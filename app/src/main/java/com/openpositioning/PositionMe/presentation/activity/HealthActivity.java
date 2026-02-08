package com.openpositioning.PositionMe.presentation.activity;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.health.ScoreCalculator;
import com.openpositioning.PositionMe.health.ScoreResult;
import com.openpositioning.PositionMe.health.WalkSessionSummary;

import java.util.Locale;

public class HealthActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_health);

        // --- Toolbar Setup ---
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        // --- Mock Data ---
        int distanceMeters = 2400;
        int durationSeconds = 22 * 60; // 22 minutes
        double outdoorRatio = 0.7;

        // --- Calculation ---
        WalkSessionSummary summary = new WalkSessionSummary("mock-session", distanceMeters, durationSeconds, outdoorRatio);
        ScoreCalculator calculator = new ScoreCalculator(); // Using default thresholds
        ScoreResult scoreResult = calculator.calculateScore(summary);

        // --- View Binding ---
        TextView scoreTextView = findViewById(R.id.score_text);
        ProgressBar scoreProgressBar = findViewById(R.id.score_progress_bar);
        TextView distanceTextView = findViewById(R.id.distance_text);
        TextView timeTextView = findViewById(R.id.time_text);
        TextView outdoorTextView = findViewById(R.id.outdoor_text);
        TextView feedbackTextView = findViewById(R.id.feedback_text);
        Button historyButton = findViewById(R.id.history_button);

        // --- UI Population ---
        int score = scoreResult.getScore0to100();
        scoreTextView.setText(String.valueOf(score));
        scoreProgressBar.setProgress(score);
        feedbackTextView.setText(scoreResult.getFeedbackText());

        // Format and display metrics
        double distanceKm = distanceMeters / 1000.0;
        int durationMinutes = durationSeconds / 60;
        int outdoorPercentage = (int) (outdoorRatio * 100);

        distanceTextView.setText(String.format(Locale.getDefault(), "%.1f km", distanceKm));
        timeTextView.setText(String.format(Locale.getDefault(), "%d min", durationMinutes));
        outdoorTextView.setText(String.format(Locale.getDefault(), "%d%%", outdoorPercentage));

        // --- CTA Button ---
        historyButton.setOnClickListener(v -> {
            Toast.makeText(HealthActivity.this, "Coming soon", Toast.LENGTH_SHORT).show();
        });
    }
}
