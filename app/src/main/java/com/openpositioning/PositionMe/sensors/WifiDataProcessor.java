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

import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class WifiDataProcessor implements Observable {

    private static final long scanInterval = 5000; // 5秒扫描一次

    private final Context context;
    private final WifiManager wifiManager;
    private Wifi[] wifiData;
    private ArrayList<Observer> observers;
    private Timer scanWifiDataTimer;

    public WifiDataProcessor(Context context) {
        this.context = context;
        boolean permissionsGranted = checkWifiPermissions();
        this.wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        this.scanWifiDataTimer = new Timer();
        this.observers = new ArrayList<>();
        if(permissionsGranted && wifiManager.getWifiState()== WifiManager.WIFI_STATE_DISABLED) {
            wifiManager.setWifiEnabled(true);
        }
        if(permissionsGranted) {
            this.scanWifiDataTimer.scheduleAtFixedRate(new ScheduledWifiScan(), 0, scanInterval);
        }
        checkWifiThrottling();
    }

    BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                stopListening();
                return;
            }
            List<ScanResult> wifiScanList = wifiManager.getScanResults();
            context.unregisterReceiver(this);
            wifiData = new Wifi[wifiScanList.size()];
            for(int i = 0; i < wifiScanList.size(); i++) {
                wifiData[i] = new Wifi();
                String wifiMacAddress = wifiScanList.get(i).BSSID;
                long intMacAddress = convertBssidToLong(wifiMacAddress);
                wifiData[i].setBssid(intMacAddress);
                wifiData[i].setLevel(wifiScanList.get(i).level);
            }
            notifyObservers(0);
        }
    };

    private long convertBssidToLong(String wifiMacAddress){
        long intMacAddress = 0;
        int colonCount = 5;
        for(int j = 0; j < 17; j++){
            char macByte = wifiMacAddress.charAt(j);
            if(macByte != ':'){
                if((int) macByte >= 48 && (int) macByte <= 57){
                    intMacAddress = intMacAddress + (((int) macByte - 48) * ((long) Math.pow(16, 16 - j - colonCount)));
                } else if ((int) macByte >= 97 && (int) macByte <= 102){
                    intMacAddress = intMacAddress + (((int) macByte - 87) * ((long) Math.pow(16, 16 - j - colonCount)));
                }
            } else {
                colonCount--;
            }
        }
        return intMacAddress;
    }

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

    private void startWifiScan() {
        if(checkWifiPermissions()) {
            context.registerReceiver(wifiScanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            wifiManager.startScan();
        }
    }

    public void startListening() {
        this.scanWifiDataTimer = new Timer();
        this.scanWifiDataTimer.scheduleAtFixedRate(new ScheduledWifiScan(), 0, scanInterval);
    }

    public void stopListening() {
        context.unregisterReceiver(wifiScanReceiver);
        this.scanWifiDataTimer.cancel();
    }

    public void checkWifiThrottling(){
        if(checkWifiPermissions()) {
            try {
                if(Settings.Global.getInt(context.getContentResolver(), "wifi_scan_throttle_enabled") == 1) {
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

    private class ScheduledWifiScan extends TimerTask {
        @Override
        public void run() {
            startWifiScan();
        }
    }

    public Wifi getCurrentWifiData(){
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        Wifi currentWifi = new Wifi();
        if(networkInfo.isConnected()) {
            currentWifi.setSsid(wifiManager.getConnectionInfo().getSSID());
            String wifiMacAddress = wifiManager.getConnectionInfo().getBSSID();
            long intMacAddress = convertBssidToLong(wifiMacAddress);
            currentWifi.setBssid(intMacAddress);
            currentWifi.setFrequency(wifiManager.getConnectionInfo().getFrequency());
        } else {
            currentWifi.setSsid("Not connected");
            currentWifi.setBssid(0);
            currentWifi.setFrequency(0);
        }
        return currentWifi;
    }
}
