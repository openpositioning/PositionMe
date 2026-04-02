package com.openpositioning.PositionMe.sensors;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.WifiRttManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Manages WiFi RTT (802.11mc) ranging measurements and writes results to the
 * trajectory protobuf via {@link TrajectoryRecorder}.
 *
 * <p>Implements {@link Observer} to receive WiFi scan updates from
 * {@link WifiDataProcessor}. After each WiFi scan, it triggers RTT ranging
 * for APs that support 802.11mc ({@code rtt_enabled} flag).</p>
 */
public class RttManager implements Observer {

  private static final String TAG = "RttManager";
  private static final int MAX_RTT_APS = 10;

  private final Context context;
  private final WifiRttManager wifiRttManager;
  private final TrajectoryRecorder recorder;
  private final WifiDataProcessor wifiDataProcessor;
  private final Executor executor;
  private final boolean rttSupported;

  /**
   * Creates a new RttManager.
   *
   * @param context           application context
   * @param recorder          trajectory recorder for writing RTT results
   * @param wifiDataProcessor WiFi data processor to retrieve RTT-eligible ScanResults
   */
  public RttManager(Context context, TrajectoryRecorder recorder,
                      WifiDataProcessor wifiDataProcessor) {
    this.context = context;
    this.recorder = recorder;
    this.wifiDataProcessor = wifiDataProcessor;
    this.executor = Executors.newSingleThreadExecutor();

    this.rttSupported = context.getPackageManager()
        .hasSystemFeature(PackageManager.FEATURE_WIFI_RTT);

    if (rttSupported) {
      this.wifiRttManager = (WifiRttManager)
          context.getSystemService(Context.WIFI_RTT_RANGING_SERVICE);
    } else {
      this.wifiRttManager = null;
      Log.w(TAG, "WiFi RTT is not supported on this device");
    }
  }

  /**
   * Returns whether WiFi RTT is supported on this device.
   */
  public boolean isRttSupported() {
    return rttSupported;
  }

  /**
   * Receives WiFi scan updates from {@link WifiDataProcessor} and triggers
   * RTT ranging for any rtt-enabled APs found in the scan.
   */
  @Override
  public void update(Object[] objList) {
    if (!rttSupported || wifiRttManager == null) return;
    if (!recorder.isRecording()) return;

    // Check if RTT is currently available (WiFi may be off)
    if (!wifiRttManager.isAvailable()) {
      Log.d(TAG, "WiFi RTT not currently available, skipping ranging");
      return;
    }

    List<ScanResult> rttEligible = wifiDataProcessor.getRttEligibleScanResults();
    if (rttEligible == null || rttEligible.isEmpty()) return;

    performRanging(rttEligible);
  }

  private void performRanging(List<ScanResult> rttEligible) {
    if (ActivityCompat.checkSelfPermission(context,
        Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "Fine location permission not granted for RTT ranging");
      return;
    }

    // Limit number of APs per ranging request
    List<ScanResult> targets = rttEligible.size() > MAX_RTT_APS
        ? rttEligible.subList(0, MAX_RTT_APS) : rttEligible;

    try {
      RangingRequest request = new RangingRequest.Builder()
          .addAccessPoints(targets)
          .build();

      wifiRttManager.startRanging(request, executor, new RangingResultCallback() {
        @Override
        public void onRangingResults(@NonNull List<RangingResult> results) {
          for (RangingResult result : results) {
            if (result.getStatus() == RangingResult.STATUS_SUCCESS) {
              long mac = BleDevice.convertMacToLong(
                  result.getMacAddress().toString());
              float distanceMm = result.getDistanceMm();
              float distanceStdMm = result.getDistanceStdDevMm();
              int rssi = result.getRssi();

              recorder.addWifiRttReading(mac, distanceMm,
                  distanceStdMm, rssi);
            }
          }
        }

        @Override
        public void onRangingFailure(int code) {
          Log.w(TAG, "RTT ranging failed, code=" + code);
        }
      });
    } catch (SecurityException e) {
      Log.e(TAG, "SecurityException during RTT ranging", e);
    } catch (IllegalArgumentException e) {
      Log.e(TAG, "Invalid ranging request", e);
    }
  }
}
