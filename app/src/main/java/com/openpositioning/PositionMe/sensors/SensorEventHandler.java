package com.openpositioning.PositionMe.sensors;

import static com.openpositioning.PositionMe.BuildConstants.DEBUG;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.util.Log;

import com.openpositioning.PositionMe.sensors.fusion.FusionManager;
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

    private final SensorState state;
    private final PdrProcessing pdrProcessing;
    private final PathView pathView;
    private final TrajectoryRecorder recorder;
    private FusionManager fusionManager;

    // Timestamp tracking
    private final HashMap<Integer, Long> lastEventTimestamps = new HashMap<>();
    private final HashMap<Integer, Integer> eventCounts = new HashMap<>();
    private long lastStepTime = 0;
    private long bootTime;

    private final List<Double> accelMagnitude = new ArrayList<>();

    // Movement gating: reject false steps when stationary
    private static final double MOVEMENT_THRESHOLD = 0.4; // m/s², variance below = stationary
    private int validStepCount = 0;

    // Heading debug logging
    private long lastHeadingLogTime = 0;

    // Heading stuck detection
    private int consecutiveNorthSteps = 0;
    private static final double NORTH_THRESHOLD_DEG = 15.0;

    // Barometer floor tracking
    private int lastBaroFloor = 0;

    // Heading calibration: offset between TYPE_ROTATION_VECTOR (magnetic north)
    // and TYPE_GAME_ROTATION_VECTOR (arbitrary reference).
    // Computed once at startup, then applied as a fixed correction.
    private float headingCalibrationOffset = 0;
    private boolean headingCalibrated = false;
    private float lastMagneticHeading = 0; // latest TYPE_ROTATION_VECTOR azimuth (radians)
    private boolean magneticHeadingReady = false;
    private int calibrationSampleCount = 0;
    private static final int CALIBRATION_SAMPLES_NEEDED = 5; // average over 5 readings for stability
    private volatile boolean pdrPaused = false; // pause PDR during initial calibration

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

    /** Injects the fusion manager so step events can drive the particle filter. */
    public void setFusionManager(FusionManager fusionManager) {
        this.fusionManager = fusionManager;
    }

    /**
     * Pauses or resumes PDR step processing (heading calibration continues).
     *
     * @param paused true to pause, false to resume
     */
    public void setPdrPaused(boolean paused) { this.pdrPaused = paused; }

    /**
     * Returns whether PDR step processing is currently paused.
     *
     * @return true if paused
     */
    public boolean isPdrPaused() { return pdrPaused; }



    /**
     * Resets heading calibration so the offset between GAME_ROTATION_VECTOR and
     * magnetic north is recomputed from the next sensor events. Must be called
     * whenever sensor listeners are re-registered because GAME_ROTATION_VECTOR
     * may change its arbitrary reference frame on re-registration.
     */
    public void resetHeadingCalibration() {
        headingCalibrated = false;
        headingCalibrationOffset = 0;
        calibrationSampleCount = 0;
        magneticHeadingReady = false;
        if (DEBUG) Log.i("HEADING", "Calibration RESET — will recalibrate on next sensor events");
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
                    // Forward barometer-derived floor to particle filter
                    int baroFloor = pdrProcessing.getCurrentFloor();
                    // Log elevation every ~5 seconds for debugging
                    if (DEBUG && System.currentTimeMillis() % 5000 < 1100) {
                        Log.d("PDR", String.format("[BaroStatus] elevation=%.2fm baroFloor=%d lastBaro=%d",
                                state.elevation, baroFloor, lastBaroFloor));
                    }
                    if (baroFloor != lastBaroFloor) {
                        if (DEBUG) Log.i("PDR", String.format("[BaroFloor] CHANGED baroFloor=%d prev=%d elevation=%.2fm",
                                baroFloor, lastBaroFloor, state.elevation));
                        lastBaroFloor = baroFloor;
                    }
                    // Always forward to fusion manager so its 2-second confirmation
                    // timer can be evaluated on every barometer reading
                    if (fusionManager != null && fusionManager.isActive()) {
                        fusionManager.onFloorChanged(baroFloor);
                    }
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
                // Magnetic rotation vector — used ONLY for initial heading calibration.
                // Provides absolute north reference but susceptible to indoor magnetic distortion.
                float[] magDCM = new float[9];
                SensorManager.getRotationMatrixFromVector(magDCM, sensorEvent.values);
                float[] magOri = new float[3];
                SensorManager.getOrientation(magDCM, magOri);
                lastMagneticHeading = magOri[0];
                magneticHeadingReady = true;

                // Compute calibration offset: average first N samples for stability
                if (!headingCalibrated && state.orientation[0] != 0) {
                    float rawDiff = lastMagneticHeading - state.orientation[0];
                    // Normalize to [-π, π]
                    while (rawDiff > Math.PI) rawDiff -= 2 * Math.PI;
                    while (rawDiff < -Math.PI) rawDiff += 2 * Math.PI;

                    calibrationSampleCount++;
                    // Exponential moving average for stability
                    if (calibrationSampleCount == 1) {
                        headingCalibrationOffset = rawDiff;
                    } else {
                        headingCalibrationOffset = headingCalibrationOffset * 0.8f + rawDiff * 0.2f;
                    }

                    if (calibrationSampleCount >= CALIBRATION_SAMPLES_NEEDED) {
                        headingCalibrated = true;
                        if (DEBUG) Log.i("HEADING", String.format(
                                "CALIBRATED offset=%.1f° (mag=%.1f° game=%.1f°) after %d samples",
                                Math.toDegrees(headingCalibrationOffset),
                                Math.toDegrees(lastMagneticHeading),
                                Math.toDegrees(state.orientation[0]),
                                calibrationSampleCount));
                    }
                }
                break;

            case Sensor.TYPE_GAME_ROTATION_VECTOR:
                state.rotation = sensorEvent.values.clone();
                float[] rotationVectorDCM = new float[9];
                SensorManager.getRotationMatrixFromVector(rotationVectorDCM, state.rotation);
                SensorManager.getOrientation(rotationVectorDCM, state.orientation);

                // Apply heading calibration offset (magnetic north correction)
                if (headingCalibrated) {
                    state.orientation[0] += headingCalibrationOffset;
                    // Normalize to [-π, π]
                    if (state.orientation[0] > Math.PI) state.orientation[0] -= 2 * (float) Math.PI;
                    if (state.orientation[0] < -Math.PI) state.orientation[0] += 2 * (float) Math.PI;
                }

                // Log heading once per second
                if (DEBUG && currentTime - lastHeadingLogTime > 1000) {
                    lastHeadingLogTime = currentTime;
                    float correctedDeg = (float) Math.toDegrees(state.orientation[0]);
                    float magDeg = magneticHeadingReady
                            ? (float) Math.toDegrees(lastMagneticHeading) : Float.NaN;
                    Log.i("HEADING", String.format(
                            "corrected=%.1f° offset=%.1f° calibrated=%b mag=%.1f°",
                            correctedDeg,
                            Math.toDegrees(headingCalibrationOffset),
                            headingCalibrated, magDeg));
                }
                break;

            case Sensor.TYPE_STEP_DETECTOR:
                // Skip PDR during calibration — heading calibration still runs
                if (pdrPaused) break;

                long stepTime = SystemClock.uptimeMillis() - bootTime;

                // Debounce: ignore steps fired < 20ms apart (hardware double-fire)
                if (currentTime - lastStepTime < 20) {
                    break;
                }
                lastStepTime = currentTime;

                // Movement gating: check if recent acceleration indicates real walking
                boolean isMoving = true;
                if (accelMagnitude.size() >= 5) {
                    double sum = 0, sumSq = 0;
                    for (Double a : accelMagnitude) {
                        sum += a;
                        sumSq += a * a;
                    }
                    double mean = sum / accelMagnitude.size();
                    double variance = sumSq / accelMagnitude.size() - mean * mean;
                    isMoving = variance > MOVEMENT_THRESHOLD;

                    if (!isMoving) {
                        if (DEBUG) Log.d("PDR", String.format("[Step] REJECTED (stationary) var=%.3f < thresh=%.1f",
                                variance, MOVEMENT_THRESHOLD));
                        accelMagnitude.clear();
                        break;
                    }
                }

                // Process PDR update
                float[] newCords = this.pdrProcessing.updatePdr(
                        stepTime,
                        this.accelMagnitude,
                        state.orientation[0]
                );
                this.accelMagnitude.clear();

                if (recorder.isRecording()) {
                    validStepCount++;
                    this.pathView.drawTrajectory(newCords);
                    state.stepCounter++;
                    recorder.addPdrData(
                            SystemClock.uptimeMillis() - bootTime,
                            newCords[0], newCords[1]);

                    float stepLen = pdrProcessing.getLastStepLength();
                    float headingRad = state.orientation[0];
                    float headingDeg = (float) Math.toDegrees(headingRad);
                    float deltaX = (float) (stepLen * Math.sin(headingRad));
                    float deltaY = (float) (stepLen * Math.cos(headingRad));

                    if (DEBUG) Log.i("PDR", String.format(
                            "[Step] idx=%d isMoving=%b heading=%.1f° len=%.2fm " +
                            "delta=(%.3f,%.3f) pos=(%.2f,%.2f) rot=[%.3f,%.3f,%.3f]",
                            validStepCount, isMoving, headingDeg, stepLen,
                            deltaX, deltaY, newCords[0], newCords[1],
                            state.orientation[0], state.orientation[1], state.orientation[2]));

                    // Heading stuck detection
                    if (Math.abs(headingDeg) < NORTH_THRESHOLD_DEG
                            || Math.abs(headingDeg) > (360 - NORTH_THRESHOLD_DEG)) {
                        consecutiveNorthSteps++;
                        if (consecutiveNorthSteps >= 10) {
                            if (DEBUG) Log.w("PDR", String.format(
                                    "[HEADING_STUCK] %d consecutive steps near North (heading=%.1f°) — possible sensor issue!",
                                    consecutiveNorthSteps, headingDeg));
                        }
                    } else {
                        consecutiveNorthSteps = 0;
                    }

                    // Feed the particle filter
                    if (fusionManager != null) {
                        fusionManager.onPdrStep(stepLen, headingRad);
                    }
                }
                break;
        }
    }

    /**
     * Utility function to log the event frequency of each sensor.
     * Call this periodically for debugging purposes.
     */
    public void logSensorFrequencies() {
        if (DEBUG) {
            for (int sensorType : eventCounts.keySet()) {
                Log.d("SensorFusion", "Sensor " + sensorType
                        + " | Event Count: " + eventCounts.get(sensorType));
            }
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
