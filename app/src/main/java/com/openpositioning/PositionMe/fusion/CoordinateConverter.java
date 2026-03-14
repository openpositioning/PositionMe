package com.openpositioning.PositionMe.fusion;

import com.google.android.gms.maps.model.LatLng;

/**
 * Utility for converting between geographic WGS84 coordinates and a local
 * tangent-plane style coordinate system expressed in meters.
 *
 * <p>This is intentionally simple and is appropriate for small local areas such
 * as the assignment buildings. It matches the assignment requirement that PDR,
 * Wi-Fi, and GNSS should be fused in a local easting/northing-style frame. </p>
 */
public class CoordinateConverter {

    private static final double EARTH_RADIUS_METERS = 6378137.0;

    private final double lat0Deg;
    private final double lon0Deg;

    /**
     * Creates a converter anchored at the given geographic origin.
     *
     * @param lat0Deg anchor latitude in degrees
     * @param lon0Deg anchor longitude in degrees
     */
    public CoordinateConverter(double lat0Deg, double lon0Deg) {
        this.lat0Deg = lat0Deg;
        this.lon0Deg = lon0Deg;
    }

    /**
     * Converts a LatLng into local metric coordinates.
     *
     * @param latLng geographic coordinate to convert
     * @return array of size 2 containing x and y in meters
     */
    public double[] latLngToLocal(LatLng latLng) {
        double dLat = Math.toRadians(latLng.latitude - lat0Deg);
        double dLon = Math.toRadians(latLng.longitude - lon0Deg);

        double x = EARTH_RADIUS_METERS * dLon * Math.cos(Math.toRadians(lat0Deg));
        double y = EARTH_RADIUS_METERS * dLat;
        return new double[]{x, y};
    }

    /**
     * Converts local metric coordinates back into a LatLng.
     *
     * @param x local x coordinate in meters
     * @param y local y coordinate in meters
     * @return corresponding geographic coordinate
     */
    public LatLng localToLatLng(double x, double y) {
        double lat = lat0Deg + Math.toDegrees(y / EARTH_RADIUS_METERS);
        double lon = lon0Deg + Math.toDegrees(
                x / (EARTH_RADIUS_METERS * Math.cos(Math.toRadians(lat0Deg)))
        );
        return new LatLng(lat, lon);
    }
}