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
import com.openpositioning.PositionMe.positioning.FusionManager;

/**
 * Fragment responsible for the recording screen during a live positioning session.
 *
 * <p>Position display architecture (§3.1 + §3.2 + §3.3):</p>
 * <ol>
 *   <li><b>Primary</b>: FusionManager particle filter output, passed through
 *       MapMatchingEngine's {@code constrainPosition()} for building outline
 *       boundary enforcement.</li>
 *   <li><b>Fallback</b>: MapMatchingEngine particle filter estimate (before
 *       FusionManager initialises from WiFi/GNSS).</li>
 *   <li><b>Last resort</b>: raw PDR position from step detection.</li>
 * </ol>
 *
 * <p>Floor detection uses MapMatchingEngine's barometric particle filter
 * with 30s warmup, sustained-step guards, and stairs/lift classification.</p>
 *
 * <p>Display updates at 5 Hz (200ms interval) with colour-coded observation
 * markers for GNSS, WiFi, and PDR (§3.3).</p>
 */
public class RecordingFragment extends Fragment {

    private static final String TAG = "RecordingFragment";

    private MaterialButton completeButton, cancelButton;
    private ImageView recIcon;
    private ProgressBar timeRemaining;
    private TextView elevation, distanceTravelled, gnssError;

    private SharedPreferences settings;
    private SensorFusion sensorFusion;
    private Handler refreshDataHandler;
    private CountDownTimer autoStop;

    private float distance = 0f;
    private float previousPosX = 0f;
    private float previousPosY = 0f;

    private LatLng rawPdrPosition = null;

    private TrajectoryMapFragment trajectoryMapFragment;
    private int uiUpdateCount = 0;

    /** Tracks last MME floor to detect and log floor changes. */
    private int lastMmFloor = -1;

    // Observation display throttling — prevents marker spam on the map
    private LatLng lastDisplayedGnssObservation = null;
    private long lastDisplayedGnssTimeMs = 0L;
    private static final long GNSS_DISPLAY_MIN_INTERVAL_MS = 1500L;
    private static final double GNSS_DISPLAY_MIN_DISTANCE_M = 3.0;

    // Display-only GNSS sanity gates:
// 1) If GNSS is >25 m away from the current displayed path, don't draw it.
// 2) If a new GNSS point jumps >20 m from the last drawn GNSS point, don't draw it.
    private static final double GNSS_MAX_DISPLAY_ERROR_M = 25.0;
    private static final double GNSS_MAX_JUMP_FROM_LAST_M = 20.0;

    private LatLng lastDisplayedWifiObservation = null;
    private long lastDisplayedWifiTimeMs = 0L;
    private static final long WIFI_DISPLAY_MIN_INTERVAL_MS = 1500L;
    private static final double WIFI_DISPLAY_MIN_DISTANCE_M = 2.0;

    /** Heading smoothing to prevent arrow jitter on the map (§3.3). */
    private Float smoothedHeadingDeg = null;
    private static final float HEADING_SMOOTHING_ALPHA = 0.20f;

    /** UI refresh task at 5 Hz. */
    private final Runnable refreshDataTask = new Runnable() {
        @Override
        public void run() {
            updateUIandPosition();
            refreshDataHandler.postDelayed(refreshDataTask, 200);
        }
    };

    public RecordingFragment() {}

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
        return inflater.inflate(R.layout.fragment_recording, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.trajectoryMapFragmentContainer);

        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.trajectoryMapFragmentContainer, trajectoryMapFragment)
                    .commit();
        }

        elevation = view.findViewById(R.id.currentElevation);
        distanceTravelled = view.findViewById(R.id.currentDistanceTraveled);
        gnssError = view.findViewById(R.id.gnssError);

        completeButton = view.findViewById(R.id.stopButton);
        cancelButton = view.findViewById(R.id.cancelButton);
        recIcon = view.findViewById(R.id.redDot);
        timeRemaining = view.findViewById(R.id.timeRemainingBar);
        view.findViewById(R.id.btn_test_point).setOnClickListener(v -> onAddTestPoint());

        gnssError.setVisibility(View.GONE);
        elevation.setText(getString(R.string.elevation, "0"));
        distanceTravelled.setText(getString(R.string.meter, "0"));

        completeButton.setOnClickListener(v -> {
            if (autoStop != null) autoStop.cancel();
            sensorFusion.stopRecording();
            ((RecordingActivity) requireActivity()).showCorrectionScreen();
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

        blinkingRecordingIcon();

        if (this.settings.getBoolean("split_trajectory", false)) {
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
            refreshDataHandler.post(refreshDataTask);
        }
    }

    /** Adds a user ground-truth test point marker at the current position. */
    private void onAddTestPoint() {
        if (trajectoryMapFragment == null) return;

        LatLng cur = trajectoryMapFragment.getCurrentLocation();
        if (cur == null) {
            Toast.makeText(requireContext(),
                    "I haven't gotten my current location yet, let me take a couple of steps/wait for the map to load.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        int idx = ++testPointIndex;
        long ts = System.currentTimeMillis();
        testPoints.add(new TestPoint(idx, ts, cur.latitude, cur.longitude));
        sensorFusion.addTestPointToProto(ts, cur.latitude, cur.longitude);
        trajectoryMapFragment.addTestPointMarker(idx, ts, cur);
    }

    /**
     * Core UI update cycle. Called at 5 Hz.
     *
     * <p>Retrieves the best position from FusionManager, applies MapMatchingEngine's
     * building outline constraint, updates the map, and refreshes sensor readouts.</p>
     */
    private void updateUIandPosition() {
        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrValues == null) return;

        uiUpdateCount++;

        // Update distance counter
        distance += Math.sqrt(Math.pow(pdrValues[0] - previousPosX, 2)
                + Math.pow(pdrValues[1] - previousPosY, 2));
        distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));

        // Update elevation readout
        float elevationVal = sensorFusion.getElevation();
        elevation.setText(getString(R.string.elevation, String.format("%.1f", elevationVal)));

        com.openpositioning.PositionMe.mapmatching.MapMatchingEngine mmEngine =
                sensorFusion.getMapMatchingEngine();

        // Wait for MME initialisation before drawing — ensures traces
        // start from the manual pin position, not from (0,0)
        if (mmEngine == null || !mmEngine.isActive()) {
            previousPosX = pdrValues[0];
            previousPosY = pdrValues[1];
            return;
        }

        // Initialise raw PDR trace from the manual start pin (once)
        if (rawPdrPosition == null) {
            LatLng mmStart = mmEngine.getStartLatLng();
            if (mmStart == null) mmStart = mmEngine.getEstimatedPosition();
            if (mmStart != null) rawPdrPosition = mmStart;
        }

        // Advance raw PDR by step delta (red polyline, §3.3)
        if (rawPdrPosition != null) {
            rawPdrPosition = UtilFunctions.calculateNewPos(
                    rawPdrPosition,
                    new float[]{ pdrValues[0] - previousPosX, pdrValues[1] - previousPosY });
        }

        if (rawPdrPosition != null && trajectoryMapFragment != null) {
            trajectoryMapFragment.addRawPdrPoint(rawPdrPosition);
        }

        // ── Position source selection (§3.1 + §3.2) ─────────────────────────
        LatLng fusedLocation = null;
        String positionSource = "none";

        // Primary: FusionManager → building outline constraint
        double[] fusedPos = FusionManager.getInstance().getBestPosition();
        if (fusedPos != null) {
            fusedLocation = mmEngine.constrainPosition(fusedPos[0], fusedPos[1]);
            positionSource = "FusionManager+MMConstraint";
        }

        // Fallback: MME particle filter (before FM initialises)
        if (fusedLocation == null) {
            fusedLocation = mmEngine.getEstimatedPosition();
            if (fusedLocation != null) positionSource = "MapMatchingEngine";
        }
        // ─────────────────────────────────────────────────────────────────────

        // ── Floor detection from MME (§3.2) ──────────────────────────────────
        int mmFloor = mmEngine.getEstimatedFloor();
        if (mmFloor != lastMmFloor && lastMmFloor != -1) {
            Log.d(TAG, "MME floor change: " + lastMmFloor + " → " + mmFloor);
        }
        lastMmFloor = mmFloor;
        // ─────────────────────────────────────────────────────────────────────

        if (uiUpdateCount % 5 == 0) {
            Log.d(TAG, "Position source=" + positionSource
                    + " | floor=" + mmFloor
                    + " | fusedLoc=" + (fusedLocation == null ? "null"
                    : String.format("(%.5f, %.5f)",
                    fusedLocation.latitude, fusedLocation.longitude)));
        }

        // Smooth heading for display arrow (§3.3)
        float headingDeg = smoothHeadingDeg(
                (float) Math.toDegrees(sensorFusion.passOrientation()));

        // Update map with best position
        if (fusedLocation != null && trajectoryMapFragment != null) {
            trajectoryMapFragment.updateUserLocation(fusedLocation, headingDeg);
            trajectoryMapFragment.updatePdrObservation(fusedLocation);
        } else if (rawPdrPosition != null && trajectoryMapFragment != null) {
            trajectoryMapFragment.updateUserLocation(rawPdrPosition, headingDeg);
            trajectoryMapFragment.updatePdrObservation(rawPdrPosition);
        }

        long now = System.currentTimeMillis();

        // ── GNSS observation markers (§3.3) ──────────────────────────────────
        float[] gnss = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
        if (gnss != null && trajectoryMapFragment != null) {
            if (trajectoryMapFragment.isGnssEnabled()) {
                LatLng gnssLocation = new LatLng(gnss[0], gnss[1]);
                LatLng currentLoc = trajectoryMapFragment.getCurrentLocation();

                double errorDist = -1.0;
                if (currentLoc != null) {
                    errorDist = UtilFunctions.distanceBetweenPoints(currentLoc, gnssLocation);
                    gnssError.setVisibility(View.VISIBLE);
                    gnssError.setText(String.format(
                            getString(R.string.gnss_error) + "%.2fm", errorDist));
                } else {
                    gnssError.setVisibility(View.GONE);
                }

                boolean tooFarFromDisplayedPath =
                        (currentLoc != null && errorDist > GNSS_MAX_DISPLAY_ERROR_M);

                boolean hugeJumpFromLastDisplayed =
                        (lastDisplayedGnssObservation != null &&
                                UtilFunctions.distanceBetweenPoints(lastDisplayedGnssObservation, gnssLocation)
                                        > GNSS_MAX_JUMP_FROM_LAST_M);

                if (!tooFarFromDisplayedPath && !hugeJumpFromLastDisplayed &&
                        shouldDisplayObservation(
                                gnssLocation,
                                lastDisplayedGnssObservation,
                                now - lastDisplayedGnssTimeMs,
                                GNSS_DISPLAY_MIN_INTERVAL_MS,
                                GNSS_DISPLAY_MIN_DISTANCE_M)) {

                    trajectoryMapFragment.updateGNSS(gnssLocation);
                    lastDisplayedGnssObservation = gnssLocation;
                    lastDisplayedGnssTimeMs = now;

                } else {
                    if (tooFarFromDisplayedPath) {
                        Log.d(TAG, "Skipping GNSS draw: too far from displayed path ("
                                + String.format("%.2f", errorDist) + "m)");
                    } else if (hugeJumpFromLastDisplayed) {
                        Log.d(TAG, "Skipping GNSS draw: huge jump from last GNSS point");
                    }
                }

            } else {
                gnssError.setVisibility(View.GONE);
                trajectoryMapFragment.clearGNSS();
            }
        }

        // ── WiFi observation markers (§3.3) ──────────────────────────────────
        if (trajectoryMapFragment != null) {
            LatLng wifiLocation = sensorFusion.getLatLngWifiPositioning();
            if (wifiLocation != null && shouldDisplayObservation(
                    wifiLocation, lastDisplayedWifiObservation,
                    now - lastDisplayedWifiTimeMs,
                    WIFI_DISPLAY_MIN_INTERVAL_MS, WIFI_DISPLAY_MIN_DISTANCE_M)) {
                trajectoryMapFragment.updateWifiObservation(wifiLocation);
                lastDisplayedWifiObservation = wifiLocation;
                lastDisplayedWifiTimeMs = now;
            }
        }

        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];
    }

    /**
     * Throttles observation marker display to prevent map clutter.
     * Only shows a new marker if enough time and distance have passed.
     */
    private boolean shouldDisplayObservation(LatLng candidate, LatLng previous,
                                             long ageMs, long minIntervalMs,
                                             double minDistanceMeters) {
        if (candidate == null) return false;
        if (previous == null) return true;
        if (ageMs < minIntervalMs) return false;
        return UtilFunctions.distanceBetweenPoints(previous, candidate) >= minDistanceMeters;
    }

    /** Exponential heading smoother to prevent display arrow jitter. */
    private float smoothHeadingDeg(float newHeadingDeg) {
        if (Float.isNaN(newHeadingDeg) || Float.isInfinite(newHeadingDeg)) {
            return smoothedHeadingDeg != null ? smoothedHeadingDeg : 0f;
        }
        if (smoothedHeadingDeg == null) {
            smoothedHeadingDeg = newHeadingDeg;
            return newHeadingDeg;
        }
        float delta = newHeadingDeg - smoothedHeadingDeg;
        while (delta > 180f) delta -= 360f;
        while (delta < -180f) delta += 360f;
        smoothedHeadingDeg = smoothedHeadingDeg + HEADING_SMOOTHING_ALPHA * delta;
        return smoothedHeadingDeg;
    }

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
        if (!this.settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
    }

    // ── Test point tracking ──────────────────────────────────────────────────
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