package com.openpositioning.PositionMe.dataParser;


/**
 * Model class for a GNSS (Global Navigation Satellite System) data sample.
 */
public class GnssData {
    private long relativeTimestamp;
    private double latitude;
    private double longitude;
    private double altitude;
    private double accuracy;
    private String provider;

    public GnssData(long relativeTimestamp, double latitude, double longitude,
                    double altitude, double accuracy, String provider) {
        this.relativeTimestamp = relativeTimestamp;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.accuracy = accuracy;
        this.provider = provider;
    }

    public long getRelativeTimestamp() {
        return relativeTimestamp;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public double getAltitude() {
        return altitude;
    }

    public double getAccuracy() {
        return accuracy;
    }

    public String getProvider() {
        return provider;
    }

    @Override
    public String toString() {
        return "GnssData{" +
                "relativeTimestamp=" + relativeTimestamp +
                ", latitude=" + latitude +
                ", longitude=" + longitude +
                ", altitude=" + altitude +
                ", accuracy=" + accuracy +
                ", provider='" + provider + '\'' +
                '}';
    }
}