package com.openpositioning.PositionMe.health;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScoreCalculatorTest {

    private final ScoreCalculator calculator = new ScoreCalculator();

    @Test
    public void testPerfectScore_AtThresholds() {
        WalkSessionSummary summary = new WalkSessionSummary("test1", 3000, 1800, 0.0);
        ScoreResult result = calculator.calculateScore(summary);
        assertEquals(100, result.getScore0to100());
    }

    @Test
    public void testPerfectScore_AboveThresholds() {
        WalkSessionSummary summary = new WalkSessionSummary("test2", 4000, 2000, 0.0);
        ScoreResult result = calculator.calculateScore(summary);
        assertEquals(100, result.getScore0to100());
    }

    @Test
    public void testHalfScore() {
        // 1500m is 50% of distance, 900s is 50% of time. Base score should be 50.
        WalkSessionSummary summary = new WalkSessionSummary("test3", 1500, 900, 0.0);
        ScoreResult result = calculator.calculateScore(summary);
        assertEquals(50, result.getScore0to100());
    }

    @Test
    public void testZeroScore() {
        WalkSessionSummary summary = new WalkSessionSummary("test4", 0, 0, 0.0);
        ScoreResult result = calculator.calculateScore(summary);
        assertEquals(0, result.getScore0to100());
    }

    @Test
    public void testOutdoorBonus() {
        // 50 base score + 5 bonus points (50% outdoor ratio)
        WalkSessionSummary summary = new WalkSessionSummary("test5", 1500, 900, 0.5);
        ScoreResult result = calculator.calculateScore(summary);
        assertEquals(55, result.getScore0to100());
    }

    @Test
    public void testFullOutdoorBonus() {
        // 50 base score + 10 bonus points (100% outdoor ratio)
        WalkSessionSummary summary = new WalkSessionSummary("test6", 1500, 900, 1.0);
        ScoreResult result = calculator.calculateScore(summary);
        assertEquals(60, result.getScore0to100());
    }

     @Test
    public void testScoreClampingWithBonus() {
        // 100 base score + 10 bonus points, should be clamped to 100
        WalkSessionSummary summary = new WalkSessionSummary("test7", 3000, 1800, 1.0);
        ScoreResult result = calculator.calculateScore(summary);
        assertEquals(100, result.getScore0to100());
    }

    @Test
    public void testNullSummary() {
        ScoreResult result = calculator.calculateScore(null);
        assertEquals(0, result.getScore0to100());
        assertEquals("No walk data available yet.", result.getFeedbackText());
    }
}
