package com.openpositioning.PositionMe.sensors.SensorListeners;

import com.openpositioning.PositionMe.sensors.SensorData.GravityData;

public interface GravityListener extends SensorDataListener<GravityData> {
  @Override
  abstract void onSensorDataReceived(GravityData data);
}
