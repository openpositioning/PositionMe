package com.openpositioning.PositionMe.sensors;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.Random;

/**
 * SIR particle filter fusion engine in local East/North coordinates.
 *
 * <p>The filter predicts with step-based PDR displacement and updates particle
 * weights with GNSS/WiFi absolute fixes. Resampling is triggered when
 * the effective particle count falls below a threshold.</p>
 */
public class PositionFusionEngine {

    private static final String TAG = "PositionFusionPF";
    private static final boolean DEBUG_LOGS = true;

    private static final double EARTH_RADIUS_M = 6378137.0;

    private static final int PARTICLE_COUNT = 300;
    private static final double RESAMPLE_RATIO = 0.5;
    private static final double PDR_NOISE_STD_M = 0.55;
    private static final double INIT_STD_M = 2.0;
    private static final double ROUGHEN_STD_M = 0.15;
    private static final double WIFI_SIGMA_M = 3;
    private static final double WIFI_HARD_SNAP_DISTANCE_M = 7.0;
    private static final double OUTLIER_GATE_SIGMA_MULT_GNSS = 2.8;
    private static final double OUTLIER_GATE_SIGMA_MULT_WIFI = 10.0;
    private static final double OUTLIER_GATE_MIN_M = 6.0;
    private static final double MAX_OUTLIER_SIGMA_SCALE = 4.0;
    private static final double GNSS_INDOOR_SIGMA_MULTIPLIER = 6.0;
    private static final double GNSS_INDOOR_MIN_SIGMA_M = 18.0;
    private static final double OUTPUT_SMOOTHING_ALPHA = 0.45;
    private static final double EPS = 1e-300;
    private static final double CONNECTOR_RADIUS_M = 3.0;
    private static final double LIFT_HORIZONTAL_MAX_M = 0.50;
    private static final double ORIENTATION_BIAS_LEARN_RATE = 0.3;
    private static final double ORIENTATION_BIAS_MAX_STEP_RAD = Math.toRadians(7.0);
    private static final double ORIENTATION_BIAS_MAX_ABS_RAD = Math.toRadians(170.0);
    private static final double ORIENTATION_BIAS_MIN_STEP_M = 0.35;
    private static final double ORIENTATION_BIAS_MIN_INNOVATION_M = 0.30;
    private static final double WIFI_PATTERN_HEADING_MIN_MOVE_M = 1.2;
    private static final int WIFI_SNAP_HISTORY_POINTS = 5;
    private static final int WIFI_SNAP_MIN_HISTORY_POINTS = 1;
    private static final double WIFI_SNAP_MIN_VECTOR_M = 0.80;
    private static final double WIFI_SNAP_DIRECTION_MIN_MOVE_M = 0.50;
    private static final double ORIENTATION_BIAS_WIFI_PATTERN_LEARN_RATE = 0.8;
    private static final double ORIENTATION_BIAS_WIFI_PATTERN_MAX_STEP_RAD = Math.toRadians(10.0);
    private static final double ORIENTATION_BIAS_WIFI_SNAP_MAX_STEP_RAD = Math.toRadians(60.0);
    private static final boolean ENABLE_WALL_SLIDE = true;
    private static final double WALL_STOP_MARGIN_RATIO = 0.02;
    private static final double MAX_WALL_SLIDE_M = 0.60;
    private static final double WALL_PENALTY_HIT_INCREMENT = 1;
    private static final double WALL_PENALTY_DECAY_ON_FREE_MOVE = 0.65;
    private static final double WALL_PENALTY_STRENGTH = 0.35;
    private static final double WALL_PENALTY_SCORE_MAX = 8.0;
    private static final double FIX_WALL_CROSS_PROB_GNSS = 0.35;
    private static final double FIX_WALL_CROSS_PROB_WIFI = 0.60;
    private static final Pattern FLOOR_NUMBER_PATTERN = Pattern.compile("-?\\d+");

    private final float floorHeightMeters;
    private final Random random = new Random();

    // Local tangent frame anchor
    private double anchorLatDeg;
    private double anchorLonDeg;
    private boolean hasAnchor;

    private final List<Particle> particles = new ArrayList<>(PARTICLE_COUNT);
    private int fallbackFloor;
    private long updateCounter;
    private String activeBuildingName;
    private final Map<Integer, FloorConstraint> floorConstraints = new HashMap<>();
    private double recentStepMotionMeters;
    private double recentStepEastMeters;
    private double recentStepNorthMeters;
    private double headingBiasRad;
    private double smoothedEastMeters;
    private double smoothedNorthMeters;
    private boolean hasSmoothedEstimate;
    private double lastWifiFixEastMeters;
    private double lastWifiFixNorthMeters;
    private int lastWifiFixFloor;
    private boolean hasLastWifiFix;
    private final List<WifiFixSample> wifiFixHistory = new ArrayList<>();
    private boolean hasSnapOrientationOverride;
    private double snapOrientationOverrideRad;
    private double latestRawHeadingRad;
    private boolean hasLatestRawHeading;

    private static final class Particle {
        double xEast;
        double yNorth;
        int floor;
        double weight;
        double wallPenaltyScore;
    }

    private static final class Point2D {
        final double x;
        final double y;

        Point2D(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class Segment {
        final Point2D a;
        final Point2D b;

        Segment(Point2D a, Point2D b) {
            this.a = a;
            this.b = b;
        }
    }

    private static final class WallIntersection {
        final Segment wall;
        final double t;

        WallIntersection(Segment wall, double t) {
            this.wall = wall;
            this.t = t;
        }
    }

    private static final class FloorConstraint {
        final List<Segment> walls = new ArrayList<>();
        final List<Point2D> stairs = new ArrayList<>();
        final List<Point2D> lifts = new ArrayList<>();
    }

    private static final class WifiFixSample {
        final double east;
        final double north;
        final int floor;

        WifiFixSample(double east, double north, int floor) {
            this.east = east;
            this.north = north;
            this.floor = floor;
        }
    }

    public PositionFusionEngine(float floorHeightMeters) {
        this.floorHeightMeters = floorHeightMeters > 0f ? floorHeightMeters : 4f;
    }

    /**
     * Re-anchors the local tangent frame and reinitializes all particles.
     */
    public synchronized void reset(double latDeg, double lonDeg, int initialFloor) {
        anchorLatDeg = latDeg;
        anchorLonDeg = lonDeg;
        hasAnchor = true;

        fallbackFloor = initialFloor;
        headingBiasRad = 0.0;
        recentStepEastMeters = 0.0;
        recentStepNorthMeters = 0.0;
        recentStepMotionMeters = 0.0;
        hasSmoothedEstimate = false;
        hasLastWifiFix = false;
        wifiFixHistory.clear();
        hasSnapOrientationOverride = false;
        hasLatestRawHeading = false;
        initParticlesAtOrigin(initialFloor);
        if (DEBUG_LOGS) {
            Log.i(TAG, String.format(Locale.US,
                    "Reset anchor=(%.7f, %.7f) floor=%d particles=%d headingBiasDeg=%.2f",
                    latDeg, lonDeg, initialFloor, PARTICLE_COUNT,
                    Math.toDegrees(headingBiasRad)));
        }
    }

    /**
     * Prediction step: propagate particles using step displacement + process noise.
     *
     * <p>When a predicted segment intersects an indoor wall, motion is blocked for
     * that particle to keep trajectories inside mapped traversable space.</p>
     */
    public synchronized void updatePdrDisplacement(float dxEastMeters, float dyNorthMeters) {
        if (!hasAnchor || particles.isEmpty()) {
            return;
        }

        recentStepEastMeters = dxEastMeters;
        recentStepNorthMeters = dyNorthMeters;
        recentStepMotionMeters = Math.hypot(dxEastMeters, dyNorthMeters);
        double correctedDx = dxEastMeters;
        double correctedDy = dyNorthMeters;
        int blockedByWall = 0;
        int slidAlongWall = 0;
        int stoppedAtWall = 0;

        for (Particle p : particles) {
            double oldX = p.xEast;
            double oldY = p.yNorth;
            double candidateX = oldX + correctedDx + random.nextGaussian() * PDR_NOISE_STD_M;
            double candidateY = oldY + correctedDy + random.nextGaussian() * PDR_NOISE_STD_M;

            WallIntersection hit = firstWallIntersection(p.floor, oldX, oldY, candidateX, candidateY);
            if (hit != null) {
                blockedByWall++;

                p.wallPenaltyScore = Math.min(
                        WALL_PENALTY_SCORE_MAX,
                        p.wallPenaltyScore + WALL_PENALTY_HIT_INCREMENT);

                if (ENABLE_WALL_SLIDE) {
                    Point2D wallDir = normalize(hit.wall.b.x - hit.wall.a.x, hit.wall.b.y - hit.wall.a.y);
                    double travelRatio = clamp(hit.t - WALL_STOP_MARGIN_RATIO, 0.0, 1.0);
                    double baseX = oldX + (candidateX - oldX) * travelRatio;
                    double baseY = oldY + (candidateY - oldY) * travelRatio;

                    double remDx = candidateX - baseX;
                    double remDy = candidateY - baseY;
                    double slideMag = remDx * wallDir.x + remDy * wallDir.y;
                    slideMag = clamp(slideMag, -MAX_WALL_SLIDE_M, MAX_WALL_SLIDE_M);

                    double slideX = baseX + wallDir.x * slideMag;
                    double slideY = baseY + wallDir.y * slideMag;

                    if (!crossesWall(p.floor, oldX, oldY, baseX, baseY)
                            && !crossesWall(p.floor, baseX, baseY, slideX, slideY)) {
                        p.xEast = slideX;
                        p.yNorth = slideY;
                        slidAlongWall++;
                        continue;
                    }

                    if (!crossesWall(p.floor, oldX, oldY, baseX, baseY)) {
                        p.xEast = baseX;
                        p.yNorth = baseY;
                        stoppedAtWall++;
                        continue;
                    }
                }
                continue;
            }

            p.xEast = candidateX;
            p.yNorth = candidateY;
            p.wallPenaltyScore *= WALL_PENALTY_DECAY_ON_FREE_MOVE;
        }

        if (DEBUG_LOGS) {
            Log.d(TAG, String.format(Locale.US,
                    "Predict dPDRraw=(%.2fE, %.2fN) dPDRcorr=(%.2fE, %.2fN) headingBiasDeg=%.2f noiseStd=%.2f blockedByWall=%d slid=%d stopAtWall=%d",
                    dxEastMeters, dyNorthMeters,
                    correctedDx, correctedDy,
                    Math.toDegrees(headingBiasRad),
                    PDR_NOISE_STD_M,
                    blockedByWall,
                    slidAlongWall,
                    stoppedAtWall));
        }
    }

    /**
     * GNSS measurement update. Accuracy is converted into measurement sigma.
     */
    public synchronized void updateGnss(double latDeg, double lonDeg, float accuracyMeters) {
        // Match WiFi sigma floor so both sources contribute equally indoors.
        // When GNSS reports better accuracy outdoors it naturally gets a lower sigma.
        double sigma = Math.max(accuracyMeters, 6.0f);
        if (isIndoors()) {
            sigma = Math.max(sigma * GNSS_INDOOR_SIGMA_MULTIPLIER, GNSS_INDOOR_MIN_SIGMA_M);
        }
        if (DEBUG_LOGS) {
            Log.d(TAG, String.format(Locale.US,
                    "GNSS update lat=%.7f lon=%.7f acc=%.2f sigma=%.2f indoors=%s",
                    latDeg, lonDeg, accuracyMeters, sigma, String.valueOf(isIndoors())));
        }
        applyAbsoluteFix(latDeg, lonDeg, sigma, null);
    }

    /**
     * WiFi absolute-fix update with fixed sigma and floor hint support.
     */
    public synchronized void updateWifi(double latDeg, double lonDeg, int wifiFloor) {
        if (DEBUG_LOGS) {
            Log.d(TAG, String.format(Locale.US,
                    "WiFi update lat=%.7f lon=%.7f floor=%d sigma=%.2f",
                    latDeg, lonDeg, wifiFloor, WIFI_SIGMA_M));
        }
        applyAbsoluteFix(latDeg, lonDeg, WIFI_SIGMA_M, wifiFloor);
    }

    /**
     * Floor-transition update from barometer/elevator cues.
     *
     * <p>Transitions are allowed only near mapped stairs/lifts when those
     * connectors are available for the floor.</p>
     */
    public synchronized void updateElevation(float elevationMeters, boolean elevatorLikely) {
        int floorFromBarometer = Math.round(elevationMeters / floorHeightMeters);
        fallbackFloor = floorFromBarometer;
        int blockedTransitions = 0;
        int allowedTransitions = 0;
        if (!particles.isEmpty()) {
            for (Particle p : particles) {
                if (p.floor == floorFromBarometer) {
                    continue;
                }

                int step = floorFromBarometer > p.floor ? 1 : -1;
                int nextFloor = p.floor + step;
                if (canUseConnector(p.floor, p.xEast, p.yNorth, elevatorLikely)) {
                    p.floor = nextFloor;
                    allowedTransitions++;
                } else {
                    blockedTransitions++;
                }
            }
        }

        if (DEBUG_LOGS && (allowedTransitions > 0 || blockedTransitions > 0)) {
            Log.d(TAG, String.format(Locale.US,
                    "Elevation floor target=%d elevator=%s transitions allowed=%d blocked=%d",
                    floorFromBarometer,
                    String.valueOf(elevatorLikely),
                    allowedTransitions,
                    blockedTransitions));
        }
    }

    /**
     * Updates indoor map-matching constraints for the currently containing building.
     */
    public synchronized void updateMapMatchingContext(
            double currentLatDeg,
            double currentLonDeg,
            List<FloorplanApiClient.BuildingInfo> buildings) {
        if (!hasAnchor || buildings == null || buildings.isEmpty()) {
            floorConstraints.clear();
            activeBuildingName = null;
            return;
        }

        FloorplanApiClient.BuildingInfo containing = null;
        LatLng current = new LatLng(currentLatDeg, currentLonDeg);
        // First try the provided position (which may be the user's chosen start point).
        for (FloorplanApiClient.BuildingInfo b : buildings) {
            List<LatLng> outline = b.getOutlinePolygon();
            if (outline != null && outline.size() >= 3 && pointInPolygon(current, outline)) {
                containing = b;
                break;
            }
        }

        // Fallback: GNSS is often unreliable indoors, placing the fix outside the building.
        // If no match by polygon, try the local-frame anchor point (the user's start position)
        // which is far more reliable than a live GNSS reading inside a building.
        if (containing == null) {
            LatLng anchor = new LatLng(anchorLatDeg, anchorLonDeg);
            for (FloorplanApiClient.BuildingInfo b : buildings) {
                List<LatLng> outline = b.getOutlinePolygon();
                if (outline != null && outline.size() >= 3 && pointInPolygon(anchor, outline)) {
                    containing = b;
                    break;
                }
            }
        }

        if (containing == null) {
            // Neither GNSS nor anchor is inside any known building outline.
            // Keep existing constraints rather than wiping them on a bad reading.
            return;
        }

        if (containing.getName().equals(activeBuildingName) && !floorConstraints.isEmpty()) {
            return;
        }

        Map<Integer, FloorConstraint> parsed = new HashMap<>();
        List<FloorplanApiClient.FloorShapes> floorShapes =
                normalizeFloorOrder(containing.getFloorShapesList());
        for (int i = 0; i < floorShapes.size(); i++) {
            FloorplanApiClient.FloorShapes floor = floorShapes.get(i);
            Integer logicalFloor = parseLogicalFloor(floor, i);
            if (logicalFloor == null) {
                continue;
            }
            FloorConstraint constraint = parsed.get(logicalFloor);
            if (constraint == null) {
                constraint = new FloorConstraint();
                parsed.put(logicalFloor, constraint);
            }

            for (FloorplanApiClient.MapShapeFeature feature : floor.getFeatures()) {
                String type = feature.getIndoorType();
                List<List<LatLng>> parts = feature.getParts();
                if (parts == null || parts.isEmpty()) {
                    continue;
                }

                if ("wall".equals(type)) {
                    for (List<LatLng> part : parts) {
                        addWallSegments(part, constraint.walls);
                    }
                } else if ("stairs".equals(type) || "lift".equals(type)) {
                    for (List<LatLng> part : parts) {
                        Point2D center = toLocalCentroid(part);
                        if (center == null) {
                            continue;
                        }
                        if ("stairs".equals(type)) {
                            constraint.stairs.add(center);
                        } else {
                            constraint.lifts.add(center);
                        }
                    }
                }
            }

            if (DEBUG_LOGS) {
                Log.d(TAG, String.format(Locale.US,
                        "Map floor parsed building=%s idx=%d display=%s logical=%d walls=%d stairs=%d lifts=%d",
                        containing.getName(),
                        i,
                        floor.getDisplayName(),
                        logicalFloor,
                        constraint.walls.size(),
                        constraint.stairs.size(),
                        constraint.lifts.size()));
            }
        }

        floorConstraints.clear();
        floorConstraints.putAll(parsed);
        activeBuildingName = containing.getName();
        if (DEBUG_LOGS) {
            Log.i(TAG, String.format(Locale.US,
                    "Map matching enabled for building=%s floors=%d",
                    activeBuildingName,
                    floorConstraints.size()));
        }
    }

    /**
     * Returns the current fused estimate as the weighted particle mean.
     */
    public synchronized PositionFusionEstimate getEstimate() {
        if (!hasAnchor || particles.isEmpty()) {
            return new PositionFusionEstimate(null, fallbackFloor, false);
        }

        double meanX = 0.0;
        double meanY = 0.0;
        Map<Integer, Double> floorWeights = new HashMap<>();

        for (Particle p : particles) {
            meanX += p.weight * p.xEast;
            meanY += p.weight * p.yNorth;
            floorWeights.put(p.floor, floorWeights.getOrDefault(p.floor, 0.0) + p.weight);
        }

        int bestFloor = fallbackFloor;
        double bestFloorWeight = -1.0;
        for (Map.Entry<Integer, Double> entry : floorWeights.entrySet()) {
            if (entry.getValue() > bestFloorWeight) {
                bestFloor = entry.getKey();
                bestFloorWeight = entry.getValue();
            }
        }

        if (!hasSmoothedEstimate) {
            smoothedEastMeters = meanX;
            smoothedNorthMeters = meanY;
            hasSmoothedEstimate = true;
        } else {
            smoothedEastMeters += OUTPUT_SMOOTHING_ALPHA * (meanX - smoothedEastMeters);
            smoothedNorthMeters += OUTPUT_SMOOTHING_ALPHA * (meanY - smoothedNorthMeters);
        }

        LatLng latLng = toLatLng(smoothedEastMeters, smoothedNorthMeters);
        return new PositionFusionEstimate(latLng, bestFloor, true);
    }

    /**
     * Absolute-fix measurement update (GNSS/WiFi): reweight, normalize and resample.
     */
    private void applyAbsoluteFix(double latDeg, double lonDeg, double sigmaMeters, Integer floorHint) {
        if (!hasAnchor) {
            reset(latDeg, lonDeg, 0);
            return;
        }

        if (particles.isEmpty()) {
            initParticlesAtOrigin(fallbackFloor);
        }

        double[] z = toLocal(latDeg, lonDeg);
        double priorMeanEast = 0.0;
        double priorMeanNorth = 0.0;
        for (Particle p : particles) {
            priorMeanEast += p.weight * p.xEast;
            priorMeanNorth += p.weight * p.yNorth;
        }

        // Innovation is measured against the prior weighted mean in local EN coordinates.
        double innovationEast = z[0] - priorMeanEast;
        double innovationNorth = z[1] - priorMeanNorth;
        double innovationDistance = Math.hypot(innovationEast, innovationNorth);

        // If WiFi is clearly far from the displayed mean, hard-snap particles to the WiFi fix.
        if (floorHint != null && innovationDistance >= WIFI_HARD_SNAP_DISTANCE_M) {
            recalculateOrientationBiasOnWifiSnap(
                z[0],
                z[1],
                floorHint,
                innovationEast,
                innovationNorth);
            recordWifiFix(z[0], z[1], floorHint);
            Log.d(TAG, String.format(Locale.US,
                    "WiFi hard-snap innovation=%.2fm drift detected, resetting to fix",
                    innovationDistance));
            for (Particle p : particles) {
                p.xEast = z[0] + random.nextGaussian() * (ROUGHEN_STD_M * 0.5);
                p.yNorth = z[1] + random.nextGaussian() * (ROUGHEN_STD_M * 0.5);
                p.floor = floorHint;
                p.weight = 1.0 / particles.size();
                p.wallPenaltyScore = 0.0;
            }
            smoothedEastMeters = z[0];
            smoothedNorthMeters = z[1];
            hasSmoothedEstimate = true;
            updateCounter++;
            return;
        }

        if (floorHint != null) {
            updateOrientationBiasFromWifiPattern(z[0], z[1], floorHint);
            recordWifiFix(z[0], z[1], floorHint);
        }

        double gateSigmaMultiplier = floorHint == null
                ? OUTLIER_GATE_SIGMA_MULT_GNSS
                : OUTLIER_GATE_SIGMA_MULT_WIFI;
        double gateMeters = Math.max(gateSigmaMultiplier * sigmaMeters, OUTLIER_GATE_MIN_M);
        double effectiveSigma = sigmaMeters;
        // Outlier damping: inflate sigma instead of discarding large residual fixes.
        if (innovationDistance > gateMeters) {
            double sigmaScale = Math.min(innovationDistance / gateMeters, MAX_OUTLIER_SIGMA_SCALE);
            effectiveSigma = sigmaMeters * sigmaScale;
            if (DEBUG_LOGS) {
                Log.w(TAG, String.format(Locale.US,
                        "Outlier damping src=%s innovation=%.2fm gate=%.2fm sigma %.2f->%.2f",
                        floorHint == null ? "GNSS" : "WiFi",
                        innovationDistance,
                        gateMeters,
                        sigmaMeters,
                        effectiveSigma));
            }
        }

        double effectiveBefore = computeEffectiveSampleSize();

        double sigma2 = effectiveSigma * effectiveSigma;
        double maxLogWeight = Double.NEGATIVE_INFINITY;
        double[] logWeights = new double[particles.size()];
        int fixWallBlockedCount = 0;

        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            double dx = p.xEast - z[0];
            double dy = p.yNorth - z[1];
            double distance2 = dx * dx + dy * dy;
            double logLikelihood = -0.5 * (distance2 / sigma2);

            // Softly down-weight particles with repeated recent wall collisions.
            double wallPenaltyFactor = Math.exp(-WALL_PENALTY_STRENGTH * p.wallPenaltyScore);
            logLikelihood += Math.log(Math.max(wallPenaltyFactor, EPS));

            // Map-aware fix gating: avoid rewarding through-wall attraction.
            if (crossesWall(p.floor, p.xEast, p.yNorth, z[0], z[1])) {
                fixWallBlockedCount++;
                double blockedFixProb = floorHint == null
                        ? FIX_WALL_CROSS_PROB_GNSS
                        : FIX_WALL_CROSS_PROB_WIFI;
                logLikelihood += Math.log(Math.max(blockedFixProb, EPS));
            }

            if (floorHint != null) {
                // Soft floor gating: keep mismatch possible, but less probable.
                logLikelihood += (p.floor == floorHint) ? Math.log(0.90) : Math.log(0.10);
            }

            double logWeight = Math.log(Math.max(p.weight, EPS)) + logLikelihood;
            logWeights[i] = logWeight;
            if (logWeight > maxLogWeight) {
                maxLogWeight = logWeight;
            }
        }

        double sumW = 0.0;
        for (int i = 0; i < particles.size(); i++) {
            double normalized = Math.exp(logWeights[i] - maxLogWeight);
            particles.get(i).weight = Math.max(normalized, EPS);
            sumW += particles.get(i).weight;
        }

        if (sumW <= 0.0) {
            reinitializeAroundMeasurement(z[0], z[1], floorHint != null ? floorHint : fallbackFloor);
            return;
        }

        for (Particle p : particles) {
            p.weight /= sumW;
        }

        if (floorHint != null) {
            fallbackFloor = floorHint;
        }

        double effectiveN = computeEffectiveSampleSize();
        boolean resampled = false;
        if (effectiveN < PARTICLE_COUNT * RESAMPLE_RATIO) {
            resampleSystematic();
            roughenParticles();
            resampled = true;
        }

        updateOrientationBiasFromInnovation(innovationEast, innovationNorth, floorHint == null ? "GNSS" : "WiFi");

        updateCounter++;
        logUpdateSummary(z[0], z[1], effectiveSigma, floorHint, effectiveBefore, effectiveN, resampled);
        if (DEBUG_LOGS) {
            Log.d(TAG, String.format(Locale.US,
                    "Fix wall-aware src=%s blockedLOS=%d/%d",
                    floorHint == null ? "GNSS" : "WiFi",
                    fixWallBlockedCount,
                    particles.size()));
        }
    }

    /**
     * Updates the heading-bias calibration from the innovation residual.
     *
     * <p>This method learns gyroscope bias by observing the direction difference between
     * the predicted step displacement and the absolute-fix correction. It uses a cross-product
     * test to determine if the absolute fix is left or right of the step, then applies
     * a bounded adaptive update to {@code headingBiasRad}.</p>
     *
     * <p>The function only updates when:</p>
     * <ul>
     *   <li>Recent step displacement exceeds {@link #ORIENTATION_BIAS_MIN_STEP_M}</li>
     *   <li>Innovation residual magnitude exceeds {@link #ORIENTATION_BIAS_MIN_INNOVATION_M}</li>
     * </ul>
     *
     * <p>The bias delta is clamped per-step to {@link #ORIENTATION_BIAS_MAX_STEP_RAD} and
     * the absolute bias is constrained to ±{@link #ORIENTATION_BIAS_MAX_ABS_RAD}.</p>
     *
     * @param innovationEast  residual in East (meters), fix_east - predicted_mean_east
     * @param innovationNorth residual in North (meters), fix_north - predicted_mean_north
     * @param source          measurement source ("GNSS" or "WiFi") for logging
     */
    private void updateOrientationBiasFromInnovation(double innovationEast,
                                                     double innovationNorth,
                                                     String source) {
        if (recentStepMotionMeters < ORIENTATION_BIAS_MIN_STEP_M) {
            return;
        }

        double innovationNorm = Math.hypot(innovationEast, innovationNorth);
        if (innovationNorm < ORIENTATION_BIAS_MIN_INNOVATION_M) {
            return;
        }

        double stepNorm2 = recentStepEastMeters * recentStepEastMeters
                + recentStepNorthMeters * recentStepNorthMeters;
        if (stepNorm2 < 1e-6) {
            return;
        }

        // cross(step, innovation) tells whether absolute fixes lie left/right of step heading.
        double cross = recentStepEastMeters * innovationNorth
                - recentStepNorthMeters * innovationEast;
        double rawBiasDelta = ORIENTATION_BIAS_LEARN_RATE * (cross / stepNorm2);
        double boundedBiasDelta = clamp(rawBiasDelta,
                -ORIENTATION_BIAS_MAX_STEP_RAD,
                ORIENTATION_BIAS_MAX_STEP_RAD);

        headingBiasRad = clamp(headingBiasRad + boundedBiasDelta,
                -ORIENTATION_BIAS_MAX_ABS_RAD,
                ORIENTATION_BIAS_MAX_ABS_RAD);

        if (DEBUG_LOGS) {
            Log.d(TAG, String.format(Locale.US,
                    "HeadingBias update src=%s innovation=(%.2fE,%.2fN)|%.2fm step=(%.2fE,%.2fN)|%.2fm deltaDeg=%.2f biasDeg=%.2f",
                    source,
                    innovationEast,
                    innovationNorth,
                    innovationNorm,
                    recentStepEastMeters,
                    recentStepNorthMeters,
                    recentStepMotionMeters,
                    Math.toDegrees(boundedBiasDelta),
                    Math.toDegrees(headingBiasRad)));
        }
    }

    /**
     * Learns heading bias from consecutive WiFi fixes, even when no hard snap is triggered.
     */
    private void updateOrientationBiasFromWifiPattern(double wifiEast,
                                                      double wifiNorth,
                                                      int wifiFloor) {
        if (!hasLastWifiFix || wifiFloor != lastWifiFixFloor) {
            lastWifiFixEastMeters = wifiEast;
            lastWifiFixNorthMeters = wifiNorth;
            lastWifiFixFloor = wifiFloor;
            hasLastWifiFix = true;
            return;
        }

        double wifiDeltaEast = wifiEast - lastWifiFixEastMeters;
        double wifiDeltaNorth = wifiNorth - lastWifiFixNorthMeters;
        double wifiMoveMeters = Math.hypot(wifiDeltaEast, wifiDeltaNorth);

        lastWifiFixEastMeters = wifiEast;
        lastWifiFixNorthMeters = wifiNorth;

        if (wifiMoveMeters < WIFI_PATTERN_HEADING_MIN_MOVE_M
                || recentStepMotionMeters < ORIENTATION_BIAS_MIN_STEP_M) {
            return;
        }

        double stepNorm2 = recentStepEastMeters * recentStepEastMeters
                + recentStepNorthMeters * recentStepNorthMeters;
        if (stepNorm2 < 1e-6) {
            return;
        }

        double cross = recentStepEastMeters * wifiDeltaNorth
                - recentStepNorthMeters * wifiDeltaEast;
        double rawBiasDelta = ORIENTATION_BIAS_WIFI_PATTERN_LEARN_RATE * (cross / stepNorm2);
        double boundedBiasDelta = clamp(rawBiasDelta,
                -ORIENTATION_BIAS_WIFI_PATTERN_MAX_STEP_RAD,
                ORIENTATION_BIAS_WIFI_PATTERN_MAX_STEP_RAD);

        headingBiasRad = clamp(headingBiasRad + boundedBiasDelta,
                -ORIENTATION_BIAS_MAX_ABS_RAD,
                ORIENTATION_BIAS_MAX_ABS_RAD);

        if (DEBUG_LOGS) {
            Log.d(TAG, String.format(Locale.US,
                    "WiFi-pattern heading move=(%.2fE,%.2fN)|%.2fm step=(%.2fE,%.2fN)|%.2fm deltaDeg=%.2f biasDeg=%.2f",
                    wifiDeltaEast,
                    wifiDeltaNorth,
                    wifiMoveMeters,
                    recentStepEastMeters,
                    recentStepNorthMeters,
                    recentStepMotionMeters,
                    Math.toDegrees(boundedBiasDelta),
                    Math.toDegrees(headingBiasRad)));
        }
    }

    /**
     * Recalculates heading bias at WiFi hard-snap time using previous WiFi fix direction.
     */
    private void recalculateOrientationBiasOnWifiSnap(double wifiEast,
                                                      double wifiNorth,
                                                      int wifiFloor,
                                                      double innovationEast,
                                                      double innovationNorth) {
        if (!hasLastWifiFix || wifiFloor != lastWifiFixFloor) {
            lastWifiFixEastMeters = wifiEast;
            lastWifiFixNorthMeters = wifiNorth;
            lastWifiFixFloor = wifiFloor;
            hasLastWifiFix = true;
            return;
        }

        HeadingMetric wifiHeadingMetric = buildWifiSnapHeadingMetric(wifiEast, wifiNorth, wifiFloor);

        lastWifiFixEastMeters = wifiEast;
        lastWifiFixNorthMeters = wifiNorth;

        if (wifiHeadingMetric == null) {
            return;
        }

        // Publish hard heading override for UI orientation at snap time.
        snapOrientationOverrideRad = Math.atan2(wifiHeadingMetric.east, wifiHeadingMetric.north);
        hasSnapOrientationOverride = true;

        double wifiHeadingRad = Math.atan2(wifiHeadingMetric.east, wifiHeadingMetric.north);
        double previousBiasRad = headingBiasRad;

        if (hasLatestRawHeading) {
            // Direct snap alignment: corrected heading = raw heading + bias -> WiFi heading.
            double desiredBiasRad = normalizeAngleRad(wifiHeadingRad - latestRawHeadingRad);
            headingBiasRad = clamp(desiredBiasRad,
                    -ORIENTATION_BIAS_MAX_ABS_RAD,
                    ORIENTATION_BIAS_MAX_ABS_RAD);
        } else {
            // Fallback when no recent raw heading is available.
            double refEast;
            double refNorth;
            if (recentStepMotionMeters >= ORIENTATION_BIAS_MIN_STEP_M) {
                refEast = recentStepEastMeters;
                refNorth = recentStepNorthMeters;
            } else {
                refEast = innovationEast;
                refNorth = innovationNorth;
            }

            double refNorm2 = refEast * refEast + refNorth * refNorth;
            if (refNorm2 < 1e-6) {
                return;
            }
            double refHeadingRad = Math.atan2(refEast, refNorth);
            double desiredBiasRad = normalizeAngleRad(wifiHeadingRad - refHeadingRad);
            headingBiasRad = clamp(desiredBiasRad,
                    -ORIENTATION_BIAS_MAX_ABS_RAD,
                    ORIENTATION_BIAS_MAX_ABS_RAD);
        }
        double appliedBiasDeltaRad = normalizeAngleRad(headingBiasRad - previousBiasRad);

        if (DEBUG_LOGS) {
            Log.d(TAG, String.format(Locale.US,
                    "WiFi snap heading recalc metric=(%.2fE,%.2fN)|%.2fm hist=%d rel=%.2f rawHeadingDeg=%.2f deltaDeg=%.2f biasDeg=%.2f",
                    wifiHeadingMetric.east,
                    wifiHeadingMetric.north,
                    wifiHeadingMetric.magnitude,
                    wifiHeadingMetric.samples,
                    wifiHeadingMetric.reliability,
                    Math.toDegrees(hasLatestRawHeading ? latestRawHeadingRad : Double.NaN),
                    Math.toDegrees(appliedBiasDeltaRad),
                    Math.toDegrees(headingBiasRad)));
        }
    }

    private void recordWifiFix(double wifiEast, double wifiNorth, int wifiFloor) {
        wifiFixHistory.add(new WifiFixSample(wifiEast, wifiNorth, wifiFloor));
        while (wifiFixHistory.size() > WIFI_SNAP_HISTORY_POINTS) {
            wifiFixHistory.remove(0);
        }
    }

    private static final class HeadingMetric {
        final double east;
        final double north;
        final double magnitude;
        final int samples;
        final double reliability;

        HeadingMetric(double east, double north, double magnitude, int samples, double reliability) {
            this.east = east;
            this.north = north;
            this.magnitude = magnitude;
            this.samples = samples;
            this.reliability = reliability;
        }
    }

    private HeadingMetric buildWifiSnapHeadingMetric(double snappedEast,
                                                     double snappedNorth,
                                                     int wifiFloor) {
        double sumUnitEast = 0.0;
        double sumUnitNorth = 0.0;
        double sumRawEast = 0.0;
        double sumRawNorth = 0.0;
        int used = 0;

        for (int i = wifiFixHistory.size() - 1;
             i >= 0 && used < WIFI_SNAP_HISTORY_POINTS;
             i--) {
            WifiFixSample sample = wifiFixHistory.get(i);
            if (sample.floor != wifiFloor) {
                continue;
            }

            double vEast = snappedEast - sample.east;
            double vNorth = snappedNorth - sample.north;
            double vMag = Math.hypot(vEast, vNorth);
            if (vMag < WIFI_SNAP_DIRECTION_MIN_MOVE_M) {
                continue;
            }

            sumUnitEast += vEast / vMag;
            sumUnitNorth += vNorth / vMag;
            sumRawEast += vEast;
            sumRawNorth += vNorth;
            used++;
        }

        if (used < WIFI_SNAP_MIN_HISTORY_POINTS) {
            return null;
        }

        double meanEast = sumRawEast / used;
        double meanNorth = sumRawNorth / used;
        double meanMag = Math.hypot(meanEast, meanNorth);
        if (meanMag < WIFI_SNAP_MIN_VECTOR_M) {
            return null;
        }

        double concentration = Math.hypot(sumUnitEast, sumUnitNorth) / used;
        return new HeadingMetric(meanEast, meanNorth, meanMag, used, concentration);
    }

    /**
     * Returns and clears a pending snap-orientation override, if any.
     */
    public synchronized Double consumeSnapOrientationOverrideRad() {
        if (!hasSnapOrientationOverride) {
            return null;
        }
        hasSnapOrientationOverride = false;
        return snapOrientationOverrideRad;
    }

    /** Returns the current PDR heading-bias correction (radians). */
    public synchronized double getHeadingBiasRad() {
        return headingBiasRad;
    }

    /** Updates the latest raw sensor heading (radians, Android azimuth frame). */
    public synchronized void updateRawHeadingRad(float rawHeadingRad) {
        latestRawHeadingRad = rawHeadingRad;
        hasLatestRawHeading = true;
    }

    private void initParticlesAtOrigin(int initialFloor) {
        particles.clear();
        double w = 1.0 / PARTICLE_COUNT;
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            Particle p = new Particle();
            p.xEast = random.nextGaussian() * INIT_STD_M;
            p.yNorth = random.nextGaussian() * INIT_STD_M;
            p.floor = initialFloor;
            p.weight = w;
            p.wallPenaltyScore = 0.0;
            particles.add(p);
        }
    }

    /** Re-seeds particles around the latest absolute measurement when weights collapse. */
    private void reinitializeAroundMeasurement(double x, double y, int floor) {
        particles.clear();
        double w = 1.0 / PARTICLE_COUNT;
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            Particle p = new Particle();
            // Try a few candidate positions to avoid spawning inside a wall
            double candidateX = x;
            double candidateY = y;
            for (int attempt = 0; attempt < 6; attempt++) {
                double cx = x + random.nextGaussian() * INIT_STD_M;
                double cy = y + random.nextGaussian() * INIT_STD_M;
                if (!crossesWall(floor, x, y, cx, cy)) {
                    candidateX = cx;
                    candidateY = cy;
                    break;
                }
                // Last attempt: fall back to exact measurement point
            }
            p.xEast = candidateX;
            p.yNorth = candidateY;
            p.floor = floor;
            p.weight = w;
            p.wallPenaltyScore = 0.0;
            particles.add(p);
        }
    }

    private double computeEffectiveSampleSize() {
        double sumSquared = 0.0;
        for (Particle p : particles) {
            sumSquared += p.weight * p.weight;
        }
        if (sumSquared <= 0.0) {
            return 0.0;
        }
        return 1.0 / sumSquared;
    }

    /**
     * Performs systematic resampling to recover particle diversity when effective count drops.
     *
     * <p>This method implements the systematic resampling step of the Sequential Importance
     * Resampling (SIR) particle filter. When particle weights become skewed (many particles
     * with negligible weight), resampling duplicates high-weight particles and discards
     * low-weight ones, restoring the effective particle count and preventing weight collapse.</p>
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Compute cumulative distribution function (CDF) of particle weights</li>
     *   <li>Generate evenly-spaced quantile positions u + m/N (deterministic: reduces variance)</li>
     *   <li>For each quantile, find the corresponding particle via CDF lookup</li>
     *   <li>Copy selected particles with reset uniform weight (1/N)</li>
     *   <li>Wall penalty scores are preserved from source particles</li>
     * </ol>
     *
     * <p>After resampling, all particles have equal weight 1/N. The filter then calls
     * {@link #roughenParticles()} to add process noise and prevent duplicate collapse.</p>
     */
    private void resampleSystematic() {
        List<Particle> resampled = new ArrayList<>(PARTICLE_COUNT);
        double step = 1.0 / PARTICLE_COUNT;
        double u = random.nextDouble() * step;
        double cdf = particles.get(0).weight;
        int idx = 0;

        for (int m = 0; m < PARTICLE_COUNT; m++) {
            double threshold = u + m * step;
            while (threshold > cdf && idx < particles.size() - 1) {
                idx++;
                cdf += particles.get(idx).weight;
            }

            Particle src = particles.get(idx);
            Particle copy = new Particle();
            copy.xEast = src.xEast;
            copy.yNorth = src.yNorth;
            copy.floor = src.floor;
            copy.weight = step;
            copy.wallPenaltyScore = src.wallPenaltyScore;
            resampled.add(copy);
        }

        particles.clear();
        particles.addAll(resampled);
    }

    /**
     * Adds process noise to particles after resampling to prevent collapse.
     *
     * <p>When systematic resampling duplicates high-weight particles, identical copies
     * can cause divergence (filter collapse). This function perturbs each particle's
     * position by Gaussian noise (std {@link #ROUGHEN_STD_M}) to restore diversity.</p>
     *
     * <p>Noise is applied only if the perturbed position does not cross a mapped wall.
     * If roughening would violate wall constraints, the particle remains at its
     * resampled position.</p>
     */
    private void roughenParticles() {
        for (Particle p : particles) {
            double oldX = p.xEast;
            double oldY = p.yNorth;
            double newX = oldX + random.nextGaussian() * ROUGHEN_STD_M;
            double newY = oldY + random.nextGaussian() * ROUGHEN_STD_M;
            if (!crossesWall(p.floor, oldX, oldY, newX, newY)) {
                p.xEast = newX;
                p.yNorth = newY;
            }
            // If roughening would cross a wall, leave particle in place
        }
    }

    /**
     * Applies heading-bias correction by rotating a step vector.
     *
     * <p>Applies a 2D rotation matrix by angle {@code angleRad}. Used to correct
     * PDR step displacements when the gyroscope has a known systematic bias relative
     * to magnetic north (as learned from absolute-fix innovations).</p>
     *
     * <p>Formula: [rotated_east; rotated_north] = R(angle) · [east; north]
     * where R is the standard 2D CCW rotation matrix.</p>
     *
     * @param east      East component of step (meters)
     * @param north     North component of step (meters)
     * @param angleRad  rotation angle in radians (positive = CCW)
     * @return array [rotated_east, rotated_north]
     */
    private static double[] rotateVector(double east, double north, double angleRad) {
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);
        double rotatedEast = east * cos - north * sin;
        double rotatedNorth = east * sin + north * cos;
        return new double[]{rotatedEast, rotatedNorth};
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double normalizeAngleRad(double angleRad) {
        double result = angleRad;
        while (result > Math.PI) result -= 2.0 * Math.PI;
        while (result < -Math.PI) result += 2.0 * Math.PI;
        return result;
    }

    /**
     * Projects WGS84 lat/lon coordinates to local East/North meters.
     *
     * <p>Establishes a local tangent plane at {@code (anchorLatDeg, anchorLonDeg)}
     * and converts global coordinates to meters in that frame. This linearization
     * is accurate for small areas (< 1 km2) typical of indoor positioning scenarios.</p>
     *
     * <p>Projection:</p>
     * <ul>
     *   <li>East (meters) = Deltalon · cos(lat0) · R_earth</li>
     *   <li>North (meters) = Deltalat · R_earth</li>
     * </ul>
     *
     * @param latDeg latitude in degrees
     * @param lonDeg longitude in degrees
     * @return [east_meters, north_meters] in local frame
     */
    private double[] toLocal(double latDeg, double lonDeg) {
        double lat0Rad = Math.toRadians(anchorLatDeg);
        double dLat = Math.toRadians(latDeg - anchorLatDeg);
        double dLon = Math.toRadians(lonDeg - anchorLonDeg);

        double east = dLon * EARTH_RADIUS_M * Math.cos(lat0Rad);
        double north = dLat * EARTH_RADIUS_M;
        return new double[]{east, north};
    }

    /**
     * Inverse projection: converts local East/North meters back to WGS84 lat/lon.
     *
     * <p>Reverses the local tangent plane transformation performed by {@link #toLocal}.
     * Inverts the linearized projection to recover global coordinates.</p>
     *
     * <p>Inverse projection:</p>
     * <ul>
     *   <li>Δlat (degrees) = north_meters / R_earth</li>
     *   <li>Δlon (degrees) = east_meters / (R_earth · cos(lat0))</li>
     * </ul>
     *
     * @param eastMeters  position in East direction (meters in local frame)
     * @param northMeters position in North direction (meters in local frame)
     * @return WGS84 LatLng coordinate
     */
    private LatLng toLatLng(double eastMeters, double northMeters) {
        double lat0Rad = Math.toRadians(anchorLatDeg);

        double dLat = northMeters / EARTH_RADIUS_M;
        double cosLat = Math.cos(lat0Rad);
        if (Math.abs(cosLat) < 1e-9) {
            cosLat = 1e-9;
        }
        double dLon = eastMeters / (EARTH_RADIUS_M * cosLat);

        double lat = anchorLatDeg + Math.toDegrees(dLat);
        double lon = anchorLonDeg + Math.toDegrees(dLon);

        return new LatLng(lat, lon);
    }

    /**
     * Attempts to slide a particle along the wall it would cross instead of stopping it dead.
     * Projects the intended displacement onto the wall's direction vector and applies the
     * parallel component only, so particles continue moving along corridors rather than
     * piling up against walls.
     *
     * @return new [x, y] after sliding, or null if sliding is not possible
     */
    private double[] trySlideAlongWall(int floor, double x0, double y0, double cx, double cy) {
        FloorConstraint fc = floorConstraints.get(floor);
        if (fc == null || fc.walls.isEmpty()) return null;

        Point2D start = new Point2D(x0, y0);
        Point2D end   = new Point2D(cx, cy);

        for (Segment wall : fc.walls) {
            if (!segmentsIntersect(start, end, wall.a, wall.b)) continue;

            double wallDx = wall.b.x - wall.a.x;
            double wallDy = wall.b.y - wall.a.y;
            double wallLen2 = wallDx * wallDx + wallDy * wallDy;
            if (wallLen2 < 1e-9) continue;

            // Project movement onto wall direction
            double moveDx = cx - x0;
            double moveDy = cy - y0;
            double dot    = moveDx * wallDx + moveDy * wallDy;
            double scale  = dot / wallLen2;

            // Apply 70% of the parallel component to leave a small gap from the wall
            double slideX = x0 + scale * wallDx * 0.70;
            double slideY = y0 + scale * wallDy * 0.70;

            // Discard negligible slides and slides that cross another wall
            if (Math.hypot(slideX - x0, slideY - y0) < 0.05) return null;
            if (!crossesWall(floor, x0, y0, slideX, slideY)) {
                return new double[]{slideX, slideY};
            }

            break; // Sliding is also blocked — fall through to frozen
        }
        return null;
    }

    /**
     * Checks if a motion segment crosses any mapped wall on a floor.
     *
     * <p>Convenience wrapper that returns true if {@link #firstWallIntersection} finds
     * any wall hit, false otherwise. Used for constraint validation during particle
     * prediction and during position roughening after resampling.</p>
     *
     * @param floor logical floor ID
     * @param x0    starting East position (meters)
     * @param y0    starting North position (meters)
     * @param x1    ending East position (meters)
     * @param y1    ending North position (meters)
     * @return true if segment crosses a wall, false if free path
     */
    private boolean crossesWall(int floor, double x0, double y0, double x1, double y1) {
        return firstWallIntersection(floor, x0, y0, x1, y1) != null;
    }

    /**
     * Finds the first wall intersection along a particle's motion segment.
     *
     * <p>This method searches all mapped wall segments on a floor for the earliest
     * intersection along the motion vector from (x0, y0) to (x1, y1). It returns
     * the wall segment and the progress parameter t ∈ [0,1] where intersection occurs.</p>
     *
     * <p>Used for:</p>
     * <ul>
     *   <li>Wall collision detection during PDR prediction</li>
     *   <li>Blocking or sliding particles that would cross walls</li>
     *   <li>Height-map aware trajectory constraint validation</li>
     * </ul>
     *
     * @param floor      logical floor ID to query constraints
     * @param x0         starting East position (meters in local frame)
     * @param y0         starting North position (meters in local frame)
     * @param x1         candidate East position (meters in local frame)
     * @param y1         candidate North position (meters in local frame)
     * @return {@link WallIntersection} with wall segment and smallest t value, or null if no hit
     */
    private WallIntersection firstWallIntersection(int floor, double x0, double y0, double x1, double y1) {
        FloorConstraint fc = floorConstraints.get(floor);
        if (fc == null || fc.walls.isEmpty()) {
            return null;
        }

        Point2D a = new Point2D(x0, y0);
        Point2D b = new Point2D(x1, y1);
        WallIntersection best = null;
        for (Segment wall : fc.walls) {
            if (segmentsIntersect(a, b, wall.a, wall.b)) {
                double t = intersectionProgress(a, b, wall.a, wall.b);
                if (Double.isNaN(t)) {
                    t = 1.0;
                }
                if (best == null || t < best.t) {
                    best = new WallIntersection(wall, t);
                }
            }
        }
        return best;
    }

    /**
     * Validates whether a floor transition is plausible at the particle position.
     *
     * <p>Floor transitions (via stairs or elevators) are only allowed at mapped connector
     * locations. This method checks whether the particle's current position is near
     * a valid connector (stairs or lift) on the current floor.</p>
     *
     * <p>Logic:</p>
     * <ul>
     *   <li>If elevator is detected and horizontal motion is minimal (≤ {@link #LIFT_HORIZONTAL_MAX_M}),
     *       require proximity to a mapped lift</li>
     *   <li>Otherwise, require proximity to stairs (within {@link #CONNECTOR_RADIUS_M})</li>
     *   <li>If no connectors are mapped for the floor, allow transition (fail-open)</li>
     * </ul>
     *
     * @param floor         current logical floor ID
     * @param x             current East position (meters in local frame)
     * @param y             current North position (meters in local frame)
     * @param elevatorLikely true if barometer and motion cues suggest elevator (vertical-only)
     * @return true if transition is allowed, false if blocked by connector constraint
     */
    private boolean canUseConnector(int floor, double x, double y, boolean elevatorLikely) {
        if (floorConstraints.isEmpty()) {
            return true;
        }

        FloorConstraint fc = floorConstraints.get(floor);
        if (fc == null) {
            return true;
        }

        if (elevatorLikely && recentStepMotionMeters <= LIFT_HORIZONTAL_MAX_M) {
            if (!fc.lifts.isEmpty()) {
                return isNearAny(fc.lifts, x, y, CONNECTOR_RADIUS_M);
            }
            return false;
        }

        if (!fc.stairs.isEmpty()) {
            return isNearAny(fc.stairs, x, y, CONNECTOR_RADIUS_M);
        }

        // If stairs are not mapped for this floor, do not hard-block transitions.
        return true;
    }

    private boolean isNearAny(List<Point2D> points, double x, double y, double radius) {
        double r2 = radius * radius;
        for (Point2D p : points) {
            double dx = p.x - x;
            double dy = p.y - y;
            if (dx * dx + dy * dy <= r2) {
                return true;
            }
        }
        return false;
    }

    /**
     * Converts a polyline from FloorPlan API into local-frame wall segments.
     *
     * <p>Takes a list of WGS84 LatLng points (forming a wall boundary) and converts
     * them to sequential segments in the local East/North coordinate system.
     * Each pair of consecutive points becomes a {@link Segment} for wall intersection tests.</p>
     *
     * @param points list of LatLng coordinates (≥2 required for a valid wall)
     * @param out    output list to accumulate converted segments
     */
    private void addWallSegments(List<LatLng> points, List<Segment> out) {
        if (points == null || points.size() < 2) {
            return;
        }
        for (int i = 0; i < points.size() - 1; i++) {
            Point2D a = toLocalPoint(points.get(i));
            Point2D b = toLocalPoint(points.get(i + 1));
            if (a != null && b != null) {
                out.add(new Segment(a, b));
            }
        }
    }

    /**
     * Converts a single WGS84 point to local East/North coordinates.
     *
     * @param latLng WGS84 coordinate (null safe)
     * @return local Point2D, or null if input is null
     */
    private Point2D toLocalPoint(LatLng latLng) {
        if (latLng == null) {
            return null;
        }
        double[] local = toLocal(latLng.latitude, latLng.longitude);
        return new Point2D(local[0], local[1]);
    }

    /**
     * Computes the centroid of a connector feature (stairs/lift) in local coordinates.
     *
     * <p>Takes a polyline (list of LatLng points) representing a stair or lift area,
     * converts each point to the local frame, and returns their arithmetic mean.
     * Centroid is used as the contact point for floor-transition validation.</p>
     *
     * @param points LatLng coordinates of the connector boundary
     * @return centroid as local Point2D, or null if pointlist is empty or all out-of-bounds
     */
    private Point2D toLocalCentroid(List<LatLng> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }

        double sx = 0.0;
        double sy = 0.0;
        int count = 0;
        for (LatLng latLng : points) {
            Point2D p = toLocalPoint(latLng);
            if (p == null) {
                continue;
            }
            sx += p.x;
            sy += p.y;
            count++;
        }

        if (count == 0) {
            return null;
        }
        return new Point2D(sx / count, sy / count);
    }

    /**
     * Returns floor list sorted by logical floor when labels are parseable.
     * Keeping this ordering aligned with indoor map rendering prevents constraints
     * from being attached to the wrong logical floor.
     */
    private List<FloorplanApiClient.FloorShapes> normalizeFloorOrder(
            List<FloorplanApiClient.FloorShapes> input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        List<FloorplanApiClient.FloorShapes> ordered = new ArrayList<>(input);
        Collections.sort(ordered, (a, b) -> {
            Integer floorA = parseLogicalFloorFromDisplayName(a == null ? null : a.getDisplayName());
            Integer floorB = parseLogicalFloorFromDisplayName(b == null ? null : b.getDisplayName());

            if (floorA != null && floorB != null) {
                return Integer.compare(floorA, floorB);
            }
            if (floorA != null) {
                return -1;
            }
            if (floorB != null) {
                return 1;
            }
            return 0;
        });
        return ordered;
    }

    /** Maps floor display labels (e.g. LG, G, 1, F2) to numeric logical floors. */
    private Integer parseLogicalFloor(FloorplanApiClient.FloorShapes floor, int index) {
        if (floor == null) {
            return null;
        }

        Integer parsed = parseLogicalFloorFromDisplayName(floor.getDisplayName());
        return parsed != null ? parsed : index;
    }

    private Integer parseLogicalFloorFromDisplayName(String displayName) {
        if (displayName == null) {
            return null;
        }

        String normalized = displayName.trim().toUpperCase(Locale.US).replace(" ", "");
        if (normalized.isEmpty()) {
            return null;
        }

        if ("LG".equals(normalized) || "L".equals(normalized)
                || "LOWERGROUND".equals(normalized)) {
            return -1;
        }
        if ("G".equals(normalized) || "GF".equals(normalized)
                || "GROUND".equals(normalized) || "GROUNDFLOOR".equals(normalized)) {
            return 0;
        }

        if (normalized.startsWith("F") || normalized.startsWith("L")) {
            normalized = normalized.substring(1);
        }

        Matcher matcher = FLOOR_NUMBER_PATTERN.matcher(normalized);
        if (matcher.matches()) {
            try {
                return Integer.parseInt(normalized);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    /**
     * Tests whether a WGS84 point lies inside a polygon using the ray-casting algorithm.
     *
     * <p>Counts the number of times a ray from the point crosses polygon edges.
     * If the count is odd, the point is inside; if even (including 0), outside.
     * Used to determine which building (outline) contains the user's current position.</p>
     *
     * @param point   WGS84 coordinate to test
     * @param polygon ordered list of WGS84 vertices forming a closed polygon
     * @return true if point is inside polygon, false otherwise
     */
    private boolean pointInPolygon(LatLng point, List<LatLng> polygon) {
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            double xi = polygon.get(i).longitude;
            double yi = polygon.get(i).latitude;
            double xj = polygon.get(j).longitude;
            double yj = polygon.get(j).latitude;

            boolean intersect = ((yi > point.latitude) != (yj > point.latitude))
                    && (point.longitude
                    < (xj - xi) * (point.latitude - yi) / (yj - yi + 1e-12) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * Detects whether two line segments intersect, with robust collinearity handling.
     *
     * <p>Uses the orientation method to classify point configurations. Two segments
     * intersect if the endpoints of one segment are on opposite sides of the other
     * segment's line (orientation test), OR if they are collinear and overlapping
     * (onSegment bounding box test).</p>
     *
     * <p>Used for wall intersection detection and floor-transition validation.</p>
     *
     * @param p1 segment 1 start
     * @param p2 segment 1 end
     * @param q1 segment 2 start
     * @param q2 segment 2 end
     * @return true if segments intersect (including touching at endpoints)
     */
    private boolean segmentsIntersect(Point2D p1, Point2D p2, Point2D q1, Point2D q2) {
        double o1 = orientation(p1, p2, q1);
        double o2 = orientation(p1, p2, q2);
        double o3 = orientation(q1, q2, p1);
        double o4 = orientation(q1, q2, p2);

        if ((o1 > 0) != (o2 > 0) && (o3 > 0) != (o4 > 0)) {
            return true;
        }

        return (Math.abs(o1) < 1e-9 && onSegment(p1, q1, p2))
                || (Math.abs(o2) < 1e-9 && onSegment(p1, q2, p2))
                || (Math.abs(o3) < 1e-9 && onSegment(q1, p1, q2))
                || (Math.abs(o4) < 1e-9 && onSegment(q1, p2, q2));
    }

    /**
     * Computes the orientation of an ordered triplet of points.
     *
     * <p>Returns the signed cross product (b - a) × (c - a):</p>
     * <ul>
     *   <li>&gt; 0: c is left of the vector (a → b) (counter-clockwise)</li>
     *   <li>&lt; 0: c is right of the vector (a → b) (clockwise)</li>
     *   <li>≈ 0: points are collinear</li>
     * </ul>
     *
     * <p>Used by segment intersection tests and point-in-polygon algorithms.</p>
     *
     * @param a first point
     * @param b second point (vector start)
     * @param c third point
     * @return signed cross product magnitude
     */
    private double orientation(Point2D a, Point2D b, Point2D c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    /**
     * Normalizes a 2D vector to unit length.
     *
     * <p>Returns the unit vector in the direction of (x, y). If the norm is
     * negligible (< 1e-9), falls back to unit East vector (1, 0) to avoid
     * division by zero and provide a reasonable default direction.</p>
     *
     * @param x East component
     * @param y North component
     * @return unit vector: (x', y') where x'² + y'² ≈ 1 (or (1, 0) for tiny inputs)
     */
    private Point2D normalize(double x, double y) {
        double norm = Math.hypot(x, y);
        if (norm < 1e-9) {
            return new Point2D(1.0, 0.0);
        }
        return new Point2D(x / norm, y / norm);
    }

    /**
     * Computes the progress parameter t where two lines intersect.
     *
     * <p>Finds the parameter t ∈ [0, 1] along segment AB where it intersects
     * line CD. Uses the parametric form: intersection = A + t·(B - A).
     * If lines are parallel (cross product ≈ 0), returns NaN.</p>
     *
     * <p>Used to determine depth of wall hit for collision response (e.g., how far
     * along the motion vector the particle would hit a wall).</p>
     *
     * @param a segment start (AB)
     * @param b segment end (AB)
     * @param c line start (CD)
     * @param d line end (CD)
     * @return progress t ∈ [0, 1] clamped, or NaN if parallel
     */
    private double intersectionProgress(Point2D a, Point2D b, Point2D c, Point2D d) {
        double rX = b.x - a.x;
        double rY = b.y - a.y;
        double sX = d.x - c.x;
        double sY = d.y - c.y;

        double rxs = rX * sY - rY * sX;
        if (Math.abs(rxs) < 1e-12) {
            return Double.NaN;
        }

        double qmpX = c.x - a.x;
        double qmpY = c.y - a.y;
        double t = (qmpX * sY - qmpY * sX) / rxs;
        return clamp(t, 0.0, 1.0);
    }

    /**
     * Checks if point B lies on segment AC (collinearity bounding box test).
     *
     * <p>Used when points a, b, c are collinear (determined by orientation).
     * This test verifies that B is within the bounding box of segment AC.
     * Includes small tolerance (1e-9) for numerical stability.</p>
     *
     * @param a segment start
     * @param b point to test
     * @param c segment end
     * @return true if b is on segment ac, false otherwise
     */
    private boolean onSegment(Point2D a, Point2D b, Point2D c) {
        return b.x >= Math.min(a.x, c.x) - 1e-9
                && b.x <= Math.max(a.x, c.x) + 1e-9
                && b.y >= Math.min(a.y, c.y) - 1e-9
                && b.y <= Math.max(a.y, c.y) + 1e-9;
    }

    private void logUpdateSummary(double zEast, double zNorth,
                                  double sigmaMeters,
                                  Integer floorHint,
                                  double effectiveBefore,
                                  double effectiveAfter,
                                  boolean resampled) {
        if (!DEBUG_LOGS || particles.isEmpty()) {
            return;
        }

        double minW = Double.POSITIVE_INFINITY;
        double maxW = 0.0;
        double entropy = 0.0;
        double meanX = 0.0;
        double meanY = 0.0;
        int bestFloor = fallbackFloor;
        Map<Integer, Double> floorWeights = new HashMap<>();

        Particle bestParticle = particles.get(0);
        for (Particle p : particles) {
            minW = Math.min(minW, p.weight);
            maxW = Math.max(maxW, p.weight);
            if (p.weight > bestParticle.weight) {
                bestParticle = p;
            }

            if (p.weight > 0.0) {
                entropy -= p.weight * Math.log(p.weight);
            }

            meanX += p.weight * p.xEast;
            meanY += p.weight * p.yNorth;
            floorWeights.put(p.floor, floorWeights.getOrDefault(p.floor, 0.0) + p.weight);
        }

        double bestFloorWeight = -1.0;
        for (Map.Entry<Integer, Double> entry : floorWeights.entrySet()) {
            if (entry.getValue() > bestFloorWeight) {
                bestFloorWeight = entry.getValue();
                bestFloor = entry.getKey();
            }
        }

        double entropyNorm = entropy / Math.log(PARTICLE_COUNT);
        String source = floorHint == null ? "GNSS" : "WiFi";
        Log.i(TAG, String.format(Locale.US,
                "u=%d src=%s z=(%.2fE,%.2fN) sigma=%.2f floorHint=%s Neff=%.1f->%.1f resampled=%s w[min=%.5f max=%.5f Hn=%.3f] mean=(%.2fE,%.2fN) bestP=(%.2fE,%.2fN,f=%d,w=%.5f) bestFloor=%d(%.3f)",
                updateCounter,
                source,
                zEast,
                zNorth,
                sigmaMeters,
                floorHint == null ? "-" : String.valueOf(floorHint),
                effectiveBefore,
                effectiveAfter,
                String.valueOf(resampled),
                minW,
                maxW,
                entropyNorm,
                meanX,
                meanY,
                bestParticle.xEast,
                bestParticle.yNorth,
                bestParticle.floor,
                bestParticle.weight,
                bestFloor,
                bestFloorWeight));
    }

    /**
     * Returns true when the filter has an active mapped building and floor constraints.
     *
     * <p>This is used as a coarse indoor detector for tuning measurement trust.
     * When indoor map constraints are active, GNSS is usually much less reliable than
     * WiFi or PDR, so we downweight it aggressively.</p>
     */
    private boolean isIndoors() {
        return activeBuildingName != null && !floorConstraints.isEmpty();
    }
}
