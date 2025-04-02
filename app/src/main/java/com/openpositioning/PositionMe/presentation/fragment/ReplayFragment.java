package com.openpositioning.PositionMe.presentation.fragment;

import android.content.Context;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Toast;
// Code by Guilherme: added necessary imports
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.Tag;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.PolylineOptions;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.data.local.TrajParser;
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;
import com.openpositioning.PositionMe.sensors.WiFiPositioning;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sub fragment of Replay Activity. Fragment that replays trajectory data on a map.
 *
 * The ReplayFragment is responsible for visualizing and replaying trajectory data captured during
 * previous recordings. It loads trajectory data from a JSON file, updates the map with user movement,
 * and provides UI controls for playback, pause, and seek functionalities.
 *
 * Features:
 * - Loads trajectory data from a file and displays it on a map.
 * - Provides playback controls including separate play and pause buttons, replay (rewind), and go to end.
 * - Updates the trajectory dynamically as playback progresses.
 * - Allows users to manually seek through the recorded trajectory.
 * - Integrates with {@link TrajectoryMapFragment} for map visualization.
 *
 * @see ReplayActivity The activity managing the replay workflow.
 * @see TrajParser Utility class for parsing trajectory data.
 * @Author Jamie Arnott
 * @Author Guilherme Barreiros
 * @Author Marco Bancalari-Ruiz
 */
public class ReplayFragment extends Fragment {

    private static final String TAG = "ReplayFragment";

    // GPS start location (received from ReplayActivity)
    private float initialLat = 0f;
    private float initialLon = 0f;
    private String filePath = "";
    private int lastIndex = -1;

    // UI Controls
    private TrajectoryMapFragment trajectoryMapFragment;
    private ImageButton playButton, pauseButton, replayButton, goToEndButton;
    private Button speedHalfButton, speedDoubleButton, viewStatsButton;
    private SeekBar playbackSeekBar;
    // Spinner for selecting data source.
    private Spinner dataSourceSpinner;
    // Holds the selected mode ("PDR", "GNSS", or "WiFi")
    private String selectedMode = "EKF";

    // Playback-related
    private final Handler playbackHandler = new Handler();
    private long playbackInterval = 500; // Default interval (ms)
    private List<TrajParser.ReplayPoint> replayData = new ArrayList<>();
    private int currentIndex = 0;
    private boolean isPlaying = false;

    private LatLng prevWiFiLocation;
    public static List<LatLng> EKF_data = new ArrayList<>();

    public static List<LatLng> GNSS_data = new ArrayList<>();

    public static List<LatLng> WIFI_data = new ArrayList<>();

    public static List<LatLng> PDR_data = new ArrayList<>();


    // code by Guilherme: New field to store previous replay point for computing bearing.
    private LatLng previousReplayPoint = null;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Retrieve transferred data from ReplayActivity
        if (getArguments() != null) {
            filePath = getArguments().getString(ReplayActivity.EXTRA_TRAJECTORY_FILE_PATH, "");
            initialLat = getArguments().getFloat(ReplayActivity.EXTRA_INITIAL_LAT, 0f);
            initialLon = getArguments().getFloat(ReplayActivity.EXTRA_INITIAL_LON, 0f);
        }

        File trajectoryFile = new File(filePath);
        if (!trajectoryFile.exists() || !trajectoryFile.canRead()) return;

        List<TrajParser.ReplayPoint> parsedData = TrajParser.parseTrajectoryData(filePath, requireContext(), initialLat, initialLon);
        replayData = parsedData;
        TrajParser.replayData = parsedData;

        // Code By Guilherme: Populate all trajectory lists immediately after data is parsed
        for (int i = 0; i < replayData.size(); i++) {
            TrajParser.ReplayPoint point = replayData.get(i);

            if (point.pdrLocation != null) {
                PDR_data.add(point.pdrLocation);
            }
            if (point.gnssLocation != null) {
                GNSS_data.add(point.gnssLocation);
            }
            if (point.cachedWiFiLocation != null) {
                WIFI_data.add(point.cachedWiFiLocation);
            }
            // Pre-compute EKF data
            LatLng prevPDR = (i > 0) ? replayData.get(i - 1).pdrLocation : point.pdrLocation;
            LatLng ekfPoint = SensorFusion.getInstance().EKF_replay(
                    point.cachedWiFiLocation != null ? point.cachedWiFiLocation : prevWiFiLocation,
                    point.pdrLocation,
                    prevPDR,
                    point.gnssLocation
            );
            if (ekfPoint != null) {
                EKF_data.add(ekfPoint);
            }
        }

    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_replay, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize map fragment
        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.replayMapFragmentContainer);
        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.replayMapFragmentContainer, trajectoryMapFragment)
                    .commit();
        }

        // code by Jamie Arnott: WiFi Positioning
        // Use WiFi positioning from the first replay point if available.
        if (!replayData.isEmpty()) {
            TrajParser.ReplayPoint firstReplayPoint = replayData.get(0);
            if (firstReplayPoint.wifiSamples != null && !firstReplayPoint.wifiSamples.isEmpty()) {
                JSONObject wifiAccessPoints = new JSONObject();
                for (Traj.WiFi_Sample sample : firstReplayPoint.wifiSamples) {
                    for (Traj.Mac_Scan macScan : sample.getMacScansList()) {
                        try {
                            wifiAccessPoints.put(String.valueOf(macScan.getMac()), macScan.getRssi());
                        } catch (JSONException e) {
                            Log.e(TAG, "Error creating WiFi fingerprint JSON: " + e.getMessage());
                        }
                    }
                }
                JSONObject wifiFingerPrint = new JSONObject();
                try {
                    wifiFingerPrint.put("wf", wifiAccessPoints);
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
                new WiFiPositioning(getContext()).request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                    @Override
                    public void onSuccess(LatLng wifiLocation, int floor) {
                        Log.i(TAG, "WiFi positioning successful. Location: " + wifiLocation);
                        new AlertDialog.Builder(requireContext())
                                .setTitle("Choose Positioning Method")
                                .setMessage("WiFi-based positioning is available for this trajectory. Do you want to use WiFi positioning?")
                                .setPositiveButton("Use WiFi", (dialog, which) -> {
                                    setupInitialMapPosition((float) wifiLocation.latitude, (float) wifiLocation.longitude, floor);
                                    dialog.dismiss();
                                })
                                .setNegativeButton("Use GNSS", (dialog, which) -> {
                                    showGnssChoiceDialog();
                                    dialog.dismiss();
                                })
                                .setCancelable(false)
                                .show();
                    }
                    @Override
                    public void onError(String message) {
                        Log.e(TAG, "WiFi positioning failed: " + message);
                        if (hasAnyGnssData(replayData)) {
                            showGnssChoiceDialog();
                        } else {
                            if (initialLat != 0f || initialLon != 0f) {
                                LatLng startPoint = new LatLng(initialLat, initialLon);
                                trajectoryMapFragment.setInitialCameraPosition(startPoint);
                            }
                        }
                    }
                });
            } else {
                if (hasAnyGnssData(replayData)) {
                    showGnssChoiceDialog();
                } else {
                    if (initialLat != 0f || initialLon != 0f) {
                        LatLng startPoint = new LatLng(initialLat, initialLon);
                        trajectoryMapFragment.setInitialCameraPosition(startPoint);
                    }
                }
            }
            // add tag marker if firstReplayPoint contains one
            if (firstReplayPoint.tagPoint != null){
                trajectoryMapFragment.addTagMarker(firstReplayPoint.tagPoint.location, firstReplayPoint.tagPoint.label);
            }
            Log.d("TagPoint", "Start Tag Point: " + firstReplayPoint.tagPoint);
        } else {
            Log.e(TAG, "Replay data is empty, cannot determine initial location.");
        }

        playButton = view.findViewById(R.id.playButton);
        pauseButton = view.findViewById(R.id.pauseButton);
        replayButton = view.findViewById(R.id.replayButton);
        goToEndButton = view.findViewById(R.id.goToEndButton);
        speedHalfButton = view.findViewById(R.id.speedHalfButton);
        speedDoubleButton = view.findViewById(R.id.speedDoubleButton);
        playbackSeekBar = view.findViewById(R.id.playbackSeekBar);
        dataSourceSpinner = view.findViewById(R.id.dataSourceSpinner);

        //Code by Guilherme: View Stats button
        viewStatsButton = view.findViewById(R.id.viewStatsButton);
        viewStatsButton.setOnClickListener(v -> {
            // Navigate to StatsFragment
            getParentFragmentManager()
                    .beginTransaction()
                    .replace(R.id.replayRoot, new StatsFragment())
                    .addToBackStack(null)
                    .commit();
        });
        // code by Guilherme: Add the dropdown list adapter with desired options
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"PDR", "GNSS", "WiFi", "EKF"});
        dataSourceSpinner.setAdapter(adapter);
        dataSourceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selectedMode = parent.getItemAtPosition(position).toString();
                // code by Guilherme: Set polyline color based on selection.
                trajectoryMapFragment.setPolylineColor(getColorForMode(selectedMode));
                trajectoryMapFragment.clearMapAndReset(); // code by Guilherme: Clear previous trace when mode changes.
                currentIndex = 0;
                playbackSeekBar.setProgress(0);
                updateSeekBarMax();
                // Reset the previous replay point for bearing calculation.
                previousReplayPoint = null; // code by Guilherme
                drawReplayPointWithMode(currentIndex);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        updateSeekBarMax();
        // buttons added by Guilherme
        playButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) return;
            if (!isPlaying) {
                isPlaying = true;
                playButton.setEnabled(false);
                pauseButton.setEnabled(true);
                // code by Guilherme: Do not clear the map here so the polyline trace accumulates.
                playbackHandler.postDelayed(playbackRunnable, playbackInterval);
            }
        });

        pauseButton.setOnClickListener(v -> stopPlaying());

        speedHalfButton.setOnClickListener(v -> {
            playbackInterval = 1000;
            restartPlaybackIfPlaying();
        });

        speedDoubleButton.setOnClickListener(v -> {
            playbackInterval = 250;
            restartPlaybackIfPlaying();
        });

        replayButton.setOnClickListener(v -> {
            currentIndex = 0;
            playbackSeekBar.setProgress(0);
            trajectoryMapFragment.clearMapAndReset(); // code by Guilherme: Clear trace when restarting.
            // code by Guilherme: Reset previousReplayPoint for bearing calculation.
            previousReplayPoint = null;
            drawReplayPointWithMode(currentIndex);
        });

        goToEndButton.setOnClickListener(v -> {
            currentIndex = replayData.size() - 1;
            playbackSeekBar.setProgress(currentIndex);
            trajectoryMapFragment.clearMapAndReset(); // code by Guilherme: Clear trace before drawing final point.
            // Redraw all points up to the last one.
            for (int i = 0; i <= currentIndex && i < replayData.size(); i++) {
                drawReplayPointWithMode(i);
            }
            stopPlaying();
        });

        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentIndex = progress;
                    trajectoryMapFragment.clearMapAndReset(); // code by Guilherme: Clear trace for manual scrubbing.
                    // Reset previousReplayPoint.
                    previousReplayPoint = null; // code by Guilherme
                    for (int i = 0; i <= currentIndex && i < replayData.size(); i++) {
                        drawReplayPointWithMode(i);
                    }
                    Log.i(TAG, "SeekBar moved by user. New index: " + currentIndex);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { stopPlaying(); }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        if (!replayData.isEmpty()) {
            trajectoryMapFragment.clearMapAndReset();
            // code by Guilherme: Reset previousReplayPoint.
            previousReplayPoint = null;
            drawReplayPointWithMode(0);
        }

    }

    private void restartPlaybackIfPlaying() {
        if (isPlaying) {
            playbackHandler.removeCallbacks(playbackRunnable);
            playbackHandler.postDelayed(playbackRunnable, playbackInterval);
        }
    }

    // code by Jamie Arnott
    /**
     * Method to set the maximum length for the seekbar using method getVisibleDataSize
     */
    private void updateSeekBarMax() {
        int size = getVisibleDataSize(selectedMode);
        if (size > 0) {
            playbackSeekBar.setMax(size - 1);
        }
    }

    // Code by Jamie Arnott
    /**
     * Method to get the data sizes for displaying different data types
     *
     * Includes GNSS, WiFi, PDR, and Extended Kalman-Filter
     * @param mode
     * @return int representing data size
     */
    private int getVisibleDataSize(String mode) {
        switch (mode) {
            case "GNSS":
                return (int) replayData.stream().filter(p -> p.gnssLocation != null).count();
            case "PDR":
                return replayData.size();
            default:
                return (int) replayData.stream().filter(p -> p.cachedWiFiLocation != null || (p.wifiSamples != null && !p.wifiSamples.isEmpty())).count();
        }
    }

    private final Runnable playbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying || currentIndex >= replayData.size()) {
                stopPlaying();
                if (currentIndex >= replayData.size()) {
                    viewStatsButton.setVisibility(View.VISIBLE); //  Show button once complete
                }
                return;
            }
            if (currentIndex == 0) {
                trajectoryMapFragment.clearMapAndReset(); // only clear once at the start
            }


            drawReplayPointWithMode(currentIndex);
            TrajParser.ReplayPoint point = replayData.get(currentIndex);
            // add the tag points if they exist in the replay data
            if (point.tagPoint != null){
                trajectoryMapFragment.addTagMarker(point.tagPoint.location, point.tagPoint.label);
            }
            Log.d("TagPoint", "Tag Point at index " + currentIndex + ": " + point.tagPoint);
            currentIndex++;
            playbackSeekBar.setProgress(currentIndex);
            playbackHandler.postDelayed(this, playbackInterval);
        }
    };

    // code by Jamie Arnott & Guilherme

    /**
     * Method to draw the next replayPoint on the map using the selected method from the dropdown.
     * Uses WiFi positioning with restful API requests to the openpositioning server
     * @param index
     */
    private void drawReplayPointWithMode(int index) {
        if (index < 0 || index >= replayData.size()) return;

        TrajParser.ReplayPoint p = replayData.get(index);
        LatLng currentPoint = null;

        switch (selectedMode) {
            case "GNSS":
                Log.d(TAG, "GNSS Mode - gnssLocation: " + p.gnssLocation);
                if (p.gnssLocation != null) {
                    trajectoryMapFragment.updateUserLocation(p.gnssLocation, p.orientation);
                } else {
                    trajectoryMapFragment.updateUserLocation(p.pdrLocation, p.orientation);
                    currentPoint = p.pdrLocation;
                }
                break;


            case "WiFi":
                // code by Jamie Arnott: WiFi Positioning
                if (p.cachedWiFiLocation != null) { // cached location updated and stored to maintain marker in same location if WiFi request returns error
                    trajectoryMapFragment.updateUserLocation(p.cachedWiFiLocation, p.orientation);
                    currentPoint = p.cachedWiFiLocation;
                } else if (p.wifiSamples != null && !p.wifiSamples.isEmpty()) {
                    // check that the phone has WiFi connection before performing WiFi fingerprint creation and request
                    ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                    NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                    boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
                    if (isConnected) {
                        JSONObject wifiFingerprint = new JSONObject();
                        JSONObject wifiAccessPoints = new JSONObject();
                        for (Traj.WiFi_Sample sample : p.wifiSamples) {
                            for (Traj.Mac_Scan macScan : sample.getMacScansList()) {
                                try {
                                    wifiAccessPoints.put(String.valueOf(macScan.getMac()), macScan.getRssi());
                                } catch (JSONException e) {
                                    Log.e(TAG, "WiFi JSON error: " + e.getMessage());
                                }
                            }
                        }
                        try {
                            wifiFingerprint.put("wf", wifiAccessPoints); // fingerprint creation
                        } catch (JSONException e) {
                            Log.e(TAG, "WiFi fingerprint JSON failed: " + e.getMessage());
                        }

                        new WiFiPositioning(getContext()).request(wifiFingerprint, new WiFiPositioning.VolleyCallback() {
                            @Override
                            public void onSuccess(LatLng location, int floor) {
                                Log.d(TAG, "WiFi Positioning Successful");
                                p.cachedWiFiLocation = location; // update cached location for next run
                                float bearing = p.orientation;
                                if (previousReplayPoint != null) {
                                    bearing = (float) UtilFunctions.calculateBearing(previousReplayPoint, location); // Accurate bearing

                                }
                                trajectoryMapFragment.updateUserLocation(location, bearing); // add new point to map
                                trajectoryMapFragment.addPolylinePoint(location); // Add trace point
                                previousReplayPoint = location; // update previous location
                                prevWiFiLocation = location;
                                trajectoryMapFragment.displayNucleusFloorLevel(floor); // update the floor level inside nucleus

                            }

                            @Override
                            public void onError(String message) {
                                Log.w(TAG, "WiFi Positioning failed: " + message);
                                // Do not update map if WiFi fails
                            }
                        });

                        return; // Async, return early to avoid using fallback
                    }else{
                        Log.d(TAG, "WiFi Network Not Connected");
                    }
                }
                break;
            // code by Jamie Arnott: EKF Positioning Integration into replay
            case "PDR":
                currentPoint = p.pdrLocation;
                break;


            default: // EKF
                // call EKF_point() with the current index
                currentPoint = EKF_data.get(index);
                if (currentPoint != null){
                    trajectoryMapFragment.updateUserLocation(currentPoint,p.orientation); // update location
                }
                break;
        }
        // currentPoint remains null if WiFi positioning used due to asynchronous return to avoid callback
        // so positioning done inside onSuccess() method of WiFiPositioning.request()
        if (currentPoint != null) { // plot next point using updated currentPoint
            float bearing = p.orientation;
            if (previousReplayPoint != null) {
                bearing = (float) UtilFunctions.calculateBearing(previousReplayPoint, currentPoint); // code by Guilherme
            }

            trajectoryMapFragment.updateUserLocation(currentPoint, bearing);
            trajectoryMapFragment.addPolylinePoint(currentPoint); // Draw trace point
            previousReplayPoint = currentPoint;

            if (selectedMode.equals("GNSS") && p.gnssLocation != null) {
                trajectoryMapFragment.updateGNSS(p.gnssLocation);
            }
        }
    }



    // code by Marco Bancalari-Ruiz & Jamie Arnott: Fuse data using EKF filter

    /**
     * Method to compute the fused EKF point for a given replayPoint using the index of replayData
     *
     * This makes a call to the EKF_replay() function in SensorFusion.java
     * @param index
     * @return LatLng of the fused point
     */
    private LatLng EKF_point(int index){
        TrajParser.ReplayPoint p = replayData.get(index);
        TrajParser.ReplayPoint prev = replayData.get(index);
        if (index > 0){
            prev = replayData.get(index-1);
        }

        LatLng EKF_fused_point;
        // get PDR data
        LatLng pdrLatLng = p.pdrLocation;
        LatLng prevPDR = prev.pdrLocation;
        LatLng gnssLocation = p.gnssLocation;
        // handle WiFi data
        if (p.cachedWiFiLocation != null) {
            // do EKF using cached WiFi Location

            EKF_fused_point = SensorFusion.getInstance().EKF_replay(p.cachedWiFiLocation, pdrLatLng, prevPDR,gnssLocation);
        } else if (p.wifiSamples != null && !p.wifiSamples.isEmpty()) {
            JSONObject wifiFingerprint = new JSONObject();
            JSONObject wifiAccessPoints = new JSONObject();
            for (Traj.WiFi_Sample sample : p.wifiSamples) {
                for (Traj.Mac_Scan macScan : sample.getMacScansList()) {
                    try {
                        wifiAccessPoints.put(String.valueOf(macScan.getMac()), macScan.getRssi());
                    } catch (JSONException e) {
                        Log.e(TAG, "WiFi JSON error: " + e.getMessage());
                    }
                }
            }
            try {
                wifiFingerprint.put("wf", wifiAccessPoints);
            } catch (JSONException e) {
                Log.e(TAG, "WiFi fingerprint JSON failed: " + e.getMessage());
            }

            new WiFiPositioning(getContext()).request(wifiFingerprint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng location, int floor) {
                    Log.d(TAG, "WiFi Positioning Successful");
                    trajectoryMapFragment.displayNucleusFloorLevel(floor);
                    p.cachedWiFiLocation = location; // keep this

                    prevWiFiLocation = location; // keep this to update prev location
                    // do the EKF using location

                }

                @Override
                public void onError(String message) {
                    Log.w(TAG, "WiFi Positioning failed: " + message);
                }
            });
            EKF_fused_point = SensorFusion.getInstance().EKF_replay(prevWiFiLocation, pdrLatLng, prevPDR, gnssLocation);
        } else {
            if (prevWiFiLocation != null) {
                // do EKF using prevWiFiLocation
                EKF_fused_point = SensorFusion.getInstance().EKF_replay(prevWiFiLocation, pdrLatLng, prevPDR, gnssLocation);
            } else {
                // do nothing
                return null;
            }
        }

        return EKF_fused_point;
    }

    // code by Guilherme

    /**
     * Method to return the colour of the polyline to be drawn for the given playback mode
     * @param mode
     * @return int representing the colour of the line
     */
    private int getColorForMode(String mode) {
        switch (mode) {
            case "GNSS": return android.graphics.Color.BLUE;
            case "WiFi": return android.graphics.Color.GREEN;
            case "PDR":  return android.graphics.Color.RED;
            default: return android.graphics.Color.CYAN;
        }
    }

    private void stopPlaying() {
        isPlaying = false;
        playbackHandler.removeCallbacks(playbackRunnable);
        playButton.setEnabled(true);
        pauseButton.setEnabled(false);
    }

    @Override
    public void onPause() {
        super.onPause();
        stopPlaying();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopPlaying();
    }

    // Helper methods for GNSS choice dialog and initial map position.
    private boolean hasAnyGnssData(List<TrajParser.ReplayPoint> data) {
        for (TrajParser.ReplayPoint point : data) {
            if (point.gnssLocation != null) {
                return true;
            }
        }
        return false;
    }

    private void showGnssChoiceDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Choose Starting Location")
                .setMessage("GNSS data is found in the file. Would you like to use the file's GNSS as the start, or your manually picked coordinates?")
                .setPositiveButton("File's GNSS", (dialog, which) -> {
                    LatLng firstGnss = getFirstGnssLocation(replayData);
                    if (firstGnss != null) {
                        setupInitialMapPosition((float) firstGnss.latitude, (float) firstGnss.longitude, 1);
                    } else {
                        setupInitialMapPosition(initialLat, initialLon, 0);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("Manual Set", (dialog, which) -> {
                    setupInitialMapPosition(initialLat, initialLon, 0);
                    dialog.dismiss();
                })
                .setCancelable(false)
                .show();
    }

    // Code Updated by Jamie Arnott
    /**
     * Method to set up the initial map location once the user selects either WiFi, GNSS, or manual
     *
     * Updated from original method to also set the floor if WiFi data is used
     * @param latitude
     * @param longitude
     * @param floor
     */
    private void setupInitialMapPosition(float latitude, float longitude, int floor) {
        LatLng startPoint = new LatLng(latitude, longitude);
        Log.i(TAG, "Setting initial map position: " + startPoint);
        trajectoryMapFragment.setInitialCameraPosition(startPoint);
        trajectoryMapFragment.displayNucleusFloorLevel(floor);
    }

    private LatLng getFirstGnssLocation(List<TrajParser.ReplayPoint> data) {
        for (TrajParser.ReplayPoint point : data) {
            if (point.gnssLocation != null) {
                return new LatLng(point.gnssLocation.latitude, point.gnssLocation.longitude);
            }
        }
        return null;
    }

    // code by Guilherme
    private void showReplayTags() {
        for (TrajParser.TagPoint tag : TrajParser.tagPoints) {
            if (tag.location != null && tag.label != null) {
                trajectoryMapFragment.addTagMarker(tag.location, tag.label);
            }
        }
    }



}
