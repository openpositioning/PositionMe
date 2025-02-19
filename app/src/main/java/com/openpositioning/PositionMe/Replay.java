package com.openpositioning.PositionMe;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * The PdrProcessing class manages the computation
 * of step lengths and position updates using data from accelerometers and GNSS instruments.
 * It utilizes the Weiberg Stride Length formula to calculate stride lengths from
 * accelerometer values, enhancing pedestrian navigation accuracy. The class also processes
 * trajectory data in JSON format, converting PDR coordinates into geographic coordinates
 * for visualization on a map.
 *
 * Additionally, the class handles elevation calculations by retrieving and processing
 * sensor data, allowing for more accurate indoor and outdoor positioning. It supports
 * updating the UI with the current position and elevation during playback.
 *
 * @author Yueyan Zhao
 * @author Zizhen Wang
 * @author Chen Zhao
 */

public class Replay implements OnMapReadyCallback {

    // Context for operations.
    private Context context;
    // Google Map instance for displaying the trajectory.
    private GoogleMap googleMap;
    // List of trajectory coordinates to replay.
    private List<double[]> trajectoryCoordinates;
    // Handler for managing playback timing.
    private Handler playbackHandler;
    // Runnable for playback updates.
    private Runnable playbackRunnable;
    // Current index in the trajectory list.
    private int playbackIndex = 0;
    // Playback status flag.
    private boolean isPlaying = false;

    // Polyline for visualizing the trajectory.
    private Polyline trajectoryPolyline;

    // Interval in milliseconds between points during playback (currently set to 1).
    private static final int POINT_INTERVAL = 1;

    // Callback interface for replay events.
    private ReplayCallback callback;

    /**
     * Interface for handling replay callback events.
     * This interface defines a method for updating the UI and
     * the user's position during trajectory playback.
     */
    public interface ReplayCallback {
        /**
         * Updates the user interface and the user's current position
         * on the map based on the provided latitude and longitude.
         *
         * @param latLng   The current location of the user represented as a LatLng object.
         */
        void updateUIandPosition(LatLng latLng); // Callback method
    }

    /**
     * Constructor for the Replay class that initializes the replay instance.
     * It takes the context, trajectory data in JSON format, and a callback
     * interface for handling replay events. The constructor sets up necessary
     * data structures and prepares the playback handler.
     *
     * @param context          The context in which the Replay operates.
     * @param trajectoryJson   The trajectory data in JSON format to be processed.
     * @param callback         A callback interface for managing replay updates.
     */
    public Replay(Context context, String trajectoryJson,ReplayCallback callback) {
        this.context = context; // Assign the operating context.

        // Initialize the list to hold trajectory coordinates.
        this.trajectoryCoordinates = new ArrayList<>();

        // Create a handler to manage playback updates on the main thread.
        this.playbackHandler = new Handler(Looper.getMainLooper());

        // Assign the callback for notifying updates during the replay.
        this.callback = callback;

        // Process the trajectory JSON to extract coordinates.
        processTrajectoryJson(trajectoryJson);
    }

    /**
     * Processes the trajectory data in JSON format to extract PDR and GNSS coordinates.
     * This method parses the provided JSON string, retrieves relevant samples, and converts
     * PDR coordinates into latitude and longitude pairs, which are then stored in a list.
     *
     * @param trajectoryJson   The trajectory data in JSON format to be processed.
     */
    private void processTrajectoryJson(String trajectoryJson) {
        // Create a JsonParser instance to parse the JSON string.
        JsonParser parser = new JsonParser();
        JsonObject jsonObject = parser.parse(trajectoryJson).getAsJsonObject(); // Parse the JSON string into a JsonObject.

        // Retrieve the PDR and GNSS arrays from the JSON data.
        JsonArray pdrArray = jsonObject.getAsJsonArray("pdr_samples");
        JsonArray gnssArray = jsonObject.getAsJsonArray("gnss_samples");

        // Check if there are GNSS samples available.
        if (gnssArray != null && gnssArray.size() > 0) {
            // Get the first GNSS sample to determine the base latitude and longitude.
            JsonObject firstGnssSample = gnssArray.get(0).getAsJsonObject();
            double baseLat = firstGnssSample.get("latitude").getAsDouble(); // Latitude of the first GNSS sample.
            double baseLng = firstGnssSample.get("longitude").getAsDouble(); // Longitude of the first GNSS sample.
            //            double baseLat = 55.922881960397646;
            //            double baseLng = -3.1750141304149144;

            // Check if there are PDR samples available.
            if (pdrArray != null) {
                // Iterate through each PDR sample to convert and store coordinates.
                for (JsonElement element : pdrArray) {
                    JsonObject pdrSample = element.getAsJsonObject();
                    float x = pdrSample.get("x").getAsFloat(); // Get the x coordinate from the PDR sample.
                    float y = pdrSample.get("y").getAsFloat(); // Get the y coordinate from the PDR sample.

                    // Convert the PDR coordinates to latitude and longitude.
                    double[] latLng = convertToLatLng(baseLat, baseLng, x, y);

                    // Add the converted coordinates to the trajectory coordinates list.
                    trajectoryCoordinates.add(latLng);
                }
            }
        }

        // Log the converted coordinates for debugging purposes.
        for (double[] coordinate : trajectoryCoordinates) {
            Log.d("Replay", "Coordinate - Latitude: " + coordinate[0] + ", Longitude: " + coordinate[1]);
        }
    }

    /**
     * Converts PDR (Pedestrian Dead Reckoning) coordinates into latitude and longitude
     * based on the given base coordinates. This method calculates the offsets for latitude
     * and longitude using the provided x and y values relative to the base latitude and
     * longitude.
     *
     * @param baseLat   The base latitude from which to calculate the offset.
     * @param baseLng   The base longitude from which to calculate the offset.
     * @param x         The x offset value from the PDR coordinates.
     * @param y         The y offset value from the PDR coordinates.
     * @return         An array containing the calculated latitude and longitude after applying the offsets.
     */
    private double[] convertToLatLng(double baseLat, double baseLng, double x, double y) {
        // Pre-compute the radians of the base latitude for calculations.
        double latRadians = Math.toRadians(baseLat);

        // Calculate the latitude and longitude offsets based on the provided x and y coordinates.
        double latOffset = y * 4e-8; // Offset for latitude (scale factor applied).
        double lngOffset = x * 4e-8 / Math.cos(latRadians); // Offset for longitude, adjusted for latitude effect.

        // Return the new latitude and longitude calculated from the base values and offsets.
        return new double[]{baseLat + latOffset, baseLng + lngOffset};
    }

    /**
     * Callback invoked when the Google Map is ready for use.
     * This method initializes the Google Map instance and checks
     * if there are any trajectory coordinates available for display.
     * If no trajectory data is present, it shows a toast message to inform the user.
     *
     * @param map   The GoogleMap instance that is ready for use.
     */
    @Override
    public void onMapReady(GoogleMap map) {
        this.googleMap = map; // Assign the ready GoogleMap instance to the class variable.

        // Check if there are trajectory coordinates to display.
        if (trajectoryCoordinates.isEmpty()) {
            // Show a toast message if no trajectory data is available.
            Toast.makeText(context, "No trajectory data to display", Toast.LENGTH_SHORT).show();
            return; // Exit the method early if no data is present.
        }
        // Additional code for rendering trajectory on the map would go here.
    }

    /**
     * Starts playback of the recorded trajectory on the Google Map.
     * This method checks if the map instance and trajectory data are available,
     * initializes playback variables, and begins a handler that updates the map
     * with the trajectory points at specified intervals.
     */
    public void play() {
        // Return immediately if the Google Map is not initialized or there are no trajectory coordinates.
        if (googleMap == null || trajectoryCoordinates.isEmpty()) return;

        isPlaying = true;// Set playback status to true.

        // If starting playback from the beginning, clear the map and initialize the polyline for trajectory.
        if (playbackIndex == 0) {
            googleMap.clear(); // Clear any previous markers or drawings on the map.
            googleMap.getUiSettings().setZoomGesturesEnabled(true); // Enable zoom gestures on the map.
            trajectoryPolyline = googleMap.addPolyline(new PolylineOptions().color(Color.BLUE).width(1)); // Initialize the polyline for trajectory visualization.
        }

        // Post a runnable to the playback handler for updating the map at intervals.
        playbackHandler.post(playbackRunnable = new Runnable() {
            @Override
            public void run() {
                // Check if there are more points to process in the trajectory.
                if (playbackIndex < trajectoryCoordinates.size()) {
                    double[] point = trajectoryCoordinates.get(playbackIndex); // Get the current trajectory point.
                    LatLng latLng = new LatLng(point[0], point[1]); // Create a LatLng object for the point.

                    // Add a circle to the map at the current location to visualize the point.
                    googleMap.addCircle(new CircleOptions()
                            .center(latLng) // Set the center of the circle.
                            .radius(0.02) // Set the radius of the circle.
                            .fillColor(Color.BLUE) // Set the fill color of the circle.
                            .strokeColor(Color.BLUE) // Set the stroke color of the circle.
                            .strokeWidth(5f)); // Set the stroke width.

                    // Update the trajectory polyline with the current point.
                    if (trajectoryPolyline != null) {
                        List<LatLng> points = new ArrayList<>(trajectoryPolyline.getPoints()); // Retrieve existing points from the polyline.
                        points.add(latLng); // Add the new point to the list.
                        trajectoryPolyline.setPoints(points); // Update the polyline with the new points list.
                    }

                    // Move the camera to the current location for a better view.
                    googleMap.moveCamera(CameraUpdateFactory.newLatLng(latLng));

                    // Call the updateUIandPosition method in the ReplayFragment to update the UI.
                    if (callback != null) {
                        callback.updateUIandPosition(latLng); // Notify the callback with the current position.
                    }

                    playbackIndex++; // Move to the next index in the trajectory.

                    // Schedule the next update after the specified interval.
                    playbackHandler.postDelayed(this, POINT_INTERVAL);
                }
            }
        });
    }

    /**
     * Pauses the playback of the recorded trajectory.
     * This method stops the playback updates by removing any pending
     * callbacks associated with the playback runnable and sets the
     * playback status to false.
     */
    public void pause() {
        isPlaying = false; // Set the playback status to false.
        playbackHandler.removeCallbacks(playbackRunnable); // Stop any ongoing updates for playback.
    }

    /**
     * Restarts the playback of the recorded trajectory from the beginning.
     * This method pauses any ongoing playback, resets the playback index,
     * clears the Google Map of any drawings, and initializes a new replay.
     */
    public void replay() {
        pause(); // Pause any ongoing playback.

        playbackIndex = 0; // Reset the playback index to start from the beginning.

        googleMap.clear(); // Clear the Google Map of previous drawings and markers.

        trajectoryPolyline = null; // Reset the polyline variable to prepare for a new trajectory.

        play(); // Start the playback again from the beginning.
    }

    /**
     * Displays the full trajectory on the Google Map by drawing all points
     * stored in the trajectoryCoordinates list. This method clears any existing
     * markers or paths on the map, initializes a new polyline to represent the
     * trajectory, and centers the camera on the starting point of the trajectory.
     */
    public void displayFullTrajectory() {
        // Return if the Google Map is not initialized or there are no trajectory coordinates.
        if (googleMap == null || trajectoryCoordinates.isEmpty()) return;

        // Clear the map of any previous drawings or markers.
        googleMap.clear();

        // Enable user gesture controls for zooming in and out.
        googleMap.getUiSettings().setZoomGesturesEnabled(true);

        // Create a PolylineOptions object to configure the appearance of the trajectory line.
        PolylineOptions polylineOptions = new PolylineOptions().color(Color.BLUE).width(5);

        // Add all trajectory coordinates to the polyline options.
        for (double[] coordinate : trajectoryCoordinates) {
            polylineOptions.add(new LatLng(coordinate[0], coordinate[1]));  // Convert each coordinate to LatLng and add to polyline.
        }

        // Draw the polyline on the map to represent the full trajectory.
        trajectoryPolyline = googleMap.addPolyline(polylineOptions);

        // Move the camera to the starting point of the trajectory with an initial zoom level of 20.
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                new LatLng(trajectoryCoordinates.get(0)[0], trajectoryCoordinates.get(0)[1]), 20));
    }

    /**
     * Computes the total duration of the trajectory playback in milliseconds.
     * This method multiplies the number of trajectory coordinates by the
     * interval between points.
     *
     * @return The total duration in milliseconds.
     */
    public int getTotalDuration() {
        return trajectoryCoordinates.size() * POINT_INTERVAL; // Total duration in milliseconds.
    }

    /**
     * Calculates the current progress of the trajectory playback in milliseconds.
     * This method determines the current progress by multiplying the playback index
     * by the interval between points.
     *
     * @return The current progress in milliseconds.
     */
    public int getCurrentProgress() {
        return playbackIndex * POINT_INTERVAL; // Current progress in milliseconds.
    }

    /**
     * Checks if the trajectory playback is currently active.
     *
     * @return true if the playback is active; false otherwise.
     */
    public boolean isPlaying() {
        return isPlaying; // Return the playback status.
    }
}