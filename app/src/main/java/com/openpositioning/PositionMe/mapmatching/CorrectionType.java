package com.openpositioning.PositionMe.mapmatching;

/**
 * 表示 map matching 修正的原因。
 */
public enum CorrectionType {
    NONE,
    THROUGH_WALL,
    INVALID_FLOOR_CHANGE,
    SNAP_TO_VALID_AREA
}