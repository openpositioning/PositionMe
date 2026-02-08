package com.openpositioning.PositionMe.health;

/**
 * Calculates a wellbeing/walk score based on a user's walk session summary.
 *
 * - It calculates a base score from distance and duration, each capped at a target threshold.
 * - It adds an optional bonus for outdoor activity.
 * - The final score is normalized to a 0-100 scale and paired with a feedback message.
 */
public class ScoreCalculator {

    // Target thresholds for a "full" score in each dimension.
    private static final double DISTANCE_THRESHOLD_M = 3000.0; // 3km
    private static final double DURATION_THRESHOLD_S = 1800.0; // 30min

    // Weights for combining normalized distance and duration. Must sum to 1.0.
    private static final double W_DISTANCE = 0.5;
    private static final double W_DURATION = 0.5;

    // Bonus points for outdoor activity. A ratio of 1.0 outdoor gives the max bonus.
    private static final int OUTDOOR_BONUS_MAX = 10;

    /**
     * Calculates the score for a given walk session.
     *
     * @param summary The summary of the walk session.
     * @return A {@link ScoreResult} containing the score and feedback.
     */
    public ScoreResult calculateScore(WalkSessionSummary summary) {
        if (summary == null) {
            return new ScoreResult(0, "No walk data available yet.");
        }

        // 1. Normalize distance and duration to a 0.0-1.0 scale against their thresholds.
        double dNorm = (DISTANCE_THRESHOLD_M <= 0) ? 0.0 :
                Math.min(summary.getDistanceMeters() / DISTANCE_THRESHOLD_M, 1.0);
        double tNorm = (DURATION_THRESHOLD_S <= 0) ? 0.0 :
                Math.min(summary.getDurationSeconds() / DURATION_THRESHOLD_S, 1.0);

        // 2. Calculate a weighted average for the base score (0-100).
        double base = 100.0 * (W_DISTANCE * dNorm + W_DURATION * tNorm);

        // 3. Calculate the outdoor bonus.
        int outdoorBonus = (int) Math.round(OUTDOOR_BONUS_MAX * clamp01(summary.getOutdoorRatio()));

        // 4. Combine base score and bonus, and clamp to the final 0-100 range.
        int finalScore = clamp0to100((int) Math.round(base) + outdoorBonus);

        // 5. Generate feedback and return the result.
        return new ScoreResult(finalScore, generateFeedback(finalScore));
    }

    /**
     * Generates a feedback message based on the final score.
     *
     * @param score The final score (0-100).
     * @return A feedback string.
     */
    private String generateFeedback(int score) {
        if (score >= 90) return "Excellent walk! Keep it up.";
        if (score >= 70) return "Good job — solid progress.";
        if (score >= 50) return "Nice walk. Try a little longer next time.";
        if (score >= 30) return "Good start — every step counts.";
        return "Let’s get moving — even a short walk helps.";
    }

    /**
     * Clamps a double value to the range [0.0, 1.0].
     */
    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    //Clamps an integer value to the range [0, 100].

    private static int clamp0to100(int v) {
        return Math.max(0, Math.min(100, v));
    }
}
