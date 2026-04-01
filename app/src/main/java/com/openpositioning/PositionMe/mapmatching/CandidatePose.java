package com.openpositioning.PositionMe.mapmatching;

import androidx.annotation.Nullable;
import com.google.android.gms.maps.model.LatLng;

/**
 * Candidate pose used by map matching and by PF observation fusion.
 *
 * Backward-compatible:
 * - old callers can still use the 4-argument constructor
 * - new callers can optionally provide heading
 */
public class CandidatePose {

    private final LatLng latLng;
    private final int floor;
    private final long timestampMs;
    private final String sourceType;

    @Nullable
    private final Double headingRad;

    public CandidatePose(LatLng latLng,
                         int floor,
                         long timestampMs,
                         String sourceType) {
        this(latLng, floor, timestampMs, sourceType, null);
    }

    public CandidatePose(LatLng latLng,
                         int floor,
                         long timestampMs,
                         String sourceType,
                         @Nullable Double headingRad) {
        this.latLng = latLng;
        this.floor = floor;
        this.timestampMs = timestampMs;
        this.sourceType = sourceType;
        this.headingRad = headingRad;
    }

    public LatLng getLatLng() {
        return latLng;
    }

    /**
     * Current map stack is already using floor indices.
     * Keep this as the canonical getter.
     */
    public int getFloor() {
        return floor;
    }

    /**
     * Compatibility alias for PF code that still says "logical floor".
     * In your current app this is effectively the active floor index.
     */
    public int getLogicalFloor() {
        return floor;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public String getSourceType() {
        return sourceType;
    }

    @Nullable
    public Double getHeadingRad() {
        return headingRad;
    }
}