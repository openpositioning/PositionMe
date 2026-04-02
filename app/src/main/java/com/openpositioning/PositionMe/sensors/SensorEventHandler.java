package com.openpositioning.PositionMe.sensors;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.util.Log;

import com.openpositioning.PositionMe.data.remote.FloorplanApiClient;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.PdrProcessing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import com.google.android.gms.maps.model.LatLng;

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
    private static final float MAP_MATCH_SUBSTEP_METERS = 0.75f;
    private static final int MAX_MAP_MATCH_SUBDIVISIONS = 2;
    private static final long TURN_CONFIRM_WINDOW_MS = 850;
    private static final float TURN_CONFIRM_GYRO_THRESHOLD_RAD_S = 0.6f;
    private final SensorState state;
    private final PdrProcessing pdrProcessing;
    private final PathView pathView;
    private final TrajectoryRecorder recorder;

    // Timestamp tracking
    private final HashMap<Integer, Long> lastEventTimestamps = new HashMap<>();
    private final HashMap<Integer, Integer> eventCounts = new HashMap<>();
    private long lastStepTime = 0;
    private long lastHeadingMotionTimestamp = 0;
    private long bootTime;

    // Acceleration magnitude buffer between steps
    private final List<Double> accelMagnitude = new ArrayList<>();
    // Track the previous raw PDR position to compute per-step deltas.
    private float lastPdrX = 0f;
    private float lastPdrY = 0f;

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
                if (Math.abs(sensorEvent.values[2]) >= TURN_CONFIRM_GYRO_THRESHOLD_RAD_S) {
                    lastHeadingMotionTimestamp = currentTime;
                }
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
                    float[] rawPdrCords = this.pdrProcessing.updatePdr(
                            stepTime,
                            this.accelMagnitude,
                            state.orientation[0],
                            state.angularVelocity[2],
                            currentTime - lastHeadingMotionTimestamp <= TURN_CONFIRM_WINDOW_MS
                    );
                    this.accelMagnitude.clear();
                    // Derive the raw PDR delta for this detected step.
                    float deltaX = rawPdrCords[0] - lastPdrX;
                    float deltaY = rawPdrCords[1] - lastPdrY;

                    // Persist the raw PDR position for the next step.
                    lastPdrX = rawPdrCords[0];
                    lastPdrY = rawPdrCords[1];

                    // Run prediction and map matching before drawing the fused position.
                    com.openpositioning.PositionMe.fusion.ParticleFilter pf = SensorFusion.getInstance().getParticleFilter();
                    float[] finalCordsToDraw = rawPdrCords;
                    if (pf != null && (deltaX != 0 || deltaY != 0)) {
                        // Split larger moves into a small number of map-matching substeps.
                        int subdivisions = Math.max(1, Math.min(MAX_MAP_MATCH_SUBDIVISIONS,
                                (int) Math.ceil(Math.hypot(deltaX, deltaY) / MAP_MATCH_SUBSTEP_METERS)));

                        // Apply area-based horizontal motion scaling and wall constraints.
                        List<FloorplanApiClient.MapShapeFeature> walls = SensorFusion.getInstance().getCurrentWalls();
                        LatLng startLocation = new LatLng(state.startLocation[0], state.startLocation[1]);
                        float horizontalMovementScale = pf.getHorizontalMovementScale(
                                walls, startLocation, state.elevator);
                        float adjustedDeltaX = deltaX * horizontalMovementScale;
                        float adjustedDeltaY = deltaY * horizontalMovementScale;
                        for (int i = 0; i < subdivisions; i++) {
                            pf.predict(new com.openpositioning.PositionMe.fusion.PDRMovement(adjustedDeltaX, adjustedDeltaY), subdivisions);
                            pf.applyMapMatching(walls, startLocation);
                        }

                        // Draw the fused position instead of the raw PDR position.
                        com.openpositioning.PositionMe.fusion.Position fusedPosition =
                                pf.getEstimatedPosition(walls, startLocation);

                        // Debug logging for comparing raw PDR and particle-filter output.
                        finalCordsToDraw = new float[]{fusedPosition.x, fusedPosition.y};

                        // Debug logging for comparing raw PDR and particle-filter output.
                        Log.d("ParticleFilter", "X: " + rawPdrCords[0] + " Y: " + rawPdrCords[1]);
                        Log.d("ParticleFilter", "X: " + fusedPosition.x + " Y: " + fusedPosition.y);
                    }

                    if (recorder.isRecording()) {
                        // Draw the fused trajectory on screen.
                        this.pathView.drawTrajectory(finalCordsToDraw);

                        state.stepCounter++;
                        // Keep the recorded PDR trace in the raw local coordinate frame.
                        recorder.addPdrData(
                                SystemClock.uptimeMillis() - bootTime,
                                rawPdrCords[0], rawPdrCords[1]);
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
    }

}
