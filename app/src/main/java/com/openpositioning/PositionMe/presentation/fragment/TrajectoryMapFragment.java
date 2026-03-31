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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
import java.util.Map;

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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;


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

    //CHEN 2 CONTROL FLOORCHANGE
    private boolean mapMatchingAllowsFloorChange = false;

    private Float autoFloorBaseElevation = null;
    private long lastAutoFloorSwitchMs = 0L;
    private Integer autoFloorBaseIdx = null;
    private static final long AUTO_FLOOR_INTERVAL_MS = 800;
    private static final long AUTO_FLOOR_DEBOUNCE_MS = 1500;

    // ===== 3.3 Data display: last N observations =====
    private static final int MAX_OBSERVATIONS = 10;

    private final List<Marker> gnssObservationMarkers = new ArrayList<>();
    private final List<Marker> wifiObservationMarkers = new ArrayList<>();
    private final List<Marker> pdrObservationMarkers  = new ArrayList<>();

    private LatLng lastGnssObservation = null;
    private LatLng lastWifiObservation = null;
    private LatLng lastPdrObservation  = null;

    private static final float OBSERVATION_MIN_DISTANCE_METERS = 0.5f;

    private SwitchMaterial observationSwitch;
    private View observationLegendLayout;

    private boolean observationsVisible = true;

    private BitmapDescriptor gnssObservationIcon;
    private BitmapDescriptor wifiObservationIcon;
    private BitmapDescriptor pdrObservationIcon;

    private static final int GNSS_OBS_COLOR = Color.parseColor("#2D9CDB");
    private static final int WIFI_OBS_COLOR = Color.parseColor("#27AE60");
    private static final int PDR_OBS_COLOR  = Color.parseColor("#9B51E0");

    private com.google.android.material.chip.Chip floorLabelChip;

    private boolean indoorRunning = false;
    // ===== 3.3 smooth display =====
    // currentLocation
    // displayedLocation TRUE LOCATE
    private LatLng displayedLocation = null;
    private float displayedOrientationDeg = 0f;

    private static final float DISPLAY_ALPHA_SLOW = 0.18f;
    private static final float DISPLAY_ALPHA_MEDIUM = 0.30f;
    private static final float DISPLAY_ALPHA_FAST = 0.50f;

    private static final float DISPLAY_MEDIUM_JUMP_METERS = 2.0f;
    private static final float DISPLAY_LARGE_JUMP_METERS = 6.0f;
    private static final float DISPLAY_SNAP_JUMP_METERS = 15.0f;

    private static final float DISPLAY_ORIENTATION_ALPHA = 0.25f;

    // ===== 3.3 fused trajectory update control =====
    private static final long FUSED_TRAJECTORY_UPDATE_INTERVAL_MS = 1000L;
    private static final float FUSED_TRAJECTORY_MOVE_THRESHOLD_METERS = 0.7f;

    private LatLng lastTrajectoryPoint = null;
    private long lastTrajectoryAppendMs = 0L;

    //CHEN 2 COLOR FILL
    private final List<Polygon> indoorShapePolygons = new ArrayList<>();

    private View indoorLegendLayout;
    private View legendWallColor;
    private View legendStairsColor;
    private View legendLiftColor;

    // ===== Floorplan request timing control =====
    private boolean hasReceivedFloorplan = false;

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
    //CHEN 2
    //HUNG
    // THE FIX: Upgraded to Thread-Safe Maps so the UI and Math engines don't collide
    private final Map<String, List<List<LatLng>>> wallSegmentsByFloor = new ConcurrentHashMap<>();
    private final Map<String, List<List<LatLng>>> stairsSegmentsByFloor = new ConcurrentHashMap<>();
    private final Map<String, List<List<LatLng>>> liftSegmentsByFloor = new ConcurrentHashMap<>();


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
        //OBS
        observationSwitch = view.findViewById(R.id.observationSwitch);
        observationLegendLayout = view.findViewById(R.id.observationLegendLayout);
        gnssObservationIcon = createObservationDot(GNSS_OBS_COLOR);
        wifiObservationIcon = createObservationDot(WIFI_OBS_COLOR);
        pdrObservationIcon  = createObservationDot(PDR_OBS_COLOR);

        if (observationSwitch != null) {
            observationSwitch.setChecked(true);
            observationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                setObservationVisibility(isChecked);
            });
        }

        setObservationVisibility(true);

        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);

        //CHEN 2
        indoorLegendLayout = view.findViewById(R.id.indoorLegendLayout);
        legendWallColor = view.findViewById(R.id.legendWallColor);
        legendStairsColor = view.findViewById(R.id.legendStairsColor);
        legendLiftColor = view.findViewById(R.id.legendLiftColor);

        if (legendWallColor != null) {
            legendWallColor.setBackgroundColor(getIndoorFillColor("wall"));
        }
        if (legendStairsColor != null) {
            legendStairsColor.setBackgroundColor(getIndoorFillColor("stairs"));
        }
        if (legendLiftColor != null) {
            legendLiftColor.setBackgroundColor(getIndoorFillColor("lift"));
        }
        //end
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
            forceRelocateToLatestPosition();
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
                autoFloorBaseIdx = null;
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
            autoFloorBaseIdx = currentFloorIdx;
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
                    Log.e("AUTO_FLOOR",
                            "elev=" + elev +
                                    " baseElev=" + autoFloorBaseElevation +
                                    " baseIdx=" + autoFloorBaseIdx +
                                    " curIdx=" + currentFloorIdx
                    );

                    if (autoFloorBaseElevation == null) autoFloorBaseElevation = elev;

                    int targetIdx = elevationToFloorIndexByBands(elev);
                    targetIdx = Math.max(0, Math.min(targetIdx, availableFloors.size() - 1));

                    long now = android.os.SystemClock.uptimeMillis();
                    if (targetIdx != currentFloorIdx
                            && mapMatchingAllowsFloorChange
                            && (now - lastAutoFloorSwitchMs) > AUTO_FLOOR_DEBOUNCE_MS) {
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
//        indoorMapManager = new IndoorMapManager(map);

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

        LatLng oldRawLocation = this.currentLocation;
        this.currentLocation = newLocation;

        if (serverCommunications == null) {
            Log.e("Floorplan", "serverCommunications is null");
            return;
        }

        int wifiCount = (sensorFusion != null && sensorFusion.getWifiList() != null)
                ? sensorFusion.getWifiList().size() : 0;
        Log.d("Floorplan", "sending floorplan request lat=" + currentLocation.latitude
                + " lon=" + currentLocation.longitude
                + " wifiCount=" + wifiCount);

        Log.e("Floorplan", "🚨 HIT FLOORPLAN CALL SITE");

        LatLng markerLocation = smoothDisplayLocation(displayedLocation, newLocation);

        float targetOrientation = normalizeAngle(orientation);
        float markerOrientation = (displayedLocation == null)
                ? targetOrientation
                : smoothDisplayOrientation(displayedOrientationDeg, targetOrientation, DISPLAY_ORIENTATION_ALPHA);

        displayedLocation = markerLocation;
        displayedOrientationDeg = markerOrientation;

        if (orientationMarker == null) {
            orientationMarker = gMap.addMarker(new MarkerOptions()
                    .position(markerLocation)
                    .flat(true)
                    .title("Current Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_24)))
            );

            if (orientationMarker != null) {
                orientationMarker.setRotation(markerOrientation);
            }

            if (pendingInitialRecenter) {
                gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(markerLocation, 19f));
                pendingInitialRecenter = false;
            }
        } else {
            orientationMarker.setPosition(markerLocation);
            orientationMarker.setRotation(markerOrientation);

            if (followMyLocation) {
                gMap.moveCamera(CameraUpdateFactory.newLatLng(markerLocation));
            }

            if (pendingInitialRecenter) {
                gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(markerLocation, 19f));
                pendingInitialRecenter = false;
            }
        }

        if (polyline != null) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            long nowMs = System.currentTimeMillis();

            if (points.isEmpty()) {
                points.add(newLocation);
                polyline.setPoints(points);
                lastTrajectoryPoint = newLocation;
                lastTrajectoryAppendMs = nowMs;
            } else if (oldRawLocation != null && shouldAppendFusedTrajectory(newLocation, nowMs)) {
                LatLng tail = points.get(points.size() - 1);
                if (!tail.equals(newLocation)) {
                    points.add(newLocation);
                    polyline.setPoints(points);
                }
            }
        }

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

                        boolean ok = renderNearbyFloorplans(response);
                        hasReceivedFloorplan = ok;

                        Log.d("Floorplan", ok
                                ? "Floorplan VALID -> switch to slow refresh (30s)"
                                : "Floorplan NOT valid -> keep fast retry (5s)");
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
        if (floorUpButton != null) {
            floorUpButton.setVisibility(visibility);
        }
        if (floorDownButton != null) {
            floorDownButton.setVisibility(visibility);
        }
        if (autoFloorSwitch != null) {
            autoFloorSwitch.setVisibility(visibility);
        }
        if (floorLabelChip != null) {
            floorLabelChip.setVisibility(visibility);
        }
        if (indoorLegendLayout != null) {
            indoorLegendLayout.setVisibility(visibility);
        }
    }
    private boolean isFarEnoughForObservation(@Nullable LatLng oldPoint, @NonNull LatLng newPoint) {
        if (oldPoint == null) return true;

        float[] results = new float[1];
        android.location.Location.distanceBetween(
                oldPoint.latitude, oldPoint.longitude,
                newPoint.latitude, newPoint.longitude,
                results
        );
        return results[0] >= OBSERVATION_MIN_DISTANCE_METERS;
    }

    private void trimObservationMarkers(@NonNull List<Marker> markers) {
        while (markers.size() > MAX_OBSERVATIONS) {
            Marker oldest = markers.remove(0);
            if (oldest != null) {
                oldest.remove();
            }
        }
    }

    private void addObservationMarker(@NonNull LatLng position,
                                      @NonNull BitmapDescriptor icon,
                                      @NonNull List<Marker> markerList,
                                      @Nullable String title) {
        if (gMap == null) return;

        Marker marker = gMap.addMarker(new MarkerOptions()
                .position(position)
                .title(title)
                .icon(icon)
                .anchor(0.5f, 0.5f)
                .visible(observationsVisible)
        );

        if (marker != null) {
            marker.setAlpha(0.95f);
            markerList.add(marker);
            trimObservationMarkers(markerList);
        }
    }

    public void addGnssObservation(@NonNull LatLng position) {
        if (!isFarEnoughForObservation(lastGnssObservation, position)) return;
        addObservationMarker(
                position,
                gnssObservationIcon,
                gnssObservationMarkers,
                "GNSS observation"
        );
        lastGnssObservation = position;
    }

    public void addWifiObservation(@NonNull LatLng position) {
        if (!isFarEnoughForObservation(lastWifiObservation, position)) return;
        addObservationMarker(
                position,
                wifiObservationIcon,
                wifiObservationMarkers,
                "WiFi observation"
        );
        lastWifiObservation = position;
    }

    public void addPdrObservation(@NonNull LatLng position) {
        if (!isFarEnoughForObservation(lastPdrObservation, position)) return;
        addObservationMarker(
                position,
                pdrObservationIcon,
                pdrObservationMarkers,
                "PDR observation"
        );
        lastPdrObservation = position;
    }

    private void clearObservationMarkers(@NonNull List<Marker> markers) {
        for (Marker marker : markers) {
            if (marker != null) {
                marker.remove();
            }
        }
        markers.clear();
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

        clearObservationMarkers(gnssObservationMarkers);
        clearObservationMarkers(wifiObservationMarkers);
        clearObservationMarkers(pdrObservationMarkers);

        lastGnssObservation = null;
        lastWifiObservation = null;
        lastPdrObservation = null;

        lastGnssLocation = null;
        currentLocation  = null;

        displayedLocation = null;
        displayedOrientationDeg = 0f;
        lastTrajectoryPoint = null;
        lastTrajectoryAppendMs = 0L;

        // Re-create empty polylines
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
                .strokeColor(0xFF000000)    // Red border
                .strokeWidth(2f)           // Border width
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

        LatLng fusedLocation = sensorFusion.getFusedEstimatedLatLng();
        LatLng gnssLocation  = sensorFusion.getCurrentGnssLatLng();
        LatLng wifiLocation  = sensorFusion.getCurrentWifiLatLng();
        LatLng pdrLocation   = sensorFusion.getCurrentPdrLatLng();

        // fused
        LatLng displayLocation = (fusedLocation != null) ? fusedLocation : gnssLocation;

        if (displayLocation == null) {
            Log.e("IndoorDebug", "No fused/GNSS location available yet");
            return;
        }

        float orientation = 0f;
        try {
            orientation = (float) Math.toDegrees(sensorFusion.passOrientation());
        } catch (Exception ignored) {}

        Log.e("IndoorDebug",
                "displayLocation=" + displayLocation.latitude + "," + displayLocation.longitude +
                        " fused=" + (fusedLocation != null) +
                        " gnss=" + (gnssLocation != null) +
                        " wifi=" + (wifiLocation != null) +
                        " pdr=" + (pdrLocation != null));

        updateUserLocation(displayLocation, orientation);

        if (gnssLocation != null) {
            updateGNSS(gnssLocation);
        }

        if (gnssLocation != null) {
            addGnssObservation(gnssLocation);
        }

        if (wifiLocation != null) {
            addWifiObservation(wifiLocation);
        }

        if (pdrLocation != null) {
            addPdrObservation(pdrLocation);
        }

        requestFloorplansIfNeeded(displayLocation);
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
    //CHEN 2
        if (sensorFusion != null) {
            sensorFusion.setMapConstraint((oldX, oldY, newX, newY) -> {
                LatLng start = sensorFusion.convertLocalMetersToLatLng(oldX, oldY);
                LatLng end = sensorFusion.convertLocalMetersToLatLng(newX, newY);
                return crossesWall(start, end);
            });
        }

    }

    //Chen :Parse floorplan results, clear old polygons, draw new venue outlines, and build clickable venue list.
    private boolean renderNearbyFloorplans(@NonNull org.json.JSONObject wrapper) {
        if (gMap == null) return false;

        clearNearbyVenuePolygons();

        try {
            org.json.JSONArray results = wrapper.optJSONArray("results");
            if (results == null || results.length() == 0) {
                Log.e("Floorplan", "results empty");
                return false;
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
            return nearbyVenuePolygons.size() > 0;

        } catch (Exception e) {
            Log.e("Floorplan", "renderNearbyFloorplans error: " + e.getMessage());
            return false;
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
                        .strokeWidth(2f)
                        .strokeColor(0xFF000000)
                        .fillColor(0xCCDDD6C8)
                        .zIndex(0f);

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

        for (Polygon p : indoorShapePolygons) {
            try { p.remove(); } catch (Exception ignored) {}
        }
        indoorShapePolygons.clear();
    }

    //Chen :For a given floorKey, parse that floor’s GeoJSON shapes and render them as polylines on the map.
    private void drawIndoorShapesForFloor(@NonNull NearbyVenue v, @NonNull String floorKey) {
        clearIndoorShapes();
        //CHEN
        wallSegmentsByFloor.remove(floorKey);
        stairsSegmentsByFloor.remove(floorKey);
        liftSegmentsByFloor.remove(floorKey);

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



                //CHEN 2
                JSONObject props = feature.optJSONObject("properties");
                String indoorType = props != null ? props.optString("indoor_type", "") : "";

                String type = geom.optString("type", "");
                Log.e("INDOOR", "feature[" + i + "] type=" + type + ", indoorType=" + indoorType);

                switch (type) {
                    case "MultiLineString":
                        added += drawMultiLineString(geom.getJSONArray("coordinates"), floorKey, indoorType);
                        break;

                    case "LineString":
                        added += drawLineString(geom.getJSONArray("coordinates"), floorKey, indoorType);
                        break;

                    case "Polygon":
                        added += drawPolygonAsLines(geom.getJSONArray("coordinates"), floorKey, indoorType);
                        break;

                    case "MultiPolygon":
                        added += drawMultiPolygonAsLines(geom.getJSONArray("coordinates"), floorKey, indoorType);
                        break;

                    default:
                        break;
                }
            }

            Log.e("INDOOR", "indoor drawn floor=" + floorKey + " linesAdded=" + added);

            //CHEN 2
            int wallCount = wallSegmentsByFloor.containsKey(floorKey) ? wallSegmentsByFloor.get(floorKey).size() : 0;
            int stairsCount = stairsSegmentsByFloor.containsKey(floorKey) ? stairsSegmentsByFloor.get(floorKey).size() : 0;
            int liftCount = liftSegmentsByFloor.containsKey(floorKey) ? liftSegmentsByFloor.get(floorKey).size() : 0;

            Log.e("MapMatching", "floor=" + floorKey
                    + " wall=" + wallCount
                    + " stairs=" + stairsCount
                    + " lift=" + liftCount);

        } catch (Exception e) {
            Log.e("INDOOR", "drawIndoorShapesForFloor error: " + e.getMessage());
        }
        logIndoorFeatureCounts(floorKey);

    }

    //CHEN 2
    private int drawLineString(@NonNull JSONArray coords,
                               @NonNull String floorKey,
                               @NonNull String indoorType) throws Exception {
        PolylineOptions plo = new PolylineOptions()
                .width(4f)
                .color(getIndoorStrokeColor(indoorType))
                .zIndex(getIndoorZIndex(indoorType) + 1f);

        List<LatLng> points = new ArrayList<>();

        for (int pt = 0; pt < coords.length(); pt++) {
            JSONArray xy = coords.getJSONArray(pt);
            double lon = xy.getDouble(0);
            double lat = xy.getDouble(1);
            LatLng point = new LatLng(lat, lon);
            plo.add(point);
            points.add(point);
        }

        indoorShapeLines.add(gMap.addPolyline(plo));
        storeIndoorFeaturePoints(floorKey, indoorType, points);
        return 1;
    }


    //CHEN 2
    private int drawMultiLineString(@NonNull JSONArray lines,
                                    @NonNull String floorKey,
                                    @NonNull String indoorType) throws Exception {
        int count = 0;
        for (int li = 0; li < lines.length(); li++) {
            JSONArray line = lines.getJSONArray(li);
            count += drawLineString(line, floorKey, indoorType);
        }
        return count;
    }

    //CHEN 2
    private int drawPolygonAsLines(@NonNull JSONArray polygonCoords,
                                   @NonNull String floorKey,
                                   @NonNull String indoorType) throws Exception {
        if (gMap == null || polygonCoords.length() == 0) return 0;

        JSONArray outerRing = polygonCoords.getJSONArray(0);
        List<LatLng> outerPoints = new ArrayList<>();

        for (int i = 0; i < outerRing.length(); i++) {
            JSONArray xy = outerRing.getJSONArray(i);
            double lon = xy.getDouble(0);
            double lat = xy.getDouble(1);
            outerPoints.add(new LatLng(lat, lon));
        }

        PolygonOptions polygonOptions = new PolygonOptions()
                .addAll(outerPoints)
                .strokeWidth(2f)
                .strokeColor(getIndoorStrokeColor(indoorType))
                .fillColor(getIndoorFillColor(indoorType))
                .zIndex(getIndoorZIndex(indoorType));

        // holes / inner rings
        for (int r = 1; r < polygonCoords.length(); r++) {
            JSONArray holeRing = polygonCoords.getJSONArray(r);
            List<LatLng> holePoints = new ArrayList<>();

            for (int i = 0; i < holeRing.length(); i++) {
                JSONArray xy = holeRing.getJSONArray(i);
                double lon = xy.getDouble(0);
                double lat = xy.getDouble(1);
                holePoints.add(new LatLng(lat, lon));
            }

            polygonOptions.addHole(holePoints);
        }

        indoorShapePolygons.add(gMap.addPolygon(polygonOptions));

        storeIndoorFeaturePoints(floorKey, indoorType, outerPoints);

        return 1;
    }

    //CHEN 2
    private int drawMultiPolygonAsLines(@NonNull JSONArray multiPoly,
                                        @NonNull String floorKey,
                                        @NonNull String indoorType) throws Exception {
        int count = 0;
        for (int p = 0; p < multiPoly.length(); p++) {
            JSONArray polygon = multiPoly.getJSONArray(p);
            count += drawPolygonAsLines(polygon, floorKey, indoorType);
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

    private int elevationToFloorIndexByBands(float elevation) {

        if (elevation < -3.5f) {
            return 0;   // B1
        } else if (elevation < 3.0f) {
            return 1;   // GF
        } else if (elevation < 10f) {
            return 2;   // 1F
        } else if (elevation < 14.5f) {
            return 3;   // 2F
        } else {
            return 4;   // 3F
        }
    }

    //CHEN 2
    private void addFeatureToFloorMap(
            Map<String, List<List<LatLng>>> targetMap,
            String floorKey,
            List<LatLng> points
    ) {
        if (floorKey == null || points == null || points.isEmpty()) return;

        targetMap.computeIfAbsent(floorKey, k -> new CopyOnWriteArrayList<>()).add(new CopyOnWriteArrayList<>(points));
    }

    private void storeIndoorFeaturePoints(String floorKey, String indoorType, List<LatLng> points) {
        if (indoorType == null || points == null || points.isEmpty()) return;

        switch (indoorType.toLowerCase()) {
            case "wall":
            case "walls":
                addFeatureToFloorMap(wallSegmentsByFloor, floorKey, points);
                break;
            case "stairs":
                addFeatureToFloorMap(stairsSegmentsByFloor, floorKey, points);
                break;
            case "lift":
                addFeatureToFloorMap(liftSegmentsByFloor, floorKey, points);
                break;
            default:
                break;
        }
    }

    private void logIndoorFeatureCounts(String floorKey) {
        int wallCount = wallSegmentsByFloor.containsKey(floorKey)
                ? wallSegmentsByFloor.get(floorKey).size() : 0;
        int stairsCount = stairsSegmentsByFloor.containsKey(floorKey)
                ? stairsSegmentsByFloor.get(floorKey).size() : 0;
        int liftCount = liftSegmentsByFloor.containsKey(floorKey)
                ? liftSegmentsByFloor.get(floorKey).size() : 0;

        android.util.Log.d("MapMatching", "Floor " + floorKey
                + " wall=" + wallCount
                + " stairs=" + stairsCount
                + " lift=" + liftCount);
    }
    public String getCurrentFloorKey() {
        if (availableFloors == null || availableFloors.isEmpty()) return null;
        if (currentFloorIdx < 0 || currentFloorIdx >= availableFloors.size()) return null;
        return availableFloors.get(currentFloorIdx);
    }

    public List<List<LatLng>> getWallSegmentsForCurrentFloor() {
        String floorKey = getCurrentFloorKey();
        if (floorKey == null) return new ArrayList<>();
        return wallSegmentsByFloor.getOrDefault(floorKey, new ArrayList<>());
    }

    //CHEN 2 WALL
    private android.graphics.PointF latLngToLocalMeters(LatLng ref, LatLng p) {
        double dLat = (p.latitude - ref.latitude) * 111320.0;
        double dLon = (p.longitude - ref.longitude) * 111320.0 * Math.cos(Math.toRadians(ref.latitude));
        return new android.graphics.PointF((float) dLon, (float) dLat);
    }

    private float cross(android.graphics.PointF a, android.graphics.PointF b, android.graphics.PointF c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    private boolean onSegment(android.graphics.PointF a, android.graphics.PointF b, android.graphics.PointF p) {
        return p.x <= Math.max(a.x, b.x) + 1e-6 &&
                p.x >= Math.min(a.x, b.x) - 1e-6 &&
                p.y <= Math.max(a.y, b.y) + 1e-6 &&
                p.y >= Math.min(a.y, b.y) - 1e-6;
    }

    private boolean segmentsIntersect(android.graphics.PointF a,
                                      android.graphics.PointF b,
                                      android.graphics.PointF c,
                                      android.graphics.PointF d) {
        float d1 = cross(a, b, c);
        float d2 = cross(a, b, d);
        float d3 = cross(c, d, a);
        float d4 = cross(c, d, b);

        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
                ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }

        if (Math.abs(d1) < 1e-6 && onSegment(a, b, c)) return true;
        if (Math.abs(d2) < 1e-6 && onSegment(a, b, d)) return true;
        if (Math.abs(d3) < 1e-6 && onSegment(c, d, a)) return true;
        if (Math.abs(d4) < 1e-6 && onSegment(c, d, b)) return true;

        return false;
    }

    public boolean crossesWall(LatLng start, LatLng end) {
        if (start == null || end == null) return false;

        List<List<LatLng>> walls = getWallSegmentsForCurrentFloor();
        if (walls == null || walls.isEmpty()) return false;

        android.graphics.PointF a = latLngToLocalMeters(start, start);
        android.graphics.PointF b = latLngToLocalMeters(start, end);

        for (List<LatLng> wall : walls) {
            if (wall == null || wall.size() < 2) continue;

            for (int i = 0; i < wall.size() - 1; i++) {
                LatLng w1 = wall.get(i);
                LatLng w2 = wall.get(i + 1);

                android.graphics.PointF c = latLngToLocalMeters(start, w1);
                android.graphics.PointF d = latLngToLocalMeters(start, w2);

                if (segmentsIntersect(a, b, c, d)) {
                    android.util.Log.d("MapMatching", "Wall crossing detected");
                    return true;
                }
            }
        }

        return false;
    }

    //matching
    private LatLng interpolateLatLng(LatLng start, LatLng end, double t) {
        double lat = start.latitude + (end.latitude - start.latitude) * t;
        double lon = start.longitude + (end.longitude - start.longitude) * t;
        return new LatLng(lat, lon);
    }

    public LatLng getLastValidPointBeforeWall(LatLng start, LatLng end) {
        if (start == null || end == null) return end;

        if (!crossesWall(start, end)) {
            return end;
        }

        LatLng best = start;
        int steps = 20;

        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            LatLng testPoint = interpolateLatLng(start, end, t);

            if (crossesWall(start, testPoint)) {
                break;
            } else {
                best = testPoint;
            }
        }

        android.util.Log.d("MapMatching", "Using last valid point before wall");
        return best;
    }
    //floor

    public List<List<LatLng>> getStairsSegmentsForCurrentFloor() {
        String floorKey = getCurrentFloorKey();
        if (floorKey == null) return new ArrayList<>();
        return stairsSegmentsByFloor.getOrDefault(floorKey, new ArrayList<>());
    }

    public List<List<LatLng>> getLiftSegmentsForCurrentFloor() {
        String floorKey = getCurrentFloorKey();
        if (floorKey == null) return new ArrayList<>();
        return liftSegmentsByFloor.getOrDefault(floorKey, new ArrayList<>());
    }

    private double distanceMeters(LatLng a, LatLng b) {
        float[] results = new float[1];
        android.location.Location.distanceBetween(
                a.latitude, a.longitude,
                b.latitude, b.longitude,
                results
        );
        return results[0];
    }

    private boolean isNearFeature(LatLng position, List<List<LatLng>> featureSegments, double thresholdMeters) {
        if (position == null || featureSegments == null || featureSegments.isEmpty()) return false;

        for (List<LatLng> segment : featureSegments) {
            if (segment == null) continue;

            for (LatLng point : segment) {
                if (point == null) continue;

                if (distanceMeters(position, point) <= thresholdMeters) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean isNearStairs(LatLng position, double thresholdMeters) {
        return isNearFeature(position, getStairsSegmentsForCurrentFloor(), thresholdMeters);
    }

    public boolean isNearLift(LatLng position, double thresholdMeters) {
        return isNearFeature(position, getLiftSegmentsForCurrentFloor(), thresholdMeters);
    }

    //CHEN  CONTROL FLOOR CHANGE
    public void setMapMatchingAllowsFloorChange(boolean allow) {
        this.mapMatchingAllowsFloorChange = allow;
    }

    //COLOR FILL
    private int getIndoorFillColor(String indoorType) {
        if (indoorType == null) return 0xFF8A7F70;

        switch (indoorType.toLowerCase()) {
            case "wall":
            case "walls":
                return 0xFF6A3D9A;
            case "stairs":
                return 0xFFB56A1E;
            case "lift":
                return 0xFF2F5FA8;
            default:
                return 0xCCE8DCCB;
        }
    }
    private int getIndoorStrokeColor(String indoorType) {
        if (indoorType == null) return 0xFF6A3D9A;

        switch (indoorType.toLowerCase()) {
            case "wall":
            case "walls":
                return 0xFF6A3D9A;
            case "stairs":
                return 0xFFB56A1E;
            case "lift":
                return 0xFF2F5FA8;
            default:
                return 0xFF6A3D9A;
        }
    }

    private float getIndoorZIndex(String indoorType) {
        if (indoorType == null) return 1f;

        switch (indoorType.toLowerCase()) {
            case "wall":
            case "walls":
                return 3f;
            case "stairs":
                return 4f;
            case "lift":
                return 5f;
            default:
                return 1f;
        }
    }

    private void setObservationVisibility(boolean visible) {
        observationsVisible = visible;

        setMarkerListVisibility(gnssObservationMarkers, visible);
        setMarkerListVisibility(wifiObservationMarkers, visible);
        setMarkerListVisibility(pdrObservationMarkers, visible);

        if (observationLegendLayout != null) {
            observationLegendLayout.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void setMarkerListVisibility(@NonNull List<Marker> markers, boolean visible) {
        for (Marker marker : markers) {
            if (marker != null) {
                marker.setVisible(visible);
            }
        }
    }

    private BitmapDescriptor createObservationDot(int color) {
        int sizePx = dpToPx(10);
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(Color.WHITE);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, sizePx * 0.12f));

        float cx = sizePx / 2f;
        float cy = sizePx / 2f;
        float radius = sizePx * 0.34f;

        canvas.drawCircle(cx, cy, radius, fill);
        canvas.drawCircle(cx, cy, radius, stroke);

        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void forceRelocateToLatestPosition() {
        if (sensorFusion == null) return;

        LatLng latest = sensorFusion.getFusedEstimatedLatLng();

        if (latest == null) {
            latest = sensorFusion.getCurrentWifiLatLng();
        }
        if (latest == null) {
            latest = sensorFusion.getCurrentGnssLatLng();
        }

        if (latest == null) {
            Toast.makeText(requireContext(), "Position not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        sensorFusion.forceRelocateToLatLng(latest);

        currentLocation = latest;
        displayedLocation = latest;
        displayedOrientationDeg = normalizeAngle((float) Math.toDegrees(sensorFusion.passOrientation()));
        lastTrajectoryPoint = latest;
        lastTrajectoryAppendMs = System.currentTimeMillis();

        if (orientationMarker == null && gMap != null) {
            orientationMarker = gMap.addMarker(new MarkerOptions()
                    .position(latest)
                    .flat(true)
                    .title("Current Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_24)))
            );
            if (orientationMarker != null) {
                orientationMarker.setRotation(displayedOrientationDeg);
            }
        } else if (orientationMarker != null) {
            orientationMarker.setPosition(latest);
            orientationMarker.setRotation(displayedOrientationDeg);
        }

        Toast.makeText(requireContext(), "Position force updated", Toast.LENGTH_SHORT).show();
    }


    //3.3 smooth
    private LatLng smoothDisplayLocation(@Nullable LatLng previous, @NonNull LatLng target) {
        if (previous == null) return target;

        double jumpMeters = distanceMeters(previous, target);

        if (jumpMeters >= DISPLAY_SNAP_JUMP_METERS) {
            return target;
        }

        float alpha = DISPLAY_ALPHA_SLOW;
        if (jumpMeters >= DISPLAY_LARGE_JUMP_METERS) {
            alpha = DISPLAY_ALPHA_FAST;
        } else if (jumpMeters >= DISPLAY_MEDIUM_JUMP_METERS) {
            alpha = DISPLAY_ALPHA_MEDIUM;
        }

        double lat = previous.latitude + (target.latitude - previous.latitude) * alpha;
        double lon = previous.longitude + (target.longitude - previous.longitude) * alpha;
        return new LatLng(lat, lon);
    }

    private float smoothDisplayOrientation(float previousDeg, float targetDeg, float alpha) {
        float delta = ((targetDeg - previousDeg + 540f) % 360f) - 180f;
        return normalizeAngle(previousDeg + alpha * delta);
    }

    private float normalizeAngle(float angleDeg) {
        float normalized = angleDeg % 360f;
        if (normalized < 0f) normalized += 360f;
        return normalized;
    }

    private boolean shouldAppendFusedTrajectory(@NonNull LatLng newPoint, long nowMs) {
        if (lastTrajectoryPoint == null) {
            lastTrajectoryPoint = newPoint;
            lastTrajectoryAppendMs = nowMs;
            return true;
        }

        double movedMeters = distanceMeters(lastTrajectoryPoint, newPoint);
        boolean timeReached = (nowMs - lastTrajectoryAppendMs) >= FUSED_TRAJECTORY_UPDATE_INTERVAL_MS;
        boolean movementReached = movedMeters >= FUSED_TRAJECTORY_MOVE_THRESHOLD_METERS;

        if (timeReached || movementReached) {
            lastTrajectoryPoint = newPoint;
            lastTrajectoryAppendMs = nowMs;
            return true;
        }

        return false;
    }

}
