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
        this.gMap = map;
        this.currentFloor = 1; // 默认从G层开始
    }

    /**
     * Function to update the current location of user and display the indoor map
     * if user in building with indoor map available
     * @param currentLocation new location of user
     */
    public void setCurrentLocation(LatLng currentLocation){
        this.currentLocation = currentLocation;
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
        try {
            if (BuildingPolygon.inNucleus(currentLocation)){
                if (autoFloor) {
                    int mapIndex = newFloor + 1;
                    if (mapIndex >= 0 && mapIndex < NUCLEUS_MAPS.size()) {
                        groundOverlay.setImage(BitmapDescriptorFactory.fromResource(NUCLEUS_MAPS.get(mapIndex)));
                        this.currentFloor = newFloor;
                        Log.d("Floor Status", String.format(
                            "Auto Mode - Real Floor: %d, Showing Map: %s",
                            newFloor,
                            newFloor == -1 ? "LG" : newFloor == 0 ? "G" : String.valueOf(newFloor)
                        ));
                    }
                } else {
                    if (newFloor >= 0 && newFloor < NUCLEUS_MAPS.size()) {
                        groundOverlay.setImage(BitmapDescriptorFactory.fromResource(NUCLEUS_MAPS.get(newFloor)));
                        this.currentFloor = newFloor;
                        Log.d("Floor Status", String.format(
                            "Manual Mode - Selected Map: %s",
                            newFloor == 0 ? "LG" : newFloor == 1 ? "G" : String.valueOf(newFloor-1)
                        ));
                    }
                }
            }
            else if (BuildingPolygon.inLibrary(currentLocation)){
                if (newFloor >= 0 && newFloor < LIBRARY_MAPS.size()) {
                    groundOverlay.setImage(BitmapDescriptorFactory.fromResource(LIBRARY_MAPS.get(newFloor)));
                    this.currentFloor = newFloor;
                    Log.d("Floor Change", "Library: Changed to floor " + newFloor);
                }
            }
        } catch (Exception ex) {
            Log.e("SetFloor Error:", ex.toString());
        }
    }

    /**
     * 重新启用自动楼层时，立即更新到当前实际楼层
     * @param actualFloor 当前实际楼层
     */
    public void resumeAutoFloor(int actualFloor) {
        Log.d("Floor Status", "Resuming Auto Floor - Actual Floor: " + actualFloor);
        setCurrentFloor(actualFloor, true);
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
        try {
            if (BuildingPolygon.inNucleus(currentLocation)) {
                if (!isIndoorMapSet) {
                    currentFloor = 1;
                    groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                            .image(BitmapDescriptorFactory.fromResource(NUCLEUS_MAPS.get(currentFloor)))
                            .positionFromBounds(NUCLEUS));
                    isIndoorMapSet = true;
                    floorHeight = NUCLEUS_FLOOR_HEIGHT;
                    Log.d("Overlay", "Nucleus: Initial overlay set to floor " + currentFloor);
                }
            }
            else if (BuildingPolygon.inLibrary(currentLocation)) {
                if (!isIndoorMapSet) {
                    currentFloor = 0;
                    groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                            .image(BitmapDescriptorFactory.fromResource(LIBRARY_MAPS.get(currentFloor)))
                            .positionFromBounds(LIBRARY));
                    isIndoorMapSet = true;
                    floorHeight = LIBRARY_FLOOR_HEIGHT;
                    Log.d("Overlay", "Library: Initial overlay set to floor " + currentFloor);
                }
            }
            else if (!BuildingPolygon.inLibrary(currentLocation) &&
                    !BuildingPolygon.inNucleus(currentLocation) && isIndoorMapSet){
                groundOverlay.remove();
                isIndoorMapSet = false;
                currentFloor = 0;
                Log.d("Overlay", "Removed overlay");
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
