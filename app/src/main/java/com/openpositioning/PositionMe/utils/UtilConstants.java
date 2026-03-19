package com.openpositioning.PositionMe.utils;

import android.graphics.Color;
import com.openpositioning.PositionMe.BuildConfig;

public class UtilConstants {

    // Building outlines
    public static final int COLOUR_BUILDING_WITHOUT_FLOOR_MAPS = Color.YELLOW;
    public static final int COLOUR_BUILDING_WITH_FLOOR_MAPS = Color.GREEN;

    // Paths
    public static final int COLOUR_PATH_COLOUR = Color.RED;
    public static final int COLOUR_PATH_MONOCHROME = Color.BLACK;
    public static final int COLOUR_PATH_GNSS = Color.BLUE;

    // Floor plans
    public static final int COLOUR_FLOOR_PLAN_FILL_TRANSPARENT = Color.TRANSPARENT;
    public static final int COLOUR_FLOOR_PLAN_FILL_PREVIEW = Color.LTGRAY;
    public static final int COLOUR_FLOOR_PLAN_FILL_INSIDE = Color.WHITE;
    public static final int COLOUR_FLOOR_PLAN_ELEMENTS = Color.BLACK;

    // Set priority level for drawing elements on top of one another on the map
    public static final int MAP_DRAWING_PRIORITY_MAX = 1000;

    public static final float LINE_WEIGHT_PATH = 5f;
    public static final float LINE_WEIGHT_FLOOR_PLAN = 5f;
    public static final float LINE_WEIGHT_OUTLINE = 10f;

    public static final float ZOOM_LEVEL_DEFAULT = 19f;

    // Credentials
    public static final String CREDENTIALS_FILE_NAME = "login_details";
    public static final String CREDENTIALS_KEY_EMAIL = "email";
    public static final String CREDENTIALS_KEY_PASSWORD = "password";

    // Source: https://openpositioning.org/docs
    public static final String API_KEY_MASTER = BuildConfig.OPENPOSITIONING_MASTER_KEY;
    public static final String URL_API = "https://openpositioning.org/api";
    public static final String API_GET_USER_TRAJECTORIES = "/live/users/trajectories";
    public static final String API_GET_TRAJECTORIES = "/live/trajectory/download";
    public static final String API_POST_TRAJECTORIES = "/live/trajectory/upload";
    public static final String API_POST_FLOORPLANS = "/live/floorplan/request";
    public static final String API_POST_SIGN_UP = "/users/signup";
    public static final String API_POST_LOGIN = "/users/login";

    public static final String URL_GET_USER_TRAJECTORIES = URL_API + API_GET_USER_TRAJECTORIES;

    // URL is in two parts as user variable is inserted for number of entries to retrieve
    public static final String URL_GET_TRAJECTORIES_HEAD = URL_API + API_GET_TRAJECTORIES;
    public static final String URL_GET_TRAJECTORIES_TAIL = "&key=" + API_KEY_MASTER;
    public static final String URL_POST_FLOORPLANS = URL_API + API_POST_FLOORPLANS;
    public static final String PROTOCOL_MULTIPART = "multipart/form-data";
    public static final String PROTOCOL_APP_JSON = "application/json";

    // Expected responses from floor plan API request
    public static final String BUILDING_NAME_NUCLEUS = "nucleus_building";
    public static final String BUILDING_NAME_M_HOUSE = "murchison_house";
    public static final String BUILDING_NAME_OUTSIDE = "outside";

    public static final float FLOOR_HEIGHT_DEFAULT = 3.6F;
    public static final float FLOOR_HEIGHT_NKM_LIBRARY = 3.6F;
    public static final float FLOOR_HEIGHT_NUCLEUS = 4.2F;

    // The minimum time between sensor readings in milliseconds
    public static final int SENSOR_POLL_TIME_MS = 20;
    public static final int FLOOR_PLAN_POLL_TIME_MS = 1000;
    public static final int ACCELERATION_MAGNITUDE_MAXIMUM_SIZE = 20000;
}
