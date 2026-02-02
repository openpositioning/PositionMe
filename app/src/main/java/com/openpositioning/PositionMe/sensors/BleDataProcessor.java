package com.openpositioning.PositionMe.sensors;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.bluetooth.le.ScanRecord;
import android.os.ParcelUuid;
import android.util.Log;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

public class BleDataProcessor implements Observable {
    private static final long SCAN_INTERVAL = 5000;
    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private final BluetoothLeScanner bleScanner;
    private Ble[] bleData;
    // for Ble fingerprint
    private final Map<Long, Ble> bleWindow = new HashMap<>();
    private final long FINGERPRINT_WINDOW_MS = 2000; // 2 seconds
    private long lastWindowTimestamp = System.currentTimeMillis();
    private final ArrayList<Observer> observers = new ArrayList<>();
    private Timer scanTimer;
    private Ble lastSeenBle;   // similar role to current WiFi connection

    public BleDataProcessor(Context context) {
        this.context = context;

        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager.getAdapter();
        bleScanner = bluetoothAdapter.getBluetoothLeScanner();

        if (checkBlePermissions()) {
            startListening();
        }
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------
    private boolean checkBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED &&
                   ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    // Scanning
    private void startBleScan() {
        if (!checkBlePermissions()) return;
        bleScanner.startScan(scanCallback);
    }

    private void stopBleScan() {
        bleScanner.stopScan(scanCallback);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            String macStr = result.getDevice().getAddress();
            long macLong = convertBssidToLong(macStr);
            int rssi = result.getRssi();
            String name = result.getDevice().getName();

            // Get or create BLE entry
            Ble ble = bleWindow.get(macLong);
            if (ble == null) {
                ble = new Ble();
                ble.setMacLong(macLong);
                ble.setMacStr(macStr);
                ble.setName(name);
                ScanRecord scanRecord = result.getScanRecord();
                if (scanRecord != null) {
                    Log.d("BLE_processor", "power is "+scanRecord.getTxPowerLevel());
                    ble.setTxPower(scanRecord.getTxPowerLevel());
                    ble.setAdvertiseFlags(scanRecord.getAdvertiseFlags());
                    if (scanRecord.getServiceUuids() != null) {
                        List<String> uuids = new ArrayList<>();
                        for (ParcelUuid u : scanRecord.getServiceUuids()) {
                            uuids.add(u.toString());
                        }
                        ble.setServiceUuids(uuids);
                    }
                    ble.setManufacturerData(scanRecord.getManufacturerSpecificData());
                }
                bleWindow.put(macLong, ble);
            }

            // Aggregate RSSI
            ble.addRssi(rssi);   // internally store sum + count

            long now = System.currentTimeMillis();
            if (now - lastWindowTimestamp >= FINGERPRINT_WINDOW_MS) {

                // Notify Observer
                notifyObservers(0);

                // Reset window
                bleWindow.clear();
                // Update TIme stamp
                lastWindowTimestamp = now;
            }
        }
    };

    // ------------------------------------------------------------------
    // MAC conversion (same as Wi‑Fi → RFScan.mac)
    // ------------------------------------------------------------------
    private long convertBssidToLong(String wifiMacAddress){
        long intMacAddress =0;
        int colonCount =5;
        //Loop through each character
        for(int j =0; j<17; j++){
            //Identify character
            char macByte = wifiMacAddress.charAt(j);
            //convert string hex mac address with colons to decimal long integer
            if(macByte != ':'){
                //For characters 0-9 subtract 48 from ASCII code and multiply by 16^position
                if((int) macByte >= 48 && (int) macByte <= 57){
                    intMacAddress = intMacAddress + (((int)macByte-48)*((long)Math.pow(16,16-j-colonCount)));
                }

                //For characters a-f subtract 87 (=97-10) from ASCII code and multiply by 16^index
                else if ((int) macByte >= 97 && (int) macByte <= 102){
                    intMacAddress = intMacAddress + (((int)macByte-87)*((long)Math.pow(16,16-j-colonCount)));
                }
            }
            else
                //coloncount is used to obtain the index of each character
                colonCount --;
        }

        return intMacAddress;
    }

    // ------------------------------------------------------------------
    // Timer
    // ------------------------------------------------------------------
    public void startListening() {
        scanTimer = new Timer();
        scanTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                startBleScan();
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                stopBleScan();
            }
        }, 0, SCAN_INTERVAL);
    }

    public void stopListening() {
        if (scanTimer != null) scanTimer.cancel();
        stopBleScan();
    }

    // ------------------------------------------------------------------
    // Observer
    // ------------------------------------------------------------------
    @Override
    public void registerObserver(Observer o) {
        observers.add(o);
    }

    @Override
    public void notifyObservers(int idx) {
        for (Observer o : observers) {
            // use updateBle
            o.updateBle(bleWindow.values().toArray(new Ble[0]));
        }
    }
}
