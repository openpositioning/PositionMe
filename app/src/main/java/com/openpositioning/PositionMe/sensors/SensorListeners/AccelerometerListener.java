package com.openpositioning.PositionMe.sensors.SensorListeners;

import com.openpositioning.PositionMe.sensors.SensorData.AccelerometerData;

public interface AccelerometerListener extends SensorDataListener<AccelerometerData> {
  @Override
  abstract void onSensorDataReceived(AccelerometerData data);
}
