package com.openpositioning.PositionMe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Custom View: Displays a visual trajectory path.
 * This view draws coordinates (e.g., from PDR) as a connected path and supports scaling to fit the screen.
 */
public class PathView extends View {

    // Drawing color for the path
    private int paintColor = Color.BLUE;

    // Paint object for drawing
    private Paint drawPaint;

    // Path object to draw the trajectory
    private Path path = new Path();

    // Original (unscaled) trajectory coordinates
    private List<Float> xCoords = new ArrayList<>();
    private List<Float> yCoords = new ArrayList<>();

    // Scaling factor for adjusting trajectory to fit the screen
    private float scalingRatio;

    // Control flags for rendering
    private boolean draw = true;
    private boolean reDraw = false;

    // Line thickness
    private float strokeWidth = 5f;

    // Maximum number of track points stored
    private static final int MAX_POINTS = 500;

    // Temporary buffer for raw points added
    private List<PointF> trajectoryPoints = new ArrayList<>();

    /**
     * Constructor: Initializes the PathView with attributes.
     */
    public PathView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setupPaint();
    }

    /**
     * Configure the Paint object used for drawing the path.
     */
    private void setupPaint() {
        if (drawPaint == null) {
            drawPaint = new Paint();
        }
        drawPaint.setColor(paintColor);
        drawPaint.setAntiAlias(true);
        drawPaint.setStrokeWidth(strokeWidth);
        drawPaint.setStyle(Paint.Style.STROKE);
        drawPaint.setStrokeJoin(Paint.Join.ROUND);
        drawPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /**
     * Set the drawing color of the trajectory.
     */
    public void setPaintColor(int color) {
        this.paintColor = color;
        if (drawPaint != null) {
            drawPaint.setColor(color);
        }
    }

    /**
     * Set the stroke width for the path.
     */
    public void setStrokeWidth(float width) {
        this.strokeWidth = width;
        if (drawPaint != null) {
            drawPaint.setStrokeWidth(width);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (xCoords.isEmpty()) return; // Nothing to draw

        path.reset(); // Clear previous path

        if (reDraw) {
            scaleTrajectory(); // Scale trajectory only on redraw
        }

        path.moveTo(xCoords.get(0), yCoords.get(0)); // Start of path

        if (reDraw) {
            for (int i = 1; i < xCoords.size(); i++) {
                float newX = (xCoords.get(i) - getWidth() / 2) * scalingRatio + getWidth() / 2;
                float newY = (yCoords.get(i) - getHeight() / 2) * scalingRatio + getHeight() / 2;
                path.lineTo(newX, newY);
            }
        } else {
            for (int i = 1; i < xCoords.size(); i++) {
                path.lineTo(xCoords.get(i), yCoords.get(i));
            }
        }

        canvas.drawPath(path, drawPaint);

        draw = false;
        reDraw = false;
    }

    /**
     * Add a new point to the trajectory.
     * Y is negated to align with screen coordinates.
     */
    public void drawTrajectory(float[] newCords) {
        if (newCords == null || newCords.length < 2) return;

        if (trajectoryPoints.size() >= MAX_POINTS) {
            trajectoryPoints.remove(0);
        }

        trajectoryPoints.add(new PointF(newCords[0], -newCords[1]));
    }

    /**
     * Scale trajectory points to fit the view's dimensions.
     * Called only during redraw to ensure all points are visible on screen.
     */
    private void scaleTrajectory() {
        if (xCoords.isEmpty() || yCoords.isEmpty()) return;

        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;

        float xRightMax = Math.max(Math.abs(Collections.max(xCoords)), 0.1f);
        float xLeftMax = Math.max(Math.abs(Collections.min(xCoords)), 0.1f);
        float yTopMax = Math.max(Math.abs(Collections.max(yCoords)), 0.1f);
        float yBottomMax = Math.max(Math.abs(Collections.min(yCoords)), 0.1f);

        float xRightRange = (getWidth() / 2f) / xRightMax;
        float xLeftRange = (getWidth() / 2f) / xLeftMax;
        float yTopRange = (getHeight() / 2f) / yTopMax;
        float yBottomRange = (getHeight() / 2f) / yBottomMax;

        float minRatio = Math.min(Math.min(xRightRange, xLeftRange), Math.min(yTopRange, yBottomRange));
        scalingRatio = Math.max(0.5f, Math.min(0.9f * minRatio, 23.926f));

        System.out.println("Adjusted scaling ratio: " + scalingRatio);

        List<Float> scaledXCoords = new ArrayList<>();
        List<Float> scaledYCoords = new ArrayList<>();

        for (int i = 0; i < xCoords.size(); i++) {
            scaledXCoords.add(xCoords.get(i) * scalingRatio + centerX);
            scaledYCoords.add(yCoords.get(i) * scalingRatio + centerY);
        }

        xCoords = scaledXCoords;
        yCoords = scaledYCoords;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (xCoords != null) xCoords.clear();
        if (yCoords != null) yCoords.clear();

        if (!xCoords.isEmpty() || !yCoords.isEmpty()) {
            draw = true;
        }

        drawPaint = null; // Optional: release Paint resources
    }

    /**
     * Force a redraw with a new scaling factor.
     * Useful for zoom-in or zoom-out interactions.
     *
     * @param newScale The new scaling ratio to apply.
     */
    public void redraw(float newScale) {
        if (Math.abs(newScale - scalingRatio) < 0.01f) return;

        if (newScale < 0.1f) {
            scalingRatio = 0.1f;
        } else if (newScale > 23.926f) {
            scalingRatio = 23.926f;
        } else {
            scalingRatio = newScale;
        }

        reDraw = true;
        invalidate(); // Trigger re-render
    }
}

