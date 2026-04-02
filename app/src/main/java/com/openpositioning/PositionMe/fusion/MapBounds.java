package com.openpositioning.PositionMe.fusion;

public class MapBounds {
    public final float minX;
    public final float maxX;
    public final float minY;
    public final float maxY;

    public MapBounds(float minX, float maxX, float minY, float maxY) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
    }
}
