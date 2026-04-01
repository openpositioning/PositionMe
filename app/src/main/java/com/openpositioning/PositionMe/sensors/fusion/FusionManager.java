package com.openpositioning.PositionMe.sensors.fusion;

import static com.openpositioning.PositionMe.BuildConstants.DEBUG;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.IndoorMapManager;

import java.util.List;

/**
 * Orchestrates sensor fusion by connecting the {@link ParticleFilter} with
 * incoming PDR, WiFi, and GNSS observations.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Auto-initialises the filter from the first reliable position fix
 *       (WiFi preferred, GNSS fallback) — no user selection required.</li>
 *   <li>Feeds PDR steps as the prediction model.</li>
 *   <li>Feeds WiFi / GNSS fixes as observation updates.</li>
 *   <li>Exposes the fused position for display and recording.</li>
 * </ul>
 */
public class FusionManager {

    private static final String TAG = "FusionManager";

    /** Number of particles — balance between accuracy and phone performance. */
    private static final int NUM_PARTICLES = 500;

    /** Initial Gaussian spread (metres) when seeding particles. */
    private static final double INIT_SPREAD = 10.0;

    // Core components
    private final ParticleFilter particleFilter;
    private final CoordinateTransform coordTransform;
    private final MapConstraint mapConstraint;
    private CalibrationManager calibrationManager; // for reference-point heading correction

    // Pre-allocated arrays for wall constraint checking (avoid GC per step)
    private final double[] oldParticleX = new double[NUM_PARTICLES];
    private final double[] oldParticleY = new double[NUM_PARTICLES];

    // State tracking to avoid duplicate observations
    private LatLng lastWifiPosition;
    private LatLng lastGnssPosition;
    private int lastWifiFloor = -1;

    // WiFi initial floor seeding: set once from first valid WiFi response
    private boolean wifiFloorSeeded = false;

    // Fused output (volatile for cross-thread visibility)
    private volatile double fusedLat;
    private volatile double fusedLng;
    private volatile int fusedFloor;
    private volatile double fusedUncertainty;
    private volatile boolean active = false;

    // PDR step counter for logging
    private int pdrStepIndex = 0;

    // Floor transition state machine
    private int lastReportedFloor = -1;
    private int floorCandidate = -1;
    private long floorCandidateStartMs = 0;
    private static final long FLOOR_CONFIRM_MS = 1000; // must hold for 1s

    // ---- Gate state machine (adaptive re-acquisition) ---------------------
    private enum GateMode { LOCKED, UNLOCKED }
    enum ObservationLevel { STRONG, MEDIUM, WEAK, INVALID }

    private GateMode gateMode = GateMode.LOCKED;
    private int stepsSinceLastCorrection = 0;

    private static final int UNLOCK_STEP_THRESHOLD = 15;
    private static final double UNLOCK_UNCERTAINTY_THRESHOLD = 15.0;
    private static final double LOCKED_GATE = 15.0;
    private static final double LOCKED_GATE_INIT = 40.0;
    /** Distance (m) beyond which UNLOCKED mode re-seeds particles instead of soft update. */
    private static final double RESEED_DISTANCE = 20.0;
    /** Indoor GNSS sigma — much weaker influence than WiFi/CAL_DB. */
    private static final double GNSS_INDOOR_SIGMA = 50.0;
    /** Warmup period (ms) after init — suppress observations to prevent stale fix jumps. */
    private static final long WARMUP_MS = 2000;
    private long initTimeMs = 0;

    // Initial position calibration — accumulate fixes during 10s window
    private boolean calibrationMode = false;
    private double calSumLat = 0, calSumLng = 0;
    private double calTotalWeight = 0;
    private int calFixCount = 0;
    private static final double CAL_WEIGHT_CALDB = 3.0;  // highest trust
    private static final double CAL_WEIGHT_WIFI  = 1.0;
    private static final double CAL_WEIGHT_GNSS  = 1.0;

    // Heading error estimation (tutor-recommended)
    private double headingBias = 0;           // estimated heading error in radians
    private double prevObsX = 0, prevObsY = 0;
    private boolean hasPrevObs = false;
    // Accumulate pure PDR displacement between WiFi observations
    private double pdrAccumDx = 0, pdrAccumDy = 0;
    private static final double HEADING_BIAS_ALPHA = 0.10; // learning rate
    private static final double HEADING_EARLY_CORRECTION = Math.toRadians(5); // max 5° per step in early phase
    private static final int HEADING_EARLY_STEPS = 15; // first 15 steps use early correction
    private static final double HEADING_EARLY_MAX_DIST = 20.0; // only use obs within 20m
    private static final double HEADING_EARLY_FOV = Math.toRadians(120) / 2; // ±60° = 120° forward

    // Stationary detection — freeze position when not walking
    private long lastPdrStepTimeMs = 0;
    /** No PDR step for this long → consider user stationary, reject all corrections. */
    private static final long STATIONARY_TIMEOUT_MS = 100;

    // ---- Wall-collision heading search mode ---------------------------------
    // When most particles hit walls for consecutive steps, the heading is likely
    // wrong. Temporarily boost heading noise so particles fan out and "search"
    // for the correct direction. Walls naturally select survivors.
    private static final double SEARCH_COLLISION_THRESHOLD = 0.70; // 70% particles hit wall
    private static final int    SEARCH_TRIGGER_STEPS = 3;          // consecutive high-collision steps
    private static final double SEARCH_HEADING_STD_INIT = Math.toRadians(35); // initial search spread
    private static final double SEARCH_HEADING_STD_DECAY = 0.85;  // multiply each step
    private static final double SEARCH_HEADING_STD_MIN = Math.toRadians(8); // normal value

    private int   consecutiveHighCollisionSteps = 0;
    private double currentHeadingStd = SEARCH_HEADING_STD_MIN; // active heading noise
    private boolean inSearchMode = false;

    /** Creates a new FusionManager with default particle count and components. */
    public FusionManager() {
        this.particleFilter = new ParticleFilter(NUM_PARTICLES);
        this.coordTransform = new CoordinateTransform();
        this.mapConstraint = new MapConstraint();
    }

    /** Inject CalibrationManager for reference-point heading correction. */
    public void setCalibrationManager(CalibrationManager cm) {
        this.calibrationManager = cm;
        if (DEBUG) Log.i(TAG, "CalibrationManager set (" + (cm != null ? cm.getRecordCount() + " records" : "null") + ")");
    }

    /**
     * Enters calibration mode: incoming WiFi/GNSS/CalDB fixes are accumulated
     * (weighted) instead of initializing the particle filter.
     */
    public void startCalibrationMode() {
        calibrationMode = true;
        calSumLat = 0; calSumLng = 0;
        calTotalWeight = 0; calFixCount = 0;
        active = false; // prevent normal fusion during calibration
        if (DEBUG) Log.i(TAG, "[CalibrationMode] START — accumulating position fixes");
    }

    /** Adds a weighted position fix during calibration. */
    public void addCalibrationFix(double lat, double lng, double weight, String source) {
        if (!calibrationMode) return;
        calSumLat += lat * weight;
        calSumLng += lng * weight;
        calTotalWeight += weight;
        calFixCount++;
        if (DEBUG) Log.i(TAG, String.format("[CalibrationMode] fix #%d src=%s (%.6f,%.6f) w=%.1f totalW=%.1f",
                calFixCount, source, lat, lng, weight, calTotalWeight));
    }

    /**
     * Ends calibration mode: computes weighted average position and
     * initializes the particle filter at that location.
     * @param fallbackLat fallback latitude if no fixes were collected
     * @param fallbackLng fallback longitude if no fixes were collected
     * @return the final calibrated LatLng
     */
    public LatLng finishCalibrationMode(double fallbackLat, double fallbackLng) {
        calibrationMode = false;
        double lat, lng;
        if (calTotalWeight > 0) {
            lat = calSumLat / calTotalWeight;
            lng = calSumLng / calTotalWeight;
            if (DEBUG) Log.i(TAG, String.format("[CalibrationMode] FINISH %d fixes → (%.6f,%.6f)",
                    calFixCount, lat, lng));
        } else {
            lat = fallbackLat;
            lng = fallbackLng;
            if (DEBUG) Log.w(TAG, "[CalibrationMode] FINISH no fixes, using fallback");
        }

        // Initialize particle filter at calibrated position
        if (!coordTransform.isInitialized()) {
            coordTransform.setOrigin(lat, lng);
        }
        double[] en = coordTransform.toEastNorth(lat, lng);
        particleFilter.initialize(en[0], en[1], fusedFloor, INIT_SPREAD);
        fusedLat = lat;
        fusedLng = lng;
        active = true;
        initTimeMs = System.currentTimeMillis();

        if (DEBUG) Log.i(TAG, String.format("[CalibrationMode] PF initialized at (%.6f,%.6f) ENU=(%.2f,%.2f)",
                lat, lng, en[0], en[1]));
        return new LatLng(lat, lng);
    }

    /** Returns {@code true} if currently in calibration mode. */
    public boolean isInCalibrationMode() { return calibrationMode; }

    private IndoorMapManager indoorMapManager;
    private static final double STAIRS_STEP_FACTOR = 0.5;

    /** Inject IndoorMapManager for stairs/lift proximity detection. */
    public void setIndoorMapManager(IndoorMapManager mgr) {
        this.indoorMapManager = mgr;
    }

    /**
     * Loads wall geometry for the specified building into the map constraint.
     * Includes both interior walls (from floor shapes) and the building outline
     * (exterior boundary, applied to all floors).
     * Must be called after {@link CoordinateTransform} origin is set.
     *
     * @param floorShapesList per-floor shape data from FloorplanApiClient
     * @param outlinePolygon  building boundary polygon (may be null)
     */
    public void loadMapConstraints(
            List<FloorplanApiClient.FloorShapes> floorShapesList,
            List<LatLng> outlinePolygon) {
        if (coordTransform.isInitialized()) {
            mapConstraint.initialize(floorShapesList, coordTransform);
            mapConstraint.setOutlineConstraint(outlinePolygon, coordTransform);
        } else {
            if (DEBUG) Log.w(TAG, "Cannot load map constraints: coordTransform not initialised");
        }
    }

    // ---- initialisation ------------------------------------------------------

    /**
     * Initialises the fusion from the user-selected start location.
     * Called at the start of recording, before GNSS/WiFi fixes arrive.
     * Uses a wider spread since the user tap may not be pixel-perfect.
     */
    public void initializeFromStartLocation(double lat, double lng) {
        if (DEBUG) Log.i(TAG, String.format("Initialising from user start location (%.6f, %.6f)", lat, lng));
        // Force origin to user-selected start point (overwrite any earlier GNSS-set origin)
        coordTransform.setOrigin(lat, lng);
        tryInitialize(lat, lng, 0);
    }

    /**
     * Attempts to initialise the particle filter from a position fix.
     * Called automatically by {@link #onWifiPosition} or {@link #onGnssPosition}
     * whichever arrives first.
     */
    private void tryInitialize(double lat, double lng, int floor) {
        if (active) return;

        // During calibration mode, accumulate fixes instead of initializing
        if (calibrationMode) {
            // Still set origin from first fix so coordTransform is ready
            if (!coordTransform.isInitialized()) {
                coordTransform.setOrigin(lat, lng);
            }
            return; // actual position will be set in finishCalibrationMode()
        }

        // Set coordinate origin at the first fix
        if (!coordTransform.isInitialized()) {
            coordTransform.setOrigin(lat, lng);
        }

        double[] en = coordTransform.toEastNorth(lat, lng);
        particleFilter.initialize(en[0], en[1], floor, INIT_SPREAD);

        fusedLat = lat;
        fusedLng = lng;
        fusedFloor = floor;
        active = true;
        initTimeMs = System.currentTimeMillis();

        if (DEBUG) {
            // [Origin] diagnostic
            Log.i(TAG, String.format("[Origin] originLat=%.8f originLng=%.8f", lat, lng));

            // [RoundTrip] diagnostic
            double[] rt = coordTransform.toLatLng(0, 0);
            double rtError = Math.sqrt(Math.pow((rt[0] - lat) * 111132.92, 2) +
                    Math.pow((rt[1] - lng) * 111132.92 * Math.cos(Math.toRadians(lat)), 2));
            Log.i(TAG, String.format("[RoundTrip] toLatLng(0,0)=(%.8f,%.8f) originError=%.4fm", rt[0], rt[1], rtError));

            // [AxisTest] diagnostic: 5m East should give (+5, ~0)
            double[] east5 = coordTransform.toEastNorth(lat, lng + 5.0 / (111132.92 * Math.cos(Math.toRadians(lat))));
            double[] north5 = coordTransform.toEastNorth(lat + 5.0 / 111132.92, lng);
            Log.i(TAG, String.format("[AxisTest] 5mEast→ENU=(%.2f,%.2f) %s | 5mNorth→ENU=(%.2f,%.2f) %s",
                    east5[0], east5[1], (east5[0] > 4 && Math.abs(east5[1]) < 1) ? "PASS" : "FAIL",
                    north5[0], north5[1], (north5[1] > 4 && Math.abs(north5[0]) < 1) ? "PASS" : "FAIL"));
        }
    }

    // ---- sensor callbacks ----------------------------------------------------

    /**
     * Called on each detected step with the PDR stride length and device heading.
     *
     * @param stepLength stride in metres (Weiberg or manual)
     * @param headingRad azimuth in radians (0 = North, clockwise)
     */
    public void onPdrStep(double stepLength, double headingRad) {
        if (!active) {
            if (DEBUG) Log.d(TAG, "PDR step ignored — fusion not yet initialised");
            return;
        }

        // Stairs/lift step reduction: multiply horizontal step by 0.2
        if (indoorMapManager != null && coordTransform.isInitialized()) {
            double[] curLatLng = coordTransform.toLatLng(
                    particleFilter.getEstimatedX(),
                    particleFilter.getEstimatedY());
            LatLng curPos = new LatLng(curLatLng[0], curLatLng[1]);
            if (indoorMapManager.isNearStairs(curPos)) {
                stepLength *= STAIRS_STEP_FACTOR;
                if (DEBUG) Log.d(TAG, "[Stairs] Step reduced to " + String.format("%.3fm", stepLength));
            }
        }

        double correctedHeading = headingRad + headingBias; // headingBias enabled

        double dx = stepLength * Math.sin(correctedHeading);
        double dy = stepLength * Math.cos(correctedHeading);

        // Accumulate pure PDR displacement for heading bias calculation
        pdrAccumDx += dx;
        pdrAccumDy += dy;

        lastPdrStepTimeMs = System.currentTimeMillis();

        // Save old particle positions before predict (for wall collision check)
        Particle[] particles = particleFilter.getParticles();
        for (int i = 0; i < particles.length; i++) {
            oldParticleX[i] = particles[i].x;
            oldParticleY[i] = particles[i].y;
        }

        particleFilter.predict(stepLength, correctedHeading, currentHeadingStd);

        // Apply wall constraints: snap + penalise particles that crossed walls
        int collisionCount = 0;
        if (mapConstraint.isInitialized()) {
            collisionCount = mapConstraint.applyConstraints(particles, oldParticleX, oldParticleY);
        }

        // ---- Wall-collision heading correction via reference points ----
        double collisionRatio = (double) collisionCount / NUM_PARTICLES;
        if (collisionRatio >= SEARCH_COLLISION_THRESHOLD) {
            consecutiveHighCollisionSteps++;
            if (!inSearchMode && consecutiveHighCollisionSteps >= SEARCH_TRIGGER_STEPS) {
                inSearchMode = true;
                currentHeadingStd = SEARCH_HEADING_STD_INIT;
                if (DEBUG) Log.i(TAG, String.format("[SearchMode] ENTER collisionRatio=%.0f%% consecutive=%d",
                        collisionRatio * 100, consecutiveHighCollisionSteps));

                // ---- Reference-point heading snap ----
                // Find the nearest calibration point within ±90° of current heading
                // and force heading bias to point toward it.
                double snapHeading = findNearestRefPointHeading(correctedHeading);
                if (!Double.isNaN(snapHeading)) {
                    double correction = snapHeading - headingRad; // bias = target - raw
                    // Normalize to [-π, π]
                    while (correction > Math.PI) correction -= 2 * Math.PI;
                    while (correction < -Math.PI) correction += 2 * Math.PI;
                    if (DEBUG) Log.i(TAG, String.format("[SearchMode] SNAP oldBias=%.1f° newBias=%.1f° target=%.1f° raw=%.1f°",
                            Math.toDegrees(headingBias), Math.toDegrees(correction),
                            Math.toDegrees(snapHeading), Math.toDegrees(headingRad)));
                    headingBias = correction;
                }
            }
        } else {
            consecutiveHighCollisionSteps = 0;
        }

        // Decay heading noise each step (whether in search mode or not)
        if (inSearchMode) {
            currentHeadingStd = Math.max(
                    currentHeadingStd * SEARCH_HEADING_STD_DECAY,
                    SEARCH_HEADING_STD_MIN);
            if (currentHeadingStd <= SEARCH_HEADING_STD_MIN) {
                inSearchMode = false;
                if (DEBUG) Log.i(TAG, "[SearchMode] EXIT — heading noise decayed to normal");
            }
        }

        updateFusedOutput();
        pdrStepIndex++;
        stepsSinceLastCorrection++;
        checkGateMode();
        if (DEBUG) Log.i(TAG, String.format("[PDR] step=%d rawH=%.1f° bias=%.1f° corrH=%.1f° len=%.2f dENU=(%.3f,%.3f) fENU=(%.2f,%.2f) gate=%s nofix=%d coll=%.0f%% hStd=%.1f° search=%b",
                pdrStepIndex, Math.toDegrees(headingRad),
                Math.toDegrees(headingBias), Math.toDegrees(correctedHeading),
                stepLength, dx, dy,
                particleFilter.getEstimatedX(), particleFilter.getEstimatedY(),
                gateMode, stepsSinceLastCorrection,
                collisionRatio * 100, Math.toDegrees(currentHeadingStd), inSearchMode));
    }

    /**
     * Updates the heading bias estimate using the discrepancy between the
     * direction the PF moved and the direction of an absolute observation
     * (WiFi or GNSS). Only updates when there is enough movement.
     */
    private void updateHeadingBias(double obsX, double obsY) {
        if (!hasPrevObs) {
            prevObsX = obsX;
            prevObsY = obsY;
            pdrAccumDx = 0;
            pdrAccumDy = 0;
            hasPrevObs = true;
            return;
        }

        // Pure PDR displacement (accumulated since last WiFi observation)
        double pdrDist = Math.hypot(pdrAccumDx, pdrAccumDy);

        // WiFi observation displacement
        double obsDx = obsX - prevObsX;
        double obsDy = obsY - prevObsY;
        double obsDist = Math.hypot(obsDx, obsDy);

        // Only update if PDR moved enough (avoids noise when stationary)
        if (pdrDist < 1.0) {
            if (DEBUG) Log.d(TAG, String.format("[HeadingBias] SKIP pdrDist=%.1fm (need >1m, keep accumulating)",
                    pdrDist));
            return;
        }

        if (obsDist < 1.0) {
            if (DEBUG) Log.d(TAG, String.format("[HeadingBias] SKIP obsDist=%.1fm (need >1m) pdrDist=%.1fm — reset pdr accum",
                    obsDist, pdrDist));
            prevObsX = obsX;
            prevObsY = obsY;
            pdrAccumDx = 0;
            pdrAccumDy = 0;
            return;
        }

        // Both moved enough — compute heading bias
        double pdrAngle = Math.atan2(pdrAccumDx, pdrAccumDy);
        double obsAngle = Math.atan2(obsDx, obsDy);
        double angleDiff = obsAngle - pdrAngle;

        // Normalize to [-π, π]
        while (angleDiff > Math.PI) angleDiff -= 2 * Math.PI;
        while (angleDiff < -Math.PI) angleDiff += 2 * Math.PI;

        boolean earlyPhase = (pdrStepIndex <= HEADING_EARLY_STEPS);

        if (earlyPhase) {
            // Early phase: only accept observations within forward 90° and 20m
            // Current heading direction (PDR accumulated)
            double currentHeading = Math.atan2(pdrAccumDx, pdrAccumDy);
            double obsDirection = Math.atan2(obsDx, obsDy);
            double dirDiff = obsDirection - currentHeading;
            while (dirDiff > Math.PI) dirDiff -= 2 * Math.PI;
            while (dirDiff < -Math.PI) dirDiff += 2 * Math.PI;

            if (Math.abs(dirDiff) > HEADING_EARLY_FOV || obsDist > HEADING_EARLY_MAX_DIST) {
                if (DEBUG) Log.i(TAG, String.format("[HeadingBias] REJECT early: dirDiff=%.1f° dist=%.1fm (need <90° <20m) step=%d",
                        Math.toDegrees(dirDiff), obsDist, pdrStepIndex));
            } else {
                // Clamp correction to ±5° per observation
                double clamped = Math.max(-HEADING_EARLY_CORRECTION,
                        Math.min(HEADING_EARLY_CORRECTION, angleDiff));
                double oldBias = headingBias;
                headingBias += clamped;

                if (DEBUG) Log.i(TAG, String.format("[HeadingBias] EARLY step=%d diff=%.1f° clamped=%.1f° oldBias=%.1f° newBias=%.1f° pdrDist=%.1fm obsDist=%.1fm",
                        pdrStepIndex, Math.toDegrees(angleDiff), Math.toDegrees(clamped),
                        Math.toDegrees(oldBias), Math.toDegrees(headingBias), pdrDist, obsDist));
            }
        } else {
            // Normal phase: reject > 20°, conservative EMA
            if (Math.abs(angleDiff) > Math.toRadians(20)) {
                if (DEBUG) Log.i(TAG, String.format("[HeadingBias] REJECT diff=%.1f° > 20° step=%d pdrAngle=%.1f° obsAngle=%.1f°",
                        Math.toDegrees(angleDiff), pdrStepIndex, Math.toDegrees(pdrAngle), Math.toDegrees(obsAngle)));
            } else {
                double oldBias = headingBias;
                headingBias = headingBias * (1 - HEADING_BIAS_ALPHA)
                        + angleDiff * HEADING_BIAS_ALPHA;

                if (DEBUG) Log.i(TAG, String.format("[HeadingBias] UPDATE α=%.2f step=%d pdrAngle=%.1f° obsAngle=%.1f° diff=%.1f° oldBias=%.1f° newBias=%.1f° pdrDist=%.1fm obsDist=%.1fm",
                        HEADING_BIAS_ALPHA, pdrStepIndex,
                        Math.toDegrees(pdrAngle), Math.toDegrees(obsAngle),
                        Math.toDegrees(angleDiff), Math.toDegrees(oldBias),
                        Math.toDegrees(headingBias), pdrDist, obsDist));
            }
        }

        // Only reset accumulators when we actually consumed them (UPDATE or REJECT)
        prevObsX = obsX;
        prevObsY = obsY;
        pdrAccumDx = 0;
        pdrAccumDy = 0;
    }

    /**
     * Searches calibration reference points within ±90° of the current heading
     * and returns the heading (radians) toward the nearest one.
     *
     * <p>Reference points lie on corridors the user has walked before,
     * so the nearest forward one indicates the correct walking direction.</p>
     *
     * @param currentHeading current corrected heading in radians (0=N, CW)
     * @return heading toward nearest forward ref point, or NaN if none found
     */
    private double findNearestRefPointHeading(double currentHeading) {
        if (calibrationManager == null || !coordTransform.isInitialized()) {
            if (DEBUG) Log.d(TAG, "[SearchMode] No CalibrationManager or CoordTransform — skip snap");
            return Double.NaN;
        }

        double curX = particleFilter.getEstimatedX();
        double curY = particleFilter.getEstimatedY();

        List<LatLng> refPoints = calibrationManager.getRecordPositions(fusedFloor);
        if (refPoints.isEmpty()) {
            if (DEBUG) Log.d(TAG, "[SearchMode] No ref points on floor " + fusedFloor);
            return Double.NaN;
        }

        double bestDist = Double.MAX_VALUE;
        double bestHeading = Double.NaN;
        int candidateCount = 0;

        for (LatLng ll : refPoints) {
            double[] en = coordTransform.toEastNorth(ll.latitude, ll.longitude);
            double dx = en[0] - curX;
            double dy = en[1] - curY;
            double dist = Math.hypot(dx, dy);

            // Skip points too close (noise) or too far (irrelevant)
            if (dist < 2.0 || dist > 30.0) continue;

            // Heading from current position to this reference point
            double headingToRef = Math.atan2(dx, dy); // atan2(E, N) = azimuth

            // Angle difference to current heading
            double angleDiff = headingToRef - currentHeading;
            while (angleDiff > Math.PI) angleDiff -= 2 * Math.PI;
            while (angleDiff < -Math.PI) angleDiff += 2 * Math.PI;

            // Only consider points within ±90° (forward semicircle)
            if (Math.abs(angleDiff) > Math.PI / 2) continue;

            candidateCount++;
            if (dist < bestDist) {
                bestDist = dist;
                bestHeading = headingToRef;
            }
        }

        if (!Double.isNaN(bestHeading)) {
            if (DEBUG) Log.i(TAG, String.format("[SearchMode] Found %d forward ref points, nearest at %.1fm heading=%.1f°",
                    candidateCount, bestDist, Math.toDegrees(bestHeading)));
        } else {
            if (DEBUG) Log.i(TAG, String.format("[SearchMode] No forward ref points found (%d total on floor %d)",
                    refPoints.size(), fusedFloor));
        }
        return bestHeading;
    }

    /**
     * Called when a new WiFi position fix arrives from the OpenPositioning API.
     */
    public void onWifiPosition(double lat, double lng, int floor) {
        LatLng pos = new LatLng(lat, lng);
        if (pos.equals(lastWifiPosition)) return;
        lastWifiPosition = pos;
        lastWifiFloor = floor;

        // Seed initial floor from first valid WiFi response.
        // setBuildingOverlay() runs before WiFi responds, so we seed here.
        if (!wifiFloorSeeded && floor >= 0) {
            wifiFloorSeeded = true;
            SensorFusion.getInstance().setInitialFloor(floor);
            lastReportedFloor = floor;
            if (DEBUG) Log.i(TAG, "[WifiFloorSeed] First WiFi floor=" + floor
                    + " → seeded as initial floor");
        }

        if (!active) {
            if (calibrationMode) {
                addCalibrationFix(lat, lng, CAL_WEIGHT_WIFI, "WIFI_API");
            }
            if (DEBUG) Log.i(TAG, String.format("WiFi fix: (%.6f, %.6f) floor=%d active=false calMode=%b → init",
                    lat, lng, floor, calibrationMode));
            tryInitialize(lat, lng, floor);
            return;
        }

        if (!coordTransform.isInitialized()) return;

        double[] en = coordTransform.toEastNorth(lat, lng);

        // WiFi API = STRONG source
        if (!shouldAcceptObservation(en[0], en[1], ObservationLevel.STRONG)) {
            logObservation("WIFI_API", en[0], en[1], floor, 0, false,
                    String.format("gate_rejected mode=%s", gateMode));
            return;
        }

        double distFromFused = Math.hypot(en[0] - particleFilter.getEstimatedX(),
                                          en[1] - particleFilter.getEstimatedY());

        // Recovery: when UNLOCKED with large drift, re-seed particles around observation
        if (gateMode == GateMode.UNLOCKED && distFromFused > RESEED_DISTANCE) {
            if (DEBUG) Log.i(TAG, String.format("[Recovery] WIFI re-seed dist=%.1fm → particles reset around (%.2f,%.2f)",
                    distFromFused, en[0], en[1]));
            particleFilter.initialize(en[0], en[1], floor, 12.0);
            updateFusedOutput();
            onObservationAccepted(ObservationLevel.STRONG);
            return;
        }

        double wifiSigma = 4.0;
        logObservation("WIFI_API", en[0], en[1], floor, wifiSigma, true,
                String.format("level=STRONG mode=%s dist=%.1fm", gateMode, distFromFused));
        particleFilter.updateWithDynamicSigma(en[0], en[1], wifiSigma);
        // WiFi floor is NOT used for ongoing floor updates — baro-only autofloor.
        // particleFilter.updateFloor(floor);
        updateHeadingBias(en[0], en[1]);
        updateFusedOutput();
        onObservationAccepted(ObservationLevel.STRONG);
    }

    /**
     * Called when a new GPS fix arrives (network/cellular is excluded upstream).
     *
     * @param accuracy reported accuracy in metres from {@code Location.getAccuracy()}
     */
    public void onGnssPosition(double lat, double lng, float accuracy) {
        LatLng pos = new LatLng(lat, lng);
        if (pos.equals(lastGnssPosition)) return;
        lastGnssPosition = pos;

        if (!active) {
            if (calibrationMode) {
                addCalibrationFix(lat, lng, CAL_WEIGHT_GNSS, "GNSS");
            }
            if (DEBUG) Log.i(TAG, String.format("GPS fix: (%.6f, %.6f) acc=%.0fm active=false calMode=%b → init",
                    lat, lng, accuracy, calibrationMode));
            tryInitialize(lat, lng, 0);
            return;
        }

        if (!coordTransform.isInitialized()) return;

        double[] en = coordTransform.toEastNorth(lat, lng);

        // GF: GNSS is reliable → STRONG; upper floors: WEAK
        boolean isGF = (fusedFloor == 0 || fusedFloor == 1);
        ObservationLevel gateLevel = isGF ? ObservationLevel.STRONG : ObservationLevel.WEAK;
        if (!shouldAcceptObservation(en[0], en[1], gateLevel)) {
            logObservation("GPS", en[0], en[1], fusedFloor, 0, false,
                    String.format("gate_rejected mode=%s acc=%.0fm floor=%d", gateMode, accuracy, fusedFloor));
            return;
        }

        // GF (floor 0 or 1 with bias): GNSS is reliable, use same weight as WiFi API.
        // Other floors: indoor GNSS is unreliable, use high sigma.
        boolean isGroundFloor = (fusedFloor == 0 || fusedFloor == 1);
        double sigma;
        ObservationLevel level;
        if (isGroundFloor) {
            sigma = 4.0; // same as WiFi API
            level = ObservationLevel.STRONG;
        } else {
            sigma = Math.max(accuracy, GNSS_INDOOR_SIGMA);
            level = ObservationLevel.WEAK;
        }
        logObservation("GPS", en[0], en[1], fusedFloor, sigma, true,
                String.format("level=%s mode=%s acc=%.0fm floor=%d gf=%b step=%d",
                        level, gateMode, accuracy, fusedFloor, isGroundFloor, pdrStepIndex));
        particleFilter.updateWithDynamicSigma(en[0], en[1], sigma);
        updateFusedOutput();
        onObservationAccepted(level);
    }

    /**
     * Called when the user long-presses the map to manually correct their position.
     * Uses a very tight uncertainty to pull particles strongly toward the correction.
     *
     * @param lat true latitude selected by the user
     * @param lng true longitude selected by the user
     */
    public void onManualCorrection(double lat, double lng) {
        if (!coordTransform.isInitialized()) {
            // Use the correction as the initial origin
            tryInitialize(lat, lng, fusedFloor);
            return;
        }

        if (!active) {
            tryInitialize(lat, lng, fusedFloor);
            return;
        }

        double[] en = coordTransform.toEastNorth(lat, lng);
        // Very tight std dev (2m) — strong correction
        particleFilter.updateWithManualCorrection(en[0], en[1], 2.0);
        updateFusedOutput();
        if (DEBUG) Log.i(TAG, String.format("Manual correction applied at (%.6f, %.6f)", lat, lng));
    }

    /**
     * Reseeds all floor state to a known value (e.g. from WiFi API on autofloor toggle).
     * Updates lastReportedFloor, fusedFloor, and particle floors so that
     * subsequent baro readings don't trigger spurious floor transitions.
     *
     * @param floor the logical floor number (0=GF, 1=F1, …)
     */
    public void reseedFloor(int floor) {
        int prev = lastReportedFloor;
        lastReportedFloor = floor;
        floorCandidate = -1;
        fusedFloor = floor;
        if (particleFilter.isInitialized()) {
            particleFilter.updateFloor(floor);
        }
        if (DEBUG) Log.i(TAG, String.format("[FloorReseed] FM floor %d→%d (particles updated)", prev, floor));
    }

    // ---- unified observation logging ------------------------------------------

    /** Logs an observation event for diagnostic purposes. */
    private void logObservation(String source, double obsE, double obsN,
                                int floor, double sigma,
                                boolean accepted, String reason) {
        if (DEBUG) Log.i(TAG, String.format("[Observation] source=%s floor=%d sigma=%.1f accepted=%b reason=%s obsENU=(%.2f,%.2f)",
                source, floor, sigma, accepted, reason, obsE, obsN));
    }

    // ---- gate state machine helpers ----------------------------------------

    /** Returns the distance gate for the given observation level, or -1 to reject. */
    private double getCurrentGate(ObservationLevel level) {
        if (pdrStepIndex < 30) return LOCKED_GATE_INIT;
        if (gateMode == GateMode.UNLOCKED) {
            // UNLOCKED: accept STRONG and MEDIUM without distance limit (we know PDR drifted)
            if (level == ObservationLevel.STRONG || level == ObservationLevel.MEDIUM)
                return Double.MAX_VALUE;
            return -1; // reject WEAK (GNSS) even in UNLOCKED
        }
        return LOCKED_GATE;
    }

    /** Checks whether to transition LOCKED → UNLOCKED based on drift indicators. */
    private void checkGateMode() {
        if (gateMode == GateMode.LOCKED) {
            if (stepsSinceLastCorrection > UNLOCK_STEP_THRESHOLD ||
                    fusedUncertainty > UNLOCK_UNCERTAINTY_THRESHOLD) {
                gateMode = GateMode.UNLOCKED;
                if (DEBUG) Log.i(TAG, String.format("[Gate] LOCKED→UNLOCKED steps_no_fix=%d uncertainty=%.1fm",
                        stepsSinceLastCorrection, fusedUncertainty));
            }
        }
    }

    /** Returns true if the observation passes the adaptive distance gate. */
    private boolean shouldAcceptObservation(double obsE, double obsN,
                                            ObservationLevel level) {
        // Stationary: freeze position — reject ALL corrections when user is not walking
        if (lastPdrStepTimeMs > 0
                && (System.currentTimeMillis() - lastPdrStepTimeMs) > STATIONARY_TIMEOUT_MS) {
            if (DEBUG) Log.d(TAG, String.format("[Gate] REJECTED stationary (no step for %dms) level=%s",
                    System.currentTimeMillis() - lastPdrStepTimeMs, level));
            return false;
        }

        // Warmup: suppress all observations for WARMUP_MS after initialisation
        // to prevent stale GNSS/WiFi cache from pulling the initial position.
        if (initTimeMs > 0 && System.currentTimeMillis() - initTimeMs < WARMUP_MS) {
            if (DEBUG) Log.d(TAG, String.format("[Gate] REJECTED warmup (%dms remaining) level=%s",
                    WARMUP_MS - (System.currentTimeMillis() - initTimeMs), level));
            return false;
        }

        double distFromFused = Math.hypot(obsE - particleFilter.getEstimatedX(),
                                          obsN - particleFilter.getEstimatedY());
        double gate = getCurrentGate(level);

        if (gate < 0) {
            if (DEBUG) Log.d(TAG, String.format("[Gate] REJECTED level=%s in UNLOCKED mode (STRONG/MEDIUM only)", level));
            return false;
        }

        if (distFromFused > gate) {
            if (DEBUG) Log.d(TAG, String.format("[Gate] REJECTED dist=%.1fm > gate=%.0fm mode=%s level=%s step=%d",
                    distFromFused, gate, gateMode, level, pdrStepIndex));
            return false;
        }

        return true;
    }

    /** Called after an observation is accepted to reset drift counter and lock gate. */
    private void onObservationAccepted(ObservationLevel level) {
        stepsSinceLastCorrection = 0;
        if (gateMode == GateMode.UNLOCKED
                && (level == ObservationLevel.STRONG || level == ObservationLevel.MEDIUM)) {
            gateMode = GateMode.LOCKED;
            if (DEBUG) Log.i(TAG, String.format("[Gate] UNLOCKED→LOCKED (%s fix received)", level));
        }
    }

    // ---- calibration observation (from CalibrationManager WKNN) -------------

    /**
     * Processes a calibration database observation through the same pipeline
     * as GPS/WiFi: distance gate, stationary damping, unified logging.
     */
    public void onCalibrationObservation(double obsE, double obsN,
                                         double sigma, int floor, String quality) {
        // During calibration mode, convert ENU back to LatLng and accumulate
        if (calibrationMode && coordTransform.isInitialized()) {
            double[] ll = coordTransform.toLatLng(obsE, obsN);
            addCalibrationFix(ll[0], ll[1], CAL_WEIGHT_CALDB, "CAL_DB(" + quality + ")");
            return;
        }
        if (!active || !coordTransform.isInitialized()) return;

        // Map CAL_DB quality string to observation level
        ObservationLevel calLevel;
        switch (quality) {
            case "GOOD":
                calLevel = ObservationLevel.STRONG;
                break;
            case "AMBIGUOUS":
                calLevel = ObservationLevel.MEDIUM;
                break;
            default:
                calLevel = ObservationLevel.WEAK;
                break;
        }

        if (!shouldAcceptObservation(obsE, obsN, calLevel)) {
            logObservation("CAL_DB", obsE, obsN, floor, sigma, false,
                    String.format("gate_rejected mode=%s quality=%s level=%s",
                            gateMode, quality, calLevel));
            return;
        }

        double distFromFused = Math.hypot(obsE - particleFilter.getEstimatedX(),
                                          obsN - particleFilter.getEstimatedY());

        // Recovery: when UNLOCKED with large drift, re-seed (looser spread for CAL_DB)
        if (gateMode == GateMode.UNLOCKED && distFromFused > RESEED_DISTANCE
                && calLevel == ObservationLevel.STRONG) {
            if (DEBUG) Log.i(TAG, String.format("[Recovery] CAL_DB re-seed dist=%.1fm quality=%s",
                    distFromFused, quality));
            particleFilter.initialize(obsE, obsN, floor, 15.0);
            updateFusedOutput();
            onObservationAccepted(calLevel);
            return;
        }

        logObservation("CAL_DB", obsE, obsN, floor, sigma, true,
                String.format("level=%s mode=%s quality=%s dist=%.1f",
                        calLevel, gateMode, quality, distFromFused));
        particleFilter.updateWithDynamicSigma(obsE, obsN, sigma);
        // Use GOOD calibration observations for heading bias (more stable than WiFi API)
        if (calLevel == ObservationLevel.STRONG) {
            updateHeadingBias(obsE, obsN);
        }
        updateFusedOutput();
        onObservationAccepted(calLevel);
    }

    // ---- floor handling ------------------------------------------------------

    /**
     * Initializes particle floor distribution as a prior (not force).
     * Most particles go to the best floor, some to adjacent floors.
     *
     * @param bestFloor most likely floor
     * @param confidence 0.0–1.0, controls how concentrated the distribution is
     */
    public void initializeFloorPrior(int bestFloor, double confidence) {
        if (!particleFilter.isInitialized()) {
            fusedFloor = bestFloor;
            lastReportedFloor = bestFloor;
            if (DEBUG) Log.i(TAG, String.format("[Floor] prior deferred (PF not init): best=%d", bestFloor));
            return;
        }

        Particle[] particles = particleFilter.getParticles();
        java.util.Random rng = new java.util.Random();

        int onBest = 0, onAdjacent = 0;
        for (Particle p : particles) {
            double roll = rng.nextDouble();
            if (roll < confidence) {
                p.floor = bestFloor;
                onBest++;
            } else if (roll < confidence + (1 - confidence) * 0.5) {
                p.floor = Math.max(0, bestFloor - 1);
                onAdjacent++;
            } else {
                p.floor = bestFloor + 1;
                onAdjacent++;
            }
        }

        fusedFloor = bestFloor;
        lastReportedFloor = bestFloor;

        if (DEBUG) Log.i(TAG, String.format("[Floor] prior initialized: best=%d conf=%.0f%% particles=%d/%d on best",
                bestFloor, confidence * 100, onBest, particles.length));
    }

    /**
     * Called by barometer when relative height changes. Uses a state machine:
     * STABLE → CANDIDATE (new floor detected) → CONFIRMED (held for 2 seconds).
     * Only CONFIRMED transitions update the particle filter.
     * The barometer provides transition EVIDENCE, not a direct floor command.
     */
    public void onFloorChanged(int baroFloor) {
        if (!active) return;
        if (baroFloor == lastReportedFloor) {
            floorCandidate = -1;
            return;
        }

        long now = System.currentTimeMillis();

        if (baroFloor != floorCandidate) {
            floorCandidate = baroFloor;
            floorCandidateStartMs = now;
            if (DEBUG) Log.d(TAG, String.format("[Floor] CANDIDATE %d → %d",
                    lastReportedFloor, baroFloor));
            return;
        }

        if (now - floorCandidateStartMs < FLOOR_CONFIRM_MS) return;

        if (DEBUG) Log.i(TAG, String.format("[Floor] CONFIRMED %d → %d (baro held 1s)",
                lastReportedFloor, baroFloor));
        lastReportedFloor = baroFloor;
        floorCandidate = -1;
        particleFilter.updateFloor(baroFloor);
        updateFusedOutput();

        // Reset baro baseline to prevent long-term drift accumulation.
        // After this, relHeight resets to ~0 relative to the new floor.
        SensorFusion.getInstance().resetBaroBaseline(baroFloor);
    }

    /** Returns floor probability distribution from particle weights. */
    public double[] getFloorProbabilities() {
        double[] probs = new double[10];
        if (!particleFilter.isInitialized()) return probs;
        for (Particle p : particleFilter.getParticles()) {
            int f = Math.max(0, Math.min(9, p.floor));
            probs[f] += p.weight;
        }
        return probs;
    }

    // ---- output --------------------------------------------------------------

    private void updateFusedOutput() {
        double[] latLng = coordTransform.toLatLng(
                particleFilter.getEstimatedX(),
                particleFilter.getEstimatedY());
        fusedLat = latLng[0];
        fusedLng = latLng[1];
        fusedFloor = particleFilter.getEstimatedFloor();
        fusedUncertainty = particleFilter.getUncertainty();
    }

    /** Returns the fused position as a LatLng, or null if not yet initialised. */
    public LatLng getFusedLatLng() {
        if (!active) return null;
        return new LatLng(fusedLat, fusedLng);
    }

    /** Returns the current fused floor index. */
    public int getFusedFloor() {
        return fusedFloor;
    }

    /** Returns the current fused position uncertainty in metres. */
    public double getFusedUncertainty() {
        return fusedUncertainty;
    }

    /** Returns {@code true} if the fusion engine has been initialised and is active. */
    public boolean isActive() {
        return active;
    }

    /** Resets the fusion state for a new recording session. */
    public void reset() {
        active = false;
        lastWifiPosition = null;
        lastGnssPosition = null;
        lastWifiFloor = -1;
        wifiFloorSeeded = false;
        fusedLat = 0;
        fusedLng = 0;
        fusedFloor = 0;
        fusedUncertainty = 0;
        headingBias = 0;
        hasPrevObs = false;
        pdrAccumDx = 0;
        pdrAccumDy = 0;
        pdrStepIndex = 0;
        lastReportedFloor = -1;
        floorCandidate = -1;
        floorCandidateStartMs = 0;
        gateMode = GateMode.LOCKED;
        stepsSinceLastCorrection = 0;
        initTimeMs = 0;
        lastPdrStepTimeMs = 0;
        consecutiveHighCollisionSteps = 0;
        currentHeadingStd = SEARCH_HEADING_STD_MIN;
        inSearchMode = false;
        // ParticleFilter will be re-initialised on next position fix
    }

    /** Returns the coordinate transform used by this fusion manager. */
    public CoordinateTransform getCoordinateTransform() {
        return coordTransform;
    }

    /** Returns the underlying particle filter instance. */
    public ParticleFilter getParticleFilter() {
        return particleFilter;
    }
}
