package com.openpositioning.PositionMe.mapmatching;

import androidx.annotation.Nullable;

import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

/**
 * 表示一次 map matching 所需的完整输入。
 * 当前先打包位置、位移、高度变化、当前楼层地图和当前建筑信息。
 */
public class MapMatchingInput {

    private final CandidatePose previousPose;
    private final CandidatePose currentCandidatePose;
    private final MotionDelta motionDelta;
    private final VerticalTransitionHint verticalHint;
    private final FloorplanApiClient.FloorShapes activeFloorShapes;
    private final String activeBuildingId;

    public MapMatchingInput(
            @Nullable CandidatePose previousPose,
            CandidatePose currentCandidatePose,
            @Nullable MotionDelta motionDelta,
            @Nullable VerticalTransitionHint verticalHint,
            @Nullable FloorplanApiClient.FloorShapes activeFloorShapes,
            @Nullable String activeBuildingId
    ) {
        this.previousPose = previousPose;
        this.currentCandidatePose = currentCandidatePose;
        this.motionDelta = motionDelta;
        this.verticalHint = verticalHint;
        this.activeFloorShapes = activeFloorShapes;
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
    public FloorplanApiClient.FloorShapes getActiveFloorShapes() {
        return activeFloorShapes;
    }

    @Nullable
    public String getActiveBuildingId() {
        return activeBuildingId;
    }

    public boolean hasActiveFloorMap() {
        return activeFloorShapes != null
                && activeFloorShapes.getFeatures() != null
                && !activeFloorShapes.getFeatures().isEmpty();
    }
}