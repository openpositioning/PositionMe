package com.openpositioning.PositionMe.sensors.filters;

import android.util.Log;

import org.ejml.simple.SimpleMatrix;

import java.util.Objects;

/**
 * An adapter for a Kalman filter.
 *
 * @author Wojciech Boncela
 */
public class KalmanFilterAdapter implements FilterAdapter {
    private KalmanFilter filter;
    private double lastUpdateTimestamp;
    private double[] lastPdrPos;
    private SimpleMatrix pdrCov;

    /**
     * The constructor
     * @param initPos Initial position
     * @param initCov Initial covariance
     * @param initTimestamp The timestamp of the initial position
     * @param pdrCov Covariance used for motion update (more details in update())
     */
    public KalmanFilterAdapter(double[] initPos, SimpleMatrix initCov, double initTimestamp,
                               SimpleMatrix pdrCov) {
        if (initPos.length != 2 || initCov.numRows() != 2 || initCov.numCols() != 2
            || pdrCov.numCols() != 2 || pdrCov.numRows() != 2) {
            throw new IllegalArgumentException("Kalman Filter Adapter: bad initial" +
                    " position or/and covariance");
        }

        this.filter = new KalmanFilter(initPos, initCov);
        this.lastPdrPos = initPos.clone();
        this.lastUpdateTimestamp = initTimestamp;
        this.pdrCov = pdrCov;
    }

    /**
     * Resets the filter's state and covariance
     * @param pos The new position
     * @param cov Initial covariance
     * @param pdrPos A PDR reading recorded at the same time as the new position
     * @param timestamp The timestamp of the new position
     * @return
     */
    @Override
    public boolean reset(double[] pos, SimpleMatrix cov, double[] pdrPos, double timestamp) {
        try {
            filter.resetState(pos, cov);
        } catch (Exception e) {
            Log.e("SensorFusion",
                    "Failed to reset KalmanFilterAdapter: " +
                            Objects.requireNonNull(e.getMessage()));
            return false;
        }
        lastPdrPos = pdrPos;
        lastUpdateTimestamp = timestamp;
        return true;
    }

    /**
     * Performs the update and correction step of the Kalman filter. PDR data is used for
     * the motion update step by subtracting the most recent value from the previous one.
     * The received value is divided by the timestamp difference to obtain an average velocity.
     * The final form of the Kalman filter step:
     * x_t = x_t - 1 + delta_t * v_pdr
     * y_t = x_t
     * @param newPdrPos A PDR reading recorded at the same time as the observation
     * @param observedPos A new observation
     * @param timestamp The timestamp of the new observation
     * @param observedCov The observation's covariance
     * @return true if succeeded, false otherwise
     */
    @Override
    public boolean update(double[] newPdrPos, double[] observedPos, double timestamp,
                       SimpleMatrix observedCov) {
        double delta_t = timestamp - lastUpdateTimestamp;
        if (delta_t <= 0) {
            Log.w("SensorFusion", "delta t <= 0");
        }

        // Compute average velocity between steps using PDR readings
        double[] velocity = {
                (newPdrPos[0] - lastPdrPos[0]) / delta_t,
                (newPdrPos[1] - lastPdrPos[1]) / delta_t
        };
        SimpleMatrix F = SimpleMatrix.identity(2);
        SimpleMatrix B = new SimpleMatrix(new double[][]{
                {delta_t, 0      },
                {0      , delta_t}
        });
        SimpleMatrix H = SimpleMatrix.identity(2);
        try {
            filter.step(velocity, observedPos, F, B, H, getQMatrix(velocity), observedCov);
        } catch (Exception e) {
            Log.e("SensorFusion", "KalmanFilter step failed: " + e.getMessage());
            return false;
        }

        // Update the last PDR reading
        lastPdrPos = newPdrPos.clone();
        lastUpdateTimestamp = timestamp;
        return true;
    }

    /**
     * @return The current position
     */
    @Override
    public double[] getPos() {
        return this.filter.getPosition();
    }

    /**
     * @return The current covariance
     */
    @Override
    public SimpleMatrix getCovariance() {
        return this.filter.getCovariance();
    }

    private SimpleMatrix getQMatrix(double[] velocity) {
        // Use the R * Cov * R^T formula to align the PDR covariance with the velocity vector
        double theta = Math.atan2(velocity[0], velocity[1]);
        SimpleMatrix R = new SimpleMatrix(new double[][] {
                { Math.cos(theta), -Math.sin(theta) },
                { Math.sin(theta),  Math.cos(theta) }
        });
        return R.mult(this.pdrCov).mult(R.transpose());
    }
}
