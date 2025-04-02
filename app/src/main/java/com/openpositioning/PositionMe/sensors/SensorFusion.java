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
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.BuildingPolygon;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.MainActivity;
import com.openpositioning.PositionMe.PathView;
import com.openpositioning.PositionMe.PdrProcessing;
import com.openpositioning.PositionMe.ServerCommunications;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.utils.LocationLogger;

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

    //region Static variables
    // Singleton Class
    ////////////////////////////////////////////////////////////////////////////////////////////////////////
    private List<float[]> trajectoryPoints = new ArrayList<>();

    // 开始记录时清空轨迹数据

    // 实时添加轨迹点
    public void addTrajectoryPoint(float latitude, float longitude) {
        trajectoryPoints.add(new float[]{latitude, longitude});
    }

    // 获取记录的轨迹点
    public List<float[]> getTrajectoryPoints() {
        return trajectoryPoints;
    }



    ////////////////////////////////////////////////////////////////////////////////////////////////////////
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
    private WiFiPositioning wiFiPositioning;

    private LocationLogger locationLogger;
    private Context context;

    // 修改常量定义
    private static float STANDARD_PRESSURE = 1015.2f;
    private static final float DEFAULT_FLOOR_HEIGHT = 2.5f; // 默认楼层高度
    private static final float ALTITUDE_OFFSET = 0.0f; // 基准高度偏移量
    private float currentFloorHeight = DEFAULT_FLOOR_HEIGHT; // 当前使用的楼层高度
    private int currentFloor = 0;
    private boolean isInSpecialBuilding = false; // 是否在特殊建筑物内

    // 在 SensorFusion 类中添加观察者列表
    private List<Observer> floorObservers = new ArrayList<>();

    // 重写EKF相关变量
    private Timer ekfTimer;
    private static final int EKF_UPDATE_INTERVAL = 200; // 更快的更新频率
    private LatLng wifiLocation = null;
    private long lastWifiUpdateTime = 0;
    private static final long WIFI_DATA_EXPIRY = 10000; // WiFi数据10秒内有效
    
    // EKF状态权重
    private static final float GNSS_WEIGHT = 0.30f;
    private static final float PDR_WEIGHT = 0.40f;
    private static final float WIFI_WEIGHT = 0.30f;

    // 步伐检测相关变量
    private long lastStepTime = 0;
    private static final long MIN_STEP_INTERVAL = 500; // 最小步伐间隔(毫秒)
    private static final float STEP_THRESHOLD = 0.35f; // 步伐峰值阈值
    private boolean isAscending = false;
    private double lastPeakValue = 0;
    private final double[] recentPeaks = new double[3];
    private int peakIndex = 0;

    // 保存最后的PDR位置用于融合
    private float lastPdrLatitude = 0;
    private float lastPdrLongitude = 0;

    // 添加保存融合位置的成员变量
    private LatLng currentEkfPosition = null;

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
        
        // 初始化位置变量为0
        this.latitude = 0.0f;
        this.longitude = 0.0f;
        
        // 初始化加速度大小列表
        this.accelMagnitude = new ArrayList<>();

        // 初始化气压值为设定的基准气压
        this.pressure = STANDARD_PRESSURE;
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
        this.context = context;
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

        // 初始化位置记录器
        locationLogger = new LocationLogger(context);
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
                // 保存原始加速度数据
                acceleration[0] = sensorEvent.values[0];
                acceleration[1] = sensorEvent.values[1];
                acceleration[2] = sensorEvent.values[2];
                
                // 使用低通滤波器获取重力分量
                gravity[0] = filter_coefficient * gravity[0] + (1 - filter_coefficient) * acceleration[0];
                gravity[1] = filter_coefficient * gravity[1] + (1 - filter_coefficient) * acceleration[1];
                gravity[2] = filter_coefficient * gravity[2] + (1 - filter_coefficient) * acceleration[2];
                
                // 通过减去重力获得线性加速度
                filteredAcc[0] = acceleration[0] - gravity[0];
                filteredAcc[1] = acceleration[1] - gravity[1];
                filteredAcc[2] = acceleration[2] - gravity[2];
                
                // 计算合加速度大小
                double accMagnitude = Math.sqrt(Math.pow(filteredAcc[0], 2) + 
                                             Math.pow(filteredAcc[1], 2) + 
                                             Math.pow(filteredAcc[2], 2));
                
                // 保存加速度大小到列表中，用于步长估计
                this.accelMagnitude.add(accMagnitude);
                
                // 手动步伐检测逻辑 - 基于加速度峰值
                manualStepDetection(accMagnitude);
                
                break;

            case Sensor.TYPE_PRESSURE:
                // 使用移动平均值平滑气压数据
                float smoothedPressure = getSmoothedPressure(sensorEvent.values[0]);
                pressure = (1- ALPHA) * pressure + ALPHA * smoothedPressure;
                
                // 计算海拔高度
                float altitude = SensorManager.getAltitude(STANDARD_PRESSURE, pressure) - ALTITUDE_OFFSET;
                
                // 添加更详细的调试日志
                Log.d("PRESSURE_DEBUG", String.format(
                    "原始气压: %.2f hPa, 过滤后气压: %.2f hPa, 计算海拔: %.2f m, 位置: (%.6f, %.6f)", 
                    sensorEvent.values[0],
                    pressure,
                    altitude,
                    latitude,
                    longitude
                ));
                
                // 更新海拔高度
                this.elevation = pdrProcessing.updateElevation(altitude);
                
                // 使用新的楼层计算方法
                int newFloor = calculateFloor(altitude);
                
                // 如果楼层发生变化，通知观察者
                if (newFloor != currentFloor) {
                    currentFloor = newFloor;
                    Log.d("FLOOR_CHANGE", String.format(
                        "楼层变化 - 海拔: %.2f m, 新楼层: %d, 楼层高度: %.1f m, 特殊建筑: %b", 
                        altitude,
                        currentFloor,
                        currentFloorHeight,
                        isInSpecialBuilding
                    ));
                    notifyFloorObservers(currentFloor);
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
                SensorManager.getRotationMatrixFromVector(rotationVectorDCM,this.rotation);
                SensorManager.getOrientation(rotationVectorDCM, this.orientation);
                break;

            case Sensor.TYPE_STEP_DETECTOR:
                // 当前时间
                long currentTime = System.currentTimeMillis();
                long stepTime = android.os.SystemClock.uptimeMillis() - bootTime;
                
                // 检查距离上一次步伐检测的时间间隔，过滤掉过快的步伐
                if (currentTime - lastStepTime < MIN_STEP_INTERVAL) {
                    Log.d("SensorFusion", "忽略过快的步伐检测，间隔" + (currentTime - lastStepTime) + "ms < " + MIN_STEP_INTERVAL + "ms");
                    break; // 忽略此次步伐检测
                }
                
                // 检查加速度幅值是否足够，过滤小幅度振动
                double maxAccel = 0;
                for (double acc : accelMagnitude) {
                    maxAccel = Math.max(maxAccel, acc);
                }
                
                if (maxAccel < STEP_THRESHOLD * 0.7) {
                    Log.d("SensorFusion", "忽略微小振动引起的步伐检测，最大加速度" + maxAccel + " < " + (STEP_THRESHOLD * 0.7));
                    break; // 忽略此次步伐检测
                }
                
                // 更新PDR位置
                float[] newCords = this.pdrProcessing.updatePdr(stepTime, this.accelMagnitude, this.orientation[0]);
                
                // 添加详细日志，帮助调试步数检测
                Log.d("SensorFusion", "步伐检测触发 - 时间: " + stepTime + 
                        "ms, 位置变化: [" + newCords[0] + ", " + newCords[1] + "], 间隔: " + (currentTime - lastStepTime) + "ms");
                
                if (saveRecording) {
                    // Store the PDR coordinates for plotting the trajectory
                    this.pathView.drawTrajectory(newCords);
                }
                this.accelMagnitude.clear();
                if (saveRecording) {
                    stepCounter++;
                    trajectory.addPdrData(Traj.Pdr_Sample.newBuilder()
                            .setRelativeTimestamp(android.os.SystemClock.uptimeMillis() - bootTime)
                            .setX(newCords[0]).setY(newCords[1]));
                }
                
                // 检测到步伐后马上记录到位置日志器
                if (saveRecording && locationLogger != null) {
                    // 使用PDR位置更新LocationLogger
                    float[] pdrLongLat = getPdrLongLat(newCords[0], newCords[1]);
                    
                    // 保存最后的PDR位置用于EKF融合
                    lastPdrLatitude = pdrLongLat[0];
                    lastPdrLongitude = pdrLongLat[1];
                    
                    locationLogger.logLocation(
                        currentTime,
                        pdrLongLat[0],
                        pdrLongLat[1]
                    );
                    
                    // 添加PDR轨迹点
                    addTrajectoryPoint(pdrLongLat[0], pdrLongLat[1]);
                    
                    // 不需要在这里直接生成EKF位置 - 现在由定时器统一处理
                    
                    Log.d("SensorFusion", "步伐检测 - 已记录PDR位置: lat=" + 
                           pdrLongLat[0] + ", lng=" + pdrLongLat[1]);
                }
                
                // 更新最后一次步伐时间
                lastStepTime = currentTime;
                break;
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
                latitude = (float) location.getLatitude();
                longitude = (float) location.getLongitude();
                
                // 保存最后GNSS更新时间
                lastGnssUpdateTime = System.currentTimeMillis();
                
                // 记录位置到日志
                if(saveRecording && locationLogger != null) {
                    locationLogger.logLocation(
                        lastGnssUpdateTime,
                        latitude,
                        longitude
                    );
                    
                    // 记录GNSS位置
                    locationLogger.logGnssLocation(
                        lastGnssUpdateTime,
                        latitude,
                        longitude
                    );
                    
                    // 添加轨迹点
                    addTrajectoryPoint(latitude, longitude);
                    
                    // 不需要在这里直接生成EKF位置 - 现在由定时器统一处理
                }
                
                // 添加详细的日志
                Log.d("LOCATION_UPDATE", String.format(
                    "位置更新 - 提供者: %s, 纬度: %.6f, 经度: %.6f, 精度: %.1f米",
                    location.getProvider(),
                    latitude,
                    longitude,
                    location.getAccuracy()
                ));
                
                if(saveRecording) {
                    float altitude = (float) location.getAltitude();
                    float accuracy = (float) location.getAccuracy();
                    float speed = (float) location.getSpeed();
                    String provider = location.getProvider();
                    
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

        if(this.saveRecording) {
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
        // 使用带回调的WiFi请求方法，而不是原来的
        createWifiPositionRequestCallback();
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
                    // 更新WiFi位置和最后更新时间
                    SensorFusion.this.wifiLocation = wifiLocation;
                    lastWifiUpdateTime = System.currentTimeMillis();
                    
                    Log.d("WIFI_LOCATION", String.format(
                        "接收WiFi位置更新: [%.6f, %.6f], 楼层: %d", 
                        wifiLocation.latitude, wifiLocation.longitude, floor));
                }

                @Override
                public void onError(String message) {
                    // 记录错误
                    Log.e("WIFI_LOCATION", "WiFi定位错误: " + message);
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
        float[] latLong = new float[2];
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

    /**
     * 获取当前估计的楼层
     * @return 当前楼层数(0表示地面层)
     */
    public int getCurrentFloor() {
        return currentFloor;
    }

    /**
     * 校准当前位置的基准气压值
     * @param newPressure 新的基准气压值 (hPa)
     */
    public void calibrateBasePressure(float newPressure) {
        STANDARD_PRESSURE = newPressure;
        Log.d("PRESSURE_CALIBRATE", String.format(
            "基准气压已更新为: %.2f hPa", 
            STANDARD_PRESSURE
        ));
    }

    /**
     * 获取当前基准气压值
     * @return 当前基准气压值 (hPa)
     */
    public float getBasePressure() {
        return STANDARD_PRESSURE;
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
        
        // 降低步伐检测器的采样率，从SENSOR_DELAY_FASTEST改为SensorManager.SENSOR_DELAY_GAME
        // 这样可以减少过度灵敏的问题
        stepDetectionSensor.sensorManager.registerListener(this, stepDetectionSensor.sensor, SensorManager.SENSOR_DELAY_GAME);
        
        rotationSensor.sensorManager.registerListener(this, rotationSensor.sensor, (int) 1e6);
        wifiProcessor.startListening();
        
        // 确保GNSS处理器启动
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
                this.wifiProcessor.stopListening();
            } catch (Exception e) {
                System.err.println("Wifi resumed before existing");
            }
            // Stop receiving location updates
            if (gnssProcessor != null) {
                gnssProcessor.stopUpdating();
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
        // Acquire wakelock so the phone will record with a locked screen. Timeout after 31 minutes.
        this.wakeLock.acquire(31*60*1000L /*31 minutes*/);
        this.saveRecording = true;
        this.stepCounter = 0;
        this.absoluteStartTime = System.currentTimeMillis();
        this.bootTime = android.os.SystemClock.uptimeMillis();

        // 清空轨迹点列表
        trajectoryPoints.clear();
        
        // 启动定时EKF融合更新
        startEkfTimer();

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
                .setLightSensorInfo(createInfoBuilder(lightSensor));
        this.storeTrajectoryTimer = new Timer();
        this.storeTrajectoryTimer.scheduleAtFixedRate(new storeDataInTrajectory(), 0, TIME_CONST);
        this.pdrProcessing.resetPDR();
        if(settings.getBoolean("overwrite_constants", false)) {
            this.filter_coefficient = Float.parseFloat(settings.getString("accel_filter", "0.96"));
        }
        else {this.filter_coefficient = FILTER_COEFFICIENT;}

        // 初始化位置记录器
        locationLogger = new LocationLogger(context);
    }

    /**
     * Disables saving sensor values to the trajectory object.
     *
     * Check if a recording is in progress. If it is, it sets save recording to false, and cancels
     * the timer objects.
     *
     * @see Traj object for storing data.
     * @see com.openpositioning.PositionMe.fragments.SettingsFragment navigation that might cancel recording.
     */
    public void stopRecording() {
        // Only cancel if we are running
        if(this.saveRecording) {
            this.saveRecording = false;
            storeTrajectoryTimer.cancel();
            
            // 停止EKF融合定时器
            stopEkfTimer();

            // 保存位置日志
            if (locationLogger != null) {
                locationLogger.saveToFile();
            }
        }
        if(wakeLock.isHeld()) {
            this.wakeLock.release();
        }
    }

    //endregion

    // 定时执行EKF融合的定时器
    // private Timer ekfTimer;
    // private static final int EKF_UPDATE_INTERVAL = 200; // 更快的更新频率
    
    /**
     * 重写的EKF融合算法 - 每次更新时融合所有可用数据源
     */
    private class updateEkfLocation extends TimerTask {
        @Override
        public void run() {
            try {
                if (!saveRecording || locationLogger == null) return;
                
                long currentTime = System.currentTimeMillis();
                
                // 检查是否有足够的数据源
                boolean hasPdr = lastPdrLatitude != 0 && lastPdrLongitude != 0;
                boolean hasGnss = latitude != 0 && longitude != 0;
                boolean hasWifi = wifiLocation != null && 
                                  (currentTime - lastWifiUpdateTime < WIFI_DATA_EXPIRY);
                
                // 至少需要一个数据源
                if (!hasPdr && !hasGnss && !hasWifi) {
                    Log.d("EKF_FUSION", "没有可用的数据源进行融合");
                    return;
                }
                
                // 初始化融合位置
                float fusedLat = 0;
                float fusedLon = 0;
                float totalWeight = 0;
                
                // 融合PDR数据
                if (hasPdr) {
                    float pdrWeight = PDR_WEIGHT;
                    // 计算PDR数据的时间衰减
                    long pdrTimeDiff = currentTime - lastStepTime;
                    if (pdrTimeDiff > 2000) {
                        // 步数数据超过2秒，权重线性衰减
                        pdrWeight *= Math.max(0.3f, 1.0f - (pdrTimeDiff - 2000) / 8000.0f);
                    }
                    
                    fusedLat += pdrWeight * lastPdrLatitude;
                    fusedLon += pdrWeight * lastPdrLongitude;
                    totalWeight += pdrWeight;
                    
                    Log.d("EKF_FUSION", String.format(
                        "PDR数据: [%.6f, %.6f], 权重: %.2f", 
                        lastPdrLatitude, lastPdrLongitude, pdrWeight));
                }
                
                // 融合GNSS数据
                if (hasGnss) {
                    float gnssWeight = GNSS_WEIGHT;
                    // 计算GNSS数据的时间衰减
                    long gnssTimeDiff = currentTime - lastGnssUpdateTime;
                    if (gnssTimeDiff > 2000) {
                        // GNSS数据超过2秒，权重线性衰减
                        gnssWeight *= Math.max(0.3f, 1.0f - (gnssTimeDiff - 2000) / 8000.0f);
                    }
                    
                    fusedLat += gnssWeight * latitude;
                    fusedLon += gnssWeight * longitude;
                    totalWeight += gnssWeight;
                    
                    Log.d("EKF_FUSION", String.format(
                        "GNSS数据: [%.6f, %.6f], 权重: %.2f", 
                        latitude, longitude, gnssWeight));
                }
                
                // 融合WiFi数据
                if (hasWifi) {
                    float wifiWeight = WIFI_WEIGHT;
                    // 计算WiFi数据的时间衰减
                    long wifiTimeDiff = currentTime - lastWifiUpdateTime;
                    if (wifiTimeDiff > 3000) {
                        // WiFi数据超过3秒，权重线性衰减
                        wifiWeight *= Math.max(0.3f, 1.0f - (wifiTimeDiff - 3000) / 7000.0f);
                    }
                    
                    fusedLat += wifiWeight * (float)wifiLocation.latitude;
                    fusedLon += wifiWeight * (float)wifiLocation.longitude;
                    totalWeight += wifiWeight;
                    
                    Log.d("EKF_FUSION", String.format(
                        "WiFi数据: [%.6f, %.6f], 权重: %.2f", 
                        wifiLocation.latitude, wifiLocation.longitude, wifiWeight));
                }
                
                // 归一化权重
                if (totalWeight > 0) {
                    fusedLat /= totalWeight;
                    fusedLon /= totalWeight;
                } else if (hasPdr) {
                    // 如果权重归一化出问题，但有PDR数据，使用PDR数据
                    fusedLat = lastPdrLatitude;
                    fusedLon = lastPdrLongitude;
                } else if (hasGnss) {
                    // 否则使用GNSS数据
                    fusedLat = latitude;
                    fusedLon = longitude;
                } else if (hasWifi) {
                    // 最后选择WiFi数据
                    fusedLat = (float)wifiLocation.latitude;
                    fusedLon = (float)wifiLocation.longitude;
                }
                
                // 保存当前EKF融合位置到成员变量
                currentEkfPosition = new LatLng(fusedLat, fusedLon);
                
                // 记录融合位置
                locationLogger.logEkfLocation(currentTime, fusedLat, fusedLon);
                
                // 添加到轨迹点列表
                addTrajectoryPoint(fusedLat, fusedLon);
                
                Log.d("EKF_FUSION", String.format(
                    "融合位置: [%.6f, %.6f], 总权重: %.2f",
                    fusedLat, fusedLon, totalWeight));
                
                // 不再需要递归调度，已使用scheduleAtFixedRate
            } catch (Exception e) {
                Log.e("EKF_FUSION", "融合位置更新出错: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 启动EKF融合定时器
     */
    private void startEkfTimer() {
        Log.d("EKF_FUSION", "启动EKF融合定时器");
        
        if (ekfTimer != null) {
            ekfTimer.cancel();
        }
        ekfTimer = new Timer("EKF-Fusion-Timer");
        
        // 使用scheduleAtFixedRate而不是递归调度
        ekfTimer.scheduleAtFixedRate(new updateEkfLocation(), 0, EKF_UPDATE_INTERVAL);
    }
    
    /**
     * 停止EKF融合定时器
     */
    private void stopEkfTimer() {
        if (ekfTimer != null) {
            Log.d("EKF_FUSION", "停止EKF融合定时器");
            ekfTimer.cancel();
            ekfTimer = null;
        }
    }
    
    // 保存GNSS最后更新时间
    private long lastGnssUpdateTime = 0;

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
    private class storeDataInTrajectory extends TimerTask {
        public void run() {
            // Store IMU and magnetometer data in Trajectory class
            try {
                trajectory.addImuData(Traj.Motion_Sample.newBuilder()
                        .setRelativeTimestamp(android.os.SystemClock.uptimeMillis()-bootTime)
                        .setAccX(acceleration[0])
                        .setAccY(acceleration[1])
                        .setAccZ(acceleration[2])
                        .setGyrX(angularVelocity[0])
                        .setGyrY(angularVelocity[1])
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
                                .setRelativeTimestamp(android.os.SystemClock.uptimeMillis()-bootTime));

                // Divide timer with a counter for storing data every 1 second
                if (counter == 99) {
                    counter = 0;
                    // Store pressure and light data
                    if (barometerSensor.sensor != null) {
                        trajectory.addPressureData(Traj.Pressure_Sample.newBuilder()
                                        .setPressure(pressure)
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
                                .setFrequency((int)currentWifi.getFrequency()));
                    }
                    else {
                        secondCounter++;
                    }
                }
                else {
                    counter++;
                }
            } catch (Exception e) {
                Log.e("SensorFusion", "轨迹数据添加错误: " + e.getMessage());
            }
        }
    }

    //endregion

    /**
     * 注册观察者以接收楼层更新
     */
    public void registerFloorObserver(Observer observer) {
        if (!floorObservers.contains(observer)) {
            floorObservers.add(observer);
        }
    }

    /**
     * 移除楼层更新观察者
     */
    public void removeFloorObserver(Observer observer) {
        floorObservers.remove(observer);
    }

    /**
     * 通知所有观察者楼层变化
     */
    private void notifyFloorObservers(int floor) {
        Log.d("FLOOR_NOTIFY", String.format(
            "正在通知观察者楼层变化，观察者数量: %d, 新楼层: %d",
            floorObservers.size(),
            floor
        ));
        
        for (Observer observer : floorObservers) {
            Log.d("FLOOR_NOTIFY", "通知观察者: " + observer.getClass().getName());
            Object[] updateData = new Object[]{floor};
            observer.update(updateData);
        }
    }

    /**
     * 根据当前位置计算楼层
     * @param altitude 当前海拔高度
     * @return 计算得到的楼层数
     */
    private int calculateFloor(float altitude) {
        LatLng currentPosition = new LatLng(latitude, longitude);
        
        // 检查是否在Nucleus大楼内
        if (BuildingPolygon.inNucleus(currentPosition)) {
            currentFloorHeight = IndoorMapManager.NUCLEUS_FLOOR_HEIGHT;
            isInSpecialBuilding = true;
            
            // 计算楼层（Nucleus的特殊规则）
            int calculatedFloor = (int)Math.floor(altitude / currentFloorHeight);
            
            // Nucleus的楼层对应关系：
            // calculatedFloor: -1  0  1  2  3
            // 实际显示:       LG  G  1  2  3
            // 所以不需要额外调整，只需要限制范围
            
            // 限制楼层范围（-1到3，对应LG到3楼）
            return Math.min(Math.max(calculatedFloor, -1), 3);
        }
        // 检查是否在图书馆内
        else if (BuildingPolygon.inLibrary(currentPosition)) {
            currentFloorHeight = IndoorMapManager.LIBRARY_FLOOR_HEIGHT;
            isInSpecialBuilding = true;
            
            // 计算楼层（图书馆的特殊规则）
            int calculatedFloor = (int)Math.floor(altitude / currentFloorHeight);
            
            // 限制楼层范围（0到3，对应G到3楼）
            return Math.min(Math.max(calculatedFloor, 0), 3);
        }
        else {
            // 不在特殊建筑物内，使用默认规则
            isInSpecialBuilding = false;
            currentFloorHeight = DEFAULT_FLOOR_HEIGHT;
            
            // 计算楼层
            int calculatedFloor = (int)Math.floor(altitude / currentFloorHeight);
            
            // 对于其他位置，确保从0开始（G层）
            return Math.max(calculatedFloor, 0); // 0=G, 1=1, 2=2, ...
        }
    }

    /**
     * 获取当前楼层的显示文本
     * @return 楼层显示文本（如"LG", "G", "1", "2"等）
     */
    public String getFloorDisplay() {
        LatLng currentPosition = new LatLng(latitude, longitude);
        
        if (BuildingPolygon.inNucleus(currentPosition)) {
            // Nucleus的特殊显示规则
            if (currentFloor == -1) {
                return "LG";
            } else if (currentFloor == 0) {
                return "G";
            } else {
                return String.valueOf(currentFloor);
            }
        } else {
            // 其他位置（包括图书馆）的显示规则
            if (currentFloor == 0) {
                return "G";
            } else {
                return String.valueOf(currentFloor);
            }
        }
    }

    /**
     * 在已知楼层位置校准气压计
     * @param knownFloor 当前已知的楼层（0=G, -1=LG, 1=1F, etc.）
     */
    public void calibrateAtKnownFloor(int knownFloor) {
        // 获取当前位置
        LatLng currentPosition = new LatLng(latitude, longitude);
        float floorHeight;
        
        // 根据位置确定楼层高度
        if (BuildingPolygon.inNucleus(currentPosition)) {
            floorHeight = IndoorMapManager.NUCLEUS_FLOOR_HEIGHT;
        } else if (BuildingPolygon.inLibrary(currentPosition)) {
            floorHeight = IndoorMapManager.LIBRARY_FLOOR_HEIGHT;
        } else {
            floorHeight = DEFAULT_FLOOR_HEIGHT;
        }

        // 计算理论高度
        float expectedAltitude = knownFloor * floorHeight;
        
        // 根据当前气压和已知高度，反向计算基准气压
        float newStandardPressure = pressure * (float)Math.exp(expectedAltitude / -7400);
        
        // 更新基准气压
        STANDARD_PRESSURE = newStandardPressure;
        
        Log.d("PRESSURE_CALIBRATE", String.format(
            "在已知楼层%d校准 - 当前气压: %.2f hPa, 理论高度: %.2f m, 新基准气压: %.2f hPa",
            knownFloor,
            pressure,
            expectedAltitude,
            STANDARD_PRESSURE
        ));
    }

    /**
     * 使用移动平均值平滑气压数据
     */
    private static final int PRESSURE_WINDOW_SIZE = 10;
    private final float[] pressureWindow = new float[PRESSURE_WINDOW_SIZE];
    private int pressureWindowIndex = 0;
    private boolean pressureWindowFull = false;

    private float getSmoothedPressure(float rawPressure) {
        // 更新滑动窗口
        pressureWindow[pressureWindowIndex] = rawPressure;
        pressureWindowIndex = (pressureWindowIndex + 1) % PRESSURE_WINDOW_SIZE;
        if (pressureWindowIndex == 0) {
            pressureWindowFull = true;
        }

        // 计算平均值
        float sum = 0;
        int count = pressureWindowFull ? PRESSURE_WINDOW_SIZE : pressureWindowIndex;
        for (int i = 0; i < count; i++) {
            sum += pressureWindow[i];
        }
        return sum / count;
    }

    // 添加获取经纬度的方法
    /**
     * 获取当前纬度
     * @return 当前纬度值
     */
    public float getLatitude() {
        return this.latitude;
    }

    /**
     * 获取当前经度
     * @return 当前经度值
     */
    public float getLongitude() {
        return this.longitude;
    }

    // 更新manualStepDetection方法，移除EKF融合代码，由定时器统一处理
    private void manualStepDetection(double accMagnitude) {
        long currentTime = System.currentTimeMillis();
        
        // 时间间隔检查，防止过于频繁的步伐检测
        if (currentTime - lastStepTime < MIN_STEP_INTERVAL) {
            return;
        }
        
        // 检测峰值变化
        if (!isAscending && accMagnitude > lastPeakValue) {
            isAscending = true;
        } else if (isAscending && accMagnitude < lastPeakValue) {
            // 检测到峰值下降
            
            // 保存峰值用于连续性检查
            recentPeaks[peakIndex] = lastPeakValue;
            peakIndex = (peakIndex + 1) % recentPeaks.length;
            
            // 检查峰值是否符合走路模式 - 连续三个峰值相对稳定且超过阈值
            boolean isValidStep = lastPeakValue > STEP_THRESHOLD;
            
            // 如果有足够的峰值历史，检查连续性
            if (currentTime - lastStepTime > 2000) { // 如果超过2秒没有步伐，重置判断
                isValidStep = isValidStep && lastPeakValue > STEP_THRESHOLD * 1.2; // 第一步要求更明显的峰值
            }
            
            if (isValidStep) {
                // 检测到有效步伐
                long stepTime = android.os.SystemClock.uptimeMillis() - bootTime;
                Log.d("SensorFusion", String.format("手动步伐检测: 峰值=%.2f, 阈值=%.2f, 间隔=%d毫秒", 
                        lastPeakValue, STEP_THRESHOLD, currentTime - lastStepTime));
                
                // 使用与系统步伐检测器相同的更新逻辑
                float[] newCords = this.pdrProcessing.updatePdr(stepTime, this.accelMagnitude, this.orientation[0]);
                
                if (saveRecording) {
                    this.pathView.drawTrajectory(newCords);
                    stepCounter++;
                    trajectory.addPdrData(Traj.Pdr_Sample.newBuilder()
                            .setRelativeTimestamp(stepTime)
                            .setX(newCords[0]).setY(newCords[1]));
                }
                
                // 检测到步伐后记录到位置日志器
                if (saveRecording && locationLogger != null) {
                    float[] pdrLongLat = getPdrLongLat(newCords[0], newCords[1]);
                    
                    // 保存最后的PDR位置用于EKF融合
                    lastPdrLatitude = pdrLongLat[0];
                    lastPdrLongitude = pdrLongLat[1];
                    
                    // 记录PDR位置
                    locationLogger.logLocation(
                        currentTime,
                        pdrLongLat[0],
                        pdrLongLat[1]
                    );
                    
                    // 添加PDR轨迹点
                    addTrajectoryPoint(pdrLongLat[0], pdrLongLat[1]);
                    
                    // 不需要在这里直接生成EKF位置 - 现在由定时器统一处理
                    
                    Log.d("SensorFusion", "手动步伐检测 - 已记录位置: lat=" + 
                           pdrLongLat[0] + ", lng=" + pdrLongLat[1]);
                }
                
                this.accelMagnitude.clear();
                lastStepTime = currentTime;
            }
            isAscending = false;
        }
        
        lastPeakValue = accMagnitude;
    }
    
    /**
     * 将PDR相对坐标转换为地理坐标
     * @param x PDR坐标X分量(米)
     * @param y PDR坐标Y分量(米)
     * @return 经纬度坐标[纬度,经度]
     */
    private float[] getPdrLongLat(float x, float y) {
        if (startLocation[0] == 0 && startLocation[1] == 0) {
            // 如果没有起始位置，使用当前GNSS位置
            return new float[]{latitude, longitude};
        }
        
        // 每度对应的距离(米)
        double metersPerLatDegree = 111320.0; // 1度纬度大约等于111.32公里
        // 经度则随纬度变化
        double metersPerLngDegree = 111320.0 * Math.cos(Math.toRadians(startLocation[0]));
        
        // 将米转换为经纬度增量
        float latOffset = y / (float)metersPerLatDegree;
        float lngOffset = x / (float)metersPerLngDegree;
        
        // 从起始位置增加偏移
        float resultLat = startLocation[0] + latOffset;
        float resultLng = startLocation[1] + lngOffset;
        
        return new float[]{resultLat, resultLng};
    }

    /**
     * 获取当前EKF融合位置
     * @return 当前EKF融合位置，如果未计算则返回null
     */
    public LatLng getEkfPosition() {
        return currentEkfPosition;
    }

}
