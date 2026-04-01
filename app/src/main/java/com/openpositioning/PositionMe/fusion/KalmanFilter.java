package com.openpositioning.PositionMe.fusion;

import static com.openpositioning.PositionMe.fusion.FusionConstants.BIAS_UNCERTAINTY_INITIAL;
import static com.openpositioning.PositionMe.fusion.FusionConstants.DELTA_T;
import static com.openpositioning.PositionMe.fusion.FusionConstants.GYROSCOPE_UNCERTAINTY_INITIAL;
import static com.openpositioning.PositionMe.fusion.FusionConstants.MEASUREMENT_NOISE;
import static com.openpositioning.PositionMe.fusion.FusionConstants.NOISE_STD_DEV_BIAS;
import static com.openpositioning.PositionMe.fusion.FusionConstants.NOISE_STD_DEV_PREDICTION;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.preference.PreferenceManager;
import com.openpositioning.PositionMe.presentation.fragment.SettingsFragment;
import org.ejml.dense.row.CommonOps_FDRM;
import org.ejml.simple.SimpleMatrix;

/**
 * This class implements a Kalman filter for calculating an accurate orientation of the phone using
 * the accelerometer, magnetometer, and gyroscope.
 *
 * <p><a
 * href="https://medium.com/@niru5/fusion-of-accelerometer-magnetometer-data-with-gyroscope-part-2-2887261e7245">See
 * here for equations used</a>
 *
 * @see com.openpositioning.PositionMe.sensors.SensorFusion SensorFusion
 */
public class KalmanFilter {
    private static final String TAG = "KalmanFilter";
    private final SimpleMatrix transformation;
    private SimpleMatrix prediction;
    private final SimpleMatrix timeMatrix;
    private SimpleMatrix covariance;
    private final SimpleMatrix noiseMatrix;
    private final SimpleMatrix measurementVariance;

    private double noiseStdDevPrediction;
    private double noiseStdDevBias;
    private double noiseMeasurement;

    public KalmanFilter(Context context) {
        updateConstants(context);

        transformation =
                new SimpleMatrix(
                        new float[][] {
                            new float[] {1f, DELTA_T},
                            new float[] {0f, 1f}
                        });
        prediction = new SimpleMatrix(new float[][] {new float[1], new float[1]});
        timeMatrix = new SimpleMatrix(new float[][] {new float[] {DELTA_T}, new float[] {0}});
        noiseMatrix =
                new SimpleMatrix(
                        new double[][] {
                            new double[] {noiseStdDevPrediction * noiseStdDevPrediction, 0},
                            new double[] {0, noiseStdDevBias * noiseStdDevBias}
                        });
        measurementVariance = new SimpleMatrix(new double[][] {new double[] {noiseMeasurement}});
        Log.d(TAG, "Kalman filter created");
    }

    /**
     * Update any constants used based on the user's values from the {@link SettingsFragment}
     *
     * @param context The current app context (for retrieving the settings)
     */
    public void updateConstants(Context context) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        if (settings.getBoolean("overwrite_kalman_constants", false)) {
            noiseStdDevPrediction =
                    Float.parseFloat(
                            settings.getString(
                                    "kalman_pred_noise_std_dev",
                                    String.valueOf(NOISE_STD_DEV_PREDICTION)));
            noiseStdDevBias =
                    Float.parseFloat(
                            settings.getString(
                                    "kalman_pred_bias_std_dev",
                                    String.valueOf(NOISE_STD_DEV_BIAS)));
            noiseMeasurement =
                    Float.parseFloat(
                            settings.getString("kalman_noise", String.valueOf(MEASUREMENT_NOISE)));
        } else {
            noiseStdDevPrediction = NOISE_STD_DEV_PREDICTION;
            noiseStdDevBias = NOISE_STD_DEV_BIAS;
            noiseMeasurement = MEASUREMENT_NOISE;
        }

        Log.d(TAG, "Constants updated");
        Log.d(TAG, "noiseStdDevPrediction: " + noiseStdDevPrediction);
        Log.d(TAG, "noiseStdDevBias: " + noiseStdDevBias);
        Log.d(TAG, "noiseMeasurement: " + noiseMeasurement);
    }

    /**
     * Prediction step of the kalman filter algorithm, based on the measurement from the gyroscope
     *
     * @param angularVelocity The latest reading from the gyroscope, comprised of [x, y, z] values
     *     in radians
     */
    public void predict(float angularVelocity) {
        // Initialise last timestamp if required
        SimpleMatrix gyroMatrix = new SimpleMatrix(new float[][] {new float[] {angularVelocity}});

        // Equation 2
        prediction = (transformation.mult(prediction)).plus(timeMatrix.mult(gyroMatrix));
        if (covariance == null) {
            // Initialise covariance of rotation such that the initial uncertainly of PI (180
            // degree) and low gyroscope bias
            covariance =
                    new SimpleMatrix(
                            new double[][] {
                                new double[] {
                                    GYROSCOPE_UNCERTAINTY_INITIAL * GYROSCOPE_UNCERTAINTY_INITIAL, 0
                                },
                                new double[] {
                                    0, BIAS_UNCERTAINTY_INITIAL * BIAS_UNCERTAINTY_INITIAL
                                }
                            });
        }

        // Equation 4
        covariance =
                transformation.mult(covariance).mult(transformation.transpose()).plus(noiseMatrix);
    }

    /**
     * Apply the kalman filter to the measurement values from a sensor on the phone (accelerometer,
     * or magnetometer)
     *
     * @param heading orientation given by sensor fusion
     */
    public void measure(float heading) {
        if (covariance == null || prediction == null) return;

        // float heading = computeHeading(acceleration, magneticField);

        SimpleMatrix rawMatrix = new SimpleMatrix(new float[][] {new float[] {heading}});

        SimpleMatrix H = new SimpleMatrix(new float[][] {new float[] {1f, 0f}});

        // Equation 5
        SimpleMatrix predictionInMeasureSpace = H.mult(prediction);

        // Equation 6
        SimpleMatrix varianceInMeasureSpace = H.mult(covariance).mult(H.transpose());

        // Equation 13
        SimpleMatrix kalmanGain =
                covariance
                        .mult(H.transpose())
                        .mult(varianceInMeasureSpace.plus(measurementVariance).pseudoInverse());

        // calculate innovation
        float innovation = (float) (rawMatrix.get(0, 0) - predictionInMeasureSpace.get(0, 0));

        // wrap the innovation angle to ensure wrapped angles don't give large uncertainty
        float wrappedInnovation = wrapAngle(innovation);

        SimpleMatrix innovationMatrix =
                new SimpleMatrix(new float[][] {new float[] {wrappedInnovation}});

        // Equation 11
        SimpleMatrix newMeasurement = prediction.plus(kalmanGain.mult((innovationMatrix)));

        // Equation 12
        SimpleMatrix newCovariance = covariance.minus(kalmanGain.mult(H).mult(covariance));

        prediction = newMeasurement;
        // ensure prediction angle is wrapped
        prediction.set(0, 0, wrapAngle((float) prediction.get(0, 0)));
        covariance = newCovariance;
        Log.d(TAG, "new measurement: " + getMeasurement());
    }

    /**
     * Retrieve the latest measurement from the kalman filter
     *
     * @return The kalman filter's measurement as an array of [x, y, z] floats
     */
    public float getMeasurement() {
        float[] measurementRow = extractRow(prediction, 0);
        return measurementRow[0];
    }

    /**
     * Computes the covariance of two matrices which are assumed to be independent
     *
     * <p>Note that the matrices passed in as parameters must be of equal dimensions
     *
     * @param matrixA The first matrix
     * @param matrixB The second matrix
     * @return A 2x2 matrix [[CovAA, 0], [0, CovBB]]
     */
    private SimpleMatrix computeCovariance(SimpleMatrix matrixA, SimpleMatrix matrixB) {
        float meanA = (float) matrixA.elementSum() / matrixA.numCols();
        float meanB = (float) matrixB.elementSum() / matrixB.numCols();
        CommonOps_FDRM.add(matrixA.getMatrix(), -meanA);
        CommonOps_FDRM.add(matrixB.getMatrix(), -meanB);

        double covAA = matrixA.transpose().mult(matrixA).get(0, 0) / matrixA.numCols();
        double covBB = matrixB.transpose().mult(matrixB).get(0, 0) / matrixB.numCols();

        return new SimpleMatrix(
                new double[][] {
                    new double[] {covAA, 0},
                    new double[] {0, covBB}
                });
    }

    /**
     * @param acceleration The raw measurement value of acceleration vector
     * @param magneticField The raw measurement value of magnetic field vector
     * @return heading based on a magnetic field reading corrected using the accelerometer for phone
     *     tilt
     */
    private float computeHeading(float[] acceleration, float[] magneticField) {
        float accelerationX = acceleration[0];
        float accelerationY = acceleration[1];
        float accelerationZ = acceleration[2];

        float magX = magneticField[0];
        float magY = magneticField[1];
        float magZ = magneticField[2];

        double propInZY =
                Math.sqrt((accelerationY * accelerationY) + (accelerationZ * accelerationZ));

        // Find pitch of the phone using accelerometer vector and make it independent from roll
        double pitch = Math.atan2(accelerationX, propInZY);

        // Find roll of the phone using accelerometer data
        double roll = Math.atan2(accelerationY, accelerationZ);

        // Find horizontal forward component of magnetic field
        double mx =
                magX * Math.cos(pitch)
                        + magY * Math.sin(roll) * Math.sin(pitch)
                        + magZ * Math.cos(roll) * Math.sin(pitch);

        // Find horizontal sideways component of magnetic field
        double my = magY * Math.cos(roll) - magZ * Math.sin(roll);

        // Find heading from corrected magnetic field
        float heading = (float) Math.atan2(my, mx);
        Log.d(TAG, "heading estimated as: " + heading);
        return heading;
    }

    /**
     * Helper function to extract the values from the row of a matrix
     *
     * @param matrix The matrix with data for extraction
     * @param rowNumber The row being requested
     * @return An array of float values from the row of the matrix
     */
    private float[] extractRow(SimpleMatrix matrix, int rowNumber) {
        float[] row = new float[matrix.numCols()];
        for (int i = 0; i < matrix.numCols(); i++) {
            row[i] = (float) matrix.get(rowNumber, i);
        }
        return row;
    }

    /**
     * Helper function to extract the values from the row of a matrix
     *
     * @param angle The matrix with data for extraction
     * @return An array of float values from the row of the matrix
     */
    private float wrapAngle(float angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    public void setInitialHeading(float heading) {
        prediction.set(0, 0, heading); // set heading state
    }
}
