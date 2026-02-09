package com.openpositioning.PositionMe.utils;

/**
 * Simple in-memory holder for the currently selected venue metadata.
 */
public class SelectedVenueStore {
    private static final SelectedVenueStore INSTANCE = new SelectedVenueStore();

    private String venueName = null;
    private int floorIndex = 0;

    private SelectedVenueStore() {}

    public static SelectedVenueStore getInstance() {
        return INSTANCE;
    }

    public void reset() {
        this.venueName = null;
        this.floorIndex = 0;
    }

    public void setVenueName(String venueName) {
        this.venueName = venueName;
    }

    public String getVenueName() {
        return venueName;
    }

    public void setFloorIndex(int floorIndex) {
        this.floorIndex = floorIndex;
    }

    public int getFloorIndex() {
        return floorIndex;
    }
}
