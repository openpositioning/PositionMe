package com.openpositioning.PositionMe.sensors;

import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;

/**
 * Fuses PDR relative motion with sparse WiFi absolute fixes in local ENU-style meters.
 *
 * <p>The state is {@code [x, y]} in meters from the selected start location. PDR supplies the
 * prediction delta and WiFi supplies the absolute measurement. This is a local linear Kalman
 * filter; the lat/lon conversion is handled at the measurement boundary.</p>
 */
public class PdrWifiFusionManager {

    private static final String TAG = "PdrWifiFusion";
    private static final double DEGREE_IN_M = 111111.0;
    private static final double MIN_PROCESS_STD_M = 0.05;

    private static final int DEFAULT_INITIAL_STD_M = 3;
    private static final int DEFAULT_WIFI_STD_M = 8;
    private static final int DEFAULT_PDR_PROCESS_PERCENT = 25;
    private static final int DEFAULT_WIFI_GATE_M = 35;

    private final Object lock = new Object();
    private final SharedPreferences settings;

    private double stateX;
    private double stateY;
    private double covarianceX;
    private double covarianceY;
    private float lastRawPdrX;
    private float lastRawPdrY;
    private double startLatitude;
    private double startLongitude;
    private boolean hasStartLocation;
    private boolean filterInitialised;

    public PdrWifiFusionManager(SharedPreferences settings) {
        this.settings = settings;
    }

    public boolean isFusionEnabled() {
        return settings.getBoolean("wifi_pdr_correction_enabled", false);
    }

    /**
     * Clears KF state at the start of a recording.
     */
    public void reset(float[] startLocation) {
        synchronized (lock) {
            stateX = 0.0;
            stateY = 0.0;
            lastRawPdrX = 0f;
            lastRawPdrY = 0f;
            hasStartLocation = false;
            filterInitialised = false;
            startLatitude = 0.0;
            startLongitude = 0.0;
            double initialVariance = square(getInitialStdMeters());
            covarianceX = initialVariance;
            covarianceY = initialVariance;
            updateStartLocationLocked(startLocation);
        }
    }

    /**
     * Predicts the current fused PDR position using the latest raw PDR coordinates.
     */
    public float[] getCorrectedPdr(float[] rawPdr, float[] startLocation) {
        if (rawPdr == null || rawPdr.length < 2) {
            return null;
        }

        synchronized (lock) {
            updateStartLocationLocked(startLocation);
            predictWithPdrLocked(rawPdr);
            return new float[]{(float) stateX, (float) stateY};
        }
    }

    /**
     * Converts the fused KF state into an absolute map location.
     */
    public LatLng getCorrectedLatLng(float[] rawPdr, float[] startLocation) {
        float[] correctedPdr = getCorrectedPdr(rawPdr, startLocation);
        if (correctedPdr == null) {
            return null;
        }

        synchronized (lock) {
            if (!hasStartLocation) {
                return null;
            }
            return metersToLatLngLocked(correctedPdr[0], correctedPdr[1]);
        }
    }

    /**
     * Updates the KF with a WiFi absolute position if the residual passes the gate.
     */
    public void applyWifiCorrection(LatLng wifiLocation, float[] startLocation) {
        if (!isValidLocation(wifiLocation)) {
            return;
        }

        synchronized (lock) {
            updateStartLocationLocked(startLocation);
            if (!hasStartLocation || !filterInitialised) {
                return;
            }

            float[] wifiMeters = latLngToMetersLocked(wifiLocation);
            double residualX = wifiMeters[0] - stateX;
            double residualY = wifiMeters[1] - stateY;
            double residualMeters = Math.hypot(residualX, residualY);
            int gateMeters = getWifiGateMeters();

            if (residualMeters > gateMeters) {
                Log.d(TAG, "Rejected KF WiFi update, residual=" + residualMeters + "m");
                return;
            }

            double measurementVariance = square(getWifiStdMeters());
            double gainX = covarianceX / (covarianceX + measurementVariance);
            double gainY = covarianceY / (covarianceY + measurementVariance);

            stateX += gainX * residualX;
            stateY += gainY * residualY;
            covarianceX = Math.max((1.0 - gainX) * covarianceX, 1.0e-6);
            covarianceY = Math.max((1.0 - gainY) * covarianceY, 1.0e-6);

            Log.d(TAG, "Applied KF WiFi update, residual=" + residualMeters
                    + "m, gain=(" + gainX + ", " + gainY + "), state=("
                    + stateX + ", " + stateY + ")");
        }
    }

    private void predictWithPdrLocked(float[] rawPdr) {
        if (!filterInitialised) {
            stateX = rawPdr[0];
            stateY = rawPdr[1];
            lastRawPdrX = rawPdr[0];
            lastRawPdrY = rawPdr[1];
            filterInitialised = true;
            return;
        }

        double deltaX = rawPdr[0] - lastRawPdrX;
        double deltaY = rawPdr[1] - lastRawPdrY;
        if (deltaX == 0.0 && deltaY == 0.0) {
            return;
        }

        stateX += deltaX;
        stateY += deltaY;
        lastRawPdrX = rawPdr[0];
        lastRawPdrY = rawPdr[1];

        double movementMeters = Math.hypot(deltaX, deltaY);
        double processStd = Math.max(
                MIN_PROCESS_STD_M,
                movementMeters * getPdrProcessNoiseScale());
        double processVariance = square(processStd);
        covarianceX += processVariance;
        covarianceY += processVariance;
    }

    private void updateStartLocationLocked(float[] startLocation) {
        if (startLocation == null || startLocation.length < 2) {
            return;
        }

        double lat = startLocation[0];
        double lng = startLocation[1];
        if (lat == 0.0 && lng == 0.0) {
            return;
        }

        startLatitude = lat;
        startLongitude = lng;
        hasStartLocation = true;
    }

    private float[] latLngToMetersLocked(LatLng location) {
        float x = (float) ((location.longitude - startLongitude)
                * DEGREE_IN_M * Math.cos(Math.toRadians(startLatitude)));
        float y = (float) ((location.latitude - startLatitude) * DEGREE_IN_M);
        return new float[]{x, y};
    }

    private LatLng metersToLatLngLocked(float x, float y) {
        double lat = startLatitude + y / DEGREE_IN_M;
        double lng = startLongitude + x
                / (DEGREE_IN_M * Math.cos(Math.toRadians(startLatitude)));
        return new LatLng(lat, lng);
    }

    private boolean isValidLocation(LatLng location) {
        if (location == null) {
            return false;
        }
        return !Double.isNaN(location.latitude)
                && !Double.isNaN(location.longitude)
                && Math.abs(location.latitude) <= 90.0
                && Math.abs(location.longitude) <= 180.0
                && !(location.latitude == 0.0 && location.longitude == 0.0);
    }

    private int getInitialStdMeters() {
        return settings.getInt("kf_initial_std_m", DEFAULT_INITIAL_STD_M);
    }

    private int getWifiStdMeters() {
        return settings.getInt("kf_wifi_std_m", DEFAULT_WIFI_STD_M);
    }

    private double getPdrProcessNoiseScale() {
        return settings.getInt("kf_pdr_process_percent", DEFAULT_PDR_PROCESS_PERCENT) / 100.0;
    }

    private int getWifiGateMeters() {
        return settings.getInt("kf_wifi_gate_m", DEFAULT_WIFI_GATE_M);
    }

    private double square(double value) {
        return value * value;
    }
}
