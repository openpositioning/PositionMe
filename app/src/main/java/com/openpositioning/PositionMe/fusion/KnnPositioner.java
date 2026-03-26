package com.openpositioning.PositionMe.fusion;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * K-Nearest Neighbors WiFi positioning using pre-collected fingerprint data.
 * Compares current WiFi scan against training fingerprints using Euclidean
 * distance on RSSI vectors, then returns a weighted average of the K closest
 * training points.
 */
public class KnnPositioner {

    private static final String TAG = "KnnPositioner";
    private static final int K = 3;
    private static final double DEFAULT_RSSI = -100.0; // for missing APs
    private static final String DATA_FILE = "knn_data/wifi_fingerprints.json";

    private final WifiManager wifiManager;

    // Training data
    private final List<Map<String, Double>> trainingVectors = new ArrayList<>();
    private final List<LatLng> trainingPositions = new ArrayList<>();
    private final List<Integer> trainingFloors = new ArrayList<>();

    private boolean loaded = false;

    public KnnPositioner(Context context) {
        this.wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        loadTrainingData(context);
    }

    /** Returns true if training data was loaded successfully. */
    public boolean isLoaded() { return loaded; }

    /** Returns the number of training points. */
    public int getTrainingSize() { return trainingPositions.size(); }

    /**
     * Estimates position from current WiFi scan using KNN.
     * @return estimated LatLng, or null if no data / no scan results
     */
    public LatLng estimatePosition() {
        List<ScanResult> scanResults = wifiManager.getScanResults();
        if (scanResults == null || scanResults.isEmpty() || !loaded) return null;

        // Build current fingerprint vector
        Map<String, Double> currentVector = new HashMap<>();
        for (ScanResult sr : scanResults) {
            currentVector.put(sr.BSSID, (double) sr.level);
        }

        // Compute distance to all training points
        List<double[]> distAndIndex = new ArrayList<>();
        for (int i = 0; i < trainingVectors.size(); i++) {
            double dist = euclideanDistance(currentVector, trainingVectors.get(i));
            distAndIndex.add(new double[]{dist, i});
        }

        // Sort by distance
        Collections.sort(distAndIndex, (a, b) -> Double.compare(a[0], b[0]));

        // Weighted average of K nearest (inverse distance weighting)
        double weightSum = 0;
        double latSum = 0, lngSum = 0;
        int count = Math.min(K, distAndIndex.size());

        for (int i = 0; i < count; i++) {
            int idx = (int) distAndIndex.get(i)[1];
            double dist = distAndIndex.get(i)[0];
            // Inverse distance weight (avoid division by zero)
            double w = 1.0 / (dist + 1e-6);
            LatLng pos = trainingPositions.get(idx);
            latSum += pos.latitude * w;
            lngSum += pos.longitude * w;
            weightSum += w;
        }

        if (weightSum <= 0) return null;

        LatLng result = new LatLng(latSum / weightSum, lngSum / weightSum);
        Log.d(TAG, "KNN estimate: " + result.latitude + "," + result.longitude
                + " (top dist=" + String.format("%.1f", distAndIndex.get(0)[0]) + ")");
        return result;
    }

    /**
     * Estimates position for a specific floor only.
     * @param floor the floor index to filter by
     * @return estimated LatLng, or null
     */
    public LatLng estimatePositionForFloor(int floor) {
        List<ScanResult> scanResults = wifiManager.getScanResults();
        if (scanResults == null || scanResults.isEmpty() || !loaded) return null;

        Map<String, Double> currentVector = new HashMap<>();
        for (ScanResult sr : scanResults) {
            currentVector.put(sr.BSSID, (double) sr.level);
        }

        // Filter training data by floor, compute distances
        List<double[]> distAndIndex = new ArrayList<>();
        for (int i = 0; i < trainingVectors.size(); i++) {
            if (trainingFloors.get(i) != floor) continue;
            double dist = euclideanDistance(currentVector, trainingVectors.get(i));
            distAndIndex.add(new double[]{dist, i});
        }

        if (distAndIndex.isEmpty()) return null;
        Collections.sort(distAndIndex, (a, b) -> Double.compare(a[0], b[0]));

        double weightSum = 0, latSum = 0, lngSum = 0;
        int count = Math.min(K, distAndIndex.size());
        for (int i = 0; i < count; i++) {
            int idx = (int) distAndIndex.get(i)[1];
            double dist = distAndIndex.get(i)[0];
            double w = 1.0 / (dist + 1e-6);
            LatLng pos = trainingPositions.get(idx);
            latSum += pos.latitude * w;
            lngSum += pos.longitude * w;
            weightSum += w;
        }

        if (weightSum <= 0) return null;
        return new LatLng(latSum / weightSum, lngSum / weightSum);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private double euclideanDistance(Map<String, Double> a, Map<String, Double> b) {
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(a.keySet());
        allKeys.addAll(b.keySet());

        double sum = 0;
        for (String key : allKeys) {
            double va = a.containsKey(key) ? a.get(key) : DEFAULT_RSSI;
            double vb = b.containsKey(key) ? b.get(key) : DEFAULT_RSSI;
            double diff = va - vb;
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    private void loadTrainingData(Context context) {
        try {
            InputStream is = context.getAssets().open(DATA_FILE);
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();

            JSONArray arr = new JSONArray(new String(buf));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject fp = arr.getJSONObject(i);
                double lat = fp.getDouble("lat");
                double lng = fp.getDouble("lng");
                int floor = fp.getInt("floor");

                JSONArray aps = fp.getJSONArray("aps");
                Map<String, Double> vector = new HashMap<>();
                for (int j = 0; j < aps.length(); j++) {
                    JSONObject ap = aps.getJSONObject(j);
                    vector.put(ap.getString("bssid"), ap.getDouble("rssi"));
                }

                trainingPositions.add(new LatLng(lat, lng));
                trainingFloors.add(floor);
                trainingVectors.add(vector);
            }

            loaded = true;
            Log.d(TAG, "Loaded " + trainingPositions.size() + " training fingerprints");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load KNN training data", e);
            loaded = false;
        }
    }
}
