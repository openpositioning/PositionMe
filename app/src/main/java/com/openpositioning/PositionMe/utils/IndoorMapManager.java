package com.openpositioning.PositionMe.utils;

import android.graphics.Color;
import android.util.Log;
import androidx.annotation.Nullable;

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

public class IndoorMapManager {

    private static final String TAG = "IndoorMapManager";

    public static final int BUILDING_NONE = 0;
    public static final int BUILDING_NUCLEUS = 1;
    public static final int BUILDING_LIBRARY = 2;
    public static final int BUILDING_MURCHISON = 3;

    private final GoogleMap gMap;
    private LatLng currentLocation;
    private boolean isIndoorMapSet = false;
    private int currentFloor = 0;
    private int currentBuilding = BUILDING_NONE;
    private float floorHeight;

    private final List<Polygon> drawnPolygons = new ArrayList<>();
    private final List<Polyline> drawnPolylines = new ArrayList<>();
    private List<FloorplanApiClient.FloorShapes> currentFloorShapes;
    private boolean vectorBaseplateEnabled = false;

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

    public IndoorMapManager(GoogleMap map) {
        this.gMap = map;
    }

    public void setCurrentLocation(LatLng currentLocation) {
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

    public int indexToLogicalFloor(int floorIndex) {
        if (currentFloorShapes == null || currentFloorShapes.isEmpty()) {
            return floorIndex;
        }

        int clampedIndex = clampFloorIndex(floorIndex);
        if (clampedIndex < 0 || clampedIndex >= currentFloorShapes.size()) {
            return floorIndex;
        }

        String displayName = currentFloorShapes.get(clampedIndex).getDisplayName();
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
                return clampedIndex - getAutoFloorBias();
        }
    }

    public int indexToLogicalFloor(@Nullable Integer floorIndex) {
        if (floorIndex == null) {
            return 0;
        }
        return indexToLogicalFloor(floorIndex.intValue());
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
            if (isIndoorMapSet && currentFloorShapes != null && currentFloor >= 0 && currentFloor < currentFloorShapes.size()) {
                drawFloorShapes(currentFloor);
            }
        }
    }

    public void setSelectedBuilding(FloorplanApiClient.BuildingInfo building) {
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

                FloorplanApiClient.BuildingInfo building =
                        SensorFusion.getInstance().getFloorplanBuilding(apiName);
                if (building != null) {
                    currentFloorShapes = building.getFloorShapesList();
                }

                if (currentFloorShapes != null && !currentFloorShapes.isEmpty()) {
                    drawFloorShapes(currentFloor);
                    isIndoorMapSet = true;
                }

            } else if (!inAnyBuilding && isIndoorMapSet) {
                clearIndoorMap();
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error with overlay: " + ex);
        }
    }

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

    private int detectCurrentBuilding() {
        List<FloorplanApiClient.BuildingInfo> apiBuildings = SensorFusion.getInstance().getFloorplanBuildings();

        if (apiBuildings != null) {
            for (FloorplanApiClient.BuildingInfo building : apiBuildings) {
                List<LatLng> outline = building.getOutlinePolygon();
                if (outline != null && outline.size() >= 3
                        && BuildingPolygon.pointInPolygon(currentLocation, outline)) {
                    int type = resolveBuildingType(building.getName());
                    if (type != BUILDING_NONE) {
                        return type;
                    }
                }
            }
        }

        if (BuildingPolygon.inNucleus(currentLocation)) {
            return BUILDING_NUCLEUS;
        }
        if (BuildingPolygon.inLibrary(currentLocation)) {
            return BUILDING_LIBRARY;
        }
        if (BuildingPolygon.inMurchison(currentLocation)) {
            return BUILDING_MURCHISON;
        }

        return BUILDING_NONE;
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

    public void setIndicationOfIndoorMap() {
        List<FloorplanApiClient.BuildingInfo> apiBuildings = SensorFusion.getInstance().getFloorplanBuildings();

        boolean nucleusDrawn = false;
        boolean libraryDrawn = false;
        boolean murchisonDrawn = false;

        if (apiBuildings != null) {
            for (FloorplanApiClient.BuildingInfo building : apiBuildings) {
                List<LatLng> outline = building.getOutlinePolygon();
                if (outline == null || outline.size() < 3) {
                    continue;
                }

                List<LatLng> closed = new ArrayList<>(outline);
                closed.add(closed.get(0));
                gMap.addPolyline(new PolylineOptions().color(Color.GREEN).addAll(closed));

                switch (building.getName()) {
                    case "nucleus_building":
                        nucleusDrawn = true;
                        break;
                    case "library":
                        libraryDrawn = true;
                        break;
                    case "murchison_house":
                        murchisonDrawn = true;
                        break;
                }
            }
        }

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
