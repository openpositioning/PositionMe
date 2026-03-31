package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.google.android.gms.maps.model.LatLng;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NetworkUtils {

    private static final String TAG = "NetworkUtils";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final String API_KEY = "Fbduaqg2iffH51lef1Eh7A";

    public interface Callback {
        void onSuccess(BuildingData buildingData);
        void onError(String error);
    }

    public static class BuildingData {
        public List<List<LatLng>> outlines = new ArrayList<>();
        public Map<String, FloorData> floors = new HashMap<>();
    }

    public static class FloorData {
        public List<List<LatLng>> walls = new ArrayList<>(); // Pure walls (solid black lines)
        public List<List<LatLng>> areas = new ArrayList<>(); // Rooms and furniture areas (filled colour)
        public List<Poi> pois = new ArrayList<>();
    }

    public static class Poi {
        public LatLng position;
        public String label;
        public String type;

        public Poi(LatLng position, String label, String type) {
            this.position = position;
            this.label = label;
            this.type = type;
        }
    }

    public static void fetchFloorPlan(double latitude, double longitude, Callback callback) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String urlString = "https://openpositioning.org/api/live/floorplan/request/"
                        + API_KEY + "?key=ewireless";
                URL url = new URL(urlString);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");

                String jsonInputString = String.format(Locale.US, "{\"lat\": %.6f, \"lon\": %.6f, \"macs\": []}", latitude, longitude);

                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(jsonInputString.getBytes("utf-8"));
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) response.append(line.trim());

                    BuildingData data = parseBuildingData(response.toString());
                    mainHandler.post(() -> callback.onSuccess(data));
                } else {
                    mainHandler.post(() -> callback.onError("HTTP Error: " + responseCode));
                }
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> callback.onError("Exception: " + e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static BuildingData parseBuildingData(String responseJson) {
        BuildingData data = new BuildingData();
        try {
            JSONArray rootArray = new JSONArray(responseJson);
            for (int b = 0; b < rootArray.length(); b++) {
                JSONObject building = rootArray.getJSONObject(b);

                if (building.has("outline")) {
                    parseOutline(building.getString("outline"), data.outlines);
                }

                if (building.has("map_shapes")) {
                    JSONObject mapShapes = new JSONObject(building.getString("map_shapes"));
                    Iterator<String> keys = mapShapes.keys();

                    while (keys.hasNext()) {
                        String floorName = keys.next();
                        FloorData floorData = new FloorData();
                        JSONObject floorGeoJson = mapShapes.getJSONObject(floorName);
                        parseFloorFeatures(floorGeoJson, floorData);
                        data.floors.put(floorName, floorData);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Parsing error", e);
        }
        return data;
    }

    private static void parseOutline(String jsonStr, List<List<LatLng>> outlines) throws Exception {
        JSONObject json = new JSONObject(jsonStr);
        if (json.has("features")) {
            JSONArray features = json.getJSONArray("features");
            for (int i = 0; i < features.length(); i++) {
                extractCoordinates(features.getJSONObject(i), outlines);
            }
        }
    }

    private static void parseFloorFeatures(JSONObject floorJson, FloorData floorData) throws Exception {
        if (!floorJson.has("features")) return;
        JSONArray features = floorJson.getJSONArray("features");

        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.getJSONObject(i);
            JSONObject geometry = feature.getJSONObject("geometry");
            JSONObject properties = feature.optJSONObject("properties");

            String type = "";
            String name = "";
            if (properties != null) {
                type = properties.optString("indoor_type", "").toLowerCase();
                name = properties.optString("name", "");
            }

            // Prefer semantic tags over raw geometry when both are available.
            if (isAreaType(type)) {
                extractCoordinates(feature, floorData.areas);
            }
            else if (type.contains("wall")) {
                extractCoordinates(feature, floorData.walls);
            }
            else {
                String geomType = geometry.optString("type");
                if (geomType.contains("Polygon")) {
                    extractCoordinates(feature, floorData.areas);
                } else {
                    extractCoordinates(feature, floorData.walls);
                }
            }

            // Capture POIs for labels and special markers.
            if (!name.isEmpty() || isPoiType(type)) {
                LatLng center = getCenter(geometry);
                if (center != null) {
                    floorData.pois.add(new Poi(center, name, type));
                }
            }
        }
    }

    // Features treated as fillable areas on the map.
    private static boolean isAreaType(String type) {
        return type.contains("room") ||
                type.contains("corridor") ||
                type.contains("hall") ||
                type.contains("table") ||
                type.contains("desk") ||
                type.contains("chair") ||
                type.contains("stair") ||
                type.contains("lift") ||
                type.contains("elevator") ||
                type.contains("office") ||
                type.contains("area");
    }

    // What items should display icons?
    private static boolean isPoiType(String type) {
        return type.contains("lift") || type.contains("elevator") || type.contains("toilet")
                || type.contains("stair") || type.contains("room") || type.contains("printer");
    }

    private static void extractCoordinates(JSONObject feature, List<List<LatLng>> targetList) throws Exception {
        JSONObject geometry = feature.getJSONObject("geometry");
        String type = geometry.optString("type");
        JSONArray coords = geometry.getJSONArray("coordinates");

        if ("LineString".equalsIgnoreCase(type)) {
            targetList.add(jsonArrayToLatLng(coords));
        } else if ("MultiLineString".equalsIgnoreCase(type)) {
            for (int j = 0; j < coords.length(); j++) {
                targetList.add(jsonArrayToLatLng(coords.getJSONArray(j)));
            }
        } else if ("Polygon".equalsIgnoreCase(type)) {
            targetList.add(jsonArrayToLatLng(coords.getJSONArray(0)));
        } else if ("MultiPolygon".equalsIgnoreCase(type)) {
            for(int k=0; k<coords.length(); k++) {
                JSONArray poly = coords.getJSONArray(k);
                targetList.add(jsonArrayToLatLng(poly.getJSONArray(0)));
            }
        }
    }

    private static List<LatLng> jsonArrayToLatLng(JSONArray array) throws Exception {
        List<LatLng> points = new ArrayList<>();
        if (array.length() > 0 && !(array.get(0) instanceof JSONArray)) {
            return points;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONArray p = array.getJSONArray(i);
            points.add(new LatLng(p.getDouble(1), p.getDouble(0)));
        }
        return points;
    }

    private static LatLng getCenter(JSONObject geometry) {
        try {
            JSONArray coords = geometry.getJSONArray("coordinates");
            while (coords.length() > 0 && coords.get(0) instanceof JSONArray) {
                coords = coords.getJSONArray(0);
            }
            if (coords.length() >= 2) {
                return new LatLng(coords.getDouble(1), coords.getDouble(0));
            }
        } catch (Exception e) { return null; }
        return null;
    }
}
