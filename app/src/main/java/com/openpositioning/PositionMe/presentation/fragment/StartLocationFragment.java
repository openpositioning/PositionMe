package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.BitmapFactory;
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
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.BuildingPolygon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Start-location selection fragment.
 *
 * Keeps:
 * - nearby building outline request
 * - building tap selection
 * - indoor vector preview / actual map overlay
 * - floor switching
 *
 * Adds back:
 * - draggable / tappable start marker
 * - corrected manual start anchor for recording mode
 * - clear reset flow
 */
public class StartLocationFragment extends Fragment {

    private static final String TAG = "StartLocationFragment";

    private static final int FILL_COLOR_DEFAULT = Color.argb(60, 33, 150, 243);
    private static final int STROKE_COLOR_DEFAULT = Color.argb(200, 33, 150, 243);
    private static final int FILL_COLOR_SELECTED = Color.argb(100, 33, 150, 243);
    private static final int STROKE_COLOR_SELECTED = Color.argb(255, 25, 118, 210);

    private static final int MAX_REQUEST_RETRIES = 10;
    private static final long RETRY_DELAY_MS = 2000L;

    private enum HorizontalAnchor {
        LEFT,
        RIGHT,
        CENTER
    }

    private static final class ActualMapAlignmentConfig {
        final HorizontalAnchor horizontalAnchor;
        final double northInsetRatio;
        final double southInsetRatio;
        final double horizontalInsetRatio;
        final double widthScale;
        final double topVisibleInsetRatio;
        final double bottomVisibleInsetRatio;
        final double rightVisibleInsetRatio;
        final double leftVisibleInsetRatio;

        ActualMapAlignmentConfig(HorizontalAnchor horizontalAnchor,
                                 double northInsetRatio,
                                 double southInsetRatio,
                                 double horizontalInsetRatio,
                                 double widthScale,
                                 double topVisibleInsetRatio,
                                 double bottomVisibleInsetRatio,
                                 double rightVisibleInsetRatio,
                                 double leftVisibleInsetRatio) {
            this.horizontalAnchor = horizontalAnchor;
            this.northInsetRatio = northInsetRatio;
            this.southInsetRatio = southInsetRatio;
            this.horizontalInsetRatio = horizontalInsetRatio;
            this.widthScale = widthScale;
            this.topVisibleInsetRatio = topVisibleInsetRatio;
            this.bottomVisibleInsetRatio = bottomVisibleInsetRatio;
            this.rightVisibleInsetRatio = rightVisibleInsetRatio;
            this.leftVisibleInsetRatio = leftVisibleInsetRatio;
        }
    }

    private static final class DrawableContentInsets {
        final double leftFraction;
        final double topFraction;
        final double rightFraction;
        final double bottomFraction;

        DrawableContentInsets(double leftFraction,
                              double topFraction,
                              double rightFraction,
                              double bottomFraction) {
            this.leftFraction = clamp01(leftFraction);
            this.topFraction = clamp01(topFraction);
            this.rightFraction = clamp01(rightFraction);
            this.bottomFraction = clamp01(bottomFraction);
        }

        double contentWidthFraction() {
            return Math.max(0.01d, 1d - leftFraction - rightFraction);
        }

        double contentHeightFraction() {
            return Math.max(0.01d, 1d - topFraction - bottomFraction);
        }
    }

    private static final class OverlayCalibration {
        final float shiftLatRatio;
        final float shiftLngRatio;
        final float widthScale;
        final float heightScale;

        OverlayCalibration(float shiftLatRatio, float shiftLngRatio, float widthScale, float heightScale) {
            this.shiftLatRatio = shiftLatRatio;
            this.shiftLngRatio = shiftLngRatio;
            this.widthScale = widthScale;
            this.heightScale = heightScale;
        }

        static OverlayCalibration identity() {
            return new OverlayCalibration(0f, 0f, 1f, 1f);
        }
    }

    // UI
    private Button doneButton;
    private Button btnResetStartAnchor;
    private Button btnFindIndoorMap;
    private Button btnFindActualMap;
    private TextView instructionText;
    private TextView startAnchorStatusText;
    private FloatingActionButton floorUpButton;
    private FloatingActionButton floorDownButton;
    private TextView floorLabel;

    // Core services
    private final SensorFusion sensorFusion = SensorFusion.getInstance();
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private final FloorplanApiClient floorplanApiClient = new FloorplanApiClient();

    // Map state
    @Nullable
    private GoogleMap mMap;
    @Nullable
    private Marker startMarker;
    @Nullable
    private Marker currentLocationMarker;

    private final float[] startPosition = new float[]{0f, 0f};
    private float zoom = 19f;

    // Building selection state
    @Nullable
    private String selectedBuildingId;
    private int currentFloorIndex = 0;
    private boolean showActualMapOverlays = true;
    private boolean hasInitialCameraPositioned = false;

    /**
     * When true, live GNSS keeps moving the start marker.
     * Once the user taps / drags, this becomes false.
     */
    private boolean followCurrentLocationWithStartMarker = true;

    /**
     * True after the user manually edits the start marker in this screen.
     */
    private boolean hasUserAdjustedMarker = false;

    // Building / overlay containers
    private final List<Polygon> buildingPolygons = new ArrayList<>();
    private final Map<String, FloorplanApiClient.BuildingInfo> floorplanBuildingMap = new HashMap<>();
    private final List<GroundOverlay> realMapOverlays = new ArrayList<>();
    private final List<Polygon> previewPolygons = new ArrayList<>();
    private final List<Polyline> previewPolylines = new ArrayList<>();

    @Nullable
    private Polygon whiteMaskPolygon;
    @Nullable
    private Polygon selectedPolygon;
    @Nullable
    private FloorplanApiClient.BuildingInfo selectedFloorplanBuilding;

    private int requestRetryCount = 0;

    // Live GNSS update loop
    private final Handler liveLocationHandler = new Handler(Looper.getMainLooper());
    private final Runnable liveLocationRunnable = new Runnable() {
        @Override
        public void run() {
            updateLiveLocation(false);
            liveLocationHandler.postDelayed(this, 1000L);
        }
    };

    public StartLocationFragment() {
    }

    private boolean isRecordingMode() {
        return requireActivity() instanceof RecordingActivity;
    }

    private boolean isReplayMode() {
        return requireActivity() instanceof ReplayActivity;
    }

    private void updateInstructionTextForMode() {
        if (instructionText == null) {
            return;
        }

        if (isRecordingMode()) {
            instructionText.setText(
                    "Drag the start marker to the true start point, tap a building to choose the venue, then choose the floor and confirm."
            );
        } else {
            instructionText.setText(
                    "Choose the replay start point on the map, then select the building and floor if needed."
            );
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity != null && activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().hide();
        }
        return inflater.inflate(R.layout.fragment_startlocation, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        doneButton = view.findViewById(R.id.startLocationDone);
        btnResetStartAnchor = view.findViewById(R.id.btnResetStartAnchor);
        btnFindIndoorMap = view.findViewById(R.id.btnFindIndoorMap);
        btnFindActualMap = view.findViewById(R.id.btnFindActualMap);
        instructionText = view.findViewById(R.id.correctionInfoView);
        startAnchorStatusText = view.findViewById(R.id.startAnchorStatusText);
        floorUpButton = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        floorLabel = view.findViewById(R.id.floorLabel);

        updateInstructionTextForMode();
        setFloorControlsVisibility(View.GONE);

        if (floorUpButton != null) {
            floorUpButton.setOnClickListener(v -> moveFloor(true));
        }

        if (floorDownButton != null) {
            floorDownButton.setOnClickListener(v -> moveFloor(false));
        }

        if (btnFindIndoorMap != null) {
            btnFindIndoorMap.setOnClickListener(v -> {
                showActualMapOverlays = false;
                resetMapOverlays();
                redrawSelectedBuildingOverlay();
                updateStatus("Vector preview mode selected. Tap a blue building if needed.");
            });
        }

        if (btnFindActualMap != null) {
            btnFindActualMap.setOnClickListener(v -> {
                showActualMapOverlays = true;
                resetMapOverlays();
                redrawSelectedBuildingOverlay();
                updateStatus("Actual map mode selected. Tap a blue building if needed.");
            });
        }

        if (btnResetStartAnchor != null) {
            btnResetStartAnchor.setOnClickListener(v -> resetToCurrentLocation());
        }

        if (doneButton != null) {
            doneButton.setOnClickListener(v -> confirmSelectionAndContinue());
        }

        SupportMapFragment supportMapFragment =
                (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.startMap);

        if (supportMapFragment != null) {
            supportMapFragment.getMapAsync(googleMap -> {
                mMap = googleMap;
                setupMap();
                installStartMarkerInteraction();
                requestBuildingDataWhenReady();
            });
        } else {
            updateStatus("Map fragment not found.");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        startLiveLocationUpdates();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopLiveLocationUpdates();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        retryHandler.removeCallbacksAndMessages(null);
        stopLiveLocationUpdates();
        mMap = null;
        startMarker = null;
        currentLocationMarker = null;
    }

    /**
     * Configures the map and initial markers.
     */
    private void setupMap() {
        if (mMap == null) {
            return;
        }

        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        mMap.getUiSettings().setCompassEnabled(true);
        mMap.getUiSettings().setTiltGesturesEnabled(true);
        mMap.getUiSettings().setRotateGesturesEnabled(true);
        mMap.getUiSettings().setScrollGesturesEnabled(true);
        mMap.clear();

        float[] gnss = sensorFusion.getGNSSLatitude(false);
        if (gnss != null && gnss.length >= 2) {
            startPosition[0] = gnss[0];
            startPosition[1] = gnss[1];
        }

        LatLng manualAnchor = sensorFusion.getManualStartAnchorLatLng();
        LatLng initialPosition;

        if (isValidLatLng(manualAnchor)) {
            initialPosition = manualAnchor;
            startPosition[0] = (float) manualAnchor.latitude;
            startPosition[1] = (float) manualAnchor.longitude;
            followCurrentLocationWithStartMarker = false;
            hasUserAdjustedMarker = true;
        } else if (isValidLatLon(gnss)) {
            initialPosition = new LatLng(gnss[0], gnss[1]);
            followCurrentLocationWithStartMarker = true;
            hasUserAdjustedMarker = false;
        } else {
            initialPosition = new LatLng(0, 0);
            followCurrentLocationWithStartMarker = true;
            hasUserAdjustedMarker = false;
        }

        currentLocationMarker = mMap.addMarker(new MarkerOptions()
                .position(initialPosition)
                .title("Current Location")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                .zIndex(21f));

        startMarker = mMap.addMarker(new MarkerOptions()
                .position(initialPosition)
                .title("Start Location")
                .snippet("Drag or tap the map to adjust this marker")
                .draggable(true)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .zIndex(22f));

        if (startMarker != null && isValidLatLng(manualAnchor)) {
            styleConfirmedMarker(startMarker);
            startMarker.setSnippet("Previously confirmed start anchor");
        }

        boolean cameraReady = isValidLatLng(initialPosition);
        if (cameraReady && !hasInitialCameraPositioned) {
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(initialPosition, zoom));
            hasInitialCameraPositioned = true;
        }

        mMap.setOnPolygonClickListener(polygon -> {
            String buildingName = (String) polygon.getTag();
            if (buildingName != null) {
                onBuildingSelected(buildingName, polygon);
            }
        });
    }

    /**
     * Start marker can be moved in both recording and replay mode.
     */
    private void installStartMarkerInteraction() {
        if (mMap == null) {
            return;
        }

        mMap.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener() {
            @Override
            public void onMarkerDragStart(@NonNull Marker marker) {
                if (startMarker != null && marker.equals(startMarker)) {
                    updateStatus("Dragging start marker...");
                }
            }

            @Override
            public void onMarkerDrag(@NonNull Marker marker) {
                // no-op
            }

            @Override
            public void onMarkerDragEnd(@NonNull Marker marker) {
                if (startMarker != null && marker.equals(startMarker)) {
                    onStartMarkerManuallyMoved(marker.getPosition(), false);
                }
            }
        });

        mMap.setOnMapClickListener(latLng -> {
            if (startMarker != null) {
                startMarker.setPosition(latLng);
                onStartMarkerManuallyMoved(latLng, false);
            }
        });
    }

    private void onStartMarkerManuallyMoved(@NonNull LatLng newPosition, boolean animateCamera) {
        startPosition[0] = (float) newPosition.latitude;
        startPosition[1] = (float) newPosition.longitude;
        followCurrentLocationWithStartMarker = false;
        hasUserAdjustedMarker = true;

        sensorFusion.clearManualStartAnchor();

        if (startMarker != null) {
            styleEditableMarker(startMarker);
            startMarker.setTitle("Corrected Start Location");
            startMarker.setSnippet("Confirm to use this corrected start point");
            startMarker.showInfoWindow();
        }

        if (mMap != null && animateCamera) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(newPosition, Math.max(18.5f, mMap.getCameraPosition().zoom)));
        }

        updateStatus(formatLatLng("Corrected start marker selected", newPosition));

        // Refresh nearby building outlines around the corrected point.
        requestRetryCount = 0;
        requestBuildingData();
    }

    private void confirmSelectionAndContinue() {
        if (startMarker == null) {
            Toast.makeText(getContext(), "Start marker is not ready yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        LatLng chosenLatLng = startMarker.getPosition();
        if (!isValidLatLng(chosenLatLng)) {
            Toast.makeText(getContext(), "Please wait for a valid start location.", Toast.LENGTH_SHORT).show();
            return;
        }

        startPosition[0] = (float) chosenLatLng.latitude;
        startPosition[1] = (float) chosenLatLng.longitude;

        if (selectedBuildingId != null) {
            sensorFusion.setSelectedBuildingId(selectedBuildingId);
        }

        if (isRecordingMode()) {
            Integer selectedFloorToSave = selectedFloorplanBuilding != null ? currentFloorIndex : null;

            sensorFusion.setManualStartAnchor(
                    chosenLatLng,
                    selectedFloorToSave,
                    selectedBuildingId
            );

            sensorFusion.setStartGNSSLatitude(new float[]{
                    (float) chosenLatLng.latitude,
                    (float) chosenLatLng.longitude
            });

            if (startMarker != null) {
                styleConfirmedMarker(startMarker);
                startMarker.setTitle("Confirmed Start Location");
                startMarker.setSnippet("This start anchor will be used for recording");
                startMarker.showInfoWindow();
            }

            sensorFusion.startRecording();
            ((RecordingActivity) requireActivity()).showRecordingScreen();
            return;
        }

        if (isReplayMode()) {
            ((ReplayActivity) requireActivity()).onStartLocationChosen(
                    (float)chosenLatLng.latitude,
                    (float)chosenLatLng.longitude
            );
        }
    }

    /**
     * If GNSS is not ready yet, retry a few times.
     * If the user already adjusted the marker, use that point instead.
     */
    private void requestBuildingDataWhenReady() {
        boolean currentStartReady = isValidLatLon(startPosition);

        if (!currentStartReady) {
            float[] gnss = sensorFusion.getGNSSLatitude(false);
            if (isValidLatLon(gnss)) {
                startPosition[0] = gnss[0];
                startPosition[1] = gnss[1];
                currentStartReady = true;
            }
        }

        if (!currentStartReady) {
            if (requestRetryCount < MAX_REQUEST_RETRIES) {
                requestRetryCount++;
                retryHandler.postDelayed(this::requestBuildingDataWhenReady, RETRY_DELAY_MS);
            } else {
                updateStatus("Waiting for GNSS / WiFi start estimate...");
            }
            return;
        }

        LatLng current = new LatLng(startPosition[0], startPosition[1]);

        if (currentLocationMarker != null && isValidLatLng(current)) {
            currentLocationMarker.setPosition(current);
        }

        if (startMarker != null && followCurrentLocationWithStartMarker && !hasUserAdjustedMarker) {
            startMarker.setPosition(current);
        }

        if (mMap != null && isValidLatLng(current) && !hasInitialCameraPositioned) {
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(current, zoom));
            hasInitialCameraPositioned = true;
        }

        requestBuildingData();
    }

    /**
     * Request nearby building outlines.
     */
    private void requestBuildingData() {
        if (!isValidLatLon(startPosition)) {
            return;
        }

        List<String> observedMacs = new ArrayList<>();
        List<com.openpositioning.PositionMe.sensors.Wifi> wifiList = sensorFusion.getWifiList();
        if (wifiList != null) {
            for (com.openpositioning.PositionMe.sensors.Wifi wifi : wifiList) {
                String mac = wifi.getBssidString();
                if (mac != null && !mac.isEmpty()) {
                    observedMacs.add(mac);
                }
            }
        }

        floorplanApiClient.requestFloorplan(
                startPosition[0],
                startPosition[1],
                observedMacs,
                new FloorplanApiClient.FloorplanCallback() {
                    @Override
                    public void onSuccess(List<FloorplanApiClient.BuildingInfo> buildings) {
                        if (!isAdded() || mMap == null) {
                            return;
                        }

                        requestRetryCount = 0;
                        sensorFusion.setFloorplanBuildings(buildings);
                        floorplanBuildingMap.clear();

                        if (buildings != null) {
                            for (FloorplanApiClient.BuildingInfo building : buildings) {
                                if (building != null && building.getName() != null) {
                                    floorplanBuildingMap.put(building.getName(), building);
                                }
                            }
                        }

                        drawBuildingOutlines(buildings);

                        if (buildings == null || buildings.isEmpty()) {
                            updateStatus("No buildings found nearby. You can still drag the start marker.");
                        }
                    }

                    @Override
                    public void onFailure(String error) {
                        if (!isAdded()) {
                            return;
                        }
                        Log.e(TAG, "Floorplan API failed: " + error);
                        updateStatus("Floorplan request failed: " + error);
                    }
                }
        );
    }

    /**
     * Draw nearby buildings as clickable blue polygons.
     */
    private void drawBuildingOutlines(@Nullable List<FloorplanApiClient.BuildingInfo> buildings) {
        if (mMap == null) {
            return;
        }

        for (Polygon p : buildingPolygons) {
            p.remove();
        }
        buildingPolygons.clear();

        if (buildings == null) {
            return;
        }

        for (FloorplanApiClient.BuildingInfo building : buildings) {
            List<LatLng> outlinePoints = building.getOutlinePolygon();
            if (outlinePoints == null || outlinePoints.size() < 3) {
                continue;
            }

            Polygon polygon = mMap.addPolygon(new PolygonOptions()
                    .addAll(outlinePoints)
                    .strokeColor(STROKE_COLOR_DEFAULT)
                    .strokeWidth(4f)
                    .fillColor(FILL_COLOR_DEFAULT)
                    .clickable(true));

            polygon.setTag(building.getName());
            buildingPolygons.add(polygon);
        }
    }

    /**
     * Building selection updates the selected venue/floor context.
     * It does NOT forcibly move the marker anymore.
     */
    private void onBuildingSelected(@NonNull String buildingName, @NonNull Polygon polygon) {
        if (selectedPolygon != null) {
            selectedPolygon.setFillColor(FILL_COLOR_DEFAULT);
            selectedPolygon.setStrokeColor(STROKE_COLOR_DEFAULT);
        }

        selectedPolygon = polygon;
        selectedPolygon.setFillColor(FILL_COLOR_SELECTED);
        selectedPolygon.setStrokeColor(STROKE_COLOR_SELECTED);

        selectedBuildingId = buildingName;
        selectedFloorplanBuilding = floorplanBuildingMap.get(buildingName);
        currentFloorIndex = getDefaultFloorIndex(selectedFloorplanBuilding);

        updateFloorLabel();
        redrawSelectedBuildingOverlay();

        LatLng center = computePolygonCenter(polygon);
        if (mMap != null && isValidLatLng(center)) {
            float targetZoom = Math.max(18.5f, mMap.getCameraPosition().zoom);
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(center, targetZoom));
        }

        updateStatus(
                "Selected building: " + formatBuildingName(buildingName)
                        + " | floor: " + formatFloorLabelForUi(
                        getFloorDisplayName(selectedFloorplanBuilding, currentFloorIndex))
        );
    }

    private void redrawSelectedBuildingOverlay() {
        resetMapOverlays();

        if (selectedFloorplanBuilding == null || selectedBuildingId == null) {
            setFloorControlsVisibility(View.GONE);
            return;
        }

        if (showActualMapOverlays) {
            updateRealMapOverlay(selectedBuildingId, currentFloorIndex, true);
        } else {
            showFloorPlanOverlay(selectedBuildingId);
        }

        setFloorControlsVisibility(View.VISIBLE);
        updateFloorLabel();
    }

    private void moveFloor(boolean moveUp) {
        if (selectedFloorplanBuilding == null) {
            return;
        }

        int next = getAdjacentFloorIndex(selectedFloorplanBuilding, currentFloorIndex, moveUp);
        if (next == currentFloorIndex) {
            return;
        }

        currentFloorIndex = next;
        redrawSelectedBuildingOverlay();

        updateStatus(
                "Floor changed to " + formatFloorLabelForUi(
                        getFloorDisplayName(selectedFloorplanBuilding, currentFloorIndex)
                )
        );
    }

    private void setFloorControlsVisibility(int visibility) {
        if (floorUpButton != null) floorUpButton.setVisibility(visibility);
        if (floorDownButton != null) floorDownButton.setVisibility(visibility);
        if (floorLabel != null) floorLabel.setVisibility(visibility);
    }

    private void updateFloorLabel() {
        if (floorLabel == null || selectedFloorplanBuilding == null) {
            return;
        }
        floorLabel.setText(
                formatFloorLabelForUi(getFloorDisplayName(selectedFloorplanBuilding, currentFloorIndex))
        );
    }

    private void resetMapOverlays() {
        for (Polygon p : previewPolygons) {
            p.remove();
        }
        for (Polyline p : previewPolylines) {
            p.remove();
        }
        previewPolygons.clear();
        previewPolylines.clear();

        if (whiteMaskPolygon != null) {
            whiteMaskPolygon.remove();
            whiteMaskPolygon = null;
        }

        for (GroundOverlay overlay : realMapOverlays) {
            if (overlay != null) {
                overlay.remove();
            }
        }
        realMapOverlays.clear();
    }

    private void clearBuildingSelectionAndOverlays() {
        floorplanBuildingMap.clear();
        resetMapOverlays();

        for (Polygon p : buildingPolygons) {
            p.remove();
        }
        buildingPolygons.clear();

        selectedPolygon = null;
        selectedBuildingId = null;
        selectedFloorplanBuilding = null;
        currentFloorIndex = 0;
        setFloorControlsVisibility(View.GONE);
    }

    /**
     * Reset back to the current live GNSS position and clear any manual override.
     */
    private void resetToCurrentLocation() {
        sensorFusion.clearManualStartAnchor();
        followCurrentLocationWithStartMarker = true;
        hasUserAdjustedMarker = false;

        if (startMarker != null) {
            styleEditableMarker(startMarker);
            startMarker.setTitle("Start Location");
            startMarker.setSnippet("Following current GNSS position until you adjust it");
        }

        clearBuildingSelectionAndOverlays();
        updateLiveLocation(true);
        requestRetryCount = 0;
        requestBuildingDataWhenReady();
        updateStatus("Reset to current location.");
    }

    private void startLiveLocationUpdates() {
        liveLocationHandler.removeCallbacks(liveLocationRunnable);
        liveLocationHandler.post(liveLocationRunnable);
    }

    private void stopLiveLocationUpdates() {
        liveLocationHandler.removeCallbacks(liveLocationRunnable);
    }

    /**
     * Update the current GNSS marker.
     * If marker-follow mode is on, also move the start marker.
     */
    private void updateLiveLocation(boolean animateCamera) {
        float[] gnss = sensorFusion.getGNSSLatitude(false);
        if (!isValidLatLon(gnss)) {
            return;
        }

        LatLng current = new LatLng(gnss[0], gnss[1]);

        if (currentLocationMarker != null) {
            currentLocationMarker.setPosition(current);
        }

        if (followCurrentLocationWithStartMarker && startMarker != null) {
            startMarker.setPosition(current);
            startPosition[0] = gnss[0];
            startPosition[1] = gnss[1];
        }

        if (mMap != null && animateCamera) {
            float targetZoom = Math.max(18.5f, mMap.getCameraPosition().zoom);
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(current, targetZoom));
        }
    }

    private void styleEditableMarker(@NonNull Marker marker) {
        marker.setDraggable(true);
        marker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED));
    }

    private void styleConfirmedMarker(@NonNull Marker marker) {
        marker.setDraggable(true);
        marker.setIcon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN));
    }

    private void updateStatus(@NonNull String message) {
        if (startAnchorStatusText != null) {
            startAnchorStatusText.setText(message);
        }
        Log.d(TAG, message);
    }

    @NonNull
    private String formatLatLng(@NonNull String prefix, @NonNull LatLng latLng) {
        return String.format(
                Locale.UK,
                "%s\nlat=%.6f lon=%.6f",
                prefix,
                latLng.latitude,
                latLng.longitude
        );
    }

    private boolean isValidLatLon(@Nullable float[] latLon) {
        return latLon != null
                && latLon.length >= 2
                && !(Math.abs(latLon[0]) < 1e-6 && Math.abs(latLon[1]) < 1e-6);
    }

    private boolean isValidLatLng(@Nullable LatLng latLng) {
        return latLng != null
                && !(Math.abs(latLng.latitude) < 1e-6 && Math.abs(latLng.longitude) < 1e-6);
    }

    private void updateRealMapOverlay(String buildingName, int floorIndex, boolean show) {
        if (!show || mMap == null || selectedFloorplanBuilding == null) {
            return;
        }

        String selectedBuildingKey = resolveKnownBuildingKey(selectedFloorplanBuilding, buildingName);
        String selectedFloorDisplayName = normalizeFloorLabel(
                getFloorDisplayName(selectedFloorplanBuilding, floorIndex)
        );

        addActualMapOverlayForBuilding(
                selectedFloorplanBuilding,
                selectedBuildingKey,
                selectedFloorDisplayName,
                floorIndex
        );

        if (shouldShowLinkedLibraryAndNucleus(selectedBuildingKey)) {
            String linkedBuildingKey = "library".equals(selectedBuildingKey)
                    ? "nucleus_building"
                    : "library";

            FloorplanApiClient.BuildingInfo linkedBuilding = findBuildingByKnownKey(linkedBuildingKey);
            int linkedFloorIndex = linkedBuilding != null
                    ? findMatchingFloorIndex(linkedBuilding, selectedFloorDisplayName, floorIndex)
                    : resolveFallbackFloorIndexForKey(linkedBuildingKey, selectedFloorDisplayName, floorIndex);

            addActualMapOverlayForBuilding(
                    linkedBuilding,
                    linkedBuildingKey,
                    selectedFloorDisplayName,
                    linkedFloorIndex
            );
        }
    }

    private void addActualMapOverlayForBuilding(FloorplanApiClient.BuildingInfo building,
                                                String buildingKey,
                                                String requestedFloorDisplayName,
                                                int requestedFloorIndex) {
        if (mMap == null) {
            return;
        }

        String normalizedBuildingKey = normalizeBuildingKey(buildingKey);
        String requestedCanonicalFloor = canonicalFloorLabel(requestedFloorDisplayName);

        if ("library".equals(normalizedBuildingKey) && "LG".equals(requestedCanonicalFloor)) {
            return;
        }

        int resolvedFloorIndex = building != null
                ? findMatchingFloorIndex(building, requestedFloorDisplayName, requestedFloorIndex)
                : resolveFallbackFloorIndexForKey(normalizedBuildingKey, requestedFloorDisplayName, requestedFloorIndex);

        String resolvedFloorDisplayName = building != null
                ? normalizeFloorLabel(getFloorDisplayName(building, resolvedFloorIndex))
                : normalizeFloorLabel(requestedFloorDisplayName);

        String drawableFloorDisplayName = "library".equals(normalizedBuildingKey)
                ? requestedCanonicalFloor
                : resolvedFloorDisplayName;

        int drawableResId = resolveActualMapDrawable(
                normalizedBuildingKey,
                drawableFloorDisplayName,
                resolvedFloorIndex
        );

        LatLngBounds bounds = computeActualMapBounds(
                building,
                normalizedBuildingKey,
                drawableResId,
                drawableFloorDisplayName
        );

        if (drawableResId != 0 && bounds != null) {
            GroundOverlay overlay = mMap.addGroundOverlay(new GroundOverlayOptions()
                    .image(BitmapDescriptorFactory.fromResource(drawableResId))
                    .positionFromBounds(bounds)
                    .transparency(0.18f)
                    .zIndex(15f));
            if (overlay != null) {
                realMapOverlays.add(overlay);
            }
        }
    }

    private boolean shouldShowLinkedLibraryAndNucleus(String buildingKey) {
        return "library".equals(buildingKey) || "nucleus_building".equals(buildingKey);
    }

    private FloorplanApiClient.BuildingInfo findBuildingByKnownKey(String knownKey) {
        if (knownKey == null || knownKey.isEmpty()) {
            return null;
        }

        if (selectedFloorplanBuilding != null) {
            String selectedKey = resolveKnownBuildingKey(
                    selectedFloorplanBuilding,
                    selectedFloorplanBuilding.getName()
            );
            if (knownKey.equals(selectedKey)) {
                return selectedFloorplanBuilding;
            }
        }

        for (FloorplanApiClient.BuildingInfo building : floorplanBuildingMap.values()) {
            String candidateKey = resolveKnownBuildingKey(building, building != null ? building.getName() : null);
            if (knownKey.equals(candidateKey)) {
                return building;
            }
        }

        List<FloorplanApiClient.BuildingInfo> cachedBuildings = SensorFusion.getInstance().getFloorplanBuildings();
        if (cachedBuildings != null) {
            for (FloorplanApiClient.BuildingInfo building : cachedBuildings) {
                String candidateKey = resolveKnownBuildingKey(building, building != null ? building.getName() : null);
                if (knownKey.equals(candidateKey)) {
                    return building;
                }
            }
        }

        return null;
    }

    private int findMatchingFloorIndex(FloorplanApiClient.BuildingInfo building,
                                       String requestedFloorDisplayName,
                                       int fallbackFloorIndex) {
        if (building == null || building.getFloorShapesList() == null
                || building.getFloorShapesList().isEmpty()) {
            return 0;
        }

        String normalizedRequestedFloor = normalizeFloorLabel(requestedFloorDisplayName);
        if (!normalizedRequestedFloor.isEmpty()) {
            for (int i = 0; i < building.getFloorShapesList().size(); i++) {
                String candidateLabel = normalizeFloorLabel(
                        building.getFloorShapesList().get(i).getDisplayName()
                );
                if (areEquivalentFloorLabels(normalizedRequestedFloor, candidateLabel)) {
                    return i;
                }
            }
        }

        return Math.max(0, Math.min(
                fallbackFloorIndex,
                building.getFloorShapesList().size() - 1
        ));
    }

    private boolean areEquivalentFloorLabels(String requestedFloorLabel, String candidateFloorLabel) {
        if (requestedFloorLabel == null || candidateFloorLabel == null) {
            return false;
        }
        if (requestedFloorLabel.equals(candidateFloorLabel)) {
            return true;
        }

        String requested = canonicalFloorLabel(requestedFloorLabel);
        String candidate = canonicalFloorLabel(candidateFloorLabel);
        return !requested.isEmpty() && requested.equals(candidate);
    }

    private int resolveFallbackFloorIndexForKey(String buildingKey,
                                                String requestedFloorDisplayName,
                                                int requestedFloorIndex) {
        String normalizedKey = normalizeBuildingKey(buildingKey);
        String canonicalFloor = canonicalFloorLabel(requestedFloorDisplayName);

        if ("nucleus_building".equals(normalizedKey)) {
            switch (canonicalFloor) {
                case "LG": return 0;
                case "G": return 1;
                case "1": return 2;
                case "2": return 3;
                case "3": return 4;
                default: return Math.max(0, Math.min(requestedFloorIndex, 4));
            }
        }

        if ("library".equals(normalizedKey)) {
            switch (canonicalFloor) {
                case "G": return 0;
                case "1": return 1;
                case "2": return 2;
                case "3": return 3;
                default: return Math.max(0, Math.min(requestedFloorIndex, 3));
            }
        }

        return Math.max(0, requestedFloorIndex);
    }

    private int resolveActualMapDrawable(String buildingName, String floorDisplayName, int floorIndex) {
        buildingName = normalizeBuildingKey(buildingName);
        String canonicalFloor = canonicalFloorLabel(floorDisplayName);

        if ("nucleus_building".equals(buildingName)) {
            if ("LG".equals(canonicalFloor)) return R.drawable.nucleuslg;
            if ("G".equals(canonicalFloor)) return R.drawable.nucleusg;
            if ("1".equals(canonicalFloor)) return R.drawable.nucleus1;
            if ("2".equals(canonicalFloor)) return R.drawable.nucleus2;
            if ("3".equals(canonicalFloor)) return R.drawable.nucleus3;

            switch (floorIndex) {
                case 0: return R.drawable.nucleuslg;
                case 1: return R.drawable.nucleusg;
                case 2: return R.drawable.nucleus1;
                case 3: return R.drawable.nucleus2;
                case 4: return R.drawable.nucleus3;
                default: return R.drawable.nucleusg;
            }
        }

        if ("library".equals(buildingName)) {
            if ("LG".equals(canonicalFloor)) return 0;
            if ("G".equals(canonicalFloor)) return R.drawable.libraryg;
            if ("1".equals(canonicalFloor)) return R.drawable.library1;
            if ("2".equals(canonicalFloor)) return R.drawable.library2;
            if ("3".equals(canonicalFloor)) return R.drawable.library3;

            switch (floorIndex) {
                case 0: return 0;
                case 1: return R.drawable.libraryg;
                case 2: return R.drawable.library1;
                case 3: return R.drawable.library2;
                case 4: return R.drawable.library3;
                default: return R.drawable.libraryg;
            }
        }

        return 0;
    }

    private LatLngBounds computeActualMapBounds(FloorplanApiClient.BuildingInfo building,
                                                String buildingName,
                                                int drawableResId,
                                                String floorDisplayName) {
        buildingName = normalizeBuildingKey(buildingName);
        ActualMapAlignmentConfig config = getActualMapAlignmentConfig(buildingName);

        LatLngBounds bounds;
        if ("library".equals(buildingName)) {
            bounds = computeFixedActualMapBounds(buildingName, drawableResId);
        } else if (building != null) {
            bounds = computeThreeEdgeAlignedBounds(building, drawableResId, config);
        } else {
            bounds = computeFixedActualMapBounds(buildingName, drawableResId);
        }

        if (bounds == null) {
            bounds = getFallbackBuildingBounds(buildingName);
        }
        if (bounds != null) {
            bounds = applyDefaultCalibration(bounds, buildingName, floorDisplayName);
        }
        return bounds;
    }

    private LatLngBounds computeFixedActualMapBounds(String buildingName, int drawableResId) {
        buildingName = normalizeBuildingKey(buildingName);
        if ("library".equals(buildingName)) {
            double widthScale = getLibraryFixedWidthScale(drawableResId);
            return buildRightAnchoredRectBounds(
                    BuildingPolygon.LIBRARY_SW,
                    BuildingPolygon.LIBRARY_NE,
                    widthScale,
                    1.0,
                    0.008,
                    0.0
            );
        }
        return null;
    }

    private double getLibraryFixedWidthScale(int drawableResId) {
        return 1.000;
    }

    private LatLngBounds buildRightAnchoredRectBounds(LatLng southWest,
                                                      LatLng northEast,
                                                      double widthScale,
                                                      double heightScale,
                                                      double eastShiftRatio,
                                                      double northShiftRatio) {
        if (southWest == null || northEast == null) {
            return null;
        }

        double rectWidth = northEast.longitude - southWest.longitude;
        double rectHeight = northEast.latitude - southWest.latitude;
        if (rectWidth <= 0d || rectHeight <= 0d) {
            return null;
        }

        double overlayWidth = rectWidth * widthScale;
        double overlayHeight = rectHeight * heightScale;
        double east = northEast.longitude + rectWidth * eastShiftRatio;
        double west = east - overlayWidth;
        double north = northEast.latitude + rectHeight * northShiftRatio;
        double south = north - overlayHeight;

        return new LatLngBounds(new LatLng(south, west), new LatLng(north, east));
    }

    private LatLngBounds computeThreeEdgeAlignedBounds(FloorplanApiClient.BuildingInfo building,
                                                       int drawableResId,
                                                       ActualMapAlignmentConfig config) {
        if (building == null || config == null) {
            return null;
        }

        List<LatLng> outline = building.getOutlinePolygon();
        if (outline == null || outline.size() < 3) {
            return null;
        }

        double minLat = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double minLng = Double.POSITIVE_INFINITY;
        double maxLng = Double.NEGATIVE_INFINITY;

        for (LatLng point : outline) {
            if (point == null) {
                continue;
            }
            minLat = Math.min(minLat, point.latitude);
            maxLat = Math.max(maxLat, point.latitude);
            minLng = Math.min(minLng, point.longitude);
            maxLng = Math.max(maxLng, point.longitude);
        }

        if (!Double.isFinite(minLat) || !Double.isFinite(maxLat)
                || !Double.isFinite(minLng) || !Double.isFinite(maxLng)
                || maxLat <= minLat || maxLng <= minLng) {
            return null;
        }

        double latSpan = maxLat - minLat;
        double lngSpan = maxLng - minLng;
        double visibleNorth = maxLat - latSpan * config.northInsetRatio;
        double visibleSouth = minLat + latSpan * config.southInsetRatio;

        if (visibleSouth >= visibleNorth) {
            return null;
        }

        double fullAspectRatio = getDrawableAspectRatio(drawableResId);
        if (fullAspectRatio <= 0d) {
            return null;
        }

        DrawableContentInsets contentInsets = getDrawableContentInsets(drawableResId, config);
        double visibleContentHeightFraction = contentInsets.contentHeightFraction();
        double visibleContentWidthFraction = contentInsets.contentWidthFraction();
        if (visibleContentHeightFraction <= 0d || visibleContentWidthFraction <= 0d) {
            return null;
        }

        double visibleHeightDeg = visibleNorth - visibleSouth;
        double totalHeightDeg = visibleHeightDeg / visibleContentHeightFraction;
        double overlayNorth = visibleNorth + totalHeightDeg * contentInsets.topFraction;
        double overlaySouth = overlayNorth - totalHeightDeg;

        double midLatitudeRadians = Math.toRadians((visibleNorth + visibleSouth) / 2.0);
        double metersPerDegreeLng = Math.max(111320.0 * Math.cos(midLatitudeRadians), 1.0);
        double totalHeightMeters = totalHeightDeg * 111320.0;
        double totalWidthMeters = (totalHeightMeters / fullAspectRatio) * config.widthScale;
        double totalWidthLng = totalWidthMeters / metersPerDegreeLng;
        double visibleContentWidthLng = totalWidthLng * visibleContentWidthFraction;

        double visibleEast;
        double visibleWest;
        switch (config.horizontalAnchor) {
            case RIGHT:
                visibleEast = maxLng - lngSpan * config.horizontalInsetRatio;
                visibleWest = visibleEast - visibleContentWidthLng;
                break;
            case LEFT:
                visibleWest = minLng + lngSpan * config.horizontalInsetRatio;
                visibleEast = visibleWest + visibleContentWidthLng;
                break;
            case CENTER:
            default:
                double centerLng = ((minLng + maxLng) / 2.0)
                        + (lngSpan * (config.leftVisibleInsetRatio - config.rightVisibleInsetRatio) * 0.5);
                visibleWest = centerLng - visibleContentWidthLng / 2.0;
                visibleEast = centerLng + visibleContentWidthLng / 2.0;
                break;
        }

        double overlayWest = visibleWest - totalWidthLng * contentInsets.leftFraction;
        double overlayEast = overlayWest + totalWidthLng;

        return new LatLngBounds(
                new LatLng(overlaySouth, overlayWest),
                new LatLng(overlayNorth, overlayEast)
        );
    }

    private DrawableContentInsets getDrawableContentInsets(int drawableResId,
                                                           ActualMapAlignmentConfig config) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(getResources(), drawableResId, bounds);

        return new DrawableContentInsets(
                config.leftVisibleInsetRatio,
                config.topVisibleInsetRatio,
                config.rightVisibleInsetRatio,
                config.bottomVisibleInsetRatio
        );
    }

    private double getDrawableAspectRatio(int drawableResId) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(getResources(), drawableResId, options);
            if (options.outWidth > 0 && options.outHeight > 0) {
                return (double) options.outHeight / (double) options.outWidth;
            }
        } catch (Exception ignored) {
        }
        return 1d;
    }

    private LatLngBounds getFallbackBuildingBounds(String buildingName) {
        buildingName = normalizeBuildingKey(buildingName);

        if ("nucleus_building".equals(buildingName)) {
            return new LatLngBounds(BuildingPolygon.NUCLEUS_SW, BuildingPolygon.NUCLEUS_NE);
        }
        if ("library".equals(buildingName)) {
            return new LatLngBounds(BuildingPolygon.LIBRARY_SW, BuildingPolygon.LIBRARY_NE);
        }
        if ("murchison_house".equals(buildingName)) {
            return new LatLngBounds(BuildingPolygon.MURCHISON_SW, BuildingPolygon.MURCHISON_NE);
        }
        return null;
    }

    private ActualMapAlignmentConfig getActualMapAlignmentConfig(String buildingName) {
        buildingName = normalizeBuildingKey(buildingName);

        if ("nucleus_building".equals(buildingName)) {
            return new ActualMapAlignmentConfig(
                    HorizontalAnchor.RIGHT, 0.0, 0.0, 0.0, 0.99,
                    0.0, 0.0, 0.0, 0.0
            );
        }
        if ("library".equals(buildingName)) {
            return new ActualMapAlignmentConfig(
                    HorizontalAnchor.RIGHT, 0.0, 0.0, 0.0, 0.985,
                    0.0, 0.0, 0.0, 0.0
            );
        }
        if ("murchison_house".equals(buildingName)) {
            return new ActualMapAlignmentConfig(
                    HorizontalAnchor.CENTER, 0.0, 0.0, 0.0, 1.0,
                    0.0, 0.0, 0.0, 0.0
            );
        }
        return new ActualMapAlignmentConfig(
                HorizontalAnchor.RIGHT, 0.0, 0.0, 0.0, 1.0,
                0.0, 0.0, 0.0, 0.0
        );
    }

    private LatLngBounds applyDefaultCalibration(LatLngBounds baseBounds,
                                                 String buildingKey,
                                                 String floorDisplayName) {
        if (baseBounds == null) {
            return null;
        }

        OverlayCalibration calibration = getHardcodedCalibrationDefault(buildingKey, floorDisplayName);

        double baseNorth = baseBounds.northeast.latitude;
        double baseSouth = baseBounds.southwest.latitude;
        double baseEast = baseBounds.northeast.longitude;
        double baseWest = baseBounds.southwest.longitude;

        double latSpan = baseNorth - baseSouth;
        double lngSpan = baseEast - baseWest;
        if (latSpan <= 0d || lngSpan <= 0d) {
            return baseBounds;
        }

        double centerLat = ((baseNorth + baseSouth) / 2d) + latSpan * calibration.shiftLatRatio;
        double centerLng = ((baseEast + baseWest) / 2d) + lngSpan * calibration.shiftLngRatio;
        double adjustedLatSpan = latSpan * calibration.heightScale;
        double adjustedLngSpan = lngSpan * calibration.widthScale;

        double north = centerLat + adjustedLatSpan / 2d;
        double south = centerLat - adjustedLatSpan / 2d;
        double east = centerLng + adjustedLngSpan / 2d;
        double west = centerLng - adjustedLngSpan / 2d;

        return new LatLngBounds(new LatLng(south, west), new LatLng(north, east));
    }

    private OverlayCalibration getHardcodedCalibrationDefault(String buildingKey, String floorDisplayName) {
        String normalizedBuilding = normalizeBuildingKey(buildingKey);
        String normalizedFloor = canonicalFloorLabel(floorDisplayName);

        if ("nucleus_building".equals(normalizedBuilding)) {
            switch (normalizedFloor) {
                case "LG": return new OverlayCalibration(0.012f, -0.022f, 0.967f, 0.986f);
                case "G":  return new OverlayCalibration(0.029f, -0.052f, 0.958f, 0.942f);
                case "1":  return new OverlayCalibration(0.004f, 0.000f, 1.000f, 0.981f);
                case "2":  return new OverlayCalibration(0.005f, -0.005f, 1.000f, 1.000f);
                case "3":  return new OverlayCalibration(0.005f, 0.005f, 1.015f, 0.990f);
                default:   return OverlayCalibration.identity();
            }
        }

        if ("library".equals(normalizedBuilding)) {
            switch (normalizedFloor) {
                case "G": return new OverlayCalibration(-0.072f, 0.018f, 0.965f, 1.098f);
                case "1": return new OverlayCalibration(-0.053f, 0.019f, 0.945f, 1.050f);
                case "2": return new OverlayCalibration(-0.075f, 0.025f, 0.950f, 1.070f);
                case "3": return new OverlayCalibration(-0.070f, 0.025f, 0.960f, 1.065f);
                default:  return OverlayCalibration.identity();
            }
        }

        return OverlayCalibration.identity();
    }

    private static double clamp01(double value) {
        return Math.max(0d, Math.min(1d, value));
    }

    private String normalizeFloorLabel(String floorDisplayName) {
        if (floorDisplayName == null) {
            return "";
        }
        return floorDisplayName.trim().toUpperCase().replace(" ", "");
    }

    private String canonicalFloorLabel(String floorLabel) {
        String normalized = normalizeFloorLabel(floorLabel);
        switch (normalized) {
            case "LG":
            case "LOWERGROUND":
            case "LOWERG":
            case "B1":
            case "BASEMENT1":
                return "LG";
            case "G":
            case "GF":
            case "GROUND":
            case "GROUNDFLOOR":
            case "0":
                return "G";
            case "1":
            case "F1":
            case "FIRST":
            case "FIRSTFLOOR":
                return "1";
            case "2":
            case "F2":
            case "SECOND":
            case "SECONDFLOOR":
                return "2";
            case "3":
            case "F3":
            case "THIRD":
            case "THIRDFLOOR":
                return "3";
            default:
                return normalized;
        }
    }

    private String normalizeBuildingKey(String buildingName) {
        if (buildingName == null) {
            return "";
        }
        String key = buildingName.trim().toLowerCase();
        if (key.contains("nucleus") || key.contains("nuclear")) {
            return "nucleus_building";
        }
        if (key.contains("library") || key.contains("kenneth") || key.contains("murray")) {
            return "library";
        }
        if (key.contains("murchison")) {
            return "murchison_house";
        }
        return key;
    }

    private String resolveKnownBuildingKey(FloorplanApiClient.BuildingInfo building, String buildingName) {
        String normalized = normalizeBuildingKey(buildingName);
        if ("nucleus_building".equals(normalized)
                || "library".equals(normalized)
                || "murchison_house".equals(normalized)) {
            return normalized;
        }

        LatLng center = building != null ? building.getCenter() : null;
        if (center != null) {
            if (BuildingPolygon.inLibrary(center)) return "library";
            if (BuildingPolygon.inNucleus(center)) return "nucleus_building";
            if (BuildingPolygon.inMurchison(center)) return "murchison_house";
        }

        return normalized;
    }

    private int getDefaultFloorIndex(FloorplanApiClient.BuildingInfo building) {
        if (building == null || building.getFloorShapesList() == null
                || building.getFloorShapesList().isEmpty()) {
            return 0;
        }

        for (int i = 0; i < building.getFloorShapesList().size(); i++) {
            String displayName = building.getFloorShapesList().get(i).getDisplayName();
            if ("G".equals(canonicalFloorLabel(displayName))) {
                return i;
            }
        }

        List<Integer> orderedFloorIndices = getOrderedFloorIndices(building);
        return orderedFloorIndices.isEmpty() ? 0 : orderedFloorIndices.get(0);
    }

    private String getFloorDisplayName(FloorplanApiClient.BuildingInfo building, int floorIndex) {
        if (building == null || building.getFloorShapesList() == null
                || floorIndex < 0 || floorIndex >= building.getFloorShapesList().size()) {
            return "";
        }
        String displayName = building.getFloorShapesList().get(floorIndex).getDisplayName();
        return displayName == null ? "" : displayName.trim().toUpperCase();
    }

    private int getAdjacentFloorIndex(FloorplanApiClient.BuildingInfo building,
                                      int currentIndex,
                                      boolean moveUp) {
        List<Integer> orderedFloorIndices = getOrderedFloorIndices(building);
        if (orderedFloorIndices.isEmpty()) {
            return currentIndex;
        }

        int currentOrderedPosition = orderedFloorIndices.indexOf(currentIndex);
        if (currentOrderedPosition < 0) {
            currentOrderedPosition = 0;
            for (int i = 0; i < orderedFloorIndices.size(); i++) {
                if (orderedFloorIndices.get(i) >= currentIndex) {
                    currentOrderedPosition = i;
                    break;
                }
            }
        }

        int nextOrderedPosition = moveUp
                ? currentOrderedPosition + 1
                : currentOrderedPosition - 1;

        if (nextOrderedPosition < 0 || nextOrderedPosition >= orderedFloorIndices.size()) {
            return currentIndex;
        }

        return orderedFloorIndices.get(nextOrderedPosition);
    }

    private List<Integer> getOrderedFloorIndices(FloorplanApiClient.BuildingInfo building) {
        List<Integer> ordered = new ArrayList<>();
        if (building == null || building.getFloorShapesList() == null
                || building.getFloorShapesList().isEmpty()) {
            return ordered;
        }

        List<Integer> fallback = new ArrayList<>();
        for (int i = 0; i < building.getFloorShapesList().size(); i++) {
            fallback.add(i);
        }

        String[] desiredOrder = new String[]{"LG", "G", "1", "2", "3"};
        for (String desiredFloor : desiredOrder) {
            for (int i = 0; i < building.getFloorShapesList().size(); i++) {
                if (ordered.contains(i)) {
                    continue;
                }
                String candidateFloor = canonicalFloorLabel(
                        building.getFloorShapesList().get(i).getDisplayName()
                );
                if (desiredFloor.equals(candidateFloor)) {
                    ordered.add(i);
                    break;
                }
            }
        }

        for (Integer index : fallback) {
            if (!ordered.contains(index)) {
                ordered.add(index);
            }
        }
        return ordered;
    }

    private String formatFloorLabelForUi(String rawFloorLabel) {
        String canonicalFloor = canonicalFloorLabel(rawFloorLabel);
        switch (canonicalFloor) {
            case "LG": return "LG";
            case "G":  return "G";
            case "1":  return "1";
            case "2":  return "2";
            case "3":  return "3";
            default:   return rawFloorLabel == null ? "" : rawFloorLabel;
        }
    }

    private void showFloorPlanOverlay(String buildingName) {
        for (Polygon p : previewPolygons) {
            p.remove();
        }
        for (Polyline p : previewPolylines) {
            p.remove();
        }
        previewPolygons.clear();
        previewPolylines.clear();

        if (whiteMaskPolygon != null) {
            whiteMaskPolygon.remove();
            whiteMaskPolygon = null;
        }

        if (mMap == null) {
            return;
        }

        PolygonOptions whiteMask = new PolygonOptions()
                .add(
                        new LatLng(85, -180),
                        new LatLng(85, 180),
                        new LatLng(-85, 180),
                        new LatLng(-85, -180)
                )
                .fillColor(0xD9FFFFFF)
                .strokeWidth(0)
                .zIndex(5);

        whiteMaskPolygon = mMap.addPolygon(whiteMask);

        FloorplanApiClient.BuildingInfo building = floorplanBuildingMap.get(buildingName);
        if (building == null) {
            return;
        }

        List<FloorplanApiClient.FloorShapes> floors = building.getFloorShapesList();
        if (floors == null || floors.isEmpty()) {
            return;
        }

        FloorplanApiClient.FloorShapes floor =
                floors.get(Math.max(0, Math.min(currentFloorIndex, floors.size() - 1)));

        for (FloorplanApiClient.MapShapeFeature feature : floor.getFeatures()) {
            String geoType = feature.getGeometryType();
            String indoorType = feature.getIndoorType();

            if ("MultiPolygon".equals(geoType) || "Polygon".equals(geoType)) {
                for (List<LatLng> ring : feature.getParts()) {
                    if (ring.size() < 3) {
                        continue;
                    }
                    Polygon p = mMap.addPolygon(new PolygonOptions()
                            .addAll(ring)
                            .strokeColor(getPreviewStrokeColor(indoorType))
                            .strokeWidth(2f)
                            .fillColor(getPreviewFillColor(indoorType))
                            .zIndex(10));
                    previewPolygons.add(p);
                }
            } else if ("MultiLineString".equals(geoType) || "LineString".equals(geoType)) {
                for (List<LatLng> line : feature.getParts()) {
                    if (line.size() < 2) {
                        continue;
                    }
                    Polyline pl = mMap.addPolyline(new PolylineOptions()
                            .addAll(line)
                            .color(getPreviewStrokeColor(indoorType))
                            .width(4f)
                            .zIndex(10));
                    previewPolylines.add(pl);
                }
            }
        }
    }

    private int getPreviewStrokeColor(String indoorType) {
        if ("wall".equals(indoorType)) {
            return Color.argb(255, 34, 34, 34);
        }
        if ("room".equals(indoorType)) {
            return Color.argb(255, 60, 60, 60);
        }
        return Color.argb(255, 50, 50, 50);
    }

    private int getPreviewFillColor(String indoorType) {
        if ("room".equals(indoorType)) {
            return Color.argb(40, 33, 150, 243);
        }
        return Color.TRANSPARENT;
    }

    private String formatBuildingName(String apiName) {
        if (apiName == null || apiName.isEmpty()) {
            return "";
        }

        String[] parts = apiName.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    private LatLng computePolygonCenter(Polygon polygon) {
        List<LatLng> points = polygon.getPoints();
        double latSum = 0;
        double lonSum = 0;
        int count = 0;

        for (LatLng p : points) {
            latSum += p.latitude;
            lonSum += p.longitude;
            count++;
        }

        if (count == 0) {
            return new LatLng(0, 0);
        }
        return new LatLng(latSum / count, lonSum / count);
    }
}