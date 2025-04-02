package com.openpositioning.PositionMe;

import android.graphics.Color;
import android.util.Log;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.Arrays;
import java.util.List;

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
            R.drawable.floor_lg, R.drawable.floor_ug, R.drawable.floor_1,
            R.drawable.floor_2,R.drawable.floor_3);
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
        setBuildingOverlay();
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
        if (BuildingPolygon.inNucleus(currentLocation)){
            if (autoFloor) {
                // If nucleus add bias floor as lower-ground floor referred to as floor 0
                newFloor += 1;
            }
            // If within bounds and different from floor map currently being shown
            if (newFloor>=0 && newFloor<NUCLEUS_MAPS.size() && newFloor!=this.currentFloor) {
                //Special case for nucleus when auto-floor is being used

                groundOverlay.setImage(BitmapDescriptorFactory.fromResource(NUCLEUS_MAPS.get(newFloor)));
                this.currentFloor=newFloor;
            }
        }
        else if (BuildingPolygon.inLibrary(currentLocation)){
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
     * Sets the map overlay for the building if user's current
     * location is in building and is not already set
     * Removes the overlay if user no longer in building
     */
    private void setBuildingOverlay() {
        // Try catch block to prevent fatal crashes
        try {
            // Setting overlay if in Nucleus and not already set
            if (BuildingPolygon.inNucleus(currentLocation) && !isIndoorMapSet) {
                groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                        .image(BitmapDescriptorFactory.fromResource(R.drawable.floor_ug))
                        .positionFromBounds(NUCLEUS)
                        .zIndex(0f));
                isIndoorMapSet = true;
                // Nucleus has an LG floor so G floor is at index 1
                currentFloor=1;
                floorHeight=NUCLEUS_FLOOR_HEIGHT;
            }
            // Setting overlay if in Library and not already set
            else if (BuildingPolygon.inLibrary(currentLocation) && !isIndoorMapSet) {
                groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                        .image(BitmapDescriptorFactory.fromResource(R.drawable.libraryg))
                        .positionFromBounds(LIBRARY)
                        .zIndex(0f));
                isIndoorMapSet = true;
                currentFloor=0;
                floorHeight=LIBRARY_FLOOR_HEIGHT;
            }
            // Removing overlay if user no longer in area with indoor maps available
            else if (!BuildingPolygon.inLibrary(currentLocation) &&
                    !BuildingPolygon.inNucleus(currentLocation)&& isIndoorMapSet){
                groundOverlay.remove();
                isIndoorMapSet = false;
                currentFloor=0;
            }
        } catch (Exception ex) {
            Log.e("Error with overlay, Exception:", ex.toString());
        }
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
}