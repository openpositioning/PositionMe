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
    private static final String TAG = "WIFI_POS_DEBUG";

    // Queue for storing the POST requests made
    private RequestQueue requestQueue;
    // URL for WiFi positioning API
    private static final String url="https://openpositioning.org/api/position/fine";

    /**
     * Getter for the WiFi positioning coordinates obtained using openpositioning API
     * @return the user's coordinates based on openpositioning API
     */
    public LatLng getWifiLocation() {
        return wifiLocation;
    }

    // Store user's location obtained using WiFi positioning
    private LatLng wifiLocation;
    /**
     * Getter for the  WiFi positioning floor obtained using openpositioning API
     * @return the user's location based on openpositioning API
     */
    public int getFloor() {
        return floor;
    }

    // Store current floor of user, default value 0 (ground floor)
    private int floor=0;


    /**
     * Constructor to create the WiFi positioning object
     *
     * Initialising a request queue to handle the POST requests asynchronously
     *
     * @param context Context of object calling
     */
    public WiFiPositioning(Context context){
        // Initialising the Request queue
        this.requestQueue = Volley.newRequestQueue(context.getApplicationContext());
    }

    /**
     * Creates a POST request using the WiFi fingerprint to obtain user's location
     * The POST request is issued to https://openpositioning.org/api/position/fine
     * (the openpositioning API) with the WiFI fingerprint passed as the parameter.
     *
     * The response of the post request returns the coordinates of the WiFi position
     * along with the floor of the building the user is at.
     *
     * A try and catch block along with error Logs have been added to keep a record of error's
     * obtained while handling POST requests (for better maintainability and secure programming)
     *
     * @param jsonWifiFeatures WiFi Fingerprint from device
     */
    public void request(JSONObject jsonWifiFeatures) {
        Log.e(TAG, "=== WiFi Position Request (no callback) ===");
        Log.e(TAG, "Request URL: " + url);
        Log.e(TAG, "Fingerprint payload: " + jsonWifiFeatures.toString());
        long requestTime = System.currentTimeMillis();

        // Creating the POST request using WiFi fingerprint (a JSON object)
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST, url, jsonWifiFeatures,
                // Parses the response to obtain the WiFi location and WiFi floor
                response -> {
                    long responseTime = System.currentTimeMillis() - requestTime;
                    Log.e(TAG, "WiFi API response received in " + responseTime + "ms");
                    Log.e(TAG, "Raw response: " + response.toString());
                    try {
                            double lat = response.getDouble("lat");
                            double lon = response.getDouble("lon");
                            int floorVal = response.getInt("floor");
                            wifiLocation = new LatLng(lat, lon);
                            floor = floorVal;
                            Log.e(TAG, "WiFi position: lat=" + lat + " lon=" + lon + " floor=" + floorVal);

                            // Check distance from Nucleus building center
                            double distFromNucleus = Math.sqrt(
                                    Math.pow((lat - 55.9230) * 111111, 2)
                                    + Math.pow((lon - (-3.1743)) * 111111 * Math.cos(Math.toRadians(55.9230)), 2));
                            Log.e(TAG, "WiFi pos distance from Nucleus: " + String.format("%.1f", distFromNucleus) + "m");
                            if (distFromNucleus > 500) {
                                Log.e(TAG, "WARNING: WiFi position is far from building! Possible bad fingerprint");
                            }
                    } catch (JSONException e) {
                        Log.e(TAG,"Error parsing response: "+e.getMessage()+" "+ response);
                    }
                },
                // Handles the errors obtained from the POST request
                error -> {
                    long responseTime = System.currentTimeMillis() - requestTime;
                    Log.e(TAG, "WiFi API ERROR after " + responseTime + "ms");
                    // Validation Error
                    if (error.networkResponse!=null && error.networkResponse.statusCode==422){
                        Log.e(TAG, "Validation Error (422): " + error.getMessage());
                        if (error.networkResponse.data != null) {
                            Log.e(TAG, "Response body: " + new String(error.networkResponse.data));
                        }
                    }
                    // Other Errors
                    else{
                        if (error.networkResponse!=null) {
                            Log.e(TAG,"HTTP " + error.networkResponse.statusCode + ": " + error.getMessage());
                            if (error.networkResponse.data != null) {
                                Log.e(TAG, "Response body: " + new String(error.networkResponse.data));
                            }
                        }
                        else{
                            Log.e(TAG,"Network error (no response): " + error.getMessage()
                                    + " | Cause: " + (error.getCause() != null ? error.getCause().toString() : "null"));
                        }
                    }
                }
        );
        // Adds the request to the request queue
        requestQueue.add(jsonObjectRequest);
    }


    /**
     * Creates a POST request using the WiFi fingerprint to obtain user's location
     * The POST request is issued to https://openpositioning.org/api/position/fine
     * (the openpositioning API) with the WiFI fingerprint passed as the parameter.
     *
     * The response of the post request returns the coordinates of the WiFi position
     * along with the floor of the building the user is at though a callback.
     *
     * A try and catch block along with error Logs have been added to keep a record of error's
     * obtained while handling POST requests (for better maintainability and secure programming)
     *
     * @param jsonWifiFeatures WiFi Fingerprint from device
     * @param callback callback function to allow user to use location when ready
     */
    public void request( JSONObject jsonWifiFeatures, final VolleyCallback callback) {
        Log.e(TAG, "=== WiFi Position Request (with callback) ===");
        Log.e(TAG, "Fingerprint payload: " + jsonWifiFeatures.toString());
        long requestTime = System.currentTimeMillis();

        // Creating the POST request using WiFi fingerprint (a JSON object)
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST, url, jsonWifiFeatures,
                response -> {
                    long responseTime = System.currentTimeMillis() - requestTime;
                    Log.e(TAG, "WiFi API (callback) response in " + responseTime + "ms: " + response.toString());
                    try {
                        double lat = response.getDouble("lat");
                        double lon = response.getDouble("lon");
                        int floorVal = response.getInt("floor");
                        wifiLocation = new LatLng(lat, lon);
                        floor = floorVal;
                        Log.e(TAG, "WiFi position (callback): lat=" + lat + " lon=" + lon + " floor=" + floorVal);
                        callback.onSuccess(wifiLocation, floor);
                    } catch (JSONException e) {
                        Log.e(TAG,"Error parsing response: "+e.getMessage()+" "+ response);
                        callback.onError("Error parsing response: " + e.getMessage());
                    }
                },
                error -> {
                    long responseTime = System.currentTimeMillis() - requestTime;
                    int statusCode = (error.networkResponse != null)
                            ? error.networkResponse.statusCode : -1;
                    Log.e(TAG, "WiFi API (callback) ERROR after " + responseTime
                            + "ms, HTTP " + statusCode + ": " + error.getMessage());
                    if (error.networkResponse != null && error.networkResponse.data != null) {
                        Log.e(TAG, "Response body: " + new String(error.networkResponse.data));
                    }
                    // Pass status code in error message so caller can detect 404
                    callback.onError("HTTP " + statusCode + ": " + error.getMessage());
                }
        );
        // Adds the request to the request queue
        requestQueue.add(jsonObjectRequest);
    }

    /**
     * Interface defined for the callback to access response obtained after POST request
     */
    public interface VolleyCallback {
        void onSuccess(LatLng location, int floor);
        void onError(String message);
    }

}