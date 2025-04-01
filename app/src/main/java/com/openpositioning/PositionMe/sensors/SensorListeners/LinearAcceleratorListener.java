package com.openpositioning.PositionMe.sensors.SensorListeners;

import com.openpositioning.PositionMe.sensors.SensorData.LinearAccelerationData;

public interface LinearAcceleratorListener extends SensorDataListener<LinearAccelerationData> {
  @Override
  abstract void onSensorDataReceived(LinearAccelerationData data);
}
