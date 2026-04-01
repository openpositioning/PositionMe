package com.openpositioning.PositionMe.sensors.fusion;

import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link ParticleFilter}.
 * Verifies initialization, prediction, observation update, and state queries.
 */
public class ParticleFilterTest {

    private ParticleFilter pf;
    private static final int NUM_PARTICLES = 500;

    @Before
    public void setUp() {
        pf = new ParticleFilter(NUM_PARTICLES);
    }

    @Test
    public void initialize_setsPositionNearOrigin() {
        pf.initialize(10.0, 20.0, 0, 5.0);
        assertEquals(10.0, pf.getEstimatedX(), 3.0);
        assertEquals(20.0, pf.getEstimatedY(), 3.0);
    }

    @Test
    public void initialize_setsFloor() {
        pf.initialize(0, 0, 2, 5.0);
        Particle[] particles = pf.getParticles();
        for (Particle p : particles) {
            assertEquals(2, p.floor);
        }
    }

    @Test
    public void isInitialized_afterInit_returnsTrue() {
        pf.initialize(0, 0, 0, 5.0);
        assertTrue(pf.isInitialized());
    }

    @Test
    public void isInitialized_beforeInit_returnsFalse() {
        assertFalse(pf.isInitialized());
    }

    @Test
    public void predict_movesParticlesForward() {
        pf.initialize(0, 0, 0, 1.0);
        double xBefore = pf.getEstimatedX();
        double yBefore = pf.getEstimatedY();

        // Walk north (heading=0) for 10 steps of 1m
        for (int i = 0; i < 10; i++) {
            pf.predict(1.0, 0.0, Math.toRadians(5));
        }

        double displacement = Math.hypot(
                pf.getEstimatedX() - xBefore,
                pf.getEstimatedY() - yBefore);
        assertTrue("Displacement should be roughly 10m, was " + displacement,
                displacement > 5.0 && displacement < 15.0);
    }

    @Test
    public void updateWithDynamicSigma_pullsTowardObservation() {
        pf.initialize(0, 0, 0, 2.0);

        for (int i = 0; i < 5; i++) {
            pf.updateWithDynamicSigma(10.0, 10.0, 2.0);
        }

        double distToObs = Math.hypot(
                pf.getEstimatedX() - 10.0,
                pf.getEstimatedY() - 10.0);
        assertTrue("Should be pulled toward (10,10), dist=" + distToObs,
                distToObs < 12.0);
    }

    @Test
    public void particleCount_matchesConstructor() {
        assertEquals(NUM_PARTICLES, pf.getParticles().length);
        assertEquals(NUM_PARTICLES, pf.getNumParticles());
    }

    @Test
    public void updateFloor_changesAllParticleFloors() {
        pf.initialize(0, 0, 0, 5.0);
        pf.updateFloor(3);
        for (Particle p : pf.getParticles()) {
            assertEquals(3, p.floor);
        }
    }

    @Test
    public void getUncertainty_afterInit_isPositive() {
        pf.initialize(0, 0, 0, 5.0);
        assertTrue(pf.getUncertainty() > 0);
    }

    @Test
    public void getEstimatedFloor_afterInit_matchesSeed() {
        pf.initialize(0, 0, 2, 5.0);
        assertEquals(2, pf.getEstimatedFloor());
    }
}
