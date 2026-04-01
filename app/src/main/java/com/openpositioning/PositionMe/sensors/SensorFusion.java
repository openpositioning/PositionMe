package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.os.Build;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import com.openpositioning.PositionMe.Traj;
import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.presentation.fragment.SettingsFragment;
import com.openpositioning.PositionMe.utils.CircularFloatBuffer;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.PdrProcessing;
import com.openpositioning.PositionMe.utils.VenueSelectionHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
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
 *
 * New code guide:
 * 1. Floor-plan state shared with map matching and indoor display.
 * 2. PDR alignment, wall guidance, and zero-velocity correction.
 * 3. Particle-filter fusion for GNSS/WiFi/PDR updates.
 * 4. Automatic floor switching and floor-plan-aware display snapshots.
 */
public class SensorFusion implements SensorEventListener, Observer {

    // Store the last event timestamps for each sensor type
    private final Map<Integer, Long> lastEventTimestamps = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> eventCounts = new ConcurrentHashMap<>();

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
    private HandlerThread motionSensorThread;
    private Handler motionSensorHandler;
    private HandlerThread sensorCallbackThread;
    private Handler sensorCallbackHandler;
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
    private MovementSensor gameRotationSensor;
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
    private String pendingRecordingName = "";
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
    private float[] gameRotation;
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

    // Assignment 2 fusion state in local XY coordinates and floor-plan space.
    private com.openpositioning.PositionMe.utils.CoordinateUtils coordinateUtils;
    private com.openpositioning.PositionMe.data.remote.FloorPlan currentFloorPlan;
    private final Map<Integer, com.openpositioning.PositionMe.data.remote.FloorPlan> availableFloorPlans = new HashMap<>();
    private volatile LatLng latestGnssLatLng;
    private volatile LatLng latestWifiLatLng;
    private volatile float[] latestGnssXY = null;
    private volatile float[] latestWifiXY = null;
    private volatile float[] latestRawPdrXY = new float[]{0f, 0f};
    private volatile float[] pdrAlignmentOffsetXY = new float[]{0f, 0f};
    private volatile float[] latestPdrXY = new float[]{0f, 0f};
    private volatile float[] latestPfXY = null;
    private volatile float[] latestFusedXY = new float[]{0f, 0f};
    private volatile long lastDisplayTrajectoryUpdateMs = 0L;
    private volatile float latestMotionHeadingRad = Float.NaN;
    private volatile long lastMotionHeadingTimeMs = 0L;
    private volatile float latestHorizontalAccelerationHeadingRad = Float.NaN;
    private volatile float latestHorizontalAccelerationMagnitude = 0f;
    private volatile long lastHorizontalAccelerationHeadingTimeMs = 0L;
    private volatile float latestWallAlignedHeadingRad = Float.NaN;
    private volatile long lastWallAlignedHeadingTimeMs = 0L;
    private volatile long lastHeadingSensorUpdateMs = 0L;
    private volatile long lastGameHeadingSensorUpdateMs = 0L;
    private volatile boolean headingInitialized = false;
    private volatile float filteredGameHeadingRad = Float.NaN;
    private volatile float gameHeadingAlignmentOffsetRad = Float.NaN;
    private volatile float latestMagneticFieldStrengthUt = Float.NaN;
    private volatile long lastMagneticFieldUpdateMs = 0L;
    private volatile long lastMagneticDisturbanceTimeMs = 0L;
    private volatile boolean magneticDisturbanceActive = false;
    private volatile float latestGnssAccuracyMeters = Float.NaN;
    private volatile float latestGnssSpeedMps = Float.NaN;
    private volatile Integer latestWifiObservedFloor = null;
    private volatile long latestGnssFixTimeMs = 0L;
    private volatile long latestWifiFixTimeMs = 0L;
    private volatile long lastGnssEnqueueMs = 0L;
    private volatile float[] lastAcceptedWifiXY = null;
    private volatile float[] lastRejectedWifiXY = null;
    private volatile long lastAcceptedWifiFixTimeMs = 0L;
    private volatile int consecutiveRejectedWifiUpdates = 0;
    private volatile int repeatedRejectedWifiClusterCount = 0;
    private volatile boolean wifiPositionRequestInFlight = false;
    private volatile boolean pendingWifiPositionRequest = false;
    private volatile long lastWifiPositionRequestMs = 0L;
    private volatile String lastWifiFingerprintSignature = "";
    private volatile long lastWifiFingerprintSignatureTimeMs = 0L;
    private volatile int latestWifiApCount = 0;
    private volatile float latestWifiAverageLevelDbm = Float.NaN;
    private volatile float latestWifiQuality01 = 0f;
    private volatile boolean latestWifiUsesCoarsePositioning = false;
    private volatile long lastAppliedGnssMeasurementMs = 0L;
    private volatile long lastFusedUpdateTimeMs = 0L;
    private volatile long lastAbsoluteMeasurementTimeMs = 0L;
    private volatile long lastZeroVelocityCorrectionTimeMs = 0L;
    private volatile long lastWallGuidanceTimeMs = 0L;
    private volatile int consecutiveWallGuidedSteps = 0;
    private int pendingFloorDelta = 0;
    public int currentFloor = 0;
    private int initialFloorHint = 0;
    private long lastFloorSwitchTimeMs = 0L;
    private float confirmedFloorReferenceElevation = 0f;
    private boolean confirmedFloorReferenceInitialized = false;
    private boolean wifiAnchorEstablished = false;
    private boolean userProvidedStartLocation = false;
    private com.openpositioning.PositionMe.utils.MapMatchingEngine mapMatchingEngine;
    private com.openpositioning.PositionMe.utils.ParticleFilter particleFilter;

    private static final long WIFI_FRESH_TIMEOUT_MS = 30000L;
    private static final long GNSS_FRESH_TIMEOUT_MS = 8000L;
    private static final long WIFI_INITIALIZATION_GRACE_MS = 7000L;
    private static final float MAX_TRAJECTORY_STEP_METERS = 3.2f;
    private static final float WALL_GUIDANCE_MARGIN_METERS = 0.22f;
    private static final float WALL_GUIDANCE_HEADING_BLEND = 0.65f;  // was 0.82f — less wall heading lock, faster detach on turns
    private static final float WALL_GUIDANCE_MIN_STEP_METERS = 0.08f;
    private static final float WALL_GUIDANCE_MIN_CORRECTION_METERS = 0.03f;
    private static final float WALL_GUIDANCE_COLLISION_INTENT_MIN_GAIN_METERS = 0.05f;
    private static final float WALL_GUIDANCE_SENSOR_INTENT_BLEND = 0.28f;
    private static final float WALL_GUIDANCE_MOTION_INTENT_BLEND = 0.18f;
    private static final float WALL_GUIDANCE_ACCEL_INTENT_BLEND = 0.34f;
    private static final long WALL_GUIDANCE_RESET_TIMEOUT_MS = 2200L;
    private static final int WALL_LOCK_RECOVERY_REQUIRED_STEPS = 1;
    private static final float WALL_LOCK_RECOVERY_BLEND = 0.42f;
    private static final float WALL_LOCK_RECOVERY_MAX_SHIFT_METERS = 0.90f;
    private static final float WALL_LOCK_RECOVERY_MIN_WIFI_GAIN_METERS = 0.35f;
    private static final float AUTO_FLOOR_HEIGHT_STEP_METERS = 4.0f;
    private static final long FLOOR_SWITCH_COOLDOWN_MS = 900L;
    private static final float DEFAULT_WIFI_MEASUREMENT_STD_METERS = 3.0f;
    private static final float WIFI_REANCHOR_STD_METERS = 1.8f;
    private static final float DEFAULT_PARTICLE_INIT_STD_METERS = 1.2f;
    private static final float FLOOR_TRANSITION_CONFIRM_RATIO = 0.65f;
    private static final float FLOOR_TRANSITION_MIN_METERS = 2.0f;
    private static final float WIFI_OUTLIER_BASE_METERS = 3.2f;
    private static final float WIFI_OUTLIER_SPEED_LIMIT_MPS = 1.6f;
    private static final float WIFI_OUTLIER_MAX_METERS = 16f;
    private static final int WIFI_OUTLIER_REJECT_LIMIT = 4;
    private static final long WIFI_POSITION_REQUEST_MIN_INTERVAL_MS = 1800L;
    private static final long WIFI_DUPLICATE_FINGERPRINT_SUPPRESSION_MS = 2200L;
    private static final int WIFI_FINGERPRINT_MAX_AP_COUNT = 12;
    private static final int WIFI_MIN_AP_COUNT_FOR_POSITIONING = 4;
    private static final int WIFI_MIN_AP_COUNT_FOR_FINE_POSITIONING = 6;
    private static final int WIFI_MIN_AP_COUNT_FOR_STRONG_FIX = 8;
    private static final int WIFI_STRONG_SIGNAL_LEVEL_DBM = -72;
    private static final int WIFI_ACCEPTABLE_SIGNAL_LEVEL_DBM = -80;
    private static final int WIFI_MIN_SIGNAL_LEVEL_DBM = -92;
    private static final int WIFI_MIN_STRONG_APS_FOR_FINE = 2;
    private static final long GNSS_ENQUEUE_MIN_INTERVAL_MS = 900L;
    private static final float GNSS_MIN_STD_STANDALONE_METERS = 2.8f;
    private static final float GNSS_WIFI_OUTLIER_ASSIST_MAX_ACCURACY_METERS = 6f;
    private static final float GNSS_WIFI_OUTLIER_ASSIST_MAX_DISTANCE_METERS = 5f;
    private static final float GNSS_HIGH_CONFIDENCE_MAX_ACCURACY_METERS = 5f;
    private static final float GNSS_REDUCED_WEIGHT_MAX_ACCURACY_METERS = 10f;
    private static final float GNSS_LOW_CONFIDENCE_MAX_ACCURACY_METERS = 15f;
    private static final float GNSS_HIGH_CONFIDENCE_MAX_STD_METERS = 4.6f;
    private static final float GNSS_LOW_CONFIDENCE_MAX_STD_METERS = 12f;
    private static final float GNSS_DISCARD_ABSOLUTE_ACCURACY_METERS = 15f;
    private static final float GNSS_FULL_FUSION_WEIGHT = 0.80f;
    private static final float GNSS_REDUCED_FUSION_WEIGHT = 0.10f;
    private static final float GNSS_MINIMAL_FUSION_WEIGHT = 0.02f;
    private static final long GNSS_HIGH_CONFIDENCE_UPDATE_MIN_INTERVAL_MS = 1200L;
    private static final long GNSS_LOW_CONFIDENCE_UPDATE_MIN_INTERVAL_MS = 2200L;
    private static final float WIFI_START_ANCHOR_WEIGHT = 0.94f;
    private static final float WIFI_MEASUREMENT_MAX_CORRECTION_METERS = 14.0f;
    private static final float WIFI_OUTLIER_RELEASE_MARGIN_METERS = 18.0f;
    private static final float WIFI_PDR_ALIGNMENT_MAX_SHIFT_METERS = 2.0f;
    private static final float WIFI_OUTLIER_WEAK_UPDATE_STD_METERS = 12.5f;
    private static final float WIFI_REJECTED_CLUSTER_RADIUS_METERS = 5.5f;
    private static final int WIFI_REJECTED_CLUSTER_ACCEPT_COUNT = 2;
    private static final long MANUAL_START_WIFI_ASSIST_WINDOW_MS = 15000L;
    private static final float MANUAL_START_PARTICLE_INIT_STD_METERS = 0.9f;
    private static final float WIFI_MOBILE_ASSIST_MAX_GNSS_ACCURACY_METERS = 12f;
    private static final float WIFI_MOBILE_ASSIST_STRONG_GNSS_BLEND = 0.16f;
    private static final float WIFI_MOBILE_ASSIST_WEAK_GNSS_BLEND = 0.10f;
    private static final float WIFI_MOBILE_ASSIST_CLAMP_MARGIN_METERS = 6f;
    private static final float WIFI_MANUAL_START_CLAMP_METERS = 8f;
    private static final float WIFI_MANUAL_START_WITH_GNSS_CLAMP_METERS = 6f;
    private static final float WIFI_COARSE_MEASUREMENT_STD_METERS = 7.8f;
    private static final float WIFI_STRONG_FINE_MEASUREMENT_STD_METERS = 3.2f;
    private static final float WIFI_MOTION_LATERAL_CLAMP_METERS = 1.1f;
    private static final float WIFI_MOTION_LATERAL_RELAXED_CLAMP_METERS = 1.8f;
    private static final float WIFI_MOTION_FORWARD_CLAMP_METERS = 5.5f;
    private static final float WIFI_MOTION_FORWARD_RELAXED_CLAMP_METERS = 8.0f;
    private static final float WIFI_MOTION_BACKTRACK_CLAMP_METERS = 0.65f;
    private static final float WIFI_MOTION_BACKTRACK_RELAXED_CLAMP_METERS = 1.25f;
    private static final int PARTICLE_COUNT = 140;
    private static final float HEADING_SMOOTH_ALPHA = 0.58f;
    private static final float GAME_HEADING_SMOOTH_ALPHA = 0.24f;
    private static final float GAME_HEADING_ALIGNMENT_ALPHA = 0.08f;
    private static final long MOTION_HEADING_FRESH_TIMEOUT_MS = 2200L;
    private static final long HORIZONTAL_ACCEL_HEADING_FRESH_TIMEOUT_MS = 850L;
    private static final float HORIZONTAL_ACCEL_HEADING_MIN_MAGNITUDE_MPS2 = 0.16f;
    private static final float HORIZONTAL_ACCEL_HEADING_BLEND = 0.34f;
    private static final long WALL_HEADING_OVERRIDE_TIMEOUT_MS = 2600L;
    private static final float WALL_HEADING_OVERRIDE_BLEND = 0.82f;
    private static final long PDR_WIFI_PULL_FRESH_TIMEOUT_MS = 9000L;
    private static final float PDR_WIFI_PULL_STRONG_DRIFT_METERS = 4.8f;
    private static final float PDR_WIFI_PULL_WEAK_DRIFT_METERS = 6.8f;
    private static final float PDR_WIFI_PULL_COARSE_DRIFT_METERS = 8.5f;
    private static final long GAME_HEADING_FRESH_TIMEOUT_MS = 2200L;
    private static final long MAGNETIC_FIELD_FRESH_TIMEOUT_MS = 1800L;
    private static final long MAGNETIC_DISTURBANCE_HOLD_MS = 2600L;
    private static final float MAGNETIC_FIELD_STRENGTH_DELTA_UT = 8f;
    private static final float MAGNETIC_FIELD_STRENGTH_RATIO_DELTA = 0.18f;
    private static final float MAGNETIC_HEADING_DISAGREEMENT_RAD = (float) Math.toRadians(30.0);
    private static final float DISPLAY_HEADING_LOW_MOTION_BLEND = 0.38f;
    private static final float DISPLAY_HEADING_MEDIUM_MOTION_BLEND = 0.56f;
    private static final float DISPLAY_HEADING_HIGH_MOTION_BLEND = 0.72f;
    private static final float DISPLAY_HEADING_ACTIVE_MOTION_BLEND = 0.84f;
    private static final float DISPLAY_HEADING_FORCE_MOTION_BLEND = 0.92f;
    private static final long DISPLAY_HEADING_ACTIVE_STEP_TIMEOUT_MS = 1700L;
    private static final float DISPLAY_HEADING_ACTIVE_GNSS_SPEED_MPS = 0.55f;
    private static final float FUSED_MOTION_HEADING_MIN_DISTANCE_METERS = 0.32f;
    private static final float FUSED_MOTION_HEADING_MAX_DISTANCE_METERS = 1.9f;
    private static final long FLOOR_SWITCH_HEADING_FREEZE_MS = 1800L;
    private static final float DISPLAY_HEADING_CLOSE_AGREEMENT_RAD = (float) Math.toRadians(18.0);
    private static final float DISPLAY_HEADING_MEDIUM_AGREEMENT_RAD = (float) Math.toRadians(40.0);
    private static final float DISPLAY_HEADING_FORCE_MOTION_DISAGREEMENT_RAD = (float) Math.toRadians(62.0);
    private static final boolean WIFI_SIGNAL_FILTERING_ENABLED = true;
    private static final long HEADING_SENSOR_FRESH_TIMEOUT_MS = 1500L;
    private static final long MIN_VALID_STEP_INTERVAL_MS = 220L;
    private static final float MOTION_PREDICTION_MIN_SPEED_MPS = 0.45f;
    private static final float MOTION_PREDICTION_MAX_SPEED_MPS = 2.40f;
    private static final float MOTION_PREDICTION_MIN_ACCEL_MPS2 = 0.55f;
    private static final float MOTION_PREDICTION_MAX_ACCEL_MPS2 = 2.40f;
    private static final int STATIONARY_ACCEL_WINDOW_SAMPLES = 40;
    private static final long STATIONARY_STEP_TIMEOUT_MS = 3200L;
    private static final float STATIONARY_LINEAR_ACCEL_AVG_THRESHOLD = 0.12f;
    private static final float STATIONARY_LINEAR_ACCEL_PEAK_THRESHOLD = 0.48f;
    private static final float STATIONARY_GNSS_SPEED_THRESHOLD_MPS = 0.28f;
    private static final float STATIONARY_WIFI_SUPPRESSION_RADIUS_METERS = 1.8f;
    private static final float STATIONARY_WIFI_WEAK_UPDATE_RADIUS_METERS = 4.0f;
    private static final float STATIONARY_WIFI_WEAK_UPDATE_STD_METERS = 18f;
    private static final float STATIONARY_WIFI_CLAMP_METERS = 0.45f;
    private static final long ZERO_VELOCITY_CORRECTION_MIN_INTERVAL_MS = 700L;
    private static final long ZERO_VELOCITY_ANCHOR_FRESH_TIMEOUT_MS = 5000L;
    private static final float ZERO_VELOCITY_MIN_DRIFT_METERS = 0.18f;
    private static final float ZERO_VELOCITY_ENTER_BLEND = 0.62f;
    private static final float ZERO_VELOCITY_HOLD_BLEND = 0.28f;
    private static final float ZERO_VELOCITY_MAX_SHIFT_METERS = 0.40f;

    // Over time accelerometer magnitude values since last step
    private List<Double> accelMagnitude;
    private CircularFloatBuffer recentLinearAccelerationMagnitudes;
    private boolean zeroVelocityConstraintActive = false;

    // PDR calculation class
    private PdrProcessing pdrProcessing;

    // Trajectory displaying class
    private PathView pathView;
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    // Latest trajectory coordinates from step updates (x,y)
    private volatile float[] lastCords = null;

    // WiFi positioning object
    private WiFiPositioning wiFiPositioning;
    private Timer timer;
    private final ExecutorService fusionUpdateExecutor = Executors.newSingleThreadExecutor();
    // Record test points (Add Tag)
    private final java.util.ArrayList<com.openpositioning.PositionMe.Traj.GNSSPosition> testPoints =
            new java.util.ArrayList<>();
    // Record recording start time (ms)
    private long startTimestampMs = 0L;


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
        this.gameRotation = new float[4];
        this.rotation[3] = 1.0f;
        this.gameRotation[3] = 1.0f;
        this.orientation[0] = Float.NaN;
        this.R = new float[9];
        this.coordinateUtils = new com.openpositioning.PositionMe.utils.CoordinateUtils();
        this.mapMatchingEngine = new com.openpositioning.PositionMe.utils.MapMatchingEngine(this.coordinateUtils);
        this.particleFilter = new com.openpositioning.PositionMe.utils.ParticleFilter(PARTICLE_COUNT);
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

    // Called by indoor map UI when downloaded floor metadata changes.
    public synchronized void setCurrentFloorPlan(com.openpositioning.PositionMe.data.remote.FloorPlan floorPlan) {
        this.currentFloorPlan = floorPlan;
    }

    // Keeps fusion-side floor resolution consistent with the currently available building maps.
    public synchronized void setAvailableFloorPlans(Map<Integer, com.openpositioning.PositionMe.data.remote.FloorPlan> floorPlans) {
        availableFloorPlans.clear();
        if (floorPlans != null) {
            availableFloorPlans.putAll(floorPlans);
        }
        this.initialFloorHint = normalizeFloorToAvailable(this.initialFloorHint);
        this.currentFloor = normalizeFloorToAvailable(this.currentFloor);
        this.currentFloorPlan = resolveFloorPlan(currentFloor);
        if (this.currentFloorPlan == null) {
            this.currentFloorPlan = resolveFloorPlan(initialFloorHint);
        }
    }

    private synchronized com.openpositioning.PositionMe.data.remote.FloorPlan resolveFloorPlan(int floor) {
        com.openpositioning.PositionMe.data.remote.FloorPlan floorPlan = availableFloorPlans.get(floor);
        return floorPlan != null ? floorPlan : currentFloorPlan;
    }

    private synchronized Map<Integer, com.openpositioning.PositionMe.data.remote.FloorPlan> getFloorPlanMapForFilter() {
        Map<Integer, com.openpositioning.PositionMe.data.remote.FloorPlan> floorPlans = new HashMap<>();
        if (!availableFloorPlans.isEmpty()) {
            floorPlans.putAll(availableFloorPlans);
            return floorPlans;
        }
        if (currentFloorPlan != null) {
            floorPlans.put(currentFloor, currentFloorPlan);
        }
        return floorPlans;
    }

    private synchronized int normalizeFloorToAvailable(int floorCandidate) {
        if (availableFloorPlans.isEmpty()) {
            return floorCandidate;
        }
        if (availableFloorPlans.containsKey(floorCandidate)) {
            return floorCandidate;
        }
        int nearestFloor = floorCandidate;
        int nearestDistance = Integer.MAX_VALUE;
        for (Integer knownFloor : availableFloorPlans.keySet()) {
            if (knownFloor == null) {
                continue;
            }
            int distance = Math.abs(knownFloor - floorCandidate);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestFloor = knownFloor;
            }
        }
        return nearestDistance == Integer.MAX_VALUE
                ? availableFloorPlans.keySet().iterator().next()
                : nearestFloor;
    }

    private synchronized Integer resolvePreferredInitializationFloor(Integer observedFloor) {
        if (observedFloor != null) {
            return normalizeFloorToAvailable(observedFloor);
        }
        long nowMs = System.currentTimeMillis();
        if (latestWifiObservedFloor != null
                && latestWifiFixTimeMs > 0L
                && (nowMs - latestWifiFixTimeMs) <= WIFI_FRESH_TIMEOUT_MS) {
            return normalizeFloorToAvailable(latestWifiObservedFloor);
        }
        if (!availableFloorPlans.isEmpty()) {
            if (availableFloorPlans.containsKey(initialFloorHint)) {
                return initialFloorHint;
            }
            if (availableFloorPlans.containsKey(currentFloor)) {
                return currentFloor;
            }
            return normalizeFloorToAvailable(initialFloorHint);
        }
        return initialFloorHint;
    }

    // Mirrors the locally stored test points into the trajectory builder before upload.
    private synchronized void syncTestPointsIntoTrajectory() {
        if (this.trajectory == null) {
            return;
        }
        this.trajectory.clearTestPoints();
        if (!this.testPoints.isEmpty()) {
            this.trajectory.addAllTestPoints(this.testPoints);
        }
    }

    // Converts step-relative PDR output into the shared global XY frame used by fusion.
    private float[] alignRawPdrToGlobal(float[] rawPdrXY) {
        if (!isValidXY(rawPdrXY)) {
            return rawPdrXY;
        }
        float offsetX = (pdrAlignmentOffsetXY != null && pdrAlignmentOffsetXY.length >= 2)
                ? pdrAlignmentOffsetXY[0]
                : 0f;
        float offsetY = (pdrAlignmentOffsetXY != null && pdrAlignmentOffsetXY.length >= 2)
                ? pdrAlignmentOffsetXY[1]
                : 0f;
        return new float[]{rawPdrXY[0] + offsetX, rawPdrXY[1] + offsetY};
    }

    private synchronized void alignPdrToFusedAnchor() {
        if (!isValidXY(latestRawPdrXY)) {
            return;
        }
        float[] anchor = (lastFusedUpdateTimeMs > 0L && isValidXY(latestFusedXY))
                ? latestFusedXY
                : (isValidXY(latestWifiXY) ? latestWifiXY : null);
        alignPdrToAnchor(anchor, 1.0f);
    }

    private synchronized void alignPdrToAnchor(float[] anchor, float blend) {
        alignPdrToAnchor(anchor, blend, Float.POSITIVE_INFINITY);
    }

    private synchronized void alignPdrToAnchor(float[] anchor, float blend, float maxAdjustmentMeters) {
        if (!isValidXY(latestRawPdrXY) || !isValidXY(anchor)) {
            return;
        }
        float blendClamped = Math.max(0f, Math.min(1f, blend));
        float targetOffsetX = anchor[0] - latestRawPdrXY[0];
        float targetOffsetY = anchor[1] - latestRawPdrXY[1];
        float currentOffsetX = (pdrAlignmentOffsetXY != null && pdrAlignmentOffsetXY.length >= 2)
                ? pdrAlignmentOffsetXY[0]
                : targetOffsetX;
        float currentOffsetY = (pdrAlignmentOffsetXY != null && pdrAlignmentOffsetXY.length >= 2)
                ? pdrAlignmentOffsetXY[1]
                : targetOffsetY;
        float nextOffsetX = currentOffsetX + blendClamped * (targetOffsetX - currentOffsetX);
        float nextOffsetY = currentOffsetY + blendClamped * (targetOffsetY - currentOffsetY);

        if (!Float.isNaN(maxAdjustmentMeters)
                && !Float.isInfinite(maxAdjustmentMeters)
                && maxAdjustmentMeters > 0f) {
            float deltaX = nextOffsetX - currentOffsetX;
            float deltaY = nextOffsetY - currentOffsetY;
            float deltaNorm = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
            if (deltaNorm > maxAdjustmentMeters) {
                float scale = maxAdjustmentMeters / deltaNorm;
                nextOffsetX = currentOffsetX + deltaX * scale;
                nextOffsetY = currentOffsetY + deltaY * scale;
            }
        }

        pdrAlignmentOffsetXY = new float[]{nextOffsetX, nextOffsetY};
        latestPdrXY = alignRawPdrToGlobal(latestRawPdrXY);
    }

    private synchronized void applyWifiPositionCorrectionToPdr(float[] wifiMeasurementXY,
                                                               Integer measurementFloor,
                                                               long nowMs,
                                                               boolean weakWifiUpdate) {
        if (!saveRecording
                || !isValidXY(wifiMeasurementXY)
                || !isValidXY(latestRawPdrXY)
                || !isValidXY(latestPdrXY)) {
            return;
        }

        if (measurementFloor != null && normalizeFloorToAvailable(measurementFloor) != currentFloor) {
            return;
        }

        float[] constrainedWifiXY = constrainWifiCorrectionToMotion(wifiMeasurementXY, nowMs, weakWifiUpdate);
        if (!isValidXY(constrainedWifiXY)) {
            return;
        }

        float driftMeters = distanceMeters(latestPdrXY, constrainedWifiXY);
        float minCorrectionDriftMeters = weakWifiUpdate ? 0.50f : 0.22f;
        if (driftMeters < minCorrectionDriftMeters) {
            return;
        }

        float blend;
        float maxShiftMeters;
        if (driftMeters >= 8f) {
            blend = weakWifiUpdate ? 0.46f : 0.66f;
            maxShiftMeters = weakWifiUpdate ? 1.35f : 1.90f;
        } else if (driftMeters >= 4f) {
            blend = weakWifiUpdate ? 0.38f : 0.58f;
            maxShiftMeters = weakWifiUpdate ? 1.05f : 1.45f;
        } else if (driftMeters >= 1.5f) {
            blend = weakWifiUpdate ? 0.28f : 0.44f;
            maxShiftMeters = weakWifiUpdate ? 0.75f : 1.10f;
        } else {
            blend = weakWifiUpdate ? 0.16f : 0.32f;
            maxShiftMeters = weakWifiUpdate ? 0.40f : 0.65f;
        }

        alignPdrToAnchor(constrainedWifiXY, blend, maxShiftMeters);
        if (lastFusedUpdateTimeMs <= 0L || !isValidXY(latestFusedXY)) {
            updateTrajectoryDisplayPoint(latestPdrXY);
        }
    }

    private synchronized void maybePullPdrTowardsWifi(long nowMs) {
        if (!saveRecording
                || !isValidXY(latestPdrXY)
                || !isValidXY(latestRawPdrXY)
                || !isFreshSource(latestWifiXY, latestWifiFixTimeMs, nowMs, PDR_WIFI_PULL_FRESH_TIMEOUT_MS)
                || !isValidXY(latestWifiXY)) {
            return;
        }

        float driftMeters = distanceMeters(latestPdrXY, latestWifiXY);
        float driftThresholdMeters = latestWifiUsesCoarsePositioning
                ? PDR_WIFI_PULL_COARSE_DRIFT_METERS
                : (latestWifiQuality01 >= 0.55f ? 4.2f : 6.0f);
        if (driftMeters < driftThresholdMeters) {
            return;
        }

        boolean weakWifiUpdate = latestWifiUsesCoarsePositioning || latestWifiQuality01 < 0.45f;
        float[] correctionTargetXY = constrainWifiCorrectionToMotion(latestWifiXY, nowMs, weakWifiUpdate);
        if (!isValidXY(correctionTargetXY)) {
            correctionTargetXY = latestWifiXY;
        }

        float blend = latestWifiUsesCoarsePositioning
                ? 0.14f
                : (latestWifiQuality01 >= 0.55f ? 0.32f : 0.23f);
        float maxShiftMeters = latestWifiUsesCoarsePositioning
                ? 0.45f
                : (latestWifiQuality01 >= 0.55f ? 1.05f : 0.78f);
        alignPdrToAnchor(correctionTargetXY, blend, maxShiftMeters);
        if (lastFusedUpdateTimeMs <= 0L || !isValidXY(latestFusedXY)) {
            updateTrajectoryDisplayPoint(latestPdrXY);
        }
    }

    // Holds the result of one wall-aware step correction pass.
    private static final class StepWallGuidanceResult {
        final float[] correctedXY;
        final float headingRad;
        final boolean corrected;
        final float correctionMeters;

        StepWallGuidanceResult(float[] correctedXY, float headingRad, boolean corrected, float correctionMeters) {
            this.correctedXY = correctedXY;
            this.headingRad = headingRad;
            this.corrected = corrected;
            this.correctionMeters = correctionMeters;
        }
    }

    // Pushes a predicted step back onto legal walkable space before fusion consumes it.
    private synchronized StepWallGuidanceResult applyWallGuidanceToStep(float[] previousPdr,
                                                                        float[] alignedPdr,
                                                                        float sensorHeadingRad,
                                                                        long nowMs) {
        if (!isValidXY(previousPdr) || !isValidXY(alignedPdr) || mapMatchingEngine == null) {
            return new StepWallGuidanceResult(alignedPdr, sensorHeadingRad, false, 0f);
        }

        com.openpositioning.PositionMe.data.remote.FloorPlan activeFloorPlan = resolveFloorPlan(currentFloor);
        if (activeFloorPlan == null) {
            return new StepWallGuidanceResult(alignedPdr, sensorHeadingRad, false, 0f);
        }

        float rawStepMeters = distanceMeters(previousPdr, alignedPdr);
        if (rawStepMeters < WALL_GUIDANCE_MIN_STEP_METERS) {
            return new StepWallGuidanceResult(alignedPdr, sensorHeadingRad, false, 0f);
        }

        float[] baselineCorrectedXY = mapMatchingEngine.correctMovementAgainstWalls(
                previousPdr,
                alignedPdr,
                activeFloorPlan,
                WALL_GUIDANCE_MARGIN_METERS
        );
        if (!isValidXY(baselineCorrectedXY)) {
            return new StepWallGuidanceResult(alignedPdr, sensorHeadingRad, false, 0f);
        }

        boolean wallCollision = !mapMatchingEngine.isMovementValid(previousPdr, alignedPdr, activeFloorPlan);
        float baselineCorrectionMeters = distanceMeters(alignedPdr, baselineCorrectedXY);
        if (!wallCollision && baselineCorrectionMeters < WALL_GUIDANCE_MIN_CORRECTION_METERS) {
            return new StepWallGuidanceResult(alignedPdr, sensorHeadingRad, false, 0f);
        }

        float rawMoveHeadingRad = normalizeAngleRad(
                (float) Math.atan2(alignedPdr[0] - previousPdr[0], alignedPdr[1] - previousPdr[1])
        );
        float intendedHeadingRad = resolveMotionIntentHeadingRad(rawMoveHeadingRad, sensorHeadingRad, nowMs);
        float[] intentCandidateXY = new float[]{
                previousPdr[0] + rawStepMeters * (float) Math.sin(intendedHeadingRad),
                previousPdr[1] + rawStepMeters * (float) Math.cos(intendedHeadingRad)
        };
        float[] intentCorrectedXY = mapMatchingEngine.correctMovementAgainstWalls(
                previousPdr,
                intentCandidateXY,
                activeFloorPlan,
                WALL_GUIDANCE_MARGIN_METERS
        );

        float[] correctedXY = baselineCorrectedXY;
        if (isValidXY(intentCorrectedXY)) {
            float baselineScore = scoreWallGuidanceCandidate(
                    previousPdr,
                    baselineCorrectedXY,
                    intendedHeadingRad,
                    rawStepMeters,
                    nowMs
            );
            float intentScore = scoreWallGuidanceCandidate(
                    previousPdr,
                    intentCorrectedXY,
                    intendedHeadingRad,
                    rawStepMeters,
                    nowMs
            );
            if (intentScore > baselineScore + 0.02f
                    || distanceMeters(previousPdr, intentCorrectedXY)
                    > distanceMeters(previousPdr, baselineCorrectedXY) + WALL_GUIDANCE_COLLISION_INTENT_MIN_GAIN_METERS) {
                correctedXY = intentCorrectedXY;
            }
        }

        float correctionMeters = distanceMeters(alignedPdr, correctedXY);
        boolean wallCorrected = wallCollision || correctionMeters >= WALL_GUIDANCE_MIN_CORRECTION_METERS;
        if (!wallCorrected) {
            return new StepWallGuidanceResult(alignedPdr, sensorHeadingRad, false, 0f);
        }

        float correctedHeadingRad = sensorHeadingRad;
        float correctedStepMeters = distanceMeters(previousPdr, correctedXY);
        if (correctedStepMeters >= WALL_GUIDANCE_MIN_STEP_METERS) {
            float pathHeadingRad = normalizeAngleRad(
                    (float) Math.atan2(correctedXY[0] - previousPdr[0], correctedXY[1] - previousPdr[1])
            );
            correctedHeadingRad = blendAnglesRad(intendedHeadingRad, pathHeadingRad, WALL_GUIDANCE_HEADING_BLEND);
            registerWallAlignedHeading(correctedHeadingRad, nowMs);
        }

        return new StepWallGuidanceResult(correctedXY, correctedHeadingRad, true, correctionMeters);
    }

    private float scoreWallGuidanceCandidate(float[] previousPdr,
                                             float[] candidateXY,
                                             float intendedHeadingRad,
                                             float targetStepMeters,
                                             long nowMs) {
        if (!isValidXY(previousPdr) || !isValidXY(candidateXY)) {
            return Float.NEGATIVE_INFINITY;
        }
        float candidateStepMeters = distanceMeters(previousPdr, candidateXY);
        if (candidateStepMeters < WALL_GUIDANCE_MIN_STEP_METERS) {
            return Float.NEGATIVE_INFINITY;
        }

        float candidateHeadingRad = normalizeAngleRad(
                (float) Math.atan2(candidateXY[0] - previousPdr[0], candidateXY[1] - previousPdr[1])
        );
        float headingDelta = Math.abs(normalizeAngleRad(candidateHeadingRad - intendedHeadingRad));
        float headingScore = 1f - clamp01(headingDelta / (float) Math.PI);
        float progressScore = clamp01(candidateStepMeters / Math.max(targetStepMeters, WALL_GUIDANCE_MIN_STEP_METERS));

        float wifiScore = 0.55f;
        if (isFreshSource(latestWifiXY, latestWifiFixTimeMs, nowMs, PDR_WIFI_PULL_FRESH_TIMEOUT_MS)
                && isValidXY(latestWifiXY)) {
            float wifiDistance = distanceMeters(candidateXY, latestWifiXY);
            wifiScore = 1f - clamp01(wifiDistance / 8f);
        }
        return 0.58f * headingScore + 0.27f * progressScore + 0.15f * wifiScore;
    }

    private void updateWallGuidanceState(boolean corrected, long nowMs) {
        if (lastWallGuidanceTimeMs > 0L && (nowMs - lastWallGuidanceTimeMs) > WALL_GUIDANCE_RESET_TIMEOUT_MS) {
            consecutiveWallGuidedSteps = 0;
        }
        if (corrected) {
            consecutiveWallGuidedSteps++;
            lastWallGuidanceTimeMs = nowMs;
        } else {
            consecutiveWallGuidedSteps = 0;
            lastWallGuidanceTimeMs = 0L;
        }
    }

    private synchronized float[] maybeRecoverFromWallLock(float[] previousPdr,
                                                          float[] originalAlignedPdr,
                                                          float[] wallGuidedXY,
                                                          long nowMs) {
        if (consecutiveWallGuidedSteps < WALL_LOCK_RECOVERY_REQUIRED_STEPS
                || !isValidXY(previousPdr)
                || !isValidXY(originalAlignedPdr)
                || !isValidXY(wallGuidedXY)
                || !isFreshSource(latestWifiXY, latestWifiFixTimeMs, nowMs, WIFI_FRESH_TIMEOUT_MS)
                || !isValidXY(latestWifiXY)
                || isLikelyStationary(nowMs)) {
            return wallGuidedXY;
        }

        float guidedToWifi = distanceMeters(wallGuidedXY, latestWifiXY);
        float originalToWifi = distanceMeters(originalAlignedPdr, latestWifiXY);
        if (guidedToWifi >= originalToWifi - WALL_LOCK_RECOVERY_MIN_WIFI_GAIN_METERS) {
            return wallGuidedXY;
        }

        float[] recoveryCandidate = new float[]{
                wallGuidedXY[0] * (1f - WALL_LOCK_RECOVERY_BLEND) + latestWifiXY[0] * WALL_LOCK_RECOVERY_BLEND,
                wallGuidedXY[1] * (1f - WALL_LOCK_RECOVERY_BLEND) + latestWifiXY[1] * WALL_LOCK_RECOVERY_BLEND
        };
        float recoveryShift = distanceMeters(wallGuidedXY, recoveryCandidate);
        if (recoveryShift > WALL_LOCK_RECOVERY_MAX_SHIFT_METERS && recoveryShift > 1e-5f) {
            float scale = WALL_LOCK_RECOVERY_MAX_SHIFT_METERS / recoveryShift;
            recoveryCandidate[0] = wallGuidedXY[0] + (recoveryCandidate[0] - wallGuidedXY[0]) * scale;
            recoveryCandidate[1] = wallGuidedXY[1] + (recoveryCandidate[1] - wallGuidedXY[1]) * scale;
        }

        com.openpositioning.PositionMe.data.remote.FloorPlan activeFloorPlan = resolveFloorPlan(currentFloor);
        if (mapMatchingEngine != null && activeFloorPlan != null) {
            recoveryCandidate = mapMatchingEngine.correctMovementAgainstWalls(
                    previousPdr,
                    recoveryCandidate,
                    activeFloorPlan,
                    WALL_GUIDANCE_MARGIN_METERS
            );
        }
        if (!isValidXY(recoveryCandidate)) {
            return wallGuidedXY;
        }
        return distanceMeters(recoveryCandidate, latestWifiXY) + 0.15f < guidedToWifi
                ? recoveryCandidate
                : wallGuidedXY;
    }

    private synchronized float[] constrainToCurrentFloorWalls(float[] candidateXY, float[] preferredReferenceXY) {
        if (!isValidXY(candidateXY) || mapMatchingEngine == null) {
            return candidateXY;
        }

        com.openpositioning.PositionMe.data.remote.FloorPlan activeFloorPlan = resolveFloorPlan(currentFloor);
        if (activeFloorPlan == null) {
            return candidateXY;
        }

        float[] referenceXY = isValidXY(preferredReferenceXY) ? preferredReferenceXY : null;
        if (!isValidXY(referenceXY) && isValidXY(lastCords)) {
            referenceXY = new float[]{lastCords[0], lastCords[1]};
        }

        if (isValidXY(referenceXY) && distanceMeters(referenceXY, candidateXY) >= 1e-4f) {
            float[] correctedXY = mapMatchingEngine.correctMovementAgainstWalls(
                    referenceXY,
                    candidateXY,
                    activeFloorPlan,
                    WALL_GUIDANCE_MARGIN_METERS
            );
            if (isValidXY(correctedXY)) {
                return correctedXY;
            }
        }
        return candidateXY;
    }

    private synchronized void applyRelativeStepPrediction(float[] alignedPdr,
                                                          float[] previousPdr,
                                                          float headingRad,
                                                          List<Double> accelWindow,
                                                          long stepIntervalMs,
                                                          long nowMs) {
        ensureParticleFilterInitialized();
        if (particleFilter != null
                && particleFilter.isInitialized()
                && isValidXY(previousPdr)
                && isValidXY(alignedPdr)) {
            float stepLen = distanceMeters(alignedPdr, previousPdr);
            if (stepLen > 0.01f) {
                MotionPredictionProfile predictionProfile = buildMotionPredictionProfile(
                        Math.min(stepLen, MAX_TRAJECTORY_STEP_METERS),
                        headingRad,
                        accelWindow,
                        stepIntervalMs,
                        nowMs
                );
                particleFilter.predict(
                        predictionProfile.stepMeters,
                        predictionProfile.headingRad,
                        getFloorPlanMapForFilter(),
                        mapMatchingEngine,
                        predictionProfile.confidence01
                );
            }
        }
        syncFusedEstimateFromParticleFilter(nowMs);
    }

    // Stores the floor-constrained trajectory point used by live map display and persistence.
    private synchronized void updateTrajectoryDisplayPoint(float[] trajectoryXY) {
        if (!isValidXY(trajectoryXY)) {
            return;
        }
        float[] previousDisplayXY = isValidXY(lastCords) ? new float[]{lastCords[0], lastCords[1]} : null;
        float[] displayXY = constrainToCurrentFloorWalls(trajectoryXY, previousDisplayXY);
        lastCords = isValidXY(displayXY)
                ? new float[]{displayXY[0], displayXY[1]}
                : new float[]{trajectoryXY[0], trajectoryXY[1]};
        lastDisplayTrajectoryUpdateMs = System.currentTimeMillis();
    }

    private synchronized float[] currentTrajectoryXYForPersistence() {
        if (isValidXY(lastCords)) {
            return new float[]{lastCords[0], lastCords[1]};
        }
        if (lastFusedUpdateTimeMs > 0L && isValidXY(latestFusedXY)) {
            return new float[]{latestFusedXY[0], latestFusedXY[1]};
        }
        if (isValidXY(latestPdrXY)) {
            return new float[]{latestPdrXY[0], latestPdrXY[1]};
        }
        if (isValidXY(latestRawPdrXY)) {
            return new float[]{latestRawPdrXY[0], latestRawPdrXY[1]};
        }
        return new float[]{0f, 0f};
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
        ensureSensorCallbackThread();

        // Initialise data collection devices (unchanged)...
        this.accelerometerSensor = new MovementSensor(appContext, Sensor.TYPE_ACCELEROMETER);
        this.barometerSensor = new MovementSensor(appContext, Sensor.TYPE_PRESSURE);
        this.gyroscopeSensor = new MovementSensor(appContext, Sensor.TYPE_GYROSCOPE);
        this.lightSensor = new MovementSensor(appContext, Sensor.TYPE_LIGHT);
        this.proximitySensor = new MovementSensor(appContext, Sensor.TYPE_PROXIMITY);
        this.magnetometerSensor = new MovementSensor(appContext, Sensor.TYPE_MAGNETIC_FIELD);
        this.stepDetectionSensor = new MovementSensor(appContext, Sensor.TYPE_STEP_DETECTOR);
        this.rotationSensor = new MovementSensor(appContext, Sensor.TYPE_ROTATION_VECTOR);
        this.gameRotationSensor = new MovementSensor(appContext, Sensor.TYPE_GAME_ROTATION_VECTOR);
        this.gravitySensor = new MovementSensor(appContext, Sensor.TYPE_GRAVITY);
        this.linearAccelerationSensor = new MovementSensor(appContext, Sensor.TYPE_LINEAR_ACCELERATION);

        this.wifiProcessor = new WifiDataProcessor(appContext);
        wifiProcessor.registerObserver(this);
        this.gnssProcessor = new GNSSDataProcessor(appContext, locationListener);
        this.serverCommunications = new ServerCommunications(appContext);

        // Do not reset recording time bases here; they are set in startRecording().
        // Resetting during recording can invalidate relative timestamps.
        if (!saveRecording) {
            this.absoluteStartTime = System.currentTimeMillis();
            this.bootTime = SystemClock.uptimeMillis();
        }

        // Initialise saveRecording to false
        this.saveRecording = false;

        // Other initialisations...
        this.accelMagnitude = new ArrayList<>();
        this.recentLinearAccelerationMagnitudes = new CircularFloatBuffer(STATIONARY_ACCEL_WINDOW_SAMPLES);
        this.pdrProcessing = new PdrProcessing(context);
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.pathView = new PathView(context, null);
        this.wiFiPositioning = new WiFiPositioning(context);

        this.filter_coefficient = resolveConfiguredFilterCoefficient();

        // Keep app awake during the recording (using stored appContext)
        PowerManager powerManager = (PowerManager) this.appContext.getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PositionMe::SensorWakeLock");
        }
    }

    private synchronized void ensureSensorCallbackThread() {
        if (motionSensorThread == null || !motionSensorThread.isAlive() || motionSensorHandler == null) {
            motionSensorThread = new HandlerThread(
                    "PositionMeMotionSensors",
                    android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE
            );
            motionSensorThread.start();
            motionSensorHandler = new Handler(motionSensorThread.getLooper());
        }
        if (sensorCallbackThread == null || !sensorCallbackThread.isAlive() || sensorCallbackHandler == null) {
            sensorCallbackThread = new HandlerThread(
                    "PositionMeAuxSensors",
                    android.os.Process.THREAD_PRIORITY_DEFAULT
            );
            sensorCallbackThread.start();
            sensorCallbackHandler = new Handler(sensorCallbackThread.getLooper());
        }
    }

    private float resolveConfiguredFilterCoefficient() {
        if (settings == null || !settings.getBoolean("overwrite_constants", false)) {
            return FILTER_COEFFICIENT;
        }
        try {
            String value = settings.getString("accel_filter", Float.toString(FILTER_COEFFICIENT));
            float parsed = Float.parseFloat(value);
            return (parsed >= 0f && parsed <= 1f) ? parsed : FILTER_COEFFICIENT;
        } catch (RuntimeException e) {
            return FILTER_COEFFICIENT;
        }
    }

    public void setPathView(PathView view) {
        this.pathView = view;
    }

    private void postPathViewTrajectory(@NonNull float[] trajectoryXY) {
        if (pathView == null) {
            return;
        }
        final float[] pointCopy = new float[]{trajectoryXY[0], trajectoryXY[1]};
        mainThreadHandler.post(() -> {
            if (pathView == null) {
                return;
            }
            pathView.drawTrajectory(pointCopy);
            pathView.postInvalidate();
        });
    }

    private void clearPathViewOnMainThread() {
        if (pathView == null) {
            return;
        }
        mainThreadHandler.post(() -> {
            if (pathView == null) {
                return;
            }
            pathView.clearTrajectory();
            pathView.postInvalidate(); // was postInvalidateOnAnimation() — consistent with trajectory update
        });
    }

    private void updateHeadingFromRotationVector(@NonNull float[] rotationVectorValues) {
        long nowMs = System.currentTimeMillis();
        this.rotation = rotationVectorValues.clone();
        float[] rotationVectorDCM = new float[9];
        SensorManager.getRotationMatrixFromVector(rotationVectorDCM, this.rotation);
        float[] rotationOrientation = new float[3];
        // Use the world-frame rotation matrix directly so heading is not skewed by UI rotation.
        SensorManager.getOrientation(rotationVectorDCM, rotationOrientation);

        float trueNorthHeading = normalizeAngleRad(rotationOrientation[0] + resolveGeomagneticDeclinationRad());
        if (!headingInitialized || Float.isNaN(orientation[0]) || Float.isInfinite(orientation[0])) {
            orientation[0] = trueNorthHeading;
            headingInitialized = true;
        } else {
            orientation[0] = blendAnglesRad(orientation[0], trueNorthHeading, HEADING_SMOOTH_ALPHA);
        }
        orientation[1] = rotationOrientation[1];
        orientation[2] = rotationOrientation[2];
        lastHeadingSensorUpdateMs = nowMs;
        refreshMagneticDisturbanceState(nowMs);
        if (!magneticDisturbanceActive) {
            maybeUpdateGameHeadingAlignment(nowMs);
        }
    }

    private void updateHeadingFromGameRotationVector(@NonNull float[] rotationVectorValues) {
        long nowMs = System.currentTimeMillis();
        this.gameRotation = rotationVectorValues.clone();
        float[] rotationVectorDCM = new float[9];
        SensorManager.getRotationMatrixFromVector(rotationVectorDCM, this.gameRotation);
        float[] gameOrientation = new float[3];
        SensorManager.getOrientation(rotationVectorDCM, gameOrientation);

        float relativeHeading = normalizeAngleRad(gameOrientation[0]);
        if (Float.isNaN(filteredGameHeadingRad) || Float.isInfinite(filteredGameHeadingRad)) {
            filteredGameHeadingRad = relativeHeading;
        } else {
            filteredGameHeadingRad = blendAnglesRad(
                    filteredGameHeadingRad,
                    relativeHeading,
                    GAME_HEADING_SMOOTH_ALPHA
            );
        }
        lastGameHeadingSensorUpdateMs = nowMs;
        refreshMagneticDisturbanceState(nowMs);
        if (!magneticDisturbanceActive) {
            maybeUpdateGameHeadingAlignment(nowMs);
        }
    }

    private float[] resolveMotionRotationMatrix() {
        if (rotation != null && rotation.length >= 3) {
            try {
                float[] rotationVectorDCM = new float[9];
                SensorManager.getRotationMatrixFromVector(rotationVectorDCM, rotation);
                return rotationVectorDCM;
            } catch (RuntimeException ignored) {
                // Fall through to orientation-based reconstruction.
            }
        }
        if (!headingInitialized
                || orientation == null
                || orientation.length < 3
                || Float.isNaN(orientation[0])
                || Float.isInfinite(orientation[0])) {
            return null;
        }
        try {
            return getRotationMatrixFromOrientation(new float[]{
                    orientation[0],
                    orientation[1],
                    orientation[2]
            });
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void updateHorizontalAccelerationHeading(@NonNull float[] linearAccelerationValues, long nowMs) {
        float[] motionRotationMatrix = resolveMotionRotationMatrix();
        if (motionRotationMatrix == null || motionRotationMatrix.length < 9) {
            return;
        }

        float worldX = motionRotationMatrix[0] * linearAccelerationValues[0]
                + motionRotationMatrix[1] * linearAccelerationValues[1]
                + motionRotationMatrix[2] * linearAccelerationValues[2];
        float worldY = motionRotationMatrix[3] * linearAccelerationValues[0]
                + motionRotationMatrix[4] * linearAccelerationValues[1]
                + motionRotationMatrix[5] * linearAccelerationValues[2];
        float horizontalMagnitude = (float) Math.sqrt(worldX * worldX + worldY * worldY);
        latestHorizontalAccelerationMagnitude = horizontalMagnitude;
        if (horizontalMagnitude < HORIZONTAL_ACCEL_HEADING_MIN_MAGNITUDE_MPS2) {
            return;
        }

        float accelerationHeadingRad = normalizeAngleRad((float) Math.atan2(worldX, worldY));
        if (Float.isNaN(latestHorizontalAccelerationHeadingRad)
                || Float.isInfinite(latestHorizontalAccelerationHeadingRad)) {
            latestHorizontalAccelerationHeadingRad = accelerationHeadingRad;
        } else {
            latestHorizontalAccelerationHeadingRad = blendAnglesRad(
                    latestHorizontalAccelerationHeadingRad,
                    accelerationHeadingRad,
                    HORIZONTAL_ACCEL_HEADING_BLEND
            );
        }
        lastHorizontalAccelerationHeadingTimeMs = nowMs;
    }

    private GeomagneticField buildGeomagneticField() {
        float latitudeForDeclination = Float.NaN;
        float longitudeForDeclination = Float.NaN;
        float altitudeForDeclination = 0f;

        if (latestGnssLatLng != null) {
            latitudeForDeclination = (float) latestGnssLatLng.latitude;
            longitudeForDeclination = (float) latestGnssLatLng.longitude;
            altitudeForDeclination = Float.isNaN(elevation) ? 0f : elevation;
        } else if (startLocation != null
                && startLocation.length >= 2
                && (startLocation[0] != 0f || startLocation[1] != 0f)) {
            latitudeForDeclination = startLocation[0];
            longitudeForDeclination = startLocation[1];
        }

        if (Float.isNaN(latitudeForDeclination) || Float.isNaN(longitudeForDeclination)) {
            return null;
        }

        try {
            return new GeomagneticField(
                    latitudeForDeclination,
                    longitudeForDeclination,
                    altitudeForDeclination,
                    System.currentTimeMillis()
            );
        } catch (RuntimeException e) {
            return null;
        }
    }

    private float resolveGeomagneticDeclinationRad() {
        GeomagneticField geomagneticField = buildGeomagneticField();
        if (geomagneticField == null) {
            return 0f;
        }
        return (float) Math.toRadians(geomagneticField.getDeclination());
    }

    private float resolveExpectedGeomagneticFieldStrengthUt() {
        GeomagneticField geomagneticField = buildGeomagneticField();
        if (geomagneticField == null) {
            return Float.NaN;
        }
        return geomagneticField.getFieldStrength() / 1000f;
    }

    private float blendAnglesRad(float currentAngleRad, float targetAngleRad, float alpha) {
        if (Float.isNaN(currentAngleRad) || Float.isInfinite(currentAngleRad)) {
            return normalizeAngleRad(targetAngleRad);
        }
        float safeAlpha = Math.max(0f, Math.min(1f, alpha));
        float delta = normalizeAngleRad(targetAngleRad - currentAngleRad);
        return normalizeAngleRad(currentAngleRad + safeAlpha * delta);
    }

    private void updateMotionHeadingFromTrajectory(float[] previousXY, float[] currentXY, long nowMs) {
        if (!isValidXY(previousXY) || !isValidXY(currentXY)) {
            return;
        }
        if (lastFloorSwitchTimeMs > 0L && (nowMs - lastFloorSwitchTimeMs) <= FLOOR_SWITCH_HEADING_FREEZE_MS) {
            return;
        }
        float distance = distanceMeters(previousXY, currentXY);
        if (distance < FUSED_MOTION_HEADING_MIN_DISTANCE_METERS) {
            return;
        }
        if (distance > FUSED_MOTION_HEADING_MAX_DISTANCE_METERS) {
            return;
        }
        if (!hasMotionEvidenceForHeadingUpdate(nowMs)) {
            return;
        }
        latestMotionHeadingRad = normalizeAngleRad(
                (float) Math.atan2(currentXY[0] - previousXY[0], currentXY[1] - previousXY[1])
        );
        lastMotionHeadingTimeMs = nowMs;
    }

    private static final class MotionPredictionProfile {
        final float stepMeters;
        final float headingRad;
        final float confidence01;

        MotionPredictionProfile(float stepMeters, float headingRad, float confidence01) {
            this.stepMeters = stepMeters;
            this.headingRad = headingRad;
            this.confidence01 = confidence01;
        }
    }

    private MotionPredictionProfile buildMotionPredictionProfile(float rawStepMeters,
                                                                 float sensorHeadingRad,
                                                                 List<Double> accelWindow,
                                                                 long stepIntervalMs,
                                                                 long nowMs) {
        float safeStepMeters = Math.max(0f, rawStepMeters);
        float stepSpeedMps = Float.NaN;
        if (stepIntervalMs >= 220L && stepIntervalMs <= 1800L && safeStepMeters > 0.05f) {
            stepSpeedMps = safeStepMeters / (stepIntervalMs / 1000f);
        }

        float speedReferenceMps = stepSpeedMps;
        if (Float.isNaN(speedReferenceMps) || Float.isInfinite(speedReferenceMps) || speedReferenceMps <= 0f) {
            speedReferenceMps = safeStepMeters > 0.01f ? safeStepMeters / 0.65f : 0.8f;
        }
        speedReferenceMps = Math.max(0f, Math.min(3.2f, speedReferenceMps));

        float peakAccelMps2 = computePeakAccelerationMagnitude(accelWindow);
        float speedConfidence = clamp01(
                (speedReferenceMps - MOTION_PREDICTION_MIN_SPEED_MPS)
                        / (MOTION_PREDICTION_MAX_SPEED_MPS - MOTION_PREDICTION_MIN_SPEED_MPS)
        );
        float accelConfidence = clamp01(
                (peakAccelMps2 - MOTION_PREDICTION_MIN_ACCEL_MPS2)
                        / (MOTION_PREDICTION_MAX_ACCEL_MPS2 - MOTION_PREDICTION_MIN_ACCEL_MPS2)
        );
        float predictionConfidence = clamp01(0.20f + 0.45f * speedConfidence + 0.35f * accelConfidence);

        float predictedHeadingRad = sensorHeadingRad;

        float speedStepScale = 0.94f + 0.12f * speedConfidence;
        float accelStepScale = 0.96f + 0.08f * accelConfidence;
        float predictedStepMeters = safeStepMeters * (0.55f * speedStepScale + 0.45f * accelStepScale);
        if (stepIntervalMs > 0L && stepIntervalMs < 350L) {
            predictedStepMeters *= 1.05f;
        } else if (stepIntervalMs > 1300L) {
            predictedStepMeters *= 0.96f;
        }
        predictedStepMeters = Math.max(0.05f, Math.min(MAX_TRAJECTORY_STEP_METERS, predictedStepMeters));

        return new MotionPredictionProfile(
                predictedStepMeters,
                predictedHeadingRad,
                predictionConfidence
        );
    }

    private boolean isGnssSpeedFreshForPrediction(long nowMs) {
        return latestGnssFixTimeMs > 0L
                && (nowMs - latestGnssFixTimeMs) <= GNSS_FRESH_TIMEOUT_MS
                && !Float.isNaN(latestGnssSpeedMps)
                && !Float.isInfinite(latestGnssSpeedMps)
                && latestGnssSpeedMps > 0f
                && isGnssEligibleForFusion(latestGnssAccuracyMeters);
    }

    private boolean hasMotionEvidenceForHeadingUpdate(long nowMs) {
        boolean recentStep = lastStepTime > 0L
                && (nowMs - lastStepTime) <= DISPLAY_HEADING_ACTIVE_STEP_TIMEOUT_MS;
        return recentStep;
    }

    private void invalidateHeadingState() {
        latestMotionHeadingRad = Float.NaN;
        lastMotionHeadingTimeMs = 0L;
        latestHorizontalAccelerationHeadingRad = Float.NaN;
        latestHorizontalAccelerationMagnitude = 0f;
        lastHorizontalAccelerationHeadingTimeMs = 0L;
        latestWallAlignedHeadingRad = Float.NaN;
        lastWallAlignedHeadingTimeMs = 0L;
        lastHeadingSensorUpdateMs = 0L;
        lastGameHeadingSensorUpdateMs = 0L;
        headingInitialized = false;
        filteredGameHeadingRad = Float.NaN;
        gameHeadingAlignmentOffsetRad = Float.NaN;
        latestMagneticFieldStrengthUt = Float.NaN;
        lastMagneticFieldUpdateMs = 0L;
        lastMagneticDisturbanceTimeMs = 0L;
        magneticDisturbanceActive = false;
        if (orientation != null && orientation.length >= 3) {
            orientation[0] = Float.NaN;
            orientation[1] = 0f;
            orientation[2] = 0f;
        }
    }

    private void updateMagneticFieldStrength(@NonNull float[] magneticFieldValues, long nowMs) {
        latestMagneticFieldStrengthUt = computeVectorMagnitude(magneticFieldValues);
        lastMagneticFieldUpdateMs = nowMs;
        refreshMagneticDisturbanceState(nowMs);
    }

    private float computeVectorMagnitude(@NonNull float[] vector) {
        if (vector.length < 3) {
            return Float.NaN;
        }
        return (float) Math.sqrt(
                vector[0] * vector[0]
                        + vector[1] * vector[1]
                        + vector[2] * vector[2]
        );
    }

    private boolean isAbsoluteHeadingFresh(long nowMs) {
        return headingInitialized
                && lastHeadingSensorUpdateMs > 0L
                && (nowMs - lastHeadingSensorUpdateMs) <= HEADING_SENSOR_FRESH_TIMEOUT_MS;
    }

    private boolean isGameHeadingFresh(long nowMs) {
        return !Float.isNaN(filteredGameHeadingRad)
                && !Float.isInfinite(filteredGameHeadingRad)
                && lastGameHeadingSensorUpdateMs > 0L
                && (nowMs - lastGameHeadingSensorUpdateMs) <= GAME_HEADING_FRESH_TIMEOUT_MS;
    }

    private float getGameHeadingRadOrNaN() {
        if (Float.isNaN(filteredGameHeadingRad) || Float.isInfinite(filteredGameHeadingRad)) {
            return Float.NaN;
        }
        return normalizeAngleRad(filteredGameHeadingRad);
    }

    private float getAlignedGameHeadingRadOrNaN() {
        float gameHeading = getGameHeadingRadOrNaN();
        if (Float.isNaN(gameHeading) || Float.isInfinite(gameHeading)) {
            return Float.NaN;
        }
        if (Float.isNaN(gameHeadingAlignmentOffsetRad) || Float.isInfinite(gameHeadingAlignmentOffsetRad)) {
            return Float.NaN;
        }
        return normalizeAngleRad(gameHeading + gameHeadingAlignmentOffsetRad);
    }

    private void maybeUpdateGameHeadingAlignment(long nowMs) {
        if (!isAbsoluteHeadingFresh(nowMs) || !isGameHeadingFresh(nowMs)) {
            return;
        }
        if (isMagneticFieldStrengthDisturbed(nowMs)) {
            return;
        }
        float absoluteHeading = getSensorHeadingRadOrNaN();
        float gameHeading = getGameHeadingRadOrNaN();
        if (Float.isNaN(absoluteHeading) || Float.isInfinite(absoluteHeading)
                || Float.isNaN(gameHeading) || Float.isInfinite(gameHeading)) {
            return;
        }
        float targetOffset = normalizeAngleRad(absoluteHeading - gameHeading);
        if (Float.isNaN(gameHeadingAlignmentOffsetRad) || Float.isInfinite(gameHeadingAlignmentOffsetRad)) {
            gameHeadingAlignmentOffsetRad = targetOffset;
        } else {
            gameHeadingAlignmentOffsetRad = blendAnglesRad(
                    gameHeadingAlignmentOffsetRad,
                    targetOffset,
                    GAME_HEADING_ALIGNMENT_ALPHA
            );
        }
    }

    private boolean isMagneticFieldStrengthDisturbed(long nowMs) {
        if (lastMagneticFieldUpdateMs <= 0L
                || (nowMs - lastMagneticFieldUpdateMs) > MAGNETIC_FIELD_FRESH_TIMEOUT_MS
                || Float.isNaN(latestMagneticFieldStrengthUt)
                || Float.isInfinite(latestMagneticFieldStrengthUt)) {
            return false;
        }
        float expectedFieldStrengthUt = resolveExpectedGeomagneticFieldStrengthUt();
        if (Float.isNaN(expectedFieldStrengthUt)
                || Float.isInfinite(expectedFieldStrengthUt)
                || expectedFieldStrengthUt < 20f) {
            return false;
        }
        float deltaUt = Math.abs(latestMagneticFieldStrengthUt - expectedFieldStrengthUt);
        float ratio = deltaUt / Math.max(expectedFieldStrengthUt, 1f);
        return deltaUt >= MAGNETIC_FIELD_STRENGTH_DELTA_UT
                && ratio >= MAGNETIC_FIELD_STRENGTH_RATIO_DELTA;
    }

    private boolean isHeadingConsistencyDisturbed(long nowMs) {
        if (!isAbsoluteHeadingFresh(nowMs) || !isGameHeadingFresh(nowMs)) {
            return false;
        }
        if (!hasActiveMotionForDisplayHeading(nowMs)) {
            return false;
        }
        float absoluteHeading = getSensorHeadingRadOrNaN();
        float alignedGameHeading = getAlignedGameHeadingRadOrNaN();
        if (Float.isNaN(absoluteHeading) || Float.isInfinite(absoluteHeading)
                || Float.isNaN(alignedGameHeading) || Float.isInfinite(alignedGameHeading)) {
            return false;
        }
        float disagreementRad = Math.abs(normalizeAngleRad(absoluteHeading - alignedGameHeading));
        return disagreementRad >= MAGNETIC_HEADING_DISAGREEMENT_RAD;
    }

    private void refreshMagneticDisturbanceState(long nowMs) {
        boolean anomalyDetected = isMagneticFieldStrengthDisturbed(nowMs)
                || isHeadingConsistencyDisturbed(nowMs);
        boolean previousState = magneticDisturbanceActive;
        if (anomalyDetected) {
            magneticDisturbanceActive = true;
            lastMagneticDisturbanceTimeMs = nowMs;
        } else if (magneticDisturbanceActive
                && lastMagneticDisturbanceTimeMs > 0L
                && (nowMs - lastMagneticDisturbanceTimeMs) <= MAGNETIC_DISTURBANCE_HOLD_MS) {
            magneticDisturbanceActive = true;
        } else {
            magneticDisturbanceActive = false;
        }
        if (previousState != magneticDisturbanceActive) {
            Log.i(
                    "SensorFusion",
                    magneticDisturbanceActive
                            ? "Magnetic disturbance detected, switching to game-rotation heading."
                            : "Magnetic disturbance cleared, restoring absolute heading."
            );
        }
    }

    private float getPreferredSensorHeadingRad(long nowMs) {
        float absoluteHeading = getSensorHeadingRadOrNaN();
        float alignedGameHeading = getAlignedGameHeadingRadOrNaN();
        boolean absoluteFresh = isAbsoluteHeadingFresh(nowMs);
        boolean gameFresh = isGameHeadingFresh(nowMs)
                && !Float.isNaN(alignedGameHeading)
                && !Float.isInfinite(alignedGameHeading);

        if (magneticDisturbanceActive) {
            return gameFresh ? alignedGameHeading : Float.NaN;
        }
        if (absoluteFresh) {
            return absoluteHeading;
        }
        if (gameFresh) {
            return alignedGameHeading;
        }
        if (!Float.isNaN(absoluteHeading) && !Float.isInfinite(absoluteHeading)) {
            return absoluteHeading;
        }
        if (!Float.isNaN(alignedGameHeading) && !Float.isInfinite(alignedGameHeading)) {
            return alignedGameHeading;
        }
        return Float.NaN;
    }

    private float resolveFreshHorizontalAccelerationHeadingRad(long nowMs) {
        if (Float.isNaN(latestHorizontalAccelerationHeadingRad)
                || Float.isInfinite(latestHorizontalAccelerationHeadingRad)
                || lastHorizontalAccelerationHeadingTimeMs <= 0L
                || (nowMs - lastHorizontalAccelerationHeadingTimeMs) > HORIZONTAL_ACCEL_HEADING_FRESH_TIMEOUT_MS
                || latestHorizontalAccelerationMagnitude < HORIZONTAL_ACCEL_HEADING_MIN_MAGNITUDE_MPS2) {
            return Float.NaN;
        }
        return latestHorizontalAccelerationHeadingRad;
    }

    private float resolveFreshWallAlignedHeadingRad(long nowMs) {
        if (Float.isNaN(latestWallAlignedHeadingRad)
                || Float.isInfinite(latestWallAlignedHeadingRad)
                || lastWallAlignedHeadingTimeMs <= 0L
                || (nowMs - lastWallAlignedHeadingTimeMs) > WALL_HEADING_OVERRIDE_TIMEOUT_MS) {
            return Float.NaN;
        }
        return latestWallAlignedHeadingRad;
    }

    private void registerWallAlignedHeading(float headingRad, long nowMs) {
        if (Float.isNaN(headingRad) || Float.isInfinite(headingRad)) {
            return;
        }
        latestWallAlignedHeadingRad = normalizeAngleRad(headingRad);
        lastWallAlignedHeadingTimeMs = nowMs;
        latestMotionHeadingRad = latestWallAlignedHeadingRad;
        lastMotionHeadingTimeMs = nowMs;
    }

    private float resolveMotionIntentHeadingRad(float rawMoveHeadingRad,
                                                float sensorHeadingRad,
                                                long nowMs) {
        float intendedHeadingRad = rawMoveHeadingRad;
        if (Float.isNaN(intendedHeadingRad) || Float.isInfinite(intendedHeadingRad)) {
            intendedHeadingRad = sensorHeadingRad;
        } else if (!Float.isNaN(sensorHeadingRad) && !Float.isInfinite(sensorHeadingRad)) {
            intendedHeadingRad = blendAnglesRad(
                    intendedHeadingRad,
                    sensorHeadingRad,
                    WALL_GUIDANCE_SENSOR_INTENT_BLEND
            );
        }

        float motionHeadingRad = (!Float.isNaN(latestMotionHeadingRad)
                && !Float.isInfinite(latestMotionHeadingRad)
                && lastMotionHeadingTimeMs > 0L
                && (nowMs - lastMotionHeadingTimeMs) <= MOTION_HEADING_FRESH_TIMEOUT_MS)
                ? latestMotionHeadingRad
                : Float.NaN;
        if (!Float.isNaN(motionHeadingRad) && !Float.isInfinite(motionHeadingRad)) {
            intendedHeadingRad = blendAnglesRad(
                    intendedHeadingRad,
                    motionHeadingRad,
                    WALL_GUIDANCE_MOTION_INTENT_BLEND
            );
        }

        float accelerationHeadingRad = resolveFreshHorizontalAccelerationHeadingRad(nowMs);
        if (!Float.isNaN(accelerationHeadingRad) && !Float.isInfinite(accelerationHeadingRad)) {
            float headingDelta = Math.abs(normalizeAngleRad(accelerationHeadingRad - intendedHeadingRad));
            float accelBlend = headingDelta <= Math.toRadians(85.0)
                    ? WALL_GUIDANCE_ACCEL_INTENT_BLEND
                    : WALL_GUIDANCE_ACCEL_INTENT_BLEND * 0.45f;
            intendedHeadingRad = blendAnglesRad(
                    intendedHeadingRad,
                    accelerationHeadingRad,
                    accelBlend
            );
        }

        float wallHeadingRad = resolveFreshWallAlignedHeadingRad(nowMs);
        if (!Float.isNaN(wallHeadingRad) && !Float.isInfinite(wallHeadingRad)) {
            intendedHeadingRad = blendAnglesRad(intendedHeadingRad, wallHeadingRad, 0.22f);
        }
        return normalizeAngleRad(intendedHeadingRad);
    }

    private float computePeakAccelerationMagnitude(List<Double> accelWindow) {
        if (accelWindow == null || accelWindow.isEmpty()) {
            return 0f;
        }
        double peak = 0.0;
        for (Double sample : accelWindow) {
            if (sample == null || Double.isNaN(sample) || Double.isInfinite(sample)) {
                continue;
            }
            peak = Math.max(peak, Math.abs(sample));
        }
        return (float) peak;
    }

    private float clamp01(float value) {
        if (value <= 0f) {
            return 0f;
        }
        if (value >= 1f) {
            return 1f;
        }
        return value;
    }

    private boolean hasQuietLinearAccelerationWindow() {
        if (recentLinearAccelerationMagnitudes == null) {
            return false;
        }
        CircularFloatBuffer.SnapshotStats accelStats = recentLinearAccelerationMagnitudes.getSnapshotStats();
        if (accelStats.count < Math.max(10, STATIONARY_ACCEL_WINDOW_SAMPLES / 2)) {
            return false;
        }
        return accelStats.averageAbs <= STATIONARY_LINEAR_ACCEL_AVG_THRESHOLD
                && accelStats.peakAbs <= STATIONARY_LINEAR_ACCEL_PEAK_THRESHOLD;
    }

    private boolean isLikelyStationary(long nowMs) {
        boolean noRecentStep = lastStepTime <= 0L || (nowMs - lastStepTime) >= STATIONARY_STEP_TIMEOUT_MS;
        boolean accelQuiet = hasQuietLinearAccelerationWindow();
        boolean gnssSlow = !isGnssSpeedFreshForPrediction(nowMs)
                || latestGnssSpeedMps <= STATIONARY_GNSS_SPEED_THRESHOLD_MPS;
        return noRecentStep && accelQuiet && gnssSlow;
    }

    private float[] resolveZeroVelocityAnchor(long nowMs) {
        if (lastAbsoluteMeasurementTimeMs > 0L
                && (nowMs - lastAbsoluteMeasurementTimeMs) <= ZERO_VELOCITY_ANCHOR_FRESH_TIMEOUT_MS
                && lastFusedUpdateTimeMs > 0L
                && (nowMs - lastFusedUpdateTimeMs) <= ZERO_VELOCITY_ANCHOR_FRESH_TIMEOUT_MS
                && isValidXY(latestFusedXY)) {
            return latestFusedXY;
        }
        if (isFreshSource(latestWifiXY, latestWifiFixTimeMs, nowMs, WIFI_FRESH_TIMEOUT_MS)) {
            return latestWifiXY;
        }
        if (isFreshSource(latestGnssXY, latestGnssFixTimeMs, nowMs, GNSS_FRESH_TIMEOUT_MS)
                && isGnssEligibleForFusion(latestGnssAccuracyMeters)) {
            return latestGnssXY;
        }
        return null;
    }

    private synchronized void maybeApplyZeroVelocityConstraint(long nowMs) {
        if (!saveRecording || !isLikelyStationary(nowMs)) {
            zeroVelocityConstraintActive = false;
            return;
        }
        if (lastZeroVelocityCorrectionTimeMs > 0L
                && (nowMs - lastZeroVelocityCorrectionTimeMs) < ZERO_VELOCITY_CORRECTION_MIN_INTERVAL_MS) {
            return;
        }
        if (!isValidXY(latestRawPdrXY) || !isValidXY(latestPdrXY)) {
            return;
        }

        float[] anchor = resolveZeroVelocityAnchor(nowMs);
        if (!isValidXY(anchor)) {
            return;
        }

        float driftMeters = distanceMeters(latestPdrXY, anchor);
        if (driftMeters < ZERO_VELOCITY_MIN_DRIFT_METERS) {
            zeroVelocityConstraintActive = true;
            return;
        }

        float blend = zeroVelocityConstraintActive ? ZERO_VELOCITY_HOLD_BLEND : ZERO_VELOCITY_ENTER_BLEND;
        alignPdrToAnchor(anchor, blend, ZERO_VELOCITY_MAX_SHIFT_METERS);
        zeroVelocityConstraintActive = true;
        lastZeroVelocityCorrectionTimeMs = nowMs;
    }

    private void resetStationaryDetectionState() {
        lastStepTime = 0L;
        zeroVelocityConstraintActive = false;
        lastZeroVelocityCorrectionTimeMs = 0L;
        consecutiveWallGuidedSteps = 0;
        lastWallGuidanceTimeMs = 0L;
        if (accelMagnitude != null) {
            accelMagnitude.clear();
        }
        recentLinearAccelerationMagnitudes = new CircularFloatBuffer(STATIONARY_ACCEL_WINDOW_SAMPLES);
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


        long currentTime = System.currentTimeMillis();
        int sensorType = sensorEvent.sensor.getType();

        // Update timestamp and frequency counter for this sensor
        lastEventTimestamps.put(sensorType, currentTime);
        eventCounts.merge(sensorType, 1, Integer::sum);

        switch (sensorType) {
            case Sensor.TYPE_ACCELEROMETER:

                if (accelerometerSensor != null) {
                    accelerometerSensor.values = sensorEvent.values.clone();
                }
                acceleration[0] = sensorEvent.values[0];
                acceleration[1] = sensorEvent.values[1];
                acceleration[2] = sensorEvent.values[2];
                break;

            case Sensor.TYPE_PRESSURE:
                if (barometerSensor != null) {
                    barometerSensor.values = sensorEvent.values.clone();
                }
                pressure = (1 - ALPHA) * pressure + ALPHA * sensorEvent.values[0];
                if (saveRecording) {
                    this.elevation = pdrProcessing.updateElevation(
                            SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure)
                    );
                    updateConstrainedFloorFromElevation();
                }
                break;

            case Sensor.TYPE_GYROSCOPE:

                if (gyroscopeSensor != null) {
                    gyroscopeSensor.values = sensorEvent.values.clone();
                }
                angularVelocity[0] = sensorEvent.values[0];
                angularVelocity[1] = sensorEvent.values[1];
                angularVelocity[2] = sensorEvent.values[2];

                if (saveRecording && trajectory != null) {

                    long relativeTime = System.currentTimeMillis() - absoluteStartTime;


                    Traj.IMUReading imuReading = Traj.IMUReading.newBuilder()
                            .setRelativeTimestamp(relativeTime)
                            .setGyr(Traj.Vector3.newBuilder()
                                    .setX(angularVelocity[0])
                                    .setY(angularVelocity[1])
                                    .setZ(angularVelocity[2])
                                    .build())
                            .build();
                }
                break;

            case Sensor.TYPE_LINEAR_ACCELERATION:
                filteredAcc[0] = sensorEvent.values[0];
                filteredAcc[1] = sensorEvent.values[1];
                filteredAcc[2] = sensorEvent.values[2];

                // Compute magnitude & add to accelMagnitude
                double accelMagFiltered = Math.sqrt(
                        filteredAcc[0] * filteredAcc[0] +
                                filteredAcc[1] * filteredAcc[1] +
                                filteredAcc[2] * filteredAcc[2]
                );
                this.accelMagnitude.add(accelMagFiltered);
                if (recentLinearAccelerationMagnitudes != null) {
                    recentLinearAccelerationMagnitudes.putNewest((float) accelMagFiltered);
                }
                updateHorizontalAccelerationHeading(sensorEvent.values, currentTime);
                if (saveRecording) {
                    maybeApplyZeroVelocityConstraint(currentTime);
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
                if (lightSensor != null) {
                    lightSensor.values = sensorEvent.values.clone();
                }
                light = sensorEvent.values[0];
                break;

            case Sensor.TYPE_PROXIMITY:
                proximity = sensorEvent.values[0];
                break;

            case Sensor.TYPE_MAGNETIC_FIELD:

                if (magnetometerSensor != null) {
                    magnetometerSensor.values = sensorEvent.values.clone();
                }
                magneticField[0] = sensorEvent.values[0];
                magneticField[1] = sensorEvent.values[1];
                magneticField[2] = sensorEvent.values[2];
                updateMagneticFieldStrength(sensorEvent.values, currentTime);
                break;

            case Sensor.TYPE_ROTATION_VECTOR:
                updateHeadingFromRotationVector(sensorEvent.values);
                break;

            case Sensor.TYPE_GAME_ROTATION_VECTOR:
                updateHeadingFromGameRotationVector(sensorEvent.values);
                break;

            case Sensor.TYPE_STEP_DETECTOR:
                long stepTime = SystemClock.uptimeMillis() - bootTime;
                long stepIntervalMs = lastStepTime > 0L ? (currentTime - lastStepTime) : 0L;


                if (lastStepTime > 0L && stepIntervalMs < MIN_VALID_STEP_INTERVAL_MS) {
                    Log.w("SensorFusion", "Ignoring implausibly rapid step event after "
                            + stepIntervalMs + " ms");
                    // Ignore rapid successive step events
                    break;
                }

                else {
                    lastStepTime = currentTime;
                    zeroVelocityConstraintActive = false;
                    if (accelMagnitude.isEmpty()) {
                        Log.w("SensorFusion", "Step detected without buffered linear acceleration samples.");
                    }
                    List<Double> stepAccelWindow = new ArrayList<>(this.accelMagnitude);

                    float headingForPdr = getCurrentHeadingForParticleFilter();
                    if (Float.isNaN(headingForPdr) || Float.isInfinite(headingForPdr)) {
                        headingForPdr = 0f;
                    }
                    float[] newCords = this.pdrProcessing.updatePdr(
                            stepTime,
                            stepAccelWindow,
                            headingForPdr,
                            stepIntervalMs
                    );

                    // Clear the accelMagnitude after using it
                    this.accelMagnitude.clear();
                    if (isValidXY(newCords)) {
                        latestRawPdrXY = new float[]{newCords[0], newCords[1]};
                        float[] alignedPdr = alignRawPdrToGlobal(newCords);
                        float[] previousPdr = isValidXY(latestPdrXY)
                                ? new float[]{latestPdrXY[0], latestPdrXY[1]}
                                : null;
                        StepWallGuidanceResult wallGuidance = applyWallGuidanceToStep(
                                previousPdr,
                                alignedPdr,
                                headingForPdr,
                                currentTime
                        );
                        float predictionHeadingRad = wallGuidance.headingRad;
                        float[] correctedStepXY = alignedPdr;
                        if (wallGuidance.corrected && isValidXY(wallGuidance.correctedXY)) {
                            correctedStepXY = wallGuidance.correctedXY;
                            updateWallGuidanceState(true, currentTime);
                        } else {
                            updateWallGuidanceState(false, currentTime);
                        }
                        latestPdrXY = correctedStepXY;
                        applyRelativeStepPrediction(
                                correctedStepXY,
                                previousPdr,
                                predictionHeadingRad,
                                stepAccelWindow,
                                stepIntervalMs,
                                System.currentTimeMillis()
                        );
                        maybePullPdrTowardsWifi(currentTime);
                    }

                    if (this.pathView != null && isValidXY(lastCords)) {
                        postPathViewTrajectory(lastCords);
                    }


                    if (saveRecording && trajectory != null) {
                        float[] persistedXY = currentTrajectoryXYForPersistence();
                        float x = persistedXY[0];
                        float y = persistedXY[1];

                        // relative_timestamp is milliseconds from start_timestamp
                        long pdrTime = SystemClock.uptimeMillis() - bootTime;
                        if (pdrTime == 0) pdrTime = 1;


                        Traj.RelativePosition pdrPoint = Traj.RelativePosition.newBuilder()
                                .setRelativeTimestamp(pdrTime)
                                .setX(x)
                                .setY(y)
                                .build();

                        int pdrCount;
                        synchronized (trajectory) {
                            trajectory.addPdrData(pdrPoint);
                            pdrCount = trajectory.getPdrDataCount();
                        }

                    }


                    if (saveRecording) {
                        stepCounter++;
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
    class myLocationListener implements LocationListener {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            //Toast.makeText(context, "Location Changed", Toast.LENGTH_SHORT).show();
            latitude = (float) location.getLatitude();
            longitude = (float) location.getLongitude();
            float altitude = (float) location.getAltitude();
            latestGnssLatLng = new LatLng(latitude, longitude);
            if (coordinateUtils != null && !coordinateUtils.isOriginSet()) {
                coordinateUtils.setOrigin(latitude, longitude);
            }
            if (coordinateUtils != null && coordinateUtils.isOriginSet()) {
                latestGnssXY = coordinateUtils.latLonToXY(latitude, longitude);
                latestGnssFixTimeMs = System.currentTimeMillis();
            }
            latestGnssAccuracyMeters = location.hasAccuracy() ? location.getAccuracy() : Float.NaN;
            latestGnssSpeedMps = location.hasSpeed() ? location.getSpeed() : Float.NaN;
            if (startLocation[0] == 0 && startLocation[1] == 0) {
                startLocation[0] = latitude;
                startLocation[1] = longitude;
            }
            long nowMs = System.currentTimeMillis();
            if ((nowMs - lastGnssEnqueueMs) < GNSS_ENQUEUE_MIN_INTERVAL_MS) {
                return;
            }
            lastGnssEnqueueMs = nowMs;
            enqueueAbsoluteMeasurementUpdate(
                    latestGnssXY,
                    latestGnssAccuracyMeters,
                    com.openpositioning.PositionMe.utils.ParticleFilter.MeasurementType.GNSS
            );
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
        if (wifiList == null || wifiList.length == 0) {
            this.wifiList = new ArrayList<>();
            return;
        }
        List<Wifi> sanitized = Stream.of(wifiList)
                .filter(item -> item instanceof Wifi)
                .map(item -> (Wifi) item)
                .filter(item -> item != null && item.getBssid() != 0L)
                .collect(Collectors.toList());
        if (sanitized.isEmpty()) {
            this.wifiList = new ArrayList<>();
            return;
        }
        sanitized.sort((left, right) -> {
            int levelCompare = Integer.compare(right.getLevel(), left.getLevel());
            if (levelCompare != 0) {
                return levelCompare;
            }
            return Long.compare(right.getFrequency(), left.getFrequency());
        });
        this.wifiList = sanitized;
        createWifiPositionRequestCallback(false);
    }

    private static final class WifiFingerprintRequestData {
        final List<Wifi> accessPoints;
        final int apCount;
        final int strongApCount;
        final float averageLevelDbm;
        final float quality01;
        final WiFiPositioning.PositioningMode positioningMode;

        WifiFingerprintRequestData(List<Wifi> accessPoints,
                                   int apCount,
                                   int strongApCount,
                                   float averageLevelDbm,
                                   float quality01,
                                   WiFiPositioning.PositioningMode positioningMode) {
            this.accessPoints = accessPoints;
            this.apCount = apCount;
            this.strongApCount = strongApCount;
            this.averageLevelDbm = averageLevelDbm;
            this.quality01 = quality01;
            this.positioningMode = positioningMode;
        }
    }

    private WifiFingerprintRequestData buildWifiFingerprintRequestData() {
        if (this.wifiList == null || this.wifiList.isEmpty()) {
            return null;
        }

        List<Wifi> usableWifi = new ArrayList<>();
        for (Wifi wifi : this.wifiList) {
            if (wifi == null || wifi.getBssid() == 0L) {
                continue;
            }
            usableWifi.add(wifi);
        }

        if (usableWifi.isEmpty()) {
            return null;
        }

        usableWifi.sort((left, right) -> {
            int levelCompare = Integer.compare(right.getLevel(), left.getLevel());
            if (levelCompare != 0) {
                return levelCompare;
            }
            return Long.compare(right.getFrequency(), left.getFrequency());
        });

        int strongApCount = countStrongWifiAccessPoints(usableWifi);
        float averageLevelDbm = Float.NaN;
        if (!usableWifi.isEmpty()) {
            float levelSum = 0f;
            for (Wifi wifi : usableWifi) {
                levelSum += wifi.getLevel();
            }
            averageLevelDbm = levelSum / usableWifi.size();
        }
        float measurementAccuracy = estimateWifiMeasurementAccuracyMeters(usableWifi, latestWifiObservedFloor);
        float quality01 = estimateWifiQuality01(measurementAccuracy);
        WiFiPositioning.PositioningMode positioningMode = WiFiPositioning.PositioningMode.FINE;

        return new WifiFingerprintRequestData(
                usableWifi,
                usableWifi.size(),
                strongApCount,
                averageLevelDbm,
                quality01,
                positioningMode
        );
    }

    private int countStrongWifiAccessPoints(List<Wifi> accessPoints) {
        if (accessPoints == null || accessPoints.isEmpty()) {
            return 0;
        }
        int strongCount = 0;
        for (Wifi wifi : accessPoints) {
            if (wifi != null && wifi.getLevel() >= -82) {
                strongCount++;
            }
        }
        return strongCount;
    }

    private float estimateWifiMeasurementAccuracyMeters(List<Wifi> accessPoints, Integer observedFloor) {
        if (accessPoints == null || accessPoints.isEmpty()) {
            return DEFAULT_WIFI_MEASUREMENT_STD_METERS;
        }

        int strongCount = 0;
        int totalCount = 0;
        float strongestRssi = -120f;
        float rssSum = 0f;
        for (Wifi wifi : accessPoints) {
            if (wifi == null) {
                continue;
            }
            totalCount++;
            float rssi = wifi.getLevel();
            rssSum += rssi;
            if (rssi > strongestRssi) {
                strongestRssi = rssi;
            }
            if (rssi >= -82f) {
                strongCount++;
            }
        }
        if (totalCount == 0) {
            return DEFAULT_WIFI_MEASUREMENT_STD_METERS;
        }

        float avgRssi = rssSum / totalCount;
        float accuracy = DEFAULT_WIFI_MEASUREMENT_STD_METERS;
        if (strongCount >= 8) {
            accuracy -= 0.9f;
        } else if (strongCount <= 3) {
            accuracy += 1.0f;
        }
        if (totalCount <= 4) {
            accuracy += 0.9f;
        } else if (totalCount >= 10) {
            accuracy -= 0.4f;
        }
        if (strongestRssi <= -75f) {
            accuracy += 0.8f;
        } else if (strongestRssi >= -60f) {
            accuracy -= 0.4f;
        }
        if (avgRssi <= -78f) {
            accuracy += 0.6f;
        } else if (avgRssi >= -67f) {
            accuracy -= 0.3f;
        }
        if (observedFloor != null && observedFloor != currentFloor) {
            accuracy += 0.8f;
        }
        return Math.max(1.8f, Math.min(6.5f, accuracy));
    }

    private float estimateWifiQuality01(float measurementAccuracyMeters) {
        return clamp01((6.5f - measurementAccuracyMeters) / (6.5f - 1.8f));
    }

    private JSONObject buildWifiFingerprintPayload(List<Wifi> prioritizedWifi) throws JSONException {
        if (prioritizedWifi == null || prioritizedWifi.isEmpty()) {
            return null;
        }

        JSONObject wifiAccessPoints = new JSONObject();
        for (Wifi data : prioritizedWifi) {
            wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
        }
        JSONObject wifiFingerPrint = new JSONObject();
        wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
        return wifiFingerPrint;
    }

    private JSONObject buildWifiFingerprintPayload() throws JSONException {
        WifiFingerprintRequestData requestData = buildWifiFingerprintRequestData();
        if (requestData == null) {
            return null;
        }
        return buildWifiFingerprintPayload(requestData.accessPoints);
    }

    /**
     * Function to create a request to obtain a wifi location for the obtained wifi fingerprint
     *
     */
    private void createWifiPositioningRequest(){
        // Try catch block to catch any errors and prevent app crashing
        try {
            if (this.wiFiPositioning == null || this.wifiList == null || this.wifiList.isEmpty()) {
                return;
            }
            JSONObject wifiFingerPrint = buildWifiFingerprintPayload();
            if (wifiFingerPrint == null) {
                return;
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
    private void createWifiPositionRequestCallback(boolean force){
        try {
            if (this.wiFiPositioning == null) {
                return;
            }
            if (this.wifiList == null || this.wifiList.isEmpty()) {
                return;
            }
            WifiFingerprintRequestData requestData = buildWifiFingerprintRequestData();
            if (requestData == null || requestData.accessPoints.isEmpty()) {
                latestWifiApCount = 0;
                latestWifiAverageLevelDbm = Float.NaN;
                latestWifiQuality01 = 0f;
                latestWifiUsesCoarsePositioning = false;
                return;
            }
            if (!force && countStrongWifiAccessPoints(requestData.accessPoints) < WIFI_MIN_AP_COUNT_FOR_POSITIONING) {
                return;
            }
            long nowMs = System.currentTimeMillis();
            if (!force) {
                if (wifiPositionRequestInFlight) {
                    return;
                }
                if (lastWifiPositionRequestMs > 0L
                        && (nowMs - lastWifiPositionRequestMs) < WIFI_POSITION_REQUEST_MIN_INTERVAL_MS) {
                    return;
                }
            }
            JSONObject wifiFingerPrint = buildWifiFingerprintPayload(requestData.accessPoints);
            if (wifiFingerPrint == null) {
                return;
            }
            wifiPositionRequestInFlight = true;
            lastWifiPositionRequestMs = nowMs;
            final WifiFingerprintRequestData finalRequestData = requestData;
            this.wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng wifiLocation, int floor) {
                    wifiPositionRequestInFlight = false;
                    if (wifiLocation == null) {
                        return;
                    }
                    if (coordinateUtils != null && !coordinateUtils.isOriginSet()) {
                        coordinateUtils.setOrigin(wifiLocation.latitude, wifiLocation.longitude);
                    }
                    float[] wifiCandidateXY = null;
                    if (coordinateUtils != null && coordinateUtils.isOriginSet()) {
                        wifiCandidateXY = coordinateUtils.latLonToXY(wifiLocation.latitude, wifiLocation.longitude);
                    }

                    long nowMs = System.currentTimeMillis();
                    boolean wifiOutlier = isWifiUpdateOutlier(wifiCandidateXY, nowMs);
                    if (wifiOutlier) {
                        consecutiveRejectedWifiUpdates++;
                        Log.w("SensorFusion", "Rejected WiFi update as outlier. candidate="
                                + wifiLocation + " rejectedCount=" + consecutiveRejectedWifiUpdates);
                        return;
                    }
                    latestWifiLatLng = wifiLocation;
                    latestWifiObservedFloor = floor;
                    initialFloorHint = normalizeFloorToAvailable(floor);
                    latestWifiXY = wifiCandidateXY;
                    latestWifiFixTimeMs = nowMs;
                    latestWifiApCount = finalRequestData.apCount;
                    latestWifiAverageLevelDbm = finalRequestData.averageLevelDbm;
                    float wifiMeasurementStd = estimateWifiMeasurementAccuracyMeters(
                            finalRequestData.accessPoints,
                            floor
                    );
                    latestWifiQuality01 = estimateWifiQuality01(wifiMeasurementStd);
                    latestWifiUsesCoarsePositioning = false;
                    lastAcceptedWifiXY = wifiCandidateXY != null ? wifiCandidateXY.clone() : null;
                    lastAcceptedWifiFixTimeMs = nowMs;
                    consecutiveRejectedWifiUpdates = 0;
                    resetRejectedWifiCluster();
                    enqueueAbsoluteMeasurementUpdate(
                            latestWifiXY,
                            wifiMeasurementStd,
                            com.openpositioning.PositionMe.utils.ParticleFilter.MeasurementType.WIFI
                    );
                }

                @Override
                public void onError(String message) {
                    wifiPositionRequestInFlight = false;
                    Log.w("WiFiPositioning", "WiFi positioning request failed: " + message);
                }
            });
        } catch (RuntimeException e) {
            wifiPositionRequestInFlight = false;
            throw e;
        } catch (JSONException e) {
            wifiPositionRequestInFlight = false;
            // Catching error while making JSON object, to prevent crashes
            // Error log to keep record of errors (for secure programming and maintainability)
            Log.e("jsonErrors","Error creating json object"+e.toString());
        }

    }

    public void requestImmediateWifiPositioning() {
        createWifiPositionRequestCallback(true);
    }

    private synchronized void ensureParticleFilterInitialized() {
        if (particleFilter == null) {
            return;
        }

        float[] center = null;
        float initStd = DEFAULT_PARTICLE_INIT_STD_METERS;
        Integer observedFloor = null;
        long now = System.currentTimeMillis();
        if (saveRecording && hasUserProvidedStartLocation()) {
            center = new float[]{0f, 0f};
            initStd = MANUAL_START_PARTICLE_INIT_STD_METERS;
            observedFloor = latestWifiObservedFloor;
        } else if (isFreshSource(latestWifiXY, latestWifiFixTimeMs, now, WIFI_FRESH_TIMEOUT_MS)) {
            center = new float[]{latestWifiXY[0], latestWifiXY[1]};
            initStd = DEFAULT_WIFI_MEASUREMENT_STD_METERS;
            observedFloor = latestWifiObservedFloor;
            wifiAnchorEstablished = true;
        } else if (saveRecording
                && absoluteStartTime > 0L
                && (now - absoluteStartTime) < WIFI_INITIALIZATION_GRACE_MS
                && !isFreshSource(latestGnssXY, latestGnssFixTimeMs, now, GNSS_FRESH_TIMEOUT_MS)) {
            // During recording startup we prefer waiting briefly for WiFi instead of bootstrapping from raw PDR.
            return;
        } else if (isFreshSource(latestGnssXY, latestGnssFixTimeMs, now, GNSS_FRESH_TIMEOUT_MS)
                && isGnssEligibleForFusion(latestGnssAccuracyMeters)) {
            center = new float[]{latestGnssXY[0], latestGnssXY[1]};
            initStd = Float.isNaN(latestGnssAccuracyMeters)
                    ? GNSS_LOW_CONFIDENCE_MAX_STD_METERS
                    : Math.max(GNSS_MIN_STD_STANDALONE_METERS, latestGnssAccuracyMeters * 0.85f);
        } else if (isValidXY(latestPdrXY)) {
            center = new float[]{latestPdrXY[0], latestPdrXY[1]};
        }

        if (!isValidXY(center)) {
            return;
        }

        float headingRad = getCurrentHeadingForParticleFilter();
        if (!particleFilter.isInitialized()) {
            Integer preferredFloor = resolvePreferredInitializationFloor(observedFloor);
            particleFilter.initialize(
                    center[0],
                    center[1],
                    headingRad,
                    preferredFloor,
                    observedFloor,
                    getFloorPlanMapForFilter(),
                    initStd
            );
        }
        syncFusedEstimateFromParticleFilter(now);
    }

    private boolean isGnssEligibleForFusion(float accuracyMeters) {
        return Float.isNaN(accuracyMeters)
                || accuracyMeters <= GNSS_DISCARD_ABSOLUTE_ACCURACY_METERS;
    }

    private float sanitizeGnssAccuracy(float accuracyMeters) {
        if (Float.isNaN(accuracyMeters) || Float.isInfinite(accuracyMeters) || accuracyMeters <= 0f) {
            return Float.POSITIVE_INFINITY;
        }
        return accuracyMeters;
    }

    private float resolveGnssFusionWeight(float accuracyMeters) {
        float effectiveAccuracy = sanitizeGnssAccuracy(accuracyMeters);
        if (effectiveAccuracy <= GNSS_HIGH_CONFIDENCE_MAX_ACCURACY_METERS) {
            return GNSS_FULL_FUSION_WEIGHT;
        }
        if (effectiveAccuracy <= GNSS_REDUCED_WEIGHT_MAX_ACCURACY_METERS) {
            return GNSS_REDUCED_FUSION_WEIGHT;
        }
        return GNSS_MINIMAL_FUSION_WEIGHT;
    }

    private boolean isHighConfidenceGnssWeight(float gnssFusionWeight) {
        return gnssFusionWeight >= (GNSS_FULL_FUSION_WEIGHT - 1e-3f);
    }

    private float[] blendMeasurementTowardsReference(float[] measurementXY,
                                                     float[] referenceXY,
                                                     float measurementWeight) {
        if (!isValidXY(measurementXY)) {
            return measurementXY;
        }
        float clampedWeight = clamp01(measurementWeight);
        if (clampedWeight >= 0.999f || !isValidXY(referenceXY)) {
            return measurementXY.clone();
        }
        return new float[]{
                referenceXY[0] * (1f - clampedWeight) + measurementXY[0] * clampedWeight,
                referenceXY[1] * (1f - clampedWeight) + measurementXY[1] * clampedWeight
        };
    }

    private long resolveGnssUpdateMinIntervalMs(float accuracyMeters) {
        if (!Float.isNaN(accuracyMeters) && accuracyMeters <= GNSS_HIGH_CONFIDENCE_MAX_ACCURACY_METERS) {
            return GNSS_HIGH_CONFIDENCE_UPDATE_MIN_INTERVAL_MS;
        }
        if (!Float.isNaN(accuracyMeters) && accuracyMeters > GNSS_REDUCED_WEIGHT_MAX_ACCURACY_METERS) {
            return GNSS_LOW_CONFIDENCE_UPDATE_MIN_INTERVAL_MS + 1200L;
        }
        return GNSS_LOW_CONFIDENCE_UPDATE_MIN_INTERVAL_MS;
    }

    private synchronized float resolveGnssMeasurementStd(float[] measurementXY,
                                                         float reportedAccuracyMeters,
                                                         boolean wifiFresh) {
        float effectiveAccuracy = sanitizeGnssAccuracy(reportedAccuracyMeters);
        if (!Float.isFinite(effectiveAccuracy)) {
            effectiveAccuracy = GNSS_REDUCED_WEIGHT_MAX_ACCURACY_METERS + 2f;
        }
        float gnssFusionWeight = resolveGnssFusionWeight(effectiveAccuracy);
        float std;
        if (isHighConfidenceGnssWeight(gnssFusionWeight)) {
            std = Math.max(
                    GNSS_MIN_STD_STANDALONE_METERS,
                    Math.min(
                            GNSS_HIGH_CONFIDENCE_MAX_STD_METERS,
                            0.62f * effectiveAccuracy + 0.7f
                    )
            );
        } else if (gnssFusionWeight >= GNSS_REDUCED_FUSION_WEIGHT) {
            std = Math.max(
                    GNSS_LOW_CONFIDENCE_MAX_STD_METERS,
                    Math.min(18f, effectiveAccuracy * 1.35f)
            );
        } else {
            std = Math.max(
                    GNSS_LOW_CONFIDENCE_MAX_STD_METERS + 10f,
                    Math.min(36f, effectiveAccuracy * 1.8f)
            );
        }

        if (wifiFresh) {
            std += isHighConfidenceGnssWeight(gnssFusionWeight)
                    ? 0.8f
                    : (gnssFusionWeight >= GNSS_REDUCED_FUSION_WEIGHT ? 2.0f : 4.0f);
        }

        if (isValidXY(latestFusedXY) && isValidXY(measurementXY)) {
            float innovation = distanceMeters(latestFusedXY, measurementXY);
            if (innovation > 10f && !isHighConfidenceGnssWeight(gnssFusionWeight)) {
                std = Math.max(std, 10.5f);
            }
            if (innovation > 18f && !isHighConfidenceGnssWeight(gnssFusionWeight)) {
                std = Math.max(std, gnssFusionWeight >= GNSS_REDUCED_FUSION_WEIGHT
                        ? GNSS_LOW_CONFIDENCE_MAX_STD_METERS
                        : GNSS_LOW_CONFIDENCE_MAX_STD_METERS + 12f);
            }
        }

        return std;
    }

    // Turns one absolute observation into a particle-filter update with tuned weighting.
    private synchronized void applyAbsoluteMeasurement(float[] measurementXY,
                                                       float accuracyMeters,
                                                       com.openpositioning.PositionMe.utils.ParticleFilter.MeasurementType measurementType) {
        if (!isValidXY(measurementXY) || particleFilter == null) {
            return;
        }

        ensureParticleFilterInitialized();
        if (!particleFilter.isInitialized()) {
            return;
        }

        Integer measurementFloor = measurementType == com.openpositioning.PositionMe.utils.ParticleFilter.MeasurementType.WIFI
                ? latestWifiObservedFloor
                : null;
        if (measurementType == com.openpositioning.PositionMe.utils.ParticleFilter.MeasurementType.WIFI
                && !wifiAnchorEstablished) {
            if (saveRecording && hasUserProvidedStartLocation()) {
                wifiAnchorEstablished = true;
            } else {
                Integer preferredFloor = resolvePreferredInitializationFloor(measurementFloor);
                particleFilter.initialize(
                        measurementXY[0],
                        measurementXY[1],
                        getCurrentHeadingForParticleFilter(),
                        preferredFloor,
                        measurementFloor,
                        getFloorPlanMapForFilter(),
                        Math.max(DEFAULT_WIFI_MEASUREMENT_STD_METERS, WIFI_REANCHOR_STD_METERS)
                );
                wifiAnchorEstablished = true;
                syncFusedEstimateFromParticleFilter(System.currentTimeMillis());
                alignPdrToAnchor(measurementXY, 0.7f);
                return;
            }
        }

        float[] effectiveMeasurementXY = measurementXY;
        float effectiveAccuracy = accuracyMeters;
        if (Float.isNaN(effectiveAccuracy) || effectiveAccuracy <= 0f) {
            effectiveAccuracy = measurementType == com.openpositioning.PositionMe.utils.ParticleFilter.MeasurementType.WIFI
                    ? DEFAULT_WIFI_MEASUREMENT_STD_METERS
                    : GNSS_MIN_STD_STANDALONE_METERS;
        }
        long nowMs = System.currentTimeMillis();
        boolean weakWifiUpdate = false;
        if (measurementType == com.openpositioning.PositionMe.utils.ParticleFilter.MeasurementType.WIFI) {
            effectiveAccuracy = Math.max(DEFAULT_WIFI_MEASUREMENT_STD_METERS, effectiveAccuracy);
            if (latestWifiUsesCoarsePositioning) {
                effectiveAccuracy = Math.max(effectiveAccuracy, WIFI_COARSE_MEASUREMENT_STD_METERS);
            }
            boolean manualStartWarmupActive = isManualStartWarmupActive(nowMs);
            if (manualStartWarmupActive) {
                effectiveAccuracy = Math.max(effectiveAccuracy, 5.8f);
            }
            if (!latestWifiUsesCoarsePositioning
                    && !manualStartWarmupActive
                    && latestWifiQuality01 >= 0.55f
                    && latestWifiApCount >= WIFI_MIN_AP_COUNT_FOR_FINE_POSITIONING) {
                float tightenedAccuracy = latestWifiQuality01 >= 0.72f
                        && latestWifiApCount >= WIFI_MIN_AP_COUNT_FOR_STRONG_FIX
                        ? Math.max(2.7f, effectiveAccuracy * 0.90f)
                        : Math.max(2.85f, effectiveAccuracy * 0.94f);
                effectiveAccuracy = Math.min(effectiveAccuracy, tightenedAccuracy);
            }
            effectiveMeasurementXY = measurementXY.clone();
            weakWifiUpdate = latestWifiUsesCoarsePositioning || latestWifiQuality01 < 0.45f;
        } else {
            boolean wifiFresh = isFreshSource(latestWifiXY, latestWifiFixTimeMs, nowMs, WIFI_FRESH_TIMEOUT_MS);
            float gnssFusionWeight = resolveGnssFusionWeight(accuracyMeters);
            float[] gnssReferenceXY = isValidXY(latestFusedXY)
                    ? latestFusedXY
                    : (isValidXY(latestPdrXY) ? latestPdrXY : null);
            if (!isGnssEligibleForFusion(accuracyMeters) && !isValidXY(gnssReferenceXY)) {
                return;
            }
            effectiveMeasurementXY = blendMeasurementTowardsReference(
                    measurementXY,
                    gnssReferenceXY,
                    gnssFusionWeight
            );
            effectiveAccuracy = resolveGnssMeasurementStd(effectiveMeasurementXY, accuracyMeters, wifiFresh);
            if (Float.isNaN(effectiveAccuracy)) {
                return;
            }
            long minUpdateIntervalMs = resolveGnssUpdateMinIntervalMs(accuracyMeters);
            if (lastAppliedGnssMeasurementMs > 0L
                    && (nowMs - lastAppliedGnssMeasurementMs) < minUpdateIntervalMs) {
                return;
            }

            lastAppliedGnssMeasurementMs = nowMs;
        }
        particleFilter.update(
                effectiveMeasurementXY[0],
                effectiveMeasurementXY[1],
                effectiveAccuracy,
                measurementFloor,
                measurementType,
                getFloorPlanMapForFilter(),
                mapMatchingEngine
        );
        lastAbsoluteMeasurementTimeMs = nowMs;
        syncFusedEstimateFromParticleFilter(nowMs);
        if (measurementType == com.openpositioning.PositionMe.utils.ParticleFilter.MeasurementType.WIFI) {
            applyWifiPositionCorrectionToPdr(effectiveMeasurementXY, measurementFloor, nowMs, weakWifiUpdate);
        }
    }

    // Pulls the latest particle cloud estimate back into shared fused state for the UI.
    private synchronized void syncFusedEstimateFromParticleFilter(long nowMs) {
        if (particleFilter == null || !particleFilter.isInitialized()) {
            return;
        }

        com.openpositioning.PositionMe.utils.ParticleFilter.Estimate estimate = particleFilter.getEstimatedState();
        if (estimate == null) {
            return;
        }

        float[] estimateXY = estimate.getPositionXY();
        if (!isValidXY(estimateXY)) {
            return;
        }

        int estimatedFloor = estimate.getFloor();
        float[] previousFusedXY = isValidXY(latestFusedXY) ? latestFusedXY.clone() : null;
        latestPfXY = new float[]{estimateXY[0], estimateXY[1]};
        latestFusedXY = latestPfXY.clone();
        updateMotionHeadingFromTrajectory(previousFusedXY, latestFusedXY, nowMs);

        int previousFloor = currentFloor;
        currentFloor = estimatedFloor;
        currentFloorPlan = resolveFloorPlan(estimatedFloor);
        updateTrajectoryDisplayPoint(latestFusedXY);
        if (estimatedFloor != previousFloor) {
            pendingFloorDelta += estimatedFloor - previousFloor;
            lastFloorSwitchTimeMs = nowMs;
            confirmedFloorReferenceElevation = elevation;
            confirmedFloorReferenceInitialized = true;
        }
        lastFusedUpdateTimeMs = nowMs;
    }

    private float getSensorHeadingRadOrNaN() {
        if (!headingInitialized) {
            return Float.NaN;
        }
        float heading = normalizeAngleRad(this.orientation[0]);
        if (Float.isNaN(heading) || Float.isInfinite(heading)) {
            return Float.NaN;
        }
        return heading;
    }

    private float getCurrentHeadingForParticleFilter() {
        long nowMs = System.currentTimeMillis();
        float sensorHeading = getPreferredSensorHeadingRad(nowMs);
        float wallHeading = resolveFreshWallAlignedHeadingRad(nowMs);
        if (!Float.isNaN(wallHeading) && !Float.isInfinite(wallHeading)) {
            if (!Float.isNaN(sensorHeading) && !Float.isInfinite(sensorHeading)) {
                return blendAnglesRad(sensorHeading, wallHeading, WALL_HEADING_OVERRIDE_BLEND);
            }
            return wallHeading;
        }
        if (!Float.isNaN(sensorHeading) && !Float.isInfinite(sensorHeading)) {
            return sensorHeading;
        }
        if (!Float.isNaN(latestMotionHeadingRad)
                && !Float.isInfinite(latestMotionHeadingRad)
                && lastMotionHeadingTimeMs > 0L
                && (nowMs - lastMotionHeadingTimeMs) <= MOTION_HEADING_FRESH_TIMEOUT_MS) {
            return normalizeAngleRad(latestMotionHeadingRad);
        }
        if (!Float.isNaN(sensorHeading) && !Float.isInfinite(sensorHeading)) {
            return sensorHeading;
        }
        return 0f;
    }

    private float getDisplayHeadingRad() {
        float sensorHeading = getPreferredSensorHeadingRad(System.currentTimeMillis());
        if (!Float.isNaN(sensorHeading) && !Float.isInfinite(sensorHeading)) {
            return sensorHeading;
        }
        return getCurrentHeadingForParticleFilter();
    }

    private boolean hasActiveMotionForDisplayHeading(long nowMs) {
        boolean recentStep = lastStepTime > 0L
                && (nowMs - lastStepTime) <= DISPLAY_HEADING_ACTIVE_STEP_TIMEOUT_MS;
        boolean gnssMoving = isGnssSpeedFreshForPrediction(nowMs)
                && latestGnssSpeedMps >= DISPLAY_HEADING_ACTIVE_GNSS_SPEED_MPS;
        return recentStep || gnssMoving;
    }

    private float resolveFreshMotionHeadingRad(long nowMs) {
        if (!Float.isNaN(latestMotionHeadingRad)
                && lastMotionHeadingTimeMs > 0L
                && (nowMs - lastMotionHeadingTimeMs) <= MOTION_HEADING_FRESH_TIMEOUT_MS) {
            return latestMotionHeadingRad;
        }
        float heading = getPreferredSensorHeadingRad(nowMs);
        if (Float.isNaN(heading) || Float.isInfinite(heading)) {
            return Float.NaN;
        }
        return heading;
    }

    private synchronized float[] constrainWifiCorrectionToMotion(float[] candidateXY,
                                                                 long nowMs,
                                                                 boolean weakWifiUpdate) {
        if (!WIFI_SIGNAL_FILTERING_ENABLED) {
            return candidateXY;
        }
        if (!isValidXY(candidateXY) || isLikelyStationary(nowMs)) {
            return candidateXY;
        }
        float[] referenceXY = isValidXY(latestFusedXY) ? latestFusedXY : latestPdrXY;
        if (!isValidXY(referenceXY)) {
            return candidateXY;
        }
        float motionHeadingRad = resolveFreshMotionHeadingRad(nowMs);
        if (Float.isNaN(motionHeadingRad) || Float.isInfinite(motionHeadingRad)) {
            return candidateXY;
        }

        float dx = candidateXY[0] - referenceXY[0];
        float dy = candidateXY[1] - referenceXY[1];
        float totalCorrection = (float) Math.sqrt(dx * dx + dy * dy);
        if (totalCorrection <= 0.35f) {
            return candidateXY;
        }

        float dirX = (float) Math.sin(motionHeadingRad);
        float dirY = (float) Math.cos(motionHeadingRad);
        float parallel = dx * dirX + dy * dirY;
        float lateral = -dx * dirY + dy * dirX;
        float maxLateral = weakWifiUpdate
                ? WIFI_MOTION_LATERAL_RELAXED_CLAMP_METERS
                : WIFI_MOTION_LATERAL_CLAMP_METERS;
        float maxForward = weakWifiUpdate
                ? WIFI_MOTION_FORWARD_RELAXED_CLAMP_METERS
                : WIFI_MOTION_FORWARD_CLAMP_METERS;
        float maxBacktrack = weakWifiUpdate
                ? WIFI_MOTION_BACKTRACK_RELAXED_CLAMP_METERS
                : WIFI_MOTION_BACKTRACK_CLAMP_METERS;
        if (totalCorrection > 8f) {
            maxLateral = Math.max(maxLateral, 2.4f);
            maxForward = Math.max(maxForward, 9f);
        }
        float clampedLateral = Math.max(-maxLateral, Math.min(maxLateral, lateral));
        float clampedParallel = Math.max(-maxBacktrack, Math.min(maxForward, parallel));
        if (Math.abs(lateral - clampedLateral) < 1e-4f
                && Math.abs(parallel - clampedParallel) < 1e-4f) {
            return candidateXY;
        }

        return new float[]{
                referenceXY[0] + clampedParallel * dirX - clampedLateral * dirY,
                referenceXY[1] + clampedParallel * dirY + clampedLateral * dirX
        };
    }

    private boolean isFreshSource(float[] xy, long timestampMs, long nowMs, long timeoutMs) {
        return isValidXY(xy) && timestampMs > 0 && (nowMs - timestampMs) <= timeoutMs;
    }

    private synchronized boolean isWifiUpdateOutlier(float[] candidateXY, long nowMs) {
        if (!WIFI_SIGNAL_FILTERING_ENABLED) {
            return false;
        }
        if (!isValidXY(candidateXY)) {
            return true;
        }
        if (!wifiAnchorEstablished || lastAcceptedWifiFixTimeMs <= 0L) {
            resetRejectedWifiCluster();
            return false;
        }
        if (saveRecording
                && absoluteStartTime > 0L
                && (nowMs - absoluteStartTime) <= (WIFI_INITIALIZATION_GRACE_MS + 3000L)) {
            resetRejectedWifiCluster();
            return false;
        }
        float[] reference = null;
        if (isFreshSource(latestFusedXY, lastFusedUpdateTimeMs, nowMs, 8000L)) {
            reference = latestFusedXY;
        } else if (isValidXY(latestPdrXY)) {
            reference = latestPdrXY;
        } else if (isValidXY(lastAcceptedWifiXY)) {
            reference = lastAcceptedWifiXY;
        }
        if (!isValidXY(reference)) {
            resetRejectedWifiCluster();
            return false;
        }

        float dtSecFromFused = Math.min(18f, Math.max(1f, (nowMs - Math.max(lastFusedUpdateTimeMs, 1L)) / 1000f));
        float allowedFromFused = Math.min(
                WIFI_OUTLIER_MAX_METERS,
                WIFI_OUTLIER_BASE_METERS + WIFI_OUTLIER_SPEED_LIMIT_MPS * dtSecFromFused
        );
        boolean outlier = distanceMeters(candidateXY, reference) > allowedFromFused;

        if (outlier && isValidXY(lastAcceptedWifiXY) && lastAcceptedWifiFixTimeMs > 0L) {
            float dtSecFromWifi = Math.min(22f, Math.max(1f, (nowMs - lastAcceptedWifiFixTimeMs) / 1000f));
            float allowedFromWifi = Math.min(
                    WIFI_OUTLIER_MAX_METERS,
                    WIFI_OUTLIER_BASE_METERS + WIFI_OUTLIER_SPEED_LIMIT_MPS * dtSecFromWifi
            );
            outlier = distanceMeters(candidateXY, lastAcceptedWifiXY) > allowedFromWifi;
        }

        if (outlier && isGnssReliableForAssistingWifiOutlier(nowMs)) {
            float gnssDistance = distanceMeters(candidateXY, latestGnssXY);
            if (gnssDistance <= GNSS_WIFI_OUTLIER_ASSIST_MAX_DISTANCE_METERS) {
                outlier = false;
            }
        }

        if (outlier && consecutiveRejectedWifiUpdates >= WIFI_OUTLIER_REJECT_LIMIT) {
            float relaxedAllowed = Math.min(
                    WIFI_OUTLIER_MAX_METERS,
                    allowedFromFused + WIFI_OUTLIER_RELEASE_MARGIN_METERS
            );
            float[] releaseReference = isValidXY(latestPdrXY) ? latestPdrXY : latestFusedXY;
            if (isValidXY(releaseReference)
                    && distanceMeters(candidateXY, releaseReference) <= relaxedAllowed) {
                // Controlled release: allow if candidate is still close to predicted trajectory.
                return false;
            }
            if (consecutiveRejectedWifiUpdates >= WIFI_OUTLIER_REJECT_LIMIT * 2) {
                // Avoid freezing trajectory when WiFi fluctuates for an extended period.
                resetRejectedWifiCluster();
                return false;
            }
        }

        if (outlier && shouldAcceptRepeatedRejectedWifiCluster(candidateXY)) {
            resetRejectedWifiCluster();
            return false;
        }
        if (!outlier) {
            resetRejectedWifiCluster();
        }
        return outlier;
    }

    private boolean isGnssReliableForAssistingWifiOutlier(long nowMs) {
        return isFreshSource(latestGnssXY, latestGnssFixTimeMs, nowMs, GNSS_FRESH_TIMEOUT_MS)
                && !Float.isNaN(latestGnssAccuracyMeters)
                && latestGnssAccuracyMeters <= GNSS_WIFI_OUTLIER_ASSIST_MAX_ACCURACY_METERS;
    }

    private boolean shouldAcceptRepeatedRejectedWifiCluster(float[] candidateXY) {
        if (!isValidXY(candidateXY)) {
            return false;
        }
        if (isValidXY(lastRejectedWifiXY)
                && distanceMeters(candidateXY, lastRejectedWifiXY) <= WIFI_REJECTED_CLUSTER_RADIUS_METERS) {
            repeatedRejectedWifiClusterCount++;
        } else {
            lastRejectedWifiXY = new float[]{candidateXY[0], candidateXY[1]};
            repeatedRejectedWifiClusterCount = 1;
        }
        return repeatedRejectedWifiClusterCount >= WIFI_REJECTED_CLUSTER_ACCEPT_COUNT;
    }

    private void resetRejectedWifiCluster() {
        lastRejectedWifiXY = null;
        repeatedRejectedWifiClusterCount = 0;
    }

    private boolean hasExplicitStartLocation() {
        return startLocation != null
                && startLocation.length >= 2
                && (startLocation[0] != 0f || startLocation[1] != 0f);
    }

    private boolean hasUserProvidedStartLocation() {
        return userProvidedStartLocation && hasExplicitStartLocation();
    }

    private boolean isManualStartWarmupActive(long nowMs) {
        return saveRecording
                && hasUserProvidedStartLocation()
                && absoluteStartTime > 0L
                && (nowMs - absoluteStartTime) <= MANUAL_START_WIFI_ASSIST_WINDOW_MS;
    }

    private synchronized float[] applyMobileContextToWifiMeasurement(float[] candidateXY, long nowMs) {
        if (!isValidXY(candidateXY)) {
            return candidateXY;
        }

        float[] adjustedXY = candidateXY.clone();
        boolean appliedGnssAssist = false;
        if (isFreshSource(latestGnssXY, latestGnssFixTimeMs, nowMs, GNSS_FRESH_TIMEOUT_MS)
                && !Float.isNaN(latestGnssAccuracyMeters)
                && latestGnssAccuracyMeters <= WIFI_MOBILE_ASSIST_MAX_GNSS_ACCURACY_METERS) {
            float gnssFusionWeight = resolveGnssFusionWeight(latestGnssAccuracyMeters);
            float blend = isHighConfidenceGnssWeight(gnssFusionWeight)
                    ? WIFI_MOBILE_ASSIST_STRONG_GNSS_BLEND
                    : (gnssFusionWeight >= GNSS_REDUCED_FUSION_WEIGHT
                    ? WIFI_MOBILE_ASSIST_WEAK_GNSS_BLEND
                    : GNSS_MINIMAL_FUSION_WEIGHT);
            adjustedXY = new float[]{
                    adjustedXY[0] * (1f - blend) + latestGnssXY[0] * blend,
                    adjustedXY[1] * (1f - blend) + latestGnssXY[1] * blend
            };
            adjustedXY = clampTowardsReference(
                    latestGnssXY,
                    adjustedXY,
                    Math.max(
                            WIFI_MANUAL_START_CLAMP_METERS,
                            latestGnssAccuracyMeters + WIFI_MOBILE_ASSIST_CLAMP_MARGIN_METERS
                    )
            );
            appliedGnssAssist = true;
        }

        if (isManualStartWarmupActive(nowMs)) {
            adjustedXY = clampTowardsReference(
                    new float[]{0f, 0f},
                    adjustedXY,
                    appliedGnssAssist
                            ? WIFI_MANUAL_START_WITH_GNSS_CLAMP_METERS
                            : WIFI_MANUAL_START_CLAMP_METERS
            );
        }
        return adjustedXY;
    }

    private synchronized void applyWifiDominantStartAnchorIfAvailable() {
        if (hasExplicitStartLocation()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        boolean wifiFresh = isFreshSource(latestWifiXY, latestWifiFixTimeMs, nowMs, WIFI_FRESH_TIMEOUT_MS);
        boolean gnssFresh = isFreshSource(latestGnssXY, latestGnssFixTimeMs, nowMs, GNSS_FRESH_TIMEOUT_MS);
        boolean gnssReliable = isGnssEligibleForFusion(latestGnssAccuracyMeters);

        LatLng anchorLatLng = null;
        if (wifiFresh && latestWifiLatLng != null && gnssFresh && latestGnssLatLng != null && gnssReliable) {
            final double wifiWeight = WIFI_START_ANCHOR_WEIGHT;
            anchorLatLng = new LatLng(
                    wifiWeight * latestWifiLatLng.latitude + (1.0 - wifiWeight) * latestGnssLatLng.latitude,
                    wifiWeight * latestWifiLatLng.longitude + (1.0 - wifiWeight) * latestGnssLatLng.longitude
            );
        } else if (wifiFresh && latestWifiLatLng != null) {
            anchorLatLng = latestWifiLatLng;
        } else if (gnssFresh && latestGnssLatLng != null && gnssReliable) {
            anchorLatLng = latestGnssLatLng;
        }

        if (anchorLatLng == null || coordinateUtils == null) {
            return;
        }

        startLocation[0] = (float) anchorLatLng.latitude;
        startLocation[1] = (float) anchorLatLng.longitude;
        userProvidedStartLocation = false;
        coordinateUtils.resetOrigin();
        coordinateUtils.setOrigin(anchorLatLng.latitude, anchorLatLng.longitude);

        latestGnssXY = toXY(latestGnssLatLng);
        latestWifiXY = toXY(latestWifiLatLng);
        latestRawPdrXY = new float[]{0f, 0f};
        pdrAlignmentOffsetXY = new float[]{0f, 0f};
        latestPdrXY = new float[]{0f, 0f};
        latestPfXY = null;
        latestFusedXY = new float[]{0f, 0f};
        invalidateHeadingState();
        lastCords = new float[]{0f, 0f};
        lastDisplayTrajectoryUpdateMs = 0L;
        lastFusedUpdateTimeMs = nowMs;
        lastAbsoluteMeasurementTimeMs = 0L;
        lastAcceptedWifiXY = isValidXY(latestWifiXY) ? latestWifiXY.clone() : null;
        lastAcceptedWifiFixTimeMs = latestWifiFixTimeMs;
        consecutiveRejectedWifiUpdates = 0;
        resetRejectedWifiCluster();
        resetStationaryDetectionState();
    }

    private float[] toXY(LatLng latLng) {
        if (latLng == null || coordinateUtils == null || !coordinateUtils.isOriginSet()) {
            return null;
        }
        return coordinateUtils.latLonToXY(latLng.latitude, latLng.longitude);
    }

    private LatLng toLatLng(float[] xy) {
        if (!isValidXY(xy) || coordinateUtils == null || !coordinateUtils.isOriginSet()) {
            return null;
        }
        double[] latLon = coordinateUtils.xyToLatLon(xy[0], xy[1]);
        if (latLon == null || latLon.length < 2) {
            return null;
        }
        return new LatLng(latLon[0], latLon[1]);
    }

    private synchronized float[] stabilizeWifiMeasurement(float[] candidateXY) {
        if (!WIFI_SIGNAL_FILTERING_ENABLED) {
            return candidateXY != null ? candidateXY.clone() : null;
        }
        if (!isValidXY(candidateXY)) {
            return candidateXY;
        }
        if (!wifiAnchorEstablished || !saveRecording || !isValidXY(latestFusedXY)) {
            return candidateXY.clone();
        }
        float maxCorrectionMeters = WIFI_MEASUREMENT_MAX_CORRECTION_METERS;
        long nowMs = System.currentTimeMillis();
        if (lastFusedUpdateTimeMs <= 0L || (nowMs - lastFusedUpdateTimeMs) > 2200L) {
            maxCorrectionMeters = Math.max(maxCorrectionMeters, 12f);
        }
        if (!Float.isNaN(latestGnssAccuracyMeters)) {
            if (latestGnssAccuracyMeters <= GNSS_HIGH_CONFIDENCE_MAX_ACCURACY_METERS) {
                maxCorrectionMeters = Math.max(maxCorrectionMeters, 14f);
            } else if (latestGnssAccuracyMeters <= GNSS_LOW_CONFIDENCE_MAX_ACCURACY_METERS) {
                maxCorrectionMeters = Math.max(maxCorrectionMeters, 11f);
            }
        }
        if (consecutiveRejectedWifiUpdates > 0) {
            maxCorrectionMeters = Math.min(
                    16f,
                    maxCorrectionMeters + consecutiveRejectedWifiUpdates * 1.5f
            );
        }
        return clampTowardsReference(latestFusedXY, candidateXY, maxCorrectionMeters);
    }

    private float[] clampTowardsReference(float[] referenceXY, float[] targetXY, float maxDistanceMeters) {
        if (!isValidXY(referenceXY) || !isValidXY(targetXY) || maxDistanceMeters <= 0f) {
            return targetXY;
        }
        float dx = targetXY[0] - referenceXY[0];
        float dy = targetXY[1] - referenceXY[1];
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance <= maxDistanceMeters || distance <= 1e-5f) {
            return targetXY.clone();
        }
        float ratio = maxDistanceMeters / distance;
        return new float[]{
                referenceXY[0] + dx * ratio,
                referenceXY[1] + dy * ratio
        };
    }

    private synchronized float estimateWifiMeasurementStd(float[] wifiMeasurementXY,
                                                          boolean isOutlier,
                                                          float fingerprintQuality01,
                                                          int apCount,
                                                          boolean usedCoarsePositioning) {
        float std = usedCoarsePositioning
                ? WIFI_COARSE_MEASUREMENT_STD_METERS
                : DEFAULT_WIFI_MEASUREMENT_STD_METERS;
        if (!usedCoarsePositioning && fingerprintQuality01 >= 0.72f && apCount >= WIFI_MIN_AP_COUNT_FOR_STRONG_FIX) {
            std = Math.min(std, WIFI_STRONG_FINE_MEASUREMENT_STD_METERS);
        }
        if (fingerprintQuality01 < 0.35f) {
            std = Math.max(std, usedCoarsePositioning ? 9.0f : 6.2f);
        } else if (fingerprintQuality01 < 0.55f) {
            std = Math.max(std, usedCoarsePositioning ? 8.2f : 4.8f);
        }
        if (apCount < WIFI_MIN_AP_COUNT_FOR_FINE_POSITIONING) {
            std = Math.max(std, usedCoarsePositioning ? 8.8f : 5.6f);
        }
        float[] reference = isValidXY(latestFusedXY) ? latestFusedXY : latestPdrXY;
        if (isValidXY(reference) && isValidXY(wifiMeasurementXY)) {
            float innovation = distanceMeters(reference, wifiMeasurementXY);
            if (innovation > 4f) {
                std = Math.max(std, 5f);
            }
            if (innovation > 7f) {
                std = Math.max(std, 8f);
            }
            if (innovation > 10f) {
                std = Math.max(std, 11f);
            }
        }
        if (isOutlier) {
            std = Math.max(std, WIFI_OUTLIER_WEAK_UPDATE_STD_METERS);
        }
        return std;
    }

    private void enqueueAbsoluteMeasurementUpdate(float[] measurementXY,
                                                  float accuracyMeters,
                                                  com.openpositioning.PositionMe.utils.ParticleFilter.MeasurementType measurementType) {
        if (!isValidXY(measurementXY)) {
            return;
        }
        final float[] measurementCopy = new float[]{measurementXY[0], measurementXY[1]};
        fusionUpdateExecutor.execute(() ->
                applyAbsoluteMeasurement(measurementCopy, accuracyMeters, measurementType)
        );
    }

    private boolean isValidXY(float[] xy) {
        return xy != null
                && xy.length >= 2
                && !Float.isNaN(xy[0])
                && !Float.isNaN(xy[1])
                && !Float.isInfinite(xy[0])
                && !Float.isInfinite(xy[1]);
    }

    private float distanceMeters(float[] a, float[] b) {
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private float normalizeAngleRad(float angleRad) {
        while (angleRad > Math.PI) angleRad -= (float) (2.0 * Math.PI);
        while (angleRad < -Math.PI) angleRad += (float) (2.0 * Math.PI);
        return angleRad;
    }

    // Restricts floor changes to valid stairs/lift zones and confirmed height transitions.
    private synchronized void updateConstrainedFloorFromElevation() {
        com.openpositioning.PositionMe.data.remote.FloorPlan activeFloorPlan = resolveFloorPlan(currentFloor);
        if (activeFloorPlan == null || mapMatchingEngine == null || particleFilter == null || !particleFilter.isInitialized()) {
            return;
        }

        if (!confirmedFloorReferenceInitialized) {
            confirmedFloorReferenceElevation = elevation;
            confirmedFloorReferenceInitialized = true;
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastFloorSwitchTimeMs < FLOOR_SWITCH_COOLDOWN_MS) {
            return;
        }

        float floorHeightMeters = Math.max(AUTO_FLOOR_HEIGHT_STEP_METERS, settings != null
                ? settings.getInt("floor_height", (int) AUTO_FLOOR_HEIGHT_STEP_METERS)
                : AUTO_FLOOR_HEIGHT_STEP_METERS);
        float transitionThreshold = Math.max(FLOOR_TRANSITION_MIN_METERS, floorHeightMeters * FLOOR_TRANSITION_CONFIRM_RATIO);
        float relativeHeightDelta = elevation - confirmedFloorReferenceElevation;
        if (Math.abs(relativeHeightDelta) < transitionThreshold) {
            return;
        }

        float[] referenceXY = isValidXY(latestFusedXY) ? latestFusedXY : latestPdrXY;
        if (!isValidXY(referenceXY)) {
            return;
        }

        if (!mapMatchingEngine.isInsideVerticalAccess(referenceXY, elevator, activeFloorPlan)) {
            return;
        }

        float verticalTransitionScore = mapMatchingEngine.verticalTransitionScore(referenceXY, elevator, activeFloorPlan);
        if (verticalTransitionScore < 0.6f) {
            return;
        }

        int oneStepDelta = relativeHeightDelta > 0f ? 1 : -1;
        boolean accepted = particleFilter.applyFloorChange(
                oneStepDelta,
                elevator,
                getFloorPlanMapForFilter(),
                mapMatchingEngine
        );
        if (!accepted) {
            return;
        }

        lastFloorSwitchTimeMs = now;
        confirmedFloorReferenceElevation = elevation;
        confirmedFloorReferenceInitialized = true;
        syncFusedEstimateFromParticleFilter(now);
    }

    /**
     * Method to get user position obtained using {@link WiFiPositioning}.
     *
     * @return {@link LatLng} corresponding to user's position.
     */
    public LatLng getLatLngWifiPositioning() {
        return this.wiFiPositioning != null ? this.wiFiPositioning.getWifiLocation() : null;
    }

    /**
     * Method to get current floor the user is at, obtained using WiFiPositioning
     * @see WiFiPositioning for WiFi positioning
     * @return Current floor user is at using WiFiPositioning
     */
    public int getWifiFloor(){
        return this.wiFiPositioning != null ? this.wiFiPositioning.getFloor() : 0;
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
    public void setStartTimestampMs(long ts) {
        this.startTimestampMs = ts;
    }

    public long getStartTimestampMs() {
        return this.startTimestampMs;
    }

    public synchronized void setTestPoints(java.util.List<com.openpositioning.PositionMe.Traj.GNSSPosition> points) {
        this.testPoints.clear();
        if (points != null) this.testPoints.addAll(points);
        syncTestPointsIntoTrajectory();
    }

    public synchronized void appendTestPoint(com.openpositioning.PositionMe.Traj.GNSSPosition point) {
        if (point == null) {
            return;
        }
        // Only add to the canonical testPoints list. syncTestPointsIntoTrajectory() will
        // clear + re-add all points into trajectory before upload, so writing directly to
        // trajectory here would cause duplicates when sync runs.
        this.testPoints.add(point);
    }

    public synchronized java.util.List<com.openpositioning.PositionMe.Traj.GNSSPosition> getTestPoints() {
        return new java.util.ArrayList<>(this.testPoints);
    }

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
        LatLng preservedGnssLatLng = this.latestGnssLatLng;
        LatLng preservedWifiLatLng = this.latestWifiLatLng;
        float preservedGnssAccuracy = this.latestGnssAccuracyMeters;
        float preservedGnssSpeed = this.latestGnssSpeedMps;
        long preservedGnssFixTime = this.latestGnssFixTimeMs;
        long preservedWifiFixTime = this.latestWifiFixTimeMs;
        Integer preservedWifiFloor = this.latestWifiObservedFloor;

        if (startPosition != null && startPosition.length >= 2) {
            startLocation = startPosition.clone();
            this.userProvidedStartLocation = (startPosition[0] != 0f || startPosition[1] != 0f);
        } else {
            startLocation = new float[2];
            this.userProvidedStartLocation = false;
        }
        if (coordinateUtils != null) {
            coordinateUtils.resetOrigin();
            if (startLocation.length >= 2 && (startLocation[0] != 0f || startLocation[1] != 0f)) {
                coordinateUtils.setOrigin(startLocation[0], startLocation[1]);
            }
        }
        this.latestGnssLatLng = preservedGnssLatLng;
        this.latestWifiLatLng = preservedWifiLatLng;
        this.latestGnssXY = toXY(preservedGnssLatLng);
        this.latestWifiXY = toXY(preservedWifiLatLng);
        this.latestGnssAccuracyMeters = preservedGnssAccuracy;
        this.latestGnssSpeedMps = preservedGnssSpeed;
        this.latestGnssFixTimeMs = preservedGnssFixTime;
        this.latestWifiFixTimeMs = preservedWifiFixTime;
        this.latestWifiObservedFloor = preservedWifiFloor;
        this.lastAcceptedWifiXY = null;
        this.lastAcceptedWifiFixTimeMs = 0L;
        this.consecutiveRejectedWifiUpdates = 0;
        resetRejectedWifiCluster();
        long nowMs = System.currentTimeMillis();
        if (preservedWifiFloor != null
                && preservedWifiFixTime > 0L
                && (nowMs - preservedWifiFixTime) <= WIFI_FRESH_TIMEOUT_MS) {
            this.initialFloorHint = normalizeFloorToAvailable(preservedWifiFloor);
        } else {
            this.initialFloorHint = normalizeFloorToAvailable(this.currentFloor);
        }
        this.latestRawPdrXY = new float[]{0f, 0f};
        this.pdrAlignmentOffsetXY = new float[]{0f, 0f};
        this.latestPdrXY = new float[]{0f, 0f};
        this.latestPfXY = null;
        this.latestFusedXY = new float[]{0f, 0f};
        invalidateHeadingState();
        this.lastFusedUpdateTimeMs = 0L;
        this.lastAppliedGnssMeasurementMs = 0L;
        this.lastAbsoluteMeasurementTimeMs = 0L;
        this.lastCords = null;
        this.lastDisplayTrajectoryUpdateMs = 0L;
        this.currentFloor = normalizeFloorToAvailable(initialFloorHint);
        this.currentFloorPlan = resolveFloorPlan(currentFloor);
        this.pendingFloorDelta = 0;
        this.lastFloorSwitchTimeMs = 0L;
        this.confirmedFloorReferenceElevation = 0f;
        this.confirmedFloorReferenceInitialized = false;
        this.wifiAnchorEstablished = false;
        resetStationaryDetectionState();
        if (this.particleFilter != null) {
            this.particleFilter.reset();
        }
    }


    /**
     * Function to redraw path in corrections fragment.
     *
     * @param scalingRatio new size of path due to updated step length
     */
    public void redrawPath(float scalingRatio){
        if (pathView == null) {
            return;
        }
        mainThreadHandler.post(() -> {
            if (pathView == null) {
                return;
            }
            pathView.redraw(scalingRatio);
            pathView.postInvalidateOnAnimation();
        });
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
        return getDisplayHeadingRad();
    }

    public float getLatestGnssAccuracyMeters() {
        return latestGnssAccuracyMeters;
    }

    public boolean isUserStationary() {
        return isLikelyStationary(System.currentTimeMillis());
    }

    public static final class DisplaySnapshot {
        public final float[] wifiXY = new float[2];
        public final float[] pdrXY = new float[2];
        public final float[] fusedXY = new float[2];
        public final float[] displayTrajectoryXY = new float[2];
        public final double[] originLatLon = new double[2];
        public LatLng gnssLatLng;
        public LatLng wifiLatLng;
        public LatLng wifiPositioningLatLng;
        public boolean hasWifiXY;
        public boolean hasPdrXY;
        public boolean hasFusedXY;
        public boolean hasDisplayTrajectoryXY;
        public boolean hasOrigin;
        public boolean wifiFresh;
        public boolean gnssFresh;
        public boolean stationary;
        public boolean wifiUsesCoarsePositioning;
        public int wifiApCount;
        public float wifiQuality01 = 0f;
        public float gnssAccuracyMeters = Float.NaN;
        public float orientationRad = Float.NaN;
        public long wifiFixTimeMs;
        public long gnssFixTimeMs;
        public long frameTimeMs;

        public void reset() {
            gnssLatLng = null;
            wifiLatLng = null;
            wifiPositioningLatLng = null;
            hasWifiXY = false;
            hasPdrXY = false;
            hasFusedXY = false;
            hasDisplayTrajectoryXY = false;
            hasOrigin = false;
            wifiFresh = false;
            gnssFresh = false;
            stationary = false;
            wifiUsesCoarsePositioning = false;
            wifiApCount = 0;
            wifiQuality01 = 0f;
            gnssAccuracyMeters = Float.NaN;
            orientationRad = Float.NaN;
            wifiFixTimeMs = 0L;
            gnssFixTimeMs = 0L;
            frameTimeMs = 0L;
        }
    }

    // Packs only the display-facing fusion state needed by map fragments and overlays.
    public void fillDisplaySnapshot(@NonNull DisplaySnapshot snapshot, long fusedTimeoutMs) {
        if (fusedTimeoutMs <= 0L) {
            fusedTimeoutMs = 1500L;
        }
        long now = System.currentTimeMillis();
        snapshot.reset();
        snapshot.frameTimeMs = now;
        snapshot.gnssLatLng = latestGnssLatLng;
        snapshot.wifiLatLng = latestWifiLatLng;
        snapshot.wifiPositioningLatLng = wiFiPositioning != null ? wiFiPositioning.getWifiLocation() : null;
        snapshot.hasWifiXY = copyXYInto(latestWifiXY, snapshot.wifiXY);
        snapshot.hasPdrXY = copyXYInto(latestPdrXY, snapshot.pdrXY);
        snapshot.hasFusedXY = lastFusedUpdateTimeMs > 0L
                && (now - lastFusedUpdateTimeMs) <= fusedTimeoutMs
                && copyXYInto(latestFusedXY, snapshot.fusedXY);
        snapshot.hasDisplayTrajectoryXY = lastDisplayTrajectoryUpdateMs > 0L
                && (now - lastDisplayTrajectoryUpdateMs) <= fusedTimeoutMs
                && copyXYInto(lastCords, snapshot.displayTrajectoryXY);
        snapshot.wifiFresh = isFreshSource(latestWifiXY, latestWifiFixTimeMs, now, WIFI_FRESH_TIMEOUT_MS);
        snapshot.gnssFresh = isFreshSource(latestGnssXY, latestGnssFixTimeMs, now, GNSS_FRESH_TIMEOUT_MS);
        snapshot.stationary = isLikelyStationary(now);
        snapshot.wifiUsesCoarsePositioning = latestWifiUsesCoarsePositioning;
        snapshot.wifiApCount = latestWifiApCount;
        snapshot.wifiQuality01 = latestWifiQuality01;
        snapshot.gnssAccuracyMeters = latestGnssAccuracyMeters;
        snapshot.orientationRad = getDisplayHeadingRad();
        snapshot.wifiFixTimeMs = latestWifiFixTimeMs;
        snapshot.gnssFixTimeMs = latestGnssFixTimeMs;
        snapshot.hasOrigin = copyDisplayOriginLatLon(snapshot.originLatLon);
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
        sensorValueMap.put(SensorTypes.PDR, getLatestPdrPositionXY());
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


    public LatLng getLatestGnssLatLng() {
        return latestGnssLatLng;
    }


    public LatLng getLatestWifiLatLng() {
        return latestWifiLatLng;
    }

    public boolean copyLatestWifiPositionXY(@NonNull float[] out) {
        return copyXYInto(latestWifiXY, out);
    }

    public float[] getLatestWifiPositionXY() {
        return latestWifiXY == null ? null : latestWifiXY.clone();
    }

    public boolean isLatestWifiFresh() {
        long now = System.currentTimeMillis();
        return isFreshSource(latestWifiXY, latestWifiFixTimeMs, now, WIFI_FRESH_TIMEOUT_MS);
    }

    public boolean isLatestGnssFresh() {
        long now = System.currentTimeMillis();
        return isFreshSource(latestGnssXY, latestGnssFixTimeMs, now, GNSS_FRESH_TIMEOUT_MS);
    }

    public boolean copyLatestPdrPositionXY(@NonNull float[] out) {
        return copyXYInto(latestPdrXY, out);
    }

    public float[] getLatestPdrPositionXY() {
        return latestPdrXY == null ? null : latestPdrXY.clone();
    }

    public boolean copyLatestFusedPositionXY(@NonNull float[] out) {
        return lastFusedUpdateTimeMs > 0L && copyXYInto(latestFusedXY, out);
    }

    public float[] getLatestFusedPositionXY() {
        if (lastFusedUpdateTimeMs <= 0L) {
            return null;
        }
        return latestFusedXY == null ? null : latestFusedXY.clone();
    }

    public boolean isLatestFusedFresh(long timeoutMs) {
        if (timeoutMs <= 0L) {
            timeoutMs = 1500L;
        }
        long now = System.currentTimeMillis();
        return lastFusedUpdateTimeMs > 0L
                && (now - lastFusedUpdateTimeMs) <= timeoutMs
                && isValidXY(latestFusedXY);
    }

    public boolean copyDisplayOriginLatLon(@NonNull double[] out) {
        if (out.length < 2) {
            return false;
        }
        if (coordinateUtils != null && coordinateUtils.copyOriginTo(out)) {
            return true;
        }
        if (startLocation != null && startLocation.length >= 2
                && (startLocation[0] != 0f || startLocation[1] != 0f)) {
            out[0] = startLocation[0];
            out[1] = startLocation[1];
            return true;
        }
        if (latestGnssLatLng != null) {
            out[0] = latestGnssLatLng.latitude;
            out[1] = latestGnssLatLng.longitude;
            return true;
        }
        if (latestWifiLatLng != null) {
            out[0] = latestWifiLatLng.latitude;
            out[1] = latestWifiLatLng.longitude;
            return true;
        }
        return false;
    }

    public LatLng getDisplayOriginLatLng() {
        double[] origin = new double[2];
        if (copyDisplayOriginLatLon(origin)) {
            return new LatLng(origin[0], origin[1]);
        }
        return null;
    }


    // Exposes the map-matching floor delta once so the UI can consume it safely.
    public synchronized int consumePendingFloorDelta() {
        int delta = pendingFloorDelta;
        pendingFloorDelta = 0;
        return delta;
    }

    private boolean copyXYInto(float[] source, @NonNull float[] out) {
        if (source == null || out.length < 2 || !isValidXY(source)) {
            return false;
        }
        out[0] = source[0];
        out[1] = source[1];
        return true;
    }


    // Lets the map UI override the active floor after a deliberate manual selection.
    public synchronized void setCurrentFloorByMapMatching(int floor) {
        int targetFloor = floor;
        long nowMs = System.currentTimeMillis();
        boolean wifiFloorFresh = latestWifiObservedFloor != null
                && latestWifiFixTimeMs > 0L
                && (nowMs - latestWifiFixTimeMs) <= WIFI_FRESH_TIMEOUT_MS;
        if (wifiFloorFresh && (this.particleFilter == null || !this.particleFilter.isInitialized())) {
            targetFloor = latestWifiObservedFloor;
            this.initialFloorHint = latestWifiObservedFloor;
        } else if (!saveRecording && availableFloorPlans.size() <= 1) {
            this.initialFloorHint = floor;
        } else if (saveRecording && (this.particleFilter == null || !this.particleFilter.isInitialized())) {
            // Avoid injecting UI default floor into initialization before WiFi/GNSS fixes stabilize.
            targetFloor = this.currentFloor;
        }
        targetFloor = normalizeFloorToAvailable(targetFloor);
        this.initialFloorHint = normalizeFloorToAvailable(this.initialFloorHint);

        this.currentFloorPlan = resolveFloorPlan(targetFloor);
        this.pendingFloorDelta = 0;
        this.lastFloorSwitchTimeMs = System.currentTimeMillis();
        this.confirmedFloorReferenceElevation = this.elevation;
        this.confirmedFloorReferenceInitialized = true;
        if (this.particleFilter == null || !this.particleFilter.isInitialized()) {
            this.currentFloor = targetFloor;
        }
    }
    // Returns the floor currently selected by the fusion and map-matching pipeline.
    public int getCurrentFloorByMapMatching() {
        return currentFloor;
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

    private void registerSensorListener(MovementSensor movementSensor, int samplingPeriodUs) {
        registerSensorListener(movementSensor, samplingPeriodUs, motionSensorHandler);
    }

    private void registerSensorListener(MovementSensor movementSensor, int samplingPeriodUs, Handler targetHandler) {
        if (movementSensor == null || movementSensor.sensorManager == null || movementSensor.sensor == null) {
            return;
        }
        ensureSensorCallbackThread();
        Handler callbackHandler = targetHandler != null ? targetHandler : motionSensorHandler;
        movementSensor.sensorManager.registerListener(this, movementSensor.sensor, samplingPeriodUs, callbackHandler);
    }

    private void registerSensorListener(MovementSensor movementSensor, int samplingPeriodUs, int maxLatencyUs) {
        registerSensorListener(movementSensor, samplingPeriodUs, maxLatencyUs, motionSensorHandler);
    }

    private void registerSensorListener(MovementSensor movementSensor,
                                        int samplingPeriodUs,
                                        int maxLatencyUs,
                                        Handler targetHandler) {
        if (movementSensor == null || movementSensor.sensorManager == null || movementSensor.sensor == null) {
            return;
        }
        ensureSensorCallbackThread();
        Handler callbackHandler = targetHandler != null ? targetHandler : motionSensorHandler;
        movementSensor.sensorManager.registerListener(
                this,
                movementSensor.sensor,
                samplingPeriodUs,
                maxLatencyUs,
                callbackHandler
        );
    }

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
        ensureSensorCallbackThread();

        // Keep motion-critical sensors on a dedicated high-priority looper so step callbacks do not
        // sit behind the rest of the IMU traffic while the user is moving.
        registerSensorListener(linearAccelerationSensor, 20000, (int) maxReportLatencyNs, motionSensorHandler);
        registerSensorListener(gravitySensor, 20000, (int) maxReportLatencyNs, motionSensorHandler);
        registerSensorListener(stepDetectionSensor, SensorManager.SENSOR_DELAY_NORMAL, motionSensorHandler);
        registerSensorListener(rotationSensor, 20000, (int) maxReportLatencyNs, motionSensorHandler);
        registerSensorListener(gameRotationSensor, 20000, (int) maxReportLatencyNs, motionSensorHandler);

        // Lower the rate of auxiliary sensors to reduce callback backlog and runtime jank.
        registerSensorListener(accelerometerSensor, 20000, (int) maxReportLatencyNs, sensorCallbackHandler);
        registerSensorListener(gyroscopeSensor, 20000, (int) maxReportLatencyNs, sensorCallbackHandler);
        registerSensorListener(magnetometerSensor, 20000, (int) maxReportLatencyNs, sensorCallbackHandler);
        registerSensorListener(barometerSensor, (int) 1e6, sensorCallbackHandler);
        registerSensorListener(lightSensor, (int) 1e6, sensorCallbackHandler);
        registerSensorListener(proximitySensor, (int) 1e6, sensorCallbackHandler);
        if (wifiProcessor != null) {
            wifiProcessor.startListening();
        }
        if (gnssProcessor != null) {
            gnssProcessor.startLocationUpdates();
        }
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
            if (accelerometerSensor != null && accelerometerSensor.sensorManager != null) {
                accelerometerSensor.sensorManager.unregisterListener(this);
            }
            if (barometerSensor != null && barometerSensor.sensorManager != null) {
                barometerSensor.sensorManager.unregisterListener(this);
            }
            if (gyroscopeSensor != null && gyroscopeSensor.sensorManager != null) {
                gyroscopeSensor.sensorManager.unregisterListener(this);
            }
            if (lightSensor != null && lightSensor.sensorManager != null) {
                lightSensor.sensorManager.unregisterListener(this);
            }
            if (proximitySensor != null && proximitySensor.sensorManager != null) {
                proximitySensor.sensorManager.unregisterListener(this);
            }
            if (magnetometerSensor != null && magnetometerSensor.sensorManager != null) {
                magnetometerSensor.sensorManager.unregisterListener(this);
            }
            if (stepDetectionSensor != null && stepDetectionSensor.sensorManager != null) {
                stepDetectionSensor.sensorManager.unregisterListener(this);
            }
            if (rotationSensor != null && rotationSensor.sensorManager != null) {
                rotationSensor.sensorManager.unregisterListener(this);
            }
            if (gameRotationSensor != null && gameRotationSensor.sensorManager != null) {
                gameRotationSensor.sensorManager.unregisterListener(this);
            }
            if (linearAccelerationSensor != null && linearAccelerationSensor.sensorManager != null) {
                linearAccelerationSensor.sensorManager.unregisterListener(this);
            }
            if (gravitySensor != null && gravitySensor.sensorManager != null) {
                gravitySensor.sensorManager.unregisterListener(this);
            }
            //The app often crashes here because the scan receiver stops after it has found the list.
            // It will only unregister one if there is to unregister
            try {
                if (this.wifiProcessor != null) {
                    this.wifiProcessor.stopListening(); //error here?
                }
            } catch (Exception e) {
                System.err.println("Wifi resumed before existing");
            }
            this.wifiPositionRequestInFlight = false;
            this.pendingWifiPositionRequest = false;
            this.lastWifiFingerprintSignatureTimeMs = 0L;
            this.lastAppliedGnssMeasurementMs = 0L;
            // Stop receiving location updates
            if (this.gnssProcessor != null) {
                this.gnssProcessor.stopUpdating();
            }
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
        if (wakeLock == null && appContext != null) {
            PowerManager powerManager = (PowerManager) this.appContext.getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PositionMe::WakeLock");
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(31 * 60 * 1000L);
        }

        this.saveRecording = true;
        this.wifiPositionRequestInFlight = false;
        this.pendingWifiPositionRequest = false;
        this.lastWifiPositionRequestMs = 0L;
        this.lastWifiFingerprintSignatureTimeMs = 0L;
        this.lastAppliedGnssMeasurementMs = 0L;
        this.stepCounter = 0;
        this.absoluteStartTime = System.currentTimeMillis();
        this.bootTime = SystemClock.uptimeMillis();
        this.startTimestampMs = this.absoluteStartTime;
        long nowMs = System.currentTimeMillis();
        if (this.latestWifiObservedFloor != null
                && this.latestWifiFixTimeMs > 0L
                && (nowMs - this.latestWifiFixTimeMs) <= WIFI_FRESH_TIMEOUT_MS) {
            this.initialFloorHint = normalizeFloorToAvailable(this.latestWifiObservedFloor);
        } else {
            this.initialFloorHint = normalizeFloorToAvailable(this.currentFloor);
        }
        // Reset PDR and PF state for the new recording session.
        this.latestRawPdrXY = new float[]{0f, 0f};
        this.pdrAlignmentOffsetXY = new float[]{0f, 0f};
        this.latestPdrXY = new float[]{0f, 0f};
        this.latestPfXY = null;
        this.latestFusedXY = new float[]{0f, 0f};
        invalidateHeadingState();
        this.lastFusedUpdateTimeMs = 0L;
        this.lastAbsoluteMeasurementTimeMs = 0L;
        this.pendingFloorDelta = 0;
        this.currentFloor = normalizeFloorToAvailable(initialFloorHint);
        this.currentFloorPlan = resolveFloorPlan(currentFloor);
        this.lastFloorSwitchTimeMs = 0L;
        this.confirmedFloorReferenceElevation = 0f;
        this.confirmedFloorReferenceInitialized = false;
        this.wifiAnchorEstablished = false;
        resetStationaryDetectionState();
        if (this.particleFilter != null) {
            this.particleFilter.reset();
        }

        // Reset counters
        this.counter = 0;
        this.secondCounter = 0;

        clearPathViewOnMainThread();
        this.pdrProcessing.resetPDR();
        applyWifiDominantStartAnchorIfAvailable();
        this.lastAcceptedWifiXY = null;
        this.lastAcceptedWifiFixTimeMs = 0L;
        this.consecutiveRejectedWifiUpdates = 0;
        resetRejectedWifiCluster();
        synchronized (this) {
            this.testPoints.clear();
        }

        // Initialize Traj Builder
        this.trajectory = Traj.Trajectory.newBuilder();

        // Attach Add-Tag test points to protobuf
        syncTestPointsIntoTrajectory();
        // Set Metadata
        String currentTrajectoryIdtrajectoryId = "Traj_" + absoluteStartTime;
        this.trajectory.setTrajectoryId(currentTrajectoryIdtrajectoryId);
        this.trajectory.setAndroidVersion(Build.VERSION.RELEASE);
        this.trajectory.setStartTimestamp(absoluteStartTime);
        // Seed PDR with non-default values so it is serialized in proto3 (server needs a valid start time)
        this.trajectory.addPdrData(
                Traj.RelativePosition.newBuilder()
                        .setRelativeTimestamp(1)      // must be non-zero, otherwise it can be omitted in serialization
                        .setX(1e-4f)                  // tiny epsilon to avoid default 0 being omitted/filtered
                        .setY(0f)
                        .build()
        );

        // Initialize last known coordinates so 1Hz PDR logging can run even before the first step event
        lastCords = new float[]{0f, 0f};
        lastDisplayTrajectoryUpdateMs = 0L;
        ensureParticleFilterInitialized();
        syncFusedEstimateFromParticleFilter(System.currentTimeMillis());


        if (accelerometerSensor != null) this.trajectory.setAccelerometerInfo(createSensorInfo(accelerometerSensor));
        if (gyroscopeSensor != null) this.trajectory.setGyroscopeInfo(createSensorInfo(gyroscopeSensor));
        if (rotationSensor != null) this.trajectory.setRotationVectorInfo(createSensorInfo(rotationSensor));
        if (magnetometerSensor != null) this.trajectory.setMagnetometerInfo(createSensorInfo(magnetometerSensor));
        if (barometerSensor != null) this.trajectory.setBarometerInfo(createSensorInfo(barometerSensor));
        if (lightSensor != null) this.trajectory.setLightSensorInfo(createSensorInfo(lightSensor));

        // 4. Set Initial Position (Assignment 1 Requirement)
        if (startLocation != null && (startLocation[0] != 0 || startLocation[1] != 0)) {
            this.trajectory.setInitialPosition(Traj.GNSSPosition.newBuilder()
                    .setLatitude(startLocation[0])
                    .setLongitude(startLocation[1])
                    .build());
        }

        // 5. Schedule Data Recording Task
        if (storeTrajectoryTimer != null) {
            storeTrajectoryTimer.cancel();
        }
        this.storeTrajectoryTimer = new Timer();
        this.storeTrajectoryTimer.scheduleAtFixedRate(new storeDataInTrajectory(), 0, TIME_CONST);

        this.filter_coefficient = resolveConfiguredFilterCoefficient();
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
        if (saveRecording) {
            if (storeTrajectoryTimer != null) {
                storeTrajectoryTimer.cancel();
                storeTrajectoryTimer = null;
            }
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            this.saveRecording = false;
            this.wifiPositionRequestInFlight = false;
            this.pendingWifiPositionRequest = false;
            this.lastAppliedGnssMeasurementMs = 0L;

            if (appContext != null && trajectory != null) {
                try {
                    // Ensure startTimestamp is non-zero before building/uploading (server validates duration using it)
                    if (this.trajectory.getStartTimestamp() == 0L) {
                        this.trajectory.setStartTimestamp(absoluteStartTime);
                    }

                    syncTestPointsIntoTrajectory();
                    if (pendingRecordingName != null && !pendingRecordingName.trim().isEmpty()) {
                        this.trajectory.setTrajectoryId(pendingRecordingName.trim());
                    }
                    Traj.Trajectory finalTrajectory = this.trajectory.build();
                    Log.i("SensorFusion", "Finalizing trajectory with test_points=" + finalTrajectory.getTestPointsCount());

                    if (serverCommunications != null) {
                        final double minLocalUploadDurationSec = 5.0;
                        final double minServerUploadDurationSec = 30.0;
                        boolean shouldUploadToServer = true;
                        try {
                            if (finalTrajectory.getImuDataCount() > 1) {
                                long t0 = finalTrajectory.getImuData(0).getRelativeTimestamp();
                                long t1 = finalTrajectory.getImuData(finalTrajectory.getImuDataCount() - 1).getRelativeTimestamp();
                                double durationSec = (t1 - t0) / 1000.0;

                                if (durationSec < minLocalUploadDurationSec) {
                                    Log.w("SensorFusion", "Upload skipped: recording is shorter than "
                                            + minLocalUploadDurationSec + " seconds.");

                                    new Handler(Looper.getMainLooper()).post(() ->
                                            Toast.makeText(appContext,
                                                    "Recording too short (" + String.format(java.util.Locale.US, "%.1f", durationSec)
                                                            + "s). Please record at least "
                                                            + String.format(java.util.Locale.US, "%.0f", minLocalUploadDurationSec)
                                                            + "s before uploading.",
                                                    Toast.LENGTH_LONG).show()
                                    );
                                    this.trajectory = null; // clear stale trajectory so it cannot be re-uploaded
                                    return; // Stop here: do not upload or save
                                }

                                if (durationSec < minServerUploadDurationSec) {
                                    shouldUploadToServer = false;
                                    Log.w("SensorFusion", "Server upload skipped: recording is shorter than "
                                            + minServerUploadDurationSec + " seconds.");
                                    new Handler(Looper.getMainLooper()).post(() ->
                                            Toast.makeText(appContext,
                                                    "Recording saved locally. Server upload requires at least "
                                                            + String.format(java.util.Locale.US, "%.0f", minServerUploadDurationSec)
                                                            + "s of data.",
                                                    Toast.LENGTH_LONG).show()
                                    );
                                }
                            }
                        } catch (Exception e) {
                            Log.w("SensorFusion", "IMU duration check failed", e);
                        }

                        if (shouldUploadToServer) {
                            String selectedCampaign = VenueSelectionHelper.getSelectedCampaign(appContext);
                            Log.d("SensorFusion", "Uploading trajectory to campaign=" + selectedCampaign);
                            serverCommunications.sendTrajectory(finalTrajectory, selectedCampaign);
                        }
                    }
                    String fileName = buildRecordingFileName(absoluteStartTime);


                    File file = new File(appContext.getExternalFilesDir(null), fileName);
                    FileOutputStream fileOutputStream = new FileOutputStream(file);
                    fileOutputStream.write(finalTrajectory.toByteArray());
                    fileOutputStream.close();

                    Log.d("SensorFusion", "Trajectory saved: " + file.getAbsolutePath());
                } catch (Exception e) {
                    Log.e("SensorFusion", "Error saving trajectory", e);
                } finally {
                    pendingRecordingName = "";
                }
            }
        }

    }

    public void setPendingRecordingName(String recordingName) {
        if (recordingName == null) {
            pendingRecordingName = "";
        } else {
            pendingRecordingName = recordingName.trim();
        }
        if (trajectory != null && pendingRecordingName != null && !pendingRecordingName.isEmpty()) {
            trajectory.setTrajectoryId(pendingRecordingName);
        }
    }

    private String buildRecordingFileName(long startTimeMs) {
        String safeName = sanitizeRecordingNameForFile(pendingRecordingName);
        if (safeName.isEmpty()) {
            return "term_project_trajectory_" + startTimeMs + ".proto";
        }
        return "term_project_trajectory_" + safeName + "_" + startTimeMs + ".proto";
    }

    private String sanitizeRecordingNameForFile(String rawName) {
        if (rawName == null) {
            return "";
        }
        String trimmed = rawName.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String sanitized = Pattern.compile("[^\\p{L}\\p{N}._-]+")
                .matcher(trimmed)
                .replaceAll("_");
        sanitized = sanitized.replaceAll("_+", "_");
        sanitized = sanitized.replaceAll("^[_ .-]+|[_ .-]+$", "");
        return sanitized;
    }
    //endregion

    //region Trajectory object

    /**
     * Send the trajectory object to servers.
     *
     * @see ServerCommunications for sending and receiving data via HTTPS.
     */
    public void sendTrajectoryToCloud() {
        // Upload is handled in stopRecording() to avoid duplicate / inconsistent uploads.
        return;
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
    private Traj.SensorInfo createSensorInfo(MovementSensor sensor) {
        return Traj.SensorInfo.newBuilder()
                .setName(sensor.sensorInfo.getName())
                .setVendor(sensor.sensorInfo.getVendor())
                .setResolution(sensor.sensorInfo.getResolution())
                .setPower(sensor.sensorInfo.getPower())
                .setVersion(sensor.sensorInfo.getVersion())
                .setType(sensor.sensorInfo.getType())
                .build();
    }

    /**
     * Timer task to record data with the desired frequency in the trajectory class.
     *
     * Inherently threaded, runnables are created in {@link SensorFusion#startRecording()} and
     * destroyed in {@link SensorFusion#stopRecording()}.
     */
    private class storeDataInTrajectory extends TimerTask {
        @Override
        public void run() {
            if (!saveRecording || trajectory == null) return;

            // relative_timestamp is milliseconds from start_timestamp
            long relativeTime = System.currentTimeMillis() - absoluteStartTime;

            // Combine Acc, Gyro, Rotation, Steps into one IMUReading message
            Traj.IMUReading.Builder imuBuilder = Traj.IMUReading.newBuilder()
                    .setStepCount(stepCounter);
            imuBuilder.setRelativeTimestamp(relativeTime);


            if (accelerometerSensor != null && accelerometerSensor.values != null) {
                imuBuilder.setAcc(Traj.Vector3.newBuilder()
                        .setX(accelerometerSensor.values[0])
                        .setY(accelerometerSensor.values[1])
                        .setZ(accelerometerSensor.values[2]).build());
            }

            if (gyroscopeSensor != null && gyroscopeSensor.values != null) {
                imuBuilder.setGyr(Traj.Vector3.newBuilder()
                        .setX(gyroscopeSensor.values[0])
                        .setY(gyroscopeSensor.values[1])
                        .setZ(gyroscopeSensor.values[2]).build());
            }

            // Handle Rotation Vector (x,y,z,w)
            if (rotation != null && rotation.length >= 3) {

                float x = rotation[0];
                float y = rotation[1];
                float z = rotation[2];
                float w = (rotation.length > 3) ? rotation[3] : 1.0f;

                // Normalize quaternion to satisfy server tolerance (norm ~ 1)
                double norm = Math.sqrt((double) x * x + (double) y * y + (double) z * z + (double) w * w);
                if (norm > 1e-9) {
                    x /= norm;
                    y /= norm;
                    z /= norm;
                    w /= norm;

                    imuBuilder.setRotationVector(
                            Traj.Quaternion.newBuilder()
                                    .setX(x)
                                    .setY(y)
                                    .setZ(z)
                                    .setW(w)
                                    .build()
                    );
                }
            }


            // Add IMU reading to trajectory
            synchronized (trajectory) {
                trajectory.addImuData(imuBuilder.build());
            }

            //  Magnetometer Data
            if (magnetometerSensor != null && magnetometerSensor.values != null) {
                trajectory.addMagnetometerData(Traj.MagnetometerReading.newBuilder()
                        .setRelativeTimestamp(relativeTime) // timestamp for server duration
                        .setMag(Traj.Vector3.newBuilder()
                                .setX(magnetometerSensor.values[0])
                                .setY(magnetometerSensor.values[1])
                                .setZ(magnetometerSensor.values[2]).build())
                        .build());
            }

            //  Low Frequency Data (1Hz or slower)
            if (counter >= 100) { // 100 * 10ms = 1000ms = 1s
                counter = 0;
                // Record a 1Hz position point into pdr_data (write every second to guarantee >=30s duration)
                if (saveRecording && trajectory != null) {

                    float x = 0f;
                    float y = 0f;

                    // Prefer the latest coordinates produced by step-based PDR
                    if (lastCords != null && lastCords.length >= 2) {
                        x = lastCords[0];
                        y = lastCords[1];
                    } else {
                        // Fallback: ask PDR module for its current movement estimate
                        try {
                            if (pdrProcessing != null) {
                                float[] pdrMove = pdrProcessing.getPDRMovement();
                                if (pdrMove != null && pdrMove.length >= 2) {
                                    x = pdrMove[0];
                                    y = pdrMove[1];
                                }
                            }
                        } catch (Exception e) {
                            Log.w("SensorFusion", "Failed to read PDR movement", e);
                        }
                    }

                    // Use monotonic time since start (consistent with IMU relative timestamps)
                    long pdrTime = SystemClock.uptimeMillis() - bootTime;

                    // Avoid an all-default point (some servers may ignore/filter it)
                    if (pdrTime == 0) pdrTime = 1;
                    if (x == 0f && y == 0f) x = 1e-4f;

                    Traj.RelativePosition pdrPoint = Traj.RelativePosition.newBuilder()
                            .setRelativeTimestamp(pdrTime)
                            .setX(x)
                            .setY(y)
                            .build();

                    int pdrCount;
                    synchronized (trajectory) {
                        trajectory.addPdrData(pdrPoint);
                        pdrCount = trajectory.getPdrDataCount();
                    }

                }



                // Record Barometric Pressure
                if (barometerSensor != null && barometerSensor.values != null) {
                    trajectory.addPressureData(Traj.BarometerReading.newBuilder()
                            .setRelativeTimestamp(relativeTime) // timestamp for server duration
                            .setPressure(barometerSensor.values[0])
                            .build());
                }

                // Record Ambient Light
                if (lightSensor != null && lightSensor.values != null) {
                    trajectory.addLightData(Traj.LightReading.newBuilder()
                            .setRelativeTimestamp(relativeTime) // timestamp for server duration
                            .setLight(lightSensor.values[0])
                            .build());
                }

                // Record GNSS (Every 1s)
                if (latitude != 0 && longitude != 0) {
                    trajectory.addGnssData(Traj.GNSSReading.newBuilder()
                            .setPosition(Traj.GNSSPosition.newBuilder()
                                    .setRelativeTimestamp(relativeTime) // timestamp for server duration alignment
                                    .setLatitude(latitude)
                                    .setLongitude(longitude)
                                    .setAltitude(elevation)
                                    .build())
                            .build());

                }

                // Sub-cycle for WiFi AP Data (Every 5s approx)
                if (secondCounter >= 4) { // 5 * 1s = 5s
                    secondCounter = 0;

                    if (wifiList != null && !wifiList.isEmpty()) {

                        // Build Fingerprint (wifi_fingerprints type in Traj)
                        Traj.Fingerprint.Builder fpBuilder = Traj.Fingerprint.newBuilder()
                                .setRelativeTimestamp(relativeTime);

                        synchronized (trajectory) {
                            for (Wifi wifi : wifiList) {

                                // Keep existing aps_data
                                Traj.WiFiAPData.Builder wifiBuilder = Traj.WiFiAPData.newBuilder()
                                        .setMac(wifi.getBssid())
                                        .setSsid(wifi.getSsid())
                                        .setFrequency(wifi.getFrequency());
                                if (wifi.getRttFlag()) {
                                    wifiBuilder.setRttEnabled(true);
                                }
                                trajectory.addApsData(wifiBuilder.build());

                                // Write rf_scans -> wifi_fingerprints
                                Traj.RFScan.Builder scanBuilder = Traj.RFScan.newBuilder()
                                        .setRelativeTimestamp(relativeTime)
                                        .setMac(wifi.getBssid())
                                        .setRssi(wifi.getLevel());
                                fpBuilder.addRfScans(scanBuilder.build());
                            }

                            if (fpBuilder.getRfScansCount() > 0) {
                                trajectory.addWifiFingerprints(fpBuilder.build());
                            }

                        }

                    } else {
                    }

                } else {
                    secondCounter++;
                }

            } else {
                counter++;
            }
        }
    }

    //endregion

}
