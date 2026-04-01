package com.openpositioning.PositionMe.presentation.activity;

import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.service.SensorCollectionService;
import com.openpositioning.PositionMe.presentation.fragment.RecordingFragment;
import com.openpositioning.PositionMe.presentation.fragment.CorrectionFragment;


/**
 * Manages the recording flow: name dialog → live recording → correction.
 * The start location is anchored automatically from the first GPS fix;
 * no manual pin-drop screen is required.
 *
 * @see RecordingFragment live map and sensor data during walking.
 * @see CorrectionFragment review/correct before upload.
 *
 * @author ShuGu
 */
public class RecordingActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recording);

        if (savedInstanceState == null) {
            showTrajectoryNameDialog();
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SensorFusion.getInstance().resumeListening();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!SensorCollectionService.isRunning()) {
            SensorFusion.getInstance().stopListening();
        }
    }

    /**
     * Prompts for a trajectory name, then starts recording immediately.
     * The GPS anchor is written automatically on the first location fix.
     */
    private void showTrajectoryNameDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("e.g. Nucleus_Walk_01");
        input.setPadding(48, 24, 48, 24);

        new AlertDialog.Builder(this)
                .setTitle("Trajectory Name")
                .setMessage("Enter a name for this recording session:")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Start", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "traj_" + System.currentTimeMillis();
                    SensorFusion sf = SensorFusion.getInstance();
                    sf.setTrajectoryId(name);
                    sf.startRecording();
                    showRecordingScreen();
                })
                .setNegativeButton("Skip", (dialog, which) -> {
                    SensorFusion sf = SensorFusion.getInstance();
                    sf.setTrajectoryId("traj_" + System.currentTimeMillis());
                    sf.startRecording();
                    showRecordingScreen();
                })
                .show();
    }

    /** Show the RecordingFragment (live map + sensors). */
    public void showRecordingScreen() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(R.id.mainFragmentContainer, new RecordingFragment());
        ft.addToBackStack(null);
        ft.commit();
    }

    /** Show the CorrectionFragment after the user stops recording. */
    public void showCorrectionScreen() {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(R.id.mainFragmentContainer, new CorrectionFragment());
        ft.addToBackStack(null);
        ft.commit();
    }

    /** Finish the Activity once corrections are done. */
    public void finishFlow() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        finish();
    }
}
