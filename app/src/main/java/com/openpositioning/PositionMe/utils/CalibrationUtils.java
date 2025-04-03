package com.openpositioning.PositionMe.utils;

import com.google.android.gms.maps.model.LatLng;

/**
 * CalibrationUtils 提供用于计算位置误差的工具方法，
 * 包括使用 Haversine 公式计算两个经纬度点之间的距离，
 * 以及根据标记点与 GNSS、PDR、WiFi 三个位置计算误差。
 */
public class CalibrationUtils {

    /**
     * 计算两个经纬度点之间的距离（单位：米）
     * 使用 Haversine 公式计算地球表面两点之间的距离
     *
     * @param a 第一个位置点
     * @param b 第二个位置点
     * @return 两点之间的距离（单位：米），如果任意一个点为 null，则返回 -1
     */
    public static double distanceInMeters(LatLng a, LatLng b) {
        if (a == null || b == null) return -1;
        final double R = 6371000; // 地球半径，单位：米
        double dLat = Math.toRadians(b.latitude - a.latitude);
        double dLng = Math.toRadians(b.longitude - a.longitude);
        double lat1 = Math.toRadians(a.latitude);
        double lat2 = Math.toRadians(b.latitude);

        double aVal = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                        Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(aVal), Math.sqrt(1 - aVal));
        return R * c;
    }

    /**
     * 根据标记点与 GNSS、PDR、WiFi 位置计算误差
     *
     * @param markerPos 标记点位置
     * @param gnssPos   GNSS 获取的位置，如果为空则对应误差为 -1
     * @param pdrPos    PDR 获取的位置，如果为空则对应误差为 -1
     * @param wifiPos   WiFi 定位获取的位置，如果为空则对应误差为 -1
     * @return 一个 CalibrationErrors 对象，包含各项误差
     */
    public static CalibrationErrors calculateCalibrationErrors(LatLng markerPos, LatLng gnssPos, LatLng pdrPos, LatLng wifiPos) {
        double gnssError = (gnssPos != null) ? distanceInMeters(markerPos, gnssPos) : -1;
        double pdrError = (pdrPos != null) ? distanceInMeters(markerPos, pdrPos) : -1;
        double wifiError = (wifiPos != null) ? distanceInMeters(markerPos, wifiPos) : -1;
        return new CalibrationErrors(gnssError, pdrError, wifiError);
    }

    /**
     * CalibrationErrors 用于封装各传感器与标记点之间的误差数据
     */
    public static class CalibrationErrors {
        public final double gnssError;
        public final double pdrError;
        public final double wifiError;

        public CalibrationErrors(double gnssError, double pdrError, double wifiError) {
            this.gnssError = gnssError;
            this.pdrError = pdrError;
            this.wifiError = wifiError;
        }

        @Override
        public String toString() {
            return String.format("GNSS: %.2f m, PDR: %.2f m, WiFi: %.2f m", gnssError, pdrError, wifiError);
        }
    }
}
