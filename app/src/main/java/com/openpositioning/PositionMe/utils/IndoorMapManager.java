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
                Map<String, List<Map<String, List<LatLng>>>> mapShapes =
                        (Map<String, List<Map<String, List<LatLng>>>>) building.get("map_shapes");

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

                /*
                 * For every feature in the collection, extract the geometry,
                 * extract the coordinates, and reconstruct the outline as
                 * a list of LatLng points
                 * */
                ArrayList<LatLng> outlinePoints = new ArrayList<>();
                if (!outlineJson.isEmpty()) {
                    try {
                        JSONObject outlineObject = new JSONObject(outlineJson);
                        JSONArray features = outlineObject.getJSONArray("features");
                        if (features.length() > 0) {
                            JSONObject geometry =
                                    features.getJSONObject(0).getJSONObject("geometry");
                            JSONArray coordinates = geometry.getJSONArray("coordinates");
                            JSONArray firstRing = new JSONArray();
                            String typeGeometry = geometry.getString("type");
                            switch (typeGeometry) {
                                // MultiPolygon: coordinates[polygon_index][ring_index][point_index]
                                case "MultiPolygon":
                                    if (coordinates.length() > 0) {
                                        JSONArray firstPolygon = coordinates.getJSONArray(0);
                                        if (firstPolygon.length() > 0) {
                                            firstRing = firstPolygon.getJSONArray(0);
                                        }
                                    }
                                    break;
                                // Polygon: coordinates[ring_index][point_index]
                                case "Polygon":
                                    if (coordinates.length() > 0) {
                                        firstRing = coordinates.getJSONArray(0);
                                    }
                                    break;
                                default:
                                    break;
                            }

                            for (int j = 0; j < firstRing.length(); j++) {
                                // GeoJSON: [longitude, latitude]
                                JSONArray point = firstRing.getJSONArray(j);
                                outlinePoints.add(
                                        new LatLng(point.getDouble(1), point.getDouble(0)));
                            }
                        }
                    } catch (JSONException e) {
                        Log.w(TAG, e.getMessage());
                    }
                }

                // Part 3 - Floor plans
                String mapShapesJson = buildingEntry.optString("map_shapes", "");

                // Map to index floor plans by floor name
                Map<String, List<Map<String, List<LatLng>>>> floorplans = new HashMap<>();

                if (!mapShapesJson.isEmpty()) {
                    try {
                        JSONObject floorplansObject = new JSONObject(mapShapesJson);
                        for (Iterator<String> it = floorplansObject.keys(); it.hasNext(); ) {
                            String floorname = it.next();
                            JSONObject floor = floorplansObject.getJSONObject(floorname);
                            JSONArray features = floor.getJSONArray("features");
                            List<Map<String, List<LatLng>>> elements = new ArrayList<>();
                            for (int j = 0; j < features.length(); j++) {
                                JSONObject feature = features.getJSONObject(j);

                                JSONObject properties = feature.getJSONObject("properties");
                                String elementType = properties.getString("indoor_type");

                                JSONObject geometry = feature.getJSONObject("geometry");
                                String geoType = geometry.getString("type");
                                JSONArray coordinates = geometry.getJSONArray("coordinates");

                                switch (geoType) {
                                    case "MultiLineString":
                                        // coordinates[line_index][point_index] = [lon, lat]
                                        for (int k = 0; k < coordinates.length(); k++) {
                                            JSONArray line = coordinates.getJSONArray(k);
                                            List<LatLng> points = new ArrayList<>();
                                            for (int l = 0; l < line.length(); l++) {
                                                JSONArray point = line.getJSONArray(l);
                                                points.add(
                                                        new LatLng(
                                                                point.getDouble(1),
                                                                point.getDouble(0)));
                                            }
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
                                                List<LatLng> points = new ArrayList<>();
                                                for (int l = 0; l < outerRing.length(); l++) {
                                                    JSONArray point = outerRing.getJSONArray(l);
                                                    points.add(
                                                            new LatLng(
                                                                    point.getDouble(1),
                                                                    point.getDouble(0)));
                                                }
                                                Map<String, List<LatLng>> element = new HashMap<>();
                                                element.put(elementType, points);
                                                elements.add(element);
                                            }
                                        }
                                        break;
                                    default:
                                }
                                floorplans.put(floorname, elements);
                            }
                        }
                    } catch (JSONException e) {
                        Log.w(TAG, e.getMessage());
                    }
                }

                entryMap.put("name", name);
                entryMap.put("outline", outlinePoints);
                entryMap.put("map_shapes", floorplans);

                Log.d(TAG, "Building name: " + name);
                Log.d(TAG, "# of outline points: " + outlinePoints.size());
                Log.d(TAG, "# of floors: " + floorplans.size());
                for (String floorname : floorplans.keySet()) {
                    Log.d(
                            TAG,
                            "# of elements on floor "
                                    + floorname
                                    + ": "
                                    + floorplans.get(floorname).size());
                }

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
