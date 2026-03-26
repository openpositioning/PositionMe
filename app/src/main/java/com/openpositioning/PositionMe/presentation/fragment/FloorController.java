package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.IndoorMapManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates floor selection and AutoFloor behaviour so TrajectoryMapFragment
 * does not need to carry all floor-related state and listeners directly.
 */
public class FloorController {

    private static final long AUTO_FLOOR_DEBOUNCE_MS = 3000L;
    private static final long AUTO_FLOOR_CHECK_INTERVAL_MS = 1000L;

    public interface Host {
        @Nullable FloorplanApiClient.BuildingInfo getSelectedFloorplanBuilding();
        @Nullable IndoorMapManager getIndoorMapManager();
        @Nullable SensorFusion getSensorFusion();
        boolean isReplayModeEnabled();
        int getCurrentFloorIndex();
        void setCurrentFloorIndex(int floorIndex);
        boolean isIndoorMapVisible();
        boolean isActualMapVisible();
        void refreshSelectedPolygonAppearance();
        void resetMapOverlays();
        void updateRealMapOverlay(String buildingName, int floorIndex, boolean show);
        void updateCalibrationUi();
    }

    private final Host host;
    private Handler autoFloorHandler;
    private Runnable autoFloorTask;
    private int lastCandidateFloor = Integer.MIN_VALUE;
    private long lastCandidateTime = 0L;

    private SwitchMaterial autoFloorSwitch;
    private FloatingActionButton floorUpButton;
    private FloatingActionButton floorDownButton;
    private TextView floorLabel;

    public FloorController(Host host) {
        this.host = host;
    }

    public void bindViews(@Nullable SwitchMaterial autoFloorSwitch,
                          @Nullable FloatingActionButton floorUpButton,
                          @Nullable FloatingActionButton floorDownButton,
                          @Nullable TextView floorLabel) {
        this.autoFloorSwitch = autoFloorSwitch;
        this.floorUpButton = floorUpButton;
        this.floorDownButton = floorDownButton;
        this.floorLabel = floorLabel;
        attachListeners();
    }

    private void attachListeners() {
        if (autoFloorSwitch != null) {
            autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
                if (isChecked) {
                    startAutoFloor();
                } else {
                    stopAutoFloor();
                }
            });
        }

        if (floorUpButton != null) {
            floorUpButton.setOnClickListener(v -> {
                if (autoFloorSwitch != null) {
                    autoFloorSwitch.setChecked(false);
                }
                FloorplanApiClient.BuildingInfo building = host.getSelectedFloorplanBuilding();
                if (building != null) {
                    int currentFloorIndex = host.getCurrentFloorIndex();
                    int nextFloorIndex = getAdjacentFloorIndex(building, currentFloorIndex, true);
                    if (nextFloorIndex != currentFloorIndex) {
                        setFloor(nextFloorIndex);
                    }
                }
            });
        }

        if (floorDownButton != null) {
            floorDownButton.setOnClickListener(v -> {
                if (autoFloorSwitch != null) {
                    autoFloorSwitch.setChecked(false);
                }
                FloorplanApiClient.BuildingInfo building = host.getSelectedFloorplanBuilding();
                if (building != null) {
                    int currentFloorIndex = host.getCurrentFloorIndex();
                    int nextFloorIndex = getAdjacentFloorIndex(building, currentFloorIndex, false);
                    if (nextFloorIndex != currentFloorIndex) {
                        setFloor(nextFloorIndex);
                    }
                }
            });
        }
    }

    public void setFloor(int newFloorIndex) {
        FloorplanApiClient.BuildingInfo selectedFloorplanBuilding = host.getSelectedFloorplanBuilding();
        IndoorMapManager indoorMapManager = host.getIndoorMapManager();
        if (selectedFloorplanBuilding == null || indoorMapManager == null) {
            return;
        }

        int maxFloor = selectedFloorplanBuilding.getFloorShapesList().size() - 1;
        int clampedFloor = Math.max(0, Math.min(newFloorIndex, maxFloor));
        host.setCurrentFloorIndex(clampedFloor);
        host.refreshSelectedPolygonAppearance();

        if (!host.isIndoorMapVisible() && !host.isActualMapVisible()) {
            indoorMapManager.clearIndoorMap();
            host.resetMapOverlays();
            updateFloorLabel();
            return;
        }

        host.resetMapOverlays();
        indoorMapManager.setVectorBaseplateEnabled(false);

        if (host.isActualMapVisible()) {
            host.updateRealMapOverlay(selectedFloorplanBuilding.getName(), clampedFloor, true);
        }

        if (host.isIndoorMapVisible()) {
            indoorMapManager.setCurrentFloor(clampedFloor, false);
        } else {
            indoorMapManager.clearIndoorMap();
        }

        setFloorControlsVisibility(View.VISIBLE);
        updateFloorLabel();
        host.updateCalibrationUi();
    }

    public int getDefaultFloorIndex(@Nullable FloorplanApiClient.BuildingInfo building) {
        if (building == null || building.getFloorShapesList() == null || building.getFloorShapesList().isEmpty()) {
            return 0;
        }

        for (int i = 0; i < building.getFloorShapesList().size(); i++) {
            String displayName = building.getFloorShapesList().get(i).getDisplayName();
            if ("G".equals(canonicalFloorLabel(displayName))) {
                return i;
            }
        }

        List<Integer> orderedFloorIndices = getOrderedFloorIndices(building);
        return orderedFloorIndices.isEmpty() ? 0 : orderedFloorIndices.get(0);
    }

    public String getFloorDisplayName(@Nullable FloorplanApiClient.BuildingInfo building, int floorIndex) {
        if (building == null || building.getFloorShapesList() == null
                || floorIndex < 0 || floorIndex >= building.getFloorShapesList().size()) {
            return "";
        }
        String displayName = building.getFloorShapesList().get(floorIndex).getDisplayName();
        return displayName == null ? "" : displayName.trim().toUpperCase();
    }

    public int getAdjacentFloorIndex(@Nullable FloorplanApiClient.BuildingInfo building, int currentIndex, boolean moveUp) {
        List<Integer> orderedFloorIndices = getOrderedFloorIndices(building);
        if (orderedFloorIndices.isEmpty()) {
            return currentIndex;
        }

        int currentOrderedPosition = orderedFloorIndices.indexOf(currentIndex);
        if (currentOrderedPosition < 0) {
            currentOrderedPosition = 0;
            for (int i = 0; i < orderedFloorIndices.size(); i++) {
                if (orderedFloorIndices.get(i) >= currentIndex) {
                    currentOrderedPosition = i;
                    break;
                }
            }
        }

        int nextOrderedPosition = moveUp ? currentOrderedPosition + 1 : currentOrderedPosition - 1;
        if (nextOrderedPosition < 0 || nextOrderedPosition >= orderedFloorIndices.size()) {
            return currentIndex;
        }
        return orderedFloorIndices.get(nextOrderedPosition);
    }

    public List<Integer> getOrderedFloorIndices(@Nullable FloorplanApiClient.BuildingInfo building) {
        List<Integer> ordered = new ArrayList<>();
        if (building == null || building.getFloorShapesList() == null || building.getFloorShapesList().isEmpty()) {
            return ordered;
        }

        List<Integer> fallback = new ArrayList<>();
        for (int i = 0; i < building.getFloorShapesList().size(); i++) {
            fallback.add(i);
        }

        String[] desiredOrder = new String[]{"LG", "G", "1", "2", "3"};
        for (String desiredFloor : desiredOrder) {
            for (int i = 0; i < building.getFloorShapesList().size(); i++) {
                if (ordered.contains(i)) {
                    continue;
                }

                String displayName = building.getFloorShapesList().get(i).getDisplayName();
                if (desiredFloor.equals(canonicalFloorLabel(displayName))) {
                    ordered.add(i);
                }
            }
        }

        for (Integer index : fallback) {
            if (!ordered.contains(index)) {
                ordered.add(index);
            }
        }
        return ordered;
    }

    public void setFloorControlsVisibility(int visibility) {
        if (floorUpButton != null) {
            floorUpButton.setVisibility(visibility);
        }
        if (floorDownButton != null) {
            floorDownButton.setVisibility(visibility);
        }
        if (floorLabel != null) {
            floorLabel.setVisibility(visibility);
        }
        if (autoFloorSwitch != null) {
            autoFloorSwitch.setVisibility(visibility);
        }
        if (visibility == View.VISIBLE) {
            updateFloorLabel();
        }
    }

    public void updateFloorLabel() {
        IndoorMapManager indoorMapManager = host.getIndoorMapManager();
        if (floorLabel != null && indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) {
            floorLabel.setText(formatFloorLabelForUi(indoorMapManager.getCurrentFloorDisplayName()));
        }
        host.updateCalibrationUi();
    }

    public void startAutoFloor() {
        if (host.isReplayModeEnabled()) {
            if (autoFloorSwitch != null) {
                autoFloorSwitch.setChecked(false);
            }
            return;
        }
        if (autoFloorHandler == null) {
            autoFloorHandler = new Handler(Looper.getMainLooper());
        }
        lastCandidateFloor = Integer.MIN_VALUE;
        lastCandidateTime = 0L;
        applyImmediateFloor();

        autoFloorTask = new Runnable() {
            @Override
            public void run() {
                evaluateAutoFloor();
                if (autoFloorHandler != null) {
                    autoFloorHandler.postDelayed(this, AUTO_FLOOR_CHECK_INTERVAL_MS);
                }
            }
        };
        autoFloorHandler.post(autoFloorTask);
    }

    public void applyImmediateFloor() {
        if (host.isReplayModeEnabled()) {
            return;
        }
        SensorFusion sensorFusion = host.getSensorFusion();
        IndoorMapManager indoorMapManager = host.getIndoorMapManager();
        if (sensorFusion == null || indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
            return;
        }
        int candidateFloor;
        if (sensorFusion.getLatLngWifiPositioning() != null) {
            candidateFloor = sensorFusion.getWifiFloor();
        } else {
            float elevation = sensorFusion.getElevation();
            float floorHeight = indoorMapManager.getFloorHeight();
            if (floorHeight <= 0) {
                return;
            }
            candidateFloor = Math.round(elevation / floorHeight);
        }
        setFloor(indoorMapManager.logicalFloorToIndex(candidateFloor));
        lastCandidateFloor = candidateFloor;
        lastCandidateTime = SystemClock.elapsedRealtime();
    }

    public void stopAutoFloor() {
        if (autoFloorHandler != null && autoFloorTask != null) {
            autoFloorHandler.removeCallbacks(autoFloorTask);
        }
        lastCandidateFloor = Integer.MIN_VALUE;
        lastCandidateTime = 0L;
    }

    public void evaluateAutoFloor() {
        if (host.isReplayModeEnabled()) {
            return;
        }
        SensorFusion sensorFusion = host.getSensorFusion();
        IndoorMapManager indoorMapManager = host.getIndoorMapManager();
        if (sensorFusion == null || indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
            return;
        }
        int candidateFloor;
        if (sensorFusion.getLatLngWifiPositioning() != null) {
            candidateFloor = sensorFusion.getWifiFloor();
        } else {
            float elevation = sensorFusion.getElevation();
            float floorHeight = indoorMapManager.getFloorHeight();
            if (floorHeight <= 0) {
                return;
            }
            candidateFloor = Math.round(elevation / floorHeight);
        }

        long now = SystemClock.elapsedRealtime();
        if (candidateFloor != lastCandidateFloor) {
            lastCandidateFloor = candidateFloor;
            lastCandidateTime = now;
            return;
        }
        if (now - lastCandidateTime >= AUTO_FLOOR_DEBOUNCE_MS) {
            setFloor(indoorMapManager.logicalFloorToIndex(candidateFloor));
            lastCandidateTime = now;
        }
    }

    private String formatFloorLabelForUi(@Nullable String rawFloorLabel) {
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

    private String canonicalFloorLabel(@Nullable String floorLabel) {
        if (floorLabel == null) {
            return "";
        }
        String normalized = floorLabel.trim().toUpperCase();
        if (normalized.startsWith("FLOOR ")) {
            normalized = normalized.substring(6).trim();
        }
        if (normalized.startsWith("LEVEL ")) {
            normalized = normalized.substring(6).trim();
        }
        switch (normalized) {
            case "GROUND":
            case "GF":
            case "0":
                return "G";
            case "UG":
            case "LOWER GROUND":
            case "LOWERGROUND":
            case "L/G":
            case "B1":
            case "-1":
                return "LG";
            case "F1":
                return "1";
            case "F2":
                return "2";
            case "F3":
                return "3";
            default:
                return normalized;
        }
    }
}
