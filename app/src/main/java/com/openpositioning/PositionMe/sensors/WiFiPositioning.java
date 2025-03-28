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

import java.util.Objects;

/**
 * Class for creating and handling POST requests for obtaining the current position using
 * WiFi positioning API from https://openpositioning.org/api/position/fine
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
     * Creates a POST request using the WiFi fingerprint to obtain user's location
     * Includes duplicate detection, outlier detection, and error callbacks
     *
     * @param jsonWifiFeatures WiFi Fingerprint
     * @param callback Result callback
     */
    public void request(JSONObject jsonWifiFeatures, final VolleyCallback callback) {
        // Duplicate detection
        if (jsonWifiFeatures != null && jsonWifiFeatures.toString().equals(String.valueOf(lastRequestedFingerprint))) {
            Log.w("WiFiPositioning", "⚠️ Duplicate WiFi fingerprint detected, skipping request");
            callback.onError("Duplicate WiFi data (throttling likely)");
            return;
        }

        lastRequestedFingerprint = jsonWifiFeatures;

        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST, url, jsonWifiFeatures,
                response -> {
                    try {
                        wifiLocation = new LatLng(response.getDouble("lat"), response.getDouble("lon"));
                        floor = response.getInt("floor");

                        // Outlier / No coverage detection
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
        );
        requestQueue.add(jsonObjectRequest);
    }

    public interface VolleyCallback {
        void onSuccess(LatLng location, int floor);
        void onError(String message);
    }
}
