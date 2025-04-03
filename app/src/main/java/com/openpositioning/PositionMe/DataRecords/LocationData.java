package com.openpositioning.PositionMe.DataRecords;

import com.google.android.gms.maps.model.LatLng;


/**
 * A simple data wrapper class for storing a {@link LatLng} location.
 * Used in conjunction with {@link LocationHistory} to record and retrieve
 * historical position data.
 *
 * @see LatLng represents a geographic location with latitude and longitude.
 * @see LocationHistory stores a fixed-length list of recent {@link LocationData} entries.
 */
public class LocationData {

    /** Geographic location stored as a {@link LatLng} object */
    private LatLng location;

    /**
     * Constructs a new {@link LocationData} object with the specified location.
     *
     * @param location the geographic location to store
     */
    public LocationData(LatLng location) {
        this.location = location;
    }


    /**
     * Returns the stored location.
     *
     * @return the {@link LatLng} location
     */
    public LatLng getLocation() {
        return location;
    }
}
