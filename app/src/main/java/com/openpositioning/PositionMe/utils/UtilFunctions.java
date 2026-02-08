package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.Path;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.presentation.fragment.RecordingFragment;

/**
 * Class containing utility functions which can used by other classes.
 * @see RecordingFragment Currently used by RecordingFragment
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
        double newLongitude = initialLocation.longitude + (
                pdrMoved[0] / (DEGREE_IN_M * Math.cos(Math.toRadians(initialLocation.latitude)))
        );
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
     * @param context Context of activity being used
     * @param vectorResourceID Resource id whose vector get converted to a Bitmap
     * @return Bitmap of the resource vector
     */
    public static Bitmap getBitmapFromVector(Context context, int vectorResourceID) {
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

    /** part c
     * creates bitmap for numbered test-point marker (
     * draws numbered red map-pin
     * marker indexed with q
     *bitmap can be used with BitmapDescriptorFactory.fromBitmap().
     */
    public static Bitmap createNumberedMarkerBitmap(Context context, int number) {
        float density = context.getResources().getDisplayMetrics().density;
        int widthPx = (int) (34 * density);
        int heightPx = (int) (52 * density);
        Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        float centerX = widthPx / 2f;
        float circleRadius = widthPx / 2f - 3;
        float circleCenterY = circleRadius + 3;
        float pointY = heightPx - 3;


        Path pinPath = new Path();
        pinPath.moveTo(centerX - circleRadius, circleCenterY);
        pinPath.arcTo(centerX - circleRadius, circleCenterY - circleRadius,
                centerX + circleRadius, circleCenterY + circleRadius,
                180, 180, false);
        pinPath.lineTo(centerX, pointY);
        pinPath.close();

        Paint pinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pinPaint.setColor(0xFFE53935);
        pinPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(pinPath, pinPaint);

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setColor(0xFFB71C1C);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2);
        canvas.drawPath(pinPath, strokePaint);

        // number in pin
        String label = String.valueOf(number);
        float textSizePx = 14 * density;

        Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        outlinePaint.setColor(0xFF000000);
        outlinePaint.setTextSize(textSizePx);
        outlinePaint.setTextAlign(Paint.Align.CENTER);
        outlinePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(2);
        float textY = circleCenterY - (outlinePaint.descent() + outlinePaint.ascent()) / 2f;
        canvas.drawText(label, centerX, textY, outlinePaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(textSizePx);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText(label, centerX, textY, textPaint);

        return bitmap;
    }
}