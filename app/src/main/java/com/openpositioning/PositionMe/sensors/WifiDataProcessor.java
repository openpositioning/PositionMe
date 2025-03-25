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
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * The WifiDataProcessor class is the Wi-Fi data gathering and processing class of the application.
 * It implements the wifi scanning and broadcasting design to identify a list of nearby Wi-Fis as
 * well as collecting information about the current Wi-Fi connection.
 */
public class WifiDataProcessor implements Observable {

    private static final String TAG = "WifiDataProcessor";
    
    // 扫描相关参数
    private static final long DEFAULT_SCAN_INTERVAL = 5000;
    private static final long MAX_SCAN_INTERVAL = 30000;
    private static final long MIN_SCAN_INTERVAL = 3000;
    
    // 信号过滤参数
    private static final int MIN_RSSI_LEVEL = -85;
    private static final int MAX_RESULTS_COUNT = 20;
    
    private final Context context;
    private final WifiManager wifiManager;

    private Wifi[] wifiData;
    private Map<Long, List<Wifi>> wifiDataHistory;
    
    private ArrayList<Observer> observers;

    private Timer scanWifiDataTimer;
    private Handler mainHandler;
    
    private long currentScanInterval = DEFAULT_SCAN_INTERVAL;
    private int consecutiveFailures = 0;
    private boolean isScanning = false;
    private boolean isBatteryLow = false;

    /**
     * Public default constructor of the WifiDataProcessor class.
     */
    public WifiDataProcessor(Context context) {
        this.context = context;
        boolean permissionsGranted = checkWifiPermissions();
        this.wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        this.scanWifiDataTimer = new Timer();
        this.observers = new ArrayList<>();
        this.wifiDataHistory = new HashMap<>();
        this.mainHandler = new Handler(context.getMainLooper());

        if(permissionsGranted) {
            this.scanWifiDataTimer.schedule(new scheduledWifiScan(), 0, currentScanInterval);
        }

        checkWifiThrottling();
        registerBatteryReceiver();
    }

    /**
     * Broadcast receiver to receive updates from the wifi manager.
     */
    BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isScanning = false;
            
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                stopListening();
                return;
            }

            boolean success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            if (!success) {
                handleScanFailure();
                return;
            }

            List<ScanResult> wifiScanList = wifiManager.getScanResults();
            context.unregisterReceiver(this);
            
            wifiScanList = filterAndSortScanResults(wifiScanList);
            processScanResults(wifiScanList);
            
            consecutiveFailures = 0;
            adjustScanInterval();
        }
    };
    
    /**
     * 过滤和排序扫描结果
     */
    private List<ScanResult> filterAndSortScanResults(List<ScanResult> scanResults) {
        List<ScanResult> filteredResults = new ArrayList<>();
        for (ScanResult result : scanResults) {
            if (result.level >= MIN_RSSI_LEVEL) {
                filteredResults.add(result);
            }
        }
        
        Collections.sort(filteredResults, new Comparator<ScanResult>() {
            @Override
            public int compare(ScanResult r1, ScanResult r2) {
                return r2.level - r1.level;
            }
        });
        
        if (filteredResults.size() > MAX_RESULTS_COUNT) {
            filteredResults = filteredResults.subList(0, MAX_RESULTS_COUNT);
        }
        
        return filteredResults;
    }
    
    /**
     * 处理扫描结果
     */
    private void processScanResults(List<ScanResult> wifiScanList) {
        wifiData = new Wifi[wifiScanList.size()];
        for(int i = 0; i < wifiScanList.size(); i++) {
            wifiData[i] = new Wifi();
            String wifiMacAddress = wifiScanList.get(i).BSSID;
            long intMacAddress = convertBssidToLong(wifiMacAddress);
            
            wifiData[i].setBssid(intMacAddress);
            wifiData[i].setLevel(wifiScanList.get(i).level);
            wifiData[i].setSsid(wifiScanList.get(i).SSID);
            wifiData[i].setFrequency(wifiScanList.get(i).frequency);
            wifiData[i].setCapabilities(wifiScanList.get(i).capabilities);
            
            updateWifiHistory(wifiData[i]);
        }

        notifyObservers(0);
    }
    
    /**
     * 更新WiFi历史数据
     */
    private void updateWifiHistory(Wifi wifi) {
        if (!wifiDataHistory.containsKey(wifi.getBssid())) {
            wifiDataHistory.put(wifi.getBssid(), new ArrayList<Wifi>());
        }
        
        List<Wifi> history = wifiDataHistory.get(wifi.getBssid());
        history.add(wifi);
        
        if (history.size() > 10) {
            history.remove(0);
        }
    }
    
    /**
     * 处理扫描失败
     */
    private void handleScanFailure() {
        consecutiveFailures++;
        Log.w(TAG, "WiFi扫描失败，连续失败次数: " + consecutiveFailures);
        
        if (consecutiveFailures >= 3) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(context, "WiFi扫描失败，请检查WiFi设置", Toast.LENGTH_SHORT).show();
                }
            });
        }
        
        adjustScanInterval();
    }
    
    /**
     * 调整扫描间隔
     */
    private void adjustScanInterval() {
        if (isBatteryLow) {
            currentScanInterval = Math.min(currentScanInterval * 2, MAX_SCAN_INTERVAL);
        } else if (consecutiveFailures > 3) {
            currentScanInterval = Math.min(currentScanInterval + 2000, MAX_SCAN_INTERVAL);
        } else if (consecutiveFailures == 0 && currentScanInterval > DEFAULT_SCAN_INTERVAL) {
            currentScanInterval = Math.max(currentScanInterval - 1000, DEFAULT_SCAN_INTERVAL);
        }
        
        Log.d(TAG, "调整扫描间隔为: " + currentScanInterval + "ms");
    }
    
    /**
     * 注册电池状态变化广播接收器
     */
    private void registerBatteryReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_LOW);
        filter.addAction(Intent.ACTION_BATTERY_OKAY);
        
        BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(Intent.ACTION_BATTERY_LOW)) {
                    isBatteryLow = true;
                    currentScanInterval = Math.min(currentScanInterval * 2, MAX_SCAN_INTERVAL);
                } else if (action.equals(Intent.ACTION_BATTERY_OKAY)) {
                    isBatteryLow = false;
                    currentScanInterval = DEFAULT_SCAN_INTERVAL;
                }
            }
        };
        
        context.registerReceiver(batteryReceiver, filter);
    }

    /**
     * Converts mac address from string to integer.
     */
    private long convertBssidToLong(String wifiMacAddress){
        long intMacAddress =0;
        int colonCount =5;
        for(int j =0; j<17; j++){
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
     * Checks if the user authorised all permissions necessary for accessing wifi data.
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
     * Scan for nearby networks.
     */
    private void startWifiScan() {
        if (isScanning) {
            return;
        }
        
        if(checkWifiPermissions()) {
            try {
                isScanning = true;
                context.registerReceiver(wifiScanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
                boolean success = wifiManager.startScan();
                if (!success) {
                    handleScanFailure();
                }
            } catch (Exception e) {
                Log.e(TAG, "WiFi扫描出错: " + e.getMessage());
                isScanning = false;
                handleScanFailure();
            }
        }
    }

    /**
     * Initiate scans for nearby networks every 5 seconds.
     */
    public void startListening() {
        this.scanWifiDataTimer = new Timer();
        this.scanWifiDataTimer.scheduleAtFixedRate(new scheduledWifiScan(), 0, currentScanInterval);
    }

    /**
     * Cancel wifi scans.
     */
    public void stopListening() {
        try {
            context.unregisterReceiver(wifiScanReceiver);
        } catch (IllegalArgumentException e) {
            // 接收器可能未注册
        }
        
        if (this.scanWifiDataTimer != null) {
            this.scanWifiDataTimer.cancel();
            this.scanWifiDataTimer = null;
        }
        
        isScanning = false;
    }

    /**
     * Inform user if throttling is resent on their device.
     */
    public void checkWifiThrottling(){
        if(checkWifiPermissions()) {
            try {
                if(Settings.Global.getInt(context.getContentResolver(), "wifi_scan_throttle_enabled") == 1) {
                    Toast.makeText(context, "请禁用WiFi扫描节流以获得更好的性能", Toast.LENGTH_LONG).show();
                }
            } catch (Settings.SettingNotFoundException e) {
                Log.e(TAG, "检查WiFi节流设置出错: " + e.getMessage());
            }
        }
    }

    /**
     * Implement default method from Observable Interface to add new observers to the class.
     */
    @Override
    public void registerObserver(Observer o) {
        observers.add(o);
    }

    /**
     * Implement default method from Observable Interface to add notify observers to the class.
     */
    @Override
    public void notifyObservers(int idx) {
        for(Observer o : observers) {
            o.update(wifiData);
        }
    }

    /**
     * Class to schedule wifi scans.
     */
    private class scheduledWifiScan extends TimerTask {
        @Override
        public void run() {
            startWifiScan();
        }
    }

    /**
     * Obtains required information about wifi in which the device is currently connected.
     */
    public Wifi getCurrentWifiData(){
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService
                (Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

        Wifi currentWifi = new Wifi();
        if(networkInfo != null && networkInfo.isConnected()) {
            currentWifi.setSsid(wifiManager.getConnectionInfo().getSSID());
            String wifiMacAddress = wifiManager.getConnectionInfo().getBSSID();
            if (wifiMacAddress != null) {
                long intMacAddress = convertBssidToLong(wifiMacAddress);
                currentWifi.setBssid(intMacAddress);
            } else {
                currentWifi.setBssid(0);
            }
            currentWifi.setFrequency(wifiManager.getConnectionInfo().getFrequency());
            currentWifi.setLevel(wifiManager.getConnectionInfo().getRssi());
            currentWifi.setConnected(true);
        }
        else{
            currentWifi.setSsid("未连接");
            currentWifi.setBssid(0);
            currentWifi.setFrequency(0);
            currentWifi.setLevel(0);
            currentWifi.setConnected(false);
        }
        return currentWifi;
    }
    
    /**
     * 获取指定BSSID的历史数据
     */
    public List<Wifi> getWifiHistory(long bssid) {
        if (wifiDataHistory.containsKey(bssid)) {
            return new ArrayList<>(wifiDataHistory.get(bssid));
        }
        return new ArrayList<>();
    }
    
    /**
     * 获取所有WiFi的平均信号强度
     */
    public Map<Long, Integer> getAverageSignalStrengths() {
        Map<Long, Integer> averageStrengths = new HashMap<>();
        
        for (Map.Entry<Long, List<Wifi>> entry : wifiDataHistory.entrySet()) {
            long bssid = entry.getKey();
            List<Wifi> history = entry.getValue();
            
            int sum = 0;
            for (Wifi wifi : history) {
                sum += wifi.getLevel();
            }
            
            if (!history.isEmpty()) {
                averageStrengths.put(bssid, sum / history.size());
            }
        }
        
        return averageStrengths;
    }
    
    /**
     * 清除历史数据
     */
    public void clearHistory() {
        wifiDataHistory.clear();
    }
}
