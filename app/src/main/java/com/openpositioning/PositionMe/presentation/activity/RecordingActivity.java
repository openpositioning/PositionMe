package com.openpositioning.PositionMe.presentation.activity;

import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
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

import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.util.TypedValue;
import android.graphics.text.LineBreaker;


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
     * This dialog now configures:
     * 1. the trajectory name to be stored in the recording payload
     * 2. whether the adaptive heading calibrator is enabled for this session
     *
     * Important UI clarification:
     * - the user does NOT choose between PDR and particle fusion here
     * - the recording screen can still display the standard PDR path for reference
     * - if particle fusion is enabled elsewhere in the app, it will still be used
     *   by the live fusion pipeline automatically
     *
     * Design intent:
     * keep this dialog simple and focused on what the user must set per session,
     * while explaining clearly what particle fusion does without presenting it as
     * a confusing mode switch at the start of every recording.
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
        intro.setText("Set up this recording before starting.");
        styleDialogParagraph(intro, 15f);
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

        TextView fusionTitle = new TextView(this);
        fusionTitle.setText("Trajectory display and fusion");
        styleDialogHeading(fusionTitle);
        LinearLayout.LayoutParams fusionTitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        fusionTitleParams.topMargin = sectionSpacing;
        container.addView(fusionTitle, fusionTitleParams);

        TextView fusionDescription = new TextView(this);
        fusionDescription.setPadding(0, dp(4), 0, 0);
        styleDialogParagraph(fusionDescription, 14f);

        boolean particleFusionEnabled = SensorFusion.getInstance().isParticleFilterTrajectoryMode();
        if (particleFusionEnabled) {
            fusionDescription.setText(
                    "Particle fusion is currently enabled. During recording, the app may use fused positioning " +
                            "(for example PDR combined with WiFi/GNSS and map constraints) to improve the main estimated position. " +
                            "The recording screen can still show the standard PDR trajectory for reference."
            );
        } else {
            fusionDescription.setText(
                    "The recording screen can show the standard PDR trajectory for reference. " +
                            "The app uses a particle-filter-based fusion framework integrated with map matching and map constraints to improve indoor trajectory estimation."
            );
        }
        container.addView(fusionDescription);

        TextView noteText = new TextView(this);
        noteText.setPadding(0, dp(8), 0, 0);
        styleDialogParagraph(noteText, 13f);
        noteText.setText(
                "You do not need to choose the positioning engine here. " +
                        "This setup only names the recording and configures session-specific heading behaviour."
        );
        container.addView(noteText);

        TextView adaptiveHeadingTitle = new TextView(this);
        adaptiveHeadingTitle.setText("Heading option");
        styleDialogHeading(adaptiveHeadingTitle);
        LinearLayout.LayoutParams adaptiveHeadingTitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        adaptiveHeadingTitleParams.topMargin = sectionSpacing;
        container.addView(adaptiveHeadingTitle, adaptiveHeadingTitleParams);

        SwitchMaterial adaptiveHeadingSwitch = new SwitchMaterial(this);
        adaptiveHeadingSwitch.setText("Use adaptive heading calibrator (QSMFI-style)");
        adaptiveHeadingSwitch.setChecked(SensorFusion.getInstance().isAdaptiveHeadingEnabled());
        LinearLayout.LayoutParams adaptiveHeadingParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        adaptiveHeadingParams.topMargin = dp(8);
        container.addView(adaptiveHeadingSwitch, adaptiveHeadingParams);

        TextView adaptiveHeadingDescription = new TextView(this);
        adaptiveHeadingDescription.setPadding(0, dp(4), 0, 0);
        styleDialogParagraph(adaptiveHeadingDescription, 13f);
        container.addView(adaptiveHeadingDescription);

        Runnable updateAdaptiveHeadingDescription = () -> {
            if (adaptiveHeadingSwitch.isChecked()) {
                adaptiveHeadingDescription.setText(
                        "Adaptive heading is applied for this session before motion updates. " +
                                "This usually gives a more stable heading reference for both live positioning and the saved trajectory."
                );
            } else {
                adaptiveHeadingDescription.setText(
                        "Raw heading mode uses the current rotation-vector heading directly, " +
                                "with no adaptive absolute-yaw correction."
                );
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
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                if (name.isEmpty()) {
                    name = generateDefaultTrajectoryName();
                }

                applyTrajectorySetupAndContinue(
                        name,
                        SensorFusion.getInstance().isParticleFilterTrajectoryMode(),
                        adaptiveHeadingSwitch.isChecked()
                );
                dialog.dismiss();
            });

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                applyTrajectorySetupAndContinue(
                        generateDefaultTrajectoryName(),
                        SensorFusion.getInstance().isParticleFilterTrajectoryMode(),
                        adaptiveHeadingSwitch.isChecked()
                );
                dialog.dismiss();
            });
        });

        dialog.show();
    }

    private void styleDialogHeading(@NonNull TextView textView) {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private void styleDialogParagraph(@NonNull TextView textView, float textSizeSp) {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        textView.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            textView.setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD);
        }
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
