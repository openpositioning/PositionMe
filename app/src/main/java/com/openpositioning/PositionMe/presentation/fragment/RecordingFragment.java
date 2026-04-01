package com.openpositioning.PositionMe.presentation.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.material.button.MaterialButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.presentation.display.DataDisplayController;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import java.util.Locale;
import java.util.Date;

/**
 * New code guide:
 * 1. Live refresh loop for fused map display.
 * 2. Recording-name flow and controlled stop handling.
 * 3. Test-point restore and tagging on the live map.
 * 4. Lightweight UI updates for distance, elevation, and GNSS error.
 */
public class RecordingFragment extends Fragment {

    private static final double DEGREE_IN_METERS = 111111.0;
    private static final long LIVE_REFRESH_INTERVAL_MS = 130L;
    private static final long LIVE_REFRESH_INTERVAL_SLOW_MS = 180L;
    private static final long LIVE_REFRESH_INTERVAL_MAX_MS = 240L;
    private static final long FLOOR_UI_SYNC_INTERVAL_MS = 450L;
    private static final long GNSS_ERROR_REFRESH_INTERVAL_MS = 320L;
    private static final float DISTANCE_UI_EPSILON_METERS = 0.02f;
    private static final float ELEVATION_UI_EPSILON_METERS = 0.05f;
    private static final double GNSS_ERROR_UI_EPSILON_METERS = 0.05;

    // UI elements
    private MaterialButton completeButton, cancelButton, addTagButton;
    private PathView pathView;
    private ImageView recIcon;
    private ProgressBar timeRemaining;
    private TextView elevation, distanceTravelled, gnssError;

    // App settings
    private SharedPreferences settings;

    // Sensor & data logic
    private SensorFusion sensorFusion;
    private Handler refreshDataHandler;
    private CountDownTimer autoStop;
    private DataDisplayController dataDisplayController;

    // Distance tracking
    private float distance = 0f;
    private float previousPosX = 0f;
    private float previousPosY = 0f;
    private float lastDisplayedDistance = Float.NaN;
    private float lastDisplayedElevation = Float.NaN;
    private double lastDisplayedGnssError = Double.NaN;
    private final float[] latestPdrPositionBuffer = new float[2];
    private final float[] latestFusedPositionBuffer = new float[2];
    private final double[] displayOriginLatLonBuffer = new double[2];

    // References to the child map fragment
    private TrajectoryMapFragment trajectoryMapFragment;

    // Add Tag counter
    private int tagCount = 0;
    private long nextRefreshIntervalMs = LIVE_REFRESH_INTERVAL_MS;
    private long lastFloorUiSyncTimeMs = 0L;
    private long lastGnssErrorRefreshTimeMs = 0L;

    // Save the test point of the user pressing "Add Tag"
    private final java.util.ArrayList<com.openpositioning.PositionMe.Traj.GNSSPosition> testPoints =
            new java.util.ArrayList<>();
    // Timestamp
    private long startTimestampMs = 0L;

    // Runs the live display loop with adaptive pacing based on the last frame cost.
    private final Runnable refreshDataTask = new Runnable() {
        @Override
        public void run() {
            long frameStartMs = SystemClock.elapsedRealtime();
            try {
                updateUIandPosition();
            } catch (Exception e) {
                Log.e("RecordingFragment", "Live refresh failed", e);
            }
            if (refreshDataHandler != null && isAdded()) {
                long frameDurationMs = SystemClock.elapsedRealtime() - frameStartMs;
                nextRefreshIntervalMs = computeNextRefreshDelayMs(frameDurationMs);
                refreshDataHandler.removeCallbacks(refreshDataTask);
                refreshDataHandler.postDelayed(refreshDataTask, nextRefreshIntervalMs);
            }
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
        this.refreshDataHandler = new Handler(Looper.getMainLooper());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_recording, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        java.util.List<com.openpositioning.PositionMe.Traj.GNSSPosition> cachedPoints =
                sensorFusion.getTestPoints();
        testPoints.clear();
        if (cachedPoints != null) {
            testPoints.addAll(cachedPoints);
        }
        tagCount = testPoints.size();
        long storedStartTimestamp = sensorFusion.getStartTimestampMs();
        startTimestampMs = storedStartTimestamp > 0L ? storedStartTimestamp : System.currentTimeMillis();
        distance = 0f;
        previousPosX = 0f;
        previousPosY = 0f;
        lastDisplayedDistance = Float.NaN;
        lastDisplayedElevation = Float.NaN;
        lastDisplayedGnssError = Double.NaN;
        nextRefreshIntervalMs = LIVE_REFRESH_INTERVAL_MS;
        lastFloorUiSyncTimeMs = 0L;
        lastGnssErrorRefreshTimeMs = 0L;

        sensorFusion.setStartTimestampMs(startTimestampMs);
        sensorFusion.setTestPoints(testPoints);

        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.trajectoryMapFragmentContainer);

        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.trajectoryMapFragmentContainer, trajectoryMapFragment)
                    .commitNow();
        }

        dataDisplayController = new DataDisplayController(sensorFusion, trajectoryMapFragment);
        dataDisplayController.reset();
        trajectoryMapFragment.clearMapAndReset();
        restorePersistedTagMarkers();

        elevation = view.findViewById(R.id.currentElevation);
        distanceTravelled = view.findViewById(R.id.currentDistanceTraveled);
        gnssError = view.findViewById(R.id.gnssError);
        pathView = view.findViewById(R.id.pathView);
        completeButton = view.findViewById(R.id.stopButton);
        cancelButton = view.findViewById(R.id.cancelButton);
        addTagButton = view.findViewById(R.id.addTagButton);
        addTagButton.bringToFront();
        addTagButton.setElevation(20f);
        recIcon = view.findViewById(R.id.redDot);
        timeRemaining = view.findViewById(R.id.timeRemainingBar);

        // Assignment 2 uses the map-based live display, so hide the old PathView overlay.
        if (pathView != null) {
            pathView.setVisibility(View.GONE);
        }
        sensorFusion.setPathView(null);

        gnssError.setVisibility(View.GONE);
        elevation.setText(getString(R.string.elevation, "0"));
        distanceTravelled.setText(getString(R.string.meter, "0"));

        completeButton.setOnClickListener(v -> {
            showRecordingNameDialog();
        });

        cancelButton.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(requireActivity())
                    .setTitle("Confirm Cancel")
                    .setMessage("Are you sure you want to cancel the recording? Your progress will be lost permanently!")
                    .setNegativeButton("Yes", (dialogInterface, which) -> {
                        sensorFusion.stopRecording();
                        if (autoStop != null) autoStop.cancel();
                        requireActivity().onBackPressed();
                    })
                    .setPositiveButton("No", (dialogInterface, which) -> dialogInterface.dismiss())
                    .create();

            dialog.setOnShowListener(dialogInterface -> {
                Button negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                negativeButton.setTextColor(Color.RED);
            });

            dialog.show();
        });

        addTagButton.setOnClickListener(v -> {
            LatLng current = resolveCurrentTagLocation();
            if (current == null) {
                Toast.makeText(requireContext(), "Position not ready yet, please try again.", Toast.LENGTH_SHORT).show();
                return;
            }

            tagCount++;
            if (trajectoryMapFragment != null) {
                trajectoryMapFragment.addTagPoint(current, tagCount);
            }

            long relativeTs = Math.max(1L, System.currentTimeMillis() - startTimestampMs);
            com.openpositioning.PositionMe.Traj.GNSSPosition p =
                    com.openpositioning.PositionMe.Traj.GNSSPosition.newBuilder()
                            .setRelativeTimestamp(relativeTs)
                            .setLatitude(current.latitude)
                            .setLongitude(current.longitude)
                            .setAltitude((double) sensorFusion.getElevation())
                            .build();

            testPoints.add(p);
            sensorFusion.appendTestPoint(p);
        });

        blinkingRecordingIcon();

        if (this.settings.getBoolean("split_trajectory", false)) {
            int splitDurationMinutes = Math.max(5, Math.min(30, this.settings.getInt("split_duration", 10)));
            long limit = splitDurationMinutes * 60000L;
            timeRemaining.setVisibility(View.VISIBLE);
            timeRemaining.setMax((int) (limit / 1000));
            timeRemaining.setProgress(0);

            autoStop = new CountDownTimer(limit, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    timeRemaining.incrementProgressBy(1);
                }

                @Override
                public void onFinish() {
                    sensorFusion.stopRecording();
                    if (!isAdded()) return;
                    if (getActivity() instanceof RecordingActivity) {
                        ((RecordingActivity) getActivity()).showCorrectionScreen();
                    }
                }
            }.start();
        }
        startLiveRefreshLoop();
    }

    private void startLiveRefreshLoop() {
        if (refreshDataHandler == null || !isAdded()) {
            return;
        }
        nextRefreshIntervalMs = LIVE_REFRESH_INTERVAL_MS;
        refreshDataHandler.removeCallbacks(refreshDataTask);
        refreshDataHandler.post(refreshDataTask);
    }

    private long computeNextRefreshDelayMs(long frameDurationMs) {
        if (frameDurationMs >= 120L) {
            return LIVE_REFRESH_INTERVAL_MAX_MS;
        }
        if (frameDurationMs >= 70L) {
            return LIVE_REFRESH_INTERVAL_SLOW_MS;
        }
        return LIVE_REFRESH_INTERVAL_MS;
    }

    // Lets the user confirm a readable recording name before the upload/save step.
    private void showRecordingNameDialog() {
        if (!isAdded()) {
            return;
        }

        EditText input = new EditText(requireContext());
        input.setHint(getString(R.string.recording_name_hint));
        input.setSingleLine(true);
        input.setText(buildDefaultRecordingName());
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.recording_name_title)
                .setView(input)
                .setPositiveButton(R.string.save_recording_name, (dialog, which) -> {
                    String chosenName = input.getText() == null ? "" : input.getText().toString().trim();
                    finalizeRecordingWithName(chosenName);
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss())
                .show();
    }

    private String buildDefaultRecordingName() {
        String timestampLabel = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm",
                Locale.getDefault()
        ).format(new Date(Math.max(startTimestampMs, System.currentTimeMillis())));
        return getString(R.string.recording_name_default) + " " + timestampLabel;
    }

    // Freezes the chosen metadata and hands control back to the correction screen.
    private void finalizeRecordingWithName(String chosenName) {
        sensorFusion.setPendingRecordingName(chosenName);
        sensorFusion.setStartTimestampMs(startTimestampMs);
        sensorFusion.setTestPoints(testPoints);

        if (autoStop != null) autoStop.cancel();
        sensorFusion.stopRecording();
        ((RecordingActivity) requireActivity()).showCorrectionScreen();
    }

    /**
     * Update the UI with sensor data and hand off live map rendering to DataDisplayController.
     */
    private void updateUIandPosition() {
        try {
            long nowMs = SystemClock.elapsedRealtime();
            if (!sensorFusion.copyLatestPdrPositionXY(latestPdrPositionBuffer)) return;

            float dx = latestPdrPositionBuffer[0] - previousPosX;
            float dy = latestPdrPositionBuffer[1] - previousPosY;
            float segmentDistance = (float) Math.hypot(dx, dy);
            if (Float.isFinite(segmentDistance) && segmentDistance > 1e-4f) {
                distance += segmentDistance;
            }
            updateDistanceText(distance);

            float elevationVal = sensorFusion.getElevation();
            updateElevationText(elevationVal);

            if (trajectoryMapFragment != null
                    && (nowMs - lastFloorUiSyncTimeMs) >= FLOOR_UI_SYNC_INTERVAL_MS) {
                trajectoryMapFragment.updateElevation();
                lastFloorUiSyncTimeMs = nowMs;
            }

            if (dataDisplayController != null) {
                dataDisplayController.renderFrame();
            }

            if (trajectoryMapFragment != null && trajectoryMapFragment.isGnssEnabled()) {
                if ((nowMs - lastGnssErrorRefreshTimeMs) >= GNSS_ERROR_REFRESH_INTERVAL_MS) {
                    LatLng currentLoc = trajectoryMapFragment.getCurrentLocation();
                    LatLng gnssLocation = sensorFusion.getLatestGnssLatLng();
                    if (currentLoc != null && gnssLocation != null) {
                        double errorDist = UtilFunctions.distanceBetweenPoints(currentLoc, gnssLocation);
                        updateGnssError(errorDist);
                    } else {
                        lastDisplayedGnssError = Double.NaN;
                        gnssError.setVisibility(View.GONE);
                    }
                    lastGnssErrorRefreshTimeMs = nowMs;
                }
            } else {
                lastDisplayedGnssError = Double.NaN;
                gnssError.setVisibility(View.GONE);
            }

            previousPosX = latestPdrPositionBuffer[0];
            previousPosY = latestPdrPositionBuffer[1];
        } catch (Exception e) {
            Log.e("RecordingFragment", "updateUIandPosition failed", e);
        }
    }

    private void blinkingRecordingIcon() {
        Animation blinking = new AlphaAnimation(1, 0);
        blinking.setDuration(800);
        blinking.setInterpolator(new LinearInterpolator());
        blinking.setRepeatCount(Animation.INFINITE);
        blinking.setRepeatMode(Animation.REVERSE);
        recIcon.startAnimation(blinking);
    }

    private void updateDistanceText(float distanceMeters) {
        if (!Float.isNaN(lastDisplayedDistance)
                && Math.abs(distanceMeters - lastDisplayedDistance) < DISTANCE_UI_EPSILON_METERS) {
            return;
        }
        lastDisplayedDistance = distanceMeters;
        distanceTravelled.setText(getString(
                R.string.meter,
                String.format(Locale.US, "%.2f", distanceMeters)
        ));
    }

    private void updateElevationText(float elevationMeters) {
        if (!Float.isNaN(lastDisplayedElevation)
                && Math.abs(elevationMeters - lastDisplayedElevation) < ELEVATION_UI_EPSILON_METERS) {
            return;
        }
        lastDisplayedElevation = elevationMeters;
        elevation.setText(getString(
                R.string.elevation,
                String.format(Locale.US, "%.1f", elevationMeters)
        ));
    }

    private void updateGnssError(double errorMeters) {
        gnssError.setVisibility(View.VISIBLE);
        if (!Double.isNaN(lastDisplayedGnssError)
                && Math.abs(errorMeters - lastDisplayedGnssError) < GNSS_ERROR_UI_EPSILON_METERS) {
            return;
        }
        lastDisplayedGnssError = errorMeters;
        gnssError.setText(String.format(Locale.US, "%s%.2fm", getString(R.string.gnss_error), errorMeters));
    }

    // Restores previously saved test points when the fragment is recreated mid-session.
    private void restorePersistedTagMarkers() {
        if (trajectoryMapFragment == null || testPoints.isEmpty()) {
            return;
        }
        int markerIndex = 1;
        for (com.openpositioning.PositionMe.Traj.GNSSPosition point : testPoints) {
            if (point == null) {
                continue;
            }
            double lat = point.getLatitude();
            double lon = point.getLongitude();
            if (Double.isNaN(lat) || Double.isNaN(lon)) {
                continue;
            }
            trajectoryMapFragment.addTagPoint(new LatLng(lat, lon), markerIndex++);
        }
    }

    // Resolves the best available map position for a new manual test marker.
    @Nullable
    private LatLng resolveCurrentTagLocation() {
        if (trajectoryMapFragment != null) {
            LatLng current = trajectoryMapFragment.getCurrentLocation();
            if (current != null) {
                return current;
            }
        }

        if (sensorFusion.copyLatestFusedPositionXY(latestFusedPositionBuffer)
                && sensorFusion.copyDisplayOriginLatLon(displayOriginLatLonBuffer)) {
            double lat = displayOriginLatLonBuffer[0] + (latestFusedPositionBuffer[1] / DEGREE_IN_METERS);
            double lon = displayOriginLatLonBuffer[1] + (latestFusedPositionBuffer[0]
                    / (DEGREE_IN_METERS * Math.cos(Math.toRadians(displayOriginLatLonBuffer[0]))));
            return new LatLng(lat, lon);
        }

        LatLng wifiLocation = sensorFusion.getLatestWifiLatLng();
        if (wifiLocation != null) {
            return wifiLocation;
        }
        return sensorFusion.getLatestGnssLatLng();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (refreshDataHandler != null) {
            refreshDataHandler.removeCallbacks(refreshDataTask);
        }
        if (recIcon != null) {
            recIcon.clearAnimation();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        sensorFusion.setPathView(null);
        sensorFusion.resumeListening();
        if (recIcon != null) {
            blinkingRecordingIcon();
        }
        startLiveRefreshLoop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (refreshDataHandler != null) {
            refreshDataHandler.removeCallbacks(refreshDataTask);
        }
        if (autoStop != null) {
            autoStop.cancel();
        }
        if (recIcon != null) {
            recIcon.clearAnimation();
        }
        dataDisplayController = null;
        trajectoryMapFragment = null;
        pathView = null;
    }
}
