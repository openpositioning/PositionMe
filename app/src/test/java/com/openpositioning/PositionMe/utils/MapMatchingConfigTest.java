package com.openpositioning.PositionMe.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MapMatchingConfigTest {

    @Test
    public void defaultsAreStable() {
        MapMatchingConfig config = new MapMatchingConfig();

        assertEquals(4.0f, config.baroHeightThreshold, 0.0001f);
        assertEquals(1.5f, config.liftHorizontalMax, 0.0001f);
        assertEquals(2.0f, config.stairsHorizontalMin, 0.0001f);
        assertEquals(0.2f, config.wallPadding, 0.0001f);
        assertEquals(5.0f, config.crossFeatureProximity, 0.0001f);
    }

    @Test
    public void customValuesAreApplied() {
        MapMatchingConfig config = new MapMatchingConfig(3.0f, 1.0f, 2.5f, 0.1f, 7.0f);

        assertEquals(3.0f, config.baroHeightThreshold, 0.0001f);
        assertEquals(1.0f, config.liftHorizontalMax, 0.0001f);
        assertEquals(2.5f, config.stairsHorizontalMin, 0.0001f);
        assertEquals(0.1f, config.wallPadding, 0.0001f);
        assertEquals(7.0f, config.crossFeatureProximity, 0.0001f);
    }
}
