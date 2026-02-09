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

import com.google.android.gms.maps.CameraUpdateFactory;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import android.os.Handler;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.model.LatLng;
import android.content.Context;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;

import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.switchmaterial.SwitchMaterial;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.OnMapReadyCallback;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.*;

import android.widget.Toast;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;


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

    private LatLng pendingCameraPosition = null; // Stores pending camera movement
    private boolean hasPendingCameraMove = false; // Tracks if camera needs to move

    private IndoorMapManager indoorMapManager; // Manages indoor mapping
    private SensorFusion sensorFusion;


    // UI
    private Spinner switchMapSpinner;

    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;

    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private com.google.android.material.floatingactionbutton.FloatingActionButton exitIndoorButton;
    private ServerCommunications serverCommunications;

    private com.google.android.material.floatingactionbutton.FloatingActionButton recenterButton;

    // just focus one time when entry this fragment auto
    private boolean pendingInitialRecenter = true;

    private boolean followMyLocation = false;

    private final Handler indoorHandler = new Handler();
    private Runnable indoorTask;

    private boolean autoFloorEnabled = false;
    private final android.os.Handler autoFloorHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable autoFloorRunnable;

    private Float autoFloorBaseElevation = null;
    private long lastAutoFloorSwitchMs = 0L;
    private static final long AUTO_FLOOR_INTERVAL_MS = 800;
    private static final long AUTO_FLOOR_DEBOUNCE_MS = 1500;
    private static final float DEFAULT_FLOOR_HEIGHT_M = 3.2f;

    private com.google.android.material.chip.Chip floorLabelChip;
    private int currentFloorIndex = 0;


    private float indoorPrevPosX = 0f;
    private float indoorPrevPosY = 0f;
    private boolean indoorRunning = false;

    // ===== Floorplan request timing control =====
    private boolean hasReceivedFloorplan = false;
    private long lastFloorplanRequestTime = 0L;

    private static final long FLOORPLAN_FAST_INTERVAL_MS = 5_000;
    private static final long FLOORPLAN_SLOW_INTERVAL_MS = 30_000;

    //end
    private Button switchColorButton;
    private Polygon buildingPolygon;
    //Chen :Check the venue nearby
    // ===== Remote floorplan drawing state =====
    private static class NearbyVenue {
        final String name;
        final String outlineGeoJson;   // FeatureCollection string
        final String mapShapesJson;    // JSONObject string: { "B1": {...}, "GF": {...} }

        NearbyVenue(String name, String outlineGeoJson, String mapShapesJson) {
            this.name = name;
            this.outlineGeoJson = outlineGeoJson;
            this.mapShapesJson = mapShapesJson;
        }
    }

    private final List<Polygon> nearbyVenuePolygons = new ArrayList<>();
    private final List<Polyline> indoorShapeLines = new ArrayList<>();

    private NearbyVenue selectedVenue = null;
    private final List<String> availableFloors = new ArrayList<>();
    private int currentFloorIdx = 0;

    //end

    public TrajectoryMapFragment() {
        // Required empty public constructor
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        android.util.Log.e("Floorplan", "TrajectoryMapFragment onCreateView");
        // Inflate the separate layout containing map + map-related UI
        return inflater.inflate(R.layout.fragment_trajectory_map, container, false);
    }
 //Bind UI controls and set their initial visibility (floor buttons/exit buttons, etc.)
    @Override
    public void onCreate(@androidx.annotation.Nullable android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.util.Log.e("Floorplan", "TrajectoryMapFragment onCreate");
    }
    //end
    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        int fine = androidx.core.content.ContextCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION);
        Log.e("IndoorDebug", "fine permission=" + fine);

        android.util.Log.e("Floorplan", "TrajectoryMapFragment onViewCreated");

        floorLabelChip = view.findViewById(R.id.floorLabelChip);
        floorLabelChip.setText("Floor: -");
        floorLabelChip.setVisibility(View.GONE);
        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch      = view.findViewById(R.id.gnssSwitch);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);

        // Setup floor up/down UI hidden initially until we know there's an indoor map
        setFloorControlsVisibility(View.GONE);
        Log.e("INDOOR", "HIDE floor controls called, selectedVenue=start" );

        //set exit Button
        exitIndoorButton = view.findViewById(R.id.exitIndoorButton);
        exitIndoorButton.setVisibility(View.GONE);
        //set recenter Button
        recenterButton = view.findViewById(R.id.recenterButton);
        recenterButton.setVisibility(View.VISIBLE);
        recenterButton.setOnClickListener(v -> {
            recenterToCurrentLocation(true);
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
                    //set polycon and available of click
                    drawBuildingPolygon();
                    setupBuildingPolygonClicks();
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

        // Floor up/down logic (include autofloor)
        autoFloorSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            autoFloorEnabled = isChecked;

            if (!isChecked) {
                autoFloorHandler.removeCallbacksAndMessages(null);
                autoFloorBaseElevation = null;
                return;
            }

            if (selectedVenue == null || availableFloors.isEmpty()) {
                Toast.makeText(requireContext(),
                        "Select a building with indoor floors first",
                        Toast.LENGTH_SHORT).show();
                autoFloorSwitch.setChecked(false);
                autoFloorEnabled = false;
                return;
            }

            if (sensorFusion == null) {
                Toast.makeText(requireContext(),
                        "SensorFusion unavailable",
                        Toast.LENGTH_SHORT).show();
                autoFloorSwitch.setChecked(false);
                autoFloorEnabled = false;
                return;
            }

            autoFloorBaseElevation = sensorFusion.getElevation();
            lastAutoFloorSwitchMs = 0L;

            if (autoFloorRunnable == null) {
                autoFloorRunnable = () -> {
                    if (!autoFloorEnabled) return;

                    if (selectedVenue == null || availableFloors.isEmpty() || sensorFusion == null) {
                        autoFloorEnabled = false;
                        autoFloorSwitch.setChecked(false);
                        return;
                    }

                    float elev = sensorFusion.getElevation();
                    Log.e("AUTO_FLOOR", "elev=" + elev + " base=" + autoFloorBaseElevation + " curIdx=" + currentFloorIdx);

                    if (autoFloorBaseElevation == null) autoFloorBaseElevation = elev;

                    float floorHeight = DEFAULT_FLOOR_HEIGHT_M;

                    int targetIdx = elevationToFloorIndex(elev, autoFloorBaseElevation, floorHeight);

                    targetIdx = Math.max(0, Math.min(targetIdx, availableFloors.size() - 1));

                    long now = android.os.SystemClock.uptimeMillis();
                    if (targetIdx != currentFloorIdx && (now - lastAutoFloorSwitchMs) > AUTO_FLOOR_DEBOUNCE_MS) {
                        lastAutoFloorSwitchMs = now;
                        currentFloorIdx = targetIdx;
                        drawIndoorShapesForFloor(selectedVenue, availableFloors.get(currentFloorIdx));
                        updateFloorLabelChip();
                    }

                    autoFloorHandler.postDelayed(autoFloorRunnable, AUTO_FLOOR_INTERVAL_MS);
                };
            }
            autoFloorHandler.post(autoFloorRunnable);
        });

        floorUpButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);

            if (selectedVenue != null && !availableFloors.isEmpty()) {
                currentFloorIdx = Math.min(currentFloorIdx + 1, availableFloors.size() - 1);
                drawIndoorShapesForFloor(selectedVenue, availableFloors.get(currentFloorIdx));
                updateFloorLabelChip();
                return;
            }

            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
            }
        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);

            if (selectedVenue != null && !availableFloors.isEmpty()) {
                currentFloorIdx = Math.max(currentFloorIdx - 1, 0);
                drawIndoorShapesForFloor(selectedVenue, availableFloors.get(currentFloorIdx));
                updateFloorLabelChip();
                return;
            }

            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
            }
        });

        exitIndoorButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            selectedVenue = null;
            availableFloors.clear();
            currentFloorIdx = 0;
            clearIndoorShapes();

            if (indoorMapManager != null) {
                indoorMapManager.clearManualModeAndRemoveOverlay();
            }

            if (selectedVenue == null ) {
                setFloorControlsVisibility(View.GONE);
                exitIndoorButton.setVisibility(View.GONE);
            }
            Log.e("INDOOR", "HIDE floor controls called, selectedVenue=" + (selectedVenue == null ? "null" : selectedVenue.name));
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
        map.getUiSettings().setZoomGesturesEnabled(true);

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
     * Update the user's current location on the map, create or move orientation marker,
     * and append to polyline if the user actually moved.
     *
     * @param newLocation The new location to plot.
     * @param orientation The user’s heading (e.g. from sensor fusion).
     */
    public void updateUserLocation(@NonNull LatLng newLocation, float orientation) {
        if (gMap == null) {
            Log.e("Floorplan", "updateUserLocation entered but gMap==null, returning");
            return;
        }
        Log.e("Floorplan", "updateUserLocation CALLED newLocation=" + newLocation);

        // Keep track of current location
        LatLng oldLocation = this.currentLocation;
        this.currentLocation = newLocation;

        if (serverCommunications == null) {
            Log.e("Floorplan", "serverCommunications is null");
            return;
        }

        int wifiCount = (sensorFusion != null && sensorFusion.getWifiList() != null) ? sensorFusion.getWifiList().size() : 0;        Log.d("Floorplan", "sending floorplan request lat=" + currentLocation.latitude
                + " lon=" + currentLocation.longitude
                + " wifiCount=" + wifiCount);

        Log.e("Floorplan", "🚨 HIT FLOORPLAN CALL SITE");


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
            if (pendingInitialRecenter) {
                gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
                pendingInitialRecenter = false;
            }
        } else {
            // Update marker position + orientation
            orientationMarker.setPosition(newLocation);
            orientationMarker.setRotation(orientation);
            // Move camera a bit
            if (followMyLocation) {
                gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
            }
            if (pendingInitialRecenter) {
                gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
                pendingInitialRecenter = false;
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
            boolean apiIndoorActive = (selectedVenue != null && !availableFloors.isEmpty());
            boolean overlayIndoorActive = indoorMapManager.getIsIndoorMapSet();

            setFloorControlsVisibility((apiIndoorActive || overlayIndoorActive) ? View.VISIBLE : View.GONE);
            if (exitIndoorButton != null) {
                exitIndoorButton.setVisibility((apiIndoorActive || overlayIndoorActive) ? View.VISIBLE : View.GONE);
            }

        }
    }

//Chen
    private long lastFloorplanRequestMs = 0L;
    private boolean floorplanInFlight = false;
    public void requestFloorplansIfNeeded(@NonNull LatLng location) {
        Log.e("Floorplan", "TrajectoryMapFragment.requestFloorplansIfNeeded called");

        if (serverCommunications == null) {
            Log.e("Floorplan", "serverCommunications is null");
            return;
        }
        if (sensorFusion == null) {
            Log.e("Floorplan", "sensorFusion is null");
            return;
        }

        long now = System.currentTimeMillis();

        if (floorplanInFlight) return;

        long requiredIntervalMs = hasReceivedFloorplan ? 30_000L : 5_000L;

        if (now - lastFloorplanRequestMs < requiredIntervalMs) return;

        lastFloorplanRequestMs = now;
        floorplanInFlight = true;

        int wifiCount = (sensorFusion.getWifiList() == null) ? 0 : sensorFusion.getWifiList().size();
        Log.e("Floorplan", "🚨 requestFloorplansIfNeeded lat=" + location.latitude
                + " lon=" + location.longitude
                + " wifiCount=" + wifiCount);

        serverCommunications.requestFloorplans(
                location.latitude,
                location.longitude,
                sensorFusion.getWifiList(),
                new ServerCommunications.FloorplanCallback() {
                    @Override
                    public void onSuccess(org.json.JSONObject response) {
                        floorplanInFlight = false;
                        hasReceivedFloorplan = true;

                        Log.d("Floorplan", "Floorplan success, switch to slow refresh");
                        Log.e("Floorplan", "✅ SUCCESS keys=" + response.names());
                        Log.e("Floorplan", "response=" + response.toString());

                        renderNearbyFloorplans(response);

                    }

                    @Override
                    public void onError(String error) {
                        floorplanInFlight = false;
                        Log.e("Floorplan", "ERROR " + error);
                    }
                }
        );
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

    // ===== Test Point marker (Part C) =====
    public void addTestPointMarker(@NonNull LatLng pos, int index) {
        if (gMap == null) return;

        gMap.addMarker(new MarkerOptions()
                .position(pos)
                .title("TP " + index)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
        );
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

    private void setFloorControlsVisibility(int visibility) {
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);
        autoFloorSwitch.setVisibility(visibility);
        if (floorLabelChip != null) floorLabelChip.setVisibility(visibility);
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
        //murchision
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

        // Nucleus
        buildingPolygon = gMap.addPolygon(buildingPolygonOptions);
        buildingPolygon.setClickable(true);
        buildingPolygon.setTag("NUCLEUS");

        Log.d("TrajectoryMapFragment", "Building polygon added, vertex count: " + buildingPolygon.getPoints().size());
    }

    //Chen: Set polygon click listener; resolve the clicked venue by tag and trigger indoor selection / floor flow.
    private void setupBuildingPolygonClicks() {
        if (gMap == null) return;

        gMap.setOnPolygonClickListener(polygon -> {
            Object tagObj = polygon.getTag();
            Log.e("POLY", "clicked polygon tag=" + (tagObj == null ? "null" : tagObj.getClass().getName()));
            if (tagObj instanceof NearbyVenue) {

                onVenueSelected((NearbyVenue) tagObj);
                return;
            }
            Log.e("POLY", "clicked polygon but tag is not NearbyVenue: " + tagObj);
            String tag = tagObj == null ? "" : tagObj.toString();
            if ("NUCLEUS".equals(tag)) {
                Log.e("POLY", "NUCLEUS clicked: local overlay disabled");
                return;
            }
        });
    }

    //Chen :Check whether this fragment is hosted under RecordingFragment (to switch behaviors by context).
    private boolean isInsideRecordingFragment() {
        return getParentFragment() instanceof RecordingFragment;
    }

    //Chen :Indoor-positioning tick loop: read SensorFusion pose/elevation, infer floor, refresh indoor rendering and UI.
    private void tickIndoorPositioning() {
        Log.e("IndoorDebug", "tickIndoorPositioning called");

        if (sensorFusion == null) {
            Log.e("IndoorDebug", "sensorFusion == null");
            return;
        }

        float[] latLngArray = sensorFusion.getGNSSLatitude(false);
        if (latLngArray == null) {
            Log.e("IndoorDebug", "latLngArray == null (GNSS not ready)");
            return;
        }

        double lat = latLngArray[0];
        double lon = latLngArray[1];

        if (Math.abs(lat) < 1e-6 && Math.abs(lon) < 1e-6) {
            Log.e("IndoorDebug", "GNSS is (0,0), waiting emulator location...");
            return;
        }

        LatLng newLocation = new LatLng(lat, lon);

        float orientation = 0f;
        try {
            orientation = (float) Math.toDegrees(sensorFusion.passOrientation());
        } catch (Exception ignored) {}

        Log.e("IndoorDebug", "GNSS newLocation=" + newLocation.latitude + "," + newLocation.longitude);

        updateUserLocation(newLocation, orientation);
        requestFloorplansIfNeeded(newLocation);
    }


    @Override
    public void onResume() {
        super.onResume();

        if (isInsideRecordingFragment()) return;

        pendingInitialRecenter = true;
        followMyLocation = false;

        indoorRunning = true;

        indoorTask = new Runnable() {
            @Override
            public void run() {
                if (!indoorRunning) return;
                tickIndoorPositioning();
                indoorHandler.postDelayed(this, 200);
            }
        };
        indoorHandler.post(indoorTask);
    }

    @Override
    public void onPause() {
        super.onPause();

        if (isInsideRecordingFragment()) return;

        indoorRunning = false;
        indoorHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof MainActivity) {
            MainActivity act = (MainActivity) context;

            sensorFusion = act.getSensorFusion();
            Log.e("IndoorDebug", "TrajectoryMapFragment got sensorFusion=" + sensorFusion);
        }

        serverCommunications = new ServerCommunications(context.getApplicationContext());
        Log.e("Floorplan", "TrajectoryMapFragment serverCommunications=" + serverCommunications);


        Log.e("Floorplan", "TrajectoryMapFragment serverCommunications=" + serverCommunications);
        if (context instanceof com.openpositioning.PositionMe.presentation.activity.MainActivity) {
            com.openpositioning.PositionMe.presentation.activity.MainActivity act =
                    (com.openpositioning.PositionMe.presentation.activity.MainActivity) context;
            sensorFusion = act.getSensorFusion();
            Log.e("IndoorDebug", "TrajectoryMapFragment got sensorFusion=" + sensorFusion);
        } else {
            Log.e("IndoorDebug", "TrajectoryMapFragment: host activity is not MainActivity");
        }

        if (sensorFusion == null) {
            sensorFusion = SensorFusion.getInstance();
            Log.e("IndoorDebug", "TrajectoryMapFragment fallback SensorFusion.getInstance()=" + sensorFusion);
        }

    }

    //Chen :Parse floorplan results, clear old polygons, draw new venue outlines, and build clickable venue list.
    private void renderNearbyFloorplans(@NonNull org.json.JSONObject wrapper) {
        if (gMap == null) return;

        clearNearbyVenuePolygons();

        try {
            org.json.JSONArray results = wrapper.optJSONArray("results");
            if (results == null || results.length() == 0) {
                Log.e("Floorplan", "results empty");
                return;
            }
            try {
                String existing = com.openpositioning.PositionMe.utils.CampaignStore.get(requireContext());
                if (existing == null || existing.isEmpty()) {
                    String firstName = results.getJSONObject(0).optString("name", "");
                    if (firstName != null && !firstName.isEmpty()) {
                        com.openpositioning.PositionMe.utils.CampaignStore.set(requireContext(), firstName);
                        Log.e("CAMPAIGN", "Saved default campaign from results[0]: " + firstName);
                    }
                }
            } catch (Exception ignored) {}

            for (int i = 0; i < results.length(); i++) {
                org.json.JSONObject item = results.getJSONObject(i);

                String name = item.optString("name", "unknown");
                String outline = item.optString("outline", "");
                String mapShapes = item.optJSONObject("map_shapes") != null
                        ? item.getJSONObject("map_shapes").toString()
                        : item.optString("map_shapes", "");

                if (outline == null || outline.isEmpty()) continue;

                NearbyVenue v = new NearbyVenue(name, outline, mapShapes);

                List<Polygon> polys = drawOutlineMultiPolygon(v);
                nearbyVenuePolygons.addAll(polys);
            }

            Log.e("Floorplan", "drawn venue polygons=" + nearbyVenuePolygons.size());
        } catch (Exception e) {
            Log.e("Floorplan", "renderNearbyFloorplans error: " + e.getMessage());
        }
    }

    //Chen :Draw outline polygons from venue GeoJSON (FeatureCollection/MultiPolygon, etc.) and return polygon handles.
    private List<Polygon> drawOutlineMultiPolygon(@NonNull NearbyVenue venue) throws Exception {
        List<Polygon> out = new ArrayList<>();

        org.json.JSONObject fc = new org.json.JSONObject(venue.outlineGeoJson);
        org.json.JSONArray features = fc.getJSONArray("features");

        for (int i = 0; i < features.length(); i++) {
            org.json.JSONObject geom = features.getJSONObject(i).getJSONObject("geometry");
            String type = geom.optString("type", "");

            if (!"MultiPolygon".equals(type)) continue;

            // coordinates: [ [ [ [lon,lat]... ] ] , ... ]
            org.json.JSONArray multiPoly = geom.getJSONArray("coordinates");

            for (int p = 0; p < multiPoly.length(); p++) {
                org.json.JSONArray polygon = multiPoly.getJSONArray(p);
                if (polygon.length() == 0) continue;

                org.json.JSONArray ring = polygon.getJSONArray(0);

                PolygonOptions po = new PolygonOptions()
                        .clickable(true)
                        .strokeWidth(10f)
                        .strokeColor(0xFFFF0000)   // red
                        .fillColor(0x3300AEEF);    // blue

                for (int pt = 0; pt < ring.length(); pt++) {
                    org.json.JSONArray xy = ring.getJSONArray(pt);
                    double lon = xy.getDouble(0);
                    double lat = xy.getDouble(1);
                    po.add(new LatLng(lat, lon));
                }

                Polygon poly = gMap.addPolygon(po);
                poly.setTag(venue);
                out.add(poly);
            }
        }
        return out;
    }

    //Chen :Remove and clear currently drawn nearby-venue polygons to avoid stacking and leaks.
    private void clearNearbyVenuePolygons() {
        for (Polygon p : nearbyVenuePolygons) {
            try { p.remove(); } catch (Exception ignored) {}
        }
        nearbyVenuePolygons.clear();
    }

    //Chen :Handle venue selection: persist campaign, prepare floor list, set current floor, draw indoor shapes and update controls.
    private void onVenueSelected(@NonNull NearbyVenue v) {
        selectedVenue = v;

        com.openpositioning.PositionMe.utils.CampaignStore.set(requireContext(), v.name);
        Log.e("CAMPAIGN", "Saved campaign from selected venue: " + v.name);

        clearIndoorShapes();

        availableFloors.clear();
        try {
            JSONObject mapShapes = new JSONObject(v.mapShapesJson);
            Iterator<String> it = mapShapes.keys();
            while (it.hasNext()) {
                availableFloors.add(it.next());
            }
        } catch (Exception e) {
            Log.e("Floorplan", "map_shapes parse error: " + e.getMessage());
        }
        Log.e("INDOOR", "availableFloors=" + availableFloors);

        sortFloorKeys(availableFloors);

        currentFloorIdx = availableFloors.indexOf("GF") >= 0
                ? availableFloors.indexOf("GF")
                : 0;

        setFloorControlsVisibility(View.VISIBLE);
        updateFloorLabelChip();

        if (exitIndoorButton != null) exitIndoorButton.setVisibility(View.VISIBLE);

        if (selectedVenue != null && !availableFloors.isEmpty()) {
            drawIndoorShapesForFloor(selectedVenue, availableFloors.get(currentFloorIdx));
            updateFloorLabelChip();

        }
    }

    //Chen :Remove and clear current indoor shape polylines when switching floors or exiting indoor mode.
    private void clearIndoorShapes() {
        for (Polyline l : indoorShapeLines) {
            try { l.remove(); } catch (Exception ignored) {}
        }
        indoorShapeLines.clear();
    }

    //Chen :For a given floorKey, parse that floor’s GeoJSON shapes and render them as polylines on the map.
    private void drawIndoorShapesForFloor(@NonNull NearbyVenue v, @NonNull String floorKey) {
        clearIndoorShapes();

        try {
            JSONObject mapShapes = new JSONObject(v.mapShapesJson);
            boolean has = mapShapes.has(floorKey);
            Log.e("INDOOR", "drawIndoorShapesForFloor floor=" + floorKey + " hasKey=" + has);
            if (!has) return;

            JSONObject fc = mapShapes.getJSONObject(floorKey);
            JSONArray features = fc.optJSONArray("features");
            if (features == null) {
                Log.e("INDOOR", "features is null");
                return;
            }

            int added = 0;

            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.getJSONObject(i);
                JSONObject geom = feature.optJSONObject("geometry");
                if (geom == null) continue;

                String type = geom.optString("type", "");
                Log.e("INDOOR", "feature[" + i + "] type=" + type);

                switch (type) {
                    case "MultiLineString":
                        added += drawMultiLineString(geom.getJSONArray("coordinates"));
                        break;

                    case "LineString":
                        added += drawLineString(geom.getJSONArray("coordinates"));
                        break;

                    case "Polygon":
                        added += drawPolygonAsLines(geom.getJSONArray("coordinates"));
                        break;

                    case "MultiPolygon":
                        added += drawMultiPolygonAsLines(geom.getJSONArray("coordinates"));
                        break;

                    default:
                        break;
                }
            }

            Log.e("INDOOR", "indoor drawn floor=" + floorKey + " linesAdded=" + added);

        } catch (Exception e) {
            Log.e("INDOOR", "drawIndoorShapesForFloor error: " + e.getMessage());
        }
    }

    //Chen :Render a single LineString coordinate array as a polyline and store it in indoorShapeLines.
    private int drawLineString(@NonNull JSONArray coords) throws Exception {
        PolylineOptions plo = new PolylineOptions()
                .width(8f)
                .color(0xFFFF0000);

        for (int pt = 0; pt < coords.length(); pt++) {
            JSONArray xy = coords.getJSONArray(pt);
            double lon = xy.getDouble(0);
            double lat = xy.getDouble(1);
            plo.add(new LatLng(lat, lon));
        }

        indoorShapeLines.add(gMap.addPolyline(plo));
        return 1;
    }

    //Chen :Render a MultiLineString by drawing each line and returning the total count.
    private int drawMultiLineString(@NonNull JSONArray lines) throws Exception {
        int count = 0;
        for (int li = 0; li < lines.length(); li++) {
            JSONArray line = lines.getJSONArray(li);
            count += drawLineString(line);
        }
        return count;
    }

    //Chen :Render Polygon rings as lines (each ring as a polyline) for indoor shape visualization.
    // Polygon coordinates: [ ring1, ring2(hole)... ], ring: [ [lon,lat], ... ]
    private int drawPolygonAsLines(@NonNull JSONArray polygonCoords) throws Exception {
        int count = 0;
        for (int r = 0; r < polygonCoords.length(); r++) {
            JSONArray ring = polygonCoords.getJSONArray(r);
            count += drawLineString(ring);
        }
        return count;
    }

    //Render a MultiPolygon by drawing each polygon’s rings as lines and summing the counts.
    // MultiPolygon coordinates: [ polygon1, polygon2... ], polygon: [ ring1, ring2... ]
    private int drawMultiPolygonAsLines(@NonNull JSONArray multiPoly) throws Exception {
        int count = 0;
        for (int p = 0; p < multiPoly.length(); p++) {
            JSONArray polygon = multiPoly.getJSONArray(p);
            count += drawPolygonAsLines(polygon);
        }
        return count;
    }

    //Chen :Sort floor keys (B2/B1/1/2/G/L, etc.) into a human-friendly order for UI switching.
    private void sortFloorKeys(@NonNull List<String> floors) {
        floors.sort((a, b) -> Integer.compare(floorOrder(a), floorOrder(b)));
    }

    //Chen :Map a floor key to a comparable order value (basements first, then above-ground, unknown last).
    private int floorOrder(String key) {
        if (key == null) return 999;
        key = key.trim().toUpperCase();

        if (key.startsWith("B")) {
            try { return -100 + Integer.parseInt(key.substring(1)); }
            catch (Exception ignored) { return -50; }
        }

        if (key.equals("LG")) return -1;
        if (key.equals("GF") || key.equals("G")) return 0;

        try { return Integer.parseInt(key); }
        catch (Exception ignored) { }

        return 500;
    }

    //Chen :Recenter the map to current location (or orientation marker) using animated or immediate camera move.
    private void recenterToCurrentLocation(boolean animate) {
        if (gMap == null) return;

        LatLng target = currentLocation;
        if (target == null && orientationMarker != null) {
            target = orientationMarker.getPosition();
        }
        if (target == null) return;

        if (animate) {
            gMap.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 19f));
        } else {
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(target, 19f));
        }
    }

    //Chen :Update the floor label chip text (e.g., “B1 / 1 / 2 / G”) in sync with currentFloorIdx/availableFloors.
    private void updateFloorLabelChip() {
        if (floorLabelChip == null) return;

        if (availableFloors != null
                && !availableFloors.isEmpty()
                && currentFloorIdx >= 0
                && currentFloorIdx < availableFloors.size()) {

            String floorKey = availableFloors.get(currentFloorIdx);
            floorLabelChip.setText("Floor: " + floorKey);
        } else {
            floorLabelChip.setText("Floor: -");
        }
    }

    //Chen :Request the date to positioning
    public void requestFloorplansNow(@NonNull LatLng location) {
        lastFloorplanRequestMs = 0L;
        hasReceivedFloorplan = false;
        requestFloorplansIfNeeded(location);
    }

    //Chen :Convert elevation relative to baseElevation into a floor index using floorHeightM (round to nearest floor).
    private int elevationToFloorIndex(float elevation, float baseElevation, float floorHeightM) {
        float delta = elevation - baseElevation;
        return (int) Math.floor(delta / floorHeightM + 0.5f);
    }
}
