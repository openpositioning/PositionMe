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
import com.openpositioning.PositionMe.sensors.FloorplanAPI;
import com.openpositioning.PositionMe.sensors.Wifi;
import android.widget.Toast;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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

    // WiFi positioning display
    private Marker wifiMarker; // WiFi position marker
    private Polyline wifiPolyline; // Polyline for WiFi path
    private LatLng lastWifiLocation = null; // Stores the last WiFi location
    private boolean isWifiOn = false; // WiFi display disabled by default
    private boolean isAutoFloorOn = false; // Auto floor switching via WiFi

    private LatLng pendingCameraPosition = null; // Stores pending camera movement
    private boolean hasPendingCameraMove = false; // Tracks if camera needs to move

    private IndoorMapManager indoorMapManager; // Manages indoor mapping
    private SensorFusion sensorFusion;


    // UI
    private Spinner switchMapSpinner;

    private SwitchMaterial gnssSwitch;
    private SwitchMaterial wifiSwitch;
    private SwitchMaterial autoFloorSwitch;

    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;
    private Polygon buildingPolygon;

    // Indoor map: API client and venue polygons
    private FloorplanAPI floorplanAPI;
    private List<Polygon> buildingPolygons = new ArrayList<>();
    private FloorplanAPI.Venue selectedVenue = null;

    // Floor display state
    private List<Polygon> floorPolygons = new ArrayList<>();     // Current floor wall shapes
    private Polygon floorBackground = null;                      // White background overlay
    private int currentFloorIndex = 0;                           // Index into availableFloors

    // Test point markers
    private com.google.android.material.floatingactionbutton.FloatingActionButton addTestPointButton;
    private List<Marker> testPointMarkers = new ArrayList<>();   // Markers on map
    private int testPointCount = 0;                              // Counter

    // WiFi radiomap collection: stores reference points for upload
    private List<JSONObject> radiomapPoints = new ArrayList<>();
    private com.google.android.material.floatingactionbutton.FloatingActionButton uploadRadiomapButton;

    // Camera follow flag: follows user during recording, stops after
    private boolean cameraFollowing = true;

    // Indoor mode: auto-select first venue when buildings load
    private boolean autoSelectVenue = false;

    public void setAutoSelectVenue(boolean auto) {
        this.autoSelectVenue = auto;
    }

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

        // Initialize SensorFusion and FloorplanAPI
        sensorFusion = SensorFusion.getInstance();
        floorplanAPI = new FloorplanAPI(requireContext());

        // Prevent floor button container from intercepting map touch events
        ViewGroup floorButtonContainer = (ViewGroup) view.findViewById(R.id.floorUpButton).getParent();
        if (floorButtonContainer != null) {
            floorButtonContainer.setClickable(false);
            floorButtonContainer.setFocusable(false);
        }

        // Grab references to UI controls
        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch      = view.findViewById(R.id.gnssSwitch);
        wifiSwitch      = view.findViewById(R.id.wifiSwitch);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);
        addTestPointButton = view.findViewById(R.id.addTestPointButton);
        uploadRadiomapButton = view.findViewById(R.id.uploadRadiomapButton);

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

                    // Listen for polygon taps to select a building
                    gMap.setOnPolygonClickListener(polygon -> {
                        Object tag = polygon.getTag();
                        if (tag instanceof FloorplanAPI.Venue) {
                            FloorplanAPI.Venue venue = (FloorplanAPI.Venue) tag;
                            onVenueSelected(venue, polygon);
                        }
                    });

                    // Request nearby buildings using real GPS/WiFi data
                    requestNearbyBuildings();


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

        // WiFi Switch
        wifiSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isWifiOn = isChecked;
            if (!isChecked && wifiMarker != null) {
                wifiMarker.remove();
                wifiMarker = null;
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
                    int activeColor = (selectedVenue != null) ? Color.parseColor("#9C27B0") : Color.RED;
                    switchColorButton.setBackgroundColor(activeColor);
                    polyline.setColor(activeColor);
                    isRed = true;
                }
            }
        });

        // Auto floor switching via barometer
        autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            isAutoFloorOn = isChecked;
            if (isChecked && selectedVenue != null && !selectedVenue.availableFloors.isEmpty()) {
                // Calibrate barometer with the currently displayed floor
                int currentFloor = selectedVenue.availableFloors.get(currentFloorIndex);
                sensorFusion.calibrateBarometerForFloor(currentFloor);
                Toast.makeText(getContext(),
                        "Auto floor calibrated at " + sensorFusion.getFloorDisplayName(currentFloor),
                        Toast.LENGTH_SHORT).show();
            }
        });

        floorUpButton.setOnClickListener(v -> {
            // API venue floor switching takes priority
            if (selectedVenue != null && !selectedVenue.availableFloors.isEmpty()) {
                floorUp();
                // If auto floor is on, recalibrate to the newly selected floor
                if (isAutoFloorOn) {
                    int newFloor = selectedVenue.availableFloors.get(currentFloorIndex);
                    sensorFusion.calibrateBarometerForFloor(newFloor);
                    Toast.makeText(getContext(),
                            "Recalibrated at " + sensorFusion.getFloorDisplayName(newFloor),
                            Toast.LENGTH_SHORT).show();
                }
            } else if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
            }
        });

        floorDownButton.setOnClickListener(v -> {
            if (selectedVenue != null && !selectedVenue.availableFloors.isEmpty()) {
                floorDown();
                // If auto floor is on, recalibrate to the newly selected floor
                if (isAutoFloorOn) {
                    int newFloor = selectedVenue.availableFloors.get(currentFloorIndex);
                    sensorFusion.calibrateBarometerForFloor(newFloor);
                    Toast.makeText(getContext(),
                            "Recalibrated at " + sensorFusion.getFloorDisplayName(newFloor),
                            Toast.LENGTH_SHORT).show();
                }
            } else if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
            }
        });

        // Test point button (also captures WiFi fingerprint for radiomap)
        addTestPointButton.setOnClickListener(v -> addTestPoint());

        // Upload radiomap button
        uploadRadiomapButton.setOnClickListener(v -> uploadRadiomap());
    }

    /**
     * Configure map gestures, indoor manager, and create empty polylines for PDR/GNSS/WiFi paths.
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

        // Initialize an empty polyline (zIndex above floor shapes)
        polyline = map.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(5f)
                .zIndex(10)
                .add() // start empty
        );

        // GNSS path in blue
        gnssPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.BLUE)
                .width(5f)
                .zIndex(10)
                .add() // start empty
        );

        // WiFi path in green
        wifiPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.GREEN)
                .width(5f)
                .zIndex(10)
                .add() // start empty
        );
    }


    /**
     * Set up the map type spinner (Hybrid / Normal / Satellite).
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
            if (cameraFollowing) {
                gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
            }
        } else {
            // Update marker position + orientation
            orientationMarker.setPosition(newLocation);
            orientationMarker.setRotation(orientation);
            if (cameraFollowing) {
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
            if (selectedVenue == null || selectedVenue.availableFloors.isEmpty()) {
                setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
            }
        }
    }



    /**
     * Set the initial camera position. Queues the move if the map is not yet ready.
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
     * Update the WiFi positioning marker and polyline on the map.
     */
    public void updateWifiLocation(@NonNull LatLng wifiLocation) {
        if (gMap == null) return;

        // Auto floor switching runs regardless of WiFi display toggle
        if (isAutoFloorOn && selectedVenue != null && !selectedVenue.availableFloors.isEmpty()) {
            int estimatedFloor = sensorFusion.getEstimatedFloor();
            int idx = selectedVenue.availableFloors.indexOf(estimatedFloor);
            Log.d("AutoFloor", "estimated=" + estimatedFloor
                    + " wifiFloor=" + sensorFusion.getWifiFloor()
                    + " pressure=" + sensorFusion.getElevation()
                    + " idx=" + idx + " current=" + currentFloorIndex
                    + " floors=" + selectedVenue.availableFloors);
            if (idx >= 0 && idx != currentFloorIndex) {
                currentFloorIndex = idx;
                displayFloorShapes(selectedVenue, estimatedFloor);
            }
        }

        if (!isWifiOn) return;

        if (wifiMarker == null) {
            wifiMarker = gMap.addMarker(new MarkerOptions()
                    .position(wifiLocation)
                    .title("WiFi Position")
                    .icon(BitmapDescriptorFactory
                            .defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
            lastWifiLocation = wifiLocation;
        } else {
            wifiMarker.setPosition(wifiLocation);

            if (lastWifiLocation != null && !lastWifiLocation.equals(wifiLocation)) {
                List<LatLng> wifiPoints = new ArrayList<>(wifiPolyline.getPoints());
                wifiPoints.add(wifiLocation);
                wifiPolyline.setPoints(wifiPoints);
            }
            lastWifiLocation = wifiLocation;
        }
    }

    /**
     * Whether user is currently showing WiFi positioning or not.
     */
    public boolean isWifiEnabled() {
        return isWifiOn;
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
        this.cameraFollowing = following;
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
        if (wifiPolyline != null) {
            wifiPolyline.remove();
            wifiPolyline = null;
        }
        if (orientationMarker != null) {
            orientationMarker.remove();
            orientationMarker = null;
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
        lastWifiLocation = null;
        currentLocation  = null;

        // Clear test point markers
        for (Marker m : testPointMarkers) {
            m.remove();
        }
        testPointMarkers.clear();
        testPointCount = 0;

        // Clear floor label markers
        for (Marker m : floorLabelMarkers) {
            m.remove();
        }
        floorLabelMarkers.clear();

        // Restore camera following for next recording
        cameraFollowing = true;

        // Re-create empty polylines with your chosen colors (zIndex above floor shapes)
        if (gMap != null) {
            int pdrColor = (selectedVenue != null) ? Color.parseColor("#9C27B0") : Color.RED;
            polyline = gMap.addPolyline(new PolylineOptions()
                    .color(pdrColor)
                    .width(5f)
                    .zIndex(10)
                    .add());
            gnssPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.BLUE)
                    .width(5f)
                    .zIndex(10)
                    .add());
            wifiPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.GREEN)
                    .width(5f)
                    .zIndex(10)
                    .add());
        }
    }

    /**
     * Draw hard-coded building outlines (Nucleus, NKML, FJB, Faraday) for reference.
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
    // Indoor Map: Building Outlines & Floors
    // ========================================

    /**
     * Request nearby buildings using real GPS position and WiFi MAC addresses.
     * Falls back to Murchison House coordinates if GPS is not available.
     */
    private void requestNearbyBuildings() {
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

        floorplanAPI.requestNearbyFloorplans(
                lat,
                lon,
                macs,
                new FloorplanAPI.FloorplanCallback() {
                    @Override
                    public void onSuccess(List<FloorplanAPI.Venue> venues) {
                        Log.d("TrajectoryMapFragment", "Got " + venues.size() + " buildings");
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                displayBuildingOutlines(venues);
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
     * Draw building outlines on the map from API venue data.
     * Each polygon is clickable and tagged with its Venue for selection.
     * @param venues list of nearby venues from FloorplanAPI
     */
    private void displayBuildingOutlines(List<FloorplanAPI.Venue> venues) {
        if (gMap == null) {
            Log.w("TrajectoryMapFragment", "Map not ready, cannot display buildings");
            return;
        }

        Log.d("TrajectoryMapFragment", "Drawing " + venues.size() + " building outlines");

        // Clear previous building outlines
        for (Polygon polygon : buildingPolygons) {
            polygon.remove();
        }
        buildingPolygons.clear();

        // Draw outline for each venue
        for (FloorplanAPI.Venue venue : venues) {
            PolygonOptions polygonOptions = new PolygonOptions()
                    .strokeColor(Color.parseColor("#6200EE"))
                    .strokeWidth(8f)
                    .fillColor(Color.parseColor("#336200EE"))
                    .clickable(true)
                    .zIndex(2);

            // Log raw data for debugging
            Log.d("TrajectoryMapFragment", "Venue: " + venue.name
                    + " | outline: [" + venue.outline + "]"
                    + " | mapShapes length: " + (venue.mapShapes != null ? venue.mapShapes.length() : 0));

            // Build polygon from API outline coordinates
            if (!venue.outlineCoords.isEmpty()) {
                for (double[] coord : venue.outlineCoords) {
                    polygonOptions.add(new LatLng(coord[0], coord[1]));
                }
            } else {
                Log.w("TrajectoryMapFragment", "Venue " + venue.name + " has no outline coords, skipping");
                continue;
            }

            Polygon polygon = gMap.addPolygon(polygonOptions);
            polygon.setTag(venue);
            buildingPolygons.add(polygon);

            Log.d("TrajectoryMapFragment", "Added building: " + venue.name +
                    ", outline points: " + venue.outlineCoords.size());
        }

        // Auto-select venue in indoor mode
        if (autoSelectVenue && !venues.isEmpty() && !buildingPolygons.isEmpty()) {
            FloorplanAPI.Venue venue = venues.get(0);
            Polygon polygon = buildingPolygons.get(0);
            onVenueSelected(venue, polygon);
            autoSelectVenue = false;

            // Move camera to building center
            if (!venue.outlineCoords.isEmpty()) {
                LatLng center = computePolygonCenter(venue.outlineCoords);
                gMap.animateCamera(CameraUpdateFactory.newLatLngZoom(center, 20f));
            }
            Log.d("TrajectoryMapFragment", "Indoor mode: auto-selected venue " + venue.name);
        } else {
            Toast.makeText(getContext(),
                    "Found " + venues.size() + " nearby buildings, tap to view details",
                    Toast.LENGTH_LONG).show();
        }

        Log.d("TrajectoryMapFragment", "Building outlines drawn: " + venues.size());
    }

    /**
     * Handle venue selection: highlight building, load floor shapes, show controls.
     * @param venue   the selected venue
     * @param polygon the corresponding map polygon
     */
    private void onVenueSelected(FloorplanAPI.Venue venue, Polygon polygon) {
        Log.d("TrajectoryMapFragment", "Venue selected: " + venue.name);

        selectedVenue = venue;
        highlightSelectedVenue(polygon);

        // Store floor name map in SensorFusion for floor label conversion
        if (venue.floorNameMap != null) {
            sensorFusion.setVenueFloorNameMap(venue.floorNameMap);
        }

        // Switch PDR polyline to purple for indoor
        if (polyline != null) {
            polyline.setColor(Color.parseColor("#9C27B0"));
        }

        // Display floor shapes if available
        if (!venue.availableFloors.isEmpty()) {
            currentFloorIndex = 0;
            int firstFloor = venue.availableFloors.get(0);
            displayFloorShapes(venue, firstFloor);

            // Show floor control buttons
            setFloorControlsVisibility(View.VISIBLE);

            String floorLabel = getFloorLabel(venue, firstFloor);
            String message = venue.name + " | "
                    + venue.availableFloors.size() + " floors"
                    + " | Current: " + floorLabel;
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        } else {
            setFloorControlsVisibility(View.GONE);
            Toast.makeText(getContext(), venue.name, Toast.LENGTH_SHORT).show();
        }
    }

    // Floor label markers displayed at building center
    private List<Marker> floorLabelMarkers = new ArrayList<>();

    /**
     * Display floor shapes for a specific floor - architectural blueprint style.
     *
     * API returns ONLY wall polygons (indoor_type="wall", no room names/types).
     * All 178 shapes are wall segments. Rooms are the white spaces between walls.
     * Rendering: light background + solid dark wall fills = blueprint effect.
     */
    private void displayFloorShapes(FloorplanAPI.Venue venue, int floorNumber) {
        if (gMap == null) return;

        // Clear previous
        for (Polygon p : floorPolygons) { p.remove(); }
        floorPolygons.clear();
        for (Marker m : floorLabelMarkers) { m.remove(); }
        floorLabelMarkers.clear();
        if (floorBackground != null) { floorBackground.remove(); floorBackground = null; }

        // 1) Light background = "room" space (building outline)
        if (!venue.outlineCoords.isEmpty()) {
            PolygonOptions bgOptions = new PolygonOptions()
                    .fillColor(Color.argb(235, 236, 239, 241))  // Light blue-grey #ECEFF1
                    .strokeColor(Color.parseColor("#263238"))     // Dark building outline
                    .strokeWidth(3.5f)
                    .zIndex(3);
            for (double[] coord : venue.outlineCoords) {
                bgOptions.add(new LatLng(coord[0], coord[1]));
            }
            floorBackground = gMap.addPolygon(bgOptions);
        }

        // 2) All shapes are wall segments → render as solid dark fills
        int count = 0;
        for (FloorplanAPI.FloorShape shape : venue.floorShapes) {
            if (shape.floor == floorNumber && !shape.coords.isEmpty()) {
                PolygonOptions options = new PolygonOptions()
                        .strokeColor(Color.parseColor("#1A237E"))
                        .strokeWidth(0.8f)
                        .fillColor(Color.parseColor("#E0263238"))
                        .zIndex(4);

                for (double[] coord : shape.coords) {
                    options.add(new LatLng(coord[0], coord[1]));
                }
                floorPolygons.add(gMap.addPolygon(options));
                count++;
            }
        }

        // 3) Add floor label at building center
        if (!venue.outlineCoords.isEmpty()) {
            String label = getFloorLabel(venue, floorNumber);
            LatLng center = computePolygonCenter(venue.outlineCoords);
            Marker floorLabel = gMap.addMarker(new MarkerOptions()
                    .position(center)
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            createFloorLabelBitmap(label)))
                    .anchor(0.875f, 0.0f)
                    .zIndex(6)
                    .flat(true));
            if (floorLabel != null) {
                floorLabelMarkers.add(floorLabel);
            }
        }

        Log.d("TrajectoryMapFragment", "Floor " + floorNumber
                + ": displayed " + count + " wall shapes (blueprint style)");
    }

    /**
     * Compute the centroid of a polygon.
     */
    private LatLng computePolygonCenter(List<double[]> coords) {
        double latSum = 0, lonSum = 0;
        for (double[] c : coords) {
            latSum += c[0];
            lonSum += c[1];
        }
        return new LatLng(latSum / coords.size(), lonSum / coords.size());
    }

    /**
     * Create a floor label bitmap (floor name only).
     */
    private android.graphics.Bitmap createFloorLabelBitmap(String text) {
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

        // Border stroke
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
     * Switch to the next floor (up).
     */
    private void floorUp() {
        if (selectedVenue == null || selectedVenue.availableFloors.isEmpty()) return;
        if (currentFloorIndex < selectedVenue.availableFloors.size() - 1) {
            currentFloorIndex++;
            int floor = selectedVenue.availableFloors.get(currentFloorIndex);
            displayFloorShapes(selectedVenue, floor);
            Toast.makeText(getContext(), getFloorLabel(selectedVenue, floor), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Switch to the previous floor (down).
     */
    private void floorDown() {
        if (selectedVenue == null || selectedVenue.availableFloors.isEmpty()) return;
        if (currentFloorIndex > 0) {
            currentFloorIndex--;
            int floor = selectedVenue.availableFloors.get(currentFloorIndex);
            displayFloorShapes(selectedVenue, floor);
            Toast.makeText(getContext(), getFloorLabel(selectedVenue, floor), Toast.LENGTH_SHORT).show();
        }
    }

    // ========================================
    // Test Points & Radiomap Collection
    // ========================================

    /**
     * Add a test point at the user's current position.
     * Places a numbered marker on the map and stores the point in SensorFusion.
     */
    private void addTestPoint() {
        if (currentLocation == null) {
            Toast.makeText(getContext(), "No location available", Toast.LENGTH_SHORT).show();
            return;
        }

        SensorFusion sensorFusion = SensorFusion.getInstance();
        testPointCount++;

        // Get floor from sensor fusion (consistent source for all points)
        String floorLabel = sensorFusion.getEstimatedFloorLabel();
        int floorNumber = sensorFusion.getLastEstimatedFloor();

        // Store in SensorFusion
        sensorFusion.addTestPoint(
                currentLocation.latitude,
                currentLocation.longitude,
                sensorFusion.getElevation(),
                floorLabel
        );

        // Capture WiFi fingerprint for radiomap
        int wifiCount = captureRadiomapPoint(currentLocation, floorNumber != Integer.MIN_VALUE ? floorNumber : 0);

        // Add numbered marker on the map
        if (gMap != null) {
            Marker marker = gMap.addMarker(new MarkerOptions()
                    .position(currentLocation)
                    .title("Test Point #" + testPointCount)
                    .snippet((floorLabel != null ? "Floor: " + floorLabel + "\n" : "")
                            + "Time: " + getLondonTime()
                            + "\nLat: " + String.format("%.6f", currentLocation.latitude)
                            + ", Lon: " + String.format("%.6f", currentLocation.longitude)
                            + "\nWiFi APs: " + wifiCount)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                    .zIndex(10));
            if (marker != null) {
                testPointMarkers.add(marker);
            }
        }

        Toast.makeText(getContext(),
                "Point #" + testPointCount + " (" + wifiCount + " APs, total " + radiomapPoints.size() + " points)",
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Capture current WiFi fingerprint and store as a radiomap reference point.
     * @return number of WiFi APs captured
     */
    private int captureRadiomapPoint(LatLng location, int floor) {
        SensorFusion sf = SensorFusion.getInstance();
        List<Wifi> wifiList = sf.getWifiList();
        if (wifiList == null || wifiList.isEmpty()) return 0;

        try {
            JSONObject wf = new JSONObject();
            for (Wifi w : wifiList) {
                wf.put(String.valueOf(w.getBssid()), w.getLevel());
            }
            JSONObject point = new JSONObject();
            point.put("wf", wf);
            point.put("lat", location.latitude);
            point.put("lon", location.longitude);
            point.put("floor", floor);
            radiomapPoints.add(point);
            Log.d("Radiomap", "Captured point #" + radiomapPoints.size()
                    + " at " + location.latitude + "," + location.longitude
                    + " floor=" + floor + " APs=" + wifiList.size());
            return wifiList.size();
        } catch (JSONException e) {
            Log.e("Radiomap", "Error creating radiomap point: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Upload collected radiomap points to OpenPositioning server.
     * POST /api/radiomap/upload/?key={master_key}
     */
    private void uploadRadiomap() {
        if (radiomapPoints.isEmpty()) {
            Toast.makeText(getContext(), "No reference points collected", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONArray radiomap = new JSONArray(radiomapPoints);
            JSONObject body = new JSONObject();
            body.put("api_key", "MShXCzrAnhyDauNeeP_O8g");
            body.put("label", "murchison_house");
            body.put("radiomap", radiomap);

            final String jsonBody = body.toString();
            Log.d("Radiomap", "Upload body: " + jsonBody.substring(0, Math.min(500, jsonBody.length())));

            String url = "https://openpositioning.org/api/radiomap/upload/?key=ewireless";
            final int pointCount = radiomapPoints.size();

            RequestQueue queue = Volley.newRequestQueue(requireContext());
            StringRequest request = new StringRequest(
                    Request.Method.POST, url,
                    response -> {
                        Log.d("Radiomap", "Upload success: " + response);
                        Toast.makeText(getContext(),
                                "Radiomap uploaded! (" + pointCount + " points)",
                                Toast.LENGTH_LONG).show();
                        radiomapPoints.clear();
                    },
                    error -> {
                        String errorBody = "";
                        if (error.networkResponse != null && error.networkResponse.data != null) {
                            errorBody = new String(error.networkResponse.data);
                        }
                        int code = error.networkResponse != null ? error.networkResponse.statusCode : -1;
                        Log.e("Radiomap", "Upload failed: code=" + code
                                + " body=" + errorBody
                                + " error=" + (error.getMessage() != null ? error.getMessage() : "null"));
                        Toast.makeText(getContext(),
                                "Upload failed (code " + code + ") " + errorBody,
                                Toast.LENGTH_LONG).show();
                    }
            ) {
                @Override
                public String getBodyContentType() {
                    return "application/json; charset=utf-8";
                }

                @Override
                public byte[] getBody() {
                    return jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
            };
            queue.add(request);
            Toast.makeText(getContext(), "Uploading " + pointCount + " points...", Toast.LENGTH_SHORT).show();
        } catch (JSONException e) {
            Log.e("Radiomap", "Error building upload JSON: " + e.getMessage());
        }
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
     * Get a display label for a floor number (e.g. "LG", "GF", "1F").
     */
    private String getFloorLabel(FloorplanAPI.Venue venue, int floorNumber) {
        return SensorFusion.getInstance().getFloorDisplayName(floorNumber);
    }

    /**
     * Highlight the selected building and reset all others to default style.
     * @param selectedPolygon the polygon to highlight
     */
    private void highlightSelectedVenue(Polygon selectedPolygon) {
        // Reset all buildings to default style
        for (Polygon polygon : buildingPolygons) {
            polygon.setStrokeColor(Color.parseColor("#6200EE"));
            polygon.setStrokeWidth(8f);
            polygon.setFillColor(Color.parseColor("#336200EE"));
        }

        // Highlight the selected building
        selectedPolygon.setStrokeColor(Color.parseColor("#FF6200EE"));
        selectedPolygon.setStrokeWidth(12f);
        selectedPolygon.setFillColor(Color.parseColor("#666200EE"));

        Log.d("TrajectoryMapFragment", "Highlighted selected building");
    }

}
