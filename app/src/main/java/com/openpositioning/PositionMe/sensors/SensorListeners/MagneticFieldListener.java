package com.openpositioning.PositionMe.sensors.SensorListeners;

import com.openpositioning.PositionMe.sensors.SensorData.MagneticFieldData;

public interface MagneticFieldListener extends SensorDataListener<MagneticFieldData> {
  @Override
  abstract void onSensorDataReceived(MagneticFieldData data);
}
