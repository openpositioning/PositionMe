package com.openpositioning.PositionMe.fragments;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;

import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;

/**
 * Manages multiple indoor map layers (floors) using Google Maps GroundOverlay.
 * Allows switching between floors and hiding all overlays.
 */
public class IndoorMapManager {
    private GoogleMap mMap;
    private GroundOverlay[] groundOverlays; // Array to store GroundOverlay for each floor
    private int currentFloor = 0; // Currently displayed floor index

    /**
     * Constructor for IndoorMapManager.
     *
     * @param map         The GoogleMap instance to overlay images on.
     * @param floorNumber The total number of floors to be managed.
     */
    public IndoorMapManager(GoogleMap map, int floorNumber) {
        this.mMap = map; // Pass in Google Maps
        this.groundOverlays = new GroundOverlay[floorNumber]; // Set the number of floors
    }

    /**
     * Adds a floor overlay to the map.
     *
     * @param floorIndex    The index of the floor (0-based).
     * @param drawableResId Resource ID of the floor image.
     * @param bounds        Geographic bounds for the overlay.
     */
    public void addFloor(int floorIndex, int drawableResId, LatLngBounds bounds) {
        BitmapDescriptor image = BitmapDescriptorFactory.fromResource(drawableResId);
        GroundOverlayOptions groundOverlayOptions = new GroundOverlayOptions()
                .image(image)
                .positionFromBounds(bounds)
                .visible(floorIndex == currentFloor) // Only the current floor is initially visible
                .transparency(0.2f); // Slight transparency for better visibility

        groundOverlays[floorIndex] = mMap.addGroundOverlay(groundOverlayOptions);
    }

    /**
     * Switches the visible floor overlay.
     * Only the selected floor will be visible.
     *
     * @param floorIndex The floor index to switch to.
     */
    public void switchFloor(int floorIndex) {
        if (floorIndex < 0 || floorIndex >= groundOverlays.length) {
            return; // Prevent index out of bounds
        }

        // Hide all floor overlays
        for (GroundOverlay overlay : groundOverlays) {
            if (overlay != null) {
                overlay.setVisible(false);
            }
        }

        // Show the selected floor overlay
        GroundOverlay selectedOverlay = groundOverlays[floorIndex];
        if (selectedOverlay != null) {
            selectedOverlay.setVisible(true);
        }

        currentFloor = floorIndex;
    }

    /**
     * Hides all floor overlays.
     * Useful when switching map modes or disabling indoor view.
     */
    public void hideMap() {
        for (GroundOverlay overlay : groundOverlays) {
            if (overlay != null) {
                overlay.setVisible(false);
            }
        }
    }
}

