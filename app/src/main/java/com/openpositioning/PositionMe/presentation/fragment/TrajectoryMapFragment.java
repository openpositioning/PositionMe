package com.openpositioning.PositionMe.presentation.fragment;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.InputType;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.display.DisplayObservationType;
import com.openpositioning.PositionMe.presentation.display.ExponentialLatLngSmoother;
import com.openpositioning.PositionMe.presentation.display.MapControlPanelController;
import com.openpositioning.PositionMe.presentation.display.ObservationMarkerFactory;
import com.openpositioning.PositionMe.utils.BuildingMapController;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * New code guide:
 * 1. Observation marker queues and smoothing controls.
 * 2. Indoor map floor sync with SensorFusion.
 * 3. Fused trajectory rendering and compaction.
 * 4. Numbered test-point markers and reset helpers.
 */
public class TrajectoryMapFragment extends Fragment implements OnMapReadyCallback {
    private static final int DEFAULT_MAX_OBSERVATION_MARKERS = 3;
    private static final long FLOOR_SYNC_MIN_INTERVAL_MS = 250L;
    private static final double TRAJECTORY_RENDER_MIN_DISTANCE_METERS = 0.08;
    private static final int MAX_TRAJECTORY_RENDER_POINTS = 2400;
    private static final int TARGET_TRAJECTORY_RENDER_POINTS = 1600;
    private static final int TRAJECTORY_RECENT_TAIL_POINTS = 160;
    private static final int MAX_PENDING_OBSERVATION_UPDATES = 48;
    private static final int MAX_OBSERVATION_UPDATES_PER_FLUSH = 8;
    private static final float OBSERVATION_HISTORY_MIN_ALPHA = 0.28f;

    private GoogleMap gMap;
    private LatLng currentLocation;
    private Marker orientationMarker;
    private Marker bestEstimateDotMarker;
    private Marker gnssMarker;
    private Polyline fusedPolyline;
    private boolean isRed = true;
    private boolean isGnssOn = true;

    private LatLng pendingCameraPosition = null;
    private boolean hasPendingCameraMove = false;

    private BuildingMapController mapController;

    private Spinner switchMapSpinner;
    private Spinner observationCountSpinner;
    private View mapControlList;
    private ImageButton mapControlToggleButton;
    private SwitchMaterial gnssSwitch;
    private SwitchMaterial wifiObservationSwitch;
    private SwitchMaterial pdrObservationSwitch;
    private SwitchMaterial autoFloorSwitch;
    private SwitchMaterial smoothDisplaySwitch;

    private FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;
    private TextView currentFloorIndicator;
    private View floorControlCard;

    // --- Auto Floor Logic ---
    private static final boolean DEFAULT_SMOOTH_DISPLAY_ENABLED = true;
    private boolean isAutoFloorEnabled = true;
    private boolean smoothDisplayEnabled = DEFAULT_SMOOTH_DISPLAY_ENABLED;
    private int currentFloorValue = 0;
    private int manualFloorOffset = 0;
    private static final double FLOOR_HEIGHT_STEP = 4.0;

    private static final int MIN_FLOOR_VAL = -1; // BF
    private static final int MAX_FLOOR_VAL = 3;  // 3F
    private int maxObservationMarkers = DEFAULT_MAX_OBSERVATION_MARKERS;
    private boolean isWifiObservationOn = true;
    private boolean isPdrObservationOn = true;
    // Keeps recent observation markers bounded so the map stays readable.
    private final ArrayDeque<Marker> gnssObservationMarkers = new ArrayDeque<>();
    private final ArrayDeque<Marker> wifiObservationMarkers = new ArrayDeque<>();
    private final ArrayDeque<Marker> pdrObservationMarkers = new ArrayDeque<>();
    private final ArrayDeque<PendingObservationMarker> pendingObservationUpdates = new ArrayDeque<>();
    private final List<LatLng> fusedPathPoints = new ArrayList<>();
    private final List<LatLng> pendingTagLocations = new ArrayList<>();
    private final List<Integer> pendingTagIndices = new ArrayList<>();
    private final SparseArray<BitmapDescriptor> numberedTagIconCache = new SparseArray<>();
    private ObservationMarkerFactory observationMarkerFactory;
    private MapControlPanelController mapControlPanelController;
    private final ExponentialLatLngSmoother replaySmoother = new ExponentialLatLngSmoother(0.65); // was 0.25 — matched to DataDisplayController smoother (0.78) for consistent smooth behaviour
    private boolean floorSelectionSyncArmed = false;
    private boolean sensorFloorSyncInProgress = false;
    private long lastFloorSyncAttemptTimeMs = 0L;
    private int lastRequestedSensorFloor = Integer.MIN_VALUE;

    private static final class PendingObservationMarker {
        private final DisplayObservationType type;
        private final LatLng point;

        private PendingObservationMarker(@NonNull DisplayObservationType type, @NonNull LatLng point) {
            this.type = type;
            this.point = point;
        }
    }

    // Creates cached numbered icons for assignment test-point markers.
    private BitmapDescriptor createNumberedMarkerIcon(int number) {
        BitmapDescriptor cached = numberedTagIconCache.get(number);
        if (cached != null) {
            return cached;
        }
        final int size = 56;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setStyle(Paint.Style.FILL);
        circlePaint.setColor(0xFF2196F3);

        float cx = size / 2f;
        float cy = size / 2f;
        float r = size / 2f;
        canvas.drawCircle(cx, cy, r, circlePaint);

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(3f);
        strokePaint.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, r - 3f, strokePaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(25f);

        String text = String.valueOf(number);
        Rect textBounds = new Rect();
        textPaint.getTextBounds(text, 0, text.length(), textBounds);
        float textY = cy + textBounds.height() / 2f;
        canvas.drawText(text, cx, textY, textPaint);

        BitmapDescriptor descriptor = BitmapDescriptorFactory.fromBitmap(bitmap);
        numberedTagIconCache.put(number, descriptor);
        return descriptor;
    }

    public TrajectoryMapFragment() {}

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

        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        observationCountSpinner = view.findViewById(R.id.observationCountSpinner);
        mapControlList = view.findViewById(R.id.mapControlList);
        mapControlToggleButton = view.findViewById(R.id.mapControlToggleButton);
        gnssSwitch      = view.findViewById(R.id.gnssSwitch);
        wifiObservationSwitch = view.findViewById(R.id.wifiObservationSwitch);
        pdrObservationSwitch = view.findViewById(R.id.pdrObservationSwitch);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        smoothDisplaySwitch = view.findViewById(R.id.smoothDisplaySwitch);
        floorUpButton   = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);
        floorControlCard = view.findViewById(R.id.floorControlCard);
        currentFloorIndicator = view.findViewById(R.id.currentFloorIndicator);
        observationMarkerFactory = new ObservationMarkerFactory(requireContext());
        if (mapControlList != null && mapControlToggleButton != null) {
            mapControlPanelController = new MapControlPanelController(mapControlList, mapControlToggleButton);
            mapControlPanelController.setCollapsed(true);
        }

        currentFloorValue = 0;
        updateFloorIndicatorUI(0);
        updateManualFloorControlsVisibility();

        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.trajectoryMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        initMapTypeSpinner();
        initObservationCountSpinner();

        if (gnssSwitch != null) {
            gnssSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                isGnssOn = isChecked;
                if (gnssMarker != null) {
                    gnssMarker.setVisible(isChecked);
                }
                setMarkerCollectionVisible(gnssObservationMarkers, isChecked);
            });
        }

        if (wifiObservationSwitch != null) {
            wifiObservationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                isWifiObservationOn = isChecked;
                setMarkerCollectionVisible(wifiObservationMarkers, isChecked);
            });
        }

        if (pdrObservationSwitch != null) {
            pdrObservationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                isPdrObservationOn = isChecked;
                setMarkerCollectionVisible(pdrObservationMarkers, isChecked);
            });
        }

        if (smoothDisplaySwitch != null) {
            smoothDisplaySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                smoothDisplayEnabled = isChecked;
                if (!isChecked) {
                    replaySmoother.reset();
                }
            });
            smoothDisplaySwitch.setChecked(smoothDisplayEnabled);
        }

        if (switchColorButton != null) {
            switchColorButton.setOnClickListener(v -> {
                if (fusedPolyline != null) {
                    if (isRed) {
                        switchColorButton.setBackgroundColor(Color.BLACK);
                        fusedPolyline.setColor(Color.BLACK);
                        isRed = false;
                    } else {
                        switchColorButton.setBackgroundColor(Color.RED);
                        fusedPolyline.setColor(Color.RED);
                        isRed = true;
                    }
                }
            });
        }

        if (autoFloorSwitch != null) {
            autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
                isAutoFloorEnabled = isChecked;
                if (isChecked) {
                    Toast.makeText(requireContext(), "Auto Floor: ON", Toast.LENGTH_SHORT).show();
                    manualFloorOffset = 0;
                } else {
                    com.openpositioning.PositionMe.sensors.SensorFusion
                            .getInstance()
                            .consumePendingFloorDelta();
                }
                updateManualFloorControlsVisibility();
            });
            autoFloorSwitch.setChecked(true);
        }

        if (floorUpButton != null) {
            floorUpButton.setOnClickListener(v -> {
                if (mapController != null) {
                    floorSelectionSyncArmed = true;
                    manualFloorOffset++;
                    mapController.changeFloor(1);
                }
            });
        }

        if (floorDownButton != null) {
            floorDownButton.setOnClickListener(v -> {
                if (mapController != null) {
                    floorSelectionSyncArmed = true;
                    manualFloorOffset--;
                    mapController.changeFloor(-1);
                }
            });
        }
    }

    public void updateElevation() {
        if (!isAutoFloorEnabled || mapController == null) {
            return;
        }
        syncMapFloorToSensorFusion(false);
    }

    private String getFloorLabel(int val) {
        if (val == -1) return "BF";
        if (val == 0) return "GF";
        return val + "F";
    }

    private void updateFloorIndicatorUI(int floorVal) {
        if (currentFloorIndicator != null) {
            String label = getFloorLabel(floorVal);
            currentFloorIndicator.setText("Floor: " + label);
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        gMap = googleMap;
        initMapSettings(gMap);

        mapController = new BuildingMapController(requireContext(), gMap);

        mapController.setSelectionListener((buildingName, floorCode) -> {
            updateManualFloorControlsVisibility();

            int val = parseFloorCode(floorCode);
            currentFloorValue = val;
            updateFloorIndicatorUI(val);

            if (!isAutoFloorEnabled) {
                manualFloorOffset = val;
            }

            // Keep SensorFusion floor state aligned with the selected map floor.
            if (mapController != null) {
                com.openpositioning.PositionMe.sensors.SensorFusion sensorFusion =
                        com.openpositioning.PositionMe.sensors.SensorFusion.getInstance();
                sensorFusion
                        .setAvailableFloorPlans(mapController.getSelectedFloorPlanMap());
                sensorFusion
                        .setCurrentFloorPlan(mapController.getCurrentFloorPlan());
                if (floorSelectionSyncArmed) {
                    sensorFusion
                            .setCurrentFloorByMapMatching(val);
                }
                floorSelectionSyncArmed = false;
                if (!sensorFloorSyncInProgress && isAutoFloorEnabled) {
                    syncMapFloorToSensorFusion(true);
                }
            }
        });

        gMap.setOnPolygonClickListener(polygon -> mapController.onPolygonClick(polygon));

        if (hasPendingCameraMove && pendingCameraPosition != null) {
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
            hasPendingCameraMove = false;
            if (mapController != null) {
                mapController.downloadNearbyBuildings(pendingCameraPosition);
            }
            pendingCameraPosition = null;
        } else {
            LatLng defaultLoc = new LatLng(55.92330, -3.17450);
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLoc, 18f));
            if (mapController != null) {
                mapController.downloadNearbyBuildings(defaultLoc);
            }
        }
        flushPendingTags();
        flushPendingObservationMarkers();
    }

    // Normalizes API floor labels into the local integer floor model.
    private int parseFloorCode(String code) {
        if (code == null) return 0;
        String raw = code.toUpperCase().trim();

        if (raw.equals("BF") || raw.equals("B")) return -1;
        if (raw.equals("GF") || raw.equals("G") || raw.equals("0") || raw.contains("GROUND")) return 0;
        if (raw.contains("BASEMENT") || raw.contains("BF")) return -1;

        if (raw.startsWith("B")) {
            String digits = raw.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                try {
                    return -Integer.parseInt(digits);
                } catch (Exception ignored) {
                }
            }
            return -1;
        }

        try {
            Matcher matcher = Pattern.compile("-?\\d+").matcher(raw);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group());
            }
        } catch (Exception ignored) {
        }

        return 0;
    }

    private void initMapSettings(GoogleMap map) {
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        fusedPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(5f)
                .zIndex(200f)
                .add()
        );
        fusedPathPoints.clear();
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
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
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
    private void initObservationCountSpinner() {
        if (observationCountSpinner == null) return;
        String[] countOptions = new String[]{"1", "2", "3", getString(R.string.custom_observation_count)};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                countOptions
        );
        observationCountSpinner.setAdapter(adapter);
        observationCountSpinner.setSelection(2, false);

        observationCountSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position <= 2) {
                    setMaxObservationMarkers(position + 1);
                } else {
                    showCustomObservationCountDialog();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }
    private void showCustomObservationCountDialog() {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(getString(R.string.custom_observation_count_hint));
        input.setText(String.valueOf(maxObservationMarkers));

        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.custom_observation_count_title))
                .setView(input)
                .setPositiveButton(getString(R.string.ok), (dialog, which) -> {
                    String value = input.getText() == null ? "" : input.getText().toString().trim();
                    if (value.isEmpty()) return;
                    try {
                        int parsed = Integer.parseInt(value);
                        if (parsed <= 0) {
                            Toast.makeText(requireContext(), getString(R.string.custom_observation_count_invalid), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        setMaxObservationMarkers(parsed);
                    } catch (NumberFormatException e) {
                        Toast.makeText(requireContext(), getString(R.string.custom_observation_count_invalid), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(getString(R.string.cancel), (dialog, which) -> dialog.dismiss())
                .show();
    }
    private void setMaxObservationMarkers(int count) {
        maxObservationMarkers = count;
        trimObservationQueue(gnssObservationMarkers, getMaxObservationMarkersForType(DisplayObservationType.GNSS));
        trimObservationQueue(wifiObservationMarkers, getMaxObservationMarkersForType(DisplayObservationType.WIFI));
        trimObservationQueue(pdrObservationMarkers, getMaxObservationMarkersForType(DisplayObservationType.PDR));
        refreshObservationQueueStyle(DisplayObservationType.GNSS, gnssObservationMarkers);
        refreshObservationQueueStyle(DisplayObservationType.WIFI, wifiObservationMarkers);
        refreshObservationQueueStyle(DisplayObservationType.PDR, pdrObservationMarkers);
    }
    private void trimObservationQueue(ArrayDeque<Marker> queue, int limit) {
        while (queue.size() > limit) {
            Marker oldest = queue.removeFirst();
            if (oldest != null) oldest.remove();
        }
    }

    private int getMaxObservationMarkersForType(@NonNull DisplayObservationType type) {
        return maxObservationMarkers;
    }

    // Draws the current fused position and the heading marker used in live display.
    public void renderFusedPosition(@NonNull LatLng newLocation, float orientation, boolean moveCamera) {
        if (gMap == null || observationMarkerFactory == null) return;

        this.currentLocation = newLocation;
        float normalizedOrientation = normalizeMarkerRotationDegrees(orientation);

        if (orientationMarker == null) {
            orientationMarker = gMap.addMarker(new MarkerOptions()
                    .position(newLocation)
                    .flat(true)
                    .anchor(0.5f, 0.5f)
                    .title("Best Estimate")
                    .zIndex(300f)
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            UtilFunctions.getBitmapFromVector(requireContext(),
                                    R.drawable.ic_baseline_navigation_24)))
            );
            if (orientationMarker != null) {
                orientationMarker.setRotation(normalizedOrientation);
            }
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
        } else {
            orientationMarker.setPosition(newLocation);
            orientationMarker.setRotation(normalizedOrientation);
            if (moveCamera) {
                gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
            }
        }
        if (observationMarkerFactory != null) {
            if (bestEstimateDotMarker == null) {
                bestEstimateDotMarker = gMap.addMarker(new MarkerOptions()
                        .position(newLocation)
                        .anchor(0.5f, 0.5f)
                        .zIndex(320f)
                        .icon(observationMarkerFactory.getBestEstimateDotIcon()));
            } else {
                bestEstimateDotMarker.setPosition(newLocation);
            }
        }
        // Assignment 2 requires API map rendering only; local indoor overlays are disabled.
    }

    // Backward-compatible wrapper for existing callers
    public void updateUserLocation(@NonNull LatLng newLocation, float orientation) {
        LatLng displayPoint = newLocation;
        if (smoothDisplayEnabled) {
            displayPoint = replaySmoother.filter(newLocation);
        } else {
            replaySmoother.reset(newLocation);
        }
        renderFusedPosition(displayPoint, orientation, true);
        appendFusedTrajectoryPoint(displayPoint);
    }
    public void appendFusedTrajectoryPoint(@NonNull LatLng point) {
        if (gMap == null || fusedPolyline == null) return;
        if (fusedPathPoints.isEmpty()) {
            fusedPathPoints.add(point);
            fusedPolyline.setPoints(fusedPathPoints);
            return;
        }
        LatLng lastPoint = fusedPathPoints.get(fusedPathPoints.size() - 1);
        if (UtilFunctions.distanceBetweenPoints(lastPoint, point) < TRAJECTORY_RENDER_MIN_DISTANCE_METERS) {
            fusedPathPoints.set(fusedPathPoints.size() - 1, point);
        } else {
            fusedPathPoints.add(point);
            compactTrajectoryIfNeeded();
        }
        fusedPolyline.setPoints(fusedPathPoints);
    }

    private void compactTrajectoryIfNeeded() {
        int currentSize = fusedPathPoints.size();
        if (currentSize <= MAX_TRAJECTORY_RENDER_POINTS) {
            return;
        }
        int overflow = currentSize - TARGET_TRAJECTORY_RENDER_POINTS;
        if (overflow <= 0) {
            return;
        }
        int removable = Math.max(0, fusedPathPoints.size() - TRAJECTORY_RECENT_TAIL_POINTS - 1);
        int removeCount = Math.min(overflow, removable);
        if (removeCount <= 0) {
            return;
        }
        fusedPathPoints.subList(0, removeCount).clear();
    }

    public void addTagPoint(@NonNull LatLng latLng, int index) {
        if (gMap == null) {
            pendingTagLocations.add(latLng);
            pendingTagIndices.add(index);
            return;
        }
        gMap.addMarker(new MarkerOptions()
                .position(latLng)
                .anchor(0.5f, 0.5f)
                .zIndex(300f)
                .icon(createNumberedMarkerIcon(index)));
    }

    // Buffers marker updates so frequent sensor callbacks do not overload the UI thread.
    public void enqueueObservationMarker(@NonNull DisplayObservationType type, @NonNull LatLng point) {
        if (pendingObservationUpdates.size() >= MAX_PENDING_OBSERVATION_UPDATES) {
            pendingObservationUpdates.removeFirst();
        }
        pendingObservationUpdates.addLast(new PendingObservationMarker(type, point));
    }

    public void addObservationMarker(@NonNull DisplayObservationType type, @NonNull LatLng point) {
        enqueueObservationMarker(type, point);
        flushPendingObservationMarkers();
    }

    public void flushPendingObservationMarkers() {
        if (gMap == null || observationMarkerFactory == null || pendingObservationUpdates.isEmpty()) {
            return;
        }
        int processed = 0;
        while (!pendingObservationUpdates.isEmpty() && processed < MAX_OBSERVATION_UPDATES_PER_FLUSH) {
            PendingObservationMarker pendingMarker = pendingObservationUpdates.removeFirst();
            applyObservationMarker(pendingMarker.type, pendingMarker.point);
            processed++;
        }
    }

    private void applyObservationMarker(@NonNull DisplayObservationType type, @NonNull LatLng point) {
        ArrayDeque<Marker> targetQueue = getObservationQueue(type);
        Marker marker = obtainReusableObservationMarker(type, point, targetQueue);
        if (marker != null) {
            targetQueue.addLast(marker);
            refreshObservationQueueStyle(type, targetQueue);
        }

        if (type == DisplayObservationType.GNSS) {
            if (gnssMarker == null) {
                gnssMarker = gMap.addMarker(new MarkerOptions()
                        .position(point)
                        .title("Latest GNSS")
                        .zIndex(260f)
                        .visible(isGnssOn)
                        .icon(observationMarkerFactory.getObservationIcon(DisplayObservationType.GNSS)));
            } else {
                gnssMarker.setPosition(point);
                gnssMarker.setVisible(isGnssOn);
            }
        }
    }

    private void refreshObservationQueueStyle(@NonNull DisplayObservationType type,
                                              @NonNull ArrayDeque<Marker> targetQueue) {
        int size = targetQueue.size();
        if (size <= 0) {
            return;
        }
        int index = 0;
        for (Marker queuedMarker : targetQueue) {
            if (queuedMarker == null) {
                index++;
                continue;
            }
            float progress = size <= 1 ? 1f : (index + 1f) / size;
            float alpha = OBSERVATION_HISTORY_MIN_ALPHA
                    + (1f - OBSERVATION_HISTORY_MIN_ALPHA) * progress;
            queuedMarker.setAlpha(alpha);
            queuedMarker.setZIndex(236f + index);
            queuedMarker.setVisible(isObservationVisible(type));
            index++;
        }
    }

    private ArrayDeque<Marker> getObservationQueue(@NonNull DisplayObservationType type) {
        switch (type) {
            case GNSS:
                return gnssObservationMarkers;
            case WIFI:
                return wifiObservationMarkers;
            default:
                return pdrObservationMarkers;
        }
    }

    private Marker obtainReusableObservationMarker(@NonNull DisplayObservationType type,
                                                   @NonNull LatLng point,
                                                   @NonNull ArrayDeque<Marker> targetQueue) {
        Marker marker = null;
        int limit = getMaxObservationMarkersForType(type);
        if (targetQueue.size() >= limit) {
            marker = targetQueue.removeFirst();
        }
        if (marker == null) {
            marker = gMap.addMarker(new MarkerOptions()
                    .position(point)
                    .anchor(0.5f, 0.5f)
                    .zIndex(240f)
                    .alpha(1f)
                    .visible(isObservationVisible(type))
                    .icon(observationMarkerFactory.getObservationIcon(type)));
        } else {
            marker.setPosition(point);
            marker.setVisible(isObservationVisible(type));
            marker.setAlpha(1f);
            marker.setZIndex(240f);
        }
        return marker;
    }
    private boolean isObservationVisible(@NonNull DisplayObservationType type) {
        switch (type) {
            case GNSS:
                return isGnssOn;
            case WIFI:
                return isWifiObservationOn;
            default:
                return isPdrObservationOn;
        }
    }

    // Keeps the displayed indoor floor aligned with the floor chosen by fusion logic.
    private void syncMapFloorToSensorFusion(boolean force) {
        if (mapController == null) {
            return;
        }
        com.openpositioning.PositionMe.sensors.SensorFusion sensorFusion =
                com.openpositioning.PositionMe.sensors.SensorFusion.getInstance();
        int sensorFloor = sensorFusion.getCurrentFloorByMapMatching();
        int mapFloor = mapController.getCurrentFloorValue();
        long nowMs = System.currentTimeMillis();
        if (!force
                && sensorFloor == lastRequestedSensorFloor
                && sensorFloor == mapFloor
                && (nowMs - lastFloorSyncAttemptTimeMs) < FLOOR_SYNC_MIN_INTERVAL_MS) {
            return;
        }
        lastRequestedSensorFloor = sensorFloor;
        lastFloorSyncAttemptTimeMs = nowMs;
        if (sensorFloor == mapFloor) {
            return;
        }
        sensorFloorSyncInProgress = true;
        try {
            mapController.setFloorByValue(sensorFloor);
        } finally {
            sensorFloorSyncInProgress = false;
        }
    }

    private void setMarkerCollectionVisible(ArrayDeque<Marker> markers, boolean visible) {
        for (Marker marker : markers) {
            if (marker != null) {
                marker.setVisible(visible);
            }
        }
    }

    private void flushPendingTags() {
        if (gMap == null || pendingTagLocations.isEmpty()) {
            return;
        }
        int count = Math.min(pendingTagLocations.size(), pendingTagIndices.size());
        for (int i = 0; i < count; i++) {
            LatLng latLng = pendingTagLocations.get(i);
            Integer index = pendingTagIndices.get(i);
            if (latLng != null && index != null) {
                gMap.addMarker(new MarkerOptions()
                        .position(latLng)
                        .anchor(0.5f, 0.5f)
                        .zIndex(300f)
                        .icon(createNumberedMarkerIcon(index)));
            }
        }
        pendingTagLocations.clear();
        pendingTagIndices.clear();
    }

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

    public boolean isGnssEnabled() {
        return isGnssOn;
    }

    public boolean isSmoothDisplayEnabled() {
        return smoothDisplayEnabled;
    }
    public void updateGNSS(@NonNull LatLng gnssLocation) {
        if (gMap == null) return;
        if (gnssMarker == null) {
            gnssMarker = gMap.addMarker(new MarkerOptions()
                    .position(gnssLocation)
                    .title("Latest GNSS")
                    .zIndex(260f)
                    .visible(isGnssOn)
                    .icon(observationMarkerFactory != null
                            ? observationMarkerFactory.getObservationIcon(DisplayObservationType.GNSS)
                            : BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
        } else {
            gnssMarker.setPosition(gnssLocation);
            gnssMarker.setVisible(isGnssOn);
        }
    }

    public void clearGNSS() {
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }
        for (Marker marker : gnssObservationMarkers) {
            if (marker != null) marker.remove();
        }
        gnssObservationMarkers.clear();
    }

    private void setFloorControlsVisibility(int visibility) {
        if (floorControlCard != null) {
            floorControlCard.setVisibility(visibility);
        }
    }

    private void updateManualFloorControlsVisibility() {
        setFloorControlsVisibility(isAutoFloorEnabled ? View.GONE : View.VISIBLE);
    }

    // Clears all temporary map state so the next recording starts from a clean display.
    public void clearMapAndReset() {
        if (fusedPolyline != null) {
            fusedPolyline.remove();
            fusedPolyline = null;
        }
        if (orientationMarker != null) {
            orientationMarker.remove();
            orientationMarker = null;
        }
        if (bestEstimateDotMarker != null) {
            bestEstimateDotMarker.remove();
            bestEstimateDotMarker = null;
        }
        if (gnssMarker != null) {
            gnssMarker.remove();
            gnssMarker = null;
        }

        clearObservationQueue(gnssObservationMarkers);
        clearObservationQueue(wifiObservationMarkers);
        clearObservationQueue(pdrObservationMarkers);
        pendingObservationUpdates.clear();
        fusedPathPoints.clear();
        pendingTagLocations.clear();
        pendingTagIndices.clear();
        pendingCameraPosition = null;
        hasPendingCameraMove = false;

        currentLocation = null;

        isAutoFloorEnabled = true;
        smoothDisplayEnabled = DEFAULT_SMOOTH_DISPLAY_ENABLED;
        isRed = true;
        replaySmoother.reset();
        floorSelectionSyncArmed = false;
        sensorFloorSyncInProgress = false;
        lastFloorSyncAttemptTimeMs = 0L;
        lastRequestedSensorFloor = Integer.MIN_VALUE;
        isWifiObservationOn = true;
        isPdrObservationOn = true;
        com.openpositioning.PositionMe.sensors.SensorFusion.getInstance().consumePendingFloorDelta();
        maxObservationMarkers = DEFAULT_MAX_OBSERVATION_MARKERS;
        currentFloorValue = 0;
        manualFloorOffset = 0;

        if (currentFloorIndicator != null) updateFloorIndicatorUI(0);
        if (gnssSwitch != null) gnssSwitch.setChecked(true);
        if (wifiObservationSwitch != null) wifiObservationSwitch.setChecked(true);
        if (pdrObservationSwitch != null) pdrObservationSwitch.setChecked(true);
        if (autoFloorSwitch != null) autoFloorSwitch.setChecked(true);
        if (smoothDisplaySwitch != null) smoothDisplaySwitch.setChecked(DEFAULT_SMOOTH_DISPLAY_ENABLED);
        if (observationCountSpinner != null) observationCountSpinner.setSelection(2, false);
        if (mapControlPanelController != null) mapControlPanelController.setCollapsed(true);
        if (switchColorButton != null) switchColorButton.setBackgroundColor(Color.RED);
        updateManualFloorControlsVisibility();

        if (gMap != null) {
            fusedPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.RED)
                    .width(5f)
                    .zIndex(200f)
                    .add());
            fusedPathPoints.clear();
        }
    }

    private void clearObservationQueue(ArrayDeque<Marker> queue) {
        while (!queue.isEmpty()) {
            Marker marker = queue.removeFirst();
            if (marker != null) marker.remove();
        }
    }

    private float normalizeMarkerRotationDegrees(float degrees) {
        if (!Float.isFinite(degrees)) {
            return 0f;
        }
        float normalized = degrees % 360f;
        if (normalized < 0f) {
            normalized += 360f;
        }
        return normalized;
    }
}
