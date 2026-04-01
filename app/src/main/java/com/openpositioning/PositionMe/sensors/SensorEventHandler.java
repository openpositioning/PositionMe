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

    private static final String TAG = "HEADING_DEBUG";
    private static final float ALPHA = 0.8f;
    private static final long LARGE_GAP_THRESHOLD_MS = 500;

    private final SensorState state;
    private final PdrProcessing pdrProcessing;
    private final PathView pathView;
    private final TrajectoryRecorder recorder;
    private final PositionFusion positionFusion;

    // Timestamp tracking
    private final HashMap<Integer, Long> lastEventTimestamps = new HashMap<>();
    private final HashMap<Integer, Integer> eventCounts = new HashMap<>();
    private long lastStepTime = 0;
    private long bootTime;

    // Acceleration magnitude buffer between steps
    private final List<Double> accelMagnitude = new ArrayList<>();

    // ===================== Software Step Detection =====================
    /** Acceleration magnitude threshold to detect a step peak. */
    private static final double STEP_THRESHOLD = 2.05;
    /** Minimum interval between two steps in milliseconds. */
    private static final long MIN_STEP_INTERVAL_MS = 300;
    /** Whether the acceleration is currently above the threshold (rising phase). */
    private boolean aboveThreshold = false;

    // Previous PDR position for computing delta to feed into fusion
    private float prevPdrX = 0;
    private float prevPdrY = 0;

    /**
     * Creates a new SensorEventHandler.
     *
     * @param state           shared sensor state holder
     * @param pdrProcessing   PDR processor for step-length and position calculation
     * @param pathView        path drawing view for trajectory visualisation
     * @param recorder        trajectory recorder for checking recording state and writing PDR data
     * @param positionFusion  position fusion engine for multi-source fusion
     * @param bootTime        initial boot time offset
     */
    public SensorEventHandler(SensorState state, PdrProcessing pdrProcessing,
                              PathView pathView, TrajectoryRecorder recorder,
                              PositionFusion positionFusion, long bootTime) {
        this.state = state;
        this.pdrProcessing = pdrProcessing;
        this.pathView = pathView;
        this.recorder = recorder;
        this.positionFusion = positionFusion;
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

                // Software step detection: detect peak (rising above threshold then falling below)
                if (!aboveThreshold && accelMagFiltered > STEP_THRESHOLD) {
                    aboveThreshold = true;
                } else if (aboveThreshold && accelMagFiltered < STEP_THRESHOLD) {
                    aboveThreshold = false;
                    // Peak detected — trigger step if enough time has passed
                    if (currentTime - lastStepTime >= MIN_STEP_INTERVAL_MS
                            && accelMagnitude.size() >= 2) {
                        lastStepTime = currentTime;
                        processStep(SystemClock.uptimeMillis() - bootTime, currentTime);
                    }
                }
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
                // Log heading periodically (every ~50th event to avoid spam)
                int rotCount = eventCounts.getOrDefault(Sensor.TYPE_ROTATION_VECTOR, 0);
                if (rotCount % 50 == 0) {
                    float magAzDeg = (float) Math.toDegrees(state.orientation[0]);
                    float gameAzDeg = (float) Math.toDegrees(state.gameOrientation[0]);
                    float delta = magAzDeg - gameAzDeg;
                    // Normalize delta to [-180, 180]
                    if (delta > 180) delta -= 360;
                    if (delta < -180) delta += 360;

                    Log.e(TAG, "Heading: azimuth=" + String.format("%.1f", magAzDeg)
                            + "deg | pitch=" + String.format("%.1f", Math.toDegrees(state.orientation[1]))
                            + "deg | roll=" + String.format("%.1f", Math.toDegrees(state.orientation[2]))
                            + "deg | rotVec=[" + String.format("%.3f,%.3f,%.3f",
                            state.rotation[0], state.rotation[1], state.rotation[2]) + "]");
                    Log.e("MAG_DIAG", "magHeading=" + String.format("%.1f", magAzDeg)
                            + "deg | gameHeading=" + String.format("%.1f", gameAzDeg)
                            + "deg | delta=" + String.format("%.1f", delta) + "deg"
                            + (Math.abs(delta) > 15 ? " *** MAGNETIC INTERFERENCE ***" : ""));

                    // Log raw magnetometer magnitude for anomaly detection
                    float magMagnitude = (float) Math.sqrt(
                            state.magneticField[0] * state.magneticField[0]
                            + state.magneticField[1] * state.magneticField[1]
                            + state.magneticField[2] * state.magneticField[2]);
                    Log.e("MAG_DIAG", "magField=[" + String.format("%.1f,%.1f,%.1f",
                            state.magneticField[0], state.magneticField[1], state.magneticField[2])
                            + "] magnitude=" + String.format("%.1f", magMagnitude) + "uT"
                            + (magMagnitude > 100 ? " *** ABNORMAL ***" : ""));

                    Log.e("MAG_DIAG", "calibrated=" + state.headingCalibrated
                            + " | offset=" + String.format("%.1f", Math.toDegrees(state.headingOffset)) + "deg"
                            + " | finalHeading=" + String.format("%.1f",
                                    Math.toDegrees(state.gameOrientation[0] + state.headingOffset)) + "deg");
                }

                // Auto-calibrate heading offset: mag heading → game rotation offset
                float magMag = (float) Math.sqrt(
                        state.magneticField[0] * state.magneticField[0]
                        + state.magneticField[1] * state.magneticField[1]
                        + state.magneticField[2] * state.magneticField[2]);
                if (magMag > 5) { // Need at least some magnetic data
                    float newOffset = state.orientation[0] - state.gameOrientation[0];
                    // Normalize to [-PI, PI]
                    while (newOffset > Math.PI) newOffset -= 2 * Math.PI;
                    while (newOffset < -Math.PI) newOffset += 2 * Math.PI;

                    boolean magNormal = (magMag > 25 && magMag < 80);

                    if (!state.headingCalibrated) {
                        // First calibration: use whatever mag heading is available
                        state.headingOffset = newOffset;
                        state.headingCalibrated = true;
                        Log.e("MAG_DIAG", "=== HEADING CALIBRATED (initial) === offset="
                                + String.format("%.1f", Math.toDegrees(newOffset))
                                + "deg | magMagnitude=" + String.format("%.1f", magMag) + "uT"
                                + (magNormal ? " (GOOD)" : " (NOISY)"));
                    } else if (magNormal) {
                        // Refine offset only when magnetic field is normal
                        float diff = newOffset - state.headingOffset;
                        while (diff > Math.PI) diff -= 2 * Math.PI;
                        while (diff < -Math.PI) diff += 2 * Math.PI;
                        state.headingOffset += 0.05f * diff;
                    }
                }
                break;

            case Sensor.TYPE_GAME_ROTATION_VECTOR:
                float[] gameRotDCM = new float[9];
                SensorManager.getRotationMatrixFromVector(gameRotDCM, sensorEvent.values);
                SensorManager.getOrientation(gameRotDCM, state.gameOrientation);
                break;

            case Sensor.TYPE_STEP_DETECTOR:
                // Hardware step detector disabled — using software peak detection instead
                break;
        }
    }

    /**
     * Processes a detected step: computes heading, updates PDR, feeds particle filter.
     * Called by software peak detection when a step is identified.
     *
     * @param stepTime    elapsed time since boot in milliseconds
     * @param currentTime wall-clock time from System.currentTimeMillis()
     */
    private void processStep(long stepTime, long currentTime) {
        float stepMagAz = (float) Math.toDegrees(state.orientation[0]);
        float stepGameAz = (float) Math.toDegrees(state.gameOrientation[0]);
        float stepDelta = stepMagAz - stepGameAz;
        if (stepDelta > 180) stepDelta -= 360;
        if (stepDelta < -180) stepDelta += 360;

        Log.e(TAG, "Step detected (software) | heading(azimuth)="
                + String.format("%.1f", stepMagAz) + "deg"
                + " | gameHeading=" + String.format("%.1f", stepGameAz) + "deg"
                + " | magDelta=" + String.format("%.1f", stepDelta) + "deg"
                + " | accelSamples=" + this.accelMagnitude.size());

        // Use game rotation + calibration offset for PDR heading
        float calibratedHeading = state.gameOrientation[0] + state.headingOffset;
        // Normalize to [-PI, PI] to prevent heading drift
        while (calibratedHeading > (float) Math.PI) calibratedHeading -= (float) (2 * Math.PI);
        while (calibratedHeading < (float) -Math.PI) calibratedHeading += (float) (2 * Math.PI);

        float[] newCords = this.pdrProcessing.updatePdr(
                stepTime,
                this.accelMagnitude,
                calibratedHeading
        );

        this.accelMagnitude.clear();

        if (recorder.isRecording()) {
            this.pathView.drawTrajectory(newCords);
            state.stepCounter++;
            recorder.addPdrData(
                    SystemClock.uptimeMillis() - bootTime,
                    newCords[0], newCords[1]);

            // Compute PDR delta and feed into position fusion
            float dEast = newCords[0] - prevPdrX;
            float dNorth = newCords[1] - prevPdrY;
            prevPdrX = newCords[0];
            prevPdrY = newCords[1];

            if (positionFusion != null && positionFusion.isInitialized()) {
                positionFusion.predictWithPdr(dEast, dNorth, calibratedHeading);
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
        // Reset PDR delta tracking for new recording
        this.prevPdrX = 0;
        this.prevPdrY = 0;
    }
}
