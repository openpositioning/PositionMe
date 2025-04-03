package com.openpositioning.PositionMe;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.google.protobuf.util.JsonFormat;
import com.openpositioning.PositionMe.fragments.FilesFragment;
import com.openpositioning.PositionMe.sensors.Observable;
import com.openpositioning.PositionMe.sensors.Observer;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipInputStream;

import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * This class handles communications with the server through HTTPS.
 * It uses an OkHttpClient for making requests to the server and includes methods for sending
 * a recorded trajectory, uploading locally-stored trajectories, downloading trajectories from the
 * server and requesting information about the uploaded trajectories.
 *
 * In the sendTrajectory() method, the trajectory file is uploaded via a multipart/form-data POST.
 * Additionally, a form field "startLocationData" is added whose value is a JSON string containing
 * "longitude" and "latitude" elements. This value is set by the StartLocationFragment via the
 * setStartPosition() method.
 *
 * Authors: Michal Dvorak, Mate Stodulka
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

    // 用于存储起始位置数据（数组中 [0] 为 longitude，[1] 为 latitude）
    private float[] startPosition;

    /**
     * Setter for startPosition.
     * Call this method from StartLocationFragment when the user has determined the start marker.
     * @param startPosition a float array containing the start location's longitude and latitude.
     */
    public void setStartPosition(float[] startPosition) {
        this.startPosition = startPosition;
    }

    /**
     * Public default constructor.
     * Initializes context, connectivity manager, user preferences and observer list.
     * @param context Application context.
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
     * Outgoing communication request with a Traj.Trajectory object.
     * The recorded trajectory is converted to a byte array and stored in a local file.
     * Additionally, the start location data (with keys "longitude" and "latitude") is added as a form field
     * named "startLocationData" in the multipart POST request.
     * @param trajectory Traj.Trajectory object representing the recorded trajectory.
     */
    public void sendTrajectory(Traj.Trajectory trajectory) {

        // Convert the trajectory to a byte array
        byte[] binaryTrajectory = trajectory.toByteArray();

        // Get the directory for storing the file
        File path = context.getFilesDir();

        // Format the file name using current date and time
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yy-HH-mm-ss");
        Date date = new Date();
        File file = new File(path, "trajectory_" + dateFormat.format(date) + ".txt");

        try {
            // Write binary trajectory data to the file
            FileOutputStream stream = new FileOutputStream(file);
            stream.write(binaryTrajectory);
            stream.close();
            System.out.println("Trajectory file stored in: " + path);
        } catch (IOException ee) {
            System.err.println("Failed to store trajectory file: " + ee.getMessage());
        }

        // Check network connection
        checkNetworkStatus();

        // Check user preference for mobile data sync
        boolean enableMobileData = this.settings.getBoolean("mobile_sync", false);
        if (this.isWifiConn || (enableMobileData && isMobileConn)) {

            OkHttpClient client = new OkHttpClient();

            // 构造上传时的起始点数据 JSON 字符串
            String startLocationData = "";
            if (startPosition != null && startPosition.length >= 2) {
                try {
                    JSONObject startLocationJson = new JSONObject();
                    startLocationJson.put("longitude", startPosition[0]);
                    startLocationJson.put("latitude", startPosition[1]);
                    startLocationData = startLocationJson.toString();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // 创建 multipart 请求体，同时上传轨迹文件和起始点数据
            RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.getName(),
                            RequestBody.create(MediaType.parse("text/plain"), file))
                    .addFormDataPart("startLocationData", startLocationData)
                    .build();

            okhttp3.Request request = new okhttp3.Request.Builder().url(uploadURL).post(requestBody)
                    .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                    .addHeader("Content-Type", PROTOCOL_CONTENT_TYPE)
                    .build();

            client.newCall(request).enqueue(new okhttp3.Callback() {

                @Override
                public void onFailure(Call call, IOException e) {
                    e.printStackTrace();
                    System.err.println("Failure to get response");
                    success = false;
                    notifyObservers(1);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (ResponseBody responseBody = response.body()) {
                        if (!response.isSuccessful()) {
                            String errorBody = responseBody.string();
                            infoResponse = "Upload failed: " + errorBody;
                            new Handler(Looper.getMainLooper()).post(() ->
                                    Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());
                            System.err.println("POST error response: " + errorBody);
                            success = false;
                            notifyObservers(1);
                            throw new IOException("Unexpected code " + response);
                        }

                        Headers responseHeaders = response.headers();
                        for (int i = 0, size = responseHeaders.size(); i < size; i++) {
                            System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
                        }
                        System.out.println("Successful post response: " + responseBody.string());
                        success = file.delete();
                        notifyObservers(1);
                    }
                }
            });
        } else {
            System.err.println("No network available for upload!");
            success = false;
            notifyObservers(1);
        }
    }

    /**
     * Uploads a local trajectory file to the API server.
     * @param localTrajectory File object representing the local trajectory.
     */
    public void uploadLocalTrajectory(File localTrajectory) {
        OkHttpClient client = new OkHttpClient();

        RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", localTrajectory.getName(),
                        RequestBody.create(MediaType.parse("text/plain"), localTrajectory))
                .build();

        okhttp3.Request request = new okhttp3.Request.Builder().url(uploadURL).post(requestBody)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .addHeader("Content-Type", PROTOCOL_CONTENT_TYPE)
                .build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                success = false;
                System.err.println("UPLOAD: Failure to get response");
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
                        System.err.println("UPLOAD unsuccessful: " + errorBody);
                        infoResponse = "Upload failed: " + errorBody;
                        new Handler(Looper.getMainLooper()).post(() ->
                                Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());
                        throw new IOException("UPLOAD failed with code " + response);
                    }

                    Headers responseHeaders = response.headers();
                    for (int i = 0, size = responseHeaders.size(); i < size; i++) {
                        System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
                    }
                    System.out.println("UPLOAD SUCCESSFUL: " + responseBody.string());
                    success = localTrajectory.delete();
                    notifyObservers(1);
                }
            }
        });
    }

    /**
     * Downloads a trajectory from the server.
     * @param position Position of the trajectory in the zip file.
     * @param fileId Identifier for the file.
     */
    public void downloadTrajectory(int position, String fileId) {
        OkHttpClient client = new OkHttpClient();

        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(downloadURL)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .get()
                .build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
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
                        if (zipCount == position) {
                            break;
                        }
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

                    com.google.protobuf.util.JsonFormat.Printer printer = com.google.protobuf.util.JsonFormat.printer();
                    String receivedTrajectoryString = printer.print(receivedTrajectory);
                    System.out.println("Successful download: " + receivedTrajectoryString.substring(0, 100));

                    String storagePath = context.getFilesDir().toString();
                    String filename = "received_trajectory" + fileId + ".txt";
                    File file = new File(storagePath, filename);
                    try (FileWriter fileWriter = new FileWriter(file)) {
                        fileWriter.write(receivedTrajectoryString);
                        fileWriter.flush();
                        System.err.println("Received trajectory stored in: " + storagePath + "/" + filename);
                        new Handler(Looper.getMainLooper()).post(() ->
                                Toast.makeText(context, fileId + " download ok!", Toast.LENGTH_SHORT).show());
                    } catch (IOException ee) {
                        System.err.println("Trajectory download failed");
                    } finally {
                        zipInputStream.closeEntry();
                        byteArrayOutputStream.close();
                        zipInputStream.close();
                        inputStream.close();
                    }
                }
            }
        });
    }

    /**
     * Sends an API request for information about submitted trajectories.
     */
    public void sendInfoRequest() {
        OkHttpClient client = new OkHttpClient();

        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(infoRequestURL)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .get()
                .build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
                    infoResponse = responseBody.string();
                    System.out.println("Response received");
                    notifyObservers(0);
                }
            }
        });
    }

    /**
     * Checks the device's connection status and sets connection flags accordingly.
     */
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
}
