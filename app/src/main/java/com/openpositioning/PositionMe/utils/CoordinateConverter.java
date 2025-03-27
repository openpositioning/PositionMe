package com.openpositioning.PositionMe.utils;

import android.util.Log;
import com.google.android.gms.maps.model.LatLng;

/**
 * A utility class for converting between different coordinate systems:
 * - WGS84 geodetic coordinates (latitude, longitude, altitude)
 * - ECEF (Earth-Centered, Earth-Fixed) coordinates (X, Y, Z)
 * - ENU (East-North-Up) local tangent plane coordinates
 *
 * This enables accurate positioning of PDR coordinates on maps.
 */
public final class CoordinateConverter {
    private static final String TAG = "CoordinateConverter";

    // WGS84 ellipsoid parameters
    private static final double SEMI_MAJOR_AXIS = 6378137.0;        // Earth's semi-major axis in meters
    private static final double SEMI_MINOR_AXIS = 6356752.31424518; // Earth's semi-minor axis in meters
    private static final double FLATTENING = (SEMI_MAJOR_AXIS - SEMI_MINOR_AXIS) / SEMI_MAJOR_AXIS;
    private static final double ECCENTRICITY_SQUARED = FLATTENING * (2 - FLATTENING);

    // Constants for angle conversion
    private static final double DEG_TO_RAD = Math.PI / 180.0;
    private static final double RAD_TO_DEG = 180.0 / Math.PI;

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private CoordinateConverter() {
        // Utility class, no instantiation
    }

    /**
     * Converts WGS84 geodetic coordinates to ECEF coordinates.
     *
     * @param latitude  Latitude in degrees
     * @param longitude Longitude in degrees
     * @param altitude  Altitude above ellipsoid in meters
     * @return ECEF coordinates [X, Y, Z] in meters
     */
    public static double[] geodetic2Ecef(double latitude, double longitude, double altitude) {
        double[] ecefCoords = new double[3];
        double latRad = latitude * DEG_TO_RAD;
        double lngRad = longitude * DEG_TO_RAD;

        // Calculate prime vertical radius of curvature
        double N = Math.pow(SEMI_MAJOR_AXIS, 2) /
                Math.hypot((SEMI_MAJOR_AXIS * Math.cos(latRad)), (SEMI_MINOR_AXIS * Math.sin(latRad)));

        // Calculate ECEF coordinates
        ecefCoords[0] = (N + altitude) * Math.cos(latRad) * Math.cos(lngRad);
        ecefCoords[1] = (N + altitude) * Math.cos(latRad) * Math.sin(lngRad);
        ecefCoords[2] = (N * Math.pow((SEMI_MINOR_AXIS / SEMI_MAJOR_AXIS), 2) + altitude) * Math.sin(latRad);

        return ecefCoords;
    }

    /**
     * Converts ECEF coordinate differences to ENU coordinates.
     *
     * @param dX ECEF X difference in meters
     * @param dY ECEF Y difference in meters
     * @param dZ ECEF Z difference in meters
     * @param refLatitude  Reference latitude in degrees
     * @param refLongitude Reference longitude in degrees
     * @return ENU coordinates [East, North, Up] in meters
     */
    public static double[] ecef2Enu(double dX, double dY, double dZ, double refLatitude, double refLongitude) {
        double[] enuCoords = new double[3];
        double latRad = refLatitude * DEG_TO_RAD;
        double lngRad = refLongitude * DEG_TO_RAD;

        double t = Math.cos(lngRad) * dX + Math.sin(lngRad) * dY;
        enuCoords[0] = -Math.sin(lngRad) * dX + Math.cos(lngRad) * dY;
        enuCoords[1] = -Math.sin(latRad) * t + Math.cos(latRad) * dZ;
        enuCoords[2] = Math.cos(latRad) * t + Math.sin(latRad) * dZ;

        return enuCoords;
    }

    /**
     * Converts ENU coordinates to ECEF coordinates relative to a reference point.
     *
     * @param east The east displacement in meters
     * @param north The north displacement in meters
     * @param up The altitude displacement in meters
     * @param refLatitude The reference point latitude in degrees
     * @param refLongitude The reference point longitude in degrees
     * @param refAltitude The reference point altitude in meters
     * @return ECEF coordinates [X, Y, Z] in meters
     */
    public static double[] enu2Ecef(double east, double north, double up,
                                    double refLatitude, double refLongitude, double refAltitude) {
        double[] ecefRefCoords = geodetic2Ecef(refLatitude, refLongitude, refAltitude);
        return enu2Ecef(east, north, up, refLatitude, refLongitude, ecefRefCoords);
    }

    /**
     * Converts ENU coordinates to ECEF coordinates using pre-calculated reference ECEF coordinates.
     *
     * @param east The east displacement in meters
     * @param north The north displacement in meters
     * @param up The altitude displacement in meters
     * @param refLatitude The reference point latitude in degrees
     * @param refLongitude The reference point longitude in degrees
     * @param ecefRefCoords The reference point ECEF coordinates [X, Y, Z]
     * @return ECEF coordinates [X, Y, Z] in meters
     */
    public static double[] enu2Ecef(double east, double north, double up,
                                    double refLatitude, double refLongitude, double[] ecefRefCoords) {
        double[] ecefCoords = new double[3];
        double latRad = refLatitude * DEG_TO_RAD;
        double lngRad = refLongitude * DEG_TO_RAD;

        // Calculate ECEF coordinates
        ecefCoords[0] = (Math.cos(lngRad) * (Math.cos(latRad) * up - Math.sin(latRad) * north) -
                Math.sin(lngRad) * east) + ecefRefCoords[0];

        ecefCoords[1] = (Math.sin(lngRad) * (Math.cos(latRad) * up - Math.sin(latRad) * north) +
                Math.cos(lngRad) * east) + ecefRefCoords[1];

        ecefCoords[2] = (Math.sin(latRad) * up + Math.cos(latRad) * north) + ecefRefCoords[2];

        return ecefCoords;
    }

    /**
     * Converts ECEF coordinates to WGS84 geodetic coordinates.
     *
     * @param ecefCoords ECEF coordinates [X, Y, Z] in meters
     * @return WGS84 geodetic coordinates as LatLng (latitude, longitude in degrees)
     */
    public static LatLng ecef2Geodetic(double[] ecefCoords) {
        double x = ecefCoords[0];
        double y = ecefCoords[1];
        double z = ecefCoords[2];

        double asq = Math.pow(SEMI_MAJOR_AXIS, 2);
        double bsq = Math.pow(SEMI_MINOR_AXIS, 2);
        double ep = Math.sqrt((asq - bsq) / bsq);
        double p = Math.sqrt(x*x + y*y);
        double th = Math.atan2(SEMI_MAJOR_AXIS * z, SEMI_MINOR_AXIS * p);

        double longitude = Math.atan2(y, x);
        double latitude = Math.atan2(
                (z + ep*ep * SEMI_MINOR_AXIS * Math.pow(Math.sin(th), 3)),
                (p - ECCENTRICITY_SQUARED * SEMI_MAJOR_AXIS * Math.pow(Math.cos(th), 3))
        );

        double N = SEMI_MAJOR_AXIS / Math.sqrt(1 - ECCENTRICITY_SQUARED * Math.pow(Math.sin(latitude), 2));
        double altitude = p / Math.cos(latitude) - N;

        // Normalize longitude to range [0, 2π)
        longitude = longitude % (2 * Math.PI);

        // Convert to degrees and return
        return new LatLng(latitude * RAD_TO_DEG, longitude * RAD_TO_DEG);
    }

    /**
     * Converts ENU coordinates to WGS84 geodetic coordinates.
     *
     * @param east East coordinate in meters
     * @param north North coordinate in meters
     * @param up Up coordinate in meters
     * @param refLatitude Reference latitude in degrees
     * @param refLongitude Reference longitude in degrees
     * @param refAltitude Reference altitude in meters
     * @return WGS84 geodetic coordinates as LatLng (latitude, longitude in degrees)
     */
    public static LatLng enu2Geodetic(double east, double north, double up,
                                      double refLatitude, double refLongitude, double refAltitude) {
        double[] ecefCoords = enu2Ecef(east, north, up, refLatitude, refLongitude, refAltitude);
        Log.d(TAG, "ENU to ECEF: x=" + ecefCoords[0] + " y=" + ecefCoords[1] + " z=" + ecefCoords[2]);
        return ecef2Geodetic(ecefCoords);
    }

    /**
     * Converts ENU coordinates to WGS84 geodetic coordinates using pre-calculated reference ECEF coordinates.
     *
     * @param east East coordinate in meters
     * @param north North coordinate in meters
     * @param up Up coordinate in meters
     * @param refLatitude Reference latitude in degrees
     * @param refLongitude Reference longitude in degrees
     * @param ecefRefCoords Reference ECEF coordinates [X, Y, Z]
     * @return WGS84 geodetic coordinates as LatLng (latitude, longitude in degrees)
     */
    public static LatLng enu2Geodetic(double east, double north, double up,
                                      double refLatitude, double refLongitude, double[] ecefRefCoords) {
        double[] ecefCoords = enu2Ecef(east, north, up, refLatitude, refLongitude, ecefRefCoords);
        return ecef2Geodetic(ecefCoords);
    }

    /**
     * Converts WGS84 geodetic coordinates to ENU coordinates.
     *
     * @param latitude Latitude in degrees
     * @param longitude Longitude in degrees
     * @param altitude Altitude in meters
     * @param refLatitude Reference latitude in degrees
     * @param refLongitude Reference longitude in degrees
     * @param refAltitude Reference altitude in meters
     * @return ENU coordinates [East, North, Up] in meters
     */
    public static double[] geodetic2Enu(double latitude, double longitude, double altitude,
                                        double refLatitude, double refLongitude, double refAltitude) {
        double[] ecefCoords = geodetic2Ecef(latitude, longitude, altitude);
        double[] ecefRefCoords = geodetic2Ecef(refLatitude, refLongitude, refAltitude);

        double dX = ecefCoords[0] - ecefRefCoords[0];
        double dY = ecefCoords[1] - ecefRefCoords[1];
        double dZ = ecefCoords[2] - ecefRefCoords[2];

        return ecef2Enu(dX, dY, dZ, refLatitude, refLongitude);
    }

    /**
     * Normalizes an angle to be within -π to π range.
     *
     * @param angle Angle in radians
     * @return Normalized angle in radians
     */
    public static double wrapToPi(double angle) {
        angle = angle % (2 * Math.PI);
        if (angle > Math.PI) angle -= 2 * Math.PI;
        if (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    /**
     * Normalizes an angle to be within 0 to 2π range.
     *
     * @param angle Angle in radians
     * @return Normalized angle in radians
     */
    public static double wrapTo2Pi(double angle) {
        angle = angle % (2 * Math.PI);
        if (angle < 0) angle += 2 * Math.PI;
        return angle;
    }

    /**
     * Calculate distance between two geodetic points using the Haversine formula.
     *
     * @param lat1 First point latitude in degrees
     * @param lon1 First point longitude in degrees
     * @param lat2 Second point latitude in degrees
     * @param lon2 Second point longitude in degrees
     * @return Distance in meters
     */
    public static double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        // Convert degrees to radians
        double lat1Rad = lat1 * DEG_TO_RAD;
        double lon1Rad = lon1 * DEG_TO_RAD;
        double lat2Rad = lat2 * DEG_TO_RAD;
        double lon2Rad = lon2 * DEG_TO_RAD;

        // Haversine formula
        double dLat = lat2Rad - lat1Rad;
        double dLon = lon2Rad - lon1Rad;
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                        Math.sin(dLon/2) * Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));

        return SEMI_MAJOR_AXIS * c;
    }

    /**
     * Calculate distance between two points in the ENU coordinate system.
     *
     * @param east1 East coordinate of point 1 in meters
     * @param north1 North coordinate of point 1 in meters
     * @param east2 East coordinate of point 2 in meters
     * @param north2 North coordinate of point 2 in meters
     * @return Distance in meters
     */
    public static double enuDistance(double east1, double north1, double east2, double north2) {
        double dE = east2 - east1;
        double dN = north2 - north1;
        return Math.sqrt(dE * dE + dN * dN);
    }
}