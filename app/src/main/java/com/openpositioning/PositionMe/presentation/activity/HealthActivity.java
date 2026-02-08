package com.openpositioning.PositionMe.presentation.activity;

import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

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

        // Mock data
        int distanceMeters = 2400;
        int durationSeconds = 22 * 60; // 22 minutes
        double outdoorRatio = 0.7;

        // Create summary object
        WalkSessionSummary summary = new WalkSessionSummary("mock-session", distanceMeters, durationSeconds, outdoorRatio);

        // Calculate score
        ScoreCalculator calculator = new ScoreCalculator(); // Using default thresholds
        ScoreResult scoreResult = calculator.calculateScore(summary);

        // Find views
        TextView scoreTextView = findViewById(R.id.score_text);
        TextView distanceTextView = findViewById(R.id.distance_text);
        TextView timeTextView = findViewById(R.id.time_text);
        TextView outdoorTextView = findViewById(R.id.outdoor_text);
        TextView feedbackTextView = findViewById(R.id.feedback_text);

        // Populate views with data
        scoreTextView.setText(String.valueOf(scoreResult.getScore0to100()));
        feedbackTextView.setText(scoreResult.getFeedbackText());

        // Format and display metrics
        double distanceKm = distanceMeters / 1000.0;
        int durationMinutes = durationSeconds / 60;
        int outdoorPercentage = (int) (outdoorRatio * 100);

        distanceTextView.setText(String.format(Locale.getDefault(), "%.1f km", distanceKm));
        timeTextView.setText(String.format(Locale.getDefault(), "%d min", durationMinutes));
        outdoorTextView.setText(String.format(Locale.getDefault(), "%d%%", outdoorPercentage));
    }
}
