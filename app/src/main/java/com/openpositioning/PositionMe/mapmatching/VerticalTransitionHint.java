package com.openpositioning.PositionMe.mapmatching;

/**
 * Represents a floor-change hint estimated from height / barometer data.
 */
public class VerticalTransitionHint {

    private final double currentElevation;
    private final double deltaHeight;
    private final boolean heightChanged;

    public VerticalTransitionHint(double currentElevation, double deltaHeight, boolean heightChanged) {
        this.currentElevation = currentElevation;
        this.deltaHeight = deltaHeight;
        this.heightChanged = heightChanged;
    }

    public double getCurrentElevation() {
        return currentElevation;
    }

    public double getDeltaHeight() {
        return deltaHeight;
    }

    public boolean isHeightChanged() {
        return heightChanged;
    }
}