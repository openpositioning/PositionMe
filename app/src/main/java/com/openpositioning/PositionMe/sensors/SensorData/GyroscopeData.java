package com.openpositioning.PositionMe.sensors.SensorData;

public class GyroscopeData extends SensorData {
  public final float[] angularVelocity = new float[3];

  public GyroscopeData(float[] values) {
    System.arraycopy(values, 0, angularVelocity, 0, 3);
  }
}
