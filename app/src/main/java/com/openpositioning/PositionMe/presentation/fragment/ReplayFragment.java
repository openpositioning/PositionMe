package com.openpositioning.PositionMe.presentation.fragment;

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
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;
import com.openpositioning.PositionMe.data.local.TrajParser;
import com.openpositioning.PositionMe.utils.TrajectoryVerifier;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

// This fragment controls the full replay lifecycle for one trajectory file.
// It receives replay arguments, validates file integrity, allows start-point
// selection, and renders frames onto the map in playback order.
//
// Replay pipeline implemented in this class:
// Read replay arguments from ReplayActivity.
// Verify and parse replay data from TrajParser.
// Dispatch draw updates to TrajectoryMapFragment.
// Keep UI state, seek bar position, and playback index synchronized.

public class ReplayFragment extends Fragment {

    private static final String TAG = "ReplayFragment";

    // Manual start position received from ReplayActivity.
    private float initialLat = 0f;
    private float initialLon = 0f;
    private LatLng recordedStartPoint = null;
    private String filePath = "";
    private int lastIndex = -1;

    // UI controls used to drive replay interaction.
    private TrajectoryMapFragment trajectoryMapFragment;
    private Button playPauseButton, restartButton, exitButton, goEndButton;
    private SeekBar playbackSeekBar;

    // Playback state and scheduling fields.
    private final Handler playbackHandler = new Handler();
    private final long PLAYBACK_INTERVAL_MS = 500; // milliseconds
    private List<TrajParser.ReplayPoint> replayData = new ArrayList<>();
    private int currentIndex = 0;
    private boolean isPlaying = false;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Read replay arguments passed by ReplayActivity.
        if (getArguments() != null) {
            filePath = getArguments().getString(ReplayActivity.EXTRA_TRAJECTORY_FILE_PATH, "");
            initialLat = getArguments().getFloat(ReplayActivity.EXTRA_INITIAL_LAT, 0f);
            initialLon = getArguments().getFloat(ReplayActivity.EXTRA_INITIAL_LON, 0f);
        }

        // Log incoming replay context for debugging and support traces.
        Log.i(TAG, "ReplayFragment received data:");
        Log.i(TAG, "Trajectory file path: " + filePath);
        Log.i(TAG, "Initial latitude: " + initialLat);
        Log.i(TAG, "Initial longitude: " + initialLon);

        // Validate source file accessibility before parsing.
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

        // Perform structural verification before building replay frames.
        boolean isValid = TrajectoryVerifier.verifyTrajectoryFile(filePath);
        if (!isValid) {
            Log.e(TAG, "Trajectory file verification FAILED - file may be corrupt or empty");
        }

        // Detect which start-point sources are available in this file.
        boolean gnssExists = TrajParser.hasGnssData(filePath);
        recordedStartPoint = TrajParser.getRecordedInitialPoint(filePath);

        if (gnssExists || recordedStartPoint != null) {
            showStartChoiceDialog(gnssExists, recordedStartPoint != null);
        } else {
            // Fall back to manual start when file-based sources are unavailable.
            Log.i(TAG, "No GNSS/recorded start in file, using manual start location.");
            loadTrajectory(initialLat, initialLon);
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

        // Attach or create the child map fragment used for rendering frames.
        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.replayMapFragmentContainer);
        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.replayMapFragmentContainer, trajectoryMapFragment)
                    .commit();
        }
        
        // Replay data can be loaded before map readiness. Rendering methods are
        // written to tolerate this ordering and apply state when the map is ready.

        // Bind playback controls.
        playPauseButton = view.findViewById(R.id.playPauseButton);
        restartButton   = view.findViewById(R.id.restartButton);
        exitButton      = view.findViewById(R.id.exitButton);
        goEndButton     = view.findViewById(R.id.goEndButton);
        playbackSeekBar = view.findViewById(R.id.playbackSeekBar);

        // Initialize seek range when parsed data is already available.
        if (!replayData.isEmpty()) {
            playbackSeekBar.setMax(replayData.size() - 1);
        }

        // Playback toggle.
        playPauseButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) {
                Log.w(TAG, "Play/Pause button pressed but replayData is empty.");
                Toast.makeText(requireContext(), "This trajectory has no replayable points.", Toast.LENGTH_SHORT).show();
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

        // Restart from first frame.
        restartButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) return;
            currentIndex = 0;
            playbackSeekBar.setProgress(0);
            Log.i(TAG, "Restart button pressed. Resetting playback to index 0.");
            updateMapForIndex(0);
        });

        // Jump to final frame and pause playback.
        goEndButton.setOnClickListener(v -> {
            if (replayData.isEmpty()) return;
            currentIndex = replayData.size() - 1;
            playbackSeekBar.setProgress(currentIndex);
            Log.i(TAG, "Go to End button pressed. Moving to last index: " + currentIndex);
            updateMapForIndex(currentIndex);
            isPlaying = false;
            playPauseButton.setText("Play");
        });

        // Exit replay flow and return to the previous screen.
        exitButton.setOnClickListener(v -> {
            Log.i(TAG, "Exit button pressed. Exiting replay.");
            if (getActivity() instanceof ReplayActivity) {
                ((ReplayActivity) getActivity()).finishFlow();
            } else {
                requireActivity().onBackPressed();
            }
        });

        // Scrub to a user-selected frame.
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




    // Show start-source selection based on available trajectory metadata.
    private void showStartChoiceDialog(boolean hasGnss, boolean hasRecordedStart) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
                .setTitle("Choose Starting Location")
                .setMessage("Select which source should be used as replay trajectory start.")
                .setNegativeButton("Use Manual Set", (dialog, which) -> {
                    loadTrajectory(initialLat, initialLon);
                    dialog.dismiss();
                })
                .setCancelable(false);

        if (hasGnss) {
            builder.setPositiveButton("Use File's GNSS", (dialog, which) -> {
                LatLng firstGnss = TrajParser.getFirstGnssPoint(filePath);
                if (firstGnss != null) {
                    loadTrajectory((float) firstGnss.latitude, (float) firstGnss.longitude);
                } else {
                    loadTrajectory(initialLat, initialLon);
                }
                dialog.dismiss();
            });
        }

        if (hasRecordedStart && recordedStartPoint != null) {
            builder.setNeutralButton("Use Recorded Start", (dialog, which) -> {
                loadTrajectory((float) recordedStartPoint.latitude, (float) recordedStartPoint.longitude);
                dialog.dismiss();
            });
        }

        builder.show();
    }

    private void loadTrajectory(float latitude, float longitude) {
        lastIndex = -1;

        // Configure camera anchor and replay start marker before parsing.
        LatLng startPoint = new LatLng(latitude, longitude);
        LatLng recordedStartPoint = TrajParser.getRecordedInitialPoint(filePath);
        Log.i(TAG, "Setting initial map position: " + startPoint.toString());
        if (trajectoryMapFragment != null) {
            trajectoryMapFragment.setInitialCameraPosition(startPoint);
            trajectoryMapFragment.setReplayStartMarker(recordedStartPoint != null ? recordedStartPoint : startPoint);
        }

        // Parse replay points using the selected start origin.
        replayData = TrajParser.parseTrajectoryData(filePath, requireContext(), latitude, longitude);

        // Apply parsed result to UI.
        if (replayData != null && !replayData.isEmpty()) {
            Log.i(TAG, "Trajectory data loaded successfully. Total points: " + replayData.size());
            // Update seek bounds to match available frames.
            if (playbackSeekBar != null) {
                playbackSeekBar.setMax(replayData.size() - 1);
                playbackSeekBar.setProgress(0);
            }
            // Draw initial frame immediately.
            updateMapForIndex(0);
        } else {
            Log.e(TAG, "Failed to load trajectory data!");
            if (playPauseButton != null) {
                playPauseButton.setEnabled(false);
            }
            Toast.makeText(requireContext(), "Replay data is empty for this file.", Toast.LENGTH_LONG).show();
        }
    }




    // Periodic task that advances playback by one frame on each tick.
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


    // Render replay state at target frame index.
    // Forward playback uses incremental drawing. Backward jumps trigger full redraw.
    private void updateMapForIndex(int newIndex) {
        if (newIndex < 0 || newIndex >= replayData.size()) return;
        if (trajectoryMapFragment == null) return;

        if (lastIndex == -1 || newIndex < lastIndex) {
            trajectoryMapFragment.clearMapAndReset();
            renderRange(0, newIndex);
            lastIndex = newIndex;
            return;
        }

        if (newIndex == lastIndex) {
            return;
        }

        renderRange(lastIndex + 1, newIndex);

        lastIndex = newIndex;
    }

    private void renderRange(int startIndex, int endIndex) {
        for (int i = startIndex; i <= endIndex; i++) {
            TrajParser.ReplayPoint point = replayData.get(i);

            trajectoryMapFragment.updateUserLocation(point.pdrLocation, point.orientation);
            if (point.gnssLocation != null) {
                trajectoryMapFragment.updateGNSS(point.gnssLocation);
            }
            if (point.wifiLocation != null) {
                trajectoryMapFragment.updateWifi(point.wifiLocation);
            }
        }
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



