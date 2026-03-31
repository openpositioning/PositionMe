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
import android.os.Handler;
import android.os.Looper;
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

    // Confirmed floor — set after a successful auto or manual floor change.
    // Used by acceptFloorChange() to measure height delta from a known baseline.
    private String confirmedFloorKey = null;
    private float  confirmedFloorElevation = Float.NaN;

    // Delay before a manual browse (floor up/down button) is committed as confirmed.
    // Prevents accidental floor commits when the user taps the button quickly.
    private static final long FLOOR_COMMIT_DELAY_MS = 1500;
    private final Handler  floorCommitHandler = new Handler(Looper.getMainLooper());
    private final Runnable commitBrowsedFloorRunnable = this::commitCurrentDisplayedFloor;

    // Map polygon (outline) → venue mapping
    private final Map<Polygon, IndoorVenue> polygonToVenue = new HashMap<>();

    // List of currently drawn indoor floor polygons
    private final List<Polygon> activeFloorPolygons = new ArrayList<>();

    final double floorDistThresh = 5.0;
    final double liftDistThresh = 3.0;


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
     * For each particle, zero its weight if the step from prev to current
     * position crosses a wall. Called by ParticleFilter after predict(),
     * before resample().
     *
     * @param prevEast   previous East positions, metres (length 300)
     * @param prevNorth  previous North positions, metres (length 300)
     * @param currEast   current East positions after predict() (length 300)
     * @param currNorth  current North positions after predict() (length 300)
     * @param weights    weight array — modified in-place
     * @param converter  to convert East-North → LatLng for wall geometry check
     */
    public void applyWallConstraints(
            float[] prevEast, float[] prevNorth,
            float[] currEast, float[] currNorth,
            float[] weights,
            CoordinateConverter converter) {

        if (currentVenue == null || currentFloorKey == null) return;
        IndoorVenue.FloorFeatures floor = currentVenue.floorFeatures.get(currentFloorKey);
        if (floor == null || floor.wallPolygonsEnu.isEmpty()) {
            Log.w("WallDebug", "No ENU wall polygons — did bakeEnuCoordinates run?");
            return;
        }

        for (int i = 0; i < weights.length; i++) {
            if (weights[i] == 0f) continue;

            float[] prev = {prevEast[i], prevNorth[i]};
            float[] curr = {currEast[i], currNorth[i]};

            if (crossesAnyWallEnu(prev, curr, floor.wallPolygonsEnu)) {
                // Snap particle to last valid position and apply strong weight penalty
                float[] snapped = snapToWallEnu(prev, curr, floor.wallPolygonsEnu);
                currEast[i]  = snapped[0];
                currNorth[i] = snapped[1];
                weights[i]  *= 0.01f;
            }
        }
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

    private boolean segmentsIntersectEnu(float[] p1, float[] p2, float[] p3, float[] p4) {
        // All values in metres — no precision issues
        double d1x = p2[0] - p1[0], d1y = p2[1] - p1[1];
        double d2x = p4[0] - p3[0], d2y = p4[1] - p3[1];
        double cross = d1x * d2y - d1y * d2x;
        if (Math.abs(cross) < 1e-6) return false; // parallel, eps in m² is fine here

        double t = ((p3[0] - p1[0]) * d2y - (p3[1] - p1[1]) * d2x) / cross;
        double u = ((p3[0] - p1[0]) * d1y - (p3[1] - p1[1]) * d1x) / cross;
        return t >= 0 && t <= 1 && u >= 0 && u <= 1;
    }
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

    // -------------------------------------------------------------------------
    // EKF single-position wall constraint (added from teammate's map-matching work)
    // The particle-filter batch method above is kept unchanged for SensorFusion.
    // -------------------------------------------------------------------------

    private static final float INTERSECTION_EPSILON = 1e-6f;
    private static final float RING_CLOSURE_EPSILON  = 1e-4f;
    private static final float WALL_SNAP_BACK_METERS = 0.05f;

    /**
     * Clamps a single EKF position step to wall boundaries using binary search.
     * If fromEnu → toEnu crosses a wall, returns the last valid position before the wall.
     *
     * @param fromEnu float[]{east, north} — previous EKF position (metres)
     * @param toEnu   float[]{east, north} — new EKF position after predict()
     * @return clamped position, or toEnu unchanged if no wall was crossed
     */
    public float[] clampToWallEnu(float[] fromEnu, float[] toEnu) {
        if (currentVenue == null || currentFloorKey == null) return toEnu;
        IndoorVenue.FloorFeatures floor = currentVenue.floorFeatures.get(currentFloorKey);
        if (floor == null || floor.wallPolygonsEnu.isEmpty()) return toEnu;
        if (!crossesAnyWallEnu(fromEnu, toEnu, floor.wallPolygonsEnu)) return toEnu;
        return snapToWallEnu(fromEnu, toEnu, floor.wallPolygonsEnu);
    }

    /**
     * Constrains a single EKF position step to wall boundaries using precise
     * crossing-point detection. Snaps back WALL_SNAP_BACK_METERS before the wall.
     *
     * @param fromEnu float[]{east, north} — previous position (metres)
     * @param toEnu   float[]{east, north} — new position after predict()
     * @return constrained position, or toEnu unchanged if no wall was crossed
     */
    public float[] constrainMovementToWalls(float[] fromEnu, float[] toEnu) {
        if (fromEnu == null || toEnu == null) return toEnu;
        if (currentVenue == null || currentFloorKey == null) return toEnu;
        IndoorVenue.FloorFeatures floorFeatures = currentVenue.floorFeatures.get(currentFloorKey);
        if (floorFeatures == null || floorFeatures.wallPolygonsEnu == null
                || floorFeatures.wallPolygonsEnu.isEmpty()) return toEnu;

        WallCrossing crossing = findFirstWallCrossing(fromEnu, toEnu, floorFeatures.wallPolygonsEnu);
        if (crossing == null) return new float[]{toEnu[0], toEnu[1]};
        return moveToJustBeforeWall(fromEnu, toEnu, crossing.t, WALL_SNAP_BACK_METERS);
    }

    private static class WallCrossing {
        final float[] crossingPoint;
        final int polygonIndex;
        final int edgeIndex;
        final float t;

        WallCrossing(float[] crossingPoint, int polygonIndex, int edgeIndex, float t) {
            this.crossingPoint = crossingPoint;
            this.polygonIndex  = polygonIndex;
            this.edgeIndex     = edgeIndex;
            this.t             = t;
        }
    }

    private WallCrossing findFirstWallCrossing(float[] from, float[] to,
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
                    float[] cp = new float[]{
                            (float)(from[0] + t * (to[0] - from[0])),
                            (float)(from[1] + t * (to[1] - from[1]))
                    };
                    best = new WallCrossing(cp, pi, ei, (float) t);
                }
            }
        }
        return best;
    }

    private int getRingEdgeCount(List<float[]> ring) {
        if (ring == null || ring.size() < 2) return 0;
        float[] first = ring.get(0);
        float[] last  = ring.get(ring.size() - 1);
        boolean closed = Math.abs(first[0] - last[0]) < RING_CLOSURE_EPSILON
                      && Math.abs(first[1] - last[1]) < RING_CLOSURE_EPSILON;
        return closed ? ring.size() - 1 : ring.size();
    }

    private double intersectionT(float[] p1, float[] p2, float[] p3, float[] p4) {
        double rX = p2[0] - p1[0], rY = p2[1] - p1[1];
        double sX = p4[0] - p3[0], sY = p4[1] - p3[1];
        double denom = rX * sY - rY * sX;
        if (Math.abs(denom) < INTERSECTION_EPSILON) return -1.0;
        double qmpX = p3[0] - p1[0], qmpY = p3[1] - p1[1];
        double t = (qmpX * sY - qmpY * sX) / denom;
        double u = (qmpX * rY - qmpY * rX) / denom;
        return (t >= 0.0 && t <= 1.0 && u >= 0.0 && u <= 1.0) ? t : -1.0;
    }

    private float[] moveToJustBeforeWall(float[] from, float[] to, float hitT,
                                         float snapBackMeters) {
        float dx = to[0] - from[0];
        float dy = to[1] - from[1];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6f) return new float[]{from[0], from[1]};
        float backT = snapBackMeters / len;
        float safeT = Math.max(0f, hitT - backT);
        return new float[]{from[0] + safeT * dx, from[1] + safeT * dy};
    }

    // -------------------------------------------------------------------------

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
    public LatLng indoorLocationCorrection(LatLng oldLocation, LatLng predictedLocation, float heightChange) {
        if (currentVenue == null || currentFloorKey == null || oldLocation == null || predictedLocation == null) {
            return predictedLocation;
        }
        Log.d("MapMatch", "Checking movement from " + oldLocation + " to " + predictedLocation +
                " on floor " + currentFloorKey);

        LatLng correctedLocation = predictedLocation;
        boolean hitWall = false;

        IndoorVenue.FloorFeatures floorFeatures = currentVenue.floorFeatures.get(currentFloorKey);
        if (floorFeatures == null) {
            return predictedLocation;
        }

        List<List<LatLng>> wallPolygons = floorFeatures.wallPolygons;

        for (int k = 0; k < wallPolygons.size() && !hitWall; k++) {
            List<LatLng> polygon = wallPolygons.get(k);

            for (int i = 0; i < polygon.size(); i++) {
                LatLng edgeStart = polygon.get(i);
                LatLng edgeEnd = polygon.get((i + 1) % polygon.size());

                if (segmentsIntersect(oldLocation, predictedLocation, edgeStart, edgeEnd)) {
                    correctedLocation = adjustPositionToNearestValidLocation(
                            oldLocation,
                            predictedLocation,
                            polygon
                    );
                    hitWall = true;
                    Log.d("MapMatch", "Wall intersection detected");
                    Log.d("MapMatch", "Corrected location = " + correctedLocation);
                    break;
                }
            }
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

        return correctedLocation;
    }

    //find nearest valid point that doesn't intersect walls
    //loop through points on segment just traversed starting from predicted location back towards prev location
    //use dx and dy
    //once we find point that doesn't intersect wall
    //corrected position = point
    //break

    // ── Floor-change detection thresholds ────────────────────────────────────
    private static final double HEIGHT_THRESHOLD_METERS           = 4.5;
    private static final double STAIRS_THRESHOLD_METERS           = 12.0;
    private static final double LIFT_THRESHOLD_METERS             = 10.0;
    private static final double LIFT_HORIZONTAL_THRESHOLD_METERS  = 1.0;

    // Debounce: ignore floor-change attempts within 5 s of the last one
    private long lastFloorChangeTimeMs = 0;
    private static final long MIN_FLOOR_CHANGE_INTERVAL_MS = 5000;

    // Floor transition tracking
    private static final float FLOOR_STABLE_BAND_METERS              = 0.5f;
    private static final float FLOOR_TRANSITION_START_THRESHOLD_METERS = 1.0f;
    private LatLng  floorTransitionStartLocation = null;
    private LatLng  lastStableFloorLocation      = null;
    private boolean floorTransitionInProgress    = false;

    /**
     * Evaluates whether a floor change has occurred.
     *
     * @param correctedLocation current horizontal position
     * @param oldLocation       previous horizontal position
     * @param currentHeight     absolute barometric elevation (metres), from SensorFusion.getElevation()
     * @return FloorChangeResult carrying the (possibly new) floor key,
     *         an optionally snapped destination, and a flag indicating whether the floor changed
     */
    public FloorChangeResult acceptFloorChange(LatLng correctedLocation,
                                               LatLng oldLocation,
                                               float currentHeight) {
        if (currentVenue == null || currentFloorKey == null
                || correctedLocation == null || oldLocation == null) {
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }

        if (confirmedFloorKey == null || Float.isNaN(confirmedFloorElevation)) {
            Log.d("MapMatch", "No confirmed floor reference yet");
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }

        // Debounce: block rapid successive floor changes
        long now = System.currentTimeMillis();
        if (now - lastFloorChangeTimeMs < MIN_FLOOR_CHANGE_INTERVAL_MS) {
            Log.d("MapMatch", "Floor change blocked by debounce");
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }

        updateFloorTransitionState(correctedLocation, currentHeight);
        double horizontalDisplacement = getFloorTransitionHorizontalDisplacement(correctedLocation);

        float heightChangeMeters = currentHeight - confirmedFloorElevation;
        if (Math.abs(heightChangeMeters) < HEIGHT_THRESHOLD_METERS) {
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }

        IndoorVenue.FloorFeatures currentFloorFeatures =
                currentVenue.floorFeatures.get(confirmedFloorKey);
        if (currentFloorFeatures == null) {
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }

        String nextFloorKey = getAdjacentFloorKey(confirmedFloorKey, heightChangeMeters > 0);
        Log.d("MapMatch", "confirmed floor: " + confirmedFloorKey
                + "  candidate next: " + nextFloorKey
                + "  heightChange=" + heightChangeMeters);

        if (nextFloorKey == null || !currentVenue.floorFeatures.containsKey(nextFloorKey)) {
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }

        IndoorVenue.FloorFeatures nextFloorFeatures =
                currentVenue.floorFeatures.get(nextFloorKey);
        if (nextFloorFeatures == null) {
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }

        boolean nearStairs = isNearAnyPoint(correctedLocation,
                currentFloorFeatures.stairsCenters, STAIRS_THRESHOLD_METERS);
        boolean nearLift   = isNearAnyPoint(correctedLocation,
                currentFloorFeatures.liftCenters,   LIFT_THRESHOLD_METERS);

        boolean usedLift   = nearLift   && horizontalDisplacement < LIFT_HORIZONTAL_THRESHOLD_METERS;
        boolean usedStairs = nearStairs && horizontalDisplacement >= LIFT_HORIZONTAL_THRESHOLD_METERS;

        Log.d("MapMatch", "nearStairs=" + nearStairs + ", nearLift=" + nearLift
                + ", horizontalDisplacement=" + horizontalDisplacement
                + ", usedLift=" + usedLift + ", usedStairs=" + usedStairs);

        if (!usedLift && !usedStairs) {
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }

        if (nextFloorKey.equals(confirmedFloorKey)) {
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }

        // Snap destination to the nearest access point on the new floor
        LatLng snappedDestination = correctedLocation;
        LatLng highlightCenter    = null;

        if (usedLift) {
            LatLng nearestLift = getNearestPoint(correctedLocation, nextFloorFeatures.liftCenters);
            if (nearestLift != null) { snappedDestination = nearestLift; highlightCenter = nearestLift; }
        } else {
            LatLng nearestStairs = getNearestPoint(correctedLocation, nextFloorFeatures.stairsCenters);
            if (nearestStairs != null) { snappedDestination = nearestStairs; highlightCenter = nearestStairs; }
        }

        commitAutoFloorChange(nextFloorKey, currentHeight);
        lastFloorChangeTimeMs = now;
        resetFloorTransitionState();
        showFloor(nextFloorKey);

        Log.d("MapMatch", "Accepted floor change to " + nextFloorKey
                + " snapped=" + snappedDestination);

        return new FloorChangeResult(nextFloorKey, snappedDestination, true, highlightCenter);
    }

    // ── Floor transition helpers ──────────────────────────────────────────────

    private void updateFloorTransitionState(LatLng currentLocation, float currentHeight) {
        if (currentLocation == null || confirmedFloorKey == null
                || Float.isNaN(confirmedFloorElevation)) return;

        float absDelta = Math.abs(currentHeight - confirmedFloorElevation);

        if (absDelta < FLOOR_STABLE_BAND_METERS) {
            lastStableFloorLocation   = currentLocation;
            floorTransitionInProgress = false;
            floorTransitionStartLocation = null;
            return;
        }

        if (!floorTransitionInProgress
                && absDelta >= FLOOR_TRANSITION_START_THRESHOLD_METERS) {
            floorTransitionInProgress    = true;
            floorTransitionStartLocation = (lastStableFloorLocation != null)
                    ? lastStableFloorLocation : currentLocation;
            Log.d("MapMatch", "Floor transition started at " + floorTransitionStartLocation);
        }
    }

    private double getFloorTransitionHorizontalDisplacement(LatLng currentLocation) {
        if (!floorTransitionInProgress || floorTransitionStartLocation == null
                || currentLocation == null) return 0.0;
        return distanceMeters(floorTransitionStartLocation, currentLocation);
    }

    private void resetFloorTransitionState() {
        floorTransitionInProgress    = false;
        floorTransitionStartLocation = null;
        lastStableFloorLocation      = null;
    }

    private LatLng getNearestPoint(LatLng location, List<LatLng> centers) {
        if (location == null || centers == null || centers.isEmpty()) return null;
        LatLng nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (LatLng center : centers) {
            double d = distanceMeters(location, center);
            if (d < bestDist) { bestDist = d; nearest = center; }
        }
        return nearest;
    }

    // ── FloorChangeResult ─────────────────────────────────────────────────────

    /** Result of an acceptFloorChange() call. */
    public static class FloorChangeResult {
        public final String  floorKey;
        public final LatLng  snappedLocation;
        public final boolean changedFloor;
        public final LatLng  highlightCenter;

        public FloorChangeResult(String floorKey, LatLng snappedLocation,
                                 boolean changedFloor, LatLng highlightCenter) {
            this.floorKey        = floorKey;
            this.snappedLocation = snappedLocation;
            this.changedFloor    = changedFloor;
            this.highlightCenter = highlightCenter;
        }
    }

    private String getAdjacentFloorKey(String floorKey, boolean goingUp) {
        if (currentVenue == null || currentVenue.rawMapShapes == null) return null;
        try {
            JSONObject floorsObj = new JSONObject(currentVenue.rawMapShapes);
            List<String> floorKeys = getSortedFloorKeys(floorsObj);
            int idx = floorKeys.indexOf(floorKey);
            if (idx < 0) return null;
            int next = idx + (goingUp ? +1 : -1);
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
     * Switch floor based on direction (+1 or -1).
     * After drawing, schedules a delayed commit so rapid taps don't
     * prematurely update the confirmed elevation baseline.
     */
    private void switchFloor(int direction) {
        if (currentVenue == null || currentVenue.rawMapShapes == null) return;

        try {
            JSONObject floorsObj = new JSONObject(currentVenue.rawMapShapes);
            List<String> floorKeys = getSortedFloorKeys(floorsObj);

            int idx = floorKeys.indexOf(currentFloorKey);
            if (idx < 0) return;

            int next = idx + direction;
            if (next >= 0 && next < floorKeys.size()) {
                currentFloorKey = floorKeys.get(next);
                clearIndoorFloor();
                drawFloor(floorsObj.getJSONObject(currentFloorKey), currentFloorKey);
                isIndoorMapSet = true;
                bakeEnuCoordinates(SensorFusion.getInstance().getCoordinateConverter());
                Log.d("IndoorMapManager", "Switched to floor: " + currentFloorKey);

                // Delay before treating this as the confirmed floor baseline
                floorCommitHandler.removeCallbacks(commitBrowsedFloorRunnable);
                floorCommitHandler.postDelayed(commitBrowsedFloorRunnable, FLOOR_COMMIT_DELAY_MS);
            }

        } catch (JSONException e) {
            Log.e("IndoorMapManager", "Floor switch failed", e);
        }
    }

    /**
     * Records the currently displayed floor as confirmed and snapshots the
     * current barometric elevation as the baseline for future floor-change detection.
     */
    private void commitCurrentDisplayedFloor() {
        if (currentFloorKey == null) return;
        confirmedFloorKey       = currentFloorKey;
        confirmedFloorElevation = SensorFusion.getInstance().getElevation();
        Log.d("IndoorMapManager", "Confirmed floor: " + confirmedFloorKey
                + " at elevation " + confirmedFloorElevation);
    }

    /**
     * Redraws the map for {@code newFloorKey} and commits it as the confirmed floor.
     * Called by acceptFloorChange() after an automatic floor transition is validated.
     */
    private void commitAutoFloorChange(String newFloorKey, float elevation) {
        currentFloorKey         = newFloorKey;
        confirmedFloorKey       = newFloorKey;
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
        Log.d("IndoorMapManager", "AUTO confirmed floor: " + confirmedFloorKey
                + " at elevation " + confirmedFloorElevation);
    }

    /**
     * Shows a floor on the map without changing confirmedFloorKey.
     * Useful for previewing a floor before the change is validated.
     */
    private void showFloor(String floorKey) {
        if (currentVenue == null || currentVenue.rawMapShapes == null || floorKey == null) return;
        try {
            JSONObject floorsObj = new JSONObject(currentVenue.rawMapShapes);
            currentFloorKey = floorKey;
            clearIndoorFloor();
            drawFloor(floorsObj.getJSONObject(currentFloorKey), currentFloorKey);
            isIndoorMapSet = true;
            bakeEnuCoordinates(SensorFusion.getInstance().getCoordinateConverter());
            Log.d("IndoorMapManager", "Showing floor: " + currentFloorKey);
        } catch (JSONException e) {
            Log.e("IndoorMapManager", "Failed to show floor: " + floorKey, e);
        }
    }

    /** Returns the last confirmed floor key, or null if not yet set. */
    public String getConfirmedFloorKey() { return confirmedFloorKey; }

    /** Returns the barometric elevation recorded when the floor was last confirmed. */
    public float getConfirmedFloorElevation() { return confirmedFloorElevation; }
    
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
                // Dark stroke (not red) keeps walls visible without dominating the map
                fillColor = Color.argb(0, 250, 0, 0);
                strokeWidth = 3.5f;
            } else if (t.contains("lift") || t.contains("elevator")) {
                strokeColor = Color.BLUE;
                fillColor = Color.argb(0, 0, 0, 0);
                strokeWidth = 8f;
            } else if (t.contains("stair")) {
                strokeColor = Color.YELLOW;
                fillColor = Color.argb(35, 60, 130, 255);
                strokeWidth = 8f;
            } else if (t.contains("door")) {
                fillColor = Color.argb(0, 0, 0, 0);
                strokeWidth = 4.5f;
            } else if (t.contains("corridor") || t.contains("hall")) {
                fillColor = Color.argb(35, 60, 130, 255);
                strokeWidth = 1.5f;
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

    public IndoorVenue getCurrentVenue() { return currentVenue; }

    public IndoorVenue.FloorFeatures getCurrentFloorFeatures() {
        if (currentVenue == null || currentFloorKey == null) return null;
        return currentVenue.floorFeatures.get(currentFloorKey);
    }


}
