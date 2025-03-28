package com.openpositioning.PositionMe.sensors.SensorListeners;

import com.openpositioning.PositionMe.sensors.SensorData.RotationVectorData;

public interface RotationListener extends SensorDataListener<RotationVectorData> {
  @Override
  abstract void onSensorDataReceived(RotationVectorData data);
}
