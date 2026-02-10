package com.openpositioning.PositionMe.presentation.fragment;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.IndoorMapManager;

import java.util.Locale;

/**
 * IndoorPositioningFragment - Real-time indoor positioning debug panel
 * Displays current GPS coordinates, altitude, building selection, and indoor maps
 */
public class IndoorPositioningFragment extends Fragment implements OnMapReadyCallback {

    private static final String TAG = "IndoorPositioning";

    // UI Elements
    private TextView latitudeText, longitudeText, altitudeText, accuracyText, floorText, currentFloorText;
    private MaterialButton nucleusButton, libraryButton, murchisonButton, fjbButton;
    private FloatingActionButton floorUpBtn, floorDownBtn;
    private View floorControlsLayout;

    // Map and location
    private GoogleMap googleMap;
    private IndoorMapManager indoorMapManager;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private Marker currentLocationMarker;
    
    // Sensor fusion for altitude
    private SensorFusion sensorFusion;

    // State
    private MaterialButton selectedBuildingButton = null;
    private Handler updateHandler = new Handler(Looper.getMainLooper());
    private Runnable updateRunnable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity != null && activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle("Indoor Positioning Debug");
        }
        return inflater.inflate(R.layout.fragment_indoor_positioning, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize UI elements
        latitudeText = view.findViewById(R.id.latitudeText);
        longitudeText = view.findViewById(R.id.longitudeText);
        altitudeText = view.findViewById(R.id.altitudeText);
        accuracyText = view.findViewById(R.id.accuracyText);
        floorText = view.findViewById(R.id.floorText);
        currentFloorText = view.findViewById(R.id.currentFloorText);
        floorControlsLayout = view.findViewById(R.id.floorControlsLayout);

        nucleusButton = view.findViewById(R.id.nucleusButton);
        libraryButton = view.findViewById(R.id.libraryButton);
        murchisonButton = view.findViewById(R.id.murchisonButton);
        fjbButton = view.findViewById(R.id.fjbButton);
        
        floorUpBtn = view.findViewById(R.id.floorUpBtn);
        floorDownBtn = view.findViewById(R.id.floorDownBtn);

        // Initialize services
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext());
        sensorFusion = SensorFusion.getInstance();

        // Set up building buttons
        nucleusButton.setOnClickListener(v -> selectBuilding("Nucleus", nucleusButton));
        libraryButton.setOnClickListener(v -> selectBuilding("Library", libraryButton));
        murchisonButton.setOnClickListener(v -> selectBuilding("Murchison", murchisonButton));
        fjbButton.setOnClickListener(v -> selectBuilding("FJB", fjbButton));

        // Floor control buttons
        floorUpBtn.setOnClickListener(v -> {
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
                updateFloorDisplay();
            }
        });

        floorDownBtn.setOnClickListener(v -> {
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
                updateFloorDisplay();
            }
        });

        // Initialize map
        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.indoorMapFragment);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        // Start periodic UI updates
        startPeriodicUpdates();
    }

    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        googleMap = map;
        googleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        googleMap.getUiSettings().setCompassEnabled(true);
        googleMap.getUiSettings().setZoomControlsEnabled(true);

        // Initialize IndoorMapManager
        indoorMapManager = new IndoorMapManager(googleMap, requireContext());
        indoorMapManager.addFallbackBuildings();
        
        // Set floor data listener
        indoorMapManager.setOnFloorDataLoadedListener(hasData -> {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (hasData) {
                        floorControlsLayout.setVisibility(View.VISIBLE);
                        updateFloorDisplay();
                    } else {
                        floorControlsLayout.setVisibility(View.GONE);
                    }
                });
            }
        });

        // Set up polygon click listener
        googleMap.setOnPolygonClickListener(polygon -> {
            if (indoorMapManager != null) {
                indoorMapManager.onPolygonClick(polygon);
                updateFloorDisplay();
            }
        });

        // Move camera to Edinburgh KB campus
        LatLng kbCampus = new LatLng(55.9230, -3.1750);
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(kbCampus, 17f));

        // Start location updates
        startLocationUpdates();
    }

    /**
     * Select a building and load its indoor map
     */
    private void selectBuilding(String buildingName, MaterialButton button) {
        // Update button styles
        resetBuildingButtons();
        button.setBackgroundColor(getResources().getColor(R.color.md_theme_primary, null));
        selectedBuildingButton = button;

        // Get building center coordinates
        LatLng buildingCenter = getBuildingCenter(buildingName);
        if (buildingCenter != null && googleMap != null) {
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(buildingCenter, 19f));
            
            // Trigger indoor map load
            if (indoorMapManager != null) {
                // Set selected building BEFORE API call
                // This enables building name verification in API response
                indoorMapManager.setSelectedBuilding(buildingName, buildingCenter);
                indoorMapManager.fetchFloorPlan(buildingCenter, new java.util.ArrayList<>());
            }
        }

        Toast.makeText(getContext(), "Loading " + buildingName + " indoor map...", Toast.LENGTH_SHORT).show();
    }

    /**
     * Reset all building buttons to outlined style
     */
    private void resetBuildingButtons() {
        int outlinedColor = getResources().getColor(android.R.color.transparent, null);
        nucleusButton.setBackgroundColor(outlinedColor);
        libraryButton.setBackgroundColor(outlinedColor);
        murchisonButton.setBackgroundColor(outlinedColor);
        fjbButton.setBackgroundColor(outlinedColor);
    }

    /**
     * Get building center coordinates
     */
    private LatLng getBuildingCenter(String name) {
        switch (name) {
            case "Nucleus":
                return new LatLng(55.92307, -3.17424);
            case "Library":
                return new LatLng(55.92294, -3.17497);
            case "Murchison":
                return new LatLng(55.92413, -3.17916);
            case "FJB":
                return new LatLng(55.92246, -3.17243);
            default:
                return null;
        }
    }

    /**
     * Start real-time location updates
     */
    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(requireContext(), 
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 1000) // 1 second interval
                .setMinUpdateIntervalMillis(500)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    updateLocationDisplay(location);
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, 
                Looper.getMainLooper());
    }

    /**
     * Update location display with new GPS data
     */
    private void updateLocationDisplay(Location location) {
        LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());

        // Update marker on map
        if (currentLocationMarker == null) {
            currentLocationMarker = googleMap.addMarker(new MarkerOptions()
                    .position(latLng)
                    .title("Current Location"));
        } else {
            currentLocationMarker.setPosition(latLng);
        }

        // Update text displays
        latitudeText.setText(String.format(Locale.US, "%.6f", location.getLatitude()));
        longitudeText.setText(String.format(Locale.US, "%.6f", location.getLongitude()));
        
        if (location.hasAltitude()) {
            altitudeText.setText(String.format(Locale.US, "%.1f m", location.getAltitude()));
        }
        
        if (location.hasAccuracy()) {
            accuracyText.setText(String.format(Locale.US, "± %.1f m", location.getAccuracy()));
        }
    }

    /**
     * Start periodic UI updates for sensor data
     */
    private void startPeriodicUpdates() {
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateSensorData();
                updateHandler.postDelayed(this, 1000); // Update every second
            }
        };
        updateHandler.post(updateRunnable);
    }

    /**
     * Update sensor data displays
     */
    private void updateSensorData() {
        if (sensorFusion != null) {
            // Update altitude from barometer sensor
            float elevation = sensorFusion.getElevation();
            if (elevation != 0) {
                altitudeText.setText(String.format(Locale.US, "%.1f m", elevation));
            }

            // Update floor estimation
            if (indoorMapManager != null && indoorMapManager.getAvailableFloorsCount() > 0) {
                String floorName = indoorMapManager.getCurrentFloorName();
                if (floorName != null) {
                    floorText.setText(floorName);
                }
            }
        }
    }

    /**
     * Update floor display text
     */
    private void updateFloorDisplay() {
        if (indoorMapManager != null) {
            int currentFloor = indoorMapManager.getCurrentFloor();
            int totalFloors = indoorMapManager.getAvailableFloorsCount();
            String floorName = indoorMapManager.getCurrentFloorName();

            if (floorName != null) {
                currentFloorText.setText(floorName + " (" + (currentFloor + 1) + "/" + totalFloors + ")");
            } else {
                currentFloorText.setText("Floor " + currentFloor);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Stop location updates
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        // Stop periodic updates
        if (updateHandler != null && updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Restart location updates
        startLocationUpdates();
        // Restart periodic updates
        if (updateHandler != null && updateRunnable != null) {
            updateHandler.post(updateRunnable);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (updateHandler != null && updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
        }
    }
}
