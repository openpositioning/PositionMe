package com.openpositioning.PositionMe.sensors;

public class kalmanfilter {
    private float theta;  // Estimated angle (θ)
    private float thetaDotBias; // Bias in angular rate (θ̇b)

    private float p00, p01, p10, p11; // Error covariance matrix

    // Constructor
    public kalmanfilter() {
        this.theta = 0.0f;  // Initial estimated angle
        this.thetaDotBias = 0.0f; // Initial bias

        this.p00 = 1.0f;  // Initial error covariance
        this.p01 = 0.0f;
        this.p10 = 0.0f;
        this.p11 = 1.0f;
    }

    public float update(float accMagAngle, float gyroAngle, float dt) {
        // Prediction step
        theta += gyroAngle * dt;
        theta -= thetaDotBias * dt;

        // Process noise variance for the angle
        float qAngle = 0.01f;
        p00 += dt * (dt * p11 - p01 - p10 + qAngle);
        p01 -= dt * p11;
        p10 -= dt * p11;

        // Process noise variance for the gyro bias
        float qBias = 0.003f;
        p11 += qBias * dt;

        // Update step
        float z = accMagAngle - theta;

        // Measurement noise variance
        float rMeasure = 0.01f;
        float k0 = p00 / (p00 + rMeasure);
        float k1 = p10 / (p00 + rMeasure);

        theta += k0 * z;
        thetaDotBias += k1 * z;

        // Update covariance matrix
        float tempP00 = p00;
        float tempP01 = p01;

        p00 -= k0 * tempP00;
        p01 -= k0 * tempP01;
        p10 -= k1 * tempP00;
        p11 -= k1 * tempP01;

        return theta;
    }
}
