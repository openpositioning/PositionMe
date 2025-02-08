package com.openpositioning.PositionMe.viewitems;
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
import com.openpositioning.PositionMe.ServerCommunications;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.openpositioning.PositionMe.R;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;


/**
 * Adapter used for displaying Trajectory metadata in a RecyclerView list.
 *
 * @see TrajDownloadViewHolder the corresponding view holder.
 * @see com.openpositioning.PositionMe.fragments.FilesFragment on how the data is generated
 * @see com.openpositioning.PositionMe.ServerCommunications on where the response items are received.
 *
 * @author Mate Stodulka
 */
public class TrajDownloadListAdapter extends RecyclerView.Adapter<TrajDownloadViewHolder>{

    // Date-time formatting object
    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Context context;
    private final List<Map<String, String>>  responseItems;
    private final DownloadClickListener listener;

    /**
     * Default public constructor with context for inflating views and list to be displayed.
     *
     * @param context       application context to enable inflating views used in the list.
     * @param responseItems List of Maps, where each map is a response item from the server.
     * @param listener      clickListener to download trajectories when clicked.
     *
     * @see com.openpositioning.PositionMe.Traj protobuf objects exchanged with the server.
     */
    public TrajDownloadListAdapter(Context context, List<Map<String, String>> responseItems, DownloadClickListener listener) {
        this.context = context;
        this.responseItems = responseItems;
        this.listener = listener;
        // ✅ 加载本地下载记录
        loadDownloadRecords();
    }

    private void loadDownloadRecords() {
        try {
            File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "download_records.json");
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
     * @see com.openpositioning.PositionMe.R.layout#item_trajectorycard_view xml layout file
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
     * @see com.openpositioning.PositionMe.fragments.FilesFragment generating the data from server response.
     * @see com.openpositioning.PositionMe.R.layout#item_sensorinfo_card_view xml layout file.
     */
    @Override
    public void onBindViewHolder(@NonNull TrajDownloadViewHolder holder, int position) {
        String id = responseItems.get(position).get("id");
        holder.trajId.setText(id);
        if(id.length() > 2) holder.trajId.setTextSize(58);
        else holder.trajId.setTextSize(65);

        String dateSubmittedStr = responseItems.get(position).get("date_submitted");
        holder.trajDate.setText(
                dateFormat.format(
                        LocalDateTime.parse(dateSubmittedStr.split("\\.")[0])
                )
        );

        // ✅ 点击事件
        holder.downloadButton.setOnClickListener(v -> {
            listener.onPositionClicked(position);
            // 启动轮询检测文件更新
            startPollingForFileUpdate();
            System.out.println("📥 点击下载，启动轮询检测文件更新。");
        });

        // ✅ 检查本地下载记录
        boolean matched = false;
        for (Map.Entry<Long, String> entry : ServerCommunications.downloadRecords.entrySet()) {
            try {
                JSONObject recordDetails = new JSONObject(entry.getValue());
                String recordId = recordDetails.getString("id").trim();

                if (recordId.equals(id.trim())) {
                    matched = true;
                    holder.downloadButton.setImageResource(R.drawable.ic_baseline_play_circle_filled_24);
                    holder.downloadButton.setBackgroundColor(Color.GREEN);
                    System.out.println("✅ Matched ID: " + id);
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

    // 在适配器中新增一个方法，轮询检测文件更新时间
    private void startPollingForFileUpdate() {
        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "download_records.json");
        if (!file.exists()) {
            System.out.println("⚠️ 文件不存在，取消轮询。");
            return;
        }
        final long initialModified = file.lastModified();
        final Handler handler = new Handler(Looper.getMainLooper());
        Runnable pollRunnable = new Runnable() {
            int attempts = 0; // 尝试次数
            @Override
            public void run() {
                attempts++;
                if (file.lastModified() > initialModified) {
                    System.out.println("🎉 文件更新成功！尝试次数：" + attempts);
                    loadDownloadRecords();  // 读取新数据并刷新UI
                } else if (attempts < 10) { // 最多轮询 10 次（约2秒）
                    handler.postDelayed(this, 200);
                } else {
                    System.out.println("⏰ 轮询超时，文件更新检测失败。");
                }
            }
        };
        handler.postDelayed(pollRunnable, 200);
    }
}

