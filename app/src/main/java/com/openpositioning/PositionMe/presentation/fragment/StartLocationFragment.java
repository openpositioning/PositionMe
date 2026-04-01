package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
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
import com.openpositioning.PositionMe.utils.UtilFunctions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    private static final LatLng DEFAULT_AUTO_MAP_POSITION = new LatLng(55.9230, -3.1741);
    private static final double FLOORPLAN_REFRESH_DISTANCE_METERS = 15.0;
    private static final float LIVE_DIRECTION_MARKER_SIZE_DP = 18f;
    private static final double MIN_DIRECTION_DISTANCE_METERS = 0.55;
    private static final long AUTO_INIT_REFRESH_INTERVAL_MS = 1_000L;

    // UI elements
    private Button button;
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
    private boolean manualSelectionEnabled;
    private LatLng lastFloorplanRequestPosition;
    private LatLng previousAutoPreviewPosition;
    private float lastAutoPreviewDirectionDegrees;
    private String currentPreviewFloorDisplayName;

    // Building selection state
    private String selectedBuildingId;
    private final List<Polygon> buildingPolygons = new ArrayList<>();
    private final Map<String, FloorplanApiClient.BuildingInfo> floorplanBuildingMap = new HashMap<>();
    private final Map<String, Polygon> buildingPolygonByName = new HashMap<>();
    private Polygon selectedPolygon;

    // Vector shapes drawn as floor plan preview (cleared when switching buildings)
    private final List<Polygon> previewPolygons = new ArrayList<>();
    private final List<Polyline> previewPolylines = new ArrayList<>();

    // Building outline colours (ARGB)
    private static final int FILL_COLOR_DEFAULT = Color.argb(60, 33, 150, 243);
    private static final int STROKE_COLOR_DEFAULT = Color.argb(200, 33, 150, 243);
    private static final int FILL_COLOR_SELECTED = Color.argb(100, 33, 150, 243);
    private static final int STROKE_COLOR_SELECTED = Color.argb(255, 25, 118, 210);
    private final Handler autoInitHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoInitRefresh = new Runnable() {
        @Override
        public void run() {
            refreshAutoInitializationState();
            autoInitHandler.postDelayed(this, AUTO_INIT_REFRESH_INTERVAL_MS);
        }
    };

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
        manualSelectionEnabled = requireActivity() instanceof ReplayActivity;

        LatLng initialMapPosition = resolveInitialMapPosition();
        startPosition[0] = (float) initialMapPosition.latitude;
        startPosition[1] = (float) initialMapPosition.longitude;
        zoom = manualSelectionEnabled
                ? ((startPosition[0] == 0 && startPosition[1] == 0) ? 1f : 19f)
                : 19f;

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
        startMarker = mMap.addMarker(buildStartMarkerOptions(position));
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, zoom));

        if (manualSelectionEnabled) {
            // Marker drag listener to update the start position when dragged
            mMap.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener() {
                @Override
                public void onMarkerDragStart(Marker marker) {}

                @Override
                public void onMarkerDragEnd(Marker marker) {
                    startPosition[0] = (float) marker.getPosition().latitude;
                    startPosition[1] = (float) marker.getPosition().longitude;
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
    }

    /**
     * Requests building data from the floorplan API using the current GPS position.
     * On success, draws building outlines on the map. On failure, falls back to
     * the standard drag-marker interaction.
     */
    private void requestBuildingData() {
        LatLng requestPosition = new LatLng(startPosition[0], startPosition[1]);
        lastFloorplanRequestPosition = requestPosition;
        FloorplanApiClient apiClient = new FloorplanApiClient();

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
                        if (!isAdded() || mMap == null) return;

                        sensorFusion.setFloorplanBuildings(buildings);
                        floorplanBuildingMap.clear();
                        for (FloorplanApiClient.BuildingInfo building : buildings) {
                            floorplanBuildingMap.put(building.getName(), building);
                        }

                        if (buildings.isEmpty()) {
                            Log.d(TAG, "No buildings returned by API");
                            if (instructionText != null) {
                                instructionText.setText(
                                        manualSelectionEnabled
                                                ? R.string.noBuildingsFound
                                                : R.string.auto_init_ready
                                );
                            }
                            return;
                        }

                        drawBuildingOutlines(buildings);
                        if (!manualSelectionEnabled) {
                            autoSelectBuildingForPosition(
                                    new LatLng(startPosition[0], startPosition[1]));
                        }
                    }

                    @Override
                    public void onFailure(String error) {
                        if (!isAdded()) return;
                        sensorFusion.setFloorplanBuildings(new ArrayList<>());
                        floorplanBuildingMap.clear();
                        lastFloorplanRequestPosition = null;
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
        for (Polygon polygon : buildingPolygons) {
            polygon.remove();
        }
        buildingPolygons.clear();
        buildingPolygonByName.clear();

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
            buildingPolygonByName.put(building.getName(), polygon);
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

        // Zoom to the building
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(center, 20f));

        // Show floor plan overlay for the selected building
        showFloorPlanOverlay(buildingName);

        // Update UI with building name
        updateBuildingInfoDisplay(buildingName);

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
        currentPreviewFloorDisplayName = null;

        FloorplanApiClient.BuildingInfo building = floorplanBuildingMap.get(buildingName);
        if (building == null) return;

        List<FloorplanApiClient.FloorShapes> floors = building.getFloorShapesList();
        if (floors == null || floors.isEmpty()) {
            Log.d(TAG, "No floor shape data available for: " + buildingName);
            return;
        }

        int previewFloor = resolvePreviewFloorIndex(buildingName, floors);
        FloorplanApiClient.FloorShapes floor = floors.get(previewFloor);
        currentPreviewFloorDisplayName = floor.getDisplayName();

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
        if (!manualSelectionEnabled && currentPreviewFloorDisplayName != null
                && !currentPreviewFloorDisplayName.isEmpty()) {
            buildingNameText.setText(
                    getString(R.string.buildingSelected, displayName)
                            + " | Floor "
                            + currentPreviewFloorDisplayName
            );
        } else {
            buildingNameText.setText(getString(R.string.buildingSelected, displayName));
        }
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
        this.instructionText = view.findViewById(R.id.correctionInfoView);
        this.buildingInfoCard = view.findViewById(R.id.buildingInfoCard);
        this.buildingNameText = view.findViewById(R.id.buildingNameText);

        if (!manualSelectionEnabled) {
            button.setEnabled(sensorFusion.hasAutomaticStartFix());
            button.setText(R.string.start);
            instructionText.setText(R.string.auto_init_waiting);
            autoInitHandler.post(autoInitRefresh);
        }

        this.button.setOnClickListener(v -> {
            float chosenLat = startPosition[0];
            float chosenLon = startPosition[1];

            // Save the building selection for campaign binding during upload
            if (selectedBuildingId != null) {
                sensorFusion.setSelectedBuildingId(selectedBuildingId);
            }

            if (requireActivity() instanceof RecordingActivity) {
                if (!sensorFusion.prepareAutomaticStart()) {
                    Toast.makeText(
                            requireContext(),
                            R.string.auto_init_waiting,
                            Toast.LENGTH_SHORT
                    ).show();
                    return;
                }
                // Start sensor recording + set the start location
                sensorFusion.startRecording();
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
        autoInitHandler.removeCallbacks(autoInitRefresh);
        super.onDestroyView();
    }

    private LatLng resolveInitialMapPosition() {
        if (!manualSelectionEnabled) {
            LatLng automaticPosition = getBestAutomaticPreviewPosition();
            if (automaticPosition != null) {
                return automaticPosition;
            }
        }

        float[] gnssPosition = sensorFusion.getGNSSLatitude(false);
        if (gnssPosition[0] != 0f || gnssPosition[1] != 0f) {
            return new LatLng(gnssPosition[0], gnssPosition[1]);
        }

        return DEFAULT_AUTO_MAP_POSITION;
    }

    private void refreshAutoInitializationState() {
        if (!isAdded() || mMap == null || manualSelectionEnabled) {
            return;
        }

        LatLng automaticPosition = getBestAutomaticPreviewPosition();
        if (automaticPosition == null) {
            button.setEnabled(false);
            instructionText.setText(R.string.auto_init_waiting);
            return;
        }

        updateStartMarker(automaticPosition, true);
        button.setEnabled(true);

        boolean shouldRefreshFloorplan = lastFloorplanRequestPosition == null
                || UtilFunctions.distanceBetweenPoints(
                lastFloorplanRequestPosition,
                automaticPosition
        ) >= FLOORPLAN_REFRESH_DISTANCE_METERS;
        if (shouldRefreshFloorplan) {
            requestBuildingData();
        } else {
            autoSelectBuildingForPosition(automaticPosition);
        }

        if (selectedBuildingId != null && !selectedBuildingId.isEmpty()) {
            instructionText.setText(
                    getString(
                            R.string.auto_init_ready_with_building,
                            formatBuildingName(selectedBuildingId)
                    )
            );
        } else {
            instructionText.setText(R.string.auto_init_ready);
        }
    }

    @Nullable
    private LatLng getBestAutomaticPreviewPosition() {
        LatLng fusedPosition = sensorFusion.getCurrentFusedPosition();
        if (fusedPosition != null) {
            return fusedPosition;
        }

        float[] gnssPosition = sensorFusion.getGNSSLatitude(false);
        if (gnssPosition[0] != 0f || gnssPosition[1] != 0f) {
            return new LatLng(gnssPosition[0], gnssPosition[1]);
        }
        return null;
    }

    private void updateStartMarker(LatLng autoPosition, boolean animateCamera) {
        startPosition[0] = (float) autoPosition.latitude;
        startPosition[1] = (float) autoPosition.longitude;

        if (startMarker == null) {
            startMarker = mMap.addMarker(buildStartMarkerOptions(autoPosition));
        } else {
            startMarker.setPosition(autoPosition);
        }

        if (!manualSelectionEnabled && startMarker != null) {
            startMarker.setRotation(resolveAutoPreviewDirection(autoPosition));
        }

        if (animateCamera) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(autoPosition, 19f));
        }
    }

    private void autoSelectBuildingForPosition(LatLng autoPosition) {
        String inferredBuildingId = sensorFusion.inferBuildingIdForPosition(autoPosition);
        if (inferredBuildingId == null || inferredBuildingId.isEmpty()) {
            return;
        }

        selectedBuildingId = inferredBuildingId;
        sensorFusion.setSelectedBuildingId(inferredBuildingId);
        showFloorPlanOverlay(inferredBuildingId);
        updateBuildingInfoDisplay(inferredBuildingId);

        if (selectedPolygon != null) {
            selectedPolygon.setFillColor(FILL_COLOR_DEFAULT);
            selectedPolygon.setStrokeColor(STROKE_COLOR_DEFAULT);
        }

        Polygon polygon = buildingPolygonByName.get(inferredBuildingId);
        if (polygon != null) {
            selectedPolygon = polygon;
            polygon.setFillColor(FILL_COLOR_SELECTED);
            polygon.setStrokeColor(STROKE_COLOR_SELECTED);
        }
    }

    private MarkerOptions buildStartMarkerOptions(LatLng markerPosition) {
        MarkerOptions options = new MarkerOptions()
                .position(markerPosition)
                .title(manualSelectionEnabled ? "Start Position" : "Live Position")
                .draggable(manualSelectionEnabled);
        if (!manualSelectionEnabled) {
            options.flat(true)
                    .anchor(0.5f, 0.5f)
                    .icon(BitmapDescriptorFactory.fromBitmap(getLiveDirectionBitmap()));
        }
        return options;
    }

    private Bitmap getLiveDirectionBitmap() {
        Bitmap base = UtilFunctions.getBitmapFromVector(requireContext(), R.drawable.ic_baseline_navigation_24);
        int sizePx = Math.max(18, Math.round(
                LIVE_DIRECTION_MARKER_SIZE_DP * requireContext().getResources().getDisplayMetrics().density
        ));
        return Bitmap.createScaledBitmap(base, sizePx, sizePx, true);
    }

    private float resolveAutoPreviewDirection(@NonNull LatLng currentPosition) {
        if (previousAutoPreviewPosition != null
                && UtilFunctions.distanceBetweenPoints(previousAutoPreviewPosition, currentPosition)
                >= MIN_DIRECTION_DISTANCE_METERS) {
            lastAutoPreviewDirectionDegrees =
                    computeHeadingDegrees(previousAutoPreviewPosition, currentPosition);
        } else {
            lastAutoPreviewDirectionDegrees =
                    normalizeDegrees((float) Math.toDegrees(sensorFusion.passOrientation()));
        }
        previousAutoPreviewPosition = currentPosition;
        return lastAutoPreviewDirectionDegrees;
    }

    private int resolvePreviewFloorIndex(String buildingName,
                                         List<FloorplanApiClient.FloorShapes> floors) {
        if (floors.isEmpty()) {
            return 0;
        }

        if (!manualSelectionEnabled) {
            int preferredLogicalFloor = sensorFusion.getPreferredDisplayLogicalFloor();
            for (int i = 0; i < floors.size(); i++) {
                Integer parsedLogicalFloor = parseLogicalFloorLabel(
                        floors.get(i).getDisplayName(),
                        floors.get(i).getKey()
                );
                if (parsedLogicalFloor != null && parsedLogicalFloor == preferredLogicalFloor) {
                    return i;
                }
            }
        }

        for (int i = 0; i < floors.size(); i++) {
            Integer parsedLogicalFloor = parseLogicalFloorLabel(
                    floors.get(i).getDisplayName(),
                    floors.get(i).getKey()
            );
            if (parsedLogicalFloor != null && parsedLogicalFloor == 0) {
                return i;
            }
        }
        return Math.max(0, Math.min(floors.size() - 1, Math.min(1, floors.size() - 1)));
    }

    @Nullable
    private Integer parseLogicalFloorLabel(@Nullable String displayName, @Nullable String fallbackKey) {
        Integer parsed = parseSingleFloorLabel(displayName);
        if (parsed != null) {
            return parsed;
        }
        return parseSingleFloorLabel(fallbackKey);
    }

    @Nullable
    private Integer parseSingleFloorLabel(@Nullable String rawLabel) {
        if (rawLabel == null) {
            return null;
        }

        String normalized = rawLabel.trim().toUpperCase(Locale.UK);
        if (normalized.isEmpty()) {
            return null;
        }

        normalized = normalized
                .replace("FLOOR", "")
                .replace("LEVEL", "")
                .replace("STOREY", "")
                .replace("STORY", "")
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");

        if ("LG".equals(normalized) || "LOWGROUND".equals(normalized)
                || "LOWERGROUND".equals(normalized)) {
            return -1;
        }
        if ("G".equals(normalized) || "GF".equals(normalized) || "GROUND".equals(normalized)) {
            return 0;
        }
        if ("UG".equals(normalized) || "UPGROUND".equals(normalized)
                || "UPPERGROUND".equals(normalized)) {
            return 1;
        }
        if (normalized.startsWith("B") && normalized.length() > 1) {
            try {
                return -Integer.parseInt(normalized.substring(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (normalized.startsWith("L") && normalized.length() > 1) {
            try {
                return Integer.parseInt(normalized.substring(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private float computeHeadingDegrees(@NonNull LatLng from, @NonNull LatLng to) {
        double deltaNorth = (to.latitude - from.latitude) * 111_111d;
        double deltaEast = (to.longitude - from.longitude)
                * 111_111d
                * Math.cos(Math.toRadians((from.latitude + to.latitude) * 0.5d));
        return normalizeDegrees((float) Math.toDegrees(Math.atan2(deltaEast, deltaNorth)));
    }

    private float normalizeDegrees(float degrees) {
        float value = degrees % 360f;
        if (value < 0f) {
            value += 360f;
        }
        return value;
    }
}
