package com.openpositioning.PositionMe.FusionAlgorithms;

import android.location.Location;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.Method.CoordinateTransform;
import com.openpositioning.PositionMe.Method.OutlierDetector;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import java.util.Random;

/**
 * Modified Particle Filter implementation with particle injection strategy
 * to enhance correction from GNSS/WiFi measurement data
 */
public class ParticleFilter {
    // Number of particles
    private static final int NUM_PARTICLES = 200;
    // Initial standard deviation near the reference point (in lat/lon units, roughly meters to tens of meters)
    private static final double INITIAL_STD_DEV = 0.001;
    // Motion noise (added to particles during resampling), increased to 0.3 to enhance diffusion
    private static final double MOTION_NOISE = 0.3;

    // Particle injection parameters
    private static final double INJECTION_FRACTION = 0.3; // Inject 30% of particles
    private static final double INJECTION_NOISE = 0.005;  // Noise std. dev. during injection

    private Particle[] particles;
    private final Random random;

    // Reference location (lat/lon/alt), used for ENU conversion
    private final double refLatitude;
    private final double refLongitude;
    private final double refAlt;

    // ENU coordinates after initialization (typically close to 0,0,0)
    private final double initialTrueEasting;
    private final double initialTrueNorthing;

    // Outlier detector (currently disabled)
    private OutlierDetector outlierDetector;

    /**
     * Main constructor: initializes filter with a reference point (lat, lon, alt)
     */
    public ParticleFilter(double[] initialStartRef) {
        this.outlierDetector = new OutlierDetector();
        this.random = new Random();

        // Store the reference point
        this.refLatitude = initialStartRef[0];
        this.refLongitude = initialStartRef[1];
        this.refAlt      = initialStartRef[2];

        Log.d("PF_DEBUG", String.format("Reference point: lat=%.6f, lon=%.6f, alt=%.1f",
                refLatitude, refLongitude, refAlt));

        // Convert the reference point to ENU coordinates (usually (0,0))
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
     * No-argument constructor to maintain compatibility:
     * defaults to using SensorFusion.getInstance().getGNSSLatLngAlt(true) as the reference
     */
    public ParticleFilter() {
        this(SensorFusion.getInstance().getGNSSLatLngAlt(true));
    }

    /**
     * Initializes particles randomly around the reference point
     */
    private void initializeParticles() {
        particles = new Particle[NUM_PARTICLES];
        for (int i = 0; i < NUM_PARTICLES; i++) {
            double easting = initialTrueEasting + random.nextGaussian() * INITIAL_STD_DEV;
            double northing = initialTrueNorthing + random.nextGaussian() * INITIAL_STD_DEV;
            particles[i] = new Particle(easting, northing, 1.0 / NUM_PARTICLES);
            Log.v("PF_INIT", String.format("Particle %d: east=%.6f, north=%.6f",
                    i, easting, northing));
        }
    }

    /**
     * Motion model update (using PDR data)
     * @param stepLength Step length (in meters)
     * @param heading    Heading (in radians, 0 = north, π/2 = east, clockwise)
     */
    public void predictMotion(double stepLength, double heading) {
        for (Particle particle : particles) {
            // Add random noise to step length and heading
            double noisyStep = stepLength * (1 + random.nextGaussian() * 0.1);
            double noisyHeading = heading + random.nextGaussian() * 0.1;

            double deltaEasting = noisyStep * Math.cos(noisyHeading);
            double deltaNorthing = noisyStep * Math.sin(noisyHeading);

            particle.update(deltaEasting, deltaNorthing);
        }
    }

    /**
     * Measurement update (GNSS/WiFi)
     * @param measuredLat  Measured latitude
     * @param measuredLong Measured longitude
     */
    public void measurementUpdate(double measuredLat, double measuredLong) {
        // 1) Convert measurement to ENU coordinates
        double[] enuCoords = CoordinateTransform.geodeticToEnu(
                measuredLat, measuredLong, refAlt,
                refLatitude, refLongitude, refAlt
        );
        double measuredEast = enuCoords[0];
        double measuredNorth = enuCoords[1];

        // 2) Compute weighted average of current particle positions
        double fusedEast = 0, fusedNorth = 0;
        for (Particle p : particles) {
            fusedEast  += p.getEasting()  * p.getWeight();
            fusedNorth += p.getNorthing() * p.getWeight();
        }
        double dx = measuredEast - fusedEast;
        double dy = measuredNorth - fusedNorth;
        double distance = Math.sqrt(dx * dx + dy * dy);

        // 3) Outlier detection is disabled to ensure all measurements are used
        // boolean isOutlier = outlierDetector.detectOutliers(distance);
        // if (isOutlier) {
        //     Log.w("PF", "Outlier detected! distance=" + distance + ", skip update.");
        //     return;
        // }

        // 4) Update weights based on distance from measurement
        double totalWeight = 0;
        for (Particle particle : particles) {
            double pdx = measuredEast - particle.getEasting();
            double pdy = measuredNorth - particle.getNorthing();
            double d = Math.sqrt(pdx * pdx + pdy * pdy);
            double sigma = Math.max(0.01, d * 0.05);
            double gain = 100.0;
            double likelihood = Math.exp(-0.5 * (d * d) / (sigma * sigma)) * gain;
            particle.setWeight(particle.getWeight() * likelihood);
            totalWeight += particle.getWeight();
        }

        // Normalize weights
        if (totalWeight > 0) {
            for (Particle particle : particles) {
                particle.setWeight(particle.getWeight() / totalWeight);
            }
        } else {
            Log.w("PF", "Weight degeneracy detected, reinitializing particles");
            initializeParticles();
            return;
        }

        // 5) Particle injection: force a fraction of particles near the measurement
        int injectionCount = (int) (NUM_PARTICLES * INJECTION_FRACTION);
        for (int i = 0; i < injectionCount; i++) {
            int index = random.nextInt(NUM_PARTICLES);
            double noiseE = random.nextGaussian() * INJECTION_NOISE;
            double noiseN = random.nextGaussian() * INJECTION_NOISE;
            particles[index] = new Particle(measuredEast + noiseE, measuredNorth + noiseN, 1.0 / NUM_PARTICLES);
        }

        // 6) Resample if effective number of particles is too low
        if (calculateEffectiveParticles() < NUM_PARTICLES * 0.5) {
            resampleParticles();
        }
    }

    /**
     * Calculates effective number of particles (to detect degeneracy)
     */
    private double calculateEffectiveParticles() {
        double sumSq = 0;
        for (Particle p : particles) {
            sumSq += p.getWeight() * p.getWeight();
        }
        return 1.0 / sumSq;
    }

    /**
     * Low-variance resampling
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
            // Add index boundary check to prevent out-of-bounds error
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
     * Get fused position (convert weighted ENU average back to lat/lon)
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
                "Fused position: lat=%.6f, lon=%.6f (ENU: %.2f, %.2f)",
                result.latitude, result.longitude, eastSum, northSum
        ));
        return result;
    }

    /**
     * Inner Particle class
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
