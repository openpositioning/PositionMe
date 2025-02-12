package com.openpositioning.PositionMe.presentation.viewitems;
import java.util.HashMap;
import java.util.Map;


import android.content.Intent;

import org.json.JSONObject;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;

import java.time.format.DateTimeFormatter;
import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;
import java.util.Iterator;


import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;
import com.openpositioning.PositionMe.presentation.fragment.FilesFragment;

import java.time.LocalDateTime;
import java.util.List;


/**
 * Adapter used for displaying Trajectory metadata in a RecyclerView list.
 *
 * @see TrajDownloadViewHolder the corresponding view holder.
 * @see FilesFragment on how the data is generated
 * @see ServerCommunications on where the response items are received.
 */
public class TrajDownloadListAdapter extends RecyclerView.Adapter<TrajDownloadViewHolder> {

    // Date-time formatting object
    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Context context;
    private final List<Map<String, String>> responseItems;
    private final DownloadClickListener listener;
    private final Map<String, Boolean> pollingStatus = new HashMap<>();

    /**
     * Default public constructor with context for inflating views and list to be displayed.
     *
     * @param context       application context to enable inflating views used in the list.
     * @param responseItems List of Maps, where each map is a response item from the server.
     * @param listener      clickListener to download trajectories when clicked.
     */
    public TrajDownloadListAdapter(Context context, List<Map<String, String>> responseItems, DownloadClickListener listener) {
        this.context = context;
        this.responseItems = responseItems;
        this.listener = listener;
        // Load local records
        loadDownloadRecords();
    }

    /**
     * Loads download records from a JSON file and updates the UI if necessary.
     * It reads the JSON file, parses it, and updates the download records in ServerCommunications.
     * If the file size has not changed, it skips the update to avoid unnecessary UI refreshes.
     */
    private void loadDownloadRecords() {
        try {
            File file = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "download_records.json");
            if (file.exists()) {
                // Read line by line to reduce memory usage
                StringBuilder jsonBuilder = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(file), 8192)) { // Increase buffer size
                    String line;
                    while ((line = reader.readLine()) != null) {
                        jsonBuilder.append(line);
                    }
                }

                JSONObject jsonObject = new JSONObject(jsonBuilder.toString());
                ServerCommunications.downloadRecords.clear();

                // Pre-allocate HashMap capacity to reduce resizing overhead
                int estimatedSize = jsonObject.length();
                ServerCommunications.downloadRecords = new HashMap<>(estimatedSize * 2);

                for (Iterator<String> keys = jsonObject.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    JSONObject recordDetails = jsonObject.getJSONObject(key);
                    String id = recordDetails.optString("id", key);
                    ServerCommunications.downloadRecords.put(id, recordDetails);
                }

                System.out.println("Download records loaded: " + ServerCommunications.downloadRecords);

                // Refresh UI only once to avoid frequent redraws
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

        if (id != null && id.length() > 2) {
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

        // Directly use HashMap for O(1) lookup
        JSONObject recordDetails = ServerCommunications.downloadRecords.get(id);
        boolean matched = recordDetails != null;
        String filePath = null;

        if (matched) {
            try {
                String fileName = recordDetails.optString("file_name", null);
                if (fileName != null) {
                    File file = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
                    filePath = file.getAbsolutePath();
                }
                setButtonState(holder.downloadButton, 1); // Downloaded state
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            setButtonState(holder.downloadButton, 0); // Not downloaded state
        }

        // Copy matched and filePath to final variables for lambda use
        final boolean finalMatched = matched;
        final String finalFilePath = filePath;

        // Set button click event, determine behavior based on matched state
        holder.downloadButton.setOnClickListener(v -> {
            String trajId = responseItems.get(position).get("id");

            if (finalMatched) {
                // When in replay state, directly start ReplayActivity
                if (finalFilePath != null) {
                    Intent intent = new Intent(context, ReplayActivity.class);
                    intent.putExtra(ReplayActivity.EXTRA_TRAJECTORY_FILE_PATH, finalFilePath);
                    context.startActivity(intent);
                }
            } else {
                // Original download logic
                listener.onPositionClicked(position);
                startPollingForFileUpdate(holder, trajId); // Independent polling
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

    private void setButtonState(MaterialButton button, int state) {
        if (state == 1) {
            button.setIconResource(R.drawable.ic_baseline_play_circle_filled_24);
            button.setIconTintResource(R.color.md_theme_onPrimary);
            button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.md_theme_primary));
        } else if (state == 2) {
            button.setIconResource(R.drawable.ic_baseline_stop_24);
            button.setIconTintResource(R.color.md_theme_onPrimary);
            button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.md_theme_secondaryFixed_mediumContrast));

        } else {
            button.setIconResource(R.drawable.ic_baseline_download_24);
            button.setIconTintResource(R.color.md_theme_onSecondary);
            button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.md_theme_light_primary));
        }
    }

    /**
     * Starts polling for file updates to check if the download records file has been modified.
     * This method sets up a polling mechanism to periodically check if the download records file has been updated.
     */
    private void startPollingForFileUpdate(TrajDownloadViewHolder holder, String trajId) {
        setButtonState(holder.downloadButton, 2); // Switch to "downloading" state

        // If already polling, return directly
        if (pollingStatus.getOrDefault(trajId, false)) {
            return;
        }
        pollingStatus.put(trajId, true); // Mark as polling

        // Use the app-specific Downloads directory.
        File downloadsFolder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(downloadsFolder, "download_records.json");

        if (!file.exists()) {
            pollingStatus.put(trajId, false);
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
                    setButtonState(holder.downloadButton, 1); // Download complete, switch to "downloaded" state
                    pollingStatus.put(trajId, false); // End current polling
                } else if (attempts < 100) {
                    handler.postDelayed(this, 200); // Continue polling
                } else {
                    setButtonState(holder.downloadButton, 0); // Timeout, revert to not downloaded state
                    pollingStatus.put(trajId, false); // Stop polling
                }
            }
        };

        handler.postDelayed(pollRunnable, 200);
    }
}