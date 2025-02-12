package com.openpositioning.PositionMe.dataParser;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Utility class to parse a JSON-formatted trajectory file from internal storage.
 * It extracts GNSS data, PDR data, and Position data.
 */
public class TrajectoryParser {
    private static final String TAG = "TrajectoryParser";

    /**
     * Reads the "received_trajectory.txt" file from internal storage and parses its JSON content.
     * @param context the application context
     * @return a TrajectoryData object containing the parsed GNSS, PDR, and Position data
     */
    public static TrajectoryData parseTrajectoryFile(Context context, String fileId) {
        //TrajectoryData trajectoryData = new TrajectoryData();
        String fileName = "received_trajectory" + fileId + ".txt";
        File file = new File(context.getFilesDir(), fileName);
        StringBuilder content = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading trajectory file: " + e.getMessage());
            e.printStackTrace();
            return new TrajectoryData();
        }

        String fileContent = content.toString();
        TrajectoryData trajectoryData = new Gson().fromJson(fileContent, TrajectoryData.class);
        return trajectoryData;
    }
}