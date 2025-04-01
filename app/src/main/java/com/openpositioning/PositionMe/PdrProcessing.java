package com.openpositioning.PositionMe;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.SensorManager;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;

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
    private static final float K = 0.4f;
    // Number of samples (seconds) to keep as memory for elevation calculation
    private static final int elevationSeconds = 4;
    // Number of samples (0.01 seconds)
    private static final int accelSamples = 100;
    // Threshold used to detect significant movement
    private static final float movementThreshold = 0.28f;
    // Threshold under which movement is considered non-existent
    private static final float epsilon = 0.17f;
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

    // 修改陀螺仪相关变量
    private float[] gyroBuffer = new float[5];  // 减少到5个样本
    private int gyroBufferIndex = 0;
    private float gyroBias = 0;
    private float lastHeading = 0;
    
    // 调整卡尔曼滤波器参数以更快响应变化
    private float headingState = 0;
    private float headingCovariance = 1.0f;
    private static final float Q = 0.2f;  // 增大过程噪声，使滤波器更快响应变化
    private static final float R = 0.1f;  // 保持测量噪声不变

    // WGS84椭球体参数
    private static final double WGS84_A = 6378137.0;  // 长半轴
    private static final double WGS84_F = 1/298.257223563;  // 扁率
    private static final double WGS84_B = WGS84_A * (1 - WGS84_F);  // 短半轴
    private static final double WGS84_E = Math.sqrt(1 - Math.pow(WGS84_B/WGS84_A, 2));  // 第一偏心率

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
        this.floorHeight = settings.getInt("floor_height", 4);
        // Array for holding initial values
        this.startElevationBuffer = new Float[3];
        // Start floor - assumed to be zero
        this.currentFloor = 0;
    }

    /**
     * 将大地坐标转换为ECEF坐标
     * @param lat 纬度（弧度）
     * @param lon 经度（弧度）
     * @param h 大地高（米）
     * @return ECEF坐标 [X, Y, Z]
     */
    private double[] geodetic2ECEF(double lat, double lon, double h) {
        double N = WGS84_A / Math.sqrt(1 - Math.pow(WGS84_E * Math.sin(lat), 2));
        double X = (N + h) * Math.cos(lat) * Math.cos(lon);
        double Y = (N + h) * Math.cos(lat) * Math.sin(lon);
        double Z = (N * (1 - Math.pow(WGS84_E, 2)) + h) * Math.sin(lat);
        return new double[]{X, Y, Z};
    }

    /**
     * 将ECEF坐标转换为ENU局部坐标系
     * @param X ECEF X坐标
     * @param Y ECEF Y坐标
     * @param Z ECEF Z坐标
     * @param refLat 参考点纬度（弧度）
     * @param refLon 参考点经度（弧度）
     * @param refH 参考点大地高（米）
     * @return ENU坐标 [E, N, U]
     */
    private double[] ECEF2ENU(double X, double Y, double Z, double refLat, double refLon, double refH) {
        // 计算参考点的ECEF坐标
        double[] refECEF = geodetic2ECEF(refLat, refLon, refH);
        
        // 计算相对位置
        double dX = X - refECEF[0];
        double dY = Y - refECEF[1];
        double dZ = Z - refECEF[2];
        
        // 转换矩阵
        double sinLat = Math.sin(refLat);
        double cosLat = Math.cos(refLat);
        double sinLon = Math.sin(refLon);
        double cosLon = Math.cos(refLon);
        
        // 计算ENU坐标
        double E = -sinLon * dX + cosLon * dY;
        double N = -sinLat * cosLon * dX - sinLat * sinLon * dY + cosLat * dZ;
        double U = cosLat * cosLon * dX + cosLat * sinLon * dY + sinLat * dZ;
        
        return new double[]{E, N, U};
    }

    /**
     * 更新陀螺仪数据
     * @param gyroZ 陀螺仪Z轴角速度
     */
    public void updateGyro(float gyroZ) {
        // 更新陀螺仪缓冲区
        gyroBuffer[gyroBufferIndex] = gyroZ;
        gyroBufferIndex = (gyroBufferIndex + 1) % gyroBuffer.length;
        
        // 计算陀螺仪零偏
        float sum = 0;
        for (float value : gyroBuffer) {
            sum += value;
        }
        gyroBias = sum / gyroBuffer.length;
    }
    
    /**
     * 卡尔曼滤波处理航向角
     * @param measurement 测量值（来自磁力计）
     * @return 滤波后的航向角
     */
    private float kalmanFilter(float measurement) {
        // 预测步骤
        float predictedState = headingState;  // 假设航向角变化不大
        float predictedCovariance = headingCovariance + Q;
        
        // 更新步骤
        float kalmanGain = predictedCovariance / (predictedCovariance + R);
        headingState = predictedState + kalmanGain * (measurement - predictedState);
        headingCovariance = (1 - kalmanGain) * predictedCovariance;
        
        // 标准化航向角到[-π, π]范围
        while (headingState > Math.PI) headingState -= 2 * Math.PI;
        while (headingState < -Math.PI) headingState += 2 * Math.PI;
        
        return headingState;
    }

    /**
     * 更新PDR坐标，包含WGS84转换
     */
    public float[] updatePdr(long currentStepEnd, List<Double> accelMagnitudeOvertime, float headingRad) {
        // 使用卡尔曼滤波处理航向角
        float filteredHeading = kalmanFilter(headingRad);
        
        // 计算航向角变化
        float headingChange = filteredHeading - lastHeading;
        lastHeading = filteredHeading;
        
        // 限制航向角变化幅度
        if (Math.abs(headingChange) > Math.PI/2) {
            headingChange = (float) (Math.signum(headingChange) * Math.PI/2);
        }
        
        // 使用滤波后的航向角计算位置
        float adaptedHeading = (float) (Math.PI/2 - filteredHeading);
        
        // 计算步长
        if(!useManualStep) {
            this.stepLength = weibergMinMax(accelMagnitudeOvertime);
            // 限制步长范围
            if (this.stepLength < 0.25f) {
                this.stepLength = 0.25f; // 最小步长为0.25米
            } else if (this.stepLength > 0.75f) {
                this.stepLength = 0.75f; // 最大步长为0.75米
            }
        }
        
        // 计算局部ENU坐标系中的位移
        float deltaE = (float) (stepLength * Math.cos(adaptedHeading));
        float deltaN = (float) (stepLength * Math.sin(adaptedHeading));
        
        // 更新位置
        this.positionX += deltaE;
        this.positionY += deltaN;
        
        // 记录步长信息用于调试
        Log.d("PdrProcessing", String.format("步长: %.2fm, 方向: %.1f°, 位移: dX=%.2f, dY=%.2f", 
                stepLength, (float)Math.toDegrees(adaptedHeading), deltaE, deltaN));
        
        return new float[]{this.positionX, this.positionY};
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

            // Check if there was floor movement
            // Check if there is enough data to evaluate
            if(this.elevationList.isFull()) {
                // Check average of elevation array
                List<Float> elevationMemory = this.elevationList.getListCopy();
                OptionalDouble currentAvg = elevationMemory.stream().mapToDouble(f -> f).average();
                float finishAvg = currentAvg.isPresent() ? (float) currentAvg.getAsDouble() : 0;

                // Check if we moved floor by comparing with start position
                if(Math.abs(finishAvg - startElevation) > this.floorHeight) {
                    // Change floors - 'floor' division
                    this.currentFloor += (finishAvg - startElevation)/this.floorHeight;
                }
            }
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
        // 检查列表是否为空，避免应用崩溃
        if (accelMagnitude == null || accelMagnitude.isEmpty()) {
            Log.w("PdrProcessing", "accelMagnitude 列表为空，weibergMinMax 返回默认值 0.0f");
            return 0.0f; // 返回默认步长，避免异常
        }

        // 对加速度列表进行过滤，减少噪声影响
        List<Double> filteredAccelMagnitude = filterAcceleration(accelMagnitude);
        
        double maxAccel = Collections.max(filteredAccelMagnitude);
        double minAccel = Collections.min(filteredAccelMagnitude);
        
        // 最小加速度差异阈值，避免静止状态下微小振动导致的错误步长计算
        double accelThreshold = 0.3;
        if ((maxAccel - minAccel) < accelThreshold) {
            Log.d("PdrProcessing", String.format("加速度差异(%.2f)低于阈值(%.2f)，使用默认步长", 
                    (maxAccel - minAccel), accelThreshold));
            return 0.5f; // 使用默认步长
        }
        
        float bounce = (float) Math.pow((maxAccel - minAccel), 0.25);
        
        // 输出计算过程，便于调试
        Log.d("PdrProcessing", String.format("加速度 - 最大: %.2f, 最小: %.2f, 波动值: %.2f", 
                maxAccel, minAccel, bounce));

        if (this.settings.getBoolean("overwrite_constants", false)) {
            float customK = Float.parseFloat(settings.getString("weiberg_k", "0.934"));
            float stepLen = bounce * customK * 2;
            Log.d("PdrProcessing", "使用自定义K值: " + customK + ", 计算步长: " + stepLen);
            return stepLen;
        }
        
        float stepLen = bounce * K * 2;
        Log.d("PdrProcessing", "使用默认K值: " + K + ", 计算步长: " + stepLen);
        return stepLen;
    }

    /**
     * 过滤加速度数据，减少噪声影响
     * @param accelMagnitude 原始加速度数据列表
     * @return 过滤后的加速度数据列表
     */
    private List<Double> filterAcceleration(List<Double> accelMagnitude) {
        if (accelMagnitude.size() <= 3) {
            return accelMagnitude; // 数据点太少，不进行过滤
        }
        
        List<Double> filtered = new ArrayList<>();
        
        // 使用中值滤波减少异常值影响
        for (int i = 1; i < accelMagnitude.size() - 1; i++) {
            // 取当前点和相邻两点
            List<Double> window = new ArrayList<>();
            window.add(accelMagnitude.get(i-1));
            window.add(accelMagnitude.get(i));
            window.add(accelMagnitude.get(i+1));
            
            // 排序并取中值
            Collections.sort(window);
            filtered.add(window.get(1));
        }
        
        // 添加首尾两点
        if (accelMagnitude.size() > 0) {
            filtered.add(0, accelMagnitude.get(0));
            filtered.add(accelMagnitude.get(accelMagnitude.size() - 1));
        }
        
        return filtered;
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
        this.startElevationBuffer = new Float[3];
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

}
