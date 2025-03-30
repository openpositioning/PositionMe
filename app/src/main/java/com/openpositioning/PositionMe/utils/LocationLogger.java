package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LocationLogger {
    private static final String TAG = "LocationLogger";
    private static final String FILE_PREFIX = "location_log_local_";
    private File logFile;
    private JSONArray locationArray;
    private final SimpleDateFormat dateFormat;
    
    public LocationLogger(Context context) {
        dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault());
        locationArray = new JSONArray();
        createLogFile(context);
        Log.d(TAG, "LocationLogger initialized");
    }
    
    private void createLogFile(Context context) {
        String timestamp = dateFormat.format(new Date());
        String fileName = String.format("%s%s.json", FILE_PREFIX, timestamp);
        
        File directory = new File(context.getExternalFilesDir(null), "location_logs");
        if (!directory.exists()) {
            directory.mkdirs();
        }
        
        logFile = new File(directory, fileName);
        Log.d(TAG, "Created local log file: " + logFile.getAbsolutePath());
    }
    
    public void logLocation(long timestamp, double latitude, double longitude) {
        try {
            JSONObject locationObject = new JSONObject();
            locationObject.put("timestamp", timestamp);
            locationObject.put("latitude", latitude);
            locationObject.put("longitude", longitude);
            locationArray.put(locationObject);
            
            Log.d(TAG, String.format("Logged location: time=%d, lat=%.6f, lng=%.6f", 
                timestamp, latitude, longitude));
            Log.d(TAG, "Current array size: " + locationArray.length());
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating JSON object: " + e.getMessage());
        }
    }
    
    public void saveToFile() {
        if (locationArray.length() == 0) {
            Log.w(TAG, "No locations to save!");
            return;
        }
        
        try (FileWriter writer = new FileWriter(logFile)) {
            JSONObject root = new JSONObject();
            root.put("locationData", locationArray);
            
            String jsonString = root.toString(4);
            writer.write(jsonString);
            
            Log.i(TAG, "Saved " + locationArray.length() + " locations to: " + logFile.getAbsolutePath());
            Log.d(TAG, "File content preview: " + jsonString.substring(0, Math.min(500, jsonString.length())));
            
            if (logFile.exists()) {
                Log.d(TAG, "File size: " + logFile.length() + " bytes");
            } else {
                Log.e(TAG, "File was not created!");
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Error saving location log: " + e.getMessage());
        }
    }
}