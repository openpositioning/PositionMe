package com.openpositioning.PositionMe.sensors;

import android.util.SparseArray;

/**
 * BLE device information class.
 *
 * @author Vlad Stratulat
 */
public class BleDevice {
    private String macAddress;
    private int rssi;
    private String name;
    private int txPowerLevel;
    private int advertiseFlags;
    private String[] serviceUuids;
    private SparseArray<byte[]> manufacturerData;

    public BleDevice() {}

    public String getMacAddress() { return macAddress; }
    public int getRssi() { return rssi; }
    public String getName() { return name; }
    public int getTxPowerLevel() { return txPowerLevel; }
    public int getAdvertiseFlags() { return advertiseFlags; }
    public String[] getServiceUuids() { return serviceUuids; }
    public SparseArray<byte[]> getManufacturerData() { return manufacturerData; }

    public void setMacAddress(String macAddress) { this.macAddress = macAddress; }
    public void setRssi(int rssi) { this.rssi = rssi; }
    public void setName(String name) { this.name = name; }
    public void setTxPowerLevel(int txPowerLevel) { this.txPowerLevel = txPowerLevel; }
    public void setAdvertiseFlags(int advertiseFlags) { this.advertiseFlags = advertiseFlags; }
    public void setServiceUuids(String[] serviceUuids) { this.serviceUuids = serviceUuids; }
    public void setManufacturerData(SparseArray<byte[]> manufacturerData) {
        this.manufacturerData = manufacturerData;
    }
}