package com.openpositioning.PositionMe.utils;

/**
 * Classifies cross-floor movements into lift or stairs based on displacement and height change.
 * Pure function: no side effects, safe for unit testing and future integration.
 */
public final class CrossFloorClassifier {

    public enum Mode {
        LIFT,
        STAIRS,
        UNKNOWN
    }

    private CrossFloorClassifier() {
        // Utility class
    }

    /**
     * Decide whether a cross-floor movement is a lift or stairs event.
     *
     * @param horizontalDelta horizontal displacement in meters.
     * @param heightDelta     vertical displacement in meters (absolute value is considered).
     * @param featureDistance distance to the nearest cross-floor feature in meters (unused for now,
     *                        reserved for tie-breaks).
     * @param config          thresholds used for classification.
     * @return mode describing the movement.
     */
    public static Mode classify(double horizontalDelta,
                                double heightDelta,
                                double featureDistance,
                                MapMatchingConfig config) {
        double absHeight = Math.abs(heightDelta);
        double absHorizontal = Math.abs(horizontalDelta);

        boolean heightSignificant = absHeight >= config.baroHeightThreshold;
        if (!heightSignificant) {
            return Mode.UNKNOWN;
        }

        if (absHorizontal < config.liftHorizontalMax) {
            return Mode.LIFT;
        }

        if (absHorizontal >= config.stairsHorizontalMin) {
            return Mode.STAIRS;
        }

        return Mode.UNKNOWN;
    }
}
