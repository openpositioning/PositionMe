package com.openpositioning.PositionMe.presentation.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;
import com.openpositioning.PositionMe.data.local.TrajParser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
 * @see ReplayActivity The activity managing the replay workflow.
 * @see TrajParser Utility class for parsing trajectory data.
 *
 * @author Shu Gu
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
            initialLat = getArguments().getFloat(ReplayActivity.EXTRA_INITIAL_LAT, 0f);
            initialLon = getArguments().getFloat(ReplayActivity.EXTRA_INITIAL_LON, 0f);
        }

        // Log the received data
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

        // Log the number of parsed points
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
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
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

        // If GNSS data exists, prompt for starting location choice; otherwise, use initial values.
        boolean gnssExists = hasAnyGnssData(replayData);

        if (gnssExists) {
            showGnssChoiceDialog();
        } else {
            // No GNSS data -> automatically use param lat/lon
            if (initialLat != 0f || initialLon != 0f) {
                LatLng startPoint = new LatLng(initialLat, initialLon);
                Log.i(TAG, "Setting initial map position: " + startPoint.toString());
                trajectoryMapFragment.setInitialCameraPosition(startPoint);
            }
        }

        // Link UI controls from XML (using the IDs from your provided layout)
        playButton = view.findViewById(R.id.playButton);
        pauseButton = view.findViewById(R.id.pauseButton);
        speedHalfButton = view.findViewById(R.id.speedHalfButton);
        speedDoubleButton = view.findViewById(R.id.speedDoubleButton);
        replayButton = view.findViewById(R.id.replayButton);
        goToEndButton = view.findViewById(R.id.goToEndButton);
        playbackSeekBar = view.findViewById(R.id.playbackSeekBar);

        // Set SeekBar max value based on replay data
        if (!replayData.isEmpty()) {
            playbackSeekBar.setMax(replayData.size() - 1);
        }

        // Set up button listeners:

        // Play button starts playback
        playButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) {
                Log.w(TAG, "Play button pressed but replayData is empty.");
                return;
            }
            if (isPlaying) {
                stopPlaying();
            } else {
                Log.w(TAG, "Pause button pressed but playback is not running.");
            }
        });

        // Speed Half button sets playback interval to 1000 ms (0.5x speed)
        speedHalfButton.setOnClickListener(v -> {
            playbackInterval = 1000;
            if (isPlaying) {
                stopPlaying();
                startPlaying();
            }
            Log.i(TAG, "Playback speed set to 0.5x (interval: " + playbackInterval + " ms)");
        });

        // Speed Double button sets playback interval to 250 ms (2x speed)
        speedDoubleButton.setOnClickListener(v -> {
            playbackInterval = 250;
            if (isPlaying) {
                stopPlaying();
                startPlaying();
            }
            Log.i(TAG, "Playback speed set to 2x (interval: " + playbackInterval + " ms)");
        });

        // Replay button (rewind) resets playback to beginning
        replayButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) return;
            currentIndex = 0;
            playbackSeekBar.setProgress(0);
            updateMapForIndex(0);
            Log.i(TAG, "Replay button pressed. Resetting playback to index 0.");
        });

        // Go To End button jumps to the last index
        goToEndButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) return;
            currentIndex = replayData.size() - 1;
            playbackSeekBar.setProgress(currentIndex);
            updateMapForIndex(currentIndex);
            stopPlaying();
            Log.i(TAG, "Go To End button pressed. Moving to last index: " + currentIndex);
        });

        // SeekBar listener to allow manual scrubbing through the replay
        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentIndex = progress;
                    updateMapForIndex(currentIndex);
                    Log.i(TAG, "SeekBar moved by user. New index: " + currentIndex);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                stopPlaying();
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        // If replayData is available, update the map to show the first point.
        if (!replayData.isEmpty()) {
            updateMapForIndex(0);
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
     * Shows a dialog asking the user whether to use GNSS from file or manual coordinates.
     */
    private void showGnssChoiceDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Choose Starting Location")
                .setMessage("GNSS data found in file. Use file's GNSS or your manually picked coordinates?")
                .setPositiveButton("File's GNSS", (dialog, which) -> {
                    LatLng firstGnss = getFirstGnssLocation(replayData);
                    if (firstGnss != null) {
                        setupInitialMapPosition((float) firstGnss.latitude, (float) firstGnss.longitude);
                    } else {
                        // Fallback if no valid GNSS found
                        setupInitialMapPosition(initialLat, initialLon);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("Use Manual Set", (dialog, which) -> {
                    setupInitialMapPosition(initialLat, initialLon);
                    dialog.dismiss();
                })
                .setCancelable(false)
                .show();
    }

    private void setupInitialMapPosition(float latitude, float longitude) {
        LatLng startPoint = new LatLng(initialLat, initialLon);
        Log.i(TAG, "Setting initial map position: " + startPoint.toString());
        trajectoryMapFragment.setInitialCameraPosition(startPoint);
    }

    /**
     * Retrieve the first available GNSS location from the replay data.
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
                playbackHandler.postDelayed(this, playbackInterval);
            } else {
                Log.i(TAG, "Playback completed. Reached end.");
                stopPlaying();
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
            }
        } else {
            // Normal sequential forward step: add just the new point
            TrajParser.ReplayPoint p = replayData.get(newIndex);
            trajectoryMapFragment.updateUserLocation(p.pdrLocation, p.orientation);
            if (p.gnssLocation != null) {
                trajectoryMapFragment.updateGNSS(p.gnssLocation);
            }
        }

        lastIndex = newIndex;
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
