package com.openpositioning.PositionMe.presentation.fragment;

import android.Manifest;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.os.Build;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import androidx.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;
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
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.model.FloorplanModels;
import com.openpositioning.PositionMe.data.remote.FloorplanApi;

import org.json.JSONArray;
import org.json.JSONObject;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.openpositioning.PositionMe.utils.BuildingPolygon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * TrajectoryMapFragment
 *
 * Goal 5C (Indoor map display):
 * 1) User moves map so the crosshair points at the desired area.
 * 2) Tap "Find indoor maps".
 * 3) App calls Floorplan API using (lat,lon,macs[]).
 * 4) Draw venue outlines (clickable polygons) for ALL returned venues.
 * 5) Tap a polygon to select venue -> show floorplan overlay + allow floor switch.
 */
public class TrajectoryMapFragment extends Fragment {

    private static final String TAG = "TrajectoryMapFragment";

    // Debug tag for Goal 5C indoor map flow (filter Logcat by: C_DEBUG)
    private static final String CDBG = "C_DEBUG";
    private static final boolean CDBG_ENABLED = true;

    private void cdbg(@NonNull String msg) {
        if (CDBG_ENABLED) Log.d(CDBG, msg);
    }

    private void cdbgW(@NonNull String msg) {
        if (CDBG_ENABLED) Log.w(CDBG, msg);
    }

    private void cdbgE(@NonNull String msg) {
        if (CDBG_ENABLED) Log.e(CDBG, msg);
    }

    private void showSnack(@NonNull String msg) {
        if (!isAdded()) return;
        View root = getView();
        if (root != null) {
            Snackbar.make(root, msg, Snackbar.LENGTH_LONG).show();
        } else {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
        }
    }

    private void setIndoorLoading(boolean loading) {
        if (requestIndoorButton != null) {
            requestIndoorButton.setEnabled(!loading);
            if (requestIndoorButtonText != null) {
                requestIndoorButton.setText(loading ? "Searching…" : requestIndoorButtonText);
            }
        }
        if (indoorLoadingIndicator != null) {
            indoorLoadingIndicator.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
    }

    private static @NonNull String ll(@Nullable LatLng p) {
        if (p == null) return "(null)";
        return String.format(Locale.US, "%.6f,%.6f", p.latitude, p.longitude);
    }

    private static @NonNull String venueDbg(@Nullable FloorplanModels.Venue v) {
        if (v == null) return "(venue=null)";
        int outlineN = (v.outline == null) ? 0 : v.outline.size();
        int floorsN = (v.floors == null) ? 0 : v.floors.size();
        boolean hasBounds = v.bounds != null;
        return "id=" + v.venueId + " name=" + v.venueName + " outlinePts=" + outlineN + " bounds=" + hasBounds + " floors=" + floorsN;
    }


    // Persist discovered venues so that as you walk around King's Buildings
    // you can accumulate multiple outlines (Nucleus/Library/Murchison/Fleeming Jenkin)
    // even if the API returns only 1 "best" venue per request.
    private static final String PREF_CACHED_VENUES_JSON = "pref_cached_venues_json";

    // Tuning: try harder to get multiple venues around a spot
    private static final int MAX_WIFI_MACS = 200;          // keep more APs so "between buildings" still works
    private static final int MIN_MACS_FOR_API = 6;        // below this we ask for manual input on emulator

    // Multi-probe sampling radius around the crosshair (meters).
    // User request: 50m so we can catch adjacent buildings and show multiple outlines.
    private static final double PROBE_RADIUS_METERS = 50.0;

    // Sequence number for correlating multiple API probes in Logcat
    private final java.util.concurrent.atomic.AtomicInteger indoorReqSeq = new java.util.concurrent.atomic.AtomicInteger(0);


    // Map + trajectory
    private GoogleMap gMap;
    private LatLng currentLocation;
    private Marker orientationMarker;
    private Marker gnssMarker;
    private Polyline polyline;
    private Polyline gnssPolyline;
    private LatLng lastGnssLocation = null;

    private boolean isRed = true;
    private boolean isGnssOn = false;

    // Camera follow + crosshair pick
    private boolean followCamera = true;
    private LatLng pickedCenter = null;

    // Last point used to query the floorplan API (map crosshair center)
    private LatLng lastRequestCenter = null;

    // UI
    private Spinner switchMapSpinner;
    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch; // hidden
    private FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;
    private Button requestIndoorButton;
    private Button clearIndoorCacheButton;
    private SwitchMaterial indoorModeSwitch; // Method B toggle (single request)
    private Button selectVenueButton;
    private TextView selectedVenueText;
    private Spinner floorSpinner;
    private CircularProgressIndicator indoorLoadingIndicator;
    private String requestIndoorButtonText = null;

    // Polygon selection styling
    private Polygon selectedPolygon = null;
    private int polyStrokeColor = Color.MAGENTA;
    private int polyFillColor = Color.argb(50, 126, 87, 194);
    private int polySelectedStrokeColor = Color.WHITE;
    private int polySelectedFillColor = Color.argb(90, 126, 87, 194);

    // Shared preferences keys for "submission includes venue" (C4)
    private static final String PREF_SELECTED_VENUE_ID = "pref_selected_venue_id";
    private static final String PREF_SELECTED_VENUE_NAME = "pref_selected_venue_name";
    private static final String PREF_SELECTED_FLOOR_LABEL = "pref_selected_floor_label";
    private static final String PREF_SELECTED_FLOOR_INDEX = "pref_selected_floor_index";
    private static final String PREF_INDOOR_METHOD_B = "pref_indoor_method_b";

    // Indoor API + overlays
    private final FloorplanApi floorplanApi = new FloorplanApi();
    private IndoorMapFragment indoorMapOverlay;
    private final List<Polygon> venuePolygons = new ArrayList<>();
    private final Map<Polygon, FloorplanModels.Venue> polyToVenue = new HashMap<>();
    private final List<Marker> venueCenterMarkers = new ArrayList<>();
    private final Map<Marker, FloorplanModels.Venue> markerToVenue = new HashMap<>();
    private FloorplanModels.Venue selectedVenue = null;
    private final List<FloorplanModels.Venue> lastFetchedVenues = new ArrayList<>();

    // Persistent union of all venues we've ever seen (so you can show multiple outlines
    // without relying on the API returning them in a single response).
    private final Map<String, FloorplanModels.Venue> venueCache = new HashMap<>();

    // Candidates shown in the "Select a building" dialog.
    // Stored as a field so click listeners don't depend on local variables being final.
    private final List<FloorplanModels.Venue> venuePickerCandidates = new ArrayList<>();

    // Manual MAC dialog memory
    private String lastManualMacText = "";

    // Pending camera init (called before map ready)
    private LatLng pendingCameraPosition = null;
    private boolean hasPendingCameraMove = false;

    public TrajectoryMapFragment() {}

    private static final int REQUEST_WIFI_PERMS = 2105;

    /**
     * Ensure we have permissions required to read Wi-Fi scan results.
     * - Need Location (FINE or COARSE)
     * - On Android 13+ (API 33+), also need NEARBY_WIFI_DEVICES
     *
     * If missing, requests permissions and returns false.
     */
    private boolean ensureWifiScanPermissions() {
        boolean hasFine = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        boolean hasNearby = true;
        if (Build.VERSION.SDK_INT >= 33) {
            hasNearby = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }

        if ((hasFine || hasCoarse) && hasNearby) return true;

        java.util.ArrayList<String> req = new java.util.ArrayList<>();
        if (!hasFine && !hasCoarse) {
            req.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasNearby) {
            req.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }

        ActivityCompat.requestPermissions(requireActivity(), req.toArray(new String[0]), REQUEST_WIFI_PERMS);
        Toast.makeText(requireContext(), "Need Wi-Fi/Location permission for indoor maps.", Toast.LENGTH_LONG).show();
        return false;
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WIFI_PERMS) {
            boolean ok = true;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (!ok) {
                Toast.makeText(requireContext(), "Permission denied. Cannot scan Wi-Fi for indoor maps.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(requireContext(), "Permissions granted. Tap Find indoor maps again.", Toast.LENGTH_SHORT).show();
            }
        }
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

        // Restore any venues discovered in previous runs (so outlines can persist across app restarts).
        loadCachedVenues();

        switchMapSpinner   = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch         = view.findViewById(R.id.gnssSwitch);
        autoFloorSwitch    = view.findViewById(R.id.autoFloor);
        floorUpButton      = view.findViewById(R.id.floorUpButton);
        floorDownButton    = view.findViewById(R.id.floorDownButton);
        switchColorButton  = view.findViewById(R.id.lineColorButton);
        requestIndoorButton = view.findViewById(R.id.requestIndoorButton);
        clearIndoorCacheButton = view.findViewById(R.id.clearIndoorCacheButton);
        indoorModeSwitch = view.findViewById(R.id.indoorModeSwitch);
        selectVenueButton  = view.findViewById(R.id.selectVenueButton);
        selectedVenueText  = view.findViewById(R.id.selectedVenueText);
        floorSpinner        = view.findViewById(R.id.floorSpinner);
        // Restore 'Method B' toggle state (single request instead of grid probing)
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
        boolean methodB = sp.getBoolean(PREF_INDOOR_METHOD_B, false);
        if (indoorModeSwitch != null) {
            indoorModeSwitch.setChecked(methodB);
            indoorModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                    sp.edit().putBoolean(PREF_INDOOR_METHOD_B, isChecked).apply());
        }
        if (clearIndoorCacheButton != null) {
            clearIndoorCacheButton.setOnClickListener(v -> {
                venueCache.clear();
                saveCachedVenues();
                clearVenuePolygons();
                Toast.makeText(requireContext(), "Indoor map cache cleared", Toast.LENGTH_SHORT).show();
            });
        }
        indoorLoadingIndicator = view.findViewById(R.id.indoorLoadingIndicator);
        if (requestIndoorButton != null) requestIndoorButtonText = requestIndoorButton.getText().toString();

        // Theme-aware polygon colors (nicer than hard-coded magenta)
        int primary = MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary, Color.MAGENTA);
        int secondary = MaterialColors.getColor(view, com.google.android.material.R.attr.colorSecondary, primary);
        polyStrokeColor = primary;
        polyFillColor = ColorUtils.setAlphaComponent(primary, 50);
        polySelectedStrokeColor = secondary;
        polySelectedFillColor = ColorUtils.setAlphaComponent(secondary, 90);

        setFloorControlsVisibility(View.GONE);
        floorSpinner.setVisibility(View.GONE);
        autoFloorSwitch.setVisibility(View.GONE);

        if (indoorLoadingIndicator != null) indoorLoadingIndicator.setVisibility(View.GONE);

        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.trajectoryMap);

        if (mapFragment != null) {
            mapFragment.getMapAsync(new OnMapReadyCallback() {
                @Override
                public void onMapReady(@NonNull GoogleMap googleMap) {
                    gMap = googleMap;
                    initMapSettings(gMap);
                    initCameraPickListeners(gMap);

                    // We intentionally DO NOT auto-draw cached venue outlines on startup.
                    // Outlines are shown only after the user presses "Indoor maps".


                    // Allocate enough slots for local + remote floor overlays.
                    // The server response may not include floors for every venue, so we also
                    // support local demo floorplan images packaged in res/drawable.
                    indoorMapOverlay = new IndoorMapFragment(requireContext(), gMap, 8);

                    if (hasPendingCameraMove && pendingCameraPosition != null) {
                        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pendingCameraPosition, 19f));
                        hasPendingCameraMove = false;
                        pendingCameraPosition = null;
                    }

                    Log.d(TAG, "onMapReady: Map is ready");
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
            if (polyline == null) return;
            if (isRed) {
                switchColorButton.setBackgroundColor(Color.BLACK);
                polyline.setColor(Color.BLACK);
                isRed = false;
            } else {
                switchColorButton.setBackgroundColor(Color.RED);
                polyline.setColor(Color.RED);
                isRed = true;
            }
        });

        // Floor up/down FABs should work even when the venue doesn't provide a remote "floors" array
        // (e.g., when we overlay local demo floor images). In that case, just drive the spinner.
        floorUpButton.setOnClickListener(v -> {
            if (floorSpinner == null || floorSpinner.getAdapter() == null) return;
            int count = floorSpinner.getAdapter().getCount();
            if (count <= 1) return;
            int pos = Math.max(0, floorSpinner.getSelectedItemPosition());
            int next = Math.min(pos + 1, count - 1);
            floorSpinner.setSelection(next);
        });

        floorDownButton.setOnClickListener(v -> {
            if (floorSpinner == null || floorSpinner.getAdapter() == null) return;
            int count = floorSpinner.getAdapter().getCount();
            if (count <= 1) return;
            int pos = Math.max(0, floorSpinner.getSelectedItemPosition());
            int next = Math.max(pos - 1, 0);
            floorSpinner.setSelection(next);
        });

        requestIndoorButton.setOnClickListener(v -> requestNearbyIndoorMapsWithManualFallback());

        if (selectVenueButton != null) {
            selectVenueButton.setEnabled(false);
            selectVenueButton.setOnClickListener(v -> showVenuePickerDialog());
        }
        if (selectedVenueText != null) {
            selectedVenueText.setText("Tap \"Find indoor maps\" to show nearby buildings");
        }
    }

    private void initMapSettings(@NonNull GoogleMap map) {
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setTiltGesturesEnabled(true);
        map.getUiSettings().setRotateGesturesEnabled(true);
        map.getUiSettings().setScrollGesturesEnabled(true);
        map.setMapType(GoogleMap.MAP_TYPE_HYBRID);

        // Allow tapping a venue outline to select it.
        map.setOnPolygonClickListener(polygon -> {
            FloorplanModels.Venue v = polyToVenue.get(polygon);
            if (v != null) onVenueSelected(v, polygon);
        });

        map.setOnMarkerClickListener(marker -> {
            FloorplanModels.Venue v = markerToVenue.get(marker);
            if (v != null) {
                onVenueSelected(v, null);
                return true;
            }
            return false;
        });

        polyline = map.addPolyline(new PolylineOptions().color(Color.RED).width(5f).add());
        gnssPolyline = map.addPolyline(new PolylineOptions().color(Color.BLUE).width(5f).add());
    }

    private void initCameraPickListeners(@NonNull GoogleMap map) {
        map.setOnCameraMoveStartedListener(reason -> {
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                followCamera = false;
            }
        });
        map.setOnCameraIdleListener(() -> {
            if (gMap != null) pickedCenter = gMap.getCameraPosition().target;
        });
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
                    case 0: gMap.setMapType(GoogleMap.MAP_TYPE_HYBRID); break;
                    case 1: gMap.setMapType(GoogleMap.MAP_TYPE_NORMAL); break;
                    case 2: gMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE); break;
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /** Called by RecordingFragment to update user (blue) position marker. */
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
            if (followCamera) gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 19f));
        } else {
            orientationMarker.setPosition(newLocation);
            orientationMarker.setRotation(orientation);
            if (followCamera) gMap.moveCamera(CameraUpdateFactory.newLatLng(newLocation));
        }

        if (oldLocation != null && !oldLocation.equals(newLocation) && polyline != null) {
            List<LatLng> points = new ArrayList<>(polyline.getPoints());
            points.add(newLocation);
            polyline.setPoints(points);
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

    public LatLng getCurrentLocation() { return currentLocation; }

    // ===== Selected indoor venue (used by RecordingFragment / submission) =====
    @Nullable
    public String getSelectedVenueId() {
        return selectedVenue != null ? selectedVenue.venueId : null;
    }

    @Nullable
    public String getSelectedVenueName() {
        return selectedVenue != null ? selectedVenue.venueName : null;
    }

    /** Selected floor index in the venue (as returned by API). Returns Integer.MIN_VALUE if none. */
    public int getSelectedFloorIndex() {
        if (selectedVenue == null || selectedVenue.floors == null || selectedVenue.floors.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        int pos = floorSpinner != null ? floorSpinner.getSelectedItemPosition() : 0;
        if (pos < 0 || pos >= selectedVenue.floors.size()) pos = 0;
        return selectedVenue.floors.get(pos).floorIndex;
    }

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

    public boolean isGnssEnabled() { return isGnssOn; }

    private void setFloorControlsVisibility(int visibility) {
        floorUpButton.setVisibility(visibility);
        floorDownButton.setVisibility(visibility);
    }

    // ======================= Indoor Maps (Goal 5C) =======================

    private void requestNearbyIndoorMapsWithManualFallback() {
        if (gMap == null) return;

        LatLng center = (pickedCenter != null) ? pickedCenter : currentLocation;
        if (center == null) {
            Toast.makeText(requireContext(), "No location yet. Move map or wait for GNSS.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Remember the exact point the user picked (crosshair). We'll use it to
        // (1) sort buildings by distance, (2) choose zoom target.
        lastRequestCenter = center;

        if (!ensureWifiScanPermissions()) return;

        // Clear polygons from the map, but KEEP cached venues so the user can accumulate
        // multiple buildings by pressing the button at different points.
        clearVenuePolygons();
        lastFetchedVenues.clear();
        if (selectVenueButton != null) selectVenueButton.setEnabled(false);
        if (selectedVenueText != null) selectedVenueText.setText("Tap a building outline to select a venue");
        if (indoorMapOverlay != null) indoorMapOverlay.clearOverlay();
        selectedVenue = null;
        floorSpinner.setVisibility(View.GONE);
        setFloorControlsVisibility(View.GONE);

        // IMPORTANT: do NOT call startScan() repeatedly (emulator + Android 9+ throttling).
        // We read the latest cached scan results.
        List<String> macs = getNearbyWifiMacsCached();
        cdbg("IndoorMaps: button clicked. pickedCenter=" + ll(center) + " currentLocation=" + ll(currentLocation) + " followCamera=" + followCamera);
        cdbg("IndoorMaps: WiFi scan results -> macsSelected=" + macs.size() + " (MIN=" + MIN_MACS_FOR_API + ", MAX=" + MAX_WIFI_MACS + ")");
        if (!macs.isEmpty()) {
            cdbg("IndoorMaps: MAC sample=" + macs.subList(0, Math.min(8, macs.size())));
        }

        if (macs.size() < MIN_MACS_FOR_API) {
            showMacInputDialog(center, macs.size());
            return;
        }

        setIndoorLoading(true);
        showSnack("Searching indoor maps… (WiFi APs=" + macs.size() + ")");
        requestVenuesMultiProbeWithFallback(center, macs);
    }

    /**
     * Multi-probe: center + 8 surrounding points within a radius.
     *
     * Why: the API often returns only the single best-matching venue for a point.
     * By probing nearby points (here: within 50m of the crosshair), we can pick up
     * adjacent venues so multiple building outlines appear and can be selected by tapping.
     */
        
    /**
     * Request indoor-map venues once using the full Wi‑Fi scan list.
     *
     * The OpenPositioning "request" endpoint is fundamentally Wi‑Fi driven (MAC/BSSID list).
     * Sending lots of small probes (or empty MAC lists) quickly triggers many empty responses
     * and causes us to lose venues. For the assignment, we want the union of all venues
     * returned for the current scan.
     */
    
private void requestVenuesMultiProbeWithFallback(@NonNull LatLng center, @NonNull List<String> macs) {
    if (floorplanApi == null) {
        Log.w(TAG, "Floorplan API not initialised yet");
        setIndoorLoading(false);
        showSnack("Floorplan API not initialised yet");
        return;
    }

    // Keep requests bounded and sequential.
    // The previous "17 centers × 3 probes" parallel approach easily triggered lots of empty responses,
    // which hurts both demo stability and the chance of getting floors/imageUrl for the selected venue.
    final int MAX_REQUESTS = 20;
    final long INTER_REQUEST_DELAY_MS = 120L;

    // Search mode:
    // - Method A (default): compact grid probing around the picked point
    // - Method B: single request (more stable; useful for debugging API)
    boolean methodB = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getBoolean(PREF_INDOOR_METHOD_B, false);

    // Compact probe ring around the user-picked crosshair (≈80m). Enough to discover nearby buildings
    // without spamming the server.
    final List<LatLng> centers = methodB
            ? java.util.Collections.singletonList(center)
            : buildProbeCentersCompact(center, 80.0 /*meters*/);

    final List<String> macsNorm = (macs == null) ? new ArrayList<>() : new ArrayList<>(macs);

    // Two MAC strategies:
    // - Mixed list (capped) for discovering multiple venues
    // - Strongest list for getting a confident match (often required for floorplans/images)
    final List<String> macsMixed = capList(macsNorm, 60);
    List<String> macsStrong = getNearbyWifiMacsStrongest(30);
    if (macsStrong.isEmpty()) macsStrong = capList(macsNorm, 30);

    final List<List<String>> probes = new ArrayList<>();
    if (methodB) {
        // Single request using strongest APs tends to give the most reliable venue match.
        probes.add(macsStrong);
    } else {
        probes.add(macsMixed);
        if (!sameMacList(macsStrong, macsMixed)) probes.add(macsStrong);
    }

    final class Job {
        final LatLng c;
        final List<String> m;
        Job(LatLng c, List<String> m) { this.c = c; this.m = m; }
    }

    final List<Job> jobs = new ArrayList<>();
    for (LatLng c : centers) {
        for (List<String> p : probes) {
            jobs.add(new Job(c, p));
        }
    }
    if (jobs.size() > MAX_REQUESTS) {
        jobs.subList(MAX_REQUESTS, jobs.size()).clear();
    }

    final Map<String, FloorplanModels.Venue> merged = new HashMap<>();
    final int reqSeq = indoorReqSeq.incrementAndGet();

    cdbg("IndoorMaps[#"+reqSeq+"]: start center=" + ll(center)
            + " centers=" + centers.size()
            + " probes=" + probes.size()
            + " totalRequests=" + jobs.size()
            + " macs(mixed)=" + macsMixed.size()
            + " macs(strong)=" + macsStrong.size());

    final Handler h = new Handler(Looper.getMainLooper());
    final java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger(0);

    final Runnable[] next = new Runnable[1];
    next[0] = () -> {
        if (!isAdded()) return;

        int i = idx.getAndIncrement();
        if (i >= jobs.size()) {
            finishIndoorRequest(reqSeq, center, merged);
            return;
        }

        Job job = jobs.get(i);
        cdbg("IndoorMaps[#"+reqSeq+"]: req " + (i+1) + "/" + jobs.size() + " center=" + ll(job.c) + " macs=" + (job.m != null ? job.m.size() : 0));

        floorplanApi.requestNearbyVenues(job.c, job.m != null ? job.m : new ArrayList<>(), new FloorplanApi.VenuesCallback() {
            @Override public void onSuccess(@NonNull List<FloorplanModels.Venue> venues) {
                if (!isAdded()) return;

                Log.d("C_DEBUG", "FloorplanApi summary: " + FloorplanApi.getLastResponseSummary());

                synchronized (merged) {
                    for (FloorplanModels.Venue v : venues) {
                        if (v == null) continue;
                        String key = safeVenueKey(v);
                        if (key == null) continue;
                        merged.put(key, v);
                    }
                }

                // Early stop: once we have enough unique venues for the assignment demo, stop probing.
                if (merged.size() >= 4) {
                    idx.set(jobs.size());
                }

                h.postDelayed(next[0], INTER_REQUEST_DELAY_MS);
            }

            @Override public void onError(@NonNull String message) {
                if (!isAdded()) return;
                Log.d("C_DEBUG", "FloorplanApi summary: " + FloorplanApi.getLastResponseSummary());
                cdbg("IndoorMaps[#"+reqSeq+"]: req error: " + message);
                h.postDelayed(next[0], INTER_REQUEST_DELAY_MS);
            }
        });
    };

    next[0].run();
}

private void finishIndoorRequest(int reqSeq, @NonNull LatLng center, @NonNull Map<String, FloorplanModels.Venue> merged) {
    if (!isAdded()) return;

    setIndoorLoading(false);

    clearVenuePolygons();
    lastFetchedVenues.clear();

    synchronized (merged) {
        lastFetchedVenues.addAll(merged.values());
    }

    // Merge into persistent cache so the user can accumulate venues across requests.
    mergeVenuesIntoCache(lastFetchedVenues);
    addLocalLibraryVenueIfNearby(center);
    saveCachedVenues();

    List<FloorplanModels.Venue> toDraw = new ArrayList<>(venueCache.values());

    cdbg("IndoorMaps[#"+reqSeq+"]: done mergedUnique=" + lastFetchedVenues.size()
            + " cacheSize=" + toDraw.size());

    if (!toDraw.isEmpty()) {
        int drawn = drawVenuesAsPolygons(toDraw);
        cdbg("IndoorMaps[#"+reqSeq+"]: drawnPolygons=" + drawn);
        if (selectVenueButton != null) selectVenueButton.setEnabled(true);
        if (drawn == 0) showSnack("Venues returned but no geometry to draw (try another point).");
        return;
    }

    // If we still have nothing, try ONE lat/lon-only fallback request (some server builds support it).
    if (lastFetchedVenues.isEmpty() && floorplanApi != null) {
        setIndoorLoading(true);
        showSnack("No venues found — trying GPS-only fallback…");
        floorplanApi.requestNearbyVenues(center, new ArrayList<>(), new FloorplanApi.VenuesCallback() {
            @Override public void onSuccess(@NonNull List<FloorplanModels.Venue> venues) {
                setIndoorLoading(false);
                if (!isAdded()) return;

                mergeVenuesIntoCache(venues);
                addLocalLibraryVenueIfNearby(center);
                saveCachedVenues();

                List<FloorplanModels.Venue> toDraw2 = new ArrayList<>(venueCache.values());
                int drawn = drawVenuesAsPolygons(toDraw2);
                if (selectVenueButton != null) selectVenueButton.setEnabled(drawn > 0);
                if (drawn == 0) showSnack("No indoor venues returned near this point");
            }

            @Override public void onError(@NonNull String message) {
                setIndoorLoading(false);
                if (!isAdded()) return;
                showSnack("No indoor venues returned (" + message + ")");
            }
        });
    } else {
        showSnack("No indoor venues returned near this point");
    }
}
private @NonNull List<LatLng> buildProbeCenters(@NonNull LatLng center, double stepMeters) {
        List<LatLng> out = new ArrayList<>();
        out.add(center);

        // 8-neighbour grid around center.
        out.add(offsetLatLng(center, stepMeters, 0));
        out.add(offsetLatLng(center, -stepMeters, 0));
        out.add(offsetLatLng(center, 0, stepMeters));
        out.add(offsetLatLng(center, 0, -stepMeters));

        out.add(offsetLatLng(center, stepMeters, stepMeters));
        out.add(offsetLatLng(center, stepMeters, -stepMeters));
        out.add(offsetLatLng(center, -stepMeters, stepMeters));
        out.add(offsetLatLng(center, -stepMeters, -stepMeters));

        // A second ring helps cover the whole campus area (assignment has multiple venues close by).
        double step2 = stepMeters * 2.0;
        out.add(offsetLatLng(center, step2, 0));
        out.add(offsetLatLng(center, -step2, 0));
        out.add(offsetLatLng(center, 0, step2));
        out.add(offsetLatLng(center, 0, -step2));
        out.add(offsetLatLng(center, step2, step2));
        out.add(offsetLatLng(center, step2, -step2));
        out.add(offsetLatLng(center, -step2, step2));
        out.add(offsetLatLng(center, -step2, -step2));

        return out;
    }


/**
 * A compact probe set (center + 8 neighbors). We intentionally avoid the second ring to
 * keep requests bounded and prevent the server returning many empty results.
 */
private @NonNull List<LatLng> buildProbeCentersCompact(@NonNull LatLng center, double stepMeters) {
    List<LatLng> out = new ArrayList<>();
    out.add(center);
    out.add(offsetLatLng(center, stepMeters, 0));
    out.add(offsetLatLng(center, -stepMeters, 0));
    out.add(offsetLatLng(center, 0, stepMeters));
    out.add(offsetLatLng(center, 0, -stepMeters));

    out.add(offsetLatLng(center, stepMeters, stepMeters));
    out.add(offsetLatLng(center, stepMeters, -stepMeters));
    out.add(offsetLatLng(center, -stepMeters, stepMeters));
    out.add(offsetLatLng(center, -stepMeters, -stepMeters));
    return out;
}

    private @NonNull LatLng offsetLatLng(@NonNull LatLng c, double dNorthMeters, double dEastMeters) {
        // Rough conversion: 1 deg lat ~ 111,320 m; 1 deg lon scales by cos(lat).
        double dLat = dNorthMeters / 111320.0;
        double dLon = dEastMeters / (111320.0 * Math.cos(Math.toRadians(c.latitude)));
        return new LatLng(c.latitude + dLat, c.longitude + dLon);
    }

    private @NonNull List<String> capList(@NonNull List<String> in, int cap) {
        if (in.size() <= cap) return new ArrayList<>(in);
        return new ArrayList<>(in.subList(0, cap));
    }

    

    /**
     * 判断两组 Wi‑Fi MAC 列表是否“等价”（忽略大小写与顺序）。
     * 用途：避免 probes 中重复加入相同的 MAC 列表，减少对 API 的重复请求。
     */
    private boolean sameMacList(@NonNull List<String> a, @NonNull List<String> b) {
        if (a == b) return true;
        if (a.size() != b.size()) return false;
        java.util.HashSet<String> sa = new java.util.HashSet<>();
        for (String s : a) {
            if (s == null) continue;
            sa.add(s.trim().toLowerCase(java.util.Locale.US));
        }
        java.util.HashSet<String> sb = new java.util.HashSet<>();
        for (String s : b) {
            if (s == null) continue;
            sb.add(s.trim().toLowerCase(java.util.Locale.US));
        }
        return sa.equals(sb);
    }
private @Nullable String safeVenueKey(@NonNull FloorplanModels.Venue v) {
        // FloorplanModels.Venue uses public fields (venueId/venueName) in this project.
        String id = v.venueId;
        if (id != null && !id.trim().isEmpty()) return id.trim();

        String name = v.venueName;
        if (name != null && !name.trim().isEmpty()) return name.trim();
        return null;
    }


    /**
     * Local fallback: the live API may not return some campus venues (e.g. Library) even though
     * the assignment includes local floorplan images for them. To keep the demo usable, we add
     * a "local_library" venue when the picked point is near the Library bounds.
     *
     * NOTE: This does NOT replace the API flow; it only adds a fallback polygon + local floors.
     */
    private void addLocalLibraryVenueIfNearby(@Nullable LatLng ref) {
        if (ref == null) return;

        // If already present (from API or previous fallback), do nothing.
        for (FloorplanModels.Venue v : venueCache.values()) {
            if (v == null) continue;
            if ("local_library".equalsIgnoreCase(v.venueId)) return;
            String n = (v.venueName != null) ? v.venueName.toLowerCase() : "";
            if (n.contains("library") || n.contains("kenneth") || n.contains("murray")) return;
        }

        // Compute rough distance to the library center; only add if near campus.
        LatLng libCenter = new LatLng(
                (BuildingPolygon.LIBRARY_SW.latitude + BuildingPolygon.LIBRARY_NE.latitude) / 2.0,
                (BuildingPolygon.LIBRARY_SW.longitude + BuildingPolygon.LIBRARY_NE.longitude) / 2.0
        );

        double d = distanceMeters(ref, libCenter);
        // Tune radius to cover the Informatics campus area but avoid appearing elsewhere.
        final double ADD_RADIUS_M = 900.0;

        if (Double.isNaN(d) || d > ADD_RADIUS_M) {
            cdbg("LocalFallback: skip Library (distance=" + (int) d + "m, radius=" + (int) ADD_RADIUS_M + "m)");
            return;
        }

        List<LatLng> outline = new ArrayList<>(BuildingPolygon.LIBRARY_POLYGON);
        LatLngBounds bounds = new LatLngBounds(BuildingPolygon.LIBRARY_SW, BuildingPolygon.LIBRARY_NE);

        FloorplanModels.Venue lib = new FloorplanModels.Venue(
                "local_library",
                "kenneth_murray_library",
                outline,
                bounds,
                new ArrayList<>()
        );

        venueCache.put("local_library", lib);
        cdbg("LocalFallback: added Library venue (distance=" + (int) d + "m) outlinePts=" + outline.size() + " bounds=true floors=local");
    }



    /**
     * Merge returned venues into a persistent cache so previously discovered venues remain selectable.
     * If a venue comes back without outline, we keep any previously-cached outline for that venue.
     */
    private void mergeVenuesIntoCache(@Nullable List<FloorplanModels.Venue> venues) {
        if (venues == null || venues.isEmpty()) return;

        int added = 0;
        int updated = 0;
        int keptPrevOutline = 0;
        int keptPrevFloors = 0;

        // NOTE: FloorplanModels.Venue fields are final in this project.
        // So we cannot "patch" an existing instance in-place. Instead we create a merged copy.
        for (FloorplanModels.Venue incoming : venues) {
            if (incoming == null) continue;
            String key = safeVenueKey(incoming);
            if (TextUtils.isEmpty(key)) continue;

            FloorplanModels.Venue prev = venueCache.get(key);
            if (prev == null) {
                venueCache.put(key, incoming);
                added++;
                continue;
            }

            updated++;

            // Prefer incoming values when present; otherwise keep previous ones.
            String venueId = !TextUtils.isEmpty(incoming.venueId) ? incoming.venueId : prev.venueId;
            String venueName = !TextUtils.isEmpty(incoming.venueName) ? incoming.venueName : prev.venueName;

            // Outline: keep the richer one (incoming if it has points; else previous)
            boolean useIncomingOutline = (incoming.outline != null && !incoming.outline.isEmpty());
            if (!useIncomingOutline && prev.outline != null && !prev.outline.isEmpty()) keptPrevOutline++;
            List<LatLng> outline = useIncomingOutline ? incoming.outline : prev.outline;

            // Bounds: prefer incoming if present
            LatLngBounds bounds = (incoming.bounds != null) ? incoming.bounds : prev.bounds;

            // Floors: prefer incoming if it has floors; else keep previous
            boolean useIncomingFloors = (incoming.floors != null && !incoming.floors.isEmpty());
            if (!useIncomingFloors && prev.floors != null && !prev.floors.isEmpty()) keptPrevFloors++;
            List<FloorplanModels.Floor> floors = useIncomingFloors ? incoming.floors : prev.floors;

            venueCache.put(key, new FloorplanModels.Venue(venueId, venueName, outline, bounds, floors));
        }
        cdbg("mergeVenuesIntoCache: incoming=" + venues.size() + " added=" + added + " updated=" + updated
                + " keptPrevOutline=" + keptPrevOutline + " keptPrevFloors=" + keptPrevFloors
                + " cacheNow=" + venueCache.size());

    }


    private void loadCachedVenues() {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            String raw = prefs.getString(PREF_CACHED_VENUES_JSON, null);
            if (raw == null || raw.trim().isEmpty()) return;

            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;

                String id = o.optString("id", null);
                String name = o.optString("name", null);
                if (id == null || id.trim().isEmpty()) continue;

                // outline
                List<LatLng> outline = new ArrayList<>();
                JSONArray pts = o.optJSONArray("outline");
                if (pts != null) {
                    for (int p = 0; p < pts.length(); p++) {
                        JSONArray pair = pts.optJSONArray(p);
                        if (pair == null || pair.length() < 2) continue;
                        double lat = pair.optDouble(0, Double.NaN);
                        double lng = pair.optDouble(1, Double.NaN);
                        if (Double.isNaN(lat) || Double.isNaN(lng)) continue;
                        outline.add(new LatLng(lat, lng));
                    }
                }

                // bounds
                LatLngBounds b = null;
                JSONObject sw = o.optJSONObject("sw");
                JSONObject ne = o.optJSONObject("ne");
                if (sw != null && ne != null) {
                    double swLat = sw.optDouble("lat", Double.NaN);
                    double swLng = sw.optDouble("lng", Double.NaN);
                    double neLat = ne.optDouble("lat", Double.NaN);
                    double neLng = ne.optDouble("lng", Double.NaN);
                    if (!Double.isNaN(swLat) && !Double.isNaN(swLng) && !Double.isNaN(neLat) && !Double.isNaN(neLng)) {
                        try {
                            b = new LatLngBounds(new LatLng(swLat, swLng), new LatLng(neLat, neLng));
                        } catch (Exception ignore) {
                        }
                    }
                }

                venueCache.put(id, new FloorplanModels.Venue(id, name, outline, b, new ArrayList<>()));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load cached venues: " + e.getMessage());
        }
        cdbg("loadCachedVenues: cacheNow=" + venueCache.size());

    }

    /** Persist cached venues (outline + bounds + name). Floors are intentionally not persisted. */
    private void saveCachedVenues() {
        try {
            JSONArray arr = new JSONArray();
            for (FloorplanModels.Venue v : venueCache.values()) {
                if (v == null) continue;
                JSONObject o = new JSONObject();
                o.put("id", v.venueId);
                if (v.venueName != null) o.put("name", v.venueName);

                JSONArray outline = new JSONArray();
                if (v.outline != null) {
                    for (LatLng p : v.outline) {
                        if (p == null) continue;
                        JSONArray pair = new JSONArray();
                        pair.put(p.latitude);
                        pair.put(p.longitude);
                        outline.put(pair);
                    }
                }
                o.put("outline", outline);

                if (v.bounds != null) {
                    JSONObject sw = new JSONObject();
                    sw.put("lat", v.bounds.southwest.latitude);
                    sw.put("lng", v.bounds.southwest.longitude);
                    JSONObject ne = new JSONObject();
                    ne.put("lat", v.bounds.northeast.latitude);
                    ne.put("lng", v.bounds.northeast.longitude);
                    o.put("sw", sw);
                    o.put("ne", ne);
                }

                arr.put(o);
            }

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            prefs.edit().putString(PREF_CACHED_VENUES_JSON, arr.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to save cached venues: " + e.getMessage());
        }
        cdbg("saveCachedVenues: saved cacheSize=" + venueCache.size());

    }

    /**
     * Offset a LatLng by meters north/east (approx; good enough within ~100m scale).
     */
    private static @NonNull LatLng offsetMeters(@NonNull LatLng from, double northMeters, double eastMeters) {
        final double metersPerDegLat = 111_320.0;
        double dLat = northMeters / metersPerDegLat;
        double metersPerDegLon = metersPerDegLat * Math.cos(Math.toRadians(from.latitude));
        if (Math.abs(metersPerDegLon) < 1e-6) metersPerDegLon = metersPerDegLat;
        double dLon = eastMeters / metersPerDegLon;
        return new LatLng(from.latitude + dLat, from.longitude + dLon);
    }

    private void onMergedVenuesReady(@NonNull List<FloorplanModels.Venue> venues) {
        Log.d(TAG, "Floorplan merged venues=" + venues.size());

        // Some API responses encode coordinate pairs as [lon,lat] (common in GeoJSON).
        // In places where |lon| < 90 (e.g., UK: lon ~ -3), a simple heuristic cannot
        // reliably distinguish [lat,lon] vs [lon,lat]. We therefore fix obviously-wrong
        // geometry by choosing the orientation whose centroid is closest to the user-picked point.
        LatLng ref = (lastRequestCenter != null)
                ? lastRequestCenter
                : ((pickedCenter != null) ? pickedCenter : currentLocation);
        if (ref != null) {
            fixLonLatOrderIfNeeded(venues, ref);
        }

        lastFetchedVenues.clear();
        lastFetchedVenues.addAll(venues);

        // Merge into persistent cache so we can accumulate multiple venues across different locations.
        int before = venueCache.size();
        for (FloorplanModels.Venue v : venues) {
            if (v == null) continue;
            venueCache.put(v.venueId, v);
        }
        int added = venueCache.size() - before;
        saveCachedVenues();

        if (venues.isEmpty()) {
            Toast.makeText(requireContext(),
                    "No indoor maps nearby. Tips: Location ON + Wi‑Fi ON + stand near the building entrance.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        clearVenuePolygons();
        int drawn = drawVenuesAsPolygons(new ArrayList<>(venueCache.values()));
        zoomToVenues(venueCache.values(), /*animate=*/true);

        if (selectVenueButton != null) selectVenueButton.setEnabled(true);

        // If we couldn't draw any outline (missing geometry), open the picker so there is still an entry point.
        if (drawn == 0) {
            Toast.makeText(requireContext(),
                    "Indoor map found, but no outline geometry was returned. Use Select building.",
                    Toast.LENGTH_LONG).show();
            showVenuePickerDialog();
            return;
        }

        Toast.makeText(requireContext(),
                "Found " + venues.size() + " venues (" + added + " new). Total cached: " + venueCache.size() + ". Tap an outline or press Select building.",
                Toast.LENGTH_LONG).show();

        // Convenience: if there is only one venue, auto-select it so the user
        // immediately sees the floor selector and overlay.
        if (venues.size() == 1) {
            onVenueSelected(venues.get(0));
        }
    }

    /**
     * If a venue outline/bounds appears far away from the picked reference point, try swapping
     * lat/lon (lon/lat) and keep the version that lands much closer to the reference.
     */
    private void fixLonLatOrderIfNeeded(@NonNull List<FloorplanModels.Venue> venues, @NonNull LatLng ref) {
        for (FloorplanModels.Venue v : venues) {
            // Fix outline
            if (v.outline != null && v.outline.size() >= 3) {
                LatLng c1 = venueCenter(v);
                double d1 = distanceMeters(ref, c1);

                List<LatLng> swapped = new ArrayList<>(v.outline.size());
                for (LatLng p : v.outline) swapped.add(new LatLng(p.longitude, p.latitude));
                LatLng c2 = centroid(swapped);
                double d2 = distanceMeters(ref, c2);

                // If original is very far but swapped is much closer, apply swap.
                if (d1 > 2000 && d2 < d1 * 0.2) {
                    Log.w(TAG, "Fixing lon/lat order for venue " + v.venueId + " (" + d1 + "m -> " + d2 + "m)");
                    v.outline.clear();
                    v.outline.addAll(swapped);
                }
            }

            // Fix bounds
            if (v.bounds != null) {
                LatLngBounds b1 = v.bounds;
                LatLng c1 = new LatLng((b1.southwest.latitude + b1.northeast.latitude) / 2.0,
                        (b1.southwest.longitude + b1.northeast.longitude) / 2.0);
                double d1 = distanceMeters(ref, c1);

                LatLng sw2 = new LatLng(b1.southwest.longitude, b1.southwest.latitude);
                LatLng ne2 = new LatLng(b1.northeast.longitude, b1.northeast.latitude);
                LatLngBounds b2;
                try {
                    b2 = new LatLngBounds(
                            new LatLng(Math.min(sw2.latitude, ne2.latitude), Math.min(sw2.longitude, ne2.longitude)),
                            new LatLng(Math.max(sw2.latitude, ne2.latitude), Math.max(sw2.longitude, ne2.longitude))
                    );
                } catch (Exception e) {
                    b2 = null;
                }

                if (b2 != null) {
                    LatLng c2 = new LatLng((b2.southwest.latitude + b2.northeast.latitude) / 2.0,
                            (b2.southwest.longitude + b2.northeast.longitude) / 2.0);
                    double d2 = distanceMeters(ref, c2);
                    if (d1 > 2000 && d2 < d1 * 0.2) {
                        Log.w(TAG, "Fixing bounds lon/lat order for venue " + v.venueId + " (" + d1 + "m -> " + d2 + "m)");
                        // bounds is final reference, but we can NOT replace it; keep outline fix. If only bounds exists,
                        // drawing would still be wrong. In that case, we rely on outline OR floor bounds.
                    }
                }
            }
        }
    }

    // (centroid/distance/venueCenter helpers are defined later in the file)

    /** Alternate entry point: list venues so the user can select even if polygon click is difficult. */
    private void showVenuePickerDialog() {
        List<FloorplanModels.Venue> candidates = new ArrayList<>();
        if (!venueCache.isEmpty()) candidates.addAll(venueCache.values());
        else if (lastFetchedVenues != null) candidates.addAll(lastFetchedVenues);

        if (candidates.isEmpty()) {
            Toast.makeText(requireContext(), "No venues loaded yet. Press Find indoor maps first.", Toast.LENGTH_SHORT).show();
            return;
        }

        showVenuePickerDialog(candidates);
    }

    /** Alternate entry point: list venues so the user can select even if polygon click is difficult. */
    private void showVenuePickerDialog(@NonNull List<FloorplanModels.Venue> venues) {
        try {
            final LatLng ref = (lastRequestCenter != null)
                    ? lastRequestCenter
                    : ((pickedCenter != null) ? pickedCenter : currentLocation);

            List<FloorplanModels.Venue> sorted = new ArrayList<>(venues);
            // Sort by distance to the picked point first (closest building on top).
            Collections.sort(sorted, (a, b) -> {
                double da = distanceMeters(ref, venueCenter(a));
                double db = distanceMeters(ref, venueCenter(b));
                int c = Double.compare(da, db);
                if (c != 0) return c;
                String an = (a.venueName != null) ? a.venueName : "";
                String bn = (b.venueName != null) ? b.venueName : "";
                return an.compareToIgnoreCase(bn);
            });

            // Prefer venues that are close to the picked point (within a reasonable radius).
        // Do not aggressively filter by distance; we want to show all discovered venues
        // and let the user choose by tapping the outline or from the list.
        final double MAX_PICK_RADIUS_M = 1e9;
            List<FloorplanModels.Venue> close = new ArrayList<>();
            if (ref != null) {
                for (FloorplanModels.Venue v : sorted) {
                    double d = distanceMeters(ref, venueCenter(v));
                    if (!Double.isNaN(d) && d <= MAX_PICK_RADIUS_M) {
                        close.add(v);
                    }
                }
            }
            // Populate dialog candidates into a field so the click listener doesn't
            // capture a non-final local variable (some environments still complain).
            venuePickerCandidates.clear();
            venuePickerCandidates.addAll(close.isEmpty() ? sorted : close);

            String[] items = new String[venuePickerCandidates.size()];
            for (int i = 0; i < venuePickerCandidates.size(); i++) {
                FloorplanModels.Venue v = venuePickerCandidates.get(i);
                String name = (v.venueName != null && !v.venueName.isEmpty()) ? v.venueName : "(Unnamed venue)";
                double d = distanceMeters(ref, venueCenter(v));
                if (!Double.isNaN(d) && d < 9_999_999) {
                    items[i] = String.format(Locale.US, "%s  (%.0f m)", name, d);
                } else {
                    items[i] = name;
                }
            }

            new AlertDialog.Builder(requireContext())
                    .setTitle("Select a building")
                    .setItems(items, (d, which) -> {
                        if (which >= 0 && which < venuePickerCandidates.size()) {
                            onVenueSelected(venuePickerCandidates.get(which));
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Exception e) {
            Log.w(TAG, "showVenuePickerDialog failed: " + e.getMessage());
        }
    }

    // ---------------- Wi‑Fi MAC helpers ----------------

        private @NonNull List<String> getNearbyWifiMacsCached() {
        List<String> out = new ArrayList<>();

        try {
            WifiManager wm = (WifiManager) requireContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return out;

            @SuppressLint("MissingPermission")
            List<ScanResult> results = wm.getScanResults();
            if (results == null || results.isEmpty()) return out;

            // Sort by signal strength (descending). Note: we intentionally do NOT only take the strongest APs,
            // because that tends to return only one venue. We want a mix of strong + mid + weak APs so the
            // API can return multiple nearby venues (e.g., the 4 AOIs in the assignment).
            results.sort((a, b) -> Integer.compare(b.level, a.level));

            cdbg("WiFiScan: rawScanResults=" + results.size() + " wifiEnabled=" + wm.isWifiEnabled());
            int top = Math.min(5, results.size());
            for (int i = 0; i < top; i++) {
                ScanResult r = results.get(i);
                if (r == null) continue;
                cdbg("WiFiScan: top[" + i + "] bssid=" + r.BSSID + " level=" + r.level + " freq=" + r.frequency);
            }

            // 1) Always keep a chunk of strongest APs (for reliability)
            final int strongestKeep = Math.min(40, results.size());
            HashSet<String> seen = new HashSet<>();
            for (int i = 0; i < strongestKeep && out.size() < MAX_WIFI_MACS; i++) {
                String bssid = results.get(i).BSSID;
                if (bssid == null) continue;
                bssid = bssid.trim().toLowerCase(Locale.US);
                if (!seen.add(bssid)) continue;
                out.add(bssid);
            }

            // 2) Fill the rest by sampling evenly across the full sorted list (adds mid/weak APs)
            int target = Math.min(MAX_WIFI_MACS, results.size());
            if (out.size() < target) {
                int remaining = target - out.size();
                int step = Math.max(1, results.size() / Math.max(remaining, 1));
                for (int i = 0; i < results.size() && out.size() < target; i += step) {
                    String bssid = results.get(i).BSSID;
                    if (bssid == null) continue;
                    bssid = bssid.trim().toLowerCase(Locale.US);
                    if (!seen.add(bssid)) continue;
                    out.add(bssid);
                }
            }

            // 3) If still not enough (rare), add from the tail
            for (int i = results.size() - 1; i >= 0 && out.size() < Math.min(MAX_WIFI_MACS, results.size()); i--) {
                String bssid = results.get(i).BSSID;
                if (bssid == null) continue;
                bssid = bssid.trim().toLowerCase(Locale.US);
                if (!seen.add(bssid)) continue;
                out.add(bssid);
            }

        } catch (Throwable t) {
            Log.w(TAG, "getNearbyWifiMacsCached failed: " + t.getMessage());
        }

        return out;
    }


/**
 * Return the strongest (highest RSSI) BSSID list from the last Wi‑Fi scan.
 * This is useful when we want a *focused* request (e.g., after the user selected a venue)
 * to maximize the chance the server returns floorplan images (floors + imageUrl).
 */
private @NonNull List<String> getNearbyWifiMacsStrongest(int maxCount) {
    List<String> out = new ArrayList<>();
    try {
        WifiManager wm = (WifiManager) requireContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return out;
        @SuppressLint("MissingPermission")
        List<ScanResult> results = wm.getScanResults();
        if (results == null || results.isEmpty()) return out;

        results.sort((a, b) -> Integer.compare(b.level, a.level));
        HashSet<String> seen = new HashSet<>();
        int cap = Math.min(Math.max(maxCount, 0), results.size());
        for (int i = 0; i < cap; i++) {
            ScanResult r = results.get(i);
            if (r == null) continue;
            String bssid = r.BSSID;
            if (bssid == null) continue;
            bssid = bssid.trim().toLowerCase(Locale.US);
            // very defensive filtering
            if (bssid.length() < 11) continue;
            if (!seen.add(bssid)) continue;
            out.add(bssid);
        }
    } catch (Throwable t) {
        Log.w(TAG, "getNearbyWifiMacsStrongest failed: " + t.getMessage());
    }
    return out;
}

    private void showMacInputDialog(@NonNull LatLng center, int scannedCount) {
        EditText input = new EditText(requireContext());
        input.setHint("Paste BSSID MACs here (comma/space/newline separated)\nExample:\nAA:BB:CC:DD:EE:FF\n11:22:33:44:55:66");
        input.setMinLines(6);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        if (lastManualMacText != null && !lastManualMacText.isEmpty()) {
            input.setText(lastManualMacText);
        }

        new AlertDialog.Builder(requireContext())
                .setTitle("Wi‑Fi scan unavailable (APs=" + scannedCount + ")")
                .setMessage("Emulator often cannot access real Wi‑Fi BSSID.\n\nIf you test on a real phone: this dialog should NOT appear (you will have many APs).\n\nYou can paste MAC list from another phone/logcat.")
                .setView(input)
                .setPositiveButton("OK", (d, which) -> {
                    String text = input.getText().toString();
                    lastManualMacText = text;
                    List<String> macs = parseMacList(text);
                    if (macs.size() < MIN_MACS_FOR_API) {
                        Toast.makeText(requireContext(),
                                "Not enough MACs (" + macs.size() + "). Need at least " + MIN_MACS_FOR_API,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(requireContext(), "Requesting indoor maps… (Manual MACs=" + macs.size() + ")", Toast.LENGTH_SHORT).show();
                    requestVenuesMultiProbeWithFallback(center, macs);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private @NonNull List<String> parseMacList(@Nullable String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;

        String[] parts = text.split("[^0-9a-fA-F:]+");
        HashSet<String> seen = new HashSet<>();

        for (String p : parts) {
            if (p == null) continue;
            String s = p.trim().toLowerCase(Locale.US);
            if (s.isEmpty()) continue;
            if (!s.matches("([0-9a-f]{2}:){5}[0-9a-f]{2}")) continue;
            if ("02:00:00:00:00:00".equals(s)) continue;
            if (seen.add(s)) out.add(s);
        }
        return out;
    }

    // ---------------- Draw venues + select floors ----------------

    private void clearVenuePolygons() {
        for (Polygon p : venuePolygons) p.remove();
        venuePolygons.clear();
        polyToVenue.clear();
        selectedPolygon = null;

        for (Marker m : venueCenterMarkers) {
            if (m != null) m.remove();
        }
        venueCenterMarkers.clear();
        markerToVenue.clear();
    }

    private int drawVenuesAsPolygons(@NonNull List<FloorplanModels.Venue> venues) {
        if (gMap == null) return 0;

        int drawn = 0;
        LatLngBounds boundsForZoom = null;
        LatLng ref = (lastRequestCenter != null) ? lastRequestCenter : pickedCenter;
        double bestDist = Double.POSITIVE_INFINITY;
        LatLngBounds bestBounds = null;

        // Also add a few center markers (helps selection when polygon clicking is hard)
        // Clear previous markers by reusing clearVenuePolygons() only; markers are not stored.

        for (FloorplanModels.Venue v : venues) {
            List<LatLng> pts = null;

            if (v.outline != null && v.outline.size() >= 3) {
                pts = v.outline;
            } else {
                
LatLngBounds b = v.bounds;
if (b == null && v.floors != null && !v.floors.isEmpty()) {
    FloorplanModels.Floor f0 = v.floors.get(0);
    if (f0 != null) b = f0.bounds;
}
if (b != null && ref != null) {
    b = bestBoundsForRef(b, ref);
}
if (b != null) {
                    LatLng sw = b.southwest;
                    LatLng ne = b.northeast;
                    List<LatLng> rect = new ArrayList<>();
                    rect.add(new LatLng(sw.latitude, sw.longitude));
                    rect.add(new LatLng(sw.latitude, ne.longitude));
                    rect.add(new LatLng(ne.latitude, ne.longitude));
                    rect.add(new LatLng(ne.latitude, sw.longitude));
                    pts = rect;
                }
            }

            cdbg("Draw: venue " + venueDbg(v) + " ref=" + ll(ref) + " using=" + ((v.outline != null && v.outline.size() >= 3) ? "outline" : "boundsRect") + " pts=" + ((pts == null) ? 0 : pts.size()));

            if (pts == null || pts.size() < 3) {
                cdbgW("Draw: SKIP(no geometry) " + venueDbg(v));
                if (v.outline == null || v.outline.size() < 3) cdbgW("Draw:   outline missing/too small");
                if (v.bounds == null) cdbgW("Draw:   bounds missing");
                if (v.floors == null || v.floors.isEmpty()) cdbgW("Draw:   floors empty");
                continue;
            }

            PolygonOptions opt = new PolygonOptions()
                    .addAll(pts)
                    .strokeWidth(6f)
                    .strokeColor(polyStrokeColor)
                    .fillColor(polyFillColor)
                    .zIndex(1000f);

            Polygon poly = gMap.addPolygon(opt);
            poly.setClickable(true);
            venuePolygons.add(poly);
            polyToVenue.put(poly, v);
            drawn++;

            // Build per-venue bounds
            
LatLngBounds vb;
if (v.bounds != null) {
    vb = (ref != null) ? bestBoundsForRef(v.bounds, ref) : v.bounds;
} else {
                LatLngBounds.Builder bld = new LatLngBounds.Builder();
                for (LatLng p : pts) bld.include(p);
                vb = bld.build();
            }

            // Choose zoom target = closest venue to the picked point
            if (ref != null) {
                double d = distanceMeters(ref, venueCenterFromBoundsOrOutline(v, vb, pts));
                if (d < bestDist) {
                    bestDist = d;
                    bestBounds = vb;
                }
            }

            // Add a small marker at venue center (makes selection easier and helps debugging
            // when polygon stroke is hard to see on satellite imagery).
            LatLng c = venueCenterFromBoundsOrOutline(v, vb, pts);
            if (c != null) {
                Marker m = gMap.addMarker(new MarkerOptions()
                        .position(c)
                        .title(v.toString())
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_MAGENTA))
                        .anchor(0.5f, 0.5f));
                if (m != null) {
                    venueCenterMarkers.add(m);
                    markerToVenue.put(m, v);
                }
            }
        }

        cdbg("drawVenuesAsPolygons: inputVenues=" + venues.size() + " drawnPolygons=" + drawn + " cacheSize=" + venueCache.size());

        boundsForZoom = (bestBounds != null) ? bestBounds : null;

        if (boundsForZoom != null) {
            try {
                gMap.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsForZoom, 120));
            } catch (Exception e) {
                LatLng sw = boundsForZoom.southwest;
                LatLng ne = boundsForZoom.northeast;
                LatLng c = new LatLng((sw.latitude + ne.latitude) / 2.0, (sw.longitude + ne.longitude) / 2.0);
                gMap.animateCamera(CameraUpdateFactory.newLatLngZoom(c, 19f));
            }
        }

        return drawn;
    }

    private void zoomToVenues(@NonNull Collection<FloorplanModels.Venue> venues, boolean animate) {
        if (gMap == null || venues.isEmpty()) return;
        LatLngBounds.Builder b = new LatLngBounds.Builder();
        boolean hasAny = false;
        for (FloorplanModels.Venue v : venues) {
            if (v == null) continue;
            List<LatLng> pts = v.outline;
            if (pts == null || pts.isEmpty()) continue;
            for (LatLng p : pts) {
                if (p == null) continue;
                b.include(p);
                hasAny = true;
            }
        }
        if (!hasAny) return;
        try {
            LatLngBounds bounds = b.build();
            int padPx = Math.round(48f * getResources().getDisplayMetrics().density);
            if (animate) gMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padPx));
            else gMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padPx));
        } catch (Exception ignored) {
            // If the map has not laid out yet, bounds update can throw. It's safe to ignore.
        }
    }

    // ---------------- Distance helpers (for "pick point -> nearest building") ----------------

    private static @NonNull LatLng centroid(@NonNull List<LatLng> pts) {
        double lat = 0.0, lng = 0.0;
        int n = 0;
        for (LatLng p : pts) {
            if (p == null) continue;
            lat += p.latitude;
            lng += p.longitude;
            n++;
        }
        if (n == 0) return new LatLng(0, 0);
        return new LatLng(lat / n, lng / n);
    }

    private static double distanceMeters(@Nullable LatLng a, @Nullable LatLng b) {
        if (a == null || b == null) return Double.NaN;
        final double R = 6371000.0; // meters
        double lat1 = Math.toRadians(a.latitude);
        double lat2 = Math.toRadians(b.latitude);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.longitude - a.longitude);
        double sin1 = Math.sin(dLat / 2.0);
        double sin2 = Math.sin(dLon / 2.0);
        double h = sin1 * sin1 + Math.cos(lat1) * Math.cos(lat2) * sin2 * sin2;
        double c = 2.0 * Math.asin(Math.min(1.0, Math.sqrt(h)));
        return R * c;
    }


private static @Nullable LatLngBounds swapBoundsLatLon(@Nullable LatLngBounds b) {
    if (b == null) return null;
    LatLng sw = b.southwest;
    LatLng ne = b.northeast;
    LatLng sw2 = new LatLng(sw.longitude, sw.latitude);
    LatLng ne2 = new LatLng(ne.longitude, ne.latitude);
    try {
        return new LatLngBounds(
                new LatLng(Math.min(sw2.latitude, ne2.latitude), Math.min(sw2.longitude, ne2.longitude)),
                new LatLng(Math.max(sw2.latitude, ne2.latitude), Math.max(sw2.longitude, ne2.longitude))
        );
    } catch (Exception e) {
        return null;
    }
}

private static @NonNull LatLngBounds bestBoundsForRef(@NonNull LatLngBounds original, @NonNull LatLng ref) {
    LatLngBounds swapped = swapBoundsLatLon(original);
    if (swapped == null) return original;

    LatLng c1 = new LatLng(
            (original.southwest.latitude + original.northeast.latitude) / 2.0,
            (original.southwest.longitude + original.northeast.longitude) / 2.0
    );
    LatLng c2 = new LatLng(
            (swapped.southwest.latitude + swapped.northeast.latitude) / 2.0,
            (swapped.southwest.longitude + swapped.northeast.longitude) / 2.0
    );

    double d1 = distanceMeters(ref, c1);
    double d2 = distanceMeters(ref, c2);

    if (d1 > 2000 && d2 < d1 * 0.2) return swapped;
    return original;
}


    private static @Nullable LatLng venueCenter(@Nullable FloorplanModels.Venue v) {
        if (v == null) return null;
        if (v.bounds != null) {
            LatLng sw = v.bounds.southwest;
            LatLng ne = v.bounds.northeast;
            return new LatLng((sw.latitude + ne.latitude) / 2.0, (sw.longitude + ne.longitude) / 2.0);
        }
        if (v.outline != null && !v.outline.isEmpty()) {
            double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
            double minLng = Double.POSITIVE_INFINITY, maxLng = Double.NEGATIVE_INFINITY;
            for (LatLng p : v.outline) {
                if (p == null) continue;
                minLat = Math.min(minLat, p.latitude);
                maxLat = Math.max(maxLat, p.latitude);
                minLng = Math.min(minLng, p.longitude);
                maxLng = Math.max(maxLng, p.longitude);
            }
            if (Double.isFinite(minLat) && Double.isFinite(maxLat) && Double.isFinite(minLng) && Double.isFinite(maxLng)) {
                return new LatLng((minLat + maxLat) / 2.0, (minLng + maxLng) / 2.0);
            }
        }
        return null;
    }

    private static @Nullable LatLng venueCenterFromBoundsOrOutline(@NonNull FloorplanModels.Venue v,
                                                                  @Nullable LatLngBounds vb,
                                                                  @Nullable List<LatLng> pts) {
        if (vb != null) {
            LatLng sw = vb.southwest;
            LatLng ne = vb.northeast;
            return new LatLng((sw.latitude + ne.latitude) / 2.0, (sw.longitude + ne.longitude) / 2.0);
        }
        if (pts != null && !pts.isEmpty()) {
            double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
            double minLng = Double.POSITIVE_INFINITY, maxLng = Double.NEGATIVE_INFINITY;
            for (LatLng p : pts) {
                if (p == null) continue;
                minLat = Math.min(minLat, p.latitude);
                maxLat = Math.max(maxLat, p.latitude);
                minLng = Math.min(minLng, p.longitude);
                maxLng = Math.max(maxLng, p.longitude);
            }
            if (Double.isFinite(minLat) && Double.isFinite(maxLat) && Double.isFinite(minLng) && Double.isFinite(maxLng)) {
                return new LatLng((minLat + maxLat) / 2.0, (minLng + maxLng) / 2.0);
            }
        }
        return venueCenter(v);
    }

    // ---------------- Local floorplan mapping ----------------
    // The assignment package includes several floorplan PNGs under res/drawable.
    // The live API may return floors=0 (no image urls), so we map venues to these
    // local resources as a practical fallback.
    private static final class FloorSpec {
        final String label;
        final int drawableResId;
        FloorSpec(@NonNull String label, int drawableResId) {
            this.label = label;
            this.drawableResId = drawableResId;
        }
    }

    private @NonNull List<FloorSpec> getLocalFloorsForVenue(@NonNull FloorplanModels.Venue venue) {
        // FloorplanModels.Venue stores the API "name" in venueName.
        String name = (venue.venueName != null) ? venue.venueName.toLowerCase() : "";
        List<FloorSpec> out = new ArrayList<>();

        // Nucleus building (several levels)
        if (name.contains("nucleus")) {
            out.add(new FloorSpec("LG", R.drawable.nucleuslg));
            out.add(new FloorSpec("G",  R.drawable.nucleusg));
            out.add(new FloorSpec("1",  R.drawable.nucleus1));
            out.add(new FloorSpec("2",  R.drawable.nucleus2));
            out.add(new FloorSpec("3",  R.drawable.nucleus3));
            // Optional: overview
            // out.add(new FloorSpec("Overview", R.drawable.nucleusground));
            return out;
        }

        // Library (G + 1..3)
        if (name.contains("library") || name.contains("kenneth") || name.contains("murray")) {
            out.add(new FloorSpec("G", R.drawable.libraryg));
            out.add(new FloorSpec("1", R.drawable.library1));
            out.add(new FloorSpec("2", R.drawable.library2));
            out.add(new FloorSpec("3", R.drawable.library3));
            return out;
        }

        // Generic floor plans shipped with the assignment (commonly used for Murchison House in demos)
        // Expected order (lowest -> highest): LG, UG, 1, 2, 3
        if (name.contains("murchison")) {
            out.add(new FloorSpec("LG", R.drawable.floor_lg));
            out.add(new FloorSpec("UG", R.drawable.floor_ug));
            out.add(new FloorSpec("1", R.drawable.floor_1));
            out.add(new FloorSpec("2", R.drawable.floor_2));
            out.add(new FloorSpec("3", R.drawable.floor_3));
            return out;
        }

        // Other venues may exist but do not have bundled images.
        return out;
    }

    private void onVenueSelected(@NonNull FloorplanModels.Venue venue) {
        onVenueSelected(venue, null);
    }

    private void onVenueSelected(@NonNull FloorplanModels.Venue venue, @Nullable Polygon fromPolygon) {
        selectedVenue = venue;

        // Highlight selected polygon (nice UX)
        updatePolygonSelection(venue, fromPolygon);

        // Persist selection for data submission (C4)
        saveSelectedVenueToPrefs(venue);

        if (selectedVenueText != null) {
            selectedVenueText.setText("Selected: " + prettyVenueName(venue.venueName));
        }

        showSnack("Selected: " + prettyVenueName(venue.venueName));
        if (indoorMapOverlay != null) indoorMapOverlay.setSelectedVenue(venue);

        // -------- Floors / Indoor map overlay --------
        // The OpenPositioning API sometimes returns only an outline/bounds with floors=0.
        // In that case, we fall back to local floorplan images included in res/drawable.

        // Prefer venue bounds; if missing, derive bounds from outline.
        LatLngBounds venueBounds = venue.bounds;
        if (venueBounds == null && venue.outline != null && !venue.outline.isEmpty()) {
            double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
            double minLng = Double.POSITIVE_INFINITY, maxLng = Double.NEGATIVE_INFINITY;
            for (LatLng p : venue.outline) {
                if (p == null) continue;
                minLat = Math.min(minLat, p.latitude);
                maxLat = Math.max(maxLat, p.latitude);
                minLng = Math.min(minLng, p.longitude);
                maxLng = Math.max(maxLng, p.longitude);
            }
            if (Double.isFinite(minLat) && Double.isFinite(maxLat) && Double.isFinite(minLng) && Double.isFinite(maxLng)) {
                try {
                    venueBounds = new LatLngBounds(new LatLng(minLat, minLng), new LatLng(maxLat, maxLng));
                } catch (Exception ignore) {}
            }
        }

        // Bounds used for placing local drawable overlays and for optional refit.
        LatLngBounds placementBounds = venueBounds;

        List<FloorSpec> localFloors = getLocalFloorsForVenue(venue);
        List<FloorplanModels.Floor> floors = venue.floorsSorted();
// Remote floors are only "usable" if they include a non-empty imageUrl.
List<FloorplanModels.Floor> remoteFloorsUsable = new ArrayList<>();
if (floors != null) {
    for (FloorplanModels.Floor f : floors) {
        if (f == null) continue;
        if (f.imageUrl == null) continue;
        if (f.imageUrl.trim().isEmpty()) continue;
        remoteFloorsUsable.add(f);
    }
}


        if ((floors == null || floors.isEmpty()) && (localFloors == null || localFloors.isEmpty())) {
            floorSpinner.setVisibility(View.GONE);
            setFloorControlsVisibility(View.GONE);
            showSnack("No floorplan images for this venue");
            return;
        }

        // Re-create overlay helper with enough slots.
        int needFloors = 0;
        if (floors != null) needFloors = Math.max(needFloors, floors.size());
        if (localFloors != null) needFloors = Math.max(needFloors, localFloors.size());
        if (needFloors < 1) needFloors = 1;
        try {
            if (indoorMapOverlay != null) indoorMapOverlay.clearOverlay();
        } catch (Exception ignore) {}
        indoorMapOverlay = new IndoorMapFragment(requireContext(), gMap, Math.max(needFloors, 1));
        indoorMapOverlay.setSelectedVenue(venue);

        // Case A: remote floors exist -> use server imageUrl
        if (remoteFloorsUsable != null && !remoteFloorsUsable.isEmpty()) {
            // Keep selectedVenue.floors consistent for up/down buttons
            venue.floors.clear();
            venue.floors.addAll(remoteFloorsUsable);

            floorSpinner.setVisibility(View.VISIBLE);
            setFloorControlsVisibility(View.VISIBLE);

            ArrayAdapter<FloorplanModels.Floor> adapter = new ArrayAdapter<>(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    venue.floors
            );
            floorSpinner.setAdapter(adapter);

            floorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    FloorplanModels.Floor floor = venue.floors.get(position);
                    if (indoorMapOverlay != null) indoorMapOverlay.showFloor(floor);
                    saveSelectedFloorToPrefs(venue, floor.toString(), position);
                    updateSelectedVenueLabel(venue, floor.toString());
                }

                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });

            floorSpinner.setSelection(0);
            if (indoorMapOverlay != null) indoorMapOverlay.showFloor(venue.floors.get(0));
            saveSelectedFloorToPrefs(venue, venue.floors.get(0).toString(), 0);
            updateSelectedVenueLabel(venue, venue.floors.get(0).toString());
        }
        // Case B: no remote floors -> use local drawable overlays
        else {
            if (placementBounds == null) {
                floorSpinner.setVisibility(View.GONE);
                setFloorControlsVisibility(View.GONE);
                showSnack("Venue has no bounds; can't place local floorplan overlay");
                return;
            }

            // Add all local floors
            for (int i = 0; i < localFloors.size(); i++) {
                FloorSpec fs = localFloors.get(i);
                if (indoorMapOverlay != null) indoorMapOverlay.addFloor(i, fs.drawableResId, placementBounds);
            }

            // Spinner shows labels only
            List<String> labels = new ArrayList<>();
            for (FloorSpec fs : localFloors) labels.add(fs.label);

            floorSpinner.setVisibility(View.VISIBLE);
            setFloorControlsVisibility(View.VISIBLE);

            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    labels
            );
            floorSpinner.setAdapter(adapter);

            floorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (indoorMapOverlay != null) indoorMapOverlay.switchFloor(position);
                    String label = labels.get(position);
                    saveSelectedFloorToPrefs(venue, label, position);
                    updateSelectedVenueLabel(venue, label);
                }

                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });

            floorSpinner.setSelection(0);
            if (indoorMapOverlay != null) indoorMapOverlay.switchFloor(0);
            if (!labels.isEmpty()) {
                saveSelectedFloorToPrefs(venue, labels.get(0), 0);
                updateSelectedVenueLabel(venue, labels.get(0));
            }
        }

        // If server didn't provide usable floorplan images, try one extra request using strongest Wi‑Fi list.
        if ((remoteFloorsUsable == null || remoteFloorsUsable.isEmpty()) && placementBounds != null) {
            tryFetchRemoteFloorsForVenue(venue, placementBounds);
        }

// Optionally zoom to venue bounds
LatLngBounds b = venue.bounds;
if (b == null && !venue.floors.isEmpty()) b = venue.floors.get(0).bounds;

LatLng ref = (lastRequestCenter != null) ? lastRequestCenter : ((pickedCenter != null) ? pickedCenter : currentLocation);
if (b != null && ref != null) {
    b = bestBoundsForRef(b, ref);
}

if (b != null) {
            try {
                gMap.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 120));
            } catch (Exception ignore) {}
        }
    }


/**
 * 选中建筑后，如果 API 初次只返回了 outline/bounds（floors=0），这里会尝试用“更强的 Wi‑Fi AP 列表”
 * 再请求一次，以提高拿到 floors + imageUrl 的概率（满足 Goal 5.4: 下载/缓存 floorplan）。
 *
 * 说明：
 * - 这个请求不会改变“找附近建筑”逻辑，只是在“你已经点选某栋楼”之后补全楼层信息。
 * - 如果服务器端本身没有该建筑的 floorplan（或权限/数据缺失），会保持本地示例图，不会导致崩溃。
 */
private void tryFetchRemoteFloorsForVenue(@NonNull FloorplanModels.Venue venue, @Nullable LatLngBounds venueBounds) {
    if (!isAdded() || floorplanApi == null || gMap == null) return;

    // 已经有可用远程 floorplan，就不重复请求
    if (venue.floors != null) {
        for (FloorplanModels.Floor f : venue.floors) {
            if (f != null && f.imageUrl != null && !f.imageUrl.trim().isEmpty()) return;
        }
    }

    // 需要足够的 Wi‑Fi AP 才有意义
    List<String> macsStrong = getNearbyWifiMacsStrongest(35);
    if (macsStrong.size() < MIN_MACS_FOR_API) {
        cdbg("VenueDetails: skip (not enough MACs=" + macsStrong.size() + ")");
        return;
    }

    LatLng reqCenter = venueCenterFromBoundsOrOutline(venue, venueBounds, venue.outline);
    if (reqCenter == null) reqCenter = (pickedCenter != null ? pickedCenter : currentLocation);
    if (reqCenter == null) return;

    final int reqSeq = indoorReqSeq.incrementAndGet();
    cdbg("VenueDetails[#"+reqSeq+"]: requesting floors for " + prettyVenueName(venue.venueName)
            + " center=" + ll(reqCenter) + " macsStrong=" + macsStrong.size());

    showSnack("Fetching floorplans from server…");

    floorplanApi.requestNearbyVenues(reqCenter, macsStrong, new FloorplanApi.VenuesCallback() {
        @Override public void onSuccess(@NonNull List<FloorplanModels.Venue> venues) {
            if (!isAdded() || gMap == null) return;

            FloorplanModels.Venue best = null;

            // 1) 优先匹配 ID / 名字
            for (FloorplanModels.Venue v : venues) {
                if (v == null) continue;
                if (sameVenue(v, venue)) { best = v; break; }
            }

            // 2) 退化：按中心点距离挑最近的
            if (best == null) {
                LatLng ref = venueCenterFromBoundsOrOutline(venue, venueBounds, venue.outline);
                double bestD = Double.POSITIVE_INFINITY;
                for (FloorplanModels.Venue v : venues) {
                    if (v == null) continue;
                    double d = distanceMeters(ref, venueCenter(v));
                    if (d < bestD) {
                        bestD = d;
                        best = v;
                    }
                }
            }

            if (best == null || best.floors == null || best.floors.isEmpty()) {
                cdbg("VenueDetails[#"+reqSeq+"]: no floors returned (venues=" + venues.size() + ")");
                return;
            }

            // 只保留带 imageUrl 的楼层
            List<FloorplanModels.Floor> usable = new ArrayList<>();
            List<FloorplanModels.Floor> sorted = best.floorsSorted();
            for (FloorplanModels.Floor f : sorted) {
                if (f == null) continue;
                if (f.imageUrl == null) continue;
                if (f.imageUrl.trim().isEmpty()) continue;
                usable.add(f);
            }

            if (usable.isEmpty()) {
                cdbg("VenueDetails[#"+reqSeq+"]: floors exist but no imageUrl");
                return;
            }

            // 更新当前选中 venue 的 floors（保持对象一致，避免 tag/映射失效）
            venue.floors.clear();
            venue.floors.addAll(usable);

            // 重新构建 overlay helper，并切换到远程 floorplan
            try {
                if (indoorMapOverlay != null) indoorMapOverlay.clearOverlay();
            } catch (Exception ignore) {}
            indoorMapOverlay = new IndoorMapFragment(requireContext(), gMap, Math.max(usable.size(), 1));
            indoorMapOverlay.setSelectedVenue(venue);

            floorSpinner.setVisibility(View.VISIBLE);
            setFloorControlsVisibility(View.VISIBLE);

            ArrayAdapter<FloorplanModels.Floor> adapter = new ArrayAdapter<>(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    venue.floors
            );
            floorSpinner.setAdapter(adapter);

            floorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    FloorplanModels.Floor floor = venue.floors.get(position);
                    if (indoorMapOverlay != null) indoorMapOverlay.showFloor(floor);
                    saveSelectedFloorToPrefs(venue, floor.toString(), position);
                    updateSelectedVenueLabel(venue, floor.toString());
                }

                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });

            floorSpinner.setSelection(0);
            if (indoorMapOverlay != null) indoorMapOverlay.showFloor(venue.floors.get(0));
            saveSelectedFloorToPrefs(venue, venue.floors.get(0).toString(), 0);
            updateSelectedVenueLabel(venue, venue.floors.get(0).toString());

            showSnack("Server floorplans loaded ✔");
        }

        @Override public void onError(@NonNull String message) {
            cdbg("VenueDetails[#"+reqSeq+"]: error: " + message);
        }
    });
}

    // -------------------- UX helpers (selection + persistence) --------------------

    private void updatePolygonSelection(@NonNull FloorplanModels.Venue selected, @Nullable Polygon fromPolygon) {
        // Prefer the clicked polygon if provided
        if (fromPolygon != null) selectedPolygon = fromPolygon;

        for (Polygon p : polyToVenue.keySet()) {
            FloorplanModels.Venue v = polyToVenue.get(p);
            boolean isSel = (p == selectedPolygon) || (v != null && sameVenue(v, selected));
            if (isSel) {
                selectedPolygon = p;
                p.setStrokeColor(polySelectedStrokeColor);
                p.setFillColor(polySelectedFillColor);
                p.setStrokeWidth(8f);
                p.setZIndex(1100f);
            } else {
                p.setStrokeColor(polyStrokeColor);
                p.setFillColor(polyFillColor);
                p.setStrokeWidth(6f);
                p.setZIndex(1000f);
            }
        }
    }

    private boolean sameVenue(@NonNull FloorplanModels.Venue a, @NonNull FloorplanModels.Venue b) {
        if (!TextUtils.isEmpty(a.venueId) && a.venueId.equals(b.venueId)) return true;
        if (!TextUtils.isEmpty(a.venueName) && !TextUtils.isEmpty(b.venueName)) {
            return a.venueName.equalsIgnoreCase(b.venueName);
        }
        return false;
    }

    private void saveSelectedVenueToPrefs(@NonNull FloorplanModels.Venue venue) {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
            sp.edit()
                    .putString(PREF_SELECTED_VENUE_ID, venue.venueId)
                    .putString(PREF_SELECTED_VENUE_NAME, venue.venueName)
                    .apply();
        } catch (Exception ignore) {}
    }

    private void saveSelectedFloorToPrefs(@NonNull FloorplanModels.Venue venue, @NonNull String floorLabel, int floorIndex) {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
            sp.edit()
                    .putString(PREF_SELECTED_VENUE_ID, venue.venueId)
                    .putString(PREF_SELECTED_VENUE_NAME, venue.venueName)
                    .putString(PREF_SELECTED_FLOOR_LABEL, floorLabel)
                    .putInt(PREF_SELECTED_FLOOR_INDEX, floorIndex)
                    .apply();
        } catch (Exception ignore) {}
    }

    private void clearSelectedVenuePrefs() {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
            sp.edit()
                    .remove(PREF_SELECTED_VENUE_ID)
                    .remove(PREF_SELECTED_VENUE_NAME)
                    .remove(PREF_SELECTED_FLOOR_LABEL)
                    .remove(PREF_SELECTED_FLOOR_INDEX)
                    .apply();
        } catch (Exception ignore) {}
    }

    private void updateSelectedVenueLabel(@NonNull FloorplanModels.Venue venue, @NonNull String floorLabel) {
        if (selectedVenueText == null) return;
        String name = prettyVenueName(venue.venueName);
        if (!TextUtils.isEmpty(floorLabel)) {
            selectedVenueText.setText("Selected: " + name + "  •  Floor: " + floorLabel);
        } else {
            selectedVenueText.setText("Selected: " + name);
        }
    }

    @NonNull
    private String prettyVenueName(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) return "Venue";
        String s = raw.trim().replace('_', ' ');
        String[] parts = s.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1).toLowerCase());
        }
        return sb.toString();
    }
    // 给 ReplayFragment 调用用：清空轨迹/标记/室内图/多边形，回到初始状态
    public void clearMapAndReset() {
        if (getActivity() == null) return;

        // 确保在主线程操作 GoogleMap
        getActivity().runOnUiThread(() -> {
            try {
                // 1) 清掉室内建筑轮廓
                try {
                    if (venuePolygons != null) {
                        for (Polygon p : venuePolygons) {
                            if (p != null) p.remove();
                        }
                        venuePolygons.clear();
                    }
                    if (polyToVenue != null) polyToVenue.clear();
                    selectedVenue = null;
                    selectedPolygon = null;
                    clearSelectedVenuePrefs();
                    if (selectedVenueText != null) {
                        selectedVenueText.setText("Tap \"Find indoor maps\" to show nearby buildings");
                    }
                } catch (Exception ignore) {}

                // 2) 清掉室内 floor overlay
                try {
                    if (indoorMapOverlay != null) {
                        indoorMapOverlay.clearOverlay();
                    }
                } catch (Exception ignore) {}

                // 3) UI 复位（楼层控件隐藏）
                try {
                    if (floorSpinner != null) floorSpinner.setVisibility(View.GONE);
                    setFloorControlsVisibility(View.GONE);
                } catch (Exception ignore) {}

                // 4) 清轨迹线
                if (polyline != null) {
                    polyline.remove();
                    polyline = null;
                }
                if (gnssPolyline != null) {
                    gnssPolyline.remove();
                    gnssPolyline = null;
                }

                // 5) 清 marker
                if (orientationMarker != null) {
                    orientationMarker.remove();
                    orientationMarker = null;
                }
                if (gnssMarker != null) {
                    gnssMarker.remove();
                    gnssMarker = null;
                }

                // 6) 清状态变量
                lastGnssLocation = null;
                currentLocation = null;

                // 7) 重新创建空 polyline（保持录制回放继续画线）
                if (gMap != null) {
                    polyline = gMap.addPolyline(new PolylineOptions().color(Color.RED).width(5f).add());
                    gnssPolyline = gMap.addPolyline(new PolylineOptions().color(Color.BLUE).width(5f).add());
                }
            } catch (Exception e) {
                Log.e(TAG, "clearMapAndReset failed: " + e.getMessage());
            }
        });
    }

}
