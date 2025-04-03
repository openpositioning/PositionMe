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
import android.widget.Toast;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * The WifiDataProcessor class is the Wi-Fi data gathering and processing class of the application.
 * It implements the wifi scanning and broadcasting design to identify a list of nearby Wi-Fis as
 * well as collecting information about the current Wi-Fi connection.
 *
 * The class implements {@link Observable} for informing {@link Observer} classes of updated
 * variables. As such, it implements the {@link WifiDataProcessor#notifyObservers(int idx)} function and
 * the {@link WifiDataProcessor#registerObserver(Observer o)} function to add new users which will
 * be notified of new changes.
 *
 * The class ensures all required permissions are granted before enabling the Wi-Fi. The class will
 * periodically start a wifi scan as determined by {@link SensorFusion}. When a broadcast is
 * received it will collect a list of wifis and notify users. The
 * {@link WifiDataProcessor#getCurrentWifiData()} function will return information about the current
 * Wi-Fi when called by {@link SensorFusion}.
 *
 * @author Mate Stodulka
 * @author Virginia Cangelosi
 */
public class WifiDataProcessor implements Observable {

    // Time interval (in ms) over which a new scan will be initiated.
    private static final long scanInterval = 5000;

    // Application context for handling permissions and WifiManager instances.
    private final Context context;
    // WifiManager instance to perform scans.
    private final WifiManager wifiManager;

    // List of nearby networks.
    private Wifi[] wifiData;

    // List of observers to be notified when changes are detected.
    private ArrayList<Observer> observers;

    // Timer object for scheduling scans.
    private Timer scanWifiDataTimer;

    /**
     * Public default constructor of the WifiDataProcessor class.
     *
     * @param context Application Context to be used for permissions and device accesses.
     */
    public WifiDataProcessor(Context context) {
        this.context = context;
        // Check for permissions.
        boolean permissionsGranted = checkWifiPermissions();
        this.wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        this.scanWifiDataTimer = new Timer();
        this.observers = new ArrayList<>();
        // Turn on WiFi if it is currently disabled.
        if (permissionsGranted && wifiManager.getWifiState() == WifiManager.WIFI_STATE_DISABLED) {
            wifiManager.setWifiEnabled(true);
        }

        // Start wifi scans periodically.
        if (permissionsGranted) {
            this.scanWifiDataTimer.scheduleAtFixedRate(new scheduledWifiScan(), 0, scanInterval);
        }

        // Inform the user if WiFi throttling is enabled on their device.
        checkWifiThrottling();
    }

    /**
     * Broadcast receiver to receive updates from the WifiManager.
     * Receives updates when a wifi scan is complete. Observers are notified when the broadcast is
     * received to update the list of WiFis.
     */
    BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // Unregister this listener if permissions are not granted.
                stopListening();
                return;
            }

            // Collect the list of nearby WiFis.
            List<ScanResult> wifiScanList = wifiManager.getScanResults();
            // Log each scan result for debugging.
            for (ScanResult scanResult : wifiScanList) {
                Log.d("WifiDataProcessor", "ScanResult - BSSID: " + scanResult.BSSID + ", RSSI: " + scanResult.level);
            }
            // Unregister the receiver once the scan is complete.
            context.unregisterReceiver(this);

            // Loop through each item in the WiFi list.
            wifiData = new Wifi[wifiScanList.size()];
            for (int i = 0; i < wifiScanList.size(); i++) {
                wifiData[i] = new Wifi();
                // Convert String MAC address to an integer.
                String wifiMacAddress = wifiScanList.get(i).BSSID;
                long intMacAddress = convertBssidToLong(wifiMacAddress);
                // Store MAC address and RSSI (signal level) of the WiFi.
                wifiData[i].setBssid(intMacAddress);
                wifiData[i].setLevel(wifiScanList.get(i).level);
            }

            // Notify observers of change in wifiData variable.
            notifyObservers(0);
        }
    };

    /**
     * Converts MAC address from string to integer.
     *
     * @param wifiMacAddress String MAC address from WifiManager (with colons).
     * @return Long integer conversion of the MAC address.
     */
    private long convertBssidToLong(String wifiMacAddress) {
        long intMacAddress = 0;
        int colonCount = 5;
        // Loop through each character in the MAC address string.
        for (int j = 0; j < 17; j++) {
            char macByte = wifiMacAddress.charAt(j);
            if (macByte != ':') {
                if ((int) macByte >= 48 && (int) macByte <= 57) {
                    intMacAddress += (((int) macByte - 48) * ((long) Math.pow(16, 16 - j - colonCount)));
                } else if ((int) macByte >= 97 && (int) macByte <= 102) {
                    intMacAddress += (((int) macByte - 87) * ((long) Math.pow(16, 16 - j - colonCount)));
                }
            } else {
                colonCount--;
            }
        }
        return intMacAddress;
    }

    /**
     * Checks if the required WiFi permissions are granted.
     *
     * @return true if all permissions are granted, false otherwise.
     */
    private boolean checkWifiPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            int wifiAccessPermission = ActivityCompat.checkSelfPermission(this.context, Manifest.permission.ACCESS_WIFI_STATE);
            int wifiChangePermission = ActivityCompat.checkSelfPermission(this.context, Manifest.permission.CHANGE_WIFI_STATE);
            int coarseLocationPermission = ActivityCompat.checkSelfPermission(this.context, Manifest.permission.ACCESS_COARSE_LOCATION);
            int fineLocationPermission = ActivityCompat.checkSelfPermission(this.context, Manifest.permission.ACCESS_FINE_LOCATION);

            return wifiAccessPermission == PackageManager.PERMISSION_GRANTED &&
                    wifiChangePermission == PackageManager.PERMISSION_GRANTED &&
                    coarseLocationPermission == PackageManager.PERMISSION_GRANTED &&
                    fineLocationPermission == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    /**
     * Initiates a WiFi scan by registering the broadcast receiver and calling startScan().
     */
    private void startWifiScan() {
        if (checkWifiPermissions()) {
            context.registerReceiver(wifiScanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            wifiManager.startScan();
        }
    }

    /**
     * Starts listening for WiFi scans by scheduling them every scanInterval.
     */
    public void startListening() {
        this.scanWifiDataTimer = new Timer();
        this.scanWifiDataTimer.scheduleAtFixedRate(new scheduledWifiScan(), 0, scanInterval);
    }

    /**
     * Stops listening for WiFi scans by unregistering the broadcast receiver and canceling the timer.
     */
    public void stopListening() {
        context.unregisterReceiver(wifiScanReceiver);
        this.scanWifiDataTimer.cancel();
    }

    /**
     * Checks if WiFi throttling is enabled on the device and displays a Toast if it is.
     */
    public void checkWifiThrottling() {
        if (checkWifiPermissions()) {
            try {
                if (Settings.Global.getInt(context.getContentResolver(), "wifi_scan_throttle_enabled") == 1) {
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
        for (Observer o : observers) {
            o.update(wifiData);
        }
    }

    /**
     * TimerTask to schedule WiFi scans.
     */
    private class scheduledWifiScan extends TimerTask {
        @Override
        public void run() {
            startWifiScan();
        }
    }

    /**
     * Returns information about the currently connected WiFi network.
     *
     * @return a Wifi object with SSID, BSSID, and frequency.
     */
    public Wifi getCurrentWifiData() {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

        Wifi currentWifi = new Wifi();
        if (networkInfo.isConnected()) {
            currentWifi.setSsid(wifiManager.getConnectionInfo().getSSID());
            String wifiMacAddress = wifiManager.getConnectionInfo().getBSSID();
            long intMacAddress = convertBssidToLong(wifiMacAddress);
            currentWifi.setBssid(intMacAddress);
            currentWifi.setFrequency(wifiManager.getConnectionInfo().getFrequency());
            // Also, set the level from the connection info (if available).
            currentWifi.setLevel(wifiManager.getConnectionInfo().getRssi());
        } else {
            currentWifi.setSsid("Not connected");
            currentWifi.setBssid(0);
            currentWifi.setFrequency(0);
            currentWifi.setLevel(0);
        }
        return currentWifi;
    }

    public List<Wifi> getWifiList() {
        List<Wifi> list = new ArrayList<>();
        if (wifiData != null) {
            for (Wifi w : wifiData) {
                list.add(w);
            }
        }
        return list;
    }

}
