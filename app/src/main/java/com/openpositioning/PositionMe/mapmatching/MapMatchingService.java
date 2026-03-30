package com.openpositioning.PositionMe.mapmatching;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

/**
 * 3.2 Map Matching 的核心服务类。
 *
 * 阶段四最小补丁的目标：
 * 1. 保留现有“拒绝错误”的能力（穿墙 / 错误换层）
 * 2. 增加更主动的几何修正，让结果更像“被地图改进过的位置”
 *
 * 具体补充：
 * - 穿墙时，不再只会回退到 previous pose；优先投影到撞墙前最后一个合法点
 * - 合法换层时，把切层点轻量锚定到附近 stairs/lift，更像发生在真实 connector 处
 */
public class MapMatchingService {
    // 水平位移较小时，更像 lift
    private static final double MAX_LIFT_HORIZONTAL_DISPLACEMENT_METERS = 1.5;
    // 楼梯换层允许有一定水平位移，但不能太大；否则多半是飘点/大跳
    private static final double MAX_STAIRS_HORIZONTAL_DISPLACEMENT_METERS = 4.0;
    // 位移太小时，不做穿墙检测，避免传感器抖动带来误判
    private static final double MIN_DISPLACEMENT_FOR_WALL_CHECK_METERS = 0.3;
    // 明显静止/微动时，直接冻结在上一帧匹配位置，优先于 small-step pass-through
    private static final double MAX_IDLE_FREEZE_STEP_METERS = 0.08;
    // 穿墙恢复后，单帧允许的最大“重新贴回”位移，避免轨迹瞬移
    private static final double MAX_RECOVERY_STEP_METERS = 0.6;
    // connector 区域附近略放宽，但仍然限制大跳
    private static final double MAX_RECOVERY_STEP_NEAR_CONNECTOR_METERS = 1.0;
    // 合法换层时，对 stairs / lift 锚定的最大允许吸附距离
    private static final double MAX_STAIRS_ANCHOR_METERS = 1.0;
    private static final double MAX_LIFT_ANCHOR_METERS = 1.0;
    // 切层成功时，把 XY 直接落到目标楼层 connector 内部，避免落在墙边再被轴向滑动。
    private static final double MAX_STAIRS_LANDING_METERS = 2.0;
    private static final double MAX_LIFT_LANDING_METERS = 1.5;
    // 切层成功后的若干帧内，不要立刻把轨迹重新完全交回普通 wall solver；
    // 使用一个短暂的平滑释放窗口，把 landing 点自然过渡到后续轨迹。
    private static final int POST_TRANSITION_RELEASE_FRAMES = 3;
    private static final double POST_TRANSITION_RELEASE_BLEND_MIN = 0.28;
    private static final double POST_TRANSITION_RELEASE_BLEND_MAX = 0.72;
    private static final double POST_TRANSITION_RELEASE_MAX_STEP_METERS = 0.75;
    private static final double POST_TRANSITION_RELEASE_COMPLETE_METERS = 0.20;
    private static final double POST_TRANSITION_RELEASE_CANCEL_METERS = 6.0;

    @Nullable
    private PostTransitionReleaseState postTransitionReleaseState;

    public void resetTransientState() {
        postTransitionReleaseState = null;
    }

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
        TransitionLanding transitionLanding = floorTransitionAttempt && floorChangeAllowed
                ? buildTransitionLanding(input, candidateLatLng, motionDelta, nearStairs, nearLift)
                : null;
        String transitionDescription = describeVerticalTransition(nearStairs, nearLift, motionDelta);
        if (input.getVerticalHint() != null && input.getVerticalHint().isHeightChanged()) {
            debugReason = "Height changed; " + transitionDescription + ".";
        } else {
            debugReason = "Candidate pose accepted; " + transitionDescription + ".";
        }

        if (shouldFreezeIdleMotion(previousPose, currentPose, motionDelta, nearStairs, nearLift, floorTransitionAttempt, input.getVerticalHint())) {
            return new MapMatchingResult(
                    true,
                    false,
                    nearStairs,
                    nearLift,
                    false,
                    previousPose.getLatLng(),
                    previousPose.getFloor(),
                    CorrectionType.NONE,
                    "Micro-motion frozen to suppress idle drift."
            );
        }

        if (transitionLanding != null) {
            startPostTransitionRelease(transitionLanding.landingLatLng, currentPose.getFloor());
            return new MapMatchingResult(
                    true,
                    false,
                    nearStairs,
                    nearLift,
                    true,
                    transitionLanding.landingLatLng,
                    currentPose.getFloor(),
                    CorrectionType.SNAP_TO_VALID_AREA,
                    transitionLanding.debugReason
            );
        }

        PostTransitionReleaseResult releaseResult = maybeApplyPostTransitionRelease(
                previousPose,
                currentPose,
                wallCheckFloorShapes,
                nearStairs,
                nearLift
        );
        if (releaseResult != null) {
            return new MapMatchingResult(
                    true,
                    releaseResult.crossedWall,
                    nearStairs,
                    nearLift,
                    false,
                    releaseResult.correctedLatLng,
                    currentPose.getFloor(),
                    CorrectionType.SNAP_TO_VALID_AREA,
                    releaseResult.debugReason
            );
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
                    WallRecovery recovery = recoverFromWallCrossing(
                            previousPose.getLatLng(),
                            candidateLatLng,
                            wallCheckFloorShapes,
                            nearStairs || nearLift || postTransitionReleaseState != null
                    );
                    correctedLatLng = recovery.correctedLatLng;
                    correctionType = recovery.correctionType;
                    debugReason = recovery.debugReason;
                    validPosition = recovery.validPosition;
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

        if (floorTransitionAttempt && floorChangeAllowed && connectorFloorShapes != null) {
            LatLng anchoredTransition = anchorFloorTransition(
                    correctedLatLng,
                    connectorFloorShapes,
                    motionDelta,
                    nearStairs,
                    nearLift
            );
            if (anchoredTransition != null && !samePoint(anchoredTransition, correctedLatLng)) {
                correctedLatLng = anchoredTransition;
                correctionType = CorrectionType.SNAP_TO_VALID_AREA;
                debugReason = nearLift && !nearStairs
                        ? "Floor transition anchored to nearby lift."
                        : nearStairs && !nearLift
                        ? "Floor transition anchored to nearby stairs."
                        : "Floor transition anchored to nearby connector.";
            }
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
            return true;
        }

        LatLng previousPoint = previousPose.getLatLng();
        LatLng currentPoint = currentPose.getLatLng();
        LatLng midpoint = midpoint(previousPoint, currentPoint);

        boolean currentNearStairs = MapGeometryUtils.isNearStairs(currentPoint, connectorFloorShapes);
        boolean currentNearLift = MapGeometryUtils.isNearLift(currentPoint, connectorFloorShapes);
        boolean previousNearStairs = previousPoint != null
                && MapGeometryUtils.isNearStairs(previousPoint, connectorFloorShapes);
        boolean previousNearLift = previousPoint != null
                && MapGeometryUtils.isNearLift(previousPoint, connectorFloorShapes);
        boolean midpointNearStairs = midpoint != null
                && MapGeometryUtils.isNearStairs(midpoint, connectorFloorShapes);
        boolean midpointNearLift = midpoint != null
                && MapGeometryUtils.isNearLift(midpoint, connectorFloorShapes);

        boolean currentInsideStairs = MapGeometryUtils.isInsideIndoorType(currentPoint, connectorFloorShapes, "stairs");
        boolean previousInsideStairs = previousPoint != null
                && MapGeometryUtils.isInsideIndoorType(previousPoint, connectorFloorShapes, "stairs");
        boolean midpointInsideStairs = midpoint != null
                && MapGeometryUtils.isInsideIndoorType(midpoint, connectorFloorShapes, "stairs");

        boolean currentInsideLift = MapGeometryUtils.isInsideIndoorType(currentPoint, connectorFloorShapes, "lift");
        boolean previousInsideLift = previousPoint != null
                && MapGeometryUtils.isInsideIndoorType(previousPoint, connectorFloorShapes, "lift");
        boolean midpointInsideLift = midpoint != null
                && MapGeometryUtils.isInsideIndoorType(midpoint, connectorFloorShapes, "lift");

        boolean stairsPersistence = currentNearStairs && (previousNearStairs || midpointNearStairs);
        boolean liftPersistence = currentNearLift && (previousNearLift || midpointNearLift);
        boolean strongStairsEvidence = currentInsideStairs && (previousInsideStairs || midpointInsideStairs);
        boolean strongLiftEvidence = currentInsideLift && (previousInsideLift || midpointInsideLift);

        if (!stairsPersistence && !liftPersistence && !strongStairsEvidence && !strongLiftEvidence) {
            return false;
        }

        MotionDelta motionDelta = input.getMotionDelta();
        if (motionDelta == null) {
            return strongStairsEvidence || strongLiftEvidence || stairsPersistence || liftPersistence;
        }

        double stepDistance = motionDelta.getStepDistance();
        if (stepDistance > MAX_STAIRS_HORIZONTAL_DISPLACEMENT_METERS) {
            return false;
        }

        boolean likelyLiftMotion = stepDistance <= MAX_LIFT_HORIZONTAL_DISPLACEMENT_METERS;

        if (strongLiftEvidence && !strongStairsEvidence) {
            return likelyLiftMotion;
        }
        if (strongStairsEvidence) {
            return true;
        }
        if (liftPersistence && !stairsPersistence) {
            return likelyLiftMotion;
        }
        if (stairsPersistence && !liftPersistence) {
            return !likelyLiftMotion;
        }
        return stairsPersistence || liftPersistence;
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
    private WallRecovery recoverFromWallCrossing(@NonNull LatLng previousLatLng,
                                                 @NonNull LatLng candidateLatLng,
                                                 @NonNull FloorplanApiClient.FloorShapes wallCheckFloorShapes,
                                                 boolean preferProjection) {
        LatLng lastValidPoint = MapGeometryUtils.findFarthestValidPointBeforeWall(
                previousLatLng,
                candidateLatLng,
                wallCheckFloorShapes
        );
        if (preferProjection && lastValidPoint != null && !samePoint(lastValidPoint, previousLatLng)) {
            return new WallRecovery(
                    lastValidPoint,
                    CorrectionType.SNAP_TO_VALID_AREA,
                    "Crossed wall near connector. Projected to the last valid point before wall.",
                    true
            );
        }

        LatLng latOnlyPoint = new LatLng(candidateLatLng.latitude, previousLatLng.longitude);
        LatLng lngOnlyPoint = new LatLng(previousLatLng.latitude, candidateLatLng.longitude);

        boolean canMoveLat = !MapGeometryUtils.crossesWall(previousLatLng, latOnlyPoint, wallCheckFloorShapes);
        boolean canMoveLng = !MapGeometryUtils.crossesWall(previousLatLng, lngOnlyPoint, wallCheckFloorShapes);

        if (canMoveLat && canMoveLng) {
            double latOnlyResidual = distanceMeters(latOnlyPoint, candidateLatLng);
            double lngOnlyResidual = distanceMeters(lngOnlyPoint, candidateLatLng);
            if (latOnlyResidual <= lngOnlyResidual) {
                return new WallRecovery(
                        latOnlyPoint,
                        CorrectionType.THROUGH_WALL,
                        "Crossed wall. Sliding along Latitude (N/S).",
                        true
                );
            }
            return new WallRecovery(
                    lngOnlyPoint,
                    CorrectionType.THROUGH_WALL,
                    "Crossed wall. Sliding along Longitude (E/W).",
                    true
            );
        }

        if (canMoveLat) {
            return new WallRecovery(
                    latOnlyPoint,
                    CorrectionType.THROUGH_WALL,
                    "Crossed wall. Sliding along Latitude (N/S).",
                    true
            );
        }

        if (canMoveLng) {
            return new WallRecovery(
                    lngOnlyPoint,
                    CorrectionType.THROUGH_WALL,
                    "Crossed wall. Sliding along Longitude (E/W).",
                    true
            );
        }

        if (lastValidPoint != null && !samePoint(lastValidPoint, previousLatLng)) {
            return new WallRecovery(
                    lastValidPoint,
                    CorrectionType.SNAP_TO_VALID_AREA,
                    "Crossed wall. Projected to last valid point before wall.",
                    true
            );
        }

        return new WallRecovery(
                previousLatLng,
                CorrectionType.THROUGH_WALL,
                "Crossed wall. Stuck in corner, reverted to previous XY pose.",
                false
        );
    }

    @Nullable
    private LatLng anchorFloorTransition(@NonNull LatLng point,
                                         @NonNull FloorplanApiClient.FloorShapes connectorFloorShapes,
                                         @Nullable MotionDelta motionDelta,
                                         boolean nearStairs,
                                         boolean nearLift) {
        boolean insideStairs = MapGeometryUtils.isInsideIndoorType(point, connectorFloorShapes, "stairs");
        boolean insideLift = MapGeometryUtils.isInsideIndoorType(point, connectorFloorShapes, "lift");
        if (!nearStairs && !nearLift && !insideStairs && !insideLift) {
            return null;
        }

        boolean likelyLiftMotion = motionDelta != null
                && motionDelta.getStepDistance() <= MAX_LIFT_HORIZONTAL_DISPLACEMENT_METERS;

        boolean preferLift = (insideLift || nearLift) && (!insideStairs && (!nearStairs || likelyLiftMotion));
        boolean preferStairs = insideStairs || (nearStairs && (!nearLift || !likelyLiftMotion));

        LatLng anchor = null;
        double maxAnchorMeters = MAX_STAIRS_ANCHOR_METERS;

        if (preferLift) {
            anchor = MapGeometryUtils.findNearestSafeInteriorPointOnIndoorType(point, connectorFloorShapes, "lift");
            if (anchor == null) {
                anchor = MapGeometryUtils.findNearestPointOnIndoorType(point, connectorFloorShapes, "lift");
            }
            maxAnchorMeters = MAX_LIFT_ANCHOR_METERS;
        } else if (preferStairs) {
            anchor = MapGeometryUtils.findNearestSafeInteriorPointOnIndoorType(point, connectorFloorShapes, "stairs");
            if (anchor == null) {
                anchor = MapGeometryUtils.findNearestPointOnIndoorType(point, connectorFloorShapes, "stairs");
            }
            maxAnchorMeters = MAX_STAIRS_ANCHOR_METERS;
        }

        if (anchor == null && nearLift) {
            anchor = MapGeometryUtils.findNearestSafeInteriorPointOnIndoorType(point, connectorFloorShapes, "lift");
            if (anchor == null) {
                anchor = MapGeometryUtils.findNearestPointOnIndoorType(point, connectorFloorShapes, "lift");
            }
            maxAnchorMeters = MAX_LIFT_ANCHOR_METERS;
        }
        if (anchor == null && nearStairs) {
            anchor = MapGeometryUtils.findNearestSafeInteriorPointOnIndoorType(point, connectorFloorShapes, "stairs");
            if (anchor == null) {
                anchor = MapGeometryUtils.findNearestPointOnIndoorType(point, connectorFloorShapes, "stairs");
            }
            maxAnchorMeters = MAX_STAIRS_ANCHOR_METERS;
        }

        if (anchor == null) {
            return null;
        }

        return distanceMeters(point, anchor) <= maxAnchorMeters ? anchor : null;
    }

    @Nullable
    private TransitionLanding buildTransitionLanding(@NonNull MapMatchingInput input,
                                                     @NonNull LatLng candidateLatLng,
                                                     @Nullable MotionDelta motionDelta,
                                                     boolean nearStairs,
                                                     boolean nearLift) {
        FloorplanApiClient.FloorShapes targetFloorShapes = input.getTargetFloorShapes();
        if (targetFloorShapes == null) {
            return null;
        }

        boolean likelyLiftMotion = motionDelta != null
                && motionDelta.getStepDistance() <= MAX_LIFT_HORIZONTAL_DISPLACEMENT_METERS;

        String preferredIndoorType;
        double maxLandingMeters;
        if (nearLift && !nearStairs) {
            preferredIndoorType = "lift";
            maxLandingMeters = MAX_LIFT_LANDING_METERS;
        } else if (nearStairs && !nearLift) {
            preferredIndoorType = "stairs";
            maxLandingMeters = MAX_STAIRS_LANDING_METERS;
        } else if (nearLift && likelyLiftMotion) {
            preferredIndoorType = "lift";
            maxLandingMeters = MAX_LIFT_LANDING_METERS;
        } else {
            preferredIndoorType = "stairs";
            maxLandingMeters = MAX_STAIRS_LANDING_METERS;
        }

        LatLng safeInteriorLanding = MapGeometryUtils.findNearestSafeInteriorPointOnIndoorType(
                candidateLatLng,
                targetFloorShapes,
                preferredIndoorType
        );
        if (safeInteriorLanding != null && distanceMeters(candidateLatLng, safeInteriorLanding) <= maxLandingMeters) {
            String debugReason = "lift".equals(preferredIndoorType)
                    ? "Floor transition landed on a safe target-floor lift point."
                    : "Floor transition landed on a safe target-floor stairs point.";
            return new TransitionLanding(safeInteriorLanding, debugReason);
        }

        LatLng centroidLanding = MapGeometryUtils.findNearestIndoorTypeCentroid(
                candidateLatLng,
                targetFloorShapes,
                preferredIndoorType
        );
        if (centroidLanding != null && distanceMeters(candidateLatLng, centroidLanding) <= maxLandingMeters) {
            String debugReason = "lift".equals(preferredIndoorType)
                    ? "Floor transition landed inside target lift."
                    : "Floor transition landed inside target stairs.";
            return new TransitionLanding(centroidLanding, debugReason);
        }

        LatLng fallbackAnchor = anchorFloorTransition(
                candidateLatLng,
                targetFloorShapes,
                motionDelta,
                nearStairs,
                nearLift
        );
        if (fallbackAnchor != null) {
            String debugReason = "lift".equals(preferredIndoorType)
                    ? "Floor transition anchored on target-floor lift."
                    : "Floor transition anchored on target-floor stairs.";
            return new TransitionLanding(fallbackAnchor, debugReason);
        }

        return null;
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

    private void startPostTransitionRelease(@NonNull LatLng landingLatLng, int floor) {
        postTransitionReleaseState = new PostTransitionReleaseState(
                landingLatLng,
                floor,
                POST_TRANSITION_RELEASE_FRAMES
        );
    }

    @Nullable
    private PostTransitionReleaseResult maybeApplyPostTransitionRelease(@Nullable CandidatePose previousPose,
                                                                        @NonNull CandidatePose currentPose,
                                                                        @Nullable FloorplanApiClient.FloorShapes wallCheckFloorShapes,
                                                                        boolean nearStairs,
                                                                        boolean nearLift) {
        PostTransitionReleaseState state = postTransitionReleaseState;
        if (state == null) {
            return null;
        }
        if (previousPose == null || previousPose.getLatLng() == null) {
            postTransitionReleaseState = null;
            return null;
        }
        if (currentPose.getFloor() != state.floor || previousPose.getFloor() != state.floor) {
            postTransitionReleaseState = null;
            return null;
        }

        LatLng candidateLatLng = currentPose.getLatLng();
        LatLng previousLatLng = previousPose.getLatLng();
        double anchorToCandidateMeters = distanceMeters(state.anchorLatLng, candidateLatLng);
        if (anchorToCandidateMeters >= POST_TRANSITION_RELEASE_CANCEL_METERS) {
            postTransitionReleaseState = null;
            return null;
        }

        double progress = 1.0 - (state.framesRemaining / (double) POST_TRANSITION_RELEASE_FRAMES);
        double eased = smoothStep(progress);
        double blendRatio = POST_TRANSITION_RELEASE_BLEND_MIN
                + (POST_TRANSITION_RELEASE_BLEND_MAX - POST_TRANSITION_RELEASE_BLEND_MIN) * eased;

        LatLng blended = interpolate(state.anchorLatLng, candidateLatLng, blendRatio);
        blended = clampStepFromPrevious(previousLatLng, blended, POST_TRANSITION_RELEASE_MAX_STEP_METERS);

        boolean crossedWall = false;
        if (wallCheckFloorShapes != null && MapGeometryUtils.crossesWall(previousLatLng, blended, wallCheckFloorShapes)) {
            crossedWall = true;
            LatLng lastValid = MapGeometryUtils.findFarthestValidPointBeforeWall(previousLatLng, blended, wallCheckFloorShapes);
            if (lastValid != null && !samePoint(lastValid, previousLatLng)) {
                blended = clampStepFromPrevious(previousLatLng, lastValid, POST_TRANSITION_RELEASE_MAX_STEP_METERS);
            } else {
                blended = previousLatLng;
            }
            postTransitionReleaseState = null;
            return new PostTransitionReleaseResult(
                    blended,
                    true,
                    "Post-transition wall guard ended release smoothing at the last valid point."
            );
        }

        state.framesRemaining = Math.max(0, state.framesRemaining - 1);
        double blendedToCandidateMeters = distanceMeters(blended, candidateLatLng);
        if (state.framesRemaining == 0 || blendedToCandidateMeters <= POST_TRANSITION_RELEASE_COMPLETE_METERS) {
            postTransitionReleaseState = null;
        } else {
            state.anchorLatLng = blended;
            postTransitionReleaseState = state;
        }

        String debugReason = (nearStairs || nearLift)
                ? "Post-transition release smoothing applied near connector."
                : "Post-transition release smoothing applied.";
        return new PostTransitionReleaseResult(blended, crossedWall, debugReason);
    }

    @NonNull
    private LatLng clampStepFromPrevious(@NonNull LatLng previousLatLng,
                                         @NonNull LatLng desiredLatLng,
                                         double maxStepMeters) {
        double stepMeters = distanceMeters(previousLatLng, desiredLatLng);
        if (stepMeters <= maxStepMeters) {
            return desiredLatLng;
        }
        double ratio = maxStepMeters / Math.max(stepMeters, 1e-6d);
        return interpolate(previousLatLng, desiredLatLng, ratio);
    }

    @NonNull
    private LatLng interpolate(@NonNull LatLng start,
                               @NonNull LatLng end,
                               double ratio) {
        double clamped = Math.max(0d, Math.min(1d, ratio));
        double latitude = start.latitude + (end.latitude - start.latitude) * clamped;
        double longitude = start.longitude + (end.longitude - start.longitude) * clamped;
        return new LatLng(latitude, longitude);
    }

    private double smoothStep(double progress) {
        double clamped = Math.max(0d, Math.min(1d, progress));
        return clamped * clamped * (3d - 2d * clamped);
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

    private boolean shouldFreezeIdleMotion(@Nullable CandidatePose previousPose,
                                           @NonNull CandidatePose currentPose,
                                           @Nullable MotionDelta motionDelta,
                                           boolean nearStairs,
                                           boolean nearLift,
                                           boolean floorTransitionAttempt,
                                           @Nullable VerticalTransitionHint verticalHint) {
        if (previousPose == null || motionDelta == null) {
            return false;
        }
        if (floorTransitionAttempt || nearStairs || nearLift) {
            return false;
        }
        if (verticalHint != null && verticalHint.isHeightChanged()) {
            return false;
        }
        if (motionDelta.getStepDistance() > MAX_IDLE_FREEZE_STEP_METERS) {
            return false;
        }

        LatLng previousLatLng = previousPose.getLatLng();
        LatLng currentLatLng = currentPose.getLatLng();
        if (previousLatLng == null || currentLatLng == null) {
            return false;
        }

        return !samePoint(previousLatLng, currentLatLng) || previousPose.getFloor() == currentPose.getFloor();
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

    private double distanceMeters(@NonNull LatLng from, @NonNull LatLng to) {
        double latRad = Math.toRadians(from.latitude);
        double metersPerDegLat = 111320.0;
        double metersPerDegLon = 111320.0 * Math.cos(latRad);
        double dx = (to.longitude - from.longitude) * metersPerDegLon;
        double dy = (to.latitude - from.latitude) * metersPerDegLat;
        return Math.hypot(dx, dy);
    }

    private static final class TransitionLanding {
        final LatLng landingLatLng;
        final String debugReason;

        TransitionLanding(@NonNull LatLng landingLatLng, @NonNull String debugReason) {
            this.landingLatLng = landingLatLng;
            this.debugReason = debugReason;
        }
    }

    private static final class PostTransitionReleaseState {
        @NonNull
        private LatLng anchorLatLng;
        private final int floor;
        private int framesRemaining;

        private PostTransitionReleaseState(@NonNull LatLng anchorLatLng,
                                           int floor,
                                           int framesRemaining) {
            this.anchorLatLng = anchorLatLng;
            this.floor = floor;
            this.framesRemaining = framesRemaining;
        }
    }

    private static final class PostTransitionReleaseResult {
        @NonNull
        private final LatLng correctedLatLng;
        private final boolean crossedWall;
        @NonNull
        private final String debugReason;

        private PostTransitionReleaseResult(@NonNull LatLng correctedLatLng,
                                            boolean crossedWall,
                                            @NonNull String debugReason) {
            this.correctedLatLng = correctedLatLng;
            this.crossedWall = crossedWall;
            this.debugReason = debugReason;
        }
    }

    private static final class WallRecovery {
        @NonNull
        private final LatLng correctedLatLng;
        @NonNull
        private final CorrectionType correctionType;
        @NonNull
        private final String debugReason;
        private final boolean validPosition;

        private WallRecovery(@NonNull LatLng correctedLatLng,
                             @NonNull CorrectionType correctionType,
                             @NonNull String debugReason,
                             boolean validPosition) {
            this.correctedLatLng = correctedLatLng;
            this.correctionType = correctionType;
            this.debugReason = debugReason;
            this.validPosition = validPosition;
        }
    }
}
