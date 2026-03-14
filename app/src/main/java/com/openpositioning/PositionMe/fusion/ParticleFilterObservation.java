package com.openpositioning.PositionMe.fusion;

/**
 * Container for absolute observations used to update the particle filter.
 *
 * <p>For the first Android integration phase, this includes:
 * Wi-Fi local position, Wi-Fi floor, and GNSS local position.</p>
 */
public class ParticleFilterObservation {

    private final Double wifiX;
    private final Double wifiY;
    private final Integer wifiFloor;
    private final Double gnssX;
    private final Double gnssY;

    /**
     * Creates a new observation container.
     *
     * @param wifiX Wi-Fi x coordinate in meters, or null if unavailable
     * @param wifiY Wi-Fi y coordinate in meters, or null if unavailable
     * @param wifiFloor Wi-Fi floor estimate, or null if unavailable
     * @param gnssX GNSS x coordinate in meters, or null if unavailable
     * @param gnssY GNSS y coordinate in meters, or null if unavailable
     */
    public ParticleFilterObservation(Double wifiX,
                                     Double wifiY,
                                     Integer wifiFloor,
                                     Double gnssX,
                                     Double gnssY) {
        this.wifiX = wifiX;
        this.wifiY = wifiY;
        this.wifiFloor = wifiFloor;
        this.gnssX = gnssX;
        this.gnssY = gnssY;
    }

    /**
     * Returns the Wi-Fi local x coordinate.
     *
     * @return Wi-Fi x coordinate, or null if unavailable
     */
    public Double getWifiX() {
        return wifiX;
    }

    /**
     * Returns the Wi-Fi local y coordinate.
     *
     * @return Wi-Fi y coordinate, or null if unavailable
     */
    public Double getWifiY() {
        return wifiY;
    }

    /**
     * Returns the Wi-Fi floor estimate.
     *
     * @return Wi-Fi floor, or null if unavailable
     */
    public Integer getWifiFloor() {
        return wifiFloor;
    }

    /**
     * Returns the GNSS local x coordinate.
     *
     * @return GNSS x coordinate, or null if unavailable
     */
    public Double getGnssX() {
        return gnssX;
    }

    /**
     * Returns the GNSS local y coordinate.
     *
     * @return GNSS y coordinate, or null if unavailable
     */
    public Double getGnssY() {
        return gnssY;
    }
}