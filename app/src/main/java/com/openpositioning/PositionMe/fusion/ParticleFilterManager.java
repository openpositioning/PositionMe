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
import com.openpositioning.PositionMe.utils.IndoorMapManager;

import java.util.List;
import java.util.Locale;

/**
 * High-level controller for the live particle filter.
 *
 * Cleaned design goals:
 * - keep fusion state in local x/y
 * - convert LatLng to local only when building an observation
 * - convert local back to LatLng only when exporting a fused pose
 * - keep map usage simple: wall crossing and floor-transition gating only
 * - remove mapLikelihood and walkable-weight tuning completely
 */
public class ParticleFilterManager {

    private static final String TAG = "ParticleFilterManager";

    public interface Host {
        void onFusedPoseUpdated(@NonNull FusedPose fusedPose);
    }

    private static final double PF_MOTION_SCALE = 0.85;
    private static final double PF_MOTION_DEADBAND_METERS = 0.02;
    private static final double PF_MAX_STEP_METERS = 1.20;
    private static final double PF_DRAW_MIN_TRANSLATION_METERS = 0.06;

    private static final int WIFI_MIN_AP_COUNT = 8;
    private static final double MAP_MATCH_JUMP_GATE_METERS = 12.0;
    private static final double WIFI_JUMP_GATE_INDOOR_METERS = 8.0;
    private static final double WIFI_JUMP_GATE_OUTDOOR_METERS = 18.0;
    private static final double GNSS_JUMP_GATE_OUTDOOR_METERS = 30.0;

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
    private ParticleFilterConfig activeConfig;
    @Nullable
    private ParticleFilterEngine.StepDiagnostics lastDiagnostics;

    @NonNull
    private String lastObservationSummary = "obs:none";
    @NonNull
    private String lastMotionSummary = "motion:idle";

    @Nullable
    private FusedPose latestFusedPose;
    @Nullable
    private FusedPose latestRawPose;

    private boolean enabled = false;
    private boolean initialised = false;

    @Nullable
    private float[] lastPdrValues;

    @Nullable
    private CandidatePose latestMatchedPose;

    private boolean lastPoseShouldBeDrawn = false;
    private boolean lastMotionWasMeaningful = false;

    public ParticleFilterManager(@NonNull SensorFusion sensorFusion,
                                 @Nullable Host host) {
        this.sensorFusion = sensorFusion;
        this.host = host;
        this.debugger = new ParticleConstraintDebugger(TAG);
        this.debugger.setMinIntervalMs(300);
        reloadRuntimeSettings(true);
    }

    public ParticleFilterManager(@NonNull SensorFusion sensorFusion,
                                 @Nullable Context ignoredContext) {
        this(sensorFusion, (Host) null);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;

        if (enabled) {
            reloadRuntimeSettings(true);
        } else {
            reset();
        }

        Log.d(TAG, "Particle filter enabled = " + enabled);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean shouldDrawLatestPose() {
        return lastPoseShouldBeDrawn;
    }

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

    public void reset() {
        latestFusedPose = null;
        latestRawPose = null;
        lastPdrValues = null;
        latestMatchedPose = null;
        initialised = false;
        lastDiagnostics = null;
        lastObservationSummary = "obs:none";
        lastMotionSummary = "motion:idle";
        lastPoseShouldBeDrawn = false;
        lastMotionWasMeaningful = false;

        verticalMotionDetector.reset();
        kalmanPoseSmoother.reset();

        if (engine != null) {
            engine.clear();
        }

        Log.d(TAG, "Particle filter reset.");
    }

    public boolean isInitialised() {
        return initialised && engine != null && engine.isInitialised();
    }

    @Nullable
    public FusedPose getLatestFusedPose() {
        return latestFusedPose;
    }

    @Nullable
    public FusedPose getLatestRawPose() {
        return latestRawPose;
    }

    @NonNull
    public List<ParticleFilterEngine.Particle> getParticlesSnapshot() {
        if (engine == null) {
            return java.util.Collections.emptyList();
        }
        return engine.getParticlesSnapshot();
    }

    public void setLatestMatchedPose(@Nullable CandidatePose latestMatchedPose) {
        this.latestMatchedPose = latestMatchedPose;
    }

    @NonNull
    public String getLiveDebugSummary() {
        if (!enabled) {
            return "pf=off";
        }
        if (lastDiagnostics == null) {
            return "pf=waiting_for_step";
        }

        int deadParticles = Math.max(
                0,
                lastDiagnostics.totalParticles - lastDiagnostics.aliveParticles
        );

        return String.format(Locale.US,
                "pf alive=%d dead=%d total=%d ess=%.1f wall=%d floor=%d obs=%s %s resample=%s recover=%s",
                lastDiagnostics.aliveParticles,
                deadParticles,
                lastDiagnostics.totalParticles,
                lastDiagnostics.effectiveSampleSize,
                lastDiagnostics.wallRejectedCount,
                lastDiagnostics.floorRejectedCount,
                lastObservationSummary,
                lastMotionSummary,
                String.valueOf(lastDiagnostics.resampled),
                String.valueOf(lastDiagnostics.recovered));
    }

    public void step() {
        if (!enabled) {
            return;
        }

        setIndoorMapManager(sensorFusion.getParticleFilterIndoorMapManager());
        setLatestMatchedPose(sensorFusion.getParticleFilterMatchedPose());

        updateFromLiveSensors(SystemClock.elapsedRealtime());
    }

    /**
     * Initialise the local PF at x=0, y=0 using the GNSS anchor as the
     * coordinate-converter origin.
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

        engine.initialise(0.0, 0.0, logicalFloor, headingRad);
        kalmanPoseSmoother.reset();

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
    private ParticleFilterConfig buildConfigFromPreferences(@Nullable Context context) {
        int particleCount = parseIntPref(context, "pf_particle_count", 250, 50, 5000);

        double sigmaStep = parseDoublePref(context, "pf_sigma_step", 0.22, 0.01, 10.0);
        double sigmaThetaRad = Math.toRadians(
                parseDoublePref(context, "pf_sigma_theta_deg", 6.0, 0.1, 180.0)
        );

        double sigmaWifi = parseDoublePref(context, "pf_sigma_wifi", 2.5, 0.3, 50.0);
        double sigmaGnss = parseDoublePref(context, "pf_sigma_gnss", 5.0, 0.5, 100.0);

        double initPosStd = parseDoublePref(context, "pf_init_pos_std", 1.0, 0.05, 20.0);
        double initHeadingStdRad = Math.toRadians(
                parseDoublePref(context, "pf_init_heading_deg", 10.0, 0.1, 180.0)
        );

        double resampleRatio = parseDoublePref(context, "pf_resample_ratio", 0.45, 0.05, 0.95);
        double sigmaRegPos = parseDoublePref(context, "pf_sigma_reg_pos", 0.03, 0.0, 5.0);
        double sigmaRegThetaRad = Math.toRadians(
                parseDoublePref(context, "pf_sigma_reg_theta_deg", 1.0, 0.0, 45.0)
        );

        ParticleFilterConfig cfg = new ParticleFilterConfig(
                particleCount,
                sigmaStep,
                sigmaThetaRad,
                sigmaWifi,
                sigmaGnss,
                initPosStd,
                initHeadingStdRad,
                resampleRatio,
                sigmaRegPos,
                sigmaRegThetaRad
        );

        cfg.enableMapConstraints = true;
        cfg.enableAbsoluteObservationWeighting = true;
        cfg.minimumWeightFloor = 1e-12;

        // Keep recovery disabled by default in the cleaned version.
        cfg.enableRecoveryIfCollapsed = false;
        cfg.recoveryPositionStdMeters = 0.75;
        cfg.recoveryHeadingStdRad = Math.toRadians(8.0);

        cfg.debugLogging = true;
        return cfg;
    }

    private void reloadRuntimeSettings(boolean forceRecreateEngine) {
        Context context = sensorFusion.getContext();
        ParticleFilterConfig cfg = buildConfigFromPreferences(context);
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
        boolean stationaryLike = !lastMotionWasMeaningful;
        ParticleFilterEngine.AbsoluteObservation observation = buildObservation(stationaryLike);

        ParticleFilterEngine.StepResult result = engine.update(
                motionInput,
                observation,
                coordinateConverter
        );
        lastDiagnostics = result.diagnostics;

        latestRawPose = result.fusedPose;

        boolean strongAbsoluteObservation =
                observation != null && "map_match".equals(observation.source);

        KalmanPoseSmoother.SmoothedPose smoothedPose = kalmanPoseSmoother.update(
                latestRawPose.getX(),
                latestRawPose.getY(),
                latestRawPose.getHeadingRad(),
                latestRawPose.getConfidence(),
                motionInput.deltaForwardMeters,
                Math.abs(motionInput.deltaHeadingRad),
                strongAbsoluteObservation
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

        lastPoseShouldBeDrawn = lastMotionWasMeaningful;

        pushLatestPoseToOutputs();

        Log.d(TAG, String.format(Locale.US,
                "PF_OUTPUT lat=%.6f lng=%.6f x=%.3f y=%.3f floor=%d conf=%.3f",
                latestFusedPose.getLatLng().latitude,
                latestFusedPose.getLatLng().longitude,
                latestFusedPose.getX(),
                latestFusedPose.getY(),
                latestFusedPose.getFloor(),
                latestFusedPose.getConfidence()));

        debugger.logStep(
                "live_update",
                result.diagnostics.totalParticles,
                result.diagnostics.aliveParticles,
                result.diagnostics.wallRejectedCount,
                result.diagnostics.floorRejectedCount,
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

    private void pushLatestPoseToOutputs() {
        sensorFusion.setLatestRawFusedPose(latestRawPose);
        sensorFusion.setLatestFusedPose(latestFusedPose);

        if (host != null && latestFusedPose != null) {
            host.onFusedPoseUpdated(latestFusedPose);
        }
    }

    private void tryInitialiseFromCurrentSensors() {
        float[] gnss = sensorFusion.getGNSSLatitude(false);
        if (gnss == null || gnss.length < 2) {
            return;
        }

        if (Math.abs(gnss[0]) < 1e-6 && Math.abs(gnss[1]) < 1e-6) {
            return;
        }

        int floor = 0;

        if (indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) {
            floor = indoorMapManager.indexToLogicalFloor(indoorMapManager.getCurrentFloor());
        } else if (sensorFusion.getLatLngWifiPositioning() != null) {
            floor = sensorFusion.getWifiFloor();
        }

        double heading = sensorFusion.passOrientation();
        initialiseFromGnss(new LatLng(gnss[0], gnss[1]), floor, heading);
    }

    @NonNull
    private ParticleFilterEngine.MotionInput buildMotionInput(@NonNull float[] pdr,
                                                              long timestampMs) {

        double deltaForwardMeters = 0.0;

        if (lastPdrValues != null && lastPdrValues.length >= 2) {
            double dx = pdr[0] - lastPdrValues[0];
            double dy = pdr[1] - lastPdrValues[1];
            deltaForwardMeters = Math.sqrt(dx * dx + dy * dy);

            if (deltaForwardMeters < PF_MOTION_DEADBAND_METERS) {
                deltaForwardMeters = 0.0;
            }

            deltaForwardMeters *= PF_MOTION_SCALE;
            deltaForwardMeters = Math.min(deltaForwardMeters, PF_MAX_STEP_METERS);
        }

        double currentHeading = sensorFusion.passOrientation();
        double deltaHeadingRad = 0.0;

        if (latestFusedPose != null) {
            deltaHeadingRad = wrapAngleRad(currentHeading - latestFusedPose.getHeadingRad());
        }

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
                        latestFusedPose.getX(),
                        latestFusedPose.getY(),
                        latestFusedPose.getFloor());

                boolean nearStairs = constraintModel.isNearStairs(
                        latestFusedPose.getX(),
                        latestFusedPose.getY(),
                        latestFusedPose.getFloor());

                boolean nearLift = constraintModel.isNearLift(
                        latestFusedPose.getX(),
                        latestFusedPose.getY(),
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

        lastMotionWasMeaningful = deltaForwardMeters >= PF_DRAW_MIN_TRANSLATION_METERS || heightChanged;

        lastMotionSummary = String.format(Locale.US,
                "motion=ds%.2f dθ%.1f° dh%.2f %s hc=%s draw=%s",
                deltaForwardMeters,
                Math.toDegrees(deltaHeadingRad),
                deltaHeightMeters,
                connectorState,
                String.valueOf(heightChanged),
                String.valueOf(lastMotionWasMeaningful));

        Log.d(TAG, String.format(Locale.US,
                "PF_MOTION deltaS=%.3f deltaHeadingDeg=%.2f deltaH=%.2f heightChanged=%s %s draw=%s",
                deltaForwardMeters,
                Math.toDegrees(deltaHeadingRad),
                deltaHeightMeters,
                String.valueOf(heightChanged),
                connectorState,
                String.valueOf(lastMotionWasMeaningful)));

        return new ParticleFilterEngine.MotionInput(
                deltaForwardMeters,
                deltaHeadingRad,
                deltaHeightMeters,
                heightChanged,
                timestampMs
        );
    }

    /**
     * Builds absolute observations in local x/y.
     *
     * Priority:
     * 1) map-matched pose
     * 2) WiFi
     * 3) GNSS
     */
    @Nullable
    private ParticleFilterEngine.AbsoluteObservation buildObservation(boolean stationaryLike) {
        if (coordinateConverter == null) {
            lastObservationSummary = "obs:none";
            return null;
        }

        double beliefX = latestFusedPose != null ? latestFusedPose.getX() : 0.0;
        double beliefY = latestFusedPose != null ? latestFusedPose.getY() : 0.0;
        boolean haveBelief = latestFusedPose != null;

        double sigmaWifi = activeConfig != null ? activeConfig.observationSigmaWifiMeters : 2.5;
        double sigmaGnss = activeConfig != null ? activeConfig.observationSigmaGnssMeters : 5.0;
        boolean indoorActive = indoorMapManager != null && indoorMapManager.getIsIndoorMapSet();

        // 1) map-match
        if (latestMatchedPose != null && latestMatchedPose.getLatLng() != null) {
            LatLng latLng = latestMatchedPose.getLatLng();
            double[] local = coordinateConverter.latLngToLocal(latLng);

            Integer floor;
            if (indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) {
                floor = indoorMapManager.indexToLogicalFloor(latestMatchedPose.getFloor());
            } else {
                floor = latestMatchedPose.getFloor();
            }

            if (haveBelief) {
                double jumpMeters = Math.hypot(local[0] - beliefX, local[1] - beliefY);
                if (jumpMeters > MAP_MATCH_JUMP_GATE_METERS) {
                    lastObservationSummary = String.format(Locale.US,
                            "obs:map_match_reject_jump(%.1fm)", jumpMeters);
                    Log.d(TAG, lastObservationSummary);
                    return null;
                }
            }

            lastObservationSummary = String.format(Locale.US,
                    "obs:map_match floor=%s", String.valueOf(floor));

            return new ParticleFilterEngine.AbsoluteObservation(
                    local[0],
                    local[1],
                    floor,
                    null,
                    1.8,
                    Math.toRadians(15.0),
                    0.80,
                    "map_match"
            );
        }

        // 2) WiFi
        LatLng wifi = sensorFusion.getLatLngWifiPositioning();
        int wifiCount = sensorFusion.getWifiList() == null ? 0 : sensorFusion.getWifiList().size();

        if (wifi != null && !(Math.abs(wifi.latitude) < 1e-6 && Math.abs(wifi.longitude) < 1e-6)) {
            if (indoorActive && !stationaryLike) {
                lastObservationSummary = "obs:wifi_suppressed_moving";
                Log.d(TAG, lastObservationSummary);
                return null;
            }

            if (wifiCount < WIFI_MIN_AP_COUNT) {
                lastObservationSummary = "obs:wifi_reject_low_ap_count";
                Log.d(TAG, lastObservationSummary);
                return null;
            }

            double[] local = coordinateConverter.latLngToLocal(wifi);

            if (haveBelief) {
                double jumpMeters = Math.hypot(local[0] - beliefX, local[1] - beliefY);
                double gateMeters = indoorActive
                        ? WIFI_JUMP_GATE_INDOOR_METERS
                        : WIFI_JUMP_GATE_OUTDOOR_METERS;
                if (jumpMeters > gateMeters) {
                    lastObservationSummary = String.format(Locale.US,
                            "obs:wifi_reject_jump(%.1fm)", jumpMeters);
                    Log.d(TAG, lastObservationSummary);
                    return null;
                }
            }

            int wifiFloor = sensorFusion.getWifiFloor();

            double confidence;
            if (wifiCount >= 10) {
                confidence = indoorActive ? 0.05 : 0.20;
            } else {
                confidence = indoorActive ? 0.03 : 0.12;
            }

            double sigmaMeters = indoorActive ? sigmaWifi * 4.5 : sigmaWifi * 1.8;

            lastObservationSummary = String.format(Locale.US,
                    "obs:wifi ap=%d floor=%d", wifiCount, wifiFloor);

            return new ParticleFilterEngine.AbsoluteObservation(
                    local[0],
                    local[1],
                    wifiFloor,
                    null,
                    sigmaMeters,
                    Math.toRadians(45.0),
                    confidence,
                    "wifi"
            );
        }

        // 3) GNSS
        float[] gnss = sensorFusion.getGNSSLatitude(false);
        if (gnss != null && gnss.length >= 2
                && !(Math.abs(gnss[0]) < 1e-6 && Math.abs(gnss[1]) < 1e-6)) {

            if (indoorActive) {
                lastObservationSummary = "obs:gnss_disabled_indoors";
                Log.d(TAG, lastObservationSummary);
                return null;
            }

            LatLng gnssLatLng = new LatLng(gnss[0], gnss[1]);
            double[] local = coordinateConverter.latLngToLocal(gnssLatLng);

            if (haveBelief) {
                double jumpMeters = Math.hypot(local[0] - beliefX, local[1] - beliefY);
                if (jumpMeters > GNSS_JUMP_GATE_OUTDOOR_METERS) {
                    lastObservationSummary = String.format(Locale.US,
                            "obs:gnss_reject_jump(%.1fm)", jumpMeters);
                    Log.d(TAG, lastObservationSummary);
                    return null;
                }
            }

            lastObservationSummary = "obs:gnss";

            return new ParticleFilterEngine.AbsoluteObservation(
                    local[0],
                    local[1],
                    null,
                    null,
                    sigmaGnss * 1.8,
                    Math.toRadians(60.0),
                    0.08,
                    "gnss"
            );
        }

        lastObservationSummary = "obs:none";
        Log.d(TAG, "PF_OBS source=none");
        return null;
    }

    /**
     * Local x/y adapter around the existing geographic floor-shape / map utilities.
     *
     * The particle filter stays local.
     * Constraints convert local x/y to LatLng only when the map must be queried.
     */
    private class HybridConstraintModel implements ParticleFilterEngine.ConstraintModel {

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

        @Nullable
        private LatLng toLatLng(double x, double y) {
            if (coordinateConverter == null) {
                return null;
            }
            return coordinateConverter.localToLatLng(x, y);
        }

        boolean isNearStairs(double x, double y, int floor) {
            FloorplanApiClient.FloorShapes floorShapes = getFloorShapes(floor);
            LatLng point = toLatLng(x, y);
            if (floorShapes == null || point == null) {
                return false;
            }
            return MapGeometryUtils.isInsideIndoorType(point, floorShapes, "stairs")
                    || MapGeometryUtils.isNearStairs(point, floorShapes);
        }

        boolean isNearLift(double x, double y, int floor) {
            FloorplanApiClient.FloorShapes floorShapes = getFloorShapes(floor);
            LatLng point = toLatLng(x, y);
            if (floorShapes == null || point == null) {
                return false;
            }
            return MapGeometryUtils.isInsideIndoorType(point, floorShapes, "lift")
                    || MapGeometryUtils.isNearLift(point, floorShapes);
        }

        @NonNull
        String classifyNearbyConnector(double x, double y, int floor) {
            boolean stairs = isNearStairs(x, y, floor);
            boolean lift = isNearLift(x, y, floor);
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
        public boolean crossesWall(double oldX, double oldY,
                                   double newX, double newY,
                                   int floor) {
            if (indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
                return false;
            }

            LatLng oldPoint = toLatLng(oldX, oldY);
            LatLng newPoint = toLatLng(newX, newY);
            if (oldPoint == null || newPoint == null) {
                return false;
            }

            FloorplanApiClient.FloorShapes floorShapes = getFloorShapes(floor);
            if (floorShapes == null) {
                return false;
            }

            return MapGeometryUtils.crossesWall(oldPoint, newPoint, floorShapes);
        }

        @Override
        public boolean isFloorTransitionAllowed(double x, double y, int oldFloor, int newFloor) {
            if (oldFloor == newFloor) {
                return true;
            }

            if (indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
                return true;
            }

            boolean stairsSource = isNearStairs(x, y, oldFloor);
            boolean liftSource = isNearLift(x, y, oldFloor);
            boolean stairsTarget = isNearStairs(x, y, newFloor);
            boolean liftTarget = isNearLift(x, y, newFloor);

            return (stairsSource && stairsTarget)
                    || (liftSource && liftTarget)
                    || stairsSource
                    || liftSource
                    || stairsTarget
                    || liftTarget;
        }
    }

    private static double wrapAngleRad(double a) {
        while (a > Math.PI) a -= 2.0 * Math.PI;
        while (a < -Math.PI) a += 2.0 * Math.PI;
        return a;
    }
}
