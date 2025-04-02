package com.openpositioning.PositionMe.fusion;

import android.content.Context;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.PdrProcessing;

/**
 * Interface for position fusion algorithms.
 * Implementing classes should provide algorithms for fusing PDR and GNSS
 * positioning sources into a single estimate.
 *
 * Note: WiFi fusion will be added in a future update.
 */
public abstract class IPositionFusionAlgorithm {

    /**
     * Processes an update from the PDR system.
     *
     * @param eastMeters The east position in meters relative to the reference point
     * @param northMeters The north position in meters relative to the reference point
     * @param altitude The altitude in meters
     */
    public abstract void processPdrUpdate(float eastMeters, float northMeters, float altitude);

    /**
     * Processes an update from the GNSS system.
     *
     * @param position The GNSS position (latitude, longitude)
     * @param altitude The altitude in meters
     */
    public abstract void processGnssUpdate(LatLng position, double altitude);

    /**
     * Processes an update from the GNSS system.
     *
     * @param position The WiFi position in LatLng format (latitude, longitude)
     * @param floor The floor index
     */
    public abstract void processWifiUpdate(LatLng position, int floor);

    /**
     * Gets the current fused position estimate.
     *
     * @return The fused position (latitude, longitude)
     */
    public abstract LatLng getFusedPosition();

    /**
     * Resets the fusion algorithm to its initial state.
     */
    public abstract void reset();

    /**
     * Future implementation: Processes an update from the WiFi positioning system.
     *
     * @param position The WiFi position (latitude, longitude)
     */
    // void processWifiUpdate(LatLng position); - Will be implemented in future updates

    /**
     * Performs static positioning update every 1000ms
     *
     */
    public abstract void staticUpdate();

    /**
     *
     * @param context
     */
    public abstract void retrieveContext(Context context);

    /**
     *
     * @param elevationDirection
     */

    public abstract void setElevationStatus(PdrProcessing.ElevationDirection elevationDirection);
}