package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.utils.PdrProcessing;
import com.openpositioning.PositionMe.utils.TrajectoryValidator;

import com.google.protobuf.ByteString;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Manages the recording lifecycle, protobuf trajectory construction, and trajectory upload.
 *
 * <p>Encapsulates the start/stop recording logic, the 10ms {@link TimerTask} that periodically
 * writes sensor data into the trajectory protobuf, and the upload to the server.</p>
 *
 * @see SensorFusion the singleton facade that delegates recording operations here
 * @see SensorState  the shared sensor data holder read by the internal TimerTask
 */
public class TrajectoryRecorder {

    private static final long TIME_CONST = 10;
    public static final float FILTER_COEFFICIENT = 0.96f;

    private final SensorState state;
    private final ServerCommunications serverCommunications;
    private final SharedPreferences settings;
    private final Context appContext;

    // Recording state
    private volatile boolean saveRecording = false;
    private Traj.Trajectory.Builder trajectory;
    private String trajectoryId;
    private String selectedBuildingId;
    private Timer storeTrajectoryTimer;
    private long absoluteStartTime;
    private long bootTime;
    private int counter;
    private int secondCounter;
    private float filterCoefficient;

    // WiFi fingerprint dedup
    private int lastWifiFingerprintHash = 0;
    private final Set<Long> recordedApMacs = new HashSet<>();

    // BLE fingerprint dedup
    private int lastBleFingerprintHash = 0;
    private final Set<String> recordedBleMacs = new HashSet<>();

    // WakeLock
    private PowerManager.WakeLock wakeLock;

    // Sensor references for info builders and null checks in TimerTask
    private MovementSensor accelerometerSensor;
    private MovementSensor gyroscopeSensor;
    private MovementSensor magnetometerSensor;
    private MovementSensor barometerSensor;
    private MovementSensor lightSensor;
    private MovementSensor proximitySensor;
    private MovementSensor rotationSensor;

    /**
     * Creates a new TrajectoryRecorder.
     *
     * @param appContext            application context for WakeLock
     * @param state                 shared sensor state holder
     * @param serverCommunications  server communication instance for uploading
     * @param settings              shared preferences for filter coefficient
     */
    public TrajectoryRecorder(Context appContext, SensorState state,
                              ServerCommunications serverCommunications,
                              SharedPreferences settings) {
        this.appContext = appContext;
        this.state = state;
        this.serverCommunications = serverCommunications;
        this.settings = settings;
        this.storeTrajectoryTimer = new Timer();

        PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        this.wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag");
    }

    /**
     * Sets references to movement sensors needed for trajectory metadata and TimerTask null checks.
     */
    public void setSensorReferences(MovementSensor accel, MovementSensor gyro,
                                    MovementSensor mag, MovementSensor baro,
                                    MovementSensor light, MovementSensor prox,
                                    MovementSensor rotation) {
        this.accelerometerSensor = accel;
        this.gyroscopeSensor = gyro;
        this.magnetometerSensor = mag;
        this.barometerSensor = baro;
        this.lightSensor = light;
        this.proximitySensor = prox;
        this.rotationSensor = rotation;
    }

    //region Recording lifecycle

    /**
     * Enables saving sensor values to the trajectory object.
     *
     * <p>Sets save recording to true, resets the absolute start time and creates a new timer
     * object for periodically writing data to the trajectory.</p>
     *
     * @param pdrProcessing PDR processor to reset at recording start
     */
    public void startRecording(PdrProcessing pdrProcessing) {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag");
        }
        wakeLock.acquire(31 * 60 * 1000L /*31 minutes*/);

        this.saveRecording = true;
        state.stepCounter = 0;
        this.counter = 0;
        this.secondCounter = 0;
        this.lastWifiFingerprintHash = 0;
        this.recordedApMacs.clear();
        this.lastBleFingerprintHash = 0;
        this.recordedBleMacs.clear();
        this.absoluteStartTime = System.currentTimeMillis();
        this.bootTime = SystemClock.uptimeMillis();

        this.trajectory = Traj.Trajectory.newBuilder()
                .setAndroidVersion(Build.VERSION.RELEASE)
                .setTrajectoryVersion(2.0f)
                .setStartTimestamp(absoluteStartTime)
                .setAccelerometerInfo(createInfoBuilder(accelerometerSensor))
                .setGyroscopeInfo(createInfoBuilder(gyroscopeSensor))
                .setMagnetometerInfo(createInfoBuilder(magnetometerSensor))
                .setBarometerInfo(createInfoBuilder(barometerSensor))
                .setLightSensorInfo(createInfoBuilder(lightSensor))
                .setProximityInfo(createInfoBuilder(proximitySensor))
                .setRotationVectorInfo(createInfoBuilder(rotationSensor));

        this.storeTrajectoryTimer = new Timer();
        this.storeTrajectoryTimer.schedule(new StoreDataInTrajectory(), 0, TIME_CONST);
        pdrProcessing.resetPDR();

        if (settings.getBoolean("overwrite_constants", false)) {
            this.filterCoefficient = Float.parseFloat(
                    settings.getString("accel_filter", "0.96"));
        } else {
            this.filterCoefficient = FILTER_COEFFICIENT;
        }
    }

    /**
     * Disables saving sensor values to the trajectory object.
     */
    public void stopRecording() {
        if (this.saveRecording) {
            this.saveRecording = false;
            storeTrajectoryTimer.cancel();
        }
        if (wakeLock.isHeld()) {
            this.wakeLock.release();
        }
    }

    /**
     * Returns whether a recording is currently in progress.
     */
    public boolean isRecording() {
        return saveRecording;
    }

    //endregion

    //region Trajectory metadata

    public void setTrajectoryId(String id) {
        this.trajectoryId = id;
    }

    public String getTrajectoryId() {
        return this.trajectoryId;
    }

    /**
     * Sets the selected building identifier for the current recording session.
     * Used to determine the campaign name when uploading the trajectory.
     *
     * @param buildingId building name from the floorplan API (e.g. "nucleus_building")
     */
    public void setSelectedBuildingId(String buildingId) {
        this.selectedBuildingId = buildingId;
    }

    /**
     * Gets the selected building identifier for the current recording session.
     *
     * @return building name string, or null if no building was selected
     */
    public String getSelectedBuildingId() {
        return this.selectedBuildingId;
    }

    /**
     * Writes the initial position and heading into the trajectory protobuf.
     * Should be called after startRecording() and setStartGNSSLatitude().
     */
    public void writeInitialMetadata() {
        if (trajectory == null) return;

        if (trajectoryId != null && !trajectoryId.isEmpty()) {
            trajectory.setTrajectoryId(trajectoryId);
        }

        if (hasValidStartLocation()) {
            setInitialPosition(state.startLocation[0], state.startLocation[1]);
        } else if (isValidReplayCoordinate(state.latitude, state.longitude)) {
            // Fallback when start location was not explicitly set before recording.
            state.startLocation[0] = state.latitude;
            state.startLocation[1] = state.longitude;
            setInitialPosition(state.startLocation[0], state.startLocation[1]);
        }
    }

    /**
     * Ensures initial_position metadata is present for replay anchoring.
     */
    public void ensureInitialPosition(double latitude, double longitude) {
        if (trajectory == null) return;
        if (!isValidReplayCoordinate(latitude, longitude)) return;

        state.startLocation[0] = (float) latitude;
        state.startLocation[1] = (float) longitude;

        if (!trajectory.hasInitialPosition()) {
            setInitialPosition(latitude, longitude);
        }
    }

    private void setInitialPosition(double latitude, double longitude) {
        trajectory.setInitialPosition(
                Traj.GNSSPosition.newBuilder()
                        .setRelativeTimestamp(0)
                        .setLatitude(latitude)
                        .setLongitude(longitude)
                        .setAltitude(0.0)
        );
    }

    private boolean hasValidStartLocation() {
        return isValidReplayCoordinate(state.startLocation[0], state.startLocation[1]);
    }

    //endregion

    //region Data writing (called from other modules)

    /**
     * Adds a PDR position entry to the trajectory.
     */
    public void addPdrData(long relativeTimestamp, float x, float y) {
        if (trajectory != null) {
            trajectory.addPdrData(Traj.RelativePosition.newBuilder()
                    .setRelativeTimestamp(relativeTimestamp)
                    .setX(x)
                    .setY(y));
        }
    }

    /**
     * Adds a fused/corrected position entry to the trajectory.
     */
    public void addCorrectedPosition(long relativeTimestamp,
                                     double latitude,
                                     double longitude,
                                     int floor) {
        if (trajectory == null || !saveRecording) return;

        // Guard against replay-breaking placeholder coordinates.
        if (!isValidReplayCoordinate(latitude, longitude)) {
            return;
        }

        if (relativeTimestamp < 0) {
            relativeTimestamp = 0;
        }

        Traj.GNSSPosition.Builder corrected = Traj.GNSSPosition.newBuilder()
                .setRelativeTimestamp(relativeTimestamp)
                .setLatitude(latitude)
                .setLongitude(longitude)
                .setAltitude(0.0);

        corrected.setFloor(String.valueOf(floor));
        trajectory.addCorrectedPositions(corrected);
    }

    private boolean isValidReplayCoordinate(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            return false;
        }
        if (Math.abs(latitude) > 90.0 || Math.abs(longitude) > 180.0) {
            return false;
        }
        // (0,0) is typically an uninitialised fallback and breaks replay focus.
        return !(Math.abs(latitude) < 1e-7 && Math.abs(longitude) < 1e-7);
    }

    /**
     * Adds a GNSS reading to the trajectory.
     */
    public void addGnssData(Location location) {
        if (trajectory == null || !saveRecording) return;

        long relTs = System.currentTimeMillis() - absoluteStartTime;

        Traj.GNSSPosition.Builder pos = Traj.GNSSPosition.newBuilder()
                .setRelativeTimestamp(relTs)
                .setLatitude(location.getLatitude())
                .setLongitude(location.getLongitude())
                .setAltitude(location.getAltitude());

        trajectory.addGnssData(Traj.GNSSReading.newBuilder()
                .setPosition(pos)
                .setAccuracy(location.getAccuracy())
                .setSpeed(location.getSpeed())
                .setBearing(location.getBearing())
                .setProvider(location.getProvider()));
    }

    /**
     * Adds a WiFi fingerprint to the trajectory with cross-scan deduplication.
     *
     * @param wifiList the list of scanned WiFi access points
     */
    public void addWifiFingerprint(List<Wifi> wifiList) {
        if (!saveRecording || wifiList == null || wifiList.isEmpty()) return;

        int currentHash = 0;
        for (Wifi data : wifiList) {
            currentHash = 31 * currentHash + Long.hashCode(data.getBssid()) + data.getLevel();
        }

        if (currentHash != lastWifiFingerprintHash) {
            lastWifiFingerprintHash = currentHash;
            long relTs = SystemClock.uptimeMillis() - bootTime;

            Traj.Fingerprint.Builder fp = Traj.Fingerprint.newBuilder()
                    .setRelativeTimestamp(relTs);

            for (Wifi data : wifiList) {
                fp.addRfScans(Traj.RFScan.newBuilder()
                        .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                        .setMac(data.getBssid())
                        .setRssi(data.getLevel()));

                if (!recordedApMacs.contains(data.getBssid())) {
                    recordedApMacs.add(data.getBssid());
                    trajectory.addApsData(Traj.WiFiAPData.newBuilder()
                            .setMac(data.getBssid())
                            .setSsid(data.getSsid() != null ? data.getSsid() : "")
                            .setFrequency(data.getFrequency())
                            .setRttEnabled(data.isRttEnabled()));
                }
            }

            trajectory.addWifiFingerprints(fp);
        }
    }

    /**
     * Adds a test point (user-placed ground truth marker) to the trajectory.
     */
    public void addTestPoint(long pressTimestampMs, double lat, double lng) {
        if (trajectory == null) return;

        long startTs = trajectory.getStartTimestamp();
        long rel = pressTimestampMs - startTs;
        if (rel < 0) rel = 0;

        trajectory.addTestPoints(
                Traj.GNSSPosition.newBuilder()
                        .setRelativeTimestamp(rel)
                        .setLatitude(lat)
                        .setLongitude(lng)
                        .setAltitude(0.0)
        );
    }

    /**
     * Adds a BLE fingerprint to the trajectory with cross-scan deduplication.
     * Also records unique BLE device metadata in {@code ble_data}.
     *
     * @param bleList the list of scanned BLE devices
     */
    public void addBleFingerprint(List<BleDevice> bleList) {
        if (!saveRecording || trajectory == null || bleList == null || bleList.isEmpty()) return;

        int currentHash = 0;
        for (BleDevice d : bleList) {
            currentHash = 31 * currentHash
                    + (d.getMacAddress() != null ? d.getMacAddress().hashCode() : 0)
                    + d.getRssi();
        }

        if (currentHash != lastBleFingerprintHash) {
            lastBleFingerprintHash = currentHash;
            long relTs = SystemClock.uptimeMillis() - bootTime;

            Traj.Fingerprint.Builder fp = Traj.Fingerprint.newBuilder()
                    .setRelativeTimestamp(relTs);

            for (BleDevice d : bleList) {
                fp.addRfScans(Traj.RFScan.newBuilder()
                        .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                        .setMac(d.getMacAsLong())
                        .setRssi(d.getRssi()));

                // Record BLE device metadata once per unique MAC
                if (d.getMacAddress() != null && !recordedBleMacs.contains(d.getMacAddress())) {
                    recordedBleMacs.add(d.getMacAddress());

                    Traj.BleData.Builder bleData = Traj.BleData.newBuilder()
                            .setMacAddress(d.getMacAddress())
                            .setName(d.getName() != null ? d.getName() : "")
                            .setTxPowerLevel(d.getTxPowerLevel())
                            .setAdvertiseFlags(d.getAdvertiseFlags());

                    if (d.getServiceUuids() != null) {
                        bleData.addAllServiceUuids(d.getServiceUuids());
                    }

                    if (d.getManufacturerData() != null) {
                        bleData.setManufacturerData(
                                ByteString.copyFrom(d.getManufacturerData()));
                    }

                    trajectory.addBleData(bleData);
                }
            }

            trajectory.addBleFingerprints(fp);
        }
    }

    /**
     * Add WiFi RTT ranging measurement to the trajectory.
     *
     * @param mac           integer-encoded MAC address of the AP
     * @param distanceMm    measured distance in millimetres
     * @param distanceStdMm standard deviation of distance in millimetres
     * @param rssi          RSSI in dBm
     */
    public void addWifiRttReading(long mac, float distanceMm, float distanceStdMm, int rssi) {
        if (!saveRecording || trajectory == null) return;

        long relTs = SystemClock.uptimeMillis() - bootTime;

        trajectory.addWifiRttData(Traj.WiFiRTTReading.newBuilder()
                .setRelativeTimestamp(relTs)
                .setMac(mac)
                .setDistance(distanceMm)
                .setDistanceStd(distanceStdMm)
                .setRssi(rssi));
    }

    /**
     * Adds a BLE RTT-style ranging estimate to the trajectory.
     *
     * <p>For protobuf compatibility this is currently serialised through
     * {@code wifi_rtt_data}. The MAC and measurement values belong to BLE scans,
     * and are produced by {@link BleRttManager}.</p>
     *
     * @param reading BLE RTT-style reading derived from BLE scan metadata
     */
    public void addBleRttReading(BleRttReading reading) {
        if (reading == null) return;
        addWifiRttReading(reading.getMac(), reading.getDistanceMm(),
                reading.getDistanceStdMm(), reading.getRssi());
    }

    //endregion

    //region Upload

    /**
     * Builds the trajectory protobuf from the current builder state.
     * Used for validation before upload.
     *
     * @return the built trajectory, or null if no recording was started
     */
    public Traj.Trajectory buildTrajectory() {
        if (trajectory == null) return null;
        return trajectory.build();
    }

    /**
     * Validates the current trajectory against quality thresholds.
     *
     * @return validation result, or a failed result if no trajectory exists
     */
    public TrajectoryValidator.ValidationResult validateTrajectory() {
        Traj.Trajectory built = buildTrajectory();
        if (built == null) {
            java.util.List<String> errors = new java.util.ArrayList<>();
            errors.add("No trajectory data available");
            return new TrajectoryValidator.ValidationResult(
                    false, errors, java.util.Collections.emptyList());
        }
        return TrajectoryValidator.validate(built);
    }

    /**
     * Sends the trajectory object to the server.
     * Passes the user-selected building ID for campaign binding.
     */
    public void sendTrajectoryToCloud() {
        Traj.Trajectory sentTrajectory = trajectory.build();
        this.serverCommunications.sendTrajectory(sentTrajectory, selectedBuildingId);
    }

    //endregion

    //region Getters

    public long getBootTime() {
        return bootTime;
    }

    public long getAbsoluteStartTime() {
        return absoluteStartTime;
    }

    public ServerCommunications getServerCommunications() {
        return serverCommunications;
    }

    //endregion

    //region Internal helpers

    private Traj.SensorInfo.Builder createInfoBuilder(MovementSensor sensor) {
        return Traj.SensorInfo.newBuilder()
                .setName(sensor.sensorInfo.getName())
                .setVendor(sensor.sensorInfo.getVendor())
                .setResolution(sensor.sensorInfo.getResolution())
                .setPower(sensor.sensorInfo.getPower())
                .setVersion(sensor.sensorInfo.getVersion())
                .setType(sensor.sensorInfo.getType());
    }

    /**
     * Timer task to record data with the desired frequency in the trajectory class.
     * Reads from {@link SensorState} and writes to the protobuf trajectory builder.
     */
    private class StoreDataInTrajectory extends TimerTask {
        @Override
        public void run() {
            if (saveRecording) {
                long t = SystemClock.uptimeMillis() - bootTime;

                // IMU data (Vector3 + Quaternion)
                trajectory.addImuData(
                        Traj.IMUReading.newBuilder()
                                .setRelativeTimestamp(t)
                                .setAcc(
                                        Traj.Vector3.newBuilder()
                                                .setX(state.acceleration[0])
                                                .setY(state.acceleration[1])
                                                .setZ(state.acceleration[2])
                                )
                                .setGyr(
                                        Traj.Vector3.newBuilder()
                                                .setX(state.angularVelocity[0])
                                                .setY(state.angularVelocity[1])
                                                .setZ(state.angularVelocity[2])
                                )
                                .setRotationVector(
                                        Traj.Quaternion.newBuilder()
                                                .setX(state.rotation[0])
                                                .setY(state.rotation[1])
                                                .setZ(state.rotation[2])
                                                .setW(state.rotation[3])
                                )
                                .setStepCount(state.stepCounter)
                );

                // Magnetometer data
                trajectory.addMagnetometerData(
                        Traj.MagnetometerReading.newBuilder()
                                .setRelativeTimestamp(t)
                                .setMag(
                                        Traj.Vector3.newBuilder()
                                                .setX(state.magneticField[0])
                                                .setY(state.magneticField[1])
                                                .setZ(state.magneticField[2])
                                )
                );

                // Pressure / Light / Proximity (every ~1s, counter wraps at 99)
                if (counter >= 99) {
                    counter = 0;

                    if (barometerSensor != null && barometerSensor.sensor != null) {
                        trajectory.addPressureData(
                                Traj.BarometerReading.newBuilder()
                                        .setRelativeTimestamp(t)
                                        .setPressure(state.pressure)
                        );
                    }

                    if (lightSensor != null && lightSensor.sensor != null) {
                        trajectory.addLightData(
                                Traj.LightReading.newBuilder()
                                        .setRelativeTimestamp(t)
                                        .setLight(state.light)
                        );
                    }

                    if (proximitySensor != null && proximitySensor.sensor != null) {
                        trajectory.addProximityData(
                                Traj.ProximityReading.newBuilder()
                                        .setRelativeTimestamp(t)
                                        .setDistance(state.proximity)
                        );
                    }
                } else {
                    counter++;
                }
            }
        }
    }

    //endregion
}
