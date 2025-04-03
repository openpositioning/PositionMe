package com.openpositioning.PositionMe.sensors;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Utility classes for filtering sensor data using Particle Filter and Extended Kalman Filter (EKF) methods.
 */
public class FilterUtils {

    /**
     * Particle represents a hypothesis of the current state (position and weight) used in the Particle Filter.
     */
    public static class Particle {
        // Particle position on the x-axis.
        double x;
        // Particle position on the y-axis.
        double y;
        // Particle's weight indicating the likelihood of representing the true state.
        double weight;

        /**
         * Constructs a Particle with the given position and weight.
         *
         * @param x      The x position.
         * @param y      The y position.
         * @param weight The weight of the particle.
         */
        public Particle(double x, double y, double weight) {
            this.x = x;
            this.y = y;
            this.weight = weight;
        }

        /**
         * Copy constructor to create a new Particle from an existing one.
         *
         * @param p The particle to copy.
         */
        public Particle(Particle p) {
            this.x = p.x;
            this.y = p.y;
            this.weight = p.weight;
        }
    }

    /**
     * ParticleFilter implements a particle filter that fuses predictions from Pedestrian Dead Reckoning (PDR)
     * with measurements from WiFi signals. It maintains a set of particles, each representing a possible state.
     */
    public static class ParticleFilter {
        // List to hold all the particles.
        List<Particle> particles;
        // Total number of particles maintained in the filter.
        int numParticles;
        // Random number generator for adding noise and selecting particles.
        Random random = new Random();

        // Noise parameters used for simulating uncertainties in the process (prediction) and measurements.
        double processNoisePos = 0.8;
        double measurementNoise = 2.5; // Adjust based on sensor characteristics

        // WiFi weight parameters: an external ratio to boost the influence of WiFi measurements.
        double wifiRatio;  // External factor that determines how important the WiFi measurement is

        /**
         * Initializes the Particle Filter with a specified number of particles, an initial position, and a WiFi ratio.
         *
         * @param numParticles Number of particles to generate.
         * @param initX        Initial x position.
         * @param initY        Initial y position.
         * @param wifiRatio    External factor to boost WiFi influence.
         */
        public ParticleFilter(int numParticles, double initX, double initY, double wifiRatio) {
            this.numParticles = numParticles;
            this.wifiRatio = wifiRatio;
            particles = new ArrayList<>();
            // Initialize particles around the initial position with added Gaussian noise.
            for (int i = 0; i < numParticles; i++) {
                double x = initX + randomGaussian(0, processNoisePos);
                double y = initY + randomGaussian(0, processNoisePos);
                // Each particle initially has equal weight.
                particles.add(new Particle(x, y, 1.0 / numParticles));
            }
        }

        /**
         * Setter to update the WiFi-to-GNSS ratio dynamically.
         *
         * @param wifiRatio New WiFi weight factor.
         */
        public void setWifiRatio(double wifiRatio) {
            this.wifiRatio = wifiRatio;
        }

        /**
         * Generates a random number from a Gaussian distribution with the specified mean and standard deviation.
         *
         * @param mean   The mean value.
         * @param stdDev The standard deviation.
         * @return A random Gaussian value.
         */
        private double randomGaussian(double mean, double stdDev) {
            return mean + stdDev * random.nextGaussian();
        }

        /**
         * Prediction step: update each particle's position based on the PDR (Pedestrian Dead Reckoning) prediction.
         * Noise is added to account for uncertainty in the process.
         *
         * @param pdrX Predicted x position from PDR.
         * @param pdrY Predicted y position from PDR.
         */
        public void predict(double pdrX, double pdrY) {
            for (Particle p : particles) {
                // Update particle position with predicted PDR position plus Gaussian noise.
                p.x = pdrX + randomGaussian(0, processNoisePos);
                p.y = pdrY + randomGaussian(0, processNoisePos);
            }
        }

        /**
         * Gaussian likelihood function.
         *
         * @param error  The distance error between particle and measurement.
         * @param stdDev The standard deviation (effective noise).
         * @return The likelihood value based on the Gaussian distribution.
         */
        private double gaussianProbability(double error, double stdDev) {
            return (1.0 / (stdDev * Math.sqrt(2 * Math.PI))) *
                    Math.exp(- (error * error) / (2 * stdDev * stdDev));
        }

        /**
         * Update step: adjust particle weights using WiFi measurements.
         * The measurement's influence is boosted by reducing the effective measurement noise using wifiRatio.
         * Additionally, there is an option to inject new particles around the WiFi measurement.
         *
         * @param measuredX WiFi measured x position.
         * @param measuredY WiFi measured y position.
         */
        public void update(double measuredX, double measuredY) {
            double totalWeight = 0;
            // Calculate effective measurement noise by reducing the nominal noise with the wifiRatio.
            double effectiveNoise = measurementNoise / wifiRatio;
            for (Particle p : particles) {
                // Compute the Euclidean distance between the particle and the WiFi measurement.
                double dx = p.x - measuredX;
                double dy = p.y - measuredY;
                double error = Math.sqrt(dx * dx + dy * dy);
                // Determine the likelihood of the particle based on the Gaussian probability.
                double likelihood = gaussianProbability(error, effectiveNoise);
                p.weight = likelihood;
                totalWeight += p.weight;
            }
            // Normalize particle weights to ensure they sum to 1.
            if (totalWeight == 0) {
                // If total weight is zero (unlikely event), reset all weights to uniform distribution.
                for (Particle p : particles) {
                    p.weight = 1.0 / numParticles;
                }
            } else {
                for (Particle p : particles) {
                    p.weight /= totalWeight;
                }
            }

            // Inject new particles at the WiFi measurement to further emphasize its influence.
            // For example, replace 20% of the particles with new ones centered around the WiFi measurement.
            //            int numInjection = (int)(0.05 * numParticles);
            int numInjection = 0;
            for (int i = 0; i < numInjection; i++) {
                // Randomly choose an index in the particle list for replacement.
                int idx = random.nextInt(numParticles);
                // Adding a bit of noise so the injected particles are not all identical.
                double newX = measuredX + randomGaussian(0, measurementNoise / 2);
                double newY = measuredY + randomGaussian(0, measurementNoise / 2);
                // Replace the selected particle with a new one centered around the WiFi measurement.
                particles.set(idx, new Particle(newX, newY, 1.0 / numParticles));
            }
        }

        /**
         * Resample step: perform systematic resampling based on particle weights.
         * This step selects particles based on their weights to form a new set of particles,
         * thereby focusing on areas of higher probability.
         */
        public void resample() {
            List<Particle> newParticles = new ArrayList<>();
            // Build the cumulative sum of weights.
            double[] cumulativeSum = new double[particles.size()];
            cumulativeSum[0] = particles.get(0).weight;
            for (int i = 1; i < particles.size(); i++) {
                cumulativeSum[i] = cumulativeSum[i - 1] + particles.get(i).weight;
            }
            // Define the step for systematic resampling.
            double step = 1.0 / numParticles;
            double start = random.nextDouble() * step;
            int index = 0;
            // For each particle, select a new particle based on the cumulative weights.
            for (int i = 0; i < numParticles; i++) {
                double u = start + i * step;
                // Increment index until the cumulative sum exceeds u.
                while (index < cumulativeSum.length - 1 && u > cumulativeSum[index]) {
                    index++;
                }
                // Select the particle and create a copy with uniform weight.
                Particle selected = particles.get(index);
                newParticles.add(new Particle(selected.x, selected.y, 1.0 / numParticles));
            }
            // Replace the old particles with the resampled set.
            particles = newParticles;
        }

        /**
         * Estimate the current state as the weighted average of all particles.
         *
         * @return A Particle object representing the estimated state.
         */
        public Particle estimate() {
            double estX = 0, estY = 0;
            // Compute the weighted sum of positions.
            for (Particle p : particles) {
                estX += p.x * p.weight;
                estY += p.y * p.weight;
            }
            // Return the estimated position with a dummy weight of 1.
            return new Particle(estX, estY, 1.0);
        }

        /**
         * Setter to update the measurement noise parameter dynamically.
         *
         * @param measurementNoise New measurement noise standard deviation.
         */
        public void setMeasurementNoise(double measurementNoise) {
            this.measurementNoise = measurementNoise;
        }
    }

    /**
     * EKFFilter fuses PDR (Pedestrian Dead Reckoning) data for prediction with WiFi measurements for update.
     * It uses an Extended Kalman Filter (EKF) with a simple state and measurement model.
     * The state vector is [x, y] representing position.
     */
    public static class EKFFilter {
        // State vector components representing position.
        private double x;
        private double y;
        // 2x2 state covariance matrix P representing the uncertainty in the state.
        private double[][] P = new double[2][2];
        // Process noise covariance Q (accounts for uncertainty in the PDR prediction).
        private double[][] Q = new double[2][2];
        // Measurement noise covariance R (accounts for uncertainty in the WiFi measurement).
        private double[][] R = new double[2][2];

        // Variables to store the last PDR reading in order to compute incremental displacement.
        private double lastPDRx;
        private double lastPDRy;
        private boolean isInitialized = false;

        // WiFi weight factor: larger values reduce effective measurement noise, increasing WiFi influence.
        private double wifiRatio;

        /**
         * Constructs an EKFFilter with initial state and noise parameters.
         *
         * @param initialX         Initial x position from PDR.
         * @param initialY         Initial y position from PDR.
         * @param processNoise     Standard deviation for process noise (PDR uncertainty).
         * @param measurementNoise Standard deviation for WiFi measurement noise (before scaling).
         * @param wifiRatio        Factor to boost WiFi influence (larger -> WiFi has more weight).
         */
        public EKFFilter(double initialX, double initialY, double processNoise, double measurementNoise, double wifiRatio) {
            // Initialize the state with the first PDR reading.
            this.x = initialX;
            this.y = initialY;
            this.lastPDRx = initialX;
            this.lastPDRy = initialY;
            this.wifiRatio = wifiRatio;
            isInitialized = true;

            // Initialize the state covariance P with small initial uncertainties.
            P[0][0] = 1.0;
            P[0][1] = 0.0;
            P[1][0] = 0.0;
            P[1][1] = 1.0;

            // Set process noise covariance Q as a diagonal matrix (processNoise^2 on the diagonal).
            Q[0][0] = processNoise * processNoise;
            Q[0][1] = 0.0;
            Q[1][0] = 0.0;
            Q[1][1] = processNoise * processNoise;

            // Adjust measurement noise by boosting WiFi influence.
            double effectiveMeasurementNoise = measurementNoise / wifiRatio;
            R[0][0] = effectiveMeasurementNoise * effectiveMeasurementNoise;
            R[0][1] = 0.0;
            R[1][0] = 0.0;
            R[1][1] = effectiveMeasurementNoise * effectiveMeasurementNoise;
        }

        /**
         * Prediction step: update the state based on new PDR data.
         * The control input is computed as the incremental displacement from the last PDR reading.
         *
         * @param pdrX The current PDR x measurement.
         * @param pdrY The current PDR y measurement.
         */
        public void predict(double pdrX, double pdrY) {
            if (!isInitialized) {
                // If not yet initialized, set the state from the first PDR reading.
                x = pdrX;
                y = pdrY;
                lastPDRx = pdrX;
                lastPDRy = pdrY;
                isInitialized = true;
                return;
            }

            // Compute the incremental displacement (control input) from the last reading.
            double u_x = pdrX - lastPDRx;
            double u_y = pdrY - lastPDRy;

            // Update the last PDR reading to the current one.
            lastPDRx = pdrX;
            lastPDRy = pdrY;

            // Propagate the state using the control input.
            x = x + u_x;
            y = y + u_y;

            // Increase the state covariance by the process noise (since the state transition Jacobian is the identity matrix).
            P[0][0] += Q[0][0];
            P[0][1] += Q[0][1];
            P[1][0] += Q[1][0];
            P[1][1] += Q[1][1];
        }

        /**
         * Update step: incorporate a WiFi measurement into the state estimate.
         * The measurement model is assumed to be: z = [x, y] + noise.
         *
         * @param wifiX The WiFi measured x position.
         * @param wifiY The WiFi measured y position.
         */
        public void update(double wifiX, double wifiY) {
            // Calculate the innovation (measurement residual) as the difference between measurement and current state.
            double innov0 = wifiX - x;
            double innov1 = wifiY - y;

            // Compute the innovation covariance S = P + R (because the measurement matrix H is the identity matrix).
            double s00 = P[0][0] + R[0][0];
            double s01 = P[0][1] + R[0][1];
            double s10 = P[1][0] + R[1][0];
            double s11 = P[1][1] + R[1][1];

            // Compute the determinant of S for inversion.
            double detS = s00 * s11 - s01 * s10;
            // Invert the 2x2 matrix S.
            double invS00 = s11 / detS;
            double invS01 = -s01 / detS;
            double invS10 = -s10 / detS;
            double invS11 = s00 / detS;

            // Compute the Kalman Gain K = P * S^{-1} (since H = I).
            double K00 = P[0][0] * invS00 + P[0][1] * invS10;
            double K01 = P[0][0] * invS01 + P[0][1] * invS11;
            double K10 = P[1][0] * invS00 + P[1][1] * invS10;
            double K11 = P[1][0] * invS01 + P[1][1] * invS11;

            // Update the state estimate using the Kalman Gain and the innovation.
            x = x + K00 * innov0 + K01 * innov1;
            y = y + K10 * innov0 + K11 * innov1;

            // Update the state covariance: P = (I - K) * P.
            double newP00 = (1 - K00) * P[0][0] - K01 * P[1][0];
            double newP01 = (1 - K00) * P[0][1] - K01 * P[1][1];
            double newP10 = -K10 * P[0][0] + (1 - K11) * P[1][0];
            double newP11 = -K10 * P[0][1] + (1 - K11) * P[1][1];

            P[0][0] = newP00;
            P[0][1] = newP01;
            P[1][0] = newP10;
            P[1][1] = newP11;
        }

        /**
         * Returns the current estimated x position from the filter.
         *
         * @return The estimated x position.
         */
        public double getX() {
            return x;
        }

        /**
         * Returns the current estimated y position from the filter.
         *
         * @return The estimated y position.
         */
        public double getY() {
            return y;
        }
    }
}
