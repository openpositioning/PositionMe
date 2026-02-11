package com.openpositioning.PositionMe.data.model;

public class TestPointInfo {
    public int number;
    public double latitude;
    public double longitude;
    public long timestamp;

    public TestPointInfo(int number, double latitude, double longitude, long timestamp) {
        this.number = number;
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestamp = timestamp;
    }
}
