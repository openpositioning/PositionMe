package com.openpositioning.PositionMe.FusionAlgorithms;

import android.location.Location;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.Method.CoordinateTransform;
import com.openpositioning.PositionMe.Method.OutlierDetector;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.Random;

/**
 * The ParticleFilter implementation is mainly used for the fusion of GNSS, WiFi and PDR data,
 * and the particle filter algorithm is used to modify the positioning data.
 * The core idea is to use a set of particles to represent the probability distribution of the position,
 * and constantly adjust the particle weights through motion models (PDR data) and measurement updates (GNSS/WiFi data)
 * to obtain the best position estimate after fusion.
 * In addition, a particle injection strategy is introduced:
 * some particles are reset directly to the measured value when the measurement is updated
 * to enhance the correction of the GNSS/WiFi measurement data.
 */
public class ParticleFilter {
    // Particle number
    private static final int NUM_PARTICLES = 300;
    // At the beginning, the standard deviation of the random distribution near the reference point
    private static final double INITIAL_STD_DEV = 0.001;
    // Motion process noise (used to add random perturbations to particles during resampling)
    // has been increased to 0.2 to increase particle diffusion
    private static final double MOTION_NOISE = 0.2;

    // Particle injection parameters
    private static final double INJECTION_FRACTION = 0.3; // Injection ratio,  30%
    private static final double INJECTION_NOISE = 0.2;    // Standard deviation of noise at injection

    private Particle[] particles;
    private final Random random;

    // Reference position (latitude and longitude elevation) for ENU conversion
    private final double refLatitude;
    private final double refLongitude;
    private final double refAlt;

    // Initialized ENU coordinates
    private final double initialTrueEasting;
    private final double initialTrueNorthing;

    // Outlier detector
    private OutlierDetector outlierDetector;

    /**
     * Main constructor: based on the given initial reference point (lat, lon, alt)
     */
    public ParticleFilter(double[] initialStartRef) {
        this.outlierDetector = new OutlierDetector();
        this.random = new Random();

        // Save reference point
        this.refLatitude = initialStartRef[0];
        this.refLongitude = initialStartRef[1];
        this.refAlt      = initialStartRef[2];

        Log.d("PF_DEBUG", String.format("reference point: lat=%.6f, lon=%.6f, alt=%.1f",
                refLatitude, refLongitude, refAlt));

        // Convert reference points to ENU coordinates
        double[] enuCoords = CoordinateTransform.geodeticToEnu(
                refLatitude, refLongitude, refAlt,
                refLatitude, refLongitude, refAlt
        );
        this.initialTrueEasting = enuCoords[0];
        this.initialTrueNorthing = enuCoords[1];

        Log.d("PF_DEBUG", String.format("Initial ENU: east=%.3f, north=%.3f",
                initialTrueEasting, initialTrueNorthing));

        // Initialize all particles
        initializeParticles();
    }

    /**
     * The default is SensorFusion.getInstance().getGNSSLatLngAlt(true) as the reference point
     */
    public ParticleFilter() {
        this(SensorFusion.getInstance().getGNSSLatLngAlt(true));
    }

    /**
     * Initialize the particles, randomly distributed around the reference point
     */
    private void initializeParticles() {
        particles = new Particle[NUM_PARTICLES];
        for (int i = 0; i < NUM_PARTICLES; i++) {
            double easting = initialTrueEasting + random.nextGaussian() * INITIAL_STD_DEV;
            double northing = initialTrueNorthing + random.nextGaussian() * INITIAL_STD_DEV;
            particles[i] = new Particle(easting, northing, 1.0 / NUM_PARTICLES);
            Log.v("PF_INIT", String.format("particle%d: east=%.6f, north=%.6f",
                    i, easting, northing));
        }
    }

    /**
     * Motion model update (using PDR data)
     * @param stepLength Step size (m)
     * @param heading    Direction (radian, 0= north, π/2= East, clockwise)
     */
    public void predictMotion(double stepLength, double heading) {
        for (Particle particle : particles) {
            // Add random noise to step size and direction
            double noisyStep = stepLength * (1 + random.nextGaussian() * 0.1);
            double noisyHeading = heading + random.nextGaussian() * 0.1;

            double deltaEasting = noisyStep * Math.cos(noisyHeading);
            double deltaNorthing = noisyStep * Math.sin(noisyHeading);

            particle.update(deltaEasting, deltaNorthing);
        }
    }

    /**
     * Measurement Updates (GNSS/WiFi)
     * @param measuredLat  The measured latitude
     * @param measuredLong Measured longitude
     */
    public void measurementUpdate(double measuredLat, double measuredLong) {
        // 1) Convert the measured values to ENU coordinates
        double[] enuCoords = CoordinateTransform.geodeticToEnu(
                measuredLat, measuredLong, refAlt,
                refLatitude, refLongitude, refAlt
        );
        double measuredEast = enuCoords[0];
        double measuredNorth = enuCoords[1];

        // 2) Calculate the weighted average position of the current particle (for subsequent judgment)
        double fusedEast = 0, fusedNorth = 0;
        for (Particle p : particles) {
            fusedEast  += p.getEasting()  * p.getWeight();
            fusedNorth += p.getNorthing() * p.getWeight();
        }
        double dx = measuredEast - fusedEast;
        double dy = measuredNorth - fusedNorth;
        double distance = Math.sqrt(dx * dx + dy * dy);

        // 3)Cancel outlier detection to ensure that measurement data participates in updates
        boolean isOutlier = outlierDetector.detectOutliers(distance);
        if (isOutlier) {
            Log.w("PF", "Outlier detected! distance=" + distance + ", skip update.");
            return;
        }

        // 4) Measurement model update: Update the particle weights based on the distance
        // between the measured value and the particle position
        double totalWeight = 0;
        for (Particle particle : particles) {
            double pdx = measuredEast - particle.getEasting();
            double pdy = measuredNorth - particle.getNorthing();
            double d = Math.sqrt(pdx * pdx + pdy * pdy);
            // New parameters are used to make measurement updates more sensitive
            double sigma = Math.max(0.01, d * 0.005);
            double gain = 100.0;
            double likelihood = Math.exp(-0.5 * (d * d) / (sigma * sigma)) * gain;
            particle.setWeight(particle.getWeight() * likelihood);
            totalWeight += particle.getWeight();
        }
        // Normalized weights
        if (totalWeight > 0) {
            for (Particle particle : particles) {
                particle.setWeight(particle.getWeight() / totalWeight);
            }
        } else {
            Log.w("PF", "Weight degradation, reinitialize the particle");
            initializeParticles();
            return;
        }

        // 5) [New] Particle injection: Reset some particles directly to the measured value to enhance GNSS/WiFi correction
        int injectionCount = (int) (NUM_PARTICLES * INJECTION_FRACTION);
        for (int i = 0; i < injectionCount; i++) {
            int index = random.nextInt(NUM_PARTICLES);
            double noiseE = random.nextGaussian() * INJECTION_NOISE;
            double noiseN = random.nextGaussian() * INJECTION_NOISE;
            particles[index] = new Particle(measuredEast + noiseE, measuredNorth + noiseN, 1.0 / NUM_PARTICLES);
        }

        // 6)Check the effective particle count, and resample if it is less than 50%
        if (calculateEffectiveParticles() < NUM_PARTICLES * 0.5) {
            resampleParticles();
        }
    }

    /**
     * Calculate the effective particle count (to judge particle degradation)
     */
    private double calculateEffectiveParticles() {
        double sumSq = 0;
        for (Particle p : particles) {
            sumSq += p.getWeight() * p.getWeight();
        }
        return 1.0 / sumSq;
    }

    /**
     * Low variance resampling
     */
    private void resampleParticles() {
        Particle[] newParticles = new Particle[NUM_PARTICLES];
        double[] cumulativeWeights = new double[NUM_PARTICLES];

        cumulativeWeights[0] = particles[0].getWeight();
        for (int i = 1; i < NUM_PARTICLES; i++) {
            cumulativeWeights[i] = cumulativeWeights[i - 1] + particles[i].getWeight();
        }

        double step = 1.0 / NUM_PARTICLES;
        double position = random.nextDouble() * step;
        int index = 0;

        for (int i = 0; i < NUM_PARTICLES; i++) {
            // Added index boundary check to prevent index from going out of range
            while (index < NUM_PARTICLES - 1 && position > cumulativeWeights[index]) {
                index++;
            }
            double noiseE = random.nextGaussian() * MOTION_NOISE;
            double noiseN = random.nextGaussian() * MOTION_NOISE;

            newParticles[i] = new Particle(
                    particles[index].getEasting() + noiseE,
                    particles[index].getNorthing() + noiseN,
                    1.0 / NUM_PARTICLES
            );
            position += step;
        }
        particles = newParticles;
    }

    /**
     * Get the fused position (convert ENU coordinates back to latitude and longitude)
     */
    public LatLng getFusedPosition() {
        double eastSum = 0, northSum = 0;
        for (Particle particle : particles) {
            eastSum  += particle.getEasting()  * particle.getWeight();
            northSum += particle.getNorthing() * particle.getWeight();
        }
        LatLng result = CoordinateTransform.enuToGeodetic(
                eastSum, northSum, refAlt,
                refLatitude, refLongitude, refAlt
        );
        Log.d("PF_OUTPUT", String.format(
                "fused position: lat=%.6f, lon=%.6f (ENU: %.2f, %.2f)",
                result.latitude, result.longitude, eastSum, northSum
        ));
        return result;
    }

    /**
     * Inner particle class
     */
    private static class Particle {
        private double easting;
        private double northing;
        private double weight;

        Particle(double easting, double northing, double weight) {
            this.easting = easting;
            this.northing = northing;
            this.weight = weight;
        }

        void update(double deltaEasting, double deltaNorthing) {
            this.easting += deltaEasting;
            this.northing += deltaNorthing;
        }

        double getEasting() { return easting; }
        double getNorthing() { return northing; }
        double getWeight() { return weight; }
        void setWeight(double weight) { this.weight = weight; }
    }
}
