package com.openpositioning.PositionMe.sensors;

import com.openpositioning.PositionMe.Traj;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Minimal unit test to ensure venue_id is applied into trajectory protobuf when set.
 */
public class SensorFusionVenueTest {

    @Test
    public void applyVenueId_setsFieldOnBuilder() {
        Traj.Trajectory.Builder builder = Traj.Trajectory.newBuilder();
        builder.setTrajectoryId("20240202_aaaa");

        SensorFusion.applyVenuePrefixToTrajectoryId(builder, "abc");

        assertEquals("abc_20240202_aaaa", builder.getTrajectoryId());
    }
}
