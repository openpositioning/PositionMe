package com.openpositioning.PositionMe.utils;

// Immutable grid cell for magnetic heading compensation.
public class MagneticGridCell {
    public final double xMeters;
    public final double yMeters;
    public final float correctionRad;
    public final float confidence;
    public final int sampleCount;
    public final long updatedAtEpochMs;

    public MagneticGridCell(double xMeters,
                            double yMeters,
                            float correctionRad,
                            float confidence,
                            int sampleCount,
                            long updatedAtEpochMs) {
        this.xMeters = xMeters;
        this.yMeters = yMeters;
        this.correctionRad = correctionRad;
        this.confidence = confidence;
        this.sampleCount = sampleCount;
        this.updatedAtEpochMs = updatedAtEpochMs;
    }
}

