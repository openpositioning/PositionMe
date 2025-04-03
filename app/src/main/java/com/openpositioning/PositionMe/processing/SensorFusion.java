package com.openpositioning.PositionMe.processing;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.processing.filters.FilterAdapter;
import com.openpositioning.PositionMe.processing.filters.KalmanFilterAdapter;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.sensors.SensorData.GNSSLocationData;
import com.openpositioning.PositionMe.sensors.SensorData.SensorData;
import com.openpositioning.PositionMe.sensors.SensorData.WiFiData;
import com.openpositioning.PositionMe.sensors.SensorHub;
import com.openpositioning.PositionMe.sensors.SensorInfo;
import com.openpositioning.PositionMe.sensors.SensorListeners.SensorDataListener;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.sensors.StreamSensor;
import com.openpositioning.PositionMe.utils.CoordinateTransformer;
import com.openpositioning.PositionMe.utils.NucleusBuildingManager;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.presentation.fragment.SettingsFragment;

import org.ejml.simple.SimpleMatrix;
import org.locationtech.proj4j.ProjCoordinate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * SensorFusion is a singleton class that handles the fusion of sensor data from various sources
 * such as GNSS, WiFi, and PDR. It processes the data and provides a fused location estimate.
 * It is composed of several components:
 * - SensorHub: Manages the sensors and their data, to which SensorFusion subscribes to get GNSS,
 * WiFi position updates
 * - PdrProcessing: Processes PDR data and provides step count and orientation.
 * - TrajectoryRecorder: Records the trajectory data and sends it to the server.
 * - ServerCommunications: Handles the communication with the server for sending trajectory data
 * (used by TrajectoryRecorder)
 * To retrieve updates from SensorHub, this class implements the SensorDataListener interface.
 * It also implements the Observer interface to receive PDR updates from PdrProcessing.
 *
 * @author Michal Dvorak
 * @author Mate Stodulka
 * @author Virginia Cangelosi
 * Kalman filter integration done by:
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
  private int currentWifiFloor;
  private boolean isWifiLocationOutlier = false;
  private boolean noWifiCoverage = false;
  private LatLng gnssLoc;
  private boolean isGnssOutlier = false;
  private LatLng fusedLocation;
  private float fusedError;

  private boolean inElevator = false;
  private int elevatorTrueCount = 0;

  private boolean elevator = false;



  // Store the last event timestamps for each sensor type
  private HashMap<Integer, Integer> eventCounts = new HashMap<>();

  //region Static variables
  // Singleton Class
  private static final SensorFusion sensorFusion = new SensorFusion();
  private static final float OUTLIER_DISTANCE_THRESHOLD = 15;

  // Actual sensor Fusion
  private FilterAdapter filter;
  // Tuned Sensor Fusion constants

  public static final SimpleMatrix INIT_POS_COVARIANCE = new SimpleMatrix(new double[][]{
      {2.0, 0.0},
      {0.0, 2.0}
  });
  public static final SimpleMatrix PDR_COVARIANCE = new SimpleMatrix(new double[][]{
          {Math.pow(1.41, 2), 0.0},
          {0.0, Math.pow(0.32, 2)}
  });

  public static final SimpleMatrix WIFI_COVARIANCE = new SimpleMatrix(new double[][]{
          {Math.pow(0.5, 2), 0.0},
          {0.0, Math.pow(0.5, 2)}
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
  // Variables to help with timed events
  private long absoluteStartTime;
  private long bootTime;


  // Trajectory displaying class
  private PathView pathView;


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
   * Function to set the context of the SensorFusion class.
   * This function is called from the {@link MainActivity} when the application is started. It
   * initialises the SensorHub, PdrProcessing, and ServerCommunications classes. It also sets up
   * the wake lock to keep the device awake during recording.
   *
   * @param context Context of object calling
   */
  public void setContext(Context context) {
    this.appContext = context.getApplicationContext(); // store app context for later use

    // Initialise times
    this.absoluteStartTime = System.currentTimeMillis();
    this.bootTime = SystemClock.uptimeMillis();
    // Initialise saveRecording to false
    this.saveRecording = false;

    // Create object handling HTTPS communication
    this.serverCommunications = new ServerCommunications(this.appContext);
    // Initialise the sensor hub
    this.sensorHub = SensorHub.getInstance(context);

    // Subscribe to the SensorHub for GNSS and WiFi data
    this.sensorHub.addListener(StreamSensor.GNSS, this);
    this.sensorHub.addListener(StreamSensor.WIFI, this);

    // Initialise the PDR processing class and register for updates
    this.pdrProcessing = new PdrProcessing(context, this.sensorHub, absoluteStartTime, bootTime);
    this.pdrProcessing.registerObserver(this);

    // Initialise filter
    this.filter = new KalmanFilterAdapter(new double[]{0.0, 0.0}, INIT_POS_COVARIANCE,
        0.0, PDR_COVARIANCE);

    // Get settings and views
    this.settings = PreferenceManager.getDefaultSharedPreferences(context);
    this.pathView = new PathView(context, null);

    // Keep app awake during the recording (using stored appContext)
    PowerManager powerManager = (PowerManager) this.appContext.getSystemService(
        Context.POWER_SERVICE);
    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag");
  }

  //endregion
  // Getters/Setters
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
   * @param startPosition contains the initial location set by the user
   */
  public void setStartGNSSLatitude(float[] startPosition) {
    // Set the start location and initialise the coordinate transformer
    // with the starting position.
    this.startLocation = new LatLng(startPosition[0], startPosition[1]);
    this.coordinateTransformer = new CoordinateTransformer(startPosition[0], startPosition[1]);
  }

  /**
   * Getter function for average step count. Calls the average step count function in pdrProcessing
   * class
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
   * Return processed sensor readings from: GNSS, PDR, WIFI, and FUSED location.
   * Indexed by SensorTypes enum.
   * @return Map of <code>SensorTypes</code> to float array of most recent values.
   * @author Philip Heptonstall, updated to enable wifi data and sensor data.
   */
  public Map<SensorTypes, float[]> getSensorValueMap() {
    Map<SensorTypes, float[]> sensorValueMap = new HashMap<>();
    sensorValueMap.put(SensorTypes.GNSSLATLONG, this.getGNSSLatitude(false));
    sensorValueMap.put(SensorTypes.GNSS_OUTLIER, new float[] {isGnssOutlier ? 1 : 0});
    sensorValueMap.put(SensorTypes.PDR, pdrProcessing.getPDRMovement());
    if (currentWifiLocation != null) {
      sensorValueMap.put(SensorTypes.WIFI, new float[]{
          (float) currentWifiLocation.latitude,
          (float) currentWifiLocation.longitude});
      sensorValueMap.put(SensorTypes.WIFI_FLOOR, new float[]{this.currentWifiFloor});
      sensorValueMap.put(SensorTypes.WIFI_OUTLIER, new float[] {isWifiLocationOutlier ? 1 : 0});
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
    return this.elevator;
  }

  /**
   * Get the fusion error in meters. The fusion error is the square root of the sum of the diagonal
   * elements of the covariance matrix.
   * @return float of the fusion error in meters.
   */
  public float getFusionError() {
    return this.fusedError;
  }


  public SensorHub getSensorHub() {
    return sensorHub;
  }

  //endregion

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

  /**
   * Compute the Mean Squared Error (MSE) of the covariance matrix.
   *
   * @param covariance The covariance matrix.
   * @return The MSE value.
   */
  private double computeMSE(SimpleMatrix covariance) {
    double mse = 0.0;
    // sum of diagonal elements (trace)
    for (int i = 0; i < covariance.numRows(); i++) {
      mse += covariance.get(i, i);
    }
    return mse;
  }


  /**
   * Convert latitude and longitude to XY coordinates relative to the reference point.
   *
   * @param reference The reference point (start location).
   * @param pt The point to convert.
   * @return An array containing the X and Y coordinates.
   */
  private double[] latLngToXY(LatLng reference, LatLng pt) {
    ProjCoordinate startLocationNorthEast = coordinateTransformer.convertWGS84ToTarget(
            reference.latitude,
            reference.longitude);
    ProjCoordinate observationNorthEast = coordinateTransformer.convertWGS84ToTarget(
            pt.latitude,
            pt.longitude);
    double[] ptXYZ = CoordinateTransformer.getRelativePosition(
            startLocationNorthEast,
            observationNorthEast
    );
    return new double[]{ptXYZ[0], ptXYZ[1]};
  }

  /**
   * Update the fusion data with the new observation and PDR data.
   * @param observation The new observation (LatLng).
   * @param observationCov The covariance matrix for the observation.
   * @param pdrData The PDR data (delta x and y).
   */
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


  /**
   * Reset the filter with the new position and PDR data.
   * @param newPos The new position (LatLng).
   * @param pdrData The PDR data (delta x and y).
   */
  private void resetFilter(LatLng newPos, float[] pdrData) {
    double[] newXY = latLngToXY(startLocation, newPos);

    double[] pdrData64 = {pdrData[0], pdrData[1]};

    // Get the current timestamp and update the filter
    double timestamp = (System.currentTimeMillis() - absoluteStartTime) / 1e3;
    if (!filter.reset(newXY, INIT_POS_COVARIANCE, pdrData64, timestamp)) {
      Log.w("SensorFusion", "Filter reset failed");
    }
    double[] fusedPos = filter.getPos();

    // Convert back to Lat-Long
    ProjCoordinate fusedCoord = coordinateTransformer.applyDisplacementAndConvert(
            startLocation.latitude, startLocation.longitude, fusedPos[0], fusedPos[1]);
    fusedLocation = new LatLng(fusedCoord.y, fusedCoord.x);

    SimpleMatrix fusedCov = filter.getCovariance();
    this.fusedError = (float) Math.sqrt(computeMSE(fusedCov));
  }

  /**
   * Function to add a tag to the trajectory recorder.
   * This function is called from the
   * {@link com.openpositioning.PositionMe.presentation.fragment.TrajectoryMapFragment}
   * when the user wants to add a tag to the trajectory.
   */
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

    this.setStartGNSSLatitude(startingPoint);
  }

  /**
   * Disables saving sensor values to the trajectory object.
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
   * Function to stop the SensorFusion class. Stops all listeners and processing classes.
   * This function is called from the {@link MainActivity} when the application is stopped.
   */
  @Override
  public void stop() {
    if (!saveRecording) {
      this.trajRecorder.stop();
    }
    this.pdrProcessing.stop();
  }

  /**
   * Function to start the SensorFusion class. Starts all listeners and processing classes.
   * This function is called from the {@link MainActivity} when the application is started.
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

  /**
   * Function to handle GNSS data received from the SensorHub. It processes the data and updates
   * the fused location.
   *
   * @param data GNSSLocationData object containing the GNSS data.
   */
  public void onSensorDataReceived(GNSSLocationData data) {
    if (this.fusedLocation == null) {
      this.fusedLocation = new LatLng(data.latitude, data.longitude);
    }

    float[] currPdrData = this.pdrProcessing.getPDRMovement();
    LatLng loc = new LatLng(data.latitude, data.longitude);
    this.gnssLoc = loc;

    // Get GNSS accuracy and variance
    float xAccuracy = data.accuracy;
    float xVariance = (float) Math.pow(xAccuracy, 2);

    // Update GNSS covariance matrix according to variance
    SimpleMatrix currentGnssCovariance = GNSS_COVARIANCE.copy();
    currentGnssCovariance.set(0, 0, xVariance);
    currentGnssCovariance.set(1, 1, xVariance);

    // Check if the coordinate transformer is initialized (i.e. starting location has been set)
    if (startLocation != null && currPdrData != null) {
      // Check if the GNSS location is an outlier
      this.isGnssOutlier = isOutlier(coordinateTransformer, fusedLocation, loc);
      // If GNSS update is not an outlier and the user is not in an elevator,
      // update the filter with the new GNSS location
      if (!this.isGnssOutlier && !inElevator) {
        updateFusionData(loc, currentGnssCovariance, currPdrData);
      }
      // If the user is in an elevator but has just left it, reset the filter with the new GNSS
      // update
      if (!this.isGnssOutlier && inElevator && !getElevator()) {  // Has just left an elevator
        resetFilter(loc, currPdrData);
        inElevator = false;
      }
    }
  }

  /**
   * Function to handle WiFi data received from the SensorHub. It processes the data and updates
   * the fused location.
   *
   * @param data WiFiData object containing the WiFi data.
   */
  public void onSensorDataReceived(WiFiData data) {
    if (this.fusedLocation == null && data.location != null) {
      this.fusedLocation = data.location;
    }
    // Detect and notify the user if there is no coverage
    if (!this.noWifiCoverage && data.location == null) {
      Toast.makeText(this.appContext, "No Wifi Coverage!",Toast.LENGTH_SHORT).show();
      this.noWifiCoverage = true;
    }
    // Handle WiFi scan result
    if (data.location != this.currentWifiLocation) {
      float[] pdrData = pdrProcessing.getPDRMovement();
      // Get the current location and floor estimates
      this.currentWifiLocation = data.location;
      this.currentWifiFloor = data.floor;

      // Ensure the coordinate transformer is initialized (i.e. starting location has been set)
      if (coordinateTransformer != null && this.currentWifiLocation != null) {
        this.noWifiCoverage = false;
        // Check if the WiFi location is an outlier
        this.isWifiLocationOutlier = isOutlier(coordinateTransformer,
            fusedLocation,
            this.currentWifiLocation);

        // If we have pdr updates, wifi location is not an outlier, and the user is not in an
        // elevator, update the filter with the new WiFi location
        if (startLocation != null && pdrData != null && !isWifiLocationOutlier && !inElevator) {
          updateFusionData(this.currentWifiLocation, WIFI_COVARIANCE, pdrData);
        }
        // If the user is in an elevator but has just left it, reset the filter with the new WiFi
        // location (if it is not an outlier)
        if (!this.isWifiLocationOutlier && inElevator && !getElevator() && pdrData != null) {  // Has just left an elevator
          resetFilter(this.currentWifiLocation, pdrData);
          inElevator = false;
        }
      }
    }
  }

  @Override
  public void onSensorDataReceived(SensorData data) {
    // Check if the data is from the GNSS sensor
    if (data instanceof GNSSLocationData gnssData) {
      // Handle GNSS location data
      onSensorDataReceived(gnssData);
    } else if (data instanceof WiFiData wiFiData) {
      // Handle WiFi scan result
      onSensorDataReceived(wiFiData);
    }
  }

  @Override
  public void update(Object[] objList) {
    // Update the trajectory with the PDR data
    if (objList != null && objList.length > 0) {
      // Convert update to PdrData instance from PDR processing
      PdrProcessing.PdrData pdrData = (PdrProcessing.PdrData) objList[0];
      if (pdrData.newPosition()) {
        // Update the path view with the new PDR data
        pathView.drawTrajectory(pdrData.position());
      }
      // If there is new elevator data,
      if(pdrData.newElevator()) {
        // Update the current elevator status
        this.elevator = pdrData.inElevator();
        // Debouncing - check that elevator is true for 8 consecutive updates before confirming
        this.elevatorTrueCount = (this.elevator) ? this.elevatorTrueCount + 1 : 0;
        if(!inElevator && pdrData.inElevator() && fusedLocation != null && elevatorTrueCount == 8) {
          this.inElevator = true;
          this.elevatorTrueCount = 0;

          // Get the closest elevator's LatLng using the NucleusBuildingManager helper
          // and set the fused location to it
          LatLng closestElevator = NucleusBuildingManager.getClosestElevatorLatLng(
                  fusedLocation, coordinateTransformer);
          if (closestElevator != null) {
            fusedLocation = closestElevator;
          }
        }
      }
    }
  }
}
