package com.openpositioning.PositionMe.presentation.display;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import androidx.annotation.NonNull;

import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;

/**
 * Builds and caches custom map marker bitmaps for Assignment 2 data display.
 */
public class ObservationMarkerFactory {

    private final Context appContext;

    private BitmapDescriptor gnssObservationIcon;
    private BitmapDescriptor wifiObservationIcon;
    private BitmapDescriptor pdrObservationIcon;
    private BitmapDescriptor bestEstimateDotIcon;

    // Keeps an application context for bitmap and density work.
    public ObservationMarkerFactory(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    // Returns the cached icon for each observation type.
    @NonNull
    public BitmapDescriptor getObservationIcon(@NonNull DisplayObservationType type) {
        switch (type) {
            case GNSS:
                if (gnssObservationIcon == null) {
                    gnssObservationIcon = createCircleIcon(0xE03064FF, 12f, 0xCCFFFFFF, 1.0f);
                }
                return gnssObservationIcon;
            case WIFI:
                if (wifiObservationIcon == null) {
                    wifiObservationIcon = createCircleIcon(0xE0FF9800, 12f, 0xCC4A2C00, 1.2f);
                }
                return wifiObservationIcon;
            default:
                if (pdrObservationIcon == null) {
                    pdrObservationIcon = createCircleIcon(0xD017C964, 12f, 0xCCFFFFFF, 1.0f);
                }
                return pdrObservationIcon;
        }
    }

    // Returns the small marker used for the best estimate point.
    @NonNull
    public BitmapDescriptor getBestEstimateDotIcon() {
        if (bestEstimateDotIcon == null) {
            bestEstimateDotIcon = createCircleIcon(0xFFE11D48, 7f);
        }
        return bestEstimateDotIcon;
    }

    // Creates a circle icon without any outline.
    @NonNull
    private BitmapDescriptor createCircleIcon(int colorArgb, float diameterDp) {
        return createCircleIcon(colorArgb, diameterDp, 0x00000000, 0f);
    }

    // Draws a colored circle icon and an optional stroke.
    @NonNull
    private BitmapDescriptor createCircleIcon(int colorArgb,
                                              float diameterDp,
                                              int strokeColorArgb,
                                              float strokeWidthDp) {
        int diameterPx = dpToPx(diameterDp);
        if (diameterPx < 2) diameterPx = 2;

        Bitmap bitmap = Bitmap.createBitmap(diameterPx, diameterPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colorArgb);

        float center = diameterPx / 2f;
        float radius = (diameterPx / 2f) - 0.6f;
        canvas.drawCircle(center, center, radius, paint);

        if ((strokeColorArgb >>> 24) != 0 && strokeWidthDp > 0f) {
            Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setColor(strokeColorArgb);
            strokePaint.setStrokeWidth(dpToPx(strokeWidthDp));
            canvas.drawCircle(center, center, Math.max(0f, radius - strokePaint.getStrokeWidth() / 2f), strokePaint);
        }

        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }

    // Converts dp units into pixels for drawing.
    private int dpToPx(float dp) {
        return Math.round(dp * appContext.getResources().getDisplayMetrics().density);
    }
}
