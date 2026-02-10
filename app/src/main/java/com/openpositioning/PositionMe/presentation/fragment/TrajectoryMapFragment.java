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
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * TrajectoryMapFragment
 * Adapted for hybrid indoor map manager.
 * Fixed: Auto Floor Offset Logic.
 */
public class TrajectoryMapFragment extends Fragment {

    public interface OnVenueSelectedListener {
        void onVenueSelected(String buildingId, String venueName);
    }

    private GoogleMap gMap;
    private LatLng currentLocation;
    private Marker orientationMarker;
    private Marker gnssMarker;
    private Polyline polyline;
    private boolean isRed = true;
    private boolean isGnssOn = false;

    private Polyline gnssPolyline;
    private LatLng lastGnssLocation = null;

    private LatLng pendingCameraPosition = null;
    private boolean hasPendingCameraMove = false;

    private IndoorMapManager indoorMapManager;
    private SensorFusion sensorFusion;
    private OnVenueSelectedListener venueSelectedListener;

    private List<Marker> manualMarkers = new ArrayList<>();

    // Track if arrow is inside a building for auto-enable indoor map feature
    private boolean isArrowInsideBuilding = false;

    // [FIX START]: New variable to store Auto Floor calibration offset
    private int autoFloorOffset = 0;
    // [FIX END]

    // UI Controls
    private Spinner switchMapSpinner;
    private SwitchMaterial gnssSwitch;
    private SwitchMaterial indoorMapSwitch;
    private SwitchMaterial autoFloorSwitch;
    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;
    private TextView floorTextView;
    private View floorControlsContainer;
    private View buildingInfoCard;
    private TextView buildingNameText;

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
        gnssSwitch      = view.findViewById(R.id.gnssSwitch);
        indoorMapSwitch = view.findViewById(R.id.indoorMapSwitch);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);
        floorTextView   = view.findViewById(R.id.floorTextView);
        floorControlsContainer = view.findViewById(R.id.floorControlsContainer);
        buildingInfoCard = view.findViewById(R.id.buildingInfoCard);
        buildingNameText = view.findViewById(R.id.buildingNameText);

        setFloorControlsVisibility(View.GONE);

        // Initialize IndoorMapManager (will be set properly in onMapReady)
        indoorMapManager = new IndoorMapManager(null, getContext());
        indoorMapManager.setOnFloorDataLoadedListener((hasData) -> {
            // Update floor display when floor data is loaded from API
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (hasData) {
                        setFloorControlsVisibility(View.VISIBLE);
                        updateFloorDisplay();

                        // Now send venue selection notification
                        String buildingId = indoorMapManager.getSelectedBuildingId();
                        String venueName = indoorMapManager.getSelectedBuildingName();
                        if (venueSelectedListener != null && buildingId != null && venueName != null) {
                            venueSelectedListener.onVenueSelected(buildingId, venueName);
                        }
                    } else {
                        // No floor data for this building - hide floor controls
                        setFloorControlsVisibility(View.GONE);
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

                    // ==========================================
                    // Hybrid strategy: local fallback + API load
                    // ==========================================

                    // 1. Local fallback: immediately load Murchison/Nucleus/Library
                    if (indoorMapManager != null) {
                        indoorMapManager.addFallbackBuildings();
                    }

                    // 2. Camera movement
                    LatLng kbCampus = new LatLng(55.9230, -3.1750);
                    if (!hasPendingCameraMove) {
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(kbCampus, 17.5f));
                    }

                    // 4. Click interaction
                    gMap.setOnPolygonClickListener(new GoogleMap.OnPolygonClickListener() {
                        @Override
                        public void onPolygonClick(@NonNull Polygon polygon) {
                            if (indoorMapManager != null) {
                                boolean handled = indoorMapManager.onPolygonClick(polygon);
                                if (handled) {
                                    // Don't show floor controls yet - wait for API callback
                                    // Just show building name immediately
                                    String venueName = indoorMapManager.getSelectedBuildingName();
                                    if (buildingInfoCard != null && buildingNameText != null && venueName != null) {
                                        buildingNameText.setText(venueName);
                                        buildingInfoCard.setVisibility(View.VISIBLE);
                                    }

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
                                    .title("Start Position")
                                    .icon(BitmapDescriptorFactory.fromBitmap(
                                            UtilFunctions.getBitmapFromVector(requireContext(),
                                                    R.drawable.ic_baseline_navigation_24))));
                        }
                        currentLocation = pendingCameraPosition;
                        hasPendingCameraMove = false;
                        pendingCameraPosition = null;
                    }

                    Log.d("TrajectoryMapFragment", "Map Ready: Hybrid Mode.");
                }
            });
        }

        initMapTypeSpinner();

        gnssSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isGnssOn = isChecked;
            if (!isChecked && gnssMarker != null) {
                gnssMarker.remove();
                gnssMarker = null;
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

        indoorMapSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (indoorMapManager != null) {
                indoorMapManager.setIndoorMapVisible(isChecked);
                // Show/hide floor controls based on visibility and whether we have floor data
                if (isChecked && indoorMapManager.getAvailableFloorsCount() > 0) {
                    setFloorControlsVisibility(View.VISIBLE);
                } else {
                    setFloorControlsVisibility(View.GONE);
                }
            }
        });

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

        // [FIX START]: Fix Auto Floor Logic
        // When Auto Floor is enabled, record the difference (Offset) between current floor and sensor floor
        autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (isChecked && indoorMapManager != null && sensorFusion != null) {
                float elevationVal = sensorFusion.getElevation();
                float floorHeight = indoorMapManager.getFloorHeight();

                if (floorHeight > 0) {
                    // 1. Get current manually set floor (Real Floor)
                    int currentManualFloor = indoorMapManager.getCurrentFloor();

                    // 2. Calculate sensor floor (usually starts from 0)
                    int sensorCalculatedFloor = (int) Math.round(elevationVal / floorHeight);

                    // 3. Calculate calibration offset: Real Floor - Sensor Floor
                    // Example: User is at 2nd floor, sensor shows 0. Offset = 2 - 0 = 2.
                    // Later updates: Sensor 1st floor + Offset 2 = 3rd floor.
                    autoFloorOffset = currentManualFloor - sensorCalculatedFloor;

                    Log.d("AutoFloor", "Enabled! Calibration Offset: " + autoFloorOffset);
                    Toast.makeText(getContext(), "Auto Floor Calibrated", Toast.LENGTH_SHORT).show();
                }
            } else {
                // Reset offset when disabled (optional)
                autoFloorOffset = 0;
            }
        });
        // [FIX END]

        floorUpButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
                updateFloorDisplay();
            }
        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
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
                        updateFloorDisplay();

                        // Now send venue selection notification
                        String buildingId = indoorMapManager.getSelectedBuildingId();
                        String venueName = indoorMapManager.getSelectedBuildingName();
                        if (venueSelectedListener != null && buildingId != null && venueName != null) {
                            venueSelectedListener.onVenueSelected(buildingId, venueName);
                        }
                    } else {
                        // No floor data for this building - hide floor controls
                        setFloorControlsVisibility(View.GONE);
                    }
                });
            }
        });

        polyline = map.addPolyline(new PolylineOptions().color(Color.RED).width(5f));
        gnssPolyline = map.addPolyline(new PolylineOptions().color(Color.BLUE).width(5f));
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

        // Wall collision detection disabled for smoother trajectory

        this.currentLocation = newLocation;

        // Initialize polyline if not exists (important for replay)
        if (polyline == null) {
            polyline = gMap.addPolyline(new PolylineOptions().color(Color.RED).width(5f));
            Log.d("TrajectoryMapFragment", "Polyline initialized in updateUserLocation");
        }

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
            orientationMarker.setPosition(newLocation);
            // Convert orientation from radians to degrees for marker rotation
            float orientationDegrees = (float) Math.toDegrees(orientation);
            orientationMarker.setRotation(orientationDegrees);
            gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
        }

        // Add point to trajectory polyline
        if (oldLocation != null && !oldLocation.equals(newLocation)) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            points.add(newLocation);
            polyline.setPoints(points);
        } else if (oldLocation == null) {
            // First point - add it to start the trajectory
            List<LatLng> points = new ArrayList<>();
            points.add(newLocation);
            polyline.setPoints(points);
        }

        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(newLocation);

            // ===== Auto-enable Indoor Map when Arrow Enters Building =====
            // Check if the arrow is inside a building boundary
            boolean isCurrentlyInsideBuilding = checkIfInsideBuilding(newLocation);

            // If arrow just entered a building, auto-enable indoor map
            if (isCurrentlyInsideBuilding && !isArrowInsideBuilding) {
                Log.d("TrajectoryMapFragment", "Arrow entered building - auto-enabling indoor map");
                if (!indoorMapSwitch.isChecked()) {
                    indoorMapSwitch.setChecked(true);
                }
                isArrowInsideBuilding = true;
            }
            // If arrow just exited a building
            else if (!isCurrentlyInsideBuilding && isArrowInsideBuilding) {
                Log.d("TrajectoryMapFragment", "Arrow exited building");
                isArrowInsideBuilding = false;
            }

            if (autoFloorSwitch.isChecked() && indoorMapManager != null) {
                try {
                    float elevationVal = sensorFusion.getElevation();
                    float floorHeight = indoorMapManager.getFloorHeight();

                    Log.d("TrajectoryMapFragment", String.format(
                            "AutoFloor: elevation=%.2fm, floorHeight=%.2fm, NaN=%b, Infinite=%b",
                            elevationVal, floorHeight, Float.isNaN(elevationVal), Float.isInfinite(elevationVal)));

                    if (floorHeight > 0 && !Float.isNaN(elevationVal) && !Float.isInfinite(elevationVal)) {
                        // [FIX START]: Add Offset
                        // Calculate raw sensor floor
                        int sensorCalculatedFloor = Math.round(elevationVal / floorHeight);

                        // Apply Offset (Sensor Floor + Offset = Target Real Floor)
                        int targetFloor = sensorCalculatedFloor + autoFloorOffset;
                        // [FIX END]

                        // Get current floor to avoid unnecessary updates
                        int currentFloor = indoorMapManager.getCurrentFloor();

                        if (targetFloor != currentFloor) {
                            Log.d("TrajectoryMapFragment", String.format(
                                    "AutoFloor: Switching floor %d -> %d (elevation: %.2fm / floorHeight: %.2fm, offset: %d)",
                                    currentFloor, targetFloor, elevationVal, floorHeight, autoFloorOffset));
                            indoorMapManager.setCurrentFloor(targetFloor, true);
                        }
                    } else {
                        Log.w("TrajectoryMapFragment", "AutoFloor: Invalid data - " +
                                "floorHeight=" + floorHeight + ", elevation=" + elevationVal);
                    }
                } catch (Exception e) {
                    Log.e("TrajectoryMapFragment", "AutoFloor error: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Check if a location is inside any known building
     * Uses building boundary polygons from BuildingPolygon class and dynamic boundaries from IndoorMapManager
     */
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

    /**
     * Public method to check if current location is inside a building
     * Used by RecordingFragment for adaptive filtering
     */
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

    public LatLng getCurrentLocation() { return currentLocation; }
    public void setOnVenueSelectedListener(OnVenueSelectedListener listener) {
        this.venueSelectedListener = listener;
    }

    public void updateGNSS(@NonNull LatLng gnssLocation) {
        if (gMap == null || !isGnssOn) return;

        // Initialize GNSS polyline if not exists
        if (gnssPolyline == null) {
            gnssPolyline = gMap.addPolyline(new PolylineOptions().color(Color.BLUE).width(5f));
            Log.d("TrajectoryMapFragment", "GNSS Polyline initialized in updateGNSS");
        }

        if (gnssMarker == null) {
            gnssMarker = gMap.addMarker(new MarkerOptions()
                    .position(gnssLocation)
                    .title("GNSS Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
            lastGnssLocation = gnssLocation;
        } else {
            gnssMarker.setPosition(gnssLocation);
            if (lastGnssLocation != null && !lastGnssLocation.equals(gnssLocation)) {
                List<LatLng> gnssPoints = new ArrayList<>(gnssPolyline.getPoints());
                gnssPoints.add(gnssLocation);
                gnssPolyline.setPoints(gnssPoints);
            }
            lastGnssLocation = gnssLocation;
        }
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
            autoFloorSwitch.setVisibility(visibility);
        }
    }

    /**
     * Update floor display text based on current floor
     */
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

    public void clearMapAndReset() {
        if (polyline != null) { polyline.remove(); polyline = null; }
        if (gnssPolyline != null) { gnssPolyline.remove(); gnssPolyline = null; }
        if (orientationMarker != null) { orientationMarker.remove(); orientationMarker = null; }
        if (gnssMarker != null) { gnssMarker.remove(); gnssMarker = null; }

        for (Marker m : manualMarkers) m.remove();
        manualMarkers.clear();

        lastGnssLocation = null;
        currentLocation  = null;

        if (gMap != null) {
            polyline = gMap.addPolyline(new PolylineOptions().color(Color.RED).width(5f));
            gnssPolyline = gMap.addPolyline(new PolylineOptions().color(Color.BLUE).width(5f));
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