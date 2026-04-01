package com.openpositioning.PositionMe.sensors;

import static com.openpositioning.PositionMe.BuildConstants.DEBUG;

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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.sensors.fusion.FusionManager;
import com.openpositioning.PositionMe.service.SensorCollectionService;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.PdrProcessing;
import com.openpositioning.PositionMe.utils.TrajectoryValidator;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The SensorFusion class is the main data gathering and processing class of the application.
 *
 * <p>It follows the singleton design pattern to ensure that every fragment and process has access
 * to the same data and sensor instances. Internally it delegates to specialised modules:</p>
 * <ul>
 *   <li>{@link SensorState} &ndash; shared sensor data holder</li>
 *   <li>{@link SensorEventHandler} &ndash; sensor event dispatch (switch logic)</li>
 *   <li>{@link TrajectoryRecorder} &ndash; recording lifecycle &amp; protobuf construction</li>
 *   <li>{@link WifiPositionManager} &ndash; WiFi scan processing &amp; positioning</li>
 * </ul>
 *
 * <p>The public API is unchanged &ndash; all external callers continue to use
 * {@code SensorFusion.getInstance().method()}.</p>
 */
public class SensorFusion implements SensorEventListener {

    //region Static variables
    private static final SensorFusion sensorFusion = new SensorFusion();
    //endregion

    //region Instance variables
    private Context appContext;

    // Shared sensor state
    private final SensorState state = new SensorState();

    // Internal modules
    private SensorEventHandler eventHandler;
    private TrajectoryRecorder recorder;
    private WifiPositionManager wifiPositionManager;

    // Movement sensor instances (lifecycle managed here)
    private MovementSensor accelerometerSensor;
    private MovementSensor barometerSensor;
    private MovementSensor gyroscopeSensor;
    private MovementSensor lightSensor;
    private MovementSensor proximitySensor;
    private MovementSensor magnetometerSensor;
    private MovementSensor stepDetectionSensor;
    private MovementSensor rotationSensor;
    private MovementSensor magneticRotationSensor; // TYPE_ROTATION_VECTOR for initial heading calibration
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

    // Sensor fusion (particle filter)
    private FusionManager fusionManager;

    // Sensor registration latency setting
    long maxReportLatencyNs = 0;

    // Floorplan API cache (latest result from start-location step)
    private final Map<String, FloorplanApiClient.BuildingInfo> floorplanBuildingCache =
            new HashMap<>();
    //endregion

    //region Initialisation

    /**
     * Private constructor for implementing singleton design pattern.
     */
    private SensorFusion() {
        this.locationListener = new MyLocationListener();
    }

    /**
     * Static function to access singleton instance of SensorFusion.
     *
     * @return singleton instance of SensorFusion class.
     */
    public static SensorFusion getInstance() {
        return sensorFusion;
    }

    /**
     * Initialisation function for the SensorFusion instance.
     *
     * <p>Initialises all movement sensor instances, creates internal modules, and prepares
     * the system for data collection.</p>
     *
     * @param context application context for permissions and device access.
     *
     * @see MovementSensor handling all SensorManager based data collection devices.
     * @see ServerCommunications handling communication with the server.
     * @see GNSSDataProcessor for location data processing.
     * @see WifiDataProcessor for network data processing.
     */
    public void setContext(Context context) {
        this.appContext = context.getApplicationContext();

        // Initialise movement sensors
        this.accelerometerSensor = new MovementSensor(context, Sensor.TYPE_ACCELEROMETER);
        this.barometerSensor = new MovementSensor(context, Sensor.TYPE_PRESSURE);
        this.gyroscopeSensor = new MovementSensor(context, Sensor.TYPE_GYROSCOPE);
        this.lightSensor = new MovementSensor(context, Sensor.TYPE_LIGHT);
        this.proximitySensor = new MovementSensor(context, Sensor.TYPE_PROXIMITY);
        this.magnetometerSensor = new MovementSensor(context, Sensor.TYPE_MAGNETIC_FIELD);
        this.stepDetectionSensor = new MovementSensor(context, Sensor.TYPE_STEP_DETECTOR);
        // Use GAME_ROTATION_VECTOR (gyro+accel only, no magnetometer)
        // to avoid indoor magnetic field distortion corrupting heading
        this.rotationSensor = new MovementSensor(context, Sensor.TYPE_GAME_ROTATION_VECTOR);
        // Also register TYPE_ROTATION_VECTOR (with magnetometer) for initial heading calibration.
        // We compute the offset between magnetic north and GAME_ROTATION_VECTOR once at startup,
        // then apply it as a fixed correction — giving absolute heading without magnetic drift.
        this.magneticRotationSensor = new MovementSensor(context, Sensor.TYPE_ROTATION_VECTOR);
        this.gravitySensor = new MovementSensor(context, Sensor.TYPE_GRAVITY);
        this.linearAccelerationSensor = new MovementSensor(context, Sensor.TYPE_LINEAR_ACCELERATION);

        // Initialise non-sensor data sources
        this.gnssProcessor = new GNSSDataProcessor(context, locationListener);
        ServerCommunications serverCommunications = new ServerCommunications(context);

        // Initialise utilities
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.pdrProcessing = new PdrProcessing(context);
        this.pathView = new PathView(context, null);
        WiFiPositioning wiFiPositioning = new WiFiPositioning(context);

        // Create internal modules
        this.recorder = new TrajectoryRecorder(appContext, state, serverCommunications, settings);
        this.recorder.setSensorReferences(
                accelerometerSensor, gyroscopeSensor, magnetometerSensor,
                barometerSensor, lightSensor, proximitySensor, rotationSensor);

        this.wifiPositionManager = new WifiPositionManager(wiFiPositioning, recorder);

        // Create fusion manager (particle filter)
        this.fusionManager = new FusionManager();

        long bootTime = SystemClock.uptimeMillis();
        this.eventHandler = new SensorEventHandler(
                state, pdrProcessing, pathView, recorder, bootTime);
        this.eventHandler.setFusionManager(fusionManager);

        // Wire WiFi positioning callback to fusion manager
        this.wifiPositionManager.setFusionManager(fusionManager);

        // Register WiFi observer on WifiPositionManager (not on SensorFusion)
        this.wifiProcessor = new WifiDataProcessor(context);
        wifiProcessor.registerObserver(wifiPositionManager);

        // Initialise BLE scanner and register observer for trajectory recording
        this.bleProcessor = new BleDataProcessor(context);
        bleProcessor.registerObserver(new Observer() {
            @Override
            public void update(Object[] objList) {
                List<BleDevice> bleList = Stream.of(objList)
                        .map(o -> (BleDevice) o).collect(Collectors.toList());
                recorder.addBleFingerprint(bleList);
            }
        });

        // Initialise WiFi RTT manager and register as WiFi scan observer
        this.rttManager = new RttManager(appContext, recorder, wifiProcessor);
        wifiProcessor.registerObserver(rttManager);

        // Initialise BLE RTT estimator and register on BLE scan updates
        this.bleRttManager = new BleRttManager(recorder);
        bleProcessor.registerObserver(bleRttManager);

        if (!rttManager.isRttSupported()) {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(appContext,
                            "WiFi RTT is not supported on this device",
                            Toast.LENGTH_LONG).show());
        }
    }

    //endregion

    //region SensorEventListener

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link SensorEventHandler#handleSensorEvent(SensorEvent)}.</p>
     */
    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        eventHandler.handleSensorEvent(sensorEvent);
    }

    /** {@inheritDoc} */
    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {}

    //endregion

    //region Start/Stop listening

    /**
     * Registers all device listeners and enables updates with the specified sampling rate.
     *
     * <p>Should be called from {@link MainActivity} when resuming the application.</p>
     */
    public void resumeListening() {
        // GAME_ROTATION_VECTOR may change its arbitrary reference frame when
        // listeners are re-registered, so reset heading calibration to force
        // recomputation from the next TYPE_ROTATION_VECTOR samples.
        eventHandler.resetHeadingCalibration();

        // Request 10000μs (100Hz). The device's OS scheduling only delivers ~48Hz
        // at 20000μs. Requesting faster forces the HAL to deliver closer to 50Hz+.
        // Server requires ≥50Hz, rejects >200Hz.
        final int imuPeriodUs = 10000;
        accelerometerSensor.sensorManager.registerListener(this,
                accelerometerSensor.sensor, imuPeriodUs, (int) maxReportLatencyNs);
        accelerometerSensor.sensorManager.registerListener(this,
                linearAccelerationSensor.sensor, imuPeriodUs, (int) maxReportLatencyNs);
        accelerometerSensor.sensorManager.registerListener(this,
                gravitySensor.sensor, imuPeriodUs, (int) maxReportLatencyNs);
        barometerSensor.sensorManager.registerListener(this,
                barometerSensor.sensor, (int) 1e6);
        gyroscopeSensor.sensorManager.registerListener(this,
                gyroscopeSensor.sensor, imuPeriodUs, (int) maxReportLatencyNs);
        lightSensor.sensorManager.registerListener(this,
                lightSensor.sensor, (int) 1e6);
        proximitySensor.sensorManager.registerListener(this,
                proximitySensor.sensor, (int) 1e6);
        magnetometerSensor.sensorManager.registerListener(this,
                magnetometerSensor.sensor, imuPeriodUs, (int) maxReportLatencyNs);
        stepDetectionSensor.sensorManager.registerListener(this,
                stepDetectionSensor.sensor, SensorManager.SENSOR_DELAY_NORMAL);
        rotationSensor.sensorManager.registerListener(this,
                rotationSensor.sensor, (int) 1e6);
        magneticRotationSensor.sensorManager.registerListener(this,
                magneticRotationSensor.sensor, (int) 1e6);
        // Foreground service owns WiFi/BLE scanning during recording.
        if (!recorder.isRecording()) {
            startWirelessCollectors();
        }
        gnssProcessor.startLocationUpdates();
    }

    /**
     * Un-registers all device listeners and pauses data collection.
     *
     * <p>Should be called from {@link MainActivity} when pausing the application.</p>
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
            magneticRotationSensor.sensorManager.unregisterListener(this);
            linearAccelerationSensor.sensorManager.unregisterListener(this);
            gravitySensor.sensorManager.unregisterListener(this);
            stopWirelessCollectors();
            this.gnssProcessor.stopUpdating();
        }
    }

    /**
     * Called by {@link SensorCollectionService} when foreground collection starts.
     * Moves WiFi/BLE scanning responsibility into the service lifecycle while recording.
     */
    public void onCollectionServiceStarted() {
        startWirelessCollectors();
    }

    /**
     * Called by {@link SensorCollectionService} when foreground collection stops.
     * Stops WiFi/BLE scans that were started for recording continuity.
     */
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
            if (DEBUG) System.err.println("WiFi stop failed");
        }
        try {
            if (bleProcessor != null) {
                bleProcessor.stopListening();
            }
        } catch (Exception e) {
            if (DEBUG) System.err.println("BLE stop failed");
        }
    }

    //endregion

    //region Recording lifecycle (delegated to TrajectoryRecorder)

    /**
     * Enables saving sensor values to the trajectory object.
     * Also starts the foreground service to keep data collection alive in the background.
     *
     * @see TrajectoryRecorder#startRecording(PdrProcessing)
     * @see SensorCollectionService
     */
    public void startRecording() {
        recorder.startRecording(pdrProcessing);
        eventHandler.resetBootTime(recorder.getBootTime());

        // Reset fusion for new session
        if (fusionManager != null) {
            fusionManager.reset();

            // Initialize fusion from user-selected start position (more reliable than indoor GNSS)
            if (state.startLocation[0] != 0 || state.startLocation[1] != 0) {
                fusionManager.initializeFromStartLocation(
                        state.startLocation[0], state.startLocation[1]);
            }

            // Load wall constraints from cached floorplan data
            String buildingId = recorder.getSelectedBuildingId();
            if (DEBUG) android.util.Log.i("SensorFusion",
                    "Wall loading: buildingId=" + buildingId
                    + " cacheSize=" + floorplanBuildingCache.size()
                    + " cacheKeys=" + floorplanBuildingCache.keySet());
            FloorplanApiClient.BuildingInfo building = getFloorplanBuilding(buildingId);
            if (building != null && building.getFloorShapesList() != null
                    && !building.getFloorShapesList().isEmpty()) {
                fusionManager.loadMapConstraints(
                        building.getFloorShapesList(),
                        building.getOutlinePolygon());
                if (DEBUG) android.util.Log.i("SensorFusion",
                        "Wall constraints loaded for: " + buildingId
                        + " floors=" + building.getFloorShapesList().size()
                        + " outlinePoints=" + (building.getOutlinePolygon() != null
                                ? building.getOutlinePolygon().size() : 0));
            } else {
                if (DEBUG) android.util.Log.w("SensorFusion",
                        "Primary building not found: building="
                        + (building != null) + " hasShapes="
                        + (building != null && building.getFloorShapesList() != null));
                // Fallback: try the first available building in cache
                for (FloorplanApiClient.BuildingInfo b : getFloorplanBuildings()) {
                    if (DEBUG) android.util.Log.d("SensorFusion",
                            "Fallback check: " + b.getName()
                            + " hasShapes=" + (b.getFloorShapesList() != null)
                            + " shapeCount=" + (b.getFloorShapesList() != null
                                    ? b.getFloorShapesList().size() : 0));
                    if (b.getFloorShapesList() != null
                            && !b.getFloorShapesList().isEmpty()) {
                        fusionManager.loadMapConstraints(
                                b.getFloorShapesList(),
                                b.getOutlinePolygon());
                        if (DEBUG) android.util.Log.i("SensorFusion",
                                "Wall constraints loaded (fallback): " + b.getName());
                        break;
                    }
                }
            }

            // WiFi floor is unreliable (API often returns 0 regardless of actual floor).
            // Location-aware CalDB in RecordingFragment.onCreate() will set the
            // initial floor based on nearby calibration records instead.
            int wifiFloor = wifiPositionManager.getWifiFloor();
            if (DEBUG) android.util.Log.i("SensorFusion",
                    "Fusion start: wifiFloor=" + wifiFloor + " (not used for seeding)");
        }

        // Handover WiFi/BLE scan lifecycle from activity callbacks to foreground service.
        stopWirelessCollectors();

        if (appContext != null) {
            SensorCollectionService.start(appContext);
        }
    }

    /**
     * Pauses or resumes PDR step processing (heading calibration continues).
     *
     * @param paused true to pause, false to resume
     */
    public void setPdrPaused(boolean paused) {
        if (eventHandler != null) eventHandler.setPdrPaused(paused);
    }



    /**
     * Disables saving sensor values and stops the foreground collection service.
     *
     * @see TrajectoryRecorder#stopRecording()
     * @see SensorCollectionService
     */
    public void stopRecording() {
        recorder.stopRecording();
        if (appContext != null) {
            SensorCollectionService.stop(appContext);
        }
    }

    /**
     * Validates the current trajectory against quality thresholds before upload.
     *
     * @return validation result with errors and warnings
     * @see TrajectoryValidator
     */
    public TrajectoryValidator.ValidationResult validateTrajectory() {
        return recorder.validateTrajectory();
    }

    /**
     * Send the trajectory object to servers.
     *
     * @see TrajectoryRecorder#sendTrajectoryToCloud()
     */
    public void sendTrajectoryToCloud() {
        recorder.sendTrajectoryToCloud();
    }

    /**
     * Sets the trajectory name/ID for the current recording session.
     *
     * @param id trajectory name entered by the user
     */
    public void setTrajectoryId(String id) {
        recorder.setTrajectoryId(id);
    }

    /**
     * Gets the trajectory name/ID for the current recording session.
     *
     * @return trajectory name string, or null if not set
     */
    public String getTrajectoryId() {
        return recorder.getTrajectoryId();
    }

    /**
     * Sets the selected building identifier for the current recording session.
     * Used to determine the campaign name when uploading the trajectory.
     *
     * @param buildingId building name from the floorplan API (e.g. "nucleus_building")
     */
    public void setSelectedBuildingId(String buildingId) {
        recorder.setSelectedBuildingId(buildingId);
    }

    /**
     * Gets the selected building identifier for the current recording session.
     *
     * @return building name string, or null if no building was selected
     */
    public String getSelectedBuildingId() {
        return recorder.getSelectedBuildingId();
    }

    /**
     * Caches floorplan API building payloads for use in later fragments.
     *
     * @param buildings buildings returned by floorplan API
     */
    public void setFloorplanBuildings(List<FloorplanApiClient.BuildingInfo> buildings) {
        floorplanBuildingCache.clear();
        if (buildings == null) return;

        for (FloorplanApiClient.BuildingInfo building : buildings) {
            if (building == null || building.getName() == null || building.getName().isEmpty()) {
                continue;
            }
            floorplanBuildingCache.put(building.getName(), building);
        }
    }

    /**
     * Returns a cached floorplan entry by building id.
     *
     * @param buildingId building name from floorplan API
     * @return cached building info, or null if not present
     */
    public FloorplanApiClient.BuildingInfo getFloorplanBuilding(String buildingId) {
        if (buildingId == null || buildingId.isEmpty()) {
            return null;
        }
        return floorplanBuildingCache.get(buildingId);
    }

    /**
     * Returns all cached floorplan entries.
     *
     * @return list copy of cached building info objects
     */
    public List<FloorplanApiClient.BuildingInfo> getFloorplanBuildings() {
        return new ArrayList<>(floorplanBuildingCache.values());
    }

    /**
     * Writes the initial position and heading into the trajectory protobuf.
     * Should be called after startRecording() and setStartGNSSLatitude().
     */
    public void writeInitialMetadata() {
        recorder.writeInitialMetadata();
    }

    /**
     * Adds a test point (user ground truth marker) to the trajectory.
     */
    public void addTestPointToProto(long pressTimestampMs, double lat, double lng) {
        recorder.addTestPoint(pressTimestampMs, lat, lng);
    }

    //endregion

    //region Getters/Setters

    /**
     * Getter function for core location data.
     *
     * @param start set true to get the initial location
     * @return longitude and latitude data in a float[2].
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
     * Setter function for core location data.
     *
     * @param startPosition contains the initial location set by the user
     */
    public void setStartGNSSLatitude(float[] startPosition) {
        state.startLocation[0] = startPosition[0];
        state.startLocation[1] = startPosition[1];
    }

    /**
     * Function to redraw path in corrections fragment.
     *
     * @param scalingRatio new size of path due to updated step length
     */
    public void redrawPath(float scalingRatio) {
        pathView.redraw(scalingRatio);
    }

    /**
     * Getter function for average step length.
     *
     * @return average step length of total PDR.
     */
    public float passAverageStepLength() {
        return pdrProcessing.getAverageStepLength();
    }

    /**
     * Getter function for device orientation.
     *
     * @return orientation of device in radians.
     */
    public float passOrientation() {
        return state.orientation[0];
    }

    /**
     * Return most recent sensor readings.
     *
     * @return Map of {@link SensorTypes} to float array of most recent values.
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

    /**
     * Return the most recent list of WiFi names and levels.
     *
     * @return list of Wifi objects.
     */
    public List<Wifi> getWifiList() {
        return wifiPositionManager.getWifiList();
    }

    /**
     * Get information about all the sensors registered in SensorFusion.
     *
     * @return List of SensorInfo objects containing name, resolution, power, etc.
     */
    public List<SensorInfo> getSensorInfos() {
        List<SensorInfo> sensorInfoList = new ArrayList<>();
        sensorInfoList.add(this.accelerometerSensor.sensorInfo);
        sensorInfoList.add(this.barometerSensor.sensorInfo);
        sensorInfoList.add(this.gyroscopeSensor.sensorInfo);
        sensorInfoList.add(this.lightSensor.sensorInfo);
        sensorInfoList.add(this.proximitySensor.sensorInfo);
        sensorInfoList.add(this.magnetometerSensor.sensorInfo);
        return sensorInfoList;
    }

    /**
     * Registers the caller observer to receive updates from the server instance.
     *
     * @param observer Instance implementing {@link Observer} who wants to be notified of
     *                 events relating to sending and receiving trajectories.
     */
    public void registerForServerUpdate(Observer observer) {
        recorder.getServerCommunications().registerObserver(observer);
    }

    /**
     * Get the estimated elevation value in meters calculated by the PDR class.
     *
     * @return float of the estimated elevation in meters.
     */
    public float getElevation() {
        return state.elevation;
    }

    /**
     * Get an estimate whether the user is currently taking an elevator.
     *
     * @return true if the PDR estimates the user is in an elevator, false otherwise.
     */
    public boolean getElevator() {
        return state.elevator;
    }

    /**
     * Estimates position of the phone based on proximity and light sensors.
     *
     * @return int 1 if the phone is by the ear, int 0 otherwise.
     */
    public int getHoldMode() {
        int proximityThreshold = 1, lightThreshold = 100;
        if (state.proximity < proximityThreshold && state.light > lightThreshold) {
            return 1;
        } else {
            return 0;
        }
    }

    /**
     * Returns the user position obtained using WiFi positioning.
     *
     * @return {@link LatLng} corresponding to user's position.
     */
    public LatLng getLatLngWifiPositioning() {
        return wifiPositionManager.getLatLngWifiPositioning();
    }

    /**
     * Returns the current floor the user is on, obtained using WiFi positioning.
     *
     * @return current floor number.
     */
    public int getWifiFloor() {
        return wifiPositionManager.getWifiFloor();
    }

    /**
     * Ensures WiFi and BLE scanning is running. Safe to call multiple times.
     * Called from StartLocationFragment so WiFi floor data is cached before recording.
     */
    public void ensureWirelessScanning() {
        startWirelessCollectors();
    }

    /**
     * Sets a callback to be notified when a WiFi floor response arrives.
     * Used by autofloor toggle to re-seed initial floor from WiFi.
     *
     * @param callback callback to invoke on WiFi floor response, or null to clear
     */
    public void setWifiFloorCallback(WifiPositionManager.WifiFloorCallback callback) {
        if (wifiPositionManager != null) {
            wifiPositionManager.setWifiFloorCallback(callback);
        }
    }

    /**
     * Sets the initial floor for PDR processing.
     * Called when floor is known from calibration DB or other sources.
     */
    public void setInitialFloor(int floor) {
        if (pdrProcessing != null) {
            pdrProcessing.setInitialFloor(floor);
        }
    }

    /**
     * Reseeds the floor to a WiFi-determined value during recording.
     * Properly resets both PdrProcessing baro baseline (using current smoothed
     * altitude) and FusionManager/ParticleFilter floor state.
     * Use this instead of setInitialFloor + resetBaroBaseline to avoid the
     * ordering bug where resetBaroBaseline sees floorsChanged=0.
     *
     * @param floor logical floor number (0=GF, 1=F1, …)
     */
    public void reseedFloor(int floor) {
        if (pdrProcessing != null) {
            pdrProcessing.reseedFloor(floor);
        }
        if (fusionManager != null) {
            fusionManager.reseedFloor(floor);
        }
    }

    /**
     * Returns the current floor as tracked by the barometric floor detector
     * in PdrProcessing. Accounts for initialFloorOffset, unlike raw elevation.
     *
     * @return logical floor number (0=GF, 1=F1, …)
     */
    public int getBaroFloor() {
        return pdrProcessing != null ? pdrProcessing.getCurrentFloor() : 0;
    }

    /**
     * Overrides the barometric floor height used by PdrProcessing.
     * Call when the building is identified so floor detection uses the
     * correct barometric altitude difference for that building.
     */
    public void setBaroFloorHeight(float height) {
        if (pdrProcessing != null) {
            pdrProcessing.setBaroFloorHeight(height);
        }
    }

    /**
     * Resets the barometric baseline after a confirmed floor change.
     * This prevents long-term baro drift from accumulating.
     *
     * @param confirmedFloor the confirmed floor number
     */
    public void resetBaroBaseline(int confirmedFloor) {
        if (pdrProcessing != null) {
            pdrProcessing.resetBaroBaseline(confirmedFloor);
        }
    }

    /**
     * Returns the fusion manager for accessing the fused position.
     */
    public FusionManager getFusionManager() {
        return fusionManager;
    }

    /**
     * Utility function to log the event frequency of each sensor.
     */
    public void logSensorFrequencies() {
        eventHandler.logSensorFrequencies();
    }

    //endregion

    //region Location listener

    /**
     * Location listener class to receive updates from the location manager.
     * Writes position data to {@link SensorState} and GNSS readings to
     * {@link TrajectoryRecorder}.
     */
    class MyLocationListener implements LocationListener {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            state.latitude = (float) location.getLatitude();
            state.longitude = (float) location.getLongitude();
            recorder.addGnssData(location);

            // Only feed GPS provider to the particle filter.
            if (fusionManager != null && location.getProvider() != null
                    && location.getProvider().equals(android.location.LocationManager.GPS_PROVIDER)) {
                fusionManager.onGnssPosition(
                        location.getLatitude(), location.getLongitude(),
                        location.getAccuracy());
            }
        }
    }

    //endregion
}
