package com.openpositioning.PositionMe.presentation.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.utils.PositionListener;
import com.openpositioning.PositionMe.utils.UtilFunctions;
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
 * - Visualizes fusion results from combined PDR and GNSS data.
 *
 * @see TrajectoryMapFragment The map fragment displaying the recorded trajectory.
 * @see RecordingActivity The activity managing the recording workflow.
 * @see SensorFusion Handles sensor data collection.
 * @see SensorTypes Enumeration of available sensor types.
 *
 * @author Shu Gu
 */

public class RecordingFragment extends Fragment implements PositionListener {

    private static final String TAG = "RecordingFragment";

    // UI elements
    private MaterialButton completeButton, cancelButton;
    private ImageView recIcon;
    private ProgressBar timeRemaining;
    private TextView elevation, distanceTravelled, gnssError, fusionInfoText;

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

    // Fusion tracking
    private LatLng lastFusionPosition = null;

    // References to the child map fragment
    private TrajectoryMapFragment trajectoryMapFragment;

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

        // Find fusion info text if it exists in your layout
        // If it doesn't exist in your layout, you can add it


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
            // Stop recording & go to correction
            if (autoStop != null) autoStop.cancel();
            sensorFusion.stopRecording();
            // Show Correction screen
            ((RecordingActivity) requireActivity()).showCorrectionScreen();
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

        if (!sensorFusion.isStepDetectionWorking()) {
            Log.w(TAG, "Step detection may not be working - this will affect PDR tracking");
            // Could show a warning to the user here
        }

        // The blinking effect for recIcon
        blinkingRecordingIcon();

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
     */
    private void updateUIandPosition() {
        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrValues == null) {
            Log.e(TAG, "PDR values are null in updateUIandPosition");
            return;
        }

        if(!trajectoryMapFragment.isPdrEnabled()){
            trajectoryMapFragment.clearPdrTrajectory();
        }

        // Distance
        distance += Math.sqrt(Math.pow(pdrValues[0] - previousPosX, 2)
                + Math.pow(pdrValues[1] - previousPosY, 2));
        distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));

        // Elevation
        float elevationVal = sensorFusion.getElevation();
        elevation.setText(getString(R.string.elevation, String.format("%.1f", elevationVal)));

        // Current location
        float[] latLngArray = sensorFusion.getGNSSLatitude(true);
        if (latLngArray != null) {
            LatLng oldLocation = trajectoryMapFragment.getCurrentLocation(); // or store locally
            LatLng newLocation = UtilFunctions.calculateNewPos(
                    oldLocation == null ? new LatLng(latLngArray[0], latLngArray[1]) : oldLocation,
                    new float[]{ pdrValues[0] - previousPosX, pdrValues[1] - previousPosY }
            );

            Log.d(TAG, "PDR update in updateUIandPosition: deltaX=" + (pdrValues[0] - previousPosX) +
                    ", deltaY=" + (pdrValues[1] - previousPosY) +
                    " -> newLocation=" + newLocation.latitude + ", " + newLocation.longitude);

            // Pass the location + orientation to the map
            if (trajectoryMapFragment != null) {
                trajectoryMapFragment.pdrLocation(newLocation);

                // Force polyline update if there are no points yet
                if (trajectoryMapFragment.isPolylineEmpty()) {
                    Log.d(TAG, "Forcing initial polyline update with location: " +
                            newLocation.latitude + ", " + newLocation.longitude);
                    trajectoryMapFragment.forcePolylineUpdate(newLocation);
                }
            }
        } else {
            Log.e(TAG, "latLngArray is null in updateUIandPosition");
        }

        // GNSS logic if you want to show GNSS error, etc.
        float[] gnss = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
        if (gnss != null && trajectoryMapFragment != null) {
            // If user toggles showing GNSS in the map, call e.g.
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

        // If there's a fusion position, we can calculate fusion-PDR error
        if (lastFusionPosition != null && fusionInfoText != null) {
            LatLng currentLoc = trajectoryMapFragment.getCurrentLocation();
            if (currentLoc != null) {
                double fusionPdrError = UtilFunctions.distanceBetweenPoints(currentLoc, lastFusionPosition);
                fusionInfoText.setVisibility(View.VISIBLE);
                fusionInfoText.setText(String.format("Fusion-PDR error: %.2fm", fusionPdrError));
            }
        }

        LatLng wifiLocation = sensorFusion.getLatLngWifiPositioning();
        // Update WiFi marker if the switch is enabled
        if (trajectoryMapFragment.isWifiEnabled() && wifiLocation != null) {
            trajectoryMapFragment.updateWiFi(wifiLocation);
        } else {
            trajectoryMapFragment.clearWiFi();
        }


        // Update previous
        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];
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

    @Override
    public void onStart() {
        super.onStart();
        // Register for position updates
        sensorFusion.registerPositionListener(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        // Unregister when fragment stops
        sensorFusion.unregisterPositionListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        refreshDataHandler.removeCallbacks(refreshDataTask);
    }

    @Override
    public void onResume() {
        super.onResume();
        if(!this.settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
    }

    // Implementation of PositionListener interface
    // In RecordingFragment.java, enhance the onPositionUpdate method:

    @Override
    public void onPositionUpdate(PositionListener.UpdateType updateType, LatLng position) {
        if (updateType == PositionListener.UpdateType.WIFI_ERROR) {
            Log.e("RecordingFragment", "WiFi positioning failed while RecordingFragment is active.");
            Context context = getContext();
            if (context != null) {
                Toast.makeText(context, "WiFi positioning failed. Check your connection.", Toast.LENGTH_SHORT).show();
            } else {
                Log.e("RecordingFragment", "Context is null - cannot show Toast");
            }
           // return; // early exit after handling
        }




        if (position == null) {
            Log.w("RecordingFragment", "Received null position update for type: " + updateType);
            return;
        }

        // Use a handler to ensure UI updates happen on the main thread
        new Handler(Looper.getMainLooper()).post(() -> {
            // Process different types of position updates
            switch (updateType) {
                case PDR_POSITION:
                    // PDR updates are already handled in updateUIandPosition
                    Log.d("RecordingFragment", "PDR position update: " + position.latitude + ", " + position.longitude);
                    break;

                case GNSS_POSITION:
                    // GNSS updates are already handled in updateUIandPosition
                    Log.d("RecordingFragment", "GNSS position update: " + position.latitude + ", " + position.longitude);
                    break;

                case FUSED_POSITION:
                    // Update fusion position on map
                    Log.d("RecordingFragment", "Fusion position update received: " + position.latitude + ", " + position.longitude);

                    if (trajectoryMapFragment != null) {
                        trajectoryMapFragment.updateFusionPosition(position, (float) Math.toDegrees(sensorFusion.passOrientation()));
                    } else {
                        Log.e("RecordingFragment", "Cannot update fusion position: trajectoryMapFragment is null");
                    }
                    break;

                case ORIENTATION_UPDATE:
                    // Orientation updates are handled elsewhere
                    break;




            }
        });
    }


}