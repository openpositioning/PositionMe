package com.openpositioning.PositionMe.utils;

import static com.openpositioning.PositionMe.BuildConstants.DEBUG;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Standalone barometer data logger for floor calibration.
 *
 * Records raw pressure (hPa), calculated altitude (m), and a smoothed
 * altitude at 1 Hz to a CSV file. The user walks between floors while
 * this logger runs, then the resulting CSV is analysed to determine
 * per-floor pressure/altitude ranges.
 *
 * Usage:
 *   BarometerLogger logger = new BarometerLogger(context);
 *   logger.start();   // begin recording
 *   // ... user walks between floors ...
 *   logger.stop();    // stop and flush file
 *   String path = logger.getFilePath(); // CSV location
 */
public class BarometerLogger implements SensorEventListener {

    private static final String TAG = "BarometerLogger";
    private static final float ALPHA = 0.15f; // low-pass filter coefficient

    private final SensorManager sensorManager;
    private final Sensor pressureSensor;
    private FileWriter writer;
    private File outputFile;

    private float smoothedPressure = 0;
    private float startAltitude = Float.NaN;
    private long startTimeMs;
    private int sampleCount = 0;

    // Periodic status log
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusLog = new Runnable() {
        @Override
        public void run() {
            if (writer != null) {
                float alt = pressureToAltitude(smoothedPressure);
                float rel = Float.isNaN(startAltitude) ? 0 : alt - startAltitude;
                if (DEBUG) Log.i(TAG, String.format(
                        "[LIVE] pressure=%.2f hPa  altitude=%.2f m  relAlt=%.2f m  samples=%d",
                        smoothedPressure, alt, rel, sampleCount));
                handler.postDelayed(this, 5000);
            }
        }
    };

    public BarometerLogger(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);

        // Output file in app's external storage
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = context.getExternalFilesDir(null);
        outputFile = new File(dir, "baro_calibration_" + timestamp + ".csv");
    }

    /** Start recording barometer data to CSV. */
    public void start() {
        if (pressureSensor == null) {
            Log.e(TAG, "No pressure sensor available on this device!");
            return;
        }

        try {
            writer = new FileWriter(outputFile, false);
            writer.write("elapsed_s,timestamp,raw_pressure_hPa,smoothed_pressure_hPa,altitude_m,relative_altitude_m\n");
            writer.flush();
        } catch (IOException e) {
            Log.e(TAG, "Failed to create output file", e);
            return;
        }

        startTimeMs = System.currentTimeMillis();
        smoothedPressure = 0;
        startAltitude = Float.NaN;
        sampleCount = 0;

        // Register at ~1Hz (1,000,000 μs)
        sensorManager.registerListener(this, pressureSensor, 1_000_000);

        handler.postDelayed(statusLog, 5000);

        if (DEBUG) Log.i(TAG, "=== Barometer logging STARTED → " + outputFile.getAbsolutePath());
    }

    /** Stop recording and flush the CSV file. */
    public void stop() {
        sensorManager.unregisterListener(this);
        handler.removeCallbacks(statusLog);

        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing file", e);
            }
            writer = null;
        }

        if (DEBUG) Log.i(TAG, String.format("=== Barometer logging STOPPED. %d samples → %s",
                sampleCount, outputFile.getAbsolutePath()));
    }

    /** Returns the absolute path of the output CSV file. */
    public String getFilePath() {
        return outputFile.getAbsolutePath();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_PRESSURE) return;

        float rawPressure = event.values[0];

        // Low-pass filter
        if (smoothedPressure == 0) {
            smoothedPressure = rawPressure;
        } else {
            smoothedPressure = (1 - ALPHA) * smoothedPressure + ALPHA * rawPressure;
        }

        float altitude = pressureToAltitude(smoothedPressure);

        // Set start altitude from first reading
        if (Float.isNaN(startAltitude)) {
            startAltitude = altitude;
        }

        float relAltitude = altitude - startAltitude;
        float elapsedS = (System.currentTimeMillis() - startTimeMs) / 1000f;
        sampleCount++;

        // Write to CSV
        if (writer != null) {
            try {
                writer.write(String.format(Locale.US, "%.1f,%d,%.3f,%.3f,%.3f,%.3f\n",
                        elapsedS,
                        System.currentTimeMillis(),
                        rawPressure,
                        smoothedPressure,
                        altitude,
                        relAltitude));
                // Flush every 10 samples to avoid data loss
                if (sampleCount % 10 == 0) {
                    writer.flush();
                }
            } catch (IOException e) {
                Log.e(TAG, "Write error", e);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /** Convert pressure (hPa) to altitude (m) using standard atmosphere. */
    private static float pressureToAltitude(float pressure) {
        return SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure);
    }
}
