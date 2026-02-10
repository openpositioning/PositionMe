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


// 在现有的 imports 下方添加：
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.Collections;
import java.util.Comparator;
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
    // ==============================================================
    // 部分 1：变量定义与 Log 标签
    // ==============================================================

    // 1. 添加日志标签 (在 Logcat 中搜索 "ServerDebug" 就能看到日志)
    private static final String TAG = "ServerDebug";

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

    // 2. 必须硬编码 Key 以修复 401 错误
    private static final String userKey = "LY31NlnGAe9vN-HvQJWTZg";
    private static final String masterKey = "ewireless";

    // 3. URL 定义
    private static final String uploadURL =
            "https://openpositioning.org/api/live/trajectory/upload/" + userKey
                    + "/?key=" + masterKey;
    private static final String downloadURL =
            "https://openpositioning.org/api/live/trajectory/download/" + userKey
                    + "?skip=0&limit=30&key=" + masterKey;
    private static final String infoRequestURL =
            "https://openpositioning.org/api/live/users/trajectories/" + userKey
                    + "?key=" + masterKey;

    // Task D: 室内地图请求 URL
    private static final String floorPlanRequestURL =
            "https://openpositioning.org/api/live/floorplan/request/" + userKey
                    + "?key=" + masterKey;

    private static final String PROTOCOL_CONTENT_TYPE = "multipart/form-data";
    private static final String PROTOCOL_ACCEPT_TYPE = "application/json";
    // ... (下面接着是你原来的 public ServerCommunications(Context context) 构造函数，不要动)


    /**
     * Public default constructor of {@link ServerCommunications}. The constructor saves context,
     * initialises a {@link ConnectivityManager}, {@link Observer} and gets the user preferences.
     * Boolean variables storing WiFi and Mobile Data connection status are initialised to false.
     *
     * @param context application context for handling permissions and devices.
     */
    public ServerCommunications(Context context) {
        this.context = context;
        this.connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.observers = new ArrayList<>();

    }

    public void sendInfo(Traj.Trajectory trajectory) {
        this.trajectory = trajectory;

        Log.i("ServerCommunications", "IMU Data size: " + trajectory.getImuDataCount());
        Log.i("ServerCommunications", "Light Data size: " + trajectory.getLightDataCount());
        Log.i("ServerCommunications", "GNSS Data size: " + trajectory.getGnssDataCount());


        Log.i("ServerCommunications", "WiFi Data size: " + trajectory.getWifiFingerprintsCount());

        Log.i("ServerCommunications", "APS Data size: " + trajectory.getApsDataCount());
        Log.i("ServerCommunications", "PDR Data size: " + trajectory.getPdrDataCount());


        Log.i("ServerCommunications", "Mag Data size: " + trajectory.getMagnetometerDataCount());
    }


    /**
     * 上传轨迹到服务器 (Feature B & D)
     * 替换了旧的 Map 参数版本，直接接收构建好的 Protobuf 对象和 Campaign 名称
     */
    public void sendTrajectory(Traj.Trajectory sentTrajectory, String campaign) {
        // 1. 处理 Campaign 参数 (Feature D 要求)
        // 如果未指定，默认为 murchison_house，防止 URL 错误
        if (campaign == null || campaign.isEmpty()) {
            campaign = "murchison_house";
        }

        // 2. 动态构建 URL
        // 格式: .../upload/{campaign}/{userKey}/?key={masterKey}
        String dynamicUrl = "https://openpositioning.org/api/live/trajectory/upload/" + campaign + "/" + userKey + "/?key=" + masterKey;

        // 3. 将 Protobuf 对象写入临时文件 (OkHttp 需要文件流)
        File file;
        try {
            // 使用时间戳防止文件名冲突
            String fileName = "upload_" + System.currentTimeMillis() + ".proto";
            // 使用缓存目录，避免污染外部存储
            file = new File(context.getCacheDir(), fileName);

            FileOutputStream fos = new FileOutputStream(file);
            fos.write(sentTrajectory.toByteArray());
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("Failed to save temp file for upload");
            return;
        }

        // 4. 构建网络请求 (Multipart Upload)
        OkHttpClient client = new OkHttpClient();

        RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(),
                        RequestBody.create(MediaType.parse("application/octet-stream"), file))
                .build();

        Request request = new Request.Builder()
                .url(dynamicUrl)
                .post(requestBody)
                .build();

        System.out.println("Uploading to: " + dynamicUrl);

        // 5. 异步发送请求
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                System.err.println("Upload Failed: " + e.getMessage());

                // 通知 MainActivity 更新 UI (失败)
                success = false;
                notifyObservers(1);

                // 清理临时文件
                if (file.exists()) file.delete();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        System.err.println("Upload Error: " + response.code() + " " + responseBody.string());
                        success = false;
                    } else {
                        System.out.println("Upload SUCCESS: " + responseBody.string());
                        success = true;
                    }

                    // 通知 MainActivity 更新 UI (根据 success 状态)
                    notifyObservers(1);

                    // 清理临时文件
                    if (file.exists()) file.delete();
                }
            }
        });
    }

    /**
     * Uploads a local trajectory file to the API server in the specified format.
     * {@link OkHttp} library is used for the asynchronous POST request.
     *
     * @param localTrajectory the File object of the local trajectory to be uploaded
     */
    public void uploadLocalTrajectory(File localTrajectory) {

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
     * @param fileName       the name of the file
     * @param id             the ID of the trajectory
     * @param dateSubmitted  the date the trajectory was submitted
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
     * @param position      the position of the trajectory in the zip file to retrieve
     * @param id            the ID of the trajectory
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
                    if (!response.isSuccessful())
                        throw new IOException("Unexpected code " + response);

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
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    // Check if the response is successful
                    if (!response.isSuccessful()) throw new IOException("Unexpected code " +
                            response);

                    // Get the requested information from the response body and save it in a string
                    // TODO: add printing to the screen somewhere
                    infoResponse = responseBody.string();
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
        Log.i("ServerCommunications", "IMU Data size: " + trajectory.getImuDataCount());

        Log.i("ServerCommunications", "Magnetometer Data size: " + trajectory.getMagnetometerDataCount());
        Log.i("ServerCommunications", "Pressure Data size: " + trajectory.getPressureDataCount());
        Log.i("ServerCommunications", "Light Data size: " + trajectory.getLightDataCount());
        Log.i("ServerCommunications", "GNSS Data size: " + trajectory.getGnssDataCount());

        Log.i("ServerCommunications", "WiFi Data size: " + trajectory.getWifiFingerprintsCount());
        Log.i("ServerCommunications", "APS Data size: " + trajectory.getApsDataCount());
        Log.i("ServerCommunications", "PDR Data size: " + trajectory.getPdrDataCount());
    }

    /**
     * {@inheritDoc}
     * <p>
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
     * <p>
     * Method for notifying all registered observers. The observer is notified based on the index
     * passed to the method.
     *
     * @param index Index for identifying the observer to be notified.
     */
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
    // ==========================================
    // 部分 2：Task D 功能实现 (带 Logcat 调试)
    // ==========================================

    public interface BuildingCallback {
        void onBuildingsReceived(List<Building> buildings);
        void onError(String message);
    }

    public interface ImageCallback {
        void onImageLoaded(Bitmap bitmap);
        void onError(String message);
    }

    public void getNearbyBuildings(double lat, double lng, BuildingCallback callback) {
        OkHttpClient client = new OkHttpClient();

        // [Debug] 打印请求 URL 和参数
        Log.d(TAG, "------------------------------------------------");
        Log.d(TAG, "Requesting Buildings...");
        Log.d(TAG, "URL: " + floorPlanRequestURL);
        Log.d(TAG, "Coordinates: " + lat + ", " + lng);

        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("lat", lat);
            jsonBody.put("lon", lng);
            jsonBody.put("macs", new JSONArray());
            Log.d(TAG, "Request Body: " + jsonBody.toString());
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }

        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(JSON, jsonBody.toString());

        Request request = new Request.Builder()
                .url(floorPlanRequestURL)
                .post(body) // 必须是 POST
                .addHeader("accept", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // [Debug] 网络请求失败
                Log.e(TAG, "Network FAILURE: " + e.getMessage());
                new Handler(Looper.getMainLooper()).post(() ->
                        callback.onError("Network Error: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                // [Debug] 收到服务器响应
                Log.d(TAG, "Response Code: " + response.code());

                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        // [Debug] 打印错误详情 (例如 401 Unauthorized)
                        String errorMsg = responseBody != null ? responseBody.string() : "null";
                        Log.e(TAG, "Server Error Body: " + errorMsg);
                        new Handler(Looper.getMainLooper()).post(() ->
                                callback.onError("Server Error " + response.code() + ": " + errorMsg));
                        return;
                    }

                    String jsonString = responseBody.string();
                    // [Debug] 打印成功的 JSON 数据
                    Log.d(TAG, "Response JSON: " + jsonString);

                    try {
                        List<Building> buildings = parseBuildingsJson(jsonString);
                        Log.d(TAG, "Parsed Buildings Count: " + buildings.size());
                        new Handler(Looper.getMainLooper()).post(() ->
                                callback.onBuildingsReceived(buildings));
                    } catch (JSONException e) {
                        Log.e(TAG, "JSON Parsing Error: " + e.getMessage());
                        new Handler(Looper.getMainLooper()).post(() ->
                                callback.onError("Parsing Error: " + e.getMessage()));
                    }
                }
            }
        });
    }

    public void downloadFloorMapImage(String url, ImageCallback callback) {
        Log.d(TAG, "Downloading Image from: " + url);
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Image Download Failure: " + e.getMessage());
                new Handler(Looper.getMainLooper()).post(() ->
                        callback.onError("Image Download Failed"));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    InputStream inputStream = response.body().byteStream();
                    Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                    Log.d(TAG, "Image Downloaded & Decoded. Size: " + bitmap.getByteCount());
                    new Handler(Looper.getMainLooper()).post(() ->
                            callback.onImageLoaded(bitmap));
                } else {
                    Log.e(TAG, "Image Response Error: " + response.code());
                    new Handler(Looper.getMainLooper()).post(() ->
                            callback.onError("Image Response Failed"));
                }
            }
        });
    }

    /**
     * 修复后的解析方法：支持解析 GeoJSON 格式的 outline 字符串
     */
    /**
     * 修复后的解析方法：同时支持 outline 和 map_shapes (GeoJSON 格式)
     */
    /**
     * 修复后的解析方法：加入楼层智能排序 (B1 < G < 1 < 2 ...)
     */
    /**
     * 修复后的解析方法：支持任意 GeoJSON 形状 (LineString, MultiLineString, Polygon)
     */
    private List<Building> parseBuildingsJson(String jsonString) throws JSONException {
        List<Building> buildingList = new ArrayList<>();
        JSONArray jsonArray = new JSONArray(jsonString);

        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject bObj = jsonArray.getJSONObject(i);

            String id = bObj.has("id") && !bObj.isNull("id") ? bObj.getString("id") : "unknown";
            String name = bObj.has("name") ? bObj.getString("name") : "Unknown Building";

            // 1. 解析 Outline
            List<List<Double>> outline = new ArrayList<>();
            if (bObj.has("outline")) {
                Object outlineObj = bObj.get("outline");
                if (outlineObj instanceof String) {
                    // 提取轮廓
                    extractPolygonsFromGeoJson((String) outlineObj, outline);
                }
            }

            // 2. 解析 Floors
            List<FloorPlan> floors = new ArrayList<>();
            if (bObj.has("map_shapes")) {
                String mapShapesStr = bObj.getString("map_shapes");
                JSONObject mapShapes = new JSONObject(mapShapesStr);

                Iterator<String> keys = mapShapes.keys();
                while (keys.hasNext()) {
                    String floorCode = keys.next();
                    double[] bounds = new double[]{0, 0, 0, 0};

                    // 🔴 关键修改：提取墙体 (walls)
                    List<List<List<Double>>> walls = new ArrayList<>();
                    Object floorData = mapShapes.get(floorCode);

                    // 无论数据是 JSONObject 还是 String，都转成 String 处理
                    String floorGeoJson = floorData instanceof JSONObject ? floorData.toString() : floorData.toString();

                    // 使用新的增强版解析器
                    extractWallsFromGeoJson(floorGeoJson, walls);

                    floors.add(new FloorPlan(floorCode, 0, null, bounds, walls));
                }
            }

            // 排序楼层
            Collections.sort(floors, new Comparator<FloorPlan>() {
                @Override
                public int compare(FloorPlan f1, FloorPlan f2) {
                    return Integer.compare(getFloorOrderValue(f1.getFloorCode()), getFloorOrderValue(f2.getFloorCode()));
                }
            });

            buildingList.add(new Building(id, name, outline, floors));
        }
        return buildingList;
    }

    /**
     * 辅助方法：楼层排序权重
     */
    private int getFloorOrderValue(String code) {
        if (code == null) return 0;
        String raw = code.trim().toUpperCase();
        if (raw.equals("G") || raw.equals("GROUND") || raw.equals("0")) return 0;
        if (raw.equals("LG") || raw.startsWith("B")) return -1;
        try { return Integer.parseInt(raw); } catch (NumberFormatException e) { return 0; }
    }

    /**
     * 辅助方法：提取 Outline (仅多边形)
     */
    private void extractPolygonsFromGeoJson(String geoJsonStr, List<List<Double>> outList) {
        try {
            JSONObject featureCollection = new JSONObject(geoJsonStr);
            JSONArray features = featureCollection.getJSONArray("features");
            if (features.length() > 0) {
                JSONObject geometry = features.getJSONObject(0).getJSONObject("geometry");
                JSONArray coordinates = geometry.getJSONArray("coordinates");
                String type = geometry.getString("type");

                JSONArray ring = null;
                if (type.equalsIgnoreCase("MultiPolygon")) ring = coordinates.getJSONArray(0).getJSONArray(0);
                else if (type.equalsIgnoreCase("Polygon")) ring = coordinates.getJSONArray(0);

                if (ring != null) {
                    for (int j = 0; j < ring.length(); j++) {
                        JSONArray point = ring.getJSONArray(j);
                        List<Double> latLng = new ArrayList<>();
                        latLng.add(point.getDouble(1));
                        latLng.add(point.getDouble(0));
                        outList.add(latLng);
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    /**
     * 🔴 增强版墙体解析器：支持 LineString, MultiLineString 和 Polygon
     */
    private void extractWallsFromGeoJson(String geoJsonStr, List<List<List<Double>>> wallsList) {
        try {
            JSONObject featureCollection = new JSONObject(geoJsonStr);
            JSONArray features = featureCollection.getJSONArray("features");

            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.getJSONObject(i);
                if (!feature.has("geometry")) continue;

                JSONObject geometry = feature.getJSONObject("geometry");
                String type = geometry.getString("type");
                JSONArray coordinates = geometry.getJSONArray("coordinates");

                // 情况 1: MultiLineString (多条线) -> [[[lon,lat], [lon,lat]], ...]
                if (type.equalsIgnoreCase("MultiLineString")) {
                    for (int k = 0; k < coordinates.length(); k++) {
                        parseLineString(coordinates.getJSONArray(k), wallsList);
                    }
                }
                // 情况 2: LineString (单条线) -> [[lon,lat], [lon,lat], ...]
                else if (type.equalsIgnoreCase("LineString")) {
                    parseLineString(coordinates, wallsList);
                }
                // 情况 3: Polygon (多边形房间) -> [[[lon,lat], ...]]
                else if (type.equalsIgnoreCase("Polygon")) {
                    // Polygon 的第一个元素是外环，当作线条画出来
                    parseLineString(coordinates.getJSONArray(0), wallsList);
                }
                // 情况 4: MultiPolygon
                else if (type.equalsIgnoreCase("MultiPolygon")) {
                    for (int k = 0; k < coordinates.length(); k++) {
                        parseLineString(coordinates.getJSONArray(k).getJSONArray(0), wallsList);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "Wall Parsing Error: " + e.getMessage());
        }
    }

    // 解析单个 LineString 的坐标数组
    private void parseLineString(JSONArray lineArray, List<List<List<Double>>> wallsList) throws JSONException {
        List<List<Double>> path = new ArrayList<>();
        for (int p = 0; p < lineArray.length(); p++) {
            JSONArray point = lineArray.getJSONArray(p);
            List<Double> latLng = new ArrayList<>();
            latLng.add(point.getDouble(1)); // Lat
            latLng.add(point.getDouble(0)); // Lon
            path.add(latLng);
        }
        if (!path.isEmpty()) {
            wallsList.add(path);
        }
    }
    /**
     * 兼容性方法：修复 SensorFusion 报错
     */
    public void sendTrajectory(Traj.Trajectory sentTrajectory) {
        Log.d(TAG, "sendTrajectory (compatibility overload) called");
        this.sendTrajectory(sentTrajectory, "murchison_house");
    }
}

