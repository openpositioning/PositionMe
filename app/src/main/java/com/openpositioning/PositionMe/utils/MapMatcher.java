package com.openpositioning.PositionMe.utils;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Map Matching engine for indoor positioning.
 *
 * <p>Provides three core services:</p>
 * <ol>
 *   <li><b>Wall penetration detection</b> 鈥?given a movement segment in the
 *       local East/North plane, determines whether it crosses any registered
 *       wall segment. Used by fusion motion models to reject impossible
 *       movements through walls.</li>
 *   <li><b>Position correction</b> 鈥?snaps a position that has drifted
 *       through a wall back to the nearest valid side of that wall.</li>
 *   <li><b>Floor inference</b> 鈥?combines barometer height change with a
 *       motion-model heuristic to distinguish stair climbing from elevator
 *       riding, and updates the current floor accordingly.</li>
 * </ol>
 *
 * <h3>Coordinate system</h3>
 * <p>All positions are in the local East/North metric plane managed by
 * {@link CoordinateConverter}.  Wall endpoints must be supplied in the same
 * coordinate system.</p>
 *
 * <h3>Feature types</h3>
 * <ul>
 *   <li>{@code WALL}  鈥?impenetrable barrier; triggers {@link #correctPosition}.</li>
 *   <li>{@code STAIRS} 鈥?floor transition zone; floor changes only when the
 *       barometer height change exceeds {@link #STAIR_HEIGHT_THRESHOLD_M}.</li>
 *   <li>{@code LIFT}  鈥?elevator zone; floor changes without significant
 *       horizontal displacement (detected by low horizontal speed).</li>
 * </ul>
 *
 * @author PositionMe Assessment 2
 */
public class MapMatcher {

    private static final String TAG = "MapMatcher";

    // Thresholds
    /**
     * Minimum barometric height change (metres) required to confirm a floor
     * transition via stairs.  One floor 鈮?3鈥? m; we use a conservative 2 m
     * to account for sensor noise.
     */
    public static final float STAIR_HEIGHT_THRESHOLD_M = 2.0f;

    /**
     * Maximum horizontal speed (m/s) below which we consider the user to be
     * stationary 鈥?a prerequisite for elevator detection.
     */
    private static final float LIFT_MAX_HORIZ_SPEED = 0.3f;

    /**
     * Minimum barometric height change (metres) to trigger an elevator floor
     * update (same threshold as stairs but the motion model differs).
     */
    private static final float LIFT_HEIGHT_THRESHOLD_M = 2.0f;

    /** Ignore very short micro-movements to reduce wall-crossing false positives. */
    private static final double MIN_WALL_CHECK_STEP_M = 0.20;

    /** Treat near-endpoint touches as non-penetration to avoid corridor lockups. */
    private static final double WALL_ENDPOINT_TOLERANCE_M = 0.35;

    /** Allow near-parallel motion along walls in narrow corridors. */
    private static final double WALL_SLIDING_DISTANCE_M = 0.45;

    // Feature types
    /** Enumeration of map feature types. */
    public enum FeatureType { WALL, STAIRS, LIFT }

    // Inner classes
    /**
     * A line-segment feature in the local East/North plane.
     */
    public static class MapFeature {
        /** Feature type. */
        public final FeatureType type;
        /** Start point east (metres). */
        public final double x1;
        /** Start point north (metres). */
        public final double y1;
        /** End point east (metres). */
        public final double x2;
        /** End point north (metres). */
        public final double y2;
        /** Optional label (e.g. "Wall-A", "Staircase-1"). */
        public final String label;

        public MapFeature(FeatureType type,
                          double x1, double y1,
                          double x2, double y2,
                          String label) {
            this.type  = type;
            this.x1    = x1;
            this.y1    = y1;
            this.x2    = x2;
            this.y2    = y2;
            this.label = label;
        }
    }

    // State
    private final List<MapFeature> features = new ArrayList<>();

    /** Current floor (0 = ground floor). */
    private int currentFloor = 0;

    /** Floor height used for barometer-based floor estimation (metres). */
    private float floorHeightM = 3.5f;

    // Constructor
    /** Create an empty map matcher (add features via {@link #addFeature}). */
    public MapMatcher() {}

    /**
     * Create a map matcher pre-loaded with the Nucleus building walls for the
     * given floor.  Coordinates are in the local East/North plane anchored at
     * the Nucleus building SW corner (55.92282257掳N, 3.17459565掳W).
     *
     * <p>This is a simplified rectangular wall layout derived from the building
     * boundary.  Replace with actual floor-plan data for production use.</p>
     *
     * @param floor Floor number (0 = ground, 1 = first, 鈥?.
     */
    public static MapMatcher forNucleusFloor(int floor) {
        MapMatcher mm = new MapMatcher();
        mm.currentFloor = floor;
        mm.floorHeightM = 4.2f;   // Nucleus floor height

        // Nucleus building outer boundary in local EN plane
        // (anchored at SW corner: 55.92282257, -3.17459565)
        // NE corner 鈮?(50, 55) m from SW corner
        double W = 0,  E = 50;
        double S = 0,  N = 55;

        // Outer walls
        mm.addFeature(new MapFeature(FeatureType.WALL, W, S, E, S, "South wall"));
        mm.addFeature(new MapFeature(FeatureType.WALL, E, S, E, N, "East wall"));
        mm.addFeature(new MapFeature(FeatureType.WALL, E, N, W, N, "North wall"));
        mm.addFeature(new MapFeature(FeatureType.WALL, W, N, W, S, "West wall"));

        // Internal corridor walls (simplified)
        mm.addFeature(new MapFeature(FeatureType.WALL, 10, S, 10, 40, "Corridor-W"));
        mm.addFeature(new MapFeature(FeatureType.WALL, 40, S, 40, 40, "Corridor-E"));
        mm.addFeature(new MapFeature(FeatureType.WALL, 10, 40, 40, 40, "Corridor-N"));

        // Staircase zones (represented as short segments at stair locations)
        mm.addFeature(new MapFeature(FeatureType.STAIRS, 5, 45, 10, 55, "Staircase-NW"));
        mm.addFeature(new MapFeature(FeatureType.STAIRS, 40, 45, 50, 55, "Staircase-NE"));

        // Lift zone
        mm.addFeature(new MapFeature(FeatureType.LIFT, 22, 42, 28, 48, "Lift"));

        return mm;
    }

    // Feature management
    /**
     * Add a map feature (wall, stairs, or lift segment).
     *
     * @param feature Feature to add.
     */
    public void addFeature(MapFeature feature) {
        features.add(feature);
    }

    /** Remove all features. */
    public void clearFeatures() {
        features.clear();
    }

    // Wall penetration detection
    /**
     * Test whether the movement segment from {@code (x1,y1)} to {@code (x2,y2)}
     * crosses any registered {@link FeatureType#WALL} segment.
     *
     * <p>Used by fusion motion models to reject impossible movement updates
     * that would pass through walls.</p>
     *
     * @param x1 Start east (metres).
     * @param y1 Start north (metres).
     * @param x2 End east (metres).
     * @param y2 End north (metres).
     * @return {@code true} if the segment intersects at least one wall.
     */
    public boolean crossesWall(double x1, double y1, double x2, double y2) {
        if (distance(x1, y1, x2, y2) < MIN_WALL_CHECK_STEP_M) {
            return false;
        }

        for (MapFeature f : features) {
            if (f.type == FeatureType.WALL) {
                if (segmentsIntersect(x1, y1, x2, y2, f.x1, f.y1, f.x2, f.y2)) {
                    if (isNearWallEndpoint(x1, y1, x2, y2, f)
                            || isLikelyWallSliding(x1, y1, x2, y2, f)) {
                        continue;
                    }
                    return true;
                }
            }
        }
        return false;
    }

    // Position correction
    /**
     * Correct a position that has drifted through a wall using <b>wall sliding</b>.
     *
     * <h3>Algorithm (vector projection / wall sliding)</h3>
     * <ol>
     *   <li>Compute the raw displacement vector {@code d = (newX-prevX, newY-prevY)}.</li>
     *   <li>For each wall that the displacement crosses, decompose {@code d} into:
     *       <ul>
     *         <li>A component <em>parallel</em> to the wall (allowed 鈥?the user slides along it).</li>
     *         <li>A component <em>perpendicular</em> to the wall (blocked 鈥?would penetrate the wall).</li>
     *       </ul>
     *   </li>
     *   <li>The corrected displacement retains only the parallel component, so the
     *       position glides smoothly along the wall instead of being frozen or
     *       snapped back.  This eliminates the "walk-into-wall 鈫?hard-pull-back"
     *       spike pattern.</li>
     *   <li>A small safety margin ({@link #WALL_SLIDE_SAFETY_M}) is subtracted from
     *       the perpendicular component to keep the position on the correct side of
     *       the wall even after floating-point rounding.</li>
     * </ol>
     *
     * <p>If the slid position still crosses another wall the process is repeated
     * (up to {@link #MAX_SLIDE_ITERATIONS} times) so that corner collisions are
     * handled correctly.</p>
     *
     * @param prevX Previous east position (metres).
     * @param prevY Previous north position (metres).
     * @param newX  Proposed new east position (metres).
     * @param newY  Proposed new north position (metres).
     * @return Corrected position as {@code double[]{east, north}}.
     */
    public double[] correctPosition(double prevX, double prevY,
                                    double newX,  double newY) {
        if (distance(prevX, prevY, newX, newY) < MIN_WALL_CHECK_STEP_M) {
            return new double[]{newX, newY};
        }

        // Iterative wall-sliding: resolve up to MAX_SLIDE_ITERATIONS wall collisions.
        double curX = prevX, curY = prevY;
        double dstX = newX,  dstY = newY;

        for (int iter = 0; iter < MAX_SLIDE_ITERATIONS; iter++) {
            MapFeature hitWall = null;

            for (MapFeature f : features) {
                if (f.type != FeatureType.WALL) continue;
                if (segmentsIntersect(curX, curY, dstX, dstY,
                        f.x1, f.y1, f.x2, f.y2)) {
                    if (isNearWallEndpoint(curX, curY, dstX, dstY, f)
                            || isLikelyWallSliding(curX, curY, dstX, dstY, f)) {
                        continue;
                    }
                    hitWall = f;
                    break;  // handle one wall per iteration
                }
            }

            if (hitWall == null) {
                // No more walls hit 鈥?accept the current destination.
                return new double[]{dstX, dstY};
            }

            // Wall-sliding via vector projection
            // Displacement vector from current position to proposed destination.
            double dx = dstX - curX;
            double dy = dstY - curY;

            // Unit vector along the wall.
            double wallDx = hitWall.x2 - hitWall.x1;
            double wallDy = hitWall.y2 - hitWall.y1;
            double wallLen = Math.sqrt(wallDx * wallDx + wallDy * wallDy);
            if (wallLen < 1e-9) {
                // Degenerate wall 鈥?fall back to hard stop.
                return new double[]{curX, curY};
            }
            double wallUx = wallDx / wallLen;   // unit vector along wall (east component)
            double wallUy = wallDy / wallLen;   // unit vector along wall (north component)

            // Project displacement onto the wall direction (parallel component).
            double parallelMag = dx * wallUx + dy * wallUy;
            double slideX = parallelMag * wallUx;
            double slideY = parallelMag * wallUy;

            // Apply safety margin: pull the slid position slightly away from the wall
            // in the direction the user came from (perpendicular, toward prevX/prevY).
            double perpX = dx - slideX;   // perpendicular component of displacement
            double perpY = dy - slideY;
            double perpLen = Math.sqrt(perpX * perpX + perpY * perpY);
            double safeOffsetX = 0, safeOffsetY = 0;
            if (perpLen > 1e-9) {
                // Unit vector pointing away from the wall (back toward the user's side).
                safeOffsetX = -(perpX / perpLen) * WALL_SLIDE_SAFETY_M;
                safeOffsetY = -(perpY / perpLen) * WALL_SLIDE_SAFETY_M;
            }

            // New proposed destination: slide along wall + safety offset.
            dstX = curX + slideX + safeOffsetX;
            dstY = curY + slideY + safeOffsetY;

        }

        // Exceeded iteration limit 鈥?hard stop to prevent infinite loops.
        Log.w(TAG, "Wall sliding: max iterations reached, hard stop.");
        return new double[]{prevX, prevY};
    }

    /** Maximum number of wall-sliding iterations per correction call. */
    private static final int MAX_SLIDE_ITERATIONS = 4;

    /**
     * Safety margin (metres) subtracted from the perpendicular displacement
     * component to keep the corrected position on the correct side of the wall.
     */
    private static final double WALL_SLIDE_SAFETY_M = 0.05;

    // Floor inference
    /**
     * Attempt to update the current floor based on barometric height change
     * and the motion model.
     *
     * <p>Two scenarios are handled:</p>
     * <ul>
     *   <li><b>Stairs</b>: the user is near a staircase zone AND the barometer
     *       reports a height change exceeding {@link #STAIR_HEIGHT_THRESHOLD_M}.
     *       The floor is updated by 卤1 depending on the sign of the height
     *       change.</li>
     *   <li><b>Lift</b>: the user is near a lift zone AND horizontal speed is
     *       below {@link #LIFT_MAX_HORIZ_SPEED} AND the barometer reports a
     *       height change exceeding {@link #LIFT_HEIGHT_THRESHOLD_M}.  The
     *       floor is updated by the number of floors implied by the height
     *       change.</li>
     * </ul>
     *
     * @param eastM              Current east position (metres).
     * @param northM             Current north position (metres).
     * @param baroHeightChangedM Barometric height change since last floor check (metres).
     * @param horizSpeedMs       Estimated horizontal speed (m/s).
     * @return New floor number (unchanged if no transition detected).
     */
    public int updateFloor(double eastM, double northM,
                           float baroHeightChangedM, float horizSpeedMs) {

        boolean hasTransitionFeatures = hasFeatureType(FeatureType.STAIRS)
                || hasFeatureType(FeatureType.LIFT);

        // Staircase check
        if (Math.abs(baroHeightChangedM) >= STAIR_HEIGHT_THRESHOLD_M) {
            if (isNearFeatureType(eastM, northM, FeatureType.STAIRS, 8.0)) {
                int delta = (baroHeightChangedM > 0) ? 1 : -1;
                int newFloor = currentFloor + delta;
                Log.d(TAG, String.format("Stairs detected: floor %d 鈫?%d (螖h=%.1f m)",
                        currentFloor, newFloor, baroHeightChangedM));
                currentFloor = newFloor;
                return currentFloor;
            }
        }

        // Lift check
        if (Math.abs(baroHeightChangedM) >= LIFT_HEIGHT_THRESHOLD_M
                && horizSpeedMs < LIFT_MAX_HORIZ_SPEED) {
            if (isNearFeatureType(eastM, northM, FeatureType.LIFT, 10.0)) {
                int floorsChanged = (int) Math.round(baroHeightChangedM / floorHeightM);
                if (floorsChanged != 0) {
                    int newFloor = currentFloor + floorsChanged;
                    Log.d(TAG, String.format("Lift detected: floor %d 鈫?%d (螖h=%.1f m)",
                            currentFloor, newFloor, baroHeightChangedM));
                    currentFloor = newFloor;
                    return currentFloor;
                }
            }
        }

        // If no stairs/lift map features are available, fall back to pure
        // barometer-based floor inference so floor can still auto-switch.
        if (!hasTransitionFeatures && Math.abs(baroHeightChangedM) >= STAIR_HEIGHT_THRESHOLD_M) {
            int floorsChanged = (int) Math.round(baroHeightChangedM / floorHeightM);
            if (floorsChanged == 0) {
                floorsChanged = (baroHeightChangedM > 0) ? 1 : -1;
            }
            int newFloor = currentFloor + floorsChanged;
            Log.d(TAG, String.format("Barometer fallback: floor %d 鈫?%d (螖h=%.1f m)",
                    currentFloor, newFloor, baroHeightChangedM));
            currentFloor = newFloor;
            return currentFloor;
        }

        return currentFloor;
    }

    // Proximity helpers
    /**
     * Check whether the given position is within {@code radiusM} metres of any
     * feature of the specified type.
     */
    private boolean isNearFeatureType(double eastM, double northM,
                                      FeatureType type, double radiusM) {
        for (MapFeature f : features) {
            if (f.type == type) {
                // Distance from point to segment
                double dist = pointToSegmentDistance(eastM, northM,
                        f.x1, f.y1, f.x2, f.y2);
                if (dist <= radiusM) return true;
            }
        }
        return false;
    }

    private boolean hasFeatureType(FeatureType type) {
        for (MapFeature f : features) {
            if (f.type == type) {
                return true;
            }
        }
        return false;
    }

    // Geometry utilities
    /**
     * Test whether two 2-D line segments intersect.
     *
     * <p>Uses the cross-product / orientation method.</p>
     */
    private static boolean segmentsIntersect(double ax, double ay, double bx, double by,
                                             double cx, double cy, double dx, double dy) {
        double d1 = cross(cx, cy, dx, dy, ax, ay);
        double d2 = cross(cx, cy, dx, dy, bx, by);
        double d3 = cross(ax, ay, bx, by, cx, cy);
        double d4 = cross(ax, ay, bx, by, dx, dy);

        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
                ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }

        // Collinear cases
        if (d1 == 0 && onSegment(cx, cy, dx, dy, ax, ay)) return true;
        if (d2 == 0 && onSegment(cx, cy, dx, dy, bx, by)) return true;
        if (d3 == 0 && onSegment(ax, ay, bx, by, cx, cy)) return true;
        if (d4 == 0 && onSegment(ax, ay, bx, by, dx, dy)) return true;

        return false;
    }

    /** Cross product of vectors (px, py) and (qx, qy). */
    private static double cross(double px, double py,
                                double qx, double qy,
                                double rx, double ry) {
        return (qx - px) * (ry - py) - (qy - py) * (rx - px);
    }

    /** Check whether point rx lies on segment px (assuming collinearity). */
    private static boolean onSegment(double px, double py,
                                     double qx, double qy,
                                     double rx, double ry) {
        return Math.min(px, qx) <= rx && rx <= Math.max(px, qx) &&
                Math.min(py, qy) <= ry && ry <= Math.max(py, qy);
    }

    private static boolean isNearWallEndpoint(double x1, double y1,
                                              double x2, double y2,
                                              MapFeature wall) {
        return distance(x1, y1, wall.x1, wall.y1) <= WALL_ENDPOINT_TOLERANCE_M
                || distance(x1, y1, wall.x2, wall.y2) <= WALL_ENDPOINT_TOLERANCE_M
                || distance(x2, y2, wall.x1, wall.y1) <= WALL_ENDPOINT_TOLERANCE_M
                || distance(x2, y2, wall.x2, wall.y2) <= WALL_ENDPOINT_TOLERANCE_M;
    }

    private static boolean isLikelyWallSliding(double x1, double y1,
                                               double x2, double y2,
                                               MapFeature wall) {
        double moveX = x2 - x1;
        double moveY = y2 - y1;
        double moveLen = Math.sqrt(moveX * moveX + moveY * moveY);
        if (moveLen < MIN_WALL_CHECK_STEP_M) {
            return true;
        }

        double wallX = wall.x2 - wall.x1;
        double wallY = wall.y2 - wall.y1;
        double wallLen = Math.sqrt(wallX * wallX + wallY * wallY);
        if (wallLen < 1e-6) {
            return false;
        }

        double parallel = Math.abs((moveX * wallX + moveY * wallY) / (moveLen * wallLen));
        if (parallel < 0.92) {
            return false;
        }

        double dStart = pointToSegmentDistance(x1, y1, wall.x1, wall.y1, wall.x2, wall.y2);
        double dEnd = pointToSegmentDistance(x2, y2, wall.x1, wall.y1, wall.x2, wall.y2);
        return dStart <= WALL_SLIDING_DISTANCE_M && dEnd <= WALL_SLIDING_DISTANCE_M;
    }

    private static double distance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Minimum distance from point {@code (px,py)} to segment
     * {@code (ax,ay) and (bx,by)}.
     */
    private static double pointToSegmentDistance(double px, double py,
                                                 double ax, double ay,
                                                 double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        if (lenSq == 0) {
            // Degenerate segment (point)
            double ex = px - ax, ey = py - ay;
            return Math.sqrt(ex * ex + ey * ey);
        }
        double t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq));
        double projX = ax + t * dx;
        double projY = ay + t * dy;
        double ex = px - projX, ey = py - projY;
        return Math.sqrt(ex * ex + ey * ey);
    }

    // Getters / setters
    /** @return Current floor number (0 = ground). */
    public int getCurrentFloor() { return currentFloor; }

    /**
     * Manually set the current floor (e.g. from user input or WiFi floor).
     *
     * @param floor Floor number.
     */
    public void setCurrentFloor(int floor) { this.currentFloor = floor; }

    /**
     * Set the floor height used for barometer-based floor estimation.
     *
     * @param heightM Floor height in metres.
     */
    public void setFloorHeightM(float heightM) { this.floorHeightM = heightM; }

    /** @return All registered map features. */
    public List<MapFeature> getFeatures() { return features; }
}

