package com.openpositioning.PositionMe.data.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.openpositioning.PositionMe.Traj;
import com.google.protobuf.util.JsonFormat;
import com.openpositioning.PositionMe.presentation.activity.MainActivity;
import com.openpositioning.PositionMe.presentation.fragment.FilesFragment;
import com.openpositioning.PositionMe.sensors.Observable;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.utils.VenueSelectionHelper;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
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

/**
 * Handles communications with the server through HTTPs.
 *
 * New code guide:
 * 1. Campaign-aware upload entry points for live and local trajectories.
 * 2. Compatibility upload fallback strategy for server payload formats.
 * 3. Download helpers for ZIP trajectory exports and local record tracking.
 * 4. Building and floor-plan requests for indoor map rendering.
 */
public class ServerCommunications implements Observable {

    private static final String TAG = "ServerCommunications";
    private static final String LEGACY_UPLOAD_CAMPAIGN = "murchison_house";
    private static final MediaType PROTO_MEDIA_TYPE = MediaType.parse("application/octet-stream");
    private static final double MIN_SERVER_UPLOAD_DURATION_SEC = 30.0;


    public static Map<String, JSONObject> downloadRecords = new HashMap<>();
    private final Context context;
    private Traj.Trajectory trajectory;

    private String infoResponse;
    private boolean success;
    private List<Observer> observers;

    private static final String userKey = "LY31NlnGAe9vN-HvQJWTZg";
    private static final String masterKey = "ewireless";

    private static final String uploadFallbackURL =
            "https://openpositioning.org/api/live/trajectory/upload/" + userKey
                    + "/?key=" + masterKey;
    private static final String downloadBaseURL =
            "https://openpositioning.org/api/live/trajectory/download/" + userKey;
    private static final String infoRequestURL =
            "https://openpositioning.org/api/live/users/trajectories/" + userKey
                    + "?key=" + masterKey;

    private static final String floorPlanRequestURL =
            "https://openpositioning.org/api/live/floorplan/request/" + userKey
                    + "?key=" + masterKey;

    private static final String PROTOCOL_ACCEPT_TYPE = "application/json";

    public ServerCommunications(Context context) {
        this.context = context;
        this.observers = new ArrayList<>();
    }

    public void sendInfo(Traj.Trajectory trajectory) {
        this.trajectory = trajectory;
    }

    /**
     * Uploads a freshly recorded trajectory using the selected venue campaign.
     */
    public void sendTrajectory(Traj.Trajectory sentTrajectory, String campaign) {
        String validationMessage = validateTrajectoryForServerUpload(sentTrajectory);
        if (validationMessage != null) {
            success = false;
            infoResponse = validationMessage;
            Log.w(TAG, infoResponse);
            notifyObservers(1);
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context, infoResponse, Toast.LENGTH_LONG).show());
            return;
        }

        String resolvedCampaign = resolveCampaign(campaign);
        Log.i(TAG, "Uploading trajectory to campaign=" + resolvedCampaign
                + " (test_points=" + sentTrajectory.getTestPointsCount() + ")");

        String fileName = "upload_" + System.currentTimeMillis() + ".proto";
        byte[] rawBytes = sentTrajectory.toByteArray();
        byte[] compatBytes = null;
        try {
            compatBytes = TrajectoryUploadCompat.toServerBytes(sentTrajectory);
        } catch (Exception e) {
            Log.w(TAG, "Compatibility payload generation failed for live upload", e);
        }

        List<UploadAttempt> attempts = buildUploadAttempts(resolvedCampaign, rawBytes, compatBytes);
        executeUploadAttempt(attempts, 0, fileName, null);
    }

    /** Uploads a local trajectory file; calls onSuccess on main thread if upload succeeds. */
    public void uploadLocalTrajectory(File localTrajectory) {
        uploadLocalTrajectory(localTrajectory, null);
    }

    /**
     * Uploads a local trajectory file to the API server.
     * @param onSuccess Optional Runnable invoked on the main thread after a successful upload.
     */
    public void uploadLocalTrajectory(File localTrajectory, Runnable onSuccess) {
        if (localTrajectory == null) {
            success = false;
            infoResponse = "Upload failed: localTrajectory is null";
            Log.e(TAG, infoResponse);
            notifyObservers(1);
            return;
        }

        String resolvedCampaign = resolveCampaign(null);
        Log.i(TAG, "Uploading local trajectory " + localTrajectory.getName()
                + " to campaign=" + resolvedCampaign);

        byte[] rawBytes;
        byte[] compatBytes = null;
        try {
            rawBytes = readFileBytes(localTrajectory);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read local trajectory for upload", e);
            success = false;
            infoResponse = "Upload failed: could not prepare trajectory payload";
            notifyObservers(1);
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());
            return;
        }

        try {
            Traj.Trajectory localTrajectoryProto = Traj.Trajectory.parseFrom(rawBytes);
            String validationMessage = validateTrajectoryForServerUpload(localTrajectoryProto);
            if (validationMessage != null) {
                success = false;
                infoResponse = validationMessage;
                Log.w(TAG, infoResponse);
                notifyObservers(1);
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(context, infoResponse, Toast.LENGTH_LONG).show());
                return;
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to parse local trajectory for validation; continuing upload attempt", e);
        }

        try {
            compatBytes = TrajectoryUploadCompat.localFileToServerBytes(localTrajectory);
        } catch (Exception e) {
            Log.w(TAG, "Compatibility payload generation failed for local upload", e);
        }

        List<UploadAttempt> attempts = buildUploadAttempts(resolvedCampaign, rawBytes, compatBytes);
        executeUploadAttempt(attempts, 0, localTrajectory.getName(), onSuccess);
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
                        Log.w(TAG, "Skipping malformed download record", e);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to load download records", e);
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
            Log.e(TAG, "Failed to save download record", e);
        }
    }

    /**
     * Downloads a specific trajectory from the server and saves it as a JSON text file.
     */
    public void downloadTrajectory(int position, String id, String dateSubmitted) {
        loadDownloadRecords();
        OkHttpClient client = new OkHttpClient();
        String downloadUrl = buildDownloadUrl(position);

        Request request = new Request.Builder()
                .url(downloadUrl)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "downloadTrajectory failed", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "downloadTrajectory HTTP error: " + response.code());
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(context, "Download failed: HTTP " + response.code(), Toast.LENGTH_SHORT).show());
                    if (response.body() != null) response.body().close();
                    return;
                }
                InputStream inputStream = null;
                ZipInputStream zipInputStream = null;
                ByteArrayOutputStream byteArrayOutputStream = null;
                try {
                    inputStream = response.body().byteStream();
                    zipInputStream = new ZipInputStream(inputStream);
                    ZipEntry zipEntry = findMatchingZipEntry(zipInputStream, id, position);
                    if (zipEntry == null) {
                        Log.e(TAG, "downloadTrajectory: position " + position
                                + " / id " + id + " not found in ZIP from " + downloadUrl);
                        new Handler(Looper.getMainLooper()).post(() ->
                                Toast.makeText(context, "Download failed: trajectory not found", Toast.LENGTH_SHORT).show());
                        return;
                    }

                    byteArrayOutputStream = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = zipInputStream.read(buffer)) != -1) {
                        byteArrayOutputStream.write(buffer, 0, bytesRead);
                    }

                    byte[] byteArray = byteArrayOutputStream.toByteArray();
                    Traj.Trajectory receivedTrajectory = TrajectoryUploadCompat.parseServerBytes(byteArray);
                    logDataSize(receivedTrajectory);

                    long startTimestamp = receivedTrajectory.getStartTimestamp();
                    String fileName = buildDownloadedTrajectoryFileName(id, dateSubmitted);

                    File appSpecificDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (appSpecificDownloads != null && !appSpecificDownloads.exists()) {
                        appSpecificDownloads.mkdirs();
                    }

                    File file = new File(appSpecificDownloads, fileName);
                    try (FileWriter fileWriter = new FileWriter(file)) {
                        String receivedTrajectoryString = JsonFormat.printer().print(receivedTrajectory);
                        fileWriter.write(receivedTrajectoryString);
                        fileWriter.flush();
                    }

                    saveDownloadRecord(startTimestamp, fileName, id, dateSubmitted);
                    loadDownloadRecords();

                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(context, "Download complete", Toast.LENGTH_SHORT).show());

                } catch (Exception e) {
                    Log.e(TAG, "downloadTrajectory processing failed", e);
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(context, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                } finally {
                    if (byteArrayOutputStream != null) try { byteArrayOutputStream.close(); } catch (IOException ignored) {}
                    if (zipInputStream != null) try { zipInputStream.close(); } catch (IOException ignored) {}
                    if (inputStream != null) try { inputStream.close(); } catch (IOException ignored) {}
                    if (response.body() != null) response.body().close();
                }
            }
        });
    }

    private ZipEntry findMatchingZipEntry(ZipInputStream zipInputStream, String id, int fallbackPosition)
            throws IOException {
        if (zipInputStream == null) {
            return null;
        }

        String expectedName = id == null ? null : id + ".pkt";
        ZipEntry zipEntry;
        int zipCount = 0;

        while ((zipEntry = zipInputStream.getNextEntry()) != null) {
            String entryName = zipEntry.getName();
            if (expectedName != null && expectedName.equals(entryName)) {
                Log.i(TAG, "Matched download ZIP entry by id: " + entryName);
                return zipEntry;
            }
            if (expectedName == null && zipCount == fallbackPosition) {
                Log.w(TAG, "Falling back to ZIP entry position " + fallbackPosition);
                return zipEntry;
            }
            zipCount++;
        }
        return null;
    }

    private String buildDownloadedTrajectoryFileName(String id, String dateSubmitted) {
        String safeId = sanitizeFileComponent(id, "trajectory");
        String safeDate = sanitizeFileComponent(dateSubmitted, "download");
        return String.format(Locale.US, "trajectory_%s_%s.txt", safeId, safeDate);
    }

    private String sanitizeFileComponent(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        String sanitized = Pattern.compile("[^\\p{L}\\p{N}._-]+")
                .matcher(value.trim())
                .replaceAll("_");
        sanitized = sanitized.replaceAll("_+", "_");
        sanitized = sanitized.replaceAll("^[_ .-]+|[_ .-]+$", "");
        return sanitized.isEmpty() ? fallback : sanitized;
    }

    private String resolveCampaign(String campaign) {
        String resolvedCampaign = campaign;
        if (resolvedCampaign == null || resolvedCampaign.trim().isEmpty()) {
            resolvedCampaign = VenueSelectionHelper.getSelectedCampaign(context);
        }
        if (resolvedCampaign == null || resolvedCampaign.trim().isEmpty()) {
            return LEGACY_UPLOAD_CAMPAIGN;
        }
        if (VenueSelectionHelper.DEFAULT_CAMPAIGN.equals(resolvedCampaign)) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                    context.getApplicationContext());
            String storedCampaign = prefs.getString(VenueSelectionHelper.PREF_CURRENT_CAMPAIGN, null);
            String storedBuilding = prefs.getString(VenueSelectionHelper.PREF_SELECTED_BUILDING, null);
            boolean hasExplicitVenue = (storedCampaign != null && !storedCampaign.trim().isEmpty())
                    || (storedBuilding != null && !storedBuilding.trim().isEmpty());
            if (!hasExplicitVenue) {
                return LEGACY_UPLOAD_CAMPAIGN;
            }
        }
        return resolvedCampaign;
    }

    private String buildCampaignUploadUrl(String campaign) {
        String resolvedCampaign = resolveCampaign(campaign);
        return "https://openpositioning.org/api/live/trajectory/upload/"
                + resolvedCampaign + "/" + userKey + "/?key=" + masterKey;
    }

    private String buildDownloadUrl(int position) {
        int limit = Math.max(position + 1, 30);
        return downloadBaseURL + "?skip=0&limit=" + limit + "&key=" + masterKey;
    }

    // Builds a retry chain across current and legacy-compatible upload payloads.
    private List<UploadAttempt> buildUploadAttempts(String preferredCampaign, byte[] rawBytes,
                                                    byte[] compatBytes) {
        List<UploadAttempt> attempts = new ArrayList<>();
        addUploadAttempt(attempts, preferredCampaign, rawBytes, "raw/current");
        if (compatBytes != null) {
            addUploadAttempt(attempts, preferredCampaign, compatBytes, "compat/legacy");
        }
        if (!LEGACY_UPLOAD_CAMPAIGN.equals(preferredCampaign)) {
            addUploadAttempt(attempts, LEGACY_UPLOAD_CAMPAIGN, rawBytes, "raw/current fallback");
            if (compatBytes != null) {
                addUploadAttempt(attempts, LEGACY_UPLOAD_CAMPAIGN, compatBytes,
                        "compat/legacy fallback");
            }
        }
        return attempts;
    }

    private void addUploadAttempt(List<UploadAttempt> attempts, String campaign, byte[] payload,
                                  String label) {
        String resolvedCampaign = resolveCampaign(campaign);
        for (UploadAttempt existing : attempts) {
            if (existing.campaign.equals(resolvedCampaign)
                    && Arrays.equals(existing.payload, payload)) {
                return;
            }
        }
        attempts.add(new UploadAttempt(resolvedCampaign, payload, label));
    }

    // Tries each upload strategy in sequence until one is accepted by the API.
    private void executeUploadAttempt(List<UploadAttempt> attempts, int attemptIndex, String fileName,
                                      Runnable onSuccess) {
        if (attemptIndex >= attempts.size()) {
            success = false;
            infoResponse = "Upload failed: no upload strategy succeeded";
            Log.e(TAG, infoResponse);
            notifyObservers(1);
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());
            return;
        }

        UploadAttempt attempt = attempts.get(attemptIndex);
        Log.i(TAG, "Upload attempt " + (attemptIndex + 1) + "/" + attempts.size()
                + " using campaign=" + attempt.campaign
                + " payload=" + attempt.label
                + " bytes=" + attempt.payload.length);

        OkHttpClient client = new OkHttpClient();
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName,
                        RequestBody.create(PROTO_MEDIA_TYPE, attempt.payload))
                .build();

        Request request = new Request.Builder()
                .url(buildCampaignUploadUrl(attempt.campaign))
                .post(requestBody)
                .addHeader("accept", PROTOCOL_ACCEPT_TYPE)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Upload failed on attempt " + (attemptIndex + 1), e);
                success = false;
                infoResponse = "Upload failed: " + e.getClass().getSimpleName() + " - " + e.getMessage();
                notifyObservers(1);
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String bodyStr = null;
                try (ResponseBody responseBody = response.body()) {
                    bodyStr = (responseBody != null) ? responseBody.string() : null;
                    if (response.isSuccessful()) {
                        success = true;
                        infoResponse = "Upload successful!";
                        Log.i(TAG, "Upload succeeded on attempt " + (attemptIndex + 1)
                                + ": HTTP " + response.code());
                        if (bodyStr != null && !bodyStr.trim().isEmpty()) {
                            Log.i(TAG, "Upload response body: " + bodyStr);
                        }
                        notifyObservers(1);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show();
                            if (onSuccess != null) {
                                onSuccess.run();
                            }
                        });
                        return;
                    }

                    String failureMessage = "Upload failed: HTTP " + response.code()
                            + " " + response.message();
                    if (bodyStr != null && !bodyStr.trim().isEmpty()) {
                        Log.e(TAG, failureMessage + ", body=" + bodyStr);
                    } else {
                        Log.e(TAG, failureMessage);
                    }

                    if (attemptIndex + 1 < attempts.size()) {
                        Log.w(TAG, "Retrying upload with next compatibility strategy");
                        executeUploadAttempt(attempts, attemptIndex + 1, fileName, onSuccess);
                        return;
                    }

                    success = false;
                    infoResponse = failureMessage;
                    notifyObservers(1);
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(context, infoResponse, Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private byte[] readFileBytes(File file) throws IOException {
        try (InputStream inputStream = new FileInputStream(file);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            return outputStream.toByteArray();
        }
    }

    // Rejects uploads that are known to fail the server-side minimum-duration check.
    private String validateTrajectoryForServerUpload(Traj.Trajectory trajectory) {
        if (trajectory == null) {
            return "Upload failed: trajectory is empty";
        }
        double imuDurationSec = calculateDurationSec(
                trajectory.getImuDataCount(),
                index -> trajectory.getImuData(index).getRelativeTimestamp());
        if (imuDurationSec >= MIN_SERVER_UPLOAD_DURATION_SEC) {
            return null;
        }
        return "Upload skipped: server requires at least "
                + String.format(java.util.Locale.US, "%.0f", MIN_SERVER_UPLOAD_DURATION_SEC)
                + "s of recorded trajectory data.";
    }

    private double calculateDurationSec(int count, TimestampProvider timestampProvider) {
        if (count <= 1) {
            return 0.0;
        }
        long startTs = timestampProvider.getTimestamp(0);
        long endTs = timestampProvider.getTimestamp(count - 1);
        return Math.max(0L, endTs - startTs) / 1000.0;
    }

    private static final class UploadAttempt {
        private final String campaign;
        private final byte[] payload;
        private final String label;

        private UploadAttempt(String campaign, byte[] payload, String label) {
            this.campaign = campaign;
            this.payload = payload;
            this.label = label;
        }
    }

    private interface TimestampProvider {
        long getTimestamp(int index);
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
                Log.e(TAG, "sendInfoRequest failed", e);
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

    private void logDataSize(Traj.Trajectory trajectory) {
        Log.d(TAG, "Trajectory summary:"
                + " imu=" + trajectory.getImuDataCount()
                + " wifi=" + trajectory.getWifiFingerprintsCount()
                + " gnss=" + trajectory.getGnssDataCount()
                + " pdr=" + trajectory.getPdrDataCount()
                + " test_points=" + trajectory.getTestPointsCount());
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
            Log.e(TAG, "Failed to build nearby building request", e);
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
    // Parses the API building payload into app-side models with ordered floor plans.
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
                    List<List<List<Double>>> stairs = new ArrayList<>();
                    List<List<List<Double>>> lifts = new ArrayList<>();


                    extractFeaturesFromGeoJson(mapShapes.get(floorCode).toString(), walls, stairs, lifts);


                    floors.add(new FloorPlan(floorCode, 0, null, new double[]{0, 0, 0, 0}, walls, stairs, lifts));
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
        } catch (Exception e) { Log.e(TAG, "Failed to parse building outline GeoJSON", e); }
    }
    // Extracts wall, stair, and lift geometry from one floor-plan feature collection.
    private void extractFeaturesFromGeoJson(String geoJsonStr,
                                            List<List<List<Double>>> wallsList,
                                            List<List<List<Double>>> stairsList,
                                            List<List<List<Double>>> liftsList) {
        try {
            JSONObject featureCollection = new JSONObject(geoJsonStr);
            JSONArray features = featureCollection.getJSONArray("features");
            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.getJSONObject(i);
                JSONObject geometry = feature.getJSONObject("geometry");
                String type = geometry.getString("type");
                JSONArray coords = geometry.getJSONArray("coordinates");

                String indoorType = "wall"; // default fallback

                // Try reading feature properties.
                if (feature.has("properties") && !feature.isNull("properties")) {
                    JSONObject properties = feature.getJSONObject("properties");

                    // Prefer the explicit indoor classification before falling back to generic fields.
                    if (properties.has("indoor_type")) {
                        indoorType = properties.getString("indoor_type").toLowerCase();
                    } else if (properties.has("type")) {
                        indoorType = properties.getString("type").toLowerCase();
                    } else if (properties.has("name")) {
                        indoorType = properties.getString("name").toLowerCase();
                    }
                }

                List<List<List<Double>>> targetList = wallsList;
                if (indoorType.contains("stairs") || indoorType.contains("stair")) {
                    targetList = stairsList;
                } else if (indoorType.contains("lift") || indoorType.contains("elevator")) {
                    targetList = liftsList;
                }

                // Normalize each supported GeoJSON geometry into a polyline-style path list.
                if (type.equalsIgnoreCase("MultiLineString")) {
                    for (int k = 0; k < coords.length(); k++) parseLineString(coords.getJSONArray(k), targetList);
                } else if (type.equalsIgnoreCase("LineString") || type.equalsIgnoreCase("Polygon")) {
                    parseLineString(type.equalsIgnoreCase("LineString") ? coords : coords.getJSONArray(0), targetList);
                } else if (type.equalsIgnoreCase("MultiPolygon")) {
                    for (int k = 0; k < coords.length(); k++) parseLineString(coords.getJSONArray(k).getJSONArray(0), targetList);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "GeoJSON parse failed", e);
        }
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
