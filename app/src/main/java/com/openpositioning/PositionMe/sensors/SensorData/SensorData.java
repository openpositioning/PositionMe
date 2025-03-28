package com.openpositioning.PositionMe.sensors.SensorData;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.os.SystemClock;

public abstract class SensorData {
  public final long timestamp;

  public SensorData() {
    this.timestamp = SystemClock.uptimeMillis();
  }

}


