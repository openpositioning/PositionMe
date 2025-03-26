package com.openpositioning.PositionMe.presentation.activity;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.CollectionFragment;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.sensors.Wifi;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * CollectionActivity passively collects sensor data from SensorFusion, including:
 * IMU (accelerometer, gyro, magnetometer, pressure, light, proximity),
 * GNSS lat/lng, PDR coords, WiFi scans, and more.
 *
 * This example:
 * 1) Starts sensorFusion in onCreate.
 * 2) Repeatedly calls SensorFusion.getAllSensorData() on a loop (using a Handler).
 * 3) Stores JSON data in a buffer and periodically flushes it to a file in the public Downloads folder.
 * 4) Lets the user calibrate position (via CollectionFragment) to store a labeled data point.
 * 5) On destroy, flushes any remaining records and closes the file.
 */
public class CollectionActivity extends AppCompatActivity {

    private static final String TAG = "CollectionActivity";

    private SensorFusion sensorFusion;
    private Handler sensorUpdateHandler;
    private Runnable sensorUpdateTask;

    // Buffer to store JSON records before writing to file
    private final List<JSONObject> dataBuffer = new ArrayList<>();

    // Uri for the output file in the public Downloads folder
    private Uri outputFileUri;

    // Persistent output stream for writing data
    private OutputStream outStream;

    // Collection frequency (ms)
    private static final long PASSIVE_COLLECTION_INTERVAL_MS = 500; // e.g. 2 samples/second

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set layout containing the CollectionFragment container
        setContentView(R.layout.activity_collection);

        // Keep the screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 1) Initialize SensorFusion and start listening for sensor updates
        sensorFusion = SensorFusion.getInstance();
        sensorFusion.setContext(getApplicationContext());
        sensorFusion.resumeListening();

        // 2) Prepare the output file in the public Downloads folder with a timestamped filename.
        // For API >= Q we use MediaStore; for older devices we fall back to File API.
        outputFileUri = getDownloadOutputFile(this);
        if (outputFileUri != null) {
            Log.d(TAG, "Output file created: " + outputFileUri.toString());
            try {
                // Open the output stream once (in write mode)
                outStream = getContentResolver().openOutputStream(outputFileUri, "w");
                Log.d(TAG, "Output stream opened successfully.");
            } catch (IOException e) {
                Log.e(TAG, "Error opening output stream", e);
            }
        } else {
            Log.e(TAG, "Failed to create output file URI.");
        }

        // 3) Show the fragment with the map/calibration UI
        if (savedInstanceState == null) {
            showCollectionFragment();
        }

        // 4) Start the passive sensor data collection loop
        sensorUpdateHandler = new Handler();
        sensorUpdateTask = new Runnable() {
            @Override
            public void run() {
                collectFullSensorData();
                sensorUpdateHandler.postDelayed(this, PASSIVE_COLLECTION_INTERVAL_MS);
            }
        };
        sensorUpdateHandler.post(sensorUpdateTask);
    }

    /**
     * Replaces the container with CollectionFragment so the user can calibrate by dragging a pin.
     */
    private void showCollectionFragment() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(R.id.mainFragmentContainer, new CollectionFragment());
        ft.commit();
        Log.d(TAG, "CollectionFragment displayed.");
    }

    /**
     * Collects sensor data using SensorFusion.getAllSensorData(), adds it to the buffer,
     * and flushes the buffer if it reaches a certain size.
     */
    private void collectFullSensorData() {
        try {
            JSONObject record = sensorFusion.getAllSensorData();
            dataBuffer.add(record);
            Log.d(TAG, "Collected sensor data. Buffer size: " + dataBuffer.size());

            // Flush strategy: flush every 50 records
            if (dataBuffer.size() >= 50) {
                flushDataBuffer();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error collecting sensor data", e);
        }
    }

    /**
     * Called by CollectionFragment when the user triggers calibration.
     *
     * @param userLat      The user-labeled latitude.
     * @param userLng      The user-labeled longitude.
     * @param floorLevel   The user-labeled floor (or -255 for outdoors).
     * @param indoorState  The user-labeled indoor state (0=unknown, 1=indoor, 2=outdoor, 3=transitional/in-boundary).
     * @param buildingName The user-labeled building name (or null).
     */
    public void onCalibrationTriggered(double userLat, double userLng, int indoorState, int floorLevel, String buildingName) {
        try {
            JSONObject calibrationRecord = sensorFusion.getAllSensorData();
            calibrationRecord.put("isCalibration", true);
            calibrationRecord.put("userLat", userLat);
            calibrationRecord.put("userLng", userLng);
            calibrationRecord.put("floorLevel", floorLevel);
            calibrationRecord.put("indoorState", indoorState);
            calibrationRecord.put("buildingName", buildingName);

            dataBuffer.add(calibrationRecord);
            Log.d(TAG, "Calibration triggered. Buffer size: " + dataBuffer.size());
            flushDataBuffer(); // Optionally flush immediately
        } catch (Exception e) {
            Log.e(TAG, "Error triggering calibration", e);
        }
    }

    /**
     * Writes all buffered JSON records to the output file using the persistent OutputStream,
     * then clears the buffer.
     */
    private void flushDataBuffer() {
        if (dataBuffer.isEmpty() || outStream == null) {
            Log.d(TAG, "Flush skipped; buffer empty or output stream is null.");
            return;
        }
        try {
            for (JSONObject obj : dataBuffer) {
                String jsonLine = obj.toString() + "\n";
                outStream.write(jsonLine.getBytes());
            }
            outStream.flush();
            Log.d(TAG, "Flushed " + dataBuffer.size() + " records to file.");
            dataBuffer.clear();
        } catch (IOException e) {
            Log.e(TAG, "Error flushing data", e);
        }
    }

    /**
     * Creates a new file in the public Downloads folder with a timestamped filename.
     * For API >= Q it uses MediaStore.Downloads; for older devices it falls back to the legacy File API.
     *
     * @param context The application context.
     * @return The Uri of the newly created file, or null if creation failed.
     */
    private Uri getDownloadOutputFile(Context context) {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmm").format(new Date());
        String fileName = "collection_data_"  + Build.MODEL + "_" + timeStamp +".json";
        Log.d(TAG, "Creating output file: " + fileName);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return getDownloadOutputFileForQ(context, fileName);
        } else {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File file = new File(downloadsDir, fileName);
            Log.d(TAG, "Legacy output file path: " + file.getAbsolutePath());
            return Uri.fromFile(file);
        }
    }

    /**
     * Creates a new file in the public Downloads folder using MediaStore for API >= Q.
     * Leaves the file in a pending state (IS_PENDING = 1) so it remains writable.
     *
     * @param context  The application context.
     * @param fileName The desired file name.
     * @return The Uri of the created file.
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private Uri getDownloadOutputFileForQ(Context context, String fileName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
        // Keep the file pending until we're finished writing
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri fileUri = context.getContentResolver().insert(collection, values);
        if (fileUri != null) {
            Log.d(TAG, "MediaStore file created (pending): " + fileUri.toString());
            // Do not update IS_PENDING here—delay until onDestroy.
        } else {
            Log.e(TAG, "Failed to create MediaStore file.");
        }
        return fileUri;
    }

    @Override
    protected void onDestroy() {
        // 1) Stop the sensor update loop
        if (sensorUpdateHandler != null && sensorUpdateTask != null) {
            sensorUpdateHandler.removeCallbacks(sensorUpdateTask);
            Log.d(TAG, "Sensor update loop stopped.");
        }

        // 2) Stop SensorFusion
        sensorFusion.stopListening();
        Log.d(TAG, "SensorFusion stopped.");

        // 3) Flush any remaining records
        flushDataBuffer();

        // 4) Close the persistent output stream
        if (outStream != null) {
            try {
                outStream.close();
                Log.d(TAG, "Output stream closed.");
            } catch (IOException e) {
                Log.e(TAG, "Error closing output stream", e);
            }
        }

        // 5) For API >= Q, update the file's IS_PENDING flag to 0 so it becomes visible
        if (outputFileUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(outputFileUri, values, null, null);
            Log.d(TAG, "Output file updated to not pending.");
        }

        super.onDestroy();
    }
}
