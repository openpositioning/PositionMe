package com.openpositioning.PositionMe.utils;

import android.graphics.Color;

/**
 * Centralised definitions of {@link Building} related constants
 *
 * @see Building
 * @see com.openpositioning.PositionMe.presentation.fragment.TrajectoryMapFragment
 *     TrajectoryMapFragment
 * @see IndoorMapManager
 * @see FloorPlan
 */
public class BuildingConstants {

    // Building outlines
    public static final int COLOUR_BUILDING_WITHOUT_FLOOR_MAPS = Color.YELLOW;
    public static final int COLOUR_BUILDING_WITH_FLOOR_MAPS = Color.GREEN;

    public static final String BUILDING_ELEMENT_WALL = "wall";
    public static final String BUILDING_ELEMENT_STAIRS = "stairs";
    public static final String BUILDING_ELEMENT_LIFT = "lift";

    public static final int BUILDING_NO_FLOOR_NUMBER = -1;
    public static final String BUILDING_NO_FLOOR_NAME = "N/A";

    // Paths
    public static final int COLOUR_PATH_COLOUR = Color.RED;
    public static final int COLOUR_PATH_MONOCHROME = Color.BLACK;
    public static final int COLOUR_PATH_GNSS = Color.BLUE;
    public static final int COLOUR_PATH_FUSION = Color.GREEN;

    // Floor plans
    public static final int COLOUR_FLOOR_PLAN_FILL_TRANSPARENT = Color.TRANSPARENT;
    public static final int COLOUR_FLOOR_PLAN_FILL_PREVIEW = Color.LTGRAY;
    public static final int COLOUR_FLOOR_PLAN_FILL_INSIDE = Color.WHITE;
    public static final int COLOUR_FLOOR_PLAN_ELEMENTS_DEFAULT = Color.BLACK;
    public static final int COLOUR_FLOOR_PLAN_ELEMENTS_WALL = Color.BLACK;
    public static final int COLOUR_FLOOR_PLAN_ELEMENTS_STAIRS = Color.CYAN;
    public static final int COLOUR_FLOOR_PLAN_ELEMENTS_LIFT = Color.BLUE;

    // Set priority level for drawing elements on top of one another on the map
    public static final int MAP_DRAWING_PRIORITY_MAX = 1000;

    public static final float LINE_WEIGHT_FLOOR_PLAN = 5f;
    public static final float LINE_WEIGHT_OUTLINE = 10f;

    public static final float FLOOR_HEIGHT_DEFAULT = 3.6F;
    public static final float FLOOR_HEIGHT_NKM_LIBRARY = 3.6F;
    public static final float FLOOR_HEIGHT_NUCLEUS = 4.2F;
}
