package com.openpositioning.PositionMe.presentation.fragment;

import android.content.Context;
import android.content.res.ColorStateList;
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
import com.google.android.material.switchmaterial.SwitchMaterial;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.presentation.trajmap.BuildingPolygonPlotter;
import com.openpositioning.PositionMe.presentation.trajmap.TrajectoryPlotter;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.google.android.material.button.MaterialButton;
import java.util.Objects;
import android.widget.Spinner;

/**
 * A fragment responsible for displaying a trajectory map using Google Maps.
 *
 * It delegates trajectory plotting to external classes or the "TrajectoryPlotter"
 * from the com.openpositioning.PositionMe.presentation.trajmap package.
 * The building polygon drawing is delegated to BuildingPolygonPlotter.
 */
public class TrajectoryMapFragment extends Fragment {

    private GoogleMap gMap; // Google Maps instance

    // Plotter references (Raw and Fusion)
    private TrajectoryPlotter rawTrajectoryPlotter;
    private TrajectoryPlotter fusionTrajectoryPlotter;
    private TrajectoryPlotter wifiTrajectoryPlotter;

    // GNSS-related members
    private com.google.android.gms.maps.model.Marker gnssMarker;
    private Circle gnssAccuracyCircle;
    private Polyline gnssPolyline;
    private LatLng lastGnssLocation = null;
    private boolean isGnssOn = false;

    private SensorFusion sensorFusion;
    private IndoorMapManager indoorMapManager;

    // UI references
    private Spinner switchMapSpinner;
    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;
    private FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;
    private MaterialButton showRawButton, showFusionButton;

    // For deferring camera movement until map is ready
    private LatLng pendingCameraPosition = null;
    private boolean hasPendingCameraMove = false;

    // We reintroduce a field to keep track of the user’s latest “raw” location
    private LatLng rawCurrentLocation = null;

    private final List<Marker> emergencyExitMarkers = new ArrayList<>();
    private final List<Marker> liftMarkers = new ArrayList<>();
    private final List<Marker> toiletMarkers = new ArrayList<>();
    private final List<Marker> accessibleRouteMarkers = new ArrayList<>();
    private final List<Marker> accessibleToiletMarkers = new ArrayList<>();
    private final List<Marker> drinkingWaterMarkers = new ArrayList<>();
    private final List<Marker> medicalRoomMarkers = new ArrayList<>();


    public TrajectoryMapFragment() {
        // Required empty public constructor
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Inflate the layout containing map + map-related UI
        return inflater.inflate(R.layout.fragment_trajectory_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Grab references to UI controls
        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch       = view.findViewById(R.id.gnssSwitch);
        autoFloorSwitch  = view.findViewById(R.id.autoFloor);
        floorUpButton    = view.findViewById(R.id.floorUpButton);
        floorDownButton  = view.findViewById(R.id.floorDownButton);
        switchColorButton= view.findViewById(R.id.lineColorButton);
        showRawButton    = view.findViewById(R.id.showRawButton);
        showFusionButton = view.findViewById(R.id.showFusionButton);

        // Initialize sensorFusion
        sensorFusion = SensorFusion.getInstance();
        sensorFusion.setTrajectoryMapFragment(this);

        // Hide floor controls initially
        setFloorControlsVisibility(View.GONE);

        // Set up button toggles
        showRawButton.setOnClickListener(v -> setShowRawTrajectory(!isRawTrajectoryVisible()));
        showFusionButton.setOnClickListener(v -> setShowFusionTrajectory(!isFusionTrajectoryVisible()));

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

                    // Instantiate our plotters
                    rawTrajectoryPlotter = new TrajectoryPlotter.RawTrajectoryPlotter(
                            requireContext(),
                            gMap
                    );
                    fusionTrajectoryPlotter = new TrajectoryPlotter.FusionTrajectoryPlotter(
                            requireContext(),
                            gMap
                    );
                    wifiTrajectoryPlotter = new TrajectoryPlotter.WifiTrajectoryPlotter(
                            requireContext(),
                            gMap
                    );

                    // Draw building polygons
                    BuildingPolygonPlotter drawer = new BuildingPolygonPlotter(gMap);
                    drawer.drawBuildingPolygons();

                    // If we had a pending camera move, apply it now
                    if (hasPendingCameraMove && pendingCameraPosition != null) {
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
                        hasPendingCameraMove = false;
                        pendingCameraPosition = null;
                    }
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
            // Example: toggling color for the "raw" polyline
            if (rawTrajectoryPlotter != null
                    && rawTrajectoryPlotter.getPolyline() != null) {
                int currentColor = rawTrajectoryPlotter.getPolyline().getColor();
                if (currentColor == Color.RED) {
                    switchColorButton.setBackgroundColor(Color.BLACK);
                    rawTrajectoryPlotter.getPolyline().setColor(Color.BLACK);
                } else {
                    switchColorButton.setBackgroundColor(Color.RED);
                    rawTrajectoryPlotter.getPolyline().setColor(Color.RED);
                }
            }
        });

        // Floor up/down logic
        autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            // TODO: implement your auto-floor logic if needed.
        });

        floorUpButton.setOnClickListener(v -> {
            // If user manually changes floor, turn off auto floor
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
                Log.d("currentfloor", "Building polygon added, vertex count: " + this.getCurrentBuilding());
                Log.d("currentfloor", "Building polygon added, vertex count: " + this.getCurrentFloor());
                //update indoor maker
                updateAllIndoorMarkers();
            }
        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
                Log.d("currentfloor", "Building polygon added, vertex count: " + this.getCurrentBuilding());
                Log.d("currentfloor", "Building polygon added, vertex count: " + this.getCurrentFloor());
                //update indoor maker
                updateAllIndoorMarkers();
            }
        });

        // Initialize sensorFusion instance
        sensorFusion = SensorFusion.getInstance();

        // Register callback for Wi-Fi floor changes
        sensorFusion.setOnWifiFloorChangedListener(newFloor -> {
            if (autoFloorSwitch.isChecked() && indoorMapManager != null) {
                Log.d("TrajectoryMapFragment", "Wi-Fi floor changed, updating floor to: " + newFloor);
                indoorMapManager.setCurrentFloor(newFloor, true);
                Log.d("currentfloor", "Register callback for Wi-Fi floor changes: " + this.getCurrentBuilding());
                Log.d("currentfloor", "Register callback for Wi-Fi floor changes: " + this.getCurrentFloor());
                //update indoor maker
                updateAllIndoorMarkers();
            }
        });
        sensorFusion.setTrajectoryMapFragment(this);
    }


    /**
     * Basic initialization for map settings.
     */
    private void initMapSettings(GoogleMap map) {
        // Basic map settings
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        // Initialize Indoor map manager
        indoorMapManager = new IndoorMapManager(map);

        // GNSS path in blue
        gnssPolyline = map.addPolyline(new com.google.android.gms.maps.model.PolylineOptions()
                .color(Color.BLUE)
                .width(5f)
        );
    }

    /**
     * Spinner to switch map types (Hybrid, Normal, Satellite).
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
                switch (position) {
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
     * Update the user's "raw" location via the rawTrajectoryPlotter.
     */
    public void updateUserLocation(@NonNull LatLng newLocation, float orientation) {
        // keep track in rawCurrentLocation
        rawCurrentLocation = newLocation;

        if (rawTrajectoryPlotter != null) {
            rawTrajectoryPlotter.updateLocation(newLocation, orientation);
        }

        // Extend polyline if movement occurred
        if (oldLocation != null && !oldLocation.equals(newLocation) && polyline != null) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            points.add(newLocation);
            polyline.setPoints(points);
        }

        if (indoorMapManager != null) {
            // 只在 indoorMap 存在时才考虑更新
            indoorMapManager.setCurrentLocation(newLocation);

            boolean currentState = indoorMapManager.getIsIndoorMapSet();
            if (currentState != lastIndoorMapState) {
                setFloorControlsVisibility(currentState ? View.VISIBLE : View.GONE);
                lastIndoorMapState = currentState;

                Log.d("currentfloor", "Building polygon added, vertex count: " + this.getCurrentBuilding());
                Log.d("currentfloor", "Building polygon added, vertex count: " + this.getCurrentFloor());
                //update indoor maker
                updateAllIndoorMarkers();
            }
        }
    }


    /**
     * Update the user's "fusion" location via the fusionTrajectoryPlotter.
     */
    public void updateFusionLocation(@NonNull LatLng newLocation, float orientation) {
        if (fusionTrajectoryPlotter != null) {
            fusionTrajectoryPlotter.updateLocation(newLocation, orientation);
        }
    }

    public void updatWifiLocation(@NonNull LatLng newLocation, float orientation) {
        if (wifiTrajectoryPlotter != null) {
            wifiTrajectoryPlotter.updateLocation(newLocation, orientation);
        }
    }

    public void setShowRawTrajectory(boolean show) {
        if (rawTrajectoryPlotter != null) {
            rawTrajectoryPlotter.setVisible(show);
        }
        if (showRawButton != null) {
            showRawButton.setBackgroundTintList(ColorStateList.valueOf(show ? Color.RED : Color.GRAY));
        }
    }

    public void setShowFusionTrajectory(boolean show) {
        if (fusionTrajectoryPlotter != null) {
            fusionTrajectoryPlotter.setVisible(show);
        }
        if (showFusionButton != null) {
            showFusionButton.setBackgroundTintList(ColorStateList.valueOf(show ? Color.GREEN : Color.GRAY));
        }

        //update indoor maker
        updateAllIndoorMarkers();

    }

    private boolean isRawTrajectoryVisible() {
        return rawTrajectoryPlotter != null
                && rawTrajectoryPlotter.getPolyline() != null
                && rawTrajectoryPlotter.getPolyline().isVisible();
    }
    private boolean isFusionTrajectoryVisible() {
        return fusionTrajectoryPlotter != null
                && fusionTrajectoryPlotter.getPolyline() != null
                && fusionTrajectoryPlotter.getPolyline().isVisible();
    }
    /**
     * Sets the floor control buttons & switch to visible or gone.
     */
    private void setFloorControlsVisibility(int visibility) {
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);
        autoFloorSwitch.setVisibility(visibility);
    }

    // get current floor - return current floor
    public int getCurrentFloor() {
        return indoorMapManager != null ? indoorMapManager.getCurrentFloor() : 0;
    }

    // get current fuilding - return name of current building / int represent
    public String getCurrentBuilding() {
        return indoorMapManager != null ? indoorMapManager.getCurrentBuilding() : "";
    }

    private void updateAllIndoorMarkers() {
        Context context = requireContext();
        int floor = getCurrentFloor();
        String building = getCurrentBuilding();

        TrajectoryMapMaker.updateEmergencyExitMarkers(gMap, floor, building, emergencyExitMarkers, context);
        TrajectoryMapMaker.updateLiftMarkers(gMap, floor, building, liftMarkers, context);
        TrajectoryMapMaker.updateToiletMarkers(gMap, floor, building, toiletMarkers, context);
        TrajectoryMapMaker.updateAccessibleToiletMarkers(gMap, floor, building, accessibleToiletMarkers, context);
        TrajectoryMapMaker.updateDrinkingWaterMarkers(gMap, floor, building, drinkingWaterMarkers, context);
        TrajectoryMapMaker.updateAccessibleRouteMarkers(gMap, floor, building, accessibleRouteMarkers, context);
        TrajectoryMapMaker.updateMedicalRoomMarkers(gMap, floor, building, medicalRoomMarkers, context);
    }



    /**
     * Allows other components to set the initial map camera position.
     * If the map isn't ready, we store a pending movement.
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

    // GNSS Methods
    public boolean isGnssEnabled() {
        return isGnssOn;
    }

    /**
     * Update GNSS location/marker as needed.
     */
    public void updateGNSS(@NonNull LatLng location) {
        if (gMap == null || !isGnssOn) return;

        float accuracy = sensorFusion.getGnssAccuracy(); // or your own logic

        if (gnssMarker == null) {
            gnssMarker = gMap.addMarker(new com.google.android.gms.maps.model.MarkerOptions()
                    .position(location)
                    .title("GNSS Position")
                    .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory
                            .defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_AZURE))
            );
            lastGnssLocation = location;

            gnssAccuracyCircle = gMap.addCircle(new com.google.android.gms.maps.model.CircleOptions()
                    .center(location)
                    .radius(accuracy)
                    .strokeColor(Color.BLUE)
                    .fillColor(Color.argb(50, 0, 0, 255))
                    .strokeWidth(2f));
        } else {
            // Move existing GNSS marker
            gnssMarker.setPosition(location);
            if (gnssAccuracyCircle != null) {
                gnssAccuracyCircle.setCenter(location);
                gnssAccuracyCircle.setRadius(accuracy);
            }

            // Add a segment to the blue GNSS line, if this is a new location
            if (lastGnssLocation != null && !lastGnssLocation.equals(location)) {
                // Extend the blue GNSS line
                java.util.List<LatLng> gnssPoints = new java.util.ArrayList<>(gnssPolyline.getPoints());
                gnssPoints.add(location);
                gnssPolyline.setPoints(gnssPoints);
            }
            lastGnssLocation = location;
        }
    }


    /**
     * Clear GNSS marker/circle if toggled off.
     */
    public void clearGNSS() {
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
        if (gnssAccuracyCircle != null) {
            gnssAccuracyCircle.remove();
            gnssAccuracyCircle = null;
        }
    }

    /**
     * Example clearing all lines & markers on the map if needed.
     */
    public void clearMapAndReset() {
        if (rawTrajectoryPlotter != null) rawTrajectoryPlotter.clear();
        if (fusionTrajectoryPlotter != null) fusionTrajectoryPlotter.clear();
        if (gnssPolyline != null) {
            gnssPolyline.remove();
            gnssPolyline = null;
        }
        clearGNSS();
        lastGnssLocation = null;
    }

    /**
     * (Reintroduced) Provide the GoogleMap instance.
     * If you have code that calls getGoogleMap() externally, this helps fix “cannot resolve” errors.
     */
    public GoogleMap getGoogleMap() {
        return gMap;
    }

    /**
     * (Reintroduced) Provide the “raw” current location if needed.
     * If you have code that calls getCurrentLocation() externally, this helps fix “cannot resolve” errors.
     */
    public LatLng getCurrentLocation() {
        return rawCurrentLocation;
    }

    /**
     * (Reintroduced) Let other classes pass a pinned location or “tag” for calibration logic.
     * If your code calls updateCalibrationPinLocation(), this is where you handle it.
     */
    public void updateCalibrationPinLocation(@NonNull LatLng newLocation, boolean pinConfirmed) {
        if (gMap == null) return;

        if (pinConfirmed) {
            Marker orientationMarker = gMap.addMarker(new MarkerOptions()
                    .position(newLocation)
                    .flat(true)
                    .title("Tagged Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_assignment_turned_in_24_red)))
            );
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
        }

        //update indoor map overlay
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(newLocation);
            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
        }
    }

}
