package com.openpositioning.PositionMe.data.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.google.protobuf.util.JsonFormat;
import com.openpositioning.PositionMe.BuildConfig;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.presentation.fragment.FilesFragment;
import com.openpositioning.PositionMe.sensors.Observable;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.utils.BuildingPolygon;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttp;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import com.openpositioning.PositionMe.Traj;


/**
 * This class handles communications with the server through HTTPs. The class uses an
 * {@link OkHttpClient} for making requests to the server. The class includes methods for sending
 * a recorded trajectory, uploading locally-stored trajectories, downloading trajectories from the
 * server and requesting information about the uploaded trajectories.
 *
 * Keys and URLs are hardcoded strings, given the simple and academic nature of the project.
 *
 * @author Michal Dvorak
 * @author Mate Stodulka
 */
public class ServerCommunications implements Observable {
  public static Map<String, JSONObject> downloadRecords = new HashMap<>();
  // Application context for handling permissions and devices
  private final Context context;

  // Network status checking
  private ConnectivityManager connMgr;
  private boolean isWifiConn;
  private boolean isMobileConn;
  private SharedPreferences settings;

  private String infoResponse;
  private boolean success;
  private List<Observer> observers;

  // Static constants necessary for communications
  private static final String userKey = BuildConfig.OPENPOSITIONING_API_KEY;
  private static final String masterKey = BuildConfig.OPENPOSITIONING_MASTER_KEY;
  private static final String DEFAULT_CAMPAIGN = "nucleus_building";
  private static final String downloadURL =
      "https://openpositioning.org/api/live/trajectory/download/" + userKey
          + "?skip=0&limit=30&key=" + masterKey;
  private static final String infoRequestURL =
      "https://openpositioning.org/api/live/users/trajectories/" + userKey
          + "?key=" + masterKey;
  private static final String PROTOCOL_CONTENT_TYPE = "multipart/form-data";
  private static final String PROTOCOL_ACCEPT_TYPE = "application/json";



  // Mapping from API building names to valid server campaign values.
  // Only "nucleus_building" and "murchison_house" are accepted by the server.
  private static final Map<String, String> BUILDING_TO_CAMPAIGN = new HashMap<>();
  static {
    BUILDING_TO_CAMPAIGN.put("nucleus_building", "nucleus_building");
    BUILDING_TO_CAMPAIGN.put("murchison_house", "murchison_house");
    // Library has no dedicated campaign on the server; mapped to default
    BUILDING_TO_CAMPAIGN.put("library", DEFAULT_CAMPAIGN);
  }

  /**
   * Maps an API building name to a valid server campaign value.
   *
   * @param buildingId the building name as returned by the floorplan API
   * @return a valid campaign string for the upload URL
   */
  private static String mapBuildingToCampaign(String buildingId) {
    if (buildingId == null) return DEFAULT_CAMPAIGN;
    String campaign = BUILDING_TO_CAMPAIGN.get(buildingId);
    return campaign != null ? campaign : DEFAULT_CAMPAIGN;
  }

  /**
   * Determines the campaign name based on the given latitude and longitude.
   * Uses {@link BuildingPolygon} to check which building the coordinates fall in.
   *
   * @param lat latitude of the trajectory start position
   * @param lon longitude of the trajectory start position
   * @return campaign name string, defaults to {@link #DEFAULT_CAMPAIGN} if no building matched
   */
  private static String determineCampaign(double lat, double lon) {
    LatLng point = new LatLng(lat, lon);
    if (BuildingPolygon.inNucleus(point)) {
      return "nucleus_building";
    } else if (BuildingPolygon.inMurchison(point)) {
      return "murchison_house";
    } else if (BuildingPolygon.inLibrary(point)) {
      return DEFAULT_CAMPAIGN;
    }
    return DEFAULT_CAMPAIGN;
  }

  /**
   * Builds the upload URL with the given campaign name.
   *
   * @param campaign the campaign name (e.g. "nucleus_building")
   * @return the full upload URL
   */
  private static String buildUploadURL(String campaign) {
    return "https://openpositioning.org/api/live/trajectory/upload/"
        + campaign + "/" + userKey + "/?key=" + masterKey;
  }

  /**
   * Public default constructor of {@link ServerCommunications}. The constructor saves context,
   * initialises a {@link ConnectivityManager}, {@link Observer} and gets the user preferences.
   * Boolean variables storing WiFi and Mobile Data connection status are initialised to false.
   *
   * @param context   application context for handling permissions and devices.
   */
  public ServerCommunications(Context context) {
    this.context = context;
    this.connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    this.settings = PreferenceManager.getDefaultSharedPreferences(context);
    this.isWifiConn = false;
    this.isMobileConn = false;
    checkNetworkStatus();

    this.observers = new ArrayList<>();
  }

  /**
   * Outgoing communication request with a {@link Traj trajectory} object. The recorded
   * trajectory is passed to the method. It is processed into the right format for sending
   * to the API server.
   *
   * @param trajectory          Traj object matching all the timing and formal restrictions.
   * @param selectedBuildingId  user-selected building name (e.g. "nucleus_building"), or null
   */
  public void sendTrajectory(Traj.Trajectory trajectory, String selectedBuildingId){

    // Determine campaign: prioritize user-selected building over coordinate-based detection
    String campaign;
    if (selectedBuildingId != null && !selectedBuildingId.isEmpty()) {
      campaign = mapBuildingToCampaign(selectedBuildingId);
    } else if (trajectory.hasInitialPosition()) {
      campaign = determineCampaign(
          trajectory.getInitialPosition().getLatitude(),
          trajectory.getInitialPosition().getLongitude());
    } else {
      campaign = DEFAULT_CAMPAIGN;
    }
    String uploadURL = buildUploadURL(campaign);

    Log.e("UPLOAD", "campaign=" + campaign);
    Log.e("UPLOAD", "uploadURL=" + uploadURL);
    Log.e("UPLOAD", "userKeyLen=" + (userKey == null ? -1 : userKey.length()));
    Log.e("UPLOAD", "masterKeyLen=" + (masterKey == null ? -1 : masterKey.length()));

    logDataSize(trajectory);

    // Convert the trajectory to byte array
    byte[] binaryTrajectory = trajectory.toByteArray();

    File path = null;
    // for android 13 or higher use dedicated external storage
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      path = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
      if (path == null) {
        path = context.getFilesDir();
      }
    } else { // for android 12 or lower use internal storage
      path = context.getFilesDir();
    }

    System.out.println(path.toString());

    // Format the file name according to date
    SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yy-HH-mm-ss");
    Date date = new Date();
    File file = new File(path, "trajectory_" + dateFormat.format(date) +  ".txt");

    try {
      // Write the binary data to the file
      FileOutputStream stream = new FileOutputStream(file);
      stream.write(binaryTrajectory);
      stream.close();
      System.out.println("Recorded binary trajectory for debugging stored in: " + path);
    } catch (IOException ee) {
      // Catch and print if writing to the file fails
      System.err.println("Storing of recorded binary trajectory failed: " + ee.getMessage());
    }

    // Check connections available before sending data
    checkNetworkStatus();

    // Check if user preference allows for syncing with mobile data
    boolean enableMobileData = this.settings.getBoolean("mobile_sync", false);
    // Check if device is connected to WiFi or to mobile data with enabled preference
    if(this.isWifiConn || (enableMobileData && isMobileConn)) {
      // Instantiate client for HTTP requests
      OkHttpClient client = new OkHttpClient();

      // Create a request body with a file to upload in multipart/form-data format
      RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
          .addFormDataPart("file", file.getName(),
              RequestBody.create(MediaType.parse("text/plain"), file))
          .build();

      // Create a POST request with the required headers
      Request request = new Request.Builder().url(uploadURL).post(requestBody)
          .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
          .addHeader("Content-Type", PROTOCOL_CONTENT_TYPE).build();

      // Enqueue the request to be executed asynchronously and handle the response
      client.newCall(request).enqueue(new Callback() {

        // Handle failure to get response from the server
        @Override public void onFailure(Call call, IOException e) {
          e.printStackTrace();
          System.err.println("Failure to get response");
          // Delete the local file and set success to false
          //file.delete();
          success = false;
          notifyObservers(1);
        }

        private void copyFile(File src, File dst) throws IOException {
          try (InputStream in = new FileInputStream(src);
                         OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) {
              out.write(buf, 0, len);
            }
          }
        }

        // Process the server's response
        @Override public void onResponse(Call call, Response response) throws IOException {
          try (ResponseBody responseBody = response.body()) {
            // If the response is unsuccessful, delete the local file and throw an
            // exception
            if (!response.isSuccessful()) {
              //file.delete();
//                            System.err.println("POST error response: " + responseBody.string());

              String errorBody = responseBody.string();
              infoResponse = "Upload failed: " + errorBody;
              new Handler(Looper.getMainLooper()).post(() ->
                  Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show()); // show error message to users

              System.err.println("POST error response: " + errorBody);
              success = false;
              notifyObservers(1);
              throw new IOException("Unexpected code " + response);
            }

            // Print the response headers
            Headers responseHeaders = response.headers();
            for (int i = 0, size = responseHeaders.size(); i < size; i++) {
              System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
            }
            // Print a confirmation of a successful POST to API
            System.out.println("Successful post response: " + responseBody.string());

            System.out.println("Get file: " + file.getName());
            String originalPath = file.getAbsolutePath();
            System.out.println("Original trajectory file saved at: " + originalPath);

            // Copy the file to the Downloads folder
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File downloadFile = new File(downloadsDir, file.getName());
            try {
              copyFile(file, downloadFile);
              System.out.println("Trajectory file copied to Downloads: " + downloadFile.getAbsolutePath());
            } catch (IOException e) {
              e.printStackTrace();
              System.err.println("Failed to copy file to Downloads: " + e.getMessage());
            }

            // Delete local file and set success to true
            success = file.delete();
            notifyObservers(1);
          }
          Log.e("UPLOAD", "code=" + response.code());
          Log.e("UPLOAD", "finalMethod=" + response.request().method());
          Log.e("UPLOAD", "finalUrl=" + response.request().url());

          Response pr = response.priorResponse();
          while (pr != null) {
            Log.e("UPLOAD", "redirect: " + pr.code() + " -> " + pr.header("Location"));
            pr = pr.priorResponse();
          }

        }
      });
    }
    else {
      // If the device is not connected to network or allowed to send, do not send trajectory
      // and notify observers and user
      System.err.println("No uploading allowed right now!");
      success = false;
      notifyObservers(1);
    }
  }

  /**
   * Uploads a local trajectory file to the API server in the specified format.
   * {@link OkHttp} library is used for the asynchronous POST request.
   *
   * @param localTrajectory the File object of the local trajectory to be uploaded
   */
  public void uploadLocalTrajectory(File localTrajectory) {

    // Parse protobuf to determine campaign from initial position
    String campaign = DEFAULT_CAMPAIGN;
    try {
      byte[] fileBytes = Files.readAllBytes(localTrajectory.toPath());
      Traj.Trajectory parsed = Traj.Trajectory.parseFrom(fileBytes);
      if (parsed.hasInitialPosition()) {
        campaign = determineCampaign(
            parsed.getInitialPosition().getLatitude(),
            parsed.getInitialPosition().getLongitude());
      }
    } catch (IOException e) {
      Log.w("UPLOAD", "Could not parse trajectory for campaign detection, using default", e);
    }
    String uploadURL = buildUploadURL(campaign);
    Log.e("UPLOAD", "Local upload campaign=" + campaign + " url=" + uploadURL);

    // Instantiate client for HTTP requests
    OkHttpClient client = new OkHttpClient();

    // robustness improvement
    RequestBody fileRequestBody;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      try {
        byte[] fileBytes = Files.readAllBytes(localTrajectory.toPath());
        fileRequestBody = RequestBody.create(MediaType.parse("text/plain"), fileBytes);
      } catch (IOException e) {
        e.printStackTrace();
        // if failed, use File object to construct RequestBody
        fileRequestBody = RequestBody.create(MediaType.parse("text/plain"), localTrajectory);
      }
    } else {
      fileRequestBody = RequestBody.create(MediaType.parse("text/plain"), localTrajectory);
    }

    // Create request body with a file to upload in multipart/form-data format
    RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("file", localTrajectory.getName(), fileRequestBody)
        .build();

    // Create a POST request with the required headers
    okhttp3.Request request = new okhttp3.Request.Builder().url(uploadURL).post(requestBody)
        .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
        .addHeader("Content-Type", PROTOCOL_CONTENT_TYPE).build();

    // Enqueue the request to be executed asynchronously and handle the response
    client.newCall(request).enqueue(new okhttp3.Callback() {
      @Override
      public void onFailure(Call call, IOException e) {
        // Print error message, set success to false and notify observers
        e.printStackTrace();
//                localTrajectory.delete();
        success = false;
        System.err.println("UPLOAD: Failure to get response");
        notifyObservers(1);
        infoResponse = "Upload failed: " + e.getMessage(); // Store error message
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show()); // show error message to users
      }

      @Override
      public void onResponse(Call call, Response response) throws IOException {
        try (ResponseBody responseBody = response.body()) {
          if (!response.isSuccessful()) {
            // Print error message, set success to false and throw an exception
            success = false;
//                        System.err.println("UPLOAD unsuccessful: " + responseBody.string());
            notifyObservers(1);
//                        localTrajectory.delete();
            assert responseBody != null;
            String errorBody = responseBody.string();
            System.err.println("UPLOAD unsuccessful: " + errorBody);
            infoResponse = "Upload failed: " + errorBody;
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());
            throw new IOException("UPLOAD failed with code " + response);
          }

          // Print the response headers
          Headers responseHeaders = response.headers();
          for (int i = 0, size = responseHeaders.size(); i < size; i++) {
            System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
          }

          // Print a confirmation of a successful POST to API
          assert responseBody != null;
          System.out.println("UPLOAD SUCCESSFUL: " + responseBody.string());

          // Delete local file, set success to true and notify observers
          success = localTrajectory.delete();
          notifyObservers(1);
        }
      }
    });
  }

  /**
   * Loads download records from a JSON file and updates the downloadRecords map.
   * If the file exists, it reads the JSON content and populates the map.
   */
  private void loadDownloadRecords() {
    // Point to the app-specific Downloads folder
    File recordsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
    File recordsFile = new File(recordsDir, "download_records.json");

    if (recordsFile.exists()) {
      try (BufferedReader reader = new BufferedReader(new FileReader(recordsFile))) {
        StringBuilder json = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
          json.append(line);
        }

        JSONObject jsonObject = new JSONObject(json.toString());
        for (Iterator<String> it = jsonObject.keys(); it.hasNext(); ) {
          String key = it.next();
          try {
            JSONObject record = jsonObject.getJSONObject(key);
            String id = record.getString("id");
            downloadRecords.put(id, record);
          } catch (Exception e) {
            System.err.println("Error loading record with key: " + key);
            e.printStackTrace();
          }
        }

        System.out.println("Loaded downloadRecords: " + downloadRecords);

      } catch (Exception e) {
        e.printStackTrace();
      }
    } else {
      System.out.println("Download_records.json not found in app-specific directory.");
    }
  }

  /**
   * Saves a download record to a JSON file.
   * The method creates or updates the JSON file with the provided details.
   *
   * @param startTimestamp the start timestamp of the trajectory
   * @param fileName the name of the file
   * @param id the ID of the trajectory
   * @param dateSubmitted the date the trajectory was submitted
   */
  private void saveDownloadRecord(long startTimestamp, String fileName, String id, String dateSubmitted) {
    File recordsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
    File recordsFile = new File(recordsDir, "download_records.json");
    JSONObject jsonObject;

    try {
      // Ensure the directory exists
      if (recordsDir != null && !recordsDir.exists()) {
        recordsDir.mkdirs();
      }

      // If the file does not exist, create it
      if (!recordsFile.exists()) {
        if (recordsFile.createNewFile()) {
          jsonObject = new JSONObject();
        } else {
          System.err.println("Failed to create file: " + recordsFile.getAbsolutePath());
          return;
        }
      } else {
        // Read the existing contents
        StringBuilder jsonBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(recordsFile))) {
          String line;
          while ((line = reader.readLine()) != null) {
            jsonBuilder.append(line);
          }
        }
        // If file is empty or invalid JSON, use a fresh JSONObject
        jsonObject = jsonBuilder.length() > 0
            ? new JSONObject(jsonBuilder.toString())
            : new JSONObject();
      }

      // Create the new record details
      JSONObject recordDetails = new JSONObject();
      recordDetails.put("file_name", fileName);
      recordDetails.put("startTimeStamp", startTimestamp);
      recordDetails.put("date_submitted", dateSubmitted);
      recordDetails.put("id", id);

      // Insert or update in the main JSON
      jsonObject.put(id, recordDetails);

      // Write updated JSON to file
      try (FileWriter writer = new FileWriter(recordsFile)) {
        writer.write(jsonObject.toString(4));
        writer.flush();
      }

      System.out.println("Download record saved successfully at: " + recordsFile.getAbsolutePath());

    } catch (Exception e) {
      e.printStackTrace();
      System.err.println("Error saving download record: " + e.getMessage());
    }
  }

  /**
   * Perform API request for downloading a Trajectory uploaded to the server. The trajectory is
   * retrieved from a zip file, with the method accepting a position argument specifying the
   * trajectory to be downloaded. The trajectory is then converted to a protobuf object and
   * then to a JSON string to be downloaded to the device's Downloads folder.
   *
   * @param position the position of the trajectory in the zip file to retrieve
   * @param id the ID of the trajectory
   * @param dateSubmitted the date the trajectory was submitted
   */
  public void downloadTrajectory(int position, String id, String dateSubmitted) {
    loadDownloadRecords();  // Load existing records from app-specific directory

    // Initialise OkHttp client
    OkHttpClient client = new OkHttpClient();

    // Create GET request with required header
    okhttp3.Request request = new okhttp3.Request.Builder()
        .url(downloadURL)
        .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
        .get()
        .build();

    // Enqueue the GET request for asynchronous execution
    client.newCall(request).enqueue(new okhttp3.Callback() {
      @Override
      public void onFailure(Call call, IOException e) {
        e.printStackTrace();
      }

      @Override
      public void onResponse(Call call, Response response) throws IOException {
        try (ResponseBody responseBody = response.body()) {
          if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

          // Extract the nth entry from the zip
          InputStream inputStream = responseBody.byteStream();
          ZipInputStream zipInputStream = new ZipInputStream(inputStream);

          java.util.zip.ZipEntry zipEntry;
          int zipCount = 0;
          while ((zipEntry = zipInputStream.getNextEntry()) != null) {
            if (zipCount == position) {
              // break if zip entry position matches the desired position
              break;
            }
            zipCount++;
          }

          // Initialise a byte array output stream
          ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

          // Read the zipped data and write it to the byte array output stream
          byte[] buffer = new byte[1024];
          int bytesRead;
          while ((bytesRead = zipInputStream.read(buffer)) != -1) {
            byteArrayOutputStream.write(buffer, 0, bytesRead);
          }


          // Convert the byte array to protobuf
          byte[] byteArray = byteArrayOutputStream.toByteArray();
          Traj.Trajectory receivedTrajectory = Traj.Trajectory.parseFrom(byteArray);

          // Inspect the size of the received trajectory
          logDataSize(receivedTrajectory);

          // Print a message in the console
          long startTimestamp = receivedTrajectory.getStartTimestamp();
          String fileName = "trajectory_" + dateSubmitted + ".txt";

          // Place the file in your app-specific "Downloads" folder
          File appSpecificDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
          if (appSpecificDownloads != null && !appSpecificDownloads.exists()) {
            appSpecificDownloads.mkdirs();
          }

          File file = new File(appSpecificDownloads, fileName);
          try (FileWriter fileWriter = new FileWriter(file)) {
            String receivedTrajectoryString = JsonFormat.printer().print(receivedTrajectory);
            fileWriter.write(receivedTrajectoryString);
            fileWriter.flush();
            System.err.println("Received trajectory stored in: " + file.getAbsolutePath());
          } catch (IOException ee) {
            System.err.println("Trajectory download failed");
          } finally {
            // Close all streams and entries to release resources
            zipInputStream.closeEntry();
            byteArrayOutputStream.close();
            zipInputStream.close();
            inputStream.close();
          }

          // Save the download record
          saveDownloadRecord(startTimestamp, fileName, id, dateSubmitted);
          loadDownloadRecords();
        }
      }
    });

  }

  /**
   * API request for information about submitted trajectories. If the response is successful,
   * the {@link ServerCommunications#infoResponse} field is updated and observes notified.
   *
   */
  public void sendInfoRequest() {
    // Create a new OkHttpclient
    OkHttpClient client = new OkHttpClient();

    // Create GET info request with appropriate URL and header
    okhttp3.Request request = new okhttp3.Request.Builder()
        .url(infoRequestURL)
        .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
        .get()
        .build();

    // Enqueue the GET request for asynchronous execution
    client.newCall(request).enqueue(new okhttp3.Callback() {
      @Override public void onFailure(Call call, IOException e) {
        e.printStackTrace();
      }

      @Override public void onResponse(Call call, Response response) throws IOException {
        try (ResponseBody responseBody = response.body()) {
          // Check if the response is successful
          if (!response.isSuccessful()) throw new IOException("Unexpected code " +
              response);

          // Get the requested information from the response body and save it in a string
          infoResponse =  responseBody.string();
          // Print a message in the console and notify observers
          System.out.println("Response received");
          notifyObservers(0);
        }
      }
    });
  }

  /**
   * This method checks the device's connection status. It sets boolean variables depending on
   * the type of active network connection.
   */
  private void checkNetworkStatus() {
    // Get active network information
    NetworkInfo activeInfo = connMgr.getActiveNetworkInfo();

    // Check for active connection and set flags accordingly
    if (activeInfo != null && activeInfo.isConnected()) {
      isWifiConn = activeInfo.getType() == ConnectivityManager.TYPE_WIFI;
      isMobileConn = activeInfo.getType() == ConnectivityManager.TYPE_MOBILE;
    } else {
      isWifiConn = false;
      isMobileConn = false;
    }
  }


  private void logDataSize(Traj.Trajectory trajectory) {
    String tag = "ServerCommunications";
    Log.i(tag, "IMU Data size: " + trajectory.getImuDataCount());
    Log.i(tag, "Magnetometer Data size: " + trajectory.getMagnetometerDataCount());
    Log.i(tag, "Pressure Data size: " + trajectory.getPressureDataCount());
    Log.i(tag, "Light Data size: " + trajectory.getLightDataCount());
    Log.i(tag, "Proximity Data size: " + trajectory.getProximityDataCount());
    Log.i(tag, "PDR Data size: " + trajectory.getPdrDataCount());
    Log.i(tag, "GNSS Data size: " + trajectory.getGnssDataCount());
    Log.i(tag, "WiFi fingerprints size: " + trajectory.getWifiFingerprintsCount());
    Log.i(tag, "APS Data size: " + trajectory.getApsDataCount());
    Log.i(tag, "WiFi RTT Data size: " + trajectory.getWifiRttDataCount());
    Log.i(tag, "BLE fingerprints size: " + trajectory.getBleFingerprintsCount());
    Log.i(tag, "BLE Data size: " + trajectory.getBleDataCount());
    Log.i(tag, "Test points size: " + trajectory.getTestPointsCount());
  }

  /**
   * {@inheritDoc}
   *
   * Implement default method from Observable Interface to add new observers to the list of
   * registered observers.
   *
   * @param o Classes which implement the Observer interface to receive updates from the class.
   */
  @Override
  public void registerObserver(Observer o) {
    this.observers.add(o);
  }

  /**
   * {@inheritDoc}
   *
   * Method for notifying all registered observers. The observer is notified based on the index
   * passed to the method.
   *
   * @param index Index for identifying the observer to be notified.
   */
  @Override
  public void notifyObservers(int index) {
    for(Observer o : observers) {
      if(index == 0 && o instanceof FilesFragment) {
        o.update(new String[] {infoResponse});
      }
      else if (index == 1 && o instanceof MainActivity) {
        o.update(new Boolean[] {success});
      }
    }
  }
}