package com.openpositioning.PositionMe.FusionFilter;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.PdrProcessing;
import com.openpositioning.PositionMe.sensors.GNSSDataProcessor;
import com.openpositioning.PositionMe.sensors.WiFiPositioning;

/**
 * Extended Kalman Filter (EKF) implementation for multi-sensor fusion positioning
 * Fuses WiFi, GNSS and PDR data to achieve more accurate position estimation
 */
public class EKFFilter {
    private static final String TAG = "EKFFilter";

    // Earth-related constants
    private static final double EARTH_RADIUS = 6371000.0; // Earth radius in meters
    private static final double METERS_PER_DEGREE_LAT = 111320.0; // Meters per degree of latitude

    // Dimension definitions
    private static final int STATE_DIMENSION = 2; // State vector dimension [latitude, longitude]

    // Noise parameters
    private double wifiNoise;
    private double gnssNoise;
    private double pdrNoise;

    // State and covariance
    private static EKFState ekfState = null;

    // System noise covariance matrix and sensor measurement noise covariance matrices
    private static double[][] Q = new double[0][];
    private static double[][] R_wifi = new double[0][];
    private static double[][] R_gnss = new double[0][];
    private static double[][] R_pdr;

    // Identity matrix
    private static final double[][] IDENTITY_MATRIX = {
            {1, 0},
            {0, 1}
    };

    private static double[][] createDiagonalMatrix(double value) {
        return new double[][]{
                {value, 0},
                {0, value}
        };
    }

    private static void update(@NonNull LatLng observation, double[][] R_sensor) {
        try {
            double[] x = ekfState.getState();
            double[][] P = ekfState.getCovariance();
            double[] z = {observation.latitude, observation.longitude};
            double[] y = subtractVector(z, x);
            double[][] S = addMatrix(P, R_sensor);
            double[][] S_inv = invert2x2(S);
            double[][] K = multiplyMatrix(P, S_inv);
            double[] K_y = multiplyMatrixVector(K, y);
            double[] x_new = addVector(x, K_y);
            double[][] I_minus_K = subtractMatrix(IDENTITY_MATRIX, K);
            double[][] P_new = multiplyMatrix(I_minus_K, P);
            ekfState.setState(x_new);
            ekfState.setCovariance(P_new);
            Log.d(TAG, String.format("Updated state: [%.6f, %.6f], innovation: [%.6f, %.6f]", x_new[0], x_new[1], y[0], y[1]));
        } catch (Exception e) {
            Log.e(TAG, "Error in EKF update: " + e.getMessage(), e);
        }
    }

    public static void updateWiFi(@Nullable LatLng observation) {
        if (isValidCoordinate(observation)) {
            update(observation, R_wifi);
        }
    }

    public static void updateGNSS(@Nullable LatLng observation) {
        if (isValidCoordinate(observation)) {
            update(observation, R_gnss);
        }
    }

    public void updatePDR(@Nullable LatLng observation) {
        if (isValidCoordinate(observation)) {
            update(observation, R_pdr);
        }
    }

    public static LatLng ekfFusion(LatLng initialPosition, LatLng wifiCoord, LatLng gnssCoord, float dx, float dy) {
        int wifiNoise = 4;
        int gnssNoise = 4;
        int pdrNoise = 1;
        int initialVariance = 10;

        ekfState = new EKFState(initialPosition, initialVariance);

        Q = createDiagonalMatrix(pdrNoise * pdrNoise);
        R_wifi = createDiagonalMatrix(wifiNoise * wifiNoise);
        R_gnss = createDiagonalMatrix(gnssNoise * gnssNoise);
        R_pdr = createDiagonalMatrix(pdrNoise * pdrNoise);

        // Step 1: Prediction - Advance state using PDR displacement (critical)
        predictWithPDR(dx, dy);  // This step is very important!

        // Step 2: Update - Correct current predicted position using GNSS/WiFi
        if (isValidCoordinate(gnssCoord)) {
            updateGNSS(gnssCoord);
        }
        if (isValidCoordinate(wifiCoord)) {
            updateWiFi(wifiCoord);
        }

        return getEstimatedPosition();
    }

    private static void predictWithPDR(float dx, float dy) {
        double[] x = ekfState.getState();      // Current state [lat, lon]
        double[][] P = ekfState.getCovariance();  // Current covariance matrix

        // 1. Convert dx/dy (meters) to latitude/longitude increments (considering latitude's effect on longitude)
        double deltaLat = dy / METERS_PER_DEGREE_LAT;
        double metersPerDegreeLon = METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(x[0]));
        double deltaLon = dx / metersPerDegreeLon;

        // 2. State prediction
        double[] xPred = {
                x[0] + deltaLat,
                x[1] + deltaLon
        };

        // 3. Covariance prediction P' = P + Q
        double[][] P_pred = addMatrix(P, Q);

        // 4. Save updated state and covariance
        ekfState.setState(xPred);
        ekfState.setCovariance(P_pred);

        // 5. Print debug log
        Log.d(TAG, String.format("PDR Predict: dx=%.2f, dy=%.2f -> Δlat=%.6f, Δlon=%.6f, new pos=[%.6f, %.6f]",
                dx, dy, deltaLat, deltaLon, xPred[0], xPred[1]));
    }



    public static LatLng getEstimatedPosition() {
        return ekfState.getEstimatedPosition();
    }

    public static boolean isValidCoordinate(@Nullable LatLng coord) {
        if (coord == null) return false;
        double lat = coord.latitude;
        double lon = coord.longitude;
        if (Double.isNaN(lat) || Double.isNaN(lon)) return false;
        if (Math.abs(lat) < 1e-5 && Math.abs(lon) < 1e-5) return false;
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }


    private static double[][] addMatrix(double[][] A, double[][] B) {
        double[][] C = new double[STATE_DIMENSION][STATE_DIMENSION];
        for (int i = 0; i < STATE_DIMENSION; i++) {
            for (int j = 0; j < STATE_DIMENSION; j++) {
                C[i][j] = A[i][j] + B[i][j];
            }
        }
        return C;
    }

    private static double[][] subtractMatrix(double[][] A, double[][] B) {
        double[][] C = new double[STATE_DIMENSION][STATE_DIMENSION];
        for (int i = 0; i < STATE_DIMENSION; i++) {
            for (int j = 0; j < STATE_DIMENSION; j++) {
                C[i][j] = A[i][j] - B[i][j];
            }
        }
        return C;
    }

    private static double[][] multiplyMatrix(double[][] A, double[][] B) {
        double[][] C = new double[STATE_DIMENSION][STATE_DIMENSION];
        for (int i = 0; i < STATE_DIMENSION; i++) {
            for (int j = 0; j < STATE_DIMENSION; j++) {
                C[i][j] = 0;
                for (int k = 0; k < STATE_DIMENSION; k++) {
                    C[i][j] += A[i][k] * B[k][j];
                }
            }
        }
        return C;
    }

    private static double[] multiplyMatrixVector(double[][] A, double[] v) {
        double[] result = new double[STATE_DIMENSION];
        for (int i = 0; i < STATE_DIMENSION; i++) {
            result[i] = 0;
            for (int j = 0; j < STATE_DIMENSION; j++) {
                result[i] += A[i][j] * v[j];
            }
        }
        return result;
    }

    private static double[] addVector(double[] a, double[] b) {
        double[] c = new double[STATE_DIMENSION];
        for (int i = 0; i < STATE_DIMENSION; i++) {
            c[i] = a[i] + b[i];
        }
        return c;
    }

    private static double[] subtractVector(double[] a, double[] b) {
        double[] c = new double[STATE_DIMENSION];
        for (int i = 0; i < STATE_DIMENSION; i++) {
            c[i] = a[i] - b[i];
        }
        return c;
    }

    private static double[][] invert2x2(double[][] M) {
        double det = M[0][0] * M[1][1] - M[0][1] * M[1][0];
        if (Math.abs(det) < 1e-10) {
            Log.w(TAG, "Matrix is nearly singular, adding regularization term");
            M[0][0] += 1e-8;
            M[1][1] += 1e-8;
            det = M[0][0] * M[1][1] - M[0][1] * M[1][0];
            if (Math.abs(det) < 1e-10) {
                throw new IllegalArgumentException("Matrix is not invertible, determinant too small");
            }
        }
        double invDet = 1.0 / det;
        double[][] inv = new double[2][2];
        inv[0][0] = M[1][1] * invDet;
        inv[0][1] = -M[0][1] * invDet;
        inv[1][0] = -M[1][0] * invDet;
        inv[1][1] = M[0][0] * invDet;
        return inv;
    }

}
