package com.openpositioning.PositionMe.presentation.viewitems;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.fragment.UploadFragment;

import java.io.File;
import java.util.List;

/**
 * Adapter used for displaying local Trajectory file data
 * FINAL VERSION: Correctly parses names by finding the last underscore.
 */
public class UploadListAdapter extends RecyclerView.Adapter<UploadViewHolder> {

    private final Context context;
    private final List<File> uploadItems;
    private final DownloadClickListener listener;

    public UploadListAdapter(Context context, List<File> uploadItems, DownloadClickListener listener) {
        this.context = context;
        this.uploadItems = uploadItems;
        this.listener = listener;
    }

    @NonNull
    @Override
    public UploadViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new UploadViewHolder(LayoutInflater.from(context).inflate(R.layout.item_upload_card_view, parent, false), listener);
    }

    /**
     * Parse filename format: traj_NAME_DATE.txt
     * Extract name (remove "traj_" prefix) and date.
     */
    @Override
    public void onBindViewHolder(@NonNull UploadViewHolder holder, int position) {
        // 1. Get filename (e.g. "traj_MyWalk_2026-02-05.txt")
        File currentFile = uploadItems.get(position);
        String fileName = currentFile.getName();

        // 2. Remove ".txt" suffix
        if (fileName.endsWith(".txt")) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }

        // 3. Find last "_" to split name and date
        int lastUnderscoreIndex = fileName.lastIndexOf("_");

        if (lastUnderscoreIndex != -1) {
            // Extract date
            String datePart = fileName.substring(lastUnderscoreIndex + 1);
            holder.trajDate.setText(datePart);

            // Extract name
            String namePart = fileName.substring(0, lastUnderscoreIndex);

            // Remove "traj_" prefix if exists
            if (namePart.startsWith("traj_")) {
                namePart = namePart.substring(5);
            }

            // Display name or "Unnamed" if empty
            if (namePart.isEmpty()) {
                holder.trajId.setText("Unnamed");
            } else {
                // Display actual name (e.g. "MyWalk")
                // For legacy files like "traj_trajectory_DATE"
                holder.trajId.setText(namePart);
            }
            holder.trajId.setTextSize(20);

        } else {
            // Edge case: filename without underscore
            holder.trajDate.setText("N/A");
            holder.trajId.setText(fileName);
            holder.trajId.setTextSize(14);
        }

        // Set up delete button
        holder.deletebutton.setOnClickListener(v -> deleteFileAtPosition(position));
    }

    @Override
    public int getItemCount() {
        return uploadItems.size();
    }

    private void deleteFileAtPosition(int position) {
        if (position >= 0 && position < uploadItems.size()) {
            File fileToDelete = uploadItems.get(position);

            if (fileToDelete.exists() && fileToDelete.delete()) {
                uploadItems.remove(position);
                notifyItemRemoved(position);
                notifyItemRangeChanged(position, uploadItems.size());
                Toast.makeText(context, "File deleted successfully", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "Failed to delete file", Toast.LENGTH_SHORT).show();
            }
        }
    }
}