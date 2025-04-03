package com.openpositioning.PositionMe.utils;

import com.google.android.gms.maps.model.LatLng;

/**
 * CalibrationUtils provides utility methods for calculating positioning errors,
 * including computing the distance between two latitude/longitude points using
 * the Haversine formula and calculating errors between a reference marker and
 * GNSS, PDR, and WiFi locations.
 */
public class CalibrationUtils {

    /**
     * Calculates the distance between two geographic points in meters.
     * Uses the Haversine formula to compute the surface distance over the Earth.
     *
     * @param a First LatLng point
     * @param b Second LatLng point
     * @return Distance between the two points in meters, or -1 if any point is null
     */
    public static double distanceInMeters(LatLng a, LatLng b) {
        if (a == null || b == null) return -1;
        final double R = 6371000; // Earth's radius in meters
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
     * Computes the positioning errors between a marker and GNSS, PDR, and WiFi locations.
     *
     * @param markerPos The reference marker position
     * @param gnssPos   GNSS-derived position (null if unavailable)
     * @param pdrPos    PDR-derived position (null if unavailable)
     * @param wifiPos   WiFi-derived position (null if unavailable)
     * @return A CalibrationErrors object containing error distances for each sensor
     */
    public static CalibrationErrors calculateCalibrationErrors(LatLng markerPos, LatLng gnssPos, LatLng pdrPos, LatLng wifiPos) {
        double gnssError = (gnssPos != null) ? distanceInMeters(markerPos, gnssPos) : -1;
        double pdrError = (pdrPos != null) ? distanceInMeters(markerPos, pdrPos) : -1;
        double wifiError = (wifiPos != null) ? distanceInMeters(markerPos, wifiPos) : -1;
        return new CalibrationErrors(gnssError, pdrError, wifiError);
    }

    /**
     * A container class that holds error values between marker and each sensor source.
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
