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
    // Keep test point markers so they can be cleared when recording ends
    private final List<Marker> testPointMarkers = new ArrayList<>();

    private Polyline polyline; // Polyline representing user's movement path
    // Catmull-Rom smoothing toggle
    private boolean isCatmullRomEnabled = false;
    private SwitchMaterial catmullSwitch;

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
    private static final long AUTO_FLOOR_DEBOUNCE_MS = 500;
    private static final long AUTO_FLOOR_CHECK_INTERVAL_MS = 1000;
    private Handler autoFloorHandler;
    private Runnable autoFloorTask;
    private int lastCandidateFloor = Integer.MIN_VALUE;
    private long lastCandidateTime = 0;

    // UI
    private Spinner switchMapSpinner;

    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;

    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private TextView floorLabel;
    private Button switchColorButton;
    private Polygon buildingPolygon;

    // -------------------------------------------------------------------------
    // NEW FIELDS — observation markers and PDR trajectory (Assignment 2)
    // -------------------------------------------------------------------------

    /**
     * Maximum number of observation markers kept on screen per positioning source.
     *
     * WHY DIFFERENT VALUES:
     * PDR updates every ~200ms whenever the user moves, so we allow more markers (6)
     * to show a visible recent trail. GNSS and WiFi are sparse (a few fixes per minute),
     * so 5 markers is plenty — they won't accumulate fast enough to clutter the map.
     *
     * The "last N observations" requirement in the assignment brief (section 3.3) is
     * satisfied by these caps: old markers are automatically removed as new ones appear.
     */
    private static final int MAX_PDR_OBSERVATIONS = 2;
    private static final int MAX_GNSS_OBSERVATIONS = 2;
    private static final int MAX_WIFI_OBSERVATIONS = 2;

    /**
     * Separate lists for each positioning source's observation markers.
     *
     * WHY SEPARATE LISTS:
     * Each source has its own cap (MAX_*_OBSERVATIONS) and its own colour, so they
     * must be managed independently. Mixing them into one list would make it impossible
     * to remove only GNSS markers when the GNSS switch is toggled off, for example.
     *
     * Colour coding (as per assignment brief section 3.3):
     *   PDR   → orange  (HUE_ORANGE)
     *   GNSS  → blue    (HUE_AZURE)
     *   WiFi  → green   (HUE_GREEN)
     */

    private final List<Marker> gnssObservationMarkers = new ArrayList<>();
    private final List<Marker> wifiObservationMarkers = new ArrayList<>();
    private final List<Marker> pdrObservationMarkers = new ArrayList<>();

    private LatLng lastWifiObservationLocation = null;

    // Raw accepted PDR positions, used as source for Catmull-Rom redraw
    private final List<LatLng> rawPdrPoints = new ArrayList<>();

    // Index into rawPdrPoints from which Catmull-Rom smoothing applies.
    // Points before this index are always drawn raw.
    private int catmullStartIndex = 0;

    /**
     * Orange polyline connecting every accepted PDR position (filtered by distance threshold).
     *
     * WHY A SEPARATE POLYLINE FROM THE RED ONE:
     * The existing red {@code polyline} draws every single raw PDR update (~5 per second),
     * including noise when the user is standing still. This new orange polyline only adds
     * a point when the user has moved at least PDR_OBSERVATION_MIN_DISTANCE_M, producing
     * a much cleaner path. This satisfies the assignment brief requirement to "display the
     * full trajectory of fused positions" without the jitter of the raw feed.
     */

    private Polyline pdrTrajectoryPolyline;
    /**
     * Stores the last position at which a PDR observation marker was placed.
     * Used to calculate the distance to the next incoming position and decide
     * whether to skip it (too close) or accept it (far enough).
     * Null on first call, which means the very first position is always accepted.
     */
    private LatLng lastPdrObservationLocation = null;

    /**
     * Minimum distance in metres the user must move before a new PDR observation
     * marker is placed and the orange trajectory polyline is extended.
     *
     * WHY 1 METRE:
     * PDR updates at ~200ms intervals. Without a threshold, standing still produces
     * hundreds of overlapping markers per minute (confirmed: 733 markers in one session
     * before this fix). 1m filters out sensor noise while still capturing each step.
     * The GNSS equivalent threshold is 3m (in shouldAddGnssObservation) because GNSS
     * noise is larger in scale than PDR noise.
     */

    private static final double PDR_OBSERVATION_MIN_DISTANCE_M = 3;


    // -------------------------------------------------------------------------
    // OBSERVATION MARKER HELPERS (NEW)
    // -------------------------------------------------------------------------

    /**
     * Places a colour-coded observation marker on the map and enforces the "last N" cap.
     *
     * HOW THE CAP WORKS:
     * Each positioning source (PDR, GNSS, WiFi) has its own list and its own maxSize.
     * When a new marker is added and the list exceeds maxSize, the oldest marker is
     * removed from both the list and the map. This implements a sliding window of
     * the most recent N observations, as required by assignment brief section 3.3:
     * "Display colour coded absolute position updates (last N observations)".
     *
     * WHY A SHARED HELPER METHOD:
     * All three sources (PDR, GNSS, WiFi) need the same add-and-cap logic, just with
     * different colours and caps. Using one helper avoids duplicating the same 15 lines
     * three times and makes it easy to change the logic in one place.
     *
     * @param position   Where to place the marker on the map.
     * @param title      Label shown when the marker is tapped (e.g. "PDR", "GNSS", "WiFi").
     * @param hue        Colour of the marker pin (use BitmapDescriptorFactory.HUE_* constants).
     * @param markerList The list for this source (pdrObservationMarkers, gnssObservationMarkers, etc.).
     * @param maxSize    Maximum number of markers to keep visible for this source.
     */
    private void addObservationMarker(
            LatLng position,
            String title,
            float hue,
            List<Marker> markerList,
            int maxSize
    ) {
        if (gMap == null)
        {
            Log.d("DisplayDebug", "addObservationMarker skipped: gMap == null, title=" + title);
            return;
        }

        Log.d("DisplayDebug", "addObservationMarker title=" + title + ", pos=" + position);

        Marker marker = gMap.addMarker(new MarkerOptions()
                .position(position)
                .title(title)
                .icon(BitmapDescriptorFactory.defaultMarker(hue)));

        if (marker != null) {
            markerList.add(marker);
        }
        // If the list now exceeds the cap, remove the oldest marker from the map and the list

        if (markerList.size() > maxSize) {
            Marker oldest = markerList.remove(0);// remove from front (oldest)
            oldest.remove(); // remove from the map
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
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        floorLabel      = view.findViewById(R.id.floorLabel);
        switchColorButton = view.findViewById(R.id.lineColorButton);

        catmullSwitch = view.findViewById(R.id.catmullSwitch);
        catmullSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isCatmullRomEnabled = isChecked;
            // Redraw BOTH polylines with current points using new mode
            redrawPdrPolyline();
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

        // NEW: Orange polyline — filtered PDR trajectory.
        // Only extended when user moves >= PDR_OBSERVATION_MIN_DISTANCE_M (1m).
        // This is the cleaner, smoother path shown to the user as their walking trail.
        // When sensor fusion is integrated, this will be replaced by the fused position path.
        pdrTrajectoryPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.rgb(255, 165, 0))
                .width(5f)
                .add()
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
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
        } else {
            // Update marker position + orientation
            orientationMarker.setPosition(newLocation);
            orientationMarker.setRotation(orientation);
            // Move camera a bit
            gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
        }

        // Extend polyline if movement occurred
        /*if (oldLocation != null && !oldLocation.equals(newLocation) && polyline != null) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            points.add(newLocation);
            polyline.setPoints(points);
        }*/
        // Extend polyline
//        if (polyline != null) {
//            List<LatLng> points = new ArrayList<>(polyline.getPoints());
//
//            // First position fix: add the first polyline point
//            if (oldLocation == null) {
//                points.add(newLocation);
//                polyline.setPoints(points);
//            } else if (!oldLocation.equals(newLocation)) {
//                // Subsequent movement: append a new polyline point
//                points.add(newLocation);
//                polyline.setPoints(points);
//            }
//        }

        // Update indoor map overlay
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(newLocation);
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


    // -------------------------------------------------------------------------
    // GNSS UPDATE (ORIGINAL, EXTENDED)
    // -------------------------------------------------------------------------

    /**
     * Updates the GNSS marker position and conditionally adds a new blue observation dot.
     *
     * WHAT CHANGED FROM ORIGINAL:
     * The original only moved the GNSS marker and extended the blue polyline on every update.
     * Now it also calls {@link #addObservationMarker} to place a capped blue dot, but only
     * when {@link #shouldAddGnssObservation} confirms the new fix is > 3m from the last one.
     * This prevents the blue dots from piling up when GNSS jitters in place indoors.
     *
     * This method only runs when the GNSS switch in the UI is toggled ON.
     *
     * @param gnssLocation The raw GNSS position from SensorTypes.GNSSLATLONG.
     */
    public void updateGNSS(@NonNull LatLng gnssLocation) {
        Log.d("DisplayDebug", "updateGNSS called: " + gnssLocation);
        if (gMap == null)
        {
            Log.d("DisplayDebug", "updateGNSS skipped: gMap == null");
            return;
        }
        if (!isGnssOn) {
            Log.d("DisplayDebug", "updateGNSS skipped: GNSS switch off");
            return;
        }
        boolean isNewGnssPoint = shouldAddGnssObservation(gnssLocation);

        // Always move the live GNSS marker to the latest fix
        if (gnssMarker == null) {
            gnssMarker = gMap.addMarker(new MarkerOptions()
                    .position(gnssLocation)
                    .title("GNSS Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
        } else {
            gnssMarker.setPosition(gnssLocation);
        }

        // Only extend the blue polyline and add an observation dot when significantly moved
        if (isNewGnssPoint) {
            List<LatLng> gnssPoints = new ArrayList<>(gnssPolyline.getPoints());
            gnssPoints.add(gnssLocation);
            gnssPolyline.setPoints(gnssPoints);

            addObservationMarker(
                    gnssLocation,
                    "GNSS",
                    BitmapDescriptorFactory.HUE_AZURE,
                    gnssObservationMarkers,
                    MAX_GNSS_OBSERVATIONS
            );



            lastGnssLocation = gnssLocation;
        }
    }

    /**
     * Decides whether a new GNSS fix is far enough from the last one to warrant
     * adding a new observation marker and extending the blue polyline.
     *
     * WHY 3 METRES:
     * GNSS receivers indoors report positions that fluctuate by several metres even
     * when the user is stationary, due to signal reflection off walls (multipath).
     * A 3m threshold filters out this stationary jitter while still capturing
     * meaningful movement.
     *
     * @param newLocation The incoming GNSS fix to evaluate.
     * @return true if a new observation marker should be added, false to skip.
     */
    private boolean shouldAddGnssObservation(LatLng newLocation) {
        if (lastGnssLocation == null) return true;

        double dist = UtilFunctions.distanceBetweenPoints(lastGnssLocation, newLocation);
        return dist > 3.0; // only  calculate new observation if distance > 3m
    }

    // -------------------------------------------------------------------------
    // PDR OBSERVATION UPDATE (NEW)
    // -------------------------------------------------------------------------

    /**
     * Adds a new orange PDR observation marker and extends the orange trajectory polyline,
     * but only if the user has moved at least PDR_OBSERVATION_MIN_DISTANCE_M (1m) since
     * the last accepted observation.
     *
     * WHY THIS METHOD EXISTS (was not in the original):
     * The original code had no PDR observation display at all — PDR was only used internally
     * to move the navigation arrow. This method adds the visible orange dot trail and the
     * smooth orange trajectory line required by assignment brief section 3.3.
     *
     * TWO THINGS HAPPEN WHEN A POINT IS ACCEPTED:
     * 1. An orange dot (observation marker) is placed and the oldest dot is removed if
     *    the count exceeds MAX_PDR_OBSERVATIONS, keeping a rolling trail of recent positions.
     * 2. The orange pdrTrajectoryPolyline is extended, building up the full walking path.
     *
     * Called by RecordingFragment.updateUIandPosition() on every sensor update cycle.
     *
     * @param pdrLocation The current PDR-computed position in WGS84 coordinates.
     */
    public void updatePdrObservation(@NonNull LatLng pdrLocation) {
        Log.d("DisplayDebug", "updatePdrObservation called: " + pdrLocation);
        if (gMap == null) {
            Log.d("DisplayDebug", "updatePdrObservation skipped: gMap == null");
            return;
        }

        // Distance threshold: skip this update if the user hasn't moved far enough.
        // Without this, standing still produces hundreds of overlapping markers per minute.

        // Only add a new PDR observation marker if the user has moved at least 1 metre
        if (lastPdrObservationLocation != null) {
            double dist = UtilFunctions.distanceBetweenPoints(lastPdrObservationLocation, pdrLocation);
            if (dist < PDR_OBSERVATION_MIN_DISTANCE_M) {
                Log.d("DisplayDebug", "updatePdrObservation skipped: too close (" +
                        String.format("%.2f", dist) + "m < " + PDR_OBSERVATION_MIN_DISTANCE_M + "m)");
                return;
            }
        }
        // Update the reference point for the next distance check
        lastPdrObservationLocation = pdrLocation;

        // Place a new orange dot; the helper removes the oldest dot if cap is exceeded
        addObservationMarker(
                pdrLocation,
                "PDR",
                BitmapDescriptorFactory.HUE_ORANGE,
                pdrObservationMarkers,
                MAX_PDR_OBSERVATIONS
        );

        // Store raw point for Catmull-Rom redraw source.
        rawPdrPoints.add(pdrLocation);

        // Build the displayed path:
        // - points before catmullStartIndex are always drawn raw (history)
        // - points from catmullStartIndex onward are smoothed if switch is on
        if (pdrTrajectoryPolyline != null) {
            List<LatLng> displayPoints;
            if (isCatmullRomEnabled && catmullStartIndex < rawPdrPoints.size()) {
                // Raw history segment
                List<LatLng> history = rawPdrPoints.subList(0, catmullStartIndex);
                // Smoothed future segment (need at least 4 points for Catmull-Rom)
                List<LatLng> future = new ArrayList<>(rawPdrPoints.subList(catmullStartIndex, rawPdrPoints.size()));
                List<LatLng> smoothFuture = applyCatmullRom(future);
                displayPoints = new ArrayList<>(history);
                displayPoints.addAll(smoothFuture);
            } else {
                displayPoints = new ArrayList<>(rawPdrPoints);
            }
            pdrTrajectoryPolyline.setPoints(displayPoints);
        }
    }

    // -------------------------------------------------------------------------
    // CATMULL-ROM SMOOTHING (NEW)
    // -------------------------------------------------------------------------

    /**
     * Computes one segment of a Catmull-Rom spline between p1 and p2,
     * using p0 and p3 as the surrounding control points.
     *
     * WHY CATMULL-ROM:
     * Unlike Bezier curves, Catmull-Rom splines pass through every control point,
     * which means the smoothed path still visits every accepted PDR observation.
     * This gives a natural-looking walking curve without drifting away from real data.
     *
     * @param p0    Control point before p1 (or p1 itself if i==0)
     * @param p1    Start of this segment
     * @param p2    End of this segment
     * @param p3    Control point after p2 (or p2 itself if at end)
     * @param steps Number of interpolated sub-points between p1 and p2 (higher = smoother)
     * @return      Interpolated LatLng points for this segment
     */
    private List<LatLng> catmullRomSegment(LatLng p0, LatLng p1, LatLng p2, LatLng p3, int steps) {
        List<LatLng> result = new ArrayList<>();
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            float t2 = t * t, t3 = t2 * t;
            double lat = 0.5 * ((2 * p1.latitude)
                    + (-p0.latitude + p2.latitude) * t
                    + (2*p0.latitude - 5*p1.latitude + 4*p2.latitude - p3.latitude) * t2
                    + (-p0.latitude + 3*p1.latitude - 3*p2.latitude + p3.latitude) * t3);
            double lng = 0.5 * ((2 * p1.longitude)
                    + (-p0.longitude + p2.longitude) * t
                    + (2*p0.longitude - 5*p1.longitude + 4*p2.longitude - p3.longitude) * t2
                    + (-p0.longitude + 3*p1.longitude - 3*p2.longitude + p3.longitude) * t3);
            result.add(new LatLng(lat, lng));
        }
        return result;
    }

    /**
     * Applies Catmull-Rom smoothing to a full list of LatLng points.
     *
     * WHY 4-POINT MINIMUM:
     * Catmull-Rom needs 4 points to define even one segment (p0 as lead-in,
     * p1-p2 as the actual segment, p3 as look-ahead). With fewer than 4 points
     * the spline is undefined, so we fall back to returning the raw points.
     *
     * @param pts  Raw list of accepted PDR positions
     * @return     Smoothed list ready to be set on the polyline
     */
    private List<LatLng> applyCatmullRom(List<LatLng> pts) {
        if (pts.size() < 4) return new ArrayList<>(pts);
        List<LatLng> smooth = new ArrayList<>();
        for (int i = 0; i < pts.size() - 1; i++) {
            LatLng p1 = pts.get(i);
            LatLng p2 = pts.get(i + 1);
            // Skip smoothing for large jumps (e.g. GNSS dropout, sudden position jump)
            // — draw straight line instead to avoid Catmull-Rom overshoot spikes
            if (UtilFunctions.distanceBetweenPoints(p1, p2) > 5.0) {
                smooth.add(p1);
                smooth.add(p2);
                continue;
            }
            LatLng p0 = pts.get(Math.max(i - 1, 0));
            LatLng p3 = pts.get(Math.min(i + 2, pts.size() - 1));
            smooth.addAll(catmullRomSegment(p0, p1, p2, p3, 10));
        }
        return smooth;
    }

    /**
     * Redraws the orange PDR trajectory polyline.
     *
     * WHY WE SPLIT AT catmullStartIndex:
     * When the switch is toggled mid-recording, we don't want to retroactively
     * smooth the path the user already walked. catmullStartIndex marks the point
     * in rawPdrPoints where smoothing was turned on, so history stays raw and
     * only future points get the Catmull-Rom curve applied.
     */
    private void redrawPdrPolyline() {
        if (pdrTrajectoryPolyline == null) return;

        if (!isCatmullRomEnabled) {
            // Switch turned off — restore full raw path
            pdrTrajectoryPolyline.setPoints(new ArrayList<>(rawPdrPoints));
            return;
        }

        // Switch just turned on — record where smoothing begins
        catmullStartIndex = rawPdrPoints.size();

        // History stays raw, nothing to smooth yet — no redraw needed
        pdrTrajectoryPolyline.setPoints(new ArrayList<>(rawPdrPoints));
    }

    /**
     * Adds a point to the raw PDR polyline (red) without moving the camera or marker.
     * Shows the unfiltered PDR path for comparison against the fused trajectory.
     */
    public void addRawPdrPoint(@NonNull LatLng point) {
        if (polyline == null) return;
        List<LatLng> points = new ArrayList<>(polyline.getPoints());
        points.add(point);
        polyline.setPoints(points);
    }

    // -------------------------------------------------------------------------
    // WIFI OBSERVATION UPDATE (NEW)
    // -------------------------------------------------------------------------

    /**
     * Places a green WiFi observation marker on the map.
     *
     * WHY THIS METHOD EXISTS (was not in the original):
     * The original code had no WiFi position display. This method is called by
     * RecordingFragment whenever the openpositioning API returns a valid WiFi fix.
     * When inside the Nucleus/Library buildings, WiFi positioning returns a fix
     * every few seconds; this method places a green dot at that location.
     *
     * NOTE: WiFi observations are not distance-filtered (unlike GNSS and PDR) because
     * they are already sparse — the API typically only returns a new fix every few seconds,
     * and each fix represents a genuinely different computed position, not sensor noise.
     *
     * @param wifiLocation The position returned by the openpositioning WiFi API.
     */
    public void updateWifiObservation(@NonNull LatLng wifiLocation) {
        Log.d("DisplayDebug", "updateWifiObservation called: " + wifiLocation);

        if (gMap == null) {
            Log.d("DisplayDebug", "updateWifiObservation skipped: gMap == null");
            return;
        }

        if (lastWifiObservationLocation != null) {
            double dist = UtilFunctions.distanceBetweenPoints(lastWifiObservationLocation, wifiLocation);
            if (dist < 2.0) {
                Log.d("DisplayDebug", "updateWifiObservation skipped: too close (" +
                        String.format("%.2f", dist) + "m < 2.0m)");
                return;
            }
        }

        addObservationMarker(
                wifiLocation,
                "WiFi",
                BitmapDescriptorFactory.HUE_GREEN,
                wifiObservationMarkers,
                MAX_WIFI_OBSERVATIONS
        );
        lastWifiObservationLocation = wifiLocation;
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

        // NEW: Clear GNSS observation dots
        for (Marker m : gnssObservationMarkers) {
            m.remove();
        }
        gnssObservationMarkers.clear();

        // NEW: Clear WiFi observation dots
        for (Marker m : wifiObservationMarkers) {
            m.remove();
        }
        wifiObservationMarkers.clear();

        // NEW: Clear WiFi observation dots
        for (Marker m : pdrObservationMarkers) {
            m.remove();
        }
        pdrObservationMarkers.clear();



        // Recreate empty polylines so the new session can start drawing immediately
        if (gMap != null) {
            polyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.RED)
                    .width(5f)
                    .add());
            gnssPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.BLUE)
                    .width(5f)
                    .add());
            // NEW: Remove and recreate the orange PDR trajectory polyline
            if (pdrTrajectoryPolyline != null) {
                pdrTrajectoryPolyline.remove();
                pdrTrajectoryPolyline = null;
            }
            // Reset the distance filter anchor so the first new point is always accepted
            lastPdrObservationLocation = null;
            // Clear the raw point store so Catmull-Rom starts fresh for the new session
            rawPdrPoints.clear();
            catmullStartIndex = 0;
            pdrTrajectoryPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.rgb(255, 165, 0))
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

        int mmFloor = sensorFusion.getMapMatchingFloor();
        if (mmFloor >= 0) {
            candidateFloor = mmFloor;
        } else if (sensorFusion.getLatLngWifiPositioning() != null) {
            candidateFloor = sensorFusion.getWifiFloor();
        } else {
            float elevation = sensorFusion.getElevation();
            float floorHeight = indoorMapManager.getFloorHeight();
            if (floorHeight <= 0) return;
            candidateFloor = Math.round(elevation / floorHeight);
        }

        indoorMapManager.setCurrentFloor(candidateFloor, true);
        updateFloorLabel();
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
     * Evaluates the current floor using WiFi com.openpositioning.PositionMe.positioning (priority) or
     * barometric elevation (fallback). Applies a 3-second debounce window
     * to prevent jittery floor switching.
     */
    private void evaluateAutoFloor() {
        if (sensorFusion == null || indoorMapManager == null) return;
        if (!indoorMapManager.getIsIndoorMapSet()) return;

        int candidateFloor;

        // Priority 1: MapMatchingEngine floor (barometric with guards)
        int mmFloor = sensorFusion.getMapMatchingFloor();
        if (mmFloor >= 0) {
            candidateFloor = mmFloor;
        }
        // Priority 2: WiFi floor
        else if (sensorFusion.getLatLngWifiPositioning() != null) {
            candidateFloor = sensorFusion.getWifiFloor();
        }
        // Priority 3: Raw barometric elevation
        else {
            float elevation = sensorFusion.getElevation();
            float floorHeight = indoorMapManager.getFloorHeight();
            if (floorHeight <= 0) return;
            candidateFloor = Math.round(elevation / floorHeight);
        }

        // Debounce (unchanged from original)
        long now = SystemClock.elapsedRealtime();
        if (candidateFloor != lastCandidateFloor) {
            lastCandidateFloor = candidateFloor;
            lastCandidateTime = now;
            return;
        }

        if (now - lastCandidateTime >= AUTO_FLOOR_DEBOUNCE_MS) {
            indoorMapManager.setCurrentFloor(candidateFloor, true);
            updateFloorLabel();
            lastCandidateTime = now;
        }
    }

    //endregion
}
