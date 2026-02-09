package com.openpositioning.PositionMe.sensors;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Handles Wi-Fi data collection using the Android WifiManager.
 * Manages permissions, throttling checks, and periodic scanning.
 * fixed for robustness and privacy changes.
 */
public class WifiDataProcessor implements Observable {

    // Interval for triggering new Wi-Fi scans (ms)
    private static final long scanInterval = 5000;

    private final Context context;
    private final WifiManager wifiManager;

    // Array storing results of the most recent scan
    private Wifi[] wifiData;

    // Observers to notify when new scan results are processed
    private ArrayList<Observer> observers;

    // Timer for scheduling periodic scans
    private Timer scanWifiDataTimer;

    // Flag to ensure the BroadcastReceiver is registered/unregistered safely
    private boolean isReceiverRegistered = false;

    public WifiDataProcessor(Context context) {
        this.context = context;
        // Verify permissions
        boolean permissionsGranted = checkWifiPermissions();
        this.wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        this.scanWifiDataTimer = new Timer();
        this.observers = new ArrayList<>();

        // Start scanning if permissions allow
        if(permissionsGranted) {
            this.scanWifiDataTimer.schedule(new scheduledWifiScan(), 0, scanInterval);
        }

        // Warn user if system Wi-Fi throttling is active
        checkWifiThrottling();
    }

    /**
     * BroadcastReceiver listening for SCAN_RESULTS_AVAILABLE_ACTION.
     * Triggered when the system completes a Wi-Fi scan.
     */
    BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Permission check before accessing results
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            // Safely unregister receiver to prevent leaks or crashes
            try {
                if (isReceiverRegistered) {
                    context.unregisterReceiver(this);
                    isReceiverRegistered = false;
                }
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }

            // Process results regardless of success flag (to retrieve cached data if scan throttled)
            processScanResults();
        }
    };

    /**
     * Retrieves scan results from WifiManager and notifies observers.
     * [Objective b] Captures RTT support and UUID (BSSID).
     */
    private void processScanResults() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        List<ScanResult> wifiScanList = wifiManager.getScanResults();
        if (wifiScanList == null) return;

        wifiData = new Wifi[wifiScanList.size()];
        for(int i = 0; i < wifiScanList.size(); i++) {
            ScanResult result = wifiScanList.get(i);

            String wifiMacAddress = result.BSSID;
            long intMacAddress = convertBssidToLong(wifiMacAddress);

            // [Objective b] Check RTT (802.11mc) support
            boolean isRttSupported = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                isRttSupported = result.is80211mcResponder();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                isRttSupported = result.is80211mcResponder();
            }

            // [Objective b] Use BSSID as UUID (SSID is not unique)
            String uuidStr = (result.BSSID != null) ? result.BSSID : "unknown";

            wifiData[i] = new Wifi();
            wifiData[i].setBssid(intMacAddress);
            wifiData[i].setLevel(result.level);
            wifiData[i].setSsid(result.SSID != null ? result.SSID : ""); // Null safety
            wifiData[i].setFrequency(result.frequency);

            wifiData[i].setUuid(uuidStr);
            wifiData[i].setRtt(isRttSupported);
        }

        // Update observers with new data
        notifyObservers(0);
    }

    /**
     * Helper to convert string MAC address (hex) to long.
     * Includes null check to prevent crashes.
     */
    private long convertBssidToLong(String wifiMacAddress){
        if (wifiMacAddress == null || wifiMacAddress.isEmpty()) {
            return 0;
        }

        long intMacAddress = 0;
        int colonCount = 5;
        // Limit loop to standard MAC length
        int length = Math.min(wifiMacAddress.length(), 17);

        for(int j = 0; j < length; j++){
            char macByte = wifiMacAddress.charAt(j);
            if(macByte != ':'){
                if((int) macByte >= 48 && (int) macByte <= 57){
                    intMacAddress = intMacAddress + (((int)macByte-48)*((long)Math.pow(16,16-j-colonCount)));
                }
                else if ((int) macByte >= 97 && (int) macByte <= 102){
                    intMacAddress = intMacAddress + (((int)macByte-87)*((long)Math.pow(16,16-j-colonCount)));
                }
            }
            else
                colonCount --;
        }
        return intMacAddress;
    }

    /**
     * Verifies that all necessary Location and Wi-Fi permissions are granted.
     */
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

    /**
     * Initiates a Wi-Fi scan by registering the receiver and calling startScan().
     */
    private void startWifiScan() {
        if(checkWifiPermissions()) {
            // Unregister previous receiver if active
            try {
                if (isReceiverRegistered) {
                    context.unregisterReceiver(wifiScanReceiver);
                }
            } catch (Exception e) { e.printStackTrace(); }

            context.registerReceiver(wifiScanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            isReceiverRegistered = true;

            boolean success = wifiManager.startScan();
            if (!success) {
                Log.e("WifiDataProcessor", "Wifi Scan start failed");
            }
        }
    }

    public void startListening() {
        if (this.scanWifiDataTimer != null) {
            this.scanWifiDataTimer.cancel();
        }
        this.scanWifiDataTimer = new Timer();
        this.scanWifiDataTimer.scheduleAtFixedRate(new scheduledWifiScan(), 0, scanInterval);
    }

    public void stopListening() {
        // Safely unregister receiver
        try {
            if (isReceiverRegistered) {
                context.unregisterReceiver(wifiScanReceiver);
                isReceiverRegistered = false;
            }
        } catch (IllegalArgumentException e) {
            Log.e("WifiDataProcessor", "Receiver not registered");
        }

        if (this.scanWifiDataTimer != null) {
            this.scanWifiDataTimer.cancel();
            this.scanWifiDataTimer.purge();
        }
    }

    /**
     * Checks global settings for Wi-Fi Scan Throttling and warns the user if enabled.
     */
    public void checkWifiThrottling(){
        if(checkWifiPermissions()) {
            try {
                if(Settings.Global.getInt(context.getContentResolver(), "wifi_scan_throttle_enabled")==1) {
                    Toast.makeText(context, "Disable Wi-Fi Throttling", Toast.LENGTH_SHORT).show();
                }
            } catch (Settings.SettingNotFoundException e) {
                e.printStackTrace();
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
            o.update(wifiData);
        }
    }

    private class scheduledWifiScan extends TimerTask {
        @Override
        public void run() {
            startWifiScan();
        }
    }

    /**
     * Gets information about the currently connected Wi-Fi network.
     */
    public Wifi getCurrentWifiData(){
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService
                (Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

        Wifi currentWifi = new Wifi();
        // Added null checks for robust connection info retrieval
        if(networkInfo != null && networkInfo.isConnected() && wifiManager.getConnectionInfo() != null) {
            currentWifi.setSsid(wifiManager.getConnectionInfo().getSSID());
            String wifiMacAddress = wifiManager.getConnectionInfo().getBSSID();
            long intMacAddress = convertBssidToLong(wifiMacAddress);
            currentWifi.setBssid(intMacAddress);
            currentWifi.setFrequency(wifiManager.getConnectionInfo().getFrequency());

            // [Objective b] Set default values for current connection
            currentWifi.setUuid(wifiMacAddress != null ? wifiMacAddress : "unknown");
            currentWifi.setRtt(false);
        }
        else{
            currentWifi.setSsid("Not connected");
            currentWifi.setBssid(0);
            currentWifi.setFrequency(0);
        }
        return currentWifi;
    }
}