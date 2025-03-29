package com.openpositioning.PositionMe.sensors;

import com.google.android.gms.maps.model.LatLng;

import java.lang.Math;

public class LocalCoordinateSystem {

    private static final double EARTH_RADIUS = 6378137.0; // meters (WGS84)
    private Double refLat = null;  // 原点纬度
    private Double refLon = null;  // 原点经度
    private boolean initialized = false;

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 初始化参考坐标（只设置一次）
     */
    public void initReference(double latitude, double longitude) {
        if (!initialized) {
            this.refLat = latitude;
            this.refLon = longitude;
            this.initialized = true;
        }
    }

    /**
     * 将 lat/lon 转换为相对坐标（单位：米）
     */
    public float[] toLocal(double latitude, double longitude) {
        if (!initialized) {
            initReference(latitude, longitude);  // 自动初始化
            return new float[]{0.0F, 0.0F};
        }

        double dLat = Math.toRadians(latitude - refLat);
        double dLon = Math.toRadians(longitude - refLon);
        double meanLat = Math.toRadians((latitude + refLat) / 2.0);

        double x = EARTH_RADIUS * dLon * Math.cos(meanLat);
        double y = EARTH_RADIUS * dLat;
        return new float[]{(float) x, (float) y};
    }

    /**
     * 将本地坐标转换回经纬度
     */
    public LatLng toGlobal(double x, double y) {
        if (!initialized) {
            throw new IllegalStateException("Reference point not initialized.");
        }

        double dLat = y / EARTH_RADIUS;
        double dLon =  x / (EARTH_RADIUS * Math.cos(Math.toRadians(refLat)));

        double lat = refLat + Math.toDegrees(dLat);
        double lon = refLon + Math.toDegrees(dLon);
        return new LatLng(lat, lon);
    }

    public double[] getReferenceLatLon() {
        if (!initialized) {
            throw new IllegalStateException("Reference point not initialized.");
        }
        return new double[]{refLat, refLon};
    }
}

