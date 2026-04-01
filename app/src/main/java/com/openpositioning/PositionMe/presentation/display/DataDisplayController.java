package com.openpositioning.PositionMe.presentation.display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.presentation.fragment.TrajectoryMapFragment;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.UtilFunctions;

/**
 * Dedicated controller for Assignment 2 live data display.
 *
 * Keeps display updates out of RecordingFragment so rendering stays modular:
 * - renders the current fused estimate
 * - applies optional visual smoothing
 * - stores and plots the last N GNSS / WiFi / PDR observations
 * - appends the fused trajectory periodically or when movement is detected
 */
public class DataDisplayController {

    private static final double DEGREE_IN_METERS = 111111.0;
    private static final long TRAJECTORY_APPEND_INTERVAL_MS = 1000L;
    private static final double TRAJECTORY_APPEND_DISTANCE_METERS = 0.20;
    private static final double TRAJECTORY_APPEND_STATIC_MIN_METERS = 0.04;
    private static final double OBSERVATION_DISTANCE_EPSILON_METERS = 0.6;
    private static final double PDR_OBSERVATION_DISTANCE_METERS = 1.0;
    private static final long FUSED_STALE_TIMEOUT_MS = 4500L;
    private static final double BACKTRACK_REJECTION_MAX_METERS = 0.45;
    private static final double BACKTRACK_COS_THRESHOLD = -0.97;
    private static final double BACKTRACK_REJECTION_MIN_SEGMENT_METERS = 0.55;
    private static final double BACKTRACK_RETURN_TO_PREV_MAX_METERS = 0.25;
    private static final double MAX_RENDER_STEP_METERS = 3.4;
    private static final double STRAIGHT_LINE_MIN_SEGMENT_METERS = 0.70;
    private static final double STRAIGHT_LINE_MAX_CROSSTRACK_METERS = 0.18;
    private static final double STRAIGHT_LINE_MAX_CORRECTION_STEP_METERS = 1.45;
    private static final long CAMERA_UPDATE_INTERVAL_MS = 900L;
    private static final double CAMERA_UPDATE_DISTANCE_METERS = 1.10;
    private static final double MARKER_RENDER_DISTANCE_EPSILON_METERS = 0.08;
    private static final double MARKER_RENDER_HEADING_EPSILON_DEGREES = 5.0;
    private static final double STATIONARY_FREEZE_DISTANCE_METERS = 2.0;
    private static final double STATIONARY_WIFI_OBSERVATION_DISTANCE_METERS = 2.2;
    private static final double STATIONARY_GNSS_OBSERVATION_DISTANCE_METERS = 2.5;

    private final SensorFusion sensorFusion;
    private final TrajectoryMapFragment trajectoryMapFragment;
    private final ExponentialLatLngSmoother smoother;
    private final SensorFusion.DisplaySnapshot displaySnapshot = new SensorFusion.DisplaySnapshot();
    private final LatLngCache wifiLatLngCache = new LatLngCache();
    private final LatLngCache pdrLatLngCache = new LatLngCache();
    private final LatLngCache fusedLatLngCache = new LatLngCache();

    private LatLng lastTrajectoryPoint;
    private long lastTrajectoryAppendTimeMs;

    private LatLng lastGnssObservation;
    private LatLng lastWifiObservation;
    private LatLng lastPdrObservation;
    private long lastWifiObservationFixTimeMs;
    private LatLng lastDrawnMarkerPoint;
    private LatLng lastRenderedPoint;
    private LatLng secondLastRenderedPoint;
    private float lastRenderedOrientationDeg = Float.NaN;
    private LatLng lastCameraPoint;
    private long lastCameraUpdateTimeMs;

    // Connects sensor data with the map fragment display logic.
    public DataDisplayController(SensorFusion sensorFusion, TrajectoryMapFragment trajectoryMapFragment) {
        this.sensorFusion = sensorFusion;
        this.trajectoryMapFragment = trajectoryMapFragment;
        this.smoother = new ExponentialLatLngSmoother(0.78);
    }

    // Clears the saved render state before a new session starts.
    public void reset() {
        lastTrajectoryPoint = null;
        lastTrajectoryAppendTimeMs = 0L;
        lastGnssObservation = null;
        lastWifiObservation = null;
        lastPdrObservation = null;
        lastWifiObservationFixTimeMs = 0L;
        lastDrawnMarkerPoint = null;
        lastRenderedPoint = null;
        secondLastRenderedPoint = null;
        lastRenderedOrientationDeg = Float.NaN;
        lastCameraPoint = null;
        lastCameraUpdateTimeMs = 0L;
        smoother.reset();
        wifiLatLngCache.reset();
        pdrLatLngCache.reset();
        fusedLatLngCache.reset();
    }

    // Builds one display frame from the latest fused sensor snapshot.
    public void renderFrame() {
        if (trajectoryMapFragment == null) return;

        sensorFusion.fillDisplaySnapshot(displaySnapshot, FUSED_STALE_TIMEOUT_MS);

        LatLng gnssLatLng = displaySnapshot.gnssLatLng;
        LatLng wifiLatLng = toLatLng(
                displaySnapshot,
                displaySnapshot.hasWifiXY ? displaySnapshot.wifiXY : null,
                wifiLatLngCache
        );
        if (wifiLatLng == null) {
            wifiLatLng = displaySnapshot.wifiLatLng;
        }
        if (wifiLatLng == null) {
            wifiLatLng = displaySnapshot.wifiPositioningLatLng;
        }
        LatLng pdrLatLng = toLatLng(
                displaySnapshot,
                displaySnapshot.hasPdrXY ? displaySnapshot.pdrXY : null,
                pdrLatLngCache
        );
        boolean wifiFresh = displaySnapshot.wifiFresh;
        boolean gnssFresh = displaySnapshot.gnssFresh;
        boolean stationary = displaySnapshot.stationary;
        float gnssAccuracyMeters = displaySnapshot.gnssAccuracyMeters;
        double gnssObservationDistance = stationary
                ? STATIONARY_GNSS_OBSERVATION_DISTANCE_METERS
                : OBSERVATION_DISTANCE_EPSILON_METERS;
        if (shouldPlotObservation(lastGnssObservation, gnssLatLng, gnssObservationDistance)) {
            trajectoryMapFragment.enqueueObservationMarker(DisplayObservationType.GNSS, gnssLatLng);
            lastGnssObservation = gnssLatLng;
        }

        boolean wifiObservationReady = wifiFresh
                && wifiLatLng != null
                && displaySnapshot.wifiFixTimeMs > 0L
                && displaySnapshot.wifiApCount >= 4
                && displaySnapshot.wifiQuality01 >= 0.18f;
        double wifiObservationDistance = stationary
                ? STATIONARY_WIFI_OBSERVATION_DISTANCE_METERS
                : OBSERVATION_DISTANCE_EPSILON_METERS;
        boolean wifiFixAdvanced = displaySnapshot.wifiFixTimeMs != lastWifiObservationFixTimeMs;
        if (wifiObservationReady
                && (wifiFixAdvanced
                || shouldPlotObservation(lastWifiObservation, wifiLatLng, wifiObservationDistance))) {
            trajectoryMapFragment.enqueueObservationMarker(DisplayObservationType.WIFI, wifiLatLng);
            lastWifiObservation = wifiLatLng;
            lastWifiObservationFixTimeMs = displaySnapshot.wifiFixTimeMs;
        }

        if (shouldPlotObservation(lastPdrObservation, pdrLatLng, PDR_OBSERVATION_DISTANCE_METERS)) {
            trajectoryMapFragment.enqueueObservationMarker(DisplayObservationType.PDR, pdrLatLng);
            lastPdrObservation = pdrLatLng;
        }

        boolean usingWallSafeTrajectoryPoint = displaySnapshot.hasDisplayTrajectoryXY;
        LatLng fusedByFilter = usingWallSafeTrajectoryPoint
                ? toLatLng(displaySnapshot, displaySnapshot.displayTrajectoryXY, fusedLatLngCache)
                : (displaySnapshot.hasFusedXY
                ? toLatLng(displaySnapshot, displaySnapshot.fusedXY, fusedLatLngCache)
                : null);
        LatLng fusedLatLng = null;
        if (fusedByFilter != null) {
            fusedLatLng = fusedByFilter;
        } else if (pdrLatLng != null) {
            fusedLatLng = pdrLatLng;
        } else if (wifiFresh && wifiLatLng != null) {
            fusedLatLng = wifiLatLng;
        } else if (gnssFresh && gnssLatLng != null) {
            fusedLatLng = gnssLatLng;
        } else if (wifiLatLng != null) {
            fusedLatLng = wifiLatLng;
        } else if (gnssLatLng != null) {
            fusedLatLng = gnssLatLng;
        }

        long nowMs = displaySnapshot.frameTimeMs;
        if (fusedLatLng != null) {
            if (stationary && lastRenderedPoint != null) {
                double stationaryJitterMeters = UtilFunctions.distanceBetweenPoints(lastRenderedPoint, fusedLatLng);
                if (stationaryJitterMeters <= STATIONARY_FREEZE_DISTANCE_METERS) {
                    fusedLatLng = lastRenderedPoint;
                }
            }
            if (!usingWallSafeTrajectoryPoint && trajectoryMapFragment.isSmoothDisplayEnabled()) {
                fusedLatLng = smoother.filter(fusedLatLng);
            } else {
                smoother.reset(fusedLatLng);
            }
            float orientationDeg = stationary && !Float.isNaN(lastRenderedOrientationDeg)
                    ? lastRenderedOrientationDeg
                    : (float) Math.toDegrees(displaySnapshot.orientationRad);
            if (!Float.isFinite(orientationDeg)) {
                orientationDeg = Float.isNaN(lastRenderedOrientationDeg) ? 0f : lastRenderedOrientationDeg;
            }
            boolean moveCamera = !stationary && shouldMoveCamera(fusedLatLng, nowMs);
            if (shouldRenderMarker(fusedLatLng, orientationDeg, moveCamera)) {
                trajectoryMapFragment.renderFusedPosition(
                        fusedLatLng,
                        orientationDeg,
                        moveCamera
                );
                lastDrawnMarkerPoint = fusedLatLng;
                lastRenderedOrientationDeg = orientationDeg;
            }
            secondLastRenderedPoint = lastRenderedPoint;
            lastRenderedPoint = fusedLatLng;
        }

        if (!stationary && shouldAppendTrajectoryPoint(fusedLatLng, nowMs)) {
            trajectoryMapFragment.appendFusedTrajectoryPoint(fusedLatLng);
            lastTrajectoryPoint = fusedLatLng;
            lastTrajectoryAppendTimeMs = nowMs;
        }

        trajectoryMapFragment.flushPendingObservationMarkers();
    }

    // Decides when a new fused trajectory point should be added.
    private boolean shouldAppendTrajectoryPoint(@Nullable LatLng point, long nowMs) {
        if (point == null) return false;
        if (lastTrajectoryPoint == null) return true;
        double distance = UtilFunctions.distanceBetweenPoints(lastTrajectoryPoint, point);
        if (distance >= TRAJECTORY_APPEND_DISTANCE_METERS) return true;
        if (nowMs - lastTrajectoryAppendTimeMs >= TRAJECTORY_APPEND_INTERVAL_MS) {
            return distance >= TRAJECTORY_APPEND_STATIC_MIN_METERS;
        }
        return false;
    }

    // Filters repeated observation markers that are too close together.
    private boolean shouldPlotObservation(@Nullable LatLng lastPoint, @Nullable LatLng newPoint, double epsilonMeters) {
        if (newPoint == null) return false;
        if (lastPoint == null) return true;
        return UtilFunctions.distanceBetweenPoints(lastPoint, newPoint) >= epsilonMeters;
    }

    // Converts local xy coordinates back into latitude and longitude.
    @Nullable
    private LatLng toLatLng(@NonNull SensorFusion.DisplaySnapshot snapshot,
                            @Nullable float[] xy,
                            @NonNull LatLngCache cache) {
        if (!snapshot.hasOrigin || xy == null || xy.length < 2) {
            cache.reset();
            return null;
        }
        double originLat = snapshot.originLatLon[0];
        double originLon = snapshot.originLatLon[1];
        float x = xy[0];
        float y = xy[1];
        if (cache.matches(originLat, originLon, x, y)) {
            return cache.value;
        }
        double lat = originLat + (y / DEGREE_IN_METERS);
        double lon = originLon + (x / (DEGREE_IN_METERS * Math.cos(Math.toRadians(originLat))));
        LatLng converted = new LatLng(lat, lon);
        cache.update(originLat, originLon, x, y, converted);
        return converted;
    }

    // Stops tiny backward jumps from being rendered as true motion.
    @Nullable
    private LatLng suppressBacktrackingNoise(@Nullable LatLng candidate) {
        if (candidate == null) {
            return null;
        }
        if (lastRenderedPoint == null || secondLastRenderedPoint == null) {
            return candidate;
        }

        double distToLast = UtilFunctions.distanceBetweenPoints(lastRenderedPoint, candidate);
        if (distToLast < 0.06) {
            return lastRenderedPoint;
        }
        if (distToLast > BACKTRACK_REJECTION_MAX_METERS) {
            return candidate;
        }

        double prevEast = eastMeters(secondLastRenderedPoint, lastRenderedPoint);
        double prevNorth = northMeters(secondLastRenderedPoint, lastRenderedPoint);
        double nextEast = eastMeters(lastRenderedPoint, candidate);
        double nextNorth = northMeters(lastRenderedPoint, candidate);
        double prevNorm = Math.sqrt(prevEast * prevEast + prevNorth * prevNorth);
        double nextNorm = Math.sqrt(nextEast * nextEast + nextNorth * nextNorth);
        if (prevNorm < 0.35 || nextNorm < 0.35) {
            return candidate;
        }

        double cos = (prevEast * nextEast + prevNorth * nextNorth) / (prevNorm * nextNorm);
        double returnToPrevDistance = UtilFunctions.distanceBetweenPoints(secondLastRenderedPoint, candidate);
        if (cos < BACKTRACK_COS_THRESHOLD
                && nextNorm <= BACKTRACK_REJECTION_MIN_SEGMENT_METERS
                && returnToPrevDistance <= BACKTRACK_RETURN_TO_PREV_MAX_METERS) {
            return lastRenderedPoint;
        }
        return candidate;
    }

    // Limits side-to-side wobble when the path should stay mostly straight.
    @Nullable
    private LatLng suppressCrossTrackJitter(@Nullable LatLng candidate) {
        if (candidate == null || lastRenderedPoint == null || secondLastRenderedPoint == null) {
            return candidate;
        }

        double prevEast = eastMeters(secondLastRenderedPoint, lastRenderedPoint);
        double prevNorth = northMeters(secondLastRenderedPoint, lastRenderedPoint);
        double prevNorm = Math.sqrt(prevEast * prevEast + prevNorth * prevNorth);
        if (prevNorm < STRAIGHT_LINE_MIN_SEGMENT_METERS) {
            return candidate;
        }

        double candEast = eastMeters(lastRenderedPoint, candidate);
        double candNorth = northMeters(lastRenderedPoint, candidate);
        double candNorm = Math.sqrt(candEast * candEast + candNorth * candNorth);
        if (candNorm < 0.15 || candNorm > STRAIGHT_LINE_MAX_CORRECTION_STEP_METERS) {
            return candidate;
        }

        double dirEast = prevEast / prevNorm;
        double dirNorth = prevNorth / prevNorm;
        double alongTrack = candEast * dirEast + candNorth * dirNorth;
        double crossTrack = -candEast * dirNorth + candNorth * dirEast;
        if (alongTrack <= 0.08 || alongTrack <= Math.abs(crossTrack) * 0.6) {
            return candidate;
        }
        if (Math.abs(crossTrack) <= STRAIGHT_LINE_MAX_CROSSTRACK_METERS) {
            return candidate;
        }

        double clampedCrossTrack = Math.copySign(STRAIGHT_LINE_MAX_CROSSTRACK_METERS, crossTrack);
        double correctedEast = alongTrack * dirEast - clampedCrossTrack * dirNorth;
        double correctedNorth = alongTrack * dirNorth + clampedCrossTrack * dirEast;
        return offsetMeters(lastRenderedPoint, correctedEast, correctedNorth);
    }

    // Measures northward distance between two map points.
    private double northMeters(@NonNull LatLng from, @NonNull LatLng to) {
        final double earthRadiusMeters = 6378137.0;
        double dLat = Math.toRadians(to.latitude - from.latitude);
        return dLat * earthRadiusMeters;
    }

    // Measures eastward distance between two map points.
    private double eastMeters(@NonNull LatLng from, @NonNull LatLng to) {
        final double earthRadiusMeters = 6378137.0;
        double dLon = Math.toRadians(to.longitude - from.longitude);
        double meanLat = Math.toRadians((from.latitude + to.latitude) * 0.5);
        return dLon * Math.cos(meanLat) * earthRadiusMeters;
    }

    // Applies meter offsets to a latitude and longitude point.
    @NonNull
    private LatLng offsetMeters(@NonNull LatLng origin, double eastMeters, double northMeters) {
        double lat = origin.latitude + (northMeters / 111111.0);
        double lon = origin.longitude + (eastMeters / (111111.0 * Math.cos(Math.toRadians(origin.latitude))));
        return new LatLng(lat, lon);
    }

    // Mixes the fused point with PDR to keep movement visually continuous.
    @Nullable
    private LatLng blendWithPdrForContinuity(@Nullable LatLng fused,
                                            @Nullable LatLng pdr,
                                            boolean strongGnss,
                                            boolean mediumGnss) {
        if (fused == null) {
            return pdr;
        }
        if (pdr == null) {
            return fused;
        }
        double innovationMeters = UtilFunctions.distanceBetweenPoints(fused, pdr);
        double alphaToPdr = strongGnss ? 0.05 : (mediumGnss ? 0.10 : 0.16);
        if (innovationMeters >= 1.5) {
            alphaToPdr = strongGnss ? 0.02 : (mediumGnss ? 0.05 : 0.06);
        } else if (innovationMeters >= 0.8) {
            alphaToPdr = strongGnss ? 0.03 : (mediumGnss ? 0.07 : 0.10);
        } else if (innovationMeters >= 0.35) {
            alphaToPdr = strongGnss ? 0.04 : (mediumGnss ? 0.08 : 0.12);
        }
        if (innovationMeters <= 0.02 || alphaToPdr <= 1e-3) {
            return fused;
        }
        return new LatLng(
                fused.latitude * (1.0 - alphaToPdr) + pdr.latitude * alphaToPdr,
                fused.longitude * (1.0 - alphaToPdr) + pdr.longitude * alphaToPdr
        );
    }

    // Caps a large render jump so the marker moves more smoothly.
    @Nullable
    private LatLng limitRenderStep(@Nullable LatLng candidate, double maxStepMeters) {
        if (candidate == null || lastRenderedPoint == null) {
            return candidate;
        }
        double distance = UtilFunctions.distanceBetweenPoints(lastRenderedPoint, candidate);
        if (distance <= maxStepMeters || distance <= 1e-4) {
            return candidate;
        }
        double ratio = maxStepMeters / distance;
        double lat = lastRenderedPoint.latitude + (candidate.latitude - lastRenderedPoint.latitude) * ratio;
        double lon = lastRenderedPoint.longitude + (candidate.longitude - lastRenderedPoint.longitude) * ratio;
        return new LatLng(lat, lon);
    }

    // Decides whether the camera should follow the new location.
    private boolean shouldMoveCamera(@NonNull LatLng location, long nowMs) {
        if (lastCameraPoint == null) {
            lastCameraPoint = location;
            lastCameraUpdateTimeMs = nowMs;
            return true;
        }
        double distance = UtilFunctions.distanceBetweenPoints(lastCameraPoint, location);
        if (distance >= CAMERA_UPDATE_DISTANCE_METERS
                || (nowMs - lastCameraUpdateTimeMs) >= CAMERA_UPDATE_INTERVAL_MS) {
            lastCameraPoint = location;
            lastCameraUpdateTimeMs = nowMs;
            return true;
        }
        return false;
    }

    // Decides whether the marker redraw is worth doing now.
    private boolean shouldRenderMarker(@NonNull LatLng location, float orientationDeg, boolean moveCamera) {
        if (lastDrawnMarkerPoint == null || Float.isNaN(lastRenderedOrientationDeg) || moveCamera) {
            return true;
        }
        double distance = UtilFunctions.distanceBetweenPoints(lastDrawnMarkerPoint, location);
        if (distance >= MARKER_RENDER_DISTANCE_EPSILON_METERS) {
            return true;
        }
        float deltaHeading = Math.abs(normalizeHeadingDeltaDegrees(orientationDeg - lastRenderedOrientationDeg));
        return deltaHeading >= MARKER_RENDER_HEADING_EPSILON_DEGREES;
    }

    // Wraps heading differences into the normal -180 to 180 range.
    private float normalizeHeadingDeltaDegrees(float deltaDeg) {
        while (deltaDeg > 180f) {
            deltaDeg -= 360f;
        }
        while (deltaDeg < -180f) {
            deltaDeg += 360f;
        }
        return deltaDeg;
    }

    private static final class LatLngCache {
        private double originLat = Double.NaN;
        private double originLon = Double.NaN;
        private float x = Float.NaN;
        private float y = Float.NaN;
        private LatLng value;

        // Checks whether the cached conversion still matches the inputs.
        private boolean matches(double candidateOriginLat, double candidateOriginLon, float candidateX, float candidateY) {
            return value != null
                    && Double.compare(originLat, candidateOriginLat) == 0
                    && Double.compare(originLon, candidateOriginLon) == 0
                    && Float.compare(x, candidateX) == 0
                    && Float.compare(y, candidateY) == 0;
        }

        // Saves the latest converted point in the cache.
        private void update(double candidateOriginLat,
                            double candidateOriginLon,
                            float candidateX,
                            float candidateY,
                            @NonNull LatLng candidateValue) {
            originLat = candidateOriginLat;
            originLon = candidateOriginLon;
            x = candidateX;
            y = candidateY;
            value = candidateValue;
        }

        // Clears all cached coordinate values.
        private void reset() {
            originLat = Double.NaN;
            originLon = Double.NaN;
            x = Float.NaN;
            y = Float.NaN;
            value = null;
        }
    }
}
