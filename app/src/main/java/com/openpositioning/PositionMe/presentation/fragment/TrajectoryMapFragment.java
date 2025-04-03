package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Color;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;

import com.google.android.gms.maps.Projection;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.OnMapReadyCallback;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.presentation.viewitems.SensorDataViewModel;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.BuildingPolygon;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.*;

import java.util.ArrayList;
import java.util.List;

import androidx.lifecycle.ViewModelProvider;


/**
 * A fragment responsible for displaying a trajectory map using Google Maps.
 * <p>
 * The TrajectoryMapFragment provides a map interface for visualizing movement trajectories,
 * GNSS tracking, and indoor mapping. It manages map settings, user interactions, and real-time
 * updates to user location and GNSS markers.
 * <p>
 * Key Features:
 * - Displays a Google Map with support for different map types (Hybrid, Normal, Satellite).
 * - Tracks and visualizes user movement using polylines.
 * - Supports GNSS position updates and visual representation.
 * - Includes indoor mapping with floor selection and auto-floor adjustments.
 * - Allows user interaction through map controls and UI elements.
 *
 * @see RecordingActivity The activity hosting this fragment.
 * @see IndoorMapManager Utility for managing indoor map overlays.
 * @see UtilFunctions Utility functions for UI and graphics handling.
 *
 * @author Mate Stodulka
 * @author Stone Anderson
 * @author Sofea Jazlan Arif
 */

public class TrajectoryMapFragment extends Fragment {

    private GoogleMap gMap; // Google Maps instance
    private LatLng currentLocation; // Stores the user's current location
    private Marker pdrMarker; // Marker representing user's heading
    private Marker gnssMarker; // GNSS position marker
    private Marker wifiMarker; // WIFI position marker
    private Marker ekfMarker;
    private Marker particleMarker;
    private ImageView deleteTagButton;
    private Marker selectedMarker;
    private Polyline polyline; // Polyline representing user's movement path
    private boolean isRed = true; // Tracks whether the polyline color is red
    private boolean isGnssOn = true; // Tracks if GNSS tracking is enabled
    private boolean isWifiOn = true; // Tracks if WiFi tracking is enabled 
    private boolean isPdrOn = true; // Tracks if PDR tracking is enabled

    private Polyline gnssPolyline; // Polyline for GNSS path
    private Polyline pdrPolyline;

    private Polyline ekfPolyline;
    private Polyline particlePolyline;
    private Polyline wifiPolyline; // Polyline for Wifi path
    private LatLng lastGnssLocation = null; // Stores the last GNSS location
    private LatLng lastWifiLocation = null; // Stores the last Wifi location
    private int wifiFloor;
    private boolean isReplayMode = false;

    private boolean hasReplayWifiData = true;


    private LatLng lastEKFLocation = null;
    private LatLng lastParticleLocation = null;

    private LatLng pendingCameraPosition = null; // Stores pending camera movement
    private boolean hasPendingCameraMove = false; // Tracks if camera needs to move
    
    // List to store tag markers
    private List<Marker> tagMarkers = new ArrayList<>();
    private List<MarkerOptions> pendingMarkers = new ArrayList<>();

    private IndoorMapManager indoorMapManager; // Manages indoor mapping
    private SensorFusion sensorFusion;


    // UI
    private Spinner switchMapSpinner;

    private SwitchMaterial gnssSwitch;

    private SwitchMaterial wifiSwitch;
    private SwitchMaterial pdrSwitch;
    private SwitchMaterial autoFloorSwitch;

    private FloatingActionButton floorUpButton, floorDownButton;
    private Polygon buildingPolygon;

    FloatingActionButton toggleButton;
    MaterialCardView controlPanel;
    boolean isPanelVisible = false; // Tracks visibility state
    private SensorDataViewModel sensorDataViewModel;

    private static final int MAX_POLYLINE_POINT = 30; // CHANGE THIS AS NEEDED ~


    public TrajectoryMapFragment() {
        // Required empty public constructor
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Inflate the separate layout containing map + map-related UI
        return inflater.inflate(R.layout.fragment_trajectory_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);



        // Grab references to UI controls
        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch      = view.findViewById(R.id.gnssSwitch);
        wifiSwitch      = view.findViewById(R.id.wifiSwitch);
        pdrSwitch      = view.findViewById(R.id.pdrSwitch);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        deleteTagButton = new ImageView(requireContext());
        deleteTagButton.setImageResource(R.drawable.ic_trash); // Use a delete icon
        deleteTagButton.setVisibility(View.GONE); // Initially hide
        deleteTagButton.setLayoutParams(new ViewGroup.LayoutParams(100, 100)); // Size in pixels

        // Add the button to the parent layout
        ((ViewGroup) view).addView(deleteTagButton);

        // Set click listener for deleting tag
        deleteTagButton.setOnClickListener(v -> removeSelectedTag());

        // References for dropdown menu components
        FloatingActionButton togglePanelButton = view.findViewById(R.id.togglePanelButton);
        MaterialCardView controlPanel = view.findViewById(R.id.controlPanel);

        // Setup floor up/down UI hidden initially until we know there's an indoor map
        setFloorControlsVisibility(View.GONE);

        // Ultra simple setup with minimal overhead
        controlPanel.setVisibility(View.GONE); // Start hidden
        
        // Simple toggle with minimal processing
        togglePanelButton.setOnClickListener(v -> {
            // Check current visibility state
            boolean isVisible = controlPanel.getVisibility() == View.VISIBLE;
            
            if (isVisible) {
                // If visible, just hide it immediately
                controlPanel.setVisibility(View.GONE);
            } else {
                // If hidden, show it with minimal delay
                showControlPanel(controlPanel, togglePanelButton);
                
                // Auto-hide after 8 seconds
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    controlPanel.setVisibility(View.GONE);
                }, 8000);
            }
        });

        /**
         * No Coverage Detection for both wifi and gnss
         * Controlling wifi and gnss switch based on data availability
         * @author Stone Anderson
         */
        // To track wifi
        sensorDataViewModel = new ViewModelProvider(requireActivity()).get(SensorDataViewModel.class);

        // Observe WiFi status changes
        sensorDataViewModel.getHasWifiData().observe(getViewLifecycleOwner(), hasWifi -> {
            if (hasWifi) {      setWifiSwitchEnabled(true);
                setAutoFloorSwitchEnabled(true);
            } else {            setWifiSwitchEnabled(false);
                setAutoFloorSwitchEnabled(false);}
        });

        // Observe GNSS location
        sensorDataViewModel.getHasGnssData().observe(getViewLifecycleOwner(), hasGnss -> {
            if (hasGnss) {      setGnssSwitchEnabled(true);
            } else {            setGnssSwitchEnabled(false);
                Log.d("GNSS", "onMapReady: Map is ready!");
            }
        });

        // Initialize the map asynchronously
        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.trajectoryMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(new OnMapReadyCallback() {
                @Override
                public void onMapReady(@NonNull GoogleMap googleMap) {
                    // Assign the provided googleMap to your field variable
                    gMap = googleMap;
                    // Initialize map settings with the now non-null gMap
                    initMapSettings(gMap);

                    // If we had a pending camera move, apply it now
                    if (hasPendingCameraMove && pendingCameraPosition != null) {
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
                        hasPendingCameraMove = false;
                        pendingCameraPosition = null;
                    }

                    drawBuildingPolygon();
                    
                    // Add building outlines for indoor maps
                    if (indoorMapManager != null) {
                        indoorMapManager.setIndicationOfIndoorMap();
                    }

                    Log.d("TrajectoryMapFragment", "onMapReady: Map is ready!");

                    processPendingMarkers(); // Add any markers that were queued

                    gMap.setOnMarkerClickListener(marker -> {
                        if (tagMarkers.contains(marker)) { // Only show for tag markers
                            selectedMarker = marker;
                            showDeleteButton(marker);
                            return true;
                        }
                        return false;
                    });

                    // Hide delete button when clicking outside markers
                    gMap.setOnMapClickListener(latLng -> {
                        deleteTagButton.setVisibility(View.GONE);
                        selectedMarker = null;
                    });

                }
            });
        }

        // Map type spinner setup
        initMapTypeSpinner();


        /**
         * Functionality for switches for wifi, gnss and pdr
         * @author Stone Anderson, Semih Vazgecen
         */

        // If no Wifi in the trajectory replayed, disable the switches
        if (isReplayMode && !hasReplayWifiData) {
            setWifiSwitchEnabled(false);
            setAutoFloorSwitchEnabled(false);
        }
        
        // Set initial switch states to match default boolean values
        if (gnssSwitch != null) gnssSwitch.setChecked(isGnssOn);
        if (wifiSwitch != null) wifiSwitch.setChecked(isWifiOn);
        if (pdrSwitch != null) pdrSwitch.setChecked(isPdrOn);
        if (autoFloorSwitch != null) autoFloorSwitch.setChecked(true); // Enable auto floor by default
        // Semih - END


        // Wifi Switch
        wifiSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isWifiOn = isChecked;
            if (wifiMarker != null) {
                wifiMarker.setVisible(isChecked);
                wifiPolyline.setVisible(isChecked);
            }
        });

        // PDR switch
        pdrSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isPdrOn = isChecked;
            if (pdrMarker != null) {
                pdrMarker.setVisible(isChecked);
                pdrPolyline.setVisible(isChecked);
            }
        });

        // GNSS switch
        gnssSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isGnssOn = isChecked;
            if (gnssMarker != null) {
                gnssMarker.setVisible(isChecked);
                gnssPolyline.setVisible(isChecked);
            }
        });

        /**
         * Functionality for map matching for floors
         * @author Semih Vazgecen
         */
        // Set the floor view based on the floor data captured from Wifi positioning
        autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (isChecked) {
                indoorMapManager.setCurrentFloor(wifiFloor, true);

            } else {
                wifiFloor = 0;
                indoorMapManager.setCurrentFloor(wifiFloor, true);
            }
        });

        // Floor up/down logic
        floorUpButton.setOnClickListener(v -> {
            // If user manually changes floor, turn off auto floor
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
            }
        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
            }
        });
    }

    /**
     * Displays the control panel with a staged approach to minimize UI thread lag.
     * 
     * This method implements a simplified, low-overhead approach to showing the control panel
     * by avoiding animations and complex transitions that might cause lag on the UI thread.
     * The panel is first set to invisible (but present in the layout) and then made visible
     * after a small delay, allowing the UI thread to process the change in stages.
     * 
     * @param controlPanel The MaterialCardView control panel to be displayed
     * @param toggleButton The FloatingActionButton that toggles the panel (not used directly
     *                    in this implementation, but maintained for interface compatibility)
     *                    
     * @author Semih Vazgecen
     */
    private void showControlPanel(MaterialCardView controlPanel, FloatingActionButton toggleButton) {
        // First just make the panel invisible but present (no animation)
        controlPanel.setVisibility(View.INVISIBLE);
        
        // Wait for the next UI cycle before showing the panel
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // Simply make it visible - no animation at all to avoid lag
            controlPanel.setVisibility(View.VISIBLE);
        }, 50); // 50ms delay is barely noticeable but gives the UI thread time to process
    }

    /**
     * Hides the control panel with a direct approach to minimize UI thread lag.
     * 
     * This method implements a zero-overhead approach to hiding the control panel
     * by immediately setting it to GONE without any animations or transitions.
     * This approach avoids potential lag on the UI thread that might occur with
     * more complex transitions, especially on lower-end devices.
     * 
     * @param controlPanel The MaterialCardView control panel to be hidden
     * @param toggleButton The FloatingActionButton that toggles the panel (not used directly
     *                    in this implementation, but maintained for interface compatibility)
     *                    
     * @author Semih Vazgecen
     */
    private void hideControlPanel(MaterialCardView controlPanel, FloatingActionButton toggleButton) {
        // Just hide it immediately - no animation to avoid lag
        controlPanel.setVisibility(View.GONE);
    }
    // Semih - END

    /**
     * Initialize the map settings with the provided GoogleMap instance.
     * <p>
     *     The method sets basic map settings, initializes the indoor map manager,
     *     and creates an empty polyline for user movement tracking.
     *     The method also initializes the GNSS polyline for tracking GNSS path.
     *     The method sets the map type to Hybrid and initializes the map with these settings.
     *
     * @param map
     * @author Stone Anderson - added aditional (i.e. pdr, Wifi, EKF) polylines
     */

    private void initMapSettings(GoogleMap map) {
        // Basic map settings
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        // Initialize indoor manager
        indoorMapManager = new IndoorMapManager(map);



        // Initialize Pdr polyline
        pdrPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(5f)
                .zIndex(2f)
                .add() // start empty
        );


        // GNSS path in blue
        gnssPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.BLUE)
                .width(5f)
                .zIndex(2f)
                .add() // start empty
        );

        // ekf path in gray
        ekfPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.GREEN)
                .width(5f)
                .zIndex(2f)
                .add() // start empty
        );

        // particle path in gray
        particlePolyline = map.addPolyline(new PolylineOptions()
                .color(Color.rgb(255, 165,0))
                .width(5f)
                .zIndex(2f)
                .add() // start empty
        );
        particlePolyline.setVisible(false);



        // Wifi path in yellow
        wifiPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.YELLOW)
                .width(5f)
                .zIndex(2f)
                .add() // start empty
        );


    }


    /**
     * Initialize the map type spinner with the available map types.
     * <p>
     *     The spinner allows the user to switch between different map types
     *     (e.g. Hybrid, Normal, Satellite) to customize their map view.
     *     The spinner is populated with the available map types and listens
     *     for user selection to update the map accordingly.
     *     The map type is updated directly on the GoogleMap instance.
     *     <p>
     *         Note: The spinner is initialized with the default map type (Hybrid).
     *         The map type is updated on user selection.
     *     </p>
     * </p>
     *     @see GoogleMap The GoogleMap instance to update map type.
     */
    private void initMapTypeSpinner() {
        if (switchMapSpinner == null) return;
        String[] maps = new String[]{
                getString(R.string.hybrid),
                getString(R.string.normal),
                getString(R.string.satellite)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                maps
        );
        switchMapSpinner.setAdapter(adapter);

        switchMapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                if (gMap == null) return;
                switch (position){
                    case 0:
                        gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                    case 1:
                        gMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                        break;
                    case 2:
                        gMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                        break;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /**
     * Update the user's current location on the map, create or move orientation marker,
     * and append to pdrPolyline if the user actually moved.
     *
     * @param newLocation The new location to plot.
     * @param orientation The user’s heading (e.g. from sensor fusion).
     */
    public void updateUserLocation(@NonNull LatLng newLocation, float orientation) {
        if (gMap == null) return;
        if(isPdrOn)
        {
            pdrPolyline.setVisible(true);
            if (pdrMarker != null) {
                pdrMarker.setVisible(true);
            }
        }
        else {
            pdrPolyline.setVisible(false);
            if (pdrMarker != null) {
                pdrMarker.setVisible(false);
            }
        }

        // Keep track of current location
        LatLng oldLocation = this.currentLocation;
        this.currentLocation = newLocation;

        // If no marker, create it
        if (pdrMarker == null) {
            pdrMarker = gMap.addMarker(new MarkerOptions()
                    .position(newLocation)
                    .flat(true)
                    .title("Current Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_25)))
            );
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
        } else {
            // Update marker position + orientation
            pdrMarker.setPosition(newLocation);
            pdrMarker.setRotation(orientation);
            // Move camera a bit
            gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
        }

        // Extend pdrPolyline if movement occurred
        if (oldLocation != null && !oldLocation.equals(newLocation) && pdrPolyline != null) {
            List<LatLng> points = new ArrayList<>(pdrPolyline.getPoints());
            points.add(newLocation);
            pdrPolyline.setPoints(points);
        }
    }



    /**
     * Set the initial camera position for the map.
     * <p>
     *     The method sets the initial camera position for the map when it is first loaded.
     *     If the map is already ready, the camera is moved immediately.
     *     If the map is not ready, the camera position is stored until the map is ready.
     *     The method also tracks if there is a pending camera move.
     * </p>
     * @param startLocation The initial camera position to set.
     */
    public void setInitialCameraPosition(@NonNull LatLng startLocation) {
        // If the map is already ready, move camera immediately
        if (gMap != null) {
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLocation, 19f));
        } else {
            // Otherwise, store it until onMapReady
            pendingCameraPosition = startLocation;
            hasPendingCameraMove = true;
        }
    }


    /**
     * Get the current user location on the map.
     * @return The current user location as a LatLng object.
     */
    public LatLng getCurrentLocation() {
        return currentLocation;
    }

    /**
     * Called when we want to set or update the GNSS marker position
     */
    public void updateGNSS(@NonNull LatLng gnssLocation) {
        Log.d("TrajectoryMapFragmentGNN", "GNSS UPDATED" );
        if (gMap == null) return;

        if (gnssMarker == null) {
            // Create the GNSS marker for the first time
            gnssMarker = gMap.addMarker(new MarkerOptions()
                    .position(gnssLocation)
                    .title("GNSS Position")
                    .visible(isGnssOn) // Respect the current switch state
                    .icon(BitmapDescriptorFactory
                            .defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
            lastGnssLocation = gnssLocation;
        } else {
            Log.d("TrajectoryMapFragment", "New Gnss update received: " + gnssLocation);
            // Move existing GNSS marker
            gnssMarker.setPosition(gnssLocation);
            gnssMarker.setVisible(isGnssOn); // Ensure visibility matches switch state
        }

        // Add a segment to the blue GNSS line, if this is a new location
        if (lastGnssLocation != null && !lastGnssLocation.equals(gnssLocation)) {
            List<LatLng> gnssPoints = new ArrayList<>(gnssPolyline.getPoints());
            gnssPoints.add(gnssLocation);
            gnssPolyline.setPoints(gnssPoints);
        }
        
        // Update visibility of polyline
        gnssPolyline.setVisible(isGnssOn);
        
        lastGnssLocation = gnssLocation;
    }

    /**
     *
     *
     * @param ekfLocation The LatLng EKF estimated position
     * @param orientation The orientation in degrees (0-360) for the marker's heading
     *
     * @author Stone Anderson and Sofea Jazlan Arif
     */
    public void updateEKF(@NonNull LatLng ekfLocation, float orientation) {
        if (gMap == null) return;

        if (ekfMarker == null) {
            // Create the EKF marker the first time using a different color for clarity
            ekfMarker = gMap.addMarker(new MarkerOptions()
                    .position(ekfLocation)
                    .title("EKF Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_24)))
            );
            lastEKFLocation = ekfLocation;
        } else  // if ekfMarker already exists
        {
            Log.d("TrajectoryMapFragment", "New EKF update received: " + ekfLocation);
            // Update marker position
            ekfMarker.setPosition(ekfLocation);
            ekfMarker.setRotation(orientation);

            // change view based on ekf location
            gMap.moveCamera(CameraUpdateFactory.newLatLng(ekfLocation));

            // Add a segment to the EKF polyline if the location has changed
            if (lastEKFLocation != null && !lastEKFLocation.equals(ekfLocation)) {
                List<LatLng> ekfPoints = new ArrayList<>(ekfPolyline.getPoints());

                // only plot last N points
                if (ekfPoints.size() >= MAX_POLYLINE_POINT) {
                    ekfPoints.remove(0);
                }
                for (LatLng point : ekfPoints) {
                    Log.d("EKF list", "EKF Point: lat=" + point.latitude + ", lon=" + point.longitude);
                }
                ekfPoints.add(ekfLocation);
                ekfPolyline.setPoints(ekfPoints);
            }
            lastEKFLocation = ekfLocation;
        }

        // adjust indoor map to ekf position
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(ekfLocation);
            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
        }
    }


    /**
     * Updates the particle filter position marker and its corresponding polyline.
     * 
     * This method updates the visualization of the particle filter's estimated position
     * on the map. The particle filter is a sensor fusion technique that represents 
     * position estimates as a collection of weighted samples, with the final estimate 
     * being the weighted average of these samples.
     * 
     * The method performs the following actions:
     * 1. Creates a marker for the particle filter position if it doesn't exist
     * 2. Updates the marker's position and orientation based on new data
     * 3. Adds new points to the particle filter trajectory polyline
     * 4. Manages the polyline's maximum length by removing oldest points when needed
     * 
     * Note: Currently, the particle visualization is set to invisible by default, as
     * it is implemented but not actively shown in the current UI. This may change in
     * future versions of the application.
     * 
     * @param particleLocation The new LatLng position estimated by the particle filter
     * @param orientation The orientation in degrees (0-360) for the marker's heading
     * 
     * @author Sofea Jazlan Arif
     */
    public void updateParticle(LatLng particleLocation, float orientation){
        if (gMap == null) return; // Optionally, use a flag (like isEKFOn) if you want to control EKF display separately.
        Log.d("TrajectoryMapFragment", "New PARTICLE update received: " + particleLocation);

        if (particleMarker == null) {
            // Create the Particle marker the first time using a different color for clarity
            particleMarker = gMap.addMarker(new MarkerOptions()
                    .position(particleLocation)
                    .title("Particle Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_26)))
            );
            particleMarker.setVisible(false);
            //Log.d("Particle Marker", "Last particle location" + lastParticleLocation);
            lastParticleLocation = particleLocation;
            Log.d("Particle Marker", "Last particle location" + lastParticleLocation);
        } else {

            // Update marker position
            particleMarker.setPosition(particleLocation);
            particleMarker.setRotation(orientation);
            particleMarker.setVisible(false);

            //Log.d("Particle Marker", "particle location in else statement" + particleLocation);

            // Add a segment to the Particle polyline if the location has changed
            if (lastParticleLocation != null && !lastParticleLocation.equals(particleLocation)) {
                List<LatLng> particlePoints = new ArrayList<>(particlePolyline.getPoints());

                if (particlePoints.size() >= MAX_POLYLINE_POINT) {
                    particlePoints.remove(0);
                }

                for (LatLng point : particlePoints) {
                    Log.d("Particle list", "Particle Point: lat=" + point.latitude + ", lon=" + point.longitude);
                }


                particlePoints.add(particleLocation);
                particlePolyline.setPoints(particlePoints);
                particlePolyline.setVisible(false);

            }
            lastParticleLocation = particleLocation;
        }

    }
    /**
     * Remove GNSS marker if user toggles it off
     */
    public void clearGNSS() {
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
    }

    /**
     * Whether user is currently showing GNSS or not
     */
    public boolean isGnssEnabled() {
        return isGnssOn;
    }


    /**
     * Configures the map fragment for replay mode operation.
     * 
     * This method sets up the map fragment to operate in replay mode, which affects
     * how various UI elements and interactions behave. In replay mode, certain features
     * like tag deletion might be disabled, and the visualization options are configured
     * based on the available data.
     * 
     * The method also tracks whether the replayed trajectory contains WiFi data, which
     * determines if WiFi-related controls should be enabled or disabled during replay.
     * 
     * @param isReplay Indicates if the fragment should operate in replay mode (true) or
     *                 recording mode (false)
     * @param hasWifiData Indicates if the replayed trajectory contains WiFi positioning data
     * 
     * @author Stone Anderson
     */
    public void setReplayMode(boolean isReplay, boolean hasWifiData) {
        this.isReplayMode = isReplay;
        this.hasReplayWifiData = hasWifiData;
    }
    
    /**
     * Sets the default states for all visualization toggles in the control panel.
     * 
     * This method configures the initial state of the switches that control which
     * positioning data sources are visualized on the map. It handles both the internal
     * state variables and the actual UI switch controls, ensuring they remain synchronized.
     * 
     * The method works in two phases:
     * 1. Updates the internal boolean flags that track switch states
     * 2. If the UI switches have been initialized, updates their visual state as well
     * 
     * This approach ensures that the correct visualization state is maintained even if
     * the method is called before the UI is fully initialized, as the values will be
     * applied once the switches become available.
     * 
     * @param gnssEnabled Initial state for GNSS visualization (true=visible, false=hidden)
     * @param wifiEnabled Initial state for WiFi positioning visualization (true=visible, false=hidden)
     * @param pdrEnabled Initial state for PDR trajectory visualization (true=visible, false=hidden)
     * 
     * @author 
     */
    public void setDefaultSwitchStates(boolean gnssEnabled, boolean wifiEnabled, boolean pdrEnabled) {
        // Store these values to be applied when switches are fully initialized
        this.isGnssOn = gnssEnabled;
        this.isWifiOn = wifiEnabled;
        this.isPdrOn = pdrEnabled;
        
        // If switches are already initialized, set their states directly
        if (gnssSwitch != null) {
            gnssSwitch.setChecked(gnssEnabled);
        }
        
        if (wifiSwitch != null) {
            wifiSwitch.setChecked(wifiEnabled);
        }
        
        if (pdrSwitch != null) {
            pdrSwitch.setChecked(pdrEnabled);
        }
    }
    
    /**
     * Forces the map to display an indoor map for a specific building regardless of actual position.
     * 
     * This method overrides the normal indoor map detection logic and forces the map to display
     * the indoor floor plan for a specified building at a specified floor level. This is useful
     * in replay mode or for testing when the current position might not be accurately detected
     * as inside a building, but we know the trajectory takes place inside that building.
     * 
     * The method works by:
     * 1. Selecting a known point within the specified building's polygon
     * 2. Setting this point as the "current location" for indoor map detection
     * 3. Explicitly setting the floor number to display
     * 4. Making the floor control buttons visible
     * 
     * Currently supports two building types: "nucleus" and "library", which correspond to
     * pre-defined polygon areas in the BuildingPolygon class.
     * 
     * @param buildingType The building identifier ("nucleus" or "library")
     * @param floor The floor number to display (e.g., 0=ground floor, 1=first floor, etc.)
     * 
     * @author 
     */
    public void forceIndoorMap(String buildingType, int floor) {
        if (indoorMapManager == null || gMap == null) return;
        
        if ("nucleus".equalsIgnoreCase(buildingType)) {
            // Use a point inside Nucleus building
            LatLng nucleusPoint = BuildingPolygon.NUCLEUS_POLYGON.get(0);
            indoorMapManager.setCurrentLocation(nucleusPoint);
            indoorMapManager.setCurrentFloor(floor, true);
            Log.d("TrajectoryMapFragment", "Forced Nucleus indoor map, floor: " + floor);
        } else if ("library".equalsIgnoreCase(buildingType)) {
            // Use a point inside Library building
            LatLng libraryPoint = BuildingPolygon.LIBRARY_POLYGON.get(0);
            indoorMapManager.setCurrentLocation(libraryPoint);
            indoorMapManager.setCurrentFloor(floor, true);
            Log.d("TrajectoryMapFragment", "Forced Library indoor map, floor: " + floor);
        }
        
        // Show floor controls now that we have an indoor map
        setFloorControlsVisibility(View.VISIBLE);
    }


    /**
     * Enables or disables the WiFi positioning visualization switch.
     * 
     * This method controls the interactive state of the WiFi switch in the control panel.
     * When data for WiFi positioning is unavailable, this switch can be disabled to
     * provide visual feedback to the user that this positioning method is not available.
     * The method also adjusts the alpha (opacity) of the switch to provide a visual cue
     * about its enabled/disabled state.
     * 
     * @param isEnabled If true, the switch will be interactive; if false, it will be disabled
     * 
     * @author 
     */
    public void setWifiSwitchEnabled(boolean isEnabled) {
        wifiSwitch.setEnabled(isEnabled);
        wifiSwitch.setAlpha(isEnabled ? 1.0f : 0.5f); // Grey out when disabled
    }
    
    /**
     * Enables or disables the automatic floor selection switch.
     * 
     * This method controls the interactive state of the auto-floor switch in the control panel.
     * When indoor positioning data is unavailable, this switch can be disabled to indicate
     * that automatic floor detection is not possible. The method also adjusts the alpha
     * (opacity) of the switch to provide a visual cue about its enabled/disabled state.
     * 
     * @param isEnabled If true, the switch will be interactive; if false, it will be disabled
     * 
     * @author
     */
    public void setAutoFloorSwitchEnabled(boolean isEnabled) {
        autoFloorSwitch.setEnabled(isEnabled);
        autoFloorSwitch.setAlpha(isEnabled ? 1.0f : 0.5f); // Grey out when disabled
    }
    
    /**
     * Enables or disables the GNSS positioning visualization switch.
     * 
     * This method controls the interactive state of the GNSS switch in the control panel.
     * When GNSS positioning data is unavailable, this switch can be disabled to indicate
     * that this positioning method is not available. The method also adjusts the alpha
     * (opacity) of the switch to provide a visual cue about its enabled/disabled state.
     * 
     * @param isEnabled If true, the switch will be interactive; if false, it will be disabled
     * 
     * @author 
     */
    public void setGnssSwitchEnabled(boolean isEnabled) {
        gnssSwitch.setEnabled(isEnabled);
        gnssSwitch.setAlpha(isEnabled ? 1.0f : 0.5f); // Grey out when disabled
    }
    
    /**
     * Enables or disables the PDR trajectory visualization switch.
     * 
     * This method controls the interactive state of the PDR switch in the control panel.
     * When PDR (Pedestrian Dead Reckoning) data is unavailable, this switch can be disabled
     * to indicate that this positioning method is not available. The method also adjusts the
     * alpha (opacity) of the switch to provide a visual cue about its enabled/disabled state.
     * 
     * @param isEnabled If true, the switch will be interactive; if false, it will be disabled
     * 
     * @author 
     */
    public void setPdrSwitchEnabled(boolean isEnabled) {
        pdrSwitch.setEnabled(isEnabled);
        pdrSwitch.setAlpha(isEnabled ? 1.0f : 0.5f); // Grey out when disabled
    }



    /**
     * Updates the WiFi positioning marker and its corresponding trajectory polyline.
     * 
     * This method updates the visualization of WiFi-based indoor positioning on the map.
     * It creates or updates a marker showing the current WiFi-derived position and adds 
     * to the trajectory polyline that shows the path of WiFi positions over time.
     * 
     * The method performs the following actions:
     * 1. Creates a WiFi marker if it doesn't exist yet, using a yellow marker
     * 2. Updates the marker's position with new WiFi location data
     * 3. Adjusts marker visibility based on the WiFi visualization toggle state
     * 4. Extends the WiFi trajectory polyline if the location has changed
     * 5. Ensures the polyline's visibility matches the toggle state
     * 6. Manages the maximum length of the polyline to maintain performance
     * 
     * WiFi positioning is particularly valuable for indoor environments where GNSS
     * signals may be weak or unavailable. The visualization allows users to compare
     * WiFi-based positioning with other methods like PDR or GNSS.
     * 
     * @param wifiLocation The new LatLng position derived from WiFi positioning
     * 
     * @author Stone Anderson
     */
    public void updateWifi(@NonNull LatLng wifiLocation) {
        if (gMap == null) return;

        if (wifiMarker == null) {
            // Create the Wifi marker for the first time
            wifiMarker = gMap.addMarker(new MarkerOptions()
                    .position(wifiLocation)
                    .title("Wifi Position")
                    .visible(isWifiOn) // Respect current switch state
                    .icon(BitmapDescriptorFactory
                            .defaultMarker(BitmapDescriptorFactory.HUE_YELLOW)));
            lastWifiLocation = wifiLocation;
        } else {
            Log.d("TrajectoryMapFragment", "New WiFi update received: " + wifiLocation);
            // Move existing Wifi marker
            wifiMarker.setPosition(wifiLocation);
            wifiMarker.setVisible(isWifiOn); // Ensure visibility matches switch state
        }

        // Add a segment to the yellow Wifi line, if this is a new location
        if (lastWifiLocation != null && !lastWifiLocation.equals(wifiLocation)) {
            List<LatLng> wifiPoints = new ArrayList<>(wifiPolyline.getPoints());

            if (wifiPoints.size() >= MAX_POLYLINE_POINT) {
                wifiPoints.remove(0);
            }

            wifiPoints.add(wifiLocation);
            wifiPolyline.setPoints(wifiPoints);
        }
        
        // Update visibility of polyline
        wifiPolyline.setVisible(isWifiOn);
        lastWifiLocation = wifiLocation;
    }

    /**
     * Removes the WiFi positioning marker from the map.
     * 
     * This method completely removes the WiFi positioning marker from the map when
     * the user toggles off WiFi visualization or when cleaning up resources. This is 
     * different from just setting visibility to false, as it fully removes the marker
     * object from the map, freeing memory resources.
     * 
     * @author 
     */
    public void clearWifi() {
        if (wifiMarker != null) {
            wifiMarker.remove();
            wifiMarker = null;
        }
    }

    /**
     * Checks if WiFi positioning visualization is currently enabled.
     * 
     * This method returns the current state of the WiFi visualization toggle.
     * It's used by other components to determine whether WiFi positioning data
     * should be displayed on the map.
     * 
     * @return true if WiFi positioning visualization is enabled, false otherwise
     * 
     * @author 
     */
    public boolean isWifiEnabled() {
        return isWifiOn;
    }


    /**
     * Processes floor information from WiFi positioning and updates the indoor map.
     * 
     * This method handles floor change events detected through WiFi positioning.
     * When indoor positioning data includes floor information, this method stores
     * the floor value and, if automatic floor switching is enabled, updates the
     * indoor map to display the correct floor plan for that level.
     * 
     * The automatic floor detection feature allows the application to seamlessly
     * transition between floor plans as the user moves throughout a multi-level
     * building, without requiring manual floor selection.
     * 
     * @param floor The floor number detected by WiFi positioning (typically 0=ground floor,
     *              1=first floor, etc., but depends on the building's configuration)
     *              
     * @author Semih Vazgecen
     */
    public void autoFloorHandler(int floor){
        wifiFloor = floor;
        Log.d("TrajectoryMapFragment", "New WiFi Floor received: " + wifiFloor);
        
        // Always process floor changes if autoFloorSwitch is checked
        if(autoFloorSwitch != null && autoFloorSwitch.isChecked() && indoorMapManager != null) {
            indoorMapManager.setCurrentFloor(wifiFloor, true);
        }
    }



    private void setFloorControlsVisibility(int visibility) {
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);
        autoFloorSwitch.setVisibility(visibility);
    }

    public void clearMapAndReset() {
        if (pdrPolyline != null) {
            pdrPolyline.remove();
            pdrPolyline = null;
        }
        if (gnssPolyline != null) {
            gnssPolyline.remove();
            gnssPolyline = null;
        }
        if (wifiPolyline != null) {
            wifiPolyline.remove();
            wifiPolyline = null;
        }
        if (ekfPolyline != null) {
            ekfPolyline.remove();
            ekfPolyline = null;
        }

        if (pdrMarker != null) {
            pdrMarker.remove();
            pdrMarker = null;
        }
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
        if (wifiMarker != null) {
            wifiMarker.remove();
            wifiMarker = null;
        }
        lastGnssLocation = null;
        lastEKFLocation = null;
        lastWifiLocation = null;
        currentLocation  = null;

        // Re-create empty polylines with your chosen colors
        if (gMap != null) {
            pdrPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.RED)
                    .width(5f)
                    .zIndex(2f)
                    .add());
            gnssPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.BLUE)
                    .width(5f)
                    .zIndex(2f)
                    .add());
            wifiPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.YELLOW)
                    .width(5f)
                    .zIndex(2f)
                    .add());
            ekfPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.GREEN)
                    .width(5f)
                    .zIndex(2f)
                    .add());
        }
    }

    /**
     * Draw the building polygon on the map
     * <p>
     *     The method draws a polygon representing the building on the map.
     *     The polygon is drawn with specific vertices and colors to represent
     *     different buildings or areas on the map.
     *     The method removes the old polygon if it exists and adds the new polygon
     *     to the map with the specified options.
     *     The method logs the number of vertices in the polygon for debugging.
     *     <p>
     *
     *    Note: The method uses hard-coded vertices for the building polygon.
     *
     *    </p>
     *
     *    See: {@link PolygonOptions} The options for the new polygon.
     */
    private void drawBuildingPolygon() {
        if (gMap == null) {
            Log.e("TrajectoryMapFragment", "GoogleMap is not ready");
            return;
        }

        // nuclear building polygon vertices
        LatLng nucleus1 = new LatLng(55.92279538827796, -3.174612147506538);
        LatLng nucleus2 = new LatLng(55.92278121423647, -3.174107900816096);
        LatLng nucleus3 = new LatLng(55.92288405733954, -3.173843694667146);
        LatLng nucleus4 = new LatLng(55.92331786793876, -3.173832892645086);
        LatLng nucleus5 = new LatLng(55.923337194112555, -3.1746284301397387);


        // nkml building polygon vertices
        LatLng nkml1 = new LatLng(55.9230343434213, -3.1751847990731954);
        LatLng nkml2 = new LatLng(55.923032840563366, -3.174777103346131);
        LatLng nkml4 = new LatLng(55.92280139974615, -3.175195527934348);
        LatLng nkml3 = new LatLng(55.922793885410734, -3.1747958788136867);

        LatLng fjb1 = new LatLng(55.92269205199916, -3.1729563477188774);//left top
        LatLng fjb2 = new LatLng(55.922822801570994, -3.172594249522305);
        LatLng fjb3 = new LatLng(55.92223512226413, -3.171921917547244);
        LatLng fjb4 = new LatLng(55.9221071265519, -3.1722813131202097);

        LatLng faraday1 = new LatLng(55.92242866264128, -3.1719553662011815);
        LatLng faraday2 = new LatLng(55.9224966752294, -3.1717846714743474);
        LatLng faraday3 = new LatLng(55.922271383074154, -3.1715191463437162);
        LatLng faraday4 = new LatLng(55.92220124468304, -3.171705013935158);



        PolygonOptions buildingPolygonOptions = new PolygonOptions()
                .add(nucleus1, nucleus2, nucleus3, nucleus4, nucleus5)
                .strokeColor(Color.RED)    // Red border
                .strokeWidth(10f)           // Border width
                //.fillColor(Color.argb(50, 255, 0, 0)) // Semi-transparent red fill
                .zIndex(1);                // Set a higher zIndex to ensure it appears above other overlays

        // Options for the new polygon
        PolygonOptions buildingPolygonOptions2 = new PolygonOptions()
                .add(nkml1, nkml2, nkml3, nkml4, nkml1)
                .strokeColor(Color.BLUE)    // Blue border
                .strokeWidth(10f)           // Border width
                // .fillColor(Color.argb(50, 0, 0, 255)) // Semi-transparent blue fill
                .zIndex(1);                // Set a higher zIndex to ensure it appears above other overlays

        PolygonOptions buildingPolygonOptions3 = new PolygonOptions()
                .add(fjb1, fjb2, fjb3, fjb4, fjb1)
                .strokeColor(Color.GREEN)    // Green border
                .strokeWidth(10f)           // Border width
                //.fillColor(Color.argb(50, 0, 255, 0)) // Semi-transparent green fill
                .zIndex(1);                // Set a higher zIndex to ensure it appears above other overlays

        PolygonOptions buildingPolygonOptions4 = new PolygonOptions()
                .add(faraday1, faraday2, faraday3, faraday4, faraday1)
                .strokeColor(Color.YELLOW)    // Yellow border
                .strokeWidth(10f)           // Border width
                //.fillColor(Color.argb(50, 255, 255, 0)) // Semi-transparent yellow fill
                .zIndex(1);                // Set a higher zIndex to ensure it appears above other overlays


        // Remove the old polygon if it exists
        if (buildingPolygon != null) {
            buildingPolygon.remove();
        }

        // Add the polygon to the map
        buildingPolygon = gMap.addPolygon(buildingPolygonOptions);
        gMap.addPolygon(buildingPolygonOptions2);
        gMap.addPolygon(buildingPolygonOptions3);
        gMap.addPolygon(buildingPolygonOptions4);
        Log.d("TrajectoryMapFragment", "Building polygon added, vertex count: " + buildingPolygon.getPoints().size());
    }

    /**
     * Displays a delete button above the selected tag marker.
     * 
     * This method positions and displays a delete button directly above a selected tag marker,
     * allowing users to remove tags they've previously placed. The button is dynamically
     * positioned relative to the marker's screen position, accounting for different screen 
     * sizes and densities.
     * 
     * The method:
     * 1. Verifies that the operation is valid (not in replay mode, UI elements initialized)
     * 2. Converts the marker's geographic position to screen coordinates
     * 3. Calculates an appropriate offset to position the button above the marker
     * 4. Sets the button's position and makes it visible
     * 
     * The delete button is only shown when a tag marker is selected and is not available 
     * in replay mode, as replays are read-only visualizations of previously recorded data.
     * 
     * @param marker The selected marker above which to display the delete button
     * 
     * @author Semih Vazgecen
     */
    private void showDeleteButton(Marker marker) {
        if (isReplayMode || deleteTagButton == null || gMap == null) return;

        Projection projection = gMap.getProjection();
        if (projection == null) return; // Ensure projection is available

        // Convert LatLng to screen coordinates
        Point screenPosition = projection.toScreenLocation(marker.getPosition());

        // Convert 50dp to pixels dynamically
        float offsetY = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 50,
                deleteTagButton.getResources().getDisplayMetrics()
        );

        // Adjust position (Move button slightly above the marker)
        deleteTagButton.setX(screenPosition.x - (deleteTagButton.getWidth() / 2f));
        deleteTagButton.setY(screenPosition.y - offsetY); // Use dynamic height

        // Show button
        deleteTagButton.setVisibility(View.VISIBLE);
    }
    
    /**
     * Adds a marker at the specified location to indicate a user-tagged position.
     * 
     * This method creates and places a visible tag marker on the map at the specified
     * geographic coordinates. Tags are used to mark points of interest during recording,
     * such as landmarks, entrances, or other notable locations. Each tag is associated
     * with a timestamp that identifies when it was created during the recording session.
     * 
     * If the map is not yet initialized when this method is called, the marker is queued
     * and will be added once the map becomes available. This ensures that tag markers
     * are not lost due to timing issues during initialization.
     * 
     * Tag markers are displayed with a distinctive azure (blue) color to differentiate
     * them from other markers on the map, and they have a higher z-index to ensure they
     * appear above other map elements.
     * 
     * @param position The geographic coordinates where the tag should be placed
     * @param timestamp The relative timestamp when the tag was created, used for
     *                  identification and potential removal
     *                  
     * @author Joseph Azrak
     */
    public void addTagMarker(LatLng position, long timestamp) {
        MarkerOptions markerOptions = new MarkerOptions()
                .position(position)
                .title("Tag")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                .zIndex(2.0f);

        if (gMap == null) {
            Log.w("TrajectoryMapFragment", "Map not ready, queuing marker for later.");
            pendingMarkers.add(markerOptions);
            return;
        }

        Marker tagMarker = gMap.addMarker(markerOptions);
        if (tagMarker != null) {
            tagMarkers.add(tagMarker);
            tagMarker.setTag(timestamp);
            Log.d("TrajectoryMapFragment", "Added tag marker at position: " + position);
        }
    }

    /**
     * Processes all pending tag markers that were queued before the map was ready.
     * 
     * This method is called once the Google Map is fully initialized and ready to accept
     * markers. It iterates through the queue of pending markers (those that were requested
     * to be added before the map was ready) and adds them to the map. After processing,
     * it clears the queue to prevent duplicate markers.
     * 
     * This approach ensures that no tag markers are lost if they are created during
     * the asynchronous initialization of the map, which is particularly important
     * when loading a saved trajectory with pre-existing tags.
     * 
     * @author Semih Vazgecen
     */
    private void processPendingMarkers() {
        if (gMap == null) return;

        for (MarkerOptions marker : pendingMarkers) {
            gMap.addMarker(marker);
        }
        pendingMarkers.clear(); // Clear the queue after adding
    }

    /**
     * Removes the currently selected tag marker from both the map and the trajectory data.
     * 
     * This method is called when the user clicks the delete button associated with a
     * selected tag marker. It performs a complete removal of the tag by:
     * 1. Getting the timestamp associated with the selected marker
     * 2. Calling the SensorFusion service to remove the tag from the trajectory data
     * 3. Removing the marker from the Google Map visual display
     * 4. Removing the marker from the internal tag tracking list
     * 5. Hiding the delete button
     * 6. Clearing the selected marker reference
     * 
     * The method includes validation to ensure the marker has a valid timestamp tag and
     * handles potential errors safely. This implementation ensures that deleted tags are
     * completely removed from both the UI and the underlying data model.
     * 
     * @author 
     */
    private void removeSelectedTag() {
        if (selectedMarker == null) return;

        Object tagObject = selectedMarker.getTag();
        if (tagObject == null) {
            Log.e("TrajectoryMapFragment", "Error: selectedMarker has no tag!");
            return;
        }

        // Safely cast and remove
        long tagTimestamp = (long) tagObject;
        sensorFusion = SensorFusion.getInstance();
        sensorFusion.removeTag(tagTimestamp);

        // Remove marker from map
        selectedMarker.remove();
        tagMarkers.remove(selectedMarker);

        // Hide delete button
        deleteTagButton.setVisibility(View.GONE);
        selectedMarker = null;
    }

}