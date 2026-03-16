package com.openpositioning.PositionMe.utils;

/**
 * Converts between WGS84 geographic coordinates (latitude/longitude) and local
 * East-North Cartesian coordinates in metres.
 *
 * A reference point (East-North origin) should be set via the constructor before any
 * conversion can be performed. This origin is the first reliable GNSS at the start of a recording session.
 *
 * @author Haoning Huang
 */
public class CoordinateConverter {

    // Number of metres corresponding to one degree of latitude (constant at all latitudes)
    private static final double METRES_PER_DEGREE = 111320.0;

    // Reference point stored in degrees and radians
    private final double refLat;          // reference latitude  in degrees
    private final double refLon;          // reference longitude in degrees
    private final double cosRefLat;       // cos(refLat) precomputed to avoid repeated calls

    /**
     * Constructs a converter with the given WGS84 reference point as the East-North origin.
     *
     * @param refLat  Reference latitude  in decimal degrees
     * @param refLon  Reference longitude in decimal degrees
     */
    public CoordinateConverter(double refLat, double refLon) {
        this.refLat    = refLat;
        this.refLon    = refLon;
        this.cosRefLat = Math.cos(Math.toRadians(refLat));
    }

    /**
     * Converts a WGS84 position to local East-North coordinates in metres.
     *
     * @param lat  Latitude  of the point to convert, in decimal degrees
     * @param lon  Longitude of the point to convert, in decimal degrees
     * @return     float array {east, north} in metres relative to the reference point
     */
    public float[] toEnu(double lat, double lon) {
        float north = (float) ((lat - refLat) * METRES_PER_DEGREE);
        float east  = (float) ((lon - refLon) * METRES_PER_DEGREE * cosRefLat);
        return new float[]{east, north};
    }

    /**
     * Converts a local East-North position back to WGS84 latitude and longitude.
     *
     * @param east   East  displacement in metres from the reference point
     * @param north  North displacement in metres from the reference point
     * @return       double array {latitude, longitude} in decimal degrees
     */
    public double[] toLatLon(float east, float north) {
        double lat = refLat + north / METRES_PER_DEGREE;
        double lon = refLon + east  / (METRES_PER_DEGREE * cosRefLat);
        return new double[]{lat, lon};
    }

    /** @return Reference latitude in decimal degrees */
    public double getRefLat() { return refLat; }

    /** @return Reference longitude in decimal degrees */
    public double getRefLon() { return refLon; }
}
