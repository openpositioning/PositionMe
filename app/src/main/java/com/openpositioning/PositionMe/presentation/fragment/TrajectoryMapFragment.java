package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import com.google.android.material.switchmaterial.SwitchMaterial;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.OnMapReadyCallback;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.*;
import com.openpositioning.PositionMe.sensors.IndoorMapService;
import com.openpositioning.PositionMe.sensors.Wifi;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;


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
 * @see com.openpositioning.PositionMe.presentation.activity.RecordingActivity The activity hosting this fragment.
 * @see com.openpositioning.PositionMe.utils.IndoorMapManager Utility for managing indoor map overlays.
 * @see com.openpositioning.PositionMe.utils.UtilFunctions Utility functions for UI and graphics handling.
 *
 * @author Mate Stodulka
 */

public class TrajectoryMapFragment extends Fragment {

    private GoogleMap gMap; // Google Maps instance
    private LatLng currentLocation; // Stores the user's current location
    private Marker orientationMarker; // Marker representing user's heading
    private Marker gnssMarker; // GNSS position marker
    private Polyline polyline; // Polyline representing user's movement path
    private boolean isRed = true; // Tracks whether the polyline color is red
    private boolean isGnssOn = false; // Tracks if GNSS tracking is enabled

    private Polyline gnssPolyline; // Polyline for GNSS path
    private LatLng lastGnssLocation = null; // Stores the last GNSS location

    private boolean isAutoFloorOn = false; // Auto floor switching via WiFi

    private LatLng pendingCameraPosition = null; // Stores pending camera movement
    private boolean hasPendingCameraMove = false; // Tracks if camera needs to move

    private IndoorMapManager indoorMapManager; // Manages indoor mapping
    private SensorFusion sensorFusion;


    // UI
    private Spinner switchMapSpinner;

    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;
    private SwitchMaterial statsSwitch;

    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;
    private Polygon buildingPolygon;

    // Indoor mapping feature variables
    private IndoorMapService mapDataService;
    private List<Polygon> venueOverlays = new ArrayList<>();
    private IndoorMapService.BuildingData activeBuilding = null;

    // Floor display variables
    private List<Polygon> levelShapes = new ArrayList<>();
    private Polygon levelBase = null;
    private int activeLevelIdx = 0;

    // Reference point marker variables
    private com.google.android.material.floatingactionbutton.FloatingActionButton addTestPointButton;
    private List<Marker> referenceMarkers = new ArrayList<>();
    private int markerSequence = 0;

    // Camera tracking flag: follows user during recording, stops after
    private boolean trackUserPosition = true;

    // Live stats panel
    private View statsPanel;
    private android.widget.TextView statsSteps, statsDistance, statsTime, statsSpeed;
    private android.widget.TextView statsWifi, statsBle, statsGps, statsFloor;
    private android.os.Handler statsUpdateHandler;
    private Runnable statsUpdateRunnable;
    private long recordingStartTime = 0;
    private LatLng lastPositionForSpeed = null;
    private long lastSpeedUpdateTime = 0;
    private double totalDistance = 0.0;

    // Auto floor detection variables
    private float baseElevation = 0f; // Reference elevation for ground floor (meters)
    private boolean baseElevationSet = false; // Whether base elevation has been calibrated
    private static final float FLOOR_HEIGHT = 3.5f; // Average floor height in meters
    private static final float FLOOR_THRESHOLD = 1.5f; // Threshold for floor change detection (meters)

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

        // Initialize SensorFusion and IndoorMapService
        sensorFusion = SensorFusion.getInstance();
        mapDataService = new IndoorMapService(requireContext());

        // Fix: prevent floor button container from intercepting map touch events
        ViewGroup floorButtonContainer = (ViewGroup) view.findViewById(R.id.floorUpButton).getParent();
        if (floorButtonContainer != null) {
            floorButtonContainer.setClickable(false);
            floorButtonContainer.setFocusable(false);
        }

        // Grab references to UI controls
        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch      = view.findViewById(R.id.gnssSwitch);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        statsSwitch     = view.findViewById(R.id.statsSwitch);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);
        addTestPointButton = view.findViewById(R.id.addTestPointButton);

        // Stats panel references
        statsPanel = view.findViewById(R.id.statsPanel);
        statsSteps = view.findViewById(R.id.statsSteps);
        statsDistance = view.findViewById(R.id.statsDistance);
        statsTime = view.findViewById(R.id.statsTime);
        statsSpeed = view.findViewById(R.id.statsSpeed);
        statsWifi = view.findViewById(R.id.statsWifi);
        statsBle = view.findViewById(R.id.statsBle);
        statsGps = view.findViewById(R.id.statsGps);
        statsFloor = view.findViewById(R.id.statsFloor);

        // Setup floor up/down UI hidden initially until we know there's an indoor map
        setFloorControlsVisibility(View.GONE);

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

                    // Custom InfoWindow to support multi-line snippets
                    gMap.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {
                        @Override
                        public View getInfoWindow(Marker marker) { return null; }
                        @Override
                        public View getInfoContents(Marker marker) {
                            android.widget.LinearLayout layout = new android.widget.LinearLayout(requireContext());
                            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
                            android.widget.TextView title = new android.widget.TextView(requireContext());
                            title.setText(marker.getTitle());
                            title.setTypeface(null, android.graphics.Typeface.BOLD);
                            title.setTextSize(14f);
                            layout.addView(title);
                            if (marker.getSnippet() != null) {
                                android.widget.TextView snippet = new android.widget.TextView(requireContext());
                                snippet.setText(marker.getSnippet());
                                snippet.setTextSize(12f);
                                layout.addView(snippet);
                            }
                            return layout;
                        }
                    });

                    // If we had a pending camera move, apply it now
                    if (hasPendingCameraMove && pendingCameraPosition != null) {
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
                        hasPendingCameraMove = false;
                        pendingCameraPosition = null;
                    }

                    drawBuildingPolygon();

                    Log.d("TrajectoryMapFragment", "onMapReady: Map is ready!");

                    // Configure building polygon click listener
                    gMap.setOnPolygonClickListener(polygon -> {
                        Object tag = polygon.getTag();
                        if (tag instanceof IndoorMapService.BuildingData) {
                            IndoorMapService.BuildingData building = (IndoorMapService.BuildingData) tag;
                            handleBuildingSelection(building, polygon);
                        }
                    });

                    // Request nearby buildings using real GPS/WiFi data
                    loadProximityVenues();


                }
            });
        }

        // Map type spinner setup
        initMapTypeSpinner();

        // GNSS Switch
        gnssSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isGnssOn = isChecked;
            if (!isChecked && gnssMarker != null) {
                gnssMarker.remove();
                gnssMarker = null;
            }
        });

        // Color switch
        switchColorButton.setOnClickListener(v -> {
            if (polyline != null) {
                if (isRed) {
                    switchColorButton.setBackgroundColor(Color.BLACK);
                    polyline.setColor(Color.BLACK);
                    isRed = false;
                } else {
                    switchColorButton.setBackgroundColor(Color.RED);
                    polyline.setColor(Color.RED);
                    isRed = true;
                }
            }
        });

        // Auto floor switching via pressure sensor + WiFi data
        autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            isAutoFloorOn = isChecked;
            if (isChecked) {
                // Calibrate base elevation when auto floor is enabled
                calibrateBaseElevation();
                Toast.makeText(getContext(), "Auto Floor: Calibrating...", Toast.LENGTH_SHORT).show();
            }
        });

        floorUpButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            // API venue floor switching takes priority
            if (activeBuilding != null && !activeBuilding.levelsAvailable.isEmpty()) {
                changeFloorLevel(1);
            } else if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
            }
        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (activeBuilding != null && !activeBuilding.levelsAvailable.isEmpty()) {
                changeFloorLevel(-1);
            } else if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
            }
        });

        // Reference point capture button
        addTestPointButton.setOnClickListener(v -> captureReferencePoint());

        // Stats toggle switch
        statsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            statsPanel.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (isChecked) {
                startStatsUpdates();
            } else {
                stopStatsUpdates();
            }
        });

        // Initialize stats update handler
        statsUpdateHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        statsUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateLiveStats();
                statsUpdateHandler.postDelayed(this, 1000); // Update every second
            }
        };
    }

    /**
     * Initialize the map settings with the provided GoogleMap instance.
     * <p>
     *     The method sets basic map settings, initializes the indoor map manager,
     *     and creates an empty polyline for user movement tracking.
     *     The method also initializes the GNSS polyline for tracking GNSS path.
     *     The method sets the map type to Hybrid and initializes the map with these settings.
     *
     * @param map
     */

    private void initMapSettings(GoogleMap map) {
        // Basic map settings
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.getUiSettings().setZoomGesturesEnabled(true);
        map.getUiSettings().setAllGesturesEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        // Initialize indoor manager
        indoorMapManager = new IndoorMapManager(map);

        // Initialize an empty polyline (zIndex 5 to appear above floor plans)
        polyline = map.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(5f)
                .zIndex(5)
                .add() // start empty
        );

        // GNSS path in blue (zIndex 5 to appear above floor plans)
        gnssPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.BLUE)
                .width(5f)
                .zIndex(5)
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
     *     @see com.google.android.gms.maps.GoogleMap The GoogleMap instance to update map type.
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
     * and append to polyline if the user actually moved.
     *
     * @param newLocation The new location to plot.
     * @param orientation The user’s heading (e.g. from sensor fusion).
     */
    public void updateUserLocation(@NonNull LatLng newLocation, float orientation) {
        if (gMap == null) return;

        // Keep track of current location
        LatLng oldLocation = this.currentLocation;
        this.currentLocation = newLocation;

        // If no marker, create it
        if (orientationMarker == null) {
            orientationMarker = gMap.addMarker(new MarkerOptions()
                    .position(newLocation)
                    .flat(true)
                    .title("Current Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_24)))
            );
            if (trackUserPosition) {
                gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
            }
        } else {
            // Update marker position + orientation
            orientationMarker.setPosition(newLocation);
            orientationMarker.setRotation(orientation);
            if (trackUserPosition) {
                gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
            }
        }

        // Extend polyline if movement occurred
        if (oldLocation != null && !oldLocation.equals(newLocation) && polyline != null) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            points.add(newLocation);
            polyline.setPoints(points);
        }

        // Update indoor map overlay
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(newLocation);
            // Don't hide floor controls if an API venue with floors is selected
            if (activeBuilding == null || activeBuilding.levelsAvailable.isEmpty()) {
                setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
            }
        }

        // Perform auto floor detection if enabled
        performAutoFloorDetection();
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
        if (gMap == null) return;
        if (!isGnssOn) return;

        if (gnssMarker == null) {
            // Create the GNSS marker for the first time
            gnssMarker = gMap.addMarker(new MarkerOptions()
                    .position(gnssLocation)
                    .title("GNSS Position")
                    .icon(BitmapDescriptorFactory
                            .defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
            lastGnssLocation = gnssLocation;
        } else {
            // Move existing GNSS marker
            gnssMarker.setPosition(gnssLocation);

            // Add a segment to the blue GNSS line, if this is a new location
            if (lastGnssLocation != null && !lastGnssLocation.equals(gnssLocation)) {
                List<LatLng> gnssPoints = new ArrayList<>(gnssPolyline.getPoints());
                gnssPoints.add(gnssLocation);
                gnssPolyline.setPoints(gnssPoints);
            }
            lastGnssLocation = gnssLocation;
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
     * Enable or disable camera auto-following the user position.
     */
    public void setCameraFollowing(boolean following) {
        this.trackUserPosition = following;
    }

    private void setFloorControlsVisibility(int visibility) {
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);
        autoFloorSwitch.setVisibility(visibility);
    }

    public void clearMapAndReset() {
        if (polyline != null) {
            polyline.remove();
            polyline = null;
        }
        if (gnssPolyline != null) {
            gnssPolyline.remove();
            gnssPolyline = null;
        }
        if (orientationMarker != null) {
            orientationMarker.remove();
            orientationMarker = null;
        }
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
        lastGnssLocation = null;
        currentLocation  = null;

        // Keep reference point markers visible after trajectory completes
        // (numbered markers should persist on the map)
        // Note: markers are NOT cleared here to allow users to see their test points

        // Clear floor label markers
        for (Marker m : floorLabelMarkers) {
            m.remove();
        }
        floorLabelMarkers.clear();

        // Restore camera tracking for next recording
        trackUserPosition = true;

        // Reset stats for new recording
        resetStats();

        // Re-create empty polylines with your chosen colors (zIndex 5 to appear above floor plans)
        if (gMap != null) {
            polyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.RED)
                    .width(5f)
                    .zIndex(5)
                    .add());
            gnssPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.BLUE)
                    .width(5f)
                    .zIndex(5)
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
     *    See: {@link com.google.android.gms.maps.model.PolygonOptions} The options for the new polygon.
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
    // ========================================
    // Indoor mapping features: added methods
    // ========================================

    /**
     * Request nearby buildings using real GPS position and WiFi MAC addresses.
     * Falls back to Murchison House coordinates if GPS is not available.
     */
    private void loadProximityVenues() {
        Log.d("TrajectoryMapFragment", "Requesting nearby buildings");

        // Use real GPS position, fallback to Murchison House
        float[] gps = sensorFusion.getGNSSLatitude(false);
        double lat = (gps[0] != 0) ? gps[0] : 55.92426;
        double lon = (gps[1] != 0) ? gps[1] : -3.17913;

        // Use real WiFi MAC addresses from latest scan
        List<String> macs = new ArrayList<>();
        List<Wifi> wifiList = sensorFusion.getWifiList();
        if (wifiList != null) {
            for (Wifi wifi : wifiList) {
                long bssid = wifi.getBssid();
                if (bssid != 0) {
                    // Convert long to colon-separated hex string
                    String hex = String.format("%012x", bssid);
                    String mac = hex.substring(0, 2) + ":" + hex.substring(2, 4) + ":"
                            + hex.substring(4, 6) + ":" + hex.substring(6, 8) + ":"
                            + hex.substring(8, 10) + ":" + hex.substring(10, 12);
                    macs.add(mac);
                }
            }
        }

        mapDataService.fetchProximityMaps(
                lat,
                lon,
                macs,
                new IndoorMapService.MapDataCallback() {
                    @Override
                    public void onSuccess(List<IndoorMapService.BuildingData> buildings) {
                        Log.d("TrajectoryMapFragment", "Got " + buildings.size() + " buildings");
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                renderVenueBoundaries(buildings);
                            });
                        }
                    }

                    @Override
                    public void onError(String error) {
                        Log.e("TrajectoryMapFragment", "Failed to get buildings: " + error);
                    }
                }
        );
    }

    /**
     * Display building boundary outlines on the map.
     * @param buildings List of building data to render
     */
    private void renderVenueBoundaries(List<IndoorMapService.BuildingData> buildings) {
        if (gMap == null) {
            Log.w("TrajectoryMapFragment", "Map not initialized, cannot display buildings");
            return;
        }

        Log.d("TrajectoryMapFragment", "Rendering " + buildings.size() + " building outlines");

        // Clear previous building overlays
        for (Polygon polygon : venueOverlays) {
            polygon.remove();
        }
        venueOverlays.clear();

        // Draw boundary for each building
        for (IndoorMapService.BuildingData building : buildings) {
            PolygonOptions polygonOptions = new PolygonOptions()
                    .strokeColor(Color.parseColor("#6200EE"))
                    .strokeWidth(8f)
                    .fillColor(Color.parseColor("#336200EE"))
                    .clickable(true)
                    .zIndex(2);

            // Log raw data for debugging
            Log.d("TrajectoryMapFragment", "Building: " + building.buildingName
                    + " | outline: [" + building.rawOutline + "]"
                    + " | geometry data length: " + (building.rawGeometryData != null ? building.rawGeometryData.length() : 0));

            // Draw polygon using API-provided boundary coordinates
            if (!building.boundaryPoints.isEmpty()) {
                for (double[] coord : building.boundaryPoints) {
                    polygonOptions.add(new LatLng(coord[0], coord[1]));
                }
            } else {
                Log.w("TrajectoryMapFragment", "Building " + building.buildingName + " has no boundary coordinates, skipping");
                continue;
            }

            Polygon polygon = gMap.addPolygon(polygonOptions);
            polygon.setTag(building);
            venueOverlays.add(polygon);

            Log.d("TrajectoryMapFragment", "Added building: " + building.buildingName +
                    ", boundary points: " + building.boundaryPoints.size());
        }

        // Display notification to user
        Toast.makeText(getContext(),
                "Found " + buildings.size() + " nearby buildings, tap to view details",
                Toast.LENGTH_LONG).show();

        // Don't auto-move camera to avoid interfering with existing building view
        // The drawBuildingPolygon() method sets initial view
        Log.d("TrajectoryMapFragment", "Building boundary rendering complete, total: " + buildings.size());
    }

    /**
     * Handle building selection event.
     * @param building The selected building data
     * @param polygon The corresponding map polygon
     */
    private void handleBuildingSelection(IndoorMapService.BuildingData building, Polygon polygon) {
        Log.d("TrajectoryMapFragment", "User selected building: " + building.buildingName);

        activeBuilding = building;
        highlightSelectedVenue(polygon);

        // Display floor geometry if available
        if (!building.levelsAvailable.isEmpty()) {
            activeLevelIdx = 0;
            int firstFloor = building.levelsAvailable.get(0);
            renderLevelGeometry(building, firstFloor);

            // Show floor control buttons
            setFloorControlsVisibility(View.VISIBLE);

            String floorLabel = getLevelDisplayName(building, firstFloor);
            String message = building.buildingName + " | "
                    + building.levelsAvailable.size() + " floors"
                    + " | Current: " + floorLabel;
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        } else {
            setFloorControlsVisibility(View.GONE);
            Toast.makeText(getContext(), building.buildingName, Toast.LENGTH_SHORT).show();
        }
    }

    // Floor label markers (reserved for future use)
    private List<Marker> floorLabelMarkers = new ArrayList<>();

    /**
     * Display floor shapes for a specific floor - architectural blueprint style.
     *
     * API returns ONLY wall polygons (indoor_type="wall", no room names/types).
     * All 178 shapes are wall segments. Rooms are the white spaces between walls.
     * Rendering: light background + solid dark wall fills = blueprint effect.
     */
    private void renderLevelGeometry(IndoorMapService.BuildingData building, int levelNum) {
        if (gMap == null) return;

        // Clear previous
        for (Polygon p : levelShapes) { p.remove(); }
        levelShapes.clear();
        for (Marker m : floorLabelMarkers) { m.remove(); }
        floorLabelMarkers.clear();
        if (levelBase != null) { levelBase.remove(); levelBase = null; }

        // 1) Light background = "room" space (building outline)
        if (!building.boundaryPoints.isEmpty()) {
            PolygonOptions bgOptions = new PolygonOptions()
                    .fillColor(Color.argb(235, 236, 239, 241))  // Light blue-gray #ECEFF1
                    .strokeColor(Color.parseColor("#263238"))     // Dark building outline
                    .strokeWidth(3.5f)
                    .zIndex(3);
            for (double[] coord : building.boundaryPoints) {
                bgOptions.add(new LatLng(coord[0], coord[1]));
            }
            levelBase = gMap.addPolygon(bgOptions);
        }

        // 2) All shapes are wall segments → render as solid dark fills
        int count = 0;
        for (IndoorMapService.FloorGeometry geom : building.floorLayers) {
            if (geom.levelNumber == levelNum && !geom.polygonPoints.isEmpty()) {
                PolygonOptions options = new PolygonOptions()
                        .strokeColor(Color.parseColor("#1A237E"))
                        .strokeWidth(0.8f)
                        .fillColor(Color.parseColor("#E0263238"))
                        .zIndex(4);

                for (double[] coord : geom.polygonPoints) {
                    options.add(new LatLng(coord[0], coord[1]));
                }
                levelShapes.add(gMap.addPolygon(options));
                count++;
            }
        }

        // 3) Add floor label at building center
        if (!building.boundaryPoints.isEmpty()) {
            String label = getLevelDisplayName(building, levelNum);
            LatLng center = calculateCentroid(building.boundaryPoints);
            Marker floorLabel = gMap.addMarker(new MarkerOptions()
                    .position(center)
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            generateLevelMarkerImage(label)))
                    .anchor(0.875f, 0.0f)
                    .zIndex(6)
                    .flat(true));
            if (floorLabel != null) {
                floorLabelMarkers.add(floorLabel);
            }
        }

        Log.d("TrajectoryMapFragment", "Floor " + levelNum
                + ": displayed " + count + " wall shapes (blueprint style)");
    }

    /**
     * Calculate the centroid of a polygon.
     */
    private LatLng calculateCentroid(List<double[]> coords) {
        double latSum = 0, lonSum = 0;
        for (double[] c : coords) {
            latSum += c[0];
            lonSum += c[1];
        }
        return new LatLng(latSum / coords.size(), lonSum / coords.size());
    }

    /**
     * Generate a floor label bitmap image (floor name only).
     */
    private android.graphics.Bitmap generateLevelMarkerImage(String text) {
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setTextSize(22f);
        paint.setColor(Color.parseColor("#1A237E"));
        paint.setAntiAlias(true);
        paint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));

        float textWidth = paint.measureText(text);
        android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
        int padH = 10, padV = 6;
        int width = (int) (textWidth + padH * 2);
        int height = (int) (fm.bottom - fm.top + padV * 2);

        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                width, height, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);

        // White rounded background
        android.graphics.Paint bgPaint = new android.graphics.Paint();
        bgPaint.setColor(Color.argb(55, 255, 255, 255));
        bgPaint.setStyle(android.graphics.Paint.Style.FILL);
        bgPaint.setAntiAlias(true);
        canvas.drawRoundRect(0, 0, width, height, 8, 8, bgPaint);

        // Border
        android.graphics.Paint borderPaint = new android.graphics.Paint();
        borderPaint.setColor(Color.parseColor("#1A237E"));
        borderPaint.setStyle(android.graphics.Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f);
        borderPaint.setAntiAlias(true);
        canvas.drawRoundRect(0, 0, width, height, 8, 8, borderPaint);

        canvas.drawText(text, padH, -fm.top + padV, paint);
        return bitmap;
    }

    /**
     * Generate a numbered marker icon for reference points.
     * Creates a circular badge with the number displayed in the center.
     * @param number The number to display on the marker
     * @return Bitmap for the numbered marker icon
     */
    private android.graphics.Bitmap generateNumberedMarkerIcon(int number) {
        int size = 96;  // Larger size for better visibility on map
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);

        // Draw shadow circle for depth effect
        android.graphics.Paint shadowPaint = new android.graphics.Paint();
        shadowPaint.setColor(Color.argb(80, 0, 0, 0));
        shadowPaint.setStyle(android.graphics.Paint.Style.FILL);
        shadowPaint.setAntiAlias(true);
        canvas.drawCircle(size / 2f + 2, size / 2f + 2, size / 2f - 3, shadowPaint);

        // Draw circular background (orange color to match original markers)
        android.graphics.Paint circlePaint = new android.graphics.Paint();
        circlePaint.setColor(Color.parseColor("#FF5722"));  // Deep orange
        circlePaint.setStyle(android.graphics.Paint.Style.FILL);
        circlePaint.setAntiAlias(true);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3, circlePaint);

        // Draw white border
        android.graphics.Paint borderPaint = new android.graphics.Paint();
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(android.graphics.Paint.Style.STROKE);
        borderPaint.setStrokeWidth(5f);
        borderPaint.setAntiAlias(true);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 5, borderPaint);

        // Draw number text in white
        String text = String.valueOf(number);
        android.graphics.Paint textPaint = new android.graphics.Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(48f);
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));

        // Center the text vertically and horizontally
        android.graphics.Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textHeight = fm.descent - fm.ascent;
        float textOffset = (textHeight / 2) - fm.descent;
        canvas.drawText(text, size / 2f, size / 2f + textOffset, textPaint);

        return bitmap;
    }

    /**
     * Change floor level by specified direction.
     * @param direction +1 for up, -1 for down
     */
    private void changeFloorLevel(int direction) {
        if (activeBuilding == null || activeBuilding.levelsAvailable.isEmpty()) return;

        int newIdx = activeLevelIdx + direction;
        if (newIdx >= 0 && newIdx < activeBuilding.levelsAvailable.size()) {
            activeLevelIdx = newIdx;
            int level = activeBuilding.levelsAvailable.get(activeLevelIdx);
            renderLevelGeometry(activeBuilding, level);
            Toast.makeText(getContext(), getLevelDisplayName(activeBuilding, level), Toast.LENGTH_SHORT).show();
        }
    }

    // ========================================
    // Reference point marker features
    // ========================================

    /**
     * Capture a reference point at the user's current position.
     * Places a numbered marker on the map and stores the point in SensorFusion.
     */
    private void captureReferencePoint() {
        if (currentLocation == null) {
            Toast.makeText(getContext(), "No location available", Toast.LENGTH_SHORT).show();
            return;
        }

        SensorFusion sensorFusion = SensorFusion.getInstance();
        markerSequence++;

        // Get current floor number
        int levelNum = 0;
        String levelLabel = null;
        if (activeBuilding != null && !activeBuilding.levelsAvailable.isEmpty()) {
            levelNum = activeBuilding.levelsAvailable.get(activeLevelIdx);
            levelLabel = getLevelDisplayName(activeBuilding, levelNum);
        }

        // Store in SensorFusion
        sensorFusion.addTestPoint(
                currentLocation.latitude,
                currentLocation.longitude,
                sensorFusion.getElevation(),
                levelLabel
        );

        // Add numbered marker on the map (zIndex 20 to always appear above trajectories)
        if (gMap != null) {
            Marker marker = gMap.addMarker(new MarkerOptions()
                    .position(currentLocation)
                    .title("Reference Point #" + markerSequence)
                    .snippet((levelLabel != null ? "Floor: " + levelLabel + ", " : "")
                            + "Time: " + getLondonTime()
                            + "\nLat: " + String.format("%.6f", currentLocation.latitude)
                            + ", Lon: " + String.format("%.6f", currentLocation.longitude))
                    .icon(BitmapDescriptorFactory.fromBitmap(generateNumberedMarkerIcon(markerSequence)))
                    .anchor(0.5f, 0.5f)
                    .zIndex(20));
            if (marker != null) {
                referenceMarkers.add(marker);
            }
        }

        Toast.makeText(getContext(),
                "Point #" + markerSequence + " added",
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Get current London (Europe/London) time as HH:mm:ss.
     */
    private String getLondonTime() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.UK);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("Europe/London"));
        return sdf.format(new java.util.Date());
    }

    /**
     * Get a human-readable display label for a floor number (e.g. "G", "B1", "1").
     */
    private String getLevelDisplayName(IndoorMapService.BuildingData building, int levelNumber) {
        if (building.levelLabels != null && building.levelLabels.containsKey(levelNumber)) {
            return building.levelLabels.get(levelNumber);
        }
        return "Floor " + levelNumber;
    }

    /**
     * Highlight the selected building on the map.
     * @param selectedPolygon The polygon representing the selected building
     */
    private void highlightSelectedVenue(Polygon selectedPolygon) {
        // Reset all buildings to default style
        for (Polygon polygon : venueOverlays) {
            polygon.setStrokeColor(Color.parseColor("#6200EE"));  // Purple
            polygon.setStrokeWidth(8f);
            polygon.setFillColor(Color.parseColor("#336200EE"));  // Semi-transparent purple
        }

        // Highlight the selected building
        selectedPolygon.setStrokeColor(Color.parseColor("#FF6200EE"));  // Bright purple
        selectedPolygon.setStrokeWidth(12f);                             // Thicker border
        selectedPolygon.setFillColor(Color.parseColor("#666200EE"));    // Darker fill

        Log.d("TrajectoryMapFragment", "Highlighted selected building");
    }

    // ========================================
    // Live Stats Features
    // ========================================

    /**
     * Start updating live stats periodically.
     */
    private void startStatsUpdates() {
        if (recordingStartTime == 0) {
            recordingStartTime = System.currentTimeMillis();
            totalDistance = 0.0;
            lastPositionForSpeed = currentLocation;
            lastSpeedUpdateTime = System.currentTimeMillis();
        }
        statsUpdateHandler.post(statsUpdateRunnable);
    }

    /**
     * Stop updating live stats.
     */
    private void stopStatsUpdates() {
        statsUpdateHandler.removeCallbacks(statsUpdateRunnable);
    }

    /**
     * Update all live stats displays.
     */
    private void updateLiveStats() {
        if (sensorFusion == null) return;

        // 1. Step count
        int steps = sensorFusion.getStepCount();
        statsSteps.setText(String.format("🚶 Steps: %d", steps));

        // 2. Distance traveled (calculated from trajectory points)
        if (polyline != null && polyline.getPoints().size() > 1) {
            List<LatLng> points = polyline.getPoints();
            double dist = calculatePolylineDistance(points);
            if (dist >= 1000) {
                statsDistance.setText(String.format("📏 Distance: %.2f km", dist / 1000));
            } else {
                statsDistance.setText(String.format("📏 Distance: %.1f m", dist));
            }
        } else {
            statsDistance.setText("📏 Distance: 0.0 m");
        }

        // 3. Recording duration
        if (recordingStartTime > 0) {
            long elapsedMillis = System.currentTimeMillis() - recordingStartTime;
            long seconds = elapsedMillis / 1000;
            long minutes = seconds / 60;
            seconds = seconds % 60;
            statsTime.setText(String.format("⏱️ Time: %02d:%02d", minutes, seconds));
        }

        // 4. Current speed (calculate from position changes)
        if (currentLocation != null && lastPositionForSpeed != null) {
            long currentTime = System.currentTimeMillis();
            long timeDelta = currentTime - lastSpeedUpdateTime;

            if (timeDelta >= 2000) { // Update speed every 2 seconds
                float[] results = new float[1];
                android.location.Location.distanceBetween(
                    lastPositionForSpeed.latitude, lastPositionForSpeed.longitude,
                    currentLocation.latitude, currentLocation.longitude,
                    results
                );
                double speed = results[0] / (timeDelta / 1000.0); // meters per second
                statsSpeed.setText(String.format("📍 Speed: %.2f m/s", speed));

                lastPositionForSpeed = currentLocation;
                lastSpeedUpdateTime = currentTime;
            }
        } else {
            statsSpeed.setText("📍 Speed: 0.0 m/s");
        }

        // 5. WiFi AP count
        List<Wifi> wifiList = sensorFusion.getWifiList();
        int wifiCount = (wifiList != null) ? wifiList.size() : 0;
        statsWifi.setText(String.format("📶 WiFi: %d APs", wifiCount));

        // 6. BLE device count (if available)
        // Note: BLE count not directly available in current SensorFusion interface
        // This would need to be added to SensorFusion if BLE scanning is implemented
        statsBle.setText("🔵 BLE: N/A");

        // 7. GPS info (accuracy and satellite count if available)
        float[] gnss = sensorFusion.getGNSSLatitude(false);
        if (gnss[0] != 0 || gnss[1] != 0) {
            // GPS is active, but we don't have accuracy/sat count directly
            // Could be enhanced by adding these to SensorFusion
            statsGps.setText("🛰️ GPS: Active");
        } else {
            statsGps.setText("🛰️ GPS: N/A");
        }

        // 8. Current floor
        String floorLabel = "N/A";
        if (activeBuilding != null && !activeBuilding.levelsAvailable.isEmpty()) {
            int levelNum = activeBuilding.levelsAvailable.get(activeLevelIdx);
            floorLabel = getLevelDisplayName(activeBuilding, levelNum);
        }
        statsFloor.setText(String.format("🏢 Floor: %s", floorLabel));
    }

    /**
     * Calculate total distance of a polyline in meters.
     */
    private double calculatePolylineDistance(List<LatLng> points) {
        double totalDist = 0.0;
        for (int i = 1; i < points.size(); i++) {
            LatLng prev = points.get(i - 1);
            LatLng curr = points.get(i);
            float[] results = new float[1];
            android.location.Location.distanceBetween(
                prev.latitude, prev.longitude,
                curr.latitude, curr.longitude,
                results
            );
            totalDist += results[0];
        }
        return totalDist;
    }

    /**
     * Reset stats when starting a new recording.
     */
    public void resetStats() {
        recordingStartTime = 0;
        totalDistance = 0.0;
        lastPositionForSpeed = null;
        lastSpeedUpdateTime = 0;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Stop stats updates when view is destroyed
        stopStatsUpdates();
    }

    // ========================================
    // Auto Floor Detection Features
    // ========================================

    /**
     * Calibrate base elevation (ground floor reference point).
     * Should be called when user enables auto floor or when on known ground floor.
     */
    private void calibrateBaseElevation() {
        if (sensorFusion != null) {
            baseElevation = sensorFusion.getElevation();
            baseElevationSet = true;
            Log.d("TrajectoryMapFragment", "Base elevation calibrated: " + baseElevation + "m");
        }
    }

    /**
     * Calculate floor number from current elevation.
     * Uses pressure sensor altitude relative to base elevation.
     *
     * @param currentElevation Current altitude from pressure sensor (meters)
     * @return Estimated floor number (0 = ground floor, negative = basement, positive = upper floors)
     */
    private int calculateFloorFromElevation(float currentElevation) {
        if (!baseElevationSet) {
            return 0; // Default to ground floor if not calibrated
        }

        float elevationDiff = currentElevation - baseElevation;
        int estimatedFloor = Math.round(elevationDiff / FLOOR_HEIGHT);

        // Only change floor if difference exceeds threshold to avoid jitter
        if (Math.abs(elevationDiff - (estimatedFloor * FLOOR_HEIGHT)) < FLOOR_THRESHOLD) {
            return estimatedFloor;
        }

        return 0;
    }

    /**
     * Perform auto floor detection and switch floors if needed.
     * Combines pressure sensor altitude with WiFi floor data for accuracy.
     */
    private void performAutoFloorDetection() {
        if (!isAutoFloorOn || sensorFusion == null) return;

        // Get elevation from pressure sensor
        float currentElevation = sensorFusion.getElevation();
        int elevationFloor = calculateFloorFromElevation(currentElevation);

        // Get WiFi floor estimate (if available)
        int wifiFloor = sensorFusion.getWifiFloor();

        // Combine both estimates: prioritize WiFi if available and recent, otherwise use elevation
        int targetFloor;
        if (wifiFloor != 0 && sensorFusion.isWifiPositionFresh(10000)) {
            // WiFi floor data is fresh (within 10 seconds), use it as primary source
            targetFloor = wifiFloor;

            // If elevation disagrees significantly, recalibrate base elevation
            if (Math.abs(elevationFloor - wifiFloor) > 1 && baseElevationSet) {
                // Adjust base elevation based on WiFi floor
                baseElevation = currentElevation - (wifiFloor * FLOOR_HEIGHT);
                Log.d("TrajectoryMapFragment", "Base elevation adjusted to: " + baseElevation + "m (WiFi floor: " + wifiFloor + ")");
            }
        } else {
            // WiFi data not available or stale, use pressure sensor
            targetFloor = elevationFloor;
        }

        // Switch floor if we have an active building with floor data
        if (activeBuilding != null && !activeBuilding.levelsAvailable.isEmpty()) {
            // Find the closest available floor to the target
            int currentFloor = activeBuilding.levelsAvailable.get(activeLevelIdx);

            if (currentFloor != targetFloor) {
                // Search for the target floor in available floors
                for (int i = 0; i < activeBuilding.levelsAvailable.size(); i++) {
                    if (activeBuilding.levelsAvailable.get(i) == targetFloor) {
                        activeLevelIdx = i;
                        renderLevelGeometry(activeBuilding, targetFloor);
                        String floorLabel = getLevelDisplayName(activeBuilding, targetFloor);
                        Log.d("TrajectoryMapFragment", "Auto floor switched to: " + floorLabel +
                              " (elevation: " + currentElevation + "m)");
                        break;
                    }
                }
            }
        }
    }

}
