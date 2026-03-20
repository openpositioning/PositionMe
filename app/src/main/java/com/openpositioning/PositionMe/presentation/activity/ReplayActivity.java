package com.openpositioning.PositionMe.presentation.activity;

import android.os.Bundle;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.ReplayFragment;
import com.openpositioning.PositionMe.presentation.fragment.StartLocationFragment;

/**
 * Replay activity with optional forced asset replay mode.
 *
 * When FORCE_REPLAY_ASSET is true, this activity will ignore any file path passed from History
 * and instead copy an asset trajectory file into cache, then replay that file.
 *
 * This is useful for validating replay/map-matching logic with a known test trajectory without
 * touching the existing History/download_records flow.
 */
public class ReplayActivity extends AppCompatActivity {

    public static final String TAG = "ReplayActivity";
    public static final String EXTRA_INITIAL_LAT = "extra_initial_lat";
    public static final String EXTRA_INITIAL_LON = "extra_initial_lon";
    public static final String EXTRA_TRAJECTORY_FILE_PATH = "extra_trajectory_file_path";

    /**
     * Set to true to force replay from app assets instead of History/download file paths.
     */
    private static final boolean FORCE_REPLAY_ASSET = true;
//等你验证完以后，如果要恢复原来正常逻辑，把它改成：false
    /**
     * Asset file to replay. Put this file under app/src/main/assets/replay/.
     */
    private static final String FORCED_ASSET_PATH = "replay/trajectory_reasonable_false_floor_change_reject.json";
//修改验证的 asset里的 json文件
    private String filePath;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_replay);

        if (FORCE_REPLAY_ASSET) {
            filePath = copyAssetToCache(FORCED_ASSET_PATH, "forced_replay_trajectory.txt");
            Log.i(TAG, "FORCE_REPLAY_ASSET enabled, using asset: " + FORCED_ASSET_PATH);
            Log.i(TAG, "Resolved replay file path: " + filePath);
        } else {
            filePath = getIntent().getStringExtra(EXTRA_TRAJECTORY_FILE_PATH);
            Log.i(TAG, "Received trajectory file path: " + filePath);

            if (filePath == null || filePath.isEmpty()) {
                filePath = "/storage/emulated/0/Download/trajectory_default.txt";
                Log.w(TAG, "No trajectory file path provided, using default: " + filePath);
            }
        }

        if (filePath == null || filePath.isEmpty()) {
            Log.e(TAG, "Replay file path is empty; replay cannot start.");
            finish();
            return;
        }

        if (!new File(filePath).exists()) {
            Log.e(TAG, "Trajectory file does NOT exist: " + filePath);
        } else {
            Log.i(TAG, "Trajectory file exists: " + filePath);
        }

        if (savedInstanceState == null) {
            showStartLocationFragment();
        }
    }

    private void showStartLocationFragment() {
        Log.d(TAG, "Showing StartLocationFragment...");
        StartLocationFragment startLocationFragment = new StartLocationFragment();
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.replayActivityContainer, startLocationFragment)
                .commit();
    }

    public void onStartLocationChosen(float lat, float lon) {
        Log.i(TAG, "User selected start location: Lat=" + lat + ", Lon=" + lon);
        showReplayFragment(filePath, lat, lon);
    }

    public void showReplayFragment(String filePath, float initialLat, float initialLon) {
        Log.d(TAG, "Switching to ReplayFragment with file: " + filePath +
                ", Initial Lat: " + initialLat + ", Initial Lon: " + initialLon);

        ReplayFragment replayFragment = new ReplayFragment();
        Bundle args = new Bundle();
        args.putString(EXTRA_TRAJECTORY_FILE_PATH, filePath);
        args.putFloat(EXTRA_INITIAL_LAT, initialLat);
        args.putFloat(EXTRA_INITIAL_LON, initialLon);
        replayFragment.setArguments(args);

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.replayActivityContainer, replayFragment)
                .commit();
    }

    public void finishFlow() {
        Log.d(TAG, "Replay session finished.");
        finish();
    }

    private String copyAssetToCache(String assetPath, String outFileName) {
        File outFile = new File(getCacheDir(), outFileName);
        try (InputStream inputStream = getAssets().open(assetPath);
             FileOutputStream outputStream = new FileOutputStream(outFile, false)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
            outputStream.flush();
            return outFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy replay asset to cache: " + assetPath, e);
            return null;
        }
    }
}
