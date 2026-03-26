package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
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
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;

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
import java.util.Map;

public class StartLocationFragment extends Fragment {
    private static final String TAG = "StartLocationFragment";
    private static final int FILL_COLOR_DEFAULT = Color.argb(60, 33, 150, 243);
    private static final int STROKE_COLOR_DEFAULT = Color.argb(200, 33, 150, 243);
    private static final int FILL_COLOR_SELECTED = Color.argb(100, 33, 150, 243);
    private static final int STROKE_COLOR_SELECTED = Color.argb(255, 25, 118, 210);
    private static final int MAX_REQUEST_RETRIES = 10;
    private static final long RETRY_DELAY_MS = 2000;

    private enum HorizontalAnchor { LEFT, RIGHT, CENTER }

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

        DrawableContentInsets(double leftFraction, double topFraction, double rightFraction, double bottomFraction) {
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

    private Button button;
    private Button backToCurrentLocationButton;
    private TextView instructionText;
    private View buildingInfoCard;
    private TextView buildingNameText;
    private FloatingActionButton floorUpButton;
    private FloatingActionButton floorDownButton;
    private TextView floorLabel;

    private final SensorFusion sensorFusion = SensorFusion.getInstance();
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private final FloorplanApiClient floorplanApiClient = new FloorplanApiClient();

    private GoogleMap mMap;
    private float[] startPosition = new float[2];
    private float zoom = 19f;
    private Marker startMarker;
    private Marker currentLocationMarker;
    private String selectedBuildingId;
    private int currentFloorIndex = 0;
    private boolean showActualMapOverlays = true;
    private boolean isMarkerDraggedSelection = false;
    private boolean hasInitialCameraPositioned = false;
    private boolean followCurrentLocationWithStartMarker = true;

    private final Handler liveLocationHandler = new Handler(Looper.getMainLooper());
    private final Runnable liveLocationRunnable = new Runnable() {
        @Override
        public void run() {
            updateLiveLocation(false);
            liveLocationHandler.postDelayed(this, 1000L);
        }
    };

    private final List<Polygon> buildingPolygons = new ArrayList<>();
    private final Map<String, FloorplanApiClient.BuildingInfo> floorplanBuildingMap = new HashMap<>();
    private final List<GroundOverlay> realMapOverlays = new ArrayList<>();
    private final List<Polygon> previewPolygons = new ArrayList<>();
    private final List<Polyline> previewPolylines = new ArrayList<>();
    private Polygon whiteMaskPolygon;
    private Polygon selectedPolygon;
    private FloorplanApiClient.BuildingInfo selectedFloorplanBuilding;
    private int requestRetryCount = 0;

    public StartLocationFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity != null && activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().hide();
        }
        View rootView = inflater.inflate(R.layout.fragment_startlocation, container, false);

        startPosition = sensorFusion.getGNSSLatitude(false);
        // 【修复1】不要再用 1f 这个高空视角了，直接默认给室内级别 19f
        zoom = 19f;

        SupportMapFragment supportMapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.startMap);
        if (supportMapFragment != null) {
            supportMapFragment.getMapAsync(new OnMapReadyCallback() {
                @Override
                public void onMapReady(GoogleMap googleMap) {
                    mMap = googleMap;
                    setupMap();
                    requestBuildingDataWhenReady();
                }
            });
        }
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        button = view.findViewById(R.id.startLocationDone);
        instructionText = view.findViewById(R.id.correctionInfoView);
        ensureBackToCurrentLocationButton(view);
        floorUpButton = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        floorLabel = view.findViewById(R.id.floorLabel);

        setFloorControlsVisibility(View.GONE);

        if (floorUpButton != null) {
            floorUpButton.setOnClickListener(v -> moveFloor(true));
        }
        if (floorDownButton != null) {
            floorDownButton.setOnClickListener(v -> moveFloor(false));
        }

        button.setOnClickListener(v -> {
            float chosenLat = startPosition[0];
            float chosenLon = startPosition[1];

// 【新增】保存用户在这个界面手动调整好的完美缩放比例
            if (mMap != null && getContext() != null) {
                float userChosenZoom = mMap.getCameraPosition().zoom;
                getContext().getSharedPreferences("MapCameraState", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putFloat("user_selected_zoom", userChosenZoom)
                        .putFloat("user_start_lat", chosenLat)
                        .putFloat("user_start_lon", chosenLon)
                        .apply();
            }

            if (selectedBuildingId != null) {
                sensorFusion.setSelectedBuildingId(selectedBuildingId);
            }
            if (requireActivity() instanceof RecordingActivity) {
                sensorFusion.startRecording();
                sensorFusion.setStartGNSSLatitude(startPosition);
                sensorFusion.writeInitialMetadata();
                ((RecordingActivity) requireActivity()).showRecordingScreen();
            } else if (requireActivity() instanceof ReplayActivity) {
                ((ReplayActivity) requireActivity()).onStartLocationChosen(chosenLat, chosenLon);
            }
        });
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

    private void setupMap() {
        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        mMap.getUiSettings().setCompassEnabled(true);
        mMap.getUiSettings().setTiltGesturesEnabled(true);
        mMap.getUiSettings().setRotateGesturesEnabled(true);
        mMap.getUiSettings().setScrollGesturesEnabled(true);
        mMap.clear();

        LatLng position = new LatLng(startPosition[0], startPosition[1]);
        currentLocationMarker = mMap.addMarker(new MarkerOptions()
                .position(position)
                .title("Current Location")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                .zIndex(21f));
        startMarker = mMap.addMarker(new MarkerOptions()
                .position(position)
                .title("Selected Start")
                .draggable(true)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .zIndex(22f));

        // 【修复2】判断是否有真实定位。如果是 0.0，说明还在搜星，先不要急着锁定相机视角
        boolean gnssReady = !(startPosition[0] == 0f && startPosition[1] == 0f);
        if (!hasInitialCameraPositioned && gnssReady) {
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(position, zoom));
            hasInitialCameraPositioned = true; // 只有真实定位聚焦后，才锁定
        }


        mMap.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener() {
            @Override public void onMarkerDragStart(Marker marker) {}
            @Override public void onMarkerDrag(Marker marker) {}
            @Override
            public void onMarkerDragEnd(Marker marker) {
                if (marker == null || startMarker == null || !marker.equals(startMarker)) {
                    return;
                }
                isMarkerDraggedSelection = true;
                followCurrentLocationWithStartMarker = false;
                startPosition[0] = (float) marker.getPosition().latitude;
                startPosition[1] = (float) marker.getPosition().longitude;
                clearBuildingSelectionAndOverlays();
                requestBuildingData();
            }
        });

        mMap.setOnPolygonClickListener(polygon -> {
            String buildingName = (String) polygon.getTag();
            if (buildingName != null) {
                onBuildingSelected(buildingName, polygon);
            }
        });
    }

    private void requestBuildingDataWhenReady() {
        if (!isMarkerDraggedSelection) {
            float[] gnss = sensorFusion.getGNSSLatitude(false);
            startPosition[0] = gnss[0];
            startPosition[1] = gnss[1];
        }
        boolean gnssReady = !(startPosition[0] == 0f && startPosition[1] == 0f);
        if (!gnssReady) {
            if (requestRetryCount < MAX_REQUEST_RETRIES) {
                requestRetryCount++;
                retryHandler.postDelayed(this::requestBuildingDataWhenReady, RETRY_DELAY_MS);
                return;
            }
        }

        LatLng current = new LatLng(startPosition[0], startPosition[1]);
        if (currentLocationMarker != null) currentLocationMarker.setPosition(current);
        if (startMarker != null && followCurrentLocationWithStartMarker && !isMarkerDraggedSelection) startMarker.setPosition(current);
        // 【修复3】一旦拿到了真实定位，且还没聚焦过，立刻把镜头拉近到 19f
        if (mMap != null && gnssReady && !hasInitialCameraPositioned) {
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(current, zoom)); // zoom 已经是 19f
            hasInitialCameraPositioned = true;
        }
        requestBuildingData();
    }

    private void requestBuildingData() {
        List<String> observedMacs = new ArrayList<>();
        List<com.openpositioning.PositionMe.sensors.Wifi> wifiList = sensorFusion.getWifiList();
        if (wifiList != null) {
            for (com.openpositioning.PositionMe.sensors.Wifi wifi : wifiList) {
                String mac = wifi.getBssidString();
                if (mac != null && !mac.isEmpty()) observedMacs.add(mac);
            }
        }

        floorplanApiClient.requestFloorplan(startPosition[0], startPosition[1], observedMacs,
                new FloorplanApiClient.FloorplanCallback() {
                    @Override
                    public void onSuccess(List<FloorplanApiClient.BuildingInfo> buildings) {
                        if (!isAdded() || mMap == null) return;
                        sensorFusion.setFloorplanBuildings(buildings);
                        floorplanBuildingMap.clear();
                        for (FloorplanApiClient.BuildingInfo building : buildings) {
                            floorplanBuildingMap.put(building.getName(), building);
                        }
                        drawBuildingOutlines(buildings);
                    }

                    @Override
                    public void onFailure(String error) {
                        if (!isAdded()) return;
                        Log.e(TAG, "Floorplan API failed: " + error);
                    }
                });
    }

    private void drawBuildingOutlines(List<FloorplanApiClient.BuildingInfo> buildings) {
        for (Polygon p : buildingPolygons) p.remove();
        buildingPolygons.clear();

        for (FloorplanApiClient.BuildingInfo building : buildings) {
            List<LatLng> outlinePoints = building.getOutlinePolygon();
            if (outlinePoints == null || outlinePoints.size() < 3) continue;
            Polygon polygon = mMap.addPolygon(new PolygonOptions()
                    .addAll(outlinePoints)
                    .strokeColor(STROKE_COLOR_DEFAULT)
                    .strokeWidth(4f)
                    .fillColor(FILL_COLOR_DEFAULT)
                    .clickable(true));
            polygon.setTag(building.getName());
            buildingPolygons.add(polygon);
        }

        // Keep the current camera fixed. We only refresh the outlines and do not auto-zoom.
    }

    private void onBuildingSelected(String buildingName, Polygon polygon) {
        if (selectedPolygon != null) {
            selectedPolygon.setFillColor(FILL_COLOR_DEFAULT);
            selectedPolygon.setStrokeColor(STROKE_COLOR_DEFAULT);
        }
        selectedPolygon = polygon;
        polygon.setFillColor(FILL_COLOR_SELECTED);
        polygon.setStrokeColor(STROKE_COLOR_SELECTED);
        selectedBuildingId = buildingName;
        selectedFloorplanBuilding = floorplanBuildingMap.get(buildingName);
        currentFloorIndex = getDefaultFloorIndex(selectedFloorplanBuilding);

        LatLng center = computePolygonCenter(polygon);
        if (startMarker != null) startMarker.setPosition(center);
        followCurrentLocationWithStartMarker = false;
        isMarkerDraggedSelection = true;
        startPosition[0] = (float) center.latitude;
        startPosition[1] = (float) center.longitude;
        // Keep the current camera fixed when a building is selected.

        updateBuildingInfoDisplay(buildingName);
        redrawSelectedBuildingOverlay();
    }

    private void redrawSelectedBuildingOverlay() {
        resetMapOverlays();
        if (selectedFloorplanBuilding == null || selectedBuildingId == null) {
            setFloorControlsVisibility(View.GONE);
            return;
        }
        updateRealMapOverlay(selectedBuildingId, currentFloorIndex, true);
        setFloorControlsVisibility(View.VISIBLE);
        updateFloorLabel();
    }

    private void moveFloor(boolean moveUp) {
        if (selectedFloorplanBuilding == null) return;
        int next = getAdjacentFloorIndex(selectedFloorplanBuilding, currentFloorIndex, moveUp);
        if (next == currentFloorIndex) return;
        currentFloorIndex = next;
        redrawSelectedBuildingOverlay();
    }


    private void setFloorControlsVisibility(int visibility) {
        if (floorUpButton != null) floorUpButton.setVisibility(visibility);
        if (floorDownButton != null) floorDownButton.setVisibility(visibility);
        if (floorLabel != null) floorLabel.setVisibility(visibility);
    }

    private void updateFloorLabel() {
        if (floorLabel == null || selectedFloorplanBuilding == null) return;
        floorLabel.setText(formatFloorLabelForUi(getFloorDisplayName(selectedFloorplanBuilding, currentFloorIndex)));
    }

    private void resetMapOverlays() {
        for (Polygon p : previewPolygons) p.remove();
        for (Polyline p : previewPolylines) p.remove();
        previewPolygons.clear();
        previewPolylines.clear();
        if (whiteMaskPolygon != null) {
            whiteMaskPolygon.remove();
            whiteMaskPolygon = null;
        }
        for (GroundOverlay overlay : realMapOverlays) {
            if (overlay != null) overlay.remove();
        }
        realMapOverlays.clear();
    }

    private void clearBuildingPolygonsOnly() {
        floorplanBuildingMap.clear();
        for (Polygon p : buildingPolygons) p.remove();
        buildingPolygons.clear();
        selectedPolygon = null;
    }

    private void clearBuildingSelectionAndOverlays() {
        floorplanBuildingMap.clear();
        resetMapOverlays();
        for (Polygon p : buildingPolygons) p.remove();
        buildingPolygons.clear();
        if (selectedPolygon != null) {
            selectedPolygon.remove();
            selectedPolygon = null;
        }
        selectedBuildingId = null;
        selectedFloorplanBuilding = null;
        if (buildingInfoCard != null) buildingInfoCard.setVisibility(View.GONE);
        setFloorControlsVisibility(View.GONE);
    }

    private void updateRealMapOverlay(String buildingName, int floorIndex, boolean show) {
        if (!show || mMap == null || selectedFloorplanBuilding == null) return;

        String selectedBuildingKey = resolveKnownBuildingKey(selectedFloorplanBuilding, buildingName);
        String selectedFloorDisplayName = normalizeFloorLabel(getFloorDisplayName(selectedFloorplanBuilding, floorIndex));
        addActualMapOverlayForBuilding(selectedFloorplanBuilding, selectedBuildingKey, selectedFloorDisplayName, floorIndex);

        if (shouldShowLinkedLibraryAndNucleus(selectedBuildingKey)) {
            String linkedBuildingKey = "library".equals(selectedBuildingKey) ? "nucleus_building" : "library";
            FloorplanApiClient.BuildingInfo linkedBuilding = findBuildingByKnownKey(linkedBuildingKey);
            int linkedFloorIndex = linkedBuilding != null
                    ? findMatchingFloorIndex(linkedBuilding, selectedFloorDisplayName, floorIndex)
                    : resolveFallbackFloorIndexForKey(linkedBuildingKey, selectedFloorDisplayName, floorIndex);
            addActualMapOverlayForBuilding(linkedBuilding, linkedBuildingKey, selectedFloorDisplayName, linkedFloorIndex);
        }
    }

    private void addActualMapOverlayForBuilding(FloorplanApiClient.BuildingInfo building,
                                                String buildingKey,
                                                String requestedFloorDisplayName,
                                                int requestedFloorIndex) {
        if (mMap == null) return;
        String normalizedBuildingKey = normalizeBuildingKey(buildingKey);
        String requestedCanonicalFloor = canonicalFloorLabel(requestedFloorDisplayName);
        if ("library".equals(normalizedBuildingKey) && "LG".equals(requestedCanonicalFloor)) return;

        int resolvedFloorIndex = building != null
                ? findMatchingFloorIndex(building, requestedFloorDisplayName, requestedFloorIndex)
                : resolveFallbackFloorIndexForKey(normalizedBuildingKey, requestedFloorDisplayName, requestedFloorIndex);
        String resolvedFloorDisplayName = building != null
                ? normalizeFloorLabel(getFloorDisplayName(building, resolvedFloorIndex))
                : normalizeFloorLabel(requestedFloorDisplayName);
        String drawableFloorDisplayName = "library".equals(normalizedBuildingKey)
                ? requestedCanonicalFloor
                : resolvedFloorDisplayName;
        int drawableResId = resolveActualMapDrawable(normalizedBuildingKey, drawableFloorDisplayName, resolvedFloorIndex);
        LatLngBounds bounds = computeActualMapBounds(building, normalizedBuildingKey, drawableResId, drawableFloorDisplayName);
        if (drawableResId != 0 && bounds != null) {
            GroundOverlay overlay = mMap.addGroundOverlay(new GroundOverlayOptions()
                    .image(BitmapDescriptorFactory.fromResource(drawableResId))
                    .positionFromBounds(bounds)
                    .transparency(0.18f)
                    .zIndex(15f));
            if (overlay != null) realMapOverlays.add(overlay);
        }
    }

    private boolean shouldShowLinkedLibraryAndNucleus(String buildingKey) {
        return "library".equals(buildingKey) || "nucleus_building".equals(buildingKey);
    }

    private FloorplanApiClient.BuildingInfo findBuildingByKnownKey(String knownKey) {
        if (knownKey == null || knownKey.isEmpty()) return null;
        if (selectedFloorplanBuilding != null) {
            String selectedKey = resolveKnownBuildingKey(selectedFloorplanBuilding, selectedFloorplanBuilding.getName());
            if (knownKey.equals(selectedKey)) return selectedFloorplanBuilding;
        }
        for (FloorplanApiClient.BuildingInfo building : floorplanBuildingMap.values()) {
            String candidateKey = resolveKnownBuildingKey(building, building != null ? building.getName() : null);
            if (knownKey.equals(candidateKey)) return building;
        }
        List<FloorplanApiClient.BuildingInfo> cachedBuildings = SensorFusion.getInstance().getFloorplanBuildings();
        if (cachedBuildings != null) {
            for (FloorplanApiClient.BuildingInfo building : cachedBuildings) {
                String candidateKey = resolveKnownBuildingKey(building, building != null ? building.getName() : null);
                if (knownKey.equals(candidateKey)) return building;
            }
        }
        return null;
    }

    private int findMatchingFloorIndex(FloorplanApiClient.BuildingInfo building, String requestedFloorDisplayName, int fallbackFloorIndex) {
        if (building == null || building.getFloorShapesList() == null || building.getFloorShapesList().isEmpty()) return 0;
        String normalizedRequestedFloor = normalizeFloorLabel(requestedFloorDisplayName);
        if (!normalizedRequestedFloor.isEmpty()) {
            for (int i = 0; i < building.getFloorShapesList().size(); i++) {
                String candidateLabel = normalizeFloorLabel(building.getFloorShapesList().get(i).getDisplayName());
                if (areEquivalentFloorLabels(normalizedRequestedFloor, candidateLabel)) return i;
            }
        }
        return Math.max(0, Math.min(fallbackFloorIndex, building.getFloorShapesList().size() - 1));
    }

    private boolean areEquivalentFloorLabels(String requestedFloorLabel, String candidateFloorLabel) {
        if (requestedFloorLabel == null || candidateFloorLabel == null) return false;
        if (requestedFloorLabel.equals(candidateFloorLabel)) return true;
        String requested = canonicalFloorLabel(requestedFloorLabel);
        String candidate = canonicalFloorLabel(candidateFloorLabel);
        return !requested.isEmpty() && requested.equals(candidate);
    }

    private int resolveFallbackFloorIndexForKey(String buildingKey, String requestedFloorDisplayName, int requestedFloorIndex) {
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

    private LatLngBounds computeActualMapBounds(FloorplanApiClient.BuildingInfo building, String buildingName, int drawableResId, String floorDisplayName) {
        buildingName = normalizeBuildingKey(buildingName);
        ActualMapAlignmentConfig config = getActualMapAlignmentConfig(buildingName);
        LatLngBounds bounds = null;
        if ("library".equals(buildingName)) {
            bounds = computeFixedActualMapBounds(buildingName, drawableResId);
        } else if (building != null) {
            bounds = computeThreeEdgeAlignedBounds(building, drawableResId, config);
        } else {
            bounds = computeFixedActualMapBounds(buildingName, drawableResId);
        }
        if (bounds == null) bounds = getFallbackBuildingBounds(buildingName);
        if (bounds != null) bounds = applyDefaultCalibration(bounds, buildingName, floorDisplayName);
        return bounds;
    }

    private LatLngBounds computeFixedActualMapBounds(String buildingName, int drawableResId) {
        buildingName = normalizeBuildingKey(buildingName);
        if ("library".equals(buildingName)) {
            double widthScale = getLibraryFixedWidthScale(drawableResId);
            return buildRightAnchoredRectBounds(BuildingPolygon.LIBRARY_SW, BuildingPolygon.LIBRARY_NE, widthScale, 1.0, 0.008, 0.0);
        }
        return null;
    }

    private double getLibraryFixedWidthScale(int drawableResId) {
        return 1.000;
    }

    private LatLngBounds buildRightAnchoredRectBounds(LatLng southWest, LatLng northEast,
                                                      double widthScale, double heightScale,
                                                      double eastShiftRatio, double northShiftRatio) {
        if (southWest == null || northEast == null) return null;
        double rectWidth = northEast.longitude - southWest.longitude;
        double rectHeight = northEast.latitude - southWest.latitude;
        if (rectWidth <= 0d || rectHeight <= 0d) return null;
        double overlayWidth = rectWidth * widthScale;
        double overlayHeight = rectHeight * heightScale;
        double east = northEast.longitude + rectWidth * eastShiftRatio;
        double west = east - overlayWidth;
        double north = northEast.latitude + rectHeight * northShiftRatio;
        double south = north - overlayHeight;
        return new LatLngBounds(new LatLng(south, west), new LatLng(north, east));
    }

    private LatLngBounds computeThreeEdgeAlignedBounds(FloorplanApiClient.BuildingInfo building, int drawableResId, ActualMapAlignmentConfig config) {
        if (building == null || config == null) return null;
        List<LatLng> outline = building.getOutlinePolygon();
        if (outline == null || outline.size() < 3) return null;

        double minLat = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double minLng = Double.POSITIVE_INFINITY;
        double maxLng = Double.NEGATIVE_INFINITY;
        for (LatLng point : outline) {
            if (point == null) continue;
            minLat = Math.min(minLat, point.latitude);
            maxLat = Math.max(maxLat, point.latitude);
            minLng = Math.min(minLng, point.longitude);
            maxLng = Math.max(maxLng, point.longitude);
        }
        if (!Double.isFinite(minLat) || !Double.isFinite(maxLat) || !Double.isFinite(minLng) || !Double.isFinite(maxLng)
                || maxLat <= minLat || maxLng <= minLng) return null;

        double latSpan = maxLat - minLat;
        double lngSpan = maxLng - minLng;
        double visibleNorth = maxLat - latSpan * config.northInsetRatio;
        double visibleSouth = minLat + latSpan * config.southInsetRatio;
        if (visibleSouth >= visibleNorth) return null;

        double fullAspectRatio = getDrawableAspectRatio(drawableResId);
        if (fullAspectRatio <= 0d) return null;

        DrawableContentInsets contentInsets = getDrawableContentInsets(drawableResId, config);
        double visibleContentHeightFraction = contentInsets.contentHeightFraction();
        double visibleContentWidthFraction = contentInsets.contentWidthFraction();
        if (visibleContentHeightFraction <= 0d || visibleContentWidthFraction <= 0d) return null;

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
                double centerLng = ((minLng + maxLng) / 2.0) + (lngSpan * (config.leftVisibleInsetRatio - config.rightVisibleInsetRatio) * 0.5);
                visibleWest = centerLng - visibleContentWidthLng / 2.0;
                visibleEast = centerLng + visibleContentWidthLng / 2.0;
                break;
        }

        double overlayWest = visibleWest - totalWidthLng * contentInsets.leftFraction;
        double overlayEast = overlayWest + totalWidthLng;
        return new LatLngBounds(new LatLng(overlaySouth, overlayWest), new LatLng(overlayNorth, overlayEast));
    }

    private DrawableContentInsets getDrawableContentInsets(int drawableResId, ActualMapAlignmentConfig config) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(getResources(), drawableResId, bounds);
        return new DrawableContentInsets(config.leftVisibleInsetRatio, config.topVisibleInsetRatio,
                config.rightVisibleInsetRatio, config.bottomVisibleInsetRatio);
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
        if ("nucleus_building".equals(buildingName)) return new LatLngBounds(BuildingPolygon.NUCLEUS_SW, BuildingPolygon.NUCLEUS_NE);
        if ("library".equals(buildingName)) return new LatLngBounds(BuildingPolygon.LIBRARY_SW, BuildingPolygon.LIBRARY_NE);
        if ("murchison_house".equals(buildingName)) return new LatLngBounds(BuildingPolygon.MURCHISON_SW, BuildingPolygon.MURCHISON_NE);
        return null;
    }

    private ActualMapAlignmentConfig getActualMapAlignmentConfig(String buildingName) {
        buildingName = normalizeBuildingKey(buildingName);
        if ("nucleus_building".equals(buildingName)) {
            return new ActualMapAlignmentConfig(HorizontalAnchor.RIGHT, 0.0, 0.0, 0.0, 0.99, 0.0, 0.0, 0.0, 0.0);
        }
        if ("library".equals(buildingName)) {
            return new ActualMapAlignmentConfig(HorizontalAnchor.RIGHT, 0.0, 0.0, 0.0, 0.985, 0.0, 0.0, 0.0, 0.0);
        }
        if ("murchison_house".equals(buildingName)) {
            return new ActualMapAlignmentConfig(HorizontalAnchor.CENTER, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0);
        }
        return new ActualMapAlignmentConfig(HorizontalAnchor.RIGHT, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0);
    }

    private LatLngBounds applyDefaultCalibration(LatLngBounds baseBounds, String buildingKey, String floorDisplayName) {
        if (baseBounds == null) return null;
        OverlayCalibration calibration = getHardcodedCalibrationDefault(buildingKey, floorDisplayName);
        double baseNorth = baseBounds.northeast.latitude;
        double baseSouth = baseBounds.southwest.latitude;
        double baseEast = baseBounds.northeast.longitude;
        double baseWest = baseBounds.southwest.longitude;
        double latSpan = baseNorth - baseSouth;
        double lngSpan = baseEast - baseWest;
        if (latSpan <= 0d || lngSpan <= 0d) return baseBounds;
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
                case "G": return new OverlayCalibration(0.029f, -0.052f, 0.958f, 0.942f);
                case "1": return new OverlayCalibration(0.004f, 0.000f, 1.000f, 0.981f);
                case "2": return new OverlayCalibration(0.005f, -0.005f, 1.000f, 1.000f);
                case "3": return new OverlayCalibration(0.005f, 0.005f, 1.015f, 0.990f);
                default: return OverlayCalibration.identity();
            }
        }
        if ("library".equals(normalizedBuilding)) {
            switch (normalizedFloor) {
                case "G": return new OverlayCalibration(-0.072f, 0.018f, 0.965f, 1.098f);
                case "1": return new OverlayCalibration(-0.053f, 0.019f, 0.945f, 1.050f);
                case "2": return new OverlayCalibration(-0.075f, 0.025f, 0.950f, 1.070f);
                case "3": return new OverlayCalibration(-0.070f, 0.025f, 0.960f, 1.065f);
                default: return OverlayCalibration.identity();
            }
        }
        return OverlayCalibration.identity();
    }

    private static double clamp01(double value) {
        return Math.max(0d, Math.min(1d, value));
    }

    private String normalizeFloorLabel(String floorDisplayName) {
        if (floorDisplayName == null) return "";
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
        if (buildingName == null) return "";
        String key = buildingName.trim().toLowerCase();
        if (key.contains("nucleus") || key.contains("nuclear")) return "nucleus_building";
        if (key.contains("library") || key.contains("kenneth") || key.contains("murray")) return "library";
        if (key.contains("murchison")) return "murchison_house";
        return key;
    }

    private String resolveKnownBuildingKey(FloorplanApiClient.BuildingInfo building, String buildingName) {
        String normalized = normalizeBuildingKey(buildingName);
        if ("nucleus_building".equals(normalized) || "library".equals(normalized) || "murchison_house".equals(normalized)) {
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
        if (building == null || building.getFloorShapesList() == null || building.getFloorShapesList().isEmpty()) return 0;
        for (int i = 0; i < building.getFloorShapesList().size(); i++) {
            String displayName = building.getFloorShapesList().get(i).getDisplayName();
            if ("G".equals(canonicalFloorLabel(displayName))) return i;
        }
        List<Integer> orderedFloorIndices = getOrderedFloorIndices(building);
        return orderedFloorIndices.isEmpty() ? 0 : orderedFloorIndices.get(0);
    }

    private String getFloorDisplayName(FloorplanApiClient.BuildingInfo building, int floorIndex) {
        if (building == null || building.getFloorShapesList() == null || floorIndex < 0 || floorIndex >= building.getFloorShapesList().size()) {
            return "";
        }
        String displayName = building.getFloorShapesList().get(floorIndex).getDisplayName();
        return displayName == null ? "" : displayName.trim().toUpperCase();
    }

    private int getAdjacentFloorIndex(FloorplanApiClient.BuildingInfo building, int currentIndex, boolean moveUp) {
        List<Integer> orderedFloorIndices = getOrderedFloorIndices(building);
        if (orderedFloorIndices.isEmpty()) return currentIndex;
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
        int nextOrderedPosition = moveUp ? currentOrderedPosition + 1 : currentOrderedPosition - 1;
        if (nextOrderedPosition < 0 || nextOrderedPosition >= orderedFloorIndices.size()) return currentIndex;
        return orderedFloorIndices.get(nextOrderedPosition);
    }

    private List<Integer> getOrderedFloorIndices(FloorplanApiClient.BuildingInfo building) {
        List<Integer> ordered = new ArrayList<>();
        if (building == null || building.getFloorShapesList() == null || building.getFloorShapesList().isEmpty()) return ordered;
        List<Integer> fallback = new ArrayList<>();
        for (int i = 0; i < building.getFloorShapesList().size(); i++) fallback.add(i);
        String[] desiredOrder = new String[]{"LG", "G", "1", "2", "3"};
        for (String desiredFloor : desiredOrder) {
            for (int i = 0; i < building.getFloorShapesList().size(); i++) {
                if (ordered.contains(i)) continue;
                String candidateFloor = canonicalFloorLabel(building.getFloorShapesList().get(i).getDisplayName());
                if (desiredFloor.equals(candidateFloor)) {
                    ordered.add(i);
                    break;
                }
            }
        }
        for (Integer index : fallback) if (!ordered.contains(index)) ordered.add(index);
        return ordered;
    }

    private String formatFloorLabelForUi(String rawFloorLabel) {
        String canonicalFloor = canonicalFloorLabel(rawFloorLabel);
        switch (canonicalFloor) {
            case "LG": return "LG";
            case "G": return "G";
            case "1": return "1";
            case "2": return "2";
            case "3": return "3";
            default: return rawFloorLabel == null ? "" : rawFloorLabel;
        }
    }

    private void showFloorPlanOverlay(String buildingName) {
        for (Polygon p : previewPolygons) p.remove();
        for (Polyline p : previewPolylines) p.remove();
        previewPolygons.clear();
        previewPolylines.clear();
        if (whiteMaskPolygon != null) {
            whiteMaskPolygon.remove();
            whiteMaskPolygon = null;
        }
        PolygonOptions whiteMask = new PolygonOptions()
                .add(new LatLng(85, -180), new LatLng(85, 180), new LatLng(-85, 180), new LatLng(-85, -180))
                .fillColor(0xD9FFFFFF)
                .strokeWidth(0)
                .zIndex(5);
        whiteMaskPolygon = mMap.addPolygon(whiteMask);

        FloorplanApiClient.BuildingInfo building = floorplanBuildingMap.get(buildingName);
        if (building == null) return;
        List<FloorplanApiClient.FloorShapes> floors = building.getFloorShapesList();
        if (floors == null || floors.isEmpty()) return;
        FloorplanApiClient.FloorShapes floor = floors.get(Math.max(0, Math.min(currentFloorIndex, floors.size() - 1)));
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
                            .fillColor(getPreviewFillColor(indoorType))
                            .zIndex(10));
                    previewPolygons.add(p);
                }
            } else if ("MultiLineString".equals(geoType) || "LineString".equals(geoType)) {
                for (List<LatLng> line : feature.getParts()) {
                    if (line.size() < 2) continue;
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
        if ("wall".equals(indoorType)) return Color.argb(255, 34, 34, 34);
        if ("room".equals(indoorType)) return Color.argb(255, 60, 60, 60);
        return Color.argb(255, 50, 50, 50);
    }

    private int getPreviewFillColor(String indoorType) {
        if ("room".equals(indoorType)) return Color.argb(40, 33, 150, 243);
        return Color.TRANSPARENT;
    }

    private void updateBuildingInfoDisplay(String buildingName) {
        if (buildingInfoCard != null) buildingInfoCard.setVisibility(View.GONE);
    }

    private String formatBuildingName(String apiName) {
        if (apiName == null || apiName.isEmpty()) return "";
        String[] parts = apiName.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) sb.append(part.substring(1));
        }
        return sb.toString();
    }

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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        retryHandler.removeCallbacksAndMessages(null);
        stopLiveLocationUpdates();
        mMap = null;
        startMarker = null;
        currentLocationMarker = null;
    }

    private void ensureBackToCurrentLocationButton(@NonNull View root) {
        if (button == null || backToCurrentLocationButton != null) {
            return;
        }
        if (!(button.getParent() instanceof ConstraintLayout)) {
            return;
        }
        ConstraintLayout parent = (ConstraintLayout) button.getParent();
        backToCurrentLocationButton = new Button(requireContext());
        backToCurrentLocationButton.setId(View.generateViewId());
        backToCurrentLocationButton.setText("Back to Current Location");
        backToCurrentLocationButton.setAllCaps(false);

        int marginBottomPx = Math.round(12f * root.getResources().getDisplayMetrics().density);
        ConstraintLayout.LayoutParams params = new ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
        );
        params.bottomToTop = button.getId();
        params.startToStart = button.getId();
        params.endToEnd = button.getId();
        params.bottomMargin = marginBottomPx;
        backToCurrentLocationButton.setLayoutParams(params);
        parent.addView(backToCurrentLocationButton);

        backToCurrentLocationButton.setOnClickListener(v -> resetToCurrentLocation());
    }

    private void resetToCurrentLocation() {
        followCurrentLocationWithStartMarker = true;
        isMarkerDraggedSelection = false;
        clearBuildingSelectionAndOverlays();
        updateLiveLocation(true);
        requestBuildingDataWhenReady();
    }

    private void startLiveLocationUpdates() {
        liveLocationHandler.removeCallbacks(liveLocationRunnable);
        liveLocationHandler.post(liveLocationRunnable);
    }

    private void stopLiveLocationUpdates() {
        liveLocationHandler.removeCallbacks(liveLocationRunnable);
    }

    private void updateLiveLocation(boolean animateCamera) {
        float[] gnss = sensorFusion.getGNSSLatitude(false);
        if (gnss == null || gnss.length < 2) {
            return;
        }
        if (gnss[0] == 0f && gnss[1] == 0f) {
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
}
