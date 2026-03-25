package com.openpositioning.PositionMe.utils;

import android.graphics.Color;
import android.util.Log;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages indoor floor map display for all supported buildings
 * (Nucleus, Library, Murchison). Uses vector shape data from the floorplan API
 * to dynamically draw walls, rooms, and other indoor features on the Google Map.
 * Provides unified floor indexing, floor switching, and building detection.
 *
 * @see BuildingPolygon Describes the bounds of buildings and the methods to check if a point is
 *                      within a building
 * @see FloorplanApiClient.FloorShapes Per-floor vector shape data
 */
public class IndoorMapManager {

    private static final String TAG = "IndoorMapManager";

    /** Building identifiers for tracking which building the user is in. */
    public static final int BUILDING_NONE = 0;
    public static final int BUILDING_NUCLEUS = 1;
    public static final int BUILDING_LIBRARY = 2;
    public static final int BUILDING_MURCHISON = 3;

    private GoogleMap gMap;
    private LatLng currentLocation;
    private boolean isIndoorMapSet = false;
    private int currentFloor;
    private int currentBuilding = BUILDING_NONE;
    private float floorHeight;

    // Vector shapes currently drawn on the map (cleared on floor switch or exit)
    private final List<Polygon> drawnPolygons = new ArrayList<>();
    private final List<Polyline> drawnPolylines = new ArrayList<>();

    // Per-floor vector shape data for the current building
    private List<FloorplanApiClient.FloorShapes> currentFloorShapes;

    // Average floor heights per building (meters), used for barometric auto-floor
    public static final float NUCLEUS_FLOOR_HEIGHT = 4.2F;
    public static final float LIBRARY_FLOOR_HEIGHT = 3.6F;
    public static final float MURCHISON_FLOOR_HEIGHT = 4.0F;

    // Colours for different indoor feature types
    private static final int WALL_STROKE = Color.argb(220, 25, 118, 210);
    private static final int ROOM_STROKE = Color.argb(210, 33, 150, 243);
    private static final int ROOM_FILL = Color.argb(60, 33, 150, 243);
    private static final int DEFAULT_STROKE = Color.argb(170, 66, 165, 245);

    /**
     * Constructor to set the map instance.
     *
     * @param map the map on which the indoor floor map shapes are drawn
     */
    public IndoorMapManager(GoogleMap map) {
        this.gMap = map;
    }

    /**
     * Updates the current location of the user and displays the indoor map
     * if the user is in a building with indoor maps available.
     *
     * @param currentLocation new location of user
     */
    public void setCurrentLocation(LatLng currentLocation) {
        this.currentLocation = currentLocation;
        setBuildingOverlay();
    }

    /**
     * Returns the current building's floor height.
     *
     * @return the floor height of the current building the user is in
     */
    public float getFloorHeight() {
        return floorHeight;
    }

    /**
     * Returns whether an indoor floor map is currently being displayed.
     *
     * @return true if an indoor map is visible to the user, false otherwise
     */
    public boolean getIsIndoorMapSet() {
        return isIndoorMapSet;
    }

    /**
     * Returns the identifier of the building the user is currently in.
     *
     * @return one of {@link #BUILDING_NONE}, {@link #BUILDING_NUCLEUS},
     *         {@link #BUILDING_LIBRARY}, or {@link #BUILDING_MURCHISON}
     */
    public int getCurrentBuilding() {
        return currentBuilding;
    }

    /**
     * Returns the current floor index being displayed.
     *
     * @return the current floor index in the active building's floor list
     */
    public int getCurrentFloor() {
        return currentFloor;
    }

    /**
     * Returns the display name for the current floor (e.g. "LG", "G", "1").
     * Falls back to the numeric index if no display name is available.
     *
     * @return human-readable floor label
     */
    public String getCurrentFloorDisplayName() {
        if (currentFloorShapes != null
                && currentFloor >= 0
                && currentFloor < currentFloorShapes.size()) {
            return currentFloorShapes.get(currentFloor).getDisplayName();
        }
        return String.valueOf(currentFloor);
    }

    /**
     * Returns the auto-floor bias for the current building. Buildings with a
     * lower-ground floor at index 0 need a +1 bias so that WiFi/barometric
     * floor 0 (ground) maps to the correct floor index.
     *
     * @return the floor index offset for auto-floor conversion
     */
    public int getAutoFloorBias() {
        switch (currentBuilding) {
            case BUILDING_NUCLEUS:
            case BUILDING_MURCHISON:
                return 1; // LG at index 0, so G = index 1
            case BUILDING_LIBRARY:
            default:
                return 0; // G at index 0
        }
    }

    /**
     * Sets the floor to display. When called from auto-floor, the floor number
     * is a logical floor (0=G, -1=LG, 1=Floor 1, etc.) and the building bias
     * is applied. When called manually, the floor number is the direct index.
     *
     * @param newFloor  the floor the user is at
     * @param autoFloor true if called by auto-floor feature
     */
    public void setCurrentFloor(int newFloor, boolean autoFloor) {
        if (currentFloorShapes == null || currentFloorShapes.isEmpty()) return;

        int requestedFloor = newFloor;
        int bias = getAutoFloorBias();

        if (autoFloor) {
            newFloor += bias;
            Log.d(TAG, "Auto-floor request logical=" + requestedFloor
                    + " bias=" + bias + " -> index=" + newFloor);
        }

        if (newFloor >= 0 && newFloor < currentFloorShapes.size()
                && newFloor != this.currentFloor) {
            this.currentFloor = newFloor;
            Log.i(TAG, "Set floor index=" + newFloor
                    + " display=" + getCurrentFloorDisplayName()
                    + " totalFloors=" + currentFloorShapes.size());
            drawFloorShapes(newFloor);
        } else {
            Log.d(TAG, "Ignored floor change request requested=" + requestedFloor
                    + " mappedIndex=" + newFloor
                    + " current=" + this.currentFloor
                    + " totalFloors=" + currentFloorShapes.size());
        }
    }

    /**
     * Increments the current floor and changes to a higher floor's map
     * (if a higher floor exists).
     */
    public void increaseFloor() {
        this.setCurrentFloor(currentFloor + 1, false);
    }

    /**
     * Decrements the current floor and changes to the lower floor's map
     * (if a lower floor exists).
     */
    public void decreaseFloor() {
        this.setCurrentFloor(currentFloor - 1, false);
    }

    /**
     * Sets the map overlay for the building if the user's current location is
     * inside a building and the overlay is not already set. Removes the overlay
     * if the user leaves all buildings.
     *
     * <p>Detection priority: floorplan API real polygon outlines first,
     * then legacy hard-coded rectangular boundaries as fallback.</p>
     */
    private void setBuildingOverlay() {
        try {
            int detected = detectCurrentBuilding();
            boolean inAnyBuilding = (detected != BUILDING_NONE);

            if (inAnyBuilding && !isIndoorMapSet) {
                currentBuilding = detected;
                String apiName;

                switch (detected) {
                    case BUILDING_NUCLEUS:
                        apiName = "nucleus_building";
                        currentFloor = 1;
                        floorHeight = NUCLEUS_FLOOR_HEIGHT;
                        break;
                    case BUILDING_LIBRARY:
                        apiName = "library";
                        currentFloor = 0;
                        floorHeight = LIBRARY_FLOOR_HEIGHT;
                        break;
                    case BUILDING_MURCHISON:
                        apiName = "murchison_house";
                        currentFloor = 1;
                        floorHeight = MURCHISON_FLOOR_HEIGHT;
                        break;
                    default:
                        return;
                }

                // Load floor shapes from cached API data
                FloorplanApiClient.BuildingInfo building =
                        SensorFusion.getInstance().getFloorplanBuilding(apiName);
                if (building != null) {
                    currentFloorShapes = building.getFloorShapesList();
                    Log.i(TAG, "Loaded floorplan building=" + apiName
                            + " floors=" + (currentFloorShapes == null ? 0 : currentFloorShapes.size()));
                    if (currentFloorShapes != null) {
                        for (int i = 0; i < currentFloorShapes.size(); i++) {
                            FloorplanApiClient.FloorShapes floorShapes = currentFloorShapes.get(i);
                            Log.d(TAG, "Floor index=" + i + " display=" + floorShapes.getDisplayName()
                                    + " features=" + floorShapes.getFeatures().size());
                        }
                    }
                }

                if (currentFloorShapes != null && !currentFloorShapes.isEmpty()) {
                    drawFloorShapes(currentFloor);
                    isIndoorMapSet = true;
                    Log.i(TAG, "Indoor overlay enabled building=" + apiName
                            + " startFloorIndex=" + currentFloor
                            + " display=" + getCurrentFloorDisplayName());
                }

            } else if (!inAnyBuilding && isIndoorMapSet) {
                clearDrawnShapes();
                isIndoorMapSet = false;
                currentBuilding = BUILDING_NONE;
                currentFloor = 0;
                currentFloorShapes = null;
                Log.i(TAG, "Indoor overlay disabled (left mapped buildings)");
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error with overlay: " + ex.toString());
        }
    }

    /**
     * Draws all vector shapes for the given floor index on the Google Map.
     * Clears any previously drawn shapes before drawing the new floor.
     *
     * @param floorIndex the floor index (0-based, matching FloorShapes list order)
     */
    private void drawFloorShapes(int floorIndex) {
        clearDrawnShapes();

        if (currentFloorShapes == null || floorIndex < 0
                || floorIndex >= currentFloorShapes.size()) return;

        FloorplanApiClient.FloorShapes floor = currentFloorShapes.get(floorIndex);
        Log.d(TAG, "Draw floor index=" + floorIndex + " display=" + floor.getDisplayName()
            + " featureCount=" + floor.getFeatures().size());
        for (FloorplanApiClient.MapShapeFeature feature : floor.getFeatures()) {
            String geoType = feature.getGeometryType();
            String indoorType = feature.getIndoorType();

            if ("MultiPolygon".equals(geoType) || "Polygon".equals(geoType)) {
                for (List<LatLng> ring : feature.getParts()) {
                    if (ring.size() < 3) continue;
                    Polygon p = gMap.addPolygon(new PolygonOptions()
                            .addAll(ring)
                            .strokeColor(getStrokeColor(indoorType))
                            .strokeWidth(5f)
                            .fillColor(getFillColor(indoorType)));
                    drawnPolygons.add(p);
                }
            } else if ("MultiLineString".equals(geoType)
                    || "LineString".equals(geoType)) {
                for (List<LatLng> line : feature.getParts()) {
                    if (line.size() < 2) continue;
                    Polyline pl = gMap.addPolyline(new PolylineOptions()
                            .addAll(line)
                            .color(getStrokeColor(indoorType))
                            .width(6f));
                    drawnPolylines.add(pl);
                }
            }
        }
    }

    /**
     * Removes all vector shapes currently drawn on the map.
     */
    private void clearDrawnShapes() {
        for (Polygon p : drawnPolygons) p.remove();
        for (Polyline p : drawnPolylines) p.remove();
        drawnPolygons.clear();
        drawnPolylines.clear();
    }

    /**
     * Returns the stroke colour for a given indoor feature type.
     *
     * @param indoorType the indoor_type property value
     * @return ARGB colour value
     */
    private int getStrokeColor(String indoorType) {
        if ("wall".equals(indoorType)) return WALL_STROKE;
        if ("room".equals(indoorType)) return ROOM_STROKE;
        return DEFAULT_STROKE;
    }

    /**
     * Returns the fill colour for a given indoor feature type.
     *
     * @param indoorType the indoor_type property value
     * @return ARGB colour value
     */
    private int getFillColor(String indoorType) {
        if ("room".equals(indoorType)) return ROOM_FILL;
        return Color.TRANSPARENT;
    }

    /**
     * Detects which building the user is currently in.
     * Checks floorplan API outline polygons first; falls back to legacy
     * hard-coded rectangular boundaries if no API match is found.
     *
     * @return building type constant, or {@link #BUILDING_NONE}
     */
    private int detectCurrentBuilding() {
        // Phase 1: API real polygon outlines
        List<FloorplanApiClient.BuildingInfo> apiBuildings =
                SensorFusion.getInstance().getFloorplanBuildings();
        for (FloorplanApiClient.BuildingInfo building : apiBuildings) {
            List<LatLng> outline = building.getOutlinePolygon();
            if (outline != null && outline.size() >= 3
                    && BuildingPolygon.pointInPolygon(currentLocation, outline)) {
                int type = resolveBuildingType(building.getName());
                if (type != BUILDING_NONE) return type;
            }
        }

        // Phase 2: legacy hard-coded fallback
        if (BuildingPolygon.inNucleus(currentLocation)) return BUILDING_NUCLEUS;
        if (BuildingPolygon.inLibrary(currentLocation)) return BUILDING_LIBRARY;
        if (BuildingPolygon.inMurchison(currentLocation)) return BUILDING_MURCHISON;

        return BUILDING_NONE;
    }

    /**
     * Maps a floorplan API building name to a building type constant.
     *
     * @param apiName building name from API (e.g. "nucleus_building")
     * @return building type constant, or {@link #BUILDING_NONE} if unrecognised
     */
    private int resolveBuildingType(String apiName) {
        if (apiName == null) return BUILDING_NONE;
        switch (apiName) {
            case "nucleus_building": return BUILDING_NUCLEUS;
            case "murchison_house":  return BUILDING_MURCHISON;
            case "library":          return BUILDING_LIBRARY;
            default:                 return BUILDING_NONE;
        }
    }

    /**
     * Draws green polyline indicators around all buildings with available
     * indoor floor maps. Uses floorplan API outlines when available,
     * falls back to legacy hard-coded polygons otherwise.
     */
    public void setIndicationOfIndoorMap() {
        List<FloorplanApiClient.BuildingInfo> apiBuildings =
                SensorFusion.getInstance().getFloorplanBuildings();

        boolean nucleusDrawn = false, libraryDrawn = false, murchisonDrawn = false;

        // Phase 1: draw API outlines
        for (FloorplanApiClient.BuildingInfo building : apiBuildings) {
            List<LatLng> outline = building.getOutlinePolygon();
            if (outline == null || outline.size() < 3) continue;

            List<LatLng> closed = new ArrayList<>(outline);
            closed.add(closed.get(0));
            gMap.addPolyline(new PolylineOptions().color(Color.GREEN).addAll(closed));

            switch (building.getName()) {
                case "nucleus_building": nucleusDrawn = true; break;
                case "library":          libraryDrawn = true; break;
                case "murchison_house":  murchisonDrawn = true; break;
            }
        }

        // Phase 2: fallback for buildings not covered by API
        if (!nucleusDrawn) {
            List<LatLng> pts = new ArrayList<>(BuildingPolygon.NUCLEUS_POLYGON);
            pts.add(pts.get(0));
            gMap.addPolyline(new PolylineOptions().color(Color.GREEN).addAll(pts));
        }
        if (!libraryDrawn) {
            List<LatLng> pts = new ArrayList<>(BuildingPolygon.LIBRARY_POLYGON);
            pts.add(pts.get(0));
            gMap.addPolyline(new PolylineOptions().color(Color.GREEN).addAll(pts));
        }
        if (!murchisonDrawn) {
            List<LatLng> pts = new ArrayList<>(BuildingPolygon.MURCHISON_POLYGON);
            pts.add(pts.get(0));
            gMap.addPolyline(new PolylineOptions().color(Color.GREEN).addAll(pts));
        }
    }
}
