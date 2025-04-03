package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.util.TypedValue;

import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.local.TrajParser;
import com.openpositioning.PositionMe.presentation.fragment.RecordingFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Class containing utility functions which can used by other classes.
 * @see RecordingFragment Currently used by RecordingFragment
 */
public class UtilFunctions {

    // Constant 1degree of latitiude/longitude (in m)
    public static final int  DEGREE_IN_M =111111;

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

    /**
     * Created By Guilherme Barreiros
     * Converts a single LatLng point (in WGS84) to local North-East coordinates (in meters)
     * relative to the provided reference point.
     *
     * @param point     The LatLng point to convert.
     * @param reference The reference LatLng point to use as the origin.
     * @return A double array with [north, east] in meters.
     */
    public static double[] convertLatLngToNorthEast(LatLng point, LatLng reference) {
        double deltaLat = point.latitude - reference.latitude;
        double deltaLon = point.longitude - reference.longitude;
        // 1 degree of latitude is approximately 111,111 meters.
        double north = deltaLat * 111111;
        // For longitude, adjust using the cosine of the reference latitude.
        double east = deltaLon * 111111 * Math.cos(Math.toRadians(reference.latitude));
        return new double[]{north, east};
    }

    /**
     * Code By Guilherme Barreiros
     * Converts local North-East coordinates (in meters) back to a WGS84 LatLng,
     * using the provided reference point as the origin.
     *
     * @param local     A double array with [north, east] in meters.
     * @param reference The reference LatLng point (origin).
     * @return The converted LatLng in WGS84.
     */
    public static LatLng localToLatLng(double[] local, LatLng reference) {
        double refLat = reference.latitude;
        double refLon = reference.longitude;
        // 1 degree of latitude is approximately 111,111 meters.
        double deltaLat = local[1] / 111111.0;
        // Convert east offset to degrees using the cosine of the reference latitude.
        double deltaLon = local[0] / (111111.0 * Math.cos(Math.toRadians(refLat)));
        return new LatLng(refLat + deltaLat, refLon + deltaLon);
    }

    // Code by Guilherme: Creates a custom tag marker icon with a white background label to its left and a circle on the right.
    // Code by Guilherme: Creates a composite tag marker icon with the tag symbol on the left and the label on the right.
    public static Bitmap createTagMarkerIcon(Context context, String label) {
        // Dimensions in dp:
        int markerDiameterDp = 40;  // Desired size for the tag symbol.
        int paddingDp = 8;          // Spacing between the tag symbol and the text.
        int textPaddingDp = 4;      // Padding inside the text background.

        // Convert dimensions to pixels.
        int markerDiameter = dpToPx(context, markerDiameterDp);
        int padding = dpToPx(context, paddingDp);
        int textPadding = dpToPx(context, textPaddingDp);

        // Load the custom tag vector drawable.
        Drawable markerDrawable = ContextCompat.getDrawable(context, R.drawable.ic_custom_tag);
        if (markerDrawable == null) {
            throw new RuntimeException("Custom tag drawable (ic_custom_tag) not found.");
        }

        // We will draw the marker at a fixed size (markerDiameter x markerDiameter).
        // Prepare text paint.
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, context.getResources().getDisplayMetrics()));
        textPaint.setColor(Color.BLACK);

        // Measure text dimensions.
        float textWidth = textPaint.measureText(label);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textHeight = fm.descent - fm.ascent;

        // Total width = marker (on left) + padding + text width + text background padding on both sides.
        int totalWidth = markerDiameter + padding + (int)textWidth + 2 * textPadding;
        // Total height is the maximum of markerDiameter and text background height.
        int totalHeight = Math.max(markerDiameter, (int)(textHeight + 2 * textPadding));

        // Create the bitmap.
        Bitmap bitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Draw the tag symbol (marker) on the left.
        markerDrawable.setBounds(0, (totalHeight - markerDiameter) / 2, markerDiameter, (totalHeight - markerDiameter) / 2 + markerDiameter);
        markerDrawable.draw(canvas);

        // Draw the white rounded rectangle for the label to the right of the marker.
        float rectLeft = markerDiameter + padding;
        float rectTop = (totalHeight - (textHeight + 2 * textPadding)) / 2f;
        float rectRight = rectLeft + textWidth + 2 * textPadding;
        float rectBottom = rectTop + textHeight + 2 * textPadding;
        Paint rectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        rectPaint.setColor(Color.WHITE);
        RectF rect = new RectF(rectLeft, rectTop, rectRight, rectBottom);
        float cornerRadius = dpToPx(context, 4);
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, rectPaint);

        // Draw the label text on top of the white rectangle.
        float textX = rectLeft + textPadding;
        // Calculate baseline so that text is vertically centered in the rectangle.
        float textY = rectTop - fm.ascent;
        canvas.drawText(label, textX, textY, textPaint);

        return bitmap;
    }

    public static double[] latLngToMeters(LatLng latLng, Location origin) {
        double dx = degreesToMetersLng(latLng.longitude - origin.getLongitude(), origin.getLatitude());
        double dy = degreesToMetersLat(latLng.latitude - origin.getLatitude());
        return new double[]{dx, dy};
    }

    public static double[] metersToLatLng(double xMeters, double yMeters, Location origin) {
        double lat = origin.getLatitude() + yMeters / DEGREE_IN_M;
        double lng = origin.getLongitude() + xMeters / (DEGREE_IN_M * Math.cos(Math.toRadians(origin.getLatitude())));
        return new double[]{lat, lng};
    }

    // Code by Guilherme: Converts dp to pixels.
    public static int dpToPx(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    // Code by Guilherme: Measures text width for a given string.
    public static float getTextWidth(Context context, String text) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, context.getResources().getDisplayMetrics()));
        return paint.measureText(text);

    }

    // Code by Guilherme
    public static double calculateBearing(LatLng from, LatLng to) {
        double lat1 = Math.toRadians(from.latitude);
        double lon1 = Math.toRadians(from.longitude);
        double lat2 = Math.toRadians(to.latitude);
        double lon2 = Math.toRadians(to.longitude);

        double dLon = lon2 - lon1;

        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);

        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }





}
