package com.openpositioning.PositionMe.utils;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;

import com.google.android.gms.maps.model.LatLng;

/**
 * CW2 floor-control module: indoor floor transition controller driven by WiFi seeding and
 * elevation-led switching.
 *
 * <p>WiFi is used only to confirm the initial floor anchor (or a manual re-anchor). Once the
 * anchor exists, automatic switching is controlled by cumulative elevation change relative to the
 * last confirmed floor. This keeps the start floor accurate without allowing later WiFi jitter to
 * drag the user back to the wrong level.</p>
 */
public class IndoorFloorController {

    private static final long WIFI_FLOOR_CONFIRM_MS = 1_100L;
    private static final long FLOOR_CHANGE_DEBOUNCE_MS = 900L;
    private static final float STRONG_ABSOLUTE_SWITCH_METERS = 3.7f;
    private static final float FLOOR_HEIGHT_RATIO_THRESHOLD = 0.78f;
    private final IndoorSpatialConstraintModel spatialModel;

    private float anchorElevation = Float.NaN;
    private int anchorLogicalFloor;
    private boolean anchorConfirmed;
    private int pendingWifiFloor = Integer.MIN_VALUE;
    private long pendingWifiSinceMs;
    private int pendingCandidateFloor = Integer.MIN_VALUE;
    private long pendingCandidateSinceMs;

    public IndoorFloorController(IndoorSpatialConstraintModel spatialModel) {
        this.spatialModel = spatialModel;
    }

    public void reset() {
        anchorElevation = Float.NaN;
        anchorLogicalFloor = 0;
        anchorConfirmed = false;
        pendingWifiFloor = Integer.MIN_VALUE;
        pendingWifiSinceMs = 0L;
        pendingCandidateFloor = Integer.MIN_VALUE;
        pendingCandidateSinceMs = 0L;
    }

    public boolean hasConfirmedAnchor() {
        return anchorConfirmed && !Float.isNaN(anchorElevation);
    }

    public void confirmManualFloor(float elevationMeters, int logicalFloor) {
        spatialModel.setCurrentLogicalFloor(logicalFloor);
        anchorLogicalFloor = spatialModel.getCurrentLogicalFloor();
        anchorElevation = elevationMeters;
        anchorConfirmed = true;
        clearPendingWifiFloor();
        clearPendingCandidate();
    }

    /**
     * Evaluates whether the logical floor should change at the current timestamp.
     *
     * <p>The controller first tries to confirm an initial floor anchor from WiFi. Once the anchor
     * exists, later automatic transitions are driven by cumulative elevation change relative to
     * that anchor, with a short debounce to avoid oscillation around thresholds.</p>
     */
    @Nullable
    public Integer evaluate(@Nullable LatLng currentPosition,
                            float elevationMeters,
                            @Nullable Integer wifiFloor,
                            boolean transitionZoneHint,
                            long timestampMillis) {
        if (currentPosition == null) {
            return null;
        }

        spatialModel.updatePosition(currentPosition);
        if (!spatialModel.hasIndoorContext()) {
            return null;
        }

        float floorHeight = spatialModel.getCurrentFloorHeight();
        if (floorHeight <= 0f) {
            return null;
        }

        Integer normalizedWifiFloor = wifiFloor == null
                ? null
                : spatialModel.normalizeExternalFloorObservation(wifiFloor);
        Integer seededFloor = maybeSeedFloorFromWifi(
                normalizedWifiFloor,
                currentPosition,
                elevationMeters,
                timestampMillis
        );
        if (seededFloor != null) {
            return seededFloor;
        }

        if (!anchorConfirmed) {
            return null;
        }

        int currentLogicalFloor = spatialModel.getCurrentLogicalFloor();
        float elevationDelta = elevationMeters - anchorElevation;
        int candidateFloor = resolveNextFloor(elevationDelta, floorHeight);
        if (candidateFloor == currentLogicalFloor) {
            clearPendingCandidate();
            return null;
        }

        if (!transitionZoneHint) {
            clearPendingCandidate();
            return null;
        }

        if (candidateFloor != pendingCandidateFloor) {
            pendingCandidateFloor = candidateFloor;
            pendingCandidateSinceMs = timestampMillis;
            return null;
        }

        if (timestampMillis - pendingCandidateSinceMs < FLOOR_CHANGE_DEBOUNCE_MS) {
            return null;
        }

        spatialModel.setCurrentLogicalFloor(candidateFloor);
        clearPendingCandidate();
        return spatialModel.getCurrentLogicalFloor();
    }

    /**
     * Seeds the starting floor from WiFi only when the same floor has been observed consistently
     * for a short time window.
     */
    @Nullable
    private Integer maybeSeedFloorFromWifi(@Nullable Integer normalizedWifiFloor,
                                           @NonNull LatLng currentPosition,
                                           float elevationMeters,
                                           long timestampMillis) {
        if (normalizedWifiFloor == null) {
            clearPendingWifiFloor();
            return null;
        }

        if (anchorConfirmed && spatialModel.getCurrentLogicalFloor() != normalizedWifiFloor) {
            clearPendingWifiFloor();
            return null;
        }

        if (normalizedWifiFloor != pendingWifiFloor) {
            pendingWifiFloor = normalizedWifiFloor;
            pendingWifiSinceMs = timestampMillis;
            return null;
        }

        if (!anchorConfirmed && timestampMillis - pendingWifiSinceMs < WIFI_FLOOR_CONFIRM_MS) {
            return null;
        }

        if (!anchorConfirmed) {
            spatialModel.setCurrentLogicalFloor(normalizedWifiFloor);
            seedAnchor(currentPosition, elevationMeters, spatialModel.getCurrentLogicalFloor());
            anchorConfirmed = true;
            clearPendingWifiFloor();
            clearPendingCandidate();
            return spatialModel.getCurrentLogicalFloor();
        }

        clearPendingWifiFloor();
        return null;
    }

    /**
     * Converts cumulative elevation change into a logical floor candidate.
     */
    private int resolveNextFloor(float elevationDelta, float floorHeight) {
        float floorChangeThreshold = Math.max(
                STRONG_ABSOLUTE_SWITCH_METERS,
                floorHeight * FLOOR_HEIGHT_RATIO_THRESHOLD
        );
        float absoluteDelta = Math.abs(elevationDelta);
        if (absoluteDelta < floorChangeThreshold) {
            return anchorLogicalFloor;
        }

        int movedFloors = 1 + (int) Math.floor((absoluteDelta - floorChangeThreshold) / floorHeight);
        int direction = elevationDelta > 0f ? 1 : -1;
        return spatialModel.clampLogicalFloor(
                spatialModel.getCurrentBuildingId(),
                anchorLogicalFloor + direction * movedFloors
        );
    }

    private void seedAnchor(LatLng currentPosition, float elevationMeters, int logicalFloor) {
        anchorLogicalFloor = logicalFloor;
        anchorElevation = elevationMeters;
    }

    private void clearPendingWifiFloor() {
        pendingWifiFloor = Integer.MIN_VALUE;
        pendingWifiSinceMs = 0L;
    }

    private void clearPendingCandidate() {
        pendingCandidateFloor = Integer.MIN_VALUE;
        pendingCandidateSinceMs = 0L;
    }
}
