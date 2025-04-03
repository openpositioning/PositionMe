package com.openpositioning.PositionMe.presentation.viewitems;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;
import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.os.FileObserver;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import com.openpositioning.PositionMe.presentation.activity.ReplayActivity;
import com.openpositioning.PositionMe.presentation.fragment.FilesFragment;

import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Adapter used for displaying trajectory metadata in a RecyclerView list.
 * This adapter binds trajectory metadata from the server to individual view items.
 * The download status is indicated via a button with different icons.
 * The adapter also listens for file changes using FileObserver to update the download records in real time.
 * A local set of "downloading" trajectory IDs is maintained to support simultaneous downloads.
 * @see TrajDownloadViewHolder for the corresponding view holder.
 * @see FilesFragment for details on how the data is generated.
 * @see ServerCommunications for where the response items are received.
 */
public class TrajDownloadListAdapter extends RecyclerView.Adapter<TrajDownloadViewHolder> {

    // Date-time formatter used to format date and time.
    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Context context;
    private final List<Map<String, String>> responseItems;
    private final DownloadClickListener listener;

    // FileObserver to monitor modifications to the "download_records.json" file.
    private FileObserver fileObserver;

    // Set to keep track of trajectory IDs that are currently downloading.
    private final Set<String> downloadingTrajIds = new HashSet<>();

    /**
     * Constructor for the adapter.
     *
     * @param context       Application context used for inflating layouts.
     * @param responseItems List of response items from the server.
     * @param listener      Callback listener for handling download click events.
     */
    public TrajDownloadListAdapter(Context context, List<Map<String, String>> responseItems, DownloadClickListener listener) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        this.context = context;
        this.responseItems = responseItems;
        this.listener = listener;
        // Load the local download records.
        loadDownloadRecords();
        // Initialize the FileObserver to listen for changes in the download records file.
        initFileObserver();
    }



    /**
     * Loads the local download records from storage.
     * The records are stored in a JSON file located in the app-specific Downloads directory.
     * After loading, any trajectory IDs that have now finished downloading are removed
     * from the downloading set.
     */
    private void loadDownloadRecords() {
        try {
            File file = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "download_records.json");
            if (file.exists()) {
                // Read the file line by line to reduce memory usage.
                StringBuilder jsonBuilder = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(file), 8192)) { // Increase buffer size
                    String line;
                    while ((line = reader.readLine()) != null) {
                        jsonBuilder.append(line);
                    }
                }

                // Parse the JSON content.
                JSONObject jsonObject = new JSONObject(jsonBuilder.toString());
                ServerCommunications.downloadRecords.clear();

                // Preallocate HashMap capacity to reduce resizing overhead.
                int estimatedSize = jsonObject.length();
                ServerCommunications.downloadRecords = new HashMap<>(estimatedSize * 2);

                // Iterate through keys in the JSON object.
                for (Iterator<String> keys = jsonObject.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    JSONObject recordDetails = jsonObject.getJSONObject(key);
                    // Use the record's "id" if available, otherwise use the key.
                    String id = recordDetails.optString("id", key);
                    ServerCommunications.downloadRecords.put(id, recordDetails);
                }

                System.out.println("Download records loaded: " + ServerCommunications.downloadRecords);

                // Remove any IDs from the downloading set that are now present in the download records.
                // This ensures the "downloading" state is removed when the download completes.
                downloadingTrajIds.removeIf(id -> ServerCommunications.downloadRecords.containsKey(id));

                // Refresh the RecyclerView UI on the main thread.
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
     * Initializes the FileObserver to listen for modifications on the "download_records.json" file.
     * When the file is modified, it reloads the download records and refreshes the UI.
     */
    private void initFileObserver() {
        File downloadsFolder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsFolder == null) {
            return;
        }
        // Create a FileObserver for the directory where the file is located.
        fileObserver = new FileObserver(downloadsFolder.getAbsolutePath(), FileObserver.MODIFY) {
            @Override
            public void onEvent(int event, String path) {
                // Only act if the modified file is "download_records.json".
                if (path != null && path.equals("download_records.json")) {
                    Log.i("FileObserver", "download_records.json has been modified.");
                    // On file modification, load the records and update the UI on the main thread.
                    new Handler(Looper.getMainLooper()).post(() -> {
                        loadDownloadRecords();
                    });
                }
            }
        };
        fileObserver.startWatching();
    }

    /**
     * Creates a new view holder for a trajectory item.
     *
     * @param parent   The parent view group.
     * @param viewType The view type.
     * @return A new instance of TrajDownloadViewHolder.
     */
    @NonNull
    @Override
    public TrajDownloadViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new TrajDownloadViewHolder(LayoutInflater.from(context)
                .inflate(R.layout.item_trajectorycard_view, parent, false), listener);
    }

    /**
     * Binds data to the view holder.
     * Formats and assigns trajectory metadata fields to the corresponding views.
     * The button state is determined as follows:
     * - If the trajectory is present in the download records, it is set as "downloaded".
     * - Else if the trajectory is in the downloading set, it is set as "downloading".
     * - Otherwise, it is set as "not downloaded".
     * @param holder   The view holder to bind data to.
     * @param position The position of the item in the list.
     */
    @Override
    public void onBindViewHolder(@NonNull TrajDownloadViewHolder holder, int position) {
        // Retrieve the trajectory id from the response item.
        String id = responseItems.get(position).get("id");
        holder.getTrajId().setText(id);

        // Adjust text size based on the id length.
        if (id != null && id.length() > 2) {
            holder.getTrajId().setTextSize(58);
        } else {
            holder.getTrajId().setTextSize(65);
        }

        // Parse and format the submission date.
        String dateSubmittedStr = responseItems.get(position).get("date_submitted");
        assert dateSubmittedStr != null;
        holder.getTrajDate().setText(
                dateFormat.format(
                        LocalDateTime.parse(dateSubmittedStr.split("\\.")[0])
                )
        );

        // Determine if the trajectory is already downloaded by checking the records.
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
                // Set the button state to "downloaded".
                setButtonState(holder.downloadButton, 1);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (downloadingTrajIds.contains(id)) {
            // If the item is still being downloaded, set the button state to "downloading".
            setButtonState(holder.downloadButton, 2);
        } else {
            // Otherwise, the item is not downloaded.
            setButtonState(holder.downloadButton, 0);
        }

        // Copy matched status and filePath to final variables for use in the lambda expression.
        final boolean finalMatched = matched;
        final String finalFilePath = filePath;

        // Set the click listener for the download button.
        holder.downloadButton.setOnClickListener(v -> {
            String trajId = responseItems.get(position).get("id");

            if (finalMatched) {
                // If the item is already downloaded, start ReplayActivity to display the trajectory.
                if (finalFilePath != null) {
                    Intent intent = new Intent(context, ReplayActivity.class);
                    intent.putExtra(ReplayActivity.EXTRA_TRAJECTORY_FILE_PATH, finalFilePath);
                    context.startActivity(intent);
                }
            } else {
                // If the item is not downloaded, trigger the download action.
                listener.onPositionClicked(position);
                // Mark the trajectory as downloading.
                downloadingTrajIds.add(trajId);
                // Immediately update the button state to "downloading".
                setButtonState(holder.downloadButton, 2);
                // The FileObserver will update the UI when the file changes.
            }
        });

        holder.downloadButton.invalidate();
    }

    /**
     * Returns the number of items in the response list.
     *
     * @return The size of the responseItems list.
     */
    @Override
    public int getItemCount() {
        return responseItems.size();
    }

    /**
     * Sets the appearance of the button based on its state.
     *
     * @param button The MaterialButton to update.
     * @param state  The state of the button:
     *               0 - Not downloaded,
     *               1 - Downloaded,
     *               2 - Downloading.
     */
    private void setButtonState(MaterialButton button, int state) {
        if (state == 1) {
            button.setIconResource(R.drawable.ic_baseline_play_circle_filled_24);
            button.setIconTintResource(R.color.md_theme_onPrimary);
            button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.md_theme_primary));
        } else if (state == 2) {
            button.setIconResource(R.drawable.baseline_data_usage_24);
            button.setIconTintResource(R.color.md_theme_onPrimary);
            button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.goldYellow));
        } else {
            button.setIconResource(R.drawable.ic_baseline_download_24);
            button.setIconTintResource(R.color.md_theme_onSecondary);
            button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.md_theme_light_primary));
        }
    }
}
