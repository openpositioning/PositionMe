package com.openpositioning.PositionMe.utils;

import android.util.Log;
import com.google.android.gms.maps.model.LatLng;

/**
 * Drift-Corrected Position Fusion - 专门解决PDR累积误差问题
 * 
 * 核心策略:
 * 1. 滑动窗口 - 只保留最近N步的数据，丢弃老数据避免累积
 * 2. 定期重置 - 利用GNSS/WiFi/锚点定期重置参考点
 * 3. 航向平滑 - 人不会突然大角度转弯，平滑航向变化
 * 4. 步长归一化 - 动态调整步长使长期平均保持稳定
 * 5. 速度限制 - 人类步行速度有限，限制最大位移速度
 * 
 * @author PositionMe
 */
public class DriftCorrectedFusion {
    
    private static final String TAG = "DriftCorrectedFusion";
    
    // ========== 滑动窗口参数 ==========
    // 只追踪最近的步数，超过后从最近的锚点重新计算
    private static final int STEP_WINDOW_SIZE = 50;  // 大约1分钟的步行
    
    // 步历史记录
    private double[] stepDxHistory = new double[STEP_WINDOW_SIZE];
    private double[] stepDyHistory = new double[STEP_WINDOW_SIZE];
    private long[] stepTimeHistory = new long[STEP_WINDOW_SIZE];
    private int stepHistoryIndex = 0;
    private int totalStepsInWindow = 0;
    
    // ========== 参考点系统 ==========
    // 使用最近的锚点作为参考，而不是最初的起点
    private double anchorLat = 0;
    private double anchorLng = 0;
    private double anchorX = 0;  // 锚点时的PDR偏移
    private double anchorY = 0;
    private long anchorTime = 0;
    private int stepsFromAnchor = 0;
    
    // ========== 当前位置 ==========
    private double currentLat = 0;
    private double currentLng = 0;
    private double pdrOffsetX = 0;  // 从锚点的PDR偏移
    private double pdrOffsetY = 0;
    
    // ========== 航向平滑 ==========
    private double smoothedHeading = 0;
    private double lastRawHeading = 0;
    private static final double HEADING_SMOOTH_FACTOR = 0.3;  // 航向变化要平滑
    private static final double MAX_HEADING_CHANGE_PER_STEP = Math.PI / 6;  // 每步最多转30度
    
    // ========== 步长控制 ==========
    private double baseStepLength = 0.60;  // 基础步长
    private double stepLengthSum = 0;
    private int stepLengthCount = 0;
    private static final double MIN_STEP = 0.35;
    private static final double MAX_STEP = 0.80;
    private static final double STEP_NORMALIZE_RATE = 0.05;  // 归一化速率
    
    // ========== 速度限制 ==========
    private static final double MAX_SPEED_MPS = 2.0;  // 最大2m/s (快走)
    private static final double MIN_STEP_INTERVAL_MS = 300;  // 最快3步/秒
    private long lastStepTime = 0;
    
    // ========== PDR输入跟踪 ==========
    private float lastPdrX = 0;
    private float lastPdrY = 0;
    
    // ========== 输出平滑 ==========
    private double smoothLat = 0;
    private double smoothLng = 0;
    private static final double OUTPUT_SMOOTH = 0.4;
    
    // ========== 状态 ==========
    private boolean initialized = false;
    private double startLat = 0;
    private double startLng = 0;
    
    // ========== 建筑约束 ==========
    private double[] buildingBounds = null;
    private boolean constrainEnabled = false;
    
    // ========== 常量 ==========
    private static final double METERS_PER_DEG_LAT = 111139.0;
    
    // ========== 自动重置参数 ==========
    private static final int AUTO_ANCHOR_STEPS = 30;  // 每30步自动创建锚点（如果有好的外部位置）
    private double lastExternalLat = 0;
    private double lastExternalLng = 0;
    private float lastExternalAccuracy = 999;
    private long lastExternalTime = 0;
    
    /**
     * 初始化
     */
    public void initialize(double latitude, double longitude, float accuracy) {
        startLat = latitude;
        startLng = longitude;
        
        // 第一个锚点就是起点
        anchorLat = latitude;
        anchorLng = longitude;
        anchorX = 0;
        anchorY = 0;
        anchorTime = System.currentTimeMillis();
        stepsFromAnchor = 0;
        
        // 当前位置
        currentLat = latitude;
        currentLng = longitude;
        pdrOffsetX = 0;
        pdrOffsetY = 0;
        
        // 平滑输出
        smoothLat = latitude;
        smoothLng = longitude;
        
        // 重置历史
        stepHistoryIndex = 0;
        totalStepsInWindow = 0;
        
        // 航向初始化
        smoothedHeading = 0;
        
        initialized = true;
        
        // 检查建筑约束
        checkBuildingConstraint(latitude, longitude);
        
        Log.d(TAG, String.format("初始化: (%.6f, %.6f) 精度=%.1fm", latitude, longitude, accuracy));
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * 核心步伐更新方法 - 防累积误差版
     */
    public void updateWithStep(float pdrX, float pdrY) {
        if (!initialized) {
            lastPdrX = pdrX;
            lastPdrY = pdrY;
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        // 计算原始步伐增量
        float rawDx = pdrX - lastPdrX;
        float rawDy = pdrY - lastPdrY;
        double rawDistance = Math.sqrt(rawDx * rawDx + rawDy * rawDy);
        
        // 过滤无效步伐
        if (rawDistance < 0.05 || rawDistance > 2.5) {
            lastPdrX = pdrX;
            lastPdrY = pdrY;
            return;
        }
        
        // === 1. 速度限制 ===
        long timeSinceLastStep = currentTime - lastStepTime;
        if (lastStepTime > 0) {
            if (timeSinceLastStep < MIN_STEP_INTERVAL_MS) {
                // 步伐太快，衰减
                rawDx *= 0.4f;
                rawDy *= 0.4f;
                rawDistance *= 0.4;
            }
            
            // 检查速度是否超限
            double speed = rawDistance / (timeSinceLastStep / 1000.0);
            if (speed > MAX_SPEED_MPS) {
                double factor = MAX_SPEED_MPS / speed;
                rawDx *= factor;
                rawDy *= factor;
                rawDistance *= factor;
            }
        }
        
        // === 2. 步长归一化 ===
        double normalizedStep = normalizeStepLength(rawDistance);
        double stepScale = normalizedStep / Math.max(rawDistance, 0.1);
        double dx = rawDx * stepScale;
        double dy = rawDy * stepScale;
        
        // === 3. 航向平滑 ===
        double rawHeading = Math.atan2(dx, dy);
        double smoothDx, smoothDy;
        
        if (totalStepsInWindow > 0) {
            // 限制航向变化速度
            double headingChange = normalizeAngle(rawHeading - smoothedHeading);
            if (Math.abs(headingChange) > MAX_HEADING_CHANGE_PER_STEP) {
                headingChange = Math.signum(headingChange) * MAX_HEADING_CHANGE_PER_STEP;
            }
            smoothedHeading = normalizeAngle(smoothedHeading + headingChange * HEADING_SMOOTH_FACTOR);
            
            // 用平滑后的航向重建位移
            smoothDx = normalizedStep * Math.sin(smoothedHeading);
            smoothDy = normalizedStep * Math.cos(smoothedHeading);
        } else {
            smoothedHeading = rawHeading;
            smoothDx = dx;
            smoothDy = dy;
        }
        
        // === 4. 更新滑动窗口 ===
        stepDxHistory[stepHistoryIndex] = smoothDx;
        stepDyHistory[stepHistoryIndex] = smoothDy;
        stepTimeHistory[stepHistoryIndex] = currentTime;
        stepHistoryIndex = (stepHistoryIndex + 1) % STEP_WINDOW_SIZE;
        totalStepsInWindow = Math.min(totalStepsInWindow + 1, STEP_WINDOW_SIZE);
        stepsFromAnchor++;
        
        // === 5. 重新计算从锚点的位置 ===
        recalculatePositionFromAnchor();
        
        // === 6. 自动重置检查 ===
        checkAutoAnchorReset(currentTime);
        
        // === 7. 建筑约束 ===
        applyBuildingConstraint();
        
        // === 8. 更新跟踪变量 ===
        lastPdrX = pdrX;
        lastPdrY = pdrY;
        lastStepTime = currentTime;
        
        // 输出平滑
        smoothLat = smoothLat + OUTPUT_SMOOTH * (currentLat - smoothLat);
        smoothLng = smoothLng + OUTPUT_SMOOTH * (currentLng - smoothLng);
    }
    
    /**
     * 从锚点重新计算位置
     * 这是防止累积误差的关键：只使用最近的锚点和滑动窗口内的步伐
     */
    private void recalculatePositionFromAnchor() {
        // 计算从锚点开始，窗口内所有步伐的总位移
        double totalDx = 0;
        double totalDy = 0;
        
        // 只累加窗口内的步伐（最多STEP_WINDOW_SIZE步）
        int stepsToUse = Math.min(stepsFromAnchor, totalStepsInWindow);
        
        // 从最老的步伐开始累加
        for (int i = 0; i < stepsToUse; i++) {
            int idx = (stepHistoryIndex - stepsToUse + i + STEP_WINDOW_SIZE) % STEP_WINDOW_SIZE;
            
            // 应用衰减权重 - 老步伐权重低
            double weight = 1.0 - (double)(stepsToUse - 1 - i) / (STEP_WINDOW_SIZE * 2);
            weight = Math.max(0.5, weight);  // 最低50%权重
            
            totalDx += stepDxHistory[idx] * weight;
            totalDy += stepDyHistory[idx] * weight;
        }
        
        // 更新PDR偏移
        pdrOffsetX = totalDx;
        pdrOffsetY = totalDy;
        
        // 转换为经纬度
        double metersPerDegLng = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(anchorLat));
        currentLat = anchorLat + pdrOffsetY / METERS_PER_DEG_LAT;
        currentLng = anchorLng + pdrOffsetX / metersPerDegLng;
    }
    
    /**
     * 步长归一化 - 保持长期平均步长稳定
     */
    private double normalizeStepLength(double rawStep) {
        // 更新统计
        stepLengthSum += rawStep;
        stepLengthCount++;
        
        // 计算当前平均
        double avgStep = stepLengthSum / stepLengthCount;
        
        // 如果样本足够，开始归一化
        if (stepLengthCount > 10) {
            // 逐渐调整基础步长
            baseStepLength = baseStepLength * (1 - STEP_NORMALIZE_RATE) + avgStep * STEP_NORMALIZE_RATE;
            baseStepLength = Math.max(MIN_STEP, Math.min(MAX_STEP, baseStepLength));
            
            // 定期重置统计以适应变化
            if (stepLengthCount > 100) {
                stepLengthSum = avgStep * 10;
                stepLengthCount = 10;
            }
        }
        
        // 返回归一化后的步长
        double normalized = Math.max(MIN_STEP, Math.min(MAX_STEP, rawStep));
        
        // 向基础步长靠拢
        normalized = normalized * 0.7 + baseStepLength * 0.3;
        
        return normalized;
    }
    
    /**
     * 检查是否需要自动创建新锚点
     */
    private void checkAutoAnchorReset(long currentTime) {
        // 每隔一定步数，如果有好的外部位置，创建新锚点
        if (stepsFromAnchor >= AUTO_ANCHOR_STEPS) {
            // 检查是否有最近的有效外部位置
            long externalAge = currentTime - lastExternalTime;
            
            if (externalAge < 10000 && lastExternalAccuracy < 15) {
                // 有最近10秒内的好位置，创建新锚点
                createAnchorPoint(lastExternalLat, lastExternalLng, "自动(GNSS)");
            } else if (stepsFromAnchor >= STEP_WINDOW_SIZE) {
                // 步数已满窗口但没有外部位置
                // 使用当前估计位置作为新锚点（防止窗口溢出）
                createAnchorPoint(currentLat, currentLng, "自动(窗口满)");
            }
        }
    }
    
    /**
     * 创建新锚点
     */
    private void createAnchorPoint(double lat, double lng, String source) {
        anchorLat = lat;
        anchorLng = lng;
        anchorTime = System.currentTimeMillis();
        stepsFromAnchor = 0;
        
        // 重置滑动窗口
        stepHistoryIndex = 0;
        totalStepsInWindow = 0;
        
        // 位置偏移重置
        pdrOffsetX = 0;
        pdrOffsetY = 0;
        
        // 更新当前位置
        currentLat = lat;
        currentLng = lng;
        
        Log.d(TAG, String.format("新锚点[%s]: (%.6f, %.6f)", source, lat, lng));
    }
    
    /**
     * 角度归一化到[-PI, PI]
     */
    private double normalizeAngle(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }
    
    /**
     * 外部位置更新（GNSS）
     */
    public boolean updateWithGNSS(double lat, double lng, float accuracy) {
        if (!initialized) {
            if (accuracy < 50) {
                initialize(lat, lng, accuracy);
                return true;
            }
            return false;
        }
        
        // 保存外部位置用于自动锚点
        lastExternalLat = lat;
        lastExternalLng = lng;
        lastExternalAccuracy = accuracy;
        lastExternalTime = System.currentTimeMillis();
        
        // 室内模式下忽略低质量GNSS
        if (constrainEnabled || accuracy > 20) {
            return false;
        }
        
        // 好的GNSS直接创建锚点
        if (accuracy < 10) {
            createAnchorPoint(lat, lng, "GNSS高精度");
            return true;
        }
        
        return true;
    }
    
    /**
     * 用户手动设置锚点
     */
    public void setAnchorPoint(double lat, double lng) {
        createAnchorPoint(lat, lng, "用户标记");
        
        // 检查新位置的建筑约束
        checkBuildingConstraint(lat, lng);
    }
    
    /**
     * 建筑约束
     */
    private void applyBuildingConstraint() {
        if (!constrainEnabled || buildingBounds == null) return;
        
        double oldLat = currentLat;
        double oldLng = currentLng;
        
        currentLat = Math.max(buildingBounds[0], Math.min(buildingBounds[1], currentLat));
        currentLng = Math.max(buildingBounds[2], Math.min(buildingBounds[3], currentLng));
        
        // 如果被约束，更新PDR偏移以保持一致
        if (currentLat != oldLat || currentLng != oldLng) {
            double metersPerDegLng = METERS_PER_DEG_LAT * Math.cos(Math.toRadians(anchorLat));
            pdrOffsetY = (currentLat - anchorLat) * METERS_PER_DEG_LAT;
            pdrOffsetX = (currentLng - anchorLng) * metersPerDegLng;
        }
    }
    
    /**
     * 检查建筑约束
     */
    private void checkBuildingConstraint(double lat, double lng) {
        // Nucleus
        if (lat >= 55.92282 && lat <= 55.92332 && lng >= -3.17460 && lng <= -3.17387) {
            buildingBounds = new double[]{55.92282, 55.92332, -3.17460, -3.17387};
            constrainEnabled = true;
            Log.d(TAG, "在Nucleus内 - 启用约束");
            return;
        }
        
        // Library
        if (lat >= 55.92260 && lat <= 55.92310 && lng >= -3.17530 && lng <= -3.17440) {
            buildingBounds = new double[]{55.92260, 55.92310, -3.17530, -3.17440};
            constrainEnabled = true;
            Log.d(TAG, "在Library内 - 启用约束");
            return;
        }
        
        constrainEnabled = false;
        buildingBounds = null;
    }
    
    // ========== 输出方法 ==========
    
    public double getFusedLatitude() {
        return smoothLat;
    }
    
    public double getFusedLongitude() {
        return smoothLng;
    }
    
    public LatLng getPosition() {
        return new LatLng(getFusedLatitude(), getFusedLongitude());
    }
    
    public float getPositionUncertainty() {
        // 不确定性随步数增加
        return Math.min(3.0f + stepsFromAnchor * 0.1f, 15.0f);
    }
    
    public float[] getPdrOffset() {
        return new float[]{(float)pdrOffsetX, (float)pdrOffsetY};
    }
    
    public int getStepsFromAnchor() {
        return stepsFromAnchor;
    }
    
    public double getBaseStepLength() {
        return baseStepLength;
    }
    
    public boolean hasAnchorPoint() {
        return stepsFromAnchor < STEP_WINDOW_SIZE;
    }
    
    public boolean isConstrainedToBuilding() {
        return constrainEnabled;
    }
    
    // ========== 兼容性方法 ==========
    
    public void updateWithPDR(float pdrX, float pdrY) {
        updateWithStep(pdrX, pdrY);
    }
    
    public void updateWithPDR(float pdrX, float pdrY, float heading, float stepLength) {
        updateWithStep(pdrX, pdrY);
    }
    
    public void updateWithAccelerometer(float[] accel, float heading) {
        // 简化版本不使用加速度计
    }
    
    public void updateWithGyroscope(float[] gyro) {
        // 简化版本不使用陀螺仪
    }
    
    public void updateWithMagnetometer(float heading) {
        // 可选：用磁力计航向辅助平滑
        if (totalStepsInWindow == 0) {
            smoothedHeading = heading;
        }
    }
    
    public void setConstrainToBuilding(boolean enable) {
        constrainEnabled = enable;
    }
    
    public void setBuildingBounds(double minLat, double maxLat, double minLng, double maxLng) {
        buildingBounds = new double[]{minLat, maxLat, minLng, maxLng};
        constrainEnabled = true;
    }
    
    public void forceReset(double lat, double lng, float accuracy) {
        initialize(lat, lng, accuracy);
    }
    
    public void reset() {
        initialized = false;
        anchorLat = 0;
        anchorLng = 0;
        currentLat = 0;
        currentLng = 0;
        pdrOffsetX = 0;
        pdrOffsetY = 0;
        stepHistoryIndex = 0;
        totalStepsInWindow = 0;
        stepsFromAnchor = 0;
        smoothLat = 0;
        smoothLng = 0;
        baseStepLength = 0.60;
        stepLengthSum = 0;
        stepLengthCount = 0;
        constrainEnabled = false;
        buildingBounds = null;
        Log.d(TAG, "重置完成");
    }
    
    public long getTimeSinceLastGnss() {
        return System.currentTimeMillis() - lastExternalTime;
    }
    
    public boolean isGnssStale() {
        return getTimeSinceLastGnss() > 30000;
    }
    
    public double getFilteredHeading() {
        return smoothedHeading;
    }
    
    public double getAdaptiveStepLength() {
        return baseStepLength;
    }
}
