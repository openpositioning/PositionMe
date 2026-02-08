package com.openpositioning.PositionMe.sensors;

import com.openpositioning.PositionMe.presentation.fragment.MeasurementsFragment;

/**
 * The Wifi object holds the Wifi parameters listed below.
 *
 * It contains the ssid (the identifier of the wifi), bssid (the mac address of the wifi), level
 * (the strength of the wifi in dB) and frequency (the frequency of the wifi network (2.4GHz or
 * 5GHz). For most objects only the bssid and the level are set.
 *
 * @author Virginia Cangelosi
 * @author Mate Stodulka
 */
public class Wifi {
    private String ssid;
    private long bssid;
    private int level;
    private long frequency;
    // [Objective b] New fields
    private String uuid;
    private boolean isRtt;

    /**
     * Empty public default constructor of the Wifi object.
     */
    public Wifi(){}
    /**
     * [Objective b] Full constructor including new fields.
     */
    public Wifi(long bssid, int level, String ssid, long frequency, String uuid, boolean isRtt) {
        this.bssid = bssid;
        this.level = level;
        this.ssid = ssid;
        this.frequency = frequency;
        this.uuid = uuid;
        this.isRtt = isRtt;
    }
    /**
     * Getters for each property
     */
    public String getSsid() { return ssid; }
    public long getBssid() { return bssid; }
    public int getLevel() { return level; }
    public long getFrequency() { return frequency; }
    // [Objective b] New Getters
    public String getUuid() { return uuid; }
    public boolean isRtt() { return isRtt; }

    /**
     * Setters for each property
     */
    public void setSsid(String ssid) { this.ssid = ssid; }
    public void setBssid(long bssid) { this.bssid = bssid; }
    public void setLevel(int level) { this.level = level; }
    public void setFrequency(long frequency) { this.frequency = frequency; }
    // [Objective b] New Setters
    public void setUuid(String uuid) { this.uuid = uuid; }
    public void setRtt(boolean isRtt) { this.isRtt = isRtt; }

    /**
     * Generates a string containing mac address and rssi of Wifi.
     *
     * Concatenates mac address and rssi to display in the
     * {@link MeasurementsFragment} fragment
     */
    @Override
    public String toString() {
        return  "bssid: " + bssid +", level: " + level;
    }
}
