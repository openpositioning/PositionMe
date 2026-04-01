package com.openpositioning.PositionMe.sensors;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.ParticleFilterEngine;
import com.openpositioning.PositionMe.utils.PathView;
import com.openpositioning.PositionMe.utils.PdrProcessing;
import com.openpositioning.PositionMe.utils.UtilFunctions;

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
    // Device-specific compass bias: measured north points about 40 degrees east of true north.
    private static final float TRUE_NORTH_CORRECTION_RAD = (float) Math.toRadians(-40.0);
    private static final float MAGNETIC_HEADING_BLEND = 0.18f;
    private static final float STABILIZED_HEADING_BLEND = 0.35f;
    private static final float OFFSET_BLEND_GNSS = 0.22f;
    private static final float OFFSET_BLEND_WIFI = 0.12f;
    private static final float MAGNETIC_SPIKE_REJECTION_RAD = (float) Math.toRadians(35.0);
    private static final float GYRO_TURN_CONFIRM_RAD_PER_SEC = 0.55f;
    private static final double CALIBRATION_MIN_DISTANCE_METERS = 1.6;
    private static final long CALIBRATION_MAX_GAP_MS = 12_000L;
    private static final long MIN_STEP_INTERVAL_MS = 280L;

    private final SensorState state;
    private final PdrProcessing pdrProcessing;
    private final PathView pathView;
    private final TrajectoryRecorder recorder;
    private final ParticleFilterEngine particleFilterEngine;

    // Timestamp tracking
    private final HashMap<Integer, Long> lastEventTimestamps = new HashMap<>();
    private final HashMap<Integer, Integer> eventCounts = new HashMap<>();
    private long lastStepTime = 0;
    private long bootTime;

    // Acceleration magnitude buffer between steps
    private final List<Double> accelMagnitude = new ArrayList<>();
    private float smoothedMagneticHeadingRad = Float.NaN;
    private float relativeHeadingRad = Float.NaN;
    private float headingOffsetRad = Float.NaN;
    private float stabilizedHeadingRad = Float.NaN;
    private LatLng lastGnssCalibrationPosition;
    private long lastGnssCalibrationTimestampMs;
    private LatLng lastWifiCalibrationPosition;
    private long lastWifiCalibrationTimestampMs;

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
                              long bootTime,
                              ParticleFilterEngine particleFilterEngine) {
        this.state = state;
        this.pdrProcessing = pdrProcessing;
        this.pathView = pathView;
        this.recorder = recorder;
        this.bootTime = bootTime;
        this.particleFilterEngine = particleFilterEngine;
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
                state.elevation = pdrProcessing.updateElevation(
                        SensorManager.getAltitude(
                                SensorManager.PRESSURE_STANDARD_ATMOSPHERE, state.pressure)
                );
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
                float[] absoluteOrientation = new float[3];
                SensorManager.getOrientation(rotationVectorDCM, absoluteOrientation);
                updateMagneticHeading(absoluteOrientation[0]);
                break;

            case Sensor.TYPE_GAME_ROTATION_VECTOR:
                float[] gameRotationDCM = new float[9];
                SensorManager.getRotationMatrixFromVector(gameRotationDCM, sensorEvent.values.clone());
                float[] relativeOrientation = new float[3];
                SensorManager.getOrientation(gameRotationDCM, relativeOrientation);
                updateRelativeHeading(relativeOrientation[0]);
                break;

            case Sensor.TYPE_STEP_DETECTOR:
                long stepTime = SystemClock.uptimeMillis() - bootTime;

                if (currentTime - lastStepTime < MIN_STEP_INTERVAL_MS) {
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

                    float[] newCords = this.pdrProcessing.updatePdr(
                            stepTime,
                            this.accelMagnitude,
                            state.orientation[0]
                    );

                    this.accelMagnitude.clear();

                    if (particleFilterEngine != null) {
                        particleFilterEngine.onPdrAbsolute(
                                newCords[0],
                                newCords[1],
                                currentTime,
                                state.orientation[0]
                        );
                    }

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
        lastGnssCalibrationPosition = null;
        lastGnssCalibrationTimestampMs = 0L;
        lastWifiCalibrationPosition = null;
        lastWifiCalibrationTimestampMs = 0L;
        smoothedMagneticHeadingRad = Float.NaN;
        relativeHeadingRad = Float.NaN;
        headingOffsetRad = Float.NaN;
        stabilizedHeadingRad = Float.NaN;
    }

    public void updateHeadingCalibrationFromGnss(Location location) {
        if (location == null) {
            return;
        }

        long timestampMillis = location.getTime() > 0L
                ? location.getTime()
                : System.currentTimeMillis();
        if (location.hasBearing() && (!location.hasSpeed() || location.getSpeed() >= 0.7f)) {
            applyObservedHeading(
                    normalizeRadians((float) Math.toRadians(location.getBearing())),
                    OFFSET_BLEND_GNSS
            );
        }
        float accuracyMeters = location.hasAccuracy() ? location.getAccuracy() : 25f;
        float blend = accuracyMeters <= 10f
                ? OFFSET_BLEND_GNSS
                : accuracyMeters <= 20f
                ? 0.16f
                : 0.08f;
        LatLng position = new LatLng(location.getLatitude(), location.getLongitude());
        LatLng previous = lastGnssCalibrationPosition;
        long previousTimestamp = lastGnssCalibrationTimestampMs;
        lastGnssCalibrationPosition = position;
        lastGnssCalibrationTimestampMs = timestampMillis;
        updateHeadingCalibrationFromPosition(previous, previousTimestamp, position, timestampMillis, blend);
    }

    public void updateHeadingCalibrationFromWifi(LatLng wifiLocation, long timestampMillis) {
        if (wifiLocation == null) {
            return;
        }
        LatLng previous = lastWifiCalibrationPosition;
        long previousTimestamp = lastWifiCalibrationTimestampMs;
        lastWifiCalibrationPosition = wifiLocation;
        lastWifiCalibrationTimestampMs = timestampMillis;
        updateHeadingCalibrationFromPosition(
                previous,
                previousTimestamp,
                wifiLocation,
                timestampMillis,
                OFFSET_BLEND_WIFI
        );
    }

    private float normalizeRadians(float radians) {
        float value = radians;
        while (value > Math.PI) {
            value -= (float) (2d * Math.PI);
        }
        while (value < -Math.PI) {
            value += (float) (2d * Math.PI);
        }
        return value;
    }

    private void updateMagneticHeading(float rawHeadingRad) {
        float correctedHeading = normalizeRadians(rawHeadingRad + TRUE_NORTH_CORRECTION_RAD);
        if (Float.isNaN(smoothedMagneticHeadingRad)) {
            smoothedMagneticHeadingRad = correctedHeading;
        } else {
            float delta = normalizeRadians(correctedHeading - smoothedMagneticHeadingRad);
            boolean likelyMagneticSpike = Math.abs(delta) > MAGNETIC_SPIKE_REJECTION_RAD
                    && Math.abs(state.angularVelocity[2]) < GYRO_TURN_CONFIRM_RAD_PER_SEC;
            if (!likelyMagneticSpike) {
                smoothedMagneticHeadingRad = normalizeRadians(
                        smoothedMagneticHeadingRad + MAGNETIC_HEADING_BLEND * delta
                );
            }
        }

        if (!Float.isNaN(relativeHeadingRad) && Float.isNaN(headingOffsetRad)) {
            headingOffsetRad = normalizeRadians(smoothedMagneticHeadingRad - relativeHeadingRad);
        }
        updateStabilizedHeading();
    }

    private void updateRelativeHeading(float rawRelativeHeadingRad) {
        relativeHeadingRad = normalizeRadians(rawRelativeHeadingRad);
        if (Float.isNaN(headingOffsetRad) && !Float.isNaN(smoothedMagneticHeadingRad)) {
            headingOffsetRad = normalizeRadians(smoothedMagneticHeadingRad - relativeHeadingRad);
        }
        updateStabilizedHeading();
    }

    private void updateHeadingCalibrationFromPosition(LatLng previousPosition,
                                                      long previousTimestampMillis,
                                                      LatLng currentPosition,
                                                      long currentTimestampMillis,
                                                      float blend) {
        if (previousPosition == null) {
            return;
        }
        if (currentTimestampMillis - previousTimestampMillis > CALIBRATION_MAX_GAP_MS) {
            return;
        }

        double distanceMeters = UtilFunctions.distanceBetweenPoints(previousPosition, currentPosition);
        if (distanceMeters < CALIBRATION_MIN_DISTANCE_METERS) {
            return;
        }

        applyObservedHeading(
                computeHeadingRadians(previousPosition, currentPosition),
                blend
        );
    }

    private void applyObservedHeading(float observedHeadingRad, float blend) {
        if (!Float.isNaN(relativeHeadingRad)) {
            float observedOffset = normalizeRadians(observedHeadingRad - relativeHeadingRad);
            if (Float.isNaN(headingOffsetRad)) {
                headingOffsetRad = observedOffset;
            } else {
                headingOffsetRad = normalizeRadians(
                        circleBlend(headingOffsetRad, observedOffset, blend)
                );
            }
        } else if (Float.isNaN(smoothedMagneticHeadingRad)) {
            smoothedMagneticHeadingRad = observedHeadingRad;
        } else {
            smoothedMagneticHeadingRad = normalizeRadians(
                    circleBlend(smoothedMagneticHeadingRad, observedHeadingRad, blend)
            );
        }
        updateStabilizedHeading();
    }

    private void updateStabilizedHeading() {
        float targetHeading;
        if (!Float.isNaN(relativeHeadingRad) && !Float.isNaN(headingOffsetRad)) {
            targetHeading = normalizeRadians(relativeHeadingRad + headingOffsetRad);
        } else if (!Float.isNaN(smoothedMagneticHeadingRad)) {
            targetHeading = smoothedMagneticHeadingRad;
        } else if (!Float.isNaN(relativeHeadingRad)) {
            targetHeading = relativeHeadingRad;
        } else {
            return;
        }

        if (Float.isNaN(stabilizedHeadingRad)) {
            stabilizedHeadingRad = targetHeading;
        } else {
            stabilizedHeadingRad = normalizeRadians(
                    circleBlend(stabilizedHeadingRad, targetHeading, STABILIZED_HEADING_BLEND)
            );
        }
        state.orientation[0] = stabilizedHeadingRad;
    }

    private float circleBlend(float from, float to, float alpha) {
        float delta = normalizeRadians(to - from);
        return from + alpha * delta;
    }

    private float computeHeadingRadians(LatLng from, LatLng to) {
        double deltaNorth = (to.latitude - from.latitude) * 111_111d;
        double deltaEast = (to.longitude - from.longitude)
                * 111_111d
                * Math.cos(Math.toRadians((from.latitude + to.latitude) * 0.5d));
        return normalizeRadians((float) Math.atan2(deltaEast, deltaNorth));
    }
}
