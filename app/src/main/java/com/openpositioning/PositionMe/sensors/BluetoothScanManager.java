package com.openpositioning.PositionMe.sensors;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;
import android.util.SparseArray;

import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * BluetoothScanManager manages BLE device discovery following the Observer pattern.
 * Similar architecture to {@link WifiDataProcessor}.
 *
 * Performs periodic scans for BLE advertisements, aggregates results per scan window,
 * deduplicates by MAC address (retaining strongest signal), and notifies registered
 * observers (typically {@link SensorFusion}).
 *
 * @see WifiDataProcessor for the WiFi scanning equivalent.
 */
public class BluetoothScanManager implements Observable {
    private static final String LOG_TAG = "BluetoothScanManager";
    private static final long POLLING_PERIOD_MS = 2000;
    private static final long WINDOW_DURATION_MS = 1500;

    private final Context appContext;
    private BluetoothLeScanner bleScanner;
    private Timer scheduledTimer;
    private final ArrayList<Observer> observerList = new ArrayList<>();

    private final Map<String, BeaconSnapshot> activeDeviceList = new HashMap<>();
    private boolean isScanActive = false;

    /**
     * Data container for a single BLE beacon observation.
     */
    public static class BeaconSnapshot {
        public String macAddress;
        public int rssi;
        public String deviceName;
        public int txPowerLevel;
        public int advertiseFlags;
        public List<String> serviceUuids = new ArrayList<>();
        public byte[] manufacturerData;
    }

    /**
     * Initialize BLE scanner if Bluetooth hardware is available and enabled.
     *
     * @param context Application context for system services and permission checks.
     */
    public BluetoothScanManager(Context context) {
        this.appContext = context;
        this.scheduledTimer = new Timer();

        try {
            BluetoothManager btManager = (BluetoothManager)
                    context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (btManager != null) {
                BluetoothAdapter adapter = btManager.getAdapter();
                if (adapter != null && adapter.isEnabled()) {
                    bleScanner = adapter.getBluetoothLeScanner();
                }
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Bluetooth initialization failed: " + e.getMessage());
        }

        if (bleScanner == null) {
            Log.w(LOG_TAG, "BLE scanner unavailable (Bluetooth disabled or not supported)");
        }
    }

    /**
     * Begin periodic BLE scanning at fixed intervals.
     */
    public void startListening() {
        if (bleScanner == null) {
            Log.w(LOG_TAG, "Cannot initiate scanning: BLE scanner not available");
            return;
        }
        this.scheduledTimer = new Timer();
        this.scheduledTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                initiateScanWindow();
            }
        }, 0, POLLING_PERIOD_MS);
    }

    /**
     * Stop periodic scanning and release resources.
     */
    public void stopListening() {
        scheduledTimer.cancel();
        terminateScanWindow();
    }

    /**
     * Check if app has necessary permissions for BLE scanning.
     * Android 12+ requires BLUETOOTH_SCAN; earlier versions use location permission.
     */
    private boolean validateScanAuth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(appContext,
                    android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return ActivityCompat.checkSelfPermission(appContext,
                android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Start a single BLE scan window.
     */
    private void initiateScanWindow() {
        if (isScanActive || bleScanner == null || !validateScanAuth()) return;

        synchronized (activeDeviceList) {
            activeDeviceList.clear();
        }
        isScanActive = true;

        try {
            ScanSettings scanConfig = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            bleScanner.startScan(null, scanConfig, advertisementCallback);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Scan initiation failed: " + e.getMessage());
            isScanActive = false;
            return;
        }

        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                terminateScanWindow();
                notifyObservers(1);
            }
        }, WINDOW_DURATION_MS);
    }

    /**
     * Stop the current scan window.
     */
    private void terminateScanWindow() {
        if (!isScanActive || bleScanner == null) return;
        try {
            if (validateScanAuth()) {
                bleScanner.stopScan(advertisementCallback);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Scan termination failed: " + e.getMessage());
        }
        isScanActive = false;
    }

    private final ScanCallback advertisementCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BeaconSnapshot beacon = new BeaconSnapshot();
            beacon.macAddress = result.getDevice().getAddress();
            beacon.rssi = result.getRssi();

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(appContext,
                            android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        beacon.deviceName = result.getDevice().getName();
                    }
                } else {
                    beacon.deviceName = result.getDevice().getName();
                }
            } catch (SecurityException e) {
                // Permission denied, name remains null
            }
            if (beacon.deviceName == null) beacon.deviceName = "";

            if (result.getScanRecord() != null) {
                beacon.txPowerLevel = result.getScanRecord().getTxPowerLevel();
                beacon.advertiseFlags = result.getScanRecord().getAdvertiseFlags();

                List<ParcelUuid> uuidList = result.getScanRecord().getServiceUuids();
                if (uuidList != null) {
                    for (ParcelUuid uuid : uuidList) {
                        beacon.serviceUuids.add(uuid.toString());
                    }
                }

                SparseArray<byte[]> manufacturerMap = result.getScanRecord().getManufacturerSpecificData();
                if (manufacturerMap != null && manufacturerMap.size() > 0) {
                    beacon.manufacturerData = manufacturerMap.valueAt(0);
                }
            }

            synchronized (activeDeviceList) {
                activeDeviceList.put(beacon.macAddress, beacon);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(LOG_TAG, "BLE scan failure, error code: " + errorCode);
            isScanActive = false;
        }
    };

    @Override
    public void registerObserver(Observer o) {
        observerList.add(o);
    }

    @Override
    public void notifyObservers(int idx) {
        BeaconSnapshot[] snapshotArray;
        synchronized (activeDeviceList) {
            snapshotArray = activeDeviceList.values().toArray(new BeaconSnapshot[0]);
        }
        for (Observer o : observerList) {
            o.update(snapshotArray);
        }
    }
}
