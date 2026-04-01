package com.openpositioning.PositionMe.utils;

/**
 * Coordinate conversion helper.
 * Converts WGS84 lat/lon into local XY around a selected origin.
 */
public class CoordinateUtils {

    private static final double EARTH_RADIUS = 6371000.0;

    private Double originLat = null;
    private Double originLon = null;

    // Sets the local origin the first time it becomes available.
    public void setOrigin(double lat, double lon) {
        if (originLat == null && originLon == null) {
            originLat = lat;
            originLon = lon;
        }
    }
    // Clears the local XY origin.
    public void resetOrigin() {
        originLat = null;
        originLon = null;
    }

    // Checks whether the conversion origin has been stored.
    public boolean isOriginSet() {
        return originLat != null && originLon != null;
    }

    // Copies the saved origin values into an output array.
    public boolean copyOriginTo(double[] out) {
        if (out == null || out.length < 2 || !isOriginSet()) {
            return false;
        }
        out[0] = originLat;
        out[1] = originLon;
        return true;
    }
    // Returns the stored origin in latitude and longitude.
    public double[] getOriginLatLon() {
        double[] origin = new double[2];
        return copyOriginTo(origin) ? origin : null;
    }

    // Converts latitude and longitude to local x and y meters.
    public float[] latLonToXY(double lat, double lon) {
        if (!isOriginSet()) {
            return new float[]{0f, 0f};
        }

        double latRad = Math.toRadians(lat);
        double lonRad = Math.toRadians(lon);
        double originLatRad = Math.toRadians(originLat);
        double originLonRad = Math.toRadians(originLon);

        float x = (float) (EARTH_RADIUS * Math.cos(originLatRad) * (lonRad - originLonRad));
        float y = (float) (EARTH_RADIUS * (latRad - originLatRad));
        return new float[]{x, y};
    }

    // Converts local x and y meters back to latitude and longitude.
    public double[] xyToLatLon(float xMeters, float yMeters) {
        if (!isOriginSet()) {
            return null;
        }
        double originLatRad = Math.toRadians(originLat);
        double lat = originLat + Math.toDegrees(yMeters / EARTH_RADIUS);
        double lon = originLon + Math.toDegrees(xMeters / (EARTH_RADIUS * Math.cos(originLatRad)));
        return new double[]{lat, lon};
    }
}
