package com.openpositioning.PositionMe.mapmatching;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

import java.util.List;

/**
 * 提供 3.2 Map Matching 所需的基础几何判断工具。
 *
 * 当前版本除了基础的穿墙 / 邻近判断外，还补充了：
 * 1. 沿着一段运动轨迹，找到“撞墙前最后一个合法点”
 * 2. 找到某类 indoor feature（stairs/lift）最近的锚点位置
 *
 * 这些工具会被阶段四的最小补丁使用，用来把 map matching 从
 * “只会拒绝错误”推进到“能主动拉回到更合理的位置”。
 */
public class MapGeometryUtils {

    // 默认邻近阈值（米）
    // 楼梯邻近阈值可以稍大一点
    private static final double STAIRS_PROXIMITY_THRESHOLD_METERS = 2.2;

    // 电梯邻近阈值相对更紧一些
    private static final double LIFT_PROXIMITY_THRESHOLD_METERS = 1.8;

    private static final int WALL_PROJECTION_BINARY_SEARCH_ITERATIONS = 20;
    private static final double WALL_PROJECTION_SAFETY_RATIO = 0.98;
    // 安全内缩值不要太大，否则切层时会出现明显的横向/纵向跳点。
    private static final double CONNECTOR_SAFE_INSET_METERS = 0.35;
    private static final int CONNECTOR_SAFE_POINT_STEPS = 16;

    /**
     * 判断从 start 到 end 的轨迹是否穿过当前楼层中的 wall。
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
     * 判断当前位置是否靠近楼梯。
     * 楼梯区域通常范围稍大，因此阈值放宽一些。
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
     * 判断当前位置是否靠近电梯。
     * 电梯区域通常更集中，因此阈值稍微收紧。
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
     * 判断点是否真正位于某类 indoor feature 的面域内部。
     *
     * 这个判断比 near 更严格，用于楼层切换这种需要强证据的场景。
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
     * 更通用的“是否靠近某类 indoor feature”判断。
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

                // 如果是面/封闭区域，点在里面也算 near
                if (part.size() >= 3 && isPointInPolygon(point, part)) {
                    return true;
                }

                // 否则判断点到边/线段的最小距离
                if (isPointNearPolyline(point, part, thresholdMeters)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 当一段轨迹穿墙时，沿着 start->end 的方向找到“撞墙前最后一个合法点”。
     *
     * 这比简单回退到 previous pose 更平滑，也更符合阶段四“主动修正”的目标。
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

        // 稍微往 start 方向退一点，避免贴在边界上导致下一帧继续抖动。
        double safeRatio = Math.max(0.0, low * WALL_PROJECTION_SAFETY_RATIO);
        return interpolate(start, end, safeRatio);
    }

    /**
     * 寻找 point 到指定 indoorType 最近的“安全内部落点”。
     *
     * 与简单质心不同，这个点会尽量靠近用户当前候选点所对应的入口侧，
     * 但同时向 polygon 内部缩进一小段距离，避免切层后落在边界/墙边。
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
     * 寻找 point 到指定 indoorType 最近的“内部落点”。
     *
     * 和 findNearestPointOnIndoorType 不同，这里优先返回 polygon 的内部质心，
     * 适合楼层切换后的落点安置，避免刚切层就落在边界/墙边。
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
     * 寻找 point 到指定 indoorType 最近的锚点。
     *
     * 对 stairs/lift 来说，这个点会作为楼层切换时的“更合理位置”。
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
    // 内部工具方法
    // =========================================================

    /**
     * 判断轨迹线段是否与某个 feature 的边界/线段相交。
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

            // 如果是 polygon ring，最后一个点和第一个点也需要闭合判断
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
     * 判断一个点是否靠近一条 polyline / polygon 边界。
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

        // 如果像 polygon ring，也检查闭合边
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
     * 点是否在 polygon 内。
     * 采用简单射线法。
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
     * 判断两条线段是否相交。
     * 这里采用平面近似，对室内小范围足够。
     */
    private static boolean segmentsIntersect(LatLng p1, LatLng p2, LatLng q1, LatLng q2) {
        int o1 = orientation(p1, p2, q1);
        int o2 = orientation(p1, p2, q2);
        int o3 = orientation(q1, q2, p1);
        int o4 = orientation(q1, q2, p2);

        if (o1 != o2 && o3 != o4) return true;

        // 共线特殊情况
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
     * 计算点到线段的最短距离（米）。
     * 使用局部平面近似（经纬度转米），对室内小范围足够。
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

    /**
     * 把经纬度转换为以 reference 为原点的局部米坐标。
     * 返回 [xMeters, yMeters]
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
