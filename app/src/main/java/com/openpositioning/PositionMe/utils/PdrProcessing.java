package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.SensorManager;

import androidx.preference.PreferenceManager;

import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Processes data recorded in the {@link SensorFusion} class and calculates live PDR estimates.
 * It calculates the position from the steps and directions detected, using either estimated values
 * (eg. stride length from the Weiberg algorithm) or provided constants, calculates the elevation
 * and attempts to estimate the current floor as well as elevators.
 *
 * @author Mate Stodulka
 * @author Michal Dvorak
 */
public class PdrProcessing {

    //region Static variables
    // Weiberg algorithm coefficient for stride calculations
    private static final float K = 0.364f;
    // Number of samples (seconds) to keep as memory for elevation calculation
    private static final int elevationSeconds = 4;
    // Number of samples (0.01 seconds)
    private static final int accelSamples = 100;
    private static final int MIN_FLOOR_HEIGHT_METERS = 4;
    // Threshold used to detect significant movement
    private static final float movementThreshold = 0.3f; // m/s^2
    // Threshold under which movement is considered non-existent
    private static final float epsilon = 0.18f;
    private static final int MIN_REQUIRED_SAMPLES = 2;
    private static final float DEFAULT_FALLBACK_STEP_METERS = 0.70f;
    private static final float MIN_STEP_METERS = 0.30f;
    private static final float MAX_STEP_METERS = 1.25f;
    private static final long MIN_CADENCE_INTERVAL_MS = 280L;
    private static final long MAX_CADENCE_INTERVAL_MS = 2400L;  // was 1600L — extend cadence model to cover slow walking (~0.4 Hz)
    private static final float MIN_CADENCE_HZ = 0.45f;          // was 0.75f — consistent with above
    private static final float MAX_CADENCE_HZ = 2.85f;
    private static final float CADENCE_REFERENCE_HZ = 1.80f;
    private static final float CADENCE_STEP_GAIN = 0.18f;
    private static final float STEP_SMOOTHING_ALPHA = 0.34f;
    //endregion

    //region Instance variables
    // Settings for accessing shared variables
    private SharedPreferences settings;

    // Step length
    private float stepLength;
    // Using manually input constants instead of estimated values
    private boolean useManualStep;

    // Current 2D position coordinates
    private float positionX;
    private float positionY;

    // Vertical movement calculation
    private Float[] startElevationBuffer;
    private float startElevation;
    private int setupIndex = 0;
    private float elevation;
    private int floorHeight;
    private int currentFloor;

    // Buffer of most recent elevations calculated
    private CircularFloatBuffer elevationList;

    // Buffer for most recent directional acceleration magnitudes
    private CircularFloatBuffer verticalAccel;
    private CircularFloatBuffer horizontalAccel;

    // Step sum and length aggregation variables
    private float sumStepLength = 0;
    private int stepCount = 0;
    //endregion

    /**
     * Public constructor for the PDR class.
     * Takes context for variable access. Sets initial values based on settings.
     *
     * @param context   Application context for variable access.
     */
    public PdrProcessing(Context context) {
        // Initialise settings
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        // Check if estimate or manual values should be used
        this.useManualStep = this.settings.getBoolean("manual_step_values", false);
        if(useManualStep) {
            try {
                // Retrieve manual step  length
                this.stepLength = this.settings.getInt("user_step_length", 75) / 100f;
            } catch (Exception e) {
                // Invalid values - reset to defaults
                this.stepLength = 0.75f;
                this.settings.edit().putInt("user_step_length", 75).apply();
            }
        }
        else {
            // Using estimated step length - set to zero
            this.stepLength = 0;
        }

        // Initial position and elevation - starts from zero
        this.positionX = 0f;
        this.positionY = 0f;
        this.elevation = 0f;
        initialiseMotionAndElevationBuffers();

        // Distance between floors is building dependent, use manual value
        this.floorHeight = getConfiguredFloorHeightMeters();
        // Array for holding initial values
        this.startElevationBuffer = new Float[3];
        // Start floor - assumed to be zero
        this.currentFloor = 0;
    }

    /**
     * Function to calculate PDR coordinates from sensor values.
     * Should be called from the step detector sensor's event with the sensor values since the last
     * step.
     *
     * @param currentStepEnd            relative time in milliseconds since the start of the recording.
     * @param accelMagnitudeOvertime    recorded acceleration magnitudes since the last step.
     * @param headingRad                heading relative to magnetic north in radians.
     */
    public float[] updatePdr(long currentStepEnd,
                             List<Double> accelMagnitudeOvertime,
                             float headingRad,
                             long stepIntervalMs) {
        // Change angle so zero rad is east
        float safeHeading = (Float.isNaN(headingRad) || Float.isInfinite(headingRad)) ? 0f : headingRad;
        float adaptedHeading = (float) (Math.PI/2 - safeHeading);

        float candidateStep = this.stepLength;
        boolean hasAccelWindow = accelMagnitudeOvertime != null
                && accelMagnitudeOvertime.size() >= MIN_REQUIRED_SAMPLES;

        // Calculate step length
        if (!useManualStep) {
            float cadenceStep = estimateCadenceAdaptiveStep(stepIntervalMs);
            if (hasAccelWindow) {
                float weibergStep = weibergMinMax(accelMagnitudeOvertime);
                candidateStep = blendStrideEstimate(weibergStep, cadenceStep, accelMagnitudeOvertime);
            } else {
                candidateStep = cadenceStep;
            }
        } else if (candidateStep <= 0f) {
            candidateStep = resolveFallbackStepLength();
        }
        this.stepLength = smoothStepEstimate(candidateStep);

        // Increment aggregate variables
        sumStepLength += stepLength;
        stepCount++;

        // Translate to cartesian coordinate system
        float x = (float) (stepLength * Math.cos(adaptedHeading));
        float y = (float) (stepLength * Math.sin(adaptedHeading));

        // Update position values
        this.positionX += x;
        this.positionY += y;

        // return current position
        return new float[]{this.positionX, this.positionY};
    }

    private float blendStrideEstimate(float weibergStep,
                                      float cadenceStep,
                                      List<Double> accelMagnitudeOvertime) {
        float safeWeibergStep = clamp(
                weibergStep > 0f ? weibergStep : resolveFallbackStepLength(),
                MIN_STEP_METERS,
                MAX_STEP_METERS
        );
        float safeCadenceStep = clamp(
                cadenceStep > 0f ? cadenceStep : resolveFallbackStepLength(),
                MIN_STEP_METERS,
                MAX_STEP_METERS
        );

        float accelPeak = computePeakAcceleration(accelMagnitudeOvertime);
        float accelConfidence = 0f;
        if (accelPeak > 0f) {
            accelConfidence = clamp((accelPeak - 0.7f) / 1.8f, 0f, 1f);
        }

        float cadenceWeight = 0.32f - 0.18f * accelConfidence;
        cadenceWeight = clamp(cadenceWeight, 0.12f, 0.32f);
        return safeWeibergStep * (1f - cadenceWeight) + safeCadenceStep * cadenceWeight;
    }

    private float estimateCadenceAdaptiveStep(long stepIntervalMs) {
        float baseStep = this.stepLength > 0f ? this.stepLength : resolveFallbackStepLength();
        if (stepIntervalMs < MIN_CADENCE_INTERVAL_MS || stepIntervalMs > MAX_CADENCE_INTERVAL_MS) {
            return clamp(baseStep, MIN_STEP_METERS, MAX_STEP_METERS);
        }

        float cadenceHz = 1000f / Math.max(1f, (float) stepIntervalMs);
        cadenceHz = clamp(cadenceHz, MIN_CADENCE_HZ, MAX_CADENCE_HZ);
        float cadenceDelta = clamp(cadenceHz - CADENCE_REFERENCE_HZ, -0.90f, 0.90f);
        float cadenceScale = 1f + CADENCE_STEP_GAIN * cadenceDelta;
        return clamp(baseStep * cadenceScale, MIN_STEP_METERS, MAX_STEP_METERS);
    }

    private float smoothStepEstimate(float candidateStep) {
        float safeCandidateStep = clamp(candidateStep, MIN_STEP_METERS, MAX_STEP_METERS);
        if (useManualStep) {
            return safeCandidateStep;
        }
        float previousStep = this.stepLength > 0f ? this.stepLength : resolveFallbackStepLength();
        float smoothedStep = previousStep + STEP_SMOOTHING_ALPHA * (safeCandidateStep - previousStep);
        return clamp(smoothedStep, MIN_STEP_METERS, MAX_STEP_METERS);
    }

    private float resolveFallbackStepLength() {
        if (this.stepLength > 0f) {
            return this.stepLength;
        }
        try {
            int userStepCm = this.settings.getInt("user_step_length", 75);
            if (userStepCm > 20) {
                return userStepCm / 100f;
            }
        } catch (Exception ignored) {
        }
        return DEFAULT_FALLBACK_STEP_METERS;
    }

    /**
     * Calculates the relative elevation compared to the start position.
     * The start elevation is the median of the first three seconds of data to give the sensor time
     * to settle. The sea level is irrelevant as only values relative to the initial position are
     * reported.
     *
     * @param absoluteElevation absolute elevation in meters compared to sea level.
     * @return                  current elevation in meters relative to the start position.
     */
    public float updateElevation(float absoluteElevation) {
        // Set start to median of first three values
        if(setupIndex < 3) {
            // Add values to buffer until it's full
            this.startElevationBuffer[setupIndex] = absoluteElevation;
            // When buffer is full, find median, assign as startElevation
            if(setupIndex == 2) {
                Arrays.sort(startElevationBuffer);
                startElevation = startElevationBuffer[1];
            }
            this.setupIndex++;
        }
        else {
            // Get relative elevation in meters
            this.elevation = absoluteElevation - startElevation;
            // Add to buffer
            this.elevationList.putNewest(absoluteElevation);
            // Return current elevation
            return elevation;
        }
        // Keep elevation at zero if there is no calculated value
        return 0;
    }

    /**
     * Uses the Weiberg Stride Length formula to calculate step length from accelerometer values.
     *
     * @param accelMagnitude    magnitude of acceleration values between the last and current step.
     * @return                  float stride length in meters.
     */
    private float weibergMinMax(List<Double> accelMagnitude) {
        // if the list itself is null or empty, return 0 (or return other default values as needed)
        if (accelMagnitude == null || accelMagnitude.isEmpty()) {
            return 0f;
        }

        // filter out null values from the list
        List<Double> validAccel = accelMagnitude.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (validAccel.isEmpty()) {
            return 0f;
        }

        // calculate max and min values
        double maxAccel = Collections.max(validAccel);
        double minAccel = Collections.min(validAccel);

        // calculate bounce
        float bounce = (float) Math.pow((maxAccel - minAccel), 0.25);

        // determine which constant to use based on settings
        if (this.settings.getBoolean("overwrite_constants", false)) {
            return bounce * getPositiveFloatPreference("weiberg_k", K) * 2;
        }

        return bounce * K * 2;
    }

    private float computePeakAcceleration(List<Double> accelMagnitude) {
        if (accelMagnitude == null || accelMagnitude.isEmpty()) {
            return 0f;
        }
        double peak = 0.0;
        for (Double sample : accelMagnitude) {
            if (sample == null || Double.isNaN(sample) || Double.isInfinite(sample)) {
                continue;
            }
            peak = Math.max(peak, Math.abs(sample));
        }
        return (float) peak;
    }

    /**
     * Get the current X and Y coordinates from the PDR processing class.
     * The coordinates are in meters, the start of the recording is the (0,0)
     *
     * @return  float array of size 2, with the X and Y coordinates respectively.
     */
    public float[] getPDRMovement() {
        float [] pdrPosition= new float[] {positionX,positionY};
        return pdrPosition;

    }

    /**
     * Get the current elevation as calculated by the PDR class.
     *
     * @return  current elevation in meters, relative to the start position.
     */
    public float getCurrentElevation() {
        return this.elevation;
    }

    /**
     * Get the current floor number as estimated by the PDR class.
     *
     * @return current floor number, assuming start position is on level zero.
     */
    public int getCurrentFloor() {
        return this.currentFloor;
    }

    /**
     * Estimates if the user is currently taking an elevator.
     * From the gravity and gravity-removed acceleration values the magnitude of horizontal and
     * vertical acceleration is calculated and stored over time. Averaging these values and
     * comparing with the thresholds set for this class, it estimates if the current movement
     * matches what is expected from an elevator ride.
     *
     * @param gravity   array of size three, strength of gravity along the phone's x-y-z axis.
     * @param acc       array of size three, acceleration other than gravity detected by the phone.
     * @return          boolean true if currently in an elevator, false otherwise.
     */
    public boolean estimateElevator(float[] gravity, float[] acc) {
        if (gravity == null || acc == null || gravity.length < 3 || acc.length < 3) {
            return false;
        }
        // Standard gravity
        float g = SensorManager.STANDARD_GRAVITY;
        // get horizontal and vertical acceleration magnitude
        float verticalAcc = (float) Math.sqrt(
                Math.pow((acc[0] * gravity[0]/g),2) +
                Math.pow((acc[1] * gravity[1]/g), 2) +
                Math.pow((acc[2] * gravity[2]/g), 2));
        float horizontalAcc = (float) Math.sqrt(
                Math.pow((acc[0] * (1 - gravity[0]/g)), 2) +
                Math.pow((acc[1] * (1 - gravity[1]/g)), 2) +
                Math.pow((acc[2] * (1 - gravity[2]/g)), 2));
        // Save into buffer to compare with past values
        this.verticalAccel.putNewest(verticalAcc);
        this.horizontalAccel.putNewest(horizontalAcc);
        // Once buffer is full, evaluate data
        if(this.verticalAccel.isFull() && this.horizontalAccel.isFull()) {
            CircularFloatBuffer.SnapshotStats verticalStats = this.verticalAccel.getSnapshotStats();
            CircularFloatBuffer.SnapshotStats horizontalStats = this.horizontalAccel.getSnapshotStats();
            float verticalAvg = verticalStats.averageAbs;
            float horizontalAvg = horizontalStats.averageAbs;

            //System.err.println("LIFT: Vertical: " + verticalAvg);
            //System.err.println("LIFT: Horizontal: " + horizontalAvg);

            if(this.settings.getBoolean("overwrite_constants", false)) {
                float eps = getNonNegativeFloatPreference("epsilon", epsilon);
                return horizontalAvg < eps && verticalAvg > movementThreshold;
            }
            // Check if there is minimal horizontal and significant vertical movement
            return horizontalAvg < epsilon && verticalAvg > movementThreshold;
        }
        return false;

    }

    /**
     * Resets all values stored in the PDR function and re-initialises all buffers.
     * Used to reset to zero position and remove existing history.
     */
    public void resetPDR() {
        // Check if estimate or manual values should be used
        this.useManualStep = this.settings.getBoolean("manual_step_values", false);
        if(useManualStep) {
            try {
                // Retrieve manual step  length
                this.stepLength = this.settings.getInt("user_step_length", 75) / 100f;
            } catch (Exception e) {
                // Invalid values - reset to defaults
                this.stepLength = 0.75f;
                this.settings.edit().putInt("user_step_length", 75).apply();
            }
        }
        else {
            // Using estimated step length - set to zero
            this.stepLength = 0;
        }

        // Initial position and elevation - starts from zero
        this.positionX = 0f;
        this.positionY = 0f;
        this.elevation = 0f;

        initialiseMotionAndElevationBuffers();

        // Distance between floors is building dependent, use manual value
        this.floorHeight = getConfiguredFloorHeightMeters();
        // Array for holding initial values
        this.startElevationBuffer = new Float[3];
        // Start floor - assumed to be zero
        this.currentFloor = 0;
    }

    private void initialiseMotionAndElevationBuffers() {
        if (this.settings.getBoolean("overwrite_constants", false)) {
            this.elevationList = new CircularFloatBuffer(
                    getPositiveIntPreference("elevation_seconds", elevationSeconds)
            );
            int configuredAccelSamples = getPositiveIntPreference("accel_samples", accelSamples);
            this.verticalAccel = new CircularFloatBuffer(configuredAccelSamples);
            this.horizontalAccel = new CircularFloatBuffer(configuredAccelSamples);
            return;
        }

        this.elevationList = new CircularFloatBuffer(elevationSeconds);
        this.verticalAccel = new CircularFloatBuffer(accelSamples);
        this.horizontalAccel = new CircularFloatBuffer(accelSamples);
    }

    private int getConfiguredFloorHeightMeters() {
        return Math.max(MIN_FLOOR_HEIGHT_METERS, settings.getInt("floor_height", MIN_FLOOR_HEIGHT_METERS));
    }

    private int getPositiveIntPreference(String key, int defaultValue) {
        try {
            String value = settings.getString(key, Integer.toString(defaultValue));
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : defaultValue;
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    private float getPositiveFloatPreference(String key, float defaultValue) {
        try {
            String value = settings.getString(key, Float.toString(defaultValue));
            float parsed = Float.parseFloat(value);
            return parsed > 0f ? parsed : defaultValue;
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    private float getNonNegativeFloatPreference(String key, float defaultValue) {
        try {
            String value = settings.getString(key, Float.toString(defaultValue));
            float parsed = Float.parseFloat(value);
            return parsed >= 0f ? parsed : defaultValue;
        } catch (RuntimeException e) {
            return defaultValue;
        }
    }

    /**
     * Getter for the average step length calculated from the aggregated distance and step count.
     *
     * @return  average step length in meters.
     */
    public float getAverageStepLength(){
        if (stepCount <= 0) {
            return stepLength > 0f ? stepLength : DEFAULT_FALLBACK_STEP_METERS;
        }
        //Calculate average step length
        float averageStepLength = sumStepLength/(float) stepCount;

        //Reset sum and number of steps
        stepCount = 0;
        sumStepLength = 0;

        //Return average step length
        return averageStepLength;
    }

    private float clamp(float value, float min, float max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

}
