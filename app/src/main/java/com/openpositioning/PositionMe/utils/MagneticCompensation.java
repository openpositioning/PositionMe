package com.openpositioning.PositionMe.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Grid-based heading compensation for magnetic disturbances.
public class MagneticCompensation {
    private final List<MagneticGridCell> gridCells;
    private final double maxLookupDistanceMeters;
    private final float maxCorrectionRad;
    private volatile boolean enabled = true;

    public MagneticCompensation(List<MagneticGridCell> gridCells,
                                double maxLookupDistanceMeters,
                                float maxCorrectionRad) {
        if (gridCells == null || gridCells.isEmpty()) {
            this.gridCells = Collections.emptyList();
        } else {
            this.gridCells = Collections.unmodifiableList(new ArrayList<>(gridCells));
        }
        this.maxLookupDistanceMeters = Math.max(0.0, maxLookupDistanceMeters);
        this.maxCorrectionRad = Math.max(0.0f, maxCorrectionRad);
    }

    public static MagneticCompensation empty() {
        return new MagneticCompensation(Collections.emptyList(), 10.0, (float) Math.toRadians(15.0));
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getCellCount() {
        return gridCells.size();
    }

    public float getCorrectionAngle(double currentX, double currentY) {
        if (!enabled || gridCells.isEmpty()) {
            return 0.0f;
        }

        MagneticGridCell nearest = null;
        double minDistance = Double.MAX_VALUE;

        for (MagneticGridCell cell : gridCells) {
            double dx = cell.xMeters - currentX;
            double dy = cell.yMeters - currentY;
            double distance = Math.hypot(dx, dy);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = cell;
            }
        }

        if (nearest == null || minDistance > maxLookupDistanceMeters) {
            return 0.0f;
        }

        float confidence = clamp01(nearest.confidence);
        float confidenceScaledCorrection = nearest.correctionRad * confidence;
        return clamp(confidenceScaledCorrection, -maxCorrectionRad, maxCorrectionRad);
    }

    private float clamp01(float value) {
        if (value < 0.0f) {
            return 0.0f;
        }
        if (value > 1.0f) {
            return 1.0f;
        }
        return value;
    }

    private float clamp(float value, float min, float max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}

