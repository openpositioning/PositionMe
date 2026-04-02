package com.openpositioning.PositionMe.sensors;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.openpositioning.PositionMe.positioning.FusionManager;

import com.openpositioning.PositionMe.mapmatching.MapMatchingEngine;

/**
 * Manages WiFi scan result processing and WiFi-based com.openpositioning.PositionMe.positioning requests.
 *
 * <p>Implements {@link Observer} to receive updates from {@link WifiDataProcessor},
 * replacing the role previously held by {@link SensorFusion}.</p>
 *
 * @see WifiDataProcessor the observable that triggers WiFi scan updates
 * @see WiFiPositioning   the API client for WiFi-based com.openpositioning.PositionMe.positioning
 */
public class WifiPositionManager implements Observer {

    private static final String WIFI_FINGERPRINT = "wf";

    private final WiFiPositioning wiFiPositioning;
    private final TrajectoryRecorder recorder;
    private List<Wifi> wifiList;

    /**
     * Creates a new WifiPositionManager.
     *
     * @param wiFiPositioning WiFi com.openpositioning.PositionMe.positioning API client
     * @param recorder        trajectory recorder for writing WiFi fingerprints
     */
    public WifiPositionManager(WiFiPositioning wiFiPositioning,
                               TrajectoryRecorder recorder) {
        this.wiFiPositioning = wiFiPositioning;
        this.recorder = recorder;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Receives updates from {@link WifiDataProcessor}. Converts the raw object array
     * to a typed list, delegates fingerprint recording to {@link TrajectoryRecorder},
     * and triggers a WiFi com.openpositioning.PositionMe.positioning request.</p>
     */
    @Override
    public void update(Object[] wifiList) {
        this.wifiList = Stream.of(wifiList).map(o -> (Wifi) o).collect(Collectors.toList());
        recorder.addWifiFingerprint(this.wifiList);
        createWifiPositionRequestCallback();
    }

    /**
     * Creates a request to obtain a WiFi location for the obtained WiFi fingerprint.
     */
    private void createWifiPositioningRequest() {
        try {
            JSONObject wifiAccessPoints = new JSONObject();
            for (Wifi data : this.wifiList) {
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
            this.wiFiPositioning.request(wifiFingerPrint);

            // Feed WiFi position to map matching engine
            LatLng wifiPos = this.wiFiPositioning.getWifiLocation();
            int wifiFloor = this.wiFiPositioning.getFloor();
            if (wifiPos != null) {
                MapMatchingEngine engine = SensorFusion.getInstance().getMapMatchingEngine();
                if (engine != null && engine.isActive()) {
                    engine.updateWifi(wifiPos.latitude, wifiPos.longitude, wifiFloor, 10.0f);
                }
            }
        } catch (JSONException e) {
            Log.e("jsonErrors", "Error creating json object" + e.toString());
        }
    }

    /**
     * Creates a WiFi com.openpositioning.PositionMe.positioning request using the Volley callback pattern.
     */
    private void createWifiPositionRequestCallback() {
        if (this.wifiList == null || this.wifiList.isEmpty()) {
            return;
        }
        try {
            JSONObject wifiAccessPoints = new JSONObject();
            for (Wifi data : this.wifiList) {
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
            this.wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng wifiLocation, int floor) {
                    if (wifiLocation != null) {
                        FusionManager.getInstance().onWifi(
                                wifiLocation.latitude,
                                wifiLocation.longitude
                        );
                    }
                }

                @Override
                public void onError(String message) {
                    Log.e("WifiPositionManager", "WiFi com.openpositioning.PositionMe.positioning failed: " + message);
                }
            });
        } catch (JSONException e) {
            Log.e("jsonErrors", "Error creating json object" + e.toString());
        }
    }

    /**
     * Returns the user position obtained using WiFi com.openpositioning.PositionMe.positioning.
     *
     * @return {@link LatLng} corresponding to the user's position
     */
    public LatLng getLatLngWifiPositioning() {
        return this.wiFiPositioning.getWifiLocation();
    }

    /**
     * Returns the current floor the user is on, obtained using WiFi com.openpositioning.PositionMe.positioning.
     *
     * @return current floor number
     */
    public int getWifiFloor() {
        return this.wiFiPositioning.getFloor();
    }

    /**
     * Returns the most recent list of WiFi scan results.
     *
     * @return list of {@link Wifi} objects
     */
    public List<Wifi> getWifiList() {
        return this.wifiList;
    }
}
