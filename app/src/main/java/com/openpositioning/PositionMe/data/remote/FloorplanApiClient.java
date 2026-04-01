package com.openpositioning.PositionMe.data.remote;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * API client for the OpenPositioning floorplan request endpoint.
 * Requests nearby building information based on GPS coordinates and observed WiFi MAC addresses.
 * Returns building outlines (GeoJSON polygons) and floor shape data (map_shapes).
 *
 * @see ServerCommunications for other API interactions
 */
public class FloorplanApiClient {

    private static final String TAG = "FloorplanApiClient";
    private static final String userKey = BuildConfig.OPENPOSITIONING_API_KEY;
    private static final String masterKey = BuildConfig.OPENPOSITIONING_MASTER_KEY;
    private static final String BASE_URL =
            "https://openpositioning.org/api/live/floorplan/request/";
    private static final MediaType JSON_TYPE =
            MediaType.parse("application/json; charset=utf-8");

    /**
     * Represents a single indoor map feature such as a wall segment or room polygon,
     * parsed from the map_shapes GeoJSON data.
     */
    public static class MapShapeFeature {
        private final String indoorType;
        private final String geometryType;
        private final List<List<LatLng>> parts;

        /**
         * Constructs a MapShapeFeature.
         *
         * @param indoorType   feature type from properties (e.g. "wall", "room")
         * @param geometryType GeoJSON geometry type (e.g. "MultiLineString", "MultiPolygon")
         * @param parts        coordinate lists: each inner list is a line or polygon ring
         */
        public MapShapeFeature(String indoorType, String geometryType,
                               List<List<LatLng>> parts) {
            this.indoorType = indoorType;
            this.geometryType = geometryType;
            this.parts = parts;
        }

        /** Returns the indoor feature type (e.g. "wall", "room"). */
        public String getIndoorType() { return indoorType; }

        /** Returns the GeoJSON geometry type. */
        public String getGeometryType() { return geometryType; }

        /** Returns coordinate parts: lines for MultiLineString, rings for MultiPolygon. */
        public List<List<LatLng>> getParts() { return parts; }
    }

    /**
     * Holds all indoor map features for a single floor, parsed from map_shapes.
     */
    public static class FloorShapes {
        private final String key;
        private final String displayName;
        private final List<MapShapeFeature> features;

        /**
         * Constructs a FloorShapes.
         *
         * @param key         floor key from API (e.g. "B1", "B2")
         * @param displayName human-readable floor name (e.g. "LG", "1", "2")
         * @param features    list of indoor map features on this floor
         */
        public FloorShapes(String key, String displayName,
                           List<MapShapeFeature> features) {
            this.key = key;
            this.displayName = displayName;
            this.features = features;
        }

        /** Returns the floor key (e.g. "B1"). */
        public String getKey() { return key; }

        /** Returns the display name (e.g. "LG", "1"). */
        public String getDisplayName() { return displayName; }

        /** Returns the list of features on this floor. */
        public List<MapShapeFeature> getFeatures() { return features; }
    }

    /**
     * Represents a building returned by the floorplan API, including its name,
     * raw GeoJSON strings for outline and floor shapes, and parsed polygon coordinates.
     */
    public static class BuildingInfo {
        private final String name;
        private final String outlineJson;
        private final String mapShapesJson;
        private final List<LatLng> outlinePolygon;
        private final List<FloorShapes> floorShapesList;

        /**
         * Constructs a BuildingInfo.
         *
         * @param name            building/campaign name (e.g. "nucleus_building")
         * @param outlineJson     raw GeoJSON string for the building outline
         * @param mapShapesJson   raw GeoJSON string for floor shape data
         * @param outlinePolygon  parsed polygon coordinates from the outline
         * @param floorShapesList parsed per-floor vector shape data
         */
        public BuildingInfo(String name, String outlineJson,
                            String mapShapesJson, List<LatLng> outlinePolygon,
                            List<FloorShapes> floorShapesList) {
            this.name = name;
            this.outlineJson = outlineJson;
            this.mapShapesJson = mapShapesJson;
            this.outlinePolygon = outlinePolygon;
            this.floorShapesList = floorShapesList;
        }

        /** Returns the building/campaign name. */
        public String getName() { return name; }

        /** Returns the raw GeoJSON outline string. */
        public String getOutlineJson() { return outlineJson; }

        /** Returns the raw GeoJSON map shapes string. */
        public String getMapShapesJson() { return mapShapesJson; }

        /** Returns the parsed polygon coordinates for the building outline. */
        public List<LatLng> getOutlinePolygon() { return outlinePolygon; }

        /** Returns the parsed per-floor vector shape data, indexed by floor. */
        public List<FloorShapes> getFloorShapesList() { return floorShapesList; }

        /**
         * Computes the centroid of the outline polygon.
         *
         * @return center LatLng, or (0,0) if polygon is empty
         */
        public LatLng getCenter() {
            if (outlinePolygon == null || outlinePolygon.isEmpty()) {
                return new LatLng(0, 0);
            }
            double latSum = 0, lonSum = 0;
            for (LatLng p : outlinePolygon) {
                latSum += p.latitude;
                lonSum += p.longitude;
            }
            return new LatLng(latSum / outlinePolygon.size(),
                    lonSum / outlinePolygon.size());
        }
    }

    /**
     * Callback interface for asynchronous floorplan API requests.
     */
    public interface FloorplanCallback {
        /** Called on the main thread when the request succeeds. */
        void onSuccess(List<BuildingInfo> buildings);
        /** Called on the main thread when the request fails. */
        void onFailure(String error);
    }

    /**
     * Requests floorplan data from the server for the given position.
     *
     * @param lat      latitude of the current position
     * @param lon      longitude of the current position
     * @param macs     list of observed WiFi MAC addresses (BSSID strings), may be empty
     * @param callback callback to receive the result on the main thread
     */
    public void requestFloorplan(double lat, double lon, List<String> macs,
                                  FloorplanCallback callback) {
        String url = BASE_URL + userKey + "?key=" + masterKey;

        // Build JSON request body
        JSONObject body = new JSONObject();
        try {
            body.put("lat", lat);
            body.put("lon", lon);
            JSONArray macsArray = new JSONArray();
            if (macs != null) {
                for (String mac : macs) {
                    macsArray.put(mac);
                }
            }
            body.put("macs", macsArray);
        } catch (JSONException e) {
            callback.onFailure("Failed to build request body: " + e.getMessage());
            return;
        }

        OkHttpClient client = new OkHttpClient();
        RequestBody requestBody = RequestBody.create(JSON_TYPE, body.toString());

        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Floorplan request failed", e);
                postToMainThread(() ->
                        callback.onFailure("Network error: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful() || responseBody == null) {
                        String error = responseBody != null
                                ? responseBody.string() : "Unknown error";
                        Log.e(TAG, "Floorplan request error: "
                                + response.code() + " " + error);
                        postToMainThread(() ->
                                callback.onFailure("Server error: " + response.code()));
                        return;
                    }

                    String json = responseBody.string();
                    Log.d(TAG, "Floorplan response length: " + json.length());

                    List<BuildingInfo> buildings = parseResponse(json);
                    postToMainThread(() -> callback.onSuccess(buildings));
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to parse floorplan response", e);
                    postToMainThread(() ->
                            callback.onFailure("Parse error: " + e.getMessage()));
                }
            }
        });
    }

    /**
     * Parses the JSON response array into a list of BuildingInfo objects.
     *
     * @param json the raw JSON response string (a JSON array)
     * @return list of BuildingInfo objects
     * @throws JSONException if the JSON structure is invalid
     */
    private List<BuildingInfo> parseResponse(String json) throws JSONException {
        List<BuildingInfo> buildings = new ArrayList<>();
        JSONArray array = new JSONArray(json);

        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            String name = obj.getString("name");
            String outlineJson = obj.optString("outline", "");
            String mapShapesJson = obj.optString("map_shapes", "");

            List<LatLng> polygon = parseOutlineGeoJson(outlineJson);
            List<FloorShapes> floorShapes = parseMapShapes(mapShapesJson);
            buildings.add(new BuildingInfo(name, outlineJson, mapShapesJson,
                    polygon, floorShapes));
        }

        return buildings;
    }

    /**
     * Parses a GeoJSON FeatureCollection outline string into a list of LatLng coordinates.
     * Handles both MultiPolygon and Polygon geometry types.
     * GeoJSON uses [longitude, latitude] coordinate order.
     *
     * @param geoJsonStr the stringified GeoJSON
     * @return list of LatLng points forming the outline polygon
     */
    private List<LatLng> parseOutlineGeoJson(String geoJsonStr) {
        List<LatLng> points = new ArrayList<>();
        if (geoJsonStr == null || geoJsonStr.isEmpty()) return points;

        try {
            JSONObject geoJson = new JSONObject(geoJsonStr);
            JSONArray features = geoJson.getJSONArray("features");

            if (features.length() > 0) {
                JSONObject geometry = features.getJSONObject(0)
                        .getJSONObject("geometry");
                String type = geometry.getString("type");

                JSONArray ring = extractFirstRing(geometry, type);
                if (ring != null) {
                    for (int i = 0; i < ring.length(); i++) {
                        JSONArray coord = ring.getJSONArray(i);
                        // GeoJSON: [longitude, latitude]
                        double lon = coord.getDouble(0);
                        double lat = coord.getDouble(1);
                        points.add(new LatLng(lat, lon));
                    }
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse GeoJSON outline", e);
        }

        return points;
    }

    /**
     * Extracts the first coordinate ring from a GeoJSON geometry.
     *
     * @param geometry the GeoJSON geometry object
     * @param type     the geometry type ("MultiPolygon" or "Polygon")
     * @return the first coordinate ring as a JSONArray, or null
     * @throws JSONException if parsing fails
     */
    private JSONArray extractFirstRing(JSONObject geometry, String type)
            throws JSONException {
        JSONArray coordinates = geometry.getJSONArray("coordinates");

        if ("MultiPolygon".equals(type)) {
            // MultiPolygon: coordinates[polygon_index][ring_index][point_index]
            if (coordinates.length() > 0) {
                JSONArray firstPolygon = coordinates.getJSONArray(0);
                if (firstPolygon.length() > 0) {
                    return firstPolygon.getJSONArray(0);
                }
            }
        } else if ("Polygon".equals(type)) {
            // Polygon: coordinates[ring_index][point_index]
            if (coordinates.length() > 0) {
                return coordinates.getJSONArray(0);
            }
        }

        return null;
    }

    /**
     * Parses the map_shapes JSON string into a list of FloorShapes, sorted by floor key.
     * The top-level JSON is an object with keys like "B1", "B2", etc. Each value is a
     * GeoJSON FeatureCollection containing indoor features (walls, rooms, etc.).
     *
     * @param mapShapesJson the raw map_shapes JSON string from the API
     * @return list of FloorShapes sorted by key (B1=index 0, B2=index 1, ...)
     */
    private List<FloorShapes> parseMapShapes(String mapShapesJson) {
        List<FloorShapes> result = new ArrayList<>();
        if (mapShapesJson == null || mapShapesJson.isEmpty()) return result;

        try {
            JSONObject root = new JSONObject(mapShapesJson);

            // Collect and sort floor keys (B1, B2, B3...)
            List<String> keys = new ArrayList<>();
            Iterator<String> it = root.keys();
            while (it.hasNext()) {
                keys.add(it.next());
            }
            // Sort floor keys in building-logical order:
            // Basement levels (B*) first, then Ground (G*), then upper floors (F1, F2, ...)
            Collections.sort(keys, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return floorKeyOrder(a) - floorKeyOrder(b);
                }

                private int floorKeyOrder(String key) {
                    String upper = key.toUpperCase();
                    // Basement levels: B1=-2, B2=-3, etc.
                    if (upper.startsWith("B")) {
                        try {
                            return -Integer.parseInt(upper.substring(1)) - 1;
                        } catch (NumberFormatException e) {
                            return -1;
                        }
                    }
                    // Ground floor
                    if (upper.startsWith("G")) return 0;
                    // Upper floors: F1=1, F2=2, etc.
                    if (upper.startsWith("F")) {
                        try {
                            return Integer.parseInt(upper.substring(1));
                        } catch (NumberFormatException e) {
                            return 100;
                        }
                    }
                    // Pure numeric keys
                    try {
                        return Integer.parseInt(upper);
                    } catch (NumberFormatException e) {
                        return 100;
                    }
                }
            });

            Log.e(TAG, "FLOOR_PARSE: building floor keys (sorted): " + keys);

            for (int idx = 0; idx < keys.size(); idx++) {
                String key = keys.get(idx);
                JSONObject floorCollection = root.getJSONObject(key);
                String displayName = floorCollection.optString("name", key);
                JSONArray features = floorCollection.optJSONArray("features");

                Log.e(TAG, "FLOOR_PARSE: index=" + idx
                        + " | key=\"" + key + "\""
                        + " | displayName=\"" + displayName + "\""
                        + " | features=" + (features != null ? features.length() : 0));

                List<MapShapeFeature> shapeFeatures = new ArrayList<>();
                if (features != null) {
                    for (int i = 0; i < features.length(); i++) {
                        MapShapeFeature f = parseMapShapeFeature(
                                features.getJSONObject(i));
                        if (f != null) {
                            shapeFeatures.add(f);
                        }
                    }
                }
                result.add(new FloorShapes(key, displayName, shapeFeatures));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse map_shapes", e);
        }

        return result;
    }

    /**
     * Parses a single GeoJSON Feature from map_shapes into a MapShapeFeature.
     *
     * @param feature the GeoJSON Feature object
     * @return parsed MapShapeFeature, or null if parsing fails
     */
    private MapShapeFeature parseMapShapeFeature(JSONObject feature) {
        try {
            JSONObject properties = feature.optJSONObject("properties");
            String indoorType = (properties != null)
                    ? properties.optString("indoor_type", "unknown") : "unknown";

            JSONObject geometry = feature.getJSONObject("geometry");
            String geoType = geometry.getString("type");
            JSONArray coordinates = geometry.getJSONArray("coordinates");

            List<List<LatLng>> parts = new ArrayList<>();

            if ("MultiLineString".equals(geoType)) {
                // coordinates[line_index][point_index] = [lon, lat]
                for (int i = 0; i < coordinates.length(); i++) {
                    JSONArray line = coordinates.getJSONArray(i);
                    parts.add(parseCoordArray(line));
                }
            } else if ("MultiPolygon".equals(geoType)) {
                // coordinates[polygon_index][ring_index][point_index] = [lon, lat]
                for (int i = 0; i < coordinates.length(); i++) {
                    JSONArray polygon = coordinates.getJSONArray(i);
                    if (polygon.length() > 0) {
                        // Use the outer ring (index 0) of each polygon
                        parts.add(parseCoordArray(polygon.getJSONArray(0)));
                    }
                }
            } else if ("LineString".equals(geoType)) {
                // coordinates[point_index] = [lon, lat]
                parts.add(parseCoordArray(coordinates));
            } else if ("Polygon".equals(geoType)) {
                // coordinates[ring_index][point_index] = [lon, lat]
                if (coordinates.length() > 0) {
                    parts.add(parseCoordArray(coordinates.getJSONArray(0)));
                }
            } else {
                Log.d(TAG, "Unsupported geometry type in map_shapes: " + geoType);
                return null;
            }

            return new MapShapeFeature(indoorType, geoType, parts);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse map_shapes feature", e);
            return null;
        }
    }

    /**
     * Parses a GeoJSON coordinate array into a list of LatLng points.
     * GeoJSON coordinate order is [longitude, latitude].
     *
     * @param coordArray the JSON array of [lon, lat] coordinate pairs
     * @return list of LatLng points
     * @throws JSONException if parsing fails
     */
    private List<LatLng> parseCoordArray(JSONArray coordArray) throws JSONException {
        List<LatLng> points = new ArrayList<>();
        for (int i = 0; i < coordArray.length(); i++) {
            JSONArray coord = coordArray.getJSONArray(i);
            double lon = coord.getDouble(0);
            double lat = coord.getDouble(1);
            points.add(new LatLng(lat, lon));
        }
        return points;
    }

    /**
     * Posts a runnable to the main (UI) thread.
     */
    private void postToMainThread(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }
}
