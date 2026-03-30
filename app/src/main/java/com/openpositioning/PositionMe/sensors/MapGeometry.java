package com.openpositioning.PositionMe.sensors;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

// NOTE: doesSegmentCross* methods are intentionally omitted — wall-crossing
// correction belongs to the separate "movement model / wall constraint" requirement.

/**
 * Pure static geometry helpers for map-matching.
 *
 * No instance state. No Android dependencies beyond Log.
 * All coordinates are in the local easting/northing meter frame used by
 * ParticleFilter.
 *
 * Logcat tag: MapGeometry (self-test results only)
 */
class MapGeometry {

    private static final String TAG = "MapGeometry";

    // No instances needed
    private MapGeometry() {}

    // -------------------------------------------------------------------------
    // Point-in-polygon
    // -------------------------------------------------------------------------

    /**
     * Ray-casting point-in-polygon test.
     *
     * @param x       easting (meters, local frame)
     * @param y       northing (meters, local frame)
     * @param polygon list of float[]{eastingM, northingM} vertices
     * @return true if (x,y) is strictly inside the polygon
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

    // -------------------------------------------------------------------------
    // Self-test (Step 2)
    // -------------------------------------------------------------------------

    /**
     * Validates isPointInsidePolygon against known-correct answers.
     * Called once after building map data is loaded. Tag: MapGeometry.
     */
    static void selfTest() {
        // Unit square: (0,0) → (10,0) → (10,10) → (0,10)
        List<float[]> square = new ArrayList<>();
        square.add(new float[]{0f,  0f});
        square.add(new float[]{10f, 0f});
        square.add(new float[]{10f, 10f});
        square.add(new float[]{0f,  10f});

        check("isPointInsidePolygon(5,5) == true",
                isPointInsidePolygon(5f, 5f, square), true);

        check("isPointInsidePolygon(15,5) == false",
                isPointInsidePolygon(15f, 5f, square), false);
    }

    private static void check(String name, boolean result, boolean expected) {
        if (result == expected) {
            Log.d(TAG, "PASS: " + name);
        } else {
            Log.e(TAG, "FAIL: " + name
                    + " (got " + result + ", expected " + expected + ")");
        }
    }
}
