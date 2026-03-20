package com.openpositioning.PositionMe.fusion;

public final class FusionConstants {
    private FusionConstants() {} // prevent instantiation

    // Particle filter
    public static final int PARTICLE_COUNT = 100;
    public static final double INITIAL_UNCERTAINTY_M = 5.0;

    // Observation standard deviations (metres)
    public static final double WIFI_STD_DEV = 8.0;
    public static final double GNSS_STD_DEV_DEFAULT = 15.0;

    // Floor
    public static final int DEFAULT_FLOOR = 0;
    public static final double FLOOR_HEIGHT_M = 4.0;

    // Coordinate conversion (WGS84 approximations at Edinburgh ~55.9°N)
    public static final double METRES_PER_DEG_LAT = 110574.0;
    public static final double METRES_PER_DEG_LNG_AT_EQUATOR = 111319.5;
    public static final double PDR_NOISE_STDDEV = 0.2;
    public static final double PARTICLE_FILTER_THRESHOLD = 0.33;
    public static final double RESAMPLE_JITTER = 0.05;
}
