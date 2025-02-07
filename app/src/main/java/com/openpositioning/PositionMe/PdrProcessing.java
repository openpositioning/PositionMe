package com.openpositioning.PositionMe;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.SensorManager;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Processes sensor data recorded in the {@link SensorFusion} class to compute live Pedestrian Dead Reckoning (PDR) estimates.
 * <p>
 * This class computes the user’s position based on detected steps and heading data. It uses either an estimated stride length
 * (calculated with the Weiberg algorithm) or a provided constant value. In addition, it computes elevation changes and attempts
 * to determine the current floor (including detecting elevator use).
 * </p>
 * <p>
 * This version integrates a Kalman Filter to smooth the stride length estimation. When sensor data is invalid,
 * the filter will predict the current value rather than simply returning 0. Sensor data validity is checked before computation.
 * </p>
 *
 * Author: [Your Name]
 */
public class PdrProcessing {

    // -------------------- Static Variables --------------------

    // Empirical constant for the Weiberg stride length estimation algorithm.
    private static final float K = 0.364f;
    //Note by G02: this is override by weibergMinMax func
    // Number of seconds for which elevation samples are stored.
    private static final int elevationSeconds = 4;
    // Number of acceleration samples (e.g., if sampled every 0.01 seconds, total 100 samples).
    private static final int accelSamples = 100;
    // Threshold for detecting significant movement (in m/s^2).
    private static final float movementThreshold = 0.3f;
    // Threshold below which movement is considered negligible.
    private static final float epsilon = 0.18f;

    // -------------------- Instance Variables --------------------

    // SharedPreferences for configuration settings.
    private SharedPreferences settings;

    // Step length value.
    private float stepLength;
    // Flag indicating whether to use a manually specified step length.
    private boolean useManualStep;

    // Current 2D position coordinates (in meters); starting at (0,0).
    private float positionX;
    private float positionY;

    // Variables for vertical movement (elevation) calculation.
    private Float[] startElevationBuffer;
    private float startElevation;
    private int setupIndex = 0;
    private float elevation;
    private int floorHeight;
    private int currentFloor;

    // Circular buffers for storing recent elevation and acceleration values.
    private CircularFloatBuffer elevationList;
    private CircularFloatBuffer verticalAccel;
    private CircularFloatBuffer horizontalAccel;

    // Variables to aggregate step lengths and count the steps (for computing an average step length).
    private float sumStepLength = 0;
    private int stepCount = 0;

    // Kalman Filter for smoothing and predicting step length.
    private KalmanFilter stepLengthFilter;

    // -------------------- Constructor --------------------

    /**
     * Constructs a new PdrProcessing instance.
     *
     * @param context Application context for accessing configuration settings.
     */
    public PdrProcessing(Context context) {
        // Initialize shared preferences.
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        // Check whether manual step length values should be used.
        this.useManualStep = this.settings.getBoolean("manual_step_values", false);
        if (useManualStep) {
            try {
                // Retrieve the manual step length (stored in centimeters; convert to meters).
                this.stepLength = this.settings.getInt("user_step_length", 75) / 100f;
            } catch (Exception e) {
                // If an error occurs, revert to a default value.
                this.stepLength = 0.75f;
                this.settings.edit().putInt("user_step_length", 75).apply();
            }
        } else {
            // For estimated step lengths, initialize to 0.
            this.stepLength = 0;
        }

        // Initialize position and elevation.
        this.positionX = 0f;
        this.positionY = 0f;
        this.elevation = 0f;

        // Initialize buffers for elevation and acceleration based on configuration settings.
        if (this.settings.getBoolean("overwrite_constants", false)) {
            this.elevationList = new CircularFloatBuffer(
                    Integer.parseInt(settings.getString("elevation_seconds", "4")));
            this.verticalAccel = new CircularFloatBuffer(
                    Integer.parseInt(settings.getString("accel_samples", "4")));
            this.horizontalAccel = new CircularFloatBuffer(
                    Integer.parseInt(settings.getString("accel_samples", "4")));
        } else {
            this.elevationList = new CircularFloatBuffer(elevationSeconds);
            this.verticalAccel = new CircularFloatBuffer(accelSamples);
            this.horizontalAccel = new CircularFloatBuffer(accelSamples);
        }

        // Floor height (in meters) is building-specific.
        this.floorHeight = settings.getInt("floor_height", 4);
        // Initialize the start elevation buffer (to collect the first three samples).
        this.startElevationBuffer = new Float[3];
        // Assume the starting floor is 0.
        this.currentFloor = 0;

        // Initialize the Kalman Filter for step length smoothing.
        // For example: initial estimate = 0.75 m, initial covariance = 1, process noise Q = 0.01, measurement noise R = 0.1.
        this.stepLengthFilter = new KalmanFilter(0.75, 1, 0.01, 0.1);
    }

    // -------------------- Public Methods --------------------

    /**
     * Updates the PDR position based on sensor data.
     *
     * @param currentStepEnd         The timestamp (in milliseconds) marking the end of the current step.
     * @param accelMagnitudeOvertime A list of acceleration magnitudes recorded during the current step.
     * @param headingRad             The heading (in radians) relative to magnetic north.
     * @return A float array containing the updated [X, Y] position in meters.
     */
    public float[] updatePdr(long currentStepEnd, List<Double> accelMagnitudeOvertime, float headingRad) {
        // Adjust the heading so that 0 radians points east.
        float adaptedHeading = (float) (Math.PI / 2 - headingRad);

        // Compute the step length. If not using a manual step length, estimate it from sensor data.
        if (!useManualStep) {
            this.stepLength = weibergMinMax(accelMagnitudeOvertime);
        }

        // Aggregate the step length and increment the step count (for later computing an average).
        sumStepLength += stepLength;
        stepCount++;

        // Convert the step length to 2D displacement (polar to Cartesian conversion).
        float x = (float) (stepLength * Math.cos(adaptedHeading));
        float y = (float) (stepLength * Math.sin(adaptedHeading));

        // Update the current position.
        this.positionX += x;
        this.positionY += y;

        return new float[]{this.positionX, this.positionY};
    }

    /**
     * Updates the elevation based on an absolute altitude reading.
     * The starting elevation is determined by taking the median of the first three altitude readings.
     *
     * @param absoluteElevation The absolute altitude in meters.
     * @return The elevation change relative to the starting elevation (in meters).
     */
    public float updateElevation(float absoluteElevation) {
        // If still collecting initial samples, store the reading.
        if (setupIndex < 3) {
            this.startElevationBuffer[setupIndex] = absoluteElevation;
            if (setupIndex == 2) {
                Arrays.sort(startElevationBuffer);
                startElevation = startElevationBuffer[1];
            }
            this.setupIndex++;
        } else {
            // Compute the relative elevation.
            this.elevation = absoluteElevation - startElevation;
            this.elevationList.putNewest(absoluteElevation);

            // When the buffer is full, check if a floor change has occurred.
            if (this.elevationList.isFull()) {
                List<Float> elevationMemory = this.elevationList.getListCopy();
                OptionalDouble currentAvg = elevationMemory.stream().mapToDouble(f -> f).average();
                float finishAvg = currentAvg.isPresent() ? (float) currentAvg.getAsDouble() : 0;
                if (Math.abs(finishAvg - startElevation) > this.floorHeight) {
                    // Update the floor number based on the floor height.
                    this.currentFloor += (finishAvg - startElevation) / this.floorHeight;
                }
            }
            return elevation;
        }
        return 0;
    }

    /**
     * Estimates the step length using the Weiberg algorithm combined with a Kalman Filter for smoothing.
     * When acceleration data is invalid, the Kalman Filter performs only a prediction.
     *
     * @param accelMagnitude A list of acceleration magnitudes for the current step.
     * @return The estimated step length in meters.
     */
    private float weibergMinMax(List<Double> accelMagnitude) {
        // Use a Float for the current measurement for compatibility with the Kalman Filter.
        Float currentMeasurement = null;

        if (accelMagnitude == null || accelMagnitude.isEmpty()) {
            Log.w("PdrProcessing", "Invalid or empty acceleration data; Kalman filter will only perform prediction.");
            // currentMeasurement remains null.
        } else {
            double maxAccel = Collections.max(accelMagnitude);
            double minAccel = Collections.min(accelMagnitude);
            // Compute the "bounce" value as the 0.25th power of (max - min).
            float bounce = (float) Math.pow((maxAccel - minAccel), 0.25);
            if (this.settings.getBoolean("overwrite_constants", false)) {
                currentMeasurement = (float) (bounce * Float.parseFloat(settings.getString("weiberg_k", "0.243")) * 2);
            } else {
                currentMeasurement = bounce * K * 2;
            }
        }

        // Update the Kalman Filter: if currentMeasurement is null, the filter only performs prediction.
        double filteredStepLength = stepLengthFilter.update(
                currentMeasurement != null ? currentMeasurement.doubleValue() : null);
        return (float) filteredStepLength;
    }

    /**
     * Returns the current 2D PDR position.
     *
     * @return A float array containing the [X, Y] coordinates (in meters).
     */
    public float[] getPDRMovement() {
        return new float[]{positionX, positionY};
    }

    /**
     * Returns the current elevation change relative to the starting position.
     *
     * @return The current elevation change (in meters).
     */
    public float getCurrentElevation() {
        return this.elevation;
    }

    /**
     * Returns the current floor number (assuming the starting floor is 0).
     *
     * @return The current floor number.
     */
    public int getCurrentFloor() {
        return this.currentFloor;
    }

    /**
     * Estimates whether the user is in an elevator based on horizontal and vertical acceleration values.
     *
     * @param gravity The gravity vector (x, y, z) from the sensor.
     * @param acc     The acceleration vector (x, y, z) with gravity removed.
     * @return True if the movement pattern indicates an elevator; otherwise, false.
     */
    public boolean estimateElevator(float[] gravity, float[] acc) {
        float g = SensorManager.STANDARD_GRAVITY;
        // Calculate the vertical acceleration magnitude.
        float verticalAcc = (float) Math.sqrt(
                Math.pow((acc[0] * gravity[0] / g), 2) +
                        Math.pow((acc[1] * gravity[1] / g), 2) +
                        Math.pow((acc[2] * gravity[2] / g), 2));
        // Calculate the horizontal acceleration magnitude.
        float horizontalAcc = (float) Math.sqrt(
                Math.pow((acc[0] * (1 - gravity[0] / g)), 2) +
                        Math.pow((acc[1] * (1 - gravity[1] / g)), 2) +
                        Math.pow((acc[2] * (1 - gravity[2] / g)), 2));

        this.verticalAccel.putNewest(verticalAcc);
        this.horizontalAccel.putNewest(horizontalAcc);

        if (this.verticalAccel.isFull() && this.horizontalAccel.isFull()) {
            List<Float> verticalMemory = this.verticalAccel.getListCopy();
            OptionalDouble optVerticalAvg = verticalMemory.stream().mapToDouble(Math::abs).average();
            float verticalAvg = optVerticalAvg.isPresent() ? (float) optVerticalAvg.getAsDouble() : 0;

            List<Float> horizontalMemory = this.horizontalAccel.getListCopy();
            OptionalDouble optHorizontalAvg = horizontalMemory.stream().mapToDouble(Math::abs).average();
            float horizontalAvg = optHorizontalAvg.isPresent() ? (float) optHorizontalAvg.getAsDouble() : 0;

            if (this.settings.getBoolean("overwrite_constants", false)) {
                float eps = Float.parseFloat(settings.getString("epsilon", "0.18"));
                return horizontalAvg < eps && verticalAvg > movementThreshold;
            }
            return horizontalAvg < epsilon && verticalAvg > movementThreshold;
        }
        return false;
    }

    /**
     * Resets all stored data in the PDR processor and reinitializes variables and the Kalman Filter.
     */
    public void resetPDR() {
        this.useManualStep = this.settings.getBoolean("manual_step_values", false);
        if (useManualStep) {
            try {
                this.stepLength = this.settings.getInt("user_step_length", 75) / 100f;
            } catch (Exception e) {
                this.stepLength = 0.75f;
                this.settings.edit().putInt("user_step_length", 75).apply();
            }
        } else {
            this.stepLength = 0;
        }

        this.positionX = 0f;
        this.positionY = 0f;
        this.elevation = 0f;

        if (this.settings.getBoolean("overwrite_constants", false)) {
            this.elevationList = new CircularFloatBuffer(
                    Integer.parseInt(settings.getString("elevation_seconds", "4")));
            this.verticalAccel = new CircularFloatBuffer(
                    Integer.parseInt(settings.getString("accel_samples", "4")));
            this.horizontalAccel = new CircularFloatBuffer(
                    Integer.parseInt(settings.getString("accel_samples", "4")));
        } else {
            this.elevationList = new CircularFloatBuffer(elevationSeconds);
            this.verticalAccel = new CircularFloatBuffer(accelSamples);
            this.horizontalAccel = new CircularFloatBuffer(accelSamples);
        }

        this.floorHeight = settings.getInt("floor_height", 4);
        this.startElevationBuffer = new Float[3];
        this.currentFloor = 0;

        // Reinitialize the Kalman Filter.
        this.stepLengthFilter = new KalmanFilter(0.75, 1, 0.01, 0.1);
    }

    /**
     * Returns the average step length computed from the aggregated step lengths.
     *
     * @return The average step length in meters.
     */
    public float getAverageStepLength() {
        float averageStepLength = sumStepLength / (float) stepCount;
        stepCount = 0;
        sumStepLength = 0;
        return averageStepLength;
    }

    // -------------------- Kalman Filter Implementation --------------------

    /**
     * A simple one-dimensional Kalman Filter used to smooth and predict continuous variables (e.g., step length).
     */
    public class KalmanFilter {
        // Current state estimate (e.g., step length).
        private double x;
        // Current state covariance.
        private double p;
        // Process noise covariance.
        private final double q;
        // Measurement noise covariance.
        private final double r;

        /**
         * Constructs a Kalman Filter.
         *
         * @param initX Initial state estimate.
         * @param initP Initial covariance.
         * @param q     Process noise covariance.
         * @param r     Measurement noise covariance.
         */
        public KalmanFilter(double initX, double initP, double q, double r) {
            this.x = initX;
            this.p = initP;
            this.q = q;
            this.r = r;
        }

        /**
         * Updates the Kalman Filter with a new measurement.
         *
         * @param measurement The new measurement. If null, only the prediction step is performed.
         * @return The updated state estimate.
         */
        public double update(Double measurement) {
            // Prediction step: assume state remains constant; increase covariance by process noise.
            p = p + q;

            if (measurement != null && !Double.isNaN(measurement)) {
                double k = p / (p + r);  // Compute the Kalman gain.
                x = x + k * (measurement - x);
                p = (1 - k) * p;
            } else {
                Log.w("KalmanFilter", "Invalid measurement; performing prediction only.");
            }
            return x;
        }
    }
}
