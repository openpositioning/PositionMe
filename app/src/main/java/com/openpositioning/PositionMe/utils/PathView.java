package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import com.openpositioning.PositionMe.presentation.fragment.CorrectionFragment;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.ArrayList;
import java.util.Collections;

/**
 * This View class displays the path taken in the UI.
 * A path of straight lines is drawn based on PDR coordinates. The coordinates are passed to
 * PathView by calling method {@link PathView#drawTrajectory(float[])} in {@link SensorFusion}.
 * The coordinates are scaled and centered in {@link PathView#scaleTrajectory()} to fill the
 * device's screen. The scaling ratio is passed to the {@link CorrectionFragment} for calculating
 * the Google Maps zoom ratio.
 *
 * @author Michal Dvorak
 * @author Virginia Cangelosi
 */
public class PathView extends View {
    // Set up drawing colour
    private final int paintColor = Color.BLUE;
    // Defines paint and canvas
    private Paint drawPaint;
    // Path of straight lines
    private Path path = new Path();
    // 原始 PDR 坐标，始终保持未缩放状态，便于跟随地图缩放重绘
    private static final ArrayList<Float> xCoords = new ArrayList<>();
    private static final ArrayList<Float> yCoords = new ArrayList<>();
    // 基础缩放比例，用于首次让轨迹适配屏幕
    private static float scalingRatio;
    // 用户在校正页输入步长后的累计缩放
    private static float manualScaleFactor = 1f;
    // 地图相对初始 zoom 的缩放倍率
    private static float mapZoomScaleFactor = 1f;
    // Instantiate correction fragment for passing it the scaling ratio
    private CorrectionFragment correctionFragment = new CorrectionFragment();
    // 轨迹点有新增或视图尺寸变化后需要重新计算基础缩放
    private static boolean needsBaseScaleRecompute = true;

    /**
     * Public default constructor for PathView. The constructor initialises the view with a context
     * and attribute set, sets the view as focusable and focusable in touch mode and calls
     * {@link PathView#setupPaint()} to initialise the paint object with colour and style.
     *
     * @param context   Application Context to be used for permissions and device accesses.
     * @param attrs     The attribute set of the view.
     */
    public PathView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setupPaint();
    }

    /**
     * Method used for setting up paint object for drawing the path with colour and stroke styles.
     */
    private void setupPaint() {
        drawPaint = new Paint();
        // Set the color of the paint object to paintColor
        drawPaint.setColor(paintColor);
        // Enable anti-aliasing to smooth out the edges of the lines
        drawPaint.setAntiAlias(true);
        // Set the width of path
        drawPaint.setStrokeWidth(5);
        // Set the style of path to be drawn
        drawPaint.setStyle(Paint.Style.STROKE);
        // Set the type of join to use between line segments
        drawPaint.setStrokeJoin(Paint.Join.ROUND);
        // Set the type of cap to use at the end of the line
        drawPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /**
     * {@inheritDoc}
     *
     * Method drawing the created path with our paint.
     *
     * @param canvas The canvas on which the path will be drawn
     */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (xCoords.isEmpty() || getWidth() == 0 || getHeight() == 0) {
            return;
        }

        ensureBaseScale();

        float effectiveScale = scalingRatio * manualScaleFactor * mapZoomScaleFactor;
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;

        path.reset();
        path.moveTo(centerX + xCoords.get(0) * effectiveScale,
                centerY + yCoords.get(0) * effectiveScale);

        for (int i = 1; i < xCoords.size(); i++) {
            path.lineTo(centerX + xCoords.get(i) * effectiveScale,
                    centerY + yCoords.get(i) * effectiveScale);
        }

        canvas.drawPath(path, drawPaint);
    }

    /**
     * Method called from {@link SensorFusion} used for adding PDR coordinates to the path to be
     * drawn.
     *
     * @param newCords An array containing the newly calculated coordinates to be added.
     */
    public void drawTrajectory(float[] newCords) {
        // Add x coordinates
        xCoords.add(newCords[0]);
        // Negate the y coordinate and add it to the yCoords list, since screen coordinates
        // start from top to bottom
        yCoords.add(-newCords[1]);
        needsBaseScaleRecompute = true;
    }

    /**
     * Method used for scaling PDR coordinates to fill the screen.
     * Center of the view is used as the origin, scaling ratio is calculated for the path to fit
     * the screen with margins included.
     */
    private void scaleTrajectory() {
        // Get the center coordinates of the view
        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;

        // Calculate the scaling that would be required in each direction
        float xRightRange = (getWidth() / 2) / (Math.abs(Collections.max(xCoords)));
        float xLeftRange = (getWidth() / 2) / (Math.abs(Collections.min(xCoords)));
        float yTopRange = (getHeight() / 2) / (Math.abs(Collections.max(yCoords)));
        float yBottomRange = (getHeight() / 2) / (Math.abs(Collections.min(yCoords)));

        // Take the minimum scaling ratio to ensure all points fit within the view
        float minRatio = Math.min(Math.min(xRightRange, xLeftRange), Math.min(yTopRange, yBottomRange));

        // Add margins to the scaling ratio
        scalingRatio = 0.9f * minRatio;

        // Limit scaling ratio to an equivalent of zoom of 21 in google maps
        if (scalingRatio >= 23.926) {
            scalingRatio = 23.926f;
        }
        System.out.println("Adjusted scaling ratio: " + scalingRatio);

        // Set the scaling ratio for the correction fragment for setting Google Maps zoom
        correctionFragment.setScalingRatio(scalingRatio);
        needsBaseScaleRecompute = false;
    }

    public float ensureBaseScale() {
        if (needsBaseScaleRecompute && !xCoords.isEmpty() && getWidth() > 0 && getHeight() > 0) {
            scaleTrajectory();
        }
        return scalingRatio;
    }

    public void setMapZoomScale(float zoomScaleFactor) {
        mapZoomScaleFactor = zoomScaleFactor > 0f ? zoomScaleFactor : 1f;
    }

    /**
     * Method called when PathView is detached from its window. {@link PathView#xCoords} and
     * {@link PathView#yCoords} are cleared so that path can start from 0 for next recording.
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Reset trajectory
        xCoords.clear();
        yCoords.clear();
        scalingRatio = 0f;
        manualScaleFactor = 1f;
        mapZoomScaleFactor = 1f;
        needsBaseScaleRecompute = true;
    }

    /**
     * Redraw trajectory to rescale the path.
     * Called by {@link CorrectionFragment} through {@link SensorFusion} to reset the scaling ratio
     * which will resize the path. It enables the redraw flag so new path is drawn.
     *
     * @param newScale
     */
    public void redraw(float newScale){
        manualScaleFactor *= newScale;
    }

}
