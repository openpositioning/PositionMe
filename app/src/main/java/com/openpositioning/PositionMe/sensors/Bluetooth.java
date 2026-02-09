package com.openpositioning.PositionMe.sensors;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * BLE scanning module for collecting Bluetooth Low Energy fingerprints during trajectory recording.
 * Collects MAC addresses, names, TX power, and advertise flags from nearby BLE devices.
 */
public class Bluetooth {
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BleDeviceScanCallback bleDeviceScanCallback;
    private Context context;
    private HashSet<String> scannedDevices;
    private List<BleDevice> discoveredDevices;

    /**
     * Represents a discovered BLE device with its attributes.
     */
    public static class BleDevice {
        public String macAddress;
        public String name;
        public int txPowerLevel;
        public int advertiseFlags;

        public BleDevice(String mac, String name, int txPower, int flags) {
            this.macAddress = mac;
            this.name = name != null ? name : "Unknown";
            this.txPowerLevel = txPower;
            this.advertiseFlags = flags;
        }
    }

    /**
     * Initialize Bluetooth scanner with context.
     */
    public Bluetooth(Context context) {
        this.context = context;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.scannedDevices = new HashSet<>();
        this.discoveredDevices = new ArrayList<>();
        
        if (bluetoothAdapter != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            this.bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            this.bleDeviceScanCallback = new BleDeviceScanCallback();
        }
    }

    /**
     * Start BLE scanning. Requires BLUETOOTH_SCAN permission on Android 12+.
     * Bluetooth must be enabled on the device.
     */
    public void startScan() {
        if (bluetoothAdapter == null || bluetoothLeScanner == null) {
            return;
        }

        // Check if Bluetooth is enabled
        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(context, "Bluetooth is turned off. Please enable it to collect BLE fingerprints.", Toast.LENGTH_SHORT).show();
            return; // Bluetooth is off
        }

        // Check permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                return; // Permissions not granted
            }
        }

        // Clear previous scan results
        scannedDevices.clear();
        discoveredDevices.clear();

        // Start scanning
        bluetoothLeScanner.startScan(bleDeviceScanCallback);
    }

    /**
     * Stop BLE scanning.
     */
    public void stopScan() {
        if (bluetoothLeScanner == null) {
            return;
        }

        // Check permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        bluetoothLeScanner.stopScan(bleDeviceScanCallback);
    }

    /**
     * Get list of discovered BLE devices.
     */
    public List<BleDevice> getDiscoveredDevices() {
        return new ArrayList<>(discoveredDevices);
    }

    /**
     * Callback for BLE scan results.
     */
    private class BleDeviceScanCallback extends ScanCallback {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            String deviceAddress = result.getDevice().getAddress();
            
            // Deduplicate: only process each device once per scan session
            if (!scannedDevices.add(deviceAddress)) {
                return; // Already processed this device
            }

            String deviceName = result.getDevice().getName();
            int txPowerLevel = result.getScanRecord() != null ? result.getScanRecord().getTxPowerLevel() : 0;
            int advertiseFlags = result.getScanRecord() != null ? result.getScanRecord().getAdvertiseFlags() : 0;

            BleDevice device = new BleDevice(deviceAddress, deviceName, txPowerLevel, advertiseFlags);
            if (txPowerLevel != Integer.MIN_VALUE) {
                discoveredDevices.add(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
        }
    }
}
