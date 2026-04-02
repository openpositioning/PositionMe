package com.openpositioning.PositionMe.sensors.fusion;

/**
 * Represents a single particle in the particle filter.
 * Each particle holds a hypothesis of the user's position (easting/northing)
 * along with floor level and importance weight.
 */
public class Particle {

    /** Easting coordinate in meters relative to the ENU origin. */
    public double x;

    /** Northing coordinate in meters relative to the ENU origin. */
    public double y;

    /** Floor index (0-based). */
    public int floor;

    /** Importance weight, normalized across all particles. */
    public double weight;

    /**
     * Constructs a particle with the given position, floor, and weight.
     *
     * @param x      easting in metres (ENU)
     * @param y      northing in metres (ENU)
     * @param floor  floor index (0-based)
     * @param weight importance weight
     */
    public Particle(double x, double y, int floor, double weight) {
        this.x = x;
        this.y = y;
        this.floor = floor;
        this.weight = weight;
    }

    /** Creates a deep copy of this particle. */
    public Particle copy() {
        return new Particle(x, y, floor, weight);
    }
}
