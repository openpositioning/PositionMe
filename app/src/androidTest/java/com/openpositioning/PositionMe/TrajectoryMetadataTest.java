package com.openpositioning.PositionMe;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrajectoryMetadataTest {

    @Test
    public void testTrajectoryIdGeneration() {
        String trajectoryId = generateTrajectoryId();

        assertNotNull("Trajectory ID should not be null", trajectoryId);
        assertTrue("Trajectory ID should start with 'android_'",
                trajectoryId.startsWith("android_"));
        assertTrue("Trajectory ID should contain device model",
                trajectoryId.contains(android.os.Build.MODEL.replaceAll("\\s+", "_")));
        assertTrue("Trajectory ID should contain timestamp",
                trajectoryId.length() > 20);
    }

    @Test
    public void testTrajectoryVersionIsCorrect() {
        Traj.Trajectory.Builder builder = Traj.Trajectory.newBuilder()
                .setTrajectoryVersion(2.0f)
                .setTrajectoryId("test_id");

        Traj.Trajectory trajectory = builder.build();

        assertEquals("Trajectory version should be 2.0",
                2.0f, trajectory.getTrajectoryVersion(), 0.001f);
    }

    @Test
    public void testTrajectoryIdUniqueness() {
        String id1 = generateTrajectoryId();
        // Small delay to ensure different timestamp
        try { Thread.sleep(10); } catch (InterruptedException e) {}
        String id2 = generateTrajectoryId();

        assertNotEquals("Two trajectory IDs should be different", id1, id2);
    }

    private String generateTrajectoryId() {
        String deviceModel = android.os.Build.MODEL.replaceAll("\\s+", "_");
        long timestamp = System.currentTimeMillis();
        return String.format("android_%s_%d", deviceModel, timestamp);
    }
}