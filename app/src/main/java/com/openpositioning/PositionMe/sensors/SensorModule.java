package com.openpositioning.PositionMe.sensors;

import com.openpositioning.PositionMe.sensors.SensorData.SensorData;

/**
 * Generic Interface for Sensor Modules that do not fit into
 * those defined by android.hardware.Sensor.
 * <p>
 * This abstract class provides a structure for sensor modules that are not covered by the standard Android hardware sensors.
 * It includes methods to start and stop the sensor module, and to notify listeners with sensor data.
 * </p>
 *
 * @param <T> The type of sensor data.
 *
 * @author Philip Heptonstall
 */
public abstract class SensorModule<T extends SensorData> {
  // The SensorHub instance to which this sensor module is connected.
  protected final SensorHub sensorHub;
  // The type of the stream sensor.
  protected final StreamSensor sensorType;

  /**
   * Constructor for SensorModule.
   * <p>
   * Initializes the SensorModule with the provided SensorHub and StreamSensor type.
   * </p>
   *
   * @param sensorHub The SensorHub instance.
   * @param sensorType The type of the stream sensor.
   */
  public SensorModule(SensorHub sensorHub, StreamSensor sensorType) {
    this.sensorHub = sensorHub;
    this.sensorType = sensorType;
  }

  /**
   * Starts the sensor module.
   * <p>
   * This method should be implemented to start the sensor module and begin collecting data.
   * </p>
   */
  public abstract void start();

  /**
   * Stops the sensor module.
   * <p>
   * This method should be implemented to stop the sensor module and cease collecting data.
   * </p>
   */
  public abstract void stop();

  /**
   * Notifies listeners with the provided sensor data.
   * <p>
   * This method is used to send the collected sensor data to the registered listeners.
   * </p>
   *
   * @param data The sensor data to be notified.
   */
  protected void notifyListeners(T data) {
    sensorHub.notifyStreamSensor(sensorType, data);
  }
}