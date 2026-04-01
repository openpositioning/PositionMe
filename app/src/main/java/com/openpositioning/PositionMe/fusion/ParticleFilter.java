package com.openpositioning.PositionMe.fusion;

import static com.openpositioning.PositionMe.fusion.FusionConstants.INITIAL_UNCERTAINTY_M;
import static com.openpositioning.PositionMe.fusion.FusionConstants.MAX_STEP_LENGTH;
import static com.openpositioning.PositionMe.fusion.FusionConstants.METRES_PER_DEG_LAT;
import static com.openpositioning.PositionMe.fusion.FusionConstants.METRES_PER_DEG_LNG_AT_EQUATOR;
import static com.openpositioning.PositionMe.fusion.FusionConstants.PARTICLE_COUNT;
import static com.openpositioning.PositionMe.fusion.FusionConstants.PARTICLE_FILTER_THRESHOLD;
import static com.openpositioning.PositionMe.fusion.FusionConstants.RESAMPLE_JITTER;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.preference.PreferenceManager;
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
    private List<Particle> particles;
    private int maximumNumberOfParticles;
    private boolean active;
    private Random rand;
    // Current best position estimate
    private LatLng estimatedPosition;
    // Map matching logic
    private final MapMatching mapMatching;
    private double orientationError = 0;
    private float repopulationJitter = 0f;
    private float particleFilterThreshold;

    // Internal representation of a single particle with 2D position and weight.
    //
    // List of position observations from different sensor readings
    // Queued observations from GNSS and WiFi, waiting to be applied on the next PDR step
    // Each entry is {easting (m), northing (m), sigma (m)} in the local Easting/Northing frame
    private List<double[]> pendingObs = new ArrayList<>();

    public ParticleFilter(Context context, MapMatching mapMatching) {
        this.mapMatching = mapMatching;
        updateConstants(context);
    }

    public void updateConstants(Context context) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        if (settings.getBoolean("overwrite_fusion_constants", false)) {
            particleFilterThreshold =
                    (float)
                                    settings.getInt(
                                            "particle_threshold",
                                            (int) PARTICLE_FILTER_THRESHOLD * 100)
                            / 100;

            maximumNumberOfParticles = settings.getInt("particle_count", PARTICLE_COUNT);
            repopulationJitter =
                    (float) settings.getInt("resampling_variance", (int) RESAMPLE_JITTER * 100)
                            / 100;
        } else {
            particleFilterThreshold = (float) PARTICLE_FILTER_THRESHOLD;
            maximumNumberOfParticles = PARTICLE_COUNT;
            repopulationJitter = RESAMPLE_JITTER;
        }

        Log.d(TAG, "Constants updated");
        Log.d(TAG, "particleFilterThreshold: " + particleFilterThreshold);
        Log.d(TAG, "maximumNumberOfParticles: " + maximumNumberOfParticles);
        Log.d(TAG, "repopulationJitter: " + repopulationJitter);
    }

    /**
     * Set the new maximum number of {@link Particle Particles} per iteration of the filter
     * algorithm
     *
     * @param newMaximum The new maximum number of particles allowed
     */
    public void setMaximumNumberOfParticles(int newMaximum) {
        maximumNumberOfParticles = newMaximum;
    }

    public void updateRepopulationJitter(float newJitter) {
        repopulationJitter = newJitter;
    }

    public List<Particle> getParticles() {
        return particles;
    }

    public LatLng getEstimatedPosition() {
        return estimatedPosition;
    }

    /**
     * Queues a position observation to be fused on the next PDR update.
     *
     * @param easting observed easting in metres (local EN frame).
     * @param northing observed northing in metres (local EN frame).
     * @param sigma observation uncertainty in metres.
     */
    public void addObservation(double easting, double northing, double sigma) {
        pendingObs.add(new double[] {easting, northing, sigma});
    }

    /**
     * Initialises the particle filter with a cloud of equally weighted {@link Particle particles}
     * around the given {@link LatLng} position.
     *
     * @param initialPosition starting position in WGS84 coordinates.
     * @param sigmaMetres initial position uncertainty in metres.
     */
    public void start(LatLng initialPosition, float sigmaMetres) {
        pendingObs.clear();
        rand = new Random();
        refLat = initialPosition.latitude;
        refLng = initialPosition.longitude;
        active = true;
        estimatedPosition = initialPosition;
        double[] eastNorth = latLngToEN(initialPosition.latitude, initialPosition.longitude);

        // Generate and save maximumNumberOfParticles new particles
        populateParticles(eastNorth[0], eastNorth[1]);
        Log.d(
                TAG,
                "ParticleFilter started: "
                        + initialPosition
                        + "; sigma = "
                        + sigmaMetres
                        + "m; "
                        + "# of particles = "
                        + maximumNumberOfParticles);
    }

    /**
     * Creates a new particle population centred at the given position with Gaussian spread.
     *
     * @param easting centre easting in metres.
     * @param northing centre northing in metres.
     */
    private void populateParticles(double easting, double northing) {
        ArrayList<Particle> temp_particles = new ArrayList<>(maximumNumberOfParticles);
        for (int i = 0; i < maximumNumberOfParticles; i++) {
            temp_particles.add(
                    new Particle(
                            easting + rand.nextGaussian() * INITIAL_UNCERTAINTY_M,
                            northing + rand.nextGaussian() * INITIAL_UNCERTAINTY_M,
                            rand.nextGaussian() * FusionConstants.INITIAL_ORIENTATION_ERROR_STDDEV,
                            1.0 / maximumNumberOfParticles));
        }
        double[] bestEstimateEN =
                latLngToEN(estimatedPosition.latitude, estimatedPosition.longitude);
        particles = mapMatching.removeImpossibleParticles(temp_particles, bestEstimateEN);
    }

    /** Stops the filter and releases the particle population. */
    public void stop() {
        this.active = false;
        if (particles != null) particles.clear();
    }

    /**
     * Prediction step: Propagates all particles using a PDR displacement with added process noise.
     *
     * <p>After propagation, weights are updated and systematic resampling is triggered if weight
     * degeneracy exceeds the threshold. The position estimate is updated each cycle.
     *
     * @param stepLength PDR step length in metres.
     * @param rawHeading uncorrected heading in radians.
     * @param dx easting displacement in metres from the PDR step (unused, recalculated internally).
     * @param dy northing displacement in metres from the PDR step (unused, recalculated
     *     internally).
     */
    public void updateWithPDR(double stepLength, double rawHeading, double dx, double dy) {
        if (active && particles != null) {

            for (Particle p : particles) {
                // Apply this particle's orientation error correction
                double correctedHeading = rawHeading + p.orientationError;

                // Project step into local EN frame
                p.easting +=
                        stepLength * Math.sin(correctedHeading)
                                + rand.nextGaussian() * FusionConstants.PDR_NOISE_STDDEV;
                p.northing +=
                        stepLength * Math.cos(correctedHeading)
                                + rand.nextGaussian() * FusionConstants.PDR_NOISE_STDDEV;

                // Orientation error drifts slowly
                p.orientationError +=
                        rand.nextGaussian() * FusionConstants.ORIENTATION_DRIFT_STDDEV;
            }

            double estOrientErr = getEstimatedOrientationError();
            orientationError = estOrientErr;
            Log.d(
                    TAG,
                    "orientationError estimate="
                            + estOrientErr
                            + " ("
                            + Math.toDegrees(estOrientErr)
                            + " deg)"
                            + " rawHeading="
                            + rawHeading
                            + " correctedHeading="
                            + (rawHeading + estOrientErr));

            double maxWeight = updateWeights();
            if (maxWeight > particleFilterThreshold) {
                repopulate();
            }
            updateEstimatedPosition();
        }
    }

    /**
     * Returns the estimated orientation error as a weighted mean across particles.
     *
     * @return orientation error in radians.
     */
    public double getEstimatedOrientationError() {
        if (particles == null) return 0.0;
        double sumOE = 0, sumW = 0;
        for (Particle p : particles) {
            sumOE += p.orientationError * p.weight;
            sumW += p.weight;
        }
        return sumOE / sumW;
    }

    /**
     * Converts {@link LatLng} coordinates to local East-North metres relative to the reference
     * point.
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
     * Converts local East-North metres back to {@link LatLng} coordinates.
     *
     * @param easting east displacement in metres from reference.
     * @param northing north displacement in metres from reference.
     * @return {@link LatLng} position.
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

    /**
     * Update step: Assigns weights to particles based on observation likelihood.
     *
     * @return the maximum particle weight after normalisation.
     */
    public double updateWeights() {
        if (pendingObs.isEmpty() || particles == null) return 0.0;

        double weightSum = 0.0;
        // Iterates through each particle and calculates difference between PDR data and recorded
        // observation data
        for (Particle p : particles) {
            for (double[] obs : pendingObs) {
                // Squared distance between this particle and the observation from sensors
                double de = p.easting - obs[0];
                double dn = p.northing - obs[1];
                double distSq = de * de + dn * dn;

                // Particles closer to the observation get larger weights
                double twoSigmaSq = 2.0 * obs[2] * obs[2];
                p.weight *= Math.exp(-distSq / twoSigmaSq);
            }
            weightSum += p.weight;
        }
        for (double[] obs : pendingObs) {
            Log.d(TAG, "obs easting: " + obs[0] + "obs northing: " + obs[1]);
        }

        // Clear queue for next PDR cycle
        pendingObs.clear();

        if (weightSum == 0.0) {
            Log.w(TAG, "Weight underflow: resetting to uniform");
            double uniform = 1.0 / particles.size();
            for (Particle p : particles) p.weight = uniform;
            return uniform;
        }

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
        double new_east = sum_e / sum_w;
        double new_north = sum_n / sum_w;
        double[] newEstimate = {new_east, new_north};
        Log.d(
                TAG,
                "new position (no step correction)"
                        + "new_east: "
                        + new_east
                        + "north: "
                        + new_north);
        double[] oldEstimate = latLngToEN(estimatedPosition.latitude, estimatedPosition.longitude);
        double east_diff = new_east - oldEstimate[0];
        double north_diff = new_north - oldEstimate[1];
        double distance = Math.sqrt((east_diff * east_diff) + (north_diff * north_diff));
        if (distance >= MAX_STEP_LENGTH) {
            double ratio = MAX_STEP_LENGTH / distance;
            new_east = oldEstimate[0] + east_diff * ratio;
            new_north = oldEstimate[1] + north_diff * ratio;
            Log.d(
                    TAG,
                    "new position (w/ step correction)"
                            + "new_east: "
                            + new_east
                            + "north: "
                            + new_north);
        }
        if (!mapMatching.checkWallCrossed(oldEstimate, newEstimate)) {
            estimatedPosition = enToLatLng(new_east, new_north);
        } else {
            populateParticles(oldEstimate[0], oldEstimate[1]);
        }
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
        ArrayList<Particle> newParticles = new ArrayList<>(maximumNumberOfParticles);

        // Normalise weights
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
        double step = 1.0 / maximumNumberOfParticles;
        double start = rand.nextDouble() * step;
        int idx = 0;

        for (int i = 0; i < maximumNumberOfParticles; i++) {
            double pointer = start + i * step;
            while (cumulative[idx] < pointer) {
                idx++;
            }
            Particle selected = particles.get(idx);
            // Duplicate with jitter to prevent particle collapse
            newParticles.add(
                    new Particle(
                            selected.easting + rand.nextGaussian() * repopulationJitter,
                            selected.northing + rand.nextGaussian() * repopulationJitter,
                            selected.orientationError
                                    + rand.nextGaussian()
                                            * FusionConstants.ORIENTATION_RESAMPLE_JITTER,
                            1.0 / maximumNumberOfParticles));
        }
        double[] bestEstimateEN =
                latLngToEN(estimatedPosition.latitude, estimatedPosition.longitude);
        particles = mapMatching.removeImpossibleParticles(newParticles, bestEstimateEN);
        Log.i(TAG, "Repopulation of particles complete!");
    }

    public double getOrientationError() {
        if (!Double.isNaN(orientationError)) {
            return (float) orientationError;
        } else {
            return 0.00f;
        }
    }

    public void updateOnWifiOrGNSS() {
        double max_weight = updateWeights();
        if (max_weight > PARTICLE_FILTER_THRESHOLD) {
            repopulate();
        }
        // updateEstimatedPosition();
    }

    public boolean isActive() {
        return active;
    }
}
