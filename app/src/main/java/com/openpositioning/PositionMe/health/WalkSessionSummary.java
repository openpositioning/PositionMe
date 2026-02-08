package com.openpositioning.PositionMe.health;

/**
 * Represents a summary of a user's walk session.
 * This is an immutable data class containing the key metrics of a single walk.
 */
public class WalkSessionSummary {

    private final String sessionId;
    private final int distanceMeters;
    private final int durationSeconds;
    private final double outdoorRatio;

    /**
     * Constructs a new WalkSessionSummary.
     *
     * @param sessionId A unique identifier for the session. If null, defaults to an empty string.
     * @param distanceMeters The total distance walked in meters. Must be non-negative.
     * @param durationSeconds The total duration of the walk in seconds. Must be non-negative.
     * @param outdoorRatio The fraction of the walk that was outdoors (0.0 to 1.0). Clamped to range.
     */
    public WalkSessionSummary(String sessionId, int distanceMeters, int durationSeconds, double outdoorRatio) {
        this.sessionId = (sessionId == null) ? "" : sessionId;
        this.distanceMeters = Math.max(0, distanceMeters);
        this.durationSeconds = Math.max(0, durationSeconds);
        this.outdoorRatio = Math.max(0.0, Math.min(1.0, outdoorRatio));
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

    /**
     * @return The fraction of the walk that was outdoors, from 0.0 (entirely indoors) to 1.0 (entirely outdoors).
     */
    public double getOutdoorRatio() {
        return outdoorRatio;
    }
}
