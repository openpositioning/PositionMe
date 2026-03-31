package com.openpositioning.PositionMe.utils;

public class WifiApObservation {
    public final String bssid;   // MAC
    public final int rssi;       // dBm
    public final String ssid;    // optional

    public WifiApObservation(String bssid, int rssi, String ssid) {
        this.bssid = bssid;
        this.rssi = rssi;
        this.ssid = ssid;
    }
}

