package com.openpositioning.PositionMe.sensors.SensorData;

public class AccelerometerData extends PhysicalSensorData {
  public final float[] acceleration = new float[3];

  public AccelerometerData(float[] values) {
    this.acceleration[0] = values[0];
    this.acceleration[1] = values[1];
    this.acceleration[2] = values[2];
  }
}
