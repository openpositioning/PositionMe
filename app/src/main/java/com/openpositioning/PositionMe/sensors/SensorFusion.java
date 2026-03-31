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
import java.util.Random;

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

    // Store the last event timestamps for each sensor type
    private HashMap<Integer, Long> lastEventTimestamps = new HashMap<>();
    private HashMap<Integer, Integer> eventCounts = new HashMap<>();

    long maxReportLatencyNs = 0;  // Disable batching to deliver events immediately

    // Define a threshold for large time gaps (in milliseconds)

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
    private static final String WIFI_FINGERPRINT = "wf";
    //endregion

    // ==========================================
    // EE HUNG - PARTICLE FILTER VARIABLES
    // ==========================================

    //CHEN 2
// ===== Wall-follow / slide mode =====

    private static final double MIN_STEP_LENGTH = 0.15;

    private WallEventListener wallEventListener;

    public void setWallEventListener(WallEventListener listener) {
        this.wallEventListener = listener;
    }

    private static final double GNSS_MEASUREMENT_VARIANCE = 20.0;
    private static final double WIFI_MEASUREMENT_VARIANCE = 8.0;
    //END
    private static final int NUM_PARTICLES = 100;
    private List<Particle> particles = new ArrayList<>();
    private boolean isFilterInitialized = false;
    private Random random = new Random();

    // Inner class representing a single "guess"
    private class Particle {
        double x;
        double y;
        double weight;

        public Particle(double x, double y, double weight) {
            this.x = x;
            this.y = y;
            this.weight = weight;
        }
    }
    // ==========================================

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
    private boolean hasAcquiredInitialLocation = false;

    private boolean preferWifiForStart = true;
    private long startLocationRequestTimeMs = 0L;
    private static final long WIFI_START_TIMEOUT_MS = 5000L;
    // Data listener
    private final LocationListener locationListener;

    // Server communication class for sending data
    private ServerCommunications serverCommunications;
    // Trajectory object containing all data
    private Traj.Trajectory.Builder trajectory;

    // Settings
    private boolean saveRecording;
    // EE HUNG (Variable to remember the last WiFi scan for comparison)
    private String lastScanSignature = "";
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
    private int stepCounter;
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
    private WiFiPositioning wiFiPositioning;

    //region Initialisation

    /**
     * Private constructor for implementing singleton design pattern for SensorFusion.
     * Initialises empty arrays and new objects that do not depends on outside information.
     */
    public SensorFusion() {
        // Location listener to be used by the GNSS class
        this.locationListener = new myLocationListener();
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
        this.startLocation = new float[]{0.0f, 0.0f};
    }


    /**
     * Static function to access singleton instance of SensorFusion.
     *
     * @return singleton instance of SensorFusion class.
     */
    public static SensorFusion getInstance() {
        return sensorFusion;
    }

    /**
     * Initialisation function for the SensorFusion instance.
     * <p>
     * Initialise all Movement sensor instances from context and predetermined types. Creates a
     * server communication instance for sending trajectories. Saves current absolute and relative
     * time, and initialises saving the recording to false.
     *
     * @param context application context for permissions and device access.
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

        if (settings.getBoolean("overwrite_constants", false)) {
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
     * <p>
     * Called every time a Sensor value is updated.
     * <p>
     * Checks originating sensor type, if the data is meaningful save it to a local variable.
     *
     * @param sensorEvent SensorEvent of sensor with values changed, includes types and values.
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

            case Sensor.TYPE_PROXIMITY: //EE HUNG added
                // 1. Update the local variable (keep this for the screen UI)
                proximity = sensorEvent.values[0];

                // 2. Save to the file if we are recording
                if (this.saveRecording) {
                    long timestamp = System.currentTimeMillis() - this.absoluteStartTime;

                    Log.d("ProximityTest", "SAVING PROXIMITY: " + sensorEvent.values[0]);

                    Traj.ProximityReading proxReading = Traj.ProximityReading.newBuilder()
                            .setRelativeTimestamp(timestamp)
                            .setDistance(sensorEvent.values[0]) // Distance in cm
                            .build();

                    this.trajectory.addProximityData(proxReading);
                }
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
                    break;
                } else {
                    lastStepTime = currentTime;
                    if (accelMagnitude.isEmpty()) {
                        Log.e("SensorFusion", "stepDetection triggered, but accelMagnitude is empty! Aborting step to prevent NaN virus.");
                        break;
                    }

                    Log.d("SensorFusion", "stepDetection triggered, accelMagnitude size = " + accelMagnitude.size());

                    float[] newCords = this.pdrProcessing.updatePdr(
                            stepTime,
                            this.accelMagnitude,
                            this.orientation[0]
                    );

                    // Clear the accelMagnitude after using it
                    this.accelMagnitude.clear();

                    // ==========================================
                    // THE SILENT NAN SHIELD (This was missing!)
                    // ==========================================
                    double stepLength = passAverageStepLength();
                    if (Float.isNaN(newCords[0]) || Float.isNaN(newCords[1]) || Double.isNaN(stepLength) || stepLength <= 0.0) {
                        Log.e("SensorFusion", "NaN Virus caught in PDR! Dropping bad step.");
                        break; // Silently drop the broken step. Do not infect the swarm!
                    }
                    // ==========================================

                    // EE HUNG NEW CODE: Move the particles!
                    if (isFilterInitialized) {
                        predictParticles(stepLength, this.orientation[0]);
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
     * {@inheritDoc}
     * <p>
     * Location listener class to receive updates from the location manager.
     * <p>
     * Passed to the {@link GNSSDataProcessor} to receive the location data in this class. Save the
     * values in instance variables.
     */
    class myLocationListener implements LocationListener {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            latitude = (float) location.getLatitude();
            longitude = (float) location.getLongitude();
            float altitude = (float) location.getAltitude();

            // ==========================================
            // THE GNSS NAN SHIELD
            // ==========================================
            if (Float.isNaN(latitude) || Float.isNaN(longitude) || (latitude == 0.0f && longitude == 0.0f)) {
                return; // Drop bad satellite data
            }

            // ==========================================
            // THE WEAK SIGNAL BOUNCER (NEW FIX)
            // ==========================================
            float accuracy = (float) location.getAccuracy();
            // If the satellite is guessing with an accuracy worse than 12 meters, ignore it!
            if (accuracy > 12.0f) {
                Log.e("SensorFusion", "GNSS accuracy is terrible (" + accuracy + "m). Ignoring satellite and trusting PDR.");
                return; // Throw it in the trash!
            }
            // ==========================================

            // --- THE DYNAMIC START ---
            if (!hasAcquiredInitialLocation) {
                long now = System.currentTimeMillis();

                if (!preferWifiForStart || (now - startLocationRequestTimeMs) > WIFI_START_TIMEOUT_MS) {
                    startLocation[0] = latitude;
                    startLocation[1] = longitude;
                    hasAcquiredInitialLocation = true;
                    preferWifiForStart = false;
                    Log.d("DynamicStart", "Locked GNSS start location (fallback): " + latitude + ", " + longitude);
                } else {
                    Log.d("DynamicStart", "Skipping GNSS start lock, waiting for WiFi...");
                }
            }


            // ============================================================
            // EE HUNG - TRANSLATE AND TRIGGER
            // ============================================================
            // 1. Translate Degrees to Meters
            double[] localCoords = convertToLocalMeters(latitude, longitude);
            double localX = localCoords[0]; // Easting (Meters)
            double localY = localCoords[1]; // Northing (Meters)

            // 2. Feed the METERS to the Particle Filter
            onNewPositionReceived(localX, localY, "gnss");
            // ============================================================

            if (saveRecording) {
                // Fix for Line 451:
                Traj.GNSSPosition position = Traj.GNSSPosition.newBuilder()
                        .setLatitude(location.getLatitude())
                        .setLongitude(location.getLongitude())
                        .setAltitude(location.getAltitude()) // This fixes the specific error
                        .build();

                trajectory.addGnssData(Traj.GNSSReading.newBuilder()
                        .setPosition(position)
                        .setAccuracy(location.getAccuracy())
                        .setSpeed(location.getSpeed())
                        .setBearing(location.getBearing())
                        .setProvider(location.getProvider()));
            }
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Receives updates from {@link WifiDataProcessor}.
     *
     * @see WifiDataProcessor object for wifi scanning.
     */
    @Override
    public void update(Object[] wifiList) {
        // Save newest wifi values to local variable
        this.wifiList = Stream.of(wifiList).map(o -> (Wifi) o).collect(Collectors.toList());

        // (EE HUNG) --- DUPLICATE CHECK CODE ---
        if (!this.wifiList.isEmpty()) {
            // Create a "Signature" using the first WiFi network found (Name + Signal Level)
            // If the first network is exactly the same as before, the whole scan is likely a duplicate (cached).
            Wifi firstItem = this.wifiList.get(0);
            String currentSignature = firstItem.getSsid() + " " + firstItem.getLevel();

            if (currentSignature.equals(lastScanSignature)) {
                // IT IS A REPEAT!
                android.util.Log.e("WiFi_Check", "⚠️ REPEATED FINGERPRINT DETECTED (Cached Data)");
                // Optional: Show a toast if you really want to see it on screen
                // Toast.makeText(context, "Duplicate Scan Ignored", Toast.LENGTH_SHORT).show();
                return;
            } else {
                // IT IS FRESH!
                android.util.Log.i("WiFi_Check", "✅ FRESH FINGERPRINT (New Data)");
                // Update the memory for next time
                lastScanSignature = currentSignature;
            }
        }
        // -----------------------------
        if (this.saveRecording) {
            Traj.Fingerprint.Builder wifiData = Traj.Fingerprint.newBuilder()
                    .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime);
            for (Wifi data : this.wifiList) {
                // Fix: Method is now 'addRfScans' because field is 'rf_scans'
                wifiData.addRfScans(Traj.RFScan.newBuilder()
                        .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                        .setMac(data.getBssid())
                        .setRssi(data.getLevel())
                        .setIsRttSupported(data.is80211mcResponder()) //
                        .setSsid(data.getSsid())
                        .setFrequency(data.getFrequency()));

                // Inside the loop in SensorFusion.java
                if (data.is80211mcResponder()) {
                    // Log now shows Frequency + Name
                    android.util.Log.i("WiFi_Check", "RTT! Freq: " + data.getFrequency() + " | Name: " + data.getSsid());
                } else {
                    // Log now shows Frequency + Name
                    android.util.Log.d("WiFi_Check", "Normal. Freq: " + data.getFrequency() + " | Name: " + data.getSsid());
                }

            }

// Fix: Method is now 'addWifiFingerprints' because field is 'wifi_fingerprints'
            this.trajectory.addWifiFingerprints(wifiData);
        }
        createWifiPositionRequestCallback();
    }

    /**
     * Function to create a request to obtain a wifi location for the obtained wifi fingerprint
     */
    private void createWifiPositioningRequest() {
        // Try catch block to catch any errors and prevent app crashing
        try {
            // Creating a JSON object to store the WiFi access points
            JSONObject wifiAccessPoints = new JSONObject();
            for (Wifi data : this.wifiList) {
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }
            // Creating POST Request
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
            this.wiFiPositioning.request(wifiFingerPrint);
        } catch (JSONException e) {
            // Catching error while making JSON object, to prevent crashes
            // Error log to keep record of errors (for secure programming and maintainability)
            Log.e("jsonErrors", "Error creating json object" + e.toString());
        }
    }
    // Callback Example Function

    /**
     * Function to create a request to obtain a wifi location for the obtained wifi fingerprint
     * using Volley Callback
     */
    private void createWifiPositionRequestCallback() {
        try {
            // Creating a JSON object to store the WiFi access points
            JSONObject wifiAccessPoints = new JSONObject();
            for (Wifi data : this.wifiList) {
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }
            // Creating POST Request
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
            this.wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng wifiLocation, int floor) {
                    // --- THE DYNAMIC START ---
                    if (!hasAcquiredInitialLocation) {
                        startLocation[0] = (float) wifiLocation.latitude;
                        startLocation[1] = (float) wifiLocation.longitude;
                        hasAcquiredInitialLocation = true;
                        preferWifiForStart = false;
                        Log.d("DynamicStart", "Locked WiFi start location: " + wifiLocation.latitude + ", " + wifiLocation.longitude);
                    }

                    // ============================================================
                    // EE HUNG - TRANSLATE AND TRIGGER (THE WIFI FIX)
                    // ============================================================
                    // 1. Translate WiFi Degrees to Local Meters
                    double[] localWifiCoords = convertToLocalMeters(wifiLocation.latitude, wifiLocation.longitude);

                    // 2. Feed the WiFi ping into the Particle Filter!
                    onNewPositionReceived(localWifiCoords[0], localWifiCoords[1], "wifi");
                    // ============================================================
                }

                @Override
                public void onError(String message) {
                    Log.e("WiFiFusion", "Failed to get WiFi position: " + message);
                }
            });
        } catch (JSONException e) {
            // Catching error while making JSON object, to prevent crashes
            // Error log to keep record of errors (for secure programming and maintainability)
            Log.e("jsonErrors", "Error creating json object" + e.toString());
        }

    }



    /**
     * {@inheritDoc}
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
    }
    //endregion

    //region Getters/Setters

    /**
     * Getter function for core location data.
     *
     * @param start set true to get the initial location
     * @return longitude and latitude data in a float[2].
     */
    public float[] getGNSSLatitude(boolean start) {
        // ==========================================
        // THE ULTIMATE CRASH SHIELD
        // ==========================================
        // The UI will crash if we hand it a 'null' array because it strictly reads index [0].
        // If we haven't locked onto a real signal yet, we MUST give it a valid array of numbers!
        if (!hasAcquiredInitialLocation) {
            return new float[]{55.92285f, -3.17407f}; // Safe Nucleus Fallback
        }

        // Once the WiFi or GNSS locks on, we return the real data:
        if (start) {
            // Extra safety check: ensure startLocation isn't somehow empty
            if (startLocation == null || startLocation.length < 2) {
                return new float[]{55.92285f, -3.17407f};
            }
            return startLocation; // The exact place you started
        }

        // Return your continuous, real-time walking location
        return new float[]{latitude, longitude};
    }

    /**
     * Setter function for core location data.
     *
     * @param startPosition contains the initial location set by the user
     */
    public void setStartGNSSLatitude(float[] startPosition) {
        startLocation = startPosition;
    }


    /**
     * Function to redraw path in corrections fragment.
     *
     * @param scalingRatio new size of path due to updated step length
     */
    public void redrawPath(float scalingRatio) {
        pathView.redraw(scalingRatio);
    }

    /**
     * Getter function for average step count.
     * Calls the average step count function in pdrProcessing class
     *
     * @return average step count of total PDR.
     */
    public float passAverageStepLength() {
        return pdrProcessing.getAverageStepLength();
    }

    /**
     * Getter function for device orientation.
     * Passes the orientation variable
     *
     * @return orientation of device.
     */
    public float passOrientation() {
        return orientation[0];
    }

    /**
     * Return most recent sensor readings.
     * <p>
     * Collects all most recent readings from movement and location sensors, packages them in a map
     * that is indexed by {@link SensorTypes} and makes it accessible for other classes.
     *
     * @return Map of <code>SensorTypes</code> to float array of most recent values.
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
     * @return list of Wifi objects.
     */
    public List<Wifi> getWifiList() {
        return this.wifiList;
    }

    /**
     * Get information about all the sensors registered in SensorFusion.
     *
     * @return List of SensorInfo objects containing name, resolution, power, etc.
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
     * @param observer Instance implementing {@link Observer} class who wants to be notified of
     *                 events relating to sending and receiving trajectories.
     */
    public void registerForServerUpdate(Observer observer) {
        serverCommunications.registerObserver(observer);
    }

    /**
     * Get the estimated elevation value in meters calculated by the PDR class.
     * Elevation is relative to the starting position.
     *
     * @return float of the estimated elevation in meters.
     */
    public float getElevation() {
        return this.elevation;
    }


    //endregion

    //region Start/Stop

    /**
     * Registers all device listeners and enables updates with the specified sampling rate.
     * <p>
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
     * Enables saving sensor values to the trajectory object.
     * <p>
     * Sets save recording to true, resets the absolute start time and create new timer object for
     * periodically writing data to trajectory.
     *
     * @see Traj object for storing data.
     */
    public void startRecording(String customName) {
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

        this.hasAcquiredInitialLocation = false;
        this.preferWifiForStart = true;
        this.startLocationRequestTimeMs = System.currentTimeMillis();
        // Protobuf trajectory class for sending sensor data to restful API
        this.trajectory = Traj.Trajectory.newBuilder()
                .setAndroidVersion(Build.VERSION.RELEASE)
                .setTrajectoryName(customName != null && !customName.isEmpty() ? customName : "Trajectory_" + absoluteStartTime).setStartTimestamp(absoluteStartTime)
                // ADD .build() to the end of every line here:
                .setAccelerometerInfo(createInfoBuilder(accelerometerSensor).build())
                .setGyroscopeInfo(createInfoBuilder(gyroscopeSensor).build())
                .setMagnetometerInfo(createInfoBuilder(magnetometerSensor).build())
                .setBarometerInfo(createInfoBuilder(barometerSensor).build())
                .setLightSensorInfo(createInfoBuilder(lightSensor).build())
                .setProximityInfo(createInfoBuilder(proximitySensor).build());//EE HUNG added

        // NEW: Turn on the Proximity Sensor
        if (accelerometerSensor != null && accelerometerSensor.sensorManager != null && proximitySensor != null) {
            accelerometerSensor.sensorManager.registerListener(this, proximitySensor.sensor, (int) 1e6);
        }
        // 2. EE HUNG NEW CODE: Auto-Capture Initial Orientation (Heading)
        // ============================================================
        if (this.orientation != null && this.orientation.length > 0) {
            // Convert Radians (from sensor) to Degrees (0-360)
            float azimuthDegrees = (float) Math.toDegrees(this.orientation[0]);

            // Fix negative angles (e.g., -90 becomes 270)
            if (azimuthDegrees < 0) {
                azimuthDegrees += 360;
            }

            // Save it to the file!
            // NOTE: If .setStartOrientation turns RED, try .setAzimuth or .setHeading
            this.trajectory.setStartOrientation(azimuthDegrees);
            Toast.makeText(this.appContext, "Started! Heading: " + (int) azimuthDegrees + "°", Toast.LENGTH_SHORT).show();
        }
        // ===========================================================

        this.storeTrajectoryTimer = new Timer();
        this.storeTrajectoryTimer.schedule(new storeDataInTrajectory(), 0, TIME_CONST);
        this.pdrProcessing.resetPDR();
        if (settings.getBoolean("overwrite_constants", false)) {
            this.filter_coefficient = Float.parseFloat(settings.getString("accel_filter", "0.96"));
        } else {
            this.filter_coefficient = FILTER_COEFFICIENT;
        }
    }

    /**
     * Disables saving sensor values to the trajectory object.
     * <p>
     * Check if a recording is in progress. If it is, it sets save recording to false, and cancels
     * the timer objects.
     *
     * @see Traj object for storing data.
     * @see SettingsFragment navigation that might cancel recording.
     */
    public void stopRecording() {
        // Only cancel if we are running
        if (this.saveRecording) {
            this.saveRecording = false;
            storeTrajectoryTimer.cancel();
        }
        if (wakeLock.isHeld()) {
            this.wakeLock.release();
        }

        // EE HUNG--- NEW CODE: Turn off the Proximity Sensor ---
        if (accelerometerSensor != null && accelerometerSensor.sensorManager != null && proximitySensor != null) {
            // We use the same manager (from accelerometer) to unregister the proximity sensor
            accelerometerSensor.sensorManager.unregisterListener(this, proximitySensor.sensor);
        }
        // -----------------------------------------------

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
     * @param sensor MovementSensor objects with populated sensorInfo fields
     * @return Traj.SensorInfo object to be used in building the trajectory
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
     * <p>
     * Inherently threaded, runnables are created in {@link SensorFusion} and
     * destroyed in {@link SensorFusion#stopRecording()}.
     */
    private class storeDataInTrajectory extends TimerTask {
        public void run() {
            // Store IMU and magnetometer data in Trajectory class
            // 1. Pack the Acceleration Box
            Traj.Vector3 accVector = Traj.Vector3.newBuilder()
                    .setX(acceleration[0])
                    .setY(acceleration[1])
                    .setZ(acceleration[2])
                    .build();

// 2. Pack the Gyroscope Box
            Traj.Vector3 gyrVector = Traj.Vector3.newBuilder()
                    .setX(angularVelocity[0])
                    .setY(angularVelocity[1])
                    .setZ(angularVelocity[2])
                    .build();

// 3. Pack the Rotation Box (Quaternion)
            Traj.Quaternion rotVector = Traj.Quaternion.newBuilder()
                    .setX(rotation[0])
                    .setY(rotation[1])
                    .setZ(rotation[2])
                    .setW(rotation[3])
                    .build();

// 4. Send the package
            trajectory.addImuData(Traj.IMUReading.newBuilder()
                    .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                    .setAcc(accVector)
                    .setGyr(gyrVector)
                    .setRotationVector(rotVector)
                    .setStepCount(stepCounter));
            // 1. Pack the Magnetometer Box
            Traj.Vector3 magVector = Traj.Vector3.newBuilder()
                    .setX(magneticField[0])
                    .setY(magneticField[1])
                    .setZ(magneticField[2])
                    .build();

// 2. Send the package (Note: Method is now 'addMagnetometerData', not 'addPositionData')
            trajectory.addMagnetometerData(Traj.MagnetometerReading.newBuilder()
                    .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                    .setMag(magVector));
            ;

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
                } else {
                    secondCounter++;
                }
            } else {
                counter++;
            }

        }
    }

    //endregion
// EE HUNG added
    public String getTrajectoryId() {
        if (this.trajectory != null) {
            // Change .getTrajectoryId() to .getTrajectoryName()
            return this.trajectory.getTrajectoryName();
        }
        return null;
    }


    // ==========================================
    // EE HUNG - WGS84 TO LOCAL GRID TRANSLATOR
    // ==========================================

    //Update these with the exact GPS coordinates of your starting location (e.g., Nucleus entrance)
    private static final double REF_LAT = 55.9228505640599;
    private static final double REF_LON = -3.174077391048845;
    private static final double EARTH_RADIUS = 6378137.0; // Earth's radius in meters

    /**
     * Converts global WGS84 (Lat/Lon) to local Easting/Northing (X/Y) in meters.
     */
    private double[] convertToLocalMeters(double lat, double lon) {
        // Convert degrees to radians
        double latRad = Math.toRadians(lat);
        double lonRad = Math.toRadians(lon);
        double refLatRad = Math.toRadians(REF_LAT);
        double refLonRad = Math.toRadians(REF_LON);

        // Equirectangular approximation for small distances
        double x = EARTH_RADIUS * (lonRad - refLonRad) * Math.cos(refLatRad);
        double y = EARTH_RADIUS * (latRad - refLatRad);

        // Returns {Easting (X), Northing (Y)}
        return new double[]{x, y};
    }

    /**
     * Translates Local Meters back to WGS84 Degrees for Google Maps
     */
    private double[] convertMetersToWGS84(double x, double y) {
        double refLatRad = Math.toRadians(REF_LAT);
        double refLonRad = Math.toRadians(REF_LON);

        double latRad = refLatRad + (y / EARTH_RADIUS);
        double lonRad = refLonRad + (x / (EARTH_RADIUS * Math.cos(refLatRad)));

        return new double[]{Math.toDegrees(latRad), Math.toDegrees(lonRad)};
    }

// ==========================================
    // EE HUNG - PARTICLE FILTER ENGINE
    // ==========================================

    /**
     * Trigger 1: When we get a new absolute position from GNSS or WiFi.
     */
    public void onNewPositionReceived(double measureX, double measureY, String source) {
        if (!isFilterInitialized) {
            // First time getting location? Spawn particles in a cluster here!
            for (int i = 0; i < NUM_PARTICLES; i++) {
                particles.add(new Particle(measureX, measureY, 1.0 / NUM_PARTICLES));
            }
            isFilterInitialized = true;
            Log.d("ParticleFilter", "Filter Initialized with 1000 particles at " + measureX + ", " + measureY);
        } else {
            // Already running? Update weights and Resample!
            double variance = WIFI_MEASUREMENT_VARIANCE;
            if ("gnss".equalsIgnoreCase(source)) {
                variance = GNSS_MEASUREMENT_VARIANCE;
            }

            updateParticlesWeights(measureX, measureY, variance);
            resampleParticles();
            Log.d("ParticleFilter", "Particles updated and resampled.");
        }
    }

    /**
     * Step 1: PREDICT (Move the particles based on steps)
     * Normal: try direct motion first.
     * If blocked by wall: enter short wall-follow mode and slide along wall.
     */
    //CHEN 2
    private void predictParticles(double stepLength, double heading) {
        double stepNoiseStdDev = 0.15;
        double headingNoiseStdDev = 0.08;

        int blockedCount = 0;

        for (Particle p : particles) {
            double oldX = p.x;
            double oldY = p.y;

            double noisyStep = Math.max(MIN_STEP_LENGTH,
                    stepLength + random.nextGaussian() * stepNoiseStdDev);
            double noisyHeading = heading + random.nextGaussian() * headingNoiseStdDev;

            double directX = oldX + noisyStep * Math.sin(noisyHeading);
            double directY = oldY + noisyStep * Math.cos(noisyHeading);

            if (!isBlocked(oldX, oldY, directX, directY)) {
                p.x = directX;
                p.y = directY;
                continue;
            }

            blockedCount++;

            MoveCandidate slide = trySimpleWallSlide(
                    oldX,
                    oldY,
                    noisyStep,
                    noisyHeading,
                    directX,
                    directY
            );

            if (slide != null) {
                p.x = slide.x;
                p.y = slide.y;
                p.weight *= slide.weightScale;
            } else {
                p.x = oldX;
                p.y = oldY;
                p.weight *= 0.95;
            }
        }

        if (!particles.isEmpty()) {
            double blockedRatio = blockedCount / (double) particles.size();

            if (blockedRatio > 0.30) {
                notifyWallDetected();
            }
        }
    }


    private static class MoveCandidate {
        double x;
        double y;
        double weightScale;

        MoveCandidate(double x, double y, double weightScale) {
            this.x = x;
            this.y = y;
            this.weightScale = weightScale;
        }
    }
    //END
    /**
     * Step 2: UPDATE (Score particles based on distance to measurement)
     */
    private void updateParticlesWeights(double measuredX, double measuredY, double variance) {
        double totalWeight = 0.0;

        for (Particle p : particles) {
            double distanceSquared = Math.pow(p.x - measuredX, 2) + Math.pow(p.y - measuredY, 2);
            p.weight *= Math.exp(-distanceSquared / (2 * variance));
            totalWeight += p.weight;
        }

        // --- THE RESCUE BLOCK FIX ---
        if (totalWeight < 0.000001) {
            Log.e("ParticleFilter", "Swarm is trapped! Respawning at new measurement.");

            // THE FIX: Wipe out the trapped clones and teleport them to the new WiFi/GNSS ping
            particles.clear();
            for (int i = 0; i < NUM_PARTICLES; i++) {
                // Add a 2-meter random spread so they don't all spawn exactly inside a solid brick
                double spawnX = measuredX + (random.nextGaussian() * 2.0);
                double spawnY = measuredY + (random.nextGaussian() * 2.0);

                particles.add(new Particle(spawnX, spawnY, 1.0 / NUM_PARTICLES));
            }
            return;
        }
        // ------------------------

        // Normalize weights
        for (Particle p : particles) {
            p.weight /= totalWeight;
        }
    }

    /**
     * Step 3: RESAMPLE (Delete bad guesses, clone good ones)
     */
    private void resampleParticles() {
        if (particles.isEmpty()) return;

        List<Particle> newParticles = new ArrayList<>(NUM_PARTICLES);
        double interval = 1.0 / NUM_PARTICLES;
        double r = random.nextDouble() * interval;
        double weightAccumulator = particles.get(0).weight;
        int i = 0;

        for (int j = 0; j < NUM_PARTICLES; j++) {
            double U = r + j * interval;
            while (U > weightAccumulator && i < NUM_PARTICLES - 1) {
                i++;
                weightAccumulator += particles.get(i).weight;
            }
            Particle chosen = particles.get(i);
            newParticles.add(new Particle(chosen.x, chosen.y, 1.0 / NUM_PARTICLES));
        }
        particles = newParticles;
    }

    /**
     * Calculates the center of the Particle Swarm (in meters),
     * translates it back to WGS84 Degrees, and hands it to the UI (Section 3.3).
     */
    public double[] getFusedEstimatedPosition() {
        // 1. Safety check: Don't do math if the filter hasn't started
        if (!isFilterInitialized || particles.isEmpty()) {
            return null;
        }

        double avgX = 0.0;
        double avgY = 0.0;
        double totalW = 0.0; // THE FIX: Track total weight

        // 2. Calculate the weighted center of the swarm (in Local Meters)
        for (Particle p : particles) {
            avgX += p.x * p.weight;
            avgY += p.y * p.weight;
            totalW += p.weight; // Add up the degraded weights
        }

        // THE FIX: True Weighted Average mathematically requires dividing by the sum!
        if (totalW > 0.0) {
            avgX /= totalW;
            avgY /= totalW;
        }

        // 3. THE OUTBOUND TRANSLATOR:
        // Convert the final X/Y meters BACK to global WGS84 Degrees
        double[] finalDegrees = convertMetersToWGS84(avgX, avgY);

        // 4. Return the Degrees to the UI
        return finalDegrees;
    }
    // ==========================================
    // EE HUNG MAP MATCHING INTERFACE (For Chen's Code)
    // ==========================================
    public interface MapConstraint {
        boolean crossesWallLocal(double oldX, double oldY, double newX, double newY);
    }

    //CHEN 2
    public interface WallEventListener {
        void onWallDetected();
    }

    private MapConstraint mapConstraint;

    public void setMapConstraint(MapConstraint constraint) {
        this.mapConstraint = constraint;
    }

    public LatLng convertLocalMetersToLatLng(double x, double y) {
        double[] degrees = convertMetersToWGS84(x, y);
        return new LatLng(degrees[0], degrees[1]);
    }

    public LatLng getFusedEstimatedLatLng() {
        if (!isFilterInitialized || particles.isEmpty()) return null;

        double[] degs = getFusedEstimatedPosition();
        if (degs == null) return null; // THE CRASH SHIELD

        return new LatLng(degs[0], degs[1]);
    }

    public LatLng getCurrentGnssLatLng() {
        if (Math.abs(latitude) < 1e-6 && Math.abs(longitude) < 1e-6) {
            return null;
        }
        return new LatLng(latitude, longitude);
    }

    public LatLng getCurrentWifiLatLng() {
        LatLng wifiLatLng = this.wiFiPositioning.getWifiLocation();
        if (wifiLatLng == null) return null;

        if (Math.abs(wifiLatLng.latitude) < 1e-6 && Math.abs(wifiLatLng.longitude) < 1e-6) {
            return null;
        }
        return wifiLatLng;
    }

    public LatLng getCurrentPdrLatLng() {
        if (pdrProcessing == null) return null;

        // THE FIX: Don't draw the yellow pin until we know exactly where you are!
        if (!hasAcquiredInitialLocation) return null;

        float[] pdr = pdrProcessing.getPDRMovement();
        if (pdr == null || pdr.length < 2) return null;

        // 1. Find out where your dynamic start location is on our 2D grid
        double[] startMeters = convertToLocalMeters(startLocation[0], startLocation[1]);

        // 2. Add your raw PDR footsteps to that actual dynamic starting point
        double actualX = startMeters[0] + pdr[0];
        double actualY = startMeters[1] + pdr[1];

        // 3. Translate the true location back to GPS degrees for the map
        double[] degs = convertMetersToWGS84(actualX, actualY);
        return new LatLng(degs[0], degs[1]);
    }


    private boolean isBlocked(double oldX, double oldY, double newX, double newY) {
        if (mapConstraint == null) return false;

        try {
            return mapConstraint.crossesWallLocal(oldX, oldY, newX, newY);
        } catch (Exception e) {
            return false;
        }
    }

    private MoveCandidate buildCandidate(
            double oldX,
            double oldY,
            double step,
            double heading,
            double angleOffset,
            double weightScale
    ) {
        double testHeading = heading + angleOffset;
        double newX = oldX + step * Math.sin(testHeading);
        double newY = oldY + step * Math.cos(testHeading);

        if (isBlocked(oldX, oldY, newX, newY)) {
            return null;
        }

        return new MoveCandidate(newX, newY, weightScale);
    }

    private void notifyWallDetected() {
        if (wallEventListener != null) {
            wallEventListener.onWallDetected();
        }
    }
    //CHEN 2
    private MoveCandidate trySimpleWallSlide(
            double oldX,
            double oldY,
            double step,
            double heading,
            double targetX,
            double targetY
    ) {
        double[] stepScales = new double[] {1.1, 0.9, 0.7};

        double[] leftOffsets = new double[] {
                Math.toRadians(95),
                Math.toRadians(85),
                Math.toRadians(70)
        };

        double[] rightOffsets = new double[] {
                -Math.toRadians(95),
                -Math.toRadians(85),
                -Math.toRadians(70)
        };

        MoveCandidate best = null;
        double bestDist2 = Double.POSITIVE_INFINITY;

        for (double scale : stepScales) {
            double slideStep = Math.max(MIN_STEP_LENGTH, step * scale);

            for (double offset : leftOffsets) {
                MoveCandidate left = buildCandidate(oldX, oldY, slideStep, heading, offset, 1.0);
                if (left != null) {
                    double dist2 = Math.pow(left.x - targetX, 2) + Math.pow(left.y - targetY, 2);
                    if (dist2 < bestDist2) {
                        bestDist2 = dist2;
                        best = left;
                    }
                }
            }

            for (double offset : rightOffsets) {
                MoveCandidate right = buildCandidate(oldX, oldY, slideStep, heading, offset, 1.0);
                if (right != null) {
                    double dist2 = Math.pow(right.x - targetX, 2) + Math.pow(right.y - targetY, 2);
                    if (dist2 < bestDist2) {
                        bestDist2 = dist2;
                        best = right;
                    }
                }
            }

            if (best != null) {
                return best;
            }
        }

        return null;
    }

    public void forceRelocateToLatLng(@NonNull LatLng latLng) {
        if (latLng == null) return;

        double[] local = convertToLocalMeters(latLng.latitude, latLng.longitude);
        double targetX = local[0];
        double targetY = local[1];

        if (!isFilterInitialized || particles.isEmpty()) {
            particles.clear();
            for (int i = 0; i < NUM_PARTICLES; i++) {
                particles.add(new Particle(targetX, targetY, 1.0 / NUM_PARTICLES));
            }
            isFilterInitialized = true;
            return;
        }

        particles.clear();
        for (int i = 0; i < NUM_PARTICLES; i++) {
            double spawnX = targetX + (random.nextGaussian() * 0.3);
            double spawnY = targetY + (random.nextGaussian() * 0.3);
            particles.add(new Particle(spawnX, spawnY, 1.0 / NUM_PARTICLES));
        }
    }
    //END
}
