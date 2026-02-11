package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import com.openpositioning.PositionMe.health.WalkSessionSummary;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A utility class to parse walk data files and convert them into WalkSessionSummary objects.
 * This class is responsible for reading raw trajectory files (e.g., CSV format)
 * and calculating summary statistics like total distance and duration.
 */
public class WalkFileParser {

    // Define keys for SharedPreferences (same as in ZenWorker)
    private static final String PREFS_NAME = "ZenPrefs";
    private static final String KEY_LAST_WALK_TIMESTAMP = "lastWalkTimestamp";

    private final Context context;

    /**
     * Constructs a parser with the application context.
     * @param context The context, used for accessing SharedPreferences.
     */
    public WalkFileParser(Context context) {
        this.context = context.getApplicationContext(); // Use app context to prevent memory leaks
    }

    /**
     * Parses a single trajectory file to calculate its summary.
     * After parsing, it saves the walk's timestamp to SharedPreferences for the Zen reminder feature.
     *
     * @param file The trajectory file to parse.
     * @return A {@link WalkSessionSummary} object containing the calculated data, or null if parsing fails.
     */
    public WalkSessionSummary parseFile(File file) {
        // We assume a simple CSV format: latitude,longitude,timestamp_ms
        List<Location> locations = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                // Skip headers or comments
                if (line.startsWith("#") || line.trim().isEmpty()) {
                    continue;
                }

                String[] parts = line.split(",");
                if (parts.length >= 3) { // At least lat, lon, time
                    Location loc = new Location("file");
                    loc.setLatitude(Double.parseDouble(parts[0]));
                    loc.setLongitude(Double.parseDouble(parts[1]));
                    loc.setTime(Long.parseLong(parts[2]));
                    locations.add(loc);
                }
            }
        } catch (IOException | NumberFormatException e) {
            e.printStackTrace();
            return null; // Parsing failed
        }

        if (locations.isEmpty()) {
            return null;
        }

        // --- Calculate total distance ---
        double totalDistance = 0;
        for (int i = 0; i < locations.size() - 1; i++) {
            totalDistance += locations.get(i).distanceTo(locations.get(i + 1));
        }

        // --- Calculate total duration ---
        long firstTimestamp = locations.get(0).getTime();
        long lastTimestamp = locations.get(locations.size() - 1).getTime();
        int totalDurationSeconds = (int) ((lastTimestamp - firstTimestamp) / 1000);

        // --- Create Summary ---
        String sessionId = file.getName(); // Use filename as a unique ID
        WalkSessionSummary summary = new WalkSessionSummary(sessionId, totalDistance, totalDurationSeconds, lastTimestamp);
        
        // --- Save the timestamp of this walk ---
        saveLastWalkTimestamp(summary.getTimestampMillis());
        
        return summary;
    }

    /**
     * Saves the timestamp of the most recently parsed walk to SharedPreferences.
     * This is used by ZenWorker to check if a walk has occurred today.
     * @param timestamp The timestamp of the walk session end.
     */
    private void saveLastWalkTimestamp(long timestamp) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putLong(KEY_LAST_WALK_TIMESTAMP, timestamp);
        editor.apply();
    }
}
