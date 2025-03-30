package com.openpositioning.PositionMe.fusion;

import com.google.android.gms.maps.model.LatLng;

/**
 * SmoothingFilter applies an exponential smoothing algorithm
 * to successive LatLng positions. Each new value is combined with the
 * previous smoothed value using the smoothing factor (alpha). The formula is:
 *
 *     smoothed = previousSmoothed + alpha * (newValue - previousSmoothed)
 *
 * When no previous value exists, the filter uses the new value directly.
 */
public class SmoothingFilter {
    private final double alpha;
    private LatLng previousSmoothed;

    /**
     * Initializes the filter with a smoothing factor.
     *
     * @param alpha Smoothing factor between 0 and 1 (e.g., 0.3). Lower values
     *              mean more smoothing while higher values track the signal more closely.
     * @throws IllegalArgumentException if alpha is not between 0 and 1.
     */
    public SmoothingFilter(double alpha) {
        if (alpha < 0 || alpha > 1) {
            throw new IllegalArgumentException("Alpha must be between 0 and 1");
        }
        this.alpha = alpha;
        this.previousSmoothed = null;
    }

    /**
     * Updates the filter with a new LatLng measurement and returns
     * the smoothed position.
     *
     * @param newValue The new LatLng value.
     * @return The updated, smoothed LatLng value.
     */
    public LatLng update(LatLng newValue) {
        if (previousSmoothed == null) {
            previousSmoothed = newValue;
        } else {
            double newLat = previousSmoothed.latitude + alpha * (newValue.latitude - previousSmoothed.latitude);
            double newLng = previousSmoothed.longitude + alpha * (newValue.longitude - previousSmoothed.longitude);
            previousSmoothed = new LatLng(newLat, newLng);
        }
        return previousSmoothed;
    }

    /**
     * Resets the smoothing filter by clearing the previous smoothed value.
     */
    public void reset() {
        previousSmoothed = null;
    }
}