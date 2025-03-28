package com.openpositioning.PositionMe.sensors.SensorData;

import android.location.Location;

public class GNSSLocationData extends SensorData {

  public final float latitude, longitude, altitude, accuracy, speed;
  public final String provider;

  public GNSSLocationData(Location location) {
    super();
    this.latitude = (float) location.getLatitude();
    this.longitude = (float) location.getLongitude();
    this.altitude = (float) location.getAltitude();
    this.accuracy = (float) location.getAccuracy();
    this.speed = (float) location.getSpeed();
    this.provider = location.getProvider();
  }

  public GNSSLocationData(long timestamp, float latitude, float longitude, float altitude,
      float accuracy, float speed, String provider) {
    super();
    this.latitude = latitude;
    this.longitude = longitude;
    this.altitude = altitude;
    this.accuracy = accuracy;
    this.speed = speed;
    this.provider = provider;
  }
}
