package com.openpositioning.PositionMe.utils;

import android.location.Location;
import android.util.Log;

/**
 * Detects if user is near building entrance/outside based on GPS coordinates
 * Uses geofencing to detect proximity to reference locations (trajectories 3054, 3055, 3056)
 */
public class LocationDetector {
    private static final String TAG = "LocationDetector";

    // Reference coordinates for building entrance/outside area
    // Based on actual coordinates from trajectories 3054, 3055, 3056 (entrance2, entrance3, entrance4)
    // Average of initial positions from all three entrance trajectories
    private static final double ENTRANCE_LAT = 55.924085;  // Actual building entrance latitude
    private static final double ENTRANCE_LNG = -3.178890;  // Actual building entrance longitude

    // Radius in meters to consider "near entrance"
    private static final float GEOFENCE_RADIUS_METERS = 100.0f;  // 100 meters

    // Minimum time between weather prompts (milliseconds)
    private static final long MIN_PROMPT_INTERVAL_MS = 3600000; // 1 hour

    private long lastPromptTime = 0;

    /**
     * Check if current location is near building entrance/outside
     * @param latitude Current latitude
     * @param longitude Current longitude
     * @return true if near entrance, false otherwise
     */
    public boolean isNearBuildingEntrance(double latitude, double longitude) {
        if (latitude == 0.0 && longitude == 0.0) {
            return false; // Invalid coordinates
        }

        float[] results = new float[1];
        Location.distanceBetween(
            latitude, longitude,
            ENTRANCE_LAT, ENTRANCE_LNG,
            results
        );

        float distanceMeters = results[0];
        Log.d(TAG, "Distance to entrance: " + distanceMeters + " meters");

        return distanceMeters <= GEOFENCE_RADIUS_METERS;
    }

    /**
     * Check if enough time has passed since last prompt
     * Prevents showing the weather prompt too frequently
     * @return true if should show prompt, false if too soon
     */
    public boolean shouldShowPrompt() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPromptTime >= MIN_PROMPT_INTERVAL_MS) {
            lastPromptTime = currentTime;
            return true;
        }
        return false;
    }

    /**
     * Update reference coordinates based on trajectory data
     * Call this if you have actual coordinates from trajectories 3054, 3055, 3056
     * @param trajectoryCoordinates Array of [lat, lng] pairs
     */
    public static double[] calculateCenterPoint(double[][] trajectoryCoordinates) {
        if (trajectoryCoordinates == null || trajectoryCoordinates.length == 0) {
            return new double[]{ENTRANCE_LAT, ENTRANCE_LNG};
        }

        double sumLat = 0;
        double sumLng = 0;

        for (double[] coord : trajectoryCoordinates) {
            sumLat += coord[0];
            sumLng += coord[1];
        }

        double avgLat = sumLat / trajectoryCoordinates.length;
        double avgLng = sumLng / trajectoryCoordinates.length;

        Log.i(TAG, "Calculated center point: " + avgLat + ", " + avgLng);
        return new double[]{avgLat, avgLng};
    }

    /**
     * Reset the prompt timer (for testing purposes)
     */
    public void resetPromptTimer() {
        lastPromptTime = 0;
    }
}
