package com.openpositioning.PositionMe.health;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class HealthActivity extends AppCompatActivity {

    private static final String TAG = HealthActivity.class.getSimpleName();

    private TextInputEditText distanceGoalInput, durationGoalInput;
    private TextView scoreTextView, feedbackTextView, distanceTextView, timeTextView;
    private ProgressBar scoreProgressBar;

    private WalkSessionSummary currentWalkSummary;

    @Override
    protected void onResume() {
        super.onResume();
        // Every time the Health page is resumed, refresh the data.
        currentWalkSummary = loadLatestWalk();
        updateScore();
        displaySessionMetrics();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_health);

        // --- Toolbar Setup ---
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        // --- View Binding ---
        distanceGoalInput = findViewById(R.id.distance_goal_input);
        durationGoalInput = findViewById(R.id.duration_goal_input);
        scoreTextView = findViewById(R.id.score_text);
        scoreProgressBar = findViewById(R.id.score_progress_bar);
        distanceTextView = findViewById(R.id.distance_text);
        timeTextView = findViewById(R.id.time_text);
        feedbackTextView = findViewById(R.id.feedback_text);

        // --- Initial Calculation ---
        currentWalkSummary = loadLatestWalk();
        updateScore();
        displaySessionMetrics();

        // --- Add Text Watchers to recalculate on change ---
        TextWatcher textWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                updateScore();
            }
        };
        distanceGoalInput.addTextChangedListener(textWatcher);
        durationGoalInput.addTextChangedListener(textWatcher);
    }

    /**
     * Loads the latest walk data from the recently completed recording.
     * It fetches real-time distance and time from the SensorFusion's in-memory mailbox.
     */
    private WalkSessionSummary loadLatestWalk() {
        // Directly read the distance and time from the just-completed recording from SensorFusion's memory.
        SensorFusion sensorFusion = SensorFusion.getInstance();
        double liveDist = sensorFusion.getLastRunDistance(); // This corresponds to the distance in RecordingFragment.
        long lastRunTimeInMillis = sensorFusion.getLastRunTime();

        // If liveDist > 0, it means a recording was just made and produced distance data.
        if (liveDist > 0.01) {
            Log.d(TAG, "Successfully captured live data from SensorFusion: " + liveDist + "m, time: " + lastRunTimeInMillis + "ms");
            return new WalkSessionSummary("live_session", liveDist, (int)(lastRunTimeInMillis / 1000), System.currentTimeMillis());
        }

        // If no recent recording data is found, return null.
        return null;
    }

    /**
     * Calculates the score based on the latest walk and user goals.
     */
    @SuppressLint("SetTextI18n")
    private void updateScore() {
        if (currentWalkSummary == null) {
            scoreTextView.setText("—");
            scoreProgressBar.setProgress(0);
            feedbackTextView.setText("No walks recorded yet.");
            return;
        }

        try {
            String distStr = Objects.requireNonNull(distanceGoalInput.getText()).toString();
            String durStr = Objects.requireNonNull(durationGoalInput.getText()).toString();

            if (distStr.isEmpty() || durStr.isEmpty()) {
                scoreTextView.setText("—");
                scoreProgressBar.setProgress(0);
                feedbackTextView.setText("Please enter your goals.");
                return;
            }

            double distanceGoalKm = Double.parseDouble(distStr);
            double durationGoalMin = Double.parseDouble(durStr);

            double distanceGoalM = distanceGoalKm * 1000;
            double durationGoalS = durationGoalMin * 60;

            ScoreCalculator calculator = new ScoreCalculator(distanceGoalM, durationGoalS);
            ScoreResult scoreResult = calculator.calculateScore(currentWalkSummary);

            int score = scoreResult.getScore0to100();
            scoreTextView.setText(String.valueOf(score));
            scoreProgressBar.setProgress(score);
            feedbackTextView.setText(scoreResult.getFeedbackText());

        } catch (NumberFormatException e) {
            Log.w(TAG, "Invalid number format in goal input.", e);
            scoreTextView.setText("—");
            scoreProgressBar.setProgress(0);
            feedbackTextView.setText("Please enter valid goals.");
        }
    }

    /**
     * Displays the metrics for the latest walk session.
     */
    private void displaySessionMetrics() {
        if (currentWalkSummary == null) {
            distanceTextView.setText("—");
            timeTextView.setText("—");
            return;
        }

        double distanceKm = currentWalkSummary.getDistanceMeters() / 1000.0;
        long totalSeconds = currentWalkSummary.getDurationSeconds();
        long minutes = TimeUnit.SECONDS.toMinutes(totalSeconds);
        long seconds = totalSeconds - TimeUnit.MINUTES.toSeconds(minutes);


        distanceTextView.setText(String.format(Locale.getDefault(), "%.2f km", distanceKm));
        timeTextView.setText(String.format(Locale.getDefault(), "%d min %d s", minutes, seconds));
    }
}
