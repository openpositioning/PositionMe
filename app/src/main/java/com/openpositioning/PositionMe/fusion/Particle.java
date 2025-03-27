package com.openpositioning.PositionMe.fusion;

import java.util.Random;

import org.ejml.simple.SimpleMatrix;

public class Particle {
    double x, y;  // Position and orientation of the particle

    // Constructor
    public Particle(double x, double y, double theta) {
        this.x = x;
        this.y = y;
    }

    // Update particle position with noise
    public void update(double stepLength, double headingValue, double[] noiseStd) {
        Random rand = new Random();

        // Derive update and add noise

        this.x += stepLength*Math.cos(b) + (rand.nextGaussian() * noiseStd[0]);
        this.y += stepLength*Math.sin(this.theta) + (rand.nextGaussian() * noiseStd[1]);


    }
}
