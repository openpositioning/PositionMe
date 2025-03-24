package com.openpositioning.PositionMe.Fusion;

import java.util.ArrayList;
import java.util.List;

public class BatchOptimizer implements FusionAlgorithm {
    private List<float[]> trajectory = new ArrayList<>();

    @Override
    public void init(float[] initialPos) {
        trajectory.add(initialPos);
    }

    @Override
    public void predict(float[] delta) {
        float[] last = trajectory.get(trajectory.size() - 1);
        trajectory.add(new float[]{last[0] + delta[0], last[1] + delta[1]});
    }

    @Override
    public void updateFromGnss(float[] gnssPos) {
        // Placeholder: Replace last point with average
        float[] last = trajectory.get(trajectory.size() - 1);
        trajectory.set(trajectory.size() - 1,
                new float[]{(last[0] + gnssPos[0]) / 2, (last[1] + gnssPos[1]) / 2});
    }

    @Override
    public void updateFromWifi(float[] wifiPos) {
        updateFromGnss(wifiPos);
    }

    @Override
    public float[] getFusedPosition() {
        return trajectory.get(trajectory.size() - 1);
    }
}