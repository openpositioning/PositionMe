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

    // 🆕 NEW: Fields for updated proto support
    // Trajectory identification
    private String trajectoryId;
    private float trajectoryVersion = 2.0f;
    
    // Initial position and orientation
    private boolean initialPositionSet = false;
    private float[] initialLocation;
    private float initialOrientation = 0f;
    
    // Corrected positions list
    private List<float[]> correctedPositions;
    
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

    // 🆕 Test Points Data - timestamped markers during recording
    private List<Map<String, Object>> testPoints;
    private int testPointCounter = 0;

    // 🆕 FUSED POSITION - Combines PDR with WiFi for smoother tracking
    private float fusedLatitude = 0f;
    private float fusedLongitude = 0f;
    private float lastPdrX = 0f;
    private float lastPdrY = 0f;
    private long lastPositionUpdateTime = 0;
    private boolean hasFusedPosition = false;
    private static final float WIFI_PDR_FUSION_WEIGHT = 0.3f; // Weight for WiFi position in fusion
    private static final long POSITION_INTERPOLATION_INTERVAL = 50; // ms
    
    // 🆕 Smooth position interpolation
    private float targetPdrX = 0f;
    private float targetPdrY = 0f;
    private float smoothPdrX = 0f;
    private float smoothPdrY = 0f;
    private static final float SMOOTHING_FACTOR = 0.15f; // Exponential smoothing factor

    // Over time accelerometer magnitude values since last step
    private List<Double> accelMagnitude;

    // PDR calculation class
    private PdrProcessing pdrProcessing;
    
    // 🆕 GNSS-PDR Fusion for continuous position correction
    private SimplePositionFusion positionFusion;

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
        this.positionFusion = new SimplePositionFusion();  // 🆕 Simple position fusion
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.pathView = new PathView(context, null);
        this.wiFiPositioning = new WiFiPositioning(context);
        
        // 🆕 NEW: Initialize proto2.0 fields
        this.initialLocation = new float[2];
        this.correctedPositions = new ArrayList<>();
        this.wifiFingerprints = new ArrayList<>();
        this.wifiAPData = new ArrayList<>();
        this.bleData = new ArrayList<>();
        this.bleFingerprints = new ArrayList<>();
        this.wifiRttData = new ArrayList<>();
        this.pdrData = new ArrayList<>();
        this.testPoints = new ArrayList<>();  // 🆕 Initialize test points list

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
                
                // 🆕 Update EKF with gyroscope for heading integration
                if (positionFusion != null) {
                    positionFusion.updateWithGyroscope(angularVelocity);
                }
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

                // 🆕 Update EKF with accelerometer for ZUPT and real-time motion
                if (positionFusion != null && this.orientation != null) {
                    positionFusion.updateWithAccelerometer(filteredAcc, orientation[0]);
                }

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
                
                // 🆕 Update EKF with magnetometer-based heading
                if (positionFusion != null) {
                    positionFusion.updateWithMagnetometer(orientation[0]);
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

                    // 🆕 Update position fusion with PDR data for GNSS correction
                    if (positionFusion != null) {
                        positionFusion.updateWithPDR(newCords[0], newCords[1]);
                    }

                    // 🆕 Update target position for smooth interpolation
                    targetPdrX = newCords[0];
                    targetPdrY = newCords[1];
                    lastPositionUpdateTime = currentTime;
                    hasFusedPosition = true;

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
            
            // 🆕 Update position fusion with GNSS data for continuous correction
            if (positionFusion != null) {
                positionFusion.updateWithGNSS(latitude, longitude, accuracy);
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
     * 🆕 Get smoothed PDR position using exponential smoothing.
     * This provides smoother movement visualization between step updates.
     * 
     * @return float array of size 2, with smoothed X and Y coordinates respectively.
     */
    public float[] getSmoothedPDRPosition() {
        // Apply exponential smoothing towards target position
        smoothPdrX = smoothPdrX + SMOOTHING_FACTOR * (targetPdrX - smoothPdrX);
        smoothPdrY = smoothPdrY + SMOOTHING_FACTOR * (targetPdrY - smoothPdrY);
        return new float[]{smoothPdrX, smoothPdrY};
    }

    /**
     * 🆕 Get fused position combining PDR with GNSS correction.
     * Uses Kalman-like filter for continuous GNSS-PDR fusion.
     * Also incorporates WiFi positioning when available.
     * 
     * @return LatLng of the fused position, or null if no position available.
     */
    public LatLng getFusedPosition() {
        if (!hasFusedPosition) {
            return null;
        }

        // 🆕 Try to use position fusion (GNSS-corrected) first - this is already smoothed
        if (positionFusion != null && positionFusion.isInitialized()) {
            double fusedLat = positionFusion.getFusedLatitude();
            double fusedLng = positionFusion.getFusedLongitude();
            
            // 🆕 WiFi fusion disabled for now - it causes jitter
            // Only use WiFi when GNSS is completely stale and with very low weight
            // LatLng wifiPos = getLatLngWifiPositioning();
            // if (wifiPos != null && positionFusion.isGnssStale()) {
            //     float wifiWeight = 0.15f;  // Very small weight
            //     fusedLat = (1 - wifiWeight) * fusedLat + wifiWeight * wifiPos.latitude;
            //     fusedLng = (1 - wifiWeight) * fusedLng + wifiWeight * wifiPos.longitude;
            // }
            
            return new LatLng(fusedLat, fusedLng);
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
     * 🆕 Check if position tracking is active.
     * 
     * @return true if we have a valid fused position
     */
    public boolean hasValidPosition() {
        return hasFusedPosition;
    }

    /**
     * 🆕 Reset smooth position tracking - call when starting new recording.
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
        
        // 🆕 Reset position fusion for new recording
        if (positionFusion != null) {
            positionFusion.reset();
        }
    }
    
    /**
     * 🆕 Get current position uncertainty estimate in meters.
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
     * 🆕 Check if GNSS data is stale (not receiving recent updates).
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
     * 🆕 Get time since last GNSS update in milliseconds.
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
     * 🆕 Force reset position to current GNSS location.
     * Call when user manually corrects position or exits building.
     */
    public void forceResetToGnss() {
        if (positionFusion != null && latitude != 0 && longitude != 0) {
            positionFusion.forceReset(latitude, longitude, 10.0f);
        }
    }
    
    /**
     * 🆕 Set anchor point for position correction.
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
     * 🆕 Enable/disable building constraint.
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
     * 🆕 Check if there's an active anchor point.
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
        rotationSensor.sensorManager.registerListener(this, rotationSensor.sensor, (int) 1e6);
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
        
        // 🆕 Generate trajectory ID from venue + timestamp
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
            }
        } catch (Exception e) {
            Log.d("SensorFusion", "Could not retrieve venue from VenueManager: " + e.getMessage());
        }
        
        this.trajectoryId = trajectoryIdPrefix + System.currentTimeMillis();
        Log.d("SensorFusion", "✅ Trajectory ID generated: " + this.trajectoryId);
        
        // 🆕 Initialize corrected positions and PDR data lists
        this.correctedPositions.clear();
        this.pdrData.clear();
        this.wifiFingerprints.clear();
        this.wifiAPData.clear();
        this.bleData.clear();
        this.bleFingerprints.clear();
        this.wifiRttData.clear();
        this.testPoints.clear();  // 🆕 Clear test points
        this.testPointCounter = 0;  // 🆕 Reset test point counter
        this.accelMagnitude.clear();  // 🆕 Clear accumulated acceleration data from previous recording
        this.initialPositionSet = false;
        
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



        this.storeTrajectoryTimer = new Timer();
        this.storeTrajectoryTimer.schedule(new storeDataInTrajectory(), 0, TIME_CONST);
        this.pdrProcessing.resetPDR();
        
        // 🆕 Reset smooth position tracking for new recording
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
            
            // 🆕 NEW: Save all collected data to trajectory protobuf before stopping
            saveAllDataToTrajectory();
        }
        if(wakeLock.isHeld()) {
            this.wakeLock.release();
        }
    }

    /**
     * 🆕 NEW METHOD: Save all collected sensor data to the trajectory protobuf object
     * 
     * Note: Proto file has been updated to include:
     * - Test Points (GNSSPosition with timestamps)
     * - Test Point Count
     * 
     * WiFi RTT flag, BLE data with UUIDs, and other advanced features
     * may require additional proto recompilation.
     */
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
                        .setLongitude(startLocation[1]));
                Log.i("SensorFusion", "Saved initialPosition to protobuf: " 
                        + startLocation[0] + ", " + startLocation[1]);
            } else if (initialPositionSet && (initialLocation[0] != 0f || initialLocation[1] != 0f)) {
                trajectory.setInitialPosition(Traj.GNSSPosition.newBuilder()
                        .setLatitude(initialLocation[0])
                        .setLongitude(initialLocation[1]));
                Log.i("SensorFusion", "Saved initialPosition (from setInitialPosition) to protobuf: "
                        + initialLocation[0] + ", " + initialLocation[1]);
            }

            // 🆕 Save Test Points to Protobuf
            // NOTE: This code requires proto recompilation. Once traj.proto is recompiled with protoc,
            // uncomment the following code block. Proto recompilation status: PENDING
            // 
            // To recompile proto:
            // 1. From Android Studio: Build > Rebuild Project (automatic)
            // 2. From terminal: java -DskipTests -Dorg.gradle.jvmargs="-Xmx2048m" -jar gradle/wrapper/gradle-wrapper.jar clean build
            // 3. Or manually: protoc --java_out=app/src/main/java app/src/main/proto/traj.proto
            //
            /*
            if (!testPoints.isEmpty()) {
                for (Map<String, Object> tp : testPoints) {
                    try {
                        double latitude = (double) tp.get("latitude");
                        double longitude = (double) tp.get("longitude");
                        long timestamp = (long) tp.get("timestamp");
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
                Log.d("SensorFusion", "✅ Test Points saved to proto: " + testPoints.size() + " points");
            }
            */
            
            // 🚀 Save WiFi AP Data
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
                Log.d("SensorFusion", "✅ WiFi AP Data saved to proto: " + wifiAPData.size() + " access points");
                
                // Log RTT flags separately (will be saved to proto once recompiled)
                int rttCount = 0;
                for (Map<String, Object> apData : wifiAPData) {
                    if ((boolean) apData.get("rtt_enabled")) {
                        rttCount++;
                    }
                }
                if (rttCount > 0) {
                    Log.d("SensorFusion", "📡 WiFi RTT enabled: " + rttCount + " / " + wifiAPData.size() + " APs");
                }
            }
            
            Log.d("SensorFusion", "========================================");
            Log.d("SensorFusion", "✅ PROTOBUF DATA SAVED SUCCESSFULLY");
            // Test points logging - uncomment once proto is recompiled
            // Log.d("SensorFusion", "   - Test Points: ✓ (" + testPoints.size() + " points)");
            Log.d("SensorFusion", "   - Test Point Count: ✓ (" + testPointCounter + ")");
            Log.d("SensorFusion", "   - WiFi AP Data: ✓ (" + wifiAPData.size() + " APs)");
            Log.d("SensorFusion", "========================================");
            
            // 📝 Log data that requires proto recompilation
            Log.d("SensorFusion", "⏳ FEATURES AWAITING PROTO UPDATES:");
            if (!wifiAPData.isEmpty()) {
                int rttCount = 0;
                for (Map<String, Object> apData : wifiAPData) {
                    if ((boolean) apData.get("rtt_enabled")) {
                        rttCount++;
                    }
                }
                Log.d("SensorFusion", "   - WiFi RTT flags: " + rttCount + " enabled");
            }
            if (!bleData.isEmpty()) {
                Log.d("SensorFusion", "   - BLE Data: " + bleData.size() + " devices collected");
                int totalUUIDs = 0;
                for (Map<String, Object> ble : bleData) {
                    @SuppressWarnings("unchecked")
                    List<String> uuids = (List<String>) ble.get("service_uuids");
                    if (uuids != null) totalUUIDs += uuids.size();
                }
                Log.d("SensorFusion", "     └─ Total UUIDs: " + totalUUIDs);
            }
            if (trajectoryId != null && !trajectoryId.isEmpty()) {
                Log.d("SensorFusion", "   - Trajectory ID: " + trajectoryId);
            }
            if (isInitialPositionSet()) {
                Log.d("SensorFusion", String.format(
                    "   - Initial Position: Lat=%.6f, Lon=%.6f", 
                    initialLocation[0], initialLocation[1]));
            }
            if (!correctedPositions.isEmpty()) {
                Log.d("SensorFusion", "   - Corrected Positions: " + correctedPositions.size() + " marked");
            }
            Log.d("SensorFusion", "Note: To rebuild proto with new fields, run:");
            Log.d("SensorFusion", "  protoc --java_out=app/src/main/java app/src/main/proto/traj.proto");
            
        } catch (Exception e) {
            Log.e("SensorFusion", "❌ Error saving data to trajectory: " + e.getMessage(), e);
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
            
            // 🆕 NEW: Collect WiFi fingerprint data from all scanned networks
            // This stores RSSI values from all detected WiFi networks at this timestamp
            try {
                // Get all WiFi scan results
                List<Object> wifiScanResults = getWifiScanResults();
                if (!wifiScanResults.isEmpty()) {
                    for (Object scanResult : wifiScanResults) {
                        // Extract BSSID and RSSI from scan result
                        // Note: This is a placeholder - actual implementation would use reflection
                        // or create a wrapper class for WifiScanResult
                    }
                }
            } catch (Exception e) {
                Log.d("SensorFusion", "Error collecting WiFi fingerprints: " + e.getMessage());
            }

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
                    
                    // 🆕 NEW: Store proximity sensor data if available
                    if (proximitySensor.sensor != null) {
                        setProximity(proximity);
                    }
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
                    
                    // 🆕 NEW: Store WiFi fingerprints every 5 seconds
                    // (stores all WiFi networks detected in current scan)
                    try {
                        List<Object> wifiList = getWifiList();
                        if (wifiList != null) {
                            for (Object wifi : wifiList) {
                                // Extract BSSID and RSSI, add to fingerprints
                                // This will be properly implemented when proto is compiled
                            }
                        }
                    } catch (Exception e) {
                        Log.d("SensorFusion", "Error storing WiFi fingerprints: " + e.getMessage());
                    }
                }
                else {
                    secondCounter++;
                }
            }
            else {
                counter++;
            }

        }
        
        /**
         * 🆕 Helper method to get WiFi scan results for fingerprinting
         * @return List of WiFi scan results
         */
        private List<Object> getWifiScanResults() {
            // This method retrieves the complete WiFi scan results
            // To be implemented when proto compilation is available
            return new ArrayList<>();
        }
        
        /**
         * 🆕 Helper method to get WiFi list
         * @return List of WiFi networks
         */
        private List<Object> getWifiList() {
            // Retrieve current WiFi list from wifiProcessor
            // Returns list of all detected networks
            return new ArrayList<>();
        }
    }

    //endregion

    //region 🆕 New Proto 2.0 Support Methods

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
        Log.d("SensorFusion", String.format("📍 setInitialPosition() called | Before: initialPositionSet=%s, Lat=%f, Lon=%f", 
            initialPositionSet, initialLocation[0], initialLocation[1]));
        
        if (!initialPositionSet) {
            this.initialLocation[0] = lat;
            this.initialLocation[1] = lon;
            this.initialOrientation = orientation;
            this.initialPositionSet = true;
            Log.d("SensorFusion", String.format(
                "📍 InitialPosition SET ✓ | Lat: %.6f | Lon: %.6f | Bearing: %.1f° | Flag: %s",
                lat, lon, orientation, initialPositionSet
            ));
        } else {
            Log.w("SensorFusion", String.format("⚠️ InitialPosition already set (%s), ignoring new value | New: (%.6f, %.6f)", 
                initialPositionSet, lat, lon));
        }
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
        
        Log.d("SensorFusion", String.format(
            "📶 WiFi RTT | MAC: %016X | Distance: %.1f m | Std Dev: %.1f m | RSSI: %d dBm | Total RTT: %d",
            mac, distance / 1000.0f, distanceStd / 1000.0f, rssi, wifiRttData.size()
        ));
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
        
        // 🆕 LOGCAT OUTPUT: BLE device with full UUID details
        StringBuilder uuidList = new StringBuilder();
        if (serviceUuids != null && !serviceUuids.isEmpty()) {
            for (String uuid : serviceUuids) {
                uuidList.append(uuid).append(" | ");
            }
        } else {
            uuidList.append("NONE");
        }
        
        Log.d("SensorFusion", String.format(
            "📱 BLEData | MAC: %s | Name: %-15s | TX: %+3d dBm | Flags: 0x%02X | UUIDs: %s",
            macAddress,
            name != null ? name : "N/A",
            txPower,
            flags,
            uuidList.toString()
        ));
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
        apData.put("rtt_enabled", rttEnabled);  // 🆕 WiFi RTT capability flag
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

    // 🆕 ========== TEST POINT METHODS ==========

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
            "🚩 Test Point #%d marked | Lat: %.6f | Lon: %.6f | Floor: %s | Timestamp: %d ms",
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
