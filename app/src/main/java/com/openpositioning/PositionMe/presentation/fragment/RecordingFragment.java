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
import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.List;

import android.widget.Toast;




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

    // Fused trajectory update loop (1-second interval)
    private Handler fusedTrajectoryHandler;
    private LatLng lastFusedPos = null;
    private LatLng lastGnssObsPos = null;
    private LatLng lastWifiObsPos = null;
    private float previousObsPosX = 0f;
    private float previousObsPosY = 0f;

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

    /** Updates the fused best-estimate marker and trajectory polyline every 1 second. */
    private final Runnable fusedTrajectoryTask = new Runnable() {
        @Override
        public void run() {
            updateFusedDisplay();
            fusedTrajectoryHandler.postDelayed(this, 1000);
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
        this.fusedTrajectoryHandler = new Handler();
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
        view.findViewById(R.id.btn_test_point).setOnClickListener(v -> onAddTestPoint());


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

        // The blinking effect for recIcon
        blinkingRecordingIcon();

        // Start the 1-second fused position + trajectory update loop
        fusedTrajectoryHandler.postDelayed(fusedTrajectoryTask, 1000);
 
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

    private void onAddTestPoint() {
        // 1) Ensure the map fragment is ready
        if (trajectoryMapFragment == null) return;

        // 2) Read current track position (must lie on the current path)
        LatLng cur = trajectoryMapFragment.getCurrentLocation();
        if (cur == null) {
            Toast.makeText(requireContext(), "" +
                    "I haven't gotten my current location yet, let me take a couple of steps/wait for the map to load.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // 3) Generate index + timestamp (satisfies "save timestamp")
        int idx = ++testPointIndex;
        long ts = System.currentTimeMillis();

        // 4) Keep a local copy for in-session tracking
        testPoints.add(new TestPoint(idx, ts, cur.latitude, cur.longitude));

        // Write test point into protobuf payload
        sensorFusion.addTestPointToProto(ts, cur.latitude, cur.longitude);

        // 5) Draw numbered marker on map (satisfies "leave numbered marker")
        trajectoryMapFragment.addTestPointMarker(idx, ts, cur);
    }

    /**
     * Updates the purple fused trajectory polyline every 1 second.
     * Uses the WiFi fix as the best estimate when available; falls back to the current
     * PDR-derived position. Raw GNSS is intentionally excluded here to avoid polyline jumps.
     * The position marker (arrow) is updated separately in the 200ms loop via
     * {@link #updateUIandPosition}.
     */
    private void updateFusedDisplay() {
        if (trajectoryMapFragment == null) return;

        // Prefer WiFi fix; fall back to PDR-derived current location (no raw GNSS)
        LatLng bestEstimate = sensorFusion.getLatLngWifiPositioning();
        if (bestEstimate == null) {
            bestEstimate = trajectoryMapFragment.getCurrentLocation();
        }
        if (bestEstimate == null) return;

        // Append to fused trajectory only if the position has moved > 0.3 m
        if (lastFusedPos == null
                || UtilFunctions.distanceBetweenPoints(lastFusedPos, bestEstimate) > 0.3) {
            trajectoryMapFragment.updateFusedTrajectory(bestEstimate);
            lastFusedPos = bestEstimate;
        }
    }

    /**
     * Update the UI with sensor data and pass map updates to TrajectoryMapFragment.
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

        // Current location
        // Convert PDR coordinates to actual LatLng if you have a known starting lat/lon
        // Or simply pass relative data for the TrajectoryMapFragment to handle
        // For example:
        float[] latLngArray = sensorFusion.getGNSSLatitude(true);
        if (latLngArray != null) {
            LatLng oldLocation = trajectoryMapFragment.getCurrentLocation(); // or store locally
            LatLng newLocation = UtilFunctions.calculateNewPos(
                    oldLocation == null ? new LatLng(latLngArray[0], latLngArray[1]) : oldLocation,
                    new float[]{ pdrValues[0] - previousPosX, pdrValues[1] - previousPosY }
            );

            // Update the red PDR polyline and move the position marker (with optional smoothing).
            // Prefer the particle filter estimate for the marker; fall back to PDR-derived position.
            if (trajectoryMapFragment != null) {
                float orientation = (float) Math.toDegrees(sensorFusion.passOrientation());
                trajectoryMapFragment.updateUserLocation(newLocation, orientation);
                LatLng fusedPos = sensorFusion.getFusedPosition();
                trajectoryMapFragment.updateFusedPosition(
                        fusedPos != null ? fusedPos : newLocation, orientation);
            }
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

        // --- Colour-coded observation markers ---
 
        // GNSS observation: add a blue marker whenever the raw GNSS fix changes
        float[] gnssRaw = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
        if (gnssRaw != null) {
            LatLng gnssObs = new LatLng(gnssRaw[0], gnssRaw[1]);
            if (!gnssObs.equals(lastGnssObsPos)) {
                trajectoryMapFragment.addObservationMarker(gnssObs,
                        TrajectoryMapFragment.ObservationSource.GNSS);
                lastGnssObsPos = gnssObs;
            }
        }
 
        // WiFi observation: add an orange marker whenever a new WiFi fix arrives
        LatLng wifiObs = sensorFusion.getLatLngWifiPositioning();
        if (wifiObs != null && !wifiObs.equals(lastWifiObsPos)) {
            trajectoryMapFragment.addObservationMarker(wifiObs,
                    TrajectoryMapFragment.ObservationSource.WIFI);
            lastWifiObsPos = wifiObs;
        }
 
        // PDR observation: add a red marker whenever PDR position has moved ≥ 1 m
        LatLng currentLoc = trajectoryMapFragment.getCurrentLocation();
        if (currentLoc != null) {
            double pdrDelta = Math.sqrt(
                    Math.pow(pdrValues[0] - previousObsPosX, 2)
                    + Math.pow(pdrValues[1] - previousObsPosY, 2));
            if (pdrDelta >= 1.0) {
                trajectoryMapFragment.addObservationMarker(currentLoc,
                        TrajectoryMapFragment.ObservationSource.PDR);
                previousObsPosX = pdrValues[0];
                previousObsPosY = pdrValues[1];
            }
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
    public void onPause() {
        super.onPause();
        refreshDataHandler.removeCallbacks(refreshDataTask);
        fusedTrajectoryHandler.removeCallbacks(fusedTrajectoryTask);
    }

    @Override
    public void onResume() {
        super.onResume();
        if(!this.settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
        fusedTrajectoryHandler.postDelayed(fusedTrajectoryTask, 1000);
    }

    private int testPointIndex = 0;

    private static class TestPoint {
        final int index;
        final long timestampMs;
        final double lat;
        final double lng;

        TestPoint(int index, long timestampMs, double lat, double lng) {
            this.index = index;
            this.timestampMs = timestampMs;
            this.lat = lat;
            this.lng = lng;
        }
    }

    private final List<TestPoint> testPoints = new ArrayList<>();


}
