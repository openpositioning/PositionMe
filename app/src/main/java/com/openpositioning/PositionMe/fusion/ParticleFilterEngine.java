package com.openpositioning.PositionMe.fusion;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Core particle filter engine.
 *
 * <p>This class owns the particle set and implements the standard PF loop:
 *
 * <ol>
 *   <li>initialise()</li>
 *   <li>predict(deltaS, deltaTheta)</li>
 *   <li>update(observation)</li>
 *   <li>estimate()</li>
 * </ol>
 *
 * <p>State per particle:
 * - x, y: local planar position in meters
 * - theta: heading in radians
 * - floor: discrete floor hypothesis
 * - weight: particle importance weight
 *
 * <p>Heading convention:
 * - theta is stored in Android-style azimuth convention:
 *   0 = north, +pi/2 = east, pi = south, -pi/2 = west
 *
 * <p>For motion projection into Cartesian x/y:
 * - x positive = east
 * - y positive = north
 * so we convert with:
 *   motionHeading = pi/2 - theta
 */
public class ParticleFilterEngine {

    private static final String TAG = "ParticleFilterEngine";

    /** The active particle set representing the belief distribution. */
    private final List<Particle> particles = new ArrayList<>();

    /** Random generator used for motion noise, initial spread, and resampling jitter. */
    private final Random rng = new Random();

    // -------------------------
    // Tunable filter parameters
    // -------------------------

    private final int particleCount;
    private final double sigmaStep;
    private final double sigmaTheta;
    private final double sigmaWifi;
    private final double sigmaGnss;
    private final double initPosStd;
    private final double initHeadingStd;
    private final double resampleRatio;
    private final double sigmaRegPos;
    private final double sigmaRegTheta;

    // -------------------------
    // Diagnostics / statistics
    // -------------------------

    /** Effective sample size after the latest update step. */
    private double lastNeff = 0.0;

    /** Whether the last update triggered resampling. */
    private boolean lastResampled = false;

    public ParticleFilterEngine(ParticleFilterConfig cfg) {
        this.particleCount = cfg.particleCount;
        this.sigmaStep = cfg.sigmaStep;
        this.sigmaTheta = cfg.sigmaThetaRad;
        this.sigmaWifi = cfg.sigmaWifi;
        this.sigmaGnss = cfg.sigmaGnss;
        this.initPosStd = cfg.initPosStd;
        this.initHeadingStd = cfg.initHeadingStdRad;
        this.resampleRatio = cfg.resampleRatio;
        this.sigmaRegPos = cfg.sigmaRegPos;
        this.sigmaRegTheta = cfg.sigmaRegThetaRad;
    }

    /**
     * Initialises the particle cloud around a starting pose hypothesis.
     *
     * <p>All particles begin on the same floor, but position and heading are
     * spread using Gaussian noise to represent initial uncertainty.
     *
     * @param x initial x coordinate in local frame
     * @param y initial y coordinate in local frame
     * @param theta initial heading in radians
     * @param floor initial floor estimate
     */
    public void initialise(double x, double y, double theta, int floor) {
        particles.clear();

        for (int i = 0; i < particleCount; i++) {
            double px = x + rng.nextGaussian() * initPosStd;
            double py = y + rng.nextGaussian() * initPosStd;
            double pt = wrapAngle(theta + rng.nextGaussian() * initHeadingStd);

            particles.add(new Particle(px, py, pt, floor, 1.0 / particleCount));
        }

        lastNeff = particleCount;
        lastResampled = false;

        Log.d(TAG,
                "PF engine initialised"
                        + " | particleCount=" + particleCount
                        + ", initPosStd=" + initPosStd
                        + ", initHeadingStdDeg=" + Math.toDegrees(initHeadingStd));
    }

    /**
     * @return true once particles have been created
     */
    public boolean isInitialised() {
        return !particles.isEmpty();
    }

    /**
     * Motion prediction step.
     *
     * <p>This propagates each particle forward according to:
     * - a distance increment deltaS
     * - a heading increment deltaTheta
     *
     * <p>Noise is added independently to translation and heading to represent
     * uncertainty in the motion model.
     *
     * @param deltaS motion increment in meters
     * @param deltaTheta heading increment in radians
     */
    public void predict(double deltaS, double deltaTheta) {
        for (Particle particle : particles) {
            // Add stochastic noise to the motion command
            double ds = deltaS + rng.nextGaussian() * sigmaStep;
            double dTheta = deltaTheta + rng.nextGaussian() * sigmaTheta;

            // Update heading first
            particle.theta = wrapAngle(particle.theta + dTheta);

            // Convert Android-style heading into x/y projection angle
            double motionHeading = (Math.PI / 2.0) - particle.theta;

            // Move particle in local Cartesian frame
            particle.x += ds * Math.cos(motionHeading);
            particle.y += ds * Math.sin(motionHeading);
        }
    }

    /**
     * Measurement update step.
     *
     * <p>Each available observation contributes multiplicatively to the
     * particle weight:
     * - Wi-Fi position
     * - Wi-Fi floor
     * - GNSS position
     *
     * <p>After reweighting:
     * - weights are normalised
     * - effective sample size is computed
     * - resampling is triggered if degeneracy is too high
     *
     * @param observation observation bundle for this update cycle
     */
    public void update(ParticleFilterObservation observation) {
        if (particles.isEmpty()) {
            return;
        }

        double weightSum = 0.0;

        for (Particle particle : particles) {
            double w = 1.0;

            // -------------------------
            // Wi-Fi position likelihood
            // -------------------------
            if (observation.getWifiX() != null && observation.getWifiY() != null) {
                double dx = particle.x - observation.getWifiX();
                double dy = particle.y - observation.getWifiY();

                // Gaussian likelihood based on local planar distance
                w *= gaussian2D(dx, dy, sigmaWifi);

                // Penalise particles that disagree with Wi-Fi floor estimate
                if (observation.getWifiFloor() != null
                        && particle.floor != observation.getWifiFloor()) {
                    w *= 0.10;
                }
            }

            // -------------------------
            // GNSS position likelihood
            // -------------------------
//            if (observation.getGnssX() != null && observation.getGnssY() != null) {
//                double dx = particle.x - observation.getGnssX();
//                double dy = particle.y - observation.getGnssY();
//                w *= gaussian2D(dx, dy, sigmaGnss);
//            }

            particle.weight = w;
            weightSum += w;
        }

        normaliseWeights(weightSum);

        lastNeff = effectiveSampleSize();
        lastResampled = false;

        // Resample if too few particles carry meaningful weight
        if (lastNeff < particleCount * resampleRatio) {
            systematicResample();
            regularizeParticles();
            lastResampled = true;
        }
    }

    /**
     * Computes the current fused pose estimate from the weighted particle set.
     *
     * <p>Position is the weighted mean of x and y.
     * Heading is the circular weighted mean of theta.
     * Floor is the weighted average rounded to nearest integer.
     *
     * @param converter converts local x/y back into LatLng
     * @return fused pose estimate
     */
    public FusedPose estimate(CoordinateConverter converter) {
        if (particles.isEmpty()) {
            return null;
        }

        double x = 0.0;
        double y = 0.0;
        double weightedFloor = 0.0;
        double sinSum = 0.0;
        double cosSum = 0.0;

        for (Particle particle : particles) {
            x += particle.x * particle.weight;
            y += particle.y * particle.weight;
            weightedFloor += particle.floor * particle.weight;
            sinSum += Math.sin(particle.theta) * particle.weight;
            cosSum += Math.cos(particle.theta) * particle.weight;
        }

        double theta = Math.atan2(sinSum, cosSum);
        int floor = (int) Math.round(weightedFloor);

        // Use N_eff as a simple confidence proxy
        float confidence = (float) Math.min(1.0, lastNeff / particleCount);

        return new FusedPose(
                x,
                y,
                theta,
                floor,
                converter.localToLatLng(x, y),
                confidence
        );
    }

    /**
     * Computes effective sample size.
     *
     * <p>A lower value means more degeneracy, i.e. fewer particles dominate
     * the total weight distribution.
     */
    public double effectiveSampleSize() {
        double sumSquares = 0.0;
        for (Particle particle : particles) {
            sumSquares += particle.weight * particle.weight;
        }
        return 1.0 / Math.max(sumSquares, 1e-12);
    }

    public double getLastNeff() {
        return lastNeff;
    }

    public boolean wasResampledLastStep() {
        return lastResampled;
    }

    /**
     * Gaussian likelihood in local 2D position space.
     *
     * <p>This is used for Wi-Fi and GNSS weighting.
     */
    private double gaussian2D(double dx, double dy, double sigma) {
        double d2 = dx * dx + dy * dy;
        double s2 = sigma * sigma;

        if (s2 < 1e-12) {
            return 1.0;
        }

        return Math.exp(-0.5 * d2 / s2);
    }

    /**
     * Normalises particle weights so they sum to 1.
     *
     * <p>If all weights collapse numerically, recover by assigning uniform
     * weights instead of producing NaNs.
     */
    private void normaliseWeights(double weightSum) {
        if (weightSum < 1e-12) {
            double uniformWeight = 1.0 / particleCount;
            for (Particle particle : particles) {
                particle.weight = uniformWeight;
            }
            return;
        }

        for (Particle particle : particles) {
            particle.weight /= weightSum;
        }
    }

    /**
     * Adds a small amount of random jitter after resampling.
     *
     * <p>This prevents the particle set from collapsing into too many exact
     * copies of the same hypothesis.
     */
    private void regularizeParticles() {
        for (Particle particle : particles) {
            particle.x += rng.nextGaussian() * sigmaRegPos;
            particle.y += rng.nextGaussian() * sigmaRegPos;
            particle.theta = wrapAngle(particle.theta + rng.nextGaussian() * sigmaRegTheta);
        }
    }

    /**
     * Systematic resampling.
     *
     * <p>This produces a new particle set according to the current weights,
     * then resets all copied particles to equal weight.
     */
    private void systematicResample() {
        List<Particle> resampled = new ArrayList<>(particleCount);
        double[] cdf = new double[particleCount];

        cdf[0] = particles.get(0).weight;
        for (int i = 1; i < particleCount; i++) {
            cdf[i] = cdf[i - 1] + particles.get(i).weight;
        }

        double step = 1.0 / particleCount;
        double u0 = rng.nextDouble() * step;
        int cdfIndex = 0;

        for (int i = 0; i < particleCount; i++) {
            double threshold = u0 + i * step;

            while (cdfIndex < particleCount - 1 && cdf[cdfIndex] < threshold) {
                cdfIndex++;
            }

            Particle copy = particles.get(cdfIndex).copy();
            copy.weight = 1.0 / particleCount;
            resampled.add(copy);
        }

        particles.clear();
        particles.addAll(resampled);
    }

    /**
     * Wraps angle to [-pi, pi].
     */
    private double wrapAngle(double angle) {
        while (angle > Math.PI) {
            angle -= 2.0 * Math.PI;
        }
        while (angle < -Math.PI) {
            angle += 2.0 * Math.PI;
        }
        return angle;
    }
}