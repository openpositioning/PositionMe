package com.openpositioning.PositionMe.fusion;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.maps.model.LatLng;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Core particle filter engine for live indoor positioning.
 *
 * Cleaned design goals:
 * - keep the state in local x/y meters
 * - use the map only as a feasibility veto
 * - never use map likelihoods or walkable penalties as continuous weights
 * - convert to LatLng only once when exporting the fused pose
 */
public class ParticleFilterEngine {

    private static final String TAG = "ParticleFilterEngine";

    /**
     * Individual particle.
     */
    public static class Particle {
        public double x;
        public double y;
        public double headingRad;
        public int floor;
        public double weight;
        public boolean alive = true;

        // True previous state saved before prediction.
        public double prevX;
        public double prevY;
        public double prevHeadingRad;
        public int prevFloor;

        public Particle(double x,
                        double y,
                        double headingRad,
                        int floor,
                        double weight) {
            this.x = x;
            this.y = y;
            this.headingRad = headingRad;
            this.floor = floor;
            this.weight = weight;

            this.prevX = x;
            this.prevY = y;
            this.prevHeadingRad = headingRad;
            this.prevFloor = floor;
        }

        @NonNull
        public Particle copy() {
            Particle p = new Particle(x, y, headingRad, floor, weight);
            p.alive = alive;
            p.prevX = prevX;
            p.prevY = prevY;
            p.prevHeadingRad = prevHeadingRad;
            p.prevFloor = prevFloor;
            return p;
        }
    }

    /**
     * Motion input for one PF update.
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
     * Optional absolute observation in local x/y.
     */
    public static class AbsoluteObservation {
        @Nullable public final Double x;
        @Nullable public final Double y;
        @Nullable public final Integer floor;
        @Nullable public final Double headingRad;
        public final double horizontalSigmaMeters;
        public final double headingSigmaRad;
        public final double confidence;
        @NonNull public final String source;

        public AbsoluteObservation(@Nullable Double x,
                                   @Nullable Double y,
                                   @Nullable Integer floor,
                                   @Nullable Double headingRad,
                                   double horizontalSigmaMeters,
                                   double headingSigmaRad,
                                   double confidence,
                                   @NonNull String source) {
            this.x = x;
            this.y = y;
            this.floor = floor;
            this.headingRad = headingRad;
            this.horizontalSigmaMeters = horizontalSigmaMeters;
            this.headingSigmaRad = headingSigmaRad;
            this.confidence = confidence;
            this.source = source;
        }
    }

    /**
     * Optional map-constraint helper in local x/y space.
     *
     * The map is treated as a feasibility gate only.
     */
    public interface ConstraintModel {
        boolean crossesWall(double oldX, double oldY,
                            double newX, double newY,
                            int floor);

        boolean isFloorTransitionAllowed(double x, double y,
                                         int oldFloor, int newFloor);
    }

    /**
     * Summary of one update step for debugging.
     */
    public static class StepDiagnostics {
        public int totalParticles;
        public int aliveParticles;
        public int wallRejectedCount;
        public int floorRejectedCount;
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

    private final ParticleFilterConfig config;
    private final Random random;
    private final List<Particle> particles = new ArrayList<>();

    @Nullable
    private ConstraintModel constraintModel;

    public ParticleFilterEngine(@NonNull ParticleFilterConfig config) {
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
    }

    /**
     * Initialise particles around a known local anchor.
     */
    public void initialise(double startX,
                           double startY,
                           int floor,
                           double headingRad) {
        particles.clear();

        double equalWeight = 1.0 / Math.max(1, config.particleCount);

        for (int i = 0; i < config.particleCount; i++) {
            double x = startX + gaussian(0.0, config.initialPositionStdMeters);
            double y = startY + gaussian(0.0, config.initialPositionStdMeters);
            double heading = wrapAngleRad(
                    headingRad + gaussian(0.0, config.initialHeadingStdRad)
            );

            particles.add(new Particle(x, y, heading, floor, equalWeight));
        }

        normaliseWeights();
        log("Initialised PF with "
                + particles.size()
                + " particles at x=" + startX
                + " y=" + startY
                + " floor=" + floor);
    }

    /**
     * Main PF update.
     */
    @NonNull
    public StepResult update(@NonNull MotionInput motionInput,
                             @Nullable AbsoluteObservation absoluteObservation,
                             @NonNull CoordinateConverter coordinateConverter) {

        if (particles.isEmpty()) {
            throw new IllegalStateException("ParticleFilterEngine.update() called before initialise().");
        }

        StepDiagnostics diagnostics = new StepDiagnostics();
        diagnostics.totalParticles = particles.size();

        predictParticles(motionInput);
        enforceConstraintsVeto(diagnostics);

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

        if ((countAliveParticles() == 0 || totalWeight() <= 0.0) && config.enableRecoveryIfCollapsed) {
            recoverParticles();
            diagnostics.recovered = true;
            normaliseWeights();
        }

        FusedPose fusedPose = buildFusedPose(coordinateConverter);
        List<Particle> snapshot = deepCopyParticles();

        if (config.debugLogging) {
            Log.d(TAG,
                    "PF step: alive=" + diagnostics.aliveParticles + "/" + diagnostics.totalParticles
                            + " wallRejected=" + diagnostics.wallRejectedCount
                            + " floorRejected=" + diagnostics.floorRejectedCount
                            + " obsWeighted=" + diagnostics.observationWeightedCount
                            + " ess=" + diagnostics.effectiveSampleSize
                            + " resampled=" + diagnostics.resampled
                            + " recovered=" + diagnostics.recovered);
        }

        return new StepResult(fusedPose, diagnostics, snapshot);
    }

    /**
     * Prediction step in local x/y.
     *
     * x = east-west local metres
     * y = north-south local metres
     *
     * headingRad convention:
     * 0 = north, +pi/2 = east
     */
    private void predictParticles(@NonNull MotionInput motionInput) {
        for (Particle p : particles) {
            if (!p.alive) {
                continue;
            }

            p.prevX = p.x;
            p.prevY = p.y;
            p.prevHeadingRad = p.headingRad;
            p.prevFloor = p.floor;

            double noisyHeading = wrapAngleRad(
                    p.headingRad
                            + motionInput.deltaHeadingRad
                            + gaussian(0.0, config.headingNoiseStdRad)
            );

            double noisyForward = motionInput.deltaForwardMeters
                    + gaussian(0.0, config.forwardNoiseStdMeters);

            double dNorth = noisyForward * Math.cos(noisyHeading);
            double dEast = noisyForward * Math.sin(noisyHeading);

            p.x += dEast;
            p.y += dNorth;
            p.headingRad = noisyHeading;

            if (motionInput.heightChanged && Math.abs(motionInput.deltaHeightMeters) > 1.2) {
                int floorDelta = motionInput.deltaHeightMeters > 0 ? 1 : -1;
                p.floor = p.floor + floorDelta;
            }
        }
    }

    /**
     * Apply map constraints as hard feasibility vetoes only.
     *
     * No continuous weighting.
     * No walkable penalties.
     * No map likelihood shaping.
     */
    private void enforceConstraintsVeto(@NonNull StepDiagnostics diagnostics) {
        if (constraintModel == null || !config.enableMapConstraints) {
            return;
        }

        for (Particle p : particles) {
            if (!p.alive) {
                continue;
            }

            int wallFloor = p.prevFloor;
            if (constraintModel.crossesWall(p.prevX, p.prevY, p.x, p.y, wallFloor)) {
                diagnostics.wallRejectedCount++;

                // Reject the illegal translation but keep the heading update.
                p.x = p.prevX;
                p.y = p.prevY;
            }

            if (p.floor != p.prevFloor) {
                boolean allowed = constraintModel.isFloorTransitionAllowed(
                        p.x,
                        p.y,
                        p.prevFloor,
                        p.floor
                );

                if (!allowed) {
                    diagnostics.floorRejectedCount++;
                    p.floor = p.prevFloor;
                }
            }
        }
    }

    /**
     * Apply absolute observation weighting in local x/y.
     */
    private void applyAbsoluteObservation(@NonNull AbsoluteObservation obs,
                                          @NonNull StepDiagnostics diagnostics) {
        double cappedConfidence = capObservationConfidence(obs.source, obs.confidence);

        for (Particle p : particles) {
            if (!p.alive) {
                continue;
            }

            double w = 1.0;

            if (obs.x != null && obs.y != null) {
                double dx = p.x - obs.x;
                double dy = p.y - obs.y;
                double distance = Math.hypot(dx, dy);

                double sigma = Math.max(0.5, obs.horizontalSigmaMeters);
                w *= gaussianPdf(distance, 0.0, sigma);
            }

            if (obs.floor != null && p.floor != obs.floor) {
                w *= 0.25;
            }

            if (obs.headingRad != null) {
                double err = smallestAngleDiffRad(p.headingRad, obs.headingRad);
                double sigma = Math.max(Math.toRadians(5.0), obs.headingSigmaRad);
                w *= gaussianPdf(err, 0.0, sigma);
            }

            double blended = (1.0 - cappedConfidence) + cappedConfidence * w;
            p.weight *= Math.max(config.minimumWeightFloor, blended);
            diagnostics.observationWeightedCount++;
        }
    }

    private double capObservationConfidence(@NonNull String source, double confidence) {
        double capped = clamp01(confidence);

        if ("wifi".equals(source)) {
            return Math.min(capped, 0.08);
        }
        if ("gnss".equals(source)) {
            return Math.min(capped, 0.08);
        }
        if ("map_match".equals(source)) {
            return Math.min(capped, 0.85);
        }
        return capped;
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
     * Standard systematic resampling in local x/y.
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

        int index = 0;
        for (int m = 0; m < particles.size(); m++) {
            double threshold = u + m * step;
            while (index < cumulative.length - 1 && cumulative[index] < threshold) {
                index++;
            }

            Particle copy = particles.get(index).copy();
            copy.weight = 1.0 / particles.size();
            copy.alive = true;

            if (config.resampleRegularizationPosStdMeters > 0.0) {
                copy.x += gaussian(0.0, config.resampleRegularizationPosStdMeters);
                copy.y += gaussian(0.0, config.resampleRegularizationPosStdMeters);
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
     * Local-only recovery around the best particle.
     *
     * This keeps the recovery logic simple and avoids map-driven teleporting.
     */
    private void recoverParticles() {
        Particle seed = findBestParticle();

        double centerX = 0.0;
        double centerY = 0.0;
        int floor = 0;
        double heading = 0.0;

        if (seed != null) {
            centerX = seed.x;
            centerY = seed.y;
            floor = seed.floor;
            heading = seed.headingRad;
        }

        particles.clear();

        for (int i = 0; i < config.particleCount; i++) {
            particles.add(new Particle(
                    centerX + gaussian(0.0, config.recoveryPositionStdMeters),
                    centerY + gaussian(0.0, config.recoveryPositionStdMeters),
                    wrapAngleRad(heading + gaussian(0.0, config.recoveryHeadingStdRad)),
                    floor,
                    1.0 / config.particleCount
            ));
        }
    }

    @Nullable
    private Particle findBestParticle() {
        Particle best = null;
        double bestWeight = -1.0;
        for (Particle p : particles) {
            if (p.weight > bestWeight) {
                bestWeight = p.weight;
                best = p;
            }
        }
        return best;
    }

    /**
     * Build fused pose from weighted mean in local x/y.
     */
    @NonNull
    private FusedPose buildFusedPose(@NonNull CoordinateConverter converter) {
        double x = 0.0;
        double y = 0.0;
        double cosSum = 0.0;
        double sinSum = 0.0;

        List<Integer> floors = new ArrayList<>();
        List<Double> floorWeights = new ArrayList<>();

        double weightSum = 0.0;

        for (Particle p : particles) {
            if (!p.alive || p.weight <= 0.0) {
                continue;
            }

            x += p.x * p.weight;
            y += p.y * p.weight;
            cosSum += Math.cos(p.headingRad) * p.weight;
            sinSum += Math.sin(p.headingRad) * p.weight;

            floors.add(p.floor);
            floorWeights.add(p.weight);
            weightSum += p.weight;
        }

        if (weightSum <= 0.0) {
            return new FusedPose(
                    0.0,
                    0.0,
                    0.0,
                    0,
                    converter.localToLatLng(0.0, 0.0),
                    0.0f
            );
        }

        x /= weightSum;
        y /= weightSum;

        int fusedFloor = weightedModeFloor(floors, floorWeights);
        double fusedHeading = Math.atan2(sinSum, cosSum);
        float confidence = (float) computeConfidence();

        LatLng fusedLatLng = converter.localToLatLng(x, y);

        return new FusedPose(
                x,
                y,
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

        Map<Integer, Double> accumulated = new HashMap<>();
        for (int i = 0; i < floors.size(); i++) {
            int floor = floors.get(i);
            double weight = weights.get(i);
            accumulated.put(floor, accumulated.getOrDefault(floor, 0.0) + weight);
        }

        int bestFloor = floors.get(0);
        double bestWeight = -1.0;
        for (Map.Entry<Integer, Double> entry : accumulated.entrySet()) {
            if (entry.getValue() > bestWeight) {
                bestWeight = entry.getValue();
                bestFloor = entry.getKey();
            }
        }
        return bestFloor;
    }

    /**
     * Simple confidence metric:
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

    private void log(@NonNull String message) {
        if (config.debugLogging) {
            Log.d(TAG, message);
        }
    }
}
