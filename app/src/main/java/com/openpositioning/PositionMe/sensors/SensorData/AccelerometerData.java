package com.openpositioning.PositionMe.sensors.SensorData;

public class AccelerometerData extends SensorData {
  public final float[] acceleration = new float[3];

  public AccelerometerData(float[] values) {
    System.arraycopy(values, 0, acceleration, 0, 3);
  }
}
