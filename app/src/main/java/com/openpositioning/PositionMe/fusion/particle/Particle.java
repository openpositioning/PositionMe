package com.openpositioning.PositionMe.fusion.particle;

import com.openpositioning.PositionMe.utils.WallDetectionManager;

import org.apache.commons.math3.distribution.TDistribution;

import java.util.Random;

/**
 * Represents a particle for use in the particle filter algorithm.
 *
 * <p>Each particle maintains a position (x,y), orientation (theta) and weight.
 * Particles are updated based on sensor measurements and weighted according to
 * their likelihood of representing the true position.
 *
 * @author Nick Manturov
 */
public class Particle {
    Double x, y, theta;  // Position and orientation of the particle
    Double weight;       // Weight assigned to each particle
    Double logWeight;    // Log-weight for numerical stability

    /**
     * Creates a new particle with specified position and orientation.
     *
     * @param x x-coordinate position
     * @param y y-coordinate position
     * @param theta orientation angle in radians
     */
    public Particle(double x, double y, double theta) {
        this.x = x;
        this.y = y;
        this.theta = theta;

        this.weight = 1.0;
        this.logWeight = 0.0;
    }

    /**
     * Updates particle position when step is detected.
     *
     * <p>Applies noise to the heading and step length, then moves the particle.
     * Checks for wall collisions if a wall detector is provided.
     *
     * @param stepLength detected step length in meters
     * @param currentHeading current heading angle in radians
     * @param dynamicPDRStds standard deviations for PDR noise model
     * @param wallChecker wall detection manager for collision checking
     * @param buildingType building identifier
     * @param floor floor number
     */
    public void updateDynamic(double stepLength, double currentHeading, double[] dynamicPDRStds, WallDetectionManager wallChecker, int buildingType, int floor) {
        Random rand = new Random();

        // Add noise to heading change
        this.theta = currentHeading + rand.nextGaussian() * dynamicPDRStds[1];
        stepLength += rand.nextGaussian() * dynamicPDRStds[0];

        // Update position using angle
        double dx = stepLength * Math.cos(this.theta);
        double dy = stepLength * Math.sin(this.theta);

        if (wallChecker != null) {
            boolean collision = wallChecker.doesTrajectoryIntersectWall(new double[] {this.x, this.y}, new double[] {this.x+dx, this.y+dy}, buildingType, floor);
            this.weight = collision ? 0.0 : this.weight;
            this.logWeight = collision ? Math.log(0.0) : this.logWeight;
        }

        this.x += dx;
        this.y += dy;
    }

}