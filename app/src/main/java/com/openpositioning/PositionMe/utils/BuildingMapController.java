package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.data.remote.Building;
import com.openpositioning.PositionMe.data.remote.FloorPlan;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper class to handle Indoor Map Logic.
 * Encapsulates server downloads, building rendering, and floor switching.
 */
public class BuildingMapController {

    private static final String TAG = "BuildingController";
    private final Context context;
    private final GoogleMap gMap;
    private final ServerCommunications serverCommunications;

    // State
    private Building selectedBuilding;
    private int currentFloorIndex = 0;
    private final List<Polyline> currentFloorLines = new ArrayList<>();
    private final Set<String> loadedBuildingNames = new HashSet<>();

    // Listener for UI updates
    public interface BuildingSelectionListener {
        void onBuildingSelected(String buildingName, String floorCode);
    }
    private BuildingSelectionListener selectionListener;

    public BuildingMapController(Context context, GoogleMap map) {
        this.context = context;
        this.gMap = map;
        this.serverCommunications = new ServerCommunications(context);
    }

    public void setSelectionListener(BuildingSelectionListener listener) {
        this.selectionListener = listener;
    }

    // --- Core Logic ---

    public void downloadNearbyBuildings(LatLng center) {
        if (center == null) return;
        Log.d(TAG, "Downloading buildings near: " + center.toString());

        serverCommunications.getNearbyBuildings(center.latitude, center.longitude, new ServerCommunications.BuildingCallback() {
            @Override
            public void onBuildingsReceived(List<Building> buildings) {
                if (gMap == null) return;

                if (buildings.isEmpty()) {
                    Log.d(TAG, "No buildings found nearby.");
                    return;
                }

                boolean isFirst = true;
                for (Building b : buildings) {
                    String name = (b.getName() != null) ? b.getName() : "Unknown";
                    if (!loadedBuildingNames.contains(name)) {
                        loadedBuildingNames.add(name);
                        drawSingleBuildingOutline(b);
                    }

                    // Auto-select the first building
                    if (isFirst) {
                        onBuildingSelected(b);
                        isFirst = false;
                    }
                }
            }
            @Override
            public void onError(String message) {
                Log.e(TAG, "Error: " + message);
            }
        });
    }

    private void drawSingleBuildingOutline(Building building) {
        if (building.getOutline() == null || building.getOutline().isEmpty()) return;

        List<LatLng> points = new ArrayList<>();
        for (List<Double> point : building.getOutline()) {
            if (point.size() >= 2) points.add(new LatLng(point.get(0), point.get(1)));
        }

        int strokeColor = Color.RED;
        int fillColor = Color.argb(50, 255, 0, 0);
        String name = (building.getName() != null) ? building.getName().toLowerCase() : "";

        if (name.contains("nucleus")) {
            strokeColor = Color.rgb(255, 191, 0);
            fillColor = Color.argb(50, 255, 191, 0);
        } else if (name.contains("fleeming") || name.contains("jenkin")) {
            strokeColor = Color.BLUE;
            fillColor = Color.argb(50, 0, 0, 255);
        }

        Polygon polygon = gMap.addPolygon(new PolygonOptions()
                .addAll(points)
                .strokeColor(strokeColor)
                .fillColor(fillColor)
                .strokeWidth(5)
                .clickable(true));
        polygon.setTag(building);
    }

    public void onPolygonClick(Polygon polygon) {
        Object tag = polygon.getTag();
        if (tag instanceof Building) {
            onBuildingSelected((Building) tag);
        }
    }

    private void onBuildingSelected(Building building) {
        this.selectedBuilding = building;
        Toast.makeText(context, "Selected: " + building.getName(), Toast.LENGTH_SHORT).show();

        if (building.getFloors() != null && !building.getFloors().isEmpty()) {
            // Ensure(-1) < GF(0) < 1F(1)
            Collections.sort(building.getFloors(), new Comparator<FloorPlan>() {
                @Override
                public int compare(FloorPlan f1, FloorPlan f2) {
                    int v1 = parseFloorCode(f1.getFloorCode());
                    int v2 = parseFloorCode(f2.getFloorCode());
                    return Integer.compare(v1, v2);
                }
            });

            // Debug log
            Log.d(TAG, "--- Sorted Floors ---");
            for (int i=0; i<building.getFloors().size(); i++) {
                String code = building.getFloors().get(i).getFloorCode();
                Log.d(TAG, "Index " + i + ": " + code + " -> Val: " + parseFloorCode(code));
            }
        }

        this.currentFloorIndex = 0;
        if (building.getFloors() != null) {
            for (int i = 0; i < building.getFloors().size(); i++) {
                String code = building.getFloors().get(i).getFloorCode();
                if (parseFloorCode(code) == 0) { // 找到 GF
                    this.currentFloorIndex = i;
                    break;
                }
            }
        }

        drawCurrentFloorWalls();
    }

    private int parseFloorCode(String code) {
        if (code == null) return 0;
        String raw = code.toUpperCase().trim();


        if (raw.equals("BF") || raw.equals("B")) return -1;
        if (raw.equals("GF") || raw.equals("G") || raw.equals("0") || raw.contains("GROUND")) return 0;

        if (raw.contains("BASEMENT") || raw.contains("BF")) return -1;

        if (raw.startsWith("B")) {

            String digits = raw.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                try {
                    return -Integer.parseInt(digits);
                } catch (Exception e) {}
            }
            return -1;
        }

        try {
            Matcher matcher = Pattern.compile("-?\\d+").matcher(raw);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group());
            }
        } catch (Exception ignored) {}

        return 0;
    }

    public void changeFloor(int delta) {
        if (selectedBuilding == null || selectedBuilding.getFloors() == null || selectedBuilding.getFloors().isEmpty()) {
            return;
        }

        int newIndex = currentFloorIndex + delta;

        if (newIndex >= 0 && newIndex < selectedBuilding.getFloors().size()) {
            currentFloorIndex = newIndex;
            drawCurrentFloorWalls();
        } else {
        }
    }

    public int getCurrentFloorValue() {
        if (selectedBuilding == null || selectedBuilding.getFloors() == null || selectedBuilding.getFloors().isEmpty()) {
            return 0;
        }
        try {
            if(currentFloorIndex >= selectedBuilding.getFloors().size()) currentFloorIndex = selectedBuilding.getFloors().size()-1;
            if(currentFloorIndex < 0) currentFloorIndex = 0;

            String code = selectedBuilding.getFloors().get(currentFloorIndex).getFloorCode();
            return parseFloorCode(code);
        } catch (Exception e) {
            return 0;
        }
    }

    private void drawCurrentFloorWalls() {
        for (Polyline line : currentFloorLines) line.remove();
        currentFloorLines.clear();

        if (selectedBuilding == null || selectedBuilding.getFloors().isEmpty()) return;

        if (currentFloorIndex < 0) currentFloorIndex = 0;
        if (currentFloorIndex >= selectedBuilding.getFloors().size()) {
            currentFloorIndex = selectedBuilding.getFloors().size() - 1;
        }

        FloorPlan floor = selectedBuilding.getFloors().get(currentFloorIndex);

        if (selectionListener != null) {
            selectionListener.onBuildingSelected(selectedBuilding.getName(), floor.getFloorCode());
        }

        if (floor.getWalls() != null) {
            for (List<List<Double>> wallPath : floor.getWalls()) {
                List<LatLng> points = new ArrayList<>();
                for (List<Double> point : wallPath) {
                    if (point.size() >= 2) points.add(new LatLng(point.get(0), point.get(1)));
                }
                if (!points.isEmpty()) {
                    Polyline line = gMap.addPolyline(new PolylineOptions()
                            .addAll(points)
                            .color(Color.YELLOW)
                            .width(6)
                            .zIndex(100));
                    currentFloorLines.add(line);
                }
            }
        }
    }
}