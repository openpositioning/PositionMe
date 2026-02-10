package com.openpositioning.PositionMe.data.remote;

import java.util.List;

/**
 * Data model representing a Building.
 * Contains the building's metadata, outline (polygon), and a list of floor plans.
 */
public class Building {
    private String id;
    private String name;
    // Outline points: List of [Latitude, Longitude]
    private List<List<Double>> outline;
    private List<FloorPlan> floors;

    public Building(String id, String name, List<List<Double>> outline, List<FloorPlan> floors) {
        this.id = id;
        this.name = name;
        this.outline = outline;
        this.floors = floors;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public List<List<Double>> getOutline() { return outline; }
    public List<FloorPlan> getFloors() { return floors; }
}