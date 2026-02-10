package com.openpositioning.PositionMe.utils;

import com.google.android.gms.maps.model.LatLng;
import java.util.List;

/**
 * GeometryUtils - Utility class for geometric calculations
 * Used for indoor navigation constraint checking (wall collision, boundary detection, etc.)
 */
public class GeometryUtils {

    /**
     * Check if a point is inside a polygon using ray casting algorithm
     */
    public static boolean isPointInPolygon(LatLng point, List<LatLng> polygon) {
        if (polygon == null || polygon.size() < 3) return false;
        
        boolean inside = false;
        int j = polygon.size() - 1;
        
        for (int i = 0; i < polygon.size(); i++) {
            LatLng pi = polygon.get(i);
            LatLng pj = polygon.get(j);
            
            if ((pi.longitude > point.longitude) != (pj.longitude > point.longitude) &&
                (point.latitude < (pj.latitude - pi.latitude) * (point.longitude - pi.longitude) / 
                 (pj.longitude - pi.longitude) + pi.latitude)) {
                inside = !inside;
            }
            j = i;
        }
        return inside;
    }

    /**
     * Calculate distance between two LatLng points in meters (Haversine formula)
     */
    public static double distanceBetween(LatLng p1, LatLng p2) {
        final double R = 6371000; // Earth radius in meters
        double lat1 = Math.toRadians(p1.latitude);
        double lat2 = Math.toRadians(p2.latitude);
        double dLat = Math.toRadians(p2.latitude - p1.latitude);
        double dLon = Math.toRadians(p2.longitude - p1.longitude);
        
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(lat1) * Math.cos(lat2) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        
        return R * c;
    }

    /**
     * Check if a line segment (from -> to) crosses any wall line segment
     * Returns true if movement would cross a wall
     */
    public static boolean crossesWall(LatLng from, LatLng to, List<List<LatLng>> walls) {
        if (walls == null || walls.isEmpty()) return false;
        
        for (List<LatLng> wall : walls) {
            if (wall.size() < 2) continue;
            
            // Check each segment of the wall polyline
            for (int i = 0; i < wall.size() - 1; i++) {
                if (lineSegmentsIntersect(from, to, wall.get(i), wall.get(i + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if two line segments intersect
     */
    private static boolean lineSegmentsIntersect(LatLng p1, LatLng p2, LatLng p3, LatLng p4) {
        double d1 = direction(p3, p4, p1);
        double d2 = direction(p3, p4, p2);
        double d3 = direction(p1, p2, p3);
        double d4 = direction(p1, p2, p4);
        
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        
        // Check for collinear cases
        if (d1 == 0 && onSegment(p3, p1, p4)) return true;
        if (d2 == 0 && onSegment(p3, p2, p4)) return true;
        if (d3 == 0 && onSegment(p1, p3, p2)) return true;
        if (d4 == 0 && onSegment(p1, p4, p2)) return true;
        
        return false;
    }

    /**
     * Calculate direction (cross product)
     */
    private static double direction(LatLng p1, LatLng p2, LatLng p3) {
        return (p3.latitude - p1.latitude) * (p2.longitude - p1.longitude) -
               (p2.latitude - p1.latitude) * (p3.longitude - p1.longitude);
    }

    /**
     * Check if point q lies on segment pr
     */
    private static boolean onSegment(LatLng p, LatLng q, LatLng r) {
        return q.latitude <= Math.max(p.latitude, r.latitude) &&
               q.latitude >= Math.min(p.latitude, r.latitude) &&
               q.longitude <= Math.max(p.longitude, r.longitude) &&
               q.longitude >= Math.min(p.longitude, r.longitude);
    }

    /**
     * Find the closest valid point inside a polygon boundary
     * Used when detected position is outside building
     */
    public static LatLng constrainToPolygon(LatLng point, List<LatLng> polygon) {
        if (isPointInPolygon(point, polygon)) {
            return point; // Already inside
        }
        
        // Find closest point on polygon perimeter
        LatLng closest = null;
        double minDistance = Double.MAX_VALUE;
        
        for (int i = 0; i < polygon.size(); i++) {
            LatLng p1 = polygon.get(i);
            LatLng p2 = polygon.get((i + 1) % polygon.size());
            LatLng nearestOnSegment = closestPointOnSegment(point, p1, p2);
            
            double dist = distanceBetween(point, nearestOnSegment);
            if (dist < minDistance) {
                minDistance = dist;
                closest = nearestOnSegment;
            }
        }
        
        // Move slightly inward from boundary (0.5 meters)
        if (closest != null) {
            LatLng center = getPolygonCenter(polygon);
            double dx = (center.latitude - closest.latitude) * 0.00001; // ~1 meter
            double dy = (center.longitude - closest.longitude) * 0.00001;
            return new LatLng(closest.latitude + dx, closest.longitude + dy);
        }
        
        return point; // Fallback
    }

    /**
     * Find closest point on a line segment to a given point
     */
    private static LatLng closestPointOnSegment(LatLng point, LatLng segStart, LatLng segEnd) {
        double dx = segEnd.latitude - segStart.latitude;
        double dy = segEnd.longitude - segStart.longitude;
        
        if (dx == 0 && dy == 0) return segStart;
        
        double t = ((point.latitude - segStart.latitude) * dx + 
                   (point.longitude - segStart.longitude) * dy) / (dx * dx + dy * dy);
        
        t = Math.max(0, Math.min(1, t)); // Clamp to [0,1]
        
        return new LatLng(
            segStart.latitude + t * dx,
            segStart.longitude + t * dy
        );
    }

    /**
     * Calculate polygon center (centroid)
     */
    private static LatLng getPolygonCenter(List<LatLng> polygon) {
        double sumLat = 0, sumLon = 0;
        for (LatLng p : polygon) {
            sumLat += p.latitude;
            sumLon += p.longitude;
        }
        return new LatLng(sumLat / polygon.size(), sumLon / polygon.size());
    }

    /**
     * Smooth trajectory using exponential moving average
     * alpha = smoothing factor (0-1), higher = less smoothing
     */
    public static LatLng smoothPosition(LatLng newPos, LatLng prevPos, double alpha) {
        if (prevPos == null) return newPos;
        
        double smoothLat = alpha * newPos.latitude + (1 - alpha) * prevPos.latitude;
        double smoothLon = alpha * newPos.longitude + (1 - alpha) * prevPos.longitude;
        
        return new LatLng(smoothLat, smoothLon);
    }

    /**
     * Detect if position jump is unrealistic (teleportation)
     * maxSpeed in meters/second
     */
    public static boolean isUnrealisticJump(LatLng from, LatLng to, long deltaTimeMs, double maxSpeed) {
        if (from == null || to == null || deltaTimeMs <= 0) return false;
        
        double distance = distanceBetween(from, to);
        double speed = distance / (deltaTimeMs / 1000.0); // m/s
        
        return speed > maxSpeed; // Typically 2-3 m/s for walking
    }
}
