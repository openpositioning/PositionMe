package com.openpositioning.PositionMe.domain;

import android.util.Log;

/**
 * A simple Extended Kalman Filter for Pedestrian Dead Reckoning in 2D:
 * State = [x, y, velocity, heading].
 *
 * You can extend it to 3D by adding altitude, or refine the motion model if needed.
 */
public class KalmanPdrFilter {

    // -- State vector: x, y, velocity, heading
    private double[] x = new double[4];
    // Covariance matrix (4x4)
    private double[][] P = new double[4][4];

    // Process noise covariance (tune these values!)
    // Q controls how quickly the filter adapts to unexpected changes
    private final double[][] Q = {
            {0.1, 0.0, 0.0, 0.0},
            {0.0, 0.1, 0.0, 0.0},
            {0.0, 0.0, 0.1, 0.0},
            {0.0, 0.0, 0.0, 0.01}
    };

    // Constructor initializes the filter state and covariance
    public KalmanPdrFilter(double initX, double initY, double initV, double initHeading) {
        x[0] = initX;
        x[1] = initY;
        x[2] = initV;
        x[3] = initHeading;

        // Initialize P with large diagonal values to indicate high initial uncertainty
        for (int i = 0; i < 4; i++) {
            P[i][i] = 10.0;
        }
    }

    /**
     * Time update (prediction) step.
     * @param dt      Time since last update (seconds).
     * @param gyroZ   Gyro reading around z-axis (rad/s).
     */
    public void predict(double dt, double gyroZ) {
        // Current state
        double xPos    = x[0];
        double yPos    = x[1];
        double vel     = x[2];
        double heading = x[3];

        // Predicted state
        double newXPos    = xPos + vel * dt * Math.cos(heading);
        double newYPos    = yPos + vel * dt * Math.sin(heading);
        double newVel     = vel;  // simple constant speed model
        double newHeading = heading + gyroZ * dt;

        // Update state
        x[0] = newXPos;
        x[1] = newYPos;
        x[2] = newVel;
        x[3] = newHeading;

        // Build Jacobian F of the motion model
        double cosH = Math.cos(heading);
        double sinH = Math.sin(heading);
        double[][] F = {
                {1.0, 0.0, dt * cosH, -vel * dt * sinH},
                {0.0, 1.0, dt * sinH,  vel * dt * cosH},
                {0.0, 0.0, 1.0,         0.0},
                {0.0, 0.0, 0.0,         1.0}
        };

        // P = F * P * F^T + Q
        P = matrixAdd(matrixMultiply(F, matrixMultiply(P, transpose(F))), Q);
    }

    /**
     * 1) Heading measurement update, e.g. from a magnetometer or fused rotation vector.
     *    z = heading_measured
     *    Measurement function h(x) = heading.
     *    headingNoise is the measurement noise variance (rad^2).
     */
    public void updateHeading(double headingMeas, double headingNoise) {
        // Measurement matrix for heading: h(x) = x[3]
        double[] H = {0, 0, 0, 1};
        double Hx = x[3];  // predicted heading

        double y = headingMeas - Hx;  // innovation
        y = normalizeAngle(y);

        // S = H * P * H^T + R, where we use dotProduct to compute H * (P * H^T)
        double S = dotProduct(H, matrixMultiply(P, transpose(H))) + headingNoise;

        // Kalman gain: K = P * H^T / S
        double[] K = matrixMultiply(P, transpose(H));
        for (int i = 0; i < K.length; i++) {
            K[i] /= S;
        }

        // Update state: x = x + K * y
        for (int i = 0; i < 4; i++) {
            x[i] += K[i] * y;
        }

        // Update covariance: P = (I - K * H) * P
        double[][] KH = outerProduct(K, H);
        double[][] I = identity(4);
        double[][] IMKH = matrixSubtract(I, KH);
        P = matrixMultiply(IMKH, P);
    }

    /**
     * 2) Step length update: updates the filter using a step measurement.
     *    This measurement is treated as an absolute position update.
     *    posNoise is the measurement noise variance for position.
     */
    public void updateStep(double newX, double newY, double posNoise) {
        // Measurement: absolute position [x_meas, y_meas]
        double[] z = new double[]{ newX, newY };

        // Measurement function h(x) = [ x[0], x[1] ]
        double[][] H = {
                {1, 0, 0, 0},
                {0, 1, 0, 0}
        };
        double[] Hx = new double[]{ x[0], x[1] };

        // Innovation: y = z - h(x)
        double[] y = new double[]{ z[0] - Hx[0], z[1] - Hx[1] };

        // Measurement noise covariance: R = posNoise * I2
        double[][] R = {
                {posNoise, 0.0},
                {0.0, posNoise}
        };

        // S = H * P * H^T + R
        double[][] S = matrixAdd(matrixMultiply(H, matrixMultiply(P, transpose(H))), R);

        // Kalman gain: K = P * H^T * S^-1 (S is 2x2)
        double[][] PHt = matrixMultiply(P, transpose(H));
        double[][] SInv = invert2x2(S);
        double[][] K = matrixMultiply(PHt, SInv);

        // Update state: x = x + K * y
        double[] K_y = matrixVectorMultiply(K, y);
        for (int i = 0; i < 4; i++) {
            x[i] += K_y[i];
        }

        // Update covariance: P = (I - K * H) * P
        double[][] KH = matrixMultiply(K, H);
        double[][] I = identity(4);
        double[][] IMKH = matrixSubtract(I, KH);
        P = matrixMultiply(IMKH, P);
    }

    /**
     * 3) GNSS update: updates the filter using GNSS (position) measurements.
     */
    public void updateGnss(double gnssX, double gnssY, double gnssNoise) {
        updateStep(gnssX, gnssY, gnssNoise);
    }

    /**
     * Utility method to normalize an angle into the range [-pi, pi].
     */
    private double normalizeAngle(double angle) {
        while (angle > Math.PI)  angle -= 2.0 * Math.PI;
        while (angle < -Math.PI) angle += 2.0 * Math.PI;
        return angle;
    }

    // -- Public getters for the filter state
    public double getX()        { return x[0]; }
    public double getY()        { return x[1]; }
    public double getVelocity() { return x[2]; }
    public double getHeading()  { return x[3]; }

    // -------------- Matrix Utilities --------------

    /**
     * Creates an identity matrix of size n.
     */
    private double[][] identity(int n) {
        double[][] I = new double[n][n];
        for (int i = 0; i < n; i++) {
            I[i][i] = 1.0;
        }
        return I;
    }

    /**
     * Transposes a matrix.
     */
    private double[][] transpose(double[][] A) {
        double[][] At = new double[A[0].length][A.length];
        for (int i = 0; i < A.length; i++) {
            for (int j = 0; j < A[0].length; j++) {
                At[j][i] = A[i][j];
            }
        }
        return At;
    }

    /**
     * Returns the same vector. Provided for symmetry when working with matrices.
     */
    private double[] transpose(double[] A) {
        return A;
    }

    /**
     * Computes the dot product of two vectors.
     */
    private double dotProduct(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++){
            sum += a[i] * b[i];
        }
        return sum;
    }

    /**
     * Multiplies a matrix (2D array) with a vector.
     * A is an (NxM) matrix, B is an (M) vector, returns an (N) vector.
     */
    private double[] matrixMultiply(double[][] A, double[] B) {
        int N = A.length;
        int M = B.length;
        double[] result = new double[N];
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < M; j++) {
                result[i] += A[i][j] * B[j];
            }
        }
        return result;
    }

    /**
     * Multiplies two matrices.
     * A is an (nA x mA) matrix and B is an (nB x mB) matrix where mA == nB.
     */
    private double[][] matrixMultiply(double[][] A, double[][] B) {
        int nA = A.length;
        int mA = A[0].length;
        int nB = B.length;
        int mB = B[0].length;
        if (mA != nB) {
            Log.e("KalmanPdrFilter", "Matrix dimension mismatch in multiplication.");
            return null;
        }
        double[][] C = new double[nA][mB];
        for (int i = 0; i < nA; i++) {
            for (int j = 0; j < mB; j++) {
                for (int k = 0; k < mA; k++) {
                    C[i][j] += A[i][k] * B[k][j];
                }
            }
        }
        return C;
    }

    /**
     * Element-wise addition of two matrices.
     */
    private double[][] matrixAdd(double[][] A, double[][] B) {
        int n = A.length;
        int m = A[0].length;
        double[][] C = new double[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                C[i][j] = A[i][j] + B[i][j];
            }
        }
        return C;
    }

    /**
     * Multiplies a matrix with a vector (matrix-vector product).
     */
    private double[] matrixVectorMultiply(double[][] A, double[] v) {
        double[] result = new double[A.length];
        for (int i = 0; i < A.length; i++) {
            for (int j = 0; j < v.length; j++) {
                result[i] += A[i][j] * v[j];
            }
        }
        return result;
    }

    /**
     * Computes the outer product of two vectors.
     * Returns a matrix where out[i][j] = a[i] * b[j].
     */
    private double[][] outerProduct(double[] a, double[] b) {
        double[][] out = new double[a.length][b.length];
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < b.length; j++) {
                out[i][j] = a[i] * b[j];
            }
        }
        return out;
    }

    /**
     * Element-wise subtraction of two matrices.
     */
    private double[][] matrixSubtract(double[][] A, double[][] B) {
        int n = A.length;
        int m = A[0].length;
        double[][] C = new double[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                C[i][j] = A[i][j] - B[i][j];
            }
        }
        return C;
    }

    /**
     * Inverts a 2x2 matrix.
     * Suitable for measurement updates where the innovation covariance is 2x2.
     */
    private double[][] invert2x2(double[][] mat) {
        double a = mat[0][0];
        double b = mat[0][1];
        double c = mat[1][0];
        double d = mat[1][1];
        double det = a * d - b * c;
        double[][] inv = new double[2][2];
        inv[0][0] = d / det;
        inv[0][1] = -b / det;
        inv[1][0] = -c / det;
        inv[1][1] = a / det;
        return inv;
    }
}
