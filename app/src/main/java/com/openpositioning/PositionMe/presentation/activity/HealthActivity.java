package com.openpositioning.PositionMe.presentation.activity;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.health.ScoreCalculator;
import com.openpositioning.PositionMe.health.ScoreResult;
import com.openpositioning.PositionMe.health.WalkSessionSummary;
import com.openpositioning.PositionMe.utils.WalkFileParser;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class HealthActivity extends AppCompatActivity {

    private static final String TAG = HealthActivity.class.getSimpleName();

    private TextInputEditText distanceGoalInput, durationGoalInput;
    private TextView scoreTextView, feedbackTextView, distanceTextView, timeTextView;
    private ProgressBar scoreProgressBar;

    private WalkSessionSummary currentWalkSummary;

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
        Button historyButton = findViewById(R.id.history_button);

        currentWalkSummary = loadLatestWalk();

        // --- Initial Calculation ---
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

        // --- CTA Button: Launch History Activity ---
        historyButton.setOnClickListener(v -> {
            Intent intent = new Intent(HealthActivity.this, HistoryActivity.class);
            startActivity(intent);
        });
    }

    /**
     * Scans the app's storage for trajectory files, parses them, and returns the most recent summary.
     */
    private WalkSessionSummary loadLatestWalk() {
        List<WalkSessionSummary> sessions = new ArrayList<>();
        WalkFileParser parser = new WalkFileParser(getApplicationContext());
        File storageDir = new File(getExternalFilesDir(null), "trajectories");

        if (!storageDir.exists()) {
            Log.w(TAG, "Trajectories directory does not exist: " + storageDir.getAbsolutePath());
            return null; // No walks found
        }

        File[] files = storageDir.listFiles();
        if (files == null) {
            Log.w(TAG, "Failed to list files in directory: " + storageDir.getAbsolutePath());
            return null;
        }

        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".csv")) {
                WalkSessionSummary summary = parser.parseFile(file);
                if (summary != null) {
                    sessions.add(summary);
                }
            }
        }

        if (sessions.isEmpty()) {
            return null; // No walks found
        }

        // Sort by most recent first
        Collections.sort(sessions, (s1, s2) -> Long.compare(s2.getTimestampMillis(), s1.getTimestampMillis()));
        return sessions.get(0);
    }

    /**
     * Reads the goals from the input fields and recalculates the score.
     */
    private void updateScore() {
        if (currentWalkSummary == null) {
            scoreTextView.setText("—");
            scoreProgressBar.setProgress(0);
            feedbackTextView.setText("No walks recorded yet.");
            return;
        }

        try {
            // 1. Get User Goals from EditTexts
            double distanceGoalKm = Double.parseDouble(distanceGoalInput.getText().toString());
            double durationGoalMin = Double.parseDouble(durationGoalInput.getText().toString());

            // Convert to meters and seconds for the calculator
            double distanceGoalM = distanceGoalKm * 1000;
            double durationGoalS = durationGoalMin * 60;

            // 2. Use the constructor that accepts custom goals.
            ScoreCalculator calculator = new ScoreCalculator(distanceGoalM, durationGoalS);
            ScoreResult scoreResult = calculator.calculateScore(currentWalkSummary);

            // 3. Update UI
            int score = scoreResult.getScore0to100();
            scoreTextView.setText(String.valueOf(score));
            scoreProgressBar.setProgress(score);
            feedbackTextView.setText(scoreResult.getFeedbackText());

        } catch (NumberFormatException e) {
            // Handle cases where the user enters empty or invalid text
            Log.w(TAG, "Invalid number format in goal input.", e);
            scoreTextView.setText("—");
            scoreProgressBar.setProgress(0);
            feedbackTextView.setText("Please enter valid goals.");
        }
    }

    /**
     * Displays the metrics of the current walk session.
     */
    private void displaySessionMetrics() {
        if (currentWalkSummary == null) {
            distanceTextView.setText("—");
            timeTextView.setText("—");
            return;
        }

        double distanceKm = currentWalkSummary.getDistanceMeters() / 1000.0;
        int durationMinutes = currentWalkSummary.getDurationSeconds() / 60;

        distanceTextView.setText(String.format(Locale.getDefault(), "%.1f km", distanceKm));
        timeTextView.setText(String.format(Locale.getDefault(), "%d min", durationMinutes));
    }
}
