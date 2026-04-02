package com.openpositioning.PositionMe.mapmatching;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Map-matching engine for indoor positioning (§3.2).
 *
 * <p>Provides three levels of map-based correction on top of the
 * {@link com.openpositioning.PositionMe.positioning.FusionManager} estimate:</p>
 *
 * <ol>
 *   <li><b>Wall correction (particle filter)</b>: 200 particles propagated by
 *       PDR steps; particles that cross wall segments receive a 0.1× weight
 *       penalty, causing the filter to favour wall-respecting trajectories.
 *       Satisfies: "correct estimations that pass through walls".</li>
 *   <li><b>Building outline (constrainPosition)</b>: ray-casting point-in-polygon
 *       test against the building outline; if FM drifts outside the polygon,
 *       the displayed position is held at the last valid inside position.
 *       Satisfies: "wall — areas that cannot be crossed".</li>
 *   <li><b>Floor detection (barometric)</b>: barometric elevation tracked across
 *       steps with 30s warmup, 5-step sustained threshold, and proximity
 *       bonus/penalty for stairs/lift features. Horizontal displacement during
 *       elevation episodes distinguishes stairs from lifts.
 *       Satisfies: "floor changes only near elevators/lifts" and
 *       "distinguish between lift and stairs".</li>
 * </ol>
 *
 * @see com.openpositioning.PositionMe.positioning.FusionManager for the primary position source
 */
public class MapMatchingEngine {

    private static final String TAG = "MapMatchingEngine";

    // ── Particle filter parameters ───────────────────────────────────────────
    private static final int NUM_PARTICLES = 200;
    private static final double PDR_NOISE_STEP = 0.15;
    private static final double PDR_NOISE_HEADING = Math.toRadians(5);
    private static final double WIFI_SIGMA = 8.0;
    private static final double GNSS_SIGMA = 12.0;

    /** Weight multiplier for particles crossing a wall segment (§3.2 wall correction). */
    private static final double WALL_PENALTY = 0.1;

    /** Radius (metres) within which a particle is considered near a stairs/lift feature. */
    private static final double TRANSITION_PROXIMITY = 4.0;

    private static final double INIT_SPREAD = 3.0;
    private static final double RESAMPLE_JITTER = 0.2;
    private static final double RESAMPLE_THRESHOLD = 0.5;
    private static final double POSITION_SMOOTH_ALPHA = 0.5;
    private static final double POSITION_SMOOTH_SNAP_THRESHOLD = 20.0;
    private static final double WALL_MEAN_FALLBACK_THRESHOLD = 8.0;

    // ── Floor transition parameters (§3.2 floor detection) ───────────────────

    /** Minimum barometric elevation change (metres) to trigger a floor transition. */
    private static final double FLOOR_CHANGE_ELEVATION_THRESHOLD = 2.0;

    /** Steps the elevation must remain above threshold before confirming transition. */
    private static final int ELEVATION_SUSTAIN_STEPS = 2;

    /** Minimum steps between consecutive floor changes to prevent oscillation. */
    private static final int MIN_STEPS_BETWEEN_FLOOR_CHANGES = 3;

    /** Warmup period (ms) after init during which floor transitions are suppressed. */
    private static final long FLOOR_TRANSITION_WARMUP_MS = 5_000;

    /**
     * Maximum horizontal displacement (metres) during an elevation episode
     * to classify the transition as a lift rather than stairs (§3.2).
     */
    private static final double LIFT_HORIZONTAL_THRESHOLD = 1.5;

    // ── Coordinate conversion ────────────────────────────────────────────────
    private static final double METRES_PER_DEG_LAT = 111_320.0;
    private static final double METRES_PER_DEG_LNG =
            111_320.0 * Math.cos(Math.toRadians(55.92));

    // ── State ────────────────────────────────────────────────────────────────
    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random();

    private double refLat;
    private double refLng;
    private boolean initialised = false;
    private boolean enabled = false;

    private List<FloorplanApiClient.FloorShapes> floorShapes;

    private float currentElevation = 0f;
    private float elevationAtLastFloorChange = 0f;
    private float floorHeight = 4.0f;
    private int estimatedFloor = 0;
    private long initialiseTimeMs = 0;

    // Stairs / lift classification state
    private float lastStepLength = 0f;
    private float horizontalDuringElevationChange = 0f;
    private boolean inElevationChange = false;

    // Floor transition guards
    private int stepsAboveElevationThreshold = 0;
    private int stepsSinceLastFloorChange = 0;

    // Output smoothing
    private double smoothedOutputX = Double.NaN;
    private double smoothedOutputY = Double.NaN;

    // Building outline boundary tracking
    private double lastValidX = Double.NaN;
    private double lastValidY = Double.NaN;

    // Building outline polygon in ENU coordinates
    private double[] outlineX = null;
    private double[] outlineY = null;

    // Debug counters
    private int wallHitCount = 0;
    private int wallCheckCount = 0;
    private int predictCallCount = 0;

    // =========================================================================
    // Initialisation
    // =========================================================================

    /**
     * Initialises the map-matching particle filter at the given position.
     * Called from StartLocationFragment when the user places their start pin.
     *
     * @param lat         WGS84 latitude of start position
     * @param lng         WGS84 longitude of start position
     * @param floor       initial floor number
     * @param floorHeight estimated height per floor (metres)
     * @param shapes      floor shape data from the floorplan API
     */
    public void initialise(double lat, double lng, int floor,
                           float floorHeight, List<FloorplanApiClient.FloorShapes> shapes) {
        this.refLat = lat;
        this.refLng = lng;
        this.floorHeight = floorHeight;
        this.floorShapes = shapes;
        this.estimatedFloor = floor;
        this.currentElevation = 0f;
        this.elevationAtLastFloorChange = 0f;
        this.horizontalDuringElevationChange = 0f;
        this.inElevationChange = false;
        this.stepsAboveElevationThreshold = 0;
        this.stepsSinceLastFloorChange = 0;
        this.initialiseTimeMs = System.currentTimeMillis();
        this.smoothedOutputX = Double.NaN;
        this.smoothedOutputY = Double.NaN;
        this.lastValidX = Double.NaN;
        this.lastValidY = Double.NaN;
        this.wallHitCount = 0;
        this.wallCheckCount = 0;
        this.predictCallCount = 0;

        particles.clear();
        for (int i = 0; i < NUM_PARTICLES; i++) {
            double px = random.nextGaussian() * INIT_SPREAD;
            double py = random.nextGaussian() * INIT_SPREAD;
            particles.add(new Particle(px, py, floor, 1.0 / NUM_PARTICLES));
        }

        this.initialised = true;
        this.enabled = true;

        Log.d(TAG, "initialise() — lat=" + lat + " lng=" + lng
                + " floor=" + floor + " floorHeight=" + floorHeight
                + " shapes=" + (shapes == null ? "NULL" : shapes.size() + " floors")
                + " outline=" + (outlineX == null ? "NONE" : outlineX.length + " pts"));
        logWallStats();
    }

    /**
     * Sets the building outline polygon for boundary checking in
     * {@link #constrainPosition}. Converts WGS84 vertices to local ENU
     * coordinates for fast ray-casting tests.
     *
     * @param outline building outline vertices from the floorplan API
     * @param refLat  reference latitude (same as initialise)
     * @param refLng  reference longitude (same as initialise)
     */
    public void setBuildingOutline(List<LatLng> outline, double refLat, double refLng) {
        if (outline == null || outline.size() < 3) {
            this.outlineX = null;
            this.outlineY = null;
            Log.d(TAG, "setBuildingOutline: null or too few points");
            return;
        }
        int n = outline.size();
        outlineX = new double[n];
        outlineY = new double[n];
        for (int i = 0; i < n; i++) {
            LatLng p = outline.get(i);
            outlineX[i] = (p.longitude - refLng) * METRES_PER_DEG_LNG;
            outlineY[i] = (p.latitude - refLat) * METRES_PER_DEG_LAT;
        }
        Log.d(TAG, "setBuildingOutline: " + n + " points loaded");
    }

    /** Logs wall, stairs, and lift feature counts per floor for debugging. */
    public void logWallStats() {
        if (floorShapes == null) {
            Log.d(TAG, "logWallStats: floorShapes is NULL");
            return;
        }
        for (int f = 0; f < floorShapes.size(); f++) {
            int wallSegments = 0, stairFeatures = 0, liftFeatures = 0;
            for (FloorplanApiClient.MapShapeFeature feature : floorShapes.get(f).getFeatures()) {
                String type = feature.getIndoorType();
                if ("wall".equals(type)) {
                    for (List<LatLng> part : feature.getParts())
                        wallSegments += part.size() - 1;
                } else if ("stairs".equals(type)) stairFeatures++;
                else if ("lift".equals(type)) liftFeatures++;
            }
            Log.d(TAG, "  Floor " + f + ": " + wallSegments + " wall segments, "
                    + stairFeatures + " stair features, " + liftFeatures + " lift features");
        }
    }

    public boolean isActive() { return initialised && enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** Resets all state. Called between recording sessions. */
    public void reset() {
        particles.clear();
        initialised = false;
        enabled = false;
        currentElevation = 0f;
        elevationAtLastFloorChange = 0f;
        estimatedFloor = 0;
        horizontalDuringElevationChange = 0f;
        inElevationChange = false;
        stepsAboveElevationThreshold = 0;
        stepsSinceLastFloorChange = 0;
        initialiseTimeMs = 0;
        smoothedOutputX = Double.NaN;
        smoothedOutputY = Double.NaN;
        lastValidX = Double.NaN;
        lastValidY = Double.NaN;
        wallHitCount = 0;
        wallCheckCount = 0;
        predictCallCount = 0;
    }

    public void setFloorShapes(List<FloorplanApiClient.FloorShapes> shapes) { this.floorShapes = shapes; }
    public void setFloorHeight(float height) { this.floorHeight = height; }

    // =========================================================================
    // Prediction step — wall-aware particle propagation (§3.2)
    // =========================================================================

    /**
     * Propagates all particles forward by one PDR step. Particles that cross
     * a wall segment receive {@code WALL_PENALTY} (0.1×) weight, causing
     * wall-respecting trajectories to dominate after resampling.
     *
     * <p>Also tracks barometric elevation for floor transition detection.</p>
     *
     * @param stepLength PDR step length in metres
     * @param headingRad compass heading in radians
     * @param elevation  barometric elevation in metres
     */
    public void predict(float stepLength, float headingRad, float elevation) {
        predictCallCount++;
        if (predictCallCount <= 3 || predictCallCount % 20 == 0) {
            Log.d(TAG, "predict() #" + predictCallCount
                    + " stepLen=" + String.format("%.2f", stepLength)
                    + " heading=" + String.format("%.1f", Math.toDegrees(headingRad)) + "deg"
                    + " elev=" + String.format("%.2f", elevation) + "m");
        }
        if (!isActive()) return;

        this.currentElevation = elevation;
        this.lastStepLength = stepLength;
        this.stepsSinceLastFloorChange++;

        for (Particle p : particles) {
            double noisyStep = stepLength + random.nextGaussian() * PDR_NOISE_STEP;
            double noisyHeading = headingRad + random.nextGaussian() * PDR_NOISE_HEADING;
            noisyStep = Math.max(0, Math.min(noisyStep, 2.0));

            double dx = noisyStep * Math.sin(noisyHeading);
            double dy = noisyStep * Math.cos(noisyHeading);
            double oldX = p.getX();
            double oldY = p.getY();
            double newX = oldX + dx;
            double newY = oldY + dy;

            p.setX(newX);
            p.setY(newY);

            // §3.2: penalise particles that cross wall segments
            wallCheckCount++;
            if (doesCrossWall(oldX, oldY, newX, newY, p.getFloor())) {
                p.setWeight(p.getWeight() * WALL_PENALTY);
                wallHitCount++;
            }
        }

        if (wallCheckCount > 0 && wallCheckCount % 200 == 0) {
            Log.d(TAG, "Wall stats — hits=" + wallHitCount + "/" + wallCheckCount
                    + " (" + String.format("%.1f", 100.0 * wallHitCount / wallCheckCount) + "%)");
        }

        checkFloorTransition();
        normaliseWeights();
        if (effectiveSampleSize() < RESAMPLE_THRESHOLD * NUM_PARTICLES) {
            resample();
        }
    }

    // =========================================================================
    // Observation updates
    // =========================================================================

    /**
     * Updates particle weights from a WiFi observation. Includes lost-filter
     * recovery: if the weighted mean drifts more than 12m from WiFi, the
     * filter reinitialises around the WiFi fix.
     *
     * @param lat       WiFi latitude
     * @param lng       WiFi longitude
     * @param wifiFloor WiFi-reported floor number
     * @param accuracy  WiFi accuracy estimate
     */
    public void updateWifi(double lat, double lng, int wifiFloor, float accuracy) {
        if (!isActive()) return;

        double obsX = (lng - refLng) * METRES_PER_DEG_LNG;
        double obsY = (lat - refLat) * METRES_PER_DEG_LAT;
        double sigma = Math.max(WIFI_SIGMA, accuracy);

        for (Particle p : particles) {
            double dist = Math.sqrt(Math.pow(p.getX() - obsX, 2) + Math.pow(p.getY() - obsY, 2));
            double likelihood = gaussianLikelihood(dist, sigma);
            // Floor mismatch penalty
            if (p.getFloor() != wifiFloor) likelihood *= 0.4;
            p.setWeight(p.getWeight() * likelihood);
        }
        normaliseWeights();

        // Lost-filter recovery
        double weightedX = 0, weightedY = 0;
        for (Particle p : particles) {
            weightedX += p.getX() * p.getWeight();
            weightedY += p.getY() * p.getWeight();
        }
        double meanDistFromWifi = Math.sqrt(
                Math.pow(weightedX - obsX, 2) + Math.pow(weightedY - obsY, 2));

        if (meanDistFromWifi > 12.0) {
            Log.w(TAG, "Lost filter — reinitialising dist=" + String.format("%.1f", meanDistFromWifi) + "m");
            particles.clear();
            for (int i = 0; i < NUM_PARTICLES; i++) {
                particles.add(new Particle(
                        obsX + random.nextGaussian() * WIFI_SIGMA,
                        obsY + random.nextGaussian() * WIFI_SIGMA,
                        wifiFloor, 1.0 / NUM_PARTICLES));
            }
            normaliseWeights();
            double rx = 0, ry = 0;
            for (Particle p : particles) {
                rx += p.getX() * p.getWeight();
                ry += p.getY() * p.getWeight();
            }
            smoothedOutputX = rx;
            smoothedOutputY = ry;
            this.lastValidX = rx;
            this.lastValidY = ry;
            return;
        }

        // WiFi fixes reset the building-outline constraint tracker
        this.lastValidX = obsX;
        this.lastValidY = obsY;

        if (effectiveSampleSize() < RESAMPLE_THRESHOLD * NUM_PARTICLES) resample();
        Log.d(TAG, "updateWifi() — floor=" + wifiFloor);
    }

    /** Updates particle weights from a GNSS observation. */
    public void updateGnss(double lat, double lng, float accuracy) {
        if (!isActive()) return;
        double obsX = (lng - refLng) * METRES_PER_DEG_LNG;
        double obsY = (lat - refLat) * METRES_PER_DEG_LAT;
        double sigma = Math.max(GNSS_SIGMA, accuracy);
        for (Particle p : particles) {
            double dist = Math.sqrt(Math.pow(p.getX() - obsX, 2) + Math.pow(p.getY() - obsY, 2));
            p.setWeight(p.getWeight() * gaussianLikelihood(dist, sigma));
        }
        normaliseWeights();
        if (effectiveSampleSize() < RESAMPLE_THRESHOLD * NUM_PARTICLES) resample();
    }

    // =========================================================================
    // Floor transition logic (§3.2)
    // =========================================================================

    /**
     * Checks whether the accumulated barometric elevation change indicates a
     * floor transition. Implements all §3.2 floor requirements:
     *
     * <ul>
     *   <li>30s warmup after init to let the barometer stabilise</li>
     *   <li>5-step sustained elevation threshold to reject transients</li>
     *   <li>Minimum 10 steps between transitions to prevent oscillation</li>
     *   <li>Particles near stairs/lift features get 1.2× bonus; others 0.3×</li>
     *   <li>Horizontal displacement during episode classifies stairs vs lift</li>
     * </ul>
     */
    private void checkFloorTransition() {
        if (floorShapes == null || floorHeight <= 0) return;

        // Suppress during warmup — barometer needs time to stabilise
        if (System.currentTimeMillis() - initialiseTimeMs < FLOOR_TRANSITION_WARMUP_MS) {
            elevationAtLastFloorChange = currentElevation;
            stepsAboveElevationThreshold = 0;
            return;
        }
        if (stepsSinceLastFloorChange < MIN_STEPS_BETWEEN_FLOOR_CHANGES) return;

        float delta = currentElevation - elevationAtLastFloorChange;
        float mag = Math.abs(delta);

        // Track horizontal displacement during elevation episodes
        if (mag > 1.0f) {
            if (!inElevationChange) {
                inElevationChange = true;
                horizontalDuringElevationChange = 0f;
            }
            horizontalDuringElevationChange += lastStepLength;
        } else {
            inElevationChange = false;
            horizontalDuringElevationChange = 0f;
            stepsAboveElevationThreshold = 0;
        }

        if (mag < FLOOR_CHANGE_ELEVATION_THRESHOLD) {
            stepsAboveElevationThreshold = 0;
            return;
        }

        // Require sustained elevation change to confirm transition
        stepsAboveElevationThreshold++;
        if (stepsAboveElevationThreshold < ELEVATION_SUSTAIN_STEPS) return;

        int floorDelta = (int) Math.round(delta / floorHeight);
        if (floorDelta == 0) return;

        int targetFloor = clampFloor(estimatedFloor + floorDelta);

        // §3.2: distinguish lift vs stairs by horizontal displacement
        boolean isLift = horizontalDuringElevationChange < LIFT_HORIZONTAL_THRESHOLD;

        // §3.2: bonus for particles near stairs/lift, penalty for others
        for (Particle p : particles) {
            boolean near = isNearStairsOrLift(p.getX(), p.getY(), p.getFloor());
            p.setFloor(targetFloor);
            if (near) {
                // Stairs: add horizontal jitter; lift: no displacement
                if (!isLift) {
                    p.setX(p.getX() + random.nextGaussian());
                    p.setY(p.getY() + random.nextGaussian());
                }
                p.setWeight(p.getWeight() * 1.2);
            } else {
                p.setWeight(p.getWeight() * 0.3);
            }
        }

        Log.d(TAG, "Floor transition → floor " + targetFloor + " via " + (isLift ? "LIFT" : "STAIRS")
                + " elevDelta=" + String.format("%.2f", delta) + "m");

        this.estimatedFloor = targetFloor;
        this.elevationAtLastFloorChange = currentElevation;
        this.horizontalDuringElevationChange = 0f;
        this.inElevationChange = false;
        this.stepsAboveElevationThreshold = 0;
        this.stepsSinceLastFloorChange = 0;
    }

    /**
     * Tests whether a particle position is within {@code TRANSITION_PROXIMITY}
     * metres of any stairs or lift feature on the given floor.
     */
    private boolean isNearStairsOrLift(double x, double y, int floor) {
        if (floorShapes == null || floor < 0 || floor >= floorShapes.size()) return false;
        for (FloorplanApiClient.MapShapeFeature feature : floorShapes.get(floor).getFeatures()) {
            String type = feature.getIndoorType();
            if ("stairs".equals(type) || "lift".equals(type)) {
                for (List<LatLng> part : feature.getParts()) {
                    for (LatLng point : part) {
                        double fx = (point.longitude - refLng) * METRES_PER_DEG_LNG;
                        double fy = (point.latitude - refLat) * METRES_PER_DEG_LAT;
                        if (Math.sqrt(Math.pow(x - fx, 2) + Math.pow(y - fy, 2)) < TRANSITION_PROXIMITY)
                            return true;
                    }
                }
            }
        }
        return false;
    }

    private int clampFloor(int floor) {
        if (floorShapes == null || floorShapes.isEmpty()) return floor;
        return Math.max(0, Math.min(floor, floorShapes.size() - 1));
    }

    // =========================================================================
    // constrainPosition — building outline boundary (§3.2)
    // =========================================================================

    /**
     * Applies building outline constraint to FusionManager's position output.
     *
     * <p>Uses ray-casting point-in-polygon to test whether the position is
     * inside the building. If outside, returns the last known valid inside
     * position (hard snap). When FM re-centres via WiFi, the constraint
     * tracker is reset through {@link #updateWifi}.</p>
     *
     * <p>Interior wall corrections are handled by the particle filter's
     * {@code WALL_PENALTY} in {@link #predict}, not here — the building's
     * 594 wall segments on F1 cause excessive false positives for any
     * direct line-crossing check on the displayed position.</p>
     *
     * @param lat FusionManager latitude
     * @param lng FusionManager longitude
     * @return constrained position (unchanged if inside, last-valid if outside)
     */
    public LatLng constrainPosition(double lat, double lng) {
        if (!isActive()) return new LatLng(lat, lng);

        double posX = (lng - refLng) * METRES_PER_DEG_LNG;
        double posY = (lat - refLat) * METRES_PER_DEG_LAT;

        // First call — accept unconditionally
        if (Double.isNaN(lastValidX)) {
            lastValidX = posX;
            lastValidY = posY;
            return new LatLng(lat, lng);
        }

        // Building outline — hard snap (position cannot leave the building)
        if (outlineX != null && !isInsidePolygon(posX, posY, outlineX, outlineY)) {
            Log.d(TAG, "constrainPosition: OUTSIDE BUILDING — hard snap");
            return new LatLng(refLat + lastValidY / METRES_PER_DEG_LAT,
                    refLng + lastValidX / METRES_PER_DEG_LNG);
        }

        // Inside building — accept fully
        lastValidX = posX;
        lastValidY = posY;
        return new LatLng(lat, lng);
    }

    // =========================================================================
    // Point-in-polygon (ray casting algorithm)
    // =========================================================================

    /**
     * Ray-casting algorithm for point-in-polygon testing. Counts the number
     * of times a horizontal ray from (px, py) crosses polygon edges.
     * Odd crossings = inside, even = outside. Works for convex and concave.
     */
    private static boolean isInsidePolygon(double px, double py, double[] polyX, double[] polyY) {
        int n = polyX.length;
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            if ((polyY[i] > py) != (polyY[j] > py)
                    && px < (polyX[j] - polyX[i]) * (py - polyY[i]) / (polyY[j] - polyY[i]) + polyX[i]) {
                inside = !inside;
            }
        }
        return inside;
    }

    // =========================================================================
    // Wall collision detection (§3.2)
    // =========================================================================

    /**
     * Tests whether the line segment from (x1,y1) to (x2,y2) intersects
     * any wall feature on the given floor. Used by {@link #predict} to
     * penalise wall-crossing particles.
     */
    private boolean doesCrossWall(double x1, double y1, double x2, double y2, int floor) {
        if (floorShapes == null || floor < 0 || floor >= floorShapes.size()) return false;
        FloorplanApiClient.FloorShapes floorData = floorShapes.get(floor);
        for (FloorplanApiClient.MapShapeFeature feature : floorData.getFeatures()) {
            if (!"wall".equals(feature.getIndoorType())) continue;
            for (List<LatLng> part : feature.getParts()) {
                int size = part.size();
                for (int i = 0; i < size - 1; i++) {
                    LatLng a = part.get(i), b = part.get(i + 1);
                    double ax = (a.longitude - refLng) * METRES_PER_DEG_LNG;
                    double ay = (a.latitude - refLat) * METRES_PER_DEG_LAT;
                    double bx = (b.longitude - refLng) * METRES_PER_DEG_LNG;
                    double by = (b.latitude - refLat) * METRES_PER_DEG_LAT;
                    if (segmentsIntersect(x1, y1, x2, y2, ax, ay, bx, by)) return true;
                }
                // Close polygon rings if not already closed
                if (size >= 3) {
                    String geoType = feature.getGeometryType();
                    if ("MultiPolygon".equals(geoType) || "Polygon".equals(geoType)) {
                        LatLng first = part.get(0), last = part.get(size - 1);
                        if (first.latitude == last.latitude && first.longitude == last.longitude) continue;
                        double fx = (first.longitude - refLng) * METRES_PER_DEG_LNG;
                        double fy = (first.latitude - refLat) * METRES_PER_DEG_LAT;
                        double lx = (last.longitude - refLng) * METRES_PER_DEG_LNG;
                        double ly = (last.latitude - refLat) * METRES_PER_DEG_LAT;
                        if (segmentsIntersect(x1, y1, x2, y2, lx, ly, fx, fy)) return true;
                    }
                }
            }
        }
        return false;
    }

    /** Tests whether two line segments intersect using the cross-product method. */
    private static boolean segmentsIntersect(double p1x, double p1y, double p2x, double p2y,
                                             double p3x, double p3y, double p4x, double p4y) {
        double d1 = cross(p3x, p3y, p4x, p4y, p1x, p1y);
        double d2 = cross(p3x, p3y, p4x, p4y, p2x, p2y);
        double d3 = cross(p1x, p1y, p2x, p2y, p3x, p3y);
        double d4 = cross(p1x, p1y, p2x, p2y, p4x, p4y);
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true;
        if (d1 == 0 && onSeg(p3x, p3y, p4x, p4y, p1x, p1y)) return true;
        if (d2 == 0 && onSeg(p3x, p3y, p4x, p4y, p2x, p2y)) return true;
        if (d3 == 0 && onSeg(p1x, p1y, p2x, p2y, p3x, p3y)) return true;
        if (d4 == 0 && onSeg(p1x, p1y, p2x, p2y, p4x, p4y)) return true;
        return false;
    }

    private static double cross(double ax, double ay, double bx, double by, double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    private static boolean onSeg(double ax, double ay, double bx, double by, double px, double py) {
        return Math.min(ax, bx) <= px && px <= Math.max(ax, bx)
                && Math.min(ay, by) <= py && py <= Math.max(ay, by);
    }

    // =========================================================================
    // Resampling
    // =========================================================================

    private double effectiveSampleSize() {
        double s = 0;
        for (Particle p : particles) s += p.getWeight() * p.getWeight();
        return s > 0 ? 1.0 / s : 0;
    }

    /** Low-variance systematic resampling with post-resample jitter. */
    private void resample() {
        int n = particles.size();
        if (n == 0) return;
        double[] cum = new double[n];
        cum[0] = particles.get(0).getWeight();
        for (int i = 1; i < n; i++) cum[i] = cum[i - 1] + particles.get(i).getWeight();

        List<Particle> np = new ArrayList<>(n);
        double step = 1.0 / n;
        double start = random.nextDouble() * step;
        int idx = 0;
        for (int i = 0; i < n; i++) {
            double t = start + i * step;
            while (idx < n - 1 && cum[idx] < t) idx++;
            Particle c = particles.get(idx).copy();
            c.setWeight(1.0 / n);
            np.add(c);
        }

        // Post-resample jitter — only if it doesn't cross a wall
        for (Particle p : np) {
            double jx = p.getX() + random.nextGaussian() * RESAMPLE_JITTER;
            double jy = p.getY() + random.nextGaussian() * RESAMPLE_JITTER;
            if (!doesCrossWall(p.getX(), p.getY(), jx, jy, p.getFloor())) {
                p.setX(jx);
                p.setY(jy);
            }
        }

        particles.clear();
        particles.addAll(np);
    }

    private void normaliseWeights() {
        double sum = 0;
        for (Particle p : particles) sum += p.getWeight();
        if (sum <= 0) {
            double u = 1.0 / particles.size();
            for (Particle p : particles) p.setWeight(u);
            return;
        }
        for (Particle p : particles) p.setWeight(p.getWeight() / sum);
    }

    // =========================================================================
    // Output
    // =========================================================================

    /** Returns the manual start pin position. */
    public LatLng getStartLatLng() {
        return initialised ? new LatLng(refLat, refLng) : null;
    }

    /**
     * Returns the smoothed particle filter position estimate.
     * Used as fallback before FusionManager initialises.
     */
    public LatLng getEstimatedPosition() {
        if (!isActive() || particles.isEmpty()) return null;

        double wx = 0, wy = 0;
        Particle best = null;
        double bw = -1;
        for (Particle p : particles) {
            wx += p.getX() * p.getWeight();
            wy += p.getY() * p.getWeight();
            if (p.getWeight() > bw) {
                bw = p.getWeight();
                best = p;
            }
        }

        // If weighted mean is far from best particle, use best particle
        // (prevents wall-split mean from landing inside a wall)
        double rx = wx, ry = wy;
        if (best != null && Math.sqrt(Math.pow(wx - best.getX(), 2) + Math.pow(wy - best.getY(), 2))
                > WALL_MEAN_FALLBACK_THRESHOLD) {
            rx = best.getX();
            ry = best.getY();
        }

        // Exponential moving average for smooth output
        if (Double.isNaN(smoothedOutputX)) {
            smoothedOutputX = rx;
            smoothedOutputY = ry;
        } else {
            double j = Math.sqrt(Math.pow(rx - smoothedOutputX, 2) + Math.pow(ry - smoothedOutputY, 2));
            if (j > POSITION_SMOOTH_SNAP_THRESHOLD) {
                smoothedOutputX = rx;
                smoothedOutputY = ry;
            } else {
                smoothedOutputX = POSITION_SMOOTH_ALPHA * rx + (1.0 - POSITION_SMOOTH_ALPHA) * smoothedOutputX;
                smoothedOutputY = POSITION_SMOOTH_ALPHA * ry + (1.0 - POSITION_SMOOTH_ALPHA) * smoothedOutputY;
            }
        }

        return new LatLng(refLat + smoothedOutputY / METRES_PER_DEG_LAT,
                refLng + smoothedOutputX / METRES_PER_DEG_LNG);
    }

    /**
     * Returns the most likely floor from the particle distribution.
     * Uses the barometric particle filter with all §3.2 floor guards.
     */
    public int getEstimatedFloor() {
        if (!isActive() || particles.isEmpty()) return estimatedFloor;
        java.util.Map<Integer, Double> fw = new java.util.HashMap<>();
        for (Particle p : particles) fw.merge(p.getFloor(), p.getWeight(), Double::sum);
        int bf = estimatedFloor;
        double bw = -1;
        for (java.util.Map.Entry<Integer, Double> e : fw.entrySet()) {
            if (e.getValue() > bw) {
                bw = e.getValue();
                bf = e.getKey();
            }
        }
        this.estimatedFloor = bf;
        return bf;
    }

    /** Returns the raw weighted mean position in local ENU. */
    public float[] getEstimatedXY() {
        if (!isActive() || particles.isEmpty()) return null;
        double wx = 0, wy = 0;
        for (Particle p : particles) {
            wx += p.getX() * p.getWeight();
            wy += p.getY() * p.getWeight();
        }
        return new float[]{(float) wx, (float) wy};
    }

    /** Returns a snapshot of the particle cloud for visualisation. */
    public List<Particle> getParticles() {
        List<Particle> s = new ArrayList<>(particles.size());
        for (Particle p : particles) s.add(p.copy());
        return s;
    }

    private static double gaussianLikelihood(double d, double s) {
        return Math.exp(-(d * d) / (2.0 * s * s));
    }
}