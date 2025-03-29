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
import com.openpositioning.PositionMe.sensors.WiFiPositioning;

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
 * @see TrajectoryMapFragment The map fragment displaying the trajectory.
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



        // 1) Check if the file contains any GNSS data
//        boolean gnssExists = hasAnyGnssData(replayData);
//
//        if (gnssExists) {
//            showGnssChoiceDialog();
//        } else {
//            // No GNSS data -> automatically use param lat/lon
//            if (initialLat != 0f || initialLon != 0f) {
//                LatLng startPoint = new LatLng(initialLat, initialLon);
//                Log.i(TAG, "Setting initial map position: " + startPoint.toString());
//                trajectoryMapFragment.setInitialCameraPosition(startPoint);
//            }
//        }
        // Set initial map position
        setupInitialMapPosition();



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

        if (!replayData.isEmpty()) {
            updateMapForIndex(0);
        }
    }



    /**
     * Checks if any ReplayPoint contains a non-null gnssLocation.
     */
//    private boolean hasAnyGnssData(List<TrajParser.ReplayPoint> data) {
//        for (TrajParser.ReplayPoint point : data) {
//            if (point.gnssLocation != null) {
//                return true;
//            }
//        }
//        return false;
//    }




    /**
     * Show a simple dialog asking user to pick:
     * 1) GNSS from file
     * 2) Lat/Lon from ReplayActivity arguments
     */

//    private void showGnssChoiceDialog() {
//        new AlertDialog.Builder(requireContext())
//                .setTitle("Choose Starting Location")
//                .setMessage("GNSS data is found in the file. Would you like to use the file's GNSS as the start, or the one you manually picked?")
//                .setPositiveButton("Use File's GNSS", (dialog, which) -> {
//                    LatLng firstGnss = getFirstGnssLocation(replayData);
//                    if (firstGnss != null) {
//                        setupInitialMapPosition((float) firstGnss.latitude, (float) firstGnss.longitude);
//                    } else {
//                        // Fallback if no valid GNSS found
//                        setupInitialMapPosition(initialLat, initialLon);
//                    }
//                    dialog.dismiss();
//                })
//                .setNegativeButton("Use Manual Set", (dialog, which) -> {
//                    setupInitialMapPosition(initialLat, initialLon);
//                    dialog.dismiss();
//                })
//                .setCancelable(false)
//                .show();
//    }


//    private void setupInitialMapPosition(float latitude, float longitude) {
//        LatLng startPoint = new LatLng(initialLat, initialLon);
//        Log.i(TAG, "Setting initial map position: " + startPoint.toString());
//        trajectoryMapFragment.setInitialCameraPosition(startPoint);
//    }
    private void setupInitialMapPosition() {
        LatLng startPoint = getFirstGnssLocation(replayData);
        if (startPoint == null) {
            startPoint = getFirstWifiLocation(replayData);
        }
        if (startPoint == null) {
            startPoint = new LatLng(initialLat, initialLon);
        }
        Log.i(TAG, "Setting initial map position: " + startPoint.toString());
        trajectoryMapFragment.setInitialCameraPosition(startPoint);
    }


    /**
     * Retrieve the first available GNSS location from the replay data.
     */
//    private LatLng getFirstGnssLocation(List<TrajParser.ReplayPoint> data) {
//        for (TrajParser.ReplayPoint point : data) {
//            if (point.gnssLocation != null) {
//                return new LatLng(replayData.get(0).gnssLocation.latitude, replayData.get(0).gnssLocation.longitude);
//            }
//        }
//        return null; // None found
//    }
    private LatLng getFirstGnssLocation(List<TrajParser.ReplayPoint> data) {
        for (TrajParser.ReplayPoint point : data) {
            if (point.gnssLocation != null) {
                return point.gnssLocation;
            }
        }
        return null;
    }
    private LatLng getFirstWifiLocation(List<TrajParser.ReplayPoint> data) {
        for (TrajParser.ReplayPoint point : data) {
            if (point.wifiLocation != null) {
                return point.wifiLocation;
            }
        }
        return null;
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
                // 添加 WiFi 定位更新
                if (p.wifiLocation != null) {
                    trajectoryMapFragment.updateWifiLocation(p.wifiLocation);
                }
            }
        } else {
            // Normal sequential forward step: add just the new point
            TrajParser.ReplayPoint p = replayData.get(newIndex);
            trajectoryMapFragment.updateUserLocation(p.pdrLocation, p.orientation);
            if (p.gnssLocation != null) {
                trajectoryMapFragment.updateGNSS(p.gnssLocation);
            }
             //添加 WiFi 定位更新
            if (p.wifiLocation != null) {
                trajectoryMapFragment.updateWifiLocation(p.wifiLocation);
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
