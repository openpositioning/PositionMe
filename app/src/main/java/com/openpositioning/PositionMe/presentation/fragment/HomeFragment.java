package com.openpositioning.PositionMe.presentation.fragment;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
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
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.BuildConfig;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.utils.IndoorSelectionStore;
import com.openpositioning.PositionMe.presentation.activity.IndoorMapActivity;

import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.PolygonOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HomeFragment
 * - Hub screen for navigation
 * - Shows Google Map + GNSS status
 * - Step (d): requests nearby indoor floorplans using LIVE location + LIVE observed Wi-Fi AP MACs
 *
 * UX additions:
 * - Shows currently selected indoor venue under the "Select Indoor Venue" button.
 * - Optionally blocks Recording if no venue is selected (easy to switch off).
 */
public class HomeFragment extends Fragment implements OnMapReadyCallback {

    // Interactive UI elements to navigate to other fragments
    private MaterialButton goToInfo;
    private Button start;
    private Button measurements;
    private Button files;
    private TextView gnssStatusTextView;
    private MaterialButton indoorButton;

    // UX: selected venue label (TextView you added in fragment_home.xml)
    private TextView selectedVenueTextView;

    // For the map
    private GoogleMap mMap;
    private SupportMapFragment mapFragment;

    // ===== Step (d) dependencies =====
    private WifiManager wifiManager;
    private final FloorplanApiClient floorplanApiClient = new FloorplanApiClient();
    private static final String TAG = "HomeFragmentIndoor";
    private static final int REQ_LOCATION_FOR_INDOOR = 1010;

    // Step (d): map overlays for venues
    private final Map<Polygon, JSONObject> polygonToVenue = new HashMap<>();
    private final List<Polygon> venuePolygons = new ArrayList<>();
    private final List<Marker> venueMarkers = new ArrayList<>();

    public HomeFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    /**
     * Ensure the action bar is shown at the top of the screen. Set the title visible to Home.
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        ((AppCompatActivity) getActivity()).getSupportActionBar().show();
        View rootView = inflater.inflate(R.layout.fragment_home, container, false);
        getActivity().setTitle("Home");
        return rootView;
    }

    /**
     * Initialise UI elements and set onClick actions for the buttons.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Sensor Info button
        goToInfo = view.findViewById(R.id.sensorInfoButton);
        goToInfo.setOnClickListener(v -> {
            NavDirections action = HomeFragmentDirections.actionHomeFragmentToInfoFragment();
            Navigation.findNavController(v).navigate(action);
        });

        // Start/Stop Recording button
        start = view.findViewById(R.id.startStopButton);
        start.setEnabled(!PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean("permanentDeny", false));
        start.setOnClickListener(v -> {

            // UX/assignment-safe: require venue selection before recording.
            // If you don't want to hard-block, delete this block or change to a warning toast only.
            String venueId = IndoorSelectionStore.getSelectedVenueId(requireContext());
            if (venueId == null || venueId.trim().isEmpty()) {
                Toast.makeText(requireContext(),
                        "Select an indoor venue first (tap a building outline).",
                        Toast.LENGTH_LONG).show();
                return;
            }

            Intent intent = new Intent(requireContext(), RecordingActivity.class);

            // Optional: pass selection forward for the teammate working on protobuf upload tagging.
            intent.putExtra("selected_venue_id", venueId);
            intent.putExtra("selected_venue_name", IndoorSelectionStore.getSelectedVenueName(requireContext()));

            startActivity(intent);
            ((AppCompatActivity) getActivity()).getSupportActionBar().hide();
        });

        // Measurements button
        measurements = view.findViewById(R.id.measurementButton);
        measurements.setOnClickListener(v -> {
            NavDirections action = HomeFragmentDirections.actionHomeFragmentToMeasurementsFragment();
            Navigation.findNavController(v).navigate(action);
        });

        // Files button
        files = view.findViewById(R.id.filesButton);
        files.setOnClickListener(v -> {
            NavDirections action = HomeFragmentDirections.actionHomeFragmentToFilesFragment();
            Navigation.findNavController(v).navigate(action);
        });

        // TextView to display GNSS disabled message
        gnssStatusTextView = view.findViewById(R.id.gnssStatusTextView);

        // UX: selected venue label under indoor button
        selectedVenueTextView = view.findViewById(R.id.selectedVenueText);

        updateSelectedVenueLabel();

        // Map fragment
        mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.mapFragmentContainer);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        // Wifi manager (observed APs)
        wifiManager = (WifiManager) requireContext()
                .getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);

        // Indoor button: request nearby indoor maps (live lat/lon + live MACs)
        indoorButton = view.findViewById(R.id.indoorButton);
        indoorButton.setOnClickListener(v -> requestNearbyIndoorMaps());
    }

    /**
     * Callback triggered when the Google Map is ready to be used.
     */
    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        checkAndUpdatePermissions();
    }

    @Override
    public void onResume() {
        super.onResume();
        checkAndUpdatePermissions();

        // When returning from IndoorMapActivity, refresh the label so it shows the latest selection.
        updateSelectedVenueLabel();
    }

    /**
     * Updates the "Selected venue: ..." label under the indoor button.
     */
    private void updateSelectedVenueLabel() {
        if (selectedVenueTextView == null) return;

        String venueName = IndoorSelectionStore.getSelectedVenueName(requireContext());
        if (venueName == null || venueName.trim().isEmpty()) {
            selectedVenueTextView.setText("Selected venue: None");
        } else {
            selectedVenueTextView.setText("Selected venue: " + venueName);
        }
    }

    /**
     * Checks if GNSS/Location is enabled on the device.
     */
    private boolean isGnssEnabled() {
        LocationManager locationManager =
                (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
        boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        return (gpsEnabled || networkEnabled);
    }

    /**
     * Move the map to the University of Edinburgh and display a message.
     */
    private void showEdinburghAndMessage(String message) {
        gnssStatusTextView.setText(message);
        gnssStatusTextView.setVisibility(View.VISIBLE);

        LatLng edinburghLatLng = new LatLng(55.944425, -3.188396);
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(edinburghLatLng, 15f));
        mMap.addMarker(new MarkerOptions()
                .position(edinburghLatLng)
                .title("University of Edinburgh"));
    }

    private void checkAndUpdatePermissions() {

        if (mMap == null) {
            return;
        }

        boolean gnssEnabled = isGnssEnabled();
        if (gnssEnabled) {
            gnssStatusTextView.setVisibility(View.GONE);

            if (ActivityCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(
                            requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED) {

                mMap.setMyLocationEnabled(true);

            } else {
                showEdinburghAndMessage("Permission not granted. Please enable in settings.");
            }
        } else {
            showEdinburghAndMessage("GNSS is disabled. Please enable in settings.");
        }
    }

    // ======================================================================
    // Step (d) - request nearby indoor maps using LIVE location + LIVE MACs
    // ======================================================================

    /**
     * Entry point when user taps the Indoor button.
     * We do NOT use getLastKnownLocation() anymore, because it can be stale even when the blue dot looks correct.
     */
    private void requestNearbyIndoorMaps() {

        // Clear previous selection before requesting new indoor venues
        IndoorSelectionStore.clear(requireContext());

        // ✅ Update label immediately (no need to touch map)
        if (selectedVenueTextView != null) {
            selectedVenueTextView.setText("Selected venue: None (select a building)");
        }

        boolean fineGranted = ActivityCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ActivityCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (!fineGranted && !coarseGranted) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION_FOR_INDOOR);
            return;
        }

        // Force a fresh location fix for the request.
        requestFreshLocationAndIndoorRequest();
    }


    /**
     * Requests a one-shot location update (Network preferred indoors).
     * Then calls handleIndoorRequestWithLocation() with a fresh fix.
     */
    private void requestFreshLocationAndIndoorRequest() {

        LocationManager lm =
                (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);

        try {
            boolean networkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            boolean gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER);

            if (!networkEnabled && !gpsEnabled) {
                Toast.makeText(requireContext(),
                        "Location providers disabled. Enable location services and try again.",
                        Toast.LENGTH_LONG).show();
                return;
            }

            String provider = networkEnabled
                    ? LocationManager.NETWORK_PROVIDER
                    : LocationManager.GPS_PROVIDER;

            Toast.makeText(requireContext(), "Getting fresh location…", Toast.LENGTH_SHORT).show();

            lm.requestSingleUpdate(provider, new LocationListener() {
                @Override
                public void onLocationChanged(@NonNull Location location) {
                    handleIndoorRequestWithLocation(location);
                }

                @Override
                public void onProviderDisabled(@NonNull String provider) {
                    // Not critical
                }

                @Override
                public void onProviderEnabled(@NonNull String provider) {
                    // Not critical
                }
            }, Looper.getMainLooper());

        } catch (SecurityException e) {
            Toast.makeText(requireContext(),
                    "Location permission missing.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to get fresh location", e);
            Toast.makeText(requireContext(),
                    "Could not get a fresh location fix. Try again.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Executes the floorplan request using a fresh location fix.
     * This is basically your old requestNearbyIndoorMaps() body.
     */
    private void handleIndoorRequestWithLocation(@NonNull Location loc) {

        double lat = loc.getLatitude();
        double lon = loc.getLongitude();

        Log.d(TAG, "Indoor request lat=" + lat + " lon=" + lon);

        // Observed AP MACs (BSSID)
        List<String> macs = getObservedMacs();
        if (macs.isEmpty()) {
            Toast.makeText(requireContext(),
                    "No Wi-Fi access points detected. Try again in a different area.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (BuildConfig.OPENPOSITIONING_API_KEY == null || BuildConfig.OPENPOSITIONING_API_KEY.trim().isEmpty()) {
            Toast.makeText(requireContext(),
                    "OpenPositioning API key missing. Add OPENPOSITIONING_API_KEY to secrets.properties.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(requireContext(),
                "Requesting indoor maps… (" + macs.size() + " APs)",
                Toast.LENGTH_SHORT).show();

        Log.d(TAG, "OPENPOSITIONING_API_KEY length=" +
                (BuildConfig.OPENPOSITIONING_API_KEY == null ? 0 : BuildConfig.OPENPOSITIONING_API_KEY.trim().length()));

        floorplanApiClient.requestNearbyFloorplans(
                BuildConfig.OPENPOSITIONING_API_KEY,
                lat,
                lon,
                macs,
                new FloorplanApiClient.ResultCallback() {
                    @Override
                    public void onSuccess(@NonNull String rawJson) {
                        requireActivity().runOnUiThread(() -> {
                            Log.d(TAG, "Floorplan response: " + rawJson);
                            renderVenuesOnMap(rawJson);

                            if ("[]".equals(rawJson.trim())) {
                                Toast.makeText(requireContext(),
                                        "No indoor venues found nearby (try a different building).",
                                        Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(requireContext(),
                                        "Indoor venues received.",
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }

                    @Override
                    public void onError(@NonNull String message, @NonNull Exception e) {
                        requireActivity().runOnUiThread(() -> {
                            Log.e(TAG, message, e);
                            Toast.makeText(requireContext(),
                                    message,
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                }
        );
    }

    @NonNull
    private List<String> getObservedMacs() {

        // Trigger a refresh (async). Even if it fails, cached results are still useful.
        try {
            wifiManager.startScan();
        } catch (Exception ignored) { }

        List<ScanResult> results;
        try {
            results = wifiManager.getScanResults();
        } catch (SecurityException e) {
            return new ArrayList<>();
        }

        // De-duplicate + cap to keep request small and stable
        Set<String> unique = new HashSet<>();
        for (ScanResult r : results) {
            if (r.BSSID != null && !r.BSSID.isEmpty()) {
                unique.add(r.BSSID);
            }
            if (unique.size() >= 15) break;
        }

        return new ArrayList<>(unique);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_LOCATION_FOR_INDOOR) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestNearbyIndoorMaps();
            } else {
                Toast.makeText(requireContext(),
                        "Location permission is required for Wi-Fi scanning.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ======================================================================
    // Step (d): Draw building outlines / venue markers from the floorplan API response.
    // ======================================================================

    private void renderVenuesOnMap(@NonNull String rawJson) {

        if (mMap == null) return;

        // Clear previous overlays
        clearVenueOverlays();

        try {
            JSONArray venuesArray = tryParseAsArray(rawJson);
            if (venuesArray == null) {
                JSONObject obj = new JSONObject(rawJson);
                if (obj.has("features")) {
                    venuesArray = obj.getJSONArray("features");
                }
            }

            if (venuesArray == null || venuesArray.length() == 0) {
                Toast.makeText(requireContext(),
                        "No indoor venues to display.",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            int drawnCount = 0;

            for (int i = 0; i < venuesArray.length(); i++) {
                JSONObject venue = venuesArray.getJSONObject(i);

                List<LatLng> outline = extractVenueOutlineLatLngs(venue);

                if (outline != null && outline.size() >= 3) {
                    Polygon poly = mMap.addPolygon(new PolygonOptions()
                            .addAll(outline)
                            .clickable(true));
                    venuePolygons.add(poly);
                    polygonToVenue.put(poly, venue);
                    drawnCount++;
                } else {
                    LatLng center = extractVenueCenterLatLng(venue);
                    if (center != null) {
                        Marker m = mMap.addMarker(new MarkerOptions()
                                .position(center)
                                .title(extractVenueName(venue)));
                        if (m != null) venueMarkers.add(m);
                        drawnCount++;
                    }
                }
            }

            // Click listener: selecting a venue is required in Part (d)
            mMap.setOnPolygonClickListener(polygon -> {
                JSONObject venue = polygonToVenue.get(polygon);
                if (venue == null) return;

                String venueName = extractVenueName(venue);
                String venueId = extractVenueIdBestEffort(venue);

                // API returned id=null for murchison_house, so use "name" as a stable id
                if (venueId == null || venueId.trim().isEmpty() || "null".equalsIgnoreCase(venueId.trim())) {
                    venueId = venue.optString("name", null);
                }

                IndoorSelectionStore.saveSelectedVenue(requireContext(), venueId, venueName, venue);

                // Update the Home screen label immediately (even before you return from the indoor map).
                updateSelectedVenueLabel();

                Log.d(TAG, "Saved selected venue. id=" + venueId + " name=" + venueName);

                IndoorSelectionStore.saveSelectedVenue(requireContext(), venueId, venueName, venue);
                updateSelectedVenueLabel();

                // Open full-screen indoor map (Step 4B)
                Intent intent = new Intent(requireContext(), IndoorMapActivity.class);
                startActivity(intent);
            });

            if (drawnCount == 0) {
                Toast.makeText(requireContext(),
                        "Indoor venues received, but none could be rendered (outline format).",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(requireContext(),
                        "Displayed " + drawnCount + " venue(s) on the map.",
                        Toast.LENGTH_LONG).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to render venues: " + rawJson, e);
            Toast.makeText(requireContext(),
                    "Could not parse indoor venue response (see Logcat).",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Nullable
    private JSONArray tryParseAsArray(@NonNull String rawJson) {
        try {
            return new JSONArray(rawJson);
        } catch (JSONException ignored) {
            return null;
        }
    }

    private void clearVenueOverlays() {
        for (Polygon p : venuePolygons) p.remove();
        venuePolygons.clear();
        polygonToVenue.clear();

        for (Marker m : venueMarkers) m.remove();
        venueMarkers.clear();
    }

    @NonNull
    private String extractVenueName(@NonNull JSONObject venue) {
        JSONObject props = venue.optJSONObject("properties");
        if (props != null) {
            String n = props.optString("name", "");
            if (!n.isEmpty()) return n;
            n = props.optString("venue", "");
            if (!n.isEmpty()) return n;
            n = props.optString("id", "");
            if (!n.isEmpty()) return n;
        }

        String name = venue.optString("name", "");
        if (!name.isEmpty()) return name;

        name = venue.optString("venue", "");
        if (!name.isEmpty()) return name;

        name = venue.optString("id", "");
        if (!name.isEmpty()) return name;

        return "Venue";
    }

    @Nullable
    private String extractVenueIdBestEffort(@NonNull JSONObject venue) {
        String id = venue.optString("id", null);
        if (id != null && !id.trim().isEmpty()) return id;

        id = venue.optString("venue_id", null);
        if (id != null && !id.trim().isEmpty()) return id;

        JSONObject props = venue.optJSONObject("properties");
        if (props != null) {
            id = props.optString("id", null);
            if (id != null && !id.trim().isEmpty()) return id;

            id = props.optString("venue_id", null);
            if (id != null && !id.trim().isEmpty()) return id;
        }

        return null;
    }

    @Nullable
    private List<LatLng> extractVenueOutlineLatLngs(@NonNull JSONObject venue) {

        // Case A: GeoJSON geometry directly on the object (if API returns that style)
        JSONObject geometry = venue.optJSONObject("geometry");
        if (geometry != null) {
            List<LatLng> out = parseGeometryToLatLngs(geometry);
            if (out != null) return out;
        }

        // Case B: outline is a JSON ARRAY (your old handling)
        JSONArray outlineArr = venue.optJSONArray("outline");
        if (outlineArr != null) {
            List<LatLng> out = parseOutlineArray(outlineArr);
            if (out != null) return out;
        }

        // Case C: outline is a STRING containing GeoJSON (THIS IS YOUR CURRENT API RESPONSE)
        String outlineStr = venue.optString("outline", null);
        if (outlineStr != null && outlineStr.trim().startsWith("{")) {
            try {
                JSONObject outlineObj = new JSONObject(outlineStr);

                // Often FeatureCollection -> features[0].geometry
                if ("FeatureCollection".equalsIgnoreCase(outlineObj.optString("type"))) {
                    JSONArray features = outlineObj.optJSONArray("features");
                    if (features != null && features.length() > 0) {
                        JSONObject feature0 = features.optJSONObject(0);
                        if (feature0 != null) {
                            JSONObject g = feature0.optJSONObject("geometry");
                            if (g != null) {
                                List<LatLng> out = parseGeometryToLatLngs(g);
                                if (out != null) return out;
                            }
                        }
                    }
                }

                // If not a FeatureCollection, maybe it is directly a geometry
                JSONObject g2 = outlineObj.optJSONObject("geometry");
                if (g2 != null) {
                    List<LatLng> out = parseGeometryToLatLngs(g2);
                    if (out != null) return out;
                }

            } catch (Exception e) {
                Log.e(TAG, "Failed to parse outline string GeoJSON", e);
            }
        }

        // Case D: properties.outline (array or string)
        JSONObject props = venue.optJSONObject("properties");
        if (props != null) {
            JSONArray propsOutline = props.optJSONArray("outline");
            if (propsOutline != null) {
                List<LatLng> out = parseOutlineArray(propsOutline);
                if (out != null) return out;
            }

            String propsOutlineStr = props.optString("outline", null);
            if (propsOutlineStr != null && propsOutlineStr.trim().startsWith("{")) {
                try {
                    JSONObject outlineObj = new JSONObject(propsOutlineStr);
                    JSONArray features = outlineObj.optJSONArray("features");
                    if (features != null && features.length() > 0) {
                        JSONObject feature0 = features.optJSONObject(0);
                        if (feature0 != null) {
                            JSONObject g = feature0.optJSONObject("geometry");
                            if (g != null) {
                                List<LatLng> out = parseGeometryToLatLngs(g);
                                if (out != null) return out;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse properties.outline string GeoJSON", e);
                }
            }
        }

        return null;
    }

    @Nullable
    private List<LatLng> parseGeoJsonPolygon(@Nullable JSONArray coordinates) {
        if (coordinates == null) return null;

        JSONArray outerRing = coordinates.optJSONArray(0);
        if (outerRing == null) return null;

        List<LatLng> pts = new ArrayList<>();
        for (int i = 0; i < outerRing.length(); i++) {
            JSONArray pair = outerRing.optJSONArray(i);
            if (pair == null || pair.length() < 2) continue;

            double lon = pair.optDouble(0, Double.NaN);
            double lat = pair.optDouble(1, Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;

            pts.add(new LatLng(lat, lon));
        }

        return pts.size() >= 3 ? pts : null;
    }

    @Nullable
    private List<LatLng> parseOutlineArray(@NonNull JSONArray outlineArr) {
        List<LatLng> pts = new ArrayList<>();

        for (int i = 0; i < outlineArr.length(); i++) {
            Object item = outlineArr.opt(i);

            if (item instanceof JSONObject) {
                JSONObject o = (JSONObject) item;
                double lat = o.optDouble("lat", Double.NaN);
                double lon = o.optDouble("lon", Double.NaN);

                if (Double.isNaN(lon)) lon = o.optDouble("lng", Double.NaN);

                if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                    pts.add(new LatLng(lat, lon));
                }
            } else if (item instanceof JSONArray) {
                JSONArray pair = (JSONArray) item;
                if (pair.length() >= 2) {
                    double lon = pair.optDouble(0, Double.NaN);
                    double lat = pair.optDouble(1, Double.NaN);
                    if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                        pts.add(new LatLng(lat, lon));
                    }
                }
            }
        }

        return pts.size() >= 3 ? pts : null;
    }

    @Nullable
    private LatLng extractVenueCenterLatLng(@NonNull JSONObject venue) {

        double lat = venue.optDouble("lat", Double.NaN);
        double lon = venue.optDouble("lon", Double.NaN);
        if (!Double.isNaN(lat) && !Double.isNaN(lon)) return new LatLng(lat, lon);

        JSONObject props = venue.optJSONObject("properties");
        if (props != null) {
            lat = props.optDouble("lat", Double.NaN);
            lon = props.optDouble("lon", Double.NaN);
            if (!Double.isNaN(lat) && !Double.isNaN(lon)) return new LatLng(lat, lon);
        }

        JSONObject geometry = venue.optJSONObject("geometry");
        if (geometry != null) {
            String type = geometry.optString("type", "");
            if ("Point".equalsIgnoreCase(type)) {
                JSONArray coords = geometry.optJSONArray("coordinates");
                if (coords != null && coords.length() >= 2) {
                    lon = coords.optDouble(0, Double.NaN);
                    lat = coords.optDouble(1, Double.NaN);
                    if (!Double.isNaN(lat) && !Double.isNaN(lon)) return new LatLng(lat, lon);
                }
            }
        }

        return null;
    }

    @Nullable
    private List<LatLng> parseGeometryToLatLngs(@NonNull JSONObject geometry) {
        String type = geometry.optString("type", "");

        if ("Polygon".equalsIgnoreCase(type)) {
            JSONArray coords = geometry.optJSONArray("coordinates");
            return parseGeoJsonPolygon(coords);
        }

        if ("MultiPolygon".equalsIgnoreCase(type)) {
            JSONArray coords = geometry.optJSONArray("coordinates");
            return parseGeoJsonMultiPolygon(coords);
        }

        return null;
    }

    @Nullable
    private List<LatLng> parseGeoJsonMultiPolygon(@Nullable JSONArray coordinates) {
        if (coordinates == null) return null;

        // MultiPolygon coordinates: [ [ [ [lon,lat], ... ] ] , ... ]
        // Take first polygon's outer ring: coordinates[0][0]
        JSONArray firstPoly = coordinates.optJSONArray(0);
        if (firstPoly == null) return null;

        JSONArray outerRing = firstPoly.optJSONArray(0);
        if (outerRing == null) return null;

        List<LatLng> pts = new ArrayList<>();
        for (int i = 0; i < outerRing.length(); i++) {
            JSONArray pair = outerRing.optJSONArray(i);
            if (pair == null || pair.length() < 2) continue;

            double lon = pair.optDouble(0, Double.NaN);
            double lat = pair.optDouble(1, Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;

            pts.add(new LatLng(lat, lon));
        }

        return pts.size() >= 3 ? pts : null;
    }

    // -----------------------------------------------------------------------------------------
    // Old last-known helper (no longer used). Kept for now so i can compare/remove later.
    // -----------------------------------------------------------------------------------------
    @Nullable
    private Location getBestLastKnownLocation() {
        LocationManager lm =
                (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);

        Location best = null;

        try {
            Location gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

            if (gps != null) best = gps;
            if (net != null && (best == null || net.getAccuracy() < best.getAccuracy())) best = net;

        } catch (SecurityException ignored) {
            // Permission checked before calling
        }

        return best;
    }
}



