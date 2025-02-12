package com.openpositioning.PositionMe.presentation.viewitems;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Paths;

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
import java.util.HashMap;
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
     *
     * @see Traj protobuf objects exchanged with the server.
     */
    public TrajDownloadListAdapter(Context context, List<Map<String, String>> responseItems, DownloadClickListener listener) {
        this.context = context;
        this.responseItems = responseItems;
        this.listener = listener;
        loadDownloadRecords();
    }

    /**
     * Load the download records from the local storage.
     */
    private void loadDownloadRecords() {
        try {
            File file = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "download_records.json");
            if (file.exists()) {
                // ✅ 逐行读取，减少内存占用
                StringBuilder jsonBuilder = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new FileReader(file), 8192)) { // 增加缓冲区大小
                    String line;
                    while ((line = reader.readLine()) != null) {
                        jsonBuilder.append(line);
                    }
                }

                JSONObject jsonObject = new JSONObject(jsonBuilder.toString());
                ServerCommunications.downloadRecords.clear();

                // ✅ 预分配 HashMap 容量，减少扩容开销
                int estimatedSize = jsonObject.length();
                ServerCommunications.downloadRecords = new HashMap<>(estimatedSize * 2);

                for (Iterator<String> keys = jsonObject.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    JSONObject recordDetails = jsonObject.getJSONObject(key);
                    String id = recordDetails.optString("id", key);
                    ServerCommunications.downloadRecords.put(id, recordDetails);
                }

                System.out.println("✅ Download records loaded: " + ServerCommunications.downloadRecords);

                // ✅ 仅刷新一次 UI，避免频繁重绘
                new Handler(Looper.getMainLooper()).post(() -> {
                    notifyDataSetChanged();
                    System.out.println("🔄 RecyclerView fully refreshed after loading records.");
                });
            } else {
                System.out.println("⚠️ Download records file not found.");
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

        // ✅ 直接使用 HashMap 进行 O(1) 查找
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
                setButtonState(holder.downloadButton, 1); // 已下载状态
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            setButtonState(holder.downloadButton, 0); // 未下载状态
        }

        final boolean finalMatched = matched;
        final String finalFilePath = filePath;

        holder.downloadButton.setOnClickListener(v -> {
            String trajId = responseItems.get(position).get("id");

            if (finalMatched) {
                if (finalFilePath != null) {
                    Intent intent = new Intent(context, ReplayActivity.class);
                    intent.putExtra(ReplayActivity.EXTRA_TRAJECTORY_FILE_PATH, finalFilePath);
                    context.startActivity(intent);
                }
            } else {
                listener.onPositionClicked(position);
                startPollingForFileUpdate(holder, trajId); // 独立轮询
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

    private void startPollingForFileUpdate(TrajDownloadViewHolder holder, String trajId) {
        setButtonState(holder.downloadButton, 2); // 切换为“下载中”状态

        // 如果已经在轮询，直接返回
        if (pollingStatus.getOrDefault(trajId, false)) {
            return;
        }
        pollingStatus.put(trajId, true); // 标记为正在轮询

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
                    loadDownloadRecords();
                    setButtonState(holder.downloadButton, 1); // 下载完成切换为“已下载”状态
                    pollingStatus.put(trajId, false); // 结束当前轮询
                } else if (attempts < 100) {
                    handler.postDelayed(this, 200); // 继续轮询
                } else {
                    setButtonState(holder.downloadButton, 0); // 超时恢复为未下载状态
                    pollingStatus.put(trajId, false); // 停止轮询
                }
            }
        };

        handler.postDelayed(pollRunnable, 200);
    }
}