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
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.utils.FusionManager;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.PdrProcessing;
import com.openpositioning.PositionMe.utils.SimplePositionFusion;
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
    // String for creating WiFi fingerprint JSON object
    private static final String WIFI_FINGERPRINT= "wf";

    // HEADING LAG FIX - Game Rotation Vector + Gyro-integrated heading
    /**
     * High-frequency complementary-filter heading (radians, NED convention).
     * Primary source: TYPE_GAME_ROTATION_VECTOR (accel+gyro only, no magnetometer).
     * Gyroscope integration propagates heading between GAME_ROTATION_VECTOR updates.
     * Complementary filter: heading = alpha*(heading + gyro*dt) + (1-alpha)*gameRotVecHeading
     * alpha drops to HEADING_GYRO_ALPHA_TURN on sharp turns for instant snap.
     */
    private float headingRad = 0f;

    /** Complementary-filter weight for gyro during straight walking (high = trust gyro). */
    private static final float HEADING_GYRO_ALPHA = 0.85f;

    /**
     * Complementary-filter weight during a detected sharp turn.
     * Lower value means game-rotation-vector dominates for instant heading snap.
     */
    private static final float HEADING_GYRO_ALPHA_TURN = 0.20f;

    /**
     * Angular-rate threshold (rad/s) above which we consider the user to be
     * turning sharply and switch to the fast-response alpha.
     */
    private static final float TURN_RATE_THRESHOLD_RAD_S = (float) Math.toRadians(30.0);

    /** Timestamp (ns from SensorEvent.timestamp) of the last gyroscope event. */
    private long lastGyroTimestampNs = 0;

    /** Most recent gravity-projected yaw rate (rad/s), updated in the gyroscope handler. */
    private float lastYawRate = 0f;

    /** Heading derived purely from the latest GAME_ROTATION_VECTOR quaternion (radians). */
    private float gameRotVecHeading = 0f;

    /** Whether we have received at least one GAME_ROTATION_VECTOR event. */
    private boolean gameRotVecReady = false;

    /** Adaptation gain for heading offset updates from TYPE_ROTATION_VECTOR.
     *  0.03 per 150ms interval ≈ 0.2×delta/s effective rate, matching the original
     *  per-event implementation (0.002 × delta at 100Hz). Faster than the previous
     *  0.01/300ms (0.033/s) so accumulated heading bias is corrected before it
     *  causes visible lateral drift at corridor entrances. */
    private static final float HEADING_OFFSET_ADAPT_GAIN = 0.03f;

    /** Reject large instantaneous magnetic jumps when adapting heading offset. */
    private static final float HEADING_OFFSET_MAX_UPDATE_RAD = (float) Math.toRadians(10.0);

    /** Only adapt heading offset when angular rate is small (quasi-static phone orientation). */
    private static final float HEADING_OFFSET_ADAPT_MAX_YAW_RATE_RAD_S = (float) Math.toRadians(15.0);

    /** Minimum interval between heading-offset updates to avoid rapid oscillation.
     *  150ms (was 300ms) to restore heading-offset correction bandwidth closer to
     *  the original per-event implementation while keeping the protective gates. */
    private static final long HEADING_OFFSET_ADAPT_MIN_INTERVAL_MS = 150L;

    /** Require a short settle window after turning before adapting heading offset. */
    private static final long HEADING_OFFSET_ADAPT_TURN_SETTLE_MS = 1500L;

    /** Initial offset calibration: require multiple consistent samples, not a single frame. */
    private static final int HEADING_OFFSET_INIT_REQUIRED_SAMPLES = 5;
    private static final float HEADING_OFFSET_INIT_MAX_SPREAD_RAD = (float) Math.toRadians(12.0);

    private long lastHeadingOffsetAdaptMs = 0L;
    private long lastTurnDetectedMs = 0L;

    // Circular mean accumulator for robust initial heading-offset calibration.
    private float headingOffsetInitSinSum = 0f;
    private float headingOffsetInitCosSum = 0f;
    private int headingOffsetInitSampleCount = 0;

    /** Latest GAME_ROTATION_VECTOR rotation matrix (device -> world). */
    private final float[] latestGameRotMatrix = new float[9];
    private boolean gameRotMatrixReady = false;

    /**
     * Calibration offset: headingRad = gameRotVecHeading + headingOffset.
     *
     * GAME_ROTATION_VECTOR uses an arbitrary yaw reference frame (gravity-only,
     * no magnetometer). TYPE_ROTATION_VECTOR references magnetic North.
     * This offset aligns the two frames so PDR steps use a North-referenced heading
     * while benefiting from the game-rotation-vector's immunity to local magnetic
     * disturbances.
     */
    private float headingOffset = 0f;

    /** True once the initial offset has been computed from the first valid TYPE_ROTATION_VECTOR reading. */
    private boolean headingOffsetCalibrated = false;

    /** True once at least one TYPE_ROTATION_VECTOR event has been received (orientation[0] = 0
     *  is a valid azimuth meaning "facing north", so we cannot use != 0f as a ready-check). */
    private boolean rotVecReady = false;

    /** Dedicated sensor handle for TYPE_GAME_ROTATION_VECTOR. */
    private MovementSensor gameRotationSensor;

    // Heading diagnostics (enable while tuning turn-response / trend consistency).
    private static final boolean HEADING_DEBUG_LOG_ENABLED = true;
    private static final long HEADING_DEBUG_LOG_INTERVAL_MS = 250;
    private long lastHeadingDebugLogMs = 0L;

    // NOTE: PCA heading estimation was removed – samples were collected but
    // estimatePcaHeading() was never called in the active code path.
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
    private float[] startLocation;
    // Wifi values
    private List<Wifi> wifiList;

    // Fields for updated proto support
    // Trajectory identification
    private String trajectoryId;
    private float trajectoryVersion = 2.0f;
    // Floor selected in VenueManager at recording start, saved into initialPosition.floor
    private String recordingVenueFloor = "";
    
    // Initial position and orientation
    private boolean initialPositionSet = false;
    private float[] initialLocation;
    private float initialOrientation = 0f;
    
    // Corrected positions list
    private List<float[]> correctedPositions;

    // Fused trajectory samples captured from the live map path for faithful replay.
    private List<ReplayTrackPoint> replayTrackPoints;

    private static class ReplayTrackPoint {
        final long relativeTimestamp;
        final double latitude;
        final double longitude;

        ReplayTrackPoint(long relativeTimestamp, double latitude, double longitude) {
            this.relativeTimestamp = relativeTimestamp;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
    
    // WiFi fingerprint data
    private List<Map<String, Object>> wifiFingerprints;
    
    // WiFi AP data (Access Point information with RTT flag)
    private List<Map<String, Object>> wifiAPData;
    
    // BLE data collection
    private List<Map<String, Object>> bleData;
    private List<Map<String, Object>> bleFingerprints;
    
    // WiFi RTT data
    private List<Map<String, Object>> wifiRttData;
    
    // Proximity sensor data
    private float currentProximity = 0f;
    
    // PDR data
    private List<float[]> pdrData;

    // Test Points Data - timestamped markers during recording
    private List<Map<String, Object>> testPoints;
    private int testPointCounter = 0;

    // FUSED POSITION - Combines PDR with WiFi for smoother tracking
    private float fusedLatitude = 0f;
    private float fusedLongitude = 0f;
    private float lastPdrX = 0f;
    private float lastPdrY = 0f;
    private long lastPositionUpdateTime = 0;
    // PDR holdoff: ignore step events for this many ms after fusion is (re-)initialized.
    // 400 ms is enough to skip accidental button-tap steps while still capturing the
    // user's first real walking step (typical step period ≈ 500 ms). The previous 1500 ms
    // discarded the first 2-3 real steps, creating a permanent position offset.
    private long pdrIgnoreUntilMs = 0L;
    private static final long PDR_INIT_HOLDOFF_MS = 400L;

    // Dual-phase floor detection: WiFi anchors absolute floor, barometer tracks relative delta.
    // Integer.MIN_VALUE = anchor not yet set (pre-first-WiFi-fix state).
    private int wifiFloorAnchor = Integer.MIN_VALUE;
    private float wifiAnchorElevation = 0f;
    // Last floor that passed the zone gate — the only value ever returned to the UI.
    private int lastConfirmedFloor = 0;

    // Floor transition zone constraints: floor switches only when near a known lift or staircase.
    // Populated from the API floor-plan POI list in RecordingFragment.drawFloor().
    private List<LatLng> liftZones  = new ArrayList<>();
    private List<LatLng> stairZones = new ArrayList<>();
    private static final double LIFT_ZONE_RADIUS_M  = 15.0;
    private static final double STAIR_ZONE_RADIUS_M = 12.0;

    /** Describes how the user is changing floors (display/logging only, never used by PDR). */
    public enum VerticalTransportMode { NONE, STAIRS, ELEVATOR }
    private boolean hasFusedPosition = false;
    private static final float WIFI_PDR_FUSION_WEIGHT = 0.3f; // Weight for WiFi position in fusion
    private static final long POSITION_INTERPOLATION_INTERVAL = 50; // ms
    
    // Smooth position interpolation
    private float targetPdrX = 0f;
    private float targetPdrY = 0f;
    private float smoothPdrX = 0f;
    private float smoothPdrY = 0f;
    private static final float SMOOTHING_FACTOR = 0.15f; // Exponential smoothing factor

    // Inter-step interpolation: extrapolate position between step events
    // so the marker moves continuously instead of jumping every ~500ms.
    private long lastStepSystemTimeMs = 0;       // System.currentTimeMillis() of last step
    private long estimatedStepPeriodMs = 500;     // Estimated time between steps (ms)
    private boolean isWalking = false;            // Whether user appears to be walking
    private static final long WALKING_TIMEOUT_MS = 1200; // Stop interpolating after this idle time
    private LatLng lastFusionStepPosition = null; // Fusion position at last step event

    // Over time accelerometer magnitude values since last step
    private List<Double> accelMagnitude;

    // PDR calculation class
    private PdrProcessing pdrProcessing;
    
    // GNSS-PDR Fusion for continuous position correction
    private SimplePositionFusion positionFusion;

    // Particle Filter + Map Matching fusion pipeline
    private FusionManager fusionManager;
    // Timestamp of last PDR step (for speed estimation)
    private long lastPdrStepTime = 0;
    // Last PDR step length estimate (metres)
    private float lastStepLengthM = 0.65f;

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
        // Keep legacy ROTATION_VECTOR for backward-compat data recording; heading is now
        // driven by GAME_ROTATION_VECTOR which is immune to indoor magnetic disturbances.
        this.rotationSensor = new MovementSensor(context, Sensor.TYPE_ROTATION_VECTOR);
        // HEADING LAG FIX: register GAME_ROTATION_VECTOR at the fastest rate available.
        this.gameRotationSensor = new MovementSensor(context, Sensor.TYPE_GAME_ROTATION_VECTOR);
        this.gravitySensor = new MovementSensor(context, Sensor.TYPE_GRAVITY);
        this.linearAccelerationSensor = new MovementSensor(context, Sensor.TYPE_LINEAR_ACCELERATION);
        // Listener based devices
        this.wifiProcessor = new WifiDataProcessor(context);
        wifiProcessor.registerObserver(this);
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
        this.positionFusion = new SimplePositionFusion();  // Simple position fusion
        this.fusionManager  = new FusionManager();
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.pathView = new PathView(context, null);
        this.wiFiPositioning = new WiFiPositioning(context);
        
        // Initialize proto2.0 fields
        this.initialLocation = new float[2];
        this.correctedPositions = new ArrayList<>();
        this.replayTrackPoints = new ArrayList<>();
        this.wifiFingerprints = new ArrayList<>();
        this.wifiAPData = new ArrayList<>();
        this.bleData = new ArrayList<>();
        this.bleFingerprints = new ArrayList<>();
        this.wifiRttData = new ArrayList<>();
        this.pdrData = new ArrayList<>();
        this.testPoints = new ArrayList<>();  // Initialize test points list

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

            if (timeGap > LARGE_GAP_THRESHOLD_MS) {
                Log.w("SensorFusion", "Large time gap detected for sensor " + sensorType
                        + " | gap=" + timeGap + " ms");
            }
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
                    float altitudeM = SensorManager.getAltitude(
                            SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure);
                    this.elevation = pdrProcessing.updateElevation(altitudeM);
                    // Feed barometer into FusionManager for floor inference
                    if (fusionManager != null) {
                        fusionManager.updateBarometer(altitudeM);
                    }
                }
                break;

            case Sensor.TYPE_GYROSCOPE:
                angularVelocity[0] = sensorEvent.values[0];
                angularVelocity[1] = sensorEvent.values[1];
                angularVelocity[2] = sensorEvent.values[2];

                // Complementary filter: use gyro for short-term responsiveness and
                // GAME_ROTATION_VECTOR (tilt-compensated) as the long-term reference.
                boolean isTurningNow = false;
                if (lastGyroTimestampNs > 0 && gameRotVecReady) {
                    double dtSec = (sensorEvent.timestamp - lastGyroTimestampNs) * 1e-9;
                    if (dtSec > 0 && dtSec < 0.1) {
                        // Project gyro onto world yaw axis (gravity direction in phone frame).
                        // angularVelocity[2] (screen-normal / z-axis) is NOT the yaw axis when
                        // the phone is held upright in portrait – it captures roll from arm swing
                        // and creates a systematic westward bias. The correct world yaw rate is
                        // dot(omega, gravity_unit): this works for any phone tilt angle.
                        float gravMag = (float) Math.sqrt(
                                gravity[0]*gravity[0] + gravity[1]*gravity[1] + gravity[2]*gravity[2]);
                        float yawRate = (gravMag > 1.0f)
                                ? (angularVelocity[0]*gravity[0]
                                 + angularVelocity[1]*gravity[1]
                                 + angularVelocity[2]*gravity[2]) / gravMag
                                : angularVelocity[2]; // fallback if gravity not yet valid
                        lastYawRate = yawRate;
                        boolean isTurning = Math.abs(yawRate) > TURN_RATE_THRESHOLD_RAD_S;
                        if (isTurning) {
                            lastTurnDetectedMs = System.currentTimeMillis();
                        }
                        isTurningNow = isTurning;
                        float alpha = isTurning ? HEADING_GYRO_ALPHA_TURN : HEADING_GYRO_ALPHA;

                        float refHeading;
                        if (headingOffsetCalibrated) {
                            refHeading = wrapAngleFloat(gameRotVecHeading + headingOffset);
                        } else if (rotVecReady && orientation != null) {
                            refHeading = orientation[0];
                        } else {
                            refHeading = gameRotVecHeading;
                        }

                        float gyroHeading = wrapAngleFloat(headingRad + (float) (yawRate * dtSec));
                        // Use circular mean instead of linear interpolation to avoid the ±π
                        // wrapping boundary issue: alpha*(π-ε) + (1-alpha)*(-π+ε) ≈ -0.6π (wrong).
                        float sinMean = alpha * (float) Math.sin(gyroHeading) + (1.0f - alpha) * (float) Math.sin(refHeading);
                        float cosMean = alpha * (float) Math.cos(gyroHeading) + (1.0f - alpha) * (float) Math.cos(refHeading);
                        headingRad = (float) Math.atan2(sinMean, cosMean);
                    }
                }

                lastGyroTimestampNs = sensorEvent.timestamp;

                // Update EKF with gyroscope for heading integration
                if (positionFusion != null) {
                    positionFusion.updateWithGyroscope(angularVelocity);
                }

                maybeLogHeadingDebug("gyro", angularVelocity[2], isTurningNow);
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

                // Update EKF with accelerometer for ZUPT and real-time motion
                if (positionFusion != null && this.orientation != null) {
                    positionFusion.updateWithAccelerometer(filteredAcc, orientation[0]);
                }

                elevator = pdrProcessing.estimateElevator(gravity, filteredAcc);
                break;

            case Sensor.TYPE_GRAVITY:
                gravity[0] = sensorEvent.values[0];
                gravity[1] = sensorEvent.values[1];
                gravity[2] = sensorEvent.values[2];

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

            case Sensor.TYPE_GAME_ROTATION_VECTOR: {
                // Primary heading source: GAME_ROTATION_VECTOR (gyro+accel, no magnetometer).
                // This avoids indoor magnetic disturbances that bias azimuth left/right.
                // The north-reference is restored by headingOffset adapted in
                // TYPE_ROTATION_VECTOR below.
                float[] gameRotMatrix = new float[9];
                float[] gameOrientation = new float[3];
                SensorManager.getRotationMatrixFromVector(gameRotMatrix, sensorEvent.values);
                float[] remappedGameRotMatrix = new float[9];
                remapToDisplay(gameRotMatrix, remappedGameRotMatrix);
                System.arraycopy(remappedGameRotMatrix, 0, latestGameRotMatrix, 0, latestGameRotMatrix.length);
                gameRotMatrixReady = true;
                SensorManager.getOrientation(remappedGameRotMatrix, gameOrientation);
                gameRotVecHeading = gameOrientation[0];
                gameRotVecReady = true;

                float calibrated;
                if (headingOffsetCalibrated) {
                    calibrated = wrapAngleFloat(gameRotVecHeading + headingOffset);
                } else if (rotVecReady && orientation != null) {
                    // Before offset calibration is ready, prefer north-referenced ROTATION_VECTOR.
                    // Using raw GAME_ROTATION_VECTOR yaw at this stage can introduce an arbitrary
                    // frame offset (commonly close to 90 degrees on some devices).
                    calibrated = orientation[0];
                } else {
                    calibrated = gameRotVecHeading;
                }
                headingRad = wrapAngleFloat(calibrated);
                break;
            }

            case Sensor.TYPE_ROTATION_VECTOR:
                // Keep TYPE_ROTATION_VECTOR for north-reference alignment and trajectory logging.
                // Do not use it as the real-time heading source indoors because magnetic
                // disturbances can rotate azimuth significantly.
                this.rotation = sensorEvent.values.clone();
                float[] rotationVectorDCM = new float[9];
                SensorManager.getRotationMatrixFromVector(rotationVectorDCM, this.rotation);
                float[] remappedRotVecMatrix = new float[9];
                remapToDisplay(rotationVectorDCM, remappedRotVecMatrix);
                SensorManager.getOrientation(remappedRotVecMatrix, this.orientation);
                rotVecReady = true;   // mark as received; orientation[0]=0 (north) is valid

                if (gameRotVecReady) {
                    float desiredOffset = wrapAngleFloat(orientation[0] - gameRotVecHeading);
                    long nowMs = System.currentTimeMillis();
                    // Use gravity-projected yaw rate (same axis as isTurning detection),
                    // not angularVelocity[2] (screen-normal/roll) which is the wrong axis
                    // when the phone is held upright in portrait mode.
                    boolean lowYawRate = Math.abs(lastYawRate) <= HEADING_OFFSET_ADAPT_MAX_YAW_RATE_RAD_S;
                    boolean turnSettled = (nowMs - lastTurnDetectedMs) >= HEADING_OFFSET_ADAPT_TURN_SETTLE_MS;

                    if (!headingOffsetCalibrated) {
                        if (lowYawRate && turnSettled) {
                            float meanOffset = (headingOffsetInitSampleCount > 0)
                                    ? (float) Math.atan2(headingOffsetInitSinSum, headingOffsetInitCosSum)
                                    : desiredOffset;
                            float spread = Math.abs(wrapAngleFloat(desiredOffset - meanOffset));

                            // Re-start the init window if the new sample is not consistent.
                            if (headingOffsetInitSampleCount > 0
                                    && spread > HEADING_OFFSET_INIT_MAX_SPREAD_RAD) {
                                headingOffsetInitSinSum = 0f;
                                headingOffsetInitCosSum = 0f;
                                headingOffsetInitSampleCount = 0;
                            }

                            headingOffsetInitSinSum += (float) Math.sin(desiredOffset);
                            headingOffsetInitCosSum += (float) Math.cos(desiredOffset);
                            headingOffsetInitSampleCount++;

                            if (headingOffsetInitSampleCount >= HEADING_OFFSET_INIT_REQUIRED_SAMPLES) {
                                headingOffset = (float) Math.atan2(headingOffsetInitSinSum, headingOffsetInitCosSum);
                                headingOffsetCalibrated = true;
                                lastHeadingOffsetAdaptMs = nowMs;
                                headingOffsetInitSinSum = 0f;
                                headingOffsetInitCosSum = 0f;
                                headingOffsetInitSampleCount = 0;
                            }
                        }
                    } else {
                        float deltaOffset = wrapAngleFloat(desiredOffset - headingOffset);
                        boolean intervalOk = (nowMs - lastHeadingOffsetAdaptMs) >= HEADING_OFFSET_ADAPT_MIN_INTERVAL_MS;
                        if (lowYawRate && turnSettled && intervalOk
                                && Math.abs(deltaOffset) <= HEADING_OFFSET_MAX_UPDATE_RAD) {
                            headingOffset = wrapAngleFloat(headingOffset + HEADING_OFFSET_ADAPT_GAIN * deltaOffset);
                            lastHeadingOffsetAdaptMs = nowMs;
                        }
                    }
                    headingRad = wrapAngleFloat(gameRotVecHeading + headingOffset);
                } else {
                    // Fallback before GAME_ROTATION_VECTOR is ready.
                    headingRad = orientation[0];
                }

                if (positionFusion != null) {
                    positionFusion.updateWithMagnetometer(headingRad);
                }

                maybeLogHeadingDebug("rotvec", angularVelocity[2],
                        Math.abs(angularVelocity[2]) > TURN_RATE_THRESHOLD_RAD_S);
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
                    }

                        // Use the unified heading accessor so PDR/fusion paths always consume
                        // the same calibrated heading source.
                        float pdrHeading = getHeadingRad();
                    float[] newCords = this.pdrProcessing.updatePdr(
                            stepTime,
                            this.accelMagnitude,
                            pdrHeading
                    );

                    // Clear the accelMagnitude after using it
                    this.accelMagnitude.clear();

                    // Update position fusion with PDR data for GNSS correction
                    if (positionFusion != null) {
                        positionFusion.updateWithPDR(newCords[0], newCords[1]);
                    }

                    // Update Particle Filter / EKF with PDR step.
                    // Skip during the post-init holdoff so that phone-handling steps and
                    // "walk-into-position" steps don't immediately displace the start marker.
                    if (fusionManager != null && fusionManager.isInitialized()
                            && System.currentTimeMillis() > pdrIgnoreUntilMs) {
                        long dtMs = (lastPdrStepTime > 0) ? (currentTime - lastPdrStepTime) : 500;
                        // Estimate step length from PDR delta (avoids resetting the counter)
                        float dX = newCords[0] - lastPdrX;
                        float dY = newCords[1] - lastPdrY;
                        lastStepLengthM = (float) Math.sqrt(dX * dX + dY * dY);
                        if (lastStepLengthM <= 0.05f) lastStepLengthM = 0.65f; // fallback
                        double fusionHeading = pdrHeading;
                        fusionManager.updateWithPDR(
                                newCords[0], newCords[1],
                                fusionHeading,
                                lastStepLengthM,
                                dtMs);
                    }
                    lastPdrX = newCords[0];
                    lastPdrY = newCords[1];
                    lastPdrStepTime = currentTime;

                    // Update target position for smooth interpolation
                    targetPdrX = newCords[0];
                    targetPdrY = newCords[1];
                    lastPositionUpdateTime = currentTime;
                    hasFusedPosition = true;

                    // Inter-step interpolation: record step timing & snapshot fusion position
                    long prevStepTime = lastStepSystemTimeMs;
                    lastStepSystemTimeMs = currentTime;
                    if (prevStepTime > 0) {
                        long period = currentTime - prevStepTime;
                        if (period > 100 && period < 2000) {
                            // Smooth the step period estimate (EMA)
                            estimatedStepPeriodMs = (long)(0.3 * period + 0.7 * estimatedStepPeriodMs);
                        }
                    }
                    isWalking = true;
                    // Snapshot the fusion position at this step for interpolation base
                    if (fusionManager != null && fusionManager.isInitialized()) {
                        lastFusionStepPosition = fusionManager.getEstimatedPosition();
                    }

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
        // debug utility – log output removed for production build
    }

    /**
     * HEADING LAG FIX helper: wrap an angle in radians to the range [-π, π].
     *
     * <p>Used by the complementary-filter heading update to prevent the heading
     * from accumulating unbounded values after many gyro integration steps.</p>
     *
     * @param angle Angle in radians (any value).
     * @return Equivalent angle in [-π, π].
     */
    private float wrapAngleFloat(float angle) {
        while (angle >  Math.PI) angle -= (float)(2.0 * Math.PI);
        while (angle < -Math.PI) angle += (float)(2.0 * Math.PI);
        return angle;
    }

    private double wrapAngle(double angle) {
        while (angle > Math.PI) angle -= 2.0 * Math.PI;
        while (angle < -Math.PI) angle += 2.0 * Math.PI;
        return angle;
    }

    private double radToDeg(double rad) {
        return Math.toDegrees(wrapAngle(rad));
    }

    private void maybeLogHeadingDebug(String src, float yawRateRadS, boolean isTurning) {
        if (!HEADING_DEBUG_LOG_ENABLED) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastHeadingDebugLogMs < HEADING_DEBUG_LOG_INTERVAL_MS) {
            return;
        }
        lastHeadingDebugLogMs = now;

        double fusedDeg = radToDeg(headingRad);
        double gameDeg = gameRotVecReady ? radToDeg(gameRotVecHeading) : Double.NaN;
        double rotDeg = (rotVecReady && orientation != null) ? radToDeg(orientation[0]) : Double.NaN;
        double offsetDeg = headingOffsetCalibrated ? radToDeg(headingOffset) : Double.NaN;
        int displayRotation = getDisplayRotation();

        // heading debug log removed for production build
    }

    private int getDisplayRotation() {
        if (appContext == null) {
            return Surface.ROTATION_0;
        }
        WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null || wm.getDefaultDisplay() == null) {
            return Surface.ROTATION_0;
        }
        return wm.getDefaultDisplay().getRotation();
    }

    private void remapToDisplay(float[] inR, float[] outR) {
        int rotation = getDisplayRotation();
        int axisX;
        int axisY;
        switch (rotation) {
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
        SensorManager.remapCoordinateSystem(inR, axisX, axisY, outR);
    }

    /**
     * Return the calibrated heading in radians (NED convention, 0 = magnetic North).
     *
     * <p>Derived from {@code TYPE_GAME_ROTATION_VECTOR} (accel+gyro, no magnetometer)
     * plus a calibration offset that aligns the arbitrary game-rotation frame to
     * magnetic North.  The offset is slowly updated from {@code TYPE_ROTATION_VECTOR}
     * so long-term yaw drift is corrected without reacting to brief indoor magnetic
     * anomalies (e.g. escalators, iron doors).</p>
     *
     * @return Heading in radians in [-π, π], or {@code orientation[0]} as fallback
     *         if the game-rotation-vector has not yet fired.
     */
    public float getHeadingRad() {
        return gameRotVecReady ? headingRad : (orientation != null ? orientation[0] : 0f);
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
            
            // Update position fusion with GNSS data for continuous correction
            if (positionFusion != null) {
                positionFusion.updateWithGNSS(latitude, longitude, accuracy);
            }

            // Update Particle Filter with GNSS fix
            if (fusionManager != null) {
                if (!fusionManager.isInitialized()) {
                    // First GNSS fix – initialise the fusion pipeline.
                    double initHeading = getHeadingRad();
                    fusionManager.initialize(latitude, longitude, accuracy, initHeading, 0);
                } else {
                    fusionManager.updateWithGNSS(latitude, longitude, accuracy);
                }
            }
            
            if(saveRecording) {
                trajectory.addGnssData(Traj.GNSSReading.newBuilder()
                        .setPosition(Traj.GNSSPosition.newBuilder()
                                .setRelativeTimestamp(System.currentTimeMillis()-absoluteStartTime)
                                .setLatitude(latitude)
                                .setLongitude(longitude)
                                .setAltitude(altitude))
                        .setAccuracy(accuracy)
                        .setSpeed(speed)
                        .setProvider(provider));
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
    public void update(Object[] wifiList) {
        // Save newest wifi values to local variable
        this.wifiList = Stream.of(wifiList).map(o -> (Wifi) o).collect(Collectors.toList());

        if(this.saveRecording) {
            Traj.Fingerprint.Builder wifiData = Traj.Fingerprint.newBuilder()
                    .setRelativeTimestamp(SystemClock.uptimeMillis()-bootTime);
            for (Wifi data : this.wifiList) {
                wifiData.addRfScans(Traj.RFScan.newBuilder()
                        .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                        .setMac(data.getBssid()).setRssi(data.getLevel()));
            }
            // Adding WiFi fingerprint data to Trajectory
            this.trajectory.addWifiFingerprints(wifiData);
        }
        // Use callback-based WiFi request so that positionFusion receives
        // FRESH data when the server responds, instead of stale data from the
        // previous scan (which is always behind the user and pulls backward).
        createWifiPositioningRequestWithFusion();

        // Feed raw WiFi scan into local WKNN predictor (no DL/API dependency for fusion path).
        if (fusionManager != null && fusionManager.isInitialized()) {
            Map<String, Integer> currentScan = new HashMap<>();
            for (Wifi data : this.wifiList) {
                currentScan.put(String.format("%012X", data.getBssid()), data.getLevel());
            }
            fusionManager.updateWithWifiScan(currentScan);
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

    /**
     * Callback-based WiFi positioning request.
     * Unlike createWifiPositioningRequest(), this feeds the FRESH server response
     * directly into positionFusion.updateWithWiFi() when it arrives, avoiding the
     * stale-data problem that caused backward pulling and apparent direction reversal.
     */
    private void createWifiPositioningRequestWithFusion(){
        try {
            JSONObject wifiAccessPoints = new JSONObject();
            for (Wifi data : this.wifiList){
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
            this.wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng wifiLocation, int floor) {
                    // Feed FRESH WiFi position into SimplePositionFusion (drift correction)
                    if (positionFusion != null && positionFusion.isInitialized()) {
                        positionFusion.updateWithWiFi(wifiLocation.latitude, wifiLocation.longitude);
                    }
                    // Feed into FusionManager for heading correction + EKF/PF update
                    if (fusionManager != null && fusionManager.isInitialized()) {
                        fusionManager.updateWithWifi(wifiLocation.latitude, wifiLocation.longitude);
                    }
                    // Dual-phase floor: WiFi provides absolute floor anchor.
                    // Barometer will measure relative displacement from this baseline.
                    wifiFloorAnchor = floor;
                    wifiAnchorElevation = elevation;
                }

                @Override
                public void onError(String message) {
                    // WiFi positioning failed – simply skip this correction.
                    Log.w("SensorFusion", "WiFi positioning error: " + message);
                }
            });
        } catch (JSONException e) {
            Log.e("jsonErrors","Error creating json object" + e.toString());
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
     * Requests WiFi-based positioning using the most recent WiFi scan, and delivers the result
     * via a callback. Intended only for refining the initial start-location marker before
     * recording begins. Does NOT touch positionFusion or fusionManager.
     *
     * @param callback called on the main thread when the server responds (or on error)
     * @return true if a request was sent, false if no WiFi scan data is available yet
     */
    public boolean requestWifiPositioningForInitialLocation(WiFiPositioning.VolleyCallback callback) {
        if (wifiList == null || wifiList.isEmpty()) {
            return false;
        }
        try {
            JSONObject wifiAccessPoints = new JSONObject();
            for (Wifi data : wifiList) {
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
            wiFiPositioning.request(wifiFingerPrint, callback);
            return true;
        } catch (JSONException e) {
            Log.e("SensorFusion", "Error creating WiFi JSON for initial location: " + e);
            return false;
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
     * Called when the user confirms their starting position in
     * {@link com.openpositioning.PositionMe.presentation.fragment.StartLocationFragment}.
     * This is the authoritative position: it ALWAYS overrides any earlier GNSS-based
     * initialisation, because indoor GPS is typically 10-30 m off.
     *
     * <p>After this call:
     * <ul>
     *   <li>Both {@code positionFusion} and {@code fusionManager} are (re-)initialised
     *       at the user-chosen coordinates.</li>
     *   <li>{@code fusionManager} enters a 60-second "user-anchor" window during which
     *       GNSS fixes more than 2.5 m away are rejected, preventing GPS from pulling
     *       the trajectory back to the (wrong) outdoor-biased position.</li>
     * </ul>
     *
     * @param startPosition [latitude, longitude] chosen by the user on the map.
     */
    public void setStartGNSSLatitude(float[] startPosition) {
        startLocation = startPosition;

        if (startPosition == null || (startPosition[0] == 0f && startPosition[1] == 0f)) {
            return;
        }

        // (Re-)seed SimplePositionFusion so PDR steps are immediately converted to
        // lat/lng from the correct starting point.
        if (positionFusion != null) {
            positionFusion.initialize(startPosition[0], startPosition[1], 5.0f);
        }

        // ALWAYS reinitialise FusionManager (particle filter + EKF) from the user-chosen
        // position, even if GNSS had already initialised it at the wrong GPS location.
        // Without this the EKF/PF would continue from wherever GPS placed them.
        double initHeading = gameRotVecReady ? this.headingRad
                : ((orientation != null) ? orientation[0] : 0.0);
        if (fusionManager != null) {
            fusionManager.initialize(startPosition[0], startPosition[1], 5.0f, initHeading, 0);
            // Enter anchor mode: tighten GNSS rejection for the first 60 s so that
            // the poor-quality indoor GPS cannot drag the position back to its
            // (biased) estimate.
            fusionManager.setUserAnchor();
        }

        // Re-reset PDR so any steps taken between startRecording() and here are cleared,
        // and sync SensorFusion.lastPdrX/Y with FusionManager's reset state (both → 0).
        this.pdrProcessing.resetPDR();
        this.lastPdrX = 0f;
        this.lastPdrY = 0f;

        // Arm the holdoff: PDR steps will be collected but won't move the fusion estimate
        // for PDR_INIT_HOLDOFF_MS, giving the user time to stand still at their start point.
        this.pdrIgnoreUntilMs = System.currentTimeMillis() + PDR_INIT_HOLDOFF_MS;
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
     * Get smoothed PDR position using exponential smoothing.
     * This provides smoother movement visualization between step updates.
     * 
     * @return float array of size 2, with smoothed X and Y coordinates respectively.
     */
    public float[] getSmoothedPDRPosition() {
        // Return the raw PDR target position directly.
        // The CascadedFusionManager (EKF + PF) already handles position smoothing;
        // an extra exponential-smoothing layer here only adds lag, making the
        // displayed position trail behind (or overshoot) the user's actual steps.
        return new float[]{targetPdrX, targetPdrY};
    }

    /**
     * Get raw PDR-only position in lat/lng (start point + PDR offsets).
     * This intentionally excludes WiFi/GNSS/PF fusion so UI overlays can show
     * whether step-based PDR itself is behaving correctly.
     */
    public LatLng getRawPdrLatLng() {
        float[] startPos = getGNSSLatitude(true);
        if (startPos == null || startPos[0] == 0f) {
            return null;
        }

        float[] pdrPos = (pdrProcessing != null) ? pdrProcessing.getPDRMovement() : null;
        if (pdrPos == null || pdrPos.length < 2) {
            return null;
        }

        double latOffset = pdrPos[1] / 111139.0;
        double lngOffset = pdrPos[0] / 62422.0;
        return new LatLng(startPos[0] + latOffset, startPos[1] + lngOffset);
    }

    /**
     * Get fused position combining PDR with GNSS correction.
     * Uses Kalman-like filter for continuous GNSS-PDR fusion.
     * Also incorporates WiFi positioning when available.
     * 
     * @return LatLng of the fused position, or null if no position available.
     */
    public LatLng getFusedPosition() {
        if (!hasFusedPosition) {
            return null;
        }

        // Prefer the particle-filter output when available to keep one consistent fusion stream.
        LatLng particleEstimate = getParticleFilterPosition();
        if (particleEstimate != null) {
            return particleEstimate;
        }

        // Try to use position fusion (GNSS-corrected) first - this is already smoothed
        if (positionFusion != null && positionFusion.isInitialized()) {
            // WiFi fusion is now handled inside SimplePositionFusion.updateWithWiFi()
            // which is called from update(Object[] wifiList) on every scan.
            // No ad-hoc WiFi blending is needed here.
            return new LatLng(positionFusion.getFusedLatitude(),
                             positionFusion.getFusedLongitude());
        }

        // Fallback to original method if position fusion not initialized
        // Get base position from start location
        float[] startPos = getGNSSLatitude(true);
        if (startPos == null || startPos[0] == 0) {
            return null;
        }

        // Get smoothed PDR position
        float[] smoothPos = getSmoothedPDRPosition();
        
        // Convert PDR movement to lat/lng offset
        // Approximate conversion: 1 degree latitude ≈ 111,139 meters
        // At ~56° latitude: 1 degree longitude ≈ 62,422 meters
        double latOffset = smoothPos[1] / 111139.0;  // Y movement affects latitude
        double lngOffset = smoothPos[0] / 62422.0;   // X movement affects longitude
        
        double pdrLat = startPos[0] + latOffset;
        double pdrLng = startPos[1] + lngOffset;

        // Check if we have recent WiFi position for fusion
        LatLng wifiPos = getLatLngWifiPositioning();
        if (wifiPos != null) {
            // Fuse WiFi and PDR positions using weighted average
            double fusedLat = (1 - WIFI_PDR_FUSION_WEIGHT) * pdrLat + WIFI_PDR_FUSION_WEIGHT * wifiPos.latitude;
            double fusedLng = (1 - WIFI_PDR_FUSION_WEIGHT) * pdrLng + WIFI_PDR_FUSION_WEIGHT * wifiPos.longitude;
            return new LatLng(fusedLat, fusedLng);
        }

        return new LatLng(pdrLat, pdrLng);
    }

    /**
     * Check if position tracking is active.
     * 
     * @return true if we have a valid fused position
     */
    public boolean hasValidPosition() {
        return hasFusedPosition;
    }

    /**
     * Reset smooth position tracking - call when starting new recording.
     */
    public void resetSmoothPosition() {
        smoothPdrX = 0f;
        smoothPdrY = 0f;
        targetPdrX = 0f;
        targetPdrY = 0f;
        lastPdrX = 0f;
        lastPdrY = 0f;
        hasFusedPosition = false;
        lastPositionUpdateTime = 0;
        lastPdrStepTime = 0;
        pdrIgnoreUntilMs = 0L;
        
        // Reset inter-step interpolation state
        lastStepSystemTimeMs = 0;
        estimatedStepPeriodMs = 500;
        isWalking = false;
        lastFusionStepPosition = null;

        // Reset dual-phase floor detection state for new recording
        wifiFloorAnchor = Integer.MIN_VALUE;
        wifiAnchorElevation = 0f;
        lastConfirmedFloor = 0;
        
        // Reset position fusion for new recording
        if (positionFusion != null) {
            positionFusion.reset();
        }
        // Reset particle filter pipeline for new recording
        if (fusionManager != null) {
            fusionManager.reset();
        }
    }

    // Particle Filter / FusionManager public API
    /**
     * Get the best-estimate position from the Particle Filter fusion pipeline.
     *
     * <p>This is the primary position output for the UI.  It fuses PDR, GNSS
     * and WiFi using a particle filter with map-matching constraints.</p>
     *
     * @return {@link LatLng} of the fused position, or {@code null} if not ready.
     */
    public LatLng getParticleFilterPosition() {
        if (fusionManager == null || !fusionManager.isInitialized()) {
            return null;
        }

        LatLng basePosition = fusionManager.getEstimatedPosition();
        if (basePosition == null) {
            return null;
        }

        // Inter-step interpolation
        // Between step events (~2 Hz) the fusion position is frozen.
        // Extrapolate forward using current heading & estimated walk speed
        // so the displayed marker moves continuously with the user.
        long now = System.currentTimeMillis();
        long elapsed = now - lastStepSystemTimeMs;

        // Stop interpolating if user hasn't stepped recently (standing still)
        if (!isWalking || lastStepSystemTimeMs == 0 || elapsed > WALKING_TIMEOUT_MS) {
            if (elapsed > WALKING_TIMEOUT_MS) {
                isWalking = false;
            }
            return basePosition;
        }

        // Only interpolate for the fraction of one step period after the last step.
        // Beyond one period the next step should arrive; cap to avoid overshoot.
        float fraction = Math.min(1.0f, (float) elapsed / estimatedStepPeriodMs);

        // Estimated distance walked since last step = fraction × lastStepLength
        float extrapolateM = fraction * lastStepLengthM;

        // Use current heading (updated at ~100 Hz by GAME_ROTATION_VECTOR)
        float heading = getHeadingRad();
        double dEast  = extrapolateM * Math.sin(heading);
        double dNorth = extrapolateM * Math.cos(heading);

        // Convert metre offsets to lat/lng
        double dLat = dNorth / 111139.0;
        double dLng = dEast / (111139.0 * Math.cos(Math.toRadians(basePosition.latitude)));

        return new LatLng(basePosition.latitude + dLat, basePosition.longitude + dLng);
    }

    /**
     * Get the current floor inferred by the map-matching / barometer pipeline.
     *
     * @return Floor number (0 = ground floor).
     */
    public int getInferredFloor() {
        // Step 1: compute the candidate floor from whichever source is available.
        int candidateFloor;
        if (wifiFloorAnchor != Integer.MIN_VALUE) {
            // Dual-phase: WiFi absolute anchor + barometer relative delta.
            float floorHeightM = (settings != null) ? settings.getInt("floor_height", 4) : 4f;
            float baroDelta = this.elevation - wifiAnchorElevation;
            candidateFloor = wifiFloorAnchor + Math.round(baroDelta / floorHeightM);
        } else if (Math.abs(this.elevation) <= 1.2f) {
            // Barometer near zero → candidate is ground floor; still apply zone gate below.
            candidateFloor = 0;
        } else if (fusionManager != null) {
            candidateFloor = fusionManager.getCurrentFloor();
        } else {
            return lastConfirmedFloor;
        }

        // Step 2: if candidate differs from last confirmed, apply zone gate.
        // This single gate covers ALL paths (WiFi-anchored and barometer-only),
        // fixing the two previous bypass holes.
        if (candidateFloor != lastConfirmedFloor) {
            boolean zonesLoaded = !liftZones.isEmpty() || !stairZones.isEmpty();
            if (zonesLoaded) {
                LatLng pos = (fusionManager != null && fusionManager.isInitialized())
                        ? fusionManager.getEstimatedPosition() : null;
                boolean nearTransition = pos != null
                        && (isNearZone(pos, liftZones, LIFT_ZONE_RADIUS_M)
                         || isNearZone(pos, stairZones, STAIR_ZONE_RADIUS_M));
                android.util.Log.w("SensorFusion", "[ZoneGate] FLOOR CHANGE ATTEMPT: "
                        + lastConfirmedFloor + " → " + candidateFloor
                        + " pos=" + pos
                        + " nearTransition=" + nearTransition
                        + " liftZones=" + liftZones.size() + " stairZones=" + stairZones.size());
                if (!nearTransition) {
                    android.util.Log.w("SensorFusion", "[ZoneGate] BLOCKED (not near any transition zone)");
                    return lastConfirmedFloor; // blocked: not near any lift or staircase
                }
                android.util.Log.w("SensorFusion", "[ZoneGate] ALLOWED (near transition zone)");
            } else {
                android.util.Log.w("SensorFusion", "[ZoneGate] BYPASSED (no zones loaded) " + lastConfirmedFloor + " → " + candidateFloor);
            }
            lastConfirmedFloor = candidateFloor;
        }
        return lastConfirmedFloor;
    }

    /** True once at least one WiFi floor fix has been received and the dual-phase anchor is set. */
    public boolean hasWifiFloorAnchor() {
        return wifiFloorAnchor != Integer.MIN_VALUE;
    }

    /**
     * Supply lift and staircase LatLng centres parsed from the API floor plan.
     * Called by RecordingFragment whenever a new floor is drawn.
     * Only used for floor-switch constraints; has no effect on PDR or position fusion.
     */
    public void setFloorTransitionZones(List<LatLng> lifts, List<LatLng> stairs) {
        // Only replace existing zones if the new list is non-empty.
        // This prevents a floor with no POI data from wiping out valid zones
        // and accidentally disabling the gate (zonesLoaded = false).
        if (lifts  != null && !lifts.isEmpty())  this.liftZones  = lifts;
        if (stairs != null && !stairs.isEmpty()) this.stairZones = stairs;
    }

    /**
     * Feature 2: determine whether the user is currently using a lift or a staircase.
     *
     * <p>Decision rule:
     * <ul>
     *   <li><b>ELEVATOR</b> – near a lift zone AND the accelerometer-based
     *       {@code estimateElevator()} model reports minimal horizontal motion.</li>
     *   <li><b>STAIRS</b>  – near a staircase zone AND the user is walking
     *       (step detector fired recently) AND barometer shows height change.</li>
     *   <li><b>NONE</b>    – neither condition is met.</li>
     * </ul>
     * This is purely for display / logging and never feeds back into position or PDR.</p>
     */
    public VerticalTransportMode getVerticalTransportMode() {
        LatLng pos = (fusionManager != null && fusionManager.isInitialized())
                ? fusionManager.getEstimatedPosition() : null;
        if (pos == null) return VerticalTransportMode.NONE;

        float baroDelta = this.elevation - wifiAnchorElevation;
        boolean heightChanging = Math.abs(baroDelta) > 0.8f; // > ~0.8 m movement detected

        if (isNearZone(pos, liftZones, LIFT_ZONE_RADIUS_M) && elevator) {
            return VerticalTransportMode.ELEVATOR;
        }
        if (isNearZone(pos, stairZones, STAIR_ZONE_RADIUS_M) && isWalking && heightChanging) {
            return VerticalTransportMode.STAIRS;
        }
        return VerticalTransportMode.NONE;
    }

    /** Equirectangular distance approximation (accurate to < 1 % for indoor ranges < 200 m). */
    private static double latLngDistanceM(LatLng a, LatLng b) {
        double dLat = (b.latitude  - a.latitude)  * 111320.0;
        double dLng = (b.longitude - a.longitude) * 111320.0
                * Math.cos(Math.toRadians(a.latitude));
        return Math.sqrt(dLat * dLat + dLng * dLng);
    }

    private boolean isNearZone(LatLng pos, List<LatLng> zones, double radiusM) {
        for (LatLng zone : zones) {
            if (latLngDistanceM(pos, zone) <= radiusM) return true;
        }
        return false;
    }

    /**
     * Get the source of the most recent position update (PDR / GNSS / WiFi / FUSED).
     *
     * @return {@link FusionManager.PositionSource} enum value.
     */
    public FusionManager.PositionSource getLastPositionSource() {
        if (fusionManager != null) {
            return fusionManager.getLastSource();
        }
        return FusionManager.PositionSource.PDR;
    }

    /**
     * Initialise the Particle Filter pipeline with a known starting position.
     *
     * <p>Call this when the user confirms their starting location (e.g. from
     * {@link com.openpositioning.PositionMe.presentation.fragment.StartLocationFragment}).</p>
     *
     * @param latDeg  Starting latitude (decimal degrees).
     * @param lngDeg  Starting longitude (decimal degrees).
     * @param floor   Starting floor number (0 = ground).
     */
    public void initFusionManager(double latDeg, double lngDeg, int floor) {
        if (fusionManager != null) {
            double headingRad = gameRotVecReady ? this.headingRad
                    : ((orientation != null) ? orientation[0] : 0.0);
            fusionManager.initialize(latDeg, lngDeg, 10.0f, headingRad, floor);
        }
    }

    /**
     * Load the Nucleus building map into the map matcher.
     *
     * @param floor Floor number.
     */
    public void loadNucleusMap(int floor) {
        if (fusionManager != null) {
            fusionManager.loadNucleusMap(floor);
        }
    }

    /**
     * Configure wall constraints from API floor-plan walls.
     *
     * @param wallPolylines Wall polylines in WGS84.
     * @param floor         Floor number for the supplied map.
     * @return Number of wall segments applied to the matcher.
     */
    public int configureIndoorWallConstraints(List<List<LatLng>> wallPolylines, int floor) {
        if (fusionManager == null) {
            return 0;
        }
        return fusionManager.configureDynamicWallMap(wallPolylines, floor);
    }

    /** @return The {@link FusionManager} instance (for advanced configuration). */
    public FusionManager getFusionManager() { return fusionManager; }
    
    /**
     * Get current position uncertainty estimate in meters.
     * Useful for displaying confidence level to user.
     * 
     * @return Position uncertainty in meters, or Float.MAX_VALUE if not initialized
     */
    public float getPositionUncertainty() {
        if (positionFusion != null && positionFusion.isInitialized()) {
            return positionFusion.getPositionUncertainty();
        }
        return Float.MAX_VALUE;
    }
    
    /**
     * Check if GNSS data is stale (not receiving recent updates).
     * Useful for showing indoor/outdoor indicator.
     * 
     * @return true if GNSS hasn't been updated recently
     */
    public boolean isGnssStale() {
        if (positionFusion != null) {
            return positionFusion.isGnssStale();
        }
        return true;
    }
    
    /**
     * Get time since last GNSS update in milliseconds.
     * 
     * @return Time since last GNSS update
     */
    public long getTimeSinceLastGnss() {
        if (positionFusion != null) {
            return positionFusion.getTimeSinceLastGnss();
        }
        return Long.MAX_VALUE;
    }
    
    /**
     * Force reset position to current GNSS location.
     * Call when user manually corrects position or exits building.
     */
    public void forceResetToGnss() {
        if (positionFusion != null && latitude != 0 && longitude != 0) {
            positionFusion.forceReset(latitude, longitude, 10.0f);
        }
    }
    
    /**
     * Set anchor point for position correction.
     * User marks their known current position on map to correct accumulated drift.
     * The fusion algorithm will gradually correct towards this position.
     * 
     * @param lat Latitude of the known position
     * @param lng Longitude of the known position
     */
    public void setPositionAnchor(double lat, double lng) {
        if (positionFusion != null) {
            positionFusion.setAnchorPoint(lat, lng);
            Log.d("SensorFusion", String.format("Position anchor set at (%.6f, %.6f)", lat, lng));
        }
    }
    
    /**
     * Enable/disable building constraint.
     * When enabled, position will be constrained to building boundaries.
     * 
     * @param enable true to enable building constraint
     */
    public void setBuildingConstraint(boolean enable) {
        if (positionFusion != null) {
            positionFusion.setConstrainToBuilding(enable);
        }
    }
    
    /**
     * Check if there's an active anchor point.
     * 
     * @return true if anchor point is set
     */
    public boolean hasPositionAnchor() {
        if (positionFusion != null) {
            return positionFusion.hasAnchorPoint();
        }
        return false;
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
        // 10000 microseconds = 100Hz for IMU sensors, restored to original values
        accelerometerSensor.sensorManager.registerListener(this, accelerometerSensor.sensor, 10000, (int) maxReportLatencyNs);
        accelerometerSensor.sensorManager.registerListener(this, linearAccelerationSensor.sensor, 10000, (int) maxReportLatencyNs);
        accelerometerSensor.sensorManager.registerListener(this, gravitySensor.sensor, 10000, (int) maxReportLatencyNs);
        barometerSensor.sensorManager.registerListener(this, barometerSensor.sensor, (int) 1e6);
        gyroscopeSensor.sensorManager.registerListener(this, gyroscopeSensor.sensor, 10000, (int) maxReportLatencyNs);
        lightSensor.sensorManager.registerListener(this, lightSensor.sensor, (int) 1e6);
        proximitySensor.sensorManager.registerListener(this, proximitySensor.sensor, (int) 1e6);
        magnetometerSensor.sensorManager.registerListener(this, magnetometerSensor.sensor, 10000, (int) maxReportLatencyNs);
        stepDetectionSensor.sensorManager.registerListener(this, stepDetectionSensor.sensor, SensorManager.SENSOR_DELAY_NORMAL);
        // Higher heading update rate reduces turn lag during sharp direction changes.
        rotationSensor.sensorManager.registerListener(this, rotationSensor.sensor, 20000, (int) maxReportLatencyNs);
        gameRotationSensor.sensorManager.registerListener(this, gameRotationSensor.sensor, 10000, (int) maxReportLatencyNs);
        wifiProcessor.startListening();
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
            gameRotationSensor.sensorManager.unregisterListener(this);
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
        this.absoluteStartTime = System.currentTimeMillis();
        this.bootTime = SystemClock.uptimeMillis();
        
        // Generate trajectory ID from venue + timestamp
        String trajectoryIdPrefix = "";
        try {
            // Try to get venue name from VenueManager if available
            Class<?> venueManagerClass = Class.forName("com.openpositioning.PositionMe.presentation.fragment.VenueManager");
            java.lang.reflect.Method getInstanceMethod = venueManagerClass.getMethod("getInstance", Context.class);
            Object venueManager = getInstanceMethod.invoke(null, appContext);
            
            java.lang.reflect.Method hasVenueMethod = venueManagerClass.getMethod("hasSelectedVenue");
            boolean hasVenue = (boolean) hasVenueMethod.invoke(venueManager);
            
            if (hasVenue) {
                java.lang.reflect.Method getVenueNameMethod = venueManagerClass.getMethod("getSelectedVenueName");
                String venueName = (String) getVenueNameMethod.invoke(venueManager);
                trajectoryIdPrefix = venueName.replaceAll("\\s+", "_") + "_";
                // Capture floor so it can be saved into initialPosition.floor on stopRecording
                java.lang.reflect.Method getFloorMethod = venueManagerClass.getMethod("getSelectedFloor");
                String floor = (String) getFloorMethod.invoke(venueManager);
                this.recordingVenueFloor = (floor != null) ? floor : "";
            }
        } catch (Exception e) {
            Log.d("SensorFusion", "Could not retrieve venue from VenueManager: " + e.getMessage());
        }
        
        this.trajectoryId = trajectoryIdPrefix + System.currentTimeMillis();
        Log.d("SensorFusion", "Trajectory ID generated: " + this.trajectoryId);
        
        // Initialize corrected positions and PDR data lists
        this.correctedPositions.clear();
        this.replayTrackPoints.clear();
        this.pdrData.clear();
        this.wifiFingerprints.clear();
        this.wifiAPData.clear();
        this.bleData.clear();
        this.bleFingerprints.clear();
        this.wifiRttData.clear();
        this.testPoints.clear();  // Clear test points
        this.testPointCounter = 0;  // Reset test point counter
        this.accelMagnitude.clear();  // Clear accumulated acceleration data from previous recording
        this.initialPositionSet = false;

        // Reset heading-offset calibration so it recalibrates from the current
        // magnetic environment at the start of each new recording.
        this.headingOffsetCalibrated = false;
        this.headingOffset = 0f;
        this.headingOffsetInitSinSum = 0f;
        this.headingOffsetInitCosSum = 0f;
        this.headingOffsetInitSampleCount = 0;
        this.lastHeadingOffsetAdaptMs = 0L;
        this.lastTurnDetectedMs = 0L;
        
        // Proto: Protobuf trajectory class for sending sensor data to restful API
        // Note: start_timestamp uses MILLISECONDS (matching existing Traj.java field #10)
        this.trajectory = Traj.Trajectory.newBuilder()
                .setAndroidVersion(Build.VERSION.RELEASE)
                .setTrajectoryVersion(2.0f)
                .setTrajectoryId(this.trajectoryId != null ? this.trajectoryId : "")
                .setStartTimestamp(absoluteStartTime)  // milliseconds (System.currentTimeMillis())
                .setAccelerometerInfo(createInfoBuilder(accelerometerSensor))
                .setGyroscopeInfo(createInfoBuilder(gyroscopeSensor))
                .setMagnetometerInfo(createInfoBuilder(magnetometerSensor))
                .setBarometerInfo(createInfoBuilder(barometerSensor))
                .setLightSensorInfo(createInfoBuilder(lightSensor));



        // Cancel any existing timer before starting a new one to prevent two timers
        // running concurrently (which would double the IMU frequency and cause server rejection).
        if (this.storeTrajectoryTimer != null) {
            this.storeTrajectoryTimer.cancel();
            this.storeTrajectoryTimer.purge();
        }
        this.storeTrajectoryTimer = new Timer();
        this.storeTrajectoryTimer.schedule(new storeDataInTrajectory(), 0, TIME_CONST);
        this.pdrProcessing.resetPDR();
        
        // Reset smooth position tracking for new recording
        resetSmoothPosition();
        
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
            
            // Save all collected data to trajectory protobuf before stopping
            saveAllDataToTrajectory();
        }
        if(wakeLock.isHeld()) {
            this.wakeLock.release();
        }
    }

    /** Save collected session metadata into the trajectory protobuf before stopping recording. */
    private void saveAllDataToTrajectory() {
        try {
            // Save the user's chosen start position as initialPosition in the protobuf.
            // This is the position the user selected in StartLocationFragment before recording.
            // Without this, downloaded trajectory files have no absolute position reference,
            // causing the replay map to center on the current GPS location instead of
            // the original recording location.
            if (startLocation != null && (startLocation[0] != 0f || startLocation[1] != 0f)) {
                trajectory.setInitialPosition(Traj.GNSSPosition.newBuilder()
                        .setLatitude(startLocation[0])
                        .setLongitude(startLocation[1])
                        .setFloor(recordingVenueFloor));
                Log.i("SensorFusion", "Saved initialPosition to protobuf: "
                        + startLocation[0] + ", " + startLocation[1] + ", floor=" + recordingVenueFloor);
            } else if (initialPositionSet && (initialLocation[0] != 0f || initialLocation[1] != 0f)) {
                trajectory.setInitialPosition(Traj.GNSSPosition.newBuilder()
                        .setLatitude(initialLocation[0])
                        .setLongitude(initialLocation[1])
                        .setFloor(recordingVenueFloor));
                Log.i("SensorFusion", "Saved initialPosition (from setInitialPosition) to protobuf: "
                        + initialLocation[0] + ", " + initialLocation[1] + ", floor=" + recordingVenueFloor);
            }

            if (!testPoints.isEmpty()) {
                for (Map<String, Object> tp : testPoints) {
                    try {
                        double latitude = ((Number) tp.get("latitude")).doubleValue();
                        double longitude = ((Number) tp.get("longitude")).doubleValue();
                        long timestamp = ((Number) tp.get("timestamp")).longValue();
                        String floor = (String) tp.get("floor");
                        
                        trajectory.addTestPoints(Traj.GNSSPosition.newBuilder()
                                .setRelativeTimestamp(timestamp)
                                .setLatitude(latitude)
                                .setLongitude(longitude)
                                .setFloor(floor != null ? floor : "")
                                .build());
                    } catch (Exception e) {
                        Log.e("SensorFusion", "Error saving test point: " + e.getMessage());
                    }
                }
                // Save test point count
                trajectory.setTestPointCount(testPointCounter);
                Log.d("SensorFusion", "Test Points saved to proto: " + testPoints.size() + " points");
            }
            if (!correctedPositions.isEmpty()) {
                for (float[] correctedPosition : correctedPositions) {
                    if (correctedPosition == null || correctedPosition.length < 2) {
                        continue;
                    }
                    trajectory.addCorrectedPositions(Traj.GNSSPosition.newBuilder()
                            .setLatitude(correctedPosition[0])
                            .setLongitude(correctedPosition[1])
                            .build());
                }
            }

            if (!replayTrackPoints.isEmpty()) {
                for (ReplayTrackPoint point : replayTrackPoints) {
                    trajectory.addCorrectedPositions(Traj.GNSSPosition.newBuilder()
                            .setRelativeTimestamp(point.relativeTimestamp)
                            .setLatitude(point.latitude)
                            .setLongitude(point.longitude)
                            .build());
                }
                Log.d("SensorFusion", "Replay track samples saved: " + replayTrackPoints.size());
            }
            
            // Save WiFi AP Data
            if (!wifiAPData.isEmpty()) {
                for (Map<String, Object> apData : wifiAPData) {
                    try {
                        long mac = (long) apData.get("mac");
                        String ssid = (String) apData.get("ssid");
                        long frequency = (long) apData.get("frequency");
                        boolean rttEnabled = (boolean) apData.get("rtt_enabled");
                        
                        trajectory.addApsData(Traj.WiFiAPData.newBuilder()
                                .setMac(mac)
                                .setSsid(ssid)
                                .setFrequency(frequency)
                                .setRttEnabled(rttEnabled)
                                .build());
                    } catch (Exception e) {
                        Log.e("SensorFusion", "Error saving WiFi AP data: " + e.getMessage());
                    }
                }
                Log.d("SensorFusion", "WiFi AP Data saved to proto: " + wifiAPData.size() + " access points");
                
                // Log RTT flags separately (will be saved to proto once recompiled)
                int rttCount = 0;
                for (Map<String, Object> apData : wifiAPData) {
                    if ((boolean) apData.get("rtt_enabled")) {
                        rttCount++;
                    }
                }
                if (rttCount > 0) {
                    Log.d("SensorFusion", "WiFi RTT enabled: " + rttCount + " / " + wifiAPData.size() + " APs");
                }
            }

            if (!bleData.isEmpty()) {
                for (Map<String, Object> ble : bleData) {
                    try {
                        String macAddress = (String) ble.get("mac_address");
                        String name = (String) ble.get("name");
                        int txPower = ((Number) ble.get("tx_power")).intValue();
                        int flags = ((Number) ble.get("flags")).intValue();

                        @SuppressWarnings("unchecked")
                        List<String> serviceUuids = (List<String>) ble.get("service_uuids");

                        Traj.BleData.Builder bleBuilder = Traj.BleData.newBuilder()
                                .setMacAddress(macAddress != null ? macAddress : "")
                                .setName(name != null ? name : "")
                                .setTxPowerLevel(txPower)
                                .setAdvertiseFlags(flags);

                        if (serviceUuids != null) {
                            bleBuilder.addAllServiceUuids(serviceUuids);
                        }

                        trajectory.addBleData(bleBuilder.build());
                    } catch (Exception e) {
                        Log.e("SensorFusion", "Error saving BLE data: " + e.getMessage());
                    }
                }
            }
            
            Log.i("SensorFusion", "Protobuf data saved: testPoints=" + testPoints.size()
                    + " wifiAPs=" + wifiAPData.size() + " ble=" + bleData.size());
            
        } catch (Exception e) {
            Log.e("SensorFusion", "Error saving data to trajectory: " + e.getMessage(), e);
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
            long currentTimestamp = SystemClock.uptimeMillis() - bootTime;
            
            // Clamp magnetometer values to [-999, 999] range (server validation limit)
            float magX = Math.max(-999f, Math.min(999f, magneticField[0]));
            float magY = Math.max(-999f, Math.min(999f, magneticField[1]));
            float magZ = Math.max(-999f, Math.min(999f, magneticField[2]));

            // Normalize the rotation vector quaternion before storing.
            // Android's rotation vector sensor can produce quaternions with norm slightly
            // above 1.0 due to floating-point drift. The server rejects quaternions whose
            // norm deviates more than 1% from 1.0.
            float qx = rotation[0], qy = rotation[1], qz = rotation[2], qw = rotation[3];
            float norm = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            if (norm > 0f && Math.abs(norm - 1.0f) > 1e-6f) {
                qx /= norm;
                qy /= norm;
                qz /= norm;
                qw /= norm;
            }
            
            // Store IMU and magnetometer data in Trajectory class
            trajectory.addImuData(Traj.IMUReading.newBuilder()
                    .setRelativeTimestamp(currentTimestamp)
                    .setAcc(Traj.Vector3.newBuilder()
                            .setX(acceleration[0])
                            .setY(acceleration[1])
                            .setZ(acceleration[2]))
                    .setGyr(Traj.Vector3.newBuilder()
                            .setX(angularVelocity[0])
                            .setY(angularVelocity[1])
                            .setZ(angularVelocity[2]))
                    .setRotationVector(Traj.Quaternion.newBuilder()
                            .setX(qx)
                            .setY(qy)
                            .setZ(qz)
                            .setW(qw))
                    .setStepCount(stepCounter))
                    .addMagnetometerData(Traj.MagnetometerReading.newBuilder()
                            .setRelativeTimestamp(currentTimestamp)
                            .setMag(Traj.Vector3.newBuilder()
                                    .setX(magX)
                                    .setY(magY)
                                    .setZ(magZ)));
            
            // Collect WiFi fingerprint data from all scanned networks
            // This stores RSSI values from all detected WiFi networks at this timestamp
            // Divide timer with a counter for storing data every 1 second
            if (counter == 99) {
                counter = 0;
                // Store pressure and light data
                if (barometerSensor.sensor != null) {
                    trajectory.addPressureData(Traj.BarometerReading.newBuilder()
                                    .setRelativeTimestamp(currentTimestamp)
                                    .setPressure(pressure))
                            .addLightData(Traj.LightReading.newBuilder()
                                    .setRelativeTimestamp(currentTimestamp)
                                    .setLight(light)
                                    .build());
                    
                    // Store proximity sensor data if available
                    if (proximitySensor.sensor != null) {
                        setProximity(proximity);
                    }
                }

                // Divide the timer for storing AP data every 5 seconds
                if (secondCounter == 4) {
                    secondCounter = 0;
                    //Current Wifi Object
                    Wifi currentWifi = wifiProcessor.getCurrentWifiData();
                    // Only store aps_data when connected to a valid WiFi AP.
                    // mac=0 or frequency=0 means no connection or anonymised BSSID on Android 12+;
                    // the server rejects entries with these invalid values.
                    if (currentWifi.getBssid() != 0 && currentWifi.getFrequency() != 0) {
                        String ssid = currentWifi.getSsid();
                        trajectory.addApsData(Traj.WiFiAPData.newBuilder()
                                .setMac(currentWifi.getBssid())
                                .setSsid(ssid != null ? ssid : "")
                                .setFrequency(currentWifi.getFrequency()));
                    }
                    
                    // Store WiFi fingerprints every 5 seconds
                    // (stores all WiFi networks detected in current scan)
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

    //region New Proto 2.0 Support Methods

    /**
     * Get the trajectory identifier (name) for the current recording
     * @return trajectory ID combining venue name and timestamp
     */
    public String getTrajectoryId() {
        return trajectoryId;
    }

    /**
     * Set the initial position for trajectory
     * @param lat Initial latitude
     * @param lon Initial longitude
     * @param orientation Initial device orientation (in degrees)
     */
    public void setInitialPosition(float lat, float lon, float orientation) {
        if (!initialPositionSet) {
            this.initialLocation[0] = lat;
            this.initialLocation[1] = lon;
            this.initialOrientation = orientation;
            this.initialPositionSet = true;
            Log.d("SensorFusion", String.format(
                "InitialPosition SET ✓ | Lat: %.6f | Lon: %.6f | Bearing: %.1f° | Flag: %s",
                lat, lon, orientation, initialPositionSet
            ));
        } else {
            Log.w("SensorFusion", String.format("InitialPosition already set (%s), ignoring new value | New: (%.6f, %.6f)", 
                initialPositionSet, lat, lon));
        }

        // Keep fusion/PF in sync with the user-confirmed map anchor.
        // Without this, UI "Set Position" only updates metadata and the live fusion state
        // can stay on stale GNSS initialization, making PF appear ineffective.
        setStartGNSSLatitude(new float[]{lat, lon});
    }

    /**
     * Get initial position
     * @return float array with [latitude, longitude]
     */
    public float[] getInitialPosition() {
        return initialLocation;
    }

    /**
     * Get initial orientation
     * @return Initial device bearing/orientation
     */
    public float getInitialOrientation() {
        return initialOrientation;
    }

    /**
     * Add a corrected position to the trajectory
     * @param lat Corrected latitude
     * @param lon Corrected longitude
     */
    public void addCorrectedPosition(float lat, float lon) {
        correctedPositions.add(new float[]{lat, lon});
    }

    /**
     * Add a fused map trajectory point to be used as high-fidelity replay data.
     */
    public void addReplayTrackPoint(double lat, double lon) {
        if (!saveRecording) {
            return;
        }
        long relativeTimestamp = Math.max(0L, System.currentTimeMillis() - absoluteStartTime);
        replayTrackPoints.add(new ReplayTrackPoint(relativeTimestamp, lat, lon));
    }

    /**
     * Get all corrected positions
     * @return List of corrected positions
     */
    public List<float[]> getCorrectedPositions() {
        return correctedPositions;
    }

    /**
     * Add WiFi fingerprint data
     * @param timestamp Relative timestamp in milliseconds
     * @param bssid MAC address as long
     * @param rssi RSSI value in dBm
     */
    public void addWiFiFingerprint(long timestamp, long bssid, int rssi) {
        Map<String, Object> fingerprint = new HashMap<>();
        fingerprint.put("timestamp", timestamp);
        fingerprint.put("mac", bssid);
        fingerprint.put("rssi", rssi);
        wifiFingerprints.add(fingerprint);
    }

    /**
     * Get WiFi fingerprint data
     * @return List of WiFi fingerprints
     */
    public List<Map<String, Object>> getWiFiFingerprints() {
        return wifiFingerprints;
    }

    /**
     * Add WiFi RTT measurement
     * @param timestamp Relative timestamp in milliseconds
     * @param mac MAC address as long  
     * @param distance Distance in mm
     * @param distanceStd Standard deviation in mm
     * @param rssi RSSI value in dBm
     */
    public void addWiFiRTTReading(long timestamp, long mac, float distance, float distanceStd, int rssi) {
        Map<String, Object> rttData = new HashMap<>();
        rttData.put("timestamp", timestamp);
        rttData.put("mac", mac);
        rttData.put("distance_mm", distance);
        rttData.put("distance_std_mm", distanceStd);
        rttData.put("rssi", rssi);
        wifiRttData.add(rttData);
    }

    /**
     * Get WiFi RTT data
     * @return List of WiFi RTT readings
     */
    public List<Map<String, Object>> getWiFiRTTData() {
        return wifiRttData;
    }

    /**
     * Add BLE fingerprint data
     * @param timestamp Relative timestamp in milliseconds
     * @param macAddress MAC address as string
     * @param rssi RSSI value in dBm
     * @param txPower TX power level
     */
    public void addBLEFingerprint(long timestamp, String macAddress, int rssi, int txPower) {
        Map<String, Object> bleFingerprint = new HashMap<>();
        bleFingerprint.put("timestamp", timestamp);
        bleFingerprint.put("mac", macAddress);
        bleFingerprint.put("rssi", rssi);
        bleFingerprint.put("tx_power", txPower);
        bleFingerprints.add(bleFingerprint);
    }

    /**
     * Get BLE fingerprint data
     * @return List of BLE fingerprints
     */
    public List<Map<String, Object>> getBLEFingerprints() {
        return bleFingerprints;
    }

    /**
     * Add BLE device data
     * @param macAddress MAC address
     * @param name Device name
     * @param txPower TX power level
     * @param flags Advertisement flags
     * @param serviceUuids Service UUIDs
     */
    public void addBLEData(String macAddress, String name, int txPower, int flags, List<String> serviceUuids) {
        Map<String, Object> ble = new HashMap<>();
        ble.put("mac_address", macAddress);
        ble.put("name", name);
        ble.put("tx_power", txPower);
        ble.put("flags", flags);
        ble.put("service_uuids", serviceUuids);
        bleData.add(ble);
    }

    /**
     * Get BLE data
     * @return List of BLE devices
     */
    public List<Map<String, Object>> getBLEData() {
        return bleData;
    }

    /**
     * Add WiFi Access Point (AP) data with RTT capability flag
     * @param mac MAC address (BSSID) as long
     * @param ssid Network name
     * @param frequency Frequency in MHz (2400 or 5000)
     * @param rttEnabled Flag indicating if AP supports RTT measurements
     */
    public void addWiFiAPData(long mac, String ssid, long frequency, boolean rttEnabled) {
        Map<String, Object> apData = new HashMap<>();
        apData.put("mac", mac);
        apData.put("ssid", ssid);
        apData.put("frequency", frequency);
        apData.put("rtt_enabled", rttEnabled);  // WiFi RTT capability flag
        wifiAPData.add(apData);
    }

    /**
     * Get WiFi Access Point data with RTT flags
     * @return List of WiFi AP data
     */
    public List<Map<String, Object>> getWiFiAPData() {
        return wifiAPData;
    }    /**
     * Add PDR (Pedestrian Dead Reckoning) sample
     * @param timestamp Relative timestamp in milliseconds
     * @param x X position in meters
     * @param y Y position in meters
     */
    public void addPDRSample(long timestamp, float x, float y) {
        float[] pdr = new float[]{timestamp, x, y};
        pdrData.add(pdr);
    }

    /**
     * Get PDR data
     * @return List of PDR samples
     */
    public List<float[]> getPDRData() {
        return pdrData;
    }

    /**
     * Update current proximity sensor reading
     * @param distance Distance in cm
     */
    public void setProximity(float distance) {
        this.currentProximity = distance;
    }

    /**
     * Get current proximity reading
     * @return Proximity distance in cm
     */
    public float getProximity() {
        return currentProximity;
    }

    /**
     * Get trajectory version
     * @return Trajectory version (2.0)
     */
    public float getTrajectoryVersion() {
        return trajectoryVersion;
    }

    /**
     * Get number of collected WiFi fingerprints
     * @return Count of WiFi fingerprints
     */
    public int getWiFiFingerprintCount() {
        return wifiFingerprints.size();
    }

    /**
     * Get number of collected corrected positions
     * @return Count of corrected positions
     */
    public int getCorrectedPositionCount() {
        return correctedPositions.size();
    }

    /**
     * Check if initial position has been set
     * @return True if initial position is set, false otherwise
     */
    public boolean isInitialPositionSet() {
        boolean result = initialPositionSet || (initialLocation != null && (initialLocation[0] != 0 || initialLocation[1] != 0));
        return result;
    }

    // TEST POINT METHODS

    /**
     * Add a test point (marker) with current timestamp and location
     * @param latitude Current latitude
     * @param longitude Current longitude
     * @param floor Current floor (if applicable)
     * @return Test point number (starting from 1)
     */
    public int addTestPoint(double latitude, double longitude, String floor) {
        testPointCounter++;
        
        Map<String, Object> testPoint = new HashMap<>();
        testPoint.put("point_number", testPointCounter);
        testPoint.put("timestamp", System.currentTimeMillis() - absoluteStartTime);
        testPoint.put("latitude", latitude);
        testPoint.put("longitude", longitude);
        testPoint.put("floor", floor);
        
        testPoints.add(testPoint);
        
        Log.d("SensorFusion", String.format(
            "Test Point #%d marked | Lat: %.6f | Lon: %.6f | Floor: %s | Timestamp: %d ms",
            testPointCounter, latitude, longitude, floor != null ? floor : "unknown", 
            (long) testPoint.get("timestamp")
        ));
        
        return testPointCounter;
    }

    /**
     * Get all recorded test points
     * @return List of test points
     */
    public List<Map<String, Object>> getTestPoints() {
        return testPoints;
    }

    /**
     * Get the current test point counter
     * @return Number of test points marked so far
     */
    public int getTestPointCount() {
        return testPointCounter;
    }

    /**
     * Get a specific test point by number
     * @param pointNumber The test point number (1-indexed)
     * @return Test point data or null if not found
     */
    public Map<String, Object> getTestPoint(int pointNumber) {
        for (Map<String, Object> tp : testPoints) {
            if ((int) tp.get("point_number") == pointNumber) {
                return tp;
            }
        }
        return null;
    }

    //endregion

}
