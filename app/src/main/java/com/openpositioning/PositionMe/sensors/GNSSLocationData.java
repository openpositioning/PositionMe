package com.openpositioning.PositionMe.sensors;

public class GNSSLocationData extends SensorData {
  public final double latitude, longitude, altitude;

  public GNSSLocationData(long timestamp, double latitude, double longitude, double altitude) {
    super(timestamp);
    this.latitude = latitude;
    this.longitude = longitude;
    this.altitude = altitude;
  }
}
