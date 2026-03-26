package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.LinearLayout;

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
import java.util.List;
import java.util.Queue;
import java.util.LinkedList;
import com.openpositioning.PositionMe.data.remote.FloorPlanData;
import com.openpositioning.PositionMe.utils.VenueMapper;

import okhttp3.OkHttpClient;


import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.MarkerOptions;

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

//request nearby indoor maps
    //draw venue polygons
    //handle venue click

public class TrajectoryMapFragment extends Fragment {

    private static final boolean USE_MOCK_FLOORPLAN = true;
    private GoogleMap gMap; // Google Maps instance
    private LatLng currentLocation; // Stores the user's current location
    private Marker orientationMarker; // Marker representing user's heading
    private Marker gnssMarker; // GNSS position marker

    /** Raw (unfiltered) PDR trajectory — drawn in red. */
    private Polyline polyline; // Polyline representing user's movement path
    private boolean isRed = true; // Tracks whether the polyline color is red
    private boolean isGnssOn = false; // Tracks if GNSS tracking is enabled

    private Polyline gnssPolyline; // Polyline for GNSS path
    private LatLng lastGnssLocation = null; // Stores the last GNSS location

    private LatLng pendingCameraPosition = null; // Stores pending camera movement
    private boolean hasPendingCameraMove = false; // Tracks if camera needs to move

    private IndoorMapManager indoorMapManager; // Manages indoor mapping
    private SensorFusion sensorFusion;
    private TextView floorLabel;

    //    Added
//    /** Raw (unfiltered) PDR trajectory — drawn in red. */
//    private Polyline rawPolyline;

    /** Smoothed trajectory — drawn in purple, visible only when smoothing is ON. */
    private Polyline smoothedPolyline;

    private static final String TAG = "TrajectoryMapFragment";

    /**
     * Minimum distance in metres a position must move before a new dot is placed.
     * Prevents dots stacking on each other when the user is stationary.
     */
    private static final float MIN_DOT_SPACING_METRES = 2.0f;

    /** Number of recent observations kept per source for colour-coded dots. */
    private static final int MAX_OBSERVATION_MARKERS = 10;


    /** Default rolling-window size for observation dots. */
    private static final int DEFAULT_MAX_OBSERVATIONS = 5;

    // Dot fill colours
    private static final int COLOR_GNSS = Color.parseColor("#2196F3"); // blue
    private static final int COLOR_WIFI = Color.parseColor("#FF9800"); // amber
    private static final int COLOR_PDR  = Color.parseColor("#4CAF50"); // green
    /**
     * Low-pass filter alpha. Range [0,1].
     * Lower = smoother but more lag; higher = less smoothing but more responsive.
     */
    private static final double LOW_PASS_ALPHA = 0.25;

    // -------------------------------------------------------------------------
    // Smoothing state
    // -------------------------------------------------------------------------

    /** Whether the smoothed (purple) polyline is currently shown instead of raw. */
    private boolean isSmoothingEnabled = false;

    /** Running smoothed position for the low-pass filter. */
    private LatLng smoothedPosition = null;

    /** Full list of smoothed positions (mirrors rawPolyline but filtered). */
    private final List<LatLng> smoothedPoints = new ArrayList<>();

//    // -------------------------------------------------------------------------
//    // Colour-coded observation markers (last N per source)
//    // -------------------------------------------------------------------------
//
//    /** Circular buffer of recent GNSS observation markers (shown in blue). */
//    private final Queue<Marker> gnssObservationMarkers = new LinkedList<>();
//
//    /** Circular buffer of recent WiFi observation markers (shown in orange/amber). */
//    private final Queue<Marker> wifiObservationMarkers = new LinkedList<>();
//
//    /** Circular buffer of recent PDR observation markers (shown in green). */
//    private final Queue<Marker> pdrObservationMarkers = new LinkedList<>();

    // -------------------------------------------------------------------------
    // Observation dot state
    // -------------------------------------------------------------------------

    /** Current rolling-window size — updated when user changes the N input. */
//    private int maxObservations = DEFAULT_MAX_OBSERVATIONS;
    private final int maxObservations = 5;

    // Per-source visibility flags (all enabled by default)
    private boolean showGnssDots = true;
    private boolean showWifiDots = true;
    private boolean showPdrDots  = true;

    // Per-source rolling queues of on-map markers
    private final LinkedList<Marker> gnssObservationMarkers = new LinkedList<>();
    private final LinkedList<Marker> wifiObservationMarkers = new LinkedList<>();
    private final LinkedList<Marker> pdrObservationMarkers  = new LinkedList<>();

    /**
     * Position where the most recent dot was placed for each source.
     * Used by {@link #hasMoved} to enforce the minimum spacing threshold.
     */
    private LatLng lastGnssDotPos = null;
    private LatLng lastWifiDotPos = null;
    private LatLng lastPdrDotPos  = null;

    /** If true, the arrow marker follows the fused (particle filter) position.
     *  If false, it follows the raw PDR position.
     *  Controlled by the fusedPdrSwitch toggle. */
//    private boolean useFusedPosition = true;

    /** If true, the raw PDR trajectory (red line) is visible.
     *  Controlled by showPdrPathSwitch. Arrow marker always follows fused position. */
    private boolean showPdrPath = true;

    /** LPF-smoothed fused trajectory — teal, visible only when smoothing toggle is ON. */
    private Polyline lpfPolyline;

    /** Full list of LPF-smoothed fused positions. */
    private final List<LatLng> lpfPoints = new ArrayList<>();

    /** Semi-transparent uncertainty circle around the fused position marker.
     *  Radius = getPositionUncertainty(). Colour reflects confidence level. */
    private Circle uncertaintyCircle;
    /** Particle cloud markers — faint grey dots showing filter spread. */
    private final List<Circle> particleMarkers = new ArrayList<>();

    /** Throttle particle cloud redraws to every 2 seconds. */
    private long lastParticleRedrawMs = 0;

    /** Whether particle cloud overlay is enabled — toggled by UI switch. */
    private boolean showParticleCloud = false;

    private LinearLayout controlCardContent;
    private TextView controlCardCollapseIcon;
    private boolean isControlCardExpanded = false;

    /** Most recent device heading in degrees, from passOrientation(). */
    private float lastOrientation = 0f;


    private float lastElevation = Float.NaN; //To compute height change between old and current position




    // UI
    private Spinner switchMapSpinner;

    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;

    //    Added
    private SwitchMaterial smoothingSwitch;

    private SwitchMaterial gnssDotSwitch;
    private SwitchMaterial wifiDotSwitch;
    private SwitchMaterial pdrDotSwitch;

//    Added
//    private SwitchMaterial fusedPdrSwitch;
private SwitchMaterial showPdrPathSwitch;
    // ── Fused trajectory display ──────────────────────────────────────────────

    /** Handler that drives the 1-second periodic polyline redraw. */
    private final android.os.Handler trajectoryUpdateHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** True while the fragment is visible and updates should fire. */
    private boolean isTrajectoryUpdateRunning = false;

    /**
     * Snapshot of smoothedPoints last drawn onto the polyline.
     * Compared against the live list so we only redraw when there is
     * actually something new to show.
     */
    private int lastDrawnPointCount = 0;

    /** 1-second runnable — redraws the fused polyline then reschedules itself. */
    private final Runnable trajectoryUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            redrawFusedTrajectory();
            if (isTrajectoryUpdateRunning) {
                trajectoryUpdateHandler.postDelayed(this, 1000);
            }
        }
    };

    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;
    private Polygon buildingPolygon;

    private List <IndoorMapManager.IndoorVenue> selectedVenue;
//    private IndoorMapManager.IndoorFloor selectedFloor;
    private FloorPlanData floorplanRemote;
    private long lastVenueQueryMs = 0;
    private LatLng lastVenueQueryLoc = null;

    private final Object macLock = new Object();
    private List<String> observedMacs = new ArrayList<>();

    // cache venues if they arrive before map/manager is ready (optional)
    private List<IndoorMapManager.IndoorVenue> lastFetchedVenues = null;

    private SwitchMaterial particleCloudSwitch;

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
        controlCardContent = view.findViewById(R.id.controlCardContent);
        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch      = view.findViewById(R.id.gnssSwitch);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        smoothingSwitch   = view.findViewById(R.id.smoothingSwitch);
//        fusedPdrSwitch = view.findViewById(R.id.fusedPdrSwitch);
        showPdrPathSwitch = view.findViewById(R.id.fusedPdrSwitch);
        particleCloudSwitch = view.findViewById(R.id.particleCloudSwitch);
        gnssDotSwitch     = view.findViewById(R.id.gnssDotSwitch);
        wifiDotSwitch     = view.findViewById(R.id.wifiDotSwitch);
        pdrDotSwitch      = view.findViewById(R.id.pdrDotSwitch);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorUpButton.setOnClickListener(v -> indoorMapManager.increaseFloor());
        floorDownButton = view.findViewById(R.id.floorDownButton);
        floorDownButton.setOnClickListener(v -> indoorMapManager.decreaseFloor());
        switchColorButton = view.findViewById(R.id.lineColorButton);
        floorLabel = view.findViewById(R.id.floorLabel);
        floorLabel.setText("Floor: -");


        // Setup floor up/down UI hidden initially until we know there's an indoor map
//        setFloorControlsVisibility(View.GONE);

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
                    floorplanRemote = new FloorPlanData(new OkHttpClient());


                    // If we had a pending camera move, apply it now
                    if (hasPendingCameraMove && pendingCameraPosition != null) {
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
                        hasPendingCameraMove = false;
                        pendingCameraPosition = null;
                    }

//                    drawBuildingPolygon();
                    indoorMapManager = new IndoorMapManager(gMap);
                    sensorFusion = SensorFusion.getInstance();
                    sensorFusion.setIndoorMapManager(indoorMapManager);
                    // 1) Handle user clicking a venue outline polygon
                    gMap.setOnPolygonClickListener(polygon -> {
                        IndoorMapManager.IndoorVenue v = indoorMapManager.getVenueForPolygon(polygon);

                        if (v != null) {
                            Log.d("IndoorDebug", "Clicked venue=" + v.name);

                            Log.d("IndoorDebug", "mapShapes length=" +
                                    (v.rawMapShapes == null ? "null" : v.rawMapShapes.length()));

                            Log.d("IndoorDebug", "mapShapes preview=" +
                                    (v.rawMapShapes == null ? "null" :
                                            v.rawMapShapes.substring(0,
                                                    Math.min(400, v.rawMapShapes.length()))));

                            indoorMapManager.selectVenue(v);

                            if(getActivity() instanceof VenueSelectionListener) {
                                ((VenueSelectionListener) getActivity()).onVenueSelected(
                                        v.venueId != null ? v.venueId : v.name
                                );
                            }
                            setFloorControlsVisibility(View.VISIBLE);

                            String fk = indoorMapManager.getCurrentFloorKey();
                            if (floorLabel != null) floorLabel.setText("Floor: " + (fk == null ? "-" : fk));

                        }
                    });

                    Log.d("TrajectoryMapFragment", "onMapReady: Map is ready!");


                }
            });
        }

        // Map type spinner setup
        initMapTypeSpinner();

        controlCardCollapseIcon = view.findViewById(R.id.controlCardCollapseIcon);
        view.findViewById(R.id.controlCardHeader).setOnClickListener(v -> {
            isControlCardExpanded = !isControlCardExpanded;
            controlCardContent.setVisibility(
                    isControlCardExpanded ? View.VISIBLE : View.GONE);
            controlCardCollapseIcon.setText(
                    isControlCardExpanded ? "▼" : "▶");
        });

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
        });

        // Smoothing toggle
        smoothingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isSmoothingEnabled = isChecked;
            updatePolylineVisibility();
            Log.d(TAG, "Smoothing toggled: " + isChecked);
        });

//        fusedPdrSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
//            useFusedPosition = isChecked;
//            Log.d(TAG, "Position mode: " + (isChecked ? "Fused" : "PDR"));
//        });

        showPdrPathSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            showPdrPath = isChecked;
            // Immediately show or hide the red PDR polyline
            if (polyline != null) {
                polyline.setVisible(showPdrPath);
            }
            Log.d(TAG, "PDR path visible: " + isChecked);
        });

        particleCloudSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            showParticleCloud = isChecked;
            // Hide all existing particles immediately when toggled off
            if (!isChecked) {
                clearParticleCloud();
            }
            Log.d(TAG, "Particle cloud: " + (isChecked ? "ON" : "OFF"));
        });

        // Dot visibility toggles — also immediately show/hide existing dots
        gnssDotSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            showGnssDots = isChecked;
            setQueueVisibility(gnssObservationMarkers, isChecked);
        });

        wifiDotSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            showWifiDots = isChecked;
            setQueueVisibility(wifiObservationMarkers, isChecked);
        });

        pdrDotSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            showPdrDots = isChecked;
            setQueueVisibility(pdrObservationMarkers, isChecked);
        });

        floorUpButton.setOnClickListener(v -> {
            // If user manually changes floor, turn off auto floor
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
                String fk = indoorMapManager.getCurrentFloorKey();
                if (floorLabel != null) floorLabel.setText("Floor: " + (fk == null ? "-" : fk));

            }

        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
                String fk = indoorMapManager.getCurrentFloorKey();
                if (floorLabel != null) floorLabel.setText("Floor: " + (fk == null ? "-" : fk));

            }

        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // Start the 1-second fused trajectory update loop
        isTrajectoryUpdateRunning = true;
        trajectoryUpdateHandler.postDelayed(trajectoryUpdateRunnable, 1000);
    }

    @Override
    public void onPause() {
        super.onPause();
        // Stop the loop when fragment is not visible to avoid wasted redraws
        isTrajectoryUpdateRunning = false;
        trajectoryUpdateHandler.removeCallbacks(trajectoryUpdateRunnable);
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

    // -------------------------------------------------------------------------
    // Test point markers
    // -------------------------------------------------------------------------

    /**
     * Creates a numbered red circle marker bitmap for a test point.
     *
     * @param number the test point index to display inside the circle.
     * @return a {@link Bitmap} with a numbered red circle.
     */

    private Bitmap createNumberedMarkerBitmap(int number) {
        int size = 100; // marker size in pixels

        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint circlePaint = new Paint();
        circlePaint.setColor(Color.RED);
        circlePaint.setAntiAlias(true);

        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(40f);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // Draw circle
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, circlePaint);

        // Draw number in center
        Rect bounds = new Rect();
        String text = String.valueOf(number);
        textPaint.getTextBounds(text, 0, text.length(), bounds);

        float x = size / 2f;
        float y = size / 2f - bounds.centerY();

        canvas.drawText(text, x, y, textPaint);

        return bitmap;
    }

    public void addTestPointMarker(LatLng latLng, int number) {
        Bitmap numberedMarker = createNumberedMarkerBitmap(number);

        MarkerOptions markerOptions = new MarkerOptions()
                .position(latLng)
                .icon(BitmapDescriptorFactory.fromBitmap(numberedMarker))
                .anchor(0.5f, 0.5f)   // center anchor
                .title("Test Point " + number);

        gMap.addMarker(markerOptions);
    }


    private void initMapSettings(GoogleMap map) {
        // Basic map settings
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        // Initialize indoor manager
        indoorMapManager = new IndoorMapManager(map);

        // Raw PDR trajectory — red, always present
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

        //        Added
        // Smoothed trajectory — purple, only visible when smoothing is ON
        // Raw fused trajectory — purple, always visible
        smoothedPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.parseColor("#8B00FF"))
                .width(6f)
                .visible(true));

        // LPF-smoothed fused trajectory — teal, only visible when smoothing toggle is ON
        lpfPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.parseColor("#00BCD4"))
                .width(7f)
                .visible(false));

        // Uncertainty circle — invisible until first fused position arrives
        uncertaintyCircle = map.addCircle(new CircleOptions()
                .center(new LatLng(0, 0))
                .radius(0)
                .fillColor(Color.argb(50, 0, 255, 0))   // semi-transparent green initially
                .strokeColor(Color.argb(100, 0, 255, 0))
                .strokeWidth(2f)
                .visible(false));
    }

    private void maybeRequestNearbyVenues(@NonNull LatLng loc) {
        Log.d("MapDebug", "maybeRequestNearbyVenues called, timeSinceLast=" + (System.currentTimeMillis() - lastVenueQueryMs) + " loc=" + loc);
        if (indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) return;

        long now = System.currentTimeMillis();
        if (now - lastVenueQueryMs < 15000) return; // 15s throttle

        if (lastVenueQueryLoc != null && distanceMeters(loc, lastVenueQueryLoc) < 25) return; // 25m threshold

        lastVenueQueryMs = now;
        lastVenueQueryLoc = loc;


        List<String> macs = getObservedMacsOrEmpty();
        Log.d("TrajectoryMapFragment", "maybeRequestNearbyVenues instance=" + System.identityHashCode(this)
                + " observedMacs=" + macs.size());

        if (macs.isEmpty()) {
            Log.d("TrajectoryMapFragment", "Skipping floorplan request: no MACs yet");
            return;
        }

        Log.d("TrajectoryMapFragment", "Floorplan request @ " +
                loc.latitude + "," + loc.longitude + " macs=" + macs.size());
        if (floorplanRemote == null) {
            Log.w("TrajectoryMapFragment", "floorplanRemote not initialized");
            return;
        }

        floorplanRemote.requestNearbyVenues(
                loc.latitude, loc.longitude, macs,
                new FloorPlanData.VenueCallback() {
                    @Override public void onSuccess(List<FloorPlanData.VenueDto> dtos) {
                        requireActivity().runOnUiThread(() -> {
                            Log.d("TrajectoryMapFragment", "Floorplan response venues=" + dtos.size());

                            List<IndoorMapManager.IndoorVenue> venues = VenueMapper.toIndoorVenues(dtos);
                            Log.d("TrajectoryMapFragment", "Mapped venues=" + venues.size());

                            if (indoorMapManager != null) {
                                indoorMapManager.showVenueOutlines(venues);
                            }
                        });
                    }



                    @Override public void onError(Exception e) {
                         Log.d("TrajectoryMapFragment", "floorplan request failed", e);
                    }
                }
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
        Log.d("MapDebug", "updateUserLocation called, gMap=" + gMap + " sensorFusion=" + sensorFusion);
        if (gMap == null) return;
        if (sensorFusion == null) sensorFusion = SensorFusion.getInstance();


        // Store orientation for use by the fused marker
        lastOrientation = orientation;

        // Keep track of current location
        LatLng oldLocation = this.currentLocation;
        LatLng correctedLocation = newLocation;
        float heightChange = 0f;
        if (sensorFusion != null) {
            float currentElevation = sensorFusion.getElevation();



            if (!Float.isNaN(lastElevation)) {
                heightChange = currentElevation - lastElevation;
            }


            lastElevation = currentElevation;
        }

        if (oldLocation != null && indoorMapManager != null) {
//            correctedLocation = indoorMapManager.indoorLocationCorrection(
//                    oldLocation,
//                    newLocation,
//                    heightChange
//            );

            indoorMapManager.acceptFloorChange(
                    correctedLocation,
                    oldLocation,
                    heightChange
            );
        }

        this.currentLocation = correctedLocation;
        newLocation = correctedLocation;
        Log.d("IndoorTest", "oldLocation = " + oldLocation);
        Log.d("IndoorTest", "newLocation = " + newLocation);
        Log.d("IndoorTest", "heightChange = " + heightChange);

//        if indoor map is active and current venue is known:
//        send oldLocation, newLocation, current floor, and maybe barometer info to IndoorMapManager
//        get back corrected position/floor
//        then update marker/polyline using that corrected result


        // If no marker, create it
//        if (orientationMarker == null) {
//            orientationMarker = gMap.addMarker(new MarkerOptions()
//                    .position(newLocation)
//                    .flat(true)
//                    .title("Current Position")
//                    .icon(BitmapDescriptorFactory.fromBitmap(
//                            UtilFunctions.getBitmapFromVector(requireContext(),
//                                    R.drawable.ic_baseline_navigation_24)))
//            );
//            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
//        } else {
//            // Update marker position + orientation
//            orientationMarker.setPosition(newLocation);
//            orientationMarker.setRotation(orientation);
//            // Move camera a bit
//            gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
//        }

        // Extend polyline if movement occurred
        if (oldLocation != null && !oldLocation.equals(newLocation) && polyline != null) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            points.add(newLocation);
            polyline.setPoints(points);
        }

        //        Added
        // --- Compute and extend smoothed polyline ---
//        LatLng filtered = applyLowPassFilter(newLocation);
//        if (oldLocation != null && !oldLocation.equals(newLocation)) {
//            smoothedPoints.add(filtered);
//            if (smoothedPolyline != null) {
//                smoothedPolyline.setPoints(new ArrayList<>(smoothedPoints));
//            }
//        }

//        changed to-
        // Extend smoothed polyline and trigger immediate fused trajectory redraw
//        LatLng filtered = applyLowPassFilter(newLocation);
//        if (oldLocation != null && !oldLocation.equals(newLocation)) {
//            smoothedPoints.add(filtered);
//            // Movement detected — redraw immediately rather than waiting for the 1s timer
//            redrawFusedTrajectory();
//        }


        // Green PDR dot — only when moved enough
//        if (showPdrDots && hasMoved(newLocation, lastPdrDotPos)) {
//            addObservationMarker(newLocation, COLOR_PDR, pdrObservationMarkers);
//            lastPdrDotPos = newLocation;
//        }


        // Update indoor map overlay
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(newLocation);
            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
        }

        // call api
        if (floorplanRemote != null) {
            maybeRequestNearbyVenues(newLocation);
        }
    }

    /**
     * Adds a green PDR observation dot at the raw PDR-derived position.
     * Uses getLastPdrLatLon() from SensorFusion — called from RecordingFragment.
     * Only places a dot if the PDR dot toggle is ON and the position has moved
     * at least MIN_DOT_SPACING_METRES from the last PDR dot.
     *
     * @param pdrLocation raw PDR position converted to WGS84 lat/lon.
     */
    public void updatePdrPosition(@NonNull LatLng pdrLocation) {
        if (gMap == null) return;
        if (showPdrDots && hasMoved(pdrLocation, lastPdrDotPos)) {
            addObservationMarker(pdrLocation, COLOR_PDR, pdrObservationMarkers);
            lastPdrDotPos = pdrLocation;
        }
    }

    /**
     * Updates the arrow marker and both fused trajectory polylines.
     *
     * - Purple line: raw particle filter output, always visible
     * - Teal line: LPF applied on top of fused output, toggle-controlled
     *
     * @param fusedLocation best position estimate from the particle filter.
     */
//    public void updateFusedPosition(@NonNull LatLng fusedLocation) {
//        if (gMap == null) return;
//
//        // Raw fused path — purple, always grows
//        smoothedPoints.add(fusedLocation);
//        redrawFusedTrajectory();
//
//        // Update uncertainty circle around the fused marker
//        updateUncertaintyCircle(fusedLocation);
//
//        // Redraw particle cloud overlay (throttled to every 2s)
//        redrawParticleCloud();
//
//        // LPF-smoothed fused path — teal, grows but only visible when toggle is ON
//        LatLng lpfFiltered = applyLowPassFilter(fusedLocation);
//        lpfPoints.add(lpfFiltered);
//        if (lpfPolyline != null) {
//            lpfPolyline.setPoints(new ArrayList<>(lpfPoints));
//        }
//
//        // Move arrow marker if fused mode is selected
//        if (!useFusedPosition) return;
//
//        if (orientationMarker == null) {
//            orientationMarker = gMap.addMarker(new MarkerOptions()
//                    .position(fusedLocation)
//                    .flat(true)
//                    .title("Current Position")
//                    .icon(BitmapDescriptorFactory.fromBitmap(
//                            UtilFunctions.getBitmapFromVector(requireContext(),
//                                    R.drawable.ic_baseline_navigation_24))));
//            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(fusedLocation, 19f));
//        } else {
//            orientationMarker.setPosition(fusedLocation);
//            gMap.moveCamera(CameraUpdateFactory.newLatLng(fusedLocation));
//        }
//    }

    /**
     * Updates the arrow marker using the particle filter's fused position.
     * The arrow ALWAYS follows the fused position — there is no toggle for this.
     *
     * Also grows the purple fused trajectory polyline and the teal LPF polyline,
     * and updates the uncertainty circle.
     *
     * @param fusedLocation best position estimate from the particle filter.
     */
    public void updateFusedPosition(@NonNull LatLng fusedLocation) {
        if (gMap == null) return;

        // Purple fused path — always grows
        smoothedPoints.add(fusedLocation);
        redrawFusedTrajectory();

        // Teal LPF path — grows but only visible when smoothing toggle is ON
        LatLng lpfFiltered = applyLowPassFilter(fusedLocation);
        lpfPoints.add(lpfFiltered);
        if (lpfPolyline != null) {
            lpfPolyline.setPoints(new ArrayList<>(lpfPoints));
        }

        // Uncertainty circle — shrinks/grows with filter confidence
        updateUncertaintyCircle(fusedLocation);

        // Particle cloud overlay — throttled to every 2 seconds
        redrawParticleCloud();

        // Arrow marker always follows fused position
        if (orientationMarker == null) {
            orientationMarker = gMap.addMarker(new MarkerOptions()
                    .position(fusedLocation)
                    .flat(true)
                    .title("Current Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_24))));
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(fusedLocation, 19f));
        } else {

            orientationMarker.setPosition(fusedLocation);
//            Added
            orientationMarker.setRotation(lastOrientation);
            gMap.moveCamera(CameraUpdateFactory.newLatLng(fusedLocation));
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
//    public void updateGNSS(@NonNull LatLng gnssLocation) {
//        if (gMap == null) return;
//        if (!isGnssOn) return;
//
//        if (gnssMarker == null) {
//            // Create the GNSS marker for the first time
//            gnssMarker = gMap.addMarker(new MarkerOptions()
//                    .position(gnssLocation)
//                    .title("GNSS Position")
//                    .icon(BitmapDescriptorFactory
//                            .defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
//            lastGnssLocation = gnssLocation;
//        } else {
//            // Move existing GNSS marker
//            gnssMarker.setPosition(gnssLocation);
//
//            // Add a segment to the blue GNSS line, if this is a new location
//            if (lastGnssLocation != null && !lastGnssLocation.equals(gnssLocation)) {
//                List<LatLng> gnssPoints = new ArrayList<>(gnssPolyline.getPoints());
//                gnssPoints.add(gnssLocation);
//                gnssPolyline.setPoints(gnssPoints);
//            }
//            lastGnssLocation = gnssLocation;
//        }
//
//        // Blue GNSS dot — only when moved enough
//        if (showGnssDots && hasMoved(gnssLocation, lastGnssDotPos)) {
//            addObservationMarker(gnssLocation, COLOR_GNSS, gnssObservationMarkers);
//            lastGnssDotPos = gnssLocation;
//        }
//
//    }

    /**
     * Updates the GNSS marker and polyline, and places a blue GNSS observation dot.
     *
     * The GNSS marker and blue path line are only shown when the GNSS switch is ON.
     * The blue observation dots are controlled independently by the GNSS dot switch
     * (showGnssDots) — they appear regardless of the GNSS path switch state.
     *
     * @param gnssLocation latest raw GNSS fix.
     */
    public void updateGNSS(@NonNull LatLng gnssLocation) {
        if (gMap == null) return;
        if (!isGnssOn) return;

        // GNSS marker and path line — only when GNSS switch is ON
        if (isGnssOn) {
            if (gnssMarker == null) {
                gnssMarker = gMap.addMarker(new MarkerOptions()
                        .position(gnssLocation)
                        .title("GNSS Position")
                        .icon(BitmapDescriptorFactory
                                .defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
//                lastGnssLocation = gnssLocation;
            } else {
                gnssMarker.setPosition(gnssLocation);
                if (lastGnssLocation != null && !lastGnssLocation.equals(gnssLocation)) {
                    List<LatLng> gnssPoints = new ArrayList<>(gnssPolyline.getPoints());
                    gnssPoints.add(gnssLocation);
                    gnssPolyline.setPoints(gnssPoints);
                }
            }
        }
// Always update lastGnssLocation — needed so path builds correctly
        // when gnssSwitch is turned ON mid-session
        lastGnssLocation = gnssLocation;

        // Blue dot — independent of gnssSwitch, only controlled by gnssDotSwitch
        if (showGnssDots && hasMoved(gnssLocation, lastGnssDotPos)) {
            addObservationMarker(gnssLocation, COLOR_GNSS, gnssObservationMarkers);
            lastGnssDotPos = gnssLocation;
        }
    }

    /**
     * Updates the map with the latest WiFi position fix and adds an amber WiFi
     * observation dot. Called from RecordingFragment whenever a new WiFi position
     * is available from SensorFusion.
     *
     * @param wifiLocation the WiFi-derived position.
     */
    public void updateWifiPosition(@NonNull LatLng wifiLocation) {
        if (gMap == null) return;

        if (showWifiDots && hasMoved(wifiLocation, lastWifiDotPos)) {
            addObservationMarker(wifiLocation, COLOR_WIFI, wifiObservationMarkers);
            lastWifiDotPos = wifiLocation;
        }
        Log.d(TAG, "WiFi position updated: " + wifiLocation);
    }

    // -------------------------------------------------------------------------
    // Distance threshold — fixes dot stacking when stationary
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code newPos} is at least
     * {@value MIN_DOT_SPACING_METRES} metres from {@code lastPos}.
     * Returns {@code true} unconditionally when {@code lastPos} is {@code null}
     * (the very first dot for this source).
     *
     * <p><b>Why this matters:</b> position updates arrive every 200 ms regardless
     * of whether the user has moved. Without this guard every call would add a dot,
     * so standing still would burn through the entire rolling window at one location,
     * leaving no dots along the actual path.</p>
     *
     * @param newPos  candidate position for the new dot.
     * @param lastPos position of the most recent dot ({@code null} if none yet).
     * @return {@code true} if a new dot should be placed.
     */
    private boolean hasMoved(@NonNull LatLng newPos, @Nullable LatLng lastPos) {
        if (lastPos == null) return true;
        float[] result = new float[1];
        android.location.Location.distanceBetween(
                lastPos.latitude, lastPos.longitude,
                newPos.latitude,  newPos.longitude,
                result);
        return result[0] >= MIN_DOT_SPACING_METRES;
    }

    // -------------------------------------------------------------------------
    // Smoothing — low-pass filter
    // -------------------------------------------------------------------------

    /**
     * Applies a low-pass filter to the incoming position. The filter blends the
     * previous smoothed position with the new raw position using {@link #LOW_PASS_ALPHA}.
     *
     * <p>A lower alpha produces a smoother path with more lag; a higher alpha
     * follows the raw data more closely. The current value of 0.25 gives a good
     * balance for indoor walking speeds.</p>
     *
     * @param newPoint the raw position to filter.
     * @return the filtered position.
     */
    private LatLng applyLowPassFilter(@NonNull LatLng newPoint) {
        if (smoothedPosition == null) {
            // First point — initialise filter state
            smoothedPosition = newPoint;
            return newPoint;
        }
        double lat = smoothedPosition.latitude
                + LOW_PASS_ALPHA * (newPoint.latitude  - smoothedPosition.latitude);
        double lng = smoothedPosition.longitude
                + LOW_PASS_ALPHA * (newPoint.longitude - smoothedPosition.longitude);
        smoothedPosition = new LatLng(lat, lng);
        return smoothedPosition;
    }

    /**
     * Controls visibility of the LPF-smoothed fused polyline.
     * The red PDR line and purple fused line are always visible.
     * Only the teal LPF line is toggled by the smoothing switch.
     */
    private void updatePolylineVisibility() {
        if (lpfPolyline != null) {
            lpfPolyline.setVisible(isSmoothingEnabled);
        }
    }

    /**
     * Updates the uncertainty circle around the fused position marker.
     *
     * Radius is taken from SensorFusion.getPositionUncertainty() which returns
     * the standard deviation of the particle cloud in metres:
     *   < 5m  → green  (filter confident)
     *   5–15m → yellow (moderate uncertainty)
     *   > 15m → red    (filter uncertain / diverged)
     *
     * The circle is hidden when the filter returns -1 (not yet initialised).
     *
     * @param centre the current fused position — centre of the circle.
     */
    private void updateUncertaintyCircle(@NonNull LatLng centre) {
        if (uncertaintyCircle == null) return;

        double uncertainty = SensorFusion.getInstance().getPositionUncertainty();

        // Filter not yet initialised — hide the circle
        if (uncertainty < 0) {
            uncertaintyCircle.setVisible(false);
            return;
        }

        // Choose colour based on confidence level
        int fillColor;
        int strokeColor;
        if (uncertainty < 5.0) {
            // Green — confident
            fillColor  = Color.argb(50,  0, 200, 0);
            strokeColor = Color.argb(150, 0, 200, 0);
        } else if (uncertainty <= 15.0) {
            // Yellow — moderate
            fillColor  = Color.argb(50,  255, 200, 0);
            strokeColor = Color.argb(150, 255, 200, 0);
        } else {
            // Red — uncertain
            fillColor  = Color.argb(50,  220, 0, 0);
            strokeColor = Color.argb(150, 220, 0, 0);
        }

        uncertaintyCircle.setCenter(centre);
        uncertaintyCircle.setRadius(uncertainty);
        uncertaintyCircle.setFillColor(fillColor);
        uncertaintyCircle.setStrokeColor(strokeColor);
        uncertaintyCircle.setVisible(true);

        Log.d(TAG, "Uncertainty circle: radius=" + String.format("%.1f", uncertainty) + "m");
    }

    /**
     * Redraws the particle cloud overlay from the current particle positions.
     *
     * Each particle from getParticles() is in local ENU metres and is converted
     * to LatLng via SensorFusion.enuToLatLon() before being rendered as a small
     * faint grey circle on the map.
     *
     * Throttled to redraw at most every 2 seconds to avoid performance issues
     * from recreating 300 map objects on every position update.
     *
     * Only runs when showParticleCloud is true.
     */
    private void redrawParticleCloud() {
        if (!showParticleCloud || gMap == null) return;

        long now = System.currentTimeMillis();
        if (now - lastParticleRedrawMs < 2000) return; // 2s throttle
        lastParticleRedrawMs = now;

        float[][] particles = SensorFusion.getInstance().getParticles();
        if (particles == null || particles.length == 0) return;

        // Remove old particle circles
        clearParticleCloud();

        // Draw new ones
        for (float[] particle : particles) {
            double[] latLon = SensorFusion.getInstance().enuToLatLon(particle[0], particle[1]);
            if (latLon == null) continue;

            Circle dot = gMap.addCircle(new CircleOptions()
                    .center(new LatLng(latLon[0], latLon[1]))
                    .radius(0.8)                              // 0.8m radius dot
                    .fillColor(Color.argb(60, 120, 120, 120)) // faint grey
                    .strokeWidth(0f)                          // no border
                    .zIndex(0f));                             // behind everything
            particleMarkers.add(dot);
        }

        Log.d(TAG, "Particle cloud redrawn: " + particles.length + " particles");
    }

    /**
     * Removes all particle circle overlays from the map and clears the list.
     */
    private void clearParticleCloud() {
        for (Circle c : particleMarkers) {
            if (c != null) c.remove();
        }
        particleMarkers.clear();
    }

    /**
     * Redraws the fused (smoothed) trajectory polyline if new points have
     * arrived since the last draw.
     *
     * <p>Called on two triggers:
     * <ol>
     *   <li>Every 1 second by {@link #trajectoryUpdateRunnable}.</li>
     *   <li>Immediately from {@link #updateUserLocation} when movement is
     *       detected (i.e. the position has actually changed).</li>
     * </ol>
     * Only updates the polyline when the point count has grown, avoiding
     * unnecessary Google Maps redraws.</p>
     */
    private void redrawFusedTrajectory() {
        if (smoothedPolyline == null || smoothedPoints.isEmpty()) return;
        if (smoothedPoints.size() == lastDrawnPointCount) return; // nothing new

        smoothedPolyline.setPoints(new ArrayList<>(smoothedPoints));
        lastDrawnPointCount = smoothedPoints.size();
        Log.d(TAG, "Fused trajectory redrawn — " + lastDrawnPointCount + " points");
    }

    // -------------------------------------------------------------------------
    // Colour-coded observation markers
    // -------------------------------------------------------------------------

    /**
     * Adds a numbered observation dot at the given position for the specified source.
     *
     * After adding, ALL markers in the queue are renumbered so that:
     *   - The newest dot always shows "1"
     *   - The oldest dot shows the highest number (up to MAX_OBSERVATION_MARKERS)
     *
     * When the queue is full, the oldest marker is removed before the new one is added.
     *
     * @param position    the observation position.
     * @param color       the fill colour for this source.
     * @param markerQueue the rolling window queue for this source.
     */
    private void addObservationMarker(@NonNull LatLng position,
                                      int color,
                                      @NonNull Queue<Marker> markerQueue) {
        if (gMap == null) return;

        // Remove oldest marker when the window is full
        if (markerQueue.size() >= maxObservations) {
            Marker oldest = markerQueue.poll();
            if (oldest != null) oldest.remove();
        }

        // Add the new dot — starts as number 1 (most recent), others will be renumbered below
        Marker newDot = gMap.addMarker(new MarkerOptions()
                .position(position)
                .icon(BitmapDescriptorFactory.fromBitmap(
                        createNumberedDotBitmap(color, 1)))
                .anchor(0.5f, 0.5f)
                .zIndex(2f));

        if (newDot != null) {
            // Store the dot colour as the tag so we can recreate its bitmap when renumbering
            newDot.setTag(color);
            markerQueue.add(newDot);
        }

        // Renumber all markers: newest = 1, oldest = queue.size()
        // The queue is ordered oldest-first (LinkedList), so we iterate in reverse
        List<Marker> markerList = new ArrayList<>(markerQueue);
        int total = markerList.size();
        for (int i = 0; i < total; i++) {
            Marker m = markerList.get(i);
            if (m == null) continue;
            int recencyNumber = total - i; // oldest gets highest number
            Object tag = m.getTag();
            int markerColor = (tag instanceof Integer) ? (int) tag : color;
            m.setIcon(BitmapDescriptorFactory.fromBitmap(
                    createNumberedDotBitmap(markerColor, recencyNumber)));
        }
    }

    // -------------------------------------------------------------------------
    // Dot queue visibility
    // -------------------------------------------------------------------------

    /**
     * Shows or hides all markers in a queue immediately. Called when the user
     * flips one of the three dot-source switches.
     */
    private void setQueueVisibility(@NonNull LinkedList<Marker> queue, boolean visible) {
        for (Marker m : queue) if (m != null) m.setVisible(visible);

    }

//    added-
    /**
     * Creates a numbered circle bitmap for an observation dot marker.
     * The number shows recency — 1 is the most recent observation.
     * Numbers are updated across the whole queue after each new dot is added.
     *
     * @param color  fill colour of the dot.
     * @param number the recency label to display (1 = most recent).
     * @return a 40×40 px Bitmap with a filled circle and white number.
     */
    private Bitmap createNumberedDotBitmap(int color, int number) {
        int size = 40;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Filled circle
        Paint fillPaint = new Paint();
        fillPaint.setColor(color);
        fillPaint.setAntiAlias(true);
        fillPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, fillPaint);

        // White border
        Paint borderPaint = new Paint();
        borderPaint.setColor(Color.WHITE);
        borderPaint.setAntiAlias(true);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2.5f);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, borderPaint);

        // Number text
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setAntiAlias(true);
        textPaint.setTextSize(16f);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextAlign(Paint.Align.CENTER);

        Rect bounds = new Rect();
        String text = String.valueOf(number);
        textPaint.getTextBounds(text, 0, text.length(), bounds);
        float textY = size / 2f - bounds.centerY();
        canvas.drawText(text, size / 2f, textY, textPaint);

        return bitmap;
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
        if (uncertaintyCircle != null) {
            uncertaintyCircle.remove();
            uncertaintyCircle = null;
        }

        lastGnssLocation = null;
        currentLocation  = null;

        smoothedPoints.clear();
        lpfPoints.clear();
        smoothedPosition = null; // reset LPF filter state

        clearParticleCloud();
        lastParticleRedrawMs = 0;

        // Reset fused trajectory state
        lastDrawnPointCount = 0;
        trajectoryUpdateHandler.removeCallbacks(trajectoryUpdateRunnable);
        if (isTrajectoryUpdateRunning) {
            trajectoryUpdateHandler.postDelayed(trajectoryUpdateRunnable, 1000);
        }

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
            smoothedPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.parseColor("#8B00FF"))
                    .width(6f)
                    .visible(true));
            lpfPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.parseColor("#00BCD4"))
                    .width(7f)
                    .visible(isSmoothingEnabled));
            uncertaintyCircle = gMap.addCircle(new CircleOptions()
                    .center(new LatLng(0, 0))
                    .radius(0)
                    .fillColor(Color.argb(50, 0, 255, 0))
                    .strokeColor(Color.argb(100, 0, 255, 0))
                    .strokeWidth(2f)
                    .visible(false));
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
    public interface VenueSelectionListener {
        void onVenueSelected(String venueIdOrName);
        void onVenueCleared();
    }

    private static float distanceMeters(LatLng a, LatLng b) {
        float[] out = new float[1];
        android.location.Location.distanceBetween(
                a.latitude, a.longitude, b.latitude, b.longitude, out
        );
        return out[0];
    }
    private List<String> getObservedMacsOrEmpty() {
        return observedMacs == null ? new ArrayList<>() : new ArrayList<>(observedMacs);
    }


    public void updateObservedMacs(@NonNull List<String> macs) {
        Log.d("TrajectoryMapFragment", "Observed macs updated size=" + macs.size()+ " instance=" + System.identityHashCode(this));
        synchronized (macLock) {
            observedMacs = new ArrayList<>(macs);
        }
    }

    public IndoorMapManager getIndoorMapManager() {
        return indoorMapManager;
    }




}
