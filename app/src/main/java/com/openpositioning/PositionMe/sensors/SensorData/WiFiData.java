package com.openpositioning.PositionMe.sensors.SensorData;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.Wifi;
import java.util.List;

/**
 * Class representing WiFi data.
 * <p>
 * This class extends the SensorData class and holds the list of WiFi results, the location, and the floor number.
 * </p>
 *
 * @author Philip Heptonstall
 */
public class WiFiData extends SensorData {
  // List of WiFi results from the scan
  public final List<Wifi> wifiList;

  // Processed location data
  public final LatLng location;
  // Floor number
  public final int floor;

  /**
   * Constructor for WiFiData.
   * <p>
   * Initializes the WiFi data with the provided WiFi results, location, and floor number.
   * </p>
   *
   * @param wifiResults List of WiFi results from the scan.
   * @param latLng Location data.
   * @param floor Floor number.
   */
  public WiFiData(List<Wifi> wifiResults, LatLng latLng, int floor) {
    super();
    this.wifiList = wifiResults;
    this.location = latLng;
    this.floor = floor;
  }
}