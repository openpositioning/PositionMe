package com.openpositioning.PositionMe.presentation.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.model.LatLng;
import java.util.ArrayList;
import java.util.List;


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
    private List<String> observedMacs = new ArrayList<>();
    private TextView selectedVenueText;
    public void updateObservedMacs(@NonNull List<String> macs) {
        observedMacs = new ArrayList<>(macs);
    }

    private List<String> getObservedMacsOrEmpty() {
        return observedMacs == null ? new ArrayList<>() : new ArrayList<>(observedMacs);
    }
    /** Throttle WiFi debug toast to once every 5 seconds. */
    private long lastWifiToastMs = 0;

    // UI elements
    private MaterialButton completeButton, cancelButton, markTestPointButton;
    private ImageView recIcon;
    private ProgressBar timeRemaining;
    private TextView elevation, distanceTravelled, gnssError;

    // Trajectory Info Card (merged collapsible card)
    private TextView trajectoryNameText;
    private TextView initialPositionText;
    private TextView initialOrientationText;
    private TextView wifiCountText;
    private TextView bleCountText;
    private TextView imuCountText;
    private LinearLayout trajectoryInfoContent;
    private TextView collapseIcon;
    private boolean isCardExpanded = false;

    // Counters
    private int wifiFingerprints = 0;
    private int bleDeviceLists = 0;

    // Counters for live data
    private int totalWifiScans = 0;
    private int totalBleScans = 0;
    private int totalImuReadings = 0;

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
    private int testPointCounter = 0;


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

        selectedVenueText = view.findViewById(R.id.selectedVenueText);
        selectedVenueText.setText("Venue: none");


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

        // Bind Trajectory Info Card
        trajectoryNameText = view.findViewById(R.id.trajectoryNameText);
        initialPositionText = view.findViewById(R.id.initialPositionText);
        initialOrientationText = view.findViewById(R.id.initialOrientationText);
        wifiCountText = view.findViewById(R.id.wifiCountText);
        bleCountText = view.findViewById(R.id.bleCountText);
        imuCountText = view.findViewById(R.id.imuCountText);
        trajectoryInfoContent = view.findViewById(R.id.trajectoryInfoContent);
        collapseIcon = view.findViewById(R.id.collapseIcon);

        completeButton = view.findViewById(R.id.stopButton);
        cancelButton = view.findViewById(R.id.cancelButton);
        markTestPointButton= view.findViewById(R.id.markPointButton);
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

        markTestPointButton.setOnClickListener(v -> {
            long timestampMillis = System.currentTimeMillis();

            SensorFusion.getInstance().addTestPoint(timestampMillis);

            // Use fused position if available — places marker on the purple fused trajectory.
            // Falls back to raw GNSS if the particle filter hasn't initialised yet.
            double lat, lon;
            double[] fused = SensorFusion.getInstance().getFusedLatLon();

            if (fused != null) {
                lat = fused[0];
                lon = fused[1];
            } else {
                // Particle filter not yet ready — fall back to raw GNSS
                float[] gnss = SensorFusion.getInstance().getGNSSLatitude(false);
                if (gnss == null || (gnss[0] == 0.0f && gnss[1] == 0.0f)) {
                    Toast.makeText(requireContext(),
                            "Position not available yet",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                lat = gnss[0];
                lon = gnss[1];
            }

            SensorFusion.getInstance().addTestPoint(timestampMillis, lat, lon);
            testPointCounter++;

            if (trajectoryMapFragment != null) {
                trajectoryMapFragment.addTestPointMarker(
                        new LatLng(lat, lon),
                        testPointCounter
                );
            }
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

        // Set up collapse/expand functionality
        View trajectoryInfoHeader = view.findViewById(R.id.trajectoryInfoHeader);
        if (trajectoryInfoHeader != null) {
            trajectoryInfoHeader.setOnClickListener(v -> toggleCardExpansion());
        }

        // Set initial trajectory name
        if (trajectoryNameText != null) {
            trajectoryNameText.setText("📝 Recording...");

            // Update trajectory name after recording starts (with delay)
            new Handler().postDelayed(() -> {
                if (sensorFusion != null && sensorFusion.getTrajectory() != null) {
                    String trajName = sensorFusion.getTrajectory().getTrajectoryId();
                    if (trajName != null && !trajName.isEmpty() && getActivity() != null) {
                        getActivity().runOnUiThread(() ->
                                trajectoryNameText.setText("📝 " + trajName));
                    }
                }
            }, 1000); // 1 second delay to ensure trajectory is initialized
        }

        // Update initial position and orientation after recording starts
        new Handler().postDelayed(() -> {
            if (sensorFusion != null) {
                // Update initial position
                float[] latLng = sensorFusion.getGNSSLatitude(false);
                if (latLng != null && latLng.length >= 2) {
                    double lat = latLng[0];
                    double lon = latLng[1];
                    double alt = sensorFusion.getElevation();
                    updateInitialPosition(lat, lon, alt);
                }

                // Update initial orientation
                float[] initialRotation = sensorFusion.getInitialRotation();
                if (initialRotation != null) {
                    updateInitialOrientation(initialRotation);
                }
            }
        }, 1500); // 1.5 second delay to ensure GPS data is available

    }

    /**
     * Update the UI with sensor data and pass map updates to TrajectoryMapFragment.
     */
    /**
     * Update the UI with sensor data and pass map updates to TrajectoryMapFragment.
     * Called every ~200 ms by refreshDataTask.
     *
     * Passes three types of position to the map:
     *  - PDR position  → updateUserLocation()  → red/purple polyline + green dot
     *  - GNSS position → updateGNSS()          → blue polyline + blue dot
     *  - WiFi position → updateWifiPosition()  → amber dot
     */
    private void updateUIandPosition() {
        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrValues == null) return;

        // Distance
        distance += Math.sqrt(Math.pow(pdrValues[0] - previousPosX, 2)
                + Math.pow(pdrValues[1] - previousPosY, 2));
        distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));

        // Elevation
        float elevationVal = sensorFusion.getElevation();
        elevation.setText(getString(R.string.elevation, String.format("%.1f", elevationVal)));

        // Wait for the filter to acquire its first real position fix before drawing.
        double[] fused = sensorFusion.getFusedLatLon();
        if (fused == null) {
            previousPosX = pdrValues[0];
            previousPosY = pdrValues[1];
            return;
        }

        {
            LatLng oldLocation = trajectoryMapFragment.getCurrentLocation();
            LatLng newLocation = UtilFunctions.calculateNewPos(
                    oldLocation != null ? oldLocation : new LatLng(fused[0], fused[1]),
                    new float[]{ pdrValues[0] - previousPosX, pdrValues[1] - previousPosY }
            );

            // Pass the location + orientation to the map
            TrajectoryMapFragment mapFrag = (TrajectoryMapFragment)
                    getChildFragmentManager().findFragmentById(R.id.trajectoryMapFragmentContainer);

            if (mapFrag != null) {

                List<String> macs = sensorFusion.getLatestBssids();
                Log.d("RecordingFragment", "passing macs size=" + macs.size());
                mapFrag.updateObservedMacs(macs);

                mapFrag.updateUserLocation(newLocation,
                        (float) Math.toDegrees(sensorFusion.passOrientation()));

                // Drive the arrow marker and fused trajectory polyline
                mapFrag.updateFusedPosition(new LatLng(fused[0], fused[1]));
                double[] wifi = sensorFusion.getLastWifiLatLon();
                if (wifi != null) {
                    mapFrag.updateWifiPosition(new LatLng(wifi[0], wifi[1]));
                }

                mapFrag.updatePdrPosition(newLocation);
            }
        }

        double[] gnssRaw = sensorFusion.getLastGnssLatLon();
        if (gnssRaw != null && trajectoryMapFragment != null) {
            LatLng gnssLocation = new LatLng(gnssRaw[0], gnssRaw[1]);

            // Always call updateGNSS — the fragment decides internally what to show
            // based on isGnssOn (marker/path) and showGnssDots (dots)
            trajectoryMapFragment.updateGNSS(gnssLocation);

            // Show GNSS error distance only when GNSS switch is on
            if (trajectoryMapFragment.isGnssEnabled()) {
//                LatLng gnssLocation = new LatLng(gnss[0], gnss[1]);
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
        RecordingActivity act = (RecordingActivity) requireActivity();
        String v = act.getSelectedVenueIdOrName();
        selectedVenueText.setText("Venue: " + (v == null ? "none" : v));


        // Update previous
        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];

        // Update live data counts - track actual recorded data
        if (sensorFusion != null && sensorFusion.getTrajectory() != null) {
            try {
                // WiFi fingerprints (actual recorded)
                int wifiFpCount = sensorFusion.getTrajectory().getWifiFingerprintsCount();
                android.util.Log.d("RecordingUI", "WiFi Fingerprints: " + wifiFpCount);
                updateWifiCount(wifiFpCount);

                // BLE data (actual recorded)
                int bleCount = sensorFusion.getTrajectory().getBleDataCount();
                android.util.Log.d("RecordingUI", "BLE Devices: " + bleCount);
                updateBleCount(bleCount);

                // IMU readings
                int imuCount = sensorFusion.getTrajectory().getImuDataList().size();
                android.util.Log.d("RecordingUI", "IMU Readings: " + imuCount);
                updateImuCount(imuCount);
            } catch (Exception e) {
                android.util.Log.e("RecordingUI", "Error updating counts: " + e.getMessage());
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

    /**
     * Toggle card expansion/collapse
     */
    private void toggleCardExpansion() {
        isCardExpanded = !isCardExpanded;

        if (isCardExpanded) {
            trajectoryInfoContent.setVisibility(View.VISIBLE);
            collapseIcon.setText("▼");
        } else {
            trajectoryInfoContent.setVisibility(View.GONE);
            collapseIcon.setText("▶");
        }
    }

    /**
     * Update initial position display
     * Called when recording starts with initial GPS position
     */
    public void updateInitialPosition(double lat, double lon, double alt) {
        if (initialPositionText != null && getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                // Format: Lat: 55.920, Lon: -3.168, Alt: 112m
                String posText = String.format(java.util.Locale.US,
                        "Lat: %.6f, Lon: %.6f, Alt: %.1fm", lat, lon, alt);
                initialPositionText.setText(posText);
            });
        }
    }

    /**
     * Update initial orientation display
     * Called when recording starts with initial sensor orientation
     */
    public void updateInitialOrientation(float[] quaternion) {
        if (initialOrientationText != null && getActivity() != null && quaternion != null && quaternion.length >= 4) {
            getActivity().runOnUiThread(() -> {
                // Convert quaternion to Euler angles (simplified)
                // For display purposes, just show quaternion values
                String oriText = String.format(java.util.Locale.US,
                        "Q: [%.2f, %.2f, %.2f, %.2f]",
                        quaternion[0], quaternion[1], quaternion[2], quaternion[3]);
                initialOrientationText.setText(oriText);
            });
        }
    }

    /**
     * Update WiFi count display
     */
    public void updateWifiCount(int count) {
        if (wifiCountText != null && getActivity() != null) {
            totalWifiScans = count;
            getActivity().runOnUiThread(() ->
                    wifiCountText.setText(String.valueOf(totalWifiScans)));
        }
    }

    /**
     * Update BLE count display
     */
    public void updateBleCount(int count) {
        if (bleCountText != null && getActivity() != null) {
            totalBleScans = count;
            getActivity().runOnUiThread(() ->
                    bleCountText.setText(String.valueOf(totalBleScans)));
        }
    }

    /**
     * Update IMU count display
     */
    public void updateImuCount(int count) {
        if (imuCountText != null && getActivity() != null) {
            totalImuReadings = count;
            getActivity().runOnUiThread(() ->
                    imuCountText.setText(String.valueOf(totalImuReadings)));
        }
    }
}
