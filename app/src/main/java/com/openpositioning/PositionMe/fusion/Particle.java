package com.openpositioning.PositionMe.fusion;

import java.util.Random;
import org.apache.commons.math3.distribution.TDistribution;

import org.ejml.simple.SimpleMatrix;

public class Particle {
    double x, y, theta, stepLength;  // Position and orientation of the particle
    double weight;       // Weight assigned to each particle
    double logWeight;    // Log-weight for numerical stability

    // Constructor
    public Particle(double x, double y, double theta, double stepLength) {
        this.x = x;
        this.y = y;
        this.theta = theta;
        this.stepLength = stepLength;

        this.weight = 1.0;
        this.logWeight = 0.0;
    }

    // Update particle position when step is detected
    public void updateDynamic(double stepLength, double headingChange, double[] dynamicPDRStds) {
        TDistribution tdist = new TDistribution(2);

        // Add noise to heading change and step length change
        this.theta += headingChange + tdist.sample() * dynamicPDRStds[2];
        double stepInt = Math.max(0.5, tdist.sample() * dynamicPDRStds[1]);
        this.stepLength = Math.min(stepInt, 1);

        // Update position using angle
        this.x += this.stepLength * Math.sin(this.theta);
        this.y += this.stepLength * Math.cos(this.theta);
    }

}
