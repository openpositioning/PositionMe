package com.openpositioning.PositionMe.fragments;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;

//  * @author Apoorv Tewari
// * @author Batu Bayram



public class FloorOverlayManager {
    // Variables to control visibility and manual selection of floor overlays
    public boolean manualSelectionActive = false;
    public static boolean groundFloorVisible;
    public static boolean librarygroundfloorvisible;
    public static boolean firstFloorVisible;
    public static boolean libraryfirstfloorvisible;
    public static boolean secondFloorVisible;
    public static boolean librarysecondfloorvisible;
    public static boolean thirdFloorVisible;
    public static boolean librarythirdfloorvisible;
    public static boolean isUserNearGroundFloor;
    public static boolean isuserNearGroundFloorLibrary;

    // Google Maps instance and overlay objects for each floor
    private GoogleMap mMap;
    public static LatLng southwestcornerNucleus;
    public static LatLng northeastcornerNucleus;
    public static LatLng southwestcornerLibrary;
    public static LatLng northeastcornerLibrary;
    private Floor userSelectedFloor = null; // null indicates no floor has been manually selected

    // GroundOverlay instances for each floor in the nucleus and the library
    public GroundOverlay groundflooroverlay;
    public GroundOverlay firstflooroverlay;
    public GroundOverlay secondflooroverlay;
    public GroundOverlay thirdflooroverlay;
    public GroundOverlay librarygroundflooroverlay;
    public GroundOverlay libraryfirstflooroverlay;
    public GroundOverlay librarysecondflooroverlay;
    public GroundOverlay librarythirdflooroverlay;

    // LatLngBounds for the Nucleus and the Library buildings
    public static LatLngBounds buildingBounds; // Building bounds for the Nucleus
    public static LatLngBounds buildingBoundsLibrary; // Building bounds for the Library

    private MapManager mapManager;
    private SensorFusion sensorFusion;

    // Define elevation thresholds for each floor level
    private final float GROUND_FLOOR_MAX_ELEVATION = 4.2f;
    private final float FIRST_FLOOR_MAX_ELEVATION = 8.6f;
    private final float SECOND_FLOOR_MAX_ELEVATION = 12.8f;

    // Current floor level for comparison during updates
    private Floor currentFloor = Floor.GROUND;
    /**
     * Constructor for FloorOverlayManager.
     * @param mMap The GoogleMap instance.
     * @param mapManager Instance of the custom MapManager class.
     * @param sensorFusion Instance of SensorFusion for accessing sensor data.
     */

    public FloorOverlayManager(GoogleMap mMap, MapManager mapManager, SensorFusion sensorFusion) {

        this.mMap = mMap;
        this.mapManager = mapManager;
        this.sensorFusion = sensorFusion;
        checkAndUpdateFloorOverlay(); //To Update the map and display the current floor based on the elevation
    }

    /**
     * Allows the user to manually select a floor, overriding the automatic floor detection.
     * @param floor The Floor enum representing the user-selected floor.
     */
    public void setUserSelectedFloor(Floor floor) {
        this.userSelectedFloor = floor;
        this.manualSelectionActive = (floor != null); // Enable manual selection if a floor is selected, disable otherwise for automatic detection
        updateFloorOverlaysBasedOnUserSelection();
    }

    /**
     * Updates the floor overlays based on the user's manual selection.
     */

    private void updateFloorOverlaysBasedOnUserSelection() {

        setFloorVisibility(userSelectedFloor);
    }



    /**
     * Sets the visibility of floor overlays based on the specified floor.
     * @param floor The floor whose overlay should be visible.
     */
    private void setFloorVisibility(Floor floor) {

        if (floor == null) {
            // Handle case where no floor is selected, possibly by hiding all overlays or showing a default view
            return;  // Exit the method early
        }
        // Hide all overlays initially
        setFloorVisibility(false, false, false, false);

        // Show only the selected floor overlay
        switch (floor) {
            case AUTOMATIC:
                break;
            case GROUND:
                if (groundflooroverlay != null) groundflooroverlay.setVisible(true);
                if (librarygroundflooroverlay != null) librarygroundflooroverlay.setVisible(true);
                break;
            case FIRST:
                if (firstflooroverlay != null) firstflooroverlay.setVisible(true);
                if (libraryfirstflooroverlay != null) libraryfirstflooroverlay.setVisible(true);
                break;
            case SECOND:
                if (secondflooroverlay != null) secondflooroverlay.setVisible(true);
                if (librarysecondflooroverlay != null) librarysecondflooroverlay.setVisible(true);
                break;
            case THIRD:
                if (thirdflooroverlay != null) thirdflooroverlay.setVisible(true);
                if (librarythirdflooroverlay != null) librarythirdflooroverlay.setVisible(true);
                break;
            default:
                // Handle case where no floor is selected (possibly revert to automatic detection)
                break;
        }
    }
    /**
     * Checks if the user is within the bounds of the building and updates the floor overlay accordingly.
     */
    public void checkUserInbounds(){
        float[] gnssLocation = sensorFusion.getGNSSLatitude(false); // Assume you have a method to get the current user location as a LatLng
        LatLng gnssLatLng = new LatLng(gnssLocation[0], gnssLocation[1]);
        // Check if the user is outside the building bounds
        if (!buildingBounds.contains(gnssLatLng) && !buildingBoundsLibrary.contains(gnssLatLng)) {
            hideAllOverlays(); // Hide all overlays if the user is outside
        }
    }

    /**
     * Periodically or in response to specific events, checks and updates the floor overlay based on the current elevation or user selection.
     */
    public void checkAndUpdateFloorOverlay() {

        if (!manualSelectionActive) {
            // Automatic mode
            float currentElevation = sensorFusion.getElevation();
            Floor targetFloor = determineFloorByElevation(currentElevation);
            setFloorVisibility(targetFloor);
        } else {
            // Manual mode
            setFloorVisibility(userSelectedFloor);
        }
    }


    // Helper method to determine the floor based on elevation

    /**
     * Determines the floor level based on the current elevation.
     * @param elevation The current elevation of the user.
     * @return The floor level as a Floor enum.
     */
    private Floor determineFloorByElevation(float elevation) {

        if (elevation <= GROUND_FLOOR_MAX_ELEVATION) {
            librarygroundfloorvisible=true;
            groundFloorVisible = true;
            return Floor.GROUND;
        } else if (elevation <= FIRST_FLOOR_MAX_ELEVATION) {
            libraryfirstfloorvisible=true;
            firstFloorVisible = true;
            return Floor.FIRST;
        } else if (elevation <= SECOND_FLOOR_MAX_ELEVATION) {
            librarysecondfloorvisible=true;
            secondFloorVisible = true;
            return Floor.SECOND;
        } else {
            librarythirdfloorvisible=true;
            thirdFloorVisible = true;
            return Floor.THIRD;
        }
    }


    /**
     * Sets up the ground overlays for the nucleus and library floors.
     */
    public void setupGroundOverlays() {

        defineOverlayBounds();
        createAndAddOverlays();
    }

    /**
     * Creates and adds overlays for each floor in the nucleus and the library.
     */
    private void createAndAddOverlays() {
        // Nucleus Overlays
        groundflooroverlay = addOverlay(R.drawable.nucleusg, buildingBounds);
        firstflooroverlay = addOverlay(R.drawable.nucleus1, buildingBounds);
        secondflooroverlay = addOverlay(R.drawable.nucleus2, buildingBounds);
        thirdflooroverlay = addOverlay(R.drawable.nucleus3, buildingBounds);

        // Library Overlays
        librarygroundflooroverlay = addOverlay(R.drawable.libraryg, buildingBoundsLibrary);
        libraryfirstflooroverlay = addOverlay(R.drawable.library1, buildingBoundsLibrary);
        librarysecondflooroverlay = addOverlay(R.drawable.library2, buildingBoundsLibrary);
        librarythirdflooroverlay = addOverlay(R.drawable.library3, buildingBoundsLibrary);

    }

    // Method to adjust the visibility of overlays
    /**
     * Adjusts the visibility of overlays for each floor.
     * @param ground Visibility for the ground floor overlay.
     * @param first Visibility for the first floor overlay.
     * @param second Visibility for the second floor overlay.
     * @param third Visibility for the third floor overlay.
     */
    public void setFloorVisibility(boolean ground, boolean first, boolean second, boolean third) {

        if (isUserNearGroundFloor || isuserNearGroundFloorLibrary) {
            if (groundflooroverlay != null) groundflooroverlay.setVisible(ground);
            if (firstflooroverlay != null) firstflooroverlay.setVisible(first);
            if (secondflooroverlay != null) secondflooroverlay.setVisible(second);
            if (thirdflooroverlay != null) thirdflooroverlay.setVisible(third);
            if (librarygroundflooroverlay != null) librarygroundflooroverlay.setVisible(ground);
            if (libraryfirstflooroverlay != null) libraryfirstflooroverlay.setVisible(first);
            if (librarysecondflooroverlay != null) librarysecondflooroverlay.setVisible(second);
            if (librarythirdflooroverlay != null) librarythirdflooroverlay.setVisible(third);
        }
    }

    /**
     * Defines the overlay bounds for the nucleus and the library buildings.
     */
    private void defineOverlayBounds() {

        // Initialize LatLng for corners
        southwestcornerNucleus = new LatLng(55.92278, -3.17465);
        northeastcornerNucleus = new LatLng(55.92335, -3.173842);
        southwestcornerLibrary = new LatLng(55.922738, -3.17517);
        northeastcornerLibrary = new LatLng(55.923061, -3.174764);

        // Initialize LatLngBounds
        buildingBounds = new LatLngBounds(southwestcornerNucleus, northeastcornerNucleus);
        buildingBoundsLibrary = new LatLngBounds(southwestcornerLibrary, northeastcornerLibrary);
    }

    /**
     * Adds an overlay to the map for a specific resource and bound.
     * @param resourceId The drawable resource ID for the overlay image.
     * @param bounds The bounds where the overlay should be applied.
     * @return The GroundOverlay instance added to the map.
     */

    public GroundOverlay addOverlay(int resourceId, LatLngBounds bounds) {

        return mMap.addGroundOverlay(new GroundOverlayOptions()
                .image(BitmapDescriptorFactory.fromResource(resourceId))
                .positionFromBounds(bounds)
                .transparency(0.5f)); // Adjust transparency as needed
    }

    /**
     * Hides all overlays.
     */
    public void hideAllOverlays() {
        setFloorVisibility(false, false, false, false);
        if (groundflooroverlay != null) groundflooroverlay.setVisible(false);
        if (firstflooroverlay != null) firstflooroverlay.setVisible(false);
        if (secondflooroverlay != null) secondflooroverlay.setVisible(false);
        if (thirdflooroverlay != null) thirdflooroverlay.setVisible(false);
        if (librarygroundflooroverlay != null) librarygroundflooroverlay.setVisible(false);
        if (libraryfirstflooroverlay != null) libraryfirstflooroverlay.setVisible(false);
        if (librarysecondflooroverlay != null) librarysecondflooroverlay.setVisible(false);
        if (librarythirdflooroverlay != null) librarythirdflooroverlay.setVisible(false);
    }

    // Floor enumeration to represent different floor levels
    public static enum Floor {
        AUTOMATIC, GROUND, FIRST, SECOND, THIRD
    }
}
