package com.openpositioning.PositionMe;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.google.maps.android.PolyUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Utility class for geographic operations such as determining if a point is within a given region.
 */
public class GeoUtils {


    // General GeoUtils
    /**
     * Computes the intersection point of two line segments.
     *
     * @param p1 The first endpoint of the first line segment.
     * @param p2 The second endpoint of the first line segment.
     * @param q1 The first endpoint of the second line segment.
     * @param q2 The second endpoint of the second line segment.
     * @return A float array containing the intersection point [x, y] if one exists within both segments; otherwise, null.
     */
    public static float[] getLineSegmentIntersection(float[] p1, float[] p2, float[] q1, float[] q2) {
        final float x1 = p1[0], y1 = p1[1];
        final float x2 = p2[0], y2 = p2[1];
        final float x3 = q1[0], y3 = q1[1];
        final float x4 = q2[0], y4 = q2[1];

        // Compute denominator for the intersection formula.
        final float denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (denom == 0) {
            return null; // The lines are parallel or coincident.
        }

        // Calculate intersection point (px, py).
        final float px = ((x1 * y2 - y1 * x2) * (x3 - x4) -
                (x1 - x2) * (x3 * y4 - y3 * x4)) / denom;
        final float py = ((x1 * y2 - y1 * x2) * (y3 - y4) -
                (y1 - y2) * (x3 * y4 - y3 * x4)) / denom;

        // Check if the computed intersection lies on both segments.
        if (pointOnSegment(px, py, p1, p2) && pointOnSegment(px, py, q1, q2)) {
            return new float[]{px, py};
        } else {
            return null;
        }
    }

    /**
     * Determines if a point (px, py) lies on the line segment defined by points a and b.
     *
     * @param px The x-coordinate of the point.
     * @param py The y-coordinate of the point.
     * @param a  The first endpoint of the segment.
     * @param b  The second endpoint of the segment.
     * @return True if the point lies on the segment; false otherwise.
     */
    public static boolean pointOnSegment(float px, float py, float[] a, float[] b) {
        return px >= Math.min(a[0], b[0]) - 1e-6 && px <= Math.max(a[0], b[0]) + 1e-6 &&
                py >= Math.min(a[1], b[1]) - 1e-6 && py <= Math.max(a[1], b[1]) + 1e-6;
    }


    // Stair/Lift Detection
    // Define constant region polygons as static final lists.
    private static final List<LatLng> ELEVATOR_POLYGON = Arrays.asList(
            new LatLng(55.92307361898947, -3.174281938426994),
            new LatLng(55.92307368121945, -3.174432016879645),
            new LatLng(55.92302088671354, -3.1744321230471044),
            new LatLng(55.92302085467245, -3.1743548100179217),
            new LatLng(55.923031413574854, -3.1743547887615846),
            new LatLng(55.92303138337408, -3.174282023528208)
    );

    private static final List<LatLng> FIRST_STAIRCASE_POLYGON = Arrays.asList(
            new LatLng(55.92302153551364, -3.1742210237571133),
            new LatLng(55.923021575395175, -3.1743170141360544),
            new LatLng(55.92291796333051, -3.1743172228541),
            new LatLng(55.922917910116, -3.1741892360311152),
            new LatLng(55.92298697722846, -3.174170812777022)
    );

    private static final List<LatLng> SECOND_FLOOR_STAIRCASE_POLYGON = Arrays.asList(
            new LatLng(55.92305776740818, -3.174250135524168),
            new LatLng(55.923057723893564, -3.1741455354069608),
            new LatLng(55.923144834872836, -3.174145359592135),
            new LatLng(55.92314487837232, -3.1742499599514304)
    );

    private static final List<LatLng> BASEMENT_STAIRCASE_POLYGON = Arrays.asList(
            new LatLng(55.92301092610767, -3.1742902930391566),
            new LatLng(55.92301088376152, -3.174188435819902),
            new LatLng(55.92312678459982, -3.174188202011103),
            new LatLng(55.92312682692639, -3.1742900595440013)
    );

    /**
     * Private helper method to check if a given location is within a polygonal region.
     *
     * @param currentLocation The location to check.
     * @param polygon         The list of LatLng points defining the polygon.
     * @return True if the location is within the polygon; false otherwise.
     */
    private static boolean isInRegion(LatLng currentLocation, List<LatLng> polygon) {
        return PolyUtil.containsLocation(currentLocation, polygon, true);
    }

    /**
     * Determines if the given location is within the elevator region.
     *
     * @param currentLocation The current location to check.
     * @return True if the location is within the elevator region; false otherwise.
     */
    public static boolean isInElevator(LatLng currentLocation) {
        return isInRegion(currentLocation, ELEVATOR_POLYGON);
    }

    /**
     * Determines if the given location is within the first staircase region.
     *
     * @param currentLocation The current location to check.
     * @return True if the location is within the first staircase region; false otherwise.
     */
    public static boolean isInFirstStaircase(LatLng currentLocation) {
        return isInRegion(currentLocation, FIRST_STAIRCASE_POLYGON);
    }

    /**
     * Determines if the given location is within the second floor staircase region.
     *
     * @param currentLocation The current location to check.
     * @return True if the location is within the second floor staircase region; false otherwise.
     */
    public static boolean isInSecondFloorStaircase(LatLng currentLocation) {
        return isInRegion(currentLocation, SECOND_FLOOR_STAIRCASE_POLYGON);
    }

    /**
     * Determines if the given location is within the basement staircase region.
     *
     * @param currentLocation The current location to check.
     * @return True if the location is within the basement staircase region; false otherwise.
     */
    public static boolean isInBasementStaircase(LatLng currentLocation) {
        return isInRegion(currentLocation, BASEMENT_STAIRCASE_POLYGON);
    }

    /**
     * Checks if the given location is within any of the defined regions.
     * The function returns true if the location is in the elevator, first staircase, second floor staircase,
     * or basement staircase regions.
     *
     * @param currentLocation The current location to check.
     * @return True if the location is within any region; false if it is not in any.
     */
    public static boolean isInStairRegion(LatLng currentLocation) {
        return isInElevator(currentLocation) ||
                isInFirstStaircase(currentLocation) ||
                isInSecondFloorStaircase(currentLocation) ||
                isInBasementStaircase(currentLocation);
    }

    // GeoFence implementations

    private static final List<LatLng> FIRST_WALL_POINTS = Arrays.asList(
            new LatLng(55.92301090863321, -3.174221045188629),
            new LatLng(55.92301094092557, -3.1742987516650873),
            new LatLng(55.92292858261526, -3.174298917609189),
            new LatLng(55.92292853699635, -3.174189214585424),
            new LatLng(55.92298698483965, -3.1741890966446484)
    );
    private static final List<LatLng> SECOND_WALL_POINTS = Arrays.asList(
            new LatLng(55.923012912847625, -3.17430025206314),
            new LatLng(55.9230128674766, -3.1741911042535036),
            new LatLng(55.922952153773124, -3.174191226755363),
            new LatLng(55.922952199155134, -3.17430037438893),
            new LatLng(55.923012912847625, -3.17430025206314)
    );

    /**
     * Computes corrected coordinates based on potential intersections with wall segments.
     * <p>
     * The method checks if the path from the current state coordinates to the new coordinates
     * intersects with any of the defined wall segments. If an intersection is detected, it computes
     * a corrected coordinate that offsets the intersection point slightly along the path direction and
     * perpendicular to the wall segment.
     * </p>
     *
     * @param wifiFloor         The floor number (1 for primary floor; other values for secondary).
     * @param startLocLatLng    The starting location as a reference for coordinate conversion.
     * @param currentStateCords The current state coordinates as a float array [x, y].
     * @param newCords          The new target coordinates as a float array [x, y].
     * @return The corrected coordinates as a float array [x, y] if an intersection with a wall is detected;
     *         otherwise, the original newCords are returned.
     */
    public static float[] getCorrectedCoords(int wifiFloor, LatLng startLocLatLng, float[] currentStateCords, float[] newCords) {

        // Convert the selected wall points to a local coordinate system (northing/easting).
        List<float[]> wallPoints = new ArrayList<>();
        List<LatLng> selectedWallLatLng = (wifiFloor == 1) ? FIRST_WALL_POINTS : SECOND_WALL_POINTS;

        for (LatLng point : selectedWallLatLng) {
            // Convert each LatLng point to a coordinate relative to the starting location.
            double[] converted = UtilFunctions.convertLatLangToNorthingEasting(startLocLatLng, point);
            wallPoints.add(new float[]{(float) converted[0], (float) converted[1]});
        }

        // Iterate over each wall segment to check for an intersection with the path.
        for (int i = 0; i < wallPoints.size() - 1; i++) {
            float[] wallA = wallPoints.get(i);
            float[] wallB = wallPoints.get(i + 1);

            // Calculate the intersection point between the current path and the wall segment.
            float[] intersection = GeoUtils.getLineSegmentIntersection(currentStateCords, newCords, wallA, wallB);

            if (intersection != null) {
                // Compute the direction vector from the current state to the intersection.
                float dx = currentStateCords[0] - intersection[0];
                float dy = currentStateCords[1] - intersection[1];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len == 0) {
                    Log.e("WallCheck", "Starting and ending points overlapped cannot be corrected");
                    break;
                }
                float dirX = dx / len;
                float dirY = dy / len;

                // Calculate the wall segment's direction vector.
                float wx = wallB[0] - wallA[0];
                float wy = wallB[1] - wallA[1];
                float wlen = (float) Math.sqrt(wx * wx + wy * wy);
                if (wlen == 0) {
                    Log.e("WallCheck", "Wall points overlapped, skipping");
                    continue;
                }
                float wallDirX = wx / wlen;
                float wallDirY = wy / wlen;

                // Compute the normal (perpendicular) vector to the wall segment.
                float normalX = -wallDirY;
                float normalY = wallDirX;

                // Ensure the normal vector points in the correct direction relative to the movement.
                float dot = dx * normalX + dy * normalY;
                if (dot < 0) {
                    normalX = -normalX;
                    normalY = -normalY;
                }

                // Define offsets for adjusting the coordinate.
                float offset = 0.25f;
                float slideOffset = 0.1f;
                // Compute the corrected coordinates by applying the offsets.
                float[] corrected = new float[]{
                        intersection[0] + dirX * offset + normalX * slideOffset,
                        intersection[1] + dirY * offset + normalY * slideOffset
                };

                Log.d("WallCheck", "✅ correction point: " + corrected[0] + ", " + corrected[1]);
                return corrected;
            }
        }
        // If no wall intersection is detected, return the original new coordinates.
        return newCords;
    }
}

