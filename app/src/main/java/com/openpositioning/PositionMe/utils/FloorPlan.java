package com.openpositioning.PositionMe.utils;

import static com.openpositioning.PositionMe.utils.BuildingConstants.BUILDING_ELEMENT_LIFT;
import static com.openpositioning.PositionMe.utils.BuildingConstants.BUILDING_ELEMENT_STAIRS;
import static com.openpositioning.PositionMe.utils.BuildingConstants.BUILDING_ELEMENT_WALL;

import com.google.android.gms.maps.model.LatLng;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * This class contains information about a floor plan for a {@link Building} as received from the
 * OpenPosition Server
 *
 * @see Building
 * @see IndoorMapManager
 */
public class FloorPlan {
    public static final String TAG = "FloorPlan";

    private String floorName;
    // List of elements, indexed by their element type, and comprised of a list of points
    private List<Map<String, List<LatLng>>> elements;

    // List of element types expected for the server to return
    private static final List<String> ELEMENT_TYPES_KNOWN =
            Arrays.asList(BUILDING_ELEMENT_WALL, BUILDING_ELEMENT_LIFT, BUILDING_ELEMENT_STAIRS);

    public FloorPlan(String floorName, List<Map<String, List<LatLng>>> elements) {
        this.floorName = floorName;
        this.elements = elements;
    }

    public String getFloorName() {
        return floorName;
    }

    /**
     * Retrieve a list of every floor element of a given type
     *
     * @param type The element type requested
     * @return A list of list of points, with each list representing a different floor plan element
     */
    public List<List<LatLng>> getElementsOfType(String type) {
        List<List<LatLng>> elements = new ArrayList<>();

        for (Map<String, List<LatLng>> floorElement : this.elements) {
            if (floorElement.keySet().toArray()[0].equals(type)) {
                elements.add(floorElement.get(type));
            }
        }

        return elements;
    }

    /**
     * Retrieve any elements of an unexpected type
     *
     * @return A list of elements, represented by their points, which have unexpected types
     * @see FloorPlan#ELEMENT_TYPES_KNOWN
     */
    public List<List<LatLng>> getUnknownElements() {
        List<List<LatLng>> elements = new ArrayList<>();

        for (Map<String, List<LatLng>> floorElement : this.elements) {
            String type = floorElement.keySet().toArray()[0].toString();
            if (!ELEMENT_TYPES_KNOWN.contains(type)) {
                elements.addAll(getElementsOfType(type));
            }
        }

        return elements;
    }
}
