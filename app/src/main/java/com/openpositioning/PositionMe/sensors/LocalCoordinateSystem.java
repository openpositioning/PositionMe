package com.openpositioning.PositionMe.sensors;

import com.google.android.gms.maps.model.LatLng;

import java.lang.Math;

/**
 * A utility class for converting between global coordinates (latitude, longitude)
 * and local 2D Cartesian coordinates (x, y in meters), using a reference origin point.
 * The conversion is based on the WGS84 ellipsoid model.
 */
public class LocalCoordinateSystem {

    // Earth's radius in meters (WGS84 standard)
    private static final double EARTH_RADIUS = 6378137.0; // meters (WGS84)

    // Reference latitude and longitude (origin for local coordinate system)
    private Double refLat = null;  // 原点纬度
    private Double refLon = null;  // 原点经度

    // Indicates whether the reference has been initialized
    private boolean initialized = false;

    /**
     * Returns true if the reference location has been set.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Initializes the reference latitude and longitude used for local coordinate conversion.
     * This sets the origin point of the local coordinate system.
     *
     * @param latitude  Latitude of the reference point
     * @param longitude Longitude of the reference point
     */
    public void initReference(double latitude, double longitude) {
//        if (!initialized) {
        this.refLat = latitude;
        this.refLon = longitude;
        this.initialized = true;
//        }
    }

    /**
     * Converts a global (lat/lon) coordinate to local (x/y) coordinates in meters.
     * If the reference is not initialized, it will auto-initialize to the first input coordinate.
     *
     * @param latitude  Latitude to convert
     * @param longitude Longitude to convert
     * @return A float array [x, y] representing the position in meters from the reference point
     */
    public float[] toLocal(double latitude, double longitude) {
        if (!initialized) {
            initReference(latitude, longitude);  // 自动初始化
            return new float[]{0.0F, 0.0F};
        }

        double dLat = Math.toRadians(latitude - refLat);
        double dLon = Math.toRadians(longitude - refLon);
        double meanLat = Math.toRadians((latitude + refLat) / 2.0);

        double x = EARTH_RADIUS * dLon * Math.cos(meanLat);
        double y = EARTH_RADIUS * dLat;
        return new float[]{(float) x, (float) y};
    }

    /**
     * Converts a local coordinate (x, y) in meters back to global (lat/lon) format.
     *
     * @param x Local X (easting) in meters
     * @param y Local Y (northing) in meters
     * @return The corresponding LatLng (latitude, longitude) in global coordinates
     */
    public LatLng toGlobal(double x, double y) {
        if (!initialized) {
            throw new IllegalStateException("Reference point not initialized.");
        }

        double dLat = y / EARTH_RADIUS;
        double dLon =  x / (EARTH_RADIUS * Math.cos(Math.toRadians(refLat)));

        double lat = refLat + Math.toDegrees(dLat);
        double lon = refLon + Math.toDegrees(dLon);
        return new LatLng(lat, lon);
    }


    /**
     * Gets the reference latitude and longitude as an array.
     *
     * @return [latitude, longitude] of the reference point
     * @throws IllegalStateException if the reference point is not set
     */
    public double[] getReferenceLatLon() {
        if (!initialized) {
            throw new IllegalStateException("Reference point not initialized.");
        }
        return new double[]{refLat, refLon};
    }
}

