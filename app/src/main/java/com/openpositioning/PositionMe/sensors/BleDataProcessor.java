package com.openpositioning.PositionMe.sensors;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * BLE data gathering and processing for fingerprinting.
 *
 * Scans for nearby Bluetooth Low Energy beacons and devices, collecting MAC addresses,
 * signal strength, and advertised data for positioning.
 *
 * @author Vlad Stratulat
 */
public class BleDataProcessor implements Observable {

    //Time over which a new scan will be initiated (same as WiFi)
    private static final long SCAN_INTERVAL = 5000;

    // Application context for handling permissions and BluetoothLeScanner instances
    private final Context context;
    // Bluetooth scanner for scanning devices via the android system
    private BluetoothLeScanner bleScanner;
    //List of observers to be notified when changes are detected
    private ArrayList<Observer> observers;
    //List of nearby devices
    private BleDevice[] bleData;
    // Timer object
    private Timer bleScanTimer;

    public BleDataProcessor(Context context) {
        this.context = context;
        this.observers = new ArrayList<>();

        if (!hasPermission()) {
            return;
        }

        BluetoothManager bluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);

        if (bluetoothManager == null) {
            return;
        }

        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();

        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            bleScanner = bluetoothAdapter.getBluetoothLeScanner();
            if (bleScanner != null) {
                startListening();
            }
        }
    }

    private boolean hasPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            return (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED);
        } else {
            return (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (results.isEmpty() || !hasPermission()) {
                return;
            }

            // Process results
            bleData = new BleDevice[results.size()];
            for (int i = 0; i < results.size(); i++) {
                ScanResult scanResult = results.get(i);
                bleData[i] = new BleDevice();
                bleData[i].setMacAddress(scanResult.getDevice().getAddress());
                bleData[i].setRssi(scanResult.getRssi());

                if (hasPermission()) {
                    @SuppressLint("MissingPermission")
                    String name = scanResult.getDevice().getName();
                    bleData[i].setName(name != null ? name : "");
                }
                bleData[i].setTxPowerLevel(scanResult.getTxPower());

                if (scanResult.getScanRecord() != null) {
                    List<ParcelUuid> serviceUuids = scanResult.getScanRecord().getServiceUuids();
                    if (serviceUuids != null && !serviceUuids.isEmpty()) {
                        String[] uuids = new String[serviceUuids.size()];
                        for (int j = 0; j < serviceUuids.size(); j++) {
                            uuids[j] = serviceUuids.get(j).toString();
                        }
                        bleData[i].setServiceUuids(uuids);
                    }

                    bleData[i].setManufacturerData(
                            scanResult.getScanRecord().getManufacturerSpecificData());
                    bleData[i].setAdvertiseFlags(
                            scanResult.getScanRecord().getAdvertiseFlags());
                }
            }

            notifyObservers(0);
        }

        @Override
        public void onScanFailed(int errorCode) {
            // Silent failure
        }
    };

    private void startBleScan() {
        if (!hasPermission() || bleScanner == null) {
            return;
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(SCAN_INTERVAL)
                .build();

        try {
            bleScanner.startScan(null, settings, scanCallback);
        } catch (SecurityException e) {
            // Permission denied
        }
    }

    public void startListening() {
        this.bleScanTimer = new Timer();
        this.bleScanTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                startBleScan();
            }
        }, 0, SCAN_INTERVAL);
    }

    public void stopListening() {
        if (bleScanTimer != null) {
            bleScanTimer.cancel();
        }
        if (bleScanner != null && hasPermission()) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (SecurityException e) {
                // Permission denied
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
            o.update(bleData);
        }
    }
}