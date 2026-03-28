package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.fusion.FusedPose;
import com.openpositioning.PositionMe.fusion.ParticleFilterManager;
import com.openpositioning.PositionMe.mapmatching.CandidatePose;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.service.SensorCollectionService;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.PdrProcessing;
import com.openpositioning.PositionMe.utils.TrajectoryValidator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Main sensor/data fusion singleton for the application.
 *
 * <p>This class owns:
 * <ul>
 *     <li>live sensor state</li>
 *     <li>PDR processing</li>
 *     <li>recording lifecycle</li>
 *     <li>GNSS / WiFi / BLE data collection</li>
 *     <li>particle filter wiring</li>
 * </ul>
 *
 * <p>External callers should continue using {@code SensorFusion.getInstance()}.</p>
 */
public class SensorFusion implements SensorEventListener {

    // Singleton
    private static final SensorFusion sensorFusion = new SensorFusion();

    public static SensorFusion getInstance() {
        return sensorFusion;
    }

    private SensorFusion() {
        this.locationListener = new MyLocationListener();
    }

    // Constants
    public static final int TRAJECTORY_MODE_PDR = 0;
    public static final int TRAJECTORY_MODE_PARTICLE_FILTER = 1;

    private static final String TAG = "SensorFusion";

    // Core app state
    private Context appContext;

    /**
     * Shared live sensor state container.
     */
    private final SensorState state = new SensorState();

    // Internal modules
    private SensorEventHandler eventHandler;
    private TrajectoryRecorder recorder;
    private WifiPositionManager wifiPositionManager;
    private ParticleFilterManager particleFilterManager;

    @Nullable
    private FusedPose latestParticleFilterPose;

    @Nullable
    private FusedPose latestRawParticleFilterPose;
    @Nullable
    private CandidatePose particleFilterMatchedPose;
    @Nullable
    private IndoorMapManager particleFilterIndoorMapManager;

    // Movement sensors
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

    // Non-sensor data sources
    private WifiDataProcessor wifiProcessor;
    private BleDataProcessor bleProcessor;
    private GNSSDataProcessor gnssProcessor;
    private RttManager rttManager;
    private BleRttManager bleRttManager;
    private final LocationListener locationListener;

    // PDR and path
    private PdrProcessing pdrProcessing;
    private PathView pathView;

    /**
     * Sensor batching latency configuration.
     */
    long maxReportLatencyNs = 0;

    // Floorplan / building cache

    /**
     * Latest building metadata returned by the floorplan API.
     * Cached by building id for later fragments and PF use.
     */
    private final Map<String, FloorplanApiClient.BuildingInfo> floorplanBuildingCache =
            new HashMap<>();

    // Recording mode state

    private int recordingTrajectoryMode = TRAJECTORY_MODE_PDR;

    // Initialisation
    /**
     * Initialises SensorFusion and all dependent modules.
     */
    public void setContext(Context context) {
        this.appContext = context.getApplicationContext();

        // Movement sensors
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

        // External data sources
        this.gnssProcessor = new GNSSDataProcessor(context, locationListener);
        ServerCommunications serverCommunications = new ServerCommunications(context);

        // Utilities
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.pdrProcessing = new PdrProcessing(context);
        this.pathView = new PathView(context, null);
        WiFiPositioning wiFiPositioning = new WiFiPositioning(context);

        // Recorder
        this.recorder = new TrajectoryRecorder(appContext, state, serverCommunications, settings);
        this.recorder.setSensorReferences(
                accelerometerSensor,
                gyroscopeSensor,
                magnetometerSensor,
                barometerSensor,
                lightSensor,
                proximitySensor,
                rotationSensor
        );

        // WiFi positioning manager
        this.wifiPositionManager = new WifiPositionManager(wiFiPositioning, recorder);

        // Particle filter manager
        this.particleFilterManager = new ParticleFilterManager(
                this,
                new ParticleFilterManager.Host() {
                    @Override
                    public void onFusedPoseUpdated(@NonNull FusedPose fusedPose) {
                        latestParticleFilterPose = fusedPose;
                    }
                }
        );
        this.particleFilterManager.setEnabled(isParticleFilterTrajectoryMode());

        if (particleFilterIndoorMapManager != null) {
            this.particleFilterManager.setIndoorMapManager(particleFilterIndoorMapManager);
        }

        // Event handler
        long bootTime = SystemClock.uptimeMillis();
        this.eventHandler = new SensorEventHandler(
                state,
                pdrProcessing,
                pathView,
                recorder,
                particleFilterManager,
                bootTime
        );

        // WiFi scanner
        this.wifiProcessor = new WifiDataProcessor(context);
        wifiProcessor.registerObserver(wifiPositionManager);

        // BLE scanner
        this.bleProcessor = new BleDataProcessor(context);
        bleProcessor.registerObserver(new Observer() {
            @Override
            public void update(Object[] objList) {
                List<BleDevice> bleList = Stream.of(objList)
                        .map(o -> (BleDevice) o)
                        .collect(Collectors.toList());
                recorder.addBleFingerprint(bleList);
            }
        });

        // WiFi RTT
        this.rttManager = new RttManager(appContext, recorder, wifiProcessor);
        wifiProcessor.registerObserver(rttManager);

        // BLE RTT
        this.bleRttManager = new BleRttManager(recorder);
        bleProcessor.registerObserver(bleRttManager);

        if (!rttManager.isRttSupported()) {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(
                            appContext,
                            "WiFi RTT is not supported on this device",
                            Toast.LENGTH_LONG
                    ).show()
            );
        }
    }

    // Particle filter wiring
    /**
     * Returns the latest local PDR movement estimate in metres.
     */
    public float[] getLatestPdrMovement() {
        return pdrProcessing.getPDRMovement();
    }

    /**
     * Advances the particle filter by one live update step.
     *
     * <p>This should be called after the latest sensor values have already been updated.
     * The PF manager will internally:
     * <ul>
     *     <li>try GNSS initialisation if not yet initialised</li>
     *     <li>read live PDR motion</li>
     *     <li>read GNSS / WiFi / matched pose observations</li>
     *     <li>produce fused/raw PF outputs</li>
     * </ul>
     */
    public void stepParticleFilter() {
        if (particleFilterManager == null || !isParticleFilterTrajectoryMode()) {
            return;
        }

        particleFilterManager.step();

        // Keep cached copies here so fragments can query SensorFusion directly.
        latestParticleFilterPose = particleFilterManager.getLatestFusedPose();
        latestRawParticleFilterPose = particleFilterManager.getLatestRawPose();
    }

    /**
     * Wires the current indoor map manager into SensorFusion so the PF can access
     * walkability / wall / floor-transition constraints.
     */
    public void setParticleFilterIndoorMapManager(@Nullable IndoorMapManager indoorMapManager) {
        this.particleFilterIndoorMapManager = indoorMapManager;

        if (particleFilterManager != null) {
            particleFilterManager.setIndoorMapManager(indoorMapManager);
        }
    }

    /**
     * Stores the latest accepted map-matched pose for the particle filter.
     * This is written by MapMatchingCoordinator and consumed by ParticleFilterManager.
     */
    public void setParticleFilterMatchedPose(@Nullable CandidatePose matchedPose) {
        this.particleFilterMatchedPose = matchedPose;

        if (particleFilterManager != null) {
            particleFilterManager.setLatestMatchedPose(matchedPose);
        }
    }

    /**
     * Returns the indoor map manager currently used by the particle filter.
     */
    @Nullable
    public IndoorMapManager getParticleFilterIndoorMapManager() {
        return particleFilterIndoorMapManager;
    }

    /**
     * Returns the latest map-matched pose that should be used as a strong PF observation.
     */
    @Nullable
    public CandidatePose getParticleFilterMatchedPose() {
        return particleFilterMatchedPose;
    }

    /**
     * Resets the particle filter for a new recording session.
     */
    public void resetParticleFilterForRecording() {
        particleFilterMatchedPose = null;

        if (particleFilterManager != null) {
            particleFilterManager.reset();
        }
        latestParticleFilterPose = null;
        latestRawParticleFilterPose = null;
    }

    /**
     * Returns the latest fused PF pose.
     */
    @Nullable
    public FusedPose getLatestFusedPose() {
        if (!isParticleFilterTrajectoryMode()) {
            return null;
        }

        if (particleFilterManager != null) {
            FusedPose managerPose = particleFilterManager.getLatestFusedPose();
            if (managerPose != null) {
                latestParticleFilterPose = managerPose;
            }
        }

        return latestParticleFilterPose;
    }

    /**
     * Returns the latest raw PF pose.
     */
    @Nullable
    public FusedPose getLatestRawFusedPose() {
        if (!isParticleFilterTrajectoryMode()) {
            return null;
        }

        if (particleFilterManager != null) {
            FusedPose rawPose = particleFilterManager.getLatestRawPose();
            if (rawPose != null) {
                latestRawParticleFilterPose = rawPose;
            }
        }

        return latestRawParticleFilterPose;
    }

    /**
     * Allows PF-related classes to push the latest fused pose back into SensorFusion.
     */
    public void setLatestFusedPose(@Nullable FusedPose fusedPose) {
        this.latestParticleFilterPose = fusedPose;
    }

    /**
     * Allows PF-related classes to push the latest raw PF pose back into SensorFusion.
     */
    public void setLatestRawFusedPose(@Nullable FusedPose rawPose) {
        this.latestRawParticleFilterPose = rawPose;
    }

    // SensorEventListener
    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        eventHandler.handleSensorEvent(sensorEvent);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
        // No-op
    }

    // Start/stop listening
    /**
     * Registers all listeners and resumes live collection.
     */
    public void resumeListening() {
        accelerometerSensor.sensorManager.registerListener(
                this, accelerometerSensor.sensor, 10000, (int) maxReportLatencyNs);
        accelerometerSensor.sensorManager.registerListener(
                this, linearAccelerationSensor.sensor, 10000, (int) maxReportLatencyNs);
        accelerometerSensor.sensorManager.registerListener(
                this, gravitySensor.sensor, 10000, (int) maxReportLatencyNs);
        barometerSensor.sensorManager.registerListener(
                this, barometerSensor.sensor, (int) 1e6);
        gyroscopeSensor.sensorManager.registerListener(
                this, gyroscopeSensor.sensor, 10000, (int) maxReportLatencyNs);
        lightSensor.sensorManager.registerListener(
                this, lightSensor.sensor, (int) 1e6);
        proximitySensor.sensorManager.registerListener(
                this, proximitySensor.sensor, (int) 1e6);
        magnetometerSensor.sensorManager.registerListener(
                this, magnetometerSensor.sensor, 10000, (int) maxReportLatencyNs);
        stepDetectionSensor.sensorManager.registerListener(
                this, stepDetectionSensor.sensor, SensorManager.SENSOR_DELAY_NORMAL);
        rotationSensor.sensorManager.registerListener(
                this, rotationSensor.sensor, (int) 1e6);

        // Foreground service owns WiFi/BLE scanning during recording.
        if (!recorder.isRecording()) {
            startWirelessCollectors();
        }

        gnssProcessor.startLocationUpdates();
    }

    /**
     * Unregisters listeners and pauses live collection.
     */
    public void stopListening() {
        if (!recorder.isRecording()) {
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

            stopWirelessCollectors();
            gnssProcessor.stopUpdating();
        }
    }

    public void onCollectionServiceStarted() {
        startWirelessCollectors();
    }

    public void onCollectionServiceStopped() {
        stopWirelessCollectors();
    }

    private void startWirelessCollectors() {
        if (wifiProcessor != null) {
            wifiProcessor.startListening();
        }
        if (bleProcessor != null) {
            bleProcessor.startListening();
        }
    }

    private void stopWirelessCollectors() {
        try {
            if (wifiProcessor != null) {
                wifiProcessor.stopListening();
            }
        } catch (Exception e) {
            Log.w(TAG, "WiFi stop failed", e);
        }

        try {
            if (bleProcessor != null) {
                bleProcessor.stopListening();
            }
        } catch (Exception e) {
            Log.w(TAG, "BLE stop failed", e);
        }
    }

    // Recording lifecycle
    /**
     * Starts trajectory recording and foreground collection.
     */
    public void startRecording() {
        recorder.startRecording(pdrProcessing);
        eventHandler.resetBootTime(recorder.getBootTime());

        latestParticleFilterPose = null;
        latestRawParticleFilterPose = null;
        particleFilterMatchedPose = null;

        if (isParticleFilterTrajectoryMode()) {
            resetParticleFilterForRecording();
        }

        stopWirelessCollectors();

        if (appContext != null) {
            SensorCollectionService.start(appContext);
        }
    }

    /**
     * Stops trajectory recording and foreground collection.
     */
    public void stopRecording() {
        recorder.stopRecording();

        latestParticleFilterPose = null;
        latestRawParticleFilterPose = null;
        particleFilterMatchedPose = null;

        if (particleFilterManager != null) {
            particleFilterManager.reset();
        }

        if (appContext != null) {
            SensorCollectionService.stop(appContext);
        }
    }

    /**
     * Selects whether this recording session uses standard PDR or PF trajectory mode.
     */
    public void setRecordingTrajectoryMode(int mode) {
        if (mode != TRAJECTORY_MODE_PARTICLE_FILTER) {
            mode = TRAJECTORY_MODE_PDR;
        }

        this.recordingTrajectoryMode = mode;

        Log.d(TAG, "SensorFusion mode set = "
                + (mode == TRAJECTORY_MODE_PARTICLE_FILTER
                ? "PARTICLE_FILTER"
                : "STANDARD_PDR"));

        if (particleFilterManager != null) {
            boolean enableParticleFilter = (mode == TRAJECTORY_MODE_PARTICLE_FILTER);
            particleFilterManager.setEnabled(enableParticleFilter);

            if (!enableParticleFilter) {
                particleFilterManager.reset();
                latestParticleFilterPose = null;
                latestRawParticleFilterPose = null;
            }
        }
    }

    public int getRecordingTrajectoryMode() {
        return recordingTrajectoryMode;
    }

    public boolean isParticleFilterTrajectoryMode() {
        return recordingTrajectoryMode == TRAJECTORY_MODE_PARTICLE_FILTER;
    }

    public TrajectoryValidator.ValidationResult validateTrajectory() {
        return recorder.validateTrajectory();
    }

    public void sendTrajectoryToCloud() {
        recorder.sendTrajectoryToCloud();
    }

    public void setTrajectoryId(String id) {
        recorder.setTrajectoryId(id);
    }

    public String getTrajectoryId() {
        return recorder.getTrajectoryId();
    }

    public void saveTestPointToCSV(File file) throws IOException {
        recorder.saveTestPointToCsv(file);
    }

    public void saveRecordingToJSON(File file) throws IOException {
        recorder.saveTrajectoryToJson(file);
    }

    public void setSelectedBuildingId(String buildingId) {
        recorder.setSelectedBuildingId(buildingId);
    }

    public String getSelectedBuildingId() {
        return recorder.getSelectedBuildingId();
    }

    /**
     * Writes the current initial metadata into the protobuf recording.
     */
    public void writeInitialMetadata() {
        recorder.writeInitialMetadata();
    }

    /**
     * Adds a user test point to the recording protobuf.
     */
    public void addTestPointToProto(long pressTimestampMs, double lat, double lng) {
        recorder.addTestPoint(pressTimestampMs, lat, lng);
    }

    // Floorplan cache
    public void setFloorplanBuildings(List<FloorplanApiClient.BuildingInfo> buildings) {
        floorplanBuildingCache.clear();
        if (buildings == null) {
            return;
        }

        for (FloorplanApiClient.BuildingInfo building : buildings) {
            if (building == null || building.getName() == null || building.getName().isEmpty()) {
                continue;
            }
            floorplanBuildingCache.put(building.getName(), building);
        }
    }

    @Nullable
    public FloorplanApiClient.BuildingInfo getFloorplanBuilding(String buildingId) {
        if (buildingId == null || buildingId.isEmpty()) {
            return null;
        }
        return floorplanBuildingCache.get(buildingId);
    }

    @NonNull
    public List<FloorplanApiClient.BuildingInfo> getFloorplanBuildings() {
        return new ArrayList<>(floorplanBuildingCache.values());
    }

    // Getters / setters
    /**
     * Returns current GNSS or recording start GNSS.
     */
    public float[] getGNSSLatitude(boolean start) {
        float[] latLong = new float[2];
        if (!start) {
            latLong[0] = state.latitude;
            latLong[1] = state.longitude;
        } else {
            latLong = state.startLocation;
        }
        return latLong;
    }

    /**
     * Sets the recording start GNSS anchor.
     * @param startPosition contains the initial autonomous anchor chosen by the app
     */
    public void setStartGNSSLatitude(float[] startPosition) {
        state.startLocation[0] = startPosition[0];
        state.startLocation[1] = startPosition[1];
    }

    /**
     * Redraws path using a corrected stride scaling ratio.
     */
    public void redrawPath(float scalingRatio) {
        pathView.redraw(scalingRatio);
    }

    /**
     * Returns average step length from PDR.
     */
    public float passAverageStepLength() {
        return pdrProcessing.getAverageStepLength();
    }

    /**
     * Returns current device heading in radians.
     */
    public float passOrientation() {
        return state.orientation[0];
    }

    /**
     * Returns latest live sensor values.
     */
    public Map<SensorTypes, float[]> getSensorValueMap() {
        Map<SensorTypes, float[]> sensorValueMap = new HashMap<>();
        sensorValueMap.put(SensorTypes.ACCELEROMETER, state.acceleration);
        sensorValueMap.put(SensorTypes.GRAVITY, state.gravity);
        sensorValueMap.put(SensorTypes.MAGNETICFIELD, state.magneticField);
        sensorValueMap.put(SensorTypes.GYRO, state.angularVelocity);
        sensorValueMap.put(SensorTypes.LIGHT, new float[]{state.light});
        sensorValueMap.put(SensorTypes.PRESSURE, new float[]{state.pressure});
        sensorValueMap.put(SensorTypes.PROXIMITY, new float[]{state.proximity});
        sensorValueMap.put(SensorTypes.GNSSLATLONG, getGNSSLatitude(false));
        sensorValueMap.put(SensorTypes.PDR, pdrProcessing.getPDRMovement());
        return sensorValueMap;
    }

    public List<Wifi> getWifiList() {
        return wifiPositionManager.getWifiList();
    }

    public List<SensorInfo> getSensorInfos() {
        List<SensorInfo> sensorInfoList = new ArrayList<>();
        sensorInfoList.add(accelerometerSensor.sensorInfo);
        sensorInfoList.add(barometerSensor.sensorInfo);
        sensorInfoList.add(gyroscopeSensor.sensorInfo);
        sensorInfoList.add(lightSensor.sensorInfo);
        sensorInfoList.add(proximitySensor.sensorInfo);
        sensorInfoList.add(magnetometerSensor.sensorInfo);
        return sensorInfoList;
    }

    public void registerForServerUpdate(Observer observer) {
        recorder.getServerCommunications().registerObserver(observer);
    }

    public float getElevation() {
        return state.elevation;
    }

    public boolean getElevator() {
        return state.elevator;
    }

    public int getHoldMode() {
        int proximityThreshold = 1;
        int lightThreshold = 100;
        return (state.proximity < proximityThreshold && state.light > lightThreshold) ? 1 : 0;
    }

    @Nullable
    public LatLng getLatLngWifiPositioning() {
        return wifiPositionManager != null ? wifiPositionManager.getLatLngWifiPositioning() : null;
    }

    public int getWifiFloor() {
        return wifiPositionManager != null ? wifiPositionManager.getWifiFloor() : 0;
    }

    public void logSensorFrequencies() {
        eventHandler.logSensorFrequencies();
    }

    public Context getContext() {
        return appContext;
    }

    // GNSS listener
    /**
     * GNSS listener that updates live SensorState and records GNSS samples.
     */
    class MyLocationListener implements LocationListener {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            state.latitude = (float) location.getLatitude();
            state.longitude = (float) location.getLongitude();
            recorder.addGnssData(location);
        }
    }
}