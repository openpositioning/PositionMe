package com.openpositioning.PositionMe.fusion;

import androidx.annotation.NonNull;

import com.google.android.gms.maps.model.LatLng;

/**
 * Immutable model representing the latest fused pose estimate produced by the
 * particle filter.
 *
 * <p>The pose is stored in local metric coordinates (x/y in meters) and also as
 * a geographic coordinate for map rendering. The fusion code should stay in
 * local x/y space as long as possible and convert to LatLng only at the
 * boundaries where it is actually needed.</p>
 */
public class FusedPose {
    private final double x;
    private final double y;
    private final double headingRad;
    private final int floor;
    @NonNull
    private final LatLng latLng;
    private final float confidence;

    public FusedPose(double x,
                     double y,
                     double headingRad,
                     int floor,
                     @NonNull LatLng latLng,
                     float confidence) {
        this.x = x;
        this.y = y;
        this.headingRad = headingRad;
        this.floor = floor;
        this.latLng = latLng;
        this.confidence = confidence;
    }

    /** Canonical local x coordinate in meters. */
    public double getX() {
        return x;
    }

    /** Canonical local y coordinate in meters. */
    public double getY() {
        return y;
    }

    /** Backward-compatible alias for getX(). */
    public double getXMeters() {
        return x;
    }

    /** Backward-compatible alias for getY(). */
    public double getYMeters() {
        return y;
    }

    public double getHeadingRad() {
        return headingRad;
    }

    public int getFloor() {
        return floor;
    }

    @NonNull
    public LatLng getLatLng() {
        return latLng;
    }

    public float getConfidence() {
        return confidence;
    }
}
