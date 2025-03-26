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
 * It sends a WiFi fingerprint (JSON) and updates the WiFi location and floor when a response is obtained.
 * The request is handled asynchronously using Volley. Two versions of request() are provided,
 * one with a VolleyCallback to allow fine positioning handling.
 *
 * Additionally, displayPosition() is added to show the obtained position.
 *
 * @author ...
 */
public class WiFiPositioning {
    // Queue for handling POST requests
    private RequestQueue requestQueue;
    // URL for WiFi positioning API
    private static final String url = "https://openpositioning.org/api/position/fine";

    // Store user's location obtained via WiFi positioning
    private LatLng wifiLocation;
    // Store current floor, default 0 (ground floor)
    private int floor = 0;

    /**
     * Getter for WiFi positioning coordinates.
     */
    public LatLng getWifiLocation() {
        return wifiLocation;
    }

    /**
     * Getter for the floor.
     */
    public int getFloor() {
        return floor;
    }

    /**
     * Constructor to initialize the WiFi positioning object.
     */
    public WiFiPositioning(Context context){
        this.requestQueue = Volley.newRequestQueue(context.getApplicationContext());
    }

    /**
     * Creates a POST request using the WiFi fingerprint JSON to obtain the user's location.
     * The response returns coordinates and floor.
     */
    public void request(JSONObject jsonWifiFeatures) {
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST, url, jsonWifiFeatures,
                response -> {
                    try {
                        wifiLocation = new LatLng(response.getDouble("lat"), response.getDouble("lon"));
                        floor = response.getInt("floor");
                        // 调用位置显示方法
                        displayPosition();
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
     * Creates a POST request using the WiFi fingerprint JSON with a callback to handle the response.
     */
    public void request(JSONObject jsonWifiFeatures, final VolleyCallback callback) {
        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(
                Request.Method.POST, url, jsonWifiFeatures,
                response -> {
                    try {
                        Log.d("jsonObject", response.toString());
                        wifiLocation = new LatLng(response.getDouble("lat"), response.getDouble("lon"));
                        floor = response.getInt("floor");
                        callback.onSuccess(wifiLocation, floor);
                        // 同时显示定位结果
                        displayPosition();
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
                            Log.e("WiFiPositioning", "Response Code: " + error.networkResponse.statusCode + ", " + error.getMessage());
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

    /**
     * 新增方法：显示定位结果
     * 这里使用 Log 打印，实际应用中可扩展为更新 UI 组件。
     */
    public void displayPosition() {
        if (wifiLocation != null) {
            Log.d("WiFiPositioning", "WiFi Position: (" + wifiLocation.latitude + ", " + wifiLocation.longitude + "), Floor: " + floor);
        } else {
            Log.d("WiFiPositioning", "WiFi Position not available");
        }
    }

    /**
     * Callback 接口定义，用于处理 POST 请求返回的定位数据。
     */
    public interface VolleyCallback {
        void onSuccess(LatLng location, int floor);
        void onError(String message);
    }
}
