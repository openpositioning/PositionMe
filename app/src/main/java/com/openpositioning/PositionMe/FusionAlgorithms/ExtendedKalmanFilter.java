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
 * ExtendedKalmanFilter is used to fuse data from Traj (e.g. PDR, GNSS, WIFI, pressure, etc.),
 * * And the direction, step length, time delay and so on are processed, and finally the fused position information is output (the relative time is also returned).
 * * In replay mode, the system no longer relies on the real-time system clock, but uses a relative_timestamp recorded within Traj for latency calculation.
 * * Enables replay to be played in the same sequence as the original capture.
 * * Core state Xk = [heading, east, north]^T
 * * heading is the direction (radian), and east and north are the shift relative to the starting point in the ENU coordinate system (unit: m).
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

    // -- Replay extension: initial reference point and conversion parameters --
    //   startPosition = [latitude, longitude, altitude]
    // ecefRefCoords = Corresponding ECEF coordinates
    private double[] startPosition;
    private double[] ecefRefCoords;

    public ExtendedKalmanFilter() {
        this.outlierDetector = new OutlierDetector();
        this.smoothingFilter = new ExponentialSmoothingFilter(smoothingFactor, 2);
        this.stopEKF = false;

        // Initialize the state vector Xk: [bearing, East, North]
        this.Xk = new SimpleMatrix(new double[][]{{0}, {0}, {0}});
        // Initial covariance
        this.Pk = SimpleMatrix.diag(0, 0, 0);
        // Process noise covariance
        this.Qk = SimpleMatrix.diag(
                (sigma_dTheta * sigma_dTheta),
                sigma_ds,
                sigma_ds);
        // Measure noise covariance
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

    // Set the initial GNSS reference coordinates for ENU conversion
    public void setInitialReference(double[] startPosition, double[] ecefRefCoords) {
        this.startPosition = startPosition;
        this.ecefRefCoords = ecefRefCoords;
    }

    // Set initial time
    public void setInitialTime(long initTime) {
        this.initialiseTime = initTime;
    }

    // Reset EKF
    public void reset() {
        this.stopEKF = false;
        //The state vector goes to zero
        Xk.set(0, 0, 0); // heading
        Xk.set(1, 0, 0); // east
        Xk.set(2, 0, 0); // north
        // Covariance cleared to zero
        Pk = SimpleMatrix.diag(0, 0, 0);
        // Reset the previous step length
        this.prevStepLength = defaultStepLength;
    }

    public void stopFusion() {
        this.stopEKF = true;
        Log.d("EKF:", "Stopping EKF handler");
        this.smoothingFilter.reset();
        ekfThread.quitSafely();
    }

    // Set whether to use WiFi
    public void setUsingWifi(boolean update) {
        if (stopEKF) return;
        ekfHandler.post(() -> usingWifi = update);
    }

    /**
     * Obtain the internal ECEF reference coordinates (for accurate ENU→LatLng conversion of the external PDR Marker).
     */
    //
    public double[] getEcefRefCoords() {
        return ecefRefCoords;
    }

    /**
     * Returns the ENU (east, north) estimated by the current Kalman filter.
     * heading = Xk.get(0), east = Xk.get(1), north = Xk.get(2)
     */
    //
    public double[] getEnuPosition() {
        return new double[]{Xk.get(1, 0), Xk.get(2, 0)};
    }

    // predict
    public void predict(double theta_k, double step_k, double averageStepLength, long refTime, TurnDetector.MovementType userMovement) {
        if (stopEKF) return;

        ekfHandler.post(() -> {
            double adaptedHeading = wrapToPi((Math.PI / 2 - theta_k)); // 方向修正
            // Update the state vector first
            double oldHeading = Xk.get(0, 0);
            double oldEast = Xk.get(1, 0);
            double oldNorth = Xk.get(2, 0);

            // Calculate the new east and north increments
            double deltaEast = step_k * Math.cos(theta_k);
            double deltaNorth = step_k * Math.sin(theta_k);

            // update Xk
            double newHeading = wrapToPi(adaptedHeading);
            double newEast = oldEast + deltaEast;
            double newNorth = oldNorth + deltaNorth;

            Xk.set(0, 0, newHeading);
            Xk.set(1, 0, newEast);
            Xk.set(2, 0, newNorth);

            // update Fk
            updateFk(newHeading, step_k);
            // update Qk
            updateQk(averageStepLength, newHeading, (refTime - initialiseTime), getThetaStd(userMovement));

            // Pk = Fk * Pk * Fk^T + Qk
            SimpleMatrix FkP = Fk.mult(Pk);
            Pk = FkP.mult(Fk.transpose()).plus(Qk);

            prevStepLength = step_k;
        });
    }

    // Here observeEast/observeNorth/pdrEast/pdrNorth are ENU coordinates
    public void onObservationUpdate(double observeEast, double observeNorth,
                                    double pdrEast, double pdrNorth,
                                    double altitude, double penaltyFactor) {
        if (stopEKF) return;

        ekfHandler.post(() -> {
            // Calculate the observation vector Zk = [observeEast, observeNorth]^T
            // Calculation prediction Hk*Xk = [Xk[1], Xk[2]] = [pdrEast, pdrNorth]
            //
            //// Actually, a "difference between observation and prediction" is constructed here first
            double zEast = observeEast;
            double zNorth = observeNorth;

            // predicted East, North
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

            // Updated measurement covariance Rk
            updateRk(penaltyFactor);

            // S = Hk * Pk * Hk^T + Rk
            //  Hk = [[0,1,0],[0,0,1]]
            SimpleMatrix Hp = Hk.mult(Pk);
            SimpleMatrix S = Hp.mult(Hk.transpose()).plus(Rk);

            // K = Pk * Hk^T * S^-1
            SimpleMatrix K = Pk.mult(Hk.transpose()).mult(S.invert());

            // Xk = Xk + K*yPred
            Xk = Xk.plus(K.mult(yPred));

            // heading need wrap
            double heading = wrapToPi(Xk.get(0, 0));
            Xk.set(0, 0, heading);

            // Pk = (I - K*Hk)*Pk
            SimpleMatrix I = SimpleMatrix.identity(Pk.numRows());
            Pk = (I.minus(K.mult(Hk))).mult(Pk);
        });
    }

    /**
     * Obtain the current LatLng after fusion.
     * Xk(1) = east, Xk(2) = north, altitude can be passed into the nearest GNSS or barometric altitude calculation.
     */
    public LatLng getCurrentLatLng(double altitude) {
        if (this.startPosition == null || this.ecefRefCoords == null) {
            return null; // It is not initialized
        }
        double east = Xk.get(1, 0);
        double north = Xk.get(2, 0);
        // CoordinateTransform is used to convert ENU into latitude and longitude
        double[] ecefRef = this.ecefRefCoords;
        return CoordinateTransform.enuToGeodetic(east, north, altitude,
                startPosition[0], startPosition[1], ecefRef);
    }

    // ---------------------- Internal function ----------------------

    private void updateFk(double theta_k, double step_k) {
        double cosTheta = Math.cos(theta_k);
        double sinTheta = Math.sin(theta_k);
        // Fk size 3x3
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

        // Where (0,0) corresponds to heading; (1,1) and (2,2) correspond to east/north
        this.Qk.set(0, 0, bearing_error * bearing_error);
        this.Qk.set(1, 1, step_error    * step_error);
        this.Qk.set(2, 2, step_error    * step_error);
    }


    private void updateRk(double penaltyFactor) {
        double stdVal = usingWifi ? wifi_std : gnss_std;
        double val = stdVal * stdVal * penaltyFactor;
        // Rk size 2x2
        this.Rk.set(0, 0, val);
        this.Rk.set(1, 1, val);
    }

    private double calculateTimePenalty(long dt) {
        // dt is the difference from the initial time
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
