package com.openpositioning.PositionMe.Fusion;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.sensors.SensorFusion;
import com.openpositioning.PositionMe.utils.CoordinateTransform;

import java.util.ArrayList;
import java.util.List;

public class BatchOptimizer implements FusionAlgorithm {
    private List<float[]> trajectory = new ArrayList<>();
    private float[] lastPos = new float[]{0f, 0f};

    @Override
    public void init(float[] initialPos) {
        trajectory.clear();
        lastPos = initialPos;
        trajectory.add(initialPos);
    }

    @Override
    public void predict(float[] delta) {
        lastPos = new float[]{lastPos[0] + delta[0], lastPos[1] + delta[1]};
        trajectory.add(lastPos);
    }

    @Override
    public void updateFromGnss(float[] gnssPos) {
        // Blend GNSS with last trajectory point
        lastPos = new float[]{
                (lastPos[0] + gnssPos[0]) / 2,
                (lastPos[1] + gnssPos[1]) / 2
        };
        trajectory.set(trajectory.size() - 1, lastPos);
    }

    @Override
    public void updateFromWifi(float[] wifiPos) {
        updateFromGnss(wifiPos); // Same logic
    }

    @Override
    public float[] getFusedPosition() {
        return lastPos;
    }

    @Override
    public void onOpportunisticUpdate(double east, double north, boolean isGNSS, long refTime) {
        // Blend new opportunistic update into position (e.g., WiFi or GNSS)
        float e = (float) east;
        float n = (float) north;

        lastPos = new float[]{
                (lastPos[0] + e) / 2,
                (lastPos[1] + n) / 2
        };

        // Update last point in trajectory
        if (!trajectory.isEmpty()) {
            trajectory.set(trajectory.size() - 1, lastPos);
        }

        // Convert to LatLng and notify UI
        double[] startRef = SensorFusion.getInstance().getGNSSLatLngAlt(true);
        double[] ecefRef = SensorFusion.getInstance().getEcefRefCoords();

        LatLng fusedLatLng = CoordinateTransform.enuToGeodetic(
                lastPos[0], lastPos[1], 0,
                startRef[0], startRef[1], ecefRef
        );
        SensorFusion.getInstance().notifyFusedUpdate(fusedLatLng);
    }

    @Override
    public void onStepDetected(double pdrEast, double pdrNorth, double altitude, long refTime) {
        // Treat step detection as a forward movement
        lastPos = new float[]{(float) pdrEast, (float) pdrNorth};
        trajectory.add(lastPos);

        // Notify UI
        double[] startRef = SensorFusion.getInstance().getGNSSLatLngAlt(true);
        double[] ecefRef = SensorFusion.getInstance().getEcefRefCoords();

        LatLng fusedLatLng = CoordinateTransform.enuToGeodetic(
                lastPos[0], lastPos[1], altitude,
                startRef[0], startRef[1], ecefRef
        );
        SensorFusion.getInstance().notifyFusedUpdate(fusedLatLng);
    }

    @Override
    public double[] getState() {
        return new double[]{0, lastPos[0], lastPos[1]}; // bearing, x, y
    }

    @Override
    public void stopFusion() {
        // No thread to stop in this version
    }
}
