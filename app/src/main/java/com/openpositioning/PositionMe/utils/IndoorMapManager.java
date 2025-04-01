package com.openpositioning.PositionMe.utils;

import android.graphics.Color;
import android.location.Location;
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
    //Images of the Nucleus Building, Library, Fleeming jenkin, Hudson Beare, and Sanderson building indoor floor maps
    private final List<Integer> NUCLEUS_MAPS =Arrays.asList(
            R.drawable.nucleuslg, R.drawable.nucleusg, R.drawable.nucleus1,
            R.drawable.nucleus2,R.drawable.nucleus3);
    private final List<Integer> LIBRARY_MAPS =Arrays.asList(
            R.drawable.libraryg, R.drawable.library1, R.drawable.library2,
            R.drawable.library3);
    private final List<Integer> FLEEMING_MAPS =Arrays.asList(
            R.drawable.fj_floor_g, R.drawable.fj_floor_1);
    private final List<Integer> HUDSON_MAPS =Arrays.asList(
            R.drawable.hb_floor_1, R.drawable.hb_floor_2);
    private final List<Integer> SANDERSON_MAPS =Arrays.asList(
            R.drawable.s_floor_g, R.drawable.s_floor_1, R.drawable.s_floor_2);
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
    public static final float FLEEMING_FLOOR_HEIGHT=3.6F;
    public static final float HUDSON_FLOOR_HEIGHT=3.6F;
    public static final float SANDERSON_FLOOR_HEIGHT=3.6F;

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
        else if (BuildingPolygon.inLibrary(currentLocation)){
            // If within bounds and different from floor map currently being shown
            if (newFloor>=0 && newFloor<LIBRARY_MAPS.size() && newFloor!=this.currentFloor) {
                groundOverlay.setImage(BitmapDescriptorFactory.fromResource(LIBRARY_MAPS.get(newFloor)));
                this.currentFloor=newFloor;
            }
        }
        else if (BuildingPolygon.inFleeming(currentLocation)){
            // If within bounds and different from floor map currently being shown
            if (newFloor>=0 && newFloor<FLEEMING_MAPS.size() && newFloor!=this.currentFloor) {
                groundOverlay.setImage(BitmapDescriptorFactory.fromResource(FLEEMING_MAPS.get(newFloor)));
                this.currentFloor=newFloor;
            }
        }
        else if (BuildingPolygon.inHudson(currentLocation)){
            // If within bounds and different from floor map currently being shown
            if (newFloor>=0 && newFloor<HUDSON_MAPS.size() && newFloor!=this.currentFloor) {
                groundOverlay.setImage(BitmapDescriptorFactory.fromResource(HUDSON_MAPS.get(newFloor)));
                this.currentFloor=newFloor;
            }
        }
        else if (BuildingPolygon.inSanderson(currentLocation)){
            // If within bounds and different from floor map currently being shown
            if (newFloor>=0 && newFloor<SANDERSON_MAPS.size() && newFloor!=this.currentFloor) {
                groundOverlay.setImage(BitmapDescriptorFactory.fromResource(SANDERSON_MAPS.get(newFloor)));
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
                            .image(BitmapDescriptorFactory.fromResource(R.drawable.nucleusg))
                            .positionFromBounds(NUCLEUS));
                    isIndoorMapSet = true;
                    // Nucleus has an LG floor so G floor is at index 1
                    currentFloor=1;
                    floorHeight=NUCLEUS_FLOOR_HEIGHT;
            }
            // Setting overlay if in Library and not already set
            else if (BuildingPolygon.inLibrary(currentLocation) && !isIndoorMapSet) {
                    groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                            .image(BitmapDescriptorFactory.fromResource(R.drawable.libraryg))
                            .positionFromBounds(LIBRARY));
                    isIndoorMapSet = true;
                    currentFloor=0;
                    floorHeight=LIBRARY_FLOOR_HEIGHT;
            }
            // Setting overlay if in Fleeming Jenkin building and not already set
            else if (BuildingPolygon.inFleeming(currentLocation) && !isIndoorMapSet) {
                // Calculate the center point of the overlay
                double fj_centerLat = (BuildingPolygon.FLEEMING_NW.latitude + BuildingPolygon.FLEEMING_SE.latitude) / 2;
                double fj_centerLng = (BuildingPolygon.FLEEMING_NW.longitude + BuildingPolygon.FLEEMING_SE.longitude) / 2;

                // Calculate width and height of the overlay in meters
                float[] fj_resultWidth = new float[1];
                float[] fj_resultHeight = new float[1];
                Location.distanceBetween(BuildingPolygon.FLEEMING_NW.latitude, BuildingPolygon.FLEEMING_NW.longitude, BuildingPolygon.FLEEMING_NE.latitude, BuildingPolygon.FLEEMING_NE.longitude, fj_resultWidth); // Width
                Location.distanceBetween(BuildingPolygon.FLEEMING_NW.latitude, BuildingPolygon.FLEEMING_NW.longitude, BuildingPolygon.FLEEMING_SW.latitude, BuildingPolygon.FLEEMING_SW.longitude, fj_resultHeight); // Height
                LatLng center = new LatLng(fj_centerLat, fj_centerLng);

                // Calculate the bearing (angle of rotation) based on the diagonal (NW to SE)
                double fj_bearing = Math.toDegrees(Math.atan2(BuildingPolygon.FLEEMING_SW.longitude - BuildingPolygon.FLEEMING_NW.longitude, BuildingPolygon.FLEEMING_SW.latitude - BuildingPolygon.FLEEMING_NW.latitude));
                groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                        .image(BitmapDescriptorFactory.fromResource(R.drawable.fj_floor_g))
                        .position(center, fj_resultWidth[0], fj_resultHeight[0]) // Center and dimensions in meters
                        .bearing(-80-(float)fj_bearing)); // Position overlay
                isIndoorMapSet = true;
                currentFloor = 0;
                floorHeight = FLEEMING_FLOOR_HEIGHT;
            }
            // Setting overlay if in Hudson Beare building and not already set
            else if (BuildingPolygon.inHudson(currentLocation) && !isIndoorMapSet) {
                // Calculate the center point of the overlay
                double hb_centerLat = (BuildingPolygon.HUDSON_NW.latitude + BuildingPolygon.HUDSON_SE.latitude) / 2;
                double hb_centerLng = (BuildingPolygon.HUDSON_NW.longitude + BuildingPolygon.HUDSON_SE.longitude) / 2;

                // Calculate width and height of the overlay in meters
                float[] hb_resultWidth = new float[1];
                float[] hb_resultHeight = new float[1];
                Location.distanceBetween(BuildingPolygon.HUDSON_NW.latitude, BuildingPolygon.HUDSON_NW.longitude, BuildingPolygon.HUDSON_NE.latitude, BuildingPolygon.HUDSON_NE.longitude, hb_resultWidth); // Width
                Location.distanceBetween(BuildingPolygon.HUDSON_NW.latitude, BuildingPolygon.HUDSON_NW.longitude, BuildingPolygon.HUDSON_SW.latitude, BuildingPolygon.HUDSON_SW.longitude, hb_resultHeight); // Height
                LatLng center = new LatLng(hb_centerLat, hb_centerLng);

                // Calculate the bearing (angle of rotation) based on the diagonal (NW to SE)
                double hb_bearing = Math.toDegrees(Math.atan2(BuildingPolygon.HUDSON_SW.longitude - BuildingPolygon.HUDSON_NW.longitude, BuildingPolygon.HUDSON_SW.latitude - BuildingPolygon.HUDSON_NW.latitude));
                groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                        .image(BitmapDescriptorFactory.fromResource(R.drawable.hb_floor_1))
                        .position(center, hb_resultWidth[0], hb_resultHeight[0]) // Center and dimensions in meters
                        .bearing(-87-(float)hb_bearing)); // Position overlay
                isIndoorMapSet = true;
                currentFloor = 0;
                floorHeight = HUDSON_FLOOR_HEIGHT;
            }
            // Setting overlay if in Hudson Beare building and not already set
            else if (BuildingPolygon.inSanderson(currentLocation) && !isIndoorMapSet) {
                // Calculate the center point of the overlay
                double s_centerLat = (BuildingPolygon.SANDERSON_NW.latitude + BuildingPolygon.SANDERSON_SE.latitude) / 2;
                double s_centerLng = (BuildingPolygon.SANDERSON_NW.longitude + BuildingPolygon.SANDERSON_SE.longitude) / 2;

                // Calculate width and height of the overlay in meters
                float[] s_resultWidth = new float[1];
                float[] s_resultHeight = new float[1];
                Location.distanceBetween(BuildingPolygon.SANDERSON_NW.latitude, BuildingPolygon.SANDERSON_NW.longitude, BuildingPolygon.SANDERSON_NE.latitude, BuildingPolygon.SANDERSON_NE.longitude, s_resultWidth); // Width
                Location.distanceBetween(BuildingPolygon.SANDERSON_NW.latitude, BuildingPolygon.SANDERSON_NW.longitude, BuildingPolygon.SANDERSON_SW.latitude, BuildingPolygon.SANDERSON_SW.longitude, s_resultHeight); // Height
                LatLng center = new LatLng(s_centerLat, s_centerLng);

                // Calculate the bearing (angle of rotation) based on the diagonal (NW to SE)
                double s_bearing = Math.toDegrees(Math.atan2(BuildingPolygon.SANDERSON_SW.longitude - BuildingPolygon.SANDERSON_NW.longitude, BuildingPolygon.SANDERSON_SW.latitude - BuildingPolygon.SANDERSON_NW.latitude));
                groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                        .image(BitmapDescriptorFactory.fromResource(R.drawable.s_floor_g))
                        .position(center, s_resultWidth[0], s_resultHeight[0]) // Center and dimensions in meters
                        .bearing(-83-(float)s_bearing)); // Position overlay
                isIndoorMapSet = true;
                currentFloor = 0;
                floorHeight = SANDERSON_FLOOR_HEIGHT;
            }
            // Removing overlay if user no longer in area with indoor maps available
            else if (!BuildingPolygon.inLibrary(currentLocation) &&
                    !BuildingPolygon.inNucleus(currentLocation)&& !BuildingPolygon.inFleeming(currentLocation)&& !BuildingPolygon.inHudson(currentLocation)&& !BuildingPolygon.inSanderson(currentLocation)&& isIndoorMapSet){
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

        // Indicator for the Fleeming Jenkins Building
        points=BuildingPolygon.FLEEMING_POLYGON;
        // Closing Boundary
        points.add(BuildingPolygon.FLEEMING_POLYGON.get(0));
        gMap.addPolyline(new PolylineOptions().color(Color.GREEN)
                .addAll(points));

        // Indicator for the Hudson Beare Building
        points=BuildingPolygon.HUDSON_POLYGON;
        // Closing Boundary
        points.add(BuildingPolygon.HUDSON_POLYGON.get(0));
        gMap.addPolyline(new PolylineOptions().color(Color.GREEN)
                .addAll(points));

        // Indicator for the Hudson Beare Building
        points=BuildingPolygon.SANDERSON_POLYGON;
        // Closing Boundary
        points.add(BuildingPolygon.SANDERSON_POLYGON.get(0));
        gMap.addPolyline(new PolylineOptions().color(Color.GREEN)
                .addAll(points));
    }
}
