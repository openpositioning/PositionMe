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

    // FOR PARTICLE FILTER
    /**
     * Callback invoked by {@link SensorEventHandler} every time a valid step is detected.
     * The deltas are in ENU metres relative to the session origin, computed from the
     * accumulated PDR position at the time of the step event.
     */
    public interface StepListener {
        /**
         * Called once per accepted step.
         *
         * @param deltaEasting  eastward displacement of this step in metres
         * @param deltaNorthing northward displacement of this step in metres
         */
        void onStep(float deltaEasting, float deltaNorthing);
    }

    private StepListener stepListener;
    private float lastEasting = 0f;
    private float lastNorthing = 0f;
    private boolean orientationInitialized = false;

    private float lastAcceptedHeading = Float.NaN;
    private static final float MAX_HEADING_JUMP_RAD = (float) (Math.PI / 6.0);
    private static final long MIN_STEP_INTERVAL_MS = 300;

    /**
     * Registers a listener to receive per-step ENU displacement notifications.
     * The listener is invoked from the sensor thread each time a step passes all quality
     * gates (minimum interval, peak acceleration, heading outlier check).  Pass {@code null}
     * to remove a previously registered listener.
     *
     * @param listener the {@link StepListener} to notify on each accepted step, or {@code null}
     */
    public void setStepListener(StepListener listener) {
        this.stepListener = listener;
    }

    /**
     * Resets the PDR step-delta baseline to zero and clears the heading history.
     * Must be called at the start of each recording session so that the first step of the
     * new session does not fire a large spurious delta derived from the previous session's
     * accumulated PDR position.
     */
    public void resetStepOrigin() {
        lastEasting = 0f;
        lastNorthing = 0f;
        orientationInitialized = false;
        lastAcceptedHeading = Float.NaN;
    }

    // END OF PARTICLE FILTER



    private static final float ALPHA = 0.8f;
    private static final long LARGE_GAP_THRESHOLD_MS = 500;

    private final SensorState state;
    private final PdrProcessing pdrProcessing;
    private final PathView pathView;
    private final TrajectoryRecorder recorder;

    // Timestamp tracking
    private final HashMap<Integer, Long> lastEventTimestamps = new HashMap<>();
    private final HashMap<Integer, Integer> eventCounts = new HashMap<>();
    private long lastStepTime = 0;
    private long bootTime;

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

            case Sensor.TYPE_GYROSCOPE:
                state.angularVelocity[0] = sensorEvent.values[0];
                state.angularVelocity[1] = sensorEvent.values[1];
                state.angularVelocity[2] = sensorEvent.values[2];
                break;

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
                orientationInitialized = true;
                break;

            case Sensor.TYPE_STEP_DETECTOR:
                if (!orientationInitialized) break;
                long stepTime = SystemClock.uptimeMillis() - bootTime;

                long timeSinceLastStep = currentTime - lastStepTime;
                if (timeSinceLastStep < MIN_STEP_INTERVAL_MS) break;
                lastStepTime = currentTime;

                float rawHeading = state.orientation[0];
                float headingForStep;
                if (!Float.isNaN(lastAcceptedHeading)) {
                    float delta = rawHeading - lastAcceptedHeading;
                    while (delta > (float) Math.PI) delta -= (float) (2 * Math.PI);
                    while (delta < -(float) Math.PI) delta += (float) (2 * Math.PI);
                    if (Math.abs(delta) > MAX_HEADING_JUMP_RAD && timeSinceLastStep >= 2000L) {
                        headingForStep = lastAcceptedHeading;
                    } else {
                        lastAcceptedHeading = rawHeading;
                        headingForStep = rawHeading;
                    }
                } else {
                    lastAcceptedHeading = rawHeading;
                    headingForStep = rawHeading;
                }

                if (accelMagnitude.isEmpty()) {
                    break;
                }
                double peakAccel = 0.0;
                for (double v : accelMagnitude) if (v > peakAccel) peakAccel = v;
                if (peakAccel < 0.5) {
                    accelMagnitude.clear();
                    break;
                }

                float[] newCords = pdrProcessing.updatePdr(stepTime, accelMagnitude, headingForStep);
                accelMagnitude.clear();

                if (recorder.isRecording()) {
                    pathView.drawTrajectory(newCords);
                    state.stepCounter++;
                    recorder.addPdrData(SystemClock.uptimeMillis() - bootTime, newCords[0], newCords[1]);
                }

                if (stepListener != null) {
                    float deltaEasting = newCords[0] - lastEasting;
                    float deltaNorthing = newCords[1] - lastNorthing;
                    stepListener.onStep(deltaEasting, deltaNorthing);
                }

                lastEasting = newCords[0];
                lastNorthing = newCords[1];
                break;
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
    }
}
