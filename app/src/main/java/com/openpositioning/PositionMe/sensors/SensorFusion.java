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
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.openpositioning.PositionMe.MainActivity;
import com.openpositioning.PositionMe.PathView;
import com.openpositioning.PositionMe.PdrProcessing;
import com.openpositioning.PositionMe.ServerCommunications;
import com.openpositioning.PositionMe.Traj;
import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.UtilFunctions;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
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
    private float pressure = 1013.25f;
//    private float pressure;
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
    private WiFiPositioning wiFiPositioning;

    private FilterUtils.ParticleFilter pf;
    private FilterUtils.EKFFilter ekf;

    private float[] fusionLocation;

    int MAX_WIFI_APS = 60;
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
        // Initialise data collection devices
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
        this.gnssProcessor = new GNSSDataProcessor(context,locationListener);
        // Create object handling HTTPS communication
        this.serverCommunications = new ServerCommunications(context);
        // Save absolute and relative start time
        this.absoluteStartTime = System.currentTimeMillis();
        this.bootTime = android.os.SystemClock.uptimeMillis();
        // Initialise saveRecording to false - only record when explicitly started.
        this.saveRecording = false;

        // Over time data holder
        this.accelMagnitude = new ArrayList<>();
        // PDR
        this.pdrProcessing = new PdrProcessing(context);
        //Settings
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);

        this.pathView = new PathView(context, null);
        // Initialising WiFi Positioning object
        this.wiFiPositioning=new WiFiPositioning(context);

        if(settings.getBoolean("overwrite_constants", false)) {
            this.filter_coefficient =Float.parseFloat(settings.getString("accel_filter", "0.96"));
        }
        else {this.filter_coefficient = FILTER_COEFFICIENT;}

        // Keep app awake during the recording
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "MyApp::MyWakelockTag");

        pf = new FilterUtils.ParticleFilter(200, 0, 0, 1);
        ekf = new FilterUtils.EKFFilter(0, 0, 1.0, 1, 2);
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
        switch (sensorEvent.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                // Accelerometer processing
                acceleration[0] = sensorEvent.values[0];
                acceleration[1] = sensorEvent.values[1];
                acceleration[2] = sensorEvent.values[2];
                break;

            case Sensor.TYPE_PRESSURE:
                float rawPressure = sensorEvent.values[0];

                // ✅ 1. 判空或非法值
                if (Float.isNaN(rawPressure) || rawPressure <= 0) {
                    Log.w("PDR", "Invalid pressure reading, skipped.");
                    break;
                }

                // ✅ 2. 判定范围是否合理（地球大气压力范围大致是 850~1100 hPa）
                if (rawPressure < 850f || rawPressure > 1100f) {
                    Log.w("PDR", "Out-of-range pressure value: " + rawPressure);
                    break;
                }

                // ✅ 3. 判断突变（与上一帧差值过大）
                if (Math.abs(rawPressure - pressure) > 10f) {  // 可调阈值，比如超过10 hPa
                    Log.w("PDR", "Sudden jump in pressure value, skipped.");
                    break;
                }

                // ✅ 4. 平滑气压
                pressure = (1 - ALPHA) * pressure + ALPHA * rawPressure;

                // ✅ 5. 更新 elevation
                if (saveRecording) {
                    this.elevation = pdrProcessing.updateElevation(SensorManager.getAltitude(
                            SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure));
                }
                break;

            case Sensor.TYPE_GYROSCOPE:
                // Gyro processing
                //Store gyroscope readings
                angularVelocity[0] = sensorEvent.values[0];
                angularVelocity[1] = sensorEvent.values[1];
                angularVelocity[2] = sensorEvent.values[2];
                break;


            case Sensor.TYPE_LINEAR_ACCELERATION:
                // Acceleration processing with gravity already removed
                filteredAcc[0] = sensorEvent.values[0];
                filteredAcc[1] = sensorEvent.values[1];
                filteredAcc[2] = sensorEvent.values[2];

                double accelMagFiltered = Math.sqrt(Math.pow(acceleration[0], 2) +
                        Math.pow(acceleration[1], 2) + Math.pow(acceleration[2], 2));
                this.accelMagnitude.add(accelMagFiltered);
                elevator = pdrProcessing.estimateElevator(gravity, filteredAcc);
                break;

            case Sensor.TYPE_GRAVITY:
                // Gravity processing obtained from acceleration
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
                //Store magnetic field readings
                magneticField[0] = sensorEvent.values[0];
                magneticField[1] = sensorEvent.values[1];
                magneticField[2] = sensorEvent.values[2];
                break;

            case Sensor.TYPE_ROTATION_VECTOR:
                // Save values
                this.rotation = sensorEvent.values.clone();
                float[] rotationVectorDCM = new float[9];
                // Convert rotation vector to a 3*3 DCM
                SensorManager.getRotationMatrixFromVector(rotationVectorDCM,this.rotation);
                // Convert DCM to euler angles [0] = yaw(azimuth), [1] = pitch, [2] = roll
                SensorManager.getOrientation(rotationVectorDCM, this.orientation);
                break;

            case Sensor.TYPE_STEP_DETECTOR:

//                long stepTime = android.os.SystemClock.uptimeMillis() - bootTime;
//                float[] newCords = this.pdrProcessing.updatePdr(stepTime, this.accelMagnitude, this.orientation[0]);
//                Log.e("PDR", "x: " + newCords[0] + ", y: " + newCords[1]);
//
//                // 1. Prediction: update the EKF state with the new PDR reading.
//                ekf.predict(newCords[0], newCords[1]);
//
//                // 2. Update: if a WiFi position is available, update the filter.
//                if (getLatLngWifiPositioning() != null) {
//                    LatLng latLng = getLatLngWifiPositioning();
//                    LatLng startLocation = new LatLng(this.startLocation[0], this.startLocation[1]);
//                    double[] wifiPos = UtilFunctions.convertLatLangToNorthingEasting(startLocation, latLng);
//                    Log.e("wifiPos", "x: " + wifiPos[0] + ", y: " + wifiPos[1]);
//                    ekf.update(wifiPos[0], wifiPos[1]);
//                }
//
//                // 3. If GPS measurements are available
//                // if (gpsDataAvailable()) {
//                //     double[] gpsPos = getGPSPosition();
//                //     ekf.update(gpsPos[0], gpsPos[1]);
//                // }
//
//                // 4. Retrieve and log the current EKF estimate.
//                Log.e("EKF Filter", "x: " + ekf.getX() + ", y: " + ekf.getY());
//
//                if (saveRecording) {
//                    // Draw the updated trajectory.
//                    this.pathView.drawTrajectory(new float[]{(float) ekf.getX(), (float) ekf.getY()});
//                }
//
//                this.accelMagnitude.clear();
//
//                if (saveRecording) {
//                    stepCounter++;
//                    trajectory.addPdrData(Traj.Pdr_Sample.newBuilder()
//                            .setRelativeTimestamp(android.os.SystemClock.uptimeMillis() - bootTime)
//                            .setX((float) ekf.getX())
//                            .setY((float) ekf.getY()));
//                }

                // *** Particle start ***
                //Store time of step
                long stepTime = android.os.SystemClock.uptimeMillis() - bootTime;

                // ✅ 添加判断：如果加速度点太少，就跳过这次步长估计
                int MIN_ACCEL_SAMPLES = 10;  // 可根据你采样率和步频实际情况调整
                if (this.accelMagnitude.size() < MIN_ACCEL_SAMPLES) {
                    // 不调用 updatePdr，跳过位置更新
                    Log.w("PDR", "Skipped step: not enough accel samples (" + accelMagnitude.size() + ")");
                    this.accelMagnitude.clear(); // 仍要清空缓存，准备下一步
                    break;
                }

                // ✅ 加速度数据量足够，正常执行 PDR 更新
                float[] newCords = this.pdrProcessing.updatePdr(stepTime, this.accelMagnitude, this.orientation[0]);
                Log.e("PDR", "x: " + newCords[0] + ", y: " + newCords[1]);
                // *** new
                pf.predict(newCords[0], newCords[1]);
                if (getLatLngWifiPositioning() != null) {
                    LatLng latLng = getLatLngWifiPositioning();
                    LatLng startLocation = new LatLng(this.startLocation[0], this.startLocation[1]);
                    double[] wifiPos = UtilFunctions.convertLatLangToNorthingEasting(startLocation, latLng);
                    Log.e("wifiPos", "x: " + wifiPos[0] + ", y: " + wifiPos[1]);
                    pf.setWifiRatio(ratio);
                    Log.e("Ratio", String.valueOf(ratio));
                    pf.update(wifiPos[0], wifiPos[1]);
                }

//                // 3. 如果有 GPS 测量，则进行更新

//                if (gpsDataAvailable()) {
//                    double[] gpsPos = getGPSPosition();
//                    pf.update(gpsPos[0], gpsPos[1]);
//                }
                // 4. 重采样
                pf.resample();

                // 5. 估计当前状态
                FilterUtils.Particle currentState = pf.estimate();
                newCords = new float[]{(float) currentState.x, (float) currentState.y};
                Log.e("Particle Filter", "x: " + currentState.x + ", y: " + currentState.y);

                if (saveRecording) {
                    // Store the PDR coordinates for plotting the trajectory
                    this.pathView.drawTrajectory(newCords);
                }

                // ✅ 步长估计后，清空加速度缓存
                this.accelMagnitude.clear();

                if (saveRecording) {
                    stepCounter++;
                    trajectory.addPdrData(Traj.Pdr_Sample.newBuilder()
                            .setRelativeTimestamp(android.os.SystemClock.uptimeMillis() - bootTime)
                            .setX(newCords[0]).setY(newCords[1]));
                }

                fusionLocation = new float[]{newCords[0], newCords[1]};
                this.pdrProcessing.setCurrentLocation(newCords[0], newCords[1]);
                break;
            // *** Particle END ***
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
            if(location != null){
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
        Log.d("Wifi List", this.wifiList.toString()); // Added to print wifi list

        if(this.saveRecording) {
            Log.e("Wifi", "Wifi data saved"); // Added to notify the data is saved
            Traj.WiFi_Sample.Builder wifiData = Traj.WiFi_Sample.newBuilder()
                    .setRelativeTimestamp(android.os.SystemClock.uptimeMillis()-bootTime);
            for (Wifi data : this.wifiList) {
                wifiData.addMacScans(Traj.Mac_Scan.newBuilder()
                        .setRelativeTimestamp(android.os.SystemClock.uptimeMillis() - bootTime)
                        .setMac(data.getBssid()).setRssi(data.getLevel()));
            }
            // Adding WiFi data to Trajectory
            this.trajectory.addWifiData(wifiData);
        }
//        createWifiPositioningRequest();
        createWifiPositionRequestCallback();
    }

    /**
     * Function to create a request to obtain a wifi location for the obtained wifi fingerprint
     *
     */
    private void createWifiPositioningRequest(){
        try {
            //take no. of MAX_WIFI_APS, and sort them from higher power to lower
            List<Wifi> sortedWifiList = this.wifiList.stream()
                    .sorted(Comparator.comparingInt(Wifi::getLevel).reversed())  // -30 is higher than -90
                    .limit(MAX_WIFI_APS)
                    .collect(Collectors.toList());

//            // Creating a JSON object to store the WiFi access points
//            JSONObject wifiAccessPoints=new JSONObject();
//
//            for (Wifi data : sortedWifiList) {
//                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
//            }

//            // Creating a JSON object to store the WiFi access points
//            JSONObject wifiAccessPoints=new JSONObject();
//            for (Wifi data : this.wifiList){
//                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
//            }
            // Creating POST Request
            JSONObject wifiFingerPrint = new JSONObject();
            JSONObject wf = new JSONObject();
//            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);

            for (Wifi data : sortedWifiList) {
//                Log.e("WiFi-Bssid", String.valueOf(data.getBssid()));
//                Log.e("WiFi-Level", String.valueOf(data.getLevel()));
                wf.put(String.valueOf(data.getBssid()), String.valueOf(data.getLevel()));
            }
            wifiFingerPrint.put("wf", wf);

            // Check if the wifiFinerPrint is empty
            if(wifiFingerPrint.length()==0){
                Log.e("wifiFingerPrint","Empty");
            }else {
                // Logging the wifi fingerprint
                Log.e("wifiFingerPrint",wifiFingerPrint.toString());
            }
            Log.d("wifiFingerPrint", String.valueOf(wifiFingerPrint.toString().length()));

            this.wiFiPositioning.request(wifiFingerPrint);

            // Get Location and Floor if not null
            if(this.wiFiPositioning.getWifiLocation()!=null){
                Log.e("WiFi-Location", this.wiFiPositioning.getWifiLocation().toString());
                Log.e("WiFi-Floor", String.valueOf(this.wiFiPositioning.getFloor()));
            }

        } catch (JSONException e) {
            Log.e("jsonErrors", "Error creating json object: " + e.toString());
        }
    }

    // Callback Example Function
    /**
     * Function to create a request to obtain a wifi location for the obtained wifi fingerprint
     * using Volley Callback
     */
    private void createWifiPositionRequestCallback() {
        try {
            // 创建用于存储WiFi接入点的JSON对象
            JSONObject wifiAccessPoints = new JSONObject();
            for (Wifi data : this.wifiList) {
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }
            // 创建POST请求所需的JSON对象
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put("wf", wf);

            this.wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng wifiLocation, int floor) {
                    // 成功回调时，重置计时器（更新成功时间，并启动ratioUpdater）
                    lastWifiSuccessTime = System.currentTimeMillis();
                    // 移除之前可能存在的更新任务，确保计时器重置
                    ratioHandler.removeCallbacks(ratioUpdater);
                    ratioHandler.post(ratioUpdater);

                    // 其他成功逻辑处理
                    Log.d("WiFiSuccess", "WiFi定位成功：" + wifiLocation.toString());
                    Log.d("WiFiSuccess", "所在楼层：" + floor);
                }

                @Override
                public void onError(String message) {
                    // 错误回调处理
                    Log.e("WiFiError", message);
                }
            });
        } catch (JSONException e) {
            // 捕获创建JSON对象过程中可能出现的异常，防止崩溃
            Log.e("jsonErrors", "创建json对象错误: " + e.toString());
        }
    }

//    private void createWifiPositionRequestCallback(){
//        try {
//            // Creating a JSON object to store the WiFi access points
//            JSONObject wifiAccessPoints=new JSONObject();
//            for (Wifi data : this.wifiList){
//                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
//            }
//            // Creating POST Request
//            JSONObject wifiFingerPrint = new JSONObject();
//            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
//            this.wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
//                @Override
//                public void onSuccess(LatLng wifiLocation, int floor) {
//                    // Handle the success response
////                    Log.e("Call back WiFi-Location", wifiLocation.toString());
////                    Log.e("Call back WiFi-Floor", String.valueOf(floor));
//                }
//
//                @Override
//                public void onError(String message) {
//                    // Handle the error response
////                    Log.e("Call back WiFi-Error", message);
//                }
//            });
//        } catch (JSONException e) {
//            // Catching error while making JSON object, to prevent crashes
//            // Error log to keep record of errors (for secure programming and maintainability)
//            Log.e("jsonErrors","Error creating json object"+e.toString());
//        }
//
//    }

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
        if (this.accelerometerSensor == null) {
            Log.e("SensorFusion", "accelerometerSensor is null");
        } else if (this.accelerometerSensor.sensorInfo == null) {
            Log.e("SensorFusion", "accelerometerSensor.sensorInfo is null");
        } else {
            sensorInfoList.add(this.accelerometerSensor.sensorInfo);
        }

        if (this.barometerSensor == null) {
            Log.e("SensorFusion", "barometerSensor is null");
        } else if (this.barometerSensor.sensorInfo == null) {
            Log.e("SensorFusion", "barometerSensor.sensorInfo is null");
        } else {
            sensorInfoList.add(this.barometerSensor.sensorInfo);
        }

        if (this.gyroscopeSensor == null) {
            Log.e("SensorFusion", "gyroscopeSensor is null");
        } else if (this.gyroscopeSensor.sensorInfo == null) {
            Log.e("SensorFusion", "gyroscopeSensor.sensorInfo is null");
        } else {
            sensorInfoList.add(this.gyroscopeSensor.sensorInfo);
        }

        if (this.lightSensor == null) {
            Log.e("SensorFusion", "lightSensor is null");
        } else if (this.lightSensor.sensorInfo == null) {
            Log.e("SensorFusion", "lightSensor.sensorInfo is null");
        } else {
            sensorInfoList.add(this.lightSensor.sensorInfo);
        }

        if (this.proximitySensor == null) {
            Log.e("SensorFusion", "proximitySensor is null");
        } else if (this.proximitySensor.sensorInfo == null) {
            Log.e("SensorFusion", "proximitySensor.sensorInfo is null");
        } else {
            sensorInfoList.add(this.proximitySensor.sensorInfo);
        }

        if (this.magnetometerSensor == null) {
            Log.e("SensorFusion", "magnetometerSensor is null");
        } else if (this.magnetometerSensor.sensorInfo == null) {
            Log.e("SensorFusion", "magnetometerSensor.sensorInfo is null");
        } else {
            sensorInfoList.add(this.magnetometerSensor.sensorInfo);
        }
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
        accelerometerSensor.sensorManager.registerListener(this, accelerometerSensor.sensor, 10000);
        accelerometerSensor.sensorManager.registerListener(this, linearAccelerationSensor.sensor, 10000);
        accelerometerSensor.sensorManager.registerListener(this, gravitySensor.sensor, 10000);
        barometerSensor.sensorManager.registerListener(this, barometerSensor.sensor, (int) 1e6);
        gyroscopeSensor.sensorManager.registerListener(this, gyroscopeSensor.sensor, 10000);
        lightSensor.sensorManager.registerListener(this, lightSensor.sensor, (int) 1e6);
        proximitySensor.sensorManager.registerListener(this, proximitySensor.sensor, (int) 1e6);
        magnetometerSensor.sensorManager.registerListener(this, magnetometerSensor.sensor, 10000);
        stepDetectionSensor.sensorManager.registerListener(this, stepDetectionSensor.sensor, SensorManager.SENSOR_DELAY_FASTEST);
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
        // Acquire wakelock so the phone will record with a locked screen. Timeout after 31 minutes.
        this.wakeLock.acquire(31*60*1000L /*31 minutes*/);
        this.saveRecording = true;
        this.stepCounter = 0;
        this.absoluteStartTime = System.currentTimeMillis();
        this.bootTime = android.os.SystemClock.uptimeMillis();
        // Protobuf trajectory class for sending sensor data to restful API
        this.trajectory = Traj.Trajectory.newBuilder()
                .setAndroidVersion(Build.VERSION.RELEASE)
                .setStartTimestamp(absoluteStartTime)
                /*.addApsData(Traj.AP_Data.newBuilder().setMac(example_mac).setSsid(example_ssid)
                        .setFrequency(example_frequency))*/
                .setAccelerometerInfo(createInfoBuilder(accelerometerSensor))
                .setGyroscopeInfo(createInfoBuilder(gyroscopeSensor))
                .setMagnetometerInfo(createInfoBuilder(magnetometerSensor))
                .setBarometerInfo(createInfoBuilder(barometerSensor))
                .setLightSensorInfo(createInfoBuilder(lightSensor))
                .setStartPosition(createLatLongBuilder(sensorFusion.getGNSSLatitude(true)));
        this.storeTrajectoryTimer = new Timer();
        this.storeTrajectoryTimer.scheduleAtFixedRate(new storeDataInTrajectory(), 0, TIME_CONST);
        this.pdrProcessing.resetPDR();
        if(settings.getBoolean("overwrite_constants", false)) {
            this.filter_coefficient = Float.parseFloat(settings.getString("accel_filter", "0.96"));
        }
        else {this.filter_coefficient = FILTER_COEFFICIENT;}
        fusionLocation = new float[] {0,0};
    }

    /**
     * Disables saving sensor values to the trajectory object.
     *
     * Check if a recording is in progress. If it is, it sets save recording to false, and cancels
     * the timer objects.
     *
     * @see Traj object for storing data.
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
     * Creates a {@link Traj.Lat_Long_Position} object from the specified position.
     *
     * @param latlongposition position argument in float[2] containing latitude([0]) and longitude([1])
     * @return Traj.Lat_Long_Position object to be used in building the trajectory
     *
     * @see Traj            Trajectory object used for communication with the server
     */
    private Traj.Lat_Long_Position.Builder createLatLongBuilder(float[] latlongposition) {
        return Traj.Lat_Long_Position.newBuilder()
                .setLat(latlongposition[0])
                .setLong(latlongposition[1]);
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
                            .setRelativeTimestamp(android.os.SystemClock.uptimeMillis()-bootTime)
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
                            .setStepCount(stepCounter) // seems int value 0 won't be explicitly saved
                            .setAzimuth(orientation[0])) // new attribute to store azimuth of user
//                    .setStepCount(stepCounter))

                    .addPositionData(Traj.Position_Sample.newBuilder()
                            .setMagX(magneticField[0])
                            .setMagY(magneticField[1])
                            .setMagZ(magneticField[2])
                            .setRelativeTimestamp(android.os.SystemClock.uptimeMillis()-bootTime));

            // Divide timer with a counter for storing data every 1 second
            if (counter == 99) {
                counter = 0;
                // Store pressure and light data
                if (barometerSensor.sensor != null) {
                    trajectory.addPressureData(Traj.Pressure_Sample.newBuilder()
                                    .setPressure(pressure)
                                    .setEstimatedElevation(elevation)
                                    .setRelativeTimestamp(android.os.SystemClock.uptimeMillis() - bootTime))
                            .addLightData(Traj.Light_Sample.newBuilder()
                                    .setLight(light)
                                    .setRelativeTimestamp(android.os.SystemClock.uptimeMillis() - bootTime)
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

    //region WiFi Scan Only Methods

    /**
     * Starts only the WiFi scanning process.
     *
     * This method initiates WiFi scanning without affecting the other sensors.
     */
    public void startWifiScanOnly() {
        if (this.wifiProcessor != null) {
            this.wifiProcessor.startListening();
            Log.d("SensorFusion", "WiFi scanning started.");
        } else {
            Log.e("SensorFusion", "WiFi processor is not initialized.");
        }
    }

    /**
     * Stops the WiFi scanning process.
     *
     * This method stops the WiFi scanning and logs any potential errors.
     */
    public void stopWifiScanOnly() {
        if (this.wifiProcessor != null) {
            try {
                this.wifiProcessor.stopListening();
                Log.d("SensorFusion", "WiFi scanning stopped.");
            } catch (Exception e) {
                Log.e("SensorFusion", "Error while stopping WiFi scanning: " + e.getMessage());
            }
        } else {
            Log.e("SensorFusion", "WiFi processor is not initialized.");
        }
    }
    //endregion


    public float[] getFusionLocation() {
        return fusionLocation;
    }

    // 成员变量
    private long lastWifiSuccessTime = 0;
    private float ratio = 0f;
    private Handler ratioHandler = new Handler(Looper.getMainLooper());

    // 定时器任务：每隔一小段时间更新ratio值
    private Runnable ratioUpdater = new Runnable() {
        @Override
        public void run() {
            long currentTime = System.currentTimeMillis();
            // 计算上一次wifi成功召回后的已用时间（秒）
            float elapsedSeconds = (currentTime - lastWifiSuccessTime) / 1000f;

            if (elapsedSeconds <= 1.5f) {
                // 1.5秒以内保持为1
                ratio = 1f;
            } else if (elapsedSeconds >= 10f) {
                // 超过10秒后，ratio直接为0
                ratio = 0f;
            } else {
                // 介于1.5秒到10秒之间，采用指数衰减
                // 此处选取的k值使得在10秒时ratio接近0（也可以调整k值实现你想要的衰减曲线）
                float k = (float)(Math.log(1.0/0.01) / (10 - 1.5));
                ratio = (float)Math.exp(-k * (elapsedSeconds - 1.5));
            }

            // 这里可以做一些UI更新或其他操作，例如打印当前ratio值
            Log.d("RatioUpdater", "当前ratio值：" + ratio);

            // 若计时器未超过10秒，继续更新
            if (elapsedSeconds < 10f) {
                ratioHandler.postDelayed(this, 100); // 每100毫秒更新一次
            }
        }
    };

}