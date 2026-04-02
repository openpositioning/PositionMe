package com.openpositioning.PositionMe.mapmatching;

/**
 * Represents a single particle in the particle filter for indoor map matching.
 *
 * <p>Each particle holds a 2D position in easting/northing (metres), a floor index,
 * and a weight reflecting how well the particle agrees with observations and map
 * constraints.</p>
 *
 * @see MapMatchingEngine the particle filter that manages a population of particles
 */
public class Particle {

    /** Easting position in metres relative to the reference point. */
    private double x;

    /** Northing position in metres relative to the reference point. */
    private double y;

    /** Floor index (0-based, matching FloorShapes list order). */
    private int floor;

    /** Importance weight of this particle. */
    private double weight;

    /**
     * Creates a new particle with the given position, floor, and weight.
     *
     * @param x      easting in metres
     * @param y      northing in metres
     * @param floor  floor index
     * @param weight importance weight
     */
    public Particle(double x, double y, int floor, double weight) {
        this.x = x;
        this.y = y;
        this.floor = floor;
        this.weight = weight;
    }

    /**
     * Creates a deep copy of this particle.
     *
     * @return a new Particle with the same state
     */
    public Particle copy() {
        return new Particle(x, y, floor, weight);
    }

    // --- Getters and setters ---

    public double getX() { return x; }
    public void setX(double x) { this.x = x; }

    public double getY() { return y; }
    public void setY(double y) { this.y = y; }

    public int getFloor() { return floor; }
    public void setFloor(int floor) { this.floor = floor; }

    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = weight; }
}
