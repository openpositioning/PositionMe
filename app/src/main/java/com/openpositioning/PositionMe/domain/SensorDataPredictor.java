package com.openpositioning.PositionMe.domain;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.SensorFusion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * SensorDataPredictor 负责：
 * 1. 通过 SensorFusion.getAllSensorData() 获取所有传感器数据，
 *    （该方法返回的数据结构中包含 "wifiList" 数组，字段内容见 SensorFusionUtils.getAllSensorData）
 * 2. 从 wifiList 中筛选出 RSSI 数据，提取 TOP_N 个信号强度（不足时补 -100），
 * 3. 利用预先训练好的 TensorFlow Lite 模型对 WiFi RSSI 特征进行预测，输出预测位置。
 */
public class SensorDataPredictor {
    private static final String TAG = "SensorDataPredictor";
    private static final int TOP_N = 20;
    private Interpreter tflite;

    public SensorDataPredictor(Context context) {
        try {
            tflite = new Interpreter(loadModelFile(context, "tf_model.tflite"));
            Log.d(TAG, "TFLite model loaded successfully from assets/tf_model.tflite.");
        } catch (IOException e) {
            Log.e(TAG, "Error reading TFLite model", e);
        }
    }

    private MappedByteBuffer loadModelFile(Context context, String modelFileName) throws IOException {
        AssetFileDescriptor fd = context.getAssets().openFd(modelFileName);
        FileInputStream inputStream = new FileInputStream(fd.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fd.getStartOffset();
        long declaredLength = fd.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

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
            List<Integer> rssiValues = new ArrayList<>();
            List<String> bssidValues = new ArrayList<>();
            for (int i = 0; i < wifiList.length(); i++) {
                JSONObject wifiEntry = wifiList.getJSONObject(i);
                int rssi = wifiEntry.getInt("rssi");
                rssiValues.add(rssi);
                bssidValues.add(wifiEntry.getString("bssid"));
            }
            // sort by RSSI descending
            rssiValues.sort((o1, o2) -> Integer.compare(o2, o1));

            // fill TOP_N
            float[] features = new float[TOP_N];
            for (int i = 0; i < TOP_N; i++) {
                if (i < rssiValues.size()) {
                    features[i] = rssiValues.get(i);
                } else {
                    features[i] = -100f;
                }
                Log.i(TAG, "RSSI : " + (i < rssiValues.size() ? rssiValues.get(i) : -100) + ", BSSID: " + (i < bssidValues.size() ? bssidValues.get(i) : "N/A"));
            }


            // run model
            float[][] input = new float[1][TOP_N];
            input[0] = features;
            float[][] output = new float[1][2];

            tflite.run(input, output);

            // interpret the model output as lat & lon in degrees
            float lat = output[0][0];
            float lon = output[0][1];
            Log.i(TAG, "Predicted lat/lon = (" + lat + ", " + lon + ")");

            return new LatLng(lat, lon);

        } catch (JSONException e) {
            Log.e(TAG, "JSON parse error", e);
            return null;
        }
    }
}

