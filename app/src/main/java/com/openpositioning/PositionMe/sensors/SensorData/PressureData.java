package com.openpositioning.PositionMe.sensors.SensorData;


import android.hardware.SensorManager;

public class PressureData extends SensorData {
  // Smoothing parameter to avoid too much jitter in pressure data samples
  private static final float ALPHA = 0.9f;
  public final float pressure;
  public final float altitude;

  public PressureData(float rawPressure) {
    this.pressure = (1 - ALPHA) * rawPressure + ALPHA * rawPressure;
    this.altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure);
  }
}
