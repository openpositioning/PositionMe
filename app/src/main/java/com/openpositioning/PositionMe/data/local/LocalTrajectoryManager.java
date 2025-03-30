package com.openpositioning.PositionMe.data.local;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 管理本地轨迹文件的工具类
 * 提供方法查询保存在应用中的轨迹文件
 */
public class LocalTrajectoryManager {
    private static final String TAG = "LocalTrajectoryManager";
    private static final String SAVED_TRAJECTORIES_DIR = "saved_trajectories";

    /**
     * 获取保存轨迹文件的目录
     * @param context 应用上下文
     * @return 轨迹文件目录
     */
    public static File getSavedTrajectoriesDirectory(Context context) {
        File trajectoriesDir = new File(context.getExternalFilesDir(null), SAVED_TRAJECTORIES_DIR);
        if (!trajectoriesDir.exists()) {
            boolean created = trajectoriesDir.mkdirs();
            if (!created) {
                Log.e(TAG, "Failed to create saved trajectories directory");
            }
        }
        return trajectoriesDir;
    }

    /**
     * 获取所有本地保存的轨迹文件
     * @param context 应用上下文
     * @return 轨迹文件列表，按日期从新到旧排序
     */
    public static List<File> getAllLocalTrajectories(Context context) {
        File trajectoriesDir = getSavedTrajectoriesDirectory(context);
        if (!trajectoriesDir.exists()) {
            return new ArrayList<>();
        }

        File[] files = trajectoriesDir.listFiles((dir, name) -> name.startsWith("trajectory_") && 
                                                               (name.endsWith(".txt") || name.endsWith(".json")));
        if (files == null || files.length == 0) {
            return new ArrayList<>();
        }

        List<File> trajectories = Arrays.asList(files);
        // 按修改时间倒序排序，最新的轨迹排在前面
        Collections.sort(trajectories, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        return trajectories;
    }

    /**
     * 通过文件名获取轨迹文件
     * @param context 应用上下文
     * @param fileName 文件名
     * @return 轨迹文件，如果不存在则返回null
     */
    public static File getTrajectoryByFileName(Context context, String fileName) {
        File trajectoriesDir = getSavedTrajectoriesDirectory(context);
        File file = new File(trajectoriesDir, fileName);
        return file.exists() ? file : null;
    }

    /**
     * 从文件名中提取日期时间信息
     * @param fileName 文件名，格式应为trajectory_dd-MM-yy-HH-mm-ss.txt或trajectory_dd-MM-yy-HH-mm-ss.json
     * @return 格式化的日期时间字符串，如果无法解析则返回文件名
     */
    public static String getDateTimeFromFileName(String fileName) {
        try {
            // 确定文件扩展名的长度（.txt或.json）
            int extensionLength = fileName.endsWith(".json") ? 5 : 4;
            // 文件名格式: trajectory_dd-MM-yy-HH-mm-ss.txt或trajectory_dd-MM-yy-HH-mm-ss.json
            String dateTimePart = fileName.substring(11, fileName.length() - extensionLength);
            String[] parts = dateTimePart.split("-");
            if (parts.length == 6) {
                return String.format("%s/%s/%s %s:%s:%s", 
                    parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing date time from file name: " + fileName, e);
        }
        return fileName;
    }
} 