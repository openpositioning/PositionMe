package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.util.Log;

import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.openpositioning.PositionMe.BuildConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * IndoorMapService - Retrieves indoor building geometry from the OpenPositioning server.
 * POST endpoint: /api/live/floorplan/request/{api_key}?key={master_key}
 * Request payload: {"lat": double, "lon": double, "macs": [string]}
 * Server response: [{"name": string, "outline": string, "map_shapes": string}]
 */
public class IndoorMapService {

    private static final String LOG_TAG = "IndoorMapService";

    private static final String API_USER_KEY = BuildConfig.OPENPOSITIONING_API_KEY;
    private static final String API_MASTER_KEY = BuildConfig.OPENPOSITIONING_MASTER_KEY;
    private static final String ENDPOINT_URL =
            "https://openpositioning.org/api/live/floorplan/request/" + API_USER_KEY + "?key=" + API_MASTER_KEY;

    private RequestQueue networkQueue;
    private MapDataCallback responseHandler;

    public IndoorMapService(Context context) {
        networkQueue = Volley.newRequestQueue(context);
    }

    /**
     * Callback interface for async API responses.
     */
    public interface MapDataCallback {
        void onSuccess(List<BuildingData> buildings);
        void onError(String errorMessage);
    }

    /**
     * Represents a single floor's geometric data within a building.
     */
    public static class FloorGeometry {
        public int levelNumber;
        public String levelName;
        public List<double[]> polygonPoints;
        public String imageSource;
        public String featureType;
        public String areaLabel;

        public FloorGeometry(int level) {
            this.levelNumber = level;
            this.levelName = "Floor " + level;
            this.polygonPoints = new ArrayList<>();
            this.featureType = "";
            this.areaLabel = "";
        }
    }

    /**
     * Building/campaign metadata from the positioning server.
     */
    public static class BuildingData {
        public String buildingName;
        public String rawOutline;
        public String rawGeometryData;
        public List<double[]> boundaryPoints;
        public List<FloorGeometry> floorLayers;
        public List<Integer> levelsAvailable;
        public java.util.Map<Integer, String> levelLabels;

        public BuildingData() {
            boundaryPoints = new ArrayList<>();
            floorLayers = new ArrayList<>();
            levelsAvailable = new ArrayList<>();
            levelLabels = new java.util.HashMap<>();
        }
    }

    /**
     * Query server for nearby indoor building maps.
     *
     * @param lat      User's current latitude
     * @param lon      User's current longitude
     * @param macList  WiFi MAC addresses observed nearby
     * @param handler  Callback for async response
     */
    public void fetchProximityMaps(double lat,
                                    double lon,
                                    List<String> macList,
                                    MapDataCallback handler) {
        this.responseHandler = handler;

        try {
            JSONObject payload = new JSONObject();
            payload.put("lat", lat);
            payload.put("lon", lon);

            JSONArray macArray = new JSONArray();
            for (String mac : macList) {
                macArray.put(mac);
            }
            payload.put("macs", macArray);

            Log.d(LOG_TAG, "API endpoint: " + ENDPOINT_URL);
            Log.d(LOG_TAG, "Request payload: " + payload.toString());

            final String payloadString = payload.toString();

            StringRequest httpRequest = new StringRequest(
                    Request.Method.POST,
                    ENDPOINT_URL,
                    response -> {
                        Log.d(LOG_TAG, "Server response: " + response);
                        handleServerResponse(response);
                    },
                    error -> {
                        String errorDetails;
                        NetworkResponse netResponse = error.networkResponse;
                        if (netResponse != null) {
                            String body = new String(netResponse.data);
                            errorDetails = "HTTP " + netResponse.statusCode + ": " + body;
                        } else {
                            errorDetails = "Network error: " + error.getMessage();
                        }
                        Log.e(LOG_TAG, errorDetails);
                        if (handler != null) {
                            handler.onError(errorDetails);
                        }
                    }
            ) {
                @Override
                public byte[] getBody() {
                    return payloadString.getBytes();
                }

                @Override
                public String getBodyContentType() {
                    return "application/json";
                }
            };

            networkQueue.add(httpRequest);

        } catch (JSONException e) {
            Log.e(LOG_TAG, "Failed to build JSON payload: " + e.getMessage());
            if (handler != null) {
                handler.onError("JSON construction failed: " + e.getMessage());
            }
        }
    }

    /**
     * Process server's JSON response containing building data.
     */
    private void handleServerResponse(String jsonResponse) {
        try {
            List<BuildingData> buildingsList = new ArrayList<>();
            JSONArray responseArray = new JSONArray(jsonResponse);

            for (int i = 0; i < responseArray.length(); i++) {
                JSONObject item = responseArray.getJSONObject(i);

                BuildingData building = new BuildingData();
                building.buildingName = item.optString("name", "Unknown Building");
                building.rawOutline = item.optString("outline", "");
                building.rawGeometryData = item.optString("map_shapes", "");

                extractBoundaryGeometry(building);
                processFloorGeometry(building);

                buildingsList.add(building);
                Log.d(LOG_TAG, "Parsed building: " + building.buildingName
                        + ", boundary points: " + building.boundaryPoints.size()
                        + ", levels: " + building.levelsAvailable.size()
                        + ", geometries: " + building.floorLayers.size());

                if (building.rawGeometryData.length() > 0) {
                    Log.d(LOG_TAG, "Geometry data preview: " + building.rawGeometryData.substring(0,
                            Math.min(500, building.rawGeometryData.length())));
                }
            }

            Log.d(LOG_TAG, "Total buildings parsed: " + buildingsList.size());

            if (responseHandler != null) {
                responseHandler.onSuccess(buildingsList);
            }

        } catch (JSONException e) {
            Log.e(LOG_TAG, "JSON parsing error: " + e.getMessage());
            if (responseHandler != null) {
                responseHandler.onError("Failed to parse response: " + e.getMessage());
            }
        }
    }

    /**
     * Extract building boundary polygon from GeoJSON FeatureCollection.
     * GeoJSON uses [lon, lat] ordering - we convert to [lat, lon] for Google Maps compatibility.
     */
    private void extractBoundaryGeometry(BuildingData building) {
        if (building.rawOutline == null || building.rawOutline.isEmpty()) return;

        try {
            JSONObject geoJson = new JSONObject(building.rawOutline);
            JSONArray featureList = geoJson.getJSONArray("features");

            for (int i = 0; i < featureList.length(); i++) {
                JSONObject feature = featureList.getJSONObject(i);
                JSONObject geom = feature.getJSONObject("geometry");
                String geomType = geom.getString("type");

                if ("Polygon".equals(geomType)) {
                    JSONArray coordRings = geom.getJSONArray("coordinates");
                    JSONArray exteriorRing = coordRings.getJSONArray(0);
                    for (int j = 0; j < exteriorRing.length(); j++) {
                        JSONArray coordinate = exteriorRing.getJSONArray(j);
                        double longitude = coordinate.getDouble(0);
                        double latitude = coordinate.getDouble(1);
                        building.boundaryPoints.add(new double[]{latitude, longitude});
                    }
                } else if ("MultiPolygon".equals(geomType)) {
                    JSONArray polygonSet = geom.getJSONArray("coordinates");
                    JSONArray firstPoly = polygonSet.getJSONArray(0);
                    JSONArray exteriorRing = firstPoly.getJSONArray(0);
                    for (int j = 0; j < exteriorRing.length(); j++) {
                        JSONArray coordinate = exteriorRing.getJSONArray(j);
                        double longitude = coordinate.getDouble(0);
                        double latitude = coordinate.getDouble(1);
                        building.boundaryPoints.add(new double[]{latitude, longitude});
                    }
                }
            }

            Log.d(LOG_TAG, "Extracted boundary: " + building.boundaryPoints.size()
                    + " points for " + building.buildingName);

        } catch (JSONException e) {
            Log.e(LOG_TAG, "Boundary extraction failed for " + building.buildingName
                    + ": " + e.getMessage());
        }
    }

    /**
     * Parse floor-level geometric features from building's map_shapes data.
     * Structure: {"B1": {FeatureCollection}, "G": {FeatureCollection}, "1": {FeatureCollection}}
     * Keys represent floor identifiers, values are GeoJSON feature collections.
     */
    private void processFloorGeometry(BuildingData building) {
        if (building.rawGeometryData == null || building.rawGeometryData.isEmpty()) return;

        try {
            JSONObject levelMap = new JSONObject(building.rawGeometryData);
            java.util.Set<Integer> levelNumbers = new java.util.TreeSet<>();

            java.util.Iterator<String> levelKeys = levelMap.keys();
            while (levelKeys.hasNext()) {
                String levelKey = levelKeys.next();
                int levelNum = interpretFloorLabel(levelKey);

                Log.d(LOG_TAG, "Processing level: " + levelKey + " → numeric value " + levelNum);

                JSONObject levelFeatures = levelMap.getJSONObject(levelKey);
                JSONArray featureArray = levelFeatures.getJSONArray("features");

                for (int i = 0; i < featureArray.length(); i++) {
                    JSONObject feature = featureArray.getJSONObject(i);
                    JSONObject geometry = feature.getJSONObject("geometry");

                    FloorGeometry geom = new FloorGeometry(levelNum);
                    geom.levelName = levelKey;

                    JSONObject properties = feature.optJSONObject("properties");
                    if (properties != null) {
                        geom.imageSource = properties.optString("image_url",
                                properties.optString("image", ""));
                        geom.featureType = properties.optString("indoor_type", "");
                        geom.areaLabel = properties.optString("name",
                                properties.optString("label",
                                properties.optString("room_name",
                                properties.optString("ref", ""))));

                        if (i < 3) {
                            Log.d(LOG_TAG, "Level " + levelKey + " feature[" + i
                                    + "] properties: " + properties.toString());
                        }
                    }

                    String shapeType = geometry.getString("type");
                    if ("Polygon".equals(shapeType)) {
                        JSONArray ring = geometry.getJSONArray("coordinates").getJSONArray(0);
                        for (int j = 0; j < ring.length(); j++) {
                            JSONArray point = ring.getJSONArray(j);
                            geom.polygonPoints.add(new double[]{point.getDouble(1), point.getDouble(0)});
                        }
                    } else if ("MultiPolygon".equals(shapeType)) {
                        JSONArray polySet = geometry.getJSONArray("coordinates").getJSONArray(0);
                        JSONArray ring = polySet.getJSONArray(0);
                        for (int j = 0; j < ring.length(); j++) {
                            JSONArray point = ring.getJSONArray(j);
                            geom.polygonPoints.add(new double[]{point.getDouble(1), point.getDouble(0)});
                        }
                    }

                    if (!geom.polygonPoints.isEmpty()) {
                        building.floorLayers.add(geom);
                        levelNumbers.add(levelNum);
                    }
                }

                building.levelLabels.put(levelNum, levelKey);
            }

            building.levelsAvailable.addAll(levelNumbers);

            java.util.Map<String, Integer> typeCounts = new java.util.HashMap<>();
            int namedFeatures = 0;
            for (FloorGeometry g : building.floorLayers) {
                String t = g.featureType.isEmpty() ? "(empty)" : g.featureType;
                typeCounts.put(t, typeCounts.getOrDefault(t, 0) + 1);
                if (!g.areaLabel.isEmpty()) namedFeatures++;
            }
            Log.d(LOG_TAG, "Geometry processed: " + building.floorLayers.size() + " shapes, "
                    + building.levelsAvailable.size() + " levels: " + building.levelsAvailable
                    + " labels: " + building.levelLabels
                    + " types: " + typeCounts
                    + " named features: " + namedFeatures);

        } catch (JSONException e) {
            Log.e(LOG_TAG, "Geometry processing failed for " + building.buildingName
                    + ": " + e.getMessage());
        }
    }

    /**
     * Convert textual floor identifier to numeric value.
     * "B2"→-2, "B1"→-1, "G"/"GF"/"Ground"→0, "F1"/"1"→1, "F2"/"2"→2
     */
    private int interpretFloorLabel(String label) {
        if (label == null || label.isEmpty()) return 0;
        String normalized = label.toUpperCase().trim();
        if (normalized.equals("G") || normalized.equals("GF") || normalized.equals("GROUND")) return 0;

        if (normalized.startsWith("B")) {
            try {
                return -Integer.parseInt(normalized.substring(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        if (normalized.startsWith("F")) {
            try {
                return Integer.parseInt(normalized.substring(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
