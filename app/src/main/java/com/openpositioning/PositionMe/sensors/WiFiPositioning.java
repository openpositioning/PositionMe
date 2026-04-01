package com.openpositioning.PositionMe.sensors;

import static com.openpositioning.PositionMe.BuildConstants.DEBUG;

import android.content.Context;
import android.util.Log;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.maps.model.LatLng;

import org.json.JSONException;
import org.json.JSONObject;
/**
 * Class for creating and handling POST requests for obtaining the current position using
 * WiFi positioning API from https://openpositioning.org/api/position/fine
 *
 * The class creates POST requests based on WiFi fingerprints and obtains the user's location
 *
 * The request are handled asynchronously, The WiFi position coordinates and floor are updated
 * when the response of the POST request is obtained.
 *
 * One can create a POST request using the function provided in the class (createPostRequest()) with
 * the WiFi fingerprint
 * Its then added to the RequestQueue to be handled asynchronously (not blocking the main thread)
 * When the response to the request is obtained the wifiLocation and floor are updated.
 * Calling the getters for wifiLocation and the floor allows obtaining the WiFi location and floor
 * from the POST request response.
 * @author Arun Gopalakrishnan
 */
public class WiFiPositioning {
    private RequestQueue requestQueue;
    private static final String url = "https://openpositioning.org/api/position/fine";

    /**
     * Getter for the WiFi positioning coordinates obtained using openpositioning API
     * @return the user's coordinates based on openpositioning API
     */
    public LatLng getWifiLocation() {
        return wifiLocation;
    }

    private LatLng wifiLocation;
    /**
     * Getter for the  WiFi positioning floor obtained using openpositioning API
     * @return the user's location based on openpositioning API
     */
    public int getFloor() {
        return floor;
    }

    /** Current floor; -1 means not yet determined. */
    private int floor = -1;


    /**
     * Creates a WiFiPositioning instance with an async request queue.
     *
     * @param context application or activity context
     */
    public WiFiPositioning(Context context) {
        this.requestQueue = Volley.newRequestQueue(context.getApplicationContext());
    }

    /**
     * Sends a WiFi fingerprint to the positioning API and updates the stored location/floor.
     *
     * @param jsonWifiFeatures WiFi fingerprint JSON from device
     */
    public void request(JSONObject jsonWifiFeatures) {
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST, url, jsonWifiFeatures,
                response -> {
                    try {
                        wifiLocation = new LatLng(response.getDouble("lat"), response.getDouble("lon"));
                        floor = response.getInt("floor");
                    } catch (JSONException e) {
                        Log.e("jsonErrors", "Error parsing response: " + e.getMessage() + " " + response);
                    }
                },
                error -> {
                    if (error.networkResponse != null && error.networkResponse.statusCode == 422) {
                        Log.e("WiFiPositioning", "Validation Error " + error.getMessage());
                    } else {
                        if (error.networkResponse != null) {
                            Log.e("WiFiPositioning", "Response Code: " + error.networkResponse.statusCode + ", " + error.getMessage());
                        } else {
                            Log.e("WiFiPositioning", "Error message: " + error.getMessage());
                        }
                    }
                }
        );
        requestQueue.add(jsonObjectRequest);
    }


    /**
     * Sends a WiFi fingerprint to the positioning API with an async callback for the result.
     *
     * @param jsonWifiFeatures WiFi fingerprint JSON from device
     * @param callback         callback invoked on success or error
     */
    public void request(JSONObject jsonWifiFeatures, final VolleyCallback callback) {
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST, url, jsonWifiFeatures,
                response -> {
                    try {
                        if (DEBUG) Log.d("jsonObject", response.toString());
                        wifiLocation = new LatLng(response.getDouble("lat"), response.getDouble("lon"));
                        floor = response.getInt("floor");
                        callback.onSuccess(wifiLocation, floor);
                    } catch (JSONException e) {
                        Log.e("jsonErrors", "Error parsing response: " + e.getMessage() + " " + response);
                        callback.onError("Error parsing response: " + e.getMessage());
                    }
                },
                error -> {
                    if (error.networkResponse != null && error.networkResponse.statusCode == 422) {
                        Log.e("WiFiPositioning", "Validation Error " + error.getMessage());
                        callback.onError("Validation Error (422): " + error.getMessage());
                    } else {
                        if (error.networkResponse != null) {
                            String body = "";
                            try { body = new String(error.networkResponse.data, "UTF-8"); }
                            catch (Exception ignored) {}
                            Log.e("WiFiPositioning", "Response Code: " + error.networkResponse.statusCode
                                    + ", body: " + body);
                            callback.onError("Response Code: " + error.networkResponse.statusCode + ", " + error.getMessage());
                        } else {
                            Log.e("WiFiPositioning", "Error message: " + error.getMessage());
                            callback.onError("Error message: " + error.getMessage());
                        }
                    }
                }
        );
        requestQueue.add(jsonObjectRequest);
    }

    /** Callback interface for asynchronous WiFi positioning responses. */
    public interface VolleyCallback {
        /**
         * Called when the positioning API returns a valid location.
         *
         * @param location the resolved WiFi position
         * @param floor    the resolved floor number
         */
        void onSuccess(LatLng location, int floor);

        /**
         * Called when the positioning request fails.
         *
         * @param message human-readable error description
         */
        void onError(String message);
    }

}