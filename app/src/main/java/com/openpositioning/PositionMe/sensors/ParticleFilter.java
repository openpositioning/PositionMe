
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


    public static final float HEADING_NOISE_STD = 0.01f; //TODO CHANGE LATER??
    public static final float STRIDE_LENGTH_NOISE_STD = 0.01f; // WE CAN TUNE THESE NOISE PARAMETERS BASED ON EXPECTED SENSOR ACCURACY AND ENVIRONMENTAL CONDITIONS

    public void predict(float deltaEasting, float deltaNorthing) {
        if (!initialized) return; // ignore if not initialized


        float stride = (float) Math.sqrt(deltaEasting * deltaEasting + deltaNorthing * deltaNorthing);
        float heading = (float) Math.atan2(deltaNorthing, deltaEasting); // calculate heading from deltas

        for (int i = 0; i < NUM_PARTICLES; i++) {
            // Add noise to heading and stride
            float noisyHeading = heading + (float) (random.nextGaussian() * HEADING_NOISE_STD);
            float noisyStride = stride + (float) (random.nextGaussian() * STRIDE_LENGTH_NOISE_STD);

            // Update particle position based on noisy heading and stride
            particles[i][0] += noisyStride * Math.cos(noisyHeading); // update east
            particles[i][1] += noisyStride * Math.sin(noisyHeading); // update north
        }

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
            weights[i] = (float) Math.exp(-distanceSquared / (2 * variance));
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

        //resample particles

        float[][] newParticles = new float[NUM_PARTICLES][2]; // New array for resampled particles
        float step = 1.0f / NUM_PARTICLES; // Step size for resampling
        float cumulativeWeight = weights[0];  // Cumulative weight for resampling

        float random1 = random.nextFloat() * step; //random start point for resampling

        int j = 0; // Index for particles
        for (int i = 0; i < NUM_PARTICLES; i++) {
            float threshold = random1 + i * step; // Threshold for selecting particle
            while (threshold > cumulativeWeight && j < NUM_PARTICLES - 1) { // Move to the next particle
                j++;

                cumulativeWeight += weights[j]; // Move to next particle
            }
            newParticles[i][0] = particles[j][0]; // Resample east
            newParticles[i][1] = particles[j][1]; // Resample north
        }
        particles = newParticles; // Replace old particles with resampled particles

        //reset
        for (int i = 0; i < NUM_PARTICLES; i++) {
            weights[i] = 1.0f / NUM_PARTICLES; // Reset weights to uniform after resampling
        }
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
            weights[i] = (float) Math.exp(-distanceSquared / (2 * variance));
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

        //resample particles

        float[][] newParticles = new float[NUM_PARTICLES][2]; // New array for resampled particles
        float step = 1.0f / NUM_PARTICLES; // Step size for resampling
        float cumulativeWeight = weights[0];  // Cumulative weight for resampling

        float random1 = random.nextFloat() * step; //random start point for resampling

        int j = 0; // Index for particles
        for (int i = 0; i < NUM_PARTICLES; i++) {
            float threshold = random1 + i * step; // Threshold for selecting particle
            while (threshold > cumulativeWeight && j < NUM_PARTICLES - 1) { // Move to the next particle
                j++;

                cumulativeWeight += weights[j]; // Move to next particle
            }
            newParticles[i][0] = particles[j][0]; // Resample east
            newParticles[i][1] = particles[j][1]; // Resample north
        }
        particles = newParticles; // Replace old particles with resampled particles

        //reset
        for (int i = 0; i < NUM_PARTICLES; i++) {
            weights[i] = 1.0f / NUM_PARTICLES; // Reset weights to uniform after resampling
        }
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

