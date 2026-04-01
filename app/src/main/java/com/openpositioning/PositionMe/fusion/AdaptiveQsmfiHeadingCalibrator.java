package com.openpositioning.PositionMe.fusion;

import android.hardware.SensorManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Adaptive QSMFI + gyro heading calibrator.
 *
 * Paper-inspired behaviour:
 * - classifies stationary vs walking
 * - uses different sliding-window durations for each mode
 * - adapts magnetic heading variance thresholds over time
 * - rejects candidate QSMFIs when gyro rotation over the window is too large
 * - rejects candidate QSMFIs when stable compass heading disagrees too much with gyro heading
 * - when accepted, uses the stable compass heading to recalibrate fused heading
 *
 * Output:
 * - fusedHeadingRad: the heading that should be fed into both PDR and the particle filter
 */
public class AdaptiveQsmfiHeadingCalibrator {

    private static final double DEG2RAD = Math.PI / 180.0;

    // ---------------------------------------------------------------------
    // Current sensor states
    // ---------------------------------------------------------------------
    private boolean hasGravity = false;
    private boolean hasMagnetic = false;
    private boolean initialised = false;

    private final float[] gravity = new float[3];
    private final float[] magnetic = new float[3];

    private double compassHeadingRad = 0.0;   // tilt-compensated magnetic heading
    private double gyroHeadingRad = 0.0;      // integrated gyro heading
    private double fusedHeadingRad = 0.0;     // final output heading

    private long lastGyroTimestampNs = -1L;
    private long lastStepTimestampNs = -1L;

    // ---------------------------------------------------------------------
    // Adaptive QSMFI thresholds and windows
    // ---------------------------------------------------------------------
    private long windowNsStationary = 2_000_000_000L;  // 2.0 s
    private long windowNsWalking = 800_000_000L;       // 0.8 s

    // variance of heading in radians^2
    private double tauMagVarStationary = Math.pow(6.0 * DEG2RAD, 2);
    private double tauMagVarWalking = Math.pow(10.0 * DEG2RAD, 2);

    private static final double TAU_MAG_VAR_MIN = Math.pow(3.0 * DEG2RAD, 2);
    private static final double TAU_MAG_VAR_MAX = Math.pow(18.0 * DEG2RAD, 2);

    // gyro motion thresholds
    private static final double TAU_GYRO_STATIONARY = 5.0 * DEG2RAD;
    private static final double TAU_GYRO_WALKING = 12.0 * DEG2RAD;

    // compass-vs-gyro agreement threshold
    private static final double TAU_QSMFI_GYRO_AGREE = 15.0 * DEG2RAD;

    // adaptive update parameters
    private static final double EPS_ADAPT = 0.15;
    private static final double DELTA_INC = Math.pow(1.5 * DEG2RAD, 2);
    private static final double DELTA_DEC = Math.pow(1.0 * DEG2RAD, 2);

    // ---------------------------------------------------------------------
    // Histories
    // ---------------------------------------------------------------------
    private final Deque<AngleSample> compassHistory = new ArrayDeque<>();
    private final Deque<AngleSample> gyroHistory = new ArrayDeque<>();
    private final Deque<Boolean> qsmfiAcceptHistory = new ArrayDeque<>();
    private static final int QSMFI_ACCEPT_HISTORY_SIZE = 20;

    // ---------------------------------------------------------------------
    // Status
    // ---------------------------------------------------------------------
    private boolean headingReliable = true;
    private String lastCorrectionSource = "gyro_only";

    // Turn detection with hysteresis
    private static final double TURN_RATE_ON = 30.0 * DEG2RAD;
    private static final double TURN_RATE_OFF = 18.0 * DEG2RAD;

    private boolean isTurning = false;
    private double lastGyroRate = 0.0;
    private boolean isCompassStable = false;
    private boolean isGyroStable = false;
    private boolean isStraight = false;
    private static final double FIXED_HEADING_BIAS_RAD = Math.toRadians(25.0);

    private double applyHeadingBias(double headingRad) {
        return wrapAngle(headingRad - FIXED_HEADING_BIAS_RAD);
    }

    public void reset() {
        hasGravity = false;
        hasMagnetic = false;
        initialised = false;
        gravity[0] = gravity[1] = gravity[2] = 0f;
        magnetic[0] = magnetic[1] = magnetic[2] = 0f;
        compassHeadingRad = 0.0;
        gyroHeadingRad = 0.0;
        fusedHeadingRad = 0.0;
        lastGyroTimestampNs = -1L;
        lastStepTimestampNs = -1L;
        windowNsStationary = 2_000_000_000L;
        windowNsWalking = 800_000_000L;
        tauMagVarStationary = Math.pow(6.0 * DEG2RAD, 2);
        tauMagVarWalking = Math.pow(10.0 * DEG2RAD, 2);
        compassHistory.clear();
        gyroHistory.clear();
        qsmfiAcceptHistory.clear();
        isTurning = false;
        isCompassStable = false;
        isGyroStable = false;
        lastGyroRate = 0.0;
        isStraight = false;
        headingReliable = false;
        lastCorrectionSource = "gyro_only";
    }

    /**
     * Updates gyro-based heading using angular rate integration.
     */
    public void onGyro(float gzRadPerSec, long timestampNs) {
        if (lastGyroTimestampNs < 0L) {
            lastGyroTimestampNs = timestampNs;
            return;
        }

        double dt = (timestampNs - lastGyroTimestampNs) * 1e-9;
        lastGyroTimestampNs = timestampNs;

        // Sign corrected to match your compass heading convention
        double correctedGyroRate = -gzRadPerSec;

        lastGyroRate = Math.abs(correctedGyroRate);

        if (isTurning) {
            isTurning = lastGyroRate > TURN_RATE_OFF;
        } else {
            isTurning = lastGyroRate > TURN_RATE_ON;
        }

        gyroHeadingRad = wrapAngle(gyroHeadingRad + correctedGyroRate * dt);

        if (!initialised) {
            fusedHeadingRad = gyroHeadingRad;
        } else {
            fusedHeadingRad = wrapAngle(fusedHeadingRad + correctedGyroRate * dt);
        }

        gyroHistory.addLast(new AngleSample(timestampNs, gyroHeadingRad));
        trimHistory(gyroHistory, windowNsStationary + 500_000_000L);
    }

    // Updates gravity vector and refreshes compass heading if possible.
    public void onGravity(float gx, float gy, float gz) {
        gravity[0] = gx;
        gravity[1] = gy;
        gravity[2] = gz;
        hasGravity = true;
        updateCompassHeading();
    }
    /**
     * Updates magnetic field data, computes compass heading, stores history,
     * and attempts heading calibration.
     */
    public void onMagneticField(float mx, float my, float mz, long timestampNs) {
        magnetic[0] = mx;
        magnetic[1] = my;
        magnetic[2] = mz;
        hasMagnetic = true;
        updateCompassHeading();

        if (!hasGravity || !hasMagnetic) {
            return;
        }

        compassHistory.addLast(new AngleSample(timestampNs, compassHeadingRad));
        trimHistory(compassHistory, windowNsStationary + 500_000_000L);

        attemptQsmfiCalibration(timestampNs);
    }

    // Marks the latest step time for walking detection.
    public void onStepDetected(long timestampNs) {
        lastStepTimestampNs = timestampNs;
    }

    /**
     * Computes tilt-compensated compass heading from gravity and magnetic sensors.
     */
    private void updateCompassHeading() {
        if (!hasGravity || !hasMagnetic) {
            return;
        }

        float[] R = new float[9];
        float[] I = new float[9];
        boolean ok = SensorManager.getRotationMatrix(R, I, gravity, magnetic);
        if (!ok) {
            return;
        }

        float[] orientation = new float[3];
        SensorManager.getOrientation(R, orientation);

//        compassHeadingRad = wrapAngle(orientation[0]);
        compassHeadingRad = applyHeadingBias(orientation[0]);

        if (!initialised) {
            initialised = true;
            gyroHeadingRad = compassHeadingRad;
            fusedHeadingRad = compassHeadingRad;
        }
    }

    /**
     * Attempts to correct fused heading using a stable compass heading window.
     */
    private void attemptQsmfiCalibration(long nowNs) {
        boolean walking = isWalking(nowNs);

        // Select the time window and thresholds based on the motion state.
        long windowNs = walking ? windowNsWalking : windowNsStationary;
        double tauMagVar = walking ? tauMagVarWalking : tauMagVarStationary;
        double tauGyro = walking ? TAU_GYRO_WALKING : TAU_GYRO_STATIONARY;

        // Get recent compass heading samples inside the selected window.
        List<Double> compassWindow = getRecentAngles(compassHistory, nowNs, windowNs);

        // Not enough data yet to judge heading stability.
        if (compassWindow.size() < 8) {
            headingReliable = false;
            lastCorrectionSource = "gyro_only";
            return;
        }
        // Measure compass heading variation and gyro heading change over the same window.
        double varCompass = circularVariance(compassWindow);
        double gyroDelta = getRecentGyroDelta(nowNs, windowNs);

        boolean accepted = false;

        // Check whether gyro motion is calm enough and compass heading is stable enough.
        isGyroStable = Math.abs(gyroDelta) < tauGyro;
        isCompassStable = varCompass < tauMagVar;

        // Compute a stable compass heading from the recent window.
        double stableHeading = circularMean(compassWindow);
        // Compare compass-based heading with current gyro heading.
        double disagreement = Math.abs(wrapAngle(stableHeading - gyroHeadingRad));

        boolean canFuse;

        if (isTurning) {
            // During turns, be more strict before accepting compass correction.
            canFuse = isCompassStable
                    && disagreement < TAU_QSMFI_GYRO_AGREE * 0.5;
        } else {
            // During straight or calm motion, require both stable gyro and stable compass.
            canFuse = isCompassStable
                    && isGyroStable
                    && disagreement < TAU_QSMFI_GYRO_AGREE;
        }

        if (canFuse) {
            double gain;
            // Use smaller correction gain during turns, larger gain when stable.
            if (isTurning) {
                gain = walking ? 0.07 : 0.10;
            } else {
                gain = walking ? 0.12 : 0.25;
            }
            // Blend fused heading toward the stable compass heading.
            fusedHeadingRad = blendAngle(fusedHeadingRad, stableHeading, gain);
            headingReliable = true;
            lastCorrectionSource = "qsmfi";
            accepted = true;
        } else {
            // If checks fail, keep using gyro-only heading.
            headingReliable = false;
            lastCorrectionSource = "gyro_only";
        }

        updateQsmfiAcceptance(accepted);
        adaptQsmfiThresholds();
    }

    private void updateQsmfiAcceptance(boolean accepted) {
        // Store whether the latest QSMFI attempt was accepted.
        qsmfiAcceptHistory.addLast(accepted);
        // Keep only the most recent acceptance results.
        while (qsmfiAcceptHistory.size() > QSMFI_ACCEPT_HISTORY_SIZE) {
            qsmfiAcceptHistory.removeFirst();
        }
    }

    private void adaptQsmfiThresholds() {
        // Nothing to adapt if there is no acceptance history yet.
        if (qsmfiAcceptHistory.isEmpty()) {
            return;
        }

        // Compute the recent acceptance rate of QSMFI correction.
        double acceptRate = 0.0;
        for (Boolean b : qsmfiAcceptHistory) {
            if (Boolean.TRUE.equals(b)) {
                acceptRate += 1.0;
            }
        }
        acceptRate /= qsmfiAcceptHistory.size();

        // Adapt walking compass-variance threshold:
        // increase it if acceptance is low, decrease it if acceptance is high.
        tauMagVarWalking = clamp(
                tauMagVarWalking + EPS_ADAPT * (DELTA_INC * (1.0 - acceptRate) - DELTA_DEC * acceptRate),
                TAU_MAG_VAR_MIN,
                TAU_MAG_VAR_MAX
        );

        tauMagVarStationary = clamp(
                tauMagVarStationary + EPS_ADAPT * (DELTA_INC * (1.0 - acceptRate) - DELTA_DEC * acceptRate),
                TAU_MAG_VAR_MIN,
                TAU_MAG_VAR_MAX
        );
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private boolean isWalking(long nowNs) {
        // Treat the user as walking if a step was detected recently.
        return lastStepTimestampNs > 0L && (nowNs - lastStepTimestampNs) < 1_500_000_000L;
    }

    private void trimHistory(Deque<AngleSample> history, long maxAgeNs) {
        // Use the latest known sensor timestamp as the reference time.
        long nowNs = Math.max(
                compassHistory.isEmpty() ? 0L : compassHistory.peekLast().timestampNs,
                gyroHistory.isEmpty() ? 0L : gyroHistory.peekLast().timestampNs
        );

        // Remove samples that are older than the allowed history length.
        while (!history.isEmpty() && nowNs - history.peekFirst().timestampNs > maxAgeNs) {
            history.removeFirst();
        }
    }

    private List<Double> getRecentAngles(Deque<AngleSample> history, long nowNs, long windowNs) {
        // Collect angle samples that lie inside the requested recent time window.
        List<Double> out = new ArrayList<>();
        for (AngleSample s : history) {
            if (nowNs - s.timestampNs <= windowNs) {
                out.add(s.angleRad);
            }
        }
        return out;
    }

    private double getRecentGyroDelta(long nowNs, long windowNs) {
        // Find the first and last gyro heading samples inside the time window.
        AngleSample first = null;
        AngleSample last = null;

        for (AngleSample s : gyroHistory) {
            if (nowNs - s.timestampNs <= windowNs) {
                if (first == null) {
                    first = s;
                }
                last = s;
            }
        }

        // If there are not enough samples, return zero change.
        if (first == null || last == null) {
            return 0.0;
        }

        // Return the wrapped heading change over the window.
        return wrapAngle(last.angleRad - first.angleRad);
    }

    private double circularMean(List<Double> angles) {
        // Return zero if no angles are available.
        if (angles.isEmpty()) {
            return 0.0;
        }

        // Compute circular mean using summed sine and cosine.
        double s = 0.0;
        double c = 0.0;
        for (double a : angles) {
            s += Math.sin(a);
            c += Math.cos(a);
        }

        return Math.atan2(s / angles.size(), c / angles.size());
    }

    private double circularVariance(List<Double> angles) {
        // No valid variance if there are no samples.
        if (angles.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }

        // Compute spread of angles around the circular mean.
        double mean = circularMean(angles);
        double sum = 0.0;
        for (double a : angles) {
            double d = wrapAngle(a - mean);
            sum += d * d;
        }
        return sum / angles.size();
    }

    private double blendAngle(double current, double target, double gain) {

        // Move the current angle toward the target angle by the given gain.
        return wrapAngle(current + gain * wrapAngle(target - current));
    }

    private double clamp(double v, double lo, double hi) {
        // Restrict a value to the given range.
        return Math.max(lo, Math.min(hi, v));
    }

    private double wrapAngle(double angle) {
        // Keep angle inside the range [-pi, pi].
        while (angle > Math.PI) angle -= 2.0 * Math.PI;
        while (angle < -Math.PI) angle += 2.0 * Math.PI;
        return angle;
    }

    private static class AngleSample {
        // Timestamped heading sample used in sliding-window history.
        final long timestampNs;
        final double angleRad;

        AngleSample(long timestampNs, double angleRad) {
            this.timestampNs = timestampNs;
            this.angleRad = angleRad;
        }
    }

    // ---------------------------------------------------------------------
    // Getter Functions
    // ---------------------------------------------------------------------

    public float getGyroHeadingRad() {
        // Returns current gyro-integrated heading.
        return (float) gyroHeadingRad;
    }

    public float getCompassHeadingRad() {
        // Returns current tilt-compensated compass heading.
        return (float) compassHeadingRad;
    }

    public float getFusedHeadingRad() {
        // Returns final fused heading used by downstream logic.
        return (float) fusedHeadingRad;
    }

    public boolean getIsTurning() {
        // Returns whether the user is currently detected as turning.
        return isTurning;
    }

    public boolean getCompassStable() {
        // Returns whether recent compass heading is stable enough.
        return isCompassStable;
    }

    public boolean getGyroStable() {
        // Returns whether recent gyro motion is stable enough.
        return isGyroStable;
    }

    public boolean isHeadingReliable() {
        // Returns whether the current heading output is considered reliable.
        return headingReliable;
    }
    public boolean isInitialised() {
        // Returns whether the calibrator has been initialised successfully.
        return initialised;
    }

    public String getLastCorrectionSource() {
        // Returns the source of the most recent heading correction.
        return lastCorrectionSource;
    }

}