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
import android.widget.Toast;

import com.google.android.material.switchmaterial.SwitchMaterial;

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
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.FloorplanService;
import com.openpositioning.PositionMe.data.remote.model.FloorplanLevel;
import com.openpositioning.PositionMe.data.remote.model.FloorplanVenue;
import com.openpositioning.PositionMe.data.remote.model.MapShapeData;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.Wifi;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.BitmapDescriptor;

/**
 * A fragment responsible for displaying and managing live trajectory and indoor map views.
 */
public class TrajectoryMapFragment extends Fragment {

    private static final String TAG = "TrajectoryMapFragment";
    private static final float VENUE_STROKE_WIDTH = 8f;
    private static final float VENUE_SELECTED_STROKE_WIDTH = 10f;
    private static final long FLOORPLAN_REFRESH_MS = 60_000L;
    private static final double FLOORPLAN_REFRESH_DISTANCE_METERS = 30.0;

    private GoogleMap gMap;

    /**
     * Markers used to visualize user-added test points on the map.
     * We store references so we can clear them later if needed.
     */
    private final List<Marker> testPointMarkers = new ArrayList<>();

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

    // Dynamic floorplan fields
    private SensorFusion sensorFusion;
    private FloorplanService floorplanService;
    private long lastFloorplanRequestTimeMs = 0L;
    private LatLng lastFloorplanRequestLocation;
    private LatLng floorplanProbeLocation;
    private boolean floorplanRequestInFlight = false;

    private final Map<Polygon, FloorplanVenue> venueByPolygon = new HashMap<>();
    private final List<Polygon> venuePolygons = new ArrayList<>();
    private final List<Polygon> floorShapePolygons = new ArrayList<>();
    private final List<Polyline> floorShapePolylines = new ArrayList<>();

    private FloorplanVenue selectedVenue;
    private Polygon selectedVenuePolygon;
    private int selectedFloorIndex = 0;

    // UI
    private android.widget.Spinner switchMapSpinner;
    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch;
    private com.google.android.material.floatingactionbutton.FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;

    private long lastAutoFloorSwitchTimeMs = 0L;
    private static final long max_time = 5000L; // 5 seconds debounce to prevent rapid floor switching

    private Float lastElevationMeters = null;
    public void setElevation(float elevationMeters) {
        this.lastElevationMeters = elevationMeters;
    }

    private boolean autoFloorEnabled = false;
    public boolean isAutoFloorEnabled(){
        return autoFloorEnabled;
    }

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

        sensorFusion = SensorFusion.getInstance();
        floorplanService = new FloorplanService();

        switchMapSpinner = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch = view.findViewById(R.id.gnssSwitch);
        autoFloorSwitch = view.findViewById(R.id.autoFloor);
        floorUpButton = view.findViewById(R.id.floorUpButton);
        floorDownButton = view.findViewById(R.id.floorDownButton);
        switchColorButton = view.findViewById(R.id.lineColorButton);

        setFloorControlsVisibility(View.GONE);

        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.trajectoryMap);
        if (mapFragment != null) {
            mapFragment.getMapAsync(new OnMapReadyCallback() {
                @Override
                public void onMapReady(@NonNull GoogleMap googleMap) {
                    gMap = googleMap;
                    initMapSettings(gMap);

                    // Handle taps on test-point markers to show absolute timestamp
                    gMap.setOnMarkerClickListener(marker -> {

                        Object tag = marker.getTag();

                        // Only react to markers that represent test points
                        if (tag instanceof SensorFusion.TestPoint) {
                            SensorFusion.TestPoint tp = (SensorFusion.TestPoint) tag;

                            marker.setTitle("Test Point");
                            marker.setSnippet(formatAbsoluteTime(tp.absoluteTimestampMs));
                            marker.showInfoWindow();

                            return true; // consume the click
                        }

                        return false; // allow default behavior for other markers
                    });

                    if (hasPendingCameraMove && pendingCameraPosition != null) {
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
                        hasPendingCameraMove = false;
                        pendingCameraPosition = null;
                    }

                    if (floorplanProbeLocation != null) {
                        maybeRequestFloorplans(floorplanProbeLocation);
                    }

                    Log.d(TAG, "onMapReady: map initialized");
                }

                /**
                 * Formats an absolute wall-clock timestamp into a readable local date-time string.
                 */
                private String formatAbsoluteTime(long absoluteTimestampMs) {
                    java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(
                            java.text.DateFormat.MEDIUM,
                            java.text.DateFormat.MEDIUM
                    );
                    return df.format(new java.util.Date(absoluteTimestampMs));
                }
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

        autoFloorSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            
            autoFloorEnabled = isChecked;
            if (isChecked && lastElevationMeters != null) {
                autoFloorFromElevation(lastElevationMeters);
            }
        });

        floorUpButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            switchFloor(selectedFloorIndex + 1);
            Log.d(TAG, "Floor up button clicked. New floor index: " + selectedFloorIndex);
        });

        floorDownButton.setOnClickListener(v -> {
            autoFloorSwitch.setChecked(false);
            switchFloor(selectedFloorIndex - 1);
            Log.d(TAG, "Floor down button clicked. New floor index: " + selectedFloorIndex);
        });
    }

    //auto floor helper maps elevation to a floor
    public void autoFloorFromElevation(float elevationMeters) {
        if (!hasFloorData()) {
            return;
        }

        long now = System.currentTimeMillis();
        if ((now - lastAutoFloorSwitchTimeMs) < max_time) {
            return;
        }
        int targetFloor = mapElevationToFloor(elevationMeters);
        int levelIndex = findLevelIndexByFloor(targetFloor);

        if (levelIndex >= 0) {
            switchFloor(levelIndex);
            lastAutoFloorSwitchTimeMs = now;
        }
    }

    private int mapElevationToFloor(float elevationMeters) { // murchison 
        if (elevationMeters >= 5.5 && elevationMeters <= 7.5) {
            return 1;
        } else if (elevationMeters >= 2.5 && elevationMeters <= 4.5) {
            return 0;
        } else if (elevationMeters >= -1.0 && elevationMeters <= 1.0) {
            return -1;
        } else if (elevationMeters >= 8.0 && elevationMeters <= 10.0) {
            return 2;
        } else if (elevationMeters >= 11.0 && elevationMeters <= 13.0) {
            return 3;
        } 
        return 0; // Default fallback
    }

    private int findLevelIndexByFloor(int targetFloor) {
        if (targetFloor == -1) return 0;
        if (targetFloor == 0)  return 1; // GF
        if (targetFloor == 1)  return 2; // F1
        if (targetFloor == 2)  return 3; // F2
        if (targetFloor == 3)  return 4; // F3
        return -1;
    }




    private void initMapSettings(GoogleMap map) {
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        map.setOnPolygonClickListener(this::handleVenuePolygonClick);

        polyline = map.addPolyline(new PolylineOptions()
                .color(Color.RED)
                .width(5f)
                .add());

        gnssPolyline = map.addPolyline(new PolylineOptions()
                .color(Color.BLUE)
                .width(5f)
                .add());
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
                    default:
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
                                    R.drawable.ic_baseline_navigation_24))));
            gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
        } else {
            orientationMarker.setPosition(newLocation);
            orientationMarker.setRotation(orientation);
            gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
        }

        if (oldLocation != null && !oldLocation.equals(newLocation) && polyline != null) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            points.add(newLocation);
            polyline.setPoints(points);
        }

    }

    /**
     * Updates the absolute location used for server-side floorplan proximity queries.
     * This is intentionally separate from the PDR trajectory location to avoid drift affecting
     * which venue polygons become clickable.
     */
    public void updateFloorplanProbeLocation(@NonNull LatLng absoluteLocation) {
        floorplanProbeLocation = absoluteLocation;
        if (gMap != null) {
            maybeRequestFloorplans(absoluteLocation);
        }
    }

    private void maybeRequestFloorplans(@NonNull LatLng location) {
        if (floorplanRequestInFlight) {
            Log.d(TAG, "Skip request: request already in flight");
            return;
        }

        long now = System.currentTimeMillis();
        boolean isFirst = lastFloorplanRequestLocation == null;
        boolean isStale = now - lastFloorplanRequestTimeMs >= FLOORPLAN_REFRESH_MS;
        boolean movedFar = false;

        if (lastFloorplanRequestLocation != null) {
            movedFar = UtilFunctions.distanceBetweenPoints(lastFloorplanRequestLocation, location)
                    >= FLOORPLAN_REFRESH_DISTANCE_METERS;
        }

        if (!isStale && !movedFar) {
           // Log.d(TAG, "Skip request: throttle active (isStale=" + isStale + ", movedFar=" + movedFar + ")");
            return;
        }

        Log.d(TAG, "Trigger request: lat=" + location.latitude
                + ", lon=" + location.longitude
                + ", isFirst=" + isFirst
                + ", isStale=" + isStale
                + ", movedFar=" + movedFar);
        floorplanRequestInFlight = true;
        lastFloorplanRequestTimeMs = now;
        lastFloorplanRequestLocation = location;

        List<Long> macs = new ArrayList<>();
        List<Wifi> wifiList = sensorFusion.getWifiList();
        if (wifiList != null) {
            for (Wifi wifi : wifiList) {
                if (wifi != null && wifi.getBssid() > 0) {
                    macs.add(wifi.getBssid());
                }
            }
        }
        Log.d(TAG, "Request macCount=" + macs.size());

        floorplanService.requestNearbyFloorplans(location.latitude, location.longitude, macs,
                new FloorplanService.FloorplanCallback() {
                    @Override
                    public void onSuccess(List<FloorplanVenue> venues) {
                        if (!isAdded()) {
                            floorplanRequestInFlight = false;
                            return;
                        }
                        requireActivity().runOnUiThread(() -> {
                            floorplanRequestInFlight = false;
                            Log.d(TAG, "onSuccess venuesCount=" + (venues == null ? 0 : venues.size()));
                            renderVenueOutlines(venues);
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        Log.w(TAG, "onError: " + errorMessage);
                        floorplanRequestInFlight = false;
                    }
                });
    }

    private void renderVenueOutlines(List<FloorplanVenue> venues) {
        Log.d(TAG, "renderVenueOutlines inputCount=" + (venues == null ? 0 : venues.size()));
        clearVenueOutlines();

        if (venues == null || venues.isEmpty()) {
            Log.d(TAG, "No venues to render");
            clearSelectedVenue();
            return;
        }

        boolean selectedStillAvailable = false;
        for (FloorplanVenue venue : venues) {
            if (venue.getOutline() == null || venue.getOutline().size() < 3) {
                Log.d(TAG, "Skip venue (invalid outline) campaign=" + venue.getCampaign());
                continue;
            }

            PolygonOptions options = new PolygonOptions()
                    .addAll(venue.getOutline())
                    .strokeColor(Color.CYAN)
                    .strokeWidth(VENUE_STROKE_WIDTH)
                    .fillColor(Color.argb(35, 0, 200, 255))
                    .clickable(true)
                    .zIndex(5f);
            Polygon polygon = gMap.addPolygon(options);
            venuePolygons.add(polygon);
            venueByPolygon.put(polygon, venue);
            Log.d(TAG, "Rendered venue polygon campaign=" + venue.getCampaign()
                    + ", outlinePoints=" + venue.getOutline().size()
                    + ", levels=" + venue.getLevels().size());

            if (selectedVenue != null && venue.getCampaign().equals(selectedVenue.getCampaign())) {
                selectedVenue = venue;
                selectedVenuePolygon = polygon;
                selectedStillAvailable = true;
            }
        }

        if (selectedStillAvailable) {
            styleSelectedVenue(selectedVenuePolygon);
            renderSelectedFloor();
            setFloorControlsVisibility(hasFloorData() ? View.VISIBLE : View.GONE);
        } else {
            Log.d(TAG, "Selected venue no longer available in latest response");
            clearSelectedVenue();
        }
    }

    private void handleVenuePolygonClick(Polygon polygon) {
        FloorplanVenue venue = venueByPolygon.get(polygon);
        if (venue == null) {
            return;
        }

        if (selectedVenuePolygon != null && selectedVenuePolygon.equals(polygon)) {
            return;
        }

        if (selectedVenuePolygon != null) {
            styleVenueDefault(selectedVenuePolygon);
        }

        selectedVenue = venue;
        selectedVenuePolygon = polygon;
        selectedFloorIndex = 0;
        styleSelectedVenue(polygon);
        sensorFusion.setSelectedCampaign(venue.getCampaign());
        Log.d(TAG, "Venue selected campaign=" + venue.getCampaign() + ", levels=" + venue.getLevels().size());

        renderSelectedFloor();
        setFloorControlsVisibility(hasFloorData() ? View.VISIBLE : View.GONE);

        Toast.makeText(requireContext(), "Selected venue: " + venue.getCampaign(), Toast.LENGTH_SHORT).show();
    }

    private void switchFloor(int newIndex) {
        if (!hasFloorData()) {
            return;
        }
        if (newIndex < 0 || newIndex >= selectedVenue.getLevels().size()) {
            return;
        }

        selectedFloorIndex = newIndex;
        renderSelectedFloor();
    }

    private boolean hasFloorData() {
        return selectedVenue != null
                && selectedVenue.getLevels() != null
                && !selectedVenue.getLevels().isEmpty();
    }

    private void renderSelectedFloor() {
        clearRenderedFloorShapes();

        if (!hasFloorData()) {
            return;
        }

        FloorplanLevel currentLevel = selectedVenue.getLevels().get(selectedFloorIndex);
        List<MapShapeData> shapes = currentLevel.getShapes();
        if (shapes == null) {
            return;
        }

        for (MapShapeData shape : shapes) {
            List<LatLng> points = shape.getPoints();
            if (points == null || points.size() < 2) {
                continue;
            }

            int strokeColor = parseColorSafe(shape.getStrokeColor(), Color.GREEN);
            int fillColor = parseColorSafe(shape.getFillColor(), Color.argb(30, 0, 255, 0));
            float strokeWidth = shape.getStrokeWidth() > 0 ? shape.getStrokeWidth() : 3f;
            strokeWidth = Math.max(strokeWidth, 4f);

            if (shape.getShapeType() == MapShapeData.ShapeType.POLYGON && points.size() >= 3) {
                Polygon polygon = gMap.addPolygon(new PolygonOptions()
                        .addAll(points)
                        .strokeColor(strokeColor)
                        .strokeWidth(strokeWidth)
                        .fillColor(fillColor)
                        .clickable(false)
                        .zIndex(7f));
                floorShapePolygons.add(polygon);
            } else {
                Polyline polyline = gMap.addPolyline(new PolylineOptions()
                        .addAll(points)
                        .color(strokeColor)
                        .width(strokeWidth)
                        .zIndex(7f));
                floorShapePolylines.add(polyline);
            }
        }

    }

    private void clearVenueOutlines() {
        for (Polygon polygon : venuePolygons) {
            polygon.remove();
        }
        venuePolygons.clear();
        venueByPolygon.clear();
    }

    private void clearRenderedFloorShapes() {
        for (Polygon polygon : floorShapePolygons) {
            polygon.remove();
        }
        floorShapePolygons.clear();

        for (Polyline polyline : floorShapePolylines) {
            polyline.remove();
        }
        floorShapePolylines.clear();
    }

    private void clearSelectedVenue() {
        if (selectedVenuePolygon != null) {
            styleVenueDefault(selectedVenuePolygon);
        }
        selectedVenuePolygon = null;
        selectedVenue = null;
        selectedFloorIndex = 0;
        clearRenderedFloorShapes();
        setFloorControlsVisibility(View.GONE);
        sensorFusion.setSelectedCampaign(null);
    }

    private void styleSelectedVenue(@NonNull Polygon polygon) {
        polygon.setStrokeColor(Color.RED);
        polygon.setStrokeWidth(VENUE_SELECTED_STROKE_WIDTH);
        polygon.setFillColor(Color.argb(25, 255, 0, 0));
    }

    private void styleVenueDefault(@NonNull Polygon polygon) {
        polygon.setStrokeColor(Color.CYAN);
        polygon.setStrokeWidth(VENUE_STROKE_WIDTH);
        polygon.setFillColor(Color.argb(35, 0, 200, 255));
    }

    private int parseColorSafe(String colorString, int fallback) {
        if (colorString == null || colorString.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Color.parseColor(colorString.trim());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
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


    /**
     * Creates a simple circular marker icon with a number drawn on top.
     * This allows the user to see "1,2,3..." directly on the map.
     */
    private BitmapDescriptor createNumberedMarkerIcon(int number) {
        final int sizePx = 96; // icon size
        final int radius = sizePx / 2;

        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setARGB(255, 33, 150, 243); // blue-ish
        canvas.drawCircle(radius, radius, radius, circlePaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setARGB(255, 255, 255, 255); // white
        textPaint.setTextSize(40f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);

        String text = String.valueOf(number);

        // Vertically center the text using font metrics
        Rect bounds = new Rect();
        textPaint.getTextBounds(text, 0, text.length(), bounds);
        float x = radius;
        float y = radius - bounds.exactCenterY();

        canvas.drawText(text, x, y, textPaint);

        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }


    /**
     * Adds a visible numbered marker on the map for a user-defined test point.
     *
     * @param location The test point position (LatLng)
     * @param index    Sequential number for labeling (1, 2, 3...)
     */
    public void addTestPointMarker(@NonNull LatLng location, int index, @NonNull SensorFusion.TestPoint testPoint) {
        if (gMap == null) return;

        BitmapDescriptor icon = createNumberedMarkerIcon(index);

        Marker marker = gMap.addMarker(new MarkerOptions()
                .position(location)
                .icon(icon)
                .anchor(0.5f, 0.5f) // center the circle
                .title("Test Point " + index));

        if (marker != null) {
            marker.setTag(testPoint);   // ← timestamp is stored here
            testPointMarkers.add(marker);
        }
    }


    /**
     * Clears all test point markers from the map.
     * Useful when starting a new recording session.
     */
    public void clearTestPointMarkers() {
        for (Marker marker : testPointMarkers) {
            if (marker != null) marker.remove();
        }
        testPointMarkers.clear();
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

        clearSelectedVenue();
        clearVenueOutlines();
        clearTestPointMarkers();

        lastGnssLocation = null;
        currentLocation = null;
        lastFloorplanRequestLocation = null;
        floorplanProbeLocation = null;
        floorplanRequestInFlight = false;

        if (gMap != null) {
            polyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.RED)
                    .width(5f)
                    .add());
            gnssPolyline = gMap.addPolyline(new PolylineOptions()
                    .color(Color.BLUE)
                    .width(5f)
                    .add());
        }
    }
}
