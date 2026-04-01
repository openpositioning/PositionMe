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

import android.util.Log;
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

    private static final String TAG = "RECORDING_DEBUG";

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
        view.findViewById(R.id.btn_test_point).setOnClickListener(v -> onAddTestPoint());
        view.findViewById(R.id.btn_refresh_position).setOnClickListener(v -> onRefreshPosition());

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
     * Manually refresh the fused position by resetting the particle filter
     * and re-initializing it with the current GNSS or WiFi position.
     * This is useful when the position has drifted significantly and needs correction.
     */
    private void onRefreshPosition() {
        // Record old fused position for logging
        LatLng oldFused = sensorFusion.getFusedPosition();

        // Try GNSS position first (current, not start)
        float[] gnssLatLng = sensorFusion.getGNSSLatitude(false);
        double newLat = 0;
        double newLng = 0;
        String source = null;

        if (gnssLatLng != null && (gnssLatLng[0] != 0 || gnssLatLng[1] != 0)) {
            newLat = gnssLatLng[0];
            newLng = gnssLatLng[1];
            source = "GNSS";
        } else {
            // Fallback to WiFi positioning
            LatLng wifiPos = sensorFusion.getLatLngWifiPositioning();
            if (wifiPos != null) {
                newLat = wifiPos.latitude;
                newLng = wifiPos.longitude;
                source = "WiFi";
            }
        }

        if (source != null) {
            // Reset and re-initialize the particle filter at the new position
            sensorFusion.getPositionFusion().reset();
            sensorFusion.getPositionFusion().init(newLat, newLng);

            Log.e("POSITION_REFRESH", "Position refreshed using " + source
                    + " | old=(" + (oldFused != null ? String.format("%.7f", oldFused.latitude)
                    + "," + String.format("%.7f", oldFused.longitude) : "null")
                    + ") | new=(" + String.format("%.7f", newLat)
                    + "," + String.format("%.7f", newLng) + ")");

            Toast.makeText(requireContext(),
                    getString(R.string.refresh_position_success),
                    Toast.LENGTH_SHORT).show();
        } else {
            Log.e("POSITION_REFRESH", "No valid GNSS or WiFi position available for refresh");
            Toast.makeText(requireContext(),
                    getString(R.string.refresh_position_no_source),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Update the UI with sensor data and pass map updates to TrajectoryMapFragment.
     */
    private void updateUIandPosition() {
        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrValues == null) {
            Log.e(TAG, "PDR values are null, skipping update");
            return;
        }

        // Distance
        float dx = pdrValues[0] - previousPosX;
        float dy = pdrValues[1] - previousPosY;
        double stepDist = Math.sqrt(dx * dx + dy * dy);
        distance += stepDist;
        distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));

        // Elevation
        float elevationVal = sensorFusion.getElevation();
        elevation.setText(getString(R.string.elevation, String.format("%.1f", elevationVal)));

        // Log PDR state periodically (every ~1s = every 5th call at 200ms interval)
        if (((int)(distance * 10)) % 5 == 0) {
            Log.e(TAG, "=== PDR State ===");
            Log.e(TAG, "PDR raw: x=" + pdrValues[0] + " y=" + pdrValues[1]);
            Log.e(TAG, "PDR delta: dx=" + String.format("%.4f", dx)
                    + " dy=" + String.format("%.4f", dy)
                    + " stepDist=" + String.format("%.3f", stepDist) + "m");
            Log.e(TAG, "Total distance: " + String.format("%.2f", distance)
                    + "m | Elevation: " + String.format("%.1f", elevationVal)
                    + "m | Elevator: " + sensorFusion.getElevator());
        }

        // Current location — use fused (smooth) or raw PDR based on toggle
        LatLng fusedPos = sensorFusion.getFusedPosition();
        float[] latLngArray = sensorFusion.getGNSSLatitude(true);
        boolean useSmooth = trajectoryMapFragment != null && trajectoryMapFragment.isSmoothEnabled();

        if (useSmooth && fusedPos != null) {
            // Smooth ON: use fused position (PDR + GNSS + WiFi corrections)
            LatLng oldLocation = trajectoryMapFragment.getCurrentLocation();
            if (oldLocation == null) {
                Log.e(TAG, "=== Initial Fused Position ===");
                Log.e(TAG, "Fused pos: " + fusedPos.latitude + ", " + fusedPos.longitude);
            }

            if (trajectoryMapFragment != null) {
                float orientDeg = (float) Math.toDegrees(sensorFusion.passOrientation());
                trajectoryMapFragment.updateUserLocation(fusedPos, orientDeg);

                // Log fused vs raw PDR comparison periodically
                if (latLngArray != null && ((int)(distance * 10)) % 10 == 0) {
                    LatLng pdrOnly = UtilFunctions.calculateNewPos(
                            new LatLng(latLngArray[0], latLngArray[1]),
                            pdrValues);
                    double fusedPdrDiff = UtilFunctions.distanceBetweenPoints(fusedPos, pdrOnly);
                    Log.e(TAG, "=== Fusion vs Raw PDR ===");
                    Log.e(TAG, "Fused: " + String.format("%.7f", fusedPos.latitude)
                            + ", " + String.format("%.7f", fusedPos.longitude));
                    Log.e(TAG, "RawPDR: " + String.format("%.7f", pdrOnly.latitude)
                            + ", " + String.format("%.7f", pdrOnly.longitude));
                    Log.e(TAG, "Fused-PDR diff: " + String.format("%.2f", fusedPdrDiff) + "m");
                }
            }
        } else if (latLngArray != null) {
            // Smooth OFF or fusion not initialized: use raw PDR
            LatLng oldLocation = trajectoryMapFragment.getCurrentLocation();
            LatLng newLocation = UtilFunctions.calculateNewPos(
                    oldLocation == null ? new LatLng(latLngArray[0], latLngArray[1]) : oldLocation,
                    new float[]{ dx, dy }
            );

            if (oldLocation == null) {
                Log.e(TAG, "=== Initial Position (PDR" + (useSmooth ? " fallback" : " raw") + ") ===");
                Log.e(TAG, "Start location: lat=" + latLngArray[0] + " lng=" + latLngArray[1]);
            } else if (stepDist > 2.0) {
                Log.e(TAG, "WARNING: Large single step: " + String.format("%.2f", stepDist)
                        + "m | dx=" + dx + " dy=" + dy);
            }

            if (trajectoryMapFragment != null) {
                float orientDeg = (float) Math.toDegrees(sensorFusion.passOrientation());
                trajectoryMapFragment.updateUserLocation(newLocation, orientDeg);
            }
        } else {
            Log.e(TAG, "WARNING: No position available (fusion=null, startGNSS=null)");
        }

        // GNSS logic
        float[] gnss = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
        if (gnss != null && trajectoryMapFragment != null) {
            if (trajectoryMapFragment.isGnssEnabled()) {
                LatLng gnssLocation = new LatLng(gnss[0], gnss[1]);
                LatLng currentLoc = trajectoryMapFragment.getCurrentLocation();

                Log.e(TAG, "=== GNSS Display ===");
                Log.e(TAG, "GNSS location: " + gnss[0] + ", " + gnss[1]);
                if (currentLoc != null) {
                    double errorDist = UtilFunctions.distanceBetweenPoints(currentLoc, gnssLocation);
                    Log.e(TAG, "PDR location: " + currentLoc.latitude + ", " + currentLoc.longitude);
                    Log.e(TAG, "GNSS-PDR error: " + String.format("%.2f", errorDist) + "m");
                    if (errorDist > 100) {
                        Log.e(TAG, "WARNING: GNSS-PDR divergence >100m! Possible GNSS or PDR issue");
                    }
                    gnssError.setVisibility(View.VISIBLE);
                    gnssError.setText(String.format(getString(R.string.gnss_error) + "%.2fm", errorDist));
                }
                trajectoryMapFragment.updateGNSS(gnssLocation);
            } else {
                gnssError.setVisibility(View.GONE);
                trajectoryMapFragment.clearGNSS();
            }
        }

        // WiFi positioning — display on map + log
        LatLng wifiPos = sensorFusion.getLatLngWifiPositioning();
        if (wifiPos != null && trajectoryMapFragment != null) {
            if (trajectoryMapFragment.isWifiEnabled()) {
                trajectoryMapFragment.updateWiFi(wifiPos);
            } else {
                trajectoryMapFragment.clearWiFi();
            }

            if (((int)(distance * 10)) % 10 == 0) {
                Log.e(TAG, "=== WiFi Position Status ===");
                Log.e(TAG, "WiFi pos: " + wifiPos.latitude + ", " + wifiPos.longitude
                        + " floor=" + sensorFusion.getWifiFloor());
                LatLng currentLoc = trajectoryMapFragment.getCurrentLocation();
                if (currentLoc != null) {
                    double wifiPdrDist = UtilFunctions.distanceBetweenPoints(currentLoc, wifiPos);
                    Log.e(TAG, "WiFi-PDR distance: " + String.format("%.2f", wifiPdrDist) + "m");
                }
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
