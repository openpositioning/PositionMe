package com.openpositioning.PositionMe.sensors.SensorListeners;

import com.openpositioning.PositionMe.sensors.SensorData.SensorData;

public interface SensorDataListener<T extends SensorData> {
    void onSensorDataReceived(T data);

    void stop();

    void start();

}
