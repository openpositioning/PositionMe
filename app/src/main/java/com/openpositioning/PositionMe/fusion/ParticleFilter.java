package com.openpositioning.PositionMe.fusion;

import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * SIR (Sequential Importance Resampling) particle filter for indoor positioning.
 *
 * <p>Internally operates in a local East-North coordinate system (metres) centred
 * on the initialisation point. WGS84 ↔ local conversion uses a flat-Earth
 * approximation, accurate enough for building-scale distances.</p>
 *
 * <p>Fuses PDR dead-reckoning with WiFi and GNSS measurements.
 * Map-matching projects wall-crossing particles onto the nearest wall segment
 * and applies a weight penalty.</p>
 */
public class ParticleFilter {

    // ---- Tuning constants ----
    private static final int    N                    = 500;
    private static final double INIT_SPREAD_M        = 5.0;
    private static final double PDR_NOISE_SIGMA_M    = 0.3;
    private static final double PDR_STEP_NOISE_RATIO = 0.05;
    private static final double HEADING_NOISE_RAD    = Math.toRadians(15.0);
    private static final double WIFI_SIGMA_M         = 5.0;
    private static final double GNSS_SIGMA_DEFAULT_M = 8.0;
    // Outlier rejection
    private static final double WIFI_OUTLIER_M       = 50.0;
    private static final double GNSS_OUTLIER_M       = 40.0;
    // Map-matching constants
    private static final double WALL_WEIGHT_FACTOR   = 0.01;

    // ---- Local coordinate origin (WGS84) ----
    private double originLat;
    private double originLng;
    private double metersPerDegLat;
    private double metersPerDegLng;

    // ---- Particle arrays (easting/northing in metres) ----
    private double[] east    = new double[N];  // easting  (x, positive = East)
    private double[] north   = new double[N];  // northing (y, positive = North)
    private double[] weight  = new double[N];
    private double[] pHeading = new double[N];

    private final Random rng = new Random();
    private LatLng estimate;
    private boolean initialized = false;

    // Wall segments in local coords: each entry = {e1, n1, e2, n2}
    private List<double[]> wallSegments = new ArrayList<>();

    // =========================================================================
    // Coordinate conversion
    // =========================================================================

    private void setOrigin(double lat, double lng) {
        originLat = lat;
        originLng = lng;
        metersPerDegLat = 111320.0;
        metersPerDegLng = 111320.0 * Math.cos(Math.toRadians(lat));
    }

    private double toEasting(double lng)  { return (lng - originLng) * metersPerDegLng; }
    private double toNorthing(double lat) { return (lat - originLat) * metersPerDegLat; }
    private double toLat(double northing) { return originLat + northing / metersPerDegLat; }
    private double toLng(double easting)  { return originLng + easting  / metersPerDegLng; }

    private LatLng toLatLng(double easting, double northing) {
        return new LatLng(toLat(northing), toLng(easting));
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /** Scatters N particles in a Gaussian cloud around {@code startPos}. */
    public void initialize(LatLng startPos) {
        setOrigin(startPos.latitude, startPos.longitude);

        for (int i = 0; i < N; i++) {
            east[i]     = rng.nextGaussian() * INIT_SPREAD_M;
            north[i]    = rng.nextGaussian() * INIT_SPREAD_M;
            weight[i]   = 1.0 / N;
            pHeading[i] = rng.nextDouble() * 2 * Math.PI - Math.PI;
        }
        estimate    = startPos;
        initialized = true;
    }

    public boolean isInitialized() { return initialized; }

    /**
     * Re-scatters particles around a new position (Meld's newKNNEstimate style).
     * Used when a better position fix arrives after initial init.
     * Preserves the existing origin for coordinate consistency.
     */
    public void reinitializeAround(LatLng pos, double radiusM) {
        if (!initialized) { initialize(pos); return; }
        double cE = toEasting(pos.longitude);
        double cN = toNorthing(pos.latitude);
        for (int i = 0; i < N; i++) {
            // Uniform distribution in circle (Meld's randomPointCircle)
            double r = radiusM * Math.sqrt(rng.nextDouble());
            double angle = rng.nextDouble() * 2 * Math.PI;
            east[i]     = cE + r * Math.cos(angle);
            north[i]    = cN + r * Math.sin(angle);
            weight[i]   = 1.0 / N;
            pHeading[i] = rng.nextDouble() * 2 * Math.PI - Math.PI;
        }
        estimate = pos;
    }

    /**
     * Replaces the wall-segment list. Input is in WGS84 {lat1, lng1, lat2, lng2};
     * internally converted to local easting/northing metres.
     */
    public void setWallSegments(List<double[]> segmentsLatLng) {
        wallSegments = new ArrayList<>();
        if (segmentsLatLng == null) return;
        for (double[] s : segmentsLatLng) {
            wallSegments.add(new double[]{
                    toEasting(s[1]),  toNorthing(s[0]),
                    toEasting(s[3]),  toNorthing(s[2])
            });
        }
    }

    /**
     * Predict step: propagates each particle by PDR displacement (metres).
     *
     * @param dxMeters East  displacement this tick (metres)
     * @param dyMeters North displacement this tick (metres)
     */
    public void predict(double dxMeters, double dyMeters) {
        if (!initialized) return;

        double stepLen = Math.sqrt(dxMeters * dxMeters + dyMeters * dyMeters);
        if (stepLen < 0.01) return;

        double pdrHeading = Math.atan2(dyMeters, dxMeters);

        for (int i = 0; i < N; i++) {
            double oldE = east[i];
            double oldN = north[i];

            // Two-component step noise
            double stepNoise = PDR_NOISE_SIGMA_M + stepLen * PDR_STEP_NOISE_RATIO;
            double noisyStep = Math.max(0, stepLen + rng.nextGaussian() * stepNoise);

            // Per-particle heading with noise
            pHeading[i] = pdrHeading + rng.nextGaussian() * HEADING_NOISE_RAD;

            double newE = oldE + noisyStep * Math.cos(pHeading[i]);
            double newN = oldN + noisyStep * Math.sin(pHeading[i]);

            // Map matching: wall crossing → project + penalise
            if (!wallSegments.isEmpty() && crossesWall(oldE, oldN, newE, newN)) {
                double[] proj = projectOntoNearestWall(newE, newN);
                if (proj != null) {
                    newE = proj[0];
                    newN = proj[1];
                }
                weight[i] *= WALL_WEIGHT_FACTOR;
            }

            // Travel distance penalty
            if (stepLen > 1e-6) {
                double travelDist = Math.sqrt((newE - oldE) * (newE - oldE)
                                            + (newN - oldN) * (newN - oldN));
                weight[i] *= Math.max(Math.min(travelDist / stepLen, 1.0), 0.01);
            }

            east[i]  = newE;
            north[i] = newN;
        }

        normalizeWeights();
        if (effectiveSampleSize() < N / 2.0) resample();
        estimate = computeWeightedMean();
    }

    /** Update with WiFi fix. Skipped if measurement is across a wall from estimate. */
    public void updateWithWifi(LatLng wifiPos) {
        if (!initialized) return;
        if (estimate != null && distanceMeters(estimate, wifiPos) > WIFI_OUTLIER_M) return;
        if (isMeasurementBlockedByWall(wifiPos)) return;
        applyMeasurement(toEasting(wifiPos.longitude), toNorthing(wifiPos.latitude), WIFI_SIGMA_M);
    }

    /** Update with GNSS fix. Skipped if measurement is across a wall from estimate. */
    public void updateWithGNSS(LatLng gnssPos, double accuracyM) {
        if (!initialized) return;
        if (estimate != null && distanceMeters(estimate, gnssPos) > GNSS_OUTLIER_M) return;
        if (isMeasurementBlockedByWall(gnssPos)) return;
        double sigma = (accuracyM > 0) ? accuracyM : GNSS_SIGMA_DEFAULT_M;
        applyMeasurement(toEasting(gnssPos.longitude), toNorthing(gnssPos.latitude), sigma);
    }

    public void updateWithGNSS(LatLng gnssPos) {
        updateWithGNSS(gnssPos, GNSS_SIGMA_DEFAULT_M);
    }

    /** Returns the weighted-mean position estimate as WGS84 LatLng. */
    public LatLng getEstimate() { return estimate; }

    /** Returns the highest-weight particle position. */
    public LatLng getBestPosition() {
        int best = 0;
        for (int i = 1; i < N; i++) {
            if (weight[i] > weight[best]) best = i;
        }
        return toLatLng(east[best], north[best]);
    }

    /** Returns particle snapshot as [lat, lng, weight] for visualisation. */
    public List<double[]> getParticleSnapshot() {
        List<double[]> snap = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            snap.add(new double[]{toLat(north[i]), toLng(east[i]), weight[i]});
        }
        return snap;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void applyMeasurement(double measE, double measN, double sigmaM) {
        double sig2 = sigmaM * sigmaM;

        for (int i = 0; i < N; i++) {
            double dE = east[i] - measE;
            double dN = north[i] - measN;
            weight[i] *= Math.exp(-0.5 * (dE * dE + dN * dN) / sig2);
        }
        normalizeWeights();
        if (effectiveSampleSize() < N / 2.0) resample();
        estimate = computeWeightedMean();
    }

    private LatLng computeWeightedMean() {
        double sE = 0, sN = 0;
        for (int i = 0; i < N; i++) {
            sE += weight[i] * east[i];
            sN += weight[i] * north[i];
        }

        // Restrict: if new mean crossed a wall from previous estimate, snap back
        if (!wallSegments.isEmpty() && estimate != null) {
            double prevE = toEasting(estimate.longitude);
            double prevN = toNorthing(estimate.latitude);
            if (crossesWall(prevE, prevN, sE, sN)) {
                double[] proj = projectOntoNearestWall(sE, sN);
                if (proj != null) {
                    sE = proj[0];
                    sN = proj[1];
                }
            }
        }

        return toLatLng(sE, sN);
    }

    private void normalizeWeights() {
        double sum = 0;
        for (int i = 0; i < N; i++) sum += weight[i];
        if (sum <= 0) {
            for (int i = 0; i < N; i++) weight[i] = 1.0 / N;
        } else {
            for (int i = 0; i < N; i++) weight[i] /= sum;
        }
    }

    private double effectiveSampleSize() {
        double sumSq = 0;
        for (int i = 0; i < N; i++) sumSq += weight[i] * weight[i];
        return sumSq <= 0 ? 0 : 1.0 / sumSq;
    }

    /** Residual resampling. */
    private void resample() {
        double[] nE    = new double[N];
        double[] nN    = new double[N];
        double[] nHead = new double[N];

        int idx = 0;
        double[] residual = new double[N];
        for (int i = 0; i < N; i++) {
            int copies = (int) Math.floor(weight[i] * N);
            residual[i] = weight[i] * N - copies;
            for (int c = 0; c < copies && idx < N; c++) {
                nE[idx]    = east[i];
                nN[idx]    = north[i];
                nHead[idx] = pHeading[i];
                idx++;
            }
        }

        double resSum = 0;
        for (int i = 0; i < N; i++) resSum += residual[i];
        if (resSum > 0) for (int i = 0; i < N; i++) residual[i] /= resSum;

        double[] cdf = new double[N];
        cdf[0] = residual[0];
        for (int i = 1; i < N; i++) cdf[i] = cdf[i - 1] + residual[i];
        cdf[N - 1] = 1.0;

        while (idx < N) {
            double u = rng.nextDouble();
            int j = 0;
            while (j < N - 1 && cdf[j] < u) j++;
            nE[idx]    = east[j];
            nN[idx]    = north[j];
            nHead[idx] = pHeading[j];
            idx++;
        }

        east     = nE;
        north    = nN;
        pHeading = nHead;
        for (int i = 0; i < N; i++) weight[i] = 1.0 / N;
    }

    /**
     * Returns true if the path from current estimate to the measurement crosses any wall.
     * When true, the measurement should be skipped entirely to prevent wall-crossing pulls.
     */
    private boolean isMeasurementBlockedByWall(LatLng measPos) {
        if (estimate == null || wallSegments.isEmpty()) return false;
        double estE = toEasting(estimate.longitude);
        double estN = toNorthing(estimate.latitude);
        double measE = toEasting(measPos.longitude);
        double measN = toNorthing(measPos.latitude);
        return crossesWall(estE, estN, measE, measN);
    }

    // ---- Map-matching helpers (all in local easting/northing metres) ----

    private double[] projectOntoNearestWall(double pE, double pN) {
        if (wallSegments.isEmpty()) return null;
        double bestDistSq = Double.MAX_VALUE;
        double bestE = pE, bestN = pN;

        for (double[] seg : wallSegments) {
            double aE = seg[0], aN = seg[1], bE = seg[2], bN = seg[3];
            double abE = bE - aE, abN = bN - aN;
            double apE = pE - aE, apN = pN - aN;

            double lenSq = abE * abE + abN * abN;
            if (lenSq < 1e-10) continue;
            double t = Math.max(0, Math.min(1, (apE * abE + apN * abN) / lenSq));

            double projE = aE + t * abE;
            double projN = aN + t * abN;
            double dE = pE - projE, dN = pN - projN;
            double distSq = dE * dE + dN * dN;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestE = projE;
                bestN = projN;
            }
        }
        return new double[]{bestE, bestN};
    }

    private boolean crossesWall(double e1, double n1, double e2, double n2) {
        for (double[] seg : wallSegments) {
            if (segmentsIntersect(e1, n1, e2, n2, seg[0], seg[1], seg[2], seg[3]))
                return true;
        }
        return false;
    }

    private boolean segmentsIntersect(double ax, double ay, double bx, double by,
                                      double cx, double cy, double dx, double dy) {
        double d1 = cross(cx, cy, dx, dy, ax, ay);
        double d2 = cross(cx, cy, dx, dy, bx, by);
        double d3 = cross(ax, ay, bx, by, cx, cy);
        double d4 = cross(ax, ay, bx, by, dx, dy);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
            && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private double cross(double p1x, double p1y, double p2x, double p2y,
                         double p3x, double p3y) {
        return (p2x - p1x) * (p3y - p1y) - (p2y - p1y) * (p3x - p1x);
    }

    /** Approximate distance in meters between two WGS84 points. */
    private static double distanceMeters(LatLng a, LatLng b) {
        double dLat = (a.latitude - b.latitude) * 111320.0;
        double dLng = (a.longitude - b.longitude) * 111320.0
                * Math.cos(Math.toRadians((a.latitude + b.latitude) / 2));
        return Math.sqrt(dLat * dLat + dLng * dLng);
    }
}
