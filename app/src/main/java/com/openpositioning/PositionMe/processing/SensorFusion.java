package com.openpositioning.PositionMe.processing;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.processing.filters.FilterAdapter;
import com.openpositioning.PositionMe.processing.filters.KalmanFilterAdapter;
import com.openpositioning.PositionMe.sensors.MovementSensor;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.sensors.SensorData.GNSSLocationData;
import com.openpositioning.PositionMe.sensors.SensorData.SensorData;
import com.openpositioning.PositionMe.sensors.SensorData.WiFiData;
import com.openpositioning.PositionMe.sensors.SensorHub;
import com.openpositioning.PositionMe.sensors.SensorInfo;
import com.openpositioning.PositionMe.sensors.SensorListeners.SensorDataListener;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.sensors.StreamSensor;
import com.openpositioning.PositionMe.sensors.Wifi;
import com.openpositioning.PositionMe.utils.CoordinateTransformer;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.presentation.fragment.SettingsFragment;

import java.util.Arrays;
import org.ejml.simple.SimpleMatrix;
import org.locationtech.proj4j.ProjCoordinate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * The SensorFusion class is the main data gathering and processing class of the application.
 * <p>
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
 * <p>
 * Kalman filter integration done by
 * @author Wojciech Boncela
 * @author Philip Heptonstall
 * @author Alexandros Zoupos
 */
public class SensorFusion implements SensorDataListener<SensorData>, Observer {
  // Sensor Variables
  private SensorHub sensorHub;
  private PdrProcessing pdrProcessing;
  private ServerCommunications serverCommunications;
  private TrajectoryRecorder trajRecorder;

  // To track current location from different sources
  // Coordinate transformer instance to convert WGS84 (Latitude, Longitude) coordinates into
  // a Northing-Easting space.
  // Initialized when given the start location.
  public CoordinateTransformer coordinateTransformer;
  private LatLng startLocation;
  private LatLng currentWifiLocation;
  private boolean isWifiLocationOutlier = false;
  private LatLng gnssLoc;
  private boolean isGnssOutlier = false;
  private LatLng fusedLocation;
  private float fusedError;


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
  // String for creating WiFi fingerprint JSON object
  private static final String WIFI_FINGERPRINT = "wf";

  private static final float OUTLIER_DISTANCE_THRESHOLD = 10;

  // Tuned Sensor Fusion constants

  public static final SimpleMatrix INIT_POS_COVARIANCE = new SimpleMatrix(new double[][]{
      {2.0, 0.0},
      {0.0, 2.0}
  });
  public static final SimpleMatrix PDR_COVARIANCE = new SimpleMatrix(new double[][]{
      {1.0, 0.0},
      {0.0, 5.0}
  });
  public static final SimpleMatrix WIFI_COVARIANCE = new SimpleMatrix(new double[][]{
      {2.0, 0.0},
      {0.0, 2.0}
  });
  public static final SimpleMatrix GNSS_COVARIANCE = new SimpleMatrix(new double[][]{
      {5.0, 0.0},
      {0.0, 5.0}
  });

  //region Instance variables
  // Keep device awake while recording
  private PowerManager.WakeLock wakeLock;
  private Context appContext;

  // Settings
  private SharedPreferences settings;

  // Settings
  private boolean saveRecording;
  private float filter_coefficient;
  // Variables to help with timed events
  private long absoluteStartTime;
  private long bootTime;


  // Over time accelerometer magnitude values since last step
  private List<Double> accelMagnitude;


  // Trajectory displaying class
  private PathView pathView;
  // WiFi positioning object
  private WiFiPositioning wiFiPositioning;
  // Actual sensor Fusion
  private FilterAdapter filter;

  //region Initialisation

  /**
   * Private constructor for implementing singleton design pattern for SensorFusion. Initialises
   * empty arrays and new objects that do not depends on outside information.
   */
  private SensorFusion() {}


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
   * Initialise all Movement sensor instances from context and predetermined types. Creates a server
   * communication instance for sending trajectories. Saves current absolute and relative time, and
   * initialises saving the recording to false.
   *
   * @param context application context for permissions and device access.
   * @see MovementSensor handling all SensorManager based data collection devices.
   * @see ServerCommunications handling communication with the server.
   * @see GNSSDataProcessor for location data processing.
   * @see WifiDataProcessor for network data processing.
   */
  public void setContext(Context context) {
    this.appContext = context.getApplicationContext(); // store app context for later use
    this.absoluteStartTime = System.currentTimeMillis();
    this.bootTime = SystemClock.uptimeMillis();
    // Initialise saveRecording to false
    this.saveRecording = false;

    // Create object handling HTTPS communication
    this.serverCommunications = new ServerCommunications(this.appContext);
    // Initialise the sensor hub
    this.sensorHub = SensorHub.getInstance(context);

    this.sensorHub.addListener(StreamSensor.GNSS,this);
    this.sensorHub.addListener(StreamSensor.WIFI,this);
    this.pdrProcessing = new PdrProcessing(context, this.sensorHub, absoluteStartTime, bootTime);
    this.pdrProcessing.registerObserver(this);
    // Sensor Fusion variables
    this.filter = new KalmanFilterAdapter(new double[]{0.0, 0.0}, INIT_POS_COVARIANCE,
        0.0, PDR_COVARIANCE);

    // Get settings and views
    this.settings = PreferenceManager.getDefaultSharedPreferences(context);
    this.pathView = new PathView(context, null);

    if (settings.getBoolean("overwrite_constants", false)) {
      this.filter_coefficient = Float.parseFloat(settings.getString("accel_filter", "0.96"));
    } else {
      this.filter_coefficient = FILTER_COEFFICIENT;
    }

    // Keep app awake during the recording (using stored appContext)
    PowerManager powerManager = (PowerManager) this.appContext.getSystemService(
        Context.POWER_SERVICE);
    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag");
  }

  //endregion
  // Getters/Setters
  /**
   * Method to get user position obtained using {@link WiFiPositioning}.
   *
   * @return {@link LatLng} corresponding to user's position.
   */
  public LatLng getLatLngWifiPositioning() {
    return this.wiFiPositioning.getWifiLocation();
  }

  /**
   * Method to get current floor the user is at, obtained using WiFiPositioning
   *
   * @return Current floor user is at using WiFiPositioning
   * @see WiFiPositioning for WiFi positioning
   */
  public int getWifiFloor() {
    return this.wiFiPositioning.getFloor();
  }

  //region Getters/Setters

  /**
   * Getter function for core location data.
   *
   * @param start set true to get the initial location
   * @return longitude and latitude data in a float[2].
   */
  public float[] getGNSSLatitude(boolean start) {
    float[] latLong = new float[2];
    if (!start) {
      if(gnssLoc == null) {
        return new float[]{0, 0};
      }
      latLong[0] = (float) this.gnssLoc.latitude;
      latLong[1] = (float) this.gnssLoc.longitude;
    } else {
      latLong[0] = (float) startLocation.latitude;
      latLong[1] = (float) startLocation.longitude;
    }
    return latLong;
  }

  /**
   * Setter function for core location data.
   *
   * @param startPosition contains the initial location set by the user
   */
  public void setStartGNSSLatitude(float[] startPosition) {
    this.fusedLocation = new LatLng(startPosition[0], startPosition[1]);
    this.startLocation = new LatLng(startPosition[0], startPosition[1]);
    this.coordinateTransformer = new CoordinateTransformer(startPosition[0], startPosition[1]);
  }

  /**
   * Getter function for average step count. Calls the average step count function in pdrProcessing
   * class
   *
   * @return average step count of total PDR.
   */
  public float passAverageStepLength() {
    return pdrProcessing.getAverageStepLength();
  }

  /**
   * Getter function for device orientation. Passes the orientation variable
   * @return orientation of the device.
   */
  public float passOrientation() {
    return this.pdrProcessing.getRotationVectorData().orientation[0];
  }

  /**
   * Return most recent sensor readings.
   * <p>
   * Collects all most recent readings from movement and location sensors, packages them in a map
   * that is indexed by {@link SensorTypes} and makes it accessible for other classes.
   *
   * @return Map of <code>SensorTypes</code> to float array of most recent values.
   * @author Philip Heptonstall, updated to enable wifi data and sensor data.
   */
  public Map<SensorTypes, float[]> getSensorValueMap() {
    Map<SensorTypes, float[]> sensorValueMap = new HashMap<>();
    sensorValueMap.put(SensorTypes.GNSSLATLONG, this.getGNSSLatitude(false));
    sensorValueMap.put(SensorTypes.PDR, pdrProcessing.getPDRMovement());
    if (currentWifiLocation != null) {
      sensorValueMap.put(SensorTypes.WIFI, new float[]{
          (float) currentWifiLocation.latitude,
          (float) currentWifiLocation.longitude});
      sensorValueMap.put(SensorTypes.WIFI_FLOOR, new float[]{this.getWifiFloor()});
      sensorValueMap.put(SensorTypes.WIFI_OUTLIER, new float[isWifiLocationOutlier ? 1 : 0]);
    }
    sensorValueMap.put(SensorTypes.FUSED, new float[] {
        (float) this.fusedLocation.latitude,
        (float) this.fusedLocation.longitude});
    return sensorValueMap;
  }

  /**
   * Get the estimated elevation value in meters calculated by the PDR class. Elevation is relative
   * to the starting position.
   *
   * @return float of the estimated elevation in meters.
   */
  public float getElevation() {
    return this.pdrProcessing.getCurrentElevation();
  }

  /**
   * Get an estimate by the PDR class whether it estimates the user is currently taking an
   * elevator.
   *
   * @return true if the PDR estimates the user is in an elevator, false otherwise.
   */
  public boolean getElevator() {
    return pdrProcessing.isElevator();
  }

  public float getFusionError() {
    return this.fusedError;
  }

  public SensorHub getSensorHub() {
    return sensorHub;
  }

  //endregion

//  /**
//   * Return the most recent list of WiFi names and levels. Each Wifi object contains a BSSID and a
//   * level value.
//   *
//   * @return list of Wifi objects.
//   */
//  public List<Wifi> getWifiList() {
//    return Arrays.asList(this.wifiProcessor.getWifiData());
//  }

  /**
   * Get information about all the sensors registered in SensorFusion.
   *
   * @return List of SensorInfo objects containing name, resolution, power, etc.
   */
  public List<SensorInfo> getSensorInfos() {
    return sensorHub.getSensorInfoList();
  }


  public static boolean isOutlier(CoordinateTransformer transformer,
      LatLng currentPoint, LatLng update) {
    ProjCoordinate currentPointXY = transformer
        .convertWGS84ToTarget(currentPoint.latitude, currentPoint.longitude);
    ProjCoordinate updatePointXY = transformer
        .convertWGS84ToTarget(update.latitude, update.longitude);
    double distance = CoordinateTransformer.calculateDistance(currentPointXY,
        updatePointXY);
    return distance > OUTLIER_DISTANCE_THRESHOLD;
  }

  private double computeMSE(SimpleMatrix covariance) {
    double mse = 0.0;
    // sum of diagonal elements (trace)
    for (int i = 0; i < covariance.numRows(); i++) {
      mse += covariance.get(i, i);
    }
    return mse;
  }

  private void updateFusionData(LatLng observation, SimpleMatrix observationCov, float[] pdrData) {
    double[] pdrData64 = {pdrData[0], pdrData[1]};

    // Convert the WiFi location to XY
    ProjCoordinate startLocationNorthEast = coordinateTransformer.convertWGS84ToTarget(
        startLocation.latitude,
        startLocation.longitude);
    ProjCoordinate observationNorthEast = coordinateTransformer.convertWGS84ToTarget(
        observation.latitude,
        observation.longitude);
    double[] wifiXYZ = CoordinateTransformer.getRelativePosition(
        startLocationNorthEast,
        observationNorthEast
    );
    double[] wifiXY = {wifiXYZ[0], wifiXYZ[1]};

    // Get the current timestamp and update the filter
    double timestamp = (System.currentTimeMillis() - absoluteStartTime) / 1e3;
    if (!filter.update(pdrData64, wifiXY, timestamp, observationCov)) {
      Log.w("SensorFusion", "Filter update failed");
    }
    double[] fusedPos = filter.getPos();

    // Convert back to Lat-Long
    ProjCoordinate fusedCoord = coordinateTransformer.applyDisplacementAndConvert(
        startLocation.latitude, startLocation.longitude, fusedPos[0], fusedPos[1]);
    fusedLocation = new LatLng(fusedCoord.y, fusedCoord.x);

    SimpleMatrix fusedCov = filter.getCovariance();
    this.fusedError = (float) Math.sqrt(computeMSE(fusedCov));
  }


  public void addTag(){
    this.trajRecorder.addTag(new float[]{(float) this.fusedLocation.latitude,
        (float) this.fusedLocation.longitude});
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
   * Registers the caller observer to receive updates from the server instance. Necessary when
   * classes want to act on a trajectory being successfully or unsuccessfully send to the server.
   * This grants access to observing the {@link ServerCommunications} instance used by the
   * SensorFusion class.
   *
   * @param observer Instance implementing {@link Observer} class who wants to be notified of events
   *                 relating to sending and receiving trajectories.
   */
  public void registerForServerUpdate(Observer observer) {
    this.serverCommunications.registerObserver(observer);
  }

  /**
   * Enables saving sensor values to the trajectory object.
   * Sets save recording to true, resets the absolute start time and create new timer object for
   * periodically writing data to trajectory.
   *
   * @see Traj object for storing data.
   */
  public void startRecording(float[] startingPoint) {
    // If wakeLock is null (e.g. not initialized or was cleared), reinitialize it.
    if (wakeLock == null) {
      PowerManager powerManager = (PowerManager) this.appContext.getSystemService(
          Context.POWER_SERVICE);
      wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag");
    }
    wakeLock.acquire(31 * 60 * 1000L /*31 minutes*/);

    this.saveRecording = true;
    this.pdrProcessing.resetPDR();
    this.absoluteStartTime = System.currentTimeMillis();
    this.bootTime = SystemClock.uptimeMillis();


    // Protobuf trajectory class for sending sensor data to restful API
    this.trajRecorder = new TrajectoryRecorder(sensorHub, pdrProcessing
        , serverCommunications, absoluteStartTime, bootTime);

    if (settings.getBoolean("overwrite_constants", false)) {
      this.filter_coefficient = Float.parseFloat(settings.getString("accel_filter", "0.96"));
    } else {
      this.filter_coefficient = FILTER_COEFFICIENT;
    }

    this.setStartGNSSLatitude(startingPoint);
  }

  /**
   * Disables saving sensor values to the trajectory object.
   * <p>
   * Check if a recording is in progress. If it is, it sets save recording to false, and cancels the
   * timer objects.
   *
   * @see Traj object for storing data.
   * @see SettingsFragment navigation that might cancel recording.
   */
  public void stopRecording() {
    // Only cancel if we are running
    if (this.saveRecording) {
      this.saveRecording = false;
      this.trajRecorder.sendTrajectoryToCloud();
    }
    if (wakeLock.isHeld()) {
      this.wakeLock.release();
    }
  }

  /**
   * Un-registers all device listeners and pauses data collection.
   * <p>
   * Should be called from {@link MainActivity} when pausing the application.
   *
   * @see MovementSensor handles SensorManager based devices.
   * @see WifiDataProcessor handles wifi data.
   * @see GNSSDataProcessor handles location data.
   */
  @Override
  public void stop() {
    if (!saveRecording) {
      this.trajRecorder.stop();
    }
    this.pdrProcessing.stop();
  }

  /**
   * Registers all device listeners and enables updates with the specified sampling rate.
   * <p>
   * Should be called from {@link MainActivity} when resuming the application. Sampling rate is in
   * microseconds, IMU needs 100Hz, rest 1Hz
   *
   * @see WifiDataProcessor handles wifi data.
   * @see GNSSDataProcessor handles location data.
   */
  @Override
  public void start() {
    this.pdrProcessing.start();
    if(saveRecording) {
      this.trajRecorder.start();
    }
  }


  /**
   * Utility function to log the event frequency of each sensor. Call this periodically for
   * debugging purposes.
   */
  public void logSensorFrequencies() {
    for (int sensorType : eventCounts.keySet()) {
      Log.d("SensorFusion",
          "Sensor " + sensorType + " | Event Count: " + eventCounts.get(sensorType));
    }
  }

  public void onSensorDataReceived(GNSSLocationData data) {
    float[] currPdrData = this.pdrProcessing.getPDRMovement();
    LatLng loc = new LatLng(data.latitude, data.longitude);
    this.gnssLoc = loc;
    float xAccuracy = data.accuracy;
    float xVariance = (float) Math.pow(xAccuracy, 2);
    float yVariance = (float) Math.pow(data.verticalAccuracy, 2);
    SimpleMatrix currentGnssCovariance = GNSS_COVARIANCE.copy();
    currentGnssCovariance.set(0, 0, xVariance);
    currentGnssCovariance.set(1, 1, yVariance);
    if (startLocation != null && currPdrData != null) {
      if (!isOutlier(coordinateTransformer, fusedLocation, loc)) {
        updateFusionData(loc, currentGnssCovariance, currPdrData);
      } else {
        this.isGnssOutlier = true;
      }
    }
  }
  public void onSensorDataReceived(WiFiData data) {
    // Handle WiFi scan result
    if (data.location != this.currentWifiLocation) {
      float[] pdrData = pdrProcessing.getPDRMovement();
      this.currentWifiLocation = data.location;
      if (coordinateTransformer != null) {
        this.isWifiLocationOutlier = isOutlier(coordinateTransformer,
            fusedLocation,
            this.currentWifiLocation);
        if (startLocation != null && pdrData != null && !isWifiLocationOutlier) {
          updateFusionData(this.currentWifiLocation, WIFI_COVARIANCE, pdrData);
        }
      }
    }
  }

  @Override
  public void onSensorDataReceived(SensorData data) {
    // Check if the data is from the GNSS sensor
    if (data instanceof GNSSLocationData gnssData) {
      onSensorDataReceived(gnssData);
    } else if (data instanceof WiFiData wiFiData) {
      // Handle WiFi scan result
      onSensorDataReceived(wiFiData);
    }
  }

  @Override
  public void update(Object[] objList) {
    // Convert to Float[] array, called by PDRProcessing
    // to update the path view
    float[] pdrData = new float[objList.length];
    for (int i = 0; i < objList.length; i++) {
      pdrData[i] = ((Number) objList[i]).floatValue();
    }
    // Update the path view with the new PDR data
    pathView.drawTrajectory(pdrData);
  }
}
