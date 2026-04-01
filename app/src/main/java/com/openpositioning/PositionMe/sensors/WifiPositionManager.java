package com.openpositioning.PositionMe.sensors;

import static com.openpositioning.PositionMe.BuildConstants.DEBUG;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.fusion.FusionManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages WiFi scan result processing and WiFi-based positioning requests.
 *
 * <p>Implements {@link Observer} to receive updates from {@link WifiDataProcessor},
 * replacing the role previously held by {@link SensorFusion}.</p>
 *
 * @see WifiDataProcessor the observable that triggers WiFi scan updates
 * @see WiFiPositioning   the API client for WiFi-based positioning
 */
public class WifiPositionManager implements Observer {

    private static final String WIFI_FINGERPRINT = "wf";

    private final WiFiPositioning wiFiPositioning;
    private final TrajectoryRecorder recorder;
    private FusionManager fusionManager;
    private List<Wifi> wifiList;

    /** One-shot or temporary callback for WiFi floor updates (e.g. autofloor re-seed). */
    private volatile WifiFloorCallback wifiFloorCallback;

    /** Callback interface for receiving WiFi floor responses. */
    public interface WifiFloorCallback {
        /**
         * Called when a WiFi floor response arrives.
         *
         * @param floor resolved floor number
         */
        void onWifiFloor(int floor);
    }

    /**
     * Creates a new WifiPositionManager.
     *
     * @param wiFiPositioning WiFi positioning API client
     * @param recorder        trajectory recorder for writing WiFi fingerprints
     */
    public WifiPositionManager(WiFiPositioning wiFiPositioning,
                               TrajectoryRecorder recorder) {
        this.wiFiPositioning = wiFiPositioning;
        this.recorder = recorder;
    }

    /** Injects the fusion manager so WiFi fixes feed the particle filter. */
    public void setFusionManager(FusionManager fusionManager) {
        this.fusionManager = fusionManager;
    }

    /**
     * Sets a callback to be notified when a WiFi floor response arrives.
     * Used by autofloor toggle to re-seed initial floor from WiFi.
     *
     * @param callback callback to invoke, or null to clear
     */
    public void setWifiFloorCallback(WifiFloorCallback callback) {
        this.wifiFloorCallback = callback;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Receives updates from {@link WifiDataProcessor}. Converts the raw object array
     * to a typed list, delegates fingerprint recording to {@link TrajectoryRecorder},
     * and triggers a WiFi positioning request.</p>
     */
    @Override
    public void update(Object[] wifiList) {
        this.wifiList = Stream.of(wifiList).map(o -> (Wifi) o).collect(Collectors.toList());
        recorder.addWifiFingerprint(this.wifiList);
        createWifiPositioningRequest();
    }

    /**
     * Creates a request to obtain a WiFi location for the obtained WiFi fingerprint.
     * Uses the callback variant so the result can be forwarded to the fusion manager.
     */
    private void createWifiPositioningRequest() {
        try {
            JSONObject wifiAccessPoints = new JSONObject();
            for (Wifi data : this.wifiList) {
                // API accepts integer BSSID format (returns 404 if no match).
                // Colon MAC format is rejected with 400 by the server validation.
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
            if (DEBUG) Log.d("WifiPositionManager", "WiFi request: " + wifiAccessPoints.length() + " APs");
            this.wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng wifiLocation, int floor) {
                    if (fusionManager != null && wifiLocation != null) {
                        fusionManager.onWifiPosition(
                                wifiLocation.latitude, wifiLocation.longitude, floor);
                    }
                    // Notify floor callback (used by autofloor re-seed)
                    WifiFloorCallback cb = wifiFloorCallback;
                    if (cb != null && floor >= 0) {
                        cb.onWifiFloor(floor);
                    }
                }

                @Override
                public void onError(String message) {
                    if (DEBUG) Log.w("WifiPositionManager", "WiFi positioning failed: " + message);
                }
            });
        } catch (JSONException e) {
            Log.e("jsonErrors", "Error creating json object" + e.toString());
        }
    }

    /**
     * Creates a WiFi positioning request using the Volley callback pattern.
     */
    private void createWifiPositionRequestCallback() {
        try {
            JSONObject wifiAccessPoints = new JSONObject();
            for (Wifi data : this.wifiList) {
                wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
            }
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
            this.wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng wifiLocation, int floor) { }

                @Override
                public void onError(String message) { }
            });
        } catch (JSONException e) {
            Log.e("jsonErrors", "Error creating json object" + e.toString());
        }
    }

    /**
     * Returns the user position obtained using WiFi positioning.
     *
     * @return {@link LatLng} corresponding to the user's position
     */
    public LatLng getLatLngWifiPositioning() {
        return this.wiFiPositioning.getWifiLocation();
    }

    /**
     * Returns the current floor the user is on, obtained using WiFi positioning.
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
