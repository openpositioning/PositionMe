package com.openpositioning.PositionMe;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.google.protobuf.util.JsonFormat;
import com.openpositioning.PositionMe.fragments.FilesFragment;
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

    // Static constants necessary for communications
    private static final String userKey = "ygowxLsiAJEZ89_ZS6V2Ew";
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
        // ODO: add sync delay and enforce settings
        boolean enableMobileData = this.settings.getBoolean("mobile_sync", false);
        // Check if device is connected to WiFi or to mobile data with enabled preference
        if(this.isWifiConn || (enableMobileData && isMobileConn)) {
            // Instantiate client for HTTP requests
            OkHttpClient client = new OkHttpClient();

            // Creaet a request body with a file to upload in multipart/form-data format
            RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.getName(),
                            RequestBody.create(MediaType.parse("text/plain"), file))
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
//                        System.err.println("POST error response: " + responseBody.string());

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
    /**
     * Uploads a local trajectory file to the API server in the specified format.
     * {@link okhttp3.OkHttp} library is used for the asynchronous POST request.
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
//          localTrajectory.delete();
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
                        notifyObservers(1);
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
                    System.out.println("UPLOAD SUCCESSFUL: " + responseBody.string());

                    // Delete local file, set success to true and notify observers
                    success = localTrajectory.delete();
                    notifyObservers(1);
                }
            }
        });
    }

    /**
     * Callback interface for handling the result of a download operation.
     */
    public interface DownloadResultCallback {
        void onResult(boolean success);
    }

    /**
     * Perform API request for downloading a Trajectory uploaded to the server. The trajectory is
     * retrieved from a zip file, with the method accepting an id argument specifying the
     * trajectory to be downloaded. The trajectory is then converted to a protobuf object and
     * then to a JSON string to be downloaded to the device's Downloads folder.
     *
     * @param id the id of the trajectory to be downloaded
     */
    public void downloadTrajectory(int id, DownloadResultCallback callback) {
        // Initialise OkHttp client
        OkHttpClient client = new OkHttpClient();

        // Create GET request with required header
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(downloadURL)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .get()
                .build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                // Notify failure on callback (remember to run on UI thread if needed)
                if (callback != null) {
                    callback.onResult(false);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                boolean success = false;
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful())
                        throw new IOException("Unexpected code " + response);

                    // Set target file name
                    String targetFileName = id + ".pkt";

                    // Create input streams to process the response
                    InputStream inputStream = responseBody.byteStream();
                    ZipInputStream zipInputStream = new ZipInputStream(inputStream);
                    ZipEntry zipEntry;
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    boolean fileFound = false;

                    // Search for the target file in the zip archive
                    while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                        if (zipEntry.getName().equals(targetFileName)) {
                            fileFound = true;
                            byte[] buffer = new byte[1024];
                            int bytesRead;
                            while ((bytesRead = zipInputStream.read(buffer)) != -1) {
                                byteArrayOutputStream.write(buffer, 0, bytesRead);
                            }
                            break;
                        }
                    }

                    // Check if the target file was found
                    if (!fileFound) {
                        System.err.println("File not found: " + targetFileName);
                        return; // Exit the method if the file is not found
                    }

                    // Convert to protobuf and then to JSON string
                    byte[] byteArray = byteArrayOutputStream.toByteArray();
                    Traj.Trajectory receivedTrajectory = Traj.Trajectory.parseFrom(byteArray);
                    JsonFormat.Printer printer = JsonFormat.printer();
                    String receivedTrajectoryString = printer.print(receivedTrajectory);
                    System.out.println("Successful download: " + receivedTrajectoryString.substring(0, 100));

                    // Determine storage directory (for Android versions)
                    File storageDir = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        storageDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                        if (storageDir == null) {
                            storageDir = context.getFilesDir();
                        }
                    } else {
                        storageDir = context.getFilesDir();
                    }
                    File file = new File(storageDir, "received_trajectory.txt");

                    // Write the downloaded data to a file
                    try (FileWriter fileWriter = new FileWriter(file)) {
                        fileWriter.write(receivedTrajectoryString);
                        fileWriter.flush();
                        System.out.println("Received trajectory stored in: " + storageDir.getAbsolutePath());

                        success = true;// Set success to true
                    } catch (IOException ee) {
                        System.err.println("Trajectory download failed");
                    } finally {
                        zipInputStream.closeEntry();
                        byteArrayOutputStream.close();
                        zipInputStream.close();
                        inputStream.close();
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                } finally {
                    // Notify the callback of the result
                    if (callback != null) {
                        callback.onResult(success);
                    }
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
