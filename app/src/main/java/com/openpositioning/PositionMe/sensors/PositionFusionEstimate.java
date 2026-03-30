package com.openpositioning.PositionMe.sensors;

import com.google.android.gms.maps.model.LatLng;

/**
 * Immutable snapshot of the fused position estimate.
 *
 * <p>This object is intentionally tiny and read-only so UI code can safely
 * consume it on every refresh without sharing mutable filter state.</p>
 */
public class PositionFusionEstimate {

    private final LatLng latLng;
    private final int floor;
    private final boolean available;

    /**
     * @param latLng fused position in global coordinates, null when unavailable
     * @param floor inferred floor index
     * @param available true when the estimate is valid for consumption
     */
    public PositionFusionEstimate(LatLng latLng, int floor, boolean available) {
        this.latLng = latLng;
        this.floor = floor;
        this.available = available;
    }

    /** Returns the fused global position, or null when unavailable. */
    public LatLng getLatLng() {
        return latLng;
    }

    /** Returns the inferred floor value for this estimate. */
    public int getFloor() {
        return floor;
    }

    /** Returns whether the estimate contains a usable position. */
    public boolean isAvailable() {
        return available;
    }
}
