package com.openpositioning.PositionMe.utils;

import com.google.android.gms.maps.model.LatLng;

/**
 * Represents a tag placed during recording.
 */
public class Tag {
    private final String id;
    private final long timestamp; // Relative timestamp when tag was placed.
    private final LatLng location;
    private final String label;   // A string combining time, lat, lon.

    public Tag(String id, long timestamp, LatLng location, String label) {
        this.id = id;
        this.timestamp = timestamp;
        this.location = location;
        this.label = label;
    }

    public String getId() {
        return id;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public LatLng getLocation() {
        return location;
    }

    public String getLabel() {
        return label;
    }
}
