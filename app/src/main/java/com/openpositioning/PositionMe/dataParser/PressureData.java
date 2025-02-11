package com.openpositioning.PositionMe.dataParser;

/**
 * Model class for pressure data.
 * Stores the relative timestamp and the relative altitude (in meters)
 * calculated from the pressure reading.
 */
public class PressureData {
    private long relativeTimestamp;
    private float relativeAltitude; // altitude in meters relative to baseline

    public PressureData(long relativeTimestamp, float relativeAltitude) {
        this.relativeTimestamp = relativeTimestamp;
        this.relativeAltitude = relativeAltitude;
    }

    public long getRelativeTimestamp() {
        return relativeTimestamp;
    }

    public float getRelativeAltitude() {
        return relativeAltitude;
    }

    @Override
    public String toString() {
        return "PressureData{" +
                "relativeTimestamp=" + relativeTimestamp +
                ", relativeAltitude=" + relativeAltitude +
                '}';
    }
}
