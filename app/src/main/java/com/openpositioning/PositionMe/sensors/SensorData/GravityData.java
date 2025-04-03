package com.openpositioning.PositionMe.sensors.SensorData;

/**
 * Class representing gravity sensor data.
 * <p>
 * This class extends the PhysicalSensorData class and holds the gravity values
 * for the x, y, and z axes.
 * </p>
 *
 * @author Philip Heptonstall
 */
public class GravityData extends PhysicalSensorData {
  // Array to store gravity values for x, y, and z axes
  public final float[] gravity = new float[3];

  /**
   * Constructor for GravityData.
   * <p>
   * Initializes the gravity values with the provided array.
   * </p>
   *
   * @param values Array containing gravity values for x, y, and z axes.
   */
  public GravityData(float[] values) {
    gravity[0] = values[0];
    gravity[1] = values[1];
    gravity[2] = values[2];
  }
}