package com.openpositioning.PositionMe.utils;

/**
 * Utility class for converting between WGS84 geographic coordinates and a local
 * East/North Cartesian plane centred on a reference point.
 *
 * <p>All positioning algorithms (PDR, Particle Filter) operate in the local
 * East/North metric space (metres).  GNSS and WiFi fixes arrive in WGS84
 * (latitude / longitude degrees) and must be projected before they can be
 * combined with PDR data.</p>
 *
 * <p>The flat-earth approximation used here is accurate to within ~1 cm over
 * distances of up to ~1 km, which is more than sufficient for indoor
 * positioning.</p>
 *
 * @author PositionMe Assessment 2
 */
public class CoordinateConverter {

    /** Metres per degree of latitude (constant everywhere on Earth). */
    private static final double METRES_PER_DEG_LAT = 111_139.0;

    /** Reference latitude used to scale longitude degrees → metres. */
    private final double refLat;
    /** Reference longitude. */
    private final double refLng;

    /** Metres per degree of longitude at the reference latitude. */
    private final double metresPerDegLng;

    /**
     * Create a converter anchored at the given WGS84 reference point.
     *
     * @param refLatDeg  Reference latitude in decimal degrees.
     * @param refLngDeg  Reference longitude in decimal degrees.
     */
    public CoordinateConverter(double refLatDeg, double refLngDeg) {
        this.refLat = refLatDeg;
        this.refLng = refLngDeg;
        this.metresPerDegLng = METRES_PER_DEG_LAT * Math.cos(Math.toRadians(refLatDeg));
    }


    // WGS84 → local East/North
    /**
     * Convert a WGS84 position to local East/North coordinates (metres).
     *
     * @param latDeg  Latitude in decimal degrees.
     * @param lngDeg  Longitude in decimal degrees.
     * @return {@code double[]{east, north}} in metres relative to the reference.
     */
    public double[] toEastNorth(double latDeg, double lngDeg) {
        double east  = (lngDeg - refLng) * metresPerDegLng;
        double north = (latDeg - refLat) * METRES_PER_DEG_LAT;
        return new double[]{east, north};
    }

    // Local East/North → WGS84
    /**
     * Convert local East/North coordinates (metres) back to WGS84.
     *
     * @param east   Easting in metres.
     * @param north  Northing in metres.
     * @return {@code double[]{latitude, longitude}} in decimal degrees.
     */
    public double[] toLatLng(double east, double north) {
        double lat = refLat + north / METRES_PER_DEG_LAT;
        double lng = refLng + east  / metresPerDegLng;
        return new double[]{lat, lng};
    }

    // Convenience helpers
    /** @return Reference latitude in decimal degrees. */
    public double getRefLat() { return refLat; }

    /** @return Reference longitude in decimal degrees. */
    public double getRefLng() { return refLng; }
}
