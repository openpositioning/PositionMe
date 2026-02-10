package com.openpositioning.PositionMe.sensors;

import com.openpositioning.PositionMe.presentation.fragment.MeasurementsFragment;

/**
 * BLE data object (fingerprint observation).
 *
 * Holds basic BLE discovery information:
 * - mac: device address
 * - rssi: signal level
 * - name: device name (nullable)
 * - uuid: optional (classic discovery usually cannot provide service UUIDs reliably)
 * - timestamp: elapsed realtime when observed
 */
public class BLE {

    private String mac;        // device address
    private int rssi;          // signal strength
    private String name;       // nullable
    private String uuid;       // optional, often "unknown"
    private long timestampMs;  // elapsed realtime (ms)

    public BLE() {}

    public String getMac() { return mac; }
    public int getRssi() { return rssi; }
    public String getName() { return name; }
    public String getUuid() { return uuid; }
    public long getTimestampMs() { return timestampMs; }

    public void setMac(String mac) { this.mac = mac; }
    public void setRssi(int rssi) { this.rssi = rssi; }
    public void setName(String name) { this.name = name; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    public void setTimestampMs(long timestampMs) { this.timestampMs = timestampMs; }

    /**
     * For displaying in {@link MeasurementsFragment}.
     */
    @Override
    public String toString() {
        String n = (name == null || name.isEmpty()) ? "unknown" : name;
        String u = (uuid == null || uuid.isEmpty()) ? "unknown" : uuid;
        String m = (mac == null) ? "null" : mac;
        return "mac: " + m + ", rssi: " + rssi + ", name: " + n + ", uuid: " + u;
    }
}
