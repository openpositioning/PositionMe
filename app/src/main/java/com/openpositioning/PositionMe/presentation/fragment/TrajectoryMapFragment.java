package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Color;
import android.os.Bundle;
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
import android.widget.Toast;

import com.google.android.material.switchmaterial.SwitchMaterial;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.OnMapReadyCallback;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.BuildingPolygon;
import com.openpositioning.PositionMe.utils.GeometryUtils;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.*;

import java.util.ArrayList;
import java.util.List;

// TrajectoryMapFragment
// Adapted for hybrid indoor map manager.
// Fixed: Auto Floor Offset Logic.
public class TrajectoryMapFragment extends Fragment {

    public interface OnVenueSelectedListener {
        void onVenueSelected(String buildingId, String venueName);
    }

    private GoogleMap gMap;
    private LatLng currentLocation;
    private Marker orientationMarker;
    private Marker gnssMarker;
    private Marker wifiMarker;
    private Polyline pdrPolyline;
    private Polyline fusedPolyline;
    private boolean isRed = true;
    private boolean isGnssOn = false;
    private boolean isWifiOn = false;

    private Polyline gnssPolyline;
    private Polyline wifiPolyline;
    private LatLng lastGnssLocation = null;
    private LatLng lastWifiLocation = null;

    // Filtering and smoothing parameters for fused trajectory
    private LatLng lastFusedSmoothedLocation = null;
    private static final double FUSED_SMOOTH_ALPHA = 0.70; // 0-1.0
    private static final double DISPLAY_JITTER_DEADBAND_METERS = 1.0;
    private static final double DISPLAY_MAX_JUMP_INDOOR_METERS = 3.0;
    private static final double DISPLAY_MAX_JUMP_OUTDOOR_METERS = 3.0;
    private static final double DISPLAY_SMOOTH_ALPHA_INDOOR = 0.30;
    private static final double DISPLAY_SMOOTH_ALPHA_OUTDOOR = 0.28;
    private static final double DISPLAY_MAX_WALKING_SPEED_MPS = 2.0;
    private static final double DISPLAY_SHORT_WINDOW_MAX_METERS = 1.5;
    private static final double DISPLAY_MIN_TIME_DELTA_SECONDS = 0.01;

    // Keep recent N points for color-coded absolute position updates display (rendering colored dots)
    private static final int RECENT_POINTS_N = 5;  // For trajectory
    private final List<Circle> recentCirclesBuffer = new ArrayList<>();
    
    // GNSS/WiFi history tracking (max 5 points each).
    private static final int MAX_GNSS_WIFI_HISTORY = 5;
    private final List<Circle> gnssHistoryCircles = new ArrayList<>();
    private final List<Circle> wifiHistoryCircles = new ArrayList<>();
    private LatLng lastGnssPositionForHistory = null;
    private LatLng lastWifiPositionForHistory = null;

    private LatLng pendingCameraPosition = null;
    private boolean hasPendingCameraMove = false;
    private LatLng pendingReplayStartPosition = null;
    private long lastDisplayFilterTimestampMs = -1L;
    private Marker replayStartMarker;

    private IndoorMapManager indoorMapManager;
    private SensorFusion sensorFusion;
    private OnVenueSelectedListener venueSelectedListener;

    private List<Marker> manualMarkers = new ArrayList<>();

    // Track if arrow is inside a building for auto-enable indoor map feature
    private boolean isArrowInsideBuilding = false;

    // Calibration offset that maps the fused floor estimate onto the building floor index.
    private static final int AUTO_FLOOR_REQUIRED_CONFIRMATIONS = 5;
    private static final long AUTO_FLOOR_SWITCH_COOLDOWN_MS = 7000L;
    // Hard-coded barometric floor bands for auto map after pressing Start.
    private static final float FLOOR_BAND_B1_MAX_METERS = 128.5f;
    private static final float FLOOR_BAND_GF_MAX_METERS = 132.75f;
    private static final float FLOOR_BAND_F1_MAX_METERS = 137.5f;
    private static final float FLOOR_BAND_F2_MAX_METERS = 142.7f;
    private int autoFloorOffset = 0;
    private int pendingAutoFloorTarget = Integer.MIN_VALUE;
    private int pendingAutoFloorCount = 0;
    private long lastAutoFloorSwitchTimestampMs = 0L;

    // UI Controls
    private Spinner switchMapSpinner;
    private SwitchMaterial gnssSwitch;
    private SwitchMaterial pdrSwitch;
    private SwitchMaterial wifiSwitch;
    private SwitchMaterial indoorMapSwitch;
    private SwitchMaterial autoFloorSwitch;
    private com.google.android.material.button.MaterialButton mapControlsToggleButton;
    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;
    private TextView floorTextView;
    private View floorControlsContainer;
    private View buildingInfoCard;
    private TextView buildingNameText;
    private View mapControlsContent;
    private boolean suppressAutoFloorCallback = false;
    private boolean mapControlsExpanded = false;
    private boolean autoFloorProximityManaged = false;
    private boolean wasNearVerticalTransition = false;
    private boolean autoFloorArmedByTransitionEntry = false;
    private boolean forceHardcodedBandsAfterStart = false;
    private boolean isPdrOn = true;
    private boolean pendingInitialFloorDetection = false;

    public TrajectoryMapFragment() {
        // Required empty public constructor
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_trajectory_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        sensorFusion = SensorFusion.getInstance();

        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        pdrSwitch       = view.findViewById(R.id.pdrSwitch);
        gnssSwitch      = view.findViewById(R.id.gnssSwitch);
        wifiSwitch      = view.findViewById(R.id.wifiSwitch);
        indoorMapSwitch = view.findViewById(R.id.indoorMapSwitch);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        mapControlsToggleButton = view.findViewById(R.id.mapControlsToggleButton);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);
        floorTextView   = view.findViewById(R.id.floorTextView);
        floorControlsContainer = view.findViewById(R.id.floorControlsContainer);
        buildingInfoCard = view.findViewById(R.id.buildingInfoCard);
        buildingNameText = view.findViewById(R.id.buildingNameText);
        mapControlsContent = view.findViewById(R.id.mapControlsContent);

        // Sync runtime flags with switch default state from XML.
        if (pdrSwitch != null) {
            isPdrOn = pdrSwitch.isChecked();
        }
        if (gnssSwitch != null) {
            isGnssOn = gnssSwitch.isChecked();
        }
        if (wifiSwitch != null) {
            isWifiOn = wifiSwitch.isChecked();
        }

        setFloorControlsVisibility(View.GONE);
        updateMapToggleState();
        applyMapControlsExpandedState();
        if (mapControlsToggleButton != null) {
            mapControlsToggleButton.setOnClickListener(v -> {
                mapControlsExpanded = !mapControlsExpanded;
                applyMapControlsExpandedState();
            });
        }

        // Initialize IndoorMapManager (will be set properly in onMapReady)
        indoorMapManager = new IndoorMapManager(null, getContext());
        indoorMapManager.setOnFloorDataLoadedListener((hasData) -> {
            // Update floor display when floor data is loaded from API
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (hasData) {
                        setFloorControlsVisibility(View.VISIBLE);
                        syncIndoorFloorReference();
                        updateFloorDisplay();
                        updateMapToggleState();
                        performInitialFloorDetectionIfPending();

                        // Now send venue selection notification
                        String buildingId = indoorMapManager.getSelectedBuildingId();
                        String venueName = indoorMapManager.getSelectedBuildingName();
                        if (venueSelectedListener != null && buildingId != null && venueName != null) {
                            venueSelectedListener.onVenueSelected(buildingId, venueName);
                        }
                    } else {
                        // No floor data for this building - hide floor controls
                        setFloorControlsVisibility(View.GONE);
                        setAutoFloorChecked(false);
                        updateMapToggleState();
                    }
                });
            }
        });

        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.trajectoryMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(new OnMapReadyCallback() {
                @Override
                public void onMapReady(@NonNull GoogleMap googleMap) {
                    gMap = googleMap;
                    initMapSettings(gMap);

                    // Hybrid strategy: local fallback plus API loading.

                    // Local fallback: immediately load Murchison/Nucleus/Library
                    if (indoorMapManager != null) {
                        indoorMapManager.addFallbackBuildings();
                    }

                    // Camera movement
                    LatLng kbCampus = new LatLng(55.9230, -3.1750);
                    if (!hasPendingCameraMove) {
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(kbCampus, 17.5f));
                    }

                    // Click interaction
                    gMap.setOnPolygonClickListener(new GoogleMap.OnPolygonClickListener() {
                        @Override
                        public void onPolygonClick(@NonNull Polygon polygon) {
                            if (indoorMapManager != null) {
                                boolean handled = indoorMapManager.onPolygonClick(polygon);
                                if (handled) {
                                    if (indoorMapSwitch != null && !indoorMapSwitch.isChecked()) {
                                        indoorMapSwitch.setChecked(true);
                                    }
                                    // Don't show floor controls yet - wait for API callback
                                    // Just show building name immediately
                                    String venueName = indoorMapManager.getSelectedBuildingName();
                                    if (buildingInfoCard != null && buildingNameText != null && venueName != null) {
                                        buildingNameText.setText(venueName);
                                        buildingInfoCard.setVisibility(View.VISIBLE);
                                    }

                                    updateMapToggleState();

                                    // Venue selection notification will be sent in API callback
                                }
                            }
                        }
                    });

                    if (hasPendingCameraMove && pendingCameraPosition != null) {
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
                        // Add a marker at the pending initial position
                        if (orientationMarker == null) {
                            orientationMarker = gMap.addMarker(new MarkerOptions()
                                    .position(pendingCameraPosition)
                                    .flat(true)
                                    .anchor(0.5f, 0.5f)
                                    .rotation(resolveMarkerHeadingDegrees(0f))
                                    .title("Start Position")
                                    .icon(BitmapDescriptorFactory.fromBitmap(
                                            UtilFunctions.getBitmapFromVector(requireContext(),
                                                    R.drawable.ic_baseline_navigation_24))));
                        }
                        currentLocation = pendingCameraPosition;
                        hasPendingCameraMove = false;
                        pendingCameraPosition = null;
                    }

                    if (pendingReplayStartPosition != null) {
                        setReplayStartMarker(pendingReplayStartPosition);
                        pendingReplayStartPosition = null;
                    }

                    updateMapToggleState();
                    Log.d("TrajectoryMapFragment", "Map Ready: Hybrid Mode.");
                }
            });
        }

        initMapTypeSpinner();

        if (pdrSwitch != null) {
            pdrSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                isPdrOn = isChecked;
                if (pdrPolyline != null) {
                    pdrPolyline.setVisible(isChecked);
                }
                if (switchColorButton != null) {
                    switchColorButton.setEnabled(isChecked);
                    switchColorButton.setAlpha(isChecked ? 1.0f : 0.45f);
                }
            });
        }

        gnssSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isGnssOn = isChecked;
            if (!isChecked && gnssMarker != null) {
                gnssMarker.remove();
                gnssMarker = null;
            }
            if (gnssPolyline != null) {
                gnssPolyline.setPoints(new ArrayList<>());
                gnssPolyline.setVisible(false);
            } else if (isChecked) {
                // When GNSS is turned on, immediately show current GNSS position
                if (sensorFusion != null && gMap != null) {
                    float[] gnssCoords = sensorFusion.getGNSSLatitude(false);
                    if (gnssCoords[0] != 0 || gnssCoords[1] != 0) {
                        LatLng gnssLocation = new LatLng(gnssCoords[0], gnssCoords[1]);
                        updateGNSS(gnssLocation);
                    }
                }
            }
        });

        if (wifiSwitch != null) {
            wifiSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                isWifiOn = isChecked;
                if (wifiPolyline != null) {
                    wifiPolyline.setPoints(new ArrayList<>());
                    wifiPolyline.setVisible(false);
                }
                if (!isChecked && wifiMarker != null) {
                    wifiMarker.remove();
                    wifiMarker = null;
                } else if (isChecked && sensorFusion != null && gMap != null) {
                    float[] wifiCoords = sensorFusion.getSensorValueMap().get(com.openpositioning.PositionMe.sensors.SensorTypes.WIFI);
                    if (wifiCoords != null && (wifiCoords[0] != 0 || wifiCoords[1] != 0)) {
                        updateWifi(new LatLng(wifiCoords[0], wifiCoords[1]));
                    }
                }
            });
        }

        indoorMapSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (indoorMapManager != null) {
                indoorMapManager.setIndoorMapVisible(isChecked);
                if (isChecked) {
                    ensureIndoorBuildingSelection();
                }
                if (!isChecked) {
                    setAutoFloorChecked(false);
                }
                updateMapToggleState();
            }
        });

        switchColorButton.setOnClickListener(v -> {
            if (pdrPolyline != null) {
                if (isRed) {
                    switchColorButton.setBackgroundColor(Color.BLACK);
                    pdrPolyline.setColor(Color.BLACK);
                    isRed = false;
                } else {
                    switchColorButton.setBackgroundColor(Color.RED);
                    pdrPolyline.setColor(Color.RED);
                    isRed = true;
                }
            }
        });

        autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (suppressAutoFloorCallback) {
                return;
            }

            if (!canUseAutoFloor()) {
                if (isChecked) {
                    Toast.makeText(getContext(), "Turn on Indoor Map in a mapped building first", Toast.LENGTH_SHORT).show();
                    setAutoFloorChecked(false);
                }
                return;
            }

            if (isChecked && indoorMapManager != null && sensorFusion != null) {
                syncIndoorFloorReference();
                pendingInitialFloorDetection = true;
                performInitialFloorDetectionIfPending();

                int currentManualFloor = indoorMapManager.getCurrentFloor();
                int estimatedFloor = resolveEstimatedFloorIndexForAutoMap();
                autoFloorOffset = currentManualFloor - estimatedFloor;
                clearPendingAutoFloor();

                Log.d("AutoFloor", "Enabled using fused floor estimate. Calibration Offset: " + autoFloorOffset);
                Toast.makeText(getContext(), "Auto Floor Initialized", Toast.LENGTH_SHORT).show();
            } else {
                autoFloorOffset = 0;
                clearPendingAutoFloor();
            }
        });

        floorUpButton.setOnClickListener(v -> {
            setAutoFloorChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
                syncIndoorFloorReference();
                updateFloorDisplay();
            }
        });

        floorDownButton.setOnClickListener(v -> {
            setAutoFloorChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
                syncIndoorFloorReference();
                updateFloorDisplay();
            }
        });
    }

    public void addMarkerToMap(LatLng location) {
        if (gMap != null && location != null) {
            Marker marker = gMap.addMarker(new MarkerOptions()
                    .position(location)
                    .title("Manual Marker")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW)));
            if (marker != null) {
                manualMarkers.add(marker);
            }
        }
    }

    private void initMapSettings(GoogleMap map) {
        map.getUiSettings().setCompassEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        // Initialize Manager with Context
        indoorMapManager = new IndoorMapManager(map, requireContext());
        indoorMapManager.setOnFloorDataLoadedListener((hasData) -> {
            // Update floor display when floor data is loaded from API
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (hasData) {
                        setFloorControlsVisibility(View.VISIBLE);
                        syncIndoorFloorReference();
                        updateFloorDisplay();
                        updateMapToggleState();
                        performInitialFloorDetectionIfPending();

                        // Now send venue selection notification
                        String buildingId = indoorMapManager.getSelectedBuildingId();
                        String venueName = indoorMapManager.getSelectedBuildingName();
                        if (venueSelectedListener != null && buildingId != null && venueName != null) {
                            venueSelectedListener.onVenueSelected(buildingId, venueName);
                        }
                    } else {
                        // No floor data for this building - hide floor controls
                        setFloorControlsVisibility(View.GONE);
                        setAutoFloorChecked(false);
                        updateMapToggleState();
                    }
                });
            }
        });

        pdrPolyline = map.addPolyline(new PolylineOptions().color(Color.RED).width(5f));
        pdrPolyline.setVisible(isPdrOn);
        fusedPolyline = map.addPolyline(new PolylineOptions().color(Color.GREEN).width(5f));
        gnssPolyline = map.addPolyline(new PolylineOptions().color(Color.BLUE).width(5f));
        gnssPolyline.setVisible(false);
        wifiPolyline = map.addPolyline(new PolylineOptions().color(Color.rgb(255, 191, 0)).width(5f));
        wifiPolyline.setVisible(false);
    }

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
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (gMap == null) return;
                switch (position){
                    case 0: gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID); break;
                    case 1: gMap.setMapType(GoogleMap.MAP_TYPE_NORMAL); break;
                    case 2: gMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE); break;
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    public void updateUserLocation(@NonNull LatLng newLocation, float orientation) {
        if (gMap == null) return;

        LatLng oldLocation = this.currentLocation;
        LatLng constrainedLocation = newLocation;

        if (indoorMapManager != null && oldLocation != null && indoorMapManager.hasIndoorConstraints()) {
            constrainedLocation = indoorMapManager.validatePosition(newLocation, oldLocation);
        }

        LatLng displayLocation = filterDisplayLocation(constrainedLocation, oldLocation);
        this.currentLocation = displayLocation;

        // Initialize PDR polyline if not exists (important for replay)
        if (pdrPolyline == null) {
            pdrPolyline = gMap.addPolyline(new PolylineOptions().color(Color.RED).width(5f));
            Log.d("TrajectoryMapFragment", "PDR polyline initialized in updateUserLocation");
            pdrPolyline.setVisible(isPdrOn);
        }

        if (orientationMarker == null) {
            orientationMarker = gMap.addMarker(new MarkerOptions()
                    .position(displayLocation)
                    .flat(true)
                    .anchor(0.5f, 0.5f)
                    .rotation(resolveMarkerHeadingDegrees(orientation))
                    .title("Current Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_24)))
            );
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(displayLocation, 19f));
        } else {
            orientationMarker.setPosition(displayLocation);
            orientationMarker.setRotation(resolveMarkerHeadingDegrees(orientation));
            gMap.moveCamera(CameraUpdateFactory.newLatLng(displayLocation));
        }

        // Add point to PDR trajectory polyline (red)
        if (pdrPolyline == null) {
            pdrPolyline = gMap.addPolyline(new PolylineOptions().color(Color.RED).width(4f));
            pdrPolyline.setVisible(isPdrOn);
        }
        List<LatLng> pdrPoints = new ArrayList<>(pdrPolyline.getPoints());
        pdrPoints.add(displayLocation);
        pdrPolyline.setPoints(pdrPoints);

        // Mark absolute position updates on the map using a distinct color for PDR.
        addRecentPoint(displayLocation, Color.RED);

            if (indoorMapManager != null) {
                indoorMapManager.setCurrentLocation(displayLocation);
                updateAutoFloorProximityState(displayLocation);

                // Auto-enable indoor map when the user marker enters a building.
                // Check if the arrow is inside a building boundary
                boolean isCurrentlyInsideBuilding = checkIfInsideBuilding(displayLocation);

            // If arrow just entered a building, auto-enable indoor map
            if (isCurrentlyInsideBuilding && !isArrowInsideBuilding) {
                Log.d("TrajectoryMapFragment", "Arrow entered building - auto-enabling indoor map");
                if (!indoorMapSwitch.isChecked()) {
                    indoorMapSwitch.setChecked(true);
                }
                updateMapToggleState();
                isArrowInsideBuilding = true;
            }
            // If arrow just exited a building
            else if (!isCurrentlyInsideBuilding && isArrowInsideBuilding) {
                Log.d("TrajectoryMapFragment", "Arrow exited building");
                isArrowInsideBuilding = false;
            }

            if (autoFloorSwitch.isChecked() && indoorMapManager != null) {
                try {
                    if (isCurrentlyInsideBuilding && indoorMapManager.getAvailableFloorsCount() > 0) {
                        boolean initialOrTransitionAllowed = pendingInitialFloorDetection || autoFloorArmedByTransitionEntry;
                        if (!initialOrTransitionAllowed) {
                            clearPendingAutoFloor();
                        } else {

                                    int estimatedFloor = resolveEstimatedFloorIndexForAutoMap();
                            int targetFloor = clampFloorIndex(estimatedFloor + autoFloorOffset);
                            int currentFloor = indoorMapManager.getCurrentFloor();

                            if (targetFloor != currentFloor) {
                                if (pendingAutoFloorTarget == targetFloor) {
                                    pendingAutoFloorCount++;
                                } else {
                                    pendingAutoFloorTarget = targetFloor;
                                    pendingAutoFloorCount = 1;
                                }

                                if (pendingAutoFloorCount >= AUTO_FLOOR_REQUIRED_CONFIRMATIONS) {
                                    long nowMs = SystemClock.elapsedRealtime();
                                    if (nowMs - lastAutoFloorSwitchTimestampMs < AUTO_FLOOR_SWITCH_COOLDOWN_MS) {
                                        clearPendingAutoFloor();
                                    } else {
                                        Log.d("TrajectoryMapFragment", String.format(
                                                "AutoFloor: Switching floor %d -> %d (estimated=%d, offset=%d)",
                                                currentFloor, targetFloor, estimatedFloor, autoFloorOffset));
                                        indoorMapManager.setCurrentFloor(targetFloor, true);
                                        lastAutoFloorSwitchTimestampMs = nowMs;
                                        updateFloorDisplay();
                                        clearPendingAutoFloor();
                                    }
                                }
                            } else {
                                clearPendingAutoFloor();
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e("TrajectoryMapFragment", "AutoFloor error: " + e.getMessage(), e);
                }
            }
        }
    }

    private LatLng filterDisplayLocation(@NonNull LatLng candidate, @Nullable LatLng previous) {
        long nowMs = SystemClock.elapsedRealtime();

        if (previous == null) {
            lastDisplayFilterTimestampMs = nowMs;
            return candidate;
        }

        double deltaSeconds = DISPLAY_MIN_TIME_DELTA_SECONDS;
        if (lastDisplayFilterTimestampMs > 0) {
            deltaSeconds = Math.max(DISPLAY_MIN_TIME_DELTA_SECONDS,
                    (nowMs - lastDisplayFilterTimestampMs) / 1000.0);
        }
        lastDisplayFilterTimestampMs = nowMs;

        double distanceMeters = GeometryUtils.distanceBetween(previous, candidate);
        if (distanceMeters <= DISPLAY_JITTER_DEADBAND_METERS) {
            return previous;
        }

        boolean indoors = indoorMapManager != null && indoorMapManager.hasIndoorConstraints();
        double legacyMaxJumpMeters = indoors ? DISPLAY_MAX_JUMP_INDOOR_METERS : DISPLAY_MAX_JUMP_OUTDOOR_METERS;
        double shortWindowSpeedLimitMeters = Math.max(0.6,
                Math.min(DISPLAY_SHORT_WINDOW_MAX_METERS, DISPLAY_MAX_WALKING_SPEED_MPS * deltaSeconds));
        double maxJumpMeters = Math.min(legacyMaxJumpMeters, shortWindowSpeedLimitMeters);
        LatLng limitedCandidate = candidate;
        if (distanceMeters > maxJumpMeters) {
            double ratio = maxJumpMeters / distanceMeters;
            limitedCandidate = interpolate(previous, candidate, ratio);
        }

        double alpha = indoors ? DISPLAY_SMOOTH_ALPHA_INDOOR : DISPLAY_SMOOTH_ALPHA_OUTDOOR;
        return interpolate(previous, limitedCandidate, alpha);
    }

    private LatLng interpolate(@NonNull LatLng from, @NonNull LatLng to, double alpha) {
        double clampedAlpha = Math.max(0.0, Math.min(1.0, alpha));
        double lat = from.latitude + (to.latitude - from.latitude) * clampedAlpha;
        double lon = from.longitude + (to.longitude - from.longitude) * clampedAlpha;
        return new LatLng(lat, lon);
    }

    // Check if a location is inside any known building
    // Uses building boundary polygons from BuildingPolygon class and dynamic boundaries from IndoorMapManager
    private boolean checkIfInsideBuilding(LatLng location) {
        // Check against pre-defined buildings (Nucleus, Library)
        if (BuildingPolygon.inNucleus(location) || BuildingPolygon.inLibrary(location)) {
            return true;
        }

        // Check against dynamically loaded building boundaries from API
        if (indoorMapManager != null && indoorMapManager.isLocationInsideSelectedBuilding(location)) {
            return true;
        }

        return false;
    }

    // Public method to check if current location is inside a building
    // Used by RecordingFragment for adaptive filtering
    public boolean isCurrentlyInsideBuilding() {
        return currentLocation != null && checkIfInsideBuilding(currentLocation);
    }

    public void setInitialCameraPosition(@NonNull LatLng startLocation) {
        if (gMap != null) {
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLocation, 19f));
            // Add a marker at the initial position
            if (orientationMarker == null) {
                orientationMarker = gMap.addMarker(new MarkerOptions()
                        .position(startLocation)
                        .flat(true)
                        .anchor(0.5f, 0.5f)
                        .rotation(resolveMarkerHeadingDegrees(0f))
                        .title("Start Position")
                        .icon(BitmapDescriptorFactory.fromBitmap(
                                UtilFunctions.getBitmapFromVector(requireContext(),
                                        R.drawable.ic_baseline_navigation_24))));
            }
            currentLocation = startLocation;
        } else {
            pendingCameraPosition = startLocation;
            hasPendingCameraMove = true;
        }
    }

    public void setReplayStartMarker(@NonNull LatLng startLocation) {
        if (gMap == null) {
            pendingReplayStartPosition = startLocation;
            return;
        }

        if (replayStartMarker == null) {
            replayStartMarker = gMap.addMarker(new MarkerOptions()
                    .position(startLocation)
                    .title("Recorded Start")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
        } else {
            replayStartMarker.setPosition(startLocation);
        }
    }

    private float resolveMarkerHeadingDegrees(float orientationRadians) {
        if (sensorFusion != null) {
            return sensorFusion.getDisplayHeading();
        }

        if (!Float.isNaN(orientationRadians) && !Float.isInfinite(orientationRadians)) {
            float degrees = (float) Math.toDegrees(orientationRadians);
            float normalized = degrees % 360.0f;
            return normalized < 0 ? normalized + 360.0f : normalized;
        }

        return 0f;
    }

    public LatLng getCurrentLocation() { return currentLocation; }
    public void setOnVenueSelectedListener(OnVenueSelectedListener listener) {
        this.venueSelectedListener = listener;
    }

    public void updateGNSS(@NonNull LatLng gnssLocation) {
        if (gMap == null || !isGnssOn) return;

        // GNSS rendering: keep only small history circles.
        // Remove old WiFi pointer to avoid simultaneous display
        if (wifiMarker != null) {
            wifiMarker.remove();
            wifiMarker = null;
        }
        
        // Remove GNSS large pointer if it exists
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
        
        lastGnssLocation = gnssLocation;
        
        // Track GNSS position changes - add to history if position changed significantly
        if (lastGnssPositionForHistory == null || 
            distanceBetweenLatLng(lastGnssPositionForHistory, gnssLocation) > 0.5) {
            
            // Draw a permanent small circle for GNSS history (Blue)
            Circle circle = gMap.addCircle(new CircleOptions()
                .center(gnssLocation)
                .radius(0.4) // slightly larger than trajectory points
                .fillColor(Color.BLUE)
                .strokeColor(Color.BLACK)
                .strokeWidth(1f)
                .zIndex(90)); // Under trajectory but above map
            
            // Add to GNSS history
            gnssHistoryCircles.add(circle);
            if (gnssHistoryCircles.size() > MAX_GNSS_WIFI_HISTORY) {
                Circle oldest = gnssHistoryCircles.remove(0);
                if (oldest != null) oldest.remove();
            }
            lastGnssPositionForHistory = gnssLocation;
        }
        
        if (gnssPolyline != null) {
            gnssPolyline.setPoints(new ArrayList<>());
            gnssPolyline.setVisible(false);
        }
    }

    public void updateFused(@NonNull LatLng fusedLocation) {
        if (gMap == null || fusedLocation == null) return;

        // Apply a simple smoothing filter to reduce sudden jump jitter.
        LatLng smoothLocation = fusedLocation;
        if (lastFusedSmoothedLocation != null) {
            double alpha = FUSED_SMOOTH_ALPHA;
            double smLat = alpha * fusedLocation.latitude + (1.0 - alpha) * lastFusedSmoothedLocation.latitude;
            double smLon = alpha * fusedLocation.longitude + (1.0 - alpha) * lastFusedSmoothedLocation.longitude;
            smoothLocation = new LatLng(smLat, smLon);
        }
        lastFusedSmoothedLocation = smoothLocation;

        // Initialize fused polyline if not exists
        if (fusedPolyline == null) {
            fusedPolyline = gMap.addPolyline(new PolylineOptions().color(Color.GREEN).width(5f));
            Log.d("TrajectoryMapFragment", "Fused Polyline initialized in updateFused");
        }

        List<LatLng> fusedPoints = new ArrayList<>(fusedPolyline.getPoints());
        fusedPoints.add(smoothLocation);
        fusedPolyline.setPoints(fusedPoints);

        addRecentPoint(smoothLocation, Color.GREEN);
    }

    public void updateWifi(@NonNull LatLng wifiLocation) {
        if (gMap == null || wifiLocation == null || !isWifiOn) return;

        if (wifiPolyline == null) {
            wifiPolyline = gMap.addPolyline(new PolylineOptions().color(Color.rgb(255, 191, 0)).width(5f));
        }

        // WiFi rendering: keep only small history circles.
        // Remove old GNSS pointer to avoid simultaneous display
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
        
        // Remove WiFi large pointer if it exists
        if (wifiMarker != null) {
            wifiMarker.remove();
            wifiMarker = null;
        }
        
        lastWifiLocation = wifiLocation;

        // Always record WiFi history point on each update (no movement threshold).
        Circle circle = gMap.addCircle(new CircleOptions()
            .center(wifiLocation)
            .radius(0.4) // slightly larger than trajectory points
            .fillColor(Color.YELLOW)
            .strokeColor(Color.BLACK)
            .strokeWidth(1f)
            .zIndex(90)); // Under trajectory but above map

        // Add to WiFi history
        wifiHistoryCircles.add(circle);
        if (wifiHistoryCircles.size() > MAX_GNSS_WIFI_HISTORY) {
            Circle oldest = wifiHistoryCircles.remove(0);
            if (oldest != null) oldest.remove();
        }
        lastWifiPositionForHistory = wifiLocation;
        
        if (wifiPolyline != null) {
            wifiPolyline.setPoints(new ArrayList<>());
            wifiPolyline.setVisible(false);
        }
    }

    private void addRecentPoint(LatLng position, int color) {
        if (position == null || gMap == null) return;

        if (recentCirclesBuffer.size() >= RECENT_POINTS_N) {
            Circle oldest = recentCirclesBuffer.remove(0);
            if (oldest != null) {
                oldest.remove();
            }
        }
        
        Circle circle = gMap.addCircle(new CircleOptions()
                .center(position)
                .radius(0.3) // 3 meters radius
                .fillColor(color)
                .strokeColor(Color.BLACK)
                .strokeWidth(1f)
                .zIndex(100)); // Ensure it's drawn on top
                
        recentCirclesBuffer.add(circle);
    }

    // Calculate distance between two LatLng points in meters using Haversine formula
    private double distanceBetweenLatLng(LatLng p1, LatLng p2) {
        if (p1 == null || p2 == null) return Double.MAX_VALUE;
        
        final int R = 6371000; // Earth's radius in meters
        double lat1 = Math.toRadians(p1.latitude);
        double lat2 = Math.toRadians(p2.latitude);
        double deltaLat = Math.toRadians(p2.latitude - p1.latitude);
        double deltaLon = Math.toRadians(p2.longitude - p1.longitude);
        
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double c = 2 * Math.asin(Math.sqrt(a));
        
        return R * c;  // distance in meters
    }

    private float getHueFromColor(int color) {
        if (color == Color.BLUE) return BitmapDescriptorFactory.HUE_AZURE;
        if (color == Color.GREEN) return BitmapDescriptorFactory.HUE_GREEN;
        if (color == Color.YELLOW) return BitmapDescriptorFactory.HUE_ORANGE;
        if (color == Color.RED) return BitmapDescriptorFactory.HUE_RED;
        return BitmapDescriptorFactory.HUE_VIOLET;
    }

    public void clearGNSS() {
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
    }

    public boolean isGnssEnabled() { return isGnssOn; }

    private void setFloorControlsVisibility(int visibility) {
        if (floorControlsContainer != null) {
            floorControlsContainer.setVisibility(visibility);
        }
        if (autoFloorSwitch != null) {
            autoFloorSwitch.setVisibility(View.VISIBLE);
        }
    }

    private void ensureIndoorBuildingSelection() {
        if (indoorMapManager == null || gMap == null || !indoorMapManager.isIndoorMapVisible()) {
            return;
        }

        LatLng anchorLocation = currentLocation;
        if (anchorLocation == null && gMap.getCameraPosition() != null) {
            anchorLocation = gMap.getCameraPosition().target;
        }

        boolean selected = indoorMapManager.selectBuildingForLocation(anchorLocation, true);
        if (selected && buildingInfoCard != null && buildingNameText != null) {
            String venueName = indoorMapManager.getSelectedBuildingName();
            if (venueName != null) {
                buildingNameText.setText(venueName);
                buildingInfoCard.setVisibility(View.VISIBLE);
            }
        }
    }

    public void detectCurrentFloorOnce() {
        pendingInitialFloorDetection = true;
        forceHardcodedBandsAfterStart = true;

        if (indoorMapManager == null) {
            return;
        }

        if (!indoorMapManager.isIndoorMapVisible() && indoorMapSwitch != null) {
            indoorMapSwitch.setChecked(true);
            return;
        }

        ensureIndoorBuildingSelection();
        performInitialFloorDetectionIfPending();
    }

    private void applyMapControlsExpandedState() {
        if (mapControlsContent != null) {
            mapControlsContent.setVisibility(mapControlsExpanded ? View.VISIBLE : View.GONE);
        }
        if (mapControlsToggleButton != null) {
            mapControlsToggleButton.setIconResource(
                    mapControlsExpanded ? android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float
            );
        }
    }

    private void syncIndoorFloorReference() {
        if (indoorMapManager == null || sensorFusion == null) {
            return;
        }

        if (indoorMapManager.getAvailableFloorsCount() <= 0) {
            return;
        }

        sensorFusion.setIndoorFloorReference(
                indoorMapManager.getFloorHeight(),
                indoorMapManager.getCurrentFloor(),
                indoorMapManager.getFloorAltitudeAnchors()
        );
        sensorFusion.setIndoorEnvironmentFeatures(
                indoorMapManager.getCurrentFloorStairsZones(),
                indoorMapManager.getCurrentFloorLiftZones(),
                indoorMapManager.getCurrentFloorWalls()
        );
    }

    private void performInitialFloorDetectionIfPending() {
        if (!pendingInitialFloorDetection || indoorMapManager == null || sensorFusion == null) {
            return;
        }

        if (!canUseAutoFloor()) {
            return;
        }

        // Initial floor lock must use barometer bands even before entering stairs/lift zones.
        sensorFusion.setBarometerAutoFloorEnabled(true);
        int estimatedFloor = resolveEstimatedFloorIndexForAutoMap();
        indoorMapManager.setCurrentFloor(estimatedFloor, true);
        syncIndoorFloorReference();
        updateFloorDisplay();
        if (!autoFloorArmedByTransitionEntry) {
            sensorFusion.setBarometerAutoFloorEnabled(false);
        }
        pendingInitialFloorDetection = false;
    }

    private int resolveEstimatedFloorIndexForAutoMap() {
        if (indoorMapManager == null || sensorFusion == null) {
            return 0;
        }

        int bandFloor;
        float altitudeMeters = sensorFusion.getEstimatedAbsoluteAltitude();
        if (forceHardcodedBandsAfterStart && !Float.isNaN(altitudeMeters)) {
            bandFloor = getHardcodedBandFloorFromAltitude(altitudeMeters);
        } else {
            bandFloor = sensorFusion.getEstimatedFloorByBarometerBands();
        }

        return indoorMapManager.mapBarometerBandFloorToFloorIndex(bandFloor);
    }

    private int getHardcodedBandFloorFromAltitude(float altitudeMeters) {
        if (altitudeMeters <= FLOOR_BAND_B1_MAX_METERS) {
            return 0; // B1
        }
        if (altitudeMeters < FLOOR_BAND_GF_MAX_METERS) {
            return 1; // GF
        }
        if (altitudeMeters < FLOOR_BAND_F1_MAX_METERS) {
            return 2; // F1
        }
        if (altitudeMeters < FLOOR_BAND_F2_MAX_METERS) {
            return 3; // F2
        }
        return 4; // F3+
    }

    private boolean canUseAutoFloor() {
        return indoorMapManager != null
                && indoorMapManager.isIndoorMapVisible()
                && indoorMapManager.getAvailableFloorsCount() > 0;
    }

    private void updateMapToggleState() {
        if (autoFloorSwitch == null) {
            return;
        }

        boolean autoFloorAvailable = canUseAutoFloor();
        autoFloorSwitch.setEnabled(autoFloorAvailable);
        autoFloorSwitch.setAlpha(autoFloorAvailable ? 1.0f : 0.45f);

        if (!autoFloorAvailable && autoFloorSwitch.isChecked()) {
            setAutoFloorChecked(false);
        }

        if (floorControlsContainer != null) {
            floorControlsContainer.setVisibility(autoFloorAvailable ? View.VISIBLE : View.GONE);
        }
    }

    private void setAutoFloorChecked(boolean checked) {
        if (autoFloorSwitch == null) {
            return;
        }

        suppressAutoFloorCallback = true;
        autoFloorSwitch.setChecked(checked);
        suppressAutoFloorCallback = false;

        if (!checked) {
            autoFloorOffset = 0;
            clearPendingAutoFloor();
        }
    }

    private void clearPendingAutoFloor() {
        pendingAutoFloorTarget = Integer.MIN_VALUE;
        pendingAutoFloorCount = 0;
    }

    private void updateAutoFloorProximityState(@NonNull LatLng location) {
        if (autoFloorSwitch == null || indoorMapManager == null) {
            return;
        }

        boolean canAutoFloor = canUseAutoFloor();
        boolean nearVerticalTransition = canAutoFloor
                && indoorMapManager.isNearCurrentFloorVerticalTransition(location);

        if (!canAutoFloor) {
            wasNearVerticalTransition = false;
            autoFloorArmedByTransitionEntry = false;
            if (sensorFusion != null) {
                sensorFusion.setBarometerAutoFloorEnabled(false);
            }
        } else if (nearVerticalTransition && !wasNearVerticalTransition) {
            autoFloorArmedByTransitionEntry = true;
            if (sensorFusion != null) {
                sensorFusion.setBarometerAutoFloorEnabled(true);
            }
        } else if (!nearVerticalTransition && wasNearVerticalTransition) {
            autoFloorArmedByTransitionEntry = false;
            if (sensorFusion != null) {
                sensorFusion.setBarometerAutoFloorEnabled(false);
            }
            clearPendingAutoFloor();
        }
        wasNearVerticalTransition = nearVerticalTransition;

        if (nearVerticalTransition) {
            if (!autoFloorSwitch.isChecked()) {
                autoFloorProximityManaged = true;
                setAutoFloorChecked(true);
            }
        } else if (autoFloorProximityManaged && autoFloorSwitch.isChecked()) {
            setAutoFloorChecked(false);
            autoFloorProximityManaged = false;
        } else if (!autoFloorSwitch.isChecked()) {
            autoFloorProximityManaged = false;
        }
    }

    // Update floor display text based on current floor
    private void updateFloorDisplay() {
        if (indoorMapManager == null || floorTextView == null) return;

        int currentFloor = indoorMapManager.getCurrentFloor();
        String currentFloorName = indoorMapManager.getCurrentFloorName();
        int totalFloors = indoorMapManager.getAvailableFloorsCount();

        String displayText;
        if (currentFloorName != null && !currentFloorName.isEmpty()) {
            displayText = currentFloorName;
        } else if (currentFloor == 0) {
            displayText = "G";
        } else if (currentFloor > 0) {
            displayText = "F" + currentFloor;
        } else {
            displayText = "B" + Math.abs(currentFloor);
        }

        if (totalFloors > 1) {
            displayText += "\n" + (currentFloor + 1) + "/" + totalFloors;
        }

        floorTextView.setText(displayText);
    }

    private int clampFloorIndex(int floorIndex) {
        int totalFloors = indoorMapManager != null ? indoorMapManager.getAvailableFloorsCount() : 0;
        if (totalFloors <= 0) {
            return floorIndex;
        }
        return Math.max(0, Math.min(floorIndex, totalFloors - 1));
    }

    public void clearMapAndReset() {
        if (pdrPolyline != null) { pdrPolyline.remove(); pdrPolyline = null; }
        if (fusedPolyline != null) { fusedPolyline.remove(); fusedPolyline = null; }
        if (gnssPolyline != null) { gnssPolyline.remove(); gnssPolyline = null; }
        if (wifiPolyline != null) { wifiPolyline.remove(); wifiPolyline = null; }
        if (orientationMarker != null) { orientationMarker.remove(); orientationMarker = null; }
        if (gnssMarker != null) { gnssMarker.remove(); gnssMarker = null; }
        if (wifiMarker != null) { wifiMarker.remove(); wifiMarker = null; }

        for (Marker m : manualMarkers) m.remove();
        manualMarkers.clear();

        for (Circle c : recentCirclesBuffer) {
            if (c != null) c.remove();
        }
        recentCirclesBuffer.clear();
        
        // Clear GNSS/WiFi history buffers.
        for (Circle c : gnssHistoryCircles) {
            if (c != null) c.remove();
        }
        gnssHistoryCircles.clear();
        
        for (Circle c : wifiHistoryCircles) {
            if (c != null) c.remove();
        }
        wifiHistoryCircles.clear();
        
        lastGnssPositionForHistory = null;
        lastWifiPositionForHistory = null;

        lastGnssLocation = null;
        lastWifiLocation = null;
        currentLocation  = null;
        lastDisplayFilterTimestampMs = -1L;

        if (gMap != null) {
            pdrPolyline = gMap.addPolyline(new PolylineOptions().color(Color.RED).width(5f));
            pdrPolyline.setVisible(isPdrOn);
            fusedPolyline = gMap.addPolyline(new PolylineOptions().color(Color.GREEN).width(5f));
            gnssPolyline = gMap.addPolyline(new PolylineOptions().color(Color.BLUE).width(5f));
            gnssPolyline.setVisible(false);
            wifiPolyline = gMap.addPolyline(new PolylineOptions().color(Color.rgb(255, 191, 0)).width(5f));
            wifiPolyline.setVisible(isWifiOn);
        }

        if (indoorMapManager != null) {
            indoorMapManager.hideMap();
            setFloorControlsVisibility(View.GONE);
            if (buildingInfoCard != null) {
                buildingInfoCard.setVisibility(View.GONE);
            }
        }
    }
    public LatLng getCameraTarget() {
        if (gMap != null) {
            return gMap.getCameraPosition().target;
        }
        return null;
    }
}


