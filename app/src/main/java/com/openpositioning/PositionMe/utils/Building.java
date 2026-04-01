package com.openpositioning.PositionMe.utils;

import static com.openpositioning.PositionMe.utils.BuildingConstants.BUILDING_ELEMENT_LIFT;
import static com.openpositioning.PositionMe.utils.BuildingConstants.BUILDING_ELEMENT_STAIRS;
import static com.openpositioning.PositionMe.utils.BuildingConstants.BUILDING_ELEMENT_WALL;
import static com.openpositioning.PositionMe.utils.BuildingConstants.BUILDING_NO_FLOOR_NUMBER;
import static com.openpositioning.PositionMe.utils.BuildingConstants.COLOUR_BUILDING_WITHOUT_FLOOR_MAPS;
import static com.openpositioning.PositionMe.utils.BuildingConstants.COLOUR_BUILDING_WITH_FLOOR_MAPS;
import static com.openpositioning.PositionMe.utils.BuildingConstants.COLOUR_FLOOR_PLAN_ELEMENTS_DEFAULT;
import static com.openpositioning.PositionMe.utils.BuildingConstants.COLOUR_FLOOR_PLAN_ELEMENTS_LIFT;
import static com.openpositioning.PositionMe.utils.BuildingConstants.COLOUR_FLOOR_PLAN_ELEMENTS_STAIRS;
import static com.openpositioning.PositionMe.utils.BuildingConstants.COLOUR_FLOOR_PLAN_ELEMENTS_WALL;
import static com.openpositioning.PositionMe.utils.BuildingConstants.COLOUR_FLOOR_PLAN_FILL_TRANSPARENT;
import static com.openpositioning.PositionMe.utils.BuildingConstants.FLOOR_HEIGHT_DEFAULT;
import static com.openpositioning.PositionMe.utils.BuildingConstants.FLOOR_HEIGHT_NUCLEUS;
import static com.openpositioning.PositionMe.utils.BuildingConstants.LINE_WEIGHT_FLOOR_PLAN;
import static com.openpositioning.PositionMe.utils.BuildingConstants.LINE_WEIGHT_OUTLINE;
import static com.openpositioning.PositionMe.utils.UtilConstants.BUILDING_NAME_NUCLEUS;

import android.util.Log;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class defines all buildings retrieved from the OpenPosition API. Buildings have a name, an
 * outline, and multiple floor plans. These are drawn onto Google Map objects in {@link
 * IndoorMapManager}.
 *
 * @see IndoorMapManager
 */
public class Building {
    private static final String TAG = "Building";
    private static final String BUILDING_GROUND_PREFIX = "G";

    /*
     * Sort the floor names in the order:
     * - Basement
     * - Lower ground
     * - Ground
     * - Upper ground
     * - (Upper) Floors
     * */
    private static final String[] BUILDING_FLOOR_PREFIX_ORDER =
            new String[] {"B", "L", BUILDING_GROUND_PREFIX, "U", "F"};

    private List<LatLng> outlinePoints;
    private String name;
    private List<FloorPlan> floorPlans;
    private List<String> floorNames;
    private float floorHeight;
    private int floorNumber = BUILDING_NO_FLOOR_NUMBER;
    private int groundFloorIndex = 0;
    private boolean isInsideBuilding = false;
    private boolean isPreviewingFloorPlan = false;
    private Map<String, List<Object>> floorPlanElementOptions;
    private Map<String, List<Polyline>> floorPlanElements;
    private Map<String, List<Polygon>> floorPlanPolygons;
    private PolygonOptions outlinePolygonOptions;
    private Polygon outlinePolygon;

    public Building(String name, List<LatLng> outlinePoints, List<FloorPlan> floorPlans) {
        this.name = name;
        this.floorPlans = floorPlans;
        this.outlinePoints = outlinePoints;
        this.floorPlanElements = new HashMap<>();
        this.floorPlanPolygons = new HashMap<>();

        /*
         Build the building outline, and all floor plan elements.
         These Option objects will then be applied to a GoogleMap
         object from TrajectoryMapManager to create a Polygon and
         List of Polyline objects respectively
        */
        this.outlinePolygonOptions = buildOutlinePolygon();
        this.floorPlanElementOptions = buildFloorPlanElements();

        this.floorNames = defineFloorNameOrder(this.floorPlans);

        // Define the floor height
        if (name.equals(BUILDING_NAME_NUCLEUS)) {
            floorHeight = FLOOR_HEIGHT_NUCLEUS;
        } else {
            floorHeight = FLOOR_HEIGHT_DEFAULT;
        }
        Log.d(TAG, "Created new building: " + this.name);
    }

    /**
     * Prepare polygon of building outline
     *
     * @return PolygonOption representing the building outline
     */
    private PolygonOptions buildOutlinePolygon() {
        return new PolygonOptions()
                .strokeWidth(LINE_WEIGHT_OUTLINE)
                .strokeColor(COLOUR_BUILDING_WITH_FLOOR_MAPS)
                .fillColor(COLOUR_FLOOR_PLAN_FILL_TRANSPARENT)
                .zIndex(0)
                .clickable(true)
                .addAll(this.outlinePoints);
    }

    /**
     * Generate a map of possible floor plans with their associated elements, indexed by floor plan
     * name
     *
     * @return Map of floor plan elements indexed by name
     */
    private Map<String, List<Object>> buildFloorPlanElements() {
        Map<String, List<Object>> floorPlanElementOptions = new HashMap<>();
        List<String> floorNames = new ArrayList<>();

        for (FloorPlan floorPlan : floorPlans) {
            String floorName = floorPlan.getFloorName();
            floorNames.add(floorName);

            List<List<LatLng>> elementsWall = floorPlan.getElementsOfType(BUILDING_ELEMENT_WALL);
            List<List<LatLng>> elementsStairs =
                    floorPlan.getElementsOfType(BUILDING_ELEMENT_STAIRS);
            List<List<LatLng>> elementsLift = floorPlan.getElementsOfType(BUILDING_ELEMENT_LIFT);
            List<List<LatLng>> elementsUnknown = floorPlan.getUnknownElements();

            List<Object> floorElements = new ArrayList<>();
            for (List<LatLng> elementWall : elementsWall) {
                floorElements.add(
                        new PolygonOptions()
                                .strokeWidth(LINE_WEIGHT_FLOOR_PLAN)
                                .strokeColor(COLOUR_FLOOR_PLAN_ELEMENTS_WALL)
                                .fillColor(COLOUR_FLOOR_PLAN_ELEMENTS_WALL)
                                .zIndex(2)
                                .addAll(elementWall));
            }
            for (List<LatLng> elementStairs : elementsStairs) {
                floorElements.add(
                        new PolygonOptions()
                                .strokeWidth(LINE_WEIGHT_FLOOR_PLAN)
                                .strokeColor(COLOUR_FLOOR_PLAN_ELEMENTS_STAIRS)
                                .fillColor(COLOUR_FLOOR_PLAN_ELEMENTS_STAIRS)
                                .zIndex(1)
                                .addAll(elementStairs));
            }
            for (List<LatLng> elementLift : elementsLift) {
                floorElements.add(
                        new PolygonOptions()
                                .strokeWidth(LINE_WEIGHT_FLOOR_PLAN)
                                .strokeColor(COLOUR_FLOOR_PLAN_ELEMENTS_LIFT)
                                .fillColor(COLOUR_FLOOR_PLAN_ELEMENTS_LIFT)
                                .zIndex(1)
                                .addAll(elementLift));
            }
            for (List<LatLng> elementUnknown : elementsUnknown) {
                floorElements.add(
                        new PolylineOptions()
                                .width(LINE_WEIGHT_FLOOR_PLAN)
                                .color(COLOUR_FLOOR_PLAN_ELEMENTS_DEFAULT)
                                .zIndex(1)
                                .addAll(elementUnknown));
            }
            floorPlanElementOptions.put(floorName, floorElements);
        }
        return floorPlanElementOptions;
    }

    /**
     * Sort the floor plan names such that the lowest index is the lowest floor. Also sets the
     * ground floor index for future reference
     *
     * @param floors The list of all {@link FloorPlan FloorPlans} associated with the building
     * @return A list of sorted floor plan names
     */
    private List<String> defineFloorNameOrder(List<FloorPlan> floors) {
        List<String> orderedNames = new ArrayList<>();

        List<String> allNames = new ArrayList<>();
        for (FloorPlan floor : floors) {
            allNames.add(floor.getFloorName());
        }

        for (String floorPrefix : BUILDING_FLOOR_PREFIX_ORDER) {
            for (String name : allNames) {
                if (name.toUpperCase().startsWith(floorPrefix)) {
                    orderedNames.add(name);
                }
            }
        }

        // Add floor names with unexpected/unknown prefixes to end of floor plan list
        for (String name : allNames) {
            if (!orderedNames.contains(name)) {
                Log.w(TAG, "Unexpected floor name \"" + name + "\"; adding to end of list");
                orderedNames.add(name);
            }
        }

        // Set the index of the ground floor, for future reference
        for (String name : orderedNames) {
            if (name.toUpperCase().startsWith(BUILDING_GROUND_PREFIX)) {
                this.groundFloorIndex = orderedNames.indexOf(name);
            }
        }
        return orderedNames;
    }

    public String getName() {
        return name;
    }

    public float getFloorHeight() {
        return floorHeight;
    }

    public int getGroundFloorIndex() {
        return groundFloorIndex;
    }

    public List<FloorPlan> getFloorPlans() {
        return floorPlans;
    }

    /**
     * Getter to obtain if currently an indoor floor map is being displayed
     *
     * @return true if an indoor map is visible to the user, false otherwise
     */
    public boolean getIsInsideBuilding() {
        return isInsideBuilding;
    }

    public boolean getIsPreviewingFloorPlan() {
        return isPreviewingFloorPlan;
    }

    public int getFloorNumber() {
        return floorNumber;
    }

    public String getFloorName() {
        return floorNames.get(floorNumber);
    }

    public List<String> getFloorNames() {
        return floorNames;
    }

    public Polygon getBuildingOutline() {
        return outlinePolygon;
    }

    public List<Polyline> getFloorPlanElements(String floorName) {
        return floorPlanElements.get(floorName);
    }

    public void setIsPreviewingFloorPlan(boolean set) {
        isPreviewingFloorPlan = set;
    }

    /**
     * Draw an outline of the building on the map.
     *
     * @param map The GoogleMap object where the outline is being drawn
     */
    public void drawBuildingOutline(GoogleMap map) {
        if (outlinePolygon == null) {
            // Set stroke colour depending on presence of floor plans
            int strokeColour =
                    !floorPlans.isEmpty()
                            ? COLOUR_BUILDING_WITH_FLOOR_MAPS
                            : COLOUR_BUILDING_WITHOUT_FLOOR_MAPS;
            this.outlinePolygon = map.addPolygon(outlinePolygonOptions.strokeColor(strokeColour));
            Log.d(TAG, name + ": Building outline drawn");
        } else {
            Log.d(TAG, name + ": Outline already visible");
        }
    }

    /**
     * Set the fill colour of the building polygon
     *
     * @param colour The desired colour of the polygon
     */
    public void setFillColour(int colour) {
        if (outlinePolygon == null) {
            Log.w(TAG, name + ": Outline polygon is null!");
        } else {
            outlinePolygon.setFillColor(colour);
        }
    }

    /**
     * Draw the floor plan elements of the desired floor on a map object
     *
     * @param newFloor The number of the floor being drawn
     * @param gMap The GoogleMap object where floor plans are being drawn
     */
    public void setCurrentFloor(int newFloor, GoogleMap gMap) {
        if (newFloor < 0 || newFloor >= floorNames.size()) {
            Log.w(
                    TAG,
                    name
                            + ": Suggested floor "
                            + newFloor
                            + " outside of range {0,"
                            + floorNames.size()
                            + "}");
        } else if (newFloor == floorNumber) {
            // Redraw the floor plan elements
            editFloorPlan(gMap, floorNumber, true);
            Log.d(TAG, name + ": Already on floor " + newFloor);
        } else {
            // Floor number initialises to -1, so reinitialise if required
            if (floorNumber == BUILDING_NO_FLOOR_NUMBER) {
                floorNumber = groundFloorIndex;
            } else {
                // Remove old floor plan before continuing
                editFloorPlan(gMap, floorNumber, false);
                floorNumber = newFloor;
            }
            editFloorPlan(gMap, floorNumber, true);
            Log.d(TAG, name + ": Floor set to " + floorNumber);
        }
    }

    /**
     * Hide all available floor plans for the building
     *
     * @param map The GoogleMap object showing the floor plans of the building
     */
    public void hideFloorPlans(GoogleMap map) {
        for (int i = 0; i < floorNames.size(); i++) {
            editFloorPlan(map, i, false);
        }
        // Reset floor number upon leaving building
        if (!isInsideBuilding) {
            floorNumber = BUILDING_NO_FLOOR_NUMBER;
        }
    }

    /**
     * Draw the desired floor number's floor plan on the map.
     *
     * <p>Either add the polylines required if they have never been drawn before, or toggle their
     * visibility
     *
     * @param map The GoogleMap object drawing the floor plans
     * @param floorNumber The floor being displayed or hidden
     * @param showFloor True if the floor plan should be visible; false otherwise, to hide the
     *     floorplan
     */
    public void editFloorPlan(GoogleMap map, int floorNumber, boolean showFloor) {
        String floorName = floorNames.get(floorNumber);
        List<Polyline> elementsPolyline;
        List<Polygon> elementsPolygon;

        // Use pre-existing floor plan elements if possible
        if (floorPlanElements.containsKey(floorName)) {
            elementsPolyline = floorPlanElements.get(floorName);
            elementsPolygon = floorPlanPolygons.get(floorName);
        } else {
            // Continue only if floor plan elements can be created
            if (!floorPlanElementOptions.containsKey(floorName)) {
                Log.w(TAG, name + ": Floor " + floorNumber + " has no floor plan!");
                return;
            }
            // Create the Polyline/Polygon objects for the first time
            elementsPolyline = new ArrayList<>();
            elementsPolygon = new ArrayList<>();
            for (Object options : floorPlanElementOptions.get(floorName)) {
                if (options instanceof PolylineOptions polylineOptions) {
                    Polyline floorElement = map.addPolyline(polylineOptions);
                    elementsPolyline.add(floorElement);
                } else if (options instanceof PolygonOptions polygonOptions) {
                    Polygon floorElement = map.addPolygon(polygonOptions);
                    elementsPolygon.add(floorElement);
                }
            }
            floorPlanElements.put(floorName, elementsPolyline);
            floorPlanPolygons.put(floorName, elementsPolygon);
            Log.d(TAG, name + " floor " + floorNumber + " added to list");
        }

        // With all elements gathered, set their visibility as required
        for (Polyline element : elementsPolyline) {
            element.setVisible(showFloor);
        }
        for (Polygon element : elementsPolygon) {
            element.setVisible(showFloor);
        }
        Log.d(TAG, name + " floor " + floorNumber + " visibility set to " + showFloor);
    }

    /**
     * Function to check if point in polygon (approximates earth to be flat) Ray casting algorithm
     * https://en.wikipedia.org/wiki/Point_in_polygon
     *
     * @param point point to be checked if in polygon
     * @return True if point in polygon False otherwise
     */
    public boolean isPointInBuilding(LatLng point) {
        int numCrossings = 0;
        // For each edge
        for (int i = 0; i < outlinePoints.size(); i++) {
            LatLng a = outlinePoints.get(i);
            int j = i + 1;
            // Last edge (includes first point of Polygon)
            if (j >= outlinePoints.size()) {
                j = 0;
            }
            LatLng b = outlinePoints.get(j);
            if (crossingSegment(point, a, b)) {
                numCrossings++;
            }
        }

        // If odd number of numCrossings, return true (point is in polygon)
        isInsideBuilding = (numCrossings % 2 == 1);
        return isInsideBuilding;
    }

    /**
     * Ray Casting algorithm for a segment joining ab
     *
     * @param point the point we check
     * @param a the line segment's starting point
     * @param b the line segment's ending point
     * @return True if the point is 1) To the left of the segment ab 2) Not above nor below the
     *     segment ab Otherwise False
     */
    private static boolean crossingSegment(LatLng point, LatLng a, LatLng b) {
        double pointLng = point.longitude,
                pointLat = point.latitude,
                aLng = a.longitude,
                aLat = a.latitude,
                bLng = b.longitude,
                bLat = b.latitude;
        if (aLat > bLat) {
            aLng = b.longitude;
            aLat = b.latitude;
            bLng = a.longitude;
            bLat = a.latitude;
        }
        // Alter longitude to correct for 180 degree crossings
        if (pointLng < 0 || aLng < 0 || bLng < 0) {
            pointLng += 360;
            aLng += 360;
            bLng += 360;
        }
        // If point has same latitude as a or b, increase slightly pointLat
        if (pointLat == aLat || pointLat == bLat) pointLat += 0.00000001;

        // If the point is above, below or to the right of the segment,return false
        if ((pointLat > bLat || pointLat < aLat) || (pointLng > Math.max(aLng, bLng))) {
            return false;
        }
        // If the point is not above, below or to the right and is to the left, return true
        else if (pointLng < Math.min(aLng, bLng)) {
            return true;
        }
        // Comparing the slope of segment [a,b] (slope1)
        // and segment [a,point] (slope2) to check if to the left of segment [a,b] or not
        else {
            double slope1 =
                    (aLng != bLng) ? ((bLat - aLat) / (bLng - aLng)) : Double.POSITIVE_INFINITY;
            double slope2 =
                    (aLng != pointLng)
                            ? ((pointLat - aLat) / (pointLng - aLng))
                            : Double.POSITIVE_INFINITY;
            return (slope2 >= slope1);
        }
    }
}
