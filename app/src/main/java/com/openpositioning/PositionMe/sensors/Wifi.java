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

    // Add RTT support flag for IEEE 802.11mc capability
    private boolean rttFlag;

    // Add unique identifier for scan traceability
    private String uuid;
    /**
     * Empty public default constructor of the Wifi object.
     */
    public Wifi(){}

    /**
     * Getters for each property
     */
    public String getSsid() { return ssid; }
    public long  getBssid() { return bssid; }
    public int getLevel() { return level; }
    public long getFrequency() { return frequency; }

    //  Getter for RTT flag
    public boolean getRttFlag() { return rttFlag; }
    
    // Getter for unique ID
    public String getUuid() { return uuid; }


    /**
     * Setters for each property
     */
    public void setSsid(String ssid) { this.ssid = ssid; }
    public void setBssid(long bssid) { this.bssid = bssid; }
    public void setLevel(int level) { this.level = level; }
    public void setFrequency(long frequency) { this.frequency = frequency; }

    // Setter for RTT flag
    public void setRttFlag(boolean rttFlag) { this.rttFlag = rttFlag; }

    // Setter for unique ID
    public void setUuid(String uuid) { this.uuid = uuid; }
    /**
     * Generates a string containing mac address and rssi of Wifi.
     *
     * Concatenates mac address and rssi to display in the
     * {@link MeasurementsFragment} fragment
     */
    @Override
    public String toString() {
        String macStr = String.format("%012X", bssid);
        StringBuilder formattedMac = new StringBuilder();
        for (int i = 0; i < macStr.length(); i += 2) {
            if (i > 0) formattedMac.append(":");
            formattedMac.append(macStr.substring(i, i + 2));
        }

        return "MAC: " + formattedMac.toString() + ", Level: " + level + "dBm, RTT: " + rttFlag;
    }
}
