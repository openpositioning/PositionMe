package com.openpositioning.PositionMe.sensors;

import com.google.android.gms.maps.model.LatLng;

import java.lang.Math;

/**
 * A utility class for converting between global coordinates (latitude, longitude)
 * and local 2D Cartesian coordinates (x, y in meters), using a reference origin point.
 *
 * The conversion assumes a spherical Earth model based on the WGS84 ellipsoid standard.
 * This class is essential for performing sensor fusion in a local metric frame.
 *
 * @see LatLng global coordinate type used in Google Maps
 */
public class LocalCoordinateSystem {

    /** Earth's radius in meters according to WGS84 */
    private static final double EARTH_RADIUS = 6378137.0;

    /** Reference latitude (origin for local coordinates) */
    private Double refLat = null;

    /** Reference longitude (origin for local coordinates) */
    private Double refLon = null;

    /** Indicates whether the reference has been initialized */
    private boolean initialized = false;

    /**
     * Checks whether the coordinate system has been initialized with a reference origin.
     *
     * @return true if initialized, false otherwise
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
            initReference(latitude, longitude);  // Automatic initialization.
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

