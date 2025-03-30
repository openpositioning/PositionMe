package com.openpositioning.PositionMe.data.remote;
import android.util.Log;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.io.BufferedReader;
import java.io.FileReader;
import org.json.JSONObject;

import android.os.Environment;

import java.io.FileInputStream;
import java.io.OutputStream;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.google.protobuf.util.JsonFormat;
import com.openpositioning.PositionMe.BuildConfig;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.presentation.fragment.FilesFragment;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.sensors.Observable;
import com.openpositioning.PositionMe.sensors.Observer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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
    private static final String uploadURL =
            "https://openpositioning.org/api/live/trajectory/upload/" + userKey
                    + "/?key=" + masterKey;
    private static final String downloadURL =
            "https://openpositioning.org/api/live/trajectory/download/" + userKey
                    + "?skip=0&limit=30&key=" + masterKey;
    private static final String infoRequestURL =
            "https://openpositioning.org/api/live/users/trajectories/" + userKey
                    + "?key=" + masterKey;
    private static final String PROTOCOL_CONTENT_TYPE = "multipart/form-data";
    private static final String PROTOCOL_ACCEPT_TYPE = "application/json";



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
     * @param trajectory    Traj object matching all the timing and formal restrictions.
     */
    public void sendTrajectory(Traj.Trajectory trajectory){
        logDataSize(trajectory);

        // Convert the trajectory to byte array for upload
        byte[] binaryTrajectory = trajectory.toByteArray();

        // Convert trajectory to JSON string for local storage
        String jsonTrajectory;
        try {
            // 创建简化的JSON对象，只包含融合后的位置数据
            JsonObject trajectoryJson = new JsonObject();
            
            // 添加基本元数据
            trajectoryJson.addProperty("startTimestamp", trajectory.getStartTimestamp());
            trajectoryJson.addProperty("endTimestamp", System.currentTimeMillis());
            
            // 创建融合位置数组
            JsonArray fusionLocations = new JsonArray();
            
            // 如果有GNSS数据，获取初始位置
            double initialLat = 0;
            double initialLng = 0;
            if (!trajectory.getGnssDataList().isEmpty()) {
                Traj.GNSS_Sample firstGnss = trajectory.getGnssDataList().get(0);
                initialLat = firstGnss.getLatitude();
                initialLng = firstGnss.getLongitude();
                // 添加初始位置属性
                trajectoryJson.addProperty("initialLatitude", initialLat);
                trajectoryJson.addProperty("initialLongitude", initialLng);
            }
            
            // 处理PDR数据
            // 仅当PDR和GNSS数据都存在时才能生成融合位置
            if (!trajectory.getPdrDataList().isEmpty()) {
                float lastX = 0, lastY = 0;
                long lastTimestamp = -1000;
                
                for (Traj.Pdr_Sample pdrData : trajectory.getPdrDataList()) {
                    long currentTimestamp = pdrData.getRelativeTimestamp();
                    float currentX = pdrData.getX();
                    float currentY = pdrData.getY();
                    
                    // 找到最近的IMU数据获取方向
                    float orientation = 0;
                    for (Traj.Motion_Sample imuData : trajectory.getImuDataList()) {
                        if (Math.abs(imuData.getRelativeTimestamp() - currentTimestamp) < 100) {
                            // 简单计算方向角度（实际项目中可能需要更复杂的计算）
                            orientation = (float) Math.toDegrees(
                                Math.atan2(imuData.getRotationVectorZ(), imuData.getRotationVectorY()));
                            if (orientation < 0) orientation += 360;
                            break;
                        }
                    }
                    
                    // 只在位置变化超过阈值或时间间隔超过1秒时保存
                    if (lastTimestamp + 1000 <= currentTimestamp || 
                        Math.abs(currentX - lastX) > 0.1 || 
                        Math.abs(currentY - lastY) > 0.1) {
                        
                        // 计算当前位置的融合经纬度
                        // 这里使用简单的偏移计算，实际项目中可能需要更精确的转换
                        double lat = initialLat + currentY * 1E-5;
                        double lng = initialLng + currentX * 1E-5;
                        
                        // 找到最近的GNSS数据进行融合
                        for (Traj.GNSS_Sample gnssData : trajectory.getGnssDataList()) {
                            if (Math.abs(gnssData.getRelativeTimestamp() - currentTimestamp) < 500) {
                                // 简单的50/50融合，实际项目中可能需要更复杂的融合算法
                                lat = (lat + gnssData.getLatitude()) / 2;
                                lng = (lng + gnssData.getLongitude()) / 2;
                                break;
                            }
                        }
                        
                        // 创建并添加融合位置点
                        JsonObject locationPoint = new JsonObject();
                        locationPoint.addProperty("timestamp", currentTimestamp);
                        locationPoint.addProperty("latitude", lat);
                        locationPoint.addProperty("longitude", lng);
                        locationPoint.addProperty("orientation", orientation);
                        fusionLocations.add(locationPoint);
                        
                        lastTimestamp = currentTimestamp;
                        lastX = currentX;
                        lastY = currentY;
                    }
                }
            } else if (!trajectory.getGnssDataList().isEmpty()) {
                // 如果没有PDR数据，则只使用GNSS数据
                long lastTimestamp = -1000;
                double lastLat = 0, lastLng = 0;
                
                for (Traj.GNSS_Sample gnssData : trajectory.getGnssDataList()) {
                    long currentTimestamp = gnssData.getRelativeTimestamp();
                    double currentLat = gnssData.getLatitude();
                    double currentLng = gnssData.getLongitude();
                    
                    // 只在位置变化超过阈值或时间间隔超过1秒时保存
                    if (lastTimestamp == -1000 || 
                        lastTimestamp + 1000 <= currentTimestamp || 
                        Math.abs(currentLat - lastLat) > 0.00001 || 
                        Math.abs(currentLng - lastLng) > 0.00001) {
                        
                        // 创建并添加GNSS位置点
                        JsonObject locationPoint = new JsonObject();
                        locationPoint.addProperty("timestamp", currentTimestamp);
                        locationPoint.addProperty("latitude", currentLat);
                        locationPoint.addProperty("longitude", currentLng);
                        locationPoint.addProperty("orientation", 0); // GNSS无方向信息，默认为0
                        fusionLocations.add(locationPoint);
                        
                        lastTimestamp = currentTimestamp;
                        lastLat = currentLat;
                        lastLng = currentLng;
                    }
                }
            }
            
            // 添加融合位置数组到JSON对象
            trajectoryJson.add("fusionLocations", fusionLocations);
            
            // 转换为带格式的JSON字符串（用于调试）
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            jsonTrajectory = gson.toJson(trajectoryJson);
            System.out.println("保存的轨迹数据: " + jsonTrajectory);
        } catch (Exception ee) {
            System.err.println("Failed to convert trajectory to JSON: " + ee.getMessage());
            return;
        }

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

        // Create a dedicated directory for storing local trajectories
        File localTrajectoryDir = new File(path, "local_trajectories");
        if (!localTrajectoryDir.exists()) {
            localTrajectoryDir.mkdirs();
        }

        System.out.println(path.toString());

        // Format the file name according to date
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm");
        dateFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        Date date = new Date();
        String fileName = "trajectory_" + dateFormat.format(date);
        
        // Create temporary files for both upload and local storage
        File tempFile = new File(path, "temp_upload.txt");
        File tempLocalFile = new File(localTrajectoryDir, "temp_local.json");

        try {
            // Write the binary data to temp file for upload
            FileOutputStream tempStream = new FileOutputStream(tempFile);
            tempStream.write(binaryTrajectory);
            tempStream.close();
            
            // Write JSON data to temporary local file
            FileWriter localWriter = new FileWriter(tempLocalFile);
            localWriter.write(jsonTrajectory);
            localWriter.close();
            
            System.out.println("临时文件创建成功，JSON文件路径: " + tempLocalFile.getAbsolutePath());
        } catch (IOException ee) {
            System.err.println("创建临时文件失败: " + ee.getMessage());
            return;
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
                    .addFormDataPart("file", "trajectory.txt",
                            RequestBody.create(MediaType.parse("text/plain"), tempFile))
                    .build();

            // Create a POST request with the required headers
            Request request = new Request.Builder().url(uploadURL).post(requestBody)
                    .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                    .addHeader("Content-Type", PROTOCOL_CONTENT_TYPE).build();

            // Enqueue the request to be executed asynchronously and handle the response
            client.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    e.printStackTrace();
                    System.err.println("上传失败: " + e.getMessage());
                    
                    // 在上传失败时，仍然保存本地文件
                    try {
                        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm");
                        dateFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                        String finalFileName = "trajectory_" + dateFormat.format(new Date());
                        File localFile = new File(localTrajectoryDir, finalFileName + ".json");
                        
                        if (tempLocalFile.exists()) {
                            if (tempLocalFile.renameTo(localFile)) {
                                System.out.println("上传失败但JSON文件已保存: " + localFile.getAbsolutePath());
                            } else {
                                copyFile(tempLocalFile, localFile);
                                tempLocalFile.delete();
                                System.out.println("上传失败但JSON文件已复制: " + localFile.getAbsolutePath());
                            }
                        }
                    } catch (Exception ex) {
                        System.err.println("保存JSON文件失败: " + ex.getMessage());
                    }
                    
                    // 清理临时文件
                    if (tempFile.exists()) tempFile.delete();
                    if (tempLocalFile.exists()) tempLocalFile.delete();
                    
                    success = false;
                    notifyObservers(1);
                }

                @Override public void onResponse(Call call, Response response) throws IOException {
                    try (ResponseBody responseBody = response.body()) {
                        if (!response.isSuccessful()) {
                            String message = "服务器响应错误: " + response;
                            System.err.println(message);
                            success = false;
                            throw new IOException(message);
                        }

                        // Get server response time from response headers
                        String serverDate = response.header("Date");
                        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm");
                        dateFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                        Date responseDate = new Date();
                        if (serverDate != null) {
                            try {
                                SimpleDateFormat serverDateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
                                responseDate = serverDateFormat.parse(serverDate);
                            } catch (Exception e) {
                                System.err.println("解析服务器时间失败: " + e.getMessage());
                            }
                        }
                        
                        String finalFileName = "trajectory_" + dateFormat.format(responseDate);
                        File localFile = new File(localTrajectoryDir, finalFileName + ".json");
                        
                        // 重命名临时JSON文件为最终文件
                        if (tempLocalFile.exists()) {
                            if (tempLocalFile.renameTo(localFile)) {
                                System.out.println("JSON文件重命名成功: " + localFile.getAbsolutePath());
                            } else {
                                copyFile(tempLocalFile, localFile);
                                tempLocalFile.delete();
                                System.out.println("JSON文件复制成功: " + localFile.getAbsolutePath());
                            }
                        } else {
                            System.err.println("临时JSON文件不存在: " + tempLocalFile.getAbsolutePath());
                        }

                        // 打印响应头
                        Headers responseHeaders = response.headers();
                        for (int i = 0, size = responseHeaders.size(); i < size; i++) {
                            System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
                        }

                        assert responseBody != null;
                        System.out.println("上传成功: " + responseBody.string());

                        // 删除临时文件
                        if (tempFile.exists()) tempFile.delete();
                        if (tempLocalFile.exists()) tempLocalFile.delete();
                        
                        success = true;
                        notifyObservers(1);
                    }
                }
            });
        } else {
            System.err.println("网络连接不可用，无法上传!");
            
            // 即使不上传，也保存本地文件
            try {
                String finalFileName = "trajectory_" + dateFormat.format(date);
                File localFile = new File(localTrajectoryDir, finalFileName + ".json");
                
                if (tempLocalFile.exists()) {
                    if (tempLocalFile.renameTo(localFile)) {
                        System.out.println("离线模式：JSON文件已保存: " + localFile.getAbsolutePath());
                    } else {
                        copyFile(tempLocalFile, localFile);
                        tempLocalFile.delete();
                        System.out.println("离线模式：JSON文件已复制: " + localFile.getAbsolutePath());
                    }
                }
            } catch (Exception ex) {
                System.err.println("保存本地JSON文件失败: " + ex.getMessage());
            }
            
            // 清理临时文件
            if (tempFile.exists()) tempFile.delete();
            if (tempLocalFile.exists()) tempLocalFile.delete();
            
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
                    // TODO: add printing to the screen somewhere
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
        Log.i("ServerCommunications", "IMU Data size: " + trajectory.getImuDataCount());
        Log.i("ServerCommunications", "Position Data size: " + trajectory.getPositionDataCount());
        Log.i("ServerCommunications", "Pressure Data size: " + trajectory.getPressureDataCount());
        Log.i("ServerCommunications", "Light Data size: " + trajectory.getLightDataCount());
        Log.i("ServerCommunications", "GNSS Data size: " + trajectory.getGnssDataCount());
        Log.i("ServerCommunications", "WiFi Data size: " + trajectory.getWifiDataCount());
        Log.i("ServerCommunications", "APS Data size: " + trajectory.getApsDataCount());
        Log.i("ServerCommunications", "PDR Data size: " + trajectory.getPdrDataCount());
    }

    /**
     * 复制文件的辅助方法
     * @param src 源文件
     * @param dst 目标文件
     * @throws IOException 如果复制过程中发生IO错误
     */
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