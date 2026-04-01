package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import com.google.android.material.switchmaterial.SwitchMaterial;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.OnMapReadyCallback;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.MapMatchingConfig;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.CrossFloorClassifier;
import com.openpositioning.PositionMe.utils.WallGeometryBuilder;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import android.graphics.PointF;
import java.util.List;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.*;

import java.util.ArrayList;
import java.util.List;

import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;


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
    // Keep test point markers so they can be cleared when recording ends
    private final List<Marker> testPointMarkers = new ArrayList<>();

    private Polyline polyline; // Polyline representing user's movement path
    private boolean isRed = true; // Tracks whether the polyline color is red
    private boolean isGnssOn = false; // Tracks if GNSS tracking is enabled

    private Polyline gnssPolyline; // Polyline for GNSS path
    private LatLng lastGnssLocation = null; // Stores the last GNSS location

    private LatLng pendingCameraPosition = null; // Stores pending camera movement
    private boolean hasPendingCameraMove = false; // Tracks if camera needs to move

    private IndoorMapManager indoorMapManager; // Manages indoor mapping
    private SensorFusion sensorFusion;

    // Auto-floor state
    private static final String TAG = "TrajectoryMapFragment";
    private static final long AUTO_FLOOR_DEBOUNCE_MS = 3000;
    private static final long AUTO_FLOOR_CHECK_INTERVAL_MS = 1000;
    private Handler autoFloorHandler;
    private Runnable autoFloorTask;
    private int lastCandidateFloor = Integer.MIN_VALUE;
    private long lastCandidateTime = 0;

    private final MapMatchingConfig mapMatchingConfig = new MapMatchingConfig();
    private LatLng wallOrigin;

    // UI
    private Spinner switchMapSpinner;

    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;

    private SwitchMaterial smoothingSwitch;

    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private TextView floorLabel;
    private Button switchColorButton;
    private Polygon buildingPolygon;

    // --- Last N observation display state ---
    private static final int MAX_OBSERVATIONS = 20;

    // Rolling histories of absolute position updates
    private final List<LatLng> gnssHistory = new ArrayList<>();
    private final List<LatLng> wifiHistory = new ArrayList<>();
    private final List<LatLng> pdrHistory = new ArrayList<>();

    // Rendered map circles for each source, so they can be removed/redrawn cleanly
    private final List<Circle> gnssCircles = new ArrayList<>();
    private final List<Circle> wifiCircles = new ArrayList<>();
    private final List<Circle> pdrCircles = new ArrayList<>();

    // Optional UI switches for visibility control
    private SwitchMaterial wifiSwitch;
    private SwitchMaterial pdrSwitch;

    // --- Display smoothing state ---

    // Types of smoothing filters available for display
    private enum SmoothingType {
        RAW,               // No smoothing
        MOVING_AVERAGE,    // Average over last N points
        EXPONENTIAL        // Exponential smoothing
    }
    private Spinner smoothingSpinner;

    // Current selected smoothing mode (default = RAW)
    private SmoothingType smoothingType = SmoothingType.RAW;

    // For exponential smoothing
    private LatLng smoothedDisplayLocation = null;

    // For moving average
    private static final int SMOOTHING_WINDOW = 5;
    private final List<LatLng> smoothingBuffer = new ArrayList<>();

    // Exponential smoothing strength
    private static final double ALPHA = 0.25;

    /**
     * Adds a new position observation to a rolling history list.
     * Maintains only the most recent MAX_OBSERVATIONS points.
     *
     * @param history The list storing past observations (GNSS, WiFi, or PDR)
     * @param point   The new LatLng position to add
     */
    private void addObservation(List<LatLng> history, LatLng point) {

        // Ignore null points (e.g., when a sensor has no valid reading)
        if (point == null) return;

        // Add the new observation to the history
        history.add(point);

        // If we exceed the maximum allowed observations,
        // remove the oldest point (FIFO behaviour)
        if (history.size() > MAX_OBSERVATIONS) {
            history.remove(0);
        }
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

        // Grab references to UI controls
        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch      = view.findViewById(R.id.gnssSwitch);
        wifiSwitch = view.findViewById(R.id.wifiSwitch);
        pdrSwitch = view.findViewById(R.id.pdrSwitch);
        smoothingSpinner = view.findViewById(R.id.smoothingSpinner);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        floorLabel      = view.findViewById(R.id.floorLabel);
        switchColorButton = view.findViewById(R.id.lineColorButton);

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

                    // Draw green outlines around buildings that have indoor map data
                    if (indoorMapManager != null) {
                        indoorMapManager.setIndicationOfIndoorMap();
                    }

                    Log.d("TrajectoryMapFragment", "onMapReady: Map is ready!");


                }
            });
        }
        // Smoothing type spinner setup
        initSmoothingSpinner();
        // Map type spinner setup
        initMapTypeSpinner();

        // GNSS Switch
        gnssSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isGnssOn = isChecked;
            if (!isChecked && gnssMarker != null) {
                gnssMarker.remove();
                gnssMarker = null;
            }
            redrawObservationOverlays();
        });

        wifiSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            redrawObservationOverlays();
        });

        pdrSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            redrawObservationOverlays();
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

        // Auto-floor toggle: start/stop periodic floor evaluation
        sensorFusion = SensorFusion.getInstance();
        autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (isChecked) {
                startAutoFloor();
            } else {
                stopAutoFloor();
            }
        });

        floorUpButton.setOnClickListener(v -> {
            // If user manually changes floor, turn off auto floor
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
                updateFloorLabel();
            }
        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
                updateFloorLabel();
            }
        });
    }

    /**
     * Redraws the colour-coded observation circles for GNSS, WiFi, and PDR.
     * Only sources enabled by their switches are displayed.
     */
    private void redrawObservationOverlays() {
        if (gMap == null) return;

        clearObservationCircles();

        if (gnssSwitch != null && gnssSwitch.isChecked()) {
            drawHistory(gnssHistory, gnssCircles, Color.BLUE);
        }

        if (wifiSwitch != null && wifiSwitch.isChecked()) {
            drawHistory(wifiHistory, wifiCircles, Color.GREEN);
        }

        if (pdrSwitch != null && pdrSwitch.isChecked()) {
            drawHistory(pdrHistory, pdrCircles, Color.RED);
        }
    }

    /**
     * Draws one rolling history of observations on the map as circles.
     * Older observations are faded, newer ones are more visible.
     *
     * @param history  The observation points to render
     * @param rendered The list of Circle references currently on the map
     * @param color    The base colour for this data source
     */
    private void drawHistory(List<LatLng> history, List<Circle> rendered, int color) {
        for (int i = 0; i < history.size(); i++) {
            LatLng point = history.get(i);

            int alpha = (int) (255f * (i + 1) / history.size());
            int fadedColor = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));

            Circle circle = gMap.addCircle(new CircleOptions()
                    .center(point)
                    .radius(1.5)
                    .strokeWidth(2f)
                    .strokeColor(fadedColor)
                    .fillColor(fadedColor));

            rendered.add(circle);
        }
    }

    /**
     * Removes all currently displayed observation circles from the map.
     */
    private void clearObservationCircles() {
        removeAll(gnssCircles);
        removeAll(wifiCircles);
        removeAll(pdrCircles);
    }

    /**
     * Removes every circle in the provided list from the map and clears the list.
     *
     * @param circles The rendered circles to remove
     */
    private void removeAll(List<Circle> circles) {
        for (Circle circle : circles) {
            if (circle != null) {
                circle.remove();
            }
        }
        circles.clear();
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
     * Initializes the smoothing filter spinner.
     * Allows user to select how trajectory is smoothed.
     */
    private void initSmoothingSpinner() {

        String[] options = new String[]{
                "Raw",
                "Moving Average",
                "Exponential"
        };

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                options
        );

        smoothingSpinner.setAdapter(adapter);

        smoothingSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {

                // Update selected smoothing mode
                switch (position) {
                    case 0:
                        smoothingType = SmoothingType.RAW;
                        break;
                    case 1:
                        smoothingType = SmoothingType.MOVING_AVERAGE;
                        break;
                    case 2:
                        smoothingType = SmoothingType.EXPONENTIAL;
                        break;
                }

                // Reset smoothing state when switching modes
                smoothedDisplayLocation = null;
                smoothingBuffer.clear();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }


    /**
     * Returns the display location after applying the selected smoothing filter.
     */
    private LatLng applySmoothing(@NonNull LatLng newLocation) {

        switch (smoothingType) {

            case RAW:
                return newLocation;

            case MOVING_AVERAGE:
                smoothingBuffer.add(newLocation);

                if (smoothingBuffer.size() > SMOOTHING_WINDOW) {
                    smoothingBuffer.remove(0);
                }

                double sumLat = 0;
                double sumLng = 0;

                for (LatLng p : smoothingBuffer) {
                    sumLat += p.latitude;
                    sumLng += p.longitude;
                }

                return new LatLng(
                        sumLat / smoothingBuffer.size(),
                        sumLng / smoothingBuffer.size()
                );

            case EXPONENTIAL:

                if (smoothedDisplayLocation == null) {
                    smoothedDisplayLocation = newLocation;
                    return newLocation;
                }

                double lat = ALPHA * newLocation.latitude +
                        (1 - ALPHA) * smoothedDisplayLocation.latitude;

                double lng = ALPHA * newLocation.longitude +
                        (1 - ALPHA) * smoothedDisplayLocation.longitude;

                smoothedDisplayLocation = new LatLng(lat, lng);
                return smoothedDisplayLocation;
        }

        return newLocation;
    }

    /**
     * Adds a GNSS observation to the rolling history and redraws the overlays.
     *
     * @param point New GNSS position
     */
    public void addGnssObservation(@NonNull LatLng point) {
        addObservation(gnssHistory, point);
        redrawObservationOverlays();
    }

    /**
     * Adds a WiFi observation to the rolling history and redraws the overlays.
     *
     * @param point New WiFi-derived position
     */
    public void addWifiObservation(@NonNull LatLng point) {
        addObservation(wifiHistory, point);
        redrawObservationOverlays();
    }

    /**
     * Adds a PDR observation to the rolling history and redraws the overlays.
     *
     * @param point New PDR-derived position
     */
    public void addPdrObservation(@NonNull LatLng point) {
        addObservation(pdrHistory, point);
        redrawObservationOverlays();
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

        // Apply selected smoothing filter before rendering
        LatLng displayLocation = applySmoothing(newLocation);

        addObservation(pdrHistory, displayLocation);
        redrawObservationOverlays();

        // Keep track of current location using the displayed point
        LatLng oldLocation = this.currentLocation;
        this.currentLocation = displayLocation;

        // If no marker, create it
        if (orientationMarker == null) {
            orientationMarker = gMap.addMarker(new MarkerOptions()
                    .position(displayLocation)
                    .flat(true)
                    .title("Current Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_24)))
            );
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(displayLocation, 19f));
        } else {
            // Update marker position + orientation
            orientationMarker.setPosition(displayLocation);
            orientationMarker.setRotation(orientation);
            // Move camera a bit
            gMap.moveCamera(CameraUpdateFactory.newLatLng(displayLocation));
        }

        // Extend polyline if movement occurred
        /*if (oldLocation != null && !oldLocation.equals(newLocation) && polyline != null) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            points.add(newLocation);
            polyline.setPoints(points);
        }*/
        // Extend polyline
        if (polyline != null) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());

            // First position fix: add the first polyline point
            if (oldLocation == null) {
                points.add(displayLocation);
                polyline.setPoints(points);
            } else if (!oldLocation.equals(displayLocation)) {
                // Subsequent movement: append a new polyline point
                points.add(displayLocation);
                polyline.setPoints(points);
            }
        }

        // Update indoor map overlay
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(displayLocation);
            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
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
     * Add a numbered test point marker on the map.
     * Called by RecordingFragment when user presses the "Test Point" button.
     */
    public void addTestPointMarker(int index, long timestampMs, @NonNull LatLng position) {
        if (gMap == null) return;

        Marker m = gMap.addMarker(new MarkerOptions()
                .position(position)
                .title("TP " + index)
                .snippet("t=" + timestampMs));

        if (m != null) {
            m.showInfoWindow(); // Show TP index immediately
            testPointMarkers.add(m);
        }
    }


    /**
     * Called when we want to set or update the GNSS marker position
     */
    public void updateGNSS(@NonNull LatLng gnssLocation) {
        if (gMap == null) return;
        addObservation(gnssHistory, gnssLocation);
        redrawObservationOverlays();
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
     * Updates the WiFi history with a new location and refreshes the map display.
     * @param wifiLocation The new coordinates to add to the observation history.
     */
    public void updateWiFiObservation(@NonNull LatLng wifiLocation) {
        addObservation(wifiHistory, wifiLocation);
        redrawObservationOverlays();
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
        floorLabel.setVisibility(visibility);
        autoFloorSwitch.setVisibility(visibility);
        if (visibility == View.VISIBLE) {
            updateFloorLabel();
        }
    }

    /**
     * Updates the floor label text to reflect the current floor display name.
     */
    private void updateFloorLabel() {
        if (floorLabel != null && indoorMapManager != null) {
            floorLabel.setText(indoorMapManager.getCurrentFloorDisplayName());
        }
    }

    public void clearMapAndReset() {
        stopAutoFloor();
        if (autoFloorSwitch != null) {
            autoFloorSwitch.setChecked(false);
        }
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

        // Clear test point markers
        for (Marker m : testPointMarkers) {
            m.remove();
        }
        testPointMarkers.clear();

        // remove coloured observation circles from map
        gnssHistory.clear();
        wifiHistory.clear();
        pdrHistory.clear();
        clearObservationCircles();

        smoothedDisplayLocation = null;

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
        Log.d(TAG, "Building polygon added, vertex count: " + buildingPolygon.getPoints().size());
    }

    //region Auto-floor logic
    // Uses WiFi floor when available; otherwise barometric elevation divided by floor height (meters).
    // Behavior unchanged; proximity to stairs/lift will be added in later steps.

    /**
     * Starts the periodic auto-floor evaluation task. Checks every second
     * and applies floor changes only after the debounce window (3 seconds
     * of consistent readings).
     */
    private void startAutoFloor() {
        if (autoFloorHandler == null) {
            autoFloorHandler = new Handler(Looper.getMainLooper());
        }
        lastCandidateFloor = Integer.MIN_VALUE;
        lastCandidateTime = 0;

        // Immediately jump to the best-guess floor (skip debounce on first toggle)
        applyImmediateFloor();

        autoFloorTask = new Runnable() {
            @Override
            public void run() {
                evaluateAutoFloor();
                autoFloorHandler.postDelayed(this, AUTO_FLOOR_CHECK_INTERVAL_MS);
            }
        };
        autoFloorHandler.post(autoFloorTask);
        Log.d(TAG, "Auto-floor started");
    }

    /**
     * Applies the best-guess floor immediately without debounce.
     * Called once when auto-floor is first toggled on, so the user
     * sees an instant correction after manually browsing wrong floors.
     */
    private void applyImmediateFloor() {
        if (sensorFusion == null || indoorMapManager == null) return;
        if (!indoorMapManager.getIsIndoorMapSet()) return;

        updateWallsForPdr();

        int candidateFloor;
        if (sensorFusion.getLatLngWifiPositioning() != null) {
            candidateFloor = sensorFusion.getWifiFloor();
        } else {
            float elevation = sensorFusion.getElevation();
            float floorHeight = indoorMapManager.getFloorHeight();
            if (floorHeight <= 0) {
                // Fallback to config default if building metadata is missing
                floorHeight = mapMatchingConfig.baroHeightThreshold;
            }
            if (Math.abs(elevation) < mapMatchingConfig.baroHeightThreshold) {
                return; // Ignore small height changes
            }
            if (floorHeight <= 0) return;
            candidateFloor = Math.round(elevation / floorHeight);

            // Require proximity to stairs/lift when using barometer path
            boolean nearFeature = indoorMapManager.isNearCrossFloorFeature(mapMatchingConfig.crossFeatureProximity);
            if (!nearFeature) {
                return;
            }

            CrossFloorClassifier.Mode mode =
                    CrossFloorClassifier.classify(0.0, elevation, 0.0, mapMatchingConfig);
            Log.d(TAG, "Auto-floor (baro) mode=" + mode + " elevation=" + elevation
                    + " floorHeight=" + floorHeight);
        }

        indoorMapManager.setCurrentFloor(candidateFloor, true);
        updateFloorLabel();
        // Seed the debounce state so subsequent checks don't re-trigger immediately
        lastCandidateFloor = candidateFloor;
        lastCandidateTime = SystemClock.elapsedRealtime();
    }

    /**
     * Stops the periodic auto-floor evaluation and resets debounce state.
     */
    private void stopAutoFloor() {
        if (autoFloorHandler != null && autoFloorTask != null) {
            autoFloorHandler.removeCallbacks(autoFloorTask);
        }
        lastCandidateFloor = Integer.MIN_VALUE;
        lastCandidateTime = 0;
        Log.d(TAG, "Auto-floor stopped");
    }

    /**
     * Evaluates the current floor using WiFi positioning (priority) or
     * barometric elevation (fallback). Applies a 3-second debounce window
     * to prevent jittery floor switching.
     */
    private void evaluateAutoFloor() {
        if (sensorFusion == null || indoorMapManager == null) return;
        if (!indoorMapManager.getIsIndoorMapSet()) return;

        updateWallsForPdr();

        int candidateFloor;

        // Priority 1: WiFi-based floor (only if WiFi positioning has returned data)
        if (sensorFusion.getLatLngWifiPositioning() != null) {
            candidateFloor = sensorFusion.getWifiFloor();
        } else {
            // Fallback: barometric elevation estimate
            float elevation = sensorFusion.getElevation();
            float floorHeight = indoorMapManager.getFloorHeight();
            if (floorHeight <= 0) {
                // Fallback to config default if building metadata is missing
                floorHeight = mapMatchingConfig.baroHeightThreshold;
            }
            if (Math.abs(elevation) < mapMatchingConfig.baroHeightThreshold) {
                return; // Ignore small height changes
            }
            boolean nearFeature = indoorMapManager.isNearCrossFloorFeature(mapMatchingConfig.crossFeatureProximity);
            if (!nearFeature) {
                return;
            }
            if (floorHeight <= 0) return;
            candidateFloor = Math.round(elevation / floorHeight);

            CrossFloorClassifier.Mode mode =
                    CrossFloorClassifier.classify(0.0, elevation, 0.0, mapMatchingConfig);
            Log.d(TAG, "Auto-floor (baro) mode=" + mode + " elevation=" + elevation
                    + " floorHeight=" + floorHeight);
        }

        // Debounce: require the same floor reading for AUTO_FLOOR_DEBOUNCE_MS
        long now = SystemClock.elapsedRealtime();
        if (candidateFloor != lastCandidateFloor) {
            lastCandidateFloor = candidateFloor;
            lastCandidateTime = now;
            return;
        }

        if (now - lastCandidateTime >= AUTO_FLOOR_DEBOUNCE_MS) {
            indoorMapManager.setCurrentFloor(candidateFloor, true);
            updateFloorLabel();
            // Reset timer so we don't keep re-applying the same floor
            lastCandidateTime = now;
        }
    }

    //endregion

    private void updateWallsForPdr() {
        if (sensorFusion == null || indoorMapManager == null) return;
        if (!indoorMapManager.getIsIndoorMapSet()) return;
        LatLng current = indoorMapManager.getLastLocation();
        if (current == null) return;

        if (wallOrigin == null) {
            wallOrigin = current;
        }

        FloorplanApiClient.FloorShapes floor = indoorMapManager.getCurrentFloorShape();
        if (floor == null) return;

        List<List<PointF>> walls = WallGeometryBuilder.buildWalls(
                floor, wallOrigin);
        sensorFusion.setPdrWalls(walls);
    }
}
