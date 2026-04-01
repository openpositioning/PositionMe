package com.openpositioning.PositionMe.sensors;

import android.util.Log;

/**
 * Estimates BLE RTT-style ranging values from BLE scan batches.
 *
 * <p>BLE RTT is not available as a platform ranging API for generic advertisements,
 * so this class derives distance estimates from RSSI and TxPower and records them
 * into the trajectory through {@link TrajectoryRecorder}.</p>
 */
public class BleRttManager implements Observer {

  private static final String TAG = "BleRttManager";

  /**
   * Path-loss exponent for indoor propagation. Higher values model stronger attenuation.
   */
  private static final float PATH_LOSS_EXPONENT = 2.2f;

  /**
   * Fallback TxPower at 1 meter when scan result does not provide TxPower.
   */
  private static final int DEFAULT_TX_POWER_DBM = -59;

  /**
   * Clamp BLE distance estimates to avoid unrealistic outliers.
   */
  private static final float MAX_DISTANCE_METERS = 50f;

  private final TrajectoryRecorder recorder;

  /**
   * Creates a BLE RTT estimator.
   *
   * @param recorder trajectory recorder used to persist estimated readings
   */
  public BleRttManager(TrajectoryRecorder recorder) {
    this.recorder = recorder;
  }

  @Override
  public void update(Object[] objList) {
    if (objList == null || objList.length == 0) {
      return;
    }
    if (!recorder.isRecording()) {
      return;
    }

    for (Object obj : objList) {
      if (!(obj instanceof BleDevice)) {
        continue;
      }
      BleDevice device = (BleDevice) obj;
      BleRttReading reading = estimateReading(device);
      if (reading != null) {
        recorder.addBleRttReading(reading);
      }
    }
  }

  private BleRttReading estimateReading(BleDevice device) {
    if (device.getMacAddress() == null || device.getMacAddress().isEmpty()) {
      return null;
    }

    int rssi = device.getRssi();
    if (rssi >= 0) {
      return null;
    }

    int txPower = sanitiseTxPower(device.getTxPowerLevel());

    // Log-distance path loss model: d = 10 ^ ((txPower - rssi) / (10 * n))
    float distanceMeters = (float) Math.pow(10d,
        (txPower - rssi) / (10f * PATH_LOSS_EXPONENT));
    distanceMeters = Math.max(0.1f, Math.min(distanceMeters, MAX_DISTANCE_METERS));

    float distanceMm = distanceMeters * 1000f;
    float stdMm = Math.max(300f, distanceMm * 0.35f);

    long mac = device.getMacAsLong();
    if (mac == 0L) {
      mac = BleDevice.convertMacToLong(device.getMacAddress());
    }

    if (mac == 0L) {
      Log.d(TAG, "Skip BLE RTT estimate due to invalid MAC: " + device.getMacAddress());
      return null;
    }

    return new BleRttReading(mac, distanceMm, stdMm, rssi);
  }

  private int sanitiseTxPower(int txPowerLevel) {
    // Android reports 127 when TxPower is unavailable in BLE scan results.
    if (txPowerLevel == 127 || txPowerLevel == 0) {
      return DEFAULT_TX_POWER_DBM;
    }
    return txPowerLevel;
  }
}
