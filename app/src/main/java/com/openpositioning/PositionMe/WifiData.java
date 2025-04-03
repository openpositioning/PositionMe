package com.openpositioning.PositionMe;

/**
 * WifiData: A simple data model representing a WiFi access point's information.
 *
 * Used primarily for displaying scan results in a RecyclerView or tracking
 * WiFi fingerprinting data.
 */
public class WifiData {

    // The SSID (network name) of the WiFi access point
    private String ssid;

    // The BSSID (unique MAC address) of the WiFi access point
    private String bssid;

    // The signal strength of the WiFi in dBm (as a formatted string)
    private String signalStrength;

    /**
     * Constructor to create a WifiData object.
     *
     * @param ssid            The SSID of the access point.
     * @param bssid           The BSSID (MAC address).
     * @param signalStrength  The signal level, usually in dBm (e.g., "-65 dB").
     */
    public WifiData(String ssid, String bssid, String signalStrength) {
        this.ssid = ssid;
        this.bssid = bssid;
        this.signalStrength = signalStrength;
    }

    /**
     * Returns the SSID (name) of the WiFi access point.
     *
     * @return the SSID
     */
    public String getSsid() {
        return ssid;
    }

    /**
     * Returns the BSSID (MAC address) of the access point.
     *
     * @return the BSSID
     */
    public String getBssid() {
        return bssid;
    }

    /**
     * Returns the signal strength of the WiFi in dBm.
     *
     * @return the signal strength string (e.g., "-55 dB")
     */
    public String getSignalStrength() {
        return signalStrength;
    }
}

