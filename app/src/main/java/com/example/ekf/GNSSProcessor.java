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
    
    // 平滑位置数组（保存最近的多个位置用于平滑）- 增加历史长度
    private LatLng[] positionHistory = new LatLng[10]; // 增加到10个点
    private int positionHistoryIndex = 0;
    private int positionHistoryCount = 0;
    
    // 速度限制参数
    private static final double MAX_GNSS_SPEED = 2.5; // 最大允许GNSS速度(米/秒)，稍高于行走速度
    private double currentSpeed = 0.0; // 当前估计速度(米/秒)
    
    // 历史方向数组 (用于检测方向变化)
    private double[] directionHistory = new double[5];
    private int directionHistoryIndex = 0;
    private int directionHistoryCount = 0;
    
    // 平滑因子(0-1)，值越小平滑效果越强
    private static final double SMOOTHING_FACTOR = 0.15;
    
    // 跳变检测参数
    private static final double JUMP_THRESHOLD = 1.5; // 降低位置跳变阈值(米)
    private static final double OSCILLATION_THRESHOLD = 0.8; // 降低摆动检测阈值(米)
    private static final long MIN_UPDATE_INTERVAL = 500; // 最小更新间隔(毫秒)
    
    // 方向变化检测参数
    private static final double DIRECTION_CHANGE_THRESHOLD = 90.0; // 方向变化阈值(度)
    
    // 静止检测参数
    private static final double STATIC_THRESHOLD = 0.5; // 静止速度阈值(米/秒)
    private static final long STATIC_TIME_THRESHOLD = 3000; // 静止时间阈值(毫秒)
    private boolean isStaticState = false; // 是否处于静止状态
    private long staticStartTime = 0; // 静止开始时间
    
    // 信号质量指标
    private int signalQuality = 2; // 0-很差，1-差，2-中等，3-好，4-很好
    
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
            Log.w(TAG, "GNSS位置为null，无法处理");
            return lastValidPosition;
        }
        
        long currentTime = System.currentTimeMillis();
        
        // 如果是第一个位置，直接设置为有效位置
        if (lastValidPosition == null) {
            lastValidPosition = rawPosition;
            lastUpdateTime = currentTime;
            
            // 初始化位置历史
            for (int i = 0; i < positionHistory.length; i++) {
                positionHistory[i] = rawPosition;
            }
            positionHistoryCount = 1;
            
            return rawPosition;
        }
        
        // 计算与上次有效位置的距离
        double distance = calculateDistance(lastValidPosition, rawPosition);
        long timeElapsed = (currentTime - lastUpdateTime) / 1000; // 秒
        
        // 防止除零错误
        double instantSpeed = timeElapsed > 0 ? distance / timeElapsed : 0;
        
        // 更新当前速度估计 (使用加权平均)
        currentSpeed = 0.8 * currentSpeed + 0.2 * instantSpeed;
        
        // 计算当前位置与上次位置的方向角度
        double direction = calculateBearing(lastValidPosition, rawPosition);
        updateDirectionHistory(direction);
        
        // 更精细的跳变检测和过滤
        if (timeElapsed > 0) {
            // 静止状态检测
            if (instantSpeed < STATIC_THRESHOLD) {
                if (!isStaticState) {
                    // 刚进入静止状态
                    staticStartTime = currentTime;
                    isStaticState = true;
                } else if (currentTime - staticStartTime > STATIC_TIME_THRESHOLD) {
                    // 持续静止状态，加强平滑
                    signalQuality = Math.max(0, signalQuality - 1); // 降低信号质量评估
                    Log.d(TAG, "持续静止状态已超过" + STATIC_TIME_THRESHOLD + "ms，信号质量降级为: " + signalQuality);
                }
            } else {
                isStaticState = false;
                if (instantSpeed < MAX_GNSS_SPEED) {
                    // 正常移动，信号质量可能较好
                    signalQuality = Math.min(4, signalQuality + 1);
                }
            }
            
            // 检测跳变 - 增强的判断条件:
            // 1. 距离超过跳变阈值且速度超过最大速度
            // 2. 时间间隔太短但距离较大（可能是信号不稳定导致的快速变化）
            // 3. 方向频繁变化（摆动检测）
            boolean isJumping = (distance > JUMP_THRESHOLD && instantSpeed > MAX_GNSS_SPEED) || 
                               (distance > 2.0 && timeElapsed < 0.2);
            
            boolean isOscillating = distance > OSCILLATION_THRESHOLD && hasRecentDirectionChange();
            
            if (isJumping || isOscillating) {
                String reason = isJumping ? "速度/距离异常" : "方向频繁变化";
                Log.d(TAG, "GNSS跳变被检测到: 距离=" + distance + "m, 速度=" + instantSpeed + 
                      "m/s, 时间=" + timeElapsed + "s, 原因=" + reason);
                
                // 对于严重跳变，直接返回上一个有效位置
                if (distance > JUMP_THRESHOLD * 2 || (isOscillating && signalQuality < 3)) {
                    Log.d(TAG, "严重跳变，直接使用上一个有效位置");
                    updatePositionHistory(lastValidPosition); // 继续使用上一个有效位置进行平滑
                    return lastValidPosition;
                }
                
                // 对于中等跳变，限制移动距离
                double limitFactor = signalQuality < 2 ? 0.2 : 0.4; // 降低限制因子，更严格限制
                double limitedDistance = MAX_GNSS_SPEED * timeElapsed * limitFactor;
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
        updatePositionHistory(rawPosition);
        
        // 计算平滑后的位置 - 根据信号质量调整平滑强度
        LatLng smoothedPosition = calculateSmoothedPosition();
        
        // 更新状态
        lastValidPosition = smoothedPosition;
        lastUpdateTime = currentTime;
        
        return smoothedPosition;
    }
    
    /**
     * 更新位置历史数组
     * @param position 新位置
     */
    private void updatePositionHistory(LatLng position) {
        if (position == null) {
            Log.e(TAG, "尝试使用null位置更新历史记录");
            return; // 避免存储null值
        }
        
        positionHistory[positionHistoryIndex] = position;
        positionHistoryIndex = (positionHistoryIndex + 1) % positionHistory.length;
        if (positionHistoryCount < positionHistory.length) {
            positionHistoryCount++;
        }
    }
    
    /**
     * 更新方向历史数组
     * @param direction 新方向(度)
     */
    private void updateDirectionHistory(double direction) {
        directionHistory[directionHistoryIndex] = direction;
        directionHistoryIndex = (directionHistoryIndex + 1) % directionHistory.length;
        if (directionHistoryCount < directionHistory.length) {
            directionHistoryCount++;
        }
    }
    
    /**
     * 检测是否存在近期方向变化
     * @return 如果存在近期明显方向变化返回true
     */
    private boolean hasRecentDirectionChange() {
        if (directionHistoryCount < 3) {
            return false; // 需要至少3个方向样本
        }
        
        for (int i = 0; i < directionHistoryCount - 2; i++) {
            int idx1 = (directionHistoryIndex - 1 - i + directionHistory.length) % directionHistory.length;
            int idx2 = (directionHistoryIndex - 1 - i - 2 + directionHistory.length) % directionHistory.length;
            
            double dirDiff = Math.abs(directionHistory[idx1] - directionHistory[idx2]);
            if (dirDiff > 180) {
                dirDiff = 360 - dirDiff; // 处理方向角度环绕
            }
            
            if (dirDiff > DIRECTION_CHANGE_THRESHOLD) {
                Log.d(TAG, "检测到方向大幅变化: " + dirDiff + "度");
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 根据位置历史计算平滑后的位置
     * @return 平滑后的位置
     */
    private LatLng calculateSmoothedPosition() {
        if (positionHistoryCount == 0) {
            return null;
        }
        
        // 检查历史记录中是否有空值
        boolean hasNullEntries = false;
        for (int i = 0; i < positionHistoryCount; i++) {
            int index = (positionHistoryIndex - 1 - i + positionHistory.length) % positionHistory.length;
            if (positionHistory[index] == null) {
                hasNullEntries = true;
                break;
            }
        }
        
        // 如果有空值，返回最后一个有效位置
        if (hasNullEntries) {
            return lastValidPosition;
        }
        
        // 根据信号质量调整平滑强度
        double smoothingStrength;
        switch(signalQuality) {
            case 0: // 很差
                smoothingStrength = 0.08; // 强平滑
                break;
            case 1: // 差
                smoothingStrength = 0.12;
                break;
            case 2: // 中等
                smoothingStrength = 0.15;
                break;
            case 3: // 好
                smoothingStrength = 0.25;
                break;
            case 4: // 很好
                smoothingStrength = 0.4; // 弱平滑
                break;
            default:
                smoothingStrength = SMOOTHING_FACTOR;
        }
        
        // 使用指数衰减权重计算平滑位置
        double totalWeight = 0;
        double weightedLatSum = 0;
        double weightedLngSum = 0;
        
        // 最新的位置权重最大，历史位置权重指数衰减
        // 使用平滑强度作为衰减因子
        for (int i = 0; i < positionHistoryCount; i++) {
            int index = (positionHistoryIndex - 1 - i + positionHistory.length) % positionHistory.length;
            
            // 权重随着历史增加而指数衰减
            double weight = Math.pow(smoothingStrength, i);
            
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
        directionHistoryCount = 0;
        directionHistoryIndex = 0;
        isStaticState = false;
        signalQuality = 2; // 重置为中等
        
        // 清空位置历史数组
        for (int i = 0; i < positionHistory.length; i++) {
            positionHistory[i] = null;
        }
        
        // 清空方向历史数组
        for (int i = 0; i < directionHistory.length; i++) {
            directionHistory[i] = 0;
        }
        
        Log.d(TAG, "GNSSProcessor has been reset");
    }
    
    /**
     * 计算两个LatLng点之间的方位角(度)
     * @param start 起始点
     * @param end 终点
     * @return 方位角(0-360度)
     */
    private double calculateBearing(LatLng start, LatLng end) {
        double startLat = Math.toRadians(start.latitude);
        double startLng = Math.toRadians(start.longitude);
        double endLat = Math.toRadians(end.latitude);
        double endLng = Math.toRadians(end.longitude);
        
        double dLng = endLng - startLng;
        
        double y = Math.sin(dLng) * Math.cos(endLat);
        double x = Math.cos(startLat) * Math.sin(endLat) -
                   Math.sin(startLat) * Math.cos(endLat) * Math.cos(dLng);
        
        double bearing = Math.atan2(y, x);
        
        // 转换为度数并确保在0-360范围内
        bearing = Math.toDegrees(bearing);
        bearing = (bearing + 360) % 360;
        
        return bearing;
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
        
        try {
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
        } catch (Exception e) {
            Log.e(TAG, "计算距离时出错: " + e.getMessage());
            return 0; // 出错时返回0距离
        }
    }
} 