package com.openpositioning.PositionMe.utils;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;

/**
 * A utility class used to convert the PDR coordinates which are relative East North Up (ENU) on a tangent plane to the earths surface to
 * WSG84 coordinates that google maps uses. This allows the relative coordinates to be accurately transformed into longitude and latitude
 * coordinates used by google maps. Largely adapted from the pymap3D library.
 *
 * @author Yueyan Zhao
 * @author Zizhen Wang
 * @author Chen Zhao
 */
public final class CoordinateTransform {

    //Constants used in calculations
    public static final double semimajor_axis = 6378137.0;
    public static final double semiminor_axis = 6356752.31424518;
    public static final double flattening = (semimajor_axis-semiminor_axis)/semimajor_axis;
    public static final double eccentricity_squared = flattening * (2-flattening);

    /**
     * An empty constructor as this is a utility class no constructor is needed.
     */
    private CoordinateTransform(){
    }

    /**
     * Converts WSG84 coordinates to Earth-Centered, Earth-Fixed (ECEF) coordinates.
     *
     * @param latitude  The latitude of the coordinate to convert.
     * @param longitude The longitude of the coordinate to convert.
     * @param altitude  The altitude of the coordinate to convert.
     * @return The converted ECEF coordinates as a double array with X, Y, and Z coordinates.
     */
    public static double[] geodeticToEcef(double latitude, double longitude, double altitude){
        double[] ecefCoords = new double[3];
        double latRad = Math.toRadians(latitude);
        double lngRad = Math.toRadians(longitude);

        //Calculate Prime Vertical Radius of Curvature
        double N = Math.pow(semimajor_axis,2) /
                Math.hypot((semimajor_axis*Math.cos(latRad)), (semiminor_axis * Math.sin(latRad)));

        ecefCoords[0] = (N + altitude) * Math.cos(latRad) * Math.cos(lngRad);
        ecefCoords[1] = (N + altitude) * Math.cos(latRad) * Math.sin(lngRad);
        ecefCoords[2] = (N * Math.pow((semiminor_axis / semimajor_axis),2) + altitude) * Math.sin(latRad);

        return ecefCoords;
    }

    /**
     * Converts Earth-Centered, Earth-Fixed (ECEF) coordinates to East-North-Up (ENU) coordinates
     * relative to a reference point specified by latitude and longitude.
     *
     * @param east         The east displacement in meters.
     * @param north        The north displacement in meters.
     * @param up           The altitude displacement in meters.
     * @param refLatitude  The reference point latitude.
     * @param refLongitude The reference point longitude.
     * @return A double array containing the East, North, and Up displacements in meters.
     */
    public static double[] ecefToENU(double east, double north, double up, double refLatitude, double refLongitude){
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
     * Converts ENU coordinates to ECEF coordinates from a reference point which is the users start location.
     * The reference ECEF coordinates are calculated from the WSG84 coordinates.
     *
     * @param east The east displacement in meters
     * @param north The north displacement in meters
     * @param up The altitude in meters
     * @param refLatitude The reference point latitude
     * @param refLongitude the reference point longitude
     * @param refAlt the reference point altitude
     * @return a double array with the X, Y and Z coordinates
     */
    public static double[] enuToEcef(double east, double north, double up, double refLatitude, double refLongitude, double refAlt){
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
     * An overloaded method the same as the previous, but with the ECEF reference coordinates already calculated
     */
    public static double[] enuToEcef(double east, double north, double up, double refLatitude, double refLongitude, double[] ecefRefCoords){
        double[] calCoords = new double[3];
        double latRad = Math.toRadians(refLatitude);
        double lngRad = Math.toRadians(refLongitude);

        calCoords[0] = (Math.cos(lngRad) * (Math.cos(latRad)*up - Math.sin(latRad)*north) - Math.sin(lngRad)*east) + ecefRefCoords[0];
        calCoords[1] = (Math.sin(lngRad)*(Math.cos(latRad)*up - Math.sin(latRad)*north) + Math.cos(lngRad)*east) + ecefRefCoords[1];
        calCoords[2] = (Math.sin(latRad)*up + Math.cos(latRad)*north) + ecefRefCoords[2];

        return calCoords;
    }

    /**
     * Converts the ECEF coordinates to WSG84 coordinates.
     * @param ecefCoords The ECEF X, Y and Z coordinates
     * @return the WSG84 coordiantes as a LatLng
     */
    public static LatLng ecefToGeodetic(double[] ecefCoords) {

        double asq = Math.pow(semimajor_axis,2);
        double bsq = Math.pow(semiminor_axis,2);

        double ep = Math.sqrt((asq-bsq)/bsq);

        double p = Math.sqrt(Math.pow(ecefCoords[0],2) + Math.pow(ecefCoords[1],2));

        double th = Math.atan2(semimajor_axis *
                ecefCoords[2], semiminor_axis * p);

        double longitude = Math.atan2(ecefCoords[1],ecefCoords[0]);

        double latitude = Math.atan2((ecefCoords[2] + Math.pow(ep,2) *
                        semiminor_axis * Math.pow(Math.sin(th),3)),
                (p - eccentricity_squared*semimajor_axis*Math.pow(Math.cos(th),3)));

        double N = semimajor_axis/
                (Math.sqrt(1-eccentricity_squared*
                        Math.pow(Math.sin(latitude),2)));

        double altitude = p / Math.cos(latitude) - N;
        Log.d("UserLocation", "alt: "+altitude);

        longitude = longitude % (2*Math.PI);

        return new LatLng(toDegrees(latitude), toDegrees(longitude));
    }

    /**
     * Converts the ENU coordiantes to WSG84 coordiantes.
     * First the ENU coordinates have to be converted to ECEF and then they can be converted to WSG84.
     * @param east The east displacement in meters
     * @param north The north displacement in meters
     * @param up The altitude in meters
     * @param refLatitude The reference point latitude
     *  @param refLongitude the reference point longitude
     * @param ecefRefCoords the ECEF reference coordinates
     * @return LatLng of the converted coordinates.
     */
    public static LatLng enuToGeodetic(double east, double north, double up, double refLatitude, double refLongitude, double[] ecefRefCoords) {
        double[] ecefCoords = enuToEcef(east, north, up, refLatitude, refLongitude, ecefRefCoords);

        return ecefToGeodetic(ecefCoords);
    }

    /**
     * An overloadden method similar to the previous, but the reference coordinates have not been calculated. They will be
     * calculated.
     */
    public static LatLng enuToGeodetic(double east, double north, double up, double refLatitude, double refLongitude, double refAlt) {
        double[] ecefCoords = enuToEcef(east, north, up, refLatitude, refLongitude, refAlt);
        Log.d("ECEFCOORDS", "x: "+ecefCoords[0]+" y: "+ecefCoords[1]+" z: "+ecefCoords[2]);

        return ecefToGeodetic(ecefCoords);
    }

    /**
     * Converts geodetic coordinates (latitude, longitude, altitude) to East-North-Up (ENU) coordinates
     * relative to a reference point specified by its geodetic coordinates.
     *
     * @param latitude      The latitude of the point to convert.
     * @param longitude     The longitude of the point to convert.
     * @param altitude      The altitude of the point to convert.
     * @param refLatitude   The latitude of the reference point.
     * @param refLongitude  The longitude of the reference point.
     * @param refAltitude   The altitude of the reference point.
     * @return A double array containing the East, North, and Up displacements in meters.
     */
    public static double[] geodeticToEnu(double latitude, double longitude, double altitude, double refLatitude, double refLongitude, double refAltitude){
        double[] newPosition = geodeticToEcef(latitude, longitude, altitude);
        double[] ecefRefCoords = geodeticToEcef(refLatitude, refLongitude, refAltitude);

        return ecefToENU((newPosition[0]-ecefRefCoords[0]), (newPosition[1]-ecefRefCoords[1]), (newPosition[2]-ecefRefCoords[2]), refLatitude, refLongitude);
    }

    /**
     * Helper method to convert radians to degrees.
     * @param val The value to convert in degrees.
     * @return The value in radians
     */
    public static double toDegrees(double val) {
        return val * (180/Math.PI);
    }

}
