package com.openpositioning.PositionMe.fusion;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.mapmatching.CandidatePose;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.utils.BuildingPolygon;
import com.openpositioning.PositionMe.utils.IndoorMapManager;

import com.openpositioning.PositionMe.fusion.CoordinateConverter;
import com.openpositioning.PositionMe.mapmatching.VerticalMotionDetector;
import com.openpositioning.PositionMe.mapmatching.VerticalTransitionHint;
import com.openpositioning.PositionMe.mapmatching.MapGeometryUtils;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

import java.util.List;
import java.util.Locale;

/**
 * High-level controller for the live particle filter.
 *
 * Responsibilities:
 * - initialise the PF from a GNSS start anchor
 * - convert live PDR motion into PF motion updates
 * - convert map-match / WiFi / GNSS into optional absolute observations
 * - expose latest fused pose back to SensorFusion
 * - hold a first-pass hybrid map constraint model
 *
 * Notes:
 * - This class does not render anything.
 * - Rendering belongs in TrajectoryMapFragment / TrajectoryRenderer.
 * - This version is intentionally conservative so it integrates safely first.
 */
public class ParticleFilterManager {

    private static final String TAG = "ParticleFilterManager";

    /**
     * Optional callback host if another layer wants fused pose updates.
     */
    public interface Host {
        void onFusedPoseUpdated(@NonNull FusedPose fusedPose);
    }

    private final SensorFusion sensorFusion;
    private final ParticleFilterEngine engine;
    private final ParticleConstraintDebugger debugger;

    @Nullable
    private final Host host;

    @Nullable
    private IndoorMapManager indoorMapManager;

    @Nullable
    private HybridConstraintModel constraintModel;
    @Nullable
    private CoordinateConverter coordinateConverter;
    private final VerticalMotionDetector verticalMotionDetector = new VerticalMotionDetector();

    /**
     * Latest filtered pose exposed to the rest of the app.
     */
    @Nullable
    private FusedPose latestFusedPose;

    /**
     * Latest raw PF output before any future smoothing layer.
     * For now this is the same as fused output, but we keep them separate
     * so later you can add smoothing without changing the external API.
     */
    @Nullable
    private FusedPose latestRawPose;

    /**
     * Whether the PF is currently enabled by the selected recording mode.
     */
    private boolean enabled = false;

    /**
     * Whether the PF has been initialised with a real anchor.
     */
    private boolean initialised = false;

    /**
     * Last raw PDR values used to compute motion deltas.
     */
    @Nullable
    private float[] lastPdrValues;

    /**
     * Latest map-matched pose if provided by another subsystem.
     */
    @Nullable
    private CandidatePose latestMatchedPose;

    /**
     * Primary constructor using an optional host callback.
     */
    public ParticleFilterManager(@NonNull SensorFusion sensorFusion,
                                 @Nullable Host host) {
        this.sensorFusion = sensorFusion;
        this.host = host;

        Context context = sensorFusion.getContext();
        ParticleFilterEngine.Config cfg = buildConfigFromPreferences(context);

        this.engine = new ParticleFilterEngine(cfg);
        this.debugger = new ParticleConstraintDebugger(TAG);
        this.debugger.setMinIntervalMs(300);
    }

    /**
     * Compatibility constructor for branches where SensorFusion still passes Context.
     * The Context is currently unused here, but this keeps your existing SensorFusion code compiling.
     */
    public ParticleFilterManager(@NonNull SensorFusion sensorFusion,
                                 @Nullable Context ignoredContext) {
        this(sensorFusion, (Host) null);
    }

    /**
     * Enable or disable PF operation.
     * Disabling also clears its state to avoid stale outputs.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;

        Log.d(TAG,"Particle filter enabled = " + enabled);

        if (!enabled) {
            reset();
        }

        Log.d(TAG, "Particle filter enabled = " + enabled);
    }

    /**
     * Returns whether PF mode is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Attach or refresh the current indoor map manager.
     * This should be called once the active map fragment has a valid IndoorMapManager.
     */
    public void setIndoorMapManager(@Nullable IndoorMapManager indoorMapManager) {
        this.indoorMapManager = indoorMapManager;
        this.constraintModel = new HybridConstraintModel(indoorMapManager);
        this.engine.setConstraintModel(this.constraintModel);

        Log.d(TAG, "IndoorMapManager attached to ParticleFilterManager: " + (indoorMapManager != null));
    }

    /**
     * Resets the PF for a new recording session.
     */
    public void reset() {
        engine.clear();
        latestFusedPose = null;
        latestRawPose = null;
        lastPdrValues = null;
        latestMatchedPose = null;
        initialised = false;

        Log.d(TAG, "Particle filter reset.");
    }

    /**
     * Returns true if the PF currently has particles initialised.
     */
    public boolean isInitialised() {
        return initialised && engine.isInitialised();
    }

    /**
     * Returns the latest fused PF pose.
     */
    @Nullable
    public FusedPose getLatestFusedPose() {
        return latestFusedPose;
    }

    /**
     * Returns the latest raw PF pose.
     * At the moment this is the same as the fused pose.
     */
    @Nullable
    public FusedPose getLatestRawPose() {
        return latestRawPose;
    }

    /**
     * Returns a particle snapshot for debugging or visualisation.
     */
    @NonNull
    public List<ParticleFilterEngine.Particle> getParticlesSnapshot() {
        return engine.getParticlesSnapshot();
    }

    /**
     * Optional map-matched pose input.
     */
    public void setLatestMatchedPose(@Nullable CandidatePose latestMatchedPose) {
        this.latestMatchedPose = latestMatchedPose;
    }

    /**
     * Compatibility update entry for SensorFusion.
     * This method is what SensorFusion.stepParticleFilter() expects to call.
     */
    public void step() {
        if (!enabled) {
            return;
        }
        // Keep dependencies synced from SensorFusion.
        setIndoorMapManager(sensorFusion.getParticleFilterIndoorMapManager());
        setLatestMatchedPose(sensorFusion.getParticleFilterMatchedPose());

        updateFromLiveSensors(SystemClock.elapsedRealtime());
    }

    /**
     * Explicit initialisation from GNSS-based session anchor.
     *
     * This matches your desired policy:
     * - initial anchor should come from GNSS
     * - WiFi should not set the initial start point
     */
    public void initialiseFromGnss(@NonNull LatLng gnssStart,
                                   int logicalFloor,
                                   double headingRad) {
        coordinateConverter = new CoordinateConverter(
                gnssStart.latitude,
                gnssStart.longitude
        );

        engine.initialise(gnssStart, logicalFloor, headingRad);
        //At the anchor, Local x/y are exactly zero.
        latestRawPose = new FusedPose(
                0.0,
                0.0,
                headingRad,
                logicalFloor,
                gnssStart,
                0.5f
        );
        latestFusedPose = latestRawPose;

        initialised = true;
        lastPdrValues = null;
        verticalMotionDetector.reset();

        Log.d(TAG, String.format(Locale.US,
                "PF initialised from GNSS at %.6f, %.6f floor=%d heading=%.3f",
                gnssStart.latitude, gnssStart.longitude, logicalFloor, Math.toDegrees(headingRad)));

        pushLatestPoseToOutputs();
    }

    @NonNull
    private ParticleFilterEngine.Config buildConfigFromPreferences(@Nullable Context context) {
        ParticleFilterEngine.Config cfg = new ParticleFilterEngine.Config();

        cfg.particleCount = parseIntPref(context, "pf_particle_count", 250, 50, 5000);
        cfg.forwardNoiseStdMeters = parseDoublePref(context, "pf_sigma_step", 0.22, 0.01, 10.0);
        cfg.headingNoiseStdRad = Math.toRadians(
                parseDoublePref(context, "pf_sigma_theta_deg", 6.0, 0.1, 180.0)
        );

        cfg.enableMapConstraints = true;
        cfg.hardKillOnWallCross = false;
        cfg.hardKillOnInvalidFloorTransition = false;
        cfg.softPenaltyForOutOfWalkable = true;

        cfg.outOfWalkablePenalty = 0.15;
        cfg.wallCrossPenalty = 0.05;
        cfg.invalidFloorPenalty = 0.05;

        cfg.enableAbsoluteObservationWeighting = true;
        cfg.resampleEffectiveSampleSizeRatio =
                parseDoublePref(context, "pf_resample_ratio", 0.45, 0.05, 0.95);

        cfg.debugLogging = true;
        return cfg;
    }

    private int parseIntPref(@Nullable Context context,
                             @NonNull String key,
                             int defaultValue,
                             int minValue,
                             int maxValue) {
        if (context == null) {
            return defaultValue;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String raw = prefs.getString(key, String.valueOf(defaultValue));

        try {
            int parsed = Integer.parseInt(raw);
            return Math.max(minValue, Math.min(maxValue, parsed));
        } catch (Exception e) {
            Log.w(TAG, "Invalid integer preference for " + key + ": " + raw);
            return defaultValue;
        }
    }

    private double parseDoublePref(@Nullable Context context,
                                   @NonNull String key,
                                   double defaultValue,
                                   double minValue,
                                   double maxValue) {
        if (context == null) {
            return defaultValue;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String raw = prefs.getString(key, String.valueOf(defaultValue));

        try {
            double parsed = Double.parseDouble(raw);
            return Math.max(minValue, Math.min(maxValue, parsed));
        } catch (Exception e) {
            Log.w(TAG, "Invalid double preference for " + key + ": " + raw);
            return defaultValue;
        }
    }

    @NonNull
    private FusedPose enrichWithLocalCoordinates(@NonNull FusedPose geographicPose) {
        if (coordinateConverter == null) {
            return geographicPose;
        }

        double[] local = coordinateConverter.latLngToLocal(geographicPose.getLatLng());

        return new FusedPose(
                local[0],
                local[1],
                geographicPose.getHeadingRad(),
                geographicPose.getFloor(),
                geographicPose.getLatLng(),
                geographicPose.getConfidence()
        );
    }

    /**
     * Main live-update entry point.
     *
     * This should be called once per live cycle after SensorFusion already contains:
     * - latest PDR
     * - latest GNSS
     * - latest WiFi
     * - latest optional matched pose
     */
    public void updateFromLiveSensors(long timestampMs) {
        if (!enabled) {
            return;
        }

        if (!initialised) {
            tryInitialiseFromCurrentSensors();
            if (!initialised) {
                Log.d(TAG, "PF update skipped: waiting for initial GNSS anchor.");
                return;
            }
        }

        float[] pdr = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdr == null || pdr.length < 2) {
            Log.d(TAG, "PF update skipped: PDR unavailable.");
            return;
        }

        ParticleFilterEngine.MotionInput motionInput = buildMotionInput(pdr, timestampMs);
        ParticleFilterEngine.AbsoluteObservation observation = buildObservation();

        ParticleFilterEngine.StepResult result = engine.update(motionInput, observation);

        // Convert engine geographic output into the local x/y frame used by recorder/UI.
        latestRawPose = enrichWithLocalCoordinates(result.fusedPose);

        // For now fused == raw. Later you can insert KalmanPoseSmoother here.
        latestFusedPose = latestRawPose;

        pushLatestPoseToOutputs();

        Log.d(TAG, String.format(Locale.US,
                "PF_OUTPUT lat=%.6f lng=%.6f x=%.3f y=%.3f floor=%d conf=%.3f",
                latestFusedPose.getLatLng().latitude,
                latestFusedPose.getLatLng().longitude,
                latestFusedPose.getXMeters(),
                latestFusedPose.getYMeters(),
                latestFusedPose.getFloor(),
                latestFusedPose.getConfidence()));

        debugger.logStep(
                "live_update",
                result.diagnostics.totalParticles,
                result.diagnostics.aliveParticles,
                result.diagnostics.wallRejectedCount,
                result.diagnostics.floorRejectedCount,
                result.diagnostics.outOfWalkablePenalisedCount,
                result.diagnostics.observationWeightedCount,
                observation == null ? "obs:none" : ("obs:" + observation.source)
        );

        debugger.logFusedPose(
                "live_update",
                latestFusedPose.getLatLng().latitude,
                latestFusedPose.getLatLng().longitude,
                latestFusedPose.getFloor(),
                latestFusedPose.getHeadingRad(),
                latestFusedPose.getConfidence()
        );
    }

    /**
     * Pushes the latest PF outputs back into SensorFusion and host callback.
     */
    private void pushLatestPoseToOutputs() {
        sensorFusion.setLatestRawFusedPose(latestRawPose);
        sensorFusion.setLatestFusedPose(latestFusedPose);

        if (host != null && latestFusedPose != null) {
            host.onFusedPoseUpdated(latestFusedPose);
        }
    }

    /**
     * Attempts one-time PF initialisation from current GNSS.
     * GNSS is used as the initial anchor by design.
     */
    private void tryInitialiseFromCurrentSensors() {
        float[] gnss = sensorFusion.getGNSSLatitude(false);
        if (gnss == null || gnss.length < 2) {
            return;
        }

        if (Math.abs(gnss[0]) < 1e-6 && Math.abs(gnss[1]) < 1e-6) {
            return;
        }

        int floor = 0;
        double heading = sensorFusion.passOrientation();

        initialiseFromGnss(new LatLng(gnss[0], gnss[1]), floor, heading);
    }

    /**
     * Converts raw PDR into one PF motion step.
     *
     * This uses:
     * - PDR delta distance
     * - current heading change relative to previous fused heading
     * - placeholder vertical transition fields
     */
    @NonNull
    private ParticleFilterEngine.MotionInput buildMotionInput(@NonNull float[] pdr,
                                                              long timestampMs) {

        double deltaForwardMeters = 0.0;

        if (lastPdrValues != null && lastPdrValues.length >= 2) {
            double dx = pdr[0] - lastPdrValues[0];
            double dy = pdr[1] - lastPdrValues[1];
            deltaForwardMeters = Math.sqrt(dx * dx + dy * dy);
        }

        double currentHeading = sensorFusion.passOrientation();
        double deltaHeadingRad = 0.0;

        if (latestFusedPose != null) {
            deltaHeadingRad = wrapAngleRad(currentHeading - latestFusedPose.getHeadingRad());
        }

        // Build a conservative vertical hint from recent barometer/elevator evidence.
        verticalMotionDetector.addSample(
                timestampMs,
                sensorFusion.getElevation(),
                sensorFusion.getElevator()
        );
        VerticalTransitionHint verticalHint = verticalMotionDetector.buildHint();

        double deltaHeightMeters = 0.0;
        boolean heightChanged = false;
        if (verticalHint != null) {
            deltaHeightMeters = verticalHint.getDeltaHeight();
            heightChanged = verticalHint.isHeightChanged();
        }

        lastPdrValues = new float[]{pdr[0], pdr[1]};

        Log.d(TAG, String.format(Locale.US,
                "PF_MOTION deltaS=%.3f deltaHeadingDeg=%.2f deltaH=%.2f heightChanged=%s",
                deltaForwardMeters,
                Math.toDegrees(deltaHeadingRad),
                deltaHeightMeters,
                String.valueOf(heightChanged)));

        return new ParticleFilterEngine.MotionInput(
                deltaForwardMeters,
                deltaHeadingRad,
                deltaHeightMeters,
                heightChanged,
                timestampMs
        );
    }

    /**
     * Builds the strongest currently-available absolute observation.
     *
     * Priority:
     * 1) map-matched pose
     * 2) WiFi
     * 3) GNSS
     */
    @Nullable
    private ParticleFilterEngine.AbsoluteObservation buildObservation() {
        // strongest indoor observation: map matched pose
        if (latestMatchedPose != null) {
            LatLng ll = latestMatchedPose.getLatLng();

            Integer floor = null;
            if (indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) {
                floor = indoorMapManager.indexToLogicalFloor(latestMatchedPose.getFloor());
            } else {
                floor = latestMatchedPose.getFloor();
            }

            if (ll != null) {
                return new ParticleFilterEngine.AbsoluteObservation(
                        ll,
                        floor,
                        null, // CandidatePose currently does not carry heading here
                        1.2,  // strong spatial trust
                        Math.toRadians(12.0),
                        0.90,
                        "map_match"
                );
            }
        }

        // WiFi as fallback
        LatLng wifi = sensorFusion.getLatLngWifiPositioning();
        if (wifi != null && !(Math.abs(wifi.latitude) < 1e-6 && Math.abs(wifi.longitude) < 1e-6)) {
            Log.d(TAG, String.format(Locale.US,
                    "PF_OBS source=wifi lat=%.6f lng=%.6f floor=%d",
                    wifi.latitude,
                    wifi.longitude,
                    sensorFusion.getWifiFloor()));

            return new ParticleFilterEngine.AbsoluteObservation(
                    wifi,
                    sensorFusion.getWifiFloor(),
                    null,
                    5.0,
                    Math.toRadians(45.0),
                    0.45,
                    "wifi"
            );
        }

        // GNSS as weakest fallback
        float[] gnss = sensorFusion.getGNSSLatitude(false);
        if (gnss != null && gnss.length >= 2
                && !(Math.abs(gnss[0]) < 1e-6 && Math.abs(gnss[1]) < 1e-6)) {

            Log.d(TAG, String.format(Locale.US,
                    "PF_OBS source=gnss lat=%.6f lng=%.6f",
                    gnss[0], gnss[1]));

            return new ParticleFilterEngine.AbsoluteObservation(
                    new LatLng(gnss[0], gnss[1]),
                    null,
                    null,
                    8.0,
                    Math.toRadians(60.0),
                    0.30,
                    "gnss"
            );
        }

        Log.d(TAG, "PF_OBS source=none");
        return null;
    }

    /**
     * First-pass hybrid map constraint model.
     *
     * This version is intentionally coarse and safe:
     * - if no indoor map is loaded, it stays permissive
     * - if indoor map is loaded, it enforces selected-building containment
     * - wall crossing is approximated using outer-building boundary only
     * - floor changes are allowed conservatively for now
     *
     * Later can ne replace with:
     * - wall segment intersection from vector map features
     * - connector / stairs / lift region checks
     * - room / corridor walkability masks
     */
    private static class HybridConstraintModel implements ParticleFilterEngine.ConstraintModel {

        @Nullable
        private final IndoorMapManager indoorMapManager;

        HybridConstraintModel(@Nullable IndoorMapManager indoorMapManager) {
            this.indoorMapManager = indoorMapManager;
        }

        /**
         * Returns whether a PF particle is in a valid walkable coarse region.
         *
         * First-pass behaviour:
         * - if no indoor map is loaded, allow all states
         * - otherwise require coarse containment inside the selected building footprint
         */
        @Override
        public boolean isWalkable(double lat, double lng, int logicalFloor) {
            if (indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
                return true;
            }

            LatLng point = new LatLng(lat, lng);
            int currentBuilding = indoorMapManager.getCurrentBuilding();

            switch (currentBuilding) {
                case IndoorMapManager.BUILDING_NUCLEUS:
                    return BuildingPolygon.inNucleus(point);

                case IndoorMapManager.BUILDING_LIBRARY:
                    return BuildingPolygon.inLibrary(point);

                case IndoorMapManager.BUILDING_MURCHISON:
                    return BuildingPolygon.inMurchison(point);

                default:
                    return true;
            }
        }

        /**
         * Returns whether the motion segment crosses an invalid outer building boundary.
         *
         * First-pass behaviour:
         * - if both old and new are inside walkable space, do not reject
         * - if the new state leaves the selected building footprint, treat it as invalid crossing
         *
         * Later replace this with wall-segment intersection against the vector floor map.
         */
        @Override
        public boolean crossesWall(double oldLat, double oldLng,
                                   double newLat, double newLng,
                                   int logicalFloor) {
            if (indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
                return false;
            }

            boolean oldWalkable = isWalkable(oldLat, oldLng, logicalFloor);
            boolean newWalkable = isWalkable(newLat, newLng, logicalFloor);

            return oldWalkable && !newWalkable;
        }

        /**
         * Returns whether a floor transition is allowed.
         *
         * First-pass behaviour:
         * - same floor is always valid
         * - if no indoor map is present, allow
         * - if indoor map is present, still allow conservatively for now
         *
         * Later replace this with:
         * - stairs region
         * - lift region
         * - connector region checks
         */
        @Override
        public boolean isFloorTransitionAllowed(double lat, double lng, int oldFloor, int newFloor) {
            if (oldFloor == newFloor) {
                return true;
            }

            if (indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
                return true;
            }

            // Conservative first integration: allow, then refine later.
            return true;
        }

        /**
         * Returns a soft map likelihood in [0,1].
         *
         * First-pass behaviour:
         * - 1.0 if valid inside coarse building footprint
         * - 0.15 if outside valid coarse indoor region
         */
        @Override
        public double mapLikelihood(double lat, double lng, int logicalFloor) {
            if (indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
                return 1.0;
            }

            return isWalkable(lat, lng, logicalFloor) ? 1.0 : 0.15;
        }

        /**
         * Optional recovery pose.
         * Not used yet in this first integration.
         */
        @Nullable
        @Override
        public CandidatePose getRecoveryPose(@NonNull LatLng currentLatLng, int logicalFloor) {
            return null;
        }
    }

    /**
     * Wrap angle to [-pi, pi].
     */
    private static double wrapAngleRad(double a) {
        while (a > Math.PI) a -= 2.0 * Math.PI;
        while (a < -Math.PI) a += 2.0 * Math.PI;
        return a;
    }
}