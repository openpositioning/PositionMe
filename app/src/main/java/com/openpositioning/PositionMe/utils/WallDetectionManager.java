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
 * Class to handle wall detection from floor plan images and trajectory intersection checking
 */
public class WallDetectionManager {
    private static final String TAG = "WallDetectionManager";
    // Threshold for detecting wall pixels (black lines) - may need tuning
    private static final int WALL_COLOR_THRESHOLD = 50; // Lower values are closer to black

    // Map to store processed wall data for each floor
    private final Map<Integer, List<Line>> wallsMap = new HashMap<>();
    // Reference to the parent IndoorMapManager
    private final BuildingPolygon buildingPolygon;

    private double[] referencePosition;
    // Building type constants
    private static final int BUILDING_NUCLEUS = 1;
    private static final int BUILDING_LIBRARY = 2;

    public final List<Integer> NUCLEUS_MAPS = Arrays.asList(
            R.drawable.nucleuslg, R.drawable.nucleusg, R.drawable.nucleus1,
            R.drawable.nucleus2,R.drawable.nucleus3);
    public final List<Integer> LIBRARY_MAPS =Arrays.asList(
            R.drawable.libraryg, R.drawable.library1, R.drawable.library2,
            R.drawable.library3);

    /**
     * Constructor
     * @param referencePosition
     */
    public WallDetectionManager(double[] referencePosition) {
        this.buildingPolygon = new BuildingPolygon();
        this.referencePosition = referencePosition;
    }

    /**
     * Initialize wall detection data for all floor plans
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
     * Process a floor plan image to extract wall data
     * @param context Android context to load resources
     * @param resourceId Resource ID of the floor plan image
     * @param buildingType Type of building (NUCLEUS or LIBRARY)
     * @param floor Floor number
     */
    private void processFloorPlan(Context context, int resourceId, int buildingType, int floor) {
        try {
            // Load image as bitmap
            Bitmap floorPlan = BitmapFactory.decodeResource(context.getResources(), resourceId);
            if (floorPlan == null) {
                Log.e(TAG, "Failed to load floor plan: " + resourceId);
                return;
            }

            // Extract wall lines from the bitmap
            List<Line> walls = extractWallsFromBitmap(floorPlan, buildingType, floor);

            // Store the extracted walls with a unique key for building and floor
            String key = buildingType + "_" + floor;
            wallsMap.put(generateMapKey(buildingType, floor), walls);

            // Recycle bitmap to free memory
            floorPlan.recycle();

            Log.d(TAG, "Processed floor plan: " + resourceId + ", extracted " + walls.size() + " wall segments");
        } catch (Exception e) {
            Log.e(TAG, "Error processing floor plan: " + resourceId, e);
        }
    }

    /**
     * Generate a unique key for the wallsMap
     */
    private int generateMapKey(int buildingType, int floor) {
        return buildingType * 100 + floor;
    }

    /**
     * Extract wall lines from a bitmap image
     * @param bitmap The floor plan bitmap
     * @param buildingType Type of building (NUCLEUS or LIBRARY)
     * @param floor Floor number
     * @return List of Line objects representing walls
     */
    private List<Line> extractWallsFromBitmap(Bitmap bitmap, int buildingType, int floor) {
        List<Line> walls = new ArrayList<>();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        // Create a 2D array to represent wall pixels
        boolean[][] wallPixels = new boolean[width][height];

        // Identify wall pixels (black lines)
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
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
        for (int y = 0; y < height; y++) {
            int startX = -1;
            for (int x = 0; x < width; x++) {
                if (wallPixels[x][y] && startX == -1) {
                    startX = x;
                } else if (!wallPixels[x][y] && startX != -1) {
                    // End of line segment
                    if (x - startX > 5) { // Only consider lines longer than 5 pixels
                        walls.add(new Line(
                                pixelToLatLng(new Point(startX, y), buildingType, floor),
                                pixelToLatLng(new Point(x-1, y), buildingType, floor)
                        ));
                    }
                    startX = -1;
                }
            }
            // Check if line extends to the edge
            if (startX != -1) {
                walls.add(new Line(
                        pixelToLatLng(new Point(startX, y), buildingType, floor),
                        pixelToLatLng(new Point(width-1, y), buildingType, floor)
                ));
            }
        }

        // For vertical lines
        for (int x = 0; x < width; x++) {
            int startY = -1;
            for (int y = 0; y < height; y++) {
                if (wallPixels[x][y] && startY == -1) {
                    startY = y;
                } else if (!wallPixels[x][y] && startY != -1) {
                    // End of line segment
                    if (y - startY > 5) { // Only consider lines longer than 5 pixels
                        walls.add(new Line(
                                pixelToLatLng(new Point(x, startY), buildingType, floor),
                                pixelToLatLng(new Point(x, y-1), buildingType, floor)
                        ));
                    }
                    startY = -1;
                }
            }
            // Check if line extends to the edge
            if (startY != -1) {
                walls.add(new Line(
                        pixelToLatLng(new Point(x, startY), buildingType, floor),
                        pixelToLatLng(new Point(x, height-1), buildingType, floor)
                ));
            }
        }

        return walls;
    }

    /**
     * Convert pixel coordinates to LatLng
     * @param pixel Pixel coordinates
     * @param buildingType Type of building (NUCLEUS or LIBRARY)
     * @param floor Floor number
     * @return LatLng coordinates
     */
    private LatLng pixelToLatLng(Point pixel, int buildingType, int floor) {
        LatLngBounds bounds;
        if (buildingType == BUILDING_NUCLEUS) {
            bounds = indoorMapManager.NUCLEUS;
        } else {
            bounds = indoorMapManager.LIBRARY;
        }

        // Calculate the ratio of the pixel position within the image
        double width = pixel.x;
        double height = pixel.y;

        // Get the bitmap dimensions from the resource
        Bitmap bitmap = BitmapFactory.decodeResource(
                indoorMapManager.gMap.getContext().getResources(),
                buildingType == BUILDING_NUCLEUS ?
                        indoorMapManager.NUCLEUS_MAPS.get(floor) :
                        indoorMapManager.LIBRARY_MAPS.get(floor));

        double imageWidth = bitmap.getWidth();
        double imageHeight = bitmap.getHeight();
        bitmap.recycle();

        // Calculate position ratios (0.0 to 1.0)
        double widthRatio = width / imageWidth;
        double heightRatio = height / imageHeight;

        // Interpolate position within the building bounds
        double lat = bounds.southwest.latitude +
                (bounds.northeast.latitude - bounds.southwest.latitude) * (1 - heightRatio);
        double lng = bounds.southwest.longitude +
                (bounds.northeast.longitude - bounds.southwest.longitude) * widthRatio;

        return new LatLng(lat, lng);
    }

    /**
     * Check if a trajectory intersects any walls
     * @param start Starting point of trajectory
     * @param end Ending point of trajectory
     * @return true if the trajectory intersects any wall, false otherwise
     */
    public boolean doesTrajectoryIntersectWall(LatLng start, LatLng end) {
        // Determine current building and floor
        int buildingType = 0;
        int currentFloor = indoorMapManager.currentFloor;

        if (BuildingPolygon.inNucleus(start)) {
            buildingType = BUILDING_NUCLEUS;
        } else if (BuildingPolygon.inLibrary(start)) {
            buildingType = BUILDING_LIBRARY;
        } else {
            // Not in a building with indoor maps
            return false;
        }

        // Get walls for current building and floor
        List<Line> walls = wallsMap.get(generateMapKey(buildingType, currentFloor));
        if (walls == null || walls.isEmpty()) {
            Log.w(TAG, "No wall data available for building " + buildingType + " floor " + currentFloor);
            return false;
        }

        // Create a line for the trajectory
        Line trajectory = new Line(start, end);

        // Check for intersection with each wall
        for (Line wall : walls) {
            if (linesIntersect(trajectory, wall)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if two line segments intersect
     * @param line1 First line segment
     * @param line2 Second line segment
     * @return true if lines intersect, false otherwise
     */
    private boolean linesIntersect(Line line1, Line line2) {
        // Get points from lines
        LatLng p1 = line1.start;
        LatLng p2 = line1.end;
        LatLng p3 = line2.start;
        LatLng p4 = line2.end;

        // Calculate direction vectors
        double dx1 = p2.longitude - p1.longitude;
        double dy1 = p2.latitude - p1.latitude;
        double dx2 = p4.longitude - p3.longitude;
        double dy2 = p4.latitude - p3.latitude;

        // Calculate determinant
        double denominator = dy2 * dx1 - dx2 * dy1;

        // Lines are parallel if denominator is zero
        if (Math.abs(denominator) < 0.000001) {
            return false;
        }

        // Calculate parameters for parametric line equations
        double a = ((dx2 * (p1.latitude - p3.latitude)) - (dy2 * (p1.longitude - p3.longitude))) / denominator;
        double b = ((dx1 * (p1.latitude - p3.latitude)) - (dy1 * (p1.longitude - p3.longitude))) / denominator;

        // Check if intersection point lies on both line segments
        return a >= 0 && a <= 1 && b >= 0 && b <= 1;
    }

    /**
     * Line class to represent wall segments and trajectories
     */
    private static class Line {
        LatLng start;
        LatLng end;

        Line(LatLng start, LatLng end) {
            this.start = start;
            this.end = end;
        }
    }
}
