package com.openpositioning.PositionMe.fusion;

/**
 * Representation of a particle within the {@link ParticleFilter} used in {@link Fusion}
 *
 * <p>As {@link Fusion} requires Easting-Northing coordinates, {@link
 * com.google.android.gms.maps.model.LatLng} points must be converted before creating a new Particle
 * (see {@link ParticleFilter#latLngToEN(double, double) ParticleFilter.latLngToEN()})
 *
 * @see ParticleFilter
 * @see Fusion
 */
public class Particle {
    double easting;
    double northing;
    double orientationError; // radians: correction to apply to raw PDR heading
    double weight;

    public Particle(double easting, double northing, double orientationError, double weight) {
        this.easting = easting;
        this.northing = northing;
        this.orientationError = orientationError;
        this.weight = weight;
    }

    /**
     * Retrieve the local Easting-Northing coordinates for a give particle
     *
     * @return The particle's Easting and Northing values in an array
     */
    public double[] getEastingNorthing() {
        return new double[] {easting, northing};
    }

    /**
     * Retrieve the particle's weight
     *
     * @return The particle's weight
     */
    public double getWeight() {
        return weight;
    }
}
