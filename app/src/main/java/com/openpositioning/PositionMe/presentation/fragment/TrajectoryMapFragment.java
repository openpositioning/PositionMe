package com.openpositioning.PositionMe.presentation.fragment;

import static com.openpositioning.PositionMe.BuildConstants.DEBUG;

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
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.OnMapReadyCallback;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.*;
import com.openpositioning.PositionMe.sensors.fusion.FusionManager;

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

    private Polyline polyline; // Polyline representing user's PDR movement path
    private boolean isGnssOn = true; // Tracks if GNSS tracking is enabled
    private boolean isWifiOn = true; // Tracks if WiFi marker is enabled
    private boolean isEstimatedOn = false; // Tracks if CalDB estimated marker is shown
    private Marker estimatedMarker; // Grey marker for KNN estimated position

    // Last N observation dots
    private static final int OBS_HISTORY_SIZE = 3;
    private final java.util.LinkedList<Circle> gnssObsDots = new java.util.LinkedList<>();
    private final java.util.LinkedList<Circle> wifiObsDots = new java.util.LinkedList<>();
    private static final int FUSED_OBS_HISTORY_SIZE = 10;
    private final java.util.LinkedList<Circle> fusedObsDots = new java.util.LinkedList<>();

    private Polyline gnssPolyline; // Polyline for GNSS path
    private LatLng lastGnssLocation = null; // Stores the last GNSS location

    // Fused position display
    private Polyline fusedPolyline; // Purple polyline for fused trajectory
    private Marker fusedMarker;     // Marker for current fused position
    private Marker wifiMarker;      // Green marker for latest WiFi fix
    private Circle fusedUncertaintyCircle; // Uncertainty radius around fused position
    private boolean fusionActive = false; // When true, fused marker is primary

    // Manual correction
    private Marker correctionDragMarker;  // Draggable marker for user correction
    private final List<Marker> correctionMarkers = new ArrayList<>(); // Confirmed corrections
    private CorrectionCallback correctionCallback;

    private LatLng pendingCameraPosition = null; // Stores pending camera movement
    private boolean hasPendingCameraMove = false; // Tracks if camera needs to move

    private IndoorMapManager indoorMapManager; // Manages indoor mapping
    private SensorFusion sensorFusion;

    // Auto-floor state
    private static final String TAG = "TrajectoryMapFragment";
    private static final long AUTO_FLOOR_DEBOUNCE_MS = 3000;
    private static final long AUTO_FLOOR_CHECK_INTERVAL_MS = 1000;
    private static final long WIFI_SEED_WINDOW_MS = 10000; // 10s WiFi refresh window
    private Handler autoFloorHandler;
    private Runnable autoFloorTask;
    private Runnable wifiSeedTimeoutTask; // clears WiFi callback after seed window
    private int lastCandidateFloor = Integer.MIN_VALUE;
    private long lastCandidateTime = 0;

    // Elevator/stairs distinction: track elevation at floor-change start
    private float elevationAtFloorChangeStart = Float.NaN;
    private long floorChangeStartTimeMs = 0;
    /** Elevation rate threshold (m/s). Above = elevator, below = stairs. */
    private static final float ELEVATOR_RATE_THRESHOLD = 0.5f;
    /** Last detected transition method: "elevator", "stairs", or "unknown". */
    private String lastTransitionMethod = "unknown";

    // UI
    private Spinner switchMapSpinner;
    private View mapInteractionBlocker;
    private View calibrationMapStyleList;
    private TextView mapStyleHybridOption;
    private TextView mapStyleNormalOption;
    private TextView mapStyleSatelliteOption;
    private SwitchMaterial compassSwitch;
    private SwitchMaterial weatherSwitch;
    private SwitchMaterial gnssSwitch;
    private SwitchMaterial wifiSwitch;
    private SwitchMaterial estimatedSwitch;
    private SwitchMaterial pdrPathSwitch;
    private SwitchMaterial fusedPathSwitch;
    private SwitchMaterial smoothPathSwitch;
    private SwitchMaterial autoFloorSwitch;
    private SwitchMaterial navigatingSwitch;

    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private TextView floorLabel;
    private MaterialCardView controlPanel;
    private ImageView toolbarToggle;
    private boolean toolbarExpanded = true;
    private boolean interactionLocked = false;
    private boolean pendingInitialCalibrationMode = false;
    private boolean pendingIndoorPositioningModeDefaults = false;
    private boolean showPdrPath = false;
    private boolean showFusedPath = false;
    private boolean showSmoothPath = false;
    private boolean navigatingMode = false;
    private Polyline smoothPolyline;

    // Smooth path state (EMA)
    // Smooth path state (Moving Average)
    private static final int SMOOTH_WINDOW = 40;
    private final java.util.LinkedList<LatLng> smoothBuffer = new java.util.LinkedList<>();
    private Polygon buildingPolygon;


    /** Callback for when a user confirms a manual position correction. */
    public interface CorrectionCallback {
        void onCorrectionConfirmed(LatLng correctedPosition);
    }

    /** Required empty public constructor. */
    public TrajectoryMapFragment() {}

    /** Sets the callback to receive manual position corrections. */
    public void setCorrectionCallback(CorrectionCallback callback) {
        this.correctionCallback = callback;
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
        mapInteractionBlocker = view.findViewById(R.id.mapInteractionBlocker);
        calibrationMapStyleList = view.findViewById(R.id.calibrationMapStyleList);
        mapStyleHybridOption = view.findViewById(R.id.mapStyleHybridOption);
        mapStyleNormalOption = view.findViewById(R.id.mapStyleNormalOption);
        mapStyleSatelliteOption = view.findViewById(R.id.mapStyleSatelliteOption);
        compassSwitch = view.findViewById(R.id.compassSwitch);
        weatherSwitch = view.findViewById(R.id.weatherSwitch);
        gnssSwitch = view.findViewById(R.id.gnssSwitch);
        wifiSwitch = view.findViewById(R.id.wifiSwitch);
        estimatedSwitch = view.findViewById(R.id.estimatedSwitch);
        pdrPathSwitch = view.findViewById(R.id.pdrPathSwitch);
        fusedPathSwitch = view.findViewById(R.id.fusedPathSwitch);
        smoothPathSwitch = view.findViewById(R.id.smoothPathSwitch);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        navigatingSwitch = view.findViewById(R.id.navigatingSwitch);
        floorUpButton = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        floorLabel = view.findViewById(R.id.floorLabel);
        controlPanel = view.findViewById(R.id.controlPanel);
        toolbarToggle = view.findViewById(R.id.toolbarToggle);

        mapStyleHybridOption.setOnClickListener(v -> applyMapTypeSelection(0));
        mapStyleNormalOption.setOnClickListener(v -> applyMapTypeSelection(1));
        mapStyleSatelliteOption.setOnClickListener(v -> applyMapTypeSelection(2));

        // Toolbar collapse/expand toggle
        toolbarToggle.setOnClickListener(v -> {
            if (interactionLocked) return;
            if (toolbarExpanded) {
                // Collapse: slide card left, then hide
                controlPanel.animate()
                        .translationX(-controlPanel.getWidth())
                        .alpha(0f)
                        .setDuration(250)
                        .withEndAction(() -> controlPanel.setVisibility(View.GONE))
                        .start();
                toolbarToggle.setImageResource(R.drawable.ic_chevron_right);
            } else {
                // Expand: show card, slide back in
                controlPanel.setVisibility(View.VISIBLE);
                controlPanel.setAlpha(0f);
                controlPanel.animate()
                        .translationX(0)
                        .alpha(0.85f)
                        .setDuration(250)
                        .start();
                toolbarToggle.setImageResource(R.drawable.ic_chevron_left);
            }
            toolbarExpanded = !toolbarExpanded;
        });

        // Compass toggle — controls CompassView in parent RecordingFragment
        compassSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (interactionLocked) { buttonView.setChecked(!isChecked); return; }
            Fragment parent = getParentFragment();
            if (parent != null && parent.getView() != null) {
                View compass = parent.getView().findViewById(R.id.compassView);
                if (compass != null) {
                    compass.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }
            }
        });

        // Weather toggle — controls WeatherView in parent RecordingFragment
        weatherSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (interactionLocked) { buttonView.setChecked(!isChecked); return; }
            Fragment parent = getParentFragment();
            if (parent instanceof RecordingFragment) {
                ((RecordingFragment) parent).setWeatherVisible(isChecked);
            }
        });

        // Path visibility toggles
        pdrPathSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (interactionLocked) { buttonView.setChecked(!isChecked); return; }
            showPdrPath = isChecked;
            if (polyline != null) polyline.setVisible(isChecked);
        });

        fusedPathSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (interactionLocked) { buttonView.setChecked(!isChecked); return; }
            showFusedPath = isChecked;
            if (fusedPolyline != null) fusedPolyline.setVisible(isChecked);
        });

        smoothPathSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (interactionLocked) { buttonView.setChecked(!isChecked); return; }
            showSmoothPath = isChecked;
            if (smoothPolyline != null) smoothPolyline.setVisible(isChecked);
        });

        // Navigating mode toggle — camera follows + rotates with heading
        navigatingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (interactionLocked) { buttonView.setChecked(!isChecked); return; }
            navigatingMode = isChecked;
            if (isChecked) {
                snapCameraToUser();
            } else if (gMap != null) {
                // Reset to north-up when switching to free mode
                CameraPosition northUp = new CameraPosition.Builder()
                        .target(gMap.getCameraPosition().target)
                        .zoom(gMap.getCameraPosition().zoom)
                        .bearing(0)
                        .tilt(0)
                        .build();
                gMap.animateCamera(CameraUpdateFactory.newCameraPosition(northUp));
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
                    setInteractionLocked(interactionLocked);

                    // If we had a pending camera move, apply it now
                    if (hasPendingCameraMove && pendingCameraPosition != null) {
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
                        if (indoorMapManager != null) {
                            indoorMapManager.setCurrentLocation(pendingCameraPosition);
                            if (DEBUG) Log.i(TAG, "[onMapReady] setCurrentLocation done, isIndoorMapSet="
                                    + indoorMapManager.getIsIndoorMapSet()
                                    + " pos=" + pendingCameraPosition
                                    + " floorplanBuildings=" + SensorFusion.getInstance().getFloorplanBuildings().size()
                                    + " pendingIndoor=" + pendingIndoorPositioningModeDefaults);
                            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
                        }
                        hasPendingCameraMove = false;
                        pendingCameraPosition = null;
                    }

                    drawBuildingPolygon();

                    if (DEBUG) Log.d("TrajectoryMapFragment", "onMapReady: Map is ready!");

                    if (pendingIndoorPositioningModeDefaults) {
                        applyIndoorPositioningModeDefaultsInternal();
                    }


                }
            });
        }

        // Map type spinner setup
        initMapTypeSpinner();

        // GNSS Switch
        gnssSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (interactionLocked) { buttonView.setChecked(!isChecked); return; }
            isGnssOn = isChecked;
            if (!isChecked && gnssMarker != null) {
                gnssMarker.remove();
                gnssMarker = null;
            }
        });

        // WiFi Switch
        wifiSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (interactionLocked) { buttonView.setChecked(!isChecked); return; }
            isWifiOn = isChecked;
            if (!isChecked && wifiMarker != null) {
                wifiMarker.remove();
                wifiMarker = null;
            }
        });

        // Estimated (CalDB KNN) switch
        estimatedSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (interactionLocked) { buttonView.setChecked(!isChecked); return; }
            isEstimatedOn = isChecked;
            if (!isChecked) {
                if (estimatedMarker != null) { estimatedMarker.remove(); estimatedMarker = null; }
                for (Circle c : fusedObsDots) c.remove();
                fusedObsDots.clear();
            }
        });

        // Auto-floor toggle: start/stop periodic floor evaluation
        sensorFusion = SensorFusion.getInstance();
        autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (interactionLocked) { compoundButton.setChecked(!isChecked); return; }
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

        // Apply pending calibration mode AFTER all listeners are registered,
        // so resetToolbarSwitchesToOff() properly syncs Java fields via listeners.
        if (pendingInitialCalibrationMode) {
            applyInitialCalibrationMode();
        }
    }

    @Override
    public void onDestroyView() {
        stopAutoFloor();
        if (gMap != null) {
            clearMapAndReset();
            gMap = null;
        }
        super.onDestroyView();
    }

    /**
     * Initializes map settings, indoor map manager, and empty polylines for tracking.
     *
     * @param map the GoogleMap instance to configure
     */
    private void initMapSettings(GoogleMap map) {
        // Basic map settings
        map.getUiSettings().setCompassEnabled(false);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        // Initialize indoor manager
        indoorMapManager = new IndoorMapManager(map);

        // Inject IndoorMapManager into FusionManager for stairs detection
        FusionManager fm = SensorFusion.getInstance().getFusionManager();
        if (fm != null) {
            fm.setIndoorMapManager(indoorMapManager);
        }

        // Initialize an empty polyline
        polyline = map.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(5f)
                .add() // start empty
        );
        polyline.setVisible(showPdrPath);

        // Fused trajectory in purple
        fusedPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.rgb(156, 39, 176)) // Material Purple
                .width(7f)
                .add()
        );
        fusedPolyline.setVisible(showFusedPath);

        // Smooth trajectory in green (placeholder — not yet populated)
        smoothPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.rgb(76, 175, 80)) // Material Green
                .width(7f)
                .add()
        );
        smoothPolyline.setVisible(showSmoothPath);

        // Long press → place a draggable correction marker
        map.setOnMapLongClickListener(latLng -> {
            if (interactionLocked) return;
            // Remove previous drag marker if it exists
            if (correctionDragMarker != null) {
                correctionDragMarker.remove();
            }
            correctionDragMarker = map.addMarker(new MarkerOptions()
                    .position(latLng)
                    .title("Drag to your actual position")
                    .snippet("Tap this marker to confirm")
                    .draggable(true)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                    .zIndex(20));
            if (correctionDragMarker != null) {
                correctionDragMarker.showInfoWindow();
            }
        });

        // Tap on the correction marker → confirm the correction
        map.setOnInfoWindowClickListener(marker -> {
            if (interactionLocked) return;
            if (marker.equals(correctionDragMarker)) {
                confirmCorrection(marker.getPosition());
                marker.hideInfoWindow();
                correctionDragMarker.remove();
                correctionDragMarker = null;
            }
        });
    }

    /**
     * Prepares the toolbar for the initial calibration window:
     * expands the panel, clears all toggle switches, and opens the map-style dropdown.
     */
    public void enterInitialCalibrationMode() {
        pendingInitialCalibrationMode = true;
        if (!isAdded() || getView() == null || switchMapSpinner == null) return;
        applyInitialCalibrationMode();
    }

    /** Applies default switch states for indoor positioning mode. */
    public void applyIndoorPositioningModeDefaults() {
        pendingIndoorPositioningModeDefaults = true;
        if (!isAdded() || getView() == null || switchMapSpinner == null || gMap == null) return;
        applyIndoorPositioningModeDefaultsInternal();
    }

    /**
     * Locks or unlocks map interactions during the calibration window.
     */
    public void setInteractionLocked(boolean locked) {
        interactionLocked = locked;

        if (locked) {
            expandToolbar();
        }

        if (mapInteractionBlocker != null) {
            mapInteractionBlocker.setVisibility(locked ? View.VISIBLE : View.GONE);
        }
        // Swap Spinner ↔ fixed list during calibration
        if (switchMapSpinner != null) {
            switchMapSpinner.setVisibility(locked ? View.GONE : View.VISIBLE);
        }
        if (calibrationMapStyleList != null) {
            calibrationMapStyleList.setVisibility(locked ? View.VISIBLE : View.GONE);
        }
        // Toolbar toggle disabled during calibration (keep visual appearance)
        if (toolbarToggle != null) {
            toolbarToggle.setEnabled(!locked);
        }
        // Floor buttons disabled during calibration
        if (floorUpButton != null) {
            floorUpButton.setEnabled(!locked);
        }
        if (floorDownButton != null) {
            floorDownButton.setEnabled(!locked);
        }
        // NOTE: switches are NOT disabled — that causes Material grey-out dimming.
        // Instead, switch listeners check interactionLocked to reject changes.

        if (gMap != null) {
            gMap.getUiSettings().setScrollGesturesEnabled(!locked);
            gMap.getUiSettings().setZoomGesturesEnabled(!locked);
            gMap.getUiSettings().setRotateGesturesEnabled(!locked);
            gMap.getUiSettings().setTiltGesturesEnabled(!locked);
            gMap.getUiSettings().setMapToolbarEnabled(!locked);
        }
    }

    private void resetToolbarSwitchesToDefaults() {
        // Temporarily lift lock so listeners don't revert the programmatic changes
        boolean wasLocked = interactionLocked;
        interactionLocked = false;

        // First 4: ON — toggle false→true to guarantee listener fires
        // even if the XML default is already checked="true"
        if (compassSwitch != null)  { compassSwitch.setChecked(false);  compassSwitch.setChecked(true); }
        if (weatherSwitch != null)  { weatherSwitch.setChecked(false);  weatherSwitch.setChecked(true); }
        if (gnssSwitch != null)     { gnssSwitch.setChecked(false);     gnssSwitch.setChecked(true); }
        if (wifiSwitch != null)     { wifiSwitch.setChecked(false);     wifiSwitch.setChecked(true); }
        // Rest: OFF
        if (estimatedSwitch != null) estimatedSwitch.setChecked(false);
        if (pdrPathSwitch != null) pdrPathSwitch.setChecked(false);
        if (fusedPathSwitch != null) fusedPathSwitch.setChecked(false);
        if (smoothPathSwitch != null) smoothPathSwitch.setChecked(false);
        if (autoFloorSwitch != null) autoFloorSwitch.setChecked(false);
        if (navigatingSwitch != null) navigatingSwitch.setChecked(false);

        interactionLocked = wasLocked;
    }

    /**
     * Restores the default switch states: first 4 ON, rest OFF.
     * Called after calibration countdown finishes.
     */
    public void restoreDefaultSwitchStates() {
        if (compassSwitch != null) compassSwitch.setChecked(true);
        if (weatherSwitch != null) weatherSwitch.setChecked(true);
        if (gnssSwitch != null) gnssSwitch.setChecked(true);
        if (wifiSwitch != null) wifiSwitch.setChecked(true);
    }

    private void applyInitialCalibrationMode() {
        expandToolbar();
        resetToolbarSwitchesToDefaults();
        // Swap: hide Spinner, show fixed list for calibration
        if (switchMapSpinner != null) {
            switchMapSpinner.setVisibility(View.GONE);
        }
        if (calibrationMapStyleList != null) {
            calibrationMapStyleList.setVisibility(View.VISIBLE);
        }
        highlightSelectedMapStyleOption(switchMapSpinner != null ? switchMapSpinner.getSelectedItemPosition() : 0);
    }

    private void applyIndoorPositioningModeDefaultsInternal() {
        if (gMap == null || indoorMapManager == null) {
            pendingIndoorPositioningModeDefaults = true;
            return;
        }
        pendingIndoorPositioningModeDefaults = false;
        pendingInitialCalibrationMode = false;

        // Temporarily lift interaction lock to set switches without listener revert,
        // then restore — so calibration lock is preserved.
        boolean wasLocked = interactionLocked;
        interactionLocked = false;

        // ON: Compass, Weather, Auto Floor, Smooth Path, Navigating
        if (compassSwitch != null)    { compassSwitch.setChecked(false);    compassSwitch.setChecked(true); }
        if (weatherSwitch != null)    { weatherSwitch.setChecked(false);    weatherSwitch.setChecked(true); }
        if (smoothPathSwitch != null) { smoothPathSwitch.setChecked(false); smoothPathSwitch.setChecked(true); }
        if (autoFloorSwitch != null)  { autoFloorSwitch.setChecked(false);  autoFloorSwitch.setChecked(true); }
        if (navigatingSwitch != null) { navigatingSwitch.setChecked(false); navigatingSwitch.setChecked(true); }
        // OFF: the rest
        if (gnssSwitch != null) gnssSwitch.setChecked(false);
        if (wifiSwitch != null) wifiSwitch.setChecked(false);
        if (estimatedSwitch != null) estimatedSwitch.setChecked(false);
        if (pdrPathSwitch != null) pdrPathSwitch.setChecked(false);
        if (fusedPathSwitch != null) fusedPathSwitch.setChecked(false);

        interactionLocked = wasLocked;

        // Normal map
        applyMapTypeSelection(1);

        // Force building setup — skip pointInPolygon detection
        String selectedBuilding = sensorFusion.getSelectedBuildingId();
        if (selectedBuilding != null && indoorMapManager != null) {
            boolean ok = indoorMapManager.forceSetBuilding(selectedBuilding);
            setFloorControlsVisibility(ok ? View.VISIBLE : View.GONE);
        }

        if (calibrationMapStyleList != null) {
            calibrationMapStyleList.setVisibility(View.GONE);
        }
        collapseToolbar();
    }

    private void expandToolbar() {
        if (controlPanel == null || toolbarToggle == null) return;

        controlPanel.animate().cancel();
        controlPanel.setVisibility(View.VISIBLE);
        controlPanel.setTranslationX(0f);
        controlPanel.setAlpha(0.85f);
        toolbarToggle.setImageResource(R.drawable.ic_chevron_left);
        toolbarExpanded = true;
    }

    private void collapseToolbar() {
        if (controlPanel == null || toolbarToggle == null) return;

        controlPanel.animate().cancel();
        controlPanel.setVisibility(View.GONE);
        controlPanel.setTranslationX(-controlPanel.getWidth());
        controlPanel.setAlpha(0f);
        toolbarToggle.setImageResource(R.drawable.ic_chevron_right);
        toolbarExpanded = false;
    }


    /** Initializes the map type spinner (Hybrid, Normal, Satellite). */
    private void initMapTypeSpinner() {
        if (switchMapSpinner == null) return;
        String[] maps = new String[]{
                getString(R.string.hybrid),
                getString(R.string.normal),
                getString(R.string.satellite)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                maps
        ) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                ((TextView) v).setGravity(android.view.Gravity.CENTER);
                return v;
            }

            @Override
            public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                if (v instanceof android.widget.CheckedTextView) {
                    ((android.widget.CheckedTextView) v).setGravity(android.view.Gravity.CENTER);
                    ((android.widget.CheckedTextView) v).setTextSize(
                            android.util.TypedValue.COMPLEX_UNIT_SP, 13);
                } else if (v instanceof TextView) {
                    ((TextView) v).setGravity(android.view.Gravity.CENTER);
                    ((TextView) v).setTextSize(
                            android.util.TypedValue.COMPLEX_UNIT_SP, 13);
                }
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        switchMapSpinner.setAdapter(adapter);

        // Match dropdown popup to toolbar visual width and shift left
        controlPanel.post(() -> {
            if (controlPanel != null && switchMapSpinner != null) {
                int scaledWidth = (int)(controlPanel.getWidth() * 0.80f);
                switchMapSpinner.setDropDownWidth(scaledWidth);
                switchMapSpinner.setDropDownHorizontalOffset(-20);
            }
        });
        // Semi-transparent popup background matching toolbar alpha
        android.graphics.drawable.ColorDrawable popupBg =
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.argb(
                        (int)(0.85f * 255), 255, 255, 255));
        switchMapSpinner.setPopupBackgroundDrawable(popupBg);

        switchMapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                applyMapTypeSelection(position);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void applyMapTypeSelection(int position) {
        if (switchMapSpinner != null && switchMapSpinner.getSelectedItemPosition() != position) {
            switchMapSpinner.setSelection(position);
        }

        if (gMap != null) {
            switch (position) {
                case 0:
                    gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                    gMap.setMapStyle(null); // clear custom style
                    break;
                case 1:
                    gMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                    // Apply light pink style to Normal map
                    try {
                        gMap.setMapStyle(com.google.android.gms.maps.model.MapStyleOptions
                                .loadRawResourceStyle(requireContext(), R.raw.map_style_pink));
                    } catch (Exception e) {
                        if (DEBUG) Log.w(TAG, "Failed to apply pink map style", e);
                    }
                    break;
                case 2:
                    gMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                    gMap.setMapStyle(null);
                    break;
                default:
                    break;
            }
        }

        highlightSelectedMapStyleOption(position);
    }

    private void highlightSelectedMapStyleOption(int selectedPosition) {
        updateMapStyleOptionAppearance(mapStyleHybridOption, selectedPosition == 0);
        updateMapStyleOptionAppearance(mapStyleNormalOption, selectedPosition == 1);
        updateMapStyleOptionAppearance(mapStyleSatelliteOption, selectedPosition == 2);
    }

    private void updateMapStyleOptionAppearance(@Nullable TextView option, boolean selected) {
        if (option == null) return;
        option.setBackgroundColor(selected
                ? Color.argb(35, 2, 103, 125)
                : Color.TRANSPARENT);
        option.setTextColor(selected
                ? requireContext().getColor(R.color.md_theme_primary)
                : requireContext().getColor(R.color.md_theme_onSurface));
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

        LatLng oldLocation = this.currentLocation;
        this.currentLocation = newLocation;

        // When fusion is active, the PDR marker is hidden — only draw the polyline.
        // The fused marker (purple) takes over as the primary position indicator.
        if (!fusionActive) {
            if (orientationMarker == null) {
                orientationMarker = gMap.addMarker(new MarkerOptions()
                        .position(newLocation)
                        .flat(true)
                        .title("PDR Position")
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(requireContext(),
                                        R.drawable.ic_baseline_navigation_24)))
                );
                // Always center on first position
                gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
            } else {
                orientationMarker.setPosition(newLocation);
                orientationMarker.setRotation(orientation);
                if (navigatingMode) {
                    CameraPosition pos = new CameraPosition.Builder()
                            .target(newLocation)
                            .zoom(gMap.getCameraPosition().zoom)
                            .bearing(orientation)
                            .tilt(0)
                            .build();
                    gMap.moveCamera(CameraUpdateFactory.newCameraPosition(pos));
                }
            }
        } else {
            // Hide PDR marker when fusion takes over
            if (orientationMarker != null) {
                orientationMarker.setVisible(false);
            }
        }

        // Always extend the PDR polyline (red) regardless of fusion state
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

        // Update indoor map overlay — only trigger building detection if not already set
        if (indoorMapManager != null) {
            if (!indoorMapManager.getIsIndoorMapSet()) {
                indoorMapManager.setCurrentLocation(newLocation);
            }
            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
        }
    }

    /** Called by RecordingFragment to signal that fusion is now providing the primary position. */
    public void setFusionActive(boolean active) {
        this.fusionActive = active;
        // Show PDR marker again if fusion is deactivated
        if (!active && orientationMarker != null) {
            orientationMarker.setVisible(true);
        }
    }

    /** Re-center + rotate camera to current user position and heading. */
    private void snapCameraToUser() {
        if (gMap == null) return;
        LatLng target = (fusionActive && fusedMarker != null)
                ? fusedMarker.getPosition()
                : (orientationMarker != null ? orientationMarker.getPosition() : null);
        if (target == null) return;

        float bearing = (fusionActive && fusedMarker != null)
                ? fusedMarker.getRotation()
                : (orientationMarker != null ? orientationMarker.getRotation() : 0f);

        CameraPosition pos = new CameraPosition.Builder()
                .target(target)
                .zoom(gMap.getCameraPosition().zoom)
                .bearing(bearing)
                .tilt(0)
                .build();
        gMap.animateCamera(CameraUpdateFactory.newCameraPosition(pos));
    }



    /**
     * Sets the initial camera position, moving immediately if the map is ready.
     *
     * @param startLocation the initial camera position to set
     */
    public void setInitialCameraPosition(@NonNull LatLng startLocation) {
        // If the map is already ready, move camera immediately
        if (gMap != null) {
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLocation, 19f));
            if (indoorMapManager != null) {
                indoorMapManager.setCurrentLocation(startLocation);
                setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
            }
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

    /** Returns the fused trajectory points, or PDR points if fused is empty. */
    public List<LatLng> getTrajectoryPoints() {
        if (fusedPolyline != null && !fusedPolyline.getPoints().isEmpty()) {
            return new ArrayList<>(fusedPolyline.getPoints());
        }
        if (polyline != null) {
            return new ArrayList<>(polyline.getPoints());
        }
        return new ArrayList<>();
    }

    /** Returns current building name for test point metadata. */
    public String getLocationBuilding() {
        if (indoorMapManager != null) return indoorMapManager.getCurrentBuildingName();
        return "Outdoor";
    }

    /** Returns current floor display name for test point metadata. */
    public String getLocationFloor() {
        if (indoorMapManager != null && indoorMapManager.getIsIndoorMapSet())
            return indoorMapManager.getCurrentFloorDisplayName();
        return "--";
    }

    /**
     * Returns a formatted location summary string for clipboard output.
     * Format: "BuildingName, FloorLabel, LocationType"
     * LocationType is determined by {@link #getLocationType()} (stub — returns "Unknown" for now).
     */
    public String getLocationSummary() {
        String building = "Outdoor";
        String floor = "--";
        if (indoorMapManager != null) {
            building = indoorMapManager.getCurrentBuildingName();
            if (indoorMapManager.getCurrentBuilding() != IndoorMapManager.BUILDING_NONE) {
                floor = indoorMapManager.getCurrentFloorDisplayName();
            }
        }
        String locType = getLocationType();
        return building + ", Floor: " + floor + ", " + locType;
    }

    /**
     * Stub for location-type detection (near elevator / stairs / hall).
     * TODO: Implement actual detection logic based on indoor map features.
     *
     * @return one of "Near Elevator", "Near Stairs", or "Hall"
     */
    public String getLocationType() {
        // TODO: implement proximity detection to elevator/stairs/hall areas
        return "Unknown";
    }

    /**
     * Add a numbered test point marker on the map.
     * Called by RecordingFragment when user presses the "Test Point" button.
     */
    public void addTestPointMarker(int index, long timestampMs, @NonNull LatLng position) {
        if (gMap == null) return;

        // Format local time HH:mm:ss
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
        String localTime = sdf.format(new java.util.Date(timestampMs));

        // Building and floor info
        String building = "Outdoor";
        String floor = "--";
        if (indoorMapManager != null) {
            building = indoorMapManager.getCurrentBuildingName();
            if (indoorMapManager.getIsIndoorMapSet()) {
                floor = indoorMapManager.getCurrentFloorDisplayName();
            }
        }

        Marker m = gMap.addMarker(new MarkerOptions()
                .position(position)
                .title("TP " + index + " | " + localTime)
                .snippet(building + ", Floor: " + floor));

        if (m != null) {
            m.showInfoWindow();
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
            gnssMarker = gMap.addMarker(new MarkerOptions()
                    .position(gnssLocation)
                    .title("GNSS Position")
                    .icon(BitmapDescriptorFactory
                            .defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
            lastGnssLocation = gnssLocation;
        } else {
            gnssMarker.setPosition(gnssLocation);
            lastGnssLocation = gnssLocation;
        }

        // Add GNSS observation dot (small cyan circle)
        addObsDot(gnssObsDots, gnssLocation, Color.rgb(0, 188, 212));
    }


    // ---- Fused position display ------------------------------------------------

    /**
     * Updates the fused position marker and polyline on the map.
     * The fused position is the particle-filter estimate combining PDR + WiFi + GNSS.
     *
     * @param fusedLocation  the fused LatLng
     * @param uncertainty    uncertainty radius in metres
     * @param orientation    heading in degrees for the marker
     */
    public void updateFusedLocation(@NonNull LatLng fusedLocation,
                                    double uncertainty, float orientation) {
        if (gMap == null) return;

        // Fused marker (purple navigation icon)
        if (fusedMarker == null) {
            fusedMarker = gMap.addMarker(new MarkerOptions()
                    .position(fusedLocation)
                    .flat(true)
                    .title("Fused Position")
                    .anchor(0.5f, 0.5f)
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_24)))
                    .zIndex(10));
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(fusedLocation, 19f));
        } else {
            fusedMarker.setPosition(fusedLocation);
            fusedMarker.setRotation(orientation);
        }

        // Uncertainty circle
        if (fusedUncertaintyCircle == null) {
            fusedUncertaintyCircle = gMap.addCircle(new CircleOptions()
                    .center(fusedLocation)
                    .radius(uncertainty)
                    .strokeColor(Color.argb(100, 156, 39, 176))
                    .fillColor(Color.argb(30, 156, 39, 176))
                    .strokeWidth(2f));
        } else {
            fusedUncertaintyCircle.setCenter(fusedLocation);
            fusedUncertaintyCircle.setRadius(uncertainty);
        }

        // Extend fused polyline
        if (fusedPolyline != null) {
            List<LatLng> points = new ArrayList<>(fusedPolyline.getPoints());
            points.add(fusedLocation);
            fusedPolyline.setPoints(points);
        }

        // Fused position observation dots (red, tied to Show Estimated)
        if (isEstimatedOn) {
            addObsDot(fusedObsDots, fusedLocation, Color.rgb(244, 67, 54), FUSED_OBS_HISTORY_SIZE);
        }

        // Smoothed trajectory (green line)
        if (smoothPolyline != null) {
            LatLng smoothed = smoothPosition(fusedLocation);
            List<LatLng> sPoints = new ArrayList<>(smoothPolyline.getPoints());
            sPoints.add(smoothed);
            smoothPolyline.setPoints(sPoints);
        }

        // Camera follows fused position only in navigating mode
        if (navigatingMode) {
            CameraPosition pos = new CameraPosition.Builder()
                    .target(fusedLocation)
                    .zoom(gMap.getCameraPosition().zoom)
                    .bearing(orientation)
                    .tilt(0)
                    .build();
            gMap.moveCamera(CameraUpdateFactory.newCameraPosition(pos));
        }

        // Update indoor map overlay — only trigger building detection if not already set,
        // to avoid resetting floor when position oscillates near building boundary.
        if (indoorMapManager != null) {
            if (!indoorMapManager.getIsIndoorMapSet()) {
                indoorMapManager.setCurrentLocation(fusedLocation);
            }
            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Updates the grey marker showing the CalDB (KNN) estimated position.
     *
     * @param estimatedLocation the estimated position from calibration database
     */
    public void updateEstimatedMarker(@NonNull LatLng estimatedLocation) {
        if (gMap == null || !isEstimatedOn) return;

        if (estimatedMarker == null) {
            estimatedMarker = gMap.addMarker(new MarkerOptions()
                    .position(estimatedLocation)
                    .title("Estimated (CalDB)")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            createGreyMarkerBitmap())));
        } else {
            estimatedMarker.setPosition(estimatedLocation);
        }
    }

    /** Returns whether the estimated (CalDB) marker is currently enabled. */
    public boolean isEstimatedEnabled() {
        return isEstimatedOn;
    }

    private LatLng smoothPosition(LatLng observation) {
        smoothBuffer.addLast(observation);
        if (smoothBuffer.size() > SMOOTH_WINDOW) {
            smoothBuffer.removeFirst();
        }
        double sumLat = 0, sumLng = 0;
        for (LatLng p : smoothBuffer) {
            sumLat += p.latitude;
            sumLng += p.longitude;
        }
        return new LatLng(sumLat / smoothBuffer.size(), sumLng / smoothBuffer.size());
    }

    /**
     * Adds a small circle dot to the observation history.
     * Removes the oldest if exceeding OBS_HISTORY_SIZE.
     */
    private void addObsDot(java.util.LinkedList<Circle> dots, LatLng position, int color) {
        addObsDot(dots, position, color, OBS_HISTORY_SIZE);
    }

    private void addObsDot(java.util.LinkedList<Circle> dots, LatLng position, int color, int maxSize) {
        if (gMap == null) return;
        // Skip if same position as last dot (avoid stacking)
        if (!dots.isEmpty()) {
            LatLng last = dots.getLast().getCenter();
            if (Math.abs(last.latitude - position.latitude) < 1e-7
                    && Math.abs(last.longitude - position.longitude) < 1e-7) {
                return;
            }
        }
        Circle dot = gMap.addCircle(new CircleOptions()
                .center(position)
                .radius(0.5)
                .strokeColor(Color.argb(200, Color.red(color), Color.green(color), Color.blue(color)))
                .strokeWidth(3)
                .fillColor(Color.argb(80, Color.red(color), Color.green(color), Color.blue(color))));
        dots.addLast(dot);
        if (dots.size() > maxSize) {
            dots.removeFirst().remove();
        }
    }

    private void clearObsDots() {
        for (Circle c : gnssObsDots) c.remove();
        gnssObsDots.clear();
        for (Circle c : wifiObsDots) c.remove();
        wifiObsDots.clear();
        for (Circle c : fusedObsDots) c.remove();
        fusedObsDots.clear();
    }

    private android.graphics.Bitmap createGreyMarkerBitmap() {
        int w = 70, h = 90;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h,
                android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(158, 158, 158)); // grey
        // Marker head (circle)
        canvas.drawCircle(w / 2f, 28f, 28f, paint);
        // Marker pin (triangle)
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(w / 2f - 18f, 50f);
        path.lineTo(w / 2f + 18f, 50f);
        path.lineTo(w / 2f, h);
        path.close();
        canvas.drawPath(path, paint);
        // White dot in center
        paint.setColor(Color.WHITE);
        canvas.drawCircle(w / 2f, 28f, 10f, paint);
        return bmp;
    }

    /**
     * Updates or creates a green marker showing the latest WiFi position fix.
     *
     * @param wifiLocation the WiFi-derived position
     */
    public void updateWifiMarker(@NonNull LatLng wifiLocation) {
        if (gMap == null || !isWifiOn) return;

        if (wifiMarker == null) {
            wifiMarker = gMap.addMarker(new MarkerOptions()
                    .position(wifiLocation)
                    .title("WiFi Position")
                    .icon(BitmapDescriptorFactory
                            .defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
        } else {
            wifiMarker.setPosition(wifiLocation);
        }

        // Add WiFi observation dot (small green circle)
        addObsDot(wifiObsDots, wifiLocation, Color.rgb(76, 175, 80));
    }

    // ---- Manual correction logic ------------------------------------------------

    /**
     * Confirms a correction point: places a permanent marker and fires the callback.
     */
    private void confirmCorrection(@NonNull LatLng position) {
        if (gMap == null) return;

        // Permanent orange circle marker
        int idx = correctionMarkers.size() + 1;
        Marker m = gMap.addMarker(new MarkerOptions()
                .position(position)
                .title("Correction #" + idx)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                .zIndex(15));
        if (m != null) correctionMarkers.add(m);

        // Notify the callback (RecordingFragment → FusionManager)
        if (correctionCallback != null) {
            correctionCallback.onCorrectionConfirmed(position);
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

    /** Remove WiFi marker if user toggles it off. */
    public void clearWiFi() {
        if (wifiMarker != null) {
            wifiMarker.remove();
            wifiMarker = null;
        }
    }

    /** Whether user is currently showing WiFi or not. */
    public boolean isWifiEnabled() {
        return isWifiOn;
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

    /** Removes all markers, polylines, and resets map state for a fresh recording. */
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

        // Clear correction markers
        if (correctionDragMarker != null) { correctionDragMarker.remove(); correctionDragMarker = null; }
        for (Marker m : correctionMarkers) m.remove();
        correctionMarkers.clear();

        // Clear fused elements
        fusionActive = false;
        if (fusedMarker != null) { fusedMarker.remove(); fusedMarker = null; }
        if (wifiMarker != null) { wifiMarker.remove(); wifiMarker = null; }
        if (estimatedMarker != null) { estimatedMarker.remove(); estimatedMarker = null; }
        smoothBuffer.clear();
        clearObsDots();
        if (fusedUncertaintyCircle != null) { fusedUncertaintyCircle.remove(); fusedUncertaintyCircle = null; }
        if (fusedPolyline != null) { fusedPolyline.remove(); fusedPolyline = null; }
        if (smoothPolyline != null) { smoothPolyline.remove(); smoothPolyline = null; }

        // Clear test point markers
        for (Marker m : testPointMarkers) {
            m.remove();
        }
        testPointMarkers.clear();

        // Re-create empty polylines with your chosen colors
        if (gMap != null) {
            polyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.RED)
                    .width(5f)
                    .add());
            fusedPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.rgb(156, 39, 176))
                    .width(7f)
                    .add());
            smoothPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.rgb(76, 175, 80))
                    .width(7f)
                    .add());
            smoothPolyline.setVisible(showSmoothPath);
        }
    }

    /** Draws building outline polygons on the map using API data or hard-coded fallbacks. */
    // Scale factor for buildings without API outline data.
    // <1.0 shrinks toward centroid to approximate 2D map footprint.
    private static final double NUCLEUS_SCALE  = 0.88;
    private static final double NKML_SCALE     = 0.88;
    private static final double FJB_SCALE      = 0.88;
    private static final double FARADAY_SCALE  = 0.88;

    /**
     * Scales polygon vertices toward their centroid.
     * @param vertices original vertices
     * @param factor   scale factor (<1.0 shrinks, >1.0 enlarges)
     * @return new array of scaled LatLng vertices
     */
    private LatLng[] scalePolygon(LatLng[] vertices, double factor) {
        double latSum = 0, lngSum = 0;
        for (LatLng v : vertices) {
            latSum += v.latitude;
            lngSum += v.longitude;
        }
        double cLat = latSum / vertices.length;
        double cLng = lngSum / vertices.length;

        LatLng[] scaled = new LatLng[vertices.length];
        for (int i = 0; i < vertices.length; i++) {
            scaled[i] = new LatLng(
                    cLat + (vertices[i].latitude - cLat) * factor,
                    cLng + (vertices[i].longitude - cLng) * factor);
        }
        return scaled;
    }

    /**
     * Looks up a building's outline polygon from the floorplan API cache.
     * @param apiName building name (e.g. "nucleus_building", "library")
     * @return outline points, or null if not available
     */
    private List<LatLng> getApiOutline(String apiName) {
        FloorplanApiClient.BuildingInfo info =
                sensorFusion.getFloorplanBuilding(apiName);
        if (info != null) {
            List<LatLng> outline = info.getOutlinePolygon();
            if (outline != null && outline.size() >= 3) return outline;
        }
        return null;
    }

    private void drawBuildingPolygon() {
        if (gMap == null) {
            Log.e("TrajectoryMapFragment", "GoogleMap is not ready");
            return;
        }

        // Remove old polygons
        if (buildingPolygon != null) {
            buildingPolygon.remove();
        }

        // --- Nucleus + Library connected shape (RED, zIndex=1) ---
        // API "nucleus_building" outline covers both Nucleus and Library as
        // one connected polygon. Draw it first in red as the base layer.
        List<LatLng> nucleusOutline = getApiOutline("nucleus_building");
        if (nucleusOutline != null) {
            PolygonOptions nucleusOpts = new PolygonOptions()
                    .addAll(nucleusOutline)
                    .strokeColor(Color.RED).strokeWidth(10f).zIndex(1);
            buildingPolygon = gMap.addPolygon(nucleusOpts);
            if (DEBUG) Log.d(TAG, "Nucleus polygon from API, vertices: " + nucleusOutline.size());
        }

        // --- Library (BLUE, zIndex=2) ---
        // Overlays on top of the red, so the Library portion shows blue.
        List<LatLng> libraryOutline = getApiOutline("library");
        if (libraryOutline != null) {
            PolygonOptions libOpts = new PolygonOptions()
                    .addAll(libraryOutline)
                    .strokeColor(Color.BLUE).strokeWidth(10f).zIndex(2);
            gMap.addPolygon(libOpts);
            if (DEBUG) Log.d(TAG, "Library polygon from API, vertices: " + libraryOutline.size());
        }

        // --- Murchison House (MAGENTA) ---
        List<LatLng> murchisonOutline = getApiOutline("murchison_house");
        if (murchisonOutline != null) {
            PolygonOptions murOpts = new PolygonOptions()
                    .addAll(murchisonOutline)
                    .strokeColor(Color.MAGENTA).strokeWidth(10f).zIndex(1);
            gMap.addPolygon(murOpts);
            if (DEBUG) Log.d(TAG, "Murchison polygon from API, vertices: " + murchisonOutline.size());
        }

        // --- FJB (GREEN): hardcoded + scaled (no API data) ---
        LatLng[] fjbRaw = {
                new LatLng(55.92269205199916, -3.1729563477188774),
                new LatLng(55.922822801570994, -3.172594249522305),
                new LatLng(55.92223512226413, -3.171921917547244),
                new LatLng(55.9221071265519, -3.1722813131202097)
        };
        LatLng[] fjb = scalePolygon(fjbRaw, FJB_SCALE);
        PolygonOptions fjbOpts = new PolygonOptions()
                .strokeColor(Color.GREEN).strokeWidth(10f).zIndex(1);
        for (LatLng v : fjb) fjbOpts.add(v);
        fjbOpts.add(fjb[0]);
        gMap.addPolygon(fjbOpts);

        // --- Faraday (YELLOW): hardcoded + scaled (no API data) ---
        LatLng[] faradayRaw = {
                new LatLng(55.92242866264128, -3.1719553662011815),
                new LatLng(55.9224966752294, -3.1717846714743474),
                new LatLng(55.922271383074154, -3.1715191463437162),
                new LatLng(55.92220124468304, -3.171705013935158)
        };
        LatLng[] faraday = scalePolygon(faradayRaw, FARADAY_SCALE);
        PolygonOptions faradayOpts = new PolygonOptions()
                .strokeColor(Color.YELLOW).strokeWidth(10f).zIndex(1);
        for (LatLng v : faraday) faradayOpts.add(v);
        faradayOpts.add(faraday[0]);
        gMap.addPolygon(faradayOpts);

        if (DEBUG) Log.d(TAG, "Building polygons drawn");
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

        // 1. Try immediate WiFi floor seed
        boolean wifiSeeded = false;
        if (sensorFusion != null && indoorMapManager != null
                && indoorMapManager.getIsIndoorMapSet()) {
            int wifiFloor = sensorFusion.getWifiFloor();
            if (wifiFloor >= 0) {
                // reseedFloor: resets PdrProcessing baro baseline + FusionManager + PF
                sensorFusion.reseedFloor(wifiFloor);
                indoorMapManager.setCurrentFloor(wifiFloor, true);
                updateFloorLabel();
                lastCandidateFloor = wifiFloor;
                lastCandidateTime = SystemClock.elapsedRealtime();
                wifiSeeded = true;
                if (DEBUG) Log.i(TAG, "[AutoFloor] Immediate WiFi seed floor=" + wifiFloor);
            }
        }
        if (!wifiSeeded) {
            // Fallback to PF/baro
            applyImmediateFloor();
        }

        // 2. Open 10s WiFi floor refresh window — accept fresh WiFi responses as re-seed
        if (sensorFusion != null) {
            sensorFusion.setWifiFloorCallback(floor -> {
                // Volley delivers on main thread, safe to update UI directly
                if (indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()
                        && floor >= 0) {
                    sensorFusion.reseedFloor(floor);
                    indoorMapManager.setCurrentFloor(floor, true);
                    updateFloorLabel();
                    lastCandidateFloor = floor;
                    lastCandidateTime = SystemClock.elapsedRealtime();
                    if (DEBUG) Log.i(TAG, "[AutoFloor] WiFi re-seed floor=" + floor);
                }
            });

            // 3. Close WiFi seed window after 10s
            wifiSeedTimeoutTask = () -> {
                sensorFusion.setWifiFloorCallback(null);
                wifiSeedTimeoutTask = null;
                if (DEBUG) Log.d(TAG, "[AutoFloor] WiFi seed window closed (10s)");
            };
            autoFloorHandler.postDelayed(wifiSeedTimeoutTask, WIFI_SEED_WINDOW_MS);
        }

        // 4. Start periodic baro-based evaluation
        autoFloorTask = new Runnable() {
            @Override
            public void run() {
                evaluateAutoFloor();
                autoFloorHandler.postDelayed(this, AUTO_FLOOR_CHECK_INTERVAL_MS);
            }
        };
        autoFloorHandler.post(autoFloorTask);
        if (DEBUG) Log.d(TAG, "Auto-floor started");
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
        // Priority: fused floor (PF consensus) > barometric floor (PdrProcessing)
        com.openpositioning.PositionMe.sensors.fusion.FusionManager fm =
                sensorFusion.getFusionManager();
        if (fm != null && fm.isActive()) {
            candidateFloor = fm.getFusedFloor();
        } else {
            // Use PdrProcessing's floor directly — it already accounts for
            // initialFloorOffset and hysteresis. Raw elevation/floorHeight
            // does not include the offset and gives wrong results after reseed.
            candidateFloor = sensorFusion.getBaroFloor();
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
        // Clear WiFi floor callback and pending timeout
        if (sensorFusion != null) {
            sensorFusion.setWifiFloorCallback(null);
        }
        if (autoFloorHandler != null) {
            if (autoFloorTask != null) {
                autoFloorHandler.removeCallbacks(autoFloorTask);
            }
            if (wifiSeedTimeoutTask != null) {
                autoFloorHandler.removeCallbacks(wifiSeedTimeoutTask);
                wifiSeedTimeoutTask = null;
            }
        }
        lastCandidateFloor = Integer.MIN_VALUE;
        lastCandidateTime = 0;
        if (DEBUG) Log.d(TAG, "Auto-floor stopped");
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

        // Use PF floor probability distribution for robust floor estimation
        com.openpositioning.PositionMe.sensors.fusion.FusionManager fm =
                sensorFusion.getFusionManager();
        if (fm != null && fm.isActive()) {
            double[] probs = fm.getFloorProbabilities();
            double topProb = 0, secondProb = 0;
            int topFloor = 0;
            for (int i = 0; i < probs.length; i++) {
                if (probs[i] > topProb) {
                    secondProb = topProb;
                    topProb = probs[i];
                    topFloor = i;
                } else if (probs[i] > secondProb) {
                    secondProb = probs[i];
                }
            }
            // Log floor posterior for debugging
            if (DEBUG) Log.d(TAG, String.format("[AutoFloor] source=PF topFloor=%d topProb=%.2f secondProb=%.2f fusedFloor=%d",
                    topFloor, topProb, secondProb, fm.getFusedFloor()));

            // Only accept if confident (>0.6 or margin >0.2)
            if (topProb > 0.6 || (topProb - secondProb) > 0.2) {
                candidateFloor = topFloor;
            } else {
                return; // not confident enough, keep current
            }
        } else {
            // Baro-only fallback: use PdrProcessing's floor directly —
            // it accounts for initialFloorOffset and hysteresis.
            candidateFloor = sensorFusion.getBaroFloor();
        }

        // Only allow floor change if near stairs or lift (within 5m)
        int currentFloorIdx = indoorMapManager.getCurrentFloor();
        if (candidateFloor != currentFloorIdx) {
            LatLng pos = currentLocation;
            if (pos != null && !indoorMapManager.isNearStairsOrLift(pos, 5.0)) {
                if (DEBUG) Log.d(TAG, String.format("[AutoFloor] BLOCKED floor change %d→%d (not near stairs/lift)",
                        currentFloorIdx, candidateFloor));
                return;
            }
        }

        // Debounce: require the same floor reading for AUTO_FLOOR_DEBOUNCE_MS
        long now = SystemClock.elapsedRealtime();
        if (candidateFloor != lastCandidateFloor) {
            lastCandidateFloor = candidateFloor;
            lastCandidateTime = now;
            // Snapshot elevation at the start of a potential floor transition
            elevationAtFloorChangeStart = sensorFusion.getElevation();
            floorChangeStartTimeMs = now;
            return;
        }

        if (now - lastCandidateTime >= AUTO_FLOOR_DEBOUNCE_MS) {
            // Classify transition as elevator or stairs (debug log only)
            classifyFloorTransition();

            indoorMapManager.setCurrentFloor(candidateFloor, true);
            updateFloorLabel();
            lastCandidateTime = now;
        }
    }

    /**
     * Classifies a confirmed floor transition as elevator or stairs based on
     * the barometric elevation change rate. Pure observation — does not alter
     * any floor detection or navigation logic.
     *
     * <p>Elevator: rapid vertical displacement (>{@value ELEVATOR_RATE_THRESHOLD} m/s).
     * Stairs: slower, gradual height change.</p>
     */
    private void classifyFloorTransition() {
        if (Float.isNaN(elevationAtFloorChangeStart) || sensorFusion == null) {
            lastTransitionMethod = "unknown";
            return;
        }

        float currentElevation = sensorFusion.getElevation();
        float elevDelta = Math.abs(currentElevation - elevationAtFloorChangeStart);
        long durationMs = SystemClock.elapsedRealtime() - floorChangeStartTimeMs;
        float durationSec = durationMs / 1000f;

        if (durationSec < 0.5f) {
            lastTransitionMethod = "unknown";
            return;
        }

        float rate = elevDelta / durationSec;
        lastTransitionMethod = (rate > ELEVATOR_RATE_THRESHOLD) ? "elevator" : "stairs";

        if (DEBUG) Log.i(TAG, String.format(
                "[FloorTransition] method=%s elevDelta=%.2fm duration=%.1fs rate=%.2fm/s",
                lastTransitionMethod, elevDelta, durationSec, rate));
    }

    //endregion
}
