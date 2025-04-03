//Authored by Ashley Dong, Sriram Jagathisan, Yuxuan Liu

package com.openpositioning.PositionMe.fragments;

import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.sensors.WiFiPositioning;
import com.openpositioning.PositionMe.sensors.Wifi;
import com.openpositioning.PositionMe.sensors.WifiDataProcessor;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

public class WifiFragment extends Fragment implements OnMapReadyCallback {

    private static final String TAG = "WifiFragment";

    private GoogleMap mMap;
    private Marker wifiMarker;
    private Polyline wifiPolyline;
    private List<LatLng> wifiPath;

    private Handler updateHandler = new Handler(Looper.getMainLooper());
    private Runnable updateRunnable;
    private static final long UPDATE_INTERVAL_MS = 5000; // 5-second update interval

    // Current floor (default is 0)
    private int currentFloor = 0;

    private WifiDataProcessor wifiDataProcessor;
    private WiFiPositioning wifiPositioning;
    private IndoorMapManager indoorMapManager;

    private Spinner mapSpinner;

    public WifiFragment() {
        // Required empty public constructor.
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Inflate the layout from fragment_wifi.xml
        return inflater.inflate(R.layout.fragment_wifi, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated called");

        // Set up the exit button to return to HomeFragment.
        view.findViewById(R.id.exitButton_wifi).setOnClickListener(v ->
                Navigation.findNavController(v).popBackStack(R.id.homeFragment, false)
        );

        // Set up floor buttons.
        view.findViewById(R.id.floorUpButton_wifi).setOnClickListener(v -> {
            if (indoorMapManager != null) {
                indoorMapManager.increaseFloor();
            }
        });
        view.findViewById(R.id.floorDownButton_wifi).setOnClickListener(v -> {
            if (indoorMapManager != null) {
                indoorMapManager.decreaseFloor();
            }
        });

        // Initialize WiFi data processor and positioning service.
        wifiDataProcessor = new WifiDataProcessor(getContext());
        wifiPositioning = new WiFiPositioning(getContext());

        // Define periodic updates for WiFi positioning.
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateWifiPosition();
                updateHandler.postDelayed(this, UPDATE_INTERVAL_MS);
            }
        };

        // Retrieve the SupportMapFragment and call getMapAsync().
        SupportMapFragment mapFragment = (SupportMapFragment) getChildFragmentManager().findFragmentById(R.id.wifiMap);
        if (mapFragment != null) {
            Log.d(TAG, "Map fragment found, calling getMapAsync");
            mapFragment.getMapAsync(this);
        } else {
            Log.e(TAG, "Map fragment is null");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateHandler.postDelayed(updateRunnable, UPDATE_INTERVAL_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        updateHandler.removeCallbacks(updateRunnable);
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        Log.d(TAG, "onMapReady called");
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
        mMap.getUiSettings().setCompassEnabled(true);

        // Initialize IndoorMapManager with the map.
        indoorMapManager = new IndoorMapManager(mMap);

        // Initialize an empty path list.
        wifiPath = new java.util.ArrayList<>();

        // Note: We are no longer setting a hard-coded initial position.
        // The marker and camera will be set when the first WiFi position update is received.

        // Set up the map spinner now that mMap is available.
        mapSpinner = getView().findViewById(R.id.mapSwitchSpinner_wifi);
        setupMapSpinner();
    }

    /**
     * Retrieves the current WiFi fingerprint from the scan results,
     * builds a JSON fingerprint following the flat mapping structure, and requests the WiFi position via the API.
     */
    private void updateWifiPosition() {
        // Get the list of scanned WiFi objects.
        List<Wifi> wifiList = wifiDataProcessor.getWifiList();
        if (wifiList == null || wifiList.isEmpty()) {
            Log.d(TAG, "No WiFi scan results available");
            return;
        }
        try {
            JSONObject wifiAccessPoints = new JSONObject();
            // Loop through each Wifi object.
            for (Wifi data : wifiList) {
                // Use the BSSID (converted to string) as key and map directly to its RSSI value.
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }
            JSONObject wifiFingerprint = new JSONObject();
            wifiFingerprint.put("wf", wifiAccessPoints);
            Log.d(TAG, "Fingerprint JSON: " + wifiFingerprint.toString());

            // Request the position using WiFiPositioning with a Volley callback.
            wifiPositioning.request(wifiFingerprint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng location, int floor) {
                    Log.d(TAG, "WiFi position: " + location.toString() + ", Floor: " + floor);
                    // Outlier detection: if new position is more than 20m from the last, show Toast and skip.
                    if (!wifiPath.isEmpty()) {
                        LatLng lastPosition = wifiPath.get(wifiPath.size() - 1);
                        float[] results = new float[1];
                        Location.distanceBetween(lastPosition.latitude, lastPosition.longitude,
                                location.latitude, location.longitude, results);
                        if (results[0] > 50) {
                            Toast.makeText(getContext(), "Outlier detected", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    updateMapPosition(location, floor);
                }

                @Override
                public void onError(String message) {
                    Log.e(TAG, "Error retrieving WiFi position: " + message);
                    if (message.contains("404")) {
                        Toast.makeText(getContext(), "No WiFi coverage detected", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getContext(), "Error: " + message, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "Error building JSON fingerprint: " + e.getMessage());
        }
    }

    /**
     * Updates the marker position and polyline path on the map,
     * and updates the IndoorMapManager with the new location and floor.
     *
     * @param newPosition the new LatLng position.
     * @param floor       the floor returned from the API.
     */
    private void updateMapPosition(LatLng newPosition, int floor) {
        currentFloor = floor;
        if (wifiMarker == null) {
            // First valid location: create the marker, initialize the path, and move the camera.
            wifiMarker = mMap.addMarker(new MarkerOptions().position(newPosition).title("WiFi Position"));
            wifiPath.add(newPosition);
            PolylineOptions polylineOptions = new PolylineOptions()
                    .addAll(wifiPath)
                    .color(getResources().getColor(R.color.pastelBlue))
                    .zIndex(1000f); // Set high z-index so it overlays the map.
            wifiPolyline = mMap.addPolyline(polylineOptions);
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newPosition, 19f));
        } else {
            // Subsequent updates: move marker, update path, and animate camera.
            wifiMarker.setPosition(newPosition);
            wifiPath.add(newPosition);
            wifiPolyline.setPoints(wifiPath);
            mMap.animateCamera(CameraUpdateFactory.newLatLng(newPosition));
        }
        if (indoorMapManager != null) {
            indoorMapManager.setCurrentLocation(newPosition);
            indoorMapManager.setCurrentFloor(currentFloor, true);
            indoorMapManager.setIndicationOfIndoorMap();
        }
    }

    /**
     * Sets up the map spinner to allow switching between different map types.
     */
    private void setupMapSpinner() {
        String[] maps = new String[]{"Hybrid", "Normal", "Satellite"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, maps);
        mapSpinner.setAdapter(adapter);
        mapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Log.d(TAG, "Spinner selected: " + maps[position]);
                if (mMap == null) return;
                switch (position) {
                    case 0:
                        mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                        break;
                    case 1:
                        mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                        break;
                    case 2:
                        mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                        break;
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                if (mMap != null) mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
            }
        });
    }
}