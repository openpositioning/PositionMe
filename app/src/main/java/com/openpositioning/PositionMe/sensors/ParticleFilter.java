package com.openpositioning.PositionMe.sensors;

import android.graphics.PointF;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Particle filter localization module that fuses position data from WiFi,
 * GNSS, and PDR to estimate user location in a local coordinate system.
 *
 * This implementation uses:
 * <ul>
 *   <li>Motion prediction (from PDR)</li>
 *   <li>Measurement update (from WiFi or GNSS)</li>
 *   <li>Low-variance resampling to maintain particle diversity</li>
 * </ul>
 *
 * @see PositioningFusion uses this class for multi-sensor location fusion.
 */
public class ParticleFilter {

    /**
     * Represents a single particle in the filter, holding its (x, y) position
     * and the associated importance weight.
     */
    public static class Particle {
        double x;
        double y;
        double weight;

        /**
         * Constructs a particle with given position and weight.
         *
         * @param x      particle X coordinate
         * @param y      particle Y coordinate
         * @param weight importance weight of the particle
         */
        Particle(double x, double y, double weight) {
            this.x = x;
            this.y = y;
            this.weight = weight;
        }
    }

    /**
     * A wrapper class for the result of a particle filter update.
     * Contains the estimated best position and updated particle set.
     */
    public static class Result {
        public double bestX;      // X-coordinate of the optimal estimated position.
        public double bestY;      // Y-coordinate of the optimal estimated position.
        public List<Particle> particles;  // Updated set of particles.

        /**
         * Constructs the result.
         *
         * @param bestX     weighted average X of particles
         * @param bestY     weighted average Y of particles
         * @param particles updated list of particles
         */
        Result(double bestX, double bestY, List<Particle> particles) {
            this.bestX = bestX;
            this.bestY = bestY;
            this.particles = particles;
        }
    }

    /**
     * Initializes a new list of particles centered around the initial position.
     * Each particle is randomly spread in a small area.
     *
     * @param initPos optional initial center position (can be null)
     * @return list of initialized particles with equal weights
     */
    public static List<Particle> initializeParticles(PointF initPos) {
        List<Particle> particles = new ArrayList<>(NUM_PARTICLES);
        double initX = (initPos != null) ? initPos.x : 0.0;
        double initY = (initPos != null) ? initPos.y : 0.0;
        // Set the initial particle dispersion range (e.g., randomly
        // distribute within a range of 5 units around the initial center).
        double initSpread = 5.0;
        Random rand = new Random();
        for (int i = 0; i < NUM_PARTICLES; i++) {
            double rx = initX + (rand.nextDouble() * 2 - 1) * initSpread;
            double ry = initY + (rand.nextDouble() * 2 - 1) * initSpread;
            // Each particle has an equal weight at initialization.
            particles.add(new Particle(rx, ry, 1.0 / NUM_PARTICLES));
        }
        return particles;
    }

    // Fixed number of particles, configurable as needed.
    private static final int NUM_PARTICLES = 200; // increase to 200 particles


    /**
     * Core function for updating the state of the particle filter. It fuses current sensor data (WiFi/GNSS positioning and PDR displacement),
     * performs particle state prediction, observation update, and resampling, and returns the new set of particles along with the estimated position.
     *
     * @param particles List of particles from the previous time step (can be null or size 0 to indicate initialization)
     * @param wifiPos Current position obtained from WiFi localization (x, y); can be null if no WiFi data is available
     * @param gnssPos Current position obtained from GNSS localization (x, y); can be null if no GNSS data is available
     * @param pdrDelta Displacement increment (Δx, Δy) provided by PDR, representing the movement since the previous time step; can be null
     * @return Result object containing the estimated optimal position (bestX, bestY) and the new list of particles
     */
    public static Result updateParticleFilter(List<Particle> particles,
                                              PointF wifiPos, PointF gnssPos, PointF pdrDelta) {
        int N = NUM_PARTICLES;
        Random rand = new Random();
        double measurementStd = 0.0;
        boolean isMoving = true;

        // 1. If the particle list is empty, perform initialization.
        if (particles == null || particles.isEmpty()) {
            particles = new ArrayList<>(N);
            // Determine the initial center position: give priority to the
            // initial value from WiFi or GNSS; otherwise, default to (0, 0).
            double initX = 0.0;
            double initY = 0.0;
            if (wifiPos != null) {
                initX = wifiPos.x;
                initY = wifiPos.y;
            } else if (gnssPos != null) {
                initX = gnssPos.x;
                initY = gnssPos.y;
            }
            // Set the initial particle dispersion range (e.g., randomly
            // distribute within a range of 5 units around the initial center).
            double initSpread = 5.0;
            for (int i = 0; i < N; i++) {
                double rx = initX + (rand.nextDouble() * 2 - 1) * initSpread;
                double ry = initY + (rand.nextDouble() * 2 - 1) * initSpread;
                // Each particle has an equal weight at initialization.
                particles.add(new Particle(rx, ry, 1.0 / N));
            }
        } else {
            // Use the provided particle list and ensure the number is fixed to N.
            N = particles.size();
        }

        // 2. **Motion prediction: update each particle's position based on the PDR data.**
        if (pdrDelta != null) {
            double moveX = pdrDelta.x;
            double moveY = pdrDelta.y;
            double motionNoiseStd = 0.5; // Motion noise standard deviation, adjustable as needed.
            double absMovement = Math.sqrt(moveX * moveX + moveY * moveY);
//            Log.d("ParticleFilter", "Absolute movement: " + absMovement);
            if (absMovement < 0.5) {
                isMoving = false;
//                Log.d("ParticleFilter", "PDR moving detect"+isMoving);
            }


            for (Particle p : particles) {
                // **Introduce random perturbations to step length and direction.
                double stepNoise = rand.nextGaussian() * 0.05; // Step length error
                double angleNoise = rand.nextGaussian() * 0.02; // Direction error (radians)
                double noisyMoveX = moveX * (1 + stepNoise) * Math.cos(angleNoise) - moveY * Math.sin(angleNoise);
                double noisyMoveY = moveY * (1 + stepNoise) * Math.cos(angleNoise) + moveX * Math.sin(angleNoise);
                // Apply displacement and noise.
                p.x += noisyMoveX + rand.nextGaussian() * motionNoiseStd;
                p.y += noisyMoveY + rand.nextGaussian() * motionNoiseStd;
            }
        }

        // 3. Observation update: update particle weights based on the
        // currently available WiFi or GNSS localization observations.
        PointF measurement = null;

        // First, calculate the current estimated position (weighted average).
        double estX = 0.0;
        double estY = 0.0;
        for (Particle p : particles) {
            estX += p.x * p.weight;
            estY += p.y * p.weight;
        }
        if (wifiPos != null) {
            measurement = wifiPos;
            if (isMoving){
                measurementStd = 1.0;
            } else {
                measurementStd = 99999;
            }
//            Log.d("ParticleFilter", "measurementStd is" + measurementStd); // base noise
            // If the WiFi position differs significantly
            // from the current estimated position, increase the noise.
            double dxEst = estX - wifiPos.x;
            double dyEst = estY - wifiPos.y;
            double distToEst = Math.sqrt(dxEst * dxEst + dyEst * dyEst);
            if (distToEst > 5.0) { // Threshold is adjustable.
                measurementStd = 3.0; // Increase the noise.
            }
        } else if (gnssPos != null) {
            measurement = gnssPos;
            if (isMoving){
            measurementStd = 5.0;}
            else{measurementStd = 99999;}// GNSS noise
        }
//        Log.d("ParticleFilter", "measurementStd is" + measurementStd); // Base noise
        if (measurement != null) {
            double sigmaSq = measurementStd * measurementStd;
            double weightSum = 0.0;
            for (Particle p : particles) {
                double dx = p.x - measurement.x;
                double dy = p.y - measurement.y;
                double distSq = dx * dx + dy * dy;
                double dist = Math.sqrt(distSq);
                // Use a Huber-like robust model.
                double huberThreshold = 2.0; // Adjustable parameter
                double w = dist < huberThreshold ? Math.exp(-distSq / (2 * sigmaSq)) : huberThreshold / dist;
                p.weight = w;
                weightSum += w;
            }
            // Normalized weights
            if (weightSum > 0) {
                for (Particle p : particles) {
                    p.weight /= weightSum;
                }
            } else {
                // In extreme cases, distribute weights evenly.
                for (Particle p : particles) {
                    p.weight = 1.0 / N;
                }
            }
        } else {
            // No observation data; keep weights unchanged (but normalize).
            double weightSum = 0.0;
            for (Particle p : particles) {
                weightSum += p.weight;
            }
            if (weightSum == 0) {
                for (Particle p : particles) {
                    p.weight = 1.0 / N;
                }
            } else {
                for (Particle p : particles) {
                    p.weight /= weightSum;
                }
            }
        }

        // 4. Estimate the current optimal position:
        // calculate the weighted average coordinates of the particle set.
        estX = 0.0;
        estY = 0.0;
        for (Particle p : particles) {
            estX += p.x * p.weight;
            estY += p.y * p.weight;
        }

        // 5. Low-variance resampling: generate a new particle set based
        // on the current particle weight distribution.
        List<Particle> newParticles = new ArrayList<>(N);
        double r = rand.nextDouble() / N;
        double c = particles.get(0).weight;
        int index = 0;
        for (int m = 0; m < N; m++) {
            double U = r + m * (1.0 / N);
            while (U > c) {
                index++;
                if (index >= N) {
                    index = N - 1;
                    break;
                }
                c += particles.get(index).weight;
            }
            Particle chosen = particles.get(index);
            newParticles.add(new Particle(chosen.x, chosen.y, 1.0 / N));
        }

        // 6. **Return the result, including the estimated position and the new particle set.**
        return new Result(estX, estY, newParticles);
    }
}