package com.openpositioning.PositionMe.dataParser;

public class PositionData {
    private long relativeTimestamp;
    private double magX;
    private double magY;
    private double magZ;

    public PositionData(long relativeTimestamp, double magX, double magY, double magZ) {
        this.relativeTimestamp = relativeTimestamp;
        this.magX = magX;
        this.magY = magY;
        this.magZ = magZ;
    }

    public long getRelativeTimestamp() {
        return relativeTimestamp;
    }

    public double getMagX() {
        return magX;
    }

    public double getMagY() {
        return magY;
    }

    public double getMagZ() {
        return magZ;
    }

    @Override
    public String toString() {
        return "PositionData{" +
                "relativeTimestamp=" + relativeTimestamp +
                ", magX=" + magX +
                ", magY=" + magY +
                ", magZ=" + magZ +
                '}';
    }
}