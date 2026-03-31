package com.openpositioning.PositionMe.sensors;

// Data class for Bluetooth Low Energy (BLE) devices.
// Holds MAC address, device name, and signal strength (RSSI) for discovered BLE devices.
// @author GitHub Copilot
public class BleDevice {
    private String macAddress;
    private String name;
    private int rssi;

    public BleDevice() {}

    public BleDevice(String macAddress, String name, int rssi) {
        this.macAddress = macAddress;
        this.name = name;
        this.rssi = rssi;
    }

    public String getMacAddress() { return macAddress; }
    public String getName() { return name; }
    public int getRssi() { return rssi; }

    public void setMacAddress(String macAddress) { this.macAddress = macAddress; }
    public void setName(String name) { this.name = name; }
    public void setRssi(int rssi) { this.rssi = rssi; }

    @Override
    public String toString() {
        return "MAC: " + macAddress + ", RSSI: " + rssi + " dBm";
    }
}


