package com.openpositioning.PositionMe.sensors;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import com.openpositioning.PositionMe.Traj;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.PdrProcessing;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * SensorFusion (Final Fix for Filename)
 * Ensure startRecording uses the user-provided ID.
 */
public class SensorFusion implements SensorEventListener, Observer {

    private HashMap<Integer, Long> lastEventTimestamps = new HashMap<>();
    private HashMap<Integer, Integer> eventCounts = new HashMap<>();

    long maxReportLatencyNs = 0;
    private static final long LARGE_GAP_THRESHOLD_MS = 500;

    //region Static variables
    private static final SensorFusion sensorFusion = new SensorFusion();
    // IMU data sampling period: 10ms = 100Hz (high frequency for accurate trajectory recording)
    private static final long TIME_CONST = 10;
    public static final float FILTER_COEFFICIENT = 0.96f;
    private static final float ALPHA = 0.8f;
    private static final String WIFI_FINGERPRINT= "wf";
    //endregion

    //region Instance variables
    private PowerManager.WakeLock wakeLock;
    private Context appContext;
    private SharedPreferences settings;

    // Movement sensor instances
    private MovementSensor accelerometerSensor;
    private MovementSensor barometerSensor;
    private MovementSensor gyroscopeSensor;
    private MovementSensor lightSensor;
    private MovementSensor proximitySensor;
    private MovementSensor magnetometerSensor;
    private MovementSensor stepDetectionSensor;
    private MovementSensor rotationSensor;
    private MovementSensor gravitySensor;
    private MovementSensor linearAccelerationSensor;

    // Bluetooth components
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private ScanCallback bleScanCallback;

    private WifiDataProcessor wifiProcessor;
    private GNSSDataProcessor gnssProcessor;
    private final LocationListener locationListener;

    private ServerCommunications serverCommunications;

    // Protobuf Builder
    private Traj.Trajectory.Builder trajectory;

    private boolean saveRecording;
    private float filter_coefficient;

    private long absoluteStartTime;
    private long bootTime;
    long lastStepTime = 0;
    private Timer storeTrajectoryTimer;
    private int counter;
    private int secondCounter;

    // Marker tracking for timestamp markers
    private int markerCount = 0;
    private float currentHeading = 0.0f;  // Current bearing/heading in degrees

    // Sensor values
    private float[] acceleration = new float[3];
    private float[] filteredAcc = new float[3];
    private float[] gravity = new float[3];
    private float[] magneticField = new float[3];
    private float[] angularVelocity = new float[3];
    private float[] orientation = new float[3];
    private float[] rotation = new float[4]; // x, y, z, w
    private float pressure;
    private float light;
    private float proximity;
    private int stepCounter;

    // Recording statistics
    private int gnssRecordCount = 0;
    private int pdrRecordCount = 0;

    // Derived values
    private float elevation;
    private boolean elevator;
    private float latitude;
    private float longitude;
    private float altitude_val;
    private float gnssAccuracy = Float.MAX_VALUE;  // GNSS accuracy in meters
    private float[] startLocation = new float[2];

    private List<Wifi> wifiList = new ArrayList<>();
    private List<BleDevice> bleList = new ArrayList<>();
    private List<Double> accelMagnitude = new ArrayList<>();

    private PdrProcessing pdrProcessing;
    private PathView pathView;
    private WiFiPositioning wiFiPositioning;

    // Low-pass filter variables
    private static final int SMOOTH_WINDOW = 10;
    private float[] accWindow = new float[SMOOTH_WINDOW];
    private int accWindowIndex = 0;

    // Weinberg Step Length variables
    private float currentMaxAcc = 0;
    private float currentMinAcc = 0;
    private static final long MIN_STEP_DELAY_MS = 350; // Reduced to 350ms for faster steps
    private static final float STEP_THRESHOLD = 2.0f; // Reduced to 2.0f to detect smaller movements

    //region Initialisation
    private SensorFusion() {
        this.locationListener= new myLocationListener();
        this.storeTrajectoryTimer = new Timer();
        this.counter = 0;
        this.secondCounter = 0;
        this.stepCounter = 0;
        this.elevation = 0;
        this.elevator = false;
        this.rotation[3] = 1.0f;
        this.wifiList = new ArrayList<>();
        this.bleList = new ArrayList<>();
    }

    public static SensorFusion getInstance() {
        return sensorFusion;
    }

    public void setContext(Context context) {
        this.appContext = context.getApplicationContext();

        this.accelerometerSensor = new MovementSensor(context, Sensor.TYPE_ACCELEROMETER);
        this.barometerSensor = new MovementSensor(context, Sensor.TYPE_PRESSURE);
        this.gyroscopeSensor = new MovementSensor(context, Sensor.TYPE_GYROSCOPE);
        this.lightSensor = new MovementSensor(context, Sensor.TYPE_LIGHT);
        this.proximitySensor = new MovementSensor(context, Sensor.TYPE_PROXIMITY);
        this.magnetometerSensor = new MovementSensor(context, Sensor.TYPE_MAGNETIC_FIELD);
        this.stepDetectionSensor = new MovementSensor(context, Sensor.TYPE_STEP_DETECTOR);
        this.rotationSensor = new MovementSensor(context, Sensor.TYPE_ROTATION_VECTOR);
        this.gravitySensor = new MovementSensor(context, Sensor.TYPE_GRAVITY);
        this.linearAccelerationSensor = new MovementSensor(context, Sensor.TYPE_LINEAR_ACCELERATION);

        this.wifiProcessor = new WifiDataProcessor(context);
        wifiProcessor.registerObserver(this);
        this.gnssProcessor = new GNSSDataProcessor(context, locationListener);

        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            this.bluetoothAdapter = bluetoothManager.getAdapter();
        }

        this.serverCommunications = new ServerCommunications(context);
        this.absoluteStartTime = System.currentTimeMillis();
        this.bootTime = SystemClock.uptimeMillis();
        this.saveRecording = false;

        this.accelMagnitude = new ArrayList<>();
        this.pdrProcessing = new PdrProcessing(context);
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.pathView = new PathView(context, null);
        this.wiFiPositioning = new WiFiPositioning(context);

        if(settings.getBoolean("overwrite_constants", false)) {
            this.filter_coefficient = Float.parseFloat(settings.getString("accel_filter", "0.96"));
        } else {
            this.filter_coefficient = FILTER_COEFFICIENT;
        }

        PowerManager powerManager = (PowerManager) this.appContext.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PositionMe::WakeLock");

        // Start BLE scanning for UI display
        startBleScan();
    }
    //endregion

    //region Sensor processing
    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        long currentTime = System.currentTimeMillis();
        int sensorType = sensorEvent.sensor.getType();

        lastEventTimestamps.put(sensorType, currentTime);
        eventCounts.put(sensorType, eventCounts.getOrDefault(sensorType, 0) + 1);

        switch (sensorType) {
            case Sensor.TYPE_ACCELEROMETER:
                System.arraycopy(sensorEvent.values, 0, acceleration, 0, 3);

                // Enhanced PDR Logic (Low-pass + Weinberg)
                if (saveRecording) {
                    updateEnhancedPDR(acceleration[0], acceleration[1], acceleration[2]);
                }
                break;

            case Sensor.TYPE_PRESSURE:
                pressure = (1 - ALPHA) * pressure + ALPHA * sensorEvent.values[0];
                // Update elevation regardless of recording state (needed for AutoFloor)
                this.elevation = pdrProcessing.updateElevation(
                        SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure)
                );
                break;

            case Sensor.TYPE_GYROSCOPE:
                System.arraycopy(sensorEvent.values, 0, angularVelocity, 0, 3);
                break;

            case Sensor.TYPE_LINEAR_ACCELERATION:
                System.arraycopy(sensorEvent.values, 0, filteredAcc, 0, 3);
                double accelMagFiltered = Math.sqrt(
                        filteredAcc[0]*filteredAcc[0] + filteredAcc[1]*filteredAcc[1] + filteredAcc[2]*filteredAcc[2]
                );
                this.accelMagnitude.add(accelMagFiltered);
                elevator = pdrProcessing.estimateElevator(gravity, filteredAcc);
                break;

            case Sensor.TYPE_GRAVITY:
                System.arraycopy(sensorEvent.values, 0, gravity, 0, 3);
                elevator = pdrProcessing.estimateElevator(gravity, filteredAcc);
                break;

            case Sensor.TYPE_LIGHT:
                light = sensorEvent.values[0];
                break;

            case Sensor.TYPE_PROXIMITY:
                proximity = sensorEvent.values[0];
                break;

            case Sensor.TYPE_MAGNETIC_FIELD:
                System.arraycopy(sensorEvent.values, 0, magneticField, 0, 3);
                break;

            case Sensor.TYPE_ROTATION_VECTOR:
                if (sensorEvent.values.length >= 4) {
                    System.arraycopy(sensorEvent.values, 0, rotation, 0, 4);
                } else {
                    System.arraycopy(sensorEvent.values, 0, rotation, 0, 3);
                    rotation[3] = 1.0f;
                }
                float[] rotationVectorDCM = new float[9];
                SensorManager.getRotationMatrixFromVector(rotationVectorDCM, this.rotation);
                SensorManager.getOrientation(rotationVectorDCM, this.orientation);
                break;

            case Sensor.TYPE_STEP_DETECTOR:
                // Keep original logic as backup or if manual step detector is preferred
                long stepTime = SystemClock.uptimeMillis() - bootTime;

                if (currentTime - lastStepTime < 20) {
                    break;
                } else {
                    // Original logic kept but potentially overridden by manual PDR update in Accelerometer
                    // If you want to use ONLY the manual enhanced PDR, comment out this block or control it with a flag.
                    // For now, we keep it to support devices with good hardware step detectors.
                    /* lastStepTime = currentTime;
                    float[] newCords = this.pdrProcessing.updatePdr(
                            stepTime,
                            this.accelMagnitude,
                            this.orientation[0]
                    );
                    this.accelMagnitude.clear();

                    if (saveRecording && trajectory != null) {
                        this.pathView.drawTrajectory(newCords);
                        stepCounter++;
                        trajectory.addPdrData(Traj.RelativePosition.newBuilder()
                                .setRelativeTimestamp(stepTime)
                                .setX(newCords[0])
                                .setY(newCords[1])
                                .build());

                        pdrRecordCount++;
                    }
                    */
                    break;
                }
        }
    }

    // Low-pass filter for acceleration magnitude
    private float getSmoothedMagnitude(float rawMagnitude) {
        accWindow[accWindowIndex] = rawMagnitude;
        accWindowIndex = (accWindowIndex + 1) % SMOOTH_WINDOW;

        float sum = 0;
        for (float v : accWindow) sum += v;
        return sum / SMOOTH_WINDOW;
    }

    // Weinberg Stride Length Estimation
    private float calculateWeinbergStride(float aMax, float aMin) {
        float K = 0.45f; // Calibration constant
        double amplitude = aMax - aMin;
        if (amplitude < 0) amplitude = 0;
        return (float) (K * Math.pow(amplitude, 0.25));
    }

    // Enhanced PDR Update Logic
    private void updateEnhancedPDR(float x, float y, float z) {
        // Calculate magnitude (minus gravity)
        float rawMag = (float) Math.sqrt(x*x + y*y + z*z) - 9.81f;

        // Low-pass filter
        float smoothMag = getSmoothedMagnitude(rawMag);

        // Track peaks for Weinberg
        if (smoothMag > currentMaxAcc) currentMaxAcc = smoothMag;
        if (smoothMag < currentMinAcc) currentMinAcc = smoothMag;

        // Step detection
        long currentTime = System.currentTimeMillis();
        if (smoothMag > STEP_THRESHOLD && (currentTime - lastStepTime) > MIN_STEP_DELAY_MS) {

            // Step detected!

            // Calculate dynamic stride
            float dynamicStride = calculateWeinbergStride(currentMaxAcc, currentMinAcc);

            // Override stride if manual setting is preferred/enforced in PdrProcessing
            // But here we calculate a "better" dynamic one.
            // We can feed this into pdrProcessing if we modify it, or apply it directly.

            // For integration with existing system, let's update PdrProcessing with this custom stride event
            // Or calculate coordinates directly here. Let's do direct calc to ensure the fix works.

            float currentHeading = orientation[0]; // Radians

            // Manually update PDR state in PdrProcessing (requires PdrProcessing to allow external updates or we do it here)
            // Since PdrProcessing is internal, we will simulate the update call but with our dynamic stride.
            // Actually, PdrProcessing.updatePdr calculates stride internally.
            // To force our Weinberg stride, we might need to modify PdrProcessing or set a temporary flag.

            // Simpler approach: Use the calculated stride to update position
            float dx = (float) (dynamicStride * Math.sin(currentHeading));
            float dy = (float) (dynamicStride * Math.cos(currentHeading));

            // We need to update the accumulated PDR movement in pdrProcessing so UI gets it
            // Assuming we added a method `addManualMovement(dx, dy)` to PdrProcessing, or we rely on pdrProcessing's existing logic.
            // If we cannot modify PdrProcessing, we must rely on its updatePdr which uses pre-defined stride estimation.

            // However, since the goal is to IMPROVE it, let's assume we use the existing updatePdr
            // but we trigger it here based on our BETTER step detection.

            long stepTime = SystemClock.uptimeMillis() - bootTime;
            lastStepTime = currentTime;

            // Pass empty mag list as we handled magnitude logic here, or pass current to let it do its thing?
            // If we pass empty, it might use default stride.
            // Let's rely on PdrProcessing's updatePdr for coordinate integration but trigger it with our timing.
            float[] newCords = this.pdrProcessing.updatePdrWithStride(
                    dynamicStride,
                    currentHeading
            );

            // Reset peaks
            currentMaxAcc = 0;
            currentMinAcc = 0;

            if (saveRecording && trajectory != null) {
                this.pathView.drawTrajectory(newCords);
                stepCounter++;
                trajectory.addPdrData(Traj.RelativePosition.newBuilder()
                        .setRelativeTimestamp(stepTime)
                        .setX(newCords[0])
                        .setY(newCords[1])
                        .build());

                pdrRecordCount++;
                Log.d("Recording", "Enhanced PDR recorded: count=" + pdrRecordCount +
                        ", steps=" + stepCounter);
            }
        }
    }

    class myLocationListener implements LocationListener{
        @Override
        public void onLocationChanged(@NonNull Location location) {
            latitude = (float) location.getLatitude();
            longitude = (float) location.getLongitude();
            altitude_val = (float) location.getAltitude();
            gnssAccuracy = location.getAccuracy();  // Store accuracy for fusion

            if(saveRecording && trajectory != null) {
                long relativeTime = SystemClock.uptimeMillis() - bootTime;

                Traj.GNSSPosition gnssPosition = Traj.GNSSPosition.newBuilder()
                        .setLatitude(location.getLatitude())
                        .setLongitude(location.getLongitude())
                        .setAltitude(location.getAltitude())
                        .setRelativeTimestamp(relativeTime)
                        .build();

                trajectory.addGnssData(Traj.GNSSReading.newBuilder()
                        .setPosition(gnssPosition)
                        .setAccuracy(location.getAccuracy())
                        .setSpeed(location.getSpeed())
                        .setBearing(location.getBearing())
                        .setProvider(location.getProvider() != null ? location.getProvider() : "unknown")
                        .build());

                gnssRecordCount++;
                Log.d("Recording", "GNSS recorded: count=" + gnssRecordCount +
                        ", lat=" + location.getLatitude() +
                        ", lon=" + location.getLongitude() +
                        ", accuracy=" + location.getAccuracy() + "m");
            }
        }
    }

    @Override
    public void update(Object[] wifiList) {
        this.wifiList = Stream.of(wifiList).map(o -> (Wifi) o).collect(Collectors.toList());

        if(this.saveRecording && trajectory != null) {
            long relativeTime = SystemClock.uptimeMillis() - bootTime;

            Traj.Fingerprint.Builder fingerprintBuilder = Traj.Fingerprint.newBuilder()
                    .setRelativeTimestamp(relativeTime);

            for (Wifi data : this.wifiList) {
                fingerprintBuilder.addRfScans(Traj.RFScan.newBuilder()
                        .setRelativeTimestamp(relativeTime)
                        .setMac(data.getBssid())
                        .setRssi(data.getLevel())
                        .build());
            }
            this.trajectory.addWifiFingerprints(fingerprintBuilder.build());
        }
        createWifiPositioningRequest();
    }

    private void createWifiPositioningRequest(){
        try {
            JSONObject wifiAccessPoints=new JSONObject();
            for (Wifi data : this.wifiList){
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
            this.wiFiPositioning.request(wifiFingerPrint);
        } catch (JSONException e) {
            Log.e("jsonErrors","Error creating json object"+e.toString());
        }
    }

    public LatLng getLatLngWifiPositioning(){return this.wiFiPositioning.getWifiLocation();}

    public int getWifiFloor(){
        return this.wiFiPositioning.getFloor();
    }

    //region Helper Methods
    private Traj.Vector3 toVector3(float[] values) {
        return Traj.Vector3.newBuilder()
                .setX(values[0])
                .setY(values[1])
                .setZ(values[2])
                .build();
    }

    private Traj.Quaternion toQuaternion(float[] values) {
        return Traj.Quaternion.newBuilder()
                .setX(values[0])
                .setY(values[1])
                .setZ(values[2])
                .setW(values.length > 3 ? values[3] : 1.0f)
                .build();
    }

    private Traj.SensorInfo createSensorInfo(MovementSensor sensor) {
        if (sensor == null || sensor.sensorInfo == null) return Traj.SensorInfo.getDefaultInstance();
        return Traj.SensorInfo.newBuilder()
                .setName(sensor.sensorInfo.getName())
                .setVendor(sensor.sensorInfo.getVendor())
                .setResolution(sensor.sensorInfo.getResolution())
                .setPower(sensor.sensorInfo.getPower())
                .setVersion(sensor.sensorInfo.getVersion())
                .setType(sensor.sensorInfo.getType())
                .build();
    }
    //endregion

    public void addMarker() {
        if (saveRecording && trajectory != null) {
            long relativeTime = SystemClock.uptimeMillis() - bootTime;

            // Create a timestamped marker with index
            Traj.TimestampMarker marker = Traj.TimestampMarker.newBuilder()
                    .setRelativeTimestamp(relativeTime)
                    .setLatitude(latitude)
                    .setLongitude(longitude)
                    .setAltitude(altitude_val)
                    .setMarkerIndex(markerCount)
                    .setMarkerName("Marker_" + markerCount)  // Auto-generated name
                    .build();

            trajectory.addTestPoints(marker);
            markerCount++;
            Log.d("SensorFusion", "Marker #" + (markerCount - 1) + " added at: " + relativeTime + "ms");
        }
    }

    /**
     * Set the venue/building name for this trajectory
     */
    public void setVenueName(String venueName) {
        if (trajectory != null && venueName != null && !venueName.isEmpty()) {
            trajectory.setVenueName(venueName);
            Log.d("SensorFusion", "Venue name set to: " + venueName);
        }
    }

    /**
     * Set the building ID for linking to indoor map APIs
     */
    public void setBuildingId(String buildingId) {
        if (trajectory != null && buildingId != null && !buildingId.isEmpty()) {
            trajectory.setBuildingId(buildingId);
            Log.d("SensorFusion", "Building ID set to: " + buildingId);
        }
    }

    /**
     * Update current heading/bearing (in degrees 0-360)
     */
    public void updateHeading(float heading) {
        this.currentHeading = heading % 360.0f;  // Normalize to 0-360
        if (currentHeading < 0) {
            currentHeading += 360.0f;
        }
    }

    /**
     * Get current heading
     */
    public float getCurrentHeading() {
        return currentHeading;
    }

    /**
     * Get marker count
     */
    public int getMarkerCount() {
        return markerCount;
    }

    //region Start/Stop

    public void resumeListening() {
        if (accelerometerSensor.sensor != null) accelerometerSensor.sensorManager.registerListener(this, accelerometerSensor.sensor, 10000);
        if (linearAccelerationSensor.sensor != null) accelerometerSensor.sensorManager.registerListener(this, linearAccelerationSensor.sensor, 10000);
        if (gravitySensor.sensor != null) accelerometerSensor.sensorManager.registerListener(this, gravitySensor.sensor, 10000);
        if (barometerSensor.sensor != null) barometerSensor.sensorManager.registerListener(this, barometerSensor.sensor, (int) 1e6);
        if (gyroscopeSensor.sensor != null) gyroscopeSensor.sensorManager.registerListener(this, gyroscopeSensor.sensor, 10000);
        if (lightSensor.sensor != null) lightSensor.sensorManager.registerListener(this, lightSensor.sensor, (int) 1e6);
        if (proximitySensor.sensor != null) proximitySensor.sensorManager.registerListener(this, proximitySensor.sensor, (int) 1e6);
        if (magnetometerSensor.sensor != null) magnetometerSensor.sensorManager.registerListener(this, magnetometerSensor.sensor, 10000);
        if (stepDetectionSensor.sensor != null) stepDetectionSensor.sensorManager.registerListener(this, stepDetectionSensor.sensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (rotationSensor.sensor != null) rotationSensor.sensorManager.registerListener(this, rotationSensor.sensor, (int) 1e6);

        wifiProcessor.startListening();
        gnssProcessor.startLocationUpdates();
    }

    public void stopListening() {
        if(!saveRecording) {
            accelerometerSensor.sensorManager.unregisterListener(this);
            barometerSensor.sensorManager.unregisterListener(this);
            gyroscopeSensor.sensorManager.unregisterListener(this);
            lightSensor.sensorManager.unregisterListener(this);
            proximitySensor.sensorManager.unregisterListener(this);
            magnetometerSensor.sensorManager.unregisterListener(this);
            stepDetectionSensor.sensorManager.unregisterListener(this);
            rotationSensor.sensorManager.unregisterListener(this);
            linearAccelerationSensor.sensorManager.unregisterListener(this);
            gravitySensor.sensorManager.unregisterListener(this);
            try {
                this.wifiProcessor.stopListening();
            } catch (Exception e) {
                System.err.println("Wifi resumed before existing");
            }
            this.gnssProcessor.stopUpdating();
        }
    }

    private void startBleScan() {
        // Prevent multiple scan starts
        if (bluetoothLeScanner != null && bleScanCallback != null) {
            android.util.Log.d("SensorFusion", "BLE scan already running");
            return; // Already scanning
        }

        if (bluetoothAdapter == null) {
            android.util.Log.w("SensorFusion", "Bluetooth adapter is null");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            android.util.Log.w("SensorFusion", "Bluetooth is not enabled");
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            android.util.Log.w("SensorFusion", "Bluetooth LE scanner is null");
            return;
        }

        bleScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);

                // Add to display list for UI
                String macAddress = result.getDevice().getAddress();
                String name = null;
                try {
                    if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        name = result.getDevice().getName();
                    }
                } catch (SecurityException ignored) { }

                int rssi = result.getRssi();

                // Update existing device or add new one
                synchronized (bleList) {
                    boolean found = false;
                    for (BleDevice device : bleList) {
                        if (device.getMacAddress().equals(macAddress)) {
                            device.setRssi(rssi);
                            if (name != null) {
                                device.setName(name);
                            }
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        bleList.add(new BleDevice(macAddress, name, rssi));
                        android.util.Log.d("SensorFusion", "New BLE device added: " + macAddress);
                    }
                }

                // Save to trajectory if recording
                if (saveRecording && trajectory != null) {
                    Traj.BleData.Builder bleBuilder = Traj.BleData.newBuilder()
                            .setMacAddress(macAddress)
                            .setTxPowerLevel(result.getTxPower());

                    if (name != null) {
                        bleBuilder.setName(name);
                    }

                    trajectory.addBleData(bleBuilder.build());
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                super.onScanFailed(errorCode);
                android.util.Log.e("SensorFusion", "BLE scan failed with error code: " + errorCode);
            }
        };

        try {
            if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothLeScanner.startScan(bleScanCallback);
                android.util.Log.d("SensorFusion", "BLE scan started successfully");
            } else {
                android.util.Log.w("SensorFusion", "BLUETOOTH_SCAN permission not granted");
            }
        } catch (SecurityException e) {
            android.util.Log.e("SensorFusion", "SecurityException starting BLE scan: " + e.getMessage());
        }
    }

    private void stopBleScan() {
        if (bluetoothLeScanner != null && bleScanCallback != null) {
            try {
                if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothLeScanner.stopScan(bleScanCallback);
                    android.util.Log.d("SensorFusion", "BLE scan stopped");
                }
            } catch (SecurityException e) {
                android.util.Log.e("SensorFusion", "Error stopping BLE scan: " + e.getMessage());
            }
        }
    }

    /**
     * Starts recording with the specified trajectory ID.
     */
    public void startRecording(String trajectoryId) {
        if (wakeLock == null) {
            PowerManager powerManager = (PowerManager) this.appContext.getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PositionMe::WakeLock");
        }
        wakeLock.acquire(31 * 60 * 1000L);

        // Safety check: provide default if ID is empty
        if (trajectoryId == null || trajectoryId.isEmpty()) {
            trajectoryId = "UnknownTraj_" + System.currentTimeMillis();
        }

        // Log to confirm received ID is correct
        Log.d("SensorFusion", "Start recording with ID: " + trajectoryId);

        this.saveRecording = true;
        this.stepCounter = 0;
        this.gnssRecordCount = 0;
        this.pdrRecordCount = 0;
        this.markerCount = 0;  // Reset marker counter
        this.currentHeading = orientation[2];  // Set initial heading from magnetometer
        this.absoluteStartTime = System.currentTimeMillis();
        this.bootTime = SystemClock.uptimeMillis();

        // Build trajectory with provided ID
        this.trajectory = Traj.Trajectory.newBuilder()
                .setAndroidVersion(Build.VERSION.RELEASE)
                .setStartTimestamp(absoluteStartTime)
                .setTrajectoryId(trajectoryId) // Use parameter variable
                .setInitialHeading(currentHeading)  // Set initial bearing
                .setAccelerometerInfo(createSensorInfo(accelerometerSensor))
                .setGyroscopeInfo(createSensorInfo(gyroscopeSensor))
                .setMagnetometerInfo(createSensorInfo(magnetometerSensor))
                .setBarometerInfo(createSensorInfo(barometerSensor))
                .setLightSensorInfo(createSensorInfo(lightSensor));

        // Use manual start location if set, otherwise use device GNSS
        float initLat = latitude;
        float initLon = longitude;
        if (startLocation != null && startLocation[0] != 0 && startLocation[1] != 0) {
            initLat = startLocation[0];
            initLon = startLocation[1];
            Log.d("SensorFusion", "Using Manual Start Location: " + initLat + ", " + initLon);
        } else {
            Log.d("SensorFusion", "Using Device GNSS Location: " + initLat + ", " + initLon);
        }

        Traj.GNSSPosition initialPos = Traj.GNSSPosition.newBuilder()
                .setLatitude(initLat)
                .setLongitude(initLon)
                .setAltitude(altitude_val)
                .setRelativeTimestamp(0)
                .build();
        this.trajectory.setInitialPosition(initialPos);

        // BLE scan is already running from setContext(), no need to start again

        this.storeTrajectoryTimer = new Timer();
        this.storeTrajectoryTimer.schedule(new storeDataInTrajectory(), 0, TIME_CONST);
        this.pdrProcessing.resetPDR();

        // Reset Low-pass filter and peaks for new recording
        accWindowIndex = 0;
        for(int i=0; i<SMOOTH_WINDOW; i++) accWindow[i] = 0;
        currentMaxAcc = 0;
        currentMinAcc = 0;

        if(settings.getBoolean("overwrite_constants", false)) {
            this.filter_coefficient = Float.parseFloat(settings.getString("accel_filter", "0.96"));
        } else {
            this.filter_coefficient = FILTER_COEFFICIENT;
        }
    }

    public void stopRecording() {
        if(this.saveRecording) {
            this.saveRecording = false;
            storeTrajectoryTimer.cancel();
            // Note: Keep BLE scanning running for UI display

            // Log recording statistics
            Log.d("Recording", "========== RECORDING STOPPED ==========");
            Log.d("Recording", "  GNSS points recorded: " + gnssRecordCount);
            Log.d("Recording", "  PDR points recorded: " + pdrRecordCount);
            Log.d("Recording", "  Steps counted: " + stepCounter);

            if (gnssRecordCount == 0) {
                Log.e("Recording", "  !!! WARNING: No GNSS data recorded !!!");
                Log.e("Recording", "  Check if FusedLocationProvider is providing updates");
            }

            if (pdrRecordCount == 0) {
                Log.e("Recording", "  !!! WARNING: No PDR data recorded !!!");
                Log.e("Recording", "  Check if step detector sensor is working");
            }

            Log.d("Recording", "========================================");
        }
        if(wakeLock != null && wakeLock.isHeld()) {
            this.wakeLock.release();
        }
    }

    //endregion

    //region Trajectory object

    public void sendTrajectoryToCloud() {
        if (trajectory != null) {
            // Read campaign from SharedPreferences, default to empty string
            String campaign = settings.getString("current_campaign", "");
            Traj.Trajectory sentTrajectory = trajectory.build();

            // Log detailed trajectory statistics BEFORE sending
            Log.d("SensorFusion", "========== TRAJECTORY VALIDATION ==========");
            Log.d("SensorFusion", "Trajectory ID: " + sentTrajectory.getTrajectoryId());
            Log.d("SensorFusion", "IMU Data count: " + sentTrajectory.getImuDataCount());
            Log.d("SensorFusion", "GNSS Data count: " + sentTrajectory.getGnssDataCount());
            Log.d("SensorFusion", "PDR Data count: " + sentTrajectory.getPdrDataCount());
            Log.d("SensorFusion", "Magnetometer Data count: " + sentTrajectory.getMagnetometerDataCount());
            Log.d("SensorFusion", "Pressure Data count: " + sentTrajectory.getPressureDataCount());
            Log.d("SensorFusion", "WiFi Fingerprints count: " + sentTrajectory.getWifiFingerprintsCount());
            Log.d("SensorFusion", "BLE Data count: " + sentTrajectory.getBleDataCount());
            Log.d("SensorFusion", "Test Points count: " + sentTrajectory.getTestPointsCount());

            if (sentTrajectory.getGnssDataCount() > 0) {
                Traj.GNSSReading firstGnss = sentTrajectory.getGnssData(0);
                Traj.GNSSReading lastGnss = sentTrajectory.getGnssData(sentTrajectory.getGnssDataCount() - 1);
                Log.d("SensorFusion", "First GNSS: lat=" + firstGnss.getPosition().getLatitude() +
                        ", lon=" + firstGnss.getPosition().getLongitude());
                Log.d("SensorFusion", "Last GNSS: lat=" + lastGnss.getPosition().getLatitude() +
                        ", lon=" + lastGnss.getPosition().getLongitude());
            } else {
                Log.e("SensorFusion", "WARNING: No GNSS data in trajectory!");
            }

            if (sentTrajectory.getPdrDataCount() > 0) {
                Traj.RelativePosition firstPdr = sentTrajectory.getPdrData(0);
                Traj.RelativePosition lastPdr = sentTrajectory.getPdrData(sentTrajectory.getPdrDataCount() - 1);
                Log.d("SensorFusion", "First PDR: x=" + firstPdr.getX() + ", y=" + firstPdr.getY());
                Log.d("SensorFusion", "Last PDR: x=" + lastPdr.getX() + ", y=" + lastPdr.getY());
            } else {
                Log.e("SensorFusion", "WARNING: No PDR data in trajectory!");
            }

            Log.d("SensorFusion", "Campaign: " + (campaign.isEmpty() ? "(none)" : campaign));
            Log.d("SensorFusion", "==========================================");

            this.serverCommunications.sendTrajectory(sentTrajectory, campaign);
        } else {
            Log.e("SensorFusion", "ERROR: trajectory is null, cannot send!");
        }
    }

    private class storeDataInTrajectory extends TimerTask {
        public void run() {
            if (trajectory == null) return;
            long relTime = SystemClock.uptimeMillis() - bootTime;

            trajectory.addImuData(Traj.IMUReading.newBuilder()
                    .setRelativeTimestamp(relTime)
                    .setAcc(toVector3(acceleration))
                    .setGyr(toVector3(angularVelocity))
                    .setRotationVector(toQuaternion(rotation))
                    .setStepCount(stepCounter)
                    .build());

            trajectory.addMagnetometerData(Traj.MagnetometerReading.newBuilder()
                    .setRelativeTimestamp(relTime)
                    .setMag(toVector3(magneticField))
                    .build());

            if (counter == 99) {
                counter = 0;

                if (barometerSensor.sensor != null) {
                    trajectory.addPressureData(Traj.BarometerReading.newBuilder()
                            .setPressure(pressure)
                            .setRelativeTimestamp(relTime)
                            .build());
                }

                if (lightSensor.sensor != null) {
                    trajectory.addLightData(Traj.LightReading.newBuilder()
                            .setLight(light)
                            .setRelativeTimestamp(relTime)
                            .build());
                }

                if(proximitySensor.sensor != null) {
                    trajectory.addProximityData(Traj.ProximityReading.newBuilder()
                            .setDistance(proximity)
                            .setRelativeTimestamp(relTime)
                            .build());
                }

                if (secondCounter == 4) {
                    secondCounter = 0;
                    Wifi currentWifi = wifiProcessor.getCurrentWifiData();
                    if (currentWifi != null) {
                        trajectory.addApsData(Traj.WiFiAPData.newBuilder()
                                .setMac(currentWifi.getBssid())
                                .setSsid(currentWifi.getSsid() != null ? currentWifi.getSsid() : "")
                                .setFrequency(currentWifi.getFrequency())
                                .build());
                    }
                }
                else {
                    secondCounter++;
                }
            }
            else {
                counter++;
            }
        }
    }

    //endregion

    //region Getters/Setters

    public Map<SensorTypes, float[]> getSensorValueMap() {
        Map<SensorTypes, float[]> sensorValueMap = new HashMap<>();

        sensorValueMap.put(SensorTypes.ACCELEROMETER, acceleration);
        sensorValueMap.put(SensorTypes.GRAVITY, gravity);
        sensorValueMap.put(SensorTypes.MAGNETICFIELD, magneticField);
        sensorValueMap.put(SensorTypes.GYRO, angularVelocity);
        sensorValueMap.put(SensorTypes.LIGHT, new float[]{light});
        sensorValueMap.put(SensorTypes.PRESSURE, new float[]{pressure});
        sensorValueMap.put(SensorTypes.PROXIMITY, new float[]{proximity});
        sensorValueMap.put(SensorTypes.GNSSLATLONG, new float[]{latitude, longitude, gnssAccuracy});  // Added accuracy
        sensorValueMap.put(SensorTypes.PDR, pdrProcessing.getPDRMovement());
        sensorValueMap.put(SensorTypes.WIFI, new float[]{0f});
        sensorValueMap.put(SensorTypes.BLE, new float[]{0f});

        return sensorValueMap;
    }

    public List<Wifi> getWifiList() {
        return wifiList;
    }

    public List<BleDevice> getBleList() {
        synchronized (bleList) {
            return new ArrayList<>(bleList);
        }
    }

    public float[] getGNSSLatitude(boolean start) {
        float [] latLong = new float[2];
        if(!start) {
            latLong[0] = latitude;
            latLong[1] = longitude;
        }
        else{
            latLong = startLocation;
        }
        return latLong;
    }

    public void setStartGNSSLatitude(float[] startPosition){
        startLocation = startPosition;
    }

    public void redrawPath(float scalingRatio){
        pathView.redraw(scalingRatio);
    }

    public float passAverageStepLength(){
        return pdrProcessing.getAverageStepLength();
    }

    public float passOrientation(){
        return orientation[0];
    }

    public List<Object> getSensorInfos() {
        List<Object> infoList = new ArrayList<>();
        if (accelerometerSensor != null && accelerometerSensor.sensorInfo != null) infoList.add(accelerometerSensor.sensorInfo);
        if (gyroscopeSensor != null && gyroscopeSensor.sensorInfo != null) infoList.add(gyroscopeSensor.sensorInfo);
        if (magnetometerSensor != null && magnetometerSensor.sensorInfo != null) infoList.add(magnetometerSensor.sensorInfo);
        if (barometerSensor != null && barometerSensor.sensorInfo != null) infoList.add(barometerSensor.sensorInfo);
        if (lightSensor != null && lightSensor.sensorInfo != null) infoList.add(lightSensor.sensorInfo);
        if (proximitySensor != null && proximitySensor.sensorInfo != null) infoList.add(proximitySensor.sensorInfo);
        if (stepDetectionSensor != null && stepDetectionSensor.sensorInfo != null) infoList.add(stepDetectionSensor.sensorInfo);
        if (rotationSensor != null && rotationSensor.sensorInfo != null) infoList.add(rotationSensor.sensorInfo);
        if (gravitySensor != null && gravitySensor.sensorInfo != null) infoList.add(gravitySensor.sensorInfo);
        if (linearAccelerationSensor != null && linearAccelerationSensor.sensorInfo != null) infoList.add(linearAccelerationSensor.sensorInfo);

        // Add Bluetooth adapter info
        if (bluetoothAdapter != null) {
            String adapterName = "System";
            try {
                if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    String name = bluetoothAdapter.getName();
                    if (name != null && !name.isEmpty()) {
                        adapterName = name;
                    }
                }
            } catch (SecurityException e) {
                android.util.Log.d("SensorFusion", "Cannot get Bluetooth adapter name: " + e.getMessage());
            }

            SensorInfo bleInfo = new SensorInfo(
                    "Bluetooth LE Scanner",
                    adapterName,
                    -1.0f,
                    0.0f,
                    0,
                    -1
            );
            infoList.add(bleInfo);
        }

        return infoList;
    }

    public void registerForServerUpdate(Observer observer) {
        serverCommunications.registerObserver(observer);
    }

    public float getElevation() { return this.elevation; }
    public boolean getElevator() { return this.elevator; }

    public int getHoldMode(){
        int proximityThreshold = 1, lightThreshold = 100;
        if(proximity<proximityThreshold && light>lightThreshold) return 1;
        else return 0;
    }
    //endregion

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {}

    public Traj.Trajectory.Builder getTrajectory() {
        return this.trajectory;
    }
    public void resetPDR() {
        if (pdrProcessing != null) {
            pdrProcessing.refreshSettings();
        }
    }
}