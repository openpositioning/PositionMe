package com.openpositioning.PositionMe.fusion;

import java.util.Random;

import org.ejml.simple.SimpleMatrix;

public class Particle {
    double x, y, theta;  // Position and orientation of the particle
    double weight;       // Weight assigned to each particle
    double logWeight;    // Log-weight for numerical stability

    // Constructor
    public Particle(double x, double y, double theta) {
        this.x = x;
        this.y = y;
        this.theta = theta;

        this.weight = 1.0;
        this.logWeight = 0.0;
    }

    // Update particle position when step is detected
    public void updateDynamic(double stepLength, double currentHeading, double[] dynamicPDRStds) {
        Random rand = new Random();

        // Add noise to heading change
        this.theta += currentHeading + rand.nextGaussian() * dynamicPDRStds[1];

        // Add noise to step length
        double noiseStep = rand.nextGaussian() * dynamicPDRStds[0];
        double noisyStepLength = Math.max(0, stepLength + noiseStep); // Ensure non-negative step

        // Update position using angle
        this.x += noisyStepLength * Math.sin(this.theta);
        this.y += noisyStepLength * Math.cos(this.theta);
    }

}
