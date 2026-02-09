package com.openpositioning.PositionMe.data.remote.model;

import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed venue with campaign ID, venue outline and floor levels.
 */
public class FloorplanVenue {
    private final String campaign;
    private final List<LatLng> outline;
    private final List<FloorplanLevel> levels;

    public FloorplanVenue(String campaign, List<LatLng> outline, List<FloorplanLevel> levels) {
        this.campaign = campaign;
        this.outline = outline == null ? new ArrayList<>() : outline;
        this.levels = levels == null ? new ArrayList<>() : levels;
    }

    public String getCampaign() {
        return campaign;
    }

    public List<LatLng> getOutline() {
        return outline;
    }

    public List<FloorplanLevel> getLevels() {
        return levels;
    }
}
