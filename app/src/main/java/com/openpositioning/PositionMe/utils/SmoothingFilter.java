package com.openpositioning.PositionMe.utils;

import java.util.Arrays;

public class SmoothingFilter {
    private final double alpha;
    private Double[] smoothedValues;
    private final int valueCount;

    /**
     * Constructor for the exponential smoothing filter.
     *
     * @param alpha The smoothing factor between 0 and 1.
     * @param valueCount The number of values to smooth.
     */
    public SmoothingFilter(double alpha, int valueCount) {
        if (alpha < 0 || alpha > 1) {
            throw new IllegalArgumentException("Alpha must be between 0 and 1");
        }
        if (valueCount <= 0) {
            throw new IllegalArgumentException("Value count must be greater than 0");
        }
        this.alpha = alpha;
        this.valueCount = valueCount;
        this.smoothedValues = new Double[valueCount];
    }

    /**
     * Applies exponential smoothing to the provided new values.
     *
     * @param newValues An array of new values to smooth. Its length must equal valueCount.
     * @return A double array containing the smoothed values.
     */
    public double[] applySmoothing(double[] newValues) {
        if (newValues.length != valueCount) {
            throw new IllegalArgumentException("newValues length must match valueCount");
        }
        for (int i = 0; i < valueCount; i++) {
            if (smoothedValues[i] == null) {
                smoothedValues[i] = newValues[i];
            } else {
                smoothedValues[i] = alpha * newValues[i] + (1 - alpha) * smoothedValues[i];
            }
        }
        return Arrays.stream(smoothedValues).mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * Resets the filter by clearing the smoothed values.
     */
    public void reset() {
        Arrays.fill(smoothedValues, null);
    }
}
