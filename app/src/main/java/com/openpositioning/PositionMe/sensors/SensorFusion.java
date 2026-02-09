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
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.PdrProcessing;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Singleton class handling sensor data aggregation, trajectory generation,
 * and server communication.
 * Manages the lifecycle of recording and processes raw sensor inputs.
 */
public class SensorFusion implements SensorEventListener, Observer {

    private HashMap<Integer, Long> lastEventTimestamps = new HashMap<>();
    private HashMap<Integer, Integer> eventCounts = new HashMap<>();
    long maxReportLatencyNs = 0;
    private static final SensorFusion sensorFusion = new SensorFusion();
    private static final long TIME_CONST = 10;
    public static final float FILTER_COEFFICIENT = 0.96f;
    private static final float ALPHA = 0.8f;
    private static final String WIFI_FINGERPRINT= "wf";

    private PowerManager.WakeLock wakeLock;
    private Context appContext;
    private SharedPreferences settings;

    // Sensors
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

    // Processors
    private WifiDataProcessor wifiProcessor;
    private GNSSDataProcessor gnssProcessor;
    private final LocationListener locationListener;
    private ServerCommunications serverCommunications;
    private PdrProcessing pdrProcessing;
    private WiFiPositioning wiFiPositioning;

    // Trajectory State
    private Traj.Trajectory.Builder trajectory;
    private boolean saveRecording;
    private float filter_coefficient;
    private long absoluteStartTime;
    private long bootTime;
    long lastStepTime = 0;
    private Timer storeTrajectoryTimer;
    private int counter;
    private int secondCounter;

    // Data Containers
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
    private int stepCounter;
    private float elevation;
    private boolean elevator;
    private float latitude;
    private float longitude;
    private float[] startLocation;
    private List<Wifi> wifiList;
    private List<Double> accelMagnitude;

    private PathView pathView;
    private String currentVenue = "unknown";

    private SensorFusion() {
        this.locationListener = new myLocationListener();
        this.storeTrajectoryTimer = new Timer();
        this.counter = 0;
        this.secondCounter = 0;
        this.stepCounter = 0;
        this.elevation = 0;
        this.elevator = false;
        this.startLocation = new float[2];
        this.acceleration = new float[3];
        this.filteredAcc = new float[3];
        this.gravity = new float[3];
        this.magneticField = new float[3];
        this.angularVelocity = new float[3];
        this.orientation = new float[3];
        this.rotation = new float[4];
        this.rotation[3] = 1.0f;
        this.startLocation = new float[2];
    }

    public static SensorFusion getInstance() {
        return sensorFusion;
    }

    public void setContext(Context context) {
        this.appContext = context.getApplicationContext();

        // Initialize Sensors
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

        // Initialize Processors
        this.wifiProcessor = new WifiDataProcessor(context);
        wifiProcessor.registerObserver(this);
        this.gnssProcessor = new GNSSDataProcessor(context, locationListener);
        this.serverCommunications = new ServerCommunications(context);
        this.wiFiPositioning = new WiFiPositioning(context);
        this.pdrProcessing = new PdrProcessing(context);
        this.pathView = new PathView(context, null);

        this.absoluteStartTime = System.currentTimeMillis();
        this.bootTime = SystemClock.uptimeMillis();
        this.saveRecording = false;
        this.accelMagnitude = new ArrayList<>();

        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.filter_coefficient = settings.getBoolean("overwrite_constants", false) ?
                Float.parseFloat(settings.getString("accel_filter", "0.96")) : FILTER_COEFFICIENT;

        PowerManager powerManager = (PowerManager) this.appContext.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag");
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        long currentTime = System.currentTimeMillis();
        int sensorType = sensorEvent.sensor.getType();

        // Track frequency
        lastEventTimestamps.put(sensorType, currentTime);
        eventCounts.put(sensorType, eventCounts.getOrDefault(sensorType, 0) + 1);

        switch (sensorType) {
            case Sensor.TYPE_ACCELEROMETER:
                System.arraycopy(sensorEvent.values, 0, acceleration, 0, 3);
                break;
            case Sensor.TYPE_PRESSURE:
                pressure = (1 - ALPHA) * pressure + ALPHA * sensorEvent.values[0];
                if (saveRecording) {
                    this.elevation = pdrProcessing.updateElevation(
                            SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure));
                }
                break;
            case Sensor.TYPE_GYROSCOPE:
                System.arraycopy(sensorEvent.values, 0, angularVelocity, 0, 3);
                break;
            case Sensor.TYPE_LINEAR_ACCELERATION:
                System.arraycopy(sensorEvent.values, 0, filteredAcc, 0, 3);
                double accelMagFiltered = Math.sqrt(Math.pow(filteredAcc[0], 2) + Math.pow(filteredAcc[1], 2) + Math.pow(filteredAcc[2], 2));
                this.accelMagnitude.add(accelMagFiltered);
                elevator = pdrProcessing.estimateElevator(gravity, filteredAcc);
                break;
            case Sensor.TYPE_GRAVITY:
                System.arraycopy(sensorEvent.values, 0, gravity, 0, 3);
                elevator = pdrProcessing.estimateElevator(gravity, filteredAcc);
                break;
            case Sensor.TYPE_LIGHT:
                light = sensorEvent.values[0];
                break;
            case Sensor.TYPE_PROXIMITY:
                proximity = sensorEvent.values[0];
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                System.arraycopy(sensorEvent.values, 0, magneticField, 0, 3);
                break;
            case Sensor.TYPE_ROTATION_VECTOR:
                this.rotation = sensorEvent.values.clone();
                float[] rotationVectorDCM = new float[9];
                SensorManager.getRotationMatrixFromVector(rotationVectorDCM, this.rotation);
                SensorManager.getOrientation(rotationVectorDCM, this.orientation);
                break;
            case Sensor.TYPE_STEP_DETECTOR:
                long stepTime = SystemClock.uptimeMillis() - bootTime;
                if (currentTime - lastStepTime >= 20) {
                    lastStepTime = currentTime;
                    if (!accelMagnitude.isEmpty()) {
                        float[] newCords = this.pdrProcessing.updatePdr(stepTime, this.accelMagnitude, this.orientation[0]);
                        this.accelMagnitude.clear();
                        if (saveRecording) {
                            this.pathView.drawTrajectory(newCords);
                            stepCounter++;
                            // Store PDR relative position
                            trajectory.addPdrData(Traj.RelativePosition.newBuilder()
                                    .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                                    .setX(newCords[0])
                                    .setY(newCords[1]));
                        }
                    }
                }
                break;
        }
    }

    public void logSensorFrequencies() {
        for (int sensorType : eventCounts.keySet()) {
            Log.d("SensorFusion", "Sensor " + sensorType + " | Event Count: " + eventCounts.get(sensorType));
        }
    }

    class myLocationListener implements LocationListener{
        @Override
        public void onLocationChanged(@NonNull Location location) {
            latitude = (float) location.getLatitude();
            longitude = (float) location.getLongitude();
            float altitude = (float) location.getAltitude();
            float accuracy = (float) location.getAccuracy();
            float speed = (float) location.getSpeed();
            String provider = location.getProvider();

            if(saveRecording) {
                Traj.GNSSPosition gnssPosition = Traj.GNSSPosition.newBuilder()
                        .setLatitude(latitude)
                        .setLongitude(longitude)
                        .setAltitude(altitude)
                        .setRelativeTimestamp(System.currentTimeMillis()-absoluteStartTime)
                        .build();

                trajectory.addGnssData(Traj.GNSSReading.newBuilder()
                        .setPosition(gnssPosition)
                        .setAccuracy(accuracy)
                        .setSpeed(speed)
                        .setProvider(provider != null ? provider : "unknown"));
            }
        }
    }

    @Override
    public void update(Object[] wifiList) {
        this.wifiList = Stream.of(wifiList).map(o -> (Wifi) o).collect(Collectors.toList());

        if(this.saveRecording) {
            long relativeTime = SystemClock.uptimeMillis() - bootTime;

            // Map Wifi data to RFScan
            Traj.Fingerprint.Builder fingerprintBuilder = Traj.Fingerprint.newBuilder()
                    .setRelativeTimestamp(relativeTime);

            for (Wifi data : this.wifiList) {
                fingerprintBuilder.addRfScans(Traj.RFScan.newBuilder()
                        .setRelativeTimestamp(relativeTime)
                        .setMac(data.getBssid())
                        .setRssi(data.getLevel()));
            }
            this.trajectory.addWifiFingerprints(fingerprintBuilder);
        }
        createWifiPositioningRequest();
    }

    private void createWifiPositioningRequest(){
        try {
            JSONObject wifiAccessPoints=new JSONObject();
            for (Wifi data : this.wifiList){
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
            this.wiFiPositioning.request(wifiFingerPrint);
        } catch (JSONException e) {
            Log.e("jsonErrors","Error creating json object"+e.toString());
        }
    }

    public LatLng getLatLngWifiPositioning(){return this.wiFiPositioning.getWifiLocation();}
    public int getWifiFloor(){return this.wiFiPositioning.getFloor();}
    public float passOrientation(){return orientation[0];}

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

    // =================================================================================
    // Recording Logic
    // =================================================================================

    public void startRecording() {
        if (wakeLock == null) {
            PowerManager powerManager = (PowerManager) this.appContext.getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag");
        }
        wakeLock.acquire(31 * 60 * 1000L);

        this.saveRecording = true;
        this.stepCounter = 0;
        this.absoluteStartTime = System.currentTimeMillis();
        this.bootTime = SystemClock.uptimeMillis();

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault());
        String readableTime = sdf.format(new Date(this.absoluteStartTime));

        // Generate Filename (ID)
        String baseName = "Traj_" + readableTime;
        if (!currentVenue.equals("unknown")) {
            baseName += "_" + currentVenue;
        }
        settings.edit().putString("trajectory_name", baseName).apply();

        // Set Initial Position
        Traj.GNSSPosition initialPos = Traj.GNSSPosition.newBuilder()
                .setLatitude(startLocation[0])
                .setLongitude(startLocation[1])
                .setRelativeTimestamp(0)
                .build();

        // Initialize Trajectory Builder
        this.trajectory = Traj.Trajectory.newBuilder()
                .setAndroidVersion(Build.VERSION.RELEASE)
                .setTrajectoryVersion(2.0f)
                .setTrajectoryId(baseName)
                .setStartTimestamp(absoluteStartTime)
                .setInitialPosition(initialPos)
                .setAccelerometerInfo(createSensorInfo(accelerometerSensor))
                .setGyroscopeInfo(createSensorInfo(gyroscopeSensor))
                .setMagnetometerInfo(createSensorInfo(magnetometerSensor))
                .setBarometerInfo(createSensorInfo(barometerSensor))
                .setLightSensorInfo(createSensorInfo(lightSensor))
                .setProximityInfo(createSensorInfo(proximitySensor));

        this.storeTrajectoryTimer = new Timer();
        this.storeTrajectoryTimer.schedule(new storeDataInTrajectory(), 0, TIME_CONST);
        this.pdrProcessing.resetPDR();

        this.filter_coefficient = settings.getBoolean("overwrite_constants", false) ?
                Float.parseFloat(settings.getString("accel_filter", "0.96")) : FILTER_COEFFICIENT;
    }

    private Traj.SensorInfo.Builder createSensorInfo(MovementSensor sensor) {
        if (sensor == null || sensor.sensor == null) {
            return Traj.SensorInfo.newBuilder().setName("Unknown");
        }
        return Traj.SensorInfo.newBuilder()
                .setName(sensor.sensorInfo.getName())
                .setVendor(sensor.sensorInfo.getVendor())
                .setResolution(sensor.sensorInfo.getResolution())
                .setPower(sensor.sensorInfo.getPower())
                .setVersion(sensor.sensorInfo.getVersion())
                .setType(sensor.sensorInfo.getType())
                .setMaxRange(sensor.sensor.getMaximumRange())
                .setFrequency(0);
    }

    public void stopRecording() {
        if(this.saveRecording) {
            this.saveRecording = false;
            storeTrajectoryTimer.cancel();
        }
        if(wakeLock.isHeld()) wakeLock.release();
    }

    public void sendTrajectoryToCloud() {
        this.serverCommunications.sendTrajectory(trajectory.build());
    }

    private class storeDataInTrajectory extends TimerTask {
        public void run() {
            long relativeTime = SystemClock.uptimeMillis() - bootTime;

            Traj.Vector3 accVec = Traj.Vector3.newBuilder().setX(acceleration[0]).setY(acceleration[1]).setZ(acceleration[2]).build();
            Traj.Vector3 gyrVec = Traj.Vector3.newBuilder().setX(angularVelocity[0]).setY(angularVelocity[1]).setZ(angularVelocity[2]).build();
            Traj.Quaternion rotQuat = Traj.Quaternion.newBuilder().setX(rotation[0]).setY(rotation[1]).setZ(rotation[2]).setW(rotation[3]).build();

            // 1. IMU
            trajectory.addImuData(Traj.IMUReading.newBuilder()
                    .setRelativeTimestamp(relativeTime)
                    .setAcc(accVec)
                    .setGyr(gyrVec)
                    .setRotationVector(rotQuat)
                    .setStepCount(stepCounter));

            // 2. Magnetometer
            Traj.Vector3 magVec = Traj.Vector3.newBuilder().setX(magneticField[0]).setY(magneticField[1]).setZ(magneticField[2]).build();
            trajectory.addMagnetometerData(Traj.MagnetometerReading.newBuilder()
                    .setRelativeTimestamp(relativeTime)
                    .setMag(magVec));

            if (counter == 99) {
                counter = 0;
                if (barometerSensor.sensor != null) {
                    // 3. Pressure
                    trajectory.addPressureData(Traj.BarometerReading.newBuilder()
                            .setRelativeTimestamp(relativeTime)
                            .setPressure(pressure));
                    // 4. Light
                    trajectory.addLightData(Traj.LightReading.newBuilder()
                            .setRelativeTimestamp(relativeTime)
                            .setLight(light));
                }

                if (secondCounter == 4) {
                    secondCounter = 0;
                    Wifi current = wifiProcessor.getCurrentWifiData();
                    if(current != null) {
                        // 5. AP Connection Data
                        trajectory.addApsData(Traj.WiFiAPData.newBuilder()
                                .setMac(current.getBssid())
                                .setSsid(current.getSsid())
                                .setFrequency(current.getFrequency())
                                .setRttEnabled(current.isRtt()));
                    }
                } else {
                    secondCounter++;
                }
            } else {
                counter++;
            }
        }
    }

    public void addMarker() {
        if (!saveRecording || trajectory == null) {
            return;
        }
        long relativeTime = SystemClock.uptimeMillis() - bootTime;
        Log.d("SensorFusion", "Adding Marker at: " + relativeTime);

        try {
            Traj.GNSSPosition marker = Traj.GNSSPosition.newBuilder()
                    .setRelativeTimestamp(relativeTime)
                    .setLatitude(latitude)
                    .setLongitude(longitude)
                    .setAltitude(elevation)
                    .setFloor(String.valueOf(getWifiFloor()))
                    .build();

            trajectory.addTestPoints(marker);
        } catch (Exception e) {
            Log.e("SensorFusion", "Error adding marker: " + e.getMessage());
        }
    }

    /**
     * Updates the building name for Context Awareness.
     * If recording is active, it immediately updates the Trajectory ID (filename).
     */
    public void setVenueName(String name) {
        if (name != null && !name.isEmpty()) {
            this.currentVenue = name;
            Log.d("SensorFusion", "Current venue set to: " + this.currentVenue);

            if (this.saveRecording && this.trajectory != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault());
                String readableTime = sdf.format(new Date(this.absoluteStartTime));

                String newName = "Traj_" + readableTime + "_" + name;

                this.trajectory.setTrajectoryId(newName);
                settings.edit().putString("trajectory_name", newName).apply();

                Log.d("SensorFusion", "Filename dynamically updated to: " + newName);
            }
        }
    }

    // Getters and Helpers
    public float[] getGNSSLatitude(boolean start) { return !start ? new float[]{latitude, longitude} : startLocation; }
    public void setStartGNSSLatitude(float[] startPosition){ startLocation = startPosition; }
    public void redrawPath(float scalingRatio){ pathView.redraw(scalingRatio); }
    public float passAverageStepLength(){ return pdrProcessing.getAverageStepLength(); }
    public List<Wifi> getWifiList() { return this.wifiList; }

    public List<SensorInfo> getSensorInfos() {
        List<SensorInfo> list = new ArrayList<>();
        list.add(accelerometerSensor.sensorInfo);
        if(gyroscopeSensor.sensor != null) list.add(gyroscopeSensor.sensorInfo);
        if(magnetometerSensor.sensor != null) list.add(magnetometerSensor.sensorInfo);
        if(barometerSensor.sensor != null) list.add(barometerSensor.sensorInfo);
        if(lightSensor.sensor != null) list.add(lightSensor.sensorInfo);
        return list;
    }

    public void registerForServerUpdate(Observer observer) { serverCommunications.registerObserver(observer); }
    public float getElevation() { return this.elevation; }
    public boolean getElevator() { return this.elevator; }
    public int getHoldMode(){ return (proximity<1 && light>100) ? 1 : 0; }

    public void resumeListening() {
        accelerometerSensor.sensorManager.registerListener(this, accelerometerSensor.sensor, 10000, (int) maxReportLatencyNs);
        barometerSensor.sensorManager.registerListener(this, barometerSensor.sensor, (int) 1e6);
        gyroscopeSensor.sensorManager.registerListener(this, gyroscopeSensor.sensor, 10000, (int) maxReportLatencyNs);
        lightSensor.sensorManager.registerListener(this, lightSensor.sensor, (int) 1e6);
        proximitySensor.sensorManager.registerListener(this, proximitySensor.sensor, (int) 1e6);
        magnetometerSensor.sensorManager.registerListener(this, magnetometerSensor.sensor, 10000, (int) maxReportLatencyNs);
        stepDetectionSensor.sensorManager.registerListener(this, stepDetectionSensor.sensor, SensorManager.SENSOR_DELAY_NORMAL);
        rotationSensor.sensorManager.registerListener(this, rotationSensor.sensor, (int) 1e6);
        gravitySensor.sensorManager.registerListener(this, gravitySensor.sensor, 10000, (int) maxReportLatencyNs);
        linearAccelerationSensor.sensorManager.registerListener(this, linearAccelerationSensor.sensor, 10000, (int) maxReportLatencyNs);

        wifiProcessor.startListening();
        gnssProcessor.startLocationUpdates();
    }

    public void stopListening() {
        if(!saveRecording) {
            accelerometerSensor.sensorManager.unregisterListener(this);
            barometerSensor.sensorManager.unregisterListener(this);
            gyroscopeSensor.sensorManager.unregisterListener(this);
            lightSensor.sensorManager.unregisterListener(this);
            proximitySensor.sensorManager.unregisterListener(this);
            magnetometerSensor.sensorManager.unregisterListener(this);
            stepDetectionSensor.sensorManager.unregisterListener(this);
            rotationSensor.sensorManager.unregisterListener(this);
            gravitySensor.sensorManager.unregisterListener(this);
            linearAccelerationSensor.sensorManager.unregisterListener(this);

            wifiProcessor.stopListening();
            gnssProcessor.stopUpdating();
        }
    }
    public void onAccuracyChanged(Sensor sensor, int i) {}
}