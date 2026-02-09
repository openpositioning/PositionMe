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
import com.google.android.material.switchmaterial.SwitchMaterial;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.OnMapReadyCallback;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.*;

import java.util.ArrayList;
import java.util.List;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;

/**
 * Fragment for displaying the Google Map, user trajectory, and indoor building maps.
 * <p>
 * Key Features:
 * - Map types: Hybrid, Normal, Satellite.
 * - Visualizes user movement (PDR) and GNSS path.
 * - Manages indoor floor plans (local assets or API fetch).
 * - Handles building selection for context-aware recording.
 *
 * @see com.openpositioning.PositionMe.presentation.activity.RecordingActivity
 * @see com.openpositioning.PositionMe.utils.IndoorMapManager
 */
public class TrajectoryMapFragment extends Fragment {

    private GoogleMap gMap;
    private LatLng currentLocation;
    private Marker orientationMarker;
    private Marker gnssMarker;
    private Polyline polyline;
    private boolean isRed = true;
    private boolean isGnssOn = false;

    private Polyline gnssPolyline;
    private LatLng lastGnssLocation = null;

    private LatLng pendingCameraPosition = null;
    private boolean hasPendingCameraMove = false;

    private IndoorMapManager indoorMapManager;
    private SensorFusion sensorFusion;

    // Tracks active building polygons for easy removal/redrawing
    private List<Polygon> activePolygons = new ArrayList<>();

    // UI Elements
    private Spinner switchMapSpinner;
    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;
    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;
    private Polygon buildingPolygon;

    public TrajectoryMapFragment() {
        // Required empty public constructor
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_trajectory_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // UI Initialization
        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch      = view.findViewById(R.id.gnssSwitch);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);

        setFloorControlsVisibility(View.GONE);

        // Initialize Map
        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.trajectoryMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(new OnMapReadyCallback() {
                @Override
                public void onMapReady(@NonNull GoogleMap googleMap) {
                    gMap = googleMap;
                    initMapSettings(gMap);

                    // Move camera if position was pending
                    if (hasPendingCameraMove && pendingCameraPosition != null) {
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
                        hasPendingCameraMove = false;
                        pendingCameraPosition = null;
                    }

                    drawBuildingPolygon();

                    // -----------------------------------------------------------------
                    // [Objective d] Handle Building Clicks (Context & Indoor Maps)
                    // -----------------------------------------------------------------
                    gMap.setOnPolygonClickListener(polygon -> {
                        Object tag = polygon.getTag();
                        if (tag != null) {
                            String buildingName = tag.toString();

                            // 1. Set filename context (formatted as lower_case)
                            String safeNameForFile = buildingName.toLowerCase().replace(" ", "_");
                            SensorFusion.getInstance().setVenueName(safeNameForFile);

                            // 2. Handle Indoor Map loading
                            if (indoorMapManager != null) {
                                // Try loading local map (Nucleus/Library)
                                boolean hasLocalMap = indoorMapManager.selectBuilding(buildingName);

                                if (hasLocalMap) {
                                    setFloorControlsVisibility(View.VISIBLE);
                                    android.widget.Toast.makeText(requireContext(),
                                            "Switched to " + buildingName + " Map",
                                            android.widget.Toast.LENGTH_SHORT).show();
                                } else {
                                    // Check for API-based maps (Murchison/FJB)
                                    if (buildingName.equals("Murchison")) {
                                        android.widget.Toast.makeText(requireContext(),
                                                "Fetching API for " + buildingName + "...",
                                                android.widget.Toast.LENGTH_SHORT).show();

                                        // Fetch map via API
                                        indoorMapManager.fetchFloorPlanFromApi(new LatLng(55.924550, -3.179700));

                                        // Force show buttons while loading to prevent flickering
                                        setFloorControlsVisibility(View.VISIBLE);

                                    } else if (buildingName.equals("Fleeming Jenkin")) {
                                        android.widget.Toast.makeText(requireContext(),
                                                "Fetching API for " + buildingName + "...",
                                                android.widget.Toast.LENGTH_SHORT).show();
                                        indoorMapManager.fetchFloorPlanFromApi(new LatLng(55.922692, -3.172956));
                                        setFloorControlsVisibility(View.VISIBLE);

                                    } else {
                                        // No map available
                                        setFloorControlsVisibility(View.GONE);
                                    }
                                }
                            }
                        }
                    });

                    // -----------------------------------------------------------------
                    // Handle Map Background Clicks (Deselect)
                    // -----------------------------------------------------------------
                    gMap.setOnMapClickListener(latLng -> {
                        if (indoorMapManager != null) {
                            // Clear overlays and state
                            indoorMapManager.deselectBuilding();
                            setFloorControlsVisibility(View.GONE);

                            android.widget.Toast.makeText(requireContext(),
                                    "Map Deselected",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });

                    Log.d("TrajectoryMapFragment", "onMapReady: Map ready.");
                }
            });
        }

        initMapTypeSpinner();

        // GNSS Toggle
        gnssSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isGnssOn = isChecked;
            if (!isChecked && gnssMarker != null) {
                gnssMarker.remove();
                gnssMarker = null;
            }
        });

        // Path Color Toggle
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

        // Floor Control Logic
        autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            // TODO: Fix SensorFusion elevation method for auto-floor logic.
        });

        floorUpButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) indoorMapManager.increaseFloor();
        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (indoorMapManager != null) indoorMapManager.decreaseFloor();
        });
    }

    /**
     * Initializes basic map settings, indoor manager, and trajectory polylines.
     */
    private void initMapSettings(GoogleMap map) {
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        indoorMapManager = new IndoorMapManager(map);

        // PDR Path (Red)
        polyline = map.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(5f)
                .add());

        // GNSS Path (Blue)
        gnssPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.BLUE)
                .width(5f)
                .add());
    }

    /**
     * Sets up the Spinner for switching map types (Hybrid, Normal, Satellite).
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
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (gMap == null) return;
                switch (position){
                    case 0: gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID); break;
                    case 1: gMap.setMapType(GoogleMap.MAP_TYPE_NORMAL); break;
                    case 2: gMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE); break;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /**
     * Updates user location, orientation marker, and PDR polyline.
     * Also updates the indoor map overlay position.
     */
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
        } else {
            orientationMarker.setPosition(newLocation);
            orientationMarker.setRotation(orientation);
            gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
        }

        // Extend PDR polyline
        if (oldLocation != null && !oldLocation.equals(newLocation) && polyline != null) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            points.add(newLocation);
            polyline.setPoints(points);
        }

        // Update Indoor Map center
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(newLocation);
            // Visibility logic handled in building selection/API callback
        }
    }

    /**
     * Sets initial camera position. Stores it if map is not ready yet.
     */
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

    /**
     * Updates GNSS marker and extends the blue GNSS polyline.
     */
    public void updateGNSS(@NonNull LatLng gnssLocation) {
        if (gMap == null || !isGnssOn) return;

        if (gnssMarker == null) {
            gnssMarker = gMap.addMarker(new MarkerOptions()
                    .position(gnssLocation)
                    .title("GNSS Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
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
        currentLocation  = null;

        if (gMap != null) {
            polyline = gMap.addPolyline(new PolylineOptions().color(Color.RED).width(5f).add());
            gnssPolyline = gMap.addPolyline(new PolylineOptions().color(Color.BLUE).width(5f).add());
        }
    }

    /**
     * Draws building polygons on the map using specific colors.
     * <p>
     * Colors: Murchison (Red), Nucleus/Library (Amber), FJB (Blue).
     * Tags are used by IndoorMapManager to identify buildings.
     */
    private void drawBuildingPolygon() {
        if (gMap == null) {
            Log.e("TrajectoryMapFragment", "GoogleMap is not ready");
            return;
        }

        // Clear existing polygons
        for (Polygon p : activePolygons) {
            p.remove();
        }
        activePolygons.clear();

        int redColor = Color.RED;
        int amberColor = Color.parseColor("#FFBF00");
        int blueColor = Color.BLUE;
        int transparent = Color.TRANSPARENT;

        // 1. Murchison House (Red)
        PolygonOptions murchisonOptions = new PolygonOptions()
                .add(new LatLng(55.924550, -3.179700),
                        new LatLng(55.924550, -3.178600),
                        new LatLng(55.923750, -3.178600),
                        new LatLng(55.923750, -3.179700))
                .strokeColor(redColor)
                .strokeWidth(10f)
                .fillColor(transparent)
                .clickable(true)
                .zIndex(2);

        // 2. Nucleus (Amber)
        PolygonOptions nucleusOptions = new PolygonOptions()
                .add(new LatLng(55.922795, -3.174612),
                        new LatLng(55.922781, -3.174107),
                        new LatLng(55.922884, -3.173843),
                        new LatLng(55.923317, -3.173832),
                        new LatLng(55.923337, -3.174628))
                .strokeColor(amberColor)
                .strokeWidth(10f)
                .fillColor(transparent)
                .clickable(true)
                .zIndex(2);

        // 3. Library (Amber)
        PolygonOptions libraryOptions = new PolygonOptions()
                .add(new LatLng(55.923034, -3.175184),
                        new LatLng(55.923032, -3.174777),
                        new LatLng(55.922793, -3.174795),
                        new LatLng(55.922801, -3.175195))
                .strokeColor(amberColor)
                .strokeWidth(10f)
                .fillColor(transparent)
                .clickable(true)
                .zIndex(2);

        // 4. Fleeming Jenkin (Blue)
        PolygonOptions fjbOptions = new PolygonOptions()
                .add(new LatLng(55.922692, -3.172956),
                        new LatLng(55.922822, -3.172594),
                        new LatLng(55.922235, -3.171921),
                        new LatLng(55.922107, -3.172281))
                .strokeColor(blueColor)
                .strokeWidth(10f)
                .fillColor(transparent)
                .clickable(true)
                .zIndex(2);

        // Add polygons and tag them for recognition
        Polygon p1 = gMap.addPolygon(murchisonOptions); p1.setTag("Murchison");
        Polygon p2 = gMap.addPolygon(nucleusOptions);   p2.setTag("Nucleus");
        Polygon p3 = gMap.addPolygon(libraryOptions);   p3.setTag("Library");
        Polygon p4 = gMap.addPolygon(fjbOptions);       p4.setTag("Fleeming Jenkin");

        activePolygons.add(p1);
        activePolygons.add(p2);
        activePolygons.add(p3);
        activePolygons.add(p4);
    }

    private com.google.android.gms.maps.model.BitmapDescriptor createNumberedMarkerBitmap(int number) {
        android.graphics.Bitmap conf = android.graphics.Bitmap.createBitmap(80, 80, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(conf);

        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(android.graphics.Color.BLUE);
        canvas.drawCircle(40, 40, 40, paint);

        paint.setColor(android.graphics.Color.WHITE);
        paint.setTextSize(40);
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        canvas.drawText(String.valueOf(number), 40, 55, paint);

        return com.google.android.gms.maps.model.BitmapDescriptorFactory.fromBitmap(conf);
    }

    /**
     * [Objective c] Adds a numbered custom marker to the map.
     */
    public void addMapMarker(LatLng position, int number) {
        if (gMap != null) {
            gMap.addMarker(new MarkerOptions()
                    .position(position)
                    .title("Marker " + number)
                    .icon(createNumberedMarkerBitmap(number))
                    .anchor(0.5f, 0.5f));
        }
    }
}