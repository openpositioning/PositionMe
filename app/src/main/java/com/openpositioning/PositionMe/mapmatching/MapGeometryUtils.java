package com.openpositioning.PositionMe.mapmatching;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

import java.util.List;

/**
 * 提供 3.2 Map Matching 所需的基础几何判断工具。
 *
 * 当前第一版实现：
 * 1. 判断一段轨迹是否与 wall 要素相交
 * 2. 判断当前位置是否靠近 stairs
 * 3. 判断当前位置是否靠近 lift
 *
 * 注意：
 * 这里先实现“够用、稳定、易懂”的版本，
 * 后面如果需要再继续增强。
 */
public class MapGeometryUtils {

    // 默认邻近阈值（米）
    // 楼梯邻近阈值可以稍大一点
    private static final double STAIRS_PROXIMITY_THRESHOLD_METERS = 2.2;

    // 电梯邻近阈值相对更紧一些
    private static final double LIFT_PROXIMITY_THRESHOLD_METERS = 1.8;
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
     */
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
        double[] pxy = toLocalMeters(p, p);
        double[] axy = toLocalMeters(a, p);
        double[] bxy = toLocalMeters(b, p);

        double px = pxy[0], py = pxy[1];
        double ax = axy[0], ay = axy[1];
        double bx = bxy[0], by = bxy[1];

        double dx = bx - ax;
        double dy = by - ay;

        if (dx == 0 && dy == 0) {
            return Math.hypot(px - ax, py - ay);
        }

        double t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));

        double closestX = ax + t * dx;
        double closestY = ay + t * dy;

        return Math.hypot(px - closestX, py - closestY);
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
}