package com.openpositioning.PositionMe.domain;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.MappedByteBuffer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SensorDataPredictor {
private static final String TAG = "SensorDataPredictor";
private static final int TOP_N = 20;

private Interpreter tflite;
private Map<String, Integer> bssid2idx; // 加载 bssid->index 映射

// 示例：这里用固定的归一化参数（与训练时保持一致）
private static final float LAT_MIN = 55.9224739074707f;
private static final float LAT_MAX = 55.9234504699707f;
private static final float LAT_RANGE = LAT_MAX - LAT_MIN;

private static final float LON_MIN = -3.1746456623077393f;
private static final float LON_MAX = -3.173879623413086f;
private static final float LON_RANGE = LON_MAX - LON_MIN;

public SensorDataPredictor(Context context) {
    // 1. 加载模型
    try {
        MappedByteBuffer tfliteModel = loadModelFile(context, "tf_model.tflite");
        tflite = new Interpreter(tfliteModel);
        Log.d(TAG, "TFLite model loaded successfully from assets/tf_model.tflite.");
    } catch (IOException e) {
        Log.e(TAG, "Error reading TFLite model", e);
    }

    // 2. 加载 bssid2idx.json
    try {
        bssid2idx = loadBssid2Idx(context, "bssid2idx.json");
        Log.d(TAG, "bssid2idx loaded. size = " + bssid2idx.size());
    } catch (IOException e) {
        Log.e(TAG, "Error loading bssid2idx.json", e);
        bssid2idx = new HashMap<>();
    }
}

/**
 * 从 assets 中读取 TFLite 模型文件
 */
private MappedByteBuffer loadModelFile(Context context, String modelFileName) throws IOException {
    AssetFileDescriptor fd = context.getAssets().openFd(modelFileName);
    FileInputStream inputStream = new FileInputStream(fd.getFileDescriptor());
    FileChannel fileChannel = inputStream.getChannel();
    long startOffset = fd.getStartOffset();
    long declaredLength = fd.getDeclaredLength();
    return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
}

/**
 * 从 assets 中加载 bssid->index 的映射 JSON
 */
private Map<String, Integer> loadBssid2Idx(Context context, String fileName) throws IOException {
    InputStream is = context.getAssets().open(fileName);
    byte[] buffer = new byte[is.available()];
    int readBytes = is.read(buffer);
    is.close();
    if (readBytes <= 0) {
        return new HashMap<>();
    }
    String jsonStr = new String(buffer);
    try {
        JSONObject obj = new JSONObject(jsonStr);
        Map<String, Integer> map = new LinkedHashMap<>();
        // 形如： { "bssidA": 1, "bssidB": 2, ... }
        JSONArray names = obj.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String bssid = names.getString(i);
                int index = obj.getInt(bssid);
                map.put(bssid, index);
            }
        }
        return map;
    } catch (JSONException e) {
        e.printStackTrace();
        return new HashMap<>();
    }
}

/**
 * 调用模型进行推理：BSSID embedding + RSSI
 * @param sensorFusion 传感器对象
 * @return 预测得到的 (Lat, Lon)
 */
public LatLng predictPosition(SensorFusion sensorFusion) {
    if (tflite == null) {
        Log.e(TAG, "TFLite Interpreter is null.");
        return null;
    }
    if (sensorFusion == null) {
        Log.e(TAG, "SensorFusion is null.");
        return null;
    }

    JSONObject sensorData = sensorFusion.getAllSensorData();
    if (sensorData == null) {
        Log.e(TAG, "sensorData is null.");
        return null;
    }

    try {
        if (!sensorData.has("wifiList")) {
            Log.e(TAG, "No wifiList in sensorData.");
            return null;
        }
        JSONArray wifiList = sensorData.getJSONArray("wifiList");

        // 解析 wifiList 并按 RSSI 降序排序
        // 同时保留 (bssid, rssi)
        List<JSONObject> wifiJsonList = new ArrayList<>();
        for (int i = 0; i < wifiList.length(); i++) {
            wifiJsonList.add(wifiList.getJSONObject(i));
        }
        Collections.sort(wifiJsonList, (o1, o2) -> {
            try {
                return Integer.compare(o2.getInt("rssi"), o1.getInt("rssi"));
            } catch (JSONException e) {
                return 0;
            }
        });

        // 准备两个输入：bssidIndices 和 rssiValues
        int[] bssidIndices = new int[TOP_N];
        float[] rssiValues = new float[TOP_N];

        for (int i = 0; i < TOP_N; i++) {
            if (i < wifiJsonList.size()) {
                JSONObject wifiEntry = wifiJsonList.get(i);
                int rssi = wifiEntry.getInt("rssi");
                String bssid = wifiEntry.getString("bssid");

                // 1) 找到 bssid 对应的索引，若未找到则用 0
                Integer idx = bssid2idx.get(bssid);
                if (idx == null) {
                    idx = 0; // 未知 BSSID 使用 padding=0
                }
                bssidIndices[i] = idx;

                // 2) RSSI
                rssiValues[i] = (float) rssi;
            } else {
                // 不足 TOP_N 时，补齐
                bssidIndices[i] = 0;
                rssiValues[i] = -100.0f;
            }
        }

        // RSSI 归一化：除以 100
        for (int i = 0; i < TOP_N; i++) {
            rssiValues[i] = rssiValues[i] / 100.0f;
        }

        // === 构造 ByteBuffer for INT32 输入 ===
        ByteBuffer bssidBuffer = ByteBuffer.allocateDirect(TOP_N * 4); // 4 bytes per int
        bssidBuffer.order(java.nio.ByteOrder.nativeOrder());
        for (int i = 0; i < TOP_N; i++) {
            bssidBuffer.putInt(bssidIndices[i]);
        }
        bssidBuffer.rewind(); // 重置位置

        // === 构造 RSSI float[][] 输入 ===
        float[][] rssiInput = new float[1][TOP_N];
        rssiInput[0] = rssiValues;

        // === 构造 TFLite 多输入 ===
        Object[] inputs = new Object[]{rssiInput, bssidBuffer};


        // === 构造输出 ===
        float[][] output = new float[1][2];
        Map<Integer, Object> outputs = new HashMap<>();
        outputs.put(0, output);

        // === 推理 ===
        tflite.runForMultipleInputsOutputs(inputs, outputs);


        // output[0][0] = 归一化后的 lat
        // output[0][1] = 归一化后的 lon
        float normLat = output[0][0];
        float normLon = output[0][1];

        // 反归一化
        float lat = normLat * LAT_RANGE + LAT_MIN;
        float lon = normLon * LON_RANGE + LON_MIN;

        Log.i(TAG, "Predicted normalized lat/lon = (" + normLat + ", " + normLon + ")");
        Log.i(TAG, "Predicted lat/lon after reverse normalization = (" + lat + ", " + lon + ")");

        return new LatLng(lat, lon);

    } catch (JSONException e) {
        Log.e(TAG, "JSON parse error", e);
        return null;
    }
}
}
