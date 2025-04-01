package com.openpositioning.PositionMe.DataRecords;

import com.google.android.gms.maps.model.LatLng;

public class LocationData {
    private LatLng location;

    public LocationData(LatLng location) {
        this.location = location;
    }


    public LatLng getLocation() {
        return location;
    }
}
