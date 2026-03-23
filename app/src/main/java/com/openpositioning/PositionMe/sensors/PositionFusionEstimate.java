package com.openpositioning.PositionMe.sensors;

import com.google.android.gms.maps.model.LatLng;

/**
 * Immutable snapshot of the fused position estimate.
 */
public class PositionFusionEstimate {

    private final LatLng latLng;
    private final int floor;
    private final boolean available;

    public PositionFusionEstimate(LatLng latLng, int floor, boolean available) {
        this.latLng = latLng;
        this.floor = floor;
        this.available = available;
    }

    public LatLng getLatLng() {
        return latLng;
    }

    public int getFloor() {
        return floor;
    }

    public boolean isAvailable() {
        return available;
    }
}
