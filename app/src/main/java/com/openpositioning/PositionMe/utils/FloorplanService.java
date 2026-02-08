package com.openpositioning.PositionMe.utils;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Requests nearby floorplans from the OpenPositioning API and download outline/map data.
 * Response example per assignment:
 */
public class FloorplanService {

    public interface FloorplanCallback {
        void onSuccess(List<Venue> venues);
        void onError(String msg);
    }

    public static class Venue {
        public final String name;
        public final String outlineRaw;
        public final String mapShapesRaw;

        public Venue(String name, String outlineRaw, String mapShapesRaw) {
            this.name = name;
            this.outlineRaw = outlineRaw;
            this.mapShapesRaw = mapShapesRaw;
        }

        /** Simple container for a single floor’s shapes. */
        public static class FloorShape {
            public final String name;
            public final List<List<LatLng>> lines;

            public FloorShape(String name, List<List<LatLng>> lines) {
                this.name = name;
                this.lines = lines;
            }
        }

        /**
         * Try to parse outline as GeoJSON MultiPolygon/Polygon string first; fall back to lat,lng;lat,lng.
         */
        @Nullable
        public List<LatLng> parseOutline() {
            if (outlineRaw == null || outlineRaw.isEmpty()) return null;
            // Attempt GeoJSON
            List<LatLng> geo = GeoParser.parseFirstPolygonRing(outlineRaw);
            if (geo != null && !geo.isEmpty()) return geo;

            // Fallback: semicolon-separated lat,lng pairs
            String[] pairs = outlineRaw.split(";");
            List<LatLng> result = new ArrayList<>();
            for (String pair : pairs) {
                String[] parts = pair.split(",");
                if (parts.length != 2) continue;
                try {
                    double lat = Double.parseDouble(parts[0].trim());
                    double lng = Double.parseDouble(parts[1].trim());
                    result.add(new LatLng(lat, lng));
                } catch (NumberFormatException ignored) {
                }
            }
            return result.isEmpty() ? null : result;
        }

        /**
         * Parse map_shapes GeoJSON into a list of polyline rings (each is a list of LatLng).
         */
        @Nullable
        public List<List<LatLng>> parseMapShapes() {
            if (mapShapesRaw == null || mapShapesRaw.isEmpty()) return null;
            return GeoParser.parseMultiLineOrPolygonCollection(mapShapesRaw);
        }

        /** Parse floor-separated map shapes; each floor has its own set of polylines. */
        @Nullable
        public List<FloorShape> parseFloorShapes() {
            if (mapShapesRaw == null || mapShapesRaw.isEmpty()) return null;
            var floorMap = GeoParser.parseFloorPolylines(mapShapesRaw);
            if (floorMap == null || floorMap.isEmpty()) return null;
            List<FloorShape> floors = new ArrayList<>();
            for (var entry : floorMap.entrySet()) {
                floors.add(new FloorShape(entry.getKey(), entry.getValue()));
            }
            return floors;
        }
    }

    private static final String TAG = "FloorplanService";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient client = new OkHttpClient();
    private final String endpoint = "https://openpositioning.org/api/live/floorplan/request";

    public void requestVenues(double lat, double lon, FloorplanCallback callback) {
        // POST body per API doc: { lat, lon, macs: ["bssid", ...] }
        JSONObject body = new JSONObject();
        try {
            body.put("lat", lat);
            body.put("lon", lon);
            body.put("macs", new JSONArray()); // minimal: no Wi-Fi list provided
        } catch (JSONException e) {
            callback.onError("Failed to build request body");
            return;
        }

        String userKey = BuildConfig.OPENPOSITIONING_API_KEY; // user key in path
        String masterKey = BuildConfig.OPENPOSITIONING_MASTER_KEY; // master key as query ?key=
        String path = (userKey == null || userKey.isEmpty()) ? endpoint : endpoint + "/" + userKey;
        String url = (masterKey == null || masterKey.isEmpty()) ? path : path + "?key=" + masterKey;

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Request failed", e);
                callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    callback.onError("HTTP " + response.code());
                    return;
                }
                String body = response.body() != null ? response.body().string() : "";
                try {
                    JSONArray arr = new JSONArray(body);
                    List<Venue> venues = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        String name = obj.optString("name", "Venue");
                        String outline = obj.optString("outline", "");
                        String mapShapes = obj.optString("map_shapes", "");
                        venues.add(new Venue(name, outline, mapShapes));
                    }
                    callback.onSuccess(venues);
                } catch (JSONException ex) {
                    Log.e(TAG, "Bad JSON", ex);
                    callback.onError("Parse error");
                }
            }
        });
    }

}
