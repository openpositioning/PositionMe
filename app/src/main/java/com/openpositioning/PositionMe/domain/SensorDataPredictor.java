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

/**
 *  This class uses a TensorFlow Lite model to predict the location (latitude and longitude)
 *  of a device based on sensor data, primarily Wi-Fi RSSI values and BSSIDs.
 *  It loads the model and related data (BSSID to index mapping) from the assets folder.
 */
public class SensorDataPredictor {
    private static final String TAG = "SensorDataPredictor";
    private static final int TOP_N = 20;

    private Interpreter tflite;
    private Map<String, Integer> bssid2idx;

    // Normalization parameters (should match training)
    private static final float LAT_MIN = 55.9224739074707f;
    private static final float LAT_MAX = 55.9234504699707f;
    private static final float LAT_RANGE = LAT_MAX - LAT_MIN;

    private static final float LON_MIN = -3.1746456623077393f;
    private static final float LON_MAX = -3.173879623413086f;
    private static final float LON_RANGE = LON_MAX - LON_MIN;

    public SensorDataPredictor(Context context) {
        try {
            MappedByteBuffer tfliteModel = loadModelFile(context, "tf_model.tflite");
            tflite = new Interpreter(tfliteModel);
            Log.d(TAG, "TFLite model loaded successfully from assets/tf_model.tflite.");
        } catch (IOException e) {
            Log.e(TAG, "Error reading TFLite model", e);
        }

        try {
            bssid2idx = loadBssid2Idx(context, "bssid2idx.json");
            Log.d(TAG, "bssid2idx loaded. size = " + bssid2idx.size());
        } catch (IOException e) {
            Log.e(TAG, "Error loading bssid2idx.json", e);
            bssid2idx = new HashMap<>();
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
     * Run inference using current SensorFusion input
     * @param sensorFusion the fusion object with all available sensor data
     * @return predicted LatLng, or null if error
     */
    public LatLng predictPosition(SensorFusion sensorFusion) {
        if (tflite == null || sensorFusion == null) {
            Log.e(TAG, "TFLite Interpreter or SensorFusion is null.");
            return null;
        }

        JSONObject sensorData = sensorFusion.getAllSensorData();
        if (sensorData == null || !sensorData.has("wifiList")) {
            Log.e(TAG, "Invalid sensorData or missing wifiList.");
            return null;
        }

        try {
            JSONArray wifiList = sensorData.getJSONArray("wifiList");
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

            int[] bssidIndices = new int[TOP_N];
            float[] rssiValues = new float[TOP_N];

            for (int i = 0; i < TOP_N; i++) {
                if (i < wifiJsonList.size()) {
                    JSONObject wifiEntry = wifiJsonList.get(i);
                    int rssi = wifiEntry.getInt("rssi");
                    String bssid = wifiEntry.getString("bssid");
                    Integer idx = bssid2idx.getOrDefault(bssid, 0);
                    bssidIndices[i] = idx;
                    rssiValues[i] = (float) rssi;
                } else {
                    bssidIndices[i] = 0;
                    rssiValues[i] = -100.0f;
                }
            }

            for (int i = 0; i < TOP_N; i++) {
                rssiValues[i] = rssiValues[i] / 100.0f;
            }

            ByteBuffer bssidBuffer = ByteBuffer.allocateDirect(TOP_N * 4);
            bssidBuffer.order(java.nio.ByteOrder.nativeOrder());
            for (int i = 0; i < TOP_N; i++) {
                bssidBuffer.putInt(bssidIndices[i]);
            }
            bssidBuffer.rewind();

            float[][] rssiInput = new float[1][TOP_N];
            rssiInput[0] = rssiValues;

            Object[] inputs = new Object[]{rssiInput, bssidBuffer};
            float[][] output = new float[1][2];
            Map<Integer, Object> outputs = new HashMap<>();
            outputs.put(0, output);

            tflite.runForMultipleInputsOutputs(inputs, outputs);

            float normLat = output[0][0];
            float normLon = output[0][1];

            float lat = normLat * LAT_RANGE + LAT_MIN;
            float lon = normLon * LON_RANGE + LON_MIN;

            Log.i(TAG, "Predicted normalized lat/lon = (" + normLat + ", " + normLon + ")");
            Log.i(TAG, "Predicted lat/lon = (" + lat + ", " + lon + ")");

            return new LatLng(lat, lon);

        } catch (JSONException e) {
            Log.e(TAG, "JSON parse error", e);
            return null;
        }
    }
}
