package com.openpositioning.PositionMe.fusion;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import androidx.annotation.Nullable;

/**
 * High-level manager around the particle filter engine.
 *
 * <p>This class is responsible for:
 * - loading PF settings from SharedPreferences
 * - listening for user changes to those settings
 * - initialising the engine when enough information is available
 * - converting raw app data into PF prediction + observation inputs
 * - storing the latest fused pose estimate
 *
 * <p>This class does not implement the PF math itself.
 * That logic lives in {@link ParticleFilterEngine}.
 */
public class ParticleFilterManager {

    private static final String TAG = "RecordingMode";

    /** Main app sensor/data source. */
    private final SensorFusion sensorFusion;

    /** Application context used for preferences. */
    private final Context appContext;

    /** SharedPreferences backing live-tunable PF settings. */
    private final SharedPreferences prefs;

    /** Converts between global LatLng and local x/y coordinates. */
    private CoordinateConverter coordinateConverter;

    /** The actual PF engine instance. */
    private ParticleFilterEngine particleFilterEngine;

    /** Latest estimated fused pose. */
    private FusedPose latestFusedPose;
    /** Latest raw PF estimate before Kalman-style output smoothing. */
    private FusedPose latestRawPose;

    /** Kalman-style output smoother sitting after the PF. */
    private final KalmanPoseSmoother poseSmoother = new KalmanPoseSmoother();

    /** Whether WiFi observation was accepted during the latest buildObservation() call. */
    private boolean lastWifiAccepted = false;

    /** Whether GNSS observation was accepted during the latest buildObservation() call. */
    private boolean lastGnssAccepted = false;

    /*WiFi and GNSS gating*/
    private static final double WIFI_GATE_METERS = 12.0;
    private static final double GNSS_GATE_METERS = 25.0;
    private static final double WIFI_OBS_EMA_ALPHA = 0.25;

    private boolean wifiObsEmaInitialised = false;
    private double wifiObsEmaX = 0.0;
    private double wifiObsEmaY = 0.0;

    /*When false, the manager remains constructed and can still hold PF settings,
    but it must not produce fused poses or consume step updates for this session.*/
    private boolean enabled = false;

    // -------------------------
    // PDR incremental tracking
    /**
     * PF prediction currently uses incremental displacement magnitude extracted
     * from cumulative PDR/IMUNet position.
     *
     * These fields store the previous position so we can compute deltaS.
     */
    private boolean firstPdrSample = true;
    private double lastPdrX = 0.0;
    private double lastPdrY = 0.0;

    /** Previous heading so we can compute deltaTheta. */
    private double lastHeading = 0.0;

    // -------------------------
    // Live PF settings
    /** Current active PF settings. */
    private ParticleFilterConfig currentConfig;

    /**
     * Set to true when the user changes PF settings in-app.
     *
     * <p>At the next step() call we reset the filter and allow it to reinitialise
     * using the new parameter set.
     */
    private boolean pfConfigDirty = false;

    public ParticleFilterManager(SensorFusion sensorFusion, Context context) {
        this.sensorFusion = sensorFusion;
        this.appContext = context.getApplicationContext();

        this.prefs = PreferenceManager.getDefaultSharedPreferences(appContext);

        // Load config once at construction time
        this.currentConfig = loadPfConfig();

        // Listen for runtime parameter changes from the settings UI
        prefs.registerOnSharedPreferenceChangeListener(pfListener);
    }

    /**
     * Preference change listener for PF parameters only.
     *
     * <p>When the user edits a PF setting in the app:
     * - reload config once
     * - mark the filter dirty
     * - let the next step() reset and rebuild the PF
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener pfListener =
            (sharedPreferences, key) -> {
                if (key == null) return;

                switch (key) {
                    case "pf_particle_count":
                    case "pf_sigma_step":
                    case "pf_sigma_theta_deg":
                    case "pf_sigma_wifi":
                    case "pf_sigma_gnss":
                    case "pf_init_pos_std":
                    case "pf_init_heading_deg":
                    case "pf_resample_ratio":
                    case "pf_sigma_reg_pos":
                    case "pf_sigma_reg_theta_deg":
                        currentConfig = loadPfConfig();
                        pfConfigDirty = true;
                        Log.d(TAG, "PF config changed: " + key);
                        break;
                }
            };

    /**
     * Must be called when the manager is no longer needed,
     * otherwise the preference listener remains registered.
     */
    public void destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(pfListener);
    }

    /**
     * Loads all PF tunable parameters from SharedPreferences.
     *
     * <p>Angle values are stored in degrees in the UI,
     * but converted to radians here before being passed to the engine.
     */
    private ParticleFilterConfig loadPfConfig() {
        int particleCount = Integer.parseInt(prefs.getString("pf_particle_count", "300"));
        double sigmaStep = Double.parseDouble(prefs.getString("pf_sigma_step", "0.15"));
        double sigmaThetaDeg = Double.parseDouble(prefs.getString("pf_sigma_theta_deg", "6"));
        double sigmaWifi = Double.parseDouble(prefs.getString("pf_sigma_wifi", "2.5"));
        double sigmaGnss = Double.parseDouble(prefs.getString("pf_sigma_gnss", "5.0"));
        double initPosStd = Double.parseDouble(prefs.getString("pf_init_pos_std", "1.0"));
        double initHeadingDeg = Double.parseDouble(prefs.getString("pf_init_heading_deg", "10"));
        double resampleRatio = Double.parseDouble(prefs.getString("pf_resample_ratio", "0.5"));
        double sigmaRegPos = Double.parseDouble(prefs.getString("pf_sigma_reg_pos", "0.03"));
        double sigmaRegThetaDeg = Double.parseDouble(prefs.getString("pf_sigma_reg_theta_deg", "1.0"));

        Log.d("loadPfConfig", "Particle count" + particleCount);
        Log.d("loadPfConfig", "SigmaStep" + sigmaStep);
        Log.d("loadPfConfig", "SigmaWifi" + sigmaWifi);

        return new ParticleFilterConfig(
                particleCount,
                sigmaStep,
                Math.toRadians(sigmaThetaDeg),
                sigmaWifi,
                sigmaGnss,
                initPosStd,
                Math.toRadians(initHeadingDeg),
                resampleRatio,
                sigmaRegPos,
                Math.toRadians(sigmaRegThetaDeg)
        );
    }

    /**
     * Advances the particle filter by one logical movement update.
     *
     * This method is usually triggered once per detected step, after the raw PDR
     * state has already been updated by {@link PdrProcessing}.
     *
     * Runtime flow:
     * 1. If the user changed PF settings in the UI, defer-reset the filter here.
     *    We do it here rather than immediately inside the preference listener so
     *    the PF is only rebuilt at a clean movement-update boundary.
     * 2. Initialise the PF lazily when enough absolute position information exists.
     * 3. Convert cumulative PDR motion into incremental motion for PF prediction.
     * 4. Run PF prediction using step distance + heading change.
     * 5. Build the absolute observation bundle from Wi-Fi / GNSS.
     * 6. Update the filter and store the newest fused pose.
     */
    public void step() {
        // If this recording session is using normal PDR mode,
        // the PF manager must stay dormant.
        if (!enabled) {
            return;
        }
        // Apply any live-tuned PF parameter changes on the next clean PF update.
        // This avoids rebuilding the filter in the middle of an unrelated callback.
        if (pfConfigDirty) {
            Log.d(TAG, "Applying new PF config -> resetting filter");
            reset();
            pfConfigDirty = false;
        }
        // Build the PF only when an initial absolute reference is available.
        initialiseIfNeeded();
        // Still waiting for enough data to initialise.
        if (particleFilterEngine == null || coordinateConverter == null) {
            return;
        }
        // Convert cumulative PDR movement into the scalar motion increment used
        // by the PF motion model.
        double deltaS = extractPdrDeltaDistance();
        // Heading increment is derived from the latest orientation estimate.
        double currentHeading = wrapAngle(sensorFusion.passOrientation());
        double deltaTheta = wrapAngle(currentHeading - lastHeading);
        lastHeading = currentHeading;
        // Avoid predicting on numerical noise when the apparent movement is tiny.
        // We still allow the absolute observation update below.
        if (deltaS >= 0.01) {
            particleFilterEngine.predict(deltaS, deltaTheta);
            Log.d(TAG, "PF predict: deltaS=" + deltaS + ", deltaTheta=" + deltaTheta);
        } else {
            Log.d(TAG, "PF stationary: skip predict, keep update");
        }
        // Build absolute observations in the local PF frame, then correct the particles.
        ParticleFilterObservation observation = buildObservation();
        particleFilterEngine.update(observation);

        FusedPose rawPose = particleFilterEngine.estimate(coordinateConverter);
        latestRawPose = rawPose;

        latestFusedPose = smoothPoseWithKalman(
                rawPose,
                deltaS,
                Math.abs(deltaTheta),
                lastWifiAccepted,
                lastGnssAccepted
        );

        if (rawPose != null && latestFusedPose != null) {
            Log.d(TAG,
                    "PF raw vs kalman-smoothed"
                            + " | rawX=" + rawPose.getXMeters()
                            + ", rawY=" + rawPose.getYMeters()
                            + ", smoothX=" + latestFusedPose.getXMeters()
                            + ", smoothY=" + latestFusedPose.getYMeters()
                            + ", rawThetaDeg=" + Math.toDegrees(rawPose.getHeadingRad())
                            + ", smoothThetaDeg=" + Math.toDegrees(latestFusedPose.getHeadingRad())
                            + ", conf=" + latestFusedPose.getConfidence()
                            + ", wifiAccepted=" + lastWifiAccepted
                            + ", gnssAccepted=" + lastGnssAccepted);
        }
    }

    /**
     * Initialises the PF only when a reliable autonomous absolute anchor is available.
     *
     * Priority:
     * 1. already-locked autonomous session start stored in SensorFusion
     * 2. live Wi-Fi position
     * 3. live GNSS position
     *
     * This avoids dependence on a user-selected manual start point.
     */
    public void initialiseIfNeeded() {
        if (particleFilterEngine != null && particleFilterEngine.isInitialised()) {
            return;
        }

        LatLng initialLatLng = resolveAutonomousInitialLatLng();
        int initialFloor = sensorFusion.getWifiFloor();

        if (initialLatLng == null) {
            Log.d(TAG, "PF init skipped: waiting for autonomous WiFi/GNSS initial position.");
            return;
        }

        coordinateConverter = new CoordinateConverter(
                initialLatLng.latitude,
                initialLatLng.longitude
        );

        double[] initialLocal = coordinateConverter.latLngToLocal(initialLatLng);

        particleFilterEngine = new ParticleFilterEngine(currentConfig);

        poseSmoother.reset();
        double initialHeading = wrapAngle(sensorFusion.passOrientation());
        particleFilterEngine.initialise(
                initialLocal[0],
                initialLocal[1],
                initialHeading,
                initialFloor
        );

        FusedPose initialRawPose = particleFilterEngine.estimate(coordinateConverter);
        latestRawPose = initialRawPose;
        latestFusedPose = smoothPoseWithKalman(
                initialRawPose,
                0.0,
                0.0,
                true,
                true
        );
        lastHeading = initialHeading;

        Log.d(TAG,
                "PF initialised from autonomous fix"
                        + " | particles=" + currentConfig.particleCount
                        + ", sigmaStep=" + currentConfig.sigmaStep
                        + ", sigmaThetaDeg=" + Math.toDegrees(currentConfig.sigmaThetaRad)
                        + ", sigmaWifi=" + currentConfig.sigmaWifi
                        + ", sigmaGnss=" + currentConfig.sigmaGnss
                        + ", lat=" + initialLatLng.latitude
                        + ", lon=" + initialLatLng.longitude);
    }

    /**
     * Resolves the PF initial anchor without using any manual user-selected position.
     *
     * Order:
     * 1. already-locked autonomous session start from SensorFusion
     * 2. live WiFi position
     * 3. live GNSS position
     *
     * The first option keeps PF aligned with the same anchor already accepted by
     * RecordingFragment once that has been seeded.
     */
    private LatLng resolveAutonomousInitialLatLng() {
        // If RecordingFragment has already locked an autonomous start anchor into
        // SensorFusion, prefer that so standard PDR mode and PF mode share the same start.
        float[] storedStart = sensorFusion.getGNSSLatitude(true);
        if (isValidLatLon(storedStart)) {
            return new LatLng(storedStart[0], storedStart[1]);
        }

        LatLng wifiLatLng = sensorFusion.getLatLngWifiPositioning();
        if (isValidLatLng(wifiLatLng)) {
            return wifiLatLng;
        }

        float[] gnssLatLon = sensorFusion.getGNSSLatitude(false);
        if (isValidLatLon(gnssLatLon)) {
            return new LatLng(gnssLatLon[0], gnssLatLon[1]);
        }

        return null;
    }

    /**
     * Clears only the live PF runtime state.
     *
     * This is intentionally different from clearing PF settings:
     * - currentConfig is preserved
     * - SharedPreferences values are preserved
     * - only the active filter instance and motion trackers are reset
     *
     * This is useful when:
     * - the user starts a new recording
     * - PF settings change mid-session
     * - PF mode is disabled for the current session
     */
    public void reset() {
        coordinateConverter = null;
        particleFilterEngine = null;
        latestFusedPose = null;
        latestRawPose = null;

        firstPdrSample = true;
        lastPdrX = 0.0;
        lastPdrY = 0.0;
        lastHeading = 0.0;

        wifiObsEmaInitialised = false;
        wifiObsEmaX = 0.0;
        wifiObsEmaY = 0.0;

        poseSmoother.reset();
        lastWifiAccepted = false;
        lastGnssAccepted = false;
    }

    /**
     * Returns the latest fused estimate only when PF mode is active for this session.
     *
     * Returning null while disabled is deliberate:
     * callers should interpret null as "PF output is not available / not in use",
     * then fall back to normal PDR rendering if needed.
     */
    public FusedPose getLatestFusedPose() {
        return enabled ? latestFusedPose : null;
    }

    public FusedPose getLatestRawPose(){ return enabled ? latestRawPose : null;}
    /**
     * Enables or disables PF participation for the current recording session.
     *
     * When disabling, we immediately reset the live PF state so stale fused poses
     * from a previous session cannot leak into a standard-PDR recording.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;

        Log.d(TAG, "PF manager = " + (enabled ? "ENABLED" : "DISABLED"));

        if (!enabled) {
            reset();
        }
    }
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Builds one observation bundle for the PF correction step.
     *
     * Observations are converted from global coordinates (LatLng) into the local
     * x/y frame used internally by the PF.
     *
     * Current observation sources:
     * - Wi-Fi position + Wi-Fi floor
     * - GNSS position
     *
     * Either source may be absent in a given update cycle.
     */
    private ParticleFilterObservation buildObservation() {
        lastWifiAccepted = false;
        lastGnssAccepted = false;

        Double wifiX = null;
        Double wifiY = null;
        Integer wifiFloor = null;

        LatLng wifiLatLng = sensorFusion.getLatLngWifiPositioning();
        if (isValidLatLng(wifiLatLng)) {
            double[] local = coordinateConverter.latLngToLocal(wifiLatLng);

            boolean acceptWifi = true;
            FusedPose gateRef = getGateReferencePose();
            if (gateRef != null) {
                double dx = local[0] - gateRef.getXMeters();
                double dy = local[1] - gateRef.getYMeters();
                double dist = Math.hypot(dx, dy);

                if (dist > WIFI_GATE_METERS) {
                    acceptWifi = false;
                    Log.d(TAG, "Rejecting WiFi update by gate"
                            + " | dist=" + dist
                            + ", gate=" + WIFI_GATE_METERS);
                }
            }

            if (acceptWifi) {
                double[] smoothWifi = smoothWifiObservation(local[0], local[1]);
                wifiX = smoothWifi[0];
                wifiY = smoothWifi[1];
                wifiFloor = sensorFusion.getWifiFloor();
                lastWifiAccepted = true;
            }
        }

        Double gnssX = null;
        Double gnssY = null;

        float[] gnssLatLon = sensorFusion.getGNSSLatitude(false);
        if (isValidLatLon(gnssLatLon)) {
            LatLng gnssLatLng = new LatLng(gnssLatLon[0], gnssLatLon[1]);
            double[] local = coordinateConverter.latLngToLocal(gnssLatLng);

            boolean acceptGnss = true;
            FusedPose gateRef = getGateReferencePose();
            if (gateRef != null) {
                double dx = local[0] - gateRef.getXMeters();
                double dy = local[1] - gateRef.getYMeters();
                double dist = Math.hypot(dx, dy);

                if (dist > GNSS_GATE_METERS) {
                    acceptGnss = false;
                    Log.d(TAG, "Rejecting GNSS update by gate"
                            + " | dist=" + dist
                            + ", gate=" + GNSS_GATE_METERS);
                }
            }

            if (acceptGnss) {
                gnssX = local[0];
                gnssY = local[1];
                lastGnssAccepted = true;
            }
        }

        return new ParticleFilterObservation(wifiX, wifiY, wifiFloor, gnssX, gnssY);
    }

    private double[] smoothWifiObservation(double x, double y) {
        if (!wifiObsEmaInitialised) {
            wifiObsEmaInitialised = true;
            wifiObsEmaX = x;
            wifiObsEmaY = y;
        } else {
            wifiObsEmaX = wifiObsEmaX + WIFI_OBS_EMA_ALPHA * (x - wifiObsEmaX);
            wifiObsEmaY = wifiObsEmaY + WIFI_OBS_EMA_ALPHA * (y - wifiObsEmaY);
        }

        return new double[]{wifiObsEmaX, wifiObsEmaY};
    }

    @Nullable
    private FusedPose getGateReferencePose() {
        if (latestRawPose != null) {
            return latestRawPose;
        }
        return latestFusedPose;
    }

    /**
     * Converts cumulative PDR position into incremental distance.
     *
     * <p>The PF prediction currently uses:
     * - scalar displacement magnitude deltaS
     * - heading increment deltaTheta
     *
     * so here we compute:
     *   deltaS = hypot(dx, dy)
     */
    private double extractPdrDeltaDistance() {
        float[] pdrPosition = sensorFusion.getLatestPdrMovement();
        if (pdrPosition == null || pdrPosition.length < 2) {
            return 0.0;
        }

        double px = pdrPosition[0];
        double py = pdrPosition[1];

        if (firstPdrSample) {
            firstPdrSample = false;
            lastPdrX = px;
            lastPdrY = py;
            return 0.0;
        }

        double dx = px - lastPdrX;
        double dy = py - lastPdrY;

        lastPdrX = px;
        lastPdrY = py;

        return Math.hypot(dx, dy);
    }

    private FusedPose smoothPoseWithKalman(
            FusedPose rawPose,
            double deltaS,
            double deltaThetaAbs,
            boolean wifiAccepted,
            boolean gnssAccepted
    ) {
        if (rawPose == null || coordinateConverter == null) {
            return rawPose;
        }

        KalmanPoseSmoother.SmoothedPose smoothed = poseSmoother.update(
                rawPose.getXMeters(),
                rawPose.getYMeters(),
                rawPose.getHeadingRad(),
                rawPose.getConfidence(),
                deltaS,
                deltaThetaAbs,
                wifiAccepted,
                gnssAccepted
        );

        LatLng smoothedLatLng = coordinateConverter.localToLatLng(
                smoothed.x,
                smoothed.y
        );

        return new FusedPose(
                smoothed.x,
                smoothed.y,
                smoothed.theta,
                rawPose.getFloor(),
                smoothedLatLng,
                rawPose.getConfidence()
        );
    }

    /**
     * Checks whether a LatLng is meaningful enough to use.
     */
    private boolean isValidLatLng(LatLng latLng) {
        if (latLng == null) {
            return false;
        }
        return !(Math.abs(latLng.latitude) < 1e-6 && Math.abs(latLng.longitude) < 1e-6);
    }

    /**
     * Checks whether a raw float lat/lon array is meaningful enough to use.
     */
    private boolean isValidLatLon(float[] latLon) {
        if (latLon == null || latLon.length < 2) {
            return false;
        }
        return !(Math.abs(latLon[0]) < 1e-6 && Math.abs(latLon[1]) < 1e-6);
    }

    /**
     * Wraps heading angle to [-pi, pi].
     */
    private double wrapAngle(double angle) {
        while (angle > Math.PI) angle -= 2.0 * Math.PI;
        while (angle < -Math.PI) angle += 2.0 * Math.PI;
        return angle;
    }
}