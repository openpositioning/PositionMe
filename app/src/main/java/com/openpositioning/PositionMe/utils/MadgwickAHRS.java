package com.openpositioning.PositionMe.utils;

import android.util.Log;

// Lightweight Madgwick AHRS filter for Android sensor fusion.
// Uses gyroscope integration corrected by accelerometer and magnetometer observations.
// P1-2 IMPROVEMENT: Added magnetic anomaly detection for indoor environments
public class MadgwickAHRS {

    // P1-2: Improved Beta value (from 0.12 to 0.15) for better drift correction
    private static final float DEFAULT_BETA = 0.15f;
    

    private static final float MAGNETOMETER_MIN = 20.0f;
    private static final float MAGNETOMETER_MAX = 60.0f;

    private float beta;
    private float q0 = 1.0f;
    private float q1 = 0.0f;
    private float q2 = 0.0f;
    private float q3 = 0.0f;
    
    // P1-2: Track magnetic anomalies for logging
    private int magneticAnomalyCount = 0;

    public MadgwickAHRS() {
        this(DEFAULT_BETA);
    }

    public MadgwickAHRS(float beta) {
        this.beta = beta;
    }

    public void setBeta(float beta) {
        this.beta = beta;
    }

    public void reset() {
        q0 = 1.0f;
        q1 = 0.0f;
        q2 = 0.0f;
        q3 = 0.0f;
        magneticAnomalyCount = 0;
    }

    public void update(float gx, float gy, float gz,
                       float ax, float ay, float az,
                       float mx, float my, float mz,
                       float deltaTimeSeconds) {
        if (deltaTimeSeconds <= 0.0f) {
            return;
        }

        float accNorm = invSqrt(ax * ax + ay * ay + az * az);
        if (Float.isNaN(accNorm) || Float.isInfinite(accNorm)) {
            integrateGyroscopeOnly(gx, gy, gz, deltaTimeSeconds);
            return;
        }
        ax *= accNorm;
        ay *= accNorm;
        az *= accNorm;

        // ===== P1-2 IMPROVEMENT: Magnetic anomaly detection =====
        float magIntensity = (float) Math.sqrt(mx * mx + my * my + mz * mz);
        
        // Check if magnetic field is outside normal range (indicates indoor metal/electrical interference)
        if (magIntensity < MAGNETOMETER_MIN || magIntensity > MAGNETOMETER_MAX) {
            // Magnetic anomaly detected - use accelerometer only without magnetometer
            magneticAnomalyCount++;
            Log.w("MadgwickAHRS", String.format("Magnetic anomaly #%d: %.1f 渭T (outside [%.0f-%.0f])", 
                magneticAnomalyCount, magIntensity, MAGNETOMETER_MIN, MAGNETOMETER_MAX));
            integrateGyroAndAccelOnly(gx, gy, gz, ax, ay, az, deltaTimeSeconds);
            return;
        }
        
        // Magnetometer is reliable, proceed with standard fusion
        magneticAnomalyCount = 0;
        float magNorm = invSqrt(mx * mx + my * my + mz * mz);

        float q0q0 = q0 * q0;
        float q0q1 = q0 * q1;
        float q0q2 = q0 * q2;
        float q0q3 = q0 * q3;
        float q1q1 = q1 * q1;
        float q1q2 = q1 * q2;
        float q1q3 = q1 * q3;
        float q2q2 = q2 * q2;
        float q2q3 = q2 * q3;
        float q3q3 = q3 * q3;

        float hx = 2.0f * mx * (0.5f - q2q2 - q3q3)
                + 2.0f * my * (q1q2 - q0q3)
                + 2.0f * mz * (q1q3 + q0q2);
        float hy = 2.0f * mx * (q1q2 + q0q3)
                + 2.0f * my * (0.5f - q1q1 - q3q3)
                + 2.0f * mz * (q2q3 - q0q1);
        float twoBx = (float) Math.sqrt(hx * hx + hy * hy);
        float twoBz = 2.0f * mx * (q1q3 - q0q2)
                + 2.0f * my * (q2q3 + q0q1)
                + 2.0f * mz * (0.5f - q1q1 - q2q2);
        float fourBx = 2.0f * twoBx;
        float fourBz = 2.0f * twoBz;

        float s0 = -2.0f * q2 * (2.0f * q1q3 - 2.0f * q0q2 - ax)
                + 2.0f * q1 * (2.0f * q0q1 + 2.0f * q2q3 - ay)
                - twoBz * q2 * (twoBx * (0.5f - q2q2 - q3q3) + twoBz * (q1q3 - q0q2) - mx)
                + (-twoBx * q3 + twoBz * q1) * (twoBx * (q1q2 - q0q3) + twoBz * (q0q1 + q2q3) - my)
                + twoBx * q2 * (twoBx * (q0q2 + q1q3) + twoBz * (0.5f - q1q1 - q2q2) - mz);
        float s1 = 2.0f * q3 * (2.0f * q1q3 - 2.0f * q0q2 - ax)
                + 2.0f * q0 * (2.0f * q0q1 + 2.0f * q2q3 - ay)
                - 4.0f * q1 * (1.0f - 2.0f * q1q1 - 2.0f * q2q2 - az)
                + twoBz * q3 * (twoBx * (0.5f - q2q2 - q3q3) + twoBz * (q1q3 - q0q2) - mx)
                + (twoBx * q2 + twoBz * q0) * (twoBx * (q1q2 - q0q3) + twoBz * (q0q1 + q2q3) - my)
                + (twoBx * q3 - fourBz * q1) * (twoBx * (q0q2 + q1q3) + twoBz * (0.5f - q1q1 - q2q2) - mz);
        float s2 = -2.0f * q0 * (2.0f * q1q3 - 2.0f * q0q2 - ax)
                + 2.0f * q3 * (2.0f * q0q1 + 2.0f * q2q3 - ay)
                - 4.0f * q2 * (1.0f - 2.0f * q1q1 - 2.0f * q2q2 - az)
                + (-fourBx * q2 - twoBz * q0) * (twoBx * (0.5f - q2q2 - q3q3) + twoBz * (q1q3 - q0q2) - mx)
                + (twoBx * q1 + twoBz * q3) * (twoBx * (q1q2 - q0q3) + twoBz * (q0q1 + q2q3) - my)
                + (twoBx * q0 - fourBz * q2) * (twoBx * (q0q2 + q1q3) + twoBz * (0.5f - q1q1 - q2q2) - mz);
        float s3 = 2.0f * q1 * (2.0f * q1q3 - 2.0f * q0q2 - ax)
                + 2.0f * q2 * (2.0f * q0q1 + 2.0f * q2q3 - ay)
                + (-fourBx * q3 + twoBz * q1) * (twoBx * (0.5f - q2q2 - q3q3) + twoBz * (q1q3 - q0q2) - mx)
                + (-twoBx * q0 + twoBz * q2) * (twoBx * (q1q2 - q0q3) + twoBz * (q0q1 + q2q3) - my)
                + twoBx * q1 * (twoBx * (q0q2 + q1q3) + twoBz * (0.5f - q1q1 - q2q2) - mz);

        float stepNorm = invSqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3);
        if (!Float.isNaN(stepNorm) && !Float.isInfinite(stepNorm)) {
            s0 *= stepNorm;
            s1 *= stepNorm;
            s2 *= stepNorm;
            s3 *= stepNorm;
        } else {
            s0 = 0.0f;
            s1 = 0.0f;
            s2 = 0.0f;
            s3 = 0.0f;
        }

        float qDot0 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz) - beta * s0;
        float qDot1 = 0.5f * (q0 * gx + q2 * gz - q3 * gy) - beta * s1;
        float qDot2 = 0.5f * (q0 * gy - q1 * gz + q3 * gx) - beta * s2;
        float qDot3 = 0.5f * (q0 * gz + q1 * gy - q2 * gx) - beta * s3;

        q0 += qDot0 * deltaTimeSeconds;
        q1 += qDot1 * deltaTimeSeconds;
        q2 += qDot2 * deltaTimeSeconds;
        q3 += qDot3 * deltaTimeSeconds;

        normalizeQuaternion();
    }

    public void updateIMU(float gx, float gy, float gz,
                          float ax, float ay, float az,
                          float deltaTimeSeconds) {
        if (deltaTimeSeconds <= 0.0f) {
            return;
        }

        float accNorm = invSqrt(ax * ax + ay * ay + az * az);
        if (Float.isNaN(accNorm) || Float.isInfinite(accNorm)) {
            integrateGyroscopeOnly(gx, gy, gz, deltaTimeSeconds);
            return;
        }

        ax *= accNorm;
        ay *= accNorm;
        az *= accNorm;
        integrateGyroAndAccelOnly(gx, gy, gz, ax, ay, az, deltaTimeSeconds);
    }

    // P1-2: Gyro + Accelerometer integration without magnetometer (for magnetic anomaly conditions)
    private void integrateGyroAndAccelOnly(float gx, float gy, float gz,
                                           float ax, float ay, float az,
                                           float deltaTimeSeconds) {
        // Compute the expected vertical acceleration
        float q0q0 = q0 * q0;
        float q0q1 = q0 * q1;
        float q0q2 = q0 * q2;
        float q1q1 = q1 * q1;
        float q1q2 = q1 * q2;
        float q2q2 = q2 * q2;
        float q3q2 = q3 * q2;
        
        // Error vector for gravity (accelerometer correction)
        float ex = ay * (q0q0 - q1q1 - q2q2 + q3 * q3) - az * (2.0f * (q0q2 + q1 * q3));
        float ey = az * (2.0f * (q0q1 - q2 * q3)) - ax * (q0q0 - q1q1 - q2q2 + q3 * q3);
        float ez = ax * (2.0f * (q1 * q3 - q0q2)) - ay * (2.0f * (q0q1 + q2q2));
        
        // P1-2: Use a more conservative beta when magnetometer is unavailable
        float conservativeBeta = this.beta * 0.8f;  // Reduce correction strength
        
        // Integrate gyroscope with gravity correction (no magnetometer)
        float qDot0 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz) - conservativeBeta * ex;
        float qDot1 = 0.5f * (q0 * gx + q2 * gz - q3 * gy) - conservativeBeta * ey;
        float qDot2 = 0.5f * (q0 * gy - q1 * gz + q3 * gx) - conservativeBeta * ez;
        float qDot3 = 0.5f * (q0 * gz + q1 * gy - q2 * gx);

        q0 += qDot0 * deltaTimeSeconds;
        q1 += qDot1 * deltaTimeSeconds;
        q2 += qDot2 * deltaTimeSeconds;
        q3 += qDot3 * deltaTimeSeconds;

        normalizeQuaternion();
    }

    private void integrateGyroscopeOnly(float gx, float gy, float gz, float deltaTimeSeconds) {
        float qDot0 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz);
        float qDot1 = 0.5f * (q0 * gx + q2 * gz - q3 * gy);
        float qDot2 = 0.5f * (q0 * gy - q1 * gz + q3 * gx);
        float qDot3 = 0.5f * (q0 * gz + q1 * gy - q2 * gx);

        q0 += qDot0 * deltaTimeSeconds;
        q1 += qDot1 * deltaTimeSeconds;
        q2 += qDot2 * deltaTimeSeconds;
        q3 += qDot3 * deltaTimeSeconds;
        normalizeQuaternion();
    }

    private void normalizeQuaternion() {
        float quaternionNorm = invSqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3);
        if (Float.isNaN(quaternionNorm) || Float.isInfinite(quaternionNorm)) {
            reset();
            return;
        }

        q0 *= quaternionNorm;
        q1 *= quaternionNorm;
        q2 *= quaternionNorm;
        q3 *= quaternionNorm;
    }

    public float[] getEulerRadians() {
        float yaw = (float) Math.atan2(2.0f * (q0 * q3 + q1 * q2), 1.0f - 2.0f * (q2 * q2 + q3 * q3));
        float sinPitch = 2.0f * (q0 * q2 - q3 * q1);
        if (sinPitch > 1.0f) sinPitch = 1.0f;
        if (sinPitch < -1.0f) sinPitch = -1.0f;
        float pitch = (float) Math.asin(sinPitch);
        float roll = (float) Math.atan2(2.0f * (q0 * q1 + q2 * q3), 1.0f - 2.0f * (q1 * q1 + q2 * q2));
        return new float[]{yaw, pitch, roll};
    }

    private float invSqrt(float value) {
        if (value <= 0.0f) {
            return Float.NaN;
        }
        return (float) (1.0 / Math.sqrt(value));
    }
    
    // P1-2: Getter for magnetic anomaly count (for monitoring)
    public int getMagneticAnomalyCount() {
        return magneticAnomalyCount;
    }
}


