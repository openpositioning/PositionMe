package com.openpositioning.PositionMe;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

/**
 * Unit tests for Test Point functionality in trajectory recording.
 */
public class TestPointUnitTest {

    private Traj.Trajectory.Builder trajectoryBuilder;

    @Before
    public void setUp() {
        // Create a new trajectory builder before each test
        trajectoryBuilder = Traj.Trajectory.newBuilder();
    }

    @Test
    public void testAddSingleTestPoint() {
        // Arrange
        double lat = 55.9445;
        double lon = -3.1892;
        double alt = 50.0;
        long timestamp = 1000L;

        // Act
        trajectoryBuilder.addTestPoints(Traj.GNSSPosition.newBuilder()
                .setRelativeTimestamp(timestamp)
                .setLatitude(lat)
                .setLongitude(lon)
                .setAltitude(alt));

        Traj.Trajectory trajectory = trajectoryBuilder.build();

        // Assert
        assertEquals("Should have 1 test point", 1, trajectory.getTestPointsCount());
        assertEquals(lat, trajectory.getTestPoints(0).getLatitude(), 0.0001);
        assertEquals(lon, trajectory.getTestPoints(0).getLongitude(), 0.0001);
        assertEquals(alt, trajectory.getTestPoints(0).getAltitude(), 0.0001);
        assertEquals(timestamp, trajectory.getTestPoints(0).getRelativeTimestamp());
    }

    @Test
    public void testAddMultipleTestPoints() {
        // Arrange & Act - Add 3 test points
        for (int i = 0; i < 3; i++) {
            trajectoryBuilder.addTestPoints(Traj.GNSSPosition.newBuilder()
                    .setRelativeTimestamp(i * 1000L)
                    .setLatitude(55.9445 + i * 0.001)
                    .setLongitude(-3.1892 + i * 0.001)
                    .setAltitude(50.0 + i));
        }

        Traj.Trajectory trajectory = trajectoryBuilder.build();

        // Assert
        assertEquals("Should have 3 test points", 3, trajectory.getTestPointsCount());
    }

    @Test
    public void testTestPointTimestampOrder() {
        // Arrange - Add test points with different timestamps
        trajectoryBuilder.addTestPoints(Traj.GNSSPosition.newBuilder()
                .setRelativeTimestamp(5000L)
                .setLatitude(55.9445)
                .setLongitude(-3.1892)
                .setAltitude(50.0));

        trajectoryBuilder.addTestPoints(Traj.GNSSPosition.newBuilder()
                .setRelativeTimestamp(10000L)
                .setLatitude(55.9446)
                .setLongitude(-3.1893)
                .setAltitude(51.0));

        Traj.Trajectory trajectory = trajectoryBuilder.build();

        // Assert - Verify timestamps are preserved
        assertEquals(5000L, trajectory.getTestPoints(0).getRelativeTimestamp());
        assertEquals(10000L, trajectory.getTestPoints(1).getRelativeTimestamp());
    }

    @Test
    public void testEmptyTrajectoryHasNoTestPoints() {
        // Act
        Traj.Trajectory trajectory = trajectoryBuilder.build();

        // Assert
        assertEquals("Empty trajectory should have 0 test points", 0, trajectory.getTestPointsCount());
    }

    @Test
    public void testTestPointCoordinatesAccuracy() {
        // Arrange - Use precise coordinates (Edinburgh area)
        double preciseLat = 55.944425;
        double preciseLon = -3.189208;

        // Act
        trajectoryBuilder.addTestPoints(Traj.GNSSPosition.newBuilder()
                .setRelativeTimestamp(0L)
                .setLatitude(preciseLat)
                .setLongitude(preciseLon)
                .setAltitude(0.0));

        Traj.Trajectory trajectory = trajectoryBuilder.build();

        // Assert - Check precision to 6 decimal places
        assertEquals(preciseLat, trajectory.getTestPoints(0).getLatitude(), 0.000001);
        assertEquals(preciseLon, trajectory.getTestPoints(0).getLongitude(), 0.000001);
    }
}