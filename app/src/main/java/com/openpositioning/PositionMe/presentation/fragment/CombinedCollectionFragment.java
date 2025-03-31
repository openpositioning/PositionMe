package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.local.DataFileManager;
import com.openpositioning.PositionMe.domain.SensorDataPredictor;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.Wifi;

import org.json.JSONObject;

import java.util.List;

/**
 * CombinedCollectionFragment merges sensor data collection and calibration/map UI.
 * All data and file operations are delegated to DataFileManager.
 */
public class CombinedCollectionFragment extends Fragment {

    private static final String TAG = "CombinedCollectionFragment";

    // Sensor and data management
    private SensorFusion sensorFusion;
    private SensorDataPredictor predictor;
    private Handler sensorUpdateHandler;
    private Runnable sensorUpdateTask;
    private long lastWifiRequestTime = 0;
    private static final long WIFI_REQUEST_INTERVAL_MS = 8000; // 8 seconds between WiFi updates
    private static final long PASSIVE_COLLECTION_INTERVAL_MS = 500; // e.g., 2 samples/second
    private DataFileManager dataFileManager;

    // UI elements & calibration fields
    private TrajectoryMapFragment trajectoryMapFragment;
    private Spinner floorSpinner, indoorStateSpinner;
    private EditText buildingNameEditText;
    private Button calibrationButton, finishButton, cancelButton;
    private Marker calibrationMarker;
    private boolean markerPlaced = false;
    private int selectedFloorLevel = -1;
    private int selectedIndoorState = 0;
    private String buildingName = null;

    // Location update for map (simulated)
    private Handler locationUpdateHandler;
    private Runnable locationUpdateRunnable;
    private static final long UPDATE_INTERVAL = 1000; // 1 second

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable android.os.Bundle savedInstanceState) {
        // Inflate the fragment layout (assumed to be fragment_collection.xml)
        return inflater.inflate(R.layout.fragment_collection, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable android.os.Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Optionally keep the screen on
        if (getActivity() != null) {
            getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // Initialize SensorFusion and start listening for sensor updates
        sensorFusion = SensorFusion.getInstance();
        sensorFusion.setContext(requireContext().getApplicationContext());
        sensorFusion.resumeListening();

        // Initialize SensorDataPredictor (e.g., load your model)
        predictor = new SensorDataPredictor(requireContext());

        // Initialize DataFileManager for file I/O and buffering
        dataFileManager = new DataFileManager(requireContext());

        // Attach the map fragment (TrajectoryMapFragment) for calibration UI
        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.mapContainer);
        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            FragmentTransaction ft = getChildFragmentManager().beginTransaction();
            ft.replace(R.id.mapContainer, trajectoryMapFragment).commit();
        }

        // Set up UI elements
        floorSpinner = view.findViewById(R.id.floorSpinner);
        indoorStateSpinner = view.findViewById(R.id.indoorStateSpinner);
        buildingNameEditText = view.findViewById(R.id.buildingNameEditText);
        calibrationButton = view.findViewById(R.id.addCalibrationMarkerButton);
        finishButton = view.findViewById(R.id.finishButton);
        cancelButton = view.findViewById(R.id.cancelButton);

        setupFloorSpinner();
        setupIndoorStateSpinner();

        calibrationButton.setText("Add Tag");
        calibrationButton.setOnClickListener(v -> {
            if (!markerPlaced) {
                placeCalibrationMarker();
                markerPlaced = true;
                calibrationButton.setBackgroundColor(android.graphics.Color.GREEN);
                calibrationButton.setText("Confirm");
                Log.d(TAG, "Calibration marker placed.");
            } else {
                confirmCalibration();
                calibrationMarker = null;
                markerPlaced = false;
                calibrationButton.setBackgroundColor(android.graphics.Color.YELLOW);
                calibrationButton.setText("Add Tag");
            }
        });

        finishButton.setOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().finish();
            }
        });

        cancelButton.setOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().finish();
            }
        });

        // Start continuous location updates for the map display
        startLocationUpdates();

        // Start the passive sensor data collection loop
        sensorUpdateHandler = new Handler(Looper.getMainLooper());
        sensorUpdateTask = new Runnable() {
            @Override
            public void run() {
                collectFullSensorData();
                sensorUpdateHandler.postDelayed(this, PASSIVE_COLLECTION_INTERVAL_MS);
            }
        };
        sensorUpdateHandler.post(sensorUpdateTask);
    }

    /**
     * Collects sensor data using SensorFusion.getAllSensorData() and delegates the record
     * to DataFileManager. Also triggers WiFi positioning requests as needed.
     */
    private void collectFullSensorData() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastWifiRequestTime > WIFI_REQUEST_INTERVAL_MS) {
                lastWifiRequestTime = now;
                requestWifiLocationUpdate();
            }
            JSONObject record = sensorFusion.getAllSensorData();
            dataFileManager.addRecord(record);
            Log.d(TAG, "Collected sensor data.");
        } catch (Exception e) {
            Log.e(TAG, "Error collecting sensor data", e);
        }
    }

    /**
     * Requests a WiFi positioning update based on current WiFi scan results.
     */
    private void requestWifiLocationUpdate() {
        List<Wifi> wifiList = sensorFusion.getWifiList();
        if (wifiList == null || wifiList.isEmpty()) {
            Log.w(TAG, "No WiFi data available yet, skipping WiFi positioning request.");
            return;
        }
        try {
            JSONObject wifiAccessPoints = new JSONObject();
            for (Wifi wifi : wifiList) {
                wifiAccessPoints.put(String.valueOf(wifi.getBssid()), wifi.getLevel());
            }
            JSONObject fingerprint = new JSONObject();
            fingerprint.put("wf", wifiAccessPoints);
            sensorFusion.getWiFiPositioning().request(fingerprint, new com.openpositioning.PositionMe.data.remote.WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(com.google.android.gms.maps.model.LatLng wifiLocation, int floor) {
                    Log.d(TAG, "WiFi Positioning Success: lat=" + wifiLocation.latitude + ", lon=" +
                            wifiLocation.longitude + ", floor=" + floor);
                }
                @Override
                public void onError(String message) {
                    Log.e(TAG, "WiFi Positioning Failed: " + message);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error building WiFi fingerprint request", e);
        }
    }

    /**
     * Called when the user confirms a calibration.
     */
    private void onCalibrationTriggered(double userLat, double userLng, int indoorState, int floorLevel, String buildingName) {
        try {
            JSONObject calibrationRecord = sensorFusion.getAllSensorData();
            calibrationRecord.put("isCalibration", true);
            calibrationRecord.put("userLat", userLat);
            calibrationRecord.put("userLng", userLng);
            calibrationRecord.put("floorLevel", floorLevel);
            calibrationRecord.put("indoorState", indoorState);
            calibrationRecord.put("buildingName", buildingName);
            dataFileManager.addRecord(calibrationRecord);
            Log.d(TAG, "Calibration triggered.");
        } catch (Exception e) {
            Log.e(TAG, "Error triggering calibration", e);
        }
    }

    private void setupFloorSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                requireContext(),
                R.array.floor_levels,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        floorSpinner.setAdapter(adapter);
        floorSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String selected = parent.getItemAtPosition(position).toString();
                if (selected.contains("Outdoors") || selected.startsWith("-1")) {
                    selectedFloorLevel = -1;
                } else {
                    try {
                        selectedFloorLevel = Integer.parseInt(selected.split(" ")[0]);
                    } catch (NumberFormatException e) {
                        selectedFloorLevel = -1;
                    }
                }
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    private void setupIndoorStateSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                requireContext(),
                R.array.indoor_states,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        indoorStateSpinner.setAdapter(adapter);
        indoorStateSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                try {
                    selectedIndoorState = Integer.parseInt(parent.getItemAtPosition(position).toString().substring(0, 1));
                } catch (Exception e) {
                    selectedIndoorState = 0;
                }
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    private void placeCalibrationMarker() {
        GoogleMap map = trajectoryMapFragment.getGoogleMap();
        if (map == null) return;
        if (calibrationMarker != null) {
            map.animateCamera(CameraUpdateFactory.newLatLng(calibrationMarker.getPosition()));
            return;
        }
        LatLng userLocation = trajectoryMapFragment.getCurrentLocation();
        if (userLocation == null) {
            userLocation = new LatLng(55.9228, -3.1746); // fallback location
        }
        calibrationMarker = map.addMarker(new MarkerOptions()
                .position(userLocation)
                .title("Calibration Marker")
                .draggable(true));
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(userLocation, 19f));
        trajectoryMapFragment.updateCalibrationPinLocation(userLocation, false);
        map.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener() {
            @Override public void onMarkerDragStart(@NonNull Marker marker) {}
            @Override public void onMarkerDrag(@NonNull Marker marker) {}
            @Override public void onMarkerDragEnd(@NonNull Marker marker) {
                if (calibrationMarker != null) {
                    calibrationMarker.setPosition(marker.getPosition());
                    trajectoryMapFragment.updateCalibrationPinLocation(marker.getPosition(), false);
                }
            }
        });
    }

    private void confirmCalibration() {
        if (calibrationMarker == null) {
            Toast.makeText(getContext(), "No calibration marker placed!", Toast.LENGTH_SHORT).show();
            return;
        }
        buildingName = buildingNameEditText.getText().toString();
        if (TextUtils.isEmpty(buildingName)) {
            buildingName = null;
        }
        LatLng markerPos = calibrationMarker.getPosition();
        trajectoryMapFragment.updateCalibrationPinLocation(markerPos, true);
        double lat = markerPos.latitude;
        double lng = markerPos.longitude;
        Toast.makeText(getContext(), "Calibration confirmed at (" + lat + ", " + lng + ")", Toast.LENGTH_SHORT).show();
        onCalibrationTriggered(lat, lng, selectedIndoorState, selectedFloorLevel, buildingName);
        calibrationMarker.remove();
    }

    private void startLocationUpdates() {
        locationUpdateHandler = new Handler(Looper.getMainLooper());
        locationUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                LatLng newLocation = getWifiPredictedLocation();
                float orientation = 0f; // static orientation for demonstration
                if (trajectoryMapFragment != null) {
                    trajectoryMapFragment.updateFusionLocation(newLocation, orientation);
                }
                locationUpdateHandler.postDelayed(this, UPDATE_INTERVAL);
            }
        };
        locationUpdateHandler.post(locationUpdateRunnable);
    }

    private void stopLocationUpdates() {
        if (locationUpdateHandler != null && locationUpdateRunnable != null) {
            locationUpdateHandler.removeCallbacks(locationUpdateRunnable);
        }
    }

    private LatLng getWifiPredictedLocation() {
        if (predictor != null && sensorFusion != null) {
            LatLng predictedPos = predictor.predictPosition(sensorFusion);
            if (predictedPos != null) {
                Log.d(TAG, "WiFi Predicted pos: " + predictedPos.latitude + ", " + predictedPos.longitude);
                return predictedPos;
            }
        }
        return new LatLng(55.9228, -3.1746);
    }

    @Override
    public void onPause() {
        super.onPause();
        stopLocationUpdates();
    }

    @Override
    public void onDestroy() {
        if (sensorUpdateHandler != null && sensorUpdateTask != null) {
            sensorUpdateHandler.removeCallbacks(sensorUpdateTask);
        }
        if (sensorFusion != null) {
            sensorFusion.stopListening();
        }
        dataFileManager.close();
        stopLocationUpdates();
        super.onDestroy();
    }
}
