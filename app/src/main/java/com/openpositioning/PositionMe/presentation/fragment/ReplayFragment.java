package com.openpositioning.PositionMe.presentation.fragment;

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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;
import com.openpositioning.PositionMe.data.local.TrajParser;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.sensors.WiFiPositioning;
import com.openpositioning.PositionMe.utils.UtilFunctions;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
    private String selectedMode = "PDR";

    // Playback-related
    private final Handler playbackHandler = new Handler();
    private long playbackInterval = 500; // Default interval (ms)
    private List<TrajParser.ReplayPoint> replayData = new ArrayList<>();
    private int currentIndex = 0;
    private boolean isPlaying = false;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Retrieve transferred data from ReplayActivity
        if (getArguments() != null) {
            filePath = getArguments().getString(ReplayActivity.EXTRA_TRAJECTORY_FILE_PATH, "");
            initialLat = getArguments().getFloat(ReplayActivity.EXTRA_INITIAL_LAT, 0f);
            initialLon = getArguments().getFloat(ReplayActivity.EXTRA_INITIAL_LON, 0f);
        }

        Log.i(TAG, "ReplayFragment received data:");
        Log.i(TAG, "Trajectory file path: " + filePath);
        Log.i(TAG, "Initial latitude: " + initialLat);
        Log.i(TAG, "Initial longitude: " + initialLon);

        // Check if file exists before parsing
        File trajectoryFile = new File(filePath);
        if (!trajectoryFile.exists()) {
            Log.e(TAG, "ERROR: Trajectory file does NOT exist at: " + filePath);
            return;
        }
        if (!trajectoryFile.canRead()) {
            Log.e(TAG, "ERROR: Trajectory file exists but is NOT readable: " + filePath);
            return;
        }

        Log.i(TAG, "Trajectory file confirmed to exist and is readable.");

        // Parse the JSON file and prepare replayData using TrajParser
        replayData = TrajParser.parseTrajectoryData(filePath, requireContext(), initialLat, initialLon);

        if (replayData != null && !replayData.isEmpty()) {
            Log.i(TAG, "Trajectory data loaded successfully. Total points: " + replayData.size());
        } else {
            Log.e(TAG, "Failed to load trajectory data! replayData is empty or null.");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
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

        // Attempt to use the first WiFi point if available
        if (!replayData.isEmpty()) {
            TrajParser.ReplayPoint firstReplayPoint = replayData.get(0);

            if (firstReplayPoint.wifiSamples != null && !firstReplayPoint.wifiSamples.isEmpty()) {
                JSONObject wifiAccessPoints = new JSONObject();

                // Build WiFi fingerprint JSON from first WiFi sample
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
                // Attempt to retrieve WiFi position
                WiFiPositioning wifiPositioning = new WiFiPositioning(getContext());
                wifiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                    @Override
                    public void onSuccess(LatLng wifiLocation, int floor) {
                        Log.i(TAG, "WiFi positioning successful. Location: " + wifiLocation);

                        // Show dialog asking user if they want to use WiFi positioning
                        new AlertDialog.Builder(requireContext())
                                .setTitle("Choose Positioning Method")
                                .setMessage("WiFi-based positioning is available for this trajectory. Do you want to use WiFi positioning?")
                                .setPositiveButton("Use WiFi", (dialog, which) -> {
                                    setupInitialMapPosition((float) wifiLocation.latitude, (float) wifiLocation.longitude);
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
                        Log.d(TAG, "WiFi Fingerprint: " + wifiFingerPrint);
                        // If WiFi fails, proceed with the existing GNSS choice dialog
                        if (hasAnyGnssData(replayData)) {
                            showGnssChoiceDialog();
                        } else {
                            if (initialLat != 0f || initialLon != 0f) {
                                LatLng startPoint = new LatLng(initialLat, initialLon);
                                Log.i(TAG, "Setting initial map position: " + startPoint.toString());
                                trajectoryMapFragment.setInitialCameraPosition(startPoint);
                            }
                        }
                    }
                });
            } else {
                // No WiFi samples, proceed with GNSS
                if (hasAnyGnssData(replayData)) {
                    showGnssChoiceDialog();
                } else {
                    if (initialLat != 0f || initialLon != 0f) {
                        LatLng startPoint = new LatLng(initialLat, initialLon);
                        Log.i(TAG, "Setting initial map position: " + startPoint.toString());
                        trajectoryMapFragment.setInitialCameraPosition(startPoint);
                    }
                }
            }
        } else {
            Log.e(TAG, "Replay data is empty, cannot determine initial location.");
        }

        // Link UI controls.
        playButton = view.findViewById(R.id.playButton);
        pauseButton = view.findViewById(R.id.pauseButton);
        speedHalfButton = view.findViewById(R.id.speedHalfButton);
        speedDoubleButton = view.findViewById(R.id.speedDoubleButton);
        replayButton = view.findViewById(R.id.replayButton);
        goToEndButton = view.findViewById(R.id.goToEndButton);
        playbackSeekBar = view.findViewById(R.id.playbackSeekBar);
        if (playbackSeekBar == null) {
            Log.e(TAG, "ERROR: playbackSeekBar is NULL! Check XML layout ID.");
            return;
        }
        dataSourceSpinner = view.findViewById(R.id.dataSourceSpinner);

        // Set up Spinner adapter and listener.
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"PDR", "GNSS", "WiFi"});
        dataSourceSpinner.setAdapter(adapter);
        dataSourceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selectedMode = parent.getItemAtPosition(position).toString();
                Log.i(TAG, "Data source selected: " + selectedMode);
                // Set polyline color based on selection.
                if (trajectoryMapFragment != null) {
                    trajectoryMapFragment.setPolylineColor(getColorForMode(selectedMode));
                }
                // Instead of resetting playback time, clear current drawing and redraw the polyline
                // from index 0 to currentIndex using the new selected mode.
                trajectoryMapFragment.clearMapAndReset();
                for (int i = 0; i <= currentIndex && i < replayData.size(); i++) {
                    drawReplayPointWithMode(i);
                }
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        // Set SeekBar max value.
        if (!replayData.isEmpty()) {
            playbackSeekBar.setMax(replayData.size() - 1);
        }

        // Set up button listeners:

        // Initialize button listeners
        view.findViewById(R.id.playButton).setOnClickListener(v -> {
            if (replayData.isEmpty()) {
                Log.w(TAG, "Play button pressed but replayData is empty.");
                return;
            }
            if (!isPlaying) {
                isPlaying = true;
                v.setEnabled(false);  // Disable play button while playing
                view.findViewById(R.id.pauseButton).setEnabled(true);  // Enable pause button
                playbackHandler.postDelayed(playbackRunnable, playbackInterval);
                Log.i(TAG, "Playback started from index: " + currentIndex);
            } else {
                Toast.makeText(getContext(), "Playback is already running.", Toast.LENGTH_SHORT).show();
            }
        });

        view.findViewById(R.id.pauseButton).setOnClickListener(v -> {
            if (isPlaying) {
                isPlaying = false;
                v.setEnabled(false);  // Disable pause button when paused
                view.findViewById(R.id.playButton).setEnabled(true);  // Re-enable play button
                playbackHandler.removeCallbacks(playbackRunnable);
                Log.i(TAG, "Playback stopped at index: " + currentIndex);
            } else {
                Toast.makeText(getContext(), "Playback is not running.", Toast.LENGTH_SHORT).show();
            }
        });

        // Speed Half button sets playback interval to 1000 ms (0.5x speed).
        speedHalfButton.setOnClickListener(v -> {
            playbackInterval = 1000;
            if (isPlaying) {
                playbackHandler.removeCallbacks(playbackRunnable);
                playbackHandler.postDelayed(playbackRunnable, playbackInterval);
            }
            Log.i(TAG, "Playback speed set to 0.5x (interval: " + playbackInterval + " ms)");
        });

        view.findViewById(R.id.speedDoubleButton).setOnClickListener(v -> {
            playbackInterval = 250;
            if (isPlaying) {
                playbackHandler.removeCallbacks(playbackRunnable);
                playbackHandler.postDelayed(playbackRunnable, playbackInterval);
            }
            Log.i(TAG, "Playback speed set to 2x (interval: " + playbackInterval + " ms)");
        });

        view.findViewById(R.id.replayButton).setOnClickListener(v -> {
            if (replayData.isEmpty()) return;
            currentIndex = 0;
            playbackSeekBar.setProgress(0);
            trajectoryMapFragment.clearMapAndReset();
            for (int i = 0; i <= currentIndex && i < replayData.size(); i++) {
                drawReplayPointWithMode(i);
            }
            Log.i(TAG, "Replay button pressed. Resetting playback to index 0.");
        });

        view.findViewById(R.id.goToEndButton).setOnClickListener(v -> {
            if (replayData.isEmpty()) return;
            currentIndex = replayData.size() - 1;
            playbackSeekBar.setProgress(currentIndex);
            drawReplayPointWithMode(currentIndex);
            isPlaying = false;
            trajectoryMapFragment.clearMapAndReset();
            for (int i = 0; i <= currentIndex && i < replayData.size(); i++) {
                drawReplayPointWithMode(i);
            }
            stopPlaying();
            Log.i(TAG, "Go To End button pressed. Moving to last index: " + currentIndex);
        });

        SeekBar playbackSeekBar = view.findViewById(R.id.playbackSeekBar);
        if (!replayData.isEmpty()) {
            playbackSeekBar.setMax(replayData.size() - 1);
        }

        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentIndex = progress;
                    trajectoryMapFragment.clearMapAndReset();
                    for (int i = 0; i <= currentIndex && i < replayData.size(); i++) {
                        drawReplayPointWithMode(i);
                    }
                    Log.i(TAG, "SeekBar moved by user. New index: " + currentIndex);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isPlaying = false;
                playbackHandler.removeCallbacks(playbackRunnable);
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }


        });

        // Initially, if replayData is available, draw the first point.
        if (!replayData.isEmpty()) {
            trajectoryMapFragment.clearMapAndReset();
            drawReplayPointWithMode(0);
        }

        //Code By Guilherme: Add tags to map
        List<com.openpositioning.PositionMe.utils.Tag> tags = SensorFusion.getInstance().getTagList();
        if (tags != null && !tags.isEmpty()) {
            for (com.openpositioning.PositionMe.utils.Tag tag : tags) {
                trajectoryMapFragment.addTagMarker(tag.getLocation(), tag.getLabel());
            }
        }

    }

    /**
     * Helper method to return the polyline color for the given mode.
     */
    private int getColorForMode(String mode) {
        switch (mode) {
            case "GNSS":
                return android.graphics.Color.BLUE;
            case "WiFi":
                return android.graphics.Color.GREEN;
            default: // "PDR"
                return android.graphics.Color.RED;
        }
    }

    /**
     * Checks if any ReplayPoint contains a non-null GNSS location.
     */
    private boolean hasAnyGnssData(List<TrajParser.ReplayPoint> data) {
        for (TrajParser.ReplayPoint point : data) {
            if (point.gnssLocation != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shows a dialog asking whether to use GNSS from file or manual coordinates.
     */
    private void showGnssChoiceDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Choose Starting Location")
                .setMessage("GNSS data is found in the file. Would you like to use the file's GNSS as the start, or your manually picked coordinates?")
                .setPositiveButton("File's GNSS", (dialog, which) -> {
                    LatLng firstGnss = getFirstGnssLocation(replayData);
                    if (firstGnss != null) {
                        setupInitialMapPosition((float) firstGnss.latitude, (float) firstGnss.longitude);
                    } else {
                        setupInitialMapPosition(initialLat, initialLon);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("Manual Set", (dialog, which) -> {
                    setupInitialMapPosition(initialLat, initialLon);
                    dialog.dismiss();
                })
                .setCancelable(false)
                .show();
    }

    private void setupInitialMapPosition(float latitude, float longitude) {
        LatLng startPoint = new LatLng(latitude, longitude);
        Log.i(TAG, "Setting initial map position: " + startPoint);
        trajectoryMapFragment.setInitialCameraPosition(startPoint);
    }

    private LatLng getFirstGnssLocation(List<TrajParser.ReplayPoint> data) {
        for (TrajParser.ReplayPoint point : data) {
            if (point.gnssLocation != null) {
                return new LatLng(point.gnssLocation.latitude, point.gnssLocation.longitude);
            }
        }
        return null;
    }

    /**
     * Runnable for updating playback. Called repeatedly to update the map with the next replay point.
     */
    private final Runnable playbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying || replayData.isEmpty()) return;
            Log.i(TAG, "Playing index: " + currentIndex);
            trajectoryMapFragment.clearMapAndReset();
            // Set the polyline color based on the current mode.
            trajectoryMapFragment.setPolylineColor(getColorForMode(selectedMode));
            for (int i = 0; i <= currentIndex && i < replayData.size(); i++) {
                drawReplayPointWithMode(i);
            }
            currentIndex++;
            playbackSeekBar.setProgress(currentIndex);
            if (currentIndex < replayData.size()) {
                playbackHandler.postDelayed(this, playbackInterval);
            } else {
                Log.i(TAG, "Playback completed. Reached end.");
                stopPlaying();
            }
        }
    };

    /**
     * Updates the map with the replay point at the given index.
     */
    private void drawReplayPointWithMode(int index) {
        if (index < 0 || index >= replayData.size()) return;
        TrajParser.ReplayPoint p = replayData.get(index);
        switch (selectedMode) {
            case "GNSS":
                if (p.gnssLocation != null) {
                    trajectoryMapFragment.updateUserLocation(p.gnssLocation, p.orientation);
                } else {
                    trajectoryMapFragment.updateUserLocation(p.pdrLocation, p.orientation);
                }
                break;
            case "WiFi":
                // Code  by Jamie Arnott
                // Each replaypoint contains the full list of WiFi samples for that given location.
                // Each location is a scan of wifi samples and the fingerprint needs to be passed to
                // the API to obtain the latitude and longitude.
                // Take the p.wifiSamples and iterate through them to get the Mac and RSSI data
                // create JSONObject() wifiAccessPoints
                JSONObject wifiAccessPoints = new JSONObject();
                // Build WiFi fingerprint JSON from first WiFi sample
                for (Traj.WiFi_Sample sample : p.wifiSamples) {
                    for (Traj.Mac_Scan macScan : sample.getMacScansList()) {
                        try {
                            wifiAccessPoints.put(String.valueOf(macScan.getMac()), macScan.getRssi());
                        } catch (JSONException e) {
                            Log.e(TAG, "Error creating WiFi fingerprint JSON: " + e.getMessage());
                        }
                    }
                }

                // create new JSONObject() for the wifi fingerprint
                JSONObject wifiFingerPrint = new JSONObject();
                try {
                    wifiFingerPrint.put("wf", wifiAccessPoints);
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }

                // make a request to the API to obtain the LatLng location from the wifi sample
                WiFiPositioning wifiPositioning = new WiFiPositioning(getContext());
                wifiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                    @Override
                    public void onSuccess(LatLng location, int floor) {
                        trajectoryMapFragment.updateUserLocation(location, p.orientation);
                    }

                    @Override
                    public void onError(String message) {
                        Log.e("ReplayFragment: ", "WiFi Positioning failed: " + message );
                        // revert to PDR
                        trajectoryMapFragment.updateUserLocation(p.pdrLocation, p.orientation);
                    }
                });
                break;
            default: // "PDR"
                // For PDR mode, use the first GNSS coordinate as the starting point if available.
                if (index == 0) {
                    LatLng firstGnss = getFirstGnssLocation(replayData);
                    if (firstGnss != null) {
                        trajectoryMapFragment.updateUserLocation(firstGnss, p.orientation);
                        break;
                    }
                }
                trajectoryMapFragment.updateUserLocation(p.pdrLocation, p.orientation);
                break;
        }
        if (selectedMode.equals("GNSS") && p.gnssLocation != null) {
            trajectoryMapFragment.updateGNSS(p.gnssLocation);
        }
    }

    private void updateMapForIndex(int newIndex) throws JSONException {
        if (newIndex < 0 || newIndex >= replayData.size()) return;

        // Get the current replay point
        TrajParser.ReplayPoint replayPoint = replayData.get(newIndex);

        // Track if WiFi positioning was successful
        final boolean[] wifiPositionSuccess = {false};

        // Check if WiFi samples exist for this index
        // code by Jamie Arnott
        if (replayPoint.wifiSamples != null && !replayPoint.wifiSamples.isEmpty()) {
            // Create JSON fingerprint for WiFi positioning
            JSONObject wifiAccessPoints = new JSONObject();
            for (Traj.WiFi_Sample sample : replayPoint.wifiSamples) {
                for (Traj.Mac_Scan macScan : sample.getMacScansList()) {
                    try {
                        wifiAccessPoints.put(String.valueOf(macScan.getMac()), macScan.getRssi());
                    } catch (JSONException e) {
                        Log.e(TAG, "Error creating WiFi fingerprint JSON: " + e.getMessage());
                    }
                }
            }
            JSONObject wifiFingerprint = new JSONObject();
            wifiFingerprint.put("wf", wifiAccessPoints);
            // Request WiFi position from API
            WiFiPositioning wifiPositioning = new WiFiPositioning(getContext());
            wifiPositioning.request(wifiFingerprint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng wifiLocation, int floor) {
                    // WiFi positioning successful
                    wifiPositionSuccess[0] = true;
                    trajectoryMapFragment.updateUserLocation(wifiLocation, 0f); // Display WiFi Position
                    Log.i(TAG, "WiFi positioning successful at: " + wifiLocation.toString());
                }

                @Override
                public void onError(String message) {
                    Log.e(TAG, "WiFi positioning failed: " + message);
                    // If WiFi positioning fails, fall back to PDR positioning
                    fallbackToPdr(replayPoint);
                }
            });
        } else {
            // No WiFi samples, directly fallback to PDR positioning
            fallbackToPdr(replayPoint);
        }

        lastIndex = newIndex;
    }

    private void fallbackToPdr(TrajParser.ReplayPoint replayPoint) {
        Log.i(TAG, "Falling back to PDR positioning");

        // Detect if user is playing sequentially (lastIndex + 1)
        boolean isSequentialForward = (currentIndex == lastIndex + 1);

        if (!isSequentialForward) {
            // Clear everything and redraw up to currentIndex
            trajectoryMapFragment.clearMapAndReset();
            for (int i = 0; i <= currentIndex; i++) {
                TrajParser.ReplayPoint p = replayData.get(i);
                trajectoryMapFragment.updateUserLocation(p.pdrLocation, p.orientation);
                if (p.gnssLocation != null) {
                    trajectoryMapFragment.updateGNSS(p.gnssLocation);
                }
            }
        } else {
            // Normal sequential forward step: add just the new point
            trajectoryMapFragment.updateUserLocation(replayPoint.pdrLocation, replayPoint.orientation);
            if (replayPoint.gnssLocation != null) {
                trajectoryMapFragment.updateGNSS(replayPoint.gnssLocation);
            }
        }
    }
    /**
     * Starts playback by posting the playbackRunnable and updating UI controls.
     */
    private void startPlaying() {
        isPlaying = true;
        playButton.setEnabled(false);
        pauseButton.setEnabled(true);
        playbackHandler.postDelayed(playbackRunnable, playbackInterval);
        Log.i(TAG, "Playback started from index: " + currentIndex);
    }

    /**
     * Stops playback by removing playbackRunnable callbacks and updating UI controls.
     */
    private void stopPlaying() {
        isPlaying = false;
        playbackHandler.removeCallbacks(playbackRunnable);
        playButton.setEnabled(true);
        pauseButton.setEnabled(false);
        Log.i(TAG, "Playback stopped at index: " + currentIndex);
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
}
