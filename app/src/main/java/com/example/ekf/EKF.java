package com.example.ekf;

import com.google.android.gms.maps.model.LatLng;

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
    
    // 观测噪声协方差矩阵
    private double[][] RGNSS = new double[2][2];
    private double[][] RWiFi = new double[2][2];
    
    // 当前融合后的位置
    private LatLng fusedPosition;
    
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
        
        // 初始化状态协方差矩阵 (高不确定性)
        for (int i = 0; i < 3; i++) {
            P[i][i] = 100.0;
        }
        
        // 过程噪声协方差矩阵
        Q[0][0] = 0.01; // x位置过程噪声
        Q[1][1] = 0.01; // y位置过程噪声
        Q[2][2] = 0.01; // 航向过程噪声
        
        // GNSS测量噪声协方差
        RGNSS[0][0] = 5.0; // x位置测量噪声
        RGNSS[1][1] = 5.0; // y位置测量噪声
        
        // WiFi测量噪声协方差 (通常比GNSS误差大)
        RWiFi[0][0] = 10.0; // x位置测量噪声
        RWiFi[1][1] = 10.0; // y位置测量噪声
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
            state[2] = initialHeading;
            
            fusedPosition = initialLatLng;
            isInitialized = true;
        }
    }
    
    /**
     * 使用PDR步进数据进行预测更新
     * @param stepLength 步长(米)
     * @param headingChange 航向变化(弧度)
     */
    public void predict(double stepLength, double headingChange) {
        if (!isInitialized) return;
        
        // 更新状态向量
        double heading = state[2] + headingChange;
        state[0] += stepLength * Math.cos(heading);
        state[1] += stepLength * Math.sin(heading);
        state[2] = heading;
        
        // 计算状态转移雅可比矩阵
        double[][] F = new double[3][3];
        F[0][0] = 1.0;
        F[0][2] = -stepLength * Math.sin(heading);
        F[1][1] = 1.0;
        F[1][2] = stepLength * Math.cos(heading);
        F[2][2] = 1.0;
        
        // 更新协方差矩阵: P = F*P*F^T + Q
        P = addMatrices(multiplyMatrices(multiplyMatrices(F, P), transpose(F)), Q);
        
        // 更新融合位置 (这里简化处理，实际应使用地球坐标转换)
        updateFusedPosition();
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
        double[][] K = multiplyMatrices(
                multiplyMatrices(P, transpose(H)),
                inverse(addMatrices(multiplyMatrices(multiplyMatrices(H, P), transpose(H)), RGNSS))
        );
        
        // 计算测量残差: y = z - H*x
        double[] y = new double[2];
        y[0] = z[0] - state[0];
        y[1] = z[1] - state[1];
        
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
        
        // 计算卡尔曼增益
        double[][] K = multiplyMatrices(
                multiplyMatrices(P, transpose(H)),
                inverse(addMatrices(multiplyMatrices(multiplyMatrices(H, P), transpose(H)), RWiFi))
        );
        
        // 计算测量残差
        double[] y = new double[2];
        y[0] = z[0] - state[0];
        y[1] = z[1] - state[1];
        
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
        // 这里简化处理，实际应基于初始位置和相对坐标计算
        if (fusedPosition != null) {
            // 将局部坐标转回LatLng，这里假设有个初始点作为参考
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
        
        if (fusedPosition != null) {
            // 简单转换，假设1度纬度和经度约为111km
            double latDiff = latLng.latitude - fusedPosition.latitude;
            double lngDiff = latLng.longitude - fusedPosition.longitude;
            
            // 转换为米，考虑到纬度影响经度比例
            xy[0] = lngDiff * 111000 * Math.cos(Math.toRadians(fusedPosition.latitude));
            xy[1] = latDiff * 111000;
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
        if (fusedPosition == null) return null;
        
        // 简单转换，假设1度纬度和经度约为111km
        double latDiff = y / 111000;
        double lngDiff = x / (111000 * Math.cos(Math.toRadians(fusedPosition.latitude)));
        
        return new LatLng(
                fusedPosition.latitude + latDiff,
                fusedPosition.longitude + lngDiff
        );
    }
    
    // 矩阵运算辅助方法
    
    /**
     * 矩阵乘法
     */
    private double[][] multiplyMatrices(double[][] A, double[][] B) {
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
        if (A.length != 2 || A[0].length != 2) {
            return null; // 错误处理
        }
        
        double det = A[0][0] * A[1][1] - A[0][1] * A[1][0];
        
        if (det == 0) {
            // 奇异矩阵，不可逆
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