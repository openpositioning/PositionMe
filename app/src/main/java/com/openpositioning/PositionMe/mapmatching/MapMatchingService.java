package com.openpositioning.PositionMe.mapmatching;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

/**
 * 3.2 Map Matching 的核心服务类。
 *
 * 这一版最小补丁重点处理两个问题：
 * 1. 穿墙一段时间后，一旦重新回到合法区域就出现“大跳”
 * 2. 仅凭末端一个点碰到楼梯/电梯红区，就被直接放行换层
 *
 * 处理策略：
 * - 大跳恢复时，对单帧允许的最大位移做上限约束，避免瞬移
 * - 楼层切换时，不再只看当前点；要求 previous/current（或 midpoint）
 *   对 connector 有持续接触，并限制楼梯/电梯转换的水平位移范围
 */
public class MapMatchingService {
    // 水平位移较小时，更像 lift
    private static final double MAX_LIFT_HORIZONTAL_DISPLACEMENT_METERS = 1.5;
    // 楼梯换层允许有一定水平位移，但不能太大；否则多半是飘点/大跳
    private static final double MAX_STAIRS_HORIZONTAL_DISPLACEMENT_METERS = 4.0;
    // 位移太小时，不做穿墙检测，避免传感器抖动带来误判
    private static final double MIN_DISPLACEMENT_FOR_WALL_CHECK_METERS = 0.3;
    // 穿墙恢复后，单帧允许的最大“重新贴回”位移，避免轨迹瞬移
    private static final double MAX_RECOVERY_STEP_METERS = 1.2;
    // connector 区域附近略放宽，但仍然限制大跳
    private static final double MAX_RECOVERY_STEP_NEAR_CONNECTOR_METERS = 2.0;

    @NonNull
    public MapMatchingResult match(@NonNull MapMatchingInput input) {
        CandidatePose currentPose = input.getCurrentCandidatePose();
        CandidatePose previousPose = input.getPreviousPose();

        boolean floorTransitionAttempt = previousPose != null
                && currentPose.getFloor() != previousPose.getFloor();
        boolean floorChangeAllowed = isFloorChangeAllowed(input);

        FloorplanApiClient.FloorShapes connectorFloorShapes = chooseConnectorFloorShapes(input, floorTransitionAttempt);
        FloorplanApiClient.FloorShapes wallCheckFloorShapes = chooseWallCheckFloorShapes(input, floorTransitionAttempt);

        LatLng candidateLatLng = currentPose.getLatLng();
        LatLng correctedLatLng = candidateLatLng;
        int correctedFloor = currentPose.getFloor();

        boolean nearStairs = false;
        boolean nearLift = false;
        boolean crossedWall = false;

        CorrectionType correctionType = CorrectionType.NONE;
        String debugReason = "Candidate pose accepted.";
        boolean validPosition = true;

        if (connectorFloorShapes != null) {
            nearStairs = MapGeometryUtils.isNearStairs(candidateLatLng, connectorFloorShapes);
            nearLift = MapGeometryUtils.isNearLift(candidateLatLng, connectorFloorShapes);
        }

        MotionDelta motionDelta = input.getMotionDelta();
        String transitionDescription = describeVerticalTransition(nearStairs, nearLift, motionDelta);
        if (input.getVerticalHint() != null && input.getVerticalHint().isHeightChanged()) {
            debugReason = "Height changed; " + transitionDescription + ".";
        } else {
            debugReason = "Candidate pose accepted; " + transitionDescription + ".";
        }

        // 没有任何可用楼层地图时，不做墙体约束。
        if (wallCheckFloorShapes == null) {
            return new MapMatchingResult(
                    true,
                    false,
                    nearStairs,
                    nearLift,
                    floorChangeAllowed,
                    candidateLatLng,
                    currentPose.getFloor(),
                    CorrectionType.NONE,
                    "No usable wall map. Pass through candidate pose."
            );
        }

        // 穿墙检测：楼层切换尝试时优先看 source floor，避免被目标层几何误导。
        if (previousPose != null && previousPose.getLatLng() != null) {
            boolean shouldCheckWall = true;
            if (motionDelta != null) {
                shouldCheckWall = motionDelta.getStepDistance() >= MIN_DISPLACEMENT_FOR_WALL_CHECK_METERS;
            }

            if (shouldCheckWall) {
                crossedWall = MapGeometryUtils.crossesWall(
                        previousPose.getLatLng(),
                        candidateLatLng,
                        wallCheckFloorShapes
                );

                if (crossedWall) {
                    LatLng latOnlyPoint = new LatLng(candidateLatLng.latitude, previousPose.getLatLng().longitude);
                    LatLng lngOnlyPoint = new LatLng(previousPose.getLatLng().latitude, candidateLatLng.longitude);

                    boolean canMoveLat = !MapGeometryUtils.crossesWall(previousPose.getLatLng(), latOnlyPoint, wallCheckFloorShapes);
                    boolean canMoveLng = !MapGeometryUtils.crossesWall(previousPose.getLatLng(), lngOnlyPoint, wallCheckFloorShapes);

                    if (canMoveLat && !canMoveLng) {
                        correctedLatLng = latOnlyPoint;
                        correctionType = CorrectionType.THROUGH_WALL;
                        debugReason = "Crossed wall. Sliding along Latitude (N/S).";
                        validPosition = true;
                    } else if (!canMoveLat && canMoveLng) {
                        correctedLatLng = lngOnlyPoint;
                        correctionType = CorrectionType.THROUGH_WALL;
                        debugReason = "Crossed wall. Sliding along Longitude (E/W).";
                        validPosition = true;
                    } else {
                        correctedLatLng = previousPose.getLatLng();
                        if (!floorChangeAllowed) {
                            correctedFloor = previousPose.getFloor();
                        }
                        correctionType = CorrectionType.THROUGH_WALL;
                        debugReason = "Crossed wall. Stuck in corner, reverted to previous XY pose.";
                        validPosition = false;
                    }
                }
            } else {
                debugReason = "Step distance too small for wall check. Candidate pose accepted.";
            }
        }

        if (floorTransitionAttempt && !floorChangeAllowed && previousPose != null) {
            correctedFloor = previousPose.getFloor();
            if (correctionType == CorrectionType.NONE) {
                correctionType = CorrectionType.INVALID_FLOOR_CHANGE;
                debugReason = "Floor change rejected: connector evidence is too weak or horizontal jump is too large.";
            }
            validPosition = false;
        }

        if (!crossedWall && previousPose != null && previousPose.getLatLng() != null && motionDelta != null) {
            LatLng clampedRecovery = clampRecoveryJump(
                    previousPose.getLatLng(),
                    correctedLatLng,
                    motionDelta,
                    nearStairs || nearLift,
                    floorTransitionAttempt,
                    floorChangeAllowed
            );
            if (!samePoint(clampedRecovery, correctedLatLng)) {
                correctedLatLng = clampedRecovery;
                if (correctionType == CorrectionType.NONE) {
                    correctionType = CorrectionType.SNAP_TO_VALID_AREA;
                }
                debugReason = "Large recovery jump limited to avoid instant snap-back.";
            }
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

    public boolean isFloorChangeAllowed(@NonNull MapMatchingInput input) {
        VerticalTransitionHint verticalHint = input.getVerticalHint();
        CandidatePose previousPose = input.getPreviousPose();
        CandidatePose currentPose = input.getCurrentCandidatePose();

        if (verticalHint == null || !verticalHint.isHeightChanged()) {
            return false;
        }

        if (previousPose == null || currentPose.getFloor() == previousPose.getFloor()) {
            return false;
        }

        FloorplanApiClient.FloorShapes connectorFloorShapes = chooseConnectorFloorShapes(input, true);
        if (connectorFloorShapes == null) {
            // 没有地图时维持旧版宽松策略，不阻断已有逻辑。
            return true;
        }

        LatLng previousPoint = previousPose.getLatLng();
        LatLng currentPoint = currentPose.getLatLng();
        LatLng midpoint = midpoint(previousPoint, currentPoint);

        boolean currentNearStairs = MapGeometryUtils.isNearStairs(currentPoint, connectorFloorShapes);
        boolean currentNearLift = MapGeometryUtils.isNearLift(currentPoint, connectorFloorShapes);
        if (!currentNearStairs && !currentNearLift) {
            return false;
        }

        boolean previousNearStairs = previousPoint != null
                && MapGeometryUtils.isNearStairs(previousPoint, connectorFloorShapes);
        boolean previousNearLift = previousPoint != null
                && MapGeometryUtils.isNearLift(previousPoint, connectorFloorShapes);
        boolean midpointNearStairs = midpoint != null
                && MapGeometryUtils.isNearStairs(midpoint, connectorFloorShapes);
        boolean midpointNearLift = midpoint != null
                && MapGeometryUtils.isNearLift(midpoint, connectorFloorShapes);

        boolean stairsPersistence = currentNearStairs && (previousNearStairs || midpointNearStairs);
        boolean liftPersistence = currentNearLift && (previousNearLift || midpointNearLift);
        if (!stairsPersistence && !liftPersistence) {
            return false;
        }

        MotionDelta motionDelta = input.getMotionDelta();
        if (motionDelta == null) {
            return stairsPersistence || liftPersistence;
        }

        double stepDistance = motionDelta.getStepDistance();
        if (stepDistance > MAX_STAIRS_HORIZONTAL_DISPLACEMENT_METERS) {
            return false;
        }

        boolean likelyLiftMotion = stepDistance <= MAX_LIFT_HORIZONTAL_DISPLACEMENT_METERS;

        if (liftPersistence && !stairsPersistence) {
            return likelyLiftMotion;
        }
        if (stairsPersistence && !liftPersistence) {
            return !likelyLiftMotion;
        }
        return true;
    }

    @Nullable
    private FloorplanApiClient.FloorShapes chooseConnectorFloorShapes(@NonNull MapMatchingInput input,
                                                                      boolean floorTransitionAttempt) {
        if (floorTransitionAttempt && input.getSourceFloorShapes() != null) {
            return input.getSourceFloorShapes();
        }
        if (input.getActiveFloorShapes() != null) {
            return input.getActiveFloorShapes();
        }
        return input.getSourceFloorShapes();
    }

    @Nullable
    private FloorplanApiClient.FloorShapes chooseWallCheckFloorShapes(@NonNull MapMatchingInput input,
                                                                      boolean floorTransitionAttempt) {
        if (floorTransitionAttempt && input.getSourceFloorShapes() != null) {
            return input.getSourceFloorShapes();
        }
        if (input.getActiveFloorShapes() != null) {
            return input.getActiveFloorShapes();
        }
        return input.getSourceFloorShapes();
    }

    @NonNull
    private LatLng clampRecoveryJump(@NonNull LatLng previousLatLng,
                                     @NonNull LatLng candidateLatLng,
                                     @NonNull MotionDelta motionDelta,
                                     boolean nearConnector,
                                     boolean floorTransitionAttempt,
                                     boolean floorChangeAllowed) {
        if (floorTransitionAttempt && floorChangeAllowed) {
            return candidateLatLng;
        }

        double stepDistance = motionDelta.getStepDistance();
        double maxAllowedStep = nearConnector
                ? MAX_RECOVERY_STEP_NEAR_CONNECTOR_METERS
                : MAX_RECOVERY_STEP_METERS;

        if (stepDistance <= maxAllowedStep) {
            return candidateLatLng;
        }

        double ratio = maxAllowedStep / Math.max(stepDistance, 1e-6);
        double latitude = previousLatLng.latitude
                + (candidateLatLng.latitude - previousLatLng.latitude) * ratio;
        double longitude = previousLatLng.longitude
                + (candidateLatLng.longitude - previousLatLng.longitude) * ratio;
        return new LatLng(latitude, longitude);
    }

    @Nullable
    private LatLng midpoint(@Nullable LatLng a, @Nullable LatLng b) {
        if (a == null || b == null) {
            return null;
        }
        return new LatLng(
                (a.latitude + b.latitude) / 2.0,
                (a.longitude + b.longitude) / 2.0
        );
    }

    private boolean samePoint(@Nullable LatLng a, @Nullable LatLng b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return Math.abs(a.latitude - b.latitude) < 1e-12
                && Math.abs(a.longitude - b.longitude) < 1e-12;
    }

    private String describeVerticalTransition(boolean nearStairs,
                                              boolean nearLift,
                                              @Nullable MotionDelta motionDelta) {
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

        return likelyLiftMotion
                ? "near both stairs and lift, motion slightly favors lift"
                : "near both stairs and lift, motion slightly favors stairs";
    }
}
