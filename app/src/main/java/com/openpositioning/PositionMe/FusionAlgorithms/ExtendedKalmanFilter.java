package com.openpositioning.PositionMe.FusionAlgorithms;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.openpositioning.PositionMe.Method.CoordinateTransform;
import com.openpositioning.PositionMe.Method.ExponentialSmoothingFilter;
import com.openpositioning.PositionMe.Method.OutlierDetector;
import com.openpositioning.PositionMe.Method.TurnDetector;
import com.google.android.gms.maps.model.LatLng;

import org.ejml.simple.SimpleMatrix;

/**
 * ExtendedKalmanFilter is used to fuse data from Traj (e.g., PDR, GNSS, WiFi, pressure, etc.)
 * and handle heading, step length, time delay, etc., to output fused position information
 * (including relative timestamp).
 *
 * In replay mode, the system does not rely on the real-time system clock,
 * but instead uses the relative_timestamp recorded within Traj to calculate delays,
 * allowing the replay to follow the original capture timing.
 *
 * Core state Xk = [ heading, east, north ]^T
 * heading is the direction (radian), east/north are displacements in the ENU coordinate system (unit: meters)
 */
public class ExtendedKalmanFilter {

    // Parameters
    private final static long relevanceThreshold = 5000;
    private final static double stepPercentageError = 0.1;
    private final static double stepMisdirection = 0.2;
    private final static double defaultStepLength = 0.7;
    private final static long maxElapsedTimeForMaxPenalty = 6000;
    private final static long maxElapsedTimeForBearingPenalty = 15;
    private final static double maxBearingPenalty = Math.toRadians(22.5);
    private final static double sigma_dTheta = Math.toRadians(15);
    private final static double sigma_dPseudo = Math.toRadians(8);
    private final static double sigma_dStraight = Math.toRadians(2);
    private final static double smoothingFactor = 0.35;
    private double sigma_ds = 1;
    private double sigma_north_meas = 10;
    private double sigma_east_meas = 10;
    private double wifi_std = 10;
    private double gnss_std = 5;

    // Matrices and state
    private SimpleMatrix Fk;
    private SimpleMatrix Qk;
    private SimpleMatrix Hk;
    private SimpleMatrix Rk;
    private SimpleMatrix Pk;
    private SimpleMatrix Xk;

    // For replay
    private long initialiseTime;

    // Flags
    private boolean usingWifi;
    private boolean stopEKF;
    private double prevStepLength;

    // Handler thread for asynchronous processing
    private HandlerThread ekfThread;
    private Handler ekfHandler;

    // External modules
    private OutlierDetector outlierDetector;
    private ExponentialSmoothingFilter smoothingFilter;

    // --- Replay extension: initial reference point and conversion parameters ---
    //   startPosition = [latitude, longitude, altitude]
    //   ecefRefCoords = corresponding ECEF coordinates
    private double[] startPosition;
    private double[] ecefRefCoords;

    public ExtendedKalmanFilter() {
        this.outlierDetector = new OutlierDetector();
        this.smoothingFilter = new ExponentialSmoothingFilter(smoothingFactor, 2);
        this.stopEKF = false;

        // Initialize state vector Xk: [heading, East, North]
        this.Xk = new SimpleMatrix(new double[][]{{0}, {0}, {0}});
        // Initial covariance
        this.Pk = SimpleMatrix.diag(0, 0, 0);
        // Process noise covariance
        this.Qk = SimpleMatrix.diag(
                (sigma_dTheta * sigma_dTheta),
                sigma_ds,
                sigma_ds);
        // Measurement noise covariance
        this.Rk = SimpleMatrix.diag((sigma_east_meas * sigma_east_meas), (sigma_north_meas * sigma_north_meas));
        // Observation matrix
        this.Hk = new SimpleMatrix(new double[][]{{0, 1, 0}, {0, 0, 1}});

        this.initialiseTime = 0;
        this.prevStepLength = defaultStepLength;
        this.usingWifi = false;

        initialiseBackgroundHandler();
    }

    private void initialiseBackgroundHandler() {
        ekfThread = new HandlerThread("EKFProcessingThread");
        ekfThread.start();
        ekfHandler = new Handler(ekfThread.getLooper());
    }

    // Set initial GNSS reference coordinates for ENU conversion
    public void setInitialReference(double[] startPosition, double[] ecefRefCoords) {
        this.startPosition = startPosition;
        this.ecefRefCoords = ecefRefCoords;
    }

    // Set initial timestamp
    public void setInitialTime(long initTime) {
        this.initialiseTime = initTime;
    }

    // Reset the EKF
    public void reset() {
        this.stopEKF = false;
        // Reset state vector
        Xk.set(0, 0, 0); // heading
        Xk.set(1, 0, 0); // east
        Xk.set(2, 0, 0); // north
        // Reset covariance
        Pk = SimpleMatrix.diag(0, 0, 0);
        // Reset previous step length
        this.prevStepLength = defaultStepLength;
    }

    public void stopFusion() {
        this.stopEKF = true;
        Log.d("EKF:", "Stopping EKF handler");
        this.smoothingFilter.reset();
        ekfThread.quitSafely();
    }

    // Set whether WiFi is being used
    public void setUsingWifi(boolean update) {
        if (stopEKF) return;
        ekfHandler.post(() -> usingWifi = update);
    }

    /**
     * Get internal ECEF reference coordinates (used externally for precise ENU→LatLng conversion of PDR markers).
     */
    public double[] getEcefRefCoords() {
        return ecefRefCoords;
    }

    /**
     * Return current ENU position (east, north) estimated by the Kalman filter.
     * heading = Xk.get(0), east = Xk.get(1), north = Xk.get(2)
     */
    public double[] getEnuPosition() {
        return new double[]{Xk.get(1, 0), Xk.get(2, 0)};
    }

    // Prediction
    public void predict(double theta_k, double step_k, double averageStepLength, long refTime, TurnDetector.MovementType userMovement) {
        if (stopEKF) return;

        ekfHandler.post(() -> {
            double adaptedHeading = wrapToPi((Math.PI / 2 - theta_k)); // Correct heading
            // Update state vector
            double oldHeading = Xk.get(0, 0);
            double oldEast = Xk.get(1, 0);
            double oldNorth = Xk.get(2, 0);

            // Compute delta for east and north
            double deltaEast = step_k * Math.cos(theta_k);
            double deltaNorth = step_k * Math.sin(theta_k);

            // Update Xk
            double newHeading = wrapToPi(adaptedHeading);
            double newEast = oldEast + deltaEast;
            double newNorth = oldNorth + deltaNorth;

            Xk.set(0, 0, newHeading);
            Xk.set(1, 0, newEast);
            Xk.set(2, 0, newNorth);

            // Update Fk
            updateFk(newHeading, step_k);
            // Update Qk
            updateQk(averageStepLength, newHeading, (refTime - initialiseTime), getThetaStd(userMovement));

            // Pk = Fk * Pk * Fk^T + Qk
            SimpleMatrix FkP = Fk.mult(Pk);
            Pk = FkP.mult(Fk.transpose()).plus(Qk);

            prevStepLength = step_k;
        });
    }

    // observeEast / observeNorth / pdrEast / pdrNorth are all in ENU coordinates
    public void onObservationUpdate(double observeEast, double observeNorth,
                                    double pdrEast, double pdrNorth,
                                    double altitude, double penaltyFactor) {
        if (stopEKF) return;

        ekfHandler.post(() -> {
            // Construct observation vector Zk = [ observeEast, observeNorth ]^T
            // Predicted Hk*Xk = [ Xk[1], Xk[2] ] = [ pdrEast, pdrNorth ]
            // Compute difference between observation and prediction: y_pred = Zk - Hk*Xk
            double zEast = observeEast;
            double zNorth = observeNorth;

            // Predicted East, North
            double predEast = pdrEast;
            double predNorth = pdrNorth;

            double diffEast = zEast - predEast;
            double diffNorth = zNorth - predNorth;

            SimpleMatrix Zk = new SimpleMatrix(2, 1);
            Zk.set(0, 0, zEast);
            Zk.set(1, 0, zNorth);

            SimpleMatrix yPred = new SimpleMatrix(2, 1);
            yPred.set(0, 0, diffEast);
            yPred.set(1, 0, diffNorth);

            // Update measurement covariance Rk
            updateRk(penaltyFactor);

            // S = Hk * Pk * Hk^T + Rk
            SimpleMatrix Hp = Hk.mult(Pk);
            SimpleMatrix S = Hp.mult(Hk.transpose()).plus(Rk);

            // K = Pk * Hk^T * S^-1
            SimpleMatrix K = Pk.mult(Hk.transpose()).mult(S.invert());

            // Xk = Xk + K*yPred
            Xk = Xk.plus(K.mult(yPred));

            // Wrap heading
            double heading = wrapToPi(Xk.get(0, 0));
            Xk.set(0, 0, heading);

            // Pk = (I - K*Hk)*Pk
            SimpleMatrix I = SimpleMatrix.identity(Pk.numRows());
            Pk = (I.minus(K.mult(Hk))).mult(Pk);
        });
    }

    /**
     * Get the current fused LatLng.
     * Xk(1) = east, Xk(2) = north, altitude can be from latest GNSS or barometer reading.
     */
    public LatLng getCurrentLatLng(double altitude) {
        if (this.startPosition == null || this.ecefRefCoords == null) {
            return null; // Not initialized yet
        }
        double east = Xk.get(1, 0);
        double north = Xk.get(2, 0);
        // Convert ENU to LatLng using CoordinateTransform
        double[] ecefRef = this.ecefRefCoords;
        return CoordinateTransform.enuToGeodetic(east, north, altitude,
                startPosition[0], startPosition[1], ecefRef);
    }

    // ---------------------- Internal methods ----------------------

    private void updateFk(double theta_k, double step_k) {
        double cosTheta = Math.cos(theta_k);
        double sinTheta = Math.sin(theta_k);
        // Fk is a 3x3 matrix
        this.Fk = new SimpleMatrix(new double[][]{
                {1, 0, 0},
                {step_k * cosTheta, 1, 0},
                {-step_k * sinTheta, 0, 1}
        });
    }

    private void updateQk(double averageStepLength, double theta_k, long dt, double thetaStd) {
        double penaltyFactor = calculateTimePenalty(dt);
        double step_error = (stepPercentageError * averageStepLength + stepMisdirection) * penaltyFactor;
        double bearing_error = calculateBearingPenalty(thetaStd, dt);

        // (0,0) corresponds to heading; (1,1) and (2,2) to east / north
        this.Qk.set(0, 0, bearing_error * bearing_error);
        this.Qk.set(1, 1, step_error    * step_error);
        this.Qk.set(2, 2, step_error    * step_error);
    }

    private void updateRk(double penaltyFactor) {
        double stdVal = usingWifi ? wifi_std : gnss_std;
        double val = stdVal * stdVal * penaltyFactor;
        // Rk is a 2x2 matrix
        this.Rk.set(0, 0, val);
        this.Rk.set(1, 1, val);
    }

    private double calculateTimePenalty(long dt) {
        // dt is the time difference from the initial timestamp
        double penaltyFactor = 1.0 + 0.5 * Math.min(dt, maxElapsedTimeForMaxPenalty) / maxElapsedTimeForMaxPenalty;
        return penaltyFactor;
    }

    private double calculateBearingPenalty(double thetaStd, long dt) {
        double elapsedTimeFraction = Math.min(dt / 60000.0, maxElapsedTimeForBearingPenalty) / maxElapsedTimeForBearingPenalty;
        double penalty = thetaStd + (maxBearingPenalty - thetaStd) * elapsedTimeFraction;
        return penalty;
    }

    private double getThetaStd(TurnDetector.MovementType userMovement) {
        switch (userMovement) {
            case TURN:
                return sigma_dTheta;
            case PSEUDO_TURN:
                return sigma_dPseudo;
            case STRAIGHT:
                return sigma_dStraight;
            default:
                return sigma_dTheta;
        }
    }

    // Wrap angle to [-pi, pi]
    private static double wrapToPi(double x) {
        double bearing = x % (2 * Math.PI);
        if (bearing < -Math.PI) {
            bearing += 2 * Math.PI;
        } else if (bearing > Math.PI) {
            bearing -= 2 * Math.PI;
        }
        return bearing;
    }
}
