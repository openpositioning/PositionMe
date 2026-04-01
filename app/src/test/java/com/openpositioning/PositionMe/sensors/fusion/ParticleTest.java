package com.openpositioning.PositionMe.sensors.fusion;

import static org.junit.Assert.*;
import org.junit.Test;

/**
 * Unit tests for {@link Particle}.
 */
public class ParticleTest {

    @Test
    public void constructor_setsFields() {
        Particle p = new Particle(3.0, 7.0, 2, 0.5);
        assertEquals(3.0, p.x, 1e-10);
        assertEquals(7.0, p.y, 1e-10);
        assertEquals(2, p.floor);
        assertEquals(0.5, p.weight, 1e-10);
    }

    @Test
    public void fields_areMutable() {
        Particle p = new Particle(0, 0, 0, 1.0);
        p.x = 5.0;
        p.y = -3.0;
        p.floor = 1;
        p.weight = 0.25;
        assertEquals(5.0, p.x, 1e-10);
        assertEquals(-3.0, p.y, 1e-10);
        assertEquals(1, p.floor);
        assertEquals(0.25, p.weight, 1e-10);
    }
}
