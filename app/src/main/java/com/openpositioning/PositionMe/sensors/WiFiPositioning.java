package com.openpositioning.PositionMe.sensors;
import android.content.Context;
import android.util.Log;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.VolleyError;
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
    // Queue for storing the POST requests made
    private RequestQueue requestQueue;
    // Try fine first, then fallback to coarse when fine cannot localize this fingerprint.
    private static final String[] URL_CANDIDATES = new String[] {
            "https://openpositioning.org/api/position/fine",
            "https://openpositioning.org/api/position/coarse"
    };

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
        requestWithRetry(jsonWifiFeatures, 0, null);
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
        requestWithRetry(jsonWifiFeatures, 0, callback);
    }

    private void requestWithRetry(JSONObject jsonWifiFeatures,
                                  int urlIndex,
                                  final VolleyCallback callback) {
        if (urlIndex >= URL_CANDIDATES.length) {
            String message = "WiFi positioning failed for all URL candidates";
            Log.e("WiFiPositioning", message);
            if (callback != null) {
                callback.onError(message);
            }
            return;
        }

        final String url = URL_CANDIDATES[urlIndex];
        Log.d("WiFiPositioning", "Request URL=" + url);
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
            Request.Method.POST, url, jsonWifiFeatures,
                response -> {
                    try {
                        Log.d("WiFiPositioning", "Response URL=" + url + " body=" + response);
                        wifiLocation = new LatLng(response.getDouble("lat"), response.getDouble("lon"));
                        floor = response.getInt("floor");
                        if (callback != null) {
                            callback.onSuccess(wifiLocation, floor);
                        }
                    } catch (JSONException e) {
                        String msg = "Error parsing response from " + url + ": " + e.getMessage();
                        Log.e("WiFiPositioning", msg + " " + response);
                        if (callback != null) {
                            callback.onError(msg);
                        }
                    }
                },
                error -> {
                    int code = error.networkResponse != null ? error.networkResponse.statusCode : -1;
                    String responseBody = extractErrorBody(error);
                    Log.e("WiFiPositioning",
                            "Request failed URL=" + url
                                    + " code=" + code
                                    + " message=" + error.getMessage()
                                    + " body=" + responseBody);

                    if (code == 404 && responseBody.contains("Position unknown")) {
                        // Endpoint is reachable, but this fingerprint is not known at current granularity.
                        // Fallback from fine -> coarse if available.
                        if (urlIndex + 1 < URL_CANDIDATES.length) {
                            requestWithRetry(jsonWifiFeatures, urlIndex + 1, callback);
                            return;
                        }
                        if (callback != null) {
                            callback.onError("Position unknown");
                        }
                        return;
                    }

                    if (code == 404 && urlIndex + 1 < URL_CANDIDATES.length) {
                        requestWithRetry(jsonWifiFeatures, urlIndex + 1, callback);
                        return;
                    }

                    if (callback != null) {
                        callback.onError("Response Code: " + code + ", "
                                + error.getMessage() + ", body=" + responseBody);
                    }
                }
        );
        requestQueue.add(jsonObjectRequest);
    }

    private String extractErrorBody(VolleyError error) {
        if (error == null || error.networkResponse == null || error.networkResponse.data == null) {
            return "";
        }
        try {
            return new String(error.networkResponse.data, "UTF-8");
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Interface defined for the callback to access response obtained after POST request
     */
    public interface VolleyCallback {
        void onSuccess(LatLng location, int floor);
        void onError(String message);
    }

}