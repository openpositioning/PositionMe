package com.openpositioning.PositionMe.sensors.SensorData;

public class GravityData extends PhysicalSensorData {
  public final float[] gravity = new float[3];

  public GravityData(float[] values) {
    gravity[0] = values[0];
    gravity[1] = values[1];
    gravity[2] = values[2];
  }
}
