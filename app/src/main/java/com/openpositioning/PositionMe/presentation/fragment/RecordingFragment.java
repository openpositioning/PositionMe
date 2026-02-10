package com.openpositioning.PositionMe.presentation.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.openpositioning.PositionMe.utils.LocationDetector;
import com.openpositioning.PositionMe.utils.WeatherPromptDialog;
import com.google.android.gms.maps.model.LatLng;


/**
 * Fragment responsible for managing the recording process of trajectory data.
 * <p>
 * The RecordingFragment serves as the interface for users to initiate, monitor, and
 * complete trajectory recording. It integrates sensor fusion data to track user movement
 * and updates a map view in real time. Additionally, it provides UI controls to cancel,
 * stop, and monitor recording progress.
 * <p>
 * Features:
 * - Starts and stops trajectory recording.
 * - Displays real-time sensor data such as elevation and distance traveled.
 * - Provides UI controls to cancel or complete recording.
 * - Uses {@link TrajectoryMapFragment} to visualize recorded paths.
 * - Manages GNSS tracking and error display.
 *
 * @see TrajectoryMapFragment The map fragment displaying the recorded trajectory.
 * @see RecordingActivity The activity managing the recording workflow.
 * @see SensorFusion Handles sensor data collection.
 * @see SensorTypes Enumeration of available sensor types.
 *
 * @author Shu Gu
 */

public class RecordingFragment extends Fragment {

    // UI elements
    private MaterialButton completeButton, cancelButton;
    private ImageView recIcon;
    private ProgressBar timeRemaining;
    private TextView elevation, distanceTravelled, gnssError;

    // App settings
    private SharedPreferences settings;

    // Sensor & data logic
    private SensorFusion sensorFusion;
    private Handler refreshDataHandler;
    private CountDownTimer autoStop;

    // Distance tracking
    private float distance = 0f;
    private float previousPosX = 0f;
    private float previousPosY = 0f;

    // PDR-WiFi fusion: WiFi corrects rawPdrPosition each cycle (feedback loop)
    private LatLng rawPdrPosition = null;
    // Per-cycle correction strength: how much WiFi pulls rawPdrPosition each 200ms update
    private static final double WIFI_CORRECTION_ALPHA = 0.15;
    // Track last WiFi position used for fusion to detect actual changes
    private LatLng lastFusedWifiPos = null;
    // Max age (ms) for WiFi position to be considered fresh
    private static final long WIFI_FRESHNESS_MS = 10000;

    // References to the child map fragment
    private TrajectoryMapFragment trajectoryMapFragment;

    // Weather feature: location detector and prompt dialog
    private LocationDetector locationDetector;
    private WeatherPromptDialog weatherPromptDialog;
    private boolean weatherPromptShown = false;

    private final Runnable refreshDataTask = new Runnable() {
        @Override
        public void run() {
            updateUIandPosition();
            // Loop again
            refreshDataHandler.postDelayed(refreshDataTask, 200);
        }
    };

    public RecordingFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.sensorFusion = SensorFusion.getInstance();
        Context context = requireActivity();
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.refreshDataHandler = new Handler();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Inflate only the "recording" UI parts (no map)
        return inflater.inflate(R.layout.fragment_recording, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Child Fragment: the container in fragment_recording.xml
        // where TrajectoryMapFragment is placed
        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.trajectoryMapFragmentContainer);

        // If not present, create it
        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.trajectoryMapFragmentContainer, trajectoryMapFragment)
                    .commit();
        }

        // Initialize UI references
        elevation = view.findViewById(R.id.currentElevation);
        distanceTravelled = view.findViewById(R.id.currentDistanceTraveled);
        gnssError = view.findViewById(R.id.gnssError);

        completeButton = view.findViewById(R.id.stopButton);
        cancelButton = view.findViewById(R.id.cancelButton);
        recIcon = view.findViewById(R.id.redDot);
        timeRemaining = view.findViewById(R.id.timeRemainingBar);

        // Hide or initialize default values
        gnssError.setVisibility(View.GONE);
        elevation.setText(getString(R.string.elevation, "0"));
        distanceTravelled.setText(getString(R.string.meter, "0"));

        // Buttons
        completeButton.setOnClickListener(v -> {
            // Complete recording and go to correction screen
            // Naming will happen in CorrectionFragment after position adjustment
            completeRecording();
        });


        // Cancel button with confirmation dialog
        cancelButton.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(requireActivity())
                    .setTitle("Confirm Cancel")
                    .setMessage("Are you sure you want to cancel the recording? Your progress will be lost permanently!")
                    .setNegativeButton("Yes", (dialogInterface, which) -> {
                        // User confirmed cancellation
                        sensorFusion.stopRecording();
                        if (autoStop != null) autoStop.cancel();
                        requireActivity().onBackPressed();
                    })
                    .setPositiveButton("No", (dialogInterface, which) -> {
                        // User cancelled the dialog. Do nothing.
                        dialogInterface.dismiss();
                    })
                    .create(); // Create the dialog but do not show it yet

            // Show the dialog and change the button color
            dialog.setOnShowListener(dialogInterface -> {
                Button negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                negativeButton.setTextColor(Color.RED); // Set "Yes" button color to red
            });

            dialog.show(); // Finally, show the dialog
        });

        // The blinking effect for recIcon
        blinkingRecordingIcon();

        // Initialize weather feature components
        locationDetector = new LocationDetector();
        weatherPromptDialog = new WeatherPromptDialog(requireActivity());

        // Start the timed or indefinite UI refresh
        if (this.settings.getBoolean("split_trajectory", false)) {
            // A maximum recording time is set
            long limit = this.settings.getInt("split_duration", 30) * 60000L;
            timeRemaining.setMax((int) (limit / 1000));
            timeRemaining.setProgress(0);
            timeRemaining.setScaleY(3f);

            autoStop = new CountDownTimer(limit, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    timeRemaining.incrementProgressBy(1);
                    updateUIandPosition();
                }

                @Override
                public void onFinish() {
                    sensorFusion.stopRecording();
                    if (trajectoryMapFragment != null) trajectoryMapFragment.setCameraFollowing(false);
                    ((RecordingActivity) requireActivity()).showCorrectionScreen();
                }
            }.start();
        } else {
            // No set time limit, just keep refreshing
            refreshDataHandler.post(refreshDataTask);
        }
    }

    /**
     * Update the UI with sensor data and pass map updates to TrajectoryMapFragment.
     * Implements PDR-WiFi sensor fusion: raw PDR position is tracked separately,
     * then blended with WiFi absolute position using a weighted average.
     */
    private void updateUIandPosition() {
        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrValues == null) return;

        // PDR delta since last update
        float[] pdrDelta = { pdrValues[0] - previousPosX, pdrValues[1] - previousPosY };

        // Distance
        distance += Math.sqrt(Math.pow(pdrDelta[0], 2) + Math.pow(pdrDelta[1], 2));
        distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));

        // Elevation
        float elevationVal = sensorFusion.getElevation();
        elevation.setText(getString(R.string.elevation, String.format("%.1f", elevationVal)));

        // Update raw PDR position and apply WiFi correction
        float[] latLngArray = sensorFusion.getGNSSLatitude(true);
        if (latLngArray != null) {
            if (rawPdrPosition == null) {
                rawPdrPosition = new LatLng(latLngArray[0], latLngArray[1]);
            }
            // Step 1: Apply PDR delta to raw position
            rawPdrPosition = UtilFunctions.calculateNewPos(rawPdrPosition, pdrDelta);

            // Step 2: Get WiFi position
            LatLng wifiPos = sensorFusion.getLatLngWifiPositioning();

            // Step 3: WiFi correction feedback loop
            // Only apply correction when WiFi data is FRESH and has CHANGED
            // This prevents pinning to a stale WiFi position
            if (wifiPos != null && trajectoryMapFragment != null
                    && sensorFusion.isWifiPositionFresh(WIFI_FRESHNESS_MS)
                    && !wifiPos.equals(lastFusedWifiPos)) {
                double correctedLat = rawPdrPosition.latitude
                        + WIFI_CORRECTION_ALPHA * (wifiPos.latitude - rawPdrPosition.latitude);
                double correctedLon = rawPdrPosition.longitude
                        + WIFI_CORRECTION_ALPHA * (wifiPos.longitude - rawPdrPosition.longitude);
                rawPdrPosition = new LatLng(correctedLat, correctedLon);
                lastFusedWifiPos = wifiPos;
            }

            // Pass corrected position to map
            if (trajectoryMapFragment != null) {
                trajectoryMapFragment.updateUserLocation(rawPdrPosition,
                        (float) Math.toDegrees(sensorFusion.passOrientation()));
            }
            // Store trajectory point for correction screen
            sensorFusion.addTrajectoryPoint(rawPdrPosition.latitude, rawPdrPosition.longitude);
        }

        // GNSS display
        float[] gnss = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
        if (gnss != null && trajectoryMapFragment != null) {
            if (trajectoryMapFragment.isGnssEnabled()) {
                LatLng gnssLocation = new LatLng(gnss[0], gnss[1]);
                LatLng currentLoc = trajectoryMapFragment.getCurrentLocation();
                if (currentLoc != null) {
                    double errorDist = UtilFunctions.distanceBetweenPoints(currentLoc, gnssLocation);
                    gnssError.setVisibility(View.VISIBLE);
                    gnssError.setText(String.format(getString(R.string.gnss_error) + "%.2fm", errorDist));
                }
                trajectoryMapFragment.updateGNSS(gnssLocation);
            } else {
                gnssError.setVisibility(View.GONE);
                trajectoryMapFragment.clearGNSS();
            }
        }

        // Update previous
        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];

        // Weather feature: check if user is near building entrance
        // Only show prompt once per session and if location detector allows it
        if (!weatherPromptShown && gnss != null && locationDetector != null) {
            boolean nearEntrance = locationDetector.isNearBuildingEntrance(gnss[0], gnss[1]);
            if (nearEntrance && locationDetector.shouldShowPrompt()) {
                weatherPromptShown = true;
                if (weatherPromptDialog != null) {
                    weatherPromptDialog.showWeatherPrompt();
                }
            }
        }
    }

    /**
     * Start the blinking effect for the recording icon.
     */
    private void blinkingRecordingIcon() {
        Animation blinking = new AlphaAnimation(1, 0);
        blinking.setDuration(800);
        blinking.setInterpolator(new LinearInterpolator());
        blinking.setRepeatCount(Animation.INFINITE);
        blinking.setRepeatMode(Animation.REVERSE);
        recIcon.startAnimation(blinking);
    }

    /**
     * Show dialog to name the trajectory before completing recording.
     */
    private void showTrajectoryNameDialog() {
        // Create input field for trajectory name
        EditText input = new EditText(requireContext());
        input.setHint("Enter trajectory name");

        // Generate default name with timestamp
        String defaultName = "Traj_" + System.currentTimeMillis();
        input.setText(defaultName);
        input.selectAll(); // Select all text for easy replacement

        // Create dialog
        AlertDialog dialog = new AlertDialog.Builder(requireActivity())
                .setTitle("Name Your Trajectory")
                .setMessage("Enter a name for this trajectory recording:")
                .setView(input)
                .setPositiveButton("Complete", (dialogInterface, which) -> {
                    // Get trajectory name from input
                    String trajectoryName = input.getText().toString().trim();
                    if (trajectoryName.isEmpty()) {
                        trajectoryName = defaultName; // Use default if empty
                    }

                    // Store trajectory name in SensorFusion
                    sensorFusion.setTrajectoryName(trajectoryName);

                    // Complete the recording
                    completeRecording();
                })
                .setNegativeButton("Cancel", (dialogInterface, which) -> {
                    // User cancelled naming, do nothing
                    dialogInterface.dismiss();
                })
                .create();

        // Show keyboard automatically
        dialog.setOnShowListener(dialogInterface -> {
            input.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) requireActivity()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });

        dialog.show();
    }

    /**
     * Complete the recording process (called after naming trajectory).
     */
    private void completeRecording() {
        // Stop recording & go to correction
        if (autoStop != null) autoStop.cancel();
        sensorFusion.stopRecording();
        if (trajectoryMapFragment != null) trajectoryMapFragment.setCameraFollowing(false);
        // Show Correction screen
        ((RecordingActivity) requireActivity()).showCorrectionScreen();
    }

    @Override
    public void onPause() {
        super.onPause();
        refreshDataHandler.removeCallbacks(refreshDataTask);
        // Dismiss weather dialog if showing to prevent window leak
        if (weatherPromptDialog != null) {
            weatherPromptDialog.dismiss();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if(!this.settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
        // Reset weather prompt flag for new recording session
        weatherPromptShown = false;
    }
}
