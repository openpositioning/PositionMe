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

import com.google.protobuf.util.JsonFormat;
import com.openpositioning.PositionMe.BuildConfig;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.presentation.fragment.FilesFragment;
import com.openpositioning.PositionMe.sensors.Observable;
import com.openpositioning.PositionMe.sensors.Observer;

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
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Handles all network operations: uploading trajectories, downloading files,
 * and fetching user data from the OpenPositioning API.
 */
public class ServerCommunications implements Observable {

    public static Map<String, JSONObject> downloadRecords = new HashMap<>();
    private final Context context;
    private ConnectivityManager connMgr;
    private boolean isWifiConn;
    private boolean isMobileConn;
    private SharedPreferences settings;
    private String infoResponse;
    private boolean success;
    private List<Observer> observers;

    // API Keys and Endpoints
    private static final String userKey = BuildConfig.OPENPOSITIONING_API_KEY;
    private static final String masterKey = BuildConfig.OPENPOSITIONING_MASTER_KEY;
    private static final String BASE_UPLOAD_URL = "https://openpositioning.org/api/live/trajectory/upload/";
    private static final String downloadURL = "https://openpositioning.org/api/live/trajectory/download/" + userKey + "?skip=0&limit=30&key=" + masterKey;
    private static final String infoRequestURL = "https://openpositioning.org/api/live/users/trajectories/" + userKey + "?key=" + masterKey;
    private static final String PROTOCOL_CONTENT_TYPE = "multipart/form-data";
    private static final String PROTOCOL_ACCEPT_TYPE = "application/json";

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
     * Serializes the Trajectory object and uploads it to the server.
     * Uses trajectory_id as the filename for context awareness.
     */
    public void sendTrajectory(Traj.Trajectory trajectory){
        logDataSize(trajectory);

        // Convert the trajectory to byte array
        byte[] binaryTrajectory = trajectory.toByteArray();

        // Determine storage path based on Android version
        File path;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            path = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (path == null) path = context.getFilesDir();
        } else {
            path = context.getFilesDir();
        }

        // -----------------------------------------------------------
        // [Objective a] Filename Generation
        // Uses getTrajectoryId() as the unique filename (set in SensorFusion).
        // -----------------------------------------------------------
        String rawName = trajectory.getTrajectoryId();
        Log.d("ServerCommunications", "Received Trajectory ID: " + rawName);

        String fileName;
        if (rawName == null || rawName.isEmpty()) {
            // Fallback to timestamp if ID is missing (should not happen)
            SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yy-HH-mm-ss", java.util.Locale.getDefault());
            fileName = "trajectory_" + dateFormat.format(new Date());
            Log.e("ServerCommunications", "Trajectory ID was empty! Using fallback name.");
        } else {
            // Ensure .txt extension
            fileName = rawName.endsWith(".txt") ? rawName : rawName + ".txt";
        }

        File file = new File(path, fileName);
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(binaryTrajectory);
            System.out.println("Recorded binary trajectory stored in: " + file.getAbsolutePath());
        } catch (IOException ee) {
            System.err.println("Storing of recorded binary trajectory failed: " + ee.getMessage());
        }

        checkNetworkStatus();

        boolean enableMobileData = this.settings.getBoolean("mobile_sync", false);
        if(this.isWifiConn || (enableMobileData && isMobileConn)) {
            OkHttpClient client = new OkHttpClient();

            // Set campaign name and construct dynamic URL
            // Note: Trailing slash is maintained to prevent 405 errors
            String campaignName = "murchison_house";
            String dynamicUploadUrl = BASE_UPLOAD_URL + campaignName + "/" + userKey + "/?key=" + masterKey;

            Log.d("UPLOAD_DEBUG", "Correct Upload URL: " + dynamicUploadUrl);

            MediaType binaryType = MediaType.parse("application/octet-stream");

            RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.getName(),
                            RequestBody.create(binaryType, file))
                    .addFormDataPart("campaign", campaignName)
                    .build();

            Request request = new Request.Builder()
                    .url(dynamicUploadUrl)
                    .post(requestBody)
                    .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                    .addHeader("Content-Type", PROTOCOL_CONTENT_TYPE)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    e.printStackTrace();
                    success = false;
                    notifyObservers(1);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (ResponseBody responseBody = response.body()) {
                        if (!response.isSuccessful()) {
                            String errorBody = responseBody != null ? responseBody.string() : "Unknown Error";
                            infoResponse = "Upload failed: " + errorBody;
                            new Handler(Looper.getMainLooper()).post(() ->
                                    Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());
                            success = false;
                            notifyObservers(1);
                            throw new IOException("Unexpected code " + response);
                        }

                        System.out.println("Successful post response: " + responseBody.string());

                        // Copy file to Downloads folder for user access/debugging
                        copyToDownloads(file);

                        // Cleanup local internal file
                        success = file.delete();
                        notifyObservers(1);
                    }
                }
            });
        } else {
            System.err.println("No connection for uploading.");
            success = false;
            notifyObservers(1);
        }
    }

    /**
     * Copies a file to the public Downloads directory.
     */
    private void copyToDownloads(File src) {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dst = new File(downloadsDir, src.getName());
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            System.out.println("Copied to Downloads: " + dst.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Logs the size of data fields in the Trajectory object.
     * Updated for Proto v2 definition.
     */
    private void logDataSize(Traj.Trajectory trajectory) {
        Log.i("ServerCommunications", "IMU Data size: " + trajectory.getImuDataCount());
        Log.i("ServerCommunications", "Mag Data size: " + trajectory.getMagnetometerDataCount());
        Log.i("ServerCommunications", "Pressure Data size: " + trajectory.getPressureDataCount());
        Log.i("ServerCommunications", "Light Data size: " + trajectory.getLightDataCount());
        Log.i("ServerCommunications", "GNSS Data size: " + trajectory.getGnssDataCount());
        Log.i("ServerCommunications", "WiFi Fingerprints size: " + trajectory.getWifiFingerprintsCount());
        Log.i("ServerCommunications", "APS Data size: " + trajectory.getApsDataCount());
        Log.i("ServerCommunications", "PDR Data size: " + trajectory.getPdrDataCount());
    }

    private void loadDownloadRecords() {
        File recordsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File recordsFile = new File(recordsDir, "download_records.json");
        if (recordsFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(recordsFile))) {
                StringBuilder json = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) json.append(line);
                JSONObject jsonObject = new JSONObject(json.toString());
                for (Iterator<String> it = jsonObject.keys(); it.hasNext(); ) {
                    String key = it.next();
                    try {
                        JSONObject record = jsonObject.getJSONObject(key);
                        downloadRecords.put(record.getString("id"), record);
                    } catch (Exception e) { e.printStackTrace(); }
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    private void saveDownloadRecord(long startTimestamp, String fileName, String id, String dateSubmitted) {
        File recordsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File recordsFile = new File(recordsDir, "download_records.json");
        try {
            if (recordsDir != null && !recordsDir.exists()) recordsDir.mkdirs();
            if (!recordsFile.exists()) recordsFile.createNewFile();

            JSONObject jsonObject = new JSONObject();
            if (recordsFile.length() > 0) {
                StringBuilder jsonBuilder = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(recordsFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) jsonBuilder.append(line);
                }
                jsonObject = new JSONObject(jsonBuilder.toString());
            }

            JSONObject recordDetails = new JSONObject();
            recordDetails.put("file_name", fileName);
            recordDetails.put("startTimeStamp", startTimestamp);
            recordDetails.put("date_submitted", dateSubmitted);
            recordDetails.put("id", id);
            jsonObject.put(id, recordDetails);

            try (FileWriter writer = new FileWriter(recordsFile)) {
                writer.write(jsonObject.toString(4));
                writer.flush();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void downloadTrajectory(int position, String id, String dateSubmitted) {
        loadDownloadRecords();
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(downloadURL).addHeader("accept", PROTOCOL_ACCEPT_TYPE).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) { e.printStackTrace(); }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
                    InputStream inputStream = responseBody.byteStream();
                    ZipInputStream zipInputStream = new ZipInputStream(inputStream);
                    java.util.zip.ZipEntry zipEntry;
                    int zipCount = 0;
                    while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                        if (zipCount == position) break;
                        zipCount++;
                    }
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = zipInputStream.read(buffer)) != -1) byteArrayOutputStream.write(buffer, 0, bytesRead);

                    byte[] byteArray = byteArrayOutputStream.toByteArray();
                    Traj.Trajectory receivedTrajectory = Traj.Trajectory.parseFrom(byteArray);
                    logDataSize(receivedTrajectory);

                    File appDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (appDownloads != null) appDownloads.mkdirs();
                    File file = new File(appDownloads, "trajectory_" + dateSubmitted + ".txt");

                    try (FileWriter fileWriter = new FileWriter(file)) {
                        fileWriter.write(JsonFormat.printer().print(receivedTrajectory));
                        System.out.println("Downloaded: " + file.getAbsolutePath());
                    }
                    saveDownloadRecord(receivedTrajectory.getStartTimestamp(), file.getName(), id, dateSubmitted);
                    loadDownloadRecords();
                }
            }
        });
    }

    public void sendInfoRequest() {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(infoRequestURL).addHeader("accept", PROTOCOL_ACCEPT_TYPE).get().build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) { e.printStackTrace(); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
                    infoResponse = responseBody.string();
                    notifyObservers(0);
                }
            }
        });
    }

    private void checkNetworkStatus() {
        NetworkInfo activeInfo = connMgr.getActiveNetworkInfo();
        if (activeInfo != null && activeInfo.isConnected()) {
            isWifiConn = activeInfo.getType() == ConnectivityManager.TYPE_WIFI;
            isMobileConn = activeInfo.getType() == ConnectivityManager.TYPE_MOBILE;
        } else {
            isWifiConn = false;
            isMobileConn = false;
        }
    }

    @Override public void registerObserver(Observer o) { this.observers.add(o); }
    @Override public void notifyObservers(int index) {
        for(Observer o : observers) {
            if(index == 0 && o instanceof FilesFragment) o.update(new String[] {infoResponse});
            else if (index == 1 && o instanceof MainActivity) o.update(new Boolean[] {success});
        }
    }
    /**
     * Uploads a locally saved trajectory file.
     * Accessible from UploadFragment.
     */
    public void uploadLocalTrajectory(File localTrajectory) {
        if (localTrajectory.exists()) {
            uploadFile(localTrajectory);
        }
    }
    /**
     * Helper method to upload a file to the server.
     * Used by both automatic recording (sendTrajectory) and manual upload (uploadLocalTrajectory).
     */
    private void uploadFile(File file) {
        OkHttpClient client = new OkHttpClient();

        // Ensure the campaign name is correct
        String campaignName = "murchison_house";

        // Construct the URL (Maintain the trailing slash to avoid 405 errors)
        String dynamicUploadUrl = BASE_UPLOAD_URL + campaignName + "/" + userKey + "/?key=" + masterKey;

        MediaType binaryType = MediaType.parse("application/octet-stream");

        RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(), RequestBody.create(binaryType, file))
                .addFormDataPart("campaign", campaignName)
                .build();

        Request request = new Request.Builder()
                .url(dynamicUploadUrl)
                .post(requestBody)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                success = false;
                notifyObservers(1);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        String errorBody = responseBody != null ? responseBody.string() : "null";
                        infoResponse = "Upload failed: " + errorBody;
                        new Handler(Looper.getMainLooper()).post(() ->
                                Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());
                        success = false;
                        notifyObservers(1);
                        return;
                    }
                    System.out.println("UPLOAD SUCCESSFUL: " + (responseBody != null ? responseBody.string() : ""));

                    // Copy to downloads for visibility
                    copyToDownloads(file);

                    // Delete the local file after successful upload
                    success = file.delete();
                    notifyObservers(1);
                }
            }
        });
    }
}