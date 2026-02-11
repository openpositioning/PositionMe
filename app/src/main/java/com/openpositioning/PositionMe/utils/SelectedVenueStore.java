package com.openpositioning.PositionMe.utils;

/**
 * Simple in-memory holder for the currently selected venue metadata.
 */
public class SelectedVenueStore {
    private static final SelectedVenueStore INSTANCE = new SelectedVenueStore();

    private String venueName = null;
    private int floorIndex = 0;

    private SelectedVenueStore() {}

    /**
     * Global access point for the singleton instance.
     * @return instance of SelectedVenueStore
     */
    public static SelectedVenueStore getInstance() {
        return INSTANCE;
    }

    /**
     * Resets current venue and floor
     */
    public void reset() {
        this.venueName = null;
        this.floorIndex = 0;
    }

    /**
     *
     * @param venueName
     */
    public void setVenueName(String venueName) {
        this.venueName = venueName;
    }

    /**
     *
     * @return venueName
     */
    public String getVenueName() {
        return venueName;
    }

    /**
     *
     * @param floorIndex
     */
    public void setFloorIndex(int floorIndex) {
        this.floorIndex = floorIndex;
    }

    /**
     *
     * @return
     */
    public int getFloorIndex() {
        return floorIndex;
    }
}
