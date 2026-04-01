package com.openpositioning.PositionMe.fusion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.mapmatching.CandidatePose;
import com.openpositioning.PositionMe.mapmatching.CorrectionType;
import com.openpositioning.PositionMe.mapmatching.MapMatchingInput;
import com.openpositioning.PositionMe.mapmatching.MapMatchingResult;
import com.openpositioning.PositionMe.mapmatching.MapMatchingService;
import com.openpositioning.PositionMe.mapmatching.MotionDelta;
import com.openpositioning.PositionMe.mapmatching.VerticalMotionDetector;
import com.openpositioning.PositionMe.mapmatching.VerticalTransitionHint;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.mapmatching.MapGeometryUtils;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Live particle-filter manager for indoor positioning.
 *
 * Ownership in this version:
 * - owns the live PF pipeline and publishes the final fused pose
 * - raw PDR remains raw and is not corrected here
 * - WiFi / GNSS are used only as PF observations
 * - live floor commit decisions are owned here through AutoFloorManager
 *
 * Live step flow:
 * 1. refresh vertical context
 * 2. initialise PF if needed
 * 3. read incremental PDR motion
 * 4. predict particles
 * 5. build accepted observations
 * 6. update particle weights
 * 7. estimate raw PF pose
 * 8. attempt stuck recovery if needed
 * 9. apply discrete floor / connector validation
 * 10. hard-clamp final published pose against walls
 */

public class ParticleFilterManager {

    private static final String TAG = "ParticleFilterManager";

    // Observation gating
    private static final double WIFI_GATE_METERS = 3.0;
    private static final double GNSS_GATE_METERS =  4.0;
    private static final double WIFI_OBS_EMA_ALPHA = 0.10;
    private static final double STATIONARY_STEP_THRESHOLD_METERS = 0.06;

    // Constraint / recovery
    private static final double WALL_CROSS_PENALTY = 0.005;
    private static final double SOFT_STUCK_WALL_RATIO = 0.40;
    private static final double LOW_NEFF_RATIO = 0.4;

    private static final double MIN_STEP_FOR_RECOVERY_METERS = 0.35;
    private static final int HARD_RECOVERY_AFTER_CONSECUTIVE_STEPS = 3;

    private static final double SOFT_RECOVERY_RESPAWN_FRACTION = 0.35;
    private static final double HARD_RECOVERY_RESPAWN_FRACTION = 0.85;

    private static final double SOFT_RECOVERY_POSITION_STD_METERS = 0.45;
    private static final double HARD_RECOVERY_POSITION_STD_METERS = 2.50;
    private static final double RECOVERY_HEADING_STD_RAD = Math.toRadians(22.0);
    private static final int WARMUP_FRAMES = 10;

    /** Main sensor / state source. */
    private final SensorFusion sensorFusion;

    /** Application context for preferences. */
    private final Context appContext;

    /** PF settings storage. */
    private final SharedPreferences prefs;

    /** Converts LatLng <-> local XY. */
    private CoordinateConverter coordinateConverter;

    /** PF engine. */
    private ParticleFilterEngine particleFilterEngine;

    /** Final fused live pose. */
    private FusedPose latestFusedPose;

    /** Whether PF is enabled for this recording session. */
    private boolean enabled = false;

    // -------------------------
    // PDR incremental tracking
    // -------------------------
    private boolean firstPdrSample = true;
    private double lastPdrX = 0.0;
    private double lastPdrY = 0.0;
    private double lastHeading = 0.0;

    // -------------------------
    // PF config state
    // -------------------------
    private ParticleFilterConfig currentConfig;
    private boolean pfConfigDirty = false;

    // -------------------------
    // Observation smoothing
    // -------------------------
    private boolean wifiObsEmaInitialised = false;
    private double wifiObsEmaX = 0.0;
    private double wifiObsEmaY = 0.0;

    // -------------------------
    // Map-constraint state
    // -------------------------
    private final MapMatchingService mapMatchingService = new MapMatchingService();
    private final VerticalMotionDetector verticalMotionDetector = new VerticalMotionDetector();
    /**
     * Owns the LIVE floor decision state for the PF pipeline.
     *
     * Important:
     * - startup/manual/bootstrap floor behaviour is still handled elsewhere
     * - this manager only owns live PF floor request/commit logic after the session starts
     */
    private final AutoFloorManager autoFloorManager;
    @Nullable
    private CandidatePose lastMatchedPose = null;

    /** Authoritative live PF floor. */
    private int activePfFloor = 0;

    /** Counts consecutive updates where the filter looks wall-stuck. */
    private int consecutiveStuckSteps = 0;

    // -------------------------
    // Live debug state
    // -------------------------
    private double lastStepDistanceMeters = 0.0;
    private boolean lastStationaryUpdate = false;
    @NonNull
    private String lastObservationSource = "none";
    @NonNull
    private String lastCorrectionType = "NONE";
    @NonNull
    private String lastCorrectionReason = "Waiting for updates";
    @NonNull
    private String lastRecoveryType = "NONE";
    @NonNull
    private String lastRecoveryReason = "No recovery";
    private boolean lastCrossedWall = false;
    private boolean lastNearStairs = false;
    private boolean lastNearLift = false;
    private boolean lastFloorChangeAllowed = false;
    private int lastRequestedFloor = 0;
    private int lastMatchedFloor = 0;
    /** One-shot flag consumed by the UI to start a new trajectory segment after a committed floor change. */
    private boolean floorChangedSinceLastConsume = false;
    private double lastWallInvalidRatio = 0.0;
    private int frameCount = 0;
    private final Random rng = new Random();

    /**
     * Creates the live particle-filter manager.
     *
     * @param sensorFusion main live sensor/data source used to read PDR, GNSS, WiFi, elevation, etc.
     * @param context application/activity context used to access shared preferences
     */
    public ParticleFilterManager(@NonNull SensorFusion sensorFusion, @NonNull Context context) {
        this.sensorFusion = sensorFusion; // Store the shared SensorFusion instance used by this manager.
        this.appContext = context.getApplicationContext(); // Keep the application context to avoid leaking an Activity context.
        this.prefs = PreferenceManager.getDefaultSharedPreferences(appContext); // Open the app's default shared preferences for PF settings.

        this.currentConfig = loadPfConfig(); // Read the initial PF configuration from preferences immediately.

        this.autoFloorManager = new AutoFloorManager(
                sensorFusion,
                new AutoFloorManager.FloorIndexAdapter() {
                    @Override
                    public boolean isFloorIndexAvailable(int floorIndex) {
                        return ParticleFilterManager.this.isFloorIndexAvailable(floorIndex);
                    }

                    @Override
                    public int sanitiseFloorIndex(int floorIndex) {
                        return ParticleFilterManager.this.sanitiseFloorIndex(floorIndex);
                    }
                }
        );

        prefs.registerOnSharedPreferenceChangeListener(pfListener); // Listen for later PF setting changes so the filter can refresh.
        constraintDebugger.setMinIntervalMs(500L);
    }

    /**
     * Watches for live PF tuning changes from settings.
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener pfListener =
            (sharedPreferences, key) -> {
                if (key == null) {
                    return;
                }

                switch (key) {
                    case "pf_particle_count":
                    case "pf_sigma_step":
                    case "pf_sigma_theta_deg":
                    case "pf_sigma_wifi":
                    case "pf_sigma_gnss":
                    case "pf_init_pos_std":
                    case "pf_init_heading_deg":
                    case "pf_resample_ratio":
                    case "pf_sigma_reg_pos":
                    case "pf_sigma_reg_theta_deg":
                        currentConfig = loadPfConfig();
                        pfConfigDirty = true;
                        Log.d(TAG, "PF config changed: " + key);
                        break;
                }
            };

    /** Small helper for consistent PF constraint / fused-pose debug logs. */
    private final ParticleConstraintDebugger constraintDebugger =
            new ParticleConstraintDebugger("PF_CONSTRAINT_DEBUG");

    /**
     * Unregister listeners when the app is destroying this manager.
     */
    public void destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(pfListener);
    }

    /**
     * Loads current PF configuration from SharedPreferences.
     */
    @NonNull
    private ParticleFilterConfig loadPfConfig() {
        int particleCount = Integer.parseInt(prefs.getString("pf_particle_count", "300"));
        double sigmaStep = Double.parseDouble(prefs.getString("pf_sigma_step", "0.15"));
        double sigmaThetaDeg = Double.parseDouble(prefs.getString("pf_sigma_theta_deg", "4.5"));
        double sigmaWifi = Double.parseDouble(prefs.getString("pf_sigma_wifi", "14"));
        double sigmaGnss = Double.parseDouble(prefs.getString("pf_sigma_gnss", "25.0"));
        double initPosStd = Double.parseDouble(prefs.getString("pf_init_pos_std", "1.0"));
        double initHeadingDeg = Double.parseDouble(prefs.getString("pf_init_heading_deg", "10"));
        double resampleRatio = Double.parseDouble(prefs.getString("pf_resample_ratio", "0.5"));
        double sigmaRegPos = Double.parseDouble(prefs.getString("pf_sigma_reg_pos", "0.03"));
        double sigmaRegThetaDeg = Double.parseDouble(prefs.getString("pf_sigma_reg_theta_deg", "2.0"));

        return new ParticleFilterConfig(
                particleCount,
                sigmaStep,
                Math.toRadians(sigmaThetaDeg),
                sigmaWifi,
                sigmaGnss,
                initPosStd,
                Math.toRadians(initHeadingDeg),
                resampleRatio,
                sigmaRegPos,
                Math.toRadians(sigmaRegThetaDeg)
        );
    }

    /**
     * Forces the live PF state onto a specific floor.
     *
     * Use this when the UI manually changes floor, so the PF internal floor,
     * the fused pose floor, and the displayed floor all stay aligned.
     */
    public void forceActiveFloor(int floorIndex) {
        autoFloorManager.forceFloor(floorIndex); // Force the AutoFloorManager to adopt the requested floor immediately.
        activePfFloor = autoFloorManager.getActiveFloor(); // Mirror the authoritative floor locally to minimise changes elsewhere.

        if (particleFilterEngine != null) { // If the PF engine already exists, all particles must move to the same floor owner.
            particleFilterEngine.setAllParticlesFloor(activePfFloor);
        }

        if (latestFusedPose != null) { // If a fused pose already exists, rewrite it with the forced floor so UI and PF stay aligned.
            latestFusedPose = new FusedPose(
                    latestFusedPose.getXMeters(),
                    latestFusedPose.getYMeters(),
                    latestFusedPose.getHeadingRad(),
                    activePfFloor,
                    latestFusedPose.getLatLng(),
                    latestFusedPose.getConfidence()
            );
        }

        if (lastMatchedPose != null && latestFusedPose != null && latestFusedPose.getLatLng() != null) {
            lastMatchedPose = new CandidatePose(
                    latestFusedPose.getLatLng(),
                    activePfFloor,
                    SystemClock.elapsedRealtime(),
                    "manual_floor_override",
                    latestFusedPose.getHeadingRad()
            );
        }

        Log.d(TAG, "Forced PF active floor = " + activePfFloor);
    }

    /**
     * Feeds barometer-derived vertical context into the live detector.
     *
     * Call this from your pressure path so stairs / lift evidence can accumulate
     * even before the next step-triggered PF update.
     */
    public void onVerticalContextSample(long timestampMs,
                                        double elevationMeters,
                                        boolean elevatorLikely) {
        if (!enabled) {
            return;
        }
        verticalMotionDetector.addSample(timestampMs, elevationMeters, elevatorLikely);
    }

    /**
     * Advances the live particle filter by one update cycle.
     *
     * Processing order:
     * 1. refresh vertical context
     * 2. initialise the PF if needed
     * 3. compute incremental motion from PDR
     * 4. predict particles
     * 5. build absolute observations
     * 6. update particle weights
     * 7. estimate fused pose
     * 8. attempt stuck recovery
     *
     * Important for this current version:
     * - GNSS is intentionally still used
     * - absolute-observation suppression is intentionally not enforced
     * - this method only fixes the call/signature mismatch and adds clarity comments
     */
    public void step() {
        if (!enabled) { // Do nothing if PF mode is disabled for the current session.
            return; // Exit early because no PF work should be done.
        }

        if (pfConfigDirty) { // Check whether PF settings were changed from preferences.
            Log.d(TAG, "Applying new PF config -> resetting filter"); // Log that the filter is being rebuilt with new settings.
            reset(); // Clear all live PF state so the next initialisation uses the new config cleanly.
            pfConfigDirty = false; // Mark the config as clean again after reset.
        }

        onVerticalContextSample( // Feed the latest height/lift state into the vertical detector.
                SystemClock.uptimeMillis(), // Use current uptime as the timestamp for this vertical sample.
                sensorFusion.getElevation(), // Read the latest estimated elevation from SensorFusion.
                sensorFusion.getElevator() // Read whether lift/elevator behaviour is currently likely.
        );

        initialiseIfNeeded(); // Lazily initialise the PF once a valid start anchor becomes available.
        if (particleFilterEngine == null || coordinateConverter == null) { // If PF is still not ready, we cannot continue this cycle.
            return; // Exit until a valid initial state exists.
        }

        double deltaS = extractPdrDeltaDistance(); // Compute incremental distance travelled since the previous PF update.
        double currentHeading = wrapAngle(sensorFusion.getSelectedHeadingRad()); // Read the selected heading source and wrap it to [-pi, pi].

        boolean isTurning = sensorFusion.getIsTurning();
        boolean isGyroStable = sensorFusion.getIsGyroStable();
        boolean headingReliable = sensorFusion.isHeadingReliable();

        // Freeze tiny heading changes during confident straight motion.
        boolean isStraight = !isTurning && isGyroStable && headingReliable;
        // HEADING LOCK
        if (isStraight) {
            // Strongly resist heading change
            currentHeading = lastHeading;
        }

        double deltaTheta = wrapAngle(currentHeading - lastHeading); // Compute heading change since the previous update.
        lastHeading = currentHeading; // Store the current heading so the next cycle can compute the next delta.

        lastStepDistanceMeters = deltaS; // Save step distance for live debug output.
        lastStationaryUpdate = deltaS < STATIONARY_STEP_THRESHOLD_METERS; // Mark this frame as stationary if motion is below the threshold.

        double MAX_STEP = 1.0; // Define a safety cap for overly large PDR jumps.
        deltaS = Math.min(deltaS, MAX_STEP); // Clamp the PDR distance so one bad step does not move the PF too far.

        if (Math.abs(deltaTheta) < Math.toRadians(3)) { // Treat very small heading changes as noise.
            deltaTheta = 0; // Zero out tiny rotations to reduce jitter.
        }

        ParticleFilterEngine.ConstraintContext constraintContext = buildConstraintContext(); // Build wall-constraint data for predict/update.

        if (deltaS >= 0.01) { // Only run prediction when there is meaningful forward motion.
            particleFilterEngine.predict(deltaS, deltaTheta, constraintContext); // Propagate particles using motion and map constraints.
            Log.d(TAG, "PF predict: deltaS=" + deltaS + ", deltaTheta=" + deltaTheta); // Log the applied motion command.
        } else { // Otherwise this is effectively a stationary frame.
            Log.d(TAG, "PF stationary: skip predict, keep update"); // Log that motion prediction was skipped.
        }

        // Build the accepted WiFi / GNSS observation bundle for this frame.
        ParticleFilterObservation observation = buildObservation(deltaS);

        // Update particle weights using the accepted observations and wall constraints.
        particleFilterEngine.update(observation, constraintContext);

        // Log particle health and update summary after the measurement update.
        constraintDebugger.logStep(
                "post_update",
                particleFilterEngine.getParticleCount(),
                particleFilterEngine.getAliveParticleCount(),
                (int) Math.round(
                        particleFilterEngine.getLastWallInvalidRatio()
                                * particleFilterEngine.getParticleCount()
                ),
                0,
                ((observation.getWifiX() != null) || (observation.getGnssX() != null))
                        ? particleFilterEngine.getAliveParticleCount()
                        : 0,
                String.format(
                        Locale.US,
                        "step=%.2f dThetaDeg=%.1f floor=%d obs=%s neff=%.1f",
                        deltaS,
                        Math.toDegrees(deltaTheta),
                        activePfFloor,
                        lastObservationSource,
                        particleFilterEngine.getLastNeff()
                )
        );

        // Get the raw PF fused pose before recovery or floor validation.
        FusedPose rawPose = particleFilterEngine.estimate(coordinateConverter);

        // Log the raw PF estimate for debugging.
        if (rawPose != null && rawPose.getLatLng() != null) {
            constraintDebugger.logFusedPose(
                    "raw_estimate",
                    rawPose.getLatLng().latitude,
                    rawPose.getLatLng().longitude,
                    rawPose.getFloor(),
                    rawPose.getHeadingRad(),
                    rawPose.getConfidence()
            );
        }

        // Try to recover the filter if it looks stuck against walls or severely degraded.
        FusedPose recoveredPose = maybeRecoverStuckParticles(
                deltaS,
                currentHeading,
                observation,
                constraintContext,
                rawPose
        );

        // Use the recovered pose if recovery succeeded, otherwise keep the raw PF pose.
        FusedPose poseAfterRecovery = recoveredPose != null ? recoveredPose : rawPose;

        /*
         * Apply live floor / connector validation in the normal step path.
         * This is important for stair transitions, because they should be
         * committed using real walking motion rather than the no-step path.
         */
        FusedPose floorCheckedPose = applyDiscreteMapMatching(
                poseAfterRecovery,
                currentHeading,
                deltaS,
                SystemClock.elapsedRealtime()
        );

        // Prefer the floor-checked pose if available.
        FusedPose poseToPublish = floorCheckedPose != null ? floorCheckedPose : poseAfterRecovery;

        // Only continue if there is a valid pose and map location to publish.
        if (poseToPublish != null && poseToPublish.getLatLng() != null) {
            // Get wall geometry for the active floor.
            FloorplanApiClient.FloorShapes wallShapes = getFloorShapesForFloor(activePfFloor);
            // Final hard wall clamp before publishing to the UI.
            LatLng finalLatLng = clampFinalPoseAgainstWalls(poseToPublish.getLatLng(), wallShapes);

            /*
             * Extra hard safety check:
             * even after final clamp, never publish a point that still lies inside a wall.
             * If that happens, fall back to the last valid matched pose when possible.
             */
            if (wallShapes != null && MapGeometryUtils.isInsideWall(finalLatLng, wallShapes)) {
                if (lastMatchedPose != null && lastMatchedPose.getLatLng() != null) {
                    // Fall back to the previous valid matched pose.
                    finalLatLng = lastMatchedPose.getLatLng();
                    lastCorrectionType = "FINAL_INSIDE_WALL_FALLBACK";
                    lastCorrectionReason = "Final candidate remained inside wall after clamp; reverted to last matched valid pose.";
                    lastCrossedWall = true;
                } else {
                    // No valid fallback exists, so keep the current state but record the issue.
                    lastCorrectionType = "FINAL_INSIDE_WALL_HOLD";
                    lastCorrectionReason = "Final candidate remained inside wall after clamp; no previous valid pose available.";
                    lastCrossedWall = true;
                }
            }

            // Convert the final published LatLng back into local x/y coordinates.
            double[] finalLocal = coordinateConverter.latLngToLocal(finalLatLng);

            // Save the final fused pose used by the live UI.
            latestFusedPose = new FusedPose(
                    finalLocal[0],
                    finalLocal[1],
                    poseToPublish.getHeadingRad(),
                    activePfFloor,
                    finalLatLng,
                    poseToPublish.getConfidence()
            );

            lastMatchedPose = new CandidatePose(
                    finalLatLng,
                    activePfFloor,
                    SystemClock.elapsedRealtime(),
                    "pf_wall_guard",
                    poseToPublish.getHeadingRad()
            );

            if (latestFusedPose != null && latestFusedPose.getLatLng() != null) {
                constraintDebugger.logFusedPose(
                        "published",
                        latestFusedPose.getLatLng().latitude,
                        latestFusedPose.getLatLng().longitude,
                        latestFusedPose.getFloor(),
                        latestFusedPose.getHeadingRad(),
                        latestFusedPose.getConfidence()
                );
            }

        } else {
            // If no valid map location exists, keep the best available pose as-is.
            latestFusedPose = poseToPublish;
        }
    }

    /**
     * Initialises the PF only when a usable start anchor is available.
     *
     * Priority:
     * - user-confirmed start marker
     * - stored session start anchor
     * - WiFi
     * - GNSS
     */
    public void initialiseIfNeeded() {
        if (particleFilterEngine != null && particleFilterEngine.isInitialised()) {
            return;
        }

        LatLng initialLatLng = resolveInitialLatLng();
        if (initialLatLng == null) {
            Log.d(TAG, "PF init skipped: waiting for start anchor.");
            return;
        }

        coordinateConverter = new CoordinateConverter(
                initialLatLng.latitude,
                initialLatLng.longitude
        );

        activePfFloor = resolveInitialFloorIndex(); // Resolve the starting floor from the existing startup policy.
        autoFloorManager.initialiseFloor(activePfFloor); // Initialise live floor ownership and committed elevation anchor inside AutoFloorManager.
        activePfFloor = autoFloorManager.getActiveFloor(); // Mirror back the sanitised authoritative floor.

        double[] initialLocal = coordinateConverter.latLngToLocal(initialLatLng);
        double initialHeading = wrapAngle(sensorFusion.getSelectedHeadingRad());

        particleFilterEngine = new ParticleFilterEngine(currentConfig);
        particleFilterEngine.initialise(
                initialLocal[0],
                initialLocal[1],
                initialHeading,
                activePfFloor
        );

        FusedPose initialRawPose = particleFilterEngine.estimate(coordinateConverter);
        FusedPose initialCorrectedPose = applyDiscreteMapMatching(
                initialRawPose,
                initialHeading,
                0.0,
                SystemClock.elapsedRealtime()
        );

        latestFusedPose = initialCorrectedPose != null ? initialCorrectedPose : initialRawPose;
        lastHeading = initialHeading;

        if (latestFusedPose != null) {
            lastMatchedPose = new CandidatePose(
                    latestFusedPose.getLatLng(),
                    latestFusedPose.getFloor(),
                    SystemClock.elapsedRealtime(),
                    "pf_init",
                    latestFusedPose.getHeadingRad()
            );
        }

        lastRequestedFloor = activePfFloor;
        lastMatchedFloor = activePfFloor;

        Log.d(TAG,
                "PF initialised"
                        + " | particles=" + currentConfig.particleCount
                        + ", sigmaStep=" + currentConfig.sigmaStep
                        + ", sigmaThetaDeg=" + Math.toDegrees(currentConfig.sigmaThetaRad)
                        + ", sigmaWifi=" + currentConfig.sigmaWifi
                        + ", sigmaGnss=" + currentConfig.sigmaGnss
                        + ", activeFloor=" + activePfFloor
                        + ", lat=" + initialLatLng.latitude
                        + ", lon=" + initialLatLng.longitude);
    }

    /**
     * Uses SensorFusion's single preferred-start resolver so raw-PDR seeding,
     * recording start, and PF initialisation all read the same start location.
     */
    @Nullable
    private LatLng resolveInitialLatLng() {
        return sensorFusion.resolvePreferredStartAnchor();
    }

    /**
     * Uses SensorFusion's single preferred-start floor resolver.
     */
    private int resolveInitialFloorIndex() {
        return sanitiseFloorIndex(sensorFusion.resolvePreferredStartFloorIndex());
    }

    /**
     * Fully resets PF state for a new recording session.
     */
    public void reset() {
        coordinateConverter = null;
        particleFilterEngine = null;
        latestFusedPose = null;
        firstPdrSample = true;
        lastPdrX = 0.0;
        lastPdrY = 0.0;
        lastHeading = 0.0;
        wifiObsEmaInitialised = false;
        wifiObsEmaX = 0.0;
        wifiObsEmaY = 0.0;
        activePfFloor = 0;
        lastMatchedPose = null;
        verticalMotionDetector.reset();
        mapMatchingService.resetTransientState();
        autoFloorManager.reset(); //Reset all live floor-ownership state handled by AutoFloorManager
        consecutiveStuckSteps = 0;
        frameCount = 0;
        lastStepDistanceMeters = 0.0;
        lastStationaryUpdate = false;
        lastObservationSource = "none";
        lastCorrectionType = "NONE";
        lastCorrectionReason = "Waiting for updates";
        lastRecoveryType = "NONE";
        lastRecoveryReason = "No Recovery";
        lastCrossedWall = false;
        lastNearStairs = false;
        lastNearLift = false;
        lastFloorChangeAllowed = false;
        lastRequestedFloor = 0;
        lastMatchedFloor = 0;
        lastWallInvalidRatio = 0.0;
        floorChangedSinceLastConsume = false;
    }

    /**
     * Returns the latest final fused pose for live rendering.
     */
    @Nullable
    public FusedPose getLatestFusedPose() {
        return enabled ? latestFusedPose : null;
    }

    /**
     * Enables or disables PF for the current session.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        Log.d(TAG, "PF manager = " + (enabled ? "ENABLED" : "DISABLED"));

        if (!enabled) {
            reset();
        }
    }

    /**
     * Returns whether this manager is active.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns whether a validated live floor change happened since the last UI read.
     *
     * This is a one-shot signal so the UI can start a new trajectory segment exactly once.
     */
    public boolean consumeFloorChangedFlag() {
        boolean changed = floorChangedSinceLastConsume;
        floorChangedSinceLastConsume = false;
        return changed;
    }

    /**
     * Builds the absolute-observation bundle used by the PF update step.
     *
     * Current policy in this version:
     * - WiFi is still allowed when it passes gating checks
     * - GNSS is also intentionally allowed
     * - the step-distance argument is accepted for API consistency with the caller
     * - stepDistanceMeters is intentionally unused here because you said you want
     *   to keep GNSS and skip absolute-observation suppression for now
     *
     * @param stepDistanceMeters latest PDR step distance for this update cycle
     * @return accepted observation bundle for the PF measurement update
     */
    @NonNull
    private ParticleFilterObservation buildObservation(double stepDistanceMeters) {
        lastObservationSource = "none"; // Reset the debug label before deciding which source is accepted this frame.

        Double wifiX = null; // Prepare WiFi local-x observation, initially absent.
        Double wifiY = null; // Prepare WiFi local-y observation, initially absent.
        Integer wifiFloor = null; // Prepare WiFi floor observation, initially absent.

        LatLng wifiLatLng = sensorFusion.getLatLngWifiPositioning(); // Read the latest WiFi position estimate in global coordinates.
        if (isValidLatLng(wifiLatLng) && coordinateConverter != null) { // Continue only if WiFi is valid and coordinate conversion is ready.
            double[] local = coordinateConverter.latLngToLocal(wifiLatLng); // Convert WiFi LatLng into local x/y coordinates.
            int observedWifiFloor = sensorFusion.getWifiFloor(); // Read the WiFi-derived floor index.

            boolean acceptWifi = true; // Start by assuming the WiFi observation is usable.

            if (isIndoorContextActive()
                    && isFloorIndexAvailable(observedWifiFloor)
                    && observedWifiFloor != activePfFloor) {

                boolean allowCrossFloorWifi = autoFloorManager.shouldAcceptCrossFloorWifiObservation(
                        observedWifiFloor,
                        SystemClock.elapsedRealtime()
                );

                if (!allowCrossFloorWifi) {
                    acceptWifi = false;
                    Log.d(TAG,
                            "Rejecting WiFi position: floor mismatch with active PF floor."
                                    + " | activePfFloor=" + activePfFloor
                                    + ", wifiFloor=" + observedWifiFloor);
                } else {
                    Log.d(TAG,
                            "Allowing WiFi position during lift with stable WiFi floor"
                                    + " | activePfFloor=" + activePfFloor
                                    + ", wifiFloor=" + observedWifiFloor);
                }
            }

            if (acceptWifi && latestFusedPose != null) { // If WiFi still looks okay, also check its distance from the current fused pose.
                double dx = local[0] - latestFusedPose.getXMeters(); // Compute WiFi offset in x from the latest fused pose.
                double dy = local[1] - latestFusedPose.getYMeters(); // Compute WiFi offset in y from the latest fused pose.
                double dist = Math.hypot(dx, dy); // Convert x/y offset into Euclidean distance.

                if (dist > WIFI_GATE_METERS) { // Reject WiFi if it is too far away from the current fused pose.
                    acceptWifi = false; // Mark WiFi as rejected by the distance gate.
                    Log.d(TAG, "Rejecting WiFi update by gate | dist=" + dist + ", gate=" + WIFI_GATE_METERS); // Log the distance-gate rejection.
                }
            }

            if (acceptWifi) { // Only publish WiFi observation if all checks passed.
                double[] smoothWifi = smoothWifiObservation(local[0], local[1]); // Smooth WiFi coordinates with EMA before using them.
                wifiX = smoothWifi[0]; // Store the smoothed WiFi x coordinate.
                wifiY = smoothWifi[1]; // Store the smoothed WiFi y coordinate.
                wifiFloor = observedWifiFloor; // Store the accepted WiFi floor.
                lastObservationSource = "wifi"; // Update debug state to show WiFi was accepted.
            }
        }

        Double gnssX = null; // Prepare GNSS local-x observation, initially absent.
        Double gnssY = null; // Prepare GNSS local-y observation, initially absent.
        float[] gnssLatLon = sensorFusion.getGNSSLatitude(false); // Read the latest live GNSS lat/lon pair.
        if (isValidLatLon(gnssLatLon) && coordinateConverter != null) { // Continue only if GNSS is valid and local conversion is ready.
            LatLng gnssLatLng = new LatLng(gnssLatLon[0], gnssLatLon[1]); // Convert raw GNSS floats into a LatLng object.
            double[] local = coordinateConverter.latLngToLocal(gnssLatLng); // Convert GNSS LatLng into local x/y coordinates.

            boolean acceptGnss = true; // Start by assuming GNSS is usable.

            if (latestFusedPose != null) { // Gate GNSS against the latest fused pose if one exists.
                double dx = local[0] - latestFusedPose.getXMeters(); // Compute GNSS offset in x from the fused pose.
                double dy = local[1] - latestFusedPose.getYMeters(); // Compute GNSS offset in y from the fused pose.
                double dist = Math.hypot(dx, dy); // Convert offset to Euclidean distance.

                if (dist > GNSS_GATE_METERS) { // Reject GNSS if it is too far away from the current fused pose.
                    acceptGnss = false; // Mark GNSS as rejected.
                    Log.d(TAG, "Rejecting GNSS update by gate | dist=" + dist + ", gate=" + GNSS_GATE_METERS); // Log the rejection reason.
                }
            }

            if (acceptGnss) { // Publish GNSS only if it passed the gate.
                gnssX = local[0]; // Store accepted GNSS x coordinate.
                gnssY = local[1]; // Store accepted GNSS y coordinate.
            }
        }

        return new ParticleFilterObservation(wifiX, wifiY, wifiFloor, gnssX, gnssY); // Return the complete observation bundle for the PF update.
    }

    /**
     * Builds a wall-constraint context for the PF engine.
     */
    @Nullable
    private ParticleFilterEngine.ConstraintContext buildConstraintContext() {
        if (coordinateConverter == null) {
            return null;
        }

        FloorplanApiClient.FloorShapes sourceFloorShapes = getFloorShapesForFloor(activePfFloor);
        if (sourceFloorShapes == null) {
            return null;
        }

        return new ParticleFilterEngine.ConstraintContext(
                coordinateConverter,
                sourceFloorShapes,
                WALL_CROSS_PENALTY
        );
    }

    @Nullable
    private FusedPose maybeRecoverStuckParticles(double stepDistanceMeters,
                                                 double currentHeadingRad,
                                                 @Nullable ParticleFilterObservation observation,
                                                 @Nullable ParticleFilterEngine.ConstraintContext constraintContext,
                                                 @Nullable FusedPose rawPose) {

        // Recovery cannot run unless PF, coordinate conversion, and a raw pose already exist.
        if (particleFilterEngine == null || coordinateConverter == null || rawPose == null) {
            lastRecoveryType = "NONE";
            lastRecoveryReason = "PF/coordinate converter/raw pose unavailable";
            return rawPose;
        }

        // Give the filter a short warmup period before allowing recovery logic.
        frameCount++;
        if (frameCount < WARMUP_FRAMES) {
            consecutiveStuckSteps = 0;
            lastRecoveryType = "WARMUP";
            lastRecoveryReason = "Recovery warmup active";
            return rawPose;
        }
        // Ignore very small steps because they are too weak to judge stuck behaviour reliably.
        if (stepDistanceMeters < MIN_STEP_FOR_RECOVERY_METERS) {
            consecutiveStuckSteps = 0;
            lastRecoveryType = "NONE";
            lastRecoveryReason = String.format(Locale.US, "Step too small for recovery (%.2fm)", stepDistanceMeters);
            return rawPose;
        }

        // Read PF health indicators from the latest update.
        double wallInvalidRatio = particleFilterEngine.getLastWallInvalidRatio();
        double neffRatio = particleFilterEngine.getLastNeff()
                / Math.max(1.0, particleFilterEngine.getParticleCount());

        // Treat the filter as stuck only when many particles are wall-invalid
        // and the effective sample size is also poor.
        boolean stuck = (wallInvalidRatio > SOFT_STUCK_WALL_RATIO)
                && (neffRatio < LOW_NEFF_RATIO);

        Log.d(TAG, "PF recovery check"
                + " | wallInvalidRatio=" + wallInvalidRatio
                + " | neffRatio=" + neffRatio
                + " | stuck=" + stuck
                + " | consecutiveStuckSteps=" + consecutiveStuckSteps);

        // If the filter does not look stuck, clear the stuck counter and keep the raw pose.
        if (!stuck) {
            consecutiveStuckSteps = 0;
            lastRecoveryType = "NONE";
            lastRecoveryReason = String.format(
                    Locale.US,
                    "No recovery | wallRatio=%.2f neffRatio=%.2f",
                    wallInvalidRatio,
                    neffRatio
            );
            return rawPose;
        }
        // Count consecutive stuck detections to decide between soft and hard recovery.
        consecutiveStuckSteps++;
        // Escalate to hard recovery only after repeated stuck frames.
        boolean hardRecovery = consecutiveStuckSteps >= HARD_RECOVERY_AFTER_CONSECUTIVE_STEPS;
        // Start from the last known valid recovery anchor.
        double[] recoveryAnchor = resolveLastValidRecoveryAnchorXY(rawPose);

        // For hard recovery, optionally blend in a trusted WiFi anchor.
        if (hardRecovery) {
            double[] wifiAnchor = resolveAcceptedWifiRecoveryAnchorXY(observation);
            if (wifiAnchor != null) {
                // Blend raw PF position with WiFi anchor instead of jumping fully to WiFi.
                recoveryAnchor[0] = 0.7 * rawPose.getXMeters() + 0.3 * wifiAnchor[0];
                recoveryAnchor[1] = 0.7 * rawPose.getYMeters() + 0.3 * wifiAnchor[1];
                Log.d(TAG, "Using WiFi anchor for recovery");
            } else {
                // Fall back to the last valid anchor if WiFi is not trusted or unavailable.
                Log.d(TAG, "WiFi anchor unavailable, falling back to last valid anchor");
            }
        }

        // Choose respawn strength based on whether this is soft or hard recovery.
        double respawnFraction = hardRecovery ? HARD_RECOVERY_RESPAWN_FRACTION : SOFT_RECOVERY_RESPAWN_FRACTION;
        double positionStdMeters = hardRecovery ? HARD_RECOVERY_POSITION_STD_METERS : SOFT_RECOVERY_POSITION_STD_METERS;

        // Rejuvenate weak particles around the chosen recovery anchor.
        boolean recovered = particleFilterEngine.rejuvenateParticles(
                recoveryAnchor[0],
                recoveryAnchor[1],
                currentHeadingRad,
                activePfFloor,
                respawnFraction,
                positionStdMeters,
                RECOVERY_HEADING_STD_RAD,
                constraintContext
        );

        // If rejuvenation failed, keep the raw pose and record the failure reason.
        if (!recovered) {
            lastRecoveryType = hardRecovery ? "HARD_FAILED" : "SOFT_FAILED";
            lastRecoveryReason = String.format(
                    Locale.US,
                    "anchor=(%.2f, %.2f) wallRatio=%.2f neffRatio=%.2f",
                    recoveryAnchor[0],
                    recoveryAnchor[1],
                    wallInvalidRatio,
                    neffRatio
            );
            return rawPose;
        }

        FusedPose recoveredPose = particleFilterEngine.estimate(coordinateConverter);

        lastRecoveryType = hardRecovery ? "HARD_APPLIED" : "SOFT_APPLIED";
        lastRecoveryReason = String.format(
                Locale.US,
                "anchor=(%.2f, %.2f) wallRatio=%.2f neffRatio=%.2f",
                recoveryAnchor[0],
                recoveryAnchor[1],
                wallInvalidRatio,
                neffRatio
        );

        Log.d(TAG,
                "PF recovery applied"
                        + " | hardRecovery=" + hardRecovery
                        + " | consecutiveStuckSteps=" + consecutiveStuckSteps
                        + " | wallInvalidRatio=" + wallInvalidRatio
                        + " | neffRatio=" + neffRatio
                        + " | anchorX=" + recoveryAnchor[0]
                        + " | anchorY=" + recoveryAnchor[1]);

        return recoveredPose != null ? recoveredPose : rawPose;
    }

    /**
     * Uses the last known valid matched pose as the first recovery anchor.
     */
    @NonNull
    private double[] resolveLastValidRecoveryAnchorXY(@NonNull FusedPose fallbackPose) {
        if (lastMatchedPose != null && lastMatchedPose.getLatLng() != null && coordinateConverter != null) {
            return coordinateConverter.latLngToLocal(lastMatchedPose.getLatLng());
        }
        double sigmaMeters = 0.8;
        Log.d("Rejuvenate", "Rejuvenate using last valid position");
        if (latestFusedPose != null) {
            return new double[]{
                    latestFusedPose.getXMeters() + rng.nextGaussian() * sigmaMeters,
                    latestFusedPose.getYMeters() + rng.nextGaussian() * sigmaMeters
            };
        }
        return new double[]{
                fallbackPose.getXMeters()+ rng.nextGaussian() * sigmaMeters,
                fallbackPose.getYMeters()+ rng.nextGaussian() * sigmaMeters
        };
    }

    /**
     * Returns WiFi anchor for hard recovery only if it is floor-consistent and not too far.
     */
    @Nullable
    private double[] resolveAcceptedWifiRecoveryAnchorXY(@Nullable ParticleFilterObservation observation) {
        if (observation == null
                || observation.getWifiX() == null
                || observation.getWifiY() == null) {
            return null;
        }
        /*
         * Only use WiFi as a hard relocalisation anchor if it agrees with the current PF floor.
         * This stops cross-floor WiFi mistakes from blowing up recovery.
         */
        int wifiFloor = sensorFusion.getWifiFloor();
        if (isFloorIndexAvailable(wifiFloor) && wifiFloor != activePfFloor) {
            return null;
        }
        return new double[]{
                observation.getWifiX(),
                observation.getWifiY()
        };
    }

    /**
     * Returns GNSS anchor for hard recovery only if it stays reasonably close.
     */
    @Nullable
    private double[] resolveAcceptedGnssRecoveryAnchorXY(@Nullable ParticleFilterObservation observation) {
        if (observation == null || observation.getGnssX() == null || observation.getGnssY() == null) {
            return null;
        }
        Double gnssX = observation.getGnssX();
        Double gnssY = observation.getGnssY();

        double[] gnssAnchor = new double[]{gnssX, gnssY};

        Log.d(TAG, "Recovery anchor selected from GNSS");
        return gnssAnchor;
    }

    /**
     * Applies one discrete map validity stage to the raw PF estimate.
     *
     * this stage is not allowed to become a second absolute-observation blender.
     * It is only for wall/floor validity and limited correction.
     */
    @Nullable
    private FusedPose applyDiscreteMapMatching(@Nullable FusedPose rawPose,
                                               double currentHeadingRad,
                                               double stepDistanceMeters,
                                               long timestampMs) {
        // If there is no usable PF pose yet, keep the input as-is.
        if (rawPose == null || coordinateConverter == null || rawPose.getLatLng() == null) {
            return rawPose;
        }
        // Build the latest vertical-transition hint from elevation / lift evidence.
        VerticalTransitionHint verticalHint = verticalMotionDetector.buildHint();
        // Ask live floor logic which floor is currently being requested.
        int requestedFloor = autoFloorManager.resolveRequestedFloor(verticalHint, timestampMs);
        lastRequestedFloor = requestedFloor;

        // Load source and target floor shapes for floor validation.
        FloorplanApiClient.FloorShapes sourceFloorShapes = getFloorShapesForFloor(activePfFloor);
        FloorplanApiClient.FloorShapes targetFloorShapes = getFloorShapesForFloor(requestedFloor);

        // Build the current PF candidate pose on the requested floor.
        CandidatePose currentCandidatePose = new CandidatePose(
                rawPose.getLatLng(),
                requestedFloor,
                timestampMs,
                "pf_raw",
                currentHeadingRad
        );

        // Use the previous matched pose as the reference motion origin when available.
        double previousX = rawPose.getXMeters();
        double previousY = rawPose.getYMeters();
        if (lastMatchedPose != null && lastMatchedPose.getLatLng() != null) {
            double[] prevLocal = coordinateConverter.latLngToLocal(lastMatchedPose.getLatLng());
            previousX = prevLocal[0];
            previousY = prevLocal[1];
        }

        // Build motion delta from the previous validated pose to the current PF estimate.
        MotionDelta motionDelta = new MotionDelta(
                rawPose.getXMeters() - previousX,
                rawPose.getYMeters() - previousY,
                stepDistanceMeters,
                Math.toDegrees(currentHeadingRad)
        );

        // Build the map-matching input used for live floor validation.
        MapMatchingInput input = new MapMatchingInput(
                lastMatchedPose,
                currentCandidatePose,
                motionDelta,
                verticalHint,
                sourceFloorShapes,
                targetFloorShapes,
                sensorFusion.getSelectedBuildingId()
        );

        // Run live map matching validation.
        MapMatchingResult result = mapMatchingService.match(input);

        // Save debug information for UI / logs.
        lastCorrectionType = result.getCorrectionType() != null ? result.getCorrectionType().name() : CorrectionType.NONE.name();
        lastCorrectionReason = result.getDebugReason() != null ? result.getDebugReason() : "No debug reason";
        lastCrossedWall = result.isCrossedWall();
        lastNearStairs = result.isNearStairs();
        lastNearLift = result.isNearLift();
        lastFloorChangeAllowed = result.isFloorChangeAllowed();

        // Clamp the matched floor to the valid building floor range.
        int matchedFloor = sanitiseFloorIndex(result.getCorrectedFloor());
        // Commit the floor change only if live validation explicitly allows it.
        if (result.isFloorChangeAllowed() && matchedFloor != activePfFloor) {
            int previousCommittedFloor = activePfFloor;

            // Commit the validated floor transition into the live floor owner.
            autoFloorManager.onFloorCommitted(matchedFloor, timestampMs);
            activePfFloor = autoFloorManager.getActiveFloor(); // Mirror the authoritative committed floor locally.

            if (activePfFloor != previousCommittedFloor) {
                floorChangedSinceLastConsume = true;
            }

            verticalMotionDetector.reset(); // Clear the consumed vertical transition evidence so it is not re-used next frame.

            if (particleFilterEngine != null) {
                particleFilterEngine.setAllParticlesFloor(activePfFloor); // Push the committed floor into all PF particles.
            }
        }

        lastMatchedFloor = activePfFloor;

        // Keep XY exactly as the PF estimated it.
        FusedPose correctedPose = new FusedPose(
                rawPose.getXMeters(),
                rawPose.getYMeters(),
                rawPose.getHeadingRad(),
                activePfFloor,
                rawPose.getLatLng(),
                rawPose.getConfidence()
        );

        // Keep a pose record for connector persistence / future floor validation,
        // but do not apply XY snapping here.
        lastMatchedPose = new CandidatePose(
                rawPose.getLatLng(),
                activePfFloor,
                timestampMs,
                "pf_floor_checked",
                correctedPose.getHeadingRad()
        );
        return correctedPose;
    }

    public void evaluateFloorChangeWithoutStep() {
        if (!enabled) {
            return;
        }

        initialiseIfNeeded();
        if (particleFilterEngine == null || coordinateConverter == null || latestFusedPose == null) {
            return;
        }

        long now = SystemClock.elapsedRealtime();

        onVerticalContextSample(
                now,
                sensorFusion.getElevation(),
                sensorFusion.getElevator()
        );

        VerticalTransitionHint verticalHint = verticalMotionDetector.buildHint();
        if (verticalHint == null || !verticalHint.isHeightChanged()) {
            return;
        }

        /*
         * This no-step path is only for lift/elevator-like transitions.
         * Stair transitions should be committed through the normal step()
         * path where real deltaS is available.
         */
        if (!sensorFusion.getElevator()) {
            Log.d(TAG,
                    "Skipping no-step floor evaluation: not elevator-like."
                            + " | activePfFloor=" + activePfFloor
                            + ", wifiFloor=" + sensorFusion.getWifiFloor()
                            + ", deltaHeight=" + verticalHint.getDeltaHeight());
            return;
        }

        int requestedFloor = autoFloorManager.resolveRequestedFloor(verticalHint, now);

        Log.d(TAG,
                "FLOOR no-step check"
                        + " | activePfFloor=" + activePfFloor
                        + ", requestedFloor=" + requestedFloor
                        + ", wifiFloor=" + sensorFusion.getWifiFloor()
                        + ", deltaHeight=" + verticalHint.getDeltaHeight()
                        + ", heightChanged=" + verticalHint.isHeightChanged()
                        + ", elevator=" + sensorFusion.getElevator());

        if (requestedFloor == activePfFloor) {
            return;
        }

        FusedPose correctedPose = applyDiscreteMapMatching(
                latestFusedPose,
                wrapAngle(sensorFusion.getSelectedHeadingRad()),
                0.0,
                now
        );

        if (correctedPose != null) {
            latestFusedPose = correctedPose;
        }
    }

    /**
     * Final hard wall clamp for the live fused pose.
     *
     * Why this exists:
     * - PF particle penalties are soft
     * - map matching corrections can still miss some wall-cross cases
     * - live rendering now shows the PF result directly
     *
     * So before publishing the final fused pose, enforce:
     * previous matched pose -> candidate pose must not cross a wall.
     */
    @NonNull
    private LatLng clampFinalPoseAgainstWalls(@NonNull LatLng candidateLatLng,
                                              @Nullable FloorplanApiClient.FloorShapes wallCheckFloorShapes) {
        if (lastMatchedPose == null
                || lastMatchedPose.getLatLng() == null
                || wallCheckFloorShapes == null) {
            return candidateLatLng;
        }

        LatLng previousLatLng = lastMatchedPose.getLatLng();

        if (!MapGeometryUtils.crossesWall(previousLatLng, candidateLatLng, wallCheckFloorShapes)) {
            return candidateLatLng;
        }

        LatLng lastValidPoint = MapGeometryUtils.findFarthestValidPointBeforeWall(
                previousLatLng,
                candidateLatLng,
                wallCheckFloorShapes
        );

        if (lastValidPoint != null) {
            lastCorrectionType = "FINAL_WALL_CLAMP";
            lastCorrectionReason = "Final candidate crossed a wall; projected to last valid point before wall.";
            lastCrossedWall = true;
            return lastValidPoint;
        }

        lastCorrectionType = "FINAL_WALL_REJECT";
        lastCorrectionReason = "Final candidate crossed a wall; kept previous valid matched pose.";
        lastCrossedWall = true;
        return previousLatLng;
    }

    /**
     * Returns the active floor shapes for the selected building.
     */
    @Nullable
    private FloorplanApiClient.FloorShapes getFloorShapesForFloor(int floorIndex) {
        FloorplanApiClient.BuildingInfo building = getSelectedBuildingInfo();
        if (building == null) {
            return null;
        }

        List<FloorplanApiClient.FloorShapes> floors = building.getFloorShapesList();
        if (floors == null || floors.isEmpty()) {
            return null;
        }

        if (floorIndex < 0 || floorIndex >= floors.size()) {
            return null;
        }

        return floors.get(floorIndex);
    }

    /**
     * Resolves the currently selected building object from SensorFusion cache.
     */
    @Nullable
    private FloorplanApiClient.BuildingInfo getSelectedBuildingInfo() {
        String buildingId = sensorFusion.getSelectedBuildingId();
        if (buildingId == null || buildingId.isEmpty()) {
            return null;
        }
        return sensorFusion.getFloorplanBuilding(buildingId);
    }

    /**
     * Checks whether a floor index is valid for the active building.
     */
    private boolean isFloorIndexAvailable(int floorIndex) {
        FloorplanApiClient.BuildingInfo building = getSelectedBuildingInfo();
        if (building == null || building.getFloorShapesList() == null) {
            return floorIndex >= 0;
        }
        return floorIndex >= 0 && floorIndex < building.getFloorShapesList().size();
    }

    /**
     * Clamps a floor index to the valid range for the active building.
     */
    private int sanitiseFloorIndex(int floorIndex) {
        FloorplanApiClient.BuildingInfo building = getSelectedBuildingInfo();
        if (building == null || building.getFloorShapesList() == null || building.getFloorShapesList().isEmpty()) {
            return Math.max(0, floorIndex);
        }
        return Math.max(0, Math.min(floorIndex, building.getFloorShapesList().size() - 1));
    }

    /**
     * Applies a small EMA to accepted WiFi observations.
     */
    @NonNull
    private double[] smoothWifiObservation(double x, double y) {
        if (!wifiObsEmaInitialised) {
            wifiObsEmaInitialised = true;
            wifiObsEmaX = x;
            wifiObsEmaY = y;
        } else {
            wifiObsEmaX = ema(wifiObsEmaX, x, WIFI_OBS_EMA_ALPHA);
            wifiObsEmaY = ema(wifiObsEmaY, y, WIFI_OBS_EMA_ALPHA);
        }

        return new double[]{wifiObsEmaX, wifiObsEmaY};
    }

    /**
     * Extracts step distance from the cumulative PDR state.
     */
    private double extractPdrDeltaDistance() {
        float[] pdrPosition = sensorFusion.getLatestPdrMovement();
        if (pdrPosition == null || pdrPosition.length < 2) {
            return 0.0;
        }

        double px = pdrPosition[0];
        double py = pdrPosition[1];

        if (firstPdrSample) {
            firstPdrSample = false;
            lastPdrX = px;
            lastPdrY = py;
            return 0.0;
        }

        double dx = px - lastPdrX;
        double dy = py - lastPdrY;

        lastPdrX = px;
        lastPdrY = py;

        return Math.hypot(dx, dy);
    }

    /**
     * Simple scalar EMA helper.
     */
    private double ema(double previous, double current, double alpha) {
        return previous + alpha * (current - previous);
    }

    /**
     * Basic LatLng validity check.
     */
    private boolean isValidLatLng(@Nullable LatLng latLng) {
        if (latLng == null) {
            return false;
        }
        return !(Math.abs(latLng.latitude) < 1e-6 && Math.abs(latLng.longitude) < 1e-6);
    }

    /**
     * Basic float[] lat/lon validity check.
     */
    private boolean isValidLatLon(@Nullable float[] latLon) {
        if (latLon == null || latLon.length < 2) {
            return false;
        }
        return !(Math.abs(latLon[0]) < 1e-6 && Math.abs(latLon[1]) < 1e-6);
    }

    /**
     * Indoor context is active when a selected building is present in cache.
     */
    private boolean isIndoorContextActive() {
        String buildingId = sensorFusion.getSelectedBuildingId();
        return buildingId != null
                && !buildingId.isEmpty()
                && sensorFusion.getFloorplanBuilding(buildingId) != null;
    }

    /**
     * Wraps angle to [-pi, pi].
     */
    private double wrapAngle(double angle) {
        while (angle > Math.PI) angle -= 2.0 * Math.PI;
        while (angle < -Math.PI) angle += 2.0 * Math.PI;
        return angle;
    }

    /**
     * Approximates metric distance between two LatLng values.
     */
    private double distanceMeters(@Nullable LatLng a, @Nullable LatLng b) {
        if (a == null || b == null) {
            return 0.0;
        }

        double meanLatRad = Math.toRadians((a.latitude + b.latitude) * 0.5);
        double metersPerDegLat = 111320.0;
        double metersPerDegLon = 111320.0 * Math.cos(meanLatRad);

        double dx = (b.longitude - a.longitude) * metersPerDegLon;
        double dy = (b.latitude - a.latitude) * metersPerDegLat;
        return Math.hypot(dx, dy);
    }

    /**
     * Interpolates between two LatLng values.
     */
    @NonNull
    private LatLng interpolate(@NonNull LatLng from, @NonNull LatLng to, double alpha) {
        double clampedAlpha = Math.max(0.0, Math.min(1.0, alpha));
        return new LatLng(
                from.latitude + clampedAlpha * (to.latitude - from.latitude),
                from.longitude + clampedAlpha * (to.longitude - from.longitude)
        );
    }

    /**
     * Returns a cleaner live debug summary for the on-screen debug box.
     *
     * Layout intent:
     * 1. session / context
     * 2. current fused pose
     * 3. particle health
     * 4. motion + observation state
     * 5. auto-floor state
     * 6. map-matching / recovery reasoning
     *
     * This version is designed for fast live reading during walking tests.
     */
    @NonNull
    public String getLiveDebugSummary() {
        if (!enabled) {
            return "LIVE PF\nstatus: disabled";
        }

        if (particleFilterEngine == null) {
            return "LIVE PF\nstatus: waiting init";
        }

        FusedPose pose = getLatestFusedPose();

        int total = particleFilterEngine.getParticleCount();
        int alive = particleFilterEngine.getAliveParticleCount();
        int dead = particleFilterEngine.getDeadParticleCount();
        double neff = particleFilterEngine.getLastNeff();

        String buildingId = sensorFusion.getSelectedBuildingId();
        if (buildingId == null || buildingId.trim().isEmpty()) {
            buildingId = "none";
        }

        double poseX = pose != null ? pose.getXMeters() : Double.NaN;
        double poseY = pose != null ? pose.getYMeters() : Double.NaN;
        double poseHeadingDeg = pose != null ? Math.toDegrees(pose.getHeadingRad()) : Double.NaN;
        int poseFloor = pose != null ? pose.getFloor() : activePfFloor;
        float poseConfidence = pose != null ? pose.getConfidence() : 0f;

        int wifiFloor = sensorFusion.getWifiFloor();
        double elevationMeters = sensorFusion.getElevation();
        boolean autoFloorEnabled = sensorFusion.isLiveAutoFloorEnabled();
        boolean elevatorLikely = sensorFusion.getElevator();
        boolean indoorContext = isIndoorContextActive();

        return String.format(
                Locale.US,
                "LIVE PF\n" +
                        "building=%s | indoor=%s | autoFloor=%s\n" +
                        "pose: x=%.2f y=%.2f heading=%.1f° floor=%d conf=%.2f\n" +
                        "particles: total=%d alive=%d dead=%d neff=%.1f wallRatio=%.2f\n" +
                        "motion: step=%.2fm stationary=%s obs=%s\n" +
                        "floor: wifi=%d requested=%d active=%d matched=%d elev=%.2fm elevator=%s\n" +
                        "map: correction=%s wall=%s stairs=%s lift=%s allowFloor=%s\n" +
                        "mapReason: %s\n" +
                        "recovery: %s\n" +
                        "recoveryReason: %s",
                buildingId,
                String.valueOf(indoorContext),
                String.valueOf(autoFloorEnabled),
                poseX,
                poseY,
                poseHeadingDeg,
                poseFloor,
                poseConfidence,
                total,
                alive,
                dead,
                neff,
                lastWallInvalidRatio,
                lastStepDistanceMeters,
                String.valueOf(lastStationaryUpdate),
                lastObservationSource,
                wifiFloor,
                lastRequestedFloor,
                activePfFloor,
                lastMatchedFloor,
                elevationMeters,
                String.valueOf(elevatorLikely),
                lastCorrectionType,
                String.valueOf(lastCrossedWall),
                String.valueOf(lastNearStairs),
                String.valueOf(lastNearLift),
                String.valueOf(lastFloorChangeAllowed),
                lastCorrectionReason,
                lastRecoveryType,
                lastRecoveryReason
        );
    }
}