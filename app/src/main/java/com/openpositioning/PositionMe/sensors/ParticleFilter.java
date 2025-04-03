package com.openpositioning.PositionMe.sensors;


import android.util.Log;

import java.util.Iterator;
import java.util.Random;
import java.util.List;
import java.util.ArrayList;

/**
 * Implements a particle filter algorithm for position estimation.
 * The particle filter maintains a collection of particles representing potential positions,
 * and updates their weights based on GNSS measurements to improve position accuracy.
 *
 * @author Sofea Jazlan Arif
 */

public class ParticleFilter {
    private ArrayList<ParticleAttribute> particles;
    private int numParticles;
    private Random rand;

    private float gnssX;
    private float gnssY;
    private EKFSensorFusion ekf;

    /**
     * Constructs a new ParticleFilter with the specified number of particles and initial position.
     *
     * @param numParticles Number of particles to be used in the filter
     * @param gnssX Initial X coordinate from either GNSS or Wi-Fi
     * @param gnssY Initial Y coordinate from either GNSS or Wi-Fi GNSS
     * 
     * @author Sofea Jazlan Arif
     */
    public ParticleFilter(int numParticles, float gnssX, float gnssY) {
        this.numParticles = numParticles;
        this.particles = new ArrayList<>();
        this.rand = new Random();


        setPosition(gnssX,gnssY);
        initializeParticles(gnssX, gnssY);
    }

    /**
     * Initializes the particles with random positions within 1m radius of the initial GNSS coordinates.
     * Each particle is assigned an equal initial weight.
     * The particles are saved within the ArrayList<particles>
     *
     * @param gnssX Center X coordinate for particle distribution
     * @param gnssY Center Y coordinate for particle distribution
     * 
     * @author Sofea Jazlan Arif
     */
    private void initializeParticles(float gnssX, float gnssY) {
        Log.d("Random Particle", "New batch ~~" + numParticles);
        for (int i = 0; i < numParticles; i++) {
            float lat = gnssX + (rand.nextFloat() * 2 - 1);
            float lon = gnssY + (rand.nextFloat() * 2 - 1);
            float weight = (float) (1.0 / numParticles);
            Log.d("Random Particles","X= " + lat + " Y= " + lon + "particle no" + i);
            Log.d("Random Particles","Inversed Random Particle" + ekf.getInverseTransformedCoordinate(lat, lon, 30, true));
            particles.add(new ParticleAttribute(lat, lon, weight));
        }
    }

    /**
     * Returns the current X coordinate from initial position used.
     *
     * @return The current X coordinate
     * 
     * @author Sofea Jazlan Arif
     */
    public float getGnssX() {
        return gnssX;
    }
    
    /**
     * Returns the current Y coordinate from initial position used.
     *
     * @return The current Y coordinate
     * 
     * @author Sofea Jazlan Arif
     */
    public float getGnssY() {
        return gnssY;
    }

    /**
     * Updates the current initial position.
     *
     * @param gnssX New X coordinate from GNSS or Wi-Fi
     * @param gnssY New Y coordinate from GNSS or Wi-Fi
     * 
     * @author Sofea Jazlan Arif
     */
    public void setPosition(float gnssX, float gnssY) {
        this.gnssX = gnssX;
        this.gnssY = gnssY;
    }

    /**
     * Predicts the next state of particles based on movement changes based on PDR data.
     * Applies the movement deltas to each particle with a maximum step limitation
     * to prevent unrealistic jumps.
     *
     * @param deltaX Change in X coordinate
     * @param deltaY Change in Y coordinate
     * 
     * @author Sofea Jazlan Arif
     */
    public synchronized void predict(float deltaX, float deltaY) {
        float maxStep = 0.5f;  // to prevent any large pdr jumps

        for (ParticleAttribute particle : particles) {
            float moveX = Math.min(maxStep, Math.abs(deltaX)) * Math.signum(deltaX);
            float moveY = Math.min(maxStep, Math.abs(deltaY)) * Math.signum(deltaY);

            particle.lat += moveX;
            particle.lon += moveY;
        }
    }

    /**
     * Updates the weights of particles based on their distance to the actual position.
     * Particles closer to the actual position receive higher weights, using a Gaussian
     * probability distribution. Weights are normalized to sum to 1.
     *
     * @param actualX X coordinate of the reference measured position
     * @param actualY Y coordinate of the reference measured position
     * 
     * @author Sofea Jazlan Arif
     */
    public synchronized void updateWeights(double actualX, double actualY) {

        double sumWeights = 0;

//        // Update weights based on how close each particle is to the GNSS measurement
//        for (ParticleAttribute particle : particles) {
//            float distance = (float) Math.sqrt(Math.pow(particle.lat - actualX, 2)
//                    + Math.pow(particle.lon - actualY, 2));
//            particle.weight = (float) (1.0 / (1.0 + distance * distance));
//            sumWeights += particle.weight;
//        }

        // Use an iterator to safely modify particles
        Iterator<ParticleAttribute> iterator = particles.iterator();
        while (iterator.hasNext()) {
            ParticleAttribute particle = iterator.next();
            float distance = (float) Math.sqrt(Math.pow(particle.lat - actualX, 2) +
                    Math.pow(particle.lon - actualY, 2));

            particle.weight = (float) Math.exp(-distance * distance / 2.0);
            sumWeights += particle.weight;
        }

        // Precaution to avoid division by zero
        if (sumWeights > 0) {
            for (ParticleAttribute particle : particles) {
                particle.weight /= sumWeights;
            }
        } else {
            // Precaution if all weights are zero, assign equal weights to all particles
            float equalWeight = 1.0f / particles.size();
            for (ParticleAttribute particle : particles) {
                particle.weight = equalWeight;
            }
        }
    }

    private float prevEstX = Float.NaN;
    private float prevEstY = Float.NaN;
    
    /**
     * Resamples particles based on their weights to focus on more likely positions.
     * This implementation selects the best particles by distance to reference position,
     * then replicates them with added noise to create a new set of particles.
     *
     * @param actualX X coordinate of the reference position
     * @param actualY Y coordinate of the reference position
     * 
     * @author Sofea Jazlan Arif
     */
    public synchronized void resample(float actualX, float actualY) {
        ArrayList<ParticleAttribute> newParticles = new ArrayList<>();

        float referenceX = Float.isNaN(prevEstX) ? actualX : prevEstX;
        float referenceY = Float.isNaN(prevEstY) ? actualY : prevEstY;

        // Create a copy of particles to avoid concurrent modification
        ArrayList<ParticleAttribute> particlesCopy = new ArrayList<>(particles);
        
        // Finding distance between particles and previous location
        particlesCopy.sort((p1, p2) -> {
            double dist1 = Math.sqrt(Math.pow(p1.lat - referenceX, 2) + Math.pow(p1.lon - referenceY, 2));
            double dist2 = Math.sqrt(Math.pow(p2.lat - referenceX, 2) + Math.pow(p2.lon - referenceY, 2));
            return Double.compare(dist1, dist2);
        });

        // Finding the best n particles (either 30% of max 5)
        int topN = Math.max(5, numParticles / 3);
        List<ParticleAttribute> bestParticles = new ArrayList<>(particlesCopy.subList(0, topN));

        Random rand = new Random();

        // Replicate the best particles + adding small gaussian noise (to avoid losing particles)
        while (newParticles.size() < numParticles) {
            ParticleAttribute p = bestParticles.get(rand.nextInt(bestParticles.size()));

            // Add small Gaussian noise to prevent particle collapse
            float newLat = p.lat + (float) (rand.nextGaussian() * 0.2);
            float newLon = p.lon + (float) (rand.nextGaussian() * 0.2);

            newParticles.add(new ParticleAttribute(newLat, newLon, (float) (1.0 / numParticles)));
        }

        particles = newParticles;

        // Debugging: Print the resampled particles
        for (int i = 0; i < newParticles.size(); i++) {
            Log.d("Resampled Particles", "Particle " + i + " -> Lat: " + newParticles.get(i).lat + ", Lon: " + newParticles.get(i).lon);
        }
    }

    /**
     * Calculates the estimated position as a weighted average of all particles.
     * Returns a new ParticleAttribute with the weighted average coordinates.
     *
     * @return A ParticleAttribute containing the estimated position
     * 
     * @author Sofea Jazlan Arif
     */
    public synchronized ParticleAttribute getEstimatedPosition() {
        float sumX = 0;
        float sumY = 0;

        // Create a defensive copy to avoid concurrent modification
        ArrayList<ParticleAttribute> particlesCopy = new ArrayList<>(particles);

        for (ParticleAttribute particle : particlesCopy) {
            sumX += particle.lat * particle.weight;
            sumY += particle.lon * particle.weight;
        }

        return new ParticleAttribute(sumX, sumY, 0);
    }
    
    /**
     * Calculate the position accuracy estimate based on the particle dispersion.
     * This uses the weighted standard deviation of particle positions to estimate accuracy.
     * 
     * @return The estimated accuracy in meters
     * 
     * @author Joseph Azrak
     */
    public synchronized double getPositionAccuracy() {
        if (particles == null || particles.isEmpty()) {
            return Double.NaN;
        }
        
        // Get the estimated position (center of mass)
        ParticleAttribute estimate = getEstimatedPosition();
        double centerX = estimate.lat;
        double centerY = estimate.lon;
        
        // Calculate weighted variance
        double varX = 0;
        double varY = 0;
        
        // Create a defensive copy to avoid concurrent modification
        ArrayList<ParticleAttribute> particlesCopy = new ArrayList<>(particles);
        
        for (ParticleAttribute particle : particlesCopy) {
            double dx = particle.lat - centerX;
            double dy = particle.lon - centerY;
            varX += particle.weight * dx * dx;
            varY += particle.weight * dy * dy;
        }
        
        // Return DRMS (similar to EKF accuracy calculation for consistency)
        return Math.sqrt(varX + varY);
    }


}