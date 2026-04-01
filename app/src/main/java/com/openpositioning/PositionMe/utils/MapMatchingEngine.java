package com.openpositioning.PositionMe.utils;

import com.openpositioning.PositionMe.data.remote.FloorPlan;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * New code guide:
 * 1. Transition scoring for wall and floor-change legality.
 * 2. Wall-aware correction for step motion.
 * 3. Vertical access detection and snapping.
 * 4. Shared geometry helpers for floor-plan features.
 */
public class MapMatchingEngine {

    // Distinguishes ordinary areas from legal floor-change areas.
    public enum VerticalZoneType {
        NONE,
        STAIRS,
        LIFT
    }

    private static final float VERTICAL_ZONE_STAIR_NEAR_METERS = 1.8f;
    private static final float VERTICAL_ZONE_LIFT_NEAR_METERS = 2.2f;
    private static final float FEATURE_DISTANCE_BIAS_METERS = 0.2f;
    private static final float MIN_SAFE_DISTANCE_METERS = 0.05f;
    private static final float WALL_CROSSING_PENALTY = 0.03f;
    private static final float WRONG_VERTICAL_TRANSITION_PENALTY = 0.04f;
    private static final float NEAR_VERTICAL_TRANSITION_SCORE = 0.68f;
    private static final float GOOD_WALL_CLEARANCE_METERS = 0.35f;
    private static final float POOR_WALL_CLEARANCE_METERS = 0.05f;
    private static final float WALL_GUIDANCE_TRIGGER_METERS = 0.24f;
    private static final float WALL_GUIDANCE_MIN_PROGRESS_METERS = 0.08f;
    private static final float WALL_GUIDANCE_MIN_APPROACH_DOT = 0.18f;
    private static final float WALL_GUIDANCE_JUNCTION_RADIUS_METERS = 0.70f;  // was 0.52f — wider corner exclusion zone, prevents guidance lock at junctions
    private static final float WALL_GUIDANCE_JUNCTION_COS_THRESHOLD = 0.84f;

    private final CoordinateUtils coordinateUtils;
    private final Map<List<Double>, float[]> pointXYCache = new IdentityHashMap<>();
    private double cacheOriginLat = Double.NaN;
    private double cacheOriginLon = Double.NaN;

    private static final class IntersectionHit {
        private final float t;
        private final float[] wallStart;
        private final float[] wallEnd;

        // Stores the first wall intersection found for a motion segment.
        private IntersectionHit(float t, float[] wallStart, float[] wallEnd) {
            this.t = t;
            this.wallStart = wallStart;
            this.wallEnd = wallEnd;
        }
    }

    private static final class NearestWallInfo {
        private final float distanceMeters;
        private final float[] nearestPoint;
        private final float[] wallStart;
        private final float[] wallEnd;

        // Stores the nearest wall segment and projected point.
        private NearestWallInfo(float distanceMeters, float[] nearestPoint, float[] wallStart, float[] wallEnd) {
            this.distanceMeters = distanceMeters;
            this.nearestPoint = nearestPoint;
            this.wallStart = wallStart;
            this.wallEnd = wallEnd;
        }
    }

    // Keeps the shared coordinate converter for floor-plan geometry.
    public MapMatchingEngine(CoordinateUtils coordinateUtils) {
        this.coordinateUtils = coordinateUtils;
    }

    // Checks whether a straight movement would cross any wall.
    public boolean isMovementValid(float[] startXY, float[] endXY, FloorPlan floor) {
        if (!isValidXY(startXY) || !isValidXY(endXY) || floor == null || floor.getWalls() == null) {
            return true;
        }
        return findFirstIntersection(startXY, endXY, floor) == null;
    }

    // Scores whether a particle transition is plausible on the current floor.
    public float transitionScore(float[] startXY,
                                 float[] endXY,
                                 FloorPlan floor,
                                 boolean floorChange,
                                 boolean elevatorMode) {
        if (!isValidXY(endXY) || floor == null) {
            return 1f;
        }

        float score = stateSupportScore(endXY, floor);
        if (isValidXY(startXY) && findFirstIntersection(startXY, endXY, floor) != null) {
            score *= WALL_CROSSING_PENALTY;
        }
        if (floorChange) {
            float[] reference = isValidXY(startXY) ? startXY : endXY;
            score *= verticalTransitionScore(reference, elevatorMode, floor);
        }
        return clamp(score, 0f, 1f);
    }

    // Scores how safe one position is with respect to nearby walls.
    public float stateSupportScore(float[] pointXY, FloorPlan floor) {
        if (!isValidXY(pointXY) || floor == null || floor.getWalls() == null || floor.getWalls().isEmpty()) {
            return 1f;
        }

        float minWallDistance = minDistanceToWalls(pointXY, floor);
        if (minWallDistance <= POOR_WALL_CLEARANCE_METERS) {
            return 0.18f;
        }
        if (minWallDistance >= GOOD_WALL_CLEARANCE_METERS) {
            return 1f;
        }

        float ratio = (minWallDistance - POOR_WALL_CLEARANCE_METERS)
                / Math.max(1e-5f, GOOD_WALL_CLEARANCE_METERS - POOR_WALL_CLEARANCE_METERS);
        return clamp(0.18f + 0.82f * ratio, 0.18f, 1f);
    }

    // Scores whether a floor change is reasonable at the current place.
    public float verticalTransitionScore(float[] currentXY, boolean elevatorMode, FloorPlan floor) {
        if (!isValidXY(currentXY) || floor == null) {
            return 1f;
        }

        VerticalZoneType zoneType = detectVerticalZone(currentXY, floor);
        if (elevatorMode) {
            if (zoneType == VerticalZoneType.LIFT) {
                return 1f;
            }
            float nearestLift = nearestDistanceToFeatures(currentXY, floor.getLifts());
            if (nearestLift <= VERTICAL_ZONE_LIFT_NEAR_METERS) {
                return NEAR_VERTICAL_TRANSITION_SCORE;
            }
            return WRONG_VERTICAL_TRANSITION_PENALTY;
        }

        if (zoneType == VerticalZoneType.STAIRS) {
            return 1f;
        }
        float nearestStairs = nearestDistanceToFeatures(currentXY, floor.getStairs());
        if (nearestStairs <= VERTICAL_ZONE_STAIR_NEAR_METERS) {
            return NEAR_VERTICAL_TRANSITION_SCORE;
        }
        return WRONG_VERTICAL_TRANSITION_PENALTY;
    }

    // Checks whether the user is inside a valid stairs or lift area.
    public boolean isInsideVerticalAccess(float[] currentXY, boolean elevatorMode, FloorPlan floor) {
        if (!isValidXY(currentXY) || floor == null) {
            return false;
        }
        return elevatorMode
                ? isPointInAnyFeature(currentXY, floor.getLifts())
                : isPointInAnyFeature(currentXY, floor.getStairs());
    }

    // Stops motion at walls, then tries to keep the remaining movement sliding along them.
    public float[] correctMovementAgainstWalls(float[] startXY,
                                               float[] endXY,
                                               FloorPlan floor,
                                               float safetyMarginMeters) {
        if (!isValidXY(endXY)) {
            return endXY;
        }
        if (!isValidXY(startXY) || floor == null || floor.getWalls() == null || floor.getWalls().isEmpty()) {
            return new float[]{endXY[0], endXY[1]};
        }

        float[] corrected = new float[]{endXY[0], endXY[1]};
        float dx = endXY[0] - startXY[0];
        float dy = endXY[1] - startXY[1];
        float travel = (float) Math.sqrt(dx * dx + dy * dy);
        if (travel < 1e-5f) {
            return corrected;
        }

        IntersectionHit firstHit = findFirstIntersection(startXY, endXY, floor);
        boolean wallCorrected = false;
        if (firstHit != null) {
            float margin = Math.max(0f, safetyMarginMeters);
            float marginT = margin / travel;
            float safeT = Math.max(0f, firstHit.t - marginT);
            corrected[0] = startXY[0] + dx * safeT;
            corrected[1] = startXY[1] + dy * safeT;
            wallCorrected = true;

            float remaining = Math.max(0f, travel * (1f - safeT) - margin);
            if (remaining > 0.02f) {
                float dirX = dx / travel;
                float dirY = dy / travel;
                float[] slideCandidate = slideAlongWall(
                        corrected,
                        firstHit.wallStart,
                        firstHit.wallEnd,
                        dirX,
                        dirY,
                        remaining,
                        1f
                );
                if (!isValidSlidePath(corrected, slideCandidate, floor)) {
                    slideCandidate = slideAlongWall(
                            corrected,
                            firstHit.wallStart,
                            firstHit.wallEnd,
                            dirX,
                            dirY,
                            remaining,
                            -1f
                    );
                }
                if (isValidSlidePath(corrected, slideCandidate, floor)) {
                    corrected[0] = slideCandidate[0];
                    corrected[1] = slideCandidate[1];
                }
            }
        }

        corrected = guideMovementAlongNearestWall(
                startXY,
                corrected,
                floor,
                safetyMarginMeters,
                wallCorrected
        );

        float safeDistance = Math.max(MIN_SAFE_DISTANCE_METERS, safetyMarginMeters * 0.8f);
        float minWallDistance = minDistanceToWalls(corrected, floor);
        if (minWallDistance < safeDistance) {
            float pull = Math.min(1f, (safeDistance - minWallDistance) / safeDistance);
            corrected[0] = corrected[0] * (1f - pull) + startXY[0] * pull;
            corrected[1] = corrected[1] * (1f - pull) + startXY[1] * pull;
        }

        IntersectionHit secondHit = findFirstIntersection(startXY, corrected, floor);
        if (secondHit != null && secondHit.t < 0.999f) {
            float retreatT = Math.max(0f, secondHit.t - 0.02f);
            return new float[]{
                    startXY[0] + (corrected[0] - startXY[0]) * retreatT,
                    startXY[1] + (corrected[1] - startXY[1]) * retreatT
            };
        }

        return corrected;
    }

    // Checks whether the current position allows a floor transition.
    public boolean canChangeFloor(float[] currentXY, boolean isElevator, FloorPlan floor) {
        return verticalTransitionScore(currentXY, isElevator, floor) >= 0.6f;
    }

    // Finds whether the current position is best explained by stairs, lift, or neither.
    public VerticalZoneType detectVerticalZone(float[] currentXY, FloorPlan floor) {
        if (!isValidXY(currentXY) || floor == null) {
            return VerticalZoneType.NONE;
        }

        float nearestLift = nearestDistanceToFeatures(currentXY, floor.getLifts());
        float nearestStairs = nearestDistanceToFeatures(currentXY, floor.getStairs());

        boolean inLift = isPointInAnyFeature(currentXY, floor.getLifts());
        boolean inStairs = isPointInAnyFeature(currentXY, floor.getStairs());

        if (inLift) {
            return VerticalZoneType.LIFT;
        }
        if (inStairs) {
            return VerticalZoneType.STAIRS;
        }

        if (nearestLift <= VERTICAL_ZONE_LIFT_NEAR_METERS
                && nearestLift <= nearestStairs + FEATURE_DISTANCE_BIAS_METERS) {
            return VerticalZoneType.LIFT;
        }
        if (nearestStairs <= VERTICAL_ZONE_STAIR_NEAR_METERS) {
            return VerticalZoneType.STAIRS;
        }
        return VerticalZoneType.NONE;
    }

    // Snaps a point onto the nearest valid stairs or lift access area.
    public float[] snapToVerticalAccess(float[] currentXY, boolean elevatorMode, FloorPlan floor) {
        if (!isValidXY(currentXY)) {
            return currentXY;
        }
        if (floor == null) {
            return new float[]{currentXY[0], currentXY[1]};
        }

        List<List<List<Double>>> features = elevatorMode ? floor.getLifts() : floor.getStairs();
        float[] snapped = snapToNearestFeature(currentXY, features);
        if (!isValidXY(snapped)) {
            return new float[]{currentXY[0], currentXY[1]};
        }
        return snapped;
    }

    // Slides the remaining motion along the wall direction.
    private float[] slideAlongWall(float[] anchor,
                                   float[] wallStart,
                                   float[] wallEnd,
                                   float moveDirX,
                                   float moveDirY,
                                   float distanceMeters,
                                   float preferredSign) {
        if (!isValidXY(anchor) || !isValidXY(wallStart) || !isValidXY(wallEnd) || distanceMeters <= 0f) {
            return null;
        }
        float wallDx = wallEnd[0] - wallStart[0];
        float wallDy = wallEnd[1] - wallStart[1];
        float wallLen = (float) Math.sqrt(wallDx * wallDx + wallDy * wallDy);
        if (wallLen < 1e-5f) {
            return null;
        }
        float tangentX = wallDx / wallLen;
        float tangentY = wallDy / wallLen;
        float dirAlignment = moveDirX * tangentX + moveDirY * tangentY;
        float sign = dirAlignment >= 0f ? 1f : -1f;
        sign *= preferredSign;
        return new float[]{
                anchor[0] + sign * tangentX * distanceMeters,
                anchor[1] + sign * tangentY * distanceMeters
        };
    }

    // Checks whether a sliding path stays clear of walls.
    private boolean isValidSlidePath(float[] startXY, float[] endXY, FloorPlan floor) {
        if (!isValidXY(startXY) || !isValidXY(endXY)) {
            return false;
        }
        IntersectionHit hit = findFirstIntersection(startXY, endXY, floor);
        return hit == null;
    }

    // Biases a near-wall step to follow the corridor instead of cutting through the wall.
    private float[] guideMovementAlongNearestWall(float[] startXY,
                                                  float[] endXY,
                                                  FloorPlan floor,
                                                  float safetyMarginMeters,
                                                  boolean forceGuidance) {
        if (!isValidXY(startXY) || !isValidXY(endXY) || floor == null || floor.getWalls() == null) {
            return endXY;
        }

        float moveDx = endXY[0] - startXY[0];
        float moveDy = endXY[1] - startXY[1];
        float travel = (float) Math.sqrt(moveDx * moveDx + moveDy * moveDy);
        if (travel < WALL_GUIDANCE_MIN_PROGRESS_METERS) {
            return endXY;
        }

        NearestWallInfo nearestWall = findNearestWall(endXY, floor);
        if (nearestWall == null || !isValidXY(nearestWall.nearestPoint)) {
            return endXY;
        }

        float triggerDistance = Math.max(WALL_GUIDANCE_TRIGGER_METERS, safetyMarginMeters * 1.35f);
        if (!forceGuidance && nearestWall.distanceMeters > triggerDistance) {
            return endXY;
        }
        if (hasAmbiguousWallGuidance(
                endXY,
                nearestWall.wallStart,
                nearestWall.wallEnd,
                floor,
                Math.max(WALL_GUIDANCE_JUNCTION_RADIUS_METERS, triggerDistance * 1.7f)
        )) {
            return endXY;
        }

        float wallDx = nearestWall.wallEnd[0] - nearestWall.wallStart[0];
        float wallDy = nearestWall.wallEnd[1] - nearestWall.wallStart[1];
        float wallLength = (float) Math.sqrt(wallDx * wallDx + wallDy * wallDy);
        if (wallLength < 1e-5f) {
            return endXY;
        }

        float tangentX = wallDx / wallLength;
        float tangentY = wallDy / wallLength;
        float parallelProgress = moveDx * tangentX + moveDy * tangentY;
        if (!forceGuidance && Math.abs(parallelProgress) < Math.max(WALL_GUIDANCE_MIN_PROGRESS_METERS, travel * 0.18f)) {
            return endXY;
        }

        float offsetX = endXY[0] - nearestWall.nearestPoint[0];
        float offsetY = endXY[1] - nearestWall.nearestPoint[1];
        float offsetNorm = (float) Math.sqrt(offsetX * offsetX + offsetY * offsetY);
        float normalX;
        float normalY;
        if (offsetNorm > 1e-5f) {
            normalX = offsetX / offsetNorm;
            normalY = offsetY / offsetNorm;
        } else {
            normalX = -tangentY;
            normalY = tangentX;
            float startSide = (startXY[0] - nearestWall.nearestPoint[0]) * normalX
                    + (startXY[1] - nearestWall.nearestPoint[1]) * normalY;
            if (startSide < 0f) {
                normalX = -normalX;
                normalY = -normalY;
            }
        }

        float moveNormX = moveDx / travel;
        float moveNormY = moveDy / travel;
        float approachDot = -(moveNormX * normalX + moveNormY * normalY);
        if (!forceGuidance && approachDot < WALL_GUIDANCE_MIN_APPROACH_DOT) {
            return endXY;
        }

        float safeOffset = Math.max(MIN_SAFE_DISTANCE_METERS, safetyMarginMeters * 0.9f);
        float[] guided = new float[]{
                nearestWall.nearestPoint[0] + tangentX * parallelProgress + normalX * safeOffset,
                nearestWall.nearestPoint[1] + tangentY * parallelProgress + normalY * safeOffset
        };
        if (isValidSlidePath(startXY, guided, floor)) {
            return guided;
        }
        return endXY;
    }

    // Finds the first wall segment hit by a movement line.
    private IntersectionHit findFirstIntersection(float[] startXY, float[] endXY, FloorPlan floor) {
        if (floor == null || floor.getWalls() == null) {
            return null;
        }

        IntersectionHit firstHit = null;
        for (List<List<Double>> wallPath : floor.getWalls()) {
            if (wallPath == null || wallPath.size() < 2) {
                continue;
            }
            for (int i = 0; i < wallPath.size() - 1; i++) {
                float[] wallPt1 = latLonToXY(wallPath.get(i));
                float[] wallPt2 = latLonToXY(wallPath.get(i + 1));
                if (!isValidXY(wallPt1) || !isValidXY(wallPt2)) {
                    continue;
                }

                Float hitT = segmentIntersectionT(
                        startXY[0], startXY[1], endXY[0], endXY[1],
                        wallPt1[0], wallPt1[1], wallPt2[0], wallPt2[1]
                );
                if (hitT != null && (firstHit == null || hitT < firstHit.t)) {
                    firstHit = new IntersectionHit(
                            hitT,
                            new float[]{wallPt1[0], wallPt1[1]},
                            new float[]{wallPt2[0], wallPt2[1]}
                    );
                }
            }
        }
        return firstHit;
    }

    // Finds the wall segment closest to a given point.
    private NearestWallInfo findNearestWall(float[] pointXY, FloorPlan floor) {
        if (!isValidXY(pointXY) || floor == null || floor.getWalls() == null) {
            return null;
        }

        NearestWallInfo nearestWall = null;
        float bestDistance = Float.MAX_VALUE;
        for (List<List<Double>> wallPath : floor.getWalls()) {
            if (wallPath == null || wallPath.size() < 2) {
                continue;
            }
            for (int i = 0; i < wallPath.size() - 1; i++) {
                float[] wallPt1 = latLonToXY(wallPath.get(i));
                float[] wallPt2 = latLonToXY(wallPath.get(i + 1));
                float[] nearestPoint = nearestPointOnSegment(pointXY, wallPt1, wallPt2);
                if (!isValidXY(wallPt1) || !isValidXY(wallPt2) || !isValidXY(nearestPoint)) {
                    continue;
                }
                float distance = distanceBetween(pointXY, nearestPoint);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    nearestWall = new NearestWallInfo(
                            distance,
                            nearestPoint,
                            new float[]{wallPt1[0], wallPt1[1]},
                            new float[]{wallPt2[0], wallPt2[1]}
                    );
                }
            }
        }
        return nearestWall;
    }

    // Detects junctions where wall guidance would be ambiguous.
    private boolean hasAmbiguousWallGuidance(float[] pointXY,
                                             float[] chosenWallStart,
                                             float[] chosenWallEnd,
                                             FloorPlan floor,
                                             float radiusMeters) {
        if (!isValidXY(pointXY)
                || !isValidXY(chosenWallStart)
                || !isValidXY(chosenWallEnd)
                || floor == null
                || floor.getWalls() == null) {
            return false;
        }

        float chosenDx = chosenWallEnd[0] - chosenWallStart[0];
        float chosenDy = chosenWallEnd[1] - chosenWallStart[1];
        float chosenLen = (float) Math.sqrt(chosenDx * chosenDx + chosenDy * chosenDy);
        if (chosenLen < 1e-5f) {
            return false;
        }
        float chosenTx = chosenDx / chosenLen;
        float chosenTy = chosenDy / chosenLen;

        for (List<List<Double>> wallPath : floor.getWalls()) {
            if (wallPath == null || wallPath.size() < 2) {
                continue;
            }
            for (int i = 0; i < wallPath.size() - 1; i++) {
                float[] wallPt1 = latLonToXY(wallPath.get(i));
                float[] wallPt2 = latLonToXY(wallPath.get(i + 1));
                if (!isValidXY(wallPt1) || !isValidXY(wallPt2)) {
                    continue;
                }
                if (isSameWallSegment(chosenWallStart, chosenWallEnd, wallPt1, wallPt2)) {
                    continue;
                }

                float[] nearestPoint = nearestPointOnSegment(pointXY, wallPt1, wallPt2);
                if (!isValidXY(nearestPoint) || distanceBetween(pointXY, nearestPoint) > radiusMeters) {
                    continue;
                }

                float segDx = wallPt2[0] - wallPt1[0];
                float segDy = wallPt2[1] - wallPt1[1];
                float segLen = (float) Math.sqrt(segDx * segDx + segDy * segDy);
                if (segLen < 1e-5f) {
                    continue;
                }
                float segTx = segDx / segLen;
                float segTy = segDy / segLen;
                float alignment = Math.abs(chosenTx * segTx + chosenTy * segTy);
                if (alignment < WALL_GUIDANCE_JUNCTION_COS_THRESHOLD) {
                    return true;
                }
            }
        }
        return false;
    }

    // Checks whether two wall segments represent the same edge.
    private boolean isSameWallSegment(float[] aStart, float[] aEnd, float[] bStart, float[] bEnd) {
        return (distanceBetween(aStart, bStart) <= 0.05f && distanceBetween(aEnd, bEnd) <= 0.05f)
                || (distanceBetween(aStart, bEnd) <= 0.05f && distanceBetween(aEnd, bStart) <= 0.05f);
    }

    // Returns where two line segments intersect along the first segment.
    private Float segmentIntersectionT(float x1, float y1, float x2, float y2,
                                       float x3, float y3, float x4, float y4) {
        float rx = x2 - x1;
        float ry = y2 - y1;
        float sx = x4 - x3;
        float sy = y4 - y3;

        float rCrossS = cross(rx, ry, sx, sy);
        float qpx = x3 - x1;
        float qpy = y3 - y1;

        if (Math.abs(rCrossS) < 1e-6f) {
            return null;
        }

        float t = cross(qpx, qpy, sx, sy) / rCrossS;
        float u = cross(qpx, qpy, rx, ry) / rCrossS;
        if (t >= 0f && t <= 1f && u >= 0f && u <= 1f) {
            return t;
        }
        return null;
    }

    // Finds the minimum distance from a point to all walls.
    private float minDistanceToWalls(float[] pointXY, FloorPlan floor) {
        if (!isValidXY(pointXY) || floor == null || floor.getWalls() == null) {
            return Float.MAX_VALUE;
        }
        float minDistance = Float.MAX_VALUE;
        for (List<List<Double>> wallPath : floor.getWalls()) {
            float d = distanceToFeature(pointXY, wallPath);
            if (d < minDistance) {
                minDistance = d;
            }
        }
        return minDistance;
    }

    // Finds the nearest distance from a point to a set of features.
    private float nearestDistanceToFeatures(float[] pointXY, List<List<List<Double>>> features) {
        if (!isValidXY(pointXY) || features == null || features.isEmpty()) {
            return Float.MAX_VALUE;
        }
        float minDistance = Float.MAX_VALUE;
        for (List<List<Double>> feature : features) {
            float d = distanceToFeature(pointXY, feature);
            if (d < minDistance) {
                minDistance = d;
            }
        }
        return minDistance;
    }

    // Snaps a point to the closest available feature boundary.
    private float[] snapToNearestFeature(float[] pointXY, List<List<List<Double>>> features) {
        if (!isValidXY(pointXY) || features == null || features.isEmpty()) {
            return null;
        }

        float[] bestPoint = null;
        float bestDistance = Float.MAX_VALUE;
        for (List<List<Double>> feature : features) {
            float[] candidate = nearestPointOnFeature(pointXY, feature);
            if (!isValidXY(candidate)) {
                continue;
            }
            float distance = distanceBetween(pointXY, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestPoint = candidate;
            }
        }
        return bestPoint;
    }

    // Measures the distance from a point to one feature shape.
    private float distanceToFeature(float[] pointXY, List<List<Double>> featureLatLon) {
        if (!isValidXY(pointXY) || featureLatLon == null || featureLatLon.isEmpty()) {
            return Float.MAX_VALUE;
        }

        if (featureLatLon.size() == 1) {
            float[] p = latLonToXY(featureLatLon.get(0));
            if (!isValidXY(p)) {
                return Float.MAX_VALUE;
            }
            float dx = pointXY[0] - p[0];
            float dy = pointXY[1] - p[1];
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        float minDistance = Float.MAX_VALUE;
        for (int i = 0; i < featureLatLon.size() - 1; i++) {
            float[] a = latLonToXY(featureLatLon.get(i));
            float[] b = latLonToXY(featureLatLon.get(i + 1));
            if (!isValidXY(a) || !isValidXY(b)) {
                continue;
            }
            float d = distancePointToSegment(pointXY[0], pointXY[1], a[0], a[1], b[0], b[1]);
            if (d < minDistance) {
                minDistance = d;
            }
        }

        if (featureLatLon.size() >= 3) {
            float[] first = latLonToXY(featureLatLon.get(0));
            float[] last = latLonToXY(featureLatLon.get(featureLatLon.size() - 1));
            if (isValidXY(first) && isValidXY(last)) {
                float d = distancePointToSegment(pointXY[0], pointXY[1], last[0], last[1], first[0], first[1]);
                if (d < minDistance) {
                    minDistance = d;
                }
            }
            if (isPointInPolygon(pointXY, featureLatLon)) {
                return 0f;
            }
        }

        return minDistance;
    }

    // Finds the nearest point on one feature to the given point.
    private float[] nearestPointOnFeature(float[] pointXY, List<List<Double>> featureLatLon) {
        if (!isValidXY(pointXY) || featureLatLon == null || featureLatLon.isEmpty()) {
            return null;
        }

        if (featureLatLon.size() == 1) {
            float[] point = latLonToXY(featureLatLon.get(0));
            if (!isValidXY(point)) {
                return null;
            }
            return new float[]{point[0], point[1]};
        }

        if (featureLatLon.size() >= 3 && isPointInPolygon(pointXY, featureLatLon)) {
            return new float[]{pointXY[0], pointXY[1]};
        }

        float[] bestPoint = null;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < featureLatLon.size() - 1; i++) {
            float[] a = latLonToXY(featureLatLon.get(i));
            float[] b = latLonToXY(featureLatLon.get(i + 1));
            float[] candidate = nearestPointOnSegment(pointXY, a, b);
            if (!isValidXY(candidate)) {
                continue;
            }
            float distance = distanceBetween(pointXY, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestPoint = candidate;
            }
        }

        if (featureLatLon.size() >= 3) {
            float[] first = latLonToXY(featureLatLon.get(0));
            float[] last = latLonToXY(featureLatLon.get(featureLatLon.size() - 1));
            float[] candidate = nearestPointOnSegment(pointXY, last, first);
            if (isValidXY(candidate)) {
                float distance = distanceBetween(pointXY, candidate);
                if (distance < bestDistance) {
                    bestPoint = candidate;
                }
            }
        }

        return bestPoint;
    }

    // Checks whether a point lies inside any polygon feature.
    private boolean isPointInAnyFeature(float[] pointXY, List<List<List<Double>>> features) {
        if (!isValidXY(pointXY) || features == null || features.isEmpty()) {
            return false;
        }
        for (List<List<Double>> feature : features) {
            if (feature != null && feature.size() >= 3 && isPointInPolygon(pointXY, feature)) {
                return true;
            }
        }
        return false;
    }

    // Computes the shortest distance from a point to a segment.
    private float distancePointToSegment(float px, float py, float ax, float ay, float bx, float by) {
        float abx = bx - ax;
        float aby = by - ay;
        float abLenSq = abx * abx + aby * aby;
        if (abLenSq < 1e-6f) {
            float dx = px - ax;
            float dy = py - ay;
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        float apx = px - ax;
        float apy = py - ay;
        float t = (apx * abx + apy * aby) / abLenSq;
        t = Math.max(0f, Math.min(1f, t));

        float cx = ax + t * abx;
        float cy = ay + t * aby;
        float dx = px - cx;
        float dy = py - cy;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // Projects a point onto a line segment.
    private float[] nearestPointOnSegment(float[] pointXY, float[] a, float[] b) {
        if (!isValidXY(pointXY) || !isValidXY(a) || !isValidXY(b)) {
            return null;
        }

        float abx = b[0] - a[0];
        float aby = b[1] - a[1];
        float abLenSq = abx * abx + aby * aby;
        if (abLenSq < 1e-6f) {
            return new float[]{a[0], a[1]};
        }

        float apx = pointXY[0] - a[0];
        float apy = pointXY[1] - a[1];
        float t = (apx * abx + apy * aby) / abLenSq;
        t = Math.max(0f, Math.min(1f, t));
        return new float[]{a[0] + t * abx, a[1] + t * aby};
    }

    // Reuses converted coordinates because floor-plan vertices are queried repeatedly.
    private synchronized float[] latLonToXY(List<Double> latLon) {
        if (latLon == null || latLon.size() < 2 || coordinateUtils == null || !coordinateUtils.isOriginSet()) {
            return null;
        }
        ensureOriginCacheFresh();
        float[] cached = pointXYCache.get(latLon);
        if (cached != null && isValidXY(cached)) {
            return cached;
        }
        float[] converted = coordinateUtils.latLonToXY(latLon.get(0), latLon.get(1));
        if (isValidXY(converted)) {
            pointXYCache.put(latLon, converted);
        }
        return converted;
    }

    // Clears cached geometry when the coordinate origin changes.
    private synchronized void ensureOriginCacheFresh() {
        if (coordinateUtils == null || !coordinateUtils.isOriginSet()) {
            pointXYCache.clear();
            cacheOriginLat = Double.NaN;
            cacheOriginLon = Double.NaN;
            return;
        }
        double[] origin = coordinateUtils.getOriginLatLon();
        if (origin == null || origin.length < 2) {
            return;
        }
        if (Double.isNaN(cacheOriginLat)
                || Double.isNaN(cacheOriginLon)
                || Math.abs(origin[0] - cacheOriginLat) > 1e-10
                || Math.abs(origin[1] - cacheOriginLon) > 1e-10) {
            pointXYCache.clear();
            cacheOriginLat = origin[0];
            cacheOriginLon = origin[1];
        }
    }

    // Checks whether an xy array contains usable coordinates.
    private boolean isValidXY(float[] xy) {
        return xy != null
                && xy.length >= 2
                && !Float.isNaN(xy[0])
                && !Float.isNaN(xy[1])
                && !Float.isInfinite(xy[0])
                && !Float.isInfinite(xy[1]);
    }

    // Computes the 2D cross product of two vectors.
    private float cross(float ax, float ay, float bx, float by) {
        return ax * by - ay * bx;
    }

    // Computes the distance between two xy points.
    private float distanceBetween(float[] a, float[] b) {
        if (!isValidXY(a) || !isValidXY(b)) {
            return Float.MAX_VALUE;
        }
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // Uses ray casting to test whether a point is inside a polygon.
    private boolean isPointInPolygon(float[] point, List<List<Double>> polygonLatLon) {
        if (!isValidXY(point) || polygonLatLon == null || polygonLatLon.size() < 3) {
            return false;
        }

        int intersections = 0;
        float px = point[0];
        float py = point[1];

        for (int i = 0; i < polygonLatLon.size(); i++) {
            float[] v1 = latLonToXY(polygonLatLon.get(i));
            float[] v2 = latLonToXY(polygonLatLon.get((i + 1) % polygonLatLon.size()));
            if (!isValidXY(v1) || !isValidXY(v2)) {
                continue;
            }

            if (((v1[1] > py) != (v2[1] > py))
                    && (px < (v2[0] - v1[0]) * (py - v1[1]) / (v2[1] - v1[1]) + v1[0])) {
                intersections++;
            }
        }
        return (intersections % 2) != 0;
    }

    // Limits a value to the given minimum and maximum range.
    private float clamp(float value, float min, float max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
