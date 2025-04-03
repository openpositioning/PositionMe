package com.openpositioning.PositionMe.sensors.SensorData;

/**
 * Class representing proximity data.
 * <p>
 * This class extends the PhysicalSensorData class and holds the proximity value.
 * </p>
 *
 * @author Philip Heptonstall
 */
public class ProximityData extends PhysicalSensorData {
  // Proximity value
  public final float proximity;

  /**
   * Constructor for ProximityData.
   * <p>
   * Initializes the proximity value with the provided value.
   * </p>
   *
   * @param proximity Proximity value from the sensor.
   */
  public ProximityData(float proximity) {
    this.proximity = proximity;
  }
}