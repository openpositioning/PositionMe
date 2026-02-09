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
public class TrajectoryMapFragment extends Fragment {
    private static final String TAG = "TrajectoryMapFragment";
    private static final String CDBG = "C_DEBUG";
    private static final boolean CDBG_ENABLED = true;
    private static final int DEBUG_BUFFER_MAX_CHARS = 14000;
    private final StringBuilder debugBuffer = new StringBuilder(4096);
    private void dbgBufAppend(@NonNull String level, @NonNull String msg) {
        try {
            String line = String.format(Locale.US, "%tT %s %s\n", System.currentTimeMillis(), level, msg);
            debugBuffer.append(line);
            if (debugBuffer.length() > DEBUG_BUFFER_MAX_CHARS) {
                int cut = debugBuffer.length() - DEBUG_BUFFER_MAX_CHARS;
                debugBuffer.delete(0, Math.min(cut, debugBuffer.length()));
            }
        } catch (Exception ignore) {
        }
    }
    private void cdbg(@NonNull String msg) {
        if (CDBG_ENABLED) Log.d(CDBG, msg);
        dbgBufAppend("D", msg);
    }
    private void cdbgW(@NonNull String msg) {
        if (CDBG_ENABLED) Log.w(CDBG, msg);
        dbgBufAppend("W", msg);
    }
    private void cdbgE(@NonNull String msg) {
        if (CDBG_ENABLED) Log.e(CDBG, msg);
        dbgBufAppend("E", msg);
    }
    private void setStatusText(@NonNull String msg) {
        if (!isAdded()) return;
        if (selectedVenueText != null) selectedVenueText.setText(msg);
    }
    private void showDebugDialog() {
        if (!isAdded()) return;
        String text = debugBuffer.length() == 0 ? "(No debug logs yet)" : debugBuffer.toString();
        TextView tv = new TextView(requireContext());
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextIsSelectable(true);
        tv.setText(text);
        new AlertDialog.Builder(requireContext())
                .setTitle("Indoor map debug log (copy me)")
                .setView(tv)
                .setPositiveButton("Close", (d, w) -> d.dismiss())
                .show();
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
        indoorSearching = loading;
        if (requestIndoorButton != null) {
            requestIndoorButton.setEnabled(true);
            requestIndoorButton.setLongClickable(true);
            requestIndoorButton.setAlpha(loading ? 0.6f : 1f);
            if (requestIndoorButtonText != null) {
                requestIndoorButton.setText(loading ? "Searching… (tap to cancel)" : requestIndoorButtonText);
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
    private static final String PREF_CACHED_VENUES_JSON = "pref_cached_venues_json";
    private static final int MAX_WIFI_MACS = 200;          // keep more APs so "between buildings" still works
    private static final int MIN_MACS_FOR_API = 6;        // below this we ask for manual input on emulator
    private static final double PROBE_RADIUS_METERS = 50.0;
    private static final boolean ENABLE_LOCAL_LIBRARY_FALLBACK = false; // hard-coded fallback outlines (Library + Fleeming Jenkin) // hard-coded Library polygon
    private static final boolean USE_LOCAL_FLOORPLAN_FALLBACK = false;  // use bundled drawable floor PNGs
    private static final boolean ENABLE_LOCAL_FJB_FALLBACK = false;     // hard-coded Fleeming Jenkin polygon
    private final java.util.concurrent.atomic.AtomicInteger indoorReqSeq = new java.util.concurrent.atomic.AtomicInteger(0);
    private GoogleMap gMap;
    private LatLng currentLocation;
    private Marker orientationMarker;
    private Marker gnssMarker;
    private Polyline polyline;
    private Polyline gnssPolyline;
    private LatLng lastGnssLocation = null;
    private boolean isRed = true;
    private boolean isGnssOn = false;
    private boolean followCamera = true;
    private LatLng pickedCenter = null;
    private LatLng lastRequestCenter = null;
    private Spinner switchMapSpinner;
    private SwitchMaterial gnssSwitch;
    private SwitchMaterial autoFloorSwitch; // hidden
    private FloatingActionButton floorUpButton, floorDownButton;
    private Button switchColorButton;
    private Button requestIndoorButton;
    private Button selectVenueButton;
    private TextView selectedVenueText;
    private Spinner floorSpinner;
    private boolean programmaticFloorSelection = false;
    private CircularProgressIndicator indoorLoadingIndicator;
    private String requestIndoorButtonText = null;
    private volatile boolean indoorSearching = false;
    private Polygon selectedPolygon = null;
    private int polyStrokeColor = Color.MAGENTA;
    private int polyFillColor = Color.argb(50, 126, 87, 194);
    private int polySelectedStrokeColor = Color.WHITE;
    private int polySelectedFillColor = Color.argb(90, 126, 87, 194);
    private static final String PREF_SELECTED_VENUE_ID = "pref_selected_venue_id";
    private static final String PREF_SELECTED_VENUE_NAME = "pref_selected_venue_name";
    private static final String PREF_SELECTED_FLOOR_LABEL = "pref_selected_floor_label";
    private static final String PREF_SELECTED_FLOOR_INDEX = "pref_selected_floor_index";
    private static final String PREF_SELECTED_FLOOR_MANUAL = "pref_selected_floor_manual";
    private static final String PREF_INDOOR_METHOD_B = "pref_indoor_method_b";
    private final FloorplanApi floorplanApi = new FloorplanApi();
    private IndoorMapFragment indoorMapOverlay;
    private final List<Polygon> venuePolygons = new ArrayList<>();
    private final Map<Polygon, FloorplanModels.Venue> polyToVenue = new HashMap<>();
    private final List<Marker> venueCenterMarkers = new ArrayList<>();
    private final Map<Marker, FloorplanModels.Venue> markerToVenue = new HashMap<>();
    private FloorplanModels.Venue selectedVenue = null;
    private final List<FloorplanModels.Venue> lastFetchedVenues = new ArrayList<>();
    private final Map<String, FloorplanModels.Venue> venueCache = new HashMap<>();
    private final List<FloorplanModels.Venue> venuePickerCandidates = new ArrayList<>();
    private String lastManualMacText = "";
    private LatLng pendingCameraPosition = null;
    private boolean hasPendingCameraMove = false;
    public TrajectoryMapFragment() {}
    private static final int REQUEST_WIFI_PERMS = 2105;
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
        loadCachedVenues();
        switchMapSpinner   = view.findViewById(R.id.mapSwitchSpinner);
        gnssSwitch         = view.findViewById(R.id.gnssSwitch);
        autoFloorSwitch    = view.findViewById(R.id.autoFloor);
        floorUpButton      = view.findViewById(R.id.floorUpButton);
        floorDownButton    = view.findViewById(R.id.floorDownButton);
        switchColorButton  = view.findViewById(R.id.lineColorButton);
        requestIndoorButton = view.findViewById(R.id.requestIndoorButton);
        selectVenueButton  = view.findViewById(R.id.selectVenueButton);
        selectedVenueText  = view.findViewById(R.id.selectedVenueText);
        floorSpinner        = view.findViewById(R.id.floorSpinner);
        indoorLoadingIndicator = view.findViewById(R.id.indoorLoadingIndicator);
        if (requestIndoorButton != null) requestIndoorButtonText = requestIndoorButton.getText().toString();
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
                    indoorMapOverlay = new IndoorMapFragment(gMap, 8);
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
        requestIndoorButton.setOnClickListener(v -> {
            if (indoorSearching) {
                try {
                    if (floorplanApi != null) floorplanApi.cancelAll();
                } catch (Exception ignore) {}
                setIndoorLoading(false);
                setStatusText("Search cancelled. Tap again to search.");
                showSnack("Indoor map search cancelled");
                return;
            }
            requestNearbyIndoorMapsWithManualFallback();
        });
        requestIndoorButton.setOnLongClickListener(v -> {
            showDebugDialog();
            return true;
        });
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
        map.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
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

        try { switchMapSpinner.setSelection(2); } catch (Exception ignore) {}
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
    @Nullable
    public String getSelectedVenueId() {
        return selectedVenue != null ? selectedVenue.venueId : null;
    }
    @Nullable
    public String getSelectedVenueName() {
        return selectedVenue != null ? selectedVenue.venueName : null;
    }
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
    private void requestNearbyIndoorMapsWithManualFallback() {
        if (gMap == null) return;
        LatLng center = (pickedCenter != null) ? pickedCenter : currentLocation;
        if (center == null) {
            Toast.makeText(requireContext(), "No location yet. Move map or wait for GNSS.", Toast.LENGTH_SHORT).show();
            return;
        }
        lastRequestCenter = center;
        if (!ensureWifiScanPermissions()) return;
        clearVenuePolygons();
        lastFetchedVenues.clear();
        if (selectVenueButton != null) selectVenueButton.setEnabled(false);
        if (selectedVenueText != null) selectedVenueText.setText("Tap a building outline to select a venue");
        if (indoorMapOverlay != null) indoorMapOverlay.clearOverlay();
        selectedVenue = null;
        floorSpinner.setVisibility(View.GONE);
        setFloorControlsVisibility(View.GONE);
        List<String> macs = getNearbyWifiMacsCached();
        cdbg("IndoorMaps: button clicked. pickedCenter=" + ll(center) + " currentLocation=" + ll(currentLocation) + " followCamera=" + followCamera);
        cdbg("IndoorMaps: WiFi scan results -> macsSelected=" + macs.size() + " (MIN=" + MIN_MACS_FOR_API + ", MAX=" + MAX_WIFI_MACS + ")");
        if (!macs.isEmpty()) {
            cdbg("IndoorMaps: MAC sample=" + macs.subList(0, Math.min(8, macs.size())));
        }
        if (macs.size() < MIN_MACS_FOR_API) {
            setStatusText("Not enough Wi‑Fi access points (" + macs.size() + "). If you're on a real phone, turn Wi‑Fi on and try again.");
            showMacInputDialog(center, macs.size());
            return;
        }
        setIndoorLoading(true);
        setStatusText("Searching indoor maps… (WiFi APs=" + macs.size() + ")");
        showSnack("Searching indoor maps… (WiFi APs=" + macs.size() + ")");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isAdded()) return;
            if (indoorSearching) {
                showSnack("Still waiting for the floorplan server… please check Logcat (tag: C_DEBUG / FloorplanApi) or long-press the button to copy the debug log.");
            }
        }, 35000);
        requestVenuesMultiProbeWithFallback(center, macs);
    }
    private void requestVenuesMultiProbeWithFallback(@NonNull LatLng center, @NonNull List<String> macs) {
        if (floorplanApi == null) {
            Log.w(TAG, "Floorplan API not initialised yet");
            setIndoorLoading(false);
            showSnack("Floorplan API not initialised yet");
            return;
        }
        final int MAX_REQUESTS = 20;
        final long INTER_REQUEST_DELAY_MS = 120L;
        boolean methodB = false;
        final List<LatLng> centers = methodB
                ? java.util.Collections.singletonList(center)
                : buildProbeCentersCompact(center, 80.0 /*meters*/);
        final List<String> macsNorm = (macs == null) ? new ArrayList<>() : new ArrayList<>(macs);
        final List<String> macsMixed = capList(macsNorm, 60);
        List<String> macsStrong = getNearbyWifiMacsStrongest(30);
        if (macsStrong.isEmpty()) macsStrong = capList(macsNorm, 30);
        final List<List<String>> probes = new ArrayList<>();
        if (methodB) {
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
        setStatusText("Searching indoor maps… 0/" + jobs.size() + "  venues=0");
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
            setStatusText("Searching indoor maps… " + (i+1) + "/" + jobs.size() + "  venues=" + merged.size());
            final String reqId = "#" + reqSeq + "." + (i + 1);
            floorplanApi.requestNearbyVenues(job.c, job.m != null ? job.m : new ArrayList<>(), reqId, new FloorplanApi.VenuesCallback() {
                @Override public void onSuccess(@NonNull List<FloorplanModels.Venue> venues) {
                    if (!isAdded()) return;
                    synchronized (merged) {
                        for (FloorplanModels.Venue v : venues) {
                            if (v == null) continue;
                            String key = safeVenueKey(v);
                            if (key == null) continue;
                            merged.put(key, v);
                        }
                    }
                    if (merged.size() >= 4) {
                        idx.set(jobs.size());
                    }
                    setStatusText("Searching indoor maps… " + Math.min(i + 1, jobs.size()) + "/" + jobs.size() + "  venues=" + merged.size());
                    h.postDelayed(next[0], INTER_REQUEST_DELAY_MS);
                }
                @Override public void onError(@NonNull String message) {
                    if (!isAdded()) return;
                    cdbg("IndoorMaps[#"+reqSeq+"]: req error: " + message);
                    setStatusText("Searching indoor maps… " + Math.min(i + 1, jobs.size()) + "/" + jobs.size() + "  venues=" + merged.size() + "  (last error)");
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
        mergeVenuesIntoCache(lastFetchedVenues);
        if (ENABLE_LOCAL_LIBRARY_FALLBACK) {
            addLocalLibraryVenueIfNearby(center);
            if (ENABLE_LOCAL_FJB_FALLBACK) addLocalFleemingJenkinVenueIfNearby(center);
        }
        saveCachedVenues();
        List<FloorplanModels.Venue> toDraw = new ArrayList<>(venueCache.values());
        cdbg("IndoorMaps[#"+reqSeq+"]: done mergedUnique=" + lastFetchedVenues.size()
                + " cacheSize=" + toDraw.size());
        if (!toDraw.isEmpty()) {
            int drawn = drawVenuesAsPolygons(toDraw);
            cdbg("IndoorMaps[#"+reqSeq+"]: drawnPolygons=" + drawn);
            if (selectVenueButton != null) selectVenueButton.setEnabled(drawn > 0);
            if (drawn > 0) {
                setStatusText("Found " + toDraw.size() + " buildings. Tap an outline to select a venue.");
            } else {
                setStatusText("Venues returned but no drawable geometry (try another point).");
                showSnack("Venues returned but no geometry to draw (try another point).");
            }
            return;
        }
        if (lastFetchedVenues.isEmpty() && floorplanApi != null) {
            setIndoorLoading(true);
            setStatusText("No venues found — trying GPS-only fallback…");
            showSnack("No venues found — trying GPS-only fallback…");
            floorplanApi.requestNearbyVenues(center, new ArrayList<>(), new FloorplanApi.VenuesCallback() {
                @Override public void onSuccess(@NonNull List<FloorplanModels.Venue> venues) {
                    setIndoorLoading(false);
                    if (!isAdded()) return;
                    mergeVenuesIntoCache(venues);
                    if (ENABLE_LOCAL_LIBRARY_FALLBACK) {
                        addLocalLibraryVenueIfNearby(center);
                        if (ENABLE_LOCAL_FJB_FALLBACK) addLocalFleemingJenkinVenueIfNearby(center);
                    }
                    saveCachedVenues();
                    List<FloorplanModels.Venue> toDraw2 = new ArrayList<>(venueCache.values());
                    int drawn = drawVenuesAsPolygons(toDraw2);
                    if (selectVenueButton != null) selectVenueButton.setEnabled(drawn > 0);
                    if (drawn > 0) {
                        setStatusText("Found " + toDraw2.size() + " buildings. Tap an outline to select a venue.");
                    } else {
                        setStatusText("No indoor venues returned near this point");
                        showSnack("No indoor venues returned near this point");
                    }
                }
                @Override public void onError(@NonNull String message) {
                    setIndoorLoading(false);
                    if (!isAdded()) return;
                    setStatusText("No indoor venues returned (" + message + ")");
                    showSnack("No indoor venues returned (" + message + ")");
                }
            });
        } else {
            setStatusText("No indoor venues returned near this point");
            showSnack("No indoor venues returned near this point");
        }
    }
    private @NonNull List<LatLng> buildProbeCenters(@NonNull LatLng center, double stepMeters) {
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
        double dLat = dNorthMeters / 111320.0;
        double dLon = dEastMeters / (111320.0 * Math.cos(Math.toRadians(c.latitude)));
        return new LatLng(c.latitude + dLat, c.longitude + dLon);
    }
    private @NonNull List<String> capList(@NonNull List<String> in, int cap) {
        if (in.size() <= cap) return new ArrayList<>(in);
        return new ArrayList<>(in.subList(0, cap));
    }
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
        String id = v.venueId;
        if (id != null && !id.trim().isEmpty()) return id.trim();
        String name = v.venueName;
        if (name != null && !name.trim().isEmpty()) return name.trim();
        return null;
    }
    private void addLocalLibraryVenueIfNearby(@Nullable LatLng ref) {
        if (ref == null) return;
        for (FloorplanModels.Venue v : venueCache.values()) {
            if (v == null) continue;
            if (v.venueId != null && v.venueId.toLowerCase(Locale.US).startsWith("local_")) continue;
            if ("local_library".equalsIgnoreCase(v.venueId)) return;
            String n = (v.venueName != null) ? v.venueName.toLowerCase() : "";
            if (n.contains("library") || n.contains("kenneth") || n.contains("murray")) return;
        }
        LatLng libCenter = new LatLng(
                (BuildingPolygon.LIBRARY_SW.latitude + BuildingPolygon.LIBRARY_NE.latitude) / 2.0,
                (BuildingPolygon.LIBRARY_SW.longitude + BuildingPolygon.LIBRARY_NE.longitude) / 2.0
        );
        double d = distanceMeters(ref, libCenter);
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
    private void addLocalFleemingJenkinVenueIfNearby(@Nullable LatLng ref) {
        if (ref == null) return;
        for (FloorplanModels.Venue v : venueCache.values()) {
            if (v == null) continue;
            if ("local_fleeming_jenkin".equalsIgnoreCase(v.venueId)) return;
            String n = (v.venueName != null) ? v.venueName.toLowerCase(Locale.US) : "";
            if (n.contains("fleeming") || n.contains("jenkin") || n.contains("fjb")) return;
        }
        List<LatLng> outline = new ArrayList<>();
        outline.add(new LatLng(55.92269205199916, -3.1729563477188774)); // left top
        outline.add(new LatLng(55.922822801570994, -3.172594249522305));
        outline.add(new LatLng(55.92223512226413, -3.171921917547244));
        outline.add(new LatLng(55.9221071265519, -3.1722813131202097));
        double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
        double minLng = Double.POSITIVE_INFINITY, maxLng = Double.NEGATIVE_INFINITY;
        for (LatLng p : outline) {
            if (p == null) continue;
            minLat = Math.min(minLat, p.latitude);
            maxLat = Math.max(maxLat, p.latitude);
            minLng = Math.min(minLng, p.longitude);
            maxLng = Math.max(maxLng, p.longitude);
        }
        if (!(Double.isFinite(minLat) && Double.isFinite(maxLat) && Double.isFinite(minLng) && Double.isFinite(maxLng))) return;
        LatLngBounds bounds;
        try {
            bounds = new LatLngBounds(new LatLng(minLat, minLng), new LatLng(maxLat, maxLng));
        } catch (Exception e) {
            return;
        }
        LatLng center = new LatLng((minLat + maxLat) / 2.0, (minLng + maxLng) / 2.0);
        final double ADD_RADIUS_M = 1200.0;
        double d = distanceMeters(ref, center);
        if (Double.isNaN(d) || d > ADD_RADIUS_M) {
            cdbg("LocalFallback: skip FJB (distance=" + (int) d + "m, radius=" + (int) ADD_RADIUS_M + "m)");
            return;
        }
        FloorplanModels.Venue fjb = new FloorplanModels.Venue(
                "local_fleeming_jenkin",
                "fleeming_jenkin_building",
                outline,
                bounds,
                new ArrayList<>()
        );
        venueCache.put(fjb.venueId, fjb);
        cdbg("LocalFallback: added FJB outline (distance=" + (int) d + "m) outlinePts=" + outline.size());
    }
    private void mergeVenuesIntoCache(@Nullable List<FloorplanModels.Venue> venues) {
        if (venues == null || venues.isEmpty()) return;
        int added = 0;
        int updated = 0;
        int keptPrevOutline = 0;
        int keptPrevFloors = 0;
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
            String venueId = !TextUtils.isEmpty(incoming.venueId) ? incoming.venueId : prev.venueId;
            String venueName = !TextUtils.isEmpty(incoming.venueName) ? incoming.venueName : prev.venueName;
            boolean useIncomingOutline = (incoming.outline != null && !incoming.outline.isEmpty());
            if (!useIncomingOutline && prev.outline != null && !prev.outline.isEmpty()) keptPrevOutline++;
            List<LatLng> outline = useIncomingOutline ? incoming.outline : prev.outline;
            LatLngBounds bounds = (incoming.bounds != null) ? incoming.bounds : prev.bounds;
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
        if (!ENABLE_LOCAL_LIBRARY_FALLBACK) {
            venueCache.remove("local_library");
        }
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
            String raw = prefs.getString(PREF_CACHED_VENUES_JSON, null);
            if (raw == null || raw.trim().isEmpty()) return;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", null);
                if (!ENABLE_LOCAL_LIBRARY_FALLBACK && id != null && id.toLowerCase(Locale.US).startsWith("local_")) continue;
                String name = o.optString("name", null);
                if (id == null || id.trim().isEmpty()) continue;
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
        LatLng ref = (lastRequestCenter != null)
                ? lastRequestCenter
                : ((pickedCenter != null) ? pickedCenter : currentLocation);
        if (ref != null) {
            fixLonLatOrderIfNeeded(venues, ref);
        }
        lastFetchedVenues.clear();
        lastFetchedVenues.addAll(venues);
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
        if (venues.size() == 1) {
            onVenueSelected(venues.get(0));
        }
    }
    private void fixLonLatOrderIfNeeded(@NonNull List<FloorplanModels.Venue> venues, @NonNull LatLng ref) {
        for (FloorplanModels.Venue v : venues) {
            if (v.outline != null && v.outline.size() >= 3) {
                LatLng c1 = venueCenter(v);
                double d1 = distanceMeters(ref, c1);
                List<LatLng> swapped = new ArrayList<>(v.outline.size());
                for (LatLng p : v.outline) swapped.add(new LatLng(p.longitude, p.latitude));
                LatLng c2 = centroid(swapped);
                double d2 = distanceMeters(ref, c2);
                if (d1 > 2000 && d2 < d1 * 0.2) {
                    Log.w(TAG, "Fixing lon/lat order for venue " + v.venueId + " (" + d1 + "m -> " + d2 + "m)");
                    v.outline.clear();
                    v.outline.addAll(swapped);
                }
            }
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
                    }
                }
            }
        }
    }
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
    private void showVenuePickerDialog(@NonNull List<FloorplanModels.Venue> venues) {
        try {
            final LatLng ref = (lastRequestCenter != null)
                    ? lastRequestCenter
                    : ((pickedCenter != null) ? pickedCenter : currentLocation);
            List<FloorplanModels.Venue> sorted = new ArrayList<>(venues);
            Collections.sort(sorted, (a, b) -> {
                double da = distanceMeters(ref, venueCenter(a));
                double db = distanceMeters(ref, venueCenter(b));
                int c = Double.compare(da, db);
                if (c != 0) return c;
                String an = (a.venueName != null) ? a.venueName : "";
                String bn = (b.venueName != null) ? b.venueName : "";
                return an.compareToIgnoreCase(bn);
            });
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
    private @NonNull List<String> getNearbyWifiMacsCached() {
        List<String> out = new ArrayList<>();
        try {
            WifiManager wm = (WifiManager) requireContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return out;
            @SuppressLint("MissingPermission")
            List<ScanResult> results = wm.getScanResults();
            if (results == null || results.isEmpty()) return out;
            results.sort((a, b) -> Integer.compare(b.level, a.level));
            cdbg("WiFiScan: rawScanResults=" + results.size() + " wifiEnabled=" + wm.isWifiEnabled());
            int top = Math.min(5, results.size());
            for (int i = 0; i < top; i++) {
                ScanResult r = results.get(i);
                if (r == null) continue;
                cdbg("WiFiScan: top[" + i + "] bssid=" + r.BSSID + " level=" + r.level + " freq=" + r.frequency);
            }
            final int strongestKeep = Math.min(40, results.size());
            HashSet<String> seen = new HashSet<>();
            for (int i = 0; i < strongestKeep && out.size() < MAX_WIFI_MACS; i++) {
                String bssid = results.get(i).BSSID;
                if (bssid == null) continue;
                bssid = bssid.trim().toLowerCase(Locale.US);
                if (!seen.add(bssid)) continue;
                out.add(bssid);
            }
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
    private int colorForVenueOutline(@NonNull FloorplanModels.Venue v) {
        String n = (v.venueName != null) ? v.venueName.toLowerCase(Locale.US) : "";
        String id = (v.venueId != null) ? v.venueId.toLowerCase(Locale.US) : "";
        if (n.contains("murchison") || id.contains("murchison")) return Color.RED;
        if (n.contains("nucleus") || id.contains("nucleus")) return Color.GREEN;
        if (n.contains("library") || n.contains("kenneth") || n.contains("murray") || id.contains("library")) return Color.rgb(255, 165, 0); // amber
        if (n.contains("fleeming") || n.contains("jenkin") || n.contains("fjb") || id.contains("jenkin")) return Color.BLUE;
        return polyStrokeColor;
    }
    private int fillColorForVenueOutline(@NonNull FloorplanModels.Venue v) {
        int stroke = colorForVenueOutline(v);
        int a = (v.venueId != null && v.venueId.toLowerCase(Locale.US).startsWith("local_")) ? 35 : 50;
        return Color.argb(a, Color.red(stroke), Color.green(stroke), Color.blue(stroke));
    }
    int drawVenuesAsPolygons(@NonNull List<FloorplanModels.Venue> venues) {
        if (gMap == null) return 0;
        int drawn = 0;
        LatLngBounds boundsForZoom = null;
        LatLng ref = (lastRequestCenter != null) ? lastRequestCenter : pickedCenter;
        double bestDist = Double.POSITIVE_INFINITY;
        LatLngBounds bestBounds = null;
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
            int stroke = colorForVenueOutline(v);
            int fill = fillColorForVenueOutline(v);
            float strokeW = (v.venueId != null && v.venueId.toLowerCase(Locale.US).startsWith("local_")) ? 10f : 6f;
            PolygonOptions opt = new PolygonOptions()
                    .addAll(pts)
                    .strokeWidth(strokeW)
                    .strokeColor(stroke)
                    .fillColor(fill)
                    .zIndex(1000f);
            Polygon poly = gMap.addPolygon(opt);
            poly.setClickable(true);
            venuePolygons.add(poly);
            polyToVenue.put(poly, v);
            drawn++;
            LatLngBounds vb;
            if (v.bounds != null) {
                vb = (ref != null) ? bestBoundsForRef(v.bounds, ref) : v.bounds;
            } else {
                LatLngBounds.Builder bld = new LatLngBounds.Builder();
                for (LatLng p : pts) bld.include(p);
                vb = bld.build();
            }
            if (ref != null) {
                double d = distanceMeters(ref, venueCenterFromBoundsOrOutline(v, vb, pts));
                if (d < bestDist) {
                    bestDist = d;
                    bestBounds = vb;
                }
            }
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
        }
    }
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
    private static final class FloorSpec {
        final String label;
        final int drawableResId;
        FloorSpec(@NonNull String label, int drawableResId) {
            this.label = label;
            this.drawableResId = drawableResId;
        }
    }
    private @NonNull List<FloorSpec> getLocalFloorsForVenue(@NonNull FloorplanModels.Venue venue) {
        String name = (venue.venueName != null) ? venue.venueName.toLowerCase() : "";
        List<FloorSpec> out = new ArrayList<>();
        if (name.contains("nucleus")) {
            out.add(new FloorSpec("LG", R.drawable.nucleuslg));
            out.add(new FloorSpec("G",  R.drawable.nucleusg));
            out.add(new FloorSpec("1",  R.drawable.nucleus1));
            out.add(new FloorSpec("2",  R.drawable.nucleus2));
            out.add(new FloorSpec("3",  R.drawable.nucleus3));
            return out;
        }
        if (name.contains("library") || name.contains("kenneth") || name.contains("murray")) {
            out.add(new FloorSpec("G", R.drawable.libraryg));
            out.add(new FloorSpec("1", R.drawable.library1));
            out.add(new FloorSpec("2", R.drawable.library2));
            out.add(new FloorSpec("3", R.drawable.library3));
            return out;
        }
        if (name.contains("murchison")) {
            out.add(new FloorSpec("LG", R.drawable.floor_lg));
            out.add(new FloorSpec("UG", R.drawable.floor_ug));
            out.add(new FloorSpec("1", R.drawable.floor_1));
            out.add(new FloorSpec("2", R.drawable.floor_2));
            out.add(new FloorSpec("3", R.drawable.floor_3));
            return out;
        }
        return out;
    }
    private void onVenueSelected(@NonNull FloorplanModels.Venue venue) {
        onVenueSelected(venue, null);
    }
    private void onVenueSelected(@NonNull FloorplanModels.Venue venue, @Nullable Polygon fromPolygon) {
        if (venue.venueId != null && venue.venueId.toLowerCase(Locale.US).startsWith("local_")) {
            updatePolygonSelection(venue, fromPolygon);
            showSnack("Outline only (no server floorplan): " + prettyVenueName(venue.venueName));
            return;
        }
        selectedVenue = venue;
        updatePolygonSelection(venue, fromPolygon);
        saveSelectedVenueToPrefs(venue);
        if (selectedVenueText != null) {
            selectedVenueText.setText("Selected: " + prettyVenueName(venue.venueName));
        }
        showSnack("Selected: " + prettyVenueName(venue.venueName));
        if (indoorMapOverlay != null) indoorMapOverlay.setSelectedVenue(venue);
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
        LatLngBounds placementBounds = venueBounds;
        List<FloorSpec> localFloors = getLocalFloorsForVenue(venue);
        List<FloorplanModels.Floor> floors = venue.floorsSorted();
        List<FloorplanModels.Floor> remoteFloorsUsable = new ArrayList<>();
        if (floors != null) {
            for (FloorplanModels.Floor f : floors) {
                if (f == null) continue;
                boolean hasImg = (f.imageUrl != null && !f.imageUrl.trim().isEmpty());
                boolean hasGeom = f.hasGeometry();
                if (hasImg || hasGeom) remoteFloorsUsable.add(f);
            }
        }
        if ((floors == null || floors.isEmpty()) && (localFloors == null || localFloors.isEmpty())) {
            floorSpinner.setVisibility(View.GONE);
            setFloorControlsVisibility(View.GONE);
            showSnack("No floorplan images for this venue");
            return;
        }
        int needFloors = 0;
        if (floors != null) needFloors = Math.max(needFloors, floors.size());
        if (localFloors != null) needFloors = Math.max(needFloors, localFloors.size());
        if (needFloors < 1) needFloors = 1;
        try {
            if (indoorMapOverlay != null) indoorMapOverlay.clearOverlay();
        } catch (Exception ignore) {}
        indoorMapOverlay = new IndoorMapFragment(gMap, Math.max(needFloors, 1));
        indoorMapOverlay.setSelectedVenue(venue);
        if (remoteFloorsUsable != null && !remoteFloorsUsable.isEmpty()) {
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
                    saveSelectedFloorManualFlag(venue, !programmaticFloorSelection);
                    updateSelectedVenueLabel(venue, floor.toString());
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
            programmaticFloorSelection = true;
            int initialPos = chooseInitialFloorPosition(venue);
            floorSpinner.setSelection(initialPos);
            floorSpinner.post(() -> programmaticFloorSelection = false);
        }
        else {
            if (!USE_LOCAL_FLOORPLAN_FALLBACK) {
                floorSpinner.setVisibility(View.GONE);
                setFloorControlsVisibility(View.GONE);
                showSnack("No floorplans returned from server yet. Try moving the crosshair closer to the building and tap \"Find indoor maps\" again.");
            } else {
                if (placementBounds == null) {
                    floorSpinner.setVisibility(View.GONE);
                    setFloorControlsVisibility(View.GONE);
                    showSnack("Venue has no bounds; can't place local floorplan overlay");
                    return;
                }
                for (int i = 0; i < localFloors.size(); i++) {
                    FloorSpec fs = localFloors.get(i);
                    if (indoorMapOverlay != null) indoorMapOverlay.addFloor(i, fs.drawableResId, placementBounds);
                }
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
                        saveSelectedFloorManualFlag(venue, !programmaticFloorSelection);
                        updateSelectedVenueLabel(venue, label);
                    }
                    @Override public void onNothingSelected(AdapterView<?> parent) {}
                });
                programmaticFloorSelection = true;
                int initialPos = chooseInitialLocalFloorPosition(venue, labels);
                floorSpinner.setSelection(initialPos);
                floorSpinner.post(() -> programmaticFloorSelection = false);
            }
        }
        if ((remoteFloorsUsable == null || remoteFloorsUsable.isEmpty()) && placementBounds != null) {
            tryFetchRemoteFloorsForVenue(venue, placementBounds);
        }
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
    private void tryFetchRemoteFloorsForVenue(@NonNull FloorplanModels.Venue venue, @Nullable LatLngBounds venueBounds) {
        if (!isAdded() || floorplanApi == null || gMap == null) return;
        if (venue.floors != null) {
            for (FloorplanModels.Floor f : venue.floors) {
                if (f == null) continue;
                if (f.hasGeometry()) return;
                if (f.imageUrl != null && !f.imageUrl.trim().isEmpty()) return;
            }
        }
        if (venue.venueId != null && venue.venueId.startsWith("local_")) {
            cdbg("VenueDetails: selected a local/demo venue -> skip server floor fetch: " + venueDbg(venue));
            showSnack("This is a local outline demo (no server floorplan). Move inside a real venue (e.g., Murchison House) and press \"Find indoor maps\" again.");
            setStatusText("Selected (local outline only): " + prettyVenueName(venue.venueName));
            return;
        }
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
                for (FloorplanModels.Venue v : venues) {
                    if (v == null) continue;
                    if (sameVenue(v, venue)) { best = v; break; }
                }
                if (best == null) {
                    cdbg("VenueDetails[#"+reqSeq+"]: venues returned but none matched selected venue; skip updating floors to avoid wrong building.");
                    showSnack("Server returned venues, but none matched the selected outline. You might have selected a local/demo outline, or you are not close enough to the building. Try moving inside the building and press 'Find indoor maps' again.");
                    setStatusText("No matching server venue for: " + prettyVenueName(venue.venueName));
                    return;
                }
                if (best == null || best.floors == null || best.floors.isEmpty()) {
                    cdbg("VenueDetails[#"+reqSeq+"]: no floors returned (venues=" + venues.size() + ")");
                    showSnack("Server matched the venue, but returned no floorplans. This venue may be outline-only at the moment. Try again from inside the building (stronger Wi‑Fi), or test in Murchison House.");
                    setStatusText("No floorplans for: " + prettyVenueName(venue.venueName));
                    return;
                }
                List<FloorplanModels.Floor> usable = new ArrayList<>();
                List<FloorplanModels.Floor> sorted = best.floorsSorted();
                for (FloorplanModels.Floor f : sorted) {
                    if (f == null) continue;
                    boolean hasImg = (f.imageUrl != null && !f.imageUrl.trim().isEmpty());
                    boolean hasGeom = f.hasGeometry();
                    if (hasImg || hasGeom) usable.add(f);
                }
                if (usable.isEmpty()) {
                    cdbg("VenueDetails[#"+reqSeq+"]: floors exist but none are renderable (no geometry, no imageUrl)");
                    showSnack("Server returned floors, but none contain an image or vector geometry that can be drawn.");
                    setStatusText("Floorplans not drawable for: " + prettyVenueName(venue.venueName));
                    return;
                }
                venue.floors.clear();
                venue.floors.addAll(usable);
                try {
                    if (indoorMapOverlay != null) indoorMapOverlay.clearOverlay();
                } catch (Exception ignore) {}
                indoorMapOverlay = new IndoorMapFragment(gMap, Math.max(usable.size(), 1));
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
                showSnack("Server floorplans loaded ✔");
            }
            @Override public void onError(@NonNull String message) {
                cdbg("VenueDetails[#"+reqSeq+"]: error: " + message);
                showSnack("Floorplan request failed: " + message);
                setStatusText("Floorplan request failed for: " + prettyVenueName(venue.venueName));
            }
        });
    }
    private void updatePolygonSelection(@NonNull FloorplanModels.Venue selected, @Nullable Polygon fromPolygon) {
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

    private void saveSelectedFloorManualFlag(@NonNull FloorplanModels.Venue venue, boolean manual) {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
            sp.edit()
                    .putString(PREF_SELECTED_VENUE_ID, venue.venueId)
                    .putBoolean(PREF_SELECTED_FLOOR_MANUAL, manual)
                    .apply();
        } catch (Exception ignore) {}
    }

    private int chooseInitialFloorPosition(@NonNull FloorplanModels.Venue venue) {
        if (venue.floors == null || venue.floors.isEmpty()) return 0;
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
            String savedVenueId = sp.getString(PREF_SELECTED_VENUE_ID, null);
            if (!TextUtils.isEmpty(savedVenueId) && savedVenueId.equals(venue.venueId)) {
                boolean manual = sp.getBoolean(PREF_SELECTED_FLOOR_MANUAL, false);
                if (manual) {
                    String savedLabel = sp.getString(PREF_SELECTED_FLOOR_LABEL, null);
                    int savedPos = sp.getInt(PREF_SELECTED_FLOOR_INDEX, -1);
                    if (savedPos >= 0 && savedPos < venue.floors.size()) {
                        if (TextUtils.isEmpty(savedLabel) || savedLabel.equalsIgnoreCase(venue.floors.get(savedPos).toString())) {
                            return savedPos;
                        }
                    }
                    if (!TextUtils.isEmpty(savedLabel)) {
                        for (int i = 0; i < venue.floors.size(); i++) {
                            if (savedLabel.equalsIgnoreCase(venue.floors.get(i).toString())) return i;
                        }
                    }
                }
            }
        } catch (Exception ignore) {}
        int gf = findGroundFloorPosition(venue.floors);
        if (gf >= 0) return gf;
        return 0;
    }

    private int chooseInitialLocalFloorPosition(@NonNull FloorplanModels.Venue venue, @NonNull List<String> labels) {
        if (labels.isEmpty()) return 0;
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
            String savedVenueId = sp.getString(PREF_SELECTED_VENUE_ID, null);
            if (!TextUtils.isEmpty(savedVenueId) && savedVenueId.equals(venue.venueId)) {
                boolean manual = sp.getBoolean(PREF_SELECTED_FLOOR_MANUAL, false);
                if (manual) {
                    String savedLabel = sp.getString(PREF_SELECTED_FLOOR_LABEL, null);
                    int savedPos = sp.getInt(PREF_SELECTED_FLOOR_INDEX, -1);
                    if (savedPos >= 0 && savedPos < labels.size()) {
                        if (TextUtils.isEmpty(savedLabel) || savedLabel.equalsIgnoreCase(labels.get(savedPos))) {
                            return savedPos;
                        }
                    }
                    if (!TextUtils.isEmpty(savedLabel)) {
                        for (int i = 0; i < labels.size(); i++) {
                            if (savedLabel.equalsIgnoreCase(labels.get(i))) return i;
                        }
                    }
                }
            }
        } catch (Exception ignore) {}
        for (int i = 0; i < labels.size(); i++) {
            if (looksLikeGround(labels.get(i))) return i;
        }
        return 0;
    }

    private int findGroundFloorPosition(@NonNull List<FloorplanModels.Floor> floors) {
        for (int i = 0; i < floors.size(); i++) {
            FloorplanModels.Floor f = floors.get(i);
            String name = (f != null) ? f.toString() : null;
            if (looksLikeGround(name)) return i;
        }
        for (int i = 0; i < floors.size(); i++) {
            FloorplanModels.Floor f = floors.get(i);
            if (f == null) continue;
            String n = f.toString();
            if (n == null) continue;
            String nn = n.trim().toUpperCase(Locale.US);
            if (nn.equals("0") || nn.equals("F0") || nn.equals("F00")) return i;
        }
        return -1;
    }

    private boolean looksLikeGround(@Nullable String name) {
        if (name == null) return false;
        String n = name.trim().toUpperCase(Locale.US);
        if (n.equals("GF") || n.equals("G") || n.equals("GROUND") || n.equals("GROUND FLOOR")) return true;
        return n.contains("GROUND");
    }
    private void clearSelectedVenuePrefs() {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(requireContext());
            sp.edit()
                    .remove(PREF_SELECTED_VENUE_ID)
                    .remove(PREF_SELECTED_VENUE_NAME)
                    .remove(PREF_SELECTED_FLOOR_LABEL)
                    .remove(PREF_SELECTED_FLOOR_INDEX)
                    .remove(PREF_SELECTED_FLOOR_MANUAL)
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
    public void clearMapAndReset() {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            try {
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
                try {
                    if (indoorMapOverlay != null) {
                        indoorMapOverlay.clearOverlay();
                    }
                } catch (Exception ignore) {}
                try {
                    if (floorSpinner != null) floorSpinner.setVisibility(View.GONE);
                    setFloorControlsVisibility(View.GONE);
                } catch (Exception ignore) {}
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