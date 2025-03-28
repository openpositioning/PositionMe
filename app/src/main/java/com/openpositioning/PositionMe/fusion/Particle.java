package com.openpositioning.PositionMe.fusion;

import java.util.Random;

import org.ejml.simple.SimpleMatrix;

public class Particle {
    double x, y, theta;  // Position and orientation of the particle

    double weight; // Weight assigned to each particle

    // Constructor
    public Particle(double x, double y, double theta) {
        this.x = x;
        this.y = y;
        this.theta = theta;
    }

    public void updateStatic(double[] staticPDRStds) {
        Random rand = new Random();
        this.theta += rand.nextGaussian() * staticPDRStds[1];
        this.x += rand.nextGaussian()*staticPDRStds[0];
        this.y += rand.nextGaussian()*staticPDRStds[0];
    }

    // Update particle position when step is detected
    public void updateDynamic(double stepLength, double headingChange, double[] dynamicPDRStds) {
        Random rand = new Random();

        // Derive update and add noise
        this.theta += headingChange + rand.nextGaussian() * dynamicPDRStds[1];
        this.x += (stepLength + dynamicPDRStds[0]) * Math.cos(this.theta);
        this.y += (stepLength + dynamicPDRStds[0]) * Math.sin(this.theta);

    }

    // Reweight particles
    public void reweight(SimpleMatrix measurementCovariance,
                         SimpleMatrix forwardModel,
                         SimpleMatrix measurementVector) {

        SimpleMatrix positionVector = new SimpleMatrix(new double[] {this.x, this.y, this.theta});
        SimpleMatrix error = measurementVector.minus(forwardModel.mult(positionVector));

        double logProb = -0.5*error.transpose().mult(measurementCovariance.invert()).mult(error).get(0);
        double prob = Math.exp(logProb);

        this.weight *= prob;
    }
}
