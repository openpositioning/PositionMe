package com.openpositioning.PositionMe.sensors;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

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

    // log throttling (avoid spam)
    private static final long STATUS_LOG_DEBOUNCE_MS = 3000;
    private long lastStatusLogElapsed = 0L;

    private String lastToastMsg = "";
    private long lastToastTimeMs = 0L;

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;

    private final ArrayList<Observer> observers = new ArrayList<>();
    private Timer scanTimer;

    private boolean isReceiverRegistered = false;
    private long lastScanElapsedMs = 0L;

    // MAC -> latest obs in current window
    private final Map<String, BLE> windowMap = new HashMap<>();

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
        BLE[] data = windowMap.values().toArray(new BLE[0]);
        Log.i(TAG, "notifyObservers(): sending count=" + data.length + " to observers=" + observers.size());

        for (Observer o : observers) {
            try {
                o.update(data);
            } catch (Exception e) {
                Log.e(TAG, "notifyObservers(): observer.update() crashed", e);
            }
        }
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
            Log.e(TAG, "Missing ACCESS_FINE_LOCATION permission");
            showDebouncedToast("Missing ACCESS_FINE_LOCATION permission", Toast.LENGTH_SHORT);
            return;
        }

        registerReceiverIfNeeded();

        if (scanTimer != null) {
            scanTimer.cancel();
            scanTimer = null;
        }

        scanTimer = new Timer();
        scanTimer.scheduleAtFixedRate(new ScheduledBleScan(), 0, SCAN_INTERVAL_MS);

        Log.i(TAG, "startListening(): timer scheduled every " + SCAN_INTERVAL_MS + "ms");
    }

    public void stopListening() {
        Log.i(TAG, "stopListening() called");

        if (scanTimer != null) {
            scanTimer.cancel();
            scanTimer = null;
        }

        stopDiscoverySafely();
        windowMap.clear();
        unregisterReceiverIfNeeded();

        Log.i(TAG, "stopListening(): stopped, cache cleared, receiver unregistered(if needed)");
    }

    // -------------------- Core scanning --------------------

    @SuppressLint("MissingPermission")
    private void startBleDiscovery() {
        if (bluetoothAdapter == null) {
            logStatusThrottled("startBleDiscovery(): adapter null => cannot start");
            return;
        }

        if (!checkBlePermissions()) {
            Log.e(TAG, "startBleDiscovery(): permission missing => stopListening()");
            stopListening();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            logStatusThrottled("startBleDiscovery(): bluetooth disabled => skip");
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastScanElapsedMs < SCAN_INTERVAL_MS) {
            // should rarely happen because timer already uses SCAN_INTERVAL_MS
            logStatusThrottled("startBleDiscovery(): debounced (too soon)");
            return;
        }
        lastScanElapsedMs = now;

        if (bluetoothAdapter.isDiscovering()) {
            Log.d(TAG, "startBleDiscovery(): was discovering => cancelDiscovery()");
            bluetoothAdapter.cancelDiscovery();
        }

        Log.i(TAG, "startBleDiscovery(): calling startDiscovery()");
        boolean ok = bluetoothAdapter.startDiscovery();
        Log.i(TAG, "startBleDiscovery(): startDiscovery() returned " + ok);

        if (!ok) {
            Log.w(TAG, "startDiscovery() returned false (busy? permission? device limitation?)");
        }
    }

    @SuppressLint("MissingPermission")
    private void stopDiscoverySafely() {
        if (bluetoothAdapter == null) return;

        if (bluetoothAdapter.isDiscovering()) {
            Log.i(TAG, "stopDiscoverySafely(): cancelDiscovery()");
            bluetoothAdapter.cancelDiscovery();
        }
    }

    // -------------------- Receiver --------------------

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context c, Intent intent) {
            if (intent == null || intent.getAction() == null) return;

            if (!checkBlePermissions()) {
                Log.e(TAG, "Receiver: permission missing => stopListening()");
                stopListening();
                return;
            }

            String action = intent.getAction();

            if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                windowMap.clear();
                Log.i(TAG, "Receiver: ACTION_DISCOVERY_STARTED (cache cleared)");
                return;
            }

            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) return;

                String mac = device.getAddress();
                String name = device.getName();

                short rssiShort = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                int rssi = (rssiShort == Short.MIN_VALUE) ? 0 : (int) rssiShort;

                // Placeholder for UUID (classic discovery doesn't easily provide BLE adv uuids)
                String uuid = "unknown";

                BLE obs = new BLE();
                obs.setMac(mac);
                obs.setName(name);
                obs.setRssi(rssi);
                obs.setUuid(uuid);
                obs.setTimestampMs(SystemClock.elapsedRealtime());

                if (mac != null) windowMap.put(mac, obs);

                // Throttle FOUND logs so it won't spam too much
                if (windowMap.size() <= 5 || (windowMap.size() % 10 == 0)) {
                    Log.d(TAG, "Receiver: ACTION_FOUND mac=" + mac + " name=" + name + " rssi=" + rssi
                            + " uniqueSoFar=" + windowMap.size());
                }
                return;
            }

            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                Log.i(TAG, "Receiver: ACTION_DISCOVERY_FINISHED, unique devices=" + windowMap.size());
                notifyObservers(0);
                windowMap.clear();
            }
        }
    };

    private void registerReceiverIfNeeded() {
        if (isReceiverRegistered) {
            Log.d(TAG, "registerReceiverIfNeeded(): already registered");
            return;
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

        context.registerReceiver(bluetoothReceiver, filter);
        isReceiverRegistered = true;

        Log.i(TAG, "registerReceiverIfNeeded(): receiver registered");
    }

    private void unregisterReceiverIfNeeded() {
        if (!isReceiverRegistered) return;
        try {
            context.unregisterReceiver(bluetoothReceiver);
            Log.i(TAG, "unregisterReceiverIfNeeded(): receiver unregistered");
        } catch (IllegalArgumentException ignored) {
            Log.w(TAG, "unregisterReceiverIfNeeded(): receiver not registered (ignored)");
        }
        isReceiverRegistered = false;
    }

    // -------------------- Permissions (Android <= 11 style) --------------------

    private boolean checkBlePermissions() {
        int fineLoc = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION);
        return fineLoc == PackageManager.PERMISSION_GRANTED;
    }

    // -------------------- Timer task --------------------

    private class ScheduledBleScan extends TimerTask {
        @Override
        public void run() {
            Log.d(TAG, "ScheduledBleScan tick");
            startBleDiscovery();
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

    private void logStatusThrottled(String msg) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastStatusLogElapsed < STATUS_LOG_DEBOUNCE_MS) return;
        lastStatusLogElapsed = now;
        Log.w(TAG, msg);
    }
}
