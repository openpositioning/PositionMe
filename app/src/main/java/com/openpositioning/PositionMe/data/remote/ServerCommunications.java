package com.openpositioning.PositionMe.data.remote;

import static com.openpositioning.PositionMe.utils.UtilConstants.API_KEY_MASTER;
import static com.openpositioning.PositionMe.utils.UtilConstants.API_POST_LOGIN;
import static com.openpositioning.PositionMe.utils.UtilConstants.API_POST_SIGN_UP;
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
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.presentation.fragment.FilesFragment;
import com.openpositioning.PositionMe.presentation.fragment.LoginFragment;
import com.openpositioning.PositionMe.presentation.fragment.RegisterFragment;
import com.openpositioning.PositionMe.sensors.Observable;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.sensors.Wifi;
import com.openpositioning.PositionMe.utils.IndoorMapManager;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttp;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * This class handles communications with the server through HTTPs. The class uses an {@link
 * OkHttpClient} for making requests to the server. The class includes methods for interfacing with
 * the OpenPosition REST API.
 *
 * <p>The key methods {@link ServerCommunications#sendRequestGET(String, String, REQUEST, Object)
 * sendRequestGET()} and {@link ServerCommunications#sendRequestPOST(String, RequestBody, String,
 * String, REQUEST, Object) sendRequestPOST()} are used to sent HTTP GET and POST requests
 * respectively, and are called by public methods which format the requests as required.
 *
 * <p>As the OpenPosition API evolves, these two methods can be extended to support handling more
 * requests to the server.
 *
 * @author Michal Dvorak
 * @author Mate Stodulka
 */
public class ServerCommunications implements Observable {
    private static final String TAG = "ServerCommunications";
    public static Map<String, JSONObject> downloadRecords = new HashMap<>();

    // Application context for handling permissions and devices
    private static final int OBSERVER_INDEX_FILES = 0;
    private static final int OBSERVER_INDEX_MAIN = 1;
    private static final int OBSERVER_INDEX_INDOOR_MAPS = 2;
    private static final int OBSERVER_INDEX_LOGIN = 3;
    private static final int OBSERVER_INDEX_REGISTER = 4;
    private static final int TRAJECTORY_REQUEST_BUFFER = 10;

    private final Context context;

    // Network status checking
    private ConnectivityManager connMgr;
    private boolean isWifiConn;
    private boolean isMobileConn;
    private SharedPreferences settings;
    private LoginManager loginManager;

    private String infoResponse;
    private boolean success;
    private Set<Observer> observers;
    private long lastTime = 0;

    // Enum to iterate through possible API requests
    private enum REQUEST {
        GET_TRAJECTORIES_LIST,
        GET_TRAJECTORY_SINGLE,
        POST_TRAJECTORY_RECORDED,
        POST_TRAJECTORY_LOCAL,
        POST_FLOOR_PLANS,
        POST_SIGN_UP,
        POST_LOG_IN
    }

    private String ERROR_CODE_NO_SERVER;
    private String ERROR_MESSAGE_NO_SERVER;

    /**
     * Public default constructor of {@link ServerCommunications}. The constructor saves context,
     * initialises a {@link ConnectivityManager}, {@link Observer}, and gets the user preferences.
     * Boolean variables storing WiFi and Mobile Data connection status are initialised to false.
     *
     * @param context application context for handling permissions and devices.
     */
    public ServerCommunications(Context context) {
        this.context = context;
        this.connMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.isWifiConn = false;
        this.isMobileConn = false;
        checkNetworkStatus();

        ERROR_CODE_NO_SERVER = context.getString(R.string.errorCodeNoServerResponse);
        ERROR_MESSAGE_NO_SERVER = context.getString(R.string.errorMessageNoServerResponse);
        this.observers = new LinkedHashSet<>();
        loginManager = LoginManager.getInstance();
    }

    //////////////////////////////////////////////////////////////////////////////

    //////////////////////////////////////////////////////////////////////////////

    /**
     * Sends an HTTP GET request to the provided URL, and handles responses.
     *
     * @param url The URL of the API
     * @param headerAccept The header string defining the response data type expected
     * @param requestType An encoded value used to determine how to process the server's response
     * @param additionalData Additional data used in processing the server's response. Varies from
     *     request to request
     * @see REQUEST
     */
    private void sendRequestGET(
            String url, String headerAccept, REQUEST requestType, Object additionalData) {
        OkHttpClient client = new OkHttpClient();
        Request request =
                new Request.Builder().url(url).addHeader("accept", headerAccept).get().build();
        client.newCall(request)
                .enqueue(
                        new Callback() {
                            @Override
                            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                                // Notify users
                                Log.w(TAG, "Error: " + e.getMessage());
                                String errorMessage = ERROR_MESSAGE_NO_SERVER;
                                success = false;
                                new Handler(Looper.getMainLooper())
                                        .post(
                                                () -> {
                                                    Toast.makeText(
                                                                    context,
                                                                    errorMessage,
                                                                    Toast.LENGTH_SHORT)
                                                            .show();
                                                });
                                infoResponse = ERROR_CODE_NO_SERVER + ":" + errorMessage;

                                switch (requestType) {
                                    case GET_TRAJECTORIES_LIST:
                                        notifyObservers(OBSERVER_INDEX_FILES);
                                        return;
                                    case GET_TRAJECTORY_SINGLE:
                                        return;
                                    default:
                                        return;
                                }
                            }

                            @Override
                            public void onResponse(@NonNull Call call, @NonNull Response response)
                                    throws IOException {
                                try (ResponseBody responseBody = response.body()) {
                                    String responseCode = String.valueOf(response.code());
                                    Log.i(TAG, "GET response: Code " + responseCode);

                                    if (!response.isSuccessful()) {
                                        String errorBody = responseBody.string();
                                        Log.w(TAG, "Error: " + errorBody);
                                        infoResponse = responseCode + ":" + errorBody;
                                        success = false;

                                        switch (requestType) {
                                            case GET_TRAJECTORIES_LIST:
                                                parseErrorResponse(infoResponse);
                                                notifyObservers(OBSERVER_INDEX_FILES);
                                                return;
                                            case GET_TRAJECTORY_SINGLE:
                                                parseErrorResponse(infoResponse);
                                                return;
                                            default:
                                                return;
                                        }
                                    }

                                    switch (requestType) {
                                        case GET_TRAJECTORIES_LIST:
                                            // Save the requested information from the response body
                                            infoResponse = responseBody.string();
                                            success = true;
                                            notifyObservers(OBSERVER_INDEX_FILES);
                                            return;
                                        case GET_TRAJECTORY_SINGLE:
                                            @SuppressWarnings("unchecked")
                                            List<Object> data = (List<Object>) additionalData;

                                            String id = data.get(0).toString();
                                            int position = (int) data.get(1);
                                            String dateSubmitted = data.get(2).toString();

                                            processDownloadedTrajectory(
                                                    responseBody, id, position, dateSubmitted);
                                            return;
                                        default:
                                            return;
                                    }
                                }
                            }
                        });
    }

    /**
     * Sends an HTTP POST request to the provided URL, and handles responses.
     *
     * @param url The URL of the API
     * @param payload The data to be sent in the POST request
     * @param headerAccept The header string defining the response data type expected
     * @param headerContentType The header string defining the data type of the payload
     * @param requestType An encoded value used to determine how to process the server's response
     * @param additionalData Additional data used in processing the server's response. Varies from
     *     request to request
     * @see REQUEST
     */
    private void sendRequestPOST(
            String url,
            RequestBody payload,
            String headerAccept,
            String headerContentType,
            REQUEST requestType,
            Object additionalData) {
        OkHttpClient client = new OkHttpClient();
        Request request =
                new Request.Builder()
                        .url(url)
                        .post(payload)
                        .addHeader("accept", headerAccept)
                        .addHeader("Content-Type", headerContentType)
                        .build();
        client.newCall(request)
                .enqueue(
                        new Callback() {

                            /** When the attempt to send the request has failed, notify the user. */
                            @Override
                            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                                Log.w(TAG, "Error: " + e.getMessage());
                                String errorMessage = ERROR_MESSAGE_NO_SERVER;
                                success = false;
                                new Handler(Looper.getMainLooper())
                                        .post(
                                                () -> {
                                                    Toast.makeText(
                                                                    context,
                                                                    errorMessage,
                                                                    Toast.LENGTH_SHORT)
                                                            .show();
                                                });
                                infoResponse = ERROR_CODE_NO_SERVER + ":" + errorMessage;

                                switch (requestType) {
                                    case POST_LOG_IN:
                                        notifyObservers(OBSERVER_INDEX_LOGIN);
                                        return;
                                    case POST_SIGN_UP:
                                        notifyObservers(OBSERVER_INDEX_REGISTER);
                                        return;
                                    case POST_TRAJECTORY_RECORDED:
                                    case POST_TRAJECTORY_LOCAL:
                                        notifyObservers(OBSERVER_INDEX_MAIN);
                                        return;
                                    case POST_FLOOR_PLANS:
                                        notifyObservers(OBSERVER_INDEX_INDOOR_MAPS);
                                        return;
                                    default:
                                        return;
                                }
                            }

                            /**
                             * Process the response from the server, based on it's HTTP response
                             * code.
                             *
                             * <p>For non-error codes (2XX-3XX), process the response.
                             *
                             * <p>For error codes (4XX), send the code to the {@link Observer}
                             * instance to handle directly.
                             */
                            @Override
                            public void onResponse(@NonNull Call call, @NonNull Response response)
                                    throws IOException {
                                try (ResponseBody responseBody = response.body()) {
                                    String responseCode = String.valueOf(response.code());
                                    Log.i(TAG, "POST response: Code " + responseCode);

                                    if (!response.isSuccessful()) {
                                        String errorBody = responseBody.string();
                                        Log.w(TAG, "Error: " + errorBody);
                                        infoResponse = responseCode + ":" + errorBody;
                                        success = false;

                                        switch (requestType) {
                                            case POST_LOG_IN:
                                                parseErrorResponse(infoResponse);
                                                notifyObservers(OBSERVER_INDEX_LOGIN);
                                                return;
                                            case POST_SIGN_UP:
                                                parseErrorResponse(infoResponse);
                                                notifyObservers(OBSERVER_INDEX_REGISTER);
                                                return;
                                            case POST_TRAJECTORY_RECORDED:
                                            case POST_TRAJECTORY_LOCAL:
                                                // Invalid trajectories should not be saved
                                                File badTrajectoryLocal = (File) additionalData;
                                                badTrajectoryLocal.delete();
                                                Log.d(
                                                        TAG,
                                                        "Trajectory "
                                                                + badTrajectoryLocal.getName()
                                                                + " deleted");
                                                Log.d(TAG, "Sending " + errorBody);
                                                notifyObservers(OBSERVER_INDEX_MAIN);
                                                return;
                                            case POST_FLOOR_PLANS:
                                                parseErrorResponse(infoResponse);
                                                notifyObservers(OBSERVER_INDEX_INDOOR_MAPS);
                                                return;
                                            default:
                                                return;
                                        }
                                    }
                                    switch (requestType) {
                                        case POST_LOG_IN:
                                            infoResponse = responseBody.string();
                                            success = true;
                                            notifyObservers(OBSERVER_INDEX_LOGIN);
                                            return;
                                        case POST_SIGN_UP:
                                            infoResponse = responseBody.string();
                                            success = true;
                                            notifyObservers(OBSERVER_INDEX_REGISTER);
                                            return;
                                        case POST_TRAJECTORY_RECORDED:
                                            File originalFile = (File) additionalData;
                                            infoResponse = responseBody.string();
                                            success = processTrajectoryResponse(originalFile);
                                            notifyObservers(OBSERVER_INDEX_MAIN);
                                            return;
                                        case POST_TRAJECTORY_LOCAL:
                                            File localFile = (File) additionalData;
                                            infoResponse = responseBody.string();
                                            success = localFile.delete();
                                            notifyObservers(OBSERVER_INDEX_MAIN);
                                            return;
                                        case POST_FLOOR_PLANS:
                                            infoResponse = responseBody.string();
                                            success = true;
                                            notifyObservers(OBSERVER_INDEX_INDOOR_MAPS);
                                            return;
                                        default:
                                            return;
                                    }
                                }
                            }
                        });
    }

    /**
     * Error responses from the server share the same output format, so this function will parse
     * that response and produce a user-friendly {@link Toast} explaining the problem.
     *
     * @param infoString String formatted as "errorCode:JSONResponse"
     */
    private void parseErrorResponse(String infoString) {
        String[] errorElements = infoString.split(":", 2);
        String errorCode = errorElements[0];
        String errorCause = errorElements[1];

        // Only perform JSON data extraction if response is in JSON format
        if (!errorCause.contains("{")) {
            Log.i(TAG, "\"" + errorCause + "\" is not a JSON response");
        } else {
            try {
                JSONObject jsonObject = new JSONObject(errorCause);
                JSONArray detailArray = jsonObject.optJSONArray("detail");
                if (detailArray != null) {
                    for (int i = 0; i < detailArray.length(); i++) {
                        JSONObject problem = detailArray.getJSONObject(i);
                        String type = problem.getString("type");
                        if (type.contains("value_error")) {
                            String badValue = type.split("\\.", 2)[1];
                            if (badValue.equals("missing")) {
                                badValue = problem.getJSONArray("loc").getString(1);
                                badValue =
                                        badValue.substring(0, 1).toUpperCase()
                                                + badValue.substring(1);
                                errorCause = badValue + " " + problem.getString("msg");
                            } else {
                                badValue =
                                        badValue.substring(0, 1).toUpperCase()
                                                + badValue.substring(1);
                                errorCause =
                                        badValue + " " + problem.getString("msg").split(" ", 2)[1];
                            }
                        } else {
                            errorCause = problem.getString("msg");
                        }
                    }
                } else {
                    // If error response is not JSON format, print as-is
                    errorCause = jsonObject.getString("detail");
                }
            } catch (JSONException e) {
                Log.w(TAG, e.getMessage());
                errorCause = "Unknown error";
            }
        }

        String errorMessage = errorCause + " (HTTP Error " + errorCode + ")";
        new Handler(Looper.getMainLooper())
                .post(
                        () -> {
                            Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show();
                        });
    }

    //////////////////////////////////////////////////////////////////////////////

    /**
     * Handle the server response from uploading a newly recorded trajectory, and delete the
     * trajectory file saved on disk during the recording.
     *
     * <p>
     *
     * @param originalFile The trajectory file created during the recording
     * @return True if the processing is completed successfully. False otherwise.
     */
    private boolean processTrajectoryResponse(File originalFile) {
        // Copy the file to the Downloads folder
        File downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File downloadFile = new File(downloadsDir, originalFile.getName());
        try {
            copyFile(originalFile, downloadFile);
            Log.i(TAG, "Trajectory file copied to Downloads: " + downloadFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy file to Downloads: " + e.getMessage());
        }

        return originalFile.delete();
    }

    /**
     * Helper function to copy a {@link File file's} contents from one location to another.
     *
     * @param src The source file
     * @param dst The destination file
     * @see ServerCommunications#processTrajectoryResponse(File)
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
     * Outgoing communication request with a {@link Traj trajectory} object. The recorded trajectory
     * is passed to the method. It is processed into the right format for sending to the API server.
     *
     * @param trajectory Traj object matching all the timing and formal restrictions.
     * @param campaign The building associated with the route
     */
    public void sendTrajectory(Traj.Trajectory trajectory, String campaign) {
        new Handler(Looper.getMainLooper())
                .post(
                        () -> {
                            Toast.makeText(
                                            context,
                                            "Now uploading '" + campaign + "' trajectory...",
                                            Toast.LENGTH_SHORT)
                                    .show();
                        });
        logDataSize(trajectory);

        File file = convertTrajectoryToFile(trajectory);

        // Only proceed if campaign is valid
        if (campaign.isBlank() || campaign.equals(BUILDING_NAME_OUTSIDE)) {
            String message = "Invalid campaign ('" + campaign + "') - Cancelling upload";
            Log.w(TAG, message);
            new Handler(Looper.getMainLooper())
                    .post(
                            () -> {
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                            });
            return;
        }

        // Check connections available before sending data
        checkNetworkStatus();

        // Check if user preference allows for syncing with mobile data
        // TODO: add sync delay and enforce settings
        boolean enableMobileData = this.settings.getBoolean("mobile_sync", false);

        // Check if device is connected to WiFi or to mobile data with enabled preference
        if (!this.isWifiConn && !(enableMobileData && isMobileConn)) {
            // If the device is not connected to network or allowed to send,
            // do not send trajectory and notify observers and user
            String errorMessage = "No uploading allowed right now!";
            infoResponse = ERROR_CODE_NO_SERVER + ":" + errorMessage;
            Log.w(TAG, infoResponse);
            success = false;
            new Handler(Looper.getMainLooper())
                    .post(
                            () -> {
                                Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show();
                            });
            notifyObservers(OBSERVER_INDEX_MAIN);
            return;
        }
        // Create a request body with a file to upload in multipart/form-data format
        RequestBody requestBody =
                new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart(
                                "file",
                                file.getName(),
                                RequestBody.create(MediaType.parse("text/plain"), file))
                        .build();

        // Create a POST request with the required headers
        String uploadURL = createTrajectoryUploadURL(campaign);
        sendRequestPOST(
                uploadURL,
                requestBody,
                PROTOCOL_APP_JSON,
                PROTOCOL_MULTIPART,
                REQUEST.POST_TRAJECTORY_RECORDED,
                file);
    }

    /**
     * Helper function to convert the recorded {@link Traj trajectory} object into a {@link File}
     * for uploading
     *
     * <p>
     *
     * @param trajectory The recorded trajectory object.
     * @return A {@link File} prepared for uploading.
     */
    private File convertTrajectoryToFile(Traj.Trajectory trajectory) {
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

        Log.i(TAG, path.toString());

        // Format the file name according to date
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yy-HH-mm-ss");
        Date date = new Date();
        File file = new File(path, "trajectory_" + dateFormat.format(date) + ".txt");

        try {
            // Write the binary data to the file
            FileOutputStream stream = new FileOutputStream(file);
            stream.write(binaryTrajectory);
            stream.close();
            Log.i(TAG, "Recorded binary trajectory for debugging stored in: " + path);
        } catch (IOException e) {
            // Catch and print if writing to the file fails
            Log.e(TAG, "Storing of recorded binary trajectory failed: " + e.getMessage());
        }

        return file;
    }

    /**
     * Uploads a local trajectory file to the API server in the specified format. {@link OkHttp}
     * library is used for the asynchronous POST request.
     *
     * @param localTrajectory the File object of the local trajectory to be uploaded
     * @param campaign The building associated with the route
     */
    public void uploadLocalTrajectory(File localTrajectory, String campaign) {
        // Only proceed if campaign is valid
        if (campaign.isBlank() || campaign.equals(BUILDING_NAME_OUTSIDE)) {
            String message = "Invalid campaign ('" + campaign + "') - Cancelling upload";
            Log.w(TAG, message);
            new Handler(Looper.getMainLooper())
                    .post(
                            () -> {
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                            });
            return;
        }

        if (!localTrajectory.exists()) {
            Log.e(TAG, "Unable to use file!");
            new Handler(Looper.getMainLooper())
                    .post(
                            () -> {
                                Toast.makeText(
                                                context,
                                                "Trajectory has been deleted. Please refresh the"
                                                        + " view.",
                                                Toast.LENGTH_SHORT)
                                        .show();
                            });
            return;
        }

        // Robustness improvement
        RequestBody fileRequestBody;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                byte[] fileBytes = Files.readAllBytes(localTrajectory.toPath());
                fileRequestBody = RequestBody.create(MediaType.parse("text/plain"), fileBytes);
            } catch (IOException e) {
                Log.e(TAG, "Error: " + e.getMessage());
                // if failed, use File object to construct RequestBody
                fileRequestBody =
                        RequestBody.create(MediaType.parse("text/plain"), localTrajectory);
            }
        } else {
            fileRequestBody = RequestBody.create(MediaType.parse("text/plain"), localTrajectory);
        }

        // Create request body with a file to upload in multipart/form-data format
        RequestBody requestBody =
                new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", localTrajectory.getName(), fileRequestBody)
                        .build();

        // Create a POST request with the required headers
        String uploadURL = createTrajectoryUploadURL(campaign);

        sendRequestPOST(
                uploadURL,
                requestBody,
                PROTOCOL_APP_JSON,
                PROTOCOL_MULTIPART,
                REQUEST.POST_TRAJECTORY_LOCAL,
                localTrajectory);
    }

    /**
     * Loads download records from a JSON file and updates the downloadRecords map. If the file
     * exists, it reads the JSON content and populates the map.
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
                        Log.e(
                                TAG,
                                "[" + e.getMessage() + "] Error loading record with key: " + key);
                    }
                }

                Log.i(TAG, "Loaded downloadRecords: " + downloadRecords);

            } catch (Exception e) {
                Log.e(TAG, "Error: " + e.getMessage());
            }
        } else {
            Log.i(TAG, "Download_records.json not found in app-specific directory.");
        }
    }

    /**
     * Saves a download record to a JSON file. The method creates or updates the JSON file with the
     * provided details.
     *
     * @param startTimestamp the start timestamp of the trajectory
     * @param fileName the name of the file
     * @param id the ID of the trajectory
     * @param dateSubmitted the date the trajectory was submitted
     */
    private void saveDownloadRecord(
            long startTimestamp, String fileName, String id, String dateSubmitted) {
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
                    Log.e(TAG, "Failed to create file: " + recordsFile.getAbsolutePath());
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
                jsonObject =
                        jsonBuilder.length() > 0
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

            Log.i(TAG, "Download record saved successfully at: " + recordsFile.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "Error saving download record: " + e.getMessage());
        }
    }

    /**
     * Perform API request for downloading a Trajectory uploaded to the server. The trajectory is
     * retrieved from a zip file, with the method accepting a position argument specifying the
     * trajectory to be downloaded. The trajectory is then converted to a protobuf object and then
     * to a JSON string to be downloaded to the device's Downloads folder.
     *
     * @param position The position of the trajectory in the zip file to retrieve
     * @param id The ID of the trajectory
     * @param dateSubmitted The date the trajectory was submitted
     */
    public void downloadTrajectory(int position, String id, String dateSubmitted) {
        // Load existing records from app-specific directory
        loadDownloadRecords();

        String url =
                URL_GET_TRAJECTORIES_HEAD
                        + "/"
                        + loginManager.getUserKey()
                        + "?skip=0&limit="
                        + (position + TRAJECTORY_REQUEST_BUFFER)
                        + URL_GET_TRAJECTORIES_TAIL;

        List<Object> additionalData = new ArrayList<>();
        additionalData.add(id);
        additionalData.add(position);
        additionalData.add(dateSubmitted);

        // Enqueue the GET request for asynchronous execution
        sendRequestGET(url, PROTOCOL_APP_JSON, REQUEST.GET_TRAJECTORY_SINGLE, additionalData);
        new Handler(Looper.getMainLooper())
                .post(
                        () -> {
                            Toast.makeText(
                                            context,
                                            "Request sent for Trajectory " + id,
                                            Toast.LENGTH_SHORT)
                                    .show();
                        });
    }

    /**
     * Processes the downloaded trajectory data such that it can be replayed
     *
     * @param responseBody The raw response from the server
     * @param id The ID of the trajectory being processed
     * @param position The position within the ZIP file's array of the trajectory requested
     * @param dateSubmitted The date on which the trajectory was recorded
     */
    private void processDownloadedTrajectory(
            ResponseBody responseBody, String id, int position, String dateSubmitted) {
        try {
            new Handler(Looper.getMainLooper())
                    .post(
                            () ->
                                    Toast.makeText(
                                                    context,
                                                    "Trajectory "
                                                            + id
                                                            + " received!\n"
                                                            + "Now parsing trajectory...",
                                                    Toast.LENGTH_SHORT)
                                            .show());

            // Extract the nth entry from the zip
            InputStream inputStream = responseBody.byteStream();
            ZipInputStream zipInputStream = new ZipInputStream(inputStream);

            int zipCount = 0;
            while ((zipInputStream.getNextEntry()) != null) {
                if (zipCount == position) {
                    // break if zip entry position matches the desired position
                    break;
                }
                zipCount++;
            }

            // Initialise a byte array output stream
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

            // Read the zipped data and write it to the byte array output
            // stream
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = zipInputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead);
            }

            // Convert the byte array to protobuf
            byte[] byteArray = byteArrayOutputStream.toByteArray();
            Log.d(TAG, "byteArray = " + Arrays.toString(byteArray));
            Traj.Trajectory receivedTrajectory = Traj.Trajectory.parseFrom(byteArray);

            // Print a message in the console
            long startTimestamp = receivedTrajectory.getStartTimestamp();
            String fileName = "trajectory_" + dateSubmitted + ".txt";
            Log.i(TAG, fileName + " received");

            // Inspect the size of the received trajectory
            logDataSize(receivedTrajectory);

            // Place the file in your app-specific "Downloads" folder
            File appSpecificDownloads =
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (appSpecificDownloads != null && !appSpecificDownloads.exists()) {
                Log.d(TAG, "Creating downloads directory...");
                appSpecificDownloads.mkdirs();
            }

            File file = new File(appSpecificDownloads, fileName);

            try (FileWriter fileWriter = new FileWriter(file)) {
                String receivedTrajectoryString = JsonFormat.printer().print(receivedTrajectory);
                fileWriter.write(receivedTrajectoryString);
                fileWriter.flush();
                Log.i(TAG, "Received trajectory stored in: " + file.getAbsolutePath());

                // Save the download record
                saveDownloadRecord(startTimestamp, fileName, id, dateSubmitted);
                new Handler(Looper.getMainLooper())
                        .post(
                                () ->
                                        Toast.makeText(
                                                        context,
                                                        "Trajectory "
                                                                + id
                                                                + " is ready for replay!",
                                                        Toast.LENGTH_SHORT)
                                                .show());
            } finally {
                // Close all streams and entries to release resources
                zipInputStream.closeEntry();
                byteArrayOutputStream.close();
                zipInputStream.close();
                inputStream.close();
                Log.d(TAG, "Files cleaned up");
            }

        } catch (IOException e) {
            Log.e(TAG, "Trajectory download failed: " + e.getMessage());
            new Handler(Looper.getMainLooper())
                    .post(
                            () ->
                                    Toast.makeText(
                                                    context,
                                                    "There was a problem"
                                                            + " parsing trajectory "
                                                            + id
                                                            + "\nPlease try again later",
                                                    Toast.LENGTH_SHORT)
                                            .show());
        }

        // Refresh the list of download options
        loadDownloadRecords();
    }

    /**
     * API request for information about submitted trajectories.
     *
     * <p>
     *
     * @param baseURL The URL of the API GET request
     */
    public void requestPathsFromServer(String baseURL) {
        String requestURL = baseURL + "/" + loginManager.getUserKey() + "?key=" + API_KEY_MASTER;
        sendRequestGET(requestURL, PROTOCOL_APP_JSON, REQUEST.GET_TRAJECTORIES_LIST, null);
    }

    /**
     * Retrieve floor plans for nearby buildings from the server
     *
     * @param position Current position during recording
     * @param wifiAPs List of nearby wireless access points
     */
    public void requestFloorplans(@NonNull LatLng position, @NonNull List<Wifi> wifiAPs) {
        // Simple delay window between floor plan API calls
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastTime < FLOOR_PLAN_POLL_TIME_MS) {
            return;
        } else {
            lastTime = currentTime;
        }

        String url =
                URL_POST_FLOORPLANS + "/" + loginManager.getUserKey() + "?key=" + API_KEY_MASTER;

        // Generate list of AP MAC addresses
        List<String> aps = new ArrayList<>();
        for (Wifi ap : wifiAPs) {
            aps.add(String.valueOf(ap.getBssid()));
        }

        // Construct payload for request
        MediaType JSON = MediaType.get(PROTOCOL_APP_JSON + "; charset=utf-8");
        JSONObject payload = new JSONObject();
        try {
            payload.put("lat", position.latitude);
            payload.put("lon", position.longitude);
            payload.put("macs", new JSONArray(aps));
        } catch (JSONException ignore) {
            return;
        }
        RequestBody requestBody = RequestBody.create(payload.toString(), JSON);

        sendRequestPOST(
                url,
                requestBody,
                PROTOCOL_APP_JSON,
                PROTOCOL_APP_JSON,
                REQUEST.POST_FLOOR_PLANS,
                null);
    }

    public void registerUserDetails(String username, String email, String password) {
        String url = URL_API + API_POST_SIGN_UP;

        // Construct payload for request
        MediaType JSON = MediaType.get(PROTOCOL_APP_JSON + "; charset=utf-8");
        JSONObject payload = new JSONObject();
        try {
            payload.put("username", username);
            payload.put("email", email);
            payload.put("password", password);
        } catch (JSONException ignore) {
            return;
        }
        RequestBody requestBody = RequestBody.create(payload.toString(), JSON);

        sendRequestPOST(
                url, requestBody, PROTOCOL_APP_JSON, PROTOCOL_APP_JSON, REQUEST.POST_SIGN_UP, null);
    }

    public void logInUser(String email, String password) {
        String url = URL_API + API_POST_LOGIN;

        // Construct payload for request
        MediaType JSON = MediaType.get(PROTOCOL_APP_JSON + "; charset=utf-8");
        JSONObject payload = new JSONObject();
        try {
            payload.put("email", email);
            payload.put("password", password);
        } catch (JSONException ignore) {
            return;
        }

        RequestBody requestBody = RequestBody.create(payload.toString(), JSON);

        sendRequestPOST(
                url, requestBody, PROTOCOL_APP_JSON, PROTOCOL_APP_JSON, REQUEST.POST_LOG_IN, null);
    }

    /**
     * This method checks the device's connection status. It sets boolean variables depending on the
     * type of active network connection.
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
     * Create the trajectory upload URL by inserting the building the route is related to
     *
     * @param campaign The name of the building which the recording is currently associated with
     * @return The newly formed URL including the campaign field
     */
    private String createTrajectoryUploadURL(String campaign) {
        return URL_API
                + API_POST_TRAJECTORIES
                + "/"
                + campaign
                + "/"
                + loginManager.getUserKey()
                + "/?key="
                + API_KEY_MASTER;
    }

    /**
     * Logs all the sensor information in the trajectory
     *
     * @param trajectory The trajectory being logged
     */
    private void logDataSize(Traj.Trajectory trajectory) {
        Log.i(TAG, "IMU Data size: " + trajectory.getImuDataCount());
        Log.i(TAG, "Position Data size: " + trajectory.getMagnetometerDataCount());
        Log.i(TAG, "Pressure Data size: " + trajectory.getPressureDataCount());
        Log.i(TAG, "Light Data size: " + trajectory.getLightDataCount());
        Log.i(TAG, "GNSS Data size: " + trajectory.getGnssDataCount());
        Log.i(TAG, "WiFi Data size: " + trajectory.getWifiFingerprintsCount());
        Log.i(TAG, "APS Data size: " + trajectory.getApsDataCount());
        Log.i(TAG, "PDR Data size: " + trajectory.getPdrDataCount());
    }

    //////////////////////////////////////////////////////////////////////////////

    /**
     * {@inheritDoc}
     *
     * <p>Implement default method from Observable Interface to add new observers to the list of
     * registered observers.
     *
     * <p>Note that only one type of each {@link Observer} class may be registered.
     *
     * @param o Classes which implement the Observer interface to receive updates from the class.
     */
    @Override
    public void registerObserver(Observer o) {
        for (Observer obs : observers) {
            if (obs.getClass().getSimpleName().equals(o.getClass().getSimpleName())) {
                observers.remove(obs);
            }
        }

        if (observers.add(o)) {
            Log.i(TAG, "Added observer of type " + o.getClass().getSimpleName());
        } else {
            Log.w(TAG, "Failed to add Observer of type " + o.getClass().getSimpleName());
        }
        Log.d(TAG, "Observers currently in list:");
        observers.forEach(observer -> Log.d(TAG, " " + observer.getClass().getSimpleName()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Method for notifying all registered observers. The observer is notified based on the index
     * passed to the method.
     *
     * @param index Index for identifying the observer to be notified.
     */
    @Override
    public void notifyObservers(int index) {
        if (observers.isEmpty()) {
            Log.w(TAG, "No observers registered! Update will be lost.");
            return;
        }
        for (Observer o : observers) {
            if (index == OBSERVER_INDEX_FILES && o instanceof FilesFragment) {
                o.update(new Object[] {success, infoResponse});
            } else if (index == OBSERVER_INDEX_MAIN && o instanceof MainActivity) {
                o.update(new Object[] {success, infoResponse});
            } else if (index == OBSERVER_INDEX_INDOOR_MAPS && o instanceof IndoorMapManager) {
                o.update(new Object[] {success, infoResponse});
            } else if (index == OBSERVER_INDEX_LOGIN && o instanceof LoginFragment) {
                o.update(new Object[] {success, infoResponse});
            } else if (index == OBSERVER_INDEX_REGISTER && o instanceof RegisterFragment) {
                o.update(new Object[] {success, infoResponse});
            } else {
                Log.w(
                        TAG,
                        "Observer type "
                                + o.getClass().getSimpleName()
                                + " does not match observer index "
                                + index);
            }
        }
    }
}
