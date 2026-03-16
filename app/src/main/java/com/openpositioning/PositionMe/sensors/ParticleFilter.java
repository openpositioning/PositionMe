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
    private static final float MOTION_NOISE_STD = 0.3f;

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
     * @param dx  PDR displacement in East  direction (metres) since last call
     * @param dy  PDR displacement in North direction (metres) since last call
     */
    public void predict(float dx, float dy) {
        if (!initialized) return;
        for (int i = 0; i < NUM_PARTICLES; i++) {
            particlesX[i] += dx + (float) (random.nextGaussian() * MOTION_NOISE_STD);
            particlesY[i] += dy + (float) (random.nextGaussian() * MOTION_NOISE_STD);
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

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Multiplies each particle's weight by the Gaussian likelihood of the given observation.
     * Normalizes the weight array, sum = 1.
     *
     * Likelihood: w_i *= exp( -distance^2 / (2 * std^2) )
     *
     * If all weights collapse to zero, weights are reset to uniform to allow recovery.
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
            // Weight collapse: reset to uniform so the filter can recover
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
