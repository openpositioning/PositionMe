package com.openpositioning.PositionMe.sensors.SensorData;

import com.openpositioning.PositionMe.sensors.Wifi;
import java.util.List;

public class WiFiScanResult extends SensorData {
  public final List<Wifi> wifiList;

  public WiFiScanResult(List<Wifi> wifiResults) {
    super();
    this.wifiList = wifiResults;
  }
}
