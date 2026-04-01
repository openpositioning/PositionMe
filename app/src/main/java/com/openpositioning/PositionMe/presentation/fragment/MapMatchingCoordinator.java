package com.openpositioning.PositionMe.presentation.fragment;

import android.content.Context;
import android.location.Location;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.mapmatching.CandidatePose;
import com.openpositioning.PositionMe.mapmatching.CorrectionType;
import com.openpositioning.PositionMe.mapmatching.MapMatchingInput;
import com.openpositioning.PositionMe.mapmatching.MapMatchingResult;
import com.openpositioning.PositionMe.mapmatching.MapMatchingService;
import com.openpositioning.PositionMe.mapmatching.MotionDelta;
import com.openpositioning.PositionMe.mapmatching.VerticalTransitionHint;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.IndoorMapManager;

import java.util.List;
import java.util.Locale;

/**
 * MapMatchingCoordinator
 *
 * Cleaned responsibility:
 * - REPLAY ONLY
 * - owns replay map matching state
 * - owns replay floor replay-context interpretation
 * - does NOT own live WiFi/GNSS correction
 * - does NOT own live fused trajectory generation
 *
 * Live fused pose is now owned by ParticleFilterManager.
 */
final class MapMatchingCoordinator {

    interface Host {
        @Nullable GoogleMap getGoogleMap();
        @NonNull Context requireContext();
        @NonNull TrajectoryRenderer getTrajectoryRenderer();
        @Nullable FloorplanApiClient.BuildingInfo getSelectedFloorplanBuilding();
        @Nullable IndoorMapManager getIndoorMapManager();
        boolean isReplayModeEnabled();
        @Nullable SensorFusion getSensorFusion();
        @Nullable Integer getTrackingCandidateFloorIndex();
        int getCurrentFloorIndex();
        void setFloor(int floorIndex);
        boolean isAutoFloorEnabled();
        boolean hasReliableInitialFloorFix();
        @Nullable LatLng getCurrentLocation();
        void setCurrentLocation(@NonNull LatLng location);
        @NonNull String resolveKnownBuildingKey(@NonNull FloorplanApiClient.BuildingInfo building,
                                                @Nullable String buildingName);
        @NonNull String canonicalFloorLabel(@Nullable String floorLabel);
        @NonNull List<Integer> getOrderedFloorIndices(@NonNull FloorplanApiClient.BuildingInfo building);
        void maybeFetchNearbyBuildingsOnFirstLocation();
        boolean hasAutoSelectedIndoorMap();
        @NonNull List<FloorplanApiClient.BuildingInfo> getLastFetchedBuildings();
        void maybeAutoSelectIndoorMap(@NonNull List<FloorplanApiClient.BuildingInfo> buildings);
        @NonNull String getTag();
        @NonNull String getTestLogTag();
    }

    private final Host host;
    private final MapMatchingService mapMatchingService = new MapMatchingService();

    @Nullable
    private CandidatePose previousMatchedPose;

    @Nullable
    private Integer replaySyntheticFloor;
    @Nullable
    private Double replayCurrentElevation;
    @Nullable
    private Double replayDeltaHeight;
    private boolean replayHeightChanged = false;
    @Nullable
    private Integer replayInitialFloor;
    @Nullable
    private Integer replayBaseFloorIndex;
    private boolean replayDisplayFloorInitialized = false;

    @NonNull
    private String latestDebugStatus = defaultDebugStatus();

    MapMatchingCoordinator(@NonNull Host host) {
        this.host = host;
    }

    /**
     * Returns the latest replay debug text.
     */
    @NonNull
    String getLatestDebugStatus() {
        return latestDebugStatus;
    }

    /**
     * Called when replay mode toggles.
     */
    void onReplayModeChanged(boolean enabled) {
        previousMatchedPose = null;
        mapMatchingService.resetTransientState();
        host.getTrajectoryRenderer().clearRawReplayPath();
        clearReplayContext(true);
        latestDebugStatus = defaultDebugStatus();

        if (enabled) {
            replayDisplayFloorInitialized = false;
        }
    }

    /**
     * Called when the selected building changes.
     *
     * Replay state is per-building, so this must reset it.
     */
    void onSelectedBuildingChanged() {
        previousMatchedPose = null;
        mapMatchingService.resetTransientState();
        host.getTrajectoryRenderer().clearRawReplayPath();
        clearReplayContext(true);
        latestDebugStatus = defaultDebugStatus();
    }

    /**
     * Supplies replay frame context without initial-floor override.
     */
    void setReplayFrameContext(@Nullable Integer syntheticFloor,
                               @Nullable Double currentElevation,
                               @Nullable Double deltaHeight,
                               boolean heightChanged) {
        setReplayFrameContext(syntheticFloor, currentElevation, deltaHeight, heightChanged, null);
    }

    /**
     * Supplies replay frame context including optional initial-floor override.
     */
    void setReplayFrameContext(@Nullable Integer syntheticFloor,
                               @Nullable Double currentElevation,
                               @Nullable Double deltaHeight,
                               boolean heightChanged,
                               @Nullable Integer initialFloor) {
        replaySyntheticFloor = syntheticFloor;
        replayCurrentElevation = currentElevation;
        replayDeltaHeight = deltaHeight;
        replayHeightChanged = heightChanged;

        if (initialFloor != null && (replayInitialFloor == null || !initialFloor.equals(replayInitialFloor))) {
            replayBaseFloorIndex = null;
            replayDisplayFloorInitialized = false;
        }

        replayInitialFloor = initialFloor;
        maybeInitializeReplayDisplayFloor();

        Log.d(host.getTag(), String.format(Locale.US,
                "Replay frame context initialFloor=%s syntheticFloor=%s elevation=%s deltaHeight=%s heightChanged=%s",
                String.valueOf(initialFloor),
                String.valueOf(syntheticFloor),
                String.valueOf(currentElevation),
                String.valueOf(deltaHeight),
                String.valueOf(heightChanged)));
    }

    /**
     * Updates the replay location.
     *
     * Important:
     * - in live mode this coordinator does nothing meaningful anymore
     * - in replay mode it performs replay map matching only
     */
    void updateUserLocation(@NonNull LatLng newLocation, float orientationDeg) {
        if (host.getGoogleMap() == null) {
            return;
        }

        if (!host.isReplayModeEnabled()) {
            latestDebugStatus = "Replay map matching only\nLive path bypasses coordinator";
            return;
        }

        long timestampMs = SystemClock.elapsedRealtime();
        LatLng rawLocation = newLocation;

        int candidateFloorIndex = resolveReplayCandidateFloorIndex();
        int sourceFloorIndex = previousMatchedPose != null
                ? previousMatchedPose.getFloor()
                : candidateFloorIndex;

        FloorplanApiClient.FloorShapes sourceFloorShapes = getFloorShapesForFloorIndex(sourceFloorIndex);
        FloorplanApiClient.FloorShapes targetFloorShapes = getFloorShapesForFloorIndex(candidateFloorIndex);

        CandidatePose currentCandidatePose = new CandidatePose(
                rawLocation,
                candidateFloorIndex,
                timestampMs,
                "replay_pdr"
        );

        MotionDelta motionDelta = buildMotionDelta(previousMatchedPose, rawLocation, orientationDeg);
        VerticalTransitionHint verticalHint = buildReplayVerticalHint();

        FloorplanApiClient.BuildingInfo selectedBuilding = host.getSelectedFloorplanBuilding();
        String activeBuildingId = selectedBuilding != null
                ? host.resolveKnownBuildingKey(selectedBuilding, selectedBuilding.getName())
                : null;

        MapMatchingInput matchingInput = new MapMatchingInput(
                previousMatchedPose,
                currentCandidatePose,
                motionDelta,
                verticalHint,
                sourceFloorShapes,
                targetFloorShapes,
                activeBuildingId
        );

        MapMatchingResult matchingResult = mapMatchingService.match(matchingInput);

        LatLng matchedLocation = matchingResult.getCorrectedLatLng() != null
                ? matchingResult.getCorrectedLatLng()
                : rawLocation;

        int matchedFloor = matchingResult.getCorrectedFloor();
        int displayFloor = resolveReplayDisplayFloor(matchedFloor);

        logFeatureValidation(rawLocation, matchingResult);

        latestDebugStatus = buildDebugStatus(
                displayFloor,
                candidateFloorIndex,
                matchedFloor,
                verticalHint,
                matchingResult
        );

        LatLng oldLocation = host.getCurrentLocation();
        host.setCurrentLocation(matchedLocation);

        previousMatchedPose = new CandidatePose(
                matchedLocation,
                displayFloor,
                timestampMs,
                "replay_map_state"
        );

        applyReplayDisplayFloorIfNeeded(displayFloor);

        float savedZoom = host.requireContext()
                .getSharedPreferences("MapCameraState", Context.MODE_PRIVATE)
                .getFloat("user_selected_zoom", 19f);

        host.getTrajectoryRenderer().updateCurrentPosition(
                host.requireContext(),
                matchedLocation,
                orientationDeg,
                true,
                savedZoom
        );

        host.getTrajectoryRenderer().appendMatchedLocation(oldLocation, matchedLocation);

        IndoorMapManager indoorMapManager = host.getIndoorMapManager();
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(matchedLocation);
        }

        host.maybeFetchNearbyBuildingsOnFirstLocation();
        if (!host.hasAutoSelectedIndoorMap() && !host.getLastFetchedBuildings().isEmpty()) {
            host.maybeAutoSelectIndoorMap(host.getLastFetchedBuildings());
        }
    }

    /**
     * Fully resets replay-side map matching state.
     */
    void resetMapMatchingState() {
        previousMatchedPose = null;
        mapMatchingService.resetTransientState();
        host.getTrajectoryRenderer().clearRawReplayPath();
        clearReplayContext(true);
        latestDebugStatus = defaultDebugStatus();
    }

    /**
     * Clears replay frame context.
     */
    private void clearReplayContext(boolean clearBaseFloorState) {
        replaySyntheticFloor = null;
        replayCurrentElevation = null;
        replayDeltaHeight = null;
        replayHeightChanged = false;
        replayInitialFloor = null;

        if (clearBaseFloorState) {
            replayBaseFloorIndex = null;
            replayDisplayFloorInitialized = false;
        }
    }

    /**
     * Returns floor shapes for a given floor index.
     */
    @Nullable
    private FloorplanApiClient.FloorShapes getFloorShapesForFloorIndex(int floorIndexForMatching) {
        FloorplanApiClient.BuildingInfo selectedBuilding = host.getSelectedFloorplanBuilding();
        if (selectedBuilding == null
                || selectedBuilding.getFloorShapesList() == null
                || selectedBuilding.getFloorShapesList().isEmpty()) {
            return null;
        }

        if (host.isReplayModeEnabled()) {
            maybeInitializeReplayDisplayFloor();
        }

        int safeFloorIndex = Math.max(
                0,
                Math.min(floorIndexForMatching, selectedBuilding.getFloorShapesList().size() - 1)
        );

        return selectedBuilding.getFloorShapesList().get(safeFloorIndex);
    }

    /**
     * Builds motion delta between replay frames.
     */
    @Nullable
    private MotionDelta buildMotionDelta(@Nullable CandidatePose previousPose,
                                         @NonNull LatLng rawLocation,
                                         float orientationDeg) {
        if (previousPose == null || previousPose.getLatLng() == null) {
            return null;
        }

        float[] results = new float[1];
        Location.distanceBetween(
                previousPose.getLatLng().latitude,
                previousPose.getLatLng().longitude,
                rawLocation.latitude,
                rawLocation.longitude,
                results
        );

        double deltaX = rawLocation.longitude - previousPose.getLatLng().longitude;
        double deltaY = rawLocation.latitude - previousPose.getLatLng().latitude;

        return new MotionDelta(deltaX, deltaY, results[0], orientationDeg);
    }

    /**
     * Builds replay vertical hint directly from replay frame context.
     */
    @Nullable
    private VerticalTransitionHint buildReplayVerticalHint() {
        if (!host.isReplayModeEnabled()) {
            return null;
        }

        if (replayCurrentElevation == null && replayDeltaHeight == null && !replayHeightChanged) {
            return null;
        }

        double currentElevation = replayCurrentElevation != null ? replayCurrentElevation : 0d;
        double deltaHeight = replayDeltaHeight != null ? replayDeltaHeight : 0d;

        return new VerticalTransitionHint(currentElevation, deltaHeight, replayHeightChanged);
    }

    /**
     * Resolves candidate replay floor from base floor + relative replay floor movement.
     */
    private int resolveReplayCandidateFloorIndex() {
        Integer baseFloorIndex = resolveReplayBaseFloorIndex();
        if (!host.isReplayModeEnabled() || baseFloorIndex == null || host.getSelectedFloorplanBuilding() == null) {
            return host.getCurrentFloorIndex();
        }

        List<Integer> orderedFloorIndices = host.getOrderedFloorIndices(host.getSelectedFloorplanBuilding());
        if (orderedFloorIndices.isEmpty()) {
            return baseFloorIndex;
        }

        int basePosition = orderedFloorIndices.indexOf(baseFloorIndex);
        if (basePosition < 0) {
            return baseFloorIndex;
        }

        int relativeFloorOffset = 0;
        if (replaySyntheticFloor != null && hasReplayVerticalEvidence()) {
            relativeFloorOffset = replaySyntheticFloor;
        }

        int targetPosition = Math.max(
                0,
                Math.min(basePosition + relativeFloorOffset, orderedFloorIndices.size() - 1)
        );

        return orderedFloorIndices.get(targetPosition);
    }

    /**
     * Resolves the replay base floor from initial replay metadata.
     */
    @Nullable
    private Integer resolveReplayBaseFloorIndex() {
        FloorplanApiClient.BuildingInfo selectedBuilding = host.getSelectedFloorplanBuilding();
        if (!host.isReplayModeEnabled() || selectedBuilding == null) {
            return null;
        }

        if (replayBaseFloorIndex != null) {
            int maxFloor = Math.max(0, selectedBuilding.getFloorShapesList().size() - 1);
            return Math.max(0, Math.min(replayBaseFloorIndex, maxFloor));
        }

        if (replayInitialFloor == null) {
            replayBaseFloorIndex = host.getCurrentFloorIndex();
            return replayBaseFloorIndex;
        }

        String desiredFloorLabel;
        if (replayInitialFloor < 0) {
            desiredFloorLabel = "LG";
        } else if (replayInitialFloor == 0) {
            desiredFloorLabel = "G";
        } else {
            desiredFloorLabel = String.valueOf(replayInitialFloor);
        }

        for (int i = 0; i < selectedBuilding.getFloorShapesList().size(); i++) {
            String candidateLabel = host.canonicalFloorLabel(
                    selectedBuilding.getFloorShapesList().get(i).getDisplayName()
            );
            if (desiredFloorLabel.equals(candidateLabel)) {
                replayBaseFloorIndex = i;
                return replayBaseFloorIndex;
            }
        }

        replayBaseFloorIndex = host.getCurrentFloorIndex();
        return replayBaseFloorIndex;
    }

    /**
     * True when replay frame context contains usable vertical evidence.
     */
    private boolean hasReplayVerticalEvidence() {
        if (!host.isReplayModeEnabled()) {
            return false;
        }

        if (replaySyntheticFloor != null && replaySyntheticFloor != 0) {
            return true;
        }

        if (replayHeightChanged) {
            return true;
        }

        if (replayDeltaHeight != null && Math.abs(replayDeltaHeight) >= 1.0d) {
            return true;
        }

        return replayCurrentElevation != null && Math.abs(replayCurrentElevation) >= 1.0d;
    }

    /**
     * Initialises the replay display floor the first time replay context becomes available.
     */
    private void maybeInitializeReplayDisplayFloor() {
        FloorplanApiClient.BuildingInfo selectedBuilding = host.getSelectedFloorplanBuilding();
        IndoorMapManager indoorMapManager = host.getIndoorMapManager();

        if (!host.isReplayModeEnabled()
                || replayDisplayFloorInitialized
                || selectedBuilding == null
                || indoorMapManager == null) {
            return;
        }

        Integer baseFloorIndex = resolveReplayBaseFloorIndex();
        if (baseFloorIndex == null) {
            return;
        }

        indoorMapManager.setSelectedBuilding(selectedBuilding);
        host.setFloor(baseFloorIndex);
        replayDisplayFloorInitialized = true;

        Log.d(host.getTag(), "Initialized replay display floor index=" + baseFloorIndex);

        if (previousMatchedPose != null && previousMatchedPose.getFloor() != baseFloorIndex) {
            previousMatchedPose = new CandidatePose(
                    previousMatchedPose.getLatLng(),
                    baseFloorIndex,
                    previousMatchedPose.getTimestampMs(),
                    "replay_map_state"
            );
        }
    }

    /**
     * Resolves the displayed replay floor.
     */
    private int resolveReplayDisplayFloor(int replayCandidateFloorIndex) {
        FloorplanApiClient.BuildingInfo selectedBuilding = host.getSelectedFloorplanBuilding();
        if (selectedBuilding == null
                || selectedBuilding.getFloorShapesList() == null
                || selectedBuilding.getFloorShapesList().isEmpty()) {
            return replayCandidateFloorIndex;
        }

        int maxFloor = Math.max(0, selectedBuilding.getFloorShapesList().size() - 1);
        int clampedFloor = Math.max(0, Math.min(replayCandidateFloorIndex, maxFloor));

        if (hasReplayVerticalEvidence()) {
            return clampedFloor;
        }

        if (previousMatchedPose != null) {
            return Math.max(0, Math.min(previousMatchedPose.getFloor(), maxFloor));
        }

        Integer baseFloorIndex = resolveReplayBaseFloorIndex();
        if (baseFloorIndex != null) {
            return Math.max(0, Math.min(baseFloorIndex, maxFloor));
        }

        return clampedFloor;
    }

    /**
     * Applies replay display floor to the map UI if needed.
     */
    private void applyReplayDisplayFloorIfNeeded(int targetFloorIndex) {
        FloorplanApiClient.BuildingInfo selectedBuilding = host.getSelectedFloorplanBuilding();
        IndoorMapManager indoorMapManager = host.getIndoorMapManager();

        if (!host.isReplayModeEnabled() || selectedBuilding == null || indoorMapManager == null) {
            return;
        }

        int maxFloor = Math.max(0, selectedBuilding.getFloorShapesList().size() - 1);
        int clampedFloor = Math.max(0, Math.min(targetFloorIndex, maxFloor));

        if (clampedFloor == host.getCurrentFloorIndex()) {
            return;
        }

        indoorMapManager.setSelectedBuilding(selectedBuilding);
        host.setFloor(clampedFloor);
    }

    /**
     * Classifies replay map matching scenario for logs.
     */
    @NonNull
    private String classifyMapMatchingScenario(@Nullable MapMatchingResult result) {
        if (result == null) {
            return "UNKNOWN";
        }

        String reason = result.getDebugReason() == null
                ? ""
                : result.getDebugReason().toLowerCase(Locale.US);

        CorrectionType correctionType = result.getCorrectionType();

        if (correctionType == CorrectionType.THROUGH_WALL || result.isCrossedWall()) {
            return "03_cross_wall_reject";
        }
        if (correctionType == CorrectionType.INVALID_FLOOR_CHANGE) {
            return "04_false_floor_change_reject";
        }
        if (result.isFloorChangeAllowed() && result.isNearStairs() && !result.isNearLift()) {
            return "05_stairs_floor_change_allow";
        }
        if (result.isFloorChangeAllowed() && result.isNearLift() && !result.isNearStairs()) {
            return "06_lift_floor_change_allow";
        }
        if (reason.contains("step distance too small")) {
            return "02_small_step_skip_wall_check";
        }
        return "01_same_floor_accept";
    }

    /**
     * Logs replay feature validation details.
     */
    private void logFeatureValidation(@NonNull LatLng rawLocation, @Nullable MapMatchingResult result) {
        if (result == null) {
            return;
        }

        LatLng matched = result.getCorrectedLatLng() != null
                ? result.getCorrectedLatLng()
                : rawLocation;

        String scenario = classifyMapMatchingScenario(result);

        Log.d(host.getTestLogTag(), String.format(Locale.US,
                "SCENARIO=%s raw=(%.6f, %.6f) matched=(%.6f, %.6f) matchedFloor=%d crossedWall=%s nearStairs=%s nearLift=%s floorAllowed=%s correction=%s reason=%s",
                scenario,
                rawLocation.latitude,
                rawLocation.longitude,
                matched.latitude,
                matched.longitude,
                result.getCorrectedFloor(),
                String.valueOf(result.isCrossedWall()),
                String.valueOf(result.isNearStairs()),
                String.valueOf(result.isNearLift()),
                String.valueOf(result.isFloorChangeAllowed()),
                result.getCorrectionType() != null ? result.getCorrectionType().name() : CorrectionType.NONE.name(),
                result.getDebugReason()));
    }

    /**
     * Default replay debug state.
     */
    @NonNull
    private static String defaultDebugStatus() {
        return "Replay MM: idle\n"
                + "floor d/c/m: -/-/-\n"
                + "vertical: steady\n"
                + "correction: NONE";
    }

    /**
     * Builds replay debug text for the fragment debug box.
     */
    @NonNull
    private String buildDebugStatus(int displayFloorIndex,
                                    int candidateFloorIndex,
                                    int matchedFloor,
                                    @Nullable VerticalTransitionHint verticalHint,
                                    @NonNull MapMatchingResult result) {
        String correctionName = result.getCorrectionType() != null
                ? result.getCorrectionType().name()
                : CorrectionType.NONE.name();

        String verticalSummary = verticalHint == null
                ? "steady"
                : String.format(Locale.US, "Δh=%.2f changed=%s",
                verticalHint.getDeltaHeight(),
                String.valueOf(verticalHint.isHeightChanged()));

        return String.format(
                Locale.US,
                "Replay MM: %s\n" +
                        "wall=%s stairs=%s lift=%s allow=%s\n" +
                        "floor d/c/m: %d/%d/%d\n" +
                        "vertical: %s",
                correctionName,
                String.valueOf(result.isCrossedWall()),
                String.valueOf(result.isNearStairs()),
                String.valueOf(result.isNearLift()),
                String.valueOf(result.isFloorChangeAllowed()),
                displayFloorIndex,
                candidateFloorIndex,
                matchedFloor,
                verticalSummary
        );
    }
}