package com.openpositioning.PositionMe.fragments;

import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.GroundOverlay;
import com.openpositioning.PositionMe.R;

/**
 * Manages map functionalities including initialization and configuration
 * of map settings like map type, UI settings, and potentially adding overlays or polylines.
 * @author apoorvtewari
 */
public class MapManager implements OnMapReadyCallback {
    private GoogleMap mMap; // Instance of GoogleMap
    private Fragment fragment; // Fragment in which the map is displayed

    /**
     * Constructor for MapManager.
     * @param fragment The fragment that contains the map.
     */
    public MapManager(Fragment fragment) {
        this.fragment = fragment;
        initializeMap(); // Initialize the map when MapManager is instantiated
    }

    /**
     * Initializes the map by obtaining the SupportMapFragment and setting up the asynchronous map callback.
     */
    private void initializeMap() {
        // Attempt to obtain the map fragment using the fragment manager and fragment ID
        SupportMapFragment mapFragment = (SupportMapFragment) fragment.getChildFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this); // Set up the callback for when the map is ready to be used
        }
    }

    /**
     * Callback method called when the map is ready to be used.
     * @param googleMap The GoogleMap instance that is ready.
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap; // Store the GoogleMap instance
        int savedMapType = GlobalVariables.getMapType(); // Obtain the preferred map type from global settings
        mMap.setMapType(savedMapType); // Set the map type
        configureMapSettings(); // Configure additional map settings
    }

    /**
     * Configures various map settings such as UI controls and gesture settings.
     */
    private void configureMapSettings() {
        // Set various UI settings to enable gestures like compass, tilt, rotate, and scroll
        mMap.setMapType(GlobalVariables.getMapType());
        mMap.getUiSettings().setCompassEnabled(true);
        mMap.getUiSettings().setTiltGesturesEnabled(true);
        mMap.getUiSettings().setRotateGesturesEnabled(true);
        mMap.getUiSettings().setScrollGesturesEnabled(true);
    }

}
