package com.openpositioning.PositionMe.sensors.SensorData;

import android.os.SystemClock;

public class StepDetectorData extends PhysicalSensorData {
  public final long stepTime;

  public StepDetectorData() {
    this.stepTime = SystemClock.uptimeMillis();
  }
}
