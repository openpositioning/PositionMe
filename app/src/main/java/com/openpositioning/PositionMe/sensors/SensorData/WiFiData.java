package com.openpositioning.PositionMe.sensors.SensorData;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.Wifi;
import java.util.List;

public class WiFiData extends SensorData {
  // From the Scan result
  public final List<Wifi> wifiList;

  // Processed data
  public final LatLng location;
  public final int floor;

  public WiFiData(List<Wifi> wifiResults, LatLng latLng, int floor) {
    super();
    this.wifiList = wifiResults;
    this.location = latLng;
    this.floor = floor;
  }
}
