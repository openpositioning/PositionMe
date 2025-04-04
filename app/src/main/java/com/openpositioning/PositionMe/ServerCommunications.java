package com.openpositioning.PositionMe;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.openpositioning.PositionMe.fragments.FilesFragment;
import com.openpositioning.PositionMe.sensors.Observable;
import com.openpositioning.PositionMe.sensors.Observer;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

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

    private List<Map<String, String>> entryList;

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

    public void setEntryList(List<Map<String, String>> entryList) {
        this.entryList = entryList;
    }

    /**
     * Outgoing communication request with a {@link Traj trajectory} object. The recorded
     * trajectory is passed to the method. It is processed into the right format for sending
     * to the API server.
     *
     * @param trajectory    Traj object matching all the timing and formal restrictions.
     */
    public void sendTrajectory(Traj.Trajectory trajectory){

        // Convert the trajectory to byte array
        byte[] binaryTrajectory = trajectory.toByteArray();

        // Get the directory path for storing the file with the trajectory
        File path = context.getFilesDir();

        // Format the file name according to date
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
        dateFormat.setTimeZone(java.util.TimeZone.getDefault());  // 使用本地时区
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
        // TODO: add sync delay and enforce settings
        boolean enableMobileData = this.settings.getBoolean("mobile_sync", false);
        // Check if device is connected to WiFi or to mobile data with enabled preference
        if(this.isWifiConn || (enableMobileData && isMobileConn)) {
            // Instantiate client for HTTP requests
            OkHttpClient client = new OkHttpClient();

            // Creaet a equest body with a file to upload in multipart/form-data format
            RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.getName(),
                            RequestBody.create(MediaType.parse("application/json"), file))
                    .build();

            // Create a POST request with the required headers
            okhttp3.Request request = new okhttp3.Request.Builder().url(uploadURL).post(requestBody)
                    .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                    .addHeader("Content-Type", PROTOCOL_CONTENT_TYPE).build();

            // Enqueue the request to be executed asynchronously and handle the response
            client.newCall(request).enqueue(new okhttp3.Callback() {

                // Handle failure to get response from the server
                @Override public void onFailure(Call call, IOException e) {
                    e.printStackTrace();
                    System.err.println("Failure to get response");
                    // Delete the local file and set success to false
                    //file.delete();
                    success = false;
                    notifyObservers(1);
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
                            infoResponse = "上传失败: " + errorBody;
                            Log.e("ServerCommunications", "上传错误: " + errorBody);
                            
                            new Handler(Looper.getMainLooper()).post(() -> {
                                Toast.makeText(context, infoResponse, Toast.LENGTH_LONG).show();
                                Log.e("ServerCommunications", "上传错误: " + errorBody);
                            });

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

                        // Delete local file and set success to true
                        success = file.delete();
                        notifyObservers(1);
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
     * {@link okhttp3.OkHttp} library is used for the asynchronous POST request.
     *
     * @param localTrajectory the File object of the local trajectory to be uploaded
     */
    public void uploadLocalTrajectory(File localTrajectory) {
        // 从文件名中提取时间并转换为本地时区
        String fileName = localTrajectory.getName();
        File finalTrajectory = localTrajectory;  // 初始化为原始文件
        try {
            // 提取时间部分
            String timeStr = fileName.substring(fileName.indexOf("_") + 1, fileName.lastIndexOf("."));
            SimpleDateFormat serverFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
            serverFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date utcDate = serverFormat.parse(timeStr);
            
            // 转换为本地时区
            SimpleDateFormat localFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
            localFormat.setTimeZone(java.util.TimeZone.getDefault());
            String localTimeStr = localFormat.format(utcDate);
            
            // 更新文件名
            String newFileName = fileName.replace(timeStr, localTimeStr);
            File newFile = new File(localTrajectory.getParent(), newFileName);
            if (!localTrajectory.renameTo(newFile)) {
                Log.e("ServerCommunications", "Failed to rename file");
                return;
            }
            finalTrajectory = newFile;  // 更新为新的文件
        } catch (Exception e) {
            Log.e("ServerCommunications", "Error processing timezone", e);
            // 如果出错，保持使用原始文件
        }

        final File uploadFile = finalTrajectory;  // 创建一个final变量用于匿名内部类

        // Instantiate client for HTTP requests
        OkHttpClient client = new OkHttpClient();

        // Create request body with a file to upload in multipart/form-data format
        RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", uploadFile.getName(),
                        RequestBody.create(MediaType.parse("application/json"), uploadFile))
                .build();

        // Create a POST request with the required headers
        okhttp3.Request request = new okhttp3.Request.Builder().url(uploadURL).post(requestBody)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .addHeader("Content-Type", PROTOCOL_CONTENT_TYPE).build();

        // Enqueue the request to be executed asynchronously and handle the response
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
                // Print error message, set success to false and notify observers
                e.printStackTrace();
                success = false;
                System.err.println("UPLOAD: Failure to get response");
                notifyObservers(1);
                infoResponse = "Upload failed: " + e.getMessage(); // Store error message
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());//show error message to users
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        // Print error message, set success to false and throw an exception
                        success = false;
                        notifyObservers(1);
                        String errorBody = responseBody.string();
                        System.err.println("UPLOAD unsuccessful: " + errorBody);
                        infoResponse = "Upload failed: " + errorBody;
                        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());
                        throw new IOException("UPLOAD failed with code " + response);
                    }

                    // Print the response headers
                    Headers responseHeaders = response.headers();
                    for (int i = 0, size = responseHeaders.size(); i < size; i++) {
                        System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
                    }

                    // Print a confirmation of a successful POST to API
                    System.out.println("UPLOAD SUCCESSFUL: " + responseBody.string());

                    // Delete local file, set success to true and notify observers
                    success = uploadFile.delete();
                    notifyObservers(1);
                }
            }
        });
    }

    /**
     * Perform API request for downloading a Trajectory uploaded to the server. The trajectory is
     * retrieved from a zip file, with the method accepting a position argument specifying the
     * trajectory to be downloaded. The trajectory is then converted to a protobuf object and
     * then to a JSON string to be downloaded to the device's Downloads folder.
     *
     * @param position the position of the trajectory in the zip file to retrieve
     */
    public void downloadTrajectory(int position) {
        if (entryList == null || position >= entryList.size()) {
            Log.e("ServerCommunications", "Invalid position or entryList not set");
            return;
        }
        
        // 获取轨迹信息
        Map<String, String> trajectory = entryList.get(position);
        String id = trajectory.get("id");
        String utcDateStr = trajectory.get("date_submitted");
        
        // 添加时区转换
        try {
            // 解析UTC时间字符串
            SimpleDateFormat utcFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX");
            utcFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date utcDate = utcFormat.parse(utcDateStr);
            
            // 转换为本地时间
            SimpleDateFormat localFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
            localFormat.setTimeZone(java.util.TimeZone.getDefault());
            String localDateStr = localFormat.format(utcDate);
            
            Log.d("ServerCommunications", "UTC time: " + utcDateStr);
            Log.d("ServerCommunications", "Local time: " + localDateStr);
            
            // 构建云端轨迹文件路径
            String fileName = String.format("trajectory_%s_%s.json", localDateStr, id);
            File localDirectory = new File(context.getExternalFilesDir(null), "cloud_trajectories");
            Log.d("ServerCommunications", "Cloud trajectories directory: " + localDirectory.getAbsolutePath());
            File localFile = new File(localDirectory, fileName);
            
            // 如果本地文件已存在，直接返回成功
            if (localFile.exists()) {
                Log.d("ServerCommunications", "Local file already exists: " + localFile.getAbsolutePath());
                // 更新映射文件
                updateMappingFile(id, fileName);
                new Handler(Looper.getMainLooper()).post(() -> {
                    success = true;
                    notifyObservers(1);
                });
                return;
            }
            
            // 否则从服务器下载
            downloadFile(id, localFile, localDirectory);
            
        } catch (Exception e) {
            Log.e("ServerCommunications", "Error converting timezone", e);
            success = false;
            notifyObservers(1);
        }
    }

    private void downloadFile(String id, File localFile, File localDirectory) {
        OkHttpClient client = new OkHttpClient();
        String downloadUrl = downloadURL + "&id=" + id;
        
        okhttp3.Request request = new okhttp3.Request.Builder()
            .url(downloadUrl)
            .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
            .get()
            .build();
            
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e("ServerCommunications", "Download failed", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    success = false;
                    notifyObservers(1);
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        Log.e("ServerCommunications", "Download unsuccessful: " + responseBody.string());
                        new Handler(Looper.getMainLooper()).post(() -> {
                            success = false;
                            notifyObservers(1);
                        });
                        return;
                    }

                    // 确保目录存在
                    if (!localDirectory.exists()) {
                        localDirectory.mkdirs();
                    }

                    // 保存文件
                    try (FileOutputStream fos = new FileOutputStream(localFile)) {
                        fos.write(responseBody.bytes());
                    }

                    // 更新映射文件
                    updateMappingFile(id, localFile.getName());

                    Log.d("ServerCommunications", "Download successful: " + localFile.getAbsolutePath());
                    new Handler(Looper.getMainLooper()).post(() -> {
                        success = true;
                        notifyObservers(1);
                    });
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
        Log.d("ServerCommunications", "Registering observer: " + o.getClass().getSimpleName());
        if (observers == null) {
            observers = new ArrayList<>();
        }
        observers.add(o);
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
        Log.d("ServerCommunications", "Notifying observers with success: " + success);
        if (observers != null) {
            Log.d("ServerCommunications", "Number of observers: " + observers.size());
            for (Observer observer : observers) {
                if (index == 0 && observer instanceof FilesFragment) {
                    observer.update(new String[] {infoResponse});
                } else if (index == 1) {
                    observer.update(new Boolean[] {success});
                    Log.d("ServerCommunications", "Notifying " + observer.getClass().getSimpleName() + " with success: " + success);
                }
            }
        } else {
            Log.e("ServerCommunications", "No observers registered");
        }
    }

    // 添加更新映射文件的方法
    private void updateMappingFile(String cloudId, String localFileName) {
        try {
            File mappingFile = new File(context.getExternalFilesDir(null), "trajectory_mapping.json");
            JSONObject mapping;
            
            if (mappingFile.exists()) {
                // 读取现有映射
                StringBuilder content = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(mappingFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line);
                    }
                }
                mapping = new JSONObject(content.toString());
            } else {
                // 创建新的映射
                mapping = new JSONObject();
            }
            
            // 更新映射
            mapping.put(cloudId, localFileName);
            
            // 保存映射文件
            try (FileWriter writer = new FileWriter(mappingFile)) {
                writer.write(mapping.toString());
            }
            
            Log.d("ServerCommunications", "Updated mapping file for cloud ID: " + cloudId + " -> " + localFileName);
        } catch (Exception e) {
            Log.e("ServerCommunications", "Error updating mapping file", e);
        }
    }
}
