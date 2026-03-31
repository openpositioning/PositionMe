package com.openpositioning.PositionMe.utils;

import android.util.Log;

import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cascaded fusion scheduler: DynamicEKF + MapConstrainedPF + WKNN.
 */
public class CascadedFusionManager {

    private static final String TAG = "Fusion";

    // Only accept WKNN predictions when enough confidence is achieved.
    // 0.25 was too strict with real indoor RSSI variability and often prevented
    // WiFi corrections from ever being applied.
    private static final double LOW_SCORE_THRESHOLD = 0.12;
    private static final double BALANCED_OBSERVATION_SCORE = 0.30;
    // Maximum distance a WiFi/WKNN fix may be from the current EKF estimate before it is
    // rejected as an outlier.  Tightened from 8 m to 4 m: anything farther is almost
    // certainly a fingerprint database mismatch, not actual movement.
    private static final double WIFI_OUTLIER_REJECT_DIST_M = 4.0;
    private static final double RESET_DIFF_THRESHOLD_M = 5.0;
    private static final double RESET_PF_VAR_THRESHOLD = 2.0;
    private static final double WALL_MODE_RESET_DIFF_THRESHOLD_M = 1.2;
    private static final double WALL_MODE_MAX_ACCEPTABLE_PF_VAR = 8.0;
    private static final double PF_BLEND_CONFIDENCE_VAR = 6.0;
    private static final double MAP_SNAP_MAX_WALL_DIST_M = 3.0;
    private static final double MAP_SNAP_MAX_HEADING_DIFF_RAD = Math.toRadians(35.0);
    private static final double MAP_SNAP_GAIN = 0.75;
    private static final int MAX_FINGERPRINTS = 3000;
    private static final double ABS_RELOCALIZE_DIST_M = 6.0;
    private static final double ABS_RELOCALIZE_MIN_SCORE = 0.40;
    private static final int ABS_RELOCALIZE_REQUIRED_STREAK = 2;
    private static final double ABS_RELOCALIZE_STRONG_SCORE = 0.80;
    private static final double ABS_RELOCALIZE_STRONG_DIST_M = 2.0;

    private CoordinateConverter converter;
    private DynamicEKF ekf;
    private MapConstrainedPF pf;
    private WknnPredictor wknn;

    private List<MapConstrainedPF.Wall> walls = new ArrayList<>();
    private boolean wallConstraintEnabled = false;
    private boolean initialized = false;
    private int farAbsoluteObsStreak = 0;

    // Output-stage wall correction: applied to fused position before converting to LatLng.
    // Does not touch EKF/PF internals.
    private MapMatcher outputMapMatcher = null;
    private double lastFusedX = 0.0;
    private double lastFusedY = 0.0;

    public void initialize(double latDeg, double lngDeg, double headingRad) {
        converter = new CoordinateConverter(latDeg, lngDeg);
        ekf = new DynamicEKF(0.0, 0.0, headingRad);
        pf = new MapConstrainedPF(walls);
        pf.reset(0.0, 0.0, headingRad);
        wknn = new WknnPredictor(4, 1e-6, 0.05, -100);
        farAbsoluteObsStreak = 0;
        lastFusedX = 0.0;
        lastFusedY = 0.0;
        initialized = true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void reset() {
        initialized = false;
        converter = null;
        ekf = null;
        pf = null;
        wknn = null;
        farAbsoluteObsStreak = 0;
        lastFusedX = 0.0;
        lastFusedY = 0.0;
    }

    public void setWalls(List<MapConstrainedPF.Wall> wallList) {
        walls = (wallList == null) ? new ArrayList<>() : new ArrayList<>(wallList);
        wallConstraintEnabled = !walls.isEmpty();
        if (pf != null) {
            pf.setWalls(walls);
        }
        // Rebuild output-stage MapMatcher so correctPosition() has the latest walls.
        if (!walls.isEmpty()) {
            outputMapMatcher = new MapMatcher();
            for (MapConstrainedPF.Wall w : walls) {
                outputMapMatcher.addFeature(new MapMatcher.MapFeature(
                        MapMatcher.FeatureType.WALL, w.x1, w.y1, w.x2, w.y2, "wall"));
            }
        } else {
            outputMapMatcher = null;
        }
    }

    public void onStepDetected(double stepLen, double deltaTheta) {
        if (!initialized) {
            return;
        }
        if (wallConstraintEnabled) {
            double currentTheta = ekf.getTheta();
            double desiredHeading = wrapAngle(currentTheta + deltaTheta);
            double snappedHeading = snapHeadingToMapAxis(desiredHeading, ekf.getX(), ekf.getY());
            deltaTheta = wrapAngle(snappedHeading - currentTheta);
        }
        ekf.predict(stepLen, deltaTheta);
        pf.predict(stepLen, deltaTheta);
        maybeResetEkfFromPf();
    }

    public void alignHeading(double headingRad) {
        if (!initialized) {
            return;
        }
        ekf.setHeading(headingRad);
        pf.setHeading(headingRad);
    }

    public WknnPredictor.WknnResult onWifiScanned(Map<String, Integer> currentScan) {
        if (!initialized || wknn == null) {
            return null;
        }

        WknnPredictor.WknnResult result = wknn.predictPosition(currentScan);
        if (result == null) {
            return null;
        }

        if (result.score < LOW_SCORE_THRESHOLD) {
            return result;
        }

        // Outlier rejection: ignore WKNN fix that is too far from current estimate.
        // A large jump means the scan matched a fingerprint from a different location.
        double dist = Math.hypot(result.x - ekf.getX(), result.y - ekf.getY());
        if (dist > WIFI_OUTLIER_REJECT_DIST_M) {
            Log.w(TAG, String.format("WiFi WKNN outlier rejected: %.1fm from EKF estimate", dist));
            return result;
        }

        // Use the actual WKNN match score rather than a fixed constant so that
        // poor matches are trusted less and the EKF covariance scales accordingly.
        ekf.updateWithDynamicR(result.x, result.y, result.score);
        pf.update(result.x, result.y, result.score);
        maybeResetEkfFromPf();
        return result;
    }

    public void onAbsoluteObservationLatLng(double lat, double lng, double score) {
        if (!initialized || converter == null) {
            return;
        }
        double[] en = converter.toEastNorth(lat, lng);
        onAbsoluteObservationEn(en[0], en[1], score);
    }

    public void onAbsoluteObservationEn(double x, double y, double score) {
        if (!initialized) {
            return;
        }
        double priorDiff = Math.hypot(x - ekf.getX(), y - ekf.getY());
        double useScore = Math.max(LOW_SCORE_THRESHOLD,
            Math.min(1.0, Double.isFinite(score) ? score : BALANCED_OBSERVATION_SCORE));

        // Strong trusted observation (e.g. cliff-phase WiFi) can trigger immediate relocalization
        // to remove the last corridor-length lag near exits.
        if (useScore >= ABS_RELOCALIZE_STRONG_SCORE && priorDiff >= ABS_RELOCALIZE_STRONG_DIST_M) {
            ekf.resetState(x, y);
            pf.relocalize(x, y, ekf.getTheta());
            farAbsoluteObsStreak = 0;
            Log.w(TAG, String.format("Immediate relocalize by strong obs: diff=%.1fm score=%.2f", priorDiff, useScore));
            maybeResetEkfFromPf();
            return;
        }

        ekf.updateWithDynamicR(x, y, useScore);
        pf.update(x, y, useScore);

        // Exit-release: when trusted absolute observations repeatedly disagree
        // with the local EKF/PF state by a large distance, force relocalize.
        // This prevents "still inside building" lag when the user has already
        // reached the entrance/outdoor area.
        if (useScore >= ABS_RELOCALIZE_MIN_SCORE && priorDiff >= ABS_RELOCALIZE_DIST_M) {
            farAbsoluteObsStreak++;
        } else {
            farAbsoluteObsStreak = 0;
        }

        if (farAbsoluteObsStreak >= ABS_RELOCALIZE_REQUIRED_STREAK) {
            ekf.resetState(x, y);
            pf.relocalize(x, y, ekf.getTheta());
            farAbsoluteObsStreak = 0;
            Log.w(TAG, String.format("Relocalized by absolute obs: diff=%.1fm score=%.2f", priorDiff, useScore));
        }

        maybeResetEkfFromPf();
    }

    public void addFingerprintFromCurrentEstimate(Map<String, Integer> scan) {
        if (!initialized || wknn == null || scan == null || scan.isEmpty()) {
            return;
        }
        if (wknn.size() >= MAX_FINGERPRINTS) {
            return;
        }
        wknn.addFingerprint(new WknnPredictor.Fingerprint(ekf.getX(), ekf.getY(), scan));
    }

    public LatLng getEstimatedLatLng() {
        if (!initialized || converter == null) {
            return null;
        }

        double pfVar = pf.getConfidence();
        double fusedX;
        double fusedY;

        if (wallConstraintEnabled) {
            if (Double.isFinite(pfVar) && pfVar <= WALL_MODE_MAX_ACCEPTABLE_PF_VAR) {
                // Use a stronger PF blend when PF confidence is high to reduce EKF lag.
                double pfWeight = (pfVar <= PF_BLEND_CONFIDENCE_VAR) ? 0.40 : 0.25;
                fusedX = (1.0 - pfWeight) * ekf.getX() + pfWeight * pf.getX();
                fusedY = (1.0 - pfWeight) * ekf.getY() + pfWeight * pf.getY();
            } else {
                fusedX = ekf.getX();
                fusedY = ekf.getY();
            }
        } else {
            // Even without wall constraints, blend PF when confidence is good
            // so the UI does not trail behind a conservative EKF state.
            if (Double.isFinite(pfVar) && pfVar <= PF_BLEND_CONFIDENCE_VAR) {
                fusedX = 0.7 * ekf.getX() + 0.3 * pf.getX();
                fusedY = 0.7 * ekf.getY() + 0.3 * pf.getY();
            } else {
                fusedX = ekf.getX();
                fusedY = ekf.getY();
            }
        }

        // Output-stage wall correction: slide the fused position back to the legal side
        // of any wall it crossed. EKF/PF internal states are NOT modified.
        if (outputMapMatcher != null) {
            double[] corrected = outputMapMatcher.correctPosition(lastFusedX, lastFusedY, fusedX, fusedY);
            fusedX = corrected[0];
            fusedY = corrected[1];
        }
        lastFusedX = fusedX;
        lastFusedY = fusedY;

        double[] latLng = converter.toLatLng(fusedX, fusedY);
        return new LatLng(latLng[0], latLng[1]);
    }

    public double getEkfX() {
        return initialized ? ekf.getX() : 0.0;
    }

    public double getEkfY() {
        return initialized ? ekf.getY() : 0.0;
    }

    public double getPfX() {
        return initialized ? pf.getX() : 0.0;
    }

    public double getPfY() {
        return initialized ? pf.getY() : 0.0;
    }

    public double getPfConfidence() {
        return initialized ? pf.getConfidence() : Double.POSITIVE_INFINITY;
    }

    public CoordinateConverter getConverter() {
        return converter;
    }

    public double getEkfTheta() {
        return initialized ? ekf.getTheta() : Double.NaN;
    }

    private void maybeResetEkfFromPf() {
        if (!initialized) return;

        double diff = Math.hypot(ekf.getX() - pf.getX(), ekf.getY() - pf.getY());
        double pfVariance = pf.getConfidence();

        if (!Double.isFinite(pfVariance) || pfVariance > WALL_MODE_MAX_ACCEPTABLE_PF_VAR) {
            return;
        }

        // For small differences, apply stronger tether to reduce persistent lag.
        if (diff <= WALL_MODE_RESET_DIFF_THRESHOLD_M) {
            double newX = ekf.getX() * 0.70 + pf.getX() * 0.30;
            double newY = ekf.getY() * 0.70 + pf.getY() * 0.30;
            ekf.setPosition(newX, newY);
            return;
        }

        // For medium divergence, pull EKF gradually instead of waiting for hard reset.
        if (diff <= 4.0) {
            double newX = ekf.getX() * 0.80 + pf.getX() * 0.20;
            double newY = ekf.getY() * 0.80 + pf.getY() * 0.20;
            ekf.setPosition(newX, newY);
            return;
        }

        // Only perform a hard reset / teleport if divergence is totally catastrophic.
        if (diff > 8.0) {
            ekf.resetState(pf.getX(), pf.getY());
            Log.w(TAG, "EKF 穿墙走飞！已被 PF 强行硬重置。");
        }
    }

    private double snapHeadingToMapAxis(double heading, double x, double y) {
        if (walls == null || walls.isEmpty()) {
            return heading;
        }

        double nearestDist = Double.POSITIVE_INFINITY;
        WallNearest nearest = null;
        for (MapConstrainedPF.Wall wall : walls) {
            WallNearest candidate = distanceToSegment(x, y, wall);
            if (candidate.distance < nearestDist) {
                nearestDist = candidate.distance;
                nearest = candidate;
            }
        }

        if (nearest == null || nearestDist > MAP_SNAP_MAX_WALL_DIST_M) {
            return heading;
        }

        double axisHeading = wallHeading(nearest.wall);
        double axisOpposite = wrapAngle(axisHeading + Math.PI);
        double d1 = Math.abs(wrapAngle(axisHeading - heading));
        double d2 = Math.abs(wrapAngle(axisOpposite - heading));
        double target = d1 <= d2 ? axisHeading : axisOpposite;

        double diff = wrapAngle(target - heading);
        if (Math.abs(diff) > MAP_SNAP_MAX_HEADING_DIFF_RAD) {
            return heading;
        }

        return wrapAngle(heading + MAP_SNAP_GAIN * diff);
    }

    private static class WallNearest {
        final MapConstrainedPF.Wall wall;
        final double distance;

        WallNearest(MapConstrainedPF.Wall wall, double distance) {
            this.wall = wall;
            this.distance = distance;
        }
    }

    private WallNearest distanceToSegment(double px, double py, MapConstrainedPF.Wall wall) {
        double vx = wall.x2 - wall.x1;
        double vy = wall.y2 - wall.y1;
        double segLen2 = vx * vx + vy * vy;
        if (segLen2 < 1e-9) {
            double d = Math.hypot(px - wall.x1, py - wall.y1);
            return new WallNearest(wall, d);
        }

        double t = ((px - wall.x1) * vx + (py - wall.y1) * vy) / segLen2;
        t = Math.max(0.0, Math.min(1.0, t));
        double projX = wall.x1 + t * vx;
        double projY = wall.y1 + t * vy;
        double d = Math.hypot(px - projX, py - projY);
        return new WallNearest(wall, d);
    }

    private double wallHeading(MapConstrainedPF.Wall wall) {
        // EN -> heading(0=north, clockwise positive)
        return wrapAngle(Math.atan2(wall.x2 - wall.x1, wall.y2 - wall.y1));
    }

    private double wrapAngle(double angle) {
        while (angle > Math.PI) angle -= 2.0 * Math.PI;
        while (angle < -Math.PI) angle += 2.0 * Math.PI;
        return angle;
    }
}
