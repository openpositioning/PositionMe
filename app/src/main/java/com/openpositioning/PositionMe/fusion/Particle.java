package com.openpositioning.PositionMe.fusion;

import java.util.Random;

/** Represents a single particle in the particle filter. */
public class Particle {
    float x;
    float y;
    public float oldX;
    public float oldY;
    int floor;
    double weight;

    /**
     * Creates a particle with an initial position and floor.
     *
     * @param x initial x coordinate in meters
     * @param y initial y coordinate in meters
     * @param floor initial floor index
     */
    public Particle(float x, float y, int floor) {
        this.x = x;
        this.y = y;
        this.oldX = x;
        this.oldY = y;
        this.floor = floor;
        this.weight = 1.0;
    }

    /**
     * Moves the particle using the default motion noise.
     *
     * @param deltaX movement along the x axis in meters
     * @param deltaY movement along the y axis in meters
     * @param random random source used to sample motion noise
     */
    public void move(float deltaX, float deltaY, Random random) {
        move(deltaX, deltaY, random, 0.03f);
    }

    /**
     * Moves the particle and adds Gaussian motion noise.
     *
     * @param deltaX movement along the x axis in meters
     * @param deltaY movement along the y axis in meters
     * @param random random source used to sample motion noise
     * @param noiseStdDev standard deviation of the motion noise in meters
     */
    public void move(float deltaX, float deltaY, Random random, float noiseStdDev) {
        this.oldX = this.x;
        this.oldY = this.y;
        this.x += deltaX;
        this.y += deltaY;
        this.x += random.nextGaussian() * noiseStdDev;
        this.y += random.nextGaussian() * noiseStdDev;
    }
}
