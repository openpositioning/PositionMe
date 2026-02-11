package com.openpositioning.PositionMe.sensors;

import com.openpositioning.PositionMe.presentation.fragment.MeasurementsFragment;

/**
 * The Wifi object holds the Wifi parameters listed below.
 * * Updated to include RTT flag and UUID for latest protobuf format.
 *
 * @author Virginia Cangelosi
 * @author Mate Stodulka
 */
public class Wifi {
    private String ssid;
    private long bssid;
    private int level;
    private long frequency;

    private boolean rttFlag; // WiFi RTT flag
    private String uuid; // UUID [cite: 16]

    /**
     * Empty public default constructor of the Wifi object.
     */
    public Wifi(){}

    /**
     * Getters for each property
     */
    public String getSsid() { return ssid; }
    public long getBssid() { return bssid; }
    public int getLevel() { return level; }
    public long getFrequency() { return frequency; }

    public boolean getRttFlag() { return rttFlag; }
    public String getUuid() { return uuid; }

    /**
     * Setters for each property
     */
    public void setSsid(String ssid) { this.ssid = ssid; }
    public void setBssid(long bssid) { this.bssid = bssid; }
    public void setLevel(int level) { this.level = level; }
    public void setFrequency(long frequency) { this.frequency = frequency; }

    public void setRttFlag(boolean rttFlag) { this.rttFlag = rttFlag; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    /**
     * Generates a string containing mac address and rssi of Wifi.
     *
     * Concatenates mac address and rssi to display in the
     * {@link MeasurementsFragment} fragment
     */
    @Override
    public String toString() {
        return  "bssid: " + bssid +
                ", level: " + level +
                ", RTT: " + (rttFlag ? "Yes" : "No");
    }
}