package com.openpositioning.PositionMe.health;

/**
 * Calculates a wellbeing score based on a walk session summary.
 *
 * This class computes a score from 0-100 based on a walk's distance and duration
 * against configurable thresholds. It also applies a bonus for maintaining a
 * healthy pace.
 */
public class ScoreCalculator {

    //region Constants
    private static final double DEFAULT_DISTANCE_THRESHOLD_M = 3000.0; // 3km
    private static final double DEFAULT_DURATION_THRESHOLD_S = 1800.0; // 30min

    private static final double HEALTHY_PACE_THRESHOLD_MS = 1.4; // ~5 km/h
    private static final int INTENSITY_BONUS = 5;
    //endregion

    private final double distanceThresholdM;
    private final double durationThresholdS;

    /**
     * Constructs a ScoreCalculator with custom goals for distance and duration.
     *
     * @param distanceGoal The target distance for a session in meters.
     * @param durationGoal The target duration for a session in seconds.
     */
    public ScoreCalculator(double distanceGoal, double durationGoal) {
        this.distanceThresholdM = distanceGoal;
        this.durationThresholdS = durationGoal;
    }

    /**
     * Constructs a ScoreCalculator with default goals (3km, 30 minutes).
     */
    public ScoreCalculator() {
        this.distanceThresholdM = DEFAULT_DISTANCE_THRESHOLD_M;
        this.durationThresholdS = DEFAULT_DURATION_THRESHOLD_S;
    }

    /**
     * Calculates the score for a single session.
     *
     * @param summary The summary of the walk session to be scored.
     * @return A {@link ScoreResult} containing the final score and feedback.
     */
    public ScoreResult calculateScore(WalkSessionSummary summary) {
        if (summary == null) {
            return new ScoreResult(0, "No walk data available yet.");
        }

        // Calculate the score based on the single session.
        int score = calculateRawScore(summary);

        // Generate feedback and return the final result.
        return new ScoreResult(score, generateFeedback(score));
    }

    /**
     * Calculates the raw score for a single session using a smooth efficiency bonus.
     */
    private int calculateRawScore(WalkSessionSummary summary) {
        // 1. Calculate the raw completion ratios for distance and time.
        double dNorm = (distanceThresholdM <= 0) ? 0.0 : Math.min(summary.getDistanceMeters() / distanceThresholdM, 1.0);
        double tNorm = (durationThresholdS <= 0) ? 0.0 : Math.min(summary.getDurationSeconds() / durationThresholdS, 1.0);

        // 2. Adjust the time score based on distance completion.
        // This formula smoothly boosts the time score as distance completion increases, rewarding efficiency.
        double tNormAdjusted = tNorm + ((1 - tNorm) * dNorm);

        // 3. The base score is a weighted average of distance and the adjusted time score.
        double base = 100.0 * (0.5 * dNorm + 0.5 * tNormAdjusted);

        // 4. Add a bonus for maintaining a healthy pace.
        int intensityBonus = 0;
        if (summary.getDurationSeconds() > 0) {
            double pace = (double) summary.getDistanceMeters() / summary.getDurationSeconds();
            if (pace >= HEALTHY_PACE_THRESHOLD_MS) {
                intensityBonus = INTENSITY_BONUS;
            }
        }
        return clamp0to100((int) Math.round(base) + intensityBonus);
    }

    /**
     * Generates a feedback message based on the final score.
     */
    private String generateFeedback(int score) {
        if (score >= 90) return "Excellent walk! Keep it up.";
        if (score >= 70) return "Good job — solid progress.";
        if (score >= 50) return "Nice walk. Try a little longer next time.";
        if (score >= 30) return "Good start — every step counts.";
        return "Let’s get moving — even a short walk helps.";
    }

    // Helper to clamp a value between 0 and 100.
    private static int clamp0to100(int v) { return Math.max(0, Math.min(100, v)); }
}
