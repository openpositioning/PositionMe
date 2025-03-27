package com.openpositioning.PositionMe.sensors;

public abstract class SensorData {
  public final long timestamp;

  public SensorData(long timestamp) {
    this.timestamp = timestamp;
  }
}


