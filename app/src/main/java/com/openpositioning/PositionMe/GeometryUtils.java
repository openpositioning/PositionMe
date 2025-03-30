package com.openpositioning.PositionMe;

public class GeometryUtils {

    // 判断三点是否逆时针
    private static boolean ccw(float[] A, float[] B, float[] C) {
        return (C[1]-A[1]) * (B[0]-A[0]) > (B[1]-A[1]) * (C[0]-A[0]);
    }

    // 判断两条线段 AB 与 CD 是否相交
    public static boolean segmentsIntersect(float[] A, float[] B, float[] C, float[] D) {
        return ccw(A, C, D) != ccw(B, C, D) && ccw(A, B, C) != ccw(A, B, D);
    }

    // 将点 P 投影到线段 AB 上，返回投影点（最近点）
    public static float[] projectPointOntoSegment(float[] P, float[] A, float[] B) {
        float[] AB = new float[]{B[0] - A[0], B[1] - A[1]};
        float[] AP = new float[]{P[0] - A[0], P[1] - A[1]};

        float ab2 = AB[0] * AB[0] + AB[1] * AB[1];
        if (ab2 == 0) return A; // A和B重合，返回A

        float t = (AP[0] * AB[0] + AP[1] * AB[1]) / ab2;
        t = Math.max(0, Math.min(1, t)); // 限制 t ∈ [0,1]

        return new float[]{
                A[0] + t * AB[0],
                A[1] + t * AB[1]
        };
    }
}
