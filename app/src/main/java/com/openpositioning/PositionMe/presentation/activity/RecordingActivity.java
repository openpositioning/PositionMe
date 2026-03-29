package com.openpositioning.PositionMe.presentation.activity;

import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.CorrectionFragment;
import com.openpositioning.PositionMe.presentation.fragment.RecordingFragment;
import com.openpositioning.PositionMe.presentation.fragment.StartLocationFragment;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.service.SensorCollectionService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.util.Log;


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
            showTrajectorySetupDialog();
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
     * Shows the per-session recording setup dialog.
     *
     * This dialog now configures two things before the user enters the normal
     * start-location flow:
     * 1. the trajectory name to be stored in the recording payload
     * 2. the trajectory engine for this session:
     *    - Standard PDR
     *    - Particle filter fusion
     *
     * Design intent:
     * the choice is session-scoped, so the user can run one recording in normal PDR
     * and the next one in PF mode without changing the app's persistent settings page.
     */
    private void showTrajectorySetupDialog() {
        final int outerPadding = dp(24);
        final int sectionSpacing = dp(16);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(outerPadding, outerPadding, outerPadding, outerPadding / 2);
        scrollView.addView(container,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView intro = new TextView(this);
        intro.setText("Set up this recording before selecting the start location.");
        intro.setTextSize(15f);
        container.addView(intro);

        TextInputLayout nameLayout = new TextInputLayout(this);
        nameLayout.setHint("Trajectory name");

        LinearLayout.LayoutParams nameLayoutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        nameLayoutParams.topMargin = sectionSpacing;
        container.addView(nameLayout, nameLayoutParams);

        TextInputEditText nameInput = new TextInputEditText(this);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        nameInput.setHint("e.g. Nucleus_Walk_01");
        nameInput.setSingleLine(true);
        nameInput.setText(generateDefaultTrajectoryName());

        LinearLayout.LayoutParams editTextParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        nameLayout.addView(nameInput, editTextParams);

        TextView modeTitle = new TextView(this);
        modeTitle.setText("Trajectory engine");
        modeTitle.setTextSize(16f);
        LinearLayout.LayoutParams modeTitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        modeTitleParams.topMargin = sectionSpacing;
        container.addView(modeTitle, modeTitleParams);

        TextView modeSubtitle = new TextView(this);
        modeSubtitle.setText("Choose how the live trajectory is drawn and saved during this session.");
        modeSubtitle.setTextSize(14f);
        container.addView(modeSubtitle);

        // Session mode toggle:
        // OFF -> standard PDR
        // ON  -> particle filter fusion
        //
        // We seed the switch from the current SensorFusion session state so reopening
        // the dialog during development/testing reflects the latest chosen mode.
        SwitchMaterial particleFilterSwitch = new SwitchMaterial(this);
        particleFilterSwitch.setText("Use particle filter fusion");
        particleFilterSwitch.setChecked(SensorFusion.getInstance().isParticleFilterTrajectoryMode());
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        switchParams.topMargin = dp(8);
        container.addView(particleFilterSwitch, switchParams);

        TextView modeDescription = new TextView(this);
        modeDescription.setTextSize(13f);
        modeDescription.setPadding(0, dp(4), 0, 0);
        container.addView(modeDescription);

        // Keep the explanation text in sync with the selected mode so the user
        // understands that this changes both the live path and the saved trajectory.
        Runnable updateModeDescription = () -> {
            if (particleFilterSwitch.isChecked()) {
                modeDescription.setText(
                        "Particle filter mode: uses your fused positioning pipeline for the live path and saved trajectory. " +
                                "This is useful when you want the path to follow WiFi/GNSS-assisted fusion rather than raw PDR only.");
            } else {
                modeDescription.setText(
                        "Standard PDR mode: uses the normal pedestrian dead reckoning path directly for both display and saved trajectory.");
            }
        };
        updateModeDescription.run();
        particleFilterSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> updateModeDescription.run());

        SwitchMaterial adaptiveHeadingSwitch = new SwitchMaterial(this);
        adaptiveHeadingSwitch.setText("Use adaptive heading calibrator (QSMFI-style)");
        adaptiveHeadingSwitch.setChecked(SensorFusion.getInstance().isAdaptiveHeadingEnabled());
        LinearLayout.LayoutParams adaptiveHeadingParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        adaptiveHeadingParams.topMargin = dp(12);
        container.addView(adaptiveHeadingSwitch, adaptiveHeadingParams);

        TextView adaptiveHeadingDescription = new TextView(this);
        adaptiveHeadingDescription.setTextSize(13f);
        adaptiveHeadingDescription.setPadding(0, dp(4), 0, 0);
        container.addView(adaptiveHeadingDescription);

        Runnable updateAdaptiveHeadingDescription = () -> {
            if (adaptiveHeadingSwitch.isChecked()) {
                adaptiveHeadingDescription.setText(
                        "Adaptive heading mode is session-scoped and is applied before both PDR and particle-filter motion updates. " +
                                "This keeps the PF prediction, saved path, and live map aligned to the same heading convention.");
            } else {
                adaptiveHeadingDescription.setText(
                        "Raw heading mode uses the current rotation-vector heading directly with no adaptive absolute-yaw correction.");
            }
        };
        updateAdaptiveHeadingDescription.run();
        adaptiveHeadingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> updateAdaptiveHeadingDescription.run());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("New Recording")
                .setView(scrollView)
                .setCancelable(false)
                .setPositiveButton("Continue", null)
                .setNegativeButton("Use Auto Name", null)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            // Keep the user-selected engine mode, but replace the name with an auto-generated one.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                if (name.isEmpty()) {
                    name = generateDefaultTrajectoryName();
                }
                applyTrajectorySetupAndContinue(
                        name,
                        particleFilterSwitch.isChecked(),
                        adaptiveHeadingSwitch.isChecked()
                );
                dialog.dismiss();
            });

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                applyTrajectorySetupAndContinue(
                        generateDefaultTrajectoryName(),
                        particleFilterSwitch.isChecked(),
                        adaptiveHeadingSwitch.isChecked()
                );
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    /**
     * Commits the session choices from the setup dialog into SensorFusion,
     * then continues into the normal start-location flow.
     *
     * This is the bridge between the UI choice and the downstream runtime behaviour:
     * - RecordingFragment will render the selected trajectory mode
     * - SensorEventHandler will save the selected trajectory mode
     */
    private static final String TAG = "RecordingActivity";
    private void applyTrajectorySetupAndContinue(String trajectoryName,
                                                 boolean useParticleFilter,
                                                 boolean useAdaptiveHeading) {
        SensorFusion sensorFusion = SensorFusion.getInstance();
        sensorFusion.setTrajectoryId(trajectoryName);
        sensorFusion.setRecordingTrajectoryMode(
                useParticleFilter
                        ? SensorFusion.TRAJECTORY_MODE_PARTICLE_FILTER
                        : SensorFusion.TRAJECTORY_MODE_PDR
        );
        sensorFusion.setUseAdaptiveQsmfiHeading(useAdaptiveHeading);

        Log.d(TAG, "New recording setup:"
                + " name=" + trajectoryName
                + ", mode=" + (useParticleFilter ? "PARTICLE_FILTER" : "STANDARD_PDR")
                + ", adaptiveHeading=" + useAdaptiveHeading);

        showStartLocationScreen();
    }

    /**
     * Generates a simple timestamp-based fallback name for the recording.
     *
     * Using a deterministic auto-name avoids empty trajectory IDs and makes quick
     * field testing easier when the user does not want to type a custom name.
     */
    private String generateDefaultTrajectoryName() {
        return "traj_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(new Date());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

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
