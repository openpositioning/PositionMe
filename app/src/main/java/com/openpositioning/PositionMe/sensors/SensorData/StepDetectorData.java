package com.openpositioning.PositionMe.sensors.SensorData;

public class StepDetectorData extends SensorData {
  public final long stepTime;

  public StepDetectorData() {
    this.stepTime = SystemClock.uptimeMillis();
  }
}
