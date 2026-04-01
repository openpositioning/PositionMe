package com.openpositioning.PositionMe.fusion;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Provides constraints to the movements made in fusion by using known building geometries to
 * improve estimation by removing particles that move through walls with PDR updates. This class
 * also uses building geometries to determine if elevation changes are eligible.
 *
 * @see Fusion
 */
public class MapMatching {
    private static final String TAG = "MapMatching";

    // Hashmaps hashing floor index to a list of floor plan elements
    private Map<Integer, List<List<double[]>>> walls;
    private Map<Integer, List<List<double[]>>> stairs;
    private Map<Integer, List<List<double[]>>> elevators;

    // Current floor estimate
    private int floor;

    public MapMatching() {}

    /**
     * sets the known geometries of the current building.
     *
     * @param walls maps floor number int to a list of EN points making all walls of that floor
     * @param stairs maps floor number int to a list of EN points making all stairs of that floor
     * @param elevators maps floor number int to a list of EN points making all elevators of that
     *     floor
     */
    public void setGeometries(
            Map<Integer, List<List<double[]>>> walls,
            Map<Integer, List<List<double[]>>> stairs,
            Map<Integer, List<List<double[]>>> elevators) {
        this.walls = walls;
        this.stairs = stairs;
        this.elevators = elevators;
        Log.d(
                TAG,
                "Geometries set! ("
                        + walls.size()
                        + " walls; "
                        + stairs.size()
                        + " stairs; "
                        + elevators.size()
                        + " elevators)");
    }

    /**
     * removes particles in which the next PDR position change estimates that move through a walls.
     *
     * @param currentParticles particles before a move
     * @param bestEstmatePositionEN The current best Fusion estimate
     */
    public List<Particle> removeImpossibleParticles(
            List<Particle> currentParticles, double[] bestEstmatePositionEN) {
        if (walls == null || walls.isEmpty()) {
            Log.w(TAG, "No walls available; skipping map matching");
            return currentParticles;
        }

        double currentX = bestEstmatePositionEN[0];
        double currentY = bestEstmatePositionEN[1];
        ArrayList<Particle> validParticles = new ArrayList<>(currentParticles);
        List<List<double[]>> wallSegments = walls.get(floor);
        for (Particle particle : currentParticles) {
            double newX = particle.easting;
            double newY = particle.northing;

            boolean crossedWall = false;
            if (wallSegments == null) return currentParticles;
            for (List<double[]> wallSegment : wallSegments) {
                for (int i = 0; i < wallSegment.size(); i++) {
                    double[] wallPointOne = wallSegment.get(i);
                    int j = i + 1;
                    if (j >= wallSegment.size()) {
                        j = 0;
                    }
                    double[] wallPointTwo = wallSegment.get(j);
                    double d1 =
                            cross(
                                    wallPointOne[0],
                                    wallPointOne[1],
                                    wallPointTwo[0],
                                    wallPointTwo[1],
                                    currentX,
                                    currentY);
                    double d2 =
                            cross(
                                    wallPointOne[0],
                                    wallPointOne[1],
                                    wallPointTwo[0],
                                    wallPointTwo[1],
                                    newX,
                                    newY);
                    double d3 =
                            cross(currentX, currentY, newX, newY, wallPointOne[0], wallPointOne[1]);
                    double d4 =
                            cross(currentX, currentY, newX, newY, wallPointTwo[0], wallPointTwo[1]);

                    crossedWall = (d1 > 0 != d2 > 0) && (d3 > 0 != d4 > 0);
                    if (crossedWall) break;
                }
                if (crossedWall) break;
            }
            if (crossedWall) validParticles.remove(particle);
        }
        Log.d(
                TAG,
                "Removed "
                        + (currentParticles.size() - validParticles.size())
                        + " invalid particles");
        return validParticles;
    }

    public boolean checkWallCrossed(double[] currentEstimate, double[] newEstimate) {
        if (walls == null) return false;

        double currentX = currentEstimate[0];
        double currentY = currentEstimate[1];
        double newX = newEstimate[0];
        double newY = newEstimate[1];

        List<List<double[]>> wallSegments = walls.get(floor);

        boolean crossedWall = false;
        if (wallSegments == null) return false;
        for (List<double[]> wallSegment : wallSegments) {
            for (int i = 0; i < wallSegment.size(); i++) {
                double[] wallPointOne = wallSegment.get(i);
                int j = i + 1;
                if (j >= wallSegment.size()) {
                    j = 0;
                }
                double[] wallPointTwo = wallSegment.get(j);
                double d1 =
                        cross(
                                wallPointOne[0],
                                wallPointOne[1],
                                wallPointTwo[0],
                                wallPointTwo[1],
                                currentX,
                                currentY);
                double d2 =
                        cross(
                                wallPointOne[0],
                                wallPointOne[1],
                                wallPointTwo[0],
                                wallPointTwo[1],
                                newX,
                                newY);
                double d3 = cross(currentX, currentY, newX, newY, wallPointOne[0], wallPointOne[1]);
                double d4 = cross(currentX, currentY, newX, newY, wallPointTwo[0], wallPointTwo[1]);

                if ((d1 > 0 != d2 > 0) && (d3 > 0 != d4 > 0)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Determines if a user is in a staircase or elevator and therefore able to change altitude.
     *
     * @param position position to check if in stairway or elevator
     * @return {@code true} if the position is if in stairway or elevator
     */
    public boolean isEligibleForAltitudeChange(double[] position) {
        if (stairs == null || elevators == null) {
            Log.w(TAG, "Stairs or elevators not set; skipping altitude check");
            return false;
        }
        List<List<double[]>> floorStairs = stairs.get(floor);
        List<List<double[]>> floorElevators = elevators.get(floor);

        // Check if position is inside any staircase
        if (floorStairs != null) {
            if (isPointInAnyElement(floorStairs, position)) {
                Log.d(TAG, "Eligible for floor change: Stairs");
                return true;
            }
        } else {
            Log.w(TAG, "Stairs is null!");
        }

        // Check if position is inside any elevator
        if (floorElevators != null) {
            if (isPointInAnyElement(floorElevators, position)) {
                Log.d(TAG, "Eligible for floor change: Lifts");
                return true;
            }
        } else {
            Log.w(TAG, "Lifts is null!");
        }

        Log.d(TAG, "Ineligible for floor change");
        return false;
    }

    /** TODO - JavaDocs */
    private boolean isPointInAnyElement(List<List<double[]>> elements, double[] point) {
        for (List<double[]> element : elements) {
            if (isPointInPolygon(point, element)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines whether a point lies inside a polygon using the ray casting algorithm.
     *
     * @param pos the test point as {@code [x, y]}
     * @param shape the polygon vertices as an ordered list of {@code [x, y]} points
     * @return {@code true} if the point is inside the polygon
     */
    private boolean isPointInPolygon(double[] pos, List<double[]> shape) {
        int crossings = 0;
        for (int i = 0; i < shape.size(); i++) {
            double[] firstPoint = shape.get(i);
            int j = i + 1;
            if (j >= shape.size()) {
                j = 0;
            }
            double[] secondPoint = shape.get(j);
            if (crossingSegment(pos, firstPoint, secondPoint)) {
                crossings++;
            }
        }
        return crossings % 2 == 1;
    }

    /**
     * Determines whether a horizontal ray cast rightward from a given point crosses a line segment
     * defined by two endpoints, using the ray casting algorithm for point-in-polygon testing.
     *
     * <p>The method handles edge cases where the point's y-coordinate aligns exactly with a vertex
     * by applying a small epsilon nudge to avoid double-counting shared vertices between adjacent
     * segments.
     *
     * @param point the test point as {@code [x, y]}
     * @param a one endpoint of the segment as {@code [x, y]}
     * @param b the other endpoint of the segment as {@code [x, y]}
     * @return {@code true} if a rightward ray from {@code point} crosses the segment
     */
    private boolean crossingSegment(double[] point, double[] a, double[] b) {
        double px = point[0], py = point[1];
        double ax = a[0], ay = a[1];
        double bx = b[0], by = b[1];

        // Ensure a is the lower point
        if (ay > by) {
            ax = b[0];
            ay = b[1];
            bx = a[0];
            by = a[1];
        }

        // Nudge if point is exactly on a vertex's y
        if (py == ay || py == by) py += 0.00000001;

        // Point is above, below, or to the right of segment
        if (py > by || py < ay || px > Math.max(ax, bx)) {
            return false;
        }
        // Point is to the left of segment
        else if (px < Math.min(ax, bx)) {
            return true;
        }
        // Compare slopes
        else {
            double slope1 = (ax != bx) ? ((by - ay) / (bx - ax)) : Double.POSITIVE_INFINITY;
            double slope2 = (ax != px) ? ((py - ay) / (px - ax)) : Double.POSITIVE_INFINITY;
            return (slope2 >= slope1);
        }
    }

    /**
     * Sets the current floor estimate for local logic
     *
     * @param floor The new floor estimate
     */
    public void setFloor(int floor) {
        this.floor = floor;
    }

    /**
     * Computes the z-component of the cross product of vectors AB and AC.
     *
     * <p>A positive result indicates C is to the left of AB, a negative result indicates C is to
     * the right, and zero indicates the points are collinear.
     *
     * <p>
     *
     * @param ax x-coordinate of point A
     * @param ay y-coordinate of point A
     * @param bx x-coordinate of point B
     * @param by y-coordinate of point B
     * @param cx x-coordinate of point C
     * @param cy y-coordinate of point C
     * @return the z-component of AB × AC
     */
    private double cross(double ax, double ay, double bx, double by, double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }
}
