package com.openpositioning.PositionMe.sensors;

import android.Manifest;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * BLE data acquisition processor following the same {@link Observable} pattern
 * as {@link WifiDataProcessor}.
 *
 * <p>Performs continuous BLE scanning and batches results every 5 seconds,
 * matching the WiFi scan rhythm. Observers are notified with an array of
 * {@link BleDevice} objects.</p>
 */
public class BleDataProcessor implements Observable {

  private static final String TAG = "BleDataProcessor";
  private static final long BATCH_INTERVAL_MS = 5000;

  private final Context context;
  private final BluetoothAdapter bluetoothAdapter;
  private BluetoothLeScanner scanner;
  private final ArrayList<Observer> observers = new ArrayList<>();

  private Timer batchTimer;
  private volatile boolean scanning = false;
  private volatile boolean listening = false;

  // Accumulated devices between batch intervals, deduplicated by MAC address
  private final ConcurrentHashMap<String, BleDevice> accumulatedDevices = new ConcurrentHashMap<>();

  // Latest batch snapshot delivered to observers
  private BleDevice[] latestBatch;

  /**
   * Creates a new BleDataProcessor.
   *
   * @param context application context for Bluetooth access and permission checks
   */
  public BleDataProcessor(Context context) {
    this.context = context;
    BluetoothManager btManager =
        (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
    this.bluetoothAdapter = (btManager != null) ? btManager.getAdapter() : null;

    if (bluetoothAdapter == null) {
      Log.w(TAG, "Bluetooth not available on this device");
    }
  }

  /**
   * Starts continuous BLE scanning with periodic batch reporting every 5 seconds.
   */
  public synchronized void startListening() {
    if (listening) {
      return;
    }

    if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
      Log.w(TAG, "Bluetooth is not enabled, skipping BLE scan");
      return;
    }

    if (!checkBlePermissions()) {
      Log.w(TAG, "BLE permissions not granted, skipping BLE scan");
      return;
    }

    try {
      this.scanner = bluetoothAdapter.getBluetoothLeScanner();
    } catch (SecurityException e) {
      Log.e(TAG, "SecurityException getting BLE scanner", e);
      return;
    }

    if (scanner == null) {
      Log.w(TAG, "BLE scanner not available");
      return;
    }

    startBleScan();
    if (!scanning) {
      return;
    }

    listening = true;

    // Periodic batch collection matching WiFi's 5-second rhythm
    this.batchTimer = new Timer();
    this.batchTimer.scheduleAtFixedRate(new TimerTask() {
      @Override
      public void run() {
        processBatch();
      }
    }, BATCH_INTERVAL_MS, BATCH_INTERVAL_MS);
  }

  /**
   * Stops BLE scanning and cancels the batch timer.
   */
  public synchronized void stopListening() {
    if (!listening) {
      return;
    }
    listening = false;

    if (batchTimer != null) {
      batchTimer.cancel();
      batchTimer = null;
    }
    stopBleScan();
  }

  private void startBleScan() {
    if (scanner == null || scanning) return;

    try {
      ScanSettings settings = new ScanSettings.Builder()
          .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
          .build();
      scanner.startScan(null, settings, scanCallback);
      scanning = true;
    } catch (SecurityException e) {
      Log.e(TAG, "SecurityException starting BLE scan", e);
    }
  }

  private void stopBleScan() {
    if (scanner != null && scanning) {
      try {
        scanner.stopScan(scanCallback);
      } catch (SecurityException | IllegalStateException e) {
        Log.e(TAG, "Error stopping BLE scan", e);
      }
      scanning = false;
    }
  }

  private final ScanCallback scanCallback = new ScanCallback() {
    @Override
    public void onScanResult(int callbackType, ScanResult result) {
      if (result == null || result.getDevice() == null) return;

      try {
        BleDevice device = createBleDevice(result);
        if (device != null && device.getMacAddress() != null) {
          // Keep the strongest signal per MAC address
          BleDevice existing = accumulatedDevices.get(device.getMacAddress());
          if (existing == null || device.getRssi() > existing.getRssi()) {
            accumulatedDevices.put(device.getMacAddress(), device);
          }
        }
      } catch (SecurityException e) {
        Log.e(TAG, "SecurityException processing BLE scan result", e);
      }
    }

    @Override
    public void onScanFailed(int errorCode) {
      Log.e(TAG, "BLE scan failed with error code: " + errorCode);
      scanning = false;
    }
  };

  /**
   * Constructs a {@link BleDevice} from a BLE {@link ScanResult}.
   */
  private BleDevice createBleDevice(ScanResult result) throws SecurityException {
    BleDevice device = new BleDevice();
    device.setMacAddress(result.getDevice().getAddress());
    device.setRssi(result.getRssi());

    // getName() requires BLUETOOTH_CONNECT on Android 12+
    String name = null;
    try {
      name = result.getDevice().getName();
    } catch (SecurityException e) {
      // Permission not granted for name; continue without it
    }
    device.setName(name != null ? name : "");

    // TX power level
    device.setTxPowerLevel(result.getTxPower());

    // Parse ScanRecord for additional metadata
    android.bluetooth.le.ScanRecord scanRecord = result.getScanRecord();
    if (scanRecord != null) {
      device.setAdvertiseFlags(scanRecord.getAdvertiseFlags());

      // Service UUIDs
      List<ParcelUuid> uuids = scanRecord.getServiceUuids();
      if (uuids != null) {
        List<String> uuidStrings = new ArrayList<>();
        for (ParcelUuid uuid : uuids) {
          uuidStrings.add(uuid.toString());
        }
        device.setServiceUuids(uuidStrings);
      }

      // Manufacturer data (first entry if present)
      SparseArray<byte[]> mfData = scanRecord.getManufacturerSpecificData();
      if (mfData != null && mfData.size() > 0) {
        device.setManufacturerData(mfData.valueAt(0));
      }
    }

    return device;
  }

  /**
   * Takes a snapshot of accumulated BLE devices and notifies all observers.
   */
  private void processBatch() {
    if (accumulatedDevices.isEmpty()) return;

    // Snapshot current batch and clear buffer for next scan window
    latestBatch = accumulatedDevices.values().toArray(new BleDevice[0]);
    accumulatedDevices.clear();

    notifyObservers(0);
  }

  private boolean checkBlePermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      return ActivityCompat.checkSelfPermission(context,
          Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    } else {
      // Pre-Android 12: BLE scanning requires location permission
      return ActivityCompat.checkSelfPermission(context,
          Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
  }

  @Override
  public void registerObserver(Observer o) {
    observers.add(o);
  }

  @Override
  public void notifyObservers(int idx) {
    for (Observer o : observers) {
      o.update(latestBatch);
    }
  }
}
