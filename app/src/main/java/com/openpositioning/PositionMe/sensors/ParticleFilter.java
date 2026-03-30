package com.openpositioning.PositionMe.sensors;

import android.util.Log;
import java.util.Random;

/**
 * The filter maintains a set of NUM_PARTICLES weighted hypotheses (particles) that each
 * represent a possible user position in the local East-North coordinate frame (metres East and
 * North from a reference point set.
 *
 * Three-step cycle:
 *   1. predict()        — move every particle using the PDR displacement + Gaussian noise
 *   2. updateWithGnss() — reweight particles by distance to the GNSS observation
 *      updateWithWifi() — reweight particles by distance to the WiFi observation
 *   3. resample()       — called inside each update step
 *
 * @author Haoning Huang
 */
public class ParticleFilter {

    private static final String TAG = "ParticleFilter";

    /** Number of particles.*/
    private static final int NUM_PARTICLES = 300;

    /**
     * Standard deviation of Gaussian noise added to each particle during the predict step (metres).
     * Represents uncertainty in PDR step length / heading.
     */
    private static final float MOTION_NOISE_STD = 0.15f;

    /**
     * Standard deviation of the GNSS observation model (metres).
     * Reflects typical outdoor GNSS horizontal accuracy.
     */
    private static final float GNSS_NOISE_STD = 5.0f;

    /**
     * Standard deviation of the WiFi observation model (metres).
     * WiFi fingerprinting is less accurate than GNSS, so a larger value is used.
     */
    private static final float WIFI_NOISE_STD = 15.0f;

    /** East coordinate of each particle in East-North metres */
    private float[] particlesX;

    /** North coordinate of each particle in East-North metres */
    private float[] particlesY;

    /** Normalised importance weight of each particle (sum = 1) */
    private float[] weights;

    private final Random random = new Random();

    private boolean initialized = false;

    /** Default constructor — allocates arrays but does not initialise positions. */
    public ParticleFilter() {
        particlesX = new float[NUM_PARTICLES];
        particlesY = new float[NUM_PARTICLES];
        weights    = new float[NUM_PARTICLES];
    }

    /**
     * Spreads all particles as a Gaussian cloud centred on the given East-North position.
     *
     * @param initX       Initial East  position in East-North metres
     * @param initY       Initial North position in East-North metres
     * @param uncertainty Spread radius
     */
    public void initParticles(float initX, float initY, float uncertainty) {
        for (int i = 0; i < NUM_PARTICLES; i++) {
            particlesX[i] = initX + (float) (random.nextGaussian() * uncertainty);
            particlesY[i] = initY + (float) (random.nextGaussian() * uncertainty);
            weights[i]    = 1.0f / NUM_PARTICLES;
        }
        initialized = true;
        Log.i(TAG, "Initialised " + NUM_PARTICLES + " particles at ("
                + initX + ", " + initY + ") ± " + uncertainty + " m");
    }

    /**
     * Prediction step: translates every particle by the PDR displacement and
     * adds Gaussian noise to model uncertainty in step length and heading.
     *
     * When the device is stationary, using a reduced noise to lower the drifting of particle cloud.
     *
     * @param dx  PDR displacement in East  direction (metres) since last call
     * @param dy  PDR displacement in North direction (metres) since last call
     */
    public void predict(float dx, float dy) {
        if (!initialized) return;
        float movementMagnitude = (float) Math.sqrt(dx * dx + dy * dy);
        float noise = (movementMagnitude < 0.05f) ? MOTION_NOISE_STD * 0.1f : MOTION_NOISE_STD;
        for (int i = 0; i < NUM_PARTICLES; i++) {
            particlesX[i] += dx + (float) (random.nextGaussian() * noise);
            particlesY[i] += dy + (float) (random.nextGaussian() * noise);
        }
    }


    /**
     * Update step using a GNSS position observation.
     * Reweights particles by a Gaussian likelihood centred on the measurement.
     * Resamples to remove low-weight particles.
     *
     * @param measX  Observed East  position in East-North metres (converted from WGS84)
     * @param measY  Observed North position in East-North metres (converted from WGS84)
     */
    public void updateWithGnss(float measX, float measY) {
        if (!initialized) return;
        updateWeights(measX, measY, GNSS_NOISE_STD);
        resample();
        Log.d(TAG, "GNSS update applied. Best estimate: " + java.util.Arrays.toString(getBestEstimate()));
    }

    /**
     * Update step using a GNSS position observation with accuracy-adaptive noise.
     * Maps the reported GNSS accuracy (metres) to an observation noise std dev:
     *   accuracy < 5 m  → noise std = accuracy (high trust)
     *   accuracy 5–20 m → noise std = accuracy
     *   accuracy > 20 m → noise std = accuracy (capped at 30 m)
     *
     * @param measX    Observed East  position in East-North metres
     * @param measY    Observed North position in East-North metres
     * @param accuracy Reported horizontal accuracy from Android Location (metres)
     */
    public void updateWithGnss(float measX, float measY, float accuracy) {
        if (!initialized) return;
        float noiseStd = Math.min(Math.max(accuracy, 3.0f), 30.0f);
        updateWeights(measX, measY, noiseStd);
        resample();
        Log.d(TAG, "GNSS update (accuracy=" + accuracy + "m, noiseStd=" + noiseStd
                + "m). Best estimate: " + java.util.Arrays.toString(getBestEstimate()));
    }

    /**
     * Update step using a WiFi positioning observation.
     *
     * @param measX  Observed East  position in East-North metres
     * @param measY  Observed North position in East-North metres
     */
    public void updateWithWifi(float measX, float measY) {
        if (!initialized) return;
        updateWeights(measX, measY, WIFI_NOISE_STD);
        resample();
        Log.d(TAG, "WiFi update applied. Best estimate: " + java.util.Arrays.toString(getBestEstimate()));
    }

    /**
     * Update step using a WiFi positioning observation with a caller-supplied noise std.
     *
     * @param measX    Observed East  position in East-North metres
     * @param measY    Observed North position in East-North metres
     * @param noiseStd Observation noise standard deviation (metres)
     */
    public void updateWithWifi(float measX, float measY, float noiseStd) {
        if (!initialized) return;
        updateWeights(measX, measY, noiseStd);
        resample();
        Log.d(TAG, "WiFi update (noiseStd=" + noiseStd + "m). Best estimate: "
                + java.util.Arrays.toString(getBestEstimate()));
    }

    /**
     * Update step using a WiFi positioning observation with AP-count-adaptive noise.
     *
     * @param measX   Observed East  position in East-North metres
     * @param measY   Observed North position in East-North metres
     * @param apCount Number of WiFi APs used to compute the position fix
     */
    public void updateWithWifi(float measX, float measY, int apCount) {
        if (!initialized) return;
        float noiseStd = Math.max(8.0f, 20.0f - apCount * 1.5f);
        updateWeights(measX, measY, noiseStd);
        resample();
        Log.d(TAG, "WiFi update (apCount=" + apCount + ", noiseStd=" + noiseStd
                + "m). Best estimate: " + java.util.Arrays.toString(getBestEstimate()));
    }

    /**
     * Returns the current best position estimate as the weighted mean of all particles.
     *
     * @return float array {east, north} in East-North metres.
     */
    public float[] getBestEstimate() {
        if (!initialized) return new float[]{0f, 0f};
        float x = 0f, y = 0f;
        for (int i = 0; i < NUM_PARTICLES; i++) {
            x += weights[i] * particlesX[i];
            y += weights[i] * particlesY[i];
        }
        return new float[]{x, y};
    }

    /**
     * Returns the spread of the particle cloud as a single scalar (metres).
     * Computed as the RMS of the weighted standard deviations in East and North.
     * A larger value means more uncertain.
     *
     * @return spread radius in metres (if not initialized,return -1).
     */
    public double getSigmaMetres() {
        if (!initialized) return -1.0;
        float[] mean = getBestEstimate();
        double varX = 0.0, varY = 0.0;
        for (int i = 0; i < NUM_PARTICLES; i++) {
            double dx = particlesX[i] - mean[0];
            double dy = particlesY[i] - mean[1];
            varX += weights[i] * dx * dx;
            varY += weights[i] * dy * dy;
        }
        return Math.sqrt(varX + varY);
    }

    /**
     * Returns a copy of all particle positions in local East-North coordinates (metres).
     * Each row is one particle: {east, north}.
     * Used for map matching wall-constraint filter.
     *
     * @return float[NUM_PARTICLES][2] array, or empty array if not initialized.
     */
    public float[][] getParticles() {
        if (!initialized) return new float[0][2];
        float[][] result = new float[NUM_PARTICLES][2];
        for (int i = 0; i < NUM_PARTICLES; i++) {
            result[i][0] = particlesX[i];
            result[i][1] = particlesY[i];
        }
        return result;
    }

    /**
     * Returns a deep copy of all particle positions in local East-North coordinates.
     * Each row is one particle: {east, north}.
     */
    public float[][] getParticlesCopy() {
        if (!initialized) return new float[0][2];

        float[][] copy = new float[NUM_PARTICLES][2];
        for (int i = 0; i < NUM_PARTICLES; i++) {
            copy[i][0] = particlesX[i];
            copy[i][1] = particlesY[i];
        }
        return copy;
    }

    /**
     * Returns a copy of the particle weights.
     */
    public float[] getWeights() {
        if (!initialized) return new float[0];

        float[] copy = new float[NUM_PARTICLES];
        System.arraycopy(weights, 0, copy, 0, NUM_PARTICLES);
        return copy;
    }

    public float[] getParticlesXRef() {
        return particlesX;
    }

    public float[] getParticlesYRef() {
        return particlesY;
    }

    public float[] getWeightsRef() {
        return weights;
    }

    /**
     * Resets the particle cloud around a new position with increased uncertainty.
     * Called when a floor change is detected, to prevent particles from remaining on the wrong floor.
     * Horizontal position is still preserved as the best estimate, but spread is widened.
     *
     * @param centreX     East  position to re-centre particles around (metres)
     * @param centreY     North position to re-centre particles around (metres)
     * @param uncertainty Spread radius for the new particle cloud (metres)
     */
    public void resetAroundPosition(float centreX, float centreY, float uncertainty) {
        for (int i = 0; i < NUM_PARTICLES; i++) {
            particlesX[i] = centreX + (float) (random.nextGaussian() * uncertainty);
            particlesY[i] = centreY + (float) (random.nextGaussian() * uncertainty);
            weights[i]    = 1.0f / NUM_PARTICLES;
        }
        Log.i(TAG, "Particles reset around (" + centreX + ", " + centreY
                + ") ± " + uncertainty + " m after floor change");
    }


    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Fraction of particles injected near the observation when weight collapse is detected.
     * These particles allow the filter to recover when PDR error exceeds the observation noise range.
     */
    private static final float INJECTION_FRACTION = 0.2f;

    /**
     * Multiplies each particle's weight by the Gaussian likelihood of the given observation.
     * Normalizes the weight array, sum = 1.
     *
     * Likelihood: w_i *= exp( -distance^2 / (2 * std^2) )
     *
     * If all weights collapse to zero, a fraction of particles is injected near the observation
     * to allow recovery when PDR error is large. Remaining particles retain uniform weights.
     *
     * @param measX    Observed East  position (East-North metres)
     * @param measY    Observed North position (East-North metres)
     * @param noiseStd Standard deviation of the observation model (metres)
     */
    private void updateWeights(float measX, float measY, float noiseStd) {
        float totalWeight  = 0f;
        float twoSigmaSq   = 2.0f * noiseStd * noiseStd;

        for (int i = 0; i < NUM_PARTICLES; i++) {
            float dx     = particlesX[i] - measX;
            float dy     = particlesY[i] - measY;
            float distSq = dx * dx + dy * dy;
            weights[i]  *= (float) Math.exp(-distSq / twoSigmaSq);
            totalWeight += weights[i];
        }

        // Normalise
        if (totalWeight > 1e-10f) {
            for (int i = 0; i < NUM_PARTICLES; i++) {
                weights[i] /= totalWeight;
            }
        } else {
            // Weight collapse: inject a fraction of particles near the observation,
            // then reset all weights to uniform.
            Log.w(TAG, "Weight collapse — injecting particles near observation ("
                    + measX + ", " + measY + ")");
            int injected = (int) (NUM_PARTICLES * INJECTION_FRACTION);
            for (int i = 0; i < injected; i++) {
                particlesX[i] = measX + (float) (random.nextGaussian() * noiseStd);
                particlesY[i] = measY + (float) (random.nextGaussian() * noiseStd);
            }
            float uniform = 1.0f / NUM_PARTICLES;
            for (int i = 0; i < NUM_PARTICLES; i++) {
                weights[i] = uniform;
            }
        }
    }

    public void normalizeWeights() {
        if (!initialized) return;

        float totalWeight = 0f;
        for (int i = 0; i < NUM_PARTICLES; i++) {
            totalWeight += weights[i];
        }

        if (totalWeight > 1e-10f) {
            for (int i = 0; i < NUM_PARTICLES; i++) {
                weights[i] /= totalWeight;
            }
        } else {
            Log.w(TAG, "Weight collapse detected — resetting to uniform weights");
            float uniform = 1.0f / NUM_PARTICLES;
            for (int i = 0; i < NUM_PARTICLES; i++) {
                weights[i] = uniform;
            }
        }
    }

    /**
     * Resampling: draws new particles from the current weighted distribution, then resets weights to uniform.
     */
    private void resample() {
        float[] newX = new float[NUM_PARTICLES];
        float[] newY = new float[NUM_PARTICLES];

        // Build cumulative weight array
        float[] cdf = new float[NUM_PARTICLES];
        cdf[0] = weights[0];
        for (int i = 1; i < NUM_PARTICLES; i++) {
            cdf[i] = cdf[i - 1] + weights[i];
        }

        // evenly spaced starting point
        float step = 1.0f / NUM_PARTICLES;
        float u    = random.nextFloat() * step;   // random offset in [0, step)
        int   j    = 0;

        for (int i = 0; i < NUM_PARTICLES; i++) {
            while (j < NUM_PARTICLES - 1 && u > cdf[j]) {
                j++;
            }
            newX[i] = particlesX[j];
            newY[i] = particlesY[j];
            u += step;
        }

        // Replace old particles and reset to uniform weights
        particlesX = newX;
        particlesY = newY;
        float uniform = 1.0f / NUM_PARTICLES;
        for (int i = 0; i < NUM_PARTICLES; i++) {
            weights[i] = uniform;
        }
    }
}
