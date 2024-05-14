package com.openpositioning.PositionMe.sensors;


/**
 * {@inheritDoc}
 *
 *Class to handle the server response for the json files received from the server and the radiomap
 * @author Michalis Voudaskas
 */
public class LocationResponse {
    private double latitude;
    private double longitude;
    private int floor;

    // Constructors, getters, and setters
    public LocationResponse(double latitude, double longitude, int floor) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.floor = floor;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public int getFloor() {
        return floor;
    }
}
