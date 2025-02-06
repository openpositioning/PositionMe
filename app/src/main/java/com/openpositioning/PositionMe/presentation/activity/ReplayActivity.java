package com.openpositioning.PositionMe.presentation.activity;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.ReplayFragment;
import com.openpositioning.PositionMe.presentation.fragment.StartLocationFragment;

/**
 * Activity that replays a previously recorded trajectory.
 * Flow:
 * 1) StartLocationFragment (user can correct start pos).
 * 2) ReplayFragment (map + playback controls).
 */
public class ReplayActivity extends AppCompatActivity {

    public static final String EXTRA_TRAJECTORY_FILE_PATH = "extra_trajectory_file_path";
    // Possibly pass the file path or other ID from the "Files" section.

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_replay);

        if (savedInstanceState == null) {
            showStartLocationFragment();
        }
    }

    /**
     * Show StartLocationFragment to allow user to correct the start location
     * (similar to Recording flow).
     */
    public void showStartLocationFragment() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(R.id.replayActivityContainer, new StartLocationFragment());
        ft.commit();
    }

    /**
     * Show the ReplayFragment with the final corrected start location.
     */
    public void showReplayFragment() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(R.id.replayActivityContainer, new ReplayFragment());
        ft.addToBackStack(null);
        ft.commit();
    }

    /**
     * Called after finishing replay or user chooses to exit.
     */
    public void finishFlow() {
        finish();
    }
}
