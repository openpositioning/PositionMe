package com.openpositioning.PositionMe.sensors.SensorListeners;

import com.openpositioning.PositionMe.sensors.SensorData.GNSSLocationData;

public interface GnssListener extends SensorDataListener<GNSSLocationData> {
  @Override
  abstract void onSensorDataReceived(GNSSLocationData data);
}
