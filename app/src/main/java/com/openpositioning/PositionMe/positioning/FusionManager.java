package com.openpositioning.PositionMe.positioning;

import android.util.Log;

/**
 * Sensor fusion engine using a particle filter to combine PDR, GNSS, and WiFi
 * observations into a single position estimate.
 *
 * <p>Implements §3.1 (Positioning Fusion):</p>
 * <ul>
 *   <li>Particle filter with 250 particles in easting/northing space</li>
 *   <li>Automatic initialisation from GNSS or WiFi (no user selection)</li>
 *   <li>PDR movement model via {@link #onStep}</li>
 *   <li>GNSS positioning updates via {@link #onGnss}</li>
 *   <li>WiFi positioning updates via {@link #onWifi}</li>
 * </ul>
 *
 * <p>Includes a WiFi death-spiral recovery mechanism: if the compass drifts
 * the estimate beyond the WiFi outlier gate and consecutive fixes are rejected,
 * the filter re-centres on WiFi to prevent permanent divergence.</p>
 *
 * @see ParticleFilter for the underlying filter implementation
 * @see com.openpositioning.PositionMe.mapmatching.MapMatchingEngine for map-based corrections
 */
public class FusionManager {

    private static final String TAG = "FusionManager";
    private static final int PARTICLE_COUNT = 250;

    private static final FusionManager instance = new FusionManager();

    private ParticleFilter particleFilter;
    private CoordinateUtils coords;
    private boolean ready = false;

    /** Exponential moving average for display smoothing (higher = faster response). */
    private double[] smoothedLatLon = null;
    private static final double DISPLAY_SMOOTHING_ALPHA = 0.5;

    /** Tracks whether FM was initialised from WiFi to avoid re-init from weaker GNSS. */
    private boolean initialisedFromWifi = false;

    /**
     * WiFi death-spiral recovery counter. When compass error causes FM to drift
     * past the outlier gate, every subsequent WiFi fix is also rejected, causing
     * permanent divergence. After {@code MAX_WIFI_REJECTIONS_BEFORE_RECENTRE}
     * consecutive rejections, FM force re-centres on the WiFi position.
     */
    private int consecutiveWifiRejections = 0;
    private static final int MAX_WIFI_REJECTIONS_BEFORE_RECENTRE = 2;

    /** WiFi outlier gate distance in metres — fixes beyond this are rejected. */
    private static final double WIFI_OUTLIER_GATE_M = 10.0;

    private FusionManager() {}

    public static FusionManager getInstance() {
        return instance;
    }

    /** Resets all state for a new recording session. */
    public synchronized void reset() {
        particleFilter = null;
        coords = null;
        ready = false;
        smoothedLatLon = null;
        initialisedFromWifi = false;
        consecutiveWifiRejections = 0;
    }

    // =========================================================================
    // GNSS observation update (§3.1)
    // =========================================================================

    /**
     * Processes a GNSS position fix. Initialises the filter on first valid fix,
     * or updates the particle weights for subsequent fixes.
     *
     * @param lat           WGS84 latitude
     * @param lon           WGS84 longitude
     * @param accuracyMeters reported horizontal accuracy from the location provider
     */
    public synchronized void onGnss(double lat, double lon, float accuracyMeters) {
        float rawAccuracy = accuracyMeters > 0 ? accuracyMeters : 8.0f;

        // Indoor GNSS below 15m is rare — reject poor fixes early
        if (rawAccuracy > 15.0f) {
            Log.d(TAG, "Ignoring GNSS fix due to poor accuracy: " + rawAccuracy);
            return;
        }

        float sigma = Math.max(6.0f, rawAccuracy * 1.5f);

        if (!ready) {
            coords = new CoordinateUtils(lat, lon);
            particleFilter = new ParticleFilter(PARTICLE_COUNT);
            particleFilter.initialise(0.0, 0.0, Math.max(sigma, 6.0f));
            ready = true;
            Log.d(TAG, "Initialised from GNSS: " + lat + ", " + lon + " acc=" + sigma);
            return;
        }

        double[] en = coords.toLocal(lat, lon);

        // Outlier gate: reject fixes far from current estimate
        double[] est = particleFilter.estimate();
        if (est != null) {
            double de = en[0] - est[0];
            double dn = en[1] - est[1];
            double dist = Math.sqrt(de * de + dn * dn);

            if (dist > Math.max(18.0, sigma * 2.5)) {
                Log.d(TAG, "Rejected GNSS outlier. Dist=" + dist + " sigma=" + sigma);
                return;
            }
        }

        particleFilter.updateGnss(en[0], en[1], sigma);
    }

    // =========================================================================
    // WiFi observation update (§3.1)
    // =========================================================================

    /**
     * Processes a WiFi position fix from the openpositioning API.
     *
     * <p>On first fix, initialises the filter. If GNSS-initialised and WiFi
     * disagrees significantly, re-centres on WiFi (more reliable indoors).
     * Includes death-spiral recovery: after consecutive rejections, force
     * re-centres to prevent permanent compass-driven divergence.</p>
     *
     * @param lat WGS84 latitude from WiFi positioning
     * @param lon WGS84 longitude from WiFi positioning
     */
    public synchronized void onWifi(double lat, double lon) {
        if (!ready) {
            // First fix — initialise from WiFi
            coords = new CoordinateUtils(lat, lon);
            particleFilter = new ParticleFilter(PARTICLE_COUNT);
            particleFilter.initialise(0.0, 0.0, 8.0);
            ready = true;
            initialisedFromWifi = true;
            consecutiveWifiRejections = 0;
            Log.d(TAG, "Initialised from WiFi: " + lat + ", " + lon);
            return;
        }

        // If GNSS-initialised and WiFi is far away, re-centre once on WiFi
        // since WiFi is more accurate indoors than GNSS
        if (!initialisedFromWifi && coords != null) {
            double[] en = coords.toLocal(lat, lon);
            double[] est = particleFilter.estimate();
            if (est != null) {
                double dist = Math.sqrt(Math.pow(en[0] - est[0], 2) + Math.pow(en[1] - est[1], 2));
                if (dist > 10.0) {
                    coords = new CoordinateUtils(lat, lon);
                    particleFilter = new ParticleFilter(PARTICLE_COUNT);
                    particleFilter.initialise(0.0, 0.0, 8.0);
                    initialisedFromWifi = true;
                    consecutiveWifiRejections = 0;
                    Log.d(TAG, "Re-centred from WiFi (GNSS was " + String.format("%.1f", dist) + "m off): " + lat + ", " + lon);
                    return;
                }
            }
            initialisedFromWifi = true;
        }

        double[] en = coords.toLocal(lat, lon);

        // Outlier gate with death-spiral recovery
        double[] est = particleFilter.estimate();
        if (est != null) {
            double de = en[0] - est[0];
            double dn = en[1] - est[1];
            double dist = Math.sqrt(de * de + dn * dn);

            if (dist > WIFI_OUTLIER_GATE_M) {
                consecutiveWifiRejections++;

                if (consecutiveWifiRejections >= MAX_WIFI_REJECTIONS_BEFORE_RECENTRE) {
                    // Death-spiral detected: compass drift has pushed FM so far
                    // that every WiFi fix gets rejected. WiFi is the most reliable
                    // indoor source — re-centre unconditionally.
                    coords = new CoordinateUtils(lat, lon);
                    particleFilter = new ParticleFilter(PARTICLE_COUNT);
                    particleFilter.initialise(0.0, 0.0, 8.0);
                    smoothedLatLon = null;
                    consecutiveWifiRejections = 0;
                    Log.w(TAG, "Re-centred from WiFi after " + MAX_WIFI_REJECTIONS_BEFORE_RECENTRE
                            + " consecutive rejections. Dist was " + String.format("%.1f", dist) + "m");
                    return;
                }

                Log.d(TAG, "Rejected WiFi outlier. Dist=" + dist
                        + " (rejection " + consecutiveWifiRejections
                        + "/" + MAX_WIFI_REJECTIONS_BEFORE_RECENTRE + ")");
                return;
            }
        }

        // WiFi accepted — reset rejection counter and update particles
        consecutiveWifiRejections = 0;
        particleFilter.updateWifi(en[0], en[1]);
    }

    // =========================================================================
    // PDR movement model (§3.1)
    // =========================================================================

    /**
     * Propagates all particles forward using the PDR step detection.
     *
     * @param stepLen    step length in metres from the step detector
     * @param headingRad compass heading in radians from the rotation vector sensor
     */
    public synchronized void onStep(double stepLen, double headingRad) {
        if (!ready || particleFilter == null) return;
        if (Double.isNaN(stepLen) || Double.isNaN(headingRad) || stepLen <= 0.05) return;
        particleFilter.predict(stepLen, headingRad);
    }

    // =========================================================================
    // Position output
    // =========================================================================

    /**
     * Returns the best fused position estimate in WGS84, with exponential
     * moving average display smoothing applied.
     *
     * @return [latitude, longitude] or null if not yet initialised
     */
    public synchronized double[] getBestPosition() {
        if (!ready || particleFilter == null || coords == null) return null;

        double[] en = particleFilter.estimate();
        if (en == null) return null;

        double[] latLon = coords.toGlobal(en[0], en[1]);

        if (smoothedLatLon == null) {
            smoothedLatLon = latLon;
        } else {
            smoothedLatLon[0] =
                    DISPLAY_SMOOTHING_ALPHA * latLon[0] +
                            (1.0 - DISPLAY_SMOOTHING_ALPHA) * smoothedLatLon[0];
            smoothedLatLon[1] =
                    DISPLAY_SMOOTHING_ALPHA * latLon[1] +
                            (1.0 - DISPLAY_SMOOTHING_ALPHA) * smoothedLatLon[1];
        }

        return new double[]{smoothedLatLon[0], smoothedLatLon[1]};
    }

    /** Returns the raw particle filter estimate in local ENU coordinates. */
    public synchronized double[] getBestPositionLocal() {
        if (!ready || particleFilter == null) return null;
        return particleFilter.estimate();
    }

    /** Returns the effective sample size ratio as a confidence metric (0–1). */
    public synchronized double getConfidence() {
        if (!ready || particleFilter == null) return 0.0;
        return particleFilter.getConfidence();
    }

    /** Returns true once the filter has been initialised from any source. */
    public synchronized boolean isReady() {
        return ready;
    }
}