package com.openpositioning.PositionMe.sensors.fusion;

import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link CoordinateTransform}.
 * Verifies ENU ↔ LatLng conversions are accurate and invertible.
 */
public class CoordinateTransformTest {

    private CoordinateTransform ct;
    private static final double ORIGIN_LAT = 55.9230;
    private static final double ORIGIN_LNG = -3.1742;

    @Before
    public void setUp() {
        ct = new CoordinateTransform();
        ct.setOrigin(ORIGIN_LAT, ORIGIN_LNG);
    }

    @Test
    public void isInitialized_afterSetOrigin_returnsTrue() {
        assertTrue(ct.isInitialized());
    }

    @Test
    public void isInitialized_beforeSetOrigin_returnsFalse() {
        CoordinateTransform fresh = new CoordinateTransform();
        assertFalse(fresh.isInitialized());
    }

    @Test
    public void originMapsToZeroZero() {
        double[] en = ct.toEastNorth(ORIGIN_LAT, ORIGIN_LNG);
        assertEquals(0.0, en[0], 0.01);
        assertEquals(0.0, en[1], 0.01);
    }

    @Test
    public void roundTrip_originReturnsOrigin() {
        double[] ll = ct.toLatLng(0, 0);
        assertEquals(ORIGIN_LAT, ll[0], 1e-6);
        assertEquals(ORIGIN_LNG, ll[1], 1e-6);
    }

    @Test
    public void roundTrip_arbitraryPoint() {
        double testLat = 55.9235;
        double testLng = -3.1750;
        double[] en = ct.toEastNorth(testLat, testLng);
        double[] ll = ct.toLatLng(en[0], en[1]);
        assertEquals(testLat, ll[0], 1e-5);
        assertEquals(testLng, ll[1], 1e-5);
    }

    @Test
    public void fiveMetresNorth_givesPositiveNorthing() {
        double offsetLat = ORIGIN_LAT + 5.0 / 111132.92;
        double[] en = ct.toEastNorth(offsetLat, ORIGIN_LNG);
        assertEquals(0.0, en[0], 0.5);   // easting ~0
        assertEquals(5.0, en[1], 0.5);   // northing ~5m
    }

    @Test
    public void fiveMetresEast_givesPositiveEasting() {
        double metersPerDegreeLng = 111132.92 * Math.cos(Math.toRadians(ORIGIN_LAT));
        double offsetLng = ORIGIN_LNG + 5.0 / metersPerDegreeLng;
        double[] en = ct.toEastNorth(ORIGIN_LAT, offsetLng);
        assertEquals(5.0, en[0], 0.5);   // easting ~5m
        assertEquals(0.0, en[1], 0.5);   // northing ~0
    }

    @Test
    public void getOrigin_returnsSetValues() {
        assertEquals(ORIGIN_LAT, ct.getOriginLat(), 1e-10);
        assertEquals(ORIGIN_LNG, ct.getOriginLng(), 1e-10);
    }
}
