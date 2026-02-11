package com.openpositioning.PositionMe.presentation.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.util.Log;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.material.button.MaterialButton;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.presentation.activity.RecordingActivity;
import com.openpositioning.PositionMe.sensors.Observer;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.SensorTypes;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import org.geojson.MultiLineString;
import org.geojson.MultiPolygon;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.geojson.Feature;
import org.geojson.FeatureCollection;
import org.geojson.GeoJsonObject;
import org.geojson.LngLatAlt;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

/**
 * Fragment responsible for managing the recording process of trajectory data.
 * <p>
 * The RecordingFragment serves as the interface for users to initiate, monitor, and
 * complete trajectory recording. It integrates sensor fusion data to track user movement
 * and updates a map view in real time. Additionally, it provides UI controls to cancel,
 * stop, and monitor recording progress.
 * <p>
 * Features:
 * - Starts and stops trajectory recording.
 * - Displays real-time sensor data such as elevation and distance traveled.
 * - Provides UI controls to cancel or complete recording.
 * - Uses {@link TrajectoryMapFragment} to visualize recorded paths.
 * - Manages GNSS tracking and error display.
 *
 * @see TrajectoryMapFragment The map fragment displaying the recorded trajectory.
 * @see RecordingActivity The activity managing the recording workflow.
 * @see SensorFusion Handles sensor data collection.
 * @see SensorTypes Enumeration of available sensor types.
 * @see Observer Interface for handling server responses
 *
 * @author Shu Gu
 */

public class RecordingFragment extends Fragment implements Observer {

    // UI elements
    private MaterialButton completeButton, cancelButton;
    private FloatingActionButton timedMarker;
    private ImageView recIcon;
    private ProgressBar timeRemaining;
    private TextView elevation, distanceTravelled, gnssError;

    // App settings
    private SharedPreferences settings;

    // Sensor & data logic
    private SensorFusion sensorFusion;
    private Handler refreshDataHandler;
    private CountDownTimer autoStop;

    // Distance tracking
    private float distance = 0f;
    private float previousPosX = 0f;
    private float previousPosY = 0f;

    // References to the child map fragment
    private TrajectoryMapFragment trajectoryMapFragment;
    private int timed_marker_counter = 1;

    private final Runnable refreshDataTask = new Runnable() {
        @Override
        public void run() {
            updateUIandPosition();
            // Loop again
            refreshDataHandler.postDelayed(refreshDataTask, 200);
        }
    };

    public RecordingFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.sensorFusion = SensorFusion.getInstance();
        this.sensorFusion.registerForServerUpdate(this);
        Context context = requireActivity();
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.refreshDataHandler = new Handler();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // Inflate only the "recording" UI parts (no map)
        return inflater.inflate(R.layout.fragment_recording, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Child Fragment: the container in fragment_recording.xml
        // where TrajectoryMapFragment is placed
        trajectoryMapFragment = (TrajectoryMapFragment)
            getChildFragmentManager().findFragmentById(R.id.trajectoryMapFragmentContainer);

        // If not present, create it
        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            getChildFragmentManager()
                .beginTransaction()
                .replace(R.id.trajectoryMapFragmentContainer, trajectoryMapFragment)
                .commit();
        }

        // Initialize UI references
        elevation = view.findViewById(R.id.currentElevation);
        distanceTravelled = view.findViewById(R.id.currentDistanceTraveled);
        gnssError = view.findViewById(R.id.gnssError);

        completeButton = view.findViewById(R.id.stopButton);
        cancelButton = view.findViewById(R.id.cancelButton);
        timedMarker = view.findViewById(R.id.dropMarkerButton);
        recIcon = view.findViewById(R.id.redDot);
        timeRemaining = view.findViewById(R.id.timeRemainingBar);

        // Hide or initialize default values
        gnssError.setVisibility(View.GONE);
        elevation.setText(getString(R.string.elevation, "0"));
        distanceTravelled.setText(getString(R.string.meter, "0"));

        // Buttons
        completeButton.setOnClickListener(v -> {
            // Stop recording & go to correction
            if (autoStop != null) autoStop.cancel();
            sensorFusion.stopRecording();
            ((RecordingActivity) requireActivity()).showCorrectionScreen();
        });

        // Cancel button with confirmation dialog
        cancelButton.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(requireActivity())
                .setTitle("Confirm Cancel")
                .setMessage(
                    "Are you sure you want to cancel the recording? "
                    + "Your progress will be lost permanently!"
                )
                .setNegativeButton("Yes", (dialogInterface, which) -> {
                    // User confirmed cancellation
                    sensorFusion.stopRecording();
                    if (autoStop != null) autoStop.cancel();
                    requireActivity().onBackPressed();
                })
                .setPositiveButton("No", (dialogInterface, which) -> {
                    // User cancelled the dialog. Do nothing.
                    dialogInterface.dismiss();
                })
                .create(); // Create the dialog but do not show it yet

            // Show the dialog and change the button color
            dialog.setOnShowListener(dialogInterface -> {
                Button negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                negativeButton.setTextColor(Color.RED); // Set "Yes" button color to red
            });

            dialog.show(); // Finally, show the dialog
        });

        timedMarker.setOnClickListener(v -> {
            if (trajectoryMapFragment == null) return;
            LatLng marker_location = trajectoryMapFragment.getCurrentLocation();
            if (marker_location == null) return;
            long tMs = sensorFusion.getRecordingElapsedMs();
            String timeLabel = android.text.format.DateFormat.format(
        "HH:mm:ss",
                tMs
            ).toString();
            trajectoryMapFragment.addTimeMarker(marker_location, timeLabel, timed_marker_counter);
            double GNNSAltitude = sensorFusion.getGNSSAltitude();
            sensorFusion.addTestPoint(
                marker_location.latitude,
                marker_location.longitude,
                GNNSAltitude
            );
            timed_marker_counter++;
        });

        // The blinking effect for recIcon
        blinkingRecordingIcon();

        // Start the timed or indefinite UI refresh
        if (this.settings.getBoolean("split_trajectory", false)) {
            // A maximum recording time is set
            long limit = this.settings.getInt("split_duration", 30) * 60000L;
            timeRemaining.setMax((int) (limit / 1000));
            timeRemaining.setProgress(0);
            timeRemaining.setScaleY(3f);

            autoStop = new CountDownTimer(limit, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    timeRemaining.incrementProgressBy(1);
                    updateUIandPosition();
                }

                @Override
                public void onFinish() {
                    sensorFusion.stopRecording();
                    ((RecordingActivity) requireActivity()).showCorrectionScreen();
                }
            }.start();
            Log.i(
            "RecordingFragment",
            "Timer started for " + (int) (limit / 1000) + " minutes"
            );
        } else {
            // No set time limit, just keep refreshing
            refreshDataHandler.post(refreshDataTask);
        }
    }
    
    /**
     * Update the UI with sensor data and pass map updates to TrajectoryMapFragment.
     */
    private void updateUIandPosition() {
        float[] pdrValues = sensorFusion.getSensorValueMap().get(SensorTypes.PDR);
        if (pdrValues == null) return;

        // Distance
        distance += (float) Math.sqrt(Math.pow(pdrValues[0] - previousPosX, 2)
            + Math.pow(pdrValues[1] - previousPosY, 2));
        distanceTravelled.setText(getString(R.string.meter, String.format("%.2f", distance)));

        // Elevation
        float elevationVal = sensorFusion.getElevation();
        elevation.setText(getString(R.string.elevation, String.format("%.1f", elevationVal)));

        // Current location
        // Convert PDR coordinates to actual LatLng if you have a known starting lat/lon
        // Or simply pass relative data for the TrajectoryMapFragment to handle
        // For example:
        float[] latLngArray = sensorFusion.getGNSSLatitude(true);
        if (latLngArray != null) {
            LatLng oldLocation = trajectoryMapFragment.getCurrentLocation(); // or store locally
            LatLng newLocation = UtilFunctions.calculateNewPos(
                oldLocation == null ? new LatLng(latLngArray[0], latLngArray[1]) : oldLocation,
                new float[]{ pdrValues[0] - previousPosX, pdrValues[1] - previousPosY }
            );

            // Pass the location + orientation to the map
            if (trajectoryMapFragment != null) {
                trajectoryMapFragment.updateUserLocation(
                    newLocation,
                    (float) Math.toDegrees(sensorFusion.passOrientation())
                );
            }

            // Retrieve floorplans for nearby buildings
            sensorFusion.requestFloorplans(newLocation);
        }

        // GNSS logic if you want to show GNSS error, etc.
        float[] gnss = sensorFusion.getSensorValueMap().get(SensorTypes.GNSSLATLONG);
        if (gnss != null && trajectoryMapFragment != null) {
            // If user toggles showing GNSS in the map, call e.g.
            if (trajectoryMapFragment.isGnssEnabled()) {
                LatLng gnssLocation = new LatLng(gnss[0], gnss[1]);
                LatLng currentLoc = trajectoryMapFragment.getCurrentLocation();
                if (currentLoc != null) {
                    double errorDist = UtilFunctions.distanceBetweenPoints(
                        currentLoc,
                        gnssLocation
                    );
                    gnssError.setVisibility(View.VISIBLE);
                    gnssError.setText(
                        String.format(getString(
                            R.string.gnss_error) + "%.2fm",
                            errorDist
                        )
                    );
                }
                trajectoryMapFragment.updateGNSS(gnssLocation);
            } else {
                gnssError.setVisibility(View.GONE);
                trajectoryMapFragment.clearGNSS();
            }
        }

        // Update previous
        previousPosX = pdrValues[0];
        previousPosY = pdrValues[1];
    }

    /**
     * Start the blinking effect for the recording icon.
     */
    private void blinkingRecordingIcon() {
        Animation blinking = new AlphaAnimation(1, 0);
        blinking.setDuration(800);
        blinking.setInterpolator(new LinearInterpolator());
        blinking.setRepeatCount(Animation.INFINITE);
        blinking.setRepeatMode(Animation.REVERSE);
        recIcon.startAnimation(blinking);
    }

    @Override
    public void onPause() {
        super.onPause();
        refreshDataHandler.removeCallbacks(refreshDataTask);
    }

    @Override
    public void onResume() {
        super.onResume();
        if(!this.settings.getBoolean("split_trajectory", false)) {
            refreshDataHandler.postDelayed(refreshDataTask, 500);
        }
    }

    /**
     * {@inheritDoc}
     * Called by {@link ServerCommunications} when the response
     * to the HTTP info request is received.
     *
     * @param singletonStringList   A single string wrapped in
     *                              an object array containing
     *                              the HTTP response from the
     *                              server.
     */
    @Override
    public void update(Object[] singletonStringList) {
        if (singletonStringList != null && singletonStringList.length > 0){
            String response = singletonStringList[0].toString();
            Log.d("RecordingFragment", "Received response: " + response);
            try{
                // Parse the JSON, and draw all possible buildings
                List<Map<String, Object>> entryList = processPOSTResponse(response);
                for (Map<String, Object> building : entryList){

                    String name = (String) building.get("name");
                    @SuppressWarnings("unchecked")
                    List<LatLng> outline = (List<LatLng>) building.get("outline");
                    @SuppressWarnings("unchecked")
                    Map<String, List<Object>> mapShapes =
                        (Map<String, List<Object>>) building.get("map_shapes");

                    trajectoryMapFragment.addBuilding(name, outline, mapShapes);
                }
            } catch (RuntimeException e){
                Log.e(
                "RecordingFragment",
                "Error processing server response: " + e.getMessage()
                );
            }
        }
    }

    /**
     * Parses the GeoJSON response for floor plans
     *
     * @param response The raw JSON string response from the server
     * @return A list of maps containing the data associated with every
     * building contained with the response
     * */
    private List<Map<String, Object>> processPOSTResponse(
        String response
    ) throws RuntimeException {
        List<Map<String, Object>> entryList = new ArrayList<>();

        try {
            JSONArray jsonArray = new JSONArray(response);

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject buildingEntry = jsonArray.getJSONObject(i);
                Map<String, Object> entryMap = new HashMap<>();

                // Part 1 - Building Name
                String name = buildingEntry.getString("name");

                // Part 2 - Building Outline
                FeatureCollection featureCollection = new ObjectMapper()
                    .readValue(buildingEntry.getString("outline"), FeatureCollection.class);

                /*
                * For every feature in the collection, extract the geometry,
                * extract the coordinates, and reconstruct the outline as
                * a list of LatLng points (ie, without the Alt, which is
                * always NaN)
                * */
                List<Feature> featuresOutline = featureCollection.getFeatures();
                List<LatLng> coordinates = new ArrayList<>();
                for (Feature feature : featuresOutline){
                    GeoJsonObject geometry = feature.getGeometry();
                    if (geometry instanceof MultiPolygon multiPolygon){
                        List<List<List<LngLatAlt>>> coordinatesLngLatAlt =
                            multiPolygon.getCoordinates();
                        for (LngLatAlt point : coordinatesLngLatAlt.get(0).get(0)){
                            coordinates.add(
                                new LatLng(point.getLatitude(), point.getLongitude())
                            );
                        }
                    }
                }

                // Part 3 - Floor plans
                Map<String, Object> floorplansJSON = new ObjectMapper()
                    .readValue(
                        buildingEntry.getString("map_shapes"),
                        new TypeReference<>() {}
                    );

                // Map to index floor plans by floor name
                Map<String, List<Object>> floorplans = new HashMap<>();

                for (String floorname : floorplansJSON.keySet()){
                    Object floor = floorplansJSON.get(floorname);
                    FeatureCollection fc = new ObjectMapper()
                        .convertValue(floor, FeatureCollection.class);

                    List<Feature> feats = fc.getFeatures();
                    List<Object> floorPolys = new ArrayList<>();
                    for (Feature feat : feats){
                        GeoJsonObject geometry = feat.getGeometry();
                        // Check for MultiLineString is for Nucleus only
                        if (geometry instanceof MultiPolygon multiPolygon){
                            floorPolys.add(multiPolygon);
                            Log.d(
                            "RecordingFragment",
                            name + ": geometry MultiPolygon"
                            );
                        } else if (geometry instanceof MultiLineString multiLineString){
                            floorPolys.add(multiLineString);
                            Log.d(
                            "RecordingFragment",
                            name + ": geometry MultiLineString"
                            );
                        } else {
                            Log.w(
                            "RecordingFragment",
                            name + " has no floorplans!"
                            );
                        }
                    }
                    floorplans.put(floorname, floorPolys);
                }

                entryMap.put("name", name);
                entryMap.put("outline", coordinates);
                entryMap.put("map_shapes", floorplans);

                entryList.add(entryMap);
                Log.d("RecordingFragment", "Building '" + name + "' parsed");
            }
        Log.d("RecordingFragment", entryList.size() + " buildings parsed");
    } catch (JSONException e) {
        Log.e("RecordingFragment", "JSON Parse Failed: " + e.getMessage());
    }
        return entryList;
    }
}
