package com.openpositioning.PositionMe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.model.LatLng;

/**
 * Class containing utility functions which can used by other classes.
 * @see com.openpositioning.PositionMe.fragments.RecordingFragment Currently used by RecordingFragment
 */
public class UtilFunctions {
    // Constant 1degree of latitiude/longitude (in m)
    private static final int  DEGREE_IN_M=111111;
    /**
     * Simple function to calculate the angle between two close points
     * @param pointA Starting point
     * @param pointB Ending point
     * @return Angle between the points
     */
    public static double calculateAngleSimple(LatLng pointA, LatLng pointB) {
        // Simple formula for close-by points
        return Math.toDegrees( Math.atan2(pointB.latitude-pointA.latitude,
                (pointB.longitude- pointA.longitude)*Math.cos(Math.toRadians(pointA.latitude))));
    }

    /**
     * Calculate new coordinates based on net distance moved in PDR
     * (as per WGS84 datum)
     * @param initialLocation Current Location of user
     * @param pdrMoved Amount of movement along X and Y
     * @return new Coordinates based on the movement
     */
    public static LatLng calculateNewPos(LatLng initialLocation,float[] pdrMoved){
        // Changes Euclidean movement into maps latitude and longitude as per WGS84 datum
        double newLatitude=initialLocation.latitude+(pdrMoved[1]/(DEGREE_IN_M));
        double newLongitude=initialLocation.longitude+(pdrMoved[0]/(DEGREE_IN_M))
                *Math.cos(Math.toRadians(initialLocation.latitude));
        return new LatLng(newLatitude, newLongitude);
    }
    /**
     * Converts a degree value of Latitude into meters
     * (as per WGS84 datum)
     * @param degreeVal Value in degrees to convert to meters
     * @return double corresponding to the value in meters.
     */
    public static double degreesToMetersLat(double degreeVal) {
        return degreeVal*DEGREE_IN_M;
    }
    /**
     * Converts a degree value of Longitude into meters
     * (as per WGS84 datum)
     * @param degreeVal Value in degrees to convert to meters
     * @param latitude the latitude of the current position
     * @return double corresponding to the value in meters.
     */
    public static double degreesToMetersLng(double degreeVal, double latitude) {
        return degreeVal*DEGREE_IN_M/Math.cos(Math.toRadians(latitude));
    }

    /**
     * Calculates the distance between two LatLng points A and B (in meters)
     * (Note: approximation: for short distances)
     * @param pointA initial point
     * @param pointB final point
     * @return the distance between the two points
     */
    public static double distanceBetweenPoints(LatLng pointA, LatLng pointB){
        return  Math.sqrt(Math.pow(degreesToMetersLat(pointA.latitude-pointB.latitude),2) +
                Math.pow(degreesToMetersLng(pointA.longitude-pointB.longitude,pointA.latitude),2));
    }

    /**
     * Creates a bitmap from a vector
     *
     * @param context          Context of activity being used
     * @param vectorResourceID Resource id whose vector get converted to a Bitmap
     * @param v
     * @return Bitmap of the resource vector
     */
    public static Bitmap getBitmapFromVector(Context context, int vectorResourceID, float v) {
        // Get drawable vector
        Drawable vectorDrawable = ContextCompat.getDrawable(context, vectorResourceID);
        // Bitmap created to draw the vector in
        Bitmap bitmap = Bitmap.createBitmap(vectorDrawable.getIntrinsicWidth(), vectorDrawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        // Canvas to draw the bitmap on
        Canvas canvas = new Canvas(bitmap);
        // Drawing on canvas
        vectorDrawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        vectorDrawable.draw(canvas);
        return bitmap;
    }

}
