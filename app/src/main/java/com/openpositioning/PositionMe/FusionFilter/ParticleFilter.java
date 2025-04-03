package com.openpositioning.PositionMe.FusionFilter;

import com.google.android.gms.maps.model.LatLng;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * particle filter for fusing multi-sensor data for position estimation.
 * Improvements include: more efficient resampling, motion model optimization, input validation, and code structure refactoring.
 */
public class ParticleFilter {
    private List<Particle> particles;
    private final Random random = new Random();
    private final int numParticles;
    private final double wifiNoise;
    private final double gnssNoise;
    private final double pdrNoise;
    private LatLng lastValidPosition;

    // Default configuration parameters
    private static final int DEFAULT_NUM_PARTICLES = 1000;
    private static final double DEFAULT_WIFI_NOISE = 0.000004;
    private static final double DEFAULT_GNSS_NOISE = 0.000004;
    private static final double DEFAULT_PDR_NOISE  = 0.0000065;

    /**
     * Full parameter constructor
     */
    public ParticleFilter(LatLng initialPosition, int numParticles,
                          double wifiNoise, double gnssNoise, double pdrNoise) {
        if (!isValidCoordinate(initialPosition)) {
            throw new IllegalArgumentException("Invalid initial position");
        }

        this.numParticles = numParticles;
        this.wifiNoise = wifiNoise;
        this.gnssNoise = gnssNoise;
        this.pdrNoise = pdrNoise;
        this.lastValidPosition = initialPosition;
        initializeParticles(initialPosition);
    }

    /**
     * Simplified constructor (using default parameters)
     */
    public ParticleFilter(LatLng initialPosition) {
        this(initialPosition, DEFAULT_NUM_PARTICLES,
                DEFAULT_WIFI_NOISE, DEFAULT_GNSS_NOISE, DEFAULT_PDR_NOISE);
    }

    /**
     * Main processing flow
     */
    public LatLng particleFilter(LatLng wifiCoord, LatLng gnssCoord, LatLng pdrCoord) {
        MotionModel();
        SensorUpdates(wifiCoord, gnssCoord, pdrCoord);
        normalizeWeights();
        resample();
        return estimatePosition();
    }

    /**
     * Initialize particle swarm
     */
    private void initializeParticles(LatLng center) {
        particles = new ArrayList<>(numParticles);
        final double spread = 0.0001; // Initial spread range
        final double halfSpread = spread / 2.0;
        final double initWeight = 1.0 / numParticles;

        for (int i = 0; i < numParticles; i++) {
            double latOffset = (random.nextDouble() * spread) - halfSpread;
            double lonOffset = (random.nextDouble() * spread) - halfSpread;
            double lat = center.latitude + latOffset;
            double lon = center.longitude + lonOffset;
            particles.add(new Particle(lat, lon, initWeight));
        }
    }


    /**
     * Motion model (currently simple random walk)
     */
    private void MotionModel() {
        // Previous fixed implementation
        final double movementRange = 0.00001; // Movement perturbation range (example)
        final double halfRange = movementRange / 2.0;

        for (Particle p : particles) {
            // Generate a random offset in [-halfRange, +halfRange] (uniform distribution)
            double baseLatOffset = random.nextDouble() * movementRange - halfRange;
            double baseLonOffset = random.nextDouble() * movementRange - halfRange;

            // Simple correction because the actual distance corresponding to each degree of longitude decreases in high latitude areas
            double latRad = Math.toRadians(p.position.latitude);
            double cosLat = Math.cos(latRad);

            // Avoid extreme case cosLat = 0 (very close to the poles), prevent division by zero
            if (Math.abs(cosLat) < 1e-10) {
                cosLat = 1e-10 * (cosLat < 0 ? -1 : 1);
            }

            // Use the same random amount, keep the original latitude offset. The higher the latitude (smaller cosLat), the better the longitude offset matches the latitude offset on the map
            double latOffset = baseLatOffset;
            double lonOffset = baseLonOffset / cosLat;

            // Update particle position
            p.position = new LatLng(
                    p.position.latitude + latOffset,
                    p.position.longitude + lonOffset
            );
        }
    }



    /**
     * Sensor data update (multi-source fusion)
     */
    private void SensorUpdates(LatLng wifiCoord, LatLng gnssCoord, LatLng pdrCoord) {
        if (wifiCoord != null && isValidCoordinate(wifiCoord)) {
            updateWeights(wifiCoord, wifiNoise);
        }
        if (gnssCoord != null && isValidCoordinate(gnssCoord)) {
            updateWeights(gnssCoord, gnssNoise);
        }
        if (pdrCoord != null && isValidCoordinate(pdrCoord)) {
            updateWeights(pdrCoord, pdrNoise);
        }
    }

    /**
     * Weight update (optimized distance calculation)
     */
    private void updateWeights(LatLng coord, double noise) {
        final double denominator = 2 * noise * noise;

        for (Particle p : particles) {
            double latDiff = p.position.latitude - coord.latitude;
            double lonDiff = p.position.longitude - coord.longitude;
            double squaredDistance = latDiff * latDiff + lonDiff * lonDiff;
            p.weight *= Math.exp(-squaredDistance / denominator);
        }
    }

    /**
     * Weight normalization (with exception handling)
     */
    private void normalizeWeights() {
        double total = particles.stream().mapToDouble(p -> p.weight).sum();

        if (total == 0) {
            double uniformWeight = 1.0 / numParticles;
            particles.forEach(p -> p.weight = uniformWeight);
        } else {
            particles.forEach(p -> p.weight /= total);
        }
    }


    /**
     * Systematic resampling (using binary search to optimize performance)
     */
    private void resample() {
        int N = numParticles;
        double[] cumulativeWeights = new double[N];
        cumulativeWeights[0] = particles.get(0).weight;
        for (int i = 1; i < N; i++) {
            cumulativeWeights[i] = cumulativeWeights[i - 1] + particles.get(i).weight;
        }

        List<Particle> newParticles = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            double u = random.nextDouble();
            int index = Arrays.binarySearch(cumulativeWeights, u);
            // If u is not matched exactly, binarySearch returns -(insertion point) - 1
            if (index < 0) {
                index = -index - 1;
            }
            // Prevent index from going out of bounds
            index = Math.min(index, N - 1);
            Particle selected = particles.get(index);
            newParticles.add(new Particle(selected.position.latitude,
                    selected.position.longitude,
                    1.0 / N));
        }
        particles = newParticles;
    }


    /**
     * Position estimation (with fault recovery mechanism)
     */
    private LatLng estimatePosition() {
        if (particles.isEmpty()) {
            return lastValidPosition;
        }

        double latSum = 0.0;
        double lonSum = 0.0;
        for (Particle p : particles) {
            latSum += p.position.latitude * p.weight;
            lonSum += p.position.longitude * p.weight;
        }

        // Calculated estimated position
        LatLng estimate = new LatLng(latSum, lonSum);

        // If the estimate is valid, update lastValidPosition; otherwise, keep the original value
        if (isValidCoordinate(estimate)) {
            lastValidPosition = estimate;
        }
        return lastValidPosition;
    }




    /**
     * Coordinate validity check
     */
    private boolean isValidCoordinate(LatLng coord) {
        return coord != null
                && !(Double.isNaN(coord.latitude) || Double.isNaN(coord.longitude))
                && Math.abs(coord.latitude) <= 90
                && Math.abs(coord.longitude) <= 180
                && !(coord.latitude == 0 && coord.longitude == 0);
    }

    /**
     * Particle class (optimized memory layout)
     */
    private static class Particle {
        LatLng position;
        double weight;

        Particle(double lat, double lon, double weight) {
            this.position = new LatLng(lat, lon);
            this.weight = weight;
        }
    }
}