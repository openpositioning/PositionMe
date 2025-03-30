package com.openpositioning.PositionMe.data.local;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 简化版轨迹解析器，只处理融合后的位置数据和时间戳。
 */
public class TrajParser {

    private static final String TAG = "TrajParser";

    /**
     * 代表一个回放点，包含位置和时间戳
     */
    public static class ReplayPoint {
        public LatLng location;    // 融合后的位置
        public long timestamp;      // 相对时间戳
        public float orientation;   // 方向（度）

        /**
         * 构造一个回放点
         */
        public ReplayPoint(LatLng location, float orientation, long timestamp) {
            this.location = location;
            this.orientation = orientation;
            this.timestamp = timestamp;
        }
    }

    /**
     * 从JSON文件解析轨迹数据并重构回放点列表
     *
     * @param filePath  轨迹JSON文件路径
     * @param context   Android上下文（可能未使用）
     * @param defaultLat 默认初始纬度（如果文件中没有数据）
     * @param defaultLng 默认初始经度（如果文件中没有数据）
     * @return 解析出的ReplayPoint对象列表
     */
    public static List<ReplayPoint> parseTrajectoryData(String filePath, Context context,
                                                        double defaultLat, double defaultLng) {
        List<ReplayPoint> result = new ArrayList<>();

        try {
            // 检查文件是否存在和可读
            File file = new File(filePath);
            if (!file.exists()) {
                Log.e(TAG, "文件不存在: " + filePath);
                return result;
            }
            if (!file.canRead()) {
                Log.e(TAG, "文件不可读: " + filePath);
                return result;
            }

            // 读取并解析JSON文件
            BufferedReader br = new BufferedReader(new FileReader(file));
            JsonObject root = new JsonParser().parse(br).getAsJsonObject();
            br.close();

            Log.i(TAG, "成功读取轨迹文件: " + filePath);
            
            // 读取融合位置数据
            JsonArray fusionLocations = root.getAsJsonArray("fusionLocations");
            if (fusionLocations == null || fusionLocations.size() == 0) {
                Log.e(TAG, "文件中没有融合位置数据");
                return result;
            }
            
            Log.i(TAG, "获取到 " + fusionLocations.size() + " 个融合位置点");
            
            // 解析每个位置点
            Gson gson = new Gson();
            for (int i = 0; i < fusionLocations.size(); i++) {
                JsonObject point = fusionLocations.get(i).getAsJsonObject();
                long timestamp = point.get("timestamp").getAsLong();
                double latitude = point.get("latitude").getAsDouble();
                double longitude = point.get("longitude").getAsDouble();
                float orientation = point.has("orientation") ? 
                    point.get("orientation").getAsFloat() : 0f;
                
                LatLng location = new LatLng(latitude, longitude);
                result.add(new ReplayPoint(location, orientation, timestamp));
            }
            
            // 按时间戳排序
            Collections.sort(result, Comparator.comparingLong(rp -> rp.timestamp));
            
            Log.i(TAG, "最终解析的位置点数量: " + result.size());

        } catch (Exception e) {
            Log.e(TAG, "解析轨迹文件出错!", e);
        }

        return result;
    }
}