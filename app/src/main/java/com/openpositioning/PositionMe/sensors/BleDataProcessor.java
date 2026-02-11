package com.openpositioning.PositionMe.sensors;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.openpositioning.PositionMe.BuildConfig;
import android.bluetooth.le.BluetoothLeScanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BLE data gathering and processing using BluetoothAdapter discovery + BroadcastReceiver.
 *
 * Debug logs tag: BLE_PIPE
 *
 * Design:
 * - Periodically start discovery (default every 5s)
 * - Use MAC as dedup key within a window
 * - On ACTION_DISCOVERY_FINISHED: flush (notifyObservers) and clear cache
 *
 * Note:
 * - Android 9 (API 28) style permissions: requires ACCESS_FINE_LOCATION for discovery results.
 * - Emulator may not support Bluetooth => bluetoothAdapter == null (expected).
 */
public class BleDataProcessor implements Observable {

    // Use a dedicated TAG so you can filter Logcat by it.
    private static final String TAG = "BLE_PIPE";

    private static final long SCAN_INTERVAL_MS = 5000;
    private static final long TOAST_DEBOUNCE_MS = 8000;
    private static final long LOG_SAMPLE_DEBOUNCE_MS = 3000;
    private String lastToastMsg = "";
    private long lastToastTimeMs = 0L;

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;

    private final ArrayList<Observer> observers = new ArrayList<>();
    private ScanCallback scanCallback;

    // Flush window every SCAN_INTERVAL_MS
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // MAC -> latest obs in current window
    private final Map<String, BLE> windowMap = new HashMap<>();
    private long lastLogSampleMs = 0L;

    public BleDataProcessor(Context context) {
        this.context = context.getApplicationContext();

        BluetoothManager bluetoothManager =
                (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);

        BluetoothAdapter adapter = null;
        if (bluetoothManager != null) {
            adapter = bluetoothManager.getAdapter();
        }
        this.bluetoothAdapter = adapter;

        Log.i(TAG, "BleDataProcessor() init: bluetoothAdapter=" + (bluetoothAdapter == null ? "null" : "OK"));
    }

    // -------------------- Observable --------------------

    @Override
    public void registerObserver(Observer o) {
        observers.add(o);
        Log.i(TAG, "registerObserver(): totalObservers=" + observers.size());
    }

    @Override
    public void notifyObservers(int idx) {
        BLE[] data;
        synchronized (windowMap) {
            data = windowMap.values().toArray(new BLE[0]);
        }
        sendToObservers(data);
    }

    // -------------------- Public lifecycle --------------------

    public void startListening() {
        Log.i(TAG, "startListening() called");

        if (bluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter == null (Bluetooth not supported / emulator limitation)");
            showDebouncedToast("Bluetooth not supported on this device", Toast.LENGTH_SHORT);
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth is disabled");
            showDebouncedToast("Please enable Bluetooth", Toast.LENGTH_SHORT);
            return;
        }

        if (!checkBlePermissions()) {
            Log.e(TAG, "Missing BLE permissions");
            showDebouncedToast("Missing Bluetooth permissions", Toast.LENGTH_SHORT);
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            Log.e(TAG, "BluetoothLeScanner is null (adapter disabled?)");
            return;
        }

        stopScanningInternal(); // ensure clean state
        startScanningInternal();
        scheduleFlush();

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "BLE scan started (mode=LOW_LATENCY, interval=" + SCAN_INTERVAL_MS + "ms)");
        }
    }

    public void stopListening() {
        Log.i(TAG, "stopListening() called");

        stopScanningInternal();
        cancelFlush();
        synchronized (windowMap) {
            windowMap.clear();
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "BLE scan stopped");
        }
    }

    // -------------------- Core scanning --------------------

    // -------------------- Permissions --------------------

    private boolean checkBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            int scan = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN);
            int connect = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT);
            return scan == PackageManager.PERMISSION_GRANTED && connect == PackageManager.PERMISSION_GRANTED;
        }
        int fineLoc = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION);
        return fineLoc == PackageManager.PERMISSION_GRANTED;
    }

    // -------------------- Scan helpers --------------------

    @SuppressLint("MissingPermission")
    private void startScanningInternal() {
        if (bluetoothLeScanner == null) return;

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                handleScanResult(result);
            }

            @Override
            public void onBatchScanResults(java.util.List<ScanResult> results) {
                if (results == null) return;
                for (ScanResult r : results) {
                    handleScanResult(r);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "onScanFailed(): errorCode=" + errorCode);
            }
        };

        bluetoothLeScanner.startScan(null, settings, scanCallback);
    }

    @SuppressLint("MissingPermission")
    private void stopScanningInternal() {
        if (bluetoothLeScanner != null && scanCallback != null) {
            bluetoothLeScanner.stopScan(scanCallback);
        }
        scanCallback = null;
    }

    @SuppressLint("MissingPermission")
    private void handleScanResult(ScanResult result) {
        if (result == null) return;
        if (!checkBlePermissions()) {
            Log.w(TAG, "handleScanResult(): permissions revoked during scan, stopping");
            stopListening();
            return;
        }

        BluetoothDevice device = result.getDevice();
        String mac = (device != null) ? device.getAddress() : null;
        if (mac == null) return; // cannot dedup without id

        BLE obs = new BLE();
        obs.setMac(mac);
        obs.setName(device != null ? device.getName() : null);
        obs.setRssi(result.getRssi());
        List<String> serviceUuids = extractServiceUuids(result);
        obs.setServiceUuids(serviceUuids);
        obs.setUuid(serviceUuids.isEmpty() ? "unknown" : serviceUuids.get(0));
        long tsMs = result.getTimestampNanos() > 0
                ? result.getTimestampNanos() / 1_000_000L
                : SystemClock.elapsedRealtime();
        obs.setTimestampMs(tsMs);

        synchronized (windowMap) {
            windowMap.put(mac, obs);
        }

        long now = SystemClock.elapsedRealtime();
        if (BuildConfig.DEBUG && (windowMap.size() <= 5 || now - lastLogSampleMs >= LOG_SAMPLE_DEBOUNCE_MS)) {
            Log.d(TAG, "scan result mac=" + mac + " rssi=" + result.getRssi()
                    + " unique=" + windowMap.size());
            lastLogSampleMs = now;
        }
    }

    @NonNull
    private List<String> extractServiceUuids(@NonNull ScanResult result) {
        if (result.getScanRecord() == null) {
            return Collections.emptyList();
        }
        List<ParcelUuid> parcelUuids = result.getScanRecord().getServiceUuids();
        if (parcelUuids == null || parcelUuids.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(parcelUuids.size());
        for (ParcelUuid parcelUuid : parcelUuids) {
            if (parcelUuid == null) {
                continue;
            }
            String value = parcelUuid.toString();
            if (value != null && !value.trim().isEmpty()) {
                out.add(value.trim());
            }
        }
        return out;
    }

    private void scheduleFlush() {
        cancelFlush();
        mainHandler.postDelayed(flushRunnable, SCAN_INTERVAL_MS);
    }

    private void cancelFlush() {
        mainHandler.removeCallbacks(flushRunnable);
    }

    private final Runnable flushRunnable = new Runnable() {
        @Override
        public void run() {
            flushWindow();
            mainHandler.postDelayed(this, SCAN_INTERVAL_MS);
        }
    };

    private void flushWindow() {
        BLE[] data;
        synchronized (windowMap) {
            if (windowMap.isEmpty()) return;
            data = windowMap.values().toArray(new BLE[0]);
            windowMap.clear();
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "flushWindow(): sending " + data.length + " entries to observers");
        }
        sendToObservers(data);
    }

    private void sendToObservers(BLE[] data) {
        Log.i(TAG, "notifyObservers(): sending count=" + data.length + " to observers=" + observers.size());
        for (Observer o : observers) {
            try {
                o.update(data);
            } catch (Exception e) {
                Log.e(TAG, "notifyObservers(): observer.update() crashed", e);
            }
        }
    }

    // -------------------- Toast helper --------------------

    private void showDebouncedToast(String message, int duration) {
        long now = SystemClock.elapsedRealtime();
        if (message.equals(lastToastMsg) && now - lastToastTimeMs <= TOAST_DEBOUNCE_MS) return;
        lastToastMsg = message;
        lastToastTimeMs = now;
        Toast.makeText(context, message, duration).show();
    }
}
