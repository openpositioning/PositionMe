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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.HashMap;
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
            public List<LatLng> wallPolylines = new ArrayList<>(); // if your walls are lines
            public List<LatLng> stairsCenters = new ArrayList<>(); // or polygons if provided
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
        IndoorVenue.FloorFeatures floor =
                currentVenue.floorFeatures.get(currentFloorKey);
        if (floor == null || floor.wallPolygons.isEmpty()) return;

        for (int i = 0; i < weights.length; i++) {
            if (weights[i] == 0f) continue; // already dead, skip

            LatLng prev = toLatLng(prevEast[i], prevNorth[i], converter);
            LatLng curr = toLatLng(currEast[i], currNorth[i], converter);
            if (crossesAnyWall(prev, curr, floor.wallPolygons)) {
                LatLng snapped = adjustPositionToNearestValidLocation(
                        prev, curr, getNearestWallPolygon(prev, curr, floor.wallPolygons));
                float[] enu = converter.toEnu(snapped.latitude, snapped.longitude);
                currEast[i] = enu[0];
                currNorth[i] = enu[1];
                weights[i] *= 0.1f;
            }
            }

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
                if (segmentsIntersect(from, to, wallA, wallB)) return true;
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

        if (Math.abs(val) < eps) {
            return 0;      // collinear
        }

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

    public String acceptFloorChange(LatLng correctedLocation, LatLng oldLocation, float heightChangeMeters) {
        if (currentVenue == null || currentFloorKey == null || correctedLocation == null || oldLocation == null) {
            return currentFloorKey;
        }

        // Require meaningful vertical movement first
        double heightThresholdMeters = 2.5;
        if (Math.abs(heightChangeMeters) < heightThresholdMeters) {
            return currentFloorKey;
        }

        IndoorVenue.FloorFeatures floorFeatures = currentVenue.floorFeatures.get(currentFloorKey);
        if (floorFeatures == null) {
            return currentFloorKey;
        }

        double stairsThresholdMeters = 5.0;
        double liftThresholdMeters = 4.0;
        double liftHorizontalThresholdMeters = 2.0;

        boolean nearStairs = isNearAnyPoint(correctedLocation, floorFeatures.stairsCenters, stairsThresholdMeters);
        boolean nearLift = isNearAnyPoint(correctedLocation, floorFeatures.liftCenters, liftThresholdMeters);

        if (!nearStairs && !nearLift) {
            return currentFloorKey;
        }

        double horizontalDisplacement = distanceMeters(oldLocation, correctedLocation);

        boolean usedLift = nearLift && horizontalDisplacement < liftHorizontalThresholdMeters;
        boolean usedStairs = nearStairs && horizontalDisplacement >= liftHorizontalThresholdMeters;
        Log.d("MapMatch", "heightChange=" + heightChangeMeters +
                ", floor=" + currentFloorKey);
        Log.d("MapMatch", "nearStairs=" + nearStairs +
                ", nearLift=" + nearLift);
        Log.d("MapMatch", "horizontalDisplacement=" + horizontalDisplacement);

        if (!usedLift && !usedStairs) {
            return currentFloorKey;
        }

        String nextFloorKey = getAdjacentFloorKey(currentFloorKey, heightChangeMeters > 0);
        if (nextFloorKey == null || !currentVenue.floorFeatures.containsKey(nextFloorKey)) {
            return currentFloorKey;
        }
        if (!nextFloorKey.equals(currentFloorKey)) {
            currentFloorKey = nextFloorKey;
            boolean goingUp = heightChangeMeters > 0;
            switchFloor(goingUp ? +1 : -1);
        }

        return currentFloorKey;
    }

    private String getAdjacentFloorKey(String floorKey, boolean goingUp) {
        try {
            int floor = Integer.parseInt(floorKey);
            int next = goingUp ? floor + 1 : floor - 1;
            return String.valueOf(next);
        } catch (NumberFormatException e) {
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

    public LatLng adjustPositionToNearestValidLocation(LatLng oldLocation, LatLng predictedLocation, List<LatLng> polygon) {

        LatLng corrected = oldLocation;

        double dx = predictedLocation.latitude - oldLocation.latitude;
        double dy = predictedLocation.longitude - oldLocation.longitude;

        for (double t = 1.0; t >= 0.0; t -= 0.05) {
            double lat = oldLocation.latitude + t * dx;
            double lon = oldLocation.longitude + t * dy;
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

            if (!intersectsWall) {
                corrected = candidate;
                return corrected;
            }
        }

        return corrected;
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
            }

        } catch (JSONException e) {
            Log.e("IndoorMapManager", "Floor switch failed", e);
        }
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
                fillColor = Color.argb(0, 0, 0, 0);
                strokeWidth = 3.5f;
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
