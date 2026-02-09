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

import java.util.ArrayList;
import java.util.List;

/**
 * FloorplanAPI - Requests nearby indoor map data from the OpenPositioning API.
 * API endpoint: POST /api/live/floorplan/request/{api_key}?key={master_key}
 * Request body: {"lat": double, "lon": double, "macs": [string]}
 * Response: [{"name": string, "outline": string, "map_shapes": string}]
 */
public class FloorplanAPI {

    private static final String TAG = "FloorplanAPI";

    private static final String userKey = "MShXCzrAnhyDauNeeP_O8g";
    private static final String masterKey = "ewireless";
    private static final String API_URL =
            "https://openpositioning.org/api/live/floorplan/request/" + userKey + "?key=" + masterKey;

    private RequestQueue requestQueue;
    private FloorplanCallback callback;

    public FloorplanAPI(Context context) {
        requestQueue = Volley.newRequestQueue(context);
    }

    /**
     * A single floor shape (room/area polygon on a specific floor).
     */
    public static class FloorShape {
        public int floor;                   // Floor number
        public String floorName;            // Floor name (e.g. "Ground Floor")
        public List<double[]> coords;       // Polygon coordinates [lat, lon]
        public String imageUrl;             // Floor plan image URL (if available)
        public String indoorType;           // Type: "wall", "room", "corridor", "stairs", etc.
        public String name;                 // Area name/label (if available)

        public FloorShape(int floor) {
            this.floor = floor;
            this.floorName = "Floor " + floor;
            this.coords = new ArrayList<>();
            this.indoorType = "";
            this.name = "";
        }
    }

    /**
     * Campaign/venue information returned by the API.
     */
    public static class Venue {
        public String name;
        public String outline;                  // Raw outline string from API
        public String mapShapes;                // Raw map_shapes string from API
        public List<double[]> outlineCoords;    // Parsed outline coordinates [lat, lon]
        public List<FloorShape> floorShapes;    // Parsed floor shapes from map_shapes
        public List<Integer> availableFloors;   // Sorted list of floor numbers
        public java.util.Map<Integer, String> floorNameMap; // floor number → original key name (e.g. 0→"G", -1→"B1")

        public Venue() {
            outlineCoords = new ArrayList<>();
            floorShapes = new ArrayList<>();
            availableFloors = new ArrayList<>();
            floorNameMap = new java.util.HashMap<>();
        }
    }

    /**
     * Callback interface for API results.
     */
    public interface FloorplanCallback {
        void onSuccess(List<Venue> venues);
        void onError(String error);
    }

    /**
     * Request nearby indoor maps from the API.
     *
     * @param latitude  Current latitude
     * @param longitude Current longitude
     * @param macs      List of observed WiFi MAC addresses
     * @param callback  Callback for results
     */
    public void requestNearbyFloorplans(double latitude,
                                        double longitude,
                                        List<String> macs,
                                        FloorplanCallback callback) {
        this.callback = callback;

        try {
            // Build request body: {"lat": ..., "lon": ..., "macs": [...]}
            JSONObject requestBody = new JSONObject();
            requestBody.put("lat", latitude);
            requestBody.put("lon", longitude);

            JSONArray macsArray = new JSONArray();
            for (String mac : macs) {
                macsArray.put(mac);
            }
            requestBody.put("macs", macsArray);

            Log.d(TAG, "Request URL: " + API_URL);
            Log.d(TAG, "Request body: " + requestBody.toString());

            final String body = requestBody.toString();

            // POST request with JSON body, expecting JSON array response
            StringRequest request = new StringRequest(
                    Request.Method.POST,
                    API_URL,
                    response -> {
                        Log.d(TAG, "API response: " + response);
                        parseResponse(response);
                    },
                    error -> {
                        String errorMsg;
                        NetworkResponse networkResp = error.networkResponse;
                        if (networkResp != null) {
                            String respBody = new String(networkResp.data);
                            errorMsg = "HTTP " + networkResp.statusCode + ": " + respBody;
                        } else {
                            errorMsg = "Network error: " + error.getMessage();
                        }
                        Log.e(TAG, errorMsg);
                        if (callback != null) {
                            callback.onError(errorMsg);
                        }
                    }
            ) {
                @Override
                public byte[] getBody() {
                    return body.getBytes();
                }

                @Override
                public String getBodyContentType() {
                    return "application/json";
                }
            };

            requestQueue.add(request);

        } catch (JSONException e) {
            Log.e(TAG, "JSON build error: " + e.getMessage());
            if (callback != null) {
                callback.onError("JSON build failed: " + e.getMessage());
            }
        }
    }

    /**
     * Parse the API response (JSON array of campaigns).
     */
    private void parseResponse(String responseStr) {
        try {
            List<Venue> venues = new ArrayList<>();
            JSONArray array = new JSONArray(responseStr);

            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);

                Venue venue = new Venue();
                venue.name = obj.optString("name", "Unknown");
                venue.outline = obj.optString("outline", "");
                venue.mapShapes = obj.optString("map_shapes", "");

                // Parse outline and map_shapes as GeoJSON
                parseOutlineCoords(venue);
                parseMapShapes(venue);

                venues.add(venue);
                Log.d(TAG, "Venue: " + venue.name
                        + ", outline coords: " + venue.outlineCoords.size()
                        + ", floors: " + venue.availableFloors.size()
                        + ", floor shapes: " + venue.floorShapes.size());
            }

            Log.d(TAG, "Parsed " + venues.size() + " venues");

            if (callback != null) {
                callback.onSuccess(venues);
            }

        } catch (JSONException e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
            if (callback != null) {
                callback.onError("Parse failed: " + e.getMessage());
            }
        }
    }

    /**
     * Parse venue outline from GeoJSON FeatureCollection format.
     * GeoJSON coordinates are [longitude, latitude] - we swap to [lat, lon] for Google Maps.
     */
    private void parseOutlineCoords(Venue venue) {
        if (venue.outline == null || venue.outline.isEmpty()) return;

        try {
            JSONObject geojson = new JSONObject(venue.outline);
            JSONArray features = geojson.getJSONArray("features");

            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.getJSONObject(i);
                JSONObject geometry = feature.getJSONObject("geometry");
                String type = geometry.getString("type");

                if ("Polygon".equals(type)) {
                    // Polygon coordinates: [[[lon,lat], [lon,lat], ...]]
                    JSONArray rings = geometry.getJSONArray("coordinates");
                    JSONArray outerRing = rings.getJSONArray(0);
                    for (int j = 0; j < outerRing.length(); j++) {
                        JSONArray point = outerRing.getJSONArray(j);
                        double lon = point.getDouble(0);
                        double lat = point.getDouble(1);
                        venue.outlineCoords.add(new double[]{lat, lon});
                    }
                } else if ("MultiPolygon".equals(type)) {
                    // MultiPolygon: [[[[lon,lat], ...]]]
                    JSONArray polygons = geometry.getJSONArray("coordinates");
                    JSONArray firstPolygon = polygons.getJSONArray(0);
                    JSONArray outerRing = firstPolygon.getJSONArray(0);
                    for (int j = 0; j < outerRing.length(); j++) {
                        JSONArray point = outerRing.getJSONArray(j);
                        double lon = point.getDouble(0);
                        double lat = point.getDouble(1);
                        venue.outlineCoords.add(new double[]{lat, lon});
                    }
                }
            }

            Log.d(TAG, "GeoJSON parsed: " + venue.outlineCoords.size() + " coordinates for " + venue.name);

        } catch (JSONException e) {
            Log.e(TAG, "GeoJSON parse error for " + venue.name + ": " + e.getMessage());
        }
    }

    /**
     * Parse map_shapes to extract floor-level shape data.
     * Format: {"B1": {FeatureCollection}, "G": {FeatureCollection}, "1": {FeatureCollection}, ...}
     * Top-level keys are floor names, each value is a GeoJSON FeatureCollection.
     */
    private void parseMapShapes(Venue venue) {
        if (venue.mapShapes == null || venue.mapShapes.isEmpty()) return;

        try {
            JSONObject floorMap = new JSONObject(venue.mapShapes);
            java.util.Set<Integer> floorSet = new java.util.TreeSet<>();

            // Iterate over floor keys (e.g. "B1", "G", "1", "2")
            java.util.Iterator<String> floorKeys = floorMap.keys();
            while (floorKeys.hasNext()) {
                String floorKey = floorKeys.next();
                int floorNum = parseFloorName(floorKey);

                Log.d(TAG, "Parsing floor: " + floorKey + " → number " + floorNum);

                JSONObject floorCollection = floorMap.getJSONObject(floorKey);
                JSONArray features = floorCollection.getJSONArray("features");

                for (int i = 0; i < features.length(); i++) {
                    JSONObject feature = features.getJSONObject(i);
                    JSONObject geometry = feature.getJSONObject("geometry");

                    FloorShape shape = new FloorShape(floorNum);
                    shape.floorName = floorKey;

                    // Extract properties (indoor_type, name, image_url)
                    JSONObject properties = feature.optJSONObject("properties");
                    if (properties != null) {
                        shape.imageUrl = properties.optString("image_url",
                                properties.optString("image", ""));
                        shape.indoorType = properties.optString("indoor_type", "");
                        shape.name = properties.optString("name",
                                properties.optString("label",
                                properties.optString("room_name",
                                properties.optString("ref", ""))));
                    }

                    // Parse coordinates
                    String type = geometry.getString("type");
                    if ("Polygon".equals(type)) {
                        JSONArray outerRing = geometry.getJSONArray("coordinates").getJSONArray(0);
                        for (int j = 0; j < outerRing.length(); j++) {
                            JSONArray point = outerRing.getJSONArray(j);
                            shape.coords.add(new double[]{point.getDouble(1), point.getDouble(0)});
                        }
                    } else if ("MultiPolygon".equals(type)) {
                        JSONArray firstPoly = geometry.getJSONArray("coordinates").getJSONArray(0);
                        JSONArray outerRing = firstPoly.getJSONArray(0);
                        for (int j = 0; j < outerRing.length(); j++) {
                            JSONArray point = outerRing.getJSONArray(j);
                            shape.coords.add(new double[]{point.getDouble(1), point.getDouble(0)});
                        }
                    }

                    if (!shape.coords.isEmpty()) {
                        venue.floorShapes.add(shape);
                        floorSet.add(floorNum);
                    }
                }

                venue.floorNameMap.put(floorNum, floorKey);
            }

            venue.availableFloors.addAll(floorSet);

            Log.d(TAG, "map_shapes parsed: " + venue.floorShapes.size() + " shapes, "
                    + venue.availableFloors.size() + " floors: " + venue.availableFloors);

        } catch (JSONException e) {
            Log.e(TAG, "map_shapes parse error for " + venue.name + ": " + e.getMessage());
        }
    }

    /**
     * Convert floor name string to integer.
     * "B2"→-2, "B1"→-1, "G"/"GF"/"Ground"→0, "F1"/"1"→1, "F2"/"2"→2, etc.
     */
    private int parseFloorName(String floorName) {
        if (floorName == null || floorName.isEmpty()) return 0;
        String upper = floorName.toUpperCase().trim();
        if (upper.equals("G") || upper.equals("GF") || upper.equals("GROUND")) return 0;
        // Basement: "B1"→-1, "B2"→-2
        if (upper.startsWith("B")) {
            try {
                return -Integer.parseInt(upper.substring(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        // Floor prefix: "F1"→1, "F2"→2, "F3"→3
        if (upper.startsWith("F")) {
            try {
                return Integer.parseInt(upper.substring(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        // Pure number: "1"→1, "2"→2
        try {
            return Integer.parseInt(upper);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
