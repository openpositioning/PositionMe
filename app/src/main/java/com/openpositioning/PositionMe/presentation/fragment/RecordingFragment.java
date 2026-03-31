package com.openpositioning.PositionMe.presentation.fragment;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.sensors.Wifi;
import com.openpositioning.PositionMe.utils.FusionManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enhanced RecordingFragment with Indoor Venue Selection
 *
 * New Features:
 * - Display building outlines during recording
 * - Allow users to select venue by clicking building outline
 * - Show indoor floor plans and floor selector
 * - Auto-record venue information for data submission
 * - Integrated trajectory display with venue context
 *
 * @author Original Team + Your Enhancements
 */
public class RecordingFragment extends Fragment implements OnMapReadyCallback {

    private static final String TAG = "RecordingFragment";

    // Building Location Data
    private static class BuildingLocation {
        String name;
        String apiName;
        LatLng center;
        double radiusMeters;
        int outlineColor;
        int fillColor;
        float markerHue;

        BuildingLocation(String name, String apiName, LatLng center, double radiusMeters,
                         int outlineColor, int fillColor, float markerHue) {
            this.name = name;
            this.apiName = apiName;
            this.center = center;
            this.radiusMeters = radiusMeters;
            this.outlineColor = outlineColor;
            this.fillColor = fillColor;
            this.markerHue = markerHue;
        }
    }

    // Target buildings
    private static final BuildingLocation[] TARGET_BUILDINGS = {
            new BuildingLocation(
                    "Murchison House",
                    "Murchison House",
                    new LatLng(55.92412, -3.1792),
                    20.0,
                    Color.RED,
                    Color.argb(51, 255, 0, 0),
                    BitmapDescriptorFactory.HUE_RED
            ),
            new BuildingLocation(
                    "Noreen and Kenneth Murray Library",
                    "Library",
                    new LatLng(55.9229, -3.1750),
                    10.0,
                    Color.GREEN,
                    Color.argb(51, 0, 255, 0),
                    BitmapDescriptorFactory.HUE_GREEN
            ),
            new BuildingLocation(
                    "The Nucleus Building",
                    "The Nucleus",
                    new LatLng(55.92301, -3.1742),
                    20.0,
                    Color.BLUE,
                    Color.argb(51, 0, 0, 255),
                    BitmapDescriptorFactory.HUE_BLUE
            ),
            new BuildingLocation(
                    "Fleeming Jenkin Building",
                    "Fleeming Jenkin",
                    new LatLng(55.92248, -3.17299),
                    20.0,
                    Color.MAGENTA,
                    Color.argb(51, 255, 0, 255),
                    BitmapDescriptorFactory.HUE_MAGENTA
            )
    };

    // UI Elements
    private MaterialButton completeButton, cancelButton;
    private ImageView recIcon;
    private ProgressBar timeRemaining;
    private TextView elevation, distanceTravelled, gnssError;

    // Venue selection UI
    private TextView venueInfoText;
    private MaterialButton changeVenueButton;
    private View floorSelectorContainer;
    private MaterialButton floorToggleButton;   // created programmatically
    private LinearLayout floorButtonLayout;
    private Button backToRecordingButton;
    private boolean isFloorSelectorExpanded = false;
    private int floorSelectorExpandedWidth = 0;

    // SENSOR DATA UI ELEMENTS
    private TextView trajectoryIdText;
    private TextView wifiFingerprintsCount;
    private TextView correctedPositionsCount;
    private TextView initialPositionStatus;
    private ImageView initialPositionIndicator;
    private MaterialButton setInitialPositionButton;

    // TEST POINTS UI ELEMENTS
    private MaterialButton markTestPointButton;
    private TextView testPointsCount;

    // Smooth Trajectory toggle
    private SwitchMaterial smoothTrajectorySwitch;
    private boolean smoothTrajectoryEnabled = false;

    // Bottom drawer (slide-to-hide info panel)
    private SwipeDownLinearLayout bottomDrawer;
    private MaterialButton expandDrawerTab;
    private final List<Marker> testPointMarkers = new ArrayList<>();

    // Map & Location
    private GoogleMap googleMap;
    private FusedLocationProviderClient fusedLocationClient;
    private LatLng currentLocation;
    private Marker userMarker;
    private Polyline trajectoryPolyline;
    private Marker gnssMarker;

    // Color-coded source polylines (PDR=blue, GNSS=green, WiFi=orange, Fused=red)
    private Polyline pdrPolyline;
    private Polyline gnssPolyline2;
    private Polyline wifiPolyline;
    private Polyline fusedPolyline;
    private static final int MAX_RAW_OBSERVATIONS = 5;
    private static final double RAW_OBSERVATION_RADIUS_M = 0.8;
    private static final long RAW_OBSERVATION_MIN_INTERVAL_MS = 1200;
    private static final float RAW_OBSERVATION_MIN_DISTANCE_M = 0.6f;
    private final List<Circle> gnssObservationCircles = new ArrayList<>();
    private final List<Circle> wifiObservationCircles = new ArrayList<>();
    private final List<Circle> pdrObservationCircles = new ArrayList<>();
    private LatLng lastGnssObservation = null;
    private LatLng lastWifiObservation = null;
    private LatLng lastPdrObservation = null;
    private long lastGnssObservationTime = 0L;
    private long lastWifiObservationTime = 0L;
    private long lastPdrObservationTime = 0L;
    // Track last source to detect source changes
    private FusionManager.PositionSource lastPolylineSource = null;
    // Floor display TextView
    private TextView floorDisplayText;
    // Last trajectory update time
    private long lastTrajectoryUpdateTime = 0;
    private static final long TRAJECTORY_UPDATE_INTERVAL_MS = 350;

    // Indoor map support
    private final Map<String, NetworkUtils.BuildingData> allBuildingsData = new HashMap<>();
    private final Map<Polygon, String> buildingPolygonMap = new HashMap<>();
    private String currentSelectedBuilding = null;
    private String currentSelectedFloor = null;
    private GroundOverlay currentFloorImageOverlay = null;
    private final List<Polyline> currentWallLines = new ArrayList<>();
    private final List<Polygon> currentAreaPolygons = new ArrayList<>();
    private final List<Marker> currentPoiMarkers = new ArrayList<>();
    private static final float FLOOR_IMAGE_TRANSPARENCY = 0.35f;

    // Manual overlay offsets (degrees). Tune these to move floor images.
    // +LAT moves UP (north), -LAT moves DOWN (south).
    // +LNG moves RIGHT (east), -LNG moves LEFT (west).
    private static final double NUCLEUS_OVERLAY_LAT_OFFSET = 0.000015;
    private static final double NUCLEUS_OVERLAY_LNG_OFFSET = -0.000059;
    private static final double LIBRARY_OVERLAY_LAT_OFFSET = 0.000024;
    private static final double LIBRARY_OVERLAY_LNG_OFFSET = 0.000057;

    private static class UprightOverlayConfig {
        final LatLng center;
        final float widthM;
        final float bearingDeg;

        UprightOverlayConfig(LatLng center, float widthM, float bearingDeg) {
            this.center = center;
            this.widthM = widthM;
            this.bearingDeg = bearingDeg;
        }
    }

    // Per-floor delta config relative to ground-floor base.
    private static class FloorDelta {
        final double latDelta;
        final double lngDelta;
        final float widthDeltaM;
        final float bearingDeltaDeg;

        FloorDelta(double latDelta, double lngDelta, float widthDeltaM, float bearingDeltaDeg) {
            this.latDelta = latDelta;
            this.lngDelta = lngDelta;
            this.widthDeltaM = widthDeltaM;
            this.bearingDeltaDeg = bearingDeltaDeg;
        }
    }

    private static final FloorDelta ZERO_FLOOR_DELTA = new FloorDelta(0.0, 0.0, 0f, 0f);

    private UprightOverlayConfig withOffset(UprightOverlayConfig config, double latOffset, double lngOffset) {
        return new UprightOverlayConfig(
                new LatLng(config.center.latitude + latOffset, config.center.longitude + lngOffset),
                config.widthM,
                config.bearingDeg);
    }

    private UprightOverlayConfig applyFloorDelta(UprightOverlayConfig base, FloorDelta delta) {
        return new UprightOverlayConfig(
                new LatLng(base.center.latitude + delta.latDelta, base.center.longitude + delta.lngDelta),
                base.widthM + delta.widthDeltaM,
                base.bearingDeg + delta.bearingDeltaDeg);
    }

    private FloorDelta getNucleusFloorDelta(int floor) {
        switch (floor) {
            case -1:
                // B1 relative to G
                return new FloorDelta(-0.000011, 0.000034, 4.0f, 0f);
            case 0:
                return ZERO_FLOOR_DELTA;
            case 1:
                return new FloorDelta(-0.000011, 0.000034, 4.0f, 0f);
            case 2:
                return new FloorDelta(-0.000011, 0.000034, 4.0f, 0f);
            case 3:
                return new FloorDelta(-0.000011, 0.000034, 4.0f, 0f);
            default:
                return ZERO_FLOOR_DELTA;
        }
    }

    private FloorDelta getLibraryFloorDelta(int floor) {
        switch (floor) {
            case 0:
                return new FloorDelta(-0.000002, -0.000022, 0.0f, 0f);
            case 1:
                return new FloorDelta(-0.000002, -0.000022, 0.0f, 0f);
            case 2:
                return new FloorDelta(-0.000002, -0.000022, 0.0f, 0f);
            case 3:
                return new FloorDelta(-0.000002, -0.000022, 0.0f, 0f);
            default:
                return ZERO_FLOOR_DELTA;
        }
    }

    // Recording Logic
    private SharedPreferences settings;
    private SensorFusion sensorFusion;
    private Handler refreshDataHandler;
    private CountDownTimer autoStop;
    private float distance = 0f;
    private float previousPosX = 0f;
    private float previousPosY = 0f;

    // Venue tracking
    private boolean hasVenue = false;
    private String venueId = "";
    private String venueName = "";
    private String venueFloor = "";
    // Default keeps automatic floor following. Tapping a floor button switches
    // to manual lock until user taps AUTO.
    private boolean autoFloorFollowEnabled = true;

    // UI Update Interval (ms)
    private static final int UI_REFRESH_INTERVAL_MS = 100; // 10 FPS for smooth movement

    // Runnable for UI Updates
    private final Runnable refreshDataTask = new Runnable() {
        @Override
        public void run() {
            updateUIandPosition();
            refreshDataHandler.postDelayed(refreshDataTask, UI_REFRESH_INTERVAL_MS);
        }
    };

    public RecordingFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.sensorFusion = SensorFusion.getInstance();
        Context context = requireActivity();
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.refreshDataHandler = new Handler();

        // Read venue info from arguments
        Bundle args = getArguments();
        if (args != null) {
            hasVenue = args.getBoolean("has_venue", false);
            venueId = args.getString("venue_id", "");
            venueName = args.getString("venue_name", "");
            venueFloor = args.getString("venue_floor", "");

        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_recording, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());

        // Initialize UI references
        elevation = view.findViewById(R.id.currentElevation);
        distanceTravelled = view.findViewById(R.id.currentDistanceTraveled);
        gnssError = view.findViewById(R.id.gnssError);
        completeButton = view.findViewById(R.id.stopButton);
        cancelButton = view.findViewById(R.id.cancelButton);
        recIcon = view.findViewById(R.id.redDot);
        timeRemaining = view.findViewById(R.id.timeRemainingBar);

        // Venue selection UI
        venueInfoText = view.findViewById(R.id.venueInfoText);
        changeVenueButton = view.findViewById(R.id.changeVenueButton);
        floorSelectorContainer = view.findViewById(R.id.floorSelectorContainer);
        floorButtonLayout = view.findViewById(R.id.floorButtonLayout);
        backToRecordingButton = view.findViewById(R.id.backToRecordingButton);

        // SENSOR DATA UI ELEMENTS
        trajectoryIdText = view.findViewById(R.id.trajectoryIdText);
        wifiFingerprintsCount = view.findViewById(R.id.wifiFingerprintsCount);
        correctedPositionsCount = view.findViewById(R.id.correctedPositionsCount);
        initialPositionStatus = view.findViewById(R.id.initialPositionStatus);
        initialPositionIndicator = view.findViewById(R.id.initialPositionIndicator);
        setInitialPositionButton = view.findViewById(R.id.setInitialPositionButton);
        // Floor display (particle filter / map matcher output)
        floorDisplayText = view.findViewById(R.id.floorDisplayText);

        // TEST POINTS UI ELEMENTS
        markTestPointButton = view.findViewById(R.id.markTestPointButton);
        testPointsCount = view.findViewById(R.id.testPointsCount);

        // Smooth Trajectory toggle
        smoothTrajectorySwitch = view.findViewById(R.id.smoothTrajectorySwitch);
        smoothTrajectorySwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            smoothTrajectoryEnabled = isChecked;
            // Reset so the filter initialises from the current position
            positionInitialized = false;
        });

        // Initialize map
        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.recordingMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        // Setup UI
        setupRecordingControls();
        setupVenueDisplay();
        setupBottomDrawer(view);

        // Start recording refresh
        if (this.settings.getBoolean("split_trajectory", false)) {
            long limit = this.settings.getInt("split_duration", 30) * 60000L;
            timeRemaining.setMax((int) (limit / 1000));
            timeRemaining.setProgress(0);
            timeRemaining.setScaleY(3f);

            autoStop = new CountDownTimer(limit, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    timeRemaining.incrementProgressBy(1);
                    updateUIandPosition();
                }

                @Override
                public void onFinish() {
                    sensorFusion.stopRecording();
                    ((RecordingActivity) requireActivity()).showCorrectionScreen();
                }
            }.start();
        } else {
            refreshDataHandler.post(refreshDataTask);
        }

        // Blinking recording icon
        blinkingRecordingIcon();

        // Start automated WiFi fingerprint collection
        startWiFiFingerprintCollection();
        
        // Start automated BLE data collection
        startBLEDataCollection();
        
        // Reset trajectory tracking variables for new recording
        lastTrajectoryPoint = null;
        secondLastTrajectoryPoint = null;
        positionInitialized = false;
        smoothedLat = 0.0;
        smoothedLng = 0.0;
    }

    // WiFi FINGERPRINT COLLECTION
    private Runnable wiFiFingerprintTask;
    private static final long WIFI_FINGERPRINT_INTERVAL = 3000; // 3 seconds

    private void startWiFiFingerprintCollection() {
        wiFiFingerprintTask = new Runnable() {
            @Override
            public void run() {
                // Collect WiFi fingerprints from current available networks
                List<Wifi> wifiList = sensorFusion.getWifiList();
                if (wifiList != null && !wifiList.isEmpty()) {
                    long currentTime = System.currentTimeMillis();
                    for (Wifi wifi : wifiList) {
                        // BSSID is already a long value, get level (signal strength)
                        long bssid = wifi.getBssid();
                        int rssi = wifi.getLevel();
                        
                        // Add WiFi fingerprint
                        sensorFusion.addWiFiFingerprint(currentTime, bssid, rssi);
                        
                        // Log detailed WiFi AP data together with RTT capability flag.
                        // In production, would check actual device RTT capability
                        boolean rttEnabled = false; // Default: assume no RTT support
                        // Check if this AP supports RTT (would need actual RTT scanning)
                        
                        // Add AP data with RTT flag
                        sensorFusion.addWiFiAPData(bssid, wifi.getSsid(), wifi.getFrequency(), rttEnabled);
                        
                    }

                    // Update UI
                    int count = sensorFusion.getWiFiFingerprintCount();
                    wifiFingerprintsCount.setText(String.valueOf(count));
                }
                
                // Schedule next collection
                refreshDataHandler.postDelayed(this, WIFI_FINGERPRINT_INTERVAL);
            }
        };
        
        refreshDataHandler.postDelayed(wiFiFingerprintTask, WIFI_FINGERPRINT_INTERVAL);
    }

    // BLE DATA COLLECTION
    private Runnable blEDataTask;
    private static final long BLE_DATA_INTERVAL = 5000; // 5 seconds

    private void startBLEDataCollection() {
        blEDataTask = new Runnable() {
            @Override
            public void run() {
                collectPairedBleDevices();
                
                // Schedule next collection
                refreshDataHandler.postDelayed(this, BLE_DATA_INTERVAL);
            }
        };
        
        refreshDataHandler.postDelayed(blEDataTask, BLE_DATA_INTERVAL);
    }

    @SuppressLint("MissingPermission")
    private void collectPairedBleDevices() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();
            for (BluetoothDevice device : bondedDevices) {
                List<String> serviceUuids = new ArrayList<>();
                ParcelUuid[] uuids = device.getUuids();
                if (uuids != null) {
                    for (ParcelUuid uuid : uuids) {
                        serviceUuids.add(uuid.toString());
                    }
                }

                String name = device.getName() != null ? device.getName() : "Unknown";
                sensorFusion.addBLEData(device.getAddress(), name, 0, 0, serviceUuids);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error collecting BLE data: " + e.getMessage());
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        googleMap = map;
        googleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        googleMap.getUiSettings().setZoomControlsEnabled(true);

        // Enable user location if permission granted
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            googleMap.setMyLocationEnabled(true);
        }

        // Setup building click listener
        setupBuildingClickListener();

        // Initialize legacy trajectory polyline
        trajectoryPolyline = googleMap.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(8f)
                .geodesic(true));

        // Initialize color-coded source polylines
        // PDR = Blue, GNSS = Green, WiFi = Orange, Fused = Red
        pdrPolyline = googleMap.addPolyline(new PolylineOptions()
                .color(Color.BLUE).width(6f).geodesic(true).zIndex(10));
        gnssPolyline2 = googleMap.addPolyline(new PolylineOptions()
                .color(Color.GREEN).width(6f).geodesic(true).zIndex(10));
        wifiPolyline = googleMap.addPolyline(new PolylineOptions()
                .color(Color.parseColor("#FF8C00")).width(6f).geodesic(true).zIndex(10)); // Dark orange
        fusedPolyline = googleMap.addPolyline(new PolylineOptions()
                .color(Color.RED).width(8f).geodesic(true).zIndex(11));

        // Move camera to campus center
        LatLng campusCenter = new LatLng(55.9234, -3.1761);
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(campusCenter, 17f));

        // Draw building outlines
        drawBuildingOutlines();
    }

    // BUILDING OUTLINES
    private void drawBuildingOutlines() {
        if (googleMap == null) return;

        for (BuildingLocation building : TARGET_BUILDINGS) {
            List<LatLng> circlePoints = createCircle(building.center, building.radiusMeters);

            Polygon polygon = googleMap.addPolygon(new PolygonOptions()
                    .addAll(circlePoints)
                    .strokeColor(building.outlineColor)
                    .strokeWidth(10f)
                    .fillColor(building.fillColor)
                    .clickable(true)
                    .zIndex(50));

            buildingPolygonMap.put(polygon, building.name);

            googleMap.addMarker(new MarkerOptions()
                    .position(building.center)
                    .title(building.name)
                    .snippet("Click to select venue")
                    .icon(BitmapDescriptorFactory.defaultMarker(building.markerHue))
                    .zIndex(60));
        }

        Toast.makeText(getContext(), "Tap building outline to select venue", Toast.LENGTH_SHORT).show();
    }

    private List<LatLng> createCircle(LatLng center, double radiusMeters) {
        List<LatLng> points = new ArrayList<>();
        int numPoints = 36;
        double earthRadius = 6371000; // meters

        for (int i = 0; i < numPoints; i++) {
            double angle = 2.0 * Math.PI * i / numPoints;
            double dx = radiusMeters * Math.cos(angle);
            double dy = radiusMeters * Math.sin(angle);
            double deltaLat = dy / earthRadius;
            double deltaLon = dx / (earthRadius * Math.cos(Math.PI * center.latitude / 180));
            double lat = center.latitude + (deltaLat * 180 / Math.PI);
            double lon = center.longitude + (deltaLon * 180 / Math.PI);
            points.add(new LatLng(lat, lon));
        }
        return points;
    }

    // BUILDING CLICK LISTENER
    private void setupBuildingClickListener() {
        if (googleMap == null) return;

        // Setup map click listener for marking corrected positions
        googleMap.setOnMapClickListener(latLng -> {
            // Add corrected position marker
            addCorrectedPositionMarker(latLng);
        });
        
        // Long press to set position anchor (correct drift)
        googleMap.setOnMapLongClickListener(latLng -> {
            setPositionAnchor(latLng);
        });

        googleMap.setOnPolygonClickListener(polygon -> {
            String buildingName = buildingPolygonMap.get(polygon);
            if (buildingName == null) {
                return;
            }

            if (allBuildingsData.containsKey(buildingName)) {
                showVenueSelectionDialog(buildingName);
            } else {
                loadBuildingDataForSelection(buildingName);
            }
        });
    }
    
    // POSITION ANCHOR (DRIFT CORRECTION)
    private Marker anchorMarker = null;
    
    /**
     * Set position anchor to correct accumulated drift.
     * Long-press on map where you actually are to fix positioning.
     */
    private void setPositionAnchor(LatLng latLng) {
        if (googleMap == null) return;
        
        // Remove old anchor marker
        if (anchorMarker != null) {
            anchorMarker.remove();
        }
        
        // Add anchor marker (green color)
        anchorMarker = googleMap.addMarker(new MarkerOptions()
                .position(latLng)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                .title("Position Anchor")
                .snippet("Long-press to set new anchor"));
        
        // Set anchor in fusion algorithm
        sensorFusion.setPositionAnchor(latLng.latitude, latLng.longitude);
        
        // Also update current position variables for smoother transition
        currentLocation = latLng;
        smoothedLat = latLng.latitude;
        smoothedLng = latLng.longitude;
        positionInitialized = true;
        lastTrajectoryPoint = latLng;
        
        // Update trajectory to include the corrected position
        if (trajectoryPolyline != null) {
            List<LatLng> points = trajectoryPolyline.getPoints();
            points.add(latLng);
            trajectoryPolyline.setPoints(points);
        }
        
        Log.d(TAG, "Position anchor set at: " + latLng.latitude + ", " + latLng.longitude);
        Toast.makeText(getContext(), "Position corrected! Drift will now reduce.", Toast.LENGTH_SHORT).show();
    }

    // CORRECTED POSITION MARKING
    private static final float CORRECTION_MARKER_COLOR = BitmapDescriptorFactory.HUE_YELLOW;
    private final List<Marker> correctionMarkers = new ArrayList<>();

    private void addCorrectedPositionMarker(LatLng latLng) {
        if (googleMap == null) return;

        // Add marker to map
        Marker marker = googleMap.addMarker(new MarkerOptions()
                .position(latLng)
                .icon(BitmapDescriptorFactory.defaultMarker(CORRECTION_MARKER_COLOR))
                .title("Corrected Position #" + (sensorFusion.getCorrectedPositionCount() + 1))
                .snippet("Tap to remove"));

        correctionMarkers.add(marker);

        // Add position to SensorFusion
        sensorFusion.addCorrectedPosition((float) latLng.latitude, (float) latLng.longitude);
        
        // Update UI count
        int count = sensorFusion.getCorrectedPositionCount();
        correctedPositionsCount.setText(String.valueOf(count));

        Log.d(TAG, "Position #" + count + " marked at: " + latLng.latitude + ", " + latLng.longitude);
        Toast.makeText(getContext(), "Position marked (#" + count + ")", Toast.LENGTH_SHORT).show();
    }

    // TEST POINT MARKING
    private static final float TEST_POINT_MARKER_COLOR = BitmapDescriptorFactory.HUE_VIOLET;

    private void addTestPointMarker(LatLng latLng, int pointNumber) {
        if (googleMap == null) return;

        // Create numbered marker bitmap
        BitmapDescriptor markerIcon = createNumberedMarker(pointNumber);

        // Add marker to map with numbered bitmap
        Marker marker = googleMap.addMarker(new MarkerOptions()
                .position(latLng)
                .icon(markerIcon)
                .title("Test Point #" + pointNumber)
                .snippet("Marked at: " + String.format("%.4f, %.4f", latLng.latitude, latLng.longitude))
                .zIndex(95));

        testPointMarkers.add(marker);

        Log.d(TAG, "Test Point #" + pointNumber + " marker added at: " + latLng.latitude + ", " + latLng.longitude);
    }

    /**
     * Create a custom numbered marker bitmap
     * Generates a purple marker with white number displayed on it
     * @param number The number to display on the marker
     * @return BitmapDescriptor for the marker
     */
    private BitmapDescriptor createNumberedMarker(int number) {
        // Create canvas for marker (size: 96x96 pixels)
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Draw purple circle background (to match TEST_POINT_MARKER_COLOR - HUE_VIOLET)
        Paint backgroundPaint = new Paint();
        backgroundPaint.setColor(Color.parseColor("#7C4DFF")); // purple/violet
        backgroundPaint.setAntiAlias(true);
        canvas.drawCircle(48, 48, 40, backgroundPaint);

        // Draw white number on top
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(50f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setAntiAlias(true);

        // Draw text centered in circle
        Rect textBounds = new Rect();
        String numberStr = String.valueOf(number);
        textPaint.getTextBounds(numberStr, 0, numberStr.length(), textBounds);
        int textHeight = textBounds.height();
        canvas.drawText(numberStr, 48, 48 + textHeight / 2, textPaint);

        // Convert to BitmapDescriptor
        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }

    // VENUE SELECTION DIALOG
    private void showVenueSelectionDialog(String buildingName) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Select Venue")
                .setMessage("Record trajectory in " + buildingName + "?")
                .setPositiveButton("Select", (dialog, which) -> {
                    selectVenue(buildingName);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void selectVenue(String buildingName) {
        currentSelectedBuilding = buildingName;
        NetworkUtils.BuildingData data = allBuildingsData.get(buildingName);

        if (data != null && !data.floors.isEmpty()) {
            // Update venue manager
            String firstFloor = new ArrayList<>(data.floors.keySet()).get(0);
            venueId = buildingName; // Use building name as ID
            venueName = buildingName;
            venueFloor = firstFloor;
            hasVenue = true;

            VenueManager.getInstance(requireContext())
                    .setSelectedVenue(venueName, venueId, venueFloor);

            // Show floor selector
            setupFloorSelector(data.floors, buildingName);
            drawFloor(firstFloor, data);

            // Update UI
            updateVenueDisplay();

            Toast.makeText(getContext(), "Venue selected: " + buildingName, Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Venue selected: " + buildingName + " - " + firstFloor);
        }
    }

    // FLOOR DISPLAY
    private void drawFloor(String floorName, NetworkUtils.BuildingData data) {
        if (googleMap == null) return;

        // Clear previous floor
        clearCurrentFloor();

        currentSelectedFloor = floorName;
        NetworkUtils.FloorData floorData = data.floors.get(floorName);
        if (floorData == null) return;

        // Draw configured floor image first so API walls/areas align on top.
        addIndoorFloorImageOverlay(floorName, floorData, data);

        // Draw walls (black lines)
        for (List<LatLng> wall : floorData.walls) {
            if (wall.size() >= 2) {
                Polyline line = googleMap.addPolyline(new PolylineOptions()
                        .addAll(wall)
                        .color(Color.BLACK)
                        .width(3f)
                        .zIndex(90));
                currentWallLines.add(line);
            }
        }

        // Draw areas (filled polygons)
        for (List<LatLng> area : floorData.areas) {
            if (area.size() >= 3) {
                Polygon poly = googleMap.addPolygon(new PolygonOptions()
                        .addAll(area)
                        .strokeColor(Color.DKGRAY)
                        .strokeWidth(2f)
                        .fillColor(Color.argb(35, 200, 200, 200))
                        .zIndex(80));
                currentAreaPolygons.add(poly);
            }
        }

        // Draw POIs (icons)
        for (NetworkUtils.Poi poi : floorData.pois) {
            Marker marker = googleMap.addMarker(new MarkerOptions()
                    .position(poi.position)
                    .title(poi.label)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                    .zIndex(100));
            currentPoiMarkers.add(marker);
        }

            // Apply API wall constraints to fusion so the trajectory cannot cross walls.
            int wallSegments = sensorFusion.configureIndoorWallConstraints(
                floorData.walls,
                extractFloorNumber(floorName)
            );

            // Supply lift/stair POI centres so floor switching is constrained to
            // known transition zones (Feature 1) and the elevator/stair mode can be
            // identified (Feature 2). Display-only: no effect on PDR or position fusion.
            List<LatLng> liftCenters  = new ArrayList<>();
            List<LatLng> stairCenters = new ArrayList<>();
            for (NetworkUtils.Poi poi : floorData.pois) {
                String t = poi.type;
                if (t.contains("lift") || t.contains("elevator")) {
                    liftCenters.add(poi.position);
                } else if (t.contains("stair")) {
                    stairCenters.add(poi.position);
                }
            }
            sensorFusion.setFloorTransitionZones(liftCenters, stairCenters);

        Log.d(TAG, "Floor drawn: " + currentWallLines.size() + " walls, "
            + currentAreaPolygons.size() + " areas, map-constrained wall segments=" + wallSegments);
    }

    private void clearCurrentFloor() {
        if (currentFloorImageOverlay != null) {
            currentFloorImageOverlay.remove();
            currentFloorImageOverlay = null;
        }
        for (Polyline line : currentWallLines) line.remove();
        for (Polygon poly : currentAreaPolygons) poly.remove();
        for (Marker marker : currentPoiMarkers) marker.remove();
        currentWallLines.clear();
        currentAreaPolygons.clear();
        currentPoiMarkers.clear();
    }

    private void addIndoorFloorImageOverlay(String floorName,
                                            NetworkUtils.FloorData floorData,
                                            NetworkUtils.BuildingData buildingData) {
        if (googleMap == null || currentSelectedBuilding == null) {
            return;
        }

        int floorNumber;
        try {
            floorNumber = extractFloorNumber(floorName);
        } catch (Exception e) {
            return;
        }

        int drawableRes = resolveFloorImageResource(
            currentSelectedBuilding,
            floorNumber,
            floorName,
            buildingData != null ? buildingData.floors.keySet() : null);
        if (drawableRes == 0) {
            return;
        }

        UprightOverlayConfig fixedConfig = getUprightOverlayConfig(
            currentSelectedBuilding,
            floorNumber,
            floorName,
            buildingData != null ? buildingData.floors.keySet() : null);

        if (fixedConfig != null) {
            currentFloorImageOverlay = googleMap.addGroundOverlay(new GroundOverlayOptions()
                    .image(BitmapDescriptorFactory.fromResource(drawableRes))
                    .position(fixedConfig.center, fixedConfig.widthM)
                    .bearing(fixedConfig.bearingDeg)
                    .transparency(FLOOR_IMAGE_TRANSPARENCY)
                    .zIndex(70f));

            return;
        }

        LatLngBounds bounds = buildApiAlignedBounds(floorData, currentSelectedBuilding);
        if (bounds == null) {
            return;
        }

        currentFloorImageOverlay = googleMap.addGroundOverlay(new GroundOverlayOptions()
                .image(BitmapDescriptorFactory.fromResource(drawableRes))
                .positionFromBounds(bounds)
                .transparency(FLOOR_IMAGE_TRANSPARENCY)
                .zIndex(70f));

    }

    private UprightOverlayConfig getUprightOverlayConfig(String buildingName,
                                                         int floorNumber,
                                                         String floorName,
                                                         Set<String> allFloorNames) {
        String normalized = buildingName == null ? "" : buildingName.toLowerCase();

        boolean isNucleus = normalized.equals("the nucleus building")
                || normalized.equals("the nucleus")
                || normalized.equals("nucleus");
        boolean isLibrary = normalized.equals("noreen and kenneth murray library")
                || normalized.equals("library")
                || normalized.contains("murray library");

        if (isNucleus) {
            UprightOverlayConfig nucleusBase = new UprightOverlayConfig(
                new LatLng(55.923041, -3.174234),
                46f,
                0f);
            UprightOverlayConfig tuned = applyFloorDelta(nucleusBase, getNucleusFloorDelta(floorNumber));
            return withOffset(
                tuned,
                    NUCLEUS_OVERLAY_LAT_OFFSET,
                    NUCLEUS_OVERLAY_LNG_OFFSET);
        }

        if (isLibrary) {
            int mappedFloor = floorNumber;
            boolean hasExplicitGround = hasGroundLikeFloorLabel(allFloorNames);
            if (!hasExplicitGround && mappedFloor >= 1) {
            mappedFloor -= 1;
            }
            if (isGroundLikeLabel(floorName)) {
            mappedFloor = 0;
            }

            UprightOverlayConfig libraryBase = new UprightOverlayConfig(
                new LatLng(55.9229, -3.1750),
                26.0f,
                0f);
            UprightOverlayConfig tuned = applyFloorDelta(libraryBase, getLibraryFloorDelta(mappedFloor));
            return withOffset(
                tuned,
                    LIBRARY_OVERLAY_LAT_OFFSET,
                    LIBRARY_OVERLAY_LNG_OFFSET);
        }

        return null;
    }

    private int resolveFloorImageResource(String buildingName,
                                          int floorNumber,
                                          String floorName,
                                          Set<String> allFloorNames) {
        String normalized = buildingName.toLowerCase();

        boolean isNucleus = normalized.equals("the nucleus building")
                || normalized.equals("the nucleus")
                || normalized.equals("nucleus");
        boolean isLibrary = normalized.equals("noreen and kenneth murray library")
                || normalized.equals("library")
                || normalized.contains("murray library");

        if (isNucleus) {
            switch (floorNumber) {
                case -1:
                    return R.drawable.nucleuslg;
                case 0:
                    return R.drawable.nucleusg;
                case 1:
                    return R.drawable.nucleus1;
                case 2:
                    return R.drawable.nucleus2;
                case 3:
                    return R.drawable.nucleus3;
                default:
                    return 0;
            }
        }

        if (isLibrary) {
            // Some Library API payloads are 1-based (1 means ground floor).
            // If no explicit G/GF/0 label exists, shift positive floors by -1.
            int mappedFloor = floorNumber;
            boolean hasExplicitGround = hasGroundLikeFloorLabel(allFloorNames);
            if (!hasExplicitGround && mappedFloor >= 1) {
                mappedFloor -= 1;
            }

            if (isGroundLikeLabel(floorName) || mappedFloor == 0) {
                return R.drawable.libraryg;
            }

            switch (mappedFloor) {
                case 1:
                    return R.drawable.library1;
                case 2:
                    return R.drawable.library2;
                case 3:
                    return R.drawable.library3;
                default:
                    return 0;
            }
        }

        return 0;
    }

    private boolean hasGroundLikeFloorLabel(Set<String> floorNames) {
        if (floorNames == null || floorNames.isEmpty()) {
            return false;
        }
        for (String floorName : floorNames) {
            if (isGroundLikeLabel(floorName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isGroundLikeLabel(String floorName) {
        if (floorName == null) {
            return false;
        }
        String normalized = floorName.toLowerCase().replace("[", "").replace("]", "").trim();
        return normalized.equals("g")
                || normalized.equals("gf")
                || normalized.equals("ground")
                || normalized.equals("ground_floor")
                || normalized.equals("0");
    }

    private LatLngBounds buildApiAlignedBounds(NetworkUtils.FloorData floorData, String buildingName) {
        double minLat = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double minLng = Double.POSITIVE_INFINITY;
        double maxLng = Double.NEGATIVE_INFINITY;

        // Prefer wall geometry for floor-image alignment. Areas/POIs may contain
        // outliers and can make overlays spill into neighboring buildings.
        minLat = updateMinLatLngFromPolylines(floorData.walls, minLat, true);
        maxLat = updateMaxLatLngFromPolylines(floorData.walls, maxLat, true);
        minLng = updateMinLatLngFromPolylines(floorData.walls, minLng, false);
        maxLng = updateMaxLatLngFromPolylines(floorData.walls, maxLng, false);

        // If walls are not available, fall back to areas.
        if (!Double.isFinite(minLat) || !Double.isFinite(maxLat)
                || !Double.isFinite(minLng) || !Double.isFinite(maxLng)) {
            minLat = updateMinLatLngFromPolylines(floorData.areas, minLat, true);
            maxLat = updateMaxLatLngFromPolylines(floorData.areas, maxLat, true);
            minLng = updateMinLatLngFromPolylines(floorData.areas, minLng, false);
            maxLng = updateMaxLatLngFromPolylines(floorData.areas, maxLng, false);
        }

        if (!Double.isFinite(minLat) || !Double.isFinite(maxLat)
                || !Double.isFinite(minLng) || !Double.isFinite(maxLng)) {
            return fallbackBoundsFromBuildingCenter(buildingName);
        }

        double latPad = Math.max((maxLat - minLat) * 0.03, 0.00001);
        double lngPad = Math.max((maxLng - minLng) * 0.03, 0.00001);

        LatLng southWest = new LatLng(minLat - latPad, minLng - lngPad);
        LatLng northEast = new LatLng(maxLat + latPad, maxLng + lngPad);
        LatLngBounds apiBounds = new LatLngBounds(southWest, northEast);
        return constrainBoundsToBuilding(apiBounds, buildingName);
    }

    private LatLngBounds constrainBoundsToBuilding(LatLngBounds sourceBounds, String buildingName) {
        LatLngBounds buildingBounds = fallbackBoundsFromBuildingCenter(buildingName);
        if (buildingBounds == null) {
            return sourceBounds;
        }

        LatLng sw = sourceBounds.southwest;
        LatLng ne = sourceBounds.northeast;
        LatLng bsw = buildingBounds.southwest;
        LatLng bne = buildingBounds.northeast;

        double sourceLatSpan = ne.latitude - sw.latitude;
        double sourceLngSpan = ne.longitude - sw.longitude;
        double buildingLatSpan = bne.latitude - bsw.latitude;
        double buildingLngSpan = bne.longitude - bsw.longitude;

        if (sourceLatSpan <= 0 || sourceLngSpan <= 0 || buildingLatSpan <= 0 || buildingLngSpan <= 0) {
            return buildingBounds;
        }

        // Keep bounds centered to avoid one-sided clipping that visually shifts
        // overlays toward lower-left.
        double targetLatSpan = Math.min(sourceLatSpan, buildingLatSpan * 0.95);
        double targetLngSpan = Math.min(sourceLngSpan, buildingLngSpan * 0.95);

        double sourceCenterLat = (sw.latitude + ne.latitude) * 0.5;
        double sourceCenterLng = (sw.longitude + ne.longitude) * 0.5;

        double minCenterLat = bsw.latitude + targetLatSpan * 0.5;
        double maxCenterLat = bne.latitude - targetLatSpan * 0.5;
        double minCenterLng = bsw.longitude + targetLngSpan * 0.5;
        double maxCenterLng = bne.longitude - targetLngSpan * 0.5;

        double centerLat = Math.min(Math.max(sourceCenterLat, minCenterLat), maxCenterLat);
        double centerLng = Math.min(Math.max(sourceCenterLng, minCenterLng), maxCenterLng);

        double south = centerLat - targetLatSpan * 0.5;
        double north = centerLat + targetLatSpan * 0.5;
        double west = centerLng - targetLngSpan * 0.5;
        double east = centerLng + targetLngSpan * 0.5;

        return new LatLngBounds(new LatLng(south, west), new LatLng(north, east));
    }

    private double updateMinLatLngFromPolylines(List<List<LatLng>> groups, double currentMin, boolean latitude) {
        if (groups == null) return currentMin;
        double min = currentMin;
        for (List<LatLng> group : groups) {
            if (group == null) continue;
            for (LatLng point : group) {
                if (point == null) continue;
                min = Math.min(min, latitude ? point.latitude : point.longitude);
            }
        }
        return min;
    }

    private double updateMaxLatLngFromPolylines(List<List<LatLng>> groups, double currentMax, boolean latitude) {
        if (groups == null) return currentMax;
        double max = currentMax;
        for (List<LatLng> group : groups) {
            if (group == null) continue;
            for (LatLng point : group) {
                if (point == null) continue;
                max = Math.max(max, latitude ? point.latitude : point.longitude);
            }
        }
        return max;
    }

    private LatLngBounds fallbackBoundsFromBuildingCenter(String buildingName) {
        for (BuildingLocation building : TARGET_BUILDINGS) {
            if (!building.name.equals(buildingName)) {
                continue;
            }

            // Use a generous but building-local bound so one building image
            // cannot span and cover neighboring buildings.
            double radiusM = building.radiusMeters * 2.2;
            double dLat = radiusM / 111000.0;
            double dLng = radiusM / (111000.0 * Math.cos(Math.toRadians(building.center.latitude)));

            LatLng southWest = new LatLng(building.center.latitude - dLat, building.center.longitude - dLng);
            LatLng northEast = new LatLng(building.center.latitude + dLat, building.center.longitude + dLng);
            return new LatLngBounds(southWest, northEast);
        }
        return null;
    }

    // FLOOR SELECTOR
    private void setupFloorSelector(Map<String, NetworkUtils.FloorData> floors, String buildingName) {
        if (floorButtonLayout == null || getContext() == null) return;

        backToRecordingButton.setVisibility(View.VISIBLE);
        floorButtonLayout.removeAllViews();
        isFloorSelectorExpanded = false;

        List<String> sortedFloors = sortFloorNames(new ArrayList<>(floors.keySet()));

        // Calculate full expanded width: toggle + (AUTO + floors) * per-button-width
        int togglePx   = dpToPx(56);
        int otherBtnPx = 110 + dpToPx(2) * 2;          // button width + left/right margin
        int cardPadPx  = dpToPx(4) * 2;                 // LinearLayout padding
        floorSelectorExpandedWidth = togglePx + otherBtnPx * (1 + sortedFloors.size()) + cardPadPx;

        // Toggle button (drag handle + expand/collapse)
        String initialLabel = (autoFloorFollowEnabled || venueFloor.isEmpty()) ? "AUTO" : venueFloor;
        floorToggleButton = new MaterialButton(getContext());
        floorToggleButton.setText(initialLabel);
        floorToggleButton.setTextSize(11);
        floorToggleButton.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(togglePx, togglePx);
        floorToggleButton.setLayoutParams(toggleParams);
        floorToggleButton.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
        floorToggleButton.setCornerRadius(dpToPx(10));
        floorToggleButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(getContext(), R.color.md_theme_primary)));
        setupFloorToggleDragAndTap(floorToggleButton);
        floorButtonLayout.addView(floorToggleButton);

        // AUTO button
        MaterialButton autoButton = new MaterialButton(getContext());
        autoButton.setText("AUTO");
        autoButton.setTextSize(12);
        LinearLayout.LayoutParams autoParams = new LinearLayout.LayoutParams(110, LinearLayout.LayoutParams.WRAP_CONTENT);
        autoParams.setMargins(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2));
        autoButton.setLayoutParams(autoParams);
        autoButton.setPadding(8, 4, 8, 4);
        autoButton.setCornerRadius(6);
        autoButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(getContext(), R.color.md_theme_primary)));
        autoButton.setOnClickListener(v -> {
            autoFloorFollowEnabled = true;
            floorToggleButton.setText("AUTO");
            collapseFloorSelector();
            Toast.makeText(getContext(), "Auto floor ON", Toast.LENGTH_SHORT).show();
        });
        floorButtonLayout.addView(autoButton);

        // Floor buttons
        for (String floorName : sortedFloors) {
            MaterialButton btn = new MaterialButton(getContext());
            btn.setText(floorName);
            btn.setTextSize(13);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(110, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2));
            btn.setLayoutParams(params);
            btn.setPadding(8, 4, 8, 4);
            btn.setCornerRadius(6);
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(getContext(), R.color.md_theme_secondary)));
            btn.setOnClickListener(v -> {
                NetworkUtils.BuildingData data = allBuildingsData.get(currentSelectedBuilding);
                if (data != null) {
                    autoFloorFollowEnabled = false;
                    drawFloor(floorName, data);
                    venueFloor = floorName;
                    floorToggleButton.setText(floorName);
                    collapseFloorSelector();
                    VenueManager.getInstance(requireContext()).setSelectedVenue(venueName, venueId, floorName);
                    updateVenueDisplay();
                    Toast.makeText(getContext(), "Floor: " + floorName + " (manual)", Toast.LENGTH_SHORT).show();
                }
            });
            floorButtonLayout.addView(btn);
        }

        // Show as collapsed square
        ViewGroup.LayoutParams lp = floorSelectorContainer.getLayoutParams();
        lp.width = dpToPx(56);
        floorSelectorContainer.setLayoutParams(lp);
        floorSelectorContainer.setVisibility(View.VISIBLE);

        backToRecordingButton.setOnClickListener(v -> returnToRecording());
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupFloorToggleDragAndTap(MaterialButton toggleBtn) {
        final float[] down = new float[4]; // rawX, rawY, transX, transY
        final boolean[] dragging = {false};
        final int threshold = dpToPx(6);

        toggleBtn.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                    down[2] = floorSelectorContainer.getTranslationX();
                    down[3] = floorSelectorContainer.getTranslationY();
                    dragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - down[0];
                    float dy = event.getRawY() - down[1];
                    if (!dragging[0] && (Math.abs(dx) > threshold || Math.abs(dy) > threshold)) {
                        dragging[0] = true;
                    }
                    if (dragging[0]) {
                        floorSelectorContainer.setTranslationX(down[2] + dx);
                        floorSelectorContainer.setTranslationY(down[3] + dy);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging[0]) {
                        // It's a tap: toggle expand/collapse
                        if (isFloorSelectorExpanded) {
                            collapseFloorSelector();
                        } else {
                            expandFloorSelector();
                        }
                    }
                    return true;
            }
            return false;
        });
    }

    private void expandFloorSelector() {
        int startWidth = dpToPx(56);
        ValueAnimator anim = ValueAnimator.ofInt(startWidth, floorSelectorExpandedWidth);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(animation -> {
            ViewGroup.LayoutParams lp = floorSelectorContainer.getLayoutParams();
            lp.width = (int) animation.getAnimatedValue();
            floorSelectorContainer.setLayoutParams(lp);
        });
        anim.start();
        isFloorSelectorExpanded = true;
    }

    private void collapseFloorSelector() {
        int startWidth = floorSelectorContainer.getWidth();
        int endWidth   = dpToPx(56);
        ValueAnimator anim = ValueAnimator.ofInt(startWidth, endWidth);
        anim.setDuration(180);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(animation -> {
            ViewGroup.LayoutParams lp = floorSelectorContainer.getLayoutParams();
            lp.width = (int) animation.getAnimatedValue();
            floorSelectorContainer.setLayoutParams(lp);
        });
        anim.start();
        isFloorSelectorExpanded = false;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void returnToRecording() {
        floorSelectorContainer.setTranslationX(0);
        floorSelectorContainer.setTranslationY(0);
        floorSelectorContainer.setVisibility(View.GONE);
        isFloorSelectorExpanded = false;
        floorToggleButton = null;
        backToRecordingButton.setVisibility(View.GONE);
        clearCurrentFloor();
    }

    private List<String> sortFloorNames(List<String> floorNames) {
        Collections.sort(floorNames, (f1, f2) -> {
            try {
                int n1 = extractFloorNumber(f1);
                int n2 = extractFloorNumber(f2);
                return Integer.compare(n2, n1);
            } catch (Exception e) {
                return f1.compareTo(f2);
            }
        });
        return floorNames;
    }

    private int extractFloorNumber(String floorName) {
        String normalized = floorName.toLowerCase().replace("[", "").replace("]", "").trim();

        if (normalized.isEmpty()) return 0;

        if (normalized.equals("g") || normalized.equals("gf") || normalized.equals("ground")
                || normalized.equals("ground_floor") || normalized.equals("ug")
                || normalized.equals("upper_ground")) {
            return 0;
        }

        if (normalized.equals("lg") || normalized.equals("lower_ground")
                || normalized.equals("lower_ground_floor")) {
            return -1;
        }

        if (normalized.contains("basement")) {
            Integer basementIndex = extractFirstInteger(normalized);
            return basementIndex != null ? -Math.abs(basementIndex) : -1;
        }

        if (normalized.matches("b\\d+")) {
            return -Integer.parseInt(normalized.substring(1));
        }

        if (normalized.matches("b[-_ ]?\\d+")) {
            String digits = normalized.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? -1 : -Integer.parseInt(digits);
        }

        if (normalized.matches("f\\d+")) {
            return Integer.parseInt(normalized.substring(1));
        }

        String clean = normalized.replaceAll("[^0-9-]", "");
        if (clean.isEmpty() || clean.equals("-")) return 0;
        return Integer.parseInt(clean);
    }

    private Integer extractFirstInteger(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        return Integer.parseInt(digits);
    }

    private int normalizeFloorForVenue(int inferredFloor, float elevationM) {
        boolean isNucleus = "The Nucleus Building".equals(currentSelectedBuilding)
                || "The Nucleus Building".equals(venueName)
                || "The Nucleus".equals(venueName);

        if (!isNucleus) {
            return inferredFloor;
        }

        // If WiFi has provided an absolute floor anchor, trust the dual-phase result
        // (WiFi floor + barometer delta) directly. This survives HVAC positive-pressure
        // anomalies on F1 that push raw elevation to -2.8 m even on the ground floor.
        if (sensorFusion.hasWifiFloorAnchor()) {
            return inferredFloor;
        }

        // Before WiFi anchor: trust the zone-gated result from getInferredFloor().
        // The previous raw-elevation heuristic was removed because the elevation estimate
        // is affected by accelerometer noise (shaking causes ±5 m swings), which caused
        // floor changes even when the user was nowhere near a lift or staircase.

        return inferredFloor;
    }

    private void syncInferredFloorToVenue(int inferredFloor) {
        if (!autoFloorFollowEnabled) {
            return;
        }

        if (!hasVenue || currentSelectedBuilding == null) {
            venueFloor = String.valueOf(inferredFloor);
            return;
        }

        NetworkUtils.BuildingData data = allBuildingsData.get(currentSelectedBuilding);
        if (data == null || data.floors == null || data.floors.isEmpty()) {
            venueFloor = String.valueOf(inferredFloor);
            return;
        }

        String targetFloorName = findClosestFloorName(inferredFloor, data.floors.keySet());
        if (targetFloorName == null) {
            venueFloor = String.valueOf(inferredFloor);
            return;
        }

        venueFloor = targetFloorName;

        if (!targetFloorName.equals(currentSelectedFloor)) {
            Log.w(TAG, "[FloorChange] *** FLOOR CHANGING: " + currentSelectedFloor + " → " + targetFloorName
                    + " | inferredFloor=" + inferredFloor
                    + " | elev=" + sensorFusion.getElevation()
                    + " | wifiAnchor=" + sensorFusion.hasWifiFloorAnchor() + " ***");
            drawFloor(targetFloorName, data);
            VenueManager.getInstance(requireContext())
                    .setSelectedVenue(venueName, venueId, targetFloorName);
            updateVenueDisplay();
            Log.d(TAG, "Auto floor switched to: " + targetFloorName + " (inferred=" + inferredFloor + ")");
        }
    }

    private String findClosestFloorName(int inferredFloor, Collection<String> floorNames) {
        String exactMatch = null;
        String closest = null;
        int minDistance = Integer.MAX_VALUE;

        for (String name : floorNames) {
            int mappedFloor;
            try {
                mappedFloor = extractFloorNumber(name);
            } catch (Exception ignored) {
                continue;
            }

            if (mappedFloor == inferredFloor) {
                exactMatch = name;
                break;
            }

            int distance = Math.abs(mappedFloor - inferredFloor);
            if (distance < minDistance) {
                minDistance = distance;
                closest = name;
            }
        }

        return exactMatch != null ? exactMatch : closest;
    }

    // LOAD BUILDING FROM API
    private void loadBuildingDataForSelection(String buildingName) {
        BuildingLocation selectedBuilding = null;
        for (BuildingLocation building : TARGET_BUILDINGS) {
            if (building.name.equals(buildingName)) {
                selectedBuilding = building;
                break;
            }
        }

        if (selectedBuilding == null) {
            Toast.makeText(getContext(), "Unknown building: " + buildingName, Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(getContext(), "Loading " + buildingName + "...", Toast.LENGTH_SHORT).show();

        final BuildingLocation buildingToLoad = selectedBuilding;
        NetworkUtils.fetchFloorPlan(
                buildingToLoad.center.latitude,
                buildingToLoad.center.longitude,
                new NetworkUtils.Callback() {
                    @Override
                    public void onSuccess(NetworkUtils.BuildingData buildingData) {
                        if (isAdded() && getContext() != null) {
                            allBuildingsData.put(buildingToLoad.name, buildingData);
                            Log.d(TAG, "Loaded " + buildingToLoad.name + ": "
                                    + buildingData.floors.size() + " floors");
                            showVenueSelectionDialog(buildingToLoad.name);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (isAdded() && getContext() != null) {
                            Toast.makeText(getContext(), "Failed to load " + buildingToLoad.name, Toast.LENGTH_SHORT).show();
                        }
                        Log.e(TAG, "Failed to load " + buildingToLoad.name + ": " + error);
                    }
                }
        );
    }

    // VENUE DISPLAY UI
    private void setupVenueDisplay() {
        updateVenueDisplay();

        if (changeVenueButton != null) {
            changeVenueButton.setOnClickListener(v -> {
                // Allow user to change venue during recording
                Toast.makeText(getContext(),
                        "Tap building outline to select venue",
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    private void updateVenueDisplay() {
        if (venueInfoText == null) return;

        if (hasVenue && !venueName.isEmpty()) {
            String displayText = "📍 " + venueName;
            if (!venueFloor.isEmpty()) {
                displayText += " - " + venueFloor;
            }
            venueInfoText.setText(displayText);
            venueInfoText.setVisibility(View.VISIBLE);
            if (changeVenueButton != null) {
                changeVenueButton.setVisibility(View.VISIBLE);
            }
        } else {
            venueInfoText.setText("📍 Outdoor (No venue selected)");
            venueInfoText.setVisibility(View.VISIBLE);
            if (changeVenueButton != null) {
                changeVenueButton.setVisibility(View.VISIBLE);
            }
        }
    }

    // BOTTOM DRAWER

    private void setupBottomDrawer(View view) {
        bottomDrawer = view.findViewById(R.id.bottomDrawer);
        expandDrawerTab = view.findViewById(R.id.expandDrawerTab);

        // Swipe anywhere on the white panel to hide it
        bottomDrawer.setOnSwipeDownListener(new SwipeDownLinearLayout.OnSwipeDownListener() {
            @Override
            public void onDrag(float dy) {
                // Panel follows the finger in real time
                bottomDrawer.setTranslationY(dy);
            }

            @Override
            public void onRelease(float dy) {
                if (dy > bottomDrawer.getHeight() * 0.25f) {
                    // Swiped far enough — complete the hide
                    hideBottomDrawer();
                } else {
                    // Not far enough — spring back to original position
                    bottomDrawer.animate()
                            .translationY(0)
                            .setDuration(200)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                }
            }
        });

        expandDrawerTab.setOnClickListener(v -> showBottomDrawer());
    }

    private void hideBottomDrawer() {
        // Animate from wherever the finger released to fully off-screen
        bottomDrawer.animate()
                .translationY(bottomDrawer.getHeight())
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    bottomDrawer.setVisibility(View.INVISIBLE);
                    expandDrawerTab.setVisibility(View.VISIBLE);
                })
                .start();
    }

    private void showBottomDrawer() {
        expandDrawerTab.setVisibility(View.GONE);
        bottomDrawer.setVisibility(View.VISIBLE);
        bottomDrawer.setTranslationY(bottomDrawer.getHeight());
        bottomDrawer.animate()
                .translationY(0)
                .setDuration(280)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    // RECORDING CONTROLS
    private void setupRecordingControls() {
        // Complete button
        completeButton.setOnClickListener(v -> {
            if (autoStop != null) autoStop.cancel();
            sensorFusion.stopRecording();

            // Pass venue info to correction screen
            Bundle venueBundle = new Bundle();
            venueBundle.putBoolean("has_venue", hasVenue);
            venueBundle.putString("venue_id", venueId);
            venueBundle.putString("venue_name", venueName);
            venueBundle.putString("venue_floor", venueFloor);

            Log.d(TAG, "Recording completed with venue: " + venueName);
            ((RecordingActivity) requireActivity()).showCorrectionScreen();
        });

        // Cancel button
        cancelButton.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(requireActivity())
                    .setTitle("Confirm Cancel")
                    .setMessage("Cancel recording? Progress will be lost!")
                    .setNegativeButton("Yes", (dialogInterface, which) -> {
                        sensorFusion.stopRecording();
                        if (autoStop != null) autoStop.cancel();
                        requireActivity().onBackPressed();
                    })
                    .setPositiveButton("No", (dialogInterface, which) -> {
                        dialogInterface.dismiss();
                    })
                    .create();

            dialog.setOnShowListener(dialogInterface -> {
                android.widget.Button negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                negativeButton.setTextColor(Color.RED);
            });

            dialog.show();
        });

        // Set Initial Position button
        setInitialPositionButton.setOnClickListener(v -> {
            try {
                float[] gnssPos = sensorFusion.getGNSSLatitude(true);
                if (gnssPos != null && gnssPos.length >= 2) {
                    sensorFusion.setInitialPosition(gnssPos[0], gnssPos[1], 0);
                    initialPositionStatus.setText("Set ✓");
                    initialPositionStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_dark));
                    initialPositionIndicator.setVisibility(View.VISIBLE);
                    Toast.makeText(requireContext(), "Position: " + String.format("%.4f, %.4f", gnssPos[0], gnssPos[1]), Toast.LENGTH_LONG).show();
                    Log.i(TAG, String.format("Initial position set | Lat: %.6f | Lon: %.6f", gnssPos[0], gnssPos[1]));
                } else {
                    LatLng fallbackLocation = currentLocation != null ? currentLocation :
                        new LatLng(55.9234, -3.1761); // Default to campus center
                    sensorFusion.setInitialPosition((float) fallbackLocation.latitude, (float) fallbackLocation.longitude, 0);
                    initialPositionStatus.setText("Set ✓ (approx)");
                    initialPositionStatus.setTextColor(requireContext().getColor(android.R.color.holo_orange_dark));
                    initialPositionIndicator.setVisibility(View.VISIBLE);
                    Toast.makeText(requireContext(), "Using approximate position (GPS unavailable)", Toast.LENGTH_LONG).show();
                    Log.w(TAG, String.format("GPS unavailable, using fallback | Lat: %.6f | Lon: %.6f", fallbackLocation.latitude, fallbackLocation.longitude));
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in Set Position button: " + e.getMessage(), e);
                Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        // Mark Test Point button
        markTestPointButton.setOnClickListener(v -> {
            try {
                // Get current location
                LatLng testPointLocation = currentLocation != null ? currentLocation : 
                    new LatLng(55.9234, -3.1761);
                
                // Add test point via SensorFusion
                int pointNumber = sensorFusion.addTestPoint(
                    testPointLocation.latitude, 
                    testPointLocation.longitude, 
                    venueFloor
                );
                
                // Add marker on map
                addTestPointMarker(testPointLocation, pointNumber);
                
                // Update UI count
                testPointsCount.setText(String.valueOf(sensorFusion.getTestPointCount()));
                
                Toast.makeText(requireContext(), "Test Point #" + pointNumber + " marked", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Test point #" + pointNumber + " marked at: " + testPointLocation.latitude + ", " + testPointLocation.longitude);
                
            } catch (Exception e) {
                Log.e(TAG, "Error marking test point: " + e.getMessage(), e);
                Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        // Initialize UI defaults
        if (gnssError != null) gnssError.setVisibility(View.GONE);
        if (elevation != null) elevation.setText(getString(R.string.elevation, "0"));
        if (distanceTravelled != null) distanceTravelled.setText(getString(R.string.meter, "0"));

        // Initialize new sensor data UI
        updateTrajectoryIdDisplay();
        // Check actual initial position state instead of hardcoding "Not set"
        if (initialPositionStatus != null) {
            if (sensorFusion.isInitialPositionSet()) {
                initialPositionStatus.setText("Set ✓");
                initialPositionStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_dark));
                if (initialPositionIndicator != null) initialPositionIndicator.setVisibility(View.VISIBLE);
            } else {
                initialPositionStatus.setText("Not set");
                initialPositionStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_dark));
                if (initialPositionIndicator != null) initialPositionIndicator.setVisibility(View.GONE);
            }
        }
        if (wifiFingerprintsCount != null) wifiFingerprintsCount.setText("0");
        if (correctedPositionsCount != null) correctedPositionsCount.setText("0");
        if (testPointsCount != null) testPointsCount.setText("0");  // Initialize test points counter
    }

    // UI UPDATE
    private void updateTrajectoryIdDisplay() {
        if (trajectoryIdText == null) return;
        String trajectoryId = sensorFusion.getTrajectoryId();
        if (trajectoryId != null && !trajectoryId.isEmpty()) {
            trajectoryIdText.setText(trajectoryId);
        } else {
            trajectoryIdText.setText("--");
        }
    }

    // Minimum distance (in meters) to add a new trajectory point
    private static final float MIN_TRAJECTORY_POINT_DISTANCE = 0.25f;
    private LatLng lastTrajectoryPoint = null;
    private LatLng secondLastTrajectoryPoint = null;  // For direction checking
    private LatLng lastCameraLocation = null;
    private long lastCameraUpdateTime = 0;
    private static final long CAMERA_UPDATE_INTERVAL_MS = 250;
    private static final float MIN_CAMERA_MOVE_DISTANCE_M = 0.25f;
    
    // Anti-jitter: smooth position output
    private double smoothedLat = 0.0;
    private double smoothedLng = 0.0;
    private boolean positionInitialized = false;
    private static final float POSITION_SMOOTHING_BASE = 0.85f;
    private static final float POSITION_SMOOTHING_FAST = 0.97f;

    private void updateUIandPosition() {
        // Sensor data counts
        updateTrajectoryIdDisplay();

        if (wifiFingerprintsCount != null) {
            wifiFingerprintsCount.setText(String.valueOf(sensorFusion.getWiFiFingerprintCount()));
        }
        if (correctedPositionsCount != null) {
            correctedPositionsCount.setText(String.valueOf(sensorFusion.getCorrectedPositionCount()));
        }
        if (testPointsCount != null) {
            testPointsCount.setText(String.valueOf(sensorFusion.getTestPointCount()));
        }
        if (initialPositionStatus != null) {
            if (sensorFusion.isInitialPositionSet()) {
                initialPositionStatus.setText("Set ✓");
                initialPositionStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_dark));
                if (initialPositionIndicator != null) initialPositionIndicator.setVisibility(View.VISIBLE);
            } else {
                initialPositionStatus.setText("Not set");
                initialPositionStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_dark));
                if (initialPositionIndicator != null) initialPositionIndicator.setVisibility(View.GONE);
            }
        }

        // Elevation & distance
        float[] pdrValues = sensorFusion.getSmoothedPDRPosition();
        if (pdrValues != null) {
            float deltaX = pdrValues[0] - previousPosX;
            float deltaY = pdrValues[1] - previousPosY;
            float movementDelta = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
            if (movementDelta > 0.01f && distanceTravelled != null) {
                distance += movementDelta;
                distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));
            }
            previousPosX = pdrValues[0];
            previousPosY = pdrValues[1];
        }

        if (elevation != null) {
            elevation.setText(getString(R.string.elevation,
                    String.format("%.1f", sensorFusion.getElevation())));
        }

        // Floor display
        int inferredFloor = sensorFusion.getInferredFloor();
        float elevForLog = sensorFusion.getElevation();
        int normalizedFloor = normalizeFloorForVenue(inferredFloor, elevForLog);
        if (inferredFloor != normalizedFloor) {
            Log.w(TAG, "[ZoneGate] normalizeFloorForVenue OVERRIDE: inferred=" + inferredFloor
                    + " → normalized=" + normalizedFloor + " elev=" + elevForLog
                    + " wifiAnchor=" + sensorFusion.hasWifiFloorAnchor());
        }
        if (floorDisplayText != null) {
            floorDisplayText.setText("Floor: " + normalizedFloor);
        }
        syncInferredFloorToVenue(normalizedFloor);

        // ── Position: prefer Particle Filter, fall back to SimplePositionFusion ──
        LatLng rawLocation = sensorFusion.getParticleFilterPosition();
        FusionManager.PositionSource source = sensorFusion.getLastPositionSource();

        if (rawLocation == null) {
            // Fallback to legacy fused position
            rawLocation = sensorFusion.getFusedPosition();
            source = FusionManager.PositionSource.PDR;
        }

        if (rawLocation == null) {
            // Last resort: use latest GNSS fix
            float[] latLngArray = sensorFusion.getGNSSLatitude(false);
            if (latLngArray != null && latLngArray[0] != 0) {
                rawLocation = new LatLng(latLngArray[0], latLngArray[1]);
                source = FusionManager.PositionSource.GNSS;
            }
        }

        if (rawLocation == null || googleMap == null) return;

        long now = System.currentTimeMillis();

        // Position smoothing (controlled by the Smooth Trajectory toggle)
        // OFF (default): use EKF+PF fusion output directly — no extra lag.
        // ON: apply a low-pass filter (alpha=0.85) on top of the fusion output.
        //     This deliberately introduces systematic lag so the user can see
        //     the trailing-marker artefact when reversing direction.
        if (smoothTrajectoryEnabled && positionInitialized) {
            smoothedLat = POSITION_SMOOTHING_BASE * smoothedLat
                    + (1.0f - POSITION_SMOOTHING_BASE) * rawLocation.latitude;
            smoothedLng = POSITION_SMOOTHING_BASE * smoothedLng
                    + (1.0f - POSITION_SMOOTHING_BASE) * rawLocation.longitude;
        } else {
            smoothedLat = rawLocation.latitude;
            smoothedLng = rawLocation.longitude;
        }
        positionInitialized = true;
        LatLng newLocation = new LatLng(smoothedLat, smoothedLng);
        currentLocation = newLocation;

        // Color-coded trajectory update (1 s interval OR on movement)
        boolean timeElapsed = (now - lastTrajectoryUpdateTime) >= TRAJECTORY_UPDATE_INTERVAL_MS;
        boolean movedEnough = (lastTrajectoryPoint == null)
            || calculateDistance(lastTrajectoryPoint, newLocation) > MIN_TRAJECTORY_POINT_DISTANCE;

        updateRawSensorObservationOverlays(now);

        if (timeElapsed || movedEnough) {
            // Always draw fused trajectory as a red line.
            if (fusedPolyline != null) {
                List<LatLng> pts = fusedPolyline.getPoints();
                pts.add(newLocation);
                fusedPolyline.setPoints(pts);
            }

            // Persist the same fused point the user sees, so replay matches recording.
            sensorFusion.addReplayTrackPoint(newLocation.latitude, newLocation.longitude);

            secondLastTrajectoryPoint = lastTrajectoryPoint;
            lastTrajectoryPoint = newLocation;
            lastTrajectoryUpdateTime = now;
            lastPolylineSource = source;
        }

        // User marker
        if (userMarker == null) {
            // Keep fused marker fixed as a red pin.
            userMarker = googleMap.addMarker(new MarkerOptions()
                    .position(newLocation)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .title("Fused Position")
                    .flat(true)
                    .anchor(0.5f, 0.5f)
                    .zIndex(999));
        } else {
            userMarker.setPosition(newLocation);
            userMarker.setRotation((float) Math.toDegrees(sensorFusion.passOrientation()));
        }

        // Camera
        boolean cameraTimeOk = (now - lastCameraUpdateTime) > CAMERA_UPDATE_INTERVAL_MS;
        boolean cameraMoveEnough = lastCameraLocation == null
                || calculateDistance(lastCameraLocation, newLocation) >= MIN_CAMERA_MOVE_DISTANCE_M;
        if (cameraTimeOk && cameraMoveEnough) {
            googleMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
            lastCameraUpdateTime = now;
            lastCameraLocation = newLocation;
        }
    }

    private void addRawObservationCircle(LatLng position, FusionManager.PositionSource source) {
        if (googleMap == null) return;

        int fillColor;
        List<Circle> targetList;

        if (source == FusionManager.PositionSource.GNSS) {
            fillColor = Color.argb(120, 0, 200, 0);
            targetList = gnssObservationCircles;
        } else if (source == FusionManager.PositionSource.WIFI) {
            fillColor = Color.argb(120, 255, 140, 0);
            targetList = wifiObservationCircles;
        } else {
            fillColor = Color.argb(120, 40, 100, 255);
            targetList = pdrObservationCircles;
        }

        Circle circle = googleMap.addCircle(new CircleOptions()
            .center(position)
                .radius(RAW_OBSERVATION_RADIUS_M)
                .fillColor(fillColor)
                .strokeWidth(0f)
                .zIndex(120));

        if (circle != null) {
            targetList.add(circle);
            while (targetList.size() > MAX_RAW_OBSERVATIONS) {
                Circle oldCircle = targetList.remove(0);
                if (oldCircle != null) {
                    oldCircle.remove();
                }
            }
        }
    }

    private void updateRawSensorObservationOverlays(long nowMs) {
        LatLng pdrEstimate = sensorFusion.getRawPdrLatLng();
        if (pdrEstimate != null) {
            maybeAddRawObservation(
                    pdrEstimate,
                    FusionManager.PositionSource.PDR,
                    nowMs,
                    lastPdrObservation,
                    lastPdrObservationTime
            );
        }

        float[] gnss = sensorFusion.getGNSSLatitude(false);
        if (gnss != null && gnss.length >= 2 && gnss[0] != 0f && gnss[1] != 0f) {
            LatLng gnssLatLng = new LatLng(gnss[0], gnss[1]);
            maybeAddRawObservation(
                    gnssLatLng,
                    FusionManager.PositionSource.GNSS,
                    nowMs,
                    lastGnssObservation,
                    lastGnssObservationTime
            );
        }

        LatLng wifiLatLng = sensorFusion.getLatLngWifiPositioning();
        if (wifiLatLng != null) {
            maybeAddRawObservation(
                    wifiLatLng,
                    FusionManager.PositionSource.WIFI,
                    nowMs,
                    lastWifiObservation,
                    lastWifiObservationTime
            );
        }
    }

    private void maybeAddRawObservation(
            LatLng current,
            FusionManager.PositionSource source,
            long nowMs,
            LatLng previous,
            long previousTimeMs) {

        if (current == null) {
            return;
        }

        boolean timeOk = (nowMs - previousTimeMs) >= RAW_OBSERVATION_MIN_INTERVAL_MS;
        boolean distOk = previous == null
                || calculateDistance(previous, current) >= RAW_OBSERVATION_MIN_DISTANCE_M;

        if (!timeOk || !distOk) {
            return;
        }

        addRawObservationCircle(current, source);

        if (source == FusionManager.PositionSource.GNSS) {
            lastGnssObservation = current;
            lastGnssObservationTime = nowMs;
        } else if (source == FusionManager.PositionSource.WIFI) {
            lastWifiObservation = current;
            lastWifiObservationTime = nowMs;
        } else {
            lastPdrObservation = current;
            lastPdrObservationTime = nowMs;
        }
    }

    /**
     * Calculate distance between two LatLng points in meters.
     */
    private float calculateDistance(LatLng p1, LatLng p2) {
        double lat1 = Math.toRadians(p1.latitude);
        double lat2 = Math.toRadians(p2.latitude);
        double dLat = Math.toRadians(p2.latitude - p1.latitude);
        double dLng = Math.toRadians(p2.longitude - p1.longitude);
        
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                   Math.cos(lat1) * Math.cos(lat2) *
                   Math.sin(dLng/2) * Math.sin(dLng/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        
        return (float) (6371000 * c); // Earth radius in meters
    }

    private void blinkingRecordingIcon() {
        Animation blinking = new AlphaAnimation(1, 0);
        blinking.setDuration(800);
        blinking.setInterpolator(new LinearInterpolator());
        blinking.setRepeatCount(Animation.INFINITE);
        blinking.setRepeatMode(Animation.REVERSE);
        recIcon.startAnimation(blinking);
    }

    @Override
    public void onPause() {
        super.onPause();
        refreshDataHandler.removeCallbacks(refreshDataTask);
        if (wiFiFingerprintTask != null) {
            refreshDataHandler.removeCallbacks(wiFiFingerprintTask);
        }
        if (blEDataTask != null) {
            refreshDataHandler.removeCallbacks(blEDataTask);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!this.settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, UI_REFRESH_INTERVAL_MS);
        }
    }

}
