package com.openpositioning.PositionMe.fusion;

/**
 * Lightweight Kalman-style output smoother for PF poses.
 *
 * It does NOT modify the internal particle set.
 * It only smooths the exported/displayed fused pose.
 *
 * State:
 * - x, y in local meters
 * - theta in radians
 *
 * Inputs per update:
 * - raw PF pose
 * - PF confidence
 * - step distance
 * - absolute heading change
 * - whether a strong absolute observation was accepted this update
 */
public class KalmanPoseSmoother {
    private boolean initialised = false;

    private double x;
    private double y;
    private double theta;

    // State uncertainty
    private double pPos = 1.0;
    private double pTheta = Math.toRadians(15.0) * Math.toRadians(15.0);

    public void reset() {
        initialised = false;
        x = 0.0;
        y = 0.0;
        theta = 0.0;
        pPos = 1.0;
        pTheta = Math.toRadians(15.0) * Math.toRadians(15.0);
    }

    public SmoothedPose update(
            double rawX,
            double rawY,
            double rawTheta,
            double confidence,
            double stepDistance,
            double deltaThetaAbs,
            boolean strongAbsoluteObservationAccepted
    ) {
        if (!initialised) {
            initialised = true;
            x = rawX;
            y = rawY;
            theta = rawTheta;
            return new SmoothedPose(x, y, theta);
        }

        double qPos = 0.02 + 0.20 * stepDistance * stepDistance;
        double qTheta = Math.toRadians(1.0) * Math.toRadians(1.0)
                + 0.50 * deltaThetaAbs * deltaThetaAbs;

        pPos += qPos;
        pTheta += qTheta;

        double clampedConf = clamp(confidence, 0.0, 1.0);

        double rPos = lerp(9.0, 0.5, clampedConf);
        double rTheta = lerp(
                Math.toRadians(20.0) * Math.toRadians(20.0),
                Math.toRadians(3.0) * Math.toRadians(3.0),
                clampedConf
        );

        if (!strongAbsoluteObservationAccepted) {
            rPos *= 1.5;
            rTheta *= 1.2;
        }

        double kPos = pPos / (pPos + rPos);
        double kTheta = pTheta / (pTheta + rTheta);

        x = x + kPos * (rawX - x);
        y = y + kPos * (rawY - y);
        theta = wrapAngle(theta + kTheta * wrapAngle(rawTheta - theta));

        pPos = (1.0 - kPos) * pPos;
        pTheta = (1.0 - kTheta) * pTheta;

        return new SmoothedPose(x, y, theta);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }

    private static double wrapAngle(double angle) {
        while (angle > Math.PI) angle -= 2.0 * Math.PI;
        while (angle < -Math.PI) angle += 2.0 * Math.PI;
        return angle;
    }

    public static class SmoothedPose {
        public final double x;
        public final double y;
        public final double theta;

        public SmoothedPose(double x, double y, double theta) {
            this.x = x;
            this.y = y;
            this.theta = theta;
        }
    }
}
