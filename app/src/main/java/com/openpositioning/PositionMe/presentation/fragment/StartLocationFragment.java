package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Color;
import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fragment for selecting the start location before recording begins.
 * Displays a Google Map with building outlines fetched from the floorplan API.
 * Users can tap a building outline to select it, which shows the indoor floor plan
 * and places a draggable marker at the building center. The selected building ID
 * is saved for use during trajectory upload.
 *
 * @see RecordingFragment the next fragment in the recording flow
 * @see SensorFusion the class containing sensors and recording
 * @see FloorplanApiClient the API client for fetching building data
 */
public class StartLocationFragment extends Fragment {

    private static final String TAG = "StartLocationFragment";
    private static final long INITIAL_ESTIMATE_POLL_MS = 500L;

    // UI elements
    private Button button;
    private Button loadingButton;
    private TextView instructionText;
    private View buildingInfoCard;
    private TextView buildingNameText;

    // Singleton SensorFusion class which stores data from all sensors
    private SensorFusion sensorFusion = SensorFusion.getInstance();

    // Map and position state
    private GoogleMap mMap;
    private LatLng position;
    private float[] startPosition = new float[2];
    private float zoom = 19f;
    private Marker startMarker;

    // Building selection state
    private String selectedBuildingId;
    private final List<Polygon> buildingPolygons = new ArrayList<>();
    private final Map<String, FloorplanApiClient.BuildingInfo> floorplanBuildingMap = new HashMap<>();
    private Polygon selectedPolygon;
    private boolean userAdjustedStartPosition;
    private boolean initialEstimateApplied;
    private boolean floorplanRequestInFlight;
    private boolean floorplanDataReady;

    private final Handler initialEstimateHandler = new Handler(Looper.getMainLooper());
    private final Runnable initialEstimatePoller = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) {
                return;
            }
            updateSetButtonAvailability();
            initialEstimateHandler.postDelayed(this, INITIAL_ESTIMATE_POLL_MS);
        }
    };

    // Vector shapes drawn as floor plan preview (cleared when switching buildings)
    private final List<Polygon> previewPolygons = new ArrayList<>();
    private final List<Polyline> previewPolylines = new ArrayList<>();

    // Building outline colours (ARGB)
    private static final int FILL_COLOR_DEFAULT = Color.argb(60, 33, 150, 243);
    private static final int STROKE_COLOR_DEFAULT = Color.argb(200, 33, 150, 243);
    private static final int FILL_COLOR_SELECTED = Color.argb(100, 33, 150, 243);
    private static final int STROKE_COLOR_SELECTED = Color.argb(255, 25, 118, 210);

    /**
     * Public Constructor for the class.
     * Left empty as not required
     */
    public StartLocationFragment() {
        // Required empty public constructor
    }

    /**
     * {@inheritDoc}
     * Inflates the layout, initialises the map, and requests building data from the API.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity != null && activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().hide();
        }
        View rootView = inflater.inflate(R.layout.fragment_startlocation, container, false);

        // Obtain the start position from GPS data
        startPosition = sensorFusion.getGNSSLatitude(false);
        if (startPosition[0] == 0 && startPosition[1] == 0) {
            zoom = 1f;
        } else {
            zoom = 19f;
        }

        // Initialize map fragment
        SupportMapFragment supportMapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.startMap);

        supportMapFragment.getMapAsync(new OnMapReadyCallback() {
            /**
             * {@inheritDoc}
             * Sets up the map, adds the initial marker, and fetches building outlines.
             */
            @Override
            public void onMapReady(GoogleMap googleMap) {
                mMap = googleMap;
                setupMap();
                requestBuildingData();
            }
        });

        return rootView;
    }

    /**
     * Configures the Google Map with initial settings, draggable marker, and listeners.
     */
    private void setupMap() {
        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        mMap.getUiSettings().setCompassEnabled(true);
        mMap.getUiSettings().setTiltGesturesEnabled(true);
        mMap.getUiSettings().setRotateGesturesEnabled(true);
        mMap.getUiSettings().setScrollGesturesEnabled(true);
        mMap.clear();

        // Add initial marker at GPS position
        position = new LatLng(startPosition[0], startPosition[1]);
        startMarker = mMap.addMarker(new MarkerOptions()
                .position(position)
                .title("Start Position")
                .draggable(true));
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, zoom));

        // Marker drag listener to update the start position when dragged
        mMap.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener() {
            @Override
            public void onMarkerDragStart(Marker marker) {}

            @Override
            public void onMarkerDragEnd(Marker marker) {
                startPosition[0] = (float) marker.getPosition().latitude;
                startPosition[1] = (float) marker.getPosition().longitude;
                userAdjustedStartPosition = true;
            }

            @Override
            public void onMarkerDrag(Marker marker) {}
        });

        // Polygon click listener for building selection
        mMap.setOnPolygonClickListener(polygon -> {
            String buildingName = (String) polygon.getTag();
            if (buildingName != null) {
                onBuildingSelected(buildingName, polygon);
            }
        });
    }

    /**
     * Requests building data from the floorplan API using the current GPS position.
     * On success, draws building outlines on the map. On failure, falls back to
     * the standard drag-marker interaction.
     */
    private void requestBuildingData() {
        FloorplanApiClient apiClient = new FloorplanApiClient();
        floorplanRequestInFlight = true;
        floorplanDataReady = false;
        updateSetButtonAvailability();

        // Collect observed WiFi AP MAC addresses from latest scan
        List<String> observedMacs = new ArrayList<>();
        List<com.openpositioning.PositionMe.sensors.Wifi> wifiList =
                sensorFusion.getWifiList();
        if (wifiList != null) {
            for (com.openpositioning.PositionMe.sensors.Wifi wifi : wifiList) {
                String mac = wifi.getBssidString();
                if (mac != null && !mac.isEmpty()) {
                    observedMacs.add(mac);
                }
            }
        }

        apiClient.requestFloorplan(startPosition[0], startPosition[1], observedMacs,
                new FloorplanApiClient.FloorplanCallback() {
                    @Override
                    public void onSuccess(List<FloorplanApiClient.BuildingInfo> buildings) {
                        floorplanRequestInFlight = false;
                        floorplanDataReady = true;
                        sensorFusion.setFloorplanBuildings(buildings);
                        floorplanBuildingMap.clear();
                        for (FloorplanApiClient.BuildingInfo building : buildings) {
                            floorplanBuildingMap.put(building.getName(), building);
                        }

                        if (!isAdded() || mMap == null) {
                            return;
                        }

                        updateSetButtonAvailability();

                        if (buildings.isEmpty()) {
                            Log.d(TAG, "No buildings returned by API");
                            if (instructionText != null) {
                                instructionText.setText(R.string.noBuildingsFound);
                            }
                            return;
                        }

                        drawBuildingOutlines(buildings);
                    }

                    @Override
                    public void onFailure(String error) {
                        floorplanRequestInFlight = false;
                        floorplanDataReady = true;
                        sensorFusion.setFloorplanBuildings(new ArrayList<>());
                        floorplanBuildingMap.clear();
                        if (!isAdded()) return;
                        updateSetButtonAvailability();
                        Log.e(TAG, "Floorplan API failed: " + error);
                    }
                });
    }

    /**
     * Draws building outlines on the map as clickable coloured polygons.
     *
     * @param buildings list of building info objects containing outline polygons
     */
    private void drawBuildingOutlines(List<FloorplanApiClient.BuildingInfo> buildings) {
        for (FloorplanApiClient.BuildingInfo building : buildings) {
            List<LatLng> outlinePoints = building.getOutlinePolygon();
            if (outlinePoints == null || outlinePoints.size() < 3) {
                Log.w(TAG, "Skipping building with insufficient outline points: "
                        + building.getName());
                continue;
            }

            PolygonOptions options = new PolygonOptions()
                    .addAll(outlinePoints)
                    .strokeColor(STROKE_COLOR_DEFAULT)
                    .strokeWidth(4f)
                    .fillColor(FILL_COLOR_DEFAULT)
                    .clickable(true);

            Polygon polygon = mMap.addPolygon(options);
            polygon.setTag(building.getName());
            buildingPolygons.add(polygon);
        }

        // Auto-zoom to include building(s) and current position
        if (!buildingPolygons.isEmpty()) {
            LatLngBounds.Builder boundsBuilder = new LatLngBounds.Builder();
            boundsBuilder.include(new LatLng(startPosition[0], startPosition[1]));
            for (Polygon p : buildingPolygons) {
                for (LatLng point : p.getPoints()) {
                    boundsBuilder.include(point);
                }
            }
            try {
                mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(
                        boundsBuilder.build(), 100));
            } catch (Exception e) {
                Log.w(TAG, "Could not fit bounds", e);
            }
        }
    }

    /**
     * Handles building selection when user taps a building polygon.
     * Highlights the polygon, shows the floor plan overlay, moves the marker,
     * and stores the building identifier.
     *
     * @param buildingName the name/ID of the selected building (e.g. "nucleus_building")
     * @param polygon      the tapped polygon on the map
     */
    private void onBuildingSelected(String buildingName, Polygon polygon) {
        // Reset previous selection colour
        if (selectedPolygon != null) {
            selectedPolygon.setFillColor(FILL_COLOR_DEFAULT);
            selectedPolygon.setStrokeColor(STROKE_COLOR_DEFAULT);
        }

        // Highlight selected polygon
        selectedPolygon = polygon;
        polygon.setFillColor(FILL_COLOR_SELECTED);
        polygon.setStrokeColor(STROKE_COLOR_SELECTED);

        // Store building selection
        selectedBuildingId = buildingName;

        // Compute building centre from polygon points
        LatLng center = computePolygonCenter(polygon);

        // Move the marker to building centre
        if (startMarker != null) {
            startMarker.setPosition(center);
        }
        startPosition[0] = (float) center.latitude;
        startPosition[1] = (float) center.longitude;
        userAdjustedStartPosition = true;

        // Zoom to the building
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(center, 20f));

        // Show floor plan overlay for the selected building
        showFloorPlanOverlay(buildingName);

        // Update UI with building name
        updateBuildingInfoDisplay(buildingName);
        updateSetButtonAvailability();

        Log.d(TAG, "Building selected: " + buildingName);
    }

    /**
     * Shows a vector floor plan preview for the selected building using the
     * map_shapes data from the API. Draws the ground floor shapes (walls, rooms).
     * Removes any previously drawn preview shapes.
     *
     * @param buildingName the building identifier
     */
    private void showFloorPlanOverlay(String buildingName) {
        // Clear previous preview shapes
        for (Polygon p : previewPolygons) p.remove();
        for (Polyline p : previewPolylines) p.remove();
        previewPolygons.clear();
        previewPolylines.clear();

        FloorplanApiClient.BuildingInfo building = floorplanBuildingMap.get(buildingName);
        if (building == null) return;

        List<FloorplanApiClient.FloorShapes> floors = building.getFloorShapesList();
        if (floors == null || floors.isEmpty()) {
            Log.d(TAG, "No floor shape data available for: " + buildingName);
            return;
        }

        // Pick the default floor to preview (ground floor)
        int defaultFloor = Math.min(1, floors.size() - 1);
        FloorplanApiClient.FloorShapes floor = floors.get(defaultFloor);

        for (FloorplanApiClient.MapShapeFeature feature : floor.getFeatures()) {
            String geoType = feature.getGeometryType();
            String indoorType = feature.getIndoorType();

            if ("MultiPolygon".equals(geoType) || "Polygon".equals(geoType)) {
                for (List<LatLng> ring : feature.getParts()) {
                    if (ring.size() < 3) continue;
                    Polygon p = mMap.addPolygon(new PolygonOptions()
                            .addAll(ring)
                            .strokeColor(getPreviewStrokeColor(indoorType))
                            .strokeWidth(2f)
                            .fillColor(getPreviewFillColor(indoorType)));
                    previewPolygons.add(p);
                }
            } else if ("MultiLineString".equals(geoType)
                    || "LineString".equals(geoType)) {
                for (List<LatLng> line : feature.getParts()) {
                    if (line.size() < 2) continue;
                    Polyline pl = mMap.addPolyline(new PolylineOptions()
                            .addAll(line)
                            .color(getPreviewStrokeColor(indoorType))
                            .width(3f));
                    previewPolylines.add(pl);
                }
            }
        }
    }

    /**
     * Returns the stroke colour for a preview indoor feature.
     */
    private int getPreviewStrokeColor(String indoorType) {
        if ("wall".equals(indoorType)) return Color.argb(200, 80, 80, 80);
        if ("room".equals(indoorType)) return Color.argb(180, 33, 150, 243);
        return Color.argb(150, 100, 100, 100);
    }

    /**
     * Returns the fill colour for a preview indoor feature.
     */
    private int getPreviewFillColor(String indoorType) {
        if ("room".equals(indoorType)) return Color.argb(40, 33, 150, 243);
        return Color.TRANSPARENT;
    }

    /**
     * Updates the building info card to show the selected building name.
     *
     * @param buildingName the raw building name from the API
     */
    private void updateBuildingInfoDisplay(String buildingName) {
        if (buildingInfoCard == null || buildingNameText == null) return;

        String displayName = formatBuildingName(buildingName);
        buildingNameText.setText(getString(R.string.buildingSelected, displayName));
        buildingInfoCard.setVisibility(View.VISIBLE);
    }

    /**
     * Formats a building API name into a user-friendly display name.
     * Converts underscores to spaces and capitalises each word.
     *
     * @param apiName the API building name (e.g. "nucleus_building")
     * @return formatted name (e.g. "Nucleus Building")
     */
    private String formatBuildingName(String apiName) {
        if (apiName == null || apiName.isEmpty()) return "";
        String[] parts = apiName.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    /**
     * Computes the centroid of a Google Maps Polygon by averaging all vertices.
     *
     * @param polygon the polygon whose centre is to be computed
     * @return the centroid LatLng
     */
    private LatLng computePolygonCenter(Polygon polygon) {
        List<LatLng> points = polygon.getPoints();
        double latSum = 0, lonSum = 0;
        int count = 0;
        for (LatLng p : points) {
            latSum += p.latitude;
            lonSum += p.longitude;
            count++;
        }
        if (count == 0) return new LatLng(0, 0);
        return new LatLng(latSum / count, lonSum / count);
    }

    /**
     * {@inheritDoc}
     * Sets up button click listeners and view references after the view is created.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        this.button = view.findViewById(R.id.startLocationDone);
        this.loadingButton = view.findViewById(R.id.startLocationLoading);
        this.instructionText = view.findViewById(R.id.correctionInfoView);
        this.buildingInfoCard = view.findViewById(R.id.buildingInfoCard);
        this.buildingNameText = view.findViewById(R.id.buildingNameText);

        updateSetButtonAvailability();
        if (requireActivity() instanceof RecordingActivity) {
            initialEstimateHandler.post(initialEstimatePoller);
        }

        this.button.setOnClickListener(v -> {
            if (requireActivity() instanceof RecordingActivity
                    && !(hasInitialEstimate() && hasRequiredFloorplanDataForRecording())) {
                updateSetButtonAvailability();
                return;
            }

            float chosenLat = startPosition[0];
            float chosenLon = startPosition[1];

            // Save the building selection for campaign binding during upload
            if (selectedBuildingId != null) {
                sensorFusion.setSelectedBuildingId(selectedBuildingId);
            }

            if (requireActivity() instanceof RecordingActivity) {
                // Start sensor recording + set the start location
                sensorFusion.startRecording();
                sensorFusion.setStartGNSSLatitude(startPosition);
                // Write trajectory_id, initial_position and initial heading to protobuf
                sensorFusion.writeInitialMetadata();

                // Switch to the recording screen
                ((RecordingActivity) requireActivity()).showRecordingScreen();

            } else if (requireActivity() instanceof ReplayActivity) {
                ((ReplayActivity) requireActivity())
                        .onStartLocationChosen(chosenLat, chosenLon);
            }
        });
    }

    @Override
    public void onDestroyView() {
        initialEstimateHandler.removeCallbacks(initialEstimatePoller);
        super.onDestroyView();
    }

    private boolean hasInitialEstimate() {
        return sensorFusion.getFusedLatLng() != null;
    }

    private void updateSetButtonAvailability() {
        if (button == null || !isAdded()) {
            return;
        }

        boolean isReplayFlow = requireActivity() instanceof ReplayActivity;
        boolean hasEstimate = hasInitialEstimate();
        boolean hasFloorplan = hasRequiredFloorplanDataForRecording();
        boolean ready = isReplayFlow || (hasEstimate && hasFloorplan);

        button.setEnabled(ready);
        button.setAlpha(ready ? 1.0f : 0.5f);
        if (loadingButton != null) {
            loadingButton.setVisibility(ready ? View.GONE : View.VISIBLE);
        }
        button.setVisibility(ready ? View.VISIBLE : View.GONE);

        if (!isReplayFlow && instructionText != null) {
            if (!hasEstimate) {
                instructionText.setText(R.string.initialEstimateWaiting);
            } else if (!hasFloorplan) {
                instructionText.setText(R.string.floorplanLoadingWaiting);
            } else {
                instructionText.setText(R.string.buildingSelectionInstructions);
            }
        }

        applyInitialEstimateToMarkerIfNeeded(ready, isReplayFlow);
    }

    private boolean hasRequiredFloorplanDataForRecording() {
        if (requireActivity() instanceof ReplayActivity) {
            return true;
        }

        if (selectedBuildingId != null && !selectedBuildingId.isEmpty()) {
            FloorplanApiClient.BuildingInfo selectedBuilding =
                    sensorFusion.getFloorplanBuilding(selectedBuildingId);
            return selectedBuilding != null
                    && selectedBuilding.getFloorShapesList() != null
                    && !selectedBuilding.getFloorShapesList().isEmpty();
        }

        return floorplanDataReady && !floorplanRequestInFlight;
    }

    private void applyInitialEstimateToMarkerIfNeeded(boolean ready, boolean isReplayFlow) {
        if (!ready || isReplayFlow || initialEstimateApplied || userAdjustedStartPosition
                || mMap == null || startMarker == null) {
            return;
        }

        LatLng fusedPosition = sensorFusion.getFusedLatLng();
        if (fusedPosition == null) {
            return;
        }

        startPosition[0] = (float) fusedPosition.latitude;
        startPosition[1] = (float) fusedPosition.longitude;
        startMarker.setPosition(fusedPosition);
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(fusedPosition, zoom));
        initialEstimateApplied = true;
    }
}
