package com.openpositioning.PositionMe.sensors.SensorListeners;

import com.openpositioning.PositionMe.sensors.SensorData.WiFiData;

public interface WifiListener extends SensorDataListener<WiFiData> {
  @Override
  abstract void onSensorDataReceived(WiFiData data);
}
