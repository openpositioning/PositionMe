package com.openpositioning.PositionMe.sensors.SensorData;

/**
 * Class representing magnetic field data.
 * <p>
 * This class extends the PhysicalSensorData class and holds the magnetic field values
 * for the x, y, and z axes.
 * </p>
 *
 * @author Philip Heptonstall
 */
public class MagneticFieldData extends PhysicalSensorData {
  // Array to store magnetic field values for x, y, and z axes
  public final float[] magneticField = new float[3];

  /**
   * Constructor for MagneticFieldData.
   * <p>
   * Initializes the magnetic field values with the provided array.
   * </p>
   *
   * @param values Array containing magnetic field values for x, y, and z axes.
   */
  public MagneticFieldData(float[] values) {
    this.magneticField[0] = values[0];
    this.magneticField[1] = values[1];
    this.magneticField[2] = values[2];
  }
}