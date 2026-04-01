package com.openpositioning.PositionMe.presentation.display;

import androidx.annotation.NonNull;

import com.google.android.gms.maps.model.LatLng;

/**
 * Lightweight exponential smoother for live map display only.
 * It improves visual stability without changing the underlying fusion state.
 */
public class ExponentialLatLngSmoother {

    private final double alpha;
    private LatLng lastOutput;

    // Stores the smoothing factor for later display updates.
    public ExponentialLatLngSmoother(double alpha) {
        this.alpha = alpha;
    }

    // Smooths the next point while still following larger moves quickly.
    public LatLng filter(@NonNull LatLng input) {
        if (lastOutput == null) {
            lastOutput = input;
            return input;
        }

        double effectiveAlpha = alpha;
        double gapMeters = approximateDistanceMeters(lastOutput, input);
        if (gapMeters >= 0.75) {
            effectiveAlpha = Math.max(effectiveAlpha, 0.92);
        } else if (gapMeters >= 0.35) {
            effectiveAlpha = Math.max(effectiveAlpha, 0.86);
        } else if (gapMeters >= 0.15) {
            effectiveAlpha = Math.max(effectiveAlpha, 0.80);
        }

        double lat = lastOutput.latitude + effectiveAlpha * (input.latitude - lastOutput.latitude);
        double lon = lastOutput.longitude + effectiveAlpha * (input.longitude - lastOutput.longitude);
        lastOutput = new LatLng(lat, lon);
        return lastOutput;
    }

    // Clears the saved output so smoothing starts over.
    public void reset() {
        lastOutput = null;
    }

    // Resets the smoother with a given starting point.
    public void reset(@NonNull LatLng initialValue) {
        lastOutput = initialValue;
    }

    // Estimates the straight-line distance between two map points.
    private double approximateDistanceMeters(@NonNull LatLng a, @NonNull LatLng b) {
        final double earthRadiusMeters = 6378137.0;
        double dLat = Math.toRadians(b.latitude - a.latitude);
        double dLon = Math.toRadians(b.longitude - a.longitude);
        double meanLat = Math.toRadians((a.latitude + b.latitude) * 0.5);
        double north = dLat * earthRadiusMeters;
        double east = dLon * Math.cos(meanLat) * earthRadiusMeters;
        return Math.sqrt(east * east + north * north);
    }
}
