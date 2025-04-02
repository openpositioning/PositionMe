package com.example.ekf;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.SensorFusion;

/**
 * EKF管理器类，负责协调传感器数据与扩展卡尔曼滤波器(EKF)之间的交互
 * 将SensorFusion中的GNSS、PDR和WiFi数据融合处理
 */
public class EKFManager {
    private static final String TAG = "EKFManager";
    
    // 单例实例
    private static EKFManager instance;
    
    // EKF实例
    private EKF ekf;
    
    // SensorFusion实例
    private SensorFusion sensorFusion;
    
    // 标记EKF是否已初始化
    private boolean isInitialized = false;
    
    // 标记是否启用EKF
    private boolean isEkfEnabled = false;
    
    // 最近一次PDR位置
    private LatLng lastPdrPosition;
    
    // 最近一次GNSS位置
    private LatLng lastGnssPosition;
    
    // 最近一次WiFi位置
    private LatLng lastWifiPosition;
    
    // 上一次步长
    private float previousStepLength = 0;
    
    // 上一次航向角(弧度)
    private float previousHeading = 0;
    
    // 静止状态相关参数
    private static final double STATIC_THRESHOLD = 0.2; // 静止速度阈值(米/秒)
    private static final long STATIC_TIME_THRESHOLD = 2000; // 静止时间阈值(毫秒)
    private boolean isStaticState = false; // 是否处于静止状态
    private long lastMovementTime = 0; // 上次移动的时间
    
    // 位置平滑处理
    private LatLng smoothedPosition = null; // 平滑后的位置
    private static final double SMOOTHING_FACTOR = 0.15; // 降低平滑因子以增强平滑效果
    private LatLng lastFusedPosition = null; // 上次融合后的位置
    
    // 位移限制相关参数
    private static final double MAX_DISPLACEMENT_PER_UPDATE = 1.0; // 降低每次更新最大位移
    private static final long DISPLACEMENT_TIME_WINDOW = 1000; // 增加位移时间窗口
    private long lastDisplacementTime = 0;
    private double accumulatedDisplacement = 0; // 累积位移(米)
    
    // 速度限制参数
    private static final double MAX_SPEED = 2.0; // 最大允许速度(米/秒)
    private double currentSpeed = 0.0; // 当前速度(米/秒)
    private double[] speedHistory = new double[5]; // 速度历史记录，用于平滑
    private int speedHistoryIndex = 0; // 速度历史记录索引
    private int speedHistoryCount = 0; // 速度历史记录计数
    
    // 新增变量
    private long lastGnssUpdateTime = 0; // 上次更新GNSS位置的时间
    private double[] displacementHistory = new double[10]; // 位移历史记录
    private int displacementHistoryIndex = 0; // 位移历史记录索引
    private int displacementHistoryCount = 0; // 位移历史记录计数
    private float lastOrientation = 0; // 上次GNSS的航向角
    
    // WiFi相关参数
    private static final double WIFI_MAX_SPEED = 2.0; // WiFi最大允许速度(米/秒)
    private static final double WIFI_MIN_ACCURACY = 5.0; // WiFi最小精度要求(米)
    private static final long WIFI_MIN_UPDATE_INTERVAL = 2000; // WiFi最小更新间隔(毫秒)
    private long lastWifiUpdateTime = 0;
    
    /**
     * 私有构造函数，遵循单例模式
     */
    private EKFManager() {
        ekf = new EKF();
        sensorFusion = SensorFusion.getInstance();
        
        // 初始化速度历史记录
        for (int i = 0; i < speedHistory.length; i++) {
            speedHistory[i] = 0.0;
        }
        speedHistoryCount = 0;
        speedHistoryIndex = 0;
    }
    
    /**
     * 获取EKFManager单例实例
     * @return EKFManager实例
     */
    public static synchronized EKFManager getInstance() {
        if (instance == null) {
            instance = new EKFManager();
        }
        return instance;
    }
    
    /**
     * 设置EKF是否启用
     * @param enabled 是否启用EKF
     */
    public void setEkfEnabled(boolean enabled) {
        this.isEkfEnabled = enabled;
        Log.d(TAG, "EKF " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 判断EKF是否启用
     * @return EKF是否启用
     */
    public boolean isEkfEnabled() {
        return isEkfEnabled;
    }
    
    /**
     * 初始化EKF
     * @param initialPosition 初始位置
     * @param initialHeading 初始航向角(弧度)
     */
    public void initialize(LatLng initialPosition, float initialHeading) {
        if (initialPosition == null) {
            Log.e(TAG, "无法初始化EKF：初始位置为空");
            return;
        }
        
        // 初始化EKF
        ekf.initialize(initialPosition, initialHeading);
        
        // 设置初始PDR和航向角
        this.lastPdrPosition = initialPosition;
        this.previousHeading = initialHeading;
        this.smoothedPosition = initialPosition;
        this.lastFusedPosition = initialPosition;
        
        // 重置位移和速度相关参数
        this.lastDisplacementTime = System.currentTimeMillis();
        this.accumulatedDisplacement = 0;
        this.currentSpeed = 0;
        for (int i = 0; i < speedHistory.length; i++) {
            speedHistory[i] = 0.0;
        }
        
        // 标记为已初始化
        isInitialized = true;
        Log.d(TAG, "EKF已初始化，初始位置: " + initialPosition + "，初始航向: " + Math.toDegrees(initialHeading) + "°");
    }
    
    /**
     * 使用当前的GNSS位置初始化EKF
     * @param initialHeading 初始航向角(弧度)
     * @return 是否成功初始化
     */
    public boolean initializeWithGnss(float initialHeading) {
        // 从SensorFusion获取当前GNSS位置
        float[] gnssLatLng = sensorFusion.getGNSSLatitude(false);
        
        if (gnssLatLng != null && gnssLatLng.length >= 2 && gnssLatLng[0] != 0 && gnssLatLng[1] != 0) {
            LatLng initialPosition = new LatLng(gnssLatLng[0], gnssLatLng[1]);
            initialize(initialPosition, initialHeading);
            return true;
        } else {
            Log.e(TAG, "无法使用GNSS初始化EKF：GNSS数据无效");
            return false;
        }
    }
    
    /**
     * 更新PDR位置
     * @param pdrPosition 新的PDR位置
     * @param heading 当前航向角(弧度)
     */
    public void updatePdrPosition(LatLng pdrPosition, float heading) {
        if (!isEkfEnabled || !isInitialized || pdrPosition == null) {
            return;
        }

        // 计算时间间隔
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - lastDisplacementTime;
        
        // 如果PDR位置没有变化，更新静止状态计数
        if (lastPdrPosition != null && 
            Math.abs(pdrPosition.latitude - lastPdrPosition.latitude) < 1e-8 && 
            Math.abs(pdrPosition.longitude - lastPdrPosition.longitude) < 1e-8) {
            
            if (currentTime - lastMovementTime > STATIC_TIME_THRESHOLD) {
                isStaticState = true;
                return;  // 静止状态下不进行预测更新
            }
        } else {
            lastMovementTime = currentTime;
            isStaticState = false;
        }

        // 计算步长
        double stepLength = 0;
        if (lastPdrPosition != null) {
            stepLength = calculateDistance(lastPdrPosition, pdrPosition);
        }

        // 计算航向角变化
        float headingChange = heading - previousHeading;
        // 标准化航向角变化到[-π, π]
        while (headingChange > Math.PI) headingChange -= 2 * Math.PI;
        while (headingChange < -Math.PI) headingChange += 2 * Math.PI;

        // 只有在非静止状态且有实际位移时才进行预测更新
        if (stepLength > 0 && !isStaticState && timeDiff >= DISPLACEMENT_TIME_WINDOW) {
            // 限制步长，防止突变
            double limitedStepLength = Math.min(stepLength, MAX_DISPLACEMENT_PER_UPDATE);
            if (stepLength > MAX_DISPLACEMENT_PER_UPDATE) {
                Log.d(TAG, "PDR步长被限制: " + stepLength + "m -> " + limitedStepLength + "m");
            }
            
            ekf.predict(limitedStepLength, headingChange);
            Log.d(TAG, "EKF预测更新: 步长=" + limitedStepLength + "m, 航向变化=" + Math.toDegrees(headingChange) + "°");
            
            // 更新时间戳
            lastDisplacementTime = currentTime;
        }
        
        // 保存当前值为下一次使用
        this.lastPdrPosition = pdrPosition;
        this.previousHeading = heading;
    }
    
    /**
     * 更新来自GNSS的位置
     * 已经由GNSSProcessor预处理，减少了跳变
     * @param gnssLocation GNSS的位置(LatLng格式)
     */
    public void updateGnssPosition(LatLng gnssLocation) {
        if (gnssLocation == null) return;
        
        // 计算与上一个GNSS位置的位移
        double displacement = 0;
        double speed = 0;
        
        if (lastGnssPosition != null) {
            long currentTime = System.currentTimeMillis();
            double timeDelta = (currentTime - lastGnssUpdateTime) / 1000.0; // 转为秒
            
            // 计算位移
            displacement = calculateDistance(lastGnssPosition, gnssLocation);
            
            // 计算速度
            if (timeDelta > 0.1) { // 避免除以很小的数
                speed = displacement / timeDelta;
            }
            
            // 更新当前速度估计（使用加权平均进行平滑）
            currentSpeed = 0.7 * currentSpeed + 0.3 * speed;
            
            // 记录速度历史
            speedHistory[speedHistoryIndex] = speed;
            speedHistoryIndex = (speedHistoryIndex + 1) % speedHistory.length;
            if (speedHistoryCount < speedHistory.length) {
                speedHistoryCount++;
            }
        }
        
        // 应用位移限制（已经由GNSSProcessor处理，这里作为额外保障）
        if (displacement > MAX_DISPLACEMENT_PER_UPDATE) {
            Log.d(TAG, "EKF: GNSS displacement exceeds limit: " + displacement + "m > " + 
                  MAX_DISPLACEMENT_PER_UPDATE + "m - applying limit");
            
            // 已经由GNSSProcessor平滑处理过，这里的限制作用更小
            double ratio = MAX_DISPLACEMENT_PER_UPDATE / displacement;
            double latDiff = (gnssLocation.latitude - lastGnssPosition.latitude) * ratio;
            double lonDiff = (gnssLocation.longitude - lastGnssPosition.longitude) * ratio;
            
            gnssLocation = new LatLng(
                    lastGnssPosition.latitude + latDiff,
                    lastGnssPosition.longitude + lonDiff
            );
        }
        
        // 更新EKF中的GNSS位置
        lastGnssPosition = gnssLocation;
        lastGnssUpdateTime = System.currentTimeMillis();
        
        // 记录位移历史（用于统计分析）
        displacementHistory[displacementHistoryIndex] = displacement;
        displacementHistoryIndex = (displacementHistoryIndex + 1) % displacementHistory.length;
        if (displacementHistoryCount < displacementHistory.length) {
            displacementHistoryCount++;
        }
        
        // 如果EKF已初始化，对接收到的GNSS位置进行更新
        if (isInitialized) {
            ekf.update_gnss(gnssLocation.latitude, gnssLocation.longitude);
        } else {
            // 如果EKF未初始化，且GNSS接收到有效位置，则初始化EKF
            ekf.initialize(new LatLng(gnssLocation.latitude, gnssLocation.longitude), (double)lastOrientation);
            isInitialized = true;
        }
    }
    
    /**
     * 更新WiFi位置
     * @param wifiPosition WiFi位置
     */
    public void updateWifiPosition(LatLng wifiPosition) {
        if (!isInitialized || !isEkfEnabled || wifiPosition == null) {
            this.lastWifiPosition = wifiPosition;
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        // 检查更新间隔
        if (currentTime - lastWifiUpdateTime < WIFI_MIN_UPDATE_INTERVAL) {
            return;
        }
        
        // 如果处于静止状态，减小WiFi更新的影响
        if (isStaticState) {
            if (Math.random() > 0.05) { // 静止时仅使用5%的WiFi数据
                return;
            }
        }
        
        // 检查WiFi位置跳变
        if (lastWifiPosition != null) {
            double distance = calculateDistance(lastWifiPosition, wifiPosition);
            double timeElapsed = (currentTime - lastWifiUpdateTime) / 1000.0;
            
            if (timeElapsed > 0) {
                double wifiSpeed = distance / timeElapsed;
                
                // 如果WiFi速度超过阈值，可能是位置跳变
                if (wifiSpeed > WIFI_MAX_SPEED) {
                    Log.d(TAG, "检测到WiFi位置跳变: " + wifiSpeed + "m/s > " + WIFI_MAX_SPEED + "m/s，跳过此次更新");
                    return;
                }
                
                // 如果位移过大，进行线性插值
                if (distance > MAX_DISPLACEMENT_PER_UPDATE) {
                    double ratio = MAX_DISPLACEMENT_PER_UPDATE / distance;
                    double interpolatedLat = lastWifiPosition.latitude + (wifiPosition.latitude - lastWifiPosition.latitude) * ratio;
                    double interpolatedLng = lastWifiPosition.longitude + (wifiPosition.longitude - lastWifiPosition.longitude) * ratio;
                    wifiPosition = new LatLng(interpolatedLat, interpolatedLng);
                }
            }
        }
        
        // 更新EKF使用WiFi数据
        ekf.updateWithWiFi(wifiPosition);
        this.lastWifiPosition = wifiPosition;
        this.lastWifiUpdateTime = currentTime;
        
        Log.d(TAG, "EKF更新: 使用WiFi位置 " + wifiPosition);
    }
    
    /**
     * 获取融合后的位置
     * @return 融合后的位置 (如果EKF未启用或未初始化，则返回null)
     */
    public LatLng getFusedPosition() {
        if (!isInitialized || !isEkfEnabled) {
            return null;
        }
        
        // 获取EKF原始融合结果
        LatLng rawPosition = ekf.getFusedPosition();
        if (rawPosition == null) {
            return null;
        }
        
        // 应用位移限制
        LatLng limitedPosition = rawPosition;
        if (lastFusedPosition != null) {
            double distance = calculateDistance(lastFusedPosition, rawPosition);
            long currentTime = System.currentTimeMillis();
            double timeElapsed = (currentTime - lastDisplacementTime) / 1000.0; // 秒
            
            if (timeElapsed > 0) {
                // 计算当前速度
                double speed = distance / timeElapsed;
                
                // 如果速度超过最大限制，进行限制
                if (speed > MAX_SPEED) {
                    // 计算限制后的距离
                    double limitedDistance = MAX_SPEED * timeElapsed;
                    
                    // 按比例缩小位移
                    double ratio = limitedDistance / distance;
                    double latDiff = (rawPosition.latitude - lastFusedPosition.latitude) * ratio;
                    double lngDiff = (rawPosition.longitude - lastFusedPosition.longitude) * ratio;
                    
                    // 创建限制后的位置
                    limitedPosition = new LatLng(
                            lastFusedPosition.latitude + latDiff,
                            lastFusedPosition.longitude + lngDiff
                    );
                    
                    Log.d(TAG, "位置跳变被限制: " + speed + "m/s -> " + MAX_SPEED + "m/s");
                }
            }
            
            // 更新时间
            lastDisplacementTime = currentTime;
        }
        
        // 应用位置平滑
        if (smoothedPosition == null) {
            smoothedPosition = limitedPosition;
        } else {
            // 指数平滑公式: newValue = α * currentValue + (1-α) * previousValue
            double smoothFactor = isStaticState ? 0.05 : SMOOTHING_FACTOR; // 静止时使用更强的平滑效果
            
            double lat = smoothFactor * limitedPosition.latitude + (1 - smoothFactor) * smoothedPosition.latitude;
            double lng = smoothFactor * limitedPosition.longitude + (1 - smoothFactor) * smoothedPosition.longitude;
            
            smoothedPosition = new LatLng(lat, lng);
        }
        
        // 更新上次融合位置
        lastFusedPosition = smoothedPosition;
        
        return smoothedPosition;
    }
    
    /**
     * 计算两个LatLng点之间的距离（米）
     * @param point1 第一个点
     * @param point2 第二个点
     * @return 两点之间的距离（米）
     */
    private double calculateDistance(LatLng point1, LatLng point2) {
        if (point1 == null || point2 == null) {
            return 0;
        }
        
        // 地球半径（米）
        final double R = 6371000;
        
        // 将经纬度转换为弧度
        double lat1 = Math.toRadians(point1.latitude);
        double lon1 = Math.toRadians(point1.longitude);
        double lat2 = Math.toRadians(point2.latitude);
        double lon2 = Math.toRadians(point2.longitude);
        
        // 计算差值
        double dLat = lat2 - lat1;
        double dLon = lon2 - lon1;
        
        // 应用Haversine公式
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                   Math.cos(lat1) * Math.cos(lat2) *
                   Math.sin(dLon/2) * Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        double distance = R * c;
        
        return distance;
    }
    
    /**
     * 重置EKF
     */
    public void reset() {
        isInitialized = false;
        lastPdrPosition = null;
        lastGnssPosition = null;
        lastWifiPosition = null;
        previousHeading = 0;
        previousStepLength = 0;
        isStaticState = false;
        lastMovementTime = 0;
        smoothedPosition = null;
        lastFusedPosition = null;
        lastDisplacementTime = 0;
        accumulatedDisplacement = 0;
        currentSpeed = 0;
        
        // 重置速度历史
        for (int i = 0; i < speedHistory.length; i++) {
            speedHistory[i] = 0.0;
        }
        speedHistoryIndex = 0;
        speedHistoryCount = 0;
        
        ekf = new EKF();  // 创建新的EKF实例
        Log.d(TAG, "EKF已重置");
    }
} 