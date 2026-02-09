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
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * BLE data gathering class following the same Observer pattern as {@link WifiDataProcessor}.
 *
 * Periodically scans for nearby BLE devices and notifies observers with scan results.
 * Each scan window collects BLE advertisements, deduplicates by MAC (keeping strongest RSSI),
 * and passes the results to registered observers (i.e. {@link SensorFusion}).
 *
 * @see WifiDataProcessor for the WiFi equivalent.
 */
public class BleDataProcessor implements Observable {
    private static final String TAG = "BleDataProcessor";
    private static final long SCAN_INTERVAL = 2000;   // 2s between scan starts
    private static final long SCAN_DURATION = 1500;    // 1.5s scan window

    private final Context context;
    private BluetoothLeScanner bleScanner;
    private Timer scanTimer;
    private final ArrayList<Observer> observers = new ArrayList<>();

    // Accumulated scan results for current scan window
    private final List<BleDevice> currentScanResults = new ArrayList<>();
    private boolean isScanning = false;

    /**
     * Data class representing a single BLE device scan result.
     */
    public static class BleDevice {
        public String macAddress;
        public int rssi;
        public String name;
        public int txPowerLevel;
        public int advertiseFlags;
        public List<String> serviceUuids = new ArrayList<>();
        public byte[] manufacturerData;
    }

    /**
     * Constructor. Initialises BluetoothLeScanner if Bluetooth is available and enabled.
     *
     * @param context Application context for permissions and system service access.
     */
    public BleDataProcessor(Context context) {
        this.context = context;
        this.scanTimer = new Timer();

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
            Log.w(TAG, "Bluetooth init failed: " + e.getMessage());
        }

        if (bleScanner == null) {
            Log.w(TAG, "BLE scanner not available (Bluetooth disabled or unsupported)");
        }
    }

    /**
     * Start periodic BLE scanning.
     */
    public void startListening() {
        if (bleScanner == null) {
            Log.w(TAG, "Cannot start BLE scanning: scanner not available");
            return;
        }
        this.scanTimer = new Timer();
        this.scanTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                startBleScan();
            }
        }, 0, SCAN_INTERVAL);
    }

    /**
     * Stop periodic BLE scanning.
     */
    public void stopListening() {
        scanTimer.cancel();
        stopBleScan();
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(context,
                    android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        // Pre-Android 12: location permission is sufficient for BLE scanning
        return ActivityCompat.checkSelfPermission(context,
                android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startBleScan() {
        if (isScanning || bleScanner == null || !hasScanPermission()) return;

        synchronized (currentScanResults) {
            currentScanResults.clear();
        }
        isScanning = true;

        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            bleScanner.startScan(null, settings, scanCallback);
        } catch (Exception e) {
            Log.e(TAG, "BLE startScan failed: " + e.getMessage());
            isScanning = false;
            return;
        }

        // Stop after SCAN_DURATION and notify observers
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                stopBleScan();
                notifyObservers(1);
            }
        }, SCAN_DURATION);
    }

    private void stopBleScan() {
        if (!isScanning || bleScanner == null) return;
        try {
            if (hasScanPermission()) {
                bleScanner.stopScan(scanCallback);
            }
        } catch (Exception e) {
            Log.e(TAG, "BLE stopScan failed: " + e.getMessage());
        }
        isScanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BleDevice device = new BleDevice();
            device.macAddress = result.getDevice().getAddress();
            device.rssi = result.getRssi();

            // Device name (requires BLUETOOTH_CONNECT on Android 12+)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(context,
                            android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        device.name = result.getDevice().getName();
                    }
                } else {
                    device.name = result.getDevice().getName();
                }
            } catch (SecurityException e) {
                // Ignore, name stays null
            }
            if (device.name == null) device.name = "";

            // Advertisement data from ScanRecord
            if (result.getScanRecord() != null) {
                device.txPowerLevel = result.getScanRecord().getTxPowerLevel();
                device.advertiseFlags = result.getScanRecord().getAdvertiseFlags();

                // Service UUIDs
                List<ParcelUuid> uuids = result.getScanRecord().getServiceUuids();
                if (uuids != null) {
                    for (ParcelUuid uuid : uuids) {
                        device.serviceUuids.add(uuid.toString());
                    }
                }

                // Manufacturer-specific data (take first entry)
                SparseArray<byte[]> mfData = result.getScanRecord().getManufacturerSpecificData();
                if (mfData != null && mfData.size() > 0) {
                    device.manufacturerData = mfData.valueAt(0);
                }
            }

            // Deduplicate by MAC within scan window (keep latest/strongest)
            synchronized (currentScanResults) {
                boolean found = false;
                for (int i = 0; i < currentScanResults.size(); i++) {
                    if (currentScanResults.get(i).macAddress.equals(device.macAddress)) {
                        currentScanResults.set(i, device);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    currentScanResults.add(device);
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "BLE scan failed with error code: " + errorCode);
            isScanning = false;
        }
    };

    @Override
    public void registerObserver(Observer o) {
        observers.add(o);
    }

    @Override
    public void notifyObservers(int idx) {
        BleDevice[] results;
        synchronized (currentScanResults) {
            results = currentScanResults.toArray(new BleDevice[0]);
        }
        for (Observer o : observers) {
            o.update(results);
        }
    }
}
