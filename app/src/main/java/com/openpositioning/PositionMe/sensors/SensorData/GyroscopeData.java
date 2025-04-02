package com.openpositioning.PositionMe.sensors.SensorData;

public class GyroscopeData extends PhysicalSensorData {
  public final float[] angularVelocity = new float[3];

  public GyroscopeData(float[] values) {
    angularVelocity[0] = values[0];
    angularVelocity[1] = values[1];
    angularVelocity[2] = values[2];
  }
}
