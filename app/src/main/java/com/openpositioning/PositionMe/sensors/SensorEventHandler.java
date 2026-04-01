package com.openpositioning.PositionMe.sensors;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.util.Log;

import com.openpositioning.PositionMe.fusion.AdaptiveQsmfiHeadingCalibrator;
import com.openpositioning.PositionMe.fusion.FusedPose;
import com.openpositioning.PositionMe.fusion.ParticleFilterManager;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.PdrProcessing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Handles sensor event dispatching for all registered movement sensors.
 *
 * <p>Extracts the switch-case logic previously in
 * {@link SensorFusion#onSensorChanged(SensorEvent)}, writing sensor values into the shared
 * {@link SensorState} and coordinating step detection with {@link PdrProcessing}.</p>
 */
public class SensorEventHandler {

    private static final float ALPHA = 0.8f;

    private final SensorFusion sensorFusion;
    private final SensorState state;
    private final PdrProcessing pdrProcessing;
    private final PathView pathView;
    private final TrajectoryRecorder recorder;
    private final ParticleFilterManager particleFilterManager;
    private final AdaptiveQsmfiHeadingCalibrator adaptiveQsmfiHeadingCalibrator;

    // Timestamp tracking
    private final HashMap<Integer, Long> lastEventTimestamps = new HashMap<>();
    private final HashMap<Integer, Integer> eventCounts = new HashMap<>();
    private long lastStepTime = 0;
    private long bootTime;

    // Acceleration magnitude buffer between steps
    private final List<Double> accelMagnitude = new ArrayList<>();

    public SensorEventHandler(SensorFusion sensorFusion,
                              SensorState state,
                              PdrProcessing pdrProcessing,
                              PathView pathView,
                              TrajectoryRecorder recorder,
                              ParticleFilterManager particleFilterManager,
                              AdaptiveQsmfiHeadingCalibrator adaptiveQsmfiHeadingCalibrator,
                              long bootTime) {
        this.sensorFusion = sensorFusion;
        this.state = state;
        this.pdrProcessing = pdrProcessing;
        this.pathView = pathView;
        this.recorder = recorder;
        this.particleFilterManager = particleFilterManager;
        this.adaptiveQsmfiHeadingCalibrator = adaptiveQsmfiHeadingCalibrator;
        this.bootTime = bootTime;
    }

    /**
     * Main dispatch method. Processes a sensor event and updates the shared {@link SensorState}.
     */
    public void handleSensorEvent(SensorEvent sensorEvent) {
        long currentTime = System.currentTimeMillis();
        int sensorType = sensorEvent.sensor.getType();

        lastEventTimestamps.put(sensorType, currentTime);
        eventCounts.put(sensorType, eventCounts.getOrDefault(sensorType, 0) + 1);

        switch (sensorType) {
            case Sensor.TYPE_ACCELEROMETER:
                state.acceleration[0] = sensorEvent.values[0];
                state.acceleration[1] = sensorEvent.values[1];
                state.acceleration[2] = sensorEvent.values[2];
                break;

            case Sensor.TYPE_PRESSURE:
                state.pressure = (1 - ALPHA) * state.pressure + ALPHA * sensorEvent.values[0];
                if (recorder.isRecording()) {
                    state.elevation = pdrProcessing.updateElevation(
                            SensorManager.getAltitude(
                                    SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                                    state.pressure
                            )
                    );

                    if (particleFilterManager != null && particleFilterManager.isEnabled()) {
                        particleFilterManager.onVerticalContextSample(
                                SystemClock.uptimeMillis(),
                                state.elevation,
                                state.elevator
                        );
                    }
                }
                break;

            // Intentional fall-through from GYROSCOPE to LINEAR_ACCELERATION.
            case Sensor.TYPE_GYROSCOPE:
                state.angularVelocity[0] = sensorEvent.values[0];
                state.angularVelocity[1] = sensorEvent.values[1];
                state.angularVelocity[2] = sensorEvent.values[2];
                if (adaptiveQsmfiHeadingCalibrator != null) {
                    adaptiveQsmfiHeadingCalibrator.onGyro(
                            sensorEvent.values[2],
                            sensorEvent.timestamp
                    );
                }

            case Sensor.TYPE_LINEAR_ACCELERATION:
                state.filteredAcc[0] = sensorEvent.values[0];
                state.filteredAcc[1] = sensorEvent.values[1];
                state.filteredAcc[2] = sensorEvent.values[2];

                double accelMagFiltered = Math.sqrt(
                        Math.pow(state.filteredAcc[0], 2)
                                + Math.pow(state.filteredAcc[1], 2)
                                + Math.pow(state.filteredAcc[2], 2)
                );
                this.accelMagnitude.add(accelMagFiltered);

                state.elevator = pdrProcessing.estimateElevator(state.gravity, state.filteredAcc);
                break;

            case Sensor.TYPE_GRAVITY:
                state.gravity[0] = sensorEvent.values[0];
                state.gravity[1] = sensorEvent.values[1];
                state.gravity[2] = sensorEvent.values[2];
                if (adaptiveQsmfiHeadingCalibrator != null) {
                    adaptiveQsmfiHeadingCalibrator.onGravity(
                            sensorEvent.values[0],
                            sensorEvent.values[1],
                            sensorEvent.values[2]
                    );
                }
                state.elevator = pdrProcessing.estimateElevator(state.gravity, state.filteredAcc);
                break;

            case Sensor.TYPE_LIGHT:
                state.light = sensorEvent.values[0];
                break;

            case Sensor.TYPE_PROXIMITY:
                state.proximity = sensorEvent.values[0];
                break;

            case Sensor.TYPE_MAGNETIC_FIELD:
                state.magneticField[0] = sensorEvent.values[0];
                state.magneticField[1] = sensorEvent.values[1];
                state.magneticField[2] = sensorEvent.values[2];
                if (adaptiveQsmfiHeadingCalibrator != null) {
                    adaptiveQsmfiHeadingCalibrator.onMagneticField(
                            sensorEvent.values[0],
                            sensorEvent.values[1],
                            sensorEvent.values[2],
                            sensorEvent.timestamp
                    );
                }
                break;

            case Sensor.TYPE_ROTATION_VECTOR:
                state.rotation = sensorEvent.values.clone();
                float[] rotationVectorDCM = new float[9];
                SensorManager.getRotationMatrixFromVector(rotationVectorDCM, state.rotation);
                SensorManager.getOrientation(rotationVectorDCM, state.orientation);
                break;

            case Sensor.TYPE_STEP_DETECTOR:
                long stepTime = SystemClock.uptimeMillis() - bootTime;

                if (currentTime - lastStepTime < 20) {
                    Log.e("SensorFusion", "Ignoring step event, too soon after last step event:"
                            + (currentTime - lastStepTime) + " ms");
                    break;
                }

                lastStepTime = currentTime;

                if (accelMagnitude.isEmpty()) {
                    Log.e("SensorFusion",
                            "stepDetection triggered, but accelMagnitude is empty! "
                                    + "This can cause updatePdr(...) to fail or return bad results.");
                } else {
                    Log.d("SensorFusion",
                            "stepDetection triggered, accelMagnitude size = " + accelMagnitude.size());
                }

                // Use the session-selected heading source.
                // The adaptive calibrator may still keep updating internally even when disabled,
                // but PDR/PF prediction only consumes it when the toggle is ON.
                float headingForPdr = sensorFusion.getSelectedHeadingRad();

                float[] newCords = this.pdrProcessing.updatePdr(
                        stepTime,
                        this.accelMagnitude,
                        headingForPdr
                );
                this.accelMagnitude.clear();

                if (adaptiveQsmfiHeadingCalibrator != null) {
                    adaptiveQsmfiHeadingCalibrator.onStepDetected(sensorEvent.timestamp);
                }

                if (particleFilterManager != null && particleFilterManager.isEnabled()) {
                    particleFilterManager.step();
                }

                if (recorder.isRecording()) {
                    float[] pointToStoreAndDraw = newCords;

                    if (particleFilterManager != null && particleFilterManager.isEnabled()) {
                        FusedPose fusedPose = particleFilterManager.getLatestFusedPose();
                        if (fusedPose != null) {
                            pointToStoreAndDraw = new float[]{
                                    (float) fusedPose.getXMeters(),
                                    (float) fusedPose.getYMeters()
                            };
                        }
                    }

                    this.pathView.drawTrajectory(pointToStoreAndDraw);
                    state.stepCounter++;

                    recorder.addPdrData(
                            SystemClock.uptimeMillis() - bootTime,
                            pointToStoreAndDraw[0],
                            pointToStoreAndDraw[1]
                    );
                }
                break;
        }
    }

    /** Utility function to log the event frequency of each sensor. */
    public void logSensorFrequencies() {
        for (int sensorType : eventCounts.keySet()) {
            Log.d("SensorFusion", "Sensor " + sensorType
                    + " | Event Count: " + eventCounts.get(sensorType));
        }
    }

    /**
     * Resets the boot time offset. Called when a new recording starts.
     */
    void resetBootTime(long newBootTime) {
        this.bootTime = newBootTime;
    }
}
