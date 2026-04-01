package com.openpositioning.PositionMe.utils;

import android.graphics.Color;
import android.util.Log;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.ArrayList;
import java.util.Arrays;
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

    // Pre-rendered PNG floor plan images for Nucleus and Library (same as PositionMe_Private)
    private static final List<Integer> NUCLEUS_MAPS = Arrays.asList(
            R.drawable.nucleuslg, R.drawable.nucleusg, R.drawable.nucleus1,
            R.drawable.nucleus2, R.drawable.nucleus3);
    private static final List<Integer> LIBRARY_MAPS = Arrays.asList(
            R.drawable.libraryg, R.drawable.library1, R.drawable.library2,
            R.drawable.library3);

    // Fine-tune these to shift the PNG floor-plan overlay without affecting building detection.
    // +0.00005 ≈ +5 m north,  -0.00001 ≈ -1 m west  (1 deg lat ≈ 111 km, 1 deg lng ≈ 70 km here)
    private static final double OVERLAY_SHIFT_LAT = 0.0001;
    private static final double OVERLAY_SHIFT_LNG = 0.0;

    // Lat/lng bounds for positioning ground overlay images on the map
    private static final LatLngBounds NUCLEUS_BOUNDS = new LatLngBounds(
            new LatLng(BuildingPolygon.NUCLEUS_SW.latitude  + OVERLAY_SHIFT_LAT,
                       BuildingPolygon.NUCLEUS_SW.longitude + OVERLAY_SHIFT_LNG),
            new LatLng(BuildingPolygon.NUCLEUS_NE.latitude  + OVERLAY_SHIFT_LAT,
                       BuildingPolygon.NUCLEUS_NE.longitude + OVERLAY_SHIFT_LNG));
    private static final LatLngBounds LIBRARY_BOUNDS = new LatLngBounds(
            new LatLng(BuildingPolygon.LIBRARY_SW.latitude  + OVERLAY_SHIFT_LAT,
                       BuildingPolygon.LIBRARY_SW.longitude + OVERLAY_SHIFT_LNG),
            new LatLng(BuildingPolygon.LIBRARY_NE.latitude  + OVERLAY_SHIFT_LAT,
                       BuildingPolygon.LIBRARY_NE.longitude + OVERLAY_SHIFT_LNG));

    private GoogleMap gMap;
    private LatLng currentLocation;
    private boolean isIndoorMapSet = false;
    private int currentFloor;
    private int currentBuilding = BUILDING_NONE;
    // Floor height in meters derived from building metadata
    private float floorHeight;

    // Ground overlay for Nucleus and Library (pre-rendered PNG floor plan images)
    private GroundOverlay groundOverlay;

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
    private static final int WALL_STROKE = Color.argb(200, 80, 80, 80);
    private static final int ROOM_STROKE = Color.argb(180, 33, 150, 243);
    private static final int ROOM_FILL = Color.argb(40, 33, 150, 243);
    private static final int DEFAULT_STROKE = Color.argb(150, 100, 100, 100);

    // Last known user location (lat/lng on map)
    private LatLng lastLocation;

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
        this.lastLocation = currentLocation;
        setBuildingOverlay();
    }

    /** Returns last location stored via setCurrentLocation (may be null). */
    public LatLng getLastLocation() {
        return lastLocation;
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
     * Returns the current floor shapes object, or null if unavailable.
     */
    public FloorplanApiClient.FloorShapes getCurrentFloorShape() {
        if (currentFloorShapes == null) return null;
        if (currentFloor < 0 || currentFloor >= currentFloorShapes.size()) return null;
        return currentFloorShapes.get(currentFloor);
    }

    /**
     * Returns true if the last known location is within the given radius (meters)
     * of any stairs or lift feature on the current floor.
     *
     * @param radiusMeters proximity threshold in meters
     * @return true when near stairs/lift; false otherwise or when data missing
     */
    public boolean isNearCrossFloorFeature(float radiusMeters) {
        if (lastLocation == null || currentFloorShapes == null) return false;
        if (currentFloor < 0 || currentFloor >= currentFloorShapes.size()) return false;

        FloorplanApiClient.FloorShapes floor = currentFloorShapes.get(currentFloor);
        if (floor == null || floor.getFeatures() == null) return false;

        double minDistance = Double.MAX_VALUE;
        for (FloorplanApiClient.MapShapeFeature feature : floor.getFeatures()) {
            String type = feature.getIndoorType();
            if (!"stairs".equals(type) && !"lift".equals(type)) continue;
            for (List<LatLng> part : feature.getParts()) {
                for (LatLng point : part) {
                    double d = UtilFunctions.distanceBetweenPoints(lastLocation, point);
                    if (d < minDistance) {
                        minDistance = d;
                    }
                    if (minDistance <= radiusMeters) {
                        return true;
                    }
                }
            }
        }
        return false;
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
        // Fallback display names for PNG buildings when API data not yet loaded
        if (currentBuilding == BUILDING_NUCLEUS) {
            String[] names = {"LG", "G", "1", "2", "3"};
            if (currentFloor >= 0 && currentFloor < names.length) return names[currentFloor];
        } else if (currentBuilding == BUILDING_LIBRARY) {
            String[] names = {"G", "1", "2", "3"};
            if (currentFloor >= 0 && currentFloor < names.length) return names[currentFloor];
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
        boolean hasPng = (currentBuilding == BUILDING_NUCLEUS
                || currentBuilding == BUILDING_LIBRARY);

        // For PNG buildings we don't need API data; for others we do
        if (!hasPng && (currentFloorShapes == null || currentFloorShapes.isEmpty())) return;

        if (autoFloor) {
            newFloor += getAutoFloorBias();
        }

        int maxFloor = hasPng
                ? (currentBuilding == BUILDING_NUCLEUS ? NUCLEUS_MAPS.size() : LIBRARY_MAPS.size())
                : currentFloorShapes.size();

        if (newFloor >= 0 && newFloor < maxFloor && newFloor != this.currentFloor) {
            this.currentFloor = newFloor;
            if (hasPng) {
                showGroundOverlay(currentBuilding, newFloor);
            } else {
                drawFloorShapes(newFloor);
            }
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

                // Always load floor shapes from cached API data (used by particle filter / auto-floor)
                FloorplanApiClient.BuildingInfo building =
                        SensorFusion.getInstance().getFloorplanBuilding(apiName);
                if (building != null) {
                    currentFloorShapes = building.getFloorShapesList();
                }

                // Display: PNG ground overlay for Nucleus/Library; API vectors for Murchison
                if (detected == BUILDING_NUCLEUS || detected == BUILDING_LIBRARY) {
                    showGroundOverlay(detected, currentFloor);
                    isIndoorMapSet = true;
                } else if (currentFloorShapes != null && !currentFloorShapes.isEmpty()) {
                    drawFloorShapes(currentFloor);
                    isIndoorMapSet = true;
                }

            } else if (!inAnyBuilding && isIndoorMapSet) {
                clearDrawnShapes(); // also removes groundOverlay
                isIndoorMapSet = false;
                currentBuilding = BUILDING_NONE;
                currentFloor = 0;
                currentFloorShapes = null;
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error with overlay: " + ex.toString());
        }
    }

    /**
     * Displays the pre-rendered PNG floor plan image for Nucleus or Library as a
     * ground overlay. Replaces any existing ground overlay.
     *
     * @param building   BUILDING_NUCLEUS or BUILDING_LIBRARY
     * @param floorIndex floor index matching the NUCLEUS_MAPS / LIBRARY_MAPS list
     */
    private void showGroundOverlay(int building, int floorIndex) {
        if (groundOverlay != null) {
            groundOverlay.remove();
            groundOverlay = null;
        }
        List<Integer> maps;
        LatLngBounds bounds;
        if (building == BUILDING_NUCLEUS) {
            maps = NUCLEUS_MAPS;
            bounds = NUCLEUS_BOUNDS;
        } else if (building == BUILDING_LIBRARY) {
            maps = LIBRARY_MAPS;
            bounds = LIBRARY_BOUNDS;
        } else {
            return;
        }
        if (floorIndex < 0 || floorIndex >= maps.size()) return;
        groundOverlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                .image(BitmapDescriptorFactory.fromResource(maps.get(floorIndex)))
                .positionFromBounds(bounds)
                .transparency(0.1f)); // slight transparency so satellite map is visible underneath
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
        for (FloorplanApiClient.MapShapeFeature feature : floor.getFeatures()) {
            String geoType    = feature.getGeometryType();
            String indoorType = feature.getIndoorType();

            // Prefer API-provided colours; fall back to type-based defaults
            int stroke = parseApiColor(feature.getStrokeColor(), getStrokeColor(indoorType));
            int fill   = parseApiColor(feature.getFillColor(),   getFillColor(indoorType));
            float width = feature.getStrokeWidth() > 0
                    ? Math.max(feature.getStrokeWidth(), 4f) : 5f;

            if ("MultiPolygon".equals(geoType) || "Polygon".equals(geoType)) {
                for (List<LatLng> ring : feature.getParts()) {
                    if (ring.size() < 3) continue;
                    Polygon p = gMap.addPolygon(new PolygonOptions()
                            .addAll(ring)
                            .strokeColor(stroke)
                            .strokeWidth(width)
                            .fillColor(fill));
                    drawnPolygons.add(p);
                }
            } else if ("MultiLineString".equals(geoType)
                    || "LineString".equals(geoType)) {
                for (List<LatLng> line : feature.getParts()) {
                    if (line.size() < 2) continue;
                    Polyline pl = gMap.addPolyline(new PolylineOptions()
                            .addAll(line)
                            .color(stroke)
                            .width(width));
                    drawnPolylines.add(pl);
                }
            }
        }
    }

    /**
     * Parses an API colour string (e.g. "#666666") to an ARGB int.
     * Returns {@code fallback} if the string is null, empty, or unparseable.
     */
    private int parseApiColor(String colorStr, int fallback) {
        if (colorStr == null || colorStr.trim().isEmpty()) return fallback;
        try {
            return Color.parseColor(colorStr.trim());
        } catch (IllegalArgumentException ex) {
            return fallback;
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
        if (groundOverlay != null) {
            groundOverlay.remove();
            groundOverlay = null;
        }
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
