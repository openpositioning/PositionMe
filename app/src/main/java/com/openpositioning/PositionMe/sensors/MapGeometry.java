package com.openpositioning.PositionMe.sensors;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Seperated in a different class for modularity.
 * Static geometry helpers used by MapMatcher and ParticleFilter.
 * Handles point-in-polygon checks and wall-crossing detection.
 * Coordinates are local metres, same frame as ParticleFilter.
 */
class MapGeometry {

    private static final String TAG = "MapGeometry";

    // No instances needed
    private MapGeometry() {}



    /**
     * Tests whether point (x, y) is inside the given polygon using ray-casting.
     *
     * Casts a ray horizontally to the left from (x, y) and counts how many polygon
     * edges it crosses. An odd count means inside, even means outside.
     * Returns false for null or degenerate polygons (fewer than 3 vertices).
     *
     * @param x       easting in local metres
     * @param y       northing in local metres
     * @param polygon list of float[]{eastingM, northingM} vertices in order
     * @return true if the point is inside the polygon
     */
    static boolean isPointInsidePolygon(float x, float y, List<float[]> polygon) {
        if (polygon == null || polygon.size() < 3) return false;

        int n = polygon.size();
        boolean inside = false;
        int j = n - 1;

        for (int i = 0; i < n; i++) {
            float xi = polygon.get(i)[0], yi = polygon.get(i)[1];
            float xj = polygon.get(j)[0], yj = polygon.get(j)[1];

            if (((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
            j = i;
        }
        return inside;
    }



    /**
     * Returns true if the segment from (x1,y1) to (x2,y2) crosses any edge of the polygon.
     *
     * Called by ParticleFilter.predict() after each particle is displaced. If the move
     * segment crosses a wall edge, the particle is snapped back to its previous position.
     * Iterates all consecutive vertex pairs of the polygon and checks each edge with
     * segmentsIntersect(). The last edge joins the final vertex back to the first.
     *
     * Returns false for null or single-point polygons.
     *
     * @param x1      start easting in local metres
     * @param y1      start northing in local metres
     * @param x2      end easting in local metres
     * @param y2      end northing in local metres
     * @param polygon wall vertices as float[]{eastingM, northingM}
     */
    static boolean doesSegmentCrossPolygon(
            float x1, float y1, float x2, float y2,
            List<float[]> polygon) {
        if (polygon == null || polygon.size() < 2) return false;
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            float[] a = polygon.get(i);
            float[] b = polygon.get((i + 1) % n);
            if (segmentsIntersect(x1, y1, x2, y2, a[0], a[1], b[0], b[1])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if segment AB and segment CD intersect.
     * Uses parametric cross-product; returns false for parallel/collinear segments.
     *
     * @param ax start of segment A - easting
     * @param ay start of segment A - northing
     * @param bx end of segment A - easting
     * @param by end of segment A - northing
     * @param cx start of segment B - easting
     * @param cy start of segment B - northing
     * @param dx end of segment B - easting
     * @param dy end of segment B - northing
     */
    private static boolean segmentsIntersect(
            float ax, float ay, float bx, float by,
            float cx, float cy, float dx, float dy) {
        float d1x = bx - ax, d1y = by - ay;  // direction of segment A
        float d2x = dx - cx, d2y = dy - cy;  // direction of segment B
        float cross = d1x * d2y - d1y * d2x;
        if (Math.abs(cross) < 1e-6f) return false; // parallel or collinear
        float t = ((cx - ax) * d2y - (cy - ay) * d2x) / cross;
        float u = ((cx - ax) * d1y - (cy - ay) * d1x) / cross;
        return t >= 0f && t <= 1f && u >= 0f && u <= 1f;
    }



    /**
     * Runs a set of known-answer checks against isPointInsidePolygon and
     * doesSegmentCrossPolygon. Called once from MapMatcher.tryLoadBuilding()
     * after building data is parsed. Results are written to Logcat under the
     * MapGeometry tag so any geometry regression shows up immediately.
     */
    static void selfTest() {
        // Test polygon: 10x10 unit square with corners at (0,0), (10,0), (10,10), (0,10)
        List<float[]> square = new ArrayList<>();
        square.add(new float[]{0f,  0f});
        square.add(new float[]{10f, 0f});
        square.add(new float[]{10f, 10f});
        square.add(new float[]{0f,  10f});

        check("isPointInsidePolygon(5,5) == true",
                isPointInsidePolygon(5f, 5f, square), true);

        check("isPointInsidePolygon(15,5) == false",
                isPointInsidePolygon(15f, 5f, square), false);

        // Segment crossing the left edge of the square (x=-2 to x=5, y=5): should cross
        check("doesSegmentCrossPolygon(-2,5 -> 5,5) == true",
                doesSegmentCrossPolygon(-2f, 5f, 5f, 5f, square), true);

        // Segment that starts and ends inside the square - no edge crossing
        check("doesSegmentCrossPolygon(2,2 -> 8,8) == false",
                doesSegmentCrossPolygon(2f, 2f, 8f, 8f, square), false);

        // Segment completely outside the square - no crossing
        check("doesSegmentCrossPolygon(12,0 -> 15,10) == false",
                doesSegmentCrossPolygon(12f, 0f, 15f, 10f, square), false);
    }

    // Logs PASS or FAIL for a single self-test case.
    private static void check(String name, boolean result, boolean expected) {
        if (result == expected) {
            Log.d(TAG, "PASS: " + name);
        } else {
            Log.e(TAG, "FAIL: " + name
                    + " (got " + result + ", expected " + expected + ")");
        }
    }
}