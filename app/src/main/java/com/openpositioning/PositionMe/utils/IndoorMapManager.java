package com.openpositioning.PositionMe.utils;

import android.graphics.Color;
import android.util.Log;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.HashMap;
//import java.util.logging.Handler;
import android.os.Handler;
import android.os.Looper;
import java.util.logging.LogRecord;

import static java.lang.Math.*;
/**
 * Class used to manage indoor floor map overlays
 * Currently used by RecordingFragment
 * @see BuildingPolygon Describes the bounds of buildings and the methods to check if point is
 * in the building
 * @author Arun Gopalakrishnan
 */
/**
 * Class used to manage indoor floor map overlays
 * Currently used by RecordingFragment
 * @see BuildingPolygon Describes the bounds of buildings and the methods to check if point is
 * in the building
 *
 * UPDATED:
 * - Replaced bitmap GroundOverlay indoor floors with vector GeoJSON floor rendering
 * - Floors are now drawn using polygons from map_shapes
 */
public class IndoorMapManager {

    // To store the map instance
    private final GoogleMap gMap;

    // Stores the current Location of user
    private LatLng currentLocation;

    // Stores if indoor map overlay is currently set
    private boolean isIndoorMapSet = false;

    // Stores the current venue selected
    private IndoorVenue currentVenue;

    // Stores the currently displayed floor key (e.g., "B1", "G", "1")
    private String currentFloorKey;

    // Map polygon (outline) → venue mapping
    private final Map<Polygon, IndoorVenue> polygonToVenue = new HashMap<>();

    // List of currently drawn indoor floor polygons
    private final List<Polygon> activeFloorPolygons = new ArrayList<>();

    final double floorDistThresh = 5.0;
    final double liftDistThresh = 3.0;

    private String confirmedFloorKey = null;
    private float confirmedFloorElevation = Float.NaN;

    private final Handler floorCommitHandler = new Handler(Looper.getMainLooper());
    private static final long FLOOR_COMMIT_DELAY_MS = 1500;

    private final Runnable commitBrowsedFloorRunnable = new Runnable() {
        @Override
        public void run() {
            commitCurrentDisplayedFloor();
        }
    };


    /**
     * Venue model used for storing API floorplan data
     */
    public static class IndoorVenue{
        public String venueId;
        public String name;
        public LatLngBounds bounds;
        public List<LatLng> outline;

        // Raw GeoJSON floor data from API
        public String rawMapShapes;
        public Map<String, FloorFeatures> floorFeatures = new HashMap<>();

        public static class FloorFeatures {
            public List<List<LatLng>> wallPolygons = new ArrayList<>();
            public List<List<float[]>> wallPolygonsEnu = new ArrayList<>();
            public List<LatLng> stairsCenters = new ArrayList<>();
            public List<LatLng> liftCenters = new ArrayList<>();
        }
    }


    /**
     * Constructor to set the map instance
     * @param map The map on which the indoor floor map overlays are set
     */
    public IndoorMapManager(GoogleMap map){
        this.gMap = map;
    }


    /**
     * Function to update the current location of user
     * @param currentLocation new location of user
     */
    public void setCurrentLocation(LatLng currentLocation){
        this.currentLocation = currentLocation;
    }


    /**
     * Draw available indoor venues as clickable green polygons.
     */
    public void showVenueOutlines(List<IndoorVenue> venues) {

        polygonToVenue.clear();

        for (IndoorVenue venue : venues) {
            Log.d("IndoorMapManager", "Adding venue outline: " + venue.name +
                    " points=" + venue.outline.size());
            Polygon poly = gMap.addPolygon(
                    new PolygonOptions()
                            .addAll(venue.outline)
                            .strokeColor(Color.GREEN)
                            .strokeWidth(4f)
                            .fillColor(0x2200FF00)
                            .clickable(true)
            );

            polygonToVenue.put(poly, venue);
        }
    }


    /**
     * Returns the IndoorVenue corresponding to a clicked polygon.
     */
    public IndoorVenue getVenueForPolygon(Polygon polygon) {
        return polygonToVenue.get(polygon);
    }


    /**
     * Called when user selects a venue.
     * Parses GeoJSON floor data and renders first available floor.
     */
    public void selectVenue(IndoorVenue venue){

        if (venue == null || venue.rawMapShapes == null) return;

        clearIndoorFloor();

        this.currentVenue = venue;

        try {
            JSONObject floorsObj = new JSONObject(venue.rawMapShapes);

            Iterator<String> keys = floorsObj.keys();
            if (!keys.hasNext()) {
                Log.w("IndoorMapManager", "No floors found for venue: " + venue.name);
                return;
            }

            // Default to first floor
            currentFloorKey = keys.next();
            drawFloor(floorsObj.getJSONObject(currentFloorKey), currentFloorKey);

            isIndoorMapSet = true;

            Log.d("IndoorMapManager", "Selected floor: " + currentFloorKey);

        } catch (JSONException e) {
            Log.e("IndoorMapManager", "Failed parsing floor GeoJSON", e);
        }
    }

    /**
     * Checks whether the movement from {@code fromEnu} to {@code toEnu} crosses any wall
     * on the current floor. If it does, returns the last valid ENU position just before the
     * wall (clamped via binary search). If no wall is crossed, returns {@code toEnu} unchanged.
     *
     * Call this after every EKF prediction step, before reading the new state.
     *
     * @param fromEnu float[]{east, north} — previous EKF position in metres
     * @param toEnu   float[]{east, north} — new EKF position after predict()
     * @return        clamped float[]{east, north}, or toEnu if no wall was crossed
     */
    public float[] clampToWallEnu(float[] fromEnu, float[] toEnu) {
        if (currentVenue == null || currentFloorKey == null) return toEnu;
        IndoorVenue.FloorFeatures floor = currentVenue.floorFeatures.get(currentFloorKey);
        if (floor == null || floor.wallPolygonsEnu.isEmpty()) return toEnu;

        if (!crossesAnyWallEnu(fromEnu, toEnu, floor.wallPolygonsEnu)) return toEnu;

        return snapToWallEnu(fromEnu, toEnu, floor.wallPolygonsEnu);
    }

    private static final float INTERSECTION_EPSILON = 1e-6f;
    private static final float RING_CLOSURE_EPSILON = 1e-4f;
    private static final float WALL_SNAP_BACK_METERS = 0.05f;

    public float[] constrainMovementToWalls(float[] fromEnu, float[] toEnu) {
        if (fromEnu == null || toEnu == null) {
            return toEnu;
        }

        if (currentVenue == null || currentFloorKey == null) {
            return toEnu;
        }

        IndoorVenue.FloorFeatures floorFeatures = currentVenue.floorFeatures.get(currentFloorKey);
        if (floorFeatures == null ||
                floorFeatures.wallPolygonsEnu == null ||
                floorFeatures.wallPolygonsEnu.isEmpty()) {
            return toEnu;
        }

        WallCrossing crossing = findFirstWallCrossing(fromEnu, toEnu, floorFeatures.wallPolygonsEnu);

        if (crossing == null) {
            return new float[]{toEnu[0], toEnu[1]};
        }

        return moveToJustBeforeWall(fromEnu, toEnu, crossing.t, WALL_SNAP_BACK_METERS);
    }

    private static class WallCrossing {
        final float[] crossingPoint;
        final int polygonIndex;
        final int edgeIndex;
        final float t;

        WallCrossing(float[] crossingPoint, int polygonIndex, int edgeIndex, float t) {
            this.crossingPoint = crossingPoint;
            this.polygonIndex = polygonIndex;
            this.edgeIndex = edgeIndex;
            this.t = t;
        }
    }

    private WallCrossing findFirstWallCrossing(float[] from,
                                               float[] to,
                                               List<List<float[]>> walls) {
        double bestT = Double.MAX_VALUE;
        WallCrossing best = null;

        for (int pi = 0; pi < walls.size(); pi++) {
            List<float[]> polygon = walls.get(pi);
            if (polygon == null || polygon.size() < 2) continue;

            int edgeCount = getRingEdgeCount(polygon);

            for (int ei = 0; ei < edgeCount; ei++) {
                float[] a = polygon.get(ei);
                float[] b = polygon.get((ei + 1) % polygon.size());

                double t = intersectionT(from, to, a, b);
                if (t >= 0.0 && t <= 1.0 && t < bestT) {
                    bestT = t;

                    float[] crossingPoint = new float[]{
                            (float) (from[0] + t * (to[0] - from[0])),
                            (float) (from[1] + t * (to[1] - from[1]))
                    };

                    best = new WallCrossing(crossingPoint, pi, ei, (float) t);
                }
            }
        }

        return best;
    }

    private int getRingEdgeCount(List<float[]> ring) {
        if (ring == null || ring.size() < 2) return 0;

        float[] first = ring.get(0);
        float[] last = ring.get(ring.size() - 1);

        boolean alreadyClosed =
                Math.abs(first[0] - last[0]) < RING_CLOSURE_EPSILON &&
                        Math.abs(first[1] - last[1]) < RING_CLOSURE_EPSILON;

        return alreadyClosed ? ring.size() - 1 : ring.size();
    }

    private double intersectionT(float[] p1, float[] p2, float[] p3, float[] p4) {
        double rX = p2[0] - p1[0];
        double rY = p2[1] - p1[1];
        double sX = p4[0] - p3[0];
        double sY = p4[1] - p3[1];

        double denom = rX * sY - rY * sX;
        if (Math.abs(denom) < INTERSECTION_EPSILON) {
            return -1.0;
        }

        double qmpX = p3[0] - p1[0];
        double qmpY = p3[1] - p1[1];

        double t = (qmpX * sY - qmpY * sX) / denom;
        double u = (qmpX * rY - qmpY * rX) / denom;

        return (t >= 0.0 && t <= 1.0 && u >= 0.0 && u <= 1.0) ? t : -1.0;
    }

    private float[] moveToJustBeforeWall(float[] from, float[] to, float hitT, float snapBackMeters) {
        float dx = to[0] - from[0];
        float dy = to[1] - from[1];
        float len = (float) Math.sqrt(dx * dx + dy * dy);

        if (len < 1e-6f) {
            return new float[]{from[0], from[1]};
        }

        float backT = snapBackMeters / len;
        float safeT = Math.max(0f, hitT - backT);

        return new float[]{
                from[0] + safeT * dx,
                from[1] + safeT * dy
        };
    }

//    /**
//     * For each particle, zero its weight if the step from prev to current
//     * position crosses a wall. Called by ParticleFilter after predict(),
//     * before resample().
//     *
//     * @param prevEast   previous East positions, metres (length 300)
//     * @param prevNorth  previous North positions, metres (length 300)
//     * @param currEast   current East positions after predict() (length 300)
//     * @param currNorth  current North positions after predict() (length 300)
//     * @param weights    weight array — modified in-place
//     * @param converter  to convert East-North → LatLng for wall geometry check
//     */
//    private static final float INTERSECTION_EPSILON = 1e-6f;
//    private static final float WALL_CLEARANCE_METERS = 0.20f;
//    private static final float WALL_SNAP_BACK_METERS = 0.25f;
//    private static final float RING_CLOSURE_EPSILON = 1e-4f;
//
//    public void applyWallConstraints(
//            float[] prevEast,
//            float[] prevNorth,
//            float[] currEast,
//            float[] currNorth,
//            float[] weights,
//            CoordinateConverter converter) {
//
//        if (currentVenue == null || currentFloorKey == null) return;
//        IndoorVenue.FloorFeatures floor = currentVenue.floorFeatures.get(currentFloorKey);
//        if (floor == null || floor.wallPolygonsEnu == null || floor.wallPolygonsEnu.isEmpty()) return;
//
//        List<List<float[]>> walls = floor.wallPolygonsEnu;
//
//        for (int i = 0; i < weights.length; i++) {
//            if (weights[i] <= 0f) continue;
//
//            float[] prev = {prevEast[i], prevNorth[i]};
//            float[] curr = {currEast[i], currNorth[i]};
//
//            WallCrossing crossing = findFirstWallCrossing(prev, curr, walls);
//            boolean tooClose = isTooCloseToAnyWall(curr, walls, WALL_CLEARANCE_METERS);
//
//            if (crossing == null && !tooClose) {
//                continue;
//            }
//
//            float[] corrected;
//            if (crossing != null) {
//                corrected = moveToJustBeforeWall(prev, curr, crossing.t, WALL_SNAP_BACK_METERS);
//            } else {
//                corrected = new float[]{prev[0], prev[1]};
//            }
//
//            currEast[i] = corrected[0];
//            currNorth[i] = corrected[1];
//            weights[i] *= 0.1f;
//        }
//    }
//
//    private static class WallCrossing {
//        final float[] crossingPoint;
//        final int polygonIndex;
//        final int edgeIndex;
//        final float t;
//
//        WallCrossing(float[] pt, int polygonIndex, int edgeIndex, float t) {
//            this.crossingPoint = pt;
//            this.polygonIndex = polygonIndex;
//            this.edgeIndex = edgeIndex;
//            this.t = t;
//        }
//    }
//
//    private WallCrossing findFirstWallCrossing(
//            float[] from,
//            float[] to,
//            List<List<float[]>> walls) {
//
//        double bestT = Double.MAX_VALUE;
//        WallCrossing best = null;
//
//        for (int pi = 0; pi < walls.size(); pi++) {
//            List<float[]> polygon = walls.get(pi);
//            if (polygon == null || polygon.size() < 2) continue;
//
//            int edgeCount = getRingEdgeCount(polygon);
//
//            for (int ei = 0; ei < edgeCount; ei++) {
//                float[] a = polygon.get(ei);
//                float[] b = polygon.get((ei + 1) % polygon.size());
//
//                double t = intersectionT(from, to, a, b);
//                if (t >= 0.0 && t <= 1.0 && t < bestT) {
//                    bestT = t;
//                    float[] pt = {
//                            (float) (from[0] + t * (to[0] - from[0])),
//                            (float) (from[1] + t * (to[1] - from[1]))
//                    };
//                    best = new WallCrossing(pt, pi, ei, (float) t);
//                }
//            }
//        }
//
//        return best;
//    }
//
//    private int getRingEdgeCount(List<float[]> ring) {
//        if (ring == null || ring.size() < 2) return 0;
//
//        float[] first = ring.get(0);
//        float[] last = ring.get(ring.size() - 1);
//
//        boolean alreadyClosed =
//                Math.abs(first[0] - last[0]) < RING_CLOSURE_EPSILON &&
//                        Math.abs(first[1] - last[1]) < RING_CLOSURE_EPSILON;
//
//        return alreadyClosed ? ring.size() - 1 : ring.size();
//    }

//    private double intersectionT(float[] p1, float[] p2, float[] p3, float[] p4) {
//        double rX = p2[0] - p1[0];
//        double rY = p2[1] - p1[1];
//        double sX = p4[0] - p3[0];
//        double sY = p4[1] - p3[1];
//
//        double denom = rX * sY - rY * sX;
//        if (Math.abs(denom) < INTERSECTION_EPSILON) {
//            return -1.0;
//        }
//
//        double qmpX = p3[0] - p1[0];
//        double qmpY = p3[1] - p1[1];
//
//        double t = (qmpX * sY - qmpY * sX) / denom;
//        double u = (qmpX * rY - qmpY * rX) / denom;
//
//        return (t >= 0.0 && t <= 1.0 && u >= 0.0 && u <= 1.0) ? t : -1.0;
//    }

//    private boolean isTooCloseToAnyWall(float[] point, List<List<float[]>> walls, float clearance) {
//        for (List<float[]> polygon : walls) {
//            if (polygon == null || polygon.size() < 2) continue;
//
//            int edgeCount = getRingEdgeCount(polygon);
//
//            for (int i = 0; i < edgeCount; i++) {
//                float[] a = polygon.get(i);
//                float[] b = polygon.get((i + 1) % polygon.size());
//
//                if (distancePointToSegment(point, a, b) < clearance) {
//                    return true;
//                }
//            }
//        }
//        return false;
//    }

//    private float distancePointToSegment(float[] p, float[] a, float[] b) {
//        float px = p[0], py = p[1];
//        float ax = a[0], ay = a[1];
//        float bx = b[0], by = b[1];
//
//        float dx = bx - ax;
//        float dy = by - ay;
//        float len2 = dx * dx + dy * dy;
//
//        if (len2 < 1e-12f) {
//            float ex = px - ax;
//            float ey = py - ay;
//            return (float) Math.sqrt(ex * ex + ey * ey);
//        }
//
//        float t = ((px - ax) * dx + (py - ay) * dy) / len2;
//        t = Math.max(0f, Math.min(1f, t));
//
//        float cx = ax + t * dx;
//        float cy = ay + t * dy;
//
//        float ex = px - cx;
//        float ey = py - cy;
//        return (float) Math.sqrt(ex * ex + ey * ey);
//    }
//
//    private float[] moveToJustBeforeWall(float[] from, float[] to, float hitT, float snapBackMeters) {
//        float dx = to[0] - from[0];
//        float dy = to[1] - from[1];
//        float len = (float) Math.sqrt(dx * dx + dy * dy);
//
//        if (len < 1e-6f) {
//            return new float[]{from[0], from[1]};
//        }
//
//        float backT = snapBackMeters / len;
//        float safeT = Math.max(0f, hitT - backT);
//
//        return new float[]{
//                from[0] + safeT * dx,
//                from[1] + safeT * dy
//        };
//    }
//    private static final float MAX_GAP_WIDTH_METERS = 2.5f;
//    private static final float GAP_SEARCH_RADIUS_METERS = 3.0f;
//    private static final float MIN_GAP_WIDTH_METERS = 0.20f;
//    private static final float GAP_ROUTE_WEIGHT_PENALTY = 0.5f;
//
//    public void applyWallConstraints(
//            float[] prevEast,
//            float[] prevNorth,
//            float[] currEast,
//            float[] currNorth,
//            float[] weights,
//            CoordinateConverter converter) {
//
//        if (currentVenue == null || currentFloorKey == null) return;
//        IndoorVenue.FloorFeatures floor = currentVenue.floorFeatures.get(currentFloorKey);
//        if (floor == null || floor.wallPolygonsEnu == null || floor.wallPolygonsEnu.isEmpty()) return;
//
//        List<List<float[]>> walls = floor.wallPolygonsEnu;
//
//        for (int i = 0; i < weights.length; i++) {
//            if (weights[i] <= 0f) continue;
//
//            float[] prev = {prevEast[i], prevNorth[i]};
//            float[] curr = {currEast[i], currNorth[i]};
//
//            WallCrossing crossing = findFirstWallCrossing(prev, curr, walls);
//            if (crossing == null) continue;
//
//            float[] gapMid = findLocalGapNearCrossing(
//                    crossing.crossingPoint,
//                    walls,
//                    GAP_SEARCH_RADIUS_METERS,
//                    MIN_GAP_WIDTH_METERS,
//                    MAX_GAP_WIDTH_METERS
//            );
//
//            if (gapMid != null) {
//                boolean leg1Clear = findFirstWallCrossing(prev, gapMid, walls) == null;
//                boolean leg2Clear = findFirstWallCrossing(gapMid, curr, walls) == null;
//
//                if (leg1Clear && leg2Clear) {
//                    // Let particle continue to its intended destination,
//                    // but penalise for using an inferred opening.
//                    weights[i] *= GAP_ROUTE_WEIGHT_PENALTY;
//                    continue;
//                }
//            }
//
//            float[] snapped = snapToWallEnu(prev, curr, walls);
//            currEast[i] = snapped[0];
//            currNorth[i] = snapped[1];
//            weights[i] = 0f;
//        }
//    }
//
//    private float[] findLocalGapNearCrossing(
//            float[] crossingPoint,
//            List<List<float[]>> walls,
//            float searchRadius,
//            float minGapWidth,
//            float maxGapWidth) {
//
//        List<float[]> nearbyVertices = new ArrayList<>();
//
//        // Only collect vertices near the crossing point
//        for (List<float[]> polygon : walls) {
//            for (float[] pt : polygon) {
//                if (distance(pt, crossingPoint) <= searchRadius) {
//                    nearbyVertices.add(pt);
//                }
//            }
//        }
//
//        float bestScore = Float.MAX_VALUE;
//        float[] bestMid = null;
//
//        for (int i = 0; i < nearbyVertices.size(); i++) {
//            for (int j = i + 1; j < nearbyVertices.size(); j++) {
//                float[] a = nearbyVertices.get(i);
//                float[] b = nearbyVertices.get(j);
//
//                float gapWidth = distance(a, b);
//                if (gapWidth < minGapWidth || gapWidth > maxGapWidth) continue;
//
//                float[] mid = midpoint(a, b);
//
//                // midpoint itself must be close to the crossing
//                float midpointDist = distance(mid, crossingPoint);
//                if (midpointDist > searchRadius) continue;
//
//                // Prefer narrow gaps close to the crossing point
//                float score = midpointDist + 0.5f * gapWidth;
//
//                if (score < bestScore) {
//                    bestScore = score;
//                    bestMid = mid;
//                }
//            }
//        }
//
//        return bestMid;
//    }
//
//    private float distance(float[] a, float[] b) {
//        float dx = a[0] - b[0];
//        float dy = a[1] - b[1];
//        return (float) Math.sqrt(dx * dx + dy * dy);
//    }
//
//    private float[] midpoint(float[] a, float[] b) {
//        return new float[] {
//                0.5f * (a[0] + b[0]),
//                0.5f * (a[1] + b[1])
//        };
//    }
//
//    private static class WallCrossing {
//        float[] crossingPoint;
//        int polygonIndex;
//        int edgeIndex;
//        WallCrossing(float[] pt, int poly, int edge) {
//            crossingPoint = pt; polygonIndex = poly; edgeIndex = edge;
//        }
//    }
//
//    private WallCrossing findFirstWallCrossing(float[] from, float[] to,
//                                               List<List<float[]>> walls) {
//        double bestT = Double.MAX_VALUE;
//        WallCrossing best = null;
//
//        for (int pi = 0; pi < walls.size(); pi++) {
//            List<float[]> polygon = walls.get(pi);
//            for (int ei = 0; ei < polygon.size(); ei++) {
//                float[] a = polygon.get(ei);
//                float[] b = polygon.get((ei + 1) % polygon.size());
//
//                // Parametric intersection — find t along from→to where it hits a→b
//                double t = intersectionT(from, to, a, b);
//                if (t >= 0 && t <= 1 && t < bestT) {
//                    bestT = t;
//                    float[] pt = {
//                            (float)(from[0] + t * (to[0] - from[0])),
//                            (float)(from[1] + t * (to[1] - from[1]))
//                    };
//                    best = new WallCrossing(pt, pi, ei);
//                }
//            }
//        }
//        return best;
//    }
//
//    // Returns t ∈ [0,1] along segment p1→p2 where it intersects p3→p4, or -1 if no intersection
//    private double intersectionT(float[] p1, float[] p2, float[] p3, float[] p4) {
//        double d1x = p2[0] - p1[0], d1y = p2[1] - p1[1];
//        double d2x = p4[0] - p3[0], d2y = p4[1] - p3[1];
//        double cross = d1x * d2y - d1y * d2x;
//        if (Math.abs(cross) < 1e-6) return -1;
//
//        double t = ((p3[0] - p1[0]) * d2y - (p3[1] - p1[1]) * d2x) / cross;
//        double u = ((p3[0] - p1[0]) * d1y - (p3[1] - p1[1]) * d1x) / cross;
//        return (t >= 0 && t <= 1 && u >= 0 && u <= 1) ? t : -1;
//    }
//
//    private float[] findNearestGap(float[] nearPoint, List<List<float[]>> walls,
//                                   float maxGapWidth) {
//        float bestDist = Float.MAX_VALUE;
//        float[] bestMid = null;
//
//        // Collect all wall endpoints (start and end of each polygon)
//        List<float[]> endpoints = new ArrayList<>();
//        for (List<float[]> polygon : walls) {
//            // The last point of a closed polygon is the same as the first,
//            // so the "open" endpoints are just all points in the list
//
//            for (float[] pt : polygon) {
//                endpoints.add(pt);
//            }
//        }
//
//        // Find pairs of endpoints that are close together — those are gap edges
//        for (int i = 0; i < endpoints.size(); i++) {
//            for (int j = i + 1; j < endpoints.size(); j++) {
//                float[] a = endpoints.get(i);
//                float[] b = endpoints.get(j);
//
//                float dx = b[0] - a[0];
//                float dy = b[1] - a[1];
//                float dist = (float) Math.sqrt(dx * dx + dy * dy);
//
//                // Skip if the two endpoints are too far apart (not a gap) or
//                // touching (same point — closing edge of same polygon)
//                if (dist < 0.05f || dist > maxGapWidth) continue;
//
//                // Midpoint of this gap
//                float[] mid = {(a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f};
//
//                // Distance from the wall crossing to this gap
//                float dx2 = mid[0] - nearPoint[0];
//                float dy2 = mid[1] - nearPoint[1];
//                float gapDist = (float) Math.sqrt(dx2 * dx2 + dy2 * dy2);
//// Add this to findNearestGap temporarily for calibration
//                Log.d("GapDebug", "Gap found: dist=" + dist + "m at " + mid[0] + "," + mid[1]);
//                if (gapDist < bestDist) {
//                    bestDist = gapDist;
//                    bestMid = mid;
//                }
//            }
//        }
//
//        return bestMid; // null if no gap found within maxGapWidth
//    }
//    public void applyWallConstraints(
//            float[] prevEast, float[] prevNorth,
//            float[] currEast, float[] currNorth,
//            float[] weights,
//            CoordinateConverter converter) {
//
//        if (currentVenue == null || currentFloorKey == null) return;
//        IndoorVenue.FloorFeatures floor = currentVenue.floorFeatures.get(currentFloorKey);
//        if (floor == null || floor.wallPolygonsEnu.isEmpty()) {
//            Log.w("WallDebug", "No ENU wall polygons — did bakeEnuCoordinates run?");
//            return;
//        }
//
//        for (int i = 0; i < weights.length; i++) {
//            if (weights[i] == 0f) continue;
//
//            float[] prev = {prevEast[i], prevNorth[i]};
//            float[] curr = {currEast[i], currNorth[i]};
//
//            if (crossesAnyWallEnu(prev, curr, floor.wallPolygonsEnu)) {
//                // Snap back to just before the wall
//                float[] snapped = snapToWallEnu(prev, curr, floor.wallPolygonsEnu);
//                currEast[i]  = snapped[0];
//                currNorth[i] = snapped[1];
//                weights[i]   *= 0.1;
//            }
//        }
//    }

    private boolean crossesAnyWallEnu(float[] from, float[] to, List<List<float[]>> walls) {
        for (List<float[]> polygon : walls) {
            for (int i = 0; i < polygon.size(); i++) {
                float[] a = polygon.get(i);
                float[] b = polygon.get((i + 1) % polygon.size());
                if (segmentsIntersectEnu(from, to, a, b)) return true;
            }
        }
        return false;
    }


    private float[] snapToWallEnu(float[] from, float[] to,
                                  List<List<float[]>> allWalls) {
        float[] best = from.clone();
        double low = 0.0, high = 1.0;

        for (int iter = 0; iter < 24; iter++) {
            double mid = (low + high) / 2.0;
            float[] candidate = {
                    (float)(from[0] + mid * (to[0] - from[0])),
                    (float)(from[1] + mid * (to[1] - from[1]))
            };

            // Check candidate against ALL walls, not just the nearest
            if (crossesAnyWallEnu(from, candidate, allWalls)) {
                high = mid;
            } else {
                low = mid;
                best = candidate;
            }
        }
        return best;
    }

    private boolean segmentsIntersectEnu(float[] p1, float[] p2, float[] p3, float[] p4) {
        double eps = 1e-6;

        double o1 = orientation(p1, p2, p3);
        double o2 = orientation(p1, p2, p4);
        double o3 = orientation(p3, p4, p1);
        double o4 = orientation(p3, p4, p2);

        // Proper intersection
        if ((o1 > eps && o2 < -eps || o1 < -eps && o2 > eps) &&
                (o3 > eps && o4 < -eps || o3 < -eps && o4 > eps)) {
            return true;
        }

        // Collinear / endpoint cases
        if (Math.abs(o1) <= eps && onSegmentEnu(p1, p3, p2, eps)) return true;
        if (Math.abs(o2) <= eps && onSegmentEnu(p1, p4, p2, eps)) return true;
        if (Math.abs(o3) <= eps && onSegmentEnu(p3, p1, p4, eps)) return true;
        if (Math.abs(o4) <= eps && onSegmentEnu(p3, p2, p4, eps)) return true;

        return false;
    }

    private double orientation(float[] a, float[] b, float[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    private boolean onSegmentEnu(float[] a, float[] p, float[] b, double eps) {
        return p[0] >= Math.min(a[0], b[0]) - eps &&
                p[0] <= Math.max(a[0], b[0]) + eps &&
                p[1] >= Math.min(a[1], b[1]) - eps &&
                p[1] <= Math.max(a[1], b[1]) + eps;
    }

//    private boolean segmentsIntersectEnu(float[] p1, float[] p2, float[] p3, float[] p4) {
//        // All values in metres — no precision issues
//        double d1x = p2[0] - p1[0], d1y = p2[1] - p1[1];
//        double d2x = p4[0] - p3[0], d2y = p4[1] - p3[1];
//        double cross = d1x * d2y - d1y * d2x;
//        if (Math.abs(cross) < 1e-6) return false; // parallel, eps in m² is fine here
//
//        double t = ((p3[0] - p1[0]) * d2y - (p3[1] - p1[1]) * d2x) / cross;
//        double u = ((p3[0] - p1[0]) * d1y - (p3[1] - p1[1]) * d1x) / cross;
//        return t >= 0 && t <= 1 && u >= 0 && u <= 1;
//    }
//    public void applyWallConstraints(
//            float[] prevEast, float[] prevNorth,
//            float[] currEast, float[] currNorth,
//            float[] weights,
//            CoordinateConverter converter) {
//
//
//        if (currentVenue == null || currentFloorKey == null) return;
//        IndoorVenue.FloorFeatures floor =
//                currentVenue.floorFeatures.get(currentFloorKey);
//        if (floor == null || floor.wallPolygons.isEmpty()) return;
//
//        for (int i = 0; i < weights.length; i++) {
//            if (weights[i] == 0f) continue; // already dead, skip
//            Log.d("WallDebug", "Particle " + i +
//                    " prev=(" + prevEast[i] + "," + prevNorth[i] + ")" +
//                    " curr=(" + currEast[i] + "," + currNorth[i] + ")" +
//                    " floor = "+ currentFloorKey);
//
//            //convert prev and curr to LatLng
//            LatLng prev = toLatLng(prevEast[i], prevNorth[i], converter);
//            LatLng curr = toLatLng(currEast[i], currNorth[i], converter);
//
//            //check if cross walls on that floor
//            if (crossesAnyWall(prev, curr, floor.wallPolygons)) {
//                Log.d("WallDebug", "Particle " + i + " CROSSED WALL");
//                LatLng snapped = adjustPositionToNearestValidLocation(
//                        prev, curr, getNearestWallPolygon(prev, curr, floor.wallPolygons));
//                Log.d("WallDebug", "Snapped from " + curr + " → " + snapped);
//                float[] enu = converter.toEnu(snapped.latitude, snapped.longitude);
//                Log.d("WallDebug", "Snapped from " + curr + " → " + snapped);
//                //directly change currEast, currNorth, and weights arrays
//                currEast[i] = enu[0];
//                currNorth[i] = enu[1];
//                //downweight changed location
//                weights[i] *= 0.01f;
//                Log.d("WallDebug", "Weight updated: " + weights[i]);
//
//            }
//
//            }
//        Log.e("IndoorMapManager", "applied wall constraints!");
//
//        }
public void bakeEnuCoordinates(CoordinateConverter converter) {
        if (currentVenue == null || currentFloorKey == null) return;
        IndoorVenue.FloorFeatures floor = currentVenue.floorFeatures.get(currentFloorKey);
        if (floor == null) return;

        floor.wallPolygonsEnu.clear();
        for (List<LatLng> polygon : floor.wallPolygons) {
            List<float[]> enuPoly = new ArrayList<>();
            for (LatLng p : polygon) {
                enuPoly.add(converter.toEnu(p.latitude, p.longitude));
            }
            floor.wallPolygonsEnu.add(enuPoly);
        }
        Log.d("WallDebug", "Baked " + floor.wallPolygonsEnu.size() + " wall polygons to ENU");
}
    private List<LatLng> getNearestWallPolygon(LatLng from, LatLng to,
                                               List<List<LatLng>> wallPolygons) {
        for (List<LatLng> polygon : wallPolygons) {
            for (int i = 0; i < polygon.size(); i++) {
                LatLng wallA = polygon.get(i);
                LatLng wallB = polygon.get((i + 1) % polygon.size());
                if (segmentsIntersect(from, to, wallA, wallB)) return polygon;
            }
        }
        return wallPolygons.get(0); // fallback, shouldn't reach here
    }

    private boolean crossesAnyWall(LatLng from, LatLng to,
                                   List<List<LatLng>> wallPolygons) {
        for (List<LatLng> polygon : wallPolygons) {
            for (int i = 0; i < polygon.size(); i++) {
                LatLng wallA = polygon.get(i);
                LatLng wallB = polygon.get((i + 1) % polygon.size());
                if (segmentsIntersect(from, to, wallA, wallB)) {
                    Log.e("IndoorMapManager", "wall detected!");
                    return true;

                }
            }
        }
        return false;
    }

    private LatLng toLatLng(float eastM, float northM, CoordinateConverter c) {
        double[] ll = c.toLatLon(eastM, northM);
        return new LatLng(ll[0], ll[1]);
    }

    private float[] toEnu(LatLng latLng, CoordinateConverter c) {
        float[] enu = c.toEnu(latLng.latitude, latLng.longitude);
        return new float[]{(float) enu[0], (float) enu[1]};
    }

    //checks orientation using three points: 0= points on same line, 1 & 2 = points on either side
    private static int orientation(LatLng a, LatLng b, LatLng c) {

        double val =
                (b.longitude - a.longitude) * (c.latitude - a.latitude) -
                        (b.latitude - a.latitude) * (c.longitude - a.longitude);

        double eps = 1e-12;

//        if (Math.abs(val) < eps) {
//            return 0;      // collinear
//        }

        return (val > 0) ? 1 : 2;
    }

    /*
    takes endpoints of two segments and determines whether they intersect
     */
    private static boolean segmentsIntersect(LatLng a, LatLng b, LatLng c, LatLng d) {

        int o1 = orientation(a, b, c);
        int o2 = orientation(a, b, d);
        int o3 = orientation(c, d, a);
        int o4 = orientation(c, d, b);

        // Proper intersection
        if (o1 != o2 && o3 != o4) {
            return true;
        }

        // Special cases (collinear)
        if (o1 == 0 && onSegment(a, c, b)) return true;
        if (o2 == 0 && onSegment(a, d, b)) return true;
        if (o3 == 0 && onSegment(c, a, d)) return true;
        if (o4 == 0 && onSegment(c, b, d)) return true;

        return false;
    }

    private static boolean onSegment(LatLng a, LatLng p, LatLng b) {
        return p.latitude <= Math.max(a.latitude, b.latitude) &&
                p.latitude >= Math.min(a.latitude, b.latitude) &&
                p.longitude <= Math.max(a.longitude, b.longitude) &&
                p.longitude >= Math.min(a.longitude, b.longitude);
    }

    /// height change = sensorfusion.getelevation
    public LatLng indoorLocationCorrection(LatLng oldLocation,
                                           LatLng predictedLocation,
                                           float heightChange) {

        if (currentVenue == null || currentFloorKey == null ||
                oldLocation == null || predictedLocation == null) {
            return predictedLocation;
        }

        IndoorVenue.FloorFeatures floorFeatures =
                currentVenue.floorFeatures.get(currentFloorKey);

        if (floorFeatures == null ||
                floorFeatures.wallPolygonsEnu == null ||
                floorFeatures.wallPolygonsEnu.isEmpty()) {
            return predictedLocation;
        }

        CoordinateConverter c =
                SensorFusion.getInstance().getCoordinateConverter();

        if (c == null) return predictedLocation;

        float[] from = toEnu(oldLocation, c);
        float[] to = toEnu(predictedLocation, c);

        Log.d("MapMatch",
                "Checking movement from (" + from[0] + "," + from[1] + ") to (" +
                        to[0] + "," + to[1] + ")");

        WallCrossing crossing =
                findFirstWallCrossing(from, to, floorFeatures.wallPolygonsEnu);

        if (crossing == null) {
            return predictedLocation;
        }

        float[] corrected = moveToJustBeforeWall(from, to, crossing.t, 0.25f);

        Log.d("MapMatch",
                "Wall hit at (" + crossing.crossingPoint[0] + "," + crossing.crossingPoint[1] + ")" +
                        " → corrected to (" + corrected[0] + "," + corrected[1] + ")");

        return toLatLng(corrected[0], corrected[1], c);
    }
        //floor adjustment
        // if abs(heightChange) > heightThreshold:
        ////
        ////        if distance(correctedLocation, nearest stairs or lift) < proximityThreshold:
        ////
        ////        accept floor change
        ////
        ////        if horizontalMovement < horizontalThreshold:
        ////        mode = lift
        ////            else:
        ////        mode = stairs
        ////
        ////        else:
        ////        reject floor change
        ////        keep same floor



    //find nearest valid point that doesn't intersect walls
    //loop through points on segment just traversed starting from predicted location back towards prev location
    //use dx and dy
    //once we find point that doesn't intersect wall
    //corrected position = point
    //break

    private long lastFloorChangeTimeMs = 0;
    private static final long MIN_FLOOR_CHANGE_INTERVAL_MS = 5000; // 5 seconds minimum
    private static final double HEIGHT_THRESHOLD_METERS = 2.5;
    private static final double STAIRS_THRESHOLD_METERS = 5.0;
    private static final double LIFT_THRESHOLD_METERS = 4.0;
    private static final double LIFT_HORIZONTAL_THRESHOLD_METERS = 2.0;

    public String acceptFloorChange(LatLng correctedLocation,
                                    LatLng oldLocation,
                                    float currentHeight) {

        if (currentVenue == null ||
                currentFloorKey == null ||
                correctedLocation == null ||
                oldLocation == null) {
            return currentFloorKey;
        }

        if (confirmedFloorKey == null || Float.isNaN(confirmedFloorElevation)) {
            Log.d("MapMatch", "No confirmed floor reference yet");
            return currentFloorKey;
        }

        long now = System.currentTimeMillis();
        if (now - lastFloorChangeTimeMs < MIN_FLOOR_CHANGE_INTERVAL_MS) {
            Log.d("MapMatch", "Floor change blocked by debounce");
            return currentFloorKey;
        }

        float heightChangeMeters = currentHeight - confirmedFloorElevation;
        if (Math.abs(heightChangeMeters) < HEIGHT_THRESHOLD_METERS) {
            Log.d("MapMatch", "Height change too small: " + heightChangeMeters);
            return currentFloorKey;
        }

        IndoorVenue.FloorFeatures floorFeatures = currentVenue.floorFeatures.get(confirmedFloorKey);
        if (floorFeatures == null) {
            Log.d("MapMatch", "No floor features for confirmed floor: " + confirmedFloorKey);
            return currentFloorKey;
        }

        int direction = heightChangeMeters > 0 ? 1 : -1;
        String nextFloorKey = getAdjacentFloorKey(confirmedFloorKey, direction);

        Log.d("MapMatch", "confirmed floor: " + confirmedFloorKey);
        Log.d("MapMatch", "current displayed floor: " + currentFloorKey);
        Log.d("MapMatch", "candidate next floor: " + nextFloorKey);
        Log.d("MapMatch", "heightChange=" + heightChangeMeters);

        if (nextFloorKey == null || !currentVenue.floorFeatures.containsKey(nextFloorKey)) {
            Log.d("MapMatch", "Next floor invalid");
            return currentFloorKey;
        }

        boolean nearStairs = isNearAnyPoint(
                correctedLocation,
                floorFeatures.stairsCenters,
                STAIRS_THRESHOLD_METERS
        );

        boolean nearLift = isNearAnyPoint(
                correctedLocation,
                floorFeatures.liftCenters,
                LIFT_THRESHOLD_METERS
        );

        double horizontalDisplacement = distanceMeters(oldLocation, correctedLocation);

        boolean usedLift = nearLift && horizontalDisplacement < LIFT_HORIZONTAL_THRESHOLD_METERS;
        boolean usedStairs = nearStairs && horizontalDisplacement >= LIFT_HORIZONTAL_THRESHOLD_METERS;

        Log.d("MapMatch", "nearStairs=" + nearStairs + ", nearLift=" + nearLift);
        Log.d("MapMatch", "horizontalDisplacement=" + horizontalDisplacement);
        Log.d("MapMatch", "usedLift=" + usedLift + ", usedStairs=" + usedStairs);

        /// commented out right now because location accuracy is bad so algo never detects that it is near stairs/lift
        if (!usedLift && !usedStairs) {
            Log.d("MapMatch", "Rejected floor change: not near stairs/lift in a plausible way");
            return currentFloorKey;
        }

        if (nextFloorKey.equals(confirmedFloorKey)) {
            return currentFloorKey;
        }

        commitAutoFloorChange(nextFloorKey, currentHeight);
        lastFloorChangeTimeMs = now;

        Log.d("MapMatch", "Accepted floor change to " + nextFloorKey);
        showFloor(nextFloorKey);
        return nextFloorKey;
    }

    private void commitCurrentDisplayedFloor() {
        if (currentFloorKey == null) return;

        confirmedFloorKey = currentFloorKey;
        confirmedFloorElevation = SensorFusion.getInstance().getElevation();

        Log.d("IndoorMapManager", "Confirmed floor: " + confirmedFloorKey +
                " at elevation " + confirmedFloorElevation);
    }

    private String getAdjacentFloorKey(String floorKey, int direction) {
        if (currentVenue == null || currentVenue.rawMapShapes == null) return null;

        try {
            JSONObject floorsObj = new JSONObject(currentVenue.rawMapShapes);
            List<String> floorKeys = getSortedFloorKeys(floorsObj);

            int idx = floorKeys.indexOf(floorKey);
            if (idx < 0) return null;

            int next = idx + direction;
            if (next < 0 || next >= floorKeys.size()) return null;

            return floorKeys.get(next);

        } catch (JSONException e) {
            Log.e("IndoorMapManager", "getAdjacentFloorKey failed", e);
            return null;
        }
    }

    private double distanceMeters(LatLng a, LatLng b) {
        float[] result = new float[1];
        android.location.Location.distanceBetween(
                a.latitude, a.longitude,
                b.latitude, b.longitude,
                result
        );
        return result[0];
    }

    private boolean isNearAnyPoint(LatLng location, List<LatLng> centers, double thresholdMeters) {

        if (location == null || centers == null || centers.isEmpty()) {
            return false;
        }

        double result;
        for (LatLng center : centers) {
            result = distanceMeters(location, center);
            if (result <= thresholdMeters) {
                return true;
            }
        }

        return false;
    }

    public LatLng adjustPositionToNearestValidLocation(
            LatLng oldLocation,
            LatLng predictedLocation,
            List<LatLng> polygon) {

        LatLng bestValid = oldLocation;

        double low = 0.0;   // valid end
        double high = 1.0;  // invalid end

        //binary search
        for (int iter = 0; iter < 20; iter++) {
            double mid = (low + high) / 2.0;

            double lat = oldLocation.latitude +
                    mid * (predictedLocation.latitude - oldLocation.latitude);
            double lon = oldLocation.longitude +
                    mid * (predictedLocation.longitude - oldLocation.longitude);

            LatLng candidate = new LatLng(lat, lon);

            boolean intersectsWall = false;

            for (int i = 0; i < polygon.size(); i++) {
                LatLng wallStart = polygon.get(i);
                LatLng wallEnd = polygon.get((i + 1) % polygon.size());

                if (segmentsIntersect(oldLocation, candidate, wallStart, wallEnd)) {
                    intersectsWall = true;
                    break;
                }
            }

            if (intersectsWall) {
                high = mid;   // candidate is invalid, search closer to oldLocation
            } else {
                low = mid;    // candidate is valid, search closer to predictedLocation
                bestValid = candidate;
            }
        }

        return bestValid;
    }

    public void initializeFloorFromLocation(LatLng location) {
        if (currentVenue == null) return;

        double bestDist = Double.MAX_VALUE;
        String bestFloor = null;

        for (Map.Entry<String, IndoorVenue.FloorFeatures> entry : currentVenue.floorFeatures.entrySet()) {
            String floorKey = entry.getKey();
            IndoorVenue.FloorFeatures floor = entry.getValue();

            // use centroid of walls (simple heuristic)
            LatLng centroid = computeCentroid(floor.wallPolygons);
            double dist = distanceMeters(location, centroid);

            if (dist < bestDist) {
                bestDist = dist;
                bestFloor = floorKey;
            }
        }

        if (bestFloor != null) {
            currentFloorKey = bestFloor;
            Log.d("FloorInit", "Initial floor set to " + bestFloor);
        }
    }

    public LatLng computeCentroid(List<List<LatLng>> wallPolygons) {
        if (wallPolygons == null || wallPolygons.isEmpty()) {
            return null;
        }

        double sumLat = 0.0;
        double sumLon = 0.0;
        int polyCount = 0;

        for (List<LatLng> polygon : wallPolygons) {
            if (polygon == null || polygon.isEmpty()) continue;

            double polyLat = 0.0;
            double polyLon = 0.0;

            for (LatLng point : polygon) {
                polyLat += point.latitude;
                polyLon += point.longitude;
            }

            polyLat /= polygon.size();
            polyLon /= polygon.size();

            sumLat += polyLat;
            sumLon += polyLon;
            polyCount++;
        }

        if (polyCount == 0) return null;

        return new LatLng(sumLat / polyCount, sumLon / polyCount);
    }
    /**
     * Increase floor within the current venue.
     */
    public void increaseFloor() {
        switchFloor(+1);
    }


    /**
     * Decrease floor within the current venue.
     */
    public void decreaseFloor() {
        switchFloor(-1);
    }


    /**
     * Switch floor based on direction (+1 or -1)
     */
    private void switchFloor(int direction) {
        if (currentVenue == null || currentVenue.rawMapShapes == null) return;

        try {
            JSONObject floorsObj = new JSONObject(currentVenue.rawMapShapes);
            List<String> floorKeys = getSortedFloorKeys(floorsObj); // already has all keys

            int idx = floorKeys.indexOf(currentFloorKey);
            if (idx < 0) return;

            int next = idx + direction;
            if (next >= 0 && next < floorKeys.size()) {
                currentFloorKey = floorKeys.get(next);
                clearIndoorFloor();
                drawFloor(floorsObj.getJSONObject(currentFloorKey), currentFloorKey);
                isIndoorMapSet = true;
                Log.d("IndoorMapManager", "Switched to floor: " + currentFloorKey);
                bakeEnuCoordinates(SensorFusion.getInstance().getCoordinateConverter());


                // Delay the floor commit
                floorCommitHandler.removeCallbacks(commitBrowsedFloorRunnable);
                floorCommitHandler.postDelayed(commitBrowsedFloorRunnable, FLOOR_COMMIT_DELAY_MS);

            }

        } catch (JSONException e) {
            Log.e("IndoorMapManager", "Floor switch failed", e);
        }
    }

    private void showFloor(String floorKey) {
        if (currentVenue == null || currentVenue.rawMapShapes == null || floorKey == null) return;

        try {
            JSONObject floorsObj = new JSONObject(currentVenue.rawMapShapes);

            currentFloorKey = floorKey;
            clearIndoorFloor();
            Log.d("showFloor", "drawing floor "+ currentFloorKey);
            drawFloor(floorsObj.getJSONObject(currentFloorKey), currentFloorKey);
            isIndoorMapSet = true;
            bakeEnuCoordinates(SensorFusion.getInstance().getCoordinateConverter());

            Log.d("IndoorMapManager", "Showing floor: " + currentFloorKey);

        } catch (JSONException e) {
            Log.e("IndoorMapManager", "Failed to show floor: " + floorKey, e);
        }
    }

    private void commitAutoFloorChange(String newFloorKey, float elevation) {
        currentFloorKey = newFloorKey;
        confirmedFloorKey = newFloorKey;
        confirmedFloorElevation = elevation;

        floorCommitHandler.removeCallbacks(commitBrowsedFloorRunnable);

        try {
            JSONObject floorsObj = new JSONObject(currentVenue.rawMapShapes);
            clearIndoorFloor();
            drawFloor(floorsObj.getJSONObject(currentFloorKey), currentFloorKey);
            isIndoorMapSet = true;
            bakeEnuCoordinates(SensorFusion.getInstance().getCoordinateConverter());
        } catch (JSONException e) {
            Log.e("IndoorMapManager", "Auto floor redraw failed", e);
        }

        Log.d("IndoorMapManager", "AUTO confirmed floor: " + confirmedFloorKey +
                " at elevation " + confirmedFloorElevation);
    }



    /**
     * Draw a single floor from GeoJSON FeatureCollection.
     */
    private void drawFloor(JSONObject floorGeoJson, String floorKey) throws JSONException {

        IndoorVenue.FloorFeatures features = new IndoorVenue.FloorFeatures();

        JSONArray featuresArray = floorGeoJson.getJSONArray("features");

        for (int i = 0; i < featuresArray.length(); i++) {

            JSONObject feature = featuresArray.getJSONObject(i);

            JSONObject props = feature.optJSONObject("properties");
            String indoorType = props != null ? props.optString("indoor_type", "") : "";
            String t = indoorType == null ? "" : indoorType.toLowerCase();

            JSONObject geometry = feature.getJSONObject("geometry");
            String geomType = geometry.optString("type", "");

            if (!"MultiPolygon".equalsIgnoreCase(geomType)) continue;

            JSONArray multiPoly = geometry.getJSONArray("coordinates");

            // Style defaults
            int strokeColor = Color.argb(220, 20, 20, 20);
            int fillColor   = Color.argb(25,  60, 130, 255);
            float strokeWidth = 2.0f;

            if (t.contains("wall")) {
//                strokeColor = Color.RED;
                fillColor = Color.argb(0, 250, 0, 0);
                strokeWidth = 3.5f;
            } else if (t.contains("lift")) {
                strokeColor = Color.RED;
                fillColor = Color.argb(0, 0, 0, 0);
                strokeWidth = 8f;
            } else if (t.contains("stairs")) {
                strokeColor = Color.YELLOW;
                fillColor = Color.argb(35, 60, 130, 255);
                strokeWidth = 8f;
            } else if (t.contains("room") || t.contains("area")) {
                fillColor = Color.argb(30, 60, 130, 255);
                strokeWidth = 1.2f;
            } else {
                fillColor = Color.argb(15, 60, 130, 255);
                strokeWidth = 1.0f;
            }

            for (int j = 0; j < multiPoly.length(); j++) {

                JSONArray polygon = multiPoly.getJSONArray(j);
                if (polygon.length() == 0) continue;

                JSONArray outerRing = polygon.getJSONArray(0);
                List<LatLng> outerPoints = toLatLngList(outerRing);

                if (outerPoints.size() < 3) continue;
                if (isTiny(outerPoints)) continue;

                // Store by type for map matching
                if (t.contains("wall")) {
                    features.wallPolygons.add(outerPoints);
                } else if (t.contains("stair")) {
                    features.stairsCenters.add(centroidOf(outerPoints));
                } else if (t.contains("lift") || t.contains("elevator")) {
                    features.liftCenters.add(centroidOf(outerPoints));
                }
                Log.d("IndoorTypes", "Feature " + i + " indoor_type='" + indoorType +
                        "' geomType=" + geomType + " points=" + outerPoints.size());

                PolygonOptions opts = new PolygonOptions()
                        .addAll(outerPoints)
                        .strokeColor(strokeColor)
                        .strokeWidth(strokeWidth)
                        .fillColor(fillColor);

                for (int k = 1; k < polygon.length(); k++) {
                    JSONArray holeRing = polygon.getJSONArray(k);
                    List<LatLng> holePoints = toLatLngList(holeRing);
                    if (holePoints.size() >= 3) {
                        opts.addHole(holePoints);
                    }
                }

                Polygon polyObj = gMap.addPolygon(opts);
                activeFloorPolygons.add(polyObj);

                if (props != null) {
                    Log.d("IndoorTypes", "Found indoor_type=" + indoorType);
                }
            }
        }

        // Store features on current venue
        if (currentVenue != null) {
            currentVenue.floorFeatures.put(floorKey, features);
            Log.d("IndoorMapManager", "Floor " + floorKey +
                    " walls="  + features.wallPolygons.size() +
                    " stairs=" + features.stairsCenters.size() +
                    " lifts="  + features.liftCenters.size());
        }
    }
    /**
     * Convert a GeoJSON ring ([[lon,lat], ...]) into List<LatLng>.
     * GeoJSON uses [lon,lat] order.
     */
    private List<LatLng> toLatLngList(JSONArray ring) throws JSONException {
        List<LatLng> points = new ArrayList<>(ring.length());
        for (int m = 0; m < ring.length(); m++) {
            JSONArray coord = ring.getJSONArray(m);
            double lon = coord.getDouble(0);
            double lat = coord.getDouble(1);
            points.add(new LatLng(lat, lon));
        }
        return points;
    }

    private LatLng centroidOf(List<LatLng> pts) {
        double lat = 0, lon = 0;
        for (LatLng p : pts) { lat += p.latitude; lon += p.longitude; }
        return new LatLng(lat / pts.size(), lon / pts.size());
    }

    /**
     * Heuristic to drop tiny polygons that clutter the view.
     * Adjust thresholds if needed.
     */
    private boolean isTiny(List<LatLng> pts) {
        double minLat = Double.POSITIVE_INFINITY, minLon = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY, maxLon = Double.NEGATIVE_INFINITY;

        for (LatLng p : pts) {
            minLat = Math.min(minLat, p.latitude);
            minLon = Math.min(minLon, p.longitude);
            maxLat = Math.max(maxLat, p.latitude);
            maxLon = Math.max(maxLon, p.longitude);
        }

        double dLat = maxLat - minLat;
        double dLon = maxLon - minLon;

        // ~1e-6 deg is ~0.11m lat-wise; tune as needed
        return (dLat * dLon) < 2e-12;
    }



    private List<String> getSortedFloorKeys(JSONObject floorsObj) throws JSONException {
        List<String> keys = new ArrayList<>();
        Iterator<String> it = floorsObj.keys();
        while (it.hasNext()) keys.add(it.next());

        keys.sort((a, b) -> Integer.compare(floorOrderValue(a), floorOrderValue(b)));
        return keys;
    }

    private int floorOrderValue(String key) {
        // Basement floors: B3 < B2 < B1
        if (key == null) return 9999;
        key = key.trim().toUpperCase();

        if (key.startsWith("B")) {
            try {
                int n = Integer.parseInt(key.substring(1));
                return -100 - n; // B3 -> -103, B1 -> -101
            } catch (Exception ignored) {
                return -150;
            }
        }

        // Ground floor
        if (key.equals("G") || key.equals("GF") || key.equals("GROUND")) return 0;

        // Numeric floors
        try {
            return Integer.parseInt(key);
        } catch (Exception ignored) {
            return 1000;
        }
    }

    /**
     * Removes currently drawn indoor floor polygons.
     */
    private void clearIndoorFloor() {
        for (Polygon p : activeFloorPolygons) {
            p.remove();
        }
        activeFloorPolygons.clear();

    }

    public void clearSelection() {
        clearIndoorFloor();
        currentVenue = null;
        currentFloorKey = null;
        isIndoorMapSet = false;
    }



    /**
     * Getter to obtain if currently an indoor floor map is being displayed
     */
    public boolean getIsIndoorMapSet() {
        return isIndoorMapSet;
    }

    public String getCurrentFloorKey() {
        return currentFloorKey;
    }

    public String getConfirmedFloorKey() {
        return confirmedFloorKey;
    }

    public float getConfirmedFloorElevation() {
        return confirmedFloorElevation;
    }

    public IndoorVenue getCurrentVenue() { return currentVenue; }

    public IndoorVenue.FloorFeatures getCurrentFloorFeatures() {
        if (currentVenue == null || currentFloorKey == null) return null;
        return currentVenue.floorFeatures.get(currentFloorKey);
    }


}
