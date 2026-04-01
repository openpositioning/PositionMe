package com.openpositioning.PositionMe.utils;

import android.graphics.Color;
import android.util.Log;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
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


    // Distance (m) to consider user near stairs/floor features
    final double floorDistThresh = 5.0;

    // Distance (m) to consider user near a lift (tighter than stairs)
    final double liftDistThresh = 3.0;

    // Last confirmed floor key used as baseline for floor change detection
    private String confirmedFloorKey = null;

    // Elevation (m) at which the current floor was confirmed (barometer reference)
    private float confirmedFloorElevation = Float.NaN;

    // Handler to delay committing floor changes during UI floor browsing
    private final Handler floorCommitHandler = new Handler(Looper.getMainLooper());

    // Delay (ms) before confirming a browsed floor as the active floor
    private static final long FLOOR_COMMIT_DELAY_MS = 1500;

    // Timestamp of last accepted floor change (used to debounce rapid switches)
    private long lastFloorChangeTimeMs = 0;

    // Minimum time (ms) between floor changes to prevent oscillation
    private static final long MIN_FLOOR_CHANGE_INTERVAL_MS = 5000;

    // Required vertical change (m) to consider a real floor transition (barometer threshold)
    private static final double HEIGHT_THRESHOLD_METERS = 4.5;

    // Max distance (m) to consider user near stairs for a valid floor change
    private static final double STAIRS_THRESHOLD_METERS = 12.0;

    // Max distance (m) to consider user near a lift for a valid floor change
    private static final double LIFT_THRESHOLD_METERS = 10.0;

    // Max horizontal movement (m) allowed for lift detection (lifts have minimal horizontal shift)
    private static final double LIFT_HORIZONTAL_THRESHOLD_METERS = 1.0;

    // Tolerance for floating-point precision when checking line segment intersections
    private static final float INTERSECTION_EPSILON = 1e-6f;

    // Tolerance for determining if a polygon ring is already closed (first ≈ last point)
    private static final float RING_CLOSURE_EPSILON = 1e-4f;

    // Distance (m) to back off from a wall after detecting a collision (prevents sticking)
    private static final float WALL_SNAP_BACK_METERS = 0.05f;

    // FLOOR TRANSITION TRACKING

    //location of where we starting changing floors
    private LatLng floorTransitionStartLocation = null;

    private LatLng lastStableFloorLocation = null;
    //are we potentially changing floors
    private boolean floorTransitionInProgress = false;

    private static final float FLOOR_STABLE_BAND_METERS = 0.5f;
    //threshold to start tracking as potential new floor transition
    private static final float FLOOR_TRANSITION_START_THRESHOLD_METERS = 1.0f;

    /**
     * Confirms the currently displayed floor as the user's reference floor.
     *
     * Stores both:
     * - the current floor key, and
     * - the current sensor-derived elevation at the moment of confirmation.
     *
     * This confirmed floor/elevation pair is later used as the baseline for
     * detecting real floor changes.
     */
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

        // Stores all geometric features for a single floor, used for rendering and map-matching
        public static class FloorFeatures {
            // Wall polygons in LatLng (used for drawing and geographic checks)
            public List<List<LatLng>> wallPolygons = new ArrayList<>();

            // Wall polygons converted to ENU (meters) for efficient collision detection
            public List<List<float[]>> wallPolygonsEnu = new ArrayList<>();

            // Centroid positions of stair features (used for floor transition logic)
            public List<LatLng> stairsCenters = new ArrayList<>();

            // Centroid positions of lift/elevator features (used for floor transition logic)
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
     * Prevents movement from passing through walls on the current floor.
     *
     * This checks whether the line segment from the previous ENU position to the
     * proposed ENU position intersects any wall polygon edge. If no wall is crossed,
     * the destination is returned unchanged.
     *
     * If a wall is crossed, the movement is shortened so that the returned point
     * lies just before the first wall intersection. This is used as a hard wall
     * constraint for fused position estimates.
     *
     * @param fromEnu Previous position in ENU metres: {east, north}
     * @param toEnu Proposed new position in ENU metres: {east, north}
     * @return Safe ENU position that does not cross a wall
     */
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

    // Represents where a movement path first intersects a wall edge in ENU space
    private static class WallCrossing {
        // Exact ENU point where the path intersects the wall
        final float[] crossingPoint;

        // Index of the wall polygon that was intersected
        final int polygonIndex;

        // Index of the specific edge within the polygon that was hit
        final int edgeIndex;

        // Parametric position along the path (0=start, 1=end) where intersection occurs
        final float t;

        WallCrossing(float[] crossingPoint, int polygonIndex, int edgeIndex, float t) {
            this.crossingPoint = crossingPoint;
            this.polygonIndex = polygonIndex;
            this.edgeIndex = edgeIndex;
            this.t = t;
        }
    }


    /**
     * Finds the earliest wall edge intersected by a movement segment.
     *
     * Iterates through all wall polygons on the current floor and checks every edge
     * for intersection with the segment from {@code from} to {@code to}. If multiple
     * walls are crossed, the closest one along the path is returned.
     *
     * The returned WallCrossing includes:
     * - the intersection point,
     * - which polygon and edge were hit,
     * - the interpolation parameter t along the path.
     *
     * @param from Start ENU point {east, north}
     * @param to End ENU point {east, north}
     * @param walls List of wall polygons in ENU coordinates
     * @return First wall crossing along the path, or null if no crossing occurs
     */
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

    /**
     * Returns the number of usable edges in a polygon ring.
     *
     * Some polygon rings are explicitly closed, meaning the first and last point are
     * the same. In that case, the final repeated point should not create an extra edge.
     * This helper detects that case and returns the correct number of edges.
     *
     * @param ring Polygon ring as a list of ENU points
     * @return Number of distinct edges in the ring
     */
    private int getRingEdgeCount(List<float[]> ring) {
        if (ring == null || ring.size() < 2) return 0;

        float[] first = ring.get(0);
        float[] last = ring.get(ring.size() - 1);

        boolean alreadyClosed =
                Math.abs(first[0] - last[0]) < RING_CLOSURE_EPSILON &&
                        Math.abs(first[1] - last[1]) < RING_CLOSURE_EPSILON;

        return alreadyClosed ? ring.size() - 1 : ring.size();
    }

    /**
     * Computes the intersection parameter t for two line segments.
     *
     * The first segment is p1 -> p2, and the second is p3 -> p4.
     * If the segments intersect, this returns the parameter t such that:
     *
     *   intersection = p1 + t * (p2 - p1)
     *
     * where 0 <= t <= 1 means the intersection lies on the first segment.
     *
     * If the lines are parallel or the segments do not intersect, returns -1.
     *
     * @param p1 Start of first segment
     * @param p2 End of first segment
     * @param p3 Start of second segment
     * @param p4 End of second segment
     * @return Interpolation parameter t on first segment, or -1 if no intersection
     */
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

    /**
     * Moves a point to just before a detected wall collision.
     *
     * Given a path from {@code from} to {@code to} and the interpolation value
     * {@code hitT} where the wall was hit, this backs the point away slightly
     * from the wall by {@code snapBackMeters}. This avoids leaving the estimate
     * exactly on the wall boundary.
     *
     * @param from Start ENU point
     * @param to End ENU point
     * @param hitT Path interpolation value where wall is hit
     * @param snapBackMeters Distance to back off from the wall
     * @return Adjusted ENU position just before the wall
     */
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

    /**
     * Checks whether a movement segment intersects any wall edge in ENU space.
     *
     * This is a broad collision test used before more detailed correction logic.
     * It loops over every wall polygon and every edge within each polygon.
     *
     * @param from Start ENU point
     * @param to End ENU point
     * @param walls List of wall polygons in ENU coordinates
     * @return true if the segment crosses at least one wall, false otherwise
     */
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

    /**
     * Finds the furthest valid point along a path before hitting a wall.
     *
     * Uses binary search between {@code from} and {@code to} to find the last point
     * that does not cross any wall. This is useful when a movement intersects a wall
     * and we want to clamp the position as close as possible to the obstacle without
     * crossing it.
     *
     * @param from Start ENU point
     * @param to End ENU point
     * @param allWalls List of all wall polygons in ENU coordinates
     * @return Last valid ENU point before wall intersection
     */
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


    /**
     * Tests whether two 2D line segments intersect in ENU coordinates.
     *
     * Handles both proper crossings and edge cases such as collinear points
     * or touching at endpoints. This is the core geometric primitive used by
     * the wall constraint logic.
     *
     * @param p1 Start of first segment
     * @param p2 End of first segment
     * @param p3 Start of second segment
     * @param p4 End of second segment
     * @return true if the segments intersect, false otherwise
     */
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

    /**
     * Returns the signed orientation / cross product of three ENU points.
     *
     * Positive value  -> c is to one side of line ab
     * Negative value  -> c is to the other side
     * Near zero       -> points are approximately collinear
     *
     * Used by segment intersection tests.
     *
     * @param a First point
     * @param b Second point
     * @param c Third point
     * @return Signed 2D cross product value
     */
    private double orientation(float[] a, float[] b, float[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    /**
     * Checks whether a point lies on a line segment in ENU space.
     *
     * Assumes the point is already known to be approximately collinear with the
     * segment, and then checks whether it falls within the segment bounds.
     *
     * @param a Segment start
     * @param p Candidate point
     * @param b Segment end
     * @param eps Tolerance for floating-point comparisons
     * @return true if p lies on segment ab, false otherwise
     */
    private boolean onSegmentEnu(float[] a, float[] p, float[] b, double eps) {
        return p[0] >= Math.min(a[0], b[0]) - eps &&
                p[0] <= Math.max(a[0], b[0]) + eps &&
                p[1] >= Math.min(a[1], b[1]) - eps &&
                p[1] <= Math.max(a[1], b[1]) + eps;
    }

    /**
     * Precomputes ENU versions of wall polygons for the current floor.
     *
     * Converts all wall polygon LatLng coordinates into local East-North-Up metre
     * coordinates using the supplied CoordinateConverter. This allows wall collision
     * checks to be done in a consistent local metric coordinate system.
     *
     * Should be called whenever:
     * - a new floor is shown, or
     * - the coordinate converter becomes available / changes.
     *
     * @param converter Converter for LatLng <-> ENU transformations
     */
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

        return (val > 0) ? 1 : 2;
    }


    /**
     * When the EKF has teleported to the other side of a wall (e.g. after a
     * strong GNSS/WiFi correction), this re-routes prevEkfEnu through the
     * nearest navigable gap so that future wall-slide checks work correctly.
     *
     * Returns an updated "previous position" that is on the same side of all
     * walls as toEnu, routed through doorways/gaps.
     *
     * @param fromEnu current prevEkfEnu
     * @param toEnu   current EKF best estimate (possibly on other side of wall)
     * @return        re-routed fromEnu that is navigably connected to toEnu
     */
    public float[] reroutePrevEnu(float[] fromEnu, float[] toEnu) {
        if (currentVenue == null || currentFloorKey == null) return fromEnu;
        IndoorVenue.FloorFeatures floor = currentVenue.floorFeatures.get(currentFloorKey);
        if (floor == null || floor.wallPolygonsEnu.isEmpty()) return fromEnu;

        // If no wall crossing, no rerouting needed
        if (!crossesAnyWallEnu(fromEnu, toEnu, floor.wallPolygonsEnu)) return fromEnu;

        // Simple gap search: scan along the wall that was crossed looking for
        // the largest gap (doorway). We sample points along the crossing wall
        // and find the one that has the shortest clear path to toEnu.
        float[] bestWaypoint = null;
        double bestScore = Double.MAX_VALUE;

        for (List<float[]> polygon : floor.wallPolygonsEnu) {
            for (int i = 0; i < polygon.size(); i++) {
                float[] a = polygon.get(i);
                float[] b = polygon.get((i + 1) % polygon.size());

                if (!segmentsIntersectEnu(fromEnu, toEnu, a, b)) continue;

                // Sample along this wall segment and adjacent segments
                // looking for a point that has a clear line to toEnu
                int samples = 20;
                for (int s = 0; s <= samples; s++) {
                    float t = (float) s / samples;
                    float candidateX = a[0] + t * (b[0] - a[0]);
                    float candidateY = a[1] + t * (b[1] - a[1]);
                    float[] candidate = {candidateX, candidateY};

                    // Check if this candidate has a clear line to toEnu
                    if (!crossesAnyWallEnu(candidate, toEnu, floor.wallPolygonsEnu)) {
                        double dist = Math.sqrt(
                                Math.pow(candidateX - fromEnu[0], 2) +
                                        Math.pow(candidateY - fromEnu[1], 2));
                        if (dist < bestScore) {
                            bestScore = dist;
                            bestWaypoint = candidate;
                        }
                    }
                }

                // Also check points slightly offset from the wall endpoints
                // as these are where doorways typically are
                float[][] endpoints = {a, b};
                float[][] offsets = {{0.5f, 0f}, {-0.5f, 0f}, {0f, 0.5f}, {0f, -0.5f}};
                for (float[] ep : endpoints) {
                    for (float[] off : offsets) {
                        float[] candidate = {ep[0] + off[0], ep[1] + off[1]};
                        if (!crossesAnyWallEnu(candidate, toEnu, floor.wallPolygonsEnu)) {
                            double dist = Math.sqrt(
                                    Math.pow(candidate[0] - fromEnu[0], 2) +
                                            Math.pow(candidate[1] - fromEnu[1], 2));
                            if (dist < bestScore) {
                                bestScore = dist;
                                bestWaypoint = candidate;
                            }
                        }
                    }
                }
            }
        }

        // If we found a waypoint through a gap, route prevEkfEnu through it
        if (bestWaypoint != null) {
            Log.d("IndoorMapManager", "Rerouted prevEnu through gap at ("
                    + bestWaypoint[0] + ", " + bestWaypoint[1] + ")");
            return bestWaypoint;
        }

        // No gap found — just move prevEkfEnu to toEnu directly
        // so we don't stay permanently stuck
        Log.d("IndoorMapManager", "No gap found, snapping prevEnu to toEnu");
        return toEnu.clone();
    }

    private Circle activeAccessHighlight = null;

    /**
     * Draws a temporary visual highlight around a stairs/lift access point.
     *
     * Used to show the user which access point was selected during a floor change.
     * Removes any previously drawn highlight before adding the new one.
     *
     * @param center Access point centre to highlight
     */
    public void highlightAccessPoint(LatLng center) {
        if (center == null || gMap == null) return;

        // remove previous highlight
        if (activeAccessHighlight != null) {
            activeAccessHighlight.remove();
        }

        activeAccessHighlight = gMap.addCircle(new CircleOptions()
                .center(center)
                .radius(2.0) // meters (tweak)
                .strokeWidth(3f)
                .strokeColor(Color.GREEN)
                .fillColor(0x2200FF00) // translucent green
        );
    }



    /**
     * If the movement from fromEnu to toEnu crosses a wall, slides the destination
     * along the wall surface rather than stopping at it. This prevents the position
     * from getting stuck while still respecting the wall boundary.
     *
     * If no wall is crossed, returns toEnu unchanged.
     *
     * @param fromEnu float[]{east, north} — previous position in metres
     * @param toEnu   float[]{east, north} — new position after predict()
     * @return        wall-slid float[]{east, north}, or toEnu if no wall was crossed
     */
    public float[] slideAlongWallEnu(float[] fromEnu, float[] toEnu) {
        if (currentVenue == null || currentFloorKey == null) return toEnu;
        IndoorVenue.FloorFeatures floor = currentVenue.floorFeatures.get(currentFloorKey);
        if (floor == null || floor.wallPolygonsEnu.isEmpty()) return toEnu;

        if (!crossesAnyWallEnu(fromEnu, toEnu, floor.wallPolygonsEnu)) return toEnu;

        // Find the wall segment that was hit
        float[] wallA = null, wallB = null;
        outer:
        for (List<float[]> polygon : floor.wallPolygonsEnu) {
            for (int i = 0; i < polygon.size(); i++) {
                float[] a = polygon.get(i);
                float[] b = polygon.get((i + 1) % polygon.size());
                if (segmentsIntersectEnu(fromEnu, toEnu, a, b)) {
                    wallA = a;
                    wallB = b;
                    break outer;
                }
            }
        }

        if (wallA == null) return toEnu; // shouldn't happen

        // Snap to just before the wall first
        float[] hitPoint = snapToWallEnu(fromEnu, toEnu, floor.wallPolygonsEnu);

        // Compute the movement vector and the wall direction vector
        float moveX = toEnu[0] - fromEnu[0];
        float moveY = toEnu[1] - fromEnu[1];

        float wallDX = wallB[0] - wallA[0];
        float wallDY = wallB[1] - wallA[1];
        float wallLen = (float) Math.sqrt(wallDX * wallDX + wallDY * wallDY);
        if (wallLen < 1e-6f) return hitPoint;

        // Normalise wall direction
        float wallNX = wallDX / wallLen;
        float wallNY = wallDY / wallLen;

        // Project movement onto wall direction (slide component)
        float dot = moveX * wallNX + moveY * wallNY;
        float slideX = dot * wallNX;
        float slideY = dot * wallNY;

        // Apply slide from hit point
        float[] slid = new float[]{hitPoint[0] + slideX, hitPoint[1] + slideY};

        // If the slid position also crosses a wall, just return the hit point
        if (crossesAnyWallEnu(hitPoint, slid, floor.wallPolygonsEnu)) {
            return hitPoint;
        }

        return slid;
    }


    /**
     * Applies wall-based correction to a predicted indoor location.
     *
     * Converts the old and predicted geographic coordinates into ENU space, checks
     * whether the motion crosses a wall, and if so clamps the destination to just
     * before the first wall hit. The corrected ENU point is then converted back to
     * LatLng.
     *
     * This is the LatLng-facing version of the wall constraint logic.
     *
     * @param oldLocation Previous corrected location
     * @param predictedLocation Newly predicted location
     * @param heightChange Current vertical change estimate (currently not directly used here)
     * @return Corrected location that does not pass through walls
     */
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


    /**
     * Decides whether a proposed floor change should be accepted.
     *
     * A floor change is accepted only if:
     * - there is a confirmed current floor reference,
     * - enough time has passed since the last floor change,
     * - the barometric height change exceeds a threshold,
     * - the user is plausibly near stairs or a lift,
     * - the horizontal movement pattern matches either stairs or lift usage.
     *
     * If accepted, the method:
     * - determines the adjacent destination floor,
     * - snaps the destination to the nearest stairs/lift centre on that floor,
     * - commits the new floor state,
     * - redraws the correct floor overlay.
     *
     * @param correctedLocation Current corrected location on map
     * @param oldLocation Previous location
     * @param currentHeight Current barometric height estimate
     * @return FloorChangeResult describing whether the floor changed and where to snap the user
     */
    public FloorChangeResult acceptFloorChange(LatLng correctedLocation,
                                               LatLng oldLocation,
                                               float currentHeight) {

        if (currentVenue == null ||
                currentFloorKey == null ||
                correctedLocation == null ||
                oldLocation == null) {
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }

        if (confirmedFloorKey == null || Float.isNaN(confirmedFloorElevation)) {
            Log.d("MapMatch", "No confirmed floor reference yet");
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }

        long now = System.currentTimeMillis();
        if (now - lastFloorChangeTimeMs < MIN_FLOOR_CHANGE_INTERVAL_MS) {
            Log.d("MapMatch", "Floor change blocked by debounce");
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }
        updateFloorTransitionState(correctedLocation, currentHeight);

        double horizontalDisplacement =
                getFloorTransitionHorizontalDisplacement(correctedLocation);

        float heightChangeMeters = currentHeight - confirmedFloorElevation;
        if (Math.abs(heightChangeMeters) < HEIGHT_THRESHOLD_METERS) {
            Log.d("MapMatch", "Height change too small: " + heightChangeMeters);
//            resetFloorTransitionState();
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }

        IndoorVenue.FloorFeatures currentFloorFeatures = currentVenue.floorFeatures.get(confirmedFloorKey);
        if (currentFloorFeatures == null) {
            Log.d("MapMatch", "No floor features for confirmed floor: " + confirmedFloorKey);
//            resetFloorTransitionState();
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }

        int direction = heightChangeMeters > 0 ? 1 : -1;
        String nextFloorKey = getAdjacentFloorKey(confirmedFloorKey, direction);

        Log.d("MapMatch", "confirmed floor: " + confirmedFloorKey);
        Log.d("MapMatch", "current displayed floor: " + currentFloorKey);
        Log.d("MapMatch", "candidate next floor: " + nextFloorKey);
        Log.d("MapMatch", "heightChange=" + heightChangeMeters);

        if (nextFloorKey == null || !currentVenue.floorFeatures.containsKey(nextFloorKey)) {
            Log.d("MapMatch", "Next floor invalid");
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }

        IndoorVenue.FloorFeatures nextFloorFeatures = currentVenue.floorFeatures.get(nextFloorKey);
        if (nextFloorFeatures == null) {
            Log.d("MapMatch", "No floor features for destination floor: " + nextFloorKey);
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }

        boolean nearStairs = isNearAnyPoint(
                correctedLocation,
                currentFloorFeatures.stairsCenters,
                STAIRS_THRESHOLD_METERS
        );

        boolean nearLift = isNearAnyPoint(
                correctedLocation,
                currentFloorFeatures.liftCenters,
                LIFT_THRESHOLD_METERS
        );


        boolean usedLift = nearLift && horizontalDisplacement < LIFT_HORIZONTAL_THRESHOLD_METERS;
        boolean usedStairs = false;

        usedStairs = nearStairs && horizontalDisplacement >= LIFT_HORIZONTAL_THRESHOLD_METERS;
        Log.d("MapMatch", "nearStairs=" + nearStairs + ", nearLift=" + nearLift);
        Log.d("MapMatch", "horizontalDisplacement=" + horizontalDisplacement);
        Log.d("MapMatch", "usedLift=" + usedLift + ", usedStairs=" + usedStairs);

        if (!usedLift && !usedStairs) {
            Log.d("MapMatch", "Rejected floor change: not near stairs/lift in a plausible way");
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }
        LatLng highlightCenter = null;



        if (nextFloorKey.equals(confirmedFloorKey)) {
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }

        LatLng snappedDestination = correctedLocation;


        if (usedLift) {
            LatLng nearestLiftOnNextFloor = getNearestPoint(correctedLocation, nextFloorFeatures.liftCenters);
            if (nearestLiftOnNextFloor != null) {
                snappedDestination = nearestLiftOnNextFloor;
                highlightCenter = nearestLiftOnNextFloor;
            }
        } else if (usedStairs) {
            LatLng nearestStairsOnNextFloor = getNearestPoint(correctedLocation, nextFloorFeatures.stairsCenters);
            highlightCenter = nearestStairsOnNextFloor;

            if (nearestStairsOnNextFloor != null) {
                snappedDestination = nearestStairsOnNextFloor;
            }
        }

        commitAutoFloorChange(nextFloorKey, currentHeight);
        lastFloorChangeTimeMs = now;
        resetFloorTransitionState();

        Log.d("MapMatch", "Accepted floor change to " + nextFloorKey +
                " with snapped destination " + snappedDestination);
        Log.d("MapMatch", "nextFloor stair count = " +
                (nextFloorFeatures.stairsCenters == null ? 0 : nextFloorFeatures.stairsCenters.size()));
        Log.d("MapMatch", "nextFloor lift count = " +
                (nextFloorFeatures.liftCenters == null ? 0 : nextFloorFeatures.liftCenters.size()));
        showFloor(nextFloorKey);
        return new FloorChangeResult(nextFloorKey, snappedDestination, true, highlightCenter);
    }





    /**
     * Updates transition tracking state.
     * Call this every update BEFORE computing horizontal displacement.
     */
    private void updateFloorTransitionState(LatLng currentLocation, float currentHeight) {
        if (currentLocation == null) return;
        if (confirmedFloorKey == null || Float.isNaN(confirmedFloorElevation)) return;

        float heightDelta = currentHeight - confirmedFloorElevation;
        float absDelta = Math.abs(heightDelta);

        // Case 1: Still clearly on the confirmed floor → keep refreshing anchor
        if (absDelta < FLOOR_STABLE_BAND_METERS) {
            lastStableFloorLocation = currentLocation;

            // If we had started a transition but came back, cancel it
            floorTransitionInProgress = false;
            floorTransitionStartLocation = null;
            return;
        }

        // Case 2: Height has meaningfully deviated → start transition (once)
        if (!floorTransitionInProgress &&
                absDelta >= FLOOR_TRANSITION_START_THRESHOLD_METERS) {

            floorTransitionInProgress = true;

            // Prefer last stable location (more accurate than noisy trigger point)
            floorTransitionStartLocation = (lastStableFloorLocation != null)
                    ? lastStableFloorLocation
                    : currentLocation;

            Log.d("MapMatch", "Started floor transition at " + floorTransitionStartLocation +
                    ", current location = " + currentLocation +
                    ", height delta = " + heightDelta);
        }
    }

    /**
     * Returns horizontal displacement since transition began.
     */
    private double getFloorTransitionHorizontalDisplacement(LatLng currentLocation) {
        if (!floorTransitionInProgress ||
                floorTransitionStartLocation == null ||
                currentLocation == null) {
            return 0.0;
        }

        return distanceMeters(floorTransitionStartLocation, currentLocation);
    }

    /**
     * Call this after a successful floor change.
     */
    private void resetFloorTransitionState() {
        floorTransitionInProgress = false;
        floorTransitionStartLocation = null;
        lastStableFloorLocation = null;
    }
    private LatLng getNearestPoint(LatLng location, List<LatLng> centers) {
        if (location == null || centers == null || centers.isEmpty()) {
            return null;
        }

        LatLng nearest = null;
        double bestDist = Double.MAX_VALUE;

        for (LatLng center : centers) {
            double d = distanceMeters(location, center);
            if (d < bestDist) {
                bestDist = d;
                nearest = center;
            }
        }

        return nearest;
    }

    public static class FloorChangeResult {
        public final String floorKey;
        public final LatLng snappedLocation;
        public final boolean changedFloor;

        public LatLng highlightcenter;

        public FloorChangeResult(String floorKey, LatLng snappedLocation, boolean changedFloor, LatLng highlightcenter) {
            this.floorKey = floorKey;
            this.snappedLocation = snappedLocation;
            this.changedFloor = changedFloor;
            this.highlightcenter = highlightcenter;
        }
    }

    private void commitCurrentDisplayedFloor() {
        if (currentFloorKey == null) return;

        confirmedFloorKey = currentFloorKey;
        confirmedFloorElevation = SensorFusion.getInstance().getElevation();

        Log.d("IndoorMapManager", "Confirmed floor: " + confirmedFloorKey +
                " at elevation " + confirmedFloorElevation);
    }

    /**
     * Returns the floor key directly above or below a given floor.
     *
     * Floors are first sorted into building order (e.g. B2, B1, G, 1, 2, ...),
     * then the next key is selected using the supplied direction.
     *
     * @param floorKey Current floor key
     * @param direction +1 for up one floor, -1 for down one floor
     * @return Adjacent floor key, or null if none exists
     */
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

    /**
     * Computes straight-line distance between two LatLng points in metres.
     *
     * Uses Android's Location.distanceBetween utility.
     *
     * @param a First point
     * @param b Second point
     * @return Distance in metres
     */
    private double distanceMeters(LatLng a, LatLng b) {
        float[] result = new float[1];
        android.location.Location.distanceBetween(
                a.latitude, a.longitude,
                b.latitude, b.longitude,
                result
        );
        return result[0];
    }

    /**
     * Checks whether a location lies within a threshold distance of any point in a list.
     *
     * Used for access-point logic such as determining whether the user is close enough
     * to stairs or a lift for a floor change to be plausible.
     *
     * @param location Location to test
     * @param centers Candidate reference points
     * @param thresholdMeters Distance threshold in metres
     * @return true if location is near at least one point
     */
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

    /**
     * Displays a specific floor immediately.
     *
     * Clears the existing floor overlay, redraws the requested floor, and rebakes
     * ENU wall coordinates for map matching.
     *
     * Unlike delayed browsing logic, this is typically used when the floor should
     * be shown directly, such as after an accepted automatic floor change.
     *
     * @param floorKey Floor key to display
     */
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

    /**
     * Immediately commits an automatically detected floor change.
     *
     * Updates both the displayed floor and the confirmed floor reference, stores the
     * elevation at which the change was accepted, cancels any pending delayed floor
     * commit, and redraws the new floor overlay.
     *
     * @param newFloorKey Newly accepted floor
     * @param elevation Elevation associated with the accepted floor change
     */
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
                fillColor = Color.argb(0, 250, 0, 0);
                strokeWidth = 3.5f;
            } else if (t.contains("lift")) {
                strokeColor = Color.BLUE;
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