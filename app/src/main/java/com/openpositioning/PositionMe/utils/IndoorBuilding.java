package com.openpositioning.PositionMe.utils;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import java.util.List;
import java.util.Map;

/**
 * Stores building info from API.
 */
public class IndoorBuilding {
    public String id;
    public String name;
    public List<LatLng> polygonPoints; // Building outline
    public LatLngBounds bounds;        // Image coverage bounds
    public Map<Integer, String> floorUrls; // Floor to image URL map (e.g. 0 -> "http://.../g_floor.png")
    public float floorHeight;          // Floor height

    public IndoorBuilding(String id, String name, List<LatLng> polygonPoints, LatLngBounds bounds, Map<Integer, String> floorUrls, float floorHeight) {
        this.id = id;
        this.name = name;
        this.polygonPoints = polygonPoints;
        this.bounds = bounds;
        this.floorUrls = floorUrls;
        this.floorHeight = floorHeight;
    }
}