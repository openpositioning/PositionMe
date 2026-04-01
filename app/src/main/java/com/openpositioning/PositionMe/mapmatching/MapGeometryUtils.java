package com.openpositioning.PositionMe.mapmatching;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

import java.util.List;

/**
 * Provides the core geometric checks required for Map Matching 3.2.
 *
 * In addition to basic wall-crossing and proximity checks, this version also adds:
 * 1. Find the last valid point before hitting a wall along a motion segment
 * 2. Find the nearest anchor point for an indoor feature type (stairs/lift)
 *
 * These utilities are used by the stage-four minimal patch to move map matching
 * from "only rejecting errors" to "actively pulling the pose back to a more reasonable location."
 */
public class MapGeometryUtils {

    // Default proximity threshold (meters)
    // Stairs can use a slightly larger threshold.
    private static final double STAIRS_PROXIMITY_THRESHOLD_METERS = 2.2;

    // Lift uses a slightly tighter threshold.
    private static final double LIFT_PROXIMITY_THRESHOLD_METERS = 1.8;

    private static final int WALL_PROJECTION_BINARY_SEARCH_ITERATIONS = 20;
    private static final double WALL_PROJECTION_SAFETY_RATIO = 0.98;
    // Keep the safe inset small; otherwise floor transitions may introduce obvious lateral jumps.
    private static final double CONNECTOR_SAFE_INSET_METERS = 0.35;
    private static final int CONNECTOR_SAFE_POINT_STEPS = 16;
    private static final double LOCAL_DIRECTION_SEARCH_RADIUS_METERS = 4.0;
    private static final double LOCAL_DIRECTION_MIN_SEGMENT_WEIGHT = 0.25;

    /**
     * Estimate the dominant walkable heading around a point by inspecting nearby wall edges.
     * The dominant travel direction in corridors is usually parallel to nearby wall edges.
     */
    public static double estimateLocalWalkableHeadingRadians(LatLng point,
                                                             FloorplanApiClient.FloorShapes floorShapes,
                                                             double fallbackHeadingRad) {
        if (point == null || floorShapes == null || floorShapes.getFeatures() == null) {
            return fallbackHeadingRad;
        }

        double sumCos2 = 0d;
        double sumSin2 = 0d;
        double totalWeight = 0d;

        for (FloorplanApiClient.MapShapeFeature feature : floorShapes.getFeatures()) {
            if (!"wall".equalsIgnoreCase(feature.getIndoorType())) {
                continue;
            }
            List<List<LatLng>> parts = feature.getParts();
            if (parts == null) continue;
            for (List<LatLng> part : parts) {
                if (part == null || part.size() < 2) continue;
                for (int i = 0; i < part.size(); i++) {
                    LatLng a = part.get(i);
                    LatLng b = part.get((i + 1) % part.size());
                    if (a == null || b == null || samePoint(a, b)) continue;
                    double distance = distancePointToSegmentMeters(point, a, b);
                    if (distance > LOCAL_DIRECTION_SEARCH_RADIUS_METERS) continue;
                    double heading = headingBetween(a, b);
                    double closenessWeight = 1d / Math.max(distance, LOCAL_DIRECTION_MIN_SEGMENT_WEIGHT);
                    double lengthWeight = Math.max(0.30d, distanceMeters(a, b));
                    double weight = closenessWeight * Math.min(lengthWeight, 6.0d);
                    sumCos2 += Math.cos(2d * heading) * weight;
                    sumSin2 += Math.sin(2d * heading) * weight;
                    totalWeight += weight;
                }
            }
        }

        if (totalWeight <= 1e-6d) {
            return fallbackHeadingRad;
        }

        double axisHeading = 0.5d * Math.atan2(sumSin2, sumCos2);
        double alternative = normalizeAngleRadians(axisHeading + Math.PI);
        return angularDifference(axisHeading, fallbackHeadingRad)
                <= angularDifference(alternative, fallbackHeadingRad)
                ? axisHeading
                : alternative;
    }

    /**
     * Checks whether the trajectory from start to end crosses a wall on the current floor.
     */
    public static boolean crossesWall(LatLng start,
                                      LatLng end,
                                      FloorplanApiClient.FloorShapes floorShapes) {
        if (start == null || end == null || floorShapes == null || floorShapes.getFeatures() == null) {
            return false;
        }

        for (FloorplanApiClient.MapShapeFeature feature : floorShapes.getFeatures()) {
            if (!"wall".equalsIgnoreCase(feature.getIndoorType())) {
                continue;
            }

            if (intersectsFeature(start, end, feature)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks whether the current position is near stairs.
     * Stair areas are usually a bit larger, so the threshold is relaxed.
     */
    public static boolean isNearStairs(LatLng point,
                                       FloorplanApiClient.FloorShapes floorShapes) {
        return isNearIndoorType(
                point,
                floorShapes,
                "stairs",
                STAIRS_PROXIMITY_THRESHOLD_METERS
        );
    }

    /**
     * Checks whether the current position is near a lift.
     * Lift areas are usually more compact, so the threshold is slightly tighter.
     */
    public static boolean isNearLift(LatLng point,
                                     FloorplanApiClient.FloorShapes floorShapes) {
        return isNearIndoorType(
                point,
                floorShapes,
                "lift",
                LIFT_PROXIMITY_THRESHOLD_METERS
        );
    }


    /**
     * Checks whether a point is actually inside an indoor feature area.
     *
     * This is stricter than a near check and is used for floor transitions that need strong evidence.
     */
    public static boolean isInsideIndoorType(LatLng point,
                                             FloorplanApiClient.FloorShapes floorShapes,
                                             String indoorType) {
        if (point == null || floorShapes == null || floorShapes.getFeatures() == null) {
            return false;
        }

        for (FloorplanApiClient.MapShapeFeature feature : floorShapes.getFeatures()) {
            if (!indoorType.equalsIgnoreCase(feature.getIndoorType())) {
                continue;
            }

            List<List<LatLng>> parts = feature.getParts();
            if (parts == null) continue;

            for (List<LatLng> part : parts) {
                if (part == null || part.size() < 3) continue;
                if (isPointInPolygon(point, part)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * A more general check for whether a point is near an indoor feature type.
     */
    public static boolean isNearIndoorType(LatLng point,
                                           FloorplanApiClient.FloorShapes floorShapes,
                                           String indoorType,
                                           double thresholdMeters) {
        if (point == null || floorShapes == null || floorShapes.getFeatures() == null) {
            return false;
        }

        for (FloorplanApiClient.MapShapeFeature feature : floorShapes.getFeatures()) {
            if (!indoorType.equalsIgnoreCase(feature.getIndoorType())) {
                continue;
            }

            List<List<LatLng>> parts = feature.getParts();
            if (parts == null) continue;

            for (List<LatLng> part : parts) {
                if (part == null || part.isEmpty()) continue;

                // If this is an area / closed region, being inside also counts as near.
                if (part.size() >= 3 && isPointInPolygon(point, part)) {
                    return true;
                }

                // Otherwise, check the minimum distance from the point to the edge / segment.
                if (isPointNearPolyline(point, part, thresholdMeters)) {
                    return true;
                }
            }
        }

        return false;
    }


    /**
     * Returns true when the point falls inside a wall polygon/ring on the current floor.
     */
    public static boolean isInsideWall(LatLng point,
                                       FloorplanApiClient.FloorShapes floorShapes) {
        if (point == null || floorShapes == null || floorShapes.getFeatures() == null) {
            return false;
        }

        for (FloorplanApiClient.MapShapeFeature feature : floorShapes.getFeatures()) {
            if (!"wall".equalsIgnoreCase(feature.getIndoorType())) {
                continue;
            }

            List<List<LatLng>> parts = feature.getParts();
            if (parts == null) continue;

            for (List<LatLng> part : parts) {
                if (part == null || part.size() < 3) continue;
                if (isPointInPolygon(point, part)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Distance from a point to the nearest wall boundary on the active floor.
     * Returns Double.MAX_VALUE when no wall geometry is available.
     */
    public static double distanceToNearestWallMeters(LatLng point,
                                                     FloorplanApiClient.FloorShapes floorShapes) {
        if (point == null || floorShapes == null || floorShapes.getFeatures() == null) {
            return Double.MAX_VALUE;
        }

        double bestDistance = Double.MAX_VALUE;
        for (FloorplanApiClient.MapShapeFeature feature : floorShapes.getFeatures()) {
            if (!"wall".equalsIgnoreCase(feature.getIndoorType())) {
                continue;
            }

            List<List<LatLng>> parts = feature.getParts();
            if (parts == null) continue;

            for (List<LatLng> part : parts) {
                if (part == null || part.size() < 2) continue;
                if (part.size() >= 3 && isPointInPolygon(point, part)) {
                    return 0.0;
                }
                double distance = distancePointToPolygonBoundaryMeters(point, part);
                if (distance < bestDistance) {
                    bestDistance = distance;
                }
            }
        }

        return bestDistance;
    }

    /**
     * When a trajectory crosses a wall, find the last valid point before collision along start->end.
     *
     * This is smoother than simply reverting to the previous pose and better matches the stage-four active-correction goal.
     */
    public static LatLng findFarthestValidPointBeforeWall(LatLng start,
                                                          LatLng end,
                                                          FloorplanApiClient.FloorShapes floorShapes) {
        if (start == null || end == null || floorShapes == null) {
            return start;
        }
        if (!crossesWall(start, end, floorShapes)) {
            return end;
        }

        double low = 0.0;
        double high = 1.0;
        LatLng best = start;

        for (int i = 0; i < WALL_PROJECTION_BINARY_SEARCH_ITERATIONS; i++) {
            double mid = (low + high) / 2.0;
            LatLng midPoint = interpolate(start, end, mid);
            if (crossesWall(start, midPoint, floorShapes)) {
                high = mid;
            } else {
                best = midPoint;
                low = mid;
            }
        }

        // Step slightly back toward start to avoid landing on the boundary and oscillating on the next frame.
        double safeRatio = Math.max(0.0, low * WALL_PROJECTION_SAFETY_RATIO);
        return interpolate(start, end, safeRatio);
    }

    /**
     * Find the nearest safe interior landing point from point to the specified indoorType.
     *
     * Unlike a simple centroid, this point stays as close as possible to the entrance side near the current candidate point,
     * while still moving slightly inside the polygon to avoid landing on the boundary or next to a wall after a floor change.
     */
    public static LatLng findNearestSafeInteriorPointOnIndoorType(LatLng point,
                                                                  FloorplanApiClient.FloorShapes floorShapes,
                                                                  String indoorType) {
        if (point == null || floorShapes == null || floorShapes.getFeatures() == null) {
            return null;
        }

        double bestDistance = Double.MAX_VALUE;
        LatLng bestPoint = null;

        for (FloorplanApiClient.MapShapeFeature feature : floorShapes.getFeatures()) {
            if (!indoorType.equalsIgnoreCase(feature.getIndoorType())) {
                continue;
            }

            List<List<LatLng>> parts = feature.getParts();
            if (parts == null) continue;

            for (List<LatLng> part : parts) {
                if (part == null || part.size() < 3) continue;

                LatLng safePoint = findSafeInteriorPoint(point, part);
                if (safePoint == null) continue;

                double distance = distanceMeters(point, safePoint);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestPoint = safePoint;
                }
            }
        }

        return bestPoint;
    }

    /**
     * Find the nearest interior landing point from point to the specified indoorType.
     *
     * Unlike findNearestPointOnIndoorType, this prefers returning an interior centroid of the polygon,
     * which is suitable for placing the landing point after a floor transition and avoids landing on the boundary or next to a wall.
     */
    public static LatLng findNearestIndoorTypeCentroid(LatLng point,
                                                       FloorplanApiClient.FloorShapes floorShapes,
                                                       String indoorType) {
        if (point == null || floorShapes == null || floorShapes.getFeatures() == null) {
            return null;
        }

        double bestDistance = Double.MAX_VALUE;
        LatLng bestPoint = null;

        for (FloorplanApiClient.MapShapeFeature feature : floorShapes.getFeatures()) {
            if (!indoorType.equalsIgnoreCase(feature.getIndoorType())) {
                continue;
            }

            List<List<LatLng>> parts = feature.getParts();
            if (parts == null) continue;

            for (List<LatLng> part : parts) {
                if (part == null || part.size() < 3) continue;

                LatLng centroid = polygonCentroid(part);
                if (centroid == null) continue;
                if (!isPointInPolygon(centroid, part)) {
                    centroid = averagePoint(part);
                }
                if (centroid == null || !isPointInPolygon(centroid, part)) {
                    continue;
                }

                double distance = distanceMeters(point, centroid);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestPoint = centroid;
                }
            }
        }

        return bestPoint;
    }

    /**
     * Find the nearest anchor point from point to the specified indoorType.
     *
     * For stairs/lift, this point acts as a more reasonable position during a floor transition.
     */
    public static LatLng findNearestPointOnIndoorType(LatLng point,
                                                      FloorplanApiClient.FloorShapes floorShapes,
                                                      String indoorType) {
        if (point == null || floorShapes == null || floorShapes.getFeatures() == null) {
            return null;
        }

        double bestDistance = Double.MAX_VALUE;
        LatLng bestPoint = null;

        for (FloorplanApiClient.MapShapeFeature feature : floorShapes.getFeatures()) {
            if (!indoorType.equalsIgnoreCase(feature.getIndoorType())) {
                continue;
            }

            List<List<LatLng>> parts = feature.getParts();
            if (parts == null) continue;

            for (List<LatLng> part : parts) {
                if (part == null || part.isEmpty()) continue;

                if (part.size() >= 3 && isPointInPolygon(point, part)) {
                    LatLng safeInterior = findSafeInteriorPoint(point, part);
                    return safeInterior != null ? safeInterior : point;
                }

                int limit = part.size() - 1;
                for (int i = 0; i < limit; i++) {
                    LatLng nearest = projectPointToSegment(point, part.get(i), part.get(i + 1));
                    double distance = distanceMeters(point, nearest);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestPoint = nearest;
                    }
                }

                if (part.size() >= 3) {
                    LatLng nearest = projectPointToSegment(point, part.get(part.size() - 1), part.get(0));
                    double distance = distanceMeters(point, nearest);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestPoint = nearest;
                    }
                }
            }
        }

        return bestPoint;
    }

    // =========================================================
    // Internal helper methods
    // =========================================================

    /**
     * Checks whether a trajectory segment intersects the boundary / segment of a feature.
     */
    private static boolean intersectsFeature(LatLng start,
                                             LatLng end,
                                             FloorplanApiClient.MapShapeFeature feature) {
        List<List<LatLng>> parts = feature.getParts();
        if (parts == null) return false;

        for (List<LatLng> part : parts) {
            if (part == null || part.size() < 2) continue;

            for (int i = 0; i < part.size() - 1; i++) {
                LatLng a = part.get(i);
                LatLng b = part.get(i + 1);

                if (segmentsIntersect(start, end, a, b)) {
                    return true;
                }
            }

            // For a polygon ring, also check the closing edge between the last and first points.
            if (part.size() >= 3) {
                LatLng last = part.get(part.size() - 1);
                LatLng first = part.get(0);
                if (segmentsIntersect(start, end, last, first)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Checks whether a point is near a polyline / polygon boundary.
     */
    private static boolean isPointNearPolyline(LatLng point,
                                               List<LatLng> polyline,
                                               double thresholdMeters) {
        if (polyline == null || polyline.size() < 2) return false;

        for (int i = 0; i < polyline.size() - 1; i++) {
            LatLng a = polyline.get(i);
            LatLng b = polyline.get(i + 1);

            double distance = distancePointToSegmentMeters(point, a, b);
            if (distance <= thresholdMeters) {
                return true;
            }
        }

        // If this behaves like a polygon ring, also check the closing edge.
        if (polyline.size() >= 3) {
            double distance = distancePointToSegmentMeters(
                    point,
                    polyline.get(polyline.size() - 1),
                    polyline.get(0)
            );
            return distance <= thresholdMeters;
        }

        return false;
    }

    /**
     * Checks whether a point is inside a polygon.
     * Uses a simple ray-casting method.
     */
    private static boolean isPointInPolygon(LatLng point, List<LatLng> polygon) {
        if (point == null || polygon == null || polygon.size() < 3) return false;

        boolean inside = false;
        double x = point.longitude;
        double y = point.latitude;

        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            double xi = polygon.get(i).longitude;
            double yi = polygon.get(i).latitude;
            double xj = polygon.get(j).longitude;
            double yj = polygon.get(j).latitude;

            boolean intersect = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / ((yj - yi) + 1e-12) + xi);

            if (intersect) inside = !inside;
        }

        return inside;
    }

    /**
     * Checks whether two segments intersect.
     * A planar approximation is used here, which is sufficient for small indoor areas.
     */
    private static boolean segmentsIntersect(LatLng p1, LatLng p2, LatLng q1, LatLng q2) {
        int o1 = orientation(p1, p2, q1);
        int o2 = orientation(p1, p2, q2);
        int o3 = orientation(q1, q2, p1);
        int o4 = orientation(q1, q2, p2);

        if (o1 != o2 && o3 != o4) return true;

        // Special case: collinear segments
        if (o1 == 0 && onSegment(p1, q1, p2)) return true;
        if (o2 == 0 && onSegment(p1, q2, p2)) return true;
        if (o3 == 0 && onSegment(q1, p1, q2)) return true;
        if (o4 == 0 && onSegment(q1, p2, q2)) return true;

        return false;
    }

    private static int orientation(LatLng a, LatLng b, LatLng c) {
        double value = (b.latitude - a.latitude) * (c.longitude - b.longitude)
                - (b.longitude - a.longitude) * (c.latitude - b.latitude);

        if (Math.abs(value) < 1e-12) return 0;
        return (value > 0) ? 1 : 2;
    }

    private static boolean onSegment(LatLng a, LatLng b, LatLng c) {
        return b.longitude <= Math.max(a.longitude, c.longitude)
                && b.longitude >= Math.min(a.longitude, c.longitude)
                && b.latitude <= Math.max(a.latitude, c.latitude)
                && b.latitude >= Math.min(a.latitude, c.latitude);
    }

    /**
     * Computes the shortest distance from a point to a segment (meters).
     * Uses a local planar approximation (lat/lng converted to meters), which is sufficient for small indoor areas.
     */
    private static double distancePointToSegmentMeters(LatLng p, LatLng a, LatLng b) {
        LatLng nearest = projectPointToSegment(p, a, b);
        return distanceMeters(p, nearest);
    }

    private static LatLng findSafeInteriorPoint(LatLng referencePoint, List<LatLng> polygon) {
        if (referencePoint == null || polygon == null || polygon.size() < 3) {
            return null;
        }

        LatLng centroid = polygonCentroid(polygon);
        if (centroid == null || !isPointInPolygon(centroid, polygon)) {
            centroid = averagePoint(polygon);
        }
        if (centroid == null || !isPointInPolygon(centroid, polygon)) {
            return null;
        }

        LatLng startPoint = isPointInPolygon(referencePoint, polygon)
                ? referencePoint
                : findNearestPointOnPolygonBoundary(referencePoint, polygon);
        if (startPoint == null) {
            startPoint = centroid;
        }

        double currentBoundaryDistance = distancePointToPolygonBoundaryMeters(startPoint, polygon);
        if (isPointInPolygon(startPoint, polygon) && currentBoundaryDistance >= CONNECTOR_SAFE_INSET_METERS) {
            return startPoint;
        }

        LatLng bestPoint = centroid;
        double bestBoundaryDistance = distancePointToPolygonBoundaryMeters(centroid, polygon);

        for (int i = 1; i <= CONNECTOR_SAFE_POINT_STEPS; i++) {
            double ratio = i / (double) CONNECTOR_SAFE_POINT_STEPS;
            LatLng candidate = interpolate(startPoint, centroid, ratio);
            if (!isPointInPolygon(candidate, polygon)) {
                continue;
            }

            double boundaryDistance = distancePointToPolygonBoundaryMeters(candidate, polygon);
            if (boundaryDistance > bestBoundaryDistance) {
                bestBoundaryDistance = boundaryDistance;
                bestPoint = candidate;
            }
            if (boundaryDistance >= CONNECTOR_SAFE_INSET_METERS) {
                return candidate;
            }
        }

        return bestPoint;
    }

    private static LatLng findNearestPointOnPolygonBoundary(LatLng point, List<LatLng> polygon) {
        if (point == null || polygon == null || polygon.size() < 2) {
            return null;
        }

        double bestDistance = Double.MAX_VALUE;
        LatLng bestPoint = null;

        for (int i = 0; i < polygon.size(); i++) {
            LatLng a = polygon.get(i);
            LatLng b = polygon.get((i + 1) % polygon.size());
            LatLng projected = projectPointToSegment(point, a, b);
            double distance = distanceMeters(point, projected);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestPoint = projected;
            }
        }

        return bestPoint;
    }

    private static double distancePointToPolygonBoundaryMeters(LatLng point, List<LatLng> polygon) {
        if (point == null || polygon == null || polygon.size() < 2) {
            return Double.MAX_VALUE;
        }

        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < polygon.size(); i++) {
            LatLng a = polygon.get(i);
            LatLng b = polygon.get((i + 1) % polygon.size());
            double distance = distancePointToSegmentMeters(point, a, b);
            if (distance < bestDistance) {
                bestDistance = distance;
            }
        }
        return bestDistance;
    }

    private static LatLng projectPointToSegment(LatLng p, LatLng a, LatLng b) {
        double[] axy = toLocalMeters(a, p);
        double[] bxy = toLocalMeters(b, p);

        double ax = axy[0], ay = axy[1];
        double bx = bxy[0], by = bxy[1];
        double dx = bx - ax;
        double dy = by - ay;

        if (dx == 0 && dy == 0) {
            return a;
        }

        double t = (-(ax * dx + ay * dy)) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));

        double closestX = ax + t * dx;
        double closestY = ay + t * dy;
        return fromLocalMeters(closestX, closestY, p);
    }

    /**
     * Euclidean distance in local tangent plane (meters).
     */
    public static double distanceMetersPublic(LatLng a, LatLng b) {
        return distanceMeters(a, b);
    }

    /**
     * Move a point by east/north offsets in meters using a local tangent plane approximation.
     */
    public static LatLng offsetPointByMeters(LatLng reference,
                                             double eastMeters,
                                             double northMeters) {
        if (reference == null) {
            return null;
        }
        return fromLocalMeters(eastMeters, northMeters, reference);
    }

    private static double distanceMeters(LatLng a, LatLng b) {
        double[] bxy = toLocalMeters(b, a);
        return Math.hypot(bxy[0], bxy[1]);
    }

    private static LatLng polygonCentroid(List<LatLng> polygon) {
        if (polygon == null || polygon.size() < 3) return null;

        double areaTwice = 0.0;
        double cx = 0.0;
        double cy = 0.0;

        for (int i = 0; i < polygon.size(); i++) {
            LatLng a = polygon.get(i);
            LatLng b = polygon.get((i + 1) % polygon.size());
            double cross = a.longitude * b.latitude - b.longitude * a.latitude;
            areaTwice += cross;
            cx += (a.longitude + b.longitude) * cross;
            cy += (a.latitude + b.latitude) * cross;
        }

        if (Math.abs(areaTwice) < 1e-12) {
            return averagePoint(polygon);
        }

        double scale = 1.0 / (3.0 * areaTwice);
        return new LatLng(cy * scale, cx * scale);
    }

    private static LatLng averagePoint(List<LatLng> polygon) {
        if (polygon == null || polygon.isEmpty()) return null;
        double lat = 0.0;
        double lng = 0.0;
        int count = 0;
        for (LatLng point : polygon) {
            if (point == null) continue;
            lat += point.latitude;
            lng += point.longitude;
            count += 1;
        }
        if (count == 0) return null;
        return new LatLng(lat / count, lng / count);
    }

    private static LatLng interpolate(LatLng start, LatLng end, double ratio) {
        double clamped = Math.max(0.0, Math.min(1.0, ratio));
        return new LatLng(
                start.latitude + (end.latitude - start.latitude) * clamped,
                start.longitude + (end.longitude - start.longitude) * clamped
        );
    }

    private static double headingBetween(LatLng from, LatLng to) {
        double[] xy = toLocalMeters(to, from);
        if (Math.abs(xy[0]) < 1e-9 && Math.abs(xy[1]) < 1e-9) {
            return 0d;
        }
        return Math.atan2(xy[0], xy[1]);
    }

    private static boolean samePoint(LatLng a, LatLng b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Math.abs(a.latitude - b.latitude) < 1e-12
                && Math.abs(a.longitude - b.longitude) < 1e-12;
    }

    private static double normalizeAngleRadians(double angle) {
        double value = angle;
        while (value <= -Math.PI) value += 2d * Math.PI;
        while (value > Math.PI) value -= 2d * Math.PI;
        return value;
    }

    private static double angularDifference(double a, double b) {
        double diff = Math.abs(normalizeAngleRadians(a - b));
        return diff > Math.PI ? (2d * Math.PI - diff) : diff;
    }

    /**
     * Converts lat/lng to local meter coordinates with reference as the origin.
     * Returns [xMeters, yMeters]
     */
    private static double[] toLocalMeters(LatLng point, LatLng reference) {
        double latRad = Math.toRadians(reference.latitude);
        double metersPerDegLat = 111320.0;
        double metersPerDegLon = 111320.0 * Math.cos(latRad);

        double x = (point.longitude - reference.longitude) * metersPerDegLon;
        double y = (point.latitude - reference.latitude) * metersPerDegLat;

        return new double[]{x, y};
    }

    private static LatLng fromLocalMeters(double xMeters, double yMeters, LatLng reference) {
        double latRad = Math.toRadians(reference.latitude);
        double metersPerDegLat = 111320.0;
        double metersPerDegLon = 111320.0 * Math.cos(latRad);

        double latitude = reference.latitude + (yMeters / metersPerDegLat);
        double longitude = reference.longitude + (xMeters / Math.max(metersPerDegLon, 1e-9));
        return new LatLng(latitude, longitude);
    }
}