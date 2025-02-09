package com.openpositioning.PositionMe.viewitems;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

public class TrajDownloadListAdapter extends RecyclerView.Adapter<TrajDownloadViewHolder> {

    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Context context;
    private final List<Map<String, String>> trajectoryList;
    private final DownloadClickListener downloadListener;
    private final ReplayClickListener replayListener;

    // Constructor using external interfaces
    public TrajDownloadListAdapter(Context context, List<Map<String, String>> trajectoryList,
                                   DownloadClickListener downloadListener, ReplayClickListener replayListener) {
        this.context = context;
        this.trajectoryList = trajectoryList;
        this.downloadListener = downloadListener;
        this.replayListener = replayListener;
    }

    @NonNull
    @Override
    public TrajDownloadViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_trajectorycard_view, parent, false);
        // Directly pass external interfaces to ViewHolder
        return new TrajDownloadViewHolder(view, downloadListener, replayListener);
    }

    @Override
    public void onBindViewHolder(@NonNull TrajDownloadViewHolder holder, int position) {
        Map<String, String> trajectory = trajectoryList.get(position);
        String rawId = trajectory.get("id");
        String rawDate = trajectory.get("date_submitted");

        // Display only the last 4 digits of the ID (to prevent UI display issues with long numbers)
        String formattedId = rawId.length() > 4 ? rawId.substring(rawId.length() - 4) : rawId;
        holder.trajId.setText("ID: " + formattedId);

        // Parse the date (assuming the server returns the date in ISO format)
        try {
            LocalDateTime dateTime = LocalDateTime.parse(rawDate, DateTimeFormatter.ISO_DATE_TIME);
            String formattedDate = dateTime.format(dateFormat);
            holder.trajDate.setText("Date: " + formattedDate);
        } catch (DateTimeParseException e) {
            Log.e("TrajDownloadListAdapter", "Date parsing error: " + rawDate, e);
            holder.trajDate.setText("Invalid Date");
        }

        // Set the play button click event: call replayListener.onReplayClick(position)
        holder.playButton.setOnClickListener(v -> {
            if (replayListener != null) {
                replayListener.onReplayClick(position);
            }
        });

        // Set the download button click event: call downloadListener.onDownloadClick(position)
        holder.downloadButton.setOnClickListener(v -> {
            if (downloadListener != null) {
                downloadListener.onPositionClicked(position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return trajectoryList.size();
    }
}