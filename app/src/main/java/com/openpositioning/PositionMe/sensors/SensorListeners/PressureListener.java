package com.openpositioning.PositionMe.sensors.SensorListeners;

import com.openpositioning.PositionMe.sensors.SensorData.PressureData;

public interface PressureListener extends SensorDataListener<PressureData> {
  @Override
  abstract void onSensorDataReceived(PressureData data);
}
