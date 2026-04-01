package com.openpositioning.PositionMe.utils;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Backward-compatible facade that routes updates into CascadedFusionManager.
 */
public class FusionManager {

    private static final String TAG = "FusionManager";
    // WiFi API positioning accuracy indoors is typically 3-10 m; a lower score means
    // the EKF trusts it less and doesn't snap the trajectory sideways on each 5-second update.
    private static final double WIFI_OBSERVATION_SCORE = 0.45;
    // Indoor GNSS accuracy is usually 10-30 m; capping the score keeps it from pulling
    // the trajectory away from a good PDR estimate.
    private static final double GNSS_MIN_SCORE = 0.12;
    private static final double GNSS_MAX_SCORE = 0.20;
    // Tighter outlier rejection: a jump > 6 m between consecutive fixes is almost certainly
    // an indoor GNSS multipath artefact, not real movement.
    private static final double GNSS_OUTLIER_REJECT_DIST_M = 6.0;
    private static final double WIFI_OUTLIER_REJECT_DIST_M = 5.0;

    // ---- WiFi-based continuous heading correction ----
    // Minimum displacement (metres) between WiFi fixes before inferring heading
    private static final double HEADING_CORR_MIN_DISP_M = 4.0;
    // Maximum correction per WiFi heading update (radians) to prevent sudden heading jumps
    private static final double HEADING_CORR_MAX_RAD = Math.toRadians(8.0);
    // Reject WiFi heading innovations that are too large to be reliable indoors.
    private static final double HEADING_CORR_MAX_INNOV_RAD = Math.toRadians(20.0);
    // Require WiFi/PDR displacement magnitudes to be broadly consistent.
    private static final double HEADING_CORR_MIN_DISP_RATIO = 0.5;
    private static final double HEADING_CORR_MAX_DISP_RATIO = 1.8;
    // EMA gain for heading offset correction (lower = smoother, higher = faster convergence)
    private static final double HEADING_CORR_GAIN = 0.10;

    // WiFi fingerprint training gates
    // Require enough APs to avoid training with sparse/noisy scans.
    private static final int MIN_WIFI_FINGERPRINT_APS = 6;
    // Add fingerprints only when the user has moved enough to increase spatial diversity.
    private static final double MIN_WIFI_FINGERPRINT_SPACING_M = 1.5;
    // Also enforce a time interval to avoid flooding the DB with near-duplicates.
    private static final long MIN_WIFI_FINGERPRINT_INTERVAL_MS = 2000;

    private final CascadedFusionManager cascadedFusionManager = new CascadedFusionManager();

    private float lastFloorAltitude = Float.NaN;
    private float accumulatedHeightChange = 0f;
    private int currentFloor = 0;

    private boolean initialized = false;

    private List<List<LatLng>> pendingWallPolylines = null;
    private int pendingFloorHint = 0;

    private LatLng lastEstimate = null;

    public enum PositionSource { PDR, GNSS, WIFI, FUSED }
    private PositionSource lastSource = PositionSource.PDR;

    private float lastPdrX = 0f;
    private float lastPdrY = 0f;
    private double lastHeadingRad = Double.NaN;
    private boolean firstStepHeadingAligned = false;
    private boolean headingOffsetCalibrated = false;
    private double headingOffsetRad = 0.0;

    // WiFi heading correction state: track WiFi positions to infer actual heading
    private double[] lastWifiEN = null;          // Last WiFi position in EastNorth (for heading inference)
    private double pdrDxSinceWifi = 0.0;         // Accumulated PDR east displacement since last WiFi heading fix
    private double pdrDySinceWifi = 0.0;         // Accumulated PDR north displacement since last WiFi heading fix
    private int stepsSinceWifiHeadingFix = 0;    // Steps taken since last WiFi-based heading correction
    private double[] lastFingerprintEN = null;
    private long lastFingerprintAddMs = 0L;

    // User-anchor mode
    // After the user manually sets their start position, indoor GPS is still unreliable
    // (typically 5-20 m off, biased toward windows/exits).  For the first
    // USER_ANCHOR_DURATION_MS we use a tighter outlier-rejection distance so that
    // GPS cannot pull the trajectory back to its (wrong) outdoor-biased position.
    private long userAnchorStartMs = 0;
    private static final long USER_ANCHOR_DURATION_MS = 60_000; // 60 seconds
    // During anchor mode a GNSS fix more than this many metres from the current
    // EKF estimate is rejected as an outlier (vs. the normal GNSS_OUTLIER_REJECT_DIST_M).
    private static final double USER_ANCHOR_GNSS_OUTLIER_M = 2.5;

    public FusionManager() {
    }

    /**
     * Activate "user-anchor" mode.
     *
     * <p>Call this immediately after the user confirms their starting position on the map.
     * For the next {@value #USER_ANCHOR_DURATION_MS} ms, any GNSS fix that is more than
     * {@value #USER_ANCHOR_GNSS_OUTLIER_M} m from the current EKF estimate is rejected.
     * This prevents indoor GPS (typically biased 5-20 m toward windows/exits) from pulling
     * the trajectory back to the wrong position after the user has manually placed themselves.</p>
     */
    public void setUserAnchor() {
        userAnchorStartMs = System.currentTimeMillis();
        Log.d(TAG, "User anchor set – tight GNSS rejection active for "
                + USER_ANCHOR_DURATION_MS / 1000 + " s");
    }

    /**
     * Initialises the fusion engine with a known starting position and heading.
     * Must be called before any update methods. Resets all internal state and delegates
     * to the underlying {@link CascadedFusionManager}.
     *
     * @param latDeg     starting latitude in decimal degrees (WGS-84).
     * @param lngDeg     starting longitude in decimal degrees (WGS-84).
     * @param accuracy   estimated horizontal accuracy of the starting fix in metres (unused
     *                   internally but provided for API consistency with sensor callbacks).
     * @param headingRad initial heading in radians (0 = north, clockwise positive).
     * @param floor      starting floor number (0 = ground level).
     */
    public void initialize(double latDeg, double lngDeg, float accuracy,
                           double headingRad, int floor) {
        cascadedFusionManager.initialize(latDeg, lngDeg, headingRad);

        lastFloorAltitude = Float.NaN;
        accumulatedHeightChange = 0f;
        currentFloor = floor;
        initialized = true;
        lastEstimate = new LatLng(latDeg, lngDeg);
        lastPdrX = 0f;
        lastPdrY = 0f;
        lastHeadingRad = headingRad;
        firstStepHeadingAligned = false;
        headingOffsetCalibrated = false;
        headingOffsetRad = 0.0;
        lastWifiEN = null;
        pdrDxSinceWifi = 0.0;
        pdrDySinceWifi = 0.0;
        stepsSinceWifiHeadingFix = 0;

        applyPendingWallMapIfAny();

        Log.d(TAG, String.format("Initialized at (%.6f, %.6f) floor=%d", latDeg, lngDeg, floor));
    }

    /**
     * Notifies the manager that the Nucleus building map has been loaded for the given floor.
     * Updates the internal floor tracker so that subsequent position estimates are associated
     * with the correct floor level.
     *
     * @param floor the floor number for which the Nucleus map was loaded (0 = ground level).
     */
    public void loadNucleusMap(int floor) {
        currentFloor = floor;
        Log.d(TAG, "Loaded Nucleus map for floor " + floor);
    }

    /**
     * Legacy no-op retained for API backwards compatibility.
     * Map wall geometry is now supplied via {@link #configureDynamicWallMap} instead.
     *
     * @param matcher ignored; pass {@code null} to document intent.
     * @deprecated Use {@link #configureDynamicWallMap(java.util.List, int)} to supply wall data.
     */
    @Deprecated
    public void setMapMatcher(MapMatcher matcher) {
        // Legacy no-op: map walls are now passed via configureDynamicWallMap.
    }

    /**
     * Converts a set of geographic wall polylines into ENU wall segments and passes them to
     * the underlying {@link CascadedFusionManager} for map-constrained particle filtering.
     *
     * <p>If the filter has not yet been initialised, the wall data is buffered and applied
     * automatically once {@link #initialize} is called.
     *
     * @param wallPolylines list of polylines, each represented as an ordered list of
     *                      {@link com.google.android.gms.maps.model.LatLng} vertices.
     *                      Consecutive vertex pairs define wall segments.
     * @param floorHint     floor number that the supplied walls belong to.
     * @return              number of wall segments successfully loaded, or 0 if the filter
     *                      is not yet ready and the data was buffered instead.
     */
    public int configureDynamicWallMap(List<List<LatLng>> wallPolylines, int floorHint) {
        if (wallPolylines == null || wallPolylines.isEmpty()) {
            return 0;
        }

        if (!initialized || !cascadedFusionManager.isInitialized()) {
            pendingWallPolylines = copyWallPolylines(wallPolylines);
            pendingFloorHint = floorHint;
            return 0;
        }

        CoordinateConverter converter = cascadedFusionManager.getConverter();
        if (converter == null) {
            return 0;
        }

        int segmentCount = 0;
        List<MapConstrainedPF.Wall> walls = new ArrayList<>();
        for (List<LatLng> wall : wallPolylines) {
            if (wall == null || wall.size() < 2) {
                continue;
            }
            for (int i = 1; i < wall.size(); i++) {
                LatLng start = wall.get(i - 1);
                LatLng end = wall.get(i);
                double[] en1 = converter.toEastNorth(start.latitude, start.longitude);
                double[] en2 = converter.toEastNorth(end.latitude, end.longitude);
                walls.add(new MapConstrainedPF.Wall(en1[0], en1[1], en2[0], en2[1]));
                segmentCount++;
            }
        }

        if (segmentCount > 0) {
            cascadedFusionManager.setWalls(walls);
            currentFloor = floorHint;
            Log.d(TAG, "Applied dynamic wall map, segments=" + segmentCount + ", floor=" + floorHint);
        }
        return segmentCount;
    }

    /**
     * Applies any wall map data that was buffered before initialisation.
     * Called internally by {@link #initialize} once the filter is ready to accept wall data.
     */
    private void applyPendingWallMapIfAny() {
        if (pendingWallPolylines == null || pendingWallPolylines.isEmpty()) {
            return;
        }
        configureDynamicWallMap(pendingWallPolylines, pendingFloorHint);
        pendingWallPolylines = null;
    }

    /**
     * Creates a defensive deep copy of a list of wall polylines.
     * Used to safely buffer pending wall data without holding a reference to the caller's list.
     *
     * @param source the original list of polylines to copy; {@code null} inner lists are skipped.
     * @return a new list containing independent copies of each non-null inner polyline list.
     */
    private static List<List<LatLng>> copyWallPolylines(List<List<LatLng>> source) {
        List<List<LatLng>> copy = new ArrayList<>();
        for (List<LatLng> wall : source) {
            if (wall == null) {
                continue;
            }
            copy.add(new ArrayList<>(wall));
        }
        return copy;
    }

    /**
     * Feeds the latest Pedestrian Dead Reckoning (PDR) output into the fusion engine.
     *
     * <p>The incremental displacement since the previous PDR update is extracted and passed
     * to the {@link CascadedFusionManager} as a step-and-turn motion command. On the very
     * first step, the EKF heading is aligned to the sensor heading to resolve the initial
     * heading ambiguity. A continuously updated heading offset (derived from WiFi corrections)
     * is applied to compensate for gyroscope drift.
     *
     * @param pdrX      cumulative PDR East displacement in metres (relative to recording start).
     * @param pdrY      cumulative PDR North displacement in metres (relative to recording start).
     * @param headingRad current heading from the sensor fusion in radians
     *                   (0 = north, clockwise positive).
     * @param stepLenM  step length for the current step in metres; if ≤ 0, the magnitude of
     *                  the PDR displacement is used instead.
     * @param dtMs      elapsed time since the previous PDR update in milliseconds (reserved
     *                  for future use in velocity-based motion models).
     */
    public void updateWithPDR(float pdrX, float pdrY, double headingRad,
                              float stepLenM, long dtMs) {
        if (!initialized) return;

        double dx = pdrX - lastPdrX;
        double dy = pdrY - lastPdrY;
        double moveDistance = Math.hypot(dx, dy);
        double step = stepLenM;
        if (step <= 0f) {
            step = moveDistance;
        }

        // Accumulate PDR displacement for WiFi heading correction
        pdrDxSinceWifi += dx;
        pdrDySinceWifi += dy;
        stepsSinceWifiHeadingFix++;

        // Initial heading alignment (first step only, NOT one-shot calibration)
        if (!firstStepHeadingAligned && Double.isFinite(headingRad)) {
            cascadedFusionManager.alignHeading(headingRad);
            firstStepHeadingAligned = true;
            lastHeadingRad = headingRad;
        }

        // Apply current heading offset (continuously updated by WiFi corrections)
        double usedHeading = headingRad;
        if (Double.isFinite(usedHeading) && headingOffsetCalibrated) {
            usedHeading = wrapAngle(usedHeading + headingOffsetRad);
        }
        double deltaTheta = 0.0;
        if (Double.isFinite(lastHeadingRad) && Double.isFinite(usedHeading)) {
            deltaTheta = wrapAngle(usedHeading - lastHeadingRad);
        }
        lastHeadingRad = usedHeading;

        if (step > 0.0) {
            cascadedFusionManager.onStepDetected(step, deltaTheta);
        }

        lastPdrX = pdrX;
        lastPdrY = pdrY;
        lastEstimate = cascadedFusionManager.getEstimatedLatLng();
        lastSource = PositionSource.PDR;
    }

    /**
     * Called when a WiFi absolute position is available.
     * In addition to updating the EKF/PF, this also infers heading by comparing
     * the WiFi displacement direction with the PDR displacement direction.
     *
     * <p>Key insight: if the user walks straight north but PDR says north-east
     * (due to heading bias), the WiFi displacement will point north while the
     * PDR displacement points north-east. The angular difference is the heading
     * bias, which we correct gradually.</p>
     */
    private void inferHeadingFromWifi(double wifiEast, double wifiNorth) {
        if (lastWifiEN == null) {
            // First WiFi fix – just record position, no heading inference yet
            lastWifiEN = new double[]{wifiEast, wifiNorth};
            pdrDxSinceWifi = 0.0;
            pdrDySinceWifi = 0.0;
            stepsSinceWifiHeadingFix = 0;
            return;
        }

        // WiFi displacement since last heading fix
        double wifiDx = wifiEast - lastWifiEN[0];
        double wifiDy = wifiNorth - lastWifiEN[1];
        double wifiDisp = Math.hypot(wifiDx, wifiDy);
        double pdrDisp = Math.hypot(pdrDxSinceWifi, pdrDySinceWifi);

        // Need sufficient displacement for reliable heading inference
        // Also need at least 3 steps to smooth out WiFi jitter
        if (wifiDisp < HEADING_CORR_MIN_DISP_M || pdrDisp < HEADING_CORR_MIN_DISP_M
                || stepsSinceWifiHeadingFix < 3) {
            return;
        }

        // Displacement consistency gate: if WiFi and PDR travelled very different
        // distances over the same interval, WiFi direction is likely noisy.
        double dispRatio = wifiDisp / Math.max(1e-6, pdrDisp);
        if (dispRatio < HEADING_CORR_MIN_DISP_RATIO || dispRatio > HEADING_CORR_MAX_DISP_RATIO) {
            return;
        }

        // Infer heading from WiFi displacement vs PDR displacement
        // WiFi heading = direction the user ACTUALLY moved
        // PDR heading = direction PDR THINKS the user moved
        double wifiHeading = wrapAngle(Math.atan2(wifiDx, wifiDy));  // EN → heading(0=N)
        double pdrHeading = wrapAngle(Math.atan2(pdrDxSinceWifi, pdrDySinceWifi));

        // The difference is the heading bias: how much PDR is off
        double headingError = wrapAngle(wifiHeading - pdrHeading);

        // Innovation gate: ignore implausibly large heading disagreements from noisy WiFi.
        if (Math.abs(headingError) > HEADING_CORR_MAX_INNOV_RAD) {
            return;
        }

        // Clamp to prevent a single jittery WiFi fix from causing a large heading jump
        double clampedError = Math.max(-HEADING_CORR_MAX_RAD,
                Math.min(HEADING_CORR_MAX_RAD, headingError));

        // EMA update of the heading offset
        if (!headingOffsetCalibrated) {
            // First correction: apply strongly
            headingOffsetRad = clampedError;
            headingOffsetCalibrated = true;
        } else {
            headingOffsetRad = headingOffsetRad + HEADING_CORR_GAIN * wrapAngle(clampedError - headingOffsetRad);
        }

        // Reset accumulators for next interval
        lastWifiEN = new double[]{wifiEast, wifiNorth};
        pdrDxSinceWifi = 0.0;
        pdrDySinceWifi = 0.0;
        stepsSinceWifiHeadingFix = 0;
    }

    /**
     * Feeds a GNSS absolute position fix into the fusion engine.
     *
     * <p>Outlier rejection is applied before the fix reaches the EKF:
     * <ul>
     *   <li>During user-anchor mode (first {@value #USER_ANCHOR_DURATION_MS} ms after
     *       {@link #setUserAnchor} is called), fixes further than
     *       {@value #USER_ANCHOR_GNSS_OUTLIER_M} m from the current estimate are rejected.</li>
     *   <li>Otherwise, fixes further than {@value #GNSS_OUTLIER_REJECT_DIST_M} m are rejected.</li>
     * </ul>
     * The observation score passed to the EKF is derived from accuracy via an exponential
     * decay function, then clamped to [{@value #GNSS_MIN_SCORE}, {@value #GNSS_MAX_SCORE}]
     * to prevent GNSS from overriding a good PDR trajectory when indoors.
     *
     * @param latDeg   measured latitude in decimal degrees.
     * @param lngDeg   measured longitude in decimal degrees.
     * @param accuracy horizontal accuracy of the fix in metres as reported by the OS.
     */
    public void updateWithGNSS(double latDeg, double lngDeg, float accuracy) {
        if (!initialized) return;

        LatLng current = cascadedFusionManager.getEstimatedLatLng();
        if (current != null) {
            double jumpM = distanceMeters(current.latitude, current.longitude, latDeg, lngDeg);

            // During user-anchor mode use a much tighter outlier threshold so that the
            // indoor GPS (biased toward the nearest window/exit) cannot drag the trajectory
            // back to its (wrong) position after the user has manually placed themselves.
            boolean inAnchorMode = userAnchorStartMs > 0
                    && (System.currentTimeMillis() - userAnchorStartMs) < USER_ANCHOR_DURATION_MS;
            double rejectDist = inAnchorMode ? USER_ANCHOR_GNSS_OUTLIER_M : GNSS_OUTLIER_REJECT_DIST_M;

            if (jumpM > rejectDist) {
                Log.w(TAG, String.format("GNSS rejected: %.1fm > %.1fm (anchor=%b)",
                        jumpM, rejectDist, inAnchorMode));
                return;
            }
        }

        double safeAcc = Math.max(0.0f, accuracy);
        double gnssScore = Math.exp(-safeAcc / 12.0);
    gnssScore = Math.max(GNSS_MIN_SCORE, Math.min(GNSS_MAX_SCORE, gnssScore));
        cascadedFusionManager.onAbsoluteObservationLatLng(latDeg, lngDeg, gnssScore);

        lastEstimate = cascadedFusionManager.getEstimatedLatLng();
        lastSource = PositionSource.GNSS;
    }

    /**
     * Feeds a WiFi API absolute position estimate into the fusion engine.
     *
     * <p>Fixes that jump more than {@value #WIFI_OUTLIER_REJECT_DIST_M} m from the current
     * EKF estimate are discarded as outliers (typical indoor WiFi API multipath artefacts).
     * Accepted fixes are also used to infer heading correction via
     * {@link #inferHeadingFromWifi}, then forwarded to the EKF with a fixed observation
     * score of {@value #WIFI_OBSERVATION_SCORE}.
     *
     * @param latDeg estimated latitude from the WiFi positioning API, in decimal degrees.
     * @param lngDeg estimated longitude from the WiFi positioning API, in decimal degrees.
     */
    public void updateWithWifi(double latDeg, double lngDeg) {
        if (!initialized || !cascadedFusionManager.isInitialized()) return;

        LatLng current = cascadedFusionManager.getEstimatedLatLng();
        if (current != null) {
            double jumpM = distanceMeters(current.latitude, current.longitude, latDeg, lngDeg);
            if (jumpM > WIFI_OUTLIER_REJECT_DIST_M) {
                Log.w(TAG, String.format("WiFi API outlier rejected: %.1fm jump", jumpM));
                return;
            }
        }

        // Infer heading correction from WiFi displacement before updating position
        CoordinateConverter conv = cascadedFusionManager.getConverter();
        if (conv != null) {
            double[] en = conv.toEastNorth(latDeg, lngDeg);
            inferHeadingFromWifi(en[0], en[1]);
        }

        double wifiScore = WIFI_OBSERVATION_SCORE;
        cascadedFusionManager.onAbsoluteObservationLatLng(latDeg, lngDeg, wifiScore);
        lastEstimate = cascadedFusionManager.getEstimatedLatLng();
        lastSource = PositionSource.WIFI;
    }

    /**
     * Processes a raw WiFi RSSI scan for fingerprint-based positioning.
     *
     * <p>The scan is first matched against the existing fingerprint database using the
     * WKNN predictor. If the match confidence (score) is at least 0.08, the resulting
     * position estimate is accepted and the last source is updated to {@code WIFI}.
     * The scan is then passed to {@link #maybeAddWifiFingerprint} to grow the radio map,
     * subject to spatial and temporal gating to avoid near-duplicate fingerprints.
     *
     * @param currentScan map of WiFi BSSID (access point MAC address) to RSSI value in dBm
     *                    for all access points detected in this scan.
     */
    public void updateWithWifiScan(Map<String, Integer> currentScan) {
        if (!initialized || !cascadedFusionManager.isInitialized() || currentScan == null || currentScan.isEmpty()) {
            return;
        }

        // Predict first using existing fingerprint DB.
        // Previously we inserted currentScan before prediction, which made WKNN
        // self-match the just-added sample and produced little/no corrective effect.
        WknnPredictor.WknnResult result = cascadedFusionManager.onWifiScanned(currentScan);
        if (result != null && result.score >= 0.08) {
            lastSource = PositionSource.WIFI;
            lastEstimate = cascadedFusionManager.getEstimatedLatLng();
        }

        // Then update fingerprint DB for future scans, but only with spatial/temporal gating
        // to build a useful radio-map instead of near-duplicate clusters.
        maybeAddWifiFingerprint(currentScan);
    }

    /**
     * Conditionally adds the current WiFi scan to the fingerprint database.
     *
     * <p>A fingerprint is only added when both spatial and temporal gates are satisfied:
     * <ul>
     *   <li>At least {@value #MIN_WIFI_FINGERPRINT_APS} access points are visible (sparse
     *       scans are too noisy to be useful reference points).</li>
     *   <li>At least {@value #MIN_WIFI_FINGERPRINT_INTERVAL_MS} ms have elapsed since the
     *       last fingerprint was added.</li>
     *   <li>The current EKF position is at least {@value #MIN_WIFI_FINGERPRINT_SPACING_M} m
     *       from the position of the last fingerprint (ensures spatial diversity).</li>
     * </ul>
     *
     * @param currentScan map of BSSID to RSSI (dBm) for the current WiFi scan.
     */
    private void maybeAddWifiFingerprint(Map<String, Integer> currentScan) {
        if (currentScan.size() < MIN_WIFI_FINGERPRINT_APS) {
            return;
        }

        double ekfX = cascadedFusionManager.getEkfX();
        double ekfY = cascadedFusionManager.getEkfY();
        long nowMs = System.currentTimeMillis();

        boolean intervalOk = (nowMs - lastFingerprintAddMs) >= MIN_WIFI_FINGERPRINT_INTERVAL_MS;
        boolean spacingOk = lastFingerprintEN == null
                || Math.hypot(ekfX - lastFingerprintEN[0], ekfY - lastFingerprintEN[1]) >= MIN_WIFI_FINGERPRINT_SPACING_M;

        if (!intervalOk || !spacingOk) {
            return;
        }

        cascadedFusionManager.addFingerprintFromCurrentEstimate(currentScan);
        lastFingerprintEN = new double[]{ekfX, ekfY};
        lastFingerprintAddMs = nowMs;
    }

    /**
     * Feeds a barometric altitude reading into the floor-change detector.
     *
     * <p>Altitude changes are accumulated incrementally. When the accumulated change exceeds
     * 3.0 m, a floor transition is inferred by dividing by the assumed inter-floor height
     * (3.5 m) and rounding to the nearest integer. The accumulator is then reset.
     *
     * @param altitudeM barometric altitude in metres above sea level, as derived from
     *                  the device pressure sensor using the standard atmosphere model.
     */
    public void updateBarometer(float altitudeM) {
        if (!initialized) return;

        if (Float.isNaN(lastFloorAltitude)) {
            lastFloorAltitude = altitudeM;
            return;
        }

        accumulatedHeightChange += (altitudeM - lastFloorAltitude);
        lastFloorAltitude = altitudeM;

        if (Math.abs(accumulatedHeightChange) >= 3.0f) {
            int floorDelta = Math.round(accumulatedHeightChange / 3.5f);
            if (floorDelta != 0) {
                currentFloor += floorDelta;
            }
            accumulatedHeightChange = 0f;
        }
    }

    /**
     * Returns the current best-estimate position from the fusion engine.
     * Prefers the live {@link CascadedFusionManager} estimate when available;
     * falls back to the last cached estimate otherwise.
     *
     * @return current estimated {@link LatLng}, or {@code null} if the manager has not
     *         been initialised yet.
     */
    public LatLng getEstimatedPosition() {
        if (cascadedFusionManager.isInitialized()) {
            return cascadedFusionManager.getEstimatedLatLng();
        }
        return lastEstimate;
    }

    /**
     * Returns the current estimated floor number.
     * Updated by {@link #updateBarometer} when a significant altitude change is detected,
     * and also set explicitly by {@link #initialize} and {@link #loadNucleusMap}.
     *
     * @return floor number, where 0 represents the ground level.
     */
    public int getCurrentFloor() {
        return currentFloor;
    }

    /**
     * Returns the positioning source that produced the most recent estimate update.
     *
     * @return one of {@link PositionSource#PDR}, {@link PositionSource#GNSS},
     *         {@link PositionSource#WIFI}, or {@link PositionSource#FUSED}.
     */
    public PositionSource getLastSource() { return lastSource; }

    /**
     * Returns whether both this manager and the underlying {@link CascadedFusionManager}
     * have been successfully initialised and are ready to produce position estimates.
     *
     * @return {@code true} if {@link #initialize} has been called and the cascaded manager
     *         is also ready; {@code false} otherwise.
     */
    public boolean isInitialized() { return initialized && cascadedFusionManager.isInitialized(); }

    /**
     * Resets the fusion engine to its uninitialised state.
     * Clears all accumulated position estimates, heading corrections, WiFi fingerprint
     * state, and floor tracking. {@link #initialize} must be called again before further
     * update methods are invoked.
     */
    public void reset() {
        cascadedFusionManager.reset();
        lastFloorAltitude = Float.NaN;
        accumulatedHeightChange = 0f;
        currentFloor = 0;
        initialized = false;
        lastEstimate = null;
        lastPdrX = 0f;
        lastPdrY = 0f;
        lastHeadingRad = Double.NaN;
        firstStepHeadingAligned = false;
        headingOffsetCalibrated = false;
        headingOffsetRad = 0.0;
        lastSource = PositionSource.PDR;
        userAnchorStartMs = 0;
        lastWifiEN = null;
        pdrDxSinceWifi = 0.0;
        pdrDySinceWifi = 0.0;
        stepsSinceWifiHeadingFix = 0;
        lastFingerprintEN = null;
        lastFingerprintAddMs = 0L;
    }

    /**
     * Computes the great-circle distance between two geographic coordinates using the
     * Haversine formula.
     *
     * @param lat1 latitude of the first point in decimal degrees.
     * @param lon1 longitude of the first point in decimal degrees.
     * @param lat2 latitude of the second point in decimal degrees.
     * @param lon2 longitude of the second point in decimal degrees.
     * @return distance between the two points in metres.
     */
    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2.0) * Math.sin(dPhi / 2.0)
                + Math.cos(phi1) * Math.cos(phi2)
                * Math.sin(dLambda / 2.0) * Math.sin(dLambda / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return r * c;
    }

    /**
     * Wraps an angle in radians to the range [-π, π].
     *
     * @param angle angle in radians, any value.
     * @return equivalent angle normalised to [-π, π].
     */
    private static double wrapAngle(double angle) {
        while (angle > Math.PI) angle -= 2.0 * Math.PI;
        while (angle < -Math.PI) angle += 2.0 * Math.PI;
        return angle;
    }
}
