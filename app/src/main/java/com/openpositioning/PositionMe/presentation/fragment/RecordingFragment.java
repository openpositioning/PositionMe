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
import com.openpositioning.PositionMe.fusion.FusedPose;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.utils.TcpClient;
import com.openpositioning.PositionMe.utils.TcpPacketSender;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecordingFragment
 *
 * Live-trajectory policy in this cleaned version:
 * - RED trajectory = PF fused pose only
 * - BLUE trajectory = raw PDR only
 * - No fallback from PF red trajectory to standard PDR
 *
 * Important:
 * - raw PDR is still updated every refresh cycle for reference
 * - PF fused pose is the only pose passed into TrajectoryMapFragment.updateUserLocation(...)
 * - therefore map matching / map constraints / wall-stairs-lift debug are tied to the red trajectory
 */
public class RecordingFragment extends Fragment {

    private static final String TAG = "RecordingFragment";

    // UI elements
    private MaterialButton completeButton;
    private MaterialButton cancelButton;
    private ImageView recIcon;
    private ProgressBar timeRemaining;
    private TextView elevation;
    private TextView distanceTravelled;
    private TextView gnssError;

    // App settings
    private SharedPreferences settings;

    // Sensor and data logic
    private SensorFusion sensorFusion;
    private Handler refreshDataHandler;
    private CountDownTimer autoStop;

    // Distance tracking from raw PDR only
    private float distance = 0f;
    private float previousPosX = 0f;
    private float previousPosY = 0f;

    // Child map fragment
    private TrajectoryMapFragment trajectoryMapFragment;

    // TCP streaming to desktop GUI
    private TcpClient tcpClient;
    private TcpPacketSender tcpPacketSender;
    private final Handler tcpHandler = new Handler(Looper.getMainLooper());
    private boolean tcpStreamingEnabled = false;

    // Autonomous initial map anchoring for raw PDR reference path
    private static final long INITIAL_ANCHOR_STABLE_DURATION_MS = 1000L;
    private static final int INITIAL_ANCHOR_REQUIRED_SAMPLES = 3;
    private static final double INITIAL_ANCHOR_MAX_JUMP_METRES = 8.0;
    private static final float LIVE_DRAW_MIN_PDR_DELTA_METERS = 0.03f;
    private static final long LIVE_DRAW_STATIONARY_HOLD_MS = 800L;
    private long lastLiveMovementUptimeMs = 0L;

    private static final long PF_UI_STEP_INTERVAL_MS = 120L;
    private static final float PF_UI_MIN_PDR_DELTA_METERS = 0.05f;
    private static final double PF_UI_MIN_HEADING_DELTA_RAD = Math.toRadians(4.0);

    private long lastPfUiStepElapsedMs = 0L;
    private float lastPfUiStepPdrX = 0f;
    private float lastPfUiStepPdrY = 0f;
    private double lastPfUiStepHeadingRad = 0.0;
    private boolean pfUiStepInitialised = false;
    private static final long NO_STEP_FLOOR_EVAL_INTERVAL_MS = 800L;
    private long lastNoStepFloorEvalElapsedMs = 0L;

    private enum InitialAnchorSource {
        WIFI,
        GNSS
    }

    private LatLng autonomousInitialLocation = null;
    private boolean initialMapLocationSeeded = false;
    private LatLng pendingInitialAnchorCandidate = null;
    private InitialAnchorSource pendingInitialAnchorSource = null;
    private long pendingInitialAnchorSinceMs = 0L;
    private int pendingInitialAnchorSamples = 0;

    // Test point handling
    private int testPointIndex = 0;
    private final List<TestPoint> testPoints = new ArrayList<>();

    /**
     * Runnable used to periodically refresh UI and live map data.
     */
    private final Runnable refreshDataTask = new Runnable() {
        @Override
        public void run() {
            updateUIandPosition();
            refreshDataHandler.postDelayed(this, 200);
        }
    };

    public RecordingFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sensorFusion = SensorFusion.getInstance();
        Context context = requireActivity();
        settings = PreferenceManager.getDefaultSharedPreferences(context);
        refreshDataHandler = new Handler();
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
                    .commitNow();
        }

        // Try to seed the initial raw-PDR anchor immediately.
        seedInitialMapLocation();

        // UI references
        elevation = view.findViewById(R.id.currentElevation);
        distanceTravelled = view.findViewById(R.id.currentDistanceTraveled);
        gnssError = view.findViewById(R.id.gnssError);

        completeButton = view.findViewById(R.id.stopButton);
        cancelButton = view.findViewById(R.id.cancelButton);
        recIcon = view.findViewById(R.id.redDot);
        timeRemaining = view.findViewById(R.id.timeRemainingBar);

        View testPointButton = view.findViewById(R.id.btn_test_point);
        if (testPointButton != null) {
            testPointButton.setOnClickListener(v -> onAddTestPoint());
        }

        gnssError.setVisibility(View.GONE);
        elevation.setText(getString(R.string.elevation, "0"));
        distanceTravelled.setText(getString(R.string.meter, "0"));

        completeButton.setOnClickListener(v -> {
            if (autoStop != null) {
                autoStop.cancel();
            }
            refreshDataHandler.removeCallbacks(refreshDataTask);
            stopTcpStreaming();
            sensorFusion.stopRecording();

            new AlertDialog.Builder(requireActivity())
                    .setTitle("Save trajectory?")
                    .setMessage("Do you want to save trajectory into JSON locally?")
                    .setPositiveButton("Save", (dialog, which) -> {
                        try {
                            File dir = new File(requireContext().getExternalFilesDir(null), "trajectories");
                            if (!dir.exists()) {
                                dir.mkdirs();
                            }

                            String timestamp = new SimpleDateFormat(
                                    "yyyyMMdd_HHmmss", Locale.UK).format(new Date());

                            String trajName = sensorFusion.getTrajectoryId();
                            if (trajName == null || trajName.trim().isEmpty()) {
                                trajName = "traj";
                            }

                            trajName = trajName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                            String baseName = trajName + "_" + timestamp;

                            File jsonFile = new File(dir, baseName + ".json");
                            File csvFile = new File(dir, baseName + ".csv");

                            sensorFusion.saveTestPointToCSV(csvFile);
                            sensorFusion.saveRecordingToJSON(jsonFile);

                            Toast.makeText(
                                    requireContext(),
                                    "Saved to /trajectories folder",
                                    Toast.LENGTH_SHORT
                            ).show();

                        } catch (Exception e) {
                            e.printStackTrace();
                            Toast.makeText(
                                    requireContext(),
                                    "Save failed: " + e.getMessage(),
                                    Toast.LENGTH_LONG
                            ).show();
                        }

                        ((RecordingActivity) requireActivity()).showCorrectionScreen();
                    })
                    .setNegativeButton("Don't save", (dialog, which) ->
                            ((RecordingActivity) requireActivity()).showCorrectionScreen())
                    .setCancelable(false)
                    .show();
        });

        cancelButton.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(requireActivity())
                    .setTitle("Confirm Cancel")
                    .setMessage("Are you sure you want to cancel the recording? Your progress will be lost permanently!")
                    .setNegativeButton("Yes", (dialogInterface, which) -> {
                        stopTcpStreaming();
                        sensorFusion.stopRecording();
                        if (autoStop != null) {
                            autoStop.cancel();
                        }
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
        startTcpStreaming();

        pfUiStepInitialised = false;
        lastPfUiStepElapsedMs = 0L;

        if (settings.getBoolean("split_trajectory", false)) {
            long limit = settings.getInt("split_duration", 30) * 60000L;
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
                    stopTcpStreaming();
                    sensorFusion.stopRecording();
                    ((RecordingActivity) requireActivity()).showCorrectionScreen();
                }
            }.start();
        } else {
            refreshDataHandler.post(refreshDataTask);
        }
    }

    /**
     * Add a numbered test point marker at the current displayed trajectory position.
     * This uses the current displayed location (red PF trajectory), not raw PDR.
     */
    private void onAddTestPoint() {
        if (trajectoryMapFragment == null) {
            return;
        }

        LatLng cur = trajectoryMapFragment.getCurrentLocation();
        if (cur == null) {
            Toast.makeText(
                    requireContext(),
                    "I haven't gotten my current location yet, let me take a couple of steps/wait for the map to load.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        if (!testPoints.isEmpty()) {
            TestPoint last = testPoints.get(testPoints.size() - 1);
            LatLng lastLatLng = new LatLng(last.lat, last.lng);
            double dist = UtilFunctions.distanceBetweenPoints(lastLatLng, cur);

            if (dist < 0.5) {
                Toast.makeText(
                        requireContext(),
                        "Test point not added because the location has not changed enough.",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }
        }

        int idx = ++testPointIndex;
        long ts = System.currentTimeMillis();

        testPoints.add(new TestPoint(idx, ts, cur.latitude, cur.longitude));
        sensorFusion.addTestPointToProto(ts, cur.latitude, cur.longitude);

        trajectoryMapFragment.addTestPointMarker(idx, ts, cur);

        Log.d(TAG, String.format(
                Locale.UK,
                "TEST_POINT added idx=%d ts=%d lat=%.6f lon=%.6f",
                idx,
                ts,
                cur.latitude,
                cur.longitude
        ));
    }

    /**
     * Refreshes UI and updates:
     * - blue raw-PDR reference path
     * - red PF fused trajectory only
     * - WiFi/GNSS overlays
     */
    private void updateUIandPosition() {
        if (!initialMapLocationSeeded) {
            seedInitialMapLocation();
        }

        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrValues == null || trajectoryMapFragment == null) {
            return;
        }

        maybeStepParticleFilterForLiveUi();

//        boolean shouldAdvanceLiveTrajectory = shouldUpdateLiveTrajectory(pdrValues);

        float prevX = previousPosX;
        float prevY = previousPosY;

        // Distance uses raw PDR increments only.
        distance += Math.sqrt(
                Math.pow(pdrValues[0] - prevX, 2) +
                        Math.pow(pdrValues[1] - prevY, 2)
        );
        distanceTravelled.setText(
                getString(R.string.meter, String.format(Locale.UK, "%.2f", distance))
        );

        // Blue raw-PDR path
        if (autonomousInitialLocation != null) {
            LatLng rawPdrLocation = UtilFunctions.calculateNewPos(autonomousInitialLocation, pdrValues);
            trajectoryMapFragment.updateRawPdrObservation(rawPdrLocation);
        }

        // Elevation display
        float elevationVal = sensorFusion.getElevation();
        elevation.setText(
                getString(R.string.elevation, String.format(Locale.UK, "%.1f", elevationVal))
        );

        //Let PF evaluate lift/floor transitions even if user is not moving
        maybeEvaluateNoStepFloorChange();

        // Red main trajectory = PF fused only
        trajectoryMapFragment.setCurrentTrajectoryPlotSource("PF fused");
        updateLiveMapWithParticleFilterOnly();

        // WiFi overlay
        LatLng wifiLatLng = sensorFusion.getLatLngWifiPositioning();
        if (wifiLatLng != null && trajectoryMapFragment.isWifiEnabled()) {
            trajectoryMapFragment.updateWifi(wifiLatLng);
        } else {
            trajectoryMapFragment.clearWifi();
        }

        // GNSS overlay + error against current displayed red trajectory
        float[] gnss = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
        if (gnss != null && trajectoryMapFragment.isGnssEnabled()) {
            LatLng gnssLocation = new LatLng(gnss[0], gnss[1]);
            LatLng currentLoc = trajectoryMapFragment.getCurrentLocation();

            if (currentLoc != null) {
                double errorDist = UtilFunctions.distanceBetweenPoints(currentLoc, gnssLocation);
                gnssError.setVisibility(View.VISIBLE);
                gnssError.setText(String.format(
                        Locale.UK,
                        getString(R.string.gnss_error) + "%.2fm",
                        errorDist
                ));
            } else {
                gnssError.setVisibility(View.GONE);
            }

            trajectoryMapFragment.updateGNSS(gnssLocation);
        } else {
            gnssError.setVisibility(View.GONE);
            trajectoryMapFragment.clearGNSS();
        }

        trajectoryMapFragment.refreshLiveDebugBox();

        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];
    }

    private void maybeEvaluateNoStepFloorChange() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNoStepFloorEvalElapsedMs < NO_STEP_FLOOR_EVAL_INTERVAL_MS) {
            return;
        }

        lastNoStepFloorEvalElapsedMs = now;
        sensorFusion.evaluateParticleFilterFloorChangeWithoutStep();
    }

    private void maybeStepParticleFilterForLiveUi() {
        if (sensorFusion == null || !sensorFusion.isParticleFilterTrajectoryMode()) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastPfUiStepElapsedMs < PF_UI_STEP_INTERVAL_MS) {
            return;
        }

        float[] pdr = sensorFusion.getLatestPdrMovement();
        if (pdr == null || pdr.length < 2) {
            return;
        }

        double headingRad = sensorFusion.getSelectedHeadingRad();

        if (!pfUiStepInitialised) {
            pfUiStepInitialised = true;
            lastPfUiStepElapsedMs = now;
            lastPfUiStepPdrX = pdr[0];
            lastPfUiStepPdrY = pdr[1];
            lastPfUiStepHeadingRad = headingRad;
            return;
        }

        float dx = pdr[0] - lastPfUiStepPdrX;
        float dy = pdr[1] - lastPfUiStepPdrY;
        double deltaPdrMeters = Math.hypot(dx, dy);
        double deltaHeadingRad = Math.abs(wrapAngle(headingRad - lastPfUiStepHeadingRad));

        if (deltaPdrMeters < PF_UI_MIN_PDR_DELTA_METERS
                && deltaHeadingRad < PF_UI_MIN_HEADING_DELTA_RAD) {
            return;
        }

        lastPfUiStepElapsedMs = now;
        lastPfUiStepPdrX = pdr[0];
        lastPfUiStepPdrY = pdr[1];
        lastPfUiStepHeadingRad = headingRad;

//        sensorFusion.stepParticleFilter();
    }

    private double wrapAngle(double angle) {
        while (angle > Math.PI) angle -= 2.0 * Math.PI;
        while (angle < -Math.PI) angle += 2.0 * Math.PI;
        return angle;
    }

    /**
     * Updates the map using PF fused pose only.
     *
     * Important:
     * - no fallback to standard PDR
     * - always publishes the latest fused pose to the map fragment
     * - duplicate fused points are naturally suppressed by the renderer
     * - this allows floor changes / wall recovery / stationary corrections
     *   to still appear in live UI when needed
     */
    private void updateLiveMapWithParticleFilterOnly() {
        if (trajectoryMapFragment == null) {
            return;
        }

        FusedPose fusedPose = sensorFusion.getLatestFusedPose();

        if (fusedPose == null || fusedPose.getLatLng() == null) {
            trajectoryMapFragment.setCurrentTrajectoryPlotSource("PF waiting");
            Log.d(TAG, "PF fused pose not ready yet; skip red trajectory update until PF is ready.");
            return;
        }

        if (!sensorFusion.shouldDrawLatestParticleFilterPose()) {
            trajectoryMapFragment.setCurrentTrajectoryPlotSource("PF waiting");
            Log.d(TAG, "PF draw skipped: no meaningful PF pose available");
            return;
        }

        trajectoryMapFragment.setCurrentTrajectoryPlotSource("PF fused");

        Log.d(TAG,
                "PF branch using fused pose: " + fusedPose.getLatLng()
                        + ", floor=" + fusedPose.getFloor()
                        + ", confidence=" + fusedPose.getConfidence());

        trajectoryMapFragment.updateUserLocation(
                fusedPose.getLatLng(),
                (float) Math.toDegrees(fusedPose.getHeadingRad())
        );

        trajectoryMapFragment.updateDebugInfo((float) fusedPose.getHeadingRad());
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
     * Starts TCP streaming of live JSON packets to the desktop GUI.
     */
    private void startTcpStreaming() {
        if (tcpStreamingEnabled) {
            return;
        }

        String serverIp = "172.20.10.3";
        int serverPort = 6000;

        tcpClient = new TcpClient(serverIp, serverPort);
        tcpPacketSender = new TcpPacketSender(sensorFusion, tcpClient);
        tcpStreamingEnabled = true;

        tcpHandler.postDelayed(tcpStreamRunnable, 1000);
    }

    /**
     * Stops TCP streaming and closes the client socket.
     */
    private void stopTcpStreaming() {
        tcpStreamingEnabled = false;
        tcpHandler.removeCallbacks(tcpStreamRunnable);

        if (tcpClient != null) {
            tcpClient.stopClient();
            tcpClient = null;
        }

        tcpPacketSender = null;
    }

    /**
     * Seeds the raw-PDR anchor for live display.
     *
     * Priority:
     * 1. user-confirmed manual start anchor
     * 2. stored session start anchor
     * 3. WiFi
     * 4. GNSS
     *
     * Important:
     * this anchor is for the blue raw-PDR reference path only.
     * The red fused trajectory is still owned by the PF pipeline.
     */
    private void seedInitialMapLocation() {
        if (trajectoryMapFragment == null || initialMapLocationSeeded) {
            return;
        }

        LatLng initialFix = sensorFusion.resolvePreferredStartAnchor();

        if (initialFix == null) {
            Log.d(TAG, "Waiting for initial preferred start anchor before seeding raw PDR path.");
            return;
        }

        autonomousInitialLocation = initialFix;
        initialMapLocationSeeded = true;

        sensorFusion.setStartGNSSLatitude(new float[]{
                (float) initialFix.latitude,
                (float) initialFix.longitude
        });

        sensorFusion.writeInitialMetadata();

        trajectoryMapFragment.setCurrentTrajectoryPlotSource("PF waiting");
        tryShowInitialFusedPose();

        Log.d(TAG,
                "Live raw-PDR anchor seeded at "
                        + initialFix.latitude + ", " + initialFix.longitude);
    }

    /**
     * Tries to show the initial PF fused pose immediately, before the user starts moving.
     *
     * This does not change your live update flow.
     * It only forces one PF initialisation attempt after the start anchor is known.
     */
    private void tryShowInitialFusedPose() {
        if (trajectoryMapFragment == null) {
            return;
        }

        sensorFusion.initialiseParticleFilterIfNeeded();

        FusedPose fusedPose = sensorFusion.getLatestFusedPose();
        if (fusedPose == null || fusedPose.getLatLng() == null) {
            return;
        }

        trajectoryMapFragment.setCurrentTrajectoryPlotSource("PF fused");

        trajectoryMapFragment.updateUserLocation(
                fusedPose.getLatLng(),
                (float) Math.toDegrees(fusedPose.getHeadingRad())
        );

        trajectoryMapFragment.updateDebugInfo((float) fusedPose.getHeadingRad());
        trajectoryMapFragment.refreshLiveDebugBox();

        Log.d(TAG,
                "Initial PF fused pose shown before movement: "
                        + fusedPose.getLatLng()
                        + ", floor=" + fusedPose.getFloor());
    }

    /**
     * Resolves a stable autonomous absolute position.
     *
     * Policy:
     * 1. Prefer GNSS first for the initial anchor.
     * 2. Use WiFi only as fallback.
     * 3. Require the candidate to remain stable briefly before locking.
     */
    @Nullable
    private LatLng resolveAutonomousInitialLocation() {
        float[] gnssLatLng = sensorFusion.getGNSSLatitude(false);

        LatLng candidateLatLng = null;
        InitialAnchorSource candidateSource = null;

        if (gnssLatLng != null
                && gnssLatLng.length >= 2
                && !(Math.abs(gnssLatLng[0]) < 1e-6 && Math.abs(gnssLatLng[1]) < 1e-6)) {
            candidateLatLng = new LatLng(gnssLatLng[0], gnssLatLng[1]);
            candidateSource = InitialAnchorSource.GNSS;
        } else {
            LatLng wifiLatLng = sensorFusion.getLatLngWifiPositioning();
            if (isValidLatLng(wifiLatLng)) {
                candidateLatLng = wifiLatLng;
                candidateSource = InitialAnchorSource.WIFI;
            }
        }

        if (candidateLatLng == null || candidateSource == null) {
            resetPendingInitialAnchor("no usable GNSS/WiFi fix yet");
            return null;
        }

        long now = System.currentTimeMillis();
        boolean sourceChanged = pendingInitialAnchorSource != candidateSource;
        boolean jumpTooLarge = pendingInitialAnchorCandidate != null
                && UtilFunctions.distanceBetweenPoints(pendingInitialAnchorCandidate, candidateLatLng)
                > INITIAL_ANCHOR_MAX_JUMP_METRES;

        if (pendingInitialAnchorCandidate == null || sourceChanged || jumpTooLarge) {
            pendingInitialAnchorCandidate = candidateLatLng;
            pendingInitialAnchorSource = candidateSource;
            pendingInitialAnchorSinceMs = now;
            pendingInitialAnchorSamples = 1;

            Log.d(TAG, String.format(
                    Locale.UK,
                    "STEP3_ANCHOR pending source=%s lat=%.6f lon=%.6f",
                    candidateSource,
                    candidateLatLng.latitude,
                    candidateLatLng.longitude
            ));
            return null;
        }

        pendingInitialAnchorSamples++;
        long stableDurationMs = now - pendingInitialAnchorSinceMs;
        if (pendingInitialAnchorSamples >= INITIAL_ANCHOR_REQUIRED_SAMPLES
                || stableDurationMs >= INITIAL_ANCHOR_STABLE_DURATION_MS) {
            Log.d(TAG, String.format(
                    Locale.UK,
                    "STEP3_ANCHOR accepted stable source=%s samples=%d stableMs=%d lat=%.6f lon=%.6f",
                    candidateSource,
                    pendingInitialAnchorSamples,
                    stableDurationMs,
                    candidateLatLng.latitude,
                    candidateLatLng.longitude
            ));
            return candidateLatLng;
        }

        return null;
    }

    private void resetPendingInitialAnchor(@NonNull String reason) {
        if (pendingInitialAnchorCandidate != null || pendingInitialAnchorSource != null) {
            Log.d(TAG, "STEP3_ANCHOR reset pending candidate: " + reason);
        }
        pendingInitialAnchorCandidate = null;
        pendingInitialAnchorSource = null;
        pendingInitialAnchorSinceMs = 0L;
        pendingInitialAnchorSamples = 0;
    }

    private boolean isValidLatLng(@Nullable LatLng latLng) {
        if (latLng == null) {
            return false;
        }
        return !(Math.abs(latLng.latitude) < 1e-6 && Math.abs(latLng.longitude) < 1e-6);
    }

    private boolean hasMeaningfulRawPdrMovement(float[] pdrValues) {
        if (pdrValues == null || pdrValues.length < 2) {
            return false;
        }

        float dx = pdrValues[0] - previousPosX;
        float dy = pdrValues[1] - previousPosY;
        float delta = (float) Math.hypot(dx, dy);

        return delta >= LIVE_DRAW_MIN_PDR_DELTA_METERS;
    }

    private boolean shouldUpdateLiveTrajectory(float[] pdrValues) {
        long now = SystemClock.uptimeMillis();

        if (hasMeaningfulRawPdrMovement(pdrValues)) {
            lastLiveMovementUptimeMs = now;
            return true;
        }

        return (now - lastLiveMovementUptimeMs) <= LIVE_DRAW_STATIONARY_HOLD_MS;
    }

    /**
     * Periodically sends the latest sensor packet to the desktop TCP server.
     */
    private final Runnable tcpStreamRunnable = new Runnable() {
        @Override
        public void run() {
            if (!tcpStreamingEnabled || tcpPacketSender == null) {
                return;
            }

            tcpPacketSender.sendLatestPacket();
            tcpHandler.postDelayed(this, 200);
        }
    };

    @Override
    public void onPause() {
        super.onPause();
        refreshDataHandler.removeCallbacks(refreshDataTask);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopTcpStreaming();
        refreshDataHandler.removeCallbacks(refreshDataTask);
        pfUiStepInitialised = false;
        lastPfUiStepElapsedMs = 0L;
    }

    /**
     * Simple in-session test point record.
     */
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
}