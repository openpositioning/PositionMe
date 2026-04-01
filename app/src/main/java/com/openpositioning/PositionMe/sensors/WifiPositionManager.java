package com.openpositioning.PositionMe.sensors;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages WiFi scan result processing and WiFi-based positioning requests.
 *
 * <p>Implements {@link Observer} to receive updates from {@link WifiDataProcessor},
 * replacing the role previously held by {@link SensorFusion}.</p>
 *
 * <p>Filters out mobile hotspot APs (randomized MAC addresses) before sending
 * fingerprints to the positioning API. If the API returns 404 (no match),
 * retries once with the strongest AP removed, as the positioning engine
 * uses the top 5 APs for initial search.</p>
 *
 * @see WifiDataProcessor the observable that triggers WiFi scan updates
 * @see WiFiPositioning   the API client for WiFi-based positioning
 */
public class WifiPositionManager implements Observer {

    private static final String TAG = "WIFI_POS_DEBUG";
    private static final String WIFI_FINGERPRINT = "wf";

    private final WiFiPositioning wiFiPositioning;
    private final TrajectoryRecorder recorder;
    private final PositionFusion positionFusion;
    private List<Wifi> wifiList;

    /**
     * Creates a new WifiPositionManager.
     *
     * @param wiFiPositioning WiFi positioning API client
     * @param recorder        trajectory recorder for writing WiFi fingerprints
     * @param positionFusion  position fusion engine for multi-source correction
     */
    public WifiPositionManager(WiFiPositioning wiFiPositioning,
                               TrajectoryRecorder recorder,
                               PositionFusion positionFusion) {
        this.wiFiPositioning = wiFiPositioning;
        this.recorder = recorder;
        this.positionFusion = positionFusion;
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
        Log.e(TAG, "=== WiFi Scan Update ===");
        Log.e(TAG, "Total APs scanned: " + this.wifiList.size());
        for (int i = 0; i < Math.min(this.wifiList.size(), 5); i++) {
            Wifi w = this.wifiList.get(i);
            Log.e(TAG, "  AP[" + i + "] SSID=" + w.getSsid()
                    + " BSSID=" + w.getBssidString()
                    + " RSSI=" + w.getLevel() + "dBm"
                    + " freq=" + w.getFrequency() + "MHz"
                    + " RTT=" + w.isRttEnabled());
        }
        if (this.wifiList.size() > 5) {
            Log.e(TAG, "  ... and " + (this.wifiList.size() - 5) + " more APs");
        }
        recorder.addWifiFingerprint(this.wifiList);
        createWifiPositioningRequest();
    }

    /**
     * Checks if a MAC address is locally administered (randomized).
     * Mobile hotspots and privacy-enabled devices use randomized MACs where
     * the second least significant bit of the first octet is set to 1.
     * For example: e6:c2:d7:bb:48:ab -> first octet 0xe6 = 11100110, bit 1 = 1 -> randomized.
     *
     * @param bssid the MAC address string (e.g. "e6:c2:d7:bb:48:ab")
     * @return true if the MAC is locally administered (likely a mobile hotspot)
     */
    private boolean isRandomizedMac(String bssid) {
        if (bssid == null || bssid.length() < 2) return false;
        try {
            int firstOctet = Integer.parseInt(bssid.substring(0, 2), 16);
            // Bit 1 of first octet: 1 = locally administered (randomized)
            return (firstOctet & 0x02) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Builds a fingerprint JSONObject from the WiFi list, filtering out
     * mobile hotspot APs that use randomized MAC addresses.
     *
     * @param skipStrongest if true, also removes the AP with the strongest signal
     *                      (used for 404 retry, as the API searches by top 5 APs)
     * @return the fingerprint JSON, or null if no valid APs remain
     */
    private JSONObject buildFingerprint(boolean skipStrongest) throws JSONException {
        // Filter out randomized MACs (mobile hotspots)
        List<Wifi> filtered = new ArrayList<>();
        int removedCount = 0;
        for (Wifi data : this.wifiList) {
            String bssid = data.getBssidString();
            if (isRandomizedMac(bssid)) {
                removedCount++;
                Log.e(TAG, "  Filtered out randomized MAC: " + bssid
                        + " RSSI=" + data.getLevel() + "dBm"
                        + " (likely mobile hotspot)");
            } else {
                filtered.add(data);
            }
        }
        if (removedCount > 0) {
            Log.e(TAG, "Filtered " + removedCount + " randomized MAC APs, "
                    + filtered.size() + " APs remaining");
        }

        // Optionally remove the strongest AP (for 404 retry)
        if (skipStrongest && !filtered.isEmpty()) {
            Wifi strongest = Collections.max(filtered,
                    Comparator.comparingInt(Wifi::getLevel));
            Log.e(TAG, "Removing strongest AP for retry: BSSID="
                    + strongest.getBssidString()
                    + " RSSI=" + strongest.getLevel() + "dBm");
            filtered.remove(strongest);
        }

        if (filtered.isEmpty()) {
            Log.e(TAG, "No valid APs remaining after filtering");
            return null;
        }

        JSONObject wifiAccessPoints = new JSONObject();
        for (Wifi data : filtered) {
            wifiAccessPoints.put(String.valueOf(data.getBssid()), data.getLevel());
        }
        JSONObject wifiFingerPrint = new JSONObject();
        wifiFingerPrint.put(WIFI_FINGERPRINT, wifiAccessPoints);
        return wifiFingerPrint;
    }

    /**
     * Creates a WiFi positioning request with mobile hotspot filtering.
     * If the API returns 404, retries once with the strongest AP removed.
     */
    private void createWifiPositioningRequest() {
        try {
            JSONObject fingerprint = buildFingerprint(false);
            if (fingerprint == null) return;

            Log.e(TAG, "Sending WiFi positioning request with filtered APs");
            this.wiFiPositioning.request(fingerprint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng location, int floor) {
                    Log.e(TAG, "WiFi positioning SUCCESS: " + location + " floor=" + floor);
                    // Feed WiFi observation into position fusion
                    if (positionFusion != null && positionFusion.isInitialized()) {
                        positionFusion.updateWithWifi(location.latitude, location.longitude);
                    }
                }

                @Override
                public void onError(String message) {
                    // If 404 (no match), retry once with strongest AP removed
                    if (message != null && message.contains("404")) {
                        Log.e(TAG, "Got 404, retrying with strongest AP removed...");
                        try {
                            JSONObject retryFp = buildFingerprint(true);
                            if (retryFp != null) {
                                wiFiPositioning.request(retryFp);
                            }
                        } catch (JSONException e) {
                            Log.e(TAG, "Error building retry fingerprint: " + e);
                        }
                    }
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "Error creating WiFi fingerprint JSON: " + e.toString());
        }
    }

    /**
     * Creates a WiFi positioning request using the Volley callback pattern.
     */
    private void createWifiPositionRequestCallback() {
        try {
            JSONObject fingerprint = buildFingerprint(false);
            if (fingerprint == null) return;

            this.wiFiPositioning.request(fingerprint, new WiFiPositioning.VolleyCallback() {
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
            Log.e(TAG, "Error creating WiFi fingerprint JSON: " + e.toString());
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
