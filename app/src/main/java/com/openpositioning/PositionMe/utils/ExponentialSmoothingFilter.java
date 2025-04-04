package com.openpositioning.PositionMe.utils;

import java.util.Arrays;
/**
 * A class implementing an Exponential Smoothing Filter.
 *
 * This filter is used to smooth a sequence of values using exponential smoothing.
 * It calculates a weighted average of current and past values, with the weight of each value determined by a smoothing factor alpha.
 * A higher alpha value discounts older observations faster, giving more weight to recent observations.
 *
 * Smoothing is applied independently to each value in the sequence.
 *
 * @author Yueyan Zhao
 * @author Zizhen Wang
 * @author Chen Zhao
 */
public class ExponentialSmoothingFilter {

    private final double alpha; // Smoothing factor between 0 and 1
    private Double[] smoothedValues; // Stores the last smoothed value
    private int valueCount; // The number of values to be smoothed

    /**
     * Constructor for the exponential smoothing filter.
     *
     * @param alpha The smoothing factor used, between 0 (no smoothing) and 1 (ignore all but the most recent value).
     */
    public ExponentialSmoothingFilter(double alpha, int valueCount) {
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
     * Applies exponential smoothing to new values.
     *
     * @param newValues An array containing the new values to be smoothed. Its length must match valueCount.
     * @return An array containing the smoothed values.
     */
    public double[] applySmoothing(double[] newValues) {
        if (newValues.length != valueCount) {
            throw new IllegalArgumentException("The length of newValues must match valueCount");
        }

        for (int i = 0; i < valueCount; i++) {
            if (smoothedValues[i] == null) {
                // First value, just use it as is for initialization
                smoothedValues[i] = newValues[i];
            } else {
                // Apply exponential smoothing formula
                smoothedValues[i] = alpha * newValues[i] + (1 - alpha) * smoothedValues[i];
            }
        }

        // Convert the Double array to double array for return
        return Arrays.stream(smoothedValues).mapToDouble(Double::doubleValue).toArray();
    }


    /**
     * Resets the filter, clearing the last smoothed value.
     */
    public void reset() {
        Arrays.fill(smoothedValues, null);
    }
}
