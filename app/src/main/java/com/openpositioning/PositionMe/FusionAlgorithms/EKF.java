package com.openpositioning.PositionMe.FusionAlgorithms;

import com.google.android.gms.maps.model.LatLng;
import org.ejml.simple.SimpleMatrix;

/**
 * 在原有 EKF 基础上，将状态拓展至 [x, y, z, theta]。
 */
public class EKF {

    // state = [ x, y, z, theta ]^T
    // x, y, z in meters (ENU), theta in radians
    private SimpleMatrix state;       // 4x1
    private SimpleMatrix covariance;  // 4x4

    // 过程噪声
    private SimpleMatrix processNoise;     // 4x4
    // WiFi 测量噪声(只更新 x,y)
    private SimpleMatrix measurementNoise; // 2x2
    // Barometer 测量噪声(只更新 z)
    private double baroNoise;             // 1维

    private final SimpleMatrix I4; // 4x4 identity

    /**
     * 构造函数：增加 initZ
     */
    public EKF(double initX, double initY, double initZ, double initHeading) {
        // 状态维度改为4
        state = new SimpleMatrix(4,1);
        state.set(0,0, initX);      // x
        state.set(1,0, initY);      // y
        state.set(2,0, initZ);      // z
        state.set(3,0, initHeading);// theta

        // 协方差 4x4
        covariance = SimpleMatrix.identity(4).scale(1.0);

        // 过程噪声改为 4x4
        processNoise = SimpleMatrix.identity(4).scale(0.1);

        // 测量噪声( WiFi 观测 2x2 )
        measurementNoise = SimpleMatrix.identity(2).scale(0.5);

        // 气压计噪声( 单独 1维 ), 你可以根据实际情况调整
        baroNoise = 0.8;

        I4 = SimpleMatrix.identity(4);
    }

    /**
     * 老的构造函数 (若需要兼容旧代码)
     * 默认 z=0
     */
    public EKF(double initX, double initY, double initHeading) {
        this(initX, initY, 0.0, initHeading);
    }

    /**
     * Predict step (PDR):
     * 令步长 stepLen, heading = state(3)
     * x_k = x_{k-1} + stepLen*cos(theta)
     * y_k = y_{k-1} + stepLen*sin(theta)
     * z_k 不变(可自行处理楼层爬升)
     * theta_k 不变(或在此融入陀螺仪积分)
     */
    public void predict(double stepLen) {
        double x = state.get(0,0);
        double y = state.get(1,0);
        double z = state.get(2,0);
        double theta = state.get(3,0);

        double newX = x + stepLen*Math.cos(theta);
        double newY = y + stepLen*Math.sin(theta);
        double newZ = z;      // 若想在predict中根据气压计/楼梯逻辑更新,可在此处理
        double newTheta = theta; // 如果没有额外旋转或已在别处处理

        // 计算 4x4 的雅可比Fx
        // partial(newX)/partial(x) = 1
        // partial(newX)/partial(y) = 0
        // partial(newX)/partial(z) = 0
        // partial(newX)/partial(theta) = -stepLen*sin(theta)
        double f00 = 1;    double f01 = 0;    double f02 = 0;    double f03 = -stepLen*Math.sin(theta);

        // partial(newY)/partial(x) = 0
        // partial(newY)/partial(y) = 1
        // partial(newY)/partial(z) = 0
        // partial(newY)/partial(theta) = stepLen*cos(theta)
        double f10 = 0;    double f11 = 1;    double f12 = 0;    double f13 = stepLen*Math.cos(theta);

        // z 不变
        double f20 = 0;    double f21 = 0;    double f22 = 1;    double f23 = 0;

        // theta 不变
        double f30 = 0;    double f31 = 0;    double f32 = 0;    double f33 = 1;

        SimpleMatrix Fx = new SimpleMatrix(4,4,true, new double[]{
                f00,f01,f02,f03,
                f10,f11,f12,f13,
                f20,f21,f22,f23,
                f30,f31,f32,f33
        });

        // 更新状态
        state.set(0,0, newX);
        state.set(1,0, newY);
        state.set(2,0, newZ);
        state.set(3,0, newTheta);

        // P^- = Fx * P * Fx^T + Q
        covariance = Fx.mult(covariance).mult(Fx.transpose()).plus(processNoise);
    }

    /**
     * WiFi 更新(只更新 x, y)
     * z, theta 不变
     * => 观测模型 z_meas = [ x, y ]
     * => H = 2x4
     */
    public void update(double measX, double measY) {
        // 2x4
        SimpleMatrix H = new SimpleMatrix(2,4,true, new double[]{
                1,0,0,0,
                0,1,0,0
        });

        // 预测观测
        double x = state.get(0,0);
        double y = state.get(1,0);
        SimpleMatrix zPred = new SimpleMatrix(2,1);
        zPred.set(0,0, x);
        zPred.set(1,0, y);

        // 实际测量
        SimpleMatrix zMeas = new SimpleMatrix(2,1);
        zMeas.set(0,0, measX);
        zMeas.set(1,0, measY);

        // 创新
        SimpleMatrix yVec = zMeas.minus(zPred);

        // S = H P H^T + R(2x2)
        SimpleMatrix S = H.mult(covariance).mult(H.transpose()).plus(measurementNoise);

        // K = P H^T S^-1
        SimpleMatrix K = covariance.mult(H.transpose()).mult(S.invert());

        // 更新状态
        state = state.plus(K.mult(yVec));

        // 更新协方差
        SimpleMatrix IminusKH = I4.minus(K.mult(H));
        covariance = IminusKH.mult(covariance);
    }

    /**
     * 新增：气压计更新(只更新 z)
     * 观测方程: z_baro = z
     * => Hbaro = [ 0, 0, 1, 0 ]  (1x4)
     * => 由于 measurementNoise 原先是 2x2, 我们单独用 baroNoise
     */
    public void updateZ(double measZ) {
        // 1x4
        SimpleMatrix Hbaro = new SimpleMatrix(1,4,true, new double[]{
                0,0,1,0
        });

        double z = state.get(2,0);
        // 预测观测
        SimpleMatrix zPred = new SimpleMatrix(1,1);
        zPred.set(0,0, z);

        // 实际测量
        SimpleMatrix zMeas = new SimpleMatrix(1,1);
        zMeas.set(0,0, measZ);

        // 创新
        SimpleMatrix yVec = zMeas.minus(zPred);

        // S = Hbaro * P * Hbaro^T + baroNoise(1x1)
        // baroNoise 是 double, 需拼成 1x1
        double sVal = Hbaro.mult(covariance).mult(Hbaro.transpose()).get(0,0) + baroNoise;

        // K = P Hbaro^T S^-1(标量)
        // S^-1 = 1/sVal
        SimpleMatrix K = covariance.mult(Hbaro.transpose()).scale(1.0/sVal);

        // 更新状态
        state = state.plus(K.mult(yVec));

        // 更新协方差
        SimpleMatrix IminusKH = I4.minus(K.mult(Hbaro));
        covariance = IminusKH.mult(covariance);
    }

    /**
     * 返回 (x,y) 作为 LatLng (仅演示)
     * 如果要包含 z, 需另外做 enuToGeodetic(x,y,z,refLat,refLon,refAlt)
     */
    public LatLng getEstimatedPosition() {
        double x = state.get(0,0);
        double y = state.get(1,0);
        return new LatLng(x, y);
    }

    /**
     * 额外的获取 4D 状态方法
     */
    public double[] getStateVector() {
        return new double[]{
                state.get(0,0), // x
                state.get(1,0), // y
                state.get(2,0), // z
                state.get(3,0)  // theta
        };
    }

    // 若需要设置噪声
    public void setBaroNoise(double bn) { this.baroNoise = bn; }
    // ...
}