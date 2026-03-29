package com.openpositioning.PositionMe.sensors;

import android.util.Log;

/**
 * Extended Kalman Filter for indoor positioning.
 *
 * State vector: [east, north] in local East-North metres.
 * Process model: constant velocity, state advances by PDR displacement.
 * Observation model: direct position measurement.
 *
 * @author Haoning Huang
 */

public class ExtendedKalmanFilter {

    private static final String TAG = "EKFPositioning";

    /** PDR process noise standard deviation (metres). */
    private static final float MOTION_NOISE_STD = 0.3f;
    /** GNSS observation noise standard deviation (metres). */
    private static final float GNSS_NOISE_STD   = 5.0f;
    /** WiFi observation noise standard deviation (metres). */
    private static final float WIFI_NOISE_STD   = 15.0f;

    // State estimate
    private float stateX, stateY;

    // State covariance P (2x2 symmetric, stored as four scalars)
    private float p00, p01, p10, p11;

    private boolean initialized = false;

    /**
     * Initializes the filter at the given East-North position.
     *
     * @param initX       Initial East  position (metres)
     * @param initY       Initial North position (metres)
     * @param uncertainty Initial 1-sigma uncertainty (metres)
     */
    public void initParticles(float initX, float initY, float uncertainty) {
        stateX = initX;
        stateY = initY;
        float u2 = uncertainty * uncertainty;
        p00 = u2;  p01 = 0f;
        p10 = 0f;  p11 = u2;
        initialized = true;
        Log.i(TAG, "EKF initialised at (" + initX + ", " + initY
                + ") ± " + uncertainty + " m");
    }

    /**
     * Prediction step: advances state by PDR displacement and grows covariance.
     *
     * @param dx East  displacement since last step (metres)
     * @param dy North displacement since last step (metres)
     */
    public void predict(float dx, float dy) {
        if (!initialized) return;
        float movementMagnitude = (float) Math.sqrt(dx * dx + dy * dy);
        float noiseStd = (movementMagnitude < 0.05f)
                ? MOTION_NOISE_STD * 0.1f : MOTION_NOISE_STD;
        stateX += dx;
        stateY += dy;
        float q = noiseStd * noiseStd;
        p00 += q;
        p11 += q;
    }

    /**
     * Update step with a position observation.
     * H = I, so innovation = meas - state, S = P + R.
     *
     * @param measX    Observed East  position (metres)
     * @param measY    Observed North position (metres)
     * @param noiseStd Observation noise standard deviation (metres)
     */
    private void update(float measX, float measY, float noiseStd) {
        if (!initialized) return;
        float r   = noiseStd * noiseStd;
        // Innovation covariance S = P + R*I
        float s00 = p00 + r,  s01 = p01;
        float s10 = p10,       s11 = p11 + r;
        // S inverse
        float det = s00 * s11 - s01 * s10;
        if (Math.abs(det) < 1e-10f) return;
        float id  = 1f / det;
        float si00 =  s11 * id,  si01 = -s01 * id;
        float si10 = -s10 * id,  si11 =  s00 * id;
        // Kalman gain K = P * S^{-1}
        float k00 = p00 * si00 + p01 * si10;
        float k01 = p00 * si01 + p01 * si11;
        float k10 = p10 * si00 + p11 * si10;
        float k11 = p10 * si01 + p11 * si11;
        // State update
        float innX = measX - stateX;
        float innY = measY - stateY;
        stateX += k00 * innX + k01 * innY;
        stateY += k10 * innX + k11 * innY;
        // Covariance update P = (I - K) * P
        float ik00 = 1f - k00,  ik01 = -k01;
        float ik10 = -k10,       ik11 = 1f - k11;
        float np00 = ik00 * p00 + ik01 * p10;
        float np01 = ik00 * p01 + ik01 * p11;
        float np10 = ik10 * p00 + ik11 * p10;
        float np11 = ik10 * p01 + ik11 * p11;
        p00 = np00;  p01 = np01;
        p10 = np10;  p11 = np11;
    }

    /** Update with a GNSS position observation. */
    public void updateWithGnss(float measX, float measY) {
        update(measX, measY, GNSS_NOISE_STD);
        Log.d(TAG, "GNSS update. State: (" + stateX + ", " + stateY + ")");
    }

    /**
     * Update with a GNSS observation using accuracy-adaptive noise.
     *
     * @param measX    Observed East  position (metres)
     * @param measY    Observed North position (metres)
     * @param accuracy Reported horizontal accuracy from Android Location (metres)
     */
    public void updateWithGnss(float measX, float measY, float accuracy) {
        float noiseStd = Math.min(Math.max(accuracy, 3.0f), 30.0f);
        update(measX, measY, noiseStd);
        Log.d(TAG, "GNSS update (accuracy=" + accuracy + "m, noiseStd=" + noiseStd
                + "m). State: (" + stateX + ", " + stateY + ")");
    }

    /** Update with a WiFi positioning observation. */
    public void updateWithWifi(float measX, float measY) {
        update(measX, measY, WIFI_NOISE_STD);
        Log.d(TAG, "WiFi update. State: (" + stateX + ", " + stateY + ")");
    }

    /**
     * Returns the current best position estimate.
     *
     * @return float[]{east, north} in East-North metres.
     */
    public float[] getBestEstimate() {
        if (!initialized) return new float[]{0f, 0f};
        return new float[]{stateX, stateY};
    }

    /**
     * Returns position uncertainty as the RMS of the covariance diagonal.
     *
     * @return 1-sigma uncertainty in metres (-1 if not initialized)
     */
    public double getSigmaMetres() {
        if (!initialized) return -1.0;
        return Math.sqrt(p00 + p11);
    }

    /**
     * Resets state around a new position with increased uncertainty.
     * Used after a floor change.
     */
    public void resetAroundPosition(float centreX, float centreY, float uncertainty) {
        stateX = centreX;
        stateY = centreY;
        float u2 = uncertainty * uncertainty;
        p00 = u2;  p01 = 0f;
        p10 = 0f;  p11 = u2;
        Log.i(TAG, "EKF reset around (" + centreX + ", " + centreY + ")");
    }

    public boolean isInitialized() { return initialized; }
}
