package com.openpositioning.PositionMe.utils;

import android.hardware.SensorManager;
import android.util.Log;






public class ImprovedMadgwickAHRS {

    private static final float DEFAULT_BETA = 0.15f;
    private static final float MAGNETOMETER_MIN = 20.0f;
    private static final float MAGNETOMETER_MAX = 60.0f;
    private static final float GRAVITY_NOMINAL = 9.81f;

    private float beta;
    private float q0 = 1.0f;
    private float q1 = 0.0f;
    private float q2 = 0.0f;
    private float q3 = 0.0f;
    

    private CircularFloatBuffer magIntensityBuffer;
    private int magAnomalyCount = 0;
    private static final int MAG_ANOMALY_THRESHOLD = 5;

    public ImprovedMadgwickAHRS() {
        this(DEFAULT_BETA);
        this.magIntensityBuffer = new CircularFloatBuffer(10);
    }

    public ImprovedMadgwickAHRS(float beta) {
        this.beta = beta;
        this.magIntensityBuffer = new CircularFloatBuffer(10);
    }

    public void setBeta(float beta) {
        this.beta = beta;
    }

    public void reset() {
        q0 = 1.0f;
        q1 = 0.0f;
        q2 = 0.0f;
        q3 = 0.0f;
        magAnomalyCount = 0;
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


        float magIntensity = (float) Math.sqrt(mx * mx + my * my + mz * mz);
        

        boolean isMagneticAnomaly = isMagneticAnomaly(magIntensity);
        
        if (isMagneticAnomaly) {

            Log.w("MadgwickAHRS", "Magnetic anomaly detected: " + magIntensity + " 渭T");
            integrateGyroAndAccel(gx, gy, gz, ax, ay, az, deltaTimeSeconds);
            magAnomalyCount++;
            return;
        }
        

        float magNorm = invSqrt(mx * mx + my * my + mz * mz);
        if (Float.isNaN(magNorm) || Float.isInfinite(magNorm)) {
            integrateGyroscopeOnly(gx, gy, gz, deltaTimeSeconds);
            return;
        }
        mx *= magNorm;
        my *= magNorm;
        mz *= magNorm;
        magAnomalyCount = 0;


        float accelMagnitude = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        float adaptiveBeta = calculateAdaptiveBeta(accelMagnitude);


        updateWithFullMagneticCorrection(gx, gy, gz, ax, ay, az, mx, my, mz, adaptiveBeta);
    }



    private boolean isMagneticAnomaly(float magIntensity) {
        magIntensityBuffer.putNewest(magIntensity);
        

        if (magIntensity < MAGNETOMETER_MIN || magIntensity > MAGNETOMETER_MAX) {
            return true;
        }
        

        if (magIntensityBuffer.isFull()) {
            java.util.List<Float> history = magIntensityBuffer.getListCopy();
            float mean = 0;
            for (float val : history) {
                mean += val;
            }
            mean /= history.size();
            
            float variance = 0;
            for (float val : history) {
                variance += (val - mean) * (val - mean);
            }
            variance /= history.size();
            float stdDev = (float) Math.sqrt(variance);
            

            if (Math.abs(magIntensity - mean) > 2.0f * stdDev) {
                return true;
            }
        }
        
        return false;
    }



    private float calculateAdaptiveBeta(float accelMagnitude) {

        float deviation = Math.abs(accelMagnitude - GRAVITY_NOMINAL);
        
        if (deviation > 3.0f) {

            return 0.30f;
        } else if (deviation > 1.0f) {

            return 0.20f;
        } else if (deviation < 0.2f) {

            return 0.10f;
        } else {

            return 0.15f;
        }
    }


    private void integrateGyroAndAccel(float gx, float gy, float gz,
                                       float ax, float ay, float az,
                                       float deltaTimeSeconds) {
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


        float gx_pred = 2.0f * (q1q3 - q0q2);
        float gy_pred = 2.0f * (q0q1 + q2q3);
        float gz_pred = q0q0 - q1q1 - q2q2 + q3q3;


        float ex = ay * gz_pred - az * gy_pred;
        float ey = az * gx_pred - ax * gz_pred;
        float ez = ax * gy_pred - ay * gx_pred;


        float adaptiveBeta = calculateAdaptiveBeta((float) Math.sqrt(ax * ax + ay * ay + az * az));

        float qDot0 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz) - adaptiveBeta * ex;
        float qDot1 = 0.5f * (q0 * gx + q2 * gz - q3 * gy) - adaptiveBeta * ey;
        float qDot2 = 0.5f * (q0 * gy - q1 * gz + q3 * gx) - adaptiveBeta * ez;
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


    private void updateWithFullMagneticCorrection(float gx, float gy, float gz,
                                                  float ax, float ay, float az,
                                                  float mx, float my, float mz,
                                                  float adaptiveBeta) {
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
        float bx = (float) Math.sqrt(hx * hx + hy * hy);
        float bz = 2.0f * mx * (q1q3 - q0q2)
                + 2.0f * my * (q2q3 + q0q1)
                + 2.0f * mz * (0.5f - q1q1 - q2q2);


        float s0 = -2.0f * q2 * (2.0f * q1q3 - 2.0f * q0q2 - ax)
                + 2.0f * q1 * (2.0f * q0q1 + 2.0f * q2q3 - ay)
                - 2.0f * bz * q2 * (2.0f * bx * (0.5f - q2q2 - q3q3) + 2.0f * bz * (q1q3 - q0q2) - mx)
                + (-2.0f * bx * q3 + 2.0f * bz * q1) * (2.0f * bx * (q1q2 - q0q3) + 2.0f * bz * (q0q1 + q2q3) - my)
                + 2.0f * bx * q2 * (2.0f * bx * (q0q2 + q1q3) + 2.0f * bz * (0.5f - q1q1 - q2q2) - mz);
        float s1 = 2.0f * q3 * (2.0f * q1q3 - 2.0f * q0q2 - ax)
                + 2.0f * q0 * (2.0f * q0q1 + 2.0f * q2q3 - ay)
                - 4.0f * q1 * (1.0f - 2.0f * q1q1 - 2.0f * q2q2 - az)
                + 2.0f * bz * q3 * (2.0f * bx * (0.5f - q2q2 - q3q3) + 2.0f * bz * (q1q3 - q0q2) - mx)
                + (2.0f * bx * q2 + 2.0f * bz * q0) * (2.0f * bx * (q1q2 - q0q3) + 2.0f * bz * (q0q1 + q2q3) - my)
                + (2.0f * bx * q3 - 4.0f * bz * q1) * (2.0f * bx * (q0q2 + q1q3) + 2.0f * bz * (0.5f - q1q1 - q2q2) - mz);
        float s2 = -2.0f * q0 * (2.0f * q1q3 - 2.0f * q0q2 - ax)
                + 2.0f * q3 * (2.0f * q0q1 + 2.0f * q2q3 - ay)
                - 4.0f * q2 * (1.0f - 2.0f * q1q1 - 2.0f * q2q2 - az)
                + (-4.0f * bx * q2 - 2.0f * bz * q0) * (2.0f * bx * (0.5f - q2q2 - q3q3) + 2.0f * bz * (q1q3 - q0q2) - mx)
                + (2.0f * bx * q1 + 2.0f * bz * q3) * (2.0f * bx * (q1q2 - q0q3) + 2.0f * bz * (q0q1 + q2q3) - my)
                + (2.0f * bx * q0 - 4.0f * bz * q2) * (2.0f * bx * (q0q2 + q1q3) + 2.0f * bz * (0.5f - q1q1 - q2q2) - mz);
        float s3 = 2.0f * q1 * (2.0f * q1q3 - 2.0f * q0q2 - ax)
                + 2.0f * q2 * (2.0f * q0q1 + 2.0f * q2q3 - ay)
                + (-4.0f * bx * q3 + 2.0f * bz * q1) * (2.0f * bx * (0.5f - q2q2 - q3q3) + 2.0f * bz * (q1q3 - q0q2) - mx)
                + (-2.0f * bx * q0 + 2.0f * bz * q2) * (2.0f * bx * (q1q2 - q0q3) + 2.0f * bz * (q0q1 + q2q3) - my)
                + 2.0f * bx * q1 * (2.0f * bx * (q0q2 + q1q3) + 2.0f * bz * (0.5f - q1q1 - q2q2) - mz);

        float stepNorm = invSqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3);
        if (!Float.isNaN(stepNorm) && !Float.isInfinite(stepNorm)) {
            s0 *= stepNorm;
            s1 *= stepNorm;
            s2 *= stepNorm;
            s3 *= stepNorm;
        }

        float qDot0 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz) - adaptiveBeta * s0;
        float qDot1 = 0.5f * (q0 * gx + q2 * gz - q3 * gy) - adaptiveBeta * s1;
        float qDot2 = 0.5f * (q0 * gy - q1 * gz + q3 * gx) - adaptiveBeta * s2;
        float qDot3 = 0.5f * (q0 * gz + q1 * gy - q2 * gx) - adaptiveBeta * s3;

        q0 += qDot0 * 0.01f;
        q1 += qDot1 * 0.01f;
        q2 += qDot2 * 0.01f;
        q3 += qDot3 * 0.01f;

        normalizeQuaternion();
    }

    private void normalizeQuaternion() {
        float quaternionNorm = invSqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3);
        if (Float.isNaN(quaternionNorm) || Float.isInfinite(quaternionNorm)) {
            return;
        }
        q0 *= quaternionNorm;
        q1 *= quaternionNorm;
        q2 *= quaternionNorm;
        q3 *= quaternionNorm;
    }

    private static float invSqrt(float x) {
        return 1.0f / (float) Math.sqrt(x);
    }

    public void getQuaternion(float[] q) {
        q[0] = q0;
        q[1] = q1;
        q[2] = q2;
        q[3] = q3;
    }

    public int getMagneticAnomalyCount() {
        return magAnomalyCount;
    }
}


