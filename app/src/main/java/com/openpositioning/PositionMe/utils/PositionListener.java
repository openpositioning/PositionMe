package com.openpositioning.PositionMe.utils;

import com.google.android.gms.maps.model.LatLng;

/**
 * Interface for listening to position updates from various sources.
 * Implementing classes can receive updates from PDR, GNSS, and fused positions.
 *
 * @author Michal Wiercigroch
 */
public interface PositionListener {

    /**
     * Enumeration of different types of position updates.
     */
    enum UpdateType {
        /** Update from Pedestrian Dead Reckoning */
        PDR_POSITION,

        /** Update from Global Navigation Satellite System */
        GNSS_POSITION,

        /** Update from the fusion algorithm (combined position) */
        FUSED_POSITION,

        /** Update for heading/orientation */
        ORIENTATION_UPDATE,

        WIFI_ERROR
    }

    /**
     * Called when a position is updated.
     *
     * @param updateType The type of position update
     * @param position The new position (may be null for some update types)
     */
    void onPositionUpdate(UpdateType updateType, LatLng position);
}