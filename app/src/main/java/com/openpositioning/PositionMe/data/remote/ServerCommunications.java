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
import com.openpositioning.PositionMe.Traj;
import com.google.protobuf.util.JsonFormat;
import com.openpositioning.PositionMe.BuildConfig;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.presentation.fragment.FilesFragment;
import com.openpositioning.PositionMe.sensors.Observable;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.sensors.SensorFusion;

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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.Collections;
import java.util.Comparator;

/**
 * Handles communications with the server through HTTPs.
 */
public class ServerCommunications implements Observable {

    private static final String TAG = "ServerDebug";
    // verify ServerCommunications is loaded (appears even without upload)
    static {
        Log.d(TAG, "[DBG] ServerCommunications loaded");
    }


    public static Map<String, JSONObject> downloadRecords = new HashMap<>();
    private final Context context;
    private Traj.Trajectory trajectory;
    private ConnectivityManager connMgr;
    private boolean isWifiConn;
    private boolean isMobileConn;
    private SharedPreferences settings;

    private String infoResponse;
    private boolean success;
    private List<Observer> observers;

    private static final String userKey = "LY31NlnGAe9vN-HvQJWTZg";
    private static final String masterKey = "ewireless";

    private static final String uploadURL =
            "https://openpositioning.org/api/live/trajectory/upload/" + userKey
                    + "/?key=" + masterKey;
    private static final String downloadURL =
            "https://openpositioning.org/api/live/trajectory/download/" + userKey
                    + "?skip=0&limit=30&key=" + masterKey;
    private static final String infoRequestURL =
            "https://openpositioning.org/api/live/users/trajectories/" + userKey
                    + "?key=" + masterKey;

    private static final String floorPlanRequestURL =
            "https://openpositioning.org/api/live/floorplan/request/" + userKey
                    + "?key=" + masterKey;

    private static final String PROTOCOL_CONTENT_TYPE = "multipart/form-data";
    private static final String PROTOCOL_ACCEPT_TYPE = "application/json";

    public ServerCommunications(Context context) {
        this.context = context;
        this.connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.observers = new ArrayList<>();
    }

    public void sendInfo(Traj.Trajectory trajectory) {
        this.trajectory = trajectory;

        Log.d(TAG, "[DBG] sendInfo() called");
        Log.i(TAG, "IMU Data size: " + trajectory.getImuDataCount());
        Log.i(TAG, "Light Data size: " + trajectory.getLightDataCount());
        Log.i(TAG, "GNSS Data size: " + trajectory.getGnssDataCount());
        Log.i(TAG, "WiFi Data size: " + trajectory.getWifiFingerprintsCount());
        Log.i(TAG, "APS Data size: " + trajectory.getApsDataCount());
        Log.i(TAG, "PDR Data size: " + trajectory.getPdrDataCount());
        Log.i(TAG, "Mag Data size: " + trajectory.getMagnetometerDataCount());
    }

    /**
     * Uploads trajectory to server with specified campaign.
     */
    public void sendTrajectory(Traj.Trajectory sentTrajectory, String campaign) {
        Log.e(TAG, "sendTrajectory called from: "
                + android.util.Log.getStackTraceString(new Throwable()).split("\n")[2]);

        if (campaign == null || campaign.isEmpty()) {
            campaign = "murchison_house";
        }

        String dynamicUrl = "https://openpositioning.org/api/live/trajectory/upload/" + campaign + "/" + userKey + "/?key=" + masterKey;

        // Confirm sendTrajectory is invoked and log upload URL
        Log.e(TAG, "new >>> ENTER sendTrajectory <<< campaign=" + campaign + " url=" + dynamicUrl);
        // Added: log key field counts before upload (diagnose empty wifi_fingerprints)
        Log.d(TAG, "[DBG] sendTrajectory counts:"
                + " imu=" + sentTrajectory.getImuDataCount()
                + " wifi=" + sentTrajectory.getWifiFingerprintsCount()
                + " pdr=" + sentTrajectory.getPdrDataCount()
                + " test_points=" + sentTrajectory.getTestPointsCount());

        // Verify PDR duration on the trajectory being uploaded
        try {
            int pdrCount = sentTrajectory.getPdrDataCount();
            if (pdrCount > 1) {
                long t0 = sentTrajectory.getPdrData(0).getRelativeTimestamp();
                long t1 = sentTrajectory.getPdrData(pdrCount - 1).getRelativeTimestamp();
                double dtSec = (t1 - t0) / 1000.0;
                Log.e(TAG, "UPLOAD PDR duration: " + dtSec + "s (t0=" + t0 + ", t1=" + t1 + ") count=" + pdrCount);
            } else {
                Log.e(TAG, "UPLOAD PDR duration: insufficient pdr_data count=" + pdrCount);
            }
        } catch (Exception e) {
            Log.e(TAG, "UPLOAD PDR duration check failed: " + e.getMessage(), e);
        }
        // Check whether PDR positions actually change (server may drop constant positions)
        try {
            int pdrCount = sentTrajectory.getPdrDataCount();
            float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;

            for (int i = 0; i < pdrCount; i++) {
                float x = sentTrajectory.getPdrData(i).getX();
                float y = sentTrajectory.getPdrData(i).getY();
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }

            Log.e(TAG, "UPLOAD PDR range: x=[" + minX + "," + maxX + "], y=[" + minY + "," + maxY + "], count=" + pdrCount);
        } catch (Exception e) {
            Log.e(TAG, "UPLOAD PDR range check failed: " + e.getMessage(), e);
        }
        // Debug: show first/last 3 PDR samples to confirm timestamps are really present in protobuf
        try {
            int n = sentTrajectory.getPdrDataCount();
            Log.e(TAG, "UPLOAD PDR count=" + n);

            for (int i = 0; i < Math.min(3, n); i++) {
                long ts = sentTrajectory.getPdrData(i).getRelativeTimestamp();
                float x = sentTrajectory.getPdrData(i).getX();
                float y = sentTrajectory.getPdrData(i).getY();
                Log.e(TAG, "UPLOAD PDR first[" + i + "]: t=" + ts + " x=" + x + " y=" + y);
            }

            for (int i = Math.max(0, n - 3); i < n; i++) {
                long ts = sentTrajectory.getPdrData(i).getRelativeTimestamp();
                float x = sentTrajectory.getPdrData(i).getX();
                float y = sentTrajectory.getPdrData(i).getY();
                Log.e(TAG, "UPLOAD PDR last[" + i + "]: t=" + ts + " x=" + x + " y=" + y);
            }
        } catch (Exception e) {
            Log.e(TAG, "UPLOAD PDR sample dump failed: " + e.getMessage(), e);
        }
        // Dump JSON field names to identify which field the server expects as position_data
        try {
            String json = com.google.protobuf.util.JsonFormat.printer().print(sentTrajectory);
            Log.e(TAG, "UPLOAD Trajectory JSON (first 1200 chars): " + json.substring(0, Math.min(1200, json.length())));
        } catch (Exception e) {
            Log.e(TAG, "UPLOAD JSON dump failed: " + e.getMessage(), e);
        }
        // Locate possible position field names in JSON and print surrounding snippet
        try {
            String json = com.google.protobuf.util.JsonFormat.printer().print(sentTrajectory);

            String[] keys = new String[] {
                    "\"pdrData\"", "\"pdr_data\"", "\"pdr\"", "\"pdrPositions\"",
                    "\"positionData\"", "\"position_data\"", "\"positions\"", "\"position\"",
                    "\"relativePosition\"", "\"relativePositions\"",
                    "\"startTimestamp\"", "\"start_timestamp\""
            };

            for (String k : keys) {
                int idx = json.indexOf(k);
                if (idx >= 0) {
                    int start = Math.max(0, idx - 200);
                    int end = Math.min(json.length(), idx + 800);
                    Log.e(TAG, "JSON contains " + k + " at " + idx + " snippet:\n" + json.substring(start, end));
                } else {
                    Log.e(TAG, "JSON missing key: " + k);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "JSON key search failed: " + e.getMessage(), e);
        }


        File file;
        try {
            String fileName = "upload_" + System.currentTimeMillis() + ".proto";
            file = new File(context.getCacheDir(), fileName);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(sentTrajectory.toByteArray());
            fos.close();

            // Verify temp upload file is written successfully
            Log.d(TAG, "[DBG] tempUploadFile path=" + file.getAbsolutePath()
                    + " exists=" + file.exists()
                    + " size=" + file.length());
        } catch (IOException e) {
            Log.e(TAG, "sendTrajectory: file write failed: " + e.getMessage(), e);
            e.printStackTrace();
            return;
        }

        OkHttpClient client = new OkHttpClient();
        RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(),
                        RequestBody.create(MediaType.parse("application/octet-stream"), file))
                .build();

        Request request = new Request.Builder()
                .url(dynamicUrl)
                .post(requestBody)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .build();

        // Added: log sendTrajectory request headers
        Log.d(TAG, "[DBG] sendTrajectory headers=" + request.headers().toString());

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "sendTrajectory onFailure: " + e.getMessage(), e);
                e.printStackTrace();
                success = false;
                notifyObservers(1);
                if (file.exists()) file.delete();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String bodyStr = null;
                try (ResponseBody responseBody = response.body()) {
                    bodyStr = (responseBody != null) ? responseBody.string() : null;
                    Log.d(TAG, "[DBG] sendTrajectory response code=" + response.code()
                            + " message=" + response.message()
                            + " body=" + bodyStr);

                    success = response.isSuccessful();
                    notifyObservers(1);
                    if (file.exists()) file.delete();
                }
            }
        });
    }


    /**
     * Uploads a local trajectory file to the API server.
     */
    public void uploadLocalTrajectory(File localTrajectory) {
        OkHttpClient client = new OkHttpClient();

        // upload entry log
        Log.d(TAG, "[DBG] uploadLocalTrajectory() called"
                + " path=" + (localTrajectory == null ? "null" : localTrajectory.getAbsolutePath())
                + " exists=" + (localTrajectory != null && localTrajectory.exists())
                + " size=" + (localTrajectory != null && localTrajectory.exists() ? localTrajectory.length() : -1));

        if (localTrajectory == null) {
            success = false;
            infoResponse = "Upload failed: localTrajectory is null";
            Log.e(TAG, infoResponse);
            notifyObservers(1);
            return;
        }

        // log filename (check naming)
        Log.d(TAG, "[DBG] uploading filename=" + localTrajectory.getName());

        RequestBody fileRequestBody;

        // Change (also a debug point): protobuf is binary, avoid text/plain
        MediaType protoType = MediaType.parse("application/octet-stream");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                byte[] fileBytes = Files.readAllBytes(localTrajectory.toPath());
                // log byte length
                Log.d(TAG, "[DBG] readAllBytes length=" + (fileBytes == null ? -1 : fileBytes.length));
                fileRequestBody = RequestBody.create(protoType, fileBytes);
            } catch (IOException e) {
                Log.e(TAG, "readAllBytes failed: " + e.getMessage(), e);
                fileRequestBody = RequestBody.create(protoType, localTrajectory);
            }
        } else {
            fileRequestBody = RequestBody.create(protoType, localTrajectory);
        }

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", localTrajectory.getName(), fileRequestBody)
                .build();

        // log uploadURL
        Log.d(TAG, "[DBG] uploadURL=" + uploadURL);

        Request request = new Request.Builder()
                .url(uploadURL)
                .post(requestBody)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .build();

        // log request headers
        Headers headers = request.headers();
        Log.d(TAG, "[DBG] request headers=" + headers.toString());

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                success = false;

                // network failure log
                infoResponse = "Upload onFailure: " + e.getClass().getSimpleName() + " - " + e.getMessage();
                Log.e(TAG, infoResponse, e);

                notifyObservers(1);

                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String bodyStr = null;
                try (ResponseBody responseBody = response.body()) {
                    bodyStr = (responseBody != null) ? responseBody.string() : null;

                    // log HTTP status code and response body
                    Log.d(TAG, "[DBG] Upload response code=" + response.code()
                            + " message=" + response.message()
                            + " body=" + bodyStr);

                    if (!response.isSuccessful()) {
                        success = false;
                        infoResponse = "Upload failed: HTTP " + response.code()
                                + " " + response.message()
                                + (bodyStr != null ? (", body=" + bodyStr) : "");

                        Log.e(TAG, infoResponse);
                        notifyObservers(1);

                        new Handler(Looper.getMainLooper()).post(() ->
                                Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());
                        return;
                    }

                    success = true;
                    infoResponse = "Upload success: HTTP " + response.code();
                    Log.i(TAG, infoResponse);

                    notifyObservers(1);

                }
            }
        });
    }
    /**
     * Loads download records from a JSON file.
     */
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
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Saves a download record to the local JSON file.
     */
    private void saveDownloadRecord(long startTimestamp, String fileName, String id, String dateSubmitted) {
        File recordsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File recordsFile = new File(recordsDir, "download_records.json");
        JSONObject jsonObject;

        try {
            if (recordsDir != null && !recordsDir.exists()) {
                recordsDir.mkdirs();
            }

            if (!recordsFile.exists()) {
                if (recordsFile.createNewFile()) {
                    jsonObject = new JSONObject();
                } else {
                    return;
                }
            } else {
                StringBuilder jsonBuilder = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(recordsFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        jsonBuilder.append(line);
                    }
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

    /**
     * Downloads a specific trajectory from the server and saves it as a JSON text file.
     */
    public void downloadTrajectory(int position, String id, String dateSubmitted) {
        loadDownloadRecords();
        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url(downloadURL)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
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
                    while ((bytesRead = zipInputStream.read(buffer)) != -1) {
                        byteArrayOutputStream.write(buffer, 0, bytesRead);
                    }

                    byte[] byteArray = byteArrayOutputStream.toByteArray();
                    Traj.Trajectory receivedTrajectory = Traj.Trajectory.parseFrom(byteArray);
                    logDataSize(receivedTrajectory);

                    long startTimestamp = receivedTrajectory.getStartTimestamp();
                    String fileName = "trajectory_" + dateSubmitted + ".txt";

                    File appSpecificDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (appSpecificDownloads != null && !appSpecificDownloads.exists()) {
                        appSpecificDownloads.mkdirs();
                    }

                    File file = new File(appSpecificDownloads, fileName);
                    try (FileWriter fileWriter = new FileWriter(file)) {
                        String receivedTrajectoryString = JsonFormat.printer().print(receivedTrajectory);
                        fileWriter.write(receivedTrajectoryString);
                        fileWriter.flush();
                    } finally {
                        zipInputStream.closeEntry();
                        byteArrayOutputStream.close();
                        zipInputStream.close();
                        inputStream.close();
                    }

                    saveDownloadRecord(startTimestamp, fileName, id, dateSubmitted);
                    loadDownloadRecords();
                }
            }
        });
    }

    /**
     * Requests information about all submitted trajectories from the server.
     */
    public void sendInfoRequest() {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(infoRequestURL)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
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

    private void logDataSize(Traj.Trajectory trajectory) {
        Log.i(TAG, "IMU: " + trajectory.getImuDataCount());
        Log.i(TAG, "Mag: " + trajectory.getMagnetometerDataCount());
        Log.i(TAG, "Pressure: " + trajectory.getPressureDataCount());
        Log.i(TAG, "Light: " + trajectory.getLightDataCount());
        Log.i(TAG, "GNSS: " + trajectory.getGnssDataCount());
        Log.i(TAG, "WiFi: " + trajectory.getWifiFingerprintsCount());
        Log.i(TAG, "APS: " + trajectory.getApsDataCount());
        Log.i(TAG, "PDR: " + trajectory.getPdrDataCount());
    }

    @Override
    public void registerObserver(Observer o) {
        this.observers.add(o);
    }

    @Override
    public void notifyObservers(int index) {
        for (Observer o : observers) {
            if (index == 0 && o instanceof FilesFragment) {
                o.update(new String[]{infoResponse});
            } else if (index == 1 && o instanceof MainActivity) {
                o.update(new Boolean[]{success});
            }
        }
    }

    public interface BuildingCallback {
        void onBuildingsReceived(List<Building> buildings);
        void onError(String message);
    }

    public interface ImageCallback {
        void onImageLoaded(Bitmap bitmap);
        void onError(String message);
    }

    /**
     * Fetches nearby buildings based on coordinates.
     */
    public void getNearbyBuildings(double lat, double lng, BuildingCallback callback) {
        OkHttpClient client = new OkHttpClient();
        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("lat", lat);
            jsonBody.put("lon", lng);
            jsonBody.put("macs", new JSONArray());
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }

        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(JSON, jsonBody.toString());

        Request request = new Request.Builder()
                .url(floorPlanRequestURL)
                .post(body)
                .addHeader("accept", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onError(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        String errorMsg = responseBody != null ? responseBody.string() : "Error";
                        new Handler(Looper.getMainLooper()).post(() -> callback.onError(errorMsg));
                        return;
                    }
                    String jsonString = responseBody.string();
                    try {
                        List<Building> buildings = parseBuildingsJson(jsonString);
                        new Handler(Looper.getMainLooper()).post(() -> callback.onBuildingsReceived(buildings));
                    } catch (JSONException e) {
                        new Handler(Looper.getMainLooper()).post(() -> callback.onError(e.getMessage()));
                    }
                }
            }
        });
    }

    /**
     * Downloads floor map image from the provided URL.
     */
    public void downloadFloorMapImage(String url, ImageCallback callback) {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onError("Image Download Failed"));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    InputStream inputStream = response.body().byteStream();
                    Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                    new Handler(Looper.getMainLooper()).post(() -> callback.onImageLoaded(bitmap));
                } else {
                    new Handler(Looper.getMainLooper()).post(() -> callback.onError("Image Response Failed"));
                }
            }
        });
    }

    /**
     * Parses building JSON data including outline and floor plans.
     */
    private List<Building> parseBuildingsJson(String jsonString) throws JSONException {
        List<Building> buildingList = new ArrayList<>();
        JSONArray jsonArray = new JSONArray(jsonString);

        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject bObj = jsonArray.getJSONObject(i);
            String id = bObj.has("id") && !bObj.isNull("id") ? bObj.getString("id") : "unknown";
            String name = bObj.has("name") ? bObj.getString("name") : "Unknown Building";

            List<List<Double>> outline = new ArrayList<>();
            if (bObj.has("outline") && bObj.get("outline") instanceof String) {
                extractPolygonsFromGeoJson(bObj.getString("outline"), outline);
            }

            List<FloorPlan> floors = new ArrayList<>();
            if (bObj.has("map_shapes")) {
                JSONObject mapShapes = new JSONObject(bObj.getString("map_shapes"));
                Iterator<String> keys = mapShapes.keys();
                while (keys.hasNext()) {
                    String floorCode = keys.next();
                    List<List<List<Double>>> walls = new ArrayList<>();
                    extractWallsFromGeoJson(mapShapes.get(floorCode).toString(), walls);
                    floors.add(new FloorPlan(floorCode, 0, null, new double[]{0, 0, 0, 0}, walls));
                }
            }

            Collections.sort(floors, (f1, f2) -> Integer.compare(getFloorOrderValue(f1.getFloorCode()), getFloorOrderValue(f2.getFloorCode())));
            buildingList.add(new Building(id, name, outline, floors));
        }
        return buildingList;
    }

    private int getFloorOrderValue(String code) {
        if (code == null) return 0;
        String raw = code.trim().toUpperCase();
        if (raw.equals("G") || raw.equals("GROUND") || raw.equals("0")) return 0;
        if (raw.equals("LG") || raw.startsWith("B")) return -1;
        try { return Integer.parseInt(raw); } catch (NumberFormatException e) { return 0; }
    }

    private void extractPolygonsFromGeoJson(String geoJsonStr, List<List<Double>> outList) {
        try {
            JSONObject featureCollection = new JSONObject(geoJsonStr);
            JSONArray features = featureCollection.getJSONArray("features");
            if (features.length() > 0) {
                JSONObject geometry = features.getJSONObject(0).getJSONObject("geometry");
                JSONArray coordinates = geometry.getJSONArray("coordinates");
                String type = geometry.getString("type");
                JSONArray ring = type.equalsIgnoreCase("MultiPolygon") ? coordinates.getJSONArray(0).getJSONArray(0) : coordinates.getJSONArray(0);
                for (int j = 0; j < ring.length(); j++) {
                    JSONArray point = ring.getJSONArray(j);
                    List<Double> latLng = new ArrayList<>();
                    latLng.add(point.getDouble(1));
                    latLng.add(point.getDouble(0));
                    outList.add(latLng);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void extractWallsFromGeoJson(String geoJsonStr, List<List<List<Double>>> wallsList) {
        try {
            JSONObject featureCollection = new JSONObject(geoJsonStr);
            JSONArray features = featureCollection.getJSONArray("features");
            for (int i = 0; i < features.length(); i++) {
                JSONObject geometry = features.getJSONObject(i).getJSONObject("geometry");
                String type = geometry.getString("type");
                JSONArray coords = geometry.getJSONArray("coordinates");

                if (type.equalsIgnoreCase("MultiLineString")) {
                    for (int k = 0; k < coords.length(); k++) parseLineString(coords.getJSONArray(k), wallsList);
                } else if (type.equalsIgnoreCase("LineString") || type.equalsIgnoreCase("Polygon")) {
                    parseLineString(type.equalsIgnoreCase("LineString") ? coords : coords.getJSONArray(0), wallsList);
                } else if (type.equalsIgnoreCase("MultiPolygon")) {
                    for (int k = 0; k < coords.length(); k++) parseLineString(coords.getJSONArray(k).getJSONArray(0), wallsList);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void parseLineString(JSONArray lineArray, List<List<List<Double>>> wallsList) throws JSONException {
        List<List<Double>> path = new ArrayList<>();
        for (int p = 0; p < lineArray.length(); p++) {
            JSONArray point = lineArray.getJSONArray(p);
            List<Double> latLng = new ArrayList<>();
            latLng.add(point.getDouble(1));
            latLng.add(point.getDouble(0));
            path.add(latLng);
        }
        if (!path.isEmpty()) wallsList.add(path);
    }

}