package com.openpositioning.PositionMe.sensors;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.PdrProcessing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.openpositioning.PositionMe.fusion.ParticleFilterManager;
import com.openpositioning.PositionMe.fusion.FusedPose;
import com.openpositioning.PositionMe.fusion.AdaptiveQsmfiHeadingCalibrator;


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

    // Timestamp tracking
    private final HashMap<Integer, Long> lastEventTimestamps = new HashMap<>();
    private final HashMap<Integer, Integer> eventCounts = new HashMap<>();
    private long lastStepTime = 0;
    private long bootTime;

    // Acceleration magnitude buffer between steps
    private final List<Double> accelMagnitude = new ArrayList<>();

    private final ParticleFilterManager particleFilterManager;
    private final AdaptiveQsmfiHeadingCalibrator adaptiveQsmfiHeadingCalibrator;

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
                              ParticleFilterManager particleFilterManager,
                              AdaptiveQsmfiHeadingCalibrator adaptiveQsmfiHeadingCalibrator,
                              long bootTime) {
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
                if (adaptiveQsmfiHeadingCalibrator != null) {
                    adaptiveQsmfiHeadingCalibrator.onGyro(sensorEvent.values[2], sensorEvent.timestamp);
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

                if (adaptiveQsmfiHeadingCalibrator != null) {
                    adaptiveQsmfiHeadingCalibrator.onGravity(
                            sensorEvent.values[0],
                            sensorEvent.values[1],
                            sensorEvent.values[2]
                    );
                }

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
                // Debounce very closely spaced step-detector events.
                // This protects the PDR/PF pipeline from duplicate triggers.
                if (currentTime - lastStepTime < 20) {
                    Log.e("SensorFusion", "Ignoring step event, too soon after last step event:"
                            + (currentTime - lastStepTime) + " ms");
                    break;
                } else {
                    lastStepTime = currentTime;
                    // PdrProcessing expects a buffer of acceleration magnitudes collected
                    // since the previous detected step. Log if the buffer is unexpectedly empty.
                    if (accelMagnitude.isEmpty()) {
                        Log.e("SensorFusion",
                                "stepDetection triggered, but accelMagnitude is empty! " +
                                        "This can cause updatePdr(...) to fail or return bad results.");
                    } else {
                        Log.d("SensorFusion",
                                "stepDetection triggered, accelMagnitude size = "
                                        + accelMagnitude.size());
                    }
                    // Always update the raw PDR state first.
                    //
                    // This remains true even in particle-filter mode because:
                    // - PF prediction still uses PDR as its motion input
                    // - the raw PDR output is the baseline fallback if PF has not initialised yet

                    // Choose heading source for PDR:
                    // - Prefer fused heading from QSMFI  when available
                    //   → more stable and drift-corrected
                    // - Fallback to raw orientation yaw (state.orientation[0]) if calibrator is unavailable
                    float headingForPdr = adaptiveQsmfiHeadingCalibrator != null
                            ? adaptiveQsmfiHeadingCalibrator.getFusedHeadingRad()
                            : state.orientation[0];

                    float[] newCords = this.pdrProcessing.updatePdr(
                            stepTime,
                            this.accelMagnitude,
                            headingForPdr
                    );
                    // Acceleration samples have now been consumed for this step.
                    this.accelMagnitude.clear();

                    if (adaptiveQsmfiHeadingCalibrator != null) {
                        adaptiveQsmfiHeadingCalibrator.onStepDetected(sensorEvent.timestamp);
                    }

                    if (particleFilterManager != null && particleFilterManager.isEnabled()) {
                        Log.d("SensorFusion", String.format(
                                "PF_STEP_TRIGGER stepTimeMs=%d rawPdrX=%.3f rawPdrY=%.3f headingDeg=%.2f",
                                stepTime,
                                newCords[0],
                                newCords[1],
                                Math.toDegrees(state.orientation[0])
                        ));

                        particleFilterManager.step();
                    }

                    if (recorder.isRecording()) {
                        /*
                         * Important:
                         * Raw PDR is always updated first, even in PF mode.
                         * The particle filter uses that raw step motion as its prediction input.
                         *
                         * After PF stepping:
                         * - PDR mode stores/draws raw local PDR
                         * - PF mode stores/draws PF fused local trajectory
                         */
                        float[] pointToStoreAndDraw = resolveTrajectoryPointForCurrentMode(newCords);

                        // Draw exactly the same trajectory point that will be saved,
                        // so the live local path view stays consistent with the recording payload.
                        this.pathView.drawTrajectory(pointToStoreAndDraw);
                        state.stepCounter++;

                        recorder.addPdrData(
                                SystemClock.uptimeMillis() - bootTime,
                                pointToStoreAndDraw[0],
                                pointToStoreAndDraw[1]
                        );

                        if (particleFilterManager != null && particleFilterManager.isEnabled()) {
                            FusedPose fusedPose = particleFilterManager.getLatestFusedPose();
                            if (fusedPose != null && fusedPose.getLatLng() != null) {
                                Log.d("SensorFusion",
                                        "Recorded PF trajectory point from fused pose lat="
                                                + fusedPose.getLatLng().latitude
                                                + " lng=" + fusedPose.getLatLng().longitude
                                                + " floor=" + fusedPose.getFloor()
                                                + " conf=" + fusedPose.getConfidence());
                            }
                        }
                    }
        }}}

    /**
     * Returns the point that should be drawn and recorded for the current positioning mode.
     *
     * Behaviour:
     * - PDR mode: return raw PDR coordinates directly
     * - PF mode: convert the latest fused geographic pose back into local metres
     *            relative to the recording start anchor
     * - PF not ready yet: fall back to raw PDR
     */
    @NonNull
    private float[] resolveTrajectoryPointForCurrentMode(@NonNull float[] rawPdrPoint) {
        if (particleFilterManager == null || !particleFilterManager.isEnabled()) {
            return rawPdrPoint;
        }

        FusedPose fusedPose = particleFilterManager.getLatestFusedPose();
        if (fusedPose == null || fusedPose.getLatLng() == null) {
            Log.d("SensorFusion", "PF mode enabled but fused pose not ready yet, using raw PDR point.");
            return rawPdrPoint;
        }

        float[] localPoint = convertLatLngToLocalMeters(
                fusedPose.getLatLng().latitude,
                fusedPose.getLatLng().longitude
        );

        if (localPoint == null) {
            Log.d("SensorFusion", "PF fused pose available but start GNSS anchor missing, using raw PDR point.");
            return rawPdrPoint;
        }

        Log.d("SensorFusion",
                "Using PF trajectory point x=" + localPoint[0]
                        + " y=" + localPoint[1]
                        + " floor=" + fusedPose.getFloor()
                        + " conf=" + fusedPose.getConfidence());

        return localPoint;
    }

    /**
     * Converts a geographic position into local metres relative to the recording start anchor.
     *
     * Convention used here:
     * - x = east-west metres
     * - y = north-south metres
     *
     * This matches the most common local trajectory convention used with map anchoring.
     */
    @Nullable
    private float[] convertLatLngToLocalMeters(double lat, double lng) {
        float startLat = state.startLocation[0];
        float startLng = state.startLocation[1];

        if (Math.abs(startLat) < 1e-6f && Math.abs(startLng) < 1e-6f) {
            return null;
        }

        double northMeters = (lat - startLat) * 111320.0;
        double midLatRad = Math.toRadians((lat + startLat) * 0.5);
        double eastMeters = (lng - startLng) * 111320.0 * Math.cos(midLatRad);

        return new float[] {
                (float) eastMeters,
                (float) northMeters
        };
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
