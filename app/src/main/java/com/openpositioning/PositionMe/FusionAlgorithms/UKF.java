package com.openpositioning.PositionMe.FusionAlgorithms;

public class UKF {
    // State vector: [theta, bias]
    private double theta;      // estimated heading (radians)
    private double bias;       // gyroscope bias
    private double[][] P;      // state covariance matrix
    private double Q_theta = 0.01;  // process noise variance for theta
    private double Q_bias = 0.0001; // process noise variance for bias
    private double R = 0.5;         // measurement noise variance (magnetometer)

    public UKF(double initTheta) {
        this.theta = initTheta;
        this.bias = 0;
        this.P = new double[][] {
                {1.0, 0.0},
                {0.0, 0.01}
        };
    }

    public void predict(double gyroZ, double dt) {
        double rate = gyroZ - bias;
        theta += rate * dt;

        // Normalize angle
        theta = wrapToPi(theta);

        // Jacobian F
        double[][] F = {
                {1.0, -dt},
                {0.0, 1.0}
        };

        // Process noise matrix Q
        double[][] Q = {
                {Q_theta * dt * dt, 0.0},
                {0.0, Q_bias * dt * dt}
        };

        // Predict covariance: P = F * P * F^T + Q
        P = matAdd(matMul(F, matMul(P, transpose(F))), Q);
    }

    public void update(double magTheta) {
        double y = wrapToPi(magTheta - theta); // innovation
        double S = P[0][0] + R;
        double[] K = {P[0][0] / S, P[1][0] / S}; // Kalman gain

        // Update state
        theta += K[0] * y;
        bias += K[1] * y;

        // Normalize angle
        theta = wrapToPi(theta);

        // Update covariance
        double[][] I_KH = {
                {1 - K[0], -K[0]},
                {-K[1], 1 - K[1]}
        };
        P = matMul(I_KH, P);
    }

    public double getTheta() {
        return theta;
    }

    private double wrapToPi(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    private double[][] matMul(double[][] A, double[][] B) {
        int rows = A.length;
        int cols = B[0].length;
        int inner = B.length;
        double[][] result = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                for (int k = 0; k < inner; k++) {
                    result[i][j] += A[i][k] * B[k][j];
                }
            }
        }
        return result;
    }

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

    private double[][] matAdd(double[][] A, double[][] B) {
        int rows = A.length;
        int cols = A[0].length;
        double[][] result = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                result[i][j] = A[i][j] + B[i][j];
            }
        }
        return result;
    }
}
