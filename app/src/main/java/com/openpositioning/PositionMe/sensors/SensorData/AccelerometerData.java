package com.openpositioning.PositionMe.sensors.SensorData;

/**
 * Class representing accelerometer data.
 * <p>
 * This class extends the PhysicalSensorData class and holds the acceleration values
 * for the x, y, and z axes.
 * </p>
 *
 * @author Philip Heptonstall
 */
public class AccelerometerData extends PhysicalSensorData {
  // Array to store acceleration values for x, y, and z axes
  public final float[] acceleration = new float[3];

  /**
   * Constructor for AccelerometerData.
   * <p>
   * Initializes the acceleration values with the provided array.
   * </p>
   *
   * @param values Array containing acceleration values for x, y, and z axes.
   */
  public AccelerometerData(float[] values) {
    this.acceleration[0] = values[0];
    this.acceleration[1] = values[1];
    this.acceleration[2] = values[2];
  }
}