package com.openpositioning.PositionMe.data.local;

import android.content.Context;
import android.hardware.SensorManager;
import android.util.Log;

import android.widget.Toast;
import com.google.android.gms.maps.model.LatLng;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openpositioning.PositionMe.presentation.fragment.ReplayFragment;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.WiFiPositioning;
import com.openpositioning.PositionMe.sensors.filters.FilterAdapter;
import com.openpositioning.PositionMe.sensors.filters.KalmanFilterAdapter;
import com.openpositioning.PositionMe.utils.CoordinateTransformer;
import com.openpositioning.PositionMe.utils.TimedData;

import java.io.FileNotFoundException;
import java.io.IOException;
import org.ejml.simple.SimpleMatrix;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * Handles parsing of trajectory data stored in JSON files, combining IMU, PDR, and GNSS data to
 * reconstruct motion paths.
 *
 * <p>
 * The **TrajParser** is primarily responsible for processing recorded trajectory data and
 * reconstructing motion information, including estimated positions, GNSS coordinates, speed, and
 * orientation. It does this by reading a JSON file containing:
 * </p>
 * <ul>
 *     <li>IMU (Inertial Measurement Unit) data</li>
 *     <li>PDR (Pedestrian Dead Reckoning) position data</li>
 *     <li>GNSS (Global Navigation Satellite System) location data</li>
 * </ul>
 *
 * <p>
 * **Usage in Module 'PositionMe.app.main':**
 * </p>
 * <ul>
 *     <li>**ReplayFragment** - Calls `parseTrajectoryData()` to read recorded trajectory files and process movement.</li>
 *     <li>Stores parsed trajectory data as `ReplayPoint` objects.</li>
 *     <li>Provides data for updating map visualizations in `ReplayFragment`.</li>
 * </ul>
 *
 * @author Shu Gu
 * @author Lin Cheng
 * @see ReplayFragment which uses parsed trajectory data for visualization.
 * @see SensorFusion for motion processing and sensor integration.
 * @see com.openpositioning.PositionMe.presentation.fragment.ReplayFragment for implementation
 * details.
 */
public class TrajParser {

  private static final String TAG = "TrajParser";

  private static final String WIFI_FINGERPRINT = "wf";

  // To run sensor fusion on replays
  private static FilterAdapter filter;


  public interface TrajectoryParseCallback {

    void progress(int completed, int total);

    void onTrajectoryParsed(List<ReplayPoint> replayPoints);

    void onError(Exception e);
  }

  /**
   * Represents a single replay point containing estimated PDR position, GNSS location, orientation,
   * speed, and timestamp.
   */
  public static class ReplayPoint {

    public LatLng pdrLocation;  // PDR-derived location estimate
    public LatLng gnssLocation; // GNSS location (may be null if unavailable)
    public LatLng wifiLocation; // Wifi-Location
    public LatLng fusedLocation; // TODO: Haven't used it.
    public float orientation;   // Orientation in degrees
    public float speed;         // Speed in meters per second
    public long timestamp;      // Relative timestamp

    /**
     * Constructs a ReplayPoint.
     *
     * @param pdrLocation  The pedestrian dead reckoning (PDR) location.
     * @param gnssLocation The GNSS location, or null if unavailable.
     * @param orientation  The orientation angle in degrees.
     * @param speed        The speed in meters per second.
     * @param timestamp    The timestamp associated with this point.
     */
    public ReplayPoint(LatLng pdrLocation, LatLng gnssLocation,
        LatLng wifiLocation, LatLng fusedLocation,
        float orientation, float speed, long timestamp) {
      this.pdrLocation = pdrLocation;
      this.gnssLocation = gnssLocation;
      this.wifiLocation = wifiLocation;
      this.fusedLocation = fusedLocation;
      this.orientation = orientation;
      this.speed = speed;
      this.timestamp = timestamp;
    }
  }

  /**
   * Represents an IMU (Inertial Measurement Unit) data record used for orientation calculations.
   */
  private static class ImuRecord extends TimedData {

    public float accX, accY, accZ; // Accelerometer values
    public float gyrX, gyrY, gyrZ; // Gyroscope values
    public float rotationVectorX, rotationVectorY, rotationVectorZ, rotationVectorW; // Rotation quaternion
  }

  /**
   * Represents a Pedestrian Dead Reckoning (PDR) data record storing position shifts over time.
   */
  private static class PdrRecord extends TimedData {

    public float x, y; // Position relative to the starting point
  }

  /**
   * Represents a GNSS (Global Navigation Satellite System) data record with latitude/longitude.
   */
  private static class GnssRecord extends TimedData {

    public double latitude, longitude; // GNSS coordinates
    public double accuracy; // GNSS x-axis accuracy, 1 std. deviation.
  }

  private static class WifiLocation extends TimedData {

    public LatLng latLng; // Wifi Location coordinates
  }

  private static class WifiRecord extends TimedData {

    public WifiSample[] macScans;
  }

  private static class WifiSample extends TimedData {

    public float rssi; // Measured signal strength
    public long mac; // MAC Address

  }

  /**
   * Parses trajectory data from a JSON file and reconstructs a list of replay points.
   *
   * <p>
   * This method processes a trajectory log file, extracting IMU, PDR, and GNSS records, and uses
   * them to generate **ReplayPoint** objects. Each point contains:
   * </p>
   * <ul>
   *     <li>Estimated PDR-based position.</li>
   *     <li>GNSS location (if available).</li>
   *     <li>Computed orientation using rotation vectors.</li>
   *     <li>Speed estimation based on movement data.</li>
   * </ul>
   *
   * @param filePath Path to the JSON file containing trajectory data.
   * @param context  Android application context (used for sensor processing).
   */
  public static void parseTrajectoryData(String filePath,
      Context context,
      LatLng initialPos,
      boolean useGnssAsInitial,
      TrajectoryParseCallback callback) {
    List<ReplayPoint> result = new ArrayList<>();

    // First extract the trajectory as a JSON
    try {
      JsonObject root = extractTrajectoryJson(filePath, callback);

      Log.i(TAG, "Successfully read trajectory file: " + filePath);

      long startTimestamp = root.has("startTimestamp")
          ? root.get("startTimestamp").getAsLong() : 0;

      // Get the data from the trajectory
      List<ImuRecord> imuList = parseImuData(root.getAsJsonArray("imuData"));
      List<PdrRecord> pdrList = parsePdrData(root.getAsJsonArray("pdrData"));
      List<GnssRecord> gnssList = parseGnssData(root.getAsJsonArray("gnssData"));
      List<WifiRecord> wifiList = parseWifiData(root.getAsJsonArray("wifiData"));
      
      // Send out asynchronous callbacks to get locations from the wifi points.
      List<WifiLocation> wifiLocations = getLocationsForWifi(
          new WiFiPositioning(context),
          wifiList,
          true,
          callback);

      Log.i(TAG, "Parsed data - IMU: " + imuList.size() + " records, PDR: "
          + pdrList.size() + " records, GNSS: " + gnssList.size() + " records");

      LatLng initialPosition;
      if (!gnssList.isEmpty() && useGnssAsInitial) {
        initialPosition = new LatLng(gnssList.get(0).latitude, gnssList.get(0).longitude);
      } else {
        initialPosition = initialPos;
        if (useGnssAsInitial) {
          Toast.makeText(context, "No GNSS samples found. "
              + "Defaulting to user-set location", Toast.LENGTH_LONG).show();
        }
      }
      result = constructReplayPoints(context, initialPosition, pdrList, imuList,
          gnssList, startTimestamp, wifiLocations);

    } catch (Exception e) {
      Log.e(TAG, "Error parsing trajectory file!", e);
      callback.onError(e);
    }
//    Gson gson = new Gson();
//    gson.toJson(result);
    callback.onTrajectoryParsed(result);
  }

  private static JsonObject extractTrajectoryJson(String filePath, TrajectoryParseCallback callback)
      throws IOException {
    File file = new File(filePath);
    if (!file.exists()) {
      Log.e(TAG, "File does NOT exist: " + filePath);
      FileNotFoundException e = new FileNotFoundException("File " + filePath + "does not exist!");
      callback.onError(e);
    }
    if (!file.canRead()) {
      Log.e(TAG, "File is NOT readable: " + filePath);
      FileNotFoundException e = new FileNotFoundException("File " + filePath + "not readable!");
      callback.onError(e);
    }

    BufferedReader br = new BufferedReader(new FileReader(file));
    JsonObject root = new JsonParser().parse(br).getAsJsonObject();
    br.close();
    return root;
  }

  private static List<ReplayPoint> constructReplayPoints(Context context, LatLng initialPos,
      List<PdrRecord> pdrList, List<ImuRecord> imuList, List<GnssRecord> gnssList,
      long startTimestamp, List<WifiLocation> wifiLocations) {
    List<ReplayPoint> result = new ArrayList<>(pdrList.size());
    // Initialize Coordinate transformer based on initial positions
    CoordinateTransformer coordinateTransformer = new CoordinateTransformer(
        initialPos.latitude, initialPos.longitude);

    // Initialize Sensor Fusion and associated variables
    TrajParser.filter = new KalmanFilterAdapter(
        new double[] {0,0},
        SensorFusion.INIT_POS_COVARIANCE, 0.0, SensorFusion.PDR_COVARIANCE
    );

    LatLng fusedLocation = initialPos;
    LatLng previousWifiLoc = null;
    LatLng previousGnssLoc = null;

    // Now construct ReplayPoints for the trajectory replay
    for (int i = 0; i < pdrList.size(); i++) {
      PdrRecord pdr = pdrList.get(i);

      ImuRecord closestImu = TimedData.findClosestRecord(imuList, pdr.relativeTimestamp);

      float orientationDeg = closestImu != null ? computeOrientationFromRotationVector(
          closestImu.rotationVectorX,
          closestImu.rotationVectorY,
          closestImu.rotationVectorZ,
          closestImu.rotationVectorW,
          context
      ) : 0f;

      ProjCoordinate newPdrLoc = coordinateTransformer.applyDisplacementAndConvert(
          initialPos.latitude, initialPos.longitude,
          pdr.x, pdr.y);

      LatLng pdrLocation = new LatLng(newPdrLoc.y, newPdrLoc.x);

      GnssRecord closestGnss = TimedData.findClosestRecord(gnssList, pdr.relativeTimestamp);
      LatLng gnssLocation = closestGnss == null ? null :
          new LatLng(closestGnss.latitude, closestGnss.longitude);

      if (gnssLocation != null) {
        if (previousGnssLoc == null || !previousGnssLoc.equals(gnssLocation)) {
          // New GNSS observation. Run sensor fusion if not an outlier
//            SimpleMatrix currentGnssCovariance = SensorFusion.GNSS_COVARIANCE.copy();
//            currentGnssCovariance.set(0, 0, Math.pow(closestGnss.get().accuracy,2));
          if (!SensorFusion.isOutlier(coordinateTransformer, fusedLocation, gnssLocation)) {
            fusedLocation = updateFusionData(gnssLocation, coordinateTransformer,
                startTimestamp, initialPos, SensorFusion.GNSS_COVARIANCE,
                new float[]{pdr.x, pdr.y});
          }
          previousGnssLoc = gnssLocation;
        }
      }

      WifiLocation closestWifiLoc = TimedData.findClosestRecord(
          wifiLocations, pdr.relativeTimestamp);
      LatLng wifiLocation = closestWifiLoc == null ? null : closestWifiLoc.latLng;

      if (wifiLocation != null) {
        if (previousWifiLoc == null || !previousWifiLoc.equals(wifiLocation)) {
          // New GNSS observation. Run sensor fusion IF it is not an outlier
          if (!SensorFusion.isOutlier(coordinateTransformer, fusedLocation, wifiLocation)) {
            fusedLocation = updateFusionData(wifiLocation, coordinateTransformer,
                startTimestamp, initialPos, SensorFusion.WIFI_COVARIANCE,
                new float[]{pdr.x, pdr.y});
          }
          previousWifiLoc = wifiLocation;
        }
      }
      // TODO: Think about optionals rather than passing around nulls everywhere. What should
      // the application do when there is no point to plot?
      result.add(new ReplayPoint(pdrLocation, gnssLocation, wifiLocation, fusedLocation,
          orientationDeg, 0f, pdr.relativeTimestamp));
    }
    result.sort(Comparator.comparingLong(rp -> rp.timestamp));
    Log.i(TAG, "Final ReplayPoints count: " + result.size());
    return result;
  }

  private static LatLng updateFusionData(LatLng observation, CoordinateTransformer transformer,
      long startTime, LatLng initialPos, SimpleMatrix observationCov, float[] pdrData) {
    double[] pdrData64 = {pdrData[0], pdrData[1]};

    // Convert the WiFi location to XY
    ProjCoordinate startLocationNorthEast = transformer.convertWGS84ToTarget(
        initialPos.latitude, initialPos.longitude);

    ProjCoordinate observationNorthEast = transformer.convertWGS84ToTarget(
        observation.latitude, observation.longitude);

    double[] wifiXYZ = CoordinateTransformer.getRelativePosition(
        startLocationNorthEast,
        observationNorthEast
    );

    double[] wifiXY = {wifiXYZ[0], wifiXYZ[1]};

    // Get the current timestamp and update the filter
    double timestamp = (System.currentTimeMillis() - startTime) / 1e3;
    if (!filter.update(pdrData64, wifiXY, timestamp, observationCov)) {
      Log.w("SensorFusion", "Filter update failed");
    }
    double[] fusedPos = filter.getPos();

    // Convert back to Lat-Long
    ProjCoordinate fusedCoord = transformer.applyDisplacementAndConvert(
        initialPos.latitude, initialPos.longitude, fusedPos[0], fusedPos[1]);
    LatLng fusedLocation = new LatLng(fusedCoord.y, fusedCoord.x);

    SimpleMatrix fusedCov = filter.getCovariance();
//    float fusedError = (float) Math.sqrt(computeMSE(fusedCov));
    return fusedLocation;
  }


  /**
   * Send out asynchronous callbacks to retrieve the locations all WiFi fingerprints
   *  TODO: Check what happens when we either 1) don't have signal or 2) are an outlier
   *
   * @param wiFiPositioning
   * @param wifiRecords
   * @param wait            If the call should block until all wifi responses are received
   */
  private static List<WifiLocation> getLocationsForWifi(WiFiPositioning wiFiPositioning,
      List<WifiRecord> wifiRecords,
      boolean wait,
      TrajectoryParseCallback callback) {
    WifiLocation[] wifiLocationsWithNulls = new WifiLocation[wifiRecords.size()];
    // Initialize a countdownlatch that will wait for all responses to come back
    CountDownLatch wifiResponses = new CountDownLatch(wifiRecords.size());
    callback.progress(0, (int) wifiRecords.size());

    for (int i = 0; i < wifiRecords.size(); i++) {
      WifiRecord record = wifiRecords.get(i);
      try {
        // Creating a JSON object to store the WiFi access points
        JSONObject wifiAccessPoints = new JSONObject();
        for (WifiSample sample : record.macScans) {
          wifiAccessPoints.put(String.valueOf(sample.mac), sample.rssi);
        }
        // Creating POST Request
        JSONObject wifiFingerPrint = new JSONObject();
        wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
        //
        int index = i;
        WifiLocation location = new WifiLocation();
        location.relativeTimestamp = record.relativeTimestamp;
        wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
          @Override
          public void onSuccess(LatLng wifiLocation, int floor) {
            // Handle the success response
            location.latLng = wifiLocation;
            wifiLocationsWithNulls[index] = location;
            // Signal completion on wifiResponses (decrement the count)
            wifiResponses.countDown();
            callback.progress(wifiRecords.size() - (int) wifiResponses.getCount(),
                wifiRecords.size());
          }

          @Override
          public void onError(String message) {
            // Handle the error response
            Log.e("getLocationsForWifi", "ERROR IN SERVER RESPONSE FOR WIFI POSITIONING"
                + message);
            // Error is implicitly a completion; otherwise we risk stalling the UI
            wifiResponses.countDown();
            callback.progress(wifiRecords.size() - (int) wifiResponses.getCount(),
                wifiRecords.size());
          }
        });
      } catch (JSONException e) {
        // Catching error while making JSON object, to prevent crashes
        // Error log to keep record of errors (for secure programming and maintainability)
        Log.e("jsonErrors", "Error creating json object" + e.toString());
        // Error is implicitly a completion; otherwise we risk stalling the UI
        wifiResponses.countDown();
        callback.progress(wifiRecords.size() - (int) wifiResponses.getCount(), wifiRecords.size());
      }
    }
    if (wait) {
      try {
        callback.progress(wifiRecords.size() - (int) wifiResponses.getCount(), wifiRecords.size());
        wifiResponses.await(); // Wait for all responses to compelte
      } catch (InterruptedException e) {
        Log.e("getLocationsForWifi",
            "Interrupted whilst waiting for wifi responses: " + e.toString());
      }
    }
    List<WifiLocation> wifiLocations = new ArrayList<>();
    // Filter out the nulls into a new
    for (int i = 0; i < wifiLocationsWithNulls.length; i++) {
      if (wifiLocationsWithNulls[i] != null) {
        wifiLocations.add(wifiLocationsWithNulls[i]);
      }
    }
    return wifiLocations;
  }

  /**
   * Parses IMU data from JSON.
   */
  private static List<ImuRecord> parseImuData(JsonArray imuArray) {
    List<ImuRecord> imuList = new ArrayList<>();
    if (imuArray == null) {
      return imuList;
    }
    Gson gson = new Gson();
    for (int i = 0; i < imuArray.size(); i++) {
      ImuRecord record = gson.fromJson(imuArray.get(i), ImuRecord.class);
      imuList.add(record);
    }
    return imuList;
  }

  /**
   * Parses PDR data from JSON.
   */
  private static List<PdrRecord> parsePdrData(JsonArray pdrArray) {
    List<PdrRecord> pdrList = new ArrayList<>();
    if (pdrArray == null) {
      return pdrList;
    }
    Gson gson = new Gson();
    for (int i = 0; i < pdrArray.size(); i++) {
      PdrRecord record = gson.fromJson(pdrArray.get(i), PdrRecord.class);
      pdrList.add(record);
    }
    return pdrList;
  }

  /**
   * Parses GNSS data from JSON.
   */
  private static List<GnssRecord> parseGnssData(JsonArray gnssArray) {
    List<GnssRecord> gnssList = new ArrayList<>();
    if (gnssArray == null) {
      return gnssList;
    }
    Gson gson = new Gson();
    for (int i = 0; i < gnssArray.size(); i++) {
      GnssRecord record = gson.fromJson(gnssArray.get(i), GnssRecord.class);
      gnssList.add(record);
    }
    return gnssList;
  }

  /**
   * Parses WIFI data from JSON.
   */
  private static List<WifiRecord> parseWifiData(JsonArray wifiArray) {
    List<WifiRecord> wifiList = new ArrayList<>();
    if (wifiArray == null) {
      return wifiList;
    }
    Gson gson = new Gson();
    for (int i = 0; i < wifiArray.size(); i++) {
      WifiRecord record = gson.fromJson(wifiArray.get(i), WifiRecord.class);
      wifiList.add(record);
    }
    return wifiList;
  }

  /**
   * Computes the orientation from a rotation vector.
   */
  private static float computeOrientationFromRotationVector(float rx, float ry, float rz, float rw,
      Context context) {
    float[] rotationVector = new float[]{rx, ry, rz, rw};
    float[] rotationMatrix = new float[9];
    float[] orientationAngles = new float[3];

    SensorManager sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector);
    SensorManager.getOrientation(rotationMatrix, orientationAngles);

    float azimuthDeg = (float) Math.toDegrees(orientationAngles[0]);
    return azimuthDeg < 0 ? azimuthDeg + 360.0f : azimuthDeg;
  }
}