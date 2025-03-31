package com.openpositioning.PositionMe.utils;

//Code written by Marco Bancalari-Ruiz - s2074492

import android.location.Location;
import androidx.annotation.NonNull;
import java.util.Arrays;
import java.util.function.Function;

public class ExtendedKalmanFilter {

    // Define state vector: [x, y, heading, velocity]
    private double[] state; // [x (meters), y (meters), heading (radians), velocity (m/s)]
    private double[][] covariance; // State covariance matrix
    private double processNoiseQ; // Process noise covariance
    private double measurementNoiseR; // Measurement noise covariance

    // Initial state, covariance and noise values can be configured during construction.
    public ExtendedKalmanFilter(double initialX, double initialY, double initialHeading, double initialVelocity,
                              double initialCovarianceValue, double processNoiseValue, double measurementNoiseValue) {
        // Initialize state vector
        this.state = new double[]{initialX, initialY, initialHeading, initialVelocity};

        // Initialize covariance matrix
        this.covariance = new double[4][4];
        for (int i = 0; i < 4; i++) {
            covariance[i][i] = initialCovarianceValue; // Initial uncertainty
        }

        // Initialize noise values
        this.processNoiseQ = processNoiseValue;
        this.measurementNoiseR = measurementNoiseValue;
    }

    // Define the state transition function (motion model)
    // Predict next state based on current state and time interval
    private double[] stateTransitionFunction(double[] state, double dt) {
        double x = state[0];
        double y = state[1];
        double heading = state[2];
        double velocity = state[3];

        // Update position based on velocity, heading and time interval
        double newX = x + velocity * dt * Math.cos(heading);
        double newY = y + velocity * dt * Math.sin(heading);

        // Assuming constant velocity and heading for simplicity
        return new double[]{newX, newY, heading, velocity};
    }

    //Linear Motion Model
    private double[][] createStateTransitionMatrix(double dt) {
        double[][] F = new double[4][4];
        // Position update model (x = x_0 + v*dt*cos(theta))
        F[0][0] = 1; // No change to previous x
        F[0][3] = dt;   // Add change to previous x based on velocity and dt
        // Position update model (y = y_0 + v*dt*sin(theta))
        F[1][1] = 1;
        F[1][3] = dt;   // Add change to previous y based on velocity and dt
        // No change in orientation
        F[2][2] = 1;
        // No change in velocity
        F[3][3] = 1;

        return F;
    }

    // Apply PDR data to update state estimate
    public void applyPDRData(double deltaX, double deltaY, double dt) {
        // Prediction Step
        predict(dt);

        // Correct state and covariance matrix based on PDR data.
        // Update position and velocity estimates based on PDR data.
        // For simplicity, let's assume PDR directly provides deltaX and deltaY.
        double updatedX = state[0] + deltaX;
        double updatedY = state[1] + deltaY;

        // Assuming PDR provides some information about velocity, adjust it as well
        double updatedVelocity = state[3]; // + some function of deltaX, deltaY, and dt

        state[0] = updatedX;
        state[1] = updatedY;
        state[3] = updatedVelocity;

        // Recalculate state transition matrix
        double[][] F = createStateTransitionMatrix(dt);

        // Update covariance matrix
        covariance = matrixAdd(matrixMultiply(F, matrixMultiply(covariance, transpose(F))), createProcessNoiseMatrix());
    }

    // WiFi measurement function: returns predicted latitude and longitude
    private double[] measurementFunction(double[] state) {
        // Converts estimated x, y (meters) to estimated latitude, longitude.
        // Need to incorporate the initial GPS coordinates.

        double x = state[0];
        double y = state[1];

        // Assuming initial latitude and longitude are stored elsewhere.
        double initialLatitude = 0; // Missing: Initial latitude from first GPS fix
        double initialLongitude = 0; // Missing: Initial longitude from first GPS fix

        // Earth’s radius in meters
        double earthRadius = 6371000;

        // Calculate latitude offsets
        double latOffset = (x / earthRadius) * (180 / Math.PI);

        // Calculate longitude offsets
        double lonOffset = (y / (earthRadius * Math.cos(Math.PI * initialLatitude / 180))) * (180 / Math.PI);

        double predictedLatitude = initialLatitude + latOffset;
        double predictedLongitude = initialLongitude + lonOffset;

        return new double[] {predictedLatitude, predictedLongitude};
    }

    // Compute the Jacobian of the measurement function
    private double[][] createMeasurementMatrix() {
        double[][] H = new double[2][4];

        double initialLatitude = 0; // Missing: Initial latitude from first GPS fix

        // Earth’s radius in meters
        double earthRadius = 6371000;

        // Elements of the Jacobian matrix for latitude
        H[0][0] = 1 / earthRadius * (180 / Math.PI); // Derivative of latitude with respect to x
        H[0][1] = 0; // Derivative of latitude with respect to y
        H[0][2] = 0; // Derivative of latitude with respect to heading
        H[0][3] = 0; // Derivative of latitude with respect to velocity

        // Elements of the Jacobian matrix for longitude
        H[1][0] = 0; // Derivative of longitude with respect to x
        H[1][1] = 1 / (earthRadius * Math.cos(Math.PI * initialLatitude / 180)) * (180 / Math.PI);
        H[1][2] = 0; // Derivative of longitude with respect to heading
        H[1][3] = 0; // Derivative of longitude with respect to velocity

        return H;
    }

    //Update the state estimate using the new Wifi data
    public void updateWithWifiData(double wifiLatitude, double wifiLongitude) {

        // Step 1: Predict the measurement based on the current state estimate.
        double[] predictedMeasurement = measurementFunction(state);

        // Step 2: Compute the measurement Jacobian.
        double[][] H = createMeasurementMatrix();

        // Step 3: Compute the innovation (measurement residual).
        double[] innovation = vectorSubtract(new double[]{wifiLatitude, wifiLongitude}, predictedMeasurement);

        // Step 4: Compute the innovation covariance matrix.
        double[][] S = matrixAdd(matrixMultiply(H, matrixMultiply(covariance, transpose(H))), createMeasurementNoiseMatrix());

        // Step 5: Compute the Kalman gain.
        double[][] K = matrixMultiply(covariance, matrixMultiply(transpose(H), inverse(S)));

        // Step 6: Update the state estimate.
        state = vectorAdd(state, matrixVectorMultiply(K, innovation));

        // Step 7: Update the error covariance matrix.
        covariance = matrixMultiply(matrixSubtract(createIdentityMatrix(), matrixMultiply(K, H)), covariance);
    }

    // Create process noise matrix
    private double[][] createProcessNoiseMatrix() {
        // Missing: Adjust the values based on system characteristics.
        double[][] Q = new double[4][4];

        Q[0][0] = processNoiseQ; // Process noise for X
        Q[1][1] = processNoiseQ; // Process noise for Y
        Q[2][2] = processNoiseQ; // Process noise for heading
        Q[3][3] = processNoiseQ; // Process noise for velocity
        return Q;
    }

    // Create measurement noise matrix
    private double[][] createMeasurementNoiseMatrix() {
        // Measurement noise matrix represents the uncertainty in WiFi measurements
        double[][] R = new double[2][2];
        R[0][0] = measurementNoiseR; // Variance for latitude measurement
        R[1][1] = measurementNoiseR; // Variance for longitude measurement
        return R;
    }

    // Prediction step for Kalman Filter
    public void predict(double dt) {
        // Linear state transition function with noise
        double[][] F = createStateTransitionMatrix(dt);

        state = stateTransitionFunction(state, dt);

        // Update covariance matrix
        covariance = matrixAdd(matrixMultiply(F, matrixMultiply(covariance, transpose(F))), createProcessNoiseMatrix());
    }

    public double[] getState() {
        return state;
    }

    // Helper functions for matrix operations
    private double[][] matrixAdd(double[][] a, double[][] b) {
        int rows = a.length;
        int cols = a[0].length;
        double[][] result = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                result[i][j] = a[i][j] + b[i][j];
            }
        }
        return result;
    }

    private double[][] matrixSubtract(double[][] a, double[][] b) {
        int rows = a.length;
        int cols = a[0].length;
        double[][] result = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                result[i][j] = a[i][j] - b[i][j];
            }
        }
        return result;
    }

    // Function to compute matrix multiplication
    private double[][] matrixMultiply(double[][] a, double[][] b) {
        int rowsA = a.length;
        int colsA = a[0].length;
        int colsB = b[0].length;
        double[][] result = new double[rowsA][colsB];
        for (int i = 0; i < rowsA; i++) {
            for (int j = 0; j < colsB; j++) {
                for (int k = 0; k < colsA; k++) {
                    result[i][j] += a[i][k] * b[k][j];
                }
            }
        }
        return result;
    }

    // Function to perform matrix-vector multiplication
    private double[] matrixVectorMultiply(double[][] a, double[] b) {
        int rowsA = a.length;
        int colsA = a[0].length;
        double[] result = new double[rowsA];
        for (int i = 0; i < rowsA; i++) {
            for (int j = 0; j < colsA; j++) {
                result[i] += a[i][j] * b[j];
            }
        }
        return result;
    }

    // Function to perform vector subtraction
    private double[] vectorSubtract(double[] a, double[] b) {
        int n = a.length;
        double[] result = new double[n];
        for (int i = 0; i < n; i++) {
            result[i] = a[i] - b[i];
        }
        return result;
    }

    // Function to perform vector addition
    private double[] vectorAdd(double[] a, double[] b) {
        int n = a.length;
        double[] result = new double[n];
        for (int i = 0; i < n; i++) {
            result[i] = a[i] + b[i];
        }
        return result;
    }

    // Function to compute the inverse of a 2x2 matrix
    private double[][] inverse(double[][] matrix) {
        double a = matrix[0][0];
        double b = matrix[0][1];
        double c = matrix[1][0];
        double d = matrix[1][1];
        double determinant = a * d - b * c;
        double[][] invMatrix = new double[2][2];
        invMatrix[0][0] = d / determinant;
        invMatrix[0][1] = -b / determinant;
        invMatrix[1][0] = -c / determinant;
        invMatrix[1][1] = a / determinant;
        return invMatrix;
    }

    // Function to compute the transpose of a matrix
    private double[][] transpose(double[][] matrix) {
        int rows = matrix.length;
        int cols = matrix[0].length;
        double[][] transposedMatrix = new double[cols][rows];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                transposedMatrix[j][i] = matrix[i][j];
            }
        }
        return transposedMatrix;
    }

    // Create identity matrix
    private double[][] createIdentityMatrix() {
        double[][] I = new double[4][4];
        for (int i = 0; i < 4; i++) {
            I[i][i] = 1;
        }
        return I;
    }

}
