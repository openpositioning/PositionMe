package com.openpositioning.PositionMe.data.remote.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed floor-level data for a venue.
 */
public class FloorplanLevel {
    private final String levelId;
    private final List<MapShapeData> shapes;

    public FloorplanLevel(String levelId, List<MapShapeData> shapes) {
        this.levelId = levelId;
        this.shapes = shapes == null ? new ArrayList<>() : shapes;
    }

    public String getLevelId() {
        return levelId;
    }

    public List<MapShapeData> getShapes() {
        return shapes;
    }
}
