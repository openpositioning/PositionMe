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

/**
 * Class used to manage indoor floor map overlays
 * Currently used by RecordingFragment
 * @see BuildingPolygon Describes the bounds of buildings and the methods to check if point is
 * in the building
 * @author Arun Gopalakrishnan
 */
public class IndoorMapManager {
    // To store the map instance
    private GoogleMap gMap;
    // Stores the name of the currently selected building (e.g., "Nucleus", "Library")
    private String selectedBuildingName = "";
    //Stores the overlay of the indoor maps
    private GroundOverlay groundOverlay;
    // Stores the current Location of user
    private LatLng currentLocation;
    // Stores if indoor map overlay is currently set
    private boolean isIndoorMapSet=false;
    //Stores the current floor in building
    private int currentFloor;
    // Floor height of current building
    private float floorHeight;
    //Images of the Nucleus Building and Library indoor floor maps
    private final List<Integer> NUCLEUS_MAPS =Arrays.asList(
            R.drawable.nucleuslg, R.drawable.nucleusg, R.drawable.nucleus1,
            R.drawable.nucleus2,R.drawable.nucleus3);
    private final List<Integer> LIBRARY_MAPS =Arrays.asList(
            R.drawable.libraryg, R.drawable.library1, R.drawable.library2,
            R.drawable.library3);
    // South-west and north east Bounds of Nucleus building and library to set the Overlay
    LatLngBounds NUCLEUS=new LatLngBounds(
            BuildingPolygon.NUCLEUS_SW,
            BuildingPolygon.NUCLEUS_NE
    );
    LatLngBounds LIBRARY=new LatLngBounds(
            BuildingPolygon.LIBRARY_SW,
            BuildingPolygon.LIBRARY_NE
    );
    //Average Floor Heights of the Buildings
    public static final float NUCLEUS_FLOOR_HEIGHT=4.2F;
    public static final float LIBRARY_FLOOR_HEIGHT=3.6F;

    /**
     * Constructor to set the map instance
     * @param map The map on which the indoor floor map overlays are set
     */
    public IndoorMapManager(GoogleMap map){
        this.gMap=map;
    }

    /**
     * Function to update the current location of user and display the indoor map
     * if user in building with indoor map available
     * @param currentLocation new location of user
     */
    public void setCurrentLocation(LatLng currentLocation){
        this.currentLocation=currentLocation;
        //setBuildingOverlay();
    }

    /**
     * [Objective d] Manually select a building to display its indoor map.
     * This is called when the user clicks on a building polygon.
     * @param buildingName The name of the building (Tag from the polygon).
     * @return true if the map was successfully loaded, false otherwise.
     */
    public boolean selectBuilding(String buildingName) {
        if (gMap == null) return false;

        if (isIndoorMapSet && selectedBuildingName.equals(buildingName)) {
            return true;
        }

        // remove old Overlay
        if (groundOverlay != null) {
            groundOverlay.remove();
            groundOverlay = null;
        }
        isIndoorMapSet = false;
        selectedBuildingName = buildingName;

        try {
            if ("Nucleus".equals(buildingName)) {
                //  Nucleus G floor (default)
                groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                        .image(BitmapDescriptorFactory.fromResource(R.drawable.nucleusg))
                        .positionFromBounds(NUCLEUS)
                        .zIndex(1));
                isIndoorMapSet = true;
                currentFloor = 1; // G floor index in list
                floorHeight = NUCLEUS_FLOOR_HEIGHT;
                return true;
            }
            else if ("Library".equals(buildingName)) {
                //  Library G floor
                groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                        .image(BitmapDescriptorFactory.fromResource(R.drawable.libraryg))
                        .positionFromBounds(LIBRARY)
                        .zIndex(1));
                isIndoorMapSet = true;
                currentFloor = 0; // G floor index
                floorHeight = LIBRARY_FLOOR_HEIGHT;
                return true;
            }
            // Murchison and FJB
            else {
                Log.d("IndoorMapManager", "No indoor map available for: " + buildingName);
                return false;
            }
        } catch (Exception e) {
            Log.e("IndoorMapManager", "Error loading map for " + buildingName, e);
            return false;
        }
    }

    /**
     * [Objective d] Clear the current building overlay and reset selection.
     */
    public void deselectBuilding() {
        if (groundOverlay != null) {
            groundOverlay.remove();
            groundOverlay = null;
        }
        isIndoorMapSet = false;
        selectedBuildingName = "";
        currentFloor = 0;
    }

    public String getSelectedBuilding() {
        return selectedBuildingName;
    }
    /**
     * Function to obtain the current building's floor height
     * @return the floor height of the current building the user is in
     */
    public float getFloorHeight() {
        return floorHeight;
    }

    /**
     * Getter to obtain if currently an indoor floor map is being displayed
     * @return true if an indoor map is visible to the user, false otherwise
     */
    public boolean getIsIndoorMapSet(){
        return isIndoorMapSet;
    }

    /**
     * Setting the new floor of a user and displaying the indoor floor map accordingly
     * (if floor exists in building)
     * @param newFloor the floor the user is at
     * @param autoFloor flag if function called by auto-floor feature
     */
    public void setCurrentFloor(int newFloor, boolean autoFloor) {
        if ("Nucleus".equals(selectedBuildingName)){
            //Special case for nucleus when auto-floor is being used
            if (autoFloor) {
                // If nucleus add bias floor as lower-ground floor referred to as floor 0
                newFloor += 1;
            }
            // If within bounds and different from floor map currently being shown
             if (newFloor>=0 && newFloor<NUCLEUS_MAPS.size() && newFloor!=this.currentFloor) {
                 groundOverlay.setImage(BitmapDescriptorFactory.fromResource(NUCLEUS_MAPS.get(newFloor)));
                 this.currentFloor=newFloor;
             }
        }
        else if ("Library".equals(selectedBuildingName)){
            // If within bounds and different from floor map currently being shown
            if (newFloor>=0 && newFloor<LIBRARY_MAPS.size() && newFloor!=this.currentFloor) {
                groundOverlay.setImage(BitmapDescriptorFactory.fromResource(LIBRARY_MAPS.get(newFloor)));
                this.currentFloor=newFloor;
            }
        }

    }

    /**
     * Increments the Current Floor and changes to higher floor's map (if a higher floor exists)
     */
    public void increaseFloor(){
        this.setCurrentFloor(currentFloor+1,false);
    }

    /**
     * Decrements the Current Floor and changes to the lower floor's map (if a lower floor exists)
     */
    public void decreaseFloor(){
        this.setCurrentFloor(currentFloor-1,false);
    }

    /**
     * Function used to set the indication of available floor maps for building using green Polylines
     * along the building's boundaries.
     */
    public void setIndicationOfIndoorMap(){
        //Indicator for Nucleus Building
        List<LatLng> points=BuildingPolygon.NUCLEUS_POLYGON;
        // Closing Boundary
        points.add(BuildingPolygon.NUCLEUS_POLYGON.get(0));
        gMap.addPolyline(new PolylineOptions().color(Color.GREEN)
                .addAll(points));

        // Indicator for the Library Building
        points=BuildingPolygon.LIBRARY_POLYGON;
        // Closing Boundary
        points.add(BuildingPolygon.LIBRARY_POLYGON.get(0));
        gMap.addPolyline(new PolylineOptions().color(Color.GREEN)
                .addAll(points));
    }
    /**
     * [Objective d] Try to download floorplan from API.
     * API Endpoint: https://openpositioning.org/api/live/floorplan/request
     */
    public void fetchFloorPlanFromApi(LatLng location) {
        // internet asking
        new Thread(() -> {
            try {
                String urlString = "https://openpositioning.org/api/live/floorplan/request" +
                        "?latitude=" + location.latitude +
                        "&longitude=" + location.longitude;

                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("GET");

                conn.setRequestProperty("Accept", "application/json");
                // API Key
                conn.setRequestProperty("x-api-key", "tkZ4QoAApy-6CBM6fKYwYA");

                conn.setDoOutput(false);
                conn.setDoInput(true);

                Log.d("IndoorMapAPI", "Sending GET request to: " + urlString);

                //response
                int responseCode = conn.getResponseCode();
                Log.d("IndoorMapAPI", "Response Code: " + responseCode);

                if (responseCode == 200) {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "utf-8"));
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }

                    String jsonResponse = response.toString();
                    Log.d("IndoorMapAPI", "Response Data: " + jsonResponse);

                    new Handler(Looper.getMainLooper()).post(() -> {
                        parseAndHandleApiResponse(jsonResponse);
                    });
                } else {
                    Log.e("IndoorMapAPI", "Failed to fetch map. Code: " + responseCode);
                }

                conn.disconnect();

            } catch (Exception e) {
                Log.e("IndoorMapAPI", "Error fetching map", e);
            }
        }).start();
    }
    private void parseAndHandleApiResponse(String jsonResponse) {
        try {
            JSONObject json = new JSONObject(jsonResponse);

            Log.d("IndoorMapAPI", "Parsing JSON: " + jsonResponse);

        } catch (Exception e) {
            Log.e("IndoorMapAPI", "Error parsing response", e);
        }
    }
}
