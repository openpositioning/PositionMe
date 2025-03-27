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

        // 初始化 RecyclerView 适配器
        // Initialize the RecyclerView adapter
        recyclerView = view.findViewById(R.id.sensor_recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        sensorList = new ArrayList<>();
        sensorAdapter = new SensorAdapter(sensorList);
        recyclerView.setAdapter(sensorAdapter);


        sensorManager = (SensorManager) getActivity().getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> sensorAvaiableList = sensorManager.getSensorList(Sensor.TYPE_ALL);

        for (Sensor sensorAvaiable : sensorAvaiableList) {
            Log.d("AvailableSensors", "Sensor: " + sensorAvaiable.getName() + " | Type: " + sensorAvaiable.getType());
        }

        sensorDataMap = new LinkedHashMap<>();

        // 添加所有需要监听的传感器
        // Add all sensors that need to be monitored
        registerSensor(Sensor.TYPE_ACCELEROMETER, "Accelerometer");
        registerSensor(Sensor.TYPE_GYROSCOPE, "Gyroscope");
        registerSensor(Sensor.TYPE_MAGNETIC_FIELD, "Magnetic Field");
        registerSensor(Sensor.TYPE_GRAVITY, "Gravity");
        registerSensor(Sensor.TYPE_LIGHT, "Light Sensor");
        registerSensor(Sensor.TYPE_PRESSURE, "Pressure Sensor");
        registerSensor(Sensor.TYPE_PROXIMITY, "Proximity Sensor");

        // 添加新的传感器
        // Add a new sensor
        registerSensor(Sensor.TYPE_LINEAR_ACCELERATION, "Linear Acceleration");
        registerSensor(Sensor.TYPE_ROTATION_VECTOR, "Rotation Vector");
        registerSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, "Geomagnetic Rotation Vector");

        // ✅ 确保 `GNSS` 被正确初始化
        // ✅ Make sure `GNSS` is initialized correctly
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startGNSSListener(); // 🟢 如果已有权限，直接启动 GNSS If you already have permission, start GNSS directly
        } else {
            requestLocationPermission(); // 🔥 请求权限 Request Permission
        }

        startButton = view.findViewById(R.id.button_start);
        stopButton = view.findViewById(R.id.button_stop);

        startButton.setOnClickListener(v -> startMeasurement());
        stopButton.setOnClickListener(v -> stopMeasurement());

        return view;
    }

    private void registerSensor(int sensorType, String sensorName) {
        Sensor sensor = sensorManager.getDefaultSensor(sensorType);
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
            sensorDataMap.put(sensorName, new SensorData(sensorName, "Waiting..."));
            sensorList.add(sensorDataMap.get(sensorName));
            Log.d("SensorDebug", "Registered sensor: " + sensorName); // ✅ 记录注册成功 Record successful registration
        } else {
            Log.d("SensorDebug", "Sensor not found: " + sensorName); // ❌ 传感器不可用 Sensor not available
        }
    }

    private void startMeasurement() {
        if (!isMeasuring) {
            isMeasuring = true;
            sensorList.clear();

            for (String key : sensorDataMap.keySet()) {
                SensorData data = sensorDataMap.get(key);
                data.setValue("Measuring..."); // 🔥 GNSS 也变成 Measuring... GNSS has also become a measuring...
                sensorList.add(data);
            }

            sensorAdapter.notifyDataSetChanged(); // 🔥 刷新 UI Refresh the UI
            Log.d("SensorDebug", "Sensor List Size after Start: " + sensorList.size());

            // 🔥 重新启动 GNSS 监听
            // 🔥 Restart GNSS monitoring
            startGNSSListener();
        }
    }

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

                // 🔥 确保 UI 按照固定顺序更新
                // 🔥 Ensure UI updates in a fixed order
                List<SensorData> sortedData = new ArrayList<>(sensorDataMap.values());
                sensorAdapter.updateData(sortedData);
            }
        }
    }


    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Toast.makeText(getContext(), "Location permission granted", Toast.LENGTH_SHORT).show();
                    startGNSSListener(); // 🟢 用户授予权限后立即启动 GNSS Start GNSS immediately after user grants permission
                } else {
                    Toast.makeText(getContext(), "Location permission denied", Toast.LENGTH_SHORT).show();
                }
            });

    private void requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        } else {
            startGNSSListener(); // 🟢 如果已经有权限，直接启动 GNSS If you already have permission, start GNSS directly
        }
    }

    private void startGNSSListener() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e("GNSS", "Permission not granted. Cannot start GNSS.");
            return;
        }

        // 🔥 确保 GNSS 传感器已被加入
        // 🔥 Make sure the GNSS sensor is added
        if (!sensorDataMap.containsKey("GNSS")) {
            sensorDataMap.put("GNSS", new SensorData("GNSS", "Waiting..."));
            sensorList.add(sensorDataMap.get("GNSS"));
            sensorAdapter.notifyDataSetChanged(); // 🔥 立刻刷新 UI Refresh the UI immediately
        }

        if (locationListener == null) {
            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(@NonNull Location location) {
//                    Log.d("GNSS", "📡 Location updated: " + location.getLatitude() + ", " + location.getLongitude());

                    if (isMeasuring) {
                        Log.d("GNSS", "🔥 GNSS isMeasuring == true"); // 🔥 确保 isMeasuring == true

                        if (!isAdded()) {
                            Log.w("SensorsFragment", "⚠️ Fragment is not attached. Skipping location update.");
                            return;
                        }

                        Activity activity = getActivity();
                        if (activity == null) {
                            Log.w("SensorsFragment", "⚠️ Activity is null. Skipping location update.");
                            return;
                        }

                        // ✅ 用 activity 而不是 requireActivity()，避免崩溃
                        // ✅ Use activity instead of requireActivity() to avoid crashes
                        activity.runOnUiThread(() -> {
                            if (sensorDataMap.containsKey("GNSS")) {
                                sensorDataMap.get("GNSS").setValue(
                                        String.format("Lat: %.5f  Lon: %.5f  Alt: %.2f",
                                                location.getLatitude(), location.getLongitude(), location.getAltitude())
                                );

                                // 🔥 触发 UI 更新
                                // 🔥 Trigger UI update
                                Log.d("GNSS", "🔥 GNSS UI 更新成功");
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

        LocationManager locationManager = (LocationManager) requireActivity().getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000, // **1秒更新一次** Update once every 1 second
                    1,    // **位移1米更新** **Update with 1 meter displacement**
                    locationListener
            );
            Log.d("GNSS", "🔥 GNSS Listening started!");
        } else {
            Log.e("GNSS", "❌ LocationManager is null. Cannot start GNSS.");
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
            startGNSSListener();  // **🔥 确保 GNSS 在 UI 里** **🔥 Make sure GNSS is in the UI**
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
