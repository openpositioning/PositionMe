package com.openpositioning.PositionMe.sensors.SensorListeners;

import com.openpositioning.PositionMe.sensors.SensorData.StepDetectorData;

public interface StepDetectorListener extends SensorDataListener<StepDetectorData> {
  @Override
  abstract void onSensorDataReceived(StepDetectorData data);
}
