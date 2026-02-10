package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IndoorMapManager - API-based indoor map handler.
 * Supports GeoJSON data parsing and floor plan management.
 */
public class IndoorMapManager {

    private static final String TAG = "IndoorMapManager";
    private GoogleMap gMap;
    private Context context;
    private GroundOverlay groundOverlay;
    private SharedPreferences settings;

    // Current location
    private LatLng currentLocation;

    // Stores drawn shapes on map for easy removal
    private List<Object> drawnShapes = new ArrayList<>();

    private IndoorBuilding selectedBuilding;
    private int currentFloor = 0;
    private boolean isIndoorMapSet = false;
    private boolean isIndoorMapVisible = true;  // Indoor map visibility toggle
    
    // Store floor data from API response
    private String currentVenueName = null;
    private String currentOutlineGeoJson = null;
    private Map<String, String> floorShapesMap = new HashMap<>();  // floor name -> GeoJSON string
    private List<String> floorNamesList = new ArrayList<>();  // Ordered list of floor names
    
    // Wall collision detection data
    private Map<String, List<List<LatLng>>> floorWallsMap = new HashMap<>();  // floor name -> wall polylines
    private List<LatLng> buildingBoundary = null;  // Current building boundary polygon

    private Map<Polygon, IndoorBuilding> polygonMap = new HashMap<>();

    // Request tracking to handle race conditions
    private int currentRequestId = 0;
    private String pendingBuildingName = null;  // Building name for current pending request

    private static final String BASE_URL = "https://openpositioning.org/api/live/floorplan/request/";
    private static final String RAW_API_KEY = BuildConfig.OPENPOSITIONING_API_KEY;
    private static final String RAW_MASTER_KEY = BuildConfig.OPENPOSITIONING_MASTER_KEY;

    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // Callback for when floor data is loaded
    public interface OnFloorDataLoadedListener {
        void onFloorDataLoaded(boolean hasData);
    }
    private OnFloorDataLoadedListener floorDataLoadedListener;

    public IndoorMapManager(GoogleMap map, Context context) {
        this.gMap = map;
        this.context = context;
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
    }

    // ============================================================
    // Core API Methods
    // ============================================================

    public void fetchFloorPlan(LatLng loc, List<String> macs) {
        // Increment request ID and save expected building name
        final int thisRequestId = ++currentRequestId;
        final String expectedBuildingName = (selectedBuilding != null) ? selectedBuilding.name : "unknown";
        pendingBuildingName = expectedBuildingName;
        
        Log.d(TAG, ">>> [REQUEST #" + thisRequestId + "] Starting for: " + expectedBuildingName);
        
        executor.execute(() -> {
            try {
                String cleanApiKey = RAW_API_KEY.replace("<", "").replace(">", "").trim();
                String cleanMasterKey = RAW_MASTER_KEY.replace("<", "").replace(">", "").trim();
                String urlString = BASE_URL + cleanApiKey + "?key=" + cleanMasterKey;

                Log.d(TAG, ">>> [API] Requesting: " + urlString);
                Log.d(TAG, ">>> [API] Request body: lat=" + String.format("%.6f", loc.latitude) + ", lon=" + String.format("%.6f", loc.longitude));

                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setConnectTimeout(10000);

                JSONObject jsonBody = new JSONObject();
                jsonBody.put("lat", loc.latitude);
                jsonBody.put("lon", loc.longitude);
                JSONArray macArray = new JSONArray();
                if (macs != null) for (String mac : macs) macArray.put(mac);
                jsonBody.put("macs", macArray);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonBody.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                Log.d(TAG, ">>> [API] Response code: " + responseCode);
                
                if (responseCode == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder responseSb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) responseSb.append(line);
                    String jsonResponse = responseSb.toString().trim();

                    Log.d(TAG, ">>> [API] Response received (" + jsonResponse.length() + " chars)");
                    Log.d(TAG, ">>> [API] Full response: " + jsonResponse);

                    // Clear previous map
                    mainHandler.post(this::hideMap);

                    // Check if this response is still relevant (not superseded by newer request)
                    if (thisRequestId != currentRequestId) {
                        Log.w(TAG, ">>> [REQUEST #" + thisRequestId + "] IGNORED - superseded by request #" + currentRequestId);
                        return;
                    }
                    
                    // Parse response
                    if (jsonResponse.startsWith("[")) {
                        JSONArray arr = new JSONArray(jsonResponse);
                        Log.d(TAG, ">>> [API] Response is an array with " + arr.length() + " elements");
                        if (arr.length() > 0) {
                            parseResponseObject(arr.getJSONObject(0), expectedBuildingName);
                        } else {
                            Log.w(TAG, ">>> [API] Empty list [] returned. No map data for this location.");
                            Log.w(TAG, ">>> Server may not have indoor map data for this building.");
                            Log.w(TAG, ">>> Building: " + (selectedBuilding != null ? selectedBuilding.name : "unknown"));
                            // Notify listener that no data is available so UI can update
                            mainHandler.post(() -> {
                                if (floorDataLoadedListener != null) {
                                    floorDataLoadedListener.onFloorDataLoaded(false);
                                }
                            });
                        }
                    } else if (jsonResponse.startsWith("{")) {
                        Log.d(TAG, ">>> [API] Response is an object");
                        parseResponseObject(new JSONObject(jsonResponse), expectedBuildingName);
                    } else {
                        Log.e(TAG, ">>> [API] Invalid response format: " + jsonResponse.substring(0, Math.min(100, jsonResponse.length())));
                    }

                } else {
                    Log.e(TAG, ">>> [API] Error Code: " + responseCode);
                    if (responseCode == 404) {
                        Log.w(TAG, ">>> Building not found in server database.");
                        Log.w(TAG, ">>> Requested coordinates: (" + String.format("%.6f", loc.latitude) + ", " + String.format("%.6f", loc.longitude) + ")");
                    }
                    // Try to read error response body
                    try {
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
                        StringBuilder errorSb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) errorSb.append(line);
                        Log.e(TAG, ">>> [API] Error response: " + errorSb.toString());
                    } catch (Exception e) {
                        // Ignore
                    }
                }
                conn.disconnect();

            } catch (Exception e) {
                Log.e(TAG, ">>> [API] Request Failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void parseResponseObject(JSONObject obj, String expectedBuildingName) {
        try {
            Log.d(TAG, ">>> [PARSE] Starting to parse response object");
            Log.d(TAG, ">>> [PARSE] Expected building: " + expectedBuildingName);
            
            // Extract the three required fields: name, outline, map_shapes
            currentVenueName = obj.optString("name", null);
            currentOutlineGeoJson = obj.optString("outline", null);
            String mapShapesJsonString = obj.optString("map_shapes", null);

            Log.d(TAG, ">>> Parsing API Response:");
            Log.d(TAG, "    - API Returned Venue Name: " + currentVenueName);
            Log.d(TAG, "    - Expected Building Name: " + (selectedBuilding != null ? selectedBuilding.name : "null"));
            
            if (selectedBuilding != null && currentVenueName != null) {
                if (!currentVenueName.equalsIgnoreCase(selectedBuilding.name) && 
                    !currentVenueName.contains("Nucleus") && selectedBuilding.name.contains("Library")) {
                    Log.w(TAG, "    - WARNING: Building mismatch!");
                    Log.w(TAG, "    - Clicked: " + selectedBuilding.name);
                    Log.w(TAG, "    - API returned: " + currentVenueName);
                }
            }
            
            Log.d(TAG, "    - Has Outline: " + (currentOutlineGeoJson != null && !currentOutlineGeoJson.isEmpty()));
            Log.d(TAG, "    - Outline length: " + (currentOutlineGeoJson != null ? currentOutlineGeoJson.length() : 0) + " chars");
            Log.d(TAG, "    - Has map_shapes: " + (mapShapesJsonString != null && !mapShapesJsonString.isEmpty()));
            Log.d(TAG, "    - map_shapes length: " + (mapShapesJsonString != null ? mapShapesJsonString.length() : 0) + " chars");

            // Parse map_shapes: it's a JSON string containing a dictionary
            // where keys are floor names and values are GeoJSON strings
            floorShapesMap.clear();
            floorNamesList.clear();
            if (mapShapesJsonString != null && !mapShapesJsonString.isEmpty()) {
                try {
                    Log.d(TAG, ">>> [PARSE] Parsing map_shapes string...");
                    JSONObject shapesDict = new JSONObject(mapShapesJsonString);
                    Log.d(TAG, ">>> [PARSE] map_shapes has " + shapesDict.length() + " floors");
                    java.util.Iterator<String> keys = shapesDict.keys();
                    int floorIndex = 0;
                    while (keys.hasNext()) {
                        String floorName = keys.next();
                        String floorGeoJson = shapesDict.getString(floorName);
                        floorShapesMap.put(floorName, floorGeoJson);
                        floorNamesList.add(floorName);
                        Log.d(TAG, "    - Found floor " + (++floorIndex) + ": " + floorName + " (" + floorGeoJson.length() + " chars)");
                    }
                    
                    // Sort floors in logical order (ground, 0, 1, 2...)
                    floorNamesList.sort((f1, f2) -> {
                        int priority1 = getFloorPriority(f1);
                        int priority2 = getFloorPriority(f2);
                        return Integer.compare(priority1, priority2);
                    });
                    
                    Log.d(TAG, "    - Floor order after sorting: " + floorNamesList);
                } catch (
                        JSONException e) {
                    Log.e(TAG, ">>> Failed to parse map_shapes dictionary: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                Log.w(TAG, ">>> [PARSE] No map_shapes data in response!");
            }

            // Update selected building name if we have it
            if (currentVenueName != null && selectedBuilding != null) {
                Log.d(TAG, ">>> [PARSE] Updating selected building info:");
                Log.d(TAG, "    - Requested: " + expectedBuildingName);
                Log.d(TAG, "    - API returned: " + currentVenueName);
                
                // NO STRICT VALIDATION - Accept any data from API
                // The API returns the nearest building with indoor map data.
                // We display whatever data is returned to ensure the map is always shown.
                if (!currentVenueName.equalsIgnoreCase(expectedBuildingName)) {
                    Log.w(TAG, "    - Note: API returned different building's data.");
                    Log.w(TAG, "    - This is normal if the requested building has no data in the server.");
                }
                
                // Always accept: Update building info with API response
                selectedBuilding.name = currentVenueName;
                selectedBuilding.id = "venue_" + currentVenueName.toLowerCase().replace(" ", "_");
                Log.d(TAG, "    - Accepted API data");
                Log.d(TAG, "    - New ID: " + selectedBuilding.id);
            }

            // Render the data on the map
            mainHandler.post(() -> {
                hideMap();  // Clear previous drawings
                
                // Draw outline first (if available)
                if (currentOutlineGeoJson != null && !currentOutlineGeoJson.isEmpty()) {
                    Log.d(TAG, ">>> Drawing building outline");
                    renderGeoJson(currentOutlineGeoJson, Color.argb(80, 0, 0, 255), 6);
                    
                    // Parse and store building boundary for collision detection
                    try {
                        parseBuildingBoundary(currentOutlineGeoJson);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse building boundary: " + e.getMessage());
                    }
                } else {
                    Log.w(TAG, ">>> No outline data to draw");
                }
                
                // Draw ground floor shapes by default (if indoor map is visible)
                if (isIndoorMapVisible) {
                    Log.d(TAG, ">>> Rendering current floor (index " + currentFloor + ")");
                    renderCurrentFloor();
                } else {
                    Log.d(TAG, ">>> Indoor map hidden by user, skipping floor render");
                }
                
                // Notify listener that floor data is ready
                if (floorDataLoadedListener != null) {
                    boolean hasFloors = !floorNamesList.isEmpty();
                    Log.d(TAG, ">>> Notifying floor data loaded listener (hasData=" + hasFloors + ")");
                    floorDataLoadedListener.onFloorDataLoaded(hasFloors);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, ">>> JSON Parse Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void renderGeoJson(String jsonString, int color, float width) {
        renderGeoJsonWithWallData(jsonString, color, width, null);
    }
    
    /**
     * Render GeoJSON and optionally store wall data for collision detection
     * @param jsonString GeoJSON string to render
     * @param color Line color
     * @param width Line width
     * @param floorName Floor name (if not null, stores wall data for this floor)
     */
    private void renderGeoJsonWithWallData(String jsonString, int color, float width, String floorName) {
        try {
            JSONObject geoJson;
            if (jsonString.trim().startsWith("{") && !jsonString.contains("FeatureCollection")) {
                JSONObject wrapper = new JSONObject(jsonString);
                String key = wrapper.keys().next();
                geoJson = wrapper.getJSONObject(key);
            } else {
                geoJson = new JSONObject(jsonString);
            }

            JSONArray features = geoJson.optJSONArray("features");
            if (features == null) return;
            
            // Store wall data if floorName is provided
            List<List<LatLng>> wallsForThisFloor = new ArrayList<>();

            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.getJSONObject(i);
                JSONObject geometry = feature.getJSONObject("geometry");
                String type = geometry.getString("type");
                JSONArray coordinates = geometry.getJSONArray("coordinates");

                if (type.equals("MultiPolygon")) {
                    for (int j = 0; j < coordinates.length(); j++) {
                        JSONArray polygon = coordinates.getJSONArray(j);
                        JSONArray ring = polygon.getJSONArray(0);
                        List<LatLng> points = parseCoordinates(ring);
                        
                        // Draw polygon
                        Polygon p = gMap.addPolygon(new PolygonOptions()
                                .addAll(points)
                                .strokeColor(color)
                                .strokeWidth(width)
                                .fillColor(Color.TRANSPARENT));
                        drawnShapes.add(p);
                        
                        // Store as wall data (polygon perimeter is a wall)
                        if (floorName != null) {
                            wallsForThisFloor.add(points);
                        }
                    }
                } else if (type.equals("MultiLineString")) {
                    for (int j = 0; j < coordinates.length(); j++) {
                        JSONArray line = coordinates.getJSONArray(j);
                        List<LatLng> points = parseCoordinates(line);
                        
                        // Draw polyline
                        com.google.android.gms.maps.model.Polyline p = gMap.addPolyline(new PolylineOptions()
                                .addAll(points)
                                .color(color)
                                .width(width));
                        drawnShapes.add(p);
                        
                        // Store as wall data
                        if (floorName != null) {
                            wallsForThisFloor.add(points);
                        }
                    }
                } else if (type.equals("Polygon")) {
                    JSONArray ring = coordinates.getJSONArray(0);
                    List<LatLng> points = parseCoordinates(ring);
                    
                    // Draw polygon
                    Polygon p = gMap.addPolygon(new PolygonOptions()
                            .addAll(points)
                            .strokeColor(color)
                            .strokeWidth(width)
                            .fillColor(Color.TRANSPARENT));
                    drawnShapes.add(p);
                    
                    // Store as wall data
                    if (floorName != null) {
                        wallsForThisFloor.add(points);
                    }
                } else if (type.equals("LineString")) {
                    List<LatLng> points = parseCoordinates(coordinates);
                    
                    // Draw polyline
                    com.google.android.gms.maps.model.Polyline p = gMap.addPolyline(new PolylineOptions()
                            .addAll(points)
                            .color(color)
                            .width(width));
                    drawnShapes.add(p);
                    
                    // Store as wall data
                    if (floorName != null) {
                        wallsForThisFloor.add(points);
                    }
                }
            }
            
            // Store wall data for this floor
            if (floorName != null && !wallsForThisFloor.isEmpty()) {
                floorWallsMap.put(floorName, wallsForThisFloor);
                Log.d(TAG, ">>> Stored " + wallsForThisFloor.size() + " walls for floor: " + floorName);
            }
            
            isIndoorMapSet = true;
            Log.d(TAG, ">>> Vector shapes rendered successfully.");

        } catch (Exception e) {
            Log.e(TAG, ">>> Vector Render Failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private List<LatLng> parseCoordinates(JSONArray ring) throws Exception {
        List<LatLng> list = new ArrayList<>();
        for (int k = 0; k < ring.length(); k++) {
            JSONArray coord = ring.getJSONArray(k);
            double lon = coord.getDouble(0);
            double lat = coord.getDouble(1);
            list.add(new LatLng(lat, lon));
        }
        return list;
    }
    
    /**
     * Parse building boundary from outline GeoJSON for collision detection
     */
    private void parseBuildingBoundary(String outlineGeoJson) throws Exception {
        buildingBoundary = null;  // Clear previous boundary
        
        JSONObject geoJson;
        if (outlineGeoJson.trim().startsWith("{") && !outlineGeoJson.contains("FeatureCollection")) {
            JSONObject wrapper = new JSONObject(outlineGeoJson);
            String key = wrapper.keys().next();
            geoJson = wrapper.getJSONObject(key);
        } else {
            geoJson = new JSONObject(outlineGeoJson);
        }

        JSONArray features = geoJson.optJSONArray("features");
        if (features == null || features.length() == 0) return;

        // Get the first polygon as building boundary
        JSONObject feature = features.getJSONObject(0);
        JSONObject geometry = feature.getJSONObject("geometry");
        String type = geometry.getString("type");
        JSONArray coordinates = geometry.getJSONArray("coordinates");

        if (type.equals("MultiPolygon")) {
            // Take first polygon
            JSONArray polygon = coordinates.getJSONArray(0);
            JSONArray ring = polygon.getJSONArray(0);
            buildingBoundary = parseCoordinates(ring);
        } else if (type.equals("Polygon")) {
            JSONArray ring = coordinates.getJSONArray(0);
            buildingBoundary = parseCoordinates(ring);
        }
        
        if (buildingBoundary != null && !buildingBoundary.isEmpty()) {
            Log.d(TAG, ">>> Building boundary parsed: " + buildingBoundary.size() + " points");
        }
    }

    private void downloadAndShowImage(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.connect();
            InputStream in = conn.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap != null) {
                mainHandler.post(() -> updateOverlay(bitmap));
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, ">>> Image Download Error: " + e.getMessage());
        }
    }

    // ============================================================
    // 2. Helper Methods
    // ============================================================

    // Track selected polygon for visual feedback
    private Polygon selectedPolygon = null;
    private int selectedPolygonOriginalStroke = Color.DKGRAY;

    public void addFallbackBuildings() {
        List<IndoorBuilding> fallbackList = new ArrayList<>();

        // ========== Nucleus Building ==========
        // Precise polygon based on BuildingPolygon.java reference
        List<LatLng> nucleusPoints = new ArrayList<>();
        nucleusPoints.add(new LatLng(55.92332, -3.17388));  // NE corner
        nucleusPoints.add(new LatLng(55.92282, -3.17388));  // SE corner
        nucleusPoints.add(new LatLng(55.92282, -3.17460));  // SW corner
        nucleusPoints.add(new LatLng(55.92332, -3.17460));  // NW corner
        fallbackList.add(new IndoorBuilding("venue_nucleus", "The Nucleus Building", nucleusPoints, calculateBounds(nucleusPoints), new HashMap<>(), 4.2f));

        // ========== Murray Library ==========
        // Moved west to eliminate overlap with Nucleus (east edge at -3.17477)
        List<LatLng> libraryPoints = new ArrayList<>();
        libraryPoints.add(new LatLng(55.92307, -3.17477));  // NE corner
        libraryPoints.add(new LatLng(55.92281, -3.17477));  // SE corner
        libraryPoints.add(new LatLng(55.92281, -3.17518));  // SW corner
        libraryPoints.add(new LatLng(55.92307, -3.17518));  // NW corner
        fallbackList.add(new IndoorBuilding("venue_library", "Murray Library", libraryPoints, calculateBounds(libraryPoints), new HashMap<>(), 4.0f));

        // ========== Murchison House ==========
        List<LatLng> murchisonPoints = new ArrayList<>();
        murchisonPoints.add(new LatLng(55.92447, -3.17868));  // NE
        murchisonPoints.add(new LatLng(55.92379, -3.17868));  // SE
        murchisonPoints.add(new LatLng(55.92379, -3.17964));  // SW
        murchisonPoints.add(new LatLng(55.92447, -3.17964));  // NW
        fallbackList.add(new IndoorBuilding("venue_murchison", "Murchison House", murchisonPoints, calculateBounds(murchisonPoints), new HashMap<>(), 4.0f));

        // ========== Fleeming Jenkin Building ==========
        List<LatLng> fjbPoints = new ArrayList<>();
        fjbPoints.add(new LatLng(55.92282, -3.17259));  // NE
        fjbPoints.add(new LatLng(55.92221, -3.17192));  // SE
        fjbPoints.add(new LatLng(55.92211, -3.17228));  // SW
        fjbPoints.add(new LatLng(55.92269, -3.17296));  // NW
        fallbackList.add(new IndoorBuilding("venue_fjb", "Fleeming Jenkin Building", fjbPoints, calculateBounds(fjbPoints), new HashMap<>(), 3.5f));

        Log.d(TAG, ">>> Loaded " + fallbackList.size() + " fallback buildings:");
        for (IndoorBuilding b : fallbackList) {
            LatLng center = b.bounds.getCenter();
            Log.d(TAG, "    - " + b.name + " at (" + String.format("%.6f", center.latitude) + ", " + String.format("%.6f", center.longitude) + ")");
        }

        mainHandler.post(() -> drawBuildingOutlines(fallbackList));
    }

    private void drawBuildingOutlines(List<IndoorBuilding> buildings) {
        // Distinct colors for each building
        int[][] buildingColors = {
            {Color.rgb(255, 191, 0), 0x30FFD700},  // Nucleus: gold
            {Color.rgb(41, 128, 185), 0x252980B9},  // Library: blue
            {Color.rgb(39, 174, 96), 0x2527AE60},   // Murchison: green
            {Color.rgb(192, 57, 43), 0x25C0392B},   // FJB: red
        };

        for (int i = 0; i < buildings.size(); i++) {
            IndoorBuilding b = buildings.get(i);
            int strokeColor = (i < buildingColors.length) ? buildingColors[i][0] : Color.DKGRAY;
            int fillColor   = (i < buildingColors.length) ? buildingColors[i][1] : 0x20444444;

            Polygon poly = gMap.addPolygon(new PolygonOptions()
                    .addAll(b.polygonPoints)
                    .strokeColor(strokeColor)
                    .strokeWidth(4f)
                    .fillColor(fillColor)
                    .clickable(true)
                    .zIndex(10));
            poly.setTag(b.name);  // Store building name as tag
            polygonMap.put(poly, b);
        }
    }

    public boolean onPolygonClick(Polygon polygon) {
        IndoorBuilding b = polygonMap.get(polygon);
        if (b != null) {
            // Reset previously selected polygon
            if (selectedPolygon != null && selectedPolygon != polygon) {
                selectedPolygon.setStrokeColor(selectedPolygonOriginalStroke);
                selectedPolygon.setStrokeWidth(4f);
            }

            selectedBuilding = b;
            currentFloor = 0;
            
            // Clear old floor data immediately when selecting a new building
            // This prevents stale floor data from a previous building being used
            floorShapesMap.clear();
            floorNamesList.clear();
            floorWallsMap.clear();
            buildingBoundary = null;
            currentVenueName = null;
            currentOutlineGeoJson = null;
            hideMap();  // Clear previously drawn indoor shapes
            
            LatLng center = b.bounds.getCenter();
            Log.d(TAG, "====================================");
            Log.d(TAG, ">>> Building Clicked: " + b.name);
            Log.d(TAG, ">>> Building ID: " + b.id);
            Log.d(TAG, ">>> Center: (" + String.format("%.6f", center.latitude) + ", " + String.format("%.6f", center.longitude) + ")");
            Log.d(TAG, ">>> Requesting API for THIS building's floor data...");

            // Highlight selected building
            selectedPolygonOriginalStroke = polygon.getStrokeColor();
            selectedPolygon = polygon;
            polygon.setStrokeColor(Color.rgb(0, 230, 118));  // Material green accent
            polygon.setStrokeWidth(6f);

            // Convert building name to API campaign ID and save to SharedPreferences
            String apiCampaignId = convertNameToApiId(b.name);
            settings.edit().putString("current_campaign", apiCampaignId).apply();
            Log.d(TAG, "Saved Campaign to Prefs: " + apiCampaignId);

            fetchFloorPlan(center, new ArrayList<>());
            return true;
        } else {
            Log.w(TAG, ">>> Polygon clicked but not found in polygonMap!");
        }
        return false;
    }

    public void hideMap() {
        if (groundOverlay != null) {
            groundOverlay.remove();
            groundOverlay = null;
        }
        // Clear drawn vector shapes
        for (Object shape : drawnShapes) {
            if (shape instanceof Polygon) ((Polygon) shape).remove();
            if (shape instanceof com.google.android.gms.maps.model.Polyline) ((com.google.android.gms.maps.model.Polyline) shape).remove();
        }
        drawnShapes.clear();
        isIndoorMapSet = false;
    }

    private void updateOverlay(Bitmap bitmap) {
        if (bitmap == null || selectedBuilding == null) return;
        hideMap();

        try {
            groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                    .image(BitmapDescriptorFactory.fromBitmap(bitmap))
                    .positionFromBounds(selectedBuilding.bounds)
                    .zIndex(100)
                    .transparency(0.1f));
            isIndoorMapSet = true;
        } catch (Exception e) {
            Log.e(TAG, "Overlay Error: " + e.getMessage());
        }
    }

    // Setter methods
    public void setCurrentLocation(LatLng loc) { this.currentLocation = loc; }
    public void fetchBuildingsFromApi(LatLng loc) { fetchFloorPlan(loc, new ArrayList<>()); }
    public void setOnFloorDataLoadedListener(OnFloorDataLoadedListener listener) {
        this.floorDataLoadedListener = listener;
    }
    
    /**
     * Set selected building before API call (for validation)
     * This is critical for building name verification in API responses
     */
    public void setSelectedBuilding(String name, LatLng center) {
        // Create a temporary building object for validation purposes
        List<LatLng> tempPolygon = new ArrayList<>();
        tempPolygon.add(center);
        
        // Clear old floor data when switching buildings
        floorShapesMap.clear();
        floorNamesList.clear();
        floorWallsMap.clear();
        buildingBoundary = null;
        currentVenueName = null;
        currentOutlineGeoJson = null;
        hideMap();
        
        selectedBuilding = new IndoorBuilding(
            "venue_" + name.toLowerCase().replace(" ", "_"),
            name,
            tempPolygon,
            new LatLngBounds(center, center),
            new HashMap<>(),
            4.0f
        );
        
        Log.d(TAG, "====================================");
        Log.d(TAG, ">>> Selected building set: " + name);
        Log.d(TAG, ">>> Building ID: " + selectedBuilding.id);
        Log.d(TAG, ">>> Center: (" + String.format("%.6f", center.latitude) + ", " + String.format("%.6f", center.longitude) + ")");
        Log.d(TAG, ">>> This will be used to validate API response");
    }
    
    /**
     * Toggle indoor map visibility (toggle floor plan display)
     */
    public void setIndoorMapVisible(boolean visible) {
        this.isIndoorMapVisible = visible;
        if (!visible) {
            // Hide indoor shapes but keep building outlines
            List<Object> shapesToRemove = new ArrayList<>();
            for (Object shape : drawnShapes) {
                if (shape instanceof com.google.android.gms.maps.model.Polyline) {
                    ((com.google.android.gms.maps.model.Polyline) shape).setVisible(false);
                } else if (shape instanceof Polygon) {
                    ((Polygon) shape).setVisible(false);
                }
            }
            Log.d(TAG, ">>> Indoor map hidden");
        } else {
            // Show indoor shapes
            for (Object shape : drawnShapes) {
                if (shape instanceof com.google.android.gms.maps.model.Polyline) {
                    ((com.google.android.gms.maps.model.Polyline) shape).setVisible(true);
                } else if (shape instanceof Polygon) {
                    ((Polygon) shape).setVisible(true);
                }
            }
            Log.d(TAG, ">>> Indoor map shown");
        }
    }
    
    public boolean isIndoorMapVisible() { return isIndoorMapVisible; }
    public String getSelectedVenueId() { return currentVenueName != null ? currentVenueName : (selectedBuilding != null ? selectedBuilding.name : "None"); }
    public String getSelectedBuildingId() { return selectedBuilding != null ? selectedBuilding.id : null; }
    public String getSelectedBuildingName() { return currentVenueName != null ? currentVenueName : (selectedBuilding != null ? selectedBuilding.name : null); }
    public float getFloorHeight() { return selectedBuilding != null ? selectedBuilding.floorHeight : 4.0f; }
    public int getCurrentFloor() { return currentFloor; }
    public int getAvailableFloorsCount() { return floorNamesList.size(); }
    
    /**
     * Get the current floor name as it appears in the map data
     */
    public String getCurrentFloorName() {
        if (floorNamesList.isEmpty()) return null;
        
        // Get floor name by index from ordered list
        if (currentFloor >= 0 && currentFloor < floorNamesList.size()) {
            return floorNamesList.get(currentFloor);
        }
        
        return null;
    }
    public void setCurrentFloor(int floor, boolean auto) {
        this.currentFloor = floor;
        if (selectedBuilding != null && !floorShapesMap.isEmpty()) {
            // Don't re-fetch from API, just re-render from cached data
            mainHandler.post(() -> {
                // Clear indoor shapes (both Polygons and Polylines), keep the outline
                List<Object> shapesToRemove = new ArrayList<>();
                for (Object shape : drawnShapes) {
                    if (shape instanceof com.google.android.gms.maps.model.Polyline) {
                        ((com.google.android.gms.maps.model.Polyline) shape).remove();
                        shapesToRemove.add(shape);
                    } else if (shape instanceof Polygon) {
                        // Only remove if it's not the building outline
                        Polygon polygon = (Polygon) shape;
                        // Building outlines have specific color (blue), indoor shapes are black
                        if (polygon.getStrokeColor() != Color.argb(80, 0, 0, 255) && 
                            polygon.getStrokeColor() != Color.GREEN) {
                            polygon.remove();
                            shapesToRemove.add(shape);
                        }
                    }
                }
                drawnShapes.removeAll(shapesToRemove);
                Log.d(TAG, ">>> Cleared " + shapesToRemove.size() + " indoor shapes for floor switch");
                if (isIndoorMapVisible) {
                    renderCurrentFloor();
                }
            });
        }
    }
    
    /**
     * Helper method to determine floor priority for sorting
     */
    private int getFloorPriority(String floorName) {
        String lower = floorName.toLowerCase();
        // Ground floor variants get priority 0
        if (lower.contains("ground") || lower.equals("0")) {
            return 0;
        }
        // Try to extract floor number
        if (lower.startsWith("floor_")) {
            try {
                return Integer.parseInt(lower.substring(6)) + 1;
            } catch (NumberFormatException e) {
                return 999;
            }
        }
        // Try to parse as number
        try {
            return Integer.parseInt(lower) + 1;
        } catch (NumberFormatException e) {
            return 999;
        }
    }
    
    /**
     * Render the current floor's shapes from cached data.
     * Uses the ordered floor list to access floors by index.
     */
    private void renderCurrentFloor() {
        if (floorNamesList.isEmpty() || floorShapesMap.isEmpty()) {
            Log.d(TAG, ">>> No floor data available to render");
            return;
        }
        
        // Get floor name by index from ordered list
        if (currentFloor >= 0 && currentFloor < floorNamesList.size()) {
            String floorName = floorNamesList.get(currentFloor);
            String floorGeoJson = floorShapesMap.get(floorName);
            
            if (floorGeoJson != null) {
                Log.d(TAG, ">>> Rendering floor: " + floorName + " (index=" + currentFloor + " of " + floorNamesList.size() + ")");
                
                // Clear existing wall data for this floor before re-rendering
                floorWallsMap.remove(floorName);
                
                // Render and store wall data for collision detection
                renderGeoJsonWithWallData(floorGeoJson, Color.BLACK, 3, floorName);
                isIndoorMapSet = true;
            } else {
                Log.w(TAG, ">>> Floor data missing for: " + floorName);
            }
        } else {
            Log.w(TAG, ">>> Invalid floor index: " + currentFloor + " (available: 0-" + (floorNamesList.size() - 1) + ")");
        }
    }
    
    public void increaseFloor() {
        if (!floorNamesList.isEmpty()) {
            int maxFloor = floorNamesList.size() - 1;
            if (currentFloor < maxFloor) {
                setCurrentFloor(currentFloor + 1, false);
                String floorName = floorNamesList.get(currentFloor);
                Log.d(TAG, "Increased to floor: " + floorName + " (index=" + currentFloor + ")");
            } else {
                Log.d(TAG, "Already at top floor: " + currentFloor + "/" + maxFloor);
            }
        }
    }
    
    public void decreaseFloor() {
        if (!floorNamesList.isEmpty()) {
            if (currentFloor > 0) {
                setCurrentFloor(currentFloor - 1, false);
                String floorName = floorNamesList.get(currentFloor);
                Log.d(TAG, "Decreased to floor: " + floorName + " (index=" + currentFloor + ")");
            } else {
                Log.d(TAG, "Already at ground floor: 0");
            }
        }
    }
    
    private LatLngBounds calculateBounds(List<LatLng> points) {
        LatLngBounds.Builder b = new LatLngBounds.Builder();
        for (LatLng p : points) b.include(p);
        return b.build();
    }
    
    /**
     * Check if indoor constraints (walls, boundaries) are available for current floor
     */
    public boolean hasIndoorConstraints() {
        // Check if we have wall data for the current floor and indoor map is visible
        if (!isIndoorMapVisible) {
            return false;  // Don't apply constraints when indoor map is hidden
        }
        
        String currentFloorName = getCurrentFloorName();
        if (currentFloorName != null && floorWallsMap.containsKey(currentFloorName)) {
            List<List<LatLng>> walls = floorWallsMap.get(currentFloorName);
            return walls != null && !walls.isEmpty();
        }
        
        // Also check if we have building boundary
        return buildingBoundary != null && !buildingBoundary.isEmpty();
    }

    /**
     * Validate a position against indoor constraints (wall collision, boundary check)
     * Prevents the position marker from going through walls or outside building
     * Enhanced with wall sliding - allows movement parallel to walls
     */
    public LatLng validatePosition(LatLng newLoc, LatLng oldLoc) {
        if (newLoc == null) return oldLoc;
        if (oldLoc == null) return newLoc;  // First position, no validation needed
        if (!isIndoorMapVisible) return newLoc;  // No constraints when indoor map is off
        
        // 1. Check building boundary
        if (buildingBoundary != null && !buildingBoundary.isEmpty()) {
            if (!GeometryUtils.isPointInPolygon(newLoc, buildingBoundary)) {
                // New position is outside building - constrain to boundary
                Log.d(TAG, "Position outside building boundary - constraining");
                return GeometryUtils.constrainToPolygon(newLoc, buildingBoundary);
            }
        }
        
        // 2. Check wall collision for current floor
        String currentFloorName = getCurrentFloorName();
        if (currentFloorName != null && floorWallsMap.containsKey(currentFloorName)) {
            List<List<LatLng>> walls = floorWallsMap.get(currentFloorName);
            if (walls != null && !walls.isEmpty()) {
                // Check if movement from oldLoc to newLoc crosses any wall
                if (GeometryUtils.crossesWall(oldLoc, newLoc, walls)) {
                    Log.d(TAG, "Wall collision detected - attempting wall slide");
                    
                    // Try to slide along the wall instead of stopping completely
                    // Decompose movement into x and y components
                    LatLng slidPos = tryWallSlide(oldLoc, newLoc, walls);
                    if (slidPos != null && !slidPos.equals(oldLoc)) {
                        Log.d(TAG, "Wall slide successful - allowing partial movement");
                        return slidPos;
                    }
                    
                    // If slide failed, stay at old position
                    return oldLoc;
                }
            }
        }
        
        // No collision - allow movement
        return newLoc;
    }
    
    /**
     * Try to slide along a wall when direct movement is blocked
     * Attempts horizontal and vertical components separately
     */
    private LatLng tryWallSlide(LatLng from, LatLng to, List<List<LatLng>> walls) {
        // Try horizontal movement only (keep latitude)
        LatLng horizontalMove = new LatLng(from.latitude, to.longitude);
        if (!GeometryUtils.crossesWall(from, horizontalMove, walls)) {
            return horizontalMove;
        }
        
        // Try vertical movement only (keep longitude)
        LatLng verticalMove = new LatLng(to.latitude, from.longitude);
        if (!GeometryUtils.crossesWall(from, verticalMove, walls)) {
            return verticalMove;
        }
        
        // Try partial movement (50% distance)
        double midLat = (from.latitude + to.latitude) / 2.0;
        double midLon = (from.longitude + to.longitude) / 2.0;
        LatLng halfMove = new LatLng(midLat, midLon);
        if (!GeometryUtils.crossesWall(from, halfMove, walls)) {
            return halfMove;
        }
        
        return null;  // No valid slide found
    }
    
    /**
     * ✅ Convert display name to API campaign ID
     * Maps UI-friendly building names to API-compatible IDs for trajectory uploads
     * Only uses verified campaign IDs that exist on the server
     */
    private String convertNameToApiId(String displayName) {
    if (displayName == null) return ""; // Default: empty (no campaign)

        String lowerName = displayName.toLowerCase().trim();

        // Only map campaigns that are CONFIRMED to exist on the server
        if (lowerName.contains("murchison")) {
            return "murchison_house";
        }
        else if (lowerName.contains("nucleus")) {
            return "nucleus_building";
        }
        
        // ⚠️ Library, FJB and other buildings: use empty campaign
        // These campaigns may not exist on the server, so return empty string
        // Once confirmed these campaigns exist, update this mapping
        return "";
    }
    
    /**
     * Check if a location is inside the currently selected building's boundary
     * @param location The location to check
     * @return true if location is inside the selected building, false otherwise
     */
    public boolean isLocationInsideSelectedBuilding(LatLng location) {
        if (location == null) return false;
        
        // Check against building boundary if available
        if (buildingBoundary != null && !buildingBoundary.isEmpty()) {
            return GeometryUtils.isPointInPolygon(location, buildingBoundary);
        }
        
        // Fallback: check against selected building's polygon bounds
        if (selectedBuilding != null && selectedBuilding.polygonPoints != null) {
            return GeometryUtils.isPointInPolygon(location, selectedBuilding.polygonPoints);
        }
        
        return false;
    }
}