package com.openpositioning.PositionMe.positioning;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Bootstrap particle filter for sensor fusion in easting/northing (ENU) space.
 *
 * <p>Implements the predict–update–resample cycle used by {@link FusionManager}
 * to fuse PDR step detections with absolute GNSS and WiFi observations.
 * Operates entirely in local metres around a WGS84 reference point managed
 * by {@link CoordinateUtils}.</p>
 *
 * <p>Key design choices:</p>
 * <ul>
 *   <li>Systematic resampling with ESS threshold at 50% for particle diversity</li>
 *   <li>Gaussian likelihood weighting for absolute observations</li>
 *   <li>Additive motion noise proportional to step length for realistic spread</li>
 *   <li>WiFi sigma tuned to 10m to avoid over-correcting on noisy indoor fixes</li>
 * </ul>
 *
 * @see FusionManager which owns and drives this filter
 */
public class ParticleFilter {

    /** Single hypothesis in the particle cloud. */
    private static class Particle {
        double east;
        double north;
        double weight;

        Particle(double east, double north, double weight) {
            this.east = east;
            this.north = north;
            this.weight = weight;
        }

        Particle copy() {
            return new Particle(east, north, weight);
        }
    }

    // ── Tuning constants ─────────────────────────────────────────────────────
    private static final double DEFAULT_INIT_SIGMA = 4.0;

    /** Base random walk noise added per step (metres). */
    private static final double MOTION_NOISE_BASE = 0.12;
    /** Additional noise scaled by step length (metres per metre). */
    private static final double MOTION_NOISE_STEP_SCALE = 0.08;
    /** Gaussian noise on the step length itself (metres). */
    private static final double STEP_NOISE_SIGMA = 0.05;
    /** Gaussian noise on the compass heading (radians). */
    private static final double HEADING_NOISE_SIGMA = 0.05;

    /** Minimum sigma for GNSS observations (metres). */
    private static final double GNSS_SIGMA_MIN = 3.0;
    /** Sigma for WiFi observations — tuned higher to avoid over-correction. */
    private static final double WIFI_SIGMA_DEFAULT = 10.0;

    // ── State ────────────────────────────────────────────────────────────────
    private final Random rng = new Random(42);
    private final List<Particle> particles = new ArrayList<>();
    private final int particleCount;
    private boolean initialised = false;

    /**
     * Creates a new particle filter with the specified number of particles.
     *
     * @param particleCount number of particles to maintain
     */
    public ParticleFilter(int particleCount) {
        this.particleCount = particleCount;
    }

    public boolean isInitialised() {
        return initialised;
    }

    /**
     * Initialises the particle cloud as a Gaussian distribution around (east, north).
     *
     * @param east         centre easting in local metres
     * @param north        centre northing in local metres
     * @param sigmaMeters  spread of the initial distribution
     */
    public void initialise(double east, double north, double sigmaMeters) {
        particles.clear();
        double sigma = Math.max(1.0, sigmaMeters > 0 ? sigmaMeters : DEFAULT_INIT_SIGMA);
        for (int i = 0; i < particleCount; i++) {
            particles.add(new Particle(
                    east + rng.nextGaussian() * sigma,
                    north + rng.nextGaussian() * sigma,
                    1.0 / particleCount
            ));
        }
        initialised = true;
    }

    // =========================================================================
    // Predict step — PDR movement model (§3.1)
    // =========================================================================

    /**
     * Propagates all particles forward by one PDR step. Each particle receives
     * independent noise on step length, heading, and position to maintain
     * diversity in the cloud.
     *
     * @param stepLength estimated step length in metres
     * @param headingRad compass heading in radians (0 = north, clockwise)
     */
    public void predict(double stepLength, double headingRad) {
        if (!initialised || stepLength <= 0.05 || Double.isNaN(stepLength) || Double.isNaN(headingRad)) {
            return;
        }

        double motionSigma = MOTION_NOISE_BASE + MOTION_NOISE_STEP_SCALE * stepLength;

        for (Particle p : particles) {
            double noisyStep = stepLength + rng.nextGaussian() * STEP_NOISE_SIGMA;
            double noisyHeading = headingRad + rng.nextGaussian() * HEADING_NOISE_SIGMA;

            double dE = noisyStep * Math.sin(noisyHeading);
            double dN = noisyStep * Math.cos(noisyHeading);

            p.east += dE + rng.nextGaussian() * motionSigma;
            p.north += dN + rng.nextGaussian() * motionSigma;
        }
    }

    // =========================================================================
    // Update steps — absolute observations (§3.1)
    // =========================================================================

    /**
     * Updates particle weights from a GNSS observation.
     *
     * @param obsEast       observed easting in local metres
     * @param obsNorth      observed northing in local metres
     * @param accuracyMeters reported GNSS accuracy
     */
    public void updateGnss(double obsEast, double obsNorth, float accuracyMeters) {
        double sigma = Math.max(GNSS_SIGMA_MIN, accuracyMeters);
        updateAbsolute(obsEast, obsNorth, sigma);
    }

    /**
     * Updates particle weights from a WiFi observation.
     *
     * @param obsEast  observed easting in local metres
     * @param obsNorth observed northing in local metres
     */
    public void updateWifi(double obsEast, double obsNorth) {
        updateAbsolute(obsEast, obsNorth, WIFI_SIGMA_DEFAULT);
    }

    /**
     * Core observation update: re-weights particles by Gaussian likelihood
     * relative to the observed position, normalises, and resamples if ESS
     * drops below 50%.
     */
    private void updateAbsolute(double obsEast, double obsNorth, double sigma) {
        if (!initialised) return;

        double sigma2 = sigma * sigma;
        double totalWeight = 0.0;

        for (Particle p : particles) {
            double dE = p.east - obsEast;
            double dN = p.north - obsNorth;
            double dist2 = dE * dE + dN * dN;
            double likelihood = Math.exp(-0.5 * dist2 / sigma2);
            p.weight *= Math.max(likelihood, 1e-12);
            totalWeight += p.weight;
        }

        // Weight collapse — reinitialise around observation
        if (totalWeight < 1e-20) {
            initialise(obsEast, obsNorth, sigma);
            return;
        }

        normalise(totalWeight);

        if (effectiveSampleSize() < particleCount * 0.5) {
            resampleSystematic();
        }
    }

    // =========================================================================
    // Resampling
    // =========================================================================

    private void normalise(double totalWeight) {
        for (Particle p : particles) {
            p.weight /= totalWeight;
        }
    }

    /** Effective sample size: 1/sum(w_i^2). Measures particle diversity. */
    private double effectiveSampleSize() {
        double sumSq = 0.0;
        for (Particle p : particles) {
            sumSq += p.weight * p.weight;
        }
        return sumSq <= 0.0 ? 0.0 : 1.0 / sumSq;
    }

    /**
     * Low-variance systematic resampling. Produces a new set of equally-weighted
     * particles drawn proportionally to the current weight distribution.
     */
    private void resampleSystematic() {
        List<Particle> resampled = new ArrayList<>(particleCount);
        double step = 1.0 / particleCount;
        double u0 = rng.nextDouble() * step;
        double cumulative = particles.get(0).weight;
        int i = 0;

        for (int m = 0; m < particleCount; m++) {
            double threshold = u0 + m * step;
            while (threshold > cumulative && i < particles.size() - 1) {
                i++;
                cumulative += particles.get(i).weight;
            }
            Particle picked = particles.get(i).copy();
            picked.weight = 1.0 / particleCount;
            resampled.add(picked);
        }

        particles.clear();
        particles.addAll(resampled);
    }

    // =========================================================================
    // Output
    // =========================================================================

    /**
     * Returns the weighted mean position of all particles.
     *
     * @return [easting, northing] in local metres, or null if uninitialised
     */
    public double[] estimate() {
        if (!initialised || particles.isEmpty()) return null;

        double east = 0.0;
        double north = 0.0;
        for (Particle p : particles) {
            east += p.east * p.weight;
            north += p.north * p.weight;
        }
        return new double[]{east, north};
    }

    /** Returns ESS/N as a confidence metric (0–1). */
    public double getConfidence() {
        if (!initialised || particles.isEmpty()) return 0.0;
        double ess = effectiveSampleSize();
        return Math.max(0.0, Math.min(1.0, ess / particleCount));
    }
}