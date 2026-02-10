package com.openpositioning.PositionMe.utils;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.FloorplanService.Venue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal GeoJSON helpers for parsing outline and map_shapes.
 */
public class GeoParser {
    private static final String TAG = "GeoParser";

    /**
    * Parses a GeoJSON string and returns the first polygon ring as a list of LatLng points.
    * Used to extract the building outline for rendering on the map.
    */
    public static List<LatLng> parseOutline(String geoJson) {
        try {
            JSONObject root = new JSONObject(geoJson);
            // If FeatureCollection, take first feature
            if ("FeatureCollection".equalsIgnoreCase(root.optString("type"))) {
                JSONArray features = root.optJSONArray("features");
                if (features == null || features.length() == 0) return null;
                root = features.getJSONObject(0).optJSONObject("geometry");
                if (root == null) return null;
            }
            String type = root.optString("type");
            if ("MultiPolygon".equalsIgnoreCase(type)) {
                JSONArray coords = root.optJSONArray("coordinates");
                if (coords == null || coords.length() == 0) return null;
                JSONArray firstPoly = coords.optJSONArray(0); // first polygon
                if (firstPoly == null || firstPoly.length() == 0) return null;
                return toLatLngRing(firstPoly.optJSONArray(0)); // first ring
            }
        } catch (JSONException e) {
            Log.e(TAG, "parseFirstPolygonRing error", e);
        }
        return null;
    }
    

    /**
    * Parses a map_shapes GeoJSON string into a per-floor map of polylines.
    * The input is a JSON object keyed by floor identifier (e.g. "B1", "GF", "F1"),
    * where each value is a FeatureCollection of wall/room geometries for that floor.

    * Returns null if no valid floor data is found.
    */
    public static Map<String, List<List<LatLng>>> parseFloorPolylines(String geoJson) {
        Map<String, List<List<LatLng>>> out = new LinkedHashMap<>();
        try {
            JSONObject root = new JSONObject(geoJson);
            JSONArray names = root.names();
            if (names == null) return null;
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                JSONObject fc = root.optJSONObject(key);
                if (fc == null) continue;
                List<List<LatLng>> lines = new ArrayList<>();
                collectFeatureCollection(lines, fc);
                if (!lines.isEmpty()) out.put(key, lines);
            }
            return out.isEmpty() ? null : out;
        } catch (JSONException e) {
            Log.e(TAG, "parseFloorPolylines error", e);
            return null;
        }
    }


    /**
    * Extract all geometries from a GeoJSON FeatureCollection and append
    * their drawable paths into the output list.
    */

    private static void collectFeatureCollection(List<List<LatLng>> out, JSONObject featureCollection) {

        // Get the array of features from the collection
        JSONArray features = featureCollection.optJSONArray("features");
        if (features == null) return;

        // Iterate over every feature
        for (int i = 0; i < features.length(); i++) {
            // Read the feature object per iteration
            JSONObject feat = features.optJSONObject(i);
            if (feat == null) continue;
             // Each feature should contain a "geometry" object
            JSONObject geom = feat.optJSONObject("geometry");
            if (geom == null) continue;

            // Parse the geometry and append results into `out`
            collectGeom(out, geom);
        }
    }


    /**
    * Reads GeoJSON and extract drawable path data.
    * The paths are converted into lists of LatLng and appended to `out`.
    *
    * Supports:
    * - MultiLineString
    * - MultiPolygon 
    */

    private static void collectGeom(List<List<LatLng>> out, JSONObject geom) {

        // Determine the geometry type (e.g., MultiLineString or  MultiPolygon, etc.)
        String type = geom.optString("type");

        // MultiLineString -> many independent lines
        // coordinates: [ [ [lon,lat], [lon,lat] ], [ ... ] ]
        if ("MultiLineString".equalsIgnoreCase(type)) {

             // Get the array of lines
            JSONArray lines = geom.optJSONArray("coordinates");
            if (lines == null) return;

            // Process each line
            for (int i = 0; i < lines.length(); i++) {
                JSONArray line = lines.optJSONArray(i);

                // Convert JSON coordinates to LatLng objects
                List<LatLng> ring = toLatLngRing(line);
                if (ring != null && ring.size() > 1) out.add(ring);
            }
        } else if ("MultiPolygon".equalsIgnoreCase(type)) {
            JSONArray polys = geom.optJSONArray("coordinates");
            if (polys == null) return;
            for (int i = 0; i < polys.length(); i++) {
                JSONArray poly = polys.optJSONArray(i);
                if (poly == null || poly.length() == 0) continue;
                List<LatLng> ring = toLatLngRing(poly.optJSONArray(0));
                if (ring != null && ring.size() > 1) out.add(ring);
            }
        }
    }

    // Convert a JSONArray of [longitude, latitude] pairs
    // into a List of LatLng objects.
    private static List<LatLng> toLatLngRing(JSONArray arr) {
        // If the array itself is null, return null to avoid errors
        if (arr == null) return null;

        // Create a list that will hold the converted coordinates
        List<LatLng> list = new ArrayList<>();

         // Loop through each element in the JSON array
        for (int i = 0; i < arr.length(); i++) {

            // Try to read the current element as a JSONArray
            // Expected format: [lon, lat]
            JSONArray pair = arr.optJSONArray(i);

            // Skip if it's not an array or doesn't have at least two values
            if (pair == null || pair.length() < 2) continue;

            // GeoJSON stores coordinates as [longitude, latitude]
            double lon = pair.optDouble(0);
            double lat = pair.optDouble(1);

            // LatLng expects (latitude, longitude),
            // so we swap the order here
            list.add(new LatLng(lat, lon));
        }
        // Return the completed list of coordinates
        return list;
    }
}
