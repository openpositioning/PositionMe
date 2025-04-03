package com.openpositioning.PositionMe.processing.filters;

import org.ejml.simple.SimpleMatrix;

/**
 * A filter for a system that can be described with
 * x_t = F * x_t-1 + B * u - linear motion update based on the previous state and current control inputs
 * y_t = H * x_t           - an observation that is a linear function of the current state
 * https://en.wikipedia.org/wiki/Kalman_filter
 * @author Wojciech Boncela
 */
public class KalmanFilter implements Filter{
    private double[] pos;
    private SimpleMatrix covariance;

    /**
     * The constructor
     * @param pos initial position
     * @param cov initial covariance
     */
    public KalmanFilter(double[] pos, SimpleMatrix cov) {
        if (pos.length != STATE_SIZE) {
            throw new IllegalArgumentException(
                    "Kalman Filter: position should be an array of size STATE_SIZE");
        }
        this.pos = pos.clone();
        if (cov.numCols() != STATE_SIZE || cov.numRows() != STATE_SIZE) {
            throw new IllegalArgumentException(
                    "Kalman Filter: covariance should be a matrix of size STATE_SIZE x STATE_SIZE");
        }
        this.covariance = cov.copy();
    }

    /**
     * The reset function
     * @param pos the new position
     * @param cov initial covariance of the new state
     */
    @Override
    public void resetState(double[] pos, SimpleMatrix cov) {
        if (pos.length != STATE_SIZE) {
            throw new IllegalArgumentException(
                    "Kalman Filter: position should be an array of size STATE_SIZE");
        }
        this.pos = pos.clone();
        if (cov.numCols() != STATE_SIZE || cov.numRows() != STATE_SIZE) {
            throw new IllegalArgumentException(
                    "Kalman Filter: final P should be a matrix of size STATE_SIZE x STATE_SIZE");
        }
        this.covariance = cov.copy();
    }

    /**
     * @return The current position
     */
    @Override
    public double[] getPosition() {
        return pos.clone(); // Return a copy to prevent external modification
    }

    /**
     * @return The current covariance
     */
    @Override
    public SimpleMatrix getCovariance() {
        return this.covariance.copy();
    }

    /**
     * Update the position using a new observation and the motion update:
     * x_t = F * x_t-1 + B * control
     * y_t = H * x_t
     * @param control
     * @param observation
     * @param F
     * @param B
     * @param H
     * @param Q motion update covariance
     * @param R observation covariance
     * @throws IllegalArgumentException
     */
    public void step(double[] control, double[] observation, SimpleMatrix F, SimpleMatrix B,
                     SimpleMatrix H, SimpleMatrix Q, SimpleMatrix R) throws IllegalArgumentException {
        int controlSize = control.length;
        int observationSize = observation.length;

        // Verify input shapes
        if (controlSize == 0 || observationSize == 0) {
            throw new IllegalArgumentException("control or observation empty");
        }

        if (F.numRows() != STATE_SIZE || F.numCols() != STATE_SIZE) {
            throw new IllegalArgumentException("F matrix shape mismatch");
        }

        if (B.numRows() == 0 || B.numCols() != controlSize) {
            throw new IllegalArgumentException("B matrix shape mismatch");
        }

        if (H.numRows() != observationSize || H.numCols() != STATE_SIZE) {
            throw new IllegalArgumentException("H matrix shape mismatch");
        }

        if (Q.numRows() != STATE_SIZE || Q.numCols() != STATE_SIZE) {
            throw new IllegalArgumentException("Q matrix shape mismatch");
        }

        if (R.numRows() != STATE_SIZE || R.numCols() != STATE_SIZE) {
            throw new IllegalArgumentException("R matrix shape mismatch");
        }

        // Operate on a local copy in case an exception is thrown
        SimpleMatrix P = this.covariance.copy();

        // Convert vectors to column matrices
        SimpleMatrix x = new SimpleMatrix(STATE_SIZE, 1, true, this.pos);
        SimpleMatrix u = new SimpleMatrix(controlSize, 1, true, control);
        SimpleMatrix z = new SimpleMatrix(observationSize, 1, true, observation);

        // The motion update step
        // x_pred = F * x + B @ u
        // P_pred = F * P * F^T + Q
        SimpleMatrix xPred = F.mult(x).plus(B.mult(u));
        SimpleMatrix Ppred = F.mult(P).mult(F.transpose()).plus(Q);

        // The correction step
        // y = z - H * x_pred
        // S = H * P_pred * H.T + R
        // K = P_pred * H.T * np.linalg.pinv(S)
        SimpleMatrix y = z.minus(H.mult(xPred));
        SimpleMatrix S = H.mult(Ppred).mult(H.transpose()).plus(R);
        SimpleMatrix K = Ppred.mult(H.transpose()).mult(S.pseudoInverse());

        // x = x_pred + K * y
        // P = (I - K @ H) * P_pred
        x = xPred.plus(K.mult(y));
        if (x.numCols() != 1 || x.numRows() != STATE_SIZE) {
            throw new RuntimeException(
                    "Kalman Filter: final x should be a matrix of size STATE_SIZE x 1");
        }

        P = SimpleMatrix.identity(Ppred.numCols()).minus(K.mult(H)).mult(Ppred);
        if (P.numRows() != STATE_SIZE || P.numCols() != STATE_SIZE) {
            throw new RuntimeException(
                    "Kalman Filter: final P should be a matrix of size STATE_SIZE x STATE_SIZE");
        }

        // Update the filter's state and covariance
        for (int i = 0; i < STATE_SIZE; i++) {
            this.pos[i] = x.get(i, 0);
        }
        this.covariance = P.copy();
    }
}
