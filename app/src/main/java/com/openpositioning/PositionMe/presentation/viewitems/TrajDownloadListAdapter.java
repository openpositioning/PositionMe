package com.openpositioning.PositionMe.presentation.viewitems;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
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

    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Context context;
    private final List<Map<String, String>> responseItems;
    private final DownloadClickListener listener;
    private long lastFileSize = -1;

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
        loadDownloadRecords();
    }

    private void loadDownloadRecords() {
        try {
            File file = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "download_records.json");
            System.out.println("laigan File exists: " + file.exists() + ", Size: " + file.length());

            if (file.exists()) {
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
                    System.out.println("laigan Processing key: " + key);

                    try {
                        JSONObject recordDetails = jsonObject.getJSONObject(key);

                        // 检查 id 是否存在，如果不存在则使用 key 作为 id
                        String id = recordDetails.has("id") ? recordDetails.getString("id") : key;

                        // 保存到 downloadRecords
                        ServerCommunications.downloadRecords.put(id, recordDetails);
                        System.out.println("laigan Added record with id: " + id);
                    } catch (Exception e) {
                        System.err.println("laigan Error processing key: " + key);
                        e.printStackTrace();
                    }
                }

                // 刷新 UI（在遍历完成后调用）
                new Handler(Looper.getMainLooper()).post(this::notifyDataSetChanged);
                System.out.println("laigan Finished loading download records."+ServerCommunications.downloadRecords);
            }
        } catch (Exception e) {
            System.err.println("laigan Error loading download records:");
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

        boolean matched = false;
        String filePath = null;
        for (Map.Entry<String, JSONObject> entry : ServerCommunications.downloadRecords.entrySet()) {
            try {
                JSONObject recordDetails = new JSONObject(entry.getValue().toString());
                String recordId = recordDetails.getString("id").trim();

                if (recordId.equals(id.trim())) {
                    matched = true;
                    String fileName = recordDetails.optString("file_name", null);
                    if (fileName != null) {
                        File file = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
                        filePath = file.getAbsolutePath();
                    }
                    setButtonState(holder.downloadButton, true);
                    break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (!matched) {
            setButtonState(holder.downloadButton, false);
        }

        final boolean finalMatched = matched;
        final String finalFilePath = filePath;

        holder.downloadButton.setOnClickListener(v -> {
            if (finalMatched) {
                if (finalFilePath != null) {
                    Intent intent = new Intent(context, ReplayActivity.class);
                    intent.putExtra(ReplayActivity.EXTRA_TRAJECTORY_FILE_PATH, finalFilePath);
                    context.startActivity(intent);
                }
            } else {
                listener.onPositionClicked(position);
                startPollingForFileUpdate();
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


    private void setButtonState(MaterialButton button, boolean isMatched) {
        if (isMatched) {
            button.setIconResource(R.drawable.ic_baseline_play_circle_filled_24);
            button.setIconTintResource(R.color.md_theme_onPrimary);
            button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.md_theme_primary));
        } else {
            button.setIconResource(R.drawable.ic_baseline_download_24);
            button.setIconTintResource(R.color.md_theme_onSecondary);
            button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.md_theme_light_primary));
        }
    }

    private boolean isPolling = false;

    private void startPollingForFileUpdate() {
        if (isPolling) {
            return;
        }
        isPolling = true;

        File downloadsFolder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(downloadsFolder, "download_records.json");

        if (!file.exists()) {
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
                    loadDownloadRecords();
                    isPolling = false;
                } else if (attempts < 100) {
                    handler.postDelayed(this, 200);
                } else {
                    isPolling = false;
                }
            }
        };

        handler.postDelayed(pollRunnable, 200);
    }
}
