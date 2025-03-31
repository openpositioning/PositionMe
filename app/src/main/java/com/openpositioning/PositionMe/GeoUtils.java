package com.openpositioning.PositionMe;

public class GeoUtils {

    public static float[] getLineSegmentIntersection(
            float[] p1, float[] p2, float[] q1, float[] q2) {
        float x1 = p1[0], y1 = p1[1];
        float x2 = p2[0], y2 = p2[1];
        float x3 = q1[0], y3 = q1[1];
        float x4 = q2[0], y4 = q2[1];

        float denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (denom == 0) return null; // 平行或重合

        float px = ((x1 * y2 - y1 * x2) * (x3 - x4) -
                (x1 - x2) * (x3 * y4 - y3 * x4)) / denom;
        float py = ((x1 * y2 - y1 * x2) * (y3 - y4) -
                (y1 - y2) * (x3 * y4 - y3 * x4)) / denom;

        // 检查是否在线段范围内（即是真正的交点，而不是延长线相交）
        if (pointOnSegment(px, py, p1, p2) && pointOnSegment(px, py, q1, q2)) {
            return new float[]{px, py};
        } else {
            return null;
        }
    }

    public static boolean pointOnSegment(float px, float py, float[] a, float[] b) {
        return px >= Math.min(a[0], b[0]) - 1e-6 && px <= Math.max(a[0], b[0]) + 1e-6 &&
                py >= Math.min(a[1], b[1]) - 1e-6 && py <= Math.max(a[1], b[1]) + 1e-6;
    }

}