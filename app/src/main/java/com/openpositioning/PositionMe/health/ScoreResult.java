package com.openpositioning.PositionMe.health;

/**
 * Represents the result of a score calculation.
 * <p>
 * This is an immutable data class that holds a score and a corresponding feedback message.
 */
public class ScoreResult {

    /** The calculated score, always clamped to the range [0, 100]. */
    private final int score0to100;

    /** A human-readable feedback message related to the score. Never null. */
    private final String feedbackText;

    /**
     * Constructs a new ScoreResult.
     *
     * @param score0to100 The calculated score. It will be automatically clamped to the range [0, 100].
     * @param feedbackText The feedback message. If null, it will be converted to an empty string.
     */
    public ScoreResult(int score0to100, String feedbackText) {
        this.score0to100 = Math.max(0, Math.min(score0to100, 100));
        this.feedbackText = (feedbackText == null) ? "" : feedbackText;
    }

    /**
     * @return The score, guaranteed to be between 0 and 100 (inclusive).
     */
    public int getScore0to100() {
        return score0to100;
    }

    /**
     * @return The feedback message, guaranteed to be non-null.
     */
    public String getFeedbackText() {
        return feedbackText;
    }
}
