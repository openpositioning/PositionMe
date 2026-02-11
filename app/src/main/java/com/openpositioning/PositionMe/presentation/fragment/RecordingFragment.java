package com.openpositioning.PositionMe.presentation.fragment;

import android.Manifest;
import android.app.AlertDialog;
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
import android.os.CountDownTimer;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
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
import com.google.android.gms.maps.model.LatLng;
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
import com.openpositioning.PositionMe.utils.UtilFunctions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // ========== Building Location Data ==========
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

    // Target buildings (same as MapsFragment)
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

    // ========== UI Elements ==========
    private MaterialButton completeButton, cancelButton;
    private ImageView recIcon;
    private ProgressBar timeRemaining;
    private TextView elevation, distanceTravelled, gnssError;

    // 🆕 Venue selection UI
    private TextView venueInfoText;
    private MaterialButton changeVenueButton;
    private View floorSelectorContainer;
    private LinearLayout floorButtonLayout;
    private Button backToRecordingButton;

    // 🆕 NEW SENSOR DATA UI ELEMENTS
    private TextView trajectoryIdText;
    private TextView wifiFingerprintsCount;
    private TextView correctedPositionsCount;
    private TextView initialPositionStatus;
    private ImageView initialPositionIndicator;
    private MaterialButton setInitialPositionButton;

    // 🆕 NEW TEST POINTS UI ELEMENTS
    private MaterialButton markTestPointButton;
    private TextView testPointsCount;
    private final List<Marker> testPointMarkers = new ArrayList<>();

    // ========== Map & Location ==========
    private GoogleMap googleMap;
    private FusedLocationProviderClient fusedLocationClient;
    private LatLng currentLocation;
    private Marker userMarker;
    private Polyline trajectoryPolyline;
    private Marker gnssMarker;

    // 🆕 Indoor map support
    private final Map<String, NetworkUtils.BuildingData> allBuildingsData = new HashMap<>();
    private final Map<Polygon, String> buildingPolygonMap = new HashMap<>();
    private String currentSelectedBuilding = null;
    private String currentSelectedFloor = null;
    private final List<Polyline> currentWallLines = new ArrayList<>();
    private final List<Polygon> currentAreaPolygons = new ArrayList<>();
    private final List<Marker> currentPoiMarkers = new ArrayList<>();

    // ========== Recording Logic ==========
    private SharedPreferences settings;
    private SensorFusion sensorFusion;
    private Handler refreshDataHandler;
    private CountDownTimer autoStop;
    private float distance = 0f;
    private float previousPosX = 0f;
    private float previousPosY = 0f;

    // 🆕 Venue tracking
    private boolean hasVenue = false;
    private String venueId = "";
    private String venueName = "";
    private String venueFloor = "";

    // ========== UI Update Interval (ms) ==========
    private static final int UI_REFRESH_INTERVAL_MS = 100; // 10 FPS for smooth movement

    // ========== Runnable for UI Updates ==========
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

            Log.d(TAG, "========================================");
            if (hasVenue) {
                Log.d(TAG, "🏢 Recording in venue:");
                Log.d(TAG, "   Name: " + venueName);
                Log.d(TAG, "   Floor: " + venueFloor);
            } else {
                Log.d(TAG, "🌍 Outdoor recording");
            }
            Log.d(TAG, "========================================");
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

        // 🆕 Venue selection UI
        venueInfoText = view.findViewById(R.id.venueInfoText);
        changeVenueButton = view.findViewById(R.id.changeVenueButton);
        floorSelectorContainer = view.findViewById(R.id.floorSelectorContainer);
        floorButtonLayout = view.findViewById(R.id.floorButtonLayout);
        backToRecordingButton = view.findViewById(R.id.backToRecordingButton);

        // 🆕 NEW SENSOR DATA UI ELEMENTS
        trajectoryIdText = view.findViewById(R.id.trajectoryIdText);
        wifiFingerprintsCount = view.findViewById(R.id.wifiFingerprintsCount);
        correctedPositionsCount = view.findViewById(R.id.correctedPositionsCount);
        initialPositionStatus = view.findViewById(R.id.initialPositionStatus);
        initialPositionIndicator = view.findViewById(R.id.initialPositionIndicator);
        setInitialPositionButton = view.findViewById(R.id.setInitialPositionButton);

        // 🆕 NEW TEST POINTS UI ELEMENTS
        markTestPointButton = view.findViewById(R.id.markTestPointButton);
        testPointsCount = view.findViewById(R.id.testPointsCount);

        // Initialize map
        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.recordingMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        // Setup UI
        setupRecordingControls();
        setupVenueDisplay();

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

        // 🆕 Start automated WiFi fingerprint collection
        startWiFiFingerprintCollection();
        
        // 🆕 Start automated BLE data collection
        startBLEDataCollection();
        
        // 🆕 Reset trajectory tracking variables for new recording
        lastTrajectoryPoint = null;
        secondLastTrajectoryPoint = null;
        positionInitialized = false;
        smoothedLat = 0.0;
        smoothedLng = 0.0;
    }

    // ========== WiFi FINGERPRINT COLLECTION ==========
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
                        
                        // 🆕 Log detailed WiFi AP data with RTT flag (simulated for now)
                        // In production, would check actual device RTT capability
                        boolean rttEnabled = false; // Default: assume no RTT support
                        // Check if this AP supports RTT (would need actual RTT scanning)
                        
                        // Add AP data with RTT flag
                        sensorFusion.addWiFiAPData(bssid, wifi.getSsid(), wifi.getFrequency(), rttEnabled);
                        
                        // 📡 LOGCAT OUTPUT: WiFi data with RTT flag
                        Log.d(TAG, String.format(
                            "📡 WiFiData | SSID: %-20s | MAC: %016X | RSSI: %3d dBm | RTT: %s | Freq: %d MHz",
                            wifi.getSsid() != null ? wifi.getSsid() : "HIDDEN",
                            bssid,
                            rssi,
                            rttEnabled ? "✓ ENABLED" : "✗ DISABLED",
                            wifi.getFrequency()
                        ));
                    }
                    
                    // Update UI
                    int count = sensorFusion.getWiFiFingerprintCount();
                    wifiFingerprintsCount.setText(String.valueOf(count));
                    Log.d(TAG, "📡 Total WiFi Fingerprints: " + count);
                }
                
                // Schedule next collection
                refreshDataHandler.postDelayed(this, WIFI_FINGERPRINT_INTERVAL);
            }
        };
        
        refreshDataHandler.postDelayed(wiFiFingerprintTask, WIFI_FINGERPRINT_INTERVAL);
    }

    // ========== BLE DATA COLLECTION ==========
    private Runnable blEDataTask;
    private static final long BLE_DATA_INTERVAL = 5000; // 5 seconds

    private void startBLEDataCollection() {
        blEDataTask = new Runnable() {
            @Override
            public void run() {
                // 🆕 Simulate BLE device detection with various service UUIDs
                // In production, this would use Android BluetoothManager to scan for actual BLE devices
                
                // Simulated BLE devices with Service UUIDs
                addMockBLEDevice("AA:BB:CC:DD:EE:01", "HeartRate_Sensor", -5, 0x06, 
                    Arrays.asList("0000180D-0000-1000-8000-00805F9B34FB", "0000180A-0000-1000-8000-00805F9B34FB"));
                
                addMockBLEDevice("AA:BB:CC:DD:EE:02", "BLE_Beacon", -10, 0x02, 
                    Arrays.asList("0000FEAA-0000-1000-8000-00805F9B34FB"));
                
                addMockBLEDevice("AA:BB:CC:DD:EE:03", "Fitness_Tracker", -15, 0x06, 
                    Arrays.asList("0000183B-0000-1000-8000-00805F9B34FB", "0000181F-0000-1000-8000-00805F9B34FB"));
                
                Log.d(TAG, "✅ BLE Data Collection Complete");
                
                // Schedule next collection
                refreshDataHandler.postDelayed(this, BLE_DATA_INTERVAL);
            }
        };
        
        refreshDataHandler.postDelayed(blEDataTask, BLE_DATA_INTERVAL);
    }

    /**
     * Helper method to add mock BLE device data (for demonstration/testing)
     * In production, this would receive actual data from BluetoothManager scanning
     */
    private void addMockBLEDevice(String macAddress, String name, int txPower, int flags, List<String> serviceUuids) {
        try {
            sensorFusion.addBLEData(macAddress, name, txPower, flags, serviceUuids);
            Log.d(TAG, String.format("➕ Mock BLE Device Added: %s (%s)", name, macAddress));
        } catch (Exception e) {
            Log.e(TAG, "Error adding BLE data: " + e.getMessage());
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

        // Initialize trajectory polyline
        trajectoryPolyline = googleMap.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(8f)
                .geodesic(true));

        // Move camera to campus center
        LatLng campusCenter = new LatLng(55.9234, -3.1761);
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(campusCenter, 17f));

        // Draw building outlines
        drawBuildingOutlines();

        // Load building data from API
        loadAllBuildings();
    }

    // ========== BUILDING OUTLINES ==========
    private void drawBuildingOutlines() {
        if (googleMap == null) return;

        Log.d(TAG, "📍 Drawing building outlines...");

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

    // ========== BUILDING CLICK LISTENER ==========
    private void setupBuildingClickListener() {
        if (googleMap == null) return;

        // Setup map click listener for marking corrected positions
        googleMap.setOnMapClickListener(latLng -> {
            // Add corrected position marker
            addCorrectedPositionMarker(latLng);
        });
        
        // 🆕 Long press to set position anchor (correct drift)
        googleMap.setOnMapLongClickListener(latLng -> {
            setPositionAnchor(latLng);
        });

        googleMap.setOnPolygonClickListener(polygon -> {
            String buildingName = buildingPolygonMap.get(polygon);
            if (buildingName != null && allBuildingsData.containsKey(buildingName)) {
                showVenueSelectionDialog(buildingName);
            } else {
                Toast.makeText(getContext(), "Loading " + buildingName + "...", Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    // 🆕 ========== POSITION ANCHOR (DRIFT CORRECTION) ==========
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
        
        Log.d(TAG, "📌 Position anchor set at: " + latLng.latitude + ", " + latLng.longitude);
        Toast.makeText(getContext(), "📌 Position corrected! Drift will now reduce.", Toast.LENGTH_SHORT).show();
    }

    // ========== CORRECTED POSITION MARKING ==========
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

        Log.d(TAG, "📍 Position #" + count + " marked at: " + latLng.latitude + ", " + latLng.longitude);
        Toast.makeText(getContext(), "Position marked (#" + count + ")", Toast.LENGTH_SHORT).show();
    }

    // 🆕 ========== TEST POINT MARKING ==========
    private static final float TEST_POINT_MARKER_COLOR = BitmapDescriptorFactory.HUE_VIOLET;

    private void addTestPointMarker(LatLng latLng, int pointNumber) {
        if (googleMap == null) return;

        // 🆕 Create numbered marker bitmap
        BitmapDescriptor markerIcon = createNumberedMarker(pointNumber);

        // Add marker to map with numbered bitmap
        Marker marker = googleMap.addMarker(new MarkerOptions()
                .position(latLng)
                .icon(markerIcon)
                .title("Test Point #" + pointNumber)
                .snippet("Marked at: " + String.format("%.4f, %.4f", latLng.latitude, latLng.longitude))
                .zIndex(95));

        testPointMarkers.add(marker);

        Log.d(TAG, "🚩 Test Point #" + pointNumber + " marker added at: " + latLng.latitude + ", " + latLng.longitude);
    }

    /**
     * 🆕 Create a custom numbered marker bitmap
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

    // ========== VENUE SELECTION DIALOG ==========
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
            Log.d(TAG, "✅ Venue selected: " + buildingName + " - " + firstFloor);
        }
    }

    // ========== FLOOR DISPLAY ==========
    private void drawFloor(String floorName, NetworkUtils.BuildingData data) {
        if (googleMap == null) return;

        // Clear previous floor
        clearCurrentFloor();

        currentSelectedFloor = floorName;
        NetworkUtils.FloorData floorData = data.floors.get(floorName);
        if (floorData == null) return;

        Log.d(TAG, "🏢 Drawing floor: " + floorName);

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
                        .fillColor(Color.argb(100, 200, 200, 200))
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

        Log.d(TAG, "✅ Floor drawn: " + currentWallLines.size() + " walls, "
                + currentAreaPolygons.size() + " areas");
    }

    private void clearCurrentFloor() {
        for (Polyline line : currentWallLines) line.remove();
        for (Polygon poly : currentAreaPolygons) poly.remove();
        for (Marker marker : currentPoiMarkers) marker.remove();
        currentWallLines.clear();
        currentAreaPolygons.clear();
        currentPoiMarkers.clear();
    }

    // ========== FLOOR SELECTOR ==========
    private void setupFloorSelector(Map<String, NetworkUtils.FloorData> floors, String buildingName) {
        if (floorButtonLayout == null || getContext() == null) return;

        floorSelectorContainer.setVisibility(View.VISIBLE);
        backToRecordingButton.setVisibility(View.VISIBLE);
        floorButtonLayout.removeAllViews();

        List<String> sortedFloors = sortFloorNames(new ArrayList<>(floors.keySet()));

        for (String floorName : sortedFloors) {
            MaterialButton btn = new MaterialButton(getContext());
            btn.setText(floorName);
            btn.setTextSize(13);  // Font size

            // Set button size and padding
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    110,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(2, 2, 2, 2);
            btn.setLayoutParams(params);
            
            // Add padding inside the button to ensure the text displays correctly.
            btn.setPadding(8, 4, 8, 4);

            btn.setCornerRadius(6);
            btn.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(getContext(), R.color.md_theme_secondary)
                    )
            );

            btn.setOnClickListener(v -> {
                NetworkUtils.BuildingData data = allBuildingsData.get(currentSelectedBuilding);
                if (data != null) {
                    drawFloor(floorName, data);
                    venueFloor = floorName;

                    // Update venue manager
                    VenueManager.getInstance(requireContext())
                            .setSelectedVenue(venueName, venueId, floorName);

                    updateVenueDisplay();
                    Toast.makeText(getContext(), "Floor: " + floorName, Toast.LENGTH_SHORT).show();
                }
            });

            floorButtonLayout.addView(btn);
        }

        // Setup back button
        backToRecordingButton.setOnClickListener(v -> returnToRecording());
    }

    private void returnToRecording() {
        floorSelectorContainer.setVisibility(View.GONE);
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
        String clean = floorName.toLowerCase().replaceAll("[^0-9-]", "");
        if (clean.isEmpty()) return 0;
        return Integer.parseInt(clean);
    }

    // ========== LOAD BUILDINGS FROM API ==========
    private void loadAllBuildings() {
        for (BuildingLocation building : TARGET_BUILDINGS) {
            NetworkUtils.fetchFloorPlan(
                    building.center.latitude,
                    building.center.longitude,
                    new NetworkUtils.Callback() {
                        @Override
                        public void onSuccess(NetworkUtils.BuildingData buildingData) {
                            if (isAdded() && getContext() != null) {
                                allBuildingsData.put(building.name, buildingData);
                                Log.d(TAG, "✅ " + building.name + " loaded: "
                                        + buildingData.floors.size() + " floors");
                            }
                        }

                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "❌ Failed to load " + building.name + ": " + error);
                        }
                    }
            );
        }
    }

    // ========== VENUE DISPLAY UI ==========
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

    // ========== RECORDING CONTROLS ==========
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

            Log.d(TAG, "✅ Recording completed with venue: " + venueName);
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

        // 🆕 Set Initial Position button
        setInitialPositionButton.setOnClickListener(v -> {
            try {
                Log.d(TAG, "🟡 Set Position button clicked");
                Log.d(TAG, "Debug: initialPositionStatus = " + (initialPositionStatus != null ? "OK" : "NULL"));
                Log.d(TAG, "Debug: sensorFusion = " + (sensorFusion != null ? "OK" : "NULL"));
                
                float[] gnssPos = sensorFusion.getGNSSLatitude(true);
                Log.d(TAG, "Debug: gnssPos = " + (gnssPos != null ? "Array[" + gnssPos.length + "]" : "null"));
                
                if (gnssPos != null && gnssPos.length >= 2) {
                    // Set initial position with current GNSS location
                    Log.d(TAG, "Using GPS position");
                    sensorFusion.setInitialPosition(gnssPos[0], gnssPos[1], 0);
                    Log.d(TAG, "After setInitialPosition, isSet = " + sensorFusion.isInitialPositionSet());
                    
                    // Update UI to show position is set
                    initialPositionStatus.setText("Set ✓");
                    initialPositionStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_dark));
                    initialPositionIndicator.setVisibility(View.VISIBLE);
                    Log.d(TAG, "UI updated to green");
                    
                    Toast.makeText(requireContext(), "✅ Position: " + String.format("%.4f, %.4f", gnssPos[0], gnssPos[1]), Toast.LENGTH_LONG).show();
                    Log.d(TAG, String.format("✅ Initial position set | Lat: %.6f | Lon: %.6f | Accuracy: ~5m", gnssPos[0], gnssPos[1]));
                } else {
                    // Fallback: Use current user location or map center
                    Log.d(TAG, "GPS unavailable, using fallback");
                    LatLng fallbackLocation = currentLocation != null ? currentLocation : 
                        new LatLng(55.9234, -3.1761); // Default to campus center
                    
                    Log.d(TAG, "Fallback location: " + fallbackLocation.latitude + ", " + fallbackLocation.longitude);
                    sensorFusion.setInitialPosition((float) fallbackLocation.latitude, (float) fallbackLocation.longitude, 0);
                    Log.d(TAG, "After setInitialPosition, isSet = " + sensorFusion.isInitialPositionSet());
                    
                    // Update UI
                    initialPositionStatus.setText("Set ✓ (approx)");
                    initialPositionStatus.setTextColor(requireContext().getColor(android.R.color.holo_orange_dark));
                    initialPositionIndicator.setVisibility(View.VISIBLE);
                    Log.d(TAG, "UI updated to orange");
                    
                    Toast.makeText(requireContext(), "⚠️ Using approximate position (GPS unavailable)", Toast.LENGTH_LONG).show();
                    Log.w(TAG, String.format("⚠️ GPS unavailable, using fallback | Lat: %.6f | Lon: %.6f", fallbackLocation.latitude, fallbackLocation.longitude));
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Error in Set Position button: " + e.getMessage(), e);
                Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        // 🆕 Mark Test Point button
        markTestPointButton.setOnClickListener(v -> {
            try {
                Log.d(TAG, "🚩 Mark Test Point button clicked");
                
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
                
                Toast.makeText(requireContext(), "🚩 Test Point #" + pointNumber + " marked", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "✅ Test point #" + pointNumber + " marked at: " + testPointLocation.latitude + ", " + testPointLocation.longitude);
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Error marking test point: " + e.getMessage(), e);
                Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        // Initialize UI defaults
        if (gnssError != null) gnssError.setVisibility(View.GONE);
        if (elevation != null) elevation.setText(getString(R.string.elevation, "0"));
        if (distanceTravelled != null) distanceTravelled.setText(getString(R.string.meter, "0"));

        // 🆕 Initialize new sensor data UI
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
        if (testPointsCount != null) testPointsCount.setText("0");  // 🆕 Initialize test points counter
    }

    // ========== UI UPDATE ==========
    private void updateTrajectoryIdDisplay() {
        if (trajectoryIdText == null) return;
        String trajectoryId = sensorFusion.getTrajectoryId();
        if (trajectoryId != null && !trajectoryId.isEmpty()) {
            trajectoryIdText.setText(trajectoryId);
        } else {
            trajectoryIdText.setText("--");
        }
    }

    // 🆕 Minimum distance (in meters) to add a new trajectory point
    private static final float MIN_TRAJECTORY_POINT_DISTANCE = 1.0f;  // Increased to reduce noise
    private LatLng lastTrajectoryPoint = null;
    private LatLng secondLastTrajectoryPoint = null;  // 🆕 For direction checking
    private long lastCameraUpdateTime = 0;
    private static final long CAMERA_UPDATE_INTERVAL_MS = 800; // Update camera less frequently
    
    // 🆕 Anti-jitter: smooth position output
    private double smoothedLat = 0.0;
    private double smoothedLng = 0.0;
    private boolean positionInitialized = false;
    private static final float POSITION_SMOOTHING = 0.3f;

    private void updateUIandPosition() {
        // 🆕 Use smoothed PDR position for smoother tracking
        float[] pdrValues = sensorFusion.getSmoothedPDRPosition();
        if (pdrValues == null) return;

        // 🆕 Update sensor data counts (with null checks for UI elements)
        updateTrajectoryIdDisplay();
        
        // WiFi Fingerprints count
        if (wifiFingerprintsCount != null) {
            int wifiCount = sensorFusion.getWiFiFingerprintCount();
            wifiFingerprintsCount.setText(String.valueOf(wifiCount));
        }
        
        // Corrected Positions count
        if (correctedPositionsCount != null) {
            int positionCount = sensorFusion.getCorrectedPositionCount();
            correctedPositionsCount.setText(String.valueOf(positionCount));
        }
        
        // 🆕 Test Points count
        if (testPointsCount != null) {
            int testPointCount = sensorFusion.getTestPointCount();
            testPointsCount.setText(String.valueOf(testPointCount));
        }
        
        // Initial Position status
        if (initialPositionStatus != null) {
            if (sensorFusion.isInitialPositionSet()) {
                initialPositionStatus.setText("Set ✓");
                initialPositionStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_dark));
                if (initialPositionIndicator != null) {
                    initialPositionIndicator.setVisibility(View.VISIBLE);
                }
            } else {
                initialPositionStatus.setText("Not set");
                initialPositionStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_dark));
                if (initialPositionIndicator != null) {
                    initialPositionIndicator.setVisibility(View.GONE);
                }
            }
        }

        // 🆕 Calculate distance delta for display (only when there's significant movement)
        float deltaX = pdrValues[0] - previousPosX;
        float deltaY = pdrValues[1] - previousPosY;
        float movementDelta = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        
        // Only update distance if there's meaningful movement (> 1cm)
        if (movementDelta > 0.01f && distanceTravelled != null) {
            distance += movementDelta;
            distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));
        }

        // Elevation
        if (elevation != null) {
            float elevationVal = sensorFusion.getElevation();
            elevation.setText(getString(R.string.elevation, String.format("%.1f", elevationVal)));
        }

        // 🆕 Current location with smooth tracking
        float[] latLngArray = sensorFusion.getGNSSLatitude(true);
        if (latLngArray != null && googleMap != null && trajectoryPolyline != null && latLngArray[0] != 0) {
            // 🆕 Use fused position if available, otherwise calculate from PDR delta
            LatLng rawLocation = sensorFusion.getFusedPosition();
            
            if (rawLocation == null) {
                // Fallback to delta-based calculation
                LatLng baseLocation = currentLocation != null ? currentLocation : new LatLng(latLngArray[0], latLngArray[1]);
                rawLocation = UtilFunctions.calculateNewPos(baseLocation, new float[]{deltaX, deltaY});
            }
            
            // 🆕 Apply additional smoothing to prevent jitter
            LatLng newLocation;
            if (!positionInitialized) {
                smoothedLat = rawLocation.latitude;
                smoothedLng = rawLocation.longitude;
                positionInitialized = true;
                newLocation = rawLocation;
            } else {
                smoothedLat = smoothedLat + POSITION_SMOOTHING * (rawLocation.latitude - smoothedLat);
                smoothedLng = smoothedLng + POSITION_SMOOTHING * (rawLocation.longitude - smoothedLng);
                newLocation = new LatLng(smoothedLat, smoothedLng);
            }

            currentLocation = newLocation;

            // 🆕 Update trajectory polyline only when moved significant distance AND forward
            boolean shouldAddPoint = false;
            if (lastTrajectoryPoint == null) {
                shouldAddPoint = true;
            } else {
                float distToLast = calculateDistance(lastTrajectoryPoint, newLocation);
                
                // Only add if moved enough distance
                if (distToLast > MIN_TRAJECTORY_POINT_DISTANCE) {
                    // 🆕 Anti-jitter: check if movement is consistent (not bouncing back)
                    if (secondLastTrajectoryPoint != null) {
                        float distToSecondLast = calculateDistance(secondLastTrajectoryPoint, newLocation);
                        float lastSegmentDist = calculateDistance(secondLastTrajectoryPoint, lastTrajectoryPoint);
                        
                        // Only add if we're moving forward (not bouncing back)
                        // New point should be further from secondLast than lastPoint was
                        shouldAddPoint = distToSecondLast >= lastSegmentDist * 0.5f;
                    } else {
                        shouldAddPoint = true;
                    }
                }
            }
            
            if (shouldAddPoint) {
                List<LatLng> points = trajectoryPolyline.getPoints();
                points.add(newLocation);
                trajectoryPolyline.setPoints(points);
                secondLastTrajectoryPoint = lastTrajectoryPoint;
                lastTrajectoryPoint = newLocation;
            }

            // Update user marker (always update position for smooth tracking)
            if (userMarker == null) {
                userMarker = googleMap.addMarker(new MarkerOptions()
                        .position(newLocation)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_CYAN))
                        .flat(true)
                        .anchor(0.5f, 0.5f)
                        .zIndex(999));
            } else {
                userMarker.setPosition(newLocation);
                userMarker.setRotation((float) Math.toDegrees(sensorFusion.passOrientation()));
            }

            // 🆕 Update camera less frequently to reduce jitter
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastCameraUpdateTime > CAMERA_UPDATE_INTERVAL_MS) {
                googleMap.animateCamera(CameraUpdateFactory.newLatLng(newLocation));
                lastCameraUpdateTime = currentTime;
            }
        }

        // Update previous
        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];
    }

    /**
     * 🆕 Calculate distance between two LatLng points in meters.
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
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
    }

    // ========== PUBLIC GETTERS ==========
    public boolean hasVenue() {
        return hasVenue;
    }

    public String getVenueName() {
        return venueName;
    }

    public String getVenueFloor() {
        return venueFloor;
    }

    public Bundle getVenueInfo() {
        Bundle info = new Bundle();
        info.putBoolean("has_venue", hasVenue);
        info.putString("venue_id", venueId);
        info.putString("venue_name", venueName);
        info.putString("venue_floor", venueFloor);
        return info;
    }
}