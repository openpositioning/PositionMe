package com.openpositioning.PositionMe.presentation.activity;

import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.StartLocationFragment;
import com.openpositioning.PositionMe.presentation.fragment.RecordingFragment;
import com.openpositioning.PositionMe.presentation.fragment.CorrectionFragment;

/**
 * This Activity hosts the flow:
 * StartLocationFragment -> RecordingFragment (+ TrajectoryMapFragment) -> CorrectionFragment -> finish
 */
public class RecordingActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recording);

        if (savedInstanceState == null) {
            showStartLocationScreen(); // Start with the user selecting the start location
        }

        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /**
     * Show the StartLocationFragment (beginning of flow).
     */
    public void showStartLocationScreen() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(R.id.mainFragmentContainer, new StartLocationFragment());
        ft.commit();
    }

    /**
     * Show the RecordingFragment, which contains the TrajectoryMapFragment internally.
     */
    public void showRecordingScreen() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(R.id.mainFragmentContainer, new RecordingFragment());
        ft.addToBackStack(null);
        ft.commit();
    }

    /**
     * Show the CorrectionFragment after the user stops recording.
     */
    public void showCorrectionScreen() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(R.id.mainFragmentContainer, new CorrectionFragment());
        ft.addToBackStack(null);
        ft.commit();
    }

    /**
     * Finish the Activity (or do any final steps) once corrections are done.
     */
    public void finishFlow() {
        // Option 1: Just finish this activity
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        finish();

        // Option 2: If you want to navigate somewhere else or show a "HomeFragment",
        // you could do so here instead.
    }
}
