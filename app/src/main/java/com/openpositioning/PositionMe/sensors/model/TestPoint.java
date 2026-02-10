package com.openpositioning.PositionMe.sensors.model;

public class TestPoint {
    public final long timestampMillis;
    public final double latitude;
    public final double longitude;

    public TestPoint(long timestampMillis, double latitude, double longitude) {
        this.timestampMillis = timestampMillis;
        this.latitude = latitude;
        this.longitude = longitude;
    }

}
