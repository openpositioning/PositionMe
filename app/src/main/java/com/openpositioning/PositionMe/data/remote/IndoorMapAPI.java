package com.openpositioning.PositionMe.data.remote;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.Locale;

// IndoorMapAPI - Handles API calls to fetch indoor map data
// Communicates with servers to get building info, floor plans, etc.
public class IndoorMapAPI {
    private static final String TAG = "IndoorMapAPI";
    
    // API endpoints - Update these with your actual server endpoints
    private static final String BASE_URL = "https://openpositioning.org/api/live";
    
    private final OkHttpClient httpClient;
    
    public IndoorMapAPI() {
        this.httpClient = new OkHttpClient();
    }
    
    // Building data class to represent building information
    public static class BuildingInfo {
        public String buildingId;
        public String buildingName;
        public double latitude;
        public double longitude;
        public List<String> floorNames;
        public int floorCount;
        
        public BuildingInfo() {
            this.floorNames = new ArrayList<>();
        }
    }
    
    // Floor plan data class
    public static class FloorPlan {
        public String floorId;
        public String floorName;
        public int floorNumber;
        public String imageUrl;  // URL to floor plan image
        public double minLat;
        public double maxLat;
        public double minLon;
        public double maxLon;
    }
    
    // Fetch buildings near a coordinate
    // @param latitude User current latitude
    // @param longitude User current longitude
    // @param radiusMeters Search radius in meters
    // @param callback Callback to handle results
    public void fetchNearbyBuildings(double latitude, double longitude, double radiusMeters, 
                                     BuildingsCallback callback) {
        new Thread(() -> {
            try {
                String url = String.format(Locale.US, "%s/buildings/nearby?lat=%f&lon=%f&radius=%f",
                        BASE_URL, latitude, longitude, radiusMeters);
                
                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        callback.onError("API Error: " + response.code());
                        return;
                    }
                    
                    String jsonData = response.body().string();
                    try {
                        List<BuildingInfo> buildings = parseBuildings(jsonData);
                        callback.onSuccess(buildings);
                    } catch (JSONException e) {
                        Log.e(TAG, "JSON parse error", e);
                        callback.onError("Parse error: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Network error", e);
                callback.onError("Network error: " + e.getMessage());
            }
        }).start();
    }
    
    // Fetch floor plans for a specific building
    // @param buildingId Building ID to fetch floors for
    // @param callback Callback to handle results
    public void fetchBuildingFloors(String buildingId, FloorsCallback callback) {
        new Thread(() -> {
            try {
                String url = String.format(Locale.US, "%s/buildings/%s/floors", BASE_URL, buildingId);
                
                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        callback.onError("API Error: " + response.code());
                        return;
                    }
                    
                    String jsonData = response.body().string();
                    try {
                        List<FloorPlan> floors = parseFloors(jsonData);
                        callback.onSuccess(floors);
                    } catch (JSONException e) {
                        Log.e(TAG, "JSON parse error", e);
                        callback.onError("Parse error: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Network error", e);
                callback.onError("Network error: " + e.getMessage());
            }
        }).start();
    }
    
    // Fetch building outline/boundary polygon
    // @param buildingId Building ID
    // @param callback Callback with coordinate array
    public void fetchBuildingOutline(String buildingId, OutlineCallback callback) {
        new Thread(() -> {
            try {
                String url = String.format(Locale.US, "%s/buildings/%s/outline", BASE_URL, buildingId);
                
                Request request = new Request.Builder()
                        .url(url)
                        .get()
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        callback.onError("API Error: " + response.code());
                        return;
                    }
                    
                    String jsonData = response.body().string();
                    try {
                        double[][] coordinates = parseOutlineCoordinates(jsonData);
                        callback.onSuccess(coordinates);
                    } catch (JSONException e) {
                        Log.e(TAG, "JSON parse error", e);
                        callback.onError("Parse error: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Network error", e);
                callback.onError("Network error: " + e.getMessage());
            }
        }).start();
    }
    
    // ============= Parsing Methods =============
    
    private List<BuildingInfo> parseBuildings(String jsonData) throws JSONException {
        List<BuildingInfo> buildings = new ArrayList<>();
        JSONArray array = new JSONArray(jsonData);
        
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            BuildingInfo building = new BuildingInfo();
            
            building.buildingId = obj.optString("building_id", "");
            building.buildingName = obj.optString("name", "Unknown Building");
            building.latitude = obj.optDouble("latitude", 0.0);
            building.longitude = obj.optDouble("longitude", 0.0);
            building.floorCount = obj.optInt("floor_count", 1);
            
            // Parse floor names if available
            JSONArray floors = obj.optJSONArray("floors");
            if (floors != null) {
                for (int j = 0; j < floors.length(); j++) {
                    building.floorNames.add(floors.getString(j));
                }
            }
            
            buildings.add(building);
        }
        
        return buildings;
    }
    
    private List<FloorPlan> parseFloors(String jsonData) throws JSONException {
        List<FloorPlan> floors = new ArrayList<>();
        JSONArray array = new JSONArray(jsonData);
        
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            FloorPlan floor = new FloorPlan();
            
            floor.floorId = obj.optString("floor_id", "");
            floor.floorName = obj.optString("name", "Floor " + i);
            floor.floorNumber = obj.optInt("floor_number", i);
            floor.imageUrl = obj.optString("image_url", "");
            floor.minLat = obj.optDouble("bounds_min_lat", 0.0);
            floor.maxLat = obj.optDouble("bounds_max_lat", 0.0);
            floor.minLon = obj.optDouble("bounds_min_lon", 0.0);
            floor.maxLon = obj.optDouble("bounds_max_lon", 0.0);
            
            floors.add(floor);
        }
        
        return floors;
    }
    
    private double[][] parseOutlineCoordinates(String jsonData) throws JSONException {
        JSONObject obj = new JSONObject(jsonData);
        JSONArray coords = obj.getJSONArray("coordinates");
        
        double[][] result = new double[coords.length()][2];
        for (int i = 0; i < coords.length(); i++) {
            JSONArray coord = coords.getJSONArray(i);
            result[i][0] = coord.getDouble(0);  // latitude
            result[i][1] = coord.getDouble(1);  // longitude
        }
        
        return result;
    }
    
    // ============= Callbacks =============
    
    public interface BuildingsCallback {
        void onSuccess(List<BuildingInfo> buildings);
        void onError(String error);
    }
    
    public interface FloorsCallback {
        void onSuccess(List<FloorPlan> floors);
        void onError(String error);
    }
    
    public interface OutlineCallback {
        void onSuccess(double[][] coordinates);
        void onError(String error);
    }
}


