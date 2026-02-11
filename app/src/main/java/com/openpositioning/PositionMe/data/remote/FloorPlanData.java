/**
 * FloorPlanData is responsible for communicating with the OpenPositioning
 * live floorplan API to retrieve nearby indoor venues.
 *
 * This class performs asynchronous HTTP POST requests using OkHttp,
 * sending the user's current latitude, longitude, and observed Wi-Fi
 * BSSID values to the server. The API response is parsed into
 * lightweight VenueDto objects containing:
 *   - Venue name (identifier)
 *   - Building outline (GeoJSON)
 *   - Floor map geometry (GeoJSON map_shapes)
 *
 */

package com.openpositioning.PositionMe.data.remote;
import com.openpositioning.PositionMe.BuildConfig;
import com.openpositioning.PositionMe.utils.IndoorMapManager;

import android.util.Log;

import androidx.annotation.NonNull;

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
 * Calls OpenPositioning floorplan API to fetch nearby indoor venues.
 * Networking + JSON parsing only. No map/UI code here.
 */
public class FloorPlanData{

     // Static constants necessary for communications
        private static final String API_KEY_PATH = BuildConfig.OPENPOSITIONING_API_KEY;
        private static final String MASTER_KEY = BuildConfig.OPENPOSITIONING_MASTER_KEY;
        private static final String BASE_URL = "https://openpositioning.org/api/live/floorplan/request/";




    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;

    public FloorPlanData(OkHttpClient client) {
        this.client = client;
    }

    /** DTO matching the API response fields */
    public static class VenueDto {
        public final String name;
        public final String outline;     // raw string from API
        public final String mapShapes;   // raw string from API

        public VenueDto(String name, String outline, String mapShapes) {
            this.name = name;
            this.outline = outline;
            this.mapShapes = mapShapes;
        }
    }

    public interface VenueCallback {
        void onSuccess(List<VenueDto> venues);
        void onError(Exception e);
    }

    public void requestNearbyVenues(
            double lat,
            double lon,
            @NonNull List<String> macs,
            @NonNull VenueCallback callback
    ) {
        String url = BASE_URL + API_KEY_PATH + "?key=" + MASTER_KEY;

        JSONObject body = new JSONObject();
        try {
            body.put("lat", lat);
            body.put("lon", lon);

            JSONArray macArr = new JSONArray();
            for (String m : macs) macArr.put(m);
            body.put("macs", macArr);

        } catch (JSONException e) {
            callback.onError(e);
            return;
        }

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        client.newCall(req).enqueue(new Callback() {

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e("FloorplanAPI", "Request failed", e);
                callback.onError(e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {

                Log.d("FloorplanAPI", "HTTP response code=" + response.code());

                if (!response.isSuccessful()) {
                    Log.e("FloorplanAPI", "Unsuccessful response");
                    callback.onError(new IOException("HTTP " + response.code()));
                    return;
                }

                String respBody = response.body() != null ? response.body().string() : "[]";
                Log.d("FloorplanAPI", "Raw response=" + respBody);

                try {
                    JSONArray arr = new JSONArray(respBody);
                    List<VenueDto> venues = new ArrayList<>();

                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject v = arr.getJSONObject(i);
                        String name = v.optString("name", "");
                        String outline = v.optString("outline", "");
                        String mapShapes = v.optString("map_shapes", "");
                        venues.add(new VenueDto(name, outline, mapShapes));
                    }

                    Log.d("FloorplanAPI", "Parsed venues=" + venues.size());
                    for (VenueDto v: venues) {
                        Log.d("IndoorDebug", "MapShapes raw=" + v.mapShapes);
                    }

                    callback.onSuccess(venues);

                } catch (JSONException e) {
                    Log.e("FloorplanAPI", "Bad JSON: " + respBody);
                    callback.onError(e);
                }
            }
        });

    }
}

