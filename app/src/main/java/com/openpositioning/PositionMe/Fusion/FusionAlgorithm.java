package com.openpositioning.PositionMe.Fusion;

public interface FusionAlgorithm {
    void init(float[] initialPos);
    void predict(float[] delta); // from PDR step
    void updateFromGnss(float[] gnssPos);
    void updateFromWifi(float[] wifiPos);
    float[] getFusedPosition(); // current estimate
    void onOpportunisticUpdate(double east, double north, boolean isGNSS, long refTime);
    void onStepDetected(double pdrEast, double pdrNorth, double altitude, long refTime);
    double[] getState();
    void stopFusion();
}
