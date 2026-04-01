
package com.openpositioning.PositionMe.sensors;
import android.util.Log;
import com.google.android.gms.maps.model.LatLng;
import com.openpositioning.PositionMe.utils.UtilFunctions;
import java.util.ArrayList;
import java.util.List;


import java.util.Random; //FOR PARTICLE FILTER

public class ParticleFilter {
    private static final String TAG = "ParticleFilter";
    private static final int NUM_PARTICLES = 200; // Number of particles
    private float[][] particles;

    //[num particles][2] is {easting, northing} 
    private float[] weights; // Weights for each particle
    private LatLng origin; // Origin point for ENU conversion

    private boolean initialized = false;
    private final Random random = new Random();

    // Wall segments: {x1, y1, x2, y2} in ENU
    private List<float[]> walls = new ArrayList<>();

    public void initialise(LatLng firstFix, float accuracyMeters) {
        if (initialized) return;          // ignore subsequent calls, DONE
        this.origin = firstFix;
        this.particles = new float[NUM_PARTICLES][2];


        this.weights = new float[NUM_PARTICLES];
        float spread = accuracyMeters;

        for (int i = 0; i < NUM_PARTICLES; i++) {
            particles[i][0] = (float) (random.nextGaussian() * spread); // east
            particles[i][1] = (float) (random.nextGaussian() * spread); // north
            weights[i] = 1.0f / NUM_PARTICLES;
        }
        initialized = true;
        Log.d(TAG, "Initialized at " + firstFix + " accuraccy is =" + accuracyMeters + "m");
    }

    public boolean isInitialized() {
        return initialized;
    }

    /** Resets the filter so it can be re-initialised at a new origin. */
    public void reset() {
        initialized = false;
        origin = null;
        particles = null;
        weights = null;
    }

    public LatLng getOrigin() {
        return origin;
    }

    public void setWalls(List<float[]> wallSegments) {
        this.walls = wallSegments;
    }


    public static final float HEADING_NOISE_STD = 0.05f;
    public static final float STRIDE_LENGTH_NOISE_STD = 0.05f;

    // Small positional noise added to each particle after resampling.
    // Prevents sample impoverishment: without roughening, particles copied from the
    // same parent are identical and provide no extra information on the next step.
    private static final float ROUGHENING_STD = 0.08f; // metres — reduced to limit stationary drift

    public void predict(float deltaEasting, float deltaNorthing) {
        if (!initialized) return; // ignore if not initialized


        float stride = (float) Math.sqrt(deltaEasting * deltaEasting + deltaNorthing * deltaNorthing);
        float heading = (float) Math.atan2(deltaNorthing, deltaEasting); // calculate heading from deltas

        for (int i = 0; i < NUM_PARTICLES; i++) {
            // Add noise to heading and stride
            float noisyHeading = heading + (float) (random.nextGaussian() * HEADING_NOISE_STD);
            float noisyStride = stride + (float) (random.nextGaussian() * STRIDE_LENGTH_NOISE_STD);

            // Update particle position based on noisy heading and stride

            //JAPJOT: ive commented this out to add wall collision checking, we need to check if the line from old position to new position intersects any walls, if it does, we can either discard the particle (set weight to 0) 
            // or reflect it off the wall (more complex). and particles that hit walls will be reflected in opposite direction
            //like dvd logo

            // particles[i][0] += noisyStride * Math.cos(noisyHeading); // update east
            // particles[i][1] += noisyStride * Math.sin(noisyHeading); // update north


            float oldX = particles[i][0];
            float oldY = particles[i][1];
            float newX = oldX + noisyStride * (float) Math.cos(noisyHeading);
            float newY = oldY + noisyStride * (float) Math.sin(noisyHeading);

            if (intersectsWall(oldX, oldY, newX, newY)) {
                // Stop at old position — can't pass through a wall.
                // Keeping oldX/oldY is more stable than reversing direction,
                // which can send the particle into an opposite wall.
            } else {
                particles[i][0] = newX;
                particles[i][1] = newY;
            }
        }
        // Weights are intentionally NOT updated or resampled here.
        // predict() only propagates particle positions; weight updates happen
        // exclusively in updateGNSS() and updateWiFi() when a new observation arrives.
    }

    /**
     * Returns true if the straight line between two geographic positions would
     * cross any loaded wall segment.  Used by the map layer to prevent the
     * trajectory polyline from being drawn through floorplan walls.
     *
     * @param from start of the candidate segment (WGS-84)
     * @param to   end   of the candidate segment (WGS-84)
     */
    public boolean wouldCrossWall(LatLng from, LatLng to) {
        if (origin == null || walls == null || walls.isEmpty()) return false;
        float[] fromENU = UtilFunctions.convertWGS84ToENU(origin, from);
        float[] toENU   = UtilFunctions.convertWGS84ToENU(origin, to);
        return intersectsWall(fromENU[0], fromENU[1], toENU[0], toENU[1]);
    }

    private boolean intersectsWall(float x1, float y1, float x2, float y2) {
        // Check if the line segment from (x1, y1) to (x2, y2) intersects any wall segment
        if (walls == null || walls.isEmpty()) return false;
        for (float[] wall : walls) {
            if (doIntersect(x1, y1, x2, y2, wall[0], wall[1], wall[2], wall[3])) {
                return true;
            }
        }
        return false;
    }

    


    private boolean doIntersect(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4) {
        float den = (y4 - y3) * (x2 - x1) - (x4 - x3) * (y2 - y1); //calculate the denominator of the intersection formula
        if (den == 0) return false;
        // Calculate the intersection point using the parametric form of the line segments
        float ua = ((x4 - x3) * (y1 - y3) - (y4 - y3) * (x1 - x3)) / den;
        float ub = ((x2 - x1) * (y1 - y3) - (y2 - y1) * (x1 - x3)) / den;
        return ua >= 0 && ua <= 1 && ub >= 0 && ub <= 1;
    }

    /**
     * Computes the effective sample size: N_eff = 1 / Σ(w_i²).
     *
     * <p>N_eff equals NUM_PARTICLES when all weights are equal (maximum diversity)
     * and approaches 1 when a single particle carries all the weight (collapsed).
     * Resampling is triggered when N_eff falls below NUM_PARTICLES / 2.</p>
     */
    private float computeEffectiveSampleSize() {
        float sumSquared = 0f;
        for (float w : weights) {
            sumSquared += w * w;
        }
        return sumSquared < 1e-10f ? NUM_PARTICLES : 1.0f / sumSquared;
    }

    /**
     * Systematic resampling followed by roughening.
     *
     * <p>Systematic resampling draws NUM_PARTICLES new particles proportional to
     * their weights using a single random offset, which has lower variance than
     * multinomial resampling.</p>
     *
     * <p>Roughening adds small Gaussian noise after resampling. Without it,
     * particles copied from the same parent are identical and degenerate into
     * a point mass on the next predict step.</p>
     */
    private void resample() {
        float[][] newParticles = new float[NUM_PARTICLES][2];
        float step = 1.0f / NUM_PARTICLES;
        float cumulativeWeight = weights[0];
        float randomStart = random.nextFloat() * step;
        int j = 0;
        for (int i = 0; i < NUM_PARTICLES; i++) {
            float threshold = randomStart + i * step;
            while (threshold > cumulativeWeight && j < NUM_PARTICLES - 1) {
                j++;
                cumulativeWeight += weights[j];
            }
            newParticles[i][0] = particles[j][0];
            newParticles[i][1] = particles[j][1];
        }
        particles = newParticles;

        // Roughening: add small noise so copied particles diverge on the next step.
        // Wall-check each jitter so roughening doesn't land particles inside walls.
        for (int i = 0; i < NUM_PARTICLES; i++) {
            float rx = (float) (random.nextGaussian() * ROUGHENING_STD);
            float ry = (float) (random.nextGaussian() * ROUGHENING_STD);
            float roughenedX = particles[i][0] + rx;
            float roughenedY = particles[i][1] + ry;
            if (!intersectsWall(particles[i][0], particles[i][1], roughenedX, roughenedY)) {
                particles[i][0] = roughenedX;
                particles[i][1] = roughenedY;
            }
        }

        // Reset to uniform weights after resampling
        for (int i = 0; i < NUM_PARTICLES; i++) {
            weights[i] = 1.0f / NUM_PARTICLES;
        }
    }

    public void updateGNSS(LatLng gnssPosition, float gnssAccuracy) {
        if (!initialized) return; // ignore if not initialized

        // Convert GNSS position to ENU coordinates 
        float[] mesurementENU = UtilFunctions.convertWGS84ToENU(origin, gnssPosition);

        float mx = mesurementENU[0]; //easting value of the measurement
        float my = mesurementENU[1]; //northing value of the measurement

        // Indoors, GPS signals reflect off ceilings and walls, giving fixes several
        // metres off and often on the wrong side of a wall.  Inflate the uncertainty
        // so the GNSS observation cannot drag particles through wall segments.
        float effectiveAccuracy = walls.isEmpty() ? gnssAccuracy : gnssAccuracy * 3.0f;
        float variance = effectiveAccuracy * effectiveAccuracy; // Convert accuracy to variance sigma^2


        //gaussian likelihood function

        float weightSum = 0f;
        for (int i = 0; i < NUM_PARTICLES; i++) {
            float dx = particles[i][0] - mx;
            float dy = particles[i][1] - my;
            float distanceSquared = dx * dx + dy * dy;

            // Calculate weight using Gaussian likelihood
            weights[i] *= (float) Math.exp(-distanceSquared / (2 * variance));
            weightSum += weights[i];
        }

        if (weightSum < 1e-6) {
            for (int i = 0; i < NUM_PARTICLES; i++) {
                weights[i] = 1.0f / NUM_PARTICLES;
            }
        } else {
            for (int i = 0; i < NUM_PARTICLES; i++) {
                weights[i] /= weightSum;
            }
        }

        // Resample only when particle diversity is low (N_eff < N/2).
        // Resampling on every observation wastes diversity; N_eff-gating preserves it.
        float nEff = computeEffectiveSampleSize();
        if (nEff < NUM_PARTICLES / 2.0f) {
            resample();
            Log.d(TAG, "GNSS resampled: Neff=" + nEff);
        }
        Log.d(TAG, "GNSS update: pos=" + gnssPosition + " accuracy=" + gnssAccuracy + "m Neff=" + nEff);


    }

    public void updateWiFi(LatLng wifiPosition, float wifiAccuracy) {
        if (!initialized) return; // ignore if not initialized
        float[] mesurementENU = UtilFunctions.convertWGS84ToENU(origin, wifiPosition);
        float mx = mesurementENU[0]; //easting value of the measurement
        float my = mesurementENU[1]; //northing value of the measurement
        float variance = wifiAccuracy * wifiAccuracy; // Convert accuracy to variance sigma^2


        //gaussian likelihood function

        float weightSum = 0f;
        for (int i = 0; i < NUM_PARTICLES; i++) {
            float dx = particles[i][0] - mx;
            float dy = particles[i][1] - my;
            float distanceSquared = dx * dx + dy * dy;

            // Calculate weight using Gaussian likelihood
            weights[i] *= (float) Math.exp(-distanceSquared / (2 * variance));
            weightSum += weights[i];
        }

        if (weightSum < 1e-6) {
            for (int i = 0; i < NUM_PARTICLES; i++) {
                weights[i] = 1.0f / NUM_PARTICLES;
            }
        } else {
            for (int i = 0; i < NUM_PARTICLES; i++) {
                weights[i] /= weightSum;
            }
        }

        // Resample only when particle diversity is low (N_eff < N/2)
        float nEff = computeEffectiveSampleSize();
        if (nEff < NUM_PARTICLES / 2.0f) {
            resample();
            Log.d(TAG, "WiFi resampled: Neff=" + nEff);
        }
        Log.d(TAG, "WiFi update: pos=" + wifiPosition + " accuracy=" + wifiAccuracy + "m Neff=" + nEff);


    }

    public LatLng getFusedPosition() {
        if (!initialized) return null; // Return null if not initialized

        // Calculate weighted average of particles
        float meanEasting = 0f;
        float meanNorthing = 0f;
        for (int i = 0; i < NUM_PARTICLES; i++) {
            meanEasting += particles[i][0] * weights[i];
            meanNorthing += particles[i][1] * weights[i];
        }

        // Convert mean ENU back to WGS84 coordinates
        return UtilFunctions.convertENUToWGS84(origin, new float[]{meanEasting, meanNorthing, 0f});
    }

    /**
     * Returns the RMS spread of particles around their weighted mean, in metres.
     * Provides a live estimate of position uncertainty — large when particles are
     * spread out, small when they are tightly clustered.
     */
    public float getPositionUncertaintyMeters() {
        if (!initialized) return Float.MAX_VALUE;
        float meanE = 0f, meanN = 0f;
        for (int i = 0; i < NUM_PARTICLES; i++) {
            meanE += particles[i][0] * weights[i];
            meanN += particles[i][1] * weights[i];
        }
        float varE = 0f, varN = 0f;
        for (int i = 0; i < NUM_PARTICLES; i++) {
            float dE = particles[i][0] - meanE;
            float dN = particles[i][1] - meanN;
            varE += weights[i] * dE * dE;
            varN += weights[i] * dN * dN;
        }
        return (float) Math.sqrt(varE + varN);
    }

}
