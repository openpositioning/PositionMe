package com.openpositioning.PositionMe.presentation.viewitems;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.openpositioning.PositionMe.R;
import com.openpositioning.PositionMe.data.local.LocalTrajectoryManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for displaying local trajectory files in a RecyclerView
 */
public class LocalTrajectoryAdapter extends RecyclerView.Adapter<LocalTrajectoryViewHolder> {

    private final Context context;
    private final List<File> trajectories;
    private final LocalTrajectoryViewHolder.LocalTrajectoryClickListener listener;

    /**
     * Constructor for LocalTrajectoryAdapter
     * @param context app context
     * @param trajectories list of trajectory files
     * @param listener click listener for trajectory items
     */
    public LocalTrajectoryAdapter(Context context, List<File> trajectories, 
                                 LocalTrajectoryViewHolder.LocalTrajectoryClickListener listener) {
        this.context = context;
        this.trajectories = trajectories;
        this.listener = listener;
    }

    @NonNull
    @Override
    public LocalTrajectoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_local_trajectory, parent, false);
        return new LocalTrajectoryViewHolder(view, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull LocalTrajectoryViewHolder holder, int position) {
        File trajectory = trajectories.get(position);
        
        // 设置文件名，移除轨迹文件名前缀
        String fileName = trajectory.getName();
        if (fileName.startsWith("trajectory_") && fileName.length() > 11) {
            fileName = fileName.substring(11);
        }
        if (fileName.endsWith(".txt")) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }
        holder.name.setText(fileName);
        
        // 从文件名或最后修改时间提取日期信息
        String dateTime = LocalTrajectoryManager.getDateTimeFromFileName(trajectory.getName());
        if (dateTime.equals(trajectory.getName())) {
            // 如果无法从文件名提取日期，使用文件的最后修改时间
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault());
            dateTime = sdf.format(new Date(trajectory.lastModified()));
        }
        holder.date.setText(dateTime);
    }

    @Override
    public int getItemCount() {
        return trajectories.size();
    }
} 