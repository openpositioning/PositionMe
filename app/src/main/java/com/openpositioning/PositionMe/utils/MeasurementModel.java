package com.openpositioning.PositionMe.utils;

import android.util.Log;

/**
 * Handles measurement models for different sensors, providing noise characteristics
 * and uncertainty estimates for fusion algorithms.
 */
public class MeasurementModel {
    private static final String TAG = "MeasurementModel";

    // Standard deviations for different measurement sources (in meters)
    private static final double PDR_BASE_STD = 0.5;  // Base PDR noise per step
    private static final double GNSS_BASE_STD = 5.0; // Typical GPS accuracy

    // Maximum uncertainty (meters) to consider a measurement
    private static final double MAX_UNCERTAINTY = 50.0;

    // Time relevance thresholds (milliseconds)
    private static final long PDR_RELEVANCE_THRESHOLD = 2000;    // 2 seconds
    private static final long GNSS_RELEVANCE_THRESHOLD = 20000;  // 20 seconds

    // Current measurement uncertainties
    private double pdrEastStd;
    private double pdrNorthStd;
    private double gnssStd;

    // Timestamp of last measurements
    private long lastPdrTimestamp;
    private long lastGnssTimestamp;

    /**
     * Constructor to initialize the measurement model with default values.
     */
    public MeasurementModel() {
        this.pdrEastStd = PDR_BASE_STD;
        this.pdrNorthStd = PDR_BASE_STD;
        this.gnssStd = GNSS_BASE_STD;
        this.lastPdrTimestamp = 0;
        this.lastGnssTimestamp = 0;
    }

    /**
     * Updates PDR measurement uncertainty based on step count and time since last update.
     * PDR uncertainty grows with each step due to accumulating errors.
     *
     * @param stepCount Number of steps since reset
     * @param currentTimeMillis Current system time in milliseconds
     */
    public void updatePdrUncertainty(int stepCount, long currentTimeMillis) {
        // PDR uncertainty grows with the square root of number of steps
        double stepFactor = Math.sqrt(Math.max(1, stepCount));

        // Calculate time factor (uncertainty grows with time since last update)
        double timeFactor = 1.0;
        if (lastPdrTimestamp > 0) {
            long timeDiff = currentTimeMillis - lastPdrTimestamp;
            timeFactor = 1.0 + Math.min(5.0, timeDiff / 1000.0 / 10.0); // Max 6x increase after 50 seconds
        }

        // Update uncertainties
        this.pdrEastStd = PDR_BASE_STD * stepFactor * timeFactor;
        this.pdrNorthStd = PDR_BASE_STD * stepFactor * timeFactor;

        // Update timestamp
        this.lastPdrTimestamp = currentTimeMillis;

        Log.d(TAG, "Updated PDR uncertainty: E=" + pdrEastStd + ", N=" + pdrNorthStd +
                " (steps=" + stepCount + ", timeFactor=" + timeFactor + ")");
    }

    /**
     * Updates GNSS measurement uncertainty based on reported accuracy and time.
     *
     * @param accuracy Reported GNSS accuracy in meters (can be null)
     * @param currentTimeMillis Current system time in milliseconds
     */
    public void updateGnssUncertainty(Float accuracy, long currentTimeMillis) {
        double baseUncertainty = GNSS_BASE_STD;

        // Use reported accuracy if available, otherwise use default
        if (accuracy != null && accuracy > 0) {
            baseUncertainty = accuracy;
        }

        // Apply time factor if we have a previous measurement
        double timeFactor = 1.0;
        if (lastGnssTimestamp > 0) {
            long timeDiff = currentTimeMillis - lastGnssTimestamp;
            timeFactor = 1.0 + Math.min(3.0, timeDiff / 1000.0 / 20.0); // Max 4x increase after 60 seconds
        }

        // Update uncertainty
        this.gnssStd = baseUncertainty * timeFactor;

        // Update timestamp
        this.lastGnssTimestamp = currentTimeMillis;

        Log.d(TAG, "Updated GNSS uncertainty: " + gnssStd +
                " (baseAccuracy=" + baseUncertainty + ", timeFactor=" + timeFactor + ")");
    }

    /**
     * Checks if a PDR measurement is still relevant based on time.
     *
     * @param currentTimeMillis Current system time in milliseconds
     * @return True if PDR data is still relevant, false otherwise
     */
    public boolean isPdrRelevant(long currentTimeMillis) {
        if (lastPdrTimestamp == 0) {
            return false; // No measurements yet
        }

        long timeDiff = currentTimeMillis - lastPdrTimestamp;
        return timeDiff <= PDR_RELEVANCE_THRESHOLD;
    }

    /**
     * Checks if a GNSS measurement is still relevant based on time.
     *
     * @param currentTimeMillis Current system time in milliseconds
     * @return True if GNSS data is still relevant, false otherwise
     */
    public boolean isGnssRelevant(long currentTimeMillis) {
        if (lastGnssTimestamp == 0) {
            return false; // No measurements yet
        }

        long timeDiff = currentTimeMillis - lastGnssTimestamp;
        return timeDiff <= GNSS_RELEVANCE_THRESHOLD;
    }

    /**
     * Gets the current PDR east uncertainty.
     *
     * @return Standard deviation in meters
     */
    public double getPdrEastStd() {
        return pdrEastStd;
    }

    /**
     * Gets the current PDR north uncertainty.
     *
     * @return Standard deviation in meters
     */
    public double getPdrNorthStd() {
        return pdrNorthStd;
    }

    /**
     * Gets the current GNSS uncertainty.
     *
     * @return Standard deviation in meters
     */
    public double getGnssStd() {
        return gnssStd;
    }

    /**
     * Calculates a weight for PDR measurements in fusion, based on uncertainty.
     * Lower uncertainty gives higher weight, capped at maximum value.
     *
     * @return Weight value between 0.0 and 1.0
     */
    public double getPdrWeight() {
        double uncertainty = Math.max(pdrEastStd, pdrNorthStd);
        return Math.max(0.0, Math.min(1.0, 1.0 - uncertainty / MAX_UNCERTAINTY));
    }

    /**
     * Calculates a weight for GNSS measurements in fusion, based on uncertainty.
     * Lower uncertainty gives higher weight, capped at maximum value.
     *
     * @return Weight value between 0.0 and 1.0
     */
    public double getGnssWeight() {
        return Math.max(0.0, Math.min(1.0, 1.0 - gnssStd / MAX_UNCERTAINTY));
    }

    /**
     * Gets the timestamp of the last PDR update.
     *
     * @return Timestamp in milliseconds
     */
    public long getLastPdrTimestamp() {
        return lastPdrTimestamp;
    }

    /**
     * Gets the timestamp of the last GNSS update.
     *
     * @return Timestamp in milliseconds
     */
    public long getLastGnssTimestamp() {
        return lastGnssTimestamp;
    }

    /**
     * Resets the measurement model to its initial state.
     */
    public void reset() {
        this.pdrEastStd = PDR_BASE_STD;
        this.pdrNorthStd = PDR_BASE_STD;
        this.gnssStd = GNSS_BASE_STD;
        this.lastPdrTimestamp = 0;
        this.lastGnssTimestamp = 0;
    }
}