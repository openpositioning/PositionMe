package com.openpositioning.PositionMe.mapmatching;

/**
 * 表示相邻两次位置更新之间的运动增量。
 */
public class MotionDelta {

    private final double deltaX;
    private final double deltaY;
    private final double stepDistance;
    private final double headingDeg;

    public MotionDelta(double deltaX, double deltaY, double stepDistance, double headingDeg) {
        this.deltaX = deltaX;
        this.deltaY = deltaY;
        this.stepDistance = stepDistance;
        this.headingDeg = headingDeg;
    }

    public double getDeltaX() {
        return deltaX;
    }

    public double getDeltaY() {
        return deltaY;
    }

    public double getStepDistance() {
        return stepDistance;
    }

    public double getHeadingDeg() {
        return headingDeg;
    }
}