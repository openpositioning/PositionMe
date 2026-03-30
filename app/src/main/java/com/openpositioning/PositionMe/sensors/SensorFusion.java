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
import androidx.annotation.RequiresApi;
import androidx.preference.PreferenceManager;

import android.net.wifi.ScanResult;
import android.net.wifi.rtt.RangingResult;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
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

import com.openpositioning.PositionMe.sensors.model.TestPoint;

import com.openpositioning.PositionMe.sensors.ParticleFilter;
import com.openpositioning.PositionMe.utils.CoordinateConverter;

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

    //Save the last WiFi fingerprint for deduplication.
    private Traj.Fingerprint lastWifiFingerprint = null;
    private List<BleDataProcessor.BleDevice> lastBleDeviceList = null;


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
    private volatile List<String> latestBssids = new ArrayList<>();

    private BleDataProcessor bleProcessor;
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
    private int stepCounter ;
    // Derived values
    private float elevation;
    private boolean elevator;
    // Location values
    private float latitude;
    private float longitude;
    private float altitude = 0.0f;

    //add trajectoryName
    private String trajectoryName = "";

    // Initial position data
    private float initialLatitude = 0.0f;
    private float initialLongitude = 0.0f;
    private float initialAltitude = 0.0f;

    // Initial orientation data (rotation vector - quaternion)
    private float[] initialRotation = new float[4];

    private float[] startLocation;
    // Wifi values
    private List<Wifi> wifiList;
    private List<BleDataProcessor.BleDevice> bleDeviceList;


    // Over time accelerometer magnitude values since last step
    private List<Double> accelMagnitude;

    // PDR calculation class
    private PdrProcessing pdrProcessing;

    // Trajectory displaying class
    private PathView pathView;
    // WiFi positioning object
    private WiFiPositioning wiFiPositioning;

    // Particle filter for sensor fusion positioning
    private ParticleFilter particleFilter;

    // Coordinate converter for WGS84 ↔ East-North transformation
    private CoordinateConverter coordinateConverter;

    // Previous PDR cumulative position, used to compute per-step displacement
    private float prevPdrX = 0f;
    private float prevPdrY = 0f;

    // Gyroscope-integrated heading with rotation-vector correction (radians)
    private float fusedHeading = 0f;
    private boolean headingInitialised = false;
    private long lastGyroTimestampMs = 0;

    // Floor change detection for particle reset
    private int lastKnownFloor = 0;

    private ExtendedKalmanFilter ekfPositioning;
    // true = use EKF output, false = use Particle Filter output
    private boolean useEKF = false;



    // Last raw position from each individual source, for colour-coded display
    private double[] lastGnssLatLon   = null;   // {lat, lon}
    private double[] lastWifiLatLon   = null;   // {lat, lon}
    private double[] lastPdrLatLon    = null;   // {lat, lon}

    // Previous valid GNSS position in ENU (metres), used to derive heading from consecutive fixes
    private float[] lastGnssEnu = null;

    // Last WiFi position in ENU (metres), cached for stationary soft-update
    private float[] lastWifiEnu = null;

    // PF best-estimate position recorded at each WiFi update.
    // Used to compute the PF movement direction between consecutive WiFi observations,
    // so that WiFi fixes contradicting the direction of travel can be penalised.
    private float[] lastPfPositionForTrend = null;

    // Timestamp and ENU position of the last WiFi fix that was accepted by the filters.
    // Used for velocity-based rejection and consecutive-fix consistency checks.
    private long lastWifiUpdateTimeMs = 0;
    private float[] lastAcceptedWifiEnu = null;

    // Gradual EKF correction: when a large WiFi jump is accepted while moving,
    // the full state correction is spread over this many PDR steps instead of
    // being applied in a single frame. This prevents visible trajectory backjumps.
    private static final int CORRECTION_STEPS = 5;
    private float pendingCorrectionX = 0f;
    private float pendingCorrectionY = 0f;
    private int pendingCorrectionStepsLeft = 0;

    private final List<TestPoint> testPoints = new ArrayList<>();

    boolean enuBaked = false;

    private IndoorMapManager indoorMapManager;

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
     * Get current BLE device list
     * @return List of BLE devices from last scan
     */
    public List<BleDataProcessor.BleDevice> getBleDeviceList() {
        return this.bleDeviceList;
    }

    /**
     * Get current trajectory being recorded
     * @return Trajectory protobuf builder
     */
    public Traj.Trajectory.Builder getTrajectory() {
        return this.trajectory;
    }

    public void setIndoorMapManager(IndoorMapManager indoorMapManager) {
        Log.i("SensorFusion", "set indoormapmanager");
        this.indoorMapManager = indoorMapManager;
        if(coordinateConverter == null){
            Log.i("SensorFusion", "coordinateConverter null");
        }
        if (!enuBaked && this.indoorMapManager != null && coordinateConverter != null) {
            Log.i("SensorFusion", "Baking ENU coordinates from setIndoorMapManager");
            this.indoorMapManager.bakeEnuCoordinates(coordinateConverter);
            enuBaked = true;
        }
    }


    /**
     * Static function to access singleton instance of SensorFusion.
     *
     * @return  singleton instance of SensorFusion class.
     */
    public static SensorFusion getInstance() {
        return sensorFusion;
    }

    public float[] getInitialRotation() {
        return this.initialRotation;
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

    public void addTestPoint(long timestampMillis, double lat, double lon) {
        testPoints.add(new TestPoint(timestampMillis, lat, lon));
    }

//    debugging-
    public List<TestPoint> getTestPoints() {
        return testPoints;
    }

    public void addTestPoint(long timestamp) {
        Log.d("TestPoint", "Test point marked at: " + timestamp);
        if (!saveRecording || trajectory == null) {
            Log.w("SensorFusion", "Test point ignored: not recording");
            return;
        }

        long relativeTs = System.currentTimeMillis() - absoluteStartTime;

        Traj.GNSSPosition testPoint = Traj.GNSSPosition.newBuilder()
                .setRelativeTimestamp(relativeTs)
                .setLatitude(latitude)
                .setLongitude(longitude)
                .setAltitude(elevation) // or GNSS altitude if you prefer
                .build();

        trajectory.addTestPoints(testPoint);

        Log.d("SensorFusion", "Test point added @ " + latitude + ", " + longitude);
    }

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
        // Register RTT result listener to receive ranging data from WiFi RTT
        wifiProcessor.setRttResultListener((results, associatedScans) -> {
            if (saveRecording) {
                recordRttResults(results, associatedScans);
            }
        });

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
        this.particleFilter = new ParticleFilter();
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.ekfPositioning = new ExtendedKalmanFilter();
        this.useEKF = settings.getBoolean("use_ekf", false);
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
        wifiProcessor.startListening();

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

                // Integrate yaw rate to advance fusedHeading.
                // angularVelocity[2] is the device z-axis rotation rate (rad/s).
                if (headingInitialised && lastGyroTimestampMs > 0) {
                    long dtMs = currentTime - lastGyroTimestampMs;
                    if (dtMs > 0 && dtMs < 500) {
                        fusedHeading = normalizeAngle(fusedHeading - angularVelocity[2] * (dtMs / 1000f));
                    }
                }
                lastGyroTimestampMs = currentTime;
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

                // Complementary filter: slow rotation-vector correction on gyro-integrated heading.
                // Corrects long-term gyro drift while preserving short-term stability.
                if (!headingInitialised) {
                    fusedHeading = this.orientation[0];
                    headingInitialised = true;
                } else {
                    float diff = normalizeAngle(this.orientation[0] - fusedHeading);
                    fusedHeading = normalizeAngle(fusedHeading + 0.02f * diff);
                }
                break;

            case Sensor.TYPE_STEP_DETECTOR:
                long stepTime = SystemClock.uptimeMillis() - bootTime;

                if (currentTime - lastStepTime < 20) {
                    Log.e("SensorFusion", "Ignoring step event, too soon after last step event:" + (currentTime - lastStepTime) + " ms");
                    // Ignore rapid successive step events
                    break;
                } else {
                    lastStepTime = currentTime;

                    // Skip PDR update if the acceleration pattern indicates no real movement
                    if (isStationary(accelMagnitude)) {
                        accelMagnitude.clear();
                        break;
                    }

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
                            fusedHeading
                    );

                    // Feed PDR displacement to particle filter
                    if (particleFilter.isInitialized()) {
                        float dx = newCords[0] - prevPdrX;
                        float dy = newCords[1] - prevPdrY;

                        // Save particle positions BEFORE prediction
                        float[][] prevParticles = particleFilter.getParticlesCopy();

                        // Predict particle motion
                        particleFilter.predict(dx, dy);
                        ekfPositioning.predict(dx, dy);

                        // Drip-feed any pending large-jump correction into the EKF.
                        // One equal slice is applied per PDR step until the queue is exhausted.
                        if (pendingCorrectionStepsLeft > 0) {
                            float stepX = pendingCorrectionX / pendingCorrectionStepsLeft;
                            float stepY = pendingCorrectionY / pendingCorrectionStepsLeft;
                            ekfPositioning.applyDirectOffset(stepX, stepY);
                            pendingCorrectionX -= stepX;
                            pendingCorrectionY -= stepY;
                            pendingCorrectionStepsLeft--;
                        }

                        float[] bestBefore = particleFilter.getBestEstimate();
                        Log.d("PFDebug", "Best BEFORE constraints: " +
                                bestBefore[0] + ", " + bestBefore[1]);

                        // Apply wall constraints after prediction
                        if (coordinateConverter != null && indoorMapManager != null
                                && settings.getBoolean("use_wall_constraints", true)) {
                            float[] currEast = particleFilter.getParticlesXRef();
                            float[] currNorth = particleFilter.getParticlesYRef();
                            float[] liveWeights = particleFilter.getWeightsRef();

                            float[] prevEast = new float[prevParticles.length];
                            float[] prevNorth = new float[prevParticles.length];

                            for (int i = 0; i < prevParticles.length; i++) {
                                prevEast[i] = prevParticles[i][0];
                                prevNorth[i] = prevParticles[i][1];
                            }

                            indoorMapManager.applyWallConstraints(
                                    prevEast,
                                    prevNorth,
                                    currEast,
                                    currNorth,
                                    liveWeights,
                                    coordinateConverter
                            );
                            particleFilter.normalizeWeights();

                            Log.d("SensorFusion", "Applied wall constraints to particle cloud");
                            float[] bestAfter = particleFilter.getBestEstimate();

                            Log.d("PFDebug", "Best BEFORE constraints: " + bestBefore[0] + ", " + bestBefore[1]);
                            Log.d("PFDebug", "Best AFTER constraints: " + bestAfter[0] + ", " + bestAfter[1]);
                        }

                        prevPdrX = newCords[0];
                        prevPdrY = newCords[1];

                        if (coordinateConverter != null) {
                            lastPdrLatLon = coordinateConverter.toLatLon(prevPdrX, prevPdrY);
                        }
                    }

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
            altitude = (float) location.getAltitude();
            float accuracy = (float) location.getAccuracy();
            float speed = (float) location.getSpeed();
            String provider = location.getProvider();
            // Initialize coordinate converter and particle filter on first GNSS position during recording
            if (coordinateConverter == null) {
                coordinateConverter = new CoordinateConverter(
                        location.getLatitude(), location.getLongitude());
                Log.i("SensorFusion", "CoordinateConverter initialised at lat="
                        + latitude + " lon=" + longitude);
            }
            if (saveRecording) {
                if (!particleFilter.isInitialized()) {
                    // First position: set East-North origin, spread particles around (0,0)
//                    coordinateConverter = new CoordinateConverter(
//                            location.getLatitude(), location.getLongitude());

                    particleFilter.initParticles(0f, 0f, Math.max(accuracy, 5f));
                    ekfPositioning.initParticles(0f, 0f, Math.max(accuracy, 5f));
                    prevPdrX = 0f;
                    prevPdrY = 0f;
                    Log.i("SensorFusion", "ParticleFilter initialised at lat="
                            + latitude + " lon=" + longitude);
                    if (indoorMapManager != null) {
                        indoorMapManager.bakeEnuCoordinates(coordinateConverter);
                        enuBaked = true;
                    }
                } else {
                    // Subsequent positions: convert to East-North space and update particle weights.
                    float[] enu = coordinateConverter.toEnu(
                            location.getLatitude(), location.getLongitude());

                    // Jump detection: reject position if it is too far from the current estimate
                    float[] currentEst = particleFilter.getBestEstimate();
                    float jumpDist = (float) Math.hypot(
                            enu[0] - currentEst[0], enu[1] - currentEst[1]);
                    if (jumpDist > 80f) {
                        Log.w("SensorFusion", "GNSS jump " + jumpDist + "m — update rejected");
                    } else {
                        // Sigma-adaptive noise: tighten observation noise when particles diverge
                        float adaptedAccuracy = accuracy;
                        double sigma = particleFilter.getSigmaMetres();
                        if (sigma > 15.0) {
                            adaptedAccuracy = Math.max(accuracy * 0.5f, 3.0f);
                        }

                        // Derive heading from two consecutive valid GNSS fixes.
                        // Displacement must exceed 1.5x the reported accuracy to ensure the
                        // computed direction reflects real movement rather than GPS noise.
                        if (lastGnssEnu != null) {
                            float dEast  = enu[0] - lastGnssEnu[0];
                            float dNorth = enu[1] - lastGnssEnu[1];
                            float gnssDist = (float) Math.hypot(dEast, dNorth);
                            float minDist  = Math.max(accuracy * 1.5f, 5f);
                            if (gnssDist > minDist) {
                                fusedHeading = normalizeAngle(
                                        (float) Math.atan2(dEast, dNorth));
                                Log.d("SensorFusion", "GNSS heading: "
                                        + (float) Math.toDegrees(fusedHeading) + "°");
                            }
                        }
                        lastGnssEnu = enu;

                        // High-accuracy fix: re-centre particle cloud to prevent filter divergence
                        if (accuracy < 5f) {
                            particleFilter.resetAroundPosition(enu[0], enu[1], accuracy);
                            ekfPositioning.resetAroundPosition(enu[0], enu[1], accuracy);
                            Log.i("SensorFusion", "High-accuracy GNSS (" + accuracy
                                    + "m) — particle cloud recentred");
                        } else {
                            particleFilter.updateWithGnss(enu[0], enu[1], adaptedAccuracy);
                            ekfPositioning.updateWithGnss(enu[0], enu[1], adaptedAccuracy);
                        }
                        lastGnssLatLon = new double[]{location.getLatitude(), location.getLongitude()};
                    }

                    // Detect floor change and reset particle cloud if needed
                    int currentFloor = pdrProcessing.getCurrentFloor();
                    if (currentFloor != lastKnownFloor) {
                        float[] best = particleFilter.getBestEstimate();
                        particleFilter.resetAroundPosition(best[0], best[1], 8f);
                        ekfPositioning.resetAroundPosition(best[0], best[1], 8f);
                        lastKnownFloor = currentFloor;
                        Log.i("SensorFusion", "Floor change detected: " + lastKnownFloor
                                + " → " + currentFloor + ", particles reset");
                    }

                }
            }
            if(saveRecording) {
                trajectory.addGnssData(Traj.GNSSReading.newBuilder()
                        .setPosition(Traj.GNSSPosition.newBuilder()
                                .setLatitude(latitude)
                                .setLongitude(longitude)
                                .setAltitude(altitude)
                                .setRelativeTimestamp(System.currentTimeMillis()-absoluteStartTime)
                                .build())
                        .setAccuracy(accuracy)
                        .setSpeed(speed)
                        .setBearing(0)
                        .setProvider(provider)
                        .build());
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * Receives updates from {@link WifiDataProcessor}.
     * Receives updates from {@link BleDataProcessor}
     * @see WifiDataProcessor object for wifi scanning.
     * @see BleDataProcessor object for BLE scanning.
     */

    @Override
    public void update(Object[] dataArray) {
        if (dataArray == null || dataArray.length == 0) {
            return;
        }

        // Check the type of the first element to determine if it's WiFi or BLE
        if (dataArray[0] instanceof Wifi) {
            // Handle WiFi data
            updateWifiData(dataArray);
        } else if (dataArray[0] instanceof BleDataProcessor.BleDevice) {
            // Handle BLE data
            updateBleData(dataArray);
        }
    }

    // Original WiFi update logic
    private void updateWifiData(Object[] wifiList) {
        // Save newest wifi values to local variable
        this.wifiList = Stream.of(wifiList).map(o -> (Wifi) o).collect(Collectors.toList());

        if(this.saveRecording) {
            // build new wifi fingerprint
            Traj.Fingerprint.Builder wifiData = Traj.Fingerprint.newBuilder()
                    .setRelativeTimestamp(SystemClock.uptimeMillis()-bootTime);
            for (Wifi data : this.wifiList) {
                wifiData.addRfScans(Traj.RFScan.newBuilder()
                        .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                        .setMac(data.getBssid())
                        .setRssi(data.getLevel())
                        .build());
            }

            Traj.Fingerprint newFingerprint = wifiData.build();

            if (!isSameFingerprintAs(newFingerprint, lastWifiFingerprint)) {
                this.trajectory.addWifiFingerprints(newFingerprint);
                lastWifiFingerprint = newFingerprint;
                android.util.Log.i("SensorFusion", "New WiFi fingerprint added (" +
                        newFingerprint.getRfScansCount() + " APs)");
            } else {
                android.util.Log.d("SensorFusion", "Duplicate WiFi fingerprint skipped");
            }


        }

        createWifiPositionRequestCallback();
        Log.d("SensorFusion", "wifiList length = " +
                (wifiList == null ? 0 : wifiList.length));


    }

    /**
     * Records WiFi RTT ranging results and AP metadata into the trajectory protobuf.
     * Called by the RttResultListener when new ranging results arrive.
     *
     * WiFiAPData stores per-AP metadata including the rtt_enabled flag.
     * WiFiRTTReading stores the actual measured distance for each RTT-capable AP.
     *
     * @param results         List of RTT ranging results from WifiRttManager
     * @param associatedScans List of ScanResults corresponding to the ranging request
     */
    private void recordRttResults(List<RangingResult> results,
                                  List<ScanResult> associatedScans) {
        long relativeTimestamp = SystemClock.uptimeMillis() - bootTime;

        for (RangingResult result : results) {
            // Convert MAC address to long integer (same format as BSSID elsewhere)
            String macString = result.getMacAddress().toString();
            long macLong = bssidStringToLong(macString);

            // Record AP metadata with rtt_enabled = true (these are RTT-capable APs)
            Traj.WiFiAPData apData = Traj.WiFiAPData.newBuilder()
                    .setMac(macLong)
                    .setRttEnabled(true)
                    .build();
            this.trajectory.addApsData(apData);

            // Only record distance measurement if ranging succeeded
            if (result.getStatus() == RangingResult.STATUS_SUCCESS) {
                Traj.WiFiRTTReading rttReading = Traj.WiFiRTTReading.newBuilder()
                        .setRelativeTimestamp(relativeTimestamp)
                        .setMac(macLong)
                        .setDistance(result.getDistanceMm())         // distance in mm
                        .setDistanceStd(result.getDistanceStdDevMm()) // std deviation in mm
                        .setRssi(result.getRssi())
                        .build();
                this.trajectory.addWifiRttData(rttReading);
                Log.i("SensorFusion", "RTT result: mac=" + macString
                        + " dist=" + result.getDistanceMm() + "mm");
            } else {
                Log.d("SensorFusion", "RTT ranging failed for AP: " + macString
                        + " status=" + result.getStatus());
            }
        }
    }

    /**
     * Converts a MAC address string (e.g. "aa:bb:cc:dd:ee:ff") to a long integer.
     * Replicates the same conversion logic used for WiFi BSSID in WifiDataProcessor.
     *
     * @param mac   MAC address string with colon separators
     * @return      Long integer representation of the MAC address
     */
    private long bssidStringToLong(String mac) {
        try {
            return Long.parseLong(mac.replace(":", ""), 16);
        } catch (NumberFormatException e) {
            Log.w("SensorFusion", "Failed to convert MAC: " + mac);
            return 0L;
        }
    }

    // BLE update logic
    /**
     * Update BLE data with deduplication
     */
    private void updateBleData(Object[] bleArray) {
        BleDataProcessor.BleDevice[] bleDevices = new BleDataProcessor.BleDevice[bleArray.length];
        for (int i = 0; i < bleArray.length; i++) {
            bleDevices[i] = (BleDataProcessor.BleDevice) bleArray[i];
        }

        // Save BLE devices to local variable
        List<BleDataProcessor.BleDevice> newBleDeviceList = java.util.Arrays.asList(bleDevices);
        this.bleDeviceList = newBleDeviceList;

        if(this.saveRecording) {
            // Check for duplicate BLE device list
            if (!isSameBleDeviceList(newBleDeviceList, lastBleDeviceList)) {
                // Add each BLE device to trajectory
                for (BleDataProcessor.BleDevice device : bleDevices) {
                    Traj.BleData.Builder bleData = Traj.BleData.newBuilder()
                            .setMacAddress(device.macAddress)
                            .setName(device.name)
                            .setTxPowerLevel(device.txPowerLevel)
                            .setAdvertiseFlags(device.advertiseFlags);

                    // Add service UUIDs if available
                    if (device.serviceUuids != null && !device.serviceUuids.isEmpty()) {
                        bleData.addAllServiceUuids(device.serviceUuids);
                    }

                    // Add manufacturer data if available
                    if (device.manufacturerData != null) {
                        bleData.setManufacturerData(com.google.protobuf.ByteString.copyFrom(device.manufacturerData));
                    }

                    this.trajectory.addBleData(bleData.build());
                }

                lastBleDeviceList = newBleDeviceList;
                android.util.Log.i("SensorFusion", "New BLE device list added (" + bleDevices.length + " devices)");
            } else {
                android.util.Log.d("SensorFusion", "Duplicate BLE device list skipped");
            }
        }
    }


    /**
     * Check if two WiFi fingerprints are similar enough to be considered duplicates
     * Uses overlap ratio instead of exact match to handle unstable WiFi signals
     */
    /**
     * Check if two WiFi fingerprints are similar (for deduplication)
     * Uses both MAC address overlap and RSSI change to determine similarity
     *
     * @param newFingerprint New WiFi fingerprint
     * @param oldFingerprint Previous WiFi fingerprint
     * @return true if fingerprints are similar, false otherwise
     */
    private boolean isSameFingerprintAs(Traj.Fingerprint newFingerprint, Traj.Fingerprint oldFingerprint) {
        if (oldFingerprint == null) return false;
        if (newFingerprint.getRfScansCount() < 3 || oldFingerprint.getRfScansCount() < 3) return false;

        // Build maps of MAC -> RSSI for both fingerprints
        java.util.Map<Long, Integer> oldMacRssi = new java.util.HashMap<>();
        for (Traj.RFScan scan : oldFingerprint.getRfScansList()) {
            oldMacRssi.put(scan.getMac(), scan.getRssi());
        }

        java.util.Map<Long, Integer> newMacRssi = new java.util.HashMap<>();
        for (Traj.RFScan scan : newFingerprint.getRfScansList()) {
            newMacRssi.put(scan.getMac(), scan.getRssi());
        }

        // Count common MACs and check RSSI changes
        int commonCount = 0;
        int significantRssiChanges = 0;
        final int RSSI_THRESHOLD = 5; // dBm threshold for "significant" change

        for (Traj.RFScan newScan : newFingerprint.getRfScansList()) {
            Long mac = newScan.getMac();
            if (oldMacRssi.containsKey(mac)) {
                commonCount++;

                // Check if RSSI changed significantly
                int oldRssi = oldMacRssi.get(mac);
                int newRssi = newScan.getRssi();
                int rssiDiff = Math.abs(newRssi - oldRssi);

                if (rssiDiff >= RSSI_THRESHOLD) {
                    significantRssiChanges++;
                }
            }
        }

        int minCount = Math.min(newFingerprint.getRfScansCount(), oldFingerprint.getRfScansCount());
        float overlapRatio = (float) commonCount / minCount;
        float rssiChangeRatio = commonCount > 0 ? (float) significantRssiChanges / commonCount : 0;

        android.util.Log.d("SensorFusion", String.format(
                "WiFi comparison: overlap %.0f%% (%d/%d MACs), RSSI changes %.0f%% (%d/%d APs)",
                overlapRatio * 100, commonCount, minCount,
                rssiChangeRatio * 100, significantRssiChanges, commonCount
        ));

        // Consider duplicate if:
        // 1. High overlap (≥70%) AND
        // 2. Few RSSI changes (<30% of common APs changed significantly)
        return overlapRatio >= 0.7f && rssiChangeRatio < 0.3f;
    }

    /**
     * Check if two BLE device lists are similar (for deduplication)
     * Uses MAC address overlap ratio to determine similarity
     *
     * @param newList New BLE device list
     * @param oldList Previous BLE device list
     * @return true if lists are similar (overlap >= 80%), false otherwise
     */
    /**
     * Check if two BLE device lists are similar (for deduplication)
     * Uses dynamic threshold based on device count:
     * - Many devices (≥20): 50% overlap or 15+ common devices
     * - Medium devices (≥10): 60% overlap or 8+ common devices
     * - Few devices (<10): 70% overlap or 5+ common devices
     *
     * @param newList New BLE device list
     * @param oldList Previous BLE device list
     * @return true if lists are similar, false otherwise
     */
    private boolean isSameBleDeviceList(List<BleDataProcessor.BleDevice> newList,
                                        List<BleDataProcessor.BleDevice> oldList) {
        if (oldList == null || oldList.isEmpty()) {
            return false;
        }

        if (newList.isEmpty()) {
            return false;
        }

        // Create set of MAC addresses from old list
        java.util.Set<String> oldMacs = new java.util.HashSet<>();
        for (BleDataProcessor.BleDevice device : oldList) {
            oldMacs.add(device.macAddress);
        }

        // Count common MAC addresses
        int commonCount = 0;
        for (BleDataProcessor.BleDevice device : newList) {
            if (oldMacs.contains(device.macAddress)) {
                commonCount++;
            }
        }

        // Calculate overlap ratio
        int minCount = Math.min(newList.size(), oldList.size());
        float overlapRatio = (float) commonCount / minCount;

        // Determine if duplicate based on dynamic threshold
        boolean isDuplicate;
        String thresholdInfo;

        if (minCount >= 20) {
            // Many devices: use 50% threshold
            isDuplicate = overlapRatio >= 0.5f || commonCount >= 15;
            thresholdInfo = "threshold=50% or 15+ devices";
        } else if (minCount >= 10) {
            // Medium devices: use 60% threshold
            isDuplicate = overlapRatio >= 0.6f || commonCount >= 8;
            thresholdInfo = "threshold=60% or 8+ devices";
        } else {
            // Few devices: use 70% threshold
            isDuplicate = overlapRatio >= 0.7f || commonCount >= 5;
            thresholdInfo = "threshold=70% or 5+ devices";
        }

        android.util.Log.d("SensorFusion", String.format(
                "BLE comparison: overlap %.0f%% (%d/%d common MACs), common: %d, %s → %s",
                overlapRatio * 100, commonCount, minCount, commonCount,
                thresholdInfo, isDuplicate ? "DUPLICATE" : "NEW"
        ));

        return isDuplicate;
    }



    /**
     * Function to create a request to obtain a wifi location for the obtained wifi fingerprint
     *
     */
    private void PositioningRequest(){
        // Try catch block to catch any errors and prevent app crashing
        try {
            // Creating a JSON object to store the WiFi access points
            JSONObject wifiAccessPoints=new JSONObject();
            for (Wifi data : this.wifiList){
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }
            // Creating POST Request
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
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
            // Capture AP count, average RSSI and timestamp before the async callback closure.
            // These must be final locals because the lambda captures them from the enclosing scope.
            final int apCount = this.wifiList.size();
            final long currentTime = System.currentTimeMillis();
            float rssiSum = 0f;
            for (Wifi data : this.wifiList) rssiSum += data.getLevel();
            final float avgRssi = (apCount > 0) ? rssiSum / apCount : -75f;
            // Creating POST Request
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
            this.wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng wifiLocation, int floor) {
                    lastWifiLatLon = new double[]{wifiLocation.latitude, wifiLocation.longitude};
                    if (!saveRecording) return;

                    if (!particleFilter.isInitialized()) {
                        // GNSS not yet available — initialise from first WiFi fix
                        if (coordinateConverter == null) {
                            coordinateConverter = new CoordinateConverter(
                                    wifiLocation.latitude, wifiLocation.longitude);
                            if (indoorMapManager != null) {
                                indoorMapManager.bakeEnuCoordinates(coordinateConverter);
                                enuBaked = true;
                            }
                        }
                        particleFilter.initParticles(0f, 0f, 20f);
                        ekfPositioning.initParticles(0f, 0f, 20f);
                        prevPdrX = 0f;
                        prevPdrY = 0f;
                        Log.i("SensorFusion", "ParticleFilter initialised from WiFi at lat="
                                + wifiLocation.latitude + " lon=" + wifiLocation.longitude);
                    } else {
                        // Normal update
                        float[] enu = coordinateConverter.toEnu(
                                wifiLocation.latitude, wifiLocation.longitude);

                        // Cache WiFi ENU for stationary soft-update
                        lastWifiEnu = enu;

                        // Hard-reject implausibly large jumps (> 80 m).
                        float[] currentEst = particleFilter.getBestEstimate();
                        float jumpDist = (float) Math.hypot(
                                enu[0] - currentEst[0], enu[1] - currentEst[1]);
                        if (jumpDist > 80f) {
                            Log.w("SensorFusion", "WiFi hard-reject: jump=" + jumpDist + "m");
                        } else {
                            long stationaryMs = currentTime - lastStepTime;
                            boolean isStationary = stationaryMs > 1000;

                            // Pre-compute distance and elapsed time from the last accepted WiFi fix.
                            // These are used by both the velocity check and the consistency check below.
                            float wifiSelfDist = Float.MAX_VALUE;
                            long timeSinceLastWifi = Long.MAX_VALUE;
                            if (lastAcceptedWifiEnu != null && lastWifiUpdateTimeMs > 0) {
                                wifiSelfDist = (float) Math.hypot(
                                        enu[0] - lastAcceptedWifiEnu[0],
                                        enu[1] - lastAcceptedWifiEnu[1]);
                                timeSinceLastWifi = currentTime - lastWifiUpdateTimeMs;
                            }

                            // Velocity check: reject the fix if the implied travel speed between
                            // consecutive WiFi observations exceeds normal walking speed (3 m/s).
                            // Only applied when moving and when the time window is meaningful (< 15 s).
                            boolean velocityRejected = false;
                            if (!isStationary && wifiSelfDist < Float.MAX_VALUE
                                    && timeSinceLastWifi > 0 && timeSinceLastWifi < 15000) {
                                float impliedSpeed = wifiSelfDist / (timeSinceLastWifi / 1000f);
                                if (impliedSpeed > 3.0f) {
                                    Log.w("SensorFusion", "WiFi velocity-reject: "
                                            + impliedSpeed + "m/s selfDist=" + wifiSelfDist
                                            + "m dt=" + timeSinceLastWifi + "ms");
                                    velocityRejected = true;
                                }
                            }

                            if (!velocityRejected) {
                                // Base observation noise from AP count.
                                // More visible APs → tighter position estimate → lower noise.
                                float noiseStd = Math.max(8.0f, 20.0f - apCount * 1.5f);

                                // Scale noise based on average signal strength of the scan.
                                // Strong signal (> -60 dBm) → reliable ranging; weak (< -80 dBm) → coarser estimate.
                                if (avgRssi > -60f) {
                                    noiseStd *= 0.7f;
                                } else if (avgRssi < -80f) {
                                    noiseStd *= 1.5f;
                                }

                                // When moving, a large displacement between the WiFi fix and the current
                                // estimate is likely a fingerprinting error rather than true movement.
                                // Penalise proportionally: at 8 m → ×1, at 16 m → ×2, capped at ×4.
                                if (!isStationary && jumpDist > 8f) {
                                    float jumpPenalty = Math.min(jumpDist / 8f, 4f);
                                    noiseStd *= jumpPenalty;
                                    Log.d("SensorFusion", "WiFi jump penalty ×" + jumpPenalty
                                            + " dist=" + jumpDist + "m rssi=" + avgRssi);
                                }

                                // Consecutive-fix consistency check.
                                if (!isStationary && jumpDist > 8f && wifiSelfDist < Float.MAX_VALUE) {
                                    if (wifiSelfDist > 10f) {
                                        noiseStd *= 2.0f;
                                        Log.d("SensorFusion", "WiFi self-inconsistent: ×2 penalty"
                                                + " selfDist=" + wifiSelfDist + "m");
                                    } else if (wifiSelfDist < 5f) {
                                        noiseStd *= 0.6f;
                                        Log.d("SensorFusion", "WiFi self-consistent, EKF diverged:"
                                                + " trust boost ×0.6 selfDist=" + wifiSelfDist + "m");
                                    }
                                }

                                // PF trend gating: compare the WiFi jump direction against the PF's
                                // recent movement direction. If opposite and PF is confident, penalise.
                                if (!isStationary && jumpDist > 8f && lastPfPositionForTrend != null) {
                                    float pfTrendE = currentEst[0] - lastPfPositionForTrend[0];
                                    float pfTrendN = currentEst[1] - lastPfPositionForTrend[1];
                                    float pfTrendDist = (float) Math.hypot(pfTrendE, pfTrendN);
                                    if (pfTrendDist > 1.5f) {
                                        float wifiDeltaE = enu[0] - currentEst[0];
                                        float wifiDeltaN = enu[1] - currentEst[1];
                                        float dot = wifiDeltaE * pfTrendE + wifiDeltaN * pfTrendN;
                                        if (dot < 0) {
                                            double sigma = particleFilter.getSigmaMetres();
                                            if (sigma < 10.0) {
                                                noiseStd *= 3.0f;
                                                Log.d("SensorFusion", "WiFi trend conflict: ×3 penalty"
                                                        + " dot=" + dot + " pfSigma=" + sigma);
                                            }
                                        }
                                    }
                                }
                                lastPfPositionForTrend = currentEst.clone();

                                // When stationary, WiFi is the primary position source.
                                // Tighten noise and inflate EKF covariance so updates converge quickly.
                                if (stationaryMs > 3000) {
                                    noiseStd = 3.5f;
                                    ekfPositioning.inflateCovariance(100.0f);
                                } else if (isStationary) {
                                    noiseStd = 5.0f;
                                    ekfPositioning.inflateCovariance(25.0f);
                                } else {
                                    // While moving, reduce noise further only if the particle cloud
                                    // is already widely dispersed.
                                    double sigma = particleFilter.getSigmaMetres();
                                    if (sigma > 15.0) {
                                        noiseStd = Math.max(noiseStd * 0.5f, 5.0f);
                                    }
                                }

                                noiseStd = Math.max(noiseStd, 2.0f);
                                Log.d("SensorFusion", "WiFi update noiseStd=" + noiseStd
                                        + " apCount=" + apCount + " avgRssi=" + avgRssi
                                        + " jump=" + jumpDist + "m stationary=" + isStationary);
                                particleFilter.updateWithWifi(enu[0], enu[1], noiseStd);

                                if (!isStationary && jumpDist > 8f) {
                                    // Gradual EKF correction: apply the WiFi update internally to
                                    // get the target state, then undo the state jump while keeping
                                    // the covariance reduction. The correction is drip-fed over
                                    // CORRECTION_STEPS PDR steps via applyDirectOffset().
                                    float[] ekfBefore = ekfPositioning.getBestEstimate();
                                    ekfPositioning.updateWithWifi(enu[0], enu[1], noiseStd);
                                    float[] ekfAfter  = ekfPositioning.getBestEstimate();
                                    // Undo the state jump; P update is intentionally kept
                                    ekfPositioning.applyDirectOffset(
                                            ekfBefore[0] - ekfAfter[0],
                                            ekfBefore[1] - ekfAfter[1]);
                                    // Queue the full correction to be applied in equal slices
                                    pendingCorrectionX = ekfAfter[0] - ekfBefore[0];
                                    pendingCorrectionY = ekfAfter[1] - ekfBefore[1];
                                    pendingCorrectionStepsLeft = CORRECTION_STEPS;
                                    Log.d("SensorFusion", "EKF correction queued ("
                                            + pendingCorrectionX + ", " + pendingCorrectionY
                                            + ") over " + CORRECTION_STEPS + " steps");
                                } else {
                                    ekfPositioning.updateWithWifi(enu[0], enu[1], noiseStd);
                                }

                                // Record this fix as the last accepted one for future checks.
                                lastWifiUpdateTimeMs = currentTime;
                                lastAcceptedWifiEnu = enu.clone();
                            }
                        }
                    }
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
     * Returns the particle filter's best estimated position in WGS84 coordinates.
     * Used by the map display fragment to show the fused position marker.
     *
     * @return double[]{latitude, longitude}, or null(if filter is not initialized)
     */
    public double[] getFusedLatLon() {
        boolean ekfReady = useEKF && ekfPositioning != null && ekfPositioning.isInitialized();
        boolean pfReady  = !useEKF && particleFilter != null && particleFilter.isInitialized();
        if ((ekfReady || pfReady) && coordinateConverter != null) {
            float[] enu = useEKF ? ekfPositioning.getBestEstimate()
                    : particleFilter.getBestEstimate();
            return coordinateConverter.toLatLon(enu[0], enu[1]);
        }
        return null;
    }


    /** Last raw GNSS position. Returns null before first GPS signal. */
    public double[] getLastGnssLatLon() {
        return lastGnssLatLon;
    }

    /** Last raw WiFi position. Returns null before first WiFi scan result. */
    public double[] getLastWifiLatLon() {
        return lastWifiLatLon;
    }

    /** Last PDR-derived position as lat/lon. Returns null before first step is detected. */
    public double[] getLastPdrLatLon() {
        return lastPdrLatLon;
    }

    /**
     * Returns the spread of the particle cloud in metres.
     * Use this to draw an uncertainty circle around the fused position marker.
     *
     * @return RMS particle spread in metres.
     */
    public double getPositionUncertainty() {
        if (useEKF) {
            return ekfPositioning == null ? -1.0 : ekfPositioning.getSigmaMetres();
        }
        return particleFilter == null ? -1.0 : particleFilter.getSigmaMetres();
    }


    /**
     * Returns a copy of all particle positions in local East-North metres.
     *
     * Used for map matching wall-constraint filter.
     *
     * @return float[300][2] particle array, or float[0][2] if uninitialised.
     */
    public float[][] getParticles() {
        if (particleFilter == null) return new float[0][2];
        return particleFilter.getParticles();
    }

    /** Returns true if the EKF is selected as the active positioning algorithm. */
    public boolean isUsingEKF() {
        return useEKF;
    }








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

    public CoordinateConverter getCoordinateConverter() {
        return coordinateConverter;
    }

    public float[] getBestParticleEstimate() {
        if (particleFilter == null || !particleFilter.isInitialized()) {
            return new float[]{0f, 0f};
        }
        return particleFilter.getBestEstimate();
    }

    /**
     * Called when map matching confirms a floor change.
     * Resets the particle cloud around the current best estimate with increased uncertainty,
     * and updates the last known floor to stay in sync with barometer-based detection.
     *
     * @param newFloor integer floor number confirmed by map matching
     */
    public void onFloorChanged(int newFloor) {
        float[] best = particleFilter.getBestEstimate();
        particleFilter.resetAroundPosition(best[0], best[1], 8f);
        ekfPositioning.resetAroundPosition(best[0], best[1], 8f);
        lastKnownFloor = newFloor;
        Log.i("SensorFusion", "Floor change confirmed by map matching → floor " + newFloor);
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
                this.wifiProcessor.stopListening(); //error here?
            } catch (Exception e) {
                System.err.println("Wifi resumed before existing");
            }

            // Stop BLE scanning
            try {
                this.bleProcessor.stopListening();
            } catch (Exception e) {
                System.err.println("BLE stopped before existing");
            }

            // Stop receiving location updates
            this.gnssProcessor.stopUpdating();
        }
    }

    /**
     * Set trajectory name before recording starts
     */
    public void setTrajectoryName(String name) {
        this.trajectoryName = name;
        android.util.Log.i("SensorFusion", "Trajectory name set: " + name);
    }

    /**
     * Get trajectory name
     */
    public String getTrajectoryName() {
        return this.trajectoryName;
    }

    /**
     * Set initial position data before recording starts
     */
    public void setInitialPositionData(float lat, float lon) {
        this.initialLatitude = lat;
        this.initialLongitude = lon;
        this.initialAltitude = this.altitude;

        // Save initial orientation (rotation vector)
        this.initialRotation[0] = this.rotation[0];
        this.initialRotation[1] = this.rotation[1];
        this.initialRotation[2] = this.rotation[2];
        this.initialRotation[3] = this.rotation[3];

        android.util.Log.i("SensorFusion", String.format(
                "Initial position set: lat=%.6f, lon=%.6f, alt=%.2fm",
                initialLatitude, initialLongitude, initialAltitude));
        android.util.Log.i("SensorFusion", String.format(
                "Initial orientation set: quat[%.3f, %.3f, %.3f, %.3f]",
                initialRotation[0], initialRotation[1], initialRotation[2], initialRotation[3]));
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

        this.lastWifiFingerprint = null;
        this.lastBleDeviceList = null;
        this.saveRecording = true;
        this.stepCounter = 0;
        this.absoluteStartTime = System.currentTimeMillis();
        this.bootTime = SystemClock.uptimeMillis();
        // Protobuf trajectory class for sending sensor data to restful API
        this.trajectory = Traj.Trajectory.newBuilder()
                .setAndroidVersion(Build.VERSION.RELEASE)
                .setStartTimestamp(absoluteStartTime)
                .setTrajectoryId(trajectoryName)
                .setInitialPosition(Traj.GNSSPosition.newBuilder()    // ← 添加这4行！
                        .setLatitude(initialLatitude)
                        .setLongitude(initialLongitude)
                        .setAltitude(initialAltitude)
                        .setRelativeTimestamp(0)
                        .build())
                .setAccelerometerInfo(createInfoBuilder(accelerometerSensor))
                .setGyroscopeInfo(createInfoBuilder(gyroscopeSensor))
                .setRotationVectorInfo(createInfoBuilder(rotationSensor))
                .setMagnetometerInfo(createInfoBuilder(magnetometerSensor))
                .setBarometerInfo(createInfoBuilder(barometerSensor))
                .setLightSensorInfo(createInfoBuilder(lightSensor))
                .setProximityInfo(createInfoBuilder(proximitySensor));

        // Add the initial orientation as the first IMU reading
        this.trajectory.addImuData(Traj.IMUReading.newBuilder()
                .setRelativeTimestamp(0)  // 时间戳为0（起始点）
                .setAcc(Traj.Vector3.newBuilder()
                        .setX(0).setY(0).setZ(0)  // 初始加速度设为0
                        .build())
                .setGyr(Traj.Vector3.newBuilder()
                        .setX(0).setY(0).setZ(0)  // 初始陀螺仪设为0
                        .build())
                .setRotationVector(Traj.Quaternion.newBuilder()
                        .setX(initialRotation[0])
                        .setY(initialRotation[1])
                        .setZ(initialRotation[2])
                        .setW(initialRotation[3])
                        .build())
                .setStepCount(0)
                .build());

        this.storeTrajectoryTimer = new Timer();
        this.storeTrajectoryTimer.schedule(new storeDataInTrajectory(), 0, TIME_CONST);
        this.pdrProcessing.resetPDR();
        // Reset particle filter state for new recording session
        particleFilter = new ParticleFilter();
        ekfPositioning = new ExtendedKalmanFilter();
        useEKF = settings.getBoolean("use_ekf", false);
        coordinateConverter = null;  // force fresh origin at actual recording position
        enuBaked = false;
        lastGnssLatLon = null;
        lastWifiLatLon = null;
        lastPdrLatLon  = null;
        lastKnownFloor = 0;
        prevPdrX = 0f;
        prevPdrY = 0f;
        fusedHeading = 0f;
        headingInitialised = false;
        lastGyroTimestampMs = 0;
        lastGnssEnu = null;
        lastWifiEnu = null;
        lastPfPositionForTrend = null;
        lastWifiUpdateTimeMs = 0;
        lastAcceptedWifiEnu = null;
        pendingCorrectionX = 0f;
        pendingCorrectionY = 0f;
        pendingCorrectionStepsLeft = 0;
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
        }
        if(wakeLock.isHeld()) {
            this.wakeLock.release();
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
        // Pass object to communications object
        this.serverCommunications.sendTrajectory(sentTrajectory);
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
            trajectory.addImuData(Traj.IMUReading.newBuilder()
                    .setRelativeTimestamp(SystemClock.uptimeMillis()-bootTime)
                    .setAcc(Traj.Vector3.newBuilder()
                            .setX(acceleration[0])
                            .setY(acceleration[1])
                            .setZ(acceleration[2])
                            .build())
                    .setGyr(Traj.Vector3.newBuilder()
                            .setX(angularVelocity[0])
                            .setY(angularVelocity[1])
                            .setZ(angularVelocity[2])
                            .build())
                    .setRotationVector(Traj.Quaternion.newBuilder()
                            .setX(rotation[0])
                            .setY(rotation[1])
                            .setZ(rotation[2])
                            .setW(rotation[3])
                            .build())
                    .setStepCount(stepCounter)
                    .build());

            trajectory.addMagnetometerData(Traj.MagnetometerReading.newBuilder()
                    .setMag(Traj.Vector3.newBuilder()
                            .setX(magneticField[0])
                            .setY(magneticField[1])
                            .setZ(magneticField[2])
                            .build())
                    .setRelativeTimestamp(SystemClock.uptimeMillis()-bootTime)
                    .build());

            // Divide timer with a counter for storing data every 1 second
            if (counter == 99) {
                counter = 0;
                // Store pressure and light data
                if (barometerSensor.sensor != null) {
                    trajectory.addPressureData(Traj.BarometerReading.newBuilder()
                                    .setPressure(pressure)
                                    .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime))
                            .addLightData(Traj.LightReading.newBuilder()
                                    .setLight(light)
                                    .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
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
                            .setFrequency(currentWifi.getFrequency()));
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

    public List<String> getLatestBssids() {
        if (wifiProcessor == null) {
            Log.w("SensorFusion", "getLatestBssids(): wifiProcessor is null");
            return new ArrayList<>();
        }
        List<String> macs = wifiProcessor.getLastObservedBssids();
        Log.d("SensorFusion", "getLatestBssids(): macs=" + macs.size());
        return macs;
    }

    /**
     * Converts a local ENU position to WGS84 lat/lon.
     * Returns null if coordinate converter is not yet initialised.
     *
     * @param east  metres east from the reference point
     * @param north metres north from the reference point
     * @return double[]{latitude, longitude} or null
     */
    public double[] enuToLatLon(float east, float north) {
        if (coordinateConverter == null) return null;
        return coordinateConverter.toLatLon(east, north);
    }

    //endregion

    /**
     * Returns true if the linear acceleration samples indicate the device is stationary.
     * Uses the peak-to-peak range of the sample window.
     *
     * @param samples linear acceleration magnitudes (m/s²) collected between two step events
     * @return true if peak-to-peak range is below the stationary threshold (0.5 m/s²)
     */
    private boolean isStationary(List<Double> samples) {
        if (samples.isEmpty()) return false;
        double max = Double.MIN_VALUE, min = Double.MAX_VALUE;
        for (double v : samples) {
            if (v > max) max = v;
            if (v < min) min = v;
        }
        return (max - min) < 0.5;
    }

    /**
     * Wraps an angle in radians to the range [-π, π].
     *
     * @param angle angle in radians
     * @return equivalent angle in [-π, π]
     */
    private float normalizeAngle(float angle) {
        while (angle >  Math.PI) angle -= 2 * (float) Math.PI;
        while (angle < -Math.PI) angle += 2 * (float) Math.PI;
        return angle;
    }

}
