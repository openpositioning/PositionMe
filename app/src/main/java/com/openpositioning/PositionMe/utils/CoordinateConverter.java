package com.openpositioning.PositionMe.utils;

import android.util.Log;
import com.google.android.gms.maps.model.LatLng;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility for converting between coordinate systems required for positioning fusion.
 * Handles transformations between WGS84, ECEF, and ENU coordinate systems with
 * optimizations for mobile environments.
 */
public class CoordinateConverter {
    private static final String TAG = "CoordinateConverter";

    // WGS84 ellipsoid parameters
    private static final double SEMI_MAJOR_AXIS = 6378137.0;      // Earth semi-major axis in meters
    private static final double SEMI_MINOR_AXIS = 6356752.31424;  // Earth semi-minor axis in meters
    private static final double ECCENTRICITY_SQUARED = 6.69437999014e-3; // First eccentricity squared

    // Values used for performance optimization
    private static final double PI_OVER_180 = Math.PI / 180.0;
    private static final double DEG_TO_RAD = PI_OVER_180;
    private static final double RAD_TO_DEG = 180.0 / Math.PI;

    // Caching for repeated transformations (keyed by reference point hash)
    private static Map<Integer, TransformationMatrices> transformationCache = new HashMap<>();
    private static final int MAX_CACHE_SIZE = 10;

    /**
     * Private constructor to prevent instantiation
     */
    private CoordinateConverter() {
        // Utility class, no instantiation
    }

    /**
     * Converts geodetic coordinates to local ENU coordinates.
     * Optimized for PDR and sensor fusion in mobile environments.
     *
     * @param latitude Latitude in degrees
     * @param longitude Longitude in degrees
     * @param height Height above ellipsoid in meters
     * @param refLatitude Reference latitude in degrees
     * @param refLongitude Reference longitude in degrees
     * @param refHeight Reference height in meters
     * @return East, North, Up coordinates in meters
     */
    public static double[] geodetic2Enu(double latitude, double longitude, double height,
                                        double refLatitude, double refLongitude, double refHeight) {
        // Convert to ECEF first
        double[] pointEcef = geodetic2Ecef(latitude, longitude, height);
        double[] referenceEcef = geodetic2Ecef(refLatitude, refLongitude, refHeight);

        // Compute ENU from ECEF difference
        return ecef2Enu(
                pointEcef[0] - referenceEcef[0],
                pointEcef[1] - referenceEcef[1],
                pointEcef[2] - referenceEcef[2],
                refLatitude, refLongitude
        );
    }

    /**
     * Converts local ENU coordinates to geodetic coordinates.
     * Used for displaying PDR results on map.
     *
     * @param east East coordinate in meters
     * @param north North coordinate in meters
     * @param up Up coordinate in meters
     * @param refLatitude Reference latitude in degrees
     * @param refLongitude Reference longitude in degrees
     * @param refHeight Reference height in meters
     * @return LatLng object containing latitude and longitude in degrees
     */
    public static LatLng enu2Geodetic(double east, double north, double up,
                                      double refLatitude, double refLongitude, double refHeight) {
        try {
            // Get reference point in ECEF
            double[] refEcef = geodetic2Ecef(refLatitude, refLongitude, refHeight);

            // Convert ENU to ECEF
            double[] ecefDelta = enu2EcefDelta(east, north, up, refLatitude, refLongitude);

            // Add delta to reference ECEF
            double[] pointEcef = new double[3];
            pointEcef[0] = refEcef[0] + ecefDelta[0];
            pointEcef[1] = refEcef[1] + ecefDelta[1];
            pointEcef[2] = refEcef[2] + ecefDelta[2];

            // Convert ECEF to geodetic
            double[] geodetic = ecef2Geodetic(pointEcef[0], pointEcef[1], pointEcef[2]);

            // Return only lat/lng for map display
            return new LatLng(geodetic[0], geodetic[1]);
        } catch (Exception e) {
            Log.e(TAG, "Error converting ENU to geodetic: " + e.getMessage());
            return new LatLng(refLatitude, refLongitude);
        }
    }

    /**
     * Convert geodetic coordinates to ECEF coordinates.
     * Optimized implementation for mobile devices.
     *
     * @param latitude Latitude in degrees
     * @param longitude Longitude in degrees
     * @param height Height above ellipsoid in meters
     * @return ECEF coordinates [X, Y, Z] in meters
     */
    public static double[] geodetic2Ecef(double latitude, double longitude, double height) {
        double[] ecef = new double[3];

        // Convert degrees to radians
        double latRad = latitude * DEG_TO_RAD;
        double lonRad = longitude * DEG_TO_RAD;

        // Precalculate frequently used values
        double sinLat = Math.sin(latRad);
        double cosLat = Math.cos(latRad);
        double sinLon = Math.sin(lonRad);
        double cosLon = Math.cos(lonRad);

        // Calculate prime vertical radius of curvature
        double N = SEMI_MAJOR_AXIS / Math.sqrt(1.0 - ECCENTRICITY_SQUARED * sinLat * sinLat);

        // Calculate ECEF coordinates
        ecef[0] = (N + height) * cosLat * cosLon;
        ecef[1] = (N + height) * cosLat * sinLon;
        ecef[2] = (N * (1.0 - ECCENTRICITY_SQUARED) + height) * sinLat;

        return ecef;
    }

    /**
     * Convert ECEF coordinates to geodetic coordinates using Bowring's method.
     * This implementation provides millimeter-level precision with good performance.
     *
     * @param X ECEF X coordinate in meters
     * @param Y ECEF Y coordinate in meters
     * @param Z ECEF Z coordinate in meters
     * @return Geodetic coordinates [latitude, longitude, height] (degrees, degrees, meters)
     */
    public static double[] ecef2Geodetic(double X, double Y, double Z) {
        double[] geodetic = new double[3];

        double p = Math.sqrt(X*X + Y*Y);
        double theta = Math.atan2(Z * SEMI_MAJOR_AXIS, p * SEMI_MINOR_AXIS);

        // Bowring's method for latitude
        double sinTheta = Math.sin(theta);
        double cosTheta = Math.cos(theta);

        double lat = Math.atan2(
                Z + ECCENTRICITY_SQUARED * SEMI_MINOR_AXIS * sinTheta * sinTheta * sinTheta,
                p - ECCENTRICITY_SQUARED * SEMI_MAJOR_AXIS * cosTheta * cosTheta * cosTheta
        );

        // Longitude calculation
        double lon = Math.atan2(Y, X);

        // Calculate prime vertical radius
        double sinLat = Math.sin(lat);
        double N = SEMI_MAJOR_AXIS / Math.sqrt(1.0 - ECCENTRICITY_SQUARED * sinLat * sinLat);

        // Height calculation
        double cosLat = Math.cos(lat);
        double height = p / cosLat - N;

        // Convert to degrees
        geodetic[0] = lat * RAD_TO_DEG;
        geodetic[1] = lon * RAD_TO_DEG;
        geodetic[2] = height;

        return geodetic;
    }

    /**
     * Converts ECEF delta coordinates to ENU coordinates.
     *
     * @param dX ECEF X delta in meters
     * @param dY ECEF Y delta in meters
     * @param dZ ECEF Z delta in meters
     * @param refLatitude Reference latitude in degrees
     * @param refLongitude Reference longitude in degrees
     * @return ENU coordinates [East, North, Up] in meters
     */
    public static double[] ecef2Enu(double dX, double dY, double dZ,
                                    double refLatitude, double refLongitude) {
        // Get or create transformation matrices
        TransformationMatrices matrices = getTransformationMatrices(refLatitude, refLongitude);

        // Apply rotation matrix
        double[] enu = new double[3];
        enu[0] = matrices.rotationMatrix[0][0] * dX + matrices.rotationMatrix[0][1] * dY + matrices.rotationMatrix[0][2] * dZ;
        enu[1] = matrices.rotationMatrix[1][0] * dX + matrices.rotationMatrix[1][1] * dY + matrices.rotationMatrix[1][2] * dZ;
        enu[2] = matrices.rotationMatrix[2][0] * dX + matrices.rotationMatrix[2][1] * dY + matrices.rotationMatrix[2][2] * dZ;

        return enu;
    }

    /**
     * Converts ENU coordinates to ECEF delta coordinates.
     *
     * @param east East coordinate in meters
     * @param north North coordinate in meters
     * @param up Up coordinate in meters
     * @param refLatitude Reference latitude in degrees
     * @param refLongitude Reference longitude in degrees
     * @return ECEF delta coordinates [dX, dY, dZ] in meters
     */
    public static double[] enu2EcefDelta(double east, double north, double up,
                                         double refLatitude, double refLongitude) {
        // Get or create transformation matrices
        TransformationMatrices matrices = getTransformationMatrices(refLatitude, refLongitude);

        // Apply inverse rotation matrix
        double[] ecefDelta = new double[3];
        ecefDelta[0] = matrices.inverseMatrix[0][0] * east + matrices.inverseMatrix[0][1] * north + matrices.inverseMatrix[0][2] * up;
        ecefDelta[1] = matrices.inverseMatrix[1][0] * east + matrices.inverseMatrix[1][1] * north + matrices.inverseMatrix[1][2] * up;
        ecefDelta[2] = matrices.inverseMatrix[2][0] * east + matrices.inverseMatrix[2][1] * north + matrices.inverseMatrix[2][2] * up;

        return ecefDelta;
    }

    /**
     * Gets cached transformation matrices or creates new ones for the given reference point.
     * This optimization avoids recalculating matrices for the same reference point.
     *
     * @param refLatitude Reference latitude in degrees
     * @param refLongitude Reference longitude in degrees
     * @return TransformationMatrices object containing rotation and inverse matrices
     */
    private static TransformationMatrices getTransformationMatrices(double refLatitude, double refLongitude) {
        // Create key from reference coordinates
        int key = hashRefPoint(refLatitude, refLongitude);

        // Check if matrices are in cache
        TransformationMatrices matrices = transformationCache.get(key);
        if (matrices != null) {
            return matrices;
        }

        // Create new matrices if not in cache
        matrices = new TransformationMatrices();

        // Convert to radians for calculations
        double latRad = refLatitude * DEG_TO_RAD;
        double lonRad = refLongitude * DEG_TO_RAD;

        // Precalculate trigonometric values
        double sinLat = Math.sin(latRad);
        double cosLat = Math.cos(latRad);
        double sinLon = Math.sin(lonRad);
        double cosLon = Math.cos(lonRad);

        // Rotation matrix from ECEF to ENU
        matrices.rotationMatrix = new double[3][3];

        // R = [-sinLon, cosLon, 0;
        //      -sinLat*cosLon, -sinLat*sinLon, cosLat;
        //       cosLat*cosLon, cosLat*sinLon, sinLat]
        matrices.rotationMatrix[0][0] = -sinLon;
        matrices.rotationMatrix[0][1] = cosLon;
        matrices.rotationMatrix[0][2] = 0.0;

        matrices.rotationMatrix[1][0] = -sinLat * cosLon;
        matrices.rotationMatrix[1][1] = -sinLat * sinLon;
        matrices.rotationMatrix[1][2] = cosLat;

        matrices.rotationMatrix[2][0] = cosLat * cosLon;
        matrices.rotationMatrix[2][1] = cosLat * sinLon;
        matrices.rotationMatrix[2][2] = sinLat;

        // Calculate inverse matrix (transpose of rotation matrix)
        matrices.inverseMatrix = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                matrices.inverseMatrix[i][j] = matrices.rotationMatrix[j][i];
            }
        }

        // Manage cache size
        if (transformationCache.size() >= MAX_CACHE_SIZE) {
            // Remove a random entry if cache is full
            Integer keyToRemove = transformationCache.keySet().iterator().next();
            transformationCache.remove(keyToRemove);
        }

        // Add to cache
        transformationCache.put(key, matrices);

        return matrices;
    }

    /**
     * Creates a hash code for reference point caching.
     *
     * @param lat Latitude in degrees
     * @param lon Longitude in degrees
     * @return Hash code for reference point
     */
    private static int hashRefPoint(double lat, double lon) {
        // Round to 5 decimal places (approx. 1.1 meters precision)
        int latInt = (int)(lat * 100000);
        int lonInt = (int)(lon * 100000);
        return 31 * latInt + lonInt;
    }

    /**
     * Specialized method for positioning fusion:
     * Calculates distance between two points in the ENU coordinate system.
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

    /**
     * Specialized method for positioning fusion:
     * Calculates bearing between two points in the ENU coordinate system.
     *
     * @param east1 East coordinate of point 1 in meters
     * @param north1 North coordinate of point 1 in meters
     * @param east2 East coordinate of point 2 in meters
     * @param north2 North coordinate of point 2 in meters
     * @return Bearing in degrees (0-360, 0 = North, 90 = East)
     */
    public static double enuBearing(double east1, double north1, double east2, double north2) {
        double dE = east2 - east1;
        double dN = north2 - north1;

        // Bearing in radians (0 = East in atan2)
        double bearingRad = Math.atan2(dN, dE);

        // Convert to degrees, rotate so 0 = North, 90 = East
        double bearingDeg = 90.0 - (bearingRad * RAD_TO_DEG);

        // Normalize to 0-360
        if (bearingDeg < 0) {
            bearingDeg += 360.0;
        }

        return bearingDeg;
    }

    /**
     * Utility method for EKF: Convert position uncertainty from ENU to WGS84.
     * This helps with appropriate error modeling in the fusion algorithm.
     *
     * @param eastingError Standard deviation in easting (meters)
     * @param northingError Standard deviation in northing (meters)
     * @param latitude Latitude in degrees
     * @return Array with [latError, lonError] in degrees
     */
    public static double[] enuErrorToGeodetic(double eastingError, double northingError, double latitude) {
        // Approximate conversion factors at the given latitude
        double latRad = latitude * DEG_TO_RAD;
        double metersPerDegreeLat = 111132.92 - 559.82 * Math.cos(2 * latRad) + 1.175 * Math.cos(4 * latRad);
        double metersPerDegreeLon = 111412.84 * Math.cos(latRad) - 93.5 * Math.cos(3 * latRad);

        // Convert to degrees
        double latError = northingError / metersPerDegreeLat;
        double lonError = eastingError / metersPerDegreeLon;

        return new double[] {latError, lonError};
    }

    /**
     * A class to hold rotation matrices used in coordinate transformations.
     * Cached for efficiency on repeated transformations.
     */
    private static class TransformationMatrices {
        public double[][] rotationMatrix;    // ECEF to ENU rotation matrix
        public double[][] inverseMatrix;     // ENU to ECEF rotation matrix
    }
}