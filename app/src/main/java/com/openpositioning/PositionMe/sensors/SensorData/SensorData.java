package com.openpositioning.PositionMe.sensors.SensorData;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.os.SystemClock;

/**
 * Abstract class representing sensor data.
 * <p>
 * This class provides a common structure for all sensor data types, including a timestamp.
 * </p>
 *
 * @author Philip Heptonstall
 */
public abstract class SensorData {
  // Timestamp of the sensor data
  public final long timestamp;

  /**
   * Constructor for SensorData.
   * <p>
   * Initializes the timestamp with the current uptime in milliseconds.
   * </p>
   */
  public SensorData() {
    this.timestamp = SystemClock.uptimeMillis();
  }
}