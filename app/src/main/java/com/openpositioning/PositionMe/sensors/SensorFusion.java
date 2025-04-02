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
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.PdrProcessing;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.presentation.fragment.SettingsFragment;
import com.openpositioning.PositionMe.utils.SensorFusionUpdates;
import com.openpositioning.PositionMe.utils.CoordinateTransform;
import com.openpositioning.PositionMe.algorithms.ExtendedKalmanFilter;
import com.openpositioning.PositionMe.algorithms.ParticleFilter;
import com.openpositioning.PositionMe.sensors.WiFiPositioning;


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
    private float prevStepLength = 0.75f;
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
    private TurnDetector turnDetector;
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

    private LatLng fusedPosition;

    private LatLng positionWifi;
    private WifiDataProcessor wifiDataProcessor;
    private double altitude;

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


    // Over time accelerometer magnitude values since last step
    private List<Double> accelMagnitude;

    // PDR calculation class
    private PdrProcessing pdrProcessing;

    // Trajectory displaying class
    private PathView pathView;
    // WiFi positioning object
    private WiFiPositioning wifiPositioning;

    private List<SensorFusionUpdates> recordingUpdates;

    // Boolean to control fusion algorithm selection
    private boolean fusionAlgorithmSelection = true;
    // Filter classes
    private ParticleFilter particleFilter;
    private ExtendedKalmanFilter extendedKalmanFilter;
    private boolean noCoverage;
    private double[] startRef;
    private double[] ecefRefCoords;








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
        this.recordingUpdates = new ArrayList<>();
        this.startRef = new double[3];
        this.ecefRefCoords = new double[3];



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
        this.turnDetector = new TurnDetector();
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
        this.wifiPositioning = new WiFiPositioning(context);
        this.wifiDataProcessor = new WifiDataProcessor(context);

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
                break;

            case Sensor.TYPE_STEP_DETECTOR:
                long stepTime = SystemClock.uptimeMillis() - bootTime;
                float step_length = this.pdrProcessing.getAverageStepLength();


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

                    notifySensorUpdate(SensorFusionUpdates.update_type.PDR_UPDATE);
                    if (saveRecording) {
                        //update fusion processing algorithm with new PDR
                        if (fusionAlgorithmSelection) {
                            this.extendedKalmanFilter.predict(this.orientation[0], step_length, passAverageStepLength(),
                                    (android.os.SystemClock.uptimeMillis()),
                                    this.turnDetector.onStepDetected(this.orientation[0]));
                        }
                        this.updateFusionPDR();
                        // Store the PDR coordinates for plotting the trajectory
                        this.pathView.drawTrajectory(newCords);
                    }
                    this.accelMagnitude.clear();

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
            float altitude = (float) location.getAltitude();
            float accuracy = (float) location.getAccuracy();
            float speed = (float) location.getSpeed();
            String provider = location.getProvider();
            if(saveRecording) {
                trajectory.addGnssData(Traj.GNSS_Sample.newBuilder()
                        .setAccuracy(accuracy)
                        .setAltitude(altitude)
                        .setLatitude(latitude)
                        .setLongitude(longitude)
                        .setSpeed(speed)
                        .setProvider(provider)
                        .setRelativeTimestamp(System.currentTimeMillis()-absoluteStartTime));
                updateFusionGNSS(latitude, longitude, altitude);
                notifySensorUpdate(SensorFusionUpdates.update_type.GNSS_UPDATE);
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
            Traj.WiFi_Sample.Builder wifiData = Traj.WiFi_Sample.newBuilder()
                    .setRelativeTimestamp(SystemClock.uptimeMillis()-bootTime);
            for (Wifi data : this.wifiList) {
                wifiData.addMacScans(Traj.Mac_Scan.newBuilder()
                        .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                        .setMac(data.getBssid()).setRssi(data.getLevel()));
            }
            // Adding WiFi data to Trajectory
            this.trajectory.addWifiData(wifiData);
        }
        createWifiPositionRequestCallback();
    }

    @Override
    public void update(Object data) {

    }

    /**
     * Function to create a request to obtain a wifi location for the obtained wifi fingerprint
     *
     */


    public void setInitialPositionENU(double east, double north) {
        if (this.extendedKalmanFilter != null) {
            this.extendedKalmanFilter.setInitialPosition(east, north);
        }
    }
    public double[] getCurrentEKFState() {
        if (extendedKalmanFilter == null) {
            Log.w("SensorFusion", "EKF未初始化");
            return null;
        }
        try {
            return this.extendedKalmanFilter.getCurrentState();
        } catch (Exception e) {
            Log.e("SensorFusion", "获取EKF状态失败", e);
            return null;
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

            Log.d("WiFi指纹", "Fingerprint JSON: " + wifiFingerPrint.toString());

            this.wifiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng wifiLocation, int floor) {
                    Log.d("SensorFusion", "📶 updateFusionWifi() called with location: " + wifiLocation);
                    JSONObject wifiResponse = new JSONObject();
                    try {
                        wifiResponse.put("lat", wifiLocation.latitude);
                        wifiResponse.put("lon", wifiLocation.longitude);
                        wifiResponse.put("floor", floor);
                        updateFusionWifi(wifiResponse);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
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
    public Wifi[] getWifiData() {
        if (wifiDataProcessor != null) {
            return new Wifi[]{wifiDataProcessor.getCurrentWifiData()};
        }
        return null;
    }

    /**
     * Method to get user position obtained using {@link WiFiPositioning}.
     *
     * @return {@link LatLng} corresponding to user's position.
     */
    public LatLng getLatLngWifiPositioning(){return this.wifiPositioning.getWifiLocation();}

    /**
     * Method to get current floor the user is at, obtained using WiFiPositioning
     * @see WiFiPositioning for WiFi positioning
     * @return Current floor user is at using WiFiPositioning
     */
    public int getWifiFloor(){
        return this.wifiPositioning.getFloor();
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
     * Getter function for core location data including altitude.
     *
     * @param start set true to get the initial location
     * @return longitude and latitude data in a float[3].
     */
    public double[] getGNSSLatLngAlt(boolean start) {
        double [] latLongAlt = new double[3];
        if(!start) {
            latLongAlt[0] = latitude;
            latLongAlt[1] = longitude;
            latLongAlt[2] = altitude;
        }
        else{
            latLongAlt = startRef;
        }
        return latLongAlt;
    }

    /**
     * A helper function to get the calculated ECEF coordinates as doubles for more accurate conversion between coordinates.
     * @return a double array with the ECEF coordinates
     */
    public double[] getEcefRefCoords(){
        return ecefRefCoords;
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
     * Setter function for core location data.
     *
     * @param startPosition contains the initial location set by the user
     */
    public void setStartGNSSLatLngAlt(double[] startPosition){
        this.startRef = startPosition;
        this.ecefRefCoords = CoordinateTransform.geodeticToEcef(startPosition[0],startPosition[1], startPosition[2]);
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
     * A helper method that allows other classes to register to receive sensor updates from this class.
     * @param observer The class that wishes to receive updates.
     */
    public void registerForSensorUpdates(SensorFusionUpdates observer) {
        recordingUpdates.add(observer);
    }

    /**
     * A helper method that allows other classes to remove itself from receiving sensor updates from this class.
     * @param observer The class that wishes to not receive updates.
     */
    public void removeSensorUpdate(SensorFusionUpdates observer) {
        recordingUpdates.remove(observer);
    }

    /**
     * Trigger WiFi Positioning request
     *
     * @param fingerprint WiFi fingerprint data
     */
    public void requestWiFiPosition(JSONObject fingerprint,Context context) {
        wifiPositioning.request(fingerprint, new WiFiPositioning.VolleyCallback() {
            @Override
            public void onSuccess(LatLng location, int floor) {
                Log.d("SensorFusion", "✅ WiFi定位成功: " + location + ", floor: " + floor);
                // 可在这里通知 UI 更新位置
            }

            @Override
            public void onError(String message) {
                Log.w("SensorFusion", "❗️ WiFi定位失败: " + message);
                Toast.makeText(context, "WiFi定位失败: " + message, Toast.LENGTH_SHORT).show();
            }
        });
    }
    /**
     * A helper method used to notify all observers that an update is available.
     * Allows recording fragment to be asynchronously notified when a change occurs.
     *
     * @param type The type of update, will determine which callback function to call.
     */
    public void notifySensorUpdate(SensorFusionUpdates.update_type type){
        for (SensorFusionUpdates observer : recordingUpdates) {
            switch (type) {
                case PDR_UPDATE:
                    observer.onPDRUpdate();
                    break;
                case ORIENTATION_UPDATE:
                    observer.onOrientationUpdate();
                    break;
                case GNSS_UPDATE:
                    observer.onGNSSUpdate();
                    break;
                case FUSED_UPDATE:
                    observer.onFusedUpdate(fusedPosition);
                    break;
                case WIFI_UPDATE:
                    observer.onWifiUpdate(positionWifi);
            }
        }
    }

    /**
     * A helper method used to notify all observers that an update is available. It is a separate method
     * to allow update method to take another parameter and be called from the fusion algorithm class.
     */
    public void notifyFusedUpdate(LatLng fused_pos){
        fusedPosition = fused_pos;
        for (SensorFusionUpdates observer : recordingUpdates) {
            observer.onFusedUpdate(fused_pos);
        }
    }

    /**
     * @deprecated ServerCommunications is now a singleton, resolving the three way dependency as
     * fragments had to register through sensor fusion. This was counterintuitive and the singleton
     * resolves this.
     *
     * Registers the caller observer to receive updates from the server instance.
     * Necessary when classes want to act on a trajectory being successfully or unsuccessfully send
     * to the server. This grants access to observing the {@link ServerCommunications} instance
     * used by the SensorFusion class.
     *
     * @param observer  Instance implementing {@link Observer} class who wants to be notified of
     *                  events relating to sending and receiving trajectories.
     */
    @Deprecated
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

    /**
     * 获取当前使用的融合算法选择
     * @return true表示使用EKF，false表示使用PF
     */
    public boolean isFusionAlgorithmSelection() {
        return fusionAlgorithmSelection;
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
     * A helper function to get the calculated PDR coordinates as doubles
     * for more accurate conversion between coordinates.
     *
     * @return a double array with the X and Y PDR coordinates
     */
    public float[] getCurrentPDRCalc(){
        return pdrProcessing.getPDRMovement();
    }

    // region FUSION PROCESSING

    /**
     * Updates the fusion process with Pedestrian Dead Reckoning (PDR) data.
     * Calculates new PDR values and elevation, then calls the appropriate fusion algorithm.
     * Stores the resulting position and timestamps it.
     */
    public void updateFusionPDR() {
        try {
            // 计算新的PDR值
            float[] pdrValues = getCurrentPDRCalc();
            float elevationVal = getElevation();

            // 转换PDR坐标
            LatLng positionPDR = CoordinateTransform.enuToGeodetic(pdrValues[0], pdrValues[1], elevationVal, 
                                                                  startRef[0], startRef[1], ecefRefCoords);
            double latitude = positionPDR.latitude;
            double longitude = positionPDR.longitude;

            // 通知UI更新PDR轨迹
            notifySensorUpdate(SensorFusionUpdates.update_type.PDR_UPDATE);

            // 同时更新EKF和PF
            if (extendedKalmanFilter != null) {
                this.extendedKalmanFilter.onStepDetected(pdrValues[0], pdrValues[1], elevationVal, 
                                                       (android.os.SystemClock.uptimeMillis()));
                // 获取EKF位置
                double[] ekfState = this.extendedKalmanFilter.getCurrentState();
                LatLng ekfPosition = CoordinateTransform.enuToGeodetic(
                    (float)ekfState[0], (float)ekfState[1], elevationVal,
                    startRef[0], startRef[1], ecefRefCoords
                );
                
                // 更新PF
                if (particleFilter != null) {
                    this.particleFilter.update(latitude, longitude);
                    LatLng pfPosition = new LatLng(latitude, longitude);
                    
                    // 添加PDR更新日志
                    Log.d("SensorFusion", String.format("PDR更新:\n" +
                            "PDR原始位置: (%.6f, %.6f)\n" +
                            "当前高度: %.2f米",
                            latitude, longitude, elevationVal));
                    
                    // 融合两个滤波器的结果
                    LatLng fusedPosition = fusePositions(ekfPosition, pfPosition);
                    notifyFusedUpdate(fusedPosition);
                }
            }
        } catch (Exception e) {
            Log.e("SensorFusion", "更新PDR融合失败", e);
        }
    }

    /**
     * Updates the fusion process with WiFi positioning data.
     * Extracts latitude, longitude, and floor information from the given JSON object.
     * Calls the appropriate fusion algorithm and stores the resulting position.
     * If the server response failed notify with null to ensure no coverage is detected.
     *
     * @param wifiResponse The JSON object containing WiFi positioning data.
     */
    public void updateFusionWifi(JSONObject wifiResponse) {
        try {
            if (wifiResponse == null) {
                this.positionWifi = null;
                notifySensorUpdate(SensorFusionUpdates.update_type.WIFI_UPDATE);
                return;
            }

            double latitude = wifiResponse.getDouble("lat");
            double longitude = wifiResponse.getDouble("lon");
            double floor = wifiResponse.getDouble("floor");
            this.positionWifi = new LatLng(latitude, longitude);

            // 通知UI更新WiFi位置
            notifySensorUpdate(SensorFusionUpdates.update_type.WIFI_UPDATE);

            // 同时更新两个滤波器
            if (!noCoverage) {
                if (extendedKalmanFilter != null) {
                    this.extendedKalmanFilter.onOpportunisticUpdate(
                        CoordinateTransform.geodeticToEnu(latitude, longitude, getElevation(), 
                                                        startRef[0], startRef[1], startRef[2]), 
                        (android.os.SystemClock.uptimeMillis())
                    );
                }
                
                if (particleFilter != null) {
                    this.particleFilter.update(latitude, longitude);
                }
            }
        } catch (JSONException e) {
            Log.e("SensorFusion", "处理WiFi响应失败", e);
        }
    }

    /**
     * Updates the fusion process with Global Navigation Satellite System (GNSS) data.
     * Calls the appropriate fusion algorithm and stores the resulting position.
     *
     * @param latitude  The latitude from GNSS data.
     * @param longitude The longitude from GNSS data.
     * @param altitude  The altitude from GNSS data.
     */
    public void updateFusionGNSS(double latitude, double longitude, double altitude) {
        try {
            if (noCoverage) {
                // 同时更新两个滤波器
                if (extendedKalmanFilter != null) {
                    this.extendedKalmanFilter.onOpportunisticUpdate(
                        CoordinateTransform.geodeticToEnu(latitude, longitude, altitude, 
                                                        startRef[0], startRef[1], startRef[2]), 
                        (android.os.SystemClock.uptimeMillis())
                    );
                }
                
                if (particleFilter != null) {
                    this.particleFilter.update(latitude, longitude);
                }
            }
        } catch (Exception e) {
            Log.e("SensorFusion", "更新GNSS融合失败", e);
        }
    }

    /**
     * Initializes the fusion algorithm based on user settings.
     * Creates either an Extended Kalman Filter or a Particle Filter instance.
     */
    public void initialiseFusionAlgorithm() {
        try {
            // 同时初始化两个滤波器
            Log.d("SensorFusion", "初始化EKF和PF算法");
            this.extendedKalmanFilter = new ExtendedKalmanFilter();
            this.particleFilter = new ParticleFilter();
            this.noCoverage = true;
        } catch (Exception e) {
            Log.e("SensorFusion", "初始化融合算法失败", e);
        }
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
    public void setCurrentFloor(int updatedFloor){
        pdrProcessing.setCurrentFloor(updatedFloor);
    }

    /**
     * 融合EKF和PF的位置估计结果
     * @param ekfPosition EKF估计的位置
     * @param pfPosition PF估计的位置
     * @return 融合后的位置
     */
    private LatLng fusePositions(LatLng ekfPosition, LatLng pfPosition) {
        // 这里可以实现不同的融合策略
        // 例如：加权平均、卡尔曼滤波等
        // 这里使用简单的加权平均作为示例
        double ekfWeight = 0.3;  // EKF权重
        double pfWeight = 0.7;   // PF权重
        
        double fusedLat = ekfPosition.latitude * ekfWeight + pfPosition.latitude * pfWeight;
        double fusedLng = ekfPosition.longitude * ekfWeight + pfPosition.longitude * pfWeight;
        
        // 添加详细的日志输出
        Log.d("SensorFusion", String.format("位置融合结果:\n" +
                "EKF位置: (%.6f, %.6f)\n" +
                "PF位置: (%.6f, %.6f)\n" +
                "融合权重: EKF=%.2f, PF=%.2f\n" +
                "融合后位置: (%.6f, %.6f)",
                ekfPosition.latitude, ekfPosition.longitude,
                pfPosition.latitude, pfPosition.longitude,
                ekfWeight, pfWeight,
                fusedLat, fusedLng));
        
        return new LatLng(fusedLat, fusedLng);
    }

}
