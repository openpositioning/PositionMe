package com.openpositioning.PositionMe.dataParser;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to parse a JSON-formatted trajectory file from internal storage.
 * It extracts GNSS data, PDR data, and Position data.
 */
public class TrajectoryParser {
    private static final String TAG = "TrajectoryParser";

    /**
     * Reads the "received_trajectory.txt" file from internal storage and parses its JSON content.
     *
     * @param context the application context
     * @return a TrajectoryData object containing the parsed GNSS, PDR, and Position data
     */
    public static TrajectoryData parseTrajectoryFile(Context context) {
        TrajectoryData trajectoryData = new TrajectoryData();
        File file = new File(context.getFilesDir(), "received_trajectory.txt");
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
                    long relativeTimestamp = Long.parseLong(gnssObj.getString("relativeTimestamp"));
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
                    long relativeTimestamp = Long.parseLong(pdrObj.getString("relativeTimestamp"));
                    float x = (float) pdrObj.getDouble("x");
                    float y = (float) pdrObj.getDouble("y");
                    PdrData sample = new PdrData(relativeTimestamp, x, y);
                    pdrDataList.add(sample);
                }
            }
            trajectoryData.setPdrData(pdrDataList);

            // --- Parse Position data ---
            JSONArray posArray = jsonTrajectory.optJSONArray("positionData");
            List<PositionData> positionDataList = new ArrayList<>();
            if (posArray != null) {
                for (int i = 0; i < posArray.length(); i++) {
                    JSONObject posObj = posArray.getJSONObject(i);
                    long relativeTimestamp = Long.parseLong(posObj.getString("relativeTimestamp"));
                    double magX = posObj.getDouble("magX");
                    double magY = posObj.getDouble("magY");
                    double magZ = posObj.getDouble("magZ");
                    PositionData sample = new PositionData(relativeTimestamp, magX, magY, magZ);
                    positionDataList.add(sample);
                }
            }
            trajectoryData.setPositionData(positionDataList);

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing JSON: " + e.getMessage());
            e.printStackTrace();
        }
        return trajectoryData;
    }
}