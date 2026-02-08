package com.openpositioning.PositionMe.health;

public class WalkSessionSummary {
    private final String sessionId;
    private final int distanceMeters;
    private final int durationSeconds;
    private final double outdoorRatio;

    public WalkSessionSummary(String sessionId, int distanceMeters, int durationSeconds, double outdoorRatio) {
        this.sessionId = sessionId;
        this.distanceMeters = distanceMeters;
        this.durationSeconds = durationSeconds;
        this.outdoorRatio = outdoorRatio;
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getDistanceMeters() {
        return distanceMeters;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public double getOutdoorRatio() {
        return outdoorRatio;
    }
}
