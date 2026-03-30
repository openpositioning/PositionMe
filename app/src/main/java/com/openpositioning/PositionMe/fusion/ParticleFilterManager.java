package com.openpositioning.PositionMe.fusion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.mapmatching.CandidatePose;
import com.openpositioning.PositionMe.mapmatching.MapMatchingInput;
import com.openpositioning.PositionMe.mapmatching.MapMatchingResult;
import com.openpositioning.PositionMe.mapmatching.MapMatchingService;
import com.openpositioning.PositionMe.mapmatching.MotionDelta;
import com.openpositioning.PositionMe.mapmatching.VerticalMotionDetector;
import com.openpositioning.PositionMe.mapmatching.VerticalTransitionHint;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.List;

/**
 * High-level manager around the particle filter engine.
 *
 * <p>This class is responsible for:
 * - loading PF settings from SharedPreferences
 * - listening for user changes to those settings
 * - initialising the engine when enough information is available
 * - converting raw app data into PF prediction + observation inputs
 * - applying discrete map matching after the raw PF estimate
 * - storing the latest fused pose estimate
 *
 * <p>Important design choice:
 * the PF remains motion-driven. Map matching is applied as a discrete validity
 * layer to stop impossible behaviour such as wall penetration or invalid floor
 * changes. There is no continuous map-based trajectory shaping here.
 */
public class ParticleFilterManager {

    private static final String TAG = "ParticleFilterManager";

    private static final double POSITION_EMA_ALPHA = 0.10;
    private static final double HEADING_EMA_ALPHA = 0.08;
    private static final double CONFIDENCE_EMA_ALPHA = 0.20;
    private static final double WIFI_GATE_METERS = 5.0;
    private static final double GNSS_GATE_METERS = 25.0;
    private static final double WIFI_OBS_EMA_ALPHA = 0.10;
    private static final double WALL_CROSS_PENALTY = 0;

    /** Main app sensor/data source. */
    private final SensorFusion sensorFusion;

    /** Application context used for preferences. */
    private final Context appContext;

    /** SharedPreferences backing live-tunable PF settings. */
    private final SharedPreferences prefs;

    /** Converts between global LatLng and local x/y coordinates. */
    private CoordinateConverter coordinateConverter;

    /** The actual PF engine instance. */
    private ParticleFilterEngine particleFilterEngine;

    /** Latest estimated fused pose. */
    private FusedPose latestFusedPose;

    /** Whether PF is enabled for the current recording session. */
    private boolean enabled = false;

    // -------------------------
    // PDR incremental tracking
    // -------------------------

    private boolean firstPdrSample = true;
    private double lastPdrX = 0.0;
    private double lastPdrY = 0.0;
    private double lastHeading = 0.0;

    // -------------------------
    // Live PF settings
    // -------------------------

    private ParticleFilterConfig currentConfig;
    private boolean pfConfigDirty = false;

    // -------------------------
    // Observation / output smoothing
    // -------------------------

    private boolean wifiObsEmaInitialised = false;
    private double wifiObsEmaX = 0.0;
    private double wifiObsEmaY = 0.0;

    private boolean poseEmaInitialised = false;
    private double emaX = 0.0;
    private double emaY = 0.0;
    private double emaTheta = 0.0;
    private float emaConfidence = 0f;

    // -------------------------
    // Map-constraint state
    // -------------------------

    private final MapMatchingService mapMatchingService = new MapMatchingService();
    private final VerticalMotionDetector verticalMotionDetector = new VerticalMotionDetector();

    @Nullable
    private CandidatePose lastMatchedPose = null;

    /** Authoritative floor owner for live PF mode. */
    private int activePfFloor = 0;

    public ParticleFilterManager(SensorFusion sensorFusion, Context context) {
        this.sensorFusion = sensorFusion;
        this.appContext = context.getApplicationContext();
        this.prefs = PreferenceManager.getDefaultSharedPreferences(appContext);

        this.currentConfig = loadPfConfig();
        prefs.registerOnSharedPreferenceChangeListener(pfListener);
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener pfListener =
            (sharedPreferences, key) -> {
                if (key == null) return;

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

    public void destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(pfListener);
    }

    private ParticleFilterConfig loadPfConfig() {
        int particleCount = Integer.parseInt(prefs.getString("pf_particle_count", "300"));
        double sigmaStep = Double.parseDouble(prefs.getString("pf_sigma_step", "0.15"));
        double sigmaThetaDeg = Double.parseDouble(prefs.getString("pf_sigma_theta_deg", "6"));
        double sigmaWifi = Double.parseDouble(prefs.getString("pf_sigma_wifi", "2.5"));
        double sigmaGnss = Double.parseDouble(prefs.getString("pf_sigma_gnss", "5.0"));
        double initPosStd = Double.parseDouble(prefs.getString("pf_init_pos_std", "1.0"));
        double initHeadingDeg = Double.parseDouble(prefs.getString("pf_init_heading_deg", "10"));
        double resampleRatio = Double.parseDouble(prefs.getString("pf_resample_ratio", "0.5"));
        double sigmaRegPos = Double.parseDouble(prefs.getString("pf_sigma_reg_pos", "0.03"));
        double sigmaRegThetaDeg = Double.parseDouble(prefs.getString("pf_sigma_reg_theta_deg", "1.0"));

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
     * Feeds barometer-derived vertical context into the live detector.
     *
     * <p>Call this from the pressure sensor path so lift/stairs evidence can
     * accumulate even before the next step-triggered PF update.
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
     * Advances the particle filter by one logical movement update.
     */
    public void step() {
        if (!enabled) {
            return;
        }

        if (pfConfigDirty) {
            Log.d(TAG, "Applying new PF config -> resetting filter");
            reset();
            pfConfigDirty = false;
        }

        // Refresh vertical context from the latest live state as well.
        onVerticalContextSample(
                SystemClock.uptimeMillis(),
                sensorFusion.getElevation(),
                sensorFusion.getElevator()
        );

        initialiseIfNeeded();
        if (particleFilterEngine == null || coordinateConverter == null) {
            return;
        }

        double deltaS = extractPdrDeltaDistance();
        double currentHeading = wrapAngle(sensorFusion.getSelectedHeadingRad());
        double deltaTheta = wrapAngle(currentHeading - lastHeading);
        lastHeading = currentHeading;

        if (deltaS >= 0.01) {
            particleFilterEngine.predict(deltaS, deltaTheta);
            Log.d(TAG, "PF predict: deltaS=" + deltaS + ", deltaTheta=" + deltaTheta);
        } else {
            Log.d(TAG, "PF stationary: skip predict, keep update");
        }

        ParticleFilterObservation observation = buildObservation();
        ParticleFilterEngine.ConstraintContext constraintContext = buildConstraintContext();
        particleFilterEngine.update(observation, constraintContext);

        FusedPose rawPose = particleFilterEngine.estimate(coordinateConverter);
        FusedPose correctedPose = applyDiscreteMapMatching(rawPose, currentHeading, deltaS);
       // latestFusedPose = smoothPose(correctedPose != null ? correctedPose : rawPose);
        latestFusedPose = correctedPose;
    }

    /**
     * Initialises the PF only when a reliable autonomous absolute anchor is available.
     */
    public void initialiseIfNeeded() {
        if (particleFilterEngine != null && particleFilterEngine.isInitialised()) {
            return;
        }

        LatLng initialLatLng = resolveAutonomousInitialLatLng();
        if (initialLatLng == null) {
            Log.d(TAG, "PF init skipped: waiting for autonomous WiFi/GNSS initial position.");
            return;
        }

        coordinateConverter = new CoordinateConverter(
                initialLatLng.latitude,
                initialLatLng.longitude
        );

        Integer manualFloor = sensorFusion.getManualStartAnchorFloor();
        if (manualFloor != null) {
            activePfFloor = sanitiseFloorIndex(manualFloor);
        } else {
            int wifiFloor = sensorFusion.getWifiFloor();
            activePfFloor = sanitiseFloorIndex(wifiFloor);
        }

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
        FusedPose initialCorrectedPose = applyDiscreteMapMatching(initialRawPose, initialHeading, 0.0);
        //latestFusedPose = smoothPose(initialCorrectedPose != null ? initialCorrectedPose : initialRawPose);
        latestFusedPose = initialCorrectedPose;
        lastHeading = initialHeading;

        if (latestFusedPose != null) {
            lastMatchedPose = new CandidatePose(
                    latestFusedPose.getLatLng(),
                    latestFusedPose.getFloor(),
                    System.currentTimeMillis(),
                    "pf_init",
                    latestFusedPose.getHeadingRad()
            );
        }

        Log.d(TAG,
                "PF initialised from autonomous fix"
                        + " | particles=" + currentConfig.particleCount
                        + ", sigmaStep=" + currentConfig.sigmaStep
                        + ", sigmaThetaDeg=" + Math.toDegrees(currentConfig.sigmaThetaRad)
                        + ", sigmaWifi=" + currentConfig.sigmaWifi
                        + ", sigmaGnss=" + currentConfig.sigmaGnss
                        + ", activeFloor=" + activePfFloor
                        + ", lat=" + initialLatLng.latitude
                        + ", lon=" + initialLatLng.longitude);
    }

    @Nullable
    private LatLng resolveAutonomousInitialLatLng() {
        LatLng manualStart = sensorFusion.getManualStartAnchorLatLng();
        if (isValidLatLng(manualStart)) {
            return manualStart;
        }

        float[] storedStart = sensorFusion.getGNSSLatitude(true);
        if (isValidLatLon(storedStart)) {
            return new LatLng(storedStart[0], storedStart[1]);
        }

        LatLng wifiLatLng = sensorFusion.getLatLngWifiPositioning();
        if (isValidLatLng(wifiLatLng)) {
            return wifiLatLng;
        }

        float[] gnssLatLon = sensorFusion.getGNSSLatitude(false);
        if (isValidLatLon(gnssLatLon)) {
            return new LatLng(gnssLatLon[0], gnssLatLon[1]);
        }

        return null;
    }

    public void reset() {
        coordinateConverter = null;
        particleFilterEngine = null;
        latestFusedPose = null;

        firstPdrSample = true;
        lastPdrX = 0.0;
        lastPdrY = 0.0;
        lastHeading = 0.0;

        poseEmaInitialised = false;
        emaX = 0.0;
        emaY = 0.0;
        emaTheta = 0.0;
        emaConfidence = 0f;

        wifiObsEmaInitialised = false;
        wifiObsEmaX = 0.0;
        wifiObsEmaY = 0.0;

        activePfFloor = 0;
        lastMatchedPose = null;
        verticalMotionDetector.reset();
        mapMatchingService.resetTransientState();
    }

    public FusedPose getLatestFusedPose() {
        return enabled ? latestFusedPose : null;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        Log.d(TAG, "PF manager = " + (enabled ? "ENABLED" : "DISABLED"));

        if (!enabled) {
            reset();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    private ParticleFilterObservation buildObservation() {
        Double wifiX = null;
        Double wifiY = null;
        Integer wifiFloor = null;

        LatLng wifiLatLng = sensorFusion.getLatLngWifiPositioning();
        if (isValidLatLng(wifiLatLng) && coordinateConverter != null) {
            double[] local = coordinateConverter.latLngToLocal(wifiLatLng);

            boolean acceptWifi = true;
            if (latestFusedPose != null) {
                double dx = local[0] - latestFusedPose.getXMeters();
                double dy = local[1] - latestFusedPose.getYMeters();
                double dist = Math.hypot(dx, dy);

                if (dist > WIFI_GATE_METERS) {
                    acceptWifi = false;
                    Log.d(TAG, "Rejecting WiFi update by gate | dist=" + dist + ", gate=" + WIFI_GATE_METERS);
                }
            }

            if (acceptWifi) {
                double[] smoothWifi = smoothWifiObservation(local[0], local[1]);
                wifiX = smoothWifi[0];
                wifiY = smoothWifi[1];
                wifiFloor = sensorFusion.getWifiFloor();
            }
        }

        Double gnssX = null;
        Double gnssY = null;

        // Disable GNSS absolute correction when an indoor building/map context is active.
        if (!isIndoorContextActive()) {
            float[] gnssLatLon = sensorFusion.getGNSSLatitude(false);
            if (isValidLatLon(gnssLatLon) && coordinateConverter != null) {
                LatLng gnssLatLng = new LatLng(gnssLatLon[0], gnssLatLon[1]);
                double[] local = coordinateConverter.latLngToLocal(gnssLatLng);

                boolean acceptGnss = true;
                if (latestFusedPose != null) {
                    double dx = local[0] - latestFusedPose.getXMeters();
                    double dy = local[1] - latestFusedPose.getYMeters();
                    double dist = Math.hypot(dx, dy);

                    if (dist > GNSS_GATE_METERS) {
                        acceptGnss = false;
                        Log.d(TAG, "Rejecting GNSS update by gate | dist=" + dist + ", gate=" + GNSS_GATE_METERS);
                    }
                }

                if (acceptGnss) {
                    gnssX = local[0];
                    gnssY = local[1];
                }
            }
        } else {
            Log.d(TAG, "GNSS disabled for PF because indoor context is active.");
        }

        return new ParticleFilterObservation(wifiX, wifiY, wifiFloor, gnssX, gnssY);
    }

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
    private FusedPose applyDiscreteMapMatching(@Nullable FusedPose rawPose,
                                               double currentHeadingRad,
                                               double stepDistanceMeters) {
        if (rawPose == null || coordinateConverter == null || rawPose.getLatLng() == null) {
            return rawPose;
        }

        FloorplanApiClient.FloorShapes sourceFloorShapes = getFloorShapesForFloor(activePfFloor);
        VerticalTransitionHint verticalHint = verticalMotionDetector.buildHint();
        int requestedFloor = resolveRequestedFloor(verticalHint);
        FloorplanApiClient.FloorShapes targetFloorShapes = getFloorShapesForFloor(requestedFloor);

        // No usable map: keep raw PF output and preserve current PF floor.
        if (sourceFloorShapes == null && targetFloorShapes == null) {
            LatLng rawLatLng = rawPose.getLatLng();
            lastMatchedPose = new CandidatePose(
                    rawLatLng,
                    activePfFloor,
                    System.currentTimeMillis(),
                    "pf_raw_nomap",
                    rawPose.getHeadingRad()
            );
            return new FusedPose(
                    rawPose.getXMeters(),
                    rawPose.getYMeters(),
                    rawPose.getHeadingRad(),
                    activePfFloor,
                    rawLatLng,
                    rawPose.getConfidence()
            );
        }

        CandidatePose currentCandidatePose = new CandidatePose(
                rawPose.getLatLng(),
                requestedFloor,
                System.currentTimeMillis(),
                "pf_raw",
                currentHeadingRad
        );

        MotionDelta motionDelta = new MotionDelta(
                rawPose.getXMeters() - (lastMatchedPose == null || lastMatchedPose.getLatLng() == null
                        ? rawPose.getXMeters()
                        : coordinateConverter.latLngToLocal(lastMatchedPose.getLatLng())[0]),
                rawPose.getYMeters() - (lastMatchedPose == null || lastMatchedPose.getLatLng() == null
                        ? rawPose.getYMeters()
                        : coordinateConverter.latLngToLocal(lastMatchedPose.getLatLng())[1]),
                stepDistanceMeters,
                Math.toDegrees(currentHeadingRad)
        );

        MapMatchingInput input = new MapMatchingInput(
                lastMatchedPose,
                currentCandidatePose,
                motionDelta,
                verticalHint,
                sourceFloorShapes,
                targetFloorShapes,
                sensorFusion.getSelectedBuildingId()
        );

        MapMatchingResult result = mapMatchingService.match(input);

        int candidateCorrectedFloor = sanitiseFloorIndex(result.getCorrectedFloor());
        boolean floorTransitionAccepted =
                result.isFloorChangeAllowed() && candidateCorrectedFloor != activePfFloor;

        LatLng rawLatLng = rawPose.getLatLng();
        LatLng candidateMatchedLatLng = result.getCorrectedLatLng() != null
                ? result.getCorrectedLatLng()
                : rawLatLng;

        // Conservative jump guard:
        // - same-floor corrections must stay small
        // - near stairs/lifts can be slightly looser
        // - true accepted floor transitions are allowed
        final double maxSameFloorCorrectionMeters = 0.8;
        final double maxConnectorCorrectionMeters = 1.5;

        double allowedCorrectionMeters =
                (result.isNearStairs() || result.isNearLift())
                        ? maxConnectorCorrectionMeters
                        : maxSameFloorCorrectionMeters;

        double correctionMeters = distanceMeters(rawLatLng, candidateMatchedLatLng);

        LatLng correctedLatLng;
        int correctedFloor = activePfFloor;

        if (result.isCrossedWall()) {
            // Wall event: do not snap to a far corrected point.
            // Prefer the last valid matched pose if we have one.
            if (lastMatchedPose != null && lastMatchedPose.getLatLng() != null) {
                correctedLatLng = lastMatchedPose.getLatLng();
            } else {
                correctedLatLng = rawLatLng;
            }
            correctedFloor = activePfFloor;

            Log.d(TAG, "Map correction guarded (wall cross) -> using last valid/raw pose"
                    + " | correction=" + result.getCorrectionType()
                    + ", reason=" + result.getDebugReason());
        } else if (result.getCorrectionType()
                == com.openpositioning.PositionMe.mapmatching.CorrectionType.INVALID_FLOOR_CHANGE) {
            // Invalid floor change: keep previous valid floor/pose.
            if (lastMatchedPose != null && lastMatchedPose.getLatLng() != null) {
                correctedLatLng = lastMatchedPose.getLatLng();
            } else {
                correctedLatLng = rawLatLng;
            }
            correctedFloor = activePfFloor;

            Log.d(TAG, "Map correction guarded (invalid floor change) -> using last valid/raw pose"
                    + " | reason=" + result.getDebugReason());
        } else if (floorTransitionAccepted) {
            // Real validated floor transition: allow map-matching correction.
            correctedLatLng = candidateMatchedLatLng;
            correctedFloor = candidateCorrectedFloor;

            Log.d(TAG, "Map correction accepted for floor transition"
                    + " | newFloor=" + correctedFloor
                    + ", correctionMeters=" + correctionMeters
                    + ", reason=" + result.getDebugReason());
        } else if (correctionMeters <= allowedCorrectionMeters) {
            // Small same-floor correction is okay.
            correctedLatLng = candidateMatchedLatLng;
            correctedFloor = activePfFloor;
        } else {
            // Too large for a same-floor correction: ignore it.
            correctedLatLng = rawLatLng;
            correctedFloor = activePfFloor;

            Log.d(TAG, "Rejected large same-floor map correction"
                    + " | correctionMeters=" + correctionMeters
                    + ", allowed=" + allowedCorrectionMeters
                    + ", nearStairs=" + result.isNearStairs()
                    + ", nearLift=" + result.isNearLift()
                    + ", correction=" + result.getCorrectionType()
                    + ", reason=" + result.getDebugReason());
        }

        if (correctedFloor != activePfFloor && particleFilterEngine != null) {
            activePfFloor = correctedFloor;
            particleFilterEngine.setAllParticlesFloor(activePfFloor);
            Log.d(TAG, "PF floor owner changed to " + activePfFloor
                    + " | reason=" + result.getDebugReason());
        }

        double[] correctedLocal = coordinateConverter.latLngToLocal(correctedLatLng);
        FusedPose correctedPose = new FusedPose(
                correctedLocal[0],
                correctedLocal[1],
                rawPose.getHeadingRad(),
                activePfFloor,
                correctedLatLng,
                rawPose.getConfidence()
        );

        lastMatchedPose = new CandidatePose(
                correctedLatLng,
                activePfFloor,
                System.currentTimeMillis(),
                "pf_matched",
                correctedPose.getHeadingRad()
        );

        Log.d(TAG,
                "Map match"
                        + " | floor=" + activePfFloor
                        + ", crossedWall=" + result.isCrossedWall()
                        + ", nearStairs=" + result.isNearStairs()
                        + ", nearLift=" + result.isNearLift()
                        + ", floorAllowed=" + result.isFloorChangeAllowed()
                        + ", correction=" + result.getCorrectionType()
                        + ", correctionMeters=" + correctionMeters
                        + ", reason=" + result.getDebugReason());

        return correctedPose;
    }

    private int resolveRequestedFloor(@Nullable VerticalTransitionHint verticalHint) {
        int requestedFloor = activePfFloor;

        if (verticalHint == null || !verticalHint.isHeightChanged()) {
            return requestedFloor;
        }

        int wifiFloor = sensorFusion.getWifiFloor();
        if (isFloorIndexAvailable(wifiFloor) && wifiFloor != activePfFloor) {
            return wifiFloor;
        }

        if (verticalHint.getDeltaHeight() > 0.0) {
            requestedFloor = activePfFloor + 1;
        } else if (verticalHint.getDeltaHeight() < 0.0) {
            requestedFloor = activePfFloor - 1;
        }

        return sanitiseFloorIndex(requestedFloor);
    }

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

    @Nullable
    private FloorplanApiClient.BuildingInfo getSelectedBuildingInfo() {
        String buildingId = sensorFusion.getSelectedBuildingId();
        if (buildingId == null || buildingId.isEmpty()) {
            return null;
        }
        return sensorFusion.getFloorplanBuilding(buildingId);
    }

    private boolean isFloorIndexAvailable(int floorIndex) {
        FloorplanApiClient.BuildingInfo building = getSelectedBuildingInfo();
        if (building == null || building.getFloorShapesList() == null) {
            return floorIndex >= 0;
        }
        return floorIndex >= 0 && floorIndex < building.getFloorShapesList().size();
    }

    private int sanitiseFloorIndex(int floorIndex) {
        FloorplanApiClient.BuildingInfo building = getSelectedBuildingInfo();
        if (building == null || building.getFloorShapesList() == null || building.getFloorShapesList().isEmpty()) {
            return Math.max(0, floorIndex);
        }
        return Math.max(0, Math.min(floorIndex, building.getFloorShapesList().size() - 1));
    }

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

    private FusedPose smoothPose(FusedPose rawPose) {
        if (rawPose == null || coordinateConverter == null) {
            return rawPose;
        }

        if (!poseEmaInitialised) {
            poseEmaInitialised = true;
            emaX = rawPose.getXMeters();
            emaY = rawPose.getYMeters();
            emaTheta = rawPose.getHeadingRad();
            emaConfidence = rawPose.getConfidence();
            return rawPose;
        }

        emaX = ema(emaX, rawPose.getXMeters(), POSITION_EMA_ALPHA);
        emaY = ema(emaY, rawPose.getYMeters(), POSITION_EMA_ALPHA);
        emaTheta = emaAngle(emaTheta, rawPose.getHeadingRad(), HEADING_EMA_ALPHA);
        emaConfidence = (float) ema(emaConfidence, rawPose.getConfidence(), CONFIDENCE_EMA_ALPHA);

        LatLng smoothedLatLng = coordinateConverter.localToLatLng(emaX, emaY);

        return new FusedPose(
                emaX,
                emaY,
                emaTheta,
                rawPose.getFloor(),
                smoothedLatLng,
                emaConfidence
        );
    }

    private double ema(double previous, double current, double alpha) {
        return previous + alpha * (current - previous);
    }

    private double emaAngle(double previous, double current, double alpha) {
        return wrapAngle(previous + alpha * wrapAngle(current - previous));
    }

    private boolean isValidLatLng(LatLng latLng) {
        if (latLng == null) {
            return false;
        }
        return !(Math.abs(latLng.latitude) < 1e-6 && Math.abs(latLng.longitude) < 1e-6);
    }

    private boolean isValidLatLon(float[] latLon) {
        if (latLon == null || latLon.length < 2) {
            return false;
        }
        return !(Math.abs(latLon[0]) < 1e-6 && Math.abs(latLon[1]) < 1e-6);
    }

    private boolean isIndoorContextActive() {
        String buildingId = sensorFusion.getSelectedBuildingId();
        return buildingId != null
                && !buildingId.isEmpty()
                && sensorFusion.getFloorplanBuilding(buildingId) != null;
    }

    private double wrapAngle(double angle) {
        while (angle > Math.PI) angle -= 2.0 * Math.PI;
        while (angle < -Math.PI) angle += 2.0 * Math.PI;
        return angle;
    }

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

    public String getLiveDebugSummary() {
        if (!enabled) {
            return "Particles: PF disabled";
        }

        if (particleFilterEngine == null) {
            return "Particles: waiting init";
        }

        FusedPose pose = getLatestFusedPose();
        int alive = particleFilterEngine.getAliveParticleCount();
        int dead = particleFilterEngine.getDeadParticleCount();
        int total = particleFilterEngine.getParticleCount();

        int pfFloor = pose != null ? pose.getFloor() : activePfFloor;

        return String.format(
                java.util.Locale.US,
                "Particles: %d | alive: %d | dead: %d\nPF floor: %d",
                total,
                alive,
                dead,
                pfFloor
        );
    }
}
