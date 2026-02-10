package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.content.SharedPreferences;
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

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.PdrProcessing;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.presentation.fragment.SettingsFragment;

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
 * The SensorFusion class is the main data gathering and processing class of the application.
 *
 * It follows the singleton design pattern to ensure that every fragment and process has access to
 * the same date and sensor instances. Hence it has a private constructor, and must be initialised
 * with the application context after creation.
 * <p>
 * The class implements {@link SensorEventListener} and has instances of {@link MovementSensor} for
 * every device type necessary for data collection. As such, it implements the
 * {@link SensorFusion#onSensorChanged(SensorEvent)} function, and process and records the data
 * provided by the sensor hardware, which are stored in a {@link Traj} object. Data is read
 * continuously but is only saved to the trajectory when recording is enabled.
 * <p>
 * The class provides a number of setters and getters so that other classes can have access to the
 * sensor data and influence the behaviour of data collection.
 *
 * @author Michal Dvorak
 * @author Mate Stodulka
 * @author Virginia Cangelosi
 */
public class SensorFusion implements SensorEventListener, Observer {

    /**
     * Represents a test point marked by the user during recording.
     */
    public static class TestPoint {
        public final long relativeTimestamp;
        public final long absoluteTimestamp;  // System.currentTimeMillis() at creation
        public final double latitude;
        public final double longitude;
        public final double altitude;
        public final String floor;

        public TestPoint(long relativeTimestamp, long absoluteTimestamp,
                         double latitude, double longitude,
                         double altitude, String floor) {
            this.relativeTimestamp = relativeTimestamp;
            this.absoluteTimestamp = absoluteTimestamp;
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
            this.floor = floor;
        }
    }

    private List<TestPoint> testPoints = new ArrayList<>();
    // Stores trajectory LatLng points for correction screen map display
    private List<double[]> trajectoryLatLngs = new ArrayList<>();

    // Store the last event timestamps for each sensor type
    private HashMap<Integer, Long> lastEventTimestamps = new HashMap<>();
    private HashMap<Integer, Integer> eventCounts = new HashMap<>();

    long maxReportLatencyNs = 0;  // Disable batching to deliver events immediately

    // Define a threshold for large time gaps (in milliseconds)
    private static final long LARGE_GAP_THRESHOLD_MS = 500;  // Adjust this if needed

    //region Static variables
    // Singleton Class
    private static final SensorFusion sensorFusion = new SensorFusion();
    // Static constant for calculations with milliseconds
    private static final long TIME_CONST = 10;
    // Coefficient for fusing gyro-based and magnetometer-based orientation
    public static final float FILTER_COEFFICIENT = 0.96f;
    //Tuning value for low pass filter
    private static final float ALPHA = 0.8f;
    // String for creating WiFi fingerprint JSO N object
    private static final String WIFI_FINGERPRINT= "wf";
    //endregion

    //region Instance variables
    // Keep device awake while recording
    private PowerManager.WakeLock wakeLock;
    private Context appContext;

    // Settings
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
    // Other data recording
    private WifiDataProcessor wifiProcessor;
    private BluetoothScanManager bleProcessor;
    private GNSSDataProcessor gnssProcessor;
    // Data listener
    private final LocationListener locationListener;

    // Server communication class for sending data
    private ServerCommunications serverCommunications;
    // Trajectory object containing all data
    private Traj.Trajectory.Builder trajectory;

    // Settings
    private boolean saveRecording;
    private float filter_coefficient;
    // Variables to help with timed events
    private long absoluteStartTime;
    private long bootTime;
    long lastStepTime = 0;
    // Timer object for scheduling data recording
    private Timer storeTrajectoryTimer;
    // Counter for dividing timer to record data every 1 second
    private int counter;

    // Sensor values
    private float[] acceleration;
    private float[] filteredAcc;
    private float[] gravity;
    private float[] magneticField;
    private float[] angularVelocity;
    private float[] orientation;
    private float[] rotation;
    private float pressure;
    private float light;
    private float proximity;
    private float[] R;
    private int stepCounter ;
    private String trajectoryName = "";
    // Derived values
    private float elevation;
    private boolean elevator;
    // Location values
    private float latitude;
    private float longitude;
    private float[] startLocation;
    // Heading calibration: offset from GNSS bearing
    private float headingOffset = 0f;
    private boolean headingCalibrated = false;
    // Complementary filter: fuse gyroscope (short-term) with magnetometer (long-term) for heading
    private float fusedHeading = 0f;
    private float magHeading = 0f;
    private long lastGyroTimestampNs = 0;
    private boolean headingInitialized = false;
    // Wifi values
    private List<Wifi> wifiList;
    // WiFi deduplication: sorted MAC list of previous snapshot
    private List<Long> lastWifiMacs = new ArrayList<>();
    // Unique AP metadata table (populated from all WiFi scans, written once at upload)
    private Map<Long, Traj.AP_Data.Builder> uniqueApMap = new HashMap<>();
    // Unique BLE device metadata table
    private Map<String, Traj.BleData.Builder> uniqueBleMap = new HashMap<>();


    // Over time accelerometer magnitude values since last step
    private List<Double> accelMagnitude;
    // Forward acceleration samples for backward walking detection
    private List<Double> forwardAccelSamples;

    // PDR calculation class
    private PdrProcessing pdrProcessing;

    // Trajectory displaying class
    private PathView pathView;
    // WiFi positioning object
    private WiFiPositioning wiFiPositioning;

    //region Initialisation
    /**
     * Private constructor for implementing singleton design pattern for SensorFusion.
     * Initialises empty arrays and new objects that do not depends on outside information.
     */
    private SensorFusion() {
        // Location listener to be used by the GNSS class
        this.locationListener= new myLocationListener();
        // Timer to store sensor values in the trajectory object
        this.storeTrajectoryTimer = new Timer();
        // Counter to track elements with slower frequency
        this.counter = 0;
        // Step count initial value
        this.stepCounter = 0;
        // PDR elevation initial values
        this.elevation = 0;
        this.elevator = false;
        // PDR position array
        this.startLocation = new float[2];
        // Empty array initialisation
        this.acceleration = new float[3];
        this.filteredAcc = new float[3];
        this.gravity = new float[3];
        this.magneticField = new float[3];
        this.angularVelocity = new float[3];
        this.orientation = new float[3];
        this.rotation = new float[4];
        this.rotation[3] = 1.0f;
        this.R = new float[9];
        // GNSS initial Long-Lat array
        this.startLocation = new float[2];
    }


    /**
     * Static function to access singleton instance of SensorFusion.
     *
     * @return  singleton instance of SensorFusion class.
     */
    public static SensorFusion getInstance() {
        return sensorFusion;
    }

    /**
     * Initialisation function for the SensorFusion instance.
     *
     * Initialise all Movement sensor instances from context and predetermined types. Creates a
     * server communication instance for sending trajectories. Saves current absolute and relative
     * time, and initialises saving the recording to false.
     *
     * @param context   application context for permissions and device access.
     *
     * @see MovementSensor handling all SensorManager based data collection devices.
     * @see ServerCommunications handling communication with the server.
     * @see GNSSDataProcessor for location data processing.
     * @see WifiDataProcessor for network data processing.
     */
    public void setContext(Context context) {
        this.appContext = context.getApplicationContext(); // store app context for later use

        // Initialise data collection devices with actual sampling periods (in microseconds)
        // IMU sensors use 10000 us (100 Hz), environmental sensors use 1000000 us (1 Hz)
        this.accelerometerSensor = new MovementSensor(context, Sensor.TYPE_ACCELEROMETER, 10000);
        this.barometerSensor = new MovementSensor(context, Sensor.TYPE_PRESSURE, 1000000);
        this.gyroscopeSensor = new MovementSensor(context, Sensor.TYPE_GYROSCOPE, 10000);
        this.lightSensor = new MovementSensor(context, Sensor.TYPE_LIGHT, 1000000);
        this.proximitySensor = new MovementSensor(context, Sensor.TYPE_PROXIMITY, 1000000);
        this.magnetometerSensor = new MovementSensor(context, Sensor.TYPE_MAGNETIC_FIELD, 10000);
        this.stepDetectionSensor = new MovementSensor(context, Sensor.TYPE_STEP_DETECTOR, 200000);
        this.rotationSensor = new MovementSensor(context, Sensor.TYPE_ROTATION_VECTOR, 10000);
        this.gravitySensor = new MovementSensor(context, Sensor.TYPE_GRAVITY, 10000);
        this.linearAccelerationSensor = new MovementSensor(context, Sensor.TYPE_LINEAR_ACCELERATION, 10000);
        // Listener based devices
        this.wifiProcessor = new WifiDataProcessor(context);
        wifiProcessor.registerObserver(this);
        this.bleProcessor = new BluetoothScanManager(context);
        bleProcessor.registerObserver(this);
        this.gnssProcessor = new GNSSDataProcessor(context, locationListener);
        // Create object handling HTTPS communication
        this.serverCommunications = new ServerCommunications(context);
        // Save absolute and relative start time
        this.absoluteStartTime = System.currentTimeMillis();
        this.bootTime = SystemClock.uptimeMillis();
        // Initialise saveRecording to false
        this.saveRecording = false;

        // Other initialisations...
        this.accelMagnitude = new ArrayList<>();
        this.forwardAccelSamples = new ArrayList<>();
        this.pdrProcessing = new PdrProcessing(context);
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.pathView = new PathView(context, null);
        this.wiFiPositioning = new WiFiPositioning(context);

        if(settings.getBoolean("overwrite_constants", false)) {
            this.filter_coefficient = Float.parseFloat(settings.getString("accel_filter", "0.96"));
        } else {
            this.filter_coefficient = FILTER_COEFFICIENT;
        }

        // Keep app awake during the recording (using stored appContext)
        PowerManager powerManager = (PowerManager) this.appContext.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag");
    }

    //endregion

    //region Sensor processing
    /**
     * {@inheritDoc}
     *
     * Called every time a Sensor value is updated.
     *
     * Checks originating sensor type, if the data is meaningful save it to a local variable.
     *
     * @param sensorEvent   SensorEvent of sensor with values changed, includes types and values.
     */
    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        long currentTime = System.currentTimeMillis();  // Current time in milliseconds
        int sensorType = sensorEvent.sensor.getType();

        // Get the previous timestamp for this sensor type
        Long lastTimestamp = lastEventTimestamps.get(sensorType);

        if (lastTimestamp != null) {
            long timeGap = currentTime - lastTimestamp;

//            // Log a warning if the time gap is larger than the threshold
//            if (timeGap > LARGE_GAP_THRESHOLD_MS) {
//                Log.e("SensorFusion", "Large time gap detected for sensor " + sensorType +
//                        " | Time gap: " + timeGap + " ms");
//            }
        }

        // Update timestamp and frequency counter for this sensor
        lastEventTimestamps.put(sensorType, currentTime);
        eventCounts.put(sensorType, eventCounts.getOrDefault(sensorType, 0) + 1);



        switch (sensorType) {
            case Sensor.TYPE_ACCELEROMETER:
                acceleration[0] = sensorEvent.values[0];
                acceleration[1] = sensorEvent.values[1];
                acceleration[2] = sensorEvent.values[2];
                break;

            case Sensor.TYPE_PRESSURE:
                pressure = (1 - ALPHA) * pressure + ALPHA * sensorEvent.values[0];
                if (saveRecording) {
                    this.elevation = pdrProcessing.updateElevation(
                            SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure)
                    );
                }
                break;

            case Sensor.TYPE_GYROSCOPE:
                angularVelocity[0] = sensorEvent.values[0];
                angularVelocity[1] = sensorEvent.values[1];
                angularVelocity[2] = sensorEvent.values[2];

                // Complementary filter: integrate gyro yaw rate + blend with magnetic heading
                long gyroTimestampNs = sensorEvent.timestamp;
                if (lastGyroTimestampNs != 0 && headingInitialized) {
                    float dt = (gyroTimestampNs - lastGyroTimestampNs) * 1e-9f;
                    if (dt > 0 && dt < 1.0f) {
                        // Project gyro angular velocity onto gravity direction to get yaw rate
                        float gMag = (float) Math.sqrt(
                                gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]);
                        if (gMag > 0.1f) {
                            float yawRate = (angularVelocity[0] * gravity[0]
                                    + angularVelocity[1] * gravity[1]
                                    + angularVelocity[2] * gravity[2]) / gMag;
                            // Complementary filter: gyro for short-term, magnetometer for long-term
                            // Negate yawRate: gyro positive = CCW (right-hand rule),
                            // but azimuth positive = CW (North→East)
                            float gyroHeading = fusedHeading - yawRate * dt;
                            // Use angular difference to avoid 360-degree spin at ±π boundary
                            float angleDiff = (float) Math.atan2(
                                    Math.sin(magHeading - gyroHeading),
                                    Math.cos(magHeading - gyroHeading));
                            fusedHeading = gyroHeading + (1 - filter_coefficient) * angleDiff;
                            // Normalize to [-PI, PI]
                            fusedHeading = (float) Math.atan2(
                                    Math.sin(fusedHeading), Math.cos(fusedHeading));
                        }
                    }
                }
                lastGyroTimestampNs = gyroTimestampNs;
                break;

            case Sensor.TYPE_LINEAR_ACCELERATION:
                filteredAcc[0] = sensorEvent.values[0];
                filteredAcc[1] = sensorEvent.values[1];
                filteredAcc[2] = sensorEvent.values[2];

                // Compute magnitude & add to accelMagnitude
                double accelMagFiltered = Math.sqrt(
                        Math.pow(filteredAcc[0], 2) +
                                Math.pow(filteredAcc[1], 2) +
                                Math.pow(filteredAcc[2], 2)
                );
                this.accelMagnitude.add(accelMagFiltered);

                // Compute forward acceleration for backward walking detection
                // Transform device acceleration to world frame using rotation matrix R
                // R maps device coords to world coords: X=East, Y=North, Z=Up
                float worldAccEast  = R[0]*filteredAcc[0] + R[1]*filteredAcc[1] + R[2]*filteredAcc[2];
                float worldAccNorth = R[3]*filteredAcc[0] + R[4]*filteredAcc[1] + R[5]*filteredAcc[2];
                // Project onto heading direction (fusedHeading = azimuth from North, clockwise)
                float heading = this.fusedHeading;
                double forwardAcc = worldAccEast * Math.sin(heading) + worldAccNorth * Math.cos(heading);
                this.forwardAccelSamples.add(forwardAcc);

                elevator = pdrProcessing.estimateElevator(gravity, filteredAcc);
                break;

            case Sensor.TYPE_GRAVITY:
                gravity[0] = sensorEvent.values[0];
                gravity[1] = sensorEvent.values[1];
                gravity[2] = sensorEvent.values[2];

                // Possibly log gravity values if needed
                //Log.v("SensorFusion", "Gravity: " + Arrays.toString(gravity));

                elevator = pdrProcessing.estimateElevator(gravity, filteredAcc);
                break;

            case Sensor.TYPE_LIGHT:
                light = sensorEvent.values[0];
                break;

            case Sensor.TYPE_PROXIMITY:
                proximity = sensorEvent.values[0];
                break;

            case Sensor.TYPE_MAGNETIC_FIELD:
                magneticField[0] = sensorEvent.values[0];
                magneticField[1] = sensorEvent.values[1];
                magneticField[2] = sensorEvent.values[2];

                // Compute magnetic heading from gravity + magnetometer
                float[] magRotMatrix = new float[9];
                if (SensorManager.getRotationMatrix(magRotMatrix, null, gravity, magneticField)) {
                    float[] magOrientation = new float[3];
                    SensorManager.getOrientation(magRotMatrix, magOrientation);
                    magHeading = magOrientation[0]; // azimuth from magnetic north
                    if (!headingInitialized) {
                        fusedHeading = magHeading;
                        headingInitialized = true;
                    }
                }
                break;

            case Sensor.TYPE_ROTATION_VECTOR:
                this.rotation = sensorEvent.values.clone();
                SensorManager.getRotationMatrixFromVector(this.R, this.rotation);
                SensorManager.getOrientation(this.R, this.orientation);
                break;

            case Sensor.TYPE_STEP_DETECTOR:
                long stepTime = SystemClock.uptimeMillis() - bootTime;


                if (currentTime - lastStepTime < 20) {
                    Log.e("SensorFusion", "Ignoring step event, too soon after last step event:" + (currentTime - lastStepTime) + " ms");
                    // Ignore rapid successive step events
                    break;
                }

                else {
                    lastStepTime = currentTime;
                    // Log if accelMagnitude is empty
                    if (accelMagnitude.isEmpty()) {
                        Log.e("SensorFusion",
                                "stepDetection triggered, but accelMagnitude is empty! " +
                                        "This can cause updatePdr(...) to fail or return bad results.");
                    } else {
                        Log.d("SensorFusion",
                                "stepDetection triggered, accelMagnitude size = " + accelMagnitude.size());
                    }

                    // Compute average forward acceleration for backward walking detection
                    double avgForwardAccel = 0.0;
                    if (!forwardAccelSamples.isEmpty()) {
                        double sum = 0;
                        for (Double val : forwardAccelSamples) {
                            sum += val;
                        }
                        avgForwardAccel = sum / forwardAccelSamples.size();
                    }

                    Log.d("PDR_DEBUG", "STEP: accelMag.size=" + accelMagnitude.size()
                            + " fusedHeading=" + String.format("%.3f", fusedHeading)
                            + " headingOffset=" + String.format("%.3f", headingOffset)
                            + " headingInit=" + headingInitialized
                            + " avgFwdAccel=" + String.format("%.3f", avgForwardAccel));

                    float[] newCords = this.pdrProcessing.updatePdr(
                            stepTime,
                            this.accelMagnitude,
                            this.fusedHeading + headingOffset,
                            avgForwardAccel
                    );

                    Log.d("PDR_DEBUG", "RESULT: posX=" + newCords[0] + " posY=" + newCords[1]);

                    // Clear the lists after using them
                    this.accelMagnitude.clear();
                    this.forwardAccelSamples.clear();


                    if (saveRecording) {
                        this.pathView.drawTrajectory(newCords);
                        stepCounter++;
                        trajectory.addPdrData(Traj.Pdr_Sample.newBuilder()
                                .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                                .setX(newCords[0])
                                .setY(newCords[1]));
                    }
                    break;
                }

        }
    }

    /**
     * Utility function to log the event frequency of each sensor.
     * Call this periodically for debugging purposes.
     */
    public void logSensorFrequencies() {
        for (int sensorType : eventCounts.keySet()) {
            Log.d("SensorFusion", "Sensor " + sensorType + " | Event Count: " + eventCounts.get(sensorType));
        }
    }

    /**
     * {@inheritDoc}
     *
     * Location listener class to receive updates from the location manager.
     *
     * Passed to the {@link GNSSDataProcessor} to receive the location data in this class. Save the
     * values in instance variables.
     */
    class myLocationListener implements LocationListener{
        @Override
        public void onLocationChanged(@NonNull Location location) {
            //Toast.makeText(context, "Location Changed", Toast.LENGTH_SHORT).show();
            latitude = (float) location.getLatitude();
            longitude = (float) location.getLongitude();
            float altitude = (float) location.getAltitude();
            float accuracy = (float) location.getAccuracy();
            float speed = (float) location.getSpeed();
            String provider = location.getProvider();

            // Heading calibration: use GNSS bearing when walking with good speed
            if (!headingCalibrated && location.hasBearing() && speed > 1.5f && accuracy < 20f) {
                float gnssBearingRad = (float) Math.toRadians(location.getBearing());
                headingOffset = gnssBearingRad - fusedHeading;
                // Normalize to [-PI, PI]
                while (headingOffset > Math.PI) headingOffset -= 2 * Math.PI;
                while (headingOffset < -Math.PI) headingOffset += 2 * Math.PI;
                headingCalibrated = true;
                Log.i("SensorFusion", "Heading calibrated from GNSS: offset=" +
                        Math.toDegrees(headingOffset) + " deg, bearing=" +
                        location.getBearing() + ", speed=" + speed);
            }

            if(saveRecording) {
                trajectory.addGnssData(Traj.GNSS_Sample.newBuilder()
                        .setPosition(Traj.GNSSPosition.newBuilder()
                                .setRelativeTimestamp(System.currentTimeMillis()-absoluteStartTime)
                                .setLatitude(location.getLatitude())
                                .setLongitude(location.getLongitude())
                                .setAltitude(location.getAltitude()))
                        .setAccuracy(accuracy)
                        .setSpeed(speed)
                        .setProvider(provider != null ? provider : ""));
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * Receives updates from {@link WifiDataProcessor}.
     *
     * @see WifiDataProcessor object for wifi scanning.
     */
    @Override
    public void update(Object[] objList) {
        if (objList == null || objList.length == 0) return;

        // Route to WiFi or BLE handler based on object type
        if (objList[0] instanceof Wifi) {
            handleWifiUpdate(objList);
        } else if (objList[0] instanceof BluetoothScanManager.BeaconSnapshot) {
            handleBleUpdate(objList);
        }
    }

    /**
     * Handle WiFi scan update: store fingerprint snapshot (with dedup), accumulate AP metadata.
     */
    private void handleWifiUpdate(Object[] objList) {
        // Save newest wifi values to local variable
        this.wifiList = Stream.of(objList).map(o -> (Wifi) o).collect(Collectors.toList());

        if (this.saveRecording) {
            // Deduplication: build sorted MAC list and compare with previous
            List<Long> currentMacs = new ArrayList<>();
            for (Wifi data : this.wifiList) {
                currentMacs.add(data.getBssid());
            }
            java.util.Collections.sort(currentMacs);

            boolean isDuplicate = currentMacs.equals(lastWifiMacs);
            if (!isDuplicate) {
                lastWifiMacs = currentMacs;

                // Build and store WiFi fingerprint snapshot
                Traj.WiFi_Sample.Builder wifiData = Traj.WiFi_Sample.newBuilder()
                        .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime);
                for (Wifi data : this.wifiList) {
                    wifiData.addMacScans(Traj.Mac_Scan.newBuilder()
                            .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                            .setMac(data.getBssid()).setRssi(data.getLevel()));
                }
                this.trajectory.addWifiData(wifiData);
            }

            // Accumulate unique AP metadata from ALL scanned APs
            for (Wifi data : this.wifiList) {
                long mac = data.getBssid();
                if (!uniqueApMap.containsKey(mac)) {
                    uniqueApMap.put(mac, Traj.AP_Data.newBuilder()
                            .setMac(mac)
                            .setSsid(data.getSsid() != null ? data.getSsid() : "")
                            .setFrequency(data.getFrequency())
                            .setRttEnabled(data.isRttEnabled()));
                }
            }
        }
        createWifiPositioningRequest();
    }

    /**
     * Handle BLE scan update: store BLE fingerprint snapshot, accumulate device metadata.
     */
    private void handleBleUpdate(Object[] objList) {
        if (!this.saveRecording) return;

        // Build BLE fingerprint snapshot (proto reuses WiFi_Sample type for ble_fingerprints)
        Traj.WiFi_Sample.Builder bleFp = Traj.WiFi_Sample.newBuilder()
                .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime);

        for (Object obj : objList) {
            BluetoothScanManager.BeaconSnapshot device = (BluetoothScanManager.BeaconSnapshot) obj;
            long macLong = macStringToLong(device.macAddress);

            bleFp.addMacScans(Traj.Mac_Scan.newBuilder()
                    .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                    .setMac(macLong)
                    .setRssi(device.rssi));

            // Accumulate unique BLE device metadata
            if (!uniqueBleMap.containsKey(device.macAddress)) {
                Traj.BleData.Builder bleData = Traj.BleData.newBuilder()
                        .setMacAddress(device.macAddress)
                        .setName(device.deviceName != null ? device.deviceName : "")
                        .setTxPowerLevel(device.txPowerLevel)
                        .setAdvertiseFlags(device.advertiseFlags);
                for (String uuid : device.serviceUuids) {
                    bleData.addServiceUuids(uuid);
                }
                if (device.manufacturerData != null) {
                    bleData.setManufacturerData(
                            com.google.protobuf.ByteString.copyFrom(device.manufacturerData));
                }
                uniqueBleMap.put(device.macAddress, bleData);
            }
        }

        trajectory.addBleFingerprints(bleFp);
        Log.d("SensorFusion", "BLE snapshot: " + objList.length + " devices, "
                + uniqueBleMap.size() + " unique total");
    }

    /**
     * Convert MAC address string "AA:BB:CC:DD:EE:FF" to long integer.
     */
    private long macStringToLong(String mac) {
        try {
            return Long.parseLong(mac.replace(":", ""), 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Function to create a request to obtain a wifi location for the obtained wifi fingerprint.
     * Uses debounce to prevent duplicate requests within 2 seconds.
     * API expects integer MAC format: {"wf": {"207394925843984": -65, ...}}
     */
    private long lastWifiRequestTime = 0;

    private void createWifiPositioningRequest(){
        // Debounce: skip if called again within 2 seconds (prevents duplicate requests)
        long now = System.currentTimeMillis();
        if (now - lastWifiRequestTime < 2000) return;
        lastWifiRequestTime = now;

        // Try catch block to catch any errors and prevent app crashing
        try {
            JSONObject wifiAccessPoints=new JSONObject();
            for (Wifi data : this.wifiList){
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }
            Log.d("WiFiFusion", "WiFi request: " + wifiAccessPoints.length() + " APs");
            // Creating POST Request
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
            this.wiFiPositioning.request(wifiFingerPrint);
        } catch (JSONException e) {
            Log.e("jsonErrors","Error creating json object"+e.toString());
        }
    }
    // Callback Example Function
    /**
     * Function to create a request to obtain a wifi location for the obtained wifi fingerprint
     * using Volley Callback
     */
    private void createWifiPositionRequestCallback(){
        try {
            // Creating a JSON object to store the WiFi access points
            JSONObject wifiAccessPoints=new JSONObject();
            for (Wifi data : this.wifiList){
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }
            // Creating POST Request
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
            this.wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng wifiLocation, int floor) {
                    // Handle the success response
                }

                @Override
                public void onError(String message) {
                    // Handle the error response
                }
            });
        } catch (JSONException e) {
            // Catching error while making JSON object, to prevent crashes
            // Error log to keep record of errors (for secure programming and maintainability)
            Log.e("jsonErrors","Error creating json object"+e.toString());
        }

    }

    /**
     * Method to get user position obtained using {@link WiFiPositioning}.
     *
     * @return {@link LatLng} corresponding to user's position.
     */
    public LatLng getLatLngWifiPositioning(){return this.wiFiPositioning.getWifiLocation();}

    /**
     * Method to get current floor the user is at, obtained using WiFiPositioning
     * @see WiFiPositioning for WiFi positioning
     * @return Current floor user is at using WiFiPositioning
     */
    public int getWifiFloor(){
        return this.wiFiPositioning.getFloor();
    }

    /**
     * Check if WiFi position was updated recently (not stale).
     * @param maxAgeMs maximum age in milliseconds to consider fresh
     * @return true if WiFi position was updated within maxAgeMs
     */
    public boolean isWifiPositionFresh(long maxAgeMs) {
        long lastUpdate = this.wiFiPositioning.getLastUpdateTime();
        return lastUpdate > 0 && (System.currentTimeMillis() - lastUpdate) < maxAgeMs;
    }

    /**
     * Method used for converting an array of orientation angles into a rotation matrix.
     *
     * @param o An array containing orientation angles in radians
     * @return resultMatrix representing the orientation angles
     */
    private float[] getRotationMatrixFromOrientation(float[] o) {
        float[] xM = new float[9];
        float[] yM = new float[9];
        float[] zM = new float[9];

        float sinX = (float)Math.sin(o[1]);
        float cosX = (float)Math.cos(o[1]);
        float sinY = (float)Math.sin(o[2]);
        float cosY = (float)Math.cos(o[2]);
        float sinZ = (float)Math.sin(o[0]);
        float cosZ = (float)Math.cos(o[0]);

        // rotation about x-axis (pitch)
        xM[0] = 1.0f; xM[1] = 0.0f; xM[2] = 0.0f;
        xM[3] = 0.0f; xM[4] = cosX; xM[5] = sinX;
        xM[6] = 0.0f; xM[7] = -sinX; xM[8] = cosX;

        // rotation about y-axis (roll)
        yM[0] = cosY; yM[1] = 0.0f; yM[2] = sinY;
        yM[3] = 0.0f; yM[4] = 1.0f; yM[5] = 0.0f;
        yM[6] = -sinY; yM[7] = 0.0f; yM[8] = cosY;

        // rotation about z-axis (azimuth)
        zM[0] = cosZ; zM[1] = sinZ; zM[2] = 0.0f;
        zM[3] = -sinZ; zM[4] = cosZ; zM[5] = 0.0f;
        zM[6] = 0.0f; zM[7] = 0.0f; zM[8] = 1.0f;

        // rotation order is y, x, z (roll, pitch, azimuth)
        float[] resultMatrix = matrixMultiplication(xM, yM);
        resultMatrix = matrixMultiplication(zM, resultMatrix);
        return resultMatrix;
    }

    /**
     * Performs and matrix multiplication of two 3x3 matrices and returns the product.
     *
     * @param A An array representing a 3x3 matrix
     * @param B An array representing a 3x3 matrix
     * @return result representing the product of A and B
     */
    private float[] matrixMultiplication(float[] A, float[] B) {
        float[] result = new float[9];

        result[0] = A[0] * B[0] + A[1] * B[3] + A[2] * B[6];
        result[1] = A[0] * B[1] + A[1] * B[4] + A[2] * B[7];
        result[2] = A[0] * B[2] + A[1] * B[5] + A[2] * B[8];

        result[3] = A[3] * B[0] + A[4] * B[3] + A[5] * B[6];
        result[4] = A[3] * B[1] + A[4] * B[4] + A[5] * B[7];
        result[5] = A[3] * B[2] + A[4] * B[5] + A[5] * B[8];

        result[6] = A[6] * B[0] + A[7] * B[3] + A[8] * B[6];
        result[7] = A[6] * B[1] + A[7] * B[4] + A[8] * B[7];
        result[8] = A[6] * B[2] + A[7] * B[5] + A[8] * B[8];

        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {}
    //endregion

    //region Getters/Setters
    /**
     * Getter function for core location data.
     *
     * @param start set true to get the initial location
     * @return longitude and latitude data in a float[2].
     */
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

    /**
     * Setter function for core location data.
     *
     * @param startPosition contains the initial location set by the user
     */
    public void setStartGNSSLatitude(float[] startPosition){
        startLocation = startPosition;
    }


    /**
     * Function to redraw path in corrections fragment.
     *
     * @param scalingRatio new size of path due to updated step length
     */
    public void redrawPath(float scalingRatio){
        pathView.redraw(scalingRatio);
    }

    /**
     * Getter function for average step count.
     * Calls the average step count function in pdrProcessing class
     *
     * @return average step count of total PDR.
     */
    public float passAverageStepLength(){
        return pdrProcessing.getAverageStepLength();
    }

    /**
     * Getter function for device orientation.
     * Passes the orientation variable
     *
     * @return orientation of device.
     */
    public float passOrientation(){
        return fusedHeading + headingOffset;
    }

    /**
     * Getter function for step count.
     * Returns the total number of steps detected since recording started.
     *
     * @return total step count.
     */
    public int getStepCount() {
        return stepCounter;
    }

    /**
     * Set the trajectory name for this recording.
     * This will be used in the protobuf trajectory_id field.
     *
     * @param name the name for this trajectory
     */
    public void setTrajectoryName(String name) {
        this.trajectoryName = (name != null) ? name : "";
        Log.i("SensorFusion", "setTrajectoryName() called with: " + this.trajectoryName);
    }

    /**
     * Get the trajectory name for this recording.
     *
     * @return the trajectory name
     */
    public String getTrajectoryName() {
        return trajectoryName;
    }

    /**
     * Return most recent sensor readings.
     *
     * Collects all most recent readings from movement and location sensors, packages them in a map
     * that is indexed by {@link SensorTypes} and makes it accessible for other classes.
     *
     * @return  Map of <code>SensorTypes</code> to float array of most recent values.
     */
    public Map<SensorTypes, float[]> getSensorValueMap() {
        Map<SensorTypes, float[]> sensorValueMap = new HashMap<>();
        sensorValueMap.put(SensorTypes.ACCELEROMETER, acceleration);
        sensorValueMap.put(SensorTypes.GRAVITY, gravity);
        sensorValueMap.put(SensorTypes.MAGNETICFIELD, magneticField);
        sensorValueMap.put(SensorTypes.GYRO, angularVelocity);
        sensorValueMap.put(SensorTypes.LIGHT, new float[]{light});
        sensorValueMap.put(SensorTypes.PRESSURE, new float[]{pressure});
        sensorValueMap.put(SensorTypes.PROXIMITY, new float[]{proximity});
        sensorValueMap.put(SensorTypes.GNSSLATLONG, getGNSSLatitude(false));
        sensorValueMap.put(SensorTypes.PDR, pdrProcessing.getPDRMovement());
        return sensorValueMap;
    }

    /**
     * Return the most recent list of WiFi names and levels.
     * Each Wifi object contains a BSSID and a level value.
     *
     * @return  list of Wifi objects.
     */
    public List<Wifi> getWifiList() {
        return this.wifiList;
    }

    /**
     * Get information about all the sensors registered in SensorFusion.
     *
     * @return  List of SensorInfo objects containing name, resolution, power, etc.
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
     * Necessary when classes want to act on a trajectory being successfully or unsuccessfully send
     * to the server. This grants access to observing the {@link ServerCommunications} instance
     * used by the SensorFusion class.
     *
     * @param observer  Instance implementing {@link Observer} class who wants to be notified of
     *                  events relating to sending and receiving trajectories.
     */
    public void registerForServerUpdate(Observer observer) {
        serverCommunications.registerObserver(observer);
    }

    /**
     * Get the estimated elevation value in meters calculated by the PDR class.
     * Elevation is relative to the starting position.
     *
     * @return  float of the estimated elevation in meters.
     */
    public float getElevation() {
        return this.elevation;
    }

    /**
     * Get an estimate by the PDR class whether it estimates the user is currently taking an elevator.
     *
     * @return  true if the PDR estimates the user is in an elevator, false otherwise.
     */
    public boolean getElevator() {
        return this.elevator;
    }

    /**
     * Estimates position of the phone based on proximity and light sensors.
     *
     * @return int 1 if the phone is by the ear, int 0 otherwise.
     */
    public int getHoldMode(){
        int proximityThreshold = 1, lightThreshold = 100; //holdMode: by ear=1, not by ear =0
        if(proximity<proximityThreshold && light>lightThreshold) { //unit cm
            return 1;
        }
        else{
            return 0;
        }
    }

    //endregion

    //region Start/Stop

    /**
     * Registers all device listeners and enables updates with the specified sampling rate.
     *
     * Should be called from {@link MainActivity} when resuming the application. Sampling rate is in
     * microseconds, IMU needs 100Hz, rest 1Hz
     *
     * @see MovementSensor handles SensorManager based devices.
     * @see WifiDataProcessor handles wifi data.
     * @see GNSSDataProcessor handles location data.
     */
    public void resumeListening() {
        accelerometerSensor.sensorManager.registerListener(this, accelerometerSensor.sensor, 10000, (int) maxReportLatencyNs);
        accelerometerSensor.sensorManager.registerListener(this, linearAccelerationSensor.sensor, 10000, (int) maxReportLatencyNs);
        accelerometerSensor.sensorManager.registerListener(this, gravitySensor.sensor, 10000, (int) maxReportLatencyNs);
        barometerSensor.sensorManager.registerListener(this, barometerSensor.sensor, (int) 1e6);
        gyroscopeSensor.sensorManager.registerListener(this, gyroscopeSensor.sensor, 10000, (int) maxReportLatencyNs);
        lightSensor.sensorManager.registerListener(this, lightSensor.sensor, (int) 1e6);
        proximitySensor.sensorManager.registerListener(this, proximitySensor.sensor, (int) 1e6);
        magnetometerSensor.sensorManager.registerListener(this, magnetometerSensor.sensor, 10000, (int) maxReportLatencyNs);
        stepDetectionSensor.sensorManager.registerListener(this, stepDetectionSensor.sensor, SensorManager.SENSOR_DELAY_NORMAL);
        rotationSensor.sensorManager.registerListener(this, rotationSensor.sensor, 10000, (int) maxReportLatencyNs);
        wifiProcessor.startListening();
        bleProcessor.startListening();
        gnssProcessor.startLocationUpdates();
    }

    /**
     * Un-registers all device listeners and pauses data collection.
     *
     * Should be called from {@link MainActivity} when pausing the application.
     *
     * @see MovementSensor handles SensorManager based devices.
     * @see WifiDataProcessor handles wifi data.
     * @see GNSSDataProcessor handles location data.
     */
    public void stopListening() {
        if(!saveRecording) {
            // Unregister sensor-manager based devices
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
            //The app often crashes here because the scan receiver stops after it has found the list.
            // It will only unregister one if there is to unregister
            try {
                this.wifiProcessor.stopListening();
            } catch (Exception e) {
                System.err.println("Wifi resumed before existing");
            }
            try {
                this.bleProcessor.stopListening();
            } catch (Exception e) {
                System.err.println("BLE stop error: " + e.getMessage());
            }
            // Stop receiving location updates
            this.gnssProcessor.stopUpdating();
        }
    }

    /**
     * Enables saving sensor values to the trajectory object.
     *
     * Sets save recording to true, resets the absolute start time and create new timer object for
     * periodically writing data to trajectory.
     *
     * @see Traj object for storing data.
     */
    public void startRecording() {
        // If wakeLock is null (e.g. not initialized or was cleared), reinitialize it.
        if (wakeLock == null) {
            PowerManager powerManager = (PowerManager) this.appContext.getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag");
        }
        wakeLock.acquire(31 * 60 * 1000L /*31 minutes*/);

        this.saveRecording = true;
        this.stepCounter = 0;
        this.trajectoryName = ""; // Reset trajectory name for new recording
        this.testPoints.clear();
        this.trajectoryLatLngs.clear();
        this.headingCalibrated = false;
        this.headingOffset = 0f;
        this.headingInitialized = false;
        this.lastGyroTimestampNs = 0;
        this.lastWifiMacs.clear();
        this.uniqueApMap.clear();
        this.uniqueBleMap.clear();
        this.absoluteStartTime = System.currentTimeMillis();
        this.bootTime = SystemClock.uptimeMillis();
        // Use custom trajectory name if set, otherwise use timestamp-based ID
        String trajId = (trajectoryName != null && !trajectoryName.isEmpty())
                ? trajectoryName
                : "traj_" + absoluteStartTime;
        // Protobuf trajectory class for sending sensor data to restful API
        this.trajectory = Traj.Trajectory.newBuilder()
                .setAndroidVersion(Build.VERSION.RELEASE)
                .setTrajectoryVersion(2.0f)
                .setTrajectoryId(trajId)
                .setStartTimestamp(absoluteStartTime)
                .setAccelerometerInfo(createInfoBuilder(accelerometerSensor))
                .setGyroscopeInfo(createInfoBuilder(gyroscopeSensor))
                .setMagnetometerInfo(createInfoBuilder(magnetometerSensor))
                .setBarometerInfo(createInfoBuilder(barometerSensor))
                .setLightSensorInfo(createInfoBuilder(lightSensor))
                .setRotationVectorInfo(createInfoBuilder(rotationSensor))
                .setProximityInfo(createInfoBuilder(proximitySensor));



        this.storeTrajectoryTimer = new Timer();
        this.storeTrajectoryTimer.schedule(new storeDataInTrajectory(), 0, TIME_CONST);
        this.pdrProcessing.resetPDR();
        if(settings.getBoolean("overwrite_constants", false)) {
            this.filter_coefficient = Float.parseFloat(settings.getString("accel_filter", "0.96"));
        } else {
            this.filter_coefficient = FILTER_COEFFICIENT;
        }
    }

    /**
     * Disables saving sensor values to the trajectory object.
     *
     * Check if a recording is in progress. If it is, it sets save recording to false, and cancels
     * the timer objects.
     *
     * @see Traj object for storing data.
     * @see SettingsFragment navigation that might cancel recording.
     */
    public void stopRecording() {
        // Only cancel if we are running
        if(this.saveRecording) {
            this.saveRecording = false;
            storeTrajectoryTimer.cancel();

            // Update trajectory ID with custom name if it was set
            if (trajectoryName != null && !trajectoryName.isEmpty()) {
                this.trajectory.setTrajectoryId(trajectoryName);
                Log.i("SensorFusion", "stopRecording(): Updated trajectory ID to: " + trajectoryName);
            } else {
                Log.w("SensorFusion", "stopRecording(): No custom trajectory name set, using default ID");
            }
        }
        if(wakeLock.isHeld()) {
            this.wakeLock.release();
        }
    }

    //endregion

    //region Test Points

    /**
     * Add a test point at the user's current position during recording.
     *
     * @param latitude  Current latitude
     * @param longitude Current longitude
     * @param altitude  Current altitude
     * @param floor     Current floor name (can be null)
     */
    public void addTestPoint(double latitude, double longitude, double altitude, String floor) {
        if (!saveRecording) return;
        long now = System.currentTimeMillis();
        long relativeTimestamp = now - absoluteStartTime;
        TestPoint tp = new TestPoint(relativeTimestamp, now, latitude, longitude, altitude, floor);
        testPoints.add(tp);
        Log.i("SensorFusion", "Test point #" + testPoints.size()
                + " added at " + relativeTimestamp + "ms"
                + " (" + latitude + ", " + longitude + ")"
                + (floor != null ? " floor=" + floor : ""));
    }

    /**
     * Get all test points recorded during this session.
     */
    public List<TestPoint> getTestPoints() {
        return testPoints;
    }

    /**
     * Add a trajectory LatLng point for the correction screen map display.
     */
    public void addTrajectoryPoint(double latitude, double longitude) {
        trajectoryLatLngs.add(new double[]{latitude, longitude});
    }

    /**
     * Get all trajectory LatLng points recorded during this session.
     */
    public List<double[]> getTrajectoryLatLngs() {
        return trajectoryLatLngs;
    }

    /**
     * Apply position correction offset to all trajectory data.
     * This modifies the trajectory coordinates before upload.
     *
     * @param latOffset Latitude offset to apply
     * @param lngOffset Longitude offset to apply
     */
    public void applyTrajectoryOffset(double latOffset, double lngOffset) {
        Log.i("SensorFusion", "Applying trajectory offset: lat=" + latOffset + ", lng=" + lngOffset);

        // Apply offset to display trajectory points
        for (double[] point : trajectoryLatLngs) {
            point[0] += latOffset;
            point[1] += lngOffset;
        }

        // Apply offset to test points - create new TestPoint objects since fields are final
        java.util.List<TestPoint> offsetTestPoints = new java.util.ArrayList<>();
        for (TestPoint tp : testPoints) {
            TestPoint newTp = new TestPoint(
                tp.relativeTimestamp,
                tp.absoluteTimestamp,
                tp.latitude + latOffset,
                tp.longitude + lngOffset,
                tp.altitude,
                tp.floor
            );
            offsetTestPoints.add(newTp);
        }
        testPoints.clear();
        testPoints.addAll(offsetTestPoints);

        // Apply offset to start location
        if (startLocation != null) {
            startLocation[0] += latOffset;
            startLocation[1] += lngOffset;
        }

        // Apply offset to GNSS data in trajectory builder
        // We need to rebuild the GNSS data with corrected coordinates
        java.util.List<Traj.GNSS_Sample> originalGnssData = trajectory.getGnssDataList();
        trajectory.clearGnssData();

        for (Traj.GNSS_Sample sample : originalGnssData) {
            Traj.GNSS_Sample.Builder newSample = sample.toBuilder();
            if (sample.hasPosition()) {
                Traj.GNSSPosition originalPos = sample.getPosition();
                Traj.GNSSPosition.Builder newPos = originalPos.toBuilder()
                        .setLatitude(originalPos.getLatitude() + latOffset)
                        .setLongitude(originalPos.getLongitude() + lngOffset);
                newSample.setPosition(newPos);
            }
            trajectory.addGnssData(newSample);
        }

        Log.i("SensorFusion", "Position offset applied to " + trajectoryLatLngs.size()
            + " trajectory points, " + testPoints.size() + " test points, and "
            + originalGnssData.size() + " GNSS samples");
    }

    /**
     * Get the absolute start time of the current recording.
     */
    public long getAbsoluteStartTime() {
        return absoluteStartTime;
    }

    //endregion

    //region Trajectory object

    /**
     * Send the trajectory object to servers.
     *
     * @see ServerCommunications for sending and receiving data via HTTPS.
     */
    /**
     * Check if trajectory has minimum required data for upload
     * @return error message if validation fails, null if valid
     */
    public String validateTrajectoryForUpload() {
        int wifiScanCount = trajectory.getWifiDataCount();
        int imuSampleCount = trajectory.getImuDataCount();
        int gnssSampleCount = trajectory.getGnssDataCount();

        Log.i("SensorFusion", "Trajectory validation - WiFi scans: " + wifiScanCount
            + ", IMU samples: " + imuSampleCount + ", GNSS samples: " + gnssSampleCount);

        if (wifiScanCount < 2) {
            return "Insufficient WiFi scans (" + wifiScanCount + "/2 minimum). Please record for a longer duration.";
        }
        if (imuSampleCount < 10) {
            return "Insufficient IMU data (" + imuSampleCount + " samples). Please record for a longer duration.";
        }

        return null; // Validation passed
    }

    public void sendTrajectoryToCloud() {
        // Set trajectory ID from stored name (set by naming dialog)
        Log.i("SensorFusion", "=== sendTrajectoryToCloud() called ===");
        Log.i("SensorFusion", "trajectoryName variable: '" + trajectoryName + "'");
        Log.i("SensorFusion", "Current trajectory ID before setting: " + trajectory.getTrajectoryId());

        if (trajectoryName != null && !trajectoryName.isEmpty()) {
            this.trajectory.setTrajectoryId(trajectoryName);
            Log.i("SensorFusion", "✅ Setting trajectory ID to: '" + trajectoryName + "'");
            Log.i("SensorFusion", "Trajectory ID after setting: " + trajectory.getTrajectoryId());
        } else {
            Log.w("SensorFusion", "⚠️ No custom trajectory name, using default ID: " + trajectory.getTrajectoryId());
        }

        // Write test points into protobuf before building
        for (TestPoint tp : testPoints) {
            Traj.GNSSPosition.Builder tpBuilder = Traj.GNSSPosition.newBuilder()
                    .setRelativeTimestamp(tp.relativeTimestamp)
                    .setLatitude(tp.latitude)
                    .setLongitude(tp.longitude)
                    .setAltitude(tp.altitude);
            if (tp.floor != null) {
                tpBuilder.setFloor(tp.floor);
            }
            trajectory.addTestPoints(tpBuilder);
        }

        // Write initial position from GNSS start location
        if (startLocation != null && (startLocation[0] != 0 || startLocation[1] != 0)) {
            trajectory.setInitialPosition(Traj.GNSSPosition.newBuilder()
                    .setRelativeTimestamp(0)
                    .setLatitude(startLocation[0])
                    .setLongitude(startLocation[1]));
        }

        // Write unique WiFi AP metadata table
        for (Traj.AP_Data.Builder apBuilder : uniqueApMap.values()) {
            trajectory.addApsData(apBuilder);
        }
        Log.i("SensorFusion", "Unique WiFi APs: " + uniqueApMap.size());

        // Write unique BLE device metadata table
        for (Traj.BleData.Builder bleBuilder : uniqueBleMap.values()) {
            trajectory.addBleData(bleBuilder);
        }
        Log.i("SensorFusion", "Unique BLE devices: " + uniqueBleMap.size());

        // Build object
        Traj.Trajectory sentTrajectory = trajectory.build();
        Log.i("SensorFusion", "=== Built trajectory ===");
        Log.i("SensorFusion", "Final trajectory ID: '" + sentTrajectory.getTrajectoryId() + "'");
        Log.i("SensorFusion", "Trajectory size: " + sentTrajectory.getSerializedSize() + " bytes");

        // Log trajectory statistics
        Log.i("SensorFusion", "=== Trajectory Upload Statistics ===");
        Log.i("SensorFusion", "WiFi scans: " + sentTrajectory.getWifiDataCount());
        Log.i("SensorFusion", "IMU samples: " + sentTrajectory.getImuDataCount());
        Log.i("SensorFusion", "GNSS samples: " + sentTrajectory.getGnssDataCount());
        Log.i("SensorFusion", "Test points: " + sentTrajectory.getTestPointsCount());
        Log.i("SensorFusion", "Unique WiFi APs: " + sentTrajectory.getApsDataCount());
        Log.i("SensorFusion", "Unique BLE devices: " + sentTrajectory.getBleDataCount());
        Log.i("SensorFusion", "=====================================");
        for (int i = 0; i < sentTrajectory.getTestPointsCount(); i++) {
            Traj.GNSSPosition tp = sentTrajectory.getTestPoints(i);
            Log.i("SensorFusion", "  Test point #" + (i + 1)
                    + ": t=" + tp.getRelativeTimestamp() + "ms"
                    + " lat=" + tp.getLatitude() + " lon=" + tp.getLongitude()
                    + " alt=" + tp.getAltitude()
                    + (tp.hasFloor() ? " floor=" + tp.getFloor() : ""));
        }
        // Pass object to communications object
        this.serverCommunications.sendTrajectory(sentTrajectory);
    }

    /**
     * Creates a {@link Traj.Sensor_Info} objects from the specified sensor's data.
     *
     * @param sensor    MovementSensor objects with populated sensorInfo fields
     * @return          Traj.SensorInfo object to be used in building the trajectory
     *
     * @see Traj            Trajectory object used for communication with the server
     * @see MovementSensor  class abstracting SensorManager based sensors
     */
    private Traj.Sensor_Info.Builder createInfoBuilder(MovementSensor sensor) {
        return Traj.Sensor_Info.newBuilder()
                .setName(sensor.sensorInfo.getName())
                .setVendor(sensor.sensorInfo.getVendor())
                .setResolution(sensor.sensorInfo.getResolution())
                .setPower(sensor.sensorInfo.getPower())
                .setVersion(sensor.sensorInfo.getVersion())
                .setType(sensor.sensorInfo.getType())
                .setMaxRange(sensor.sensorInfo.getMaxRange())
                .setFrequency(sensor.sensorInfo.getFrequency());
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Timer task to record data with the desired frequency in the trajectory class.
     *
     * Inherently threaded, runnables are created in {@link SensorFusion#startRecording()} and
     * destroyed in {@link SensorFusion#stopRecording()}.
     */
    private class storeDataInTrajectory extends TimerTask {
        public void run() {
            // Store IMU and magnetometer data in Trajectory class (nested format)
            trajectory.addImuData(Traj.Motion_Sample.newBuilder()
                    .setRelativeTimestamp(SystemClock.uptimeMillis()-bootTime)
                    .setAcc(Traj.Vector3.newBuilder()
                            .setX(acceleration[0])
                            .setY(acceleration[1])
                            .setZ(acceleration[2]))
                    .setGyr(Traj.Vector3.newBuilder()
                            .setX(angularVelocity[0])
                            .setY(angularVelocity[1])
                            .setZ(angularVelocity[2]))
                    .setRotationVector(Traj.Quaternion.newBuilder()
                            .setX(rotation[0])
                            .setY(rotation[1])
                            .setZ(rotation[2])
                            .setW(rotation[3]))
                    .setStepCount(stepCounter))
                    .addPositionData(Traj.Position_Sample.newBuilder()
                            .setMag(Traj.Vector3.newBuilder()
                                    .setX(clamp(magneticField[0], -999f, 999f))
                                    .setY(clamp(magneticField[1], -999f, 999f))
                                    .setZ(clamp(magneticField[2], -999f, 999f)))
                            .setRelativeTimestamp(SystemClock.uptimeMillis()-bootTime))
            ;

            // Divide timer with a counter for storing data every 1 second
            if (counter == 99) {
                counter = 0;
                // Store pressure, light and proximity data
                if (barometerSensor.sensor != null) {
                    trajectory.addPressureData(Traj.Pressure_Sample.newBuilder()
                                    .setPressure(pressure)
                                    .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime))
                            .addLightData(Traj.Light_Sample.newBuilder()
                                    .setLight(light)
                                    .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                                    .build());
                }
                // Store proximity data
                if (proximitySensor.sensor != null) {
                    trajectory.addProximityData(Traj.ProximityReading.newBuilder()
                            .setDistance(proximity)
                            .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime));
                }
            }
            else {
                counter++;
            }

        }
    }

    //endregion

}
