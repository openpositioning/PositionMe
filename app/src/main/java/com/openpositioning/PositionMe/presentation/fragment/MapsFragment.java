package com.openpositioning.PositionMe.presentation.fragment;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
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
import java.util.Set;

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
    private final Map<String, com.google.android.material.button.MaterialButton> floorButtons = new HashMap<>();
    private GroundOverlay currentGroundOverlay = null;
    private static final float FLOOR_IMAGE_TRANSPARENCY = 0.35f;

    // Manual overlay offsets tuned from Recording page so indoor floor images
    // align with API walls in the same way on Indoor map page.
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

    // UI components
    private View floorSelectorContainer;
    private LinearLayout floorButtonLayout;
    private Button backToOutlineButton;

    // Venue selection button
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
        }
    };

    /**
     * Draw building outlines on the map
     */
    private void drawIndependentBuildingOutlines() {
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

    private void loadBuildingData(String buildingName) {
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

        Toast.makeText(getContext(), "Loading indoor map for " + buildingName, Toast.LENGTH_SHORT).show();
        final BuildingLocation buildingToLoad = selectedBuilding;

        NetworkUtils.fetchFloorPlan(buildingToLoad.center.latitude, buildingToLoad.center.longitude,
                new NetworkUtils.Callback() {
                    @Override
                    public void onSuccess(NetworkUtils.BuildingData data) {
                        if (data.floors.isEmpty()) {
                            Toast.makeText(getContext(), "No indoor map available for " + buildingToLoad.name, Toast.LENGTH_SHORT).show();
                            Log.w(TAG, buildingToLoad.name + ": no floor data");
                            return;
                        }
                        allBuildingsData.put(buildingToLoad.name, data);
                        showIndoorMap(buildingToLoad.name);
                    }

                    @Override
                    public void onError(String error) {
                        Toast.makeText(getContext(), "Failed to load " + buildingToLoad.name, Toast.LENGTH_SHORT).show();
                        Log.e(TAG, buildingToLoad.name + " error: " + error);
                    }
                });
    }

    /**
     * Setup click listener for building polygons
     */
    private void setupBuildingClickListener() {
        googleMap.setOnPolygonClickListener(polygon -> {
            String buildingName = buildingPolygonMap.get(polygon);
            if (buildingName != null) {
                Log.d(TAG, "Building clicked: " + buildingName);
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
            loadBuildingData(buildingName);
            return;
        }

        // Show back button
        if (backToOutlineButton != null) {
            backToOutlineButton.setVisibility(View.VISIBLE);
        }

        // Show venue selection button
        if (selectVenueButton != null) {
            selectVenueButton.setVisibility(View.VISIBLE);
            selectVenueButton.setText("Select " + buildingName + " for Data Collection");
            selectVenueButton.setBackgroundTintList(
                    ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.md_theme_secondary)));
            selectVenueButton.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
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
     * Handles venue selection from the map screen.
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
            selectVenueButton.setText("Right " + currentSelectedBuilding + " Selected");
            selectVenueButton.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
                    )
            );
        }

        Toast.makeText(getContext(),
                "Venue selected: " + currentSelectedBuilding + " - " + floor,
                Toast.LENGTH_LONG).show();

        Log.d(TAG, "Venue selected: " + currentSelectedBuilding + " (ID: " + venueId + ", Floor: " + floor + ")");
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

        // Hide venue selection button
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

        // Draw configured floor image first so API walls/areas align on top.
        addIndoorFloorImageOverlay(floorName, floorData, buildingData);

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

        Log.d(TAG, "Floor drawn: " + floorName +
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

        floorButtons.clear();
    }

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
            currentGroundOverlay = googleMap.addGroundOverlay(new GroundOverlayOptions()
                    .image(BitmapDescriptorFactory.fromResource(drawableRes))
                    .position(fixedConfig.center, fixedConfig.widthM)
                    .bearing(fixedConfig.bearingDeg)
                    .transparency(FLOOR_IMAGE_TRANSPARENCY)
                    .zIndex(100f));
            return;
        }

        LatLngBounds bounds = buildApiAlignedBounds(floorData, currentSelectedBuilding);
        if (bounds == null) {
            return;
        }

        currentGroundOverlay = googleMap.addGroundOverlay(new GroundOverlayOptions()
                .image(BitmapDescriptorFactory.fromResource(drawableRes))
                .positionFromBounds(bounds)
                .transparency(FLOOR_IMAGE_TRANSPARENCY)
                .zIndex(100f));
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
            return withOffset(tuned, NUCLEUS_OVERLAY_LAT_OFFSET, NUCLEUS_OVERLAY_LNG_OFFSET);
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
            return withOffset(tuned, LIBRARY_OVERLAY_LAT_OFFSET, LIBRARY_OVERLAY_LNG_OFFSET);
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

        minLat = updateMinLatLngFromPolylines(floorData.walls, minLat, true);
        maxLat = updateMaxLatLngFromPolylines(floorData.walls, maxLat, true);
        minLng = updateMinLatLngFromPolylines(floorData.walls, minLng, false);
        maxLng = updateMaxLatLngFromPolylines(floorData.walls, maxLng, false);

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

            double radiusM = building.radiusMeters * 2.2;
            double dLat = radiusM / 111000.0;
            double dLng = radiusM / (111000.0 * Math.cos(Math.toRadians(building.center.latitude)));

            LatLng southWest = new LatLng(building.center.latitude - dLat, building.center.longitude - dLng);
            LatLng northEast = new LatLng(building.center.latitude + dLat, building.center.longitude + dLng);
            return new LatLngBounds(southWest, northEast);
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
        floorButtons.clear();

        List<String> sortedFloors = sortFloorNames(new ArrayList<>(floors.keySet()));

        for (String floorName : sortedFloors) {
            com.google.android.material.button.MaterialButton btn =
                    new com.google.android.material.button.MaterialButton(getContext());

            btn.setText(floorName);
            btn.setTextSize(18);
            btn.setTypeface(btn.getTypeface(), android.graphics.Typeface.BOLD);
            btn.setMinWidth(84);
            btn.setMinimumWidth(84);
            btn.setMinimumHeight(46);
            btn.setInsetTop(0);
            btn.setInsetBottom(0);
            btn.setCornerRadius(12);
            btn.setElevation(0f);
            btn.setStrokeWidth(1);
            btn.setLetterSpacing(0.03f);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(6, 4, 6, 4);
            btn.setLayoutParams(params);

            styleFloorButton(btn, floorName.equals(currentSelectedFloor));

            btn.setOnClickListener(v -> {
                NetworkUtils.BuildingData data = allBuildingsData.get(currentSelectedBuilding);
                if (data != null) {
                    drawFloor(floorName, data);
                    updateFloorButtonStyles(floorName);

                    // Keep selected floor synchronized across fragments.
                    if (isVenueSelected) {
                        VenueManager.getInstance(requireContext())
                                .setSelectedVenue(currentSelectedBuilding, selectedVenueId, floorName);
                    }
                }
            });

            floorButtonLayout.addView(btn);
            floorButtons.put(floorName, btn);
        }

        updateFloorButtonStyles(currentSelectedFloor);

        Log.d(TAG, "Floor selector: " + sortedFloors.size() + " floors");
    }

    private void updateFloorButtonStyles(String selectedFloor) {
        for (Map.Entry<String, com.google.android.material.button.MaterialButton> entry : floorButtons.entrySet()) {
            styleFloorButton(entry.getValue(), entry.getKey().equals(selectedFloor));
        }
    }

    private void styleFloorButton(com.google.android.material.button.MaterialButton button, boolean selected) {
        if (getContext() == null || button == null) return;

        if (selected) {
            button.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(getContext(), R.color.md_theme_primary)));
            button.setTextColor(ContextCompat.getColor(getContext(), R.color.md_theme_onPrimary));
            button.setStrokeColor(ColorStateList.valueOf(
                    ContextCompat.getColor(getContext(), R.color.md_theme_primary)));
        } else {
            button.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(getContext(), R.color.md_theme_surfaceContainerHighest)));
            button.setTextColor(ContextCompat.getColor(getContext(), R.color.md_theme_onSurface));
            button.setStrokeColor(ColorStateList.valueOf(
                    ContextCompat.getColor(getContext(), R.color.md_theme_outlineVariant)));
        }
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

        // Setup venue selection button
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
    }
}
