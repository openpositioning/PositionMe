package com.openpositioning.PositionMe.mapmatching;

import com.google.android.gms.maps.model.LatLng;

/**
 * 表示一个候选位置状态。
 * 后续可用于表示 fusion 输出的位置、map matching 修正后的位置等。
 */
public class CandidatePose {

    private final LatLng latLng;
    private final int floor;
    private final long timestampMs;
    private final String sourceType;

    public CandidatePose(LatLng latLng, int floor, long timestampMs, String sourceType) {
        this.latLng = latLng;
        this.floor = floor;
        this.timestampMs = timestampMs;
        this.sourceType = sourceType;
    }

    public LatLng getLatLng() {
        return latLng;
    }

    public int getFloor() {
        return floor;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public String getSourceType() {
        return sourceType;
    }
}