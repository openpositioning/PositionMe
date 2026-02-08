package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import java.util.ArrayList;
import java.util.List;

public class TrajectoryMapFragment extends Fragment {
    private GoogleMap gMap;
    private LatLng currentLocation;
    private Marker orientationMarker;
    private Marker gnssMarker;
    private Polyline polyline;
    private Polyline gnssPolyline;
    private LatLng lastGnssLocation;
    private LatLng pendingCameraPosition;
    private boolean hasPendingCameraMove;
    private boolean isRed = true;
    private boolean isGnssOn;
    private boolean venueSelectionEnabled;

    private SensorFusion sensorFusion;
    private IndoorMapManager indoorMapManager;

    private Spinner switchMapSpinner;
    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;
    private FloatingActionButton floorUpButton;
    private FloatingActionButton floorDownButton;
    private Button switchColorButton;
    private TextView selectedVenueText;

    public TrajectoryMapFragment() {
    }

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
        sensorFusion = SensorFusion.getInstance();
        venueSelectionEnabled = requireActivity() instanceof RecordingActivity;

        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch = view.findViewById(R.id.gnssSwitch);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        floorUpButton = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);
        selectedVenueText = view.findViewById(R.id.selectedVenueText);

        setFloorControlsVisibility(View.GONE);
        if (!venueSelectionEnabled && selectedVenueText != null) {
            selectedVenueText.setVisibility(View.GONE);
        }

        SupportMapFragment mapFragment =
                (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.trajectoryMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(new OnMapReadyCallback() {
                @Override
                public void onMapReady(@NonNull GoogleMap googleMap) {
                    gMap = googleMap;
                    initMapSettings(gMap);
                    if (hasPendingCameraMove && pendingCameraPosition != null) {
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
                        hasPendingCameraMove = false;
                        pendingCameraPosition = null;
                    }
                    if (venueSelectionEnabled) {
                        gMap.setOnPolygonClickListener(polygon -> {
                            if (indoorMapManager != null && indoorMapManager.onVenuePolygonClicked(polygon)) {
                                updateVenueLabel();
                                setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
                            }
                        });
                    }
                }
            });
        }

        initMapTypeSpinner();

        gnssSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isGnssOn = isChecked;
            if (!isChecked) {
                clearGNSS();
            }
        });

        switchColorButton.setOnClickListener(v -> {
            if (polyline == null) {
                return;
            }
            if (isRed) {
                switchColorButton.setBackgroundColor(Color.BLACK);
                polyline.setColor(Color.BLACK);
            } else {
                switchColorButton.setBackgroundColor(Color.RED);
                polyline.setColor(Color.RED);
            }
            isRed = !isRed;
        });

        autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (!isChecked || indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
                return;
            }
            float floorHeight = indoorMapManager.getFloorHeight();
            if (floorHeight <= 0f) {
                return;
            }
            int estimatedFloor = (int) (sensorFusion.getElevation() / floorHeight);
            indoorMapManager.setCurrentFloor(estimatedFloor, true);
            updateVenueLabel();
        });

        floorUpButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
                updateVenueLabel();
            }
        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
                updateVenueLabel();
            }
        });
    }

    private void initMapSettings(@NonNull GoogleMap map) {
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        indoorMapManager = new IndoorMapManager(requireContext(), map);
        indoorMapManager.setVenueSelectionListener((venueId, venueName) -> {
            if (venueSelectionEnabled) {
                sensorFusion.setCollectionVenue(venueId);
            }
            updateVenueLabel();
            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
        });

        polyline = map.addPolyline(new PolylineOptions().color(Color.RED).width(5f).add());
        gnssPolyline = map.addPolyline(new PolylineOptions().color(Color.BLUE).width(5f).add());
        updateVenueLabel();
    }

    private void initMapTypeSpinner() {
        if (switchMapSpinner == null) {
            return;
        }
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
                if (gMap == null) {
                    return;
                }
                if (position == 0) {
                    gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                } else if (position == 1) {
                    gMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                } else {
                    gMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    public void updateUserLocation(@NonNull LatLng newLocation, float orientation) {
        if (gMap == null) {
            return;
        }

        LatLng oldLocation = currentLocation;
        currentLocation = newLocation;

        if (orientationMarker == null) {
            orientationMarker = gMap.addMarker(new MarkerOptions()
                    .position(newLocation)
                    .flat(true)
                    .title("Current Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(), R.drawable.ic_baseline_navigation_24)))
            );
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
        } else {
            orientationMarker.setPosition(newLocation);
            orientationMarker.setRotation(orientation);
            gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
        }

        if (oldLocation != null && !oldLocation.equals(newLocation) && polyline != null) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            points.add(newLocation);
            polyline.setPoints(points);
        }

        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(newLocation);
            if (venueSelectionEnabled) {
                indoorMapManager.refreshNearbyVenues(newLocation, sensorFusion.getWifiList());
            }
            setFloorControlsVisibility(indoorMapManager.getIsIndoorMapSet() ? View.VISIBLE : View.GONE);
            updateVenueLabel();
        }
    }

    private void updateVenueLabel() {
        if (selectedVenueText == null || !venueSelectionEnabled) {
            return;
        }
        if (indoorMapManager == null || TextUtils.isEmpty(indoorMapManager.getSelectedVenueName())) {
            selectedVenueText.setText(getString(R.string.venue_not_selected));
            return;
        }
        String venueName = indoorMapManager.getSelectedVenueName();
        int floorCount = indoorMapManager.getFloorCount();
        if (floorCount <= 0) {
            selectedVenueText.setText(getString(R.string.venue_selected_no_floor, venueName));
            return;
        }
        int floorNumber = indoorMapManager.getCurrentFloor() + 1;
        selectedVenueText.setText(getString(R.string.venue_selected_format, venueName, floorNumber, floorCount));
    }

    public void setInitialCameraPosition(@NonNull LatLng startLocation) {
        if (gMap != null) {
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLocation, 19f));
        } else {
            pendingCameraPosition = startLocation;
            hasPendingCameraMove = true;
        }
    }

    public LatLng getCurrentLocation() {
        return currentLocation;
    }

    public void updateGNSS(@NonNull LatLng gnssLocation) {
        if (gMap == null || !isGnssOn) {
            return;
        }

        if (gnssMarker == null) {
            gnssMarker = gMap.addMarker(new MarkerOptions()
                    .position(gnssLocation)
                    .title("GNSS Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
            lastGnssLocation = gnssLocation;
            return;
        }

        gnssMarker.setPosition(gnssLocation);
        if (lastGnssLocation != null && !lastGnssLocation.equals(gnssLocation) && gnssPolyline != null) {
            List<LatLng> points = new ArrayList<>(gnssPolyline.getPoints());
            points.add(gnssLocation);
            gnssPolyline.setPoints(points);
        }
        lastGnssLocation = gnssLocation;
    }

    public void clearGNSS() {
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
        lastGnssLocation = null;
    }

    public boolean isGnssEnabled() {
        return isGnssOn;
    }

    private void setFloorControlsVisibility(int visibility) {
        int finalVisibility = venueSelectionEnabled ? visibility : View.GONE;
        floorUpButton.setVisibility(finalVisibility);
        floorDownButton.setVisibility(finalVisibility);
        autoFloorSwitch.setVisibility(finalVisibility);
    }

    public void clearMapAndReset() {
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
        clearGNSS();
        currentLocation = null;

        if (gMap != null) {
            polyline = gMap.addPolyline(new PolylineOptions().color(Color.RED).width(5f).add());
            gnssPolyline = gMap.addPolyline(new PolylineOptions().color(Color.BLUE).width(5f).add());
        }
    }
}
