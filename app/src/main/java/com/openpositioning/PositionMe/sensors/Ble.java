package com.openpositioning.PositionMe.sensors;
import android.os.ParcelUuid;
import android.util.SparseArray;
import java.util.List;

public class Ble {
    private long mac_long;
    private String mac_str; // Mac address in string
    private int rssi;
    private int rssiSum = 0;
    private int rssiCount = 0;
    private String name;
    private int txPower;
    private int advertiseFlags;
    private List<String> serviceUuids;
    private SparseArray<byte[]> manufacturerData;

    public Ble() {}

    // Getter methods
    public long getMacLong() { return mac_long; }
    public String getMacStr() { return mac_str; }
    public int getRssi() {
        rssi = rssiSum / rssiCount;
        return rssi;
    }
    public String getName() { return name; }
    public int getTxPower() { return txPower; }
    public int getAdvertiseFlags() { return advertiseFlags; }
    public List<String> getServiceUuids() { return serviceUuids; }
    public SparseArray<byte[]> getManufacturerData() { return manufacturerData; }

    // Setter Methods
    public void setMacLong(long mac) { this.mac_long = mac; }
    public void setMacStr(String mac) { this.mac_str = mac; }
    public void setName(String name) { this.name = name; }
    public void setTxPower(int txPower) { this.txPower = txPower; }
    public void setAdvertiseFlags(int advertiseFlags) { this.advertiseFlags = advertiseFlags; }
    public void setServiceUuids(List<String> serviceUuids) { this.serviceUuids = serviceUuids; }
    public void setManufacturerData(SparseArray<byte[]> manufacturerData) { this.manufacturerData = manufacturerData; }

    public void addRssi(int rssi){
        rssiSum += rssi;
        rssiCount ++;
    }
}