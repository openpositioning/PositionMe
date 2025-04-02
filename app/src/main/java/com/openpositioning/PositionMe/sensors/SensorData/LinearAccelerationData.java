package com.openpositioning.PositionMe.sensors.SensorData;

/**
 * Class representing linear acceleration data.
 * <p>
 * This class extends the PhysicalSensorData class and holds the filtered acceleration values
 * for the x, y, and z axes, as well as the magnitude of the acceleration.
 * </p>
 *
 * @author Philip Heptonstall
 */
public class LinearAccelerationData extends PhysicalSensorData {
  // Array to store filtered acceleration values for x, y, and z axes
  public final float[] filteredAcc = new float[3];
  // Magnitude of the acceleration
  public final double accelMagnitude;

  /**
   * Constructor for LinearAccelerationData.
   * <p>
   * Initializes the filtered acceleration values and calculates the magnitude of the acceleration.
   * </p>
   *
   * @param values Array containing filtered acceleration values for x, y, and z axes.
   */
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