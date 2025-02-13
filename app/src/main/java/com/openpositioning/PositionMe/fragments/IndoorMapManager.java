package com.openpositioning.PositionMe.fragments;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;

import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;

public class IndoorMapManager {
    private GoogleMap mMap;
    private GroundOverlay[] groundOverlays; // GroundOverlay used to store each layer
    private int currentFloor = 0; // Floor by default

    public IndoorMapManager(GoogleMap map, int floorNumber) {
        this.mMap = map; // Pass in Google Maps
        this.groundOverlays = new GroundOverlay[floorNumber]; // Set the number of floors
    }

    // Used to add floors
    public void addFloor(int floorIndex, int drawableResId, LatLngBounds bounds) {
        BitmapDescriptor image = BitmapDescriptorFactory.fromResource(drawableResId);
        GroundOverlayOptions groundOverlayOptions = new GroundOverlayOptions()
                .image(image)
                .positionFromBounds(bounds)
                .visible(floorIndex == currentFloor)
                .transparency(0.2f);

        groundOverlays[floorIndex] = mMap.addGroundOverlay(groundOverlayOptions);
    }

    // Switch floors and make sure only one floor is displayed
    public void switchFloor(int floorIndex) {
        if (floorIndex < 0 || floorIndex >= groundOverlays.length) {
            return; // Prevent index out of bounds
        }
        // Hide all floors
        for (GroundOverlay overlay : groundOverlays) {
            if (overlay != null) {
                overlay.setVisible(false);
            }
        }
        // Show selected floor
        GroundOverlay selectedOverlay = groundOverlays[floorIndex];
        if (selectedOverlay != null) {
            selectedOverlay.setVisible(true);
        }
        currentFloor = floorIndex;
    }

    // Hide all floors
    public void hideMap() {
        //Hide all floors
        for (GroundOverlay overlay : groundOverlays) {
            if (overlay != null) {
                overlay.setVisible(false);
            }
        }
    }
}
