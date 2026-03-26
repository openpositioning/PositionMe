package com.openpositioning.PositionMe.utils;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

/**
 * Collects WiFi fingerprints at known positions for KNN-based indoor positioning.
 * Each fingerprint stores: {lat, lng, floor, aps: [{bssid, rssi}, ...]}.
 * Data is saved to a JSON file on internal storage.
 */
public class WifiFingerprinter {

    private static final String TAG = "WifiFingerprinter";
    private static final String FILENAME = "wifi_fingerprints.json";

    private final Context context;
    private final WifiManager wifiManager;
    private final JSONArray fingerprints;

    public WifiFingerprinter(Context context) {
        this.context = context;
        this.wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        this.fingerprints = new JSONArray();
    }

    /**
     * Records one WiFi fingerprint at the given position.
     * Triggers a WiFi scan and saves all visible APs with their RSSI.
     *
     * @param pos   the known LatLng where the user is standing
     * @param floor the floor number (e.g. 1 for F1)
     * @return number of APs recorded, or -1 on error
     */
    public int recordFingerprint(LatLng pos, int floor) {
        try {
            List<ScanResult> results = wifiManager.getScanResults();
            if (results == null || results.isEmpty()) {
                Log.w(TAG, "No WiFi scan results available");
                return -1;
            }

            JSONObject fp = new JSONObject();
            fp.put("lat", pos.latitude);
            fp.put("lng", pos.longitude);
            fp.put("floor", floor);

            JSONArray aps = new JSONArray();
            for (ScanResult sr : results) {
                JSONObject ap = new JSONObject();
                ap.put("bssid", sr.BSSID);
                ap.put("rssi", sr.level);
                ap.put("ssid", sr.SSID);
                aps.put(ap);
            }
            fp.put("aps", aps);
            fp.put("timestamp", System.currentTimeMillis());

            fingerprints.put(fp);

            Log.d(TAG, "Fingerprint #" + fingerprints.length()
                    + " recorded: " + aps.length() + " APs at "
                    + pos.latitude + "," + pos.longitude + " floor=" + floor);

            return aps.length();
        } catch (JSONException e) {
            Log.e(TAG, "JSON error recording fingerprint", e);
            return -1;
        }
    }

    /**
     * Saves all recorded fingerprints to a JSON file.
     * @return the file path, or null on error
     */
    public String saveToFile() {
        try {
            // Save to app's external files dir (visible in file manager)
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir(); // fallback
            File file = new File(dir, FILENAME);
            FileWriter writer = new FileWriter(file);
            writer.write(fingerprints.toString(2));
            writer.flush();
            writer.close();
            Log.d(TAG, "Saved " + fingerprints.length()
                    + " fingerprints to " + file.getAbsolutePath());
            return file.getAbsolutePath();
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Error saving fingerprints", e);
            return null;
        }
    }

    /** Returns how many fingerprints have been recorded so far. */
    public int getCount() {
        return fingerprints.length();
    }
}
