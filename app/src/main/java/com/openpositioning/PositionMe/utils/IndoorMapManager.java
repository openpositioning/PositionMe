package com.openpositioning.PositionMe.utils;

import static com.openpositioning.PositionMe.utils.BuildingConstants.COLOUR_FLOOR_PLAN_FILL_INSIDE;
import static com.openpositioning.PositionMe.utils.BuildingConstants.COLOUR_FLOOR_PLAN_FILL_TRANSPARENT;

import android.util.Log;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Class used to manage indoor floor map overlays Currently used by RecordingFragment
 *
 * @see Building Describes the bounds of buildings and the methods to check if point is in the
 *     building
 * @see Observer Interface for handling server responses
 * @author Arun Gopalakrishnan
 */
public class IndoorMapManager implements Observer {
    private static final String TAG = "IndoorMapManager";
    // To store the map instance
    private GoogleMap gMap;
    private SensorFusion sensorFusion;
    // Stores the current location of the user
    private LatLng currentLocation;
    private List<Building> buildings = new ArrayList<>();
    private List<String> buildingNames = new ArrayList<>();

    /**
     * Constructor to set the map instance
     *
     * @param map The map on which the indoor floor map overlays are set
     */
    public IndoorMapManager(GoogleMap map) {
        this.gMap = map;
        this.sensorFusion = SensorFusion.getInstance();
        sensorFusion.registerForServerUpdate(this);
    }

    public List<Building> getAllBuildings() {
        return buildings;
    }

    public List<String> getAllBuildingNames() {
        return buildingNames;
    }

    public void addBuilding(Building building) {
        buildings.add(building);
        buildingNames.add(building.getName());
    }

    /**
     * Function to update the current location of user and display the indoor map if user in
     * building with indoor map available
     *
     * @param currentLocation Location of user
     */
    public void setCurrentLocation(LatLng currentLocation) {
        this.currentLocation = currentLocation;
        drawBuildingPolygons();
    }

    /** Handle polygon drawing if inside the building, and hide all elements otherwise */
    private void drawBuildingPolygons() {
        for (Building building : buildings) {
            if (building.equals(getCurrentBuilding(currentLocation))) {
                building.setFillColour(COLOUR_FLOOR_PLAN_FILL_INSIDE);
                building.setCurrentFloor(Math.max(building.getFloorNumber(), 0), gMap);
                // Disable preview if present
                building.setIsPreviewingFloorPlan(false);
            } else if (building.getIsPreviewingFloorPlan()) {
                return;
            } else {
                building.setFillColour(COLOUR_FLOOR_PLAN_FILL_TRANSPARENT);
                building.hideFloorPlans(gMap);
            }
        }
    }

    /**
     * Get the building the user is currently inside of, based on a given position.
     *
     * @param position The position being queried
     * @return The building, if position is inside one, or null if no building contains position
     */
    public Building getCurrentBuilding(LatLng position) {
        for (Building building : buildings) {
            if (building.isPointInBuilding(position)) {
                return building;
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Called by {@link ServerCommunications} when the response to the HTTP info request is
     * received.
     *
     * @param objList The response from the server, including a {@link Boolean} value of success and
     *     the server's response as a string.
     */
    @Override
    public void update(Object[] objList) {
        boolean success = (boolean) objList[0];
        String response = objList[1].toString();
        Log.d(TAG, "Received response: " + response);
        if (!success) return;

        try {
            // Parse the JSON, and draw all possible buildings
            List<Map<String, Object>> entryList = processPOSTResponse(response);
            for (Map<String, Object> building : entryList) {

                String name = building.get("name").toString();
                @SuppressWarnings("unchecked")
                List<LatLng> outline = (List<LatLng>) building.get("outline");
                @SuppressWarnings("unchecked")
                List<FloorPlan> mapShapes = (List<FloorPlan>) building.get("map_shapes");

                // Add building to list of known buildings
                if (!this.getAllBuildingNames().contains(name)) {
                    this.addBuilding(new Building(name, outline, mapShapes));
                } else {
                    Log.w(TAG, "Building " + name + " already exists. Skipping creation.");
                }
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Error processing server response: " + e.getMessage());
        }
    }

    /**
     * Parses the GeoJSON response for floor plans
     *
     * @param response The raw JSON string response from the server
     * @return A list of maps containing the data associated with every building contained with the
     *     response
     */
    private List<Map<String, Object>> processPOSTResponse(String response) throws RuntimeException {
        List<Map<String, Object>> entryList = new ArrayList<>();

        try {
            JSONArray jsonArray = new JSONArray(response);

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject buildingEntry = jsonArray.getJSONObject(i);
                Map<String, Object> entryMap = new HashMap<>();

                // Part 1 - Building Name
                String name = buildingEntry.getString("name");

                // Part 2 - Building Outline
                String outlineJson = buildingEntry.optString("outline", "");
                ArrayList<LatLng> outlinePoints = parseOutlineJSON(outlineJson);

                // Part 3 - Floor plans
                String mapShapesJson = buildingEntry.optString("map_shapes", "");
                List<FloorPlan> floorPlans = parseFloorPlanJSON(mapShapesJson);

                entryMap.put("name", name);
                entryMap.put("outline", outlinePoints);
                entryMap.put("map_shapes", floorPlans);

                entryList.add(entryMap);
                Log.d(TAG, "Building '" + name + "' parsed");
            }
            Log.d(TAG, entryList.size() + " buildings parsed");
        } catch (JSONException e) {
            Log.e(TAG, "JSON Parse Failed: " + e.getMessage());
        }
        return entryList;
    }

    /**
     * Extract the geometry and coordinates from the outline JSON, and reconstruct the outline as a
     * list of {@link LatLng} points
     *
     * @param outlineJson The raw JSON of the outline as a String
     * @return A list of points representing the outline, or an empty list on a parse error
     */
    private ArrayList<LatLng> parseOutlineJSON(String outlineJson) {
        ArrayList<LatLng> outlinePoints = new ArrayList<>();

        if (!outlineJson.isEmpty()) {
            try {
                JSONObject outlineObject = new JSONObject(outlineJson);
                JSONArray features = outlineObject.getJSONArray("features");

                // Only expecting one Feature for the outline
                if (features.length() > 0) {
                    JSONObject geometry = features.getJSONObject(0).getJSONObject("geometry");
                    JSONArray coordinates = geometry.getJSONArray("coordinates");

                    // Extract the 'first ring', where LatLng points are stored
                    JSONArray firstRing = new JSONArray();
                    if (coordinates.length() > 0) {
                        JSONArray firstPolygon = coordinates.getJSONArray(0);
                        if (firstPolygon.length() > 0) {
                            firstRing = firstPolygon.getJSONArray(0);
                        }
                    }

                    for (int i = 0; i < firstRing.length(); i++) {
                        // GeoJSON: [longitude, latitude]
                        JSONArray point = firstRing.getJSONArray(i);
                        outlinePoints.add(new LatLng(point.getDouble(1), point.getDouble(0)));
                    }
                }
            } catch (JSONException e) {
                Log.w(TAG, e.getMessage());
                outlinePoints.clear();
            }
        }
        return outlinePoints;
    }

    /**
     * Generate a list of all {@link FloorPlan floorplans} present in the OpenPositioning server's
     * response
     *
     * @param floorPlanJSON The raw JSON response from the server as a String
     * @return A list of {@link FloorPlan} objects for each floor in the response
     */
    private List<FloorPlan> parseFloorPlanJSON(String floorPlanJSON) {
        List<FloorPlan> floorplans = new ArrayList<>();

        if (!floorPlanJSON.isEmpty()) {
            try {
                JSONObject floorplansObject = new JSONObject(floorPlanJSON);
                for (Iterator<String> it = floorplansObject.keys(); it.hasNext(); ) {
                    String floorName = it.next();
                    JSONObject floor = floorplansObject.getJSONObject(floorName);

                    JSONArray elementsJSON = floor.getJSONArray("features");

                    List<Map<String, List<LatLng>>> elements = new ArrayList<>();
                    for (int j = 0; j < elementsJSON.length(); j++) {
                        JSONObject elementObject = elementsJSON.getJSONObject(j);

                        JSONObject properties = elementObject.getJSONObject("properties");
                        String elementType = properties.getString("indoor_type");

                        JSONObject geometry = elementObject.getJSONObject("geometry");
                        String geometryType = geometry.getString("type");
                        JSONArray coordinates = geometry.getJSONArray("coordinates");

                        switch (geometryType) {
                            case "MultiLineString":
                                // coordinates[line_index][point_index] = [lon, lat]
                                for (int k = 0; k < coordinates.length(); k++) {
                                    JSONArray line = coordinates.getJSONArray(k);
                                    List<LatLng> points = constructPointsList(line);
                                    Map<String, List<LatLng>> element = new HashMap<>();
                                    element.put(elementType, points);
                                    elements.add(element);
                                }
                                break;
                            case "MultiPolygon":
                                // coordinates[polygon_index][ring_index][point_index] =
                                // [lon, lat]
                                for (int k = 0; k < coordinates.length(); k++) {
                                    JSONArray polygon = coordinates.getJSONArray(k);
                                    if (polygon.length() > 0) {
                                        // Use the outer ring (index 0) of each polygon
                                        JSONArray outerRing = polygon.getJSONArray(0);
                                        List<LatLng> points = constructPointsList(outerRing);
                                        Map<String, List<LatLng>> element = new HashMap<>();
                                        element.put(elementType, points);
                                        elements.add(element);
                                    }
                                }
                                break;
                            default:
                        }
                    }
                    FloorPlan floorResult = new FloorPlan(floorName, elements);
                    floorplans.add(floorResult);
                }
            } catch (JSONException e) {
                Log.w(TAG, e.getMessage());
            }
        }
        return floorplans;
    }

    /**
     * Helper function to parse JSON lists of points and generate the corresponding {@link List} of
     * {@link LatLng} points
     *
     * @param pointsArray {@link JSONArray} of points
     * @return {@link List} of {@link LatLng} points
     */
    private List<LatLng> constructPointsList(JSONArray pointsArray) {
        List<LatLng> points = new ArrayList<>();
        try {
            for (int i = 0; i < pointsArray.length(); i++) {
                JSONArray point = pointsArray.getJSONArray(i);
                points.add(new LatLng(point.getDouble(1), point.getDouble(0)));
            }
        } catch (JSONException e) {
            Log.w(TAG, e.getMessage());
        }
        return points;
    }
}
