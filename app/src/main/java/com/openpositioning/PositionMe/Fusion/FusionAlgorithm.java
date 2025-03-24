package com.openpositioning.PositionMe.Fusion;

public interface FusionAlgorithm {
    void init(float[] initialPos);
    void predict(float[] delta); // from PDR step
    void updateFromGnss(float[] gnssPos);
    void updateFromWifi(float[] wifiPos);
    float[] getFusedPosition(); // current estimate
}
