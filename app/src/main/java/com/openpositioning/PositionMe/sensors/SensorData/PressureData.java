package com.openpositioning.PositionMe.sensors.SensorData;

import android.hardware.SensorManager;

/**
 * Class representing pressure data.
 * <p>
 * This class extends the PhysicalSensorData class and holds the pressure and altitude values.
 * It also applies a smoothing parameter to avoid too much jitter in pressure data samples.
 * </p>
 *
 * @author Philip Heptonstall
 */
public class PressureData extends PhysicalSensorData {
  // Smoothing parameter to avoid too much jitter in pressure data samples
  private static final float ALPHA = 0.9f;
  // Pressure value
  public final float pressure;
  // Altitude value calculated from the pressure
  public final float altitude;

  /**
   * Constructor for PressureData.
   * <p>
   * Initializes the pressure value with a smoothing parameter and calculates the altitude.
   * </p>
   *
   * @param rawPressure Raw pressure value from the sensor.
   */
  public PressureData(float rawPressure) {
    this.pressure = (1 - ALPHA) * rawPressure + ALPHA * rawPressure;
    this.altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressure);
  }
}