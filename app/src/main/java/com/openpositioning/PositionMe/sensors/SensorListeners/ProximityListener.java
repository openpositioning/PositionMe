package com.openpositioning.PositionMe.sensors.SensorListeners;

import com.openpositioning.PositionMe.sensors.SensorData.ProximityData;

public interface ProximityListener extends SensorDataListener<ProximityData> {
  @Override
  abstract void onSensorDataReceived(ProximityData data);
}
