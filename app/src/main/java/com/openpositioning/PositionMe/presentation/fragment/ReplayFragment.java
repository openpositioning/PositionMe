package com.openpositioning.PositionMe.presentation.fragment;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Sub fragment of Replay Activity. Fragment that replays trajectory data on a map.
 * <p>
 * The ReplayFragment is responsible for visualizing and replaying trajectory data captured during
 * previous recordings. It loads trajectory data from a JSON file, updates the map with user
 * movement, and provides UI controls for playback, pause, and seek functionalities.
 * <p>
 * Features:
 * - Loads trajectory data from a file and displays it on a map.
 * - Provides playback controls including play, pause, restart, and go to end.
 * - Updates the trajectory dynamically as playback progresses.
 * - Allows users to manually seek through the recorded trajectory.
 * - Integrates with {@link TrajectoryMapFragment} for map visualization.
 *
 * @author Shu Gu
 * @see TrajectoryMapFragment The map fragment displaying the trajectory.
 * @see ReplayActivity The activity managing the replay workflow.
 * @see TrajParser Utility class for parsing trajectory data.
 */
public class ReplayFragment extends Fragment {

  private static final String TAG = "ReplayFragment";

  // GPS start location (received from ReplayActivity)
  private LatLng initialLatLng;
  private String filePath = "";
  private int lastIndex = -1;
  private boolean takeGnssAsFirstIdx = true;

  // Progress Bar
  private LinearLayout progressLayout;
  private TextView progressText;
  private ProgressBar progressBar;

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
  private boolean isTrajectoryParsed = false;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Initialize Progress bar UI

    // Retrieve transferred data from ReplayActivity
    if (getArguments() != null) {
      filePath = getArguments().getString(ReplayActivity.EXTRA_TRAJECTORY_FILE_PATH, "");
      initialLatLng = new LatLng(
          getArguments().getFloat(ReplayActivity.EXTRA_INITIAL_LAT, 0f),
          getArguments().getFloat(ReplayActivity.EXTRA_INITIAL_LON, 0f));
      takeGnssAsFirstIdx = getArguments().getBoolean(ReplayActivity.TAKE_GNSS, true);
    }

    // Log the received data
    Log.i(TAG, "ReplayFragment received data:");
    Log.i(TAG, "Trajectory file path: " + filePath);
    Log.i(TAG, "Initial latitude: " + initialLatLng.latitude);
    Log.i(TAG, "Initial longitude: " + initialLatLng.longitude);

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

    // Initialize Progress bar
    progressLayout = view.findViewById(R.id.progress_layout);
    progressText = view.findViewById(R.id.progress_bar_text);
    progressBar = view.findViewById(R.id.progressBar5);

    // Initialize UI controls
    playPauseButton = view.findViewById(R.id.playPauseButton);
    restartButton = view.findViewById(R.id.restartButton);
    exitButton = view.findViewById(R.id.exitButton);
    goEndButton = view.findViewById(R.id.goEndButton);
    playbackSeekBar = view.findViewById(R.id.playbackSeekBar);

    // Trajectory not yet parsed - disable UI components
    toggleUIComponents(false);
    // Parse the JSON file and prepare replayData using TrajParser. Do it in the background

    ExecutorService executor = Executors.newSingleThreadExecutor();
    executor.submit(() -> {
      TrajParser.parseTrajectoryData(filePath, requireContext(), initialLatLng, takeGnssAsFirstIdx,
          new TrajParser.TrajectoryParseCallback() {
            @Override
            public void progress(int completed, int total) {
              progressText.setText(String.format("Fetching WiFi data: %d / %d points",
                  completed, total));
              progressBar.setMax(total);
              progressBar.setProgress(completed);
            }

            @Override
            public void onTrajectoryParsed(List<TrajParser.ReplayPoint> replayPoints) {
              replayData = replayPoints;
              getActivity().runOnUiThread(() -> toggleUIComponents(true));
            }

            @Override
            public void onError(Exception e) {
              getActivity().runOnUiThread(() -> toggleUIComponents(false));
              Log.e("ReplayFragment", "Trajectory load failed! Exception: " + e.toString());
            }
          });

      // Log the number of parsed points
      if (replayData != null && !replayData.isEmpty()) {
        Log.i(TAG, "Trajectory data loaded successfully. Total points: " + replayData.size());
      } else {
        Log.e(TAG, "Failed to load trajectory data! replayData is empty or null.");
      }
    });

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
  }

  private void toggleProgressDisplay(boolean toggle) {
    if (toggle) {
      progressBar.bringToFront();
      progressText.bringToFront();
      progressBar.setVisibility(View.VISIBLE);
      progressText.setVisibility(View.VISIBLE);
      progressLayout.setVisibility(View.VISIBLE);
      progressLayout.bringToFront();
      progressLayout.setBackgroundColor(Color.WHITE);
    } else {
      progressBar.setVisibility(View.GONE);
      progressText.setVisibility(View.GONE);
      progressLayout.setVisibility(View.GONE);
    }
  }

  private void toggleUIComponents(boolean enabled) {
    toggleProgressDisplay(!enabled);
    isTrajectoryParsed = enabled;
    // Handle the buttons
    restartButton.setEnabled(enabled);
    playPauseButton.setEnabled(enabled);
    exitButton.setEnabled(enabled);
    goEndButton.setEnabled(enabled);
    // Playback bar
    playbackSeekBar.setEnabled(enabled);

    if (enabled) {
      // Set the initial location as that of the fused data
      if (!replayData.isEmpty()) {
        trajectoryMapFragment.setInitialCameraPosition(replayData.get(0).fusedLocation);
      } else {
        trajectoryMapFragment.setInitialCameraPosition(initialLatLng);
      }

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
        if (replayData.isEmpty()) {
          return;
        }
        currentIndex = 0;
        playbackSeekBar.setProgress(0);
        Log.i(TAG, "Restart button pressed. Resetting playback to index 0.");
        updateMapForIndex(0);
      });

      // Go to End button listener
      goEndButton.setOnClickListener(v -> {
        if (replayData.isEmpty()) {
          return;
        }
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

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
      });

      if (!replayData.isEmpty()) {
        updateMapForIndex(0);
      }
    }
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
   * Show a simple dialog asking user to pick:
   * 1) GNSS from file
   * 2) Lat/Lon from ReplayActivity arguments
   */
  private void showGnssChoiceDialog() {
    new AlertDialog.Builder(requireContext())
        .setTitle("Choose Starting Location")
        .setMessage(
            "Would you like to use the file's GNSS as the start, or the one you manually picked?")
        .setPositiveButton("Use File's GNSS", (dialog, which) -> {
          LatLng firstGnss = getFirstGnssLocation(replayData);
          if (firstGnss != null) {
            setupInitialMapPosition(firstGnss);
          } else {
            // Fallback if no valid GNSS found
            setupInitialMapPosition(initialLatLng);
          }
          dialog.dismiss();
        })
        .setNegativeButton("Use Manual Set", (dialog, which) -> {
          setupInitialMapPosition(initialLatLng);
          dialog.dismiss();
        })
        .setCancelable(false)
        .show();
  }

  private void setupInitialMapPosition(LatLng pos) {
    initialLatLng = pos;
    Log.i(TAG, "Setting initial map position: " + pos.toString());
    trajectoryMapFragment.setInitialCameraPosition(pos);
  }

  /**
   * Retrieve the first available GNSS location from the replay data.
   */
  private LatLng getFirstGnssLocation(List<TrajParser.ReplayPoint> data) {
    for (TrajParser.ReplayPoint point : data) {
      if (point.gnssLocation != null) {
        return new LatLng(point.gnssLocation.position().latitude, point.gnssLocation.position().longitude);
      }
    }
    return null; // None found
  }


  /**
   * Runnable for playback of trajectory data. This runnable is called repeatedly to update the map
   * with the next point in the replayData list.
   */
  private final Runnable playbackRunnable = new Runnable() {
    @Override
    public void run() {
      if (!isPlaying || replayData.isEmpty()) {
        return;
      }

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
    if (newIndex < 0 || newIndex >= replayData.size()) {
      return;
    }
    // Detect if user is playing sequentially (lastIndex + 1)
    // or is skipping around (backwards, or jump forward)
    boolean isSequentialForward = (newIndex == lastIndex + 1);

    if (!isSequentialForward) {
      // Clear everything and redraw up to newIndex
      trajectoryMapFragment.clearMapAndReset();
      for (int i = 0; i <= newIndex; i++) {
        TrajParser.ReplayPoint p = replayData.get(i);
        // TODO: Allow setting to disable fusion.
        trajectoryMapFragment.updateUserLocation(p.fusedLocation, p.orientation);
        trajectoryMapFragment.updatePdrLocation(p.pdrLocation);
        if (p.gnssLocation != null) {
          trajectoryMapFragment.updateGNSS(p.gnssLocation.position(),
                  new float[] {p.gnssLocation.isOutlier() ? 1 : 0});
        }
        // Plot the latest WiFi datapoint
        if (p.wifiLocation != null) {
          trajectoryMapFragment.updateWifi(p.wifiLocation.position(),
                  new float[] {p.wifiLocation.isOutlier() ? 1 : 0});
          trajectoryMapFragment.updateFloor(p.wifiLocation.floor());
        }
      }
    } else {
      // Normal sequential forward step: add just the new point
      TrajParser.ReplayPoint p = replayData.get(newIndex);
      trajectoryMapFragment.updateUserLocation(p.fusedLocation, p.orientation);
      trajectoryMapFragment.updatePdrLocation(p.pdrLocation);
      if (p.gnssLocation != null) {
        trajectoryMapFragment.updateGNSS(p.gnssLocation.position(),
                new float[] {p.gnssLocation.isOutlier() ? 1 : 0});
      }
      // Plot the latest WiFi datapoint
      if (p.wifiLocation != null) {
        trajectoryMapFragment.updateWifi(p.wifiLocation.position(),
                new float[] {p.wifiLocation.isOutlier() ? 1 : 0});
        trajectoryMapFragment.updateFloor(p.wifiLocation.floor());
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
