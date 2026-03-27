package com.openpositioning.PositionMe.mapmatching;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Builds a live vertical-transition hint from recent elevation samples.
 *
 * The detector is deliberately conservative:
 * - small barometer drift on a static phone should not trigger a floor change;
 * - sustained elevation changes should be surfaced to map matching;
 * - elevator flag slightly lowers the required threshold.
 */
public final class VerticalMotionDetector {

    private static final long WINDOW_MS = 4000L;
    private static final long MIN_PERSIST_MS = 800L;
    private static final double HEIGHT_CHANGE_THRESHOLD_METERS = 1.2d;
    private static final double ELEVATOR_HEIGHT_CHANGE_THRESHOLD_METERS = 0.8d;
    private static final double RESET_THRESHOLD_METERS = 0.4d;

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private boolean latchedHeightChanged = false;

    public void reset() {
        samples.clear();
        latchedHeightChanged = false;
    }

    public void addSample(long timestampMs, double elevationMeters, boolean elevatorFlag) {
        if (Double.isNaN(elevationMeters) || Double.isInfinite(elevationMeters)) {
            return;
        }

        samples.addLast(new Sample(timestampMs, elevationMeters, elevatorFlag));
        pruneOldSamples(timestampMs);
        updateLatchState();
    }

    @Nullable
    public VerticalTransitionHint buildHint() {
        if (samples.isEmpty()) {
            return null;
        }

        Sample first = samples.peekFirst();
        Sample last = samples.peekLast();
        if (first == null || last == null) {
            return null;
        }

        double deltaHeight = last.elevationMeters - first.elevationMeters;
        return new VerticalTransitionHint(last.elevationMeters, deltaHeight, latchedHeightChanged);
    }

    private void pruneOldSamples(long latestTimestampMs) {
        long cutoff = latestTimestampMs - WINDOW_MS;
        while (!samples.isEmpty() && samples.peekFirst() != null && samples.peekFirst().timestampMs < cutoff) {
            samples.removeFirst();
        }
    }

    private void updateLatchState() {
        if (samples.size() < 2) {
            latchedHeightChanged = false;
            return;
        }

        Sample first = samples.peekFirst();
        Sample last = samples.peekLast();
        if (first == null || last == null) {
            latchedHeightChanged = false;
            return;
        }

        double deltaHeight = last.elevationMeters - first.elevationMeters;
        double absDeltaHeight = Math.abs(deltaHeight);
        long elapsedMs = Math.max(0L, last.timestampMs - first.timestampMs);
        boolean elevatorLikely = containsElevatorFlag();
        double threshold = elevatorLikely
                ? ELEVATOR_HEIGHT_CHANGE_THRESHOLD_METERS
                : HEIGHT_CHANGE_THRESHOLD_METERS;

        if (latchedHeightChanged) {
            if (absDeltaHeight <= RESET_THRESHOLD_METERS) {
                latchedHeightChanged = false;
            }
            return;
        }

        if (elapsedMs >= MIN_PERSIST_MS && absDeltaHeight >= threshold) {
            latchedHeightChanged = true;
        }
    }

    private boolean containsElevatorFlag() {
        Iterator<Sample> iterator = samples.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().elevatorFlag) {
                return true;
            }
        }
        return false;
    }

    private static final class Sample {
        final long timestampMs;
        final double elevationMeters;
        final boolean elevatorFlag;

        Sample(long timestampMs, double elevationMeters, boolean elevatorFlag) {
            this.timestampMs = timestampMs;
            this.elevationMeters = elevationMeters;
            this.elevatorFlag = elevatorFlag;
        }
    }
}
