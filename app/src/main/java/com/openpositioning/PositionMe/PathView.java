package com.openpositioning.PositionMe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import com.openpositioning.PositionMe.fragments.CorrectionFragment;
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
    // Array lists of integers to store coordinates
    private static ArrayList<Float> xCoords = new ArrayList<Float>();
    private static ArrayList<Float> yCoords = new ArrayList<Float>();
    // Scaling ratio for multiplying PDR coordinates to fill the screen size
    private static float scalingRatio;
    // Instantiate correction fragment for passing it the scaling ratio
    private CorrectionFragment correctionFragment = new CorrectionFragment();
    // Boolean flag to avoid rescaling trajectory when view is redrawn
    private static boolean firstTimeOnDraw = true;
    //Variable to only draw when the variable is true
    private static boolean draw = true;
    //Variable to only draw when the variable is true
    private static boolean reDraw = false;

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
        //If drawing for first time scale trajectory to fit screen
        if(this.draw){
            // If there are no coordinates, don't draw anything
            if (xCoords.size() == 0)
                return;

            //Scale trajectory to fit screen
            scaleTrajectory();

            // Start a new path at the center of the view
            path.moveTo(getWidth()/2, getHeight()/2);

            // Draw line between last point and this point
            for (int i = 1; i < xCoords.size(); i++) {
                path.lineTo(xCoords.get(i), yCoords.get(i));
            }

            //Draw path
            canvas.drawPath(path, drawPaint);

            //Ensure path not redrawn
            draw = false;

        }
        //If redrawing due to scaling of the average step length
        else if(reDraw){
            // If there are no coordinates, don't draw anything
            if (xCoords.size() == 0)
                return;

            //Clear old path
            path.reset();

            // Iterate over all coordinates, shifting to the center and scaling then returning to original location
            for (int i = 0; i < xCoords.size(); i++) {
                float newXCoord = (xCoords.get(i) - getWidth()/2) * scalingRatio + getWidth()/2;
                xCoords.set(i, newXCoord);
                float newYCoord = (yCoords.get(i) - getHeight()/2) * scalingRatio + getHeight()/2;
                yCoords.set(i, newYCoord);
            }

            // Start a new path at the center of the view
            path.moveTo(getWidth()/2, getHeight()/2);

            // Draw line between last point and this point
            for (int i = 1; i < xCoords.size(); i++) {
                path.lineTo(xCoords.get(i), yCoords.get(i));
            }

            canvas.drawPath(path, drawPaint);

            //Ensure path not redrawn when screen is resized
            reDraw = false;
        }
        else{

            // If there are no coordinates, don't draw anything
            if (xCoords.size() == 0)
                return;

            // Start a new path at the center of the view
            path.moveTo(getWidth()/2, getHeight()/2);

            // Draw line between last point and this point
            for (int i = 1; i < xCoords.size(); i++) {
                path.lineTo(xCoords.get(i), yCoords.get(i));
            }

            canvas.drawPath(path, drawPaint);
        }
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

        // Iterate over all coordinates, shifting to the center and scaling
        for (int i = 0; i < xCoords.size(); i++) {
            float newXCoord = xCoords.get(i) * scalingRatio + centerX;
            xCoords.set(i, newXCoord);
            float newYCoord = yCoords.get(i) * scalingRatio + centerY;
            yCoords.set(i, newYCoord);
        }
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
        //New recording so must scale trajectory
        draw = true;
    }

    /**
     * Redraw trajectory to rescale the path.
     * Called by {@link CorrectionFragment} through {@link SensorFusion} to reset the scaling ratio
     * which will resize the path. It enables the redraw flag so new path is drawn.
     *
     * @param newScale
     */
    public void redraw(float newScale){
        //Set scaling ratio based on user input
        scalingRatio = newScale;
        //Enable redrawing of path
        reDraw = true;
    }

}
