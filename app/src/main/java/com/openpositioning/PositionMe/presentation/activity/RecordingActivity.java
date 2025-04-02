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
 * The RecordingActivity manages the recording flow of the application, guiding the user through a sequence
 * of screens for location selection, recording, and correction before finalizing the process.
 * <p>
 * This activity follows a structured workflow:
 * <ol>
 *     <li>StartLocationFragment - Allows users to select their starting location.</li>
 *     <li>RecordingFragment - Handles the recording process and contains a TrajectoryMapFragment.</li>
 *     <li>CorrectionFragment - Enables users to review and correct recorded data before completion.</li>
 * </ol>
 * <p>
 * The activity ensures that the screen remains on during the recording process to prevent interruptions.
 * It also provides fragment transactions for seamless navigation between different stages of the workflow.
 * <p>
 * This class is referenced in various fragments such as HomeFragment, StartLocationFragment,
 * RecordingFragment, and CorrectionFragment to control navigation through the recording flow.
 *
 * @see StartLocationFragment The first step in the recording process where users select their starting location.
 * @see RecordingFragment Handles data recording and map visualization.
 * @see CorrectionFragment Allows users to review and make corrections before finalizing the process.
 * @see com.openpositioning.PositionMe.R.layout#activity_recording The associated layout for this activity.
 *
 * @author Yueyan Zhao
 * @author Zizhen Wang
 * @author Chen Zhao
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
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        finish();
    }
}
