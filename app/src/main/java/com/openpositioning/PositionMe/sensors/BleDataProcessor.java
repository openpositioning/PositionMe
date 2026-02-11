package com.openpositioning.PositionMe.sensors;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * BLE Data Processor for scanning and collecting Bluetooth Low Energy device information.
 * Designed to work in background without UI, similar to WifiDataProcessor.
 * Implements Observable pattern to notify SensorFusion of new BLE scan results.
 *
 * @author Generated for Proto v2.0 BLE data collection
 */
public class BleDataProcessor implements Observable {

    private static final String TAG = "BleDataProcessor";

    // Scan interval (5 seconds, same as WiFi)
    private static final long SCAN_INTERVAL = 5000;

    // BLE scan duration (4 seconds to complete before next scan)
    private static final long SCAN_DURATION = 4000;

    // Application context
    private final Context context;

    // Bluetooth components
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;

    // Collected BLE devices (using Map to avoid duplicates in same scan)
    private Map<String, BleDevice> bleDevicesMap;

    // Observers list
    private ArrayList<Observer> observers;

    // Timer for periodic scanning
    private Timer scanBleTimer;

    // Flag to track if currently scanning
    private boolean isScanning = false;

    /**
     * Constructor - Initialize BLE processor
     */
    public BleDataProcessor(Context context) {
        this.context = context;
        this.observers = new ArrayList<>();
        this.bleDevicesMap = new HashMap<>();

        Log.i(TAG, "Initializing BleDataProcessor...");

        // Check if BLE is supported
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.w(TAG, "BLE not supported on this device");
            Toast.makeText(context, "BLE not supported on this device", Toast.LENGTH_SHORT).show();
            this.bluetoothAdapter = null;
            return;
        }
        Log.i(TAG, "BLE is supported on this device");

        // Get Bluetooth adapter
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            Log.e(TAG, "BluetoothManager is null");
            Toast.makeText(context, "Bluetooth manager not available", Toast.LENGTH_SHORT).show();
            this.bluetoothAdapter = null;
            return;
        }

        this.bluetoothAdapter = bluetoothManager.getAdapter();

        // Check if Bluetooth adapter exists
        if (bluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter is null - device may not support Bluetooth");
            Toast.makeText(context, "Bluetooth not available", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.i(TAG, "BluetoothAdapter obtained successfully");

        // Check if Bluetooth is enabled
        if (!bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "Bluetooth is not enabled - BLE scanning will not work");
            Toast.makeText(context, "Please enable Bluetooth for BLE scanning", Toast.LENGTH_LONG).show();
            return;
        }
        Log.i(TAG, "Bluetooth is enabled");

        // Try to get BLE scanner immediately (with permission check)
        if (checkBlePermissions()) {
            try {
                this.bleScanner = bluetoothAdapter.getBluetoothLeScanner();
                if (bleScanner == null) {
                    Log.e(TAG, "BluetoothLeScanner is null even though Bluetooth is enabled");
                    Toast.makeText(context, "BLE scanner initialization failed - try toggling Bluetooth", Toast.LENGTH_LONG).show();
                } else {
                    Log.i(TAG, "BLE scanner initialized successfully");
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception while getting BLE scanner: " + e.getMessage());
                this.bleScanner = null;
            }
        } else {
            Log.w(TAG, "BLE permissions not granted yet - scanner will be initialized when permissions are granted");
        }
    }

    /**
     * BLE Scan Callback - receives scan results
     */
    private final ScanCallback bleScanCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            if (!checkBlePermissions()) {
                return;
            }

            try {
                BluetoothDevice device = result.getDevice();
                String address = device.getAddress();

                // Create BleDevice object
                BleDevice bleDevice = new BleDevice();
                bleDevice.macAddress = address;
                bleDevice.name = device.getName() != null ? device.getName() : "Unknown";
                bleDevice.rssi = result.getRssi();

                // Get TX power level (if available)
                if (result.getTxPower() != 127) {  // 127 means not available
                    bleDevice.txPowerLevel = result.getTxPower();
                }

                // Get Service UUIDs
                if (result.getScanRecord() != null) {
                    bleDevice.serviceUuids = new ArrayList<>();
                    if (result.getScanRecord().getServiceUuids() != null) {
                        for (android.os.ParcelUuid uuid : result.getScanRecord().getServiceUuids()) {
                            bleDevice.serviceUuids.add(uuid.toString());
                        }
                    }

                    // Get manufacturer data
                    if (result.getScanRecord().getManufacturerSpecificData() != null
                            && result.getScanRecord().getManufacturerSpecificData().size() > 0) {
                        int manufacturerId = result.getScanRecord().getManufacturerSpecificData().keyAt(0);
                        bleDevice.manufacturerData = result.getScanRecord().getManufacturerSpecificData().get(manufacturerId);
                    }

                    // Get advertise flags
                    bleDevice.advertiseFlags = result.getScanRecord().getAdvertiseFlags();
                }

                // Add to map (will replace if same device scanned again in this scan)
                bleDevicesMap.put(address, bleDevice);

            } catch (Exception e) {
                Log.e(TAG, "Error processing BLE scan result: " + e.getMessage());
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.e(TAG, "BLE Scan failed with error code: " + errorCode);
            isScanning = false;
        }
    };

    /**
     * Start BLE scanning
     */
    @SuppressLint("MissingPermission")
    private void startBleScan() {
        if (!checkBlePermissions()) {
            Log.w(TAG, "BLE permissions not granted - cannot scan");
            return;
        }

        // Try to get scanner if not already obtained
        if (bleScanner == null && bluetoothAdapter != null) {
            Log.i(TAG, "Attempting to get BLE scanner...");
            try {
                bleScanner = bluetoothAdapter.getBluetoothLeScanner();
                if (bleScanner == null) {
                    Log.e(TAG, "BLE scanner is still null - Bluetooth might need to be toggled");
                    return;
                }
                Log.i(TAG, "BLE scanner obtained successfully");
            } catch (Exception e) {
                Log.e(TAG, "Exception getting BLE scanner: " + e.getMessage());
                return;
            }
        }

        if (bleScanner == null) {
            Log.w(TAG, "BLE scanner not available");
            return;
        }

        if (isScanning) {
            Log.d(TAG, "Already scanning, skip this round");
            return;
        }

        // Clear previous scan results
        bleDevicesMap.clear();

        // Start scanning
        try {
            bleScanner.startScan(bleScanCallback);
            isScanning = true;
            Log.d(TAG, "BLE scan started");

            // Stop scan after SCAN_DURATION
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    stopBleScan();
                }
            }, SCAN_DURATION);

        } catch (SecurityException e) {
            Log.e(TAG, "Security exception when starting BLE scan: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Exception when starting BLE scan: " + e.getMessage());
        }
    }

    /**
     * Stop BLE scanning and notify observers
     */
    @SuppressLint("MissingPermission")
    private void stopBleScan() {
        if (!isScanning) {
            return;
        }

        if (!checkBlePermissions()) {
            return;
        }

        try {
            if (bleScanner != null) {
                bleScanner.stopScan(bleScanCallback);
                isScanning = false;

                Log.i(TAG, "BLE scan completed, found " + bleDevicesMap.size() + " devices");

                // Notify observers with results
                notifyObservers(0);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception when stopping BLE scan: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Exception when stopping BLE scan: " + e.getMessage());
        }
    }

    /**
     * Start periodic BLE scanning
     */
    public void startListening() {
        if (!checkBlePermissions()) {
            Log.w(TAG, "Cannot start BLE listening - permissions not granted");
            return;
        }

        Log.i(TAG, "BLE periodic scanning started");

        this.scanBleTimer = new Timer();
        this.scanBleTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                startBleScan();
            }
        }, 0, SCAN_INTERVAL);
    }

    /**
     * Stop periodic BLE scanning
     */
    public void stopListening() {
        if (scanBleTimer != null) {
            scanBleTimer.cancel();
            scanBleTimer = null;
        }

        if (isScanning) {
            stopBleScan();
        }

        Log.i(TAG, "BLE scanning stopped");
    }

    /**
     * Check BLE permissions
     */
    private boolean checkBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED;
        } else {
            // Below Android 12
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH)
                    == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN)
                            == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * Get collected BLE devices as array
     */
    public BleDevice[] getBleDevices() {
        return bleDevicesMap.values().toArray(new BleDevice[0]);
    }

    @Override
    public void registerObserver(Observer o) {
        observers.add(o);
    }

    @Override
    public void notifyObservers(int idx) {
        BleDevice[] devices = getBleDevices();
        for (Observer o : observers) {
            o.update(devices);
        }
    }

    /**
     * BLE Device data class
     */
    public static class BleDevice {
        public String macAddress;
        public String name;
        public int rssi;
        public int txPowerLevel = 0;
        public int advertiseFlags = 0;
        public List<String> serviceUuids;
        public byte[] manufacturerData;
    }
}