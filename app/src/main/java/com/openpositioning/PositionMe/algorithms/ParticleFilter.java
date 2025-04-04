package com.openpositioning.PositionMe.algorithms;

import android.util.Log;
import android.location.Location;
import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.CoordinateTransform;
import com.openpositioning.PositionMe.utils.OutlierDetector;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.Random;

/**
 * Particle filter algorithm for estimating position based on sensor measurements.
 *
 * @author Yueyan Zhao
 * @author Zizhen Wang
 * @author Chen Zhao
 */
public class ParticleFilter {
    // Constants, may need to be tuned
    private static final int NUM_PARTICLES = 200;
    private static final double PARTICLE_STD_DEV = 0.0002;

    // Parameters
    private Particle[] particles;
    private final Random random;

    // Reference Lat Long objects
    private final double refLatitude;
    private final double refLongitude;

    // Reference Altitude object
    private final double refAlt;

    // Reference ENU objects
    private final double initialTrueEasting;
    private final double initialTrueNorthing;

    // Outlier Detector
    private OutlierDetector outlierDetector;

    /**
     * Constructor to create a particle filter.
     */
    public ParticleFilter() {
        this.outlierDetector = new OutlierDetector();

        double[] startRef = SensorFusion.getInstance().getGNSSLatLngAlt(true);
        this.refLatitude = startRef[0];
        this.refLongitude = startRef[1];
        this.refAlt = startRef[2];
        Log.d("PARTICLE_FILTER", "Starting LatLong x: " + refLatitude + " y:" + refLongitude);
        double[] enuCoords = CoordinateTransform.geodeticToEnu(refLatitude, refLongitude, refAlt, refLatitude, refLongitude, refAlt);
        this.initialTrueEasting = enuCoords[0];
        this.initialTrueNorthing = enuCoords[1];
        Log.d("PARTICLE_FILTER", "Starting ENU Easting: " + initialTrueEasting + " ENU Northing:" + initialTrueNorthing);

        particles = new Particle[NUM_PARTICLES];
        random = new Random();

        initializeParticles();
    }

    /**
     * Initializes particles around the initial true Easting and Northing coordinates.
     */
    private void initializeParticles() {
        for (int i = 0; i < NUM_PARTICLES; i++) {
            double easting = initialTrueEasting + (random.nextGaussian() * PARTICLE_STD_DEV);
            double northing = initialTrueNorthing + (random.nextGaussian() * PARTICLE_STD_DEV);
            particles[i] = new Particle(easting, northing, 1.0 / NUM_PARTICLES);
        }
    }

    /**
     * Updates motion model by adding random noise to particle positions.
     */
    private void updateMotionModel() {
        for (Particle particle : particles) {
            particle.update(random.nextGaussian(), random.nextGaussian());
        }
    }

    /**
     * Updates particle weights based on measurement likelihood.
     */
    private void updateMeasurementModel(double measuredEast, double measuredNorth) {
        for (Particle particle : particles) {
            double probability = calculateDistanceProbability(measuredEast, measuredNorth, particle.getEasting(), particle.getNorthing());
            particle.setWeight(particle.getWeight() * probability);
        }

        // Normalize weights
        double totalWeight = 0;
        for (Particle particle : particles) {
            totalWeight += particle.getWeight();
        }
        for (Particle particle : particles) {
            particle.setWeight(particle.getWeight() / totalWeight);
        }
    }

    /**
     * Calculates distance-based probability for measurement likelihood.
     */
    private double calculateDistanceProbability(double measuredEast, double measuredNorth, double particleEast, double particleNorth) {
        double distance = Math.sqrt(Math.pow(measuredEast - particleEast, 2) + Math.pow(measuredNorth - particleNorth, 2));
        return Math.exp(-0.5 * distance);
    }

    /**
     * Resamples particles based on their weights.
     */
    private void resampleParticles() {
        Particle[] newParticles = new Particle[NUM_PARTICLES];
        double[] cumulativeWeights = new double[NUM_PARTICLES];

        cumulativeWeights[0] = particles[0].getWeight();
        for (int i = 1; i < NUM_PARTICLES; i++) {
            cumulativeWeights[i] = cumulativeWeights[i - 1] + particles[i].getWeight();
        }

        for (int i = 0; i < NUM_PARTICLES; i++) {
            double sample = random.nextDouble();
            int index = 0;
            for (int j = 0; j < NUM_PARTICLES - 1; j++) {
                if (sample < cumulativeWeights[j]) {
                    index = j;
                    break;
                }
            }
            newParticles[i] = new Particle(particles[index].getEasting(), particles[index].getNorthing(), 1.0 / NUM_PARTICLES);
        }

        particles = newParticles;
    }

    /**
     * Updates particle filter with new sensor measurements.
     */
    public void update(double measuredLat, double measuredLong) {
        float[] distanceBetween = new float[1];
        Location.distanceBetween(measuredLat, measuredLong, refLatitude, refLongitude, distanceBetween);
        if (outlierDetector.detectOutliers(distanceBetween[0])) {
            Log.d("PARTICLE_FILTER", "Outlier Detected at: " + measuredLat + "," + measuredLong + "," + distanceBetween[0]);
            return;
        }

        updateMotionModel();
        double[] enucoords = CoordinateTransform.geodeticToEnu(measuredLat, measuredLong, refAlt, refLatitude, refLongitude, refAlt);
        updateMeasurementModel(enucoords[0], enucoords[1]);
        resampleParticles();
        LatLng prediction = predict();
        LatLng new_prediction = new LatLng(refLatitude + prediction.latitude, refLongitude + prediction.longitude);
        SensorFusion.getInstance().notifyFusedUpdate(new_prediction);
        Log.d("PARTICLE_FILTER", "Prediction LatLong: " + new_prediction.latitude + " " + new_prediction.longitude);
    }

    /**
     * Predicts the estimated position based on particle weights.
     */
    public LatLng predict() {
        double estimatedEasting = 0;
        double estimatedNorthing = 0;

        for (Particle particle : particles) {
            estimatedEasting += particle.getEasting() * particle.getWeight();
            estimatedNorthing += particle.getNorthing() * particle.getWeight();
        }

        return CoordinateTransform.enuToGeodetic(estimatedEasting, estimatedNorthing, refAlt, initialTrueEasting, initialTrueNorthing, refAlt);
    }

    /**
     * Represents a particle with Easting, Northing coordinates, and weight.
     */
    class Particle {
        private double easting;
        private double northing;
        private double weight;

        public Particle(double easting, double northing, double weight) {
            this.easting = easting;
            this.northing = northing;
            this.weight = weight;
        }

        public void update(double deltaEasting, double deltaNorthing) {
            this.easting += deltaEasting;
            this.northing += deltaNorthing;
        }

        public double getEasting() {
            return easting;
        }

        public double getNorthing() {
            return northing;
        }

        public double getWeight() {
            return weight;
        }

        public void setWeight(double weight) {
            this.weight = weight;
        }
    }
}