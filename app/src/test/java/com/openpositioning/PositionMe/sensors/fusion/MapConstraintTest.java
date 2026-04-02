package com.openpositioning.PositionMe.sensors.fusion;

import static org.junit.Assert.*;
import org.junit.Test;

/**
 * Unit tests for {@link MapConstraint}.
 * Verifies wall segment AABB computation and initialization state.
 */
public class MapConstraintTest {

    @Test
    public void isInitialized_beforeInit_returnsFalse() {
        MapConstraint mc = new MapConstraint();
        assertFalse(mc.isInitialized());
    }

    @Test
    public void wallSegment_storesEndpoints() {
        MapConstraint.WallSegment wall = new MapConstraint.WallSegment(1, 2, 3, 4);
        assertEquals(1, wall.x1, 1e-10);
        assertEquals(2, wall.y1, 1e-10);
        assertEquals(3, wall.x2, 1e-10);
        assertEquals(4, wall.y2, 1e-10);
    }

    @Test
    public void wallSegment_computesMinMax() {
        MapConstraint.WallSegment wall = new MapConstraint.WallSegment(8, 3, 2, 9);
        assertEquals(2, wall.minX, 1e-10);
        assertEquals(8, wall.maxX, 1e-10);
        assertEquals(3, wall.minY, 1e-10);
        assertEquals(9, wall.maxY, 1e-10);
    }

    @Test
    public void wallSegment_horizontalHasEqualY() {
        MapConstraint.WallSegment wall = new MapConstraint.WallSegment(0, 5, 10, 5);
        assertEquals(wall.minY, wall.maxY, 1e-10);
    }

    @Test
    public void wallSegment_verticalHasEqualX() {
        MapConstraint.WallSegment wall = new MapConstraint.WallSegment(5, 0, 5, 10);
        assertEquals(wall.minX, wall.maxX, 1e-10);
    }
}
