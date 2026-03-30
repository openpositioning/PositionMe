package com.openpositioning.PositionMe.fusion;

/**
 * Mutable particle state used internally by the particle filter.
 *
 * <p>Each particle stores a candidate user pose and an importance weight.
 * The filter propagates, reweights, and resamples these particles over time.</p>
 */
public class Particle {

    double x;
    double y;
    double theta;
    int floor;
    double weight;

    // Previous state from the last predict() boundary.
    // These are used for discrete map-constraint checks such as wall crossing.
    double prevX;
    double prevY;
    int prevFloor;

    /**
     * Creates a particle with the supplied state.
     *
     * @param x local x coordinate in meters
     * @param y local y coordinate in meters
     * @param theta heading in radians
     * @param floor floor index
     * @param weight importance weight
     */
    public Particle(double x, double y, double theta, int floor, double weight) {
        this.x = x;
        this.y = y;
        this.theta = theta;
        this.floor = floor;
        this.weight = weight;
        this.prevX = x;
        this.prevY = y;
        this.prevFloor = floor;
    }

    /**
     * Returns a deep copy of this particle.
     *
     * @return copied particle
     */
    public Particle copy() {
        Particle copy = new Particle(x, y, theta, floor, weight);
        copy.prevX = prevX;
        copy.prevY = prevY;
        copy.prevFloor = prevFloor;
        return copy;
    }
}
