package com.openpositioning.PositionMe.sensors.fusion;

import static com.openpositioning.PositionMe.BuildConstants.DEBUG;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds pre-processed wall geometry per floor in ENU coordinates (metres).
 * Provides line-segment intersection tests so the particle filter can
 * enforce map constraints: particles that cross walls are penalised and
 * kept at their previous position.
 *
 * <p>Only axis-aligned wall segments (aligned to the building's two
 * principal orthogonal directions) are kept. This filters out diagonal
 * polygon edges that typically cross doorways or openings, preventing
 * false wall detections that would trap particles.</p>
 */
public class MapConstraint {

    private static final String TAG = "MapConstraint";

    /** Weight multiplier applied to particles that cross a wall (67 % penalty). */
    private static final double WALL_PENALTY = 0.33;

    /** Segments shorter than this (metres) are discarded.
     *  1 m filters wall-thickness edges and doorway-crossing segments (~0.8 m)
     *  while keeping exterior walls and structural walls (typically 1.5 m+). */
    private static final double MIN_WALL_LENGTH = 1.0;

    /** Angular tolerance (radians) for axis-alignment filtering (~15°). */
    private static final double AXIS_TOLERANCE_RAD = Math.toRadians(15);

    // ---- inner types --------------------------------------------------------

    /** A wall segment in ENU coordinates (metres), with pre-computed AABB. */
    public static class WallSegment {
        public final double x1, y1, x2, y2;
        public final double minX, maxX, minY, maxY;

        /**
         * Constructs a wall segment between two ENU points and pre-computes its AABB.
         *
         * @param x1 start easting (metres)
         * @param y1 start northing (metres)
         * @param x2 end easting (metres)
         * @param y2 end northing (metres)
         */
        public WallSegment(double x1, double y1, double x2, double y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.minX = Math.min(x1, x2);
            this.maxX = Math.max(x1, x2);
            this.minY = Math.min(y1, y2);
            this.maxY = Math.max(y1, y2);
        }
    }

    // ---- state --------------------------------------------------------------

    /** Floor index -> list of wall segments in ENU. */
    private final Map<Integer, List<WallSegment>> wallsByFloor = new HashMap<>();

    /** Building outline wall segments — apply to ALL floors (floor-independent). */
    private final List<WallSegment> outlineWalls = new ArrayList<>();

    private boolean initialized = false;

    // ---- initialisation -----------------------------------------------------

    /**
     * Extracts wall segments from FloorShapes, converts LatLng to ENU,
     * filters to keep only axis-aligned segments, and stores them by floor.
     *
     * @param floorShapesList per-floor shape data from FloorplanApiClient
     * @param coordTransform  the ENU coordinate transform (must be initialised)
     */
    public void initialize(List<FloorplanApiClient.FloorShapes> floorShapesList,
                           CoordinateTransform coordTransform) {
        wallsByFloor.clear();
        initialized = false;

        if (floorShapesList == null || !coordTransform.isInitialized()) {
            if (DEBUG) Log.w(TAG, "Cannot initialise: floorShapes="
                    + (floorShapesList != null) + " coordInit="
                    + coordTransform.isInitialized());
            return;
        }

        // Step 1: collect ALL raw wall segments across all floors.
        // Map by REAL floor number (parsed from display name) to match
        // the particle filter's floor numbering (WiFi API: 0=GF, 1=F1, 2=F2 …).
        List<WallSegment> allRaw = new ArrayList<>();
        Map<Integer, List<WallSegment>> rawByFloor = new HashMap<>();
        int[] floorNumbers = new int[floorShapesList.size()];

        for (int floorIdx = 0; floorIdx < floorShapesList.size(); floorIdx++) {
            FloorplanApiClient.FloorShapes floor = floorShapesList.get(floorIdx);
            int floorNum = parseFloorNumber(floor.getDisplayName());
            floorNumbers[floorIdx] = floorNum;
            List<WallSegment> rawSegments = new ArrayList<>();

            // Diagnostic: log all indoor types on this floor
            Map<String, Integer> typeCounts = new HashMap<>();
            for (FloorplanApiClient.MapShapeFeature f : floor.getFeatures()) {
                String t = f.getIndoorType();
                typeCounts.put(t, typeCounts.getOrDefault(t, 0) + 1);
            }
            if (DEBUG) Log.i(TAG, String.format("Floor %s (key=%d): feature types = %s",
                    floor.getDisplayName(), floorNum, typeCounts));

            for (FloorplanApiClient.MapShapeFeature feature : floor.getFeatures()) {
                if (!"wall".equals(feature.getIndoorType())) continue;

                String geoType = feature.getGeometryType();
                for (List<LatLng> part : feature.getParts()) {
                    if (part.size() < 2) continue;

                    for (int i = 0; i < part.size() - 1; i++) {
                        LatLng a = part.get(i);
                        LatLng b = part.get(i + 1);
                        double[] enuA = coordTransform.toEastNorth(a.latitude, a.longitude);
                        double[] enuB = coordTransform.toEastNorth(b.latitude, b.longitude);
                        rawSegments.add(new WallSegment(enuA[0], enuA[1], enuB[0], enuB[1]));
                    }

                    // For polygon geometry, close the ring
                    if (("Polygon".equals(geoType) || "MultiPolygon".equals(geoType))
                            && part.size() >= 3) {
                        LatLng last = part.get(part.size() - 1);
                        LatLng first = part.get(0);
                        if (Math.abs(last.latitude - first.latitude) > 1e-9
                                || Math.abs(last.longitude - first.longitude) > 1e-9) {
                            double[] enuLast = coordTransform.toEastNorth(
                                    last.latitude, last.longitude);
                            double[] enuFirst = coordTransform.toEastNorth(
                                    first.latitude, first.longitude);
                            rawSegments.add(new WallSegment(
                                    enuLast[0], enuLast[1], enuFirst[0], enuFirst[1]));
                        }
                    }
                }
            }

            rawByFloor.put(floorIdx, rawSegments);
            allRaw.addAll(rawSegments);
        }

        // Step 2: detect the building's primary axis from all segments
        double primaryAngle = detectPrimaryAngle(allRaw);
        if (DEBUG) Log.i(TAG, String.format("Detected building primary axis: %.1f°",
                Math.toDegrees(primaryAngle)));

        // Step 3: filter each floor — keep only axis-aligned, long-enough segments.
        // Store by REAL floor number (matching particle filter numbering).
        for (int floorIdx = 0; floorIdx < floorShapesList.size(); floorIdx++) {
            FloorplanApiClient.FloorShapes floor = floorShapesList.get(floorIdx);
            int floorNum = floorNumbers[floorIdx];
            List<WallSegment> raw = rawByFloor.get(floorIdx);
            if (raw == null) raw = new ArrayList<>();

            List<WallSegment> filtered = new ArrayList<>();
            int shortCount = 0, diagonalCount = 0;

            for (WallSegment seg : raw) {
                double dx = seg.x2 - seg.x1;
                double dy = seg.y2 - seg.y1;
                double len = Math.sqrt(dx * dx + dy * dy);

                if (len < MIN_WALL_LENGTH) {
                    shortCount++;
                    continue;
                }

                if (!isAlignedToAxis(dx, dy, primaryAngle)) {
                    diagonalCount++;
                    continue;
                }

                filtered.add(seg);
            }

            wallsByFloor.put(floorNum, filtered);
            if (DEBUG) Log.i(TAG, String.format(
                    "Floor key=%d (%s): %d raw → %d kept (filtered %d short, %d diagonal)",
                    floorNum, floor.getDisplayName(), raw.size(), filtered.size(),
                    shortCount, diagonalCount));
        }

        initialized = !wallsByFloor.isEmpty();
        int totalSegments = 0;
        for (List<WallSegment> segs : wallsByFloor.values()) totalSegments += segs.size();
        if (DEBUG) Log.i(TAG, String.format("Initialised: %d floors, %d total wall segments (axis-aligned)",
                wallsByFloor.size(), totalSegments));
    }

    /**
     * Loads the building outline polygon as floor-independent wall constraints.
     * These prevent particles from leaving the building boundary on any floor.
     *
     * @param outlinePolygon building boundary polygon from BuildingInfo
     * @param coordTransform the ENU coordinate transform (must be initialised)
     */
    public void setOutlineConstraint(List<LatLng> outlinePolygon,
                                      CoordinateTransform coordTransform) {
        outlineWalls.clear();

        if (outlinePolygon == null || outlinePolygon.size() < 3
                || !coordTransform.isInitialized()) {
            if (DEBUG) Log.w(TAG, "Cannot load outline: polygon="
                    + (outlinePolygon != null ? outlinePolygon.size() : "null")
                    + " coordInit=" + coordTransform.isInitialized());
            return;
        }

        for (int i = 0; i < outlinePolygon.size(); i++) {
            LatLng a = outlinePolygon.get(i);
            LatLng b = outlinePolygon.get((i + 1) % outlinePolygon.size());
            double[] enuA = coordTransform.toEastNorth(a.latitude, a.longitude);
            double[] enuB = coordTransform.toEastNorth(b.latitude, b.longitude);
            double dx = enuB[0] - enuA[0];
            double dy = enuB[1] - enuA[1];
            double len = Math.sqrt(dx * dx + dy * dy);
            // Keep all outline segments >= 0.5m (outline is coarser, no doorway issue)
            if (len >= 0.5) {
                outlineWalls.add(new WallSegment(enuA[0], enuA[1], enuB[0], enuB[1]));
            }
        }

        // Outline alone can mark as initialized (even without interior walls)
        if (!outlineWalls.isEmpty()) {
            initialized = true;
        }

        if (DEBUG) Log.i(TAG, String.format("Loaded %d building outline segments (from %d polygon points)",
                outlineWalls.size(), outlinePolygon.size()));
    }

    /** Returns {@code true} if wall geometry has been loaded. */
    public boolean isInitialized() {
        return initialized;
    }

    // ---- axis detection & filtering -----------------------------------------

    /**
     * Detects the building's primary axis direction from all wall segments.
     * Uses a length-weighted angle histogram over [0°, 180°) with smoothing
     * to find the dominant wall direction.
     *
     * @return primary axis angle in radians [0, π)
     */
    private double detectPrimaryAngle(List<WallSegment> segments) {
        // 1° bins over [0, 180)
        double[] histogram = new double[180];

        for (WallSegment seg : segments) {
            double dx = seg.x2 - seg.x1;
            double dy = seg.y2 - seg.y1;
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 0.01) continue;

            // Angle in degrees, normalized to [0, 180)
            double angleDeg = Math.toDegrees(Math.atan2(dy, dx));
            angleDeg = ((angleDeg % 180) + 180) % 180;
            int bin = (int) angleDeg;
            if (bin >= 180) bin = 179;

            // Weight by segment length — longer walls contribute more
            histogram[bin] += len;
        }

        // Smooth with ±5° circular window
        double[] smoothed = new double[180];
        for (int i = 0; i < 180; i++) {
            for (int d = -5; d <= 5; d++) {
                smoothed[i] += histogram[((i + d) % 180 + 180) % 180];
            }
        }

        // Find peak
        int peakBin = 0;
        for (int i = 1; i < 180; i++) {
            if (smoothed[i] > smoothed[peakBin]) peakBin = i;
        }

        return Math.toRadians(peakBin);
    }

    /**
     * Parses a floor display name into the numeric floor index used by
     * the WiFi API and particle filter (0 = GF, 1 = F1, 2 = F2, …).
     * Handles common naming conventions: "LG"→-1, "GF"/"Ground"→0,
     * "F1"/"1st"→1, "F2"/"2nd"→2, etc.
     */
    static int parseFloorNumber(String displayName) {
        if (displayName == null || displayName.isEmpty()) return 0;
        String upper = displayName.toUpperCase().trim();

        if (upper.startsWith("LG") || upper.startsWith("LOWER")
                || upper.startsWith("B") || upper.startsWith("BASEMENT")) {
            return -1;
        }
        if (upper.equals("GF") || upper.startsWith("GROUND")
                || upper.equals("G") || upper.equals("0")) {
            return 0;
        }
        // "F1", "F2", "F3" etc.
        if (upper.startsWith("F") && upper.length() >= 2) {
            try {
                return Integer.parseInt(upper.substring(1));
            } catch (NumberFormatException ignored) {}
        }
        // "1", "2", "3" or "1st", "2nd", "3rd"
        try {
            return Integer.parseInt(upper.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException ignored) {}

        return 0;
    }

    /**
     * Checks whether a segment direction is aligned to either of the building's
     * two orthogonal axes (primary or primary+90°) within the configured tolerance.
     */
    private boolean isAlignedToAxis(double dx, double dy, double primaryAngle) {
        double angle = Math.atan2(dy, dx);
        // Normalize to [0, π)
        angle = ((angle % Math.PI) + Math.PI) % Math.PI;

        double secondaryAngle = (primaryAngle + Math.PI / 2) % Math.PI;

        return angleDiffMod(angle, primaryAngle) < AXIS_TOLERANCE_RAD
                || angleDiffMod(angle, secondaryAngle) < AXIS_TOLERANCE_RAD;
    }

    /**
     * Computes the minimum angular difference between two angles in [0, π),
     * accounting for the wrap-around at 0°/180°.
     */
    private static double angleDiffMod(double a, double b) {
        double diff = Math.abs(a - b);
        if (diff > Math.PI / 2) diff = Math.PI - diff;
        return diff;
    }

    // ---- constraint application ---------------------------------------------

    /**
     * Applies wall constraints to all particles after a predict step.
     * Particles that cross a wall are reverted to their previous position
     * and penalised. Uses AABB pre-filtering for performance.
     *
     * @return the number of particles that collided with a wall
     */
    public int applyConstraints(Particle[] particles,
                                 double[] oldX, double[] oldY) {
        if (!initialized) return 0;

        int collisionCount = 0;

        for (int i = 0; i < particles.length; i++) {
            Particle p = particles[i];

            // AABB of particle movement for fast pre-filtering
            double moveMinX = Math.min(oldX[i], p.x);
            double moveMaxX = Math.max(oldX[i], p.x);
            double moveMinY = Math.min(oldY[i], p.y);
            double moveMaxY = Math.max(oldY[i], p.y);

            boolean hitWall = false;

            // Check floor-specific interior walls
            List<WallSegment> walls = wallsByFloor.get(p.floor);
            if (walls != null) {
                for (int w = 0, wSize = walls.size(); w < wSize; w++) {
                    WallSegment wall = walls.get(w);
                    if (wall.maxX < moveMinX || wall.minX > moveMaxX
                            || wall.maxY < moveMinY || wall.minY > moveMaxY) {
                        continue;
                    }
                    if (segmentsIntersect(
                            oldX[i], oldY[i], p.x, p.y,
                            wall.x1, wall.y1, wall.x2, wall.y2)) {
                        hitWall = true;
                        break;
                    }
                }
            }

            // Check building outline walls (floor-independent, all floors)
            if (!hitWall && !outlineWalls.isEmpty()) {
                for (int w = 0, wSize = outlineWalls.size(); w < wSize; w++) {
                    WallSegment wall = outlineWalls.get(w);
                    if (wall.maxX < moveMinX || wall.minX > moveMaxX
                            || wall.maxY < moveMinY || wall.minY > moveMaxY) {
                        continue;
                    }
                    if (segmentsIntersect(
                            oldX[i], oldY[i], p.x, p.y,
                            wall.x1, wall.y1, wall.x2, wall.y2)) {
                        hitWall = true;
                        break;
                    }
                }
            }

            if (hitWall) {
                // Revert to previous position + weight penalty
                p.x = oldX[i];
                p.y = oldY[i];
                p.weight *= WALL_PENALTY;
                collisionCount++;
            }
        }

        return collisionCount;
    }

    // ---- geometry primitives ------------------------------------------------

    /**
     * Tests whether two line segments intersect using the parametric approach.
     */
    static boolean segmentsIntersect(
            double px1, double py1, double px2, double py2,
            double qx1, double qy1, double qx2, double qy2) {

        double dx_p = px2 - px1;
        double dy_p = py2 - py1;
        double dx_q = qx2 - qx1;
        double dy_q = qy2 - qy1;

        double denom = dx_p * dy_q - dy_p * dx_q;

        if (Math.abs(denom) < 1e-12) {
            return false; // parallel or coincident
        }

        double dx_pq = qx1 - px1;
        double dy_pq = qy1 - py1;

        double t = (dx_pq * dy_q - dy_pq * dx_q) / denom;
        if (t < 0.0 || t > 1.0) return false;

        double u = (dx_pq * dy_p - dy_pq * dx_p) / denom;
        return u >= 0.0 && u <= 1.0;
    }

    /** Returns the number of wall segments for a given floor (for logging). */
    public int getWallCount(int floor) {
        List<WallSegment> walls = wallsByFloor.get(floor);
        return walls != null ? walls.size() : 0;
    }
}
