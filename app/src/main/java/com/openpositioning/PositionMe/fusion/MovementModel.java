// Refactored MovementModel.java
package com.openpositioning.PositionMe.fusion;

import android.util.Log;
import androidx.annotation.NonNull; // Optional, for clarity

import java.lang.Math; // Explicit import

/**
 * Models pedestrian movement characteristics for use in position fusion algorithms.
 *
 * This class provides estimates for movement parameters like bearing uncertainty
 * based on turn detection, temporal penalties for increasing uncertainty over time,
 * and step error estimation. It also incorporates outlier detection for position deviations.
 * The core logic of calculating penalties and uncertainties remains consistent with the
 * original implementation, but method names and structure have been revised for clarity.
 *
 * @author Michal Wiercigroch
 */
public class MovementModel {

    private static final String TAG = MovementModel.class.getSimpleName();

    /** Enumerates the types of pedestrian movement based on bearing changes. */
    public enum MovementType {
        /** Movement generally in a straight line (small bearing change). */
        STRAIGHT,
        /** A noticeable but not sharp change in direction. */
        PSEUDO_TURN,
        /** A significant change in direction (sharp turn). */
        TURN
    }

    // --- Pedestrian Movement Parameter Constants ---
    private static final double DEFAULT_AVERAGE_STEP_LENGTH_METERS = 0.7;
    private static final double STEP_LENGTH_RELATIVE_ERROR = 0.10; // 10% uncertainty
    private static final double STEP_DIRECTION_ABSOLUTE_ERROR_METERS = 0.20; // Base uncertainty in direction projection

    // --- Bearing Uncertainty Constants (Standard Deviations in Radians) ---
    private static final double BEARING_STD_DEV_TURN_RAD = Math.toRadians(15.0);
    private static final double BEARING_STD_DEV_PSEUDO_TURN_RAD = Math.toRadians(8.0);
    private static final double BEARING_STD_DEV_STRAIGHT_RAD = Math.toRadians(2.0);

    // --- Time-Based Uncertainty Constants ---
    /** Time duration (ms) over which the temporal penalty factor scales from 1.0 to 1.5. */
    private static final long TEMPORAL_PENALTY_MAX_DURATION_MS = 6000L; // 6 seconds
    /** Time duration (ms) over which the bearing penalty scales towards its maximum. */
    private static final long BEARING_PENALTY_MAX_DURATION_MS = 15 * 60 * 1000L; // 15 minutes
    /** Maximum additional bearing uncertainty (radians) applied due to elapsed time. */
    private static final double MAX_ADDITIONAL_BEARING_PENALTY_RAD = Math.toRadians(22.5);

    // --- Outlier Detection Constant ---
    /** A hard threshold (meters) for position deviations; larger deviations are immediately flagged. */
    private static final double POSITION_DEVIATION_HARD_THRESHOLD_METERS = 10.0;

    // --- State Variables ---
    /** Detector for statistical outliers in position deviations. */
    private KalmanFilterFusion.FusionOutlierDetector distanceDeviationChecker;
    /** The length (meters) of the previously processed step or a default value. */
    private double lastRecordedStepLengthMeters;
    /** Timestamp (milliseconds since epoch) of the last model update. */
    private long lastUpdateTimestampMillis;

    /**
     * Constructs a new MovementModel with default settings and an outlier detector.
     */
    public MovementModel() {
        this.distanceDeviationChecker = new KalmanFilterFusion.FusionOutlierDetector(); // Default window size
        this.lastRecordedStepLengthMeters = DEFAULT_AVERAGE_STEP_LENGTH_METERS;
        this.lastUpdateTimestampMillis = 0L;
        Log.i(TAG, "Initialized MovementModel with default step length: "
                + DEFAULT_AVERAGE_STEP_LENGTH_METERS + "m");
    }

    /**
     * Records the current time as the last update timestamp.
     * Used for calculating time-dependent penalties.
     *
     * @param timestampMillis The current system time in milliseconds.
     */
    public void recordTimestamp(long timestampMillis) {
        this.lastUpdateTimestampMillis = timestampMillis;
    }

    /**
     * Updates the last recorded step length.
     * Input validation ensures the step length is positive.
     *
     * @param stepLengthMeters The calculated step length in meters for the latest step.
     */
    public void updateLastStepLength(double stepLengthMeters) {
        if (stepLengthMeters > 0) {
            this.lastRecordedStepLengthMeters = stepLengthMeters;
        } else {
            Log.w(TAG, "Attempted to set non-positive step length: " + stepLengthMeters);
            // Keep the previous value or default if it was the first attempt
        }
    }

    /**
     * Retrieves the estimated standard deviation of the bearing (orientation)
     * based on the detected type of movement.
     *
     * @param movementType The classified movement type (STRAIGHT, PSEUDO_TURN, TURN).
     * @return The standard deviation of bearing uncertainty in radians.
     */
    public double getBearingUncertainty(@NonNull MovementType movementType) {
        switch (movementType) {
            case TURN:
                return BEARING_STD_DEV_TURN_RAD;
            case PSEUDO_TURN:
                return BEARING_STD_DEV_PSEUDO_TURN_RAD;
            case STRAIGHT:
                return BEARING_STD_DEV_STRAIGHT_RAD;
            default:
                // Should not happen with enum, but default to highest uncertainty
                Log.w(TAG, "Unknown movement type provided: " + movementType + ". Using TURN uncertainty.");
                return BEARING_STD_DEV_TURN_RAD;
        }
    }

    /**
     * Calculates a time-dependent penalty factor (>= 1.0) that increases
     * uncertainty based on the time elapsed since the last update.
     *
     * The penalty scales linearly from 1.0 to 1.5 over `TEMPORAL_PENALTY_MAX_DURATION_MS`.
     *
     * @param currentTimestampMillis The current system time in milliseconds.
     * @return A penalty factor (>= 1.0) to scale uncertainty values.
     */
    public double computeTemporalPenaltyFactor(long currentTimestampMillis) {
        if (lastUpdateTimestampMillis <= 0) {
            return 1.0; // No penalty if this is the first update or time hasn't been recorded
        }

        long elapsedTimeMillis = currentTimestampMillis - lastUpdateTimestampMillis;
        if (elapsedTimeMillis <= 0) {
            return 1.0; // No penalty if time hasn't advanced
        }

        // Calculate the fraction of the max duration elapsed, capped at 1.0
        double timeFraction = Math.min((double) elapsedTimeMillis / TEMPORAL_PENALTY_MAX_DURATION_MS, 1.0);

        // Linearly interpolate penalty factor from 1.0 (at 0ms) to 1.5 (at max duration)
        double penaltyFactor = 1.0 + 0.5 * timeFraction;

        return penaltyFactor;
    }

    /**
     * Calculates an adjusted bearing uncertainty (standard deviation in radians)
     * by adding a time-dependent penalty to a base uncertainty.
     *
     * The penalty increases linearly with elapsed time, up to a maximum defined by
     * `MAX_ADDITIONAL_BEARING_PENALTY_RAD`, over the duration `BEARING_PENALTY_MAX_DURATION_MS`.
     *
     * @param baseBearingStdDevRad The base bearing standard deviation (e.g., from {@link #getBearingUncertainty}).
     * @param currentTimestampMillis The current system time in milliseconds.
     * @return The adjusted bearing standard deviation in radians, including the time penalty.
     */
    public double computeAdjustedBearingUncertainty(double baseBearingStdDevRad, long currentTimestampMillis) {
        if (lastUpdateTimestampMillis <= 0) {
            return baseBearingStdDevRad; // No penalty if time hasn't been recorded
        }

        long elapsedTimeMillis = currentTimestampMillis - lastUpdateTimestampMillis;
        if (elapsedTimeMillis <= 0) {
            return baseBearingStdDevRad; // No penalty if time hasn't advanced
        }

        // Calculate the fraction of the max duration elapsed for bearing penalty, capped at 1.0
        double timeFraction = Math.min((double) elapsedTimeMillis / BEARING_PENALTY_MAX_DURATION_MS, 1.0);

        // Linearly interpolate the additional penalty from 0 to MAX_ADDITIONAL_BEARING_PENALTY_RAD
        double additionalPenalty = MAX_ADDITIONAL_BEARING_PENALTY_RAD * timeFraction;

        // Combine base uncertainty with the time-based additional penalty
        // Note: Original code seemed to interpolate *between* base and max, which is less intuitive.
        // This interpretation adds penalty *to* the base uncertainty. Let's stick to the *original logic*:
        // Linear interpolation between baseStd and maxPenalty = baseStd + (maxPenalty - baseStd) * fraction
        // Let's define maxTotalBearingUncertainty = MAX_ADDITIONAL_BEARING_PENALTY_RAD (assuming this was the intended *total max*, not *additional*)
        // If MAX_ADDITIONAL_BEARING_PENALTY_RAD was truly meant as *additional*, the calculation would be:
        // double adjustedStdDev = baseBearingStdDevRad + additionalPenalty;

        // Replicating original logic: Interpolate between baseStd and a target max value.
        // Let's assume MAX_ADDITIONAL_BEARING_PENALTY_RAD defines the upper limit target.
        double targetMaxStdDev = MAX_ADDITIONAL_BEARING_PENALTY_RAD; // As per original formula structure
        double adjustedStdDev = baseBearingStdDevRad + (targetMaxStdDev - baseBearingStdDevRad) * timeFraction;

        // Ensure the adjusted value doesn't go below the base (can happen if targetMaxStdDev < baseBearingStdDevRad)
        return Math.max(adjustedStdDev, baseBearingStdDevRad);
    }


    /**
     * Estimates the uncertainty (error) in meters associated with a single step.
     * This combines relative step length error and absolute directional error,
     * scaled by a temporal penalty factor.
     *
     * @param averageStepLengthMeters The average or estimated length of the step in meters.
     * @param temporalPenaltyFactor The time-dependent penalty factor (from {@link #computeTemporalPenaltyFactor}).
     * @return The estimated step error in meters.
     */
    public double estimateStepError(double averageStepLengthMeters, double temporalPenaltyFactor) {
        // Combine relative error (proportional to step length) and absolute error
        double baseError = (STEP_LENGTH_RELATIVE_ERROR * averageStepLengthMeters) + STEP_DIRECTION_ABSOLUTE_ERROR_METERS;

        // Scale the base error by the temporal penalty
        return baseError * temporalPenaltyFactor;
    }

    /**
     * Checks if a given position deviation (distance between expected and measured position)
     * is considered an outlier.
     *
     * It first checks against a hard threshold and then uses the statistical outlier detector.
     *
     * @param deviationMeters The distance in meters representing the position deviation.
     * @return {@code true} if the deviation is considered an outlier, {@code false} otherwise.
     */
    public boolean isDeviationAnOutlier(double deviationMeters) {
        // 1. Hard Threshold Check
        if (deviationMeters > POSITION_DEVIATION_HARD_THRESHOLD_METERS) {
            Log.d(TAG, String.format("Deviation %.2fm exceeds hard threshold %.2fm",
                    deviationMeters, POSITION_DEVIATION_HARD_THRESHOLD_METERS));
            return true;
        }

        // 2. Statistical Check using FusionOutlierDetector
        // The internal detector maintains its own history of deviations evaluated by this method.
        return distanceDeviationChecker.evaluateDistance(deviationMeters);
    }

    /**
     * Gets the default average step length used by the model.
     *
     * @return The default step length in meters.
     */
    public double getDefaultStepLength() {
        return DEFAULT_AVERAGE_STEP_LENGTH_METERS;
    }

    /**
     * Gets the last recorded step length.
     *
     * @return The last step length in meters updated via {@link #updateLastStepLength}.
     */
    public double getLastRecordedStepLength() {
        return lastRecordedStepLengthMeters;
    }

    /**
     * Gets the timestamp of the last model update.
     *
     * @return The last update timestamp in milliseconds since epoch.
     */
    public long getLastUpdateTimestamp() {
        return lastUpdateTimestampMillis;
    }

    /**
     * Resets the movement model state to its initial default values.
     * This includes resetting the last step length, the update timestamp,
     * and clearing the history of the internal outlier detector.
     */
    public void resetState() {
        this.lastRecordedStepLengthMeters = DEFAULT_AVERAGE_STEP_LENGTH_METERS;
        this.lastUpdateTimestampMillis = 0L;
        // Reset the outlier detector by creating a new instance or clearing history
        // Re-creating ensures complete reset, including buffer size if constructor changes
        this.distanceDeviationChecker = new KalmanFilterFusion.FusionOutlierDetector();
        // Alternatively, if detector state needs preservation across resets (unlikely):
        // if (distanceDeviationChecker != null) {
        //    distanceDeviationChecker.clearHistory();
        // }
        Log.i(TAG, "MovementModel state reset.");
    }
}