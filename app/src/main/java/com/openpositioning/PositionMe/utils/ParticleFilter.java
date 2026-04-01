package com.openpositioning.PositionMe.utils;

import com.openpositioning.PositionMe.data.remote.FloorPlan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Particle filter used to fuse relative PDR motion with absolute GNSS/WiFi fixes.
 *
 * Position, heading, and floor are all particle state. PDR only predicts. Absolute observations
 * only update particle weights.
 *
 * New code guide:
 * 1. Initialization around a preferred absolute fix.
 * 2. Prediction using PDR motion plus map constraints.
 * 3. Absolute measurement updates from GNSS/WiFi.
 * 4. Floor transitions through stairs/lift access points.
 * 5. State extraction and resampling helpers.
 */
public class ParticleFilter {

    // Labels the source of an absolute update so noise can be tuned per sensor.
    public enum MeasurementType {
        GNSS,
        WIFI
    }

    public static final class Estimate {
        private final float x;
        private final float y;
        private final float headingRad;
        private final int floor;

        // Stores one fused state estimate from the particle cloud.
        Estimate(float x, float y, float headingRad, int floor) {
            this.x = x;
            this.y = y;
            this.headingRad = headingRad;
            this.floor = floor;
        }

        // Returns the estimated xy position.
        public float[] getPositionXY() {
            return new float[]{x, y};
        }

        // Returns the estimated heading in radians.
        public float getHeadingRad() {
            return headingRad;
        }

        // Returns the estimated floor number.
        public int getFloor() {
            return floor;
        }
    }

    private static final float DEFAULT_INIT_STD_METERS = 1.2f;
    private static final float STEP_NOISE_STD_METERS = 0.18f;
    private static final float HEADING_NOISE_STD_RAD = 0.12f;
    private static final float RESAMPLE_POSITION_JITTER_STD_METERS = 0.08f;
    private static final float RESAMPLE_HEADING_JITTER_STD_RAD = 0.04f;
    private static final float MIN_PARTICLE_WEIGHT = 1e-6f;
    private static final float RESAMPLE_ESS_RATIO = 0.55f;
    private static final float DEFAULT_WIFI_STD_METERS = 3.8f;
    private static final float DEFAULT_GNSS_STD_METERS = 18.0f;
    private static final float MAX_INIT_STD_METERS = 8.0f;
    private static final float WRONG_FLOOR_PENALTY = 0.08f;
    private static final float ADJACENT_FLOOR_PENALTY = 0.45f;
    private static final float OBSERVED_FLOOR_INIT_SHARE = 0.72f;
    private static final float PREFERRED_FLOOR_INIT_SHARE = 0.58f;
    private static final float ADJACENT_FLOOR_INIT_SHARE = 0.24f;
    private static final float MAP_CORRECTION_MARGIN_METERS = 0.18f;

    private final int numParticles;
    private float[] particlesX;
    private float[] particlesY;
    private float[] headingsRad;
    private int[] floors;
    private final float[] weights;
    private final Random random;

    private boolean initialized;

    // Creates the particle arrays and fills them with defaults.
    public ParticleFilter(int numParticles) {
        this.numParticles = numParticles;
        this.particlesX = new float[numParticles];
        this.particlesY = new float[numParticles];
        this.headingsRad = new float[numParticles];
        this.floors = new int[numParticles];
        this.weights = new float[numParticles];
        this.random = new Random();
        reset();
    }

    // Resets all particles back to a uniform empty state.
    public void reset() {
        float uniformWeight = 1.0f / numParticles;
        for (int i = 0; i < numParticles; i++) {
            particlesX[i] = 0f;
            particlesY[i] = 0f;
            headingsRad[i] = 0f;
            floors[i] = 0;
            weights[i] = uniformWeight;
        }
        initialized = false;
    }

    // Reports whether the filter has received an initial state.
    public boolean isInitialized() {
        return initialized;
    }

    // Spreads particles around the first trusted anchor and an initial floor hypothesis.
    public void initialize(float centerX,
                           float centerY,
                           float headingRad,
                           int floor,
                           float positionStdMeters) {
        initialize(
                centerX,
                centerY,
                headingRad,
                Integer.valueOf(floor),
                Integer.valueOf(floor),
                null,
                positionStdMeters
        );
    }

    // Initializes particles using optional preferred and observed floors.
    public void initialize(float centerX,
                           float centerY,
                           float headingRad,
                           Integer preferredFloor,
                           Integer observedFloor,
                           Map<Integer, FloorPlan> floorPlansByFloor,
                           float positionStdMeters) {
        float initStd = clamp(positionStdMeters, 0.2f, MAX_INIT_STD_METERS);
        float uniformWeight = 1.0f / numParticles;
        List<Integer> candidateFloors = candidateFloors(preferredFloor, observedFloor, floorPlansByFloor);
        for (int i = 0; i < numParticles; i++) {
            particlesX[i] = centerX + sampleGaussian(initStd);
            particlesY[i] = centerY + sampleGaussian(initStd);
            headingsRad[i] = normalizeAngleRad(headingRad + sampleGaussian(HEADING_NOISE_STD_RAD));
            floors[i] = sampleInitialFloor(candidateFloors, preferredFloor, observedFloor);
            weights[i] = uniformWeight;
        }
        initialized = true;
    }

    // Uses a default spread when only one floor guess is given.
    public void initialize(float centerX, float centerY, float headingRad, int floor) {
        initialize(centerX, centerY, headingRad, floor, DEFAULT_INIT_STD_METERS);
    }

    // Predicts one motion step with the default confidence value.
    public void predict(float stepLengthMeters,
                        float measuredHeadingRad,
                        Map<Integer, FloorPlan> floorPlansByFloor,
                        MapMatchingEngine mapMatchingEngine) {
        predict(stepLengthMeters, measuredHeadingRad, floorPlansByFloor, mapMatchingEngine, 0.5f);
    }

    // Moves particles using PDR motion and optional map correction.
    public void predict(float stepLengthMeters,
                        float measuredHeadingRad,
                        Map<Integer, FloorPlan> floorPlansByFloor,
                        MapMatchingEngine mapMatchingEngine,
                        float predictionConfidence) {
        if (!initialized) {
            return;
        }

        float confidence = clamp(predictionConfidence, 0f, 1f);
        float stepNoiseStd = clamp(
                STEP_NOISE_STD_METERS * (1.18f - 0.48f * confidence),
                0.08f,
                STEP_NOISE_STD_METERS * 1.6f
        );
        float headingNoiseStd = clamp(
                HEADING_NOISE_STD_RAD * (1.18f - 0.52f * confidence),
                0.045f,
                HEADING_NOISE_STD_RAD * 1.6f
        );
        float weightSum = 0f;
        for (int i = 0; i < numParticles; i++) {
            float startX = particlesX[i];
            float startY = particlesY[i];
            FloorPlan floorPlan = resolveFloorPlan(floorPlansByFloor, floors[i]);

            float noisyHeading = normalizeAngleRad(measuredHeadingRad + sampleGaussian(headingNoiseStd));
            float noisyStep = Math.max(0f, stepLengthMeters + sampleGaussian(stepNoiseStd));
            float endX = startX + noisyStep * (float) Math.sin(noisyHeading);
            float endY = startY + noisyStep * (float) Math.cos(noisyHeading);

            float transitionScore = 1f;
            if (mapMatchingEngine != null && floorPlan != null) {
                float[] correctedEnd = mapMatchingEngine.correctMovementAgainstWalls(
                        new float[]{startX, startY},
                        new float[]{endX, endY},
                        floorPlan,
                        MAP_CORRECTION_MARGIN_METERS
                );
                float correctionDistance = distanceMeters(new float[]{endX, endY}, correctedEnd);
                float correctionPenalty = clamp(
                        1f - 0.35f * correctionDistance / Math.max(0.4f, noisyStep + MAP_CORRECTION_MARGIN_METERS),
                        0.55f,
                        1f
                );
                endX = correctedEnd[0];
                endY = correctedEnd[1];
                transitionScore = mapMatchingEngine.transitionScore(
                        new float[]{startX, startY},
                        new float[]{endX, endY},
                        floorPlan,
                        false,
                        false
                ) * correctionPenalty;
            }

            particlesX[i] = endX;
            particlesY[i] = endY;
            headingsRad[i] = noisyHeading;
            weights[i] = Math.max(MIN_PARTICLE_WEIGHT, weights[i] * transitionScore);
            weightSum += weights[i];
        }

        normalizeWeights(weightSum);
        maybeResample();
    }

    // Reweights particles against an absolute observation without moving them directly.
    public void update(float measurementX,
                       float measurementY,
                       float measurementAccuracyMeters,
                       Integer measurementFloor,
                       MeasurementType type,
                       Map<Integer, FloorPlan> floorPlansByFloor,
                       MapMatchingEngine mapMatchingEngine) {
        if (!initialized) {
            initialize(
                    measurementX,
                    measurementY,
                    0f,
                    measurementFloor,
                    measurementFloor,
                    floorPlansByFloor,
                    initialSpreadForMeasurement(measurementAccuracyMeters, type)
            );
            return;
        }

        float measurementStd = measurementStd(measurementAccuracyMeters, type);
        float variance = Math.max(0.25f, measurementStd * measurementStd);
        float weightSum = 0f;

        for (int i = 0; i < numParticles; i++) {
            float dx = particlesX[i] - measurementX;
            float dy = particlesY[i] - measurementY;
            float distanceSq = dx * dx + dy * dy;
            float likelihood = (float) Math.exp(-distanceSq / (2f * variance));

            FloorPlan floorPlan = resolveFloorPlan(floorPlansByFloor, floors[i]);
            float legalityScore = 1f;
            if (mapMatchingEngine != null && floorPlan != null) {
                legalityScore = mapMatchingEngine.stateSupportScore(
                        new float[]{particlesX[i], particlesY[i]},
                        floorPlan
                );
            }

            float floorScore = floorLikelihood(floors[i], measurementFloor);
            weights[i] = Math.max(MIN_PARTICLE_WEIGHT, weights[i] * likelihood * legalityScore * floorScore);
            weightSum += weights[i];
        }

        normalizeWeights(weightSum);
        maybeResample();
    }

    // Attempts a floor jump only when both source and target floors support the transition.
    public boolean applyFloorChange(int delta,
                                    boolean elevatorMode,
                                    Map<Integer, FloorPlan> floorPlansByFloor,
                                    MapMatchingEngine mapMatchingEngine) {
        if (!initialized || delta == 0 || mapMatchingEngine == null || floorPlansByFloor == null || floorPlansByFloor.isEmpty()) {
            return false;
        }

        boolean anyAccepted = false;
        float weightSum = 0f;
        float[] nextParticlesX = particlesX.clone();
        float[] nextParticlesY = particlesY.clone();
        int[] nextFloors = floors.clone();
        float[] nextWeights = weights.clone();

        for (int i = 0; i < numParticles; i++) {
            int sourceFloor = floors[i];
            int targetFloor = sourceFloor + delta;
            FloorPlan sourcePlan = resolveFloorPlan(floorPlansByFloor, sourceFloor);
            FloorPlan targetPlan = resolveFloorPlan(floorPlansByFloor, targetFloor);
            if (sourcePlan == null || targetPlan == null) {
                nextWeights[i] = Math.max(MIN_PARTICLE_WEIGHT, weights[i] * WRONG_FLOOR_PENALTY);
                weightSum += nextWeights[i];
                continue;
            }

            float[] pos = new float[]{particlesX[i], particlesY[i]};
            float[] sourceAccessPoint = mapMatchingEngine.snapToVerticalAccess(pos, elevatorMode, sourcePlan);
            float[] targetAccessPoint = mapMatchingEngine.snapToVerticalAccess(sourceAccessPoint, elevatorMode, targetPlan);
            float sourceTransitionScore = mapMatchingEngine.verticalTransitionScore(sourceAccessPoint, elevatorMode, sourcePlan);
            float targetTransitionScore = mapMatchingEngine.verticalTransitionScore(targetAccessPoint, elevatorMode, targetPlan);
            float targetSupportScore = mapMatchingEngine.stateSupportScore(targetAccessPoint, targetPlan);
            float combinedScore = sourceTransitionScore
                    * targetTransitionScore
                    * Math.max(0.5f, targetSupportScore);

            if (combinedScore >= 0.6f) {
                nextFloors[i] = targetFloor;
                nextParticlesX[i] = targetAccessPoint[0];
                nextParticlesY[i] = targetAccessPoint[1];
                anyAccepted = true;
            }

            nextWeights[i] = Math.max(MIN_PARTICLE_WEIGHT, weights[i] * combinedScore);
            weightSum += nextWeights[i];
        }

        if (!anyAccepted) {
            return false;
        }

        particlesX = nextParticlesX;
        particlesY = nextParticlesY;
        floors = nextFloors;
        System.arraycopy(nextWeights, 0, weights, 0, numParticles);
        normalizeWeights(weightSum);
        maybeResample();
        return true;
    }

    // Collapses the weighted cloud into one display-ready state estimate.
    public Estimate getEstimatedState() {
        if (!initialized) {
            return new Estimate(0f, 0f, 0f, 0);
        }

        float sumWeights = 0f;
        float meanX = 0f;
        float meanY = 0f;
        float sinHeading = 0f;
        float cosHeading = 0f;
        Map<Integer, Float> floorWeight = new HashMap<>();

        for (int i = 0; i < numParticles; i++) {
            float w = weights[i];
            sumWeights += w;
            meanX += particlesX[i] * w;
            meanY += particlesY[i] * w;
            sinHeading += (float) Math.sin(headingsRad[i]) * w;
            cosHeading += (float) Math.cos(headingsRad[i]) * w;
            floorWeight.put(floors[i], floorWeight.getOrDefault(floors[i], 0f) + w);
        }

        if (sumWeights <= 0f) {
            return new Estimate(0f, 0f, 0f, 0);
        }

        int bestFloor = 0;
        float bestFloorWeight = Float.NEGATIVE_INFINITY;
        for (Map.Entry<Integer, Float> entry : floorWeight.entrySet()) {
            if (entry.getValue() > bestFloorWeight) {
                bestFloorWeight = entry.getValue();
                bestFloor = entry.getKey();
            }
        }

        float heading = (float) Math.atan2(sinHeading, cosHeading);
        return new Estimate(meanX / sumWeights, meanY / sumWeights, heading, bestFloor);
    }

    // Returns only the current estimated xy position.
    public float[] getEstimatedPosition() {
        return getEstimatedState().getPositionXY();
    }

    // Looks up the floor plan for one floor number.
    private FloorPlan resolveFloorPlan(Map<Integer, FloorPlan> floorPlansByFloor, int floor) {
        if (floorPlansByFloor == null || floorPlansByFloor.isEmpty()) {
            return null;
        }
        return floorPlansByFloor.get(floor);
    }

    // Builds the candidate floor list used during initialization.
    private List<Integer> candidateFloors(Integer preferredFloor,
                                          Integer observedFloor,
                                          Map<Integer, FloorPlan> floorPlansByFloor) {
        List<Integer> candidates = new ArrayList<>();
        if (floorPlansByFloor != null && !floorPlansByFloor.isEmpty()) {
            candidates.addAll(floorPlansByFloor.keySet());
            Collections.sort(candidates);
        }
        if (observedFloor != null && !candidates.contains(observedFloor)) {
            candidates.add(observedFloor);
        }
        if (preferredFloor != null && !candidates.contains(preferredFloor)) {
            candidates.add(preferredFloor);
        }
        if (candidates.isEmpty()) {
            candidates.add(observedFloor != null ? observedFloor : (preferredFloor != null ? preferredFloor : 0));
        }
        Collections.sort(candidates);
        return candidates;
    }

    // Draws one initial floor sample from the candidate list.
    private int sampleInitialFloor(List<Integer> candidateFloors,
                                   Integer preferredFloor,
                                   Integer observedFloor) {
        if (candidateFloors == null || candidateFloors.isEmpty()) {
            return observedFloor != null ? observedFloor : (preferredFloor != null ? preferredFloor : 0);
        }
        if (candidateFloors.size() == 1) {
            return candidateFloors.get(0);
        }

        Integer primaryFloor = observedFloor != null ? observedFloor : preferredFloor;
        if (primaryFloor != null && candidateFloors.contains(primaryFloor)) {
            float primaryShare = observedFloor != null ? OBSERVED_FLOOR_INIT_SHARE : PREFERRED_FLOOR_INIT_SHARE;
            float adjacentShare = ADJACENT_FLOOR_INIT_SHARE;
            float draw = random.nextFloat();
            if (draw < primaryShare) {
                return primaryFloor;
            }

            List<Integer> adjacentFloors = adjacentFloors(candidateFloors, primaryFloor);
            if (!adjacentFloors.isEmpty() && draw < primaryShare + adjacentShare) {
                return adjacentFloors.get(random.nextInt(adjacentFloors.size()));
            }
        }

        return candidateFloors.get(random.nextInt(candidateFloors.size()));
    }

    // Collects floors that are directly above or below the reference floor.
    private List<Integer> adjacentFloors(List<Integer> candidateFloors, int referenceFloor) {
        List<Integer> adjacent = new ArrayList<>();
        for (Integer floor : candidateFloors) {
            if (floor != null && Math.abs(floor - referenceFloor) == 1) {
                adjacent.add(floor);
            }
        }
        return adjacent;
    }

    // Scores how well a particle floor matches the measured floor.
    private float floorLikelihood(int particleFloor, Integer measurementFloor) {
        if (measurementFloor == null) {
            return 1f;
        }
        if (particleFloor == measurementFloor) {
            return 1f;
        }
        if (Math.abs(particleFloor - measurementFloor) == 1) {
            return ADJACENT_FLOOR_PENALTY;
        }
        return WRONG_FLOOR_PENALTY;
    }

    // Chooses the standard deviation for one absolute measurement.
    private float measurementStd(float measurementAccuracyMeters, MeasurementType type) {
        float fallback = type == MeasurementType.GNSS ? DEFAULT_GNSS_STD_METERS : DEFAULT_WIFI_STD_METERS;
        if (Float.isNaN(measurementAccuracyMeters) || measurementAccuracyMeters <= 0f) {
            return fallback;
        }
        float minStd = type == MeasurementType.GNSS ? 10f : 2.2f;
        float maxStd = type == MeasurementType.GNSS ? 45f : 16f;
        return clamp(measurementAccuracyMeters, minStd, maxStd);
    }

    // Derives a reasonable initial spread from measurement accuracy.
    private float initialSpreadForMeasurement(float measurementAccuracyMeters, MeasurementType type) {
        return clamp(measurementStd(measurementAccuracyMeters, type) * 0.65f, 0.6f, MAX_INIT_STD_METERS);
    }

    // Computes the distance between two xy points.
    private float distanceMeters(float[] a, float[] b) {
        if (a == null || b == null || a.length < 2 || b.length < 2) {
            return 0f;
        }
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // Normalizes all particle weights so they sum to one.
    private void normalizeWeights(float weightSum) {
        if (weightSum <= 0f || Float.isNaN(weightSum) || Float.isInfinite(weightSum)) {
            float uniformWeight = 1.0f / numParticles;
            for (int i = 0; i < numParticles; i++) {
                weights[i] = uniformWeight;
            }
            return;
        }

        for (int i = 0; i < numParticles; i++) {
            weights[i] /= weightSum;
        }
    }

    // Resamples only when the effective particle count is too low.
    private void maybeResample() {
        float ess = effectiveSampleSize();
        if (ess / numParticles < RESAMPLE_ESS_RATIO) {
            resample();
        }
    }

    // Computes the effective sample size from the current weights.
    private float effectiveSampleSize() {
        float sumSq = 0f;
        for (float weight : weights) {
            sumSq += weight * weight;
        }
        if (sumSq <= 0f) {
            return 0f;
        }
        return 1f / sumSq;
    }

    // Uses systematic resampling to keep particle diversity stable at low cost.
    private void resample() {
        float[] newParticlesX = new float[numParticles];
        float[] newParticlesY = new float[numParticles];
        float[] newHeadingsRad = new float[numParticles];
        int[] newFloors = new int[numParticles];

        float step = 1f / numParticles;
        float r = random.nextFloat() * step;
        float c = weights[0];
        int i = 0;

        for (int m = 0; m < numParticles; m++) {
            float u = r + m * step;
            while (u > c && i < numParticles - 1) {
                i++;
                c += weights[i];
            }
            newParticlesX[m] = particlesX[i] + sampleGaussian(RESAMPLE_POSITION_JITTER_STD_METERS);
            newParticlesY[m] = particlesY[i] + sampleGaussian(RESAMPLE_POSITION_JITTER_STD_METERS);
            newHeadingsRad[m] = normalizeAngleRad(headingsRad[i] + sampleGaussian(RESAMPLE_HEADING_JITTER_STD_RAD));
            newFloors[m] = floors[i];
        }

        particlesX = newParticlesX;
        particlesY = newParticlesY;
        headingsRad = newHeadingsRad;
        floors = newFloors;

        float uniformWeight = 1.0f / numParticles;
        for (int j = 0; j < numParticles; j++) {
            weights[j] = uniformWeight;
        }
    }

    // Draws Gaussian noise with the given standard deviation.
    private float sampleGaussian(float std) {
        return (float) (random.nextGaussian() * std);
    }

    // Wraps angles into the normal -pi to pi range.
    private float normalizeAngleRad(float angleRad) {
        while (angleRad > Math.PI) {
            angleRad -= (float) (2.0 * Math.PI);
        }
        while (angleRad < -Math.PI) {
            angleRad += (float) (2.0 * Math.PI);
        }
        return angleRad;
    }

    // Limits a value to the given range.
    private float clamp(float value, float min, float max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
