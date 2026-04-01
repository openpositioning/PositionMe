package com.openpositioning.PositionMe.mapmatching;

import com.google.android.gms.maps.model.LatLng;

/**
 * Represents the output of one map-matching pass.
 */
public class MapMatchingResult {

    private final boolean validPosition;
    private final boolean crossedWall;
    private final boolean nearStairs;
    private final boolean nearLift;
    private final boolean floorChangeAllowed;
    private final LatLng correctedLatLng;
    private final int correctedFloor;
    private final CorrectionType correctionType;
    private final String debugReason;

    public MapMatchingResult(
            boolean validPosition,
            boolean crossedWall,
            boolean nearStairs,
            boolean nearLift,
            boolean floorChangeAllowed,
            LatLng correctedLatLng,
            int correctedFloor,
            CorrectionType correctionType,
            String debugReason
    ) {
        this.validPosition = validPosition;
        this.crossedWall = crossedWall;
        this.nearStairs = nearStairs;
        this.nearLift = nearLift;
        this.floorChangeAllowed = floorChangeAllowed;
        this.correctedLatLng = correctedLatLng;
        this.correctedFloor = correctedFloor;
        this.correctionType = correctionType;
        this.debugReason = debugReason;
    }

    public boolean isValidPosition() {
        return validPosition;
    }

    public boolean isCrossedWall() {
        return crossedWall;
    }

    public boolean isNearStairs() {
        return nearStairs;
    }

    public boolean isNearLift() {
        return nearLift;
    }

    public boolean isFloorChangeAllowed() {
        return floorChangeAllowed;
    }

    public LatLng getCorrectedLatLng() {
        return correctedLatLng;
    }

    public int getCorrectedFloor() {
        return correctedFloor;
    }

    public CorrectionType getCorrectionType() {
        return correctionType;
    }

    public String getDebugReason() {
        return debugReason;
    }
}