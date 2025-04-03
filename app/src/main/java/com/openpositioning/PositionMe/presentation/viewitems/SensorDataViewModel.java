package com.openpositioning.PositionMe.presentation.viewitems;


import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.google.android.gms.maps.model.LatLng;

/**
 * ViewModel for sharing sensor data availability status across UI components
 * Used to monitor Wifi and GNSS for No coverage Detection
 *
 * The ViewModel maintains observable flags for:
 * - WiFi positioning data availability
 * - GNSS positioning data availability
 * 
 * @author Stone Anderson
 */
public class SensorDataViewModel extends ViewModel {
    private final MutableLiveData<Boolean> hasWifiData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> hasGnssData = new MutableLiveData<>();

    /**
     * Gets the LiveData object tracking WiFi positioning data availability.
     * @return LiveData<Boolean> containing true if WiFi positioning data is available,
     *         false otherwise
     * @author Stone Anderson
     */
    public LiveData<Boolean> getHasWifiData() {
        return hasWifiData;
    }

    /**
     * Gets the LiveData object tracking GNSS data availability.
     * @return LiveData<Boolean> containing true if GNSS positioning data is available,
     *         false otherwise
     * @author  Stone Anderson
     */
    public LiveData<Boolean> getHasGnssData() {
        return hasGnssData;
    }

    /**
     * Updates the WiFi positioning data availability state.
     * @param hasWifi true if WiFi positioning data is available, false otherwise
     * @author  Stone Anderson
     */
    public void setHasWifiData(boolean hasWifi) {
        hasWifiData.setValue(hasWifi);
    }


    /**
     * Updates the GNSS (GPS) data availability state.
     * @param hasGnss true if GNSS positioning data is available, false otherwise
     * @author   Stone Anderson
     */
    public void setHasGnssData(boolean hasGnss) {
        hasGnssData.setValue(hasGnss);
    }
}

