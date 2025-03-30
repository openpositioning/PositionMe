package com.openpositioning.PositionMe;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.SensorManager;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Processes data recorded in the {SensorFusion} class and calculates live PDR estimates.
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
//    private static final float K = 0.364f;
    private static final float K = 0.240f;
    // Number of samples (seconds) to keep as memory for elevation calculation
    private static final int elevationSeconds = 4;
    // Number of samples (0.01 seconds)
    private static final int accelSamples = 100;
    // Threshold used to detect significant movement
    private static final float movementThreshold = 0.3f; // m/s^2
    // Threshold under which movement is considered non-existent
    private static final float epsilon = 0.18f;
    // buffer size to determine the start elevation
    private static final int elevation_buffer_size = 5;
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


        if(this.settings.getBoolean("overwrite_constants", false)) {
            // Capacity - pressure is read with 1Hz - store values of past 10 seconds
            this.elevationList = new CircularFloatBuffer(Integer.parseInt(settings.getString("elevation_seconds", "4")));

            // Buffer for most recent acceleration values
            this.verticalAccel = new CircularFloatBuffer(Integer.parseInt(settings.getString("accel_samples", "4")));
            this.horizontalAccel = new CircularFloatBuffer(Integer.parseInt(settings.getString("accel_samples", "4")));
        }
        else {
            // Capacity - pressure is read with 1Hz - store values of past 10 seconds
            this.elevationList = new CircularFloatBuffer(elevationSeconds);

            // Buffer for most recent acceleration values
            this.verticalAccel = new CircularFloatBuffer(accelSamples);
            this.horizontalAccel = new CircularFloatBuffer(accelSamples);
        }

        // Distance between floors is building dependent, use manual value
        this.floorHeight = 3;
        // Array for holding initial values
        this.startElevationBuffer = new Float[elevation_buffer_size];
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
    public float[] updatePdr(long currentStepEnd, List<Double> accelMagnitudeOvertime, float headingRad) {

        // Change angle so zero rad is east
        float adaptedHeading = (float) (Math.PI/2 - headingRad);

        // check if accelMagnitudeOvertime is empty
        if (accelMagnitudeOvertime == null || accelMagnitudeOvertime.isEmpty()) {
            // return current position, do not update
            return new float[]{this.positionX, this.positionY};
        }
        
        // Calculate step length
        if(!useManualStep) {
            //ArrayList<Double> accelMagnitudeFiltered = filter(accelMagnitudeOvertime);
            // Estimate stride
            this.stepLength = weibergMinMax(accelMagnitudeOvertime);
            // System.err.println("Step Length" + stepLength);
        }

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

    /**
     * Calculates the relative elevation compared to a dynamic reference point.
     * Uses the median of the first few seconds as the start height, then uses sliding window
     * average to detect floor changes. Sea level is irrelevant; all values are relative.
     *
     * @param absoluteElevation absolute elevation in meters (from pressure).
     * @return                  current relative elevation in meters.
     */
    public float updateElevation(float absoluteElevation) {
        // ✅ 1. 异常值过滤：气压异常时转换出的海拔可能是无效的
        if (Float.isNaN(absoluteElevation) || Math.abs(absoluteElevation) > 10000f) {
            return this.elevation;  // 保留上次值，不更新
        }

        // ✅ 2. 初始化阶段：构建初始参考海拔（使用中位数）
        if (setupIndex < elevation_buffer_size) {
            this.startElevationBuffer[setupIndex] = absoluteElevation;
            setupIndex++;

            if (setupIndex == elevation_buffer_size) {
                Arrays.sort(startElevationBuffer);
                // 中位数计算（考虑偶数情况）
                if (elevation_buffer_size % 2 == 1) {
                    startElevation = startElevationBuffer[elevation_buffer_size / 2];
                } else {
                    int mid = elevation_buffer_size / 2;
                    startElevation = (startElevationBuffer[mid - 1] + startElevationBuffer[mid]) / 2f;
                }
                currentFloor = 0;  // 初始化楼层
            }

            return 0;  // 初始阶段默认高度为 0
        }

        // ✅ 3. 正常状态下：计算相对高度
        this.elevation = absoluteElevation - startElevation;

        // ✅ 4. 更新滑动窗口：用于判断是否上下楼
        this.elevationList.putNewest(absoluteElevation);

        if (this.elevationList.isFull()) {
            List<Float> elevationMemory = this.elevationList.getListCopy();
            OptionalDouble currentAvgOpt = elevationMemory.stream().mapToDouble(f -> f).average();
            float currentAvg = currentAvgOpt.isPresent() ? (float) currentAvgOpt.getAsDouble() : startElevation;

            // ✅ 5. 计算与当前楼层参考的高度差
            float delta = currentAvg - startElevation;
            float floorHeight = this.floorHeight;       // 一层楼的高度阈值（如 3.0 米）
            float floorMargin = 0.8f;                   // 容忍误差（防止误判）

            if (Math.abs(delta) > floorHeight + floorMargin) {
                int floorChange = Math.round(delta / floorHeight);
                currentFloor += floorChange;

                // ✅ 6. 更新当前楼层的海拔基准为当前窗口平均值，防止反复触发
                startElevation = currentAvg;

                Log.i("PDR", "Floor changed: now at floor " + currentFloor);
            }
        }

        // ✅ 7. 返回当前相对海拔（供可视化或记录使用）
        return this.elevation;
    }

    private double medianOf3(double a, double b, double c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    /**
     * Uses the Weiberg Stride Length formula to calculate step length from accelerometer values,
     * with added filtering and outlier handling.
     *
     * @param accelMagnitude    Raw acceleration magnitude values between last and current step.
     * @return                  Estimated stride length in meters.
     */
    private float weibergMinMax(List<Double> accelMagnitude) {
        // if the list itself is null or empty, return 0
        if (accelMagnitude == null || accelMagnitude.isEmpty()) {
            return 0f;
        }

        // filter out null values
        List<Double> validAccel = accelMagnitude.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (validAccel.size() < 5) {
            return 0f; // Not enough data to be reliable
        }

        // ✅ Step 1: Apply simple median filter (window size = 3)
        List<Double> filteredAccel = new ArrayList<>();
        for (int i = 0; i < validAccel.size(); i++) {
            if (i == 0 || i == validAccel.size() - 1) {
                filteredAccel.add(validAccel.get(i)); // Keep edges
            } else {
                double median = medianOf3(validAccel.get(i - 1), validAccel.get(i), validAccel.get(i + 1));
                filteredAccel.add(median);
            }
        }

        // ✅ Step 2: Remove top/bottom 5% outliers
        int N = filteredAccel.size();
        List<Double> sorted = new ArrayList<>(filteredAccel);
        Collections.sort(sorted);
        int trim = Math.max(1, N / 20);  // trim 5% (at least 1 point)
        List<Double> trimmed = sorted.subList(trim, N - trim); // Keep middle 90%

        // ✅ Step 3: Calculate bounce from trimmed list
        double maxAccel = Collections.max(trimmed);
        double minAccel = Collections.min(trimmed);
        float bounce = (float) Math.pow((maxAccel - minAccel), 0.25);

        // ✅ Step 4: Preserve original K-setting logic
        if (this.settings.getBoolean("overwrite_constants", false)) {
            return bounce * Float.parseFloat(settings.getString("weiberg_k", "0.934")) * 2;
        }

        return bounce * K * 2;
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

            // calculate average vertical accel
            List<Float> verticalMemory = this.verticalAccel.getListCopy();
            OptionalDouble optVerticalAvg = verticalMemory.stream().mapToDouble(Math::abs).average();
            float verticalAvg = optVerticalAvg.isPresent() ? (float) optVerticalAvg.getAsDouble() : 0;


            // calculate average horizontal accel
            List<Float> horizontalMemory = this.horizontalAccel.getListCopy();
            OptionalDouble optHorizontalAvg = horizontalMemory.stream().mapToDouble(Math::abs).average();
            float horizontalAvg = optHorizontalAvg.isPresent() ? (float) optHorizontalAvg.getAsDouble() : 0;

            //System.err.println("LIFT: Vertical: " + verticalAvg);
            //System.err.println("LIFT: Horizontal: " + horizontalAvg);

            if(this.settings.getBoolean("overwrite_constants", false)) {
                float eps = Float.parseFloat(settings.getString("epsilon", "0.18"));
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

        this.setupIndex = 0; // to reset the elevation to 0, otherwise buffer would be full

        if(this.settings.getBoolean("overwrite_constants", false)) {
            // Capacity - pressure is read with 1Hz - store values of past 10 seconds
            this.elevationList = new CircularFloatBuffer(Integer.parseInt(settings.getString("elevation_seconds", "4")));

            // Buffer for most recent acceleration values
            this.verticalAccel = new CircularFloatBuffer(Integer.parseInt(settings.getString("accel_samples", "4")));
            this.horizontalAccel = new CircularFloatBuffer(Integer.parseInt(settings.getString("accel_samples", "4")));
        }
        else {
            // Capacity - pressure is read with 1Hz - store values of past 10 seconds
            this.elevationList = new CircularFloatBuffer(elevationSeconds);

            // Buffer for most recent acceleration values
            this.verticalAccel = new CircularFloatBuffer(accelSamples);
            this.horizontalAccel = new CircularFloatBuffer(accelSamples);
        }

        // Distance between floors is building dependent, use manual value
        this.floorHeight = settings.getInt("floor_height", 4);
        // Array for holding initial values
        this.startElevationBuffer = new Float[elevation_buffer_size];
        // Start floor - assumed to be zero
        this.currentFloor = 0;
    }

    /**
     * Getter for the average step length calculated from the aggregated distance and step count.
     *
     * @return  average step length in meters.
     */
    public float getAverageStepLength(){
        //Calculate average step length
        float averageStepLength = sumStepLength/(float) stepCount;

        //Reset sum and number of steps
        stepCount = 0;
        sumStepLength = 0;

        //Return average step length
        return averageStepLength;
    }

    public void setCurrentLocation(float x, float y){
        this.positionX = x;
        this.positionY = y;
    }

}