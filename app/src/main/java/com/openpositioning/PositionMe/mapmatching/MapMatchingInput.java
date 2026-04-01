package com.openpositioning.PositionMe.mapmatching;

import androidx.annotation.Nullable;

import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

/**
 * Represents the full input required for one map-matching pass.
 *
 * This explicitly distinguishes between:
 * - sourceFloorShapes: the map of the actual current floor used for wall/stairs/lift constraints
 * - targetFloorShapes: the candidate target-floor map (for example, a replay-predicted next floor)
 *
 * This prevents the code from mistakenly using the stairs on the target floor
 * to approve a transition on the current floor.
 */
public class MapMatchingInput {

    private final CandidatePose previousPose;
    private final CandidatePose currentCandidatePose;
    private final MotionDelta motionDelta;
    private final VerticalTransitionHint verticalHint;
    private final FloorplanApiClient.FloorShapes sourceFloorShapes;
    private final FloorplanApiClient.FloorShapes targetFloorShapes;
    private final String activeBuildingId;

    public MapMatchingInput(
            @Nullable CandidatePose previousPose,
            CandidatePose currentCandidatePose,
            @Nullable MotionDelta motionDelta,
            @Nullable VerticalTransitionHint verticalHint,
            @Nullable FloorplanApiClient.FloorShapes sourceFloorShapes,
            @Nullable FloorplanApiClient.FloorShapes targetFloorShapes,
            @Nullable String activeBuildingId
    ) {
        this.previousPose = previousPose;
        this.currentCandidatePose = currentCandidatePose;
        this.motionDelta = motionDelta;
        this.verticalHint = verticalHint;
        this.sourceFloorShapes = sourceFloorShapes;
        this.targetFloorShapes = targetFloorShapes;
        this.activeBuildingId = activeBuildingId;
    }

    @Nullable
    public CandidatePose getPreviousPose() {
        return previousPose;
    }

    public CandidatePose getCurrentCandidatePose() {
        return currentCandidatePose;
    }

    @Nullable
    public MotionDelta getMotionDelta() {
        return motionDelta;
    }

    @Nullable
    public VerticalTransitionHint getVerticalHint() {
        return verticalHint;
    }

    @Nullable
    public FloorplanApiClient.FloorShapes getSourceFloorShapes() {
        return sourceFloorShapes;
    }

    @Nullable
    public FloorplanApiClient.FloorShapes getTargetFloorShapes() {
        return targetFloorShapes;
    }

    /**
     * Compatibility for old callers: active floor defaults to target floor.
     */
    @Nullable
    public FloorplanApiClient.FloorShapes getActiveFloorShapes() {
        return targetFloorShapes;
    }

    @Nullable
    public String getActiveBuildingId() {
        return activeBuildingId;
    }

    public boolean hasActiveFloorMap() {
        return targetFloorShapes != null
                && targetFloorShapes.getFeatures() != null
                && !targetFloorShapes.getFeatures().isEmpty();
    }

    public boolean hasSourceFloorMap() {
        return sourceFloorShapes != null
                && sourceFloorShapes.getFeatures() != null
                && !sourceFloorShapes.getFeatures().isEmpty();
    }
}
