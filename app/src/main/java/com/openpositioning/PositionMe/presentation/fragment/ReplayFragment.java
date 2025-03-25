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
import com.google.android.gms.maps.model.Polyline;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.local.TrajParser;
import com.openpositioning.PositionMe.data.local.TrajParser.ReplayPoint;
import com.openpositioning.PositionMe.fusion.KalmanFilterFusion;
import com.openpositioning.PositionMe.utils.CoordinateConverter;

import java.util.ArrayList;
import java.util.List;

/**
 * A fragment for replaying and visualizing recorded trajectory data with fusion.
 * This fragment allows the user to play, pause, restart and end the replay.
 * It also visualizes the original PDR and GNSS data along with fused trajectory.
 */
public class ReplayFragment extends Fragment {
    private static final String TAG = "ReplayFragment";

    // UI Components
    private Button playPauseButton;
    private Button restartButton;
    private Button goEndButton;
    private Button exitButton;
    private SeekBar playbackSeekBar;

    // Map Fragment
    private TrajectoryMapFragment mapFragment;

    // Replay Data
    private List<ReplayPoint> trajectoryData = new ArrayList<>();
    private int currentPointIndex = 0;
    private boolean isPlaying = false;
    private Handler playbackHandler = new Handler();
    private static final int PLAYBACK_INTERVAL_MS = 100; // 100ms interval between points

    // Fusion-related variables
    private KalmanFilterFusion fusionAlgorithm;
    private LatLng referencePosition; // Starting position for the local ENU coordinates
    private List<LatLng> fusedTrajectory = new ArrayList<>();
    private Polyline fusedTrajectoryLine;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_replay, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Get UI components
        playPauseButton = view.findViewById(R.id.playPauseButton);
        restartButton = view.findViewById(R.id.restartButton);
        goEndButton = view.findViewById(R.id.goEndButton);
        exitButton = view.findViewById(R.id.exitButton);
        playbackSeekBar = view.findViewById(R.id.playbackSeekBar);

        // Set up the map fragment
        mapFragment = (TrajectoryMapFragment) getChildFragmentManager().findFragmentById(R.id.replayMapFragmentContainer);
        if (mapFragment == null) {
            mapFragment = new TrajectoryMapFragment();
            getChildFragmentManager()
                    .beginTransaction()
                    .replace(R.id.replayMapFragmentContainer, mapFragment)
                    .commit();
        }

        // Set up UI controls
        setupUIControls();

        // Load trajectory data - Replace with your actual file path
        String trajectoryFilePath = getArguments().getString("trajFilePath");
        if (trajectoryFilePath != null) {
            loadTrajectoryData(trajectoryFilePath);
        } else {
            Log.e(TAG, "No trajectory file path provided");
        }
    }

    private void setupUIControls() {
        // Play/Pause button
        playPauseButton.setOnClickListener(v -> {
            if (isPlaying) {
                pausePlayback();
            } else {
                startPlayback();
            }
        });

        // Restart button
        restartButton.setOnClickListener(v -> {
            restartPlayback();
        });

        // Go to End button
        goEndButton.setOnClickListener(v -> {
            jumpToEnd();
        });

        // Exit button
        exitButton.setOnClickListener(v -> {
            requireActivity().onBackPressed();
        });

        // Seek bar
        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && !trajectoryData.isEmpty()) {
                    currentPointIndex = (int) (progress / 100.0 * (trajectoryData.size() - 1));
                    updateMapWithCurrentPoint();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                pausePlayback();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Keep paused
            }
        });
    }

    private void loadTrajectoryData(String filePath) {
        // Get the origin location (e.g., first point or some reference point)
        // For simplicity, I'm using 0,0 as default, but you should set this based on your data
        double originLat = 0;
        double originLng = 0;

        // Load the trajectory data
        trajectoryData = TrajParser.parseTrajectoryData(filePath, requireContext(), originLat, originLng);

        if (!trajectoryData.isEmpty()) {
            // Set the reference position for coordinate conversion
            ReplayPoint firstPoint = trajectoryData.get(0);
            if (firstPoint.pdrLocation != null) {
                referencePosition = firstPoint.pdrLocation;
                originLat = referencePosition.latitude;
                originLng = referencePosition.longitude;
            } else if (firstPoint.gnssLocation != null) {
                referencePosition = firstPoint.gnssLocation;
                originLat = referencePosition.latitude;
                originLng = referencePosition.longitude;
            }

            // Initialize fusion algorithm
            initializeFusion();

            // Process all points with fusion algorithm
            processTrajectorWithFusion();

            // Update UI
            playbackSeekBar.setMax(100); // Percentage-based
            playbackSeekBar.setProgress(0);

            // Set initial camera position
            if (mapFragment != null && referencePosition != null) {
                mapFragment.setInitialCameraPosition(referencePosition);
            }

            // Display the first point
            currentPointIndex = 0;
            updateMapWithCurrentPoint();

            Log.i(TAG, "Loaded " + trajectoryData.size() + " trajectory points");
        } else {
            Log.e(TAG, "No trajectory data loaded");
        }
    }

    private void initializeFusion() {
        // Initialize the fusion algorithm with the reference position
        double[] refPos = new double[3];
        refPos[0] = referencePosition.latitude;
        refPos[1] = referencePosition.longitude;
        refPos[2] = 0; // Assuming altitude is 0 for simplicity

        fusionAlgorithm = new KalmanFilterFusion(refPos);
    }

    private void processTrajectorWithFusion() {
        // Process all points with the fusion algorithm to create a fused trajectory
        fusedTrajectory.clear();

        double[] refPos = new double[]{
                referencePosition.latitude,
                referencePosition.longitude,
                0  // Assuming altitude is 0 for simplicity
        };

        for (ReplayPoint point : trajectoryData) {
            if (point.pdrLocation != null) {
                // Convert PDR location to ENU coordinates
                double[] enu = CoordinateConverter.geodetic2Enu(
                        point.pdrLocation.latitude,
                        point.pdrLocation.longitude,
                        0, // Assuming altitude is 0
                        refPos[0],
                        refPos[1],
                        refPos[2]
                );

                // Process PDR update in fusion algorithm
                fusionAlgorithm.processPdrUpdate((float)enu[0], (float)enu[1], 0);
            }

            if (point.gnssLocation != null) {
                // Process GNSS update in fusion algorithm
                fusionAlgorithm.processGnssUpdate(point.gnssLocation, 0);
            }

            // Get the fused position and add it to the trajectory
            LatLng fusedPosition = fusionAlgorithm.getFusedPosition();
            if (fusedPosition != null) {
                fusedTrajectory.add(fusedPosition);
            }
        }

        Log.i(TAG, "Processed " + fusedTrajectory.size() + " fused points");
    }

    private void startPlayback() {
        if (trajectoryData.isEmpty()) return;

        isPlaying = true;
        playPauseButton.setText(R.string.replay_frag_pause);

        // Start the playback loop
        playbackHandler.removeCallbacksAndMessages(null); // Clear any pending callbacks
        playbackHandler.post(playbackRunnable);
    }

    private void pausePlayback() {
        isPlaying = false;
        playPauseButton.setText(R.string.replay_frag_play);
        playbackHandler.removeCallbacksAndMessages(null); // Clear any pending callbacks
    }

    private void restartPlayback() {
        currentPointIndex = 0;
        updateMapWithCurrentPoint();
        playbackSeekBar.setProgress(0);

        // If was playing, continue playing from the start
        if (isPlaying) {
            pausePlayback();
            startPlayback();
        }
    }

    private void jumpToEnd() {
        if (trajectoryData.isEmpty()) return;

        currentPointIndex = trajectoryData.size() - 1;
        updateMapWithCurrentPoint();
        playbackSeekBar.setProgress(100);
        pausePlayback();
    }

    private final Runnable playbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlaying && currentPointIndex < trajectoryData.size() - 1) {
                currentPointIndex++;
                updateMapWithCurrentPoint();

                // Update seek bar
                int progress = (int) (currentPointIndex * 100.0 / (trajectoryData.size() - 1));
                playbackSeekBar.setProgress(progress);

                // Schedule next update
                playbackHandler.postDelayed(this, PLAYBACK_INTERVAL_MS);
            } else if (currentPointIndex >= trajectoryData.size() - 1) {
                // End of playback
                pausePlayback();
            }
        }
    };

    private void updateMapWithCurrentPoint() {
        if (trajectoryData.isEmpty() || currentPointIndex >= trajectoryData.size()) return;

        ReplayPoint currentPoint = trajectoryData.get(currentPointIndex);

        // Update user location and orientation
        if (currentPoint.pdrLocation != null && mapFragment != null) {
            mapFragment.updateUserLocation(currentPoint.pdrLocation, currentPoint.orientation);
        }

        // Update GNSS marker if available
        if (currentPoint.gnssLocation != null && mapFragment != null) {
            mapFragment.updateGNSS(currentPoint.gnssLocation);
        }

        // Update fused position if available
        if (currentPointIndex < fusedTrajectory.size()) {
            LatLng fusedPosition = fusedTrajectory.get(currentPointIndex);
            // Update the map with the fused position using the new method
            mapFragment.updateFusedPosition(fusedPosition);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        pausePlayback();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        playbackHandler.removeCallbacksAndMessages(null);
    }
}