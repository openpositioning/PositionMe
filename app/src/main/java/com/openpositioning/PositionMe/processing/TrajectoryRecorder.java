package com.openpositioning.PositionMe.processing;

import android.hardware.Sensor;
import android.os.Build.VERSION;
import android.os.SystemClock;
import android.util.Log;
import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.presentation.fragment.TrajectoryMapFragment;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.sensors.SensorData.AccelerometerData;
import com.openpositioning.PositionMe.sensors.SensorData.GNSSLocationData;
import com.openpositioning.PositionMe.sensors.SensorData.GyroscopeData;
import com.openpositioning.PositionMe.sensors.SensorData.LightData;
import com.openpositioning.PositionMe.sensors.SensorData.MagneticFieldData;
import com.openpositioning.PositionMe.sensors.SensorData.PressureData;
import com.openpositioning.PositionMe.sensors.SensorData.RotationVectorData;
import com.openpositioning.PositionMe.sensors.SensorData.SensorData;
import com.openpositioning.PositionMe.sensors.SensorData.StepDetectorData;
import com.openpositioning.PositionMe.sensors.SensorData.WiFiData;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorHub;
import com.openpositioning.PositionMe.sensors.SensorListeners.SensorDataListener;
import com.openpositioning.PositionMe.sensors.StreamSensor;
import com.openpositioning.PositionMe.sensors.Wifi;
import java.util.Timer;
import java.util.TimerTask;

/**
 * TrajectoryRecorder is a class that records sensor data and sends it to a server.
 * It implements the SensorDataListener interface to receive sensor data updates from SensorHub.
 * The class uses a Timer to periodically store sensor data in a Trajectory object.
 *
 * It implements the Observer interface to receive updates from the PDR processing class, whenever
 * new PDR steps are available.
 * <p>
 * The trajectory object is built using the Traj protobuf class, which contains various sensor
 * information and data samples.
 * <p>
 * The class also provides methods to start and stop recording, as well as to send the recorded
 * trajectory to the server.
 *
 * @see PdrProcessing for source of observed data.
 * @see SensorHub for source of sensor data.
 * @see SensorDataListener for interface to receive sensor data.
 * @see ServerCommunications for sending data to the server.
 *
 * @author Philip Heptonstall
 **/
public class TrajectoryRecorder implements SensorDataListener<SensorData>, Observer {

  // Data saving timer
  private static final long TIME_CONST = 10;
  private Timer storeTrajectoryTimer;

  // Counters for dividing timer to record data every 1 second/ every 5 seconds
  private int counter;
  private int secondCounter;

  // Server communication class for sending data
  private final ServerCommunications serverCommunications;
  // Trajectory object containing all data
  private Traj.Trajectory.Builder trajectory;

  private final PdrProcessing pdrProcessor;

  // Sensor Variables
  private long startTime;
  private long bootTime;
  private SensorHub sensorHub;

  // List of sensors of interest (int and stream sensors)
  private final int[] INTERESTED_SENSORS = new int[]{
      Sensor.TYPE_PRESSURE, Sensor.TYPE_ACCELEROMETER,
      Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_STEP_DETECTOR,
      Sensor.TYPE_MAGNETIC_FIELD,  Sensor.TYPE_LIGHT, Sensor.TYPE_GYROSCOPE
  };
  private final StreamSensor[] INTERESTED_STREAM_SENSORS = new StreamSensor[] {
    StreamSensor.GNSS, StreamSensor.WIFI
  };

  // Collected sensor data
  private PressureData pressureData;
  private AccelerometerData accelerometerData;
  private RotationVectorData rotationVectorData;
  private MagneticFieldData magneticFieldData;
  private LightData lightData;
  private GyroscopeData gyroscopeData;
  private WiFiData wifiData;
  private GNSSLocationData gnssData;

  /**
   * Constructor for TrajectoryRecorder.
   * <p>
   * Initializes the trajectory object and registers the sensors of interest.
   *
   * @param sensorHub The sensor hub to be used for managing sensors.
   * @param pdrProcessor The PDR processing object to be used for processing PDR data.
   * @param serverCommunications The server communications object to be used for sending data.
   * @param startTime The start time of the recording session.
   * @param bootTime The boot time of the device.
   **/
  public TrajectoryRecorder(SensorHub sensorHub, PdrProcessing pdrProcessor,
      ServerCommunications serverCommunications, long startTime, long bootTime) {
    // Register sensors
    this.sensorHub = sensorHub;

    this.startTime = startTime;

    this.bootTime = bootTime;

    start();

    this.pdrProcessor = pdrProcessor;
    this.pdrProcessor.registerObserver(this);

    this.serverCommunications = serverCommunications;

    this.trajectory = Traj.Trajectory.newBuilder()
        .setAndroidVersion(VERSION.RELEASE)
        .setStartTimestamp(startTime)
        .setAccelerometerInfo(createInfoBuilder(Sensor.TYPE_ACCELEROMETER))
        .setGyroscopeInfo(createInfoBuilder(Sensor.TYPE_GYROSCOPE))
        .setMagnetometerInfo(createInfoBuilder(Sensor.TYPE_MAGNETIC_FIELD))
        .setBarometerInfo(createInfoBuilder(Sensor.TYPE_PRESSURE))
        .setLightSensorInfo(createInfoBuilder(Sensor.TYPE_LIGHT));
  }

  /**
   * Updates the trajectory with the PDR data.
   * <p>
   * This method is called when new PDR data is received.
   *
   * @param objList The list of objects containing the PDR data.
   */
  @Override
  public void update(Object[] objList) {
    // Update the trajectory with the PDR data
    if (objList != null && objList.length > 0) {
      // Update provides the X,Y float array of the PDR data
      PdrProcessing.PdrData pdrData = (PdrProcessing.PdrData) objList[0];
      if (pdrData.newPosition()) {
        // Update the trajectory with the PDR data
        trajectory.addPdrData(Traj.Pdr_Sample.newBuilder()
            .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
            .setX(pdrData.position()[0])
            .setY(pdrData.position()[1]));
      }
    } else {
      Log.e("SensorFusion", "PDR data is null or empty.");
    }
  }

  /**
   * Timer task to record data with the desired frequency in the trajectory class.
   */
  private class storeDataInTrajectory extends TimerTask {

    public void run() {
      if (accelerometerData == null || rotationVectorData == null
          || magneticFieldData == null || gyroscopeData == null) {
        Log.e("SensorFusion", "Sensor data is null, skipping data storage.");
        return;
      }
      // Store IMU and magnetometer data in Trajectory cl1ass
      trajectory.addImuData(Traj.Motion_Sample.newBuilder()
              .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
              .setAccX(accelerometerData.acceleration[0])
              .setAccY(accelerometerData.acceleration[1])
              .setAccZ(accelerometerData.acceleration[2])
              .setGyrX(gyroscopeData.angularVelocity[0])
              .setGyrY(gyroscopeData.angularVelocity[1])
              .setGyrZ(gyroscopeData.angularVelocity[2])
              .setGyrZ(gyroscopeData.angularVelocity[2])
              .setRotationVectorX(rotationVectorData.rotation[0])
              .setRotationVectorY(rotationVectorData.rotation[1])
              .setRotationVectorZ(rotationVectorData.rotation[2])
              .setRotationVectorW(rotationVectorData.rotation[3])
              .setStepCount(pdrProcessor.getStepCount()))
          .addPositionData(Traj.Position_Sample.newBuilder()
              .setMagX(magneticFieldData.magneticField[0])
              .setMagY(magneticFieldData.magneticField[1])
              .setMagZ(magneticFieldData.magneticField[2])
              .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime));
//                    .addGnssData(Traj.GNSS_Sample.newBuilder()
//                            .setLatitude(latitude)
//                            .setLongitude(longitude)
//                            .setRelativeTimestamp(SystemClock.uptimeMillis()-bootTime))


      // Divide timer with a counter for storing data every 1 second
      if (counter == 99) {
        counter = 0;
        // Store pressure and light data
        if (pressureData != null) {
          trajectory.addPressureData(Traj.Pressure_Sample.newBuilder()
                  .setPressure(pressureData.pressure)
                  .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime))
                  .addLightData(Traj.Light_Sample.newBuilder()
                      .setLight(lightData.light)
                      .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
                      .build());
        }

        // Divide the timer for storing AP data every 5 seconds
        if (secondCounter == 4) {
          secondCounter = 0;
          //Current Wifi Object
          Wifi currentWifi = ((WifiDataProcessor) sensorHub.getSensor(StreamSensor.WIFI)).
              getCurrentWifiData();
          trajectory.addApsData(Traj.AP_Data.newBuilder()
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

  /**
   * Creates a {@link Traj.Sensor_Info} objects from the specified sensor's data.
   *
   * @param sensorType taken from Sensor.TYPE_* constants
   * @return Traj.SensorInfo object to be used in building the trajectory
   * @see Traj            Trajectory object used for communication with the server
   */
  private Traj.Sensor_Info.Builder createInfoBuilder(int sensorType) {
    // Get the sensor information from the SensorHub
    Sensor sensor = sensorHub.getSensor(sensorType);
    if (sensor == null) {
      return Traj.Sensor_Info.newBuilder()
          .setName("Not available")
          .setVendor("-")
          .setResolution(-1.0f)
          .setPower(0.0f)
          .setVersion(0)
          .setType(0);
    } else {
      return Traj.Sensor_Info.newBuilder()
          .setName(sensor.getName())
          .setVendor(sensor.getVendor())
          .setResolution(sensor.getResolution())
          .setPower(sensor.getPower())
          .setVersion(sensor.getVersion())
          .setType(sensor.getType());
    }
  }


  /**
   * Called when sensor data is received.
   * <p>
   * This method is called when new sensor data is received from the SensorHub.
   *
   * @param data The sensor data received from the SensorHub.
   */
  @Override
  public void onSensorDataReceived(SensorData data) {
    if (data instanceof PressureData) {
      pressureData = (PressureData) data;
    } else if (data instanceof AccelerometerData) {
      accelerometerData = (AccelerometerData) data;
    } else if (data instanceof RotationVectorData) {
      rotationVectorData = (RotationVectorData) data;
    } else if (data instanceof StepDetectorData) {
      // Do nothing
    } else if (data instanceof MagneticFieldData) {
      magneticFieldData = (MagneticFieldData) data;
    } else if (data instanceof LightData) {
      lightData = (LightData) data;
    } else if (data instanceof GyroscopeData) {
      gyroscopeData = (GyroscopeData) data;
    } else if (data instanceof WiFiData) {
      wifiData = (WiFiData) data;
      saveWifiData(wifiData);
    } else if (data instanceof GNSSLocationData) {
      gnssData = (GNSSLocationData) data;
      saveGnssData(gnssData);
    }
  }

  /**
   * Stops the trajectory recording and unregisters the sensors.
   * <p>
   * This method is called when the recording session is stopped.
   **/
  @Override
  public void stop() {
    // Stop all sensors
    for (int sensor_idx : INTERESTED_SENSORS) {
      sensorHub.removeListener(sensor_idx, this);
    }

    for (StreamSensor sensor_idx : INTERESTED_STREAM_SENSORS) {
      sensorHub.removeListener(sensor_idx, this);
    }

    // Cancel the timer
    storeTrajectoryTimer.cancel();
  }

  /**
   * Starts the trajectory recording and registers the sensors.
   * <p>
   * This method is called when the recording session is started.
   **/
  @Override
  public void start() {
    // Start all sensors
    for (int sensor_idx : INTERESTED_SENSORS) {
      sensorHub.addListener(sensor_idx, this);
    }

    for (StreamSensor sensor_idx : INTERESTED_STREAM_SENSORS) {
      sensorHub.addListener(sensor_idx, this);
    }

    // Restart the timer
    storeTrajectoryTimer = new Timer();
    this.storeTrajectoryTimer.schedule(new storeDataInTrajectory(),
        100, TIME_CONST);
  }

  /**
   * Saves the WiFi data in the trajectory object.
   * <p>
   * This method is called when new WiFi data is received.
   *
   * @param data The WiFi data received from the SensorHub.
   */
  private void saveWifiData(WiFiData data) {
    Traj.WiFi_Sample.Builder wifiData = Traj.WiFi_Sample.newBuilder()
        .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime);
    for (Wifi wifiSample : data.wifiList) {
      wifiData.addMacScans(Traj.Mac_Scan.newBuilder()
          .setRelativeTimestamp(SystemClock.uptimeMillis() - bootTime)
          .setMac(wifiSample.getBssid()).setRssi(wifiSample.getLevel()));
    }
    // Adding WiFi data to Trajectory
    this.trajectory.addWifiData(wifiData);
  }

  /**
   * Saves the GNSS data in the trajectory object.
   * <p>
   * This method is called when new GNSS data is received.
   *
   * @param data The GNSS data received from the SensorHub.
   */
  private void saveGnssData(GNSSLocationData data) {
    trajectory.addGnssData(Traj.GNSS_Sample.newBuilder()
        .setAccuracy(data.accuracy)
        .setAltitude(data.altitude)
        .setLatitude(data.latitude)
        .setLongitude(data.longitude)
        .setSpeed(data.speed)
        .setProvider(data.provider)
        .setRelativeTimestamp(System.currentTimeMillis() - startTime));
  }

  /**
   * Adds a GNSS tag to the trajectory when the user presses "Add Tag". This method captures the
   * current GNSS location, derived from the (latitude, longitude, altitude) fused data, and stores
   * it inside the trajectory's `gnss_data` array.
   * <p>
   * The tag represents a marked position, which can later be used for debugging, validation, or
   * visualization on a map.
   */
  public void addTag(float[] fusedLocation) {

    // Check if the trajectory object exists before attempting to add a tag
    if (trajectory == null) {
      Log.e("SensorFusion", "Trajectory object is null, cannot add tag.");
      return;
    }
    // Capture the current timestamp relative to the start of the recording session
    long currentTimestamp = System.currentTimeMillis() - startTime;

    // Use fused location if available; otherwise, fall back to GNSS data.
    float currentLatitude;
    float currentLongitude;
    float currentAltitude; // Altitude is still taken from GNSS (or you could set a default if needed)

    // If statement ot check that fused location is not null and has more than 2 elements
    if (fusedLocation != null && fusedLocation.length >= 2) {
      currentLatitude = fusedLocation[0];
      currentLongitude = fusedLocation[1];
      currentAltitude = gnssData.altitude; // Fused data doesn't include altitude, so we reuse the GNSS altitude.
    } else {
      currentLatitude = gnssData.latitude;
      currentLongitude = gnssData.longitude;
      currentAltitude = gnssData.altitude;
    }

    String provider = "fusion";  // Set provider to "fusion" as per instructions

    // Construct a new GNSS_Sample protobuf message with the captured data
    Traj.GNSS_Sample tagSample = Traj.GNSS_Sample.newBuilder()
        .setRelativeTimestamp(currentTimestamp)  // Set timestamp relative to start
        .setLatitude(currentLatitude)  // Store latitude
        .setLongitude(currentLongitude)  // Store longitude
        .setAltitude(currentAltitude)  // Store altitude
        .setProvider(provider)  // Set provider information
        .build();

    // Append the new GNSS sample to the trajectory's `gnss_data` list
    trajectory.addGnssData(tagSample);

    // Get the instance of `TrajectoryMapFragment` using the static method
    TrajectoryMapFragment mapFragment = TrajectoryMapFragment.getInstance();

    if (mapFragment != null) {
      // If the map fragment exists, add the tag marker on the map
      mapFragment.addTagMarker(new LatLng(currentLatitude, currentLongitude));
    } else {
      // If the map fragment is null, log an error message
      Log.e("SensorFusion", "Map fragment is null. Cannot add tag marker.");
    }

    // Log the stored tag information for debugging and verification
    Log.d("SensorFusion", "Added GNSS tag: " + tagSample.toString());
  }

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
    this.stop();
  }
}
