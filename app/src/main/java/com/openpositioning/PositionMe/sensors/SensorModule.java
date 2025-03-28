package com.openpositioning.PositionMe.sensors;

import com.openpositioning.PositionMe.sensors.SensorData.SensorData;

/**
 * Generic Interface for Sensor Modules that do not fit into
 * those defined by android.hardware.Sensor.
 *
 * @author Philip Heptonstall
 */
public abstract class SensorModule<T extends SensorData> {
  protected final SensorHub sensorHub;
  protected final StreamSensor sensorType;

  public SensorModule(SensorHub sensorHub, StreamSensor sensorType) {
    this.sensorHub = sensorHub;
    this.sensorType = sensorType;
  }

  public abstract void start();
  public abstract void stop();

  protected void notifyListeners(T data) {
    sensorHub.notifyStreamSensor(sensorType, data);
  }
}
