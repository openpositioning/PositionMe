package com.openpositioning.PositionMe.fusion;

/**
 * Shared configuration bundle for the live particle filter.
 *
 * Keep this as the single PF config definition used by:
 * - ParticleFilterEngine
 * - ParticleFilterManager
 *
 * Units:
 * - distances in metres
 * - angles in radians
 *
 * This cleaned version removes parameters that only existed to tune
 * continuous map-based trajectory shaping.
 */
public class ParticleFilterConfig {

    // Core PF parameters loaded from settings

    /** Number of particles in the filter. */
    public int particleCount;

    /** Standard deviation of translational prediction noise (m). */
    public double forwardNoiseStdMeters;

    /** Standard deviation of heading prediction noise (rad). */
    public double headingNoiseStdRad;

    /** Wi-Fi observation spread (m). */
    public double observationSigmaWifiMeters;

    /** GNSS observation spread (m). */
    public double observationSigmaGnssMeters;

    /** Initial position spread around the startup pose (m). */
    public double initialPositionStdMeters;

    /** Initial heading spread around the startup heading (rad). */
    public double initialHeadingStdRad;

    /** Resampling threshold as a ratio of particle count. */
    public double resampleEffectiveSampleSizeRatio;

    /** Position jitter added after resampling (m). */
    public double resampleRegularizationPosStdMeters;

    /** Heading jitter added after resampling (rad). */
    public double resampleRegularizationHeadingStdRad;

    // Constraint behaviour

    /** Enable wall / floor-transition veto logic. */
    public boolean enableMapConstraints = true;

    // Observation blending

    /** Enable Wi-Fi / GNSS / map-match weighting. */
    public boolean enableAbsoluteObservationWeighting = true;

    /** Minimum non-zero weight floor for alive particles. */
    public double minimumWeightFloor = 1e-12;

    // Recovery behaviour

    /**
     * Allow recovery reseeding if the cloud collapses.
     *
     * In the cleaned configuration this is disabled by default, because
     * teleport-like recovery can reintroduce visible snapping.
     */
    public boolean enableRecoveryIfCollapsed = false;

    /** Position spread used when reseeding recovered particles (m). */
    public double recoveryPositionStdMeters = 0.75;

    /** Heading spread used when reseeding recovered particles (rad). */
    public double recoveryHeadingStdRad = Math.toRadians(8.0);

    // Logging

    /** Enable PF debug logging. */
    public boolean debugLogging = true;

    /**
     * Constructor for the user-tunable settings fields.
     */
    public ParticleFilterConfig(
            int particleCount,
            double forwardNoiseStdMeters,
            double headingNoiseStdRad,
            double observationSigmaWifiMeters,
            double observationSigmaGnssMeters,
            double initialPositionStdMeters,
            double initialHeadingStdRad,
            double resampleEffectiveSampleSizeRatio,
            double resampleRegularizationPosStdMeters,
            double resampleRegularizationHeadingStdRad
    ) {
        this.particleCount = particleCount;
        this.forwardNoiseStdMeters = forwardNoiseStdMeters;
        this.headingNoiseStdRad = headingNoiseStdRad;
        this.observationSigmaWifiMeters = observationSigmaWifiMeters;
        this.observationSigmaGnssMeters = observationSigmaGnssMeters;
        this.initialPositionStdMeters = initialPositionStdMeters;
        this.initialHeadingStdRad = initialHeadingStdRad;
        this.resampleEffectiveSampleSizeRatio = resampleEffectiveSampleSizeRatio;
        this.resampleRegularizationPosStdMeters = resampleRegularizationPosStdMeters;
        this.resampleRegularizationHeadingStdRad = resampleRegularizationHeadingStdRad;
    }
}
