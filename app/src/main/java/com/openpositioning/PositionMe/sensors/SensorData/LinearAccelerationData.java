package com.openpositioning.PositionMe.sensors.SensorData;

public class LinearAccelerationData extends SensorData {
  public final float[] filteredAcc = new float[3];
  public final double accelMagnitude;

  public LinearAccelerationData(float[] values) {
    System.arraycopy(values, 0, filteredAcc, 0, 3);
    this.accelMagnitude = Math.sqrt(
        Math.pow(filteredAcc[0], 2) +
            Math.pow(filteredAcc[1], 2) +
            Math.pow(filteredAcc[2], 2)
    );
  }
}
