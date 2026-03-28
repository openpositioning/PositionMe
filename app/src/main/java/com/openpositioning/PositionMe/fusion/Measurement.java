package com.openpositioning.PositionMe.fusion;

/** Represents a position measurement with an accuracy estimate. */
public class Measurement {
    public final float x;
    public final float y;
    public final double accuracy;

    public Measurement(float x, float y, double accuracy) {
        this.x = x;
        this.y = y;
        this.accuracy = accuracy;
    }
}
