package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper class to handle indoor map logic.
 *
 * New code guide:
 * 1. Nearby-building download and outline rendering.
 * 2. Building selection persistence and floor normalization.
 * 3. Debounced floor redraws for walls, stairs, and lifts.
 * 4. Floor-plan export for SensorFusion map matching.
 */
public class BuildingMapController {

    private static final String TAG = "BuildingController";
    private static final boolean DRAW_DEBUG_LOGS = false;

    private final Context context;
    private final GoogleMap gMap;
    private final ServerCommunications serverCommunications;
    // Posts floor rendering work to the main thread.
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingFloorRenderTask;
    private int floorRenderSeq = 0;
    private static final long FLOOR_RENDER_DEBOUNCE_MS = 80L;

    private Building selectedBuilding;
    private int currentFloorIndex = 0;
    // Tracks active floor overlays and loaded building outlines.
    private final List<Polyline> currentFloorLines = new ArrayList<>();
    private final List<Polygon> currentFloorPolygons = new ArrayList<>();
    private final Set<String> loadedBuildingNames = new HashSet<>();

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

    // Loads nearby buildings, draws their outlines, and auto-selects the main venue.
    public void downloadNearbyBuildings(LatLng center) {
        if (center == null) return;
        Log.d(TAG, "Downloading buildings near: " + center);

        serverCommunications.getNearbyBuildings(center.latitude, center.longitude, new ServerCommunications.BuildingCallback() {
            @Override
            public void onBuildingsReceived(List<Building> buildings) {
                // Keep all map operations on the main thread.
                mainHandler.post(() -> {
                    if (gMap == null) return;
                    if (buildings == null || buildings.isEmpty()) {
                        Log.d(TAG, "No buildings found nearby.");
                        return;
                    }

                    Building targetBuilding = buildings.get(0);
                    for (Building b : buildings) {
                        String name = (b.getName() != null) ? b.getName() : "Unknown";
                        if (!loadedBuildingNames.contains(name)) {
                            loadedBuildingNames.add(name);
                            drawSingleBuildingOutline(b);
                        }
                        if (name.toLowerCase().contains("nucleus")) {
                            targetBuilding = b;
                        }
                    }
                    onBuildingSelected(targetBuilding);
                });
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "Error: " + message);
            }
        });
    }

    // Draws only the outer building footprint used for venue selection.
    private void drawSingleBuildingOutline(Building building) {
        if (building == null || building.getOutline() == null || building.getOutline().isEmpty()) return;

        List<LatLng> points = new ArrayList<>();
        for (List<Double> point : building.getOutline()) {
            if (point != null && point.size() >= 2) {
                points.add(new LatLng(point.get(0), point.get(1)));
            }
        }
        if (points.isEmpty()) return;

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
                .strokeWidth(5f)
                .clickable(true));
        polygon.setTag(building);
    }

    public void onPolygonClick(Polygon polygon) {
        if (polygon == null) return;
        Object tag = polygon.getTag();
        if (tag instanceof Building) {
            onBuildingSelected((Building) tag);
        }
    }

    private void onBuildingSelected(Building building) {
        if (building == null) return;
        this.selectedBuilding = building;
        VenueSelectionHelper.persistSelectedBuilding(context, building.getName());
        Toast.makeText(context, "Selected: " + building.getName(), Toast.LENGTH_SHORT).show();

        if (building.getFloors() != null && !building.getFloors().isEmpty()) {
            Collections.sort(building.getFloors(), new Comparator<FloorPlan>() {
                @Override
                public int compare(FloorPlan f1, FloorPlan f2) {
                    return Integer.compare(parseFloorCode(f1.getFloorCode()), parseFloorCode(f2.getFloorCode()));
                }
            });
        }

        this.currentFloorIndex = 0;
        if (building.getFloors() != null) {
            for (int i = 0; i < building.getFloors().size(); i++) {
                if (parseFloorCode(building.getFloors().get(i).getFloorCode()) == 0) {
                    this.currentFloorIndex = i;
                    break;
                }
            }
        }

        notifyCurrentFloorSelection();
        scheduleFloorRender();
    }

    // Normalizes mixed API floor labels into one integer floor ordering.
    private int parseFloorCode(String code) {
        if (code == null) return 0;
        String raw = code.toUpperCase().trim();

        if (raw.equals("BF") || raw.equals("B")) return -1;
        if (raw.equals("GF") || raw.equals("G") || raw.equals("0") || raw.contains("GROUND")) return 0;
        if (raw.contains("BASEMENT")) return -1;

        if (raw.startsWith("B")) {
            String digits = raw.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                try {
                    return -Integer.parseInt(digits);
                } catch (Exception ignored) {
                }
            }
            return -1;
        }

        try {
            Matcher matcher = Pattern.compile("-?\\d+").matcher(raw);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group());
            }
        } catch (Exception ignored) {
        }

        return 0;
    }

    public void changeFloor(int delta) {
        if (selectedBuilding == null || selectedBuilding.getFloors() == null || selectedBuilding.getFloors().isEmpty()) {
            return;
        }

        int maxIndex = selectedBuilding.getFloors().size() - 1;
        int newIndex = Math.max(0, Math.min(currentFloorIndex + delta, maxIndex));
        if (newIndex != currentFloorIndex) {
            currentFloorIndex = newIndex;
            notifyCurrentFloorSelection();
            scheduleFloorRender();
        }
    }
    // Coalesces repeated floor redraw requests.
    private void scheduleFloorRender() {
        floorRenderSeq++;
        final int renderSeq = floorRenderSeq;
        if (pendingFloorRenderTask != null) {
            mainHandler.removeCallbacks(pendingFloorRenderTask);
        }
        pendingFloorRenderTask = () -> {
            if (renderSeq != floorRenderSeq) return;
            drawCurrentFloorWalls();
        };
        mainHandler.postDelayed(pendingFloorRenderTask, FLOOR_RENDER_DEBOUNCE_MS);
    }

    public boolean setFloorByValue(int floorValue) {
        if (selectedBuilding == null || selectedBuilding.getFloors() == null || selectedBuilding.getFloors().isEmpty()) {
            return false;
        }

        int bestIndex = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < selectedBuilding.getFloors().size(); i++) {
            FloorPlan floorPlan = selectedBuilding.getFloors().get(i);
            if (floorPlan == null) {
                continue;
            }
            int parsedValue = parseFloorCode(floorPlan.getFloorCode());
            if (parsedValue == floorValue) {
                bestIndex = i;
                break;
            }
            int distance = Math.abs(parsedValue - floorValue);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }

        if (bestIndex < 0 || bestIndex == currentFloorIndex) {
            return false;
        }

        currentFloorIndex = bestIndex;
        notifyCurrentFloorSelection();
        scheduleFloorRender();
        return true;
    }

    public int getCurrentFloorValue() {
        if (selectedBuilding == null || selectedBuilding.getFloors() == null || selectedBuilding.getFloors().isEmpty()) {
            return 0;
        }
        if (currentFloorIndex < 0) currentFloorIndex = 0;
        if (currentFloorIndex >= selectedBuilding.getFloors().size()) {
            currentFloorIndex = selectedBuilding.getFloors().size() - 1;
        }
        return parseFloorCode(selectedBuilding.getFloors().get(currentFloorIndex).getFloorCode());
    }
    // Returns the currently displayed floor plan.
    public FloorPlan getCurrentFloorPlan() {
        if (selectedBuilding == null || selectedBuilding.getFloors() == null || selectedBuilding.getFloors().isEmpty()) {
            return null;
        }
        if (currentFloorIndex < 0 || currentFloorIndex >= selectedBuilding.getFloors().size()) {
            return null;
        }
        return selectedBuilding.getFloors().get(currentFloorIndex);
    }

    public Map<Integer, FloorPlan> getSelectedFloorPlanMap() {
        Map<Integer, FloorPlan> floorPlanMap = new LinkedHashMap<>();
        if (selectedBuilding == null || selectedBuilding.getFloors() == null) {
            return floorPlanMap;
        }
        for (FloorPlan floor : selectedBuilding.getFloors()) {
            if (floor == null) {
                continue;
            }
            floorPlanMap.put(parseFloorCode(floor.getFloorCode()), floor);
        }
        return floorPlanMap;
    }

    // Redraws the selected floor with separate styles for walls, stairs, and lifts.
    private void drawCurrentFloorWalls() {
        // Re-dispatch if the draw request arrives off the UI thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::drawCurrentFloorWalls);
            return;
        }
        if (gMap == null) {
            return;
        }
        // Clear overlays from the previous floor before drawing the next one.
        for (Polyline line : currentFloorLines) {
            try {
                line.remove();
            } catch (Exception ignored) {
            }
        }
        currentFloorLines.clear();

        for (Polygon poly : currentFloorPolygons) {
            try {
                poly.remove();
            } catch (Exception ignored) {
            }
        }
        currentFloorPolygons.clear();

        if (selectedBuilding == null || selectedBuilding.getFloors() == null || selectedBuilding.getFloors().isEmpty()) {
            return;
        }

        if (currentFloorIndex < 0) currentFloorIndex = 0;
        if (currentFloorIndex >= selectedBuilding.getFloors().size()) {
            currentFloorIndex = selectedBuilding.getFloors().size() - 1;
        }

        FloorPlan floor = selectedBuilding.getFloors().get(currentFloorIndex);
        notifyCurrentFloorSelection();

        int wallCount = (floor.getWalls() != null) ? floor.getWalls().size() : 0;
        int stairCount = (floor.getStairs() != null) ? floor.getStairs().size() : 0;
        int liftCount = (floor.getLifts() != null) ? floor.getLifts().size() : 0;
        if (DRAW_DEBUG_LOGS) {
            Log.d("MapDrawDebug", "Prepare floor draw: " + floor.getFloorCode()
                    + " | walls: " + wallCount
                    + " | stairs: " + stairCount
                    + " | lifts: " + liftCount);
        }

        if (floor.getWalls() != null) {
            for (List<List<Double>> wallPath : floor.getWalls()) {
                List<LatLng> points = toLatLngList(wallPath);
                if (!points.isEmpty()) {
                    currentFloorLines.add(gMap.addPolyline(new PolylineOptions()
                            .addAll(points)
                            .color(Color.YELLOW)
                            .width(6f)
                            .zIndex(100f)));
                }
            }
        }

        if (floor.getStairs() != null) {
            int i = 0;
            for (List<List<Double>> stairPath : floor.getStairs()) {
                List<LatLng> points = toLatLngList(stairPath);
                if (points.isEmpty()) continue;
                if (DRAW_DEBUG_LOGS && i == 0) {
                    Log.d("MapDrawDebug", "First stair area points=" + points.size() + ", sample=" + points.get(0));
                }
                i++;
                if (points.size() >= 3) {
                    currentFloorPolygons.add(gMap.addPolygon(new PolygonOptions()
                            .addAll(points)
                            .strokeColor(Color.GREEN)
                            .strokeWidth(3f)
                            .fillColor(Color.argb(120, 0, 255, 0))
                            .zIndex(105f)));
                } else {
                    currentFloorLines.add(gMap.addPolyline(new PolylineOptions()
                            .addAll(points)
                            .color(Color.GREEN)
                            .width(20f)
                            .zIndex(105f)));
                    if (DRAW_DEBUG_LOGS) {
                        Log.d("MapDrawDebug", "Stair polygon has <3 points, using a fallback polyline.");
                    }
                }
            }
        }

        if (floor.getLifts() != null) {
            int i = 0;
            for (List<List<Double>> liftPath : floor.getLifts()) {
                List<LatLng> points = toLatLngList(liftPath);
                if (points.isEmpty()) continue;
                if (DRAW_DEBUG_LOGS && i == 0) {
                    Log.d("MapDrawDebug", "First lift area points=" + points.size() + ", sample=" + points.get(0));
                }
                i++;
                if (points.size() >= 3) {
                    currentFloorPolygons.add(gMap.addPolygon(new PolygonOptions()
                            .addAll(points)
                            .strokeColor(Color.BLUE)
                            .strokeWidth(3f)
                            .fillColor(Color.argb(120, 0, 0, 255))
                            .zIndex(105f)));
                } else {
                    currentFloorLines.add(gMap.addPolyline(new PolylineOptions()
                            .addAll(points)
                            .color(Color.BLUE)
                            .width(20f)
                            .zIndex(105f)));
                    if (DRAW_DEBUG_LOGS) {
                        Log.d("MapDrawDebug", "Lift polygon has <3 points, using a fallback polyline.");
                    }
                }
            }
        }
    }
    // Reports the active building and floor to listeners.
    private void notifyCurrentFloorSelection() {
        if (selectionListener == null || selectedBuilding == null
                || selectedBuilding.getFloors() == null
                || selectedBuilding.getFloors().isEmpty()) {
            return;
        }
        if (currentFloorIndex < 0) currentFloorIndex = 0;
        if (currentFloorIndex >= selectedBuilding.getFloors().size()) {
            currentFloorIndex = selectedBuilding.getFloors().size() - 1;
        }
        FloorPlan floor = selectedBuilding.getFloors().get(currentFloorIndex);
        selectionListener.onBuildingSelected(selectedBuilding.getName(), floor.getFloorCode());
    }

    private List<LatLng> toLatLngList(List<List<Double>> geoPath) {
        List<LatLng> points = new ArrayList<>();
        if (geoPath == null) return points;
        for (List<Double> point : geoPath) {
            if (point != null && point.size() >= 2) {
                points.add(new LatLng(point.get(0), point.get(1)));
            }
        }
        return points;
    }
}
