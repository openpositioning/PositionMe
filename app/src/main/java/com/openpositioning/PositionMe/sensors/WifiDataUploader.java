package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.os.Looper;
import android.util.Log;
import com.google.android.gms.maps.model.LatLng;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import android.os.Handler;
//import java.util.logging.Handler;
import java.util.stream.Collectors;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

public class WifiDataUploader {

    public interface WifiDataUploadCallback {
        void onUploadComplete(LatLng latLng, int floor);
    }

    private static final String SERVER_URL = "https://openpositioning.org/api/position/fine"; // URL of the server
    // Callback interface to handle upload completion and failure
    public interface WifiDataCallback {
        void onUploadComplete(LatLng latLng);
        void onUploadFailure(Exception e);
    }

    private static WifiDataUploadCallback uploadCallback;

    // Constructor that sets the callback object
    public WifiDataUploader(WifiDataUploadCallback callback) {
        this.uploadCallback = callback;
    }


    // Method to upload a list of WiFi data as JSON
    public static void uploadWifiList(List<Wifi> wifiList) {
        // Check if the wifiList is not null to prevent errors.
        if (wifiList != null) {
            try {
                JSONObject jsonWifiData = new JSONObject(); // JSONObject to hold WiFi data
                JSONObject jsonWifiPrepared = new JSONObject(); // Final JSONObject to be sent to the server

                // Convert the list of WiFi data into a JSON object
                for (Wifi wifi : wifiList) {
                    String macAddress = String.valueOf(wifi.getBssid()); // Extract the MAC address of the Wi-Fi point.
                    int signalStrength = wifi.getLevel(); // Extract the signal strength of the Wi-Fi point.
                    jsonWifiData.put(macAddress, signalStrength); // Add MAC address and signal strength to jsonWifiData
                }

                jsonWifiPrepared.put("wf", jsonWifiData); // Embed jsonWifiData in jsonWifiPrepared under "wf" key

                // Perform upload on a separate thread to avoid blocking the UI thread
                new Thread(() -> {
                    try {
                        LatLng latLng = sendData(jsonWifiPrepared.toString()); // Upload the JSON data
                    } catch (Exception e) {
                        Log.e("WifiDataUploader", "Failed to upload Wi-Fi data", e); // Log an error if the upload fails.
                    }
                }).start();
            } catch (JSONException e) {
                Log.e("WifiDataUploader", "JSON Exception", e); // Log an error if there is a problem creating the JSON objects.
            }
        }
    }

    // Method to send data using HTTP POST
    private static LatLng sendData(String jsonData) throws Exception {
        WifiDataUploader.uploadCallback = uploadCallback;
        StringBuilder response = new StringBuilder(); // StringBuilder to collect the server's response.
        URL url = new URL(SERVER_URL); // Create a URL object from the server's URL.
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(); // Open a connection to the server.
        conn.setRequestMethod("POST"); // Set the request method to POST.
        conn.setRequestProperty("Content-Type", "application/json"); // Set the header indicating the body contains JSON data.
        conn.setRequestProperty("accept", "application/json"); // Set the header indicating that the server's response will be expected in JSON.
        conn.setDoOutput(true); // Enable writing output to the connection.

        // Send JSON data
        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonData.getBytes("utf-8"); // Convert the JSON string to bytes in UTF-8 encoding.
            os.write(input, 0, input.length); // Write the JSON data to the connection output stream.
        }

        // Check response code
        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            // If HTTP response code is OK, handle the successful server response.
            Log.i("WifiDataUploader", "Wi-Fi data uploaded successfully.");
            try (InputStream is = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            JSONObject responseObject = new JSONObject(response.toString()); // This variable must be declared in the scope
            Log.i("WifiDataUploader", "Response from server: " + response.toString());

            // JSON contains latitude and longitude fields
            double latitude = responseObject.getDouble("lat"); // Extract latitude from the JSON response.
            double longitude = responseObject.getDouble("lon"); // Extract longitude from the JSON response.
            int floor = responseObject.getInt("floor"); // Extract floor from the JSON response.
            LatLng NewLatLng = new LatLng(latitude, longitude); // Create a LatLng object from the latitude and longitude
            conn.disconnect();
            if (uploadCallback != null) {
                //uploadCallback.onUploadComplete(NewLatLng);
                new Handler(Looper.getMainLooper()).post(() -> {
                    uploadCallback.onUploadComplete(new LatLng(latitude, longitude), floor);
                });
            }
            conn.disconnect(); // Disconnect the HTTP connection.
            return NewLatLng;  // Return the new LatLng position.
        } else {
            // Log an error if the upload was not successful.
            Log.e("WifiDataUploader", "Failed to upload Wi-Fi data, HTTP response code: " + responseCode); //
            conn.disconnect(); // Disconnect the HTTP connection.
            return null;       // Return null to indicate failure.
        }
    }

}
