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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.utils.FallDetectionService;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.PdrProcessing;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.presentation.fragment.SettingsFragment;


import org.ejml.simple.SimpleMatrix;
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
 * @author Stone Anderson
 * @author Sofea Jazlan Arif
 * @author Semih Vazgecen
 * @author Joseph Azrak
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
    // Fall detection service
    private FallDetectionService fallDetection;

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
    
    // Getter for bootTime to calculate relative timestamps
    public long getBootTime() {
        return bootTime;
    }
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
    // Track the provider of the last GNSS update
    private String lastGnssProvider = null;
    // Wifi values
    private List<Wifi> wifiList;


    // Over time accelerometer magnitude values since last step
    private List<Double> accelMagnitude;

    // PDR calculation class
    private PdrProcessing pdrProcessing;

    // Trajectory displaying class
    private PathView pathView;
    // WiFi positioning object
    private WiFiPositioning wiFiPositioning;
    private EKFSensorFusion ekf;
    private ParticleFilter particleFilter;

    private Float prevPDRX = null;
    private Float prevPDRY = null;
    // Temporary list for added tags
    private List<Traj.GNSS_Sample> taggedLocations = new ArrayList<>();


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
     * Initializes the Extended Kalman Filter (EKF) with the starting position.
     * @param startPosition - float of starting position [latitude, longitude]
     * @author Stone Anderson
     */
    public void initializeEKF(float[] startPosition) {

        float latitude = startPosition[0];
        float longitude = startPosition[1];
        // Convert start position (lat, lon) to (x, y)
        float[] initialXY = EKFSensorFusion.getTransformedCoordinate(new LatLng(latitude, longitude));
        // Create initial state vector (2x1 matrix)
        SimpleMatrix initialState = new SimpleMatrix(2, 1, true, new double[]{ initialXY[0], initialXY[1] });

        // Initialize covariance and noise matrices
        SimpleMatrix initialCovariance = SimpleMatrix.identity(2).scale(1.0);
        SimpleMatrix Q = SimpleMatrix.identity(2).scale(0.1);

        // Initialize GNSS and WiFi noise covariances
        SimpleMatrix R_gnss = SimpleMatrix.identity(2).scale(1.0);
        SimpleMatrix R_wifi = SimpleMatrix.identity(2).scale(1.0);

        // Instantiate EKF
        this.ekf = new EKFSensorFusion(initialState, initialCovariance, Q, R_gnss, R_wifi);
    }

    /**
     * Initializes the Particle Filter with the starting position.
     * Transforms initial coordinates (from GNSS to Wi-Fi) to a metric coordinate system and
     * creates a new ParticleFilter with the specified number of particles.
     *
     * @param startPosition An array containing the starting position as [latitude, longitude]
     * 
     * @author Sofea Jazlan Arif
     */
    public void initializeParticle(float[] startPosition)
    {
        float latitude = startPosition[0];
        float longitude = startPosition[1];

        // Convert GNSS (lat, lon) to a metric coordinate system (x, y)
        float[] initialXY = EKFSensorFusion.getTransformedCoordinate(new LatLng(latitude, longitude));

        this.particleFilter = new ParticleFilter(50, initialXY[0], initialXY[1]);
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

        //




        if(settings.getBoolean("overwrite_constants", false)) {
            this.filter_coefficient = Float.parseFloat(settings.getString("accel_filter", "0.96"));
        } else {
            this.filter_coefficient = FILTER_COEFFICIENT;
        }
        
        // Initialize the GNSS max error setting if it doesn't exist
        if (!settings.contains("gnss_max_error")) {
            SharedPreferences.Editor editor = settings.edit();
            editor.putString("gnss_max_error", "20"); // Default is 20 meters as a string
            editor.apply();
        }

        // Keep app awake during the recording (using stored appContext)
        PowerManager powerManager = (PowerManager) this.appContext.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag");

        fallDetection = new FallDetectionService(context);
        // Register fall detection listener
        fallDetection.setFallListener(() -> handleFallDetected());
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

        fallDetection.processAcceleration(acceleration[0], acceleration[1], acceleration[2], currentTime); // Send to FallDetection

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
            float accuracy = (float) location.getAccuracy();
            
            // Get the maximum allowable GNSS error from settings as a string and convert to float
            float maxGnssError = Float.parseFloat(settings.getString("gnss_max_error", "20"));
            
            // Filter out inaccurate GNSS readings
            if (accuracy > maxGnssError) {
                Log.w("SensorFusion", "Ignoring erroneous GNSS reading. Accuracy: " + 
                      accuracy + "m exceeds threshold: " + maxGnssError + "m");
                return; // Skip processing this location update
            }
            
            // If accuracy is acceptable, process the location
            latitude = (float) location.getLatitude();
            longitude = (float) location.getLongitude();
            float altitude = (float) location.getAltitude();
            float speed = (float) location.getSpeed();
            String provider = location.getProvider();
            
            // Store the provider for filtering out tag data in position calculation
            lastGnssProvider = provider;
            
            if(saveRecording) {
                trajectory.addGnssData(Traj.GNSS_Sample.newBuilder()
                        .setAccuracy(accuracy)
                        .setAltitude(altitude)
                        .setLatitude(latitude)
                        .setLongitude(longitude)
                        .setSpeed(speed)
                        .setProvider(provider)
                        .setRelativeTimestamp(System.currentTimeMillis()-absoluteStartTime));
            }
        }
    }

    // Semih - ADD
    /**
     * Handles the event when a fall is detected by the fall detection service.
     * This method is called as a callback from the FallDetectionService when
     * acceleration patterns indicate a possible fall event.
     * 
     * @author Semih Vazgecen
     */
    private void handleFallDetected() {
        Log.e("SensorFusion", "FALL DETECTED! Sending alert...");
    }

    /**
     * Sets the initial position of the trajectory using the provided latitude and longitude.
     *
     * @param lat   The latitude of the initial position.
     * @param longi The longitude of the initial position.
     */
    /**
     * Sets the initial position of the trajectory using the provided latitude and longitude.
     * This method creates an Initial_Pos builder with the given coordinates and sets it
     * in the trajectory builder.
     *
     * @param lat The latitude of the initial position
     * @param longi The longitude of the initial position
     * 
     * @author Semih Vazgecen
     */
    public void writeInitialPositionToTraj(float lat, float longi)
    {
        trajectory.setInitialPos(createInitialPosBuilder(lat, longi));
    }
    // Semih - END


    long lastUpdateTime = 0;
    long updateIntervalMs = 2000; // Update every 2 seconds
    double movementThreshold = 0.01; // Define a meaningful movement threshold

    /**
     * Updates the Extended Kalman Filter with new sensor data (PDR, WIFI and GNSS) to estimate new position
     * This update only occurs every 2 seconds, if there is pdr displacement, and if ekf object is not null
     * @author Stone Anderson
     */
    public void updateEKF() {

        // log time so updateEKF is called only every 2 seconds
        long currentTime = SystemClock.uptimeMillis();
        if (currentTime - lastUpdateTime < updateIntervalMs) {
            return;
        }

        // Get PDR measurement, compute displacement and only updateEKF if movement has been detected
        float[] pdrXY = pdrProcessing.getPDRMovement();
        double pdrMovement = Math.sqrt(pdrXY[0] * pdrXY[0] + pdrXY[1] * pdrXY[1]);
        if (pdrMovement < movementThreshold) {
            return;
        }

        // makes sure ekf is initialised
        if (ekf == null) return;

        // get GNSS data and convert it to (x,y)
        float[] gnssLatLong = getGNSSLatitude(false);
        if (lastGnssProvider != null && "fusion".equals(lastGnssProvider)) {
            Log.d("SensorFusion", "Skipping EKF update with tag data");
            return;
        }
        float[] gnssXY = EKFSensorFusion.getTransformedCoordinate(new LatLng(gnssLatLong[0], gnssLatLong[1]));


        // Get WiFi data (if available) and convert it to (x,y)
        LatLng wifiLatLng = getLatLngWifiPositioning();
        float[] wifiXY;
        if (wifiLatLng != null) {
            wifiXY = EKFSensorFusion.getTransformedCoordinate(wifiLatLng);
        } else {
            wifiXY = gnssXY; // if WiFi data is unavailable, use GNSS coordinates
        }

        // Get PDR data
        pdrXY = pdrProcessing.getPDRMovement();

        // Convert measurements to 2x1 SimpleMatrix objects
        SimpleMatrix z_gnss = new SimpleMatrix(2, 1, true, new double[]{ gnssXY[0], gnssXY[1] });
        SimpleMatrix z_wifi = new SimpleMatrix(2, 1, true, new double[]{ wifiXY[0], wifiXY[1] });
        SimpleMatrix z_pdr = new SimpleMatrix(2, 1, true, new double[]{ pdrXY[0], pdrXY[1] });


        // Use latest PDR data for prediction.
        ekf.predictWithPDR(z_pdr);

        // Use GNSS and WiFi data for update
        ekf.updateBatch(z_gnss, z_wifi);

        // track time
        lastUpdateTime = currentTime;

        // get estimate location
        LatLng fusedLocation = getEKFStateAsLatLng();

        // store estimate fused location for replay purposes
        if (saveRecording) {
            addFusedPosition(fusedLocation, SystemClock.uptimeMillis() - bootTime, "EKF");
        }

    }

    // Add a flag to prevent concurrent updates
    private volatile boolean isParticleUpdating = false;
    private ParticleAttribute lastParticlePosition = null;
    
    /**
     * Updates the Particle Filter with new sensor measurements.
     * This method calculates the movement deltas from PDR and uses GNSS and WiFi
     * positioning data to update the particle filter. It implements thread safety
     * by using a flag to prevent concurrent updates.
     * 
     * The process involves:
     * 1. Getting GNSS and WiFi position data
     * 2. Calculating PDR movement deltas since the last update
     * 3. Running the particle filter with these inputs
     * 4. Storing and returning the estimated position
     *
     * @return The estimated position as a ParticleAttribute or null if filter hasn't been initialized
     * 
     * @author Sofea Jazlan Arif
     */
    public ParticleAttribute updateParticle(){
        Log.d("Particle Filter", "UPDATES PARTICLE = " );
        if (particleFilter == null) return null;
        
        // If an update is already in progress, return the last position
        if (isParticleUpdating) {
            Log.d("Particle Filter", "Skipping update - already in progress");
            return lastParticlePosition;
        }
        
        // Set flag to prevent concurrent updates
        isParticleUpdating = true;
        
        try {
            // Check if this is real GNSS data (not a user tag)
            if (lastGnssProvider != null && isUserTagProvider(lastGnssProvider)) {
                Log.d("SensorFusion", "Skipping Particle update with tag data");
                return lastParticlePosition;
            }
            
            float[] gnssLatLong = getGNSSLatitude(false);
            float[] gnssXY = EKFSensorFusion.getTransformedCoordinate(new LatLng(gnssLatLong[0], gnssLatLong[1]));
    
            LatLng wifiLatLng = getLatLngWifiPositioning();
            float[] wifiXY;
            if (wifiLatLng != null) {
                wifiXY = EKFSensorFusion.getTransformedCoordinate(wifiLatLng);
            } else {
                wifiXY = gnssXY;
            }
    
            float wifiLat = wifiXY[0];
            float wifiLon = wifiXY[1];
    
            // get PDR location for step prediction
            float[] pdrXY = pdrProcessing.getPDRMovement();
    
            Log.d("PDR Particle Filter", "PDR X " + pdrXY[0] + " PDR Y: " + pdrXY[1]);
    
            float gnssLat = gnssXY[0];
            float gnssLon = gnssXY[1];
    
            float deltaX, deltaY;
    
            if (prevPDRX == null || prevPDRY == null){
                deltaX = pdrXY[0] - 0;
                deltaY = pdrXY[1] - 0;
                Log.d("PDR Particle Filter", "Initial X " + gnssLat + " Initial Y: " + gnssLon);
                Log.d("PDR Particle Filter", "Initial PDR X " + pdrXY[0] + " PDR Y: " + pdrXY[1]);
                Log.d("PDR Particle Filter", "Initial delta PDR X " + deltaX + " delta PDR Y: " + deltaY);
            } else {
                deltaX = pdrXY[0] - prevPDRX;
                deltaY = pdrXY[1] - prevPDRY;
            }
    
            prevPDRX = pdrXY[0];
            prevPDRY = pdrXY[1];
    
            Log.d("PDR Particle Filter", "PDR X " + pdrXY[0] + " PDR Y: " + pdrXY[1]);
            Log.d("PDR Particle Filter", "delta PDR X " + deltaX + " delta PDR Y: " + deltaY);
    
            lastParticlePosition = runParticleFilter(wifiLat, wifiLon, 5, deltaX, deltaY);
    
            return lastParticlePosition;
        } finally {
            // Always clear the flag when done
            isParticleUpdating = false;
        }
    }

    /**
     * Runs the particle filter algorithm for a specified number of iterations.
     * This method performs the predict-update-resample cycle of particle filtering
     * to refine position estimates based on movement data and reference positions.
     *
     * @param startX The x-coordinate of the reference position (usually from WiFi or GNSS)
     * @param startY The y-coordinate of the reference position
     * @param numIterations The number of iterations to run the filter
     * @param deltaX The change in x-coordinate since the last update (from PDR)
     * @param deltaY The change in y-coordinate since the last update (from PDR)
     * @return The estimated position after running the particle filter
     * 
     * @author Sofea Jazlan Arif
     */
    private ParticleAttribute runParticleFilter(float startX, float startY, int numIterations, float deltaX, float deltaY) {
        particleFilter.setPosition(startX, startY);

        float prevEstX = particleFilter.getEstimatedPosition().lat;
        float prevEstY = particleFilter.getEstimatedPosition().lon;

        for (int i = 0; i < numIterations; i++) {
            // Predict the new positions of particles
            particleFilter.predict(deltaX, deltaY);

            // Update weights using the reference position
            particleFilter.updateWeights(startX, startY);

            // Resample to refine estimates
            particleFilter.resample(startX, startY);
        }



        // Get the estimated position after 10 iterations
        return particleFilter.getEstimatedPosition();
    }

    /// END


    /**
     * Gets the current state of the Extended Kalman Filter as latitude, longitude coordinates
     * @return A LatLng object of coordinates of the current EKF state
     * @author Stone Anderson
     */
    public LatLng getEKFStateAsLatLng() {
        if (ekf == null) return null;
        SimpleMatrix state = ekf.getState();
        float x = (float) state.get(0, 0);
        float y = (float) state.get(1, 0);
        return ekf.getInverseTransformedCoordinate(x, y, 30, true);
    }
    
    /**
     * Returns the accuracy estimate of the EKF position in meters.
     * This represents the uncertainty in the position estimate.
     * 
     * @return The position accuracy in meters, or NaN if EKF is not initialized
     * 
     * @author
     */
    public double getEKFPositionAccuracy() {
        if (ekf == null) return Double.NaN;
        return ekf.getPositionAccuracy();
    }

    /**
     * Converts the Particle Filter estimated position to geographic coordinates.
     * Takes a ParticleAttribute containing position in the metric coordinate system
     * and converts it back to latitude and longitude.
     *
     * @param estimatedPos The estimated position from the Particle Filter in metric coordinates
     * @return A LatLng object containing the geographic coordinates of the position,
     *         or null if the Particle Filter hasn't been initialized
     * 
     * @author Sofea Jazlan Arif
     */
    public LatLng getParticleStateAsLatLng(ParticleAttribute estimatedPos) {
        if (particleFilter == null) return null;
        ParticleAttribute estimatedLoc = estimatedPos;
        // Convert x,y back to latitude and longitude.
        // For example, for Scotland, UTM zone 30 in the Northern Hemisphere is typically used.
        return ekf.getInverseTransformedCoordinate( (float)estimatedLoc.lat,  (float)estimatedLoc.lon, 30, true);
    }
    
    /**
     * Returns the accuracy estimate of the Particle Filter position in meters.
     * This is based on the dispersion of particles and represents uncertainty.
     * 
     * @return The position accuracy in meters, or NaN if Particle Filter is not initialized
     * 
     * @author Sofea Jazlan Arif
     */
    public double getParticlePositionAccuracy() {
        if (particleFilter == null) return Double.NaN;
        return particleFilter.getPositionAccuracy();
    }




    /**
     * Adds a fused position to the trajectory.
     * Creates a Fused_Pos object with the given location, timestamp, and algorithm information
     * and adds it to the trajectory being recorded.
     *
     * @param fusedLocation The geographic coordinates of the fused position
     * @param relativeTimestamp The relative timestamp when the position was calculated
     * @param algorithm The name of the fusion algorithm used (e.g., "EKF", "Particle")
     * 
     * @author Semih Vazgecen, Stone Anderson
     */
    public void addFusedPosition(LatLng fusedLocation, long relativeTimestamp, String algorithm) {
        Traj.Fused_Pos fusedPos = Traj.Fused_Pos.newBuilder()
                .setRelativeTimestamp(relativeTimestamp)
                .setLatitude((float) fusedLocation.latitude)
                .setLongitude((float) fusedLocation.longitude)
                .setAlgorithm(algorithm)
                .build();

        trajectory.addFusedPos(fusedPos);

        Log.d("SensorFusion", "FUSED LAT: " + fusedLocation.latitude +
                " FUSED LON: " + fusedLocation.longitude +
                " ALGORITHM: " + algorithm);
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
        Log.d("SensorFusion", "update(Object[] wifiList) called. Received " + wifiList.length + " items.");
        this.wifiList = Stream.of(wifiList).map(o -> (Wifi) o).collect(Collectors.toList());

        if(this.saveRecording) {
            Traj.WiFi_Sample.Builder wifiData = Traj.WiFi_Sample.newBuilder()
                    .setRelativeTimestamp(SystemClock.uptimeMillis()-bootTime);
            for (Wifi data : this.wifiList) {
                wifiData.addMacScans(Traj.Mac_Scan.newBuilder()
                        .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                        .setMac(data.getBssid()).setRssi(data.getLevel()));
            }

            // Semih - NEW
            // Get Wifi Position
            LatLng wifiPosition = getLatLngWifiPositioning();

            // If the user inside a WiFi positioning-supported building
            // Write the wifi-positioning data to the trajectory
            if (wifiPosition == null) {
                Log.e("SensorFusion", "WiFi Positioning returned null! Skipping position update.");
            } else {
                Traj.Position position = Traj.Position.newBuilder()
                        .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                        .setLatitude((float) wifiPosition.latitude)
                        .setLongitude((float) wifiPosition.longitude)
                        .setFloor(getWifiFloor())
                        .build();

                wifiData.setPosition(position);
                Log.d("SensorFusion", "WIFI LAT: " + wifiPosition.latitude + " WIFI LON: " + wifiPosition.longitude + " FLOOR:" + getWifiFloor());
            }
            // Adding WiFi data to Trajectory
            this.trajectory.addWifiData(wifiData);
            // Semih - END
        }
        createWifiPositioningRequest();
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

                    // Print out the WiFi position using Android logging
                    Log.d("WiFiPositioning", "Received WiFi location: " + wifiLocation.toString() + ", floor: " + floor);
                    // Optionally, show a Toast message for quick feedback
                    Toast.makeText(appContext, "WiFi Location: " + wifiLocation.toString() + " on floor: " + floor, Toast.LENGTH_LONG).show();
                    // Handle the success response
                }

                @Override
                public void onError(String message) {
                    // Handle the error response

                    Log.e("WiFiPositioning", "Error retrieving WiFi position: " + message);
                    Toast.makeText(appContext, "Error retrieving WiFi position: " + message, Toast.LENGTH_LONG).show();

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
     * Gets the latest GNSS coordinates, but specifically excludes tag points.
     * This method should be used for positioning calculations.
     * 
     * @return longitude and latitude data in a float[2].
     */
    /**
     * Gets the latest GNSS coordinates, specifically excluding tag points.
     * This method should be used for positioning calculations where tag points
     * should not be considered as actual measurement data.
     * 
     * @return A float array containing [latitude, longitude] from the system location service
     * 
     * @author
     */
    public float[] getRealGNSSLatitude() {
        // Just return the regular GNSS coordinates which come from the system location service
        // and are never tag points (which only exist in the trajector's gnssData list)
        float[] latLong = new float[2];
        latLong[0] = latitude;
        latLong[1] = longitude;
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
        Log.d("SensorFusion", "wifiProcessor.startListening() called - WiFi scanning started.");

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
        // Protobuf trajectory class for sending sensor data to restful API
        this.trajectory = Traj.Trajectory.newBuilder()
                .setAndroidVersion(Build.VERSION.RELEASE)
                .setStartTimestamp(absoluteStartTime)
                .setAccelerometerInfo(createInfoBuilder(accelerometerSensor))
                .setGyroscopeInfo(createInfoBuilder(gyroscopeSensor))
                .setMagnetometerInfo(createInfoBuilder(magnetometerSensor))
                .setBarometerInfo(createInfoBuilder(barometerSensor))
                .setLightSensorInfo(createInfoBuilder(lightSensor));

        initializeEKF(startLocation);
        initializeParticle(startLocation);

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
                .setType(sensor.sensorInfo.getType());
    }


    /**
     * Creates and returns a builder for the initial position of the trajectory.
     * This method is used to set the initial coordinates when starting a recording.
     *
     * @param lat The latitude of the initial position
     * @param longi The longitude of the initial position
     * @return A builder instance with the specified latitude and longitude set
     * 
     * @author Semih Vazgecen
     */
    public Traj.Initial_Pos.Builder createInitialPosBuilder(float lat, float longi)
    {
        return Traj.Initial_Pos.newBuilder().setInitialLatitude(lat).setInitialLongitude(longi);
    }

    /**
     * Adds a tag point to a temporary list, which is then saved to the trajectory's GNSS
     * data once the recording is complete. This method is used to mark important positions 
     * during recording.
     * 
     * @param latitude The latitude of the tagged position
     * @param longitude The longitude of the tagged position
     * @param altitude The altitude of the tagged position
     * @param relativeTimestamp The relative timestamp of when the tag was created
     * 
     * @author Joseph Azrak, Semih Vazgecen
     */
    public void addTagToTrajectory(float latitude, float longitude, float altitude, long relativeTimestamp) {
        if (saveRecording && trajectory != null) {
            Traj.GNSS_Sample tag = Traj.GNSS_Sample.newBuilder()
                    .setAccuracy(0) // Not applicable for manually added tag
                    .setAltitude(altitude)
                    .setLatitude(latitude)
                    .setLongitude(longitude)
                    .setSpeed(0) // Not applicable for manually added tag
                    .setProvider("fusion")
                    .setRelativeTimestamp(relativeTimestamp)
                    .build();

            taggedLocations.add(tag);
        } else {
            Log.e("SensorFusion", "Cannot add tag: Not recording or trajectory is null");
        }
    }
    // A function to remove desired tags from the temporary list
    /**
     * Removes a tag from the temporary list of tagged locations.
     * Searches for a tag with the specified timestamp and removes it from the list.
     *
     * @param tagTimestamp The timestamp of the tag to be removed
     * 
     * @author Semih Vazgecen, Joseph Azrak
     */
    public void removeTag(long tagTimestamp) {
        // Find the tag in the list and remove it
        for (int i = 0; i < taggedLocations.size(); i++) {
            Traj.GNSS_Sample tag = taggedLocations.get(i);
            if (tag.getRelativeTimestamp() == tagTimestamp) {
                taggedLocations.remove(i);  // Remove the tag from the list
                Log.d("SensorFusion", "Tag with timestamp " + tagTimestamp + " has been successfully removed");
                break;
            }
        }
    }

    /**
     * Checks if a given provider is a user tag.
     * Used to filter out user tags in sensor fusion algorithms since they
     * should not be treated as actual sensor measurements.
     * 
     * @param provider The provider string to check
     * @return true if this is a user tag provider, false otherwise
     * 
     * @author Semih Vazgecen, Joseph Azrak
     */
    private boolean isUserTagProvider(String provider) {
        return provider != null && "fusion".equals(provider);
    }

    // Commits the tags in the temporary list to the trajectory's GNSS data array
    /**
     * Commits the tags in the temporary list to the trajectory's GNSS data.
     * This method should be called when finalizing the trajectory, typically
     * when stopping recording.
     * 
     * @author Semih Vazgecen, Joseph Azrak
     */
    public void saveTagsToTrajectory() {
        for (Traj.GNSS_Sample tag : taggedLocations) {
            trajectory.addGnssData(tag);
            Log.d("SensorFusion", "Tag with timestamp: " + tag.getRelativeTimestamp() + " has been successfully committed");
        }
        taggedLocations.clear(); // Clear after saving
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
            trajectory.addImuData(Traj.Motion_Sample.newBuilder()
                            .setRelativeTimestamp(SystemClock.uptimeMillis()-bootTime)
                            .setAccX(acceleration[0])
                            .setAccY(acceleration[1])
                            .setAccZ(acceleration[2])
                            .setGyrX(angularVelocity[0])
                            .setGyrY(angularVelocity[1])
                            .setGyrZ(angularVelocity[2])
                            .setGyrZ(angularVelocity[2])
                            .setRotationVectorX(rotation[0])
                            .setRotationVectorY(rotation[1])
                            .setRotationVectorZ(rotation[2])
                            .setRotationVectorW(rotation[3])
                            .setStepCount(stepCounter))
                    .addPositionData(Traj.Position_Sample.newBuilder()
                            .setMagX(magneticField[0])
                            .setMagY(magneticField[1])
                            .setMagZ(magneticField[2])
                            .setRelativeTimestamp(SystemClock.uptimeMillis()-bootTime))
//                    .addGnssData(Traj.GNSS_Sample.newBuilder()
//                            .setLatitude(latitude)
//                            .setLongitude(longitude)
//                            .setRelativeTimestamp(SystemClock.uptimeMillis()-bootTime))
            ;

            // Divide timer with a counter for storing data every 1 second
            if (counter == 99) {
                counter = 0;
                // Store pressure and light data
                if (barometerSensor.sensor != null) {
                    trajectory.addPressureData(Traj.Pressure_Sample.newBuilder()
                                    .setPressure(pressure)
                                    .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime))
                            .addLightData(Traj.Light_Sample.newBuilder()
                                    .setLight(light)
                                    .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                                    .build());
                }

                // Divide the timer for storing AP data every 5 seconds
                if (secondCounter == 4) {
                    secondCounter = 0;
                    //Current Wifi Object
                    Wifi currentWifi = wifiProcessor.getCurrentWifiData();
                    trajectory.addApsData(Traj.AP_Data.newBuilder()
                            .setMac(currentWifi.getBssid())
                            .setSsid(currentWifi.getSsid())
                            .setFrequency(currentWifi.getFrequency()));
                    //Log.d("SensorFusion", "APS_DATA: " + currentWifi.getBssid() + ", " + currentWifi.getSsid() + ", " + currentWifi.getFrequency());
                }
                else {
                    secondCounter++;
                }
                
                // Run fusion algorithms in a separate thread to avoid UI lag
                new Thread(() -> {
                    updateEKF();
                    updateParticle();
                }).start();
            }
            else {
                counter++;
            }

        }
    }

    //endregion

}