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
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
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

    //Time over which a new scan will be initiated
    private static final long SCAN_INTERVAL_MS = 5000;
    private static final long TOAST_DEBOUNCE_MS = 8000;
    private static final String WIFI_CHECK_TAG = "WifiCheck";
    private static boolean sThrottleSettingWarned = false;

    private long lastScanElapsedMs = 0L;
    private String lastToastMsg = "";
    private long lastToastTimeMs = 0L;
    private boolean isWifiReceiverRegistered = false;

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

        // Decreapted method after API 29
        // Turn on wifi if it is currently disabled
        // TODO - turn it to a notification toward user
//      //  if(permissionsGranted && wifiManager.getWifiState()== WifiManager.WIFI_STATE_DISABLED) {
//      //      wifiManager.setWifiEnabled(true);
//      //  }

        // Start wifi scan and return results via broadcast
        if(permissionsGranted) {
            this.scanWifiDataTimer.schedule(new scheduledWifiScan(), 0, SCAN_INTERVAL_MS);
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
        /**
         * Updates the list of nearby wifis when the broadcast is received.
         * Ensures wifi scans are not enabled if permissions are not granted. The list of wifis is
         * then passed to store the Mac Address and strength and observers of the WifiDataProcessor
         * class are notified of the updated wifi list.
         *
         *
         * @param context           Application Context to be used for permissions and device accesses.
         * @param intent            ???.
         */
        @Override
        public void onReceive(Context context, Intent intent) {

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // Unregister this listener
                stopListening();
                return;
            }

            //Collect the list of nearby wifis
            List<ScanResult> wifiScanList = wifiManager.getScanResults();

            //Loop though each item in wifi list
            wifiData = new Wifi[wifiScanList.size()];
            for(int i = 0; i < wifiScanList.size(); i++) {
                wifiData[i] = new Wifi();
                ScanResult result = wifiScanList.get(i);
                //Convert String mac address to an integer
                String wifiMacAddress = result.BSSID;
                long intMacAddress = convertBssidToLong(wifiMacAddress);
                // 存储 MAC 地址与信号强度
                wifiData[i].setBssid(intMacAddress);
                wifiData[i].setLevel(result.level);
                // 统一 SSID / 频点字段，避免下游重复处理
                String ssid = normalizeSsid(result.SSID);
                long frequency = normalizeFrequency(result.frequency);
                boolean rttSupported = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    rttSupported = result.is80211mcResponder();
                }
                wifiData[i].setSsid(ssid);
                wifiData[i].setFrequency(frequency);
                wifiData[i].setRttSupported(rttSupported);
            }

            //Notify observers of change in wifiData variable
            notifyObservers(0);
        }
    };

    /**
     * 将带冒号的 MAC 地址转换为整数表示。
     * 解析失败或空输入返回 0（代表未知），避免异常冒泡。
     *
     * @param wifiMacAddress WiFiManager 返回的带冒号 BSSID
     * @return 十六进制字符串对应的 long 值；无法解析时返回 0
     */
    static long convertBssidToLong(String wifiMacAddress){
        // 允许空或异常输入直接返回 0，防止解析异常导致崩溃
        if (wifiMacAddress == null) {
            return 0L;
        }
        String normalized = wifiMacAddress.replace(":", "").toLowerCase(Locale.US);
        if (normalized.length() != 12) {
            return 0L;
        }
        try {
            return Long.parseLong(normalized, 16);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 标准化 SSID，统一隐藏/空/带引号的场景，避免三处重复代码。
     */
    static String normalizeSsid(String ssid) {
        if (ssid == null || ssid.isEmpty() || "<unknown ssid>".equalsIgnoreCase(ssid)) {
            return "hidden";
        }
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            String trimmed = ssid.substring(1, ssid.length() - 1);
            return trimmed.isEmpty() ? "hidden" : trimmed;
        }
        return ssid;
    }

    /**
     * 标准化频率字段，负值或缺失时返回 0 以保持下游协议一致。
     */
    static long normalizeFrequency(long frequency) {
        return frequency <= 0 ? 0 : frequency;
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
            long now = SystemClock.elapsedRealtime();
            if (now - lastScanElapsedMs < SCAN_INTERVAL_MS) {
                return;
            }
            lastScanElapsedMs = now;

            //if(sharedPreferences.getBoolean("wifi", false)) {
            //Register broadcast receiver for wifi scans
            if (!isWifiReceiverRegistered) {
                context.registerReceiver(wifiScanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
                isWifiReceiverRegistered = true;
            }
            wifiManager.startScan();

            //}
        }
    }

    /**
     * Initiate scans for nearby networks every 5 seconds.
     * The method declares a new timer instance to schedule a scan for nearby wifis every 5 seconds.
     */
    public void startListening() {
        if (this.scanWifiDataTimer != null) {
            // 避免重复创建计时器导致扫描频率翻倍
            this.scanWifiDataTimer.cancel();
        }
        this.scanWifiDataTimer = new Timer();
        this.scanWifiDataTimer.scheduleAtFixedRate(new scheduledWifiScan(), 0, SCAN_INTERVAL_MS);
    }

    /**
     * Cancel wifi scans.
     * The method unregisters the broadcast receiver associated with the wifi scans and cancels the
     * timer so that new scans are not initiated.
     */
    public void stopListening() {
        if (isWifiReceiverRegistered) {
            try {
                context.unregisterReceiver(wifiScanReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered or not registered; ignore.
            }
            isWifiReceiverRegistered = false;
        }
        if (this.scanWifiDataTimer != null) {
            this.scanWifiDataTimer.cancel();
            this.scanWifiDataTimer = null;
        }
    }

    /**
     * Inform user if throttling is resent on their device.
     * If the device supports wifi throttling check if it is enabled and instruct the user to
     * disable it.
     */
    public int checkWifiThrottling(){
        if(checkWifiPermissions()) {
            //If the device does not support wifi throttling an exception is thrown
            try {
                int throttleSetting = Settings.Global.getInt(
                        context.getContentResolver(),
                        "wifi_scan_throttle_enabled");
                if (throttleSetting == 1) {
                    //Inform user to disable wifi throttling
                    showDebouncedToast("Disable Wi-Fi Throttling", Toast.LENGTH_SHORT);
                }
                return throttleSetting;
            } catch (Settings.SettingNotFoundException | SecurityException e) {
                if (!sThrottleSettingWarned) {
                    Log.i(WIFI_CHECK_TAG, "wifi_scan_throttle_enabled not readable; status unknown.");
                    sThrottleSettingWarned = true;
                }
                return -1;
            }
        }
        return -1;
    }

    private void showDebouncedToast(String message, int duration) {
        long now = SystemClock.elapsedRealtime();
        if (message.equals(lastToastMsg) && now - lastToastTimeMs <= TOAST_DEBOUNCE_MS) {
            return;
        }
        lastToastMsg = message;
        lastToastTimeMs = now;
        Toast.makeText(context, message, duration).show();
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
            String ssid = wifiManager.getConnectionInfo().getSSID();
            currentWifi.setSsid(normalizeSsid(ssid));
            String wifiMacAddress = wifiManager.getConnectionInfo().getBSSID();
            long intMacAddress = convertBssidToLong(wifiMacAddress);
            currentWifi.setBssid(intMacAddress);
            long frequency = wifiManager.getConnectionInfo().getFrequency();
            currentWifi.setFrequency(normalizeFrequency(frequency));
            boolean rttSupported = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    List<ScanResult> scanResults = wifiManager.getScanResults();
                    for (ScanResult result : scanResults) {
                        if (result.BSSID != null && result.BSSID.equals(wifiMacAddress)) {
                            rttSupported = result.is80211mcResponder();
                            break;
                        }
                    }
                } catch (SecurityException ignored) {
                    // Permissions might be revoked; fall back to default false.
                }
            }
            currentWifi.setRttSupported(rttSupported);
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
