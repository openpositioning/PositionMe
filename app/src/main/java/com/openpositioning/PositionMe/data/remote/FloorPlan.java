package com.openpositioning.PositionMe.data.remote;

import java.util.List;

/**
 * Data model representing a single floor plan.
 * Updated to support Vector Data (Walls) from API.
 */
public class FloorPlan {
    private String floorCode; // e.g., "1", "G", "B1"
    private int order;
    private String imageUrl;  // Keep this for compatibility, though API might not send it
    private double[] bounds;

    // 新增：存储墙体线条数据
    // 结构：List of Lines, each Line is a List of Points [Lat, Lon]
    private List<List<List<Double>>> walls;

    public FloorPlan(String floorCode, int order, String imageUrl, double[] bounds, List<List<List<Double>>> walls) {
        this.floorCode = floorCode;
        this.order = order;
        this.imageUrl = imageUrl;
        this.bounds = bounds;
        this.walls = walls;
    }

    public String getFloorCode() { return floorCode; }
    public int getOrder() { return order; }
    public String getImageUrl() { return imageUrl; }
    public double[] getBounds() { return bounds; }
    public List<List<List<Double>>> getWalls() { return walls; }
}