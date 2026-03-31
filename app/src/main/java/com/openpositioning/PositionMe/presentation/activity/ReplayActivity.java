package com.openpositioning.PositionMe.presentation.activity;

import android.os.Bundle;
import android.util.Log;
import java.io.File;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.local.TrajParser;
import com.openpositioning.PositionMe.presentation.fragment.ReplayFragment;
import com.openpositioning.PositionMe.presentation.fragment.StartLocationFragment;


/**
 * The ReplayActivity is responsible for managing the replay session of a user's trajectory.
 * It handles the process of retrieving the trajectory data, displaying relevant fragments, and
 * facilitating the interaction with the user to choose the starting location before displaying the
 * replay of the trajectory.
 * <p>
 * The activity starts by extracting the trajectory file path from the intent that launched it. If
 * the file path is not provided or is empty, it uses a default file path. It ensures that the trajectory
 * file exists before proceeding. Once the file is verified, it shows the StartLocationFragment, which allows
 * the user to select their starting location (latitude and longitude). After the user has selected the
 * starting point, the activity switches to the ReplayFragment to display the replay of the user's trajectory.
 * <p>
 * The activity also provides functionality to finish the replay session and exit the activity once the replay
 * process has completed.
 * <p>
 * This activity makes use of a few key constants for passing data between fragments, including the trajectory file
 * path and the initial latitude and longitude. These constants are defined at the beginning of the class.
 * <p>
 * The ReplayActivity manages the interaction between fragments by facilitating communication from the
 * StartLocationFragment to the ReplayFragment, where the replay of the trajectory is displayed.
 *
 * @see StartLocationFragment The fragment where the user selects their start location for the trajectory replay.
 * @see ReplayFragment The fragment responsible for showing the trajectory replay.
 *
 * @author Shu Gu
 */

public class ReplayActivity extends AppCompatActivity {

    public static final String TAG = "ReplayActivity";
    public static final String EXTRA_INITIAL_LAT = "extra_initial_lat";
    public static final String EXTRA_INITIAL_LON = "extra_initial_lon";
    public static final String EXTRA_TRAJECTORY_FILE_PATH = "extra_trajectory_file_path";
    // Keys for passing the file's original recording position to StartLocationFragment
    public static final String EXTRA_FILE_INITIAL_LAT = "extra_file_initial_lat";
    public static final String EXTRA_FILE_INITIAL_LON = "extra_file_initial_lon";
    public static final String EXTRA_FLOOR = "extra_floor";

    private String filePath;
    // The original recording position extracted from the trajectory file
    private float fileInitialLat = 0f;
    private float fileInitialLon = 0f;
    // The venue floor extracted from the trajectory file (e.g. "F1", "GF")
    private String fileFloor = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_replay);
        // Get the trajectory file path from the Intent
        filePath = getIntent().getStringExtra(EXTRA_TRAJECTORY_FILE_PATH);

        // Debug log: Received file path
        Log.i(TAG, "Received trajectory file path: " + filePath);

        if (filePath == null || filePath.isEmpty()) {
            // If not provided, set a default path (or show an error message)
            filePath = "/storage/emulated/0/Download/trajectory_default.txt";
            Log.e(TAG, "No trajectory file path provided, using default: " + filePath);
        }

        // Check if file exists before proceeding
        if (!new File(filePath).exists()) {
            Log.e(TAG, "Trajectory file does NOT exist: " + filePath);
        } else {
            Log.i(TAG, "Trajectory file exists: " + filePath);

            // Extract the original recording position from the trajectory file
            LatLng filePosition = TrajParser.extractInitialPosition(filePath);
            if (filePosition != null) {
                fileInitialLat = (float) filePosition.latitude;
                fileInitialLon = (float) filePosition.longitude;
                Log.i(TAG, "Extracted recording position from file: Lat=" + fileInitialLat + ", Lon=" + fileInitialLon);
            } else {
                Log.w(TAG, "No initial position found in trajectory file, will use current GPS as fallback.");
            }
            // Extract the venue floor from the trajectory file
            fileFloor = TrajParser.extractFloor(filePath);
            Log.i(TAG, "Extracted floor from file: " + (fileFloor.isEmpty() ? "(none)" : fileFloor));
        }

        // Show StartLocationFragment first to let user pick location
        if (savedInstanceState == null) {
            // If we successfully extracted a position from the file, skip the
            // StartLocationFragment and go directly to ReplayFragment with this position.
            // The StartLocationFragment is only useful when recording (to let the user
            // fine-tune their start location). During replay, the recording's original
            // position should be used automatically.
            if (fileInitialLat != 0f || fileInitialLon != 0f) {
                Log.i(TAG, "File has a valid initial position, skipping StartLocationFragment.");
                showReplayFragment(filePath, fileInitialLat, fileInitialLon);
            } else {
                Log.w(TAG, "No initial position from file, showing StartLocationFragment.");
                showStartLocationFragment();
            }
        }
    }

    /**
     * Display a StartLocationFragment to let user set their start location.
     * Passes the file's original recording position so the map defaults to the correct location.
     */
    private void showStartLocationFragment() {
        Log.d(TAG, "Showing StartLocationFragment...");
        StartLocationFragment startLocationFragment = new StartLocationFragment();

        // Pass the file's initial position to the fragment
        if (fileInitialLat != 0f || fileInitialLon != 0f) {
            Bundle args = new Bundle();
            args.putFloat(EXTRA_FILE_INITIAL_LAT, fileInitialLat);
            args.putFloat(EXTRA_FILE_INITIAL_LON, fileInitialLon);
            startLocationFragment.setArguments(args);
            Log.d(TAG, "Passing file recording position to StartLocationFragment: " 
                    + fileInitialLat + ", " + fileInitialLon);
        }

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.replayActivityContainer, startLocationFragment)
                .commit();
    }

    /**
     * Called by StartLocationFragment when user picks their start location.
     */
    public void onStartLocationChosen(float lat, float lon) {
        Log.i(TAG, "User selected start location: Lat=" + lat + ", Lon=" + lon);
        showReplayFragment(filePath, lat, lon);
    }

    /**
     * Display ReplayFragment, passing file path and starting lat/lon as arguments.
     */
    public void showReplayFragment(String filePath, float initialLat, float initialLon) {
        Log.d(TAG, "Switching to ReplayFragment with file: " + filePath +
                ", Initial Lat: " + initialLat + ", Initial Lon: " + initialLon);

        ReplayFragment replayFragment = new ReplayFragment();
        // Pass the file path through a Bundle
        Bundle args = new Bundle();
        args.putString(EXTRA_TRAJECTORY_FILE_PATH, filePath);
        args.putFloat(EXTRA_INITIAL_LAT, initialLat);
        args.putFloat(EXTRA_INITIAL_LON, initialLon);
        args.putString(EXTRA_FLOOR, fileFloor);
        replayFragment.setArguments(args);

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.replayActivityContainer, replayFragment)
                .commit();
    }

    /**
     * Finish replay session
     * Called when the replay process is completed.
     */
    public void finishFlow() {
        Log.d(TAG, "Replay session finished.");
        finish();
    }
}