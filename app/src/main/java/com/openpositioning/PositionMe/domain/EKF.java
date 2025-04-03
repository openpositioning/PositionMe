package com.openpositioning.PositionMe.domain;

import com.google.android.gms.maps.model.LatLng;
import org.ejml.simple.SimpleMatrix;
import com.openpositioning.PositionMe.utils.SmoothingFilter;

/**
 * Extended Kalman Filter (EKF) with state vector [x, y, z, theta].
 */
public class EKF {

    // state = [ x, y, z, theta ]^T
    // x, y, z in meters (ENU), theta in radians
    private SimpleMatrix state;       // 4x1
    private SimpleMatrix covariance;  // 4x4

    // Process noise
    private SimpleMatrix processNoise;
    // WiFi measurement noise (updates x and y only)
    private SimpleMatrix measurementNoise;
    // WiFi standard deviation used to adjust measurement noise
    private double wifi_std = 0.7;
    // Smoothing filter instance
    private SmoothingFilter smoothingFilter = new SmoothingFilter(0.7, 2);
    // Barometer noise (updates z only)
    private double baroNoise;

    private final SimpleMatrix I4; // 4x4 identity

    /**
     * Constructor: initialize with z included
     */
    public EKF(double initX, double initY, double initZ, double initHeading) {
        state = new SimpleMatrix(4,1);
        state.set(0,0, initX);
        state.set(1,0, initY);
        state.set(2,0, initZ);
        state.set(3,0, initHeading);

        covariance = SimpleMatrix.identity(4).scale(1.0);
        processNoise = SimpleMatrix.identity(4).scale(0.1);
        measurementNoise = SimpleMatrix.identity(2).scale(0.5);
        baroNoise = 0.8;

        I4 = SimpleMatrix.identity(4);
    }

    /**
     * Constructor with default z = 0
     */
    public EKF(double initX, double initY, double initHeading) {
        this(initX, initY, 0.0, initHeading);
    }

    /**
     * Predict step using PDR
     * x_k = x_{k-1} + stepLen * sin(theta)
     * y_k = y_{k-1} + stepLen * cos(theta)
     * z remains unchanged; theta is updated using gyro
     */
    public void predict(double stepLen, double gyroTheta) {
        double x = state.get(0,0);
        double y = state.get(1,0);
        double z = state.get(2,0);
        double theta = state.get(3,0);

        double newTheta = wrapToPi(theta + gyroTheta);
        double newX = x + stepLen * Math.sin(newTheta);
        double newY = y + stepLen * Math.cos(newTheta);
        double newZ = z;      // z does not change, can be changed if needed

        // Calculate the 4x4 Fx
        // partial(newX)/partial(x) = 1
        // partial(newX)/partial(y) = 0
        // partial(newX)/partial(z) = 0
        // partial(newX)/partial(theta) = -stepLen*sin(theta)
        double f00 = 1;    double f01 = 0;    double f02 = 0;    double f03 = stepLen*Math.cos(newTheta);

        // partial(newY)/partial(x) = 0
        // partial(newY)/partial(y) = 1
        // partial(newY)/partial(z) = 0
        // partial(newY)/partial(theta) = stepLen*cos(theta)
        double f10 = 0;    double f11 = 1;    double f12 = 0;    double f13 = -stepLen*Math.sin(newTheta);

        // z not changed
        double f20 = 0;    double f21 = 0;    double f22 = 1;    double f23 = 0;

        // theta not changed
        double f30 = 0;    double f31 = 0;    double f32 = 0;    double f33 = 1;

        SimpleMatrix Fx = new SimpleMatrix(4,4,true, new double[]{
                f00,f01,f02,f03,
                f10,f11,f12,f13,
                f20,f21,f22,f23,
                f30,f31,f32,f33
        });

        state.set(0,0, newX);
        state.set(1,0, newY);
        state.set(2,0, newZ);
        state.set(3,0, newTheta);

        // P^- = Fx * P * Fx^T + Q
        covariance = Fx.mult(covariance).mult(Fx.transpose()).plus(processNoise);
    }

    // Helper method to wrap angle to [-pi, pi]
    private double wrapToPi(double angle) {
        while(angle > Math.PI) angle -= 2*Math.PI;
        while(angle < -Math.PI) angle += 2*Math.PI;
        return angle;
    }

    /**
     * WiFi update (updates x and y only)
     * Observation model: z_meas = [ x, y ]
     * H is 2x4
     */
    public void update(double measX, double measY, double penaltyFactor) {
        updateRk(penaltyFactor);

        // 2x4
        SimpleMatrix H = new SimpleMatrix(2,4,true, new double[]{
                1,0,0,0,
                0,1,0,0
        });

        // predict the behavior.
        double x = state.get(0,0);
        double y = state.get(1,0);
        SimpleMatrix zPred = new SimpleMatrix(2,1);
        zPred.set(0,0, x);
        zPred.set(1,0, y);

        SimpleMatrix zMeas = new SimpleMatrix(2,1);
        zMeas.set(0,0, measX);
        zMeas.set(1,0, measY);

        SimpleMatrix yVec = zMeas.minus(zPred);

        // S = H P H^T + R(2x2)
        SimpleMatrix S = H.mult(covariance).mult(H.transpose()).plus(measurementNoise);

        // K = P H^T S^-1
        SimpleMatrix K = covariance.mult(H.transpose()).mult(S.invert());

        state = state.plus(K.mult(yVec));

        // update
        SimpleMatrix IminusKH = I4.minus(K.mult(H));
        covariance = IminusKH.mult(covariance);

        // Smooth the data
        double[] smoothedCoords = smoothingFilter.applySmoothing(new double[]{state.get(0,0), state.get(1,0)});
        state.set(0, 0, smoothedCoords[0]);
        state.set(1, 0, smoothedCoords[1]);
    }

    /**
     * Barometer update (updates z only)
     * Observation model: z_meas = z
     * H is 1x4
     */
    public void updateZ(double measZ) {
        // 1x4
        SimpleMatrix Hbaro = new SimpleMatrix(1,4,true, new double[]{
                0,0,1,0
        });

        double z = state.get(2,0);
        // predict
        SimpleMatrix zPred = new SimpleMatrix(1,1);
        zPred.set(0,0, z);

        SimpleMatrix zMeas = new SimpleMatrix(1,1);
        zMeas.set(0,0, measZ);

        SimpleMatrix yVec = zMeas.minus(zPred);

        // S = Hbaro * P * Hbaro^T + baroNoise(1x1)
        // baroNoise is double, needs to be 1x1
        double sVal = Hbaro.mult(covariance).mult(Hbaro.transpose()).get(0,0) + baroNoise;

        // K = P Hbaro^T S^-1(scalar)
        // S^-1 = 1/sVal
        SimpleMatrix K = covariance.mult(Hbaro.transpose()).scale(1.0/sVal);

        state = state.plus(K.mult(yVec));

        // update
        SimpleMatrix IminusKH = I4.minus(K.mult(Hbaro));
        covariance = IminusKH.mult(covariance);
    }

    /**
     * Returns estimated geographic coordinates in LatLng (converted from ENU)
     */
    public LatLng getEstimatedPosition(double refLat, double refLon, double refAlt) {
        double x = state.get(0,0);
        double y = state.get(1,0);
        double z = state.get(2,0);
        return com.openpositioning.PositionMe.utils.CoordinateTransform.enuToGeodetic(x, y, z, refLat, refLon, refAlt);
    }

    /**
     * Returns the full 4D state vector
     */
    public double[] getStateVector() {
        return new double[]{
                state.get(0,0), // x
                state.get(1,0), // y
                state.get(2,0), // z
                state.get(3,0)  // theta
        };
    }

    public double getHeading() {
        // Return the theta component from the state vector
        return state.get(3, 0);
    }

    public void setBaroNoise(double bn) { this.baroNoise = bn; }

    /**
     * Dynamically update measurement noise using penalty factor
     */
    private void updateRk(double penaltyFactor) {
        measurementNoise.set(0, 0, (wifi_std * wifi_std) * penaltyFactor);
        measurementNoise.set(0, 1, 0);
        measurementNoise.set(1, 0, 0);
        measurementNoise.set(1, 1, (wifi_std * wifi_std) * penaltyFactor);
    }

    /**
     * Perform recursive correction using PDR position and smoothing
     */
    public void performRecursiveCorrection(double pdrX, double pdrY, double altitude, double penaltyFactor) {
        double predictedX = state.get(0,0);
        double predictedY = state.get(1,0);

        // Calculate the observation deviation (this can be adjusted to a difference or directly use pdrX, pdrY as needed).
        double innovationX = pdrX - predictedX;
        double innovationY = pdrY - predictedY;

        // Perform correction using the update method
        update(pdrX, pdrY, penaltyFactor);

        double[] smoothedCoords = smoothingFilter.applySmoothing(new double[]{state.get(0,0), state.get(1,0)});
        state.set(0, 0, smoothedCoords[0]);
        state.set(1, 0, smoothedCoords[1]);
    }

    /**
     * GNSS update (updates x, y, z)
     * GNSS is usually more accurate, so noise is lower
     */
    public void updateGNSS(double measX, double measY, double measZ, double penaltyFactor) {
        // 3x4 view matrix
        SimpleMatrix H_gnss = new SimpleMatrix(3, 4, true, new double[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0
        });

        // predict value
        double x = state.get(0, 0);
        double y = state.get(1, 0);
        double z = state.get(2, 0);
        SimpleMatrix zPred = new SimpleMatrix(3, 1);
        zPred.set(0, 0, x);
        zPred.set(1, 0, y);
        zPred.set(2, 0, z);

        SimpleMatrix zMeas = new SimpleMatrix(3, 1);
        zMeas.set(0, 0, measX);
        zMeas.set(1, 0, measY);
        zMeas.set(2, 0, measZ);

        SimpleMatrix yVec = zMeas.minus(zPred);

        // S = H P H^T + R(3x3)
        SimpleMatrix S = H_gnss.mult(covariance).mult(H_gnss.transpose()).plus(SimpleMatrix.identity(3).scale(0.3 * penaltyFactor));

        // K = P H^T S^-1
        SimpleMatrix K = covariance.mult(H_gnss.transpose()).mult(S.invert());

        state = state.plus(K.mult(yVec));

        // update
        SimpleMatrix IminusKH = I4.minus(K.mult(H_gnss));
        covariance = IminusKH.mult(covariance);

        // 数据平滑：调用平滑滤波器并更新状态
        double[] smoothedCoords = smoothingFilter.applySmoothing(new double[]{state.get(0,0), state.get(1,0)});
        state.set(0, 0, smoothedCoords[0]);
        state.set(1, 0, smoothedCoords[1]);
    }
    public double[] getEstimatedPositionENU() {
        return new double[]{state.get(0,0), state.get(1,0)};
    }

    public void resetPosition(double newX, double newY) {
        state.set(0, 0, newX);  // set x
        state.set(1, 0, newY);  // set y

        covariance.set(0, 0, 1.0);
        covariance.set(1, 1, 1.0);
        covariance.set(0, 1, 0.0);
        covariance.set(1, 0, 0.0);

    }
}