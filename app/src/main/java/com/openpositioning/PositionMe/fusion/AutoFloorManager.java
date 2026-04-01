package com.openpositioning.PositionMe.fusion;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.openpositioning.PositionMe.mapmatching.VerticalTransitionHint;
import com.openpositioning.PositionMe.sensors.SensorFusion;

/**
 * AutoFloorManager
 *
 * Owns the live floor-transition decision state for the PF pipeline.
 *
 * Design intent:
 * - startup floor bootstrap is handled elsewhere (manual floor / initial WiFi floor / default floor)
 * - this class handles only LIVE floor ownership after the session has started
 * - live floor changes must be conservative:
 *   1. require real vertical evidence relative to the last committed floor anchor
 *   2. in lift mode, require a stable repeated WiFi target floor
 *   3. in non-lift mode, allow only conservative adjacent-floor suggestions
 *
 * Important:
 * this class does NOT directly move particles or change UI.
 * It only decides:
 * - what floor is currently owned
 * - what floor is being requested
 * - when a floor change should be committed
 *
 * ParticleFilterManager remains responsible for:
 * - calling this class
 * - validating requested floor changes through MapMatchingService
 * - applying committed floor changes to the PF engine and live fused pose
 */
public class AutoFloorManager {

    private static final String TAG = "AutoFloorManager";

    /** Cooldown after a committed floor change to avoid rapid repeated flips. */
    private static final long FLOOR_CHANGE_COOLDOWN_MS = 3000L;

    /** Stable WiFi floor duration required before trusting lift-assisted floor change. */
    private static final long LIVE_LIFT_WIFI_STABLE_MS = 1800L;

    /** Stable WiFi floor sample count required before trusting lift-assisted floor change. */
    private static final int LIVE_LIFT_WIFI_REQUIRED_SAMPLES = 4;

    /**
     * Minimum vertical change required before any live floor transition is allowed.
     *
     * This prevents accidental floor switches just because the user is close to a lift/stairs
     * or because of small barometer noise.
     */
    private static final double LIVE_AUTO_FLOOR_MIN_VERTICAL_DELTA_METERS = 1.2;

    /**
     * Fraction of nominal floor height that must be exceeded before the next floor
     * is considered plausible.
     *
     * Example with 3.0 m floor height and 0.55 fraction:
     * required delta per floor ≈ 1.65 m
     */
    private static final double LIVE_AUTO_FLOOR_VERTICAL_FRACTION_PER_FLOOR = 0.80;

    /** Default floor height used when real building floor heights are not available. */
    private static final double DEFAULT_FLOOR_HEIGHT_METERS = 3.0;

    /**
     * Adapter so AutoFloorManager can validate and clamp floor indices without knowing
     * how the building model is stored.
     */
    public interface FloorIndexAdapter {
        boolean isFloorIndexAvailable(int floorIndex);
        int sanitiseFloorIndex(int floorIndex);
    }

    /** Main live sensor/state source. */
    private final SensorFusion sensorFusion;

    /** Building-specific floor bounds adapter owned by the PF manager. */
    private final FloorIndexAdapter floorIndexAdapter;

    /** Current authoritative live PF floor. */
    private int activeFloor = 0;

    /** Timestamp when the current floor was last committed. */
    private long lastCommittedFloorChangeElapsedMs = 0L;

    /** Elevation snapshot when the current floor was committed. */
    private double lastCommittedElevationMeters = Double.NaN;

    /** Pending repeated WiFi floor candidate used only during lift mode. */
    private int pendingLiftWifiFloor = Integer.MIN_VALUE;

    /** First timestamp when the current pending lift WiFi floor was observed. */
    private long pendingLiftWifiSinceMs = 0L;

    /** Number of repeated samples seen for the pending lift WiFi floor. */
    private int pendingLiftWifiSamples = 0;

    public AutoFloorManager(@NonNull SensorFusion sensorFusion,
                            @NonNull FloorIndexAdapter floorIndexAdapter) {
        this.sensorFusion = sensorFusion;
        this.floorIndexAdapter = floorIndexAdapter;
    }

    /**
     * Resets all live floor-transition state.
     *
     * Use this at the start/end of a recording session, or whenever the PF manager resets.
     */
    public void reset() {
        activeFloor = 0;
        lastCommittedFloorChangeElapsedMs = 0L;
        lastCommittedElevationMeters = Double.NaN;
        resetLiftWifiFloorState();
    }

    /**
     * Initialises the live floor owner to a known starting floor.
     *
     * This should be called once the PF start floor is chosen, so later live transitions
     * are measured relative to a committed elevation anchor.
     */
    public void initialiseFloor(int floorIndex) {
        activeFloor = floorIndexAdapter.sanitiseFloorIndex(floorIndex);
        syncCommittedFloorAnchor();
        resetLiftWifiFloorState();
    }

    /**
     * Forces a new current floor immediately.
     *
     * This is used when the user manually changes the floor from the UI.
     */
    public void forceFloor(int floorIndex) {
        initialiseFloor(floorIndex);
    }

    /**
     * Returns the currently owned live floor.
     */
    public int getActiveFloor() {
        return activeFloor;
    }

    /**
     * Commits a validated floor transition.
     *
     * This should only be called AFTER MapMatchingService has accepted the floor change.
     */
    public void onFloorCommitted(int floorIndex, long timestampMs) {
        activeFloor = floorIndexAdapter.sanitiseFloorIndex(floorIndex);
        lastCommittedFloorChangeElapsedMs = timestampMs;
        syncCommittedFloorAnchor();
        resetLiftWifiFloorState();

        Log.d(TAG,
                "Committed live floor"
                        + " | activeFloor=" + activeFloor
                        + ", committedAt=" + timestampMs
                        + ", committedElevation=" + lastCommittedElevationMeters);
    }

    /**
     * Decides whether a cross-floor WiFi observation may be accepted as a position observation.
     *
     * This is intentionally strict:
     * - only relevant during lift mode
     * - WiFi floor must be stable
     * - enough vertical change from the committed anchor must already exist
     *
     * Use this only for observation gating, not as the final floor-switch decision.
     */
    public boolean shouldAcceptCrossFloorWifiObservation(int observedWifiFloor, long timestampMs) {
        if (!sensorFusion.getElevator()) {
            return false;
        }

        if (!floorIndexAdapter.isFloorIndexAvailable(observedWifiFloor)) {
            return false;
        }

        int candidateFloor = floorIndexAdapter.sanitiseFloorIndex(observedWifiFloor);
        Integer stableLiftWifiFloor = resolveStableLiftWifiFloor(timestampMs);

        return stableLiftWifiFloor != null
                && stableLiftWifiFloor == candidateFloor
                && hasSufficientCommittedVerticalChange(candidateFloor);
    }

    /**
     * Produces the next requested floor for the current live update.
     *
     * Policy:
     * - if live auto floor is disabled -> keep current floor
     * - if there is no valid vertical transition hint -> keep current floor
     * - if cooldown is active -> keep current floor
     * - in lift mode:
     *     trust only stable repeated WiFi floor + sufficient vertical change
     * - in non-lift mode:
     *     allow adjacent WiFi floor if plausible, otherwise infer adjacent floor from height sign
     *
     * This returns only a REQUESTED floor.
     * It does not itself commit the floor change.
     */
    public int resolveRequestedFloor(@Nullable VerticalTransitionHint verticalHint,
                                     long timestampMs) {

        if (!sensorFusion.isLiveAutoFloorEnabled()) {
            resetLiftWifiFloorState();
            return activeFloor;
        }

        if (verticalHint == null || !verticalHint.isHeightChanged()) {
            resetLiftWifiFloorState();
            return activeFloor;
        }

        if (timestampMs - lastCommittedFloorChangeElapsedMs < FLOOR_CHANGE_COOLDOWN_MS) {
            return activeFloor;
        }

        boolean elevatorLikely = sensorFusion.getElevator();

        if (elevatorLikely) {
            Integer stableLiftWifiFloor = resolveStableLiftWifiFloor(timestampMs);
            if (stableLiftWifiFloor != null
                    && stableLiftWifiFloor != activeFloor
                    && hasSufficientCommittedVerticalChange(stableLiftWifiFloor)) {

                Log.d(TAG,
                        "Requested floor by stable lift WiFi"
                                + " | activeFloor=" + activeFloor
                                + ", targetFloor=" + stableLiftWifiFloor
                                + ", deltaHeight=" + verticalHint.getDeltaHeight());

                return stableLiftWifiFloor;
            }

            /*
             * Minimal fallback:
             * if lift WiFi is not stable enough yet, but the barometric height strongly
             * indicates a one-floor transition, allow a conservative adjacent-floor request
             * by height sign.
             */
            int candidateByHeight;
            if (verticalHint.getDeltaHeight() > 0.0) {
                candidateByHeight = floorIndexAdapter.sanitiseFloorIndex(activeFloor + 1);
            } else if (verticalHint.getDeltaHeight() < 0.0) {
                candidateByHeight = floorIndexAdapter.sanitiseFloorIndex(activeFloor - 1);
            } else {
                return activeFloor;
            }

            if (candidateByHeight != activeFloor
                    && Math.abs(candidateByHeight - activeFloor) == 1
                    && hasSufficientCommittedVerticalChange(candidateByHeight)) {

                Log.d(TAG,
                        "Requested floor by lift height fallback"
                                + " | activeFloor=" + activeFloor
                                + ", targetFloor=" + candidateByHeight
                                + ", deltaHeight=" + verticalHint.getDeltaHeight());

                return candidateByHeight;
            }

            return activeFloor;
        }

        // Non-lift mode: reset lift WiFi state and use conservative adjacent-floor logic.
        resetLiftWifiFloorState();

        int wifiFloor = sensorFusion.getWifiFloor();
        boolean wifiFloorValid = floorIndexAdapter.isFloorIndexAvailable(wifiFloor);

        if (wifiFloorValid
                && wifiFloor != activeFloor
                && Math.abs(wifiFloor - activeFloor) == 1
                && hasSufficientCommittedVerticalChange(wifiFloor)) {
            return floorIndexAdapter.sanitiseFloorIndex(wifiFloor);
        }

        int candidateByHeight;
        if (verticalHint.getDeltaHeight() > 0.0) {
            candidateByHeight = floorIndexAdapter.sanitiseFloorIndex(activeFloor + 1);
        } else if (verticalHint.getDeltaHeight() < 0.0) {
            candidateByHeight = floorIndexAdapter.sanitiseFloorIndex(activeFloor - 1);
        } else {
            return activeFloor;
        }

        if (candidateByHeight != activeFloor
                && hasSufficientCommittedVerticalChange(candidateByHeight)) {
            return candidateByHeight;
        }

        return activeFloor;
    }

    /**
     * Clears all pending lift WiFi stability state.
     *
     * This must be called whenever:
     * - the user forces a floor
     * - a floor is committed
     * - lift mode is no longer relevant
     * - no usable WiFi floor is present
     */
    private void resetLiftWifiFloorState() {
        pendingLiftWifiFloor = Integer.MIN_VALUE;
        pendingLiftWifiSinceMs = 0L;
        pendingLiftWifiSamples = 0;
    }

    /**
     * Re-anchors the current floor ownership to the latest elevation.
     *
     * Later floor transitions are measured relative to this committed elevation anchor.
     */
    private void syncCommittedFloorAnchor() {
        lastCommittedElevationMeters = sensorFusion.getElevation();
    }

    /**
     * Tracks repeated WiFi floor samples during lift mode and returns a stable candidate floor.
     *
     * Requirements:
     * - WiFi position must exist
     * - WiFi floor must be a valid floor
     * - WiFi floor must differ from the currently active floor
     * - same WiFi floor must repeat for enough samples and enough elapsed time
     */
    @Nullable
    private Integer resolveStableLiftWifiFloor(long timestampMs) {
        if (sensorFusion.getLatLngWifiPositioning() == null) {
            resetLiftWifiFloorState();
            return null;
        }

        int wifiFloor = sensorFusion.getWifiFloor();
        if (!floorIndexAdapter.isFloorIndexAvailable(wifiFloor)) {
            resetLiftWifiFloorState();
            return null;
        }

        int candidateFloor = floorIndexAdapter.sanitiseFloorIndex(wifiFloor);

        if (candidateFloor == activeFloor) {
            resetLiftWifiFloorState();
            return null;
        }

        if (candidateFloor != pendingLiftWifiFloor) {
            pendingLiftWifiFloor = candidateFloor;
            pendingLiftWifiSinceMs = timestampMs;
            pendingLiftWifiSamples = 1;

            Log.d(TAG,
                    "Pending lift WiFi floor"
                            + " | candidate=" + candidateFloor
                            + ", active=" + activeFloor);

            return null;
        }

        pendingLiftWifiSamples++;
        long stableDurationMs = timestampMs - pendingLiftWifiSinceMs;

        if (pendingLiftWifiSamples >= LIVE_LIFT_WIFI_REQUIRED_SAMPLES
                && stableDurationMs >= LIVE_LIFT_WIFI_STABLE_MS) {
            Log.d(TAG,
                    "Stable lift WiFi floor accepted"
                            + " | candidate=" + candidateFloor
                            + ", active=" + activeFloor
                            + ", samples=" + pendingLiftWifiSamples
                            + ", stableMs=" + stableDurationMs);
            return candidateFloor;
        }

        return null;
    }

    /**
     * Checks whether enough barometric vertical change has accumulated since the last
     * committed floor anchor.
     *
     * This is the main safeguard against false floor changes caused by:
     * - being physically near a lift
     * - one noisy WiFi floor estimate
     * - small pressure fluctuations
     */
    private boolean hasSufficientCommittedVerticalChange(int targetFloor) {
        if (!Double.isFinite(lastCommittedElevationMeters)) {
            return false;
        }

        int floorSteps = Math.max(1, Math.abs(targetFloor - activeFloor));
        double requiredDeltaMeters = Math.max(
                LIVE_AUTO_FLOOR_MIN_VERTICAL_DELTA_METERS,
                DEFAULT_FLOOR_HEIGHT_METERS
                        * LIVE_AUTO_FLOOR_VERTICAL_FRACTION_PER_FLOOR
                        * floorSteps
        );

        double actualDeltaMeters = Math.abs(sensorFusion.getElevation() - lastCommittedElevationMeters);

        Log.d(TAG,
                "Vertical gate"
                        + " | active=" + activeFloor
                        + ", target=" + targetFloor
                        + ", actualDelta=" + actualDeltaMeters
                        + ", requiredDelta=" + requiredDeltaMeters
                        + ", anchorElev=" + lastCommittedElevationMeters
                        + ", currentElev=" + sensorFusion.getElevation());

        return actualDeltaMeters >= requiredDeltaMeters;
    }
}