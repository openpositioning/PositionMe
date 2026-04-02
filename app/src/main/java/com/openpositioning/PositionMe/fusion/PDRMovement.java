package com.openpositioning.PositionMe.fusion;

/** Represents a planar PDR displacement for a single update step. */
public class PDRMovement {
    public final float deltaX;
    public final float deltaY;

    public PDRMovement(float deltaX, float deltaY) {
        this.deltaX = deltaX;
        this.deltaY = deltaY;
    }
}
