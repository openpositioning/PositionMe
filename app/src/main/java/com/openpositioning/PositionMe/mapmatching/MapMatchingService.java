package com.openpositioning.PositionMe.mapmatching;

import androidx.annotation.NonNull;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

/**
 * 3.2 Map Matching 的核心服务类。
 *
 * 当前这一版开始真正使用地图几何约束：
 * 1. 检测穿墙
 * 2. 判断是否靠近 stairs / lift
 * 3. 用高度变化 + 地图位置约束楼层切换
 *
 * 这是一个“先求稳”的第一版：
 * - 穿墙时先回退到上一点
 * - 非法换层时先保持上一楼层
 * - 不做复杂投影修正，先保证行为正确、容易调试
 */
public class MapMatchingService {
    // 水平位移较小时，更像 lift；较大时，更像 stairs
    private static final double MAX_LIFT_HORIZONTAL_DISPLACEMENT_METERS = 1.5;
    // 位移太小时，不做穿墙检测，避免传感器抖动带来误判
    private static final double MIN_DISPLACEMENT_FOR_WALL_CHECK_METERS = 0.3;

    /**
     * 对一次候选位置执行 map matching。
     */
    @NonNull
    public MapMatchingResult match(@NonNull MapMatchingInput input) {
        CandidatePose currentPose = input.getCurrentCandidatePose();
        CandidatePose previousPose = input.getPreviousPose();
        FloorplanApiClient.FloorShapes floorShapes = input.getActiveFloorShapes();

        LatLng candidateLatLng = currentPose.getLatLng();
        LatLng correctedLatLng = candidateLatLng;
        int correctedFloor = currentPose.getFloor();

        boolean nearStairs = false;
        boolean nearLift = false;
        boolean crossedWall = false;
        boolean invalidFloorChange = false;
        boolean floorChangeAllowed = isFloorChangeAllowed(input);

        CorrectionType correctionType = CorrectionType.NONE;
        String debugReason = "Candidate pose accepted.";
        boolean validPosition = true;

        // =========================================================
        // 【情况 1：当前没有可用地图】
        // 不做地图约束，直接透传
        // =========================================================
        if (!input.hasActiveFloorMap() || floorShapes == null) {
            return new MapMatchingResult(
                    true,
                    false,
                    false,
                    false,
                    floorChangeAllowed,
                    candidateLatLng,
                    currentPose.getFloor(),
                    CorrectionType.NONE,
                    "No active floor map. Pass through candidate pose."
            );
        }

        // =========================================================
        // 【新增：判断当前位置是否靠近 stairs / lift】
        // =========================================================
        nearStairs = MapGeometryUtils.isNearStairs(candidateLatLng, floorShapes);
        nearLift = MapGeometryUtils.isNearLift(candidateLatLng, floorShapes);
        // =========================================================
        // 【新增：给当前垂直变化一个更易读的解释】
        // 仅用于 debugReason，不改变主逻辑
        // =========================================================
        MotionDelta motionDelta = input.getMotionDelta();
        String transitionDescription = describeVerticalTransition(
                nearStairs,
                nearLift,
                motionDelta
        );

        if (input.getVerticalHint() != null && input.getVerticalHint().isHeightChanged()) {
            debugReason = "Height changed; " + transitionDescription + ".";
        } else {
            debugReason = "Candidate pose accepted; " + transitionDescription + ".";
        }
        // =========================================================
        // 【新增：判断轨迹是否穿墙】
        // 如果穿墙，第一版先回退到上一点
        // =========================================================
        if (previousPose != null && previousPose.getLatLng() != null) {
            boolean shouldCheckWall = true;

            MotionDelta wallCheckMotionDelta = input.getMotionDelta();
            if (wallCheckMotionDelta != null) {
                shouldCheckWall =
                        wallCheckMotionDelta.getStepDistance() >= MIN_DISPLACEMENT_FOR_WALL_CHECK_METERS;
            }

            if (shouldCheckWall) {
                crossedWall = MapGeometryUtils.crossesWall(
                        previousPose.getLatLng(),
                        candidateLatLng,
                        floorShapes
                );

                if (crossedWall) {
                    correctedLatLng = previousPose.getLatLng();
                    correctedFloor = previousPose.getFloor();
                    correctionType = CorrectionType.THROUGH_WALL;
                    debugReason = "Trajectory crossed wall. Reverted to previous pose.";
                    validPosition = false;
                }
            } else {
                debugReason = "Step distance too small for wall check. Candidate pose accepted.";
            }
        }

        // =========================================================
        // 【新增：楼层变化约束】
        // 如果当前候选楼层和上一时刻不同，但不满足换层条件，
        // 第一版先保持上一楼层
        // =========================================================
        if (previousPose != null
                && currentPose.getFloor() != previousPose.getFloor()
                && !floorChangeAllowed) {

            invalidFloorChange = true;
            correctedFloor = previousPose.getFloor();

            if (correctionType == CorrectionType.NONE) {
                correctionType = CorrectionType.INVALID_FLOOR_CHANGE;
                debugReason = "Floor change rejected: no valid height change or not near stairs/lift.";
            }

            validPosition = false;
        }

        return new MapMatchingResult(
                validPosition,
                crossedWall,
                nearStairs,
                nearLift,
                floorChangeAllowed,
                correctedLatLng,
                correctedFloor,
                correctionType,
                debugReason
        );
    }

    /**
     * 判断当前是否允许换层。
     *
     * 当前这一版规则：
     * 1. 必须有 verticalHint
     * 2. 必须检测到明显高度变化
     * 3. 如果没有可用地图，为了不阻断原逻辑，先允许
     * 4. 如果有地图，则必须 near stairs 或 near lift
     * 5. 如果有 motionDelta，则进一步用 stepDistance 区分 stairs / lift：
     *    - nearLift only  -> 需要较小水平位移
     *    - nearStairs only -> 需要较大水平位移
     *    - 如果两者都 near，则先允许（第一版不强行细分）
     */
    public boolean isFloorChangeAllowed(@NonNull MapMatchingInput input) {
        VerticalTransitionHint verticalHint = input.getVerticalHint();

        if (verticalHint == null) {
            return false;
        }

        if (!verticalHint.isHeightChanged()) {
            return false;
        }

        // 没有地图时先不阻断现有逻辑
        if (!input.hasActiveFloorMap()) {
            return true;
        }

        FloorplanApiClient.FloorShapes floorShapes = input.getActiveFloorShapes();
        if (floorShapes == null) {
            return true;
        }

        LatLng currentPoint = input.getCurrentCandidatePose().getLatLng();

        boolean nearStairs = MapGeometryUtils.isNearStairs(currentPoint, floorShapes);
        boolean nearLift = MapGeometryUtils.isNearLift(currentPoint, floorShapes);

        // 不在楼梯/电梯附近，不允许换层
        if (!nearStairs && !nearLift) {
            return false;
        }

        MotionDelta motionDelta = input.getMotionDelta();

        // 没有运动增量时，保持上一版的宽松策略
        if (motionDelta == null) {
            return nearStairs || nearLift;
        }

        double stepDistance = motionDelta.getStepDistance();
        boolean likelyLiftMotion = stepDistance <= MAX_LIFT_HORIZONTAL_DISPLACEMENT_METERS;

        // 只靠近 lift：要求水平位移小，更像乘电梯
        if (nearLift && !nearStairs) {
            return likelyLiftMotion;
        }

        // 只靠近 stairs：要求水平位移较大，更像走楼梯
        if (nearStairs && !nearLift) {
            return !likelyLiftMotion;
        }

        // 两者都靠近时，第一版先允许
        return true;
    }

    /**
     * 根据当前位置和水平位移，给一次垂直变化做一个简单解释：
     * 更像 stairs / 更像 lift / 不确定。
     *
     * 这个方法当前只用于调试说明，不改变主逻辑。
     */
    private String describeVerticalTransition(boolean nearStairs,
                                              boolean nearLift,
                                              MotionDelta motionDelta) {
        if (!nearStairs && !nearLift) {
            return "not near stairs or lift";
        }

        if (motionDelta == null) {
            if (nearStairs && nearLift) return "near both stairs and lift";
            if (nearStairs) return "near stairs";
            return "near lift";
        }

        double stepDistance = motionDelta.getStepDistance();
        boolean likelyLiftMotion = stepDistance <= MAX_LIFT_HORIZONTAL_DISPLACEMENT_METERS;

        if (nearLift && !nearStairs) {
            return likelyLiftMotion
                    ? "likely lift transition"
                    : "lift nearby but horizontal movement looks too large";
        }

        if (nearStairs && !nearLift) {
            return likelyLiftMotion
                    ? "stairs nearby but horizontal movement looks too small"
                    : "likely stairs transition";
        }

        // 两者都 near 时，先给一个保守描述
        return likelyLiftMotion
                ? "near both stairs and lift, motion slightly favors lift"
                : "near both stairs and lift, motion slightly favors stairs";
    }
}