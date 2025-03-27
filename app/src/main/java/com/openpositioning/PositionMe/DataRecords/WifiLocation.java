package com.openpositioning.PositionMe.DataRecords;

import com.google.android.gms.maps.model.LatLng;

public class WifiLocation {
    private long timestamp;
    private LatLng location;
    private int floor;

    public WifiLocation(long timestamp, LatLng location, int floor) {
        this.timestamp = timestamp;
        this.location = location;
        this.floor = floor;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public LatLng getLocation() {
        return location;
    }

    public int getFloor() {
        return floor;
    }
}
