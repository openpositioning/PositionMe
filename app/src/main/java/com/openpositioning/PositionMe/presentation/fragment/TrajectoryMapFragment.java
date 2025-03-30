package com.openpositioning.PositionMe.presentation.fragment;

import android.content.res.ColorStateList;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.google.android.material.button.MaterialButton;
import java.util.Objects;


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
    private LatLng rawCurrentLocation;    // For raw (PDR) trajectory
    private LatLng fusionCurrentLocation; // For fused (EKF) trajectory
    private Marker rawMarker;    // Marker for raw (PDR) trajectory (red arrow)
    private Marker fusionMarker; // Marker for fused (EKF) trajectory (green arrow)
    private Marker gnssMarker; // GNSS position marker
    private Circle gnssAccuracyCircle;
    private Polyline polyline; // Polyline representing user's movement path
    private boolean isRed = true; // Tracks whether the polyline color is red
    private boolean isGnssOn = false; // Tracks if GNSS tracking is enabled

    private Polyline gnssPolyline; // Polyline for GNSS path
    private Polyline fusionPolyline; // Polygon for the building
    private LatLng lastGnssLocation = null; // Stores the last GNSS location

    private LatLng pendingCameraPosition = null; // Stores pending camera movement
    private boolean hasPendingCameraMove = false; // Tracks if camera needs to move

    private IndoorMapManager indoorMapManager; // Manages indoor mapping
    private SensorFusion sensorFusion;


    // UI
    private Spinner switchMapSpinner;

    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;

    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;
    private Polygon buildingPolygon;
    private boolean showRawTrajectory = true;
    private boolean showFusionTrajectory = true;

    private MaterialButton showRawButton;
    private MaterialButton showFusionButton;

    private final List<Marker> emergencyExitMarkers = new ArrayList<>();
    private final List<Marker> liftMarkers = new ArrayList<>();
    private final List<Marker> toiletMarkers = new ArrayList<>();
    private final List<Marker> accessibleRouteMarkers = new ArrayList<>();

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
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);
        showRawButton = view.findViewById(R.id.showRawButton);
        showFusionButton = view.findViewById(R.id.showFusionButton);

        showRawButton.setOnClickListener(v -> {
            setShowRawTrajectory(!showRawTrajectory);
        });

        showFusionButton.setOnClickListener(v -> {
            setShowFusionTrajectory(!showFusionTrajectory);
        });

        showRawButton.setOnClickListener(v -> {
            setShowRawTrajectory(!showRawTrajectory);
        });

        showFusionButton.setOnClickListener(v -> {
            setShowFusionTrajectory(!showFusionTrajectory);
        });

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

                    // If we had a pending camera move, apply it now
                    if (hasPendingCameraMove && pendingCameraPosition != null) {
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
                        hasPendingCameraMove = false;
                        pendingCameraPosition = null;
                    }

                    drawBuildingPolygon();

                    Log.d("TrajectoryMapFragment", "onMapReady: Map is ready!");


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

        // Floor up/down logic
        autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {

            //TODO - fix the sensor fusion method to get the elevation (cannot get it from the current method)
//            float elevationVal = sensorFusion.getElevation();
//            indoorMapManager.setCurrentFloor((int)(elevationVal/indoorMapManager.getFloorHeight())
//                    ,true);
        });

        floorUpButton.setOnClickListener(v -> {
            // If user manually changes floor, turn off auto floor
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
                Log.d("currentfloor", "Building polygon added, vertex count: " + this.getCurrentBuilding());
                Log.d("currentfloor", "Building polygon added, vertex count: " + this.getCurrentFloor());
                //update indoor maker
                this.updateEmergencyExitMarkers();
                this.updateLiftMarkers();
                this.updateToiletMarkers();
                this.updateAccessibleRouteMarkers();
            }
        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
                Log.d("currentfloor", "Building polygon added, vertex count: " + this.getCurrentBuilding());
                Log.d("currentfloor", "Building polygon added, vertex count: " + this.getCurrentFloor());
                //update indoor maker
                this.updateEmergencyExitMarkers();
                this.updateLiftMarkers();
                this.updateToiletMarkers();
                this.updateAccessibleRouteMarkers();
            }
        });

        // Initialize sensorFusion instance
        sensorFusion = SensorFusion.getInstance();

        // Register callback for Wi-Fi floor changes
        sensorFusion.setOnWifiFloorChangedListener(newFloor -> {
            if (autoFloorSwitch.isChecked() && indoorMapManager != null) {
                Log.d("TrajectoryMapFragment", "Wi-Fi floor changed, updating floor to: " + newFloor);
                indoorMapManager.setCurrentFloor(newFloor, true);
                Log.d("currentfloor", "Register callback for Wi-Fi floor changes: " + this.getCurrentBuilding());
                Log.d("currentfloor", "Register callback for Wi-Fi floor changes: " + this.getCurrentFloor());
                //update indoor maker
                this.updateEmergencyExitMarkers();
                this.updateLiftMarkers();
                this.updateToiletMarkers();
                this.updateAccessibleRouteMarkers();
            }
        });
        sensorFusion.setTrajectoryMapFragment(this);
    }


    /**
     * Public getter to retrieve the underlying GoogleMap instance.
     * Returns null if the map is not yet ready.
     *
     * @return the GoogleMap instance
     */
    public GoogleMap getGoogleMap() {
        return gMap;
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
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        // Initialize indoor manager
        indoorMapManager = new IndoorMapManager(map);

        // Initialize an empty polyline
        polyline = map.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(5f)
                .add() // start empty
        );

        // GNSS path in blue
        gnssPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.BLUE)
                .width(5f)
                .add() // start empty
        );

        // Fusion path in green
        fusionPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.GREEN)
                .width(5f)
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
        LatLng oldLocation = rawCurrentLocation;
        rawCurrentLocation = newLocation;

        // If no marker, create it
        if (rawMarker == null) {
            rawMarker = gMap.addMarker(new MarkerOptions()
                    .position(newLocation)
                    .flat(true)
                    .title("Raw Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_24)))
            );
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
        } else {
            rawMarker.setPosition(newLocation);
            rawMarker.setRotation(orientation);
            gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
        }

        // Extend polyline if movement occurred
        if (oldLocation != null && !oldLocation.equals(newLocation) && polyline != null) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            points.add(newLocation);
            polyline.setPoints(points);
        }

        if (indoorMapManager != null) {
            // 只在 indoorMap 存在时才考虑更新
            indoorMapManager.setCurrentLocation(newLocation);

            boolean currentState = indoorMapManager.getIsIndoorMapSet();
            if (currentState != lastIndoorMapState) {
                setFloorControlsVisibility(currentState ? View.VISIBLE : View.GONE);
                lastIndoorMapState = currentState;

                Log.d("currentfloor", "Building polygon added, vertex count: " + this.getCurrentBuilding());
                Log.d("currentfloor", "Building polygon added, vertex count: " + this.getCurrentFloor());
                //update indoor maker
                this.updateEmergencyExitMarkers();
                this.updateLiftMarkers();
                this.updateToiletMarkers();
                this.updateAccessibleRouteMarkers();
            }
        }
    }

    private boolean lastIndoorMapState = false;


    /**
     * Update the user's current fused location on the map, create or move orientation marker,
     * and append to polyline if the user actually moved.
     *
     * @param newLocation The new location to plot.
     * @param orientation The user’s heading (e.g. from sensor fusion).
     */
    public void updateFusionLocation(@NonNull LatLng newLocation, float orientation) {
        if (gMap == null) return;

        // Keep track of current location
        LatLng oldLocation = fusionCurrentLocation;
        fusionCurrentLocation = newLocation;

        // If no marker, create it
        if (fusionMarker == null) {
            fusionMarker = gMap.addMarker(new MarkerOptions()
                    .position(newLocation)
                    .flat(true)
                    .title("Fusion Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_24_green)))
            );
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
        } else {
            fusionMarker.setPosition(newLocation);
            fusionMarker.setRotation(orientation);
            // Optionally move camera if needed
            // gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
        }

        // Extend polyline if movement occurred
        if (oldLocation != null && !oldLocation.equals(newLocation) && fusionPolyline != null) {
            List<LatLng> points = new ArrayList<>(fusionPolyline.getPoints());
            points.add(newLocation);
            fusionPolyline.setPoints(points);
        }

//        // Update indoor map overlay
//        if (indoorMapManager != null) {
//            indoorMapManager.setCurrentLocation(newLocation);
//            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
//        }
    }




    public void updateCalibrationPinLocation(@NonNull LatLng newLocation, boolean pinConfirmed) {

        if (gMap == null) return;

        // Keep track of current location


        if (pinConfirmed) {
            Marker orientationMarker = gMap.addMarker(new MarkerOptions()
                    .position(newLocation)
                    .flat(true)
                    .title("Tagged Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_assignment_turned_in_24_red)))
            );
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
        }

        //update indoor map overlay
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(newLocation);
            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
        }

        //update indoor maker
        this.updateEmergencyExitMarkers();
        this.updateLiftMarkers();
        this.updateToiletMarkers();
        this.updateAccessibleRouteMarkers();

    }

    public void updateEmergencyExitMarkers() {
        if (gMap == null) return;

        int currentFloor = this.getCurrentFloor();
        String currentBuilding = this.getCurrentBuilding();

        List<LatLng> exitLocations = null; // 提前定义

        if (currentFloor == 0 && Objects.equals(currentBuilding, "nucleus")) {
            exitLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 1 && Objects.equals(currentBuilding, "nucleus")) {
            exitLocations = Arrays.asList(
                    new LatLng(55.922839326615474, -3.17451573908329),
                    new LatLng(55.92283913875728, -3.1742961332201958),
                    new LatLng(55.92330295784777, -3.174157999455929)
            );
        }
        else if (currentFloor == 2 && Objects.equals(currentBuilding, "nucleus")) {
            exitLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 3 && Objects.equals(currentBuilding, "nucleus")) {
            exitLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 4 && Objects.equals(currentBuilding, "nucleus")) {
            exitLocations = Arrays.asList(
            );
        }

        // 清除旧的 marker
        for (Marker marker : emergencyExitMarkers) {
            marker.remove();
        }
        emergencyExitMarkers.clear();

        // 避免 nullPointerException
        if (exitLocations != null) {
            for (LatLng location : exitLocations) {
                Marker marker = gMap.addMarker(new MarkerOptions()
                        .position(location)
                        .flat(true)
                        .title("Emergency Exit")
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(requireContext(),
                                        R.drawable.iso_emergency_exit)))
                );
                if (marker != null) {
                    emergencyExitMarkers.add(marker);
                }
            }
        }
    }

    public void updateLiftMarkers() {
        if (gMap == null) return;

        int currentFloor = this.getCurrentFloor();
        String currentBuilding = this.getCurrentBuilding();

        List<LatLng> liftLocations = null;
        if (currentFloor == 0 && Objects.equals(currentBuilding, "nucleus")) {
            liftLocations = Arrays.asList(
                    new LatLng(55.92303883149652, -3.1743890047073364),
                    new LatLng(55.923040334354255, -3.1743494421243668),
                    new LatLng(55.92304277649792, -3.1743118911981583)
            );
        }
        else if (currentFloor == 1 && Objects.equals(currentBuilding, "nucleus")) {
            liftLocations = Arrays.asList(
                    new LatLng(55.923027560061676, -3.1743665412068367),
                    new LatLng(55.92303075363521, -3.17432664334774),
                    new LatLng(55.923030565777935, -3.174288421869278)
            );
        }
        else if (currentFloor == 2 && Objects.equals(currentBuilding, "nucleus")) {
            liftLocations = Arrays.asList(
                    new LatLng(55.92304484292707, -3.17434910684824),
                    new LatLng(55.923045970070206, -3.1743139028549194),
                    new LatLng(55.92304390364111, -3.1743836402893066)
            );
        }
        else if (currentFloor == 3 && Objects.equals(currentBuilding, "nucleus")) {
            liftLocations = Arrays.asList(
                    new LatLng(55.92304484292707, -3.17434910684824),
                    new LatLng(55.923045970070206, -3.1743139028549194),
                    new LatLng(55.92304390364111, -3.1743836402893066)
            );
        }
        else if (currentFloor == 4 && Objects.equals(currentBuilding, "nucleus")) {
            liftLocations = Arrays.asList(
                    new LatLng(55.92304484292707, -3.17434910684824),
                    new LatLng(55.923045970070206, -3.1743139028549194),
                    new LatLng(55.92304390364111, -3.1743836402893066)
            );
        }

        // 清除旧的 marker
        for (Marker marker : liftMarkers) {
            marker.remove();
        }
        liftMarkers.clear();

        // 添加新的 marker
        if (liftLocations != null) {
            for (LatLng location : liftLocations) {
                Marker marker = gMap.addMarker(new MarkerOptions()
                        .position(location)
                        .flat(true)
                        .title("Lift")
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(requireContext(),
                                        R.drawable.iso_lift)))
                );
                if (marker != null) {
                    liftMarkers.add(marker);
                }
            }
        }
    }

    public void updateToiletMarkers() {
        if (gMap == null) return;

        int currentFloor = this.getCurrentFloor();
        String currentBuilding = this.getCurrentBuilding();

        List<LatLng> toiletLocations = null;

        if (currentFloor == 0 && Objects.equals(currentBuilding, "nucleus")) {
            toiletLocations = Arrays.asList(
                    new LatLng(55.92308617148703, -3.174508698284626)
            );
        }
        else if (currentFloor == 1 && Objects.equals(currentBuilding, "nucleus")) {
            toiletLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 2 && Objects.equals(currentBuilding, "nucleus")) {
            toiletLocations = Arrays.asList(
                    new LatLng(55.92308617148703, -3.174508698284626)
            );
        }
        else if (currentFloor == 3 && Objects.equals(currentBuilding, "nucleus")) {
            toiletLocations = Arrays.asList(
                    new LatLng(55.92308617148703, -3.174508698284626)
            );
        }
        else if (currentFloor == 4 && Objects.equals(currentBuilding, "nucleus")) {
            toiletLocations = Arrays.asList(
            );
        }

        // 清除旧的 marker
        for (Marker marker : toiletMarkers) {
            marker.remove();
        }
        toiletMarkers.clear();

        // 添加新的 marker
        if (toiletLocations != null) {
            for (LatLng location : toiletLocations) {
                Marker marker = gMap.addMarker(new MarkerOptions()
                        .position(location)
                        .flat(true)
                        .title("Toilet")
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(requireContext(),
                                        R.drawable.iso_toilet_icon))) // 记得准备 iso_toilet 图标
                );
                if (marker != null) {
                    toiletMarkers.add(marker);
                }
            }
        }
    }

    public void updateAccessibleRouteMarkers() {
        if (gMap == null) return;

        int currentFloor = this.getCurrentFloor();
        String currentBuilding = this.getCurrentBuilding();

        List<LatLng> accessibleLocations = null;

        if (currentFloor == 0 && Objects.equals(currentBuilding, "nucleus")) {
            accessibleLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 1 && Objects.equals(currentBuilding, "nucleus")) {
            accessibleLocations = Arrays.asList(
                    new LatLng(55.92286243316557, -3.174589164555073),
                    new LatLng(55.923280978696596, -3.174441307783127)
            );
        }
        else if (currentFloor == 2 && Objects.equals(currentBuilding, "nucleus")) {
            accessibleLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 3 && Objects.equals(currentBuilding, "nucleus")) {
            accessibleLocations = Arrays.asList(
            );
        }
        else if (currentFloor == 4 && Objects.equals(currentBuilding, "nucleus")) {
            accessibleLocations = Arrays.asList(
            );
        }

        // 清除旧的 marker
        for (Marker marker : accessibleRouteMarkers) {
            marker.remove();
        }
        accessibleRouteMarkers.clear();

        // 添加新的 marker
        if (accessibleLocations != null) {
            for (LatLng location : accessibleLocations) {
                Marker marker = gMap.addMarker(new MarkerOptions()
                        .position(location)
                        .flat(true)
                        .title("Accessible Route")
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(requireContext(),
                                        R.drawable.iso_symbol_of_access))  // 换成你的无障碍图标
                        ));
                if (marker != null) {
                    accessibleRouteMarkers.add(marker);
                }
            }
        }
    }


    // get current floor - return current floor
    public int getCurrentFloor() {
        return indoorMapManager.getCurrentFloor();
    }

    // get current fuilding - return name of current building / int represent
    public String getCurrentBuilding() {
        return indoorMapManager.getCurrentBuilding();
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
        return rawCurrentLocation;
    }

    /**
     * Called when we want to set or update the GNSS marker position
     */
    public void updateGNSS(@NonNull LatLng location) {
        if (gMap == null) return;
        if (!isGnssOn) return;

        float accuracy = sensorFusion.getGnssAccuracy();

        if (gnssMarker == null) {
            // Create the GNSS marker for the first time
        gnssMarker = gMap.addMarker(new MarkerOptions()
                .position(location)
                .title("GNSS Position")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
        lastGnssLocation = location;
        // Create accuracy circle
        gnssAccuracyCircle = gMap.addCircle(new CircleOptions()
                .center(location)
                .radius(accuracy) // Accuracy in meters
                .strokeColor(Color.BLUE)
                .fillColor(Color.argb(50, 0, 0, 255)) // 半透明蓝色
                .strokeWidth(2f));
        } else {
            // Move existing GNSS marker
            gnssMarker.setPosition(location);
            if (gnssAccuracyCircle != null) {
                gnssAccuracyCircle.setCenter(location);
                gnssAccuracyCircle.setRadius(accuracy);
            }

            // Add a segment to the blue GNSS line, if this is a new location
            if (lastGnssLocation != null && !lastGnssLocation.equals(location)) {
                List<LatLng> gnssPoints = new ArrayList<>(gnssPolyline.getPoints());
                gnssPoints.add(location);
                gnssPolyline.setPoints(gnssPoints);
            }
            lastGnssLocation = location;
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
        if (gnssAccuracyCircle != null) {
            gnssAccuracyCircle.remove();
            gnssAccuracyCircle = null;
        }
    }

    /**
     * Whether user is currently showing GNSS or not
     */
    public boolean isGnssEnabled() {
        return isGnssOn;
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
        if (rawMarker != null) {
            rawMarker.remove();
            rawMarker = null;
        }
        if (fusionMarker != null) {
            fusionMarker.remove();
            fusionMarker = null;
        }
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
        lastGnssLocation = null;
        rawCurrentLocation = null;
        fusionCurrentLocation = null;

        // Re-create empty polylines with your chosen colors
        if (gMap != null) {
            polyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.RED)
                    .width(5f)
                    .add());
            gnssPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.BLUE)
                    .width(5f)
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



    public void setShowRawTrajectory(boolean show) {
        this.showRawTrajectory = show;
        if (polyline != null) {
            polyline.setVisible(show);
        }
        if (rawMarker != null) {
            rawMarker.setVisible(show);
        }
        if (showRawButton != null) {
            showRawButton.setBackgroundTintList(ColorStateList.valueOf(show ? Color.RED : Color.GRAY));
        }
    }


    public void setShowFusionTrajectory(boolean show) {
        this.showFusionTrajectory = show;
        if (fusionPolyline != null) {
            fusionPolyline.setVisible(show);
        }
        if (fusionMarker != null) {
            fusionMarker.setVisible(show);
        }
        if (showFusionButton != null) {
            showFusionButton.setBackgroundTintList(ColorStateList.valueOf(show ? Color.GREEN : Color.GRAY));
        }
    }
}
