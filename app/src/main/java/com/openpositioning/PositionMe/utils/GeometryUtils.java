package com.openpositioning.PositionMe.utils;

import com.google.android.gms.maps.model.LatLng;

import java.util.List;

/** Geometry helpers used by map matching and indoor polygon checks. */
public class GeometryUtils {

    private static final double EPSILON = 1e-9;

    /**
     * Returns whether a point lies inside a polygon using ray casting.
     *
     * @param point point to test
     * @param polygon polygon vertices
     * @return {@code true} if the point lies inside the polygon
     */
    public static boolean isPointInPolygon(LatLng point, List<LatLng> polygon) {
        if (polygon == null || polygon.isEmpty()) {
            return false;
        }

        int intersections = 0;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            LatLng p1 = polygon.get(i);
            LatLng p2 = polygon.get(j);

            if (point.longitude == p1.longitude && point.latitude == p1.latitude) {
                return true;
            }

            if (((p1.latitude > point.latitude) != (p2.latitude > point.latitude))
                    && (point.longitude
                    < (p2.longitude - p1.longitude) * (point.latitude - p1.latitude)
                    / (p2.latitude - p1.latitude) + p1.longitude)) {
                intersections++;
            }
        }
        return intersections % 2 == 1;
    }

    /**
     * Returns whether two line segments intersect.
     *
     * @param a first endpoint of the first segment
     * @param b second endpoint of the first segment
     * @param c first endpoint of the second segment
     * @param d second endpoint of the second segment
     * @return {@code true} if the segments intersect
     */
    public static boolean doSegmentsIntersect(LatLng a, LatLng b, LatLng c, LatLng d) {
        double d1 = direction(c, d, a);
        double d2 = direction(c, d, b);
        double d3 = direction(a, b, c);
        double d4 = direction(a, b, d);

        if (((d1 > EPSILON && d2 < -EPSILON) || (d1 < -EPSILON && d2 > EPSILON))
                && ((d3 > EPSILON && d4 < -EPSILON) || (d3 < -EPSILON && d4 > EPSILON))) {
            return true;
        }

        if (Math.abs(d1) <= EPSILON && onSegment(c, d, a)) {
            return true;
        }
        if (Math.abs(d2) <= EPSILON && onSegment(c, d, b)) {
            return true;
        }
        if (Math.abs(d3) <= EPSILON && onSegment(a, b, c)) {
            return true;
        }
        if (Math.abs(d4) <= EPSILON && onSegment(a, b, d)) {
            return true;
        }

        return false;
    }

    private static double direction(LatLng a, LatLng b, LatLng c) {
        return (c.longitude - a.longitude) * (b.latitude - a.latitude)
                - (b.longitude - a.longitude) * (c.latitude - a.latitude);
    }

    private static boolean onSegment(LatLng a, LatLng b, LatLng p) {
        return p.longitude <= Math.max(a.longitude, b.longitude) + EPSILON
                && p.longitude >= Math.min(a.longitude, b.longitude) - EPSILON
                && p.latitude <= Math.max(a.latitude, b.latitude) + EPSILON
                && p.latitude >= Math.min(a.latitude, b.latitude) - EPSILON;
    }

    /**
     * Returns the planar distance between two geographic points in meters.
     *
     * @param a first point
     * @param b second point
     * @return approximate distance in meters
     */
    public static double distanceMeters(LatLng a, LatLng b) {
        double radius = 6378137.0;
        double lat1 = Math.toRadians(a.latitude);
        double lat2 = Math.toRadians(b.latitude);
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(b.longitude - a.longitude);
        double x = dLng * Math.cos((lat1 + lat2) / 2.0);
        double y = dLat;
        return Math.sqrt(x * x + y * y) * radius;
    }

    /**
     * Returns the minimum distance between a point and a polygon in meters.
     *
     * @param point point to test
     * @param polygon polygon vertices
     * @return distance in meters, or {@link Double#POSITIVE_INFINITY} for an empty polygon
     */
    public static double distancePointToPolygonMeters(LatLng point, List<LatLng> polygon) {
        if (polygon == null || polygon.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        if (polygon.size() >= 3 && isPointInPolygon(point, polygon)) {
            return 0.0;
        }

        double minDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < polygon.size(); i++) {
            LatLng a = polygon.get(i);
            LatLng b = polygon.get((i + 1) % polygon.size());
            minDistance = Math.min(minDistance, distancePointToSegmentMeters(point, a, b));
        }
        return minDistance;
    }

    private static double distancePointToSegmentMeters(LatLng point, LatLng a, LatLng b) {
        double latScale = 111320.0;
        double lngScale =
                Math.cos(Math.toRadians((a.latitude + b.latitude + point.latitude) / 3.0)) * 111320.0;

        double px = point.longitude * lngScale;
        double py = point.latitude * latScale;
        double ax = a.longitude * lngScale;
        double ay = a.latitude * latScale;
        double bx = b.longitude * lngScale;
        double by = b.latitude * latScale;

        double abx = bx - ax;
        double aby = by - ay;
        double apx = px - ax;
        double apy = py - ay;
        double lengthSquared = abx * abx + aby * aby;

        double t = lengthSquared <= EPSILON ? 0.0 : (apx * abx + apy * aby) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));

        double closestX = ax + t * abx;
        double closestY = ay + t * aby;
        double dx = px - closestX;
        double dy = py - closestY;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
