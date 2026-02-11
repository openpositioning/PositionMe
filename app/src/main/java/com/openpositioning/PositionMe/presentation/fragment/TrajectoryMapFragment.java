package com.openpositioning.PositionMe.presentation.fragment;

import static com.openpositioning.PositionMe.utils.UtilConstants.BUILDING_NAME_NUCLEUS;
import static com.openpositioning.PositionMe.utils.UtilConstants.COLOUR_BUILDING_WITHOUT_FLOOR_MAPS;
import static com.openpositioning.PositionMe.utils.UtilConstants.COLOUR_FLOOR_PLAN_FILL_PREVIEW;
import static com.openpositioning.PositionMe.utils.UtilConstants.COLOUR_FLOOR_PLAN_FILL_TRANSPARENT;
import static com.openpositioning.PositionMe.utils.UtilConstants.COLOUR_PATH_COLOUR;
import static com.openpositioning.PositionMe.utils.UtilConstants.COLOUR_PATH_GNSS;
import static com.openpositioning.PositionMe.utils.UtilConstants.COLOUR_PATH_MONOCHROME;
import static com.openpositioning.PositionMe.utils.UtilConstants.LINE_WEIGHT_OUTLINE;
import static com.openpositioning.PositionMe.utils.UtilConstants.LINE_WEIGHT_PATH;
import static com.openpositioning.PositionMe.utils.UtilConstants.MAP_DRAWING_PRIORITY_MAX;
import static com.openpositioning.PositionMe.utils.UtilConstants.ZOOM_LEVEL_DEFAULT;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.*;
import com.google.android.material.switchmaterial.SwitchMaterial;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.Building;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import org.geojson.MultiPolygon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private boolean isColourEnabled = true; // Tracks whether the polyline color is red
    private boolean isGnssOn = false; // Tracks if GNSS tracking is enabled

    private Polyline gnssPolyline; // Polyline for GNSS path
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
    private List<Marker> markers = new ArrayList<>();

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
        switchMapSpinner  = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch        = view.findViewById(R.id.gnssSwitch);
        autoFloorSwitch   = view.findViewById(R.id.autoFloor);
        floorUpButton     = view.findViewById(R.id.floorUpButton);
        floorDownButton   = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);

        sensorFusion = SensorFusion.getInstance();

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
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                            pendingCameraPosition,
                            ZOOM_LEVEL_DEFAULT)
                        );
                        hasPendingCameraMove = false;
                        pendingCameraPosition = null;
                    }

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
                if (isColourEnabled) {
                    switchColorButton.setBackgroundColor(COLOUR_PATH_MONOCHROME);
                    polyline.setColor(COLOUR_PATH_MONOCHROME);
                    isColourEnabled = false;
                } else {
                    switchColorButton.setBackgroundColor(COLOUR_PATH_COLOUR);
                    polyline.setColor(COLOUR_PATH_COLOUR);
                    isColourEnabled = true;
                }
            }
        });

        floorUpButton.setOnClickListener(v -> {
            // If user manually changes floor, turn off auto floor
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                Building building = indoorMapManager.getCurrentBuilding(currentLocation);
                if (building != null) {
                    building.setCurrentFloor(
                    building.getFloorNumber()+1,
                        gMap
                    );
                } else {
                    Log.w("TrajectoryMapFragment", "Floor Up Button: No building!");
                }
            }
        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                Building building = indoorMapManager.getCurrentBuilding(currentLocation);
                if (building != null) {
                    building.setCurrentFloor(
                    building.getFloorNumber()-1,
                        gMap
                    );
                } else {
                    Log.w("TrajectoryMapFragment", "Floor Down Button: No building!");
                }
            }
        });
    }

    /**
     * Initialize the map settings with the provided GoogleMap instance.
     * <p>
     *     The method sets basic map settings, initializes the indoor map manager,
     *     and creates an empty polyline for user movement tracking.
     *     The method also initializes the GNSS polyline for tracking GNSS path.
     *     The method sets the map type to Hybrid and initializes the map with these settings.
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
        polyline = map.addPolyline(new PolylineOptions()
            .color(COLOUR_PATH_COLOUR)
            .width(LINE_WEIGHT_PATH)
            .zIndex(MAP_DRAWING_PRIORITY_MAX)
            .add() // start empty
        );

        // GNSS path
        gnssPolyline = map.addPolyline(new PolylineOptions()
            .color(COLOUR_PATH_GNSS)
            .width(LINE_WEIGHT_PATH)
            .zIndex(MAP_DRAWING_PRIORITY_MAX)
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
     * Update the user's current location on the map,
     * create or move orientation marker, and
     * append to polyline if the user actually moved.
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
                    UtilFunctions.getBitmapFromVector(
                        requireContext(),
                        R.drawable.ic_baseline_navigation_24)
                    )
                )
            );
            // Refocus the camera to current position
            // (Currently runs once at start of recording)
            // TODO - Implement UI toggle for this feature
            if (oldLocation == null){
                gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                    newLocation,
                    ZOOM_LEVEL_DEFAULT)
                );
            }
        } else {
            // Update marker position + orientation
            orientationMarker.setPosition(newLocation);
            orientationMarker.setRotation(orientation);
            // Move camera a bit
//            gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
        }

        // Extend polyline if movement occurred
        if (oldLocation != null && !oldLocation.equals(newLocation) && polyline != null) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            points.add(newLocation);
            polyline.setPoints(points);
        }

        // Update indoor map overlay to draw all possible building outlines
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(newLocation);
            for (Building building : indoorMapManager.getAllBuildings()){
                building.drawBuildingOutline(gMap);
                if (building.getIsInsideBuilding()){
                    setFloorControlsVisibility(View.VISIBLE);
                } else {
                    setFloorControlsVisibility(View.GONE);
                }
                // Preview floor plan by clicking on building, and associate route
                gMap.setOnPolygonClickListener(new GoogleMap.OnPolygonClickListener() {
                    @Override
                    public void onPolygonClick(@NonNull Polygon polygon) {
                        sensorFusion.setCurrentBuilding(building.getName());

                        // Only show a preview of the building if not inside
                        if (building.getIsInsideBuilding()){
                            return;
                        }
                        // Only show preview if floor plans are available
                        if (building.getFloorNames().isEmpty()){
                            Log.w(
                            "TrajectoryMapFragment",
                            "Cannot show preview of "
                                + building.getName()
                                + " as there are no floor plans."
                            );
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
                        List<Polyline> elements = building.getFloorPlanElements(
                            building.getFloorNames().get(groundFloorIndex)
                        );
                        if (elements == null) {
                            building.editFloorPlan(gMap, groundFloorIndex, true);
                        } else {
                            for (Polyline element : elements) {
                                element.setVisible(!element.isVisible());
                            }
                        }
                        // Flag to not overwrite preview
                        building.setIsPreviewingFloorPlan(!building.getIsPreviowingFloorPlan());
                    }
                });
            }
        }

        // AutoFloor elevation check
        if (autoFloorSwitch.isChecked()){
            Building building = indoorMapManager.getCurrentBuilding(currentLocation);
            if (building != null){
                float elevationVal = sensorFusion.getElevation();
                int newFloor = (int)(elevationVal/building.getFloorHeight());
                building.setCurrentFloor(newFloor, gMap);
            }
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
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLocation, ZOOM_LEVEL_DEFAULT));
        } else {
            // Otherwise, store it until onMapReady
            pendingCameraPosition = startLocation;
            hasPendingCameraMove = true;
        }
    }

    /**
     * Adds a Timed marker to the map
     */
    public void addTimeMarker(LatLng pos, String timeLabel, int number) {
        if (gMap == null || pos == null) return;

        Marker marker = gMap.addMarker(new MarkerOptions()
            .position(pos)
            .title("Test Point #" + number)
            .snippet("Time: " + timeLabel)
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        );
        markers.add(marker);
    }

    /**
     * Clears Markers From Maps
     */
    public void removeAllMarkers() {
        for (Marker marker : markers) {
            if (marker != null) {
                marker.remove();
            }
        }
        markers.clear();
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
                .icon(BitmapDescriptorFactory.defaultMarker(
                    BitmapDescriptorFactory.HUE_AZURE)
                )
            );
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

    private void setFloorControlsVisibility(int visibility) {
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);

        // When entering building for first time, default to auto floor enabled
        if (autoFloorSwitch.getVisibility() == View.GONE){
            autoFloorSwitch.setChecked(true);
        }
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

        // Re-create empty polylines with your chosen colors
        if (gMap != null) {
            polyline = gMap.addPolyline(new PolylineOptions()
                .color(COLOUR_PATH_COLOUR)
                .width(LINE_WEIGHT_PATH)
                .zIndex(MAP_DRAWING_PRIORITY_MAX)
                .add()
            );
            gnssPolyline = gMap.addPolyline(new PolylineOptions()
                .color(COLOUR_PATH_GNSS)
                .width(LINE_WEIGHT_PATH)
                .zIndex(MAP_DRAWING_PRIORITY_MAX)
                .add()
            );
        }
    }

    /**
     * Add building from the server to reference list, and
     * set building as campaign source for recording
     *
     * @param name The name of the building
     * @param outline Points representing outline of building
     * @param floorPlans MultiPolygons (or MultiLineString)
     *                  of overlays for floor plans
     * */
    public void addBuilding(
        String name,
        List<LatLng> outline,
        Map<String, List<Object>> floorPlans
    ) {
        if (!indoorMapManager.getAllBuildingNames().contains(name)) {
            indoorMapManager.addBuilding(new Building(name, outline, floorPlans));
        } else {
            Log.w(
            "TrajectoryMapFragment",
            "Building " + name + " already exists. Skipping creation."
            );
        }
        sensorFusion.setCurrentBuilding(name);
    }
}
