package com.openpositioning.PositionMe.health;

public class ScoreResult {
    private final int score0to100;
    private final String feedbackText;

    public ScoreResult(int score0to100, String feedbackText) {
        this.score0to100 = score0to100;
        this.feedbackText = feedbackText;
    }

    public int getScore0to100() {
        return score0to100;
    }

    public String getFeedbackText() {
        return feedbackText;
    }
}
