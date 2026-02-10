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

import com.openpositioning.PositionMe.BuildConfig;
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
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import com.openpositioning.PositionMe.Traj;

/**
 * ServerCommunications (Key Sanitized Version)
 * 1. Auto-sanitize API Key and Master Key (remove < >)
 * 2. Fixed URL concatenation errors to ensure upload/download works
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

    // ============================================================
    // Core fix: Key sanitization logic
    // ============================================================

    // 1. Get raw Keys
    private static final String RAW_USER_KEY = BuildConfig.OPENPOSITIONING_API_KEY;
    private static final String RAW_MASTER_KEY = BuildConfig.OPENPOSITIONING_MASTER_KEY;

    // 2. Sanitize Keys (remove angle brackets and spaces)
    private static final String userKey = RAW_USER_KEY.replace("<", "").replace(">", "").trim();
    private static final String masterKey = RAW_MASTER_KEY.replace("<", "").replace(">", "").trim();

    // 3. Base upload URL (up to upload/, excluding campaign)
    private static final String BASE_UPLOAD_URL = "https://openpositioning.org/api/live/trajectory/upload/";

    private static final String downloadURL =
            "https://openpositioning.org/api/live/trajectory/download/" + userKey + "?skip=0&limit=30&key=" + masterKey;

    private static final String infoRequestURL =
            "https://openpositioning.org/api/live/users/trajectories/" + userKey + "?key=" + masterKey;

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
     * Send trajectory data (with crash protection)
     * @param trajectory Trajectory data
     * @param campaign Building name (e.g. "murchison_house"), empty string for no campaign
     */
    public void sendTrajectory(Traj.Trajectory trajectory, String campaign){
        // 1. URL construction - dynamically append campaign (upload to user root if empty)
        String dynamicUrl;
        if (campaign != null && !campaign.isEmpty()) {
            dynamicUrl = BASE_UPLOAD_URL + campaign + "/" + userKey + "/?key=" + masterKey;
        } else {
            String defaultCampaign = "murchison_house"; // <--- Enter your default building name here
            dynamicUrl = BASE_UPLOAD_URL + defaultCampaign + "/" + userKey + "/?key=" + masterKey;
        }

        Log.e("SERVER_DEBUG", "--------------------------------------------------");
        Log.e("SERVER_DEBUG", "Campaign Passed: " + campaign);
        Log.e("SERVER_DEBUG", "Dynamic Upload URL: " + dynamicUrl);
        Log.e("SERVER_DEBUG", "--------------------------------------------------");

        // Crash protection: wrap all dangerous operations
        try {
            logDataSize(trajectory);

            // Convert the trajectory to byte array
            byte[] binaryTrajectory = trajectory.toByteArray();
            Log.e("SERVER_DEBUG", "Trajectory Byte Size: " + binaryTrajectory.length);

            // Critical section 2: file path retrieval
            File path = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                path = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            }
            // Fallback: if Documents unavailable or version low, use internal storage
            if (path == null) {
                path = context.getFilesDir();
            }

            if (path == null) {
                throw new IOException("Fatal: Could not determine any file storage path.");
            }

            System.out.println(path.toString());

        // Format the file name according to date AND user input name
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yy-HH-mm-ss");
        Date date = new Date();

        String safeName = trajectory.getTrajectoryId();
        if (safeName == null || safeName.isEmpty()) {
            safeName = "trajectory";
        }

            // File name format: traj_<name>_<date>.txt
            String fileName = "traj_" + safeName + "_" + dateFormat.format(date) + ".txt";

            File file = new File(path, fileName);
            Log.e("SERVER_DEBUG", "Saving temp file to: " + file.getAbsolutePath());

            // Critical section 3: file writing
            FileOutputStream stream = new FileOutputStream(file);
            stream.write(binaryTrajectory);
            stream.close();
            System.out.println("Recorded binary trajectory stored in: " + path + "/" + fileName);

            checkNetworkStatus();

            boolean enableMobileData = this.settings.getBoolean("mobile_sync", false);
            if(this.isWifiConn || (enableMobileData && isMobileConn)) {
                
                // Log detailed trajectory information before upload
                Log.e("SERVER_DEBUG", "==================== UPLOAD REQUEST ====================");
                Log.e("SERVER_DEBUG", "File: " + file.getName());
                Log.e("SERVER_DEBUG", "File Size: " + file.length() + " bytes");
                Log.e("SERVER_DEBUG", "Campaign: " + (campaign != null && !campaign.isEmpty() ? campaign : "[empty - user directory]"));
                Log.e("SERVER_DEBUG", "URL: " + dynamicUrl);
                Log.e("SERVER_DEBUG", "---- Trajectory Data Statistics ----");
                Log.e("SERVER_DEBUG", "IMU Data count: " + trajectory.getImuDataCount());
                Log.e("SERVER_DEBUG", "Magnetometer Data count: " + trajectory.getMagnetometerDataCount());
                Log.e("SERVER_DEBUG", "Pressure Data count: " + trajectory.getPressureDataCount());
                Log.e("SERVER_DEBUG", "GNSS Data count: " + trajectory.getGnssDataCount());
                Log.e("SERVER_DEBUG", "WiFi Fingerprints count: " + trajectory.getWifiFingerprintsCount());
                Log.e("SERVER_DEBUG", "BLE Data count: " + trajectory.getBleDataCount());
                Log.e("SERVER_DEBUG", "PDR Data count: " + trajectory.getPdrDataCount());
                Log.e("SERVER_DEBUG", "Test Points count: " + trajectory.getTestPointsCount());
                Log.e("SERVER_DEBUG", "========================================================");

                OkHttpClient client = new OkHttpClient.Builder()
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build();

                // Use application/octet-stream
                RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("file", file.getName(),
                                RequestBody.create(MediaType.parse("application/octet-stream"), file))
                        .build();

                Request request = new Request.Builder()
                        .url(dynamicUrl)
                        .post(requestBody)
                        .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                        .build();

                client.newCall(request).enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException e) {
                        e.printStackTrace();
                        Log.e("SERVER_DEBUG", "==================== NETWORK FAILURE ====================");
                        Log.e("SERVER_DEBUG", "Exception Type: " + e.getClass().getSimpleName());
                        Log.e("SERVER_DEBUG", "Error Message: " + e.getMessage());
                        Log.e("SERVER_DEBUG", "Stack Trace:");
                        for (StackTraceElement element : e.getStackTrace()) {
                            Log.e("SERVER_DEBUG", "    " + element.toString());
                        }
                        Log.e("SERVER_DEBUG", "========================================================");
                        success = false;
                        notifyObservers(1);
                    }

                    @Override public void onResponse(Call call, Response response) throws IOException {
                        try (ResponseBody responseBody = response.body()) {
                            Log.e("SERVER_DEBUG", ">>> Response Code: " + response.code());
                            Log.e("SERVER_DEBUG", ">>> Response Message: " + response.message());
                            
                            // Log response headers
                            Log.e("SERVER_DEBUG", ">>> Response Headers:");
                            for (String headerName : response.headers().names()) {
                                Log.e("SERVER_DEBUG", "    " + headerName + ": " + response.headers().get(headerName));
                            }

                            if (!response.isSuccessful()) {
                                String errorBody = responseBody.string();
                                infoResponse = "Upload failed (" + response.code() + "): " + errorBody;
                                
                                // Enhanced error logging - split long messages to avoid truncation
                                Log.e("SERVER_DEBUG", "==================== UPLOAD FAILED ====================");
                                Log.e("SERVER_DEBUG", "Response Code: " + response.code());
                                Log.e("SERVER_DEBUG", "Response Message: " + response.message());
                                Log.e("SERVER_DEBUG", "Error Body Length: " + errorBody.length() + " characters");
                                Log.e("SERVER_DEBUG", "----------- ERROR BODY START -----------");
                                
                                // Split error body into chunks to avoid logcat truncation (max ~4000 chars per log)
                                int chunkSize = 3000;
                                for (int i = 0; i < errorBody.length(); i += chunkSize) {
                                    int end = Math.min(errorBody.length(), i + chunkSize);
                                    String chunk = errorBody.substring(i, end);
                                    Log.e("SERVER_DEBUG", "ERROR CHUNK [" + (i/chunkSize + 1) + "]: " + chunk);
                                }
                                Log.e("SERVER_DEBUG", "------------ ERROR BODY END ------------");
                                Log.e("SERVER_DEBUG", "======================================================");
                                
                                new Handler(Looper.getMainLooper()).post(() ->
                                        Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());
                                success = false;
                                notifyObservers(1);
                                return;
                            }

                            // Success
                            System.out.println("Successful post response: " + responseBody.string());
                            Log.d("SERVER_DEBUG", "UPLOAD SUCCESS!");

                            // Success
                            System.out.println("Successful post response: " + responseBody.string());
                            Log.d("SERVER_DEBUG", "UPLOAD SUCCESS!");

                            // Copy to Downloads
                            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                            File downloadFile = new File(downloadsDir, file.getName());
                            try {
                                copyFile(file, downloadFile);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }

                            success = file.delete();
                            notifyObservers(1);
                        }
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
                });
            } else {
                Log.e("SERVER_DEBUG", "No Network Connection available for upload.");
                success = false;
                notifyObservers(1);
            }

        } catch (Exception e) {
            // Catch all exceptions to prevent crash
            Log.e("SERVER_DEBUG", "CRITICAL ERROR during sendTrajectory: ", e);
            e.printStackTrace();

            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    public void uploadLocalTrajectory(File localTrajectory, String campaign) {
        String dynamicUrl;
        if (campaign != null && !campaign.isEmpty()) {
            dynamicUrl = BASE_UPLOAD_URL + campaign + "/" + userKey + "/?key=" + masterKey;
        } else {
            // Empty campaign: upload directly to user directory
            dynamicUrl = BASE_UPLOAD_URL + userKey + "/?key=" + masterKey;
        }

        Log.e("SERVER_DEBUG", "Local Upload URL: " + dynamicUrl);

        OkHttpClient client = new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();

        RequestBody fileRequestBody;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                byte[] fileBytes = Files.readAllBytes(localTrajectory.toPath());
                fileRequestBody = RequestBody.create(MediaType.parse("application/octet-stream"), fileBytes);
            } catch (IOException e) {
                e.printStackTrace();
                fileRequestBody = RequestBody.create(MediaType.parse("application/octet-stream"), localTrajectory);
            }
        } else {
            fileRequestBody = RequestBody.create(MediaType.parse("application/octet-stream"), localTrajectory);
        }

        RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", localTrajectory.getName(), fileRequestBody)
                .build();

        Request request = new Request.Builder()
                .url(dynamicUrl)
                .post(requestBody)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                success = false;
                notifyObservers(1);
                infoResponse = "Upload failed: " + e.getMessage();
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        success = false;
                        notifyObservers(1);
                        String errorBody = responseBody.string();
                        infoResponse = "Upload failed: " + errorBody;
                        new Handler(Looper.getMainLooper()).post(() ->
                                Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());
                        return;
                    }
                    success = localTrajectory.delete();
                    notifyObservers(1);
                }
            }
        });
    }

    private void loadDownloadRecords() {
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
                    } catch (Exception e) {}
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void saveDownloadRecord(long startTimestamp, String fileName, String id, String dateSubmitted) {
        File recordsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File recordsFile = new File(recordsDir, "download_records.json");
        JSONObject jsonObject;
        try {
            if (recordsDir != null && !recordsDir.exists()) recordsDir.mkdirs();
            if (!recordsFile.exists()) {
                if (recordsFile.createNewFile()) jsonObject = new JSONObject();
                else return;
            } else {
                StringBuilder jsonBuilder = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(recordsFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) jsonBuilder.append(line);
                }
                jsonObject = jsonBuilder.length() > 0 ? new JSONObject(jsonBuilder.toString()) : new JSONObject();
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
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void downloadTrajectory(int position, String id, String dateSubmitted) {
        loadDownloadRecords();
        OkHttpClient client = new OkHttpClient();
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(downloadURL)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .get()
                .build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { e.printStackTrace(); }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

                    // 1. Unzip to get data stream
                    InputStream inputStream = responseBody.byteStream();
                    ZipInputStream zipInputStream = new ZipInputStream(inputStream);
                    java.util.zip.ZipEntry zipEntry;
                    int zipCount = 0;
                    while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                        if (zipCount == position) break;
                        zipCount++;
                    }

                    // 2. Read binary data into memory (byte array)
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = zipInputStream.read(buffer)) != -1) {
                        byteArrayOutputStream.write(buffer, 0, bytesRead);
                    }
                    byte[] byteArray = byteArrayOutputStream.toByteArray();

                    // 3. Parse to get start timestamp
                    Traj.Trajectory receivedTrajectory = Traj.Trajectory.parseFrom(byteArray);
                    long startTimestamp = receivedTrajectory.getStartTimestamp();

                    // ==========================================
                    // ✅ Core Fix: Save as .protobuf binary file
                    // ==========================================
                    String fileName = "trajectory_" + dateSubmitted + ".protobuf"; // Changed suffix

                    File appSpecificDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (appSpecificDownloads != null && !appSpecificDownloads.exists()) {
                        appSpecificDownloads.mkdirs();
                    }

                    File file = new File(appSpecificDownloads, fileName);

                    // ⚠️ Key Point: Use FileOutputStream to write bytes directly, do not convert to JSON!
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        fos.write(byteArray);
                        fos.flush();
                    } catch (IOException ee) {
                        System.err.println("Trajectory download failed");
                        ee.printStackTrace();
                    } finally {
                        zipInputStream.closeEntry();
                        byteArrayOutputStream.close();
                        zipInputStream.close();
                        inputStream.close();
                    }

                    // 4. 保存记录
                    saveDownloadRecord(startTimestamp, fileName, id, dateSubmitted);
                    loadDownloadRecords();
                }
            }
        });
    }

    public void sendInfoRequest() {
        OkHttpClient client = new OkHttpClient();
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(infoRequestURL)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .get()
                .build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { e.printStackTrace(); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
                    infoResponse =  responseBody.string();
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

    private void logDataSize(Traj.Trajectory trajectory) {
        Log.i("ServerCommunications", "========== TRAJECTORY DATA SIZE ==========");
        Log.i("ServerCommunications", "IMU Data size: " + trajectory.getImuDataCount());
        Log.i("ServerCommunications", "Magnetometer Data size: " + trajectory.getMagnetometerDataCount());
        Log.i("ServerCommunications", "Pressure Data size: " + trajectory.getPressureDataCount());
        Log.i("ServerCommunications", "Light Data size: " + trajectory.getLightDataCount());
        Log.i("ServerCommunications", "Proximity Data size: " + trajectory.getProximityDataCount());
        
        // Highlight critical trajectory data
        int gnssCount = trajectory.getGnssDataCount();
        int pdrCount = trajectory.getPdrDataCount();
        
        if (gnssCount > 0) {
            Log.i("ServerCommunications", "✓ GNSS Data size: " + gnssCount + " (OK)");
        } else {
            Log.e("ServerCommunications", "✗ GNSS Data size: 0 (NO TRAJECTORY!)");
        }
        
        if (pdrCount > 0) {
            Log.i("ServerCommunications", "✓ PDR Data size: " + pdrCount + " (OK)");
        } else {
            Log.w("ServerCommunications", "⚠ PDR Data size: 0 (No PDR)");
        }
        
        Log.i("ServerCommunications", "WiFi Fingerprints size: " + trajectory.getWifiFingerprintsCount());
        Log.i("ServerCommunications", "BLE Data size: " + trajectory.getBleDataCount());
        Log.i("ServerCommunications", "APS Data size: " + trajectory.getApsDataCount());
        Log.i("ServerCommunications", "Test Points size: " + trajectory.getTestPointsCount());
        Log.i("ServerCommunications", "==========================================");
    }

    @Override
    public void registerObserver(Observer o) {
        this.observers.add(o);
    }

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