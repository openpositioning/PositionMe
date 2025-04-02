package com.openpositioning.PositionMe.presentation.fragment;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.presentation.trajmap.BuildingPolygonPlotter;
import com.openpositioning.PositionMe.presentation.trajmap.TrajectoryPlotter;
import com.openpositioning.PositionMe.presentation.trajmap.TrajectoryPlotter.GnssTrajectoryPlotter;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;

public abstract class TrajectoryMapFragment extends Fragment {

    protected GoogleMap gMap;

    protected TrajectoryPlotter rawTrajectoryPlotter;
    protected TrajectoryPlotter fusionTrajectoryPlotter;
    protected TrajectoryPlotter wifiTrajectoryPlotter;
    protected GnssTrajectoryPlotter gnssTrajectoryPlotter;

    protected boolean isGnssOn = false;

    protected SensorFusion sensorFusion;
    protected IndoorMapManager indoorMapManager;

    protected MaterialButton gnssButton;
    protected MaterialButton autoFloorButton;
    protected MaterialButton showRawButton;
    protected MaterialButton fusionButton;
    protected MaterialButton wifiButton;

    protected FloatingActionButton floorUpButton, floorDownButton, recenterButton;

    protected LatLng pendingCameraPosition = null;
    protected boolean hasPendingCameraMove = false;

    protected LatLng rawCurrentLocation = new LatLng(0,0);
    protected LatLng fusionCurrentLocation = new LatLng(0,0);

    protected boolean isAutoFloorOn = false;

    public TrajectoryMapFragment() {}

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_trajectory_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        gnssButton       = view.findViewById(R.id.gnssButton);
        autoFloorButton  = view.findViewById(R.id.autoFloorButton);
        floorUpButton    = view.findViewById(R.id.floorUpButton);
        floorDownButton  = view.findViewById(R.id.floorDownButton);
        recenterButton   = view.findViewById(R.id.recenterButton);
        showRawButton    = view.findViewById(R.id.pdrButton);
        fusionButton     = view.findViewById(R.id.fusionButton);
        wifiButton       = view.findViewById(R.id.wifiButton);

        sensorFusion = SensorFusion.getInstance();
        sensorFusion.setTrajectoryMapFragment(this);

        setFloorControlsVisibility(View.GONE);

        showRawButton.setOnClickListener(v -> setShowRawTrajectory(!isRawTrajectoryVisible()));
        fusionButton.setOnClickListener(v -> setShowFusionTrajectory(!isFusionTrajectoryVisible()));

        gnssButton.setOnClickListener(v -> {
            isGnssOn = !isGnssOn;
            gnssButton.setBackgroundTintList(ColorStateList.valueOf(
                    isGnssOn ? getResources().getColor(R.color.pastelBlue) : Color.GRAY));
            if (!isGnssOn && gnssTrajectoryPlotter != null) {
                gnssTrajectoryPlotter.clear();
            }
        });

        autoFloorButton.setOnClickListener(v -> {
            isAutoFloorOn = !isAutoFloorOn;
            autoFloorButton.setBackgroundTintList(ColorStateList.valueOf(
                    isAutoFloorOn ? getResources().getColor(R.color.md_theme_primary) : Color.GRAY));
            // hide the floor switch button if auto floor is on
            if (indoorMapManager != null) {
                setManualFloorControlVisibility(isAutoFloorOn ? View.GONE : View.VISIBLE);
            }
        });

        floorUpButton.setOnClickListener(v -> {
            isAutoFloorOn = false;
            autoFloorButton.setBackgroundTintList(ColorStateList.valueOf(Color.GRAY));
            if (indoorMapManager != null) indoorMapManager.increaseFloor();
        });

        floorDownButton.setOnClickListener(v -> {
            isAutoFloorOn = false;
            autoFloorButton.setBackgroundTintList(ColorStateList.valueOf(Color.GRAY));
            if (indoorMapManager != null) indoorMapManager.decreaseFloor();
        });

        recenterButton.setOnClickListener(v -> {
            if (rawCurrentLocation != null && gMap != null) {
                gMap.animateCamera(CameraUpdateFactory.newLatLngZoom(fusionCurrentLocation, 19f));
            }
        });

        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.trajectoryMap);

        if (mapFragment != null) {
            mapFragment.getMapAsync(googleMap -> {
                gMap = googleMap;
                initMapSettings(gMap);

                rawTrajectoryPlotter = new TrajectoryPlotter.RawTrajectoryPlotter(requireContext(), gMap);
                fusionTrajectoryPlotter = new TrajectoryPlotter.FusionTrajectoryPlotter(requireContext(), gMap);
                wifiTrajectoryPlotter = new TrajectoryPlotter.WifiTrajectoryPlotter(requireContext(), gMap);
                gnssTrajectoryPlotter = new GnssTrajectoryPlotter(requireContext(), gMap);

                BuildingPolygonPlotter drawer = new BuildingPolygonPlotter(gMap);
                drawer.drawBuildingPolygons();

                if (hasPendingCameraMove && pendingCameraPosition != null) {
                    gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
                    hasPendingCameraMove = false;
                    pendingCameraPosition = null;
                }
            });
        }

        // Register callback for Wi-Fi floor changes
        sensorFusion.setOnWifiFloorChangedListener(newFloor -> {
            if (isAutoFloorOn && indoorMapManager != null) {
                Log.d("TrajectoryMapFragment", "Wi-Fi floor changed, updating floor to: " + newFloor);
                indoorMapManager.setCurrentFloor(newFloor, true);
            }
        });

        sensorFusion.setTrajectoryMapFragment(this);
    }

    protected void initMapSettings(GoogleMap map) {
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        indoorMapManager = new IndoorMapManager(map);
    }

    public void updateUserLocation(@NonNull LatLng newLocation, float orientation) {
        rawCurrentLocation = newLocation;
        if (rawTrajectoryPlotter != null) {
            rawTrajectoryPlotter.updateLocation(newLocation, orientation);
        }
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(newLocation);
            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
        }
    }

    public void updateFusionLocation(@NonNull LatLng newLocation, float orientation) {
        fusionCurrentLocation = newLocation;
        if (fusionTrajectoryPlotter != null) {
            fusionTrajectoryPlotter.updateLocation(newLocation, orientation);
        }
    }

    public void updateWifiLocation(@NonNull LatLng newLocation, float orientation) {
        if (wifiTrajectoryPlotter != null) {
            wifiTrajectoryPlotter.updateLocation(newLocation, orientation);
        }
    }

    public void updateGNSS(@NonNull LatLng location) {
        if (gMap == null || !isGnssOn || gnssTrajectoryPlotter == null) return;
        float accuracy = sensorFusion.getGnssAccuracy();
        gnssTrajectoryPlotter.updateGnssLocation(location, accuracy);
    }

    public boolean isGnssEnabled() {
        return isGnssOn;
    }

    public void clearGNSS() {
        if (gnssTrajectoryPlotter != null) {
            gnssTrajectoryPlotter.clear();
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
        if (fusionButton != null) {
            fusionButton.setBackgroundTintList(ColorStateList.valueOf(show ? Color.GREEN : Color.GRAY));
        }
    }

    protected boolean isRawTrajectoryVisible() {
        return rawTrajectoryPlotter != null &&
                rawTrajectoryPlotter.getPolyline() != null &&
                rawTrajectoryPlotter.getPolyline().isVisible();
    }

    protected boolean isFusionTrajectoryVisible() {
        return fusionTrajectoryPlotter != null &&
                fusionTrajectoryPlotter.getPolyline() != null &&
                fusionTrajectoryPlotter.getPolyline().isVisible();
    }

    protected void setFloorControlsVisibility(int visibility) {
        if (floorUpButton != null) floorUpButton.setVisibility(visibility);
        if (floorDownButton != null) floorDownButton.setVisibility(visibility);
        if (autoFloorButton != null) autoFloorButton.setVisibility(visibility);
    }

    protected void setManualFloorControlVisibility(int visibility) {
        if (floorUpButton != null) floorUpButton.setVisibility(visibility);
        if (floorDownButton != null) floorDownButton.setVisibility(visibility);
    }

    public void setInitialCameraPosition(@NonNull LatLng startLocation) {
        if (gMap != null) {
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLocation, 19f));
        } else {
            pendingCameraPosition = startLocation;
            hasPendingCameraMove = true;
        }
    }

    public GoogleMap getGoogleMap() {
        return gMap;
    }

    public LatLng getCurrentLocation() {
        return rawCurrentLocation;
    }

    public void updateCalibrationPinLocation(@NonNull LatLng newLocation, boolean pinConfirmed) {
        if (gMap == null) return;

        if (pinConfirmed) {
            gMap.addMarker(new com.google.android.gms.maps.model.MarkerOptions()
                    .position(newLocation)
                    .flat(true)
                    .title("Tagged Position")
                    .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_assignment_turned_in_24_red)))
            );
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
        }

        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(newLocation);
            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
        }
    }

    public void clearMapAndReset() {
        if (rawTrajectoryPlotter != null) rawTrajectoryPlotter.clear();
        if (fusionTrajectoryPlotter != null) fusionTrajectoryPlotter.clear();
        if (wifiTrajectoryPlotter != null) wifiTrajectoryPlotter.clear();
        if (gnssTrajectoryPlotter != null) gnssTrajectoryPlotter.clear();
    }

    public static class RecordingTrajectoryMapFragment extends TrajectoryMapFragment{

    }

    public static class ReplayTrajectoryMapFragment extends TrajectoryMapFragment {
        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            // Hide fusion and wifi buttons in replay mode
            if (fusionButton != null) fusionButton.setVisibility(View.GONE);
            if (wifiButton != null) wifiButton.setVisibility(View.GONE);
        }

        @Override
        public void updateFusionLocation(@NonNull LatLng newLocation, float orientation) {
            // Disable fusion plotting in replay mode
        }

        @Override
        public void updateWifiLocation(@NonNull LatLng newLocation, float orientation) {
            // Disable WiFi plotting in replay mode
        }
    }
}




