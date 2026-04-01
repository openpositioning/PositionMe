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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;


/**
 * A fragment responsible for displaying a trajectory map using Google Maps.
 * 
 * The TrajectoryMapFragment provides a map interface for visualizing movement trajectories,
 * GNSS tracking, and indoor mapping. It manages map settings, user interactions, and real-time
 * updates to user location and GNSS markers.
 * 
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

    /** Sources for colour-coded position observations on the map. */
    public enum ObservationSource { GNSS, WIFI, PDR }
 
    // Observation marker colours
    private static final int COLOR_GNSS_OBS  = 0xFF2196F3; // blue  (unused directly; hue used below)
    private static final int COLOR_WIFI_OBS  = 0xFFFF9800; // orange
    private static final int COLOR_PDR_OBS   = 0xFFF44336; // red
    private static final int COLOR_FUSED     = 0xFF9C27B0; // purple – fused trajectory
 
    /** Weight applied to the newest sample in the EMA smoothing filter (0 < α ≤ 1). */
    private static final float EMA_ALPHA = 0.3f;
 
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

    // Fused trajectory – updated every 1 s or on movement
    private Polyline fusedTrajectoryPolyline;
    private LatLng lastFusedTrajectoryPoint = null;
 
    // Last 3 observation circle-markers per source; index 0 = newest (label "1")
    private static final int OBS_HISTORY = 3;
    private final Deque<Marker> gnssObsMarkers = new ArrayDeque<>();
    private final Deque<Marker> wifiObsMarkers = new ArrayDeque<>();
    private final Deque<Marker> pdrObsMarkers  = new ArrayDeque<>();
 
    // EMA smoothing state
    private boolean smoothingEnabled = false;
    private double smoothedLat = Double.NaN;
    private double smoothedLng = Double.NaN;
 
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
    private SwitchMaterial autoFloorSwitch;
    private SwitchMaterial smoothSwitch;
    private SwitchMaterial pdrPathSwitch;

    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private TextView floorLabel;
    private Button switchColorButton;
    private Polygon buildingPolygon;
    private android.widget.LinearLayout switchesPanel;
    private android.widget.ImageButton toggleControlsButton;


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
        smoothSwitch    = view.findViewById(R.id.smoothSwitch);
        pdrPathSwitch   = view.findViewById(R.id.pdrPathSwitch);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        floorLabel      = view.findViewById(R.id.floorLabel);
        switchColorButton = view.findViewById(R.id.lineColorButton);
        switchesPanel = view.findViewById(R.id.switchesPanel);
        toggleControlsButton = view.findViewById(R.id.toggleControlsButton);
        toggleControlsButton.setOnClickListener(v -> {
            if (switchesPanel.getVisibility() == View.VISIBLE) {
                switchesPanel.setVisibility(View.GONE);
                toggleControlsButton.setImageResource(android.R.drawable.arrow_down_float);
            } else {
                switchesPanel.setVisibility(View.VISIBLE);
                toggleControlsButton.setImageResource(android.R.drawable.arrow_up_float);
            }
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

        // Smoothing toggle
        if (smoothSwitch != null) {
            smoothSwitch.setOnCheckedChangeListener((btn, isChecked) ->
                    setSmoothingEnabled(isChecked));
        }

        // PDR path toggle — hidden by default, shown only when user enables it
        if (pdrPathSwitch != null) {
            pdrPathSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
                if (polyline != null) polyline.setVisible(isChecked);
            });
        }
 
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

        // Initialize an empty polyline — hidden by default, toggled via pdrPathSwitch
        polyline = map.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(5f)
                .visible(false)
                .add()
        );

        // GNSS path in blue
        gnssPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.BLUE)
                .width(5f)
                .add() // start empty
        );
 
        // Fused best-estimate trajectory in purple
        fusedTrajectoryPolyline = map.addPolyline(new PolylineOptions()
                .color(COLOR_FUSED)
                .width(8f)
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
     * Records the user’s current PDR-derived location and extends the red trajectory polyline.
     * Does NOT move the orientation marker — marker updates are handled exclusively by
     * {@link #updateFusedPosition} to avoid competing updates from different loops.
     *
     * @param newLocation The new PDR-derived location.
     * @param orientation Unused here; kept for API compatibility.
     */
    public void updateUserLocation(@NonNull LatLng newLocation, float orientation) {
        if (gMap == null) return;

        LatLng oldLocation = this.currentLocation;
        this.currentLocation = newLocation;

        // Extend the red PDR polyline
        if (polyline != null) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            if (oldLocation == null) {
                points.add(newLocation);
                polyline.setPoints(points);
            } else if (!oldLocation.equals(newLocation)) {
                points.add(newLocation);
                polyline.setPoints(points);
            }
        }

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
     * Updates the user's best-estimate position on the map.
     * When smoothing is enabled an Exponential Moving Average filter (α = {@value #EMA_ALPHA})
     * is applied before moving the orientation marker, producing visibly smoother motion.
     *
     * @param pos         Raw best-estimate position from the fusion / fallback pipeline.
     * @param orientation Device heading in degrees (clockwise from north).
     */
    public void updateFusedPosition(@NonNull LatLng pos, float orientation) {
        if (gMap == null) return;
 
        LatLng displayPos;
        if (smoothingEnabled) {
            if (Double.isNaN(smoothedLat)) {
                // Seed the filter on first call
                smoothedLat = pos.latitude;
                smoothedLng = pos.longitude;
            } else {
                smoothedLat = EMA_ALPHA * pos.latitude  + (1.0 - EMA_ALPHA) * smoothedLat;
                smoothedLng = EMA_ALPHA * pos.longitude + (1.0 - EMA_ALPHA) * smoothedLng;
            }
            displayPos = new LatLng(smoothedLat, smoothedLng);
        } else {
            displayPos = pos;
        }
 
        if (orientationMarker == null) {
            orientationMarker = gMap.addMarker(new MarkerOptions()
                    .position(displayPos)
                    .flat(true)
                    .title("Current Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_24))));
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(displayPos, 19f));
        } else {
            orientationMarker.setPosition(displayPos);
            orientationMarker.setRotation(orientation);
            gMap.moveCamera(CameraUpdateFactory.newLatLng(displayPos));
        }
 
        currentLocation = displayPos;
 
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(displayPos);
            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
        }
    }
 
    /**
     * Appends a point to the purple fused-trajectory polyline.
     * Only adds a point when the position has actually changed to avoid duplicate vertices.
     *
     * @param pos New fused position to record.
     */
    public void updateFusedTrajectory(@NonNull LatLng pos) {
        if (gMap == null || fusedTrajectoryPolyline == null) return;
        if (pos.equals(lastFusedTrajectoryPoint)) return;
 
        List<LatLng> points = new ArrayList<>(fusedTrajectoryPolyline.getPoints());
        points.add(pos);
        fusedTrajectoryPolyline.setPoints(points);
        lastFusedTrajectoryPoint = pos;
    }
 
    // Show a numbered circle marker for the given source, keeping last 3 positions.
    // Circle 1 = latest, 3 = oldest.
    public void addObservationMarker(@NonNull LatLng pos, @NonNull ObservationSource source) {
        if (gMap == null) return;

        Deque<Marker> deque;
        int solidColor;
        String title;
        switch (source) {
            case GNSS:
                deque = gnssObsMarkers;
                solidColor = COLOR_GNSS_OBS;
                title = "GNSS";
                break;
            case WIFI:
                deque = wifiObsMarkers;
                solidColor = COLOR_WIFI_OBS;
                title = "WiFi";
                break;
            default: // PDR
                deque = pdrObsMarkers;
                solidColor = COLOR_PDR_OBS;
                title = "PDR";
                break;
        }

        if (deque.size() >= OBS_HISTORY) {
            deque.removeLast().remove();
        }

        // Bump labels on existing markers
        int newLabel = deque.size() + 1;
        for (Marker m : deque) {
            m.setIcon(BitmapDescriptorFactory.fromBitmap(makeObsCircleBitmap(solidColor, newLabel)));
            newLabel--;
        }

        Marker newest = gMap.addMarker(new MarkerOptions()
                .position(pos)
                .title(title)
                .anchor(0.5f, 0.5f)
                .zIndex(1f)
                .icon(BitmapDescriptorFactory.fromBitmap(makeObsCircleBitmap(solidColor, 1))));
        deque.addFirst(newest);
    }

    // Creates a semi-transparent filled circle bitmap with a number in the centre
    private Bitmap makeObsCircleBitmap(int solidColor, int number) {
        int size = 64;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        int fillColor = Color.argb(160, Color.red(solidColor), Color.green(solidColor), Color.blue(solidColor));
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(fillColor);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, fillPaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(size * 0.45f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        float textY = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(String.valueOf(number), size / 2f, textY, textPaint);

        return bmp;
    }
 
    /**
     * Enables or disables the EMA position smoothing filter.
     * Disabling resets the filter state so the next call to
     * {@link #updateFusedPosition} re-seeds it from the raw position.
     *
     * @param enabled {@code true} to enable smoothing.
     */
    public void setSmoothingEnabled(boolean enabled) {
        this.smoothingEnabled = enabled;
        if (!enabled) {
            smoothedLat = Double.NaN;
            smoothedLng = Double.NaN;
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

        // Clear all per-source observation circle-markers
        for (Marker m : gnssObsMarkers) m.remove();
        gnssObsMarkers.clear();
        for (Marker m : wifiObsMarkers) m.remove();
        wifiObsMarkers.clear();
        for (Marker m : pdrObsMarkers) m.remove();
        pdrObsMarkers.clear();
 
        // Clear fused trajectory
        if (fusedTrajectoryPolyline != null) {
            fusedTrajectoryPolyline.remove();
            fusedTrajectoryPolyline = null;
        }
        lastFusedTrajectoryPoint = null;
 
        // Reset EMA filter
        smoothedLat = Double.NaN;
        smoothedLng = Double.NaN;

        // Re-create empty polylines with your chosen colors
        if (gMap != null) {
            polyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.RED)
                    .width(5f)
                    .visible(pdrPathSwitch != null && pdrPathSwitch.isChecked())
                    .add());
            gnssPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.BLUE)
                    .width(5f)
                    .add());
            fusedTrajectoryPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(COLOR_FUSED)
                    .width(8f)
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
        if (sensorFusion.getLatLngWifiPositioning() != null) {
            candidateFloor = sensorFusion.getWifiFloor();
        } else {
            float elevation = sensorFusion.getElevation();
            float floorHeight = indoorMapManager.getFloorHeight();
            if (floorHeight <= 0) return;
            candidateFloor = Math.round(elevation / floorHeight);
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

        int candidateFloor;

        // Priority 1: WiFi-based floor (only if WiFi positioning has returned data)
        if (sensorFusion.getLatLngWifiPositioning() != null) {
            candidateFloor = sensorFusion.getWifiFloor();
        } else {
            // Fallback: barometric elevation estimate
            float elevation = sensorFusion.getElevation();
            float floorHeight = indoorMapManager.getFloorHeight();
            if (floorHeight <= 0) return;
            candidateFloor = Math.round(elevation / floorHeight);
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
}
