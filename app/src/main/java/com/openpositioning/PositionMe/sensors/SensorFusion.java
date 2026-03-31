package com.openpositioning.PositionMe.sensors;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import com.openpositioning.PositionMe.BuildConfig;
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
import android.view.Surface;
import android.view.WindowManager;
import com.openpositioning.PositionMe.Traj;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.BuildingPolygon;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.PdrProcessing;
import com.openpositioning.PositionMe.utils.CircularFloatBuffer;
import com.openpositioning.PositionMe.utils.GeometryUtils;
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

// Central sensor fusion pipeline for recording and live positioning.
// Responsibilities:
// Collect IMU, GNSS, WiFi and BLE streams.
// Maintain PDR state and fused position updates.
// Build trajectory protobuf payloads during recording.
// Expose live values for UI and replay modules.
public class SensorFusion implements SensorEventListener, Observer {

    private HashMap<Integer, Long> lastEventTimestamps = new HashMap<>();
    private HashMap<Integer, Integer> eventCounts = new HashMap<>();

    long maxReportLatencyNs = 0;
    private static final long LARGE_GAP_THRESHOLD_MS = 500;

    // region Static variables
    private static final SensorFusion sensorFusion = new SensorFusion();
    // IMU data sampling period for periodic trajectory snapshots (10ms = 100Hz).
    private static final long TIME_CONST = 10;
    public static final float FILTER_COEFFICIENT = 0.94f;
    private static final float ALPHA = 0.8f;
    private static final String WIFI_FINGERPRINT= "wf";
    private static final int BAROMETER_ALTITUDE_WINDOW = 2;
    private static final int GNSS_ALTITUDE_WINDOW = 1;
    private static final int MOTION_WINDOW = 12;
    private static final float STATIONARY_ACCEL_THRESHOLD = 0.18f;
    private static final float STATIONARY_SPEED_THRESHOLD = 0.6f;
    private static final float TRUSTED_VERTICAL_ACCURACY_METERS = 8f;
    private static final float ACCEPTABLE_VERTICAL_ACCURACY_METERS = 12f;
    private static final float ACCEPTABLE_HORIZONTAL_ACCURACY_METERS = 12f;
    private static final float MAX_ALTITUDE_OFFSET_JUMP_METERS = 18f;
    private static final float MIN_SEA_LEVEL_PRESSURE_HPA = 870f;
    private static final float MAX_SEA_LEVEL_PRESSURE_HPA = 1085f;
    private static final long STATIONARY_STEP_GAP_MS = 1000L;
    // Gains used while calibrating barometer altitude against GNSS altitude.
    private static final float SEA_LEVEL_GAIN_STATIONARY = 0.42f;
    private static final float SEA_LEVEL_GAIN_MOVING = 0.24f;
    private static final float OFFSET_CALIBRATION_GAIN_STATIONARY = 0.58f;
    private static final float OFFSET_CALIBRATION_GAIN_MOVING = 0.36f;
    private static final float ALTITUDE_SMOOTHING_GAIN_STATIONARY = 0.78f;
    private static final float ALTITUDE_SMOOTHING_GAIN_MOVING = 0.58f;
    private static final long ALTITUDE_FAST_CONVERGENCE_WINDOW_MS = 3000L;
    private static final float SEA_LEVEL_GAIN_FAST = 0.95f;
    private static final float OFFSET_CALIBRATION_GAIN_FAST = 0.97f;
    private static final float ALTITUDE_SMOOTHING_GAIN_FAST = 0.97f;
    private static final double WIFI_STABLE_DISTANCE_METERS = 3.0;
    private static final int WIFI_STABLE_CONFIRMATIONS_MOVING = 0;
    private static final int WIFI_STABLE_CONFIRMATIONS_STATIONARY = 3;
    private static final double WIFI_STATIONARY_FUSED_GATE_METERS = 4.0;
    private static final double WIFI_STATIONARY_ACCEPTED_GATE_METERS = 3.0;
    // Absolute altitude bands used by barometer-based floor estimation.
    private static final float FLOOR_BAND_B1_MAX = 128.5f;
    private static final float FLOOR_BAND_GF_MAX = 132.75f;
    private static final float FLOOR_BAND_F1_MAX = 137.5f;
    private static final float FLOOR_BAND_F2_MAX = 142.7f;
    private static final float FLOOR_BAND_HYSTERESIS_METERS = 0.6f;
    // endregion

    // region Instance variables
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
    private float displayHeading = 0.0f;  // Smoothed heading for UI arrow

    // Parameters for heading smoothing and disturbance handling.
    private static final float DISPLAY_HEADING_DEADBAND_DEG = 10f;
    private static final float DISPLAY_HEADING_MAX_STEP_DEG = 10.0f;
    private static final float DISPLAY_HEADING_ALPHA_NORMAL = 0.40f;
    private static final float DISPLAY_HEADING_ALPHA_DISTURBED = 0.16f;
    private static final float DISPLAY_HEADING_MOVE_FUSION_WEIGHT = 0.20f;
    private static final float DISPLAY_HEADING_MOVE_FUSION_WEIGHT_DISTURBED = 0.35f;
    private static final float HEADING_MOVE_MIN_DISTANCE_METERS = 0.12f;
    private static final float MAGNETIC_FIELD_MIN_UT = 20.0f;
    private static final float MAGNETIC_FIELD_MAX_UT = 60.0f;

    private boolean displayHeadingInitialized = false;
    private boolean hasLastPdrForHeading = false;
    private float lastPdrXForHeading = 0.0f;
    private float lastPdrYForHeading = 0.0f;
    private long lastGyroTimestampNs = 0L;
    private float gyroHeadingDeg = 0.0f;
    private boolean gyroHeadingInitialized = false;

    // Sensor values
    private float[] acceleration = new float[3];
    private float[] filteredAcc = new float[3];
    private float[] gravity = new float[3];
    private float[] magneticField = new float[3];

    private float[] angularVelocity = new float[3];
    private float[] orientation = new float[3];
    private float[] rotation = new float[4]; // x, y, z, w
    private final float[] remappedRotationMatrix = new float[9];
    private float pressure = Float.NaN;
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
    private float altitude_val = Float.NaN;
    private float gnssAccuracy = Float.MAX_VALUE;  // GNSS accuracy in meters
    private float gnssVerticalAccuracy = Float.MAX_VALUE;
    private float barometerAbsoluteAltitude = Float.NaN;
    private float smoothedBarometerAbsoluteAltitude = Float.NaN;
    private float smoothedGnssAltitude = Float.NaN;
    private float estimatedAbsoluteAltitude = Float.NaN;
    private float altitudeOffsetMeters = Float.NaN;
    private float seaLevelPressure = Float.NaN;
    private boolean hasAltitudeCalibration = false;
    private float latestLinearAccelerationMagnitude = 0f;
    private float latestSpeedMetersPerSecond = 0f;
    private int lastStableEstimatedFloor = Integer.MIN_VALUE;
    private boolean barometerAutoFloorEnabled = false;
    private float[] startLocation = new float[2];
    private LatLng lastWifiCandidateLocation = null;
    private LatLng lastAcceptedWifiLocation = null;
    private int stableWifiCandidateCount = 0;

    private List<Wifi> wifiList = new ArrayList<>();
    private List<BleDevice> bleList = new ArrayList<>();
    private List<Double> accelMagnitude = new ArrayList<>();

    private PdrProcessing pdrProcessing;
    private PathView pathView;
    private WiFiPositioning wiFiPositioning;
    private CircularFloatBuffer barometerAltitudeWindow;
    private CircularFloatBuffer gnssAltitudeWindow;
    private CircularFloatBuffer motionWindow;

    // Rolling window state for acceleration smoothing.
    private static final int SMOOTH_WINDOW = 20;
    private float[] accWindow = new float[SMOOTH_WINDOW];
    private int accWindowIndex = 0;

    // Dynamic stride estimation state (Weinberg method).
    private float currentMaxAcc = 0;
    private float currentMinAcc = Float.MAX_VALUE;
    private long lastEnhancedStepTimeMs = 0L;
    private static final long MIN_STEP_DELAY_MS = 300;
    private static final long STEP_DETECTOR_FALLBACK_GUARD_MS = 420;
    private static final float STEP_THRESHOLD = 1.5f;
    private static final float STEP_AMPLITUDE_THRESHOLD = 1.1f;
    private static final float MIN_DYNAMIC_STRIDE_METERS = 0.30f;
    private static final float MAX_DYNAMIC_STRIDE_METERS = 0.85f;

    // region Initialisation
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
        this.barometerAltitudeWindow = new CircularFloatBuffer(BAROMETER_ALTITUDE_WINDOW);
        this.gnssAltitudeWindow = new CircularFloatBuffer(GNSS_ALTITUDE_WINDOW);
        this.motionWindow = new CircularFloatBuffer(MOTION_WINDOW);
    }

    public static SensorFusion getInstance() {
        return sensorFusion;
    }

    public void setWifiApiAuthToken(String token) {
        if (wiFiPositioning != null) {
            wiFiPositioning.setApiAuthToken(token);
        }
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

        String openPositioningToken = settings.getString("openpositioning_api_token",
                BuildConfig.OPENPOSITIONING_API_KEY);
        this.wiFiPositioning.setApiAuthToken(openPositioningToken);

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
    // endregion

    // region Sensor processing
    // Handles sensor callbacks and updates fusion state in real time.
    // Data flow by sensor type:
    // Accelerometer/Linear Acceleration/Gravity: motion features for step and elevator logic.
    // Rotation Vector + Gyroscope + Magnetometer: heading estimation and smoothing.
    // Pressure: barometer altitude and floor estimation.
    // Step Detector: step-triggered PDR update and optional trajectory write.
    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        long currentTime = System.currentTimeMillis();
        int sensorType = sensorEvent.sensor.getType();

        lastEventTimestamps.put(sensorType, currentTime);
        eventCounts.put(sensorType, eventCounts.getOrDefault(sensorType, 0) + 1);

        switch (sensorType) {
            case Sensor.TYPE_ACCELEROMETER:
                System.arraycopy(sensorEvent.values, 0, acceleration, 0, 3);

                double accelMag = Math.sqrt(
                        acceleration[0] * acceleration[0]
                                + acceleration[1] * acceleration[1]
                                + acceleration[2] * acceleration[2]
                );
                this.accelMagnitude.add(accelMag);

                // Use accelerometer samples for stride-based PDR update.
                if (saveRecording) {
                    updateEnhancedPDR(acceleration[0], acceleration[1], acceleration[2]);
                }
                break;

            case Sensor.TYPE_PRESSURE:
                if (Float.isNaN(pressure)) {
                    pressure = sensorEvent.values[0];
                } else {
                    pressure = (1 - ALPHA) * pressure + ALPHA * sensorEvent.values[0];
                }
                updateBarometerAbsoluteAltitude();
                smoothedBarometerAbsoluteAltitude = smoothAltitudeSample(
                        barometerAltitudeWindow,
                        barometerAbsoluteAltitude
                );
                // Keep elevation state live so floor estimation works in non-recording mode too.
                this.elevation = pdrProcessing.updateElevation(
                        smoothedBarometerAbsoluteAltitude
                );
                updateAbsoluteAltitudeEstimate();
                break;

            case Sensor.TYPE_GYROSCOPE:
                System.arraycopy(sensorEvent.values, 0, angularVelocity, 0, 3);
                updateGyroHeading(sensorEvent);
                break;

            case Sensor.TYPE_LINEAR_ACCELERATION:
                System.arraycopy(sensorEvent.values, 0, filteredAcc, 0, 3);
                double accelMagFiltered = Math.sqrt(
                        filteredAcc[0]*filteredAcc[0] + filteredAcc[1]*filteredAcc[1] + filteredAcc[2]*filteredAcc[2]
                );
                latestLinearAccelerationMagnitude = (float) accelMagFiltered;
                motionWindow.putNewest(latestLinearAccelerationMagnitude);
                
                elevator = pdrProcessing.estimateElevator(gravity, filteredAcc);
                refreshFusionMotionState();
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
                applyDisplayCompensation(rotationVectorDCM, remappedRotationMatrix);
                SensorManager.getOrientation(remappedRotationMatrix, this.orientation);
                this.currentHeading = normalizeHeadingDegrees((float) Math.toDegrees(this.orientation[0]));
                updateDisplayHeading(this.currentHeading);
                break;

            case Sensor.TYPE_STEP_DETECTOR:
                long stepTime = SystemClock.uptimeMillis() - bootTime;

                if (saveRecording && (currentTime - lastEnhancedStepTimeMs) < STEP_DETECTOR_FALLBACK_GUARD_MS) {
                    break;
                }

                if (currentTime - lastStepTime < 220) {
                    break;
                }

                lastStepTime = currentTime;

                float currentRegularHeading = (float) Math.toRadians(this.currentHeading);
                float[] newCords = this.pdrProcessing.updatePdr(
                        stepTime,
                        this.accelMagnitude,
                        currentRegularHeading
                );
                updateDisplayHeadingWithMovement(newCords[0], newCords[1]);

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
                    Log.d("SensorFusion", "Step detector PDR recorded: count=" + pdrRecordCount + ", steps=" + stepCounter);
                }
                break;
        }
    }

    // Smooth acceleration magnitude with a fixed-size moving average window.
    private float getSmoothedMagnitude(float rawMagnitude) {
        accWindow[accWindowIndex] = rawMagnitude;
        accWindowIndex = (accWindowIndex + 1) % SMOOTH_WINDOW;

        float sum = 0;
        for (float v : accWindow) sum += v;
        return sum / SMOOTH_WINDOW;
    }

    // Compute stride length from peak-to-peak acceleration amplitude.
    private float calculateWeinbergStride(float aMax, float aMin) {
        float K = 0.40f;
        double amplitude = aMax - aMin;
        if (amplitude < 0) amplitude = 0;
        return (float) (K * Math.pow(amplitude, 0.25));
    }

    private float clampStride(float strideMeters) {
        return Math.max(MIN_DYNAMIC_STRIDE_METERS, Math.min(MAX_DYNAMIC_STRIDE_METERS, strideMeters));
    }

    private void updateEnhancedPDR(float x, float y, float z) {
        float rawMag = Math.abs((float) Math.sqrt(x*x + y*y + z*z) - 9.81f);

        // Suppress high-frequency jitter before threshold-based step detection.
        float smoothMag = getSmoothedMagnitude(rawMag);

        // Maintain local extrema for stride estimation once a step is detected.
        if (smoothMag > currentMaxAcc) currentMaxAcc = smoothMag;
        if (smoothMag < currentMinAcc) currentMinAcc = smoothMag;

        // Trigger a new step only if threshold and minimum step interval are both satisfied.
        long currentTime = System.currentTimeMillis();
        if (smoothMag > STEP_THRESHOLD && (currentTime - lastStepTime) > MIN_STEP_DELAY_MS) {
            float peakToPeak = (currentMinAcc == Float.MAX_VALUE) ? 0f : (currentMaxAcc - currentMinAcc);
            if (peakToPeak < STEP_AMPLITUDE_THRESHOLD) {
                return;
            }

            // Compute per-step stride length and feed it into PDR integration.
            float dynamicStride = clampStride(calculateWeinbergStride(currentMaxAcc, currentMinAcc));

            // Use current fused heading for forward projection of this step.
            float currentHeading = (float) Math.toRadians(this.currentHeading);

            long stepTime = SystemClock.uptimeMillis() - bootTime;
            lastStepTime = currentTime;
            lastEnhancedStepTimeMs = currentTime;

            float[] newCords = this.pdrProcessing.updatePdrWithStride(
                    dynamicStride,
                    currentHeading
            );
            updateDisplayHeadingWithMovement(newCords[0], newCords[1]);

            // Reset extrema for the next step cycle.
            currentMaxAcc = 0;
            currentMinAcc = Float.MAX_VALUE;

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
            gnssAccuracy = location.getAccuracy();
            latestSpeedMetersPerSecond = location.hasSpeed() ? location.getSpeed() : 0f;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
                gnssVerticalAccuracy = location.getVerticalAccuracyMeters();
            } else {
                gnssVerticalAccuracy = Math.max(gnssAccuracy * 1.5f, 10f);
            }

            recalibrateAbsoluteAltitude(location);

            // Fusion origin is initialized from GNSS only in outdoor context.
            if (!pdrProcessing.isFusionInitialized() && shouldUseGnssInitialization(latitude, longitude)) {
                pdrProcessing.initializeWithLocation(latitude, longitude);
                if (pdrProcessing.isFusionInitialized()) {
                    LatLng origin = pdrProcessing.getFusedLatLon();
                    setStartGNSSLatitude(new float[]{(float) origin.latitude, (float) origin.longitude});
                }
            }

            if (pdrProcessing.isFusionInitialized()) {
                pdrProcessing.processGnssLocation(latitude, longitude, gnssAccuracy);
            }
            refreshFusionMotionState();

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

    private void recalibrateAbsoluteAltitude(@NonNull Location location) {
        if (!location.hasAltitude() || Float.isNaN(pressure) || Float.isNaN(smoothedBarometerAbsoluteAltitude)) {
            return;
        }

        smoothedGnssAltitude = smoothAltitudeSample(gnssAltitudeWindow, (float) location.getAltitude());

        boolean goodVerticalFix = gnssVerticalAccuracy <= ACCEPTABLE_VERTICAL_ACCURACY_METERS;
        boolean acceptableHorizontalFix = gnssAccuracy <= ACCEPTABLE_HORIZONTAL_ACCURACY_METERS;
        if (!goodVerticalFix || !acceptableHorizontalFix) {
            return;
        }

        boolean stationary = isAltitudeCalibrationStationary();
        if (!stationary && gnssVerticalAccuracy > TRUSTED_VERTICAL_ACCURACY_METERS) {
            return;
        }

        float measuredSeaLevelPressure = estimateSeaLevelPressure(pressure, smoothedGnssAltitude);
        if (Float.isNaN(measuredSeaLevelPressure)) {
            return;
        }

        if (Float.isNaN(seaLevelPressure)) {
            seaLevelPressure = measuredSeaLevelPressure;
        } else {
            float seaLevelGain = stationary ? SEA_LEVEL_GAIN_STATIONARY : SEA_LEVEL_GAIN_MOVING;
            if (isInFastAltitudeConvergenceWindow()) {
                seaLevelGain = Math.max(seaLevelGain, SEA_LEVEL_GAIN_FAST);
            }
            seaLevelPressure = seaLevelPressure + seaLevelGain * (measuredSeaLevelPressure - seaLevelPressure);
        }

        updateBarometerAbsoluteAltitude();
        smoothedBarometerAbsoluteAltitude = smoothAltitudeSample(
                barometerAltitudeWindow,
                barometerAbsoluteAltitude
        );

        float measuredOffset = smoothedGnssAltitude - smoothedBarometerAbsoluteAltitude;
        if (hasAltitudeCalibration && Math.abs(measuredOffset - altitudeOffsetMeters) > MAX_ALTITUDE_OFFSET_JUMP_METERS && !stationary) {
            return;
        }

        if (!hasAltitudeCalibration || Float.isNaN(altitudeOffsetMeters)) {
            altitudeOffsetMeters = measuredOffset;
            hasAltitudeCalibration = true;
        } else {
            float calibrationGain = stationary ? OFFSET_CALIBRATION_GAIN_STATIONARY : OFFSET_CALIBRATION_GAIN_MOVING;
            if (isInFastAltitudeConvergenceWindow()) {
                calibrationGain = Math.max(calibrationGain, OFFSET_CALIBRATION_GAIN_FAST);
            }
            altitudeOffsetMeters = altitudeOffsetMeters + calibrationGain * (measuredOffset - altitudeOffsetMeters);
        }

        updateAbsoluteAltitudeEstimate();
    }

    private void updateBarometerAbsoluteAltitude() {
        if (Float.isNaN(pressure)) {
            return;
        }

        float referenceSeaLevelPressure = !Float.isNaN(seaLevelPressure)
                ? seaLevelPressure
                : SensorManager.PRESSURE_STANDARD_ATMOSPHERE;
        barometerAbsoluteAltitude = SensorManager.getAltitude(referenceSeaLevelPressure, pressure);
    }

    private float estimateSeaLevelPressure(float measuredPressure, float knownAltitudeMeters) {
        if (Float.isNaN(measuredPressure) || Float.isNaN(knownAltitudeMeters)) {
            return Float.NaN;
        }

        float normalizedAltitude = 1.0f - (knownAltitudeMeters / 44330.0f);
        if (normalizedAltitude <= 0f) {
            return Float.NaN;
        }

        float estimatedPressure = (float) (measuredPressure / Math.pow(normalizedAltitude, 5.255d));
        if (estimatedPressure < MIN_SEA_LEVEL_PRESSURE_HPA || estimatedPressure > MAX_SEA_LEVEL_PRESSURE_HPA) {
            return Float.NaN;
        }

        return estimatedPressure;
    }

    private void updateAbsoluteAltitudeEstimate() {
        float targetAltitude = Float.NaN;

        if (!Float.isNaN(smoothedBarometerAbsoluteAltitude) && hasAltitudeCalibration && !Float.isNaN(altitudeOffsetMeters)) {
            targetAltitude = smoothedBarometerAbsoluteAltitude + altitudeOffsetMeters;
        } else if (!Float.isNaN(smoothedGnssAltitude)) {
            targetAltitude = smoothedGnssAltitude;
        } else if (!Float.isNaN(altitude_val)) {
            targetAltitude = altitude_val;
        }

        if (Float.isNaN(targetAltitude)) {
            return;
        }

        if (Float.isNaN(estimatedAbsoluteAltitude)) {
            estimatedAbsoluteAltitude = targetAltitude;
        } else {
            float smoothingGain = isAltitudeCalibrationStationary()
                    ? ALTITUDE_SMOOTHING_GAIN_STATIONARY
                    : ALTITUDE_SMOOTHING_GAIN_MOVING;
            if (isInFastAltitudeConvergenceWindow()) {
                smoothingGain = Math.max(smoothingGain, ALTITUDE_SMOOTHING_GAIN_FAST);
            }
            estimatedAbsoluteAltitude = estimatedAbsoluteAltitude + smoothingGain * (targetAltitude - estimatedAbsoluteAltitude);
        }
    }

    private boolean isInFastAltitudeConvergenceWindow() {
        return (SystemClock.uptimeMillis() - bootTime) <= ALTITUDE_FAST_CONVERGENCE_WINDOW_MS;
    }

    private float smoothAltitudeSample(@NonNull CircularFloatBuffer buffer, float sample) {
        buffer.putNewest(sample);
        List<Float> samples = buffer.getListCopy();
        if (samples == null || samples.isEmpty()) {
            return sample;
        }

        float sum = 0f;
        for (float value : samples) {
            sum += value;
        }
        return sum / samples.size();
    }

    private boolean isAltitudeCalibrationStationary() {
        List<Float> motionSamples = motionWindow.getListCopy();
        float averageMotion = latestLinearAccelerationMagnitude;
        if (motionSamples != null && !motionSamples.isEmpty()) {
            float sum = 0f;
            for (float sample : motionSamples) {
                sum += Math.abs(sample);
            }
            averageMotion = sum / motionSamples.size();
        }

        long timeSinceLastStepMs = lastStepTime == 0 ? Long.MAX_VALUE : (System.currentTimeMillis() - lastStepTime);
        boolean lowMotion = averageMotion < STATIONARY_ACCEL_THRESHOLD;
        boolean lowSpeed = latestSpeedMetersPerSecond <= STATIONARY_SPEED_THRESHOLD;
        return lowMotion && lowSpeed && timeSinceLastStepMs >= STATIONARY_STEP_GAP_MS;
    }

    private void refreshFusionMotionState() {
        if (pdrProcessing == null) {
            return;
        }

        pdrProcessing.setMotionState(
                isAltitudeCalibrationStationary(),
                latestSpeedMetersPerSecond,
                latestLinearAccelerationMagnitude
        );
    }

    @Override
    public void update(Object[] wifiList) {
        this.wifiList = Stream.of(wifiList).map(o -> (Wifi) o).collect(Collectors.toList());

        if(this.saveRecording && trajectory != null) {
            long relativeTime = SystemClock.uptimeMillis() - bootTime;
            Traj.GNSSPosition replayWifiPosition = buildReplayPositionForWifi(relativeTime);

            Traj.Fingerprint.Builder fingerprintBuilder = Traj.Fingerprint.newBuilder()
                    .setRelativeTimestamp(relativeTime);

            for (Wifi data : this.wifiList) {
                Traj.RFScan.Builder rfBuilder = Traj.RFScan.newBuilder()
                        .setRelativeTimestamp(relativeTime)
                        .setMac(data.getBssid())
                        .setRssi(data.getLevel());

                if (replayWifiPosition != null) {
                    rfBuilder.setPosition(replayWifiPosition);
                }

                fingerprintBuilder.addRfScans(rfBuilder.build());
            }

            this.trajectory.addWifiFingerprints(fingerprintBuilder.build());
        }
        createWifiPositioningRequest();
    }

    private Traj.GNSSPosition buildReplayPositionForWifi(long relativeTime) {
        // Replay WiFi trace must reflect WiFi positioning output only.
        // Do not fallback to fused/GNSS here, otherwise WiFi appears to follow GNSS/PDR.
        LatLng wifiLocation = wiFiPositioning != null ? wiFiPositioning.getWifiLocation() : null;
        if (wifiLocation != null && isValidCoordinate(wifiLocation.latitude, wifiLocation.longitude)) {
            return Traj.GNSSPosition.newBuilder()
                    .setLatitude(wifiLocation.latitude)
                    .setLongitude(wifiLocation.longitude)
                    .setAltitude(altitude_val)
                    .setRelativeTimestamp(relativeTime)
                    .build();
        }

        if (lastAcceptedWifiLocation != null
                && isValidCoordinate(lastAcceptedWifiLocation.latitude, lastAcceptedWifiLocation.longitude)) {
            return Traj.GNSSPosition.newBuilder()
                    .setLatitude(lastAcceptedWifiLocation.latitude)
                    .setLongitude(lastAcceptedWifiLocation.longitude)
                    .setAltitude(altitude_val)
                    .setRelativeTimestamp(relativeTime)
                    .build();
        }

        return null;
    }

    private boolean isValidCoordinate(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            return false;
        }
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            return false;
        }
        return !(Math.abs(lat) < 1e-8 && Math.abs(lon) < 1e-8);
    }

    private boolean shouldFuseWifiLocation(@NonNull LatLng wifiLocation) {
        boolean stationary = isAltitudeCalibrationStationary();

        if (lastWifiCandidateLocation != null
                && GeometryUtils.distanceBetween(lastWifiCandidateLocation, wifiLocation) <= WIFI_STABLE_DISTANCE_METERS) {
            stableWifiCandidateCount++;
        } else {
            stableWifiCandidateCount = 1;
        }
        lastWifiCandidateLocation = wifiLocation;

        int requiredStableCount = stationary
                ? WIFI_STABLE_CONFIRMATIONS_STATIONARY
                : WIFI_STABLE_CONFIRMATIONS_MOVING;
        if (stableWifiCandidateCount < requiredStableCount) {
            return false;
        }

        if (stationary) {
            LatLng fusedLocation = pdrProcessing != null ? pdrProcessing.getFusedLatLon() : null;
            if (fusedLocation != null
                    && GeometryUtils.distanceBetween(fusedLocation, wifiLocation) > WIFI_STATIONARY_FUSED_GATE_METERS) {
                return false;
            }

            if (lastAcceptedWifiLocation != null
                    && GeometryUtils.distanceBetween(lastAcceptedWifiLocation, wifiLocation) > WIFI_STATIONARY_ACCEPTED_GATE_METERS) {
                return false;
            }
        }

        lastAcceptedWifiLocation = wifiLocation;
        return true;
    }

    private void createWifiPositioningRequest(){
        try {
            JSONObject wifiAccessPoints=new JSONObject();
            for (Wifi data : this.wifiList){
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
            this.wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng location, int floor) {
                    refreshFusionMotionState();
                    if (location != null && shouldFuseWifiLocation(location)) {
                        boolean fusionWasInitialized = pdrProcessing.isFusionInitialized();
                        if (fusionWasInitialized || shouldUseWifiInitialization(location)) {
                            pdrProcessing.processWifiLocation(location, floor);
                            if (!fusionWasInitialized && pdrProcessing.isFusionInitialized()) {
                                LatLng origin = pdrProcessing.getFusedLatLon();
                                setStartGNSSLatitude(new float[]{(float) origin.latitude, (float) origin.longitude});
                            }
                        }
                    }
                }

                @Override
                public void onError(String message) {
                    Log.d("SensorFusion", "WiFi positioning skipped: " + message);
                }
            });
        } catch (JSONException e) {
            Log.e("jsonErrors","Error creating json object"+e.toString());
        }
    }

    public LatLng getLatLngWifiPositioning(){return this.wiFiPositioning.getWifiLocation();}

    private boolean isInsideKnownIndoorBuildings(double lat, double lon) {
        LatLng point = new LatLng(lat, lon);
        return BuildingPolygon.inAnyKnownBuilding(point);
    }

    private boolean shouldUseWifiInitialization(@NonNull LatLng wifiLocation) {
        return isInsideKnownIndoorBuildings(wifiLocation.latitude, wifiLocation.longitude);
    }

    private boolean shouldUseGnssInitialization(float lat, float lon) {
        return !isInsideKnownIndoorBuildings(lat, lon);
    }

    public int getWifiFloor(){
        return this.wiFiPositioning.getFloor();
    }

    // region Helper Methods
    // Convert Android float[3] vectors to protobuf Vector3.
    private Traj.Vector3 toVector3(float[] values) {
        return Traj.Vector3.newBuilder()
                .setX(values[0])
                .setY(values[1])
                .setZ(values[2])
                .build();
    }

    // Convert Android rotation vector to protobuf Quaternion.
    private Traj.Quaternion toQuaternion(float[] values) {
        return Traj.Quaternion.newBuilder()
                .setX(values[0])
                .setY(values[1])
                .setZ(values[2])
                .setW(values.length > 3 ? values[3] : 1.0f)
                .build();
    }

    // Serialize sensor metadata for trajectory headers.
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
    // endregion

    public void addMarker() {
        if (saveRecording && trajectory != null) {
            long relativeTime = SystemClock.uptimeMillis() - bootTime;

            // Marker index and name are persisted for replay-side marker lookup.
            Traj.TimestampMarker marker = Traj.TimestampMarker.newBuilder()
                    .setRelativeTimestamp(relativeTime)
                    .setLatitude(latitude)
                    .setLongitude(longitude)
                    .setAltitude(altitude_val)
                    .setMarkerIndex(markerCount)
                    .setMarkerName("Marker_" + markerCount)
                    .build();

            trajectory.addTestPoints(marker);
            markerCount++;
            Log.d("SensorFusion", "Marker #" + (markerCount - 1) + " added at: " + relativeTime + "ms");
        }
    }

    public void addMarkerAt(@NonNull LatLng markerLocation, float markerAltitude) {
        if (!saveRecording || trajectory == null) {
            return;
        }

        long relativeTime = SystemClock.uptimeMillis() - bootTime;
        Traj.TimestampMarker marker = Traj.TimestampMarker.newBuilder()
                .setRelativeTimestamp(relativeTime)
                .setLatitude(markerLocation.latitude)
                .setLongitude(markerLocation.longitude)
                .setAltitude(markerAltitude)
                .setMarkerIndex(markerCount)
                .setMarkerName("Marker_" + markerCount)
                .build();

        trajectory.addTestPoints(marker);
        markerCount++;
        Log.d("SensorFusion", "Marker #" + (markerCount - 1) + " added at display location");
    }

    // Set the venue/building name for this trajectory.
    public void setVenueName(String venueName) {
        if (trajectory != null && venueName != null && !venueName.isEmpty()) {
            trajectory.setVenueName(venueName);
            Log.d("SensorFusion", "Venue name set to: " + venueName);
        }
    }

    // Set the building ID used by indoor map APIs.
    public void setBuildingId(String buildingId) {
        if (trajectory != null && buildingId != null && !buildingId.isEmpty()) {
            trajectory.setBuildingId(buildingId);
            Log.d("SensorFusion", "Building ID set to: " + buildingId);
        }
    }

    // Update current heading in degrees [0, 360).
    public void updateHeading(float heading) {
        this.currentHeading = normalizeHeadingDegrees(heading);
    }

    // Return current heading.
    public float getCurrentHeading() {
        return currentHeading;
    }

    public float getDisplayHeading() {
        return displayHeadingInitialized ? displayHeading : currentHeading;
    }

    private float normalizeHeadingDegrees(float headingDegrees) {
        float normalized = headingDegrees % 360.0f;
        if (normalized < 0) {
            normalized += 360.0f;
        }
        return normalized;
    }

    private void updateGyroHeading(SensorEvent sensorEvent) {
        long timestampNs = sensorEvent.timestamp;
        if (lastGyroTimestampNs == 0L) {
            lastGyroTimestampNs = timestampNs;
            return;
        }

        float dtSeconds = (timestampNs - lastGyroTimestampNs) / 1_000_000_000.0f;
        lastGyroTimestampNs = timestampNs;
        if (dtSeconds <= 0.0f || dtSeconds > 0.2f) {
            return;
        }

        float yawRateRad = sensorEvent.values[2];
        float deltaYawDeg = (float) Math.toDegrees(yawRateRad * dtSeconds);

        if (!gyroHeadingInitialized) {
            gyroHeadingDeg = currentHeading;
            gyroHeadingInitialized = true;
        } else {
            gyroHeadingDeg = normalizeHeadingDegrees(gyroHeadingDeg + deltaYawDeg);
        }
    }

    private void updateDisplayHeading(float absoluteHeadingDeg) {
        if (!displayHeadingInitialized) {
            displayHeading = normalizeHeadingDegrees(absoluteHeadingDeg);
            displayHeadingInitialized = true;
            if (!gyroHeadingInitialized) {
                gyroHeadingDeg = displayHeading;
                gyroHeadingInitialized = true;
            }
            return;
        }

        float magneticIntensity = (float) Math.sqrt(
                magneticField[0] * magneticField[0]
                        + magneticField[1] * magneticField[1]
                        + magneticField[2] * magneticField[2]);
        boolean magneticallyDisturbed = magneticIntensity < MAGNETIC_FIELD_MIN_UT
                || magneticIntensity > MAGNETIC_FIELD_MAX_UT;

        float targetHeading = absoluteHeadingDeg;
        if (magneticallyDisturbed && gyroHeadingInitialized) {
            targetHeading = gyroHeadingDeg;
        }

        float alpha = magneticallyDisturbed ? DISPLAY_HEADING_ALPHA_DISTURBED : DISPLAY_HEADING_ALPHA_NORMAL;
        float diff = shortestAngleDiffDeg(displayHeading, targetHeading);
        if (Math.abs(diff) <= DISPLAY_HEADING_DEADBAND_DEG) {
            return;
        }

        float limitedDiff = clamp(diff, -DISPLAY_HEADING_MAX_STEP_DEG, DISPLAY_HEADING_MAX_STEP_DEG);
        displayHeading = normalizeHeadingDegrees(displayHeading + alpha * limitedDiff);
    }

    private void updateDisplayHeadingWithMovement(float pdrX, float pdrY) {
        if (!displayHeadingInitialized) {
            return;
        }

        if (!hasLastPdrForHeading) {
            lastPdrXForHeading = pdrX;
            lastPdrYForHeading = pdrY;
            hasLastPdrForHeading = true;
            return;
        }

        float dx = pdrX - lastPdrXForHeading;
        float dy = pdrY - lastPdrYForHeading;
        lastPdrXForHeading = pdrX;
        lastPdrYForHeading = pdrY;

        float distance = (float) Math.hypot(dx, dy);
        if (distance < HEADING_MOVE_MIN_DISTANCE_METERS) {
            return;
        }

        float movementBearingDeg = normalizeHeadingDegrees((float) Math.toDegrees(Math.atan2(dx, dy)));
        float magneticIntensity = (float) Math.sqrt(
                magneticField[0] * magneticField[0]
                        + magneticField[1] * magneticField[1]
                        + magneticField[2] * magneticField[2]);
        boolean magneticallyDisturbed = magneticIntensity < MAGNETIC_FIELD_MIN_UT
                || magneticIntensity > MAGNETIC_FIELD_MAX_UT;
        float weight = magneticallyDisturbed
                ? DISPLAY_HEADING_MOVE_FUSION_WEIGHT_DISTURBED
                : DISPLAY_HEADING_MOVE_FUSION_WEIGHT;

        float diff = shortestAngleDiffDeg(displayHeading, movementBearingDeg);
        displayHeading = normalizeHeadingDegrees(displayHeading + weight * diff);
    }

    private float shortestAngleDiffDeg(float fromDeg, float toDeg) {
        float diff = normalizeHeadingDegrees(toDeg) - normalizeHeadingDegrees(fromDeg);
        if (diff > 180.0f) {
            diff -= 360.0f;
        } else if (diff < -180.0f) {
            diff += 360.0f;
        }
        return diff;
    }

    private float clamp(float value, float min, float max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    // Compensate rotation matrix by screen orientation for consistent heading.
    private void applyDisplayCompensation(float[] inR, float[] outR) {
        int axisX = SensorManager.AXIS_X;
        int axisY = SensorManager.AXIS_Y;

        int displayRotation = Surface.ROTATION_0;
        try {
            if (appContext != null) {
                WindowManager windowManager = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
                if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                    displayRotation = windowManager.getDefaultDisplay().getRotation();
                }
            }
        } catch (Exception ignored) {
            displayRotation = Surface.ROTATION_0;
        }

        switch (displayRotation) {
            case Surface.ROTATION_90:
                axisX = SensorManager.AXIS_Y;
                axisY = SensorManager.AXIS_MINUS_X;
                break;
            case Surface.ROTATION_180:
                axisX = SensorManager.AXIS_MINUS_X;
                axisY = SensorManager.AXIS_MINUS_Y;
                break;
            case Surface.ROTATION_270:
                axisX = SensorManager.AXIS_MINUS_Y;
                axisY = SensorManager.AXIS_X;
                break;
            case Surface.ROTATION_0:
            default:
                axisX = SensorManager.AXIS_X;
                axisY = SensorManager.AXIS_Y;
                break;
        }

        if (!SensorManager.remapCoordinateSystem(inR, axisX, axisY, outR)) {
            System.arraycopy(inR, 0, outR, 0, 9);
        }
    }

    // Return current marker count.
    public int getMarkerCount() {
        return markerCount;
    }

    // region Start/Stop

    public void resumeListening() {
        if (accelerometerSensor.sensor != null) accelerometerSensor.sensorManager.registerListener(this, accelerometerSensor.sensor, 10000);
        if (linearAccelerationSensor.sensor != null) linearAccelerationSensor.sensorManager.registerListener(this, linearAccelerationSensor.sensor, 10000);
        if (gravitySensor.sensor != null) gravitySensor.sensorManager.registerListener(this, gravitySensor.sensor, 10000);
        if (barometerSensor.sensor != null) barometerSensor.sensorManager.registerListener(this, barometerSensor.sensor, (int) 1e6);
        if (gyroscopeSensor.sensor != null) gyroscopeSensor.sensorManager.registerListener(this, gyroscopeSensor.sensor, 10000);
        if (lightSensor.sensor != null) lightSensor.sensorManager.registerListener(this, lightSensor.sensor, (int) 1e6);
        if (proximitySensor.sensor != null) proximitySensor.sensorManager.registerListener(this, proximitySensor.sensor, (int) 1e6);
        if (magnetometerSensor.sensor != null) magnetometerSensor.sensorManager.registerListener(this, magnetometerSensor.sensor, 10000);
        if (stepDetectionSensor.sensor != null) stepDetectionSensor.sensorManager.registerListener(this, stepDetectionSensor.sensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (rotationSensor.sensor != null) rotationSensor.sensorManager.registerListener(this, rotationSensor.sensor, 10000);

        wifiProcessor.startListening();
        gnssProcessor.startLocationUpdates();
        startBleScan();
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
        // Keep scanner singleton semantics to avoid duplicate callbacks and leaks.
        if (bluetoothLeScanner != null && bleScanCallback != null) {
            android.util.Log.d("SensorFusion", "BLE scan already running");
            return;
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

                // Maintain latest BLE device snapshot for UI rendering.
                String macAddress = result.getDevice().getAddress();
                String name = null;
                try {
                    if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        name = result.getDevice().getName();
                    }
                } catch (SecurityException ignored) { }

                int rssi = result.getRssi();

                // Update RSSI for known devices, otherwise append a new device entry.
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

                // Persist BLE observations in trajectory while recording.
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
        bleScanCallback = null;
        bluetoothLeScanner = null;
    }

    // Start recording with the specified trajectory ID.
    public synchronized void startRecording(String trajectoryId) {
        if (this.saveRecording) {
            Log.w("SensorFusion", "startRecording called while already recording; ignoring duplicate start");
            return;
        }

        if (this.storeTrajectoryTimer != null) {
            try {
                this.storeTrajectoryTimer.cancel();
                this.storeTrajectoryTimer.purge();
            } catch (Exception ignored) {
            }
            this.storeTrajectoryTimer = null;
        }

        if (wakeLock == null) {
            PowerManager powerManager = (PowerManager) this.appContext.getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PositionMe::WakeLock");
        }
        wakeLock.acquire(31 * 60 * 1000L);

        // Ensure every recording session has a non-empty ID.
        if (trajectoryId == null || trajectoryId.isEmpty()) {
            trajectoryId = "UnknownTraj_" + System.currentTimeMillis();
        }

        // Keep explicit session identifier in logs for upload/replay traceability.
        Log.d("SensorFusion", "Start recording with ID: " + trajectoryId);

        this.saveRecording = true;
        this.stepCounter = 0;
        this.gnssRecordCount = 0;
        this.pdrRecordCount = 0;
        this.markerCount = 0;
        this.currentHeading = orientation[2];
        this.displayHeading = normalizeHeadingDegrees(this.currentHeading);
        this.displayHeadingInitialized = true;
        this.hasLastPdrForHeading = false;
        this.lastGyroTimestampNs = 0L;
        this.gyroHeadingInitialized = false;
        this.absoluteStartTime = System.currentTimeMillis();
        this.bootTime = SystemClock.uptimeMillis();

        // Initialize trajectory header and sensor metadata.
        this.trajectory = Traj.Trajectory.newBuilder()
                .setAndroidVersion(Build.VERSION.RELEASE)
                .setStartTimestamp(absoluteStartTime)
            .setTrajectoryId(trajectoryId)
            .setInitialHeading(currentHeading)
                .setAccelerometerInfo(createSensorInfo(accelerometerSensor))
                .setGyroscopeInfo(createSensorInfo(gyroscopeSensor))
                .setMagnetometerInfo(createSensorInfo(magnetometerSensor))
                .setBarometerInfo(createSensorInfo(barometerSensor))
                .setLightSensorInfo(createSensorInfo(lightSensor));

        // Start origin policy:
        // Use manual start when provided.
        // Indoors, prefer WiFi start.
        // Outdoors, use GNSS start.
        boolean hasManualStart = startLocation != null && startLocation[0] != 0 && startLocation[1] != 0;
        LatLng wifiInitialLocation = lastAcceptedWifiLocation != null ? lastAcceptedWifiLocation : getLatLngWifiPositioning();
        boolean hasWifiStart = wifiInitialLocation != null;
        boolean indoorsByCurrentFix = isInsideKnownIndoorBuildings(latitude, longitude);

        float initLat;
        float initLon;
        boolean canInitializeNow;
        if (hasManualStart) {
            initLat = startLocation[0];
            initLon = startLocation[1];
            canInitializeNow = true;
            Log.d("SensorFusion", "Using Manual Start Location: " + initLat + ", " + initLon);
        } else if (indoorsByCurrentFix && hasWifiStart) {
            initLat = (float) wifiInitialLocation.latitude;
            initLon = (float) wifiInitialLocation.longitude;
            canInitializeNow = true;
            Log.d("SensorFusion", "Using WiFi Start Location: " + initLat + ", " + initLon);
        } else if (!indoorsByCurrentFix) {
            initLat = latitude;
            initLon = longitude;
            canInitializeNow = true;
            Log.d("SensorFusion", "Using GNSS Start Location (outdoor): " + initLat + ", " + initLon);
        } else {
            // Indoors without a WiFi location: defer fusion initialization until callback arrives.
            initLat = latitude;
            initLon = longitude;
            canInitializeNow = false;
            Log.d("SensorFusion", "Indoor start detected but WiFi unavailable, waiting for WiFi initialization");
        }

        Traj.GNSSPosition initialPos = Traj.GNSSPosition.newBuilder()
                .setLatitude(initLat)
                .setLongitude(initLon)
                .setAltitude(altitude_val)
                .setRelativeTimestamp(0)
                .build();
        this.trajectory.setInitialPosition(initialPos);

        this.storeTrajectoryTimer = new Timer("trajectory-store-timer", true);
        this.storeTrajectoryTimer.schedule(new storeDataInTrajectory(), 0, TIME_CONST);
        this.pdrProcessing.resetPDR();
        if (canInitializeNow) {
            this.pdrProcessing.initializeWithLocation(initLat, initLon);
            setStartGNSSLatitude(new float[]{initLat, initLon});
        }

        // Reset step-detection windows for a clean recording session.
        accWindowIndex = 0;
        for(int i=0; i<SMOOTH_WINDOW; i++) accWindow[i] = 0;
        currentMaxAcc = 0;
        currentMinAcc = Float.MAX_VALUE;
        lastEnhancedStepTimeMs = 0L;

        if(settings.getBoolean("overwrite_constants", false)) {
            this.filter_coefficient = Float.parseFloat(settings.getString("accel_filter", "0.96"));
        } else {
            this.filter_coefficient = FILTER_COEFFICIENT;
        }
    }

    public synchronized void stopRecording() {
        if(this.saveRecording) {
            this.saveRecording = false;
            if (storeTrajectoryTimer != null) {
                storeTrajectoryTimer.cancel();
                storeTrajectoryTimer.purge();
                storeTrajectoryTimer = null;
            }
            // BLE scanning remains active for real-time UI panels.

            // Output per-session statistics at stop time.
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

    // endregion

    // region Trajectory object

    public void sendTrajectoryToCloud() {
        if (trajectory != null) {
            // Campaign tag is optional metadata provided by UI.
            String campaign = settings.getString("current_campaign", "");
            Traj.Trajectory sentTrajectory = trajectory.build();

            // Validate payload composition before upload.
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

            // High-rate IMU and orientation packet.
            trajectory.addImuData(Traj.IMUReading.newBuilder()
                    .setRelativeTimestamp(relTime)
                    .setAcc(toVector3(acceleration))
                    .setGyr(toVector3(angularVelocity))
                    .setRotationVector(toQuaternion(rotation))
                    .setStepCount(stepCounter)
                    .build());

            // Magnetometer stream is stored independently for replay/analysis.
            trajectory.addMagnetometerData(Traj.MagnetometerReading.newBuilder()
                    .setRelativeTimestamp(relTime)
                    .setMag(toVector3(magneticField))
                    .build());

            if (counter == 99) {
                counter = 0;

                // Store slow-changing sensors at 1 Hz.
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
                    // Store representative AP metadata at 0.2 Hz.
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

    // endregion

    public Map<SensorTypes, float[]> getSensorValueMap() {
        Map<SensorTypes, float[]> sensorValueMap = new HashMap<>();

        sensorValueMap.put(SensorTypes.ACCELEROMETER, acceleration);
        sensorValueMap.put(SensorTypes.GRAVITY, gravity);
        sensorValueMap.put(SensorTypes.MAGNETICFIELD, magneticField);
        sensorValueMap.put(SensorTypes.GYRO, angularVelocity);
        sensorValueMap.put(SensorTypes.LIGHT, new float[]{light});
        sensorValueMap.put(SensorTypes.PRESSURE, new float[]{pressure});
        sensorValueMap.put(SensorTypes.PROXIMITY, new float[]{proximity});
        sensorValueMap.put(SensorTypes.GNSSLATLONG, new float[]{latitude, longitude});
        sensorValueMap.put(SensorTypes.PDR, pdrProcessing.getPDRMovement());

        // Fused position output used by map and trajectory overlays.
        LatLng fusedLatLng = pdrProcessing.getFusedLatLon();
        if (fusedLatLng != null) {
            sensorValueMap.put(SensorTypes.FUSED, new float[]{(float) fusedLatLng.latitude, (float) fusedLatLng.longitude});
        } else {
            sensorValueMap.put(SensorTypes.FUSED, new float[]{0f, 0f});
        }

        // WiFi-only position output from network fingerprinting.
        LatLng wifiLatLng = wiFiPositioning.getWifiLocation();
        if (wifiLatLng != null) {
            sensorValueMap.put(SensorTypes.WIFI, new float[]{(float) wifiLatLng.latitude, (float) wifiLatLng.longitude});
        } else {
            sensorValueMap.put(SensorTypes.WIFI, new float[]{0f, 0f});
        }

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

        // Append BLE scanner capability as a pseudo-sensor entry.
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
    public float getEstimatedAbsoluteAltitude() { return this.estimatedAbsoluteAltitude; }
    public boolean getElevator() { return this.elevator; }
    public float getGnssAccuracy() { return this.gnssAccuracy; }
    public float getGnssVerticalAccuracy() { return this.gnssVerticalAccuracy; }
    public LatLng getFusedLatLng() {
        return pdrProcessing != null ? pdrProcessing.getFusedLatLon() : null;
    }
    public int getEstimatedFloor() {
        if (!barometerAutoFloorEnabled) {
            return pdrProcessing != null ? pdrProcessing.getCurrentFloor() : 0;
        }
        return getEstimatedFloorByBarometerBands();
    }

    public int getEstimatedFloorByBarometerBands() {
        if (!barometerAutoFloorEnabled) {
            return pdrProcessing != null ? pdrProcessing.getCurrentFloor() : 0;
        }

        float altitudeForFloor = !Float.isNaN(estimatedAbsoluteAltitude)
                ? estimatedAbsoluteAltitude
                : (!Float.isNaN(smoothedBarometerAbsoluteAltitude)
                    ? smoothedBarometerAbsoluteAltitude
                    : barometerAbsoluteAltitude);

        if (Float.isNaN(altitudeForFloor)) {
            return pdrProcessing != null ? pdrProcessing.getCurrentFloor() : 0;
        }

        int rawFloor = getRawEstimatedFloorFromBands(altitudeForFloor);
        if (lastStableEstimatedFloor == Integer.MIN_VALUE) {
            lastStableEstimatedFloor = rawFloor;
            return lastStableEstimatedFloor;
        }

        int stableFloor = lastStableEstimatedFloor;
        switch (stableFloor) {
            case 0:
                if (altitudeForFloor >= FLOOR_BAND_B1_MAX + FLOOR_BAND_HYSTERESIS_METERS) {
                    stableFloor = 1;
                }
                break;
            case 1:
                if (altitudeForFloor <= FLOOR_BAND_B1_MAX - FLOOR_BAND_HYSTERESIS_METERS) {
                    stableFloor = 0;
                } else if (altitudeForFloor >= FLOOR_BAND_GF_MAX + FLOOR_BAND_HYSTERESIS_METERS) {
                    stableFloor = 2;
                }
                break;
            case 2:
                if (altitudeForFloor <= FLOOR_BAND_GF_MAX - FLOOR_BAND_HYSTERESIS_METERS) {
                    stableFloor = 1;
                } else if (altitudeForFloor >= FLOOR_BAND_F1_MAX + FLOOR_BAND_HYSTERESIS_METERS) {
                    stableFloor = 3;
                }
                break;
            case 3:
                if (altitudeForFloor <= FLOOR_BAND_F1_MAX - FLOOR_BAND_HYSTERESIS_METERS) {
                    stableFloor = 2;
                } else if (altitudeForFloor >= FLOOR_BAND_F2_MAX + FLOOR_BAND_HYSTERESIS_METERS) {
                    stableFloor = 4;
                }
                break;
            default:
                if (altitudeForFloor <= FLOOR_BAND_F2_MAX - FLOOR_BAND_HYSTERESIS_METERS) {
                    stableFloor = 3;
                }
                break;
        }

        if (Math.abs(rawFloor - stableFloor) >= 2) {
            stableFloor = rawFloor;
        }

        lastStableEstimatedFloor = stableFloor;
        return lastStableEstimatedFloor;
    }

    public void setBarometerAutoFloorEnabled(boolean enabled) {
        if (this.barometerAutoFloorEnabled == enabled) {
            return;
        }
        this.barometerAutoFloorEnabled = enabled;
        this.lastStableEstimatedFloor = Integer.MIN_VALUE;
    }

    public boolean isBarometerAutoFloorEnabled() {
        return this.barometerAutoFloorEnabled;
    }

    private int getRawEstimatedFloorFromBands(float altitudeForFloor) {
        if (altitudeForFloor <= FLOOR_BAND_B1_MAX) {
            return 0; // B1
        }
        if (altitudeForFloor < FLOOR_BAND_GF_MAX) {
            return 1; // GF
        }
        if (altitudeForFloor < FLOOR_BAND_F1_MAX) {
            return 2; // F1
        }
        if (altitudeForFloor < FLOOR_BAND_F2_MAX) {
            return 3; // F2
        }
        return 4; // F3+
    }
    public void setIndoorFloorReference(float floorHeightMeters, int floorIndex, float[] floorAltitudeAnchorsMeters) {
        if (pdrProcessing != null) {
            pdrProcessing.configureFloorReference(floorHeightMeters, floorIndex, floorAltitudeAnchorsMeters);
        }
    }

    public void setIndoorEnvironmentFeatures(List<List<LatLng>> stairsZones, List<List<LatLng>> liftZones, List<List<LatLng>> walls) {
        if (pdrProcessing != null) {
            pdrProcessing.setIndoorFeatureZones(stairsZones, liftZones, walls);
        }
    }

    public int getHoldMode(){
        int proximityThreshold = 1, lightThreshold = 100;
        if(proximity<proximityThreshold && light>lightThreshold) return 1;
        else return 0;
    }
    // endregion

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {}

    public Traj.Trajectory.Builder getTrajectory() {
        return this.trajectory;
    }
    public void resetPDR() {
        if (pdrProcessing != null) {
            pdrProcessing.refreshSettings();
        }
        lastWifiCandidateLocation = null;
        lastAcceptedWifiLocation = null;
        stableWifiCandidateCount = 0;
    }

    public void setMagneticCompensationEnabled(boolean enabled) {
        if (pdrProcessing != null) {
            pdrProcessing.setMagneticCompensationEnabled(enabled);
        }
    }

    public boolean isMagneticCompensationEnabled() {
        return pdrProcessing != null && pdrProcessing.isMagneticCompensationEnabled();
    }
}
