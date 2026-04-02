package com.openpositioning.PositionMe.sensors;

/**
 * Represents one BLE ranging sample used for BLE RTT-style distance recording.
 *
 * <p>Android does not expose a native BLE RTT API similar to WiFi RTT for generic beacons,
 * so this reading stores a distance estimate derived from BLE RSSI/TxPower.</p>
 */
public class BleRttReading {

  private final long mac;
  private final float distanceMm;
  private final float distanceStdMm;
  private final int rssi;

  /**
   * Creates a BLE RTT-style reading.
   *
   * @param mac            integer-encoded BLE MAC address
   * @param distanceMm     estimated distance in millimetres
   * @param distanceStdMm  estimated distance standard deviation in millimetres
   * @param rssi           received signal strength in dBm
   */
  public BleRttReading(long mac, float distanceMm, float distanceStdMm, int rssi) {
    this.mac = mac;
    this.distanceMm = distanceMm;
    this.distanceStdMm = distanceStdMm;
    this.rssi = rssi;
  }

  public long getMac() {
    return mac;
  }

  public float getDistanceMm() {
    return distanceMm;
  }

  public float getDistanceStdMm() {
    return distanceStdMm;
  }

  public int getRssi() {
    return rssi;
  }
}
