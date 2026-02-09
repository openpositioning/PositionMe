package com.openpositioning.PositionMe.utils;

import android.graphics.Color;
import android.util.Log;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.R;

import java.util.Arrays;
import java.util.List;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;
import android.os.Handler;
import android.os.Looper;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.Wifi;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;

/**
 * Manages indoor floor map overlays and vector drawings.
 * Handles both local image resources (Nucleus, Library) and API-fetched vector maps (Murchison).
 *
 * @author Arun Gopalakrishnan
 * @author Mate Stodulka
 */
public class IndoorMapManager {

    private GoogleMap gMap;
    // Name of the currently selected building
    private String selectedBuildingName = "";
    // Image overlay for Nucleus/Library
    private GroundOverlay groundOverlay;
    private LatLng currentLocation;
    // Flag indicating if any map is currently visible
    private boolean isIndoorMapSet = false;
    private int currentFloor;

    // Stores API response data for Murchison floor plans
    private JSONObject murchisonFloorData;
    // Stores active vector polygons drawn on the map
    private List<com.google.android.gms.maps.model.Polygon> drawnPolygons = new java.util.ArrayList<>();

    private float floorHeight;

    // Local resources for Nucleus and Library
    private final List<Integer> NUCLEUS_MAPS = Arrays.asList(
            R.drawable.nucleuslg, R.drawable.nucleusg, R.drawable.nucleus1,
            R.drawable.nucleus2, R.drawable.nucleus3);
    private final List<Integer> LIBRARY_MAPS = Arrays.asList(
            R.drawable.libraryg, R.drawable.library1, R.drawable.library2,
            R.drawable.library3);

    // Geographic bounds for local overlays
    LatLngBounds NUCLEUS = new LatLngBounds(
            BuildingPolygon.NUCLEUS_SW,
            BuildingPolygon.NUCLEUS_NE
    );
    LatLngBounds LIBRARY = new LatLngBounds(
            BuildingPolygon.LIBRARY_SW,
            BuildingPolygon.LIBRARY_NE
    );

    public static final float NUCLEUS_FLOOR_HEIGHT = 4.2F;
    public static final float LIBRARY_FLOOR_HEIGHT = 3.6F;

    public IndoorMapManager(GoogleMap map) {
        this.gMap = map;
    }

    /**
     * Parses and draws vector polygons for a specific floor (Murchison only).
     * @param floorName The key for the floor in the JSON (e.g., "GF", "F1").
     */
    private void drawFloor(String floorName) {
        if (gMap == null || murchisonFloorData == null) return;

        // Clear existing polygons from the map
        for (com.google.android.gms.maps.model.Polygon p : drawnPolygons) {
            p.remove();
        }
        drawnPolygons.clear();

        try {
            if (!murchisonFloorData.has(floorName)) {
                Log.e("IndoorMapAPI", "Floor not found: " + floorName);
                return;
            }

            JSONObject floorGeoJson = murchisonFloorData.getJSONObject(floorName);
            org.json.JSONArray features = floorGeoJson.getJSONArray("features");

            // Iterate through GeoJSON features
            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.getJSONObject(i);
                JSONObject geometry = feature.getJSONObject("geometry");

                if (geometry.getString("type").equals("MultiPolygon")) {
                    org.json.JSONArray coordinates = geometry.getJSONArray("coordinates");

                    // Parse MultiPolygon coordinates
                    for (int j = 0; j < coordinates.length(); j++) {
                        org.json.JSONArray polygonCoords = coordinates.getJSONArray(j);
                        org.json.JSONArray ring = polygonCoords.getJSONArray(0); // Outer ring

                        List<LatLng> latLngs = new java.util.ArrayList<>();
                        for (int k = 0; k < ring.length(); k++) {
                            org.json.JSONArray point = ring.getJSONArray(k);
                            double lon = point.getDouble(0);
                            double lat = point.getDouble(1);
                            latLngs.add(new LatLng(lat, lon));
                        }

                        // Draw polygons with semi-transparent black fill
                        com.google.android.gms.maps.model.PolygonOptions polyOptions =
                                new com.google.android.gms.maps.model.PolygonOptions()
                                        .addAll(latLngs)
                                        .strokeColor(Color.BLACK)
                                        .strokeWidth(2)
                                        .fillColor(Color.argb(50, 0, 0, 0))
                                        .zIndex(3);

                        drawnPolygons.add(gMap.addPolygon(polyOptions));
                    }
                }
            }
            Log.d("IndoorMapAPI", "Drawn floor: " + floorName);

        } catch (Exception e) {
            Log.e("IndoorMapAPI", "Error drawing floor " + floorName, e);
        }
    }

    public void setCurrentLocation(LatLng currentLocation) {
        this.currentLocation = currentLocation;
    }

    /**
     * Selects a building and displays its indoor map.
     * Clears any existing maps before loading the new one.
     *
     * @param buildingName The name of the building to select.
     * @return true if a local map was loaded, false otherwise (or if API fetch is needed).
     */
    public boolean selectBuilding(String buildingName) {
        if (gMap == null) return false;

        if (isIndoorMapSet && selectedBuildingName.equals(buildingName)) {
            return true;
        }

        // Clean up previous map overlays/polygons
        deselectBuilding();

        selectedBuildingName = buildingName;

        try {
            if ("Nucleus".equals(buildingName)) {
                groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                        .image(BitmapDescriptorFactory.fromResource(R.drawable.nucleusg))
                        .positionFromBounds(NUCLEUS)
                        .zIndex(1));
                isIndoorMapSet = true;
                currentFloor = 1;
                floorHeight = NUCLEUS_FLOOR_HEIGHT;
                return true;
            } else if ("Library".equals(buildingName)) {
                groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                        .image(BitmapDescriptorFactory.fromResource(R.drawable.libraryg))
                        .positionFromBounds(LIBRARY)
                        .zIndex(1));
                isIndoorMapSet = true;
                currentFloor = 0;
                floorHeight = LIBRARY_FLOOR_HEIGHT;
                return true;
            } else if ("Murchison".equals(buildingName)) {
                // Return false to trigger API fetch in Fragment
                return false;
            } else {
                return false;
            }
        } catch (Exception e) {
            Log.e("IndoorMapManager", "Error loading map for " + buildingName, e);
            return false;
        }
    }

    /**
     * Clears all indoor map overlays (images and vectors) and resets state.
     */
    public void deselectBuilding() {
        // Remove image overlays
        if (groundOverlay != null) {
            groundOverlay.remove();
            groundOverlay = null;
        }

        // Remove vector polygons
        if (drawnPolygons != null) {
            for (com.google.android.gms.maps.model.Polygon p : drawnPolygons) {
                p.remove();
            }
            drawnPolygons.clear();
        }

        isIndoorMapSet = false;
        selectedBuildingName = "";
        currentFloor = 0;
    }

    public String getSelectedBuilding() {
        return selectedBuildingName;
    }

    public float getFloorHeight() {
        return floorHeight;
    }

    public boolean getIsIndoorMapSet() {
        return isIndoorMapSet;
    }

    /**
     * Updates the displayed floor map based on the user's selection or auto-floor logic.
     * Handles switching for Nucleus, Library (Image) and Murchison (Vector).
     */
    public void setCurrentFloor(int newFloor, boolean autoFloor) {
        if ("Nucleus".equals(selectedBuildingName)) {
            if (autoFloor) {
                newFloor += 1; // Bias for Nucleus G floor
            }
            if (newFloor >= 0 && newFloor < NUCLEUS_MAPS.size() && newFloor != this.currentFloor) {
                groundOverlay.setImage(BitmapDescriptorFactory.fromResource(NUCLEUS_MAPS.get(newFloor)));
                this.currentFloor = newFloor;
            }
        } else if ("Library".equals(selectedBuildingName)) {
            if (newFloor >= 0 && newFloor < LIBRARY_MAPS.size() && newFloor != this.currentFloor) {
                groundOverlay.setImage(BitmapDescriptorFactory.fromResource(LIBRARY_MAPS.get(newFloor)));
                this.currentFloor = newFloor;
            }
        }

        // Handle Murchison floor switching
        if ("Murchison".equals(selectedBuildingName) && murchisonFloorData != null) {
            String floorKey = "";
            switch (newFloor) {
                case -1: floorKey = "B1"; break;
                case 0: floorKey = "GF"; break;
                case 1: floorKey = "F1"; break;
                case 2: floorKey = "F2"; break;
                default: return;
            }
            drawFloor(floorKey);
            this.currentFloor = newFloor;
        }
    }

    public void increaseFloor() {
        this.setCurrentFloor(currentFloor + 1, false);
    }

    public void decreaseFloor() {
        this.setCurrentFloor(currentFloor - 1, false);
    }

    /**
     * Draws green polylines indicating the boundaries of buildings with available indoor maps.
     */
    public void setIndicationOfIndoorMap() {
        List<LatLng> points = BuildingPolygon.NUCLEUS_POLYGON;
        points.add(BuildingPolygon.NUCLEUS_POLYGON.get(0)); // Close loop
        gMap.addPolyline(new PolylineOptions().color(Color.GREEN).addAll(points));

        points = BuildingPolygon.LIBRARY_POLYGON;
        points.add(BuildingPolygon.LIBRARY_POLYGON.get(0)); // Close loop
        gMap.addPolyline(new PolylineOptions().color(Color.GREEN).addAll(points));
    }

    /**
     * Fetches floor plan data via POST request to OpenPositioning API.
     * Uses Master Key in URL and sends WiFi MAC addresses in body.
     */
    public void fetchFloorPlanFromApi(LatLng location) {
        new Thread(() -> {
            try {
                // 1. Prepare Keys
                String masterKey = "tkZ4QoAApy-6CBM6fKYwYA";
                String userKey = "ewireless";

                // 2. Construct URL
                String urlString = "https://openpositioning.org/api/live/floorplan/request/"
                        + masterKey + "?key=" + userKey;

                Log.d("IndoorMapAPI", "Target URL: " + urlString);

                // 3. Prepare JSON Body
                JSONObject jsonBody = new JSONObject();
                jsonBody.put("lat", location.latitude);
                jsonBody.put("lon", location.longitude);

                JSONArray macsArray = new JSONArray();
                List<Wifi> wifiList = SensorFusion.getInstance().getWifiList();
                if (wifiList != null) {
                    for (Wifi wifi : wifiList) {
                        macsArray.put(String.valueOf(wifi.getBssid()));
                    }
                }
                jsonBody.put("macs", macsArray);

                String requestBody = jsonBody.toString();
                Log.d("IndoorMapAPI", "Request Body: " + requestBody);

                // 4. Execute POST Request
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);
                conn.setDoInput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                Log.d("IndoorMapAPI", "Response Code: " + responseCode);

                if (responseCode == 200 || responseCode == 201) {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }

                    String jsonResponse = response.toString();
                    Log.d("IndoorMapAPI", "API Success Response: " + jsonResponse);

                    // Prevent UI flickering
                    this.isIndoorMapSet = true;

                    new Handler(Looper.getMainLooper()).post(() -> {
                        parseAndHandleApiResponse(jsonResponse);
                    });
                } else {
                    // Log error details
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    Log.e("IndoorMapAPI", "Failed. Code: " + responseCode + ", Error: " + errorResponse.toString());
                }

                conn.disconnect();

            } catch (Exception e) {
                Log.e("IndoorMapAPI", "Error fetching map", e);
            }
        }).start();
    }

    /**
     * Parses the API response and triggers the drawing of the map.
     * Expects a JSON array containing map shapes for Murchison.
     */
    private void parseAndHandleApiResponse(String jsonResponse) {
        try {
            org.json.JSONArray rootArray = new org.json.JSONArray(jsonResponse);
            if (rootArray.length() == 0) return;

            JSONObject buildingData = rootArray.getJSONObject(0);

            // Parse nested map_shapes string
            String mapShapesStr = buildingData.getString("map_shapes");
            JSONObject mapShapes = new JSONObject(mapShapesStr);

            Log.d("IndoorMapAPI", "Map Shapes Keys: " + mapShapes.keys().toString());

            // Cache data and draw initial floor (GF)
            this.murchisonFloorData = mapShapes;
            drawFloor("GF");

            this.isIndoorMapSet = true;
            this.selectedBuildingName = "Murchison";

        } catch (Exception e) {
            Log.e("IndoorMapAPI", "Error parsing response", e);
        }
    }
}
