package com.openpositioning.PositionMe.sensors.SensorData;

/**
 * Class representing gyroscope data.
 * <p>
 * This class extends the PhysicalSensorData class and holds the angular velocity values
 * for the x, y, and z axes.
 * </p>
 *
 * @author Philip Heptonstall
 */
public class GyroscopeData extends PhysicalSensorData {
  // Array to store angular velocity values for x, y, and z axes
  public final float[] angularVelocity = new float[3];

  /**
   * Constructor for GyroscopeData.
   * <p>
   * Initializes the angular velocity values with the provided array.
   * </p>
   *
   * @param values Array containing angular velocity values for x, y, and z axes.
   */
  public GyroscopeData(float[] values) {
    angularVelocity[0] = values[0];
    angularVelocity[1] = values[1];
    angularVelocity[2] = values[2];
  }
}