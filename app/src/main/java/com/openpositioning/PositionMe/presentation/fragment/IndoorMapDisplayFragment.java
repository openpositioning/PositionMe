package com.openpositioning.PositionMe.presentation.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.Building;
import com.openpositioning.PositionMe.data.remote.FloorPlan;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class IndoorMapDisplayFragment extends Fragment implements OnMapReadyCallback {

    private static final String TAG = "IndoorMapFragment";

    // UI Components
    private GoogleMap mMap;
    private TextView floorText;
    private Button btnUp, btnDown;

    // Logic Components
    private ServerCommunications serverCommunications;
    private Building selectedBuilding;
    private int currentFloorIndex = 0;
    private List<Polyline> currentFloorLines = new ArrayList<>();
    private Set<String> loadedBuildingNames = new HashSet<>();

    // Locations
    private static final LatLng LOC_NUCLEUS = new LatLng(55.9232, -3.1742);
    private static final LatLng LOC_MURCHISON = new LatLng(55.924131, -3.179167);
    private static final LatLng CAMERA_CENTER = new LatLng(55.9236, -3.1767);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_indoor_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        floorText = view.findViewById(R.id.floorText);
        btnUp = view.findViewById(R.id.btnUp);
        btnDown = view.findViewById(R.id.btnDown);
        updateUIState(false);

        serverCommunications = new ServerCommunications(requireContext());

        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.mapContainer);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        btnUp.setOnClickListener(v -> changeFloor(1));
        btnDown.setOnClickListener(v -> changeFloor(-1));
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        mMap.getUiSettings().setZoomControlsEnabled(true);
        mMap.getUiSettings().setCompassEnabled(true);
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(CAMERA_CENTER, 16.5f));

        requestAllBuildings();

        mMap.setOnPolygonClickListener(polygon -> {
            Building building = (Building) polygon.getTag();
            if (building != null) {
                onBuildingSelected(building);
            }
        });

        // Long press to manually scan a location
        mMap.setOnMapLongClickListener(latLng -> {
            Toast.makeText(getContext(), "Scanning location...", Toast.LENGTH_SHORT).show();
            serverCommunications.getNearbyBuildings(latLng.latitude, latLng.longitude, new ServerCommunications.BuildingCallback() {
                @Override
                public void onBuildingsReceived(List<Building> buildings) {
                    if (!buildings.isEmpty()) {
                        addBuildingsToMap(buildings);
                        Toast.makeText(getContext(), "Found: " + buildings.get(0).getName(), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getContext(), "No buildings found here", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onError(String message) {
                    Log.e(TAG, "Manual scan error: " + message);
                }
            });
        });
    }

    private void requestAllBuildings() {
        loadedBuildingNames.clear();
        mMap.clear();

        // Step 1: Request Nucleus
        serverCommunications.getNearbyBuildings(LOC_NUCLEUS.latitude, LOC_NUCLEUS.longitude, new ServerCommunications.BuildingCallback() {
            @Override
            public void onBuildingsReceived(List<Building> buildings) {
                addBuildingsToMap(buildings);
                requestMurchison();
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "Nucleus request failed: " + message);
                requestMurchison();
            }
        });
    }

    private void requestMurchison() {
        // Step 2: Request Murchison
        serverCommunications.getNearbyBuildings(LOC_MURCHISON.latitude, LOC_MURCHISON.longitude, new ServerCommunications.BuildingCallback() {
            @Override
            public void onBuildingsReceived(List<Building> buildings) {
                addBuildingsToMap(buildings);
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "Murchison request failed: " + message);
            }
        });
    }

    private void addBuildingsToMap(List<Building> newBuildings) {
        if (mMap == null) return;

        for (Building b : newBuildings) {
            String name = (b.getName() != null) ? b.getName() : "Unknown";
            if (loadedBuildingNames.contains(name)) continue;

            loadedBuildingNames.add(name);
            drawSingleBuildingOutline(b);
        }
    }

    private void drawSingleBuildingOutline(Building building) {
        if (building.getOutline() == null || building.getOutline().isEmpty()) return;

        List<LatLng> points = new ArrayList<>();
        for (List<Double> point : building.getOutline()) {
            if (point.size() >= 2) points.add(new LatLng(point.get(0), point.get(1)));
        }

        int strokeColor = Color.RED;
        int fillColor = Color.argb(50, 255, 0, 0);

        String name = building.getName().toLowerCase();
        if (name.contains("nucleus")) {
            strokeColor = Color.rgb(255, 191, 0); // Amber
            fillColor = Color.argb(50, 255, 191, 0);
        } else if (name.contains("fleeming") || name.contains("jenkin")) {
            strokeColor = Color.BLUE;
            fillColor = Color.argb(50, 0, 0, 255);
        }

        Polygon polygon = mMap.addPolygon(new PolygonOptions()
                .addAll(points)
                .strokeColor(strokeColor)
                .fillColor(fillColor)
                .strokeWidth(5)
                .clickable(true));
        polygon.setTag(building);
    }

    private void onBuildingSelected(Building building) {
        this.selectedBuilding = building;

        // Save selected venue (campaign) to SharedPreferences
        String venueName = building.getName();
        String campaignName = venueName.toLowerCase().replace(" ", "_");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        prefs.edit().putString("current_campaign", campaignName).apply();

        Toast.makeText(getContext(), "Venue set to: " + venueName, Toast.LENGTH_SHORT).show();

        // Select default floor (G or 0)
        this.currentFloorIndex = 0;
        for (int i = 0; i < building.getFloors().size(); i++) {
            String code = building.getFloors().get(i).getFloorCode();
            if (code.equalsIgnoreCase("G") || code.equals("0")) {
                this.currentFloorIndex = i;
                break;
            }
        }

        updateUIState(true);
        if (!building.getFloors().isEmpty()) {
            drawCurrentFloorWalls();
        }
    }

    private void changeFloor(int delta) {
        if (selectedBuilding == null || selectedBuilding.getFloors().isEmpty()) return;

        int newIndex = currentFloorIndex + delta;
        if (newIndex < 0) newIndex = 0;
        if (newIndex >= selectedBuilding.getFloors().size()) newIndex = selectedBuilding.getFloors().size() - 1;

        if (newIndex != currentFloorIndex) {
            currentFloorIndex = newIndex;
            drawCurrentFloorWalls();
        }
    }

    private void drawCurrentFloorWalls() {
        // Clear previous lines
        for (Polyline line : currentFloorLines) {
            line.remove();
        }
        currentFloorLines.clear();

        FloorPlan floor = selectedBuilding.getFloors().get(currentFloorIndex);
        floorText.setText("Floor: " + floor.getFloorCode());

        if (floor.getWalls() != null) {
            for (List<List<Double>> wallPath : floor.getWalls()) {
                List<LatLng> points = new ArrayList<>();
                for (List<Double> point : wallPath) {
                    points.add(new LatLng(point.get(0), point.get(1)));
                }

                Polyline line = mMap.addPolyline(new PolylineOptions()
                        .addAll(points)
                        .color(Color.YELLOW)
                        .width(6)
                        .zIndex(100));

                currentFloorLines.add(line);
            }
        }
    }

    private void updateUIState(boolean isVisible) {
        int v = isVisible ? View.VISIBLE : View.GONE;
        if (floorText != null) floorText.setVisibility(v);
        if (btnUp != null) btnUp.setVisibility(v);
        if (btnDown != null) btnDown.setVisibility(v);
    }
}