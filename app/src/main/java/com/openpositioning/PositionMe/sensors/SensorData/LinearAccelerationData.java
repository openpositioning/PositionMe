package com.openpositioning.PositionMe.sensors.SensorData;

public class LinearAccelerationData extends PhysicalSensorData {
  public final float[] filteredAcc = new float[3];
  public final double accelMagnitude;

  public LinearAccelerationData(float[] values) {
    filteredAcc[0] = values[0];
    filteredAcc[1] = values[1];
    filteredAcc[2] = values[2];
    this.accelMagnitude = Math.sqrt(
        Math.pow(filteredAcc[0], 2) +
            Math.pow(filteredAcc[1], 2) +
            Math.pow(filteredAcc[2], 2)
    );
  }
}
