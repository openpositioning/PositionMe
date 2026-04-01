
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

    public LatLng getOrigin() {
        return origin;
    }

    public void setWalls(List<float[]> wallSegments) {
        this.walls = wallSegments;
    }


    public static final float HEADING_NOISE_STD = 0.05f; //TODO CHANGE LATER? -- JAPJOT
    public static final float STRIDE_LENGTH_NOISE_STD = 0.05f; // WE CAN TUNE THESE NOISE PARAMETERS BASED ON EXPECTED SENSOR ACCURACY AND ENVIRONMENTAL CONDITIONS

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
                // Simple reflection: reverse direction and reduce stride (simulate energy loss)
                float reflectedHeading = (float) (noisyHeading + Math.PI); // reverse direction
                float reducedStride = noisyStride * 0.5f; // reduce stride to simulate energy loss

                particles[i][0] = oldX + reducedStride * (float) Math.cos(reflectedHeading);
                particles[i][1] = oldY + reducedStride * (float) Math.sin(reflectedHeading);

                //weights[i] = 0f; could also delete the particle
            } else {
                particles[i][0] = newX;
                particles[i][1] = newY;
            }
        }
        normalizeWeightsAndResample();
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

    private void normalizeWeightsAndResample() {
        float weightSum = 0f;
        for (float w : weights) weightSum += w;

        if (weightSum < 1e-6) {
            // All particles hit a wall or died, re-initialize around current best or uniform
            for (int i = 0; i < NUM_PARTICLES; i++) {
                weights[i] = 1.0f / NUM_PARTICLES;
                // Add some jitter to avoid collapse if we just reset positions
            }
            return;
        }

        for (int i = 0; i < NUM_PARTICLES; i++) {
            weights[i] /= weightSum;
        }

        // Resample only if effective sample size is low or every step (simple version: every step)
        resample();
    }

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
        for (int i = 0; i < NUM_PARTICLES; i++) weights[i] = 1.0f / NUM_PARTICLES;
    }

    public void updateGNSS(LatLng gnssPosition, float gnssAccuracy) {
        if (!initialized) return; // ignore if not initialized

        // Convert GNSS position to ENU coordinates 
        float[] mesurementENU = UtilFunctions.convertWGS84ToENU(origin, gnssPosition);

        float mx = mesurementENU[0]; //easting value of the measurement
        float my = mesurementENU[1]; //northing value of the measurement

        float variance = gnssAccuracy * gnssAccuracy; // Convert accuracy to variance sigma^2


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
            // Avoid division by zero, reinitialize weights uniformly
            for (int i = 0; i < NUM_PARTICLES; i++) {
                weights[i] = 1.0f / NUM_PARTICLES;
            }
        } else {
            // Normalize weights
            for (int i = 0; i < NUM_PARTICLES; i++) {
                weights[i] /= weightSum;
            }
        }

        resample();
        Log.d(TAG, "GNSS UPDATE" + " GNSS position: " + gnssPosition + " accuracy: " + gnssAccuracy + "m" + (mx + ", " + my));


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
            // Avoid division by zero, reinitialize weights uniformly
            for (int i = 0; i < NUM_PARTICLES; i++) {
                weights[i] = 1.0f / NUM_PARTICLES;
            }
        } else {
            // Normalize weights
            for (int i = 0; i < NUM_PARTICLES; i++) {
                weights[i] /= weightSum;
            }
        }

        resample();
        Log.d(TAG, "wifi UPDATE" + " WiFi position: " + wifiPosition + " accuracy: " + wifiAccuracy + "m" + (mx + ", " + my));


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

}
