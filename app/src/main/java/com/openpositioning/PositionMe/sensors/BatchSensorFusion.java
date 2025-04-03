package com.openpositioning.PositionMe.sensors;

import org.ejml.simple.SimpleMatrix;

import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.BasicCoordinateTransform;
import org.locationtech.proj4j.ProjCoordinate;
import com.google.android.gms.maps.model.LatLng;
import android.util.Log;

/**
 * The BatchSensorFusion class implements a batch optimization approach to sensor fusion.
 * This class collects measurements from multiple sensors (GNSS, WiFi, PDR) over a time window
 * and performs batch optimization to determine the most likely trajectory.
 *
 * @author Joseph Azrak
 */
public class BatchSensorFusion {
    private static final String TAG = "BatchSensorFusion";

    // State window - stores position estimates for a window of time
    private SimpleMatrix[] stateWindow;
    // Measurement windows for different sensor types
    private SimpleMatrix[] gnssWindow;
    private SimpleMatrix[] wifiWindow;
    private SimpleMatrix[] pdrWindow;
    // Current window size
    private int windowSize;
    // Maximum window size
    private final int MAX_WINDOW_SIZE;
    // Window timestamps
    private long[] timestamps;

    // Process noise covariance (2x2)
    private SimpleMatrix Q;
    // Measurement noise covariance for different sensors (2x2 each)
    private SimpleMatrix R_gnss;
    private SimpleMatrix R_wifi;
    private SimpleMatrix R_pdr;

    // Parameters for numerical stability
    private static final double DAMPING_FACTOR = 0.3; // More conservative damping
    private static final double MAX_UPDATE_THRESHOLD = 50.0; // Stricter limit on updates
    private static final double REGULARIZATION_STRENGTH = 1e-2; // Stronger regularization
    private static final int MAX_ITERATIONS = 5; // Fewer iterations to prevent problems

    // UTM zone info for coordinate conversion
    private int utmZone;
    private boolean isNorthernHemisphere;

    // Initial reference point for sanity checking
    private LatLng initialReferencePoint;

    /**
     * Constructor to initialize the Batch Sensor Fusion with given parameters.
     * 
     * This constructor sets up the batch optimization framework with initial state,
     * window size, and noise parameters. It initializes the state window arrays
     * and measurement history arrays for GNSS, WiFi, and PDR data. The constructor
     * also sets default UTM zone parameters for coordinate transformations, which
     * will be updated when the first GNSS measurement is received.
     *
     * @param initialState 2x1 initial state vector [x; y] in UTM coordinates
     * @param maxWindowSize Maximum number of states to keep in the window
     * @param Q Process noise covariance (2x2) representing uncertainty in motion model
     * @param R_gnss Measurement noise covariance for GNSS (2x2) representing uncertainty in GNSS readings
     * @param R_wifi Measurement noise covariance for WiFi (2x2) representing uncertainty in WiFi positioning
     * @param R_pdr Measurement noise covariance for PDR (2x2) representing uncertainty in step detection
     * 
     * @author Joseph Azrak
     */
    public BatchSensorFusion(SimpleMatrix initialState, int maxWindowSize,
                             SimpleMatrix Q,
                             SimpleMatrix R_gnss, SimpleMatrix R_wifi, SimpleMatrix R_pdr) {
        this.MAX_WINDOW_SIZE = maxWindowSize;
        this.windowSize = 1; // Start with just the initial state

        // Initialize window arrays
        this.stateWindow = new SimpleMatrix[MAX_WINDOW_SIZE];
        this.gnssWindow = new SimpleMatrix[MAX_WINDOW_SIZE];
        this.wifiWindow = new SimpleMatrix[MAX_WINDOW_SIZE];
        this.pdrWindow = new SimpleMatrix[MAX_WINDOW_SIZE];
        this.timestamps = new long[MAX_WINDOW_SIZE];

        // Set initial state
        this.stateWindow[0] = initialState;

        // Set covariance matrices
        this.Q = Q;
        this.R_gnss = R_gnss;
        this.R_wifi = R_wifi;
        this.R_pdr = R_pdr;

        // Initialize measurement windows with null (no measurements yet)
        for (int i = 0; i < MAX_WINDOW_SIZE; i++) {
            this.gnssWindow[i] = null;
            this.wifiWindow[i] = null;
            this.pdrWindow[i] = null;
        }

        // Default UTM zone (will be updated with the first GNSS measurement)
        this.utmZone = 30; // Default to zone 30 (covers Edinburgh)
        this.isNorthernHemisphere = true; // Default to northern hemisphere

        Log.d(TAG, "BatchSensorFusion initialized with initial state: " + initialState);
    }

    /**
     * Adds a new state to the window with optional measurements from different sensors.
     * 
     * This method incorporates new sensor measurements into the state window, extending
     * the trajectory with a new position estimate. If the window is already at maximum
     * capacity, it shifts all states to make room for the new one. The method handles
     * data from multiple sensor types (PDR, GNSS, WiFi) and applies sanity checks to
     * prevent divergence due to erroneous measurements.
     * 
     * Key features:
     * - Updates UTM zone information from the first GNSS measurement
     * - Applies displacement limits to PDR measurements for stability
     * - Performs validity checks on all sensor data
     * - Maintains a window of state estimates and corresponding measurements
     * 
     * Shifts the window if it reaches maximum size.
     *
     * @param timestamp The timestamp for this state in milliseconds
     * @param pdrMeasurement PDR displacement measurement as 2x1 vector [dx, dy] (can be null)
     * @param gnssMeasurement GNSS position measurement as 2x1 vector [x, y] (can be null)
     * @param wifiMeasurement WiFi position measurement as 2x1 vector [x, y] (can be null)
     * 
     * @author Joseph Azrak
     */
    public void addStateToWindow(long timestamp,
                                 SimpleMatrix pdrMeasurement,
                                 SimpleMatrix gnssMeasurement,
                                 SimpleMatrix wifiMeasurement) {
        // If window is full, shift everything
        if (windowSize == MAX_WINDOW_SIZE) {
            shiftWindow();
        }

        // Add new measurements at the end of the window
        int idx = windowSize;

        // Update UTM zone from GNSS measurement if available (for first measurement only)
        if (gnssMeasurement != null && (initialReferencePoint == null)) {
            float x = (float)gnssMeasurement.get(0, 0);
            float y = (float)gnssMeasurement.get(1, 0);

            // Back-transform to get LatLng
            LatLng gnssLatLng = getInverseTransformedCoordinate(x, y, utmZone, isNorthernHemisphere);

            // Store as reference and update UTM zone
            initialReferencePoint = gnssLatLng;
            utmZone = (int) Math.floor((gnssLatLng.longitude + 180) / 6) + 1;
            isNorthernHemisphere = gnssLatLng.latitude >= 0;

            Log.d(TAG, "UTM Zone set to: " + utmZone + " (Northern: " + isNorthernHemisphere +
                    ") from GNSS: " + gnssLatLng.latitude + ", " + gnssLatLng.longitude);
        }

        // If we have a new state from PDR, add it
        if (pdrMeasurement != null) {
            // For PDR, we typically use the previous state plus the displacement
            SimpleMatrix prevState = stateWindow[windowSize - 1];

            // Limit PDR displacements to reasonable values to prevent divergence
            double dx = clamp(pdrMeasurement.get(0, 0), -5.0, 5.0);
            double dy = clamp(pdrMeasurement.get(1, 0), -5.0, 5.0);

            SimpleMatrix dampedPdr = new SimpleMatrix(2, 1, true, new double[] { dx, dy });
            SimpleMatrix newState = prevState.plus(dampedPdr);

            // Let's make sure the new state is reasonable
            if (isFinite(newState)) {
                stateWindow[idx] = newState;
                pdrWindow[idx] = dampedPdr;
            } else {
                Log.e(TAG, "Non-finite state detected from PDR, using previous state");
                stateWindow[idx] = prevState.copy();
                pdrWindow[idx] = new SimpleMatrix(2, 1); // Zero displacement
            }
        } else {
            // Otherwise, duplicate the last state (random walk)
            stateWindow[idx] = stateWindow[windowSize - 1].copy();
            pdrWindow[idx] = null;
        }

        // Add other sensor measurements (with sanity checks)
        if (gnssMeasurement != null && isFinite(gnssMeasurement)) {
            gnssWindow[idx] = gnssMeasurement;
        } else {
            gnssWindow[idx] = null;
        }

        if (wifiMeasurement != null && isFinite(wifiMeasurement)) {
            wifiWindow[idx] = wifiMeasurement;
        } else {
            wifiWindow[idx] = null;
        }

        timestamps[idx] = timestamp;

        // Increase window size
        windowSize++;

        // Print diagnostics for the newest state
        if (stateWindow[idx] != null) {
            Log.d(TAG, "Added new state [" + idx + "]: x=" + stateWindow[idx].get(0, 0) +
                    ", y=" + stateWindow[idx].get(1, 0));
        }
    }

    /**
     * Shifts all elements in the window arrays to make room for new entries.
     * 
     * This method is called when the window has reached its maximum capacity and
     * a new state needs to be added. It moves all elements one position earlier
     * in their respective arrays, effectively dropping the oldest state and
     * measurement data. After shifting, the last element is set to null to avoid
     * stale data, and the window size is decremented to reflect that the last
     * position is now available for new data.
     * 
     * The method shifts the following arrays:
     * - stateWindow: Position estimates [x, y]
     * - gnssWindow: GNSS measurements
     * - wifiWindow: WiFi positioning measurements
     * - pdrWindow: PDR displacement measurements
     * - timestamps: Corresponding timestamps for each state
     * 
     * @author Joseph Azrak
     */
    private void shiftWindow() {
        for (int i = 0; i < MAX_WINDOW_SIZE - 1; i++) {
            stateWindow[i] = stateWindow[i + 1];
            gnssWindow[i] = gnssWindow[i + 1];
            wifiWindow[i] = wifiWindow[i + 1];
            pdrWindow[i] = pdrWindow[i + 1];
            timestamps[i] = timestamps[i + 1];
        }
        windowSize--;  // Reduce window size since the last element will be overwritten

        // Reset last element to null to avoid stale data
        stateWindow[windowSize] = null;
        gnssWindow[windowSize] = null;
        wifiWindow[windowSize] = null;
        pdrWindow[windowSize] = null;
    }

    /**
     * Runs batch optimization on the current window of states and measurements.
     * 
     * This method performs a multi-state optimization over the entire trajectory window,
     * simultaneously incorporating all available measurements and motion constraints.
     * It implements a damped Gauss-Newton optimization approach with numerous safeguards
     * to ensure numerical stability and prevent divergence.
     * 
     * The optimization process:
     * 1. Constructs an information matrix and vector representing all constraints
     * 2. Adds process model constraints (smoothness between consecutive states)
     * 3. Adds measurement constraints from GNSS and WiFi sensors
     * 4. Solves the system to find optimal state updates
     * 5. Applies updates with damping and clamping to prevent instability
     * 6. Repeats until convergence or maximum iterations reached
     * 
     * Safety features include:
     * - Regularization to ensure matrix condition
     * - Damping to prevent excessive updates
     * - Convergence detection with early stopping
     * - Divergence detection
     * - Sanity checks on final position estimates
     * 
     * @author Joseph Azrak
     */
    public void optimizeBatch() {
        // Skip if window is too small
        if (windowSize < 2) {
            Log.d(TAG, "Window too small for optimization, skipping");
            return;
        }

        Log.d(TAG, "Starting batch optimization with " + windowSize + " states");

        // Number of iterations for optimization
        int iterations = 0;
        double lastMaxUpdate = Double.MAX_VALUE;
        boolean hasConverged = false;

        while (iterations < MAX_ITERATIONS && !hasConverged) {
            // Reset the information matrix and vector for this iteration
            SimpleMatrix H = new SimpleMatrix(windowSize * 2, windowSize * 2);
            SimpleMatrix b = new SimpleMatrix(windowSize * 2, 1);

            // First, add motion model constraints (smoothness between consecutive states)
            for (int i = 1; i < windowSize; i++) {
                // Add process model: x_i = x_{i-1} + u_i + noise
                // where u_i is the PDR measurement (if available)

                // Information for process model (inverse of Q)
                SimpleMatrix Qinv;
                try {
                    Qinv = Q.invert();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to invert Q matrix, using simplified version", e);
                    // Fallback to diagonal matrix if inversion fails
                    Qinv = SimpleMatrix.identity(2).scale(1.0/0.1);
                }

                // Fill in the blocks of H for current state and previous state
                fillInfoMatrixBlock(H, Qinv, (i-1)*2, (i-1)*2); // x_{i-1}
                fillInfoMatrixBlock(H, Qinv.scale(-1), (i-1)*2, i*2); // x_i
                fillInfoMatrixBlock(H, Qinv.scale(-1), i*2, (i-1)*2); // x_{i-1}
                fillInfoMatrixBlock(H, Qinv, i*2, i*2); // x_i

                // Calculate the expected difference between states
                SimpleMatrix diff;
                if (pdrWindow[i] != null) {
                    // If we have PDR measurement, expected diff is the PDR displacement
                    diff = pdrWindow[i];
                } else {
                    // Otherwise, expected diff is zero (states should be similar)
                    diff = new SimpleMatrix(2, 1);
                }

                // Calculate the actual difference between states
                SimpleMatrix actualDiff = stateWindow[i].minus(stateWindow[i-1]);

                // Error term
                SimpleMatrix error = actualDiff.minus(diff);

                // Clamp error values to prevent extreme updates
                for (int j = 0; j < error.getNumElements(); j++) {
                    double val = error.get(j);
                    error.set(j, clamp(val, -MAX_UPDATE_THRESHOLD, MAX_UPDATE_THRESHOLD));
                }

                // Update the information vector b
                SimpleMatrix contribution = Qinv.mult(error);
                b.insertIntoThis((i-1)*2, 0, b.extractMatrix((i-1)*2, (i-1)*2 + 2, 0, 1).plus(contribution.scale(-1)));
                b.insertIntoThis(i*2, 0, b.extractMatrix(i*2, i*2 + 2, 0, 1).plus(contribution));
            }

            // Now add measurement constraints
            for (int i = 0; i < windowSize; i++) {
                // Add GNSS measurement if available (with higher weight)
                if (gnssWindow[i] != null) {
                    SimpleMatrix scaledR = R_gnss.scale(0.5); // Reduce variance = increase weight
                    addMeasurementConstraint(H, b, i, gnssWindow[i], scaledR);
                }

                // Add WiFi measurement if available
                if (wifiWindow[i] != null) {
                    addMeasurementConstraint(H, b, i, wifiWindow[i], R_wifi);
                }
            }

            // Solve the system H * delta = b
            // Add regularization term to ensure matrix is well-conditioned
            SimpleMatrix regularization = SimpleMatrix.identity(H.numRows()).scale(REGULARIZATION_STRENGTH);
            H = H.plus(regularization);

            SimpleMatrix delta;
            try {
                delta = H.solve(b);

                // Apply damping to prevent excessive updates
                delta = delta.scale(DAMPING_FACTOR);

                // Calculate max update for convergence check
                double maxUpdate = delta.elementMaxAbs();
                Log.d(TAG, "Iteration " + iterations + " completed. Max update: " + maxUpdate);

                // Update all states in the window
                for (int i = 0; i < windowSize; i++) {
                    SimpleMatrix stateUpdate = delta.extractMatrix(i*2, (i+1)*2, 0, 1);

                    // Clamp individual updates
                    for (int j = 0; j < stateUpdate.getNumElements(); j++) {
                        double val = stateUpdate.get(j);
                        stateUpdate.set(j, clamp(val, -MAX_UPDATE_THRESHOLD, MAX_UPDATE_THRESHOLD));
                    }

                    SimpleMatrix updatedState = stateWindow[i].plus(stateUpdate);

                    // Sanity check: Make sure all elements are finite
                    if (isFinite(updatedState)) {
                        stateWindow[i] = updatedState;
                    } else {
                        Log.e(TAG, "Non-finite state detected, skipping update for state " + i);
                    }
                }

                // Check for convergence
                hasConverged = (maxUpdate < 1e-2) || Math.abs(maxUpdate - lastMaxUpdate) < 1e-3;

                // If updates are getting larger, stop early to prevent divergence
                if (maxUpdate > lastMaxUpdate * 1.5 && iterations > 1) {
                    Log.w(TAG, "Updates growing, stopping optimization to prevent divergence");
                    break;
                }

                lastMaxUpdate = maxUpdate;

            } catch (Exception e) {
                Log.e(TAG, "Error solving system: " + e.getMessage(), e);
                break;
            }

            iterations++;
        }

        // Log final state for debugging
        if (windowSize > 0) {
            SimpleMatrix finalState = stateWindow[windowSize - 1];
            Log.d(TAG, "Final optimized state: x=" + finalState.get(0,0) + ", y=" + finalState.get(1,0));

            // Validate the final state
            LatLng position = getFinalPositionAsLatLng();
            if (position != null) {
                // Check if the result is reasonable (within bounds of the GNSS reading)
                if (initialReferencePoint != null) {
                    double distanceDeg = Math.sqrt(
                            Math.pow(position.latitude - initialReferencePoint.latitude, 2) +
                                    Math.pow(position.longitude - initialReferencePoint.longitude, 2));

                    // If position is more than 0.5 degrees away from reference (very large distance),
                    // log a warning and revert to a more conservative estimate
                    if (distanceDeg > 0.5) {
                        Log.w(TAG, "Position estimate too far from reference: " + position +
                                " vs " + initialReferencePoint + ". Distance: " + distanceDeg + " degrees");

                        // Force the position to be near the reference (for safety)
                        if (position.latitude > 90 || position.latitude < -90 ||
                                position.longitude > 180 || position.longitude < -180) {

                            Log.e(TAG, "Invalid coordinates detected, resetting to reference point");

                            // Use reference point (or a slight offset from it)
                            float[] xy = getTransformedCoordinate(initialReferencePoint);
                            SimpleMatrix safeState = new SimpleMatrix(2, 1, true,
                                    new double[] { xy[0], xy[1] });

                            // Apply the safe position
                            stateWindow[windowSize - 1] = safeState;
                        }
                    }
                }
            }
        }
    }

    /**
     * Helper method to fill a 2x2 block in the information matrix.
     * 
     * This utility method efficiently copies values from a 2x2 matrix block
     * into a specific location within the larger information matrix used in
     * batch optimization. It's used repeatedly to construct the sparse block
     * structure of the information matrix that represents relationships between
     * consecutive states and measurement constraints.
     * 
     * @param H The target information matrix to fill
     * @param block The 2x2 source matrix block to copy
     * @param row The starting row index in the target matrix
     * @param col The starting column index in the target matrix
     * 
     * @author Joseph Azrak
     */
    private void fillInfoMatrixBlock(SimpleMatrix H, SimpleMatrix block, int row, int col) {
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                H.set(row + i, col + j, block.get(i, j));
            }
        }
    }

    /**
     * Helper method to add a measurement constraint to the information matrix and vector.
     * 
     * This method incorporates a sensor measurement (GNSS or WiFi) into the optimization
     * problem by updating the information matrix and vector. It calculates the error
     * between the current state estimate and the measurement, applies safety limits,
     * and adds the weighted contribution to the system.
     * 
     * The method performs several important steps:
     * 1. Performs validation to ensure measurements are valid
     * 2. Computes the information matrix contribution (using the inverse of R)
     * 3. Calculates the error between the current state and the measurement
     * 4. Applies clamping to prevent extreme updates
     * 5. Adds the weighted contribution to the information vector
     * 
     * The method includes error handling for matrix inversion failures.
     * 
     * @param H The information matrix to update
     * @param b The information vector to update
     * @param stateIdx The index of the state that this measurement constrains
     * @param measurement The 2x1 measurement vector [x, y]
     * @param R The 2x2 measurement noise covariance matrix
     * 
     * @author Joseph Azrak
     */
    private void addMeasurementConstraint(SimpleMatrix H, SimpleMatrix b, int stateIdx,
                                          SimpleMatrix measurement, SimpleMatrix R) {
        // If measurement is null or state is null, skip
        if (measurement == null || stateWindow[stateIdx] == null) {
            return;
        }

        // Check for invalid measurements
        for (int i = 0; i < measurement.getNumElements(); i++) {
            if (!Double.isFinite(measurement.get(i))) {
                Log.w(TAG, "Skipping non-finite measurement at state " + stateIdx);
                return;
            }
        }

        // Information from this measurement (inverse of R)
        SimpleMatrix Rinv;
        try {
            Rinv = R.invert();
        } catch (Exception e) {
            Log.e(TAG, "Failed to invert R matrix, using simplified version", e);
            // Fallback to diagonal matrix if inversion fails
            Rinv = SimpleMatrix.identity(2).scale(1.0/1.0);
        }

        // Add to the diagonal block for this state
        fillInfoMatrixBlock(H, Rinv, stateIdx*2, stateIdx*2);

        // Calculate error between current state and measurement
        SimpleMatrix error = measurement.minus(stateWindow[stateIdx]);

        // Clamp error values to prevent extreme updates
        for (int i = 0; i < error.getNumElements(); i++) {
            double val = error.get(i);
            error.set(i, clamp(val, -MAX_UPDATE_THRESHOLD, MAX_UPDATE_THRESHOLD));
        }

        // Add to information vector
        SimpleMatrix contribution = Rinv.mult(error);
        b.insertIntoThis(stateIdx*2, 0, b.extractMatrix(stateIdx*2, stateIdx*2 + 2, 0, 1).plus(contribution));
    }

    /**
     * Returns the current state estimate (most recent state in the window).
     * 
     * This method provides access to the latest optimized position estimate,
     * which is the last state in the window after batch optimization has been
     * performed. The state vector contains x and y coordinates in the UTM
     * coordinate system.
     * 
     * If the window is empty (which should not happen during normal operation),
     * the method returns null.
     *
     * @return 2x1 state vector [x; y] in UTM coordinates, or null if no state is available
     * 
     * @author Joseph Azrak
     */
    public SimpleMatrix getState() {
        if (windowSize > 0) {
            return stateWindow[windowSize - 1];
        } else {
            return null;
        }
    }

    /**
     * Returns the entire window of state estimates.
     * 
     * This method provides access to all position estimates currently in the window,
     * allowing access to the full trajectory history that has been optimized by
     * the batch algorithm. The returned array contains only the valid states
     * (up to windowSize), trimmed to the actual number of states present.
     * 
     * Each state in the array is a 2x1 vector containing the x and y coordinates
     * in the UTM coordinate system.
     *
     * @return Array of 2x1 state vectors [x; y] in UTM coordinates, sized to match the current window size
     * 
     * @author Joseph Azrak
     */
    public SimpleMatrix[] getStateWindow() {
        SimpleMatrix[] result = new SimpleMatrix[windowSize];
        System.arraycopy(stateWindow, 0, result, 0, windowSize);
        return result;
    }

    /**
     * Returns the timestamps associated with each state in the window.
     * 
     * This method provides access to the timestamps corresponding to each position
     * estimate in the state window. The timestamps indicate when each state estimate
     * was created, allowing temporal correlation of position data with other events
     * or measurements.
     * 
     * The returned array is trimmed to match the current window size, with each element
     * corresponding to the state at the same index in the state window.
     *
     * @return Array of timestamps in milliseconds, sized to match the current window size
     * 
     * @author Joseph Azrak
     */
    public long[] getTimestamps() {
        long[] result = new long[windowSize];
        System.arraycopy(timestamps, 0, result, 0, windowSize);
        return result;
    }

    /**
     * Returns the final position estimate as a LatLng object.
     * 
     * This method converts the most recent state estimate from UTM coordinates to
     * geographic coordinates (latitude and longitude) that can be used directly with
     * mapping APIs. The conversion uses the UTM zone that was determined when processing
     * GNSS data.
     * 
     * The method includes error handling to catch potential conversion errors and
     * applies sanity checks to ensure the resulting coordinates are valid.
     *
     * @return LatLng object with the position estimate in geographic coordinates, 
     *         or null if no state is available or conversion fails
     * 
     * @author Joseph Azrak
     */
    public LatLng getFinalPositionAsLatLng() {
        SimpleMatrix state = getState();
        if (state == null) return null;

        float x = (float)state.get(0, 0);
        float y = (float)state.get(1, 0);

        try {
            // Convert back to lat/long using correct UTM zone
            return getInverseTransformedCoordinate(x, y, utmZone, isNorthernHemisphere);
        } catch (Exception e) {
            Log.e(TAG, "Error converting position to LatLng: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Helper method to clamp a value within a range.
     * 
     * This utility method ensures that a numeric value stays within specified
     * minimum and maximum bounds. It's used throughout the batch optimization
     * to prevent extreme values from causing numerical instability or divergence,
     * particularly when dealing with error terms and state updates.
     * 
     * @param value The input value to be clamped
     * @param min The minimum allowed value
     * @param max The maximum allowed value
     * @return The clamped value, guaranteed to be between min and max (inclusive)
     * 
     * @author Joseph Azrak
     */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Checks if all elements in a matrix are finite (not NaN or Infinity).
     * 
     * This utility method validates that a matrix contains only finite numerical values.
     * It's used as a safety check throughout the batch optimization to detect and 
     * prevent the propagation of invalid values (NaN or Infinity) that could otherwise
     * cause the algorithm to produce meaningless results or crash.
     * 
     * @param matrix The SimpleMatrix to check for finite values
     * @return true if all elements in the matrix are finite, false otherwise
     * 
     * @author Joseph Azrak
     */
    private boolean isFinite(SimpleMatrix matrix) {
        for (int i = 0; i < matrix.getNumElements(); i++) {
            if (!Double.isFinite(matrix.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Transforms a LatLng coordinate from WGS84 to UTM.
     * 
     * This static utility method converts geographic coordinates (latitude and longitude)
     * in the WGS84 coordinate system to Universal Transverse Mercator (UTM) coordinates,
     * which provide a metric representation suitable for mathematical operations.
     * 
     * The method:
     * 1. Determines the appropriate UTM zone based on the longitude
     * 2. Constructs the proper EPSG code based on latitude (northern/southern hemisphere)
     * 3. Creates coordinate reference systems and a transformation between them
     * 4. Performs the coordinate transformation
     * 5. Includes error handling to prevent crashes
     *
     * @param coord_location A LatLng coordinate in WGS84 (latitude and longitude)
     * @return A float array with [easting, northing] in meters, or [0,0] if conversion fails
     * 
     * @author Joseph Azrak
     */
    public static float[] getTransformedCoordinate(LatLng coord_location) {
        if (coord_location == null) {
            Log.e("BatchSensorFusion", "Null coordinate provided to getTransformedCoordinate");
            return new float[] { 0, 0 };
        }

        int zone = (int) Math.floor((coord_location.longitude + 180) / 6) + 1;
        String epsgCode;
        if (coord_location.latitude >= 0) {
            epsgCode = String.format("EPSG:326%02d", zone);
        } else {
            epsgCode = String.format("EPSG:327%02d", zone);
        }

        try {
            CRSFactory factory = new CRSFactory();
            CoordinateReferenceSystem srcCrs = factory.createFromName("EPSG:4326"); // WGS84
            CoordinateReferenceSystem dstCrs = factory.createFromName(epsgCode);

            BasicCoordinateTransform transform = new BasicCoordinateTransform(srcCrs, dstCrs);

            ProjCoordinate srcCoord = new ProjCoordinate(coord_location.longitude, coord_location.latitude);
            ProjCoordinate dstCoord = new ProjCoordinate();

            transform.transform(srcCoord, dstCoord);

            return new float[] { (float) dstCoord.x, (float) dstCoord.y };
        } catch (Exception e) {
            Log.e("BatchSensorFusion", "Error in coordinate transformation: " + e.getMessage(), e);
            return new float[] { 0, 0 };
        }
    }

    /**
     * Inverse transforms UTM coordinates back to a LatLng (WGS84) coordinate.
     * 
     * This static utility method performs the reverse transformation of 
     * getTransformedCoordinate(), converting from UTM coordinates back to 
     * geographic coordinates. This is necessary when the position estimate
     * needs to be displayed on a map or returned to other components that
     * expect latitude and longitude.
     * 
     * The method:
     * 1. Constructs the proper EPSG code based on UTM zone and hemisphere
     * 2. Creates coordinate reference systems and a transformation between them
     * 3. Performs the coordinate transformation
     * 4. Applies sanity checks to ensure valid geographic coordinates
     * 5. Includes error handling to prevent crashes
     *
     * @param easting The UTM easting coordinate in meters
     * @param northing The UTM northing coordinate in meters
     * @param zone The UTM zone number (1-60)
     * @param isNorthern True if the coordinate is in the northern hemisphere
     * @return A LatLng coordinate (latitude, longitude), or null if conversion fails
     * 
     * @author Joseph Azrak
     */
    public static LatLng getInverseTransformedCoordinate(float easting, float northing, int zone, boolean isNorthern) {
        String epsgCode;
        if (isNorthern) {
            epsgCode = String.format("EPSG:326%02d", zone);
        } else {
            epsgCode = String.format("EPSG:327%02d", zone);
        }

        try {
            CRSFactory factory = new CRSFactory();
            CoordinateReferenceSystem srcCrs = factory.createFromName(epsgCode);      // UTM CRS
            CoordinateReferenceSystem dstCrs = factory.createFromName("EPSG:4326");     // WGS84

            BasicCoordinateTransform transform = new BasicCoordinateTransform(srcCrs, dstCrs);

            ProjCoordinate srcCoord = new ProjCoordinate(easting, northing);
            ProjCoordinate dstCoord = new ProjCoordinate();

            transform.transform(srcCoord, dstCoord);

            double lat = dstCoord.y;
            double lon = dstCoord.x;

            // Apply sanity checks to the coordinates
            lat = clamp(lat, -90.0, 90.0);
            lon = clamp(lon, -180.0, 180.0);

            // Note: dstCoord.x contains longitude and dstCoord.y contains latitude.
            return new LatLng(lat, lon);
        } catch (Exception e) {
            Log.e("BatchSensorFusion", "Error in inverse coordinate transformation: " + e.getMessage(), e);
            return null;
        }
    }
}