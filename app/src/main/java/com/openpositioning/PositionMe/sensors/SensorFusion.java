package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.app.ActivityManager;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
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
    // Toggle for heading debug logs across modules
    public static final boolean DEBUG_HEADING = false;
    //Tuning value for low pass filter
    private static final float ALPHA = 0.8f;
    // String for creating WiFi fingerprint JSO N object
    private static final String WIFI_FINGERPRINT= "wf";
    private static final String DEFAULT_COLLECTION_VENUE = "traj";
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
    private GNSSDataProcessor gnssProcessor;
    // Data listener

    private BleDataProcessor bleProcessor;
    private List<BLE> bleList;
    // BLE values
    private Set<String> recordedBleMacs = new HashSet<>();
    private String lastBleFingerprintSignature;

    private long lastBleUiToastMs = 0;


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
    // Counters for dividing timer to record data every 1 second/ every 5 seconds
    private int counter;
    private int secondCounter;

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
    // Throttling timestamp for heading debug logs (rotation vector)
    private long headingDbgRotvecLastLogMs = 0;
    private int stepCounter ;
    // Derived values
    private float elevation;
    private boolean elevator;
    // Location values
    private float latitude;
    private float longitude;
    private float[] startLocation;
    // Wifi values
    private List<Wifi> wifiList;
    private Set<Long> recordedApMacs = new HashSet<>();
    private String lastFingerprintSignature;
    // 当前录制会话的轨迹标识，用于上报 Wi-Fi 指纹
    private String trajectoryId;
    // 用户选择的场地标识，默认占位符
    private String collectionVenue = DEFAULT_COLLECTION_VENUE;

    // For Marker
    private double gnssAltitude = 0.0;
    private final List<Traj.GNSSPosition> testPoints = new ArrayList<>();



    // Over time accelerometer magnitude values since last step
    private List<Double> accelMagnitude;

    // PDR calculation class
    private PdrProcessing pdrProcessing;

    // Trajectory displaying class
    private PathView pathView;
    // WiFi positioning object
    private WiFiPositioning wiFiPositioning;

    // Convert "AA:BB:CC:DD:EE:FF" -> int64 (same idea as WiFi bssid long)
    private long macStringToLong(String mac) {
        if (mac == null) return 0L;
        // remove ":" or "-"
        String hex = mac.replace(":", "").replace("-", "");
        if (hex.isEmpty()) return 0L;
        try {
            return Long.parseUnsignedLong(hex, 16);
        } catch (Exception e) {
            return 0L;
        }
    }

    private String safeBleName(String name) {
        if (name == null) return "unknown";
        name = name.trim();
        return name.isEmpty() ? "unknown" : name;
    }


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
        // Counters to track elements with slower frequency
        this.counter = 0;
        this.secondCounter = 0;
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

        // Initialise data collection devices (unchanged)...
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
        // Listener based devices
        this.wifiProcessor = new WifiDataProcessor(context);
        wifiProcessor.registerObserver(this);

        this.bleProcessor = new BleDataProcessor(context);
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

//                // Debug logging
//                Log.v("SensorFusion",
//                        "Added new linear accel magnitude: " + accelMagFiltered
//                                + "; accelMagnitude size = " + accelMagnitude.size());

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
                break;

            case Sensor.TYPE_ROTATION_VECTOR:
                this.rotation = sensorEvent.values.clone();
                float[] rotationVectorDCM = new float[9];
                SensorManager.getRotationMatrixFromVector(rotationVectorDCM, this.rotation);
                SensorManager.getOrientation(rotationVectorDCM, this.orientation);

                Log.d("HeadingDbg", "onSensorChanged azimuth(rad)=" + orientation[0]);

                if (DEBUG_HEADING) {
                    long now = SystemClock.elapsedRealtime();
                    if (now - headingDbgRotvecLastLogMs >= 1000) {
                        Log.d("HeadingDbg", "rotvec azimuth(rad)=" + orientation[0]);
                        headingDbgRotvecLastLogMs = now;
                    }
                }
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

                    float[] newCords = this.pdrProcessing.updatePdr(
                            stepTime,
                            this.accelMagnitude,
                            this.orientation[0]
                    );

                    // Clear the accelMagnitude after using it
                    this.accelMagnitude.clear();


                    if (saveRecording) {
                        this.pathView.drawTrajectory(newCords);
                        stepCounter++;
                        trajectory.addPdrData(Traj.RelativePosition.newBuilder()
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
            if(saveRecording) {
                long relativeTimestamp = System.currentTimeMillis() - absoluteStartTime;
                Traj.GNSSPosition.Builder position = Traj.GNSSPosition.newBuilder()
                        .setRelativeTimestamp(relativeTimestamp)
                        .setLatitude(latitude)
                        .setLongitude(longitude)
                        .setAltitude(altitude);

                Traj.GNSSReading.Builder gnssBuilder = Traj.GNSSReading.newBuilder()
                        .setPosition(position)
                        .setAccuracy(accuracy)
                        .setSpeed(speed)
                        .setProvider(provider);

                if (location.hasBearing()) {
                    gnssBuilder.setBearing(location.getBearing());
                }

                trajectory.addGnssData(gnssBuilder);
            }
            gnssAltitude = location.getAltitude();

        }
    }

    /**
     * {@inheritDoc}
     *
     * Receives updates from {@link WifiDataProcessor}.
     *
     * @see WifiDataProcessor object for wifi scanning.
     */
    /*@Override
    public void update(Object[] wifiList) {
        // Save newest wifi values to local variable
        this.wifiList = Stream.of(wifiList).map(o -> (Wifi) o).collect(Collectors.toList());

        if(this.saveRecording) {
            List<Wifi> sortedWifi = new ArrayList<>(this.wifiList);
            sortedWifi.sort((a, b) -> Long.compare(a.getBssid(), b.getBssid()));
            StringBuilder signatureBuilder = new StringBuilder();
            int apCount = 0;
            for (Wifi data : sortedWifi) {
                if (data.getBssid() == 0) {
                    // BSSID=0 代表未知/解析失败，跳过避免与真实 AP 去重冲突
                    continue;
                }
                signatureBuilder.append(data.getBssid())
                        .append(':')
                        .append(data.getLevel())
                        .append(';');
                apCount++;
            }
            String fingerprintSignature = signatureBuilder.toString();
            boolean isDuplicateFingerprint = fingerprintSignature.equals(lastFingerprintSignature);
            if (isDuplicateFingerprint) {
                Log.d("WifiDedup", "Skipping duplicate fingerprint: " + fingerprintSignature + " apCount=" + apCount);
                Log.d("SensorFusion", "Skipping duplicate WiFi fingerprint: " + fingerprintSignature);
            } else {
                long sampleTimestamp = SystemClock.uptimeMillis() - bootTime;
                Traj.Fingerprint.Builder fingerprint = Traj.Fingerprint.newBuilder()
                        .setRelativeTimestamp(sampleTimestamp);

                for (Wifi data : this.wifiList) {
                    if (data.getBssid() == 0) {
                        // 无有效 BSSID 时不记录指纹/元数据，防止 0 被当成唯一键
                        continue;
                    }
                    fingerprint.addRfScans(Traj.RFScan.newBuilder()
                            .setRelativeTimestamp(sampleTimestamp)
                            .setMac(data.getBssid())
                            .setRssi(data.getLevel()));

                    if (!recordedApMacs.contains(data.getBssid())) {
                        boolean rttCapable = data.isRttSupported();
                        // 复用统一规范化逻辑，避免分支重复
                        String ssid = WifiDataProcessor.normalizeSsid(data.getSsid());
                        long frequency = WifiDataProcessor.normalizeFrequency(data.getFrequency());
                        Traj.WiFiAPData.Builder apData = Traj.WiFiAPData.newBuilder()
                                .setMac(data.getBssid())
                                .setSsid(ssid)
                                .setFrequency(frequency)
                                .setRttEnabled(rttCapable);
                        trajectory.addApsData(apData);
                        recordedApMacs.add(data.getBssid());
                        String freqLabel = (frequency == 0) ? "0(unknown)" : String.valueOf(frequency);
                        Log.d("SensorFusion", "AP data added: bssid=" + data.getBssid()
                                + " ssid=" + ssid
                                + " freq=" + freqLabel
                                + " rtt=" + rttCapable);
                    }
                }
                int rfCount = fingerprint.getRfScansCount();
                Log.d("WifiDedup", "Accepted fingerprint: " + fingerprintSignature + " apCount=" + rfCount);
                // Adding WiFi fingerprint data to Trajectory
                this.trajectory.addWifiFingerprints(fingerprint);
                Log.d("SensorFusion", "WiFi fingerprint added: count="
                        + this.trajectory.getWifiFingerprintsCount()
                        + " apCount=" + fingerprint.getRfScansCount());
                lastFingerprintSignature = fingerprintSignature;
            }
        }
        createWifiPositioningRequest();
    }*/

    @Override
    public void update(Object[] objList) {
        if (objList == null || objList.length == 0) return;

        // Case 1: WiFi update
        if (objList[0] instanceof Wifi) {
            Object[] wifiArr = objList;

            this.wifiList = Stream.of(wifiArr)
                    .map(o -> (Wifi) o)
                    .collect(Collectors.toList());

            // === keep ALL your existing WiFi fingerprint code here ===
            // (the whole "if(saveRecording) { ... trajectory.addWifiFingerprints ... }"
            //  plus createWifiPositioningRequest(); )

            if (this.saveRecording) {
                List<Wifi> sortedWifi = new ArrayList<>(this.wifiList);
                sortedWifi.sort((a, b) -> Long.compare(a.getBssid(), b.getBssid()));
                StringBuilder signatureBuilder = new StringBuilder();
                for (Wifi data : sortedWifi) {
                    signatureBuilder.append(data.getBssid())
                            .append(':')
                            .append(data.getLevel())
                            .append(';');
                }
                String fingerprintSignature = signatureBuilder.toString();
                boolean isDuplicateFingerprint = fingerprintSignature.equals(lastFingerprintSignature);
                if (isDuplicateFingerprint) {
                    Log.d("SensorFusion", "Skipping duplicate WiFi fingerprint: " + fingerprintSignature);
                }

                long sampleTimestamp = SystemClock.uptimeMillis() - bootTime;
                Traj.Fingerprint.Builder fingerprint = Traj.Fingerprint.newBuilder()
                        .setRelativeTimestamp(sampleTimestamp);

                if (!isDuplicateFingerprint) {
                    for (Wifi data : this.wifiList) {
                        fingerprint.addRfScans(Traj.RFScan.newBuilder()
                                .setRelativeTimestamp(sampleTimestamp)
                                .setMac(data.getBssid())
                                .setRssi(data.getLevel()));

                        if (!recordedApMacs.contains(data.getBssid())) {
                            boolean rttCapable = data.isRttSupported();
                            String ssid = data.getSsid();
                            if (ssid == null || ssid.isEmpty() || "<unknown ssid>".equalsIgnoreCase(ssid)) {
                                ssid = "hidden";
                            } else if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                                ssid = ssid.substring(1, ssid.length() - 1);
                                if (ssid.isEmpty()) {
                                    ssid = "hidden";
                                }
                            }
                            long frequency = data.getFrequency();
                            if (frequency <= 0) frequency = 0;

                            Traj.WiFiAPData.Builder apData = Traj.WiFiAPData.newBuilder()
                                    .setMac(data.getBssid())
                                    .setSsid(ssid)
                                    .setFrequency(frequency)
                                    .setRttEnabled(rttCapable);

                            trajectory.addApsData(apData);
                            recordedApMacs.add(data.getBssid());
                        }
                    }
                    this.trajectory.addWifiFingerprints(fingerprint);
                    lastFingerprintSignature = fingerprintSignature;
                }
            }

            createWifiPositioningRequest();
            return;
        }

        // Case 2: BLE update
        if (objList[0] instanceof BLE) {

            Log.d("BLE_PIPE", "SensorFusion.update(): got BLE count=" + objList.length);

            BLE first = (BLE) objList[0];
            Log.d("BLE_PIPE", "First BLE: mac=" + first.getMac()
                    + " rssi=" + first.getRssi()
                    + " name=" + first.getName());

            // 1) 保存 bleList
            Object[] bleArr = objList;
            this.bleList = Stream.of(bleArr)
                    .map(o -> (BLE) o)
                    .collect(Collectors.toList());

            // ✅ 2) 不管是否 recording，都给 UI 一个提示（3秒一次）
            long now = SystemClock.uptimeMillis();
            if (appContext != null && (now - lastBleUiToastMs) > 3000) {
                String msg = "BLE ok: count=" + objList.length
                        + (saveRecording && trajectory != null
                        ? (" bleFp=" + trajectory.getBleFingerprintsCount()
                        + " bleData=" + trajectory.getBleDataCount())
                        : " (not recording)");
                android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show();
                lastBleUiToastMs = now;
            }

            // 3) 只有 recording 才写入 traj
            if (this.saveRecording && trajectory != null) {

                // fingerprint signature：mac+rssi 排序拼接（防重复）
                List<BLE> sortedBle = new ArrayList<>(this.bleList);
                sortedBle.sort((a, b) -> {
                    String ma = (a.getMac() == null) ? "" : a.getMac();
                    String mb = (b.getMac() == null) ? "" : b.getMac();
                    return ma.compareTo(mb);
                });

                StringBuilder sig = new StringBuilder();
                for (BLE d : sortedBle) sig.append(d.getMac()).append(':').append(d.getRssi()).append(';');
                String bleFingerprintSignature = sig.toString();

                if (bleFingerprintSignature.equals(lastBleFingerprintSignature)) {
                    Log.d("SensorFusion", "Skipping duplicate BLE fingerprint");
                    return;
                }

                long sampleTimestamp = SystemClock.uptimeMillis() - bootTime;

                Traj.Fingerprint.Builder bleFp = Traj.Fingerprint.newBuilder()
                        .setRelativeTimestamp(sampleTimestamp);

                for (BLE d : this.bleList) {
                    long macLong = macStringToLong(d.getMac());

                    bleFp.addRfScans(
                            Traj.RFScan.newBuilder()
                                    .setRelativeTimestamp(sampleTimestamp)
                                    .setMac(macLong)
                                    .setRssi(d.getRssi())
                    );

                    String macStr = d.getMac();
                    if (macStr != null && !recordedBleMacs.contains(macStr)) {
                        Traj.BleData.Builder bleData = Traj.BleData.newBuilder()
                                .setMacAddress(macStr)
                                .setName(safeBleName(d.getName()))
                                .setTxPowerLevel(0)
                                .setAdvertiseFlags(0);

                        trajectory.addBleData(bleData);
                        recordedBleMacs.add(macStr);
                    }
                }

                trajectory.addBleFingerprints(bleFp);
                lastBleFingerprintSignature = bleFingerprintSignature;

                Log.d("BLE_PIPE",
                        "traj updated: bleFpCount=" + trajectory.getBleFingerprintsCount()
                                + ", bleDataCount=" + trajectory.getBleDataCount()
                                + ", lastFpScans=" + bleFp.getRfScansCount());
            }

            return;
        }
 
    }




    /**
     * Function to create a request to obtain a wifi location for the obtained wifi fingerprint
     *
     */
    private void createWifiPositioningRequest(){
        // Try catch block to catch any errors and prevent app crashing
        try {
            // Creating a JSON object to store the WiFi access points
            JSONObject wifiAccessPoints=new JSONObject();
            for (Wifi data : this.wifiList){
                if (data.getBssid() == 0) {
                    // 0 代表未知 BSSID，过滤避免服务器误解析或键冲突
                    continue;
                }
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }
            // Creating POST Request
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
            // 绑定当前轨迹 ID；未在录制时为空则不写入，保持行为最小化
            if (trajectoryId != null && !trajectoryId.isEmpty()) {
                wifiFingerPrint.put("trajectory_id", trajectoryId);
            }
            this.wiFiPositioning.request(wifiFingerPrint);
        } catch (JSONException e) {
            // Catching error while making JSON object, to prevent crashes
            // Error log to keep record of errors (for secure programming and maintainability)
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

    public synchronized void setCollectionVenue(String venueId) {
        this.collectionVenue = sanitiseVenueTagStatic(venueId);
        if (this.trajectory == null) {
            return;
        }
        String existingId = this.trajectory.getTrajectoryId();
        String suffix;
        String[] parts = existingId.split("_", 3);
        if (parts.length == 3) {
            suffix = parts[1] + "_" + parts[2];
        } else {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(new Date());
            String shortUuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            suffix = timestamp + "_" + shortUuid;
        }
        this.trajectory.setTrajectoryId(this.collectionVenue + "_" + suffix);
    }

    public synchronized String getCollectionVenue() {
        return collectionVenue;
    }

    private String sanitiseVenueTag(String venue) {
        return sanitiseVenueTagStatic(venue);
    }

    static String sanitiseVenueTagStatic(String venue) {
        if (venue == null) {
            return DEFAULT_COLLECTION_VENUE;
        }
        String normalised = venue.trim().toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_", "")
                .replaceAll("_$", "");
        if (normalised.isEmpty()) {
            return DEFAULT_COLLECTION_VENUE;
        }
        return normalised;
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
        return orientation[0];
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
        rotationSensor.sensorManager.registerListener(this, rotationSensor.sensor, (int) 1e6);
        wifiProcessor.startListening();
        gnssProcessor.startLocationUpdates();
        bleProcessor.startListening();

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
                this.wifiProcessor.stopListening(); //error here?
            } catch (Exception e) {
                System.err.println("Wifi resumed before existing");
            }
            // Stop receiving location updates
            this.gnssProcessor.stopUpdating();
            try {
                this.bleProcessor.stopListening();
            } catch (Exception ignored) {}

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
        // 确保录制期间 CPU 常驻，避免重复 acquire
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(31 * 60 * 1000L /*31 minutes*/);
        }

        // 录制前校验电池优化/后台限制状态，并提示用户
        verifyAlwaysOnReadiness();

        this.saveRecording = true;
        this.stepCounter = 0;
        this.absoluteStartTime = System.currentTimeMillis();
        this.bootTime = SystemClock.uptimeMillis();
        String venueOrBuilding = sanitiseVenueTag(collectionVenue);
        this.collectionVenue = venueOrBuilding;
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(new Date());
        String shortUuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        this.trajectoryId = venueOrBuilding + "_" + timestamp + "_" + shortUuid;

        // Protobuf trajectory class for sending sensor data to restful API
        this.trajectory = Traj.Trajectory.newBuilder()
                .setTrajectoryId(trajectoryId)
                .setTrajectoryVersion(2.0f)
                .setAndroidVersion(Build.VERSION.RELEASE)
                .setStartTimestamp(absoluteStartTime)
                .setAccelerometerInfo(createInfoBuilder(accelerometerSensor))
                .setGyroscopeInfo(createInfoBuilder(gyroscopeSensor))
                .setMagnetometerInfo(createInfoBuilder(magnetometerSensor))
                .setBarometerInfo(createInfoBuilder(barometerSensor))
                .setLightSensorInfo(createInfoBuilder(lightSensor));

        this.recordedApMacs = new HashSet<>();
        this.lastFingerprintSignature = null;
        // +++ add these for BLE +++
        this.recordedBleMacs = new HashSet<>();
        this.lastBleFingerprintSignature = null;

        this.storeTrajectoryTimer = new Timer();
        this.storeTrajectoryTimer.schedule(new storeDataInTrajectory(), 0, TIME_CONST);
        this.pdrProcessing.resetPDR();
        if(settings.getBoolean("overwrite_constants", false)) {
            this.filter_coefficient = Float.parseFloat(settings.getString("accel_filter", "0.96"));
        } else {
            this.filter_coefficient = FILTER_COEFFICIENT;
        }
        Log.d("BLE_PIPE", "startRecording(): saveRecording=" + saveRecording);

    }

    /**
     * 校验“始终运行”相关系统状态：电池优化、后台限制、省电模式。
     * 仅提示用户，不强制跳转。
     */
    private void verifyAlwaysOnReadiness() {
        String pkg = appContext.getPackageName();
        PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);

        boolean ignoringOpt = false;
        boolean powerSave = false;
        boolean bgRestricted = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ignoringOpt = pm != null && pm.isIgnoringBatteryOptimizations(pkg);
            }
            powerSave = pm != null && pm.isPowerSaveMode();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                bgRestricted = am != null && am.isBackgroundRestricted();
            }
        } catch (Exception e) {
            Log.w("SensorFusion", "电池/后台状态检查失败: " + e.getMessage());
            Toast.makeText(appContext, "电池优化状态未知，请手动确认", Toast.LENGTH_LONG).show();
            return;
        }

        if (!ignoringOpt || powerSave || bgRestricted) {
            StringBuilder warn = new StringBuilder("检测到可能的后台限制：");
            if (!ignoringOpt) warn.append("电池优化未豁免; ");
            if (powerSave) warn.append("省电模式开启; ");
            if (bgRestricted) warn.append("后台限制开启; ");
            Log.w("SensorFusion", warn.toString());
            Toast.makeText(appContext,
                    "请在设置中关闭电池优化/省电/后台限制，确保录制不中断",
                    Toast.LENGTH_LONG).show();
        } else {
            Log.i("SensorFusion", "电池优化/后台限制检查通过：已豁免优化且未开启省电/后台限制。");
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
        }
        if(wakeLock.isHeld()) {
            this.wakeLock.release();
        }
    }

    /**
     * NEW METHOD: Sets the Venue ID for the current trajectory recording.
     * This should be called before sendTrajectoryToCloud().
     * @param venueId The ID of the venue selected by the user.
     */
    public void setVenueIdForTrajectory(String venueId) {
        this.collectionVenue = sanitiseVenueTagStatic(venueId);
        if (this.trajectory != null) {
            applyVenuePrefixToTrajectoryId(this.trajectory, this.collectionVenue);
            Log.i("SensorFusion", "Venue tag '" + this.collectionVenue + "' applied to trajectoryId");
        } else {
            Log.w("SensorFusion", "Trajectory not ready; venue tag cached as " + this.collectionVenue);
        }
    }

    // Visible for tests; prefixes the trajectory_id with venue tag when missing.
    static void applyVenuePrefixToTrajectoryId(Traj.Trajectory.Builder trajectoryBuilder, String venueId) {
        if (trajectoryBuilder == null) {
            return;
        }
        String safeVenue = sanitiseVenueTagStatic(venueId);
        String existingId = trajectoryBuilder.getTrajectoryId();
        if (existingId == null || existingId.isEmpty()) {
            trajectoryBuilder.setTrajectoryId(safeVenue);
            return;
        }
        String prefix = safeVenue + "_";
        if (!existingId.startsWith(prefix)) {
            trajectoryBuilder.setTrajectoryId(prefix + existingId);
        }
    }


    //endregion

    //region Trajectory object

    /**
     * Send the trajectory object to servers.
     *
     * @see ServerCommunications for sending and receiving data via HTTPS.
     */
    public void sendTrajectoryToCloud() {
        // Build object
        Traj.Trajectory sentTrajectory = trajectory.build();
        Log.d("MARKER", "UPLOAD proto test_points_count=" + sentTrajectory.getTestPointsCount()
                + " trajectory_id=" + sentTrajectory.getTrajectoryId());
        // Pass object to communications object
        this.serverCommunications.sendTrajectory(sentTrajectory);
    }

    public LatLng getCurrentGnssLatLng() {
        // 还没拿到定位时，lat/lon 可能为 0
        if (latitude == 0f && longitude == 0f) return null;
        return new LatLng(latitude, longitude);
    }

    /**
     * Add a user-created test point (timestamped marker) into the trajectory proto.
     * This ensures the marker is uploaded together with the rest of the trajectory data.
     *
     * @param position  GNSS lat/lon at the time of the marker tap
     * @param altitudeM altitude in metres (if unavailable, pass 0)
     */
    public void addTestPoint(@NonNull LatLng position, double altitudeM) {
        if (trajectory == null) {
            Log.w("SensorFusion", "Trajectory not initialized; skip adding test point");
            return;
        }

        long relativeTimestamp = System.currentTimeMillis() - absoluteStartTime;

        Traj.GNSSPosition testPoint = Traj.GNSSPosition.newBuilder()
                .setRelativeTimestamp(relativeTimestamp)
                .setLatitude(position.latitude)
                .setLongitude(position.longitude)
                .setAltitude(altitudeM)
                .build();

        trajectory.addTestPoints(testPoint);
        Log.d("MARKER", "PROTO test_points_count=" + trajectory.getTestPointsCount());
        testPoints.add(testPoint);
    }

    public void clearTestPoints() {
        testPoints.clear();
    }

    // 轨迹里的 relative_timestamp 必须是“相对 start_timestamp 的毫秒”
// 你们工程里 start_timestamp 对应 bootTime，所以这里用 uptimeMillis - bootTime
    public long getRelativeTimestampMs() {
        if (bootTime == 0) return 0;
        return SystemClock.uptimeMillis() - bootTime;
    }

    public double getCurrentGnssAltitude() {
        return gnssAltitude;
    }


    /**
     * Creates a {@link Traj.SensorInfo} objects from the specified sensor's data.
     *
     * @param sensor    MovementSensor objects with populated sensorInfo fields
     * @return          Traj.SensorInfo object to be used in building the trajectory
     *
     * @see Traj            Trajectory object used for communication with the server
     * @see MovementSensor  class abstracting SensorManager based sensors
     */
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
     *
     * Inherently threaded, runnables are created in {@link SensorFusion#startRecording()} and
     * destroyed in {@link SensorFusion#stopRecording()}.
     */
    private class storeDataInTrajectory extends TimerTask {
        public void run() {
            // Store IMU and magnetometer data in Trajectory class
            long relativeTimestamp = SystemClock.uptimeMillis() - bootTime;

            Traj.Vector3 accVector = Traj.Vector3.newBuilder()
                    .setX(acceleration[0])
                    .setY(acceleration[1])
                    .setZ(acceleration[2])
                    .build();

            Traj.Vector3 gyrVector = Traj.Vector3.newBuilder()
                    .setX(angularVelocity[0])
                    .setY(angularVelocity[1])
                    .setZ(angularVelocity[2])
                    .build();

            Traj.Quaternion rotationQuat = Traj.Quaternion.newBuilder()
                    .setX(rotation[0])
                    .setY(rotation[1])
                    .setZ(rotation[2])
                    .setW(rotation[3])
                    .build();

            trajectory.addImuData(Traj.IMUReading.newBuilder()
                    .setRelativeTimestamp(relativeTimestamp)
                    .setAcc(accVector)
                    .setGyr(gyrVector)
                    .setRotationVector(rotationQuat)
                    .setStepCount(stepCounter));

            Traj.Vector3 magVector = Traj.Vector3.newBuilder()
                    .setX(magneticField[0])
                    .setY(magneticField[1])
                    .setZ(magneticField[2])
                    .build();

            trajectory.addMagnetometerData(Traj.MagnetometerReading.newBuilder()
                    .setRelativeTimestamp(relativeTimestamp)
                    .setMag(magVector));

            // Divide timer with a counter for storing data every 1 second
            if (counter == 99) {
                counter = 0;
                // Store pressure and light data
                if (barometerSensor.sensor != null) {
                    long timestamp = SystemClock.uptimeMillis() - bootTime;
                    trajectory.addPressureData(Traj.BarometerReading.newBuilder()
                                    .setPressure(pressure)
                                    .setRelativeTimestamp(timestamp))
                            .addLightData(Traj.LightReading.newBuilder()
                                    .setLight(light)
                                    .setRelativeTimestamp(timestamp)
                                    .build());
                }

                // Divide the timer for storing AP data every 5 seconds
                if (secondCounter == 4) {
                    secondCounter = 0;
                    //Current Wifi Object
                    Wifi currentWifi = wifiProcessor.getCurrentWifiData();
                    trajectory.addApsData(Traj.WiFiAPData.newBuilder()
                            .setMac(currentWifi.getBssid())
                            .setSsid(currentWifi.getSsid())
                            .setFrequency(currentWifi.getFrequency())
                            .setRttEnabled(currentWifi.isRttSupported()));
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

}
