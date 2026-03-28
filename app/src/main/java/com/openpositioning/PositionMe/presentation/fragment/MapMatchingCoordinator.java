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
import com.openpositioning.PositionMe.mapmatching.VerticalMotionDetector;
import com.openpositioning.PositionMe.mapmatching.VerticalTransitionHint;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.IndoorMapManager;

import java.util.List;
import java.util.Locale;

/**
 * Central coordinator for live/replay map matching on the trajectory map.
 *
 * Responsibilities:
 * - keep the previous accepted matched pose
 * - prepare map-matching inputs from live/raw positions
 * - apply optional absolute observation blending (WiFi / GNSS)
 * - run MapMatchingService
 * - update renderer, camera, indoor map state, and debug strings
 * - push accepted LIVE matched poses into SensorFusion so PF can consume them
 *
 * Important design notes:
 * - Replay mode should NOT feed matched poses into the live particle filter.
 * - Live mode may blend toward WiFi / GNSS, but map matching remains the final arbiter.
 * - The debug text is intentionally compact because it is shown on-screen.
 */
final class MapMatchingCoordinator {

    /**
     * Maximum allowed WiFi pull when building a candidate pose from raw motion.
     */
    private static final double WIFI_MAX_PULL_METERS = 6.0;

    /**
     * Maximum allowed GNSS pull when building a candidate pose from raw motion.
     */
    private static final double GNSS_MAX_PULL_METERS = 10.0;

    /**
     * Blend ratio used when WiFi is available and the track is already stabilised.
     */
    private static final double WIFI_BLEND_RATIO = 0.45;

    /**
     * Blend ratio used when GNSS is available and the track is already stabilised.
     */
    private static final double GNSS_BLEND_RATIO = 0.20;

    /**
     * Hard residual gate for absolute observations.
     * If WiFi/GNSS is wildly far from the raw location, ignore it for candidate generation.
     */
    private static final double MAX_OBSERVATION_RESIDUAL_METERS = 25.0;

    /**
     * Host API implemented by TrajectoryMapFragment.
     */
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
    private final VerticalMotionDetector verticalMotionDetector = new VerticalMotionDetector();

    /**
     * Latest accepted matched pose used as the previous state for next map-matching cycle.
     */
    @Nullable
    private CandidatePose previousMatchedPose;

    // Replay-only context
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

    /**
     * Compact status string shown in the on-map debug panel.
     */
    @NonNull
    private String latestDebugStatus = defaultDebugStatus();

    MapMatchingCoordinator(@NonNull Host host) {
        this.host = host;
    }

    @NonNull
    String getLatestDebugStatus() {
        return latestDebugStatus;
    }

    /**
     * Called when replay mode changes.
     * Clears transient matching state and prevents stale replay poses from feeding the live PF.
     */
    void onReplayModeChanged(boolean enabled) {
        clearTrackingState(true);

        if (enabled) {
            replayDisplayFloorInitialized = false;
        }

        Log.d(host.getTag(), "MapMatchingCoordinator replayMode=" + enabled);
    }

    /**
     * Called when the selected building changes.
     * Clears prior state because a matched pose from the old building is not valid anymore.
     */
    void onSelectedBuildingChanged() {
        clearTrackingState(true);
        Log.d(host.getTag(), "MapMatchingCoordinator building selection changed; state reset.");
    }

    void setReplayFrameContext(@Nullable Integer syntheticFloor,
                               @Nullable Double currentElevation,
                               @Nullable Double deltaHeight,
                               boolean heightChanged) {
        setReplayFrameContext(syntheticFloor, currentElevation, deltaHeight, heightChanged, null);
    }

    /**
     * Sets replay-only vertical/floor context for the next replay update cycle.
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
     * Main live/replay map-matching entry point.
     *
     * Flow:
     * 1) append raw observation path for diagnostics
     * 2) choose candidate floor
     * 3) optionally blend raw location toward WiFi/GNSS (live only)
     * 4) build MotionDelta + VerticalTransitionHint
     * 5) call MapMatchingService
     * 6) update renderer/current state/debug text
     * 7) publish matched pose to SensorFusion for PF (live mode only)
     */
    void updateUserLocation(@NonNull LatLng newLocation, float orientation) {
        if (host.getGoogleMap() == null) {
            return;
        }

        final boolean replayMode = host.isReplayModeEnabled();
        final long timestampMs = SystemClock.elapsedRealtime();
        final LatLng rawLocation = newLocation;

        // Always keep the raw observation trail for debugging.
        host.getTrajectoryRenderer().appendRawObservationPoint(rawLocation);

        final int displayFloorIndex = host.getCurrentFloorIndex();
        final int candidateFloorIndex = replayMode
                ? resolveReplayCandidateFloorIndex()
                : resolveLiveCandidateFloorIndex();
        final int sourceFloorIndex = previousMatchedPose != null
                ? previousMatchedPose.getFloor()
                : candidateFloorIndex;

        final AbsoluteObservationCorrection absoluteCorrection = replayMode
                ? AbsoluteObservationCorrection.passThrough(rawLocation, "replay_pdr")
                : applyAbsoluteObservationCorrection(rawLocation);

        final SensorFusion sensorFusion = host.getSensorFusion();

        // Show WiFi observation marker in live mode only.
        if (!replayMode && sensorFusion != null) {
            host.getTrajectoryRenderer().updateWifi(sensorFusion.getLatLngWifiPositioning());
        }

        final LatLng candidateLatLng = absoluteCorrection.getCorrectedLatLng();
        final FloorplanApiClient.FloorShapes sourceFloorShapes = getFloorShapesForFloorIndex(sourceFloorIndex);
        final FloorplanApiClient.FloorShapes targetFloorShapes = getFloorShapesForFloorIndex(candidateFloorIndex);

        final CandidatePose currentCandidatePose = new CandidatePose(
                candidateLatLng,
                candidateFloorIndex,
                timestampMs,
                absoluteCorrection.getPoseSource()
        );

        final MotionDelta motionDelta = buildMotionDelta(previousMatchedPose, candidateLatLng, orientation);
        final VerticalTransitionHint verticalHint = buildVerticalTransitionHint(timestampMs);
        logVerticalHintDiagnostics(verticalHint, replayMode, candidateFloorIndex);

        final FloorplanApiClient.BuildingInfo selectedBuilding = host.getSelectedFloorplanBuilding();
        final String activeBuildingId = selectedBuilding != null
                ? host.resolveKnownBuildingKey(selectedBuilding, selectedBuilding.getName())
                : null;

        final MapMatchingInput matchingInput = new MapMatchingInput(
                previousMatchedPose,
                currentCandidatePose,
                motionDelta,
                verticalHint,
                sourceFloorShapes,
                targetFloorShapes,
                activeBuildingId
        );

        final MapMatchingResult matchingResult = mapMatchingService.match(matchingInput);
        final LatLng matchedLocation = matchingResult.getCorrectedLatLng() != null
                ? matchingResult.getCorrectedLatLng()
                : rawLocation;
        final int matchedFloor = matchingResult.getCorrectedFloor();
        final int floorForState = replayMode
                ? resolveReplayDisplayFloor(matchedFloor)
                : matchedFloor;

        logFeatureValidation(rawLocation, matchingResult);
        logAbsoluteObservationDiagnostics(rawLocation, absoluteCorrection);

        Log.d(host.getTestLogTag(), String.format(Locale.US,
                "raw=(%.6f, %.6f) candidate=(%.6f, %.6f) matched=(%.6f, %.6f) sourceFloor=%d candidateFloor=%d matchedFloor=%d displayFloor=%d autoFloorEnabled=%s candidateSource=%s correction=%s reason=%s",
                rawLocation.latitude,
                rawLocation.longitude,
                candidateLatLng.latitude,
                candidateLatLng.longitude,
                matchedLocation.latitude,
                matchedLocation.longitude,
                sourceFloorIndex,
                candidateFloorIndex,
                matchedFloor,
                displayFloorIndex,
                String.valueOf(host.isAutoFloorEnabled()),
                absoluteCorrection.getPoseSource(),
                matchingResult.getCorrectionType() != null
                        ? matchingResult.getCorrectionType().name()
                        : CorrectionType.NONE.name(),
                matchingResult.getDebugReason()));

        latestDebugStatus = buildDebugStatus(
                absoluteCorrection.getObservationSource(),
                absoluteCorrection.getPoseSource(),
                displayFloorIndex,
                candidateFloorIndex,
                matchedFloor,
                verticalHint,
                matchingResult
        );

        final LatLng oldLocation = host.getCurrentLocation();
        host.setCurrentLocation(matchedLocation);

        previousMatchedPose = new CandidatePose(
                matchedLocation,
                floorForState,
                timestampMs,
                replayMode ? "replay_map_state" : "map_matched"
        );

        // Feed only LIVE accepted matched poses into the particle filter.
        publishMatchedPoseToParticleFilter(previousMatchedPose, replayMode);

        if (replayMode) {
            applyReplayDisplayFloorIfNeeded(floorForState);
        }

        final boolean shouldFollowCamera = replayMode;
        final Context context = host.requireContext();
        final float savedZoom = context.getSharedPreferences("MapCameraState", Context.MODE_PRIVATE)
                .getFloat("user_selected_zoom", 19f);

        host.getTrajectoryRenderer().updateCurrentPosition(
                context,
                matchedLocation,
                orientation,
                shouldFollowCamera,
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
     * Explicit reset used by the map fragment.
     */
    void resetMapMatchingState() {
        clearTrackingState(true);
        Log.d(host.getTag(), "MapMatchingCoordinator full reset.");
    }

    /**
     * Clears all transient state.
     *
     * @param clearReplayContext whether to also clear replay base-floor state
     */
    private void clearTrackingState(boolean clearReplayContext) {
        previousMatchedPose = null;
        mapMatchingService.resetTransientState();
        verticalMotionDetector.reset();
        host.getTrajectoryRenderer().clearRawReplayPath();
        publishMatchedPoseToParticleFilter(null, false);

        if (clearReplayContext) {
            clearReplayContext(true);
        }

        latestDebugStatus = defaultDebugStatus();
    }

    /**
     * Clears replay-only cached state.
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
     * Pushes the latest accepted matched pose into SensorFusion so the particle filter
     * can use it as a strong indoor observation.
     *
     * Replay mode deliberately does not publish into the live PF.
     */
    private void publishMatchedPoseToParticleFilter(@Nullable CandidatePose matchedPose,
                                                    boolean replayMode) {
        SensorFusion sensorFusion = host.getSensorFusion();
        if (sensorFusion == null) {
            return;
        }

        if (replayMode) {
            sensorFusion.setParticleFilterMatchedPose(null);
            Log.d(host.getTag(), "PF_MATCHED_POSE cleared because replay mode is active.");
            return;
        }

        sensorFusion.setParticleFilterMatchedPose(matchedPose);

        if (matchedPose == null || matchedPose.getLatLng() == null) {
            Log.d(host.getTag(), "PF_MATCHED_POSE cleared.");
            return;
        }

        Log.d(host.getTag(), String.format(Locale.US,
                "PF_MATCHED_POSE lat=%.6f lng=%.6f floor=%d source=%s",
                matchedPose.getLatLng().latitude,
                matchedPose.getLatLng().longitude,
                matchedPose.getFloor(),
                matchedPose.getSourceType()));
    }

    /**
     * Returns floor shapes for the requested floor index within the currently selected building.
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
     * Live floor candidate:
     * 1) floor tracker candidate if available
     * 2) previous matched floor
     * 3) current displayed floor
     */
    private int resolveLiveCandidateFloorIndex() {
        Integer trackingCandidateFloorIndex = host.getTrackingCandidateFloorIndex();
        if (trackingCandidateFloorIndex != null) {
            return trackingCandidateFloorIndex;
        }
        if (previousMatchedPose != null) {
            return previousMatchedPose.getFloor();
        }
        return host.getCurrentFloorIndex();
    }

    /**
     * Builds a candidate location by softly pulling the raw location toward WiFi or GNSS.
     *
     * Notes:
     * - live only
     * - map matching still validates the final candidate
     * - large residuals are ignored
     */
    @NonNull
    private AbsoluteObservationCorrection applyAbsoluteObservationCorrection(@NonNull LatLng rawLocation) {
        SensorFusion sensorFusion = host.getSensorFusion();
        if (sensorFusion == null) {
            return AbsoluteObservationCorrection.passThrough(rawLocation, "live_pdr");
        }

        LatLng wifiObservation = sensorFusion.getLatLngWifiPositioning();
        if (isUsableObservation(wifiObservation)) {
            double rawDistanceMeters = distanceMeters(rawLocation, wifiObservation);
            if (rawDistanceMeters <= MAX_OBSERVATION_RESIDUAL_METERS) {
                double blendRatio = previousMatchedPose == null ? 0.70 : WIFI_BLEND_RATIO;
                double maxPullMeters = previousMatchedPose == null
                        ? WIFI_MAX_PULL_METERS * 2.0
                        : WIFI_MAX_PULL_METERS;

                LatLng blended = blendTowardObservation(rawLocation, wifiObservation, blendRatio, maxPullMeters);
                return new AbsoluteObservationCorrection(blended, "live_wifi_fused", "wifi", rawDistanceMeters);
            }
        }

        LatLng gnssObservation = getGnssObservation(sensorFusion);
        if (isUsableObservation(gnssObservation)) {
            double rawDistanceMeters = distanceMeters(rawLocation, gnssObservation);
            if (rawDistanceMeters <= MAX_OBSERVATION_RESIDUAL_METERS) {
                double blendRatio = previousMatchedPose == null ? 0.40 : GNSS_BLEND_RATIO;
                double maxPullMeters = previousMatchedPose == null
                        ? GNSS_MAX_PULL_METERS * 1.5
                        : GNSS_MAX_PULL_METERS;

                LatLng blended = blendTowardObservation(rawLocation, gnssObservation, blendRatio, maxPullMeters);
                return new AbsoluteObservationCorrection(blended, "live_gnss_fused", "gnss", rawDistanceMeters);
            }
        }

        return AbsoluteObservationCorrection.passThrough(rawLocation, "live_pdr");
    }

    /**
     * Logs how much the candidate location was moved by WiFi/GNSS before map matching.
     */
    private void logAbsoluteObservationDiagnostics(@NonNull LatLng rawLocation,
                                                   @NonNull AbsoluteObservationCorrection correction) {
        if (!correction.hasExternalObservation()) {
            Log.d(host.getTestLogTag(), String.format(Locale.US,
                    "ABSOLUTE_UPDATE source=none raw=(%.6f, %.6f) candidate=(%.6f, %.6f)",
                    rawLocation.latitude,
                    rawLocation.longitude,
                    correction.getCorrectedLatLng().latitude,
                    correction.getCorrectedLatLng().longitude));
            return;
        }

        double appliedShiftMeters = distanceMeters(rawLocation, correction.getCorrectedLatLng());

        Log.d(host.getTestLogTag(), String.format(Locale.US,
                "ABSOLUTE_UPDATE source=%s residualMeters=%.2f appliedShiftMeters=%.2f candidate=(%.6f, %.6f)",
                correction.getObservationSource(),
                correction.getObservationResidualMeters(),
                appliedShiftMeters,
                correction.getCorrectedLatLng().latitude,
                correction.getCorrectedLatLng().longitude));
    }

    /**
     * Returns the current GNSS observation if valid.
     */
    @Nullable
    private LatLng getGnssObservation(@NonNull SensorFusion sensorFusion) {
        float[] gnss = sensorFusion.getGNSSLatitude(false);
        if (gnss == null || gnss.length < 2) {
            return null;
        }
        if (gnss[0] == 0f && gnss[1] == 0f) {
            return null;
        }
        return new LatLng(gnss[0], gnss[1]);
    }

    private boolean isUsableObservation(@Nullable LatLng observation) {
        return observation != null
                && !Double.isNaN(observation.latitude)
                && !Double.isNaN(observation.longitude)
                && !(observation.latitude == 0d && observation.longitude == 0d);
    }

    /**
     * Blends from raw pose toward an observation, then clamps the total shift.
     */
    @NonNull
    private LatLng blendTowardObservation(@NonNull LatLng rawLocation,
                                          @NonNull LatLng observation,
                                          double blendRatio,
                                          double maxPullMeters) {
        LatLng blended = interpolate(rawLocation, observation, blendRatio);
        double appliedShiftMeters = distanceMeters(rawLocation, blended);

        if (appliedShiftMeters <= maxPullMeters) {
            return blended;
        }

        double limitedRatio = maxPullMeters / Math.max(appliedShiftMeters, 1e-6d);
        return interpolate(rawLocation, blended, limitedRatio);
    }

    /**
     * Simple linear interpolation in latitude/longitude space.
     * Acceptable here because all shifts are local and small.
     */
    @NonNull
    private LatLng interpolate(@NonNull LatLng from, @NonNull LatLng to, double ratio) {
        double clampedRatio = Math.max(0d, Math.min(1d, ratio));
        return new LatLng(
                from.latitude + (to.latitude - from.latitude) * clampedRatio,
                from.longitude + (to.longitude - from.longitude) * clampedRatio
        );
    }

    /**
     * Returns geodesic distance in metres.
     */
    private double distanceMeters(@NonNull LatLng from, @NonNull LatLng to) {
        float[] results = new float[1];
        Location.distanceBetween(
                from.latitude,
                from.longitude,
                to.latitude,
                to.longitude,
                results
        );
        return results[0];
    }

    /**
     * Builds motion delta from the previous accepted matched pose to the current candidate.
     */
    @Nullable
    private MotionDelta buildMotionDelta(@Nullable CandidatePose previousPose,
                                         @NonNull LatLng rawLocation,
                                         float orientation) {
        if (previousPose == null) {
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

        return new MotionDelta(deltaX, deltaY, results[0], orientation);
    }

    /**
     * Builds vertical transition hint from live barometer/elevator data or replay metadata.
     */
    @Nullable
    private VerticalTransitionHint buildVerticalTransitionHint(long timestampMs) {
        if (!host.isReplayModeEnabled()) {
            SensorFusion sensorFusion = host.getSensorFusion();
            if (sensorFusion == null) {
                return null;
            }

            double elevationMeters = sensorFusion.getElevation();
            boolean elevatorLikely = sensorFusion.getElevator();

            verticalMotionDetector.addSample(timestampMs, elevationMeters, elevatorLikely);
            return verticalMotionDetector.buildHint();
        }

        return buildReplayVerticalHint();
    }

    /**
     * Logs vertical evidence used by map matching.
     */
    private void logVerticalHintDiagnostics(@Nullable VerticalTransitionHint verticalHint,
                                            boolean replayMode,
                                            int candidateFloorIndex) {
        if (verticalHint == null) {
            Log.d(host.getTestLogTag(), String.format(Locale.US,
                    "VERTICAL_HINT source=%s candidateFloor=%d available=false",
                    replayMode ? "replay" : "live",
                    candidateFloorIndex));
            return;
        }

        Log.d(host.getTestLogTag(), String.format(Locale.US,
                "VERTICAL_HINT source=%s candidateFloor=%d available=true elevation=%.2f deltaHeight=%.2f heightChanged=%s",
                replayMode ? "replay" : "live",
                candidateFloorIndex,
                verticalHint.getCurrentElevation(),
                verticalHint.getDeltaHeight(),
                String.valueOf(verticalHint.isHeightChanged())));
    }

    /**
     * Builds replay-only vertical hint.
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
     * Resolves the replay floor candidate relative to the base replay floor.
     */
    private int resolveReplayCandidateFloorIndex() {
        Integer baseFloorIndex = resolveReplayBaseFloorIndex();
        if (!host.isReplayModeEnabled()
                || baseFloorIndex == null
                || host.getSelectedFloorplanBuilding() == null) {
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
     * Finds the base replay floor index from initial replay metadata.
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
     * Returns whether replay has enough vertical evidence to allow floor changes.
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
     * Initialises the replay display floor once the building map is available.
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

        // If matching already started before building/floor setup completed,
        // force previous state onto the correct initial replay floor.
        if (previousMatchedPose != null && previousMatchedPose.getFloor() != baseFloorIndex) {
            previousMatchedPose = new CandidatePose(
                    previousMatchedPose.getLatLng(),
                    baseFloorIndex,
                    previousMatchedPose.getTimestampMs(),
                    "map_matched"
            );
        }
    }

    /**
     * Chooses the floor index that should be stored/displayed for replay state.
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
     * Applies replay display floor change to the indoor map if needed.
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
     * Classifies the matching result into a compact scenario label for Logcat.
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
     * Detailed map-matching scenario log.
     */
    private void logFeatureValidation(@NonNull LatLng rawLocation,
                                      @Nullable MapMatchingResult result) {
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
                result.getCorrectionType() != null
                        ? result.getCorrectionType().name()
                        : CorrectionType.NONE.name(),
                result.getDebugReason()));
    }

    /**
     * Default compact UI debug string.
     */
    @NonNull
    private static String defaultDebugStatus() {
        return "abs:none  src:idle\n"
                + "floor d/c/m: -/-/-\n"
                + "vertical: steady Δ0.00m\n"
                + "correction: NONE\n"
                + "Waiting for updates";
    }

    /**
     * Builds the compact debug status text shown in the fragment.
     */
    @NonNull
    private String buildDebugStatus(@Nullable String absoluteSource,
                                    @Nullable String poseSource,
                                    int displayFloorIndex,
                                    int candidateFloorIndex,
                                    int matchedFloor,
                                    @Nullable VerticalTransitionHint verticalHint,
                                    @NonNull MapMatchingResult result) {
        String absoluteValue = absoluteSource == null ? "none" : absoluteSource;
        String poseValue = poseSource == null ? "unknown" : poseSource;
        String verticalValue = (verticalHint != null && verticalHint.isHeightChanged())
                ? "changed"
                : "steady";
        double deltaHeight = verticalHint != null ? verticalHint.getDeltaHeight() : 0d;

        String correctionName = result.getCorrectionType() != null
                ? result.getCorrectionType().name()
                : CorrectionType.NONE.name();

        String reason = result.getDebugReason() != null
                ? result.getDebugReason().trim()
                : "Waiting for updates";

        if (reason.isEmpty()) {
            reason = "Waiting for updates";
        }
        if (reason.length() > 80) {
            reason = reason.substring(0, 80) + "…";
        }

        return String.format(Locale.US,
                "abs:%s  src:%s\n"
                        + "floor d/c/m: %d/%d/%d\n"
                        + "vertical: %s Δ%.2fm\n"
                        + "correction: %s\n"
                        + "%s",
                absoluteValue,
                poseValue,
                displayFloorIndex,
                candidateFloorIndex,
                matchedFloor,
                verticalValue,
                deltaHeight,
                correctionName,
                reason);
    }

    /**
     * Small immutable helper describing the candidate pose after optional
     * WiFi/GNSS correction, before map matching.
     */
    private static final class AbsoluteObservationCorrection {
        @NonNull
        private final LatLng correctedLatLng;

        @NonNull
        private final String poseSource;

        @Nullable
        private final String observationSource;

        private final double observationResidualMeters;

        private AbsoluteObservationCorrection(@NonNull LatLng correctedLatLng,
                                              @NonNull String poseSource,
                                              @Nullable String observationSource,
                                              double observationResidualMeters) {
            this.correctedLatLng = correctedLatLng;
            this.poseSource = poseSource;
            this.observationSource = observationSource;
            this.observationResidualMeters = observationResidualMeters;
        }

        @NonNull
        static AbsoluteObservationCorrection passThrough(@NonNull LatLng latLng,
                                                         @NonNull String poseSource) {
            return new AbsoluteObservationCorrection(latLng, poseSource, null, 0d);
        }

        @NonNull
        LatLng getCorrectedLatLng() {
            return correctedLatLng;
        }

        @NonNull
        String getPoseSource() {
            return poseSource;
        }

        boolean hasExternalObservation() {
            return observationSource != null;
        }

        @Nullable
        String getObservationSource() {
            return observationSource;
        }

        double getObservationResidualMeters() {
            return observationResidualMeters;
        }
    }
}