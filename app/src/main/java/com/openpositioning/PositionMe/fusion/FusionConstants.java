package com.openpositioning.PositionMe.fusion;

import android.graphics.Color;

public final class FusionConstants {
    // Particle filter
    public static final int PARTICLE_COUNT = 20;
    public static final int FUSION_TYPE_MAX = 20;
    public static final double INITIAL_UNCERTAINTY_M = 5;

    // Valid types of observations
    public static final String OBSERVATION_TYPE_PDR = "PDR";
    public static final String OBSERVATION_TYPE_WIFI = "WIFI";
    public static final String OBSERVATION_TYPE_GNSS = "GNSS";

    public static final float FLOOR_CHANGE_PERCENTAGE = 0.7f;

    // Observation standard deviations (metres)
    public static final float WIFI_STD_DEV = 10.0f;
    public static final float GNSS_STD_DEV_DEFAULT = 15.0f;
    public static final float MAX_STEP_LENGTH = 1.0f;

    // Coordinate conversion (WGS84 approximations at Edinburgh ~55.9°N)
    public static final double METRES_PER_DEG_LAT = 110574.0;
    public static final double METRES_PER_DEG_LNG_AT_EQUATOR = 111319.5;
    public static final double PDR_NOISE_STDDEV = 0.2;
    public static final double PARTICLE_FILTER_THRESHOLD = 0.10;
    public static final float RESAMPLE_JITTER = 0.5f;
    public static final double INITIAL_ORIENTATION_ERROR_STDDEV = 0.175; // ~10 degrees in radians
    public static final double ORIENTATION_DRIFT_STDDEV = 0.005; // radians per step - just a guess
    public static final double ORIENTATION_RESAMPLE_JITTER = 0.2;

    // Used to display particles on the map
    public static final double MAP_PARTICLE_WEIGHTING = 10.0;
    public static final float LINE_WEIGHT_PARTICLE = 5f;
    public static final int MAP_PARTICLE_COLOUR = Color.MAGENTA;
    // Dark green
    public static final int MAP_WIFI_COLOUR = Color.rgb(9, 132, 50);

    // Kalman Filter
    public static final float NOISE_STD_DEV_PREDICTION = 0.1f;
    public static final float NOISE_STD_DEV_BIAS = 0.1f;
    public static final float MEASUREMENT_NOISE = 0.25f;
    public static final float DELTA_T = 0.25f;
}
