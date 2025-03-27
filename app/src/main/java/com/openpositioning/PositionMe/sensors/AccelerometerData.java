package com.openpositioning.PositionMe.sensors;

public class AccelerometerData extends SensorData {
  public final float x, y, z;

  public AccelerometerData(long timestamp, float x, float y, float z) {
    super(timestamp);
    this.x = x;
    this.y = y;
    this.z = z;
  }
}
