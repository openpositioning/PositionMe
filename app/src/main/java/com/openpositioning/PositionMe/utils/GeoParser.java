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
     * Parse first ring of a Polygon or MultiPolygon from a GeoJSON string. Expects lon,lat order.
     */
    public static List<LatLng> parseFirstPolygonRing(String geoJson) {
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
            if ("Polygon".equalsIgnoreCase(type)) {
                JSONArray coords = root.optJSONArray("coordinates");
                return coords == null ? null : toLatLngRing(coords.optJSONArray(0));
            } else if ("MultiPolygon".equalsIgnoreCase(type)) {
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
     * Parse MultiLineString / LineString collections out of a FeatureCollection or raw geometry.
     * Returns list of polylines (each as list of LatLng).
     */
    public static List<List<LatLng>> parseMultiLineOrPolygonCollection(String geoJson) {
        List<List<LatLng>> result = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(geoJson);

            // Case: map_shapes is an object with floor keys, each a FeatureCollection
            if (!root.has("type")) {
                JSONArray names = root.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        JSONObject floorObj = root.optJSONObject(names.optString(i));
                        if (floorObj != null) {
                            collectFeatureCollection(result, floorObj);
                        }
                    }
                }
            } else if ("FeatureCollection".equalsIgnoreCase(root.optString("type"))) {
                collectFeatureCollection(result, root);
            } else {
                collectGeom(result, root);
            }
            return result.isEmpty() ? null : result;
        } catch (JSONException e) {
            Log.e(TAG, "parseMultiLineOrPolygonCollection error", e);
            return null;
        }
    }

    /**
     * Parse floors: map_shapes structured as { "B1": FeatureCollection, "GF": FeatureCollection, ... }
     * Returns a map floorName -> list of polylines.
     */
    public static Map<String, List<List<LatLng>>> parseFloorPolylines(String geoJson) {
        Map<String, List<List<LatLng>>> out = new LinkedHashMap<>();
        try {
            JSONObject root = new JSONObject(geoJson);
            // If this is already a FeatureCollection, treat as single unnamed floor
            if ("FeatureCollection".equalsIgnoreCase(root.optString("type"))) {
                JSONArray features = root.optJSONArray("features");
                if (features == null) return null;
                for (int i = 0; i < features.length(); i++) {
                    JSONObject feat = features.optJSONObject(i);
                    if (feat == null) continue;
                    JSONObject geom = feat.optJSONObject("geometry");
                    if (geom == null) continue;
                    String floorKey = extractFloorKey(feat);
                    List<List<LatLng>> lines = out.get(floorKey);
                    if (lines == null) {
                        lines = new ArrayList<>();
                        out.put(floorKey, lines);
                    }
                    collectGeom(lines, geom);
                }
                return out.isEmpty() ? null : out;
            }

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

    /** Prefer floor/level properties if present on features; fallback to F1. */
    private static String extractFloorKey(JSONObject feature) {
        JSONObject props = feature.optJSONObject("properties");
        if (props != null) {
            String[] keys = new String[]{"floor", "level", "level_id", "levelId", "floor_id", "floorId", "z_level"};
            for (String k : keys) {
                if (props.has(k)) {
                    String val = props.optString(k, "");
                    if (val == null || val.isEmpty() || "null".equalsIgnoreCase(val)) continue;
                    return val;
                }
            }
            if (props.has("z")) {
                double z = props.optDouble("z", Double.NaN);
                if (!Double.isNaN(z)) return String.valueOf((int) z);
            }
        }
        return "F1";
    }

    private static void collectFeatureCollection(List<List<LatLng>> out, JSONObject featureCollection) {
        JSONArray features = featureCollection.optJSONArray("features");
        if (features == null) return;
        for (int i = 0; i < features.length(); i++) {
            JSONObject feat = features.optJSONObject(i);
            if (feat == null) continue;
            JSONObject geom = feat.optJSONObject("geometry");
            if (geom == null) continue;
            collectGeom(out, geom);
        }
    }

    private static void collectGeom(List<List<LatLng>> out, JSONObject geom) {
        String type = geom.optString("type");
        if ("MultiLineString".equalsIgnoreCase(type)) {
            JSONArray lines = geom.optJSONArray("coordinates");
            if (lines == null) return;
            for (int i = 0; i < lines.length(); i++) {
                JSONArray line = lines.optJSONArray(i);
                List<LatLng> ring = toLatLngRing(line);
                if (ring != null && ring.size() > 1) out.add(ring);
            }
        } else if ("LineString".equalsIgnoreCase(type)) {
            List<LatLng> ring = toLatLngRing(geom.optJSONArray("coordinates"));
            if (ring != null && ring.size() > 1) out.add(ring);
        } else if ("Polygon".equalsIgnoreCase(type)) {
            // use exterior ring only
            List<LatLng> ring = toLatLngRing(geom.optJSONArray("coordinates").optJSONArray(0));
            if (ring != null && ring.size() > 1) out.add(ring);
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

    private static List<LatLng> toLatLngRing(JSONArray arr) {
        if (arr == null) return null;
        List<LatLng> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONArray pair = arr.optJSONArray(i);
            if (pair == null || pair.length() < 2) continue;
            double lon = pair.optDouble(0);
            double lat = pair.optDouble(1);
            list.add(new LatLng(lat, lon));
        }
        return list;
    }
}
