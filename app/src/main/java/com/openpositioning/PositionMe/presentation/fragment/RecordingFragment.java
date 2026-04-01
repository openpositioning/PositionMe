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

    // References to the child map fragment
    private TrajectoryMapFragment trajectoryMapFragment;

    // Minimum displacement before the displayed point is moved.
    // Keeps the marker locked while stationary; particle-filter noise is ~0.1-0.15 m
    // per resample so 0.5 m gives comfortable headroom above the noise floor.
    private static final double MOVEMENT_THRESHOLD_METERS = 0.5;

    // Maximum single-tick displacement accepted from the fused estimate.
    // Jumps larger than this are treated as filter teleports: the PDR dead-reckoning
    // fallback is used instead so the trajectory stays physically continuous.
    private static final double MAX_JUMP_METERS = 8.0;

    // Last fused point that was actually rendered
    private LatLng lastSentFusedPosition = null;

    // PDR {x, y} coordinates (meters from recording origin) at the last accepted render.
    // Used to compute the PDR delta when the fused estimate teleports.
    private float lastAcceptedPdrX = 0f;
    private float lastAcceptedPdrY = 0f;

    // Last WiFi location sent to the map — avoids flooding wifiHistory with the same point
    private LatLng lastSentWifiPosition = null;

    private final Runnable refreshDataTask = new Runnable() {
        @Override
        public void run() {
            updateUIandPosition();
            // Loop again — 16 ms ≈ 60 fps for smooth marker and camera animation
            refreshDataHandler.postDelayed(refreshDataTask, 16);
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
     * Update the UI with sensor data and pass map updates to TrajectoryMapFragment.
     */
    private void updateUIandPosition() {
        // Elevation comes from the barometer — available immediately, no PDR needed.
        float elevationVal = sensorFusion.getElevation();
        elevation.setText(getString(R.string.elevation, String.format("%.1f", elevationVal)));

        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrValues == null) return;

        // Distance
        distance += Math.sqrt(Math.pow(pdrValues[0] - previousPosX, 2)
                + Math.pow(pdrValues[1] - previousPosY, 2));
        distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));

        // // Current location
        // // Convert PDR coordinates to actual LatLng if you have a known starting lat/lon
        // // Or simply pass relative data for the TrajectoryMapFragment to handle
        // // For example:
        // float[] latLngArray = sensorFusion.getGNSSLatitude(true);
        // if (latLngArray != null) {
        //     LatLng oldLocation = trajectoryMapFragment.getCurrentLocation(); // or store locally
        //     LatLng newLocation = UtilFunctions.calculateNewPos(
        //             oldLocation == null ? new LatLng(latLngArray[0], latLngArray[1]) : oldLocation,
        //             new float[]{ pdrValues[0] - previousPosX, pdrValues[1] - previousPosY }
        //     );

        //     // Pass the location + orientation to the map
        //     if (trajectoryMapFragment != null) {
        //         trajectoryMapFragment.updateUserLocation(newLocation,
        //                 (float) Math.toDegrees(sensorFusion.passOrientation()));
        //     }
        // }

        // Get the latest fused position from SensorFusion (best estimate of user location)
        LatLng fusedPosition = sensorFusion.getFusedPosition();

        if (fusedPosition != null && trajectoryMapFragment != null) {

            boolean isFirstPoint = (lastSentFusedPosition == null);
            double movedDistance = 0.0;
            boolean movementDetected = false;

            if (lastSentFusedPosition != null) {
                movedDistance = UtilFunctions.distanceBetweenPoints(
                        lastSentFusedPosition, fusedPosition);
                movementDetected = movedDistance >= MOVEMENT_THRESHOLD_METERS;
            }

            if (isFirstPoint || movementDetected) {
                LatLng positionToRender;

                if (!isFirstPoint && movedDistance > MAX_JUMP_METERS) {
                    // ---- TELEPORT DETECTED ----
                    // The fused estimate jumped implausibly (bad GNSS/filter divergence).
                    // Fall back to PDR dead-reckoning: apply the PDR step delta to the
                    // last confirmed anchor so the trajectory stays physically continuous.
                    float pdrDeltaX = pdrValues[0] - lastAcceptedPdrX;
                    float pdrDeltaY = pdrValues[1] - lastAcceptedPdrY;
                    double pdrStep = Math.sqrt(pdrDeltaX * pdrDeltaX + pdrDeltaY * pdrDeltaY);

                    if (pdrStep >= MOVEMENT_THRESHOLD_METERS && pdrStep < MAX_JUMP_METERS) {
                        positionToRender = UtilFunctions.convertENUToWGS84(
                                lastSentFusedPosition,
                                new float[]{pdrDeltaX, pdrDeltaY, 0f});
                        Log.d("FUSED_TEST", "Teleport " + (int) movedDistance
                                + "m → PDR fallback " + String.format("%.2f", pdrStep) + "m");
                    } else {
                        // PDR also hasn't moved enough — stay put this tick
                        positionToRender = null;
                        Log.d("FUSED_TEST", "Teleport " + (int) movedDistance
                                + "m rejected, no PDR movement yet");
                    }
                } else {
                    // Normal update — fused estimate is within plausible range
                    positionToRender = fusedPosition;
                }

                if (positionToRender != null) {
                    boolean rendered = trajectoryMapFragment.updateUserLocation(
                            positionToRender,
                            (float) Math.toDegrees(sensorFusion.passOrientation()));
                    if (rendered) {
                        lastSentFusedPosition = positionToRender;
                        lastAcceptedPdrX = pdrValues[0];
                        lastAcceptedPdrY = pdrValues[1];
                    }
                }
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

        // WiFi observation logic for colour-coded last N updates
        if (trajectoryMapFragment != null) {
            LatLng wifiLocation = sensorFusion.getLatLngWifiPositioning();
            // Only add to history when the location has actually changed (new API response)
            if (wifiLocation != null && !wifiLocation.equals(lastSentWifiPosition)) {
                Log.d("WiFiDebug", "New WiFi fix: " + wifiLocation);
                trajectoryMapFragment.updateWiFiObservation(wifiLocation);
                lastSentWifiPosition = wifiLocation;
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
    }

    @Override
    public void onResume() {
        super.onResume();
        if(!this.settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
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
