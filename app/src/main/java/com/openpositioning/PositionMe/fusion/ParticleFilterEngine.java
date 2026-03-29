package com.openpositioning.PositionMe.fusion;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.mapmatching.CandidatePose;
import com.openpositioning.PositionMe.utils.IndoorMapManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Core particle filter engine for live indoor positioning.
 *
 * Design goals:
 * - Keep the engine independent from Fragment/UI code.
 * - Support a hybrid strategy:
 *   1) prediction from motion
 *   2) optional map-constraint penalty / rejection
 *   3) optional absolute observation weighting (GNSS / WiFi / matched pose)
 *   4) resampling
 *   5) fused state extraction
 *
 * Important:
 * This engine is intentionally conservative.
 * It does NOT immediately kill every particle near a wall unless the transition is clearly impossible.
 * That makes debugging and convergence much more stable in early integration.
 */
public class ParticleFilterEngine {

    private static final String TAG = "ParticleFilterEngine";

    /**
     * Individual particle.
     */
    public static class Particle {
        public double lat;
        public double lng;
        public double headingRad;
        public int logicalFloor;
        public double weight;
        public boolean alive = true;

        public Particle(double lat,
                        double lng,
                        double headingRad,
                        int logicalFloor,
                        double weight) {
            this.lat = lat;
            this.lng = lng;
            this.headingRad = headingRad;
            this.logicalFloor = logicalFloor;
            this.weight = weight;
        }

        public Particle copy() {
            Particle p = new Particle(lat, lng, headingRad, logicalFloor, weight);
            p.alive = alive;
            return p;
        }

        public LatLng toLatLng() {
            return new LatLng(lat, lng);
        }
    }

    /**
     * Motion input for one PF update.
     * This should usually come from PDR / IMU integration.
     */
    public static class MotionInput {
        public final double deltaForwardMeters;
        public final double deltaHeadingRad;
        public final double deltaHeightMeters;
        public final boolean heightChanged;
        public final long timestampMs;

        public MotionInput(double deltaForwardMeters,
                           double deltaHeadingRad,
                           double deltaHeightMeters,
                           boolean heightChanged,
                           long timestampMs) {
            this.deltaForwardMeters = deltaForwardMeters;
            this.deltaHeadingRad = deltaHeadingRad;
            this.deltaHeightMeters = deltaHeightMeters;
            this.heightChanged = heightChanged;
            this.timestampMs = timestampMs;
        }
    }

    /**
     * Optional absolute observation.
     * Can be GNSS, WiFi, or map-matched pose.
     */
    public static class AbsoluteObservation {
        @Nullable public final LatLng latLng;
        @Nullable public final Integer logicalFloor;
        @Nullable public final Double headingRad;
        public final double horizontalSigmaMeters;
        public final double headingSigmaRad;
        public final double confidence;
        public final String source;

        public AbsoluteObservation(@Nullable LatLng latLng,
                                   @Nullable Integer logicalFloor,
                                   @Nullable Double headingRad,
                                   double horizontalSigmaMeters,
                                   double headingSigmaRad,
                                   double confidence,
                                   @NonNull String source) {
            this.latLng = latLng;
            this.logicalFloor = logicalFloor;
            this.headingRad = headingRad;
            this.horizontalSigmaMeters = horizontalSigmaMeters;
            this.headingSigmaRad = headingSigmaRad;
            this.confidence = confidence;
            this.source = source;
        }
    }

    /**
     * Optional map-constraint helper.
     *
     * You can back this with:
     * - IndoorMapManager
     * - wall polygons / line segments
     * - connector / stairs / lift regions
     * - floor transition rules
     */
    public interface ConstraintModel {

        /**
         * Returns true if the particle state is inside a valid walkable region.
         */
        boolean isWalkable(double lat, double lng, int logicalFloor);

        /**
         * Returns true if the transition from old->new is blocked by a wall / invalid barrier.
         */
        boolean crossesWall(double oldLat, double oldLng,
                            double newLat, double newLng,
                            int logicalFloor);

        /**
         * Returns true if changing from oldFloor to newFloor is allowed near this location.
         * Usually this means stairs / lift / connector region.
         */
        boolean isFloorTransitionAllowed(double lat, double lng, int oldFloor, int newFloor);

        /**
         * Returns a soft map-consistency factor in [0, 1].
         * 1 = strong agreement with map
         * 0 = impossible according to map
         */
        double mapLikelihood(double lat, double lng, int logicalFloor);

        /**
         * Optional nearest valid pose for recovery if particles collapse.
         */
        @Nullable
        CandidatePose getRecoveryPose(@NonNull LatLng currentLatLng, int logicalFloor);
    }

    /**
     * Tunable configuration for the PF.
     */
    public static class Config {
        public int particleCount = 250;

        // Prediction noise
        public double forwardNoiseStdMeters = 0.25;
        public double headingNoiseStdRad = Math.toRadians(6.0);

        // Initial spread
        public double initialPositionStdMeters = 1.0;
        public double initialHeadingStdRad = Math.toRadians(12.0);

        // Map-constraint behaviour
        public boolean enableMapConstraints = true;
        public boolean hardKillOnWallCross = true;
        public boolean hardKillOnInvalidFloorTransition = true;
        public boolean softPenaltyForOutOfWalkable = true;

        public double outOfWalkablePenalty = 0.10;
        public double wallCrossPenalty = 0.02;
        public double invalidFloorPenalty = 0.02;

        // Observation blending
        public boolean enableAbsoluteObservationWeighting = true;
        public double minimumWeightFloor = 1e-12;
        public double observationSigmaWifiMeters = 2.5;
        public double observationSigmaGnssMeters = 5.0;

        // Degeneracy / recovery
        public double resampleEffectiveSampleSizeRatio = 0.45;
        public boolean enableRecoveryIfCollapsed = true;
        public int recoverySeedCount = 40;
        public double resampleRegularizationPosStdMeters = 0.03;
        public double resampleRegularizationHeadingStdRad = Math.toRadians(1.0);

        // Logging
        public boolean debugLogging = true;
    }

    /**
     * Summary of one update step for easier debugging.
     */
    public static class StepDiagnostics {
        public int totalParticles;
        public int aliveParticles;
        public int wallRejectedCount;
        public int floorRejectedCount;
        public int outOfWalkablePenalisedCount;
        public int observationWeightedCount;
        public double effectiveSampleSize;
        public boolean resampled;
        public boolean recovered;
    }

    /**
     * Result of one PF update.
     */
    public static class StepResult {
        @NonNull public final FusedPose fusedPose;
        @NonNull public final StepDiagnostics diagnostics;
        @NonNull public final List<Particle> particlesSnapshot;

        public StepResult(@NonNull FusedPose fusedPose,
                          @NonNull StepDiagnostics diagnostics,
                          @NonNull List<Particle> particlesSnapshot) {
            this.fusedPose = fusedPose;
            this.diagnostics = diagnostics;
            this.particlesSnapshot = particlesSnapshot;
        }
    }

    private final Config config;
    private final Random random;
    private final List<Particle> particles = new ArrayList<>();

    @Nullable
    private ConstraintModel constraintModel;
    @Nullable private LatLng referenceOriginLatLng;
    private double referenceOriginLat = Double.NaN;
    private double referenceOriginLng = Double.NaN;

    public ParticleFilterEngine(@NonNull Config config) {
        this.config = config;
        this.random = new Random();
    }

    public void setConstraintModel(@Nullable ConstraintModel constraintModel) {
        this.constraintModel = constraintModel;
    }

    public boolean isInitialised() {
        return !particles.isEmpty();
    }

    public void clear() {
        particles.clear();
        referenceOriginLatLng = null;
        referenceOriginLat = Double.NaN;
        referenceOriginLng = Double.NaN;
    }

    /**
     * Initialise particles around a known anchor.
     */
    public void initialise(@NonNull LatLng startLatLng,
                           int logicalFloor,
                           double headingRad) {
        particles.clear();

        referenceOriginLatLng = startLatLng;
        referenceOriginLat = startLatLng.latitude;
        referenceOriginLng = startLatLng.longitude;

        double initialSpreadMeters = config.initialPositionStdMeters;
        double headingSpreadRad = config.initialHeadingStdRad;

        for (int i = 0; i < config.particleCount; i++) {
            double dNorth = gaussian(0.0, initialSpreadMeters);
            double dEast = gaussian(0.0, initialSpreadMeters);

            LatLng perturbed = offsetLatLngMeters(startLatLng, dNorth, dEast);

            double particleHeading = wrapAngleRad(headingRad + gaussian(0.0, headingSpreadRad));

            Particle particle = new Particle(
                    perturbed.latitude,
                    perturbed.longitude,
                    particleHeading,
                    logicalFloor,
                    1.0 / config.particleCount
            );

            if (constraintModel != null && config.enableMapConstraints) {
                if (!constraintModel.isWalkable(particle.lat, particle.lng, logicalFloor)) {
                    if (config.softPenaltyForOutOfWalkable) {
                        particle.weight *= config.outOfWalkablePenalty;
                    }
                }
            }

            particles.add(particle);
        }

        normaliseWeights();
        log(String.format(Locale.US,
                "Initialised PF with %d particles at %.6f, %.6f floor=%d",
                particles.size(), startLatLng.latitude, startLatLng.longitude, logicalFloor));
    }

    /**
     * Main PF update.
     */
    @NonNull
    public StepResult update(@NonNull MotionInput motionInput,
                             @Nullable AbsoluteObservation absoluteObservation) {

        if (particles.isEmpty()) {
            throw new IllegalStateException("ParticleFilterEngine.update() called before initialise().");
        }

        StepDiagnostics diagnostics = new StepDiagnostics();
        diagnostics.totalParticles = particles.size();

        predictParticles(motionInput, diagnostics);
        applyConstraintWeights(motionInput, diagnostics);

        if (absoluteObservation != null && config.enableAbsoluteObservationWeighting) {
            applyAbsoluteObservation(absoluteObservation, diagnostics);
        }

        enforceWeightFloor();
        normaliseWeights();

        diagnostics.aliveParticles = countAliveParticles();
        diagnostics.effectiveSampleSize = computeEffectiveSampleSize();

        if (shouldResample(diagnostics.effectiveSampleSize)) {
            systematicResample();
            diagnostics.resampled = true;
        }

        if (countAliveParticles() == 0 || totalWeight() <= 0.0) {
            if (config.enableRecoveryIfCollapsed) {
                recoverParticles();
                diagnostics.recovered = true;
            }
        }

        normaliseWeights();
        FusedPose fusedPose = buildFusedPose();
        List<Particle> snapshot = deepCopyParticles();

        if (config.debugLogging) {
            Log.d(TAG, String.format(Locale.US,
                    "PF step: alive=%d/%d wallReject=%d floorReject=%d walkPenalty=%d obsWeighted=%d ess=%.2f resampled=%s recovered=%s",
                    diagnostics.aliveParticles,
                    diagnostics.totalParticles,
                    diagnostics.wallRejectedCount,
                    diagnostics.floorRejectedCount,
                    diagnostics.outOfWalkablePenalisedCount,
                    diagnostics.observationWeightedCount,
                    diagnostics.effectiveSampleSize,
                    String.valueOf(diagnostics.resampled),
                    String.valueOf(diagnostics.recovered)));
        }

        return new StepResult(fusedPose, diagnostics, snapshot);
    }

    /**
     * Prediction step.
     *
     * Each particle:
     * - perturbs heading
     * - moves forward with motion noise
     * - optionally changes logical floor if vertical change is significant
     */
    private void predictParticles(@NonNull MotionInput motionInput,
                                  @NonNull StepDiagnostics diagnostics) {

        for (Particle p : particles) {
            if (!p.alive) {
                continue;
            }

            double oldLat = p.lat;
            double oldLng = p.lng;
            int oldFloor = p.logicalFloor;

            double noisyHeading = wrapAngleRad(
                    p.headingRad
                            + motionInput.deltaHeadingRad
                            + gaussian(0.0, config.headingNoiseStdRad)
            );

            double noisyForward = motionInput.deltaForwardMeters
                    + gaussian(0.0, config.forwardNoiseStdMeters);

            double dNorth = noisyForward * Math.cos(noisyHeading);
            double dEast = noisyForward * Math.sin(noisyHeading);

            LatLng updated = offsetLatLngMeters(new LatLng(oldLat, oldLng), dNorth, dEast);

            p.lat = updated.latitude;
            p.lng = updated.longitude;
            p.headingRad = noisyHeading;

            // Simple vertical floor proposal.
            if (motionInput.heightChanged && Math.abs(motionInput.deltaHeightMeters) > 1.2) {
                int floorDelta = motionInput.deltaHeightMeters > 0 ? 1 : -1;
                p.logicalFloor = oldFloor + floorDelta;
            }
        }
    }

    /**
     * Apply map-based penalties / rejection after prediction.
     */
    private void applyConstraintWeights(@NonNull MotionInput motionInput,
                                        @NonNull StepDiagnostics diagnostics) {

        if (constraintModel == null || !config.enableMapConstraints) {
            return;
        }

        for (Particle p : particles) {
            if (!p.alive) {
                continue;
            }

            // Walkability check
            boolean walkable = constraintModel.isWalkable(p.lat, p.lng, p.logicalFloor);
            if (!walkable) {
                if (config.softPenaltyForOutOfWalkable) {
                    p.weight *= config.outOfWalkablePenalty;
                    diagnostics.outOfWalkablePenalisedCount++;
                }
            }

            // We do a wall-cross / transition validation by approximating previous state
            double backwardNorth = motionInput.deltaForwardMeters * Math.cos(p.headingRad);
            double backwardEast = motionInput.deltaForwardMeters * Math.sin(p.headingRad);
            LatLng approxPrev = offsetLatLngMeters(new LatLng(p.lat, p.lng), -backwardNorth, -backwardEast);

            boolean crossedWall = constraintModel.crossesWall(
                    approxPrev.latitude,
                    approxPrev.longitude,
                    p.lat,
                    p.lng,
                    p.logicalFloor
            );

            if (crossedWall) {
                diagnostics.wallRejectedCount++;
                if (config.hardKillOnWallCross) {
                    p.alive = false;
                    p.weight = 0.0;
                    continue;
                } else {
                    p.weight *= config.wallCrossPenalty;
                }
            }

            // Floor transition check
            if (motionInput.heightChanged && Math.abs(motionInput.deltaHeightMeters) > 1.2) {
                int oldFloor = motionInput.deltaHeightMeters > 0
                        ? p.logicalFloor - 1
                        : p.logicalFloor + 1;

                boolean allowed = constraintModel.isFloorTransitionAllowed(
                        p.lat, p.lng, oldFloor, p.logicalFloor
                );

                if (!allowed) {
                    diagnostics.floorRejectedCount++;
                    if (config.hardKillOnInvalidFloorTransition) {
                        p.alive = false;
                        p.weight = 0.0;
                        continue;
                    } else {
                        p.weight *= config.invalidFloorPenalty;
                    }
                }
            }

            // Soft map likelihood
            double mapLikelihood = clamp01(
                    constraintModel.mapLikelihood(p.lat, p.lng, p.logicalFloor)
            );
            p.weight *= Math.max(config.minimumWeightFloor, mapLikelihood);
        }
    }

    /**
     * Apply absolute observation weighting.
     */
    private void applyAbsoluteObservation(@NonNull AbsoluteObservation obs,
                                          @NonNull StepDiagnostics diagnostics) {

        for (Particle p : particles) {
            if (!p.alive) {
                continue;
            }

            double w = 1.0;

            if (obs.latLng != null) {
                double distMeters = distanceMeters(
                        p.lat, p.lng,
                        obs.latLng.latitude, obs.latLng.longitude
                );
                double sigma = Math.max(0.5, obs.horizontalSigmaMeters);
                w *= gaussianPdf(distMeters, 0.0, sigma);
            }

            if (obs.logicalFloor != null) {
                if (p.logicalFloor != obs.logicalFloor) {
                    w *= 0.15;
                }
            }

            if (obs.headingRad != null) {
                double err = smallestAngleDiffRad(p.headingRad, obs.headingRad);
                double sigma = Math.max(Math.toRadians(5.0), obs.headingSigmaRad);
                w *= gaussianPdf(err, 0.0, sigma);
            }

            // Blend by confidence so weak observations do not dominate too aggressively.
            double confidence = clamp01(obs.confidence);
            double blended = (1.0 - confidence) + confidence * w;

            p.weight *= Math.max(config.minimumWeightFloor, blended);
            diagnostics.observationWeightedCount++;
        }
    }

    private void enforceWeightFloor() {
        for (Particle p : particles) {
            if (!p.alive) {
                p.weight = 0.0;
            } else {
                p.weight = Math.max(config.minimumWeightFloor, p.weight);
            }
        }
    }

    private double totalWeight() {
        double sum = 0.0;
        for (Particle p : particles) {
            sum += p.weight;
        }
        return sum;
    }

    private void normaliseWeights() {
        double sum = totalWeight();
        if (sum <= 0.0) {
            double equal = 1.0 / Math.max(1, particles.size());
            for (Particle p : particles) {
                p.weight = p.alive ? equal : 0.0;
            }
            return;
        }

        for (Particle p : particles) {
            p.weight /= sum;
        }
    }

    private int countAliveParticles() {
        int alive = 0;
        for (Particle p : particles) {
            if (p.alive) {
                alive++;
            }
        }
        return alive;
    }

    private double computeEffectiveSampleSize() {
        double sumSq = 0.0;
        for (Particle p : particles) {
            sumSq += p.weight * p.weight;
        }
        if (sumSq <= 0.0) {
            return 0.0;
        }
        return 1.0 / sumSq;
    }

    private boolean shouldResample(double ess) {
        return ess < (config.particleCount * config.resampleEffectiveSampleSizeRatio);
    }

    /**
     * Standard systematic resampling.
     */
    private void systematicResample() {
        List<Particle> newParticles = new ArrayList<>(particles.size());

        double step = 1.0 / particles.size();
        double u = random.nextDouble() * step;

        double[] cumulative = new double[particles.size()];
        double running = 0.0;
        for (int i = 0; i < particles.size(); i++) {
            running += particles.get(i).weight;
            cumulative[i] = running;
        }

        int i = 0;
        for (int m = 0; m < particles.size(); m++) {
            double threshold = u + m * step;
            while (i < cumulative.length - 1 && cumulative[i] < threshold) {
                i++;
            }

            Particle copy = particles.get(i).copy();
            copy.weight = 1.0 / particles.size();
            copy.alive = true;

            if (config.resampleRegularizationPosStdMeters > 0.0) {
                double dNorth = gaussian(0.0, config.resampleRegularizationPosStdMeters);
                double dEast = gaussian(0.0, config.resampleRegularizationPosStdMeters);
                LatLng jittered = offsetLatLngMeters(new LatLng(copy.lat, copy.lng), dNorth, dEast);
                copy.lat = jittered.latitude;
                copy.lng = jittered.longitude;
            }

            if (config.resampleRegularizationHeadingStdRad > 0.0) {
                copy.headingRad = wrapAngleRad(
                        copy.headingRad + gaussian(0.0, config.resampleRegularizationHeadingStdRad)
                );
            }

            newParticles.add(copy);
        }

        particles.clear();
        particles.addAll(newParticles);
    }

    /**
     * Recovery if the PF collapses.
     *
     * Strategy:
     * - keep a few random particles around the highest-weight survivor if possible
     * - otherwise reseed around current fused pose or origin-like fallback
     */
    private void recoverParticles() {
        Particle seed = findBestParticle();
        LatLng center;

        int floor;
        double heading;

        if (seed != null) {
            center = new LatLng(seed.lat, seed.lng);
            floor = seed.logicalFloor;
            heading = seed.headingRad;
        } else if (referenceOriginLatLng != null) {
            center = referenceOriginLatLng;
            floor = 0;
            heading = 0.0;
        } else {
            center = new LatLng(0.0, 0.0);
            floor = 0;
            heading = 0.0;
        }

        if (constraintModel != null && center != null) {
            CandidatePose recoveryPose = constraintModel.getRecoveryPose(center, floor);
            if (recoveryPose != null && recoveryPose.getLatLng() != null) {
                center = recoveryPose.getLatLng();
                floor = recoveryPose.getLogicalFloor();
                if (recoveryPose.getHeadingRad() != null) {
                    heading = recoveryPose.getHeadingRad();
                }
            }
        }

        particles.clear();

        for (int i = 0; i < config.particleCount; i++) {
            double dNorth = gaussian(0.0, 1.5);
            double dEast = gaussian(0.0, 1.5);
            LatLng p = offsetLatLngMeters(center, dNorth, dEast);

            particles.add(new Particle(
                    p.latitude,
                    p.longitude,
                    wrapAngleRad(heading + gaussian(0.0, Math.toRadians(15))),
                    floor,
                    1.0 / config.particleCount
            ));
        }
    }

    @Nullable
    private Particle findBestParticle() {
        Particle best = null;
        double bestW = -1.0;
        for (Particle p : particles) {
            if (p.weight > bestW) {
                bestW = p.weight;
                best = p;
            }
        }
        return best;
    }

    /**
     * Build fused pose from weighted mean.
     */
    @NonNull
    private FusedPose buildFusedPose() {
        double lat = 0.0;
        double lng = 0.0;
        double cosSum = 0.0;
        double sinSum = 0.0;

        List<Integer> floors = new ArrayList<>();
        List<Double> floorWeights = new ArrayList<>();

        double weightSum = 0.0;

        for (Particle p : particles) {
            if (!p.alive || p.weight <= 0.0) {
                continue;
            }

            lat += p.lat * p.weight;
            lng += p.lng * p.weight;
            cosSum += Math.cos(p.headingRad) * p.weight;
            sinSum += Math.sin(p.headingRad) * p.weight;

            floors.add(p.logicalFloor);
            floorWeights.add(p.weight);
            weightSum += p.weight;
        }

        if (weightSum <= 0.0) {
            LatLng fallbackLatLng = referenceOriginLatLng != null
                    ? referenceOriginLatLng
                    : new LatLng(0.0, 0.0);

            return new FusedPose(
                    0.0,
                    0.0,
                    0.0,
                    0,
                    fallbackLatLng,
                    0.0f
            );
        }

        // Safety in case weights are not perfectly normalised.
        lat /= weightSum;
        lng /= weightSum;

        int fusedFloor = weightedModeFloor(floors, floorWeights);
        double fusedHeading = Math.atan2(sinSum, cosSum);
        float confidence = (float) computeConfidence();

        LatLng fusedLatLng = new LatLng(lat, lng);

        double xMeters = 0.0;
        double yMeters = 0.0;

        if (!Double.isNaN(referenceOriginLat) && !Double.isNaN(referenceOriginLng)) {
            double[] xy = latLngToLocalMeters(
                    referenceOriginLat,
                    referenceOriginLng,
                    lat,
                    lng
            );
            xMeters = xy[0];
            yMeters = xy[1];
        }

        return new FusedPose(
                xMeters,
                yMeters,
                fusedHeading,
                fusedFloor,
                fusedLatLng,
                confidence
        );
    }

    private int weightedModeFloor(@NonNull List<Integer> floors,
                                  @NonNull List<Double> weights) {
        if (floors.isEmpty()) {
            return 0;
        }

        java.util.Map<Integer, Double> acc = new java.util.HashMap<>();
        for (int i = 0; i < floors.size(); i++) {
            int f = floors.get(i);
            double w = weights.get(i);
            acc.put(f, acc.getOrDefault(f, 0.0) + w);
        }

        int bestFloor = floors.get(0);
        double bestWeight = -1.0;
        for (java.util.Map.Entry<Integer, Double> e : acc.entrySet()) {
            if (e.getValue() > bestWeight) {
                bestWeight = e.getValue();
                bestFloor = e.getKey();
            }
        }
        return bestFloor;
    }

    /**
     * A simple confidence metric:
     * higher when ESS is high and alive particle count is healthy.
     */
    private double computeConfidence() {
        double ess = computeEffectiveSampleSize();
        double essRatio = ess / Math.max(1.0, config.particleCount);
        double aliveRatio = countAliveParticles() / (double) Math.max(1, config.particleCount);
        return clamp01(0.5 * essRatio + 0.5 * aliveRatio);
    }

    @NonNull
    public List<Particle> getParticlesSnapshot() {
        return deepCopyParticles();
    }

    @NonNull
    private List<Particle> deepCopyParticles() {
        List<Particle> copy = new ArrayList<>(particles.size());
        for (Particle p : particles) {
            copy.add(p.copy());
        }
        return copy;
    }

    // -------------------------
    // Math helpers
    // -------------------------

    private double gaussian(double mean, double std) {
        return mean + random.nextGaussian() * std;
    }

    private static double gaussianPdf(double x, double mean, double std) {
        double sigma = Math.max(1e-6, std);
        double z = (x - mean) / sigma;
        return Math.exp(-0.5 * z * z);
    }

    private static double wrapAngleRad(double a) {
        while (a > Math.PI) a -= 2.0 * Math.PI;
        while (a < -Math.PI) a += 2.0 * Math.PI;
        return a;
    }

    private static double smallestAngleDiffRad(double a, double b) {
        return wrapAngleRad(a - b);
    }

    private static double clamp01(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }

    private static LatLng offsetLatLngMeters(@NonNull LatLng base,
                                             double northMeters,
                                             double eastMeters) {
        double dLat = northMeters / 111320.0;
        double dLng = eastMeters / (111320.0 * Math.cos(Math.toRadians(base.latitude)));
        return new LatLng(base.latitude + dLat, base.longitude + dLng);
    }

    private static double distanceMeters(double lat1, double lng1,
                                         double lat2, double lng2) {
        double dLat = (lat2 - lat1) * 111320.0;
        double midLatRad = Math.toRadians((lat1 + lat2) * 0.5);
        double dLng = (lng2 - lng1) * 111320.0 * Math.cos(midLatRad);
        return Math.sqrt(dLat * dLat + dLng * dLng);
    }

    @NonNull
    private static double[] latLngToLocalMeters(double originLat,
                                                double originLng,
                                                double lat,
                                                double lng) {
        double dNorth = (lat - originLat) * 111320.0;
        double midLatRad = Math.toRadians((lat + originLat) * 0.5);
        double dEast = (lng - originLng) * 111320.0 * Math.cos(midLatRad);
        return new double[]{dEast, dNorth};
    }

    private void log(@NonNull String message) {
        if (config.debugLogging) {
            Log.d(TAG, message);
        }
    }
}