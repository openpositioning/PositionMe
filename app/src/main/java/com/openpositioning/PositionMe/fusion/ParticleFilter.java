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
  private static final int PARTICLE_COUNT = 500;
  private static final double INIT_SPREAD_M = 5.0;
  private static final double PDR_NOISE_SIGMA_M = 0.3;
  private static final double PDR_STEP_NOISE_RATIO = 0.05;
  private static final double HEADING_NOISE_RAD = Math.toRadians(15.0);
  private static final double WIFI_SIGMA_M = 5.0;
  private static final double GNSS_SIGMA_DEFAULT_M = 8.0;
  // Outlier rejection
  private static final double WIFI_OUTLIER_M = 50.0;
  private static final double GNSS_OUTLIER_M = 40.0;
  // Map-matching constants
  private static final double WALL_WEIGHT_FACTOR = 0.01;

  // ---- Local coordinate origin (WGS84) ----
  private double originLat;
  private double originLng;
  private double metersPerDegLat;
  private double metersPerDegLng;

  // ---- Particle arrays (easting/northing in metres) ----
  private double[] east = new double[PARTICLE_COUNT];
  private double[] north = new double[PARTICLE_COUNT];
  private double[] weight = new double[PARTICLE_COUNT];
  private double[] pHeading = new double[PARTICLE_COUNT];

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

  /** Scatters PARTICLE_COUNT particles in a Gaussian cloud around {@code startPos}. */
  public void initialize(LatLng startPos) {
    setOrigin(startPos.latitude, startPos.longitude);

    for (int i = 0; i < PARTICLE_COUNT; i++) {
      east[i] = rng.nextGaussian() * INIT_SPREAD_M;
      north[i] = rng.nextGaussian() * INIT_SPREAD_M;
      weight[i] = 1.0 / PARTICLE_COUNT;
      pHeading[i] = rng.nextDouble() * 2 * Math.PI - Math.PI;
    }
    estimate = startPos;
    initialized = true;
  }

  /** Returns {@code true} if the particle filter has been initialized. */
  public boolean isInitialized() { return initialized; }

  /**
   * Re-scatters particles around a new position when a better fix arrives.
   * Used when a better position fix arrives after initial init.
   * Preserves the existing origin for coordinate consistency.
   */
  public void reinitializeAround(LatLng pos, double radiusM) {
    if (!initialized) { initialize(pos); return; }
    double cE = toEasting(pos.longitude);
    double cN = toNorthing(pos.latitude);
    for (int i = 0; i < PARTICLE_COUNT; i++) {
      // Uniform distribution in circle
      double r = radiusM * Math.sqrt(rng.nextDouble());
      double angle = rng.nextDouble() * 2 * Math.PI;
      east[i] = cE + r * Math.cos(angle);
      north[i] = cN + r * Math.sin(angle);
      weight[i] = 1.0 / PARTICLE_COUNT;
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

    for (int i = 0; i < PARTICLE_COUNT; i++) {
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
    if (effectiveSampleSize() < PARTICLE_COUNT / 2.0) resample();
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

  /** Convenience overload that uses the default GNSS sigma ({@value GNSS_SIGMA_DEFAULT_M} m). */
  public void updateWithGNSS(LatLng gnssPos) {
    updateWithGNSS(gnssPos, GNSS_SIGMA_DEFAULT_M);
  }

  /** Returns the weighted-mean position estimate as WGS84 LatLng. */
  public LatLng getEstimate() { return estimate; }

  /** Returns the highest-weight particle position. */
  public LatLng getBestPosition() {
    int best = 0;
    for (int i = 1; i < PARTICLE_COUNT; i++) {
      if (weight[i] > weight[best]) best = i;
    }
    return toLatLng(east[best], north[best]);
  }

  /** Returns particle snapshot as [lat, lng, weight] for visualisation. */
  public List<double[]> getParticleSnapshot() {
    List<double[]> snap = new ArrayList<>(PARTICLE_COUNT);
    for (int i = 0; i < PARTICLE_COUNT; i++) {
      snap.add(new double[]{toLat(north[i]), toLng(east[i]), weight[i]});
    }
    return snap;
  }

  // =========================================================================
  // Private helpers
  // =========================================================================

  private void applyMeasurement(double measE, double measN, double sigmaM) {
    double sig2 = sigmaM * sigmaM;

    for (int i = 0; i < PARTICLE_COUNT; i++) {
      double dE = east[i] - measE;
      double dN = north[i] - measN;
      weight[i] *= Math.exp(-0.5 * (dE * dE + dN * dN) / sig2);
    }
    normalizeWeights();
    if (effectiveSampleSize() < PARTICLE_COUNT / 2.0) resample();
    estimate = computeWeightedMean();
  }

  private LatLng computeWeightedMean() {
    double sE = 0, sN = 0;
    for (int i = 0; i < PARTICLE_COUNT; i++) {
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
    for (int i = 0; i < PARTICLE_COUNT; i++) sum += weight[i];
    if (sum <= 0) {
      for (int i = 0; i < PARTICLE_COUNT; i++) weight[i] = 1.0 / PARTICLE_COUNT;
    } else {
      for (int i = 0; i < PARTICLE_COUNT; i++) weight[i] /= sum;
    }
  }

  private double effectiveSampleSize() {
    double sumSq = 0;
    for (int i = 0; i < PARTICLE_COUNT; i++) sumSq += weight[i] * weight[i];
    return sumSq <= 0 ? 0 : 1.0 / sumSq;
  }

  /** Residual resampling. */
  private void resample() {
    double[] nE = new double[PARTICLE_COUNT];
    double[] nN = new double[PARTICLE_COUNT];
    double[] nHead = new double[PARTICLE_COUNT];

    int idx = 0;
    double[] residual = new double[PARTICLE_COUNT];
    for (int i = 0; i < PARTICLE_COUNT; i++) {
      int copies = (int) Math.floor(weight[i] * PARTICLE_COUNT);
      residual[i] = weight[i] * PARTICLE_COUNT - copies;
      for (int c = 0; c < copies && idx < PARTICLE_COUNT; c++) {
        nE[idx] = east[i];
        nN[idx] = north[i];
        nHead[idx] = pHeading[i];
        idx++;
      }
    }

    double resSum = 0;
    for (int i = 0; i < PARTICLE_COUNT; i++) resSum += residual[i];
    if (resSum > 0) for (int i = 0; i < PARTICLE_COUNT; i++) residual[i] /= resSum;

    double[] cdf = new double[PARTICLE_COUNT];
    cdf[0] = residual[0];
    for (int i = 1; i < PARTICLE_COUNT; i++) cdf[i] = cdf[i - 1] + residual[i];
    cdf[PARTICLE_COUNT - 1] = 1.0;

    while (idx < PARTICLE_COUNT) {
      double u = rng.nextDouble();
      int j = 0;
      while (j < PARTICLE_COUNT - 1 && cdf[j] < u) j++;
      nE[idx] = east[j];
      nN[idx] = north[j];
      nHead[idx] = pHeading[j];
      idx++;
    }

    east = nE;
    north = nN;
    pHeading = nHead;
    for (int i = 0; i < PARTICLE_COUNT; i++) weight[i] = 1.0 / PARTICLE_COUNT;
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

  /**
   * Projects a point onto the nearest wall segment using perpendicular projection.
   * For each wall segment A→B, computes the closest point on the segment to P
   * by clamping the parameter t = dot(AP, AB) / |AB|² to [0, 1].
   *
   * @return the projected point {easting, northing}, or null if no walls loaded
   */
  private double[] projectOntoNearestWall(double pE, double pN) {
    if (wallSegments.isEmpty()) return null;
    double bestDistSq = Double.MAX_VALUE;
    double bestE = pE, bestN = pN;

    for (double[] seg : wallSegments) {
      // Wall endpoint A = (aE, aN), B = (bE, bN)
      double aE = seg[0], aN = seg[1], bE = seg[2], bN = seg[3];
      // Vector AB and vector AP
      double abE = bE - aE, abN = bN - aN;
      double apE = pE - aE, apN = pN - aN;

      // Squared length of segment; skip degenerate (zero-length) segments
      double lenSq = abE * abE + abN * abN;
      if (lenSq < 1e-10) continue;
      // Parameter t ∈ [0,1]: fraction along A→B where projection falls
      double t = Math.max(0, Math.min(1, (apE * abE + apN * abN) / lenSq));

      // Closest point on segment
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

  /** Returns true if the line from (e1,n1) to (e2,n2) crosses any wall segment. */
  private boolean crossesWall(double e1, double n1, double e2, double n2) {
    for (double[] seg : wallSegments) {
      if (segmentsIntersect(e1, n1, e2, n2, seg[0], seg[1], seg[2], seg[3]))
        return true;
    }
    return false;
  }

  /**
   * Tests whether segment AB intersects segment CD using the cross-product method.
   * Two segments intersect iff A and B lie on opposite sides of line CD,
   * AND C and D lie on opposite sides of line AB.
   */
  private boolean segmentsIntersect(double ax, double ay, double bx, double by,
                                      double cx, double cy, double dx, double dy) {
    double d1 = cross(cx, cy, dx, dy, ax, ay);
    double d2 = cross(cx, cy, dx, dy, bx, by);
    double d3 = cross(ax, ay, bx, by, cx, cy);
    double d4 = cross(ax, ay, bx, by, dx, dy);
    // Opposite signs ⇒ points are on opposite sides of the line
    return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
      && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
  }

  /** 2D cross product: (P2−P1) × (P3−P1). Positive if P3 is left of P1→P2. */
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
