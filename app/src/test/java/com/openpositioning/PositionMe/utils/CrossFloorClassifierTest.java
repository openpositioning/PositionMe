package com.openpositioning.PositionMe.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CrossFloorClassifierTest {

    private final MapMatchingConfig config = new MapMatchingConfig(4.0f, 1.5f, 2.0f, 0.2f, 5.0f);

    @Test
    public void classifyReturnsLiftWhenHorizontalIsSmallAndHeightBig() {
        CrossFloorClassifier.Mode mode = CrossFloorClassifier.classify(0.5, 4.5, 0.0, config);
        assertEquals(CrossFloorClassifier.Mode.LIFT, mode);
    }

    @Test
    public void classifyReturnsStairsWhenHorizontalIsLargeAndHeightBig() {
        CrossFloorClassifier.Mode mode = CrossFloorClassifier.classify(3.0, 4.5, 0.0, config);
        assertEquals(CrossFloorClassifier.Mode.STAIRS, mode);
    }

    @Test
    public void classifyReturnsUnknownWhenHeightIsTooSmall() {
        CrossFloorClassifier.Mode mode = CrossFloorClassifier.classify(0.5, 1.0, 0.0, config);
        assertEquals(CrossFloorClassifier.Mode.UNKNOWN, mode);
    }

    @Test
    public void classifyReturnsUnknownWhenBetweenLiftAndStairsThresholds() {
        CrossFloorClassifier.Mode mode = CrossFloorClassifier.classify(1.6, 5.0, 0.0, config);
        assertEquals(CrossFloorClassifier.Mode.UNKNOWN, mode);
    }
}
