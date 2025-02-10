package com.openpositioning.PositionMe.presentation.viewitems;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import org.json.JSONObject;  // ✅ 导入 JSON 处理类
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;

import java.time.format.DateTimeFormatter;  // 确保 DateTimeFormatter 也被导入
import java.io.File;               // ✅ 导入 File 类
import java.io.FileReader;         // ✅ 导入 FileReader 类
import java.io.BufferedReader;     // ✅ 导入 BufferedReader 类
import java.util.Iterator;  // 确保已经导入 Iterator

import com.openpositioning.PositionMe.Traj;
import com.openpositioning.PositionMe.data.remote.ServerCommunications;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
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
        // ✅ 加载本地下载记录
        loadDownloadRecords();
    }
    private long lastFileSize = -1;

    private void loadDownloadRecords() {
        try {
            File file = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "download_records.json");
            if (file.exists()) {
                long currentSize = file.length();
                // 如果文件大小没变，认为内容也没有改变，不刷新
//                if (currentSize == lastFileSize) {
//                    System.out.println("文件大小未变化，不刷新UI。");
//                    return;
//                }
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
                ServerCommunications.downloadRecords.clear();  // ✅ 清空旧数据
                while (keys.hasNext()) {
                    String key = keys.next();
                    ServerCommunications.downloadRecords.put(Long.parseLong(key), jsonObject.getString(key));
                }
                System.out.println("✅ Download records loaded: " + ServerCommunications.downloadRecords);

                // ✅ 刷新 RecyclerView
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

        // ✅ 检查本地下载记录
        boolean matched = false;
        String filePath = null;
        for (Map.Entry<Long, String> entry : ServerCommunications.downloadRecords.entrySet()) {
            try {
                JSONObject recordDetails = new JSONObject(entry.getValue());
                String recordId = recordDetails.getString("id").trim();

                if (recordId.equals(id.trim())) {
                    matched = true;
                    // 获取 file_name 字段
                    String fileName = recordDetails.optString("file_name", null);
                    // 如果 file_name 不为 null，则构造实际的文件路径
                    if (fileName != null) {
                        File file = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName);
                        filePath = file.getAbsolutePath();
                    }
                    holder.downloadButton.setImageResource(R.drawable.ic_baseline_play_circle_filled_24);
                    holder.downloadButton.setBackgroundColor(Color.GREEN);
                    System.out.println("✅ Matched ID: " + id + ", filePath: " + filePath);
                    break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

// ❌ 未匹配时，恢复默认状态
        if (!matched) {
            holder.downloadButton.setImageResource(R.drawable.ic_baseline_download_24);
            holder.downloadButton.setBackgroundResource(R.drawable.rounded_corner_lightblue);
            System.out.println("❌ Not matched ID: " + id);
        }

// 将 matched 和 filePath 复制到 final 变量中供 lambda 使用
        final boolean finalMatched = matched;
        final String finalFilePath = filePath;

// 设置按钮点击事件，根据 matched 状态判断行为
        holder.downloadButton.setOnClickListener(v -> {
            if (finalMatched) {
                // 当为 replay 状态时，直接启动 ReplayActivity
                if (finalFilePath != null) {
                    Intent intent = new Intent(context, ReplayActivity.class);
                    intent.putExtra(ReplayActivity.EXTRA_TRAJECTORY_FILE_PATH, finalFilePath);
                    context.startActivity(intent);
                    System.out.println("▶️ 启动 ReplayActivity，传入文件路径：" + finalFilePath);
                } else {
                    System.out.println("⚠️ replay 状态下未找到文件路径！");
                }
            } else {
                // 原下载逻辑
                listener.onPositionClicked(position);
                // 启动轮询检测文件更新
                startPollingForFileUpdate();
                System.out.println("📥 点击下载，启动轮询检测文件更新。");
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
    public void refreshDownloadRecords() {
        loadDownloadRecords();
    }


    private boolean isPolling = false;

    private void startPollingForFileUpdate() {
        if (isPolling) {
            return;
        }
        isPolling = true;

        // Use the app-specific Downloads directory.
        File downloadsFolder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(downloadsFolder, "download_records.json");

        if (!file.exists()) {
            Log.i("FileUpdate", "⚠️ 文件不存在，取消轮询。");
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
                    Log.i("FileUpdate", "🎉 文件更新成功！尝试次数：" + attempts);
                    loadDownloadRecords();
                    isPolling = false;
                } else if (attempts < 100) {  // Try up to 100 times
                    handler.postDelayed(this, 200);
                } else {
                    Log.i("FileUpdate", "⏰ 轮询超时，文件更新检测失败。");
                    isPolling = false;
                }
            }
        };

        handler.postDelayed(pollRunnable, 200);
    }


}

