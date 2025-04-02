package com.example.ekf;

import com.google.android.gms.maps.model.LatLng;
import android.util.Log;

/**
 * 扩展卡尔曼滤波器(EKF)类用于融合GNSS、PDR和WiFi定位数据
 * 实现高精度的室内外无缝定位
 */
public class EKF {
    // 状态向量 [x, y, heading]
    private double[] state = new double[3];
    
    // 状态协方差矩阵
    private double[][] P = new double[3][3];
    
    // 过程噪声协方差矩阵
    private double[][] Q = new double[3][3];
    
    // 观测噪声协方差矩阵 - 根据实际观测质量调整
    private double[][] RGNSS = new double[2][2];
    private double[][] RWiFi = new double[2][2];
    
    // 当前融合后的位置
    private LatLng fusedPosition;
    private LatLng initialPosition; // 保存初始位置用于坐标转换
    
    // 位置和方向的初始值是否已设置
    private boolean isInitialized = false;
    
    /**
     * 构造函数，初始化EKF参数
     */
    public EKF() {
        // 初始化状态向量
        state[0] = 0; // x
        state[1] = 0; // y
        state[2] = 0; // heading (弧度)
        
        // 初始化状态协方差矩阵 (初始不确定性较高)
        for (int i = 0; i < 3; i++) {
            P[i][i] = 100.0;
        }
        
        // 调整过程噪声协方差矩阵 - 降低PDR的过程噪声
        Q[0][0] = 0.005; // x位置过程噪声 (降低)
        Q[1][1] = 0.005; // y位置过程噪声 (降低)
        Q[2][2] = 0.005; // 航向过程噪声 (降低，使方向更稳定)
        
        // 调整GNSS测量噪声协方差 - 增加，因为GNSS在室内或城市峡谷误差较大
        RGNSS[0][0] = 10.0; // x位置测量噪声 (增加)
        RGNSS[1][1] = 10.0; // y位置测量噪声 (增加)
        
        // 调整WiFi测量噪声协方差 - 根据WiFi定位精度调整
        RWiFi[0][0] = 15.0; // x位置测量噪声 (稍微增加)
        RWiFi[1][1] = 15.0; // y位置测量噪声 (稍微增加)
    }
    
    /**
     * 初始化滤波器状态
     * @param initialLatLng 初始位置
     * @param initialHeading 初始航向角(弧度)
     */
    public void initialize(LatLng initialLatLng, double initialHeading) {
        if (initialLatLng != null) {
            state[0] = 0; // 初始x相对坐标设为0
            state[1] = 0; // 初始y相对坐标设为0
            
            // 对初始航向角进行标准化
            while (initialHeading > Math.PI) initialHeading -= 2 * Math.PI;
            while (initialHeading < -Math.PI) initialHeading += 2 * Math.PI;
            
            state[2] = initialHeading;
            
            // 降低航向角的初始不确定性
            P[2][2] = 0.1;  // 航向角初始协方差设为较小值
            
            this.initialPosition = initialLatLng;
            this.fusedPosition = initialLatLng;
            isInitialized = true;
            
            Log.d("EKF", String.format("EKF初始化完成 - 初始位置: [%.6f, %.6f], 初始航向: %.2f°", 
                    initialLatLng.latitude, initialLatLng.longitude, Math.toDegrees(initialHeading)));
        }
    }
    
    /**
     * 使用PDR步进数据进行预测更新
     * @param stepLength 步长(米)
     * @param headingChange 航向变化(弧度)
     */
    public void predict(double stepLength, double headingChange) {
        if (!isInitialized) return;
        
        // 限制航向变化幅度
        while (headingChange > Math.PI) headingChange -= 2 * Math.PI;
        while (headingChange < -Math.PI) headingChange += 2 * Math.PI;
        
        // 限制单次航向变化不超过90度
        if (Math.abs(headingChange) > Math.PI/2) {
            headingChange = Math.signum(headingChange) * Math.PI/2;
        }
        
        // 更新状态
        state[0] += stepLength * Math.cos(state[2]);
        state[1] += stepLength * Math.sin(state[2]);
        state[2] += headingChange;
        
        // 标准化航向角
        while (state[2] > Math.PI) state[2] -= 2 * Math.PI;
        while (state[2] < -Math.PI) state[2] += 2 * Math.PI;
        
        // 更新状态协方差
        double cosHeading = Math.cos(state[2]);
        double sinHeading = Math.sin(state[2]);
        
        // 计算雅可比矩阵
        double[][] F = new double[3][3];
        F[0][0] = 1;
        F[0][1] = 0;
        F[0][2] = -stepLength * sinHeading;
        F[1][0] = 0;
        F[1][1] = 1;
        F[1][2] = stepLength * cosHeading;
        F[2][0] = 0;
        F[2][1] = 0;
        F[2][2] = 1;
        
        // 更新状态协方差
        P = multiplyMatrices(F, P);
        P = addMatrices(P, Q);
        
        // 更新融合位置
        updateFusedPosition();
        
        Log.d("EKF", String.format("EKF预测更新 - 步长: %.2fm, 航向变化: %.2f°, 当前位置: [%.6f, %.6f]", 
                stepLength, Math.toDegrees(headingChange), fusedPosition.latitude, fusedPosition.longitude));
    }
    
    /**
     * 使用GNSS位置进行测量更新
     * @param gnssLatLng GNSS测量位置
     */
    public void updateWithGNSS(LatLng gnssLatLng) {
        if (!isInitialized || gnssLatLng == null) return;
        
        // 将GNSS位置转换为相对于初始位置的局部坐标
        double[] z = latLngToLocalXY(gnssLatLng);
        
        // 观测矩阵H (2x3)，只观测位置，不观测航向
        double[][] H = new double[2][3];
        H[0][0] = 1.0; // x位置
        H[1][1] = 1.0; // y位置
        
        // 计算卡尔曼增益: K = P*H^T * (H*P*H^T + R)^-1
        double[][] PHt = multiplyMatrices(P, transpose(H));
        double[][] HPHt_R = addMatrices(multiplyMatrices(multiplyMatrices(H, P), transpose(H)), RGNSS);
        double[][] inv_HPHt_R = inverse(HPHt_R);
        
        // 确认矩阵求逆成功
        if (inv_HPHt_R == null) {
            return; // 矩阵奇异，跳过本次更新
        }
        
        double[][] K = multiplyMatrices(PHt, inv_HPHt_R);
        
        // 计算测量残差: y = z - H*x
        double[] y = new double[2];
        y[0] = z[0] - state[0];
        y[1] = z[1] - state[1];
        
        // 限制残差大小，防止突变 - 重要优化点
        double maxResidual = 5.0; // 最大允许残差(米)
        if (Math.abs(y[0]) > maxResidual) {
            y[0] = y[0] > 0 ? maxResidual : -maxResidual;
        }
        if (Math.abs(y[1]) > maxResidual) {
            y[1] = y[1] > 0 ? maxResidual : -maxResidual;
        }
        
        // 更新状态: x = x + K*y
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 2; j++) {
                state[i] += K[i][j] * y[j];
            }
        }
        
        // 更新协方差: P = (I - K*H)*P
        double[][] I = new double[3][3];
        I[0][0] = 1.0;
        I[1][1] = 1.0;
        I[2][2] = 1.0;
        
        P = multiplyMatrices(subtractMatrices(I, multiplyMatrices(K, H)), P);
        
        // 更新融合位置
        updateFusedPosition();
    }
    
    /**
     * 使用WiFi位置进行测量更新
     * @param wifiLatLng WiFi测量位置
     */
    public void updateWithWiFi(LatLng wifiLatLng) {
        if (!isInitialized || wifiLatLng == null) return;
        
        // 将WiFi位置转换为相对于初始位置的局部坐标
        double[] z = latLngToLocalXY(wifiLatLng);
        
        // 观测矩阵H (2x3)，只观测位置，不观测航向
        double[][] H = new double[2][3];
        H[0][0] = 1.0; // x位置
        H[1][1] = 1.0; // y位置
        
        // 动态调整WiFi测量噪声协方差
        double[][] currentRWiFi = new double[2][2];
        currentRWiFi[0][0] = RWiFi[0][0];
        currentRWiFi[1][1] = RWiFi[1][1];
        
        // 计算与当前状态的差异
        double dx = z[0] - state[0];
        double dy = z[1] - state[1];
        double distance = Math.sqrt(dx*dx + dy*dy);
        
        // 如果WiFi位置与当前状态差异较大，增加测量噪声
        if (distance > 5.0) {
            double factor = Math.min(distance / 5.0, 3.0); // 最多增加3倍
            currentRWiFi[0][0] *= factor;
            currentRWiFi[1][1] *= factor;
        }
        
        // 计算卡尔曼增益
        double[][] PHt = multiplyMatrices(P, transpose(H));
        double[][] HPHt_R = addMatrices(multiplyMatrices(multiplyMatrices(H, P), transpose(H)), currentRWiFi);
        double[][] inv_HPHt_R = inverse(HPHt_R);
        
        // 确认矩阵求逆成功
        if (inv_HPHt_R == null) {
            return; // 矩阵奇异，跳过本次更新
        }
        
        double[][] K = multiplyMatrices(PHt, inv_HPHt_R);
        
        // 计算测量残差
        double[] y = new double[2];
        y[0] = z[0] - state[0];
        y[1] = z[1] - state[1];
        
        // 使用自适应残差限制
        double maxResidual = Math.min(5.0 + Math.sqrt(P[0][0] + P[1][1]), 10.0);
        if (Math.abs(y[0]) > maxResidual) {
            y[0] = y[0] > 0 ? maxResidual : -maxResidual;
        }
        if (Math.abs(y[1]) > maxResidual) {
            y[1] = y[1] > 0 ? maxResidual : -maxResidual;
        }
        
        // 更新状态
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 2; j++) {
                state[i] += K[i][j] * y[j];
            }
        }
        
        // 更新协方差
        double[][] I = new double[3][3];
        I[0][0] = 1.0;
        I[1][1] = 1.0;
        I[2][2] = 1.0;
        
        P = multiplyMatrices(subtractMatrices(I, multiplyMatrices(K, H)), P);
        
        // 更新融合位置
        updateFusedPosition();
    }
    
    /**
     * 使用GNSS经纬度位置进行测量更新
     * @param latitude GNSS纬度
     * @param longitude GNSS经度
     */
    public void update_gnss(double latitude, double longitude) {
        // 使用经纬度创建LatLng对象
        LatLng gnssLatLng = new LatLng(latitude, longitude);
        
        // 调用已有的updateWithGNSS方法处理
        updateWithGNSS(gnssLatLng);
    }
    
    /**
     * 获取当前融合后的位置
     * @return 融合后的LatLng位置
     */
    public LatLng getFusedPosition() {
        return fusedPosition;
    }
    
    /**
     * 更新融合位置
     */
    private void updateFusedPosition() {
        // 确保初始位置已设置
        if (initialPosition != null) {
            // 将局部坐标转回LatLng
            fusedPosition = localXYToLatLng(state[0], state[1]);
        }
    }
    
    /**
     * 将LatLng坐标转换为相对于初始位置的局部XY坐标
     * @param latLng 需要转换的LatLng坐标
     * @return 相对局部XY坐标 [x, y]
     */
    private double[] latLngToLocalXY(LatLng latLng) {
        // 实际实现应使用精确的地球坐标转换公式
        // 这里使用简化计算，假设在小范围内地球是平的
        double[] xy = new double[2];
        
        if (initialPosition != null) {
            // 使用更精确的转换公式，考虑经度随纬度变化的比例
            double latDiff = latLng.latitude - initialPosition.latitude;
            double lngDiff = latLng.longitude - initialPosition.longitude;
            
            // 转换为米，1度纬度约为111.32km 
            // 1度经度长度随纬度变化：111.32 * cos(lat)km
            final double METERS_PER_LAT_DEGREE = 111320.0;
            double latRadians = Math.toRadians(initialPosition.latitude);
            
            xy[0] = lngDiff * METERS_PER_LAT_DEGREE * Math.cos(latRadians);
            xy[1] = latDiff * METERS_PER_LAT_DEGREE;
        }
        
        return xy;
    }
    
    /**
     * 将局部XY坐标转换回LatLng坐标
     * @param x X坐标(米)
     * @param y Y坐标(米)
     * @return 对应的LatLng坐标
     */
    private LatLng localXYToLatLng(double x, double y) {
        if (initialPosition == null) return null;
        
        // 使用更精确的转换公式
        final double METERS_PER_LAT_DEGREE = 111320.0;
        double latRadians = Math.toRadians(initialPosition.latitude);
        
        double latDiff = y / METERS_PER_LAT_DEGREE;
        double lngDiff = x / (METERS_PER_LAT_DEGREE * Math.cos(latRadians));
        
        return new LatLng(
                initialPosition.latitude + latDiff,
                initialPosition.longitude + lngDiff
        );
    }
    
    // 矩阵运算辅助方法
    
    /**
     * 矩阵乘法
     */
    private double[][] multiplyMatrices(double[][] A, double[][] B) {
        if (A == null || B == null || A.length == 0 || B.length == 0 || A[0].length != B.length) {
            return null; // 矩阵不兼容
        }
        
        int aRows = A.length;
        int aCols = A[0].length;
        int bCols = B[0].length;
        
        double[][] C = new double[aRows][bCols];
        
        for (int i = 0; i < aRows; i++) {
            for (int j = 0; j < bCols; j++) {
                for (int k = 0; k < aCols; k++) {
                    C[i][j] += A[i][k] * B[k][j];
                }
            }
        }
        
        return C;
    }
    
    /**
     * 矩阵转置
     */
    private double[][] transpose(double[][] A) {
        if (A == null || A.length == 0) {
            return null;
        }
        
        int rows = A.length;
        int cols = A[0].length;
        
        double[][] T = new double[cols][rows];
        
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                T[j][i] = A[i][j];
            }
        }
        
        return T;
    }
    
    /**
     * 矩阵加法
     */
    private double[][] addMatrices(double[][] A, double[][] B) {
        if (A == null || B == null || A.length != B.length || A[0].length != B[0].length) {
            return null; // 矩阵不兼容
        }
        
        int rows = A.length;
        int cols = A[0].length;
        
        double[][] C = new double[rows][cols];
        
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                C[i][j] = A[i][j] + B[i][j];
            }
        }
        
        return C;
    }
    
    /**
     * 矩阵减法
     */
    private double[][] subtractMatrices(double[][] A, double[][] B) {
        if (A == null || B == null || A.length != B.length || A[0].length != B[0].length) {
            return null; // 矩阵不兼容
        }
        
        int rows = A.length;
        int cols = A[0].length;
        
        double[][] C = new double[rows][cols];
        
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                C[i][j] = A[i][j] - B[i][j];
            }
        }
        
        return C;
    }
    
    /**
     * 矩阵求逆 (仅适用于2x2矩阵，扩展卡尔曼滤波实际应用中可能需要更复杂的实现)
     */
    private double[][] inverse(double[][] A) {
        // 此处简化仅处理2x2矩阵
        if (A == null || A.length != 2 || A[0].length != 2) {
            return null; // 错误处理
        }
        
        double det = A[0][0] * A[1][1] - A[0][1] * A[1][0];
        
        if (Math.abs(det) < 1e-10) {
            // 奇异矩阵或接近奇异，不可逆
            return null;
        }
        
        double[][] inv = new double[2][2];
        inv[0][0] = A[1][1] / det;
        inv[0][1] = -A[0][1] / det;
        inv[1][0] = -A[1][0] / det;
        inv[1][1] = A[0][0] / det;
        
        return inv;
    }
} 