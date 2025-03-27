package com.openpositioning.PositionMe.utils;

import android.util.Log;
import com.google.android.gms.maps.model.LatLng;

/**
 * Utility class for simple fusion of PDR and GNSS positions.
 * Uses exact coordinate transformation logic as in CoordinateTransform.
 */
public class SimpleFusionConverter {

    // Constants (same as in CoordinateTransform)
    public static final double SEMI_MAJOR_AXIS = 6378137.0;
    public static final double SEMI_MINOR_AXIS = 6356752.31424518;
    public static final double FLATTENING = (SEMI_MAJOR_AXIS-SEMI_MINOR_AXIS)/SEMI_MAJOR_AXIS;
    public static final double ECCENTRICITY_SQUARED = FLATTENING * (2-FLATTENING);

    /**
     * Convert PDR position (ENU) to geodetic coordinates (LatLng) and average with GNSS
     * @param pdrEast PDR East position in meters
     * @param pdrNorth PDR North position in meters
     * @param pdrUp PDR Up position in meters
     * @param gnssLat GNSS latitude
     * @param gnssLng GNSS longitude
     * @param refLat Reference latitude
     * @param refLng Reference longitude
     * @param refAlt Reference altitude
     * @return The averaged position as LatLng
     */
    public static LatLng fusePdrAndGnss(double pdrEast, double pdrNorth, double pdrUp,
                                        double gnssLat, double gnssLng,
                                        double refLat, double refLng, double refAlt) {

        // First convert PDR position (ENU) to LatLng using exact conversion logic
        LatLng pdrLatLng = enuToGeodetic(pdrEast, pdrNorth, pdrUp, refLat, refLng, refAlt);

        // Log all coordinates for debugging
        Log.d("SimpleFusion", "Reference: " + refLat + ", " + refLng + ", Alt: " + refAlt);
        Log.d("SimpleFusion", "PDR ENU: " + pdrEast + ", " + pdrNorth + ", " + pdrUp);
        Log.d("SimpleFusion", "PDR LatLng: " + pdrLatLng.latitude + ", " + pdrLatLng.longitude);
        Log.d("SimpleFusion", "GNSS: " + gnssLat + ", " + gnssLng);

        // Simple average of coordinates
        double avgLat = (pdrLatLng.latitude + gnssLat) / 2.0;
        double avgLng = (pdrLatLng.longitude + gnssLng) / 2.0;
        LatLng fusedPosition = new LatLng(avgLat, avgLng);

        Log.d("SimpleFusion", "Fused Position: " + fusedPosition.latitude + ", " + fusedPosition.longitude);
        return fusedPosition;
    }

    /**
     * Converts WSG84 coordinates to Earth-Centered, Earth-Fixed (ECEF) coordinates.
     * Exact logic from CoordinateTransform.
     */
    public static double[] geodeticToEcef(double latitude, double longitude, double altitude) {
        double[] ecefCoords = new double[3];
        double latRad = Math.toRadians(latitude);
        double lngRad = Math.toRadians(longitude);

        //Calculate Prime Vertical Radius of Curvature
        double N = Math.pow(SEMI_MAJOR_AXIS,2) /
                Math.hypot((SEMI_MAJOR_AXIS*Math.cos(latRad)), (SEMI_MINOR_AXIS * Math.sin(latRad)));

        ecefCoords[0] = (N + altitude) * Math.cos(latRad) * Math.cos(lngRad);
        ecefCoords[1] = (N + altitude) * Math.cos(latRad) * Math.sin(lngRad);
        ecefCoords[2] = (N * Math.pow((SEMI_MINOR_AXIS / SEMI_MAJOR_AXIS),2) + altitude) * Math.sin(latRad);

        return ecefCoords;
    }

    /**
     * Converts Earth-Centered, Earth-Fixed (ECEF) delta coordinates to East-North-Up (ENU) coordinates.
     * Exact logic from CoordinateTransform.
     */
    public static double[] ecefToENU(double east, double north, double up, double refLatitude, double refLongitude) {
        double[] enuCoords = new double[3];
        double latRad = Math.toRadians(refLatitude);
        double lngRad = Math.toRadians(refLongitude);

        double t = Math.cos(lngRad) * east + Math.sin(lngRad) * north;
        enuCoords[0] = -Math.sin(lngRad) * east + Math.cos(lngRad) * north;
        enuCoords[2] = Math.cos(latRad)*t + Math.sin(latRad) * up;
        enuCoords[1] = -Math.sin(latRad) *t + Math.cos(latRad) * up;

        return enuCoords;
    }

    /**
     * Converts ENU coordinates to ECEF coordinates.
     * Exact logic from CoordinateTransform.
     */
    public static double[] enuToEcef(double east, double north, double up, double refLatitude, double refLongitude, double refAlt) {
        double[] calCoords = new double[3];
        double[] ecefRefCoords = geodeticToEcef(refLatitude, refLongitude, refAlt);
        double latRad = Math.toRadians(refLatitude);
        double lngRad = Math.toRadians(refLongitude);

        calCoords[0] = (Math.cos(lngRad) * (Math.cos(latRad)*up - Math.sin(latRad)*north) - Math.sin(lngRad)*east) + ecefRefCoords[0];
        calCoords[1] = (Math.sin(lngRad)*(Math.cos(latRad)*up - Math.sin(latRad)*north) + Math.cos(lngRad)*east) + ecefRefCoords[1];
        calCoords[2] = (Math.sin(latRad)*up + Math.cos(latRad)*north) + ecefRefCoords[2];

        return calCoords;
    }

    /**
     * Converts ECEF coordinates to geodetic coordinates.
     * Exact logic from CoordinateTransform.
     */
    public static LatLng ecefToGeodetic(double[] ecefCoords) {
        double asq = Math.pow(SEMI_MAJOR_AXIS,2);
        double bsq = Math.pow(SEMI_MINOR_AXIS,2);

        double ep = Math.sqrt((asq-bsq)/bsq);

        double p = Math.sqrt(Math.pow(ecefCoords[0],2) + Math.pow(ecefCoords[1],2));

        double th = Math.atan2(SEMI_MAJOR_AXIS * ecefCoords[2], SEMI_MINOR_AXIS * p);

        double longitude = Math.atan2(ecefCoords[1], ecefCoords[0]);

        double latitude = Math.atan2((ecefCoords[2] + Math.pow(ep,2) *
                        SEMI_MINOR_AXIS * Math.pow(Math.sin(th),3)),
                (p - ECCENTRICITY_SQUARED*SEMI_MAJOR_AXIS*Math.pow(Math.cos(th),3)));

        double N = SEMI_MAJOR_AXIS/
                (Math.sqrt(1-ECCENTRICITY_SQUARED*
                        Math.pow(Math.sin(latitude),2)));

        double altitude = p / Math.cos(latitude) - N;

        longitude = longitude % (2*Math.PI);

        return new LatLng(toDegrees(latitude), toDegrees(longitude));
    }

    /**
     * Converts ENU coordinates to geodetic (LatLng).
     * Exact logic from CoordinateTransform.
     */
    public static LatLng enuToGeodetic(double east, double north, double up, double refLatitude, double refLongitude, double refAlt) {
        double[] ecefCoords = enuToEcef(east, north, up, refLatitude, refLongitude, refAlt);

        // Debug log the ECEF coordinates
        Log.d("SimpleFusion", "ECEF Coords: x=" + ecefCoords[0] + ", y=" + ecefCoords[1] + ", z=" + ecefCoords[2]);

        return ecefToGeodetic(ecefCoords);
    }

    /**
     * Converts geodetic coordinates to ENU.
     * Exact logic from CoordinateTransform.
     */
    public static double[] geodeticToEnu(double latitude, double longitude, double altitude,
                                         double refLatitude, double refLongitude, double refAltitude) {
        double[] newPosition = geodeticToEcef(latitude, longitude, altitude);
        double[] ecefRefCoords = geodeticToEcef(refLatitude, refLongitude, refAltitude);

        return ecefToENU((newPosition[0]-ecefRefCoords[0]),
                (newPosition[1]-ecefRefCoords[1]),
                (newPosition[2]-ecefRefCoords[2]),
                refLatitude, refLongitude);
    }

    /**
     * Helper method to convert radians to degrees.
     */
    public static double toDegrees(double val) {
        return val * (180/Math.PI);
    }
}