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
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
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
 * Main data gathering and processing class for the application. Singleton - all fragments
 * share the same instance via SensorFusion.getInstance().
 *
 * Delegates to: SensorState (data holder), SensorEventHandler (sensor dispatch),
 * TrajectoryRecorder (recording and protobuf), WifiPositionManager (WiFi positioning).
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
    private ParticleFilter particleFilter;
    private MapMatcher mapMatcher;

    // Movement sensor instances (lifecycle managed here)
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

    // Sensor registration latency setting
    long maxReportLatencyNs = 0;

    // Floorplan API cache (latest result from start-location step)
    private final Map<String, FloorplanApiClient.BuildingInfo> floorplanBuildingCache =
            new HashMap<>();

    // Max distances from a staircase / lift for a barometer floor change to be accepted
    private static final float STAIR_PROXIMITY_THRESHOLD_M = 5.0f;
    private static final float LIFT_PROXIMITY_THRESHOLD_M  = 50.0f;
    // Elevation recorded when the current floor was last confirmed; NaN before first barometer reading
    private float lastFloorElevation = Float.NaN;
    // WiFi floor number at the last confirmed floor level; MIN_VALUE until first WiFi fix
    private int lastAcceptedWifiFloor = Integer.MIN_VALUE;
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
     * Sets up all sensors, internal modules, and data processors.
     * Must be called once with an application context before anything else.
     *
     * @param context application context
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
        this.gravitySensor = new MovementSensor(context, Sensor.TYPE_GRAVITY);
        this.linearAccelerationSensor = new MovementSensor(context, Sensor.TYPE_LINEAR_ACCELERATION);

        // Initialise non-sensor data sources
        this.gnssProcessor = new GNSSDataProcessor(context, locationListener);
        ServerCommunications serverCommunications = new ServerCommunications(context);

        // Initialise utilities
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.pdrProcessing = new PdrProcessing(context);
        this.pathView = new PathView(context, null);
        WiFiPositioning wiFiPositioning = new WiFiPositioning(context, null);

        // Create internal modules
        this.recorder = new TrajectoryRecorder(appContext, state, serverCommunications, settings);
        this.recorder.setSensorReferences(
                accelerometerSensor, gyroscopeSensor, magnetometerSensor,
                barometerSensor, lightSensor, proximitySensor, rotationSensor);

        this.wifiPositionManager = new WifiPositionManager(wiFiPositioning, recorder);

        // Initialise particle filter
        this.particleFilter = new ParticleFilter();

        // Create the map matcher and give the particle filter a reference to it.
        // MapMatcher handles floor detection and stair/lift lookups to help determine whether near stairs or lift.
        this.mapMatcher = new MapMatcher(this);
        particleFilter.setMapMatcher(mapMatcher);

        wiFiPositioning.setParticleFilter(particleFilter);

        long bootTime = SystemClock.uptimeMillis();
        this.eventHandler = new SensorEventHandler(
                state, pdrProcessing, pathView, recorder, particleFilter, bootTime);

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
     * Forwards sensor events to SensorEventHandler for processing.
     * On each barometer reading while recording, also checks whether enough elevation
     * change has accumulated to trigger a floor step.
     */
    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        eventHandler.handleSensorEvent(sensorEvent);
        // Check for floor changes on every pressure update
        if (sensorEvent.sensor.getType() == Sensor.TYPE_PRESSURE && recorder.isRecording()) {
            checkAndApplyFloorChange(state.elevation);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {}

    //endregion

    //region Start/Stop listening

    /**
     * Registers all device listeners. Called from MainActivity on resume.
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
        // Foreground service owns WiFi/BLE scanning during recording.
        if (!recorder.isRecording()) {
            startWirelessCollectors();
        }
        gnssProcessor.startLocationUpdates();
    }

    /**
     * Unregisters all device listeners. Called from MainActivity on pause.
     * Does nothing while a recording is in progress.
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
        particleFilter.tryInitialise();
        // Reset the floor-change baselines for the new recording session
        lastFloorElevation = Float.NaN;
        lastAcceptedWifiFloor = Integer.MIN_VALUE;

        // Attempt to load the building map now that the particle filter is ready
        tryTriggerMapMatcher(getSelectedBuildingId());

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

        // Cache is now populated - try to load the building map
        tryTriggerMapMatcher(getSelectedBuildingId());
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
     * Loads the building map into MapMatcher once the particle filter is ready,
     * the origin is set, and the floorplan cache is populated. Called from both
     * startRecording() and setFloorplanBuildings() since either can arrive first.
     *
     * @param preferredBuildingId building name hint, or null to auto-detect
     */
    private void tryTriggerMapMatcher(String preferredBuildingId) {
        if (mapMatcher == null) return;
        if (!particleFilter.isInitialised()) return;
        if (particleFilter.getOrigin() == null) return;
        if (floorplanBuildingCache.isEmpty()) return;
        mapMatcher.tryLoadBuilding(preferredBuildingId, particleFilter.getOrigin());
        Log.d("Debug", "MapMatcher ready: " + mapMatcher.isInitialised());
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
     * Returns the best estimated position of the user from the particle filter.
     * Returns {@code null} if the filter has not yet initialised, in which case
     * the caller should fall back to the PDR-derived absolute position.
     *
     * @return particle filter position estimate, or {@code null} if not yet initialised.
     */
    public LatLng getFusedPosition() {
        if (particleFilter != null && particleFilter.isInitialised()) {
            return particleFilter.getEstimatedPosition();
        }
        return null;
    }

    /**
     * Feeds a WiFi position fix into the particle filter as a correction measurement.
     * WiFi is more reliable indoors than GNSS, so this prevents PDR drift accumulating
     * in environments where GNSS accuracy is too poor to pass the filter's threshold.
     *
     * @param wifiLatLng WiFi-derived position from the OpenPositioning API.
     */
    public void correctWithWifiPosition(LatLng wifiLatLng) {
        if (particleFilter != null && particleFilter.isInitialised() && wifiLatLng != null) {
            particleFilter.updateWeights(wifiLatLng, 8.0f);
        }
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
     * Utility function to log the event frequency of each sensor.
     */
    public void logSensorFrequencies() {
        eventHandler.logSensorFrequencies();
    }

    /**
     * Returns the current floor as a WiFi-scale integer (0=GF, 1=F1, -1=LG etc.)
     * for use by the floor switch in TrajectoryMapFragment.
     *
     * When MapMatcher is active, getLikelyFloorIndex() returns an API index that
     * already has the building bias baked in. We subtract it here so the caller's
     * setCurrentFloor(x, autoFloor=true) can add it back without double-counting.
     * Falls back to the raw WiFi floor if MapMatcher is not loaded yet.
     */
    public int getMapMatcherFloor() {
        if (mapMatcher != null && mapMatcher.isInitialised()) {
            return mapMatcher.getLikelyFloorIndex() - mapMatcher.getAutoFloorBias();
        }
        return getWifiFloor();
    }

    //endregion

    //region Floor change gating

    /**
     * Called on every barometer reading while recording.
     *
     * Rule 1: if WiFi reports a new floor since last check, snap the map to match immediately if near stairs or lift.
     * Rule 2: if accumulated elevation change exceeds one floor height (per building), step
     *         the floor up or down. Only fires when near a staircase or lift. Uses
     *         state.elevator to classify the transition as lift or stairs.
     */
    private void checkAndApplyFloorChange(float elevation) {
        if (mapMatcher == null || !mapMatcher.isInitialised()) return;

        // Seed reference on first call
        if (Float.isNaN(lastFloorElevation)) {
            lastFloorElevation = elevation;
            lastAcceptedWifiFloor = getWifiFloor();
            return;
        }

        int currentWifiFloor = getWifiFloor();

        // Rule 1: WiFi floor changed - snap the map to match and reset the elevation baseline
        if (lastAcceptedWifiFloor != Integer.MIN_VALUE
                && currentWifiFloor != lastAcceptedWifiFloor) {
            int wifiApiFloor = mapMatcher.physicalFloorToApiIndex(currentWifiFloor);
            if (wifiApiFloor >= 0) {
                mapMatcher.setCurrentFloor(wifiApiFloor);
                Log.i("SensorFusion", "Floor snapped to WiFi floor " + wifiApiFloor
                        + " (physical=" + currentWifiFloor + ")");
            } else {
                mapMatcher.setCurrentFloor(-1); // unknown floor - clear override and let WiFi drive
            }
            lastFloorElevation = elevation;
            lastAcceptedWifiFloor = currentWifiFloor;
            return;
        }

        // Rule 2: Barometer - checks accumulated elevation change against the building's floor
        // height. Only accepts a floor change if the user is physically near a staircase or
        // lift from the map. Floor changes in the middle of a room are ignored.
        float floorHeight = mapMatcher.getFloorHeight();
        float elevationDelta = elevation - lastFloorElevation;
        int floorsToMove = (int) (Math.abs(elevationDelta) / floorHeight);
        if (floorsToMove == 0) return;

        // Only proceed if the user is physically near a staircase or lift
        if (particleFilter == null || !particleFilter.isInitialised()) return;
        int currentApiFloor = mapMatcher.getLikelyFloorIndex();
        float[] pos = particleFilter.getEstimatedLocalPosition();
        boolean nearLift = isNearAny(pos,
                mapMatcher.getLiftCentersForFloor(currentApiFloor), LIFT_PROXIMITY_THRESHOLD_M);
        boolean nearStairs = isNearAny(pos,
                mapMatcher.getStairCentersForFloor(currentApiFloor), STAIR_PROXIMITY_THRESHOLD_M);
        if (!nearLift && !nearStairs) {
            Log.d("SensorFusion", "Barometer floor change skipped: not near a lift or stairs");
            return;
        }
        // Uses state.elevator (the PDR movement model) together with proximity to decide
        // whether the user is in a lift or on stairs. state.elevator is true when vertical
        // acceleration dominates and step activity is low - the lift pattern.
        boolean movementModelSaysLift = state.elevator;
        String transitionType;
        if (nearLift && movementModelSaysLift) {
            transitionType = "lift";
        } else if (nearStairs && !movementModelSaysLift) {
            transitionType = "stairs";
        } else {
            // Fallback: proximity wins when movement model and geometry disagree
            transitionType = nearLift ? "lift" : "stairs";
        }

        int direction = elevationDelta > 0 ? 1 : -1;
        int newApiFloor = currentApiFloor;
        for (int i = 0; i < floorsToMove; i++) {
            int next = mapMatcher.getAdjacentFloorIndex(newApiFloor, direction);
            if (next == newApiFloor) break; // already at the top or bottom - stop
            newApiFloor = next;
        }

        if (newApiFloor != currentApiFloor) {
            mapMatcher.setCurrentFloor(newApiFloor);
            // Advance reference by the consumed distance, preserving the remainder
            lastFloorElevation += floorsToMove * floorHeight * direction;
            Log.i("SensorFusion", "Floor changed to " + newApiFloor
                    + " via " + transitionType + " (barometer, " + floorsToMove
                    + " floor(s), delta=" + elevationDelta + "m)");
        }
    }

    /**
     * Returns true if the estimated position is near a staircase or lift on the current floor.
     * Called by TrajectoryMapFragment before applying an auto floor change.
     */
    public boolean isNearTransition() {
        if (mapMatcher == null || !mapMatcher.isInitialised()) return false;
        if (particleFilter == null || !particleFilter.isInitialised()) return false;
        int floor = mapMatcher.getLikelyFloorIndex();
        float[] pos = particleFilter.getEstimatedLocalPosition();
        return isNearAny(pos, mapMatcher.getStairCentersForFloor(floor), STAIR_PROXIMITY_THRESHOLD_M)
                || isNearAny(pos, mapMatcher.getLiftCentersForFloor(floor), LIFT_PROXIMITY_THRESHOLD_M);
    }

    /** Returns true if pos is within threshold meters of any centre in the list. */
    private boolean isNearAny(float[] pos, List<float[]> centers, float threshold) {
        if (centers == null || pos == null) return false;
        for (float[] c : centers) {
            float dx = pos[0] - c[0], dy = pos[1] - c[1];
            if (Math.sqrt(dx * dx + dy * dy) <= threshold) return true;
        }
        return false;
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

            // Update particle weights with GNSS measurement
            if (particleFilter.isInitialised()) {
                LatLng gnssLatLng = new LatLng(location.getLatitude(), location.getLongitude());
                float accuracy = location.getAccuracy();
                particleFilter.updateWeights(gnssLatLng, accuracy);
            }
        }
    }

    //endregion
}