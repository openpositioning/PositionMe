package com.openpositioning.PositionMe.sensors;

import com.openpositioning.PositionMe.presentation.fragment.MeasurementsFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    private List<String> serviceUuids = new ArrayList<>();
    private long timestampMs;  // elapsed realtime (ms)

    public BLE() {}

    public String getMac() { return mac; }
    public int getRssi() { return rssi; }
    public String getName() { return name; }
    public String getUuid() { return uuid; }
    public List<String> getServiceUuids() {
        return Collections.unmodifiableList(serviceUuids);
    }
    public long getTimestampMs() { return timestampMs; }

    public void setMac(String mac) { this.mac = mac; }
    public void setRssi(int rssi) { this.rssi = rssi; }
    public void setName(String name) { this.name = name; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    public void setServiceUuids(List<String> serviceUuids) {
        this.serviceUuids.clear();
        if (serviceUuids == null) {
            return;
        }
        for (String serviceUuid : serviceUuids) {
            if (serviceUuid == null) {
                continue;
            }
            String normalized = serviceUuid.trim();
            if (normalized.isEmpty()) {
                continue;
            }
            this.serviceUuids.add(normalized);
        }
    }
    public void setTimestampMs(long timestampMs) { this.timestampMs = timestampMs; }

    /**
     * For displaying in {@link MeasurementsFragment}.
     */
    @Override
    public String toString() {
        String n = (name == null || name.isEmpty()) ? "unknown" : name;
        String u = (serviceUuids.isEmpty())
                ? ((uuid == null || uuid.isEmpty()) ? "unknown" : uuid)
                : serviceUuids.toString();
        String m = (mac == null) ? "null" : mac;
        return "mac: " + m + ", rssi: " + rssi + ", name: " + n + ", uuid: " + u;
    }
}
