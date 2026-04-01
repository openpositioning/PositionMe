package com.openpositioning.PositionMe.utils;

/**
 * Lightweight 2D constant-velocity Kalman smoother for map display.
 *
 * <p>The particle filter remains the primary fusion engine. This smoother only stabilises
 * the rendered track so short-term drift and single-step spikes do not directly become
 * visible as large path defects.</p>
 */
final class AdaptivePlanarKalmanFilter {

    private final ConstantVelocityKalman1D eastFilter = new ConstantVelocityKalman1D();
    private final ConstantVelocityKalman1D northFilter = new ConstantVelocityKalman1D();
    private boolean initialized;

    void reset() {
        eastFilter.reset();
        northFilter.reset();
        initialized = false;
    }

    void reset(double easting, double northing, long timestampMillis) {
        eastFilter.reset(easting, timestampMillis);
        northFilter.reset(northing, timestampMillis);
        initialized = true;
    }

    void update(double easting,
                double northing,
                double measurementSigmaMeters,
                boolean moving,
                long timestampMillis) {
        double measurementVariance = Math.max(0.20, measurementSigmaMeters * measurementSigmaMeters);
        if (!initialized) {
            reset(easting, northing, timestampMillis);
            return;
        }
        eastFilter.step(easting, measurementVariance, moving, timestampMillis);
        northFilter.step(northing, measurementVariance, moving, timestampMillis);
    }

    double getEasting() {
        return eastFilter.getPosition();
    }

    double getNorthing() {
        return northFilter.getPosition();
    }

    private static final class ConstantVelocityKalman1D {

        private static final double MIN_DT_SECONDS = 0.05;
        private static final double MAX_DT_SECONDS = 1.50;
        private static final double MOVING_ACCELERATION_NOISE = 2.4;
        private static final double STATIONARY_ACCELERATION_NOISE = 0.45;
        private static final double STATIONARY_VELOCITY_DAMPING = 0.35;

        private boolean initialized;
        private double position;
        private double velocity;
        private double p00;
        private double p01;
        private double p10;
        private double p11;
        private long lastTimestampMillis;

        void reset() {
            initialized = false;
            position = 0d;
            velocity = 0d;
            p00 = 0d;
            p01 = 0d;
            p10 = 0d;
            p11 = 0d;
            lastTimestampMillis = 0L;
        }

        void reset(double measurement, long timestampMillis) {
            initialized = true;
            position = measurement;
            velocity = 0d;
            p00 = 4.0;
            p01 = 0d;
            p10 = 0d;
            p11 = 3.0;
            lastTimestampMillis = timestampMillis;
        }

        void step(double measurement,
                  double measurementVariance,
                  boolean moving,
                  long timestampMillis) {
            if (!initialized) {
                reset(measurement, timestampMillis);
                return;
            }

            double dtSeconds = Math.max(
                    MIN_DT_SECONDS,
                    Math.min(
                            MAX_DT_SECONDS,
                            (timestampMillis - lastTimestampMillis) / 1000d
                    )
            );
            predict(dtSeconds, moving);
            update(measurement, measurementVariance);
            lastTimestampMillis = timestampMillis;
        }

        double getPosition() {
            return position;
        }

        private void predict(double dtSeconds, boolean moving) {
            position += velocity * dtSeconds;
            if (!moving) {
                velocity *= STATIONARY_VELOCITY_DAMPING;
            }

            double accelNoise = moving
                    ? MOVING_ACCELERATION_NOISE
                    : STATIONARY_ACCELERATION_NOISE;
            double dt2 = dtSeconds * dtSeconds;
            double dt3 = dt2 * dtSeconds;
            double dt4 = dt2 * dt2;

            double newP00 = p00 + dtSeconds * (p01 + p10) + dt2 * p11 + 0.25 * dt4 * accelNoise;
            double newP01 = p01 + dtSeconds * p11 + 0.5 * dt3 * accelNoise;
            double newP10 = p10 + dtSeconds * p11 + 0.5 * dt3 * accelNoise;
            double newP11 = p11 + dt2 * accelNoise;

            p00 = newP00;
            p01 = newP01;
            p10 = newP10;
            p11 = newP11;
        }

        private void update(double measurement, double measurementVariance) {
            double innovation = measurement - position;
            double s = p00 + measurementVariance;
            if (s <= 1e-9) {
                return;
            }

            double k0 = p00 / s;
            double k1 = p10 / s;

            double oldP00 = p00;
            double oldP01 = p01;

            position += k0 * innovation;
            velocity += k1 * innovation;

            p00 = (1d - k0) * oldP00;
            p01 = (1d - k0) * oldP01;
            p10 -= k1 * oldP00;
            p11 -= k1 * oldP01;
        }
    }
}
