package com.openpositioning.PositionMe.sensors.SensorData;

import android.os.SystemClock;

/**
 * Class representing step detector data.
 * <p>
 * This class extends the PhysicalSensorData class and holds the timestamp of the detected step.
 * </p>
 *
 * @author Philip Heptonstall
 */
public class StepDetectorData extends PhysicalSensorData {
  // Timestamp of the detected step
  public final long stepTime;

  /**
   * Constructor for StepDetectorData.
   * <p>
   * Initializes the stepTime with the current uptime in milliseconds.
   * </p>
   */
  public StepDetectorData() {
    this.stepTime = SystemClock.uptimeMillis();
  }
}