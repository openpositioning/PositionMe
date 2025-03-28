package com.openpositioning.PositionMe.fusion;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.CoordinateConverter;
import com.openpositioning.PositionMe.utils.MeasurementModel;

import org.ejml.simple.SimpleMatrix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A Kalman Filter implementation for fusing position data from PDR and GNSS.
 * This implementation provides accurate position estimates by incorporating
 * movement models, outlier detection, and adaptive measurement handling.
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
    private static final double SMOOTHING_FACTOR = 0.3;  // Smoothing factor for output
    private static final int MAX_STEP_COUNT = 100;       // Maximum steps before forcing update
    private static final long MAX_TIME_WITHOUT_UPDATE = 10000; // Max time (ms) without updates

    // State variables
    private SimpleMatrix stateVector;          // [heading, x, y, vx, vy]ᵀ
    private SimpleMatrix covarianceMatrix;     // State covariance matrix P
    private SimpleMatrix processMatrix;        // State transition matrix F
    private SimpleMatrix processNoiseMatrix;   // Process noise matrix Q
    private SimpleMatrix measurementMatrix;    // Measurement matrix H
    private SimpleMatrix measurementNoiseMatrixGnss; // Measurement noise matrix R_GNSS
    private SimpleMatrix measurementNoiseMatrixWifi; // Measurement noise matrix R_WIFI

    private SimpleMatrix identityMatrix;       // Identity matrix for calculations

    // The reference point for ENU coordinates
    private double[] referencePosition;        // [lat, lng, alt]
    private boolean referenceInitialized;      // Flag to track if reference is properly set

    // Helper models
    private MovementModel movementModel;
    private MeasurementModel measurementModel;
    private FusionFilter smoothingFilter;
    private FusionOutlierDetector fusionOutlierDetector;

    // Timing and step variables
    private long lastUpdateTime;               // Time of last measurement update (ms)
    private long lastPredictTime;              // Time of last prediction (ms)
    private int stepsSinceLastUpdate;          // Steps since last measurement update

    // First position values to handle initialization correctly
    private LatLng firstGnssPosition;
    private boolean hasInitialGnssPosition;

    // Pending measurement variables
    private double[] pendingMeasurement;       // Pending measurement [east, north]
    private long pendingMeasurementTime;       // Time of pending measurement (ms)
    private boolean hasPendingMeasurement;     // Flag for pending measurement
    private boolean usePendingMeasurement;     // Flag to use pending measurement

    /**
     * Creates a new Kalman filter for position fusion.
     *
     * @param referencePosition The reference position [lat, lng, alt] for ENU coordinates
     */
    public KalmanFilterFusion(double[] referencePosition) {
        this.referencePosition = referencePosition.clone(); // Clone to prevent modification
        this.referenceInitialized = (referencePosition[0] != 0 || referencePosition[1] != 0);

        if (!referenceInitialized) {
            Log.w(TAG, "Reference position not initialized properly: " +
                    "lat=" + referencePosition[0] + ", lng=" + referencePosition[1] +
                    ", alt=" + referencePosition[2]);
        } else {
            Log.d(TAG, "Reference position initialized: " +
                    "lat=" + referencePosition[0] + ", lng=" + referencePosition[1] +
                    ", alt=" + referencePosition[2]);
        }

        // Initialize models
        this.movementModel = new MovementModel();
        this.measurementModel = new MeasurementModel();
        this.smoothingFilter = new FusionFilter(SMOOTHING_FACTOR, 2);
        this.fusionOutlierDetector = new FusionOutlierDetector();

        // Initialize state vector [heading, x, y, vx, vy]ᵀ
        stateVector = new SimpleMatrix(STATE_SIZE, 1);

        // Initialize covariance matrix with high uncertainty
        covarianceMatrix = SimpleMatrix.identity(STATE_SIZE);
        covarianceMatrix = covarianceMatrix.scale(100); // High initial uncertainty

        // Identity matrix for calculations
        identityMatrix = SimpleMatrix.identity(STATE_SIZE);

        // Initialize measurement matrix  - we measure position (x, y)
        measurementMatrix = new SimpleMatrix(MEAS_SIZE, STATE_SIZE);
        measurementMatrix.set(MEAS_IDX_EAST, STATE_IDX_EAST, 1.0);   // Measure east position
        measurementMatrix.set(MEAS_IDX_NORTH, STATE_IDX_NORTH, 1.0); // Measure north position

        // Initialize measurement noise matrices for GNSS and WIFI
        measurementNoiseMatrixGnss = SimpleMatrix.identity(MEAS_SIZE);
        measurementNoiseMatrixGnss = measurementNoiseMatrixGnss.scale(25); // Default 5m std

        measurementNoiseMatrixWifi = SimpleMatrix.identity(MEAS_SIZE);
        measurementNoiseMatrixWifi = measurementNoiseMatrixWifi.scale(16); // Default 4m std

        // Initialize process matrix (will be updated with each time step)
        processMatrix = SimpleMatrix.identity(STATE_SIZE);

        // Initialize process noise matrix
        processNoiseMatrix = SimpleMatrix.identity(STATE_SIZE);

        // Initialize timing variables
        lastUpdateTime = System.currentTimeMillis();
        lastPredictTime = lastUpdateTime;
        stepsSinceLastUpdate = 0;

        // Initialize first position tracking
        hasInitialGnssPosition = false;

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

        // Handle reference position if not initialized
        if (!referenceInitialized && hasInitialGnssPosition) {
            initializeReferencePosition();
        }

        // If reference still not initialized, we can't process
        if (!referenceInitialized) {
            Log.w(TAG, "Skipping PDR update: reference position not initialized");
            return;
        }

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

        // Calculate bearing from movement direction (compensating for ENU frame)
        double bearing = Math.atan2(dx, dy); // 0 = North, positive clockwise

        // Update movement model with step information
        movementModel.updateLastStepLength(stepLength);
        MovementModel.MovementType movementType = detectMovementType(bearing,
                stateVector.get(STATE_IDX_BEARING, 0));

        // Update measurement model
        measurementModel.updatePdrUncertainty(stepsSinceLastUpdate, currentTime);

        // Calculate velocity
        double vx = dx / deltaTime;
        double vy = dy / deltaTime;

        // Get noise parameters
        double movementStdDev = movementModel.getBearingUncertainty(movementType);
        double timePenalty = movementModel.computeTemporalPenaltyFactor(currentTime);

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

        // Store first GNSS position for reference initialization if needed
        if (!hasInitialGnssPosition) {
            firstGnssPosition = position;
            hasInitialGnssPosition = true;

            // Try to initialize reference position if needed
            if (!referenceInitialized) {
                initializeReferencePosition();
            }
        }

        // If reference still not initialized, we can't process
        if (!referenceInitialized) {
            Log.w(TAG, "Skipping GNSS update: reference position not initialized");
            return;
        }

        // Convert GNSS position to ENU (using the reference position)
        double[] enu = CoordinateConverter.convertGeodeticToEnu(
                position.latitude, position.longitude, altitude,
                referencePosition[0], referencePosition[1], referencePosition[2]
        );

        // Debug the conversion
        Log.d(TAG, "GNSS geodetic->ENU: " +
                position.latitude + "," + position.longitude + " -> " +
                "E=" + enu[0] + ", N=" + enu[1]);

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
        measurementNoiseMatrixGnss.set(MEAS_IDX_EAST, MEAS_IDX_EAST, gnssVar);
        measurementNoiseMatrixGnss.set(MEAS_IDX_NORTH, MEAS_IDX_NORTH, gnssVar);

        // Check for outliers by comparing GNSS with current state
        double currentEast = stateVector.get(STATE_IDX_EAST, 0);
        double currentNorth = stateVector.get(STATE_IDX_NORTH, 0);
        double distance = Math.sqrt(Math.pow(enu[0] - currentEast, 2) +
                Math.pow(enu[1] - currentNorth, 2));

        if (fusionOutlierDetector.evaluateDistance(distance)) { // NEW
            Log.w(TAG, "Skipping GNSS update: detected as outlier (distance=" + distance + "m)");
            return;
        }

        // Perform Kalman update
        performUpdate(measurementVector, measurementNoiseMatrixGnss, currentTime);

        // Reset step counter since we've had a GNSS update
        stepsSinceLastUpdate = 0;

        Log.d(TAG, "GNSS update: E=" + enu[0] + ", N=" + enu[1] +
                " -> State=" + stateVector.get(STATE_IDX_EAST, 0) + ", " +
                stateVector.get(STATE_IDX_NORTH, 0));
    }

    @Override
    public void processWifiUpdate(LatLng position, int floor) {
        long currentTime = System.currentTimeMillis();

        // If reference still not initialized, we can't process
        if (!referenceInitialized) {
            Log.w(TAG, "Skipping WiFi update: reference position not initialized");
            return;
        }

        // Convert WiFi position to ENU (using the reference position)
        // TODO: Fix altitude estimate for wifi
        double[] enu = CoordinateConverter.convertGeodeticToEnu(
                position.latitude, position.longitude, referencePosition[2],
                referencePosition[0], referencePosition[1], referencePosition[2]
        );

        // Debug the conversion
        Log.d(TAG, "WiFi geodetic->ENU: " +
                position.latitude + "," + position.longitude + " -> " +
                "E=" + enu[0] + ", N=" + enu[1]);

        // If this is the first position, initialize the state
        if (stateVector.get(STATE_IDX_EAST, 0) == 0 && stateVector.get(STATE_IDX_NORTH, 0) == 0) {
            stateVector.set(STATE_IDX_EAST, 0, enu[0]);
            stateVector.set(STATE_IDX_NORTH, 0, enu[1]);
            lastPredictTime = currentTime;
            lastUpdateTime = currentTime;

            Log.d(TAG, "First WiFi update: initializing state with E=" + enu[0] + ", N=" + enu[1]);
            return;
        }

        // Update measurement model
        measurementModel.updateWifiUncertainty(null, currentTime);

        // Apply Kalman update with WiFi measurement
        // (Create measurement vector and noise matrix)
        SimpleMatrix measurementVector = new SimpleMatrix(MEAS_SIZE, 1);
        measurementVector.set(MEAS_IDX_EAST, 0, enu[0]);
        measurementVector.set(MEAS_IDX_NORTH, 0, enu[1]);

        // Prepare measurement noise matrix
        double gnssVar = measurementModel.getWifiStd() * measurementModel.getWifiStd();
        measurementNoiseMatrixWifi.set(MEAS_IDX_EAST, MEAS_IDX_EAST, gnssVar);
        measurementNoiseMatrixWifi.set(MEAS_IDX_NORTH, MEAS_IDX_NORTH, gnssVar);

        // Perform Kalman update
        performUpdate(measurementVector, measurementNoiseMatrixWifi, currentTime);

        // Reset step counter since we've had a WiFi update
        stepsSinceLastUpdate = 0;

        Log.d(TAG, "WiFi update: E=" + enu[0] + ", N=" + enu[1] +
                " -> State=" + stateVector.get(STATE_IDX_EAST, 0) + ", " +
                stateVector.get(STATE_IDX_NORTH, 0));

    }

    @Override
    public LatLng getFusedPosition() {
        if (!referenceInitialized) {
            if (hasInitialGnssPosition) {
                Log.w(TAG, "Using initial GNSS position as fusion result (reference not initialized)");
                return firstGnssPosition;
            } else {
                Log.e(TAG, "Cannot get fused position: no reference position and no GNSS position");
                return null;
            }
        }

        // If we haven't received any updates yet, return the reference position
        if (stateVector.get(STATE_IDX_EAST, 0) == 0 && stateVector.get(STATE_IDX_NORTH, 0) == 0) {
            Log.d(TAG, "Returning reference position as fusion result (no updates yet)");
            return new LatLng(referencePosition[0], referencePosition[1]);
        }

        double east = stateVector.get(STATE_IDX_EAST, 0);
        double north = stateVector.get(STATE_IDX_NORTH, 0);

        // Apply smoothing if needed
        double[] smoothed = smoothingFilter.update(new double[]{east, north});

        // Debug the state before conversion
        Log.d(TAG, "Fused position (before conversion): E=" + smoothed[0] + ", N=" + smoothed[1]);

        // Convert ENU back to latitude/longitude
        LatLng result = CoordinateConverter.convertEnuToGeodetic(
                smoothed[0], smoothed[1], 0, // Assume altitude=0 for the return value
                referencePosition[0], referencePosition[1], referencePosition[2]
        );

        Log.d(TAG, "Fused position (after conversion): " +
                result.latitude + "," + result.longitude);

        return result;
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

        // Keep reference position but reset first GNSS position
        hasInitialGnssPosition = false;

        // Reset pending measurement variables
        pendingMeasurement = null;
        pendingMeasurementTime = 0;
        hasPendingMeasurement = false;
        usePendingMeasurement = false;

        // Reset helper models
        movementModel.resetState();
        measurementModel.reset();
        smoothingFilter.reset();
        fusionOutlierDetector.clearHistory();

        Log.d(TAG, "Kalman filter reset");
    }

    /**
     * Handles opportunistic updates to the state estimation process.
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
        double distance = CoordinateConverter.calculateEnuDistance(
                pdrEast, pdrNorth,
                pendingMeasurement[0], pendingMeasurement[1]
        );

        // Check if this is an outlier
        if (fusionOutlierDetector.evaluateDistance(distance)) { // NEW
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
        measurementNoiseMatrixGnss.set(MEAS_IDX_EAST, MEAS_IDX_EAST, variance);
        measurementNoiseMatrixGnss.set(MEAS_IDX_NORTH, MEAS_IDX_NORTH, variance);

        // Perform Kalman update
        performUpdate(measurementVector, measurementNoiseMatrixGnss, currentTime);

        // Reset state
        hasPendingMeasurement = false;
        stepsSinceLastUpdate = 0;

        Log.d(TAG, "Processed opportunistic update: E=" + pendingMeasurement[0] +
                ", N=" + pendingMeasurement[1]);
    }

    /**
     * Initializes the reference position if it wasn't provided or was zeros.
     * Uses the first GNSS position as the reference.
     */
    private void initializeReferencePosition() {
        if (referenceInitialized || !hasInitialGnssPosition) {
            return;
        }

        // Use the first GNSS position as reference
        referencePosition[0] = firstGnssPosition.latitude;
        referencePosition[1] = firstGnssPosition.longitude;
        referencePosition[2] = 0; // Assume zero altitude if not provided

        referenceInitialized = true;

        Log.d(TAG, "Reference position initialized from GNSS: " +
                "lat=" + referencePosition[0] + ", lng=" + referencePosition[1] +
                ", alt=" + referencePosition[2]);
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
        double normalizedBearing = CoordinateConverter.normalizeAngleToPi(stateVector.get(STATE_IDX_BEARING, 0));
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
        double stepError = movementModel.estimateStepError(stepLength, timePenalty);

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
    private void performUpdate(SimpleMatrix measurementVector, SimpleMatrix measurementNoiseMatrix, long currentTime) {
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
        double normalizedBearing = CoordinateConverter.normalizeAngleToPi(stateVector.get(STATE_IDX_BEARING, 0));
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
        double bearingChange = Math.abs(CoordinateConverter.normalizeAngleToPi(newBearing - oldBearing));

        // Classify movement based on bearing change thresholds
        if (bearingChange > Math.toRadians(30)) {
            return MovementModel.MovementType.TURN;
        } else if (bearingChange > Math.toRadians(10)) {
            return MovementModel.MovementType.PSEUDO_TURN;
        } else {
            return MovementModel.MovementType.STRAIGHT;
        }
    }

    /**
     * Implements an exponential smoothing filter designed to mitigate noise
     * in sequential data streams, such as position estimates. By averaging current
     * measurements with previous filtered values, it produces smoother trajectories,
     * reducing the visual jitter often present in raw sensor outputs.
     */
    public static class FusionFilter {
        private static final String TAG = FusionFilter.class.getSimpleName(); // Use class name for TAG

        /**
         * The smoothing factor (often denoted as 'alpha') controls the balance
         * between responsiveness to new data and the degree of smoothing.
         * A value of 1.0 means only the current input is used (no smoothing).
         * A value of 0.0 means the output never changes (infinite smoothing).
         * Values typically range from 0.1 (heavy smoothing) to 0.6 (moderate smoothing).
         */
        private final double smoothingFactor;

        /**
         * The number of independent dimensions in the data being filtered
         * (e.g., 2 for 2D coordinates, 3 for 3D coordinates).
         */
        private final int numDimensions;

        /**
         * Stores the most recently calculated smoothed values for each dimension.
         * This state is used in the subsequent filtering step.
         */
        private double[] previousFilteredState;

        /**
         * Tracks whether the filter has processed its first data point.
         * The first point is used directly to initialize the filter's state.
         */
        private boolean isInitialized;

        /**
         * Constructs a new exponential smoothing filter.
         *
         * @param alpha     The desired smoothing factor, clamped between 0.0 and 1.0.
         * @param dimension The dimensionality of the data vectors to be filtered.
         *                  Must be a positive integer.
         */
        public FusionFilter(double alpha, int dimension) {
            if (dimension <= 0) {
                throw new IllegalArgumentException("Dimension must be positive.");
            }
            // Ensure the smoothing factor is within the valid range [0.0, 1.0]
            this.smoothingFactor = Math.max(0.0, Math.min(1.0, alpha));
            this.numDimensions = dimension;
            this.previousFilteredState = new double[dimension];
            this.isInitialized = false;

            Log.i(TAG, "FusionFilter initialized with factor=" + this.smoothingFactor +
                    ", dimensions=" + this.numDimensions);
        }

        /**
         * Processes a new data point (vector) using the configured smoothing factor.
         *
         * @param currentInput A double array representing the latest measurement vector.
         *                     Its length must match the filter's dimensionality.
         * @return A double array containing the newly computed smoothed values.
         *         Returns the input array unmodified if dimensions mismatch or on error.
         */
        public double[] update(double[] currentInput) {
            // Validate input dimensions
            if (currentInput == null || currentInput.length != this.numDimensions) {
                Log.e(TAG, "Input array dimension mismatch or null. Expected: " +
                        this.numDimensions + ", Got: " +
                        (currentInput == null ? "null" : currentInput.length));
                // Return input defensively, though throwing might be alternative
                return (currentInput != null) ? currentInput.clone() : new double[this.numDimensions];
            }

            // Handle the first data point: Initialize state and return input directly
            if (!this.isInitialized) {
                System.arraycopy(currentInput, 0, this.previousFilteredState, 0, this.numDimensions);
                this.isInitialized = true;
                Log.d(TAG, "Filter initialized with first values.");
                // Return a clone to prevent external modification of the input array
                // affecting future initializations if the caller reuses the array.
                return currentInput.clone();
            }

            // Apply the exponential smoothing formula for each dimension
            double[] filteredOutput = new double[this.numDimensions];
            for (int i = 0; i < this.numDimensions; i++) {
                // smoothed = alpha * new + (1 - alpha) * previous_smoothed
                filteredOutput[i] = this.smoothingFactor * currentInput[i] +
                        (1.0 - this.smoothingFactor) * this.previousFilteredState[i];
            }

            // Update the internal state for the next iteration
            // It's safe to directly assign filteredOutput here if we ensure it's not modified externally
            // However, using arraycopy is safer if there were complex return paths.
            // Let's use System.arraycopy for consistency and explicit state update.
            System.arraycopy(filteredOutput, 0, this.previousFilteredState, 0, this.numDimensions);

            return filteredOutput;
        }

        /**
         * Processes a new data point using a temporarily specified smoothing factor,
         * overriding the instance's default factor for this single operation.
         * Useful for adaptive smoothing scenarios.
         *
         * @param currentInput    A double array representing the latest measurement vector.
         *                        Its length must match the filter's dimensionality.
         * @param temporalFactor The smoothing factor to use for this specific update,
         *                        clamped between 0.0 and 1.0.
         * @return A double array containing the newly computed smoothed values using the temporal factor.
         *         Returns the input array unmodified if dimensions mismatch or on error.
         */
        public double[] updateWithTemporalFactor(double[] currentInput, double temporalFactor) {
            // Validate input dimensions
            if (currentInput == null || currentInput.length != this.numDimensions) {
                Log.e(TAG, "Input array dimension mismatch or null. Expected: " +
                        this.numDimensions + ", Got: " +
                        (currentInput == null ? "null" : currentInput.length));
                return (currentInput != null) ? currentInput.clone() : new double[this.numDimensions];
            }

            // Ensure the temporal factor is valid
            double effectiveFactor = Math.max(0.0, Math.min(1.0, temporalFactor));

            // Handle the first data point (same as regular update)
            if (!this.isInitialized) {
                System.arraycopy(currentInput, 0, this.previousFilteredState, 0, this.numDimensions);
                this.isInitialized = true;
                Log.d(TAG, "Filter initialized with first values (using temporal factor update).");
                return currentInput.clone();
            }

            // Apply exponential smoothing using the *temporal* factor
            double[] filteredOutput = new double[this.numDimensions];
            for (int i = 0; i < this.numDimensions; i++) {
                filteredOutput[i] = effectiveFactor * currentInput[i] +
                        (1.0 - effectiveFactor) * this.previousFilteredState[i];
            }

            // CRITICAL: Update the internal state using the result calculated
            // with the temporal factor. This means the *next* regular 'update'
            // call will use this value as its 'previousFilteredState'.
            System.arraycopy(filteredOutput, 0, this.previousFilteredState, 0, this.numDimensions);

            return filteredOutput;
        }

        /**
         * Retrieves the configured default smoothing factor (alpha) of the filter.
         *
         * @return The smoothing factor used by the {@link #update(double[])} method.
         */
        public double getSmoothingFactor() {
            return this.smoothingFactor;
        }

        /**
         * Retrieves the last calculated filtered state vector.
         *
         * @return A *copy* of the double array containing the most recent smoothed values.
         *         Returns a zeroed array if the filter hasn't been initialized yet.
         */
        public double[] getCurrentEstimate() {
            // Return a clone to prevent external modification of the internal state
            if (this.isInitialized) {
                return this.previousFilteredState.clone();
            } else {
                // Return a new zero array of the correct dimension if not initialized
                Log.w(TAG, "getCurrentEstimate called before filter initialization. Returning zero vector.");
                return new double[this.numDimensions];
            }
        }

        /**
         * Manually sets the internal state of the filter. This effectively initializes
         * or re-initializes the filter with a specific known state.
         *
         * @param state A double array representing the desired state vector. Its length
         *              must match the filter's dimensionality.
         */
        public void setState(double[] state) {
            if (state == null || state.length != this.numDimensions) {
                Log.e(TAG, "Cannot set filter state: dimension mismatch or null input. Expected: " +
                        this.numDimensions + ", Got: " + (state == null ? "null" : state.length));
                return; // Do not change state if input is invalid
            }

            // Copy the provided state into the internal state array
            System.arraycopy(state, 0, this.previousFilteredState, 0, this.numDimensions);
            this.isInitialized = true; // Mark as initialized since we now have a valid state
            Log.d(TAG, "Filter state manually set.");
        }

        /**
         * Resets the filter to its initial, uninitialized state.
         * The internal state vector is cleared (typically to zeros), and the
         * filter will require a new data point to become initialized again.
         */
        public void reset() {
            // Consider filling with NaN or another indicator, but zeros are common.
            Arrays.fill(this.previousFilteredState, 0.0);
            this.isInitialized = false;
            Log.i(TAG, "FusionFilter has been reset.");
        }
    }

    /**
     * Detects statistical outliers within a stream of distance measurements.
     *
     * This implementation employs a robust modified Z-score methodology based on the
     * Median Absolute Deviation (MAD) calculated over a sliding window of recent measurements.
     * It includes a hard limit check for immediate rejection of implausible values.
     */
    public static class FusionOutlierDetector {
        private static final String TAG = FusionOutlierDetector.class.getSimpleName();

        // --- Configuration Constants ---

        /** The modified Z-score threshold above which a measurement is considered an outlier. */
        private static final double MODIFIED_Z_SCORE_THRESHOLD = 3.5;
        /** The normalization constant used in the modified Z-score calculation (1 / Φ⁻¹(3/4) ≈ 0.6745). */
        private static final double MAD_NORMALIZATION_FACTOR = 0.6745;
        /** An absolute maximum distance allowed; measurements exceeding this are immediately flagged as outliers. */
        private static final double ABSOLUTE_DISTANCE_THRESHOLD = 15.0; // meters
        /** A small value to prevent division by zero or near-zero MAD values. */
        private static final double MINIMUM_MAD_VALUE = 1e-4;
        /** The minimum number of samples required in the buffer before statistical analysis can be performed. */
        private static final int MINIMUM_SAMPLES_FOR_STATS = 4;
        /** The default size of the sliding window used for statistical calculations. */
        private static final int DEFAULT_BUFFER_SIZE = 10;

        // --- State Variables ---

        /** The maximum number of distance measurements to retain in the sliding window. */
        private final int bufferCapacity;
        /** Stores the recent distance measurements within the sliding window. */
        private final List<Double> distanceWindow;
        /** A reusable list for performing median calculations without repeated allocations. */
        private final List<Double> calculationWorkList;

        /**
         * Constructs a FusionOutlierDetector with the default window size.
         *
         * @see #DEFAULT_BUFFER_SIZE
         */
        public FusionOutlierDetector() {
            this(DEFAULT_BUFFER_SIZE);
        }

        /**
         * Constructs a FusionOutlierDetector with a specified window size.
         *
         * @param windowSize The number of recent distance measurements to consider for outlier detection.
         *                   Must be at least 5.
         * @throws IllegalArgumentException if windowSize is less than 5.
         */
        public FusionOutlierDetector(int windowSize) {
            if (windowSize < 5) {
                // Ensure a reasonable minimum size for statistical stability and median calculation.
                Log.w(TAG, "Requested window size " + windowSize + " is too small. Using minimum of 5.");
                this.bufferCapacity = 5;
            } else {
                this.bufferCapacity = windowSize;
            }
            this.distanceWindow = new ArrayList<>(this.bufferCapacity);
            this.calculationWorkList = new ArrayList<>(this.bufferCapacity);

            Log.i(TAG, "Initialized with buffer capacity: " + this.bufferCapacity);
        }

        /**
         * Evaluates a new distance measurement to determine if it is an outlier relative
         * to the recent history of measurements.
         *
         * The method first checks against an absolute hard limit. If the value is plausible,
         * it's added to a sliding window. Once enough samples are collected, it calculates
         * the modified Z-score based on the median and MAD of the window data.
         *
         * @param newDistance The distance measurement (in meters) to evaluate.
         * @return {@code true} if the measurement is identified as an outlier, {@code false} otherwise.
         */
        public boolean evaluateDistance(double newDistance) {
            // 1. Absolute Hard Limit Check
            if (newDistance > ABSOLUTE_DISTANCE_THRESHOLD) {
                Log.d(TAG, String.format("Outlier (Hard Limit): Distance %.2fm exceeds threshold %.2fm",
                        newDistance, ABSOLUTE_DISTANCE_THRESHOLD));
                // Note: We don't add this obviously invalid measurement to the buffer.
                return true;
            }

            // 2. Add to Sliding Window & Maintain Size
            addToWindow(newDistance);

            // 3. Check Minimum Sample Requirement
            if (distanceWindow.size() < MINIMUM_SAMPLES_FOR_STATS) {
                // Not enough data for reliable statistics yet.
                return false;
            }

            // 4. Calculate Statistics
            double median = computeMedian(distanceWindow);
            double mad = computeMedianAbsoluteDeviation(median);

            // Prevent division by very small MAD values
            double effectiveMad = Math.max(mad, MINIMUM_MAD_VALUE);

            // 5. Calculate Modified Z-Score
            // Formula: M_i = (0.6745 * |x_i - median|) / MAD
            double modifiedZScore = MAD_NORMALIZATION_FACTOR * Math.abs(newDistance - median) / effectiveMad;

            // 6. Compare Score to Threshold
            boolean isOutlier = modifiedZScore > MODIFIED_Z_SCORE_THRESHOLD;

            // 7. Logging
            if (isOutlier) {
                Log.d(TAG, String.format("Outlier (Statistical): Distance %.2fm, Median %.2fm, MAD %.2f, Z-Score %.2f > %.2f",
                        newDistance, median, mad, modifiedZScore, MODIFIED_Z_SCORE_THRESHOLD));
            } else {
                // Optional: Log non-outliers for debugging if needed
                // Log.v(TAG, String.format("Inlier: Distance %.2fm, Median %.2fm, MAD %.2f, Z-Score %.2f <= %.2f",
                //       newDistance, median, mad, modifiedZScore, MODIFIED_Z_SCORE_THRESHOLD));
            }

            return isOutlier;
        }

        /**
         * Adds a new distance measurement to the sliding window, removing the oldest
         * measurement if the buffer capacity is exceeded.
         *
         * @param distance The distance measurement to add.
         */
        private void addToWindow(double distance) {
            distanceWindow.add(distance);
            if (distanceWindow.size() > bufferCapacity) {
                distanceWindow.remove(0); // Remove the oldest element
            }
        }

        /**
         * Computes the median value from a list of numerical values.
         * Uses a reusable internal list to avoid allocations and sorts it.
         *
         * @param values The list of values for which to compute the median. Cannot be empty.
         * @return The median value.
         */
        private double computeMedian(List<Double> values) {
            Objects.requireNonNull(values, "Input list cannot be null");
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Input list cannot be empty for median calculation");
            }

            // Use the work list to avoid modifying the original and reduce allocations
            calculationWorkList.clear();
            calculationWorkList.addAll(values);
            Collections.sort(calculationWorkList);

            int size = calculationWorkList.size();
            int midIndex = size / 2;

            if (size % 2 == 0) {
                // Even number of elements: average the two middle elements
                return (calculationWorkList.get(midIndex - 1) + calculationWorkList.get(midIndex)) / 2.0;
            } else {
                // Odd number of elements: return the middle element
                return calculationWorkList.get(midIndex);
            }
        }

        /**
         * Computes the Median Absolute Deviation (MAD) for the current distance window,
         * relative to a provided median value.
         *
         * @param median The pre-calculated median of the {@code distanceWindow}.
         * @return The Median Absolute Deviation.
         */
        private double computeMedianAbsoluteDeviation(double median) {
            if (distanceWindow.isEmpty()) {
                return 0.0; // Or handle as an error case if appropriate
            }

            // Reuse the work list for calculating absolute deviations
            calculationWorkList.clear();
            for (double value : distanceWindow) {
                calculationWorkList.add(Math.abs(value - median));
            }

            // The MAD is the median of these absolute deviations
            // Relying on computeMedian's logic which uses calculationWorkList
            Collections.sort(calculationWorkList); // Need to sort the deviations before finding their median

            int size = calculationWorkList.size();
            int midIndex = size / 2;

            if (size % 2 == 0) {
                // Even number of elements: average the two middle elements
                return (calculationWorkList.get(midIndex - 1) + calculationWorkList.get(midIndex)) / 2.0;
            } else {
                // Odd number of elements: return the middle element
                return calculationWorkList.get(midIndex);
            }
            // Note: Could also call computeMedian(calculationWorkList) here,
            // but direct implementation avoids redundant checks and clearing.
        }

        /**
         * Clears all recorded distance measurements from the internal buffer.
         * Resets the detector to its initial state.
         */
        public void clearHistory() {
            distanceWindow.clear();
            calculationWorkList.clear(); // Also clear the work list
            Log.i(TAG, "History cleared.");
        }

        /**
         * Retrieves a copy of the current list of distance measurements stored in the window.
         *
         * @return A new {@link List} containing the distances currently in the buffer.
         *         Modifying this list will not affect the internal state of the detector.
         */
        public List<Double> getDistanceHistory() {
            return new ArrayList<>(distanceWindow);
        }

        /**
         * Gets the configured capacity of the internal sliding window buffer.
         *
         * @return The maximum number of measurements the buffer can hold.
         */
        public int getBufferCapacity() {
            return bufferCapacity;
        }
    }
}