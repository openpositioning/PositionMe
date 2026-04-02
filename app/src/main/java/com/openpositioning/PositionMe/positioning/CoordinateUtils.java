package com.openpositioning.PositionMe.positioning;

public class CoordinateUtils {
    private final double originLat;
    private final double originLon;
    private final double metersPerLat;
    private final double metersPerLon;

    public CoordinateUtils(double originLat, double originLon) {
        this.originLat = originLat;
        this.originLon = originLon;
        this.metersPerLat = 111320.0;
        this.metersPerLon = 111320.0 * Math.cos(Math.toRadians(originLat));
    }

    public double[] toLocal(double lat, double lon) {
        double east  = (lon - originLon) * metersPerLon;
        double north = (lat - originLat) * metersPerLat;
        return new double[]{east, north};
    }

    public double[] toGlobal(double east, double north) {
        double lon = originLon + east  / metersPerLon;
        double lat = originLat + north / metersPerLat;
        return new double[]{lat, lon};
    }
}