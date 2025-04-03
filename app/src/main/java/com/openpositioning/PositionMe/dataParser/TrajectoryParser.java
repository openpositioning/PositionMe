package com.openpositioning.PositionMe.dataParser;

import android.content.Context;
import android.hardware.SensorManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to parse a JSON-formatted trajectory file.
 */
public class TrajectoryParser {
    private static final String TAG = "TrajectoryParser";

    public static TrajectoryData parseTrajectoryFile(Context context, String trajectoryId) {
        TrajectoryData trajectoryData = new TrajectoryData();
        // Construct the file name using the trajectoryId.
        String fileName = "received_trajectory" + trajectoryId + ".txt";
        File file = new File(context.getFilesDir(), fileName);
        if (!file.exists()) {
            Log.e(TAG, "Trajectory file does not exist: " + file.getAbsolutePath());
            return trajectoryData;
        }

        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading trajectory file: " + e.getMessage());
            e.printStackTrace();
            return trajectoryData;
        }

        String fileContent = content.toString();
        try {
            JSONObject jsonTrajectory = new JSONObject(fileContent);

            // --- Parse GNSS data ---
            JSONArray gnssArray = jsonTrajectory.optJSONArray("gnssData");
            List<GnssData> gnssDataList = new ArrayList<>();
            if (gnssArray != null) {
                for (int i = 0; i < gnssArray.length(); i++) {
                    JSONObject gnssObj = gnssArray.getJSONObject(i);
                    long relativeTimestamp = gnssObj.has("relativeTimestamp")
                            ? gnssObj.getLong("relativeTimestamp")
                            : Long.parseLong(gnssObj.getString("relativeTimestamp"));
                    double latitude = gnssObj.getDouble("latitude");
                    double longitude = gnssObj.getDouble("longitude");
                    double altitude = gnssObj.getDouble("altitude");
                    double accuracy = gnssObj.getDouble("accuracy");
                    String provider = gnssObj.getString("provider");
                    GnssData sample = new GnssData(relativeTimestamp, latitude, longitude, altitude, accuracy, provider);
                    gnssDataList.add(sample);
                }
            }
            trajectoryData.setGnssData(gnssDataList);

            // --- Parse PDR data ---
            JSONArray pdrArray = jsonTrajectory.optJSONArray("pdrData");
            List<PdrData> pdrDataList = new ArrayList<>();
            if (pdrArray != null) {
                for (int i = 0; i < pdrArray.length(); i++) {
                    JSONObject pdrObj = pdrArray.getJSONObject(i);
                    long relativeTimestamp = pdrObj.has("relativeTimestamp")
                            ? pdrObj.getLong("relativeTimestamp")
                            : Long.parseLong(pdrObj.getString("relativeTimestamp"));
                    float x = (float) pdrObj.getDouble("x");
                    float y = (float) pdrObj.getDouble("y");
                    PdrData sample = new PdrData(relativeTimestamp, x, y);
                    pdrDataList.add(sample);
                }
            }
            trajectoryData.setPdrData(pdrDataList);


            // --- Parse Pressure data ---
            JSONArray pressureArray = jsonTrajectory.optJSONArray("pressureData");
            List<PressureData> pressureDataList = new ArrayList<>();
            float baselineAltitude = 0;
            if (pressureArray != null && pressureArray.length() > 0) {
                int baselineCount = Math.min(3, pressureArray.length());
                float[] baselineAltitudes = new float[baselineCount];
                for (int i = 0; i < baselineCount; i++) {
                    JSONObject pressObj = pressureArray.getJSONObject(i);
                    float pressure = (float) pressObj.getDouble("pressure");
                    float altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure);
                    baselineAltitudes[i] = altitude;
                }
                float sum = 0;
                for (float alt : baselineAltitudes) {
                    sum += alt;
                }
                baselineAltitude = sum / baselineCount;

                for (int i = 0; i < pressureArray.length(); i++) {
                    JSONObject pressObj = pressureArray.getJSONObject(i);
                    long relativeTimestamp = pressObj.has("relativeTimestamp")
                            ? pressObj.getLong("relativeTimestamp")
                            : Long.parseLong(pressObj.getString("relativeTimestamp"));
                    float pressure = (float) pressObj.getDouble("pressure");
                    float absoluteAltitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure);
                    float relativeAltitude = absoluteAltitude - baselineAltitude;
                    PressureData sample = new PressureData(relativeTimestamp, relativeAltitude);
                    pressureDataList.add(sample);
                }
            }
            trajectoryData.setPressureData(pressureDataList);

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing JSON: " + e.getMessage());
            e.printStackTrace();
        }
        return trajectoryData;
    }
}