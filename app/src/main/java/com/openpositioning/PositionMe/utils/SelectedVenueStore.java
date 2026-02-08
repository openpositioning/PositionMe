package com.openpositioning.PositionMe.utils;

/**
 * Simple in-memory holder for the currently selected venue metadata.
 */
public class SelectedVenueStore {
    private static final SelectedVenueStore INSTANCE = new SelectedVenueStore();

    private String venueName;
    private int floorIndex;

    private SelectedVenueStore() {}

    public static SelectedVenueStore getInstance() {
        return INSTANCE;
    }

    public void setSelection(String venueName, int floorIndex) {
        this.venueName = venueName;
        this.floorIndex = floorIndex;
    }

    public String getVenueName() {
        return venueName;
    }

    public int getFloorIndex() {
        return floorIndex;
    }
}
