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

        return (val > 0) ? 1 : 2;
    }

    /**
     * Finds gaps (doorways) in walls between two ENU positions by looking for
     * wall segments that have a break/gap large enough to walk through (>0.6m).
     * Returns a waypoint through the nearest gap, or null if no gap found.
     */
    private float[] findGapBetweenPoints(float[] fromEnu, float[] toEnu,
                                         List<List<float[]>> walls) {
        float[] bestGap = null;
        double bestDist = Double.MAX_VALUE;

        // Direction vector from->to
        float dx = toEnu[0] - fromEnu[0];
        float dy = toEnu[1] - fromEnu[1];
        float totalDist = (float) Math.sqrt(dx * dx + dy * dy);
        if (totalDist < 1e-6f) return null;

        for (List<float[]> polygon : walls) {
            for (int i = 0; i < polygon.size(); i++) {
                float[] a = polygon.get(i);
                float[] b = polygon.get((i + 1) % polygon.size());

                // Only consider wall segments that intersect our path
                if (!segmentsIntersectEnu(fromEnu, toEnu, a, b)) continue;

                // Look for a gap by checking adjacent segments for a break
                // A gap exists where two consecutive wall endpoints don't connect
                float[] prev = polygon.get((i - 1 + polygon.size()) % polygon.size());
                float[] next = polygon.get((i + 2) % polygon.size());

                // Check gap before segment a
                float gapBeforeSize = (float) Math.sqrt(
                        Math.pow(a[0] - prev[1], 2) + Math.pow(a[1] - prev[1], 2));

                // Check gap after segment b
                float gapAfterSize = (float) Math.sqrt(
                        Math.pow(next[0] - b[0], 2) + Math.pow(next[1] - b[1], 2));

                // Midpoint of segment as candidate gap point
                float midX = (a[0] + b[0]) / 2f;
                float midY = (a[1] + b[1]) / 2f;

                // Find closest point on the wall segment to our path
                double distToMid = Math.sqrt(
                        Math.pow(midX - fromEnu[0], 2) + Math.pow(midY - fromEnu[1], 2));

                if (distToMid < bestDist) {
                    bestDist = distToMid;
                    bestGap = new float[]{midX, midY};
                }
            }
        }
        return bestGap;
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

    private LatLng getWalkableSnapNearAccessPoint(LatLng accessCenter,
                                                  LatLng referenceLocation,
                                                  IndoorVenue.FloorFeatures floorFeatures) {
        if (accessCenter == null) return referenceLocation;
        if (floorFeatures == null) return accessCenter;

        // First try a point offset toward where the user came from
        LatLng firstCandidate = offsetFromCenterTowardReference(accessCenter, referenceLocation, 1.5);
        if (!isInsideAnyWall(firstCandidate, floorFeatures.wallPolygons)) {
            return firstCandidate;
        }

        // Then do a radial search around the center
        double[] radii = {1.0, 1.5, 2.0, 2.5, 3.0};
        int angleStepDeg = 20;

        for (double radius : radii) {
            for (int deg = 0; deg < 360; deg += angleStepDeg) {
                double rad = Math.toRadians(deg);
                double east = radius * Math.cos(rad);
                double north = radius * Math.sin(rad);

                LatLng candidate = offsetLatLngMeters(accessCenter, east, north);
                if (!isInsideAnyWall(candidate, floorFeatures.wallPolygons)) {
                    return candidate;
                }
            }
        }

        // Fallback: keep the old corrected location if everything near the center is blocked
        return referenceLocation;
    }

    private boolean isPointInPolygon(LatLng point, List<LatLng> polygon) {
        if (point == null || polygon == null || polygon.size() < 3) return false;

        boolean inside = false;
        double x = point.longitude;
        double y = point.latitude;

        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            double xi = polygon.get(i).longitude;
            double yi = polygon.get(i).latitude;
            double xj = polygon.get(j).longitude;
            double yj = polygon.get(j).latitude;

            boolean intersect = ((yi > y) != (yj > y)) &&
                    (x < (xj - xi) * (y - yi) / ((yj - yi) + 1e-12) + xi);

            if (intersect) inside = !inside;
        }

        return inside;
    }

    private boolean isInsideAnyWall(LatLng point, List<List<LatLng>> wallPolygons) {
        if (point == null || wallPolygons == null) return false;

        for (List<LatLng> polygon : wallPolygons) {
            if (polygon != null && polygon.size() >= 3 && isPointInPolygon(point, polygon)) {
                return true;
            }
        }
        return false;
    }

    private LatLng offsetLatLngMeters(LatLng origin, double eastMeters, double northMeters) {
        if (origin == null) return null;

        double latRad = Math.toRadians(origin.latitude);

        double dLat = northMeters / 111320.0;
        double dLng = eastMeters / (111320.0 * Math.cos(latRad));

        return new LatLng(
                origin.latitude + dLat,
                origin.longitude + dLng
        );
    }

    private LatLng offsetFromCenterTowardReference(LatLng center, LatLng reference, double offsetMeters) {
        if (center == null || reference == null) return center;

        double dNorth = (reference.latitude - center.latitude) * 111320.0;
        double dEast = (reference.longitude - center.longitude) *
                111320.0 * Math.cos(Math.toRadians(center.latitude));

        double norm = Math.sqrt(dEast * dEast + dNorth * dNorth);
        if (norm < 1e-6) {
            return offsetLatLngMeters(center, offsetMeters, 0.0);
        }

        double unitEast = dEast / norm;
        double unitNorth = dNorth / norm;

        return offsetLatLngMeters(center, unitEast * offsetMeters, unitNorth * offsetMeters);
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




    //find nearest valid point that doesn't intersect walls
    //loop through points on segment just traversed starting from predicted location back towards prev location
    //use dx and dy
    //once we find point that doesn't intersect wall
    //corrected position = point
    //break

    private long lastFloorChangeTimeMs = 0;
    private static final long MIN_FLOOR_CHANGE_INTERVAL_MS = 5000; // 5 seconds minimum
    private static final double HEIGHT_THRESHOLD_METERS = 4.5;
    private static final double STAIRS_THRESHOLD_METERS = 12.0;
    private static final double LIFT_THRESHOLD_METERS = 10.0;
    private static final double LIFT_HORIZONTAL_THRESHOLD_METERS = 1.0;
//    private LatLng floorTransitionStartLocation = null;
//    private boolean floorTransitionInProgress = false;
//    private static final float FLOOR_TRANSITION_START_THRESHOLD_METERS = 1.0f;

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
//            resetFloorTransitionState();
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }

        IndoorVenue.FloorFeatures nextFloorFeatures = currentVenue.floorFeatures.get(nextFloorKey);
        if (nextFloorFeatures == null) {
            Log.d("MapMatch", "No floor features for destination floor: " + nextFloorKey);
//            resetFloorTransitionState();
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

//        double horizontalDisplacement = getFloorTransitionHorizontalDisplacement(correctedLocation);
//        resetFloorTransitionState();

        boolean usedLift = nearLift && horizontalDisplacement < LIFT_HORIZONTAL_THRESHOLD_METERS;
        boolean usedStairs = false;
//        if (!nearLift && nearStairs){
//            usedStairs = true;
//        }

//        else {
            usedStairs = nearStairs && horizontalDisplacement >= LIFT_HORIZONTAL_THRESHOLD_METERS;
//        }
        Log.d("MapMatch", "nearStairs=" + nearStairs + ", nearLift=" + nearLift);
        Log.d("MapMatch", "horizontalDisplacement=" + horizontalDisplacement);
        Log.d("MapMatch", "usedLift=" + usedLift + ", usedStairs=" + usedStairs);

        if (!usedLift && !usedStairs) {
            Log.d("MapMatch", "Rejected floor change: not near stairs/lift in a plausible way");
//            resetFloorTransitionState();
            return new FloorChangeResult(currentFloorKey, correctedLocation, false, null);
        }
        LatLng highlightCenter = null;



        if (nextFloorKey.equals(confirmedFloorKey)) {
//            resetFloorTransitionState();
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
//                snappedDestination = getWalkableSnapNearAccessPoint(
//                        nearestStairsOnNextFloor,
//                        oldLocation,
//                        nextFloorFeatures
//                );
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



    // ================= FLOOR TRANSITION TRACKING =================

    private LatLng floorTransitionStartLocation = null;
    private LatLng lastStableFloorLocation = null;
    private boolean floorTransitionInProgress = false;

    // Tune these based on your barometer noise
    private static final float FLOOR_STABLE_BAND_METERS = 0.5f;              // "definitely still on floor"
    private static final float FLOOR_TRANSITION_START_THRESHOLD_METERS = 1.0f; // "transition has begun"

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
