package com.openpositioning.PositionMe.sensors.SensorListeners;

import com.openpositioning.PositionMe.sensors.SensorData.GyroscopeData;

public interface GyroscopeListener extends SensorDataListener<GyroscopeData> {
  @Override
  abstract void onSensorDataReceived(GyroscopeData data);
}
