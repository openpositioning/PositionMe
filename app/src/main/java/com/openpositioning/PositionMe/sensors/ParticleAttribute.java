package com.openpositioning.PositionMe.sensors;

// to generate random location for testing without presence of wifi/gnss
import java.util.Random;

/**
 * This class creates individual particles, and assign attributes to them such as location
 * and weight
 * This will be further utilised in ParticleFilter class
 *
 * @author Sofea Jazlan Arif
 */

public class ParticleAttribute {
    public float lat, lon;
    public float weight;

    public ParticleAttribute(float lat, float lon, float weight) {
        this.lat = lat;
        this.lon = lon;
        this.weight = weight;
    }

}

