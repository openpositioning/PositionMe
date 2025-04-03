package com.openpositioning.PositionMe.dataParser;

public class PdrData {
    private long relativeTimestamp;
    private float x;
    private float y;

    public PdrData(long relativeTimestamp, float x, float y) {
        this.relativeTimestamp = relativeTimestamp;
        this.x = x;
        this.y = y;
    }

    public long getRelativeTimestamp() {
        return relativeTimestamp;
    }

    public void setRelativeTimestamp(long relativeTimestamp) {
        this.relativeTimestamp = relativeTimestamp;
    }

    public float getX() {
        return x;
    }

    public void setX(float x) {
        this.x = x;
    }

    public float getY() {
        return y;
    }

    public void setY(float y) {
        this.y = y;
    }

    @Override
    public String toString() {
        return "PdrData{" +
                "timestamp=" + relativeTimestamp +
                ", x=" + x +
                ", y=" + y +
                '}';
    }
}