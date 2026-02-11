package com.openpositioning.PositionMe.presentation.fragment;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MapsFragment - Indoor mapping with venue selection
 *
 * Features:
 * - Display building outlines on map
 * - Show indoor floor plans when building is selected
 * - Allow user to select venue for data collection
 * - Persist selected venue using VenueManager
 *
 * @author Your Team
 */
public class MapsFragment extends Fragment {

    private static final String TAG = "MapsFragment";

    // Building location data structure
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

    // Target buildings with GPS coordinates
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

    // UI and data members
    private GoogleMap googleMap;
    private FusedLocationProviderClient fusedLocationClient;
    private final Map<String, NetworkUtils.BuildingData> allBuildingsData = new HashMap<>();
    private final Map<Polygon, String> buildingPolygonMap = new HashMap<>();
    private String currentSelectedBuilding = null;
    private String currentSelectedFloor = null;
    private final List<Polyline> currentWallLines = new ArrayList<>();
    private final List<Polygon> currentAreaPolygons = new ArrayList<>();
    private final List<Marker> currentPoiMarkers = new ArrayList<>();
    private GroundOverlay currentGroundOverlay = null;

    // UI components
    private View floorSelectorContainer;
    private LinearLayout floorButtonLayout;
    private Button backToOutlineButton;

    // 🆕 NEW: Venue selection button
    private com.google.android.material.button.MaterialButton selectVenueButton;

    // Venue selection state
    private String selectedVenueId = null;
    private boolean isVenueSelected = false;

    private final OnMapReadyCallback callback = new OnMapReadyCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onMapReady(@NonNull GoogleMap map) {
            googleMap = map;
            googleMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
            googleMap.getUiSettings().setZoomControlsEnabled(true);

            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                googleMap.setMyLocationEnabled(true);
            }

            setupBuildingClickListener();

            // Move camera to campus center
            LatLng campusCenter = new LatLng(55.9234, -3.1761);
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(campusCenter, 17f));

            drawIndependentBuildingOutlines();
            loadAllBuildings();
        }
    };

    /**
     * Draw building outlines on the map
     */
    private void drawIndependentBuildingOutlines() {
        Log.d(TAG, "Drawing building outlines with GPS coordinates");

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
                    .snippet("Click outline to view indoor map")
                    .icon(BitmapDescriptorFactory.defaultMarker(building.markerHue))
                    .zIndex(60));

            Log.d(TAG, "✅ " + building.name + " at " + building.center);
        }

        Toast.makeText(getContext(), "Building outlines loaded", Toast.LENGTH_SHORT).show();
    }

    /**
     * Create circular polygon points
     */
    private List<LatLng> createCircle(LatLng center, double radiusMeters) {
        List<LatLng> points = new ArrayList<>();
        int numPoints = 36;
        double earthRadius = 6371000; // meters

        for (int i = 0; i < numPoints; i++) {
            double angle = 2.0 * Math.PI * i / numPoints;
            double dx = radiusMeters * Math.cos(angle);
            double dy = radiusMeters * Math.sin(angle);

            double dLat = dy / earthRadius;
            double dLon = dx / (earthRadius * Math.cos(Math.toRadians(center.latitude)));

            double newLat = center.latitude + Math.toDegrees(dLat);
            double newLon = center.longitude + Math.toDegrees(dLon);
            points.add(new LatLng(newLat, newLon));
        }

        return points;
    }

    /**
     * Load building data from API
     */
    private void loadAllBuildings() {
        Log.d(TAG, "Loading building data from API...");

        for (BuildingLocation building : TARGET_BUILDINGS) {
            NetworkUtils.fetchFloorPlan(building.center.latitude, building.center.longitude,
                    new NetworkUtils.Callback() {
                        @Override
                        public void onSuccess(NetworkUtils.BuildingData data) {
                            if (data.floors.isEmpty()) {
                                Log.w(TAG, "❌ " + building.name + ": No floor data");
                            } else {
                                allBuildingsData.put(building.name, data);
                                Log.d(TAG, "✅ " + building.name + ": " + data.floors.size() + " floors loaded");
                            }
                        }

                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "❌ " + building.name + " error: " + error);
                        }
                    });
        }
    }

    /**
     * Setup click listener for building polygons
     */
    private void setupBuildingClickListener() {
        googleMap.setOnPolygonClickListener(polygon -> {
            String buildingName = buildingPolygonMap.get(polygon);
            if (buildingName != null) {
                Log.d(TAG, "🏢 Building clicked: " + buildingName);
                showIndoorMap(buildingName);
            }
        });
    }

    /**
     * Display indoor map for selected building
     */
    private void showIndoorMap(String buildingName) {
        currentSelectedBuilding = buildingName;
        NetworkUtils.BuildingData data = allBuildingsData.get(buildingName);

        if (data == null || data.floors.isEmpty()) {
            Toast.makeText(getContext(), "No indoor map available for " + buildingName, Toast.LENGTH_SHORT).show();
            return;
        }

        // Show back button
        if (backToOutlineButton != null) {
            backToOutlineButton.setVisibility(View.VISIBLE);
        }

        // 🆕 Show venue selection button
        if (selectVenueButton != null) {
            selectVenueButton.setVisibility(View.VISIBLE);
            selectVenueButton.setText("Select " + buildingName + " for Data Collection");
        }

        // Setup floor selector
        setupFloorSelector(data.floors, buildingName);

        // Display first floor
        List<String> sortedFloors = sortFloorNames(new ArrayList<>(data.floors.keySet()));
        if (!sortedFloors.isEmpty()) {
            String firstFloor = sortedFloors.get(0);
            currentSelectedFloor = firstFloor;
            drawFloor(firstFloor, data);
        }

        Toast.makeText(getContext(), "Indoor map loaded for " + buildingName, Toast.LENGTH_SHORT).show();
    }

    /**
     * 🆕 NEW: Handle venue selection
     */
    private void handleVenueSelection() {
        if (currentSelectedBuilding == null) {
            Toast.makeText(getContext(), "Please select a building first", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save venue selection using VenueManager
        VenueManager venueManager = VenueManager.getInstance(requireContext());

        // Generate venue ID from building name
        String venueId = generateVenueId(currentSelectedBuilding);

        // Get current floor or use default
        String floor = currentSelectedFloor != null ? currentSelectedFloor : "Ground Floor";

        // Save to VenueManager
        venueManager.setSelectedVenue(currentSelectedBuilding, venueId, floor);

        isVenueSelected = true;
        selectedVenueId = venueId;

        // Update button appearance
        if (selectVenueButton != null) {
            selectVenueButton.setText("✓ " + currentSelectedBuilding + " Selected");
            selectVenueButton.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
                    )
            );
        }

        Toast.makeText(getContext(),
                "Venue selected: " + currentSelectedBuilding + " - " + floor,
                Toast.LENGTH_LONG).show();

        Log.d(TAG, "✅ Venue selected: " + currentSelectedBuilding + " (ID: " + venueId + ", Floor: " + floor + ")");
    }

    /**
     * Generate venue ID from building name
     */
    private String generateVenueId(String buildingName) {
        // Create a simple ID from the building name
        return buildingName.toLowerCase()
                .replaceAll("[^a-z0-9]", "_")
                .replaceAll("_+", "_");
    }

    /**
     * Return to building outline view
     */
    private void returnToBuildingOutline() {
        clearIndoorLayers();
        currentSelectedBuilding = null;
        currentSelectedFloor = null;

        if (backToOutlineButton != null) {
            backToOutlineButton.setVisibility(View.GONE);
        }

        // 🆕 Hide venue selection button
        if (selectVenueButton != null) {
            selectVenueButton.setVisibility(View.GONE);
        }

        if (floorSelectorContainer != null) {
            floorSelectorContainer.setVisibility(View.GONE);
        }

        LatLng campusCenter = new LatLng(55.9234, -3.1761);
        googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(campusCenter, 17f));

        Log.d(TAG, "Returned to building outline view");
    }

    /**
     * Draw floor plan (walls, areas, POIs)
     */
    private void drawFloor(String floorName, NetworkUtils.BuildingData buildingData) {
        currentSelectedFloor = floorName;
        clearIndoorLayers();

        NetworkUtils.FloorData floorData = buildingData.floors.get(floorName);
        if (floorData == null) {
            Log.w(TAG, "Floor data not found: " + floorName);
            return;
        }

        // Draw floor image if available
        drawFloorImage(floorName, currentSelectedBuilding);

        // Draw walls (black lines)
        for (List<LatLng> wall : floorData.walls) {
            if (wall.size() < 2) continue;
            Polyline line = googleMap.addPolyline(new PolylineOptions()
                    .addAll(wall)
                    .color(Color.BLACK)
                    .width(6f)
                    .zIndex(110));
            currentWallLines.add(line);
        }

        // Draw areas (filled polygons)
        for (List<LatLng> area : floorData.areas) {
            if (area.size() < 3) continue;
            Polygon poly = googleMap.addPolygon(new PolygonOptions()
                    .addAll(area)
                    .strokeColor(Color.DKGRAY)
                    .strokeWidth(2f)
                    .fillColor(Color.argb(100, 200, 200, 200))
                    .zIndex(105));
            currentAreaPolygons.add(poly);
        }

        // Draw POI markers
        for (NetworkUtils.Poi poi : floorData.pois) {
            if (poi.position != null) {
                Marker marker = googleMap.addMarker(new MarkerOptions()
                        .position(poi.position)
                        .title(poi.label.isEmpty() ? poi.type : poi.label)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                        .zIndex(120));
                currentPoiMarkers.add(marker);
            }
        }

        // Adjust camera to show floor
        adjustCameraToFloor(floorData);

        Log.d(TAG, "✅ Floor drawn: " + floorName +
                " (" + floorData.walls.size() + " walls, " +
                floorData.areas.size() + " areas, " +
                floorData.pois.size() + " POIs)");
    }

    /**
     * Adjust camera to fit floor data
     */
    private void adjustCameraToFloor(NetworkUtils.FloorData floorData) {
        List<LatLng> allPoints = new ArrayList<>();

        for (List<LatLng> wall : floorData.walls) {
            allPoints.addAll(wall);
        }
        for (List<LatLng> area : floorData.areas) {
            allPoints.addAll(area);
        }

        if (!allPoints.isEmpty()) {
            try {
                LatLngBounds.Builder builder = new LatLngBounds.Builder();
                for (LatLng point : allPoints) {
                    builder.include(point);
                }
                LatLngBounds bounds = builder.build();
                googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100));
            } catch (Exception e) {
                Log.e(TAG, "Camera adjustment failed", e);
            }
        }
    }

    /**
     * Clear all indoor map layers
     */
    private void clearIndoorLayers() {
        for (Polyline line : currentWallLines) line.remove();
        currentWallLines.clear();

        for (Polygon poly : currentAreaPolygons) poly.remove();
        currentAreaPolygons.clear();

        for (Marker marker : currentPoiMarkers) marker.remove();
        currentPoiMarkers.clear();

        if (currentGroundOverlay != null) {
            currentGroundOverlay.remove();
            currentGroundOverlay = null;
        }
    }

    /**
     * Draw floor image overlay (if available)
     */
    private void drawFloorImage(String floorName, String buildingName) {
        int drawableId = getFloorImageResource(floorName, buildingName);
        if (drawableId == -1) return;

        FloorImageConfig config = getFloorImageConfig(floorName, buildingName);
        if (config == null) return;

        try {
            GroundOverlayOptions options = new GroundOverlayOptions()
                    .image(BitmapDescriptorFactory.fromResource(drawableId))
                    .position(config.center, config.widthInMeters)
                    .bearing(config.bearing)
                    .transparency(config.transparency)
                    .zIndex(100);

            currentGroundOverlay = googleMap.addGroundOverlay(options);
        } catch (Exception e) {
            Log.e(TAG, "Floor image loading failed", e);
        }
    }

    /**
     * Get floor image resource ID
     */
    private int getFloorImageResource(String floorName, String buildingName) {
        String cleanName = floorName.replace("[", "").replace("]", "").toLowerCase().trim();

        if (buildingName.equals("The Nucleus Building")) {
            if (cleanName.equals("0") || cleanName.equals("ground") ||
                    cleanName.equals("ground_floor") || cleanName.equals("gf")) {
                return R.drawable.nucleusg;
            } else if (cleanName.equals("b1") || cleanName.equals("-1") ||
                    cleanName.equals("basement") || cleanName.equals("basement_1")) {
                return R.drawable.nucleuslg;
            }
        }

        return -1;
    }

    /**
     * Floor image configuration
     */
    private static class FloorImageConfig {
        LatLng center;
        float widthInMeters;
        float bearing;
        float transparency;

        FloorImageConfig(LatLng center, float widthInMeters, float bearing, float transparency) {
            this.center = center;
            this.widthInMeters = widthInMeters;
            this.bearing = bearing;
            this.transparency = transparency;
        }
    }

    /**
     * Get floor image configuration
     */
    private FloorImageConfig getFloorImageConfig(String floorName, String buildingName) {
        String cleanName = floorName.replace("[", "").replace("]", "").toLowerCase().trim();

        if (buildingName.equals("The Nucleus Building")) {
            if (cleanName.equals("0") || cleanName.equals("ground") ||
                    cleanName.equals("ground_floor") || cleanName.equals("gf")) {
                return new FloorImageConfig(
                        new LatLng(55.923041, -3.174234), 48.5f, 360f, 0.35f);
            } else if (cleanName.equals("b1") || cleanName.equals("-1") ||
                    cleanName.equals("basement") || cleanName.equals("basement_1")) {
                return new FloorImageConfig(
                        new LatLng(55.92303, -3.17420), 50f, 360f, 0.35f);
            }
        }

        return null;
    }

    /**
     * Setup floor selector UI
     */
    private void setupFloorSelector(Map<String, NetworkUtils.FloorData> floors, String buildingName) {
        if (floorButtonLayout == null || getContext() == null) return;

        floorSelectorContainer.setVisibility(View.VISIBLE);
        floorButtonLayout.removeAllViews();

        List<String> sortedFloors = sortFloorNames(new ArrayList<>(floors.keySet()));

        for (String floorName : sortedFloors) {
            com.google.android.material.button.MaterialButton btn =
                    new com.google.android.material.button.MaterialButton(getContext());

            btn.setText(floorName);
            btn.setTextSize(16);
            btn.setMinWidth(100);
            btn.setMinimumWidth(100);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(8, 4, 8, 4);
            btn.setLayoutParams(params);

            btn.setCornerRadius(8);
            btn.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(getContext(), R.color.md_theme_primary)
                    )
            );
            btn.setTextColor(
                    ContextCompat.getColor(getContext(), R.color.md_theme_onPrimary)
            );

            btn.setOnClickListener(v -> {
                NetworkUtils.BuildingData data = allBuildingsData.get(currentSelectedBuilding);
                if (data != null) {
                    drawFloor(floorName, data);

                    // 🆕 Update venue manager with new floor
                    if (isVenueSelected) {
                        VenueManager.getInstance(requireContext())
                                .setSelectedVenue(currentSelectedBuilding, selectedVenueId, floorName);
                    }
                }
            });

            floorButtonLayout.addView(btn);
        }

        Log.d(TAG, "✅ Floor selector: " + sortedFloors.size() + " floors");
    }

    /**
     * Sort floor names numerically
     */
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

    /**
     * Extract floor number from floor name
     */
    private int extractFloorNumber(String floorName) {
        String clean = floorName.toLowerCase().replaceAll("[^0-9-]", "");
        if (clean.isEmpty()) return 0;
        return Integer.parseInt(clean);
    }

    /**
     * Get selected venue ID (for data submission)
     */
    public String getSelectedVenueId() {
        return selectedVenueId;
    }

    /**
     * Check if venue is selected
     */
    public boolean isVenueSelected() {
        return isVenueSelected;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_maps, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());
        floorSelectorContainer = view.findViewById(R.id.floorSelectorContainer);
        floorButtonLayout = view.findViewById(R.id.floorButtonLayout);

        backToOutlineButton = view.findViewById(R.id.backToOutlineButton);
        if (backToOutlineButton != null) {
            backToOutlineButton.setVisibility(View.GONE);
            backToOutlineButton.setOnClickListener(v -> returnToBuildingOutline());
        }

        // 🆕 Setup venue selection button
        selectVenueButton = view.findViewById(R.id.selectVenueButton);
        if (selectVenueButton != null) {
            selectVenueButton.setVisibility(View.GONE);
            selectVenueButton.setOnClickListener(v -> handleVenueSelection());
        }

        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(callback);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        googleMap = null;
        fusedLocationClient = null;
        allBuildingsData.clear();
        buildingPolygonMap.clear();
        currentSelectedBuilding = null;
        currentSelectedFloor = null;
        // Note: We don't clear selectedVenueId here as it's managed by VenueManager
    }
}