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

/**
 * Class used to manage indoor floor map overlays
 * Currently used by TrajectoryMapFragment to display indoor maps
 * @see BuildingPolygon Describes the bounds of buildings and the methods to check if point is
 * in the building
 * @author Arun Gopalakrishnan
 * @author Shu Gu
 * @version 1.1 - Bug fix for not updating the overlay if the location jumps from one building to another.
 */
public class IndoorMapManager {
    // Map instance and overlay
    private GoogleMap gMap;
    private GroundOverlay groundOverlay;
    // Current user location
    private LatLng currentLocation;
    // Indicates if an indoor map overlay is currently set
    private boolean isIndoorMapSet = false;
    // Current floor and floor height
    private int currentFloor;
    private float floorHeight;
    // NEW: Track which building's overlay is currently shown ("nucleus", "library", or empty)
    private String currentBuilding = "";

    // Indoor map resource lists for Nucleus and Library
    private final List<Integer> NUCLEUS_MAPS = Arrays.asList(
            R.drawable.nucleuslg, R.drawable.nucleusg, R.drawable.nucleus1,
            R.drawable.nucleus2, R.drawable.nucleus3);
    private final List<Integer> LIBRARY_MAPS = Arrays.asList(
            R.drawable.libraryg, R.drawable.library1, R.drawable.library2,
            R.drawable.library3);

    // Building bounds for overlay positioning
    LatLngBounds NUCLEUS = new LatLngBounds(
            BuildingPolygon.NUCLEUS_SW,
            BuildingPolygon.NUCLEUS_NE
    );
    LatLngBounds LIBRARY = new LatLngBounds(
            BuildingPolygon.LIBRARY_SW,
            BuildingPolygon.LIBRARY_NE
    );

    // Average floor heights for each building
    public static final float NUCLEUS_FLOOR_HEIGHT = 4.2F;
    public static final float LIBRARY_FLOOR_HEIGHT = 3.6F;

    public IndoorMapManager(GoogleMap map) {
        this.gMap = map;
    }

    /**
     * Updates the current location and sets the appropriate building overlay.
     *
     * @param currentLocation New location of the user.
     */
    public void setCurrentLocation(LatLng currentLocation) {
        this.currentLocation = currentLocation;
        setBuildingOverlay();
    }

    /**
     * Returns the floor height of the current building.
     */
    public float getFloorHeight() {
        return floorHeight;
    }

    /**
     * Returns true if an indoor map overlay is visible, false otherwise.
     */
    public boolean getIsIndoorMapSet() {
        return isIndoorMapSet;
    }

    /**
     * Sets the current floor and updates the indoor map overlay image.
     *
     * @param newFloor  The new floor the user is on.
     * @param autoFloor True if this change comes from an auto-floor feature.
     */
    public void setCurrentFloor(int newFloor, boolean autoFloor) {
        if ("nucleus".equals(currentBuilding)) {
            // Special case for Nucleus: auto-floor adds a bias (e.g., lower-ground is floor 0)
            if (autoFloor) {
                newFloor += 1;
            }
            if (newFloor >= 0 && newFloor < NUCLEUS_MAPS.size() && newFloor != this.currentFloor) {
                groundOverlay.setImage(BitmapDescriptorFactory.fromResource(NUCLEUS_MAPS.get(newFloor)));
                this.currentFloor = newFloor;
            }
        } else if ("library".equals(currentBuilding)) {
            if (newFloor >= 0 && newFloor < LIBRARY_MAPS.size() && newFloor != this.currentFloor) {
                groundOverlay.setImage(BitmapDescriptorFactory.fromResource(LIBRARY_MAPS.get(newFloor)));
                this.currentFloor = newFloor;
            }
        }
    }

    // get current floor - return current floor
    public int getCurrentFloor() {
        return this.currentFloor;
    }

    // get current building - return name of current building / int represent
    public String getCurrentBuilding() {
        return this.currentBuilding;
    }

    public void increaseFloor() {
        this.setCurrentFloor(currentFloor + 1, false);
    }

    public void decreaseFloor() {
        this.setCurrentFloor(currentFloor - 1, false);
    }

    /**
     * Sets or updates the building overlay based on the user's current location.
     * If the user jumps from one building to another, the overlay is refreshed.
     */
    private void setBuildingOverlay() {
        try {
            if (BuildingPolygon.inNucleus(currentLocation)) {
                // If we're in Nucleus but either no overlay is set or a different building's overlay is active
                if (!isIndoorMapSet || !"nucleus".equals(currentBuilding)) {
                    // Remove existing overlay if present
                    if (isIndoorMapSet && groundOverlay != null) {
                        groundOverlay.remove();
                    }
                    groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                            .image(BitmapDescriptorFactory.fromResource(R.drawable.nucleusg))
                            .positionFromBounds(NUCLEUS));
                    isIndoorMapSet = true;
                    currentFloor = 1; // Default floor for Nucleus
                    floorHeight = NUCLEUS_FLOOR_HEIGHT;
                    currentBuilding = "nucleus";
                }
            } else if (BuildingPolygon.inLibrary(currentLocation)) {
                if (!isIndoorMapSet || !"library".equals(currentBuilding)) {
                    if (isIndoorMapSet && groundOverlay != null) {
                        groundOverlay.remove();
                    }
                    groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                            .image(BitmapDescriptorFactory.fromResource(R.drawable.libraryg))
                            .positionFromBounds(LIBRARY));
                    isIndoorMapSet = true;
                    currentFloor = 0; // Default floor for Library
                    floorHeight = LIBRARY_FLOOR_HEIGHT;
                    currentBuilding = "library";
                }
            } else {
                // If the user is no longer in any building with an indoor map
                if (isIndoorMapSet && groundOverlay != null) {
                    groundOverlay.remove();
                    isIndoorMapSet = false;
                    currentFloor = 0;
                    currentBuilding = "";
                }
            }
        } catch (Exception ex) {
            Log.e("Error with overlay, Exception:", ex.toString());
        }
    }

    /**
     * Sets an indication of available floor maps for the buildings using green polylines.
     */
    public void setIndicationOfIndoorMap() {
        // Indicator for Nucleus Building
        List<LatLng> points = BuildingPolygon.NUCLEUS_POLYGON;
        points.add(BuildingPolygon.NUCLEUS_POLYGON.get(0)); // Closing boundary
        gMap.addPolyline(new PolylineOptions().color(Color.GREEN)
                .addAll(points));

        // Indicator for the Library Building
        points = BuildingPolygon.LIBRARY_POLYGON;
        points.add(BuildingPolygon.LIBRARY_POLYGON.get(0)); // Closing boundary
        gMap.addPolyline(new PolylineOptions().color(Color.GREEN)
                .addAll(points));
    }
}
