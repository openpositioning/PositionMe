package com.openpositioning.PositionMe.sensors;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.WifiRttManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * WiFi RTT (Round-Trip Time) ranging processor.
 *
 * Uses Android's WifiRttManager (802.11mc / WiFi Fine Time Measurement) to measure
 * distances to RTT-capable access points. Results include distance in mm, standard
 * deviation, and RSSI for each responding AP.
 *
 * Requires Android 9+ (API 28) and device hardware support for WiFi RTT.
 */
public class WifiRttProcessor {
    private static final String TAG = "WifiRttProcessor";

    private final Context context;
    private final WifiRttManager rttManager;
    private final boolean deviceSupportsRtt;
    private final Executor executor;

    /** Data class for a single RTT measurement result. */
    public static class RttMeasurement {
        public final long mac;          // integer MAC (same format as Wifi.getBssid())
        public final int distanceMm;    // distance in millimetres
        public final int distanceStdMm; // standard deviation in mm
        public final int rssi;          // RSSI in dBm

        public RttMeasurement(long mac, int distanceMm, int distanceStdMm, int rssi) {
            this.mac = mac;
            this.distanceMm = distanceMm;
            this.distanceStdMm = distanceStdMm;
            this.rssi = rssi;
        }
    }

    // Latest RTT results (thread-safe access via synchronized)
    private final List<RttMeasurement> latestResults = new ArrayList<>();

    /**
     * Constructor. Checks device RTT support and initialises WifiRttManager.
     */
    public WifiRttProcessor(Context context) {
        this.context = context;
        this.executor = Executors.newSingleThreadExecutor();
        this.deviceSupportsRtt = context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_WIFI_RTT);

        if (deviceSupportsRtt) {
            rttManager = (WifiRttManager) context.getSystemService(Context.WIFI_RTT_RANGING_SERVICE);
            Log.i(TAG, "WiFi RTT supported on this device");
        } else {
            rttManager = null;
            Log.w(TAG, "WiFi RTT NOT supported on this device");
        }
    }

    /**
     * Whether the device hardware supports WiFi RTT.
     */
    public boolean isSupported() {
        return deviceSupportsRtt && rttManager != null;
    }

    /**
     * Perform RTT ranging to the given list of RTT-capable APs.
     * Results are stored internally and can be retrieved via {@link #getAndClearResults()}.
     *
     * @param rttCapableAps list of ScanResult objects that support 802.11mc
     */
    public void performRanging(List<ScanResult> rttCapableAps) {
        if (!isSupported() || rttCapableAps == null || rttCapableAps.isEmpty()) return;

        if (ActivityCompat.checkSelfPermission(context,
                android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Missing FINE_LOCATION permission for RTT ranging");
            return;
        }

        // Build ranging request (max 10 APs per request per Android spec)
        RangingRequest.Builder builder = new RangingRequest.Builder();
        int count = Math.min(rttCapableAps.size(), RangingRequest.getMaxPeers());
        for (int i = 0; i < count; i++) {
            builder.addAccessPoint(rttCapableAps.get(i));
        }

        try {
            rttManager.startRanging(builder.build(), executor, new RangingResultCallback() {
                @Override
                public void onRangingResults(List<RangingResult> results) {
                    List<RttMeasurement> measurements = new ArrayList<>();
                    for (RangingResult result : results) {
                        if (result.getStatus() == RangingResult.STATUS_SUCCESS) {
                            MacAddress macAddr = result.getMacAddress();
                            long macLong = macAddressToLong(macAddr);
                            measurements.add(new RttMeasurement(
                                    macLong,
                                    result.getDistanceMm(),
                                    result.getDistanceStdDevMm(),
                                    result.getRssi()
                            ));
                            Log.d(TAG, "RTT result: mac=" + macAddr
                                    + " dist=" + result.getDistanceMm() + "mm"
                                    + " std=" + result.getDistanceStdDevMm() + "mm"
                                    + " rssi=" + result.getRssi());
                        }
                    }
                    synchronized (latestResults) {
                        latestResults.addAll(measurements);
                    }
                    if (measurements.isEmpty()) {
                        Log.d(TAG, "RTT ranging: no successful results from " + results.size() + " APs");
                    }
                }

                @Override
                public void onRangingFailure(int code) {
                    Log.w(TAG, "RTT ranging failed with code: " + code);
                }
            });
            Log.d(TAG, "RTT ranging started for " + count + " APs");
        } catch (Exception e) {
            Log.e(TAG, "RTT startRanging failed: " + e.getMessage());
        }
    }

    /**
     * Get all accumulated RTT results and clear the buffer.
     * Called by SensorFusion when storing trajectory data.
     */
    public List<RttMeasurement> getAndClearResults() {
        synchronized (latestResults) {
            List<RttMeasurement> copy = new ArrayList<>(latestResults);
            latestResults.clear();
            return copy;
        }
    }

    /**
     * Convert a MacAddress to the long integer format used throughout the app.
     */
    private static long macAddressToLong(MacAddress macAddress) {
        if (macAddress == null) return 0;
        byte[] bytes = macAddress.toByteArray();
        long result = 0;
        for (byte b : bytes) {
            result = (result << 8) | (b & 0xFF);
        }
        return result;
    }
}
