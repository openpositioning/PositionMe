package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * Custom view to display trajectory as a fixed overlay during position correction mode.
 * The trajectory is drawn at a fixed position on screen while the map moves underneath.
 */
public class TrajectoryOverlayView extends View {

    private List<double[]> trajectoryPoints;
    private Paint pathPaint;
    private Paint startPointPaint;
    private Paint endPointPaint;
    private Paint testPointPaint;
    private List<TestPointData> testPoints;

    // Bounds of the trajectory in lat/lng coordinates
    private double minLat, maxLat, minLng, maxLng;

    public TrajectoryOverlayView(Context context) {
        super(context);
        init();
    }

    public TrajectoryOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        pathPaint = new Paint();
        pathPaint.setColor(Color.BLUE);
        pathPaint.setStrokeWidth(8f);
        pathPaint.setStyle(Paint.Style.STROKE);
        pathPaint.setStrokeCap(Paint.Cap.ROUND);
        pathPaint.setStrokeJoin(Paint.Join.ROUND);
        pathPaint.setAntiAlias(true);

        startPointPaint = new Paint();
        startPointPaint.setColor(Color.GREEN);
        startPointPaint.setStyle(Paint.Style.FILL);
        startPointPaint.setAntiAlias(true);

        endPointPaint = new Paint();
        endPointPaint.setColor(Color.RED);
        endPointPaint.setStyle(Paint.Style.FILL);
        endPointPaint.setAntiAlias(true);

        testPointPaint = new Paint();
        testPointPaint.setColor(Color.rgb(255, 165, 0)); // Orange
        testPointPaint.setStyle(Paint.Style.FILL);
        testPointPaint.setAntiAlias(true);
    }

    /**
     * Set the trajectory points to be drawn
     */
    public void setTrajectoryPoints(List<double[]> points) {
        this.trajectoryPoints = points;
        if (points != null && !points.isEmpty()) {
            calculateBounds();
        }
        invalidate();
    }

    /**
     * Set test points to be drawn
     */
    public void setTestPoints(List<TestPointData> testPoints) {
        this.testPoints = testPoints;
        // Recalculate bounds to include test points
        if (trajectoryPoints != null && !trajectoryPoints.isEmpty()) {
            calculateBounds();
        }
        invalidate();
    }

    /**
     * Calculate the lat/lng bounds of the trajectory including test points
     */
    private void calculateBounds() {
        if (trajectoryPoints == null || trajectoryPoints.isEmpty()) return;

        minLat = Double.MAX_VALUE;
        maxLat = -Double.MAX_VALUE;
        minLng = Double.MAX_VALUE;
        maxLng = -Double.MAX_VALUE;

        // Include trajectory points in bounds
        for (double[] pt : trajectoryPoints) {
            minLat = Math.min(minLat, pt[0]);
            maxLat = Math.max(maxLat, pt[0]);
            minLng = Math.min(minLng, pt[1]);
            maxLng = Math.max(maxLng, pt[1]);
        }

        // Also include test points in bounds
        if (testPoints != null) {
            for (TestPointData tp : testPoints) {
                minLat = Math.min(minLat, tp.latitude);
                maxLat = Math.max(maxLat, tp.latitude);
                minLng = Math.min(minLng, tp.longitude);
                maxLng = Math.max(maxLng, tp.longitude);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (trajectoryPoints == null || trajectoryPoints.isEmpty()) return;

        int width = getWidth();
        int height = getHeight();
        int padding = 100; // Padding from screen edges

        // Calculate scale and offset to center the trajectory
        double latRange = maxLat - minLat;
        double lngRange = maxLng - minLng;

        if (latRange == 0) latRange = 0.0001;
        if (lngRange == 0) lngRange = 0.0001;

        double scaleX = (width - 2 * padding) / lngRange;
        double scaleY = (height - 2 * padding) / latRange;
        double scale = Math.min(scaleX, scaleY);

        double centerLat = (minLat + maxLat) / 2;
        double centerLng = (minLng + maxLng) / 2;

        // Draw trajectory path
        Path path = new Path();
        boolean first = true;
        for (double[] pt : trajectoryPoints) {
            float x = (float) ((pt[1] - centerLng) * scale + width / 2.0);
            float y = (float) ((centerLat - pt[0]) * scale + height / 2.0); // Invert Y

            if (first) {
                path.moveTo(x, y);
                first = false;
            } else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, pathPaint);

        // Draw start point
        double[] startPt = trajectoryPoints.get(0);
        float startX = (float) ((startPt[1] - centerLng) * scale + width / 2.0);
        float startY = (float) ((centerLat - startPt[0]) * scale + height / 2.0);
        canvas.drawCircle(startX, startY, 15f, startPointPaint);

        // Draw end point
        double[] endPt = trajectoryPoints.get(trajectoryPoints.size() - 1);
        float endX = (float) ((endPt[1] - centerLng) * scale + width / 2.0);
        float endY = (float) ((centerLat - endPt[0]) * scale + height / 2.0);
        canvas.drawCircle(endX, endY, 15f, endPointPaint);

        // Draw test points (larger and more visible)
        if (testPoints != null) {
            for (TestPointData tp : testPoints) {
                float tpX = (float) ((tp.longitude - centerLng) * scale + width / 2.0);
                float tpY = (float) ((centerLat - tp.latitude) * scale + height / 2.0);
                canvas.drawCircle(tpX, tpY, 18f, testPointPaint);  // Increased size for better visibility
            }
        }
    }

    /**
     * Simple data class for test points
     */
    public static class TestPointData {
        public double latitude;
        public double longitude;

        public TestPointData(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
