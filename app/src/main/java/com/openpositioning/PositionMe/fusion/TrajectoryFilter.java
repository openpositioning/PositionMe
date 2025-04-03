package com.openpositioning.PositionMe.fusion;

import java.util.ArrayList;
import java.util.List;
import com.google.android.gms.maps.model.*;
import com.openpositioning.PositionMe.utils.CoordinateConverter;

import java.util.Queue;
import java.util.LinkedList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.math3.analysis.interpolation.LinearInterpolator;
import org.ejml.simple.SimpleMatrix;
import org.apache.commons.math3.analysis.interpolation.SplineInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;

/**
 * Provides methods to smooth coordinate data for pedestrian tracking.
 *
 * <p>TrajectoryFilter smooths trajectory data using the Visvalingam-Whyatt algorithm
 * and Catmull-Rom spline interpolation. It handles the simultaneous smoothing of x and y coordinates
 * for pedestrian movement tracking applications.
 *
 * @author Nick Manturov
 */
public class TrajectoryFilter {

    // Represents a point in 2D space
    static class Point {
        double x, y;

        Point(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final double FRACTION_RETAIN = 0.5;
    public static final int RAW_WINDOW_SIZE = 10;
    public static final int INTERPOLATION_FACTOR = 10;
    public static final double TENSION = 0.6;

    private final List<Point> fusionBufferEnu;
    private final List<LatLng> fusionBuffer;
    private final List<Point> reducedBufferEnu;
    private final List<LatLng> interpolatedBuffer;
    private int count;

    /**
     * Creates a Moving Average filter for smoothing pedestrian positioning data.
     */
    public TrajectoryFilter() {

        this.fusionBufferEnu = new ArrayList<>();
        this.fusionBuffer = new ArrayList<>();
        this.reducedBufferEnu = new ArrayList<>();
        this.interpolatedBuffer = new ArrayList<>();
        this.count = 0;
    }

    /**
     * Process new positioning data (step length and heading angle).
     *
     * @param rawPoint Unfiltered new LatLng point
     * @param referencePosition Reference position for LatLng to ENU conversion
     * @return Filtered [east, north] or null if window isn't filled yet
     */
    public List<LatLng> processData(LatLng rawPoint, double[] referencePosition) {

        fusionBuffer.add(rawPoint);

        double[] enu = CoordinateConverter.convertGeodeticToEnu(rawPoint.latitude, rawPoint.longitude, referencePosition[2],
                referencePosition[0], referencePosition[1], referencePosition[2]);
        fusionBufferEnu.add(new Point(enu[0], enu[1]));

        int pointsRetained = (int) Math.round(fusionBufferEnu.size()*FRACTION_RETAIN);

        if (pointsRetained < 5) {
            return fusionBuffer;
        }

        if (fusionBufferEnu.size() >= RAW_WINDOW_SIZE) {
            List<Point> dataArray = fusionBufferEnu.subList(count, count+RAW_WINDOW_SIZE);
            List<Point> reducedPolyline = simplifyPolyline(dataArray, pointsRetained);

            int numPrevious = Math.min(INTERPOLATION_FACTOR, reducedBufferEnu.size());
            List<Point> EnuBufferSublist = reducedBufferEnu.subList(reducedBufferEnu.size()-numPrevious, reducedBufferEnu.size());
            List<Point> extendedPolyline = Stream.concat(EnuBufferSublist.stream(), reducedPolyline.stream())
                    .collect(Collectors.toList());
            reducedBufferEnu.add(reducedPolyline.get(0));

            List<Point> interpolatedPolyline = interpolateNaturalMovement(extendedPolyline);

            List<LatLng> geoPolyline = new ArrayList<>();
            for (Point point : interpolatedPolyline) {
                geoPolyline.add(CoordinateConverter.convertEnuToGeodetic(point.x, point.y, 0,
                        referencePosition[0], referencePosition[1], referencePosition[2]));
            }

            List<LatLng> result = Stream.concat(interpolatedBuffer.stream(), geoPolyline.stream())
                    .collect(Collectors.toList());
            interpolatedBuffer.addAll(geoPolyline.subList(0, INTERPOLATION_FACTOR));
            count++;
            return result;
        }
        else {
            List<Point> reducedPolyline = simplifyPolyline(fusionBufferEnu, pointsRetained);

            List<LatLng> geoPolyline = new ArrayList<>();
            for (Point point : reducedPolyline) {
                geoPolyline.add(CoordinateConverter.convertEnuToGeodetic(point.x, point.y, 0,
                        referencePosition[0], referencePosition[1], referencePosition[2]));
            }
            return geoPolyline;
        }
    }

    /**
     * Calculates the area of a triangle formed by three points.
     *
     * @param p1 First point of the triangle
     * @param p2 Second point of the triangle
     * @param p3 Third point of the triangle
     * @return The area of the triangle
     */
    public static double calculateTriangleArea(Point p1, Point p2, Point p3) {
        return 0.5 * Math.abs(p1.x * (p2.y - p3.y) + p2.x * (p3.y - p1.y) + p3.x * (p1.y - p2.y));
    }

    /**
     * Simplifies a polyline using the Visvalingam-Whyatt algorithm.
     *
     * @param points List of points to simplify
     * @param targetPointCount Target number of points to retain
     * @return A simplified list of points
     */
    public static List<Point> simplifyPolyline(List<Point> points, int targetPointCount) {
        if (points.size() <= targetPointCount) {
            return new ArrayList<>(points); // No simplification needed if already under target
        }

        // List to hold the simplified points and the areas
        List<Point> simplifiedPoints = new ArrayList<>(points);
        List<Double> areas = new ArrayList<>();

        // Calculate the area for each triangle formed by three consecutive points
        // We can only calculate areas for internal points (not endpoints)
        for (int i = 1; i < simplifiedPoints.size() - 1; i++) {
            double area = calculateTriangleArea(
                    simplifiedPoints.get(i - 1),
                    simplifiedPoints.get(i),
                    simplifiedPoints.get(i + 1)
            );
            areas.add(area);
        }

        // Remove points iteratively based on the smallest area
        while (simplifiedPoints.size() > targetPointCount) {
            // Find the index with the smallest area
            int minAreaIndex = findMinAreaIndex(areas);

            // The actual point index to remove is minAreaIndex + 1 because:
            // - areas[0] corresponds to point[1]
            // - areas[n] corresponds to point[n+1]
            int pointIndexToRemove = minAreaIndex + 1;

            // Remove the point
            simplifiedPoints.remove(pointIndexToRemove);

            // Remove the corresponding area
            areas.remove(minAreaIndex);

            // Update the affected areas
            // If we removed a point other than the second point
            if (minAreaIndex > 0) {
                // Update the area before the removed point
                double newArea = calculateTriangleArea(
                        simplifiedPoints.get(minAreaIndex - 1),
                        simplifiedPoints.get(minAreaIndex),
                        simplifiedPoints.get(minAreaIndex + 1)
                );
                areas.set(minAreaIndex - 1, newArea);
            }

            // If we have a point after the removed one that isn't the last point
            if (minAreaIndex < areas.size()) {
                // Update the area after the removed point
                double newArea = calculateTriangleArea(
                        simplifiedPoints.get(minAreaIndex),
                        simplifiedPoints.get(minAreaIndex + 1),
                        minAreaIndex + 2 < simplifiedPoints.size() ? simplifiedPoints.get(minAreaIndex + 2) : null
                );

                // Check if we calculated a valid area (we might be at the end)
                if (minAreaIndex + 2 < simplifiedPoints.size()) {
                    areas.set(minAreaIndex, newArea);
                }
            }
        }

        return simplifiedPoints;
    }

    /**
     * Helper method to find the index of the minimum area in the list.
     *
     * @param areas List of triangle areas
     * @return Index of the minimum area value
     */
    private static int findMinAreaIndex(List<Double> areas) {
        if (areas.isEmpty()) {
            return -1; // Handle empty list case
        }

        int minIndex = 0;
        double minValue = areas.get(0);

        for (int i = 1; i < areas.size(); i++) {
            if (areas.get(i) < minValue) {
                minValue = areas.get(i);
                minIndex = i;
            }
        }
        return minIndex;
    }


    /**
     * Interpolates points using Catmull-Rom splines to create natural, smooth curves
     * that round out sharp turns while preserving the original path's character.
     *
     * @param reducedPoints The original points to interpolate
     * @return A list of smoothly interpolated points
     */
    public static List<Point> interpolateNaturalMovement(List<Point> reducedPoints) {
        int n = reducedPoints.size();

        // Need at least 2 points for any interpolation
        if (n < 2) {
            return new ArrayList<>(reducedPoints);
        }

        // For just 2 points, use linear interpolation
        if (n == 2) {
            return interpolateLinearSegment(reducedPoints.get(0), reducedPoints.get(1), INTERPOLATION_FACTOR);
        }

        ArrayList<Point> interpolatedPoints = new ArrayList<>();

        // Create extended points list with duplicated endpoints to handle boundary conditions
        List<Point> extendedPoints = new ArrayList<>();

        // Duplicate first point to serve as "pre-first" control point
        extendedPoints.add(createExtrapolatedPoint(reducedPoints.get(0), reducedPoints.get(1), true));
        // Add all original points
        extendedPoints.addAll(reducedPoints);
        // Duplicate last point to serve as "post-last" control point
        extendedPoints.add(createExtrapolatedPoint(reducedPoints.get(n-2), reducedPoints.get(n-1), false));

        // Define tension parameter (0.5 is standard for Catmull-Rom)
        // Lower values create tighter curves, higher values create looser curves
        double tension = TENSION;

        // Iterate through all segments
        for (int i = 0; i < n - 1; i++) {
            Point p0 = extendedPoints.get(i);     // First control point
            Point p1 = extendedPoints.get(i + 1); // Start point of segment
            Point p2 = extendedPoints.get(i + 2); // End point of segment
            Point p3 = extendedPoints.get(i + 3); // Last control point

            // Number of steps to interpolate for this segment
            int steps = INTERPOLATION_FACTOR;

            // Add the start point
            if (i == 0) {
                interpolatedPoints.add(new Point(p1.x, p1.y));
            }

            // Generate points along the spline for this segment
            for (int step = 1; step <= steps; step++) {
                double t = (double) step / steps;

                // Catmull-Rom spline formula
                double x = catmullRomInterpolate(p0.x, p1.x, p2.x, p3.x, t, tension);
                double y = catmullRomInterpolate(p0.y, p1.y, p2.y, p3.y, t, tension);

                interpolatedPoints.add(new Point(x, y));
            }
        }

        return interpolatedPoints;
    }

    /**
     * Calculates a point value using Catmull-Rom interpolation formula.
     *
     * @param p0 First control point
     * @param p1 Second control point (curve start)
     * @param p2 Third control point (curve end)
     * @param p3 Fourth control point
     * @param t Parameter between 0 and 1
     * @param tension Tension parameter controlling curve tightness
     * @return The interpolated value
     */
    private static double catmullRomInterpolate(double p0, double p1, double p2, double p3, double t, double tension) {
        double t2 = t * t;
        double t3 = t2 * t;

        // Catmull-Rom matrix coefficients
        double a = -tension * p0 + (2 - tension) * p1 + (tension - 2) * p2 + tension * p3;
        double b = 2 * tension * p0 + (tension - 3) * p1 + (3 - 2 * tension) * p2 - tension * p3;
        double c = -tension * p0 + tension * p2;
        double d = p1;

        return a * t3 + b * t2 + c * t + d;
    }

    /**
     * Creates an extrapolated control point beyond the endpoints.
     *
     * @param p1 First existing point
     * @param p2 Second existing point
     * @param isFirst True if creating a point before the sequence, false for after
     * @return A new extrapolated control point
     */
    private static Point createExtrapolatedPoint(Point p1, Point p2, boolean isFirst) {
        // For first point, extrapolate backwards; for last point, extrapolate forwards
        double dx = p2.x - p1.x;
        double dy = p2.y - p1.y;

        if (isFirst) {
            // Place extrapolated point before the first point
            return new Point(p1.x - dx, p1.y - dy);
        } else {
            // Place extrapolated point after the last point
            return new Point(p2.x + dx, p2.y + dy);
        }
    }

    /**
     * Simple linear interpolation between two points.
     *
     * @param start Starting point
     * @param end Ending point
     * @param steps Number of steps to interpolate
     * @return List of interpolated points
     */
    private static List<Point> interpolateLinearSegment(Point start, Point end, int steps) {
        List<Point> result = new ArrayList<>();
        result.add(start);

        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            double x = start.x + t * (end.x - start.x);
            double y = start.y + t * (end.y - start.y);
            result.add(new Point(x, y));
        }

        result.add(end);
        return result;
    }

    /**
     * Resets the filter's data window.
     */
    public void reset() {
        fusionBufferEnu.clear();
        fusionBuffer.clear();
        reducedBufferEnu.clear();
        interpolatedBuffer.clear();
        count = 0;
    }
}