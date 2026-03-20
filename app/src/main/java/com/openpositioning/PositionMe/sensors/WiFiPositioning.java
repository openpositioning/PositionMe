package com.openpositioning.PositionMe.sensors;

import static com.openpositioning.PositionMe.utils.UtilConstants.API_POST_WIFI_FINE;
import static com.openpositioning.PositionMe.utils.UtilConstants.URL_API;

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
 * Class for creating and handling POST requests for obtaining the current position using WiFi
 * positioning API from OpenPositioning
 *
 * <p>The class creates POST requests based on WiFi fingerprints and obtains the user's location
 *
 * <p>The request are handled asynchronously using using {@link
 * WiFiPositioning#request(JSONObject)}. The WiFi position coordinates and floor are updated when
 * the response of the POST request is obtained.
 *
 * <p>TODO - This entire class should be replaced with using {@link
 * com.openpositioning.PositionMe.data.remote.ServerCommunications} for API calls
 *
 * @author Arun Gopalakrishnan
 */
public class WiFiPositioning {
    private static final String TAG = "WiFiPositioning";
    // Queue for storing the POST requests made
    private RequestQueue requestQueue;
    // URL for Wi-Fi positioning API
    private static final String URL_FINE = URL_API + API_POST_WIFI_FINE;

    /**
     * Getter for the WiFi positioning coordinates obtained using OpenPositioning API
     *
     * @return the user's coordinates based on OpenPositioning API
     */
    public LatLng getWifiLocation() {
        return wifiLocation;
    }

    // Store user's location obtained using WiFi positioning
    private LatLng wifiLocation;

    /**
     * Getter for the WiFi positioning floor obtained using OpenPositioning API
     *
     * @return the user's location based on OpenPositioning API
     */
    public int getFloor() {
        return floor;
    }

    // Store current floor of user, default value 0 (ground floor)
    private int floor = 0;

    /**
     * Constructor to create the WiFi positioning object
     *
     * <p>Initialising a request queue to handle the POST requests asynchronously
     *
     * @param context Context of object calling
     */
    public WiFiPositioning(Context context) {
        // Initialising the Request queue
        this.requestQueue = Volley.newRequestQueue(context.getApplicationContext());
    }

    /**
     * Creates a POST request using the WiFi fingerprint to obtain user's location. The POST request
     * is issued to the OpenPositioning API with the Wi-Fi fingerprint passed as the parameter.
     *
     * <p>The response of the post request returns the coordinates of the Wi-Fi position along with
     * the floor of the building the user is at though a callback.
     *
     * @param jsonWifiFeatures Wi-Fi Fingerprint from device
     */
    public void request(JSONObject jsonWifiFeatures) {
        // Creating the POST request using WiFi fingerprint (a JSON object)
        JsonObjectRequest jsonObjectRequest =
                new JsonObjectRequest(
                        Request.Method.POST,
                        URL_FINE,
                        jsonWifiFeatures,
                        // Parses the response to obtain the WiFi location and WiFi floor
                        response -> {
                            try {
                                wifiLocation =
                                        new LatLng(
                                                response.getDouble("lat"),
                                                response.getDouble("lon"));
                                floor = response.getInt("floor");
                            } catch (JSONException e) {
                                // Error log to keep record of errors (for secure programming and
                                // maintainability)
                                Log.e(
                                        TAG,
                                        "Error parsing response: "
                                                + e.getMessage()
                                                + " "
                                                + response);
                            }
                        },
                        error -> {
                            if (error.networkResponse != null) {
                                Log.e(
                                        TAG,
                                        "Error Response: HTTP "
                                                + error.networkResponse.statusCode
                                                + ", "
                                                + error.getMessage());
                            } else {
                                Log.e(TAG, "Error message: " + error.getMessage());
                            }
                        });
        // Adds the request to the request queue
        requestQueue.add(jsonObjectRequest);
    }

    /**
     * Creates a POST request using the WiFi fingerprint to obtain user's location. The POST request
     * is issued to the OpenPositioning API with the Wi-Fi fingerprint passed as the parameter.
     *
     * <p>The response of the post request returns the coordinates of the Wi-Fi position along with
     * the floor of the building the user is at though a callback.
     *
     * @param jsonWifiFeatures Wi-Fi Fingerprint from device
     * @param callback callback to allow use of location when ready
     */
    public void request(JSONObject jsonWifiFeatures, VolleyCallback callback) {

        // Creating the POST request using WiFi fingerprint (a JSON object)
        JsonObjectRequest jsonObjectRequest =
                new JsonObjectRequest(
                        Request.Method.POST,
                        URL_FINE,
                        jsonWifiFeatures,
                        response -> {
                            try {
                                Log.d(TAG, "Response: " + response.toString());

                                wifiLocation =
                                        new LatLng(
                                                response.getDouble("lat"),
                                                response.getDouble("lon"));

                                floor = response.getInt("floor");
                                callback.onSuccess(wifiLocation, floor);

                            } catch (JSONException e) {
                                String errorMessage =
                                        "Error parsing response: "
                                                + e.getMessage()
                                                + " ("
                                                + response
                                                + ")";
                                Log.e(TAG, errorMessage);
                                callback.onError(errorMessage);
                            }
                        },
                        error -> {
                            if (error.networkResponse != null) {

                                int statusCode = error.networkResponse.statusCode;
                                String responseBody = "";

                                try {
                                    if (error.networkResponse.data != null) {
                                        responseBody =
                                                new String(error.networkResponse.data, "UTF-8");
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Error reading response body: " + e.getMessage());
                                }
                                String errorBody =
                                        "HTTP " + statusCode + " Response Body: " + responseBody;
                                Log.e(TAG, errorBody);
                                callback.onError(errorBody);

                            } else {
                                String errorBody = "Network error: " + error.getMessage();
                                Log.e(TAG, errorBody);
                                callback.onError(errorBody);
                            }
                        });

        // Adds the request to the request queue
        requestQueue.add(jsonObjectRequest);
    }

    /** Interface defined for the callback to access response obtained after POST request */
    public interface VolleyCallback {
        void onSuccess(LatLng location, int floor);

        void onError(String message);
    }
}
