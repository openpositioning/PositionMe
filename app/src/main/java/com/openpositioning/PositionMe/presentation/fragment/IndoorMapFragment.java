package com.openpositioning.PositionMe.presentation.fragment;

import android.util.Log;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.GroundOverlay;
import com.google.android.gms.maps.model.GroundOverlayOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.openpositioning.PositionMe.data.remote.IndoorMapAPI;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// IndoorMapFragment - Enhanced version with API support
// Handles:
// Fetching building and floor data from API
// Drawing building outlines on map
// Displaying indoor floor plans
// Managing venue/building selection
public class IndoorMapFragment {
    private static final String TAG = "IndoorMapFragment";
    
    private GoogleMap mMap;
    private GroundOverlay[] groundOverlays; // GroundOverlay used to store each layer
    private int currentFloor = 0; // Floor by default
    private int floorCount = 0;
    
    // Indoor map API
    private IndoorMapAPI indoorMapAPI;
    
    // Building data
    private Map<String, IndoorMapAPI.BuildingInfo> buildingMap = new HashMap<>();
    private Map<String, IndoorMapAPI.FloorPlan> floorMap = new HashMap<>();
    
    // Map overlays
    private List<Polygon> buildingPolygons = new ArrayList<>();
    private String currentBuildingId = null;
    private String currentVenueName = null;
    
    // Callback for venue selection
    private VenueSelectionCallback venueCallback;
    
    public interface VenueSelectionCallback {
        void onVenueSelected(String buildingId, String venueName);
    }
    
    public IndoorMapFragment() {
        this.indoorMapAPI = new IndoorMapAPI();
    }

    public IndoorMapFragment(GoogleMap map, int floorNumber) {
        this.mMap = map; // Pass in Google Maps
        this.floorCount = floorNumber;
        this.groundOverlays = new GroundOverlay[floorNumber]; // Set the number of floors
        this.indoorMapAPI = new IndoorMapAPI();
    }
    
    // Set the venue selection callback
    public void setVenueSelectionCallback(VenueSelectionCallback callback) {
        this.venueCallback = callback;
    }
    
    // Fetch buildings near user location
    public void fetchNearbyBuildings(double latitude, double longitude, double radiusMeters) {
        if (indoorMapAPI == null) return;
        
        indoorMapAPI.fetchNearbyBuildings(latitude, longitude, radiusMeters, 
            new IndoorMapAPI.BuildingsCallback() {
                @Override
                public void onSuccess(List<IndoorMapAPI.BuildingInfo> buildings) {
                    Log.d(TAG, "Received " + buildings.size() + " buildings");
                    displayBuildings(buildings);
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "Error fetching buildings: " + error);
                }
            });
    }
    
    // Display building outlines and enable selection
    private void displayBuildings(List<IndoorMapAPI.BuildingInfo> buildings) {
        if (mMap == null) return;
        
        // Clear previous polygons
        for (Polygon polygon : buildingPolygons) {
            polygon.remove();
        }
        buildingPolygons.clear();
        buildingMap.clear();
        
        // Add each building as a marker and polygon
        for (final IndoorMapAPI.BuildingInfo building : buildings) {
            buildingMap.put(building.buildingId, building);
            
            // Add building name as marker
            com.google.android.gms.maps.model.MarkerOptions markerOptions = 
                new com.google.android.gms.maps.model.MarkerOptions()
                    .position(new LatLng(building.latitude, building.longitude))
                    .title(building.buildingName)
                    .snippet("Taps to select");
            
            final com.google.android.gms.maps.model.Marker marker = mMap.addMarker(markerOptions);
            
            // Fetch and display building outline
            indoorMapAPI.fetchBuildingOutline(building.buildingId, 
                new IndoorMapAPI.OutlineCallback() {
                    @Override
                    public void onSuccess(double[][] coordinates) {
                        if (coordinates.length > 0) {
                            drawBuildingOutline(building.buildingId, building.buildingName, coordinates);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        Log.w(TAG, "Could not fetch outline for building " + building.buildingId);
                    }
                });
        }
        
        // Set up marker click listener to select venue
        mMap.setOnMarkerClickListener(marker -> {
            String buildingId = null;
            for (IndoorMapAPI.BuildingInfo b : buildingMap.values()) {
                if (new LatLng(b.latitude, b.longitude).equals(marker.getPosition())) {
                    buildingId = b.buildingId;
                    break;
                }
            }
            
            if (buildingId != null) {
                selectVenue(buildingId, marker.getTitle());
                // Fetch and display floors for this building
                fetchBuildingFloors(buildingId);
            }
            return true;
        });
    }
    
    // Draw building outline as polygon
    private void drawBuildingOutline(String buildingId, String buildingName, double[][] coordinates) {
        if (mMap == null || coordinates.length == 0) return;
        
        PolygonOptions polygonOptions = new PolygonOptions()
                .strokeColor(0xFF0000FF)  // Blue
                .fillColor(0x220000FF)    // Semi-transparent blue
                .strokeWidth(3.0f);
        
        for (double[] coord : coordinates) {
            polygonOptions.add(new LatLng(coord[0], coord[1]));
        }
        
        Polygon polygon = mMap.addPolygon(polygonOptions);
        buildingPolygons.add(polygon);
        
        // Store building ID in polygon tag for later reference
        polygon.setTag(buildingId);
    }
    
    // Fetch and display floor plans for selected building
    public void fetchBuildingFloors(String buildingId) {
        indoorMapAPI.fetchBuildingFloors(buildingId, 
            new IndoorMapAPI.FloorsCallback() {
                @Override
                public void onSuccess(List<IndoorMapAPI.FloorPlan> floors) {
                    Log.d(TAG, "Received " + floors.size() + " floors");
                    displayFloors(floors);
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "Error fetching floors: " + error);
                }
            });
    }
    
    // Display floor plans as ground overlays
    private void displayFloors(List<IndoorMapAPI.FloorPlan> floors) {
        if (mMap == null || floors.isEmpty()) return;
        
        // Clear existing overlays
        if (groundOverlays != null) {
            for (GroundOverlay overlay : groundOverlays) {
                if (overlay != null) {
                    overlay.remove();
                }
            }
        }
        
        // Create new array for floors
        groundOverlays = new GroundOverlay[floors.size()];
        floorCount = floors.size();
        
        for (int i = 0; i < floors.size(); i++) {
            IndoorMapAPI.FloorPlan floor = floors.get(i);
            LatLngBounds bounds = new LatLngBounds(
                new LatLng(floor.minLat, floor.minLon),
                new LatLng(floor.maxLat, floor.maxLon)
            );
            
            // Add floor from URL if available
            if (!floor.imageUrl.isEmpty()) {
                addFloorFromUrl(i, floor.imageUrl, bounds);
            }
            
            floorMap.put(floor.floorId, floor);
            Log.d(TAG, "Added floor: " + floor.floorName);
        }
        
        // Show first floor by default
        currentFloor = 0;
        if (groundOverlays.length > 0 && groundOverlays[0] != null) {
            groundOverlays[0].setVisible(true);
        }
    }

    // Used to add floors from drawable resource
    public void addFloor(int floorIndex, int drawableResId, LatLngBounds bounds) {
        BitmapDescriptor image = BitmapDescriptorFactory.fromResource(drawableResId);
        GroundOverlayOptions groundOverlayOptions = new GroundOverlayOptions()
                .image(image)
                .positionFromBounds(bounds)
                .visible(floorIndex == currentFloor)
                .transparency(0.2f);

        if (groundOverlays != null && floorIndex < groundOverlays.length) {
            groundOverlays[floorIndex] = mMap.addGroundOverlay(groundOverlayOptions);
        }
    }
    
    // Add floor plan from URL (as bitmap)
    public void addFloorFromUrl(int floorIndex, String imageUrl, LatLngBounds bounds) {
        // For now, we'll use a placeholder
        // In production, you'd need to download the image from URL
        // and cache it locally
        BitmapDescriptor image = BitmapDescriptorFactory.defaultMarker();
        
        GroundOverlayOptions groundOverlayOptions = new GroundOverlayOptions()
                .image(image)
                .positionFromBounds(bounds)
                .visible(false)  // Hidden initially
                .transparency(0.2f);

        if (groundOverlays != null && floorIndex < groundOverlays.length) {
            groundOverlays[floorIndex] = mMap.addGroundOverlay(groundOverlayOptions);
        }
    }

    // Switch floors and make sure only one floor is displayed
    public void switchFloor(int floorIndex) {
        if (groundOverlays == null || floorIndex < 0 || floorIndex >= groundOverlays.length) {
            return; // Prevent index out of bounds
        }
        // Hide all floors
        for (GroundOverlay overlay : groundOverlays) {
            if (overlay != null) {
                overlay.setVisible(false);
            }
        }
        // Show selected floor
        GroundOverlay selectedOverlay = groundOverlays[floorIndex];
        if (selectedOverlay != null) {
            selectedOverlay.setVisible(true);
        }
        currentFloor = floorIndex;
        Log.d(TAG, "Switched to floor " + floorIndex);
    }

    // Hide all floors
    public void hideMap() {
        if (groundOverlays == null) return;
        // Hide all floors
        for (GroundOverlay overlay : groundOverlays) {
            if (overlay != null) {
                overlay.setVisible(false);
            }
        }
    }
    
    // Select a venue/building and notify callback
    private void selectVenue(String buildingId, String venueName) {
        this.currentBuildingId = buildingId;
        this.currentVenueName = venueName;
        
        Log.d(TAG, "Venue selected: " + venueName + " (" + buildingId + ")");
        
        if (venueCallback != null) {
            venueCallback.onVenueSelected(buildingId, venueName);
        }
    }
    
    // Get selected venue name
    public String getSelectedVenueName() {
        return currentVenueName;
    }
    
    // Get selected building ID
    public String getSelectedBuildingId() {
        return currentBuildingId;
    }
}


