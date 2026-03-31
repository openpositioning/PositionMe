package com.openpositioning.PositionMe.sensors;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.MacAddress;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.ResponderLocation;
import android.net.wifi.rtt.WifiRttManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

// WifiDataProcessor (Updated for Assignment 1)
// Updates:
// Fixed crash caused by unregistering receiver twice.
// Populates SSID and Frequency in scan results (for Task B).
// Improves MAC address conversion stability.
// Ensures no duplicate BSSIDs in a single scan.
public class WifiDataProcessor implements Observable {

    // Time over which a new scan will be initiated.
    // Reduced to 1 second for faster indoor WiFi positioning updates.
    private static final long scanInterval = 10000;

    // Application context for handling permissions and WifiManager instances
    private final Context context;
    // Wifi manager to enable access to Wifi data via the android system
    private final WifiManager wifiManager;
    private final WifiRttManager wifiRttManager;

    // List of nearby networks
    private Wifi[] wifiData;

    // List of observers to be notified when changes are detected
    private ArrayList<Observer> observers;

    // Timer object
    private Timer scanWifiDataTimer;

    // Fix: Track registration to prevent crash
    private boolean isReceiverRegistered = false;
    private boolean isRttRanging = false;

    // Public default constructor of the WifiDataProcessor class.
    public WifiDataProcessor(Context context) {
        this.context = context;
        // Check for permissions
        boolean permissionsGranted = checkWifiPermissions();
        this.wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        this.wifiRttManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? (WifiRttManager) context.getSystemService(Context.WIFI_RTT_RANGING_SERVICE)
                : null;
        this.scanWifiDataTimer = new Timer();
        this.observers = new ArrayList<>();

        // Start wifi scan and return results via broadcast
        if(permissionsGranted) {
            // Schedule the first scan immediately (0 delay)
            this.scanWifiDataTimer.schedule(new scheduledWifiScan(), 0, scanInterval);
        }

        // Inform the user if wifi throttling is enabled on their device
        checkWifiThrottling();
    }

    // Broadcast receiver to receive updates from the wifi manager.
    BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Safety check for permissions
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            // Unregister receiver immediately to prevent leaks, but check flag first
            // Note: In this design, we register for EACH scan and unregister immediately after.
            try {
                if (isReceiverRegistered) {
                    context.unregisterReceiver(this);
                    isReceiverRegistered = false;
                }
            } catch (IllegalArgumentException e) {
                // Ignore if already unregistered
            }

            // Check for success
            boolean success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            if (success) {
                processScanResults();
            } else {
                // Some devices report scan failure under throttling but still keep recent cached results.
                Log.w("WifiDataProcessor", "Scan failure received. Falling back to cached scan results.");
                processScanResults();
            }
        }
    };

    // Process the successful scan results.
    private void processScanResults() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        List<ScanResult> wifiScanList = wifiManager.getScanResults();

        // Use a set to filter duplicates based on BSSID
        List<Wifi> uniqueWifiList = new ArrayList<>();
        Set<String> seenBssids = new HashSet<>();

        for (ScanResult result : wifiScanList) {
            // Skip if we've already seen this MAC in this batch
            if (seenBssids.contains(result.BSSID)) {
                continue;
            }
            seenBssids.add(result.BSSID);

            Wifi wifi = new Wifi();

            // MAC / BSSID
            long intMacAddress = convertBssidToLong(result.BSSID);
            wifi.setBssid(intMacAddress);

            // RSSI / Level
            wifi.setLevel(result.level);


            wifi.setSsid(result.SSID != null ? result.SSID : "");


            wifi.setFrequency(result.frequency);

            uniqueWifiList.add(wifi);
        }

        // Convert List to Array for compatibility with existing Observer interface
        wifiData = uniqueWifiList.toArray(new Wifi[0]);

        // Notify observers of change
        notifyObservers(0);

        startRttAltitudeRanging(wifiScanList);
    }

    // Converts mac address from string to integer (Robust version).
    private long convertBssidToLong(String wifiMacAddress){
        if (wifiMacAddress == null || wifiMacAddress.isEmpty()) return 0;
        try {
            // Remove colons and parse as hex
            String hex = wifiMacAddress.replace(":", "").replace("-", "").trim();
            // Use Long.parseUnsignedLong to handle large MAC values correctly
            return Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException e) {
            // Fallback or log error
            Log.e("WifiDataProcessor", "Error parsing MAC: " + wifiMacAddress);
            return 0;
        }
    }

    // Checks if the user authorised all permissions necessary for accessing wifi data.
    private boolean checkWifiPermissions() {
        int wifiAccessPermission = ActivityCompat.checkSelfPermission(this.context,
                Manifest.permission.ACCESS_WIFI_STATE);
        int wifiChangePermission = ActivityCompat.checkSelfPermission(this.context,
                Manifest.permission.CHANGE_WIFI_STATE);
        int coarseLocationPermission = ActivityCompat.checkSelfPermission(this.context,
                Manifest.permission.ACCESS_COARSE_LOCATION);
        int fineLocationPermission = ActivityCompat.checkSelfPermission(this.context,
                Manifest.permission.ACCESS_FINE_LOCATION);

        return wifiAccessPermission == PackageManager.PERMISSION_GRANTED &&
                wifiChangePermission == PackageManager.PERMISSION_GRANTED &&
                coarseLocationPermission == PackageManager.PERMISSION_GRANTED &&
                fineLocationPermission == PackageManager.PERMISSION_GRANTED;
    }

    // Scan for nearby networks.
    private void startWifiScan() {
        if(checkWifiPermissions()) {
            try {
                // Register receiver ONLY if not already registered
                if (!isReceiverRegistered) {
                    context.registerReceiver(wifiScanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
                    isReceiverRegistered = true;
                }
                boolean started = wifiManager.startScan();
                if (!started) {
                    // If startScan is throttled, use last available scan cache to keep pipeline alive.
                    Log.d("WifiDataProcessor", "Wifi Scan start failed (likely throttled), using cached results");
                    processScanResults();
                }
            } catch (Exception e) {
                Log.e("WifiDataProcessor", "Error starting scan: " + e.getMessage());
                isReceiverRegistered = false; // Reset flag on error
            }
        }
    }

    private void startRttAltitudeRanging(List<ScanResult> wifiScanList) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || wifiRttManager == null || isRttRanging) {
            return;
        }

        if (!wifiRttManager.isAvailable()) {
            return;
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        List<ScanResult> responders = new ArrayList<>();
        for (ScanResult result : wifiScanList) {
            if (result != null && result.BSSID != null && result.is80211mcResponder()) {
                responders.add(result);
            }
            if (responders.size() >= 8) {
                break;
            }
        }

        if (responders.isEmpty()) {
            return;
        }

        isRttRanging = true;
        RangingRequest request = new RangingRequest.Builder()
                .addAccessPoints(responders)
                .build();

        try {
            wifiRttManager.startRanging(request, context.getMainExecutor(), new RangingResultCallback() {
                @Override
                public void onRangingResults(List<RangingResult> results) {
                    isRttRanging = false;
                    applyRttAltitudeResults(results);
                }

                @Override
                public void onRangingFailure(int code) {
                    isRttRanging = false;
                    Log.w("WifiDataProcessor", "Wi-Fi RTT ranging failed: " + code);
                }
            });
        } catch (SecurityException e) {
            isRttRanging = false;
            Log.w("WifiDataProcessor", "Wi-Fi RTT permission denied: " + e.getMessage());
        } catch (Exception e) {
            isRttRanging = false;
            Log.w("WifiDataProcessor", "Wi-Fi RTT unavailable: " + e.getMessage());
        }
    }

    private void applyRttAltitudeResults(List<RangingResult> results) {
        if (wifiData == null || results == null || results.isEmpty()) {
            return;
        }

        boolean updated = false;
        for (RangingResult result : results) {
            if (result == null || result.getStatus() != RangingResult.STATUS_SUCCESS) {
                continue;
            }

            ResponderLocation responderLocation = result.getUnverifiedResponderLocation();
            if (responderLocation == null) {
                continue;
            }

            double altitude;
            try {
                altitude = responderLocation.getAltitude();
            } catch (Exception e) {
                continue;
            }
            if (Double.isNaN(altitude) || Double.isInfinite(altitude)) {
                continue;
            }

            MacAddress macAddress = result.getMacAddress();
            if (macAddress == null) {
                continue;
            }

            long bssid = convertBssidToLong(macAddress.toString());
            for (Wifi wifi : wifiData) {
                if (wifi.getBssid() == bssid) {
                    wifi.setRttAltitudeMeters((float) altitude);
                    updated = true;
                    break;
                }
            }
        }

        if (updated) {
            notifyObservers(0);
        }
    }

    // Initiate scans for nearby networks every 5 seconds.
    public void startListening() {
        // Cancel existing timer if any to avoid duplicates
        if (this.scanWifiDataTimer != null) {
            this.scanWifiDataTimer.cancel();
        }
        this.scanWifiDataTimer = new Timer();
        this.scanWifiDataTimer.scheduleAtFixedRate(new scheduledWifiScan(), 0, scanInterval);
    }

    // Cancel wifi scans.
    public void stopListening() {
        // Safe unregister
        try {
            if (isReceiverRegistered) {
                context.unregisterReceiver(wifiScanReceiver);
                isReceiverRegistered = false;
            }
        } catch (IllegalArgumentException e) {
            // Ignore if not registered
        }

        if (this.scanWifiDataTimer != null) {
            this.scanWifiDataTimer.cancel();
            this.scanWifiDataTimer = null; // Prevent reuse
        }
    }

    // Inform user if throttling is present.
    public void checkWifiThrottling(){
        if(checkWifiPermissions()) {
            try {
                // Check if throttling is enabled (API 28+)
                // Note: This setting might not be readable on all devices/versions without special permissions,
                // but we keep the try-catch block as in original.
                if(Settings.Global.getInt(context.getContentResolver(), "wifi_scan_throttle_enabled") == 1) {
                    Toast.makeText(context, "Disable Wi-Fi Throttling in Dev Options", Toast.LENGTH_LONG).show();
                }
            } catch (Settings.SettingNotFoundException e) {
                // Setting not found, ignore
            }
        }
    }

    @Override
    public void registerObserver(Observer o) {
        observers.add(o);
    }

    @Override
    public void notifyObservers(int idx) {
        for(Observer o : observers) {
            // Make a copy or pass the array.
            // wifiData might be null if no scan has finished yet.
            if (wifiData != null) {
                o.update(wifiData);
            }
        }
    }

    private class scheduledWifiScan extends TimerTask {
        @Override
        public void run() {
            startWifiScan();
        }
    }

    // Obtains required information about wifi in which the device is currently connected.
    public Wifi getCurrentWifiData(){
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService
                (Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

        Wifi currentWifi = new Wifi();

        if(networkInfo != null && networkInfo.isConnected()) {
            // Store the ssid, mac address and frequency of the current wifi
            // Use standard API safely
            try {
                android.net.wifi.WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null) {
                    // SSID usually comes with quotes, keep them or strip them as needed.
                    // Original code kept them, so we keep them.
                    currentWifi.setSsid(info.getSSID());
                    currentWifi.setBssid(convertBssidToLong(info.getBSSID()));
                    currentWifi.setFrequency(info.getFrequency());
                }
            } catch (Exception e) {
                Log.e("WifiDataProcessor", "Error getting connection info");
            }
        }
        else {
            currentWifi.setSsid("Not connected");
            currentWifi.setBssid(0);
            currentWifi.setFrequency(0);
        }
        return currentWifi;
    }
}


