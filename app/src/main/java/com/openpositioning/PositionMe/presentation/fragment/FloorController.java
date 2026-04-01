package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
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

    private static final String TAG = "FloorController";
    private static final long AUTO_FLOOR_DEBOUNCE_MS = 3000L;
    private static final long AUTO_FLOOR_CHECK_INTERVAL_MS = 1000L;
    private static final float AUTO_FLOOR_MIN_VERTICAL_DELTA_METERS = 1.2f;
    private static final float AUTO_FLOOR_VERTICAL_FRACTION_PER_FLOOR = 0.55f;
    private static final long INITIAL_FLOOR_WIFI_STABLE_MS = 1500L;
    private static final int INITIAL_FLOOR_WIFI_REQUIRED_SAMPLES = 3;
    private static final long INITIAL_FLOOR_BOOTSTRAP_TIMEOUT_MS = 5000L;
    private long autoFloorStartMs = 0L;

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
    private float lastCommittedElevationMeters = Float.NaN;
    private boolean initialFloorResolved = false;
    private int pendingInitialWifiFloor = Integer.MIN_VALUE;
    private long pendingInitialWifiSinceMs = 0L;
    private int pendingInitialWifiSamples = 0;

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
                syncLiveAutoFloorFlag(isChecked);
                if (isChecked) {
                    startAutoFloor();
                } else {
                    stopAutoFloor();
                }
            });
        }

        if (floorUpButton != null) {
            floorUpButton.setOnClickListener(v -> {
                if (isAutoFloorEnabled()) {
                    Log.d(TAG, "Ignored manual floor-up because AutoFloor is enabled.");
                    return;
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
                if (isAutoFloorEnabled()) {
                    Log.d(TAG, "Ignored manual floor-down because AutoFloor is enabled.");
                    return;
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

    /**
     * Mirrors the Auto Floor UI state into SensorFusion so the live PF pipeline
     * knows whether floor transitions are allowed.
     */
    private void syncLiveAutoFloorFlag(boolean enabled) {
        SensorFusion sensorFusion = host.getSensorFusion();
        if (sensorFusion != null) {
            sensorFusion.setLiveAutoFloorEnabled(enabled);
        }
    }

    public void setFloor(int newFloorIndex) {
        applyFloorInternal(newFloorIndex, true);
    }

    public void displayPfOwnedFloor (int newFloorIndex){
        applyFloorInternal(newFloorIndex,false);
    }

    public void refreshCurrentFloorUi() {
        applyFloorInternal(host.getCurrentFloorIndex(), false);
    }

    /**
     * Applies a floor to the UI.
     *
     * Ownership split:
     * - confirmFloor=true  -> manual/bootstrap override path, allowed to push into PF
     * - confirmFloor=false -> PF-owned display path, must NOT push into PF
     */
    private void applyFloorInternal(int newFloorIndex, boolean confirmFloor) {
        FloorplanApiClient.BuildingInfo selectedFloorplanBuilding = host.getSelectedFloorplanBuilding();
        IndoorMapManager indoorMapManager = host.getIndoorMapManager();
        if (selectedFloorplanBuilding == null || indoorMapManager == null) {
            return;
        }

        int maxFloor = selectedFloorplanBuilding.getFloorShapesList().size() - 1;
        int clampedFloor = Math.max(0, Math.min(newFloorIndex, maxFloor));

        // Always update the displayed/UI floor.
        host.setCurrentFloorIndex(clampedFloor);

        if (confirmFloor) {
            initialFloorResolved = true;
            resetInitialFloorBootstrapState();
            syncAutoFloorAnchor();

            // Only manual/bootstrap floor changes are allowed to push back into PF.
            if (!host.isReplayModeEnabled()) {
                SensorFusion sensorFusion = host.getSensorFusion();
                if (sensorFusion != null) {
                    sensorFusion.setParticleFilterFloorOverride(clampedFloor);
                }
            }
        }

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
        if (visibility == View.VISIBLE) {
            updateFloorLabel();
        }
    }

    // Auto-floor is bootstrap-only here.
    // After startup, authoritative live floor comes from validated map matching / PF.
    public void updateFloorLabel() {
        IndoorMapManager indoorMapManager = host.getIndoorMapManager();
        if (floorLabel != null && indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) {
            floorLabel.setText(formatFloorLabelForUi(indoorMapManager.getCurrentFloorDisplayName()));
        }
        host.updateCalibrationUi();
    }

    /**
     * Starts initial floor bootstrap and enables live PF floor transitions.
     *
     * Behaviour:
     * - bootstrap loop runs only until an initial floor is resolved or timeout hits
     * - the Auto Floor switch still remains the authoritative permission for
     *   later live PF floor changes
     */
    public void startAutoFloor() {
        if (host.isReplayModeEnabled()) {
            if (autoFloorSwitch != null) {
                autoFloorSwitch.setChecked(false);
            }
            return;
        }

        syncLiveAutoFloorFlag(true);

        if (autoFloorHandler == null) {
            autoFloorHandler = new Handler(Looper.getMainLooper());
        }

        stopAutoFloorBootstrap();
        resetInitialFloorBootstrapState();
        autoFloorStartMs = SystemClock.elapsedRealtime();
        syncAutoFloorAnchor();

        autoFloorTask = new Runnable() {
            @Override
            public void run() {
                applyImmediateFloor();

                if (!initialFloorResolved
                        && !shouldForceBootstrapFallback()
                        && autoFloorHandler != null) {
                    autoFloorHandler.postDelayed(this, 500L);
                }
            }
        };

        autoFloorHandler.post(autoFloorTask);
    }

    /**
     * Stops only the temporary bootstrap polling task.
     *
     * This does NOT disable the user's Auto Floor preference.
     */
    private void stopAutoFloorBootstrap() {
        if (autoFloorHandler != null && autoFloorTask != null) {
            autoFloorHandler.removeCallbacks(autoFloorTask);
        }
        lastCandidateFloor = Integer.MIN_VALUE;
        lastCandidateTime = 0L;
        resetInitialFloorBootstrapState();
    }

    private boolean shouldForceBootstrapFallback() {
        if (initialFloorResolved || autoFloorStartMs <= 0L) {
            return false;
        }
        return SystemClock.elapsedRealtime() - autoFloorStartMs >= INITIAL_FLOOR_BOOTSTRAP_TIMEOUT_MS;
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

        if (initialFloorResolved) {
            return;
        }

        Integer bootstrapFloorIndex = resolveInitialWifiBootstrapFloorIndex(sensorFusion, indoorMapManager);
        if (bootstrapFloorIndex != null) {
            setFloor(bootstrapFloorIndex);
            stopAutoFloorBootstrap();
            return;
        }

        if (shouldForceBootstrapFallback()) {
            initialFloorResolved = true;
            syncAutoFloorAnchor();
            stopAutoFloorBootstrap();
        }
    }

    /**
     * Fully disables auto-floor behaviour for the current session.
     *
     * Use this when:
     * - the user turns the switch off
     * - replay mode is entered
     * - the map is reset
     */
    public void stopAutoFloor() {
        stopAutoFloorBootstrap();
        syncLiveAutoFloorFlag(false);
    }

    public boolean isAutoFloorEnabled() {
        return autoFloorSwitch != null && autoFloorSwitch.isChecked();
    }

    public boolean hasReliableInitialFloorFix() {
        return initialFloorResolved;
    }

    public void evaluateAutoFloor() {
        applyImmediateFloor();
    }

    @Nullable
    private Integer resolveAutoFloorCandidateIndex(@Nullable SensorFusion sensorFusion,
                                                   @Nullable IndoorMapManager indoorMapManager) {
        if (sensorFusion == null || indoorMapManager == null) {
            return null;
        }

        int candidateLogicalFloor;
        if (sensorFusion.getLatLngWifiPositioning() != null) {
            if (!initialFloorResolved) {
                return null;
            }
            candidateLogicalFloor = sensorFusion.getWifiFloor();
        } else {
            float floorHeight = indoorMapManager.getFloorHeight();
            if (floorHeight <= 0f) {
                return null;
            }
            candidateLogicalFloor = Math.round(sensorFusion.getElevation() / floorHeight);
        }

        return indoorMapManager.logicalFloorToIndex(candidateLogicalFloor);
    }

    /**
     * Returns a floor candidate for positioning logic without forcing the UI to switch floors.
     *
     * Strategy:
     * - If WiFi positioning is available, expose its floor immediately as an algorithm candidate.
     * - Otherwise only expose a barometer/elevator-derived floor when the same evidence would be
     *   strong enough to justify an AutoFloor transition.
     * - If the evidence is weak, return null so the caller can keep the previous matched floor.
     */
    @Nullable
    public Integer peekTrackingFloorCandidateIndex() {
        if (host.isReplayModeEnabled() || initialFloorResolved) {
            return null;
        }

        SensorFusion sensorFusion = host.getSensorFusion();
        IndoorMapManager indoorMapManager = host.getIndoorMapManager();
        if (sensorFusion == null || indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
            return null;
        }

        return resolveInitialWifiBootstrapFloorIndex(sensorFusion, indoorMapManager);
    }

    private void resetInitialFloorBootstrapState() {
        pendingInitialWifiFloor = Integer.MIN_VALUE;
        pendingInitialWifiSinceMs = 0L;
        pendingInitialWifiSamples = 0;
    }

    /**
     * Debug string for floor-control state.
     */
    @NonNull
    public String getAutoFloorDebugSummary() {
        SensorFusion sensorFusion = host.getSensorFusion();
        return "autoFloorUi=" + isAutoFloorEnabled()
                + " livePfAutoFloor=" + (sensorFusion != null && sensorFusion.isLiveAutoFloorEnabled())
                + " initResolved=" + initialFloorResolved
                + " currentFloorIndex=" + host.getCurrentFloorIndex()
                + " candidateFloor=" + lastCandidateFloor
                + " elev=" + (sensorFusion == null ? "na" : sensorFusion.getElevation())
                + " anchorElev=" + lastCommittedElevationMeters
                + " elevator=" + (sensorFusion == null ? "na" : sensorFusion.getElevator());
    }

    @Nullable
    private Integer resolveInitialWifiBootstrapFloorIndex(@NonNull SensorFusion sensorFusion,
                                                          @NonNull IndoorMapManager indoorMapManager) {
        if (sensorFusion.getLatLngWifiPositioning() == null) {
            resetInitialFloorBootstrapState();
            return null;
        }

        int candidateFloorIndex = indoorMapManager.logicalFloorToIndex(sensorFusion.getWifiFloor());
        long now = SystemClock.elapsedRealtime();
        if (candidateFloorIndex != pendingInitialWifiFloor) {
            pendingInitialWifiFloor = candidateFloorIndex;
            pendingInitialWifiSinceMs = now;
            pendingInitialWifiSamples = 1;
            return null;
        }

        pendingInitialWifiSamples++;
        long stableDurationMs = now - pendingInitialWifiSinceMs;
        if (pendingInitialWifiSamples >= INITIAL_FLOOR_WIFI_REQUIRED_SAMPLES
                && stableDurationMs >= INITIAL_FLOOR_WIFI_STABLE_MS) {
            return candidateFloorIndex;
        }
        return null;
    }

    private boolean shouldAllowAutoFloorChange(int candidateFloorIndex,
                                               @NonNull SensorFusion sensorFusion,
                                               @NonNull IndoorMapManager indoorMapManager) {
        if (candidateFloorIndex == host.getCurrentFloorIndex()) {
            return false;
        }

        if (sensorFusion.getElevator()) {
            return true;
        }

        float floorHeight = indoorMapManager.getFloorHeight();
        if (floorHeight <= 0f || Float.isNaN(lastCommittedElevationMeters)) {
            return false;
        }

        int floorSteps = Math.max(1, Math.abs(candidateFloorIndex - host.getCurrentFloorIndex()));
        float requiredDelta = Math.max(
                AUTO_FLOOR_MIN_VERTICAL_DELTA_METERS,
                floorHeight * AUTO_FLOOR_VERTICAL_FRACTION_PER_FLOOR * floorSteps
        );
        float measuredDelta = Math.abs(sensorFusion.getElevation() - lastCommittedElevationMeters);
        return measuredDelta >= requiredDelta;
    }

    private void syncAutoFloorAnchor() {
        SensorFusion sensorFusion = host.getSensorFusion();
        if (sensorFusion != null) {
            lastCommittedElevationMeters = sensorFusion.getElevation();
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
