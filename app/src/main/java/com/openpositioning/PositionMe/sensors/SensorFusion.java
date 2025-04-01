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
import com.openpositioning.PositionMe.utils.UtilFunctions;

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

    // State persistence variables
    private static double[] state = new double[2]; // [x_meters, y_meters]
    private static double[][] covariance = {{100, 0}, {0, 100}};
    private static Location initialLocation = null;
    private static boolean isInitialized = false;
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
    public Traj.Trajectory.Builder trajectory;

    // Settings
    public boolean saveRecording;
    private float filter_coefficient;
    // Variables to help with timed events
    private long absoluteStartTime;
    public long bootTime;
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

    private int floor = 0; // Store the detected floor level


    // Over time accelerometer magnitude values since last step
    private List<Double> accelMagnitude;

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
        this.floor = 0;
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
            latitude = (float) location.getLatitude();
            longitude = (float) location.getLongitude();
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
        // Save newest WiFi values to local variable
        this.wifiList = Stream.of(wifiList).map(o -> (Wifi) o).collect(Collectors.toList());

        if (this.saveRecording) {
            Traj.WiFi_Sample.Builder wifiData = Traj.WiFi_Sample.newBuilder()
                    .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime);

            for (Wifi data : this.wifiList) {
                wifiData.addMacScans(Traj.Mac_Scan.newBuilder()
                        .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                        .setMac(data.getBssid())
                        .setRssi(data.getLevel()));
            }

            //  Correctly add WiFi data to Trajectory
            this.trajectory.addWifiData(wifiData.build());  // <-- Fix: Ensure `.build()` is called
        }

        createWifiPositioningRequest();
    }


    /**
     * Function to create a request to obtain a wifi location for the obtained wifi fingerprint
     *
     */
    private void createWifiPositioningRequest() {
        try {
            JSONObject wifiAccessPoints = new JSONObject();
            for (Wifi data : this.wifiList) {
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }

            JSONObject wifiFingerprint = new JSONObject();
            wifiFingerprint.put(WIFI_FINGERPRINT, wifiAccessPoints);

            // Send the WiFi fingerprint request with a callback
            this.wiFiPositioning.request(wifiFingerprint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng wifiLocation, int floor) {
                    updateWifiPosition(wifiLocation, floor);
                }

                @Override
                public void onError(String message) {
                    Log.e("SensorFusion", "WiFi positioning failed: " + message);
                }
            });
        } catch (JSONException e) {
            Log.e("jsonErrors", "Error creating WiFi fingerprint JSON: " + e.toString());
        }
    }

    private void updateWifiPosition(LatLng wifiLocation, int floor) {
        Log.d("SensorFusion", "WiFi Positioning Successful: " + wifiLocation.toString() + ", Floor: " + floor);
        this.latitude = (float) wifiLocation.latitude;
        this.longitude = (float) wifiLocation.longitude;
        this.floor = floor;

        // No need to add WiFi data again here
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
     * Method to combine WiFi, GNSS, and PDR using EKF.
     * Written by Marco Bancalari-Ruiz
     */

    public LatLng EKF_recording(){

        createWifiPositioningRequest();

        //Initialise local variables
        float wifiLat = this.latitude;
        float wifiLng = this.longitude;
        float[] PDRMovement = PdrProcessing.getPDRMovement();
        float pdrDeltaX = PDRMovement[0];
        float pdrDeltaY = PDRMovement[1];

        // Initialize on first valid WiFi reading
        if (!isInitialized && wifiLat != 0 && wifiLng != 0) {
            initialLocation = new Location("");
            initialLocation.setLatitude(wifiLat);
            initialLocation.setLongitude(wifiLng);
            state[0] = 0;
            state[1] = 0;
            isInitialized = true;
            return new LatLng(wifiLat, wifiLng);
        }

        if (!isInitialized) return null;

        // Prediction step (PDR)
        double[][] F = {{1, 0}, {0, 1}}; // State transition matrix
        double[][] Q = {{0.1, 0}, {0, 0.1}}; // Process noise

        state[0] += pdrDeltaX;
        state[1] += pdrDeltaY;
        covariance = matrixAdd(matrixMultiply(F, matrixMultiply(covariance, transpose(F))), Q);

        // Update step (WiFi)
        double[] measurement = {wifiLat, wifiLng};
        double[] predictedLL = metersToLatLng(state[0], state[1]);
        double[][] H = computeJacobian();
        double[][] R = {{1e-6, 0}, {0, 1e-6}}; // Measurement noise

        // Kalman gain
        double[][] S = matrixAdd(matrixMultiply(H, matrixMultiply(covariance, transpose(H))), R);
        double[][] K = matrixMultiply(matrixMultiply(covariance, transpose(H)), inverse(S));

        // State update
        double[] innovation = {
                measurement[0] - predictedLL[0],
                measurement[1] - predictedLL[1]
        };
        double[] update = matrixVectorMultiply(K, innovation);
        state[0] += update[0];
        state[1] += update[1];

        // Covariance update
        double[][] I = {{1, 0}, {0, 1}};
        covariance = matrixMultiply(matrixSubtract(I, matrixMultiply(K, H)), covariance);

        return new LatLng(predictedLL[0], predictedLL[1]);
    }

    // Code by Marco Bancalari-Ruiz (Assisted by Jamie Arnott)
    /**
     * Method to compute the fused position using GNSS, WiFi and PDR data using an
     * Extended Kalman-Filter
     *
     * @param wifiPos
     * @param pdrPos
     * @param prevPdrPos
     * @param gnssPos
     * @return
     */
    public LatLng EKF_replay(LatLng wifiPos, LatLng pdrPos, LatLng prevPdrPos, LatLng gnssPos) {
        if (pdrPos == null || prevPdrPos == null) return null;

        initialLocation = new Location("");
        initialLocation.setLatitude(wifiPos != null ? wifiPos.latitude : gnssPos.latitude);
        initialLocation.setLongitude(wifiPos != null ? wifiPos.longitude : gnssPos.longitude);

        // Convert delta movement from degrees to meters
        double deltaX = UtilFunctions.degreesToMetersLng(
                pdrPos.longitude - prevPdrPos.longitude, initialLocation.getLatitude()
        );
        double deltaY = UtilFunctions.degreesToMetersLat(
                pdrPos.latitude - prevPdrPos.latitude
        );

        // Convert WiFi and GNSS positions to meters from origin (if they exist)
        double[] wifiMeters = null;
        if (wifiPos != null) {
            wifiMeters = UtilFunctions.latLngToMeters(wifiPos, initialLocation);
        }

        double[] gnssMeters = null;
        if (gnssPos != null) {
            gnssMeters = UtilFunctions.latLngToMeters(gnssPos, initialLocation);
        }

        // Initialize filter on first valid reading
        if (!isInitialized && wifiMeters != null) {
            state[0] = wifiMeters[0];
            state[1] = wifiMeters[1];
            isInitialized = true;
            return wifiPos;
        } else if (!isInitialized && gnssMeters != null) {
            state[0] = gnssMeters[0];
            state[1] = gnssMeters[1];
            isInitialized = true;
            return gnssPos;
        }



        if (!isInitialized) return null;

        // === Prediction step (PDR) ===
        double[][] F = {{1, 0}, {0, 1}};
        double[][] Q = {{1, 0}, {0, 1}}; // PDR noise in meters
        state[0] += deltaX;
        state[1] += deltaY;
        covariance = matrixAdd(matrixMultiply(F, matrixMultiply(covariance, transpose(F))), Q);

        // === Update step (WiFi) ===
        if (wifiMeters != null) {
            double[][] R_wifi = {{5.0, 0}, {0, 5.0}}; // WiFi = more accurate indoors
            performMeasurementUpdate(wifiMeters, R_wifi);
        }

        // === Update step (GNSS) ===
        if (gnssMeters != null) {
            double[][] R_gnss = {{10.0, 0}, {0, 10.0}}; // GNSS = less accurate indoors
            performMeasurementUpdate(gnssMeters, R_gnss);
        }

        // Convert back to lat/lng
        double[] latLng = UtilFunctions.metersToLatLng(state[0], state[1], initialLocation);
        return new LatLng(latLng[0], latLng[1]);
    }

    // Code by Marco Bancalari-Ruiz
    /**
     * Method to perform a measurement update for next step
     * Helper method for EKF_replay
     * @param measurement
     * @param R
     */
    private void performMeasurementUpdate(double[] measurement, double[][] R) {
        double[][] H = {{1, 0}, {0, 1}};

        double[] predicted = {state[0], state[1]};
        double[] innovation = {
                measurement[0] - predicted[0],
                measurement[1] - predicted[1]
        };

        double[][] S = matrixAdd(matrixMultiply(H, matrixMultiply(covariance, transpose(H))), R);
        double[][] K = matrixMultiply(matrixMultiply(covariance, transpose(H)), inverse(S));
        double[] update = matrixVectorMultiply(K, innovation);

        state[0] += update[0];
        state[1] += update[1];

        double[][] I = {{1, 0}, {0, 1}};
        covariance = matrixMultiply(matrixSubtract(I, matrixMultiply(K, H)), covariance);
    }



    /**
     *
     * Mathematical operations required for EKF
     * Written by: Marco Bancalari-Ruiz
     */
    private static double[] metersToLatLng(double dx, double dy) {
        double R = 6371e3; // Earth radius
        double lat = initialLocation.getLatitude() + (dy/R) * (180/Math.PI);
        double lng = initialLocation.getLongitude() + (dx/(R * Math.cos(Math.toRadians(initialLocation.getLatitude())))) * (180/Math.PI);
        return new double[]{lat, lng};
    }

    private static double[][] computeJacobian() {
        double R = 6371e3;
        double cosLat = Math.cos(Math.toRadians(initialLocation.getLatitude()));
        return new double[][] {
                {0, 180/(Math.PI * R)},
                {180/(Math.PI * R * cosLat), 0}
        };
    }

    // Matrix operations
    private static double[][] transpose(double[][] m) {
        return new double[][]{{m[0][0], m[1][0]}, {m[0][1], m[1][1]}};
    }

    private static double[][] matrixMultiply(double[][] a, double[][] b) {
        return new double[][]{
                {a[0][0]*b[0][0] + a[0][1]*b[1][0], a[0][0]*b[0][1] + a[0][1]*b[1][1]},
                {a[1][0]*b[0][0] + a[1][1]*b[1][0], a[1][0]*b[0][1] + a[1][1]*b[1][1]}
        };
    }

    private static double[] matrixVectorMultiply(double[][] m, double[] v) {
        return new double[]{
                m[0][0]*v[0] + m[0][1]*v[1],
                m[1][0]*v[0] + m[1][1]*v[1]
        };
    }

    private static double[][] inverse(double[][] m) {
        double det = m[0][0]*m[1][1] - m[0][1]*m[1][0];
        if (Math.abs(det) < 1e-10) {
            Log.e("EKF", "Matrix is singular, cannot invert.");
            return new double[][]{{1, 0}, {0, 1}}; // fallback identity
        }

        return new double[][]{
                {m[1][1]/det, -m[0][1]/det},
                {-m[1][0]/det, m[0][0]/det}
        };
    }

    private static double[][] matrixAdd(double[][] a, double[][] b) {
        return new double[][]{
                {a[0][0]+b[0][0], a[0][1]+b[0][1]},
                {a[1][0]+b[1][0], a[1][1]+b[1][1]}
        };
    }

    private static double[][] matrixSubtract(double[][] a, double[][] b) {
        return new double[][]{
                {a[0][0]-b[0][0], a[0][1]-b[0][1]},
                {a[1][0]-b[1][0], a[1][1]-b[1][1]}
        };
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


    // Code by Guilherme: List to store recorded tags.
    private List<com.openpositioning.PositionMe.utils.Tag> tagList = new ArrayList<>();

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
     * Timer task to record data with the desired frequency in the trajectory class.
     *
     * Inherently threaded, runnables are created in {@link SensorFusion#startRecording()} and
     * destroyed in {@link SensorFusion#stopRecording()}.
     */


    //Code By Guilherme: Finds the GNSS start location to be used as the initial location
    private LatLng getStartLocationFromGNSS() {
        float[] start = getGNSSLatitude(true); // returns startLocation when 'start' is true
        if (start != null && start.length >= 2) {
            return new LatLng(start[0], start[1]);
        }
        return new LatLng(0, 0); // fallback if not available
    }

/**
 * Code By Guilherme
 */

    // New inner class to store a fused sample with its timestamp.
    public static class FusedSample {
        public LatLng fusedPosition;
        public long timestamp;
        public FusedSample(LatLng fusedPosition, long timestamp) {
            this.fusedPosition = fusedPosition;
            this.timestamp = timestamp;
        }
    }
    // Helper method to get the reference point for conversion.
// We use the first GNSS sample (when available) from the trajectory builder.
// If not, we use the first PDR sample by converting it using our own conversion.
    private LatLng getReferencePoint() {
        if (trajectory.getGnssDataCount() > 0) {
            Traj.GNSS_Sample firstGnss = trajectory.getGnssData(0);
            return new LatLng(firstGnss.getLatitude(), firstGnss.getLongitude());
        } else if (trajectory.getPdrDataCount() > 0) {
            // If no GNSS, we assume the very first PDR sample can be used as reference.
            // Note: This assumes that PDR data is stored in a coordinate system relative to the reference.
            Traj.Pdr_Sample firstPdr = trajectory.getPdrData(0);
            // Here you might need to convert the (x,y) to lat/lon using an approximate method.
            // For simplicity, we assume the first PDR sample already corresponds to the start location.
            return new LatLng(startLocation[0], startLocation[1]);
        }
        return new LatLng(0, 0); // Fallback.
    }

    // Update batch-optimized (fused) trajectory by fusing PDR, GNSS, and WiFi data.
    // This method fuses data at each PDR time-step.
    public List<FusedSample> updateBatchOptimizedPosition() {
        List<FusedSample> fusedTrajectory = new ArrayList<>();

        // Fixed weights for each sensor type.
        double weightPDR = 0.2;
        double weightGNSS = 0.5;
        double weightWiFi = 0.3;

        // Use the reference point from GNSS (if available), otherwise from PDR.
        LatLng reference = getReferencePoint();

        // Iterate over all PDR samples (assumed to be the most frequent).
        for (int i = 0; i < trajectory.getPdrDataCount(); i++) {
            Traj.Pdr_Sample pdrSample = trajectory.getPdrData(i);
            long t = pdrSample.getRelativeTimestamp();
            // For PDR, assume the x,y are already in local coordinates (meters).
            double pdrX = pdrSample.getX();
            double pdrY = pdrSample.getY();

            // Find the closest GNSS sample.
            double bestGnssDiff = Double.MAX_VALUE;
            Traj.GNSS_Sample bestGnss = null;
            for (int j = 0; j < trajectory.getGnssDataCount(); j++) {
                long tGnss = trajectory.getGnssData(j).getRelativeTimestamp();
                double diff = Math.abs(t - tGnss);
                if (diff < bestGnssDiff) {
                    bestGnssDiff = diff;
                    bestGnss = trajectory.getGnssData(j);
                }
            }

            // Find the closest WiFi sample.
            double bestWifiDiff = Double.MAX_VALUE;
            // Assuming your trajectory builder has a method getWifiDataCount()
            // (if not, skip WiFi fusion).
            Traj.WiFi_Sample bestWifi = null;
            if (trajectory.getWifiDataCount() > 0) {
                for (int j = 0; j < trajectory.getWifiDataCount(); j++) {
                    long tWifi = trajectory.getWifiData(j).getRelativeTimestamp();
                    double diff = Math.abs(t - tWifi);
                    if (diff < bestWifiDiff) {
                        bestWifiDiff = diff;
                        bestWifi = trajectory.getWifiData(j);
                    }
                }
            }

            // Convert GNSS and WiFi data to local coordinates using UtilFunctions.
            double[] gnssLocal = null;
            if (bestGnss != null && bestGnssDiff < 1000) { // within 1 second
                LatLng gnssLatLng = new LatLng(bestGnss.getLatitude(), bestGnss.getLongitude());
                // Using the UtilFunctions method that converts lat/lon to North-East.
                gnssLocal = UtilFunctions.convertLatLngToNorthEast(gnssLatLng, reference);
            }

            double[] wifiLocal = null;
            if (bestWifi != null && bestWifiDiff < 1000) { // within 1 second
                // Here, you need to have a method to get a representative LatLng from a WiFi sample.
                // For this example, assume that bestWifi provides a valid lat/lon (e.g., via an API response).
                // If not, skip WiFi fusion.
                LatLng wifiLatLng = new LatLng(0, 0); // Replace with actual retrieval.
                wifiLocal = UtilFunctions.convertLatLngToNorthEast(wifiLatLng, reference);
            }

            // For PDR, we already have (pdrX, pdrY) in local coordinates.
            double fusedX = weightPDR * pdrX;
            double fusedY = weightPDR * pdrY;
            double totalWeight = weightPDR;
            if (gnssLocal != null) {
                fusedX += weightGNSS * gnssLocal[0];
                fusedY += weightGNSS * gnssLocal[1];
                totalWeight += weightGNSS;
            }
            if (wifiLocal != null) {
                fusedX += weightWiFi * wifiLocal[0];
                fusedY += weightWiFi * wifiLocal[1];
                totalWeight += weightWiFi;
            }
            fusedX /= totalWeight;
            fusedY /= totalWeight;

            // Convert fused local coordinate back to WGS84 using UtilFunctions.
            // Assume UtilFunctions has a method localToLatLng that takes local coordinate and a reference.
            LatLng fusedLatLng = UtilFunctions.localToLatLng(new double[]{fusedX, fusedY}, reference);

            fusedTrajectory.add(new FusedSample(fusedLatLng, t));
        }

        return fusedTrajectory;
    }


    /**
     * Adds a new tag to the list.
     */
    public void addTag(com.openpositioning.PositionMe.utils.Tag tag) {
        tagList.add(tag);
    }

    /**
     * Returns the list of recorded tags.
     */
    public List<com.openpositioning.PositionMe.utils.Tag> getTagList() {
        return tagList;
    }


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
                    .addGnssData(Traj.GNSS_Sample.newBuilder()
                            .setLatitude(latitude)
                            .setLongitude(longitude)
                            .setRelativeTimestamp(SystemClock.uptimeMillis()-bootTime))

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
}


    //endregion


