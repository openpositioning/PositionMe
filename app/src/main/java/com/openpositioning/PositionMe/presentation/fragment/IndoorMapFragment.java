package com.openpositioning.PositionMe.presentation.fragment;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.BitmapDescriptor;

import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLngBounds;
/**
 * A lightweight helper class for displaying floor-based indoor maps
 * on top of a {@link com.google.android.gms.maps.GoogleMap} instance using Ground Overlays.
 *
 * <p>This class manages a set of {@link com.google.android.gms.maps.model.GroundOverlay}
 * objects representing different floor images. It provides methods to:
 *
 * <ul>
 *   <li>Add bitmap-based floor plans to specific floors with {@link #addFloor(int, int, LatLngBounds)}</li>
 *   <li>Switch visibility between floors using {@link #switchFloor(int)}</li>
 *   <li>Hide all floor overlays using {@link #hideMap()}</li>
 * </ul>
 *
 * <p>This class does not handle dynamic content (e.g., real-time markers or POIs),
 * but is typically used in conjunction with fragments such as
 * {@link com.openpositioning.PositionMe.presentation.fragment.TrajectoryMapFragment}
 * for displaying positioning overlays.
 *
 * <p>Usage example:
 * <pre>{@code
 *     IndoorMapFragment indoorMap = new IndoorMapFragment(map, 3);
 *     indoorMap.addFloor(0, R.drawable.floor_0, bounds);
 *     indoorMap.addFloor(1, R.drawable.floor_1, bounds);
 *     indoorMap.switchFloor(1); // show 1st floor
 * }</pre>
 *
 * @see com.google.android.gms.maps.GoogleMap
 * @see com.google.android.gms.maps.model.GroundOverlay
 * @see com.google.android.gms.maps.model.LatLngBounds
 *
 * @author Mate Stodulka
 * @author Shu Gu
 */

public class IndoorMapFragment {
    private GoogleMap mMap;
    private GroundOverlay[] groundOverlays; // GroundOverlay used to store each layer
    private int currentFloor = 0; // Floor by default

    public IndoorMapFragment(GoogleMap map, int floorNumber) {
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
