package com.openpositioning.PositionMe.sensors;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.util.Log;

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
    private static final long LARGE_GAP_THRESHOLD_MS = 500;

    // Heading stabilization constants
    private static final float HEADING_LPF_ALPHA = 0.85f; // slight smoothing, fast turn response
    private static final float MAG_CORRECTION_ALPHA = 0.005f; // slow magnetometer drift correction

    private final SensorState state;
    private final PdrProcessing pdrProcessing;
    private final PathView pathView;
    private final TrajectoryRecorder recorder;

    // Timestamp tracking
    private final HashMap<Integer, Long> lastEventTimestamps = new HashMap<>();
    private final HashMap<Integer, Integer> eventCounts = new HashMap<>();
    private long lastStepTime = 0;
    private long bootTime;

    // Heading stabilization state
    private float smoothedHeading = Float.NaN;  // low-pass filtered heading

    // Acceleration magnitude buffer between steps
    private final List<Double> accelMagnitude = new ArrayList<>();

    /**
     * Creates a new SensorEventHandler.
     *
     * @param state         shared sensor state holder
     * @param pdrProcessing PDR processor for step-length and position calculation
     * @param pathView      path drawing view for trajectory visualisation
     * @param recorder      trajectory recorder for checking recording state and writing PDR data
     * @param bootTime      initial boot time offset
     */
    public SensorEventHandler(SensorState state, PdrProcessing pdrProcessing,
                              PathView pathView, TrajectoryRecorder recorder,
                              long bootTime) {
        this.state = state;
        this.pdrProcessing = pdrProcessing;
        this.pathView = pathView;
        this.recorder = recorder;
        this.bootTime = bootTime;
    }

    /**
     * Main dispatch method. Processes a sensor event and updates the shared {@link SensorState}.
     *
     * @param sensorEvent the sensor event to process
     */
    public void handleSensorEvent(SensorEvent sensorEvent) {
        long currentTime = System.currentTimeMillis();
        int sensorType = sensorEvent.sensor.getType();

        Long lastTimestamp = lastEventTimestamps.get(sensorType);
        if (lastTimestamp != null) {
            long timeGap = currentTime - lastTimestamp;
        }

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
                                    SensorManager.PRESSURE_STANDARD_ATMOSPHERE, state.pressure)
                    );
                }
                break;

            // NOTE: intentional fall-through from GYROSCOPE to LINEAR_ACCELERATION
            // (existing behavior preserved during refactoring)
            case Sensor.TYPE_GYROSCOPE:
                state.angularVelocity[0] = sensorEvent.values[0];
                state.angularVelocity[1] = sensorEvent.values[1];
                state.angularVelocity[2] = sensorEvent.values[2];

            case Sensor.TYPE_LINEAR_ACCELERATION:
                state.filteredAcc[0] = sensorEvent.values[0];
                state.filteredAcc[1] = sensorEvent.values[1];
                state.filteredAcc[2] = sensorEvent.values[2];

                double accelMagFiltered = Math.sqrt(
                        Math.pow(state.filteredAcc[0], 2) +
                                Math.pow(state.filteredAcc[1], 2) +
                                Math.pow(state.filteredAcc[2], 2)
                );
                this.accelMagnitude.add(accelMagFiltered);

                state.elevator = pdrProcessing.estimateElevator(
                        state.gravity, state.filteredAcc);
                break;

            case Sensor.TYPE_GRAVITY:
                state.gravity[0] = sensorEvent.values[0];
                state.gravity[1] = sensorEvent.values[1];
                state.gravity[2] = sensorEvent.values[2];

                state.elevator = pdrProcessing.estimateElevator(
                        state.gravity, state.filteredAcc);
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
                break;

            case Sensor.TYPE_ROTATION_VECTOR:
                state.rotation = sensorEvent.values.clone();
                float[] rotationVectorDCM = new float[9];
                SensorManager.getRotationMatrixFromVector(rotationVectorDCM, state.rotation);
                SensorManager.getOrientation(rotationVectorDCM, state.orientation);
                break;

            case Sensor.TYPE_GAME_ROTATION_VECTOR: {
                float[] gameDCM = new float[9];
                float[] gameOrientation = new float[3];
                SensorManager.getRotationMatrixFromVector(gameDCM, sensorEvent.values);
                SensorManager.getOrientation(gameDCM, gameOrientation);
                state.gameHeading = gameOrientation[0];

                if (Float.isNaN(state.headingOffset)) {
                    // First calibration: set offset from magnetometer
                    state.headingOffset = state.orientation[0] - state.gameHeading;
                }
                // Note: continuous mag correction disabled indoors — indoor magnetic
                // fields are too distorted and pull the offset in the wrong direction.
                // The game rotation vector (gyro) is stable enough for indoor sessions.
                break;
            }

            case Sensor.TYPE_STEP_DETECTOR:
                long stepTime = SystemClock.uptimeMillis() - bootTime;

                if (currentTime - lastStepTime < 20) {
                    Log.e("SensorFusion", "Ignoring step event, too soon after last step event:"
                            + (currentTime - lastStepTime) + " ms");
                    break;
                } else {
                    lastStepTime = currentTime;

                    if (accelMagnitude.isEmpty()) {
                        Log.e("SensorFusion",
                                "stepDetection triggered, but accelMagnitude is empty! " +
                                        "This can cause updatePdr(...) to fail or return bad results.");
                    } else {
                        Log.d("SensorFusion",
                                "stepDetection triggered, accelMagnitude size = "
                                        + accelMagnitude.size());
                    }

                    // Compute raw heading: gyro-based with magnetometer offset
                    // Use gyro heading directly — no LPF, no delay on turns
                    float headingForPdr = Float.isNaN(state.headingOffset)
                            ? state.orientation[0]
                            : state.gameHeading + state.headingOffset;

                    float[] newCords = this.pdrProcessing.updatePdr(
                            stepTime,
                            this.accelMagnitude,
                            headingForPdr
                    );

                    this.accelMagnitude.clear();

                    if (recorder.isRecording()) {
                        this.pathView.drawTrajectory(newCords);
                        state.stepCounter++;
                        recorder.addPdrData(
                                SystemClock.uptimeMillis() - bootTime,
                                newCords[0], newCords[1]);
                    }
                    break;
                }
        }
    }

    /**
     * Utility function to log the event frequency of each sensor.
     * Call this periodically for debugging purposes.
     */
    public void logSensorFrequencies() {
        for (int sensorType : eventCounts.keySet()) {
            Log.d("SensorFusion", "Sensor " + sensorType
                    + " | Event Count: " + eventCounts.get(sensorType));
        }
    }

    /**
     * Resets the boot time offset. Called when a new recording starts.
     *
     * @param newBootTime the new boot time offset from {@link SystemClock#uptimeMillis()}
     */
    void resetBootTime(long newBootTime) {
        this.bootTime = newBootTime;
        // Reset heading stabilization state for new recording
        smoothedHeading = Float.NaN;
    }

    /** Wraps an angle difference to [-π, π]. */
    private static float wrapAngle(float rad) {
        while (rad > Math.PI) rad -= 2 * Math.PI;
        while (rad < -Math.PI) rad += 2 * Math.PI;
        return rad;
    }
}
