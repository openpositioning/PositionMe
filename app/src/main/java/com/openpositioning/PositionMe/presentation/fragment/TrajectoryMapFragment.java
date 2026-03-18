package com.openpositioning.PositionMe.presentation.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.mapmatching.CandidatePose;
import com.openpositioning.PositionMe.mapmatching.CorrectionType;
import com.openpositioning.PositionMe.mapmatching.MapMatchingInput;
import com.openpositioning.PositionMe.mapmatching.MapMatchingResult;
import com.openpositioning.PositionMe.mapmatching.MapMatchingService;
import com.openpositioning.PositionMe.mapmatching.MotionDelta;
import com.openpositioning.PositionMe.mapmatching.VerticalTransitionHint;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.BuildingPolygon;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TrajectoryMapFragment extends Fragment {

    private static final String TAG = "TrajectoryMapFragment";
    private static final long AUTO_FLOOR_DEBOUNCE_MS = 3000;
    private static final long AUTO_FLOOR_CHECK_INTERVAL_MS = 1000;
    private static final String CALIBRATION_PREFS_NAME = "actual_map_calibration";
    private static final float CALIBRATION_SHIFT_STEP = 0.005f;
    private static final float CALIBRATION_SCALE_STEP = 0.005f;
    private static final double HEIGHT_CHANGE_THRESHOLD_METERS = 0.9d;
    private static final long MAP_MATCH_LOG_INTERVAL_MS = 0L;

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

    private Button btnFindIndoorMap;
    private Button btnFindActualMap;
    private TextView selectedVenueText;
    private TextView calibrationTargetText;
    private TextView calibrationValueText;
    private CircularProgressIndicator indoorLoadingIndicator;

    private View calibrationPanel;
    private Button btnToggleAdjustMap;
    private Button btnCalibrationTarget;
    private Button btnCalibrateUp;
    private Button btnCalibrateDown;
    private Button btnCalibrateLeft;
    private Button btnCalibrateRight;
    private Button btnWidthMinus;
    private Button btnWidthPlus;
    private Button btnHeightMinus;
    private Button btnHeightPlus;
    private Button btnSaveCalibration;
    private Button btnResetCalibration;

    private String calibrationTargetBuildingKey = "";

    private boolean indoorMapVisible = false;
    private boolean actualMapVisible = false;
    private boolean hasFetchedNearbyBuildings = false;
    private boolean hasAttemptedInitialBuildingFetch = false;
    private boolean isIndoorRequestInFlight = false;
    private int currentFloorIndex = 0;

    private final List<GroundOverlay> realMapOverlays = new ArrayList<>();

    private GoogleMap gMap;
    private LatLng currentLocation;
    private Marker orientationMarker;
    private Marker gnssMarker;
    private final List<Marker> testPointMarkers = new ArrayList<>();
    private Polyline polyline;
    private boolean isRed = true;
    private boolean isGnssOn = false;
    private Polyline gnssPolyline;
    private LatLng lastGnssLocation = null;
    private LatLng pendingCameraPosition = null;
    private boolean hasPendingCameraMove = false;

    private IndoorMapManager indoorMapManager;
    private SensorFusion sensorFusion;
    private final List<Polygon> floorplanPolygons = new ArrayList<>();
    private final Map<Polygon, FloorplanApiClient.BuildingInfo> polygonToBuilding = new HashMap<>();
    private final List<FloorplanApiClient.BuildingInfo> lastFetchedBuildings = new ArrayList<>();
    private FloorplanApiClient.BuildingInfo selectedFloorplanBuilding;
    private Polygon selectedFloorplanPolygon;
    private final FloorplanApiClient floorplanApiClient = new FloorplanApiClient();
    private final MapMatchingService mapMatchingService = new MapMatchingService();

    private CandidatePose previousMatchedPose;
    private MapMatchingResult lastMapMatchingResult;
    private float previousElevation = Float.NaN;
    private int latestCandidateLogicalFloor = Integer.MIN_VALUE;
    private long lastMapMatchLogTime = 0L;
    private long lastAutoFloorLogTime = 0L;

    private Handler autoFloorHandler;
    private Runnable autoFloorTask;
    private int lastCandidateFloor = Integer.MIN_VALUE;
    private long lastCandidateTime = 0;

    private Spinner switchMapSpinner;
    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;
    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private TextView floorLabel;
    private Button switchColorButton;

    public TrajectoryMapFragment() {
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_trajectory_map, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch = view.findViewById(R.id.gnssSwitch);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        floorUpButton = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        floorLabel = view.findViewById(R.id.floorLabel);
        switchColorButton = view.findViewById(R.id.lineColorButton);

        btnFindIndoorMap = view.findViewById(R.id.btnFindIndoorMap);
        btnFindActualMap = view.findViewById(R.id.btnFindActualMap);
        btnToggleAdjustMap = view.findViewById(R.id.btnToggleAdjustMap);
        btnCalibrationTarget = view.findViewById(R.id.btnCalibrationTarget);
        btnCalibrateUp = view.findViewById(R.id.btnCalibrateUp);
        btnCalibrateDown = view.findViewById(R.id.btnCalibrateDown);
        btnCalibrateLeft = view.findViewById(R.id.btnCalibrateLeft);
        btnCalibrateRight = view.findViewById(R.id.btnCalibrateRight);
        btnWidthMinus = view.findViewById(R.id.btnWidthMinus);
        btnWidthPlus = view.findViewById(R.id.btnWidthPlus);
        btnHeightMinus = view.findViewById(R.id.btnHeightMinus);
        btnHeightPlus = view.findViewById(R.id.btnHeightPlus);
        btnSaveCalibration = view.findViewById(R.id.btnSaveCalibration);
        btnResetCalibration = view.findViewById(R.id.btnResetCalibration);
        calibrationPanel = view.findViewById(R.id.calibrationPanel);
        selectedVenueText = view.findViewById(R.id.selectedVenueText);
        calibrationTargetText = view.findViewById(R.id.calibrationTargetText);
        calibrationValueText = view.findViewById(R.id.calibrationValueText);
        indoorLoadingIndicator = view.findViewById(R.id.indoorLoadingIndicator);

        if (indoorLoadingIndicator != null) {
            indoorLoadingIndicator.setVisibility(View.GONE);
        }
        if (selectedVenueText != null) {
            selectedVenueText.setText("Tap a blue building outline to select a building");
        }
        if (calibrationPanel != null) {
            calibrationPanel.setVisibility(View.GONE);
        }
        if (btnToggleAdjustMap != null) {
            btnToggleAdjustMap.setVisibility(View.GONE);
        }
        setFloorControlsVisibility(View.GONE);

        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.trajectoryMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(googleMap -> {
                gMap = googleMap;
                initMapSettings(gMap);
                if (hasPendingCameraMove && pendingCameraPosition != null) {
                    gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
                    hasPendingCameraMove = false;
                    pendingCameraPosition = null;
                }
                restoreCachedBuildingsIfAny();
                maybeFetchNearbyBuildingsOnFirstLocation();
            });
        }

        initMapTypeSpinner();

        gnssSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isGnssOn = isChecked;
            if (!isChecked && gnssMarker != null) {
                gnssMarker.remove();
                gnssMarker = null;
            }
        });

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

        setupCalibrationControls();

        sensorFusion = SensorFusion.getInstance();
        autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (isChecked) {
                startAutoFloor();
            } else {
                stopAutoFloor();
            }
        });

        floorUpButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (selectedFloorplanBuilding != null) {
                int nextFloorIndex = getAdjacentFloorIndex(selectedFloorplanBuilding, currentFloorIndex, true);
                if (nextFloorIndex != currentFloorIndex) {
                    setFloor(nextFloorIndex);
                }
            }
        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            if (selectedFloorplanBuilding != null) {
                int nextFloorIndex = getAdjacentFloorIndex(selectedFloorplanBuilding, currentFloorIndex, false);
                if (nextFloorIndex != currentFloorIndex) {
                    setFloor(nextFloorIndex);
                }
            }
        });

        if (btnFindIndoorMap != null) {
            btnFindIndoorMap.setOnClickListener(v -> {
                if (selectedFloorplanBuilding == null) {
                    if (lastFetchedBuildings.isEmpty()) {
                        requestNearbyIndoorMaps(true);
                    } else if (selectedVenueText != null) {
                        selectedVenueText.setText("Tap a blue building outline to select a building first");
                    }
                    return;
                }

                actualMapVisible = false;
                indoorMapVisible = true;
                if (indoorMapManager != null) {
                    indoorMapManager.setSelectedBuilding(selectedFloorplanBuilding);
                }
                setFloor(currentFloorIndex);

                if (selectedVenueText != null) {
                    selectedVenueText.setText("Showing indoor map for " + prettyBuildingName(selectedFloorplanBuilding.getName()));
                }
                updateCalibrationUi();
            });
        }

        if (btnFindActualMap != null) {
            btnFindActualMap.setOnClickListener(v -> {
                if (selectedFloorplanBuilding == null) {
                    if (lastFetchedBuildings.isEmpty()) {
                        requestNearbyIndoorMaps(true);
                    } else if (selectedVenueText != null) {
                        selectedVenueText.setText("Tap a blue building outline to select a building first");
                    }
                    return;
                }

                indoorMapVisible = true;
                actualMapVisible = !actualMapVisible;
                if (indoorMapManager != null) {
                    indoorMapManager.setSelectedBuilding(selectedFloorplanBuilding);
                }
                setFloor(currentFloorIndex);

                if (selectedVenueText != null) {
                    if (actualMapVisible) {
                        selectedVenueText.setText("Displaying actual map for " + prettyBuildingName(selectedFloorplanBuilding.getName()));
                    } else {
                        selectedVenueText.setText("Actual map hidden for " + prettyBuildingName(selectedFloorplanBuilding.getName()));
                    }
                }
                updateCalibrationUi();
            });
        }
    }

    private void maybeFetchNearbyBuildingsOnFirstLocation() {
        if (gMap == null || currentLocation == null || hasAttemptedInitialBuildingFetch || isIndoorRequestInFlight || hasFetchedNearbyBuildings) {
            return;
        }
        hasAttemptedInitialBuildingFetch = true;
        requestNearbyIndoorMaps(false);
    }

    private void setFloor(int newFloorIndex) {
        if (selectedFloorplanBuilding == null || indoorMapManager == null) {
            return;
        }

        int maxFloor = selectedFloorplanBuilding.getFloorShapesList().size() - 1;
        currentFloorIndex = Math.max(0, Math.min(newFloorIndex, maxFloor));
        refreshSelectedPolygonAppearance();

        if (!indoorMapVisible) {
            indoorMapManager.clearIndoorMap();
            updateFloorLabel();
            return;
        }

        resetMapOverlays();
        indoorMapManager.setVectorBaseplateEnabled(!actualMapVisible);
        if (actualMapVisible) {
            updateRealMapOverlay(selectedFloorplanBuilding.getName(), currentFloorIndex, true);
        }

        indoorMapManager.setCurrentFloor(currentFloorIndex, false);
        setFloorControlsVisibility(View.VISIBLE);
        updateFloorLabel();
        updateCalibrationUi();
    }




    private void updateRealMapOverlay(String buildingName, int floorIndex, boolean show) {
        if (!show || gMap == null || selectedFloorplanBuilding == null) {
            return;
        }

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
        if (gMap == null) {
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
        int drawableResId = resolveActualMapDrawable(normalizedBuildingKey, drawableFloorDisplayName, resolvedFloorIndex);
        LatLngBounds bounds = computeActualMapBounds(building, normalizedBuildingKey, drawableResId, drawableFloorDisplayName);

        if (drawableResId != 0 && bounds != null) {
            GroundOverlay overlay = gMap.addGroundOverlay(new GroundOverlayOptions()
                    .image(BitmapDescriptorFactory.fromResource(drawableResId))
                    .positionFromBounds(bounds)
                    .zIndex(5f));
            if (overlay != null) {
                realMapOverlays.add(overlay);
            }
        }
    }

    private int resolveFallbackFloorIndexForKey(String buildingKey, String requestedFloorDisplayName, int requestedFloorIndex) {
        String normalizedKey = normalizeBuildingKey(buildingKey);
        String canonicalFloor = canonicalFloorLabel(requestedFloorDisplayName);

        if ("nucleus_building".equals(normalizedKey)) {
            switch (canonicalFloor) {
                case "LG":
                    return 0;
                case "G":
                    return 1;
                case "1":
                    return 2;
                case "2":
                    return 3;
                case "3":
                    return 4;
                default:
                    return Math.max(0, Math.min(requestedFloorIndex, 4));
            }
        }

        if ("library".equals(normalizedKey)) {
            switch (canonicalFloor) {
                case "G":
                    return 0;
                case "1":
                    return 1;
                case "2":
                    return 2;
                case "3":
                    return 3;
                default:
                    return Math.max(0, Math.min(requestedFloorIndex, 3));
            }
        }

        return Math.max(0, requestedFloorIndex);
    }

    private boolean shouldShowLinkedLibraryAndNucleus(String buildingKey) {
        return "library".equals(buildingKey) || "nucleus_building".equals(buildingKey);
    }

    private FloorplanApiClient.BuildingInfo findBuildingByKnownKey(String knownKey) {
        if (knownKey == null || knownKey.isEmpty()) {
            return null;
        }

        if (selectedFloorplanBuilding != null) {
            String selectedKey = resolveKnownBuildingKey(selectedFloorplanBuilding, selectedFloorplanBuilding.getName());
            if (knownKey.equals(selectedKey)) {
                return selectedFloorplanBuilding;
            }
        }

        for (FloorplanApiClient.BuildingInfo building : lastFetchedBuildings) {
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
        if (building == null || building.getFloorShapesList() == null || building.getFloorShapesList().isEmpty()) {
            return 0;
        }

        String normalizedRequestedFloor = normalizeFloorLabel(requestedFloorDisplayName);
        if (!normalizedRequestedFloor.isEmpty()) {
            for (int i = 0; i < building.getFloorShapesList().size(); i++) {
                String candidateLabel = normalizeFloorLabel(building.getFloorShapesList().get(i).getDisplayName());
                if (areEquivalentFloorLabels(normalizedRequestedFloor, candidateLabel)) {
                    return i;
                }
            }
        }

        return Math.max(0, Math.min(fallbackFloorIndex, building.getFloorShapesList().size() - 1));
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

    private int resolveActualMapDrawable(String buildingName, String floorDisplayName, int floorIndex) {
        buildingName = normalizeBuildingKey(buildingName);
        String canonicalFloor = canonicalFloorLabel(floorDisplayName);
        if ("nucleus_building".equals(buildingName)) {
            if ("LG".equals(canonicalFloor)) {
                return R.drawable.nucleuslg;
            }
            if ("G".equals(canonicalFloor)) {
                return R.drawable.nucleusg;
            }
            if ("1".equals(canonicalFloor)) {
                return R.drawable.nucleus1;
            }
            if ("2".equals(canonicalFloor)) {
                return R.drawable.nucleus2;
            }
            if ("3".equals(canonicalFloor)) {
                return R.drawable.nucleus3;
            }

            switch (floorIndex) {
                case 0:
                    return R.drawable.nucleuslg;
                case 1:
                    return R.drawable.nucleusg;
                case 2:
                    return R.drawable.nucleus1;
                case 3:
                    return R.drawable.nucleus2;
                case 4:
                    return R.drawable.nucleus3;
                default:
                    return R.drawable.nucleusg;
            }
        }

        if ("library".equals(buildingName)) {
            if ("LG".equals(canonicalFloor)) {
                return 0;
            }
            if ("G".equals(canonicalFloor)) {
                return R.drawable.libraryg;
            }
            if ("1".equals(canonicalFloor)) {
                return R.drawable.library1;
            }
            if ("2".equals(canonicalFloor)) {
                return R.drawable.library2;
            }
            if ("3".equals(canonicalFloor)) {
                return R.drawable.library3;
            }

            switch (floorIndex) {
                case 0:
                    return 0;
                case 1:
                    return R.drawable.libraryg;
                case 2:
                    return R.drawable.library1;
                case 3:
                    return R.drawable.library2;
                case 4:
                    return R.drawable.library3;
                default:
                    return R.drawable.libraryg;
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

        if (bounds == null) {
            bounds = getFallbackBuildingBounds(buildingName);
        }
        if (bounds != null) {
            bounds = applySavedCalibration(bounds, buildingName, floorDisplayName);
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
                    0.0);
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
        int width = bounds.outWidth;
        int height = bounds.outHeight;
        if (width <= 0 || height <= 0) {
            return new DrawableContentInsets(config.leftVisibleInsetRatio, config.topVisibleInsetRatio,
                    config.rightVisibleInsetRatio, config.bottomVisibleInsetRatio);
        }
        return new DrawableContentInsets(config.leftVisibleInsetRatio, config.topVisibleInsetRatio,
                config.rightVisibleInsetRatio, config.bottomVisibleInsetRatio);
    }

    private static double clamp01(double value) {
        return Math.max(0d, Math.min(1d, value));
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
            return new ActualMapAlignmentConfig(HorizontalAnchor.RIGHT,
                    0.0, 0.0, 0.0,
                    0.99,
                    0.0, 0.0, 0.0, 0.0);
        }
        if ("library".equals(buildingName)) {
            return new ActualMapAlignmentConfig(HorizontalAnchor.RIGHT,
                    0.0, 0.0, 0.0,
                    0.985,
                    0.0, 0.0, 0.0, 0.0);
        }
        if ("murchison_house".equals(buildingName)) {
            return new ActualMapAlignmentConfig(HorizontalAnchor.CENTER,
                    0.0, 0.0, 0.0,
                    1.0,
                    0.0, 0.0, 0.0, 0.0);
        }
        return new ActualMapAlignmentConfig(HorizontalAnchor.CENTER,
                0.0, 0.0, 0.0,
                1.0,
                0.0, 0.0, 0.0, 0.0);
    }

    private String normalizeFloorLabel(String floorDisplayName) {
        if (floorDisplayName == null) {
            return "";
        }
        return floorDisplayName.trim().toUpperCase().replace(" ", "");
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
        if ("nucleus_building".equals(normalized) || "library".equals(normalized) || "murchison_house".equals(normalized)) {
            return normalized;
        }

        LatLng center = building != null ? building.getCenter() : null;
        if (center != null) {
            if (BuildingPolygon.inLibrary(center)) {
                return "library";
            }
            if (BuildingPolygon.inNucleus(center)) {
                return "nucleus_building";
            }
            if (BuildingPolygon.inMurchison(center)) {
                return "murchison_house";
            }
        }

        List<LatLng> outline = building != null ? building.getOutlinePolygon() : null;
        if (outline != null && !outline.isEmpty()) {
            LatLng centroid = computeOutlineCentroid(outline);
            if (centroid != null) {
                if (BuildingPolygon.inLibrary(centroid)) {
                    return "library";
                }
                if (BuildingPolygon.inNucleus(centroid)) {
                    return "nucleus_building";
                }
                if (BuildingPolygon.inMurchison(centroid)) {
                    return "murchison_house";
                }
            }
        }

        return normalized;
    }

    private LatLng computeOutlineCentroid(List<LatLng> outline) {
        if (outline == null || outline.isEmpty()) {
            return null;
        }

        double latSum = 0d;
        double lngSum = 0d;
        int count = 0;
        for (LatLng point : outline) {
            if (point == null) {
                continue;
            }
            latSum += point.latitude;
            lngSum += point.longitude;
            count++;
        }
        if (count == 0) {
            return null;
        }
        return new LatLng(latSum / count, lngSum / count);
    }

    private void onFloorplanBuildingSelected(FloorplanApiClient.BuildingInfo building, Polygon polygon) {
        if (selectedFloorplanPolygon != null) {
            selectedFloorplanPolygon.setFillColor(Color.argb(50, 33, 150, 243));
            selectedFloorplanPolygon.setStrokeColor(Color.argb(220, 33, 150, 243));
            selectedFloorplanPolygon.setZIndex(1f);
        }

        selectedFloorplanPolygon = polygon;
        selectedFloorplanBuilding = building;
        indoorMapVisible = false;
        actualMapVisible = false;
        currentFloorIndex = getDefaultFloorIndex(building);
        previousMatchedPose = null;
        lastMapMatchingResult = null;

        if (polygon != null) {
            polygon.setZIndex(1f);
        }
        refreshSelectedPolygonAppearance();

        SensorFusion.getInstance().setSelectedBuildingId(building.getName());

        resetMapOverlays();
        if (indoorMapManager != null) {
            indoorMapManager.clearIndoorMap();
        }
        setFloorControlsVisibility(View.GONE);

        if (selectedVenueText != null) {
            selectedVenueText.setText("Selected: " + prettyBuildingName(building.getName()) + ". Tap Find Indoor Maps.");
        }
        calibrationTargetBuildingKey = resolveKnownBuildingKey(building, building.getName());
        updateCalibrationUi();

        LatLng center = building.getCenter();
        if (center != null && !(center.latitude == 0 && center.longitude == 0)) {
            gMap.animateCamera(CameraUpdateFactory.newLatLngZoom(center, 20f));
        }
    }

    private int getDefaultFloorIndex(FloorplanApiClient.BuildingInfo building) {
        if (building == null || building.getFloorShapesList() == null || building.getFloorShapesList().isEmpty()) {
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

    private int getAdjacentFloorIndex(FloorplanApiClient.BuildingInfo building, int currentIndex, boolean moveUp) {
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

        int nextOrderedPosition = moveUp ? currentOrderedPosition + 1 : currentOrderedPosition - 1;
        if (nextOrderedPosition < 0 || nextOrderedPosition >= orderedFloorIndices.size()) {
            return currentIndex;
        }
        return orderedFloorIndices.get(nextOrderedPosition);
    }

    private List<Integer> getOrderedFloorIndices(FloorplanApiClient.BuildingInfo building) {
        List<Integer> ordered = new ArrayList<>();
        if (building == null || building.getFloorShapesList() == null || building.getFloorShapesList().isEmpty()) {
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
                String candidateFloor = canonicalFloorLabel(building.getFloorShapesList().get(i).getDisplayName());
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
            case "LG":
                return "LG";
            case "G":
                return "G";
            case "1":
                return "F1";
            case "2":
                return "F2";
            case "3":
                return "F3";
            default:
                return rawFloorLabel == null ? "" : rawFloorLabel;
        }
    }

    private void refreshSelectedPolygonAppearance() {
        if (selectedFloorplanPolygon == null) {
            return;
        }

        if (indoorMapVisible) {
            selectedFloorplanPolygon.setFillColor(Color.argb(10, 33, 150, 243));
            selectedFloorplanPolygon.setStrokeColor(Color.argb(180, 33, 150, 243));
            selectedFloorplanPolygon.setStrokeWidth(3f);
            selectedFloorplanPolygon.setZIndex(1f);
        } else {
            selectedFloorplanPolygon.setFillColor(Color.argb(100, 33, 150, 243));
            selectedFloorplanPolygon.setStrokeColor(Color.argb(255, 25, 118, 210));
            selectedFloorplanPolygon.setStrokeWidth(5f);
            selectedFloorplanPolygon.setZIndex(1f);
        }
    }

    private void resetMapOverlays() {
        for (GroundOverlay overlay : realMapOverlays) {
            if (overlay != null) {
                overlay.remove();
            }
        }
        realMapOverlays.clear();
    }

    private void restoreCachedBuildingsIfAny() {
        List<FloorplanApiClient.BuildingInfo> cached = SensorFusion.getInstance().getFloorplanBuildings();
        if (cached != null && !cached.isEmpty()) {
            drawFloorplanBuildings(cached);
            lastFetchedBuildings.clear();
            lastFetchedBuildings.addAll(cached);
            hasFetchedNearbyBuildings = true;
            if (selectedVenueText != null) {
                selectedVenueText.setText("Tap a blue building outline to select a building");
            }
        }
    }

    private void setIndoorLoading(boolean loading) {
        isIndoorRequestInFlight = loading;
        if (indoorLoadingIndicator != null) {
            indoorLoadingIndicator.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
        if (btnFindIndoorMap != null) {
            btnFindIndoorMap.setEnabled(!loading);
            btnFindIndoorMap.setAlpha(loading ? 0.6f : 1f);
        }
        if (btnFindActualMap != null) {
            btnFindActualMap.setEnabled(!loading);
            btnFindActualMap.setAlpha(loading ? 0.6f : 1f);
        }
        if (btnToggleAdjustMap != null) {
            btnToggleAdjustMap.setEnabled(!loading);
            btnToggleAdjustMap.setAlpha(loading ? 0.6f : 1f);
        }
    }

    private List<String> getObservedMacs() {
        List<String> macs = new ArrayList<>();
        List<com.openpositioning.PositionMe.sensors.Wifi> wifiList = SensorFusion.getInstance().getWifiList();
        if (wifiList != null) {
            for (com.openpositioning.PositionMe.sensors.Wifi wifi : wifiList) {
                String mac = wifi.getBssidString();
                if (mac != null && !mac.isEmpty()) {
                    macs.add(mac);
                }
            }
        }
        return macs;
    }

    private void requestNearbyIndoorMaps(boolean userInitiated) {
        if (gMap == null) {
            return;
        }

        LatLng center = currentLocation;
        if (center == null && orientationMarker != null) {
            center = orientationMarker.getPosition();
        }
        if (center == null) {
            float[] gnss = SensorFusion.getInstance().getGNSSLatitude(false);
            if (!(gnss[0] == 0f && gnss[1] == 0f)) {
                center = new LatLng(gnss[0], gnss[1]);
            }
        }

        if (center == null) {
            if (userInitiated && selectedVenueText != null) {
                selectedVenueText.setText("Location not ready yet. Please wait.");
            }
            return;
        }

        if (userInitiated && selectedVenueText != null) {
            selectedVenueText.setText("Requesting nearby indoor maps...");
        }
        setIndoorLoading(true);

        floorplanApiClient.requestFloorplan(center.latitude, center.longitude, getObservedMacs(),
                new FloorplanApiClient.FloorplanCallback() {
                    @Override
                    public void onSuccess(List<FloorplanApiClient.BuildingInfo> buildings) {
                        if (!isAdded() || gMap == null) {
                            return;
                        }
                        setIndoorLoading(false);
                        hasFetchedNearbyBuildings = buildings != null && !buildings.isEmpty();

                        lastFetchedBuildings.clear();
                        if (buildings != null) {
                            lastFetchedBuildings.addAll(buildings);
                        }

                        SensorFusion.getInstance().setFloorplanBuildings(buildings);
                        drawFloorplanBuildings(buildings);

                        if (selectedVenueText != null) {
                            if (buildings == null || buildings.isEmpty()) {
                                selectedVenueText.setText("No nearby buildings found.");
                            } else {
                                selectedVenueText.setText("Tap a blue building outline to select a building");
                            }
                        }
                    }

                    @Override
                    public void onFailure(String error) {
                        if (!isAdded()) {
                            return;
                        }
                        setIndoorLoading(false);
                        if (userInitiated && selectedVenueText != null) {
                            selectedVenueText.setText("Request failed: " + error);
                        }
                    }
                });
    }

    private void drawFloorplanBuildings(List<FloorplanApiClient.BuildingInfo> buildings) {
        for (Polygon p : floorplanPolygons) {
            p.remove();
        }
        floorplanPolygons.clear();
        polygonToBuilding.clear();

        if (buildings == null || buildings.isEmpty() || gMap == null) {
            return;
        }

        LatLngBounds.Builder boundsBuilder = new LatLngBounds.Builder();
        boolean hasAnyPoint = false;

        for (FloorplanApiClient.BuildingInfo building : buildings) {
            List<LatLng> outline = building.getOutlinePolygon();
            if (outline == null || outline.size() < 3) {
                continue;
            }

            Polygon polygon = gMap.addPolygon(new PolygonOptions()
                    .addAll(outline)
                    .strokeColor(Color.argb(220, 33, 150, 243))
                    .strokeWidth(5f)
                    .fillColor(Color.argb(50, 33, 150, 243))
                    .clickable(true)
                    .zIndex(1f));

            floorplanPolygons.add(polygon);
            polygonToBuilding.put(polygon, building);

            for (LatLng point : outline) {
                boundsBuilder.include(point);
                hasAnyPoint = true;
            }
        }

        if (hasAnyPoint) {
            try {
                gMap.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 100));
            } catch (Exception ignored) {
            }
        }
    }

    private void initMapSettings(GoogleMap map) {
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        indoorMapManager = new IndoorMapManager(map);

        map.setOnPolygonClickListener(polygon -> {
            FloorplanApiClient.BuildingInfo building = polygonToBuilding.get(polygon);
            if (building != null) {
                onFloorplanBuildingSelected(building, polygon);
            }
        });

        polyline = map.addPolyline(new PolylineOptions().color(Color.RED).width(5f).add());
        gnssPolyline = map.addPolyline(new PolylineOptions().color(Color.BLUE).width(5f).add());
    }

    private String prettyBuildingName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String[] parts = raw.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) {
                sb.append(p.substring(1));
            }
        }
        return sb.toString();
    }

    private void initMapTypeSpinner() {
        if (switchMapSpinner == null) {
            return;
        }
        String[] maps = new String[]{getString(R.string.hybrid), getString(R.string.normal), getString(R.string.satellite)};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, maps);
        switchMapSpinner.setAdapter(adapter);
        switchMapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (gMap == null) {
                    return;
                }
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
        if (gMap == null) {
            return;
        }

        LatLng previousDisplayedLocation = this.currentLocation;
        LatLng previousReferenceLocation = previousMatchedPose != null
                ? previousMatchedPose.getLatLng()
                : previousDisplayedLocation;

        MotionDelta motionDelta = buildMotionDelta(previousReferenceLocation, newLocation, orientation);
        VerticalTransitionHint verticalHint = buildVerticalHint();
        int candidateLogicalFloor = getCandidateLogicalFloorForMatching();
        CandidatePose currentCandidatePose = new CandidatePose(
                newLocation,
                candidateLogicalFloor,
                System.currentTimeMillis(),
                "RAW_PDR"
        );

        int geometryLogicalFloor = previousMatchedPose != null
                ? previousMatchedPose.getFloor()
                : getCurrentVisibleLogicalFloor();
        FloorplanApiClient.FloorShapes activeFloorShapes = getFloorShapesForLogicalFloor(geometryLogicalFloor);

        MapMatchingInput input = new MapMatchingInput(
                previousMatchedPose,
                currentCandidatePose,
                motionDelta,
                verticalHint,
                activeFloorShapes,
                selectedFloorplanBuilding != null ? selectedFloorplanBuilding.getName() : null
        );

        logMapMatchingInput(newLocation, previousReferenceLocation, candidateLogicalFloor,
                geometryLogicalFloor, motionDelta, verticalHint, activeFloorShapes, orientation);

        MapMatchingResult result = mapMatchingService.match(input);
        lastMapMatchingResult = result;

        LatLng matchedLocation = result.getCorrectedLatLng() != null
                ? result.getCorrectedLatLng()
                : newLocation;
        int matchedLogicalFloor = result.getCorrectedFloor();

        this.currentLocation = matchedLocation;
        previousMatchedPose = new CandidatePose(
                matchedLocation,
                matchedLogicalFloor,
                currentCandidatePose.getTimestampMs(),
                "MAP_MATCHED"
        );
        if (sensorFusion != null) {
            previousElevation = sensorFusion.getElevation();
        }

        if (autoFloorSwitch != null && autoFloorSwitch.isChecked()) {
            applyMatchedFloor(matchedLogicalFloor);
        }

        boolean shouldFollowCamera = !(indoorMapVisible || actualMapVisible);
        if (orientationMarker == null) {
            orientationMarker = gMap.addMarker(new MarkerOptions()
                    .position(matchedLocation)
                    .flat(true)
                    .rotation(orientation)
                    .title("Current Position")
                    .icon(BitmapDescriptorFactory.fromBitmap(UtilFunctions.getBitmapFromVector(requireContext(), R.drawable.ic_baseline_navigation_24))));
            if (shouldFollowCamera) {
                gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(matchedLocation, 19f));
            }
        } else {
            orientationMarker.setPosition(matchedLocation);
            orientationMarker.setRotation(orientation);
            if (shouldFollowCamera) {
                gMap.moveCamera(CameraUpdateFactory.newLatLng(matchedLocation));
            }
        }

        if (polyline != null) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            if (previousDisplayedLocation == null || !previousDisplayedLocation.equals(matchedLocation)) {
                points.add(matchedLocation);
                polyline.setPoints(points);
            }
        }

        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(matchedLocation);
        }

        logMapMatchingResult(newLocation, result);
        maybeFetchNearbyBuildingsOnFirstLocation();
    }

    private MotionDelta buildMotionDelta(@Nullable LatLng previousLocation,
                                         @NonNull LatLng currentLocation,
                                         float orientation) {
        if (previousLocation == null) {
            return new MotionDelta(0d, 0d, 0d, orientation);
        }

        double metersPerDegLat = 111320.0d;
        double metersPerDegLon = 111320.0d * Math.cos(Math.toRadians(currentLocation.latitude));
        double deltaX = (currentLocation.longitude - previousLocation.longitude) * metersPerDegLon;
        double deltaY = (currentLocation.latitude - previousLocation.latitude) * metersPerDegLat;
        double stepDistance = Math.hypot(deltaX, deltaY);
        return new MotionDelta(deltaX, deltaY, stepDistance, orientation);
    }

    @Nullable
    private VerticalTransitionHint buildVerticalHint() {
        if (sensorFusion == null) {
            return null;
        }

        float currentElevation = sensorFusion.getElevation();
        double deltaHeight = Float.isNaN(previousElevation) ? 0d : (currentElevation - previousElevation);
        boolean heightChanged = !Float.isNaN(previousElevation)
                && Math.abs(deltaHeight) >= HEIGHT_CHANGE_THRESHOLD_METERS;
        return new VerticalTransitionHint(currentElevation, deltaHeight, heightChanged);
    }

    private int getCandidateLogicalFloorForMatching() {
        if (autoFloorSwitch != null && autoFloorSwitch.isChecked()) {
            if (latestCandidateLogicalFloor != Integer.MIN_VALUE) {
                return latestCandidateLogicalFloor;
            }
            return resolveCandidateLogicalFloor();
        }
        return getCurrentVisibleLogicalFloor();
    }

    private int getCurrentVisibleLogicalFloor() {
        if (selectedFloorplanBuilding == null) {
            return 0;
        }
        return floorIndexToLogicalFloor(currentFloorIndex);
    }

    private int floorIndexToLogicalFloor(int floorIndex) {
        if (selectedFloorplanBuilding == null || selectedFloorplanBuilding.getFloorShapesList() == null
                || floorIndex < 0 || floorIndex >= selectedFloorplanBuilding.getFloorShapesList().size()) {
            return 0;
        }

        String canonical = canonicalFloorLabel(getFloorDisplayName(selectedFloorplanBuilding, floorIndex));
        if ("LG".equals(canonical)) {
            return -1;
        }
        if ("G".equals(canonical) || canonical.isEmpty()) {
            return 0;
        }

        try {
            return Integer.parseInt(canonical);
        } catch (NumberFormatException ignore) {
            return 0;
        }
    }

    private int resolveCandidateLogicalFloor() {
        if (sensorFusion == null) {
            return getCurrentVisibleLogicalFloor();
        }

        if (sensorFusion.getLatLngWifiPositioning() != null) {
            return sensorFusion.getWifiFloor();
        }

        if (indoorMapManager != null) {
            float floorHeight = indoorMapManager.getFloorHeight();
            if (floorHeight > 0f) {
                return Math.round(sensorFusion.getElevation() / floorHeight);
            }
        }

        return getCurrentVisibleLogicalFloor();
    }

    @NonNull
    private String describeCandidateFloorSource() {
        if (sensorFusion == null) {
            return "NO_SENSOR_FUSION";
        }

        if (sensorFusion.getLatLngWifiPositioning() != null) {
            return "WIFI";
        }

        if (indoorMapManager != null) {
            float floorHeight = indoorMapManager.getFloorHeight();
            if (floorHeight > 0f) {
                return "BAROMETER_OVER_FLOOR_HEIGHT";
            }
        }

        return "VISIBLE_FLOOR_FALLBACK";
    }

    @Nullable
    private FloorplanApiClient.FloorShapes getFloorShapesForLogicalFloor(int logicalFloor) {
        if (selectedFloorplanBuilding == null || selectedFloorplanBuilding.getFloorShapesList() == null
                || selectedFloorplanBuilding.getFloorShapesList().isEmpty()) {
            return null;
        }

        int floorIndex;
        if (indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) {
            floorIndex = indoorMapManager.logicalFloorToIndex(logicalFloor);
        } else {
            floorIndex = Math.max(0, Math.min(logicalFloor, selectedFloorplanBuilding.getFloorShapesList().size() - 1));
        }

        if (floorIndex < 0 || floorIndex >= selectedFloorplanBuilding.getFloorShapesList().size()) {
            return null;
        }
        return selectedFloorplanBuilding.getFloorShapesList().get(floorIndex);
    }

    private void applyMatchedFloor(int matchedLogicalFloor) {
        if (selectedFloorplanBuilding == null || indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
            Log.d(TAG, "FLOOR_APPLY skipped: building/map not ready");
            return;
        }

        int targetFloorIndex = indoorMapManager.logicalFloorToIndex(matchedLogicalFloor);
        Log.d(TAG, String.format(Locale.US,
                "FLOOR_APPLY visibleLogical=%d currentIndex=%d targetLogical=%d targetIndex=%d building=%s",
                getCurrentVisibleLogicalFloor(),
                currentFloorIndex,
                matchedLogicalFloor,
                targetFloorIndex,
                selectedFloorplanBuilding != null ? selectedFloorplanBuilding.getName() : "null"));
        if (targetFloorIndex != currentFloorIndex) {
            setFloor(targetFloorIndex);
        }
    }

    private void logMapMatchingResult(@NonNull LatLng rawLocation, @Nullable MapMatchingResult result) {
        if (result == null) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastMapMatchLogTime < MAP_MATCH_LOG_INTERVAL_MS) {
            return;
        }
        lastMapMatchLogTime = now;

        LatLng matched = result.getCorrectedLatLng() != null ? result.getCorrectedLatLng() : rawLocation;
        Log.d(TAG, String.format(Locale.US,
                "MAP_MATCH raw=(%.6f, %.6f) matched=(%.6f, %.6f) candidateFloor=%d matchedFloor=%d crossedWall=%s nearStairs=%s nearLift=%s floorAllowed=%s correction=%s reason=%s",
                rawLocation.latitude,
                rawLocation.longitude,
                matched.latitude,
                matched.longitude,
                getCandidateLogicalFloorForMatching(),
                result.getCorrectedFloor(),
                String.valueOf(result.isCrossedWall()),
                String.valueOf(result.isNearStairs()),
                String.valueOf(result.isNearLift()),
                String.valueOf(result.isFloorChangeAllowed()),
                result.getCorrectionType() != null ? result.getCorrectionType().name() : CorrectionType.NONE.name(),
                result.getDebugReason()));
    }


    private void logMapMatchingInput(@NonNull LatLng rawLocation,
                                     @Nullable LatLng previousReferenceLocation,
                                     int candidateLogicalFloor,
                                     int geometryLogicalFloor,
                                     @Nullable MotionDelta motionDelta,
                                     @Nullable VerticalTransitionHint verticalHint,
                                     @Nullable FloorplanApiClient.FloorShapes activeFloorShapes,
                                     float orientation) {
        String shapesSummary = describeFloorShapes(activeFloorShapes);
        String previousLocationText = previousReferenceLocation == null
                ? "null"
                : String.format(Locale.US, "(%.6f, %.6f)", previousReferenceLocation.latitude, previousReferenceLocation.longitude);
        String motionSummary = motionDelta == null
                ? "null"
                : String.format(Locale.US, "dx=%.2f dy=%.2f dist=%.2f heading=%.1f",
                motionDelta.getDeltaX(), motionDelta.getDeltaY(), motionDelta.getStepDistance(), motionDelta.getHeadingDeg());
        String verticalSummary = verticalHint == null
                ? "null"
                : String.format(Locale.US, "elev=%.2f delta=%.2f changed=%s threshold=%.2f",
                verticalHint.getCurrentElevation(), verticalHint.getDeltaHeight(),
                String.valueOf(verticalHint.isHeightChanged()), HEIGHT_CHANGE_THRESHOLD_METERS);

        Log.d(TAG, String.format(Locale.US,
                "MAP_MATCH_INPUT raw=(%.6f, %.6f) prev=%s candidateFloor=%d visibleFloor=%d geometryFloor=%d source=%s orientation=%.1f motion={%s} vertical={%s} shapes={%s}",
                rawLocation.latitude,
                rawLocation.longitude,
                previousLocationText,
                candidateLogicalFloor,
                getCurrentVisibleLogicalFloor(),
                geometryLogicalFloor,
                describeCandidateFloorSource(),
                orientation,
                motionSummary,
                verticalSummary,
                shapesSummary));
    }

    private void logAutoFloorState(@NonNull String phase, int candidateFloor, @NonNull String source) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastAutoFloorLogTime < 0L) {
            return;
        }
        lastAutoFloorLogTime = now;

        float elevationValue = sensorFusion != null ? sensorFusion.getElevation() : Float.NaN;
        int wifiFloor = sensorFusion != null ? sensorFusion.getWifiFloor() : Integer.MIN_VALUE;
        boolean hasWifiLocation = sensorFusion != null && sensorFusion.getLatLngWifiPositioning() != null;
        float floorHeight = indoorMapManager != null ? indoorMapManager.getFloorHeight() : Float.NaN;
        Log.d(TAG, String.format(Locale.US,
                "AUTO_FLOOR phase=%s source=%s candidate=%d visible=%d latestCandidate=%d lastCandidate=%d elapsedSinceCandidate=%d wifiAvailable=%s wifiFloor=%d elevation=%.2f floorHeight=%.2f building=%s indoorVisible=%s",
                phase,
                source,
                candidateFloor,
                getCurrentVisibleLogicalFloor(),
                latestCandidateLogicalFloor,
                lastCandidateFloor,
                lastCandidateTime == 0 ? -1 : (now - lastCandidateTime),
                String.valueOf(hasWifiLocation),
                wifiFloor,
                elevationValue,
                floorHeight,
                selectedFloorplanBuilding != null ? selectedFloorplanBuilding.getName() : "null",
                String.valueOf(indoorMapVisible)));
    }

    @NonNull
    private String describeFloorShapes(@Nullable FloorplanApiClient.FloorShapes floorShapes) {
        if (floorShapes == null) {
            return "none";
        }
        List<FloorplanApiClient.MapShapeFeature> features = floorShapes.getFeatures();
        if (features == null || features.isEmpty()) {
            return String.format(Locale.US, "display=%s features=0", floorShapes.getDisplayName());
        }

        int walls = 0;
        int stairs = 0;
        int lifts = 0;
        int others = 0;
        for (FloorplanApiClient.MapShapeFeature feature : features) {
            String indoorType = feature != null ? feature.getIndoorType() : null;
            if (indoorType == null) {
                others++;
            } else if ("wall".equalsIgnoreCase(indoorType)) {
                walls++;
            } else if ("stairs".equalsIgnoreCase(indoorType)) {
                stairs++;
            } else if ("lift".equalsIgnoreCase(indoorType)) {
                lifts++;
            } else {
                others++;
            }
        }
        return String.format(Locale.US,
                "display=%s total=%d wall=%d stairs=%d lift=%d other=%d",
                floorShapes.getDisplayName(), features.size(), walls, stairs, lifts, others);
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

    public void addTestPointMarker(int index, long timestampMs, @NonNull LatLng position) {
        if (gMap == null) {
            return;
        }
        Marker m = gMap.addMarker(new MarkerOptions().position(position).title("TP " + index).snippet("t=" + timestampMs));
        if (m != null) {
            m.showInfoWindow();
            testPointMarkers.add(m);
        }
    }

    public void updateGNSS(@NonNull LatLng gnssLocation) {
        if (gMap == null || !isGnssOn) {
            return;
        }
        if (gnssMarker == null) {
            gnssMarker = gMap.addMarker(new MarkerOptions().position(gnssLocation).title("GNSS Position").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
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

    private void setupCalibrationControls() {
        if (btnToggleAdjustMap != null) {
            btnToggleAdjustMap.setOnClickListener(v -> {
                if (!actualMapVisible) {
                    if (selectedVenueText != null) {
                        selectedVenueText.setText("Show actual maps first, then adjust them");
                    }
                    return;
                }
                if (calibrationPanel != null) {
                    calibrationPanel.setVisibility(calibrationPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                }
                updateCalibrationUi();
            });
        }

        if (btnCalibrationTarget != null) {
            btnCalibrationTarget.setOnClickListener(v -> {
                cycleCalibrationTarget();
                updateCalibrationUi();
            });
        }

        bindCalibrationButton(btnCalibrateUp, 0f, CALIBRATION_SHIFT_STEP, 0f, 0f);
        bindCalibrationButton(btnCalibrateDown, 0f, -CALIBRATION_SHIFT_STEP, 0f, 0f);
        bindCalibrationButton(btnCalibrateLeft, -CALIBRATION_SHIFT_STEP, 0f, 0f, 0f);
        bindCalibrationButton(btnCalibrateRight, CALIBRATION_SHIFT_STEP, 0f, 0f, 0f);
        bindCalibrationButton(btnWidthMinus, 0f, 0f, -CALIBRATION_SCALE_STEP, 0f);
        bindCalibrationButton(btnWidthPlus, 0f, 0f, CALIBRATION_SCALE_STEP, 0f);
        bindCalibrationButton(btnHeightMinus, 0f, 0f, 0f, -CALIBRATION_SCALE_STEP);
        bindCalibrationButton(btnHeightPlus, 0f, 0f, 0f, CALIBRATION_SCALE_STEP);

        if (btnSaveCalibration != null) {
            btnSaveCalibration.setOnClickListener(v -> {
                String targetKey = getEffectiveCalibrationTargetBuildingKey();
                if (targetKey.isEmpty()) {
                    return;
                }
                String floorKey = getCurrentCalibrationFloorKey(targetKey);
                OverlayCalibration calibration = loadOverlayCalibration(targetKey, floorKey);
                logCalibration(targetKey, floorKey, calibration, "saved");
                if (selectedVenueText != null) {
                    selectedVenueText.setText("Saved calibration for " + prettyBuildingName(targetKey) + " " + floorKey);
                }
                updateCalibrationUi();
            });
        }

        if (btnResetCalibration != null) {
            btnResetCalibration.setOnClickListener(v -> {
                String targetKey = getEffectiveCalibrationTargetBuildingKey();
                if (targetKey.isEmpty()) {
                    return;
                }
                String floorKey = getCurrentCalibrationFloorKey(targetKey);
                OverlayCalibration defaults = getHardcodedCalibrationDefault(targetKey, floorKey);
                saveOverlayCalibration(targetKey, floorKey, defaults);
                if (actualMapVisible) {
                    setFloor(currentFloorIndex);
                }
                if (selectedVenueText != null) {
                    selectedVenueText.setText("Reset calibration for " + prettyBuildingName(targetKey) + " " + floorKey);
                }
                logCalibration(targetKey, floorKey, defaults, "reset");
                updateCalibrationUi();
            });
        }
    }

    private void bindCalibrationButton(Button button, float shiftLngDelta, float shiftLatDelta, float widthDelta, float heightDelta) {
        if (button == null) {
            return;
        }
        button.setOnClickListener(v -> applyCalibrationDelta(shiftLngDelta, shiftLatDelta, widthDelta, heightDelta));
    }

    private void applyCalibrationDelta(float shiftLngDelta, float shiftLatDelta, float widthDelta, float heightDelta) {
        String targetKey = getEffectiveCalibrationTargetBuildingKey();
        if (targetKey.isEmpty()) {
            if (selectedVenueText != null) {
                selectedVenueText.setText("Select a visible actual map to adjust");
            }
            return;
        }

        String floorKey = getCurrentCalibrationFloorKey(targetKey);
        OverlayCalibration current = loadOverlayCalibration(targetKey, floorKey);
        OverlayCalibration updated = new OverlayCalibration(
                current.shiftLatRatio + shiftLatDelta,
                current.shiftLngRatio + shiftLngDelta,
                Math.max(0.50f, current.widthScale + widthDelta),
                Math.max(0.50f, current.heightScale + heightDelta)
        );
        saveOverlayCalibration(targetKey, floorKey, updated);
        logCalibration(targetKey, floorKey, updated, "updated");
        if (actualMapVisible) {
            setFloor(currentFloorIndex);
        }
        updateCalibrationUi();
    }

    private LatLngBounds applySavedCalibration(LatLngBounds baseBounds, String buildingKey, String floorDisplayName) {
        if (baseBounds == null) {
            return null;
        }
        OverlayCalibration calibration = loadOverlayCalibration(buildingKey, floorDisplayName);
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

    private SharedPreferences getCalibrationPrefs() {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        return context.getSharedPreferences(CALIBRATION_PREFS_NAME, Context.MODE_PRIVATE);
    }

    private OverlayCalibration loadOverlayCalibration(String buildingKey, String floorDisplayName) {
        OverlayCalibration defaults = getHardcodedCalibrationDefault(buildingKey, floorDisplayName);
        SharedPreferences prefs = getCalibrationPrefs();
        if (prefs == null) {
            return defaults;
        }
        String baseKey = getCalibrationPrefBaseKey(buildingKey, floorDisplayName);
        return new OverlayCalibration(
                prefs.getFloat(baseKey + "_shift_lat", defaults.shiftLatRatio),
                prefs.getFloat(baseKey + "_shift_lng", defaults.shiftLngRatio),
                prefs.getFloat(baseKey + "_width", defaults.widthScale),
                prefs.getFloat(baseKey + "_height", defaults.heightScale)
        );
    }

    private void saveOverlayCalibration(String buildingKey, String floorDisplayName, OverlayCalibration calibration) {
        SharedPreferences prefs = getCalibrationPrefs();
        if (prefs == null) {
            return;
        }
        String baseKey = getCalibrationPrefBaseKey(buildingKey, floorDisplayName);
        prefs.edit()
                .putFloat(baseKey + "_shift_lat", calibration.shiftLatRatio)
                .putFloat(baseKey + "_shift_lng", calibration.shiftLngRatio)
                .putFloat(baseKey + "_width", calibration.widthScale)
                .putFloat(baseKey + "_height", calibration.heightScale)
                .apply();
    }

    private String getCalibrationPrefBaseKey(String buildingKey, String floorDisplayName) {
        String normalizedBuilding = normalizeBuildingKey(buildingKey);
        String normalizedFloor = canonicalFloorLabel(floorDisplayName);
        return normalizedBuilding + "__" + normalizedFloor;
    }

    private OverlayCalibration getHardcodedCalibrationDefault(String buildingKey, String floorDisplayName) {
        String normalizedBuilding = normalizeBuildingKey(buildingKey);
        String normalizedFloor = canonicalFloorLabel(floorDisplayName);

        if ("nucleus_building".equals(normalizedBuilding)) {
            switch (normalizedFloor) {
                case "LG":
                    return new OverlayCalibration(0.012f, -0.022f, 0.967f, 0.986f);
                case "G":
                    return new OverlayCalibration(0.029f, -0.052f, 0.958f, 0.942f);
                case "1":
                    return new OverlayCalibration(0.004f, 0.000f, 1.000f, 0.981f);
                case "2":
                    return new OverlayCalibration(0.005f, -0.005f, 1.000f, 1.000f);
                case "3":
                    return new OverlayCalibration(0.005f, 0.005f, 1.015f, 0.990f);
                default:
                    return OverlayCalibration.identity();
            }
        }

        if ("library".equals(normalizedBuilding)) {
            switch (normalizedFloor) {
                case "G":
                    return new OverlayCalibration(-0.072f, 0.018f, 0.965f, 1.098f);
                case "1":
                    return new OverlayCalibration(-0.053f, 0.019f, 0.945f, 1.050f);
                case "2":
                    return new OverlayCalibration(-0.075f, 0.025f, 0.950f, 1.070f);
                case "3":
                    return new OverlayCalibration(-0.070f, 0.025f, 0.960f, 1.065f);
                default:
                    return OverlayCalibration.identity();
            }
        }

        return OverlayCalibration.identity();
    }

    private void logCalibration(String buildingKey, String floorKey, OverlayCalibration calibration, String event) {
        Log.d(TAG, String.format(Locale.US,
                "MAP_CALIBRATION %s building=%s floor=%s shiftLat=%.5f shiftLng=%.5f widthScale=%.5f heightScale=%.5f",
                event, normalizeBuildingKey(buildingKey), canonicalFloorLabel(floorKey),
                calibration.shiftLatRatio, calibration.shiftLngRatio, calibration.widthScale, calibration.heightScale));
    }

    private List<String> getAvailableCalibrationTargets() {
        List<String> targets = new ArrayList<>();
        if (!actualMapVisible || selectedFloorplanBuilding == null) {
            return targets;
        }

        String selectedKey = resolveKnownBuildingKey(selectedFloorplanBuilding, selectedFloorplanBuilding.getName());
        String selectedFloorLabel = canonicalFloorLabel(getFloorDisplayName(selectedFloorplanBuilding, currentFloorIndex));

        if (resolveActualMapDrawable(selectedKey, selectedFloorLabel, currentFloorIndex) != 0) {
            targets.add(selectedKey);
        }

        if (shouldShowLinkedLibraryAndNucleus(selectedKey)) {
            String linkedKey = "library".equals(selectedKey) ? "nucleus_building" : "library";
            int linkedIndex = resolveFallbackFloorIndexForKey(linkedKey, selectedFloorLabel, currentFloorIndex);
            if (resolveActualMapDrawable(linkedKey, selectedFloorLabel, linkedIndex) != 0 && !targets.contains(linkedKey)) {
                targets.add(linkedKey);
            }
        }
        return targets;
    }

    private void cycleCalibrationTarget() {
        List<String> targets = getAvailableCalibrationTargets();
        if (targets.isEmpty()) {
            calibrationTargetBuildingKey = "";
            return;
        }
        int currentIndex = targets.indexOf(getEffectiveCalibrationTargetBuildingKey());
        if (currentIndex < 0) {
            calibrationTargetBuildingKey = targets.get(0);
            return;
        }
        calibrationTargetBuildingKey = targets.get((currentIndex + 1) % targets.size());
    }

    private String getEffectiveCalibrationTargetBuildingKey() {
        List<String> targets = getAvailableCalibrationTargets();
        if (targets.isEmpty()) {
            return "";
        }
        if (targets.contains(calibrationTargetBuildingKey)) {
            return calibrationTargetBuildingKey;
        }
        calibrationTargetBuildingKey = targets.get(0);
        return calibrationTargetBuildingKey;
    }

    private String getCurrentCalibrationFloorKey(String buildingKey) {
        String selectedFloorLabel = selectedFloorplanBuilding == null ? "" : getFloorDisplayName(selectedFloorplanBuilding, currentFloorIndex);
        String canonicalFloor = canonicalFloorLabel(selectedFloorLabel);
        if ("library".equals(normalizeBuildingKey(buildingKey)) && "LG".equals(canonicalFloor)) {
            return "LG";
        }
        return canonicalFloor;
    }

    private void updateCalibrationUi() {
        boolean canAdjust = actualMapVisible && selectedFloorplanBuilding != null;
        if (btnToggleAdjustMap != null) {
            btnToggleAdjustMap.setVisibility(canAdjust ? View.VISIBLE : View.GONE);
            btnToggleAdjustMap.setText((calibrationPanel != null && calibrationPanel.getVisibility() == View.VISIBLE) ? "Hide Adjust Map" : "Adjust Map");
        }
        if (!canAdjust) {
            if (calibrationPanel != null) {
                calibrationPanel.setVisibility(View.GONE);
            }
            return;
        }

        String targetKey = getEffectiveCalibrationTargetBuildingKey();
        String floorKey = getCurrentCalibrationFloorKey(targetKey);
        OverlayCalibration calibration = loadOverlayCalibration(targetKey, floorKey);

        if (calibrationTargetText != null) {
            calibrationTargetText.setText("Target: " + prettyBuildingName(targetKey) + " / " + floorKey);
        }
        if (calibrationValueText != null) {
            calibrationValueText.setText(String.format(Locale.US,
                    "x=%.3f  y=%.3f  w=%.3f  h=%.3f",
                    calibration.shiftLngRatio, calibration.shiftLatRatio, calibration.widthScale, calibration.heightScale));
        }
        if (btnCalibrationTarget != null) {
            btnCalibrationTarget.setVisibility(getAvailableCalibrationTargets().size() > 1 ? View.VISIBLE : View.GONE);
        }
    }

    private void setFloorControlsVisibility(int visibility) {
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);
        floorLabel.setVisibility(visibility);
        autoFloorSwitch.setVisibility(visibility);
        if (visibility == View.VISIBLE) {
            updateFloorLabel();
        }
    }

    private void updateFloorLabel() {
        if (floorLabel != null && indoorMapManager != null && indoorMapManager.getIsIndoorMapSet()) {
            floorLabel.setText(formatFloorLabelForUi(indoorMapManager.getCurrentFloorDisplayName()));
        }
        updateCalibrationUi();
    }

    public void clearMapAndReset() {
        stopAutoFloor();
        if (autoFloorSwitch != null) {
            autoFloorSwitch.setChecked(false);
        }
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
        previousMatchedPose = null;
        lastMapMatchingResult = null;
        previousElevation = Float.NaN;
        latestCandidateLogicalFloor = Integer.MIN_VALUE;
        lastMapMatchLogTime = 0L;

        for (Marker m : testPointMarkers) {
            m.remove();
        }
        testPointMarkers.clear();

        if (gMap != null) {
            polyline = gMap.addPolyline(new PolylineOptions().color(Color.RED).width(5f).add());
            gnssPolyline = gMap.addPolyline(new PolylineOptions().color(Color.BLUE).width(5f).add());
        }
        for (Polygon p : floorplanPolygons) {
            p.remove();
        }
        floorplanPolygons.clear();
        polygonToBuilding.clear();
        lastFetchedBuildings.clear();
        selectedFloorplanPolygon = null;
        selectedFloorplanBuilding = null;
        indoorMapVisible = false;
        actualMapVisible = false;
        hasFetchedNearbyBuildings = false;
        hasAttemptedInitialBuildingFetch = false;

        resetMapOverlays();
        if (indoorMapManager != null) {
            indoorMapManager.clearIndoorMap();
        }
        if (selectedVenueText != null) {
            selectedVenueText.setText("Tap a blue building outline to select a building");
        }
        calibrationTargetBuildingKey = "";
        updateCalibrationUi();
        if (indoorLoadingIndicator != null) {
            indoorLoadingIndicator.setVisibility(View.GONE);
        }
        setFloorControlsVisibility(View.GONE);
    }

    private void startAutoFloor() {
        if (autoFloorHandler == null) {
            autoFloorHandler = new Handler(Looper.getMainLooper());
        }
        lastCandidateFloor = Integer.MIN_VALUE;
        lastCandidateTime = 0;
        applyImmediateFloor();

        autoFloorTask = new Runnable() {
            @Override
            public void run() {
                evaluateAutoFloor();
                autoFloorHandler.postDelayed(this, AUTO_FLOOR_CHECK_INTERVAL_MS);
            }
        };
        autoFloorHandler.post(autoFloorTask);
    }

    private void applyImmediateFloor() {
        if (sensorFusion == null || indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
            Log.d(TAG, "AUTO_FLOOR applyImmediate skipped: map not ready");
            return;
        }
        int candidateFloor = resolveCandidateLogicalFloor();
        latestCandidateLogicalFloor = candidateFloor;
        lastCandidateFloor = candidateFloor;
        lastCandidateTime = SystemClock.elapsedRealtime();
        logAutoFloorState("applyImmediate", candidateFloor, describeCandidateFloorSource());
    }

    private void stopAutoFloor() {
        if (autoFloorHandler != null && autoFloorTask != null) {
            autoFloorHandler.removeCallbacks(autoFloorTask);
        }
        Log.d(TAG, String.format(Locale.US,
                "AUTO_FLOOR stop visibleLogical=%d lastCandidate=%d latestCandidate=%d",
                getCurrentVisibleLogicalFloor(), lastCandidateFloor, latestCandidateLogicalFloor));
        lastCandidateFloor = Integer.MIN_VALUE;
        lastCandidateTime = 0;
        latestCandidateLogicalFloor = Integer.MIN_VALUE;
    }

    private void evaluateAutoFloor() {
        if (sensorFusion == null || indoorMapManager == null || !indoorMapManager.getIsIndoorMapSet()) {
            Log.d(TAG, "AUTO_FLOOR evaluate skipped: map not ready");
            return;
        }

        int candidateFloor = resolveCandidateLogicalFloor();
        String source = describeCandidateFloorSource();
        latestCandidateLogicalFloor = candidateFloor;

        long now = SystemClock.elapsedRealtime();
        if (candidateFloor != lastCandidateFloor) {
            logAutoFloorState("candidate_changed", candidateFloor, source);
            lastCandidateFloor = candidateFloor;
            lastCandidateTime = now;
            return;
        }

        if (now - lastCandidateTime >= AUTO_FLOOR_DEBOUNCE_MS) {
            logAutoFloorState("candidate_stable", candidateFloor, source);
            lastCandidateTime = now;
        } else {
            logAutoFloorState("candidate_waiting", candidateFloor, source);
        }
    }
}
