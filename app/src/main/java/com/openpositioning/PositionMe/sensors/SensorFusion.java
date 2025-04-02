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
import android.view.WindowManager;
import android.widget.Toast;

import java.util.HashMap;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.Fusion.EKF; // (★EKF集成处) 注意导入
import com.openpositioning.PositionMe.PathView;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.IndoorMapManager;
import com.openpositioning.PositionMe.ServerCommunications;
import com.openpositioning.PositionMe.PdrProcessing;
import com.openpositioning.PositionMe.sensors.WiFiPositioning;
import com.openpositioning.PositionMe.utils.CoordinateTransform;
import com.openpositioning.PositionMe.utils.JsonConverter;
import com.openpositioning.PositionMe.sensors.Observer;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.openpositioning.PositionMe.Fusion.FusionAlgorithm;
import com.openpositioning.PositionMe.Fusion.EKF;
import com.openpositioning.PositionMe.Fusion.ParticleFilter;
import com.openpositioning.PositionMe.Fusion.BatchOptimizer;

/**
 * SensorFusion is a simplified class that integrates sensor data from GNSS, WiFi, and PDR
 * using a chosen fusion algorithm (e.g., EKF, Particle Filter, or Batch Optimizer). It gathers
 * sensor data, fuses the information to estimate the user's position, records the trajectory,
 * and communicates with a remote server to upload sensor and WiFi fingerprint data. The class
 * also notifies registered UI observers about updates (e.g., fused position, WiFi positioning results).
 *
 * Note: This class follows the Singleton pattern.
 */
public class SensorFusion implements SensorEventListener, Observer {

    // 1) Static constants and singleton instance
    private static final SensorFusion sensorFusion = new SensorFusion();
    // Save sensor data every 10ms
    private static final long TIME_CONST = 10;

    private LatLng positionWifi;
    public static final float FILTER_COEFFICIENT = 0.96f;
    private static final float ALPHA = 0.8f;
    private LatLng fusedPosition;
    // Key used for constructing WiFi fingerprint JSON
    private static final String WIFI_FINGERPRINT = "wf";

    // List of observers to notify UI updates
    private List<SensorFusionUpdates> recordingUpdates = new ArrayList<>();

    // 2) Member variables for context, sensors, and settings
    private PowerManager.WakeLock wakeLock;
    private SharedPreferences settings;
    private Context context;

    // Sensor objects
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
    private double[] startRef;
    // Processors for WiFi and GNSS data
    private WifiDataProcessor wifiProcessor;
    private GNSSDataProcessor gnssProcessor;
    private final LocationListener locationListener;

    // Server communication
    private ServerCommunications serverCommunications;
    // Trajectory builder for storing all recorded data
    private Traj.Trajectory.Builder trajectory;

    // Recording control and timing variables
    private boolean saveRecording;
    private float filter_coefficient;
    private long absoluteStartTime;
    private long bootTime;
    // Timer for periodic storage of sensor data
    private Timer storeTrajectoryTimer;
    private int counter;
    private int secondCounter;
    private double[] ecefRefCoords;
    // Raw sensor values
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

    // Derived & computed
    private float elevation;
    private boolean elevator;

    // GNSS values
    private double latitude;
    private double longitude;
    private double altitude; // optional altitude value
    private float[] startLocation;

    // WiFi scan list
    private List<Wifi> wifiList;
    // List to collect acceleration magnitudes for PDR processing
    private List<Double> accelMagnitude;
    // PDR processing object
    private PdrProcessing pdrProcessing;
    // PathView for drawing the trajectory on the map
    private PathView pathView;
    // WiFi positioning processor
    private WiFiPositioning wiFiPositioning;

    // Fusion algorithm instance (EKF, PF, Batch Optimizer, etc.)
    private FusionAlgorithm fusionAlgorithm;

    // 3) Singleton constructor
    private SensorFusion() {
        this.locationListener = new myLocationListener();
        this.storeTrajectoryTimer = new Timer();
        this.counter = 0;
        this.secondCounter = 0;
        this.stepCounter = 0;
        this.elevation = 0;
        this.elevator = false;
        this.startLocation = new float[2];
        // Initialize sensor value arrays
        this.acceleration = new float[3];
        this.filteredAcc = new float[3];
        this.gravity = new float[3];
        this.magneticField = new float[3];
        this.angularVelocity = new float[3];
        this.orientation = new float[3];
        this.rotation = new float[4];
        this.rotation[3] = 1f;
        this.R = new float[9];
        this.accelMagnitude = new ArrayList<>();
    }

    public static SensorFusion getInstance() {
        return sensorFusion;
    }

    // 4) setContext: Initializes sensors, data processors, server communication, and settings.
    public void setContext(Context context) {
        this.context = context;

        // Register sensors
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

        // Initialize WiFi and GNSS processors
        this.wifiProcessor = new WifiDataProcessor(context);
        wifiProcessor.registerObserver(this);
        this.gnssProcessor = new GNSSDataProcessor(context, locationListener);

        // Initialize server communication and register as an observer
        this.serverCommunications = ServerCommunications.getMainInstance();
        this.serverCommunications.registerObserver(this);

        // Initialize time and recording flags
        this.absoluteStartTime = System.currentTimeMillis();
        this.bootTime = SystemClock.uptimeMillis();
        this.saveRecording = false;

        // Initialize PDR processing
        this.pdrProcessing = new PdrProcessing(context);

        // Load settings
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);

        // Initialize UI-related objects
        this.pathView = new PathView(context, null);
        this.wiFiPositioning = new WiFiPositioning(context);

        // Use custom filter coefficient if set in preferences; otherwise, use default
        if (settings.getBoolean("overwrite_constants", false)) {
            this.filter_coefficient = Float.parseFloat(settings.getString("accel_filter", "0.96"));
        } else {
            this.filter_coefficient = FILTER_COEFFICIENT;
        }

        // Acquire a wake lock to keep the device active during recording
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag");
    }
        public float passOrientation(){
        return orientation[0];
    }

    // 5) Sensor event callback: processes data from various sensors and triggers updates.
    @Override
    public void onSensorChanged(SensorEvent event) {
        switch(event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                acceleration[0] = event.values[0];
                acceleration[1] = event.values[1];
                acceleration[2] = event.values[2];
                break;
            case Sensor.TYPE_PRESSURE:
                pressure = (1 - ALPHA)*pressure + ALPHA*event.values[0];
                if (saveRecording) {
                    this.elevation = pdrProcessing.updateElevation(
                            SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure));
                }
                break;
            case Sensor.TYPE_GYROSCOPE:
                angularVelocity[0] = event.values[0];
                angularVelocity[1] = event.values[1];
                angularVelocity[2] = event.values[2];
                break;
            case Sensor.TYPE_LINEAR_ACCELERATION:
                filteredAcc[0] = event.values[0];
                filteredAcc[1] = event.values[1];
                filteredAcc[2] = event.values[2];
                double accMag = Math.sqrt(acceleration[0]*acceleration[0]
                        + acceleration[1]*acceleration[1]
                        + acceleration[2]*acceleration[2]);
                accelMagnitude.add(accMag);
                elevator = pdrProcessing.estimateElevator(gravity, filteredAcc);
                break;
            case Sensor.TYPE_GRAVITY:
                gravity[0] = event.values[0];
                gravity[1] = event.values[1];
                gravity[2] = event.values[2];
                elevator = pdrProcessing.estimateElevator(gravity, filteredAcc);
                break;
            case Sensor.TYPE_LIGHT:
                light = event.values[0];
                break;
            case Sensor.TYPE_PROXIMITY:
                proximity = event.values[0];
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                magneticField[0] = event.values[0];
                magneticField[1] = event.values[1];
                magneticField[2] = event.values[2];
                break;
            case Sensor.TYPE_ROTATION_VECTOR:
                this.rotation = event.values.clone();
                float[] rotationDCM = new float[9];
                SensorManager.getRotationMatrixFromVector(rotationDCM, rotation);
                SensorManager.getOrientation(rotationDCM, orientation);
                break;
            case Sensor.TYPE_STEP_DETECTOR:
                long stepTime = SystemClock.uptimeMillis() - bootTime;
                float[] pdrCords = pdrProcessing.updatePdr(stepTime, accelMagnitude, orientation[0]);
                accelMagnitude.clear();

                if (saveRecording) {
                    stepCounter++;
                    trajectory.addPdrData(Traj.Pdr_Sample.newBuilder()
                            .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                            .setX(pdrCords[0])
                            .setY(pdrCords[1]));

                    this.pathView.drawTrajectory(pdrCords);


                    updateFusionPDR();
                }
                break;
        }
    }

    /**
     * GNSS location listener that receives updates and triggers fusion updates.
     */
    class myLocationListener implements LocationListener {
        @Override
        public void onLocationChanged(Location location) {
            if (location != null) {
                if (startRef == null) {
                    setStartRefFromGNSS(
                            location.getLatitude(),
                            location.getLongitude(),
                            location.getAltitude()
                    );}

                latitude = location.getLatitude();
                longitude = location.getLongitude();
                altitude = location.getAltitude();
                float accuracy = location.getAccuracy();
                if (saveRecording) {
                    trajectory.addGnssData(Traj.GNSS_Sample.newBuilder()
                            .setAccuracy(accuracy)
                            .setAltitude((float) altitude)
                            .setLatitude((float) latitude)
                            .setLongitude((float) longitude)
                            .setRelativeTimestamp(System.currentTimeMillis() - absoluteStartTime)
                    );
                    updateFusionGNSS(latitude, longitude, altitude);
                }
            }
        }

    }

    /**
     * Helper method to debug local WiFi scan results by printing each AP's BSSID and RSSI.
     */
    private void debugLocalWifiScan(List<Wifi> wifiList) {
        if (wifiList == null || wifiList.isEmpty()) {
            Log.d("SensorFusion", "WiFi Debug: 未获取到任何 WiFi 扫描结果，wifiList 为空。");
            return;
        }

        for (Wifi wifi : wifiList) {
            Log.d("SensorFusion",
                    "WiFi Debug: DETECTED    AP -> BSSID: " + wifi.getBssid()
                            + ", RSSI: " + wifi.getLevel());
        }
    }
    public double[] getEcefRefCoords(){
        return ecefRefCoords;
    }
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /**
     * Sets the starting reference coordinates based on GNSS data and computes the ECEF reference.
     */
    public void setStartRefFromGNSS(double lat, double lon, double alt) {
        this.startRef = new double[]{lat, lon, alt};
        this.ecefRefCoords = CoordinateTransform.geodeticToEcef(lat, lon, alt);
    }

    /**
     * Returns an array containing the current or starting GNSS coordinates (lat, lon, alt).
     * If 'start' is true, returns the initial reference coordinates.
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

    // 6) WiFi and Server Observables

    @Override
    public void update(Object[] responseList) {
        // WifiDataProcessor那边给的回调
        if (!saveRecording) return;
        if (responseList == null || responseList.length == 0 || responseList[0] == null) {
            updateFusionWifi(null);
            return;
        }
        Object first = responseList[0];
        if (first instanceof JSONObject) {
            updateFusionWifi((JSONObject) first);
        } else if (first instanceof Wifi) {
            Log.e("SensorFusion", "Received single Wifi object, route to updateWifi()");
            updateWifi(new Object[]{ first });
        }
    }

    @Override
    public void updateWifi(Object[] wifiArr) {
        this.wifiList = Stream.of(wifiArr).map(o -> (Wifi)o).collect(Collectors.toList());
        debugLocalWifiScan(this.wifiList);
        if (saveRecording) {
            Traj.WiFi_Sample.Builder wifiData = Traj.WiFi_Sample.newBuilder()
                    .setRelativeTimestamp(SystemClock.uptimeMillis()-bootTime);
            for (Wifi w : this.wifiList) {
                Log.d("SensorFusion", "BSSID: " + w.getBssid() + ", RSSI: " + w.getLevel());
                wifiData.addMacScans(Traj.Mac_Scan.newBuilder()
                        .setRelativeTimestamp(SystemClock.uptimeMillis()-bootTime)
                        .setMac(w.getBssid())
                        .setRssi(w.getLevel())
                );
            }
            trajectory.addWifiData(wifiData);
            try {
                JSONObject wifiJSON = JsonConverter.toJson(this.wifiList);
                Log.d("SensorFusion", "Sending WiFi JSON: " + wifiJSON.toString());
                sendWifiJsonToCloud(wifiJSON);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }
    public float[] getGNSSLatitude(boolean start) {
        float [] latLong = new float[2];
        if(!start) {
            latLong[0] = (float) latitude;
            latLong[1] = (float) longitude;
        }
        else{
            latLong = startLocation;
        }
        return latLong;
    }

    // 7) Fusion Integration (e.g., EKF)
    /**
     * Initializes the fusion algorithm (EKF, PF, or Batch) based on user settings.
     */
    public void initialiseFusionAlgorithm() {
        String fusionMethod = settings.getString("fusion_method", "EKF");
        switch (fusionMethod) {
            case "EKF":
                fusionAlgorithm = new EKF();
                break;
            case "PF":
                fusionAlgorithm = new ParticleFilter();
                break;
            case "Batch":
                fusionAlgorithm = new BatchOptimizer();
                break;
            default:
                fusionAlgorithm = new EKF(); // fallback
        }
    }

    public void redrawPath(float scalingRatio){
        pathView.redraw(scalingRatio);
    }

    /**
     * Called when a WiFi positioning result is received from the server.
     */
    @Override
    public void updateServer(Object[] responseList) {
        //update fusion processing with new wifi fingerprint
        if (saveRecording) {
            if (responseList == null || responseList[0] == null){
                updateFusionWifi(null);
            }
            JSONObject wifiResponse = (JSONObject) responseList[0];
            updateFusionWifi(wifiResponse);
        }
    }
    public void notifyWifiUpdate(LatLng wifiPosition) {
        this.positionWifi = wifiPosition;
        for (SensorFusionUpdates observer : recordingUpdates) {
            observer.onWifiUpdate(wifiPosition);
        }
    }
    /**
     * Debugs and processes the WiFi JSON received from the server.
     */
    private void debugWifiResponse(JSONObject wifiResponse) {
        if (wifiResponse == null) {

            return;
        }

        if (!wifiResponse.has("lat") || !wifiResponse.has("lon")) {
            Log.d("SensorFusion",
                    "WiFi Debug: wifiResponse lacks lat or lon ，" +
                            "完整数据: " + wifiResponse.toString());
        } else {
            try {
                double lat = wifiResponse.getDouble("lat");
                double lon = wifiResponse.getDouble("lon");
                Log.d("SensorFusion",
                        "WiFi Debug: server returned WiFi coodinates -> lat: " + lat + ", lon: " + lon);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }
    public void updateFusionWifi(JSONObject wifiResponse) {

        debugWifiResponse(wifiResponse);

        if (wifiResponse == null) {
            notifyWifiUpdate(null);
            return;
        }

        if (!wifiResponse.has("lat") || !wifiResponse.has("lon")) {
            Log.e("SensorFusion", "Invalid WiFi response (missing lat/lon), skipping fusion update.");
            notifyWifiUpdate(null);
            return;
        }

        try {
            double lat = wifiResponse.getDouble("lat");
            double lon = wifiResponse.getDouble("lon");
            double floor = wifiResponse.getDouble("floor");

            LatLng wifiLatLng = new LatLng(lat, lon);

            notifyWifiUpdate(wifiLatLng);


            if (fusionAlgorithm != null) {
                // 做一些座标转换...
                double[] enu = CoordinateTransform.geodeticToEnu(
                        lat, lon, 0,
                        startLocation[0],
                        startLocation[1],
                        0
                );
                fusionAlgorithm.onOpportunisticUpdate(enu[0], enu[1], false, SystemClock.uptimeMillis());
            }
        } catch (JSONException e) {
            e.printStackTrace();
            notifyWifiUpdate(null);
        }

        double[] fusedState = fusionAlgorithm.getState();
        LatLng fusedCoordinate = CoordinateTransform.enuToGeodetic(
                fusedState[1], fusedState[2], 0,
                startRef[0], startRef[1], startRef[2]
        );
        notifyFusedUpdate(fusedCoordinate);
    }

    /**
     * Called when GNSS data is received to update the fusion process.
     */
    public void updateFusionGNSS(double lat, double lon, double alt) {
        if (fusionAlgorithm != null) {
            double[] enu = CoordinateTransform.geodeticToEnu(
                    lat, lon, alt,
                    startLocation[0],
                    startLocation[1],
                    0
            );
            fusionAlgorithm.onOpportunisticUpdate(enu[0], enu[1], true, SystemClock.uptimeMillis());
            double[] fusedState = fusionAlgorithm.getState(); // e.g. [bearing, x, y]

            if (startRef == null || startRef.length < 3) {
                Log.e("SensorFusion", "updateFusionGNSS: startRef is null or incomplete. Skipping GNSS fusion update.");
                return;
            }
            LatLng fusedCoordinate = CoordinateTransform.enuToGeodetic(
                    fusedState[1], fusedState[2], 0,
                    startRef[0], startRef[1], startRef[2]
            );

            notifyFusedUpdate(fusedCoordinate);
        }
    }
    public void notifyFusedUpdate(LatLng fused_pos){
        fusedPosition = fused_pos;
        for (SensorFusionUpdates observer : recordingUpdates) {
            observer.onFusedUpdate(fused_pos);
        }
    }
    public float getElevation() {
        return this.elevation;
    }
    public double[] getCurrentPDRCalc(){
        return pdrProcessing.getAccPDRMovement();
    }

    /**
     * Processes a new PDR measurement to update the EKF fusion.
     */
    public void updateFusionPDR(){

        double[] pdrValues = getCurrentPDRCalc();
        float elevationVal = getElevation();

        // local PDR LatLn point
        if (startRef == null || ecefRefCoords == null) {
            Log.e("SensorFusion", "startRef or ecefRefCoords is null — skipping fusion update.");
            return;
        }

        LatLng positionPDR = CoordinateTransform.enuToGeodetic(pdrValues[0], pdrValues[1], elevationVal, startRef[0], startRef[1], ecefRefCoords);
        double latitude = positionPDR.latitude;
        double longitude = positionPDR.longitude;

        // call fusion algorithm EKF
        this.fusionAlgorithm.onStepDetected(pdrValues[0], pdrValues[1], elevationVal, (android.os.SystemClock.uptimeMillis()));

        double[] fusedState = fusionAlgorithm.getState();
        LatLng fusedCoordinate = CoordinateTransform.enuToGeodetic(
                fusedState[1], fusedState[2], 0,
                startRef[0], startRef[1], startRef[2]
        );
        notifyFusedUpdate(fusedCoordinate);
    }

    // 8) start/stop recording

    public void startRecording() {
        // 保持屏幕
        this.wakeLock.acquire(31 * 60 * 1000L);
        this.saveRecording = true;
        this.stepCounter = 0;
        this.absoluteStartTime = System.currentTimeMillis();
        this.bootTime = SystemClock.uptimeMillis();

        this.trajectory = Traj.Trajectory.newBuilder()
                .setAndroidVersion(Build.VERSION.RELEASE)
                .setStartTimestamp(absoluteStartTime);

        initialiseFusionAlgorithm();

        this.storeTrajectoryTimer = new Timer();
        this.storeTrajectoryTimer.scheduleAtFixedRate(new storeDataInTrajectory(), 0, TIME_CONST);
        this.pdrProcessing.resetPDR();
    }

    public void stopRecording() {
        if (this.saveRecording) {
            this.saveRecording = false;
            storeTrajectoryTimer.cancel();
            if (fusionAlgorithm != null) {
                fusionAlgorithm.stopFusion();
            }
        }
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
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

    // 9) TimerTask for periodically saving sensor data into the trajectory object.
    private class storeDataInTrajectory extends TimerTask {
        @Override
        public void run() {
            // IMU
            trajectory.addImuData(Traj.Motion_Sample.newBuilder()
                    .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
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
                    .setStepCount(stepCounter)
            );

            if (counter == 99) {
                counter = 0;
                trajectory.addPressureData(Traj.Pressure_Sample.newBuilder()
                                .setPressure(pressure)
                                .setRelativeTimestamp(SystemClock.uptimeMillis()-bootTime))
                        .addLightData(Traj.Light_Sample.newBuilder()
                                .setLight(light)
                                .setRelativeTimestamp(SystemClock.uptimeMillis()-bootTime)
                                .build());
                if (secondCounter == 4) {
                    secondCounter = 0;
                    Wifi currentWifi = wifiProcessor.getCurrentWifiData();
                    if (currentWifi != null) {
                        trajectory.addApsData(Traj.AP_Data.newBuilder()
                                .setMac(currentWifi.getBssid())
                                .setSsid(currentWifi.getSsid())
                                .setFrequency(currentWifi.getFrequency()));
                    }
                } else {
                    secondCounter++;
                }
            } else {
                counter++;
            }
        }
    }
        public List<Wifi> getWifiList() {
        return this.wifiList;
    }
    public void addTagFusionTrajectory(LatLng fusion_position){
        if(saveRecording) {
            trajectory.addGnssData(Traj.GNSS_Sample.newBuilder()
                    .setAltitude(getElevation())
                    .setLatitude((float) fusion_position.latitude)
                    .setLongitude((float) fusion_position.longitude)
                    .setProvider("fusion")
                    .setRelativeTimestamp(System.currentTimeMillis()-absoluteStartTime));

            Toast.makeText(context, "Tag Successfully added.", Toast.LENGTH_SHORT).show();
        }
    }
    public void setStartGNSSLatitude(float[] startPosition){
        this.startLocation = startPosition;
    }
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
    public void registerForServerUpdate(Observer observer) {
        serverCommunications.registerObserver(observer);
    }

    // 10) Sending data to the server
    public void sendTrajectoryToCloud() {
        Traj.Trajectory buildTraj = trajectory.build();
        serverCommunications.sendTrajectory(buildTraj);
    }

    public void sendWifiJsonToCloud(JSONObject fingerprint) {
        serverCommunications.sendWifi(fingerprint);
    }
    public float passAverageStepLength(){
        return pdrProcessing.getAverageStepLength();
    }
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

    public void registerForSensorUpdates(SensorFusionUpdates observer) {
        if (!recordingUpdates.contains(observer)) {
            recordingUpdates.add(observer);
        }
    }

    public void removeSensorUpdate(SensorFusionUpdates observer) {
        recordingUpdates.remove(observer);
    }

    /**
     * SensorFusionUpdates is an interface for notifying UI components about sensor fusion updates.
     */
    public interface SensorFusionUpdates {
        enum update_type {
            PDR_UPDATE,
            ORIENTATION_UPDATE,
            GNSS_UPDATE,
            FUSED_UPDATE,
            WIFI_UPDATE
        }
        default void onPDRUpdate() {}
        default void onOrientationUpdate() {}
        default void onGNSSUpdate() {}
        default void onFusedUpdate(LatLng fusedPosition) {}
        default void onWifiUpdate(LatLng wifiPosition) {}
    }

}

