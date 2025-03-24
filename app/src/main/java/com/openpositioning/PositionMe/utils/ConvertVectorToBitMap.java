package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;

/**
 * Utility class to convert vector drawable to bitmap for use as marker icon on Google Maps.
 *
 * @author Alexandra Geciova
 */
public final class ConvertVectorToBitMap {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ConvertVectorToBitMap() {
    }

    /**
     * Converts a vector drawable to a bitmap descriptor with optional color tinting.
     *
     * @param context     The context used to access resources.
     * @param color       The color to tint the drawable, or -1 if no tinting is needed.
     * @param drawableId  The resource ID of the vector drawable.
     * @return            The bitmap descriptor representing the converted bitmap.
     */
    public static BitmapDescriptor convert(Context context, int color, int drawableId) {
        // Retrieve the vector drawable from resources
        Drawable vectorDrawable = ContextCompat.getDrawable(context, drawableId);

        // Apply color tinting if specified
        if (color != -1 && vectorDrawable != null) {
            vectorDrawable.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }

        // Create a bitmap to hold the converted drawable
        Bitmap bitmap = null;
        if (vectorDrawable != null) {
            bitmap = Bitmap.createBitmap(vectorDrawable.getIntrinsicWidth(),
                    vectorDrawable.getIntrinsicHeight(),
                    Bitmap.Config.ARGB_8888);
        }

        // Draw the vector drawable onto the bitmap canvas
        Canvas canvas = new Canvas(bitmap);
        if (vectorDrawable != null) {
            vectorDrawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            vectorDrawable.draw(canvas);
        }

        // Return the bitmap descriptor
        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }
}
