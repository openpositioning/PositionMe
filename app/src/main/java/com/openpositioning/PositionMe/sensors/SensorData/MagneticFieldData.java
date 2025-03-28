package com.openpositioning.PositionMe.sensors.SensorData;

public class MagneticFieldData extends SensorData {
  public final float[] magneticField = new float[3];

  public MagneticFieldData(float[] values) {
    System.arraycopy(values, 0, magneticField, 0, 3);
  }
}
