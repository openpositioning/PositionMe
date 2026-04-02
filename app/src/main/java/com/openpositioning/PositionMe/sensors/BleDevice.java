package com.openpositioning.PositionMe.sensors;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds data from a single BLE (Bluetooth Low Energy) scan result.
 *
 * <p>Analogous to {@link Wifi} for WiFi scan results, storing the properties
 * needed for BLE fingerprinting and metadata recording in the trajectory protobuf.</p>
 */
public class BleDevice {

  private String macAddress;
  private long macAsLong;
  private String name;
  private int rssi;
  private int txPowerLevel;
  private int advertiseFlags;
  private List<String> serviceUuids = new ArrayList<>();
  private byte[] manufacturerData;

  public BleDevice() {}

  // --- Getters ---

  public String getMacAddress() { return macAddress; }
  public long getMacAsLong() { return macAsLong; }
  public String getName() { return name; }
  public int getRssi() { return rssi; }
  public int getTxPowerLevel() { return txPowerLevel; }
  public int getAdvertiseFlags() { return advertiseFlags; }
  public List<String> getServiceUuids() { return serviceUuids; }
  public byte[] getManufacturerData() { return manufacturerData; }

  // --- Setters ---

  public void setMacAddress(String macAddress) {
    this.macAddress = macAddress;
    this.macAsLong = convertMacToLong(macAddress);
  }

  public void setName(String name) { this.name = name; }
  public void setRssi(int rssi) { this.rssi = rssi; }
  public void setTxPowerLevel(int txPowerLevel) { this.txPowerLevel = txPowerLevel; }
  public void setAdvertiseFlags(int advertiseFlags) { this.advertiseFlags = advertiseFlags; }
  public void setServiceUuids(List<String> serviceUuids) { this.serviceUuids = serviceUuids; }
  public void setManufacturerData(byte[] manufacturerData) { this.manufacturerData = manufacturerData; }

  /**
   * Converts a MAC address string (e.g. "AA:BB:CC:DD:EE:FF") to a long integer.
   * Uses the same encoding convention as WiFi BSSID in {@link WifiDataProcessor}.
   */
  static long convertMacToLong(String mac) {
    if (mac == null || mac.length() != 17) return 0;
    long result = 0;
    int colonCount = 5;
    for (int j = 0; j < 17; j++) {
      char c = mac.charAt(j);
      if (c != ':') {
        int digit;
        if (c >= '0' && c <= '9') {
          digit = c - '0';
        } else if (c >= 'a' && c <= 'f') {
          digit = c - 'a' + 10;
        } else if (c >= 'A' && c <= 'F') {
          digit = c - 'A' + 10;
        } else {
          continue;
        }
        result += (long) digit * ((long) Math.pow(16, 16 - j - colonCount));
      } else {
        colonCount--;
      }
    }
    return result;
  }

  @Override
  public String toString() {
    return "BleDevice{mac=" + macAddress + ", rssi=" + rssi + ", name=" + name + "}";
  }
}
