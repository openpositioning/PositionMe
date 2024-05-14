package com.openpositioning.PositionMe;

import com.google.android.gms.maps.model.LatLng;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The ParticleFilter class implements a particle filter algorithm for position tracking and estimation,
 * integrating various sources of position updates. It utilizes a probabilistic approach to estimate the system's current state by dispersing a set of particles
 * across the estimated position and adjusting their distribution based on new measurements. Each particle represents a potential state,
 * with a position (latitude and longitude) and a weight indicating its likelihood.
 *
 * The class provides functionality to initialize the filter with a set number of particles around an initial position,
 * update the filter state with new positional measurements, and compute a fused position as the weighted average of all particles.
 * This approach allows for the mitigation of measurement noise and inaccuracies inherent in any single positioning system,
 * resulting in a more accurate and reliable estimation of the true position.
 *
 * Key Methods:
 * - Initializing the particle set around an initial position with a specified spread.
 * - Predicting particle displacement based on latest updates.
 * - Updating particle weights based on proximity to new measurements from various sources.
 * - Resampling particles to focus on the most probable states, thereby refining the position estimate.
 * - Calculating the fused position as the weighted average of particle positions, representing the best estimate of the true position.
 * 
 * @author: Batu Bayram 
 */

public class ParticleFilter {
    private List<Particle> particles;
    private int numberOfParticles;
    private Random random;
    private LatLng lastPosUpdate;

    // Inner class to represent particle
    private class Particle {
        LatLng position; // Assuming a LatLng class exists to represent latitude and longitude
        double weight;

        Particle(LatLng position, double weight) {
            this.position = position;
            this.weight = weight;
        }
    }

    public ParticleFilter(int numberOfParticles, LatLng initialPosition) {
        this.numberOfParticles = numberOfParticles;
        this.particles = new ArrayList<>(numberOfParticles);
        this.random = new Random();
        this.lastPosUpdate = initialPosition;
        initializeParticles(initialPosition);
    }

    //Method to initialise the particle with an initial particle
    private void initializeParticles(LatLng initialPosition) {
        double spreadRadius = 10;
        for (int i = 0; i < numberOfParticles; i++) {
            double offsetLat = (random.nextDouble() - 0.5) * spreadRadius / 111111; // Convert meters to degrees latitude
            double offsetLng = (random.nextDouble() - 0.5) * spreadRadius / (111111 * Math.cos(Math.toRadians(initialPosition.latitude))); // Convert meters to degrees longitude
            particles.add(new Particle(new LatLng(initialPosition.latitude + offsetLat, initialPosition.longitude + offsetLng), 1.0 / numberOfParticles));
        }
    }

   //Updating the filter by giving one base (predict) and two adjusting (updating) positions
    public void updateFilter(LatLng predictPos, LatLng updPos1, LatLng updPos2, double measurementNoise) {
        // Predict movement based on the most reliable
        predict(predictPos);

        // Update based on the rest
        update(updPos1,measurementNoise);
        update(updPos2, measurementNoise);

        // Resample particles to focus on more probable states
        resample();
    }

    //Method to predict particle
    private void predict(LatLng currentPosition) {
        // Calculate displacement since the last update
        LatLng displacement = new LatLng(currentPosition.latitude - lastPosUpdate.latitude,
                currentPosition.longitude - lastPosUpdate.longitude);
        lastPosUpdate = currentPosition; // Update last position for the next prediction

        // Move each particle according to the displacement
        for (Particle particle : particles) {
            particle.position = new LatLng(particle.position.latitude + displacement.latitude,
                    particle.position.longitude + displacement.longitude);
        }
    }

    // Method to update the filter particles based on new adjusted weight considering measurement noise
    private void update(LatLng measurement, double measurementNoise) {
        // Update each particle's weight based on its distance to the measurement
        double totalWeight = 0.0;
        for (Particle particle : particles) {
            double distance = Math.sqrt(Math.pow(particle.position.latitude - measurement.latitude, 2) +
                    Math.pow(particle.position.longitude - measurement.longitude, 2));
            particle.weight = calculateLikelihood(distance, measurementNoise);
            totalWeight += particle.weight;
        }

        // Normalize the weights
        for (Particle particle : particles) {
            particle.weight /= totalWeight;
        }
    }

    // Method to get final fused position based on the a
    public LatLng getFusedPosition() {
        // Calculate the fused position as the weighted average of all particles
        double sumLat = 0.0;
        double sumLon = 0.0;
        double totalWeight = 0.0;

        for (Particle particle : particles) {
            sumLat += particle.position.latitude * particle.weight;
            sumLon += particle.position.longitude * particle.weight;
            totalWeight += particle.weight;
        }

        return new LatLng(sumLat / totalWeight, sumLon / totalWeight);
    }

    // Method to calculate Gaussian Likelihood
    private double calculateLikelihood(double distance, double measurementNoise) {
        double variance = measurementNoise * measurementNoise;
        return Math.exp(-(distance * distance) / (2 * variance)) / Math.sqrt(2 * Math.PI * variance);
    }

    // Systematic resampling method to update the complete filter
    public void resample() {
        List<Particle> newParticles = new ArrayList<>(numberOfParticles);
        double B = 0.0;
        double increment = 1.0 / numberOfParticles;
        double r = random.nextDouble() * increment;

        int index = 0;
        for (int i = 0; i < numberOfParticles; i++) {
            B += r + i * increment;
            while (B > particles.get(index).weight) {
                B -= particles.get(index).weight;
                index = (index + 1) % numberOfParticles;
            }
            newParticles.add(new Particle(particles.get(index).position, 1.0 / numberOfParticles));
        }
        particles = newParticles;
    }

}

/* ----------------------  NOTE ----------------------
 * it's normal and often expected for the estimated position produced by a particle filter to be very close to
 * the position indicated by a predictive marker.
 * This outcome can be a sign that the particle filter is performing well,
 * particularly if the predictive markers are accurate representations of the true position.
 * */
