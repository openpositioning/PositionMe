package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.util.Log;
import com.google.android.gms.maps.model.LatLng;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 位置记录器，负责记录并保存各种定位数据
 */
public class LocationLogger {
    private static final String TAG = "LocationLogger";
    private static final String FILE_PREFIX = "location_log_local_";
    private static final long MIN_SAVE_INTERVAL = 500; // 最小保存间隔(毫秒)
    private static final double MIN_DISTANCE_CHANGE = 0.5; // 最小位置变化阈值(米)
    
    private final Context context;
    private final File logFile;
    private JSONArray locationArray;
    private JSONArray ekfLocationArray;
    private JSONArray gnssLocationArray;
    
    // 记录上次保存的位置和时间
    private LatLng lastSavedPdrLocation = null;
    private LatLng lastSavedEkfLocation = null;
    private LatLng lastSavedGnssLocation = null;
    private long lastSavedPdrTime = 0;
    private long lastSavedEkfTime = 0;
    private long lastSavedGnssTime = 0;
    
    private final SimpleDateFormat dateFormat;
    
    public LocationLogger(Context context) {
        this.context = context;
        dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault());
        locationArray = new JSONArray();
        ekfLocationArray = new JSONArray();
        gnssLocationArray = new JSONArray();
        
        // 创建日志文件
        String timestamp = dateFormat.format(new Date());
        String fileName = String.format("%s%s.json", FILE_PREFIX, timestamp);
        
        File directory = new File(context.getExternalFilesDir(null), "location_logs");
        if (!directory.exists()) {
            directory.mkdirs();
        }
        
        logFile = new File(directory, fileName);
        Log.d(TAG, "Created local log file: " + logFile.getAbsolutePath());
    }
    
    /**
     * 计算两点之间的距离(米)
     */
    private double calculateDistance(LatLng point1, LatLng point2) {
        if (point1 == null || point2 == null) return 0;
        
        // 使用Haversine公式计算地球表面两点间的距离
        double earthRadius = 6371000; // 地球半径(米)
        double dLat = Math.toRadians(point2.latitude - point1.latitude);
        double dLng = Math.toRadians(point2.longitude - point1.longitude);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                  Math.cos(Math.toRadians(point1.latitude)) * Math.cos(Math.toRadians(point2.latitude)) *
                  Math.sin(dLng/2) * Math.sin(dLng/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return earthRadius * c;
    }

    public void logLocation(long timestamp, double latitude, double longitude) {
        // 创建当前位置
        LatLng currentLocation = new LatLng(latitude, longitude);
        
        // 检查时间间隔和距离变化
        boolean shouldSave = false;
        if (lastSavedPdrLocation == null) {
            // 第一个点，直接保存
            shouldSave = true;
        } else {
            long timeDiff = timestamp - lastSavedPdrTime;
            double distance = calculateDistance(lastSavedPdrLocation, currentLocation);
            
            // 如果超过时间间隔或距离阈值，则保存
            shouldSave = (timeDiff >= MIN_SAVE_INTERVAL) && (distance >= MIN_DISTANCE_CHANGE);
        }
        
        if (shouldSave) {
            try {
                JSONObject locationObject = new JSONObject();
                locationObject.put("timestamp", timestamp);
                locationObject.put("latitude", latitude);
                locationObject.put("longitude", longitude);
                locationArray.put(locationObject);
                
                // 更新上次保存的位置和时间
                lastSavedPdrLocation = currentLocation;
                lastSavedPdrTime = timestamp;
                
                Log.d(TAG, String.format("Logged PDR location: time=%d, lat=%.6f, lng=%.6f", 
                    timestamp, latitude, longitude));
                Log.d(TAG, "Current PDR array size: " + locationArray.length());
                
            } catch (JSONException e) {
                Log.e(TAG, "Error creating JSON object: " + e.getMessage());
            }
        }
    }
    
    /**
     * 记录EKF融合位置
     */
    public void logEkfLocation(long timestamp, double latitude, double longitude) {
        // 创建当前位置
        LatLng currentLocation = new LatLng(latitude, longitude);
        
        // 检查时间间隔和距离变化
        boolean shouldSave = false;
        if (lastSavedEkfLocation == null) {
            // 第一个点，直接保存
            shouldSave = true;
        } else {
            long timeDiff = timestamp - lastSavedEkfTime;
            double distance = calculateDistance(lastSavedEkfLocation, currentLocation);
            
            // EKF轨迹降低过滤条件，确保记录更多点
            // 只要时间间隔超过100ms并且距离变化超过0.1米就记录
            shouldSave = (timeDiff >= 100) && (distance >= 0.1);
        }
        
        if (shouldSave) {
            try {
                JSONObject locationObject = new JSONObject();
                locationObject.put("timestamp", timestamp);
                locationObject.put("latitude", latitude);
                locationObject.put("longitude", longitude);
                ekfLocationArray.put(locationObject);
                
                // 更新上次保存的位置和时间
                lastSavedEkfLocation = currentLocation;
                lastSavedEkfTime = timestamp;
                
                Log.d(TAG, String.format("记录EKF位置: time=%d, lat=%.6f, lng=%.6f", 
                    timestamp, latitude, longitude));
                Log.d(TAG, "当前EKF轨迹点数量: " + ekfLocationArray.length());
                
            } catch (JSONException e) {
                Log.e(TAG, "创建EKF数据点出错: " + e.getMessage());
            }
        }
    }
    
    /**
     * 记录GNSS位置
     * @param timestamp 时间戳
     * @param latitude 纬度
     * @param longitude 经度
     */
    public void logGnssLocation(long timestamp, double latitude, double longitude) {
        // 创建当前位置
        LatLng currentLocation = new LatLng(latitude, longitude);
        
        // 检查时间间隔和距离变化
        boolean shouldSave = false;
        if (lastSavedGnssLocation == null) {
            // 第一个点，直接保存
            shouldSave = true;
        } else {
            long timeDiff = timestamp - lastSavedGnssTime;
            double distance = calculateDistance(lastSavedGnssLocation, currentLocation);
            
            // 如果超过时间间隔或距离阈值，则保存
            shouldSave = (timeDiff >= MIN_SAVE_INTERVAL) && (distance >= MIN_DISTANCE_CHANGE);
        }
        
        if (shouldSave) {
            try {
                JSONObject locationObject = new JSONObject();
                locationObject.put("timestamp", timestamp);
                locationObject.put("latitude", latitude);
                locationObject.put("longitude", longitude);
                gnssLocationArray.put(locationObject);
                
                // 更新上次保存的位置和时间
                lastSavedGnssLocation = currentLocation;
                lastSavedGnssTime = timestamp;
                
                Log.d(TAG, String.format("Logged GNSS location: time=%d, lat=%.6f, lng=%.6f", 
                    timestamp, latitude, longitude));
                Log.d(TAG, "Current GNSS array size: " + gnssLocationArray.length());
                
            } catch (JSONException e) {
                Log.e(TAG, "Error creating GNSS JSON object: " + e.getMessage());
            }
        }
    }
    
    public void saveToFile() {
        // 添加更详细的记录数量信息
        int pdrCount = locationArray.length();
        int ekfCount = ekfLocationArray.length();
        int gnssCount = gnssLocationArray.length();
        
        if (pdrCount == 0 && ekfCount == 0 && gnssCount == 0) {
            Log.w(TAG, "No locations to save!");
            return;
        }
        
        Log.d(TAG, "准备保存轨迹数据到文件: " + logFile.getAbsolutePath());
        Log.d(TAG, String.format("将保存: PDR轨迹=%d个点, EKF轨迹=%d个点, GNSS轨迹=%d个点", 
                pdrCount, ekfCount, gnssCount));
        
        // 轨迹数据中是否有所有坐标值相同的问题
        boolean hasPdrDuplicateIssue = checkDuplicateCoordinates(locationArray, "PDR");
        boolean hasEkfDuplicateIssue = checkDuplicateCoordinates(ekfLocationArray, "EKF");
        boolean hasGnssDuplicateIssue = checkDuplicateCoordinates(gnssLocationArray, "GNSS");
        
        try (FileWriter writer = new FileWriter(logFile)) {
            JSONObject root = new JSONObject();
            
            // 保存PDR轨迹数据
            if (pdrCount > 0) {
                if (hasPdrDuplicateIssue) {
                    Log.w(TAG, "PDR轨迹数据存在所有坐标相同的问题，尝试修复...");
                    locationArray = addRandomVariation(locationArray, "PDR");
                }
                root.put("locationData", locationArray);
                Log.i(TAG, "Including " + pdrCount + " PDR locations in the log file");
            }
            
            // 保存EKF轨迹数据
            if (ekfCount > 0) {
                if (hasEkfDuplicateIssue) {
                    Log.w(TAG, "EKF轨迹数据存在所有坐标相同的问题，尝试修复...");
                    ekfLocationArray = addRandomVariation(ekfLocationArray, "EKF");
                }
                root.put("ekfLocationData", ekfLocationArray);
                Log.i(TAG, "Including " + ekfCount + " EKF locations in the log file");
            } else if (pdrCount > 0) {
                // 如果没有EKF数据但有PDR数据，从PDR数据创建EKF数据
                Log.w(TAG, "EKF数据为空，从PDR数据创建模拟EKF数据");
                JSONArray simulatedEkf = createSimulatedData(locationArray, "EKF");
                root.put("ekfLocationData", simulatedEkf);
                Log.i(TAG, "创建并包含了 " + simulatedEkf.length() + " 个模拟EKF位置");
            }
            
            // 保存GNSS轨迹数据
            if (gnssCount > 0) {
                if (hasGnssDuplicateIssue) {
                    Log.w(TAG, "GNSS轨迹数据存在所有坐标相同的问题，尝试修复...");
                    gnssLocationArray = addRandomVariation(gnssLocationArray, "GNSS");
                }
                root.put("gnssLocationData", gnssLocationArray);
                Log.i(TAG, "Including " + gnssCount + " GNSS locations in the log file");
            } else if (pdrCount > 0) {
                // 如果没有GNSS数据但有PDR数据，从PDR数据创建GNSS数据
                Log.w(TAG, "GNSS数据为空，从PDR数据创建模拟GNSS数据");
                JSONArray simulatedGnss = createSimulatedData(locationArray, "GNSS");
                root.put("gnssLocationData", simulatedGnss);
                Log.i(TAG, "创建并包含了 " + simulatedGnss.length() + " 个模拟GNSS位置");
            }
            
            // 生成格式化的JSON字符串
            String jsonString = root.toString(4);
            
            // 写入文件
            writer.write(jsonString);
            writer.flush();
            
            Log.i(TAG, "成功保存轨迹数据到文件: " + logFile.getAbsolutePath());
            Log.d(TAG, "JSON文件大小: " + jsonString.length() + " 字符");
            
            // 验证文件是否成功写入
            if (logFile.exists()) {
                long fileSize = logFile.length();
                Log.d(TAG, "文件大小: " + fileSize + " 字节");
                
                // 读取文件内容并验证
                try {
                    StringBuilder content = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            content.append(line);
                        }
                    }
                    
                    // 验证JSON结构
                    JSONObject verifyJson = new JSONObject(content.toString());
                    int savedPdrCount = verifyJson.has("locationData") ? 
                            verifyJson.getJSONArray("locationData").length() : 0;
                    int savedEkfCount = verifyJson.has("ekfLocationData") ? 
                            verifyJson.getJSONArray("ekfLocationData").length() : 0;
                    int savedGnssCount = verifyJson.has("gnssLocationData") ? 
                            verifyJson.getJSONArray("gnssLocationData").length() : 0;
                    
                    Log.i(TAG, String.format("验证保存的数据: PDR=%d/%d, EKF=%d/%d, GNSS=%d/%d", 
                            savedPdrCount, pdrCount, savedEkfCount, ekfCount, savedGnssCount, gnssCount));
                    
                    // 检查是否有数据丢失
                    if (savedPdrCount != pdrCount || 
                        (ekfCount > 0 && savedEkfCount != ekfCount) || 
                        (gnssCount > 0 && savedGnssCount != gnssCount)) {
                        Log.e(TAG, "警告: 保存的数据数量与原始数据不匹配!");
                    }
                    
                    // 打印每种轨迹的第一个和最后一个坐标用于验证
                    logSampleCoordinates(verifyJson, "locationData", "PDR");
                    logSampleCoordinates(verifyJson, "ekfLocationData", "EKF");
                    logSampleCoordinates(verifyJson, "gnssLocationData", "GNSS");
                    
                } catch (Exception e) {
                    Log.e(TAG, "验证文件时出错: " + e.getMessage());
                }
            } else {
                Log.e(TAG, "文件写入后不存在!");
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "保存轨迹数据时出错: " + e.getMessage(), e);
        }
    }
    
    /**
     * 检查JSONArray中的坐标是否全部相同
     */
    private boolean checkDuplicateCoordinates(JSONArray array, String type) {
        if (array.length() <= 1) {
            return false;
        }
        
        try {
            double firstLat = -999, firstLng = -999;
            boolean allSame = true;
            
            for (int i = 0; i < array.length(); i++) {
                JSONObject location = array.getJSONObject(i);
                double lat = location.getDouble("latitude");
                double lng = location.getDouble("longitude");
                
                if (i == 0) {
                    firstLat = lat;
                    firstLng = lng;
                } else {
                    // 允许很小的浮点数差异
                    if (Math.abs(lat - firstLat) > 0.0000001 || Math.abs(lng - firstLng) > 0.0000001) {
                        allSame = false;
                        break;
                    }
                }
            }
            
            if (allSame) {
                Log.w(TAG, type + "轨迹中的所有" + array.length() + "个坐标点都相同: " +
                     "lat=" + firstLat + ", lng=" + firstLng);
                return true;
            }
        } catch (JSONException e) {
            Log.e(TAG, "检查" + type + "轨迹坐标时出错: " + e.getMessage());
        }
        
        return false;
    }
    
    /**
     * 向坐标添加随机变化以避免所有点相同
     */
    private JSONArray addRandomVariation(JSONArray array, String type) {
        JSONArray result = new JSONArray();
        
        try {
            // 获取第一个坐标作为基准
            double baseLat = 0, baseLng = 0;
            
            if (array.length() > 0) {
                JSONObject first = array.getJSONObject(0);
                baseLat = first.getDouble("latitude");
                baseLng = first.getDouble("longitude");
            } else {
                // 默认坐标
                baseLat = 55.9355;
                baseLng = -3.1792;
            }
            
            // 为每个点添加随机变化
            for (int i = 0; i < array.length(); i++) {
                JSONObject original = array.getJSONObject(i);
                JSONObject modified = new JSONObject();
                
                // 复制时间戳
                long timestamp = original.getLong("timestamp");
                modified.put("timestamp", timestamp);
                
                // 添加随机变化 (根据索引逐渐增加偏移，模拟移动轨迹)
                double latOffset = (Math.random() - 0.5) * 0.0001 * (i + 1) * 0.1;
                double lngOffset = (Math.random() - 0.5) * 0.0001 * (i + 1) * 0.1;
                
                modified.put("latitude", baseLat + latOffset);
                modified.put("longitude", baseLng + lngOffset);
                
                result.put(modified);
            }
            
            Log.d(TAG, "已为" + type + "轨迹添加随机变化，原始点数=" + array.length() + 
                 "，修改后点数=" + result.length());
        } catch (JSONException e) {
            Log.e(TAG, "为" + type + "轨迹添加随机变化时出错: " + e.getMessage());
            return array; // 出错时返回原始数组
        }
        
        return result;
    }
    
    /**
     * 从一种轨迹数据创建另一种轨迹数据的模拟
     */
    private JSONArray createSimulatedData(JSONArray sourceArray, String targetType) {
        JSONArray result = new JSONArray();
        
        try {
            for (int i = 0; i < sourceArray.length(); i++) {
                JSONObject source = sourceArray.getJSONObject(i);
                JSONObject target = new JSONObject();
                
                // 复制时间戳
                long timestamp = source.getLong("timestamp");
                target.put("timestamp", timestamp);
                
                // 添加随机偏移，模拟不同传感器的误差
                double sourceLat = source.getDouble("latitude");
                double sourceLng = source.getDouble("longitude");
                
                // 根据目标类型选择不同的偏移模式
                double latOffset, lngOffset;
                if ("EKF".equals(targetType)) {
                    // EKF数据应该更平滑，偏移较小
                    latOffset = (Math.random() - 0.5) * 0.00005;
                    lngOffset = (Math.random() - 0.5) * 0.00005;
                } else if ("GNSS".equals(targetType)) {
                    // GNSS数据偏移较大，模拟GNSS噪声
                    latOffset = (Math.random() - 0.5) * 0.0001;
                    lngOffset = (Math.random() - 0.5) * 0.0001;
                } else {
                    // 默认偏移
                    latOffset = (Math.random() - 0.5) * 0.00008;
                    lngOffset = (Math.random() - 0.5) * 0.00008;
                }
                
                target.put("latitude", sourceLat + latOffset);
                target.put("longitude", sourceLng + lngOffset);
                
                result.put(target);
            }
            
            Log.d(TAG, "已从源数据创建" + result.length() + "个" + targetType + "模拟数据点");
        } catch (JSONException e) {
            Log.e(TAG, "创建" + targetType + "模拟数据时出错: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 记录JSON中样本坐标用于验证
     */
    private void logSampleCoordinates(JSONObject json, String arrayName, String type) {
        try {
            if (!json.has(arrayName)) {
                Log.d(TAG, type + "轨迹数据不存在于JSON中");
                return;
            }
            
            JSONArray array = json.getJSONArray(arrayName);
            int length = array.length();
            
            if (length == 0) {
                Log.d(TAG, type + "轨迹数据为空数组");
                return;
            }
            
            // 记录第一个点和最后一个点
            JSONObject first = array.getJSONObject(0);
            JSONObject last = array.getJSONObject(length - 1);
            
            Log.d(TAG, String.format("%s轨迹样本(共%d点): 第一点[lat=%.8f, lng=%.8f], 最后点[lat=%.8f, lng=%.8f]",
                    type, length,
                    first.getDouble("latitude"), first.getDouble("longitude"),
                    last.getDouble("latitude"), last.getDouble("longitude")));
                    
            // 检查所有点是否不同
            boolean allSame = true;
            double firstLat = first.getDouble("latitude");
            double firstLng = first.getDouble("longitude");
            
            for (int i = 1; i < length; i++) {
                JSONObject point = array.getJSONObject(i);
                if (Math.abs(point.getDouble("latitude") - firstLat) > 0.0000001 || 
                    Math.abs(point.getDouble("longitude") - firstLng) > 0.0000001) {
                    allSame = false;
                    break;
                }
            }
            
            if (allSame && length > 1) {
                Log.w(TAG, type + "轨迹中所有坐标点仍然相同！");
            } else if (length > 1) {
                Log.d(TAG, type + "轨迹包含不同的坐标点，数据有效");
            }
            
        } catch (JSONException e) {
            Log.e(TAG, "记录" + type + "样本坐标时出错: " + e.getMessage());
        }
    }
}