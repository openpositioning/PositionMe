package com.openpositioning.PositionMe.utils;

import static com.openpositioning.PositionMe.utils.UtilConstants.BUILDING_NAME_NUCLEUS;
import static com.openpositioning.PositionMe.utils.UtilConstants.COLOUR_FLOOR_PLAN_FILL_INSIDE;
import static com.openpositioning.PositionMe.utils.UtilConstants.COLOUR_FLOOR_PLAN_FILL_TRANSPARENT;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.List;

/**
 * Class used to manage indoor floor map overlays
 * Currently used by RecordingFragment
 * @see Building Describes the bounds of buildings and the methods to check if point is
 * in the building
 * @author Arun Gopalakrishnan
 */
public class IndoorMapManager {
    // To store the map instance
    private GoogleMap gMap;
    // Stores the current Location of user
    private LatLng currentLocation;
    private List<Building> buildings = new ArrayList<>();
    private List<String> buildingNames = new ArrayList<>();

    /**
     * Constructor to set the map instance
     * @param map The map on which the indoor floor map overlays are set
     */
    public IndoorMapManager(GoogleMap map){this.gMap=map;}
    public List<Building> getAllBuildings(){return buildings;}
    public List<String> getAllBuildingNames(){return buildingNames;}
    public void addBuilding(Building building){
        buildings.add(building);
        buildingNames.add(building.getName());
    }

    /**
     * Function to update the current location of user
     * and display the indoor map if user in building
     * with indoor map available
     * @param currentLocation Location of user
     */
    public void setCurrentLocation(LatLng currentLocation) {
        this.currentLocation = currentLocation;
        drawBuildingPolygons();
    }

    /**
     * Handle polygon drawing if inside the building,
     * and hide all elements otherwise
     * */
    private void drawBuildingPolygons(){
        for (Building building : buildings){
            if (building.equals(getCurrentBuilding(currentLocation))){
                building.setFillColour(COLOUR_FLOOR_PLAN_FILL_INSIDE);
                building.setCurrentFloor(Math.max(building.getFloorNumber(), 0), gMap);
                // Disable preview if present
                building.setIsPreviewingFloorPlan(false);
            } else if (building.getIsPreviowingFloorPlan()){
                return;
            } else {
                building.setFillColour(COLOUR_FLOOR_PLAN_FILL_TRANSPARENT);
                building.hideFloorPlans(gMap);
            }
        }
    }

    /**
     * Get the building the user is currently inside of,
     * based on a given position.
     *
     * @param position The position being queried
     * @return The building, if position is inside one,
     * or null if no building contains position
     * */
    public Building getCurrentBuilding(LatLng position){
        for (Building building : buildings){
            if (building.isPointInBuilding(position)){
                return building;
            }
        }
        return null;
    }
}
