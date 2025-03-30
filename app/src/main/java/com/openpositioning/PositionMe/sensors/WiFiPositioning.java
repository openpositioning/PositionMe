package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.util.Log;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.maps.model.LatLng;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

import com.openpositioning.PositionMe.data.remote.ServerCommunications;

/**
 * Improved version: WiFi positioning with better duplicate detection.
 */
public class WiFiPositioning {
    private RequestQueue requestQueue;
    private static final String url = "https://openpositioning.org/api/position/fine";
    private LatLng wifiLocation;
    private int floor = 0;
    private JSONObject lastRequestedFingerprint = null;

    public LatLng getWifiLocation() {
        return wifiLocation;
    }

    public int getFloor() {
        return floor;
    }

    public WiFiPositioning(Context context) {
        this.requestQueue = Volley.newRequestQueue(context.getApplicationContext());
    }

    /**
     * Creates a POST request using the WiFi fingerprint to obtain user's location.
     * Includes smarter duplicate detection and error callbacks.
     *
     * @param jsonWifiFeatures WiFi Fingerprint
     * @param callback Result callback
     */
    public void request(JSONObject jsonWifiFeatures, final VolleyCallback callback) {
        // Smarter Duplicate detection
        if (isDuplicate(jsonWifiFeatures)) {
            callback.onError("Duplicate WiFi data (no significant change)");
            return;
        }

        lastRequestedFingerprint = jsonWifiFeatures;

        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST, url, jsonWifiFeatures,
                response -> {
                    try {
                        wifiLocation = new LatLng(response.getDouble("lat"), response.getDouble("lon"));
                        floor = response.getInt("floor");

                        if (response.has("error") || Math.abs(response.getDouble("lat")) < 0.0001) {
                            Log.w("WiFiPositioning", "❗️ No coverage or invalid location detected");
                            callback.onError("No coverage or invalid location");
                        } else {
                            callback.onSuccess(wifiLocation, floor);
                        }
                    } catch (JSONException e) {
                        Log.e("jsonErrors", "Error parsing response: " + e.getMessage() + " " + response);
                        callback.onError("Error parsing response: " + e.getMessage());
                    }
                },
                error -> {
                    if (error.networkResponse != null && error.networkResponse.statusCode == 422) {
                        Log.e("WiFiPositioning", "Validation Error " + error.getMessage());
                        callback.onError("Validation Error (422): " + error.getMessage());
                    } else if (error.networkResponse != null) {
                        Log.e("WiFiPositioning", "Response Code: " + error.networkResponse.statusCode + ", " + error.getMessage());
                        callback.onError("Response Code: " + error.networkResponse.statusCode + ", " + error.getMessage());
                    } else {
                        Log.e("WiFiPositioning", "Error message: " + error.getMessage());
                        callback.onError("Network Error: " + error.getMessage());
                    }
                }
        ) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("key", ServerCommunications.getUserKey());
                headers.put("Accept", "application/json");
                headers.put("Content-Type", "application/json");
                return headers;
            }
        };
        requestQueue.add(jsonObjectRequest);

    }

    /**
     * Checks if the new fingerprint is similar to the last one (within small RSSI changes).
     */
    private boolean isDuplicate(JSONObject newFingerprint) {
        if (lastRequestedFingerprint == null) return false;

        try {
            JSONObject last = lastRequestedFingerprint.getJSONObject("wf");
            JSONObject current = newFingerprint.getJSONObject("wf");

            if (last.length() != current.length()) return false;

            Iterator<String> keys = current.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!last.has(key)) return false;
                int lastRssi = last.getInt(key);
                int currRssi = current.getInt(key);
                if (Math.abs(lastRssi - currRssi) > 3) {
                    return false;
                }
            }
            return true;
        } catch (JSONException e) {
            return false;
        }
    }


    public interface VolleyCallback {
        void onSuccess(LatLng location, int floor);
        void onError(String message);
    }
}
