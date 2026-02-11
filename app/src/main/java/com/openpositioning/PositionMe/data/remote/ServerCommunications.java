package com.openpositioning.PositionMe.data.remote;

import static com.openpositioning.PositionMe.utils.UtilConstants.API_KEY_MASTER;
import static com.openpositioning.PositionMe.utils.UtilConstants.API_KEY_USER;
import static com.openpositioning.PositionMe.utils.UtilConstants.API_POST_TRAJECTORIES;
import static com.openpositioning.PositionMe.utils.UtilConstants.BUILDING_NAME_OUTSIDE;
import static com.openpositioning.PositionMe.utils.UtilConstants.FLOOR_PLAN_POLL_TIME_MS;
import static com.openpositioning.PositionMe.utils.UtilConstants.PROTOCOL_APP_JSON;
import static com.openpositioning.PositionMe.utils.UtilConstants.PROTOCOL_MULTIPART;
import static com.openpositioning.PositionMe.utils.UtilConstants.URL_API;
import static com.openpositioning.PositionMe.utils.UtilConstants.URL_GET_TRAJECTORIES_HEAD;
import static com.openpositioning.PositionMe.utils.UtilConstants.URL_GET_TRAJECTORIES_TAIL;
import static com.openpositioning.PositionMe.utils.UtilConstants.URL_POST_FLOORPLANS;

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

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.google.protobuf.util.JsonFormat;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.presentation.fragment.FilesFragment;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.presentation.fragment.RecordingFragment;
import com.openpositioning.PositionMe.sensors.Observable;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.sensors.Wifi;

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
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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

import org.json.JSONObject;

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

    private static final int OBSERVER_INDEX_FILES = 0;
    private static final int OBSERVER_INDEX_MAIN = 1;
    private static final int OBSERVER_INDEX_RECORDING = 2;
    private static final int TRAJECTORY_REQUEST_BUFFER = 10;

    private final Context context;

    // Network status checking
    private ConnectivityManager connMgr;
    private boolean isWifiConn;
    private boolean isMobileConn;
    private SharedPreferences settings;

    private String infoResponse;
    private boolean success;
    private List<Observer> observers;
    private long lastTime = 0;

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
     * @param trajectory Traj object matching all the timing and formal restrictions.
     * @param campaign   The building associated with the route
     */
    public void sendTrajectory(
        Traj.Trajectory trajectory,
        String campaign
    ){
        Toast.makeText(
            context,
        "Now uploading '" + campaign + "' trajectory...",
            Toast.LENGTH_SHORT
        ).show();
        logDataSize(trajectory);

        // Convert the trajectory to byte array
        byte[] binaryTrajectory = trajectory.toByteArray();

        File path = null;
        // For Android 13 or higher, use dedicated external storage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            path = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (path == null) {
                path = context.getFilesDir();
            }
        } else {
            // For Android 12 or lower, use internal storage
            path = context.getFilesDir();
        }

        Log.i("ServerCommunications", path.toString());

        // Format the file name according to date
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yy-HH-mm-ss");
        Date date = new Date();
        File file = new File(path, "trajectory_" + dateFormat.format(date) +  ".txt");

        try {
            // Write the binary data to the file
            FileOutputStream stream = new FileOutputStream(file);
            stream.write(binaryTrajectory);
            stream.close();
            Log.i(
            "ServerCommunications",
            "Recorded binary trajectory for debugging stored in: " + path
            );
        } catch (IOException e) {
            // Catch and print if writing to the file fails
            Log.e(
            "ServerCommunications",
            "Storing of recorded binary trajectory failed: " + e.getMessage()
            );
        }

        // Only proceed if campaign is valid
        if (campaign.isBlank() || campaign.equals(BUILDING_NAME_OUTSIDE)){
            String message = "Invalid campaign ('"+ campaign + "') - Cancelling upload";
            Log.w("ServerCommunications", message);
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            return;
        }

        // Check connections available before sending data
        checkNetworkStatus();

        // Check if user preference allows for syncing with mobile data
        // TODO: add sync delay and enforce settings
        boolean enableMobileData = this.settings.getBoolean("mobile_sync", false);
        // Check if device is connected to WiFi or to mobile data with enabled preference
        if (this.isWifiConn || (enableMobileData && isMobileConn)) {
            // Instantiate client for HTTP requests
            OkHttpClient client = new OkHttpClient();

            // Create a request body with a file to upload in multipart/form-data format
            RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(),
                        RequestBody.create(MediaType.parse("text/plain"), file))
                .build();

            // Create a POST request with the required headers
            String uploadURL = createUploadURL(campaign);
            Request request = new Request.Builder()
                .url(uploadURL)
                .post(requestBody)
                .addHeader("accept", PROTOCOL_APP_JSON)
                .addHeader("Content-Type", PROTOCOL_MULTIPART)
                .build();

            // Enqueue the request to be executed asynchronously and handle the response
            client.newCall(request).enqueue(new Callback() {

                // Handle failure to get response from the server
                @Override public void onFailure(Call call, IOException e) {
                    Log.e(
                    "ServerCommunications",
                    "Failure to get response: " + e.getMessage()
                    );
                    success = false;
                    notifyObservers(OBSERVER_INDEX_MAIN);
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
                        // If the response is unsuccessful, throw an exception
                        if (!response.isSuccessful()) {
                            // Show error message to users
                            String errorBody = responseBody.string();
                            infoResponse = "Upload failed: " + errorBody;
                            new Handler(Looper.getMainLooper()).post(() ->
                                Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show()
                            );

                            Log.e(
                            "ServerCommunications",
                            "POST error (Code " + response.code() + ")\n"
                                + errorBody
                            );
                            success = false;
                            notifyObservers(OBSERVER_INDEX_MAIN);
                            throw new IOException("Unexpected code " + response);
                        }

                        // Print the response headers
                        Headers responseHeaders = response.headers();
                        for (int i = 0, size = responseHeaders.size(); i < size; i++) {
                            Log.i(
                            "ServerCommunications",
                            responseHeaders.name(i) + ": " + responseHeaders.value(i)
                            );
                        }
                        // Print a confirmation of a successful POST to API
                        Log.i("ServerCommunications", "Successful post response");

                        Log.i("ServerCommunications", "Get file: " + file.getName());
                        String originalPath = file.getAbsolutePath();
                        Log.i(
                        "ServerCommunications",
                        "Original trajectory file saved at: " + originalPath
                        );

                        // Copy the file to the Downloads folder
                        File downloadsDir = Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS
                        );
                        File downloadFile = new File(downloadsDir, file.getName());
                        try {
                            copyFile(file, downloadFile);
                            Log.i(
                            "ServerCommunications",
                            "Trajectory file copied to Downloads: "
                                + downloadFile.getAbsolutePath()
                            );
                        } catch (IOException e) {
                            Log.e(
                            "ServerCommunications", 
                            "Failed to copy file to Downloads: " + e.getMessage()
                            );
                        }

                        // Delete local file and set success to true
                        success = file.delete();
                        notifyObservers(OBSERVER_INDEX_MAIN);
                    }
                }
            });
        }
        else {
            // If the device is not connected to network or allowed to send,
            // do not send trajectory and notify observers and user
            String message = "No uploading allowed right now!";
            Log.e("ServerCommunications", message);
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            );
            success = false;
            notifyObservers(OBSERVER_INDEX_MAIN);
        }
    }

    /**
     * Uploads a local trajectory file to the API server in the specified format.
     * {@link OkHttp} library is used for the asynchronous POST request.
     *
     * @param localTrajectory the File object of the local trajectory to be uploaded
     * @param campaign The building associated with the route
     */
    public void uploadLocalTrajectory(
        File localTrajectory,
        String campaign
    ) {
        // Only proceed if campaign is valid
        if (campaign.isBlank() || campaign.equals(BUILDING_NAME_OUTSIDE)){
            String message = "Invalid campaign ('"+ campaign + "') - Cancelling upload";
            Log.w("ServerCommunications", message);
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            return;
        }

        // Instantiate client for HTTP requests
        OkHttpClient client = new OkHttpClient();

        // robustness improvement
        RequestBody fileRequestBody;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                byte[] fileBytes = Files.readAllBytes(localTrajectory.toPath());
                fileRequestBody = RequestBody.create(
                    MediaType.parse("text/plain"),
                    fileBytes
                );
            } catch (IOException e) {
                Log.e("ServerCommunications", "Error: " + e.getMessage());
                // if failed, use File object to construct RequestBody
                fileRequestBody = RequestBody.create(
                    MediaType.parse("text/plain"),
                    localTrajectory
                );
            }
        } else {
            fileRequestBody = RequestBody.create(
                MediaType.parse("text/plain"),
                localTrajectory
            );
        }

        // Create request body with a file to upload in multipart/form-data format
        RequestBody requestBody = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", localTrajectory.getName(), fileRequestBody)
            .build();

        // Create a POST request with the required headers
        String uploadURL = createUploadURL(campaign);
        okhttp3.Request request = new okhttp3.Request.Builder()
            .url(uploadURL)
            .post(requestBody)
            .addHeader("accept", PROTOCOL_APP_JSON)
            .addHeader("Content-Type", PROTOCOL_MULTIPART)
            .build();

        // Enqueue the request to be executed asynchronously and handle the response
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // Set success to false and notify observers
                success = false;
                Log.e(
                "ServerCommunications", 
                "[" + e.getMessage() + "] UPLOAD: Failure to get response"
                );
                notifyObservers(OBSERVER_INDEX_MAIN);

                // Show error message to users
                infoResponse = "Upload failed: " + e.getMessage(); // Store error message
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show()
                );
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        success = false;
                        notifyObservers(OBSERVER_INDEX_MAIN);
                        assert responseBody != null;
                        String errorBody = responseBody.string();
                        Log.e("ServerCommunications", "UPLOAD unsuccessful: " + errorBody);
                        infoResponse = "Upload failed: " + errorBody;
                        new Handler(Looper.getMainLooper()).post(() ->
                                Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());
                        throw new IOException("UPLOAD failed with code " + response);
                    }

                    // Print the response headers
                    Headers responseHeaders = response.headers();
                    for (int i = 0, size = responseHeaders.size(); i < size; i++) {
                        Log.i(
                        "ServerCommunications",
                        responseHeaders.name(i) + ": " + responseHeaders.value(i)
                        );
                    }

                    // Print a confirmation of a successful POST to API
                    assert responseBody != null;
                    Log.i(
                    "ServerCommunications",
                    "UPLOAD SUCCESSFUL: " + responseBody.string()
                    );

                    // Delete local file, set success to true and notify observers
                    success = localTrajectory.delete();
                    notifyObservers(OBSERVER_INDEX_MAIN);
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
                        Log.e("ServerCommunications", 
                        "[" + e.getMessage() + "] Error loading record with key: " + key
                        );
                    }
                }

                Log.i(
                "ServerCommunications",
                "Loaded downloadRecords: " + downloadRecords
                );

            } catch (Exception e) {
                Log.e("ServerCommunications", "Error: " + e.getMessage());
            }
        } else {
            Log.i(
            "ServerCommunications",
            "Download_records.json not found in app-specific directory."
            );
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
    private void saveDownloadRecord(
        long startTimestamp,
        String fileName,
        String id,
        String dateSubmitted
    ) {
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
                    Log.e(
                    "ServerCommunications",
                    "Failed to create file: " + recordsFile.getAbsolutePath()
                    );
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

            Log.i(
            "ServerCommunications",
            "Download record saved successfully at: " + recordsFile.getAbsolutePath()
            );

        } catch (Exception e) {
            Log.e(
            "ServerCommunications",
            "Error saving download record: " + e.getMessage()
            );
        }
    }

    /**
     * Perform API request for downloading a Trajectory uploaded to the server. The trajectory is
     * retrieved from a zip file, with the method accepting a position argument specifying the
     * trajectory to be downloaded. The trajectory is then converted to a protobuf object and
     * then to a JSON string to be downloaded to the device's Downloads folder.
     *
     * @param position The position of the trajectory in the zip file to retrieve
     * @param id The ID of the trajectory
     * @param dateSubmitted The date the trajectory was submitted
     */
    public void downloadTrajectory(int position, String id, String dateSubmitted) {
        loadDownloadRecords();  // Load existing records from app-specific directory

        String url = URL_GET_TRAJECTORIES_HEAD
            + (position + TRAJECTORY_REQUEST_BUFFER)
            + URL_GET_TRAJECTORIES_TAIL;

        // Initialise OkHttp client
        OkHttpClient client = new OkHttpClient();

        // Create GET request with required header
        okhttp3.Request request = new okhttp3.Request.Builder()
            .url(url)
            .addHeader("accept", PROTOCOL_APP_JSON)
            .get()
            .build();

        // Enqueue the GET request for asynchronous execution
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("ServerCommunications", "GET request error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) throw new IOException(
                        "Upload Error (Error Code " + response.code() + ")"
                    );
                    Log.d("ServerCommunications", "Trajectories received.\n"
                    + "Now parsing for ID " + id + " (Position " + position + ")");

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
                    Log.d(
                    "ServerCommunications",
                    "byteArray = " + Arrays.toString(byteArray)
                    );
                    Traj.Trajectory receivedTrajectory = Traj.Trajectory.parseFrom(byteArray);

                    // Print a message in the console
                    long startTimestamp = receivedTrajectory.getStartTimestamp();
                    String fileName = "trajectory_" + dateSubmitted + ".txt";
                    Log.i("ServerCommunications", fileName + " received");

                    // Inspect the size of the received trajectory
                    logDataSize(receivedTrajectory);

                    // Place the file in your app-specific "Downloads" folder
                    File appSpecificDownloads = context.getExternalFilesDir(
                        Environment.DIRECTORY_DOWNLOADS
                    );
                    if (appSpecificDownloads != null && !appSpecificDownloads.exists()) {
                        Log.d("ServerCommunications", "Creating downloads directory...");
                        appSpecificDownloads.mkdirs();
                    }

                    File file = new File(appSpecificDownloads, fileName);
                    try (FileWriter fileWriter = new FileWriter(file)) {
                        String receivedTrajectoryString = JsonFormat.printer()
                            .print(receivedTrajectory);
                        fileWriter.write(receivedTrajectoryString);
                        fileWriter.flush();
                        Log.i(
                        "ServerCommunications",
                        "Received trajectory stored in: " + file.getAbsolutePath()
                        );

                        // Save the download record
                        saveDownloadRecord(startTimestamp, fileName, id, dateSubmitted);
                    } catch (IOException ee) {
                        Log.e(
                        "ServerCommunications",
                        "Trajectory download failed: " + ee.getMessage());
                    } finally {
                        // Close all streams and entries to release resources
                        zipInputStream.closeEntry();
                        byteArrayOutputStream.close();
                        zipInputStream.close();
                        inputStream.close();
                    }
                }
                // Refresh the list of download options
                loadDownloadRecords();
            }
        });
    }

    /**
     * API request for information about submitted trajectories. If the response is successful,
     * the {@link ServerCommunications#infoResponse} field is updated and observes notified.
     *
     */
    public void sendInfoRequest(String requestURL) {
        // Create a new OkHttpclient
        OkHttpClient client = new OkHttpClient();

        // Create GET info request with appropriate URL and header
        okhttp3.Request request = new okhttp3.Request.Builder()
            .url(requestURL)
            .addHeader("accept", PROTOCOL_APP_JSON)
            .get()
            .build();

        // Enqueue the GET request for asynchronous execution
        Toast.makeText(context, "Request sent", Toast.LENGTH_SHORT).show();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e("ServerCommunications", "Error: " + e.getMessage());
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    // Check if the response is successful
                    if (!response.isSuccessful()) throw new IOException(
                        "Upload Error (Error Code " + response.code() + ")"
                    );

                    // Get the requested information from the response body and save it in a string
                    infoResponse =  responseBody.string();
                    // Print a message in the console and notify observers
                    Log.i("ServerCommunications", "Response received; URL " + requestURL);
                    Log.i("ServerCommunications", infoResponse);
                    new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(
                            context,
                        "Response Received (Code " + response.code() + ")",
                            Toast.LENGTH_SHORT)
                        .show()
                    );
                    notifyObservers(OBSERVER_INDEX_FILES);
                }
            }
        });
    }

    /**
     * Retrieve floor plans for nearby buildings from server
     *
     * @param position Current position during recording
     * @param wifiAPs List of nearby wireless access points
     * */
    public void requestFloorplans(
        @NonNull LatLng position,
        @NonNull List<Wifi> wifiAPs
    ){
        // Simple delay window between floor plan API calls
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastTime < FLOOR_PLAN_POLL_TIME_MS){
            return;
        } else {
            lastTime = currentTime;
        }

        // Create a new OkHttpclient
        OkHttpClient client = new OkHttpClient();

        // Generate list of AP MAC addresses
        List<String> aps = new ArrayList<>();
        if (wifiAPs != null){
            for (Wifi ap : wifiAPs){
                aps.add(String.valueOf(ap.getBssid()));
            }
        }

        // Construct payload for request
        MediaType JSON = MediaType.get(PROTOCOL_APP_JSON + "; charset=utf-8");
        String payload = "{"
            + "\"lat\": " + position.latitude + ", "
            + "\"lon\": " + position.longitude + ", "
            + "\"macs\": " + aps
            + "}";

        RequestBody requestBody = RequestBody.create(payload, JSON);
        okhttp3.Request request = new okhttp3.Request.Builder()
            .url(URL_POST_FLOORPLANS)
            .post(requestBody)
            .addHeader("accept", PROTOCOL_APP_JSON)
            .addHeader("Content-Type", PROTOCOL_APP_JSON)
            .build();

        client.newCall(request).enqueue(new okhttp3.Callback() {

            @Override
            public void onResponse(
                @NonNull Call call,
                @NonNull Response response
            ) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        assert responseBody != null;
                        String errorBody = responseBody.string();
                        Log.e("ServerCommunications", "UPLOAD unsuccessful: " + errorBody);
                        infoResponse = "Upload Error (Error Code " + response.code() + ")";
                        new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());
                        throw new IOException(infoResponse);
                    }

                    // Print the response headers
                    Headers responseHeaders = response.headers();
                    for (int i = 0, size = responseHeaders.size(); i < size; i++) {
                        Log.d("ServerCommunications",
                            responseHeaders.name(i) + ": " + responseHeaders.value(i)
                        );
                    }

                    assert responseBody != null;
                    infoResponse = responseBody.string();
                    Log.i("ServerCommunications", "Floor plans received!");
                    notifyObservers(OBSERVER_INDEX_RECORDING);
                }
            }

            @Override
            public void onFailure(
                @NonNull Call call,
                @NonNull IOException e
            ) {
                Log.e("ServerCommunications", 
                    "[" + e.getMessage() + "] UPLOAD: Failure to get response"
                );

                // Show error message to users
                infoResponse = "Upload failed: " + e.getMessage();
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show()
                );
                notifyObservers(OBSERVER_INDEX_RECORDING);
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

    /**
     * Create the trajectory upload URL by inserting the building
     * the route is related to
     *
     * @param campaign The name of the building
     * */
    private String createUploadURL(String campaign){
        return URL_API + API_POST_TRAJECTORIES + "/"
            + campaign + "/" + API_KEY_USER
            + "/?key=" + API_KEY_MASTER;
    }

    private void logDataSize(Traj.Trajectory trajectory) {
        Log.i(
        "ServerCommunications",
        "IMU Data size: " + trajectory.getImuDataCount()
        );
        Log.i(
        "ServerCommunications",
        "Position Data size: " + trajectory.getMagnetometerDataCount()
        );
        Log.i(
        "ServerCommunications",
        "Pressure Data size: " + trajectory.getPressureDataCount()
        );
        Log.i(
        "ServerCommunications",
        "Light Data size: " + trajectory.getLightDataCount()
        );
        Log.i(
        "ServerCommunications",
        "GNSS Data size: " + trajectory.getGnssDataCount()
        );
        Log.i(
        "ServerCommunications",
        "WiFi Data size: " + trajectory.getWifiFingerprintsCount()
        );
        Log.i(
        "ServerCommunications",
        "APS Data size: " + trajectory.getApsDataCount()
        );
        Log.i(
        "ServerCommunications",
        "PDR Data size: " + trajectory.getPdrDataCount()
        );
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
        Log.i("ServerCommunications", "Added observer of type " + o.getClass());
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
            if(index == OBSERVER_INDEX_FILES && o instanceof FilesFragment) {
                o.update(new String[] {infoResponse});
            }
            else if (index == OBSERVER_INDEX_MAIN && o instanceof MainActivity) {
                o.update(new Boolean[] {success});
            }
            else if (index == OBSERVER_INDEX_RECORDING && o instanceof RecordingFragment) {
                o.update(new String[] {infoResponse});
            } else {
                Log.w("ServerCommunications", "No observer with index " + index);
            }
        }
    }
}