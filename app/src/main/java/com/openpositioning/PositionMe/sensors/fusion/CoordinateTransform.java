package com.openpositioning.PositionMe.sensors.fusion;

/**
 * Converts between WGS84 geodetic coordinates (latitude/longitude) and a local
 * East-North-Up (ENU) Cartesian frame centred on a chosen reference point.
 *
 * <p>Uses a flat-earth approximation which is accurate to within centimetres
 * for distances under 1 km from the origin — well within the assignment's
 * indoor environment (Nucleus + Library).</p>
 */
public class CoordinateTransform {

    private static final double EARTH_RADIUS = 6371000.0; // metres
    private static final double METRES_PER_DEGREE_LAT = 111132.92;

    private double originLat;
    private double originLng;
    private double metersPerDegreeLng;
    private boolean initialized = false;

    /**
     * Sets the ENU origin. All subsequent conversions are relative to this point.
     *
     * @param lat latitude in degrees (WGS84)
     * @param lng longitude in degrees (WGS84)
     */
    public void setOrigin(double lat, double lng) {
        this.originLat = lat;
        this.originLng = lng;
        this.metersPerDegreeLng = METRES_PER_DEGREE_LAT * Math.cos(Math.toRadians(lat));
        this.initialized = true;
    }

    /** Returns {@code true} if the ENU origin has been set. */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Converts WGS84 to local ENU coordinates.
     *
     * @return double array {easting, northing} in metres
     */
    public double[] toEastNorth(double lat, double lng) {
        double east = (lng - originLng) * metersPerDegreeLng;
        double north = (lat - originLat) * METRES_PER_DEGREE_LAT;
        return new double[]{east, north};
    }

    /**
     * Converts local ENU coordinates back to WGS84.
     *
     * @return double array {latitude, longitude} in degrees
     */
    public double[] toLatLng(double east, double north) {
        double lat = originLat + north / METRES_PER_DEGREE_LAT;
        double lng = originLng + east / metersPerDegreeLng;
        return new double[]{lat, lng};
    }

    /** Returns the origin latitude in degrees (WGS84). */
    public double getOriginLat() {
        return originLat;
    }

    /** Returns the origin longitude in degrees (WGS84). */
    public double getOriginLng() {
        return originLng;
    }
}
