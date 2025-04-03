package com.openpositioning.PositionMe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;
import com.google.android.gms.maps.model.LatLng;
import android.util.Log;

/**
 * UtilFunctions: Utility class containing commonly used geospatial functions
 * for PDR (Pedestrian Dead Reckoning), coordinate conversions, distance calculation,
 * and vector-to-bitmap rendering.
 */
public class UtilFunctions {

    // Constant: Approximate meters in one degree of latitude (WGS84 ellipsoid)
    private static final double DEGREE_IN_M = 111111.0;

    // Maximum allowed dimension (width/height) for generated Bitmaps to avoid OutOfMemory errors
    private static final int MAX_SIZE = 512;

    /**
     * Computes a new GPS position given a PDR movement vector (in meters)
     * from a known starting LatLng position.
     *
     * This assumes small local movement and does not account for terrain or map projection distortion.
     *
     * @param initialLocation The known current location in LatLng format.
     * @param pdrMoved A float array representing [deltaX, deltaY] in meters.
     *                 deltaX: Eastward movement (longitude), deltaY: Northward movement (latitude).
     * @return New LatLng location after movement.
     * @throws IllegalArgumentException if input is invalid.
     */
    public static LatLng calculateNewPos(final LatLng initialLocation, final float[] pdrMoved) {
        if (initialLocation == null)
            throw new IllegalArgumentException("Initial location cannot be null");
        if (pdrMoved == null || pdrMoved.length < 2)
            throw new IllegalArgumentException("pdrMoved must be a float array of length 2");

        // Latitude: simple conversion — 1° ≈ 111111 meters
        double newLatitude = initialLocation.latitude + (pdrMoved[1] / DEGREE_IN_M);

        // Longitude: must compensate for shrinking degree distance at higher latitudes
        double newLongitude = initialLocation.longitude + (
                pdrMoved[0] / (DEGREE_IN_M * Math.cos(Math.toRadians(initialLocation.latitude)))
        );

        return new LatLng(newLatitude, newLongitude);
    }

    /**
     * Converts a latitude delta (in degrees) into distance in meters.
     * Used primarily for visualization or simple math.
     *
     * @param degreeVal Latitude delta in degrees.
     * @return Equivalent distance in meters.
     */
    public static double degreesToMetersLat(double degreeVal) {
        return degreeVal * DEGREE_IN_M;
    }

    /**
     * Converts a longitude delta (in degrees) into meters,
     * adjusting for the fact that longitudinal distance varies with latitude.
     *
     * @param degreeVal Longitude delta in degrees.
     * @param latitude  The current latitude (used to correct for Earth's curvature).
     * @return Equivalent distance in meters.
     */
    public static double degreesToMetersLng(double degreeVal, double latitude) {
        return degreeVal * DEGREE_IN_M / Math.cos(Math.toRadians(latitude));
    }

    /**
     * Calculates the great-circle (spherical) distance between two LatLng points.
     * Uses the Haversine formula for more accurate results over large distances.
     *
     * @param pointA First coordinate point.
     * @param pointB Second coordinate point.
     * @return Distance in meters.
     * @throws IllegalArgumentException if either point is null.
     */
    public static double distanceBetweenPoints(LatLng pointA, LatLng pointB) {
        if (pointA == null || pointB == null)
            throw new IllegalArgumentException("LatLng points cannot be null");

        // Radius of the Earth in meters
        final double R = 6371000;

        // Convert latitudes and deltas to radians
        double lat1 = Math.toRadians(pointA.latitude);
        double lat2 = Math.toRadians(pointB.latitude);
        double deltaLat = Math.toRadians(pointB.latitude - pointA.latitude);
        double deltaLon = Math.toRadians(pointB.longitude - pointA.longitude);

        // Haversine formula to compute arc distance between two spherical points
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                        Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return R * c;  // Return distance in meters
    }

    /**
     * Converts two LatLng coordinates to local easting/northing in meters,
     * using the first point as origin (0,0). Useful for transforming global
     * coordinates into a planar coordinate system.
     *
     * @param start  Reference/origin point.
     * @param target Destination point.
     * @return A double array [easting, northing] in meters.
     */
    public static double[] convertLatLangToNorthingEasting(final LatLng start, final LatLng target) {
        if (start == null || target == null) {
            throw new IllegalArgumentException("Start and target points cannot be null");
        }

        double deltaLat = target.latitude - start.latitude;
        double deltaLon = target.longitude - start.longitude;

        // Northing: difference in latitude * constant
        double northing = deltaLat * DEGREE_IN_M;

        // Easting: adjusted longitude difference by latitude curvature
        double easting = deltaLon * DEGREE_IN_M * Math.cos(Math.toRadians(start.latitude));

        return new double[]{easting, northing};
    }

    /**
     * Converts a VectorDrawable resource into a Bitmap, which is useful for
     * drawing icons or markers on custom views or maps.
     *
     * @param context          Application context.
     * @param vectorResourceID Resource ID of the vector drawable.
     * @return Rendered Bitmap object.
     * @throws IllegalArgumentException if inputs are invalid or resource not found.
     */
    public static Bitmap getBitmapFromVector(Context context, int vectorResourceID) {
        if (context == null)
            throw new IllegalArgumentException("Context cannot be null");

        Drawable vectorDrawable = ContextCompat.getDrawable(context, vectorResourceID);
        if (vectorDrawable == null)
            throw new IllegalArgumentException("Invalid vector resource ID: " + vectorResourceID);

        // Get intrinsic dimensions (fallback to 100 if undefined)
        int width = vectorDrawable.getIntrinsicWidth() > 0 ? vectorDrawable.getIntrinsicWidth() : 100;
        int height = vectorDrawable.getIntrinsicHeight() > 0 ? vectorDrawable.getIntrinsicHeight() : 100;

        // Cap dimensions to avoid OOM (Out of Memory)
        width = Math.min(width, MAX_SIZE);
        height = Math.min(height, MAX_SIZE);

        // Create a blank bitmap and draw the vector drawable onto it
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        vectorDrawable.setBounds(0, 0, width, height);
        vectorDrawable.draw(canvas);

        Log.d("UtilFunctions", "Bitmap created from vector ID: " + vectorResourceID +
                " (Size: " + width + "x" + height + ")");

        return bitmap;
    }
}


