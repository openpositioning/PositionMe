package com.openpositioning.PositionMe.sensors;

import com.openpositioning.PositionMe.sensors.SensorData.SensorData;

public interface SensorDataListener<T extends SensorData> {
    void onSensorDataReceived(T data);
}
