package com.openpositioning.PositionMe.fusion;

import static com.openpositioning.PositionMe.fusion.FusionConstants.INITIAL_UNCERTAINTY_M;
import static com.openpositioning.PositionMe.fusion.FusionConstants.METRES_PER_DEG_LAT;
import static com.openpositioning.PositionMe.fusion.FusionConstants.METRES_PER_DEG_LNG_AT_EQUATOR;
import static com.openpositioning.PositionMe.fusion.FusionConstants.PARTICLE_COUNT;
import static com.openpositioning.PositionMe.fusion.FusionConstants.PARTICLE_FILTER_THRESHOLD;
import static com.openpositioning.PositionMe.fusion.FusionConstants.PDR_NOISE_STDDEV;
import static com.openpositioning.PositionMe.fusion.FusionConstants.RESAMPLE_JITTER;

import android.util.Log;
import com.google.android.gms.maps.model.LatLng;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Particle filter for indoor position estimation.
 *
 * <p>Maintains a population of weighted particles in a local East-North coordinate frame and uses
 * sequential Monte Carlo methods to estimate the user's position. Particles are propagated using
 * PDR displacement and resampled using systematic resampling when weight degeneracy is detected.
 *
 * @see Fusion for the parent fusion class that drives this filter.
 * @see FusionConstants for tuning parameters.
 */
public class ParticleFilter {
    private static final String TAG = "ParticleFilter";
    // WGS84 reference point for local EN coordinate conversion
    private double refLng, refLat;
    // Particle population
    private ArrayList<Particle> particles;
    private boolean active;
    private Random rand;
    // Current best position estimate
    private LatLng estimated_position;

    // set to active when particlefilter.start() has been called in Fusion.java
    public boolean isNotActive() {
        return !active;
    }

    /** Internal representation of a single particle with 2D position and weight. */
    private class Particle {
        double easting;
        double northing;
        double weight;

        public Particle(double easting, double northing, double weight) {
            this.easting = easting;
            this.northing = northing;
            this.weight = weight;
        }
    }

    // List of position observations from different sensor readings
    // Queued observations from GNSS and WiFi, waiting to be applied on the next PDR step
    // Each entry is {easting (m), northing (m), sigma (m)} in the local Easting/Northing frame
    private List<double[]> pendingObs = new ArrayList<>();

    public void addObservation(double easting, double northing, double sigma) {
        pendingObs.add(new double[] {easting, northing, sigma});
    }

    /**
     * Initialises the particle filter with a cloud of particles around the given position.
     *
     * <p>Sets the WGS84 reference point for coordinate conversion and distributes particles with
     * Gaussian uncertainty around the initial position.
     *
     * @param initial_pos starting position in WGS84 coordinates.
     */
    public void start(LatLng initial_pos) {
        rand = new Random();
        refLat = initial_pos.latitude;
        refLng = initial_pos.longitude;
        active = true;
        estimated_position = initial_pos;
        double[] east_north = latLngToEN(initial_pos.latitude, initial_pos.longitude);
        particles = new ArrayList<>(PARTICLE_COUNT);
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            particles.add(
                    new Particle(
                            east_north[0] + rand.nextGaussian() * INITIAL_UNCERTAINTY_M,
                            east_north[1] + rand.nextGaussian() * INITIAL_UNCERTAINTY_M,
                            1.0 / PARTICLE_COUNT));
        }
    }

    /**
     * Prediction step: propagates all particles using a PDR displacement with added process noise.
     *
     * <p>After propagation, weights are updated and systematic resampling is triggered if weight
     * degeneracy exceeds the threshold. The position estimate is updated each cycle.
     *
     * @param dx easting displacement in metres from the PDR step.
     * @param dy northing displacement in metres from the PDR step.
     */
    public void updateWithPDR(double dx, double dy) {
        if (!active || particles == null) {
            Log.w(
                    TAG,
                    "updateWithPDR called while inactive or particles null"
                            + " | active="
                            + active
                            + " particles="
                            + particles);
            return;
        }
        // Propagate each particle with PDR step + stochastic process noise
        for (Particle particle : particles) {
            particle.easting += dx + rand.nextGaussian() * PDR_NOISE_STDDEV;
            particle.northing += dy + rand.nextGaussian() * PDR_NOISE_STDDEV;
        }
        double maxWeight = updateWeights();
        if (maxWeight > PARTICLE_FILTER_THRESHOLD) {
            repopulate();
        }
        updateEstimatedPosition();
    }

    /**
     * Converts WGS84 coordinates to local East-North metres relative to the reference point.
     *
     * @param lat latitude in degrees.
     * @param lng longitude in degrees.
     * @return double array {easting, northing} in metres.
     */
    public double[] latLngToEN(double lat, double lng) {
        double e =
                (lng - refLng) * Math.cos(Math.toRadians(refLat)) * METRES_PER_DEG_LNG_AT_EQUATOR;
        double n = (lat - refLat) * METRES_PER_DEG_LAT;
        return new double[] {e, n};
    }

    /**
     * Converts local East-North metres back to WGS84 coordinates.
     *
     * @param easting east displacement in metres from reference.
     * @param northing north displacement in metres from reference.
     * @return {@link LatLng} in WGS84 coordinates.
     */
    public LatLng enToLatLng(double easting, double northing) {
        double lat = refLat + northing / METRES_PER_DEG_LAT;
        double lng =
                refLng
                        + easting
                                / (METRES_PER_DEG_LNG_AT_EQUATOR
                                        * Math.cos(Math.toRadians(refLat)));
        return new LatLng(lat, lng);
    }

    /** Stops the filter and releases the particle population. */
    public void stop() {
        this.active = false;
        if (particles != null) particles.clear();
    }

    /**
     * Update step: assigns weights to particles based on observation likelihood.
     *
     * @return the maximum particle weight after normalisation.
     */
    public double updateWeights() {
        if (pendingObs.isEmpty() || particles == null) return 0.0;

        double weightSum = 0.0;
        // Iterates through each particle and calculates difference between PDR data and recorded
        // observation data
        // Maybe want to change observation logic to only use most recent reading!
        for (Particle p : particles) {
            for (double[] obs : pendingObs) {
                // Squared distance between this particle and the observation from sensors
                double de = p.easting - obs[0];
                double dn = p.northing - obs[1];
                double distSq = de * de + dn * dn;

                // Gaussian likelihood: exp(-d² / 2σ²)
                // Particles close to the observation get weight near 1.0,
                // particles far away get weight near 0.0
                double twoSigmaSq = 2.0 * obs[2] * obs[2];
                p.weight *= Math.exp(-distSq / twoSigmaSq);
            }
            weightSum += p.weight;
        }
        // Clear queue for next PDR cycle
        pendingObs.clear();

        // Normalise so all weights sum to 1
        double maxWeight = 0.0;
        for (Particle p : particles) {
            p.weight /= weightSum;
            if (p.weight > maxWeight) maxWeight = p.weight;
        }
        return maxWeight;
    }

    /** Computes the weighted mean of the particle population as the current position estimate. */
    public void updateEstimatedPosition() {
        double sum_e = 0, sum_n = 0, sum_w = 0;
        for (Particle particle : particles) {
            sum_e += particle.easting * particle.weight;
            sum_n += particle.northing * particle.weight;
            sum_w += particle.weight;
        }
        estimated_position = enToLatLng(sum_e / sum_w, sum_n / sum_w);
    }

    /**
     * Systematic resampling of the particle population.
     *
     * <p>Builds a cumulative weight distribution and selects particles at evenly spaced intervals
     * with a single random offset. Selected particles are duplicated with a small Gaussian jitter
     * to maintain diversity, and weights are reset to uniform.
     *
     * @see FusionConstants#RESAMPLE_JITTER for the regularisation noise parameter.
     */
    public void repopulate() {
        ArrayList<Particle> newParticles = new ArrayList<>(PARTICLE_COUNT);

        // normalise weights
        double totalWeight = 0;
        for (Particle p : particles) {
            totalWeight += p.weight;
        }
        for (Particle p : particles) {
            p.weight /= totalWeight;
        }

        // Build cumulative weight distribution
        double[] cumulative = new double[particles.size()];
        cumulative[0] = particles.get(0).weight;
        for (int i = 1; i < particles.size(); i++) {
            cumulative[i] = cumulative[i - 1] + particles.get(i).weight;
        }

        // Evenly spaced pointers with single random offset
        double step = 1.0 / PARTICLE_COUNT;
        double start = rand.nextDouble() * step;
        int idx = 0;

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            double pointer = start + i * step;
            while (cumulative[idx] < pointer) {
                idx++;
            }
            Particle selected = particles.get(idx);
            // Duplicate with jitter to prevent particle collapse
            newParticles.add(
                    new Particle(
                            selected.easting + rand.nextGaussian() * RESAMPLE_JITTER,
                            selected.northing + rand.nextGaussian() * RESAMPLE_JITTER,
                            1.0 / PARTICLE_COUNT));
        }
        particles = newParticles;
    }

    public LatLng getEstimated_position() {
        return estimated_position;
    }
}
