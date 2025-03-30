package com.openpositioning.PositionMe.algorithms;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.openpositioning.PositionMe.utils.CoordinateTransform;
import com.openpositioning.PositionMe.utils.ExponentialSmoothingFilter;
import com.openpositioning.PositionMe.utils.OutlierDetector;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.TurnDetector;

import org.ejml.data.SingularMatrixException;
import org.ejml.simple.SimpleMatrix;

import java.util.Arrays;

/**
 * The ExtendedKalmanFilter class implements an Extended Kalman Filter (EKF) for real-time state estimation
 * in navigation systems, particularly for applications involving pedestrian movements. This implementation
 * adjusts and corrects the state of a user based on a combination of sensor inputs and predefined motion models.
 *
 * The filter integrates multiple sources of data including inertial sensors, GPS, and Wi-Fi to provide a refined
 * estimate of position and orientation. The class manages updates from asynchronous data sources using a HandlerThread,
 * ensuring that operations do not block the main application thread and maintain responsive performance.
 *
 * Features:
 * - Real-time prediction and updating of user state based on sensor data.
 * - Handling of various movement types with different noise characteristics.
 * - Opportunistic updates when new data arrives outside the typical update cycle.
 * - Recursive correction for continual refinement of the state estimate.
 * - Use of Wi-Fi and GPS data to enhance localization accuracy.
 * - Smoothing of positional data to reduce noise and improve the output consistency.
 *
 * Key Components:
 * - Fk (State Transition Matrix): Defines how the state changes from one time step to the next without considering the process noise.
 * - Qk (Process Noise Covariance Matrix): Quantifies the uncertainty in the process noise, updated based on movement characteristics and time.
 * - Hk (Observation Matrix): Maps the state space into the observed space, used during the update phase.
 * - Rk (Observation Noise Covariance Matrix): Represents the noise in the measurements, adjusted dynamically based on data source reliability.
 * - Pk (Estimate Error Covariance Matrix): Quantifies the estimated accuracy of the state estimates.
 * - Xk (State Estimate Vector): The estimated state of the system at each time step.
 *
 * Usage:
 * The ExtendedKalmanFilter is primarily used within systems where accurate real-time positional data is crucial,
 * such as in navigation apps, augmented reality platforms, and location-based services. The filter is designed
 * to be robust against errors inherent in individual sensors by fusing data from multiple sources and applying
 * mathematical models to predict and correct the system state dynamically.
 *
 * Example:
 * ExtendedKalmanFilter ekf = new ExtendedKalmanFilter();
 * ekf.predict(currentOrientation, stepSize, averageStepLength, System.currentTimeMillis(), userMovementType);
 * ekf.update(new double[]{sensorX, sensorY}, currentPenaltyFactor);
 * ekf.onStepDetected(detectedEast, detectedNorth, currentAltitude, System.currentTimeMillis());
 *
 * @author Thomas Deppe
 * @author Alexandra Geciova
 * @author Christopher Khoo
 */
public class ExtendedKalmanFilter{

    // Threshold for relevance in the given context, measured in a specific unit (e.g., points).
    private final static long relevanceThreshold = 5000;
    // The allowable percentage error in a step calculation, as a fraction (0.1 represents 10%).
    private final static double stepPercentageError = 0.1;
    // The misdirection error rate per step as a fraction (0.2 represents 20%).
    private final static double stepMisdirection = 0.2;
    // Default length of each step in the algorithm, likely measured in units relevant to the context (e.g., meters).
    private final static double defaultStepLength = 0.7;
    // Maximum elapsed time that triggers the maximum penalty, specified in milliseconds.
    private final static long maxElapsedTimeForMaxPenalty = 6000;
    // Time threshold for bearing penalty, expressed in minutes.
    private final static long maxElapsedTimeForBearingPenalty = 15;
    // Maximum bearing penalty applied, converted from degrees to radians for internal calculations.
    private final static double maxBearingPenalty = Math.toRadians(22.5);
    // Standard deviation for the change in angle (θ) process noise, expressed in radians.
    private final static double sigma_dTheta = Math.toRadians(15);
    // Standard deviation for pseudo-range measurements, expressed in radians.
    private final static double sigma_dPseudo = Math.toRadians(8);
    // Standard deviation for straight-line movement noise, expressed in radians.
    private final static double sigma_dStraight = Math.toRadians(2);
    // Smoothing factor for a filter or algorithm, likely used to average or reduce noise in measurements.
    private final static double smoothingFactor = 0.35;
    // Standard deviation for the displacement step process noise.
    private double sigma_ds = 1;
    // Standard deviation for northward measurement noise, used in PDR.
    private double sigma_north_meas = 10;
    // Standard deviation for eastward measurement noise, used in PDR.
    private double sigma_east_meas = 10;
    // Standard deviation for WiFi-based location measurements.
    private double wifi_std = 10;
    // Standard deviation for GNSS (GPS) location measurements.
    private double gnss_std = 5;
    // State transition matrix, defining how the state evolves from one time step to the next without considering the process noise.
    private SimpleMatrix Fk;
    // Process noise covariance matrix, quantifying the uncertainty in the process noise.
    private SimpleMatrix Qk;
    // Observation matrix, mapping the state space into the observed space, used in the update step.
    private SimpleMatrix Hk;
    // Observation noise covariance matrix, representing the noise in the measurements.
    private SimpleMatrix Rk;
    // Estimate error covariance matrix, quantifying the estimated accuracy of the state estimates.
    private SimpleMatrix Pk;
    // State estimate vector, representing the estimated state of the system at each time step.
    private SimpleMatrix Xk;
    // Array storing the last opportunistic update values, could be used for adaptive measurements or corrections.
    private double[] lastOpportunisticUpdate;
    // Timestamp of the last opportunistic update, used to handle timing and delays in updates.
    private long lastOpUpdateTime;
    // Timestamp marking the initialization of the filtering process or algorithm.
    private long initialiseTime;
    // Boolean flag to indicate whether WiFi measurements are currently being used.
    private boolean usingWifi;
    // Boolean flag to control the stopping of the Extended Kalman Filter (EKF) process.
    private boolean stopEKF;
    // Boolean flag to decide whether to use the current measurement in the update step.
    private boolean useThisMeasurement;
    // Stores the length of the previous step, used in mobility or trajectory tracking.
    private double prevStepLength;
    // Handler thread dedicated to running the Extended Kalman Filter computations to offload the main thread.
    private HandlerThread ekfThread;
    // Handler to manage tasks and messages for the EKF computations within its dedicated thread.
    private Handler ekfHandler;
    // Component for detecting outliers in the measurement updates, possibly for quality control or error minimization.
    private OutlierDetector outlierDetector;
    // Filter for applying exponential smoothing to the measurements or the estimates, used to reduce noise and fluctuations.
    private ExponentialSmoothingFilter smoothingFilter;

    /**
     * Constructor for the ExtendedKalmanFilter class.
     * This method initializes components used in the Kalman filtering process, sets up initial state and covariance matrices,
     * and starts a background handler thread for processing in FIFO queue to ensure the steps are processed sequentially.
     */
    public ExtendedKalmanFilter() {
        // Initialize the component responsible for detecting outliers in the measurement updates.
        this.outlierDetector = new OutlierDetector();

        // Initialize the smoothing filter with a specified smoothing factor and dimensionality of 2.
        this.smoothingFilter = new ExponentialSmoothingFilter(smoothingFactor, 2);

        // A flag to control the stopping of the EKF; initially set to continue running.
        this.stopEKF = false;

        // Initialize the state estimate matrix (Xk) with zero values. This is a 3x1 matrix, typically representing
        // initial guesses for the state variables (bearing, East, North).
        this.Xk = new SimpleMatrix(new double[][]{{0}, {0}, {0}});

        // Initialize the error covariance matrix (Pk) with zeros on the diagonal, indicating no initial certainty in the estimates.
        this.Pk = SimpleMatrix.diag(1000, 100, 10);


        // Initialize the process noise covariance matrix (Qk) based on the variances of process noises (theta and displacement).
        this.Qk = SimpleMatrix.diag((sigma_dTheta * sigma_dTheta), sigma_ds);

        // Initialize the measurement noise covariance matrix (Rk) based on the squared standard deviations of measurement noises.
        this.Rk = SimpleMatrix.diag((sigma_east_meas * sigma_east_meas), (sigma_north_meas * sigma_north_meas));

        // Initialize the observation matrix (Hk). This matrix maps the state space into the observed space.
        // It can vary depending on what aspects of the state are directly observed.
        this.Hk = new SimpleMatrix(new double[][]{{0, 1, 0}, {0, 0, 1}});

        // Record the system time at initialization to manage timing and delays in updates.
        this.initialiseTime = android.os.SystemClock.uptimeMillis();
        this.lastOpUpdateTime = 0;  // Initialize the last opportunistic update time.
        this.prevStepLength = defaultStepLength;  // Set the previous step length to the default.
        this.usingWifi = false;  // Initially not using WiFi measurements.

        // Call the helper method to initialize background processing.
        initialiseBackgroundHandler();
    }

    /**
     * Initializes a background thread and handler for the Extended Kalman Filter processing.
     * This allows the EKF computations to be handled asynchronously, offloading the main thread.
     */
    private void initialiseBackgroundHandler() {
        // Create and start a new thread for handling the Extended Kalman Filter operations.
        ekfThread = new HandlerThread("EKFProcessingThread");
        ekfThread.start();

        // Create a handler that binds to the newly created thread's looper, to manage tasks and messages for the EKF.
        ekfHandler = new Handler(ekfThread.getLooper());
    }

    /**
     * Updates the state transition matrix Fk for the Extended Kalman Filter based on the current orientation and movement step.
     * This matrix is crucial for predicting the next state of the system.
     *
     * @param theta_k The current orientation angle in radians, where 0 radians point east.
     * @param step_k The length of the current movement step, likely in meters.
     */
    private void updateFk(double theta_k, double step_k){

        // Compute the cosine of theta_k, which represents the change in the eastward position component
        // due to the step in the direction of theta_k.
        double cosTheta = Math.cos(theta_k);

        // Compute the sine of theta_k, which represents the change in the northward position component
        // due to the step in the direction of theta_k.
        double sinTheta = Math.sin(theta_k);

        // Update the state transition matrix Fk based on the current values of theta_k and step_k.
        // This matrix predicts the next state based on the current state and the system's dynamics.
        this.Fk = new SimpleMatrix(new double[][]{
                {1, 0, 0},                           // First row: state remains unchanged.
                {step_k * cosTheta, 1, 0},           // Second row: updates x position based on step length and cosine of orientation.
                {-step_k * sinTheta, 0, 1}           // Third row: updates y position based on step length and negative sine of orientation.
        });
    }

    /**
     * Updates the process noise covariance matrix Qk based on the given step length, orientation,
     * reference time, and the standard deviation of the orientation. This matrix quantifies the uncertainty
     * in the model's predictions of the state at the next time step.
     *
     * @param averageStepLength The average length of a movement step, typically in meters.
     * @param theta_k The current orientation angle in radians, where 0 radians points east.
     * @param refTime The reference time, typically the time of the last significant update or event, in milliseconds.
     * @param thetaStd The standard deviation of the orientation angle, in radians.
     */
    private void updateQk(double averageStepLength, double theta_k, long refTime, double thetaStd){
        // Calculate a penalty factor based on the elapsed time since the last reference time.
        // This factor adjusts the perceived error in the model based on how long ago the last known good measurement occurred.
        double penaltyFactor = calculateTimePenalty(refTime);

        // Calculate the error in the step measurement, incorporating both a percentage error of the average step length
        // and a misdirection error, all scaled by the time penalty factor. This captures the uncertainty in step length due to
        // various factors like stride variability and potential misalignment.
        double step_error = (stepPercentageError * averageStepLength + stepMisdirection) * penaltyFactor;

        // Calculate the bearing error penalty, which accounts for the variability in the orientation,
        // adjusted by the time penalty. This reflects uncertainty in the direction of movement.
        double bearing_error = calculateBearingPenalty(thetaStd, refTime);

        // Set the diagonal elements of the process noise covariance matrix Qk.
        // Element (0, 0) represents the variance of the bearing error squared, indicating the uncertainty in orientation.
        this.Qk.set(0, 0, bearing_error * bearing_error);

        // Element (1, 1) represents the variance of the step error squared, indicating the uncertainty in the step length.
        this.Qk.set(1, 1, step_error * step_error);
    }

    /**
     * Updates the observation noise covariance matrix Rk based on the current sensing method (WiFi or GNSS)
     * and a penalty factor that accounts for increasing uncertainty in the PDR measurements the longer we go without receiving wifi.
     *
     * @param penaltyFactor A factor that modifies the standard deviation of the noise based on external conditions,
     *                      based on the time since receiving the last Wifi or GNSS update.
     */
    private void updateRk(double penaltyFactor){
        // Check if WiFi-based location measurements are being used.
        if (this.usingWifi){

            // Set both diagonal elements of the matrix for WiFi measurements.
            // These elements represent the variance of the observation noise, scaled by the penalty factor,
            // reflecting increased or decreased confidence in sensor readings.
            this.Rk.set(0, 0, (wifi_std * wifi_std) * penaltyFactor);
            this.Rk.set(1, 1, (wifi_std * wifi_std) * penaltyFactor);
        } else {
            this.Rk.set(0, 0, (gnss_std * gnss_std) * penaltyFactor);
            this.Rk.set(1, 1, (gnss_std * gnss_std) * penaltyFactor);
        }
    }

    private void resetFilter() {
        Log.w("EKF", "⚠️ EKF 状态异常，正在重置滤波器...");
        this.Xk = new SimpleMatrix(new double[][]{{0}, {0}, {0}});
        this.Pk = SimpleMatrix.diag(1000, 100, 10);
    }

    private boolean hasInvalidState() {
        for (int i = 0; i < Xk.getNumElements(); i++) {
            double val = Xk.get(i);
            if (Double.isNaN(val) || Double.isInfinite(val)) {
                Log.e("EKF-Monitor", "❗️ Xk state invalid: " + val);
                return true;
            }
        }
        for (int i = 0; i < Pk.getNumElements(); i++) {
            double val = Pk.get(i);
            if (Double.isNaN(val) || Double.isInfinite(val)) {
                Log.e("EKF-Monitor", "❗️ Pk state invalid: " + val);
                return true;
            }
        }
        return false;
    }

    /**
     * Performs a prediction step within the Extended Kalman Filter to estimate the system's future state.
     * This involves updating the state estimate and error covariance based on the movement and orientation data.
     *
     * @param theta_k The current orientation angle in radians, where 0 radians points east.
     * @param step_k The length of the current movement step, likely in meters.
     * @param averageStepLength The average length of a movement step, used for updating process noise covariance.
     * @param refTime The reference time for the current prediction, typically the time of the last update.
     * @param userMovementInStep The type of movement detected (e.g., walking, running), affecting the state prediction.
     */
    // 在 predict() 结束后，也加入 condition number 监控
    public void predict(double theta_k, double step_k, double averageStepLength, long refTime, TurnDetector.MovementType userMovementInStep) {
        if (stopEKF) return;

        ekfHandler.post(() -> {
            Log.d("EKF", "======== PREDICT ========");

            double adaptedHeading = wrapToPi((Math.PI / 2 - theta_k));
            Log.d("EKF", "Adapted bearing " + (Math.PI / 2 - theta_k) + " wrapped bearing " + adaptedHeading);

            SimpleMatrix T_mat = new SimpleMatrix(new double[][]{
                    {1, 0},
                    {0, Math.sin(theta_k)},
                    {0, Math.cos(theta_k)}
            });

            SimpleMatrix control_inputs = new SimpleMatrix(new double[][]{
                    {theta_k},
                    {prevStepLength}
            });

            SimpleMatrix add = T_mat.mult(control_inputs);

            Xk.set(0, 0, Xk.get(0, 0) + add.get(0, 0));
            Xk.set(1, 0, Xk.get(1, 0) + add.get(1, 0));
            Xk.set(2, 0, Xk.get(2, 0) + add.get(2, 0));

            updateFk(adaptedHeading, prevStepLength);
            updateQk(averageStepLength, adaptedHeading, (refTime - initialiseTime), getThetaStd(userMovementInStep));

            SimpleMatrix L_k = new SimpleMatrix(new double[][]{
                    {1, 0},
                    {0, Math.sin(theta_k)},
                    {0, Math.cos(theta_k)}
            });

            Pk = Fk.mult(Pk).mult(Fk.transpose()).plus(L_k.mult(Qk).mult(L_k.transpose()));
            prevStepLength = step_k;

            try {
                double pkCondition = Pk.conditionP2();
                double pkMinEig = Pk.svd().getW().get(Pk.numRows() - 1, Pk.numCols() - 1);
                Log.d("EKF-Monitor", "Post-Predict Pk condition number: " + pkCondition);
                Log.d("EKF-Monitor", "Post-Predict Pk min singular value: " + pkMinEig);

                if (pkCondition > 1e12 || pkMinEig < 1e-10 || hasInvalidState()) {
                    Log.w("EKF-Monitor", "⚠️ Pk数值异常 (Predict)，condition=" + pkCondition + ", minEig=" + pkMinEig + " [AUTO-RECOVERED]");
                    resetFilter();
                }
            } catch (Exception e) {
                Log.e("EKF-Monitor", "❗️ Pk SVD 失败 (Predict) → 自动重置", e);
                resetFilter();
            }
        });
    }

    /**
     * Updates the state estimate using new observations. This method applies the correction step of the
     * Extended Kalman Filter based on the observed data and a penalty factor that adjusts observation noise.
     *
     * @param observation_k An array containing the new observations.
     * @param penaltyFactor A factor used to modify the observation noise covariance matrix based on external conditions.
     */
    public void update(double[] observation_k, double penaltyFactor) {
        SimpleMatrix Mk = SimpleMatrix.identity(2);
        SimpleMatrix Zk = new SimpleMatrix(new double[][]{{observation_k[0]}, {observation_k[1]}});

        updateRk(penaltyFactor);

        SimpleMatrix y_pred = Zk.minus(Hk.mult(Xk));
        SimpleMatrix Sk = Hk.mult(Pk).mult(Hk.transpose()).plus(Mk.mult(Rk).mult(Mk.transpose()));
        Sk = Sk.plus(SimpleMatrix.identity(Sk.numRows()).scale(1e-6));

        SimpleMatrix KalmanGain;
        try {
            KalmanGain = Pk.mult(Hk.transpose().mult(Sk.pseudoInverse()));
        } catch (Exception e) {
            Log.e("EKF", "伪逆失败，重置滤波器避免崩溃", e);
            resetFilter();
            return;
        }

        Xk = Xk.plus(KalmanGain.mult(y_pred));
        Xk.set(0, 0, wrapToPi(Xk.get(0, 0)));

        SimpleMatrix I = SimpleMatrix.identity(Pk.getNumRows());
        Pk = (I.minus(KalmanGain.mult(Hk))).mult(Pk);

        try {
            double pkCondition = Pk.conditionP2();
            double pkMinEig = Pk.svd().getW().get(Pk.numRows() - 1, Pk.numCols() - 1);
            Log.d("EKF-Monitor", "Post-Update Pk condition number: " + pkCondition);
            Log.d("EKF-Monitor", "Post-Update Pk min singular value: " + pkMinEig);

            if (pkCondition > 1e12 || pkMinEig < 1e-10 || hasInvalidState()) {
                Log.w("EKF-Monitor", "⚠️ Pk数值异常 (Update)，condition=" + pkCondition + ", minEig=" + pkMinEig + " [AUTO-RECOVERED]");
                resetFilter();
            }
        } catch (Exception e) {
            Log.e("EKF-Monitor", "❗️ Pk SVD 失败 (Update) → 自动重置", e);
            resetFilter();
        }
    }



/**
     * Handles opportunistic updates to the state estimation process when new observations are available at unexpected times.
     * This method allows the EKF to utilize additional data points that may improve the accuracy of the state estimate.
     *
     * @param observe Array containing new observation data, typically positional coordinates like east and north.
     * @param refTime Reference time when the observation was made, used to timestamp this update.
     */
    public void onOpportunisticUpdate(double[] observe, long refTime){
        Log.d("EKF", "✅ Opportunistic Update triggered with ENU = " + Arrays.toString(observe) + ", timestamp = " + refTime);

        // Check if the EKF is set to stop and return immediately if true.
        if (stopEKF) return;

        // Delegate the processing of the update to the handler thread to maintain responsiveness of the main application.
        ekfHandler.post(new Runnable() {
            @Override
            public void run() {

                // Decide whether to use this measurement based on whether the new observation differs from the last.
                // If it's the same as the last, it might not provide any new information, so it could be skipped.
                useThisMeasurement = (lastOpportunisticUpdate != null) && (lastOpportunisticUpdate[0] != observe[0] || lastOpportunisticUpdate[1] != observe[1]);

                // Store the current observations as the last opportunistic update for future reference.
                lastOpportunisticUpdate = observe;
                lastOpUpdateTime = (refTime - initialiseTime);
            }
        });
    }

    /**
     * Processes a detected step and updates the Extended Kalman Filter based on new pedestrian movement data.
     * This method ensures that each detected step contributes to the ongoing estimation of the system's state,
     * taking into account its relevance and potential as an outlier.
     *
     * @param pdrEast The eastward displacement detected by PDR since the last update.
     * @param pdrNorth The northward displacement detected by PDR since the last update.
     * @param altitude The altitude level recorded with the step detection, which may affect some correction calculations.
     * @param refTime The reference time when the step was detected, used for timing and relevance checks.
     */
    public void onStepDetected(double pdrEast, double pdrNorth, double altitude, long refTime){
        // Return immediately if the EKF has been flagged to stop, avoiding unnecessary computations.
        if (stopEKF) return;

        // Use a handler to post the step processing to a background thread, ensuring that the UI remains responsive.
        ekfHandler.post(new Runnable() {
            @Override
            public void run() {

                // Check if there's a valid last opportunistic update, if it should be used, and if it is still relevant based on timing.
                if (lastOpportunisticUpdate != null && useThisMeasurement && checkRelevance((refTime - initialiseTime))) {
                    // Calculate the Euclidean distance between the last opportunistic update and the current PDR data.
                    double distanceBetween = Math.sqrt(Math.pow(lastOpportunisticUpdate[0] - pdrEast, 2) + Math.pow(lastOpportunisticUpdate[1] - pdrNorth, 2));

                    // Use the outlier detector to determine if the new step data significantly deviates from expected patterns.
                    if (!outlierDetector.detectOutliers(distanceBetween)) {
                        // If no outliers are detected and the data is deemed relevant, update the observations accordingly.
                        onObservationUpdate(lastOpportunisticUpdate[0], lastOpportunisticUpdate[1], pdrEast, pdrNorth, altitude, 1);
                        useThisMeasurement = false;
                        return;
                    }
                }

                // If the conditions for a direct update are not met, perform a recursive correction to refine the state estimate.
                performRecursiveCorrection(pdrEast, pdrNorth, altitude, calculateTimePenalty((refTime - initialiseTime)));
            }
        });
    }

    /**
     * Checks the relevance of the data based on the time difference between the current reference time
     * and the time of the last opportunistic update. This method helps in deciding whether to use the
     * previous data for further updates or calculations, particularly when using WiFi for location tracking,
     * where data quickly becomes outdated.
     *
     * @param refTime The current reference time, typically when a new measurement or observation is made.
     * @return true if the data is still relevant, false otherwise.
     */
    private boolean checkRelevance(long refTime){
        // If WiFi is not being used, assume that the data is always relevant.
        // This might be under the assumption that other sensors or systems provide more consistent or reliable data.
        if (!usingWifi) return true;

        // Calculate the absolute time difference between the current reference time and the last opportunistic update time.
        long timeDifference = Math.abs(refTime - lastOpUpdateTime);

        // Check if the time difference is within the predefined relevance threshold.
        // The relevance threshold is a domain-specific value that determines how quickly the data becomes outdated.
        if (timeDifference <= relevanceThreshold) return true;

        return false;
    }

    /**
     * Calculates a time-based penalty factor that scales linearly with the elapsed time since the last
     * opportunistic update (such as a WiFi update). The penalty is intended to degrade the influence
     * of older data on the system's estimations, reflecting decreased confidence in outdated information.
     *
     * @param currentTime The current system time, typically obtained at the point of calculation.
     * @return A penalty factor that increases with time since the last update, influencing how data is weighted.
     */
    private double calculateTimePenalty(long currentTime) {
        // Calculate the elapsed time since the last opportunistic update, using the difference between the current time
        // and the time of the last update.
        long elapsedTime = currentTime - lastOpUpdateTime;

        // Maximum elapsed time that is considered for the maximum penalty factor. This is a design choice and can be
        // adjusted based on empirical data or domain-specific requirements. Here, it's set to trigger a maximum penalty.
        // The example assumes a design where the penalty factor can go up to 1.5 as the elapsed time reaches or exceeds
        // the maxElapsedTimeForMaxPenalty threshold.
        double penaltyFactor = 1.0 + 0.5 * Math.min(elapsedTime, maxElapsedTimeForMaxPenalty) / maxElapsedTimeForMaxPenalty;


        // Return the penalty factor. The calculation ensures that it scales linearly from 1.0 to a maximum of 1.5
        // as the elapsed time approaches the maxElapsedTimeForMaxPenalty. This scaling directly impacts how subsequent
        // updates and corrections in the filter are weighted, with older data receiving a heavier penalty, thus reducing
        // its influence.
        return penaltyFactor;
    }

    /**
     * Calculates a penalty factor for the bearing based on the standard deviation and the elapsed time.
     * This penalty is intended to adjust the bearing noise model in the Kalman filter, reflecting increased
     * uncertainty over time due to potential drifts or other factors affecting the bearing accuracy.
     *
     * @param thetaStd The standard deviation of the bearing measurement under normal conditions, in radians.
     * @param elapsedTime The elapsed time since the last update or measurement, in milliseconds.
     * @return The adjusted bearing penalty, in radians.
     */
    private double calculateBearingPenalty(double thetaStd, long elapsedTime) {
        // Convert the elapsed time from milliseconds to minutes to match the scale expected by the model.
        double elapsedTimeMinutes = elapsedTime / 60000.0;

        // Calculate the fraction of the maximum elapsed time (for bearing penalty) that has passed,
        // capping it at 15. This fraction indicates how close the elapsed time is to reaching the
        // maximum time expected to apply the full penalty.
        double elapsedTimeFraction = Math.min(elapsedTimeMinutes, maxElapsedTimeForBearingPenalty) / maxElapsedTimeForBearingPenalty;

        // Calculate the penalty by interpolating between the standard deviation (thetaStd)
        // and the maximum bearing penalty (maxBearingPenalty) based on the elapsed time fraction.
        // This creates a linear scale where the penalty increases as time progresses.
        double penalty = thetaStd + (maxBearingPenalty - thetaStd) * elapsedTimeFraction;

        // Return the calculated penalty in radians.
        return penalty;
    }

    /**
     * Handles observation updates in the Extended Kalman Filter by incorporating observed data from external sensors
     * and comparing it against the pedestrian dead reckoning (PDR) data. The method applies penalties, updates the state,
     * and transforms the updated state to geodetic coordinates where they can be displayed on the map.
     *
     * @param observeEast The eastward component of the observed location.
     * @param observeNorth The northward component of the observed location.
     * @param pdrEast The eastward component estimated by the PDR system.
     * @param pdrNorth The northward component estimated by the PDR system.
     * @param altitude The altitude, typically used for geodetic transformations.
     * @param penaltyFactor A factor applied to the observation noise covariance matrix to account for varying confidence levels.
     */
    public void onObservationUpdate(double observeEast, double observeNorth, double pdrEast, double pdrNorth,
                                    double altitude, double penaltyFactor){
        // If the EKF is stopped, no further processing is done.
        if (stopEKF) return;

        // Post the execution to a handler to ensure the main UI thread remains responsive.
        ekfHandler.post(new Runnable() {
            @Override
            public void run() {
                // Calculate the discrepancy between the observed and PDR data.
                double[] observation = new double[] {(pdrEast - observeEast), (pdrNorth - observeNorth)};

                // Update the EKF with the new observation and the penalty factor.
                update(observation, penaltyFactor);

                // Retrieve the start position and reference ECEF coordinates from a singleton instance of SensorFusion.
                double[] startPosition = SensorFusion.getInstance().getGNSSLatLngAlt(true);
                double[] ecefRefCoords = SensorFusion.getInstance().getEcefRefCoords();

                // Apply a smoothing filter to the updated coordinates.
                double[] smoothedCoords = smoothingFilter.applySmoothing(new double[]{Xk.get(1, 0), Xk.get(2, 0)});

                // Notify the SensorFusion instance to update its fused location based on the smoothed EKF output.
                SensorFusion.getInstance().notifyFusedUpdate(
                        CoordinateTransform.enuToGeodetic(smoothedCoords[0], smoothedCoords[1],
                                altitude, startPosition[0], startPosition[1], ecefRefCoords)
                );
            }
        });
    }

    /**
     * Performs recursive corrections on the Kalman filter's state estimates based on new PDR data. This method helps
     * refine the state estimations by continuously incorporating new sensor readings, thereby improving the accuracy
     * and reliability of the estimated positions. It is used when WiFi or GNSS is not available at the given step
     *
     * @param pdrEast The eastward component from the latest PDR reading.
     * @param pdrNorth The northward component from the latest PDR reading.
     * @param altitude The altitude, typically necessary for transformations or modeling.
     * @param penaltyFactor A factor that adjusts the influence of observation noise based on various criteria.
     */
    public void performRecursiveCorrection(double pdrEast, double pdrNorth, double altitude, double penaltyFactor){
        // Stop further execution if the filter is flagged to stop.
        if (stopEKF) return;

        // Post the recursive correction process to a handler to run asynchronously, preventing UI blocking.
        ekfHandler.post(new Runnable() {
            @Override
            public void run() {

                // Retrieve the current predicted positions from the state estimate.
                double predictedEast = Xk.get(1, 0);
                double predictedNorth = Xk.get(2, 0);

                // Calculate the difference between the observed (PDR) and predicted positions.
                double[] observation = new double[] {(pdrEast - predictedEast), (pdrNorth - predictedNorth)};

                // Perform an update with the observation vector and the current penalty factor.
                update(observation, penaltyFactor);

                // Retrieve starting position and reference coordinates from the SensorFusion instance.
                double[] startPosition = SensorFusion.getInstance().getGNSSLatLngAlt(true);
                double[] ecefRefCoords = SensorFusion.getInstance().getEcefRefCoords();

                // Apply a smoothing filter to the new estimated coordinates for output stabilization.
                double[] smoothedCoords = smoothingFilter.applySmoothing(new double[]{Xk.get(1, 0), Xk.get(2, 0)});

                // Update the external system (e.g., a mapping interface) with the new smoothed geodetic coordinates.
                SensorFusion.getInstance().notifyFusedUpdate(
                        CoordinateTransform.enuToGeodetic(smoothedCoords[0], smoothedCoords[1],
                                altitude,
                                startPosition[0], startPosition[1], ecefRefCoords)
                );
            }
        });
    }

    /**
     * Returns the standard deviation of the orientation change (theta) based on the detected movement type.
     * This differentiation helps to dynamically adjust the estimation accuracy based on the character of movement.
     *
     * @param userMovement The type of movement detected by the system.
     * @return A standard deviation value corresponding to the type of movement.
     */
    private double getThetaStd(TurnDetector.MovementType userMovement){
        switch (userMovement){
            case TURN: // For a full turn, use a higher standard deviation reflecting higher uncertainty.
                return sigma_dTheta;
            case PSEUDO_TURN: // For a pseudo-turn, use a moderate standard deviation.
                return sigma_dPseudo;
            case STRAIGHT: // For straight movement, use a low standard deviation, indicating more certainty.
                return sigma_dStraight;
            default: // Default to the higher uncertainty for unspecified types.
                return sigma_dTheta;
        }
    }

    /**
     * Wraps an angle in radians to the range from -π to π. This is useful for ensuring that
     * angular measurements remain within a standard range, simplifying calculations that involve angles.
     *
     * @param x The angle in radians to be normalized.
     * @return The angle wrapped to the range [-π, π].
     */
    private static double wrapToPi(double x) {
        double bearing = x % (2 * Math.PI); // Normalize angle to range [0, 2π]

        if (bearing < -Math.PI) {
            bearing += 2 * Math.PI; // Adjust if the angle is less than -π to fit into the range [-π, π]
        } else if (bearing > Math.PI) {
            bearing -= 2 * Math.PI; // Adjust if the angle is more than π to fit into the range [-π, π]
        }
        return bearing;
    }

    /**
     * Sets whether the system should use WiFi for location updates. This setting can affect how data is processed
     * and how updates are handled, potentially prioritizing WiFi data when available.
     *
     * @param update Boolean indicating whether to use WiFi (true) or not (false).
     */
    public void setUsingWifi(boolean update) {
        if (this.stopEKF) return; // Exit if the EKF is stopped.

        ekfHandler.post(new Runnable() {
            @Override
            public void run() {
                usingWifi = update; // Update the flag that controls WiFi usage.
            }
        });
    }

    /**
     * Stops all operations related to the Extended Kalman Filter, cleans up resources, and logs the action.
     * This method should be called when the system no longer needs to perform state estimations or when it is being shut down.
     */
    public void stopFusion(){
        this.stopEKF = true; // Set the flag to stop the EKF.
        Log.d("EKF:", "Stopping EKF handler"); // Log the stopping action for debugging.
        this.smoothingFilter.reset(); // Reset the smoothing filter to clear any retained state.
        ekfThread.quitSafely(); // Safely quit the handler thread, ensuring all messages are processed before closure.
    }

    public void setInitialPosition(double east, double north) {
        this.Xk.set(1, 0, east);
        this.Xk.set(2, 0, north);
    }
    public double[] getCurrentState() {
        return new double[] { Xk.get(0,0), Xk.get(1,0), Xk.get(2,0) };
    }

}
