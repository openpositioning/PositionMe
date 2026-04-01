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
 * @author ShuGu
 */

public class RecordingActivity extends AppCompatActivity {

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_recording);

    if (savedInstanceState == null) {
      // Show trajectory name input dialog before proceeding to start location
      showTrajectoryNameDialog();
    }

    // Keep screen on
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  /**
   * {@inheritDoc}
   * Re-registers sensor listeners so that IMU, step detection, barometer and other
   * movement sensors remain active while this activity is in the foreground.
   * Without this, sensors are unregistered when {@link MainActivity#onPause()} fires
   * during the activity transition, leaving PDR and elevation updates dead.
   */
  @Override
  protected void onResume() {
    super.onResume();
    SensorFusion.getInstance().resumeListening();
  }

  /**
   * {@inheritDoc}
   * Stops sensor listeners when this activity is no longer visible, unless
   * the foreground {@link SensorCollectionService} is running (recording in progress).
   */
  @Override
  protected void onPause() {
    super.onPause();
    if (!SensorCollectionService.isRunning()) {
      SensorFusion.getInstance().stopListening();
    }
  }

  /**
   * Shows an AlertDialog prompting the user to enter a trajectory name.
   * The name is stored in SensorFusion as trajectory_id and later written to the protobuf.
   * After input, proceeds to StartLocationFragment.
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
        .setPositiveButton("Save", (dialog, which) -> {
          String name = input.getText().toString().trim();
          if (name.isEmpty()) {
            // Default name based on timestamp
            name = "traj_" + System.currentTimeMillis();
          }
          SensorFusion.getInstance().setTrajectoryId(name);
          showStartLocationScreen();
        })
        .setNegativeButton("Skip", (dialog, which) -> {
          // Use default name
          SensorFusion.getInstance().setTrajectoryId(
              "traj_" + System.currentTimeMillis());
          showStartLocationScreen();
        })
        .show();
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
