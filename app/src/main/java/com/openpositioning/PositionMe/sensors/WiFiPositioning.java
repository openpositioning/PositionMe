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
import java.util.Map;
// Class for creating and handling POST requests for obtaining the current position using
// WiFi positioning API from https://openpositioning.org/api/position/fine
// The class creates POST requests based on WiFi fingerprints and obtains the user's location
// The request are handled asynchronously, The WiFi position coordinates and floor are updated
// when the response of the POST request is obtained.
// One can create a POST request using the function provided in the class (createPostRequest()) with
// the WiFi fingerprint
// Its then added to the RequestQueue to be handled asynchronously (not blocking the main thread)
// When the response to the request is obtained the wifiLocation and floor are updated.
// Calling the getters for wifiLocation and the floor allows obtaining the WiFi location and floor
// from the POST request response.
// @author Arun Gopalakrishnan
public class WiFiPositioning {
    // Queue for storing the POST requests made
    private RequestQueue requestQueue;
    // URL for WiFi positioning API
    private static final String url="https://openpositioning.org/api/position/fine";
    // Optional API auth token (supports app-level auth over openpositioning API)
    private String apiAuthToken = null;
    private static final String FALLBACK_API_AUTH_TOKEN = "NO_AUTH"; // Demo value; can be loaded from secrets.properties.

    // Getter for the WiFi positioning coordinates obtained using openpositioning API
// Returns: the user's coordinates based on openpositioning API
    public LatLng getWifiLocation() {
        return wifiLocation;
    }

    // Store user's location obtained using WiFi positioning
    private LatLng wifiLocation;
    // Getter for the WiFi positioning floor obtained using openpositioning API
// Returns: the user's location based on openpositioning API
    public int getFloor() {
        return floor;
    }

    // Store current floor of user, default value 0 (ground floor)
    private int floor=0;


    // Constructor to create the WiFi positioning object
    // Initialising a request queue to handle the POST requests asynchronously
// Parameter context: Context of object calling
    public WiFiPositioning(Context context){
        // Initialising the Request queue
        this.requestQueue = Volley.newRequestQueue(context.getApplicationContext());
    }

    // Creates a POST request using the WiFi fingerprint to obtain user's location
    // The POST request is issued to https://openpositioning.org/api/position/fine
    // (the openpositioning API) with the WiFI fingerprint passed as the parameter.
    // The response of the post request returns the coordinates of the WiFi position
    // along with the floor of the building the user is at.
    // A try and catch block along with error Logs have been added to keep a record of error's
    // obtained while handling POST requests (for better maintainability and secure programming)
// Parameter jsonWifiFeatures: WiFi Fingerprint from device
    public void setApiAuthToken(String token) {
        if (token != null && !token.trim().isEmpty()) {
            this.apiAuthToken = token;
        }
    }

    // Returns a valid token if provided or fallback
    private String getEffectiveApiAuthToken() {
        return (apiAuthToken != null && !apiAuthToken.isEmpty()) ? apiAuthToken : FALLBACK_API_AUTH_TOKEN;
    }

    // Creates a POST request using the WiFi fingerprint to obtain user's location
    public void request(JSONObject jsonWifiFeatures) {
        executeRequest(jsonWifiFeatures, null);
    }

    private void executeRequest(JSONObject jsonWifiFeatures, VolleyCallback callback) {
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST, url, jsonWifiFeatures,
                response -> {
                    try {
                        wifiLocation = new LatLng(response.getDouble("lat"), response.getDouble("lon"));
                        floor = response.getInt("floor");
                        if (callback != null) callback.onSuccess(wifiLocation, floor);
                    } catch (JSONException e) {
                        Log.e("WiFiPositioning", "Parse error: " + e.getMessage() + " -- " + response);
                        if (callback != null) callback.onError("Error parsing response: " + e.getMessage());
                    }
                },
                error -> {
                    if (error.networkResponse != null && error.networkResponse.statusCode == 422) {
                        Log.e("WiFiPositioning", "Validation Error " + error.getMessage());
                        if (callback != null) callback.onError("Validation Error (422): " + error.getMessage());
                    } else if (error.networkResponse != null && error.networkResponse.statusCode == 401) {
                        Log.e("WiFiPositioning", "Unauthorized - API auth required");
                        if (callback != null) callback.onError("Unauthorized (401) - check API token");
                    } else {
                        if (error.networkResponse != null) {
                            Log.e("WiFiPositioning", "Response Code: " + error.networkResponse.statusCode + ", " + error.getMessage());
                            if (callback != null) callback.onError("Response Code: " + error.networkResponse.statusCode + ", " + error.getMessage());
                        } else {
                            Log.e("WiFiPositioning", "Error message: " + error.getMessage());
                            if (callback != null) callback.onError("Error message: " + error.getMessage());
                        }
                    }
                }) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");
                if (getEffectiveApiAuthToken() != null && !FALLBACK_API_AUTH_TOKEN.equals(getEffectiveApiAuthToken())) {
                    headers.put("Authorization", "Bearer " + getEffectiveApiAuthToken());
                }
                return headers;
            }
        };

        jsonObjectRequest.setRetryPolicy(new com.android.volley.DefaultRetryPolicy(
                6000,
                2,
                com.android.volley.DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        ));

        requestQueue.add(jsonObjectRequest);
    }



    // Creates a POST request using the WiFi fingerprint to obtain user's location
    // The POST request is issued to https://openpositioning.org/api/position/fine
    // (the openpositioning API) with the WiFI fingerprint passed as the parameter.
    // The response of the post request returns the coordinates of the WiFi position
    // along with the floor of the building the user is at though a callback.
    // A try and catch block along with error Logs have been added to keep a record of error's
    // obtained while handling POST requests (for better maintainability and secure programming)
// Parameter jsonWifiFeatures: WiFi Fingerprint from device
// Parameter callback: callback function to allow user to use location when ready
    public void request(JSONObject jsonWifiFeatures, final VolleyCallback callback) {
        executeRequest(jsonWifiFeatures, callback);
    }

    // Interface defined for the callback to access response obtained after POST request
    public interface VolleyCallback {
        void onSuccess(LatLng location, int floor);
        void onError(String message);
    }

}

