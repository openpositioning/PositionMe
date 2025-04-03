package com.openpositioning.PositionMe;

import com.google.android.gms.maps.model.LatLng;
import java.util.List;

public class TrajOptim {

    /**
     * Apply Weighted Moving Average (WMA) smoothing to a specific index point in the trajectory list
     * @param points trajectory point list
     * @param windowSize smoothing window size
     * @param targetIndex point to smooth (e.g. index = 3)
     * @return smoothed LatLng point
     */
    public static LatLng applyWMAAtIndex(List<LatLng> points, int windowSize, int targetIndex) {
        int size = points.size();

        if (targetIndex >= size || targetIndex < windowSize - 1) {
            return points.get(targetIndex); // Insufficient data, return original point
        }

        double sumLat = 0, sumLng = 0;
        int weightSum = 0;

        for (int i = 0; i < windowSize; i++) {
            int index = targetIndex - i;
            int weight = windowSize - i;
            sumLat += points.get(index).latitude * weight;
            sumLng += points.get(index).longitude * weight;
            weightSum += weight;
        }

        return new LatLng(sumLat / weightSum, sumLng / weightSum);
    }
    /**
     * Apply low-pass filter to current position (for position smoothing)
     * If one of the coordinates is null, return the non-null coordinate; if both are null, return null.
     * Also validates alpha range, ensuring it's between 0 and 1.
     *
     * @param prev Previous frame's coordinate
     * @param current Current frame's original coordinate
     * @param alpha Smoothing factor (0 ~ 1), recommended 0.1~0.3; will be corrected to valid range if out of bounds
     * @return Smoothed coordinate
     */
    public static LatLng applyLowPassFilter(LatLng prev, LatLng current, float alpha) {
        // Null check: if one is null, return the non-null value; if both are null, return null
        if (prev == null && current == null) {
            return null;
        } else if (prev == null) {
            return current;
        } else if (current == null) {
            return prev;
        }

        // Restrict alpha's value range to [0, 1]
        if (alpha < 0f) {
            alpha = 0f;
        } else if (alpha > 1f) {
            alpha = 1f;
        }

        double lat = prev.latitude * (1 - alpha) + current.latitude * alpha;
        double lng = prev.longitude * (1 - alpha) + current.longitude * alpha;
        return new LatLng(lat, lng);
    }

}
