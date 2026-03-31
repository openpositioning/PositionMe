package com.openpositioning.PositionMe.presentation.fragment;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
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
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.IndoorMapManager;

import java.util.ArrayList;
import java.util.Locale;

public class IndoorPositioningFragment extends Fragment implements OnMapReadyCallback {
    private static final int AUTO_FLOOR_CONFIRMATIONS = 4;
    private static final long AUTO_FLOOR_SWITCH_COOLDOWN_MS = 6000L;

    private TextView latitudeText;
    private TextView longitudeText;
    private TextView altitudeText;
    private TextView accuracyText;
    private TextView floorText;
    private TextView currentFloorText;
    private MaterialButton nucleusButton;
    private MaterialButton libraryButton;
    private MaterialButton murchisonButton;
    private MaterialButton fjbButton;
    private MaterialButton locationToggleButton;
    private MaterialButton buildingToggleButton;
    private FloatingActionButton floorUpBtn;
    private FloatingActionButton floorDownBtn;
    private View floorControlsLayout;
    private View locationContent;
    private View buildingContent;

    private GoogleMap googleMap;
    private IndoorMapManager indoorMapManager;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private Marker currentLocationMarker;

    private SensorFusion sensorFusion;
    private Double latestGpsAltitudeMeters;
    private Float latestEstimatedAbsoluteAltitudeMeters;
    private LatLng latestGnssLatLng;
    private float latestGnssAccuracy = Float.MAX_VALUE;
    private String selectedBuildingName;
    private boolean locationPanelExpanded = true;
    private boolean buildingPanelExpanded = true;
    private int pendingAutoFloorCandidate = Integer.MIN_VALUE;
    private int pendingAutoFloorCandidateCount = 0;
    private long lastAutoFloorSwitchTimestampMs = 0L;
    private boolean wasNearVerticalTransition = false;

    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private Runnable updateRunnable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity != null && activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setTitle("Indoor Positioning");
        }
        return inflater.inflate(R.layout.fragment_indoor_positioning, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

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
        locationToggleButton = view.findViewById(R.id.locationToggleButton);
        buildingToggleButton = view.findViewById(R.id.buildingToggleButton);
        floorUpBtn = view.findViewById(R.id.floorUpBtn);
        floorDownBtn = view.findViewById(R.id.floorDownBtn);
        locationContent = view.findViewById(R.id.locationContent);
        buildingContent = view.findViewById(R.id.buildingContent);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext());
        sensorFusion = SensorFusion.getInstance();

        nucleusButton.setOnClickListener(v -> selectBuilding("Nucleus", nucleusButton));
        libraryButton.setOnClickListener(v -> selectBuilding("Library", libraryButton));
        murchisonButton.setOnClickListener(v -> selectBuilding("Murchison", murchisonButton));
        fjbButton.setOnClickListener(v -> selectBuilding("FJB", fjbButton));
        locationToggleButton.setOnClickListener(v -> toggleLocationPanel());
        buildingToggleButton.setOnClickListener(v -> toggleBuildingPanel());

        floorUpBtn.setOnClickListener(v -> {
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
                syncIndoorFloorReference();
                updateFloorDisplay();
            }
        });

        floorDownBtn.setOnClickListener(v -> {
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
                syncIndoorFloorReference();
                updateFloorDisplay();
            }
        });

        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager()
                .findFragmentById(R.id.indoorMapFragment);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        resetBuildingButtons();
        startPeriodicUpdates();
        requestImmediateLocationSeed();
        updateAltitudeDisplay();
        applyPanelState(locationContent, locationToggleButton, locationPanelExpanded);
        applyPanelState(buildingContent, buildingToggleButton, buildingPanelExpanded);
    }

    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        googleMap = map;
        googleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        googleMap.getUiSettings().setCompassEnabled(true);
        googleMap.getUiSettings().setZoomControlsEnabled(true);

        indoorMapManager = new IndoorMapManager(googleMap, requireContext());
        indoorMapManager.addFallbackBuildings();
        indoorMapManager.setOnFloorDataLoadedListener(hasData -> {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    selectedBuildingName = indoorMapManager.getSelectedBuildingName();
                    floorControlsLayout.setVisibility(hasData ? View.VISIBLE : View.GONE);
                    syncIndoorFloorReference();
                    updateFloorDisplay();
                    updatePositionDisplay();
                });
            }
        });

        googleMap.setOnPolygonClickListener(polygon -> {
            if (indoorMapManager != null) {
                indoorMapManager.onPolygonClick(polygon);
                selectedBuildingName = indoorMapManager.getSelectedBuildingName();
                updateFloorDisplay();
                updatePositionDisplay();
            }
        });

        LatLng kbCampus = new LatLng(55.9230, -3.1750);
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(kbCampus, 17f));
        startLocationUpdates();
    }

    private void selectBuilding(String buildingName, MaterialButton button) {
        resetBuildingButtons();
        setBuildingButtonSelected(button, true);
        selectedBuildingName = buildingName;
        clearPendingAutoFloorCandidate();

        LatLng buildingCenter = getBuildingCenter(buildingName);
        if (buildingCenter != null && googleMap != null) {
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(buildingCenter, 19f));
            if (indoorMapManager != null) {
                indoorMapManager.setIndoorMapVisible(true);
                indoorMapManager.setSelectedBuilding(buildingName, buildingCenter);
                indoorMapManager.fetchFloorPlan(buildingCenter, new ArrayList<>());
                updateFloorDisplay();
            }
        }

        buildingPanelExpanded = false;
        applyPanelState(buildingContent, buildingToggleButton, buildingPanelExpanded);
        Toast.makeText(getContext(), "Loading " + buildingName + " indoor map...", Toast.LENGTH_SHORT).show();
    }

    private void resetBuildingButtons() {
        setBuildingButtonSelected(nucleusButton, false);
        setBuildingButtonSelected(libraryButton, false);
        setBuildingButtonSelected(murchisonButton, false);
        setBuildingButtonSelected(fjbButton, false);
    }

    private void setBuildingButtonSelected(MaterialButton button, boolean selected) {
        if (button == null || getContext() == null) {
            return;
        }

        int background = ContextCompat.getColor(requireContext(), selected ? R.color.ios_blue : R.color.ios_surface);
        int text = ContextCompat.getColor(requireContext(), selected ? R.color.white : R.color.ios_label);
        int stroke = ContextCompat.getColor(requireContext(), selected ? R.color.ios_blue : R.color.ios_separator);
        int icon = ContextCompat.getColor(requireContext(), selected ? R.color.white : R.color.ios_blue);

        button.setBackgroundTintList(ColorStateList.valueOf(background));
        button.setTextColor(text);
        button.setStrokeColor(ColorStateList.valueOf(stroke));
        button.setIconTint(ColorStateList.valueOf(icon));
    }

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

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        requestImmediateLocationSeed();

        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500)
                .setMinUpdateDistanceMeters(0.5f)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                Location location = locationResult.getLastLocation();
                if (location != null) {
                    latestGnssLatLng = new LatLng(location.getLatitude(), location.getLongitude());
                    if (location.hasAltitude()) {
                        latestGpsAltitudeMeters = location.getAltitude();
                    }
                    if (location.hasAccuracy()) {
                        latestGnssAccuracy = location.getAccuracy();
                    }
                    updatePositionDisplay();
                    updateAltitudeDisplay();
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
    }

    private void requestImmediateLocationSeed() {
        if (fusedLocationClient == null || ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location == null) {
                return;
            }

            latestGnssLatLng = new LatLng(location.getLatitude(), location.getLongitude());
            if (location.hasAltitude()) {
                latestGpsAltitudeMeters = location.getAltitude();
            }
            if (location.hasAccuracy()) {
                latestGnssAccuracy = location.getAccuracy();
            }

            updatePositionDisplay();
            updateAltitudeDisplay();
        });
    }

    private void startPeriodicUpdates() {
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateSensorData();
                updateHandler.postDelayed(this, 1000);
            }
        };
        updateHandler.post(updateRunnable);
    }

    private void updateSensorData() {
        if (sensorFusion != null) {
            float estimatedAltitude = sensorFusion.getEstimatedAbsoluteAltitude();
            if (!Float.isNaN(estimatedAltitude)) {
                latestEstimatedAbsoluteAltitudeMeters = estimatedAltitude;
            }

            if (indoorMapManager != null && indoorMapManager.getAvailableFloorsCount() > 0) {
                syncIndoorMapFloorWithEstimate();
                String floorName = indoorMapManager.getCurrentFloorName();
                if (floorName != null) {
                    floorText.setText(floorName);
                }
            } else {
                floorText.setText(formatEstimatedFloor(sensorFusion.getEstimatedFloor()));
            }
        }

        updatePositionDisplay();
        updateAltitudeDisplay();
    }

    private void syncIndoorFloorReference() {
        if (sensorFusion == null || indoorMapManager == null) {
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

    private void syncIndoorMapFloorWithEstimate() {
        if (indoorMapManager == null || sensorFusion == null) {
            return;
        }

        int totalFloors = indoorMapManager.getAvailableFloorsCount();
        if (totalFloors <= 0) {
            clearPendingAutoFloorCandidate();
            return;
        }

        int estimatedFloor = indoorMapManager.mapBarometerBandFloorToFloorIndex(
            sensorFusion.getEstimatedFloor()
        );
        if (estimatedFloor == indoorMapManager.getCurrentFloor()) {
            clearPendingAutoFloorCandidate();
            return;
        }

        if (pendingAutoFloorCandidate == estimatedFloor) {
            pendingAutoFloorCandidateCount++;
        } else {
            pendingAutoFloorCandidate = estimatedFloor;
            pendingAutoFloorCandidateCount = 1;
        }

        if (pendingAutoFloorCandidateCount >= AUTO_FLOOR_CONFIRMATIONS) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastAutoFloorSwitchTimestampMs < AUTO_FLOOR_SWITCH_COOLDOWN_MS) {
                return;
            }
            indoorMapManager.setCurrentFloor(estimatedFloor, true);
            syncIndoorFloorReference();
            lastAutoFloorSwitchTimestampMs = now;
            clearPendingAutoFloorCandidate();
            updateFloorDisplay();
        }
    }

    private void clearPendingAutoFloorCandidate() {
        pendingAutoFloorCandidate = Integer.MIN_VALUE;
        pendingAutoFloorCandidateCount = 0;
    }

    private void updatePositionDisplay() {
        LatLng displayLatLng = latestGnssLatLng;
        boolean usingFusion = false;

        if (sensorFusion != null && sensorFusion.getFusedLatLng() != null) {
            displayLatLng = sensorFusion.getFusedLatLng();
            usingFusion = true;
        }

        if (displayLatLng == null && sensorFusion != null) {
            float[] gnss = sensorFusion.getGNSSLatitude(false);
            if (isValidCoordinate(gnss[0], gnss[1])) {
                displayLatLng = new LatLng(gnss[0], gnss[1]);
            }
        }

        if (displayLatLng == null) {
            latitudeText.setText("--");
            longitudeText.setText("--");
            accuracyText.setText("Waiting for location");
            return;
        }

        updateBarometerAutoFloorGate(displayLatLng);

        if (indoorMapManager != null && currentLocationMarker != null) {
            displayLatLng = indoorMapManager.validatePosition(displayLatLng, currentLocationMarker.getPosition());
        }

        latitudeText.setText(String.format(Locale.US, "%.6f", displayLatLng.latitude));
        longitudeText.setText(String.format(Locale.US, "%.6f", displayLatLng.longitude));

        if (googleMap != null) {
            if (currentLocationMarker == null) {
                currentLocationMarker = googleMap.addMarker(new MarkerOptions()
                        .position(displayLatLng)
                        .title("Current Location"));
            } else {
                currentLocationMarker.setPosition(displayLatLng);
            }
        }

        if (usingFusion) {
            float gnssAccuracy = sensorFusion != null ? sensorFusion.getGnssAccuracy() : latestGnssAccuracy;
            if (gnssAccuracy < Float.MAX_VALUE) {
                accuracyText.setText(String.format(Locale.US, "Fusion display | GNSS ref +/- %.1f m", gnssAccuracy));
            } else {
                accuracyText.setText("Fusion display");
            }
        } else if (latestGnssAccuracy < Float.MAX_VALUE) {
            accuracyText.setText(String.format(Locale.US, "GNSS +/- %.1f m", latestGnssAccuracy));
        } else {
            accuracyText.setText("Waiting for location");
        }
    }

    private boolean isValidCoordinate(float lat, float lon) {
        return lat >= -90f && lat <= 90f && lon >= -180f && lon <= 180f
                && !(Math.abs(lat) < 0.00001f && Math.abs(lon) < 0.00001f);
    }

    private void updateBarometerAutoFloorGate(@NonNull LatLng location) {
        if (sensorFusion == null || indoorMapManager == null || indoorMapManager.getAvailableFloorsCount() <= 0) {
            wasNearVerticalTransition = false;
            if (sensorFusion != null) {
                sensorFusion.setBarometerAutoFloorEnabled(false);
            }
            return;
        }

        boolean nearVerticalTransition = indoorMapManager.isNearCurrentFloorVerticalTransition(location);
        if (nearVerticalTransition && !wasNearVerticalTransition) {
            sensorFusion.setBarometerAutoFloorEnabled(true);
        } else if (!nearVerticalTransition && wasNearVerticalTransition) {
            sensorFusion.setBarometerAutoFloorEnabled(false);
            clearPendingAutoFloorCandidate();
        }

        wasNearVerticalTransition = nearVerticalTransition;
    }

    private void updateAltitudeDisplay() {
        if (altitudeText == null) {
            return;
        }

        if (latestEstimatedAbsoluteAltitudeMeters != null) {
            altitudeText.setText(String.format(Locale.US, "%.1f m", latestEstimatedAbsoluteAltitudeMeters));
        } else if (latestGpsAltitudeMeters != null) {
            altitudeText.setText(String.format(Locale.US, "%.1f m", latestGpsAltitudeMeters));
        } else {
            altitudeText.setText("--");
        }
    }

    private void updateFloorDisplay() {
        if (indoorMapManager != null) {
            int currentFloor = indoorMapManager.getCurrentFloor();
            int totalFloors = indoorMapManager.getAvailableFloorsCount();
            String floorName = indoorMapManager.getCurrentFloorName();

            if (floorName != null && totalFloors > 0) {
                currentFloorText.setText(floorName + " (" + (currentFloor + 1) + "/" + totalFloors + ")");
                floorText.setText(floorName);
            } else if (selectedBuildingName != null) {
                currentFloorText.setText(selectedBuildingName + " loading...");
            } else if (sensorFusion != null) {
                currentFloorText.setText("Estimated " + formatEstimatedFloor(sensorFusion.getEstimatedFloor()));
            } else {
                currentFloorText.setText("Select a building");
            }
        }
    }

    private void toggleLocationPanel() {
        locationPanelExpanded = !locationPanelExpanded;
        applyPanelState(locationContent, locationToggleButton, locationPanelExpanded);
    }

    private void toggleBuildingPanel() {
        buildingPanelExpanded = !buildingPanelExpanded;
        applyPanelState(buildingContent, buildingToggleButton, buildingPanelExpanded);
    }

    private void applyPanelState(View content, MaterialButton toggle, boolean expanded) {
        if (content != null) {
            content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (toggle != null) {
            toggle.setIconResource(expanded
                    ? android.R.drawable.arrow_up_float
                    : android.R.drawable.arrow_down_float);
        }
    }

    private String formatEstimatedFloor(int estimatedFloor) {
        if (estimatedFloor == 0) {
            return "Ground";
        }
        if (estimatedFloor > 0) {
            return "Floor " + estimatedFloor;
        }
        return "Basement " + Math.abs(estimatedFloor);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        if (updateHandler != null && updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        startLocationUpdates();
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

