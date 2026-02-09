package com.openpositioning.PositionMe.data.remote;

import android.util.Log;

import com.openpositioning.PositionMe.BuildConfig;
import com.openpositioning.PositionMe.data.remote.model.FloorplanVenue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
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
 * API client used to request nearby floorplans and parse response payloads.
 */
public class FloorplanService {
    private static final String TAG = "FloorplanService";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final String USER_KEY = BuildConfig.OPENPOSITIONING_API_KEY;
    private static final String MASTER_KEY = BuildConfig.OPENPOSITIONING_MASTER_KEY;

    private final OkHttpClient client;

    public interface FloorplanCallback {
        void onSuccess(List<FloorplanVenue> venues);
        void onError(String errorMessage);
    }

    public FloorplanService() {
        this.client = new OkHttpClient();
    }

    public void requestNearbyFloorplans(double lat,
                                        double lon,
                                        List<Long> macs,
                                        FloorplanCallback callback) {
        int macCount = macs == null ? 0 : macs.size();
        Log.d(TAG, "requestNearbyFloorplans lat=" + lat + ", lon=" + lon + ", macCount=" + macCount);
        JSONObject requestJson = new JSONObject();
        try {
            requestJson.put("lat", lat);
            requestJson.put("lon", lon);
            JSONArray macArray = new JSONArray();
            if (macs != null) {
                for (Long mac : macs) {
                    if (mac != null) {
                        macArray.put(mac);
                    }
                }
            }
            requestJson.put("macs", macArray);
        } catch (JSONException e) {
            callback.onError("Failed to build floorplan request payload: " + e.getMessage());
            return;
        }

        String url = "https://openpositioning.org/api/live/floorplan/request/"
                + USER_KEY + "?key=" + MASTER_KEY;

        RequestBody body = RequestBody.create(requestJson.toString(), JSON_MEDIA_TYPE);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Floorplan request failed", e);
                callback.onError("Floorplan request failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    String bodyText = responseBody != null ? responseBody.string() : "";
                    Log.d(TAG, "Floorplan response code=" + response.code() + ", bodyLength=" + bodyText.length());
                    if (!response.isSuccessful()) {
                        callback.onError("Floorplan request failed (" + response.code() + "): " + bodyText);
                        return;
                    }

                    try {
                        List<FloorplanVenue> venues = parseFloorplanResponse(bodyText);
                        Log.d(TAG, "Parsed venues count=" + venues.size());
                        for (FloorplanVenue venue : venues) {
                            Log.d(TAG, "Venue parsed campaign=" + venue.getCampaign()
                                    + ", outlinePoints=" + venue.getOutline().size()
                                    + ", levels=" + venue.getLevels().size());
                        }
                        callback.onSuccess(venues);
                    } catch (JSONException e) {
                        callback.onError("Failed to parse floorplan response: " + e.getMessage());
                    }
                }
            }
        });
    }

    static List<FloorplanVenue> parseFloorplanResponse(String responseBody) throws JSONException {
        return FloorplanResponseParser.parseFloorplanResponse(responseBody);
    }
}
