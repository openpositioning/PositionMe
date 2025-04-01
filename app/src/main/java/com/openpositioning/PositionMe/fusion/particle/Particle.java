package com.openpositioning.PositionMe.fusion.particle;

import com.openpositioning.PositionMe.utils.CoordinateConverter;

import org.apache.commons.math3.distribution.TDistribution;

import java.util.Random;

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
    public void updateDynamic(double stepLength, double headingChange, double[] dynamicPDRStds) {
        Random rand = new Random();

        // Add noise to heading change
        this.theta += headingChange + rand.nextGaussian() * dynamicPDRStds[1];
        //this.theta += Math.PI/4;
        this.theta = CoordinateConverter.normalizeAngleToPi(this.theta);
        stepLength += rand.nextGaussian() * dynamicPDRStds[0];

        // Update position using angle
        this.x += stepLength * Math.cos(this.theta);
        this.y += stepLength * Math.sin(this.theta);
    }

}
