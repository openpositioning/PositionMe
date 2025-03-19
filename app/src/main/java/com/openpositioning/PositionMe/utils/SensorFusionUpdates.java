package com.openpositioning.PositionMe.utils;

import com.google.android.gms.maps.model.LatLng;
/**
 * An interface defining methods for receiving updates from a sensor fusion module.
 *
 * Implementing classes can receive updates related to various sensor inputs such as PDR (Pedestrian Dead Reckoning),
 * GNSS (Global Navigation Satellite System), orientation, fused data, and Wi-Fi signals.
 *
 * Implementations of this interface must provide concrete implementations for each update type.
 *
 * @see update_type
 */
public interface SensorFusionUpdates {

     enum update_type {
        PDR_UPDATE,
        GNSS_UPDATE,
        ORIENTATION_UPDATE,
        FUSED_UPDATE,
        WIFI_UPDATE
    }

    void onPDRUpdate();

    void onOrientationUpdate();

    void onGNSSUpdate();

    void onFusedUpdate(LatLng coordinate);

    void onWifiUpdate(LatLng wifi);
}

