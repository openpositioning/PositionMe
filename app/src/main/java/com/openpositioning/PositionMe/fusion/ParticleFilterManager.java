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
import com.openpositioning.PositionMe.mapmatching.MapGeometryUtils;
import com.openpositioning.PositionMe.mapmatching.VerticalMotionDetector;
import com.openpositioning.PositionMe.mapmatching.VerticalTransitionHint;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.utils.BuildingPolygon;
import com.openpositioning.PositionMe.utils.IndoorMapManager;

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
 * - apply indoor-map constraints using the same geometry helpers as map matching
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
    private final ParticleConstraintDebugger debugger;
    private final VerticalMotionDetector verticalMotionDetector = new VerticalMotionDetector();
    private final KalmanPoseSmoother kalmanPoseSmoother = new KalmanPoseSmoother();

    @Nullable
    private final Host host;

    @Nullable
    private IndoorMapManager indoorMapManager;
    @Nullable
    private HybridConstraintModel constraintModel;
    @Nullable
    private CoordinateConverter coordinateConverter;
    @Nullable
    private ParticleFilterEngine engine;
    @Nullable
    private ParticleFilterEngine.Config activeConfig;
    @Nullable
    private ParticleFilterEngine.StepDiagnostics lastDiagnostics;

    @NonNull
    private String lastObservationSummary = "obs:none";
    @NonNull
    private String lastMotionSummary = "motion:idle";

    /** Latest filtered pose exposed to the rest of the app. */
    @Nullable
    private FusedPose latestFusedPose;

    /** Latest raw PF output before Kalman-style output smoothing. */
    @Nullable
    private FusedPose latestRawPose;

    /** Whether the PF is currently enabled by the selected recording mode. */
    private boolean enabled = false;

    /** Whether the PF has been initialised with a real anchor. */
    private boolean initialised = false;

    /** Last raw PDR values used to compute motion deltas. */
    @Nullable
    private float[] lastPdrValues;

    /** Latest map-matched pose if provided by another subsystem. */
    @Nullable
    private CandidatePose latestMatchedPose;

    /**
     * Primary constructor using an optional host callback.
     */
    public ParticleFilterManager(@NonNull SensorFusion sensorFusion,
                                 @Nullable Host host) {
        this.sensorFusion = sensorFusion;
        this.host = host;
        this.debugger = new ParticleConstraintDebugger(TAG);
        this.debugger.setMinIntervalMs(300);
        reloadRuntimeSettings(true);
    }

    /**
     * Compatibility constructor for branches where SensorFusion still passes Context.
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

        if (enabled) {
            reloadRuntimeSettings(false);
        } else {
            reset();
        }

        Log.d(TAG, "Particle filter enabled = " + enabled);
    }

    /** Returns whether PF mode is enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Attach or refresh the current indoor map manager.
     */
    public void setIndoorMapManager(@Nullable IndoorMapManager indoorMapManager) {
        if (this.indoorMapManager == indoorMapManager && constraintModel != null) {
            return;
        }

        this.indoorMapManager = indoorMapManager;
        this.constraintModel = new HybridConstraintModel(indoorMapManager);

        if (engine != null) {
            engine.setConstraintModel(this.constraintModel);
        }

        Log.d(TAG, "IndoorMapManager attached to ParticleFilterManager: " + (indoorMapManager != null));
    }

    /** Resets the PF for a new recording session. */
    public void reset() {
        reloadRuntimeSettings(true);
        latestFusedPose = null;
        latestRawPose = null;
        lastPdrValues = null;
        latestMatchedPose = null;
        initialised = false;
        lastDiagnostics = null;
        lastObservationSummary = "obs:none";
        lastMotionSummary = "motion:idle";
        verticalMotionDetector.reset();
        kalmanPoseSmoother.reset();

        if (engine != null) {
            engine.clear();
        }

        Log.d(TAG, "Particle filter reset.");
    }

    /** Returns true if the PF currently has particles initialised. */
    public boolean isInitialised() {
        return initialised && engine != null && engine.isInitialised();
    }

    /** Returns the latest fused PF pose. */
    @Nullable
    public FusedPose getLatestFusedPose() {
        return latestFusedPose;
    }

    /** Returns the latest raw PF pose. */
    @Nullable
    public FusedPose getLatestRawPose() {
        return latestRawPose;
    }

    /** Returns a particle snapshot for debugging or visualisation. */
    @NonNull
    public List<ParticleFilterEngine.Particle> getParticlesSnapshot() {
        if (engine == null) {
            return java.util.Collections.emptyList();
        }
        return engine.getParticlesSnapshot();
    }

    /** Optional map-matched pose input. */
    public void setLatestMatchedPose(@Nullable CandidatePose latestMatchedPose) {
        this.latestMatchedPose = latestMatchedPose;
    }

    /**
     * Concise live debug summary for the live map debug panel.
     */
    @NonNull
    public String getLiveDebugSummary() {
        if (!enabled) {
            return "pf=off";
        }
        if (lastDiagnostics == null) {
            return "pf=waiting_for_step";
        }

        int deadParticles = Math.max(0,
                lastDiagnostics.totalParticles - lastDiagnostics.aliveParticles);

        return String.format(Locale.US,
                "pf alive=%d dead=%d total=%d ess=%.1f wall=%d floor=%d walk=%d obs=%s %s resample=%s recover=%s",
                lastDiagnostics.aliveParticles,
                deadParticles,
                lastDiagnostics.totalParticles,
                lastDiagnostics.effectiveSampleSize,
                lastDiagnostics.wallRejectedCount,
                lastDiagnostics.floorRejectedCount,
                lastDiagnostics.outOfWalkablePenalisedCount,
                lastObservationSummary,
                lastMotionSummary,
                String.valueOf(lastDiagnostics.resampled),
                String.valueOf(lastDiagnostics.recovered));
    }

    /**
     * Compatibility update entry for SensorFusion.
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
     */
    public void initialiseFromGnss(@NonNull LatLng gnssStart,
                                   int logicalFloor,
                                   double headingRad) {
        if (engine == null) {
            reloadRuntimeSettings(true);
        }
        if (engine == null) {
            return;
        }

        coordinateConverter = new CoordinateConverter(
                gnssStart.latitude,
                gnssStart.longitude
        );

        kalmanPoseSmoother.reset();
        engine.initialise(gnssStart, logicalFloor, headingRad);

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

        cfg.observationSigmaWifiMeters = parseDoublePref(context, "pf_sigma_wifi", 2.5, 0.3, 50.0);
        cfg.observationSigmaGnssMeters = parseDoublePref(context, "pf_sigma_gnss", 5.0, 0.5, 100.0);
        cfg.initialPositionStdMeters = parseDoublePref(context, "pf_init_pos_std", 1.0, 0.05, 20.0);
        cfg.initialHeadingStdRad = Math.toRadians(
                parseDoublePref(context, "pf_init_heading_deg", 10.0, 0.1, 180.0)
        );
        cfg.resampleEffectiveSampleSizeRatio =
                parseDoublePref(context, "pf_resample_ratio", 0.45, 0.05, 0.95);
        cfg.resampleRegularizationPosStdMeters =
                parseDoublePref(context, "pf_sigma_reg_pos", 0.03, 0.0, 5.0);
        cfg.resampleRegularizationHeadingStdRad = Math.toRadians(
                parseDoublePref(context, "pf_sigma_reg_theta_deg", 1.0, 0.0, 45.0)
        );

        cfg.enableMapConstraints = true;
        cfg.hardKillOnWallCross = false;
        cfg.hardKillOnInvalidFloorTransition = false;
        cfg.softPenaltyForOutOfWalkable = true;

        cfg.outOfWalkablePenalty = 0.18;
        cfg.wallCrossPenalty = 0.05;
        cfg.invalidFloorPenalty = 0.08;

        cfg.enableAbsoluteObservationWeighting = true;
        cfg.debugLogging = true;
        return cfg;
    }

    private void reloadRuntimeSettings(boolean forceRecreateEngine) {
        Context context = sensorFusion.getContext();
        ParticleFilterEngine.Config cfg = buildConfigFromPreferences(context);
        activeConfig = cfg;

        if (forceRecreateEngine || engine == null || !initialised) {
            engine = new ParticleFilterEngine(cfg);
            if (constraintModel != null) {
                engine.setConstraintModel(constraintModel);
            }
            initialised = false;
        }

        Log.d(TAG, String.format(Locale.US,
                "PF_CONFIG particles=%d sigmaStep=%.3f sigmaThetaDeg=%.2f sigmaWifi=%.2f sigmaGnss=%.2f initPos=%.2f initHeadingDeg=%.2f resample=%.2f regPos=%.3f regThetaDeg=%.2f",
                cfg.particleCount,
                cfg.forwardNoiseStdMeters,
                Math.toDegrees(cfg.headingNoiseStdRad),
                cfg.observationSigmaWifiMeters,
                cfg.observationSigmaGnssMeters,
                cfg.initialPositionStdMeters,
                Math.toDegrees(cfg.initialHeadingStdRad),
                cfg.resampleEffectiveSampleSizeRatio,
                cfg.resampleRegularizationPosStdMeters,
                Math.toDegrees(cfg.resampleRegularizationHeadingStdRad)));
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
     */
    public void updateFromLiveSensors(long timestampMs) {
        if (!enabled) {
            return;
        }

        if (engine == null) {
            reloadRuntimeSettings(true);
        }
        if (engine == null) {
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
        lastDiagnostics = result.diagnostics;

        // Convert engine geographic output into the local x/y frame used by recorder/UI.
        latestRawPose = enrichWithLocalCoordinates(result.fusedPose);

        // Kalman-style output smoothing is applied only to the exported/displayed pose.
        KalmanPoseSmoother.SmoothedPose smoothedPose = kalmanPoseSmoother.update(
                latestRawPose.getXMeters(),
                latestRawPose.getYMeters(),
                latestRawPose.getHeadingRad(),
                latestRawPose.getConfidence(),
                motionInput.deltaForwardMeters,
                Math.abs(motionInput.deltaHeadingRad),
                observation != null && ("wifi".equals(observation.source) || "map_match".equals(observation.source)),
                observation != null && "gnss".equals(observation.source)
        );

        LatLng smoothedLatLng = latestRawPose.getLatLng();
        if (coordinateConverter != null) {
            smoothedLatLng = coordinateConverter.localToLatLng(smoothedPose.x, smoothedPose.y);
        }

        latestFusedPose = new FusedPose(
                smoothedPose.x,
                smoothedPose.y,
                smoothedPose.theta,
                latestRawPose.getFloor(),
                smoothedLatLng,
                latestRawPose.getConfidence()
        );

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
                lastObservationSummary
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

    /** Pushes the latest PF outputs back into SensorFusion and host callback. */
    private void pushLatestPoseToOutputs() {
        sensorFusion.setLatestRawFusedPose(latestRawPose);
        sensorFusion.setLatestFusedPose(latestFusedPose);

        if (host != null && latestFusedPose != null) {
            host.onFusedPoseUpdated(latestFusedPose);
        }
    }

    /** Attempts one-time PF initialisation from current GNSS. */
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
        String connectorState = "connector=none";
        if (verticalHint != null) {
            deltaHeightMeters = verticalHint.getDeltaHeight();
            heightChanged = verticalHint.isHeightChanged();

            if (constraintModel != null && latestFusedPose != null) {
                connectorState = "connector=" + constraintModel.classifyNearbyConnector(
                        latestFusedPose.getLatLng().latitude,
                        latestFusedPose.getLatLng().longitude,
                        latestFusedPose.getFloor());
                boolean nearStairs = constraintModel.isNearStairs(
                        latestFusedPose.getLatLng().latitude,
                        latestFusedPose.getLatLng().longitude,
                        latestFusedPose.getFloor());
                boolean nearLift = constraintModel.isNearLift(
                        latestFusedPose.getLatLng().latitude,
                        latestFusedPose.getLatLng().longitude,
                        latestFusedPose.getFloor());

                if (heightChanged && !nearStairs && !nearLift) {
                    heightChanged = false;
                }
                if (heightChanged && sensorFusion.getElevator() && !nearLift) {
                    heightChanged = false;
                }
            }
        }

        lastPdrValues = new float[]{pdr[0], pdr[1]};

        lastMotionSummary = String.format(Locale.US,
                "motion=ds%.2f dθ%.1f° dh%.2f %s hc=%s",
                deltaForwardMeters,
                Math.toDegrees(deltaHeadingRad),
                deltaHeightMeters,
                connectorState,
                String.valueOf(heightChanged));

        Log.d(TAG, String.format(Locale.US,
                "PF_MOTION deltaS=%.3f deltaHeadingDeg=%.2f deltaH=%.2f heightChanged=%s %s",
                deltaForwardMeters,
                Math.toDegrees(deltaHeadingRad),
                deltaHeightMeters,
                String.valueOf(heightChanged),
                connectorState));

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
        LatLng currentBelief = latestFusedPose != null ? latestFusedPose.getLatLng() : null;
        double sigmaWifi = activeConfig != null ? activeConfig.observationSigmaWifiMeters : 2.5;
        double sigmaGnss = activeConfig != null ? activeConfig.observationSigmaGnssMeters : 5.0;

        // Strongest indoor observation: map matched pose.
        if (latestMatchedPose != null && latestMatchedPose.getLatLng() != null) {
            LatLng ll = latestMatchedPose.getLatLng();
            Integer floor;
            if (indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) {
                floor = indoorMapManager.indexToLogicalFloor(latestMatchedPose.getFloor());
            } else {
                floor = latestMatchedPose.getFloor();
            }

            if (currentBelief != null) {
                double jumpMeters = distanceMeters(currentBelief, ll);
                if (jumpMeters > 20.0) {
                    lastObservationSummary = String.format(Locale.US,
                            "obs:map_match_reject_jump(%.1fm)", jumpMeters);
                    Log.d(TAG, lastObservationSummary);
                    return null;
                }
            }

            lastObservationSummary = String.format(Locale.US,
                    "obs:map_match floor=%s", String.valueOf(floor));
            return new ParticleFilterEngine.AbsoluteObservation(
                    ll,
                    floor,
                    latestMatchedPose.getHeadingRad(),
                    1.2,
                    Math.toRadians(12.0),
                    0.92,
                    "map_match"
            );
        }

        // WiFi as indoor fallback.
        LatLng wifi = sensorFusion.getLatLngWifiPositioning();
        int wifiCount = sensorFusion.getWifiList() == null ? 0 : sensorFusion.getWifiList().size();
        if (wifi != null && !(Math.abs(wifi.latitude) < 1e-6 && Math.abs(wifi.longitude) < 1e-6)) {
            if (wifiCount < 3) {
                lastObservationSummary = "obs:wifi_reject_low_ap_count";
                Log.d(TAG, lastObservationSummary);
                return null;
            }

            if (currentBelief != null) {
                double jumpMeters = distanceMeters(currentBelief, wifi);
                double gateMeters = indoorMapManager != null && indoorMapManager.getIsIndoorMapSet() ? 22.0 : 30.0;
                if (jumpMeters > gateMeters) {
                    lastObservationSummary = String.format(Locale.US,
                            "obs:wifi_reject_jump(%.1fm)", jumpMeters);
                    Log.d(TAG, lastObservationSummary);
                    return null;
                }
            }

            double confidence = wifiCount >= 6 ? 0.65 : (wifiCount >= 4 ? 0.50 : 0.35);
            double sigmaMeters = sigmaWifi * (wifiCount >= 6 ? 1.0 : 1.35);
            int wifiFloor = sensorFusion.getWifiFloor();

            if (constraintModel != null && indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()
                    && !constraintModel.isWalkable(wifi.latitude, wifi.longitude, wifiFloor)) {
                confidence *= 0.6;
                sigmaMeters *= 1.5;
            }

            lastObservationSummary = String.format(Locale.US,
                    "obs:wifi ap=%d floor=%d", wifiCount, wifiFloor);
            Log.d(TAG, String.format(Locale.US,
                    "PF_OBS source=wifi lat=%.6f lng=%.6f floor=%d ap=%d",
                    wifi.latitude,
                    wifi.longitude,
                    wifiFloor,
                    wifiCount));

            return new ParticleFilterEngine.AbsoluteObservation(
                    wifi,
                    wifiFloor,
                    null,
                    sigmaMeters,
                    Math.toRadians(45.0),
                    confidence,
                    "wifi"
            );
        }

        // GNSS as weakest fallback.
        float[] gnss = sensorFusion.getGNSSLatitude(false);
        if (gnss != null && gnss.length >= 2
                && !(Math.abs(gnss[0]) < 1e-6 && Math.abs(gnss[1]) < 1e-6)) {
            LatLng gnssLatLng = new LatLng(gnss[0], gnss[1]);

            if (currentBelief != null) {
                double jumpMeters = distanceMeters(currentBelief, gnssLatLng);
                double gateMeters = indoorMapManager != null && indoorMapManager.getIsIndoorMapSet() ? 38.0 : 50.0;
                if (jumpMeters > gateMeters) {
                    lastObservationSummary = String.format(Locale.US,
                            "obs:gnss_reject_jump(%.1fm)", jumpMeters);
                    Log.d(TAG, lastObservationSummary);
                    return null;
                }
            }

            double confidence = indoorMapManager != null && indoorMapManager.getIsIndoorMapSet() ? 0.12 : 0.30;
            double sigmaMeters = indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()
                    ? sigmaGnss * 1.8
                    : sigmaGnss;

            if (constraintModel != null && indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()
                    && !constraintModel.isWalkable(gnssLatLng.latitude, gnssLatLng.longitude,
                    latestFusedPose != null ? latestFusedPose.getFloor() : 0)) {
                confidence *= 0.5;
                sigmaMeters *= 1.5;
            }

            lastObservationSummary = "obs:gnss";
            Log.d(TAG, String.format(Locale.US,
                    "PF_OBS source=gnss lat=%.6f lng=%.6f",
                    gnss[0], gnss[1]));

            return new ParticleFilterEngine.AbsoluteObservation(
                    gnssLatLng,
                    null,
                    null,
                    sigmaMeters,
                    Math.toRadians(60.0),
                    confidence,
                    "gnss"
            );
        }

        lastObservationSummary = "obs:none";
        Log.d(TAG, "PF_OBS source=none");
        return null;
    }

    private static double distanceMeters(@NonNull LatLng a, @NonNull LatLng b) {
        double dLat = (b.latitude - a.latitude) * 111320.0;
        double midLatRad = Math.toRadians((a.latitude + b.latitude) * 0.5);
        double dLng = (b.longitude - a.longitude) * 111320.0 * Math.cos(midLatRad);
        return Math.sqrt(dLat * dLat + dLng * dLng);
    }

    /**
     * Hybrid map constraint model backed by IndoorMapManager floor shapes.
     */
    private static class HybridConstraintModel implements ParticleFilterEngine.ConstraintModel {

        private static final double WALL_EXCLUSION_METERS = 0.30;
        private static final double NEAR_WALL_SOFT_METERS = 1.20;

        @Nullable
        private final IndoorMapManager indoorMapManager;

        HybridConstraintModel(@Nullable IndoorMapManager indoorMapManager) {
            this.indoorMapManager = indoorMapManager;
        }

        @Nullable
        private FloorplanApiClient.FloorShapes getFloorShapes(int logicalFloor) {
            if (indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
                return null;
            }
            return indoorMapManager.getFloorShapesForLogicalFloor(logicalFloor);
        }

        private boolean isInsideSelectedBuilding(@NonNull LatLng point) {
            if (indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
                return true;
            }

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

        boolean isNearStairs(double lat, double lng, int logicalFloor) {
            FloorplanApiClient.FloorShapes floorShapes = getFloorShapes(logicalFloor);
            if (floorShapes == null) {
                return false;
            }
            LatLng point = new LatLng(lat, lng);
            return MapGeometryUtils.isInsideIndoorType(point, floorShapes, "stairs")
                    || MapGeometryUtils.isNearStairs(point, floorShapes);
        }

        boolean isNearLift(double lat, double lng, int logicalFloor) {
            FloorplanApiClient.FloorShapes floorShapes = getFloorShapes(logicalFloor);
            if (floorShapes == null) {
                return false;
            }
            LatLng point = new LatLng(lat, lng);
            return MapGeometryUtils.isInsideIndoorType(point, floorShapes, "lift")
                    || MapGeometryUtils.isNearLift(point, floorShapes);
        }

        @NonNull
        String classifyNearbyConnector(double lat, double lng, int logicalFloor) {
            boolean stairs = isNearStairs(lat, lng, logicalFloor);
            boolean lift = isNearLift(lat, lng, logicalFloor);
            if (stairs && lift) {
                return "stairs+lift";
            }
            if (stairs) {
                return "stairs";
            }
            if (lift) {
                return "lift";
            }
            return "none";
        }

        @Override
        public boolean isWalkable(double lat, double lng, int logicalFloor) {
            LatLng point = new LatLng(lat, lng);
            if (!isInsideSelectedBuilding(point)) {
                return false;
            }

            FloorplanApiClient.FloorShapes floorShapes = getFloorShapes(logicalFloor);
            if (floorShapes == null) {
                return true;
            }

            double nearestWallMeters = nearestWallDistanceMeters(point, floorShapes);
            return nearestWallMeters > WALL_EXCLUSION_METERS;
        }

        @Override
        public boolean crossesWall(double oldLat, double oldLng,
                                   double newLat, double newLng,
                                   int logicalFloor) {
            if (indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
                return false;
            }

            FloorplanApiClient.FloorShapes floorShapes = getFloorShapes(logicalFloor);
            if (floorShapes == null) {
                return isWalkable(oldLat, oldLng, logicalFloor) && !isWalkable(newLat, newLng, logicalFloor);
            }

            return MapGeometryUtils.crossesWall(
                    new LatLng(oldLat, oldLng),
                    new LatLng(newLat, newLng),
                    floorShapes
            );
        }

        @Override
        public boolean isFloorTransitionAllowed(double lat, double lng, int oldFloor, int newFloor) {
            if (oldFloor == newFloor) {
                return true;
            }

            if (indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
                return true;
            }

            boolean stairsSource = isNearStairs(lat, lng, oldFloor);
            boolean liftSource = isNearLift(lat, lng, oldFloor);
            boolean stairsTarget = isNearStairs(lat, lng, newFloor);
            boolean liftTarget = isNearLift(lat, lng, newFloor);

            return (stairsSource && stairsTarget)
                    || (liftSource && liftTarget)
                    || stairsSource || liftSource || stairsTarget || liftTarget;
        }

        @Override
        public double mapLikelihood(double lat, double lng, int logicalFloor) {
            LatLng point = new LatLng(lat, lng);
            if (!isInsideSelectedBuilding(point)) {
                return 0.05;
            }

            FloorplanApiClient.FloorShapes floorShapes = getFloorShapes(logicalFloor);
            if (floorShapes == null) {
                return 0.80;
            }

            double nearestWallMeters = nearestWallDistanceMeters(point, floorShapes);
            if (nearestWallMeters <= 0.10) {
                return 0.05;
            }
            if (nearestWallMeters <= WALL_EXCLUSION_METERS) {
                return 0.20;
            }
            if (nearestWallMeters <= 0.70) {
                return 0.55;
            }
            if (nearestWallMeters <= NEAR_WALL_SOFT_METERS) {
                return 0.80;
            }
            return 1.0;
        }

        @Nullable
        @Override
        public CandidatePose getRecoveryPose(@NonNull LatLng currentLatLng, int logicalFloor) {
            if (isWalkable(currentLatLng.latitude, currentLatLng.longitude, logicalFloor)) {
                return new CandidatePose(currentLatLng, logicalFloor, SystemClock.elapsedRealtime(), "pf_recovery");
            }

            FloorplanApiClient.FloorShapes floorShapes = getFloorShapes(logicalFloor);
            if (floorShapes == null) {
                return null;
            }

            LatLng stairs = MapGeometryUtils.findNearestSafeInteriorPointOnIndoorType(
                    currentLatLng,
                    floorShapes,
                    "stairs"
            );
            LatLng lift = MapGeometryUtils.findNearestSafeInteriorPointOnIndoorType(
                    currentLatLng,
                    floorShapes,
                    "lift"
            );

            LatLng best = null;
            if (stairs != null && lift != null) {
                best = distanceMeters(currentLatLng, stairs) <= distanceMeters(currentLatLng, lift) ? stairs : lift;
            } else if (stairs != null) {
                best = stairs;
            } else if (lift != null) {
                best = lift;
            }

            if (best == null) {
                return null;
            }

            return new CandidatePose(best, logicalFloor, SystemClock.elapsedRealtime(), "pf_recovery");
        }

        private static double nearestWallDistanceMeters(@NonNull LatLng point,
                                                        @NonNull FloorplanApiClient.FloorShapes floorShapes) {
            double best = Double.POSITIVE_INFINITY;
            if (floorShapes.getFeatures() == null) {
                return best;
            }

            for (FloorplanApiClient.MapShapeFeature feature : floorShapes.getFeatures()) {
                if (!"wall".equalsIgnoreCase(feature.getIndoorType())) {
                    continue;
                }
                if (feature.getParts() == null) {
                    continue;
                }
                for (List<LatLng> part : feature.getParts()) {
                    if (part == null || part.size() < 2) {
                        continue;
                    }
                    for (int i = 0; i < part.size() - 1; i++) {
                        best = Math.min(best, pointToSegmentDistanceMeters(point, part.get(i), part.get(i + 1)));
                    }
                }
            }
            return best;
        }

        private static double pointToSegmentDistanceMeters(@NonNull LatLng p,
                                                           @NonNull LatLng a,
                                                           @NonNull LatLng b) {
            double[] pxy = toLocalMeters(p, p);
            double[] axy = toLocalMeters(a, p);
            double[] bxy = toLocalMeters(b, p);

            double ax = axy[0];
            double ay = axy[1];
            double bx = bxy[0];
            double by = bxy[1];
            double px = pxy[0];
            double py = pxy[1];

            double vx = bx - ax;
            double vy = by - ay;
            double wx = px - ax;
            double wy = py - ay;

            double vv = vx * vx + vy * vy;
            if (vv <= 1e-9) {
                double dx = px - ax;
                double dy = py - ay;
                return Math.sqrt(dx * dx + dy * dy);
            }

            double t = (wx * vx + wy * vy) / vv;
            t = Math.max(0.0, Math.min(1.0, t));

            double cx = ax + t * vx;
            double cy = ay + t * vy;
            double dx = px - cx;
            double dy = py - cy;
            return Math.sqrt(dx * dx + dy * dy);
        }

        private static double[] toLocalMeters(@NonNull LatLng point, @NonNull LatLng origin) {
            double dNorth = (point.latitude - origin.latitude) * 111320.0;
            double midLatRad = Math.toRadians((point.latitude + origin.latitude) * 0.5);
            double dEast = (point.longitude - origin.longitude) * 111320.0 * Math.cos(midLatRad);
            return new double[]{dEast, dNorth};
        }

        private static double distanceMeters(@NonNull LatLng a, @NonNull LatLng b) {
            double dLat = (b.latitude - a.latitude) * 111320.0;
            double midLatRad = Math.toRadians((a.latitude + b.latitude) * 0.5);
            double dLng = (b.longitude - a.longitude) * 111320.0 * Math.cos(midLatRad);
            return Math.sqrt(dLat * dLat + dLng * dLng);
        }
    }

    /** Wrap angle to [-pi, pi]. */
    private static double wrapAngleRad(double a) {
        while (a > Math.PI) a -= 2.0 * Math.PI;
        while (a < -Math.PI) a += 2.0 * Math.PI;
        return a;
    }
}
