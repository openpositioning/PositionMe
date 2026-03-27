package com.openpositioning.PositionMe.mapmatching;

import androidx.annotation.Nullable;

import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

/**
 * 表示一次 map matching 所需的完整输入。
 *
 * 这里同时区分：
 * - sourceFloorShapes: 当前“实际所在楼层”用于墙体/楼梯/电梯约束的地图
 * - targetFloorShapes: 候选目标楼层地图（例如 replay 推断出即将上/下到的楼层）
 *
 * 这样在发生楼层切换尝试时，就不会错误地拿“目标楼层的楼梯位置”
 * 去给“当前楼层”的切换放行。
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
     * 兼容旧调用：active floor 默认视为 target floor。
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
