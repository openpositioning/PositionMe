package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Bitmap;
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
import android.widget.Spinner;
import android.widget.TextView;

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
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * CW2 display module for the indoor map. This fragment renders the fused user position, live
 * heading, observation tails and per-floor track history on top of Google Maps.
 *
 * <p>A fragment responsible for displaying a trajectory map using Google Maps.
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

    private static final float DIRECTION_MARKER_SIZE_DP = 18f;
    private static final double MIN_DIRECTION_DISTANCE_METERS = 0.55;
    private static final double CAMERA_RECENTER_DISTANCE_METERS = 4.0;
    private static final long CAMERA_RECENTER_INTERVAL_MS = 1_000L;
    private static final int MAX_TRACK_HISTORY_POINTS = 600;
    private static final double TRACK_APPEND_DISTANCE_METERS = 0.06;
    private static final double TRACK_REPLACE_DISTANCE_METERS = 0.03;
    private static final double MAX_TRACK_APPEND_DISTANCE_METERS = 6.0;
    private GoogleMap gMap; // Google Maps instance
    private LatLng currentLocation; // Stores the user's current location
    private Marker directionMarker; // Current user direction arrow
    // Keep test point markers so they can be cleared when recording ends
    private final List<com.google.android.gms.maps.model.Marker> testPointMarkers = new ArrayList<>();
    private final List<Circle> gnssTailCircles = new ArrayList<>();
    private final List<Circle> wifiTailCircles = new ArrayList<>();
    private final List<Circle> pdrTailCircles = new ArrayList<>();

    private Polyline polyline; // Polyline representing user's movement path
    private boolean isGnssOn = true; // GNSS display stays enabled during recording

    private LatLng pendingCameraPosition = null; // Stores pending camera movement
    private boolean hasPendingCameraMove = false; // Tracks if camera needs to move

    private IndoorMapManager indoorMapManager; // Manages indoor mapping
    private SensorFusion sensorFusion;
    private float lastDirectionDegrees = 0f;
    private LatLng lastCameraLocation;
    private long lastCameraUpdateMs;
    private final Map<Integer, List<LatLng>> userTrackHistoryByFloor = new HashMap<>();
    private int activeTrackFloor = Integer.MIN_VALUE;

    // Auto-floor state
    private static final String TAG = "TrajectoryMapFragment";
    private static final long AUTO_FLOOR_CHECK_INTERVAL_MS = 1000;
    private static final double TAIL_RADIUS_METERS = 0.45;
    private static final int GNSS_TAIL_COLOR = Color.rgb(25, 118, 210);
    private static final int WIFI_TAIL_COLOR = Color.rgb(0, 137, 123);
    private static final int PDR_TAIL_COLOR = Color.rgb(255, 111, 0);
    private Handler autoFloorHandler;
    private Runnable autoFloorTask;

    // UI
    private View mapControlsCard;
    private Spinner switchMapSpinner;

    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private TextView floorLabel;
    private boolean previewFloorOnlyMode;


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
        mapControlsCard  = view.findViewById(R.id.mapControlsCard);
        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        floorLabel      = view.findViewById(R.id.floorLabel);
        applyOverlayMode();

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

        sensorFusion = SensorFusion.getInstance();
        configureAlwaysOnControls();
        startAutoFloor();

        floorUpButton.setOnClickListener(v -> {
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
                sensorFusion.setCurrentLogicalFloor(indoorMapManager.getCurrentLogicalFloor());
                syncActiveTrackFloorWithDisplayedFloor();
                updateFloorLabel();
            }
        });

        floorDownButton.setOnClickListener(v -> {
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
                sensorFusion.setCurrentLogicalFloor(indoorMapManager.getCurrentLogicalFloor());
                syncActiveTrackFloorWithDisplayedFloor();
                updateFloorLabel();
            }
        });
    }

    public void setPreviewFloorOnlyMode(boolean enabled) {
        previewFloorOnlyMode = enabled;
        applyOverlayMode();
    }

    private void configureAlwaysOnControls() {
        isGnssOn = true;
    }

    /**
     * Initialize the map settings with the provided GoogleMap instance.
     * <p>
     *     The method sets basic map settings, initializes the indoor map manager,
     *     and creates an empty polyline for fused movement tracking.
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
    /**
     * Updates the live user marker and the visible track on the map.
     *
     * <p>The raw fused position is first constrained by the indoor map, then the display heading
     * is resolved, and finally the floor-specific track history is updated. This keeps the
     * rendered marker responsive while still respecting map-matching constraints.</p>
     */
    public LatLng updateUserLocation(@NonNull LatLng newLocation, float orientation) {
        if (gMap == null) return newLocation;

        LatLng oldLocation = this.currentLocation;
        LatLng displayLocation = newLocation;
        if (indoorMapManager != null) {
            // Update building/floor context from the fused point before clipping the visible
            // marker to legal indoor space.
            indoorMapManager.setCurrentLocation(newLocation);
            syncDisplayedFloor();
            syncActiveTrackFloorWithDisplayedFloor();
            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
            displayLocation = indoorMapManager.constrainPositionToLegalSpace(oldLocation, newLocation);
            indoorMapManager.setCurrentLocation(displayLocation);
        }

        float resolvedDirection = resolveDisplayDirection(oldLocation, displayLocation, orientation);
        boolean insignificantMove = oldLocation != null
                && UtilFunctions.distanceBetweenPoints(oldLocation, displayLocation) < 0.18;
        boolean insignificantRotation = directionMarker != null
                && absoluteBearingDelta(lastDirectionDegrees, resolvedDirection) < 2.5f;
        this.currentLocation = displayLocation;

        if (insignificantMove && insignificantRotation) {
            updateTrackHistory(displayLocation);
            return displayLocation;
        }

        if (directionMarker == null) {
            updateDirectionMarker(displayLocation, resolvedDirection);
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(displayLocation, 19f));
            lastCameraLocation = displayLocation;
            lastCameraUpdateMs = SystemClock.elapsedRealtime();
        } else {
            updateDirectionMarker(displayLocation, resolvedDirection);
            long now = SystemClock.elapsedRealtime();
            boolean movedEnough = lastCameraLocation == null
                    || UtilFunctions.distanceBetweenPoints(lastCameraLocation, displayLocation)
                    >= CAMERA_RECENTER_DISTANCE_METERS;
            if (movedEnough && now - lastCameraUpdateMs >= CAMERA_RECENTER_INTERVAL_MS) {
                gMap.moveCamera(CameraUpdateFactory.newLatLng(displayLocation));
                lastCameraLocation = displayLocation;
                lastCameraUpdateMs = now;
            }
        }

        updateTrackHistory(displayLocation);
        return displayLocation;
    }

    public boolean isRenderSurfaceReady() {
        return gMap != null && polyline != null;
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

        com.google.android.gms.maps.model.Marker m = gMap.addMarker(new com.google.android.gms.maps.model.MarkerOptions()
                .position(position)
                .title("TP " + index)
                .snippet("t=" + timestampMs));

        if (m != null) {
            m.showInfoWindow(); // Show TP index immediately
            testPointMarkers.add(m);
        }
    }


    public void updateGNSS(@NonNull LatLng gnssLocation) {
        // GNSS is rendered as recent observation dots only.
    }

    public void clearGNSS() {
        // GNSS is rendered as recent observation dots only.
    }

    /**
     * Whether user is currently showing GNSS or not
     */
    public boolean isGnssEnabled() {
        return isGnssOn;
    }

    /**
     * Synchronises the rendered track container with the fusion lifecycle.
     *
     * <p>The actual visible history is maintained locally in this fragment so it can be split per
     * floor and rerouted around walls. The fusion history is therefore only used as a reset signal
     * when a session restarts.</p>
     */
    public void renderFusedHistory(@Nullable List<LatLng> fusedHistory) {
        if (polyline == null) return;
        if (fusedHistory == null) {
            clearTrackHistory();
            return;
        }
        if (fusedHistory.isEmpty()) {
            clearTrackHistory();
            return;
        }
    }

    /**
     * Renders the recent GNSS, WiFi and PDR observation dots used for debugging and validation.
     */
    public void renderObservationTails(@Nullable List<LatLng> gnssTrail,
                                       @Nullable List<LatLng> wifiTrail,
                                       @Nullable List<LatLng> pdrTrail) {
        if (gMap == null) return;
        redrawTailCircles(gnssTailCircles, gnssTrail, GNSS_TAIL_COLOR);
        redrawTailCircles(wifiTailCircles, wifiTrail, WIFI_TAIL_COLOR);
        redrawTailCircles(pdrTailCircles, pdrTrail, PDR_TAIL_COLOR);
    }

    private void setFloorControlsVisibility(int visibility) {
        floorLabel.setVisibility(visibility);
        if (previewFloorOnlyMode) {
            floorUpButton.setVisibility(View.GONE);
            floorDownButton.setVisibility(View.GONE);
        } else {
            floorUpButton.setVisibility(visibility);
            floorDownButton.setVisibility(visibility);
        }
        if (visibility == View.VISIBLE) {
            updateFloorLabel();
        }
    }

    /**
     * Updates the floor label text to reflect the current floor display name.
     */
    private void updateFloorLabel() {
        if (floorLabel == null) {
            return;
        }
        if (indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) {
            floorLabel.setText(indoorMapManager.getCurrentFloorDisplayName());
            return;
        }
        if (sensorFusion != null && sensorFusion.isIndoorContextActive()) {
            floorLabel.setText(sensorFusion.getCurrentFloorDisplayName());
        }
    }

    public void clearMapAndReset() {
        stopAutoFloor();
        if (polyline != null) {
            polyline.remove();
            polyline = null;
        }
        if (directionMarker != null) {
            directionMarker.remove();
            directionMarker = null;
        }
        currentLocation  = null;
        lastDirectionDegrees = 0f;
        lastCameraLocation = null;
        lastCameraUpdateMs = 0L;
        userTrackHistoryByFloor.clear();
        activeTrackFloor = Integer.MIN_VALUE;

        // Clear test point markers
        for (com.google.android.gms.maps.model.Marker m : testPointMarkers) {
            m.remove();
        }
        testPointMarkers.clear();
        clearCircles(gnssTailCircles);
        clearCircles(wifiTailCircles);
        clearCircles(pdrTailCircles);


        // Re-create empty polylines with your chosen colors
        if (gMap != null) {
            polyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.RED)
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
        indoorMapManager.setIndicationOfIndoorMap();
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
        if (autoFloorTask != null) {
            autoFloorHandler.removeCallbacks(autoFloorTask);
        }

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

        int resolvedFloor = sensorFusion.getPreferredDisplayLogicalFloor();
        indoorMapManager.setCurrentFloor(resolvedFloor, true);
        syncActiveTrackFloorWithDisplayedFloor();
        updateFloorLabel();
    }

    /**
     * Stops the periodic auto-floor evaluation and resets debounce state.
     */
    private void stopAutoFloor() {
        if (autoFloorHandler != null && autoFloorTask != null) {
            autoFloorHandler.removeCallbacks(autoFloorTask);
        }
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

        if (currentLocation == null) return;
        Integer resolvedFloor = sensorFusion.evaluateIndoorFloorChange(
                currentLocation,
                SystemClock.elapsedRealtime()
        );
        if (resolvedFloor != null) {
            indoorMapManager.setCurrentFloor(resolvedFloor, true);
            syncActiveTrackFloorWithDisplayedFloor();
            updateFloorLabel();
            return;
        }
        syncDisplayedFloor();
    }

    //endregion

    private void redrawTailCircles(List<Circle> targetCircles,
                                   @Nullable List<LatLng> points,
                                   int baseColor) {
        clearCircles(targetCircles);
        if (points == null || points.isEmpty() || gMap == null) {
            return;
        }

        int count = points.size();
        for (int i = 0; i < count; i++) {
            LatLng point = points.get(i);
            float ratio = (i + 1f) / count;
            int strokeColor = withAlpha(baseColor, (int) (80 + ratio * 140));
            int fillColor = withAlpha(baseColor, (int) (25 + ratio * 90));
            targetCircles.add(gMap.addCircle(new CircleOptions()
                    .center(point)
                    .radius(TAIL_RADIUS_METERS)
                    .strokeWidth(1.4f)
                    .strokeColor(strokeColor)
                    .fillColor(fillColor)
                    .zIndex(3f)));
        }
    }

    /**
     * Aligns the map floor with the best floor estimate currently exposed by SensorFusion.
     */
    private void syncDisplayedFloor() {
        if (sensorFusion == null || indoorMapManager == null) {
            return;
        }
        if (!indoorMapManager.getIsIndoorMapSet()) {
            return;
        }
        int preferredFloor = sensorFusion.getPreferredDisplayLogicalFloor();
        indoorMapManager.setCurrentFloor(preferredFloor, true);
        syncActiveTrackFloorWithDisplayedFloor();
        updateFloorLabel();
    }

    private void clearCircles(List<Circle> circles) {
        for (Circle circle : circles) {
            circle.remove();
        }
        circles.clear();
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    private void updateDirectionMarker(@NonNull LatLng position, float directionDegrees) {
        if (gMap == null || getContext() == null) {
            return;
        }

        if (directionMarker == null) {
            directionMarker = gMap.addMarker(new MarkerOptions()
                    .position(position)
                    .flat(true)
                    .anchor(0.5f, 0.5f)
                    .icon(BitmapDescriptorFactory.fromBitmap(getDirectionBitmap()))
                    .zIndex(6f));
        } else {
            directionMarker.setPosition(position);
        }
        directionMarker.setRotation(directionDegrees);
    }

    private void updateDirectionFromHistory(@Nullable List<LatLng> fusedHistory) {
        if (fusedHistory == null || fusedHistory.size() < 2 || directionMarker == null) {
            return;
        }

        LatLng last = fusedHistory.get(fusedHistory.size() - 1);
        for (int i = fusedHistory.size() - 2; i >= 0; i--) {
            LatLng candidate = fusedHistory.get(i);
            if (UtilFunctions.distanceBetweenPoints(candidate, last) >= MIN_DIRECTION_DISTANCE_METERS) {
                float trackDirection = computeHeadingDegrees(candidate, last);
                lastDirectionDegrees = trackDirection;
                directionMarker.setRotation(trackDirection);
                return;
            }
        }
    }

    private Bitmap getDirectionBitmap() {
        Bitmap base = UtilFunctions.getBitmapFromVector(requireContext(), R.drawable.ic_baseline_navigation_24);
        int sizePx = Math.max(18, Math.round(
                DIRECTION_MARKER_SIZE_DP * requireContext().getResources().getDisplayMetrics().density
        ));
        return Bitmap.createScaledBitmap(base, sizePx, sizePx, true);
    }

    /**
     * Resolves the marker direction shown on the map.
     *
     * <p>Movement direction takes priority whenever there is enough displacement between two
     * rendered locations. Sensor orientation is only used as a fallback when movement direction is
     * not observable yet.</p>
     */
    private float resolveDisplayDirection(@Nullable LatLng previousLocation,
                                          @NonNull LatLng currentLocation,
                                          float fallbackOrientationDegrees) {
        if (previousLocation != null
                && UtilFunctions.distanceBetweenPoints(previousLocation, currentLocation)
                >= MIN_DIRECTION_DISTANCE_METERS) {
            lastDirectionDegrees = computeHeadingDegrees(previousLocation, currentLocation);
            return lastDirectionDegrees;
        }

        if (!Float.isNaN(fallbackOrientationDegrees)) {
            lastDirectionDegrees = normalizeDegrees(fallbackOrientationDegrees);
            return lastDirectionDegrees;
        }

        if (directionMarker != null) {
            return lastDirectionDegrees;
        }

        return lastDirectionDegrees;
    }

    private float computeHeadingDegrees(@NonNull LatLng from, @NonNull LatLng to) {
        double deltaNorth = (to.latitude - from.latitude) * 111_111d;
        double deltaEast = (to.longitude - from.longitude)
                * 111_111d
                * Math.cos(Math.toRadians((from.latitude + to.latitude) * 0.5d));
        return normalizeDegrees((float) Math.toDegrees(Math.atan2(deltaEast, deltaNorth)));
    }

    private float normalizeDegrees(float degrees) {
        float value = degrees % 360f;
        if (value < 0f) {
            value += 360f;
        }
        return value;
    }

    /**
     * Updates the per-floor user history that drives the rendered red trajectory line.
     *
     * <p>Very small position changes replace the last point instead of extending the path, while
     * larger movements append either a direct point or a routed multi-point detour around walls.</p>
     */
    private void updateTrackHistory(@NonNull LatLng location) {
        if (polyline == null) {
            return;
        }

        List<LatLng> userTrackHistory = getActiveTrackHistory();

        if (userTrackHistory.isEmpty()) {
            userTrackHistory.add(location);
            refreshDisplayedTrackPolyline();
            return;
        }

        int lastIndex = userTrackHistory.size() - 1;
        LatLng lastPoint = userTrackHistory.get(lastIndex);
        List<LatLng> routedSegment = indoorMapManager == null
                ? Collections.emptyList()
                : indoorMapManager.buildLegalDisplaySegment(lastPoint, location);
        double distanceMeters = !routedSegment.isEmpty()
                ? computePathDistanceMeters(routedSegment)
                : UtilFunctions.distanceBetweenPoints(lastPoint, location);

        if (distanceMeters <= TRACK_REPLACE_DISTANCE_METERS) {
            userTrackHistory.set(lastIndex, location);
            refreshDisplayedTrackPolyline();
            return;
        }

        if (routedSegment.size() >= 3
                && distanceMeters >= TRACK_APPEND_DISTANCE_METERS
                && distanceMeters <= MAX_TRACK_APPEND_DISTANCE_METERS * 1.8d) {
            appendTrackSegment(userTrackHistory, routedSegment);
            refreshDisplayedTrackPolyline();
            return;
        }

        if (distanceMeters <= MAX_TRACK_APPEND_DISTANCE_METERS
                && distanceMeters >= TRACK_APPEND_DISTANCE_METERS) {
            userTrackHistory.add(location);
            trimTrackHistory(userTrackHistory);
            refreshDisplayedTrackPolyline();
            return;
        }

        userTrackHistory.set(lastIndex, location);
        refreshDisplayedTrackPolyline();
    }

    private void appendTrackSegment(@NonNull List<LatLng> userTrackHistory,
                                    @NonNull List<LatLng> routedSegment) {
        for (int i = 1; i < routedSegment.size(); i++) {
            LatLng point = routedSegment.get(i);
            LatLng lastPoint = userTrackHistory.get(userTrackHistory.size() - 1);
            if (UtilFunctions.distanceBetweenPoints(lastPoint, point) <= TRACK_REPLACE_DISTANCE_METERS) {
                userTrackHistory.set(userTrackHistory.size() - 1, point);
            } else {
                userTrackHistory.add(point);
            }
        }
        trimTrackHistory(userTrackHistory);
    }

    private void trimTrackHistory(@NonNull List<LatLng> userTrackHistory) {
        while (userTrackHistory.size() > MAX_TRACK_HISTORY_POINTS) {
            userTrackHistory.remove(0);
        }
    }

    private double computePathDistanceMeters(@NonNull List<LatLng> path) {
        double distanceMeters = 0d;
        for (int i = 1; i < path.size(); i++) {
            distanceMeters += UtilFunctions.distanceBetweenPoints(path.get(i - 1), path.get(i));
        }
        return distanceMeters;
    }

    private void clearTrackHistory() {
        userTrackHistoryByFloor.clear();
        activeTrackFloor = Integer.MIN_VALUE;
        if (polyline != null) {
            polyline.setPoints(Collections.emptyList());
        }
    }

    /**
     * Rebuilds the visible track polyline from the active floor history and reroutes it through
     * legal map segments when needed.
     */
    private void refreshDisplayedTrackPolyline() {
        if (polyline == null) {
            return;
        }
        List<LatLng> activeHistory = getActiveTrackHistory();
        List<LatLng> displayPath = indoorMapManager == null
                ? new ArrayList<>(activeHistory)
                : indoorMapManager.buildLegalDisplayPath(activeHistory);
        polyline.setPoints(displayPath);
    }

    private List<LatLng> getActiveTrackHistory() {
        int floorKey = activeTrackFloor != Integer.MIN_VALUE ? activeTrackFloor : 0;
        return userTrackHistoryByFloor.computeIfAbsent(floorKey, ignored -> new ArrayList<>());
    }

    /**
     * Switches the active local history buffer to the floor currently shown on the map.
     */
    private void syncActiveTrackFloorWithDisplayedFloor() {
        if (indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
            return;
        }
        int displayedFloor = indoorMapManager.getCurrentLogicalFloor();
        if (displayedFloor == activeTrackFloor) {
            return;
        }
        activeTrackFloor = displayedFloor;
        refreshDisplayedTrackPolyline();
        clearCircles(gnssTailCircles);
        clearCircles(wifiTailCircles);
        clearCircles(pdrTailCircles);
    }

    private float absoluteBearingDelta(float first, float second) {
        float delta = Math.abs(normalizeDegrees(first) - normalizeDegrees(second));
        return delta > 180f ? 360f - delta : delta;
    }

    private void applyOverlayMode() {
        if (mapControlsCard != null) {
            mapControlsCard.setVisibility(previewFloorOnlyMode ? View.GONE : View.VISIBLE);
        }
        if (previewFloorOnlyMode) {
            if (floorUpButton != null) {
                floorUpButton.setVisibility(View.GONE);
            }
            if (floorDownButton != null) {
                floorDownButton.setVisibility(View.GONE);
            }
        }
    }
}
