package com.openpositioning.PositionMe.mapmatching;

/**
 * 表示由高度/气压估计得到的楼层变化提示。
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