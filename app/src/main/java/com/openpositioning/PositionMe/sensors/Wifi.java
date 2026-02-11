package com.openpositioning.PositionMe.sensors;

import com.openpositioning.PositionMe.presentation.fragment.MeasurementsFragment;

/**
 * The Wifi object holds the Wifi parameters listed below.
 *
 * It contains the ssid (the identifier of the wifi), bssid (the mac address of the wifi), level
 * (the strength of the wifi in dB) and frequency (the frequency of the wifi network).
 *
 * For assignment v2.0 we also keep:
 * - macString: original BSSID string ("aa:bb:..")
 * - rttEnabled: whether the AP supports 802.11mc responder (RTT-capable)
 */
public class Wifi {
    private String ssid;
    private long bssid;
    private String macString;
    private int level;
    private long frequency;
    private boolean rttEnabled;

    public Wifi(){}

    public String getSsid() { return ssid; }
    public long getBssid() { return bssid; }
    public String getMacString() { return macString; }
    public int getLevel() { return level; }
    public long getFrequency() { return frequency; }
    public boolean isRttEnabled() { return rttEnabled; }

    public void setSsid(String ssid) { this.ssid = ssid; }
    public void setBssid(long bssid) { this.bssid = bssid; }
    public void setMacString(String macString) { this.macString = macString; }
    public void setLevel(int level) { this.level = level; }
    public void setFrequency(long frequency) { this.frequency = frequency; }
    public void setRttEnabled(boolean rttEnabled) { this.rttEnabled = rttEnabled; }

    @Override
    public String toString() {
        return  "mac: " + macString + ", level: " + level;
    }
}
