package com.openpositioning.PositionMe.fusion;

/**
 * Immutable parameter bundle for the particle filter.
 *
 * <p>All values here are already converted into internal units:
 * - distances in meters
 * - angles in radians
 */
public class ParticleFilterConfig {

    /** Number of particles in the filter. */
    public final int particleCount;

    /** Standard deviation of translational prediction noise (m). */
    public final double sigmaStep;

    /** Standard deviation of heading prediction noise (rad). */
    public final double sigmaThetaRad;

    /** Wi-Fi likelihood spread (m). */
    public final double sigmaWifi;

    /** GNSS likelihood spread (m). */
    public final double sigmaGnss;

    /** Initial position spread around the startup pose (m). */
    public final double initPosStd;

    /** Initial heading spread around the startup heading (rad). */
    public final double initHeadingStdRad;

    /** Resampling threshold as a ratio of particle count. */
    public final double resampleRatio;

    /** Position jitter added after resampling (m). */
    public final double sigmaRegPos;

    /** Heading jitter added after resampling (rad). */
    public final double sigmaRegThetaRad;

    public ParticleFilterConfig(
            int particleCount,
            double sigmaStep,
            double sigmaThetaRad,
            double sigmaWifi,
            double sigmaGnss,
            double initPosStd,
            double initHeadingStdRad,
            double resampleRatio,
            double sigmaRegPos,
            double sigmaRegThetaRad
    ) {
        this.particleCount = particleCount;
        this.sigmaStep = sigmaStep;
        this.sigmaThetaRad = sigmaThetaRad;
        this.sigmaWifi = sigmaWifi;
        this.sigmaGnss = sigmaGnss;
        this.initPosStd = initPosStd;
        this.initHeadingStdRad = initHeadingStdRad;
        this.resampleRatio = resampleRatio;
        this.sigmaRegPos = sigmaRegPos;
        this.sigmaRegThetaRad = sigmaRegThetaRad;
    }
}