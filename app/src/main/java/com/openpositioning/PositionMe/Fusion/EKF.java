package com.openpositioning.PositionMe.Fusion;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.CoordinateTransform;
import com.openpositioning.PositionMe.utils.ExponentialSmoothingFilter;
import org.ejml.simple.SimpleMatrix;

/**
 * MidLevelKalmanFilter - A moderate complexity EKF:
 * State: [ bearing(rad), X(m), Y(m) ]
 * Predict step: uses "step length" + "bearing" to update next position
 * Update step: fuses GNSS or Wi-Fi measurements of (X, Y)
 */
public class EKF {

    // ----------------------------
    // 1) 常量或可调参数
    // ---------------------------
    private ExponentialSmoothingFilter smoothingFilter;
    private static final double DEFAULT_STEP_LENGTH = 0.7; // meters
    private static final double DEFAULT_BEARING_STD = Math.toRadians(10); // bearing noise std
    private static final double DEFAULT_STEP_STD = 0.5;    // step length noise std
    private static final double DEFAULT_GNSS_STD = 5;      // GNSS 位置测量标准差
    private static final double DEFAULT_WIFI_STD = 8;      // Wi-Fi 位置测量标准差
    // Opportunistic update fields
    private long lastOpUpdateTime = 0; // stores the timestamp of the last opportunistic update
    private static final long RELEVANCE_THRESHOLD_MS = 10000; // example threshold (ms)


    // ----------------------------
    // 2) 状态、协方差、噪声矩阵等
    // ----------------------------
    // 状态 Xk: 3x1 = [ bearing; x; y ]
    private SimpleMatrix Xk;   // 3x1
    // 协方差矩阵
    private SimpleMatrix Pk;   // 3x3

    // 状态转移矩阵(随每次 predict 动态更新)
    private SimpleMatrix Fk;   // 3x3
    // 过程噪声 Q
    private SimpleMatrix Qk;   // 2D for (bearing, step), but we’ll map it to 3x3 with appropriate transform
    // 观测矩阵 H: 2x3 (只观测 x, y)
    private SimpleMatrix Hk;   // 2x3
    // 观测噪声 R: 2x2 (根据 GNSS/Wi-Fi 切换)
    private SimpleMatrix Rk;   // 2x2

    // HandlerThread 便于异步计算，避免阻塞主线程
    private HandlerThread ekfThread;
    private Handler ekfHandler;

    // ----------------------------
    // 3) 其他参数
    // ----------------------------
    private boolean stopEKF = false;
    private double prevStepLength = DEFAULT_STEP_LENGTH; // 上一次步长

    public EKF() {
        // 初始化状态: 假设 bearing=0, x=0, y=0
        this.Xk = new SimpleMatrix(new double[][] {
                { 0.0 },  // bearing
                { 0.0 },  // x
                { 0.0 }   // y
        });

        // 初始化 Pk: 给个较大协方差
        this.Pk = SimpleMatrix.diag( (Math.toRadians(30))*(Math.toRadians(30)),
                100,
                100 );

        // 初始化观测矩阵 Hk: 只观测 x,y
        // y_meas = [ 0, 1, 0;  0, 0, 1 ] * Xk
        this.Hk = new SimpleMatrix(new double[][] {
                { 0.0, 1.0, 0.0 },
                { 0.0, 0.0, 1.0 }
        });

        // 初始化 Rk(可在 update 时根据 GNSS/Wi-Fi 动态切换)
        this.Rk = SimpleMatrix.diag(DEFAULT_GNSS_STD*DEFAULT_GNSS_STD,
                DEFAULT_GNSS_STD*DEFAULT_GNSS_STD);

        // 初始化 Qk: 对 bearing, step length 的过程噪声
        // (此处仅设置对(bearing, step)的协方差, 之后会映射到3x3)
        // Q2x2 = diag( bearingStd^2, stepStd^2 )
        // 但实际要映射到 3x3
        // 这里先存简单 2x2
        this.Qk = SimpleMatrix.diag(
                DEFAULT_BEARING_STD*DEFAULT_BEARING_STD,
                DEFAULT_STEP_STD*DEFAULT_STEP_STD
        );

        // 初始化 HandlerThread
        initialiseBackgroundHandler();
    }

    private void initialiseBackgroundHandler() {
        ekfThread = new HandlerThread("MidEKFThread");
        ekfThread.start();
        ekfHandler = new Handler(ekfThread.getLooper());
    }

    // ----------------------------
    // 4) Predict Step
    // ----------------------------
    public void predict(final double measuredBearing, final double measuredStepLength) {
        if (stopEKF) return;

        ekfHandler.post(new Runnable() {
            @Override
            public void run() {
                // 1) 构建对 bearing, step 的观测噪声
                //   Qk(2x2)，要映射到 3x3 -> L * Q * L^T
                //   L: 3x2
                //   Xk = [bearing; x; y]
                //   bearing更新: Xk[0] = old_bearing + (bearing - old_bearing)??
                //   这里我们简化: 直接用 measuredBearing 近似取代?
                //   但更常见做法: 令 bearing_{k+1} = bearing_{k} + delta(bearing)
                //   这里做个近似: bearing_{k+1} = measuredBearing

                // step: x_{k+1} = x_k + step * cos(bearing)
                //       y_{k+1} = y_k + step * sin(bearing)
                // 2) 构建 Fk
                //   bearing 不变(或=measuredBearing?), 这里简化只令 = measuredBearing
                //   x_{k+1} = x_k + step * cos(bearing)
                //   y_{k+1} = y_k + step * sin(bearing)

                double oldBearing = Xk.get(0,0);
                double newBearing = measuredBearing;  // 简化
                double cosB = Math.cos(newBearing);
                double sinB = Math.sin(newBearing);

                // Fk = partial derivative wrt Xk
                // d(bearing_{k+1})/d(bearing_k) = 1
                // d(x_{k+1})/d(x_k) = 1
                // d(x_{k+1})/d(bearing_k) = partial( step*cos(bearing) ) = -step*sin(bearing)?
                //   这里也可以不做得太复杂, 只做 identity + small?
                //   先给个大概:
                Fk = new SimpleMatrix(new double[][] {
                        {1.0, 0.0,  0.0},  // bearing_{k+1} ~ bearing_k
                        {0.0, 1.0,  0.0},  // x_{k+1} ~ x_k + ...
                        {0.0, 0.0,  1.0}
                });
                // 我们先直接做一个简化: Fk ~ Identity
                // 后面用 "control inputs" 方式

                // 3) 更新状态
                Xk.set(0, 0, newBearing);
                double oldX = Xk.get(1, 0);
                double oldY = Xk.get(2, 0);
                double newX = oldX + measuredStepLength * cosB;
                double newY = oldY + measuredStepLength * sinB;
                Xk.set(1, 0, newX);
                Xk.set(2, 0, newY);

                // 4) Pk 预测
                // 先做: Pk = Fk * Pk * Fk^T + L * Qk(2x2) * L^T
                // L(3x2): how bearing, step => state
                // bearing_{k+1} depends on measuredBearing? (这里就简单当 identity)
                // x_{k+1} depends on step, bearing
                //   partial x_{k+1}/partial bearing ~ - step*sin(bearing)
                //   partial x_{k+1}/partial step    ~ cos(bearing)
                // y_{k+1} depends on step, bearing
                //   partial y_{k+1}/partial bearing ~ step*cos(bearing)
                //   partial y_{k+1}/partial step    ~ sin(bearing)

                double db_dbearing = 1.0;   // bearing -> bearing
                double db_dstep    = 0.0;   // step not directly sets bearing
                // x: partial wrt bearing => -step * sin(bearing)
                //    partial wrt step    => cos(bearing)
                double dx_dbearing = -measuredStepLength * sinB;
                double dx_dstep    = cosB;
                // y
                double dy_dbearing = measuredStepLength * cosB;
                double dy_dstep    = sinB;

                SimpleMatrix L = new SimpleMatrix(new double[][] {
                        { db_dbearing, db_dstep },
                        { dx_dbearing, dx_dstep },
                        { dy_dbearing, dy_dstep }
                });

                // 过程噪声: Qk(2x2) = diag( bearing_std^2, step_std^2 )
                //  => Q3x3 = L * Qk(2x2) * L^T
                // 先算 L*Qk(2x2)*L^T
                SimpleMatrix Q_3x3 = L.mult(Qk).mult(L.transpose());

                // 最终 Pk
                SimpleMatrix temp = Fk.mult(Pk).mult(Fk.transpose());
                Pk = temp.plus(Q_3x3);

                prevStepLength = measuredStepLength;
            }
        });
    }
//    public void onOpportunisticUpdate(double[] observe, long refTime){
//        if (stopEKF) return;
//        ekfHandler.post(() -> {
//            if (isValidObservation(observe, refTime)) {
//                update(observe, refTime);
//            }
//        });
//    }
    // ----------------------------
    // 5) Update Step
    // ----------------------------
    /**
     * 进行测量更新(可来自 GNSS 或 Wi-Fi).
     * @param measEast  测得的东向坐标
     * @param measNorth 测得的北向坐标
     * @param isGNSS    true=GNSS, false=WiFi(或其它)
     */
    public void update(final double measEast, final double measNorth, final boolean isGNSS) {
        if (stopEKF) return;

        ekfHandler.post(new Runnable() {
            @Override
            public void run() {
                // 1) 根据 isGNSS 来设 Rk
                double stdPos = isGNSS ? DEFAULT_GNSS_STD : DEFAULT_WIFI_STD;
                Rk = SimpleMatrix.diag(stdPos*stdPos, stdPos*stdPos);

                // 2) 计算观测
                // Zk(2x1) = [measEast; measNorth]
                SimpleMatrix Zk = new SimpleMatrix(new double[][] {
                        { measEast },
                        { measNorth }
                });

                // 3) Innovation: Y = Zk - Hk*Xk
                SimpleMatrix Y = Zk.minus( Hk.mult(Xk) );

                // 4) S = Hk*Pk*Hk^T + Rk
                SimpleMatrix S = Hk.mult(Pk).mult(Hk.transpose()).plus(Rk);

                // 5) K = Pk*Hk^T * S^-1
                SimpleMatrix K = Pk.mult(Hk.transpose().mult(S.invert()));

                // 6) 更新状态: Xk = Xk + K*Y
                Xk = Xk.plus( K.mult(Y) );

                // 7) 更新协方差: Pk = (I - K*Hk)*Pk
                SimpleMatrix I3 = SimpleMatrix.identity(3);
                Pk = I3.minus(K.mult(Hk)).mult(Pk);

                // 也可做 Joseph form 确保对称性
            }
        });
    }

    // ----------------------------
    // 6) 获取当前状态
    // ----------------------------
    public double[] getState() {
        // bearing, x, y
        return new double[] {
                Xk.get(0, 0),
                Xk.get(1, 0),
                Xk.get(2, 0)
        };
    }

    // ----------------------------
    // 7) 停止、资源释放
    // ----------------------------
    public void stopFusion() {
        stopEKF = true;
        ekfHandler.post(new Runnable() {
            @Override
            public void run() {
                ekfThread.quitSafely();
            }
        });
    }

    // Opportunistic update method
    // For example, a new measurement (Wi-Fi or otherwise) arrives at an unexpected time.
    // We optionally check if it is still relevant, then pass it on to update().
    public void onOpportunisticUpdate(final double measEast, final double measNorth,
                                      final boolean isGNSS, final long refTime) {
        if (stopEKF) return;

        ekfHandler.post(new Runnable() {
            @Override
            public void run() {
                // 1) Check if this new measurement is relevant
                if (!checkRelevance(refTime)) {
                    // If not relevant, simply ignore or handle differently
                    return;
                }

                // 2) Update last opportunistic time
                lastOpUpdateTime = refTime;

                // 3) Call the normal update routine
                update(measEast, measNorth, isGNSS);
            }
        });
    }
    public void onStepDetected(double pdrEast, double pdrNorth, double altitude, long refTime) {
        if (stopEKF) return;

        // 使用 handler 在后台线程中执行，保证 UI 不被阻塞
        ekfHandler.post(new Runnable() {
            @Override
            public void run() {
                // 简化版：直接调用一次观测更新函数
                // 这里假设 onObservationUpdate() 方法能直接根据当前 PDR 测量更新 EKF 状态
                onObservationUpdate(pdrEast, pdrNorth, pdrEast, pdrNorth, altitude, 1);
            }
        });
    }
    // A simple example function to determine if the new measurement is relevant.
    // This can be specialized to consider how old the measurement is, how frequently updates occur, etc.
    private boolean checkRelevance(long refTime) {
        long dt = Math.abs(refTime - lastOpUpdateTime);
        if (dt <= RELEVANCE_THRESHOLD_MS) {
            // For example, we say it’s relevant if it arrives within threshold
            // Adjust logic as desired.
            return true;
        }
        return true; // Currently always returning true, if above condition not met.
    }

    public void onObservationUpdate(double observeEast, double observeNorth, double pdrEast, double pdrNorth,
                                    double altitude, double penaltyFactor){
        // If the EKF is stopped, no further processing is done.
        if (stopEKF) return;

        // Post the execution to a handler to ensure the main UI thread remains responsive.
        ekfHandler.post(new Runnable() {
            @Override
            public void run() {
                // Calculate the discrepancy between the observed and PDR data.
                double[] observation = new double[] {(pdrEast - observeEast), (pdrNorth - observeNorth)};

                // Update the EKF with the new observation and the penalty factor.
                update(observation[0], observation[1], /* 是否是GNSS */ true);

                // Retrieve the start position and reference ECEF coordinates from a singleton instance of SensorFusion.
                double[] startPosition = SensorFusion.getInstance().getGNSSLatLngAlt(true);
                double[] ecefRefCoords = SensorFusion.getInstance().getEcefRefCoords();

                // Apply a smoothing filter to the updated coordinates.
                double[] smoothedCoords = smoothingFilter.applySmoothing(new double[]{Xk.get(1, 0), Xk.get(2, 0)});

                // Notify the SensorFusion instance to update its fused location based on the smoothed EKF output.
                SensorFusion.getInstance().notifyFusedUpdate(
                        CoordinateTransform.enuToGeodetic(smoothedCoords[0], smoothedCoords[1],
                                altitude, startPosition[0], startPosition[1], ecefRefCoords)
                );
            }
        });
    }
}


