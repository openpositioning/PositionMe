package com.openpositioning.PositionMe.sensors.SensorData;

public class MagneticFieldData extends PhysicalSensorData {
  public final float[] magneticField = new float[3];

  public MagneticFieldData(float[] values) {
    this.magneticField[0] = values[0];
    this.magneticField[1] = values[1];
    this.magneticField[2] = values[2];
  }
}
