package com.openpositioning.PositionMe.fusion;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.CoordinateConverter;

import org.ejml.simple.SimpleMatrix;

/**
 * An implementation of a Kalman Filter for fusing position data from PDR and GNSS.
 * This fusion algorithm will be extended to include WiFi positioning in future updates.
 */
public class KalmanFilterFusion implements IPositionFusionAlgorithm {
    private static final String TAG = "KalmanFilterFusion";

    // Constants for the Kalman filter
    private static final double PDR_NOISE_SCALE = 0.1;  // Process noise scale factor for PDR
    private static final double GNSS_NOISE = 5.0;       // Measurement noise for GNSS (in meters)

    // State variables
    private SimpleMatrix stateVector;     // [x, y, vx, vy]^T - position and velocity
    private SimpleMatrix covarianceMatrix; // State covariance matrix P

    // The reference point for ENU coordinates
    private double[] referencePosition;   // [lat, lng, alt]

    // Timing variables
    private long lastUpdateTime;

    // Matrices
    private SimpleMatrix processMatrix;     // State transition matrix F
    private SimpleMatrix processNoiseMatrix; // Process noise matrix Q
    private SimpleMatrix measurementMatrix;  // Measurement matrix H
    private SimpleMatrix identityMatrix;     // Identity matrix for calculations

    /**
     * Creates a new Kalman filter for position fusion.
     *
     * @param referencePosition The reference position [lat, lng, alt] for ENU coordinates
     */
    public KalmanFilterFusion(double[] referencePosition) {
        this.referencePosition = referencePosition;

        // Initialize state vector [x, y, vx, vy]^T
        stateVector = new SimpleMatrix(4, 1);

        // Initialize covariance matrix with high uncertainty
        covarianceMatrix = SimpleMatrix.identity(4);
        covarianceMatrix = covarianceMatrix.scale(100); // High initial uncertainty

        // Identity matrix for calculations
        identityMatrix = SimpleMatrix.identity(4);

        // Initialize measurement matrix - we only measure position (x, y)
        measurementMatrix = new SimpleMatrix(2, 4);
        measurementMatrix.set(0, 0, 1.0); // Measure x
        measurementMatrix.set(1, 1, 1.0); // Measure y

        // Initialize process noise matrix
        processNoiseMatrix = SimpleMatrix.identity(4);

        // Initialize process matrix (will be updated with each time step)
        processMatrix = SimpleMatrix.identity(4);

        // Initialize timestamp
        lastUpdateTime = System.currentTimeMillis();

        Log.d(TAG, "Kalman filter initialized with reference position: " +
                referencePosition[0] + ", " + referencePosition[1] + ", " + referencePosition[2]);
    }

    @Override
    public void processPdrUpdate(float eastMeters, float northMeters, float altitude) {
        long currentTime = System.currentTimeMillis();
        double deltaTime = (currentTime - lastUpdateTime) / 1000.0; // Convert to seconds

        if (deltaTime <= 0) {
            Log.w(TAG, "Invalid time delta: " + deltaTime);
            return;
        }

        // If this is the first PDR update, initialize the state
        if (stateVector.get(0, 0) == 0 && stateVector.get(1, 0) == 0) {
            stateVector.set(0, 0, eastMeters);
            stateVector.set(1, 0, northMeters);
            lastUpdateTime = currentTime;
            return;
        }

        // Update process matrix F with the time delta
        updateProcessMatrix(deltaTime);

        // Calculate PDR velocity
        double dx = eastMeters - stateVector.get(0, 0);
        double dy = northMeters - stateVector.get(1, 0);
        double vx = dx / deltaTime;
        double vy = dy / deltaTime;

        // Predict step
        SimpleMatrix predictedState = processMatrix.mult(stateVector);
        SimpleMatrix predictedCovariance = processMatrix.mult(covarianceMatrix).mult(processMatrix.transpose())
                .plus(processNoiseMatrix.scale(PDR_NOISE_SCALE));

        // Create measurement vector from PDR
        SimpleMatrix measurementVector = new SimpleMatrix(2, 1);
        measurementVector.set(0, 0, eastMeters);
        measurementVector.set(1, 0, northMeters);

        // Create measurement noise matrix for PDR
        SimpleMatrix measurementNoiseMatrix = SimpleMatrix.identity(2);
        double pdrNoise = PDR_NOISE_SCALE * Math.sqrt(dx*dx + dy*dy);
        measurementNoiseMatrix = measurementNoiseMatrix.scale(Math.max(pdrNoise, 0.1));

        // Update step
        SimpleMatrix innovation = measurementVector.minus(measurementMatrix.mult(predictedState));
        SimpleMatrix innovationCovariance = measurementMatrix.mult(predictedCovariance).mult(measurementMatrix.transpose())
                .plus(measurementNoiseMatrix);
        SimpleMatrix kalmanGain = predictedCovariance.mult(measurementMatrix.transpose()).mult(innovationCovariance.invert());

        // Update state and covariance
        stateVector = predictedState.plus(kalmanGain.mult(innovation));
        covarianceMatrix = identityMatrix.minus(kalmanGain.mult(measurementMatrix)).mult(predictedCovariance);

        // Update velocity components of state vector
        stateVector.set(2, 0, vx);
        stateVector.set(3, 0, vy);

        lastUpdateTime = currentTime;

        Log.d(TAG, "PDR update: E=" + eastMeters + ", N=" + northMeters +
                " -> State=" + stateVector.get(0, 0) + ", " + stateVector.get(1, 0));
    }

    @Override
    public void processGnssUpdate(LatLng position, double altitude) {
        long currentTime = System.currentTimeMillis();
        double deltaTime = (currentTime - lastUpdateTime) / 1000.0; // Convert to seconds

        if (deltaTime <= 0) {
            Log.w(TAG, "Invalid time delta: " + deltaTime);
            return;
        }

        // Convert GNSS position to ENU
        double[] enu = CoordinateConverter.geodetic2Enu(
                position.latitude, position.longitude, altitude,
                referencePosition[0], referencePosition[1], referencePosition[2]
        );

        // If this is the first position, initialize the state
        if (stateVector.get(0, 0) == 0 && stateVector.get(1, 0) == 0) {
            stateVector.set(0, 0, enu[0]);
            stateVector.set(1, 0, enu[1]);
            lastUpdateTime = currentTime;
            return;
        }

        // Update process matrix F with the time delta
        updateProcessMatrix(deltaTime);

        // Predict step
        SimpleMatrix predictedState = processMatrix.mult(stateVector);
        SimpleMatrix predictedCovariance = processMatrix.mult(covarianceMatrix).mult(processMatrix.transpose())
                .plus(processNoiseMatrix);

        // Create measurement vector from GNSS
        SimpleMatrix measurementVector = new SimpleMatrix(2, 1);
        measurementVector.set(0, 0, enu[0]);
        measurementVector.set(1, 0, enu[1]);

        // Create measurement noise matrix for GNSS
        SimpleMatrix measurementNoiseMatrix = SimpleMatrix.identity(2).scale(GNSS_NOISE * GNSS_NOISE);

        // Update step
        SimpleMatrix innovation = measurementVector.minus(measurementMatrix.mult(predictedState));
        SimpleMatrix innovationCovariance = measurementMatrix.mult(predictedCovariance).mult(measurementMatrix.transpose())
                .plus(measurementNoiseMatrix);
        SimpleMatrix kalmanGain = predictedCovariance.mult(measurementMatrix.transpose()).mult(innovationCovariance.invert());

        // Update state and covariance
        stateVector = predictedState.plus(kalmanGain.mult(innovation));
        covarianceMatrix = identityMatrix.minus(kalmanGain.mult(measurementMatrix)).mult(predictedCovariance);

        lastUpdateTime = currentTime;

        Log.d(TAG, "GNSS update: E=" + enu[0] + ", N=" + enu[1] +
                " -> State=" + stateVector.get(0, 0) + ", " + stateVector.get(1, 0));
    }

    @Override
    public LatLng getFusedPosition() {
        double east = stateVector.get(0, 0);
        double north = stateVector.get(1, 0);

        // Convert ENU back to latitude/longitude
        return CoordinateConverter.enu2Geodetic(
                east, north, 0, // Assume altitude=0 for the return value
                referencePosition[0], referencePosition[1], referencePosition[2]
        );
    }

    @Override
    public void reset() {
        // Reset state vector
        stateVector = new SimpleMatrix(4, 1);

        // Reset covariance matrix with high uncertainty
        covarianceMatrix = SimpleMatrix.identity(4);
        covarianceMatrix = covarianceMatrix.scale(100);

        // Reset timestamp
        lastUpdateTime = System.currentTimeMillis();

        Log.d(TAG, "Kalman filter reset");
    }

    /**
     * Updates the process matrix F based on the time delta.
     * The process model is a constant velocity model.
     *
     * @param deltaTime The time delta in seconds
     */
    private void updateProcessMatrix(double deltaTime) {
        processMatrix = SimpleMatrix.identity(4);

        // Update the process matrix for constant velocity model
        processMatrix.set(0, 2, deltaTime); // x += vx * dt
        processMatrix.set(1, 3, deltaTime); // y += vy * dt

        // Update process noise matrix Q based on delta time
        double dt2 = deltaTime * deltaTime;
        double dt3 = dt2 * deltaTime;
        double dt4 = dt3 * deltaTime;

        processNoiseMatrix = new SimpleMatrix(4, 4);

        // Process noise for position
        processNoiseMatrix.set(0, 0, dt4 / 4);
        processNoiseMatrix.set(0, 2, dt3 / 2);
        processNoiseMatrix.set(1, 1, dt4 / 4);
        processNoiseMatrix.set(1, 3, dt3 / 2);

        // Process noise for velocity
        processNoiseMatrix.set(2, 0, dt3 / 2);
        processNoiseMatrix.set(2, 2, dt2);
        processNoiseMatrix.set(3, 1, dt3 / 2);
        processNoiseMatrix.set(3, 3, dt2);
    }

    // WiFi fusion will be added in future updates
    /*
    public void processWifiUpdate(LatLng position) {
        // To be implemented in the future
    }
    */
}