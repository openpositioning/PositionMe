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
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;
import com.openpositioning.PositionMe.data.local.TrajParser;
import com.openpositioning.PositionMe.sensors.WiFiPositioning;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Sub fragment of Replay Activity. Fragment that replays trajectory data on a map.
 *
 * The ReplayFragment visualizes and replays trajectory data captured during previous recordings.
 * It loads trajectory data from a JSON file, updates the map with user movement, and provides
 * UI controls for playback (with separate play and pause buttons), speed control, rewind,
 * and go-to-end functionalities. Additionally, it provides a dropdown (Spinner) at the top right
 * to select which data source to replay (PDR, GNSS, or WiFi) – each with its own polyline color.
 *
 * For PDR mode, if a GNSS starting point exists, that same point is used as the starting point.
 *
 * @see ReplayActivity The activity managing the replay workflow.
 * @see TrajParser Utility class for parsing trajectory data.
 */
public class ReplayFragment extends Fragment {

    private static final String TAG = "ReplayFragment";

    // Received from ReplayActivity.
    private float initialLat = 0f;
    private float initialLon = 0f;
    private String filePath = "";
    private int lastIndex = -1;

    // UI Controls.
    private TrajectoryMapFragment trajectoryMapFragment;
    private ImageButton playButton, pauseButton, replayButton, goToEndButton;
    private Button speedHalfButton, speedDoubleButton, viewStatsButton;
    private SeekBar playbackSeekBar;
    // Spinner for selecting data source.
    private Spinner dataSourceSpinner;
    // Holds the selected mode ("PDR", "GNSS", or "WiFi")
    private String selectedMode = "PDR";

    // Playback-related.
    private final Handler playbackHandler = new Handler();
    private long playbackInterval = 500; // Default playback interval in ms.
    private List<TrajParser.ReplayPoint> replayData = new ArrayList<>();
    private int currentIndex = 0;
    private boolean isPlaying = false;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Retrieve data from ReplayActivity.
        if (getArguments() != null) {
            filePath = getArguments().getString(ReplayActivity.EXTRA_TRAJECTORY_FILE_PATH, "");
            initialLat = getArguments().getFloat(ReplayActivity.EXTRA_INITIAL_LAT, 0f);
            initialLon = getArguments().getFloat(ReplayActivity.EXTRA_INITIAL_LON, 0f);
        }
        Log.i(TAG, "ReplayFragment received data: filePath=" + filePath + ", initialLat=" + initialLat + ", initialLon=" + initialLon);

        // Check file existence.
        File trajectoryFile = new File(filePath);
        if (!trajectoryFile.exists() || !trajectoryFile.canRead()) {
            Log.e(TAG, "ERROR: Trajectory file missing or unreadable at: " + filePath);
            return;
        }
        Log.i(TAG, "Trajectory file exists and is readable.");

        // Parse trajectory data.
        replayData = TrajParser.parseTrajectoryData(filePath, requireContext(), initialLat, initialLon);
        if (replayData != null && !replayData.isEmpty()) {
            Log.i(TAG, "Trajectory data loaded. Total points: " + replayData.size());
        } else {
            Log.e(TAG, "Failed to load trajectory data!");
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

        // Initialize map fragment.
        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.replayMapFragmentContainer);
        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.replayMapFragmentContainer, trajectoryMapFragment)
                    .commit();
        }

        // If GNSS data exists, prompt the user; otherwise, use initial lat/lon.
        if (hasAnyGnssData(replayData)) {
            showGnssChoiceDialog();
        } else {
            if (initialLat != 0f || initialLon != 0f) {
                LatLng startPoint = new LatLng(initialLat, initialLon);
                Log.i(TAG, "Setting initial map position: " + startPoint);
                trajectoryMapFragment.setInitialCameraPosition(startPoint);
            }
        }

        // Link UI controls.
        playButton = view.findViewById(R.id.playButton);
        pauseButton = view.findViewById(R.id.pauseButton);
        speedHalfButton = view.findViewById(R.id.speedHalfButton);
        speedDoubleButton = view.findViewById(R.id.speedDoubleButton);
        replayButton = view.findViewById(R.id.replayButton);
        goToEndButton = view.findViewById(R.id.goToEndButton);
        playbackSeekBar = view.findViewById(R.id.playbackSeekBar);
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

        // Button Listeners:

        // Play button starts playback.
        playButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) {
                Log.w(TAG, "Play button pressed but replayData is empty.");
                return;
            }
            if (!isPlaying) {
                startPlaying();
            } else {
                Toast.makeText(getContext(), "Playback is already running.", Toast.LENGTH_SHORT).show();
            }
        });

        // Pause button stops playback.
        pauseButton.setOnClickListener(v -> {
            if (isPlaying) {
                stopPlaying();
            } else {
                Toast.makeText(getContext(), "Playback is not running.", Toast.LENGTH_SHORT).show();
            }
        });

        // Speed Half button sets playback interval to 1000 ms (0.5x speed).
        speedHalfButton.setOnClickListener(v -> {
            playbackInterval = 1000;
            if (isPlaying) {
                stopPlaying();
                startPlaying();
            }
            Log.i(TAG, "Playback speed set to 0.5x (interval: " + playbackInterval + " ms)");
        });

        // Speed Double button sets playback interval to 250 ms (2x speed).
        speedDoubleButton.setOnClickListener(v -> {
            playbackInterval = 250;
            if (isPlaying) {
                stopPlaying();
                startPlaying();
            }
            Log.i(TAG, "Playback speed set to 2x (interval: " + playbackInterval + " ms)");
        });

        // Replay (rewind) button resets playback to beginning.
        replayButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) return;
            currentIndex = 0;
            playbackSeekBar.setProgress(0);
            trajectoryMapFragment.clearMapAndReset();
            for (int i = 0; i <= currentIndex && i < replayData.size(); i++) {
                drawReplayPointWithMode(i);
            }
            Log.i(TAG, "Replay button pressed. Resetting playback to index 0.");
        });

        // Go To End button jumps to the last index.
        goToEndButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) return;
            currentIndex = replayData.size() - 1;
            playbackSeekBar.setProgress(currentIndex);
            trajectoryMapFragment.clearMapAndReset();
            for (int i = 0; i <= currentIndex && i < replayData.size(); i++) {
                drawReplayPointWithMode(i);
            }
            stopPlaying();
            Log.i(TAG, "Go To End button pressed. Moving to last index: " + currentIndex);
        });

        // SeekBar listener for manual scrubbing.
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
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                stopPlaying();
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        // Initially, if replayData is available, draw the first point.
        if (!replayData.isEmpty()) {
            trajectoryMapFragment.clearMapAndReset();
            drawReplayPointWithMode(0);
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
     * Draws the replay point at the given index based on the selected mode.
     * For "PDR" mode, if the index is 0 and a GNSS starting point exists, that is used.
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
                if (p.wifiLocation != null) {
                    trajectoryMapFragment.updateUserLocation(p.wifiLocation, p.orientation);
                } else {
                    trajectoryMapFragment.updateUserLocation(p.pdrLocation, p.orientation);
                }
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
