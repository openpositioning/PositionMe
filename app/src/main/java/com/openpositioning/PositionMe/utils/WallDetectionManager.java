package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Point;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.openpositioning.PositionMe.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles wall detection from floor plan images and trajectory intersection checking.
 *
 * <p>This class processes floor plan images to extract wall data and provides
 * methods to check if a trajectory would intersect with walls. It supports
 * multiple buildings and floors.
 *
 * @author Nick Manturov
 */
public class WallDetectionManager extends BuildingPolygon{
    private static final String TAG = "WallDetectionManager";
    // Threshold for detecting wall pixels (black lines) - may need tuning
    private static final int WALL_COLOR_THRESHOLD = 220; // Lower values are closer to black

    // Maps to store processed wall data for each floor
    private final Map<Integer, List<xLine>> xWallsMap;
    private final List<xLine> xWalls;
    private final Map<Integer, List<yLine>> yWallsMap;
    private final List<yLine> yWalls;
    // Reference to the parent IndoorMapManager

    private double[] referencePosition;
    // Building type constants
    private static final int BUILDING_NUCLEUS = 1;
    private static final int BUILDING_LIBRARY = 2;

    //Average Floor Heights of the Buildings
    public static final float NUCLEUS_FLOOR_HEIGHT = 4.2F;
    public static final float LIBRARY_FLOOR_HEIGHT = 3.6F;

    private int widthImg;
    private int heightImg;

    public final List<Integer> NUCLEUS_MAPS = Arrays.asList(
            R.drawable.nucleuslg_simplified, R.drawable.nucleusg_simplified, R.drawable.nucleus1_simplified,
            R.drawable.nucleus2_simplified,R.drawable.nucleus3_simplified);
    private double[] ReferenceEastSouth;
    public final List<Integer> LIBRARY_MAPS =Arrays.asList(
            R.drawable.libraryg_simplified);
    private double widthENU;
    private double heightENU;
    private double ENUtoPixRatioWidth;
    private double ENUtoPixRatioHeight;


    /**
     * Constructs a WallDetectionManager with a reference position.
     *
     * @param referencePosition the reference position for coordinate conversion
     */
    public WallDetectionManager(double[] referencePosition) {
        this.xWallsMap = new HashMap<>();
        this.yWallsMap = new HashMap<>();
        this.yWalls = new ArrayList<>();
        this.xWalls = new ArrayList<>();
        this.referencePosition = referencePosition;
        this.heightImg = 0;
        this.widthImg = 0;
        Log.d(TAG, "Wall data initialized successfully");
    }

    /**
     * Initializes wall detection data for all floor plans.
     *
     * @param context Android context to load resources
     */
    public void initializeWallData(Context context) {
        try {
            // Process Nucleus building floor plans
            for (int i = 0; i < NUCLEUS_MAPS.size(); i++) {
                processFloorPlan(context, NUCLEUS_MAPS.get(i), BUILDING_NUCLEUS, i);
            }

            // Process Library building floor plans
            for (int i = 0; i < LIBRARY_MAPS.size(); i++) {
                processFloorPlan(context, LIBRARY_MAPS.get(i), BUILDING_LIBRARY, i);
            }

            Log.d(TAG, "Wall data initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing wall data", e);
        }
    }

    /**
     * Processes a floor plan image to extract wall data.
     *
     * @param context Android context to load resources
     * @param resourceId Resource ID of the floor plan image
     * @param buildingType Type of building (NUCLEUS or LIBRARY)
     * @param floor Floor number
     */
    private void processFloorPlan(Context context, int resourceId, int buildingType, int floor) {
        try {
            // Create new lists for this floor plan
            List<xLine> floorXWalls = new ArrayList<>();
            List<yLine> floorYWalls = new ArrayList<>();

            // Load image as bitmap
            Bitmap floorPlan = BitmapFactory.decodeResource(context.getResources(), resourceId);
            if (floorPlan == null) {
                Log.e(TAG, "Failed to load floor plan: " + resourceId);
                return;
            }

            // Extract wall lines from the bitmap
            extractWallsFromBitmap(floorPlan, buildingType, floor, floorXWalls, floorYWalls);

            // Store the extracted walls with a unique key for building and floor
            xWallsMap.put(generateMapKey(buildingType, floor), floorXWalls);
            yWallsMap.put(generateMapKey(buildingType, floor), floorYWalls);

            // Recycle bitmap to free memory
            floorPlan.recycle();

            Log.d(TAG, "Processed floor plan: " + resourceId + ", extracted " +
                    (floorXWalls.size() + floorYWalls.size()) + " wall segments");
        } catch (Exception e) {
            Log.e(TAG, "Error processing floor plan: " + resourceId, e);
        }
    }

    /**
     * Generates a unique key for the wallsMap.
     *
     * @param buildingType building identifier
     * @param floor floor number
     * @return unique key for map storage
     */
    private int generateMapKey(int buildingType, int floor) {
        return buildingType * 100 + floor;
    }

    /**
     * Extracts wall lines from a bitmap image.
     *
     * @param bitmap the floor plan bitmap
     * @param buildingType type of building (NUCLEUS or LIBRARY)
     * @param floor floor number
     * @param xWallsList list to store horizontal wall lines
     * @param yWallsList list to store vertical wall lines
     */
    private void extractWallsFromBitmap(Bitmap bitmap, int buildingType, int floor,
                                        List<xLine> xWallsList, List<yLine> yWallsList) {

        this.widthImg = bitmap.getWidth();
        this.heightImg = bitmap.getHeight();

        setBounds(buildingType, floor);

        // Create a 2D array to represent wall pixels
        boolean[][] wallPixels = new boolean[this.widthImg][this.heightImg];

        // Identify wall pixels (black lines)
        for (int x = 0; x < this.widthImg; x++) {
            for (int y = 0; y < this.heightImg; y++) {
                int pixel = bitmap.getPixel(x, y);
                int red = Color.red(pixel);
                int green = Color.green(pixel);
                int blue = Color.blue(pixel);

                // Check if pixel is dark enough to be considered a wall
                if (red < WALL_COLOR_THRESHOLD && green < WALL_COLOR_THRESHOLD && blue < WALL_COLOR_THRESHOLD) {
                    wallPixels[x][y] = true;
                }
            }
        }

        // Convert wall pixels to line segments (simplified approach)
        // For horizontal lines
        for (int y = 0; y < this.heightImg; y++) {
            int startX = -1;
            for (int x = 0; x < this.widthImg; x++) {
                if (wallPixels[x][y] && startX == -1) {
                    startX = x;
                } else if (!wallPixels[x][y] && startX != -1) {
                    // End of line segment
                    if (x - startX > 30) { // Only consider lines longer than 15 pixels
                        xWallsList.add(getXLine(startX, x-1, y));
                    }
                    startX = -1;
                }
            }
            // Check if line extends to the edge
            if (startX != -1) {
                xWallsList.add(getXLine(startX, this.widthImg-1, y));
            }
        }

        // For vertical lines
        for (int x = 0; x < this.widthImg; x++) {
            int startY = -1;
            for (int y = 0; y < this.heightImg; y++) {
                if (wallPixels[x][y] && startY == -1) {
                    startY = y;
                } else if (!wallPixels[x][y] && startY != -1) {
                    // End of line segment
                    if (y - startY > 30) { // Only consider lines longer than 5 pixels
                        yWallsList.add(getYLine(startY, y-1, x));
                    }
                    startY = -1;
                }
            }
            // Check if line extends to the edge
            if (startY != -1) {
                yWallsList.add(getYLine(startY, this.heightImg-1, x));
            }
        }
    }

    /**
     * Sets the bounds for the current floor plan.
     *
     * @param buildingType type of building (NUCLEUS or LIBRARY)
     * @param floor floor number
     */
    private void setBounds(int buildingType, int floor) {
        List<LatLng> bounds;
        double altGeo = 0;
        if (buildingType == BUILDING_NUCLEUS) {
            altGeo = (double) floor * NUCLEUS_FLOOR_HEIGHT;
            bounds = NUCLEUS_POLYGON;
        } else {
            altGeo = (double) floor * LIBRARY_FLOOR_HEIGHT;
            bounds = LIBRARY_POLYGON;
        }
        List<double[]> boundsENU = new ArrayList<>();
        for (LatLng bound : bounds) {
            boundsENU.add(CoordinateConverter.convertGeodeticToEnu(bound.latitude, bound.longitude, altGeo,
                    referencePosition[0], referencePosition[1], referencePosition[2]));
        }

        this.widthENU = Math.abs(boundsENU.get(0)[0] - boundsENU.get(2)[0]);
        this.ENUtoPixRatioWidth = widthENU / ((double) this.widthImg);

        this.heightENU = Math.abs(boundsENU.get(0)[1] - boundsENU.get(2)[1]);
        this.ENUtoPixRatioHeight = heightENU / ((double) this.heightImg);

        this.ReferenceEastSouth = boundsENU.get(1);

    }

    /**
     * Checks if a point is in the Nucleus Building.
     *
     * @param point the point to be checked [east, north]
     * @return true if point is in Nucleus building, false otherwise
     */
    public boolean inNucleusENU(double[] point){
        if (point == null) {
            return false;
        }
        return (pointInPolygonENU(point,NUCLEUS_POLYGON));

    }

    /**
     * Checks if a point is in the Library Building.
     *
     * @param point the point to be checked [east, north]
     * @return true if point is in Library building, false otherwise
     */
    public boolean inLibraryENU(double[] point){
        if (point == null) {
            return false;
        }
        return (pointInPolygonENU(point,LIBRARY_POLYGON));
    }

    /**
     * Checks if a point is inside a polygon using ray casting algorithm.
     *
     * <p>Ray casting algorithm: https://en.wikipedia.org/wiki/Point_in_polygon
     * Approximates earth as flat.
     *
     * @param point point to be checked [east, north]
     * @param polygon boundaries of the building
     * @return true if point is in polygon, false otherwise
     */
    private boolean pointInPolygonENU(double[] point, List<LatLng> polygon) {
        int numCrossings = 0;
        List<LatLng> path=polygon;
        List<double[]> boundsENU = new ArrayList<>();
        for (LatLng element : path) {
            boundsENU.add(CoordinateConverter.convertGeodeticToEnu(element.latitude, element.longitude, 78,
                    referencePosition[0], referencePosition[1], referencePosition[2]));
        }
        // For each edge
        for (int i=0; i < boundsENU.size(); i++) {
            double[] a = boundsENU.get(i);
            int j = i + 1;
            // Last edge (includes first point of Polygon)
            if (j >= path.size()) {
                j = 0;
            }
            double[] b = boundsENU.get(j);
            if (crossingSegment(point, a, b)) {
                numCrossings++;
            }
        }

        //if odd number of numCrossings return true (point is in polygon)
        return (numCrossings % 2 == 1);
    }

    /**
     * Ray casting algorithm for a segment joining ab in ENU coordinates.
     *
     * @param point the point we check, represented as {E, N, U}
     * @param a the line segment's starting point, represented as {E, N, U}
     * @param b the line segment's ending point, represented as {E, N, U}
     * @return true if the point is (1) to the left of the segment ab and
     *         (2) not above nor below the segment ab, false otherwise
     */
    private static boolean crossingSegment(double[] point, double[] a, double[] b) {
        double pointE = point[0], pointN = point[1];
        double aE = a[0], aN = a[1];
        double bE = b[0], bN = b[1];

        if (aN > bN) {
            double tempE = aE, tempN = aN;
            aE = bE; aN = bN;
            bE = tempE; bN = tempN;
        }

        // If the point has the same N as a or b, increase slightly pointN
        if (pointN == aN || pointN == bN) pointN += 0.00000001;

        // If the point is above, below, or to the right of the segment, return false
        if ((pointN > bN || pointN < aN) || (pointE > Math.max(aE, bE))) {
            return false;
        }
        // If the point is to the left of both segment endpoints, return true
        else if (pointE < Math.min(aE, bE)) {
            return true;
        }
        // Compare slopes to determine if the point is to the left of segment ab
        else {
            double slope1 = (aE != bE) ? ((bN - aN) / (bE - aE)) : Double.POSITIVE_INFINITY;
            double slope2 = (aE != pointE) ? ((pointN - aN) / (pointE - aE)) : Double.POSITIVE_INFINITY;
            return (slope2 >= slope1);
        }
    }

    /**
     * Creates an xLine from pixel coordinates.
     *
     * @param xStartPix x-start pixel coordinate
     * @param xEndPix x-end pixel coordinate
     * @param yPix y pixel coordinate
     * @return an xLine in ENU coordinates
     */
    private xLine getXLine(double xStartPix, double xEndPix, double yPix) {

        // Interpolate position within the building bounds
        double xStart = this.ReferenceEastSouth[0] + this.ENUtoPixRatioWidth*xStartPix;
        double xEnd = this.ReferenceEastSouth[0] + this.ENUtoPixRatioWidth*xEndPix;
        double y = this.ReferenceEastSouth[1] + this.ENUtoPixRatioHeight*yPix;

        return new xLine(xStart, xEnd, y);

    }

    /**
     * Creates a yLine from pixel coordinates.
     *
     * @param yStartPix y-start pixel coordinate
     * @param yEndPix y-end pixel coordinate
     * @param xPix x pixel coordinate
     * @return a yLine in ENU coordinates
     */
    private yLine getYLine(double yStartPix, double yEndPix, double xPix) {

        // Interpolate position within the building bounds
        double yStart = this.ReferenceEastSouth[1] + this.ENUtoPixRatioHeight*yStartPix;
        double yEnd = this.ReferenceEastSouth[1] + this.ENUtoPixRatioHeight*yEndPix;
        double x = this.ReferenceEastSouth[0] + this.ENUtoPixRatioWidth*xPix;

        return new yLine(yStart, yEnd, x);

    }

    /**
     * Checks if a trajectory intersects any walls.
     *
     * @param start starting point of trajectory [east, north]
     * @param end ending point of trajectory [east, north]
     * @param buildingType building identifier
     * @param floor floor number
     * @return true if the trajectory intersects any wall, false otherwise
     */
    public boolean doesTrajectoryIntersectWall(double[] start, double[] end, int buildingType, int floor) {
        // Get walls for current building and floor
        List<xLine> xWalls = xWallsMap.get(generateMapKey(buildingType, floor));
        List<yLine> yWalls = yWallsMap.get(generateMapKey(buildingType, floor));
        if (xWalls == null || yWalls == null) {
            Log.w(TAG, "No wall data available for building " + buildingType + " floor " + floor);
            return false;
        }

        // Check for intersection with each wall
        for (xLine xWall : xWalls) {
            if (doLinesIntersect(xWall.x1, xWall.y, xWall.x2, xWall.y,
                    start[0], start[1], end[0], end[1])) {
                return true;
            }
        }

        for (yLine yWall : yWalls) {
            if (doLinesIntersect(yWall.x, yWall.y1, yWall.x, yWall.y2,
                    start[0], start[1], end[0], end[1])) {
                return true;
            }
        }

        return false;
    }

    /**
     * Determines if two line segments intersect.
     *
     * <p>Each line is defined by its two endpoints: (x1, y1) -> (x2, y2)
     *
     * @param line1X1 X-coordinate of first endpoint of line 1
     * @param line1Y1 Y-coordinate of first endpoint of line 1
     * @param line1X2 X-coordinate of second endpoint of line 1
     * @param line1Y2 Y-coordinate of second endpoint of line 1
     * @param line2X1 X-coordinate of first endpoint of line 2
     * @param line2Y1 Y-coordinate of first endpoint of line 2
     * @param line2X2 X-coordinate of second endpoint of line 2
     * @param line2Y2 Y-coordinate of second endpoint of line 2
     * @return true if the line segments intersect, false otherwise
     */
    public boolean doLinesIntersect(double line1X1, double line1Y1, double line1X2, double line1Y2,
                                    double line2X1, double line2Y1, double line2X2, double line2Y2) {

        // Calculate the direction vectors
        double line1DirX = line1X2 - line1X1;
        double line1DirY = line1Y2 - line1Y1;
        double line2DirX = line2X2 - line2X1;
        double line2DirY = line2Y2 - line2Y1;

        // Calculate the determinant
        double det = line1DirX * line2DirY - line1DirY * line2DirX;

        // If det is zero, lines are parallel
        if (Math.abs(det) < 1e-10) {
            // Check if they are collinear
            double dx = line2X1 - line1X1;
            double dy = line2Y1 - line1Y1;

            // Cross product should be zero for collinearity
            if (Math.abs(dx * line1DirY - dy * line1DirX) < 1e-10) {
                // Check if there's overlap by projecting onto x-axis or y-axis
                // Choose the axis with the larger variation
                if (Math.abs(line1DirX) > Math.abs(line1DirY)) {
                    // Project onto x-axis
                    double t1 = (line2X1 - line1X1) / line1DirX;
                    double t2 = (line2X2 - line1X1) / line1DirX;
                    return (t1 >= 0 && t1 <= 1) || (t2 >= 0 && t2 <= 1) ||
                            (line2X1 <= line1X1 && line1X1 <= line2X2) ||
                            (line2X1 <= line1X2 && line1X2 <= line2X2);
                } else {
                    // Project onto y-axis
                    double t1 = (line2Y1 - line1Y1) / line1DirY;
                    double t2 = (line2Y2 - line1Y1) / line1DirY;
                    return (t1 >= 0 && t1 <= 1) || (t2 >= 0 && t2 <= 1) ||
                            (line2Y1 <= line1Y1 && line1Y1 <= line2Y2) ||
                            (line2Y1 <= line1Y2 && line1Y2 <= line2Y2);
                }
            }
            return false;
        }

        // Calculate parameters for the point of intersection
        double s = (line1DirX * (line2Y1 - line1Y1) - line1DirY * (line2X1 - line1X1)) / det;
        double t = (line2DirX * (line2Y1 - line1Y1) - line2DirY * (line2X1 - line1X1)) / det;

        // Check if the point of intersection lies within both line segments
        return (s >= 0 && s <= 1 && t >= 0 && t <= 1);
    }

    /**
     * Sets the reference position for coordinate conversion.
     *
     * @param referencePosition new reference position [lat, lng, alt]
     */
    public void setReferencePosition(double[] referencePosition) {
        this.referencePosition = referencePosition;
    }

    /**
     * Horizontal line class to represent wall segments.
     */
    public class xLine {
        double x1, x2, y;

        /**
         * Creates a horizontal line.
         *
         * @param x1 start x-coordinate
         * @param x2 end x-coordinate
         * @param y y-coordinate
         */
        xLine(double x1, double x2, double y) {
            this.x1 = x1;
            this.y = y;
            this.x2 = x2;
        }
    }

    /**
     * Vertical line class to represent wall segments.
     */
    public class yLine {
        double y1, y2, x;

        /**
         * Creates a vertical line.
         *
         * @param y1 start y-coordinate
         * @param y2 end y-coordinate
         * @param x x-coordinate
         */
        yLine(double y1, double y2, double x) {
            this.y1 = y1;
            this.y2 = y2;
            this.x = x;
        }
    }
}