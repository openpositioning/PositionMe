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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.service.SensorCollectionService;
import com.openpositioning.PositionMe.utils.BuildingPolygon;
import com.openpositioning.PositionMe.utils.IndoorFloorController;
import com.openpositioning.PositionMe.utils.IndoorSpatialConstraintModel;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.ParticleFilterEngine;
import com.openpositioning.PositionMe.utils.PdrProcessing;
import com.openpositioning.PositionMe.utils.TrajectoryValidator;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * CW2 integration hub. SensorFusion is the main data gathering and processing class of the
 * application and ties together sensing, fusion, floor estimation and map-facing outputs.
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
    private MovementSensor gameRotationSensor;
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
    private ParticleFilterEngine particleFilterEngine;
    private IndoorSpatialConstraintModel indoorSpatialConstraintModel;
    private IndoorFloorController indoorFloorController;

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
        this.rotationSensor = new MovementSensor(context, Sensor.TYPE_ROTATION_VECTOR);
        this.gameRotationSensor = new MovementSensor(context, Sensor.TYPE_GAME_ROTATION_VECTOR);
        this.gravitySensor = new MovementSensor(context, Sensor.TYPE_GRAVITY);
        this.linearAccelerationSensor = new MovementSensor(context, Sensor.TYPE_LINEAR_ACCELERATION);

        // Initialise non-sensor data sources
        this.gnssProcessor = new GNSSDataProcessor(context, locationListener);
        ServerCommunications serverCommunications = new ServerCommunications(context);

        // Initialise utilities
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.pdrProcessing = new PdrProcessing(context);
        this.pathView = new PathView(context, null);
        this.indoorSpatialConstraintModel = new IndoorSpatialConstraintModel();
        this.indoorFloorController = new IndoorFloorController(indoorSpatialConstraintModel);
        this.particleFilterEngine = new ParticleFilterEngine(indoorSpatialConstraintModel);
        WiFiPositioning wiFiPositioning = new WiFiPositioning(context);

        // Create internal modules
        this.recorder = new TrajectoryRecorder(appContext, state, serverCommunications, settings);
        this.recorder.setSensorReferences(
                accelerometerSensor, gyroscopeSensor, magnetometerSensor,
                barometerSensor, lightSensor, proximitySensor, rotationSensor);

        this.wifiPositionManager = new WifiPositionManager(
                wiFiPositioning,
                recorder,
                new WifiPositionManager.PositionUpdateListener() {
                    @Override
                    public void onWifiPositionUpdate(LatLng wifiLocation, int floor) {
                        if (eventHandler != null) {
                            eventHandler.updateHeadingCalibrationFromWifi(
                                    wifiLocation,
                                    System.currentTimeMillis()
                            );
                        }
                        if (particleFilterEngine != null) {
                            particleFilterEngine.onWifiObservation(
                                    wifiLocation,
                                    floor,
                                    state.orientation[0],
                                    System.currentTimeMillis()
                            );
                        }
                    }
                });

        long bootTime = SystemClock.uptimeMillis();
        this.eventHandler = new SensorEventHandler(
                state, pdrProcessing, pathView, recorder, bootTime, particleFilterEngine);

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
        accelerometerSensor.sensorManager.registerListener(this,
                accelerometerSensor.sensor, 10000, (int) maxReportLatencyNs);
        accelerometerSensor.sensorManager.registerListener(this,
                linearAccelerationSensor.sensor, 10000, (int) maxReportLatencyNs);
        accelerometerSensor.sensorManager.registerListener(this,
                gravitySensor.sensor, 10000, (int) maxReportLatencyNs);
        barometerSensor.sensorManager.registerListener(this,
                barometerSensor.sensor, (int) 1e6);
        gyroscopeSensor.sensorManager.registerListener(this,
                gyroscopeSensor.sensor, 10000, (int) maxReportLatencyNs);
        lightSensor.sensorManager.registerListener(this,
                lightSensor.sensor, (int) 1e6);
        proximitySensor.sensorManager.registerListener(this,
                proximitySensor.sensor, (int) 1e6);
        magnetometerSensor.sensorManager.registerListener(this,
                magnetometerSensor.sensor, 10000, (int) maxReportLatencyNs);
        stepDetectionSensor.sensorManager.registerListener(this,
                stepDetectionSensor.sensor, SensorManager.SENSOR_DELAY_NORMAL);
        rotationSensor.sensorManager.registerListener(this,
                rotationSensor.sensor, (int) 1e6);
        if (gameRotationSensor != null && gameRotationSensor.sensor != null) {
            gameRotationSensor.sensorManager.registerListener(this,
                    gameRotationSensor.sensor, (int) 1e6);
        }
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
            if (gameRotationSensor != null && gameRotationSensor.sensorManager != null) {
                gameRotationSensor.sensorManager.unregisterListener(this);
            }
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
            System.err.println("WiFi stop failed");
        }
        try {
            if (bleProcessor != null) {
                bleProcessor.stopListening();
            }
        } catch (Exception e) {
            System.err.println("BLE stop failed");
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
        if (particleFilterEngine != null) {
            particleFilterEngine.onPdrStreamReset();
        }

        // Handover WiFi/BLE scan lifecycle from activity callbacks to foreground service.
        stopWirelessCollectors();

        if (appContext != null) {
            SensorCollectionService.start(appContext);
        }
    }

    /**
     * Disables saving sensor values to the trajectory object.
     * Also stops the foreground service since background collection is no longer needed.
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
     * Resets the live localisation state before starting a new recording flow.
     */
    public void resetLivePositioningState() {
        if (pdrProcessing != null) {
            pdrProcessing.resetPDR();
        }
        if (particleFilterEngine != null) {
            particleFilterEngine.reset();
        }
        if (indoorFloorController != null) {
            indoorFloorController.reset();
        }
        state.startLocation[0] = 0f;
        state.startLocation[1] = 0f;
        setSelectedBuildingId(null);
        floorplanBuildingCache.clear();
    }

    /**
     * Returns true once the system has auto-initialised from a reliable GNSS/WiFi observation.
     */
    public boolean hasAutomaticStartFix() {
        return getBestAvailableStartPosition() != null;
    }

    /**
     * Returns the current fused position used by the recording map.
     */
    public LatLng getCurrentFusedPosition() {
        return particleFilterEngine == null ? null : particleFilterEngine.getCurrentLatLng();
    }

    public long getCurrentFusedPositionVersion() {
        return particleFilterEngine == null ? 0L : particleFilterEngine.getCurrentPositionVersion();
    }

    public List<LatLng> getFusedTrack() {
        return particleFilterEngine == null
                ? new ArrayList<>()
                : particleFilterEngine.getFusedHistory();
    }

    public long getFusedTrackVersion() {
        return particleFilterEngine == null ? 0L : particleFilterEngine.getFusedHistoryVersion();
    }

    public List<LatLng> getRecentGnssTrail() {
        return particleFilterEngine == null
                ? new ArrayList<>()
                : particleFilterEngine.getRecentGnssTail();
    }

    public List<LatLng> getRecentWifiTrail() {
        return particleFilterEngine == null
                ? new ArrayList<>()
                : particleFilterEngine.getRecentWifiTail();
    }

    public List<LatLng> getRecentPdrTrail() {
        return particleFilterEngine == null
                ? new ArrayList<>()
                : particleFilterEngine.getRecentPdrTail();
    }

    public long getObservationTrailsVersion() {
        return particleFilterEngine == null ? 0L : particleFilterEngine.getObservationTrailsVersion();
    }

    /**
     * Applies a post-fusion map correction back into the filter state.
     *
     * <p>This is used when the UI or map-matching layer has to clip the displayed position to a
     * legal indoor point and the particle filter should adopt that correction as its new state.</p>
     */
    public void applyMapConstrainedPosition(LatLng correctedPosition) {
        if (particleFilterEngine != null) {
            particleFilterEngine.overrideCurrentPosition(
                    correctedPosition,
                    System.currentTimeMillis()
            );
        }
    }

    public int getCurrentLogicalFloor() {
        return particleFilterEngine == null ? 0 : particleFilterEngine.getCurrentLogicalFloor();
    }

    /**
     * Forces the current logical floor and re-anchors the floor controller to the current
     * elevation. This is used after a manual floor switch on the UI.
     */
    public void setCurrentLogicalFloor(int logicalFloor) {
        if (particleFilterEngine != null) {
            particleFilterEngine.setCurrentLogicalFloor(logicalFloor);
        }
        if (indoorFloorController != null) {
            indoorFloorController.confirmManualFloor(state.elevation, logicalFloor);
        }
    }

    public float getCurrentFloorHeight() {
        return particleFilterEngine == null ? 0f : particleFilterEngine.getCurrentFloorHeight();
    }

    public String getCurrentFloorDisplayName() {
        return particleFilterEngine == null ? "0" : particleFilterEngine.getCurrentFloorDisplayName();
    }

    /**
     * Returns the floor that should currently be displayed on the map.
     *
     * <p>Before the floor controller confirms an elevation anchor, WiFi floor observations are
     * allowed to drive the displayed floor so the map can show a plausible start floor earlier.
     * After anchoring, the fused floor becomes authoritative.</p>
     */
    public int getPreferredDisplayLogicalFloor() {
        int fusedFloor = getCurrentLogicalFloor();
        if (indoorFloorController != null && indoorFloorController.hasConfirmedAnchor()) {
            return fusedFloor;
        }

        if (getLatLngWifiPositioning() != null) {
            return getWifiFloor();
        }
        return fusedFloor;
    }

    public boolean isNearIndoorFeature(LatLng position, String indoorType, double radiusMeters) {
        return particleFilterEngine != null
                && particleFilterEngine.isNearIndoorFeature(position, indoorType, radiusMeters);
    }

    public boolean isIndoorContextActive() {
        return particleFilterEngine != null && particleFilterEngine.isIndoorContextActive();
    }

    /**
     * Runs the indoor floor controller and propagates any accepted floor change back to the
     * particle filter.
     */
    public Integer evaluateIndoorFloorChange(LatLng currentPosition, long timestampMillis) {
        if (indoorFloorController == null) {
            return null;
        }
        boolean transitionZoneHint = getElevator()
                || isNearIndoorFeature(currentPosition, "lift", 3.5)
                || isNearIndoorFeature(currentPosition, "stairs", 3.5);
        Integer resolvedFloor = indoorFloorController.evaluate(
                currentPosition,
                state.elevation,
                getLatLngWifiPositioning() != null ? getWifiFloor() : null,
                transitionZoneHint,
                timestampMillis
        );
        if (resolvedFloor != null && particleFilterEngine != null) {
            particleFilterEngine.setCurrentLogicalFloor(resolvedFloor);
        }
        return resolvedFloor;
    }

    /**
     * Copies the auto-initialised fused position into trajectory metadata fields.
     */
    public boolean prepareAutomaticStart() {
        LatLng startPosition = getBestAvailableStartPosition();
        if (startPosition == null) {
            return false;
        }

        setStartGNSSLatitude(new float[]{
                (float) startPosition.latitude,
                (float) startPosition.longitude
        });

        if (getSelectedBuildingId() == null || getSelectedBuildingId().isEmpty()) {
            String inferredBuilding = inferBuildingIdForPosition(startPosition);
            if (inferredBuilding != null) {
                setSelectedBuildingId(inferredBuilding);
            }
        }
        return true;
    }

    @Nullable
    private LatLng getBestAvailableStartPosition() {
        LatLng fusedPosition = getCurrentFusedPosition();
        if (fusedPosition != null) {
            return fusedPosition;
        }

        float[] gnssPosition = getGNSSLatitude(false);
        if (gnssPosition != null && (gnssPosition[0] != 0f || gnssPosition[1] != 0f)) {
            return new LatLng(gnssPosition[0], gnssPosition[1]);
        }
        return null;
    }

    /**
     * Chooses the building whose outline contains the given position, or the closest cached
     * building if the user is just outside the polygon boundary.
     */
    public String inferBuildingIdForPosition(LatLng position) {
        if (indoorSpatialConstraintModel != null) {
            String inferred = indoorSpatialConstraintModel.inferBuildingId(position);
            if (inferred != null) {
                return inferred;
            }
        }

        if (position == null) {
            return null;
        }

        String nearestBuildingId = null;
        double nearestDistanceMeters = Double.MAX_VALUE;

        for (FloorplanApiClient.BuildingInfo building : floorplanBuildingCache.values()) {
            if (building == null || building.getName() == null) {
                continue;
            }

            List<LatLng> outline = building.getOutlinePolygon();
            if (outline != null && outline.size() >= 3
                    && BuildingPolygon.pointInPolygon(position, outline)) {
                return building.getName();
            }

            LatLng center = building.getCenter();
            double distanceMeters = UtilFunctions.distanceBetweenPoints(position, center);
            if (distanceMeters < nearestDistanceMeters) {
                nearestDistanceMeters = distanceMeters;
                nearestBuildingId = building.getName();
            }
        }

        return nearestDistanceMeters <= 80.0 ? nearestBuildingId : null;
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
     * Returns the orientation that should be exposed to the map layer.
     *
     * <p>When the particle filter has a reliable motion heading, that heading is preferred. When
     * motion heading is weak, the method falls back to the raw sensor orientation so the UI can
     * still react while the user rotates in place.</p>
     *
     * @return orientation of device in radians.
     */
    public float passOrientation() {
        if (particleFilterEngine != null && particleFilterEngine.isInitialized()) {
            if (!particleFilterEngine.hasReliableMotionHeading()
                    && !Float.isNaN(state.orientation[0])) {
                return state.orientation[0];
            }
            return (float) particleFilterEngine.getCurrentDisplayHeadingRad();
        }
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
        int observedFloor = wifiPositionManager.getWifiFloor();
        if (indoorSpatialConstraintModel != null) {
            LatLng contextPosition = getCurrentFusedPosition();
            if (contextPosition == null) {
                contextPosition = getLatLngWifiPositioning();
            }
            if (contextPosition == null) {
                float[] gnssPosition = getGNSSLatitude(false);
                if (gnssPosition != null && (gnssPosition[0] != 0f || gnssPosition[1] != 0f)) {
                    contextPosition = new LatLng(gnssPosition[0], gnssPosition[1]);
                }
            }
            if (contextPosition != null) {
                indoorSpatialConstraintModel.updatePosition(contextPosition);
            }
            return indoorSpatialConstraintModel.normalizeExternalFloorObservation(observedFloor);
        }
        return observedFloor;
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
            if (eventHandler != null) {
                eventHandler.updateHeadingCalibrationFromGnss(location);
            }
            if (particleFilterEngine != null) {
                particleFilterEngine.onGnssObservation(location, state.orientation[0]);
            }
        }
    }

    //endregion
}
