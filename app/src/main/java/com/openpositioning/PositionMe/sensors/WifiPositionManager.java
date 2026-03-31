package com.openpositioning.PositionMe.sensors;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;

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

    public interface WifiFixListener {
        void onWifiFix(LatLng wifiLocation, int floor);
    }

    private static final String WIFI_FINGERPRINT = "wf";

    private final WiFiPositioning wiFiPositioning;
    private final TrajectoryRecorder recorder;
    private List<Wifi> wifiList;
    private WifiFixListener wifiFixListener;

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
     */
    private void createWifiPositioningRequest() {
        try {
            JSONObject wifiAccessPoints = new JSONObject();
            for (Wifi data : this.wifiList) {
                String bssidKey = getBssidKey(data);
                if (bssidKey == null) {
                    continue;
                }
                wifiAccessPoints.put(bssidKey, data.getLevel());
            }
            if (wifiAccessPoints.length() == 0) {
                Log.w("WifiPositionManager", "Skipping WiFi positioning request: no valid BSSID keys");
                return;
            }
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
            this.wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng wifiLocation, int floor) {
                    if (wifiFixListener != null && wifiLocation != null) {
                        wifiFixListener.onWifiFix(wifiLocation, floor);
                    }
                }

                @Override
                public void onError(String message) {
                    Log.e("WifiPositionManager", "WiFi positioning request failed: " + message);
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
                String bssidKey = getBssidKey(data);
                if (bssidKey == null) {
                    continue;
                }
                wifiAccessPoints.put(bssidKey, data.getLevel());
            }
            if (wifiAccessPoints.length() == 0) {
                Log.w("WifiPositionManager", "Skipping WiFi callback request: no valid BSSID keys");
                return;
            }
            JSONObject wifiFingerPrint = new JSONObject();
            wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
            this.wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng wifiLocation, int floor) {
                    // Handle the success response
                }

                @Override
                public void onError(String message) {
                    // Handle the error response
                }
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

    /**
     * Registers a listener receiving WiFi absolute fixes for downstream fusion.
     */
    public void setWifiFixListener(WifiFixListener wifiFixListener) {
        this.wifiFixListener = wifiFixListener;
    }

    private String getBssidKey(Wifi wifi) {
        String bssidString = wifi.getBssidString();
        if (bssidString != null && !bssidString.trim().isEmpty()) {
            String normalizedHex = normalizeMacToHex(bssidString.trim());
            if (normalizedHex != null) {
                long macValue = Long.parseUnsignedLong(normalizedHex, 16);
                return Long.toUnsignedString(macValue);
            }
        }

        // Fallback: convert packed long (lower 48 bits) to unsigned decimal string.
        long mac = wifi.getBssid() & 0x0000FFFFFFFFFFFFL;
        return Long.toUnsignedString(mac);
    }

    private String normalizeMacToHex(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace(":", "").replace("-", "").toUpperCase();
        if (!isValidHexMac(normalized)) {
            return null;
        }
        return normalized;
    }

    private boolean isValidHexMac(String value) {
        if (value.length() != 12) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean upperHex = c >= 'A' && c <= 'F';
            if (!digit && !upperHex) {
                return false;
            }
        }
        return true;
    }

}
