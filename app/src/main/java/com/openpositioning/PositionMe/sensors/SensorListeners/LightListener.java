package com.openpositioning.PositionMe.sensors.SensorListeners;

import com.openpositioning.PositionMe.sensors.SensorData.LightData;

public interface LightListener extends SensorDataListener<LightData> {
  @Override
  abstract void onSensorDataReceived(LightData data);
}
