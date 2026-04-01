package com.openpositioning.PositionMe.utils;

import android.graphics.PointF;

import java.util.List;

/**
 * Adjusts proposed 2D steps to avoid crossing walls.
 * Uses simple segment intersection: if the step crosses any wall segment, movement is cancelled.
 */
public final class WallCollisionCorrector {

    private WallCollisionCorrector() {
        // Utility class
    }

    /**
     * Returns a corrected point that avoids crossing wall segments.
     *
     * @param previous previous position (meters).
     * @param candidate proposed next position (meters).
     * @param walls list of wall polylines; each polyline is a list of points in meters.
     * @return corrected point (candidate or previous when blocked).
     */
    public static PointF correct(PointF previous,
                                 PointF candidate,
                                 List<List<PointF>> walls) {
        if (walls == null || walls.isEmpty()) {
            return candidate;
        }
        for (List<PointF> wall : walls) {
            if (wall == null || wall.size() < 2) continue;
            for (int i = 0; i < wall.size() - 1; i++) {
                PointF a = wall.get(i);
                PointF b = wall.get(i + 1);
                if (segmentsIntersect(previous, candidate, a, b)) {
                    // Block movement; return previous position
                    return previous;
                }
            }
        }
        return candidate;
    }

    private static boolean segmentsIntersect(PointF p1, PointF p2, PointF q1, PointF q2) {
        float d1 = direction(q1, q2, p1);
        float d2 = direction(q1, q2, p2);
        float d3 = direction(p1, p2, q1);
        float d4 = direction(p1, p2, q2);

        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
                ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }
        return d1 == 0 && onSegment(q1, q2, p1)
                || d2 == 0 && onSegment(q1, q2, p2)
                || d3 == 0 && onSegment(p1, p2, q1)
                || d4 == 0 && onSegment(p1, p2, q2);
    }

    private static float direction(PointF a, PointF b, PointF c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    private static boolean onSegment(PointF a, PointF b, PointF c) {
        return Math.min(a.x, b.x) <= c.x && c.x <= Math.max(a.x, b.x)
                && Math.min(a.y, b.y) <= c.y && c.y <= Math.max(a.y, b.y);
    }
}
