package com.openpositioning.PositionMe.utils;

import android.graphics.Color;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages indoor vector floor overlays for the selected building.
 *
 * This class is only responsible for:
 * - storing the current selected building
 * - storing the current floor index
 * - drawing vector indoor shapes on the map
 * - converting between logical floors and floor-shape indices
 */
public class IndoorMapManager {

    public static final int BUILDING_NONE = 0;
    public static final int BUILDING_NUCLEUS = 1;
    public static final int BUILDING_LIBRARY = 2;
    public static final int BUILDING_MURCHISON = 3;

    public static final float NUCLEUS_FLOOR_HEIGHT = 4.2F;
    public static final float LIBRARY_FLOOR_HEIGHT = 3.6F;
    public static final float MURCHISON_FLOOR_HEIGHT = 4.0F;

    private static final int WALL_STROKE = Color.argb(255, 34, 34, 34);
    private static final int ROOM_STROKE = Color.argb(255, 60, 60, 60);
    private static final int ROOM_FILL = Color.argb(18, 0, 0, 0);
    private static final int STAIRS_STROKE = Color.argb(255, 220, 20, 60);
    private static final int STAIRS_FILL = Color.argb(90, 255, 0, 0);
    private static final int LIFT_STROKE = Color.argb(255, 200, 0, 0);
    private static final int LIFT_FILL = Color.argb(70, 255, 0, 0);
    private static final int DEFAULT_STROKE = Color.argb(255, 50, 50, 50);

    private final GoogleMap gMap;

    @Nullable
    private LatLng currentLocation;

    private boolean isIndoorMapSet = false;
    private int currentFloor = 0;
    private int currentBuilding = BUILDING_NONE;
    private float floorHeight = 0f;

    private final List<Polygon> drawnPolygons = new ArrayList<>();
    private final List<Polyline> drawnPolylines = new ArrayList<>();

    @Nullable
    private List<FloorplanApiClient.FloorShapes> currentFloorShapes;

    private boolean vectorBaseplateEnabled = false;

    @Nullable
    public FloorplanApiClient.FloorShapes getFloorShapesForIndex(int floorIndex) {
        if (currentFloorShapes == null || currentFloorShapes.isEmpty()) {
            return null;
        }
        int safe = clampFloorIndex(floorIndex);
        return currentFloorShapes.get(safe);
    }

    @Nullable
    public FloorplanApiClient.FloorShapes getFloorShapesForLogicalFloor(int logicalFloor) {
        if (currentFloorShapes == null || currentFloorShapes.isEmpty()) {
            return null;
        }
        return getFloorShapesForIndex(logicalFloorToIndex(logicalFloor));
    }

    @NonNull
    public List<FloorplanApiClient.FloorShapes> getCurrentFloorShapesList() {
        return currentFloorShapes == null ? new ArrayList<>() : new ArrayList<>(currentFloorShapes);
    }

    public IndoorMapManager(GoogleMap map) {
        this.gMap = map;
    }

    public void setCurrentLocation(@Nullable LatLng currentLocation) {
        this.currentLocation = currentLocation;
    }

    public float getFloorHeight() {
        return floorHeight;
    }

    public boolean getIsIndoorMapSet() {
        return isIndoorMapSet;
    }

    public int getCurrentBuilding() {
        return currentBuilding;
    }

    public int getCurrentFloor() {
        return currentFloor;
    }

    public String getCurrentFloorDisplayName() {
        if (currentFloorShapes != null
                && currentFloor >= 0
                && currentFloor < currentFloorShapes.size()) {
            String displayName = currentFloorShapes.get(currentFloor).getDisplayName();
            if (displayName == null || displayName.isEmpty()) {
                return String.valueOf(currentFloor);
            }
            return formatFloorLabelForDisplay(displayName);
        }
        return String.valueOf(currentFloor);
    }

    /**
     * Building-dependent bias used when a direct floor label match is not found.
     */
    public int getAutoFloorBias() {
        switch (currentBuilding) {
            case BUILDING_NUCLEUS:
            case BUILDING_MURCHISON:
                return 1;
            case BUILDING_LIBRARY:
            default:
                return 0;
        }
    }

    /**
     * Convert logical floor to the API floor-shape index.
     *
     * Examples:
     * -1 -> LG
     *  0 -> G
     *  1 -> F1
     *  2 -> F2
     */
    public int logicalFloorToIndex(int logicalFloor) {
        String targetFloorLabel;
        if (logicalFloor <= -1) {
            targetFloorLabel = "LG";
        } else if (logicalFloor == 0) {
            targetFloorLabel = "G";
        } else {
            targetFloorLabel = String.valueOf(logicalFloor);
        }

        int matchingIndex = findFloorIndexByCanonicalLabel(targetFloorLabel);
        if (matchingIndex >= 0) {
            return matchingIndex;
        }

        return clampFloorIndex(logicalFloor + getAutoFloorBias());
    }

    /**
     * Converts a building floor index into a logical floor:
     * LG -> -1
     * G  -> 0
     * 1  -> 1
     * 2  -> 2
     * 3  -> 3
     *
     * This is important because map matching often uses floor indices,
     * while the particle filter works better with logical floors.
     */
    public int indexToLogicalFloor(int floorIndex) {
        if (currentFloorShapes == null || currentFloorShapes.isEmpty()) {
            return floorIndex;
        }

        int safeIndex = clampFloorIndex(floorIndex);
        String displayName = currentFloorShapes.get(safeIndex).getDisplayName();
        String canonical = canonicalFloorLabel(displayName);

        switch (canonical) {
            case "LG":
                return -1;
            case "G":
                return 0;
            case "1":
                return 1;
            case "2":
                return 2;
            case "3":
                return 3;
            default:
                return safeIndex - getAutoFloorBias();
        }
    }

    public int clampFloorIndex(int floorIndex) {
        if (currentFloorShapes == null || currentFloorShapes.isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.min(floorIndex, currentFloorShapes.size() - 1));
    }

    public void setVectorBaseplateEnabled(boolean enabled) {
        if (this.vectorBaseplateEnabled != enabled) {
            this.vectorBaseplateEnabled = enabled;

            if (isIndoorMapSet
                    && currentFloorShapes != null
                    && currentFloor >= 0
                    && currentFloor < currentFloorShapes.size()) {
                drawFloorShapes(currentFloor);
            }
        }
    }

    /**
     * Set the current selected building and load its floor shapes.
     */
    public void setSelectedBuilding(@Nullable FloorplanApiClient.BuildingInfo building) {
        clearDrawnShapes();

        if (building == null) {
            clearIndoorMap();
            return;
        }

        currentBuilding = resolveBuildingType(building.getName());
        currentFloorShapes = building.getFloorShapesList();
        currentFloor = -1;
        isIndoorMapSet = currentFloorShapes != null && !currentFloorShapes.isEmpty();

        switch (currentBuilding) {
            case BUILDING_NUCLEUS:
                floorHeight = NUCLEUS_FLOOR_HEIGHT;
                break;
            case BUILDING_LIBRARY:
                floorHeight = LIBRARY_FLOOR_HEIGHT;
                break;
            case BUILDING_MURCHISON:
                floorHeight = MURCHISON_FLOOR_HEIGHT;
                break;
            default:
                floorHeight = 0f;
                break;
        }
    }

    public void clearIndoorMap() {
        clearDrawnShapes();
        isIndoorMapSet = false;
        currentBuilding = BUILDING_NONE;
        currentFloor = 0;
        currentFloorShapes = null;
        floorHeight = 0f;
    }

    /**
     * Set current floor and redraw if needed.
     *
     * @param autoFloor if true, input is treated as logical floor
     */
    public void setCurrentFloor(int newFloor, boolean autoFloor) {
        if (currentFloorShapes == null || currentFloorShapes.isEmpty()) {
            return;
        }

        if (autoFloor) {
            newFloor = logicalFloorToIndex(newFloor);
        } else {
            newFloor = clampFloorIndex(newFloor);
        }

        if (newFloor != this.currentFloor || (drawnPolygons.isEmpty() && drawnPolylines.isEmpty())) {
            this.currentFloor = newFloor;
            drawFloorShapes(newFloor);
        }
    }

    public void increaseFloor() {
        setCurrentFloor(currentFloor + 1, false);
    }

    public void decreaseFloor() {
        setCurrentFloor(currentFloor - 1, false);
    }

    private int findFloorIndexByCanonicalLabel(String targetFloorLabel) {
        if (currentFloorShapes == null || currentFloorShapes.isEmpty()) {
            return -1;
        }

        String canonicalTarget = canonicalFloorLabel(targetFloorLabel);
        for (int i = 0; i < currentFloorShapes.size(); i++) {
            String candidateDisplayName = currentFloorShapes.get(i).getDisplayName();
            if (canonicalTarget.equals(canonicalFloorLabel(candidateDisplayName))) {
                return i;
            }
        }
        return -1;
    }

    private String formatFloorLabelForDisplay(String rawFloorLabel) {
        String canonicalFloor = canonicalFloorLabel(rawFloorLabel);
        switch (canonicalFloor) {
            case "LG":
                return "LG";
            case "G":
                return "G";
            case "1":
                return "F1";
            case "2":
                return "F2";
            case "3":
                return "F3";
            default:
                return rawFloorLabel == null ? "" : rawFloorLabel;
        }
    }

    private String canonicalFloorLabel(String rawFloorLabel) {
        if (rawFloorLabel == null) {
            return "";
        }

        String normalized = rawFloorLabel.trim().toUpperCase().replace(" ", "");
        switch (normalized) {
            case "LG":
            case "LOWERGROUND":
            case "LOWERG":
            case "B1":
            case "BASEMENT1":
                return "LG";
            case "G":
            case "GF":
            case "GROUND":
            case "GROUNDFLOOR":
            case "0":
                return "G";
            case "1":
            case "F1":
            case "FIRST":
            case "FIRSTFLOOR":
                return "1";
            case "2":
            case "F2":
            case "SECOND":
            case "SECONDFLOOR":
                return "2";
            case "3":
            case "F3":
            case "THIRD":
            case "THIRDFLOOR":
                return "3";
            default:
                return normalized;
        }
    }

    /**
     * Draw all shapes for one floor.
     */
    private void drawFloorShapes(int floorIndex) {
        clearDrawnShapes();

        if (currentFloorShapes == null || floorIndex < 0 || floorIndex >= currentFloorShapes.size()) {
            return;
        }

        FloorplanApiClient.FloorShapes floor = currentFloorShapes.get(floorIndex);

        for (FloorplanApiClient.MapShapeFeature feature : floor.getFeatures()) {
            String geoType = feature.getGeometryType();
            String indoorType = feature.getIndoorType();

            if ("MultiPolygon".equals(geoType) || "Polygon".equals(geoType)) {
                for (List<LatLng> ring : feature.getParts()) {
                    if (ring.size() < 3) {
                        continue;
                    }

                    if (vectorBaseplateEnabled) {
                        Polygon underlay = gMap.addPolygon(new PolygonOptions()
                                .addAll(ring)
                                .strokeColor(Color.argb(0, 255, 255, 255))
                                .strokeWidth(0f)
                                .fillColor(Color.argb(0, 255, 255, 255))
                                .zIndex(6f));
                        drawnPolygons.add(underlay);
                    }

                    Polygon p = gMap.addPolygon(new PolygonOptions()
                            .addAll(ring)
                            .strokeColor(getStrokeColor(indoorType))
                            .strokeWidth(4f)
                            .fillColor(getFillColor(indoorType))
                            .zIndex(10f));
                    drawnPolygons.add(p);
                }
            } else if ("MultiLineString".equals(geoType) || "LineString".equals(geoType)) {
                for (List<LatLng> line : feature.getParts()) {
                    if (line.size() < 2) {
                        continue;
                    }

                    if (vectorBaseplateEnabled) {
                        Polyline underlay = gMap.addPolyline(new PolylineOptions()
                                .addAll(line)
                                .color(Color.argb(0, 255, 255, 255))
                                .width(0f)
                                .zIndex(6f));
                        drawnPolylines.add(underlay);
                    }

                    Polyline pl = gMap.addPolyline(new PolylineOptions()
                            .addAll(line)
                            .color(getStrokeColor(indoorType))
                            .width(4.5f)
                            .zIndex(10f));
                    drawnPolylines.add(pl);
                }
            }
        }
    }

    private void clearDrawnShapes() {
        for (Polygon p : drawnPolygons) {
            p.remove();
        }
        for (Polyline p : drawnPolylines) {
            p.remove();
        }
        drawnPolygons.clear();
        drawnPolylines.clear();
    }

    private int getStrokeColor(String indoorType) {
        if ("wall".equals(indoorType)) {
            return WALL_STROKE;
        }
        if ("room".equals(indoorType)) {
            return ROOM_STROKE;
        }
        if ("stairs".equals(indoorType)) {
            return STAIRS_STROKE;
        }
        if ("lift".equals(indoorType)) {
            return LIFT_STROKE;
        }
        return DEFAULT_STROKE;
    }

    private int getFillColor(String indoorType) {
        if ("room".equals(indoorType)) {
            return ROOM_FILL;
        }
        if ("stairs".equals(indoorType)) {
            return STAIRS_FILL;
        }
        if ("lift".equals(indoorType)) {
            return LIFT_FILL;
        }
        return Color.TRANSPARENT;
    }

    private int resolveBuildingType(String apiName) {
        if (apiName == null) {
            return BUILDING_NONE;
        }

        switch (apiName) {
            case "nucleus_building":
                return BUILDING_NUCLEUS;
            case "murchison_house":
                return BUILDING_MURCHISON;
            case "library":
                return BUILDING_LIBRARY;
            default:
                return BUILDING_NONE;
        }
    }
}