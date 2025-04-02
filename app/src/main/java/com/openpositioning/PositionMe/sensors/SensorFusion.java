package com.openpositioning.PositionMe.sensors;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.FusionAlgorithms.ParticleFilter;
import com.openpositioning.PositionMe.data.remote.WiFiPositioning;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.utils.CoordinateTransform;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.PdrProcessing;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.presentation.fragment.SettingsFragment;
import com.openpositioning.PositionMe.FusionAlgorithms.EKF;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;
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
    // 回调接口，用于楼层变化通知
    private Consumer<Integer> wifiFloorChangedListener;

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
    float[] filteredAcc;
    private float[] gravity;
    private float[] magneticField;
    private float[] angularVelocity;
    float[] orientation;
    float[] rotation;
    float pressure;
    float light;
    float proximity;
    private float[] R;
    int stepCounter ;
    // Derived values
    private float elevation;
    private boolean elevator;
    // Location values
    private float latitude;
    private float longitude;
    private float[] startLocation;
    // Wifi values
    private List<Wifi> wifiList;
    private long lastOpUpdateTime = 0; // 存储 WiFi / GNSS 最后一次更新的时间戳
    private long lastGnssUpdateTime = 0; // 记录上次 GNSS 更新时间
    private LatLng wifiPos;


    // Over time accelerometer magnitude values since last step
    private List<Double> accelMagnitude;

    // PDR calculation class
    private PdrProcessing pdrProcessing;

    // Trajectory displaying class
    private PathView pathView;
    // WiFi positioning object
    private WiFiPositioning wiFiPositioning;
    private double[] startRef;
    private double refLat, refLon, refAlt;
    private double[] ecefRefCoords;
    private EKF extendedKalmanFilter;

    private LatLng pendingWifiPosition = null;
    private long wifiPositionTimestamp = 0;
    private long wifiReceivedTime = 0;
    private int wifiFloor = 0;
    private float gnssAccuracy = 100f;

    private ParticleFilter particleFilter;
    private static final double PF_EKF_THRESHOLD = 10.0;

    private double previousOrientation = 0.0;

    private com.openpositioning.PositionMe.presentation.fragment.TrajectoryMapFragment trajectoryMapFragment;

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

        // WIFI initial values
        this.wifiPos = null;


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
        // 尝试获取最新 GNSS 坐标并更新 ECEF 参考坐标
        float[] latestLatLon = getGNSSLatitude(true);
        if (latestLatLon[0] != 0.0 || latestLatLon[1] != 0.0) {
            this.refLat = latestLatLon[0];
            this.refLon = latestLatLon[1];
            this.refAlt = 0.0;
            // ecefRefCoords = CoordinateTransform.geodeticToEcef(latestLatLon[0], latestLatLon[1], 0.0);
            Log.d("SensorFusion", "Set reference location: refLat=" + refLat + ", refLon=" + refLon + ", refAlt=" + refAlt);
        }
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
        if (startRef == null) {
            Log.w("SensorFusion", "startRef is uninitialized, setting default ECEF reference.");
            startRef = new double[]{0.0, 0.0, 0.0}; // 默认 ECEF 参考坐标
        }

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
                break;

            case Sensor.TYPE_STEP_DETECTOR:
                long stepTime = SystemClock.uptimeMillis() - bootTime;
                if (currentTime - lastStepTime < 20) {
                    Log.e("SensorFusion", "Ignoring step event, too soon after last step event:" + (currentTime - lastStepTime) + " ms");
                    break;
                } else {
                    lastStepTime = currentTime;
                    if (accelMagnitude.isEmpty()) {
                        Log.e("SensorFusion", "stepDetection triggered, but accelMagnitude is empty! This can cause updatePdr(...) to fail or return bad results.");
                    } else {
                        Log.d("SensorFusion", "stepDetection triggered, accelMagnitude size = " + accelMagnitude.size());
                    }
                    float[] newCords = this.pdrProcessing.updatePdr(stepTime, this.accelMagnitude, this.orientation[0]);
                    this.accelMagnitude.clear();
                    float stepLen = this.pdrProcessing.getStepLength(); // 获取当前步长

                    double theta = wrapToPi(this.orientation[0]); // 获取当前方向角
                    Log.d("SensorFusion", "Step detected: stepLen=" + stepLen + ", theta=" + theta);



                if (saveRecording) {
                    if (trajectoryMapFragment != null && newCords != null) {
                        LatLng rawPdrLatLng = CoordinateTransform.enuToGeodetic(
                                newCords[0], newCords[1], 0.0,
                                refLat, refLon, refAlt
                        );
                        float headingDeg = (float) Math.toDegrees(this.orientation[0]);
                        trajectoryMapFragment.updateUserLocation(rawPdrLatLng, headingDeg);
                    }
                    double currentOrientation = wrapToPi(this.orientation[0]);
                    double deltaHeading = wrapToPi(currentOrientation - previousOrientation);
                    previousOrientation = currentOrientation;
                    Log.d("SensorFusion", "Step detected: stepLen=" + stepLen + ", deltaHeading=" + deltaHeading);

                    if (extendedKalmanFilter != null) {
                        extendedKalmanFilter.predict(stepLen, deltaHeading);
                        // --- PF 辅助 EKF 逻辑（仅在室内场景启用）---
                        if (particleFilter != null) {
                            particleFilter.predict(stepLen, deltaHeading);
//                            if (pendingWifiPosition != null &&
//                                    SystemClock.uptimeMillis() - wifiPositionTimestamp < 2000) {
//                                double[] wifiENU = CoordinateTransform.geodeticToEnu(
//                                        pendingWifiPosition.latitude,
//                                        pendingWifiPosition.longitude,
//                                        0.0,
//                                        refLat, refLon, refAlt
//                                );
//                                particleFilter.updateWiFi(wifiENU[0], wifiENU[1]);
//                            }
                            if (stepCounter % 5 == 0) {
                                particleFilter.resample();
                            }

                            LatLng pfEstimate = particleFilter.getEstimateLatLng();
                            LatLng ekfEstimate = extendedKalmanFilter.getEstimatedPosition(refLat, refLon, refAlt);

                            double diff = computeDistance(ekfEstimate, pfEstimate);
                            double pfEntropy = particleFilter.computeEntropy();
                            if (diff > PF_EKF_THRESHOLD && pfEntropy < 5.0) {
                                double[] enuReset = CoordinateTransform.geodeticToEnu(
                                        pfEstimate.latitude, pfEstimate.longitude, 0.0,
                                        refLat, refLon, refAlt
                                );
                                extendedKalmanFilter.resetPosition(enuReset[0], enuReset[1]);
                                Log.d("SensorFusion", "EKF reset with PF due to drift > " + diff + " m");
                            }
                        }else {
                            Log.e("SensorFusion", "ParticleFilter is not initialized!");
                        }

                    } else {
                        Log.e("SensorFusion", "EKF is not initialized!");
                    }

                    // 获取最新 GNSS 位置
                    Location gnssLocation = gnssProcessor.getLastKnownLocation();
                    if (gnssLocation != null) {
                        Log.d("SensorFusion", "GNSS Location available for update: Lat=" + gnssLocation.getLatitude() +
                                ", Lon=" + gnssLocation.getLongitude() + ", Accuracy=" + gnssLocation.getAccuracy());
                        gnssAccuracy = gnssLocation.getAccuracy();
                    } else {
                        Log.d("SensorFusion", "GNSS Location is null, skipping GNSS correction.");
                    }
                    // 获取当前 WiFi 指纹数据
                    List<Wifi> currentWifiList = getWifiList();  // 最新扫描结果
                    double avgRssi = Double.NaN;
                    if (currentWifiList != null && !currentWifiList.isEmpty()) {
                        avgRssi = currentWifiList.stream().mapToInt(Wifi::getLevel).average().orElse(Double.NaN);
                        Log.d("SensorFusion", "StepDetected - Avg WiFi RSSI: " + avgRssi);
                    } else {
                        Log.w("SensorFusion", "WiFi list is empty, skipping WiFi RSSI calculation.");
                    }

                    JSONObject wifiResponse = null;
                    if (pendingWifiPosition != null &&
                            SystemClock.uptimeMillis() - wifiPositionTimestamp < 2000) {
                        wifiResponse = new JSONObject();
                        try {
                            wifiResponse.put("lat", pendingWifiPosition.latitude);
                            wifiResponse.put("lon", pendingWifiPosition.longitude);
                            wifiResponse.put("floor", wifiFloor);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }


                    }
                    pendingWifiPosition = null;
                    updateFusion(wifiResponse, gnssLocation, avgRssi);


                    // 调用 EKF 的 updateGNSS 以确保 GNSS 数据被用于修正
                    if (gnssLocation != null && extendedKalmanFilter != null) {
                        double lat = gnssLocation.getLatitude();
                        double lon = gnssLocation.getLongitude();
                        double alt = gnssLocation.getAltitude();
                        double[] enuCoords = CoordinateTransform.geodeticToEnu(lat, lon, alt, refLat, refLon, refAlt);
                       // extendedKalmanFilter.updateGNSS(enuCoords[0], enuCoords[1], enuCoords[2], 1.0);
                    }
                    // 从融合算法中获取修正后的定位结果
                    if (extendedKalmanFilter != null) {
                        LatLng fusedPos = extendedKalmanFilter.getEstimatedPosition(refLat, refLon, refAlt);
                        //pathView.drawTrajectory(new float[]{(float) fusedPos.latitude, (float) fusedPos.longitude});
                        trajectory.addPdrData(Traj.Pdr_Sample.newBuilder()
                                .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                                .setX((float) fusedPos.latitude)
                                .setY((float) fusedPos.longitude));
                        if (trajectoryMapFragment != null) {
                        float headingDeg = (float) Math.toDegrees(this.orientation[0]);
                        trajectoryMapFragment.updateFusionLocation(fusedPos, headingDeg);
                        }
                    } else {
                        Log.e("SensorFusion", "EKF is null when trying to get estimated position!");
                    }
                    stepCounter++;
                }
                break;
                }

        }
    }




    public void updateFusion(JSONObject wifiResponse, Location gnssLocation, double avgRssi) {
        try {
            long currentTime = SystemClock.uptimeMillis();
            double gnssAccuracy = gnssLocation != null ? gnssLocation.getAccuracy() : Double.MAX_VALUE;
            boolean useWiFi = wifiResponse != null && wifiResponse.has("lat") && wifiResponse.has("lon");
            boolean useGNSS =!useWiFi && gnssLocation != null && gnssAccuracy < 20.0 && (currentTime - lastGnssUpdateTime > 5000);
            if(useGNSS){
                lastGnssUpdateTime = currentTime;
            }
            Log.d("SensorFusion", "updateFusion: gnssAccuracy=" + gnssAccuracy + ", useWiFi=" + useWiFi + ", useGNSS=" + useGNSS + ", avgRssi=" + avgRssi);

            if (useWiFi) {
                Log.d("SensorFusion", "WiFi response raw: " + wifiResponse.toString());
                double lat = wifiResponse.getDouble("lat");
                double lon = wifiResponse.getDouble("lon");
                double[] enuCoords = CoordinateTransform.geodeticToEnu(lat, lon, getElevation(), refLat, refLon, refAlt);

                Log.d("SensorFusion", "Using WiFi for EKF update: East=" + enuCoords[0] + ", North=" + enuCoords[1]);
                long fusionTime = System.currentTimeMillis();
                Log.d("SensorFusion", "WiFi定位结果使用时间: " + fusionTime + "，相对延迟: " + (fusionTime - wifiReceivedTime) + " ms");

                if (extendedKalmanFilter != null) {
                    double timeSinceLastUpdate = SystemClock.uptimeMillis() - lastOpUpdateTime;
                    lastOpUpdateTime = SystemClock.uptimeMillis();
                    double penaltyFactor = calculatePenaltyFactor(avgRssi, timeSinceLastUpdate);

                    extendedKalmanFilter.update(enuCoords[0], enuCoords[1], penaltyFactor);
                    Log.d("SensorFusion", "EKF updated with WiFi: penaltyFactor=" + penaltyFactor);
                }
                if (particleFilter != null) {
                    particleFilter.updateWiFi(enuCoords[0], enuCoords[1]);
                    Log.d("SensorFusion", "PF updated with WiFi");
                }
            } else if (useGNSS && gnssLocation != null) {
                double lat = gnssLocation.getLatitude();
                double lon = gnssLocation.getLongitude();
                double alt = gnssLocation.getAltitude();

                double[] enuCoords = CoordinateTransform.geodeticToEnu(lat, lon, alt, refLat, refLon, refAlt);
                Log.d("SensorFusion", "Using GNSS for EKF update: East=" + enuCoords[0] + ", North=" + enuCoords[1] + ", Alt=" + enuCoords[2]);
                Log.d("SensorFusion", "Raw GNSS: Lat=" + lat + ", Lon=" + lon + ", Alt=" + alt + ", Accuracy=" + gnssLocation.getAccuracy());

                if (extendedKalmanFilter != null) {
                    extendedKalmanFilter.updateGNSS(enuCoords[0], enuCoords[1], enuCoords[2], 1.0);
                    Log.d("SensorFusion", "EKF updated with GNSS.");
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }


    private double calculatePenaltyFactor(double rssi, double elapsedTime) {
        double baseFactor;

        // WiFi 误差自适应
        if (rssi > -50) {
            baseFactor = 0.75;  // 强 WiFi 信号时减少噪声影响
        } else if (rssi > -75) {
            baseFactor = 1.5;  // 普通 WiFi 信号，不做调整
        } else {
            baseFactor = 2.0;  // 弱 WiFi 信号时增加噪声影响
        }

        // 时间误差动态调整（0.1/秒，最多调整到 4.0）
        double timePenalty = 1.0 + Math.min(elapsedTime / 10000.0, 3.0);

        return baseFactor * timePenalty;
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
        public void onLocationChanged(Location location) {
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
        this.wifiList = Stream.of(wifiList).map(o -> (Wifi) o).collect(Collectors.toList());

        // 构建 trajectory WiFi 数据（略）

        // ✅ 计算 avgRssi
        double avgRssi = -100;
        if (!this.wifiList.isEmpty()) {
            avgRssi = this.wifiList.stream().mapToInt(Wifi::getLevel).average().orElse(-100);
        }

        // ✅ 传入 avgRssi
        createWifiPositioningRequest(avgRssi);
    }


    /**
     * Function to create a request to obtain a wifi location for the obtained wifi fingerprint
     *
     */
    private void createWifiPositioningRequest(double avgRssi) {
        final long requestStartTime = System.currentTimeMillis();
        try {
            JSONObject wifiAccessPoints = new JSONObject();
            for (Wifi data : this.wifiList){
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }

            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);

            Log.d("SensorFusion", "Sending WiFi fingerprint: " + wifiFingerPrint.toString());

            this.wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng wifiLocation, int floor) {
                    Log.d("SensorFusion", "Received WiFi location: lat=" + wifiLocation.latitude + ", lon=" + wifiLocation.longitude + ", floor=" + floor);

                    pendingWifiPosition = wifiLocation;
                    wifiFloor = floor;
                    wifiPositionTimestamp = SystemClock.uptimeMillis();
                    wifiReceivedTime = System.currentTimeMillis();  // 用于日志分析
                    if (trajectoryMapFragment != null && wifiLocation != null) {
                        trajectoryMapFragment.updateWifiLocation(wifiLocation, 0f);  // 0f as placeholder heading
                    }
                    Log.d("SensorFusion", "WiFi store success, store time: " + wifiReceivedTime);
                    long responseTime = System.currentTimeMillis();
                    long delay = responseTime - requestStartTime;
                    Log.d("SensorFusion", "WiFi请求总延迟: " + delay + "ms");
                    if (wifiFloorChangedListener != null) {
                        wifiFloorChangedListener.accept(floor);
                    }

                }

                @Override
                public void onError(String message) {
                    Log.e("SensorFusion", "WiFi positioning error: " + message);
                }
            });

        } catch (JSONException e) {
            Log.e("SensorFusion", "JSON error while creating WiFi fingerprint", e);
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
     * {@inheritDoc}
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {}
    public float getGnssAccuracy() {
        return gnssAccuracy;
    }
    private double wrapToPi(double angle) {
        while(angle > Math.PI) angle -= 2*Math.PI;
        while(angle < -Math.PI) angle += 2*Math.PI;
        return angle;
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

    public WiFiPositioning getWiFiPositioning() {
        return this.wiFiPositioning;
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
        // If w`        ZakeLock is null (e.g. not initialized or was cleared), reinitialize it.
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

        // 先检查 orientation 是否为空
        if (orientation == null || orientation.length < 1) {
            Log.e("SensorFusion", "Orientation array is null or uninitialized!");
            return;
        }

        // 获取 GNSS 初始经纬度 using system lastKnownLocation first, then fallback to polling
        float[] initLatLon = new float[2];
        LocationManager locationManager = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);

        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastKnownLocation != null) {
                initLatLon[0] = (float) lastKnownLocation.getLatitude();
                initLatLon[1] = (float) lastKnownLocation.getLongitude();
                Log.d("SensorFusion", "Using lastKnownLocation: " + initLatLon[0] + ", " + initLatLon[1]);
            }
        }

        double initAltitude = 0.0;
        if (initLatLon[0] == 0.0 && initLatLon[1] == 0.0) {
            Log.e("SensorFusion", "GNSS lastKnownLocation unavailable, trying polling fallback.");
//            for (int i = 0; i < 10; i++) {
//                SystemClock.sleep(1000);
//                float[] polled = getGNSSLatitude(true);
//                if (polled[0] != 0.0 || polled[1] != 0.0) {
//                    initLatLon = polled;
//                    break;
//                }
//            }
        }

        if (initLatLon[0] == 0.0 && initLatLon[1] == 0.0) {
            // After multiple attempts, still invalid: delay error logging until now.
            Log.e("SensorFusion", "GNSS fix still unavailable after multiple attempts, using default reference!");
            ecefRefCoords = new double[]{0.0, 0.0, 0.0};
            } else {
                Log.d("SensorFusion", "GNSS initial LatLon: " + initLatLon[0] + ", " + initLatLon[1]);
                if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (lastKnownLocation != null) {
                        initAltitude = lastKnownLocation.getAltitude();
                        Log.d("SensorFusion", "Using lastKnownLocation: " + initLatLon[0] + ", " + initLatLon[1] + ", Alt=" + initAltitude);
                    }
                }
                refLat = initLatLon[0];
                refLon = initLatLon[1];
                refAlt = initAltitude;
                // 转换 GNSS 经纬度到 ECEF 坐标
                ecefRefCoords = CoordinateTransform.geodeticToEcef(initLatLon[0], initLatLon[1], initAltitude);
                if (ecefRefCoords == null || ecefRefCoords.length < 3) {
                    Log.e("SensorFusion", "Failed to compute ECEF reference coordinates! Using default.");
                    ecefRefCoords = new double[]{0.0, 0.0, 0.0};
                } else {
                    Log.d("SensorFusion", "Computed ECEF reference: X=" + ecefRefCoords[0] +
                            ", Y=" + ecefRefCoords[1] + ", Z=" + ecefRefCoords[2]);
                }
            }
        // Ensure startRef is set only once based on the final valid (or default) ECEF coordinates.
        startRef = ecefRefCoords.clone();
        Log.d("SensorFusion", "startRef initialized with ECEF reference: " +
                "X=" + startRef[0] + ", Y=" + startRef[1] + ", Z=" + startRef[2]);

        // 进行地理坐标转换
        double[] enuCoords = CoordinateTransform.geodeticToEnu(
                initLatLon[0], initLatLon[1], initAltitude,
                refLat, refLon, refAlt
        );
        this.pdrProcessing.resetPDR();
        Log.d("SensorFusion", "Setting PDR initial ENU to: x=" + enuCoords[0] + ", y=" + enuCoords[1]);
        pdrProcessing.setInitialPosition((float) enuCoords[0], (float) enuCoords[1]);


        // 获取当前设备方向
        double initialTheta = wrapToPi(this.orientation[0]);

        Log.d("SensorFusion", "First EKF Init: Lat=" + initLatLon[0] + ", Lon=" + initLatLon[1]);
        Log.d("SensorFusion", "Initial Theta=" + initialTheta);

        // 初始化 EKF
        extendedKalmanFilter = new EKF(enuCoords[0], enuCoords[1], 0.0, initialTheta);
        Log.d("SensorFusion", "EKF initialized successfully: " +
                "East=" + enuCoords[0] + ", North=" + enuCoords[1] + ", Theta=" + initialTheta);
        // 初始化 PF
        particleFilter = new ParticleFilter(100, refLat, refLon, refAlt);
        particleFilter.initializeParticles(enuCoords[0], enuCoords[1], initialTheta, 1.0, 1.0, 0.1);
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

    public void setTrajectoryMapFragment(com.openpositioning.PositionMe.presentation.fragment.TrajectoryMapFragment fragment) {
        this.trajectoryMapFragment = fragment;
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
        return SensorFusionUtils.createInfoBuilder(sensor);
    }


    /**
     * Returns a JSONObject containing all sensor data (IMU, GNSS, WiFi, PDR, etc.).
     * This is a helper method to unify data retrieval for logs or training.
     *
     * @return JSONObject with keys for all sensor readings.
     */
    public JSONObject getAllSensorData() {
        return SensorFusionUtils.getAllSensorData(this, wiFiPositioning);  // "this" = current SensorFusion
    }

    private double computeDistance(LatLng a, LatLng b) {
        double latDiff = (a.latitude - b.latitude) * 111000;
        double lonDiff = (a.longitude - b.longitude) * 111000 * Math.cos(Math.toRadians((a.latitude + b.latitude) / 2));
        return Math.sqrt(latDiff * latDiff + lonDiff * lonDiff);
    }

    public void setOnWifiFloorChangedListener(Consumer<Integer> listener) {
        this.wifiFloorChangedListener = listener;
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
}



