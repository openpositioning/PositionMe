package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Color;
import android.os.Bundle;
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

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

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
    // --- 新增变量 ---
    // --- 新增变量 ---
    private Button btnFindIndoorMap;
    private Button btnFindActualMap;
    private boolean useActualMap = false; // 核心状态：判断显示哪种地图
    private com.google.android.gms.maps.model.GroundOverlay realMapOverlay;

    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private int requestRetryCount = 0;
    private static final int MAX_REQUEST_RETRIES = 10;
    private static final long RETRY_DELAY_MS = 2000;

    private static final String TAG = "StartLocationFragment";

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

    // Building selection state
    private String selectedBuildingId;
    private final List<Polygon> buildingPolygons = new ArrayList<>();
    private final Map<String, FloorplanApiClient.BuildingInfo> floorplanBuildingMap = new HashMap<>();
    private Polygon selectedPolygon;

    // Vector shapes drawn as floor plan preview (cleared when switching buildings)
    private final List<Polygon> previewPolygons = new ArrayList<>();
    private final List<Polyline> previewPolylines = new ArrayList<>();

    // 【新增这一行】：用于存储白色蒙版以便清除
    private Polygon whiteMaskPolygon;

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
                //requestBuildingData();
            }
        });

        return rootView;
    }
    /**
     * 清理所有地图上的覆盖物（矢量图、白板、真实贴图）
     */
    private void resetMapOverlays() {
        for (Polygon p : previewPolygons) p.remove();
        for (Polyline p : previewPolylines) p.remove();
        previewPolygons.clear();
        previewPolylines.clear();

        if (whiteMaskPolygon != null) {
            whiteMaskPolygon.remove();
            whiteMaskPolygon = null;
        }

        if (realMapOverlay != null) {
            realMapOverlay.remove();
            realMapOverlay = null;
        }
    }
    private void requestBuildingDataWhenReady() {
        float[] gnss = sensorFusion.getGNSSLatitude(false);
        startPosition[0] = gnss[0];
        startPosition[1] = gnss[1];

        boolean gnssReady = !(startPosition[0] == 0f && startPosition[1] == 0f);

        if (!gnssReady) {
            if (requestRetryCount < MAX_REQUEST_RETRIES) {
                requestRetryCount++;
                Log.d(TAG, "GNSS not ready, retry floorplan request: " + requestRetryCount);
                retryHandler.postDelayed(this::requestBuildingDataWhenReady, RETRY_DELAY_MS);
            } else {
                Log.w(TAG, "GNSS still not ready after retries, requesting floorplan anyway");
                requestBuildingData();
                return;
            }

        }

        LatLng current = new LatLng(startPosition[0], startPosition[1]);

        if (startMarker != null) {
            startMarker.setPosition(current);
        }

        if (mMap != null) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(current, zoom));
        }

        requestBuildingData();
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

                floorplanBuildingMap.clear();

                for (Polygon p : buildingPolygons) {
                    p.remove();
                }
                buildingPolygons.clear();

                for (Polygon p : previewPolygons) {
                    p.remove();
                }
                previewPolygons.clear();

                for (Polyline p : previewPolylines) {
                    p.remove();
                }
                previewPolylines.clear();
// 【新增这段】：清除上一层的白色蒙版
                if (whiteMaskPolygon != null) {
                    whiteMaskPolygon.remove();
                    whiteMaskPolygon = null;
                }
                selectedPolygon = null;
                selectedBuildingId = null;

                requestBuildingData();
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
                                instructionText.setText(R.string.noBuildingsFound);
                            }
                            return;
                        }

                        drawBuildingOutlines(buildings);
                    }

                    @Override
                    public void onFailure(String error) {
                        if (!isAdded()) return;
                        sensorFusion.setFloorplanBuildings(new ArrayList<>());
                        floorplanBuildingMap.clear();
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

        // Zoom to the building
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(center, 20f));

        // ================= 【核心逻辑修改】 =================
        // 点击建筑后，先清理屏幕上已有的图层
        resetMapOverlays();

        // 根据当前用户选中的模式，展示不同的地图
        if (useActualMap) {
            updateRealMapOverlay(buildingName, true); // 只有点击蓝框后，才贴上 drawable 里的真实图片
        } else {
            showFloorPlanOverlay(buildingName);       // 只有点击蓝框后，才弹出白板并画上黑色矢量线
        }
        // ==================================================

        Log.d(TAG, "Building selected: " + buildingName + ", Mode: " + (useActualMap ? "Actual" : "Vector"));
    }
    /**
     * 【新增方法】：用于在地图上覆盖你 drawable 文件夹中的实际室内地图图片
     */
    private void updateRealMapOverlay(String buildingName, boolean show) {
        // 先移除旧的图片蒙版
        if (realMapOverlay != null) {
            realMapOverlay.remove();
            realMapOverlay = null;
        }

        if (!show || buildingName == null) return;

        int drawableResId = 0;
        LatLngBounds bounds = null;

        // 根据建筑名称匹配图片和对应的真实经纬度边界
        if (buildingName.equals("nucleus_building")) {
            drawableResId = R.drawable.nucleus1;
            // 使用项目中已有的 BuildingPolygon 边界数据
            bounds = new LatLngBounds(
                    com.openpositioning.PositionMe.utils.BuildingPolygon.NUCLEUS_SW,
                    com.openpositioning.PositionMe.utils.BuildingPolygon.NUCLEUS_NE);
        }

        if (drawableResId != 0 && bounds != null) {
            // 将实际图片贴在地图上
            realMapOverlay = mMap.addGroundOverlay(new com.google.android.gms.maps.model.GroundOverlayOptions()
                    .image(com.google.android.gms.maps.model.BitmapDescriptorFactory.fromResource(drawableResId))
                    .positionFromBounds(bounds)
                    // zIndex设为 15，确保它显示在白色半透明蒙版(zIndex=10)上方，如果希望在黑线之上可改大
                    .zIndex(15));
        }
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

        // 【新增这段】：清除旧蒙版并绘制全新的全屏高透明度白色蒙版
        if (whiteMaskPolygon != null) {
            whiteMaskPolygon.remove();
            whiteMaskPolygon = null;
        }
        PolygonOptions whiteMask = new PolygonOptions()
                .add(new LatLng(85, -180), new LatLng(85, 180), new LatLng(-85, 180), new LatLng(-85, -180))
                .fillColor(0xD9FFFFFF) // 约 85% 透明度的白色
                .strokeWidth(0)
                .zIndex(5);            // 蒙版在底层 (层级5)
        whiteMaskPolygon = mMap.addPolygon(whiteMask);

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
                            .fillColor(getPreviewFillColor(indoorType))
                            .zIndex(10)); // 【新增】：层级设为10，确保浮在白板上方
                    previewPolygons.add(p);
                }
            } else if ("MultiLineString".equals(geoType)
                    || "LineString".equals(geoType)) {
                for (List<LatLng> line : feature.getParts()) {
                    if (line.size() < 2) continue;
                    Polyline pl = mMap.addPolyline(new PolylineOptions()
                            .addAll(line)
                            .color(getPreviewStrokeColor(indoorType))
                            .width(4f)    // 【修改】：稍微加粗至4f使其更明显
                            .zIndex(10)); // 【新增】：层级设为10，确保浮在白板上方
                    previewPolylines.add(pl);
                }
            }
        }
    }
    /**
     * Returns the stroke colour for a preview indoor feature.
     */
    private int getPreviewStrokeColor(String indoorType) {
        // 【修改】：全部换为更深的黑/深灰色
        if ("wall".equals(indoorType)) return Color.argb(255, 34, 34, 34);
        if ("room".equals(indoorType)) return Color.argb(255, 60, 60, 60);
        return Color.argb(255, 50, 50, 50);
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

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        this.button = view.findViewById(R.id.startLocationDone);
        this.instructionText = view.findViewById(R.id.correctionInfoView);

        // 绑定两个 Find 按钮
        this.btnFindIndoorMap = view.findViewById(R.id.btnFindIndoorMap);
        this.btnFindActualMap = view.findViewById(R.id.btnFindActualMap);

        if (this.btnFindIndoorMap != null) {
            this.btnFindIndoorMap.setOnClickListener(v -> {
                useActualMap = false;   // 模式设为：矢量地图
                resetMapOverlays();     // 清理之前的图层
                requestBuildingData();  // 请求画蓝框
                Toast.makeText(getContext(), "模式：矢量地图。请点击蓝色建筑", Toast.LENGTH_SHORT).show();
            });
        }

        if (this.btnFindActualMap != null) {
            this.btnFindActualMap.setOnClickListener(v -> {
                useActualMap = true;    // 模式设为：真实贴图
                resetMapOverlays();     // 清理之前的图层
                requestBuildingData();  // 请求画蓝框
                Toast.makeText(getContext(), "模式：实际地图。请点击蓝色建筑", Toast.LENGTH_SHORT).show();
            });
        }

        // 下方保留你原本的 this.button.setOnClickListener (Start/Set Location) 逻辑...
        // 下方保留你原本的 this.button.setOnClickListener (Start/Set Location) 逻辑
        this.button.setOnClickListener(v -> {
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
        super.onDestroyView();
        retryHandler.removeCallbacksAndMessages(null);
    }

}

