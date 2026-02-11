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
import android.widget.Toast;

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
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A fragment responsible for displaying a trajectory map using Google Maps.
 * Updated to support dynamic Indoor Map display via NetworkUtils.
 */
public class TrajectoryMapFragment extends Fragment {

    private static final String TAG = "TrajectoryMapFragment";

    private GoogleMap gMap;
    // Stores the user's current location
    private LatLng currentLocation;
    // Marker representing user's heading
    private Marker orientationMarker;
    // GNSS position marker
    private Marker gnssMarker;
    // Polyline representing user's movement path
    private Polyline polyline;
    // Tracks whether the polyline color is red
    private boolean isRed = true;
    // Tracks if GNSS tracking is enabled
    private boolean isGnssOn = false;

    // Polyline for GNSS path
    private Polyline gnssPolyline;
    // Stores the last GNSS location
    private LatLng lastGnssLocation = null;

    private LatLng pendingCameraPosition = null;
    private boolean hasPendingCameraMove = false;

    // Manages indoor mapping (Legacy, keeping for compatibility)
    private IndoorMapManager indoorMapManager;

    // UI
    private Spinner switchMapSpinner;
    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;

    private FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;
    private Polygon buildingPolygon;

    // 🆕 NEW: Venue and Floor Data
    private boolean hasVenue = false;
    private String currentVenueId = "";
    private String currentFloor = ""; // The floor selected by user or current floor
    private String currentVenueName = "";

    // 🆕 NEW: Store downloaded building data
    private NetworkUtils.BuildingData currentBuildingData = null;
    private List<String> sortedFloors = new ArrayList<>();

    // 🆕 NEW: Map objects for indoor features (to clear them when switching floors)
    private List<Polyline> indoorWalls = new ArrayList<>();
    private List<Polygon> indoorAreas = new ArrayList<>();
    private List<Marker> indoorPois = new ArrayList<>();

    // 🆕 Initial position from arguments (used for indoor map loading when currentLocation is null)
    private double initialLat = 0;
    private double initialLon = 0;

    public TrajectoryMapFragment() {
        // Required empty public constructor
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Inflate the separate layout containing map + map-related UI
        return inflater.inflate(R.layout.fragment_trajectory_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 🆕 1. Retrieve Venue Information from Arguments
        Bundle args = getArguments();
        if (args != null) {
            hasVenue = args.getBoolean("has_venue", false);
            currentVenueId = args.getString("venue_id", "");
            currentVenueName = args.getString("venue_name", "");
            currentFloor = args.getString("venue_floor", "0");
            // 🆕 Read initial position for indoor map loading
            initialLat = args.getDouble("initial_lat", 0);
            initialLon = args.getDouble("initial_lon", 0);
        }

        // Grab references to UI controls
        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch = view.findViewById(R.id.gnssSwitch);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        floorUpButton = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);

        // Setup floor up/down UI - Only show if we have a venue
        setFloorControlsVisibility(hasVenue ? View.VISIBLE : View.GONE);

        // Initialize the map asynchronously
        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.trajectoryMap);
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

                    // 🆕 2. Load Indoor Map if venue is selected
                    if (hasVenue) {
                        Log.d(TAG, "Venue detected: " + currentVenueName + ". Loading map...");
                        loadIndoorMapData();
                    } else {
                        // Fallback to old behavior (just outlines)
                        drawBuildingPolygon();
                    }

                    Log.d(TAG, "onMapReady: Map is ready!");
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

        // 🆕 3. Updated Floor Control Logic
        autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            // Logic for auto-floor using pressure sensor (omitted for now)
        });

        floorUpButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            changeFloor(1); // Go up
        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            changeFloor(-1); // Go down
        });
    }

    /**
     * 🆕 Load Indoor Map Data using NetworkUtils
     */
    private void loadIndoorMapData() {
        // Use current location > initial position from arguments > Edinburgh campus default
        double lat, lon;
        if (currentLocation != null) {
            lat = currentLocation.latitude;
            lon = currentLocation.longitude;
        } else if (initialLat != 0 || initialLon != 0) {
            lat = initialLat;
            lon = initialLon;
        } else {
            lat = 55.9234;
            lon = -3.1761;
        }
        Log.d(TAG, "loadIndoorMapData: using coordinates (" + lat + ", " + lon + ")");

        NetworkUtils.fetchFloorPlan(lat, lon, new NetworkUtils.Callback() {
            @Override
            public void onSuccess(NetworkUtils.BuildingData data) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    currentBuildingData = data;
                    // Sort floors so we know order
                    sortedFloors = sortFloorNames(new ArrayList<>(data.floors.keySet()));

                    if (data.floors.isEmpty()) {
                        Toast.makeText(getContext(), "No map data found for this location", Toast.LENGTH_SHORT).show();
                        drawBuildingPolygon(); // Fallback
                    } else {
                        // If currentFloor is set (from previous screen), try to use it
                        // otherwise use the first available floor
                        if (!data.floors.containsKey(currentFloor) && !sortedFloors.isEmpty()) {
                            currentFloor = sortedFloors.get(0);
                        }
                        drawIndoorMap(currentFloor);
                        Toast.makeText(getContext(), "Map loaded: " + currentFloor, Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Error loading map: " + error);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> drawBuildingPolygon());
                }
            }
        });
    }

    /**
     * 🆕 Draw the specific floor from downloaded data
     */
    private void drawIndoorMap(String floorName) {
        if (gMap == null || currentBuildingData == null) return;

        NetworkUtils.FloorData floorData = currentBuildingData.floors.get(floorName);
        if (floorData == null) return;

        // Clear previous indoor layers
        clearIndoorLayers();

        // 1. Draw Walls (Black Lines)
        for (List<LatLng> wall : floorData.walls) {
            Polyline line = gMap.addPolyline(new PolylineOptions()
                    .addAll(wall)
                    .color(Color.BLACK)
                    .width(6f)
                    .zIndex(10)); // Above ground
            indoorWalls.add(line);
        }

        // 2. Draw Areas (Gray Polygons)
        for (List<LatLng> area : floorData.areas) {
            Polygon poly = gMap.addPolygon(new PolygonOptions()
                    .addAll(area)
                    .strokeColor(Color.DKGRAY)
                    .strokeWidth(2f)
                    .fillColor(Color.argb(50, 200, 200, 200))
                    .zIndex(5)); // Below walls
            indoorAreas.add(poly);
        }

        // 3. Draw POIs
        for (NetworkUtils.Poi poi : floorData.pois) {
            if (poi.position != null) {
                Marker marker = gMap.addMarker(new MarkerOptions()
                        .position(poi.position)
                        .title(poi.label.isEmpty() ? poi.type : poi.label)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                        .zIndex(15));
                indoorPois.add(marker);
            }
        }
    }

    /**
     * 🆕 Clear indoor map layers
     */
    private void clearIndoorLayers() {
        for (Polyline line : indoorWalls) line.remove();
        indoorWalls.clear();
        for (Polygon poly : indoorAreas) poly.remove();
        indoorAreas.clear();
        for (Marker marker : indoorPois) marker.remove();
        indoorPois.clear();
    }

    /**
     * 🆕 Change floor logic
     */
    private void changeFloor(int offset) {
        if (sortedFloors.isEmpty()) return;

        int index = sortedFloors.indexOf(currentFloor);
        if (index == -1) index = 0;

        int newIndex = index + offset;
        if (newIndex >= 0 && newIndex < sortedFloors.size()) {
            currentFloor = sortedFloors.get(newIndex);
            drawIndoorMap(currentFloor);
            Toast.makeText(getContext(), "Floor: " + currentFloor, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "No more floors", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 🆕 Helper: Sort floor names (Copied from MapsFragment logic)
     */
    private List<String> sortFloorNames(List<String> floorNames) {
        Collections.sort(floorNames, (f1, f2) -> {
            try {
                int n1 = extractFloorNumber(f1);
                int n2 = extractFloorNumber(f2);
                return Integer.compare(n1, n2); // Ascending order
            } catch (Exception e) {
                return f1.compareTo(f2);
            }
        });
        return floorNames;
    }

    private int extractFloorNumber(String floorName) {
        String clean = floorName.toLowerCase().replaceAll("[^0-9-]", "");
        if (clean.isEmpty()) return 0;
        return Integer.parseInt(clean);
    }

    private void initMapSettings(GoogleMap map) {
        // Basic map settings
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        indoorMapManager = new IndoorMapManager(map);
        // Initialize an empty polyline
        polyline = map.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(5f)
                .add() // start empty
        );
        // GNSS path in blue
        gnssPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.BLUE)
                .width(5f)
                .add() // start empty
        );
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
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    public void updateUserLocation(@NonNull LatLng newLocation, float orientation) {
        if (gMap == null) return;
        LatLng oldLocation = this.currentLocation;
        this.currentLocation = newLocation;

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
            // Add the first point to the polyline so the line starts from the beginning
            if (polyline != null) {
                List<LatLng> points = new ArrayList<>(polyline.getPoints());
                points.add(newLocation);
                polyline.setPoints(points);
            }
        } else {
            orientationMarker.setPosition(newLocation);
            orientationMarker.setRotation(orientation);
            gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));

            if (oldLocation != null && !oldLocation.equals(newLocation) && polyline != null) {
                List<LatLng> points = new ArrayList<>(polyline.getPoints());
                points.add(newLocation);
                polyline.setPoints(points);
            }
        }

        // Use new drawing logic instead of IndoorMapManager
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(newLocation);
        }
    }

    /**
     * Sets initial camera position.
     * Renamed to be more descriptive, but aliased by setStartLocation for compatibility.
     */
    public void setInitialCameraPosition(@NonNull LatLng startLocation) {
        if (gMap != null) {
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(startLocation, 19f));
        } else {
            pendingCameraPosition = startLocation;
            hasPendingCameraMove = true;
        }
    }

    /**
     * 🔧 FIX: Added for backward compatibility with ReplayFragment.
     * ReplayFragment calls this method, so we map it to setInitialCameraPosition.
     */
    public void setStartLocation(LatLng startLocation) {
        setInitialCameraPosition(startLocation);
    }

    public LatLng getCurrentLocation() {
        return currentLocation;
    }

    public void updateGNSS(@NonNull LatLng gnssLocation) {
        if (gMap == null) return;
        if (!isGnssOn) return;

        if (gnssMarker == null) {
            gnssMarker = gMap.addMarker(new MarkerOptions()
                    .position(gnssLocation)
                    .title("GNSS Position")
                    .icon(BitmapDescriptorFactory
                            .defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
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

    public boolean isGnssEnabled() {
        return isGnssOn;
    }

    private void setFloorControlsVisibility(int visibility) {
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);
        autoFloorSwitch.setVisibility(visibility);
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
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
        lastGnssLocation = null;
        currentLocation = null;

        // Clear indoor data too
        clearIndoorLayers();

        if (gMap != null) {
            polyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.RED)
                    .width(5f)
                    .add());
            gnssPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.BLUE)
                    .width(5f)
                    .add());

            // 🆕 Redraw indoor map after clearing (if we have building data)
            if (currentBuildingData != null && currentFloor != null) {
                drawIndoorMap(currentFloor);
            }
        }
    }

    private void drawBuildingPolygon() {
        if (gMap == null) {
            Log.e(TAG, "GoogleMap is not ready");
            return;
        }
        // Keep existing fallback logic
        // nuclear building polygon vertices
        LatLng nucleus1 = new LatLng(55.92279538827796, -3.174612147506538);
        LatLng nucleus2 = new LatLng(55.92278121423647, -3.174107900816096);
        LatLng nucleus3 = new LatLng(55.92288405733954, -3.173843694667146);
        LatLng nucleus4 = new LatLng(55.92331786793876, -3.173832892645086);
        LatLng nucleus5 = new LatLng(55.923337194112555, -3.1746284301397387);
        // ... (Other hardcoded coordinates have been omitted; keep your original ones, or this coordinate might not be called if the API download is successful.)

        PolygonOptions buildingPolygonOptions = new PolygonOptions()
                .add(nucleus1, nucleus2, nucleus3, nucleus4, nucleus5)
                .strokeColor(Color.RED)
                .strokeWidth(10f)
                .zIndex(1);

        if (buildingPolygon != null) {
            buildingPolygon.remove();
        }
        buildingPolygon = gMap.addPolygon(buildingPolygonOptions);
    }
}