package com.example.ekf;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;

/**
 * GNSS处理器类，负责对原始GNSS数据进行平滑和过滤
 * 减少GNSS位置跳变，提高定位稳定性
 */
public class GNSSProcessor {
    private static final String TAG = "GNSSProcessor";
    
    // 单例实例
    private static GNSSProcessor instance;
    
    // 最近一次有效的GNSS位置
    private LatLng lastValidPosition = null;
    
    // 上次位置更新时间
    private long lastUpdateTime = 0;
    
    // 平滑位置数组（保存最近的多个位置用于平滑）
    private LatLng[] positionHistory = new LatLng[5];
    private int positionHistoryIndex = 0;
    private int positionHistoryCount = 0;
    
    // 速度限制参数
    private static final double MAX_GNSS_SPEED = 2.5; // 最大允许GNSS速度(米/秒)，稍高于行走速度
    private double currentSpeed = 0.0; // 当前估计速度(米/秒)
    
    // 平滑因子(0-1)，值越小平滑效果越强
    private static final double SMOOTHING_FACTOR = 0.3;
    
    // 跳变检测参数
    private static final double JUMP_THRESHOLD = 2.0; // 位置跳变阈值(米)
    private static final long MIN_UPDATE_INTERVAL = 500; // 最小更新间隔(毫秒)
    
    /**
     * 私有构造函数，遵循单例模式
     */
    private GNSSProcessor() {
        // 初始化
    }
    
    /**
     * 获取GNSSProcessor单例实例
     * @return GNSSProcessor实例
     */
    public static synchronized GNSSProcessor getInstance() {
        if (instance == null) {
            instance = new GNSSProcessor();
        }
        return instance;
    }
    
    /**
     * 处理原始GNSS位置，进行平滑和过滤
     * @param rawPosition 原始GNSS位置
     * @return 处理后的平滑位置，如果原始位置无效或被过滤则返回上一个有效位置
     */
    public LatLng processGNSSPosition(LatLng rawPosition) {
        if (rawPosition == null) {
            return lastValidPosition; // 如果输入无效，返回上一个有效位置
        }
        
        long currentTime = System.currentTimeMillis();
        double timeElapsed = (currentTime - lastUpdateTime) / 1000.0; // 转换为秒
        
        // 如果这是第一个位置，直接接受
        if (lastValidPosition == null) {
            lastValidPosition = rawPosition;
            lastUpdateTime = currentTime;
            
            // 初始化历史记录
            for (int i = 0; i < positionHistory.length; i++) {
                positionHistory[i] = rawPosition;
            }
            positionHistoryCount = 1;
            
            return rawPosition;
        }
        
        // 计算与上一位置的距离
        double distance = calculateDistance(lastValidPosition, rawPosition);
        
        // 更新速度估计（如果时间间隔有效）
        if (timeElapsed > 0.1) { // 避免除以非常小的数
            double instantSpeed = distance / timeElapsed;
            
            // 更新当前速度（简单平滑）
            currentSpeed = 0.7 * currentSpeed + 0.3 * instantSpeed;
            
            // 检测跳变 - 三个条件：
            // 1. 距离超过跳变阈值
            // 2. 速度超过最大速度
            // 3. 时间间隔太短（可能是信号不稳定导致的快速变化）
            if ((distance > JUMP_THRESHOLD && instantSpeed > MAX_GNSS_SPEED) || 
                (distance > 3.0 && timeElapsed < 0.2)) {
                
                Log.d(TAG, "GNSS跳变被检测到: 距离=" + distance + "m, 速度=" + instantSpeed + 
                      "m/s, 时间=" + timeElapsed + "s");
                
                // 对于严重跳变，返回上一个有效位置
                if (distance > JUMP_THRESHOLD * 2) {
                    return lastValidPosition;
                }
                
                // 对于中等跳变，限制移动距离
                double limitedDistance = MAX_GNSS_SPEED * timeElapsed;
                double ratio = limitedDistance / distance;
                
                // 按比例缩小位移
                double latDiff = (rawPosition.latitude - lastValidPosition.latitude) * ratio;
                double lngDiff = (rawPosition.longitude - lastValidPosition.longitude) * ratio;
                
                // 创建限制后的位置
                rawPosition = new LatLng(
                        lastValidPosition.latitude + latDiff,
                        lastValidPosition.longitude + lngDiff
                );
                
                Log.d(TAG, "GNSS位置被限制: " + distance + "m -> " + limitedDistance + "m");
            }
        }
        
        // 更新位置历史
        positionHistory[positionHistoryIndex] = rawPosition;
        positionHistoryIndex = (positionHistoryIndex + 1) % positionHistory.length;
        if (positionHistoryCount < positionHistory.length) {
            positionHistoryCount++;
        }
        
        // 计算平滑后的位置（基于历史位置的加权平均）
        LatLng smoothedPosition = calculateSmoothedPosition();
        
        // 更新状态
        lastValidPosition = smoothedPosition;
        lastUpdateTime = currentTime;
        
        return smoothedPosition;
    }
    
    /**
     * 根据位置历史计算平滑后的位置
     * @return 平滑后的位置
     */
    private LatLng calculateSmoothedPosition() {
        if (positionHistoryCount == 0) {
            return null;
        }
        
        // 使用指数衰减权重计算平滑位置
        double totalWeight = 0;
        double weightedLatSum = 0;
        double weightedLngSum = 0;
        
        // 最新的位置权重最大
        for (int i = 0; i < positionHistoryCount; i++) {
            int index = (positionHistoryIndex - 1 - i + positionHistory.length) % positionHistory.length;
            // 权重随着历史增加而指数衰减
            double weight = Math.pow(0.7, i);
            
            LatLng pos = positionHistory[index];
            weightedLatSum += pos.latitude * weight;
            weightedLngSum += pos.longitude * weight;
            totalWeight += weight;
        }
        
        // 计算加权平均
        double smoothedLat = weightedLatSum / totalWeight;
        double smoothedLng = weightedLngSum / totalWeight;
        
        return new LatLng(smoothedLat, smoothedLng);
    }
    
    /**
     * 重置处理器状态
     * 清除历史位置记录并重置最后有效位置
     */
    public void reset() {
        lastValidPosition = null;
        lastUpdateTime = 0;
        currentSpeed = 0;
        positionHistoryCount = 0;
        positionHistoryIndex = 0;
        // 清空位置历史数组
        for (int i = 0; i < positionHistory.length; i++) {
            positionHistory[i] = null;
        }
        Log.d(TAG, "GNSSProcessor has been reset");
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
} 