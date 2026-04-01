package com.openpositioning.PositionMe.data.remote;

import java.util.List;

/**
 * Data model representing a single floor plan.
 */
public class FloorPlan {
    private String floorCode; // e.g., "1", "G", "B1"
    private int order;
    private String imageUrl;  // Kept for compatibility with older APIs.
    private double[] bounds;

    private List<List<List<Double>>> walls;
    // Indoor stair geometry.
    private List<List<List<Double>>> stairs;
    // Indoor lift geometry.
    private List<List<List<Double>>> lifts;

    public FloorPlan(String floorCode,
                     int order,
                     String imageUrl,
                     double[] bounds,
                     List<List<List<Double>>> walls,
                     List<List<List<Double>>> stairs,
                     List<List<List<Double>>> lifts) {
        this.floorCode = floorCode;
        this.order = order;
        this.imageUrl = imageUrl;
        this.bounds = bounds;
        this.walls = walls;
        this.stairs = stairs;
        this.lifts = lifts;
    }

    public String getFloorCode() { return floorCode; }
    public int getOrder() { return order; }
    public String getImageUrl() { return imageUrl; }
    public double[] getBounds() { return bounds; }
    public List<List<List<Double>>> getWalls() { return walls; }
    // Returns stair geometry for the floor.
    public List<List<List<Double>>> getStairs() { return stairs; }
    // Returns lift geometry for the floor.
    public List<List<List<Double>>> getLifts() { return lifts; }
}
