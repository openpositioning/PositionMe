package com.openpositioning.PositionMe.sensors.SensorData;

public class GravityData extends SensorData {
  public final float[] gravity = new float[3];

  public GravityData(float[] values) {
    System.arraycopy(values, 0, gravity, 0, 3);
  }
}
