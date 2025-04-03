package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;
import com.openpositioning.PositionMe.data.local.TrajParser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

//// imports for graphs
//import com.github.mikephil.charting.charts.LineChart;
//import com.github.mikephil.charting.components.Description;
//import com.github.mikephil.charting.data.Entry;
//import com.github.mikephil.charting.data.LineData;
//import com.github.mikephil.charting.data.LineDataSet;

/**
 * Sub fragment of Replay Activity. Fragment that replays trajectory data on a map.
 * <p>
 * The ReplayFragment is responsible for visualizing and replaying trajectory data captured during
 * previous recordings. It loads trajectory data from a JSON file, updates the map with user movement,
 * and provides UI controls for playback, pause, and seek functionalities.
 * <p>
 * Features:
 * - Loads trajectory data from a file and displays it on a map.
 * - Provides playback controls including play, pause, restart, and go to end.
 * - Updates the trajectory dynamically as playback progresses.
 * - Allows users to manually seek through the recorded trajectory.
 * - Integrates with {@link TrajectoryMapFragment} for map visualization.
 *
 * @see TrajectoryMapFragment The map fragment displaying the trajectory.
 * @see ReplayActivity The activity managing the replay workflow.
 * @see TrajParser Utility class for parsing trajectory data.
 *
 * @author Shu Gu
 * @author Stone Anderson
 * @author Semih Vazgecen
 */
public class ReplayFragment extends Fragment {

    private static final String TAG = "ReplayFragment";
    private String filePath = "";
    private int lastIndex = -1;
    private Traj.Trajectory trajectory;

    // UI Controls
    private TrajectoryMapFragment trajectoryMapFragment;
    private Button playPauseButton, restartButton, exitButton, goEndButton;
    private SeekBar playbackSeekBar;

    // Playback-related
    private final Handler playbackHandler = new Handler();
    private final long PLAYBACK_INTERVAL_MS = 500; // milliseconds
    private List<TrajParser.ReplayPoint> replayData = new ArrayList<>();
    private int currentIndex = 0;
    private boolean isPlaying = false;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Retrieve transferred data from ReplayActivity
        if (getArguments() != null) {
            filePath = getArguments().getString(ReplayActivity.EXTRA_TRAJECTORY_FILE_PATH, "");
        }

        // Log the received data
        Log.i(TAG, "ReplayFragment received data:");
        Log.i(TAG, "Trajectory file path: " + filePath);

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
        replayData = TrajParser.parseTrajectoryData(filePath, requireContext());

        // Log the number of parsed points
        if (replayData != null && !replayData.isEmpty()) {
            Log.i(TAG, "Trajectory data loaded successfully. Total points: " + replayData.size());
        } else {
            Log.e(TAG, "Failed to load trajectory data! replayData is empty or null.");
            // Show a Toast error message
            Toast.makeText(getContext(), "Unable to load replay data.\nThe file may be empty or corrupted!", Toast.LENGTH_LONG).show();

            // Return to the previous activity
            if (getActivity() != null) {
                getActivity().finish();
            }
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
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        boolean hasWifiData = hasAnyWifiData(replayData);

        // Initialize map fragment
        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.replayMapFragmentContainer);
        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.replayMapFragmentContainer, trajectoryMapFragment)
                    .commit();
            trajectoryMapFragment.setReplayMode(true, hasWifiData);
            
            // Set visualization switches to on by default
            trajectoryMapFragment.setDefaultSwitchStates(true, true, true);
            
            // Delay loading tag markers until fragment transactions are complete
            getChildFragmentManager().executePendingTransactions();
        }
        
        // Load tag markers when replay starts
        loadTagMarkersFromTrajectory();


        // Get the initial position from first ReplayPoint
        if (!replayData.isEmpty()) {
            LatLng initialPos = replayData.get(0).initialPosition;
            if (initialPos != null) {
                Log.i(TAG, "Extracted Initial Position from ReplayPoint: " + initialPos.latitude + ", " + initialPos.longitude);
            } else {
                Log.e(TAG, "Initial Position is null in ReplayPoint!");
            }

            setupInitialMapPosition(initialPos.latitude, initialPos.longitude);
        }




        // Initialize UI controls
        playPauseButton = view.findViewById(R.id.playPauseButton);
        restartButton   = view.findViewById(R.id.restartButton);
        exitButton      = view.findViewById(R.id.exitButton);
        goEndButton     = view.findViewById(R.id.goEndButton);
        playbackSeekBar = view.findViewById(R.id.playbackSeekBar);

        // Set SeekBar max value based on replay data
        if (!replayData.isEmpty()) {
            playbackSeekBar.setMax(replayData.size() - 1);
        }

        // Make sure initial frame is displayed immediately
        if (!replayData.isEmpty()) {
            // Initialize with the first frame
            updateMapForIndex(0);
            
            // Get the first point with WiFi data to initialize floor
            boolean foundWifiData = false;
            for (TrajParser.ReplayPoint point : replayData) {
                if (point.wifiLocation != null) {
                    Log.i(TAG, "Found initial WiFi point with floor: " + point.wifiFloor);
                    // First try to initialize map with normal approach
                    trajectoryMapFragment.autoFloorHandler(point.wifiFloor);
                    
                    // Also force the indoor map to initialize - this is needed because 
                    // the current location might not yet be recognized as inside the building
                    trajectoryMapFragment.forceIndoorMap("nucleus", point.wifiFloor);
                    
                    foundWifiData = true;
                    break;
                }
            }
            
            // If no WiFi data was found, still try to initialize with a default
            if (!foundWifiData) {
                Log.i(TAG, "No WiFi data found, using default floor 0");
                trajectoryMapFragment.forceIndoorMap("nucleus", 0);
            }
        }

        // Button Listeners
        playPauseButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) {
                Log.w(TAG, "Play/Pause button pressed but replayData is empty.");
                return;
            }
            if (isPlaying) {
                isPlaying = false;
                playPauseButton.setText("Play");
                Log.i(TAG, "Playback paused at index: " + currentIndex);
            } else {
                isPlaying = true;
                playPauseButton.setText("Pause");
                Log.i(TAG, "Playback started from index: " + currentIndex);
                if (currentIndex >= replayData.size()) {
                    currentIndex = 0;
                }
                playbackHandler.post(playbackRunnable);
            }
        });

        // Restart button listener
        restartButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) return;
            currentIndex = 0;
            playbackSeekBar.setProgress(0);
            Log.i(TAG, "Restart button pressed. Resetting playback to index 0.");
            updateMapForIndex(0);
        });

        // Go to End button listener
        goEndButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) return;
            currentIndex = replayData.size() - 1;
            playbackSeekBar.setProgress(currentIndex);
            Log.i(TAG, "Go to End button pressed. Moving to last index: " + currentIndex);
            updateMapForIndex(currentIndex);
            isPlaying = false;
            playPauseButton.setText("Play");
        });

        // Exit button listener
        exitButton.setOnClickListener(v -> {
            Log.i(TAG, "Exit button pressed. Exiting replay.");
            if (getActivity() instanceof ReplayActivity) {
                ((ReplayActivity) getActivity()).finishFlow();
            } else {
                requireActivity().onBackPressed();
            }
        });

        // SeekBar listener
        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    Log.i(TAG, "SeekBar moved by user. New index: " + progress);
                    currentIndex = progress;
                    updateMapForIndex(currentIndex);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Removed this duplicate call as we already call updateMapForIndex(0) earlier
    }

    /**
     * Checks if any ReplayPoint contains a non-null gnssLocation.
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
     * Determines if any WiFi location data exists in the replay trajectory.
     * @param data list of ReplayPoint objects representing the trajectory
     * @return true if at least one point contains WiFi location data, false otherwise
     * 
     * @author Stone Anderson
     */
    private boolean hasAnyWifiData(List<TrajParser.ReplayPoint> data) {
        for (TrajParser.ReplayPoint point : data) {
            if (point.wifiLocation != null) {
                return true;
            }
        }
        return false;
    }


    private void setupInitialMapPosition(double latitude, double longitude) {
        LatLng startPoint = new LatLng(latitude, longitude);
        Log.i(TAG, "Setting initial map position: " + startPoint.toString());
        trajectoryMapFragment.setInitialCameraPosition(startPoint);
    }

    /**
     * Retrieve the first available GNSS location from the replay data.
     * Currently not used - but might be needed for later functionality
     * @author Stone Anderson
     */
    private LatLng getFirstGnssLocation(List<TrajParser.ReplayPoint> data) {
        for (TrajParser.ReplayPoint point : data) {
            if (point.gnssLocation != null) {
                return new LatLng(replayData.get(0).gnssLocation.latitude, replayData.get(0).gnssLocation.longitude);
            }
        }
        return null; // None found
    }


    /**
     * Retrieves the first valid WiFi location from the trajectory data.
     * @param data The list of ReplayPoint objects to search for WiFi location data
     * @return The first WiFi location as a LatLng object, or null if no WiFi data is found
     * Currently not in use - but might be needed for later functionality
     * 
     * @author Stone Anderson
     */
    private LatLng getFirstWifiLocation(List<TrajParser.ReplayPoint> data) {
        for (TrajParser.ReplayPoint point : data) {
            if (point.wifiLocation != null) {
                return new LatLng(replayData.get(0).wifiLocation.latitude, replayData.get(0).wifiLocation.longitude);
            }
        }
        return null; // None found
    }



    /**
     * Loads and displays tag markers from the trajectory data onto the map.
     * 
     * This method retrieves all tag markers that were created during the recording
     * session and adds them to the map visualization. Tags represent points of interest
     * that were manually marked by the user during recording and contain geographic
     * coordinates and timestamps.
     * 
     * The method performs the following steps:
     * 1. Retrieves the parsed tag records from the TrajParser
     * 2. Checks if any tags exist in the trajectory
     * 3. Verifies the map fragment is properly initialized
     * 4. Iterates through each tag and adds it to the map at its specified position
     * 
     * If no tags are found or the map fragment isn't initialized, appropriate warning
     * messages are logged and the method exits without making changes.
     * 
     * @author Semih Vazgecen
     */
    private void loadTagMarkersFromTrajectory() {
        List<TrajParser.TagRecord> parsedTags = TrajParser.getTagMarkers();

        if (parsedTags == null || parsedTags.isEmpty()) {
            Log.w("ReplayFragment", "No tag markers found.");
            return;
        }
        
        // Check if trajectoryMapFragment is initialized
        if (trajectoryMapFragment == null) {
            Log.e("ReplayFragment", "trajectoryMapFragment is null, cannot add tag markers");
            return;
        }

        for (TrajParser.TagRecord tag : parsedTags) {
            LatLng tagPosition = new LatLng(tag.latitude, tag.longitude);
            trajectoryMapFragment.addTagMarker(tagPosition, tag.relativeTimestamp);
        }
    }



    /**
     * Runnable for playback of trajectory data.
     * This runnable is called repeatedly to update the map with the next point in the replayData list.
     */
    private final Runnable playbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying || replayData.isEmpty()) return;

            Log.i(TAG, "Playing index: " + currentIndex);
            updateMapForIndex(currentIndex);
            currentIndex++;
            playbackSeekBar.setProgress(currentIndex);

            if (currentIndex < replayData.size()) {
                playbackHandler.postDelayed(this, PLAYBACK_INTERVAL_MS);
            } else {
                Log.i(TAG, "Playback completed. Reached end of data.");
                isPlaying = false;
                playPauseButton.setText("Play");
            }
        }
    };


    /**
     * Update the map with the user location and GNSS location (if available) for the given index.
     * Clears the map and redraws up to the given index.
     *
     * @param newIndex
     */
    private void updateMapForIndex(int newIndex) {
        if (newIndex < 0 || newIndex >= replayData.size()) return;

        // Detect if user is playing sequentially (lastIndex + 1)
        // or is skipping around (backwards, or jump forward)
        boolean isSequentialForward = (newIndex == lastIndex + 1);

        if (!isSequentialForward) {
            // Clear everything and redraw up to newIndex
            trajectoryMapFragment.clearMapAndReset();
            for (int i = 0; i <= newIndex; i++) {
                TrajParser.ReplayPoint p = replayData.get(i);
                trajectoryMapFragment.updateUserLocation(p.pdrLocation, p.orientation);
                if (p.gnssLocation != null) {
                    trajectoryMapFragment.updateGNSS(p.gnssLocation);
                }
                if (p.wifiLocation != null) {
                    trajectoryMapFragment.updateWifi(p.wifiLocation);
                    trajectoryMapFragment.autoFloorHandler(p.wifiFloor);
                }
                if(p.fusedLocation != null){
                    trajectoryMapFragment.updateEKF(p.fusedLocation, p.orientation);
                }
            }
        } else {
            // Normal sequential forward step: add just the new point
            TrajParser.ReplayPoint p = replayData.get(newIndex);
            trajectoryMapFragment.updateUserLocation(p.pdrLocation, p.orientation);
            if (p.gnssLocation != null) {
                trajectoryMapFragment.updateGNSS(p.gnssLocation);
            }
            if (p.wifiLocation != null) {
                // Log.d(TAG, "ReplayPoint has WiFi data: " + p.wifiLocation.toString());
                trajectoryMapFragment.updateWifi(p.wifiLocation);
                trajectoryMapFragment.autoFloorHandler(p.wifiFloor);
            }
            if(p.fusedLocation != null){
                trajectoryMapFragment.updateEKF(p.fusedLocation, p.orientation);
            }

        }

        lastIndex = newIndex;
    }

    @Override
    public void onPause() {
        super.onPause();
        isPlaying = false;
        playbackHandler.removeCallbacks(playbackRunnable);
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        playbackHandler.removeCallbacks(playbackRunnable);
    }
}
