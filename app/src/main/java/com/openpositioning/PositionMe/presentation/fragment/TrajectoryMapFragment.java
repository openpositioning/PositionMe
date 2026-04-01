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
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.*;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;

import java.util.ArrayList;
import java.util.HashMap;
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
    private Marker wifiMarker; // WiFi position marker
    // Keep test point markers so they can be cleared when recording ends
    private final List<Marker> testPointMarkers = new ArrayList<>();

    // Per-floor polyline storage: only the current floor's segments are visible
    private final Map<Integer, List<Polyline>> floorPolylines = new HashMap<>();
    private Polyline activePolyline; // Current segment on the active floor
    private int polylineFloor = -1; // Floor index of the active polyline segment

    private boolean isRed = true; // Tracks whether the polyline color is red
    private boolean isGnssOn = false; // Tracks if GNSS tracking is enabled
    private boolean isWifiOn = false; // Tracks if WiFi tracking is enabled
    private boolean isSmoothOn = true; // Tracks if smooth filter (fusion) is enabled

    private Polyline gnssPolyline; // Polyline for GNSS path
    private Polyline wifiPolyline; // Polyline for WiFi path
    private LatLng lastGnssLocation = null; // Stores the last GNSS location
    private LatLng lastWifiLocation = null; // Stores the last WiFi location

    // Color-coded observation dot markers (last N from each source)
    private static final int MAX_OBSERVATION_DOTS = 20;
    private final List<Circle> gnssObsDots = new ArrayList<>();
    private final List<Circle> wifiObsDots = new ArrayList<>();

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

    // UI
    private Spinner switchMapSpinner;

    private SwitchMaterial gnssSwitch;
    private SwitchMaterial wifiSwitch;
    private SwitchMaterial autoFloorSwitch;
    private SwitchMaterial smoothSwitch;

    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private TextView floorLabel;
    private Button switchColorButton;
    private Polygon buildingPolygon;


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
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        smoothSwitch    = view.findViewById(R.id.smoothSwitch);
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

        // WiFi Switch
        wifiSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isWifiOn = isChecked;
            if (!isChecked && wifiMarker != null) {
                wifiMarker.remove();
                wifiMarker = null;
            }
        });

        // Smooth Filter Switch
        smoothSwitch.setChecked(true);
        smoothSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isSmoothOn = isChecked;
            Log.e(TAG, "Smooth filter " + (isChecked ? "ON (fused)" : "OFF (raw PDR)"));
        });

        // Color switch — applies to all floor polyline segments
        switchColorButton.setOnClickListener(v -> {
            int newColor;
            if (isRed) {
                newColor = Color.BLACK;
                switchColorButton.setBackgroundColor(Color.BLACK);
                isRed = false;
            } else {
                newColor = Color.RED;
                switchColorButton.setBackgroundColor(Color.RED);
                isRed = true;
            }
            for (List<Polyline> segs : floorPolylines.values()) {
                for (Polyline p : segs) p.setColor(newColor);
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
                onFloorChanged(indoorMapManager.getCurrentFloor());
                updateFloorLabel();
            }
        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
                onFloorChanged(indoorMapManager.getCurrentFloor());
                updateFloorLabel();
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

        // Per-floor polyline: will be created on first position update
        activePolyline = null;
        polylineFloor = -1;

        // GNSS path in blue
        gnssPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.BLUE)
                .width(5f)
                .add() // start empty
        );

        // WiFi path in green
        wifiPolyline = map.addPolyline(new PolylineOptions()
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

        // Clamp position to building boundary (outer wall detection)
        if (indoorMapManager != null) {
            newLocation = indoorMapManager.clampToBuildingBoundary(newLocation);
        }

        // Constrain to inner walls: slide along side walls, bounce off front walls
        if (indoorMapManager != null && this.currentLocation != null) {
            newLocation = indoorMapManager.constrainToWalls(this.currentLocation, newLocation);
        }

        // Keep track of current location
        LatLng oldLocation = this.currentLocation;
        this.currentLocation = newLocation;

        // If no marker, create it
        if (orientationMarker == null) {
            Log.e(TAG, "PDR marker created at: " + newLocation.latitude + ", " + newLocation.longitude);
            orientationMarker = gMap.addMarker(new MarkerOptions()
                    .position(newLocation)
                    .flat(true)
                    .title("Current Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_24)))
            );
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
        } else {
            // Update marker position + orientation
            orientationMarker.setPosition(newLocation);
            orientationMarker.setRotation(orientation);
            // Move camera a bit
            gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
        }

        // Detect large jumps on the red PDR polyline
        if (oldLocation != null) {
            double pdrJump = UtilFunctions.distanceBetweenPoints(oldLocation, newLocation);
            if (pdrJump > 10) {
                Log.e(TAG, "WARNING: PDR polyline large jump " + String.format("%.1f", pdrJump)
                        + "m from (" + oldLocation.latitude + "," + oldLocation.longitude
                        + ") to (" + newLocation.latitude + "," + newLocation.longitude + ")");
            }
        }

        // Update indoor map overlay (before polyline so we know the current floor)
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(newLocation);
            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
        }

        // Per-floor polyline: start a new segment when floor changes
        int currentFloorIdx = (indoorMapManager != null) ? indoorMapManager.getCurrentFloor() : 0;
        if (activePolyline == null || currentFloorIdx != polylineFloor) {
            // Floor changed or first segment — create a new polyline for this floor
            startNewPolylineSegment(currentFloorIdx, newLocation);
        }

        // Extend the active polyline segment
        if (activePolyline != null) {
            List<LatLng> points = new ArrayList<>(activePolyline.getPoints());

            if (oldLocation == null) {
                points.add(newLocation);
                activePolyline.setPoints(points);
            } else if (!oldLocation.equals(newLocation)) {
                points.add(newLocation);
                activePolyline.setPoints(points);
            }

            if (points.size() % 20 == 0) {
                Log.e(TAG, "PDR polyline total points: " + points.size()
                        + " | floor=" + currentFloorIdx
                        + " | current pos: " + newLocation.latitude + ", " + newLocation.longitude);
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
        if (!isGnssOn) return;

        if (gnssMarker == null) {
            // Create the GNSS marker for the first time
            Log.e(TAG, "GNSS marker created at: " + gnssLocation.latitude + ", " + gnssLocation.longitude);
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
                double jumpDist = UtilFunctions.distanceBetweenPoints(lastGnssLocation, gnssLocation);
                if (jumpDist > 50) {
                    Log.e(TAG, "WARNING: GNSS polyline jump " + String.format("%.1f", jumpDist)
                            + "m from (" + lastGnssLocation.latitude + "," + lastGnssLocation.longitude
                            + ") to (" + gnssLocation.latitude + "," + gnssLocation.longitude + ")");
                }
                List<LatLng> gnssPoints = new ArrayList<>(gnssPolyline.getPoints());
                gnssPoints.add(gnssLocation);
                gnssPolyline.setPoints(gnssPoints);
                Log.e(TAG, "GNSS polyline points: " + gnssPoints.size()
                        + " | latest: " + gnssLocation.latitude + ", " + gnssLocation.longitude);
            }
            lastGnssLocation = gnssLocation;
        }

        // Add color-coded observation dot
        addGnssObservationDot(gnssLocation);
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
     * Update WiFi position marker and polyline on the map.
     *
     * @param wifiLocation the WiFi-estimated position
     */
    public void updateWiFi(@NonNull LatLng wifiLocation) {
        if (gMap == null) return;
        if (!isWifiOn) return;

        if (wifiMarker == null) {
            Log.e(TAG, "WiFi marker created at: " + wifiLocation.latitude + ", " + wifiLocation.longitude);
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

        // Add color-coded observation dot
        addObservationDot(wifiObsDots, wifiLocation,
                Color.argb(180, 0, 200, 0)); // green
    }

    /**
     * Remove WiFi marker if user toggles it off
     */
    public void clearWiFi() {
        if (wifiMarker != null) {
            wifiMarker.remove();
            wifiMarker = null;
        }
    }

    /**
     * Whether user is currently showing WiFi or not
     */
    public boolean isWifiEnabled() {
        return isWifiOn;
    }

    /**
     * Whether the smooth filter (particle fusion) is enabled.
     * When true, display fused position; when false, display raw PDR.
     */
    public boolean isSmoothEnabled() {
        return isSmoothOn;
    }

    /**
     * Adds a GNSS observation dot to the map (called each GNSS update).
     */
    public void addGnssObservationDot(@NonNull LatLng location) {
        if (gMap == null) return;
        addObservationDot(gnssObsDots, location,
                Color.argb(180, 0, 120, 255)); // blue
    }

    /**
     * Adds a colored circle dot to the map for a position observation.
     * Keeps at most MAX_OBSERVATION_DOTS per source, removing the oldest.
     */
    private void addObservationDot(List<Circle> dotList, LatLng position, int color) {
        Circle dot = gMap.addCircle(new CircleOptions()
                .center(position)
                .radius(1.5) // ~1.5 meter radius
                .strokeWidth(1f)
                .strokeColor(color)
                .fillColor(color)
                .zIndex(2));
        dotList.add(dot);

        // Remove oldest dots beyond the limit
        while (dotList.size() > MAX_OBSERVATION_DOTS) {
            dotList.remove(0).remove();
        }
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
        for (List<Polyline> segs : floorPolylines.values()) {
            for (Polyline p : segs) p.remove();
        }
        floorPolylines.clear();
        activePolyline = null;
        polylineFloor = -1;
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

        // Clear observation dots
        for (Circle c : gnssObsDots) c.remove();
        gnssObsDots.clear();
        for (Circle c : wifiObsDots) c.remove();
        wifiObsDots.clear();

        // Clear test point markers
        for (Marker m : testPointMarkers) {
            m.remove();
        }
        testPointMarkers.clear();

        // Re-create empty GNSS/WiFi polylines (PDR polyline is per-floor, created on demand)
        if (gMap != null) {
            gnssPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.BLUE)
                    .width(5f)
                    .add());
            wifiPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.GREEN)
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

    //region Per-floor polyline management

    /**
     * Creates a new polyline segment on the given floor. Hides segments from
     * other floors and shows segments for the target floor.
     *
     * @param floorIdx    the floor index to start drawing on
     * @param startPoint  the first point of the new segment (continuity anchor)
     */
    private void startNewPolylineSegment(int floorIdx, LatLng startPoint) {
        if (gMap == null) return;

        // Hide old floor's segments, show new floor's segments
        if (floorIdx != polylineFloor) {
            setFloorPolylinesVisible(polylineFloor, false);
            setFloorPolylinesVisible(floorIdx, true);
            Log.e(TAG, "POLYLINE floor switch: " + polylineFloor + " -> " + floorIdx);
        }

        polylineFloor = floorIdx;

        // Create a new segment starting at the current point
        activePolyline = gMap.addPolyline(new PolylineOptions()
                .color(isRed ? Color.RED : Color.BLACK)
                .width(5f)
                .add(startPoint));

        List<Polyline> segs = floorPolylines.get(floorIdx);
        if (segs == null) {
            segs = new ArrayList<>();
            floorPolylines.put(floorIdx, segs);
        }
        segs.add(activePolyline);
    }

    /**
     * Shows or hides all polyline segments for a given floor.
     */
    private void setFloorPolylinesVisible(int floorIdx, boolean visible) {
        List<Polyline> segs = floorPolylines.get(floorIdx);
        if (segs == null) return;
        for (Polyline p : segs) {
            p.setVisible(visible);
        }
    }

    /**
     * Called by IndoorMapManager (via evaluateAutoFloor) when the displayed floor
     * changes. Toggles polyline visibility so only the current floor's path shows.
     */
    public void onFloorChanged(int newFloorIdx) {
        if (newFloorIdx == polylineFloor) return;
        setFloorPolylinesVisible(polylineFloor, false);
        setFloorPolylinesVisible(newFloorIdx, true);
    }

    //endregion

    //region Auto-floor logic

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

        int candidateFloor;
        String source;

        // Priority 1: barometric elevation with 70% hysteresis threshold
        float elevation = sensorFusion.getElevation();
        float floorHeight = indoorMapManager.getFloorHeight();
        if (floorHeight > 0) {
            float ratio = elevation / floorHeight;
            int lowerFloor = (int) Math.floor(ratio);
            float frac = ratio - lowerFloor;
            if (frac >= 0.7f) {
                candidateFloor = lowerFloor + 1;
            } else {
                candidateFloor = lowerFloor;
            }
            source = "Baro(elev=" + String.format("%.1f", elevation)
                    + ",height=" + String.format("%.1f", floorHeight)
                    + ",ratio=" + String.format("%.2f", ratio)
                    + ",frac=" + String.format("%.2f", frac) + ")";
        } else if (sensorFusion.getLatLngWifiPositioning() != null) {
            candidateFloor = sensorFusion.getWifiFloor();
            source = "WiFi(floor=" + candidateFloor + ")";
        } else {
            return;
        }

        Log.e(TAG, "AUTO_FLOOR immediate: candidate=" + candidateFloor
                + " | source=" + source
                + " | bias=" + indoorMapManager.getAutoFloorBias());

        indoorMapManager.setCurrentFloor(candidateFloor, true);
        onFloorChanged(indoorMapManager.getCurrentFloor());
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

        int candidateFloor;
        String source;

        // Priority 1: barometric elevation (responds in seconds to floor changes)
        // Uses 70% hysteresis threshold to prevent oscillation at floor boundaries.
        // With Math.round() (50% threshold), barometric noise causes constant
        // floor flipping when elevation hovers near a floor boundary (e.g. on stairs).
        float elevation = sensorFusion.getElevation();
        float floorHeight = indoorMapManager.getFloorHeight();
        if (floorHeight > 0) {
            float ratio = elevation / floorHeight;
            int lowerFloor = (int) Math.floor(ratio);
            float frac = ratio - lowerFloor;
            // Only assign to upper floor when clearly past 70% of floor height;
            // this creates a dead zone (30%-70%) that prevents oscillation.
            if (frac >= 0.7f) {
                candidateFloor = lowerFloor + 1;
            } else {
                candidateFloor = lowerFloor;
            }
            source = "Baro(elev=" + String.format("%.1f", elevation)
                    + ",height=" + String.format("%.1f", floorHeight)
                    + ",ratio=" + String.format("%.2f", ratio)
                    + ",frac=" + String.format("%.2f", frac) + ")";
        } else if (sensorFusion.getLatLngWifiPositioning() != null) {
            // Fallback: WiFi floor (slower to update but works without barometer)
            candidateFloor = sensorFusion.getWifiFloor();
            source = "WiFi(floor=" + candidateFloor + ")";
        } else {
            return;
        }

        // Debounce: require the same floor reading for AUTO_FLOOR_DEBOUNCE_MS
        long now = SystemClock.elapsedRealtime();
        if (candidateFloor != lastCandidateFloor) {
            Log.e(TAG, "AUTO_FLOOR candidate changed: " + lastCandidateFloor
                    + " -> " + candidateFloor + " | source=" + source
                    + " | bias=" + indoorMapManager.getAutoFloorBias()
                    + " | debounce reset");
            lastCandidateFloor = candidateFloor;
            lastCandidateTime = now;
            return;
        }

        if (now - lastCandidateTime >= AUTO_FLOOR_DEBOUNCE_MS) {
            // Floor-change gate: only allow near stairs or lift
            int targetIndex = candidateFloor + indoorMapManager.getAutoFloorBias();
            int currentIndex = indoorMapManager.getCurrentFloor();
            if (targetIndex != currentIndex) {
                String transport = indoorMapManager.getNearbyVerticalTransport(currentLocation);
                if (transport == null) {
                    Log.e(TAG, "AUTO_FLOOR BLOCKED: not near stairs/lift"
                            + " | candidate=" + candidateFloor
                            + " | targetIdx=" + targetIndex
                            + " | currentIdx=" + currentIndex
                            + " | source=" + source);
                    // Don't reset timer — keep checking each cycle
                    return;
                }
                boolean isElevator = sensorFusion.getElevator();
                String motionType = isElevator ? "elevator" : "walking/stairs";
                Log.e(TAG, "AUTO_FLOOR gate PASSED: near " + transport
                        + " | motionType=" + motionType
                        + " | candidate=" + candidateFloor
                        + " | " + currentIndex + " -> " + targetIndex);
            }

            Log.e(TAG, "AUTO_FLOOR applied: candidate=" + candidateFloor
                    + " | source=" + source
                    + " | finalIndex=" + targetIndex
                    + " | display=" + indoorMapManager.getCurrentFloorDisplayName());
            indoorMapManager.setCurrentFloor(candidateFloor, true);
            onFloorChanged(indoorMapManager.getCurrentFloor());
            updateFloorLabel();
            // Reset timer so we don't keep re-applying the same floor
            lastCandidateTime = now;
        }
    }

    //endregion
}
