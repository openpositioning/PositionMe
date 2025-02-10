package com.openpositioning.PositionMe.presentation.viewitems;

import android.content.Intent;
import android.graphics.Color;
import org.json.JSONObject;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;

import java.time.format.DateTimeFormatter;
import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;
import java.util.Iterator;

import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;
import com.openpositioning.PositionMe.presentation.fragment.FilesFragment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Adapter used for displaying Trajectory metadata in a RecyclerView list.
 *
 * @see TrajDownloadViewHolder the corresponding view holder.
 * @see FilesFragment on how the data is generated
 * @see ServerCommunications on where the response items are received.
 *
 * @author Mate Stodulka
 */
public class TrajDownloadListAdapter extends RecyclerView.Adapter<TrajDownloadViewHolder> {

    // Date-time formatting object
    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Context context;
    private final List<Map<String, String>> responseItems;
    private final DownloadClickListener listener;

    /**
     * Default public constructor with context for inflating views and list to be displayed.
     *
     * @param context       application context to enable inflating views used in the list.
     * @param responseItems List of Maps, where each map is a response item from the server.
     * @param listener      clickListener to download trajectories when clicked.
     *
     * @see Traj protobuf objects exchanged with the server.
     */
    public TrajDownloadListAdapter(Context context, List<Map<String, String>> responseItems, DownloadClickListener listener) {
        this.context = context;
        this.responseItems = responseItems;
        this.listener = listener;
        // Load local download records
        loadDownloadRecords();
    }
    private long lastFileSize = -1;

    /**
     * Loads download records from a JSON file and updates the UI if necessary.
     * It reads the JSON file, parses it, and updates the download records in ServerCommunications.
     * If the file size has not changed, it skips the update to avoid unnecessary UI refreshes.
     */
    private void loadDownloadRecords() {
        try {
            File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "download_records.json");
            if (file.exists()) {
                long currentSize = file.length();
                // If the file size has not changed, assume the content has not changed and do not refresh
                if (currentSize == lastFileSize) {
                    System.out.println("File size has not changed, not refreshing UI.");
                    return;
                }
                lastFileSize = currentSize;

                StringBuilder jsonBuilder = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        jsonBuilder.append(line);
                    }
                }
                JSONObject jsonObject = new JSONObject(jsonBuilder.toString());
                Iterator<String> keys = jsonObject.keys();
                ServerCommunications.downloadRecords.clear();
                while (keys.hasNext()) {
                    String key = keys.next();
                    ServerCommunications.downloadRecords.put(Long.parseLong(key), jsonObject.getString(key));
                }
                System.out.println("Download records loaded: " + ServerCommunications.downloadRecords);

                // Refresh RecyclerView
                new Handler(Looper.getMainLooper()).post(() -> {
                    notifyDataSetChanged();
                    System.out.println("RecyclerView fully refreshed after loading records.");
                });
            } else {
                System.out.println("Download records file not found.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @see R.layout#item_trajectorycard_view xml layout file
     */
    @NonNull
    @Override
    public TrajDownloadViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new TrajDownloadViewHolder(LayoutInflater.from(context)
                .inflate(R.layout.item_trajectorycard_view, parent, false), listener);
    }

    /**
     * {@inheritDoc}
     * Formats and assigns the data fields from the Trajectory metadata object to the TextView fields.
     *
     * @see FilesFragment generating the data from server response.
     * @see R.layout#item_sensorinfo_card_view xml layout file.
     */
    @Override
    public void onBindViewHolder(@NonNull TrajDownloadViewHolder holder, int position) {
        String id = responseItems.get(position).get("id");
        holder.getTrajId().setText(id);
        assert id != null;
        if (id.length() > 2) {
            holder.getTrajId().setTextSize(58);
        } else {
            holder.getTrajId().setTextSize(65);
        }

        String dateSubmittedStr = responseItems.get(position).get("date_submitted");
        assert dateSubmittedStr != null;
        holder.getTrajDate().setText(
                dateFormat.format(
                        LocalDateTime.parse(dateSubmittedStr.split("\\.")[0])
                )
        );

        // Check local download records
        boolean matched = false;
        String filePath = null;
        for (Map.Entry<Long, String> entry : ServerCommunications.downloadRecords.entrySet()) {
            try {
                JSONObject recordDetails = new JSONObject(entry.getValue());
                String recordId = recordDetails.getString("id").trim();

                if (recordId.equals(id.trim())) {
                    matched = true;
                    // Get the file_name field
                    String fileName = recordDetails.optString("file_name", null);
                    // If file_name is not null, construct the actual file path
                    if (fileName != null) {
                        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName);
                        filePath = file.getAbsolutePath();
                    }
                    holder.downloadButton.setImageResource(R.drawable.ic_baseline_play_circle_filled_24);
                    holder.downloadButton.setBackgroundResource(R.drawable.rounded_corner);
                    System.out.println("Matched ID: " + id + ", filePath: " + filePath);
                    break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Restore default state if not matched
        if (!matched) {
            holder.downloadButton.setImageResource(R.drawable.ic_baseline_download_24);
            holder.downloadButton.setBackgroundResource(R.drawable.rounded_corner_lightblue);
            System.out.println("Not matched ID: " + id);
        }

        // Copy matched and filePath to final variables for use in lambda
        final boolean finalMatched = matched;
        final String finalFilePath = filePath;

        // Set button click event, determine behavior based on matched state
        holder.downloadButton.setOnClickListener(v -> {
            if (finalMatched) {
                // When in replay state, directly start ReplayActivity
                if (finalFilePath != null) {
                    Intent intent = new Intent(context, ReplayActivity.class);
                    intent.putExtra(ReplayActivity.EXTRA_TRAJECTORY_FILE_PATH, finalFilePath);
                    context.startActivity(intent);
                    System.out.println("Starting ReplayActivity with file path: " + finalFilePath);
                } else {
                    System.out.println("File path not found in replay state!");
                }
            } else {
                // Original download logic
                listener.onPositionClicked(position);
                // Start polling for file update
                startPollingForFileUpdate();
                System.out.println("Clicked download, starting polling for file update.");
            }
        });

        holder.downloadButton.invalidate();
    }

        /**
         * {@inheritDoc}
         * Number of response maps.
         */
    @Override
    public int getItemCount() {
        return responseItems.size();
    }

    private boolean isPolling = false;

    /**
     * Starts polling for file updates to check if the download records file has been modified.
     * This method sets up a polling mechanism to periodically check if the download records file has been updated.
     */
    private void startPollingForFileUpdate() {
        if (isPolling) {
            return;
        }
        isPolling = true;

        // Get public download directory
        // Note: Environment.getExternalStoragePublicDirectory() has been deprecated since API 29, but can still be used in Android 13
        File downloadsFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(downloadsFolder, "download_records.json");

        if (!file.exists()) {
            Log.i("FileUpdate", "File does not exist, canceling polling.");
            isPolling = false;
            return;
        }

        final long initialModified = file.lastModified();
        final Handler handler = new Handler(Looper.getMainLooper());

        Runnable pollRunnable = new Runnable() {
            int attempts = 0;

            @Override
            public void run() {
                attempts++;
                if (file.lastModified() > initialModified) {
                    Log.i("FileUpdate", "File updated successfully! Attempts: " + attempts);
                    loadDownloadRecords();
                    isPolling = false;
                } else if (attempts < 100) {  // Stop after 100 attempts
                    handler.postDelayed(this, 200);
                } else {
                    Log.i("FileUpdate", "Polling timeout, file update check failed.");
                    isPolling = false;
                }
            }
        };

        handler.postDelayed(pollRunnable, 200);
    }
}