package com.openpositioning.PositionMe.fusion;

import com.google.android.gms.maps.model.LatLng;

/**
 * Interface for position fusion algorithms.
 * Implementing classes should provide algorithms for fusing PDR and GNSS
 * positioning sources into a single estimate.
 *
 * Note: WiFi fusion will be added in a future update.
 */
public interface IPositionFusionAlgorithm {

    /**
     * Processes an update from the PDR system.
     *
     * @param eastMeters The east position in meters relative to the reference point
     * @param northMeters The north position in meters relative to the reference point
     * @param altitude The altitude in meters
     */
    void processPdrUpdate(float eastMeters, float northMeters, float altitude);

    /**
     * Processes an update from the GNSS system.
     *
     * @param position The GNSS position (latitude, longitude)
     * @param altitude The altitude in meters
     */
    void processGnssUpdate(LatLng position, double altitude);

    /**
     * Processes an update from the GNSS system.
     *
     * @param position The WiFi position in LatLng format (latitude, longitude)
     * @param floor The floor index
     */
    void processWifiUpdate(LatLng position, int floor);

    /**
     * Gets the current fused position estimate.
     *
     * @return The fused position (latitude, longitude)
     */
    LatLng getFusedPosition();

    /**
     * Resets the fusion algorithm to its initial state.
     */
    void reset();

    /**
     * Future implementation: Processes an update from the WiFi positioning system.
     *
     * @param position The WiFi position (latitude, longitude)
     */
    // void processWifiUpdate(LatLng position); - Will be implemented in future updates
}