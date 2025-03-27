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
    
    /**
     * 私有构造函数，遵循单例模式
     */
    private EKFManager() {
        ekf = new EKF();
        sensorFusion = SensorFusion.getInstance();
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
        if (!isInitialized || !isEkfEnabled || pdrPosition == null) {
            this.lastPdrPosition = pdrPosition;  // 即使未初始化也保存最新的PDR位置
            this.previousHeading = heading;      // 保存最新的航向角
            return;
        }
        
        // 计算与上一个PDR位置之间的步长
        double stepLength = 0;
        if (lastPdrPosition != null) {
            stepLength = calculateDistance(lastPdrPosition, pdrPosition);
        }
        
        // 计算航向变化
        double headingChange = heading - previousHeading;
        // 确保航向变化在-π到π之间
        while (headingChange > Math.PI) headingChange -= 2*Math.PI;
        while (headingChange < -Math.PI) headingChange += 2*Math.PI;
        
        // 更新EKF
        if (stepLength > 0) {
            ekf.predict(stepLength, headingChange);
            Log.d(TAG, "EKF预测更新: 步长=" + stepLength + "m, 航向变化=" + Math.toDegrees(headingChange) + "°");
        }
        
        // 保存当前值为下一次使用
        this.lastPdrPosition = pdrPosition;
        this.previousHeading = heading;
    }
    
    /**
     * 更新GNSS位置
     * @param gnssPosition GNSS位置
     */
    public void updateGnssPosition(LatLng gnssPosition) {
        if (!isInitialized || !isEkfEnabled || gnssPosition == null) {
            this.lastGnssPosition = gnssPosition;  // 即使未初始化也保存最新的GNSS位置
            return;
        }
        
        // 更新EKF使用GNSS数据
        ekf.updateWithGNSS(gnssPosition);
        this.lastGnssPosition = gnssPosition;
        Log.d(TAG, "EKF更新: 使用GNSS位置 " + gnssPosition);
    }
    
    /**
     * 更新WiFi位置
     * @param wifiPosition WiFi位置
     */
    public void updateWifiPosition(LatLng wifiPosition) {
        if (!isInitialized || !isEkfEnabled || wifiPosition == null) {
            this.lastWifiPosition = wifiPosition;  // 即使未初始化也保存最新的WiFi位置
            return;
        }
        
        // 更新EKF使用WiFi数据
        ekf.updateWithWiFi(wifiPosition);
        this.lastWifiPosition = wifiPosition;
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
        return ekf.getFusedPosition();
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
        ekf = new EKF();  // 创建新的EKF实例
        Log.d(TAG, "EKF已重置");
    }
} 