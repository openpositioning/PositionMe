package com.openpositioning.PositionMe.sensors;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    private static final double WIFI_SIGMA_M = 5.5;
    private static final double INDOOR_GNSS_SIGMA_MULTIPLIER = 2.0;
    private static final double INDOOR_GNSS_MIN_SIGMA_M = 10.0;
    private static final double FLOOR_HINT_MIN_SUPPORT = 0.08;
    private static final double FLOOR_HINT_INJECTION_FRACTION = 0.25;
    private static final double FLOOR_HINT_INJECTION_STD_M = 1.2;
    private static final double EPS = 1e-300;
    private static final double CONNECTOR_RADIUS_M = 3.0;
    private static final double LIFT_HORIZONTAL_MAX_M = 0.50;
    private static final double ORIENTATION_BIAS_LEARN_RATE = 0.20;
    private static final double ORIENTATION_BIAS_MAX_STEP_RAD = Math.toRadians(6.0);
    private static final double ORIENTATION_BIAS_MAX_ABS_RAD = Math.toRadians(45.0);
    private static final double ORIENTATION_BIAS_MIN_STEP_M = 0.35;
    private static final double ORIENTATION_BIAS_MIN_INNOVATION_M = 0.50;

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

    private static final class Particle {
        double xEast;
        double yNorth;
        int floor;
        double weight;
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

    private static final class FloorConstraint {
        final List<Segment> walls = new ArrayList<>();
        final List<Point2D> stairs = new ArrayList<>();
        final List<Point2D> lifts = new ArrayList<>();
    }

    public PositionFusionEngine(float floorHeightMeters) {
        this.floorHeightMeters = floorHeightMeters > 0f ? floorHeightMeters : 4f;
    }

    public synchronized void reset(double latDeg, double lonDeg, int initialFloor) {
        anchorLatDeg = latDeg;
        anchorLonDeg = lonDeg;
        hasAnchor = true;

        fallbackFloor = initialFloor;
        headingBiasRad = 0.0;
        recentStepEastMeters = 0.0;
        recentStepNorthMeters = 0.0;
        recentStepMotionMeters = 0.0;
        initParticlesAtOrigin(initialFloor);
        if (DEBUG_LOGS) {
            Log.i(TAG, String.format(Locale.US,
                    "Reset anchor=(%.7f, %.7f) floor=%d particles=%d headingBiasDeg=%.2f",
                    latDeg, lonDeg, initialFloor, PARTICLE_COUNT,
                    Math.toDegrees(headingBiasRad)));
        }
    }

    public synchronized void updatePdrDisplacement(float dxEastMeters, float dyNorthMeters) {
        if (!hasAnchor || particles.isEmpty()) {
            return;
        }

        recentStepEastMeters = dxEastMeters;
        recentStepNorthMeters = dyNorthMeters;
        recentStepMotionMeters = Math.hypot(dxEastMeters, dyNorthMeters);
        double[] correctedStep = rotateVector(dxEastMeters, dyNorthMeters, headingBiasRad);
        double correctedDx = correctedStep[0];
        double correctedDy = correctedStep[1];
        int blockedByWall = 0;

        for (Particle p : particles) {
            double oldX = p.xEast;
            double oldY = p.yNorth;
            double candidateX = oldX + correctedDx + random.nextGaussian() * PDR_NOISE_STD_M;
            double candidateY = oldY + correctedDy + random.nextGaussian() * PDR_NOISE_STD_M;

            if (crossesWall(p.floor, oldX, oldY, candidateX, candidateY)) {
                blockedByWall++;
                continue;
            }

            p.xEast = candidateX;
            p.yNorth = candidateY;
        }

        if (DEBUG_LOGS) {
            Log.d(TAG, String.format(Locale.US,
                    "Predict dPDRraw=(%.2fE, %.2fN) dPDRcorr=(%.2fE, %.2fN) headingBiasDeg=%.2f noiseStd=%.2f blockedByWall=%d",
                    dxEastMeters, dyNorthMeters,
                    correctedDx, correctedDy,
                    Math.toDegrees(headingBiasRad),
                    PDR_NOISE_STD_M,
                    blockedByWall));
        }
    }

    public synchronized void updateGnss(double latDeg, double lonDeg, float accuracyMeters) {
        double sigma = Math.max(accuracyMeters, 3.0f);
        boolean indoors = activeBuildingName != null && !activeBuildingName.isEmpty();
        if (indoors) {
            sigma = Math.max(sigma * INDOOR_GNSS_SIGMA_MULTIPLIER, INDOOR_GNSS_MIN_SIGMA_M);
        }
        if (DEBUG_LOGS) {
            Log.d(TAG, String.format(Locale.US,
                    "GNSS update lat=%.7f lon=%.7f acc=%.2f sigma=%.2f indoors=%s",
                    latDeg, lonDeg, accuracyMeters, sigma, String.valueOf(indoors)));
        }
        applyAbsoluteFix(latDeg, lonDeg, sigma, null);
    }

    public synchronized void updateWifi(double latDeg, double lonDeg, int wifiFloor) {
        if (DEBUG_LOGS) {
            Log.d(TAG, String.format(Locale.US,
                    "WiFi update lat=%.7f lon=%.7f floor=%d sigma=%.2f",
                    latDeg, lonDeg, wifiFloor, WIFI_SIGMA_M));
        }
        applyAbsoluteFix(latDeg, lonDeg, WIFI_SIGMA_M, wifiFloor);
    }

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
        for (FloorplanApiClient.BuildingInfo b : buildings) {
            List<LatLng> outline = b.getOutlinePolygon();
            if (outline != null && outline.size() >= 3 && pointInPolygon(current, outline)) {
                containing = b;
                break;
            }
        }

        if (containing == null) {
            floorConstraints.clear();
            activeBuildingName = null;
            return;
        }

        if (containing.getName().equals(activeBuildingName) && !floorConstraints.isEmpty()) {
            return;
        }

        Map<Integer, FloorConstraint> parsed = new HashMap<>();
        List<FloorplanApiClient.FloorShapes> floorShapes = containing.getFloorShapesList();
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

        LatLng latLng = toLatLng(meanX, meanY);
        return new PositionFusionEstimate(latLng, bestFloor, true);
    }

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
        if (floorHint != null) {
            injectFloorSupportIfNeeded(floorHint, z[0], z[1]);
        }
        double effectiveBefore = computeEffectiveSampleSize();

        double sigma2 = sigmaMeters * sigmaMeters;
        double maxLogWeight = Double.NEGATIVE_INFINITY;
        double[] logWeights = new double[particles.size()];

        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            double dx = p.xEast - z[0];
            double dy = p.yNorth - z[1];
            double distance2 = dx * dx + dy * dy;
            double logLikelihood = -0.5 * (distance2 / sigma2);

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

        double innovationEast = z[0] - priorMeanEast;
        double innovationNorth = z[1] - priorMeanNorth;
        updateOrientationBiasFromInnovation(innovationEast, innovationNorth, floorHint == null ? "GNSS" : "WiFi");

        updateCounter++;
        logUpdateSummary(z[0], z[1], sigmaMeters, floorHint, effectiveBefore, effectiveN, resampled);
    }

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

    private void injectFloorSupportIfNeeded(int floorHint, double zEast, double zNorth) {
        double floorSupport = floorSupportWeight(floorHint);
        if (floorSupport >= FLOOR_HINT_MIN_SUPPORT) {
            return;
        }

        int injectCount = Math.max(1,
                (int) Math.round(PARTICLE_COUNT * FLOOR_HINT_INJECTION_FRACTION));
        List<Integer> indices = new ArrayList<>(particles.size());
        for (int i = 0; i < particles.size(); i++) {
            indices.add(i);
        }
        indices.sort((a, b) -> Double.compare(particles.get(a).weight, particles.get(b).weight));

        for (int i = 0; i < injectCount && i < indices.size(); i++) {
            Particle p = particles.get(indices.get(i));
            p.floor = floorHint;
            p.xEast = zEast + random.nextGaussian() * FLOOR_HINT_INJECTION_STD_M;
            p.yNorth = zNorth + random.nextGaussian() * FLOOR_HINT_INJECTION_STD_M;
        }

        if (DEBUG_LOGS) {
            Log.i(TAG, String.format(Locale.US,
                    "Floor support injection hint=%d supportBefore=%.3f injectCount=%d",
                    floorHint,
                    floorSupport,
                    injectCount));
        }
    }

    private double floorSupportWeight(int floor) {
        double sum = 0.0;
        for (Particle p : particles) {
            if (p.floor == floor) {
                sum += p.weight;
            }
        }
        return sum;
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
            particles.add(p);
        }
    }

    private void reinitializeAroundMeasurement(double x, double y, int floor) {
        particles.clear();
        double w = 1.0 / PARTICLE_COUNT;
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            Particle p = new Particle();
            p.xEast = x + random.nextGaussian() * INIT_STD_M;
            p.yNorth = y + random.nextGaussian() * INIT_STD_M;
            p.floor = floor;
            p.weight = w;
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
            resampled.add(copy);
        }

        particles.clear();
        particles.addAll(resampled);
    }

    private void roughenParticles() {
        for (Particle p : particles) {
            p.xEast += random.nextGaussian() * ROUGHEN_STD_M;
            p.yNorth += random.nextGaussian() * ROUGHEN_STD_M;
        }
    }

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

    private double[] toLocal(double latDeg, double lonDeg) {
        double lat0Rad = Math.toRadians(anchorLatDeg);
        double dLat = Math.toRadians(latDeg - anchorLatDeg);
        double dLon = Math.toRadians(lonDeg - anchorLonDeg);

        double east = dLon * EARTH_RADIUS_M * Math.cos(lat0Rad);
        double north = dLat * EARTH_RADIUS_M;
        return new double[]{east, north};
    }

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

    private boolean crossesWall(int floor, double x0, double y0, double x1, double y1) {
        FloorConstraint fc = floorConstraints.get(floor);
        if (fc == null || fc.walls.isEmpty()) {
            return false;
        }

        Point2D a = new Point2D(x0, y0);
        Point2D b = new Point2D(x1, y1);
        for (Segment wall : fc.walls) {
            if (segmentsIntersect(a, b, wall.a, wall.b)) {
                return true;
            }
        }
        return false;
    }

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

    private Point2D toLocalPoint(LatLng latLng) {
        if (latLng == null) {
            return null;
        }
        double[] local = toLocal(latLng.latitude, latLng.longitude);
        return new Point2D(local[0], local[1]);
    }

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

    private Integer parseLogicalFloor(FloorplanApiClient.FloorShapes floor, int index) {
        if (floor == null) {
            return null;
        }

        String display = floor.getDisplayName() == null ? "" : floor.getDisplayName().trim();
        String upper = display.toUpperCase(Locale.US);

        if ("LG".equals(upper) || "L".equals(upper)) {
            return -1;
        }
        if ("G".equals(upper) || "GROUND".equals(upper)) {
            return 0;
        }

        try {
            return Integer.parseInt(display);
        } catch (Exception ignored) {
            // Fall back to index mapping when display name is not numeric.
        }

        return index;
    }

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

    private double orientation(Point2D a, Point2D b, Point2D c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

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
}
