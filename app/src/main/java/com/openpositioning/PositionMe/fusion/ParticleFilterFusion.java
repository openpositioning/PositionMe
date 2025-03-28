package com.openpositioning.PositionMe.fusion;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.CoordinateConverter;
import com.openpositioning.PositionMe.fusion.Particle;

import org.ejml.simple.SimpleMatrix;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ParticleFilterFusion implements IPositionFusionAlgorithm {
    private static final String TAG = "ParticleFilterFusion";

    // Process noise scale (PDR) update {X, Y}
    private static final double[] staticPDRStds = {1, 1, Math.PI/12};
    private static final double[] dynamicPDRStds = {0.1, 0.1, Math.PI/36};
    private SimpleMatrix forwardVariance;
    private static final double GNSS_NOISE = 5.0;       // Measurement noise for GNSS (in meters)
    private static final double WIFI_NOISE = 10.0;      // Measurement noise for WiFi (in meters)

    // Particle parameters
    private List<Particle> particles;  // List of particles
    private int numParticles;
    private float[] mapBoundaries;

    // The reference point for ENU coordinates
    private double[] referencePosition;   // [lat, lng, alt]

    // Particle parameters
    private long lastUpdateTime;

    private LatLng FusedPosition;


    // Constructor
    public ParticleFilterFusion(int numParticles, float[] initialPosition) {

        // Get particle positions
        this.numParticles = numParticles;
        //this.mapBoundaries = mapBoundaries;
        this.particles = new ArrayList<>();

        // Initialize timestamp
        lastUpdateTime = System.currentTimeMillis();

        // Set initial particle distribution
        initializeParticles(initialPosition);
    }


    public void processPdrUpdate(float stepLength, float headingChange) {
        long currentTime = System.currentTimeMillis();
        double deltaTime = (currentTime - lastUpdateTime) / 1000.0; // Convert to seconds


        // Update particle parameters
        moveParticlesDynamic(stepLength, headingChange);

        // Reweight and resample



        lastUpdateTime = currentTime;
    }

    public LatLng getFusedPosition() {return FusedPosition;};

    public void processGnssUpdate(LatLng position, double altitude) {};

    public void processWifiUpdate(LatLng position) {};

    // Initialize particles randomly within the map dimensions
    private void initializeParticles() {
        Random rand = new Random();
        for (int i = 0; i < numParticles; i++) {
            double x = rand.nextDouble() * (mapBoundaries[1] - mapBoundaries[0]);
            double y = rand.nextDouble() * (mapBoundaries[3] - mapBoundaries[2]);
            double theta = rand.nextDouble() * 2 * Math.PI;  // Random orientation
            particles.add(new Particle(x, y, theta));
        }
    }

    // Simulate motion update of particles
    public void moveParticlesStatic(double[] staticPDRStds) {
        for (Particle particle : particles) {
            particle.updateStatic(staticPDRStds);
        }
    }

    // Resample particles based on their weights (simplified here)
    public void resample() {
        Random rand = new Random();
        List<Particle> newParticles = new ArrayList<>();

        // Assuming equal weight for all particles (uniform resampling)
        for (int i = 0; i < numParticles; i++) {
            int index = rand.nextInt(particles.size());
            Particle selectedParticle = particles.get(index);
            newParticles.add(new Particle(selectedParticle.x, selectedParticle.y));
        }

        particles = newParticles;
    }

    // Get the estimated position (average position of all particles)
    public double[] getEstimatedPosition() {
        double avgX = 0, avgY = 0;
        for (Particle particle : particles) {
            avgX += particle.x;
            avgY += particle.y;
        }
        avgX /= numParticles;
        avgY /= numParticles;

        return new double[]{avgX, avgY};
    }
}