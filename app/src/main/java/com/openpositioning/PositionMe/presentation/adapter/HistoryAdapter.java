package com.openpositioning.PositionMe.presentation.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.health.ScoreCalculator;
import com.openpositioning.PositionMe.health.WalkSessionSummary;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for the RecyclerView in the HistoryActivity.
 * Binds a list of WalkSessionSummary objects to the list_item_walk_history layout.
 */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

    private final List<WalkSessionSummary> walkSessions;
    private final ScoreCalculator scoreCalculator;

    public HistoryAdapter(List<WalkSessionSummary> walkSessions) {
        this.walkSessions = walkSessions;
        // We need a score calculator to compute the score for each session in the list.
        this.scoreCalculator = new ScoreCalculator();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context context = parent.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        View historyView = inflater.inflate(R.layout.list_item_walk_history, parent, false);
        return new ViewHolder(historyView);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        // Get the data model based on position
        WalkSessionSummary session = walkSessions.get(position);

        // --- Calculate Score ---
        int score = scoreCalculator.calculateScore(session).getScore0to100();

        // --- Format Data for Display ---
        // Date
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault());
        String dateString = dateFormat.format(new Date(session.getTimestampMillis()));

        // Details (Distance and Time)
        double distanceKm = session.getDistanceMeters() / 1000.0;
        int durationMinutes = session.getDurationSeconds() / 60;
        String detailsString = String.format(Locale.getDefault(), "%.1f km  |  %d min", distanceKm, durationMinutes);

        // --- Set Views ---
        holder.dateTextView.setText(dateString);
        holder.scoreTextView.setText(String.valueOf(score));
        holder.detailsTextView.setText(detailsString);
    }

    @Override
    public int getItemCount() {
        return walkSessions.size();
    }

    /**
     * ViewHolder class to hold the views for each item in the RecyclerView.
     */
    public static class ViewHolder extends RecyclerView.ViewHolder {
        public TextView dateTextView;
        public TextView scoreTextView;
        public TextView detailsTextView;

        public ViewHolder(View itemView) {
            super(itemView);
            dateTextView = itemView.findViewById(R.id.history_date_text);
            scoreTextView = itemView.findViewById(R.id.history_score_text);
            detailsTextView = itemView.findViewById(R.id.history_details_text);
        }
    }
}
