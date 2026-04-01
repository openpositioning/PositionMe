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
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

/**
 * The WifiDataProcessor class is the Wi-Fi data gathering and processing class of the application.
 * It implements the wifi scanning and broadcasting design to identify a list of nearby Wi-Fis as
 * well as collecting information about the current Wi-Fi connection.
 * <p>
 * The class implements {@link Observable} for informing {@link Observer} classes of updated
 * variables. As such, it implements the {@link WifiDataProcessor#notifyObservers(int idx)} function and
 * the {@link WifiDataProcessor#registerObserver(Observer o)} function to add new users which will
 * be notified of new changes.
 * <p>
 * The class ensures all required permissions are granted before enabling the Wi-Fi. The class will
 * periodically start a wifi scan as determined by {@link SensorFusion}. When a broadcast is
 * received it will collect a list of users and notify users. The
 * {@link WifiDataProcessor#getCurrentWifiData()} function will return information about the current
 * Wi-Fi when called by {@link SensorFusion}.
 *
 * @author Mate Stodulka
 * @author Virginia Cangelosi
 */
public class WifiDataProcessor implements Observable {

    private static final long DEFAULT_SCAN_INTERVAL_MS = 5000L;
    private static final long MIN_SCAN_INTERVAL_MS = 2500L;
    private static final long MAX_SCAN_INTERVAL_MS = 30000L;
    private static final long DUPLICATE_SCAN_SUPPRESSION_MS = 2000L;
    private static final String TAG = "WifiDataProcessor";

    // Application context for handling permissions and WifiManager instances
    private final Context context;
    // Locations manager to enable access to Wifi data via the android system
    private final WifiManager wifiManager;

    //List of nearby networks
    private Wifi[] wifiData;

    //List of observers to be notified when changes are detected
    private ArrayList<Observer> observers;

    // Timer object
    private Timer scanWifiDataTimer;
    private boolean receiverRegistered = false;
    private final Object receiverLock = new Object();
    private long configuredScanIntervalMs = DEFAULT_SCAN_INTERVAL_MS;
    private String lastPublishedScanSignature = "";
    private long lastPublishedScanTimeMs = 0L;

    /**
     * Public default constructor of the WifiDataProcessor class.
     * The constructor saves the context, checks for permissions to use the location services,
     * creates an instance of the shared preferences to access settings using the context,
     * initialises the wifi manager, and creates a timer object and list of observers. It checks if
     * wifi is enabled and enables wifi scans every 5seconds. It also informs the user to disable
     * wifi throttling if the device implements it.
     *
     * @param context           Application Context to be used for permissions and device accesses.
     *
     * @see SensorFusion the intended parent class.
     *
     * @author Virginia Cangelosi
     * @author Mate Stodulka
     */
    public WifiDataProcessor(Context context) {
        this.context = context;
        // Check for permissions
        boolean permissionsGranted = checkWifiPermissions();
        this.wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        this.scanWifiDataTimer = new Timer();
        this.observers = new ArrayList<>();
        this.configuredScanIntervalMs = resolveScanIntervalMs();

        // Decreapted method after API 29
        // Turn on wifi if it is currently disabled
        // TODO - turn it to a notification toward user
//      //  if(permissionsGranted && wifiManager.getWifiState()== WifiManager.WIFI_STATE_DISABLED) {
//      //      wifiManager.setWifiEnabled(true);
//      //  }

        // Start wifi scan and return results via broadcast
        if(permissionsGranted) {
            registerReceiverIfNeeded();
            this.scanWifiDataTimer.scheduleAtFixedRate(
                    new scheduledWifiScan(),
                    0,
                    configuredScanIntervalMs
            );
        }

        //Inform the user if wifi throttling is enabled on their device
        checkWifiThrottling();
    }

    /**
     * Broadcast receiver to receive updates from the wifi manager.
     * Receives updates when a wifi scan is complete. Observers are notified when the broadcast is
     * received to update the list of wifis
     */

    BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            boolean success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            if (!success) {
                Log.d(TAG, "SCAN_RESULTS_AVAILABLE received without updated flag; publishing latest cached scan.");
            }
            publishLatestScanResults();
        }
    };
    /**
     * Converts mac address from string to integer.
     * Removes semicolons from mac address and converts each hex byte to a hex integer.
     *
     *
     * @param wifiMacAddress        String Mac Address received from WifiManager containing colons
     *
     * @return                      Long variable with decimal conversion of the mac address
     */
    private long convertBssidToLong(String wifiMacAddress){
        if (wifiMacAddress == null || wifiMacAddress.isEmpty()) return 0;
        try {
            String hex = wifiMacAddress.replace(":", "");
            return Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void publishLatestScanResults() {
        if (!checkWifiPermissions()) {
            return;
        }

        List<ScanResult> wifiScanList;
        try {
            wifiScanList = wifiManager.getScanResults();
        } catch (SecurityException e) {
            Log.w(TAG, "No permission to access scan results.", e);
            return;
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to read Wi-Fi scan results.", e);
            return;
        }

        Map<Long, Wifi> uniqueWifiMap = new HashMap<>();
        if (wifiScanList != null) {
            for (ScanResult result : wifiScanList) {
                if (result == null) {
                    continue;
                }
                long bssidLong = convertBssidToLong(result.BSSID);
                if (!uniqueWifiMap.containsKey(bssidLong)) {
                    Wifi wifi = new Wifi();
                    wifi.setSsid(result.SSID);
                    wifi.setBssid(bssidLong);
                    wifi.setLevel(result.level);
                    wifi.setFrequency(result.frequency);

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        wifi.setRttFlag(result.is80211mcResponder());
                    } else {
                        wifi.setRttFlag(false);
                    }
                    wifi.setUuid(UUID.randomUUID().toString());
                    uniqueWifiMap.put(bssidLong, wifi);
                }
            }
        }

        Wifi currentConnectedWifi = getCurrentWifiData();
        if (currentConnectedWifi.getBssid() != 0) {
            long currentBssid = currentConnectedWifi.getBssid();
            if (!uniqueWifiMap.containsKey(currentBssid)) {
                if (currentConnectedWifi.getUuid() == null) {
                    currentConnectedWifi.setUuid(UUID.randomUUID().toString());
                }
                uniqueWifiMap.put(currentBssid, currentConnectedWifi);
            }
        }

        List<Wifi> orderedWifi = new ArrayList<>(uniqueWifiMap.values());
        orderedWifi.sort((left, right) -> {
            int levelCompare = Integer.compare(right.getLevel(), left.getLevel());
            if (levelCompare != 0) {
                return levelCompare;
            }
            return Long.compare(right.getFrequency(), left.getFrequency());
        });
        long nowMs = System.currentTimeMillis();
        String scanSignature = buildScanSignature(orderedWifi);
        if (!scanSignature.isEmpty()
                && scanSignature.equals(lastPublishedScanSignature)
                && (nowMs - lastPublishedScanTimeMs) < DUPLICATE_SCAN_SUPPRESSION_MS) {
            return;
        }

        lastPublishedScanSignature = scanSignature;
        lastPublishedScanTimeMs = nowMs;
        wifiData = orderedWifi.toArray(new Wifi[0]);
        notifyObservers(0);
    }

    private String buildScanSignature(List<Wifi> orderedWifi) {
        if (orderedWifi == null || orderedWifi.isEmpty()) {
            return "";
        }
        StringBuilder signature = new StringBuilder(orderedWifi.size() * 24);
        for (Wifi wifi : orderedWifi) {
            if (wifi == null) {
                continue;
            }
            signature.append(wifi.getBssid())
                    .append(':')
                    .append(wifi.getLevel())
                    .append(':')
                    .append(wifi.getFrequency())
                    .append(';');
        }
        return signature.toString();
    }

    private long resolveScanIntervalMs() {
        long intervalMs = DEFAULT_SCAN_INTERVAL_MS;
        try {
            boolean overwriteConstants = PreferenceManager
                    .getDefaultSharedPreferences(context)
                    .getBoolean("overwrite_constants", false);
            if (overwriteConstants) {
                String value = PreferenceManager
                        .getDefaultSharedPreferences(context)
                        .getString("wifi_interval", "5");
                long seconds = Long.parseLong(value);
                intervalMs = seconds * 1000L;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Invalid Wi-Fi scan interval preference. Falling back to default.", e);
            intervalMs = DEFAULT_SCAN_INTERVAL_MS;
        }
        return Math.max(MIN_SCAN_INTERVAL_MS, Math.min(MAX_SCAN_INTERVAL_MS, intervalMs));
    }

    /**
     * Checks if the user authorised all permissions necessary for accessing wifi data.
     * Explicit user permissions must be granted for android sdk version 23 and above. This
     * function checks which permissions are granted, and returns their conjunction.
     *
     * @return  boolean true if all permissions are granted for wifi access, false otherwise.
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

        // Return missing permissions
        return wifiAccessPermission == PackageManager.PERMISSION_GRANTED &&
                wifiChangePermission == PackageManager.PERMISSION_GRANTED &&
                coarseLocationPermission == PackageManager.PERMISSION_GRANTED &&
                fineLocationPermission == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Scan for nearby networks.
     * The method checks for permissions again, and then requests a scan of nearby wifis. A
     * broadcast receiver is registered to be called when the scan is complete.
     */
    private void startWifiScan() {
        //Check settings for wifi permissions
        if(checkWifiPermissions()) {
            try {
                boolean started = wifiManager.startScan();
                if (!started) {
                    // startScan can be throttled; still publish the latest known scan list.
                    Log.d(TAG, "Wi-Fi scan request throttled; using latest cached scan results.");
                    publishLatestScanResults();
                }
            } catch (SecurityException e) {
                Log.w(TAG, "Wi-Fi scan start failed due to permission.", e);
            } catch (RuntimeException e) {
                Log.w(TAG, "Wi-Fi scan start failed.", e);
            }
        }
    }

    /**
     * Initiate scans for nearby networks every 5 seconds.
     * The method declares a new timer instance to schedule a scan for nearby wifis every 5 seconds.
     */
    public void startListening() {
        if (!checkWifiPermissions()) {
            return;
        }
        configuredScanIntervalMs = resolveScanIntervalMs();
        registerReceiverIfNeeded();
        if (this.scanWifiDataTimer != null) {
            this.scanWifiDataTimer.cancel();
        }
        this.scanWifiDataTimer = new Timer();
        this.scanWifiDataTimer.scheduleAtFixedRate(
                new scheduledWifiScan(),
                0,
                configuredScanIntervalMs
        );
        publishLatestScanResults();
    }

    /**
     * Cancel wifi scans.
     * The method unregisters the broadcast receiver associated with the wifi scans and cancels the
     * timer so that new scans are not initiated.
     */
    public void stopListening() {
        unregisterReceiverIfNeeded();
        if (this.scanWifiDataTimer != null) {
            this.scanWifiDataTimer.cancel();
            this.scanWifiDataTimer = null;
        }
    }

    private void registerReceiverIfNeeded() {
        synchronized (receiverLock) {
            if (receiverRegistered) {
                return;
            }
            try {
                context.registerReceiver(
                        wifiScanReceiver,
                        new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                );
                receiverRegistered = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to register Wi-Fi scan receiver.", e);
            }
        }
    }

    private void unregisterReceiverIfNeeded() {
        synchronized (receiverLock) {
            if (!receiverRegistered) {
                return;
            }
            try {
                context.unregisterReceiver(wifiScanReceiver);
            } catch (IllegalArgumentException e) {
                // Ignore stale unregister requests.
            } finally {
                receiverRegistered = false;
            }
        }
    }

    /**
     * Inform user if throttling is resent on their device.
     * If the device supports wifi throttling check if it is enabled and instruct the user to
     * disable it.
     */
    public void checkWifiThrottling(){
        if(checkWifiPermissions()) {
            //If the device does not support wifi throttling an exception is thrown
            try {
                if(Settings.Global.getInt(context.getContentResolver(), "wifi_scan_throttle_enabled")==1) {
                    //Inform user to disable wifi throttling
                    Toast.makeText(context, "Disable Wi-Fi Throttling", Toast.LENGTH_SHORT).show();
                }
            } catch (Settings.SettingNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Implement default method from Observable Interface to add new observers to the class.
     *
     * @param o     Classes which implement the Observer interface to receive updates from the class.
     */
    @Override
    public void registerObserver(Observer o) {
        observers.add(o);
    }

    /**
     * Implement default method from Observable Interface to add notify observers to the class.
     * Changes to the wifiData variable are passed to observers of the class.
     * @param idx     Unused.
     */
    @Override
    public void notifyObservers(int idx) {
        for(Observer o : observers) {
            o.update(wifiData);
        }
    }

    /**
     * Class to schedule wifi scans.
     *
     * Implements default method in {@link TimerTask} class which it implements. It begins to start
     * calling wifi scans every 5 seconds.
     */
    private class scheduledWifiScan extends TimerTask {

        @Override
        public void run() {
            startWifiScan();
        }
    }

    /**
     * Obtains required information about wifi in which the device is currently connected.
     *
     * A connectivity manager is used to obtain information about the current network. If the device
     * is connected to a network its ssid, mac address and frequency is stored to a Wifi object so
     * that it can be accessed by the caller of the method
     *
     * @return wifi object containing the currently connected wifi's ssid, mac address and frequency
     */
    public Wifi getCurrentWifiData(){
        //Set up a connectivity manager to get information about the wifi
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService
                (Context.CONNECTIVITY_SERVICE);
        //Set up a network info object to store information about the current network
        NetworkInfo networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

        //Only obtain wifi data if the device is connected
        //Wifi in which the device is currently connected to
        Wifi currentWifi = new Wifi();
        if(networkInfo != null && networkInfo.isConnected()) {
            //Store the ssid, mac address and frequency of the current wifi
            currentWifi.setSsid(wifiManager.getConnectionInfo().getSSID());
            String wifiMacAddress = wifiManager.getConnectionInfo().getBSSID();

            long intMacAddress = convertBssidToLong(wifiMacAddress);

            currentWifi.setBssid(intMacAddress);
            currentWifi.setFrequency(wifiManager.getConnectionInfo().getFrequency());
            currentWifi.setLevel(wifiManager.getConnectionInfo().getRssi());

        }
        else{
            //Store standard information if not connected
            currentWifi.setSsid("Not connected");
            currentWifi.setBssid(0);
            currentWifi.setFrequency(0);
        }
        return currentWifi;
    }
}
