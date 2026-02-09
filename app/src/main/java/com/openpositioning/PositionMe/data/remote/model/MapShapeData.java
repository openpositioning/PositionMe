package com.openpositioning.PositionMe.data.remote.model;

import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed map geometry for one shape in a floorplan level.
 */
public class MapShapeData {
    public enum ShapeType {
        POLYGON,
        POLYLINE
    }

    private final ShapeType shapeType;
    private final List<LatLng> points;
    private final String strokeColor;
    private final String fillColor;
    private final float strokeWidth;

    public MapShapeData(ShapeType shapeType,
                        List<LatLng> points,
                        String strokeColor,
                        String fillColor,
                        float strokeWidth) {
        this.shapeType = shapeType;
        this.points = points == null ? new ArrayList<>() : points;
        this.strokeColor = strokeColor;
        this.fillColor = fillColor;
        this.strokeWidth = strokeWidth;
    }

    public ShapeType getShapeType() {
        return shapeType;
    }

    public List<LatLng> getPoints() {
        return points;
    }

    public String getStrokeColor() {
        return strokeColor;
    }

    public String getFillColor() {
        return fillColor;
    }

    public float getStrokeWidth() {
        return strokeWidth;
    }
}
