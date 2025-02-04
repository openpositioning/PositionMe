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
import androidx.fragment.app.Fragment;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Fragment to replay a previously recorded trajectory and show it on the map.
 */
public class ReplayFragment extends Fragment {

    // Child map fragment
    private TrajectoryMapFragment trajectoryMapFragment;

    // UI
    private Button playPauseButton;
    private Button restartButton;
    private Button exitButton;
    private Button goEndButton;
    private SeekBar playbackSeekBar;

    // Playback logic
    private final Handler playbackHandler = new Handler();
    private final long PLAYBACK_INTERVAL_MS = 500; // or 200ms, etc.

    private List<ReplayPoint> replayData; // entire path from the proto file
    private int currentIndex = 0;
    private boolean isPlaying = false;

    // Simpler struct for each data point
    // In real code, you'll have orientation, floor, GNSS, timestamps, etc.
    private static class ReplayPoint {
        LatLng pdrLocation;  // user’s location from PDR
        LatLng gnssLocation; // optional GNSS location
        float orientation;   // heading
        long timestamp;      // for advanced time-based playback

        ReplayPoint(LatLng pdr, LatLng gnss, float orientation, long ts) {
            this.pdrLocation = pdr;
            this.gnssLocation = gnss;
            this.orientation = orientation;
            this.timestamp = ts;
        }
    }

    private final Runnable playbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying || replayData == null) return;

            updateMapForIndex(currentIndex);
            currentIndex++;

            // Update SeekBar
            playbackSeekBar.setProgress(currentIndex);

            // If we haven't reached the end, schedule next step
            if (currentIndex < replayData.size()) {
                playbackHandler.postDelayed(this, PLAYBACK_INTERVAL_MS);
            } else {
                // Reached end
                isPlaying = false;
                playPauseButton.setText("Play");
            }
        }
    };

    public ReplayFragment() {
        // Required empty constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 1) Load the trajectory data from proto file (placeholder)
        replayData = loadTrajectoryFromProto("placeholder-path");
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

        // 2) Child fragment for the map
        trajectoryMapFragment = (TrajectoryMapFragment)
                getChildFragmentManager().findFragmentById(R.id.replayMapFragmentContainer);

        if (trajectoryMapFragment == null) {
            trajectoryMapFragment = new TrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.replayMapFragmentContainer, trajectoryMapFragment)
                    .commit();
        }

        // 3) UI references
        playPauseButton   = view.findViewById(R.id.playPauseButton);
        restartButton     = view.findViewById(R.id.restartButton);
        exitButton        = view.findViewById(R.id.exitButton);
        goEndButton       = view.findViewById(R.id.goEndButton);
        playbackSeekBar   = view.findViewById(R.id.playbackSeekBar);

        // 4) Setup the SeekBar max
        if (replayData != null && !replayData.isEmpty()) {
            playbackSeekBar.setMax(replayData.size() - 1);
        }

        // 5) Listeners
        playPauseButton.setOnClickListener(v -> {
            if (replayData == null || replayData.isEmpty()) return;
            if (isPlaying) {
                // Pause
                isPlaying = false;
                playPauseButton.setText("Play");
            } else {
                // Start/Resume
                isPlaying = true;
                playPauseButton.setText("Pause");
                // If at end, restart from beginning
                if (currentIndex >= replayData.size()) {
                    currentIndex = 0;
                }
                playbackHandler.post(playbackRunnable);
            }
        });

        restartButton.setOnClickListener(v -> {
            if (replayData == null) return;
            // Immediately reset to first point
            currentIndex = 0;
            playbackSeekBar.setProgress(0);
            updateMapForIndex(0);
        });

        goEndButton.setOnClickListener(v -> {
            if (replayData == null || replayData.isEmpty()) return;
            // Jump to end
            currentIndex = replayData.size() - 1;
            playbackSeekBar.setProgress(currentIndex);
            updateMapForIndex(currentIndex);
            isPlaying = false;
            playPauseButton.setText("Play");
        });

        exitButton.setOnClickListener(v -> {
            // Done with replay, close activity or pop back stack
            if (getActivity() instanceof ReplayActivity) {
                ((ReplayActivity) getActivity()).finishFlow();
            } else {
                requireActivity().onBackPressed();
            }
        });

        // SeekBar dragging (optional): let user jump to a time index
        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentIndex = progress;
                    updateMapForIndex(currentIndex);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        // If we already have data, place the first point
        if (replayData != null && !replayData.isEmpty()) {
            updateMapForIndex(0);
        }
    }

    /**
     * Placeholder method to load the recorded trajectory from a .proto file.
     * In real implementation, parse the proto and build up the list of points.
     */
    private List<ReplayPoint> loadTrajectoryFromProto(String filePath) {
        // TODO: parse your .proto and fill in real data
        // For now, we return a small dummy list
        List<ReplayPoint> data = new ArrayList<>();
        data.add(new ReplayPoint(new LatLng(37.4219999, -122.0840575),  // pdr
                new LatLng(37.4219983, -122.0840000),  // gnss
                0f,
                0L));
        data.add(new ReplayPoint(new LatLng(37.4220005, -122.0840550),
                new LatLng(37.4220017, -122.0840050),
                45f,
                1000L));
        data.add(new ReplayPoint(new LatLng(37.4220020, -122.0840520),
                new LatLng(37.4220035, -122.0840100),
                90f,
                2000L));
        // ... etc ...
        return data;
    }

    /**
     * Update the map to show the point at replayData[index],
     * including PDR location, orientation, and GNSS location if available.
     */
    private void updateMapForIndex(int index) {
        if (replayData == null || index < 0 || index >= replayData.size()) return;

        ReplayPoint point = replayData.get(index);

        // Update user position + orientation on the trajectory map
        if (trajectoryMapFragment != null) {
            trajectoryMapFragment.updateUserLocation(point.pdrLocation, point.orientation);

            // Show GNSS if user toggles it in the UI (TrajectoryMapFragment has a switch)
            // For example, we always call updateGNSS() anyway:
            if (point.gnssLocation != null) {
                trajectoryMapFragment.updateGNSS(point.gnssLocation);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Pause playback
        isPlaying = false;
        playbackHandler.removeCallbacks(playbackRunnable);
        if (playPauseButton != null) {
            playPauseButton.setText("Play");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        playbackHandler.removeCallbacks(playbackRunnable);
    }
}
