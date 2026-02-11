package com.openpositioning.PositionMe.health;

import org.junit.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/**
 * Unit tests for the {@link ScoreCalculator}.
 * This class covers all scoring logic, including custom goals, bonuses, and edge cases.
 */
public class ScoreCalculatorTest {

    // Helper to create timestamps for testing consistency.
    private long daysAgo(int days) {
        return System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
    }

    // =============================================================================================
    // Basic Score Tests (Using Default Thresholds: 3km, 30min)
    // =============================================================================================

    @Test
    public void testBasicScore_halfGoals_withPaceBonus() {
        ScoreCalculator calculator = new ScoreCalculator(); // Default goals
        // Walked 1.5km in 15min. Pace is 1.67 m/s, which is > 1.4 m/s.
        WalkSessionSummary summary = new WalkSessionSummary("s1", 1500, 900, daysAgo(0));
        ScoreResult result = calculator.calculateScore(summary);
        // With the adjusted time score and pace bonus, the expected score is 68.
        assertEquals(68, result.getScore0to100());
    }

    @Test
    public void testBasicScore_fullGoalsMet() {
        ScoreCalculator calculator = new ScoreCalculator();
        WalkSessionSummary summary = new WalkSessionSummary("s2", 3000, 1800, daysAgo(0));
        ScoreResult result = calculator.calculateScore(summary);
        // base = 100. intensityBonus = 5. rawScore is clamp0to100(100 + 5) = 100.
        assertEquals(100, result.getScore0to100());
    }

    @Test
    public void testPerfectScore_AboveThresholds() {
        ScoreCalculator calculator = new ScoreCalculator();
        // Exceed goals: 4km in 25 minutes. High pace.
        WalkSessionSummary summary = new WalkSessionSummary("s_above", 4000, 1500, daysAgo(0));
        ScoreResult result = calculator.calculateScore(summary);
        // Raw score will be > 100, but it should be clamped to 100.
        assertEquals(100, result.getScore0to100());
    }

    // =============================================================================================
    // Bonus Logic Tests
    // =============================================================================================

    @Test
    public void testIntensityBonus_notMet() {
        ScoreCalculator calculator = new ScoreCalculator();
        // Walk slower than 1.4m/s (1000m in 1000s = 1.0 m/s).
        WalkSessionSummary summary = new WalkSessionSummary("s7", 1000, 1000, daysAgo(0));
        ScoreResult result = calculator.calculateScore(summary);
        // With the adjusted time score, the base is ~52. No pace bonus. Total = 52.
        assertEquals(52, result.getScore0to100());
    }

    // =============================================================================================
    // Custom Goal Tests
    // =============================================================================================

    @Test
    public void testCustomGoals_easy() {
        // User sets an easy goal: 1km, 10min.
        ScoreCalculator calculator = new ScoreCalculator(1000, 600);
        // Walk half of it: 500m, 5min. Pace is 1.67 m/s, so bonus is applied.
        WalkSessionSummary summary = new WalkSessionSummary("s8", 500, 300, daysAgo(0));
        ScoreResult result = calculator.calculateScore(summary);
        // With adjusted time score and bonus, score should be 68.
        assertEquals(68, result.getScore0to100());
    }

    // =============================================================================================
    // Edge Case Tests
    // =============================================================================================

    @Test
    public void testEdgeCase_nullSummary() {
        ScoreCalculator calculator = new ScoreCalculator();
        ScoreResult result = calculator.calculateScore(null);
        assertEquals(0, result.getScore0to100());
        assertEquals("No walk data available yet.", result.getFeedbackText());
    }

    @Test
    public void testEdgeCase_zeroValues() {
        ScoreCalculator calculator = new ScoreCalculator();
        WalkSessionSummary summary = new WalkSessionSummary("s9", 0, 0, daysAgo(0));
        ScoreResult result = calculator.calculateScore(summary);
        assertEquals(0, result.getScore0to100());
    }

    // =============================================================================================
    // Feedback Tests
    // =============================================================================================

    @Test
    public void testFeedbackStrings() {
        // Use a calculator with easy goals to test high scores
        ScoreCalculator calculator = new ScoreCalculator(100, 1);
        WalkSessionSummary perfect = new WalkSessionSummary("p", 100, 1, daysAgo(0));
        assertEquals("Excellent walk! Keep it up.", calculator.calculateScore(perfect).getFeedbackText());

        // Use a calculator with hard goals to test low scores
        ScoreCalculator calc2 = new ScoreCalculator(10000, 10000);
        WalkSessionSummary poor = new WalkSessionSummary("poor", 10, 10, daysAgo(0));
        assertEquals("Let’s get moving — even a short walk helps.", calc2.calculateScore(poor).getFeedbackText());
    }
}
