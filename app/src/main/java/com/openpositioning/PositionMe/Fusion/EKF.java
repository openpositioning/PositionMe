package com.openpositioning.PositionMe.Fusion;

public class EKF implements FusionAlgorithm {
    private float[] state = new float[4]; // [x, y, vx, vy]
    private float[][] P = new float[4][4]; // Covariance matrix

    @Override
    public void init(float[] initialPos) {
        state[0] = initialPos[0];
        state[1] = initialPos[1];
    }

    @Override
    public void predict(float[] delta) {
        state[0] += delta[0];
        state[1] += delta[1];
    }

    @Override
    public void updateFromGnss(float[] gnssPos) {
        state[0] = (state[0] + gnssPos[0]) / 2;
        state[1] = (state[1] + gnssPos[1]) / 2;
    }

    @Override
    public void updateFromWifi(float[] wifiPos) {
        state[0] = (state[0] + wifiPos[0]) / 2;
        state[1] = (state[1] + wifiPos[1]) / 2;
    }

    @Override
    public float[] getFusedPosition() {
        return new float[]{state[0], state[1]};
    }
}