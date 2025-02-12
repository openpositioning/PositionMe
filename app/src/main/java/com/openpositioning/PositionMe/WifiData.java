package com.openpositioning.PositionMe;

public class WifiData {
    private String ssid;
    private String bssid;
    private String signalStrength;

    public WifiData(String ssid, String bssid, String signalStrength) {
        this.ssid = ssid;
        this.bssid = bssid;
        this.signalStrength = signalStrength;
    }

    public String getSsid() {
        return ssid;
    }

    public String getBssid() {
        return bssid;
    }

    public String getSignalStrength() {
        return signalStrength;
    }
}
