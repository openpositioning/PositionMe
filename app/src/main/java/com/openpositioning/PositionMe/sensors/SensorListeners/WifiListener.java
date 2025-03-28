package com.openpositioning.PositionMe.sensors.SensorListeners;

import com.openpositioning.PositionMe.sensors.SensorData.WiFiScanResult;

public interface WifiListener extends SensorDataListener<WiFiScanResult> {
  @Override
  abstract void onSensorDataReceived(WiFiScanResult data);
}
