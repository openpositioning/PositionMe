package com.openpositioning.PositionMe.sensors.fusion;

import static com.openpositioning.PositionMe.BuildConstants.DEBUG;

import android.util.Log;

import java.util.Random;

/**
 * Sequential Importance Resampling (SIR) particle filter for indoor positioning.
 *
 * <h3>Algorithm cycle (per step):</h3>
 * <ol>
 *   <li><b>Predict</b> — propagate particles with noisy PDR displacement.</li>
 *   <li><b>Update</b>  — re-weight particles using WiFi / GNSS observations.</li>
 *   <li><b>Estimate</b> — compute weighted mean position &amp; uncertainty.</li>
 *   <li><b>Resample</b> — residual resampling when N_eff drops below threshold.</li>
 * </ol>
 *
 * <p>The filter works in a local East-North-Up (ENU) frame so that PDR
 * displacements can be handled in metres.</p>
 */
public class ParticleFilter {

    private static final String TAG = "ParticleFilter";

    // ---- tuneable constants ---------------------------------------------------

    /** Standard deviation added to each PDR step length (metres). */
    private static final double STEP_LENGTH_STD = 0.15;

    /** Standard deviation added to each PDR heading (radians ≈ 8°). */
    private static final double HEADING_STD = Math.toRadians(8);

    /** Assumed uncertainty of a WiFi position fix (metres). */
    private static final double WIFI_POSITION_STD = 8.0;

    /** Assumed uncertainty of a GNSS position fix (metres). */
    private static final double GNSS_POSITION_STD = 15.0;

    /**
     * Fraction of total particles below which resampling is triggered.
     * N_eff = 1 / Σ(w²); resample when N_eff < threshold × N.
     */
    private static final double NEFF_THRESHOLD = 0.3;

    // ---- state ---------------------------------------------------------------

    private Particle[] particles;
    private final int numParticles;
    private final Random random;

    private double estimatedX;
    private double estimatedY;
    private int estimatedFloor;
    private double uncertainty;
    private boolean initialized = false;

    // ---- constructor ---------------------------------------------------------

    /**
     * Creates a particle filter with the specified number of particles.
     *
     * @param numParticles number of particles to use
     */
    public ParticleFilter(int numParticles) {
        this.numParticles = numParticles;
        this.particles = new Particle[numParticles];
        this.random = new Random();
    }

    // ---- lifecycle -----------------------------------------------------------

    /**
     * Initialises particles in a Gaussian cloud around an initial position.
     *
     * @param x     easting (metres, ENU)
     * @param y     northing (metres, ENU)
     * @param floor initial floor index
     * @param spread standard deviation of the initial cloud (metres)
     */
    public void initialize(double x, double y, int floor, double spread) {
        double uniformWeight = 1.0 / numParticles;
        for (int i = 0; i < numParticles; i++) {
            double px = x + random.nextGaussian() * spread;
            double py = y + random.nextGaussian() * spread;
            particles[i] = new Particle(px, py, floor, uniformWeight);
        }
        estimatedX = x;
        estimatedY = y;
        estimatedFloor = floor;
        uncertainty = spread;
        initialized = true;
        if (DEBUG) Log.i(TAG, String.format("Initialised %d particles at (%.2f, %.2f) floor %d",
                numParticles, x, y, floor));
    }

    // ---- prediction ----------------------------------------------------------

    /**
     * Moves every particle according to PDR displacement with added noise.
     *
     * @param stepLength stride length in metres (from Weiberg / settings)
     * @param heading    azimuth in radians, 0 = North, clockwise positive
     */
    public void predict(double stepLength, double heading) {
        predict(stepLength, heading, HEADING_STD);
    }

    /**
     * Moves every particle with a caller-specified heading noise.
     * Used by the wall-collision search mode to temporarily widen the
     * particle heading spread when most particles are stuck against walls.
     *
     * @param headingStd heading noise standard deviation in radians
     */
    public void predict(double stepLength, double heading, double headingStd) {
        if (!initialized) return;

        for (Particle p : particles) {
            double noisyStep = stepLength + random.nextGaussian() * STEP_LENGTH_STD;
            double noisyHeading = heading + random.nextGaussian() * headingStd;
            noisyStep = Math.max(0.05, noisyStep);

            p.x += noisyStep * Math.sin(noisyHeading);
            p.y += noisyStep * Math.cos(noisyHeading);
        }

        normalizeWeights();
        estimatePosition();
    }

    // ---- observation updates -------------------------------------------------

    /**
     * Updates particle weights using a position observation (WiFi or GNSS).
     * The likelihood is an isotropic 2-D Gaussian centred on the observation.
     */
    private void updateWithPosition(double obsX, double obsY, double stdDev) {
        if (!initialized) return;

        double variance2 = 2.0 * stdDev * stdDev;
        for (Particle p : particles) {
            double dx = p.x - obsX;
            double dy = p.y - obsY;
            double distSq = dx * dx + dy * dy;
            p.weight *= Math.exp(-distSq / variance2);
        }

        normalizeWeights();
        estimatePosition();
        resampleIfNeeded();
    }

    /** Feed a WiFi-derived position observation (in ENU metres). */
    public void updateWifi(double obsX, double obsY) {
        updateWithPosition(obsX, obsY, WIFI_POSITION_STD);
    }

    /** Feed a GNSS-derived position observation (in ENU metres). */
    public void updateGnss(double obsX, double obsY) {
        updateWithPosition(obsX, obsY, GNSS_POSITION_STD);
    }

    /** Feed an observation with caller-specified uncertainty (dynamic sigma). */
    public void updateWithDynamicSigma(double obsX, double obsY, double sigma) {
        updateWithPosition(obsX, obsY, sigma);
    }

    /** Feed a user manual correction with custom tight uncertainty. */
    public void updateWithManualCorrection(double obsX, double obsY, double stdDev) {
        updateWithPosition(obsX, obsY, stdDev);
    }

    // ---- floor handling ------------------------------------------------------

    /**
     * Adjusts particle floors when a floor change is detected (barometer).
     */
    public void updateFloor(int newFloor) {
        if (!initialized) return;
        for (Particle p : particles) {
            p.floor = newFloor;
        }

        normalizeWeights();
        estimatePosition();
    }

    // ---- estimation ----------------------------------------------------------

    /** Weighted mean of particle positions → fused estimate + uncertainty. */
    private void estimatePosition() {
        double sumX = 0, sumY = 0, totalW = 0;
        double[] floorWeights = new double[20];

        for (Particle p : particles) {
            sumX += p.x * p.weight;
            sumY += p.y * p.weight;
            totalW += p.weight;
            int fi = Math.max(0, Math.min(19, p.floor));
            floorWeights[fi] += p.weight;
        }

        if (totalW > 0) {
            estimatedX = sumX / totalW;
            estimatedY = sumY / totalW;
        }

        // Floor = highest total weight, but only switch if confident
        double maxW = 0, secondW = 0;
        int candidateFloor = estimatedFloor;
        for (int i = 0; i < floorWeights.length; i++) {
            if (floorWeights[i] > maxW) {
                secondW = maxW;
                maxW = floorWeights[i];
                candidateFloor = i;
            } else if (floorWeights[i] > secondW) {
                secondW = floorWeights[i];
            }
        }
        // Only switch floor if dominant (>0.6 probability or >0.2 margin over second)
        if (maxW > 0.6 || (maxW - secondW) > 0.2) {
            estimatedFloor = candidateFloor;
        }

        // Weighted standard deviation as uncertainty measure
        double sumSqDist = 0;
        for (Particle p : particles) {
            double dx = p.x - estimatedX;
            double dy = p.y - estimatedY;
            sumSqDist += (dx * dx + dy * dy) * p.weight;
        }
        uncertainty = Math.sqrt(sumSqDist / Math.max(totalW, 1e-10));
    }

    // ---- resampling ----------------------------------------------------------

    private void resampleIfNeeded() {
        double neff = calculateNeff();
        if (neff < NEFF_THRESHOLD * numParticles) {
            residualResample();
        }
    }

    private double calculateNeff() {
        double sumWsq = 0;
        for (Particle p : particles) {
            sumWsq += p.weight * p.weight;
        }
        return 1.0 / Math.max(sumWsq, 1e-30);
    }

    /**
     * Residual resampling: deterministic copies for high-weight particles,
     * then systematic resampling over residual weights for the rest.
     * Preserves more diversity than pure multinomial resampling.
     */
    private void residualResample() {
        Particle[] resampled = new Particle[numParticles];
        int idx = 0;

        int[] copies = new int[numParticles];
        double[] residuals = new double[numParticles];
        int deterministicTotal = 0;

        for (int i = 0; i < numParticles; i++) {
            copies[i] = (int) Math.floor(numParticles * particles[i].weight);
            residuals[i] = numParticles * particles[i].weight - copies[i];
            deterministicTotal += copies[i];
        }

        // Deterministic part
        for (int i = 0; i < numParticles; i++) {
            for (int j = 0; j < copies[i] && idx < numParticles; j++) {
                resampled[idx++] = particles[i].copy();
            }
        }

        // Stochastic part over residuals
        int remaining = numParticles - idx;
        if (remaining > 0) {
            double sumRes = 0;
            for (double r : residuals) sumRes += r;

            if (sumRes > 0) {
                double[] cdf = new double[numParticles];
                cdf[0] = residuals[0] / sumRes;
                for (int i = 1; i < numParticles; i++) {
                    cdf[i] = cdf[i - 1] + residuals[i] / sumRes;
                }

                double step = 1.0 / remaining;
                double u = random.nextDouble() * step;
                int ci = 0;
                for (int i = 0; i < remaining && idx < numParticles; i++) {
                    while (ci < numParticles - 1 && u > cdf[ci]) ci++;
                    resampled[idx++] = particles[ci].copy();
                    u += step;
                }
            }
        }

        // Edge-case fill
        while (idx < numParticles) {
            resampled[idx++] = particles[random.nextInt(numParticles)].copy();
        }

        // Reset all weights to uniform
        double w = 1.0 / numParticles;
        for (Particle p : resampled) {
            p.weight = w;
        }
        particles = resampled;
    }

    // ---- helpers -------------------------------------------------------------

    private void normalizeWeights() {
        double total = 0;
        for (Particle p : particles) total += p.weight;
        if (total > 1e-30) {
            for (Particle p : particles) p.weight /= total;
        } else {
            double w = 1.0 / numParticles;
            for (Particle p : particles) p.weight = w;
        }
    }

    // ---- accessors -----------------------------------------------------------

    /** Returns the weighted-mean easting estimate in metres (ENU). */
    public double getEstimatedX()    { return estimatedX; }

    /** Returns the weighted-mean northing estimate in metres (ENU). */
    public double getEstimatedY()    { return estimatedY; }

    /** Returns the estimated floor index. */
    public int    getEstimatedFloor() { return estimatedFloor; }

    /** Returns the current position uncertainty (weighted std dev) in metres. */
    public double getUncertainty()   { return uncertainty; }

    /** Returns {@code true} if the filter has been initialised with particles. */
    public boolean isInitialized()   { return initialized; }

    /** Returns the internal particle array. */
    public Particle[] getParticles() { return particles; }

    /** Returns the total number of particles in the filter. */
    public int getNumParticles()     { return numParticles; }
}
