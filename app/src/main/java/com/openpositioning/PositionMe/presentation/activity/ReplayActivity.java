package com.openpositioning.PositionMe.presentation.activity;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.ReplayFragment;

public class ReplayActivity extends AppCompatActivity {

    public static final String EXTRA_TRAJECTORY_FILE_PATH = "extra_trajectory_file_path";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_replay);

        // Get the trajectory file path from the Intent
        String filePath = getIntent().getStringExtra(EXTRA_TRAJECTORY_FILE_PATH);
        if (filePath == null || filePath.isEmpty()) {
            // If not provided, set a default path (or show an error message)
            filePath = "/storage/emulated/0/Download/trajectory_default.txt";

            Log.e("ReplayActivity", "No trajectory file path provided");
        }

        if (savedInstanceState == null) {
            showReplayFragment(filePath);
        }
    }

    /**
     * Displays the ReplayFragment and passes the trajectory file path as an argument.
     */
    public void showReplayFragment(String filePath) {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ReplayFragment replayFragment = new ReplayFragment();
        // Pass the file path through a Bundle
        Bundle args = new Bundle();
        args.putString(EXTRA_TRAJECTORY_FILE_PATH, filePath);
        replayFragment.setArguments(args);
        ft.replace(R.id.replayActivityContainer, replayFragment);
        ft.commit();
    }

    /**
     * Called when the replay process is completed.
     */
    public void finishFlow() {
        finish();
    }
}