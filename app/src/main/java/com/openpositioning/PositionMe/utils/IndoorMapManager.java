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
import java.util.List;
import java.util.Map;
import org.geojson.Feature;
import org.geojson.FeatureCollection;
import org.geojson.GeoJsonObject;
import org.geojson.LngLatAlt;
import org.geojson.MultiPolygon;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

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
            } else if (building.getIsPreviowingFloorPlan()) {
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
                Map<String, List<Map<String, Object>>> mapShapes =
                        (Map<String, List<Map<String, Object>>>) building.get("map_shapes");

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
                FeatureCollection featureCollection =
                        new ObjectMapper()
                                .readValue(
                                        buildingEntry.getString("outline"),
                                        FeatureCollection.class);

                /*
                 * For every feature in the collection, extract the geometry,
                 * extract the coordinates, and reconstruct the outline as
                 * a list of LatLng points (ie, without the Alt, which is
                 * always NaN)
                 * */
                List<Feature> featuresOutline = featureCollection.getFeatures();
                List<LatLng> coordinates = new ArrayList<>();
                for (Feature feature : featuresOutline) {
                    GeoJsonObject geometry = feature.getGeometry();
                    if (geometry instanceof MultiPolygon multiPolygon) {
                        List<List<List<LngLatAlt>>> coordinatesLngLatAlt =
                                multiPolygon.getCoordinates();
                        for (LngLatAlt point : coordinatesLngLatAlt.get(0).get(0)) {
                            coordinates.add(new LatLng(point.getLatitude(), point.getLongitude()));
                        }
                    }
                }

                // Part 3 - Floor plans
                Map<String, Object> floorplansJSON =
                        new ObjectMapper()
                                .readValue(
                                        buildingEntry.getString("map_shapes"),
                                        new TypeReference<>() {});

                // Map to index floor plans by floor name
                Map<String, List<Map<String, Object>>> floorplans = new HashMap<>();

                for (String floorname : floorplansJSON.keySet()) {
                    Object floor = floorplansJSON.get(floorname);
                    FeatureCollection fc =
                            new ObjectMapper().convertValue(floor, FeatureCollection.class);

                    // Every element in the floor plan will be assigned it's element type
                    List<Feature> features = fc.getFeatures();
                    List<Map<String, Object>> floorElements = new ArrayList<>();
                    for (Feature feature : features) {
                        GeoJsonObject floorElement = feature.getGeometry();

                        // String elementType = "wall";
                        String elementType = (String) feature.getProperties().get("indoor_type");
                        Map<String, Object> element = new HashMap<>();
                        element.put(elementType, floorElement);

                        floorElements.add(element);
                    }
                    floorplans.put(floorname, floorElements);
                }

                entryMap.put("name", name);
                entryMap.put("outline", coordinates);
                entryMap.put("map_shapes", floorplans);

                entryList.add(entryMap);
                Log.d(TAG, "Building '" + name + "' parsed");
            }
            Log.d(TAG, entryList.size() + " buildings parsed");
        } catch (JSONException e) {
            Log.e(TAG, "JSON Parse Failed: " + e.getMessage());
        }
        return entryList;
    }
}
