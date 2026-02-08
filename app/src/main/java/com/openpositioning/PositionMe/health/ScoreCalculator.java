package com.openpositioning.PositionMe.health;

public class ScoreCalculator {

    private static final double DISTANCE_THRESHOLD = 3000.0;
    private static final double DURATION_THRESHOLD = 1800.0;

    public ScoreResult calculateScore(WalkSessionSummary summary) {
        double distanceScore = Math.min(summary.getDistanceMeters() / DISTANCE_THRESHOLD, 1.0);
        double durationScore = Math.min(summary.getDurationSeconds() / DURATION_THRESHOLD, 1.0);

        double baseScore = ((distanceScore + durationScore) / 2.0) * 100;

        // Apply outdoor bonus
        double outdoorBonus = baseScore * summary.getOutdoorRatio() * 0.1; // 10% bonus
        int finalScore = (int) Math.min(baseScore + outdoorBonus, 100);

        String feedback = generateFeedback(finalScore);

        return new ScoreResult(finalScore, feedback);
    }

    private String generateFeedback(int score) {
        if (score >= 90) {
            return "Excellent walk! Keep up the great work.";
        } else if (score >= 70) {
            return "Good job! You're making great progress.";
        } else if (score >= 50) {
            return "Nice walk! Let's aim for a bit longer next time.";
        } else if (score >= 30) {
            return "A good start! Every step counts.";
        } else {
            return "Let's get moving! Even a short walk is beneficial.";
        }
    }
}
