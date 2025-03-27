package com.openpositioning.PositionMe.fusion;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.CoordinateConverter;
import com.openpositioning.PositionMe.utils.MovementModel;
import com.openpositioning.PositionMe.utils.MeasurementModel;
import com.openpositioning.PositionMe.utils.ExponentialSmoothingFilter;

import org.ejml.simple.SimpleMatrix;

/**
 * An enhanced Kalman Filter implementation for fusing position data from PDR and GNSS.
 * This implementation provides more accurate position estimates by incorporating
 * sophisticated movement models, outlier detection, and adaptive measurement handling.
 */
public class KalmanFilterFusion implements IPositionFusionAlgorithm {
    private static final String TAG = "KalmanFilterFusion";

    // State vector components indices
    private static final int STATE_IDX_BEARING = 0;  // Bearing/heading angle (radians)
    private static final int STATE_IDX_EAST = 1;     // East position (meters)
    private static final int STATE_IDX_NORTH = 2;    // North position (meters)
    private static final int STATE_IDX_VE = 3;       // East velocity (meters/second)
    private static final int STATE_IDX_VN = 4;       // North velocity (meters/second)
    private static final int STATE_SIZE = 5;         // Total state vector size

    // Measurement components indices
    private static final int MEAS_IDX_EAST = 0;      // East position measurement
    private static final int MEAS_IDX_NORTH = 1;     // North position measurement
    private static final int MEAS_SIZE = 2;          // Total measurement vector size

    // Filter parameters
    private static final double SMOOTHING_FACTOR = 0.3;  // Smoothing factor for output (0.0-1.0)
    private static final int MAX_STEP_COUNT = 100;       // Maximum steps before forcing an update
    private static final long MAX_TIME_WITHOUT_UPDATE = 10000; // Max time (ms) without updates

    // State variables
    private SimpleMatrix stateVector;          // [heading, x, y, vx, vy]ᵀ
    private SimpleMatrix covarianceMatrix;     // State covariance matrix P
    private SimpleMatrix processMatrix;        // State transition matrix F
    private SimpleMatrix processNoiseMatrix;   // Process noise matrix Q
    private SimpleMatrix measurementMatrix;    // Measurement matrix H
    private SimpleMatrix measurementNoiseMatrix; // Measurement noise matrix R
    private SimpleMatrix identityMatrix;       // Identity matrix for calculations

    // The reference point for ENU coordinates
    private double[] referencePosition;        // [lat, lng, alt]

    // Helper models
    private MovementModel movementModel;
    private MeasurementModel measurementModel;
    private ExponentialSmoothingFilter smoothingFilter;

    // Timing and step variables
    private long lastUpdateTime;               // Time of last measurement update (ms)
    private long lastPredictTime;              // Time of last prediction (ms)
    private int stepsSinceLastUpdate;          // Steps since last measurement update

    // Pending measurement variables
    private double[] pendingMeasurement;       // Pending opportunistic measurement [east, north]
    private long pendingMeasurementTime;       // Time of pending measurement (ms)
    private boolean hasPendingMeasurement;     // Flag for pending measurement
    private boolean usePendingMeasurement;     // Flag to use pending measurement

    /**
     * Creates a new Kalman filter for position fusion.
     *
     * @param referencePosition The reference position [lat, lng, alt] for ENU coordinates
     */
    public KalmanFilterFusion(double[] referencePosition) {
        this.referencePosition = referencePosition;

        // Initialize models
        this.movementModel = new MovementModel();
        this.measurementModel = new MeasurementModel();
        this.smoothingFilter = new ExponentialSmoothingFilter(SMOOTHING_FACTOR, 2);

        // Initialize state vector [heading, x, y, vx, vy]ᵀ
        stateVector = new SimpleMatrix(STATE_SIZE, 1);

        // Initialize covariance matrix with high uncertainty
        covarianceMatrix = SimpleMatrix.identity(STATE_SIZE);
        covarianceMatrix = covarianceMatrix.scale(100); // High initial uncertainty

        // Identity matrix for calculations
        identityMatrix = SimpleMatrix.identity(STATE_SIZE);

        // Initialize measurement matrix - we measure position (x, y)
        measurementMatrix = new SimpleMatrix(MEAS_SIZE, STATE_SIZE);
        measurementMatrix.set(MEAS_IDX_EAST, STATE_IDX_EAST, 1.0);   // Measure east position
        measurementMatrix.set(MEAS_IDX_NORTH, STATE_IDX_NORTH, 1.0); // Measure north position

        // Initialize measurement noise matrix
        measurementNoiseMatrix = SimpleMatrix.identity(MEAS_SIZE);
        measurementNoiseMatrix = measurementNoiseMatrix.scale(25); // Default 5m std

        // Initialize process matrix (will be updated with each time step)
        processMatrix = SimpleMatrix.identity(STATE_SIZE);

        // Initialize process noise matrix
        processNoiseMatrix = SimpleMatrix.identity(STATE_SIZE);

        // Initialize timing variables
        lastUpdateTime = System.currentTimeMillis();
        lastPredictTime = lastUpdateTime;
        stepsSinceLastUpdate = 0;

        // Initialize pending measurement variables
        pendingMeasurement = null;
        pendingMeasurementTime = 0;
        hasPendingMeasurement = false;
        usePendingMeasurement = false;

        Log.d(TAG, "Kalman filter initialized with reference position: " +
                referencePosition[0] + ", " + referencePosition[1] + ", " + referencePosition[2]);
    }

    @Override
    public void processPdrUpdate(float eastMeters, float northMeters, float altitude) {
        long currentTime = System.currentTimeMillis();

        // If this is the first PDR update, initialize the state
        if (stateVector.get(STATE_IDX_EAST, 0) == 0 && stateVector.get(STATE_IDX_NORTH, 0) == 0) {
            stateVector.set(STATE_IDX_EAST, 0, eastMeters);
            stateVector.set(STATE_IDX_NORTH, 0, northMeters);
            lastPredictTime = currentTime;
            lastUpdateTime = currentTime;

            // Update measurement model
            measurementModel.updatePdrUncertainty(stepsSinceLastUpdate, currentTime);

            Log.d(TAG, "First PDR update: initializing state with E=" + eastMeters + ", N=" + northMeters);
            return;
        }

        // Update step count
        stepsSinceLastUpdate++;

        // Calculate time difference since last prediction
        double deltaTime = (currentTime - lastPredictTime) / 1000.0; // Convert to seconds
        if (deltaTime <= 0) {
            deltaTime = 0.1; // Minimum delta time to avoid division by zero
        }

        // Get current values
        double prevEast = stateVector.get(STATE_IDX_EAST, 0);
        double prevNorth = stateVector.get(STATE_IDX_NORTH, 0);

        // Calculate PDR movement and speed
        double dx = eastMeters - prevEast;
        double dy = northMeters - prevNorth;
        double stepLength = Math.sqrt(dx * dx + dy * dy);

        // Calculate bearing from movement direction
        double bearing = Math.atan2(dx, dy); // 0 = North, positive clockwise

        // Update movement model with step information
        movementModel.setStepLength(stepLength);
        MovementModel.MovementType movementType = detectMovementType(bearing,
                stateVector.get(STATE_IDX_BEARING, 0));

        // Update measurement model
        measurementModel.updatePdrUncertainty(stepsSinceLastUpdate, currentTime);

        // Calculate velocity
        double vx = dx / deltaTime;
        double vy = dy / deltaTime;

        // Get noise parameters
        double movementStdDev = movementModel.getOrientationStdDev(movementType);
        double timePenalty = movementModel.calculateTimePenalty(currentTime);

        // Predict state using model
        predictState(bearing, stepLength, movementStdDev, timePenalty, currentTime, movementType);

        // Check if we should process a pending measurement
        processOpportunisticUpdateIfAvailable(currentTime, eastMeters, northMeters);

        // Save last prediction time
        lastPredictTime = currentTime;

        Log.d(TAG, "PDR update: E=" + eastMeters + ", N=" + northMeters +
                " -> State=" + stateVector.get(STATE_IDX_EAST, 0) + ", " +
                stateVector.get(STATE_IDX_NORTH, 0) + ", bearing=" +
                Math.toDegrees(stateVector.get(STATE_IDX_BEARING, 0)) + "°");
    }

    @Override
    public void processGnssUpdate(LatLng position, double altitude) {
        long currentTime = System.currentTimeMillis();

        // Convert GNSS position to ENU
        double[] enu = CoordinateConverter.geodetic2Enu(
                position.latitude, position.longitude, altitude,
                referencePosition[0], referencePosition[1], referencePosition[2]
        );

        // If this is the first position, initialize the state
        if (stateVector.get(STATE_IDX_EAST, 0) == 0 && stateVector.get(STATE_IDX_NORTH, 0) == 0) {
            stateVector.set(STATE_IDX_EAST, 0, enu[0]);
            stateVector.set(STATE_IDX_NORTH, 0, enu[1]);
            lastPredictTime = currentTime;
            lastUpdateTime = currentTime;

            Log.d(TAG, "First GNSS update: initializing state with E=" + enu[0] + ", N=" + enu[1]);
            return;
        }

        // Update measurement model
        measurementModel.updateGnssUncertainty(null, currentTime);

        // Apply Kalman update with GNSS measurement
        // (Create measurement vector and noise matrix)
        SimpleMatrix measurementVector = new SimpleMatrix(MEAS_SIZE, 1);
        measurementVector.set(MEAS_IDX_EAST, 0, enu[0]);
        measurementVector.set(MEAS_IDX_NORTH, 0, enu[1]);

        // Prepare measurement noise matrix
        double gnssVar = measurementModel.getGnssStd() * measurementModel.getGnssStd();
        measurementNoiseMatrix.set(MEAS_IDX_EAST, MEAS_IDX_EAST, gnssVar);
        measurementNoiseMatrix.set(MEAS_IDX_NORTH, MEAS_IDX_NORTH, gnssVar);

        // Perform Kalman update
        performUpdate(measurementVector, currentTime);

        // Reset step counter since we've had a GNSS update
        stepsSinceLastUpdate = 0;

        Log.d(TAG, "GNSS update: E=" + enu[0] + ", N=" + enu[1] +
                " -> State=" + stateVector.get(STATE_IDX_EAST, 0) + ", " +
                stateVector.get(STATE_IDX_NORTH, 0));
    }

    @Override
    public LatLng getFusedPosition() {
        double east = stateVector.get(STATE_IDX_EAST, 0);
        double north = stateVector.get(STATE_IDX_NORTH, 0);

        // Apply smoothing if needed
        double[] smoothed = smoothingFilter.applySmoothing(new double[]{east, north});

        // Convert ENU back to latitude/longitude
        return CoordinateConverter.enu2Geodetic(
                smoothed[0], smoothed[1], 0, // Assume altitude=0 for the return value
                referencePosition[0], referencePosition[1], referencePosition[2]
        );
    }

    @Override
    public void reset() {
        // Reset state vector
        stateVector = new SimpleMatrix(STATE_SIZE, 1);

        // Reset covariance matrix with high uncertainty
        covarianceMatrix = SimpleMatrix.identity(STATE_SIZE);
        covarianceMatrix = covarianceMatrix.scale(100);

        // Reset timing variables
        lastUpdateTime = System.currentTimeMillis();
        lastPredictTime = lastUpdateTime;
        stepsSinceLastUpdate = 0;

        // Reset pending measurement variables
        pendingMeasurement = null;
        pendingMeasurementTime = 0;
        hasPendingMeasurement = false;
        usePendingMeasurement = false;

        // Reset helper models
        movementModel.reset();
        measurementModel.reset();
        smoothingFilter.reset();

        Log.d(TAG, "Kalman filter reset");
    }

    /**
     * Handles opportunistic updates (like WiFi) to the state estimation process.
     * This allows the filter to utilize additional data points when available.
     *
     * @param position An array containing the East and North positions in meters
     * @param timeMillis The timestamp of the measurement in milliseconds
     */
    public void onOpportunisticUpdate(double[] position, long timeMillis) {
        // Store this measurement for later use
        pendingMeasurement = position;
        pendingMeasurementTime = timeMillis;
        hasPendingMeasurement = true;

        // Check if the measurement is significantly different from the last one
        usePendingMeasurement = true;

        Log.d(TAG, "Received opportunistic update: E=" + position[0] + ", N=" + position[1] +
                " at time " + timeMillis);
    }

    /**
     * Process a pending opportunistic update if available and valid.
     *
     * @param currentTime Current system time in milliseconds
     * @param pdrEast Current PDR east position for comparison
     * @param pdrNorth Current PDR north position for comparison
     */
    private void processOpportunisticUpdateIfAvailable(long currentTime, double pdrEast, double pdrNorth) {
        // Check if we have a pending measurement and should use it
        if (!hasPendingMeasurement || !usePendingMeasurement) {
            return;
        }

        // Check if the measurement is too old (more than 10 seconds)
        if (currentTime - pendingMeasurementTime > MAX_TIME_WITHOUT_UPDATE) {
            Log.d(TAG, "Skipping opportunistic update: too old");
            hasPendingMeasurement = false;
            return;
        }

        // Calculate distance between PDR and opportunistic measurement
        double distance = CoordinateConverter.enuDistance(
                pdrEast, pdrNorth,
                pendingMeasurement[0], pendingMeasurement[1]
        );

        // Check if this is an outlier
        if (movementModel.isOutlier(distance)) {
            Log.d(TAG, "Skipping opportunistic update: detected as outlier (distance=" + distance + "m)");
            hasPendingMeasurement = false;
            return;
        }

        // Create measurement vector
        SimpleMatrix measurementVector = new SimpleMatrix(MEAS_SIZE, 1);
        measurementVector.set(MEAS_IDX_EAST, 0, pendingMeasurement[0]);
        measurementVector.set(MEAS_IDX_NORTH, 0, pendingMeasurement[1]);

        // Create measurement noise matrix (higher uncertainty for opportunistic updates)
        double uncertaintyFactor = 2.0; // Increase uncertainty for opportunistic updates
        double variance = Math.pow(measurementModel.getGnssStd() * uncertaintyFactor, 2);
        measurementNoiseMatrix.set(MEAS_IDX_EAST, MEAS_IDX_EAST, variance);
        measurementNoiseMatrix.set(MEAS_IDX_NORTH, MEAS_IDX_NORTH, variance);

        // Perform Kalman update
        performUpdate(measurementVector, currentTime);

        // Reset state
        hasPendingMeasurement = false;
        stepsSinceLastUpdate = 0;

        Log.d(TAG, "Processed opportunistic update: E=" + pendingMeasurement[0] +
                ", N=" + pendingMeasurement[1]);
    }

    /**
     * Predicts the next state based on the movement model.
     *
     * @param newBearing New bearing (orientation) in radians
     * @param stepLength Length of the current step in meters
     * @param orientationStdDev Standard deviation of orientation in radians
     * @param timePenalty Time-based penalty factor
     * @param currentTime Current system time in milliseconds
     * @param movementType Type of movement detected
     */
    private void predictState(double newBearing, double stepLength, double orientationStdDev,
                              double timePenalty, long currentTime, MovementModel.MovementType movementType) {
        // Calculate time delta
        double deltaTime = (currentTime - lastPredictTime) / 1000.0; // Convert to seconds
        if (deltaTime <= 0) {
            deltaTime = 0.1; // Minimum delta time
        }

        // Update process matrix for constant velocity model with bearing changes
        updateProcessMatrix(deltaTime, newBearing);

        // Update process noise matrix
        updateProcessNoiseMatrix(deltaTime, stepLength, orientationStdDev, timePenalty, movementType);

        // Predict state: x_k|k-1 = F_k * x_k-1|k-1
        SimpleMatrix predictedState = processMatrix.mult(stateVector);

        // Predict covariance: P_k|k-1 = F_k * P_k-1|k-1 * F_k^T + Q_k
        SimpleMatrix predictedCovariance = processMatrix.mult(covarianceMatrix)
                .mult(processMatrix.transpose())
                .plus(processNoiseMatrix);

        // Update state and covariance
        stateVector = predictedState;
        covarianceMatrix = predictedCovariance;

        // Normalize bearing to [-π, π]
        double normalizedBearing = CoordinateConverter.wrapToPi(stateVector.get(STATE_IDX_BEARING, 0));
        stateVector.set(STATE_IDX_BEARING, 0, normalizedBearing);
    }

    /**
     * Updates the process matrix based on the time delta.
     *
     * @param deltaTime Time delta in seconds
     * @param bearing Current bearing in radians
     */
    private void updateProcessMatrix(double deltaTime, double bearing) {
        // Start with identity matrix
        processMatrix = SimpleMatrix.identity(STATE_SIZE);

        // Add time evolution: position += velocity * dt
        processMatrix.set(STATE_IDX_EAST, STATE_IDX_VE, deltaTime);   // x += vx * dt
        processMatrix.set(STATE_IDX_NORTH, STATE_IDX_VN, deltaTime);  // y += vy * dt

        // Update bearing directly (no integration)
        processMatrix.set(STATE_IDX_BEARING, STATE_IDX_BEARING, 0);  // Zero out existing bearing
        stateVector.set(STATE_IDX_BEARING, 0, bearing);              // Set new bearing directly
    }

    /**
     * Updates the process noise covariance matrix based on movement parameters.
     *
     * @param deltaTime Time delta in seconds
     * @param stepLength Length of the current step in meters
     * @param orientationStdDev Standard deviation of orientation in radians
     * @param timePenalty Time-based penalty factor
     * @param movementType Type of movement detected
     */
    private void updateProcessNoiseMatrix(double deltaTime, double stepLength, double orientationStdDev,
                                          double timePenalty, MovementModel.MovementType movementType) {
        // Calculate step error based on movement model
        double stepError = movementModel.calculateStepError(stepLength, timePenalty);

        // Calculate position process noise (from velocity uncertainty)
        double dt2 = deltaTime * deltaTime;
        double posNoise = stepError * stepError * dt2;

        // Calculate velocity process noise
        double velNoise = (stepError / deltaTime) * (stepError / deltaTime);

        // Calculate bearing process noise
        double bearingNoise = orientationStdDev * orientationStdDev;
        if (movementType == MovementModel.MovementType.TURN) {
            bearingNoise *= 2.0; // Increase noise for turns
        }

        // Apply time penalty to all noise values
        posNoise *= timePenalty;
        velNoise *= timePenalty;
        bearingNoise *= timePenalty;

        // Build the process noise matrix
        processNoiseMatrix = SimpleMatrix.identity(STATE_SIZE);
        processNoiseMatrix.set(STATE_IDX_BEARING, STATE_IDX_BEARING, bearingNoise);
        processNoiseMatrix.set(STATE_IDX_EAST, STATE_IDX_EAST, posNoise);
        processNoiseMatrix.set(STATE_IDX_NORTH, STATE_IDX_NORTH, posNoise);
        processNoiseMatrix.set(STATE_IDX_VE, STATE_IDX_VE, velNoise);
        processNoiseMatrix.set(STATE_IDX_VN, STATE_IDX_VN, velNoise);
    }

    /**
     * Performs the Kalman filter update step with a measurement.
     *
     * @param measurementVector The measurement vector (East, North)
     * @param currentTime Current system time in milliseconds
     */
    private void performUpdate(SimpleMatrix measurementVector, long currentTime) {
        // Calculate innovation: y_k = z_k - H_k * x_k|k-1
        SimpleMatrix innovation = measurementVector.minus(measurementMatrix.mult(stateVector));

        // Calculate innovation covariance: S_k = H_k * P_k|k-1 * H_k^T + R_k
        SimpleMatrix innovationCovariance = measurementMatrix.mult(covarianceMatrix)
                .mult(measurementMatrix.transpose())
                .plus(measurementNoiseMatrix);

        // Calculate Kalman gain: K_k = P_k|k-1 * H_k^T * S_k^-1
        SimpleMatrix kalmanGain = covarianceMatrix.mult(measurementMatrix.transpose())
                .mult(innovationCovariance.invert());

        // Update state: x_k|k = x_k|k-1 + K_k * y_k
        stateVector = stateVector.plus(kalmanGain.mult(innovation));

        // Update covariance: P_k|k = (I - K_k * H_k) * P_k|k-1
        covarianceMatrix = identityMatrix.minus(kalmanGain.mult(measurementMatrix))
                .mult(covarianceMatrix);

        // Normalize bearing to [-π, π]
        double normalizedBearing = CoordinateConverter.wrapToPi(stateVector.get(STATE_IDX_BEARING, 0));
        stateVector.set(STATE_IDX_BEARING, 0, normalizedBearing);

        // Update timing
        lastUpdateTime = currentTime;
    }

    /**
     * Detects the type of movement based on bearing change.
     *
     * @param newBearing New bearing in radians
     * @param oldBearing Old bearing in radians
     * @return The type of movement detected
     */
    private MovementModel.MovementType detectMovementType(double newBearing, double oldBearing) {
        // Calculate absolute bearing change
        double bearingChange = Math.abs(CoordinateConverter.wrapToPi(newBearing - oldBearing));

        // Classify movement based on bearing change thresholds
        if (bearingChange > Math.toRadians(30)) {
            return MovementModel.MovementType.TURN;
        } else if (bearingChange > Math.toRadians(10)) {
            return MovementModel.MovementType.PSEUDO_TURN;
        } else {
            return MovementModel.MovementType.STRAIGHT;
        }
    }
}