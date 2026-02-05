package com.openpositioning.PositionMe.utils;

import android.graphics.Color;

import com.openpositioning.PositionMe.BuildConfig;

public class UtilConstants {

  public static final int COLOUR_BUILDING_WITHOUT_FLOOR_MAPS = Color.YELLOW;
  public static final int COLOUR_BUILDING_WITH_FLOOR_MAPS = Color.GREEN;
  public static final int COLOUR_PATH_COLOUR = Color.RED;
  public static final int COLOUR_PATH_MONOCHROME = Color.BLACK;
  public static final int COLOUR_PATH_GNSS = Color.BLUE;

  public static final float LINE_WEIGHT_PATH = 5f;

  public static final float ZOOM_LEVEL_DEFAULT = 19f;

  public static final String API_KEY_USER = BuildConfig.OPENPOSITIONING_API_KEY;
  public static final String API_KEY_MASTER = BuildConfig.OPENPOSITIONING_MASTER_KEY;
  public static final String URL_API = "https://openpositioning.org/api";
  public static final String API_GET_USER_TRAJECTORIES = "/live/users/trajectories";
  public static final String API_GET_TRAJECTORIES =  "/live/trajectory/download";
  public static final String API_POST_TRAJECTORIES = "/live/trajectory/upload";

  public static final String URL_GET_USER_TRAJECTORIES =
    URL_API + API_GET_USER_TRAJECTORIES + "/" + API_KEY_USER + "?key=" + API_KEY_MASTER;

  // TODO - Extract function to allow N trajectories downloaded, not just 30
  public static final String URL_GET_TRAJECTORIES =
    URL_API + API_GET_TRAJECTORIES + "/" + API_KEY_USER + "?skip=0&limit=30&key=" + API_KEY_MASTER;
  public static final String PROTOCOL_MULTIPART = "multipart/form-data";
  public static final String PROTOCOL_APP_JSON = "application/json";
}
