package com.openpositioning.PositionMe.sensors;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;

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
 * @see WiFiPositioning the API client for WiFi-based positioning
 */
public class WifiPositionManager implements Observer {

    private static final String WIFI_FINGERPRINT = "wf";
    private static final float WIFI_FUSION_MAX_DISTANCE_METERS = 10.0f;
    private static final double WIFI_FUSION_SIGMA_METERS = 12.0;

    private final WiFiPositioning wiFiPositioning;
    private final TrajectoryRecorder recorder;
    private List<Wifi> wifiList;

    /**
     * Creates a new WifiPositionManager.
     *
     * @param wiFiPositioning WiFi positioning API client
     * @param recorder trajectory recorder for writing WiFi fingerprints
     */
    public WifiPositionManager(WiFiPositioning wiFiPositioning, TrajectoryRecorder recorder) {
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
        Log.d("WifiProbe", "Received WiFi scan results.");
        this.wifiList = Stream.of(wifiList)
                .map(o -> (Wifi) o)
                .filter(w -> w.getLevel() < -40)
                .collect(Collectors.toList());
        recorder.addWifiFingerprint(this.wifiList);
        // Use the callback-based request path so the fusion layer can react to the result.
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
        } catch (JSONException e) {
            Log.e("WifiProbe", "Failed to build WiFi positioning JSON: " + e);
            Log.e("jsonErrors", "Error creating json object" + e);
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
            Log.d("WifiProbe", "Sending WiFi positioning request.");
            this.wiFiPositioning.request(wifiFingerPrint, new WiFiPositioning.VolleyCallback() {
                @Override
                public void onSuccess(LatLng wifiLocation, int floor) {
                    Log.d("WifiSuccess", "WiFi location received, updating particle filter.");

                    com.openpositioning.PositionMe.fusion.ParticleFilter pf =
                            SensorFusion.getInstance().getParticleFilter();

                    if (pf != null && wifiLocation != null) {
                        // Use the start location as the origin of the local coordinate frame.
                        float[] startCoords = SensorFusion.getInstance().getGNSSLatitude(true);
                        double lat0 = startCoords[0];
                        double lng0 = startCoords[1];

                        // Convert the WiFi location from WGS84 to local x/y coordinates in meters.
                        double radius = 6378137.0;
                        double lat = wifiLocation.latitude;
                        double lng = wifiLocation.longitude;
                        double dLat = Math.toRadians(lat - lat0);
                        double dLng = Math.toRadians(lng - lng0);
                        float x = (float) (radius * dLng * Math.cos(Math.toRadians(lat0)));
                        float y = (float) (radius * dLat);

                        // Build a measurement with the tuned WiFi uncertainty.
                        com.openpositioning.PositionMe.fusion.Measurement measurement =
                                new com.openpositioning.PositionMe.fusion.Measurement(
                                        x, y, WIFI_FUSION_SIGMA_METERS);

                        // Reuse the active map state so WiFi fusion respects map constraints.
                        LatLng startLocation = new LatLng(startCoords[0], startCoords[1]);
                        List<FloorplanApiClient.MapShapeFeature> walls =
                                SensorFusion.getInstance().getCurrentWalls();

                        SensorFusion.getInstance().setLatestWifiLocation(wifiLocation);
                        com.openpositioning.PositionMe.fusion.Position estimatedPosition =
                                pf.getEstimatedPosition(walls, startLocation);
                        double wifiDistance = Math.hypot(
                                estimatedPosition.x - x, estimatedPosition.y - y);

                        if (wifiDistance <= WIFI_FUSION_MAX_DISTANCE_METERS) {
                            pf.updateWeights(measurement, walls, startLocation);
                            pf.resample();
                        } else {
                            Log.d("WifiSuccess",
                                    "Skipping WiFi fusion, distance too large: " + wifiDistance);
                        }

                        com.openpositioning.PositionMe.fusion.Position pos =
                                pf.getEstimatedPosition();
                        Log.d("ParticleFilter",
                                "Fused WiFi position X: " + pos.x + " Y: " + pos.y);
                    }
                }

                @Override
                public void onError(String message) {
                    Log.e("WifiProbe", "WiFi positioning request failed: " + message);
                }
            });
        } catch (JSONException e) {
            Log.e("jsonErrors", "Error creating json object" + e);
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
