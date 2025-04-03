package com.openpositioning.PositionMe.fragments;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;
import java.util.LinkedHashMap;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.viewitems.SensorAdapter;
import com.openpositioning.PositionMe.SensorData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SensorsFragment extends Fragment implements SensorEventListener {

    private RecyclerView recyclerView;
    private SensorAdapter sensorAdapter;
    private List<SensorData> sensorList;
    private SensorManager sensorManager;
    private Map<String, SensorData> sensorDataMap;
    private Button startButton, stopButton;
    private boolean isMeasuring = false; // measurement enable toggle
    private LocationListener locationListener;
    private LocationManager locationManager;


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_sensors, container, false);

        // === Initialize RecyclerView for displaying sensor data ===
        recyclerView = view.findViewById(R.id.sensor_recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        sensorList = new ArrayList<>();
        sensorAdapter = new SensorAdapter(sensorList);
        recyclerView.setAdapter(sensorAdapter);

        // === Initialize SensorManager and retrieve all available sensors ===
        sensorManager = (SensorManager) getActivity().getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> sensorAvailableList = sensorManager.getSensorList(Sensor.TYPE_ALL);

        for (Sensor sensor : sensorAvailableList) {
            Log.d("AvailableSensors", "Sensor: " + sensor.getName() + " | Type: " + sensor.getType());
        }

        // Map to hold sensor data entries in insertion order
        sensorDataMap = new LinkedHashMap<>();

        // === Register sensors to monitor ===
        registerSensor(Sensor.TYPE_ACCELEROMETER, "Accelerometer");
        registerSensor(Sensor.TYPE_GYROSCOPE, "Gyroscope");
        registerSensor(Sensor.TYPE_MAGNETIC_FIELD, "Magnetic Field");
        registerSensor(Sensor.TYPE_GRAVITY, "Gravity");
        registerSensor(Sensor.TYPE_LIGHT, "Light Sensor");
        registerSensor(Sensor.TYPE_PRESSURE, "Pressure Sensor");
        registerSensor(Sensor.TYPE_PROXIMITY, "Proximity Sensor");

        // Additional sensors
        registerSensor(Sensor.TYPE_LINEAR_ACCELERATION, "Linear Acceleration");
        registerSensor(Sensor.TYPE_ROTATION_VECTOR, "Rotation Vector");
        registerSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, "Geomagnetic Rotation Vector");

        // === Initialize GNSS if location permission is granted ===
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startGNSSListener(); // Start GNSS listener immediately
        } else {
            requestLocationPermission(); // Request location permission from user
        }

        // === Set up Start and Stop buttons for measurement ===
        startButton = view.findViewById(R.id.button_start);
        stopButton = view.findViewById(R.id.button_stop);

        startButton.setOnClickListener(v -> startMeasurement());
        stopButton.setOnClickListener(v -> stopMeasurement());

        return view;
    }

    /**
     * Registers a sensor if available and adds it to the UI tracking list.
     *
     * @param sensorType The type constant from SensorManager (e.g., Sensor.TYPE_ACCELEROMETER)
     * @param sensorName A friendly name to show in the UI
     */
    private void registerSensor(int sensorType, String sensorName) {
        Sensor sensor = sensorManager.getDefaultSensor(sensorType);
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
            sensorDataMap.put(sensorName, new SensorData(sensorName, "Waiting..."));
            sensorList.add(sensorDataMap.get(sensorName));
            Log.d("SensorDebug", "Registered sensor: " + sensorName); // Record successful registration
        } else {
            Log.d("SensorDebug", "Sensor not found: " + sensorName); // Sensor not available
        }
    }

    /**
     * Starts sensor measurement and updates the UI.
     * All registered sensors and GNSS will show active values.
     */
    private void startMeasurement() {
        if (!isMeasuring) {
            isMeasuring = true;
            sensorList.clear();

            for (String key : sensorDataMap.keySet()) {
                SensorData data = sensorDataMap.get(key);
                data.setValue("Measuring...");
                sensorList.add(data);
            }

            sensorAdapter.notifyDataSetChanged(); // Refresh the UI
            Log.d("SensorDebug", "Sensor List Size after Start: " + sensorList.size());

            // Restart GNSS monitoring if applicable
            startGNSSListener();
        }
    }

    /**
     * Stops sensor measurement and updates the UI accordingly.
     */
    private void stopMeasurement() {
        if (isMeasuring) {
            isMeasuring = false;
            Toast.makeText(getContext(), "Stop measuring...", Toast.LENGTH_SHORT).show();

            for (String key : sensorDataMap.keySet()) {
                sensorDataMap.get(key).setValue("Stopped");
            }

            sensorAdapter.notifyDataSetChanged();
        }
    }

    /**
     * Called when any registered sensor provides new data.
     * Updates the corresponding value in the sensor data list and refreshes the UI.
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (isMeasuring) {
            String sensorName = null;
            String value = "";

            switch (event.sensor.getType()) {
                case Sensor.TYPE_ACCELEROMETER:
                    sensorName = "Accelerometer";
                    value = String.format("X: %.2f  Y: %.2f  Z: %.2f", event.values[0], event.values[1], event.values[2]);
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    sensorName = "Gyroscope";
                    value = String.format("X: %.2f  Y: %.2f  Z: %.2f", event.values[0], event.values[1], event.values[2]);
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    sensorName = "Magnetic Field";
                    value = String.format("X: %.2f  Y: %.2f  Z: %.2f", event.values[0], event.values[1], event.values[2]);
                    break;
                case Sensor.TYPE_GRAVITY:
                    sensorName = "Gravity";
                    value = String.format("X: %.2f  Y: %.2f  Z: %.2f", event.values[0], event.values[1], event.values[2]);
                    break;
                case Sensor.TYPE_LIGHT:
                    sensorName = "Light Sensor";
                    value = String.format("Level: %.2f", event.values[0]);
                    break;
                case Sensor.TYPE_PRESSURE:
                    sensorName = "Pressure Sensor";
                    value = String.format("Level: %.2f hPa", event.values[0]);
                    break;
                case Sensor.TYPE_PROXIMITY:
                    sensorName = "Proximity Sensor";
                    value = String.format("Distance: %.2f cm", event.values[0]);
                    break;
                case Sensor.TYPE_LINEAR_ACCELERATION:
                    sensorName = "Linear Acceleration";
                    value = String.format("X: %.2f  Y: %.2f  Z: %.2f", event.values[0], event.values[1], event.values[2]);
                    break;
                case Sensor.TYPE_ROTATION_VECTOR:
                    sensorName = "Rotation Vector";
                    value = String.format("X: %.2f  Y: %.2f  Z: %.2f", event.values[0], event.values[1], event.values[2]);
                    break;
                case Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR:
                    sensorName = "Geomagnetic Rotation Vector";
                    value = String.format("X: %.2f  Y: %.2f  Z: %.2f", event.values[0], event.values[1], event.values[2]);
                    break;
            }

            if (sensorName != null && sensorDataMap.containsKey(sensorName)) {
                sensorDataMap.get(sensorName).setValue(value);

                // Ensure UI updates preserve the original sensor display order
                List<SensorData> sortedData = new ArrayList<>(sensorDataMap.values());
                sensorAdapter.updateData(sortedData);
            }
        }
    }



    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // NOT USED in this implementation
    }


    // Permission launcher for requesting fine location access
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Toast.makeText(getContext(), "Location permission granted", Toast.LENGTH_SHORT).show();
                    startGNSSListener(); // Start GNSS immediately after permission is granted
                } else {
                    Toast.makeText(getContext(), "Location permission denied", Toast.LENGTH_SHORT).show();
                }
            });

    /**
     * Requests location permission if not already granted.
     * If permission is granted, GNSS listener is started.
     */
    private void requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        } else {
            startGNSSListener(); // Already granted — start GNSS directly
        }
    }

    /**
     * Starts GNSS location updates and binds results to the UI if measurement is active.
     */
    private void startGNSSListener() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e("GNSS", "Permission not granted. Cannot start GNSS.");
            return;
        }

        // Add GNSS data entry if not already added
        if (!sensorDataMap.containsKey("GNSS")) {
            sensorDataMap.put("GNSS", new SensorData("GNSS", "Waiting..."));
            sensorList.add(sensorDataMap.get("GNSS"));
            sensorAdapter.notifyDataSetChanged(); // Refresh the UI immediately
        }

        // Initialize location listener if needed
        if (locationListener == null) {
            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(@NonNull Location location) {
                    if (isMeasuring) {
                        Log.d("GNSS", "GNSS update received during measurement.");

                        if (!isAdded()) {
                            Log.w("SensorsFragment", "Fragment not attached. Skipping location update.");
                            return;
                        }

                        Activity activity = getActivity();
                        if (activity == null) {
                            Log.w("SensorsFragment", "Activity is null. Skipping location update.");
                            return;
                        }

                        // Use activity.runOnUiThread to avoid crashes from detached fragments
                        activity.runOnUiThread(() -> {
                            if (sensorDataMap.containsKey("GNSS")) {
                                sensorDataMap.get("GNSS").setValue(
                                        String.format("Lat: %.5f  Lon: %.5f  Alt: %.2f",
                                                location.getLatitude(),
                                                location.getLongitude(),
                                                location.getAltitude())
                                );

                                // Update the UI with new GNSS data
                                Log.d("GNSS", "GNSS UI updated.");
                                sensorAdapter.updateData(new ArrayList<>(sensorDataMap.values()));
                            }
                        });
                    }
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {}

                @Override
                public void onProviderEnabled(@NonNull String provider) {}

                @Override
                public void onProviderDisabled(@NonNull String provider) {}
            };
        }

        // Request GNSS updates every second or every meter of movement
        LocationManager locationManager = (LocationManager) requireActivity().getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000, // Update every 1 second
                    1,    // Update when moved 1 meter
                    locationListener
            );
            Log.d("GNSS", "GNSS listening started.");
        } else {
            Log.e("GNSS", "LocationManager is null. Cannot start GNSS.");
        }
    }



    @Override
    public void onResume() {
        super.onResume();

        if (recyclerView != null && sensorAdapter != null) {
            recyclerView.setAdapter(sensorAdapter);
            Log.d("SensorDebug", "RecyclerView Adapter set in onResume()");
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startGNSSListener();  // Make sure GNSS is in the UI
        } else {
            requestLocationPermission();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
            Log.d("GNSS", "GNSS Listener stopped!");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sensorManager.unregisterListener(this);

        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

}
