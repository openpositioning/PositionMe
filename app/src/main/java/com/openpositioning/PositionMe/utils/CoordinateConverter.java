package com.openpositioning.PositionMe.utils;

import android.util.Log;
import com.google.android.gms.maps.model.LatLng;

/**
 * Provides utility functions for converting coordinates between different
 * reference systems: Geodetic (WGS84 Latitude, Longitude, Height),
 * ECEF (Earth-Centered, Earth-Fixed), and ENU (East, North, Up).
 *
 * <p>This implementation is based on standard geodetic formulas and ensures
 * consistency across transformations. All angles used as input parameters are expected
 * in degrees, while internal calculations often use radians.</p>
 *
 * @author Michal Wiercigroch
 */
public final class CoordinateConverter { // Added final as it's a utility class with only static members

    // Logger Tag
    private static final String TAG = CoordinateConverter.class.getSimpleName();
    // Flag to enable/disable detailed logging for debugging
    private static final boolean ENABLE_DEBUG_LOGGING = true; // Set to false for release builds

    // --- WGS84 Ellipsoid Constants ---
    private static final double WGS84_SEMI_MAJOR_AXIS = 6378137.0;          // a, semi-major axis (meters)
    private static final double WGS84_SEMI_MINOR_AXIS = 6356752.31424;      // b, semi-minor axis (meters)
    private static final double WGS84_FLATTENING = (WGS84_SEMI_MAJOR_AXIS - WGS84_SEMI_MINOR_AXIS) / WGS84_SEMI_MAJOR_AXIS; // f, flattening
    private static final double WGS84_ECCENTRICITY_SQUARED = WGS84_FLATTENING * (2.0 - WGS84_FLATTENING); // e^2, first eccentricity squared
    // e'^2 = (a^2 - b^2) / b^2, second eccentricity squared (often used in ECEF to Geodetic)
    private static final double WGS84_ECCENTRICITY_SQUARED_PRIME = (WGS84_SEMI_MAJOR_AXIS * WGS84_SEMI_MAJOR_AXIS - WGS84_SEMI_MINOR_AXIS * WGS84_SEMI_MINOR_AXIS) / (WGS84_SEMI_MINOR_AXIS * WGS84_SEMI_MINOR_AXIS);

    // --- Angular Conversion Factors ---
    private static final double DEGREES_TO_RADIANS = Math.PI / 180.0;
    private static final double RADIANS_TO_DEGREES = 180.0 / Math.PI;

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private CoordinateConverter() {
        throw new UnsupportedOperationException("CoordinateConverter is a utility class and cannot be instantiated.");
    }

    // --- Geodetic <-> ECEF Conversions ---

    /**
     * Converts Geodetic coordinates (latitude, longitude, height) to
     * ECEF (Earth-Centered, Earth-Fixed) coordinates.
     *
     * @param latitudeDegrees  Latitude in decimal degrees.
     * @param longitudeDegrees Longitude in decimal degrees.
     * @param heightMeters     Height above the WGS84 ellipsoid in meters.
     * @return A double array containing ECEF coordinates [X, Y, Z] in meters.
     */
    public static double[] convertGeodeticToEcef(final double latitudeDegrees, final double longitudeDegrees, final double heightMeters) {
        final double latRad = latitudeDegrees * DEGREES_TO_RADIANS;
        final double lonRad = longitudeDegrees * DEGREES_TO_RADIANS;
        final double cosLat = Math.cos(latRad);
        final double sinLat = Math.sin(latRad);
        final double cosLon = Math.cos(lonRad);
        final double sinLon = Math.sin(lonRad);

        // Calculate the prime vertical radius of curvature (N)
        final double N = WGS84_SEMI_MAJOR_AXIS / Math.sqrt(1.0 - WGS84_ECCENTRICITY_SQUARED * sinLat * sinLat);

        // Calculate ECEF coordinates
        final double[] ecef = new double[3];
        ecef[0] = (N + heightMeters) * cosLat * cosLon; // X (meters)
        ecef[1] = (N + heightMeters) * cosLat * sinLon; // Y (meters)
        ecef[2] = (N * (1.0 - WGS84_ECCENTRICITY_SQUARED) + heightMeters) * sinLat; // Z (meters)

        if (ENABLE_DEBUG_LOGGING) {
            Log.d(TAG, String.format("convertGeodeticToEcef: (Lat=%.6f°, Lon=%.6f°, H=%.2fm) -> (X=%.3fm, Y=%.3fm, Z=%.3fm)",
                    latitudeDegrees, longitudeDegrees, heightMeters, ecef[0], ecef[1], ecef[2]));
        }

        return ecef;
    }

    /**
     * Converts ECEF (Earth-Centered, Earth-Fixed) coordinates to
     * Geodetic coordinates (latitude, longitude, height).
     * Uses Bowring's iterative method (approximated here) for improved accuracy, especially near poles.
     *
     * @param ecefX ECEF X coordinate in meters.
     * @param ecefY ECEF Y coordinate in meters.
     * @param ecefZ ECEF Z coordinate in meters.
     * @return A double array containing Geodetic coordinates [latitude (deg), longitude (deg), height (m)].
     */
    public static double[] convertEcefToGeodetic(final double ecefX, final double ecefY, final double ecefZ) {
        final double a = WGS84_SEMI_MAJOR_AXIS;
        final double b = WGS84_SEMI_MINOR_AXIS;
        final double eSquared = WGS84_ECCENTRICITY_SQUARED;
        final double ePrimeSquared = WGS84_ECCENTRICITY_SQUARED_PRIME; // (a^2 - b^2) / b^2

        // Calculate distance from Z-axis (projection onto equatorial plane)
        final double p = Math.sqrt(ecefX * ecefX + ecefY * ecefY);

        // Calculate longitude (atan2 ensures correct quadrant)
        final double longitudeRad = Math.atan2(ecefY, ecefX);

        // Bowring's method for latitude (iterative, but this is a common first approximation)
        final double theta = Math.atan2(ecefZ * a, p * b); // Angle for parametric latitude approximation
        final double sinTheta = Math.sin(theta);
        final double cosTheta = Math.cos(theta);

        final double latitudeRad = Math.atan2(
                ecefZ + ePrimeSquared * b * sinTheta * sinTheta * sinTheta,
                p - eSquared * a * cosTheta * cosTheta * cosTheta
        );

        // Calculate height
        final double sinLat = Math.sin(latitudeRad);
        final double cosLat = Math.cos(latitudeRad);
        // Prime vertical radius of curvature
        final double N = a / Math.sqrt(1.0 - eSquared * sinLat * sinLat);
        final double heightMeters = (p / cosLat) - N;

        // Prepare result array
        final double[] geodetic = new double[3];
        geodetic[0] = latitudeRad * RADIANS_TO_DEGREES;   // Latitude (degrees)
        geodetic[1] = longitudeRad * RADIANS_TO_DEGREES;  // Longitude (degrees)
        geodetic[2] = heightMeters;                       // Height (meters)

        if (ENABLE_DEBUG_LOGGING) {
            Log.d(TAG, String.format("convertEcefToGeodetic: (X=%.3fm, Y=%.3fm, Z=%.3fm) -> (Lat=%.6f°, Lon=%.6f°, H=%.2fm)",
                    ecefX, ecefY, ecefZ, geodetic[0], geodetic[1], geodetic[2]));
        }

        return geodetic;
    }

    // --- ECEF Delta <-> ENU Conversions ---

    /**
     * Converts a delta in ECEF coordinates (dX, dY, dZ) to local ENU
     * (East, North, Up) coordinates relative to a reference point's latitude and longitude.
     * This transformation uses the rotation matrix based on the reference location.
     *
     * @param deltaX           Change in ECEF X coordinate in meters.
     * @param deltaY           Change in ECEF Y coordinate in meters.
     * @param deltaZ           Change in ECEF Z coordinate in meters.
     * @param refLatitudeDegrees Reference point latitude in decimal degrees.
     * @param refLongitudeDegrees Reference point longitude in decimal degrees.
     * @return A double array containing ENU coordinates [East (m), North (m), Up (m)].
     */
    public static double[] convertEcefDeltaToEnu(final double deltaX, final double deltaY, final double deltaZ,
                                                 final double refLatitudeDegrees, final double refLongitudeDegrees) {
        final double latRad = refLatitudeDegrees * DEGREES_TO_RADIANS;
        final double lonRad = refLongitudeDegrees * DEGREES_TO_RADIANS;
        final double cosLat = Math.cos(latRad);
        final double sinLat = Math.sin(latRad);
        final double cosLon = Math.cos(lonRad);
        final double sinLon = Math.sin(lonRad);

        // Rotation matrix (transposed) application:
        // [-sinLon         cosLon          0    ] [deltaX]
        // [-sinLat*cosLon -sinLat*sinLon  cosLat] [deltaY]
        // [ cosLat*cosLon  cosLat*sinLon  sinLat] [deltaZ]

        final double[] enu = new double[3];
        final double t = cosLon * deltaX + sinLon * deltaY; // Temporary variable for common dot product part

        enu[0] = -sinLon * deltaX + cosLon * deltaY;               // East (meters)
        enu[1] = -sinLat * t + cosLat * deltaZ;                   // North (meters)
        enu[2] = cosLat * t + sinLat * deltaZ;                    // Up (meters)

        if (ENABLE_DEBUG_LOGGING) {
            Log.d(TAG, String.format("convertEcefDeltaToEnu: (dX=%.3fm, dY=%.3fm, dZ=%.3fm) @ (RefLat=%.6f°, RefLon=%.6f°) -> (E=%.3fm, N=%.3fm, U=%.3fm)",
                    deltaX, deltaY, deltaZ, refLatitudeDegrees, refLongitudeDegrees, enu[0], enu[1], enu[2]));
        }

        return enu;
    }

    /**
     * Converts local ENU (East, North, Up) coordinates relative to a reference point
     * back to a delta in ECEF coordinates (dX, dY, dZ). This is the inverse
     * transformation of {@link #convertEcefDeltaToEnu}.
     *
     * @param eastMeters       East coordinate in meters.
     * @param northMeters      North coordinate in meters.
     * @param upMeters         Up coordinate in meters.
     * @param refLatitudeDegrees Reference point latitude in decimal degrees.
     * @param refLongitudeDegrees Reference point longitude in decimal degrees.
     * @return A double array containing ECEF delta coordinates [dX (m), dY (m), dZ (m)].
     */
    public static double[] convertEnuToEcefDelta(final double eastMeters, final double northMeters, final double upMeters,
                                                 final double refLatitudeDegrees, final double refLongitudeDegrees) {
        final double latRad = refLatitudeDegrees * DEGREES_TO_RADIANS;
        final double lonRad = refLongitudeDegrees * DEGREES_TO_RADIANS;
        final double cosLat = Math.cos(latRad);
        final double sinLat = Math.sin(latRad);
        final double cosLon = Math.cos(lonRad);
        final double sinLon = Math.sin(lonRad);

        // Inverse rotation matrix application:
        // [-sinLon -sinLat*cosLon  cosLat*cosLon] [East ]
        // [ cosLon -sinLat*sinLon  cosLat*sinLon] [North]
        // [ 0       cosLat         sinLat       ] [Up   ]

        final double[] ecefDelta = new double[3];
        ecefDelta[0] = -sinLon * eastMeters - sinLat * cosLon * northMeters + cosLat * cosLon * upMeters; // dX (meters)
        ecefDelta[1] = cosLon * eastMeters - sinLat * sinLon * northMeters + cosLat * sinLon * upMeters; // dY (meters)
        ecefDelta[2] = cosLat * northMeters + sinLat * upMeters;                                       // dZ (meters)

        if (ENABLE_DEBUG_LOGGING) {
            Log.d(TAG, String.format("convertEnuToEcefDelta: (E=%.3fm, N=%.3fm, U=%.3fm) @ (RefLat=%.6f°, RefLon=%.6f°) -> (dX=%.3fm, dY=%.3fm, dZ=%.3fm)",
                    eastMeters, northMeters, upMeters, refLatitudeDegrees, refLongitudeDegrees, ecefDelta[0], ecefDelta[1], ecefDelta[2]));
        }

        return ecefDelta;
    }

    // --- Composite Conversions ---

    /**
     * Converts Geodetic coordinates (latitude, longitude, height) to local ENU
     * (East, North, Up) coordinates relative to a reference Geodetic point.
     * This involves converting both points to ECEF, finding the ECEF difference,
     * and then rotating that difference vector into the ENU frame of the reference point.
     *
     * @param latitudeDegrees     Latitude of the point in decimal degrees.
     * @param longitudeDegrees    Longitude of the point in decimal degrees.
     * @param heightMeters        Height of the point above the ellipsoid in meters.
     * @param refLatitudeDegrees  Reference point latitude in decimal degrees.
     * @param refLongitudeDegrees Reference point longitude in decimal degrees.
     * @param refHeightMeters     Reference point height above the ellipsoid in meters.
     * @return A double array containing ENU coordinates [East (m), North (m), Up (m)].
     */
    public static double[] convertGeodeticToEnu(final double latitudeDegrees, final double longitudeDegrees, final double heightMeters,
                                                final double refLatitudeDegrees, final double refLongitudeDegrees, final double refHeightMeters) {
        // Step 1: Convert both points from Geodetic to ECEF
        final double[] pointEcef = convertGeodeticToEcef(latitudeDegrees, longitudeDegrees, heightMeters);
        final double[] referenceEcef = convertGeodeticToEcef(refLatitudeDegrees, refLongitudeDegrees, refHeightMeters);

        // Step 2: Calculate the difference vector in ECEF coordinates
        final double deltaX = pointEcef[0] - referenceEcef[0];
        final double deltaY = pointEcef[1] - referenceEcef[1];
        final double deltaZ = pointEcef[2] - referenceEcef[2];

        // Step 3: Convert the ECEF difference vector to ENU coordinates using the reference location
        final double[] enu = convertEcefDeltaToEnu(deltaX, deltaY, deltaZ, refLatitudeDegrees, refLongitudeDegrees);

        if (ENABLE_DEBUG_LOGGING) {
            Log.d(TAG, String.format("convertGeodeticToEnu: (Lat=%.6f°, Lon=%.6f°, H=%.2fm) relative to (RefLat=%.6f°, RefLon=%.6f°, RefH=%.2fm) -> (E=%.3fm, N=%.3fm, U=%.3fm)",
                    latitudeDegrees, longitudeDegrees, heightMeters, refLatitudeDegrees, refLongitudeDegrees, refHeightMeters, enu[0], enu[1], enu[2]));
        }

        return enu;
    }

    /**
     * Converts local ENU (East, North, Up) coordinates relative to a reference
     * Geodetic point back to Geodetic coordinates (latitude, longitude).
     * Note: The resulting height is calculated internally but discarded as {@link LatLng}
     * only stores latitude and longitude.
     *
     * @param eastMeters          East coordinate in meters.
     * @param northMeters         North coordinate in meters.
     * @param upMeters            Up coordinate in meters.
     * @param refLatitudeDegrees  Reference point latitude in decimal degrees.
     * @param refLongitudeDegrees Reference point longitude in decimal degrees.
     * @param refHeightMeters     Reference point height above the ellipsoid in meters.
     * @return A {@link LatLng} object containing the calculated latitude and longitude in degrees.
     *         Returns the reference LatLng wrapped in a new object on calculation error.
     */
    public static LatLng convertEnuToGeodetic(final double eastMeters, final double northMeters, final double upMeters,
                                              final double refLatitudeDegrees, final double refLongitudeDegrees, final double refHeightMeters) {
        final LatLng fallbackResult = new LatLng(refLatitudeDegrees, refLongitudeDegrees);
        try {
            // Step 1: Get ECEF coordinates of the reference point
            final double[] referenceEcef = convertGeodeticToEcef(refLatitudeDegrees, refLongitudeDegrees, refHeightMeters);

            // Step 2: Convert ENU coordinates to an ECEF delta vector relative to the reference
            final double[] ecefDelta = convertEnuToEcefDelta(eastMeters, northMeters, upMeters, refLatitudeDegrees, refLongitudeDegrees);

            // Step 3: Add the ECEF delta to the reference ECEF coordinates to get the target point's ECEF coordinates
            final double[] pointEcef = new double[3];
            pointEcef[0] = referenceEcef[0] + ecefDelta[0];
            pointEcef[1] = referenceEcef[1] + ecefDelta[1];
            pointEcef[2] = referenceEcef[2] + ecefDelta[2];

            // Step 4: Convert the absolute ECEF coordinates back to Geodetic coordinates
            final double[] geodetic = convertEcefToGeodetic(pointEcef[0], pointEcef[1], pointEcef[2]);

            // Step 5: Create LatLng object (height is available in geodetic[2] if needed elsewhere)
            final LatLng result = new LatLng(geodetic[0], geodetic[1]);

            if (ENABLE_DEBUG_LOGGING) {
                Log.d(TAG, String.format("convertEnuToGeodetic: (E=%.3fm, N=%.3fm, U=%.3fm) relative to (RefLat=%.6f°, RefLon=%.6f°, RefH=%.2fm) -> (Lat=%.6f°, Lon=%.6f°)",
                        eastMeters, northMeters, upMeters, refLatitudeDegrees, refLongitudeDegrees, refHeightMeters, result.latitude, result.longitude));
            }

            return result;

        } catch (Exception e) {
            Log.e(TAG, "Error in convertEnuToGeodetic: " + e.getMessage(), e);
            // Return a new instance of the reference location as a fallback
            return fallbackResult;
        }
    }

    // --- Distance and Bearing Calculations ---

    /**
     * Calculates the great-circle distance between two points on the Earth's surface
     * using the Haversine formula. Assumes a spherical Earth using the WGS84 semi-major axis
     * as the radius for simplicity. For higher accuracy over long distances, consider
     * Vincenty's formulae on an ellipsoid.
     *
     * @param lat1Degrees Latitude of the first point in decimal degrees.
     * @param lon1Degrees Longitude of the first point in decimal degrees.
     * @param lat2Degrees Latitude of the second point in decimal degrees.
     * @param lon2Degrees Longitude of the second point in decimal degrees.
     * @return The approximate distance between the two points in meters.
     */
    public static double calculateHaversineDistance(final double lat1Degrees, final double lon1Degrees,
                                                    final double lat2Degrees, final double lon2Degrees) {
        final double lat1Rad = lat1Degrees * DEGREES_TO_RADIANS;
        final double lon1Rad = lon1Degrees * DEGREES_TO_RADIANS;
        final double lat2Rad = lat2Degrees * DEGREES_TO_RADIANS;
        final double lon2Rad = lon2Degrees * DEGREES_TO_RADIANS;

        final double deltaLat = lat2Rad - lat1Rad;
        final double deltaLon = lon2Rad - lon1Rad;

        // Haversine formula
        final double a = Math.sin(deltaLat / 2.0) * Math.sin(deltaLat / 2.0) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                        Math.sin(deltaLon / 2.0) * Math.sin(deltaLon / 2.0);
        final double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));

        // Distance = radius * central angle (using semi-major axis as radius)
        return WGS84_SEMI_MAJOR_AXIS * c;
    }

    /**
     * Calculates the Euclidean distance between two points in a 2D ENU plane (East, North).
     * Ignores the 'Up' component.
     *
     * @param east1Meters East coordinate of the first point in meters.
     * @param north1Meters North coordinate of the first point in meters.
     * @param east2Meters East coordinate of the second point in meters.
     * @param north2Meters North coordinate of the second point in meters.
     * @return The 2D Euclidean distance between the points in meters.
     */
    public static double calculateEnuDistance(final double east1Meters, final double north1Meters,
                                              final double east2Meters, final double north2Meters) {
        final double deltaEast = east2Meters - east1Meters;
        final double deltaNorth = north2Meters - north1Meters;
        return Math.sqrt(deltaEast * deltaEast + deltaNorth * deltaNorth);
    }

    /**
     * Calculates the bearing (azimuth) from the first ENU point to the second ENU point.
     * The bearing is measured clockwise from North (0 degrees).
     *
     * @param east1Meters East coordinate of the starting point in meters.
     * @param north1Meters North coordinate of the starting point in meters.
     * @param east2Meters East coordinate of the destination point in meters.
     * @param north2Meters North coordinate of the destination point in meters.
     * @return The bearing in degrees, ranging from [0, 360). 0° is North, 90° is East, 180° is South, 270° is West.
     */
    public static double calculateEnuBearing(final double east1Meters, final double north1Meters,
                                             final double east2Meters, final double north2Meters) {
        final double deltaEast = east2Meters - east1Meters;   // Corresponds to 'x' in atan2(y, x)
        final double deltaNorth = north2Meters - north1Meters; // Corresponds to 'y' in atan2(y, x)

        // Calculate angle using atan2(y, x), which is atan2(deltaNorth, deltaEast)
        // Result is in radians, measured counter-clockwise from the East axis (+X).
        final double angleRadFromEast = Math.atan2(deltaNorth, deltaEast);

        // Convert to degrees
        final double angleDegFromEast = angleRadFromEast * RADIANS_TO_DEGREES;

        // Convert angle from "math angle" (0° East, CCW) to "bearing" (0° North, CW)
        // Bearing = 90 - Math Angle
        double bearingDeg = 90.0 - angleDegFromEast;

        // Normalize bearing to be within [0, 360) degrees
        // Equivalent to: bearingDeg = (bearingDeg % 360.0 + 360.0) % 360.0;
        if (bearingDeg < 0) {
            bearingDeg += 360.0;
        }
        if (bearingDeg >= 360.0) { // Handle exact multiples of 360 becoming 0
            bearingDeg -= 360.0;
        }


        return bearingDeg;
    }

    // --- Angle Utilities ---

    /**
     * Normalizes an angle in radians to the range [-PI, PI].
     *
     * @param angleRadians The angle in radians.
     * @return The equivalent angle normalized to the range [-PI, PI].
     */
    public static double normalizeAngleToPi(double angleRadians) { // Keep mutable param name for loop
        // More efficient for angles close to the range than modulo
        while (angleRadians > Math.PI) {
            angleRadians -= 2.0 * Math.PI;
        }
        while (angleRadians <= -Math.PI) { // Use <= to include -PI if it maps exactly
            angleRadians += 2.0 * Math.PI;
        }
        return angleRadians;
        /* Alternative using modulo (careful with negative results):
           angleRadians = angleRadians % (2.0 * Math.PI);
           if (angleRadians > Math.PI) {
               angleRadians -= 2.0 * Math.PI;
           } else if (angleRadians <= -Math.PI) { // Ensure consistency with while loop version
               angleRadians += 2.0 * Math.PI;
           }
           return angleRadians;
        */
    }

    /**
     * Normalizes an angle in radians to the range [0, 2*PI).
     *
     * @param angleRadians The angle in radians.
     * @return The equivalent angle normalized to the range [0, 2*PI).
     */
    public static double normalizeAngleTo2Pi(double angleRadians) { // Keep mutable param name for loop/modulo
        // Modulo is generally efficient here
        angleRadians = angleRadians % (2.0 * Math.PI);
        if (angleRadians < 0) {
            angleRadians += 2.0 * Math.PI;
        }
        // Ensure result is strictly less than 2*PI if input is a multiple of 2*PI
        // Although modulo usually handles this, floating point might leave exactly 2*PI
        if (angleRadians >= 2.0 * Math.PI) {
            angleRadians = 0.0; // or angleRadians -= 2.0 * Math.PI;
        }
        return angleRadians;
        /* While loop alternative:
           while (angleRadians >= 2.0 * Math.PI) {
               angleRadians -= 2.0 * Math.PI;
           }
           while (angleRadians < 0) {
               angleRadians += 2.0 * Math.PI;
           }
           return angleRadians;
        */
    }
}