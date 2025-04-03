package com.openpositioning.PositionMe.sensors.SensorData;

import android.location.Location;

/**
 * Class representing GNSS location data.
 * <p>
 * This class extends the SensorData class and holds the GNSS location values
 * such as latitude, longitude, altitude, accuracy, vertical accuracy, speed, and provider.
 * </p>
 *
 * @author Philip Heptonstall
 */
public class GNSSLocationData extends SensorData {

  // Latitude of the location
  public final float latitude;
  // Longitude of the location
  public final float longitude;
  // Altitude of the location
  public final float altitude;
  // Horizontal accuracy of the location, expressed as 1 std. deviation
  public final float accuracy;
  // Vertical accuracy of the location, expressed as 1 std. deviation
  public final float verticalAccuracy;
  // Speed at the location
  public final float speed;
  // Provider of the location data
  public final String provider;

  /**
   * Constructor for GNSSLocationData.
   * <p>
   * Initializes the GNSS location data with the provided Location object.
   * </p>
   *
   * @param location Location object containing GNSS location data.
   */
  public GNSSLocationData(Location location) {
    super();
    this.latitude = (float) location.getLatitude();
    this.longitude = (float) location.getLongitude();
    this.altitude = (float) location.getAltitude();
    this.verticalAccuracy = location.getVerticalAccuracyMeters();
    this.accuracy = location.getAccuracy();
    this.speed = location.getSpeed();
    this.provider = location.getProvider();
  }

  /**
   * Constructor for GNSSLocationData.
   * <p>
   * Initializes the GNSS location data with the provided parameters.
   * </p>
   *
   * @param timestamp Timestamp of the location data.
   * @param latitude Latitude of the location.
   * @param longitude Longitude of the location.
   * @param altitude Altitude of the location.
   * @param accuracy Horizontal accuracy of the location, given as 1 std. deviation.
   * @param verticalAccuracy Vertical accuracy of the location, given as 1 std. deviation.
   * @param speed Speed at the location.
   * @param provider Provider of the location data.
   */
  public GNSSLocationData(long timestamp, float latitude, float longitude, float altitude,
      float accuracy, float verticalAccuracy, float speed, String provider) {
    super();
    this.latitude = latitude;
    this.longitude = longitude;
    this.altitude = altitude;
    this.accuracy = accuracy;
    this.verticalAccuracy = verticalAccuracy;
    this.speed = speed;
    this.provider = provider;
  }
}