package com.openpositioning.PositionMe.presentation.fragment;

import static com.openpositioning.PositionMe.fusion.FusionConstants.LINE_WEIGHT_PARTICLE;
import static com.openpositioning.PositionMe.fusion.FusionConstants.MAP_PARTICLE_COLOUR;
import static com.openpositioning.PositionMe.fusion.FusionConstants.MAP_PARTICLE_WEIGHTING;
import static com.openpositioning.PositionMe.fusion.FusionConstants.MAP_WIFI_COLOUR;
import static com.openpositioning.PositionMe.fusion.FusionConstants.OBSERVATION_TYPE_WIFI;
import static com.openpositioning.PositionMe.fusion.FusionConstants.WIFI_STD_DEV;
import static com.openpositioning.PositionMe.utils.BuildingConstants.COLOUR_FLOOR_PLAN_FILL_PREVIEW;
import static com.openpositioning.PositionMe.utils.BuildingConstants.COLOUR_FLOOR_PLAN_FILL_TRANSPARENT;
import static com.openpositioning.PositionMe.utils.BuildingConstants.COLOUR_PATH_COLOUR;
import static com.openpositioning.PositionMe.utils.BuildingConstants.COLOUR_PATH_FUSION;
import static com.openpositioning.PositionMe.utils.BuildingConstants.COLOUR_PATH_GNSS;
import static com.openpositioning.PositionMe.utils.BuildingConstants.MAP_DRAWING_PRIORITY_MAX;
import static com.openpositioning.PositionMe.utils.UtilConstants.LINE_WEIGHT_PATH;
import static com.openpositioning.PositionMe.utils.UtilConstants.ZOOM_LEVEL_DEFAULT;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.fusion.Particle;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.Building;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A fragment responsible for displaying a trajectory map using Google Maps.
 *
 * <p>The TrajectoryMapFragment provides a map interface for visualizing movement trajectories, GNSS
 * tracking, and indoor mapping. It manages map settings, user interactions, and real-time updates
 * to user location and GNSS timedMarkers.
 *
 * <p>Key Features: - Displays a Google Map with support for different map types (Hybrid, Normal,
 * Satellite). - Tracks and visualizes user movement using polylines. - Supports GNSS position
 * updates and visual representation. - Includes indoor mapping with floor selection and auto-floor
 * adjustments. - Allows user interaction through map controls and UI elements.
 *
 * @see com.openpositioning.PositionMe.presentation.activity.RecordingActivity The activity hosting
 *     this fragment.
 * @see com.openpositioning.PositionMe.utils.IndoorMapManager Utility for managing indoor map
 *     overlays.
 * @see com.openpositioning.PositionMe.utils.UtilFunctions Utility functions for UI and graphics
 *     handling.
 * @author Mate Stodulka
 */
public class TrajectoryMapFragment extends Fragment {
    private static final String TAG = "TrajectoryMapFragment";

    // Google Maps instance
    private GoogleMap gMap;

    // Lines and headers for each type of positioning
    private Polyline pdrPolyline;
    private Marker pdrMarker;
    private Marker gnssMarker;
    private Polyline gnssPolyline;
    private Marker fusionMarker;
    private Polyline fusionPolyline;

    private LatLng currentLocation; // Stores the user's current location
    private LatLng lastGnssLocation = null; // Stores the last GNSS location
    private LatLng lastFusedLocation = null; //

    private LatLng pendingCameraPosition = null; // Stores pending camera movement
    private boolean hasPendingCameraMove = false; // Tracks if camera needs to move

    private IndoorMapManager indoorMapManager; // Manages indoor mapping
    private SensorFusion sensorFusion;

    // UI
    private Spinner switchMapSpinner;
    private SwitchMaterial gnssSwitch;
    private SwitchMaterial pdrSwitch;
    private SwitchMaterial wifiAPSSwitch;
    private SwitchMaterial autoFloorSwitch;
    private SwitchMaterial autopanSwitch;
    private SwitchMaterial showParticlesSwitch;
    private boolean isInsideBuilding = false;
    private String currentFloorName = "no_floor";

    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton,
            floorDownButton;
    private List<Marker> timedMarkers = new ArrayList<>();
    private List<Circle> particleCircles = new ArrayList<>();
    private List<Circle> wifiAPSCircles = new ArrayList<>();

    public TrajectoryMapFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sensorFusion = SensorFusion.getInstance();
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        // Inflate the separate layout containing map + map-related UI
        return inflater.inflate(R.layout.fragment_trajectory_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Grab references to UI controls
        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        autopanSwitch = view.findViewById(R.id.switchAutopan);

        gnssSwitch = view.findViewById(R.id.gnssSwitch);
        pdrSwitch = view.findViewById(R.id.pdrSwitch);
        wifiAPSSwitch = view.findViewById(R.id.wifiAPSSwitch);

        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        showParticlesSwitch = view.findViewById(R.id.showParticles);
        floorUpButton = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);

        sensorFusion = SensorFusion.getInstance();

        // Setup floor up/down UI hidden initially until we know there's an indoor map
        setFloorUIVisibility(View.GONE);

        // Initialize the map asynchronously
        SupportMapFragment mapFragment =
                (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.trajectoryMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(
                    new OnMapReadyCallback() {
                        @Override
                        public void onMapReady(@NonNull GoogleMap googleMap) {
                            // Assign the provided googleMap to your field variable
                            gMap = googleMap;
                            // Initialize map settings with the now non-null gMap
                            initMapSettings(gMap);

                            // If we had a pending camera move, apply it now
                            if (hasPendingCameraMove && pendingCameraPosition != null) {
                                gMap.moveCamera(
                                        CameraUpdateFactory.newLatLngZoom(
                                                pendingCameraPosition, ZOOM_LEVEL_DEFAULT));
                                hasPendingCameraMove = false;
                                pendingCameraPosition = null;
                            }

                            Log.d(TAG, "onMapReady: Map is ready!");
                        }
                    });
        }

        // Map type spinner setup
        initMapTypeSpinner();

        // GNSS Switch
        gnssSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    setLineVisibility(gnssPolyline, gnssMarker, isChecked);
                });

        // PDR Switch
        pdrSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    setLineVisibility(pdrPolyline, pdrMarker, isChecked);
                });

        // Wi-Fi APS switch
        wifiAPSSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    if (!isChecked) removeAllWiFiAPS();
                });

        // Auto-Pan switch
        autopanSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> checkAutoPan(isChecked, currentLocation));

        floorUpButton.setOnClickListener(
                v -> {
                    // If user manually changes floor, turn off auto floor
                    autoFloorSwitch.setChecked(false);
                    if (indoorMapManager != null) {
                        Building building = indoorMapManager.getCurrentBuilding(currentLocation);
                        if (building != null) {
                            building.setCurrentFloor(building.getFloorNumber() + 1, gMap);
                        } else {
                            Log.w(TAG, "Floor Up Button: No building!");
                        }
                    }
                });

        floorDownButton.setOnClickListener(
                v -> {
                    autoFloorSwitch.setChecked(false);
                    if (indoorMapManager != null) {
                        Building building = indoorMapManager.getCurrentBuilding(currentLocation);
                        if (building != null) {
                            building.setCurrentFloor(building.getFloorNumber() - 1, gMap);
                        } else {
                            Log.w(TAG, "Floor Down Button: No building!");
                        }
                    }
                });
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Destroying " + getClass().getSimpleName());
        super.onDestroy();
    }

    /**
     * Draw the API response's estimated positions based on Wi-Fi fingerprinting
     *
     * @see com.openpositioning.PositionMe.fusion.Fusion Fusion
     */
    private void updateWiFiAPSDisplay() {
        if (wifiAPSSwitch.isChecked()) {

            Map<String, Object> wifiObservationsMap =
                    sensorFusion.fusion.getObservationsByType(OBSERVATION_TYPE_WIFI);
            List<LatLng> positions = (List<LatLng>) wifiObservationsMap.get("positions");
            List<String> floorNames = (List<String>) wifiObservationsMap.get("floor_names");

            for (int i = 0; i < positions.size(); i++) {
                LatLng position = positions.get(i);
                String floorName = floorNames.get(i);

                // Do not redraw circles which already exist
                boolean circleKnown = false;
                for (Circle circle : wifiAPSCircles) {
                    if (circle.getCenter().equals(position)) {
                        circleKnown = true;
                        circle.setVisible(floorName.equals(currentFloorName));
                        break;
                    }
                }
                if (circleKnown) continue;

                if (floorName.equals(currentFloorName)) {
                    Circle wifiAPSCircle =
                            gMap.addCircle(
                                    new CircleOptions()
                                            .center(position)
                                            .zIndex(4)
                                            .radius(WIFI_STD_DEV / 2)
                                            .strokeColor(MAP_WIFI_COLOUR)
                                            .fillColor(COLOUR_FLOOR_PLAN_FILL_TRANSPARENT));
                    wifiAPSCircles.add(wifiAPSCircle);
                }
            }

            // Remove old observations
            while (wifiAPSCircles.size() > positions.size()) {
                Circle oldCircle = wifiAPSCircles.get(0);
                if (oldCircle != null) {
                    oldCircle.remove();
                }
                wifiAPSCircles.remove(0);
            }
        }
    }

    private void removeAllWiFiAPS() {
        for (Circle circle : wifiAPSCircles) {
            if (circle != null) {
                circle.remove();
            }
        }
        wifiAPSCircles.clear();
    }

    /**
     * Helper function to show or hide a tracking method's line on the map
     *
     * @param polyline The line of the tracking method
     * @param marker The head of the tracking method's line
     * @param visibility True to show the line; false to hide the line
     */
    private void setLineVisibility(Polyline polyline, Marker marker, boolean visibility) {
        if (polyline != null) polyline.setVisible(visibility);
        if (marker != null) marker.setVisible(visibility);
    }

    /**
     * Helper function for auto-pan support
     *
     * @param status True for auto-pan enabled; false for disabled
     * @param position The position to zoom to
     */
    private void checkAutoPan(boolean status, LatLng position) {
        if (status && position != null) {
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(position, ZOOM_LEVEL_DEFAULT));
        }
    }

    /**
     * Initialize the map settings with the provided GoogleMap instance.
     *
     * <p>The method sets basic map settings, initializes the indoor map manager, and creates an
     * empty polyline for user movement tracking. The method also initializes the GNSS polyline for
     * tracking GNSS path. The method sets the map type to Hybrid and initializes the map with these
     * settings.
     *
     * @param map Google Map object
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
        pdrPolyline =
                map.addPolyline(
                        new PolylineOptions()
                                .color(COLOUR_PATH_COLOUR)
                                .width(LINE_WEIGHT_PATH)
                                .zIndex(MAP_DRAWING_PRIORITY_MAX)
                                .add() // start empty
                        );

        // GNSS path
        gnssPolyline =
                map.addPolyline(
                        new PolylineOptions()
                                .color(COLOUR_PATH_GNSS)
                                .width(LINE_WEIGHT_PATH)
                                .zIndex(MAP_DRAWING_PRIORITY_MAX)
                                .add() // start empty
                        );

        // Fused path
        fusionPolyline =
                map.addPolyline(
                        new PolylineOptions()
                                .color(COLOUR_PATH_FUSION)
                                .width(LINE_WEIGHT_PATH)
                                .zIndex(MAP_DRAWING_PRIORITY_MAX)
                                .add()); // start empty
    }

    /**
     * Initialize the map type spinner with the available map types.
     *
     * <p>The spinner allows the user to switch between different map types (e.g. Hybrid, Normal,
     * Satellite) to customize their map view. The spinner is populated with the available map types
     * and listens for user selection to update the map accordingly. The map type is updated
     * directly on the GoogleMap instance.
     *
     * <p>Note: The spinner is initialized with the default map type (Hybrid). The map type is
     * updated on user selection.
     *
     * @see com.google.android.gms.maps.GoogleMap The GoogleMap instance to update map type.
     */
    private void initMapTypeSpinner() {
        if (switchMapSpinner == null) return;
        String[] maps =
                new String[] {
                    getString(R.string.hybrid),
                    getString(R.string.normal),
                    getString(R.string.satellite)
                };
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(
                        requireContext(), android.R.layout.simple_spinner_dropdown_item, maps);
        switchMapSpinner.setAdapter(adapter);

        switchMapSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent, View view, int position, long id) {
                        if (gMap == null) return;
                        switch (position) {
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
     * Update the user's current location on the map, create or move orientation marker, and append
     * to polyline if the user actually moved.
     *
     * @param newLocation The new location to plot.
     * @param orientation The user’s heading (e.g. from sensor fusion).
     */
    public void updatePDRLocation(@NonNull LatLng newLocation, float orientation) {
        if (gMap == null) return;

        // Keep track of current location
        LatLng oldLocation = this.currentLocation;
        this.currentLocation = newLocation;

        // If no marker, create it
        if (pdrMarker == null) {
            pdrMarker =
                    gMap.addMarker(
                            new MarkerOptions()
                                    .position(newLocation)
                                    .flat(true)
                                    .title("Current Position")
                                    .icon(
                                            BitmapDescriptorFactory.fromBitmap(
                                                    UtilFunctions.getBitmapFromVector(
                                                            requireContext(),
                                                            R.drawable
                                                                    .ic_baseline_navigation_24))));
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, ZOOM_LEVEL_DEFAULT));
        } else {
            // Update marker position + orientation
            pdrMarker.setPosition(newLocation);
            pdrMarker.setRotation(orientation);
        }

        // Extend polyline if movement occurred
        if (oldLocation != null && !oldLocation.equals(newLocation) && pdrPolyline != null) {
            List<LatLng> points = new ArrayList<>(pdrPolyline.getPoints());
            points.add(newLocation);
            pdrPolyline.setPoints(points);
        }

        setLineVisibility(pdrPolyline, pdrMarker, pdrSwitch.isChecked());
    }

    /**
     * Called when we want to set or update the GNSS marker position
     *
     * @param gnssLocation The new GNSS location
     */
    public void updateGNSSLocation(@NonNull LatLng gnssLocation) {
        if (gMap == null) return;

        if (gnssMarker == null) {
            // Create the GNSS marker for the first time
            gnssMarker =
                    gMap.addMarker(
                            new MarkerOptions()
                                    .position(gnssLocation)
                                    .title("GNSS Position")
                                    .icon(
                                            BitmapDescriptorFactory.defaultMarker(
                                                    BitmapDescriptorFactory.HUE_AZURE)));
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

        setLineVisibility(gnssPolyline, gnssMarker, gnssSwitch.isChecked());
    }

    /**
     * Update the user's current location on the map based on the {@link
     * com.openpositioning.PositionMe.fusion.Fusion Fusion} algorithm
     *
     * @param fusedLocation The latest {@link com.openpositioning.PositionMe.fusion.Fusion Fusion}
     *     positioning estimate to plot
     * @param orientation The user’s heading in degrees
     */
    public void updateFusionLocation(@NonNull LatLng fusedLocation, float orientation) {
        if (gMap == null) return;
        if (!sensorFusion.fusion.isActive()) {
            wifiAPSSwitch.setChecked(false);
            wifiAPSSwitch.setEnabled(false);
            //            return;
        } else {
            wifiAPSSwitch.setEnabled(true);
        }

        // Initialisation
        if (fusionMarker == null) {
            fusionMarker =
                    gMap.addMarker(
                            new MarkerOptions()
                                    .position(fusedLocation)
                                    .title("Fused Position")
                                    .flat(true)
                                    .icon(
                                            BitmapDescriptorFactory.fromBitmap(
                                                    UtilFunctions.getBitmapFromVector(
                                                            requireContext(),
                                                            R.drawable
                                                                    .ic_baseline_navigation_25))));
            List<LatLng> points = new ArrayList<>(fusionPolyline.getPoints());
            points.add(fusedLocation);
            fusionPolyline.setPoints(points);
            lastFusedLocation = fusedLocation;
        } else {
            // Updating trace on screen
            fusionMarker.setPosition(fusedLocation);
            if (!Float.isNaN(orientation)) fusionMarker.setRotation(orientation);

            if (lastFusedLocation != null && !lastFusedLocation.equals(fusedLocation)) {
                List<LatLng> points = new ArrayList<>(fusionPolyline.getPoints());
                points.add(fusedLocation);
                try {
                    fusionPolyline.setPoints(points);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Invalid point; ignoring (" + e.getMessage() + ")");
                    points.remove(fusedLocation);
                    fusionPolyline.setPoints(points);
                }
            }
            lastFusedLocation = fusedLocation;

            // Auto-Pan will follow fusion path
            checkAutoPan(autopanSwitch.isChecked(), fusedLocation);

            // Update building to display correct floor
            indoorMapManager.drawBuildingPolygons();
            updateBuildingUI(fusedLocation);
            makeClickableBuildings();

            if (showParticlesSwitch.isChecked()) {
                drawParticles();
            } else {
                removeAllParticles();
            }
        }
    }

    /**
     * Display all particles currently being used for positioning
     *
     * @see com.openpositioning.PositionMe.fusion.Fusion Fusion
     * @see com.openpositioning.PositionMe.fusion.ParticleFilter ParticleFilter
     * @see Particle
     */
    private void drawParticles() {
        // Clear all old particles
        removeAllParticles();
        List<Particle> particles = sensorFusion.getFusionParticles();

        for (Particle particle : particles) {
            double weight = particle.getWeight();
            double[] en = particle.getEastingNorthing();
            LatLng particleLatLng = sensorFusion.convertENToLatLng(en);
            Circle particleCircle =
                    gMap.addCircle(
                            new CircleOptions()
                                    .center(particleLatLng)
                                    .radius(MAP_PARTICLE_WEIGHTING * weight)
                                    .strokeWidth(LINE_WEIGHT_PARTICLE)
                                    .fillColor(MAP_PARTICLE_COLOUR)
                                    .zIndex(50));
            particleCircles.add(particleCircle);
        }
    }

    /**
     * Update {@link IndoorMapManager indoor map} overlay to draw all possible {@link Building}
     * outlines, and set the current floor in AutoFloor mode
     *
     * <p>Should be called when using {@link com.openpositioning.PositionMe.fusion.Fusion Fusion}
     *
     * @param location Current location of user on map
     */
    private void updateBuildingUI(LatLng location) {
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(location);
            for (Building building : indoorMapManager.getAllBuildings()) {
                building.drawBuildingOutline(gMap);
                if (building.getIsInsideBuilding()) {
                    sensorFusion.setCurrentBuilding(building.getName());
                    sensorFusion.onBuildingAvailable(building);
                    setFloorUIVisibility(View.VISIBLE);
                    isInsideBuilding = true;
                    wifiAPSSwitch.setEnabled(true);

                    // AutoFloor elevation check
                    if (autoFloorSwitch.isChecked()) {
                        building.setCurrentFloor(sensorFusion.getEstimatedFloorNumber(), gMap);
                    }

                    currentFloorName = building.getFloorName();

                    if (wifiAPSSwitch.isChecked()) {
                        updateWiFiAPSDisplay();
                    }
                } else {
                    setFloorUIVisibility(View.GONE);
                    removeAllParticles();
                    removeAllWiFiAPS();
                    wifiAPSSwitch.setChecked(false);
                    wifiAPSSwitch.setEnabled(false);
                    isInsideBuilding = false;
                }
            }
        }
    }

    /**
     * Make the {@link Building} {@link Polygon Polygons} clickable on the map to display floorplan
     * previews
     */
    private void makeClickableBuildings() {
        if (indoorMapManager != null) {
            for (Building building : indoorMapManager.getAllBuildings()) {
                // Preview floor plan by clicking on building, and associate route
                gMap.setOnPolygonClickListener(
                        polygon -> {
                            sensorFusion.setCurrentBuilding(building.getName());

                            // Only show a preview of the building if not inside
                            if (building.getIsInsideBuilding()) {
                                return;
                            }
                            // Only show preview if floor plans are available
                            if (building.getFloorNames().isEmpty()) {
                                Log.w(
                                        TAG,
                                        "Cannot show preview of "
                                                + building.getName()
                                                + " as there are no floor plans.");
                                return;
                            }
                            polygon = building.getBuildingOutline();
                            int currentColour = polygon.getFillColor();
                            switch (currentColour) {
                                case COLOUR_FLOOR_PLAN_FILL_TRANSPARENT ->
                                        polygon.setFillColor(COLOUR_FLOOR_PLAN_FILL_PREVIEW);
                                case COLOUR_FLOOR_PLAN_FILL_PREVIEW ->
                                        polygon.setFillColor(COLOUR_FLOOR_PLAN_FILL_TRANSPARENT);
                            }
                            // Display floor plan of ground floor
                            int groundFloorIndex = building.getGroundFloorIndex();
                            List<Polyline> elements =
                                    building.getFloorPlanElements(
                                            building.getFloorNames().get(groundFloorIndex));
                            if (elements == null) {
                                building.editFloorPlan(gMap, groundFloorIndex, true);
                            } else {
                                for (Polyline element : elements) {
                                    element.setVisible(!element.isVisible());
                                }
                            }
                            // Flag to not overwrite preview
                            building.setIsPreviewingFloorPlan(!building.getIsPreviewingFloorPlan());
                        });
            }
        }
    }

    /**
     * Set the initial camera position for the map.
     *
     * <p>The method sets the initial camera position for the map when it is first loaded. If the
     * map is already ready, the camera is moved immediately. If the map is not ready, the camera
     * position is stored until the map is ready. The method also tracks if there is a pending
     * camera move.
     *
     * @param startLocation The initial camera position to set.
     */
    public void setInitialCameraPosition(@NonNull LatLng startLocation) {
        // If the map is already ready, move camera immediately
        if (gMap != null) {
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLocation, ZOOM_LEVEL_DEFAULT));
        } else {
            // Otherwise, store it until onMapReady
            pendingCameraPosition = startLocation;
            hasPendingCameraMove = true;
        }
    }

    /**
     * Adds a timed marker to the map
     *
     * @param pos Position of the marker
     * @param timeLabel Label associated with the marker
     * @param number The marker's number
     */
    public void addTimeMarker(LatLng pos, String timeLabel, int number) {
        if (gMap == null || pos == null) return;

        Marker marker =
                gMap.addMarker(
                        new MarkerOptions()
                                .position(pos)
                                .title("Test Point #" + number)
                                .snippet("Time: " + timeLabel)
                                .icon(
                                        BitmapDescriptorFactory.defaultMarker(
                                                BitmapDescriptorFactory.HUE_GREEN)));
        timedMarkers.add(marker);
    }

    /** Clears timedMarkers from maps */
    public void removeAllTimedMarkers() {
        for (Marker marker : timedMarkers) {
            if (marker != null) {
                marker.remove();
            }
        }
        timedMarkers.clear();
    }

    /** Clear all particles from the map */
    public void removeAllParticles() {
        for (Circle particleCircle : particleCircles) {
            if (particleCircle != null) {
                particleCircle.remove();
            }
        }
        particleCircles.clear();
    }

    /**
     * Get the current user location on the map.
     *
     * @return The current user location as a LatLng object.
     */
    public LatLng getCurrentLocation() {
        return currentLocation;
    }

    /** Remove GNSS marker if user toggles it off */
    public void clearGNSS() {
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
    }

    /** Whether user is currently showing GNSS or not */
    public boolean isGnssEnabled() {
        return gnssSwitch.isChecked();
    }

    public boolean getIsInsideBuilding() {
        return isInsideBuilding;
    }

    public String getFloorName() {
        return currentFloorName;
    }

    private void setFloorUIVisibility(int visibility) {
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);

        // When entering building for first time, default to auto floor enabled
        if (autoFloorSwitch.getVisibility() == View.GONE) {
            autoFloorSwitch.setChecked(true);
        }
        autoFloorSwitch.setVisibility(visibility);

        // Disable particle display switch when hiding
        if (visibility == View.GONE && showParticlesSwitch.isChecked()) {
            showParticlesSwitch.setChecked(false);
        }
        showParticlesSwitch.setVisibility(visibility);
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
        if (fusionPolyline != null) {
            fusionPolyline.remove();
            fusionPolyline = null;
        }
        if (pdrMarker != null) {
            pdrMarker.remove();
            pdrMarker = null;
        }
        if (fusionMarker != null) {
            fusionMarker.remove();
            fusionMarker = null;
        }
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }

        removeAllParticles();
        removeAllWiFiAPS();

        lastFusedLocation = null;
        lastGnssLocation = null;
        currentLocation = null;

        // Re-create empty polylines with your chosen colors
        if (gMap != null) {
            pdrPolyline =
                    gMap.addPolyline(
                            new PolylineOptions()
                                    .color(COLOUR_PATH_COLOUR)
                                    .width(LINE_WEIGHT_PATH)
                                    .zIndex(MAP_DRAWING_PRIORITY_MAX)
                                    .add());
            gnssPolyline =
                    gMap.addPolyline(
                            new PolylineOptions()
                                    .color(COLOUR_PATH_GNSS)
                                    .width(LINE_WEIGHT_PATH)
                                    .zIndex(MAP_DRAWING_PRIORITY_MAX)
                                    .add());
            fusionPolyline =
                    gMap.addPolyline(
                            new PolylineOptions()
                                    .color(COLOUR_PATH_FUSION)
                                    .width(LINE_WEIGHT_PATH)
                                    .zIndex(MAP_DRAWING_PRIORITY_MAX)
                                    .add());
        }
    }
}
