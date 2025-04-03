package com.openpositioning.PositionMe.utils;

import java.util.Arrays;

/**
 * Implements an exponential smoothing filter for a fixed number of values.
 *
 * <p>Exponential smoothing is a time series forecasting method that uses a weighted average of past observations,
 * with the weights decaying exponentially as the observations get older.  This implementation applies smoothing
 * independently to each of a fixed number of values.
 * </p>
 *
 * <p>The smoothing factor, alpha (α), controls the weight given to the most recent observation.
 * A higher alpha value (closer to 1) gives more weight to recent observations, making the smoothed value more responsive
 * to changes in the data.  A lower alpha value (closer to 0) gives more weight to past observations, resulting in a smoother
 * output that is less sensitive to short-term fluctuations.</p>
 *
 * <p>The filter is initialized with the smoothing factor and the number of values to smooth.  The {@link #applySmoothing(double[])}
 * method takes an array of new values as input and returns an array of smoothed values.  The first time this method is called,
 * the smoothed values are initialized with the input values. Subsequent calls update the smoothed values using the
 * exponential smoothing formula:</p>
 *
 * <pre>
 * smoothed_value[i] = α * new_value[i] + (1 - α) * previous_smoothed_value[i]
 * </pre>
 *
 * <p>The {@link #reset()} method clears the smoothed values, effectively restarting the filter.</p>
 */
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
