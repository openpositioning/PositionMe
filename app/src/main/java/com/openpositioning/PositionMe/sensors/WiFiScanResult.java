package com.openpositioning.PositionMe.sensors;

import java.util.List;

public class WiFiScanResult extends SensorData {
  public final List<String> accessPoints;

  public WiFiScanResult(long timestamp, List<String> accessPoints) {
    super(timestamp);
    this.accessPoints = accessPoints;
  }
}
